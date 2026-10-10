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
import com.systemwebstudio.wiring.persistence.ApprovalTestSchema
import com.systemwebstudio.wiring.persistence.JdbcMutationCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
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
 * JOURNEY 06 through the WHOLE chain, nothing mocked: HTTP (session, CSRF) -> C1 membership + `AccessPort` -> C4 workflow engine -> real `JdbcWorkflowRunStore` / `JdbcApprovalStore`
 * (PostgreSQL) -> FQ-ACT-02 UPDATE_RECORD (`recordId` -> key `id`) -> C3 data gateway -> the real PostgresConnector -> a real second PostgreSQL (`shop.items`).
 *
 *   submit a business request -> save data (status `submitted`) -> workflow -> APPROVAL (durable WAITING, nothing after it runs) -> a canonical decision by the approver the
 *   definition named (WORKFLOW_MANAGE + snapshot) -> APPROVE: the next step runs once (`approved`) / REJECT: the configured branch (`rejected`) or a deterministic FAILED.
 *
 * Restart while waiting is covered against real PostgreSQL + RabbitMQ in `WorkflowApprovalG3Tests`; here the persisted state is checked directly in the database.
 */
@TestPropertySource(
    properties = [
        "app.workflow.enabled=true", "app.data-platform.enabled=true", "app.workflow.allow-volatile-stores=true", "app.workflow.worker-delay-ms=3600000", "app.workflow.approvals=jdbc",
        "app.secrets.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    ]
)
class ApprovalJourneyE2ETests : IntegrationTestBase() {
    @TestConfiguration
    class Cfg {
        @Bean @Primary
        fun itemsConnectorRegistry(queries: QueryCatalog): DataConnectorRegistry = DataConnectorRegistry(listOf(
            PostgresConnector(queries, PostgresTargetPolicy(allowedPrivateHosts = setOf("${ShopDb.container.host.lowercase()}:${ShopDb.container.getMappedPort(5432)}")), SystemHostResolver, JdbcPgConnectionFactory(enforceTls = false))
        ))
    }

    @Autowired lateinit var schemas: SchemaRepository
    @Autowired lateinit var runtime: AppRuntime

    private class Env(val sc: Scenario, val admin: ApiSession, val boss: ApiSession, val bossId: UUID, val weak: ApiSession, val weakId: UUID, val other: ApiSession) { val rt = "${sc.base}/app-runtime" }

    @BeforeEach fun schema() { ApprovalTestSchema.ensure(jdbc) }

    init {
        DriverManager.getConnection(ShopDb.container.jdbcUrl, ShopDb.container.username, ShopDb.container.password).use { c ->
            c.createStatement().use {
                it.execute("""CREATE TABLE IF NOT EXISTS shop.items (id uuid PRIMARY KEY, status text NOT NULL DEFAULT 'new');
                    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA shop TO shop_writer;""")
            }
        }
    }

    private fun shop(sql: String, vararg args: Any) = ShopDb.rows(sql, *args)
    private fun newItem(): UUID = UUID.randomUUID().also { id -> DriverManager.getConnection(ShopDb.container.jdbcUrl, ShopDb.container.username, ShopDb.container.password).use { c -> c.createStatement().use { it.execute("INSERT INTO shop.items (id, status) VALUES ('$id', 'new')") } } }
    private fun status(id: UUID) = shop("SELECT status FROM shop.items WHERE id = ?", id).singleOrNull()?.get("status")
    private fun p(name: String, required: Boolean = true) = QueryParamSpec(name, ParamType.STRING, required)

    private fun step(id: String, status: String, next: String? = null) =
        """{"id":"$id","kind":"ACTION","actionRef":"update-item","inputs":{"recordId":{"from":"INPUT","path":"recordId"},"status":{"from":"LITERAL","value":"$status"}}${next?.let { ""","next":"$it"""" } ?: ""}}"""

    /** two workflows: `order-approval` (onReject -> a `rejected` step) and `order-strict` (no branch: a rejection fails the run) */
    private fun document(approvers: List<UUID>): JsonNode {
        val doc = AppDefinitionTestSupport.resource("valid-v2-sample.json").deepCopy() as ObjectNode
        (doc.get("queries") as ArrayNode).add(json.readTree("""{"id":"item-update","dataSourceRef":"erp-db","mode":"WRITE","operationKey":"items.update","params":[{"name":"id","type":"STRING","required":true},{"name":"status","type":"STRING","required":false}]}"""))
        (doc.get("actions") as ArrayNode).add(json.readTree("""{"id":"update-item","type":"UPDATE_RECORD","queryRef":"item-update","trigger":{"sectionId":"contact-1","event":"onSubmit"},"inputs":[{"name":"recordId","type":"STRING","required":true},{"name":"status","type":"STRING","required":true}]}"""))
        val who = approvers.joinToString(",") { """{"kind":"USER","userId":"$it"}""" }
        fun approval(onReject: String?) = """{"id":"okay","kind":"APPROVAL","approval":{"title":"Approve order","approvers":[$who],"requiredApprovals":1,"expiresInSeconds":86400${onReject?.let { ""","onReject":"$it"""" } ?: ""}},"next":"approved"}"""
        val workflows = doc.get("workflows") as ArrayNode
        workflows.add(json.readTree("""{"id":"order-approval","name":"Order approval","trigger":"ACTION","steps":[${step("submit", "submitted", "okay")},${approval("rejected")},${step("approved", "approved", "end")},${step("rejected", "rejected", "end")},{"id":"end","kind":"END"}]}"""))
        workflows.add(json.readTree("""{"id":"order-strict","name":"Order approval, strict","trigger":"ACTION","steps":[${step("submit", "submitted", "okay")},${approval(null)},${step("approved", "approved", "end")},{"id":"end","kind":"END"}]}"""))
        return doc
    }

    private fun env(): Env {
        val sc = scenario()
        val tenant = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, sc.ws)!!
        val adminUser = fx.user("wsadmin").also { fx.member(sc.ws, it, "WORKSPACE_ADMIN") }
        val admin = sessionFor(adminUser.username)
        val bossUser = fx.user("boss").also { fx.member(sc.ws, it, "WORKSPACE_ADMIN") }
        val weakUser = fx.user("weak").also { u -> fx.member(sc.ws, u, "VIEWER"); fx.projectRole(fx.projects.findById(sc.projectId).get(), u, "VIEWER") }
        val otherUser = fx.user("other").also { fx.member(sc.ws, it, "WORKSPACE_ADMIN") }
        val created = admin.post("/api/v1/workspaces/${sc.ws}/data-sources", json.writeValueAsString(mapOf(
            "name" to ("shop-" + UUID.randomUUID().toString().take(8)), "type" to "postgres",
            "config" to mapOf("host" to ShopDb.container.host, "port" to ShopDb.container.getMappedPort(5432), "database" to ShopDb.container.databaseName, "schemas" to "shop", "writable" to true, "timeoutMs" to 1500),
            "credential" to mapOf("username" to "shop_writer", "password" to ShopDb.PASSWORD))))
        assertThat(created.response.status).describedAs(created.response.contentAsString).isEqualTo(201)
        val dsId = UUID.fromString(admin.body(created).get("id").asString())
        JdbcMutationCatalog(jdbc).save(MutationDefinition("items.update", tenant, dsId, MutationKind.UPDATE, "shop.items key=id returning=id,status", listOf(p("id"), p("status", required = false)), emptyList(), "items"))
        val doc = document(listOf(bossUser.id, weakUser.id))
        schemas.upsertSchema(sc.projectId, sc.ws, doc)
        val versionId = schemas.insertVersion(sc.ws, sc.projectId, schemas.nextVersionNumber(sc.projectId), doc, "EDIT", "published", null, null, null, sc.user.id)
        val deployment = UUID.randomUUID()
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) VALUES (?, ?, ?, ?, ?, 'PRIVATE', 'RUNNING', 'mock')", deployment, sc.ws, sc.projectId, versionId, sc.user.id)
        jdbc.update("INSERT INTO sites (project_id, slug, current_deployment_id) VALUES (?, ?, ?) ON CONFLICT (project_id) DO UPDATE SET current_deployment_id = EXCLUDED.current_deployment_id",
            sc.projectId, "ap-" + UUID.randomUUID().toString().replace("-", "").take(12), deployment)
        val bound = admin.put("${sc.base}/data-bindings/LIVE/erp-db", """{"dataSourceId":"$dsId"}""")
        assertThat(bound.response.status).describedAs(bound.response.contentAsString).isEqualTo(200)
        return Env(sc, admin, sessionFor(bossUser.username), bossUser.id, sessionFor(weakUser.username), weakUser.id, sessionFor(otherUser.username))
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    private fun drain() { var n = 0; while (n++ < 200 && runtime.worker.runOnce()) { /* run every queued job */ }; runtime.engine.sweep() }
    private fun ok(r: MvcResult, status: Int = 200) = assertThat(r.response.status).describedAs(r.response.contentAsString).isEqualTo(status)
    private fun start(e: Env, workflow: String, key: String, record: UUID): UUID {
        val r = e.admin.post("${e.rt}/workflows/$workflow/runs", """{"idempotencyKey":"$key","input":{"recordId":"$record"}}""")
        ok(r, 202); return UUID.fromString(e.admin.body(r).get("runId").asString())
    }
    private fun run(e: Env, id: UUID, s: ApiSession = e.admin): JsonNode = s.body(s.get("${e.rt}/workflow-runs/$id"))
    private fun step(run: JsonNode, id: String): JsonNode = run.get("steps").first { it.get("stepId").asString() == id }
    private fun approvalOf(e: Env, id: UUID): UUID = UUID.fromString(step(run(e, id), "okay").get("approvalId").asString())
    private fun decide(e: Env, s: ApiSession, runId: UUID, approvalId: UUID, decision: String, base: String = e.rt): MvcResult =
        s.post("$base/workflow-runs/$runId/approvals/$approvalId/decision", """{"decision":"$decision","comment":"journey"}""")
    private fun code(s: ApiSession, r: MvcResult): String? = s.body(r).let { it.get("code")?.asString() ?: it.get("error")?.get("code")?.asString() }
    private fun approvalRow(id: UUID) = jdbc.queryForMap("SELECT status, version, jsonb_array_length(decisions) AS decisions FROM approvals WHERE id = ?", id)
    private fun doneActions(tenant: UUID) = jdbc.queryForObject("SELECT count(*) FROM action_runs WHERE tenant_id = ? AND action_id = 'update-item' AND status = 'SUCCEEDED'", Long::class.java, tenant)!!
    private fun tenantOf(e: Env) = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, e.sc.ws)!!

    // ---- the journey -------------------------------------------------------------------------------------------------------------

    @Test
    fun `JOURNEY 06 approve - request, save, durable wait, canonical decision, next step once, final state`() {
        val e = env(); val tenant = tenantOf(e)
        val item = newItem()
        val id = start(e, "order-approval", "j6-approve", item)
        drain()

        // saved, then WAITING on the approval: durable (database state), the approved step has not run
        val waiting = run(e, id)
        assertThat(waiting.get("status").asString()).isEqualTo("WAITING")
        assertThat(step(waiting, "submit").get("status").asString()).isEqualTo("SUCCEEDED")
        assertThat(step(waiting, "okay").get("status").asString()).isEqualTo("WAITING")
        assertThat(status(item)).describedAs("the data was saved by the first step").isEqualTo("submitted")
        val approvalId = approvalOf(e, id)
        assertThat(approvalRow(approvalId)["status"]).isEqualTo("PENDING")
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_runs WHERE run_id = ?", String::class.java, id)).isEqualTo("WAITING")
        drain(); drain()
        assertThat(run(e, id).get("status").asString()).describedAs("nothing but a decision moves a waiting run").isEqualTo("WAITING")
        assertThat(status(item)).isEqualTo("submitted")

        // who may NOT decide - each answer changes nothing
        assertThat(decide(e, e.admin, id, approvalId, "APPROVE").response.status).describedAs("the requester").isEqualTo(403)
        assertThat(decide(e, e.other, id, approvalId, "APPROVE").response.status).describedAs("a workspace admin the definition did not name").isEqualTo(403)
        val noPermission = decide(e, e.weak, id, approvalId, "APPROVE")
        assertThat(noPermission.response.status).describedAs("an approver of the definition without WORKFLOW_MANAGE").isEqualTo(403)
        val foreign = env()
        assertThat(decide(e, foreign.boss, id, approvalId, "APPROVE").response.status).describedAs("an administrator of another tenant").isEqualTo(404)
        val second = fx.project(e.sc.ws, e.sc.user, "Second")
        assertThat(decide(e, e.boss, id, approvalId, "APPROVE", base = "${api(e.sc.ws, second.id)}/app-runtime").response.status).describedAs("another project of the workspace").isEqualTo(404)
        val otherRun = start(e, "order-approval", "j6-other", newItem()); drain()
        assertThat(decide(e, e.boss, id, approvalOf(e, otherRun), "APPROVE").response.status).describedAs("the approval of another run").isEqualTo(404)
        assertThat(decide(e, e.boss, id, UUID.randomUUID(), "APPROVE").response.status).isEqualTo(404)
        assertThat(e.boss.post("${e.rt}/workflow-runs/$id/approvals/$approvalId/decision", """{"decision":"MAYBE"}""").response.status).isEqualTo(400)
        assertThat(e.boss.post("${e.rt}/workflow-runs/$id/approvals/$approvalId/decision", """{"decision":"APPROVE","userId":"${e.bossId}"}""").response.status).describedAs("no identity from the body").isEqualTo(400)
        drain()
        assertThat(approvalRow(approvalId)["status"]).describedAs("every refused attempt left the approval pending").isEqualTo("PENDING")
        assertThat(status(item)).isEqualTo("submitted")
        assertThat(run(e, id).get("status").asString()).isEqualTo("WAITING")
        val before = doneActions(tenant)

        // the approver decides
        val approved = decide(e, e.boss, id, approvalId, "APPROVE")
        ok(approved)
        assertThat(e.boss.body(approved).get("approvalStatus").asString()).isEqualTo("APPROVED")
        drain(); drain()
        val done = run(e, id)
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED")
        assertThat(step(done, "approved").get("status").asString()).isEqualTo("SUCCEEDED")
        assertThat(step(done, "approved").get("attempt").asInt()).isEqualTo(1)
        assertThat(status(item)).describedAs("the next step ran: final state").isEqualTo("approved")
        assertThat(doneActions(tenant)).describedAs("exactly one more update ran").isEqualTo(before + 1)

        // a duplicate decision: the same one is harmless, the opposite one is a conflict, and nothing runs twice
        ok(decide(e, e.boss, id, approvalId, "APPROVE"))
        assertThat(decide(e, e.boss, id, approvalId, "REJECT").response.status).isEqualTo(409)
        drain()
        assertThat(approvalRow(approvalId)["decisions"]).isEqualTo(1)
        assertThat(doneActions(tenant)).isEqualTo(before + 1)
        assertThat(status(item)).isEqualTo("approved")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE project_id = ? AND action LIKE 'APPROVAL%'", Long::class.java, e.sc.projectId)).describedAs("the approval is in the audit trail").isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `JOURNEY 06 reject - the configured branch runs, or the run fails deterministically, and the approved step never runs`() {
        val e = env(); val tenant = tenantOf(e)
        val routed = newItem(); val strict = newItem()
        val a = start(e, "order-approval", "j6-reject-a", routed); val b = start(e, "order-strict", "j6-reject-b", strict)
        drain()
        val aid = approvalOf(e, a); val bid = approvalOf(e, b)

        val rejected = decide(e, e.boss, a, aid, "REJECT"); ok(rejected)
        assertThat(e.boss.body(rejected).get("approvalStatus").asString()).isEqualTo("REJECTED")
        ok(decide(e, e.boss, b, bid, "REJECT"))
        drain(); drain()
        assertThat(run(e, a).get("status").asString()).isEqualTo("SUCCEEDED")
        assertThat(status(routed)).describedAs("the onReject branch").isEqualTo("rejected")
        assertThat(run(e, a).get("steps").none { it.get("stepId").asString() == "approved" && it.get("status").asString() == "SUCCEEDED" }).describedAs("the approved step never ran").isTrue()
        val failed = run(e, b)
        assertThat(failed.get("status").asString()).isEqualTo("FAILED"); assertThat(failed.get("errorCode").asString()).isEqualTo("APPROVAL_REJECTED")
        assertThat(status(strict)).describedAs("no branch: the data stays as it was saved").isEqualTo("submitted")
        // the opposite decision after the fact is a conflict and changes nothing
        assertThat(decide(e, e.boss, a, aid, "APPROVE").response.status).isEqualTo(409)
        drain(); assertThat(status(routed)).isEqualTo("rejected")
        assertThat(doneActions(tenant)).describedAs("submit + rejected, submit").isEqualTo(3L)
    }

    @Test
    fun `JOURNEY 06 cancel while waiting - the approval is cancelled, a later decision is a conflict and nothing runs`() {
        val e = env(); val item = newItem()
        val id = start(e, "order-approval", "j6-cancel", item); drain()
        val aid = approvalOf(e, id)
        ok(e.admin.post("${e.rt}/workflow-runs/$id/cancel", "{}"))
        assertThat(approvalRow(aid)["status"]).isEqualTo("CANCELLED")
        assertThat(decide(e, e.boss, id, aid, "APPROVE").response.status).isEqualTo(409)
        drain()
        assertThat(run(e, id).get("status").asString()).isEqualTo("CANCELLED")
        assertThat(status(item)).isEqualTo("submitted")
    }
}
