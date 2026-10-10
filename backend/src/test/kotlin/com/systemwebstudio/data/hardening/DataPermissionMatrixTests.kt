package com.systemwebstudio.data.hardening

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
 * QUERY_EXECUTE and DATA_MUTATE end to end over HTTP on the REAL LIVE data path (published release -> V28 binding -> DataGateway -> V28 stores), by every kind of caller:
 * a manager (WORKSPACE_ADMIN), an editor (QUERY_EXECUTE, no DATA_MUTATE), a viewer (neither), a member of ANOTHER workspace, a signed-in user of no workspace, and nobody. A denied
 * call must never reach the connector, never reserve an idempotency key and never write a row. The matrix of real statuses is printed (and kept in the C3 hardening report).
 */
@TestPropertySource(
    properties = [
        "app.workflow.enabled=true", "app.data-platform.enabled=true", "app.workflow.worker-delay-ms=3600000", "app.workflow.action-run-sweep-delay-ms=3600000",
        "app.secrets.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    ]
)
class DataPermissionMatrixTests : IntegrationTestBase() {
    @TestConfiguration
    class Cfg { @Bean fun fakeDataConnector() = FakeDataConnector() }

    @Autowired lateinit var schemas: SchemaRepository
    @Autowired lateinit var connector: FakeDataConnector
    @Autowired lateinit var repository: DataSourceRepository

    private val sample: JsonNode get() = AppDefinitionTestSupport.resource("valid-v2-sample.json")
    private val bindings get() = DataSourceBindingWriter(jdbc)
    private val queries get() = JdbcQueryCatalog(jdbc)
    private val mutations get() = JdbcMutationCatalog(jdbc)

    @BeforeEach fun reset() {
        connector.queryCalls.set(0); connector.mutationCalls.set(0); connector.credentialsSeen.clear(); connector.lastMutationParams.clear()
        connector.mutationHook = { _, _, _ -> MutationOutcome(1, DataJson.toNode(mapOf("id" to "rec-1"))) }
    }

    private class App(val sc: Scenario, val tenant: UUID, val source: DataSource)

    private fun app(): App {
        val sc = scenario()
        val tenant = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, sc.ws)!!
        val now = Instant.now()
        val ds = DataSource(DataSourceRef(UUID.randomUUID(), tenant, "fake", emptyMap()), "erp-" + UUID.randomUUID().toString().take(8), workspaceId = sc.ws, createdAt = now, updatedAt = now)
        repository.save(ds)
        queries.save(SqlQueryDefinition("orders.list", tenant, ds.id, "SELECT 1", listOf(QueryParamSpec("status", ParamType.STRING, false)), 200))
        mutations.save(MutationDefinition("orders.create", tenant, ds.id, MutationKind.CREATE, "orders", listOf(QueryParamSpec("customer", ParamType.STRING), QueryParamSpec("amount", ParamType.NUMBER, false)), listOf("orders.list"), "orders"))
        schemas.upsertSchema(sc.projectId, sc.ws, sample)
        val versionId = schemas.insertVersion(sc.ws, sc.projectId, schemas.nextVersionNumber(sc.projectId), sample, "EDIT", "published", null, null, null, sc.user.id)
        val dep = UUID.randomUUID()
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) VALUES (?, ?, ?, ?, ?, 'PRIVATE', 'RUNNING', 'mock')", dep, sc.ws, sc.projectId, versionId, sc.user.id)
        jdbc.update("INSERT INTO sites (project_id, slug, current_deployment_id) VALUES (?, ?, ?) ON CONFLICT (project_id) DO UPDATE SET current_deployment_id = EXCLUDED.current_deployment_id, pointer_version = sites.pointer_version + 1",
            sc.projectId, "perm-" + UUID.randomUUID().toString().replace("-", "").take(12), dep)
        bindings.bind(tenant, sc.ws, sc.projectId, ExecutionMode.LIVE, "erp-db", ds.id, sc.user.id)
        connector.rowsFor[ds.id] = listOf(mapOf("order_no" to "SO-1", "customer" to mapOf("name" to "ACME")))
        return App(sc, tenant, ds)
    }

    private fun rt(app: App, path: String) = "${app.sc.base}/app-runtime/$path"
    private fun userWith(ws: UUID, role: String?): ApiSession = sessionFor(fx.user(role?.lowercase() ?: "nobody").also { if (role != null) fx.member(ws, it, role) }.username)
    /** a member of the application's workspace holding [projectRole] on THE PROJECT (the project role is what gives QUERY_EXECUTE; a workspace role alone sees nothing of a project) */
    private fun projectUser(app: App, wsRole: String, projectRole: String?): ApiSession = sessionFor(fx.user(projectRole?.lowercase() ?: "wsonly").also {
        fx.member(app.sc.ws, it, wsRole); if (projectRole != null) fx.projectRole(fx.projects.findById(app.sc.projectId).get(), it, projectRole) }.username)
    private fun query(app: App, s: ApiSession) = s.post(rt(app, "queries/orders-list/run"), "{}")
    private fun action(app: App, s: ApiSession, key: String) = s.post(rt(app, "actions/create-order/execute"), """{"idempotencyKey":"$key","inputs":{"customer":"ACME"},"trigger":{"eventName":"contact-1.onSubmit"}}""")
    private fun idemRows(app: App) = jdbc.queryForObject("SELECT count(*) FROM data_idempotency WHERE tenant_id = ? AND data_source_id = ?", Long::class.java, app.tenant, app.source.id)!!

    @Test
    fun `QUERY_EXECUTE decides who reads LIVE data - manager and editor read, viewer, a member of another workspace, a user of no workspace and an anonymous caller do not, and no denied call reaches the connector`() {
        val app = app(); val ws = app.sc.ws
        val cases = linkedMapOf<String, Pair<ApiSession, Boolean>>(
            "WORKSPACE_ADMIN" to (userWith(ws, "WORKSPACE_ADMIN") to true),
            "project EDITOR" to (projectUser(app, "EDITOR", "EDITOR") to true),
            "project VIEWER" to (projectUser(app, "VIEWER", "VIEWER") to false),
            "workspace EDITOR without a project role" to (projectUser(app, "EDITOR", null) to false),
            "member of ANOTHER workspace" to (userWith(app().sc.ws, "WORKSPACE_ADMIN") to false),
            "user of no workspace" to (userWith(ws, null) to false),
            "anonymous" to (session() to false)
        )
        val matrix = StringBuilder("LIVE query matrix (status):")
        for ((who, c) in cases) {
            val (s, allowed) = c; val before = connector.queryCalls.get(); val r = query(app, s)
            matrix.append("\n  $who -> ${r.response.status}")
            if (allowed) { assertThat(r.response.status).describedAs(who).isEqualTo(200); assertThat(connector.queryCalls.get()).isEqualTo(before + 1) }
            else { assertThat(r.response.status).describedAs(who).isIn(401, 403, 404); assertThat(connector.queryCalls.get()).describedAs("$who must not reach the connector").isEqualTo(before)
                   assertThat(r.response.contentAsString).doesNotContain("SO-1").doesNotContain("ACME") }
        }
        println(matrix)
    }

    @Test
    fun `DATA_MUTATE decides who writes LIVE data - only the manager writes, an editor with ACTION_EXECUTE and QUERY_EXECUTE is refused, and a refused call reserves no key and writes nothing`() {
        val app = app(); val ws = app.sc.ws
        val cases = linkedMapOf<String, Pair<ApiSession, Boolean>>(
            "WORKSPACE_ADMIN" to (userWith(ws, "WORKSPACE_ADMIN") to true),
            "project EDITOR" to (projectUser(app, "EDITOR", "EDITOR") to false),
            "project VIEWER" to (projectUser(app, "VIEWER", "VIEWER") to false),
            "workspace EDITOR without a project role" to (projectUser(app, "EDITOR", null) to false),
            "member of ANOTHER workspace" to (userWith(app().sc.ws, "WORKSPACE_ADMIN") to false),
            "user of no workspace" to (userWith(ws, null) to false),
            "anonymous" to (session() to false)
        )
        val matrix = StringBuilder("LIVE mutation (action) matrix (status):"); var n = 0
        for ((who, c) in cases) {
            val (s, allowed) = c; val calls = connector.mutationCalls.get(); val rows = idemRows(app); val r = action(app, s, "matrix-key-${n++}-" + UUID.randomUUID())
            matrix.append("\n  $who -> ${r.response.status}")
            if (allowed) { assertThat(r.response.status).describedAs(r.response.contentAsString).isEqualTo(200); assertThat(connector.mutationCalls.get()).isEqualTo(calls + 1); assertThat(idemRows(app)).isEqualTo(rows + 1) }
            else { assertThat(r.response.status).describedAs(who).isIn(401, 403, 404); assertThat(connector.mutationCalls.get()).describedAs("$who must not write").isEqualTo(calls); assertThat(idemRows(app)).describedAs("$who must not reserve a key").isEqualTo(rows) }
        }
        println(matrix)
    }

    @Test
    fun `a project in another workspace and a workspace in the path that does not own the project are denied for the manager of the other side too`() {
        val a = app(); val b = app()
        val adminB = userWith(b.sc.ws, "WORKSPACE_ADMIN")
        val calls = connector.queryCalls.get(); val writes = connector.mutationCalls.get()
        assertThat(adminB.post("${a.sc.base}/app-runtime/queries/orders-list/run", "{}").response.status).isEqualTo(404)
        assertThat(adminB.post("/api/v1/workspaces/${b.sc.ws}/projects/${a.sc.projectId}/app-runtime/queries/orders-list/run", "{}").response.status).describedAs("B's workspace with A's project").isEqualTo(404)
        assertThat(action(a, adminB, "cross-" + UUID.randomUUID()).response.status).isEqualTo(404)
        assertThat(connector.queryCalls.get()).isEqualTo(calls); assertThat(connector.mutationCalls.get()).isEqualTo(writes)
    }
}
