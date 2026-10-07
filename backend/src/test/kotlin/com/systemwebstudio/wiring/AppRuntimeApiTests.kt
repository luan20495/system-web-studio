package com.systemwebstudio.wiring

import com.systemwebstudio.app.definition.AppDefinitionTestSupport
import com.systemwebstudio.support.IntegrationTestBase
import com.systemwebstudio.version.SchemaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Routes of docs/contracts/v2/runtime-api.md with BOTH flags on and `allow-volatile-stores=false` (the acceptance default). Since V28 (D-C0-21) the DataGateway
 * bean exists, backed by the persistent stores; nothing is registered or bound in this class, so a data path answers "unbound" (422), never an invented
 * success. TEST/WouldRun, permissions, tenant isolation, strict parsing and the volatile guard are fully reachable. The real LIVE data paths are in
 * [DataRuntimeLiveApiTests].
 * The AppDefinition is the C2 sample document written as the project's working draft; nothing is published, so LIVE finds no definition.
 */
@TestPropertySource(properties = ["app.workflow.enabled=true", "app.data-platform.enabled=true", "app.workflow.run-store=memory", "app.workflow.allow-volatile-stores=false", "app.workflow.worker-delay-ms=3600000", "app.workflow.action-run-sweep-delay-ms=3600000"])
class AppRuntimeApiTests : IntegrationTestBase() {
    @Autowired lateinit var schemas: SchemaRepository

    private val sample: JsonNode get() = AppDefinitionTestSupport.resource("valid-v2-sample.json")

    private fun rt(sc: Scenario, path: String) = "${sc.base}/app-runtime/$path"

    private fun withDefinition(): Scenario = scenario().also { schemas.upsertSchema(it.projectId, it.ws, sample) }

    /** a WORKSPACE_ADMIN of the scenario's workspace: holds DATA_MUTATE and WORKFLOW_EXECUTE (manager-only permissions) */
    private fun admin(sc: Scenario) = sessionFor(fx.user("wsadmin").also { fx.member(sc.ws, it, "WORKSPACE_ADMIN") }.username)

    @Test
    fun `an anonymous caller is not let in`() {
        val sc = withDefinition()
        val r = session().post(rt(sc, "actions/go-home/execute"), "{}")
        assertThat(r.response.status).isIn(401, 403)
    }

    @Test
    fun `a user of another workspace sees the project as missing on every route`() {
        val sc = withDefinition()
        val stranger = sessionFor(fx.user("stranger").also { fx.member(fx.workspace(), it, "EDITOR") }.username)
        assertThat(stranger.post(rt(sc, "actions/go-home/execute"), "{}").response.status).isEqualTo(404)
        assertThat(stranger.post(rt(sc, "queries/orders-list/run"), "{}").response.status).isEqualTo(404)
        assertThat(stranger.post(rt(sc, "workflows/notify-flow/runs"), """{"idempotencyKey":"k-12345678"}""").response.status).isEqualTo(404)
        assertThat(stranger.get(rt(sc, "workflow-runs/${UUID.randomUUID()}")).response.status).isEqualTo(404)
    }

    @Test
    fun `a body field the contract does not list is refused, tenantId first`() {
        val sc = withDefinition()
        for (field in listOf("tenantId", "userId", "dataSourceId", "sql")) {
            val r = sc.s.post(rt(sc, "actions/go-home/execute"), """{"$field":"x"}""")
            assertThat(r.response.status).describedAs(field).isEqualTo(400)
            assertThat(sc.s.body(r).get("code").asString()).isEqualTo("INVALID_REQUEST")
        }
    }

    @Test
    fun `without DATA_MUTATE a data action is forbidden even in TEST mode`() {
        val sc = withDefinition()          // the owner is an EDITOR: APP_USE + ACTION_EXECUTE, but DATA_MUTATE is manager-only
        val r = sc.s.post(rt(sc, "actions/create-order/execute"), """{"mode":"TEST","inputs":{"customer":"ACME"},"trigger":{"eventName":"contact-1.onSubmit"}}""")
        assertThat(r.response.status).isEqualTo(403)
        val b = sc.s.body(r)
        assertThat(b.get("status").asString()).isEqualTo("FAILED")
        assertThat(b.get("error").get("code").asString()).isEqualTo("FORBIDDEN")
        assertThat(b.get("error").get("retryable").asBoolean()).isFalse()
    }

    @Test
    fun `a manager gets a would-run answer in TEST mode, nothing is executed and no input value reaches the audit trail`() {
        val sc = withDefinition()
        val a = admin(sc)
        val r = a.post(rt(sc, "actions/create-order/execute"), """{"mode":"TEST","inputs":{"customer":"ACME-SECRET-NAME"},"trigger":{"eventName":"contact-1.onSubmit"}}""")
        assertThat(r.response.status).isEqualTo(200)
        val b = a.body(r)
        assertThat(b.get("status").asString()).isEqualTo("WOULD_RUN")
        assertThat(b.get("level").asString()).isEqualTo("NOT_EXECUTED")
        assertThat(sc.auditCount("ACTION_PREVIEWED")).isGreaterThanOrEqualTo(1L)
        val leaked = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE project_id = ? AND new_value::text LIKE '%ACME-SECRET-NAME%'", Long::class.java, sc.projectId)!!
        assertThat(leaked).isZero()
    }

    @Test
    fun `a trigger that does not belong to the action is an unknown action`() {
        val sc = withDefinition()
        val r = admin(sc).post(rt(sc, "actions/create-order/execute"), """{"mode":"TEST","inputs":{"customer":"A"},"trigger":{"eventName":"hero-1.onClick"}}""")
        assertThat(r.response.status).isEqualTo(404)
    }

    @Test
    fun `LIVE finds no definition while nothing is published`() {
        val sc = withDefinition()
        val a = admin(sc)
        val r = a.post(rt(sc, "actions/create-order/execute"), """{"idempotencyKey":"order-0001","inputs":{"customer":"A"}}""")
        assertThat(r.response.status).isEqualTo(404)
        assertThat(a.body(r).get("error").get("code").asString()).isEqualTo("UNKNOWN_ACTION")
    }

    // C0 / V28 (D-C0-21): this test used to expect 503 DATA_RUNTIME_UNAVAILABLE because no DataGateway bean existed. The bean exists now, so the truthful answer for an
    // application whose data source slot has no binding is 422 DATA_SOURCE_UNBOUND. The 503 is still produced (and still tested) when the gateway is absent: ActionDataPortAdapterTests.
    @Test
    fun `TEST mode of a query needs APP_EDIT and, with nothing bound, the data runtime answers 422 DATA_SOURCE_UNBOUND`() {
        val sc = withDefinition()
        val r = sc.s.post(rt(sc, "queries/orders-list/run"), """{"mode":"TEST"}""")
        assertThat(r.response.status).isEqualTo(422)
        assertThat(sc.s.body(r).get("code").asString()).isEqualTo("DATA_SOURCE_UNBOUND")
    }

    @Test
    fun `a LIVE workflow start is refused while the run store is volatile`() {
        val sc = withDefinition()
        val a = admin(sc)
        val r = a.post(rt(sc, "workflows/notify-flow/runs"), """{"idempotencyKey":"wf-0000001"}""")
        assertThat(r.response.status).isEqualTo(503)
        assertThat(a.body(r).get("code").asString()).isEqualTo("RUNTIME_STORES_VOLATILE")
    }

    @Test
    fun `a workflow start without a key is a bad request and an unknown run is not found`() {
        val sc = withDefinition()
        val r = sc.s.post(rt(sc, "workflows/notify-flow/runs"), "{}")
        assertThat(r.response.status).isEqualTo(400)
        assertThat(sc.s.body(r).get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED")
        assertThat(sc.s.get(rt(sc, "workflow-runs/${UUID.randomUUID()}")).response.status).isEqualTo(404)
        assertThat(sc.s.post(rt(sc, "workflow-runs/${UUID.randomUUID()}/cancel"), "{}").response.status).isEqualTo(404)
    }
}
