package com.systemwebstudio.wiring

import com.systemwebstudio.app.definition.AppDefinitionTestSupport
import com.systemwebstudio.data.FakeDataConnector
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceRepository
import com.systemwebstudio.data.datasource.MutationOutcome
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.SqlQueryDefinition
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.support.IntegrationTestBase
import com.systemwebstudio.version.SchemaRepository
import com.systemwebstudio.wiring.persistence.DataSourceBindingWriter
import com.systemwebstudio.wiring.persistence.JdbcMutationCatalog
import com.systemwebstudio.wiring.persistence.JdbcQueryCatalog
import com.systemwebstudio.wiring.persistence.SharedBroker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * Runtime authorization through the REAL stack, directly against the backend API (no frontend anywhere): HTTP -> session -> C1 (`AccessService`, live: enabled user,
 * membership, workspace/project, permission) -> C4 `ActionRuntime` / `WorkflowEngine` -> PostgreSQL run stores (V29) -> RabbitMQ (`app.workflow.queue=amqp`, a queue of its
 * own) -> C3 `DataGateway` -> a writable test connector.
 *
 * Every effect boundary is observed, not assumed: the connector (what reached the external system), `workflow_runs` / `action_runs` / `data_idempotency` rows, and the
 * depth of the broker queue. A denied request must leave all of them at zero; the control request of an authorized user shows the boundary is observable.
 *
 * Roles (C1 matrix): project OWNER = APP_USE + ACTION_EXECUTE (no DATA_MUTATE, no WORKFLOW_*); project VIEWER = APP_VIEW + APP_USE; WORKSPACE_ADMIN = everything;
 * workspace VIEWER without a project membership = an organization member with no right on the project at all.
 */
@TestPropertySource(
    properties = [
        "app.workflow.enabled=true", "app.data-platform.enabled=true", "app.workflow.queue=amqp",
        "app.workflow.amqp.queue=authz.api.jobs", "app.workflow.amqp.dead-letter-queue=authz.api.jobs.dlq", "app.workflow.amqp.dead-letter-exchange=authz.api.dlx",
        "app.workflow.worker-delay-ms=3600000", "app.workflow.action-run-sweep-delay-ms=3600000",
        "app.secrets.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    ]
)
class RuntimeAuthorizationApiTests : IntegrationTestBase() {
    @TestConfiguration
    class Cfg { @Bean fun fakeDataConnector() = FakeDataConnector() }

    @Autowired lateinit var schemas: SchemaRepository
    @Autowired lateinit var connector: FakeDataConnector
    @Autowired lateinit var repository: DataSourceRepository
    @Autowired lateinit var runtime: AppRuntime

    private val sample: JsonNode get() = AppDefinitionTestSupport.resource("valid-v2-sample.json")
    private val queueName = "authz.api.jobs"

    @BeforeEach
    fun reset() {
        connector.queryCalls.set(0); connector.mutationCalls.set(0); connector.lastMutationParams.clear()
        connector.mutationHook = { _, _, _ -> MutationOutcome(1, DataJson.toNode(mapOf("id" to "rec-1"))) }
        // the queue is shared by the tests of this class; nothing else publishes to it
        SharedBroker.connection().createChannel().let { ch -> try { ch.queuePurge(queueName) } finally { runCatching { ch.close() } } }
        jdbc.update("DELETE FROM workflow_runs WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') OR compensation = 'IN_PROGRESS'")
    }

    private class App(val sc: Scenario, val tenant: UUID, val source: DataSource)

    private fun app(): App {
        val sc = scenario()
        val tenant = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, sc.ws)!!
        val now = Instant.now()
        val ds = DataSource(DataSourceRef(UUID.randomUUID(), tenant, "fake", emptyMap()), "erp-" + UUID.randomUUID().toString().take(8), workspaceId = sc.ws, createdAt = now, updatedAt = now)
        repository.save(ds)
        JdbcQueryCatalog(jdbc).save(SqlQueryDefinition("orders.list", tenant, ds.id, "SELECT 1", listOf(QueryParamSpec("status", ParamType.STRING, false), QueryParamSpec("since", ParamType.TIMESTAMP, false)), 200))
        JdbcMutationCatalog(jdbc).save(MutationDefinition("orders.create", tenant, ds.id, MutationKind.CREATE, "orders", listOf(QueryParamSpec("customer", ParamType.STRING), QueryParamSpec("amount", ParamType.NUMBER, false)), listOf("orders.list"), "orders"))
        schemas.upsertSchema(sc.projectId, sc.ws, sample)
        val versionId = schemas.insertVersion(sc.ws, sc.projectId, schemas.nextVersionNumber(sc.projectId), sample, "EDIT", "published", null, null, null, sc.user.id)
        val dep = UUID.randomUUID()
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) VALUES (?, ?, ?, ?, ?, 'PRIVATE', 'RUNNING', 'mock')", dep, sc.ws, sc.projectId, versionId, sc.user.id)
        jdbc.update("INSERT INTO sites (project_id, slug, current_deployment_id) VALUES (?, ?, ?) ON CONFLICT (project_id) DO UPDATE SET current_deployment_id = EXCLUDED.current_deployment_id, pointer_version = sites.pointer_version + 1",
            sc.projectId, "authz-" + UUID.randomUUID().toString().replace("-", "").take(12), dep)
        DataSourceBindingWriter(jdbc).bind(tenant, sc.ws, sc.projectId, ExecutionMode.LIVE, "erp-db", ds.id, sc.user.id)
        return App(sc, tenant, ds)
    }

    private fun rt(a: App, path: String) = "${a.sc.base}/app-runtime/$path"
    private fun session(name: String, a: App, workspaceRole: String? = null, projectRole: String? = null): Pair<com.systemwebstudio.identity.UserEntity, ApiSession> {
        val u = fx.user(name)
        // a project membership needs a workspace membership (FK); a workspace VIEWER holds no permission of its own
        if (workspaceRole != null) fx.member(a.sc.ws, u, workspaceRole) else if (projectRole != null) fx.member(a.sc.ws, u, "VIEWER")
        if (projectRole != null) fx.projectRole(fx.projects.findById(a.sc.projectId).get(), u, projectRole)
        return u to sessionFor(u.username)
    }

    private fun create(s: ApiSession, a: App, key: String) = s.post(rt(a, "actions/create-order/execute"), """{"idempotencyKey":"$key","inputs":{"customer":"ACME"},"trigger":{"eventName":"contact-1.onSubmit"}}""")
    private fun startFlow(s: ApiSession, a: App, key: String) = s.post(rt(a, "workflows/notify-flow/runs"), """{"idempotencyKey":"$key"}""")

    // ---- the observable effect boundaries ---------------------------------------------------------------------------------------

    private fun runs(a: App) = jdbc.queryForObject("SELECT count(*) FROM workflow_runs WHERE app_id = ?", Long::class.java, a.sc.projectId)!!
    private fun actionRuns(a: App) = jdbc.queryForObject("SELECT count(*) FROM action_runs WHERE app_id = ?", Long::class.java, a.sc.projectId)!!
    private fun idempotency(a: App) = jdbc.queryForObject("SELECT count(*) FROM data_idempotency WHERE data_source_id = ?", Long::class.java, a.source.id)!!
    private fun queueDepth(): Int = SharedBroker.connection().createChannel().let { ch -> try { ch.queueDeclarePassive(queueName).messageCount } finally { runCatching { ch.close() } } }

    private data class Effects(val connectorMutations: Int, val workflowRuns: Long, val actionRuns: Long, val dataIdempotency: Long, val queued: Int)
    private fun effects(a: App) = Effects(connector.mutationCalls.get(), runs(a), actionRuns(a), idempotency(a), queueDepth())
    private val none = Effects(0, 0, 0, 0, 0)

    private fun code(s: ApiSession, r: org.springframework.test.web.servlet.MvcResult): String? = s.body(r).let { b -> b.get("error")?.get("code")?.asString() ?: b.get("code")?.asString() }

    // =============================================================================================================================

    @Test
    fun `control - an authorized administrator mutates, starts a run and the effects are observable at every boundary`() {
        val a = app()
        val (_, admin) = session("admin", a, "WORKSPACE_ADMIN")
        assertThat(create(admin, a, "ctl-order-1").response.status).isEqualTo(200)
        assertThat(connector.mutationCalls.get()).isEqualTo(1)
        assertThat(actionRuns(a)).isGreaterThanOrEqualTo(1)
        val started = startFlow(admin, a, "ctl-flow-1")
        assertThat(started.response.status).isEqualTo(202)
        assertThat(runs(a)).isEqualTo(1)
        assertThat(queueDepth()).describedAs("the job is on the real broker").isEqualTo(1)
    }

    @Test
    fun `direct API - action execution without ACTION_EXECUTE or DATA_MUTATE is 403 FORBIDDEN and nothing reaches the connector or a store`() {
        val a = app()
        val (_, viewer) = session("viewer", a, projectRole = "VIEWER")                    // APP_VIEW + APP_USE
        val owner = a.sc.s                                                                  // project OWNER: ACTION_EXECUTE, not DATA_MUTATE
        for ((who, s) in listOf("project VIEWER" to viewer, "project OWNER (no DATA_MUTATE)" to owner)) {
            val r = create(s, a, "deny-" + who.take(5))
            assertThat(r.response.status).describedAs(who).isEqualTo(403)
            assertThat(code(s, r)).describedAs(who).isEqualTo("FORBIDDEN")
        }
        assertThat(viewer.post(rt(a, "actions/go-home/execute"), """{"trigger":{"eventName":"hero-1.onClick"}}""").response.status).describedAs("NAVIGATE needs ACTION_EXECUTE").isEqualTo(403)
        assertThat(effects(a)).describedAs("effects after every denied action").isEqualTo(none)
    }

    @Test
    fun `direct API - a workflow start without WORKFLOW_EXECUTE is 403 and publishes nothing to the real broker`() {
        val a = app()
        val (_, viewer) = session("viewer", a, projectRole = "VIEWER")
        for ((who, s) in listOf("VIEWER" to viewer, "OWNER" to a.sc.s)) {
            val r = startFlow(s, a, "deny-flow-$who")
            assertThat(r.response.status).describedAs(who).isEqualTo(403)
            assertThat(code(s, r)).isEqualTo("FORBIDDEN")
        }
        assertThat(effects(a)).describedAs("no run row, no queued job, no connector call").isEqualTo(none)
    }

    @Test
    fun `direct API - status and cancel of a run of somebody else are 404 without WORKFLOW_MANAGE, and the run is untouched`() {
        val a = app()
        val (_, admin) = session("admin", a, "WORKSPACE_ADMIN")
        val runId = admin.body(startFlow(admin, a, "mgr-flow-1")).get("runId").asString()
        val (_, viewer) = session("viewer", a, projectRole = "VIEWER")
        for ((who, s) in listOf("VIEWER" to viewer, "OWNER" to a.sc.s)) {
            assertThat(s.get(rt(a, "workflow-runs/$runId")).response.status).describedAs("status $who").isEqualTo(404)
            assertThat(s.post(rt(a, "workflow-runs/$runId/cancel"), "{}").response.status).describedAs("cancel $who").isEqualTo(404)
        }
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_runs WHERE run_id = ?::uuid", String::class.java, runId)).isEqualTo("PENDING")
        val (_, manager) = session("manager", a, "WORKSPACE_ADMIN")
        assertThat(manager.get(rt(a, "workflow-runs/$runId")).response.status).describedAs("WORKFLOW_MANAGE reads").isEqualTo(200)
        assertThat(manager.post(rt(a, "workflow-runs/$runId/cancel"), "{}").response.status).describedAs("WORKFLOW_MANAGE cancels").isEqualTo(200)
    }

    @Test
    fun `cross project - a run of project A is not found through the path of project B, by its creator or by an administrator of B`() {
        val a = app(); val b = app()
        val (adminA, sessionA) = session("adminA", a, "WORKSPACE_ADMIN")
        val runId = sessionA.body(startFlow(sessionA, a, "x-flow-1")).get("runId").asString()
        // the creator is also an administrator of B's workspace
        fx.member(b.sc.ws, adminA, "WORKSPACE_ADMIN")
        for (path in listOf(rt(b, "workflow-runs/$runId"))) assertThat(sessionA.get(path).response.status).describedAs("creator through B").isEqualTo(404)
        assertThat(sessionA.post(rt(b, "workflow-runs/$runId/cancel"), "{}").response.status).isEqualTo(404)
        val (_, adminB) = session("adminB", b, "WORKSPACE_ADMIN")
        assertThat(adminB.get(rt(b, "workflow-runs/$runId")).response.status).isEqualTo(404)
        assertThat(adminB.post(rt(b, "workflow-runs/$runId/cancel"), "{}").response.status).isEqualTo(404)
        // and through a path that mixes B's workspace with A's project
        assertThat(sessionA.get("${api(b.sc.ws, a.sc.projectId)}/app-runtime/workflow-runs/$runId").response.status).isEqualTo(404)
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_runs WHERE run_id = ?::uuid", String::class.java, runId)).isEqualTo("PENDING")
    }

    @Test
    fun `same tenant, same workspace, other project - the creator and an administrator of the workspace get 404 for a run of the first project`() {
        val a = app()
        val (admin, s) = session("admin", a, "WORKSPACE_ADMIN")                           // holds every right in BOTH projects of the workspace
        val runId = s.body(startFlow(s, a, "sw-flow-1")).get("runId").asString()
        val other = fx.project(a.sc.ws, a.sc.user, "Second")
        val via = "${api(a.sc.ws, other.id)}/app-runtime/workflow-runs/$runId"
        assertThat(s.get(via).response.status).describedAs("creator through the other project").isEqualTo(404)
        assertThat(s.post("$via/cancel", "{}").response.status).isEqualTo(404)
        assertThat(a.sc.s.get(via).response.status).describedAs("owner of the workspace project").isEqualTo(404)
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_runs WHERE run_id = ?::uuid", String::class.java, runId)).isEqualTo("PENDING")
        assertThat(s.get(rt(a, "workflow-runs/$runId")).response.status).describedAs("control: the right project").isEqualTo(200)
    }

    @Test
    fun `organization membership alone - a workspace member without any project right gets 404 on every runtime route and causes no effect`() {
        val a = app()
        val (_, orgMember) = session("orgmember", a, "VIEWER")                              // workspace member, no project membership
        val tenantMember = fx.user("tenantmember")                                           // exists, belongs to nothing
        val s2 = sessionFor(tenantMember.username)
        for (s in listOf(orgMember, s2)) {
            assertThat(create(s, a, "org-order").response.status).isEqualTo(404)
            assertThat(startFlow(s, a, "org-flow").response.status).isEqualTo(404)
            assertThat(s.get(rt(a, "workflow-runs/${UUID.randomUUID()}")).response.status).isEqualTo(404)
            assertThat(s.post(rt(a, "workflow-runs/${UUID.randomUUID()}/cancel"), "{}").response.status).isEqualTo(404)
        }
        assertThat(effects(a)).isEqualTo(none)
    }

    @Test
    fun `revoked permission - an administrator demoted after login is refused at once, before the data gateway`() {
        val a = app()
        val (admin, s) = session("admin", a, "WORKSPACE_ADMIN")
        assertThat(create(s, a, "rev-order-1").response.status).isEqualTo(200)
        assertThat(connector.mutationCalls.get()).isEqualTo(1)
        jdbc.update("UPDATE workspace_members SET role = 'VIEWER' WHERE workspace_id = ? AND user_id = ?", a.sc.ws, admin.id)      // the session lives on, the right does not
        val denied = create(s, a, "rev-order-2")
        assertThat(denied.response.status).isIn(403, 404)
        assertThat(startFlow(s, a, "rev-flow-1").response.status).isIn(403, 404)
        assertThat(connector.mutationCalls.get()).describedAs("the data gateway was not reached again").isEqualTo(1)
        assertThat(runs(a)).isZero(); assertThat(queueDepth()).isZero()
    }

    @Test
    fun `disabled user - the session of a disabled account is refused on every runtime route`() {
        val a = app()
        val (admin, s) = session("admin", a, "WORKSPACE_ADMIN")
        val runId = s.body(startFlow(s, a, "dis-flow-1")).get("runId").asString()
        val before = effects(a)
        fx.disable(admin.id)
        assertThat(create(s, a, "dis-order").response.status).isEqualTo(401)
        assertThat(startFlow(s, a, "dis-flow-2").response.status).isEqualTo(401)
        assertThat(s.get(rt(a, "workflow-runs/$runId")).response.status).describedAs("the creator, disabled").isEqualTo(401)
        assertThat(s.post(rt(a, "workflow-runs/$runId/cancel"), "{}").response.status).isEqualTo(401)
        assertThat(effects(a)).isEqualTo(before)
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_runs WHERE run_id = ?::uuid", String::class.java, runId)).isEqualTo("PENDING")
    }

    @Test
    fun `a creator removed from the workspace cannot read or cancel his run any more`() {
        val a = app()
        val (admin, s) = session("admin", a, "WORKSPACE_ADMIN")
        val runId = s.body(startFlow(s, a, "gone-flow-1")).get("runId").asString()
        jdbc.update("UPDATE workspace_members SET active = false WHERE workspace_id = ? AND user_id = ?", a.sc.ws, admin.id)
        assertThat(s.get(rt(a, "workflow-runs/$runId")).response.status).isEqualTo(404)
        assertThat(s.post(rt(a, "workflow-runs/$runId/cancel"), "{}").response.status).isEqualTo(404)
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_runs WHERE run_id = ?::uuid", String::class.java, runId)).isEqualTo("PENDING")
    }

    @Test
    fun `a run whose creator lost the right while it was queued does nothing when the worker takes it - before any step, on the real broker and stores`() {
        val a = app()
        val (admin, s) = session("admin", a, "WORKSPACE_ADMIN")
        val runId = s.body(startFlow(s, a, "queued-flow-1")).get("runId").asString()
        assertThat(queueDepth()).isEqualTo(1)
        jdbc.update("UPDATE workspace_members SET role = 'VIEWER' WHERE workspace_id = ? AND user_id = ?", a.sc.ws, admin.id)   // revoked while the job waits
        var guard = 0
        while (guard++ < 200 && runtime.worker.runOnce()) Unit                              // the real worker, real queue
        val row = jdbc.queryForMap("SELECT status, error_code FROM workflow_runs WHERE run_id = ?::uuid", runId)
        assertThat(row["status"]).isEqualTo("FAILED")
        assertThat(row["error_code"]).isEqualTo("FORBIDDEN")
        assertThat(connector.mutationCalls.get()).describedAs("no step reached an effect").isZero()
        assertThat(queueDepth()).describedAs("the message was acknowledged, not requeued").isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM action_runs WHERE app_id = ?", Long::class.java, a.sc.projectId)).isZero()
    }

    @Test
    fun `error bodies of the denied requests carry no broker, credential or internal detail`() {
        val a = app()
        val (_, viewer) = session("viewer", a, projectRole = "VIEWER")
        val bodies = listOf(
            viewer.post(rt(a, "actions/create-order/execute"), """{"idempotencyKey":"leak-1","inputs":{"customer":"A"},"trigger":{"eventName":"contact-1.onSubmit"}}"""),
            viewer.post(rt(a, "workflows/notify-flow/runs"), """{"idempotencyKey":"leak-2"}"""),
            viewer.get(rt(a, "workflow-runs/${UUID.randomUUID()}"))
        ).map { it.response.contentAsString }
        for (b in bodies) assertThat(b.lowercase()).doesNotContain("amqp", "rabbit", "test-rabbit-123", "password", "jdbc:", "secret", "stacktrace")
    }
}
