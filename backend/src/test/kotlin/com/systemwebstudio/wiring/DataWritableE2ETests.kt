package com.systemwebstudio.wiring

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.systemwebstudio.app.definition.AppDefinitionTestSupport
import com.systemwebstudio.data.datasource.DataConnectorRegistry
import com.systemwebstudio.data.datasource.SystemHostResolver
import com.systemwebstudio.data.datasource.postgres.JdbcPgConnectionFactory
import com.systemwebstudio.data.datasource.postgres.PostgresConnector
import com.systemwebstudio.data.datasource.postgres.PostgresTargetPolicy
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryCatalog
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.identity.UserEntity
import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.support.IntegrationTestBase
import com.systemwebstudio.version.SchemaRepository
import com.systemwebstudio.wiring.persistence.JdbcMutationCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MvcResult
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The customer's database: a SECOND PostgreSQL (the platform's own database is never a valid target). Started once for the JVM; a role `shop_writer`
 * with INSERT/UPDATE/DELETE on `shop` only (no superuser, no CREATEROLE...), a unique and a check constraint, and a trigger that makes customer `SLOW`
 * take 4 s (past the data source's time limit) and counts every attempt in a sequence — a sequence is not rolled back, so it proves how many times the
 * statement was really sent to the database.
 */
object ShopDb {
    const val PASSWORD = "shop-writer-PASS-7c2d"
    val container: PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:17.6")).apply { start() }

    init {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
            c.createStatement().use { st ->
                st.execute("""CREATE SCHEMA shop;
                    CREATE SEQUENCE shop.slow_attempts;
                    CREATE TABLE shop.orders (id bigserial PRIMARY KEY, customer text NOT NULL, amount numeric(10,2) CONSTRAINT amount_not_negative CHECK (amount >= 0),
                        created_at timestamptz NOT NULL DEFAULT now(), CONSTRAINT orders_customer_key UNIQUE (customer));
                    CREATE FUNCTION shop.orders_slow() RETURNS trigger LANGUAGE plpgsql AS ${'$'}f${'$'}
                        BEGIN IF NEW.customer = 'SLOW' THEN PERFORM nextval('shop.slow_attempts'); PERFORM pg_sleep(4); END IF; RETURN NEW; END ${'$'}f${'$'};
                    CREATE TRIGGER orders_slow BEFORE INSERT ON shop.orders FOR EACH ROW EXECUTE FUNCTION shop.orders_slow();
                    CREATE ROLE shop_writer LOGIN PASSWORD '$PASSWORD' NOSUPERUSER NOCREATEDB NOCREATEROLE;
                    GRANT USAGE ON SCHEMA shop TO shop_writer; GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA shop TO shop_writer;
                    GRANT USAGE ON ALL SEQUENCES IN SCHEMA shop TO shop_writer;""")
            }
        }
    }

    fun rows(sql: String, vararg args: Any): List<Map<String, Any?>> = DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs ->
                val md = rs.metaData; val out = ArrayList<Map<String, Any?>>()
                while (rs.next()) out += (1..md.columnCount).associate { md.getColumnLabel(it) to rs.getObject(it) }
                out
            }
        }
    }
    fun orders(customer: String) = rows("SELECT id, customer, amount, created_at FROM shop.orders WHERE customer = ?", customer)
    fun slowAttempts(): Long = rows("SELECT CASE WHEN is_called THEN last_value ELSE 0 END AS n FROM shop.slow_attempts").single()["n"] as Long
}

/**
 * B-C0-W-03 + B-C0-W-04, the whole chain, nothing in memory and nothing mocked:
 *
 *   Management API (HTTP, C1 authorisation) -> data source + encrypted credential (V28) -> LIVE binding (HTTP) -> published app -> action over HTTP
 *   -> DefaultDataGateway (idempotency in V28) -> the real PostgresConnector -> a real second PostgreSQL -> a real row -> the runtime result or error.
 *
 * The only test-only parts: the customer's database speaks plain TCP (`JdbcPgConnectionFactory(enforceTls = false)`, a code switch that cannot be reached from
 * configuration) and its host is on the connector's private-host allow-list; C4's run stores stay in memory (`allow-volatile-stores`, the dev/E2E switch of D-C0-20).
 */
@TestPropertySource(
    properties = [
        "app.workflow.enabled=true", "app.data-platform.enabled=true", "app.workflow.allow-volatile-stores=true", "app.workflow.worker-delay-ms=3600000",
        "app.secrets.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    ]
)
class DataWritableE2ETests : IntegrationTestBase() {
    @TestConfiguration
    class Cfg {
        /** the REAL PostgreSQL connector (not a fake), pointed at the second container */
        @Bean @Primary
        fun shopConnectorRegistry(queries: QueryCatalog): DataConnectorRegistry = DataConnectorRegistry(listOf(
            PostgresConnector(queries, PostgresTargetPolicy(allowedPrivateHosts = setOf("${ShopDb.container.host.lowercase()}:${ShopDb.container.getMappedPort(5432)}")), SystemHostResolver, JdbcPgConnectionFactory(enforceTls = false))
        ))
    }

    @Autowired lateinit var schemas: SchemaRepository

    private val wrongPassword = "wrong-PASS-0000-guess"
    private val sample: JsonNode get() = AppDefinitionTestSupport.resource("valid-v2-sample.json")

    private class Env(val sc: Scenario, val tenant: UUID, val adminUser: UserEntity, val admin: ApiSession, val dsId: UUID) {
        val sources = "/api/v1/workspaces/${sc.ws}/data-sources"
        val source = "$sources/$dsId"
        val bindings = "${sc.base}/data-bindings"
        val runtime = "${sc.base}/app-runtime"
    }

    /** the transcript of every HTTP answer, to prove afterwards that no secret was in any of them */
    private val bodies: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
    private fun keep(r: MvcResult): MvcResult { bodies += r.response.contentAsString; return r }
    private fun status(r: MvcResult) = r.response.status
    private fun count(sql: String, vararg args: Any): Long = jdbc.queryForObject(sql, Long::class.java, *args)!!

    private fun pgConfig(extra: Map<String, Any> = emptyMap()): Map<String, Any> = mapOf(
        "host" to ShopDb.container.host, "port" to ShopDb.container.getMappedPort(5432), "database" to ShopDb.container.databaseName,
        "schemas" to "shop", "writable" to true, "timeoutMs" to 1500
    ) + extra

    // ------------------------------------------------------------------------------------------------ setup: the way an operator does it, over HTTP

    /** create the data source through the Management API, register the approved mutation, publish the app, bind LIVE through the API */
    private fun env(bindLive: Boolean = true): Env {
        val sc = scenario()
        val tenant = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, sc.ws)!!
        val adminUser = fx.user("wsadmin").also { fx.member(sc.ws, it, "WORKSPACE_ADMIN") }
        val admin = sessionFor(adminUser.username)

        val created = keep(admin.post("/api/v1/workspaces/${sc.ws}/data-sources", json.writeValueAsString(mapOf(
            "name" to ("shop-" + UUID.randomUUID().toString().take(8)), "type" to "postgres", "config" to pgConfig(),
            "credential" to mapOf("username" to "shop_writer", "password" to ShopDb.PASSWORD)))))
        assertThat(status(created)).describedAs(created.response.contentAsString).isEqualTo(201)
        val dsId = UUID.fromString(admin.body(created).get("id").asString())

        // the approved operation (there is no management endpoint for operations yet: handoff B-C0-W-03 limitation)
        JdbcMutationCatalog(jdbc).save(MutationDefinition("orders.create", tenant, dsId, MutationKind.CREATE, "shop.orders returning=id",
            listOf(QueryParamSpec("customer", ParamType.STRING), QueryParamSpec("amount", ParamType.NUMBER, false)), emptyList(), "orders"))

        val document = sample
        schemas.upsertSchema(sc.projectId, sc.ws, document)
        val versionId = schemas.insertVersion(sc.ws, sc.projectId, schemas.nextVersionNumber(sc.projectId), document, "EDIT", "published", null, null, null, sc.user.id)
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) VALUES (?, ?, ?, ?, ?, 'PRIVATE', 'RUNNING', 'mock')",
            UUID.randomUUID(), sc.ws, sc.projectId, versionId, sc.user.id)

        val env = Env(sc, tenant, adminUser, admin, dsId)
        if (bindLive) {
            val bound = keep(admin.put("${env.bindings}/LIVE/erp-db", """{"dataSourceId":"$dsId"}"""))
            assertThat(status(bound)).describedAs(bound.response.contentAsString).isEqualTo(200)
        }
        return env
    }

    private fun act(e: Env, key: String, customer: String, session: ApiSession = e.admin): MvcResult = keep(session.post("${e.runtime}/actions/create-order/execute",
        """{"idempotencyKey":"$key","inputs":{"customer":${json.writeValueAsString(customer)}},"trigger":{"eventName":"contact-1.onSubmit"}}"""))
    private fun errorCode(e: Env, r: MvcResult): String? = e.admin.body(r).get("error")?.get("code")?.asString()
    private fun retryable(e: Env, r: MvcResult): Boolean = e.admin.body(r).get("error").get("retryable").asBoolean()
    private fun idem(e: Env) = jdbc.queryForList("SELECT state, idem_key, affected, output_json::text AS output_json FROM data_idempotency WHERE tenant_id = ? AND data_source_id = ? ORDER BY created_at", e.tenant, e.dsId)

    private fun assertNoSecret(vararg secrets: String) {
        for (s in secrets) {
            assertThat(bodies.filter { it.contains(s) }).describedAs("HTTP answers").isEmpty()
            assertThat(count("SELECT count(*) FROM audit_events WHERE new_value::text LIKE ? OR old_value::text LIKE ?", "%$s%", "%$s%")).describedAs("audit events").isZero()
            assertThat(count("SELECT count(*) FROM data_credentials WHERE ciphertext LIKE ?", "%$s%")).describedAs("credential rows hold ciphertext only").isZero()
            for (table in listOf("data_sources", "data_mutations", "data_queries", "data_idempotency", "data_source_bindings", "source_schemas"))
                assertThat(count("SELECT count(*) FROM $table t WHERE t::text LIKE ?", "%$s%")).describedAs(table).isZero()
        }
    }

    /** our own packages at DEBUG (the connector logs its failures there), frameworks left at their default level; fails the test if a secret is ever logged */
    private class Logs : AutoCloseable {
        private val ours = LoggerFactory.getLogger("com.systemwebstudio") as Logger
        private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        private val appender = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
        private val before: Level? = ours.level
        init { ours.level = Level.DEBUG }
        val text: String get() = appender.list.toList().joinToString("\n") { e -> e.formattedMessage + (e.throwableProxy?.let { " | " + it.className + ": " + it.message } ?: "") }
        override fun close() { root.detachAppender(appender); ours.level = before }
    }

    // ------------------------------------------------------------------------------------------------ the full chain

    @Test
    fun `Management API to a real row - create, credential, test, LIVE binding, INSERT, replay, constraint, timeout, wrong password, disable, read-only, delete`() {
        Logs().use { logs ->
            val e = env()
            val before = ShopDb.rows("SELECT count(*) AS n FROM shop.orders").single()["n"] as Long
            val credentialRows = count("SELECT count(*) FROM data_credentials WHERE tenant_id = ?", e.tenant)      // this source's own row plus any source another test left in the shared tenant

            // -- the data source is registered with metadata only
            val got = keep(e.admin.get(e.source)); val ds = e.admin.body(got)
            assertThat(status(got)).isEqualTo(200)
            assertThat(ds.get("type").asString()).isEqualTo("postgres"); assertThat(ds.get("hasCredential").asBoolean()).isTrue(); assertThat(ds.get("config").get("writable").asString()).isEqualTo("true")
            val cred = keep(e.admin.get("${e.source}/credential")); val meta = e.admin.body(cred)
            assertThat(DataJson.keys(meta)).containsExactlyInAnyOrder("configured", "type", "keys", "updatedAt", "updatedBy")
            assertThat(meta.get("configured").asBoolean()).isTrue(); assertThat(meta.get("type").asString()).isEqualTo("postgres")
            assertThat(DataJson.elements(meta.get("keys")).map { it.asString() }).containsExactly("username", "password")
            assertThat(meta.get("updatedBy").asString()).describedAs("who configured it").isEqualTo(e.adminUser.id.toString())

            // -- the REAL connector reaches the REAL database with the stored credential
            val test = keep(e.admin.post("${e.source}/test", "{}"))
            assertThat(status(test)).isEqualTo(200); assertThat(e.admin.body(test).get("ok").asBoolean()).describedAs(test.response.contentAsString).isTrue()
            assertThat(DataJson.elements(e.admin.body(test).get("warnings"))).describedAs("the role can write: no advisory").isEmpty()
            assertThat(e.admin.body(keep(e.admin.get("${e.bindings}"))).get("items").size()).isEqualTo(1)

            // -- LIVE INSERT: a real row, the runtime says OK, the idempotency record is DONE and holds a derived key and the count only
            val first = act(e, "order-e2e-0001", "ACME")
            assertThat(status(first)).describedAs(first.response.contentAsString).isEqualTo(200)
            assertThat(e.admin.body(first).get("status").asString()).isEqualTo("OK")
            val acme = ShopDb.orders("ACME").single()
            assertThat(acme["created_at"]).isNotNull()
            val rows = idem(e)
            assertThat(rows).hasSize(1); assertThat(rows.single()["state"]).isEqualTo("DONE"); assertThat(rows.single()["affected"]).isEqualTo(1L)
            assertThat(rows.single()["idem_key"] as String).matches("[A-Za-z0-9_-]{43}").isNotEqualTo("order-e2e-0001")
            assertThat(rows.single()["output_json"] as String).contains((acme["id"] as Long).toString())            // RETURNING id travelled back

            // -- replay: the same operation key writes nothing again
            val replay = act(e, "order-e2e-0001", "ACME")
            assertThat(status(replay)).isIn(200, 409)
            assertThat(ShopDb.orders("ACME")).hasSize(1)
            assertThat(idem(e)).hasSize(1)

            // -- concurrent duplicates (two sessions of the same user, the same key): exactly one row
            val race = listOf(sessionFor(e.adminUser.username), sessionFor(e.adminUser.username))
            val gate = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
            val results = race.map { s -> pool.submit(Callable { gate.await(); act(e, "order-e2e-race", "RACE", s) }) }
            gate.countDown()
            val statuses = results.map { status(it.get(30, TimeUnit.SECONDS)) }
            pool.shutdown()
            assertThat(statuses).allMatch { it == 200 || it == 409 }
            assertThat(statuses).contains(200)
            assertThat(ShopDb.orders("RACE")).describedAs("two concurrent identical requests write one row").hasSize(1)

            // -- a unique-constraint violation: the database says no, nothing is written, the runtime says MUTATION_REJECTED (definite, never retryable), the key is released
            val idemBefore = idem(e).size
            val dup = act(e, "order-e2e-dup0", "ACME")
            assertThat(status(dup)).isEqualTo(422)
            assertThat(e.admin.body(dup).get("status").asString()).isEqualTo("FAILED")
            assertThat(errorCode(e, dup)).isEqualTo("MUTATION_REJECTED"); assertThat(retryable(e, dup)).isFalse()
            assertThat(dup.response.contentAsString).doesNotContain("orders_customer_key").doesNotContain("duplicate key").doesNotContain("shop_writer")
            assertThat(ShopDb.orders("ACME")).hasSize(1)
            assertThat(idem(e)).describedAs("a definite rejection releases the key: no UNKNOWN row").hasSize(idemBefore)
            assertThat(idem(e).map { it["state"] }).doesNotContain("UNKNOWN")
            val globex = act(e, "order-e2e-0002", "Globex")                                                          // the next operation is not blocked
            assertThat(status(globex)).isEqualTo(200); assertThat(ShopDb.orders("Globex")).hasSize(1)

            // -- a statement that exceeds the time limit: outcome UNKNOWN, 409, never retryable, the key stays reserved, and a retry does NOT send the statement again
            assertThat(ShopDb.slowAttempts()).isZero()
            val slow = act(e, "order-e2e-slow", "SLOW")
            assertThat(status(slow)).isEqualTo(409)
            assertThat(errorCode(e, slow)).isEqualTo("IDEMPOTENCY_OUTCOME_UNKNOWN"); assertThat(retryable(e, slow)).isFalse()
            assertThat(ShopDb.orders("SLOW")).describedAs("the server cancelled and rolled the statement back").isEmpty()
            assertThat(ShopDb.slowAttempts()).isEqualTo(1L)
            assertThat(idem(e).map { it["state"] }).contains("UNKNOWN")
            val retry = act(e, "order-e2e-slow", "SLOW")
            assertThat(status(retry)).isEqualTo(409)
            assertThat(errorCode(e, retry)).isEqualTo("IDEMPOTENCY_OUTCOME_UNKNOWN"); assertThat(retryable(e, retry)).isFalse()
            assertThat(ShopDb.slowAttempts()).describedAs("an unknown outcome is never handed out for a second run").isEqualTo(1L)
            assertThat(idem(e).count { it["state"] == "UNKNOWN" }).isEqualTo(1)

            // -- a wrong password (rotated through the API): the connection test and the write say AUTH_REJECTED, nothing is written, the key is released
            val rotate = keep(e.admin.put("${e.source}/credential", """{"credential":{"username":"shop_writer","password":"$wrongPassword"}}"""))
            assertThat(status(rotate)).isEqualTo(200); assertThat(e.admin.body(rotate).get("configured").asBoolean()).isTrue()
            val badTest = keep(e.admin.post("${e.source}/test", "{}"))
            assertThat(status(badTest)).isEqualTo(200)
            assertThat(e.admin.body(badTest).get("ok").asBoolean()).isFalse(); assertThat(e.admin.body(badTest).get("code").asString()).isEqualTo("AUTH_REJECTED")
            val unknownBefore = idem(e).size
            val denied = act(e, "order-e2e-auth", "AUTHFAIL")
            assertThat(status(denied)).isNotEqualTo(200)
            assertThat(errorCode(e, denied)).isEqualTo("AUTH_REJECTED"); assertThat(retryable(e, denied)).isFalse()
            assertThat(ShopDb.orders("AUTHFAIL")).isEmpty(); assertThat(idem(e)).hasSize(unknownBefore)
            // restore the right credential: service resumes (the old secret was never read, the new one simply replaced it)
            keep(e.admin.put("${e.source}/credential", """{"credential":{"username":"shop_writer","password":"${ShopDb.PASSWORD}"}}"""))
            assertThat(e.admin.body(keep(e.admin.post("${e.source}/test", "{}"))).get("ok").asBoolean()).isTrue()
            assertThat(status(act(e, "order-e2e-0003", "Initech"))).isEqualTo(200); assertThat(ShopDb.orders("Initech")).hasSize(1)
            assertThat(count("SELECT count(*) FROM data_credentials WHERE tenant_id = ?", e.tenant)).describedAs("replaced credentials are destroyed, not accumulated").isEqualTo(credentialRows)

            // -- disabling the data source stops writes (DISABLED, nothing sent); enabling resumes
            assertThat(status(keep(e.admin.patch(e.source, """{"status":"DISABLED"}""")))).isEqualTo(200)
            val off = act(e, "order-e2e-off0", "DISABLEDCO")
            assertThat(status(off)).isNotEqualTo(200); assertThat(errorCode(e, off)).isEqualTo("DISABLED"); assertThat(ShopDb.orders("DISABLEDCO")).isEmpty()
            assertThat(status(keep(e.admin.patch(e.source, """{"status":"ACTIVE"}""")))).isEqualTo(200)
            assertThat(status(act(e, "order-e2e-0004", "Umbrella"))).isEqualTo(200)

            // -- turning writable off makes the very same operation a definite READ_ONLY_VIOLATION; turning it on again resumes
            assertThat(status(keep(e.admin.patch(e.source, json.writeValueAsString(mapOf("config" to pgConfig(mapOf("writable" to false)))))))).isEqualTo(200)
            val ro = act(e, "order-e2e-ro00", "READONLYCO")
            assertThat(status(ro)).isNotEqualTo(200); assertThat(errorCode(e, ro)).isEqualTo("READ_ONLY_VIOLATION"); assertThat(retryable(e, ro)).isFalse()
            assertThat(ShopDb.orders("READONLYCO")).isEmpty()
            assertThat(status(keep(e.admin.patch(e.source, json.writeValueAsString(mapOf("config" to pgConfig())))))).isEqualTo(200)
            assertThat(status(act(e, "order-e2e-0005", "Wayne"))).isEqualTo(200)

            // -- a data source an application still uses cannot be deleted; unbind first, then the LIVE action changes nothing
            val blocked = keep(e.admin.delete(e.source))
            assertThat(status(blocked)).isEqualTo(409)
            assertThat(status(keep(e.admin.delete("${e.bindings}/LIVE/erp-db")))).isEqualTo(204)
            val total = (ShopDb.rows("SELECT count(*) AS n FROM shop.orders").single()["n"] as Long)
            val unbound = act(e, "order-e2e-unbound", "UNBOUNDCO")
            assertThat(status(unbound)).isNotEqualTo(200); assertThat(ShopDb.orders("UNBOUNDCO")).isEmpty()
            assertThat(ShopDb.rows("SELECT count(*) AS n FROM shop.orders").single()["n"]).isEqualTo(total)

            // -- the timed-out write above left an UNKNOWN idempotency record: a write that may have been applied. Its evidence is never destroyed, so the data source
            //    cannot be deleted while it exists (Management API contract §3.1); once its retention ends and the purge removed it, the delete goes through
            val blockedByUnknown = keep(e.admin.delete(e.source))
            assertThat(status(blockedByUnknown)).isEqualTo(409); assertThat(e.admin.body(blockedByUnknown).get("code").asString()).isEqualTo("CONFLICT")
            assertThat(status(keep(e.admin.get(e.source)))).describedAs("the refused delete removed nothing").isEqualTo(200)
            assertThat(idem(e).count { it["state"] == "UNKNOWN" }).isEqualTo(1)
            jdbc.update("UPDATE data_idempotency SET expires_at = now() - interval '1 minute' WHERE tenant_id = ? AND data_source_id = ? AND state = 'UNKNOWN'", e.tenant, e.dsId)
            assertThat(com.systemwebstudio.wiring.persistence.JdbcIdempotencyStore(jdbc).purgeExpired()).isGreaterThanOrEqualTo(1)

            // -- delete: the registration, its operations, credential and idempotency records go; the customer's business data stays
            assertThat(status(keep(e.admin.delete(e.source)))).isEqualTo(204)
            assertThat(status(keep(e.admin.get(e.source)))).isEqualTo(404)
            for (table in listOf("data_sources" to "id", "data_mutations" to "data_source_id", "data_idempotency" to "data_source_id", "data_source_bindings" to "data_source_id"))
                assertThat(count("SELECT count(*) FROM ${table.first} WHERE ${table.second} = ?", e.dsId)).describedAs(table.first).isZero()
            assertThat(count("SELECT count(*) FROM data_credentials WHERE tenant_id = ?", e.tenant)).describedAs("the deleted source takes its credential with it").isEqualTo(credentialRows - 1)
            assertThat(ShopDb.orders("ACME")).hasSize(1)
            val mine = ShopDb.rows("SELECT count(*) AS n FROM shop.orders WHERE customer IN ('ACME','RACE','Globex','Initech','Umbrella','Wayne')").single()["n"] as Long
            assertThat(mine).isEqualTo(6L)
            assertThat(ShopDb.rows("SELECT count(*) AS n FROM shop.orders").single()["n"] as Long).isEqualTo(before + 6)

            // -- no secret anywhere: not in an answer, an audit row, a table (other than as ciphertext) or a log line
            assertNoSecret(ShopDb.PASSWORD, wrongPassword)
            assertThat(logs.text).doesNotContain(ShopDb.PASSWORD).doesNotContain(wrongPassword)
            assertThat(count("SELECT count(*) FROM audit_events WHERE action IN ('DATASOURCE_CREATED','DATASOURCE_CREDENTIAL_ROTATED','DATASOURCE_STATUS_CHANGED','DATASOURCE_UPDATED','DATASOURCE_BINDING_CHANGED','DATASOURCE_DELETED') AND resource_id = ?", e.dsId.toString()))
                .describedAs("the management actions are on the audit trail").isGreaterThanOrEqualTo(8L)
        }
    }

    @Test
    fun `a TEST action is only previewed - the real database is not touched`() {
        val e = env(bindLive = false)
        assertThat(status(keep(e.admin.put("${e.bindings}/TEST/erp-db", """{"dataSourceId":"${e.dsId}"}""")))).isEqualTo(200)
        val before = ShopDb.rows("SELECT count(*) AS n FROM shop.orders").single()["n"]
        val r = keep(e.admin.post("${e.runtime}/actions/create-order/execute", """{"mode":"TEST","inputs":{"customer":"PREVIEWCO"},"trigger":{"eventName":"contact-1.onSubmit"}}"""))
        assertThat(status(r)).describedAs(r.response.contentAsString).isEqualTo(200)
        assertThat(e.admin.body(r).get("status").asString()).isEqualTo("WOULD_RUN")
        assertThat(ShopDb.orders("PREVIEWCO")).isEmpty(); assertThat(ShopDb.rows("SELECT count(*) AS n FROM shop.orders").single()["n"]).isEqualTo(before)
        assertThat(idem(e)).isEmpty()
        // LIVE is a different binding: unbound, so a LIVE action changes nothing either
        val live = act(e, "order-e2e-live-unbound", "LIVEUNBOUND")
        assertThat(status(live)).isNotEqualTo(200); assertThat(ShopDb.orders("LIVEUNBOUND")).isEmpty()
        assertNoSecret(ShopDb.PASSWORD)
    }
}
