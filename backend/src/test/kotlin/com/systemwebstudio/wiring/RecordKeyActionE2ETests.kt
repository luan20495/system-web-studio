package com.systemwebstudio.wiring

import com.systemwebstudio.app.definition.AppDefinitionTestSupport
import com.systemwebstudio.data.datasource.DataConnectorRegistry
import com.systemwebstudio.data.datasource.SystemHostResolver
import com.systemwebstudio.data.datasource.postgres.JdbcPgConnectionFactory
import com.systemwebstudio.data.datasource.postgres.PostgresConnector
import com.systemwebstudio.data.datasource.postgres.PostgresTargetPolicy
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryCatalog
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.support.IntegrationTestBase
import com.systemwebstudio.version.SchemaRepository
import com.systemwebstudio.wiring.persistence.JdbcMutationCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MvcResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.sql.DriverManager
import java.util.UUID

/**
 * FQ-ACT-02 through the WHOLE chain, nothing mocked: HTTP -> C1 -> C4 UPDATE_RECORD / DELETE_RECORD -> C0 adapter -> DefaultDataGateway (idempotency in V28) -> the real
 * PostgresConnector -> a real second PostgreSQL, on an ORDINARY table `shop.items(id uuid PRIMARY KEY, status text)`: the key column is `id`, not `recordId`.
 */
@TestPropertySource(
    properties = [
        "app.workflow.enabled=true", "app.data-platform.enabled=true", "app.workflow.allow-volatile-stores=true", "app.workflow.worker-delay-ms=3600000",
        "app.secrets.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    ]
)
class RecordKeyActionE2ETests : IntegrationTestBase() {
    @TestConfiguration
    class Cfg {
        @Bean @Primary
        fun itemsConnectorRegistry(queries: QueryCatalog): DataConnectorRegistry = DataConnectorRegistry(listOf(
            PostgresConnector(queries, PostgresTargetPolicy(allowedPrivateHosts = setOf("${ShopDb.container.host.lowercase()}:${ShopDb.container.getMappedPort(5432)}")), SystemHostResolver, JdbcPgConnectionFactory(enforceTls = false))
        ))
    }

    @Autowired lateinit var schemas: SchemaRepository

    private class Env(val sc: Scenario, val tenant: UUID, val admin: ApiSession, val dsId: UUID) { val runtime = "${sc.base}/app-runtime" }

    private fun shop(sql: String, vararg args: Any): List<Map<String, Any?>> = ShopDb.rows(sql, *args)
    private fun ddl(sql: String) = DriverManager.getConnection(ShopDb.container.jdbcUrl, ShopDb.container.username, ShopDb.container.password).use { c -> c.createStatement().use { it.execute(sql) } }

    init {
        ddl("""CREATE TABLE IF NOT EXISTS shop.items (id uuid PRIMARY KEY, status text NOT NULL DEFAULT 'new');
               CREATE TABLE IF NOT EXISTS shop.legacy_items ("recordId" text PRIMARY KEY, status text NOT NULL DEFAULT 'new');
               CREATE TABLE IF NOT EXISTS shop.numbered (no bigint PRIMARY KEY, status text NOT NULL DEFAULT 'new');
               CREATE SEQUENCE IF NOT EXISTS shop.items_slow_attempts;
               CREATE OR REPLACE FUNCTION shop.items_slow() RETURNS trigger LANGUAGE plpgsql AS ${'$'}f${'$'}
                 BEGIN
                   IF (TG_OP = 'UPDATE' AND NEW.status = 'SLOW') OR (TG_OP = 'DELETE' AND OLD.status = 'SLOW') THEN PERFORM nextval('shop.items_slow_attempts'); PERFORM pg_sleep(4); END IF;
                   IF TG_OP = 'DELETE' THEN RETURN OLD; END IF; RETURN NEW;
                 END ${'$'}f${'$'};
               DROP TRIGGER IF EXISTS items_slow ON shop.items;
               CREATE TRIGGER items_slow BEFORE UPDATE OR DELETE ON shop.items FOR EACH ROW EXECUTE FUNCTION shop.items_slow();
               GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA shop TO shop_writer; GRANT USAGE ON ALL SEQUENCES IN SCHEMA shop TO shop_writer;""")
    }

    private fun p(name: String, t: ParamType = ParamType.STRING, required: Boolean = true) = QueryParamSpec(name, t, required)

    /** the app: the C2 sample plus UPDATE_RECORD / DELETE_RECORD actions whose logical row identifier is the input `recordId` */
    private fun document(): JsonNode {
        val doc = AppDefinitionTestSupport.resource("valid-v2-sample.json").deepCopy() as ObjectNode
        val queries = doc.get("queries") as ArrayNode
        val actions = doc.get("actions") as ArrayNode
        fun q(id: String, op: String, vararg names: String) = queries.add(json.readTree("""{"id":"$id","dataSourceRef":"erp-db","mode":"WRITE","operationKey":"$op","params":[${names.joinToString(",") { """{"name":"$it","type":"STRING"}""" }}]}"""))
        fun a(id: String, type: String, q: String, vararg inputs: String) = actions.add(json.readTree(
            """{"id":"$id","type":"$type","queryRef":"$q","trigger":{"sectionId":"contact-1","event":"onSubmit"},"inputs":[${inputs.joinToString(",") { """{"name":"$it","type":"STRING","required":true}""" }}]}"""))
        q("item-update", "items.update", "id", "status"); q("item-delete", "items.delete", "id")
        q("legacy-update", "legacy.update", "recordId", "status")
        q("number-update", "numbered.update", "no", "status")
        a("update-item", "UPDATE_RECORD", "item-update", "recordId", "status"); a("delete-item", "DELETE_RECORD", "item-delete", "recordId")
        a("update-legacy", "UPDATE_RECORD", "legacy-update", "recordId", "status")
        a("update-number", "UPDATE_RECORD", "number-update", "recordId", "status")
        return doc
    }

    /** [mutations]: the approved operations of the customer's data source (there is no management endpoint for them in this fixture, like in DataWritableE2ETests) */
    private fun env(mutations: (UUID, UUID) -> List<MutationDefinition>): Env {
        val sc = scenario()
        val tenant = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, sc.ws)!!
        val admin = sessionFor(fx.user("wsadmin").also { fx.member(sc.ws, it, "WORKSPACE_ADMIN") }.username)
        val created = admin.post("/api/v1/workspaces/${sc.ws}/data-sources", json.writeValueAsString(mapOf(
            "name" to ("shop-" + UUID.randomUUID().toString().take(8)), "type" to "postgres",
            "config" to mapOf("host" to ShopDb.container.host, "port" to ShopDb.container.getMappedPort(5432), "database" to ShopDb.container.databaseName, "schemas" to "shop", "writable" to true, "timeoutMs" to 1500),
            "credential" to mapOf("username" to "shop_writer", "password" to ShopDb.PASSWORD))))
        assertThat(created.response.status).describedAs(created.response.contentAsString).isEqualTo(201)
        val dsId = UUID.fromString(admin.body(created).get("id").asString())
        mutations(tenant, dsId).forEach { JdbcMutationCatalog(jdbc).save(it) }
        val doc = document()
        schemas.upsertSchema(sc.projectId, sc.ws, doc)
        val versionId = schemas.insertVersion(sc.ws, sc.projectId, schemas.nextVersionNumber(sc.projectId), doc, "EDIT", "published", null, null, null, sc.user.id)
        val deployment = UUID.randomUUID()
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) VALUES (?, ?, ?, ?, ?, 'PRIVATE', 'RUNNING', 'mock')", deployment, sc.ws, sc.projectId, versionId, sc.user.id)
        jdbc.update("INSERT INTO sites (project_id, slug, current_deployment_id) VALUES (?, ?, ?) ON CONFLICT (project_id) DO UPDATE SET current_deployment_id = EXCLUDED.current_deployment_id",
            sc.projectId, "rk-" + UUID.randomUUID().toString().replace("-", "").take(12), deployment)
        val bound = admin.put("${sc.base}/data-bindings/LIVE/erp-db", """{"dataSourceId":"$dsId"}""")
        assertThat(bound.response.status).describedAs(bound.response.contentAsString).isEqualTo(200)
        return Env(sc, tenant, admin, dsId)
    }

    /** the approved mutations of an ordinary table whose key column is `id`: the approved definition NAMES the key (`key=id`) */
    private fun itemMutations(tenant: UUID, ds: UUID) = listOf(
        MutationDefinition("items.update", tenant, ds, MutationKind.UPDATE, "shop.items key=id returning=id,status", listOf(p("id"), p("status", required = false)), emptyList(), "items"),
        MutationDefinition("items.delete", tenant, ds, MutationKind.DELETE, "shop.items key=id returning=id", listOf(p("id")), emptyList(), "items")
    )

    private fun act(e: Env, id: String, key: String, inputs: Map<String, String>, session: ApiSession = e.admin): MvcResult = session.post("${e.runtime}/actions/$id/execute",
        """{"idempotencyKey":"$key","inputs":${json.writeValueAsString(inputs)},"trigger":{"eventName":"contact-1.onSubmit"}}""")

    private fun newItem(status: String = "new"): UUID = UUID.randomUUID().also { ddl("INSERT INTO shop.items (id, status) VALUES ('$it', '$status')") }
    private fun status(id: UUID) = shop("SELECT status FROM shop.items WHERE id = ?", id).singleOrNull()?.get("status")
    private fun code(e: Env, r: MvcResult) = e.admin.body(r).get("error")?.get("code")?.asString()

    // =============================================================================================================================

    private fun legacy(id: String) = ddl("INSERT INTO shop.legacy_items (\"recordId\", status) VALUES ('$id', 'new')")
    private fun legacyStatus(id: String) = shop("SELECT status FROM shop.legacy_items WHERE \"recordId\" = ?", id).singleOrNull()?.get("status")
    private fun numbered(n: Long) = ddl("INSERT INTO shop.numbered (no, status) VALUES ($n, 'new')")
    private fun numberedStatus(n: Long) = shop("SELECT status FROM shop.numbered WHERE no = ?", n).singleOrNull()?.get("status")
    private fun itemCount() = shop("SELECT count(*) AS n FROM shop.items").single()["n"] as Long
    private fun body(e: Env, r: MvcResult) = e.admin.body(r)
    private fun ok(r: MvcResult) = assertThat(r.response.status).describedAs(r.response.contentAsString).isEqualTo(200)

    private fun allMutations(tenant: UUID, ds: UUID) = itemMutations(tenant, ds) + listOf(
        // the workaround of the old behaviour: a table whose key column is literally named recordId, declared as such (keeps working, no alias is applied)
        MutationDefinition("legacy.update", tenant, ds, MutationKind.UPDATE, "shop.legacy_items key=recordId", listOf(p("recordId"), p("status", required = false)), emptyList(), "legacy"),
        // a bigint key: the logical identifier is a string, the key parameter is an INTEGER
        MutationDefinition("numbered.update", tenant, ds, MutationKind.UPDATE, "shop.numbered key=no", listOf(p("no", ParamType.INTEGER), p("status", required = false)), emptyList(), "numbered")
    )

    // =============================================================================================================================

    @Test
    fun `UPDATE_RECORD - the key column is id, not recordId - row exists, row missing, malformed key`() {
        val e = env(::allMutations)
        val id = newItem()
        val updated = act(e, "update-item", "upd-1", mapOf("recordId" to id.toString(), "status" to "shipped"))
        System.err.println("REPRO-FIXED update: HTTP ${updated.response.status} ${updated.response.contentAsString}")
        ok(updated)
        assertThat(body(e, updated).get("status").asString()).isEqualTo("OK")
        assertThat(status(id)).isEqualTo("shipped")

        val other = newItem("keep")
        val missing = UUID.randomUUID()
        ok(act(e, "update-item", "upd-missing", mapOf("recordId" to missing.toString(), "status" to "x")))     // no such row: nothing is changed, and nothing else is touched
        assertThat(status(missing)).isNull(); assertThat(status(other)).isEqualTo("keep"); assertThat(status(id)).isEqualTo("shipped")

        val before = itemCount()
        val malformed = act(e, "update-item", "upd-bad-uuid", mapOf("recordId" to "not-a-uuid", "status" to "x"))
        System.err.println("REPRO-FIXED malformed: HTTP ${malformed.response.status} ${malformed.response.contentAsString}")
        assertThat(malformed.response.status).isNotEqualTo(200)
        assertThat(code(e, malformed)).describedAs("a definite refusal, never an unknown outcome").isNotEqualTo("IDEMPOTENCY_OUTCOME_UNKNOWN")
        assertThat(itemCount()).isEqualTo(before)
    }

    @Test
    fun `UPDATE_RECORD - compatibility - a table whose key column really is recordId and a bigint key both work`() {
        val e = env(::allMutations)
        legacy("L-1")
        ok(act(e, "update-legacy", "upd-legacy-1", mapOf("recordId" to "L-1", "status" to "done")))
        assertThat(legacyStatus("L-1")).isEqualTo("done")
        numbered(42)
        ok(act(e, "update-number", "upd-number-1", mapOf("recordId" to "42", "status" to "done")))
        assertThat(numberedStatus(42)).isEqualTo("done")
        val notANumber = act(e, "update-number", "upd-number-2", mapOf("recordId" to "42; DROP TABLE shop.numbered", "status" to "x"))
        assertThat(notANumber.response.status).isNotEqualTo(200)
        assertThat(numberedStatus(42)).isEqualTo("done")
    }

    @Test
    fun `DELETE_RECORD - the key column is id, not recordId - row exists, row missing`() {
        val e = env(::allMutations)
        val id = newItem(); val keep = newItem()
        ok(act(e, "delete-item", "del-1", mapOf("recordId" to id.toString())))
        assertThat(status(id)).describedAs("the row is gone").isNull()
        assertThat(status(keep)).describedAs("another row is untouched").isEqualTo("new")
        ok(act(e, "delete-item", "del-missing", mapOf("recordId" to UUID.randomUUID().toString())))
        assertThat(status(keep)).isEqualTo("new")
        // a DELETE without any identifier cannot be expressed (nothing like "delete everything")
        val none = e.admin.post("${e.runtime}/actions/delete-item/execute", """{"idempotencyKey":"del-none","inputs":{},"trigger":{"eventName":"contact-1.onSubmit"}}""")
        assertThat(none.response.status).isNotEqualTo(200)
        assertThat(status(keep)).isEqualTo("new")
    }

    @Test
    fun `authorization - without ACTION_EXECUTE, without DATA_MUTATE, another project, another workspace, another tenant - nothing is changed`() {
        val e = env(::allMutations)
        val id = newItem()
        val inputs = mapOf("recordId" to id.toString(), "status" to "hacked")
        // the project OWNER holds ACTION_EXECUTE but not DATA_MUTATE (manager-only in C1)
        val noMutate = act(e, "update-item", "az-1", inputs, e.sc.s)
        assertThat(noMutate.response.status).isEqualTo(403); assertThat(code(e, noMutate)).isEqualTo("FORBIDDEN")
        // a project VIEWER holds APP_USE only: no ACTION_EXECUTE
        val viewer = fx.user("viewer").also { fx.member(e.sc.ws, it, "VIEWER"); fx.projectRole(fx.projects.findById(e.sc.projectId).get(), it, "VIEWER") }
        val noAction = act(e, "delete-item", "az-2", mapOf("recordId" to id.toString()), sessionFor(viewer.username))
        assertThat(noAction.response.status).isEqualTo(403)
        // a member of the workspace without any right on the project: the project does not exist for them
        val member = fx.user("member").also { fx.member(e.sc.ws, it, "VIEWER") }
        assertThat(act(e, "update-item", "az-3", inputs, sessionFor(member.username)).response.status).isEqualTo(404)
        // an administrator of ANOTHER tenant / workspace asking through this project's path
        val foreign = env(::allMutations)
        assertThat(act(e, "update-item", "az-4", inputs, foreign.admin).response.status).isEqualTo(404)
        // the administrator of this workspace asking through ANOTHER project of it (that project has no such action)
        val second = fx.project(e.sc.ws, e.sc.user, "Second")
        val viaOther = e.admin.post("${api(e.sc.ws, second.id)}/app-runtime/actions/update-item/execute",
            """{"idempotencyKey":"az-5","inputs":${json.writeValueAsString(inputs)},"trigger":{"eventName":"contact-1.onSubmit"}}""")
        assertThat(viaOther.response.status).isEqualTo(404)
        assertThat(status(id)).describedAs("not one of them changed the row").isEqualTo("new")
        assertThat(shop("SELECT count(*) AS n FROM shop.items WHERE status = 'hacked'").single()["n"]).isEqualTo(0L)
        // ... and the same action with the right (administrator) really does it
        ok(act(e, "update-item", "az-6", inputs)); assertThat(status(id)).isEqualTo("hacked")
    }

    @Test
    fun `key metadata - missing, undeclared, ambiguous, unsafe and unknown key columns refuse the change and touch nothing`() {
        val bad = { tenant: UUID, ds: UUID ->
            listOf(
                MutationDefinition("items.update", tenant, ds, MutationKind.UPDATE, "shop.items", listOf(p("id"), p("status", required = false)), emptyList(), "items"),                    // no key at all
                MutationDefinition("items.delete", tenant, ds, MutationKind.DELETE, "shop.items key=ghost", listOf(p("id")), emptyList(), "items"),                                      // key names an undeclared parameter
                MutationDefinition("legacy.update", tenant, ds, MutationKind.UPDATE, "shop.items key=id,status", listOf(p("id"), p("status")), emptyList(), "items"),                    // two keys: no single logical identifier
                MutationDefinition("numbered.update", tenant, ds, MutationKind.UPDATE, "shop.items key=id\"; DROP TABLE shop.items; --", listOf(p("id"), p("status", required = false)), emptyList(), "items"),   // not an identifier
                MutationDefinition("items.nocol", tenant, ds, MutationKind.UPDATE, "shop.items key=nope", listOf(p("nope"), p("status", required = false)), emptyList(), "items")       // a valid name, no such column
            )
        }
        val e = env(bad)
        val id = newItem(); val before = itemCount()
        val attempts = listOf(
            "update-item" to mapOf("recordId" to id.toString(), "status" to "x"),
            "delete-item" to mapOf("recordId" to id.toString()),
            "update-legacy" to mapOf("recordId" to id.toString(), "status" to "x"),
            "update-number" to mapOf("recordId" to id.toString(), "status" to "x")
        )
        for ((i, a) in attempts.withIndex()) {
            val r = act(e, a.first, "meta-$i", a.second)
            System.err.println("META ${a.first}: HTTP ${r.response.status} ${r.response.contentAsString.take(300)}")
            assertThat(r.response.status).describedAs(a.first).isNotEqualTo(200)
            assertThat(code(e, r)).describedAs(a.first + ": a definite refusal").isNotEqualTo("IDEMPOTENCY_OUTCOME_UNKNOWN")
        }
        assertThat(status(id)).isEqualTo("new"); assertThat(itemCount()).isEqualTo(before)
        assertThat(shop("SELECT to_regclass('shop.items') AS t").single()["t"]).describedAs("the table still exists").isNotNull()
    }

    @Test
    fun `SQL identifier safety - values and names a caller sends never become identifiers`() {
        val e = env(::allMutations)
        val id = newItem(); val before = itemCount()
        // a hostile VALUE on a text key is one bound parameter: it matches nothing and nothing else is touched
        legacy("L-A"); legacy("L-B")
        ok(act(e, "update-legacy", "inj-1", mapOf("recordId" to "' OR '1'='1", "status" to "pwned")))
        assertThat(legacyStatus("L-A")).isEqualTo("new"); assertThat(legacyStatus("L-B")).isEqualTo("new")
        // the same value for a uuid key is not a uuid: refused by the database as a definite failure, never executed as SQL
        val hostile = act(e, "update-item", "inj-2", mapOf("recordId" to "x'; DROP TABLE shop.items; --", "status" to "pwned"))
        assertThat(hostile.response.status).isNotEqualTo(200); assertThat(code(e, hostile)).isNotEqualTo("IDEMPOTENCY_OUTCOME_UNKNOWN")
        // an input NAME is not an identifier either: an undeclared input is refused before anything is sent
        val name = e.admin.post("${e.runtime}/actions/update-item/execute",
            """{"idempotencyKey":"inj-3","inputs":{"recordId":"$id","status":"x","id\" = id; DROP TABLE shop.items; --":"y"},"trigger":{"eventName":"contact-1.onSubmit"}}""")
        assertThat(name.response.status).isNotEqualTo(200)
        assertThat(status(id)).isEqualTo("new"); assertThat(itemCount()).isEqualTo(before)
        assertThat(shop("SELECT to_regclass('shop.items') AS t").single()["t"]).isNotNull()
        // giving the key twice is ambiguous, not "the last one wins"
        val twice = e.admin.post("${e.runtime}/actions/update-item/execute",
            """{"idempotencyKey":"inj-4","inputs":{"recordId":"$id","id":"${UUID.randomUUID()}","status":"x"},"trigger":{"eventName":"contact-1.onSubmit"}}""")
        assertThat(twice.response.status).isNotEqualTo(200); assertThat(status(id)).isEqualTo("new")
    }

    @Test
    fun `idempotency - a duplicate key writes once, another input under the same key is refused, an ambiguous timeout stays unknown and is never retried`() {
        val e = env(::allMutations)
        val id = newItem()
        val inputs = mapOf("recordId" to id.toString(), "status" to "paid")
        ok(act(e, "update-item", "idem-0001", inputs))
        ok(act(e, "update-item", "idem-0001", inputs))                                         // replay: the recorded answer, no second statement
        assertThat(status(id)).isEqualTo("paid")
        val conflict = act(e, "update-item", "idem-0001", mapOf("recordId" to id.toString(), "status" to "refunded"))
        assertThat(conflict.response.status).isNotEqualTo(200); assertThat(status(id)).describedAs("the conflicting request changed nothing").isEqualTo("paid")

        // an UPDATE that the database cannot finish within the data source's time limit: sent, outcome unknown
        val slow = newItem("n")
        val attemptsBefore = shop("SELECT CASE WHEN is_called THEN last_value ELSE 0 END AS n FROM shop.items_slow_attempts").single()["n"] as Long
        val timedOut = act(e, "update-item", "idem-slow-1", mapOf("recordId" to slow.toString(), "status" to "SLOW"))
        assertThat(timedOut.response.status).isEqualTo(409)
        assertThat(code(e, timedOut)).isEqualTo("IDEMPOTENCY_OUTCOME_UNKNOWN")
        assertThat(body(e, timedOut).get("error").get("retryable").asBoolean()).isFalse()
        val again = act(e, "update-item", "idem-slow-1", mapOf("recordId" to slow.toString(), "status" to "SLOW"))
        assertThat(code(e, again)).describedAs("the same key stays unknown").isEqualTo("IDEMPOTENCY_OUTCOME_UNKNOWN")
        assertThat(body(e, again).get("error").get("retryable").asBoolean()).isFalse()
        val attempts = shop("SELECT CASE WHEN is_called THEN last_value ELSE 0 END AS n FROM shop.items_slow_attempts").single()["n"] as Long
        assertThat(attempts - attemptsBefore).describedAs("the statement reached the database exactly once").isEqualTo(1L)
        ok(act(e, "update-item", "idem-slow-2", mapOf("recordId" to slow.toString(), "status" to "later")))      // a NEW key is a new, explicit request
        assertThat(status(slow)).isEqualTo("later")

        // the same for DELETE
        val doomed = newItem("SLOW")
        val del = act(e, "delete-item", "idem-del-1", mapOf("recordId" to doomed.toString()))
        assertThat(code(e, del)).isEqualTo("IDEMPOTENCY_OUTCOME_UNKNOWN")
        assertThat(code(e, act(e, "delete-item", "idem-del-1", mapOf("recordId" to doomed.toString())))).isEqualTo("IDEMPOTENCY_OUTCOME_UNKNOWN")
        assertThat(status(doomed)).describedAs("the server rolled the statement back").isEqualTo("SLOW")
    }
}
