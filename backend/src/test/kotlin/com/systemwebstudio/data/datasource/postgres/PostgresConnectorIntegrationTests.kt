package com.systemwebstudio.data.datasource.postgres

import com.systemwebstudio.data.InMemoryQueryCatalog
import com.systemwebstudio.data.LogCapture
import com.systemwebstudio.data.datasource.ConnectionTestResult
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceTypes
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.ResolvedCredential
import com.systemwebstudio.data.datasource.SystemHostResolver
import com.systemwebstudio.tenancy.TenantContext
import com.systemwebstudio.data.discovery.EntityKind
import com.systemwebstudio.data.discovery.NormalizedType
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.PageSpec
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.QueryRequest
import com.systemwebstudio.data.query.SqlQueryDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * The PostgreSQL connector against a real server (Testcontainers, same image as the platform tests). Skipped when Docker is not available.
 *
 * The container speaks plain TCP, so these tests build the connector with `JdbcPgConnectionFactory(enforceTls = false)` — a switch that exists
 * only in code, never in a data source's configuration — and allow-list the container's host explicitly, which is also how the
 * "private host only through the explicit allow-list" rule is exercised.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresConnectorIntegrationTests {
    private lateinit var pg: PostgreSQLContainer
    private val tenant = UUID.randomUUID()
    private val dsId = UUID.randomUUID()
    private val roPass = "ro-pass-TOPSECRET-1"
    private val rwPass = "rw-pass-TOPSECRET-2"
    private val ro get() = ResolvedCredential.of(mapOf("username" to "ro_user", "password" to roPass))
    private val rw get() = ResolvedCredential.of(mapOf("username" to "rw_user", "password" to rwPass))

    @BeforeAll fun start() {
        pg = PostgreSQLContainer(DockerImageName.parse("postgres:17.6")).apply { start() }
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
            c.createStatement().use { st ->
                st.execute("""CREATE SCHEMA shop; CREATE SCHEMA hidden;
                    CREATE TABLE shop.orders (id int PRIMARY KEY, customer text NOT NULL, total numeric(10,2), shipped boolean, placed timestamptz, note jsonb);
                    INSERT INTO shop.orders VALUES (1,'ann',10.50,true,'2026-01-01T10:00:00Z','{"a":1}'), (2,'bob',99.99,false,'2026-01-02T10:00:00Z',NULL),
                        (3,'cyd',150.00,true,'2026-01-03T10:00:00Z','[]'), (4,'dee',250.25,false,NULL,NULL), (5,'eve',500.00,true,NULL,NULL);
                    CREATE TABLE shop.order_items (id int PRIMARY KEY, order_id int NOT NULL REFERENCES shop.orders(id), sku text NOT NULL, buyer_email text);
                    INSERT INTO shop.order_items VALUES (1, 1, 'sku-1', 'ann@example.com'), (2, 1, 'sku-2', 'bob@example.com');
                    CREATE VIEW shop.big_orders AS SELECT id, total FROM shop.orders WHERE total > 100;
                    CREATE TABLE hidden.secrets (k text, v text); INSERT INTO hidden.secrets VALUES ('k','do-not-discover');
                    CREATE ROLE ro_user LOGIN PASSWORD '$roPass' NOSUPERUSER NOCREATEDB NOCREATEROLE;
                    GRANT USAGE ON SCHEMA shop TO ro_user; GRANT SELECT ON ALL TABLES IN SCHEMA shop TO ro_user;
                    CREATE ROLE rw_user LOGIN PASSWORD '$rwPass' NOSUPERUSER NOCREATEDB NOCREATEROLE;
                    GRANT USAGE ON SCHEMA shop TO rw_user; GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA shop TO rw_user;""")
            }
        }
    }
    @AfterAll fun stop() { pg.stop() }

    private fun config(extra: Map<String, String> = emptyMap()) =
        mapOf("host" to pg.host, "port" to pg.getMappedPort(5432).toString(), "database" to pg.databaseName, "schemas" to "shop") + extra
    private fun ref(extra: Map<String, String> = emptyMap()) = DataSourceRef(dsId, tenant, DataSourceTypes.POSTGRES, config(extra))
    private val allowContainerHost get() = PostgresTargetPolicy(allowedPrivateHosts = setOf("${pg.host.lowercase()}:${pg.getMappedPort(5432)}"))
    private fun connector(vararg defs: SqlQueryDefinition) = PostgresConnector(InMemoryQueryCatalog(*defs), allowContainerHost, SystemHostResolver, JdbcPgConnectionFactory(enforceTls = false))
    private fun req(id: String, params: Map<String, Any> = emptyMap(), page: PageSpec? = null) =
        QueryRequest(id, params.mapValues { DataJson.toNode(it.value) }, page, TenantContext(tenant, null))
    private fun failure(block: () -> Unit): ConnectorFailure { try { block() } catch (e: ConnectorFailure) { return e }; throw AssertionError("expected a ConnectorFailure") }
    private fun superCount() = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
        c.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM shop.orders").use { it.next(); it.getInt(1) } } }

    private val orders = SqlQueryDefinition("orders", tenant, dsId,
        "SELECT id, customer, total, shipped, placed, note FROM shop.orders WHERE total >= :min ORDER BY id", listOf(QueryParamSpec("min", ParamType.NUMBER)), maxRows = 100)

    // ---------------------------------------------------------------- connection test

    @Test fun `a SELECT-only role passes the connection test, a writer passes with a warning, a superuser is refused`() {
        val c = connector()
        val ok = c.test(ref(), ro) as ConnectionTestResult.Ok
        assertThat(ok.warnings).isEmpty()
        val rwOk = c.test(ref(), rw) as ConnectionTestResult.Ok
        assertThat(rwOk.warnings.single()).contains("can write")
        val su = c.test(ref(), ResolvedCredential.of(mapOf("username" to pg.username, "password" to pg.password))) as ConnectionTestResult.Failed
        assertThat(su.code).isEqualTo(FailureCodes.ROLE_TOO_PRIVILEGED)
    }

    @Test fun `a wrong password is a typed failure and appears nowhere`() {
        LogCapture().use { logs ->
            val r = connector().test(ref(), ResolvedCredential.of(mapOf("username" to "ro_user", "password" to "wrong-TOPSECRET-guess"))) as ConnectionTestResult.Failed
            assertThat(r.code).isEqualTo(FailureCodes.AUTH_REJECTED)
            for (text in listOf(r.message, r.toString(), logs.text)) { assertThat(text).doesNotContain("TOPSECRET"); assertThat(text).doesNotContain("ro_user"); assertThat(text).doesNotContain(pg.host + ":") }
        }
    }

    @Test fun `without the explicit allow-list a local or private database is refused`() {
        val c = PostgresConnector(InMemoryQueryCatalog(), PostgresTargetPolicy(), SystemHostResolver, JdbcPgConnectionFactory(enforceTls = false))
        assertThat((c.test(ref(), ro) as ConnectionTestResult.Failed).code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
    }

    // ---------------------------------------------------------------- queries

    @Test fun `parameters are bound, columns are typed, and rows come back as JSON`() {
        val r = connector(orders).executor().execute(req("orders", mapOf("min" to 100)), ref(), ro)
        assertThat(r.rows.size).isEqualTo(3); assertThat(r.truncated).isFalse()
        val types = r.columns.associate { it.name to it.type }
        assertThat(types["id"]).isEqualTo(NormalizedType.INTEGER); assertThat(types["customer"]).isEqualTo(NormalizedType.STRING)
        assertThat(types["total"]).isEqualTo(NormalizedType.NUMBER); assertThat(types["shipped"]).isEqualTo(NormalizedType.BOOLEAN)
        assertThat(types["placed"]).isEqualTo(NormalizedType.TIMESTAMP); assertThat(types["note"]).isEqualTo(NormalizedType.JSON)
        assertThat(r.rows.map { DataJson.text(it["customer"]!!) }).containsExactly("cyd", "dee", "eve")
        assertThat(r.rows[0]["placed"]!!.isNull).isFalse(); assertThat(r.rows[1]["placed"]!!.isNull).isTrue()
    }

    @Test fun `a hostile parameter value is data, never SQL`() {
        val def = SqlQueryDefinition("byCustomer", tenant, dsId, "SELECT id FROM shop.orders WHERE customer = :c", listOf(QueryParamSpec("c", ParamType.STRING)))
        val before = superCount()
        for (v in listOf("x'; DROP TABLE shop.orders; --", "ann' OR '1'='1", "\"; DELETE FROM shop.orders; --"))
            assertThat(connector(def).executor().execute(req("byCustomer", mapOf("c" to v)), ref(), rw).rows.size).isEqualTo(0)
        assertThat(superCount()).isEqualTo(before)
    }

    @Test fun `row limits, paging and the byte cap bound the result`() {
        val limited = orders.copy(maxRows = 2)
        val r = connector(limited).executor().execute(req("orders", mapOf("min" to 0)), ref(), ro)
        assertThat(r.rows.size).isEqualTo(2); assertThat(r.truncated).isTrue()
        val page = connector(orders).executor().execute(req("orders", mapOf("min" to 0), PageSpec(3, 1)), ref(), ro)
        assertThat(page.rows.map { DataJson.text(it["customer"]!!) }).containsExactly("bob", "cyd", "dee"); assertThat(page.truncated).isTrue()
        val big = SqlQueryDefinition("big", tenant, dsId, "SELECT repeat('x', 400) AS big FROM generate_series(1, 50)")
        val capped = connector(big).executor().execute(req("big"), ref(mapOf("maxResponseBytes" to "1000")), ro)
        assertThat(capped.rows.size).isLessThan(5); assertThat(capped.truncated).isTrue()
    }

    @Test fun `a runaway query is stopped by the statement timeout`() {
        val slow = SqlQueryDefinition("slow", tenant, dsId, "SELECT count(*) AS n FROM generate_series(1, 5000000000)")
        val started = System.nanoTime()
        val f = failure { connector(slow).executor().execute(req("slow"), ref(mapOf("timeoutMs" to "800")), ro) }
        assertThat(f.code).isEqualTo(FailureCodes.TIMEOUT)
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(8_000)
    }

    // ---------------------------------------------------------------- read-only is enforced by the session, not only by the guard

    @Test fun `even a role with write privileges cannot write through a connector session, however the statement arrives`() {
        val sessions = PgSessions(allowContainerHost, SystemHostResolver, JdbcPgConnectionFactory(enforceTls = false))
        val before = superCount()
        val attempts = listOf(
            "INSERT INTO shop.orders (id, customer) VALUES (99, 'mallory')",
            "UPDATE shop.orders SET customer = 'mallory'",
            "DELETE FROM shop.orders",
            "TRUNCATE shop.orders",
            "SELECT 1; INSERT INTO shop.orders (id, customer) VALUES (98, 'mallory')",          // multi-statement straight to the driver, guard bypassed
            "CREATE TABLE shop.pwned (a int)",
            "SET LOCAL transaction_read_only = off",
            "SET TRANSACTION READ WRITE"
        )
        for (sql in attempts) {
            val f = failure { sessions.withConnection(ref(), rw) { conn, _ -> conn.createStatement().use { it.execute(sql) } } }
            assertThat(listOf(FailureCodes.READ_ONLY_VIOLATION, FailureCodes.QUERY_FAILED, FailureCodes.PERMISSION_DENIED)).contains(f.code)
        }
        // flip the flag and then write in the same transaction: the flip is refused (a query has already run), so the write cannot succeed either
        val f = failure { sessions.withConnection(ref(), rw) { conn, _ ->
            conn.createStatement().use { st -> runCatching { st.execute("SET LOCAL transaction_read_only = off") } }
            conn.createStatement().use { it.execute("INSERT INTO shop.orders (id, customer) VALUES (97, 'mallory')") } } }
        assertThat(listOf(FailureCodes.READ_ONLY_VIOLATION, FailureCodes.QUERY_FAILED)).contains(f.code)
        assertThat(superCount()).isEqualTo(before)
    }

    @Test fun `statements that write never reach the server through the executor either`() {
        val bad = SqlQueryDefinition("w", tenant, dsId, "SELECT 1; DELETE FROM shop.orders")
        assertThat(failure { connector(bad).executor().execute(req("w"), ref(), rw) }.code).isEqualTo(FailureCodes.INVALID_QUERY)
        val cte = SqlQueryDefinition("c", tenant, dsId, "WITH d AS (DELETE FROM shop.orders RETURNING *) SELECT * FROM d")
        assertThat(failure { connector(cte).executor().execute(req("c"), ref(), rw) }.code).isEqualTo(FailureCodes.INVALID_QUERY)
    }

    @Test fun `connections are closed after every call`() {
        connector(orders).executor().execute(req("orders", mapOf("min" to 0)), ref(), ro)
        runCatching { connector().executor().execute(req("nope"), ref(), ro) }
        Thread.sleep(300)
        val open = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c -> c.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name = 'xweb-data-connector'").use { it.next(); it.getInt(1) } } }
        assertThat(open).isEqualTo(0)
    }

    // ---------------------------------------------------------------- discovery

    @Test fun `discovery lists the configured schemas only and carries no secrets`() {
        LogCapture().use { logs ->
            val s = connector().discovery().discover(ref(), ro)
            assertThat(s.entities.map { it.name }).containsExactlyInAnyOrder("orders", "order_items", "big_orders")
            assertThat(s.entities.all { it.schema == "shop" }).isTrue()
            assertThat(s.entities.first { it.name == "big_orders" }.kind).isEqualTo(EntityKind.VIEW)
            val total = s.entities.first { it.name == "orders" }.fields.first { it.name == "total" }
            assertThat(total.type).isEqualTo(NormalizedType.NUMBER); assertThat(total.nullable).isTrue()
            assertThat(s.entities.first { it.name == "orders" }.fields.first { it.name == "customer" }.nullable).isFalse()
            for (text in listOf(s.toString(), logs.text)) { assertThat(text).doesNotContain("do-not-discover"); assertThat(text).doesNotContain("TOPSECRET"); assertThat(text).doesNotContain("secrets") }
        }
    }

    @Test fun `discovery reports primary keys and foreign keys and masks samples before they leave the connector`() {
        val s = connector().discovery().discover(ref(), ro, com.systemwebstudio.data.discovery.DiscoveryOptions(3))
        val orders = s.entities.first { it.name == "orders" }; val items = s.entities.first { it.name == "order_items" }
        assertThat(orders.primaryKey).containsExactly("id")
        assertThat(items.relations).hasSize(1)
        assertThat(items.relations.single().fromFields).containsExactly("order_id"); assertThat(items.relations.single().toEntity).isEqualTo("orders")
        assertThat(orders.sample).hasSize(3)
        assertThat(items.sample).hasSize(2)
        val text = items.sample.toString()
        for (leak in listOf("ann@example.com", "bob@example.com")) assertThat(text).doesNotContain(leak)     // a personal column never leaves the connector unmasked
    }
}
