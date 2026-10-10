package com.systemwebstudio.tenancy

import com.systemwebstudio.organization.InMemoryOrganizationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import java.util.UUID

/**
 * C1 security matrix, part 2: the application runtime / data routes (mounted only with app.data-platform.enabled + app.workflow.enabled, as in production V1).
 * Wrong project / workspace ids answer like missing ids; APP_VIEW / APP_USE (project VIEWER) never reach QUERY_EXECUTE, TEST mode, bindings or data-source authority.
 */
@Import(InMemoryOrganizationConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(
    properties = [
        "app.workflow.enabled=true", "app.data-platform.enabled=true", "app.workflow.allow-volatile-stores=true", "app.workflow.worker-delay-ms=3600000",
        "app.secrets.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    ]
)
class SecurityRuntimeRoutesMatrixTests : SecurityMatrixSupport() {
    private val workflowBody = """{"idempotencyKey":"sm-wf-key-0001"}"""

    @Test
    fun `R1 wrong project id on the app-runtime and data-binding routes, wrong workspace id on the data-source routes - answered exactly like a random id`() {
        val sys = sysAdmin(); val a = company(sys); val b = company(sys)
        val w1 = wsIn(a, "W1"); val w2 = wsIn(a, "W2"); val wB = wsIn(b)
        val u = memberOf(w1, "WORKSPACE_ADMIN"); fx.member(w2, u, "EDITOR"); val p2 = fx.project(w2, u, "P2")
        val ownerB = memberOf(wB, "EDITOR"); val pB = fx.project(wB, ownerB)
        val s = loginAs(u); val run = UUID.randomUUID()
        assertThat(s.get("${api(w2, p2.id)}/data-bindings").response.status).describedAs("positive control: P2 under its own workspace").isEqualTo(200)
        for ((label, foreign) in listOf("P2 under W1" to p2.id, "B's project under W1" to pB.id)) {
            fun p(it: String) = api(w1, UUID.fromString(it))
            likeMissing("$label query run", 404, "PROJECT_NOT_FOUND", foreign) { s.post("${p(it)}/app-runtime/queries/q1/run", "{}") }
            likeMissing("$label action execute", 404, "PROJECT_NOT_FOUND", foreign) { s.post("${p(it)}/app-runtime/actions/a1/execute", "{}") }
            likeMissing("$label workflow start", 404, "PROJECT_NOT_FOUND", foreign) { s.post("${p(it)}/app-runtime/workflows/wf1/runs", workflowBody) }
            likeMissing("$label workflow status", 404, "PROJECT_NOT_FOUND", foreign) { s.get("${p(it)}/app-runtime/workflow-runs/$run") }
            likeMissing("$label workflow cancel", 404, "PROJECT_NOT_FOUND", foreign) { s.post("${p(it)}/app-runtime/workflow-runs/$run/cancel") }
            likeMissing("$label bindings", 404, "PROJECT_NOT_FOUND", foreign) { s.get("${p(it)}/data-bindings") }
            likeMissing("$label bind", 404, "PROJECT_NOT_FOUND", foreign) { s.put("${p(it)}/data-bindings/LIVE/slot1", """{"dataSourceId":"${UUID.randomUUID()}"}""") }
        }
        for (path in listOf("data-sources", "data-sources/connectors", "data-sources/${UUID.randomUUID()}"))
            likeMissing("B's workspace $path", 404, "WORKSPACE_NOT_FOUND", wB) { s.get("/api/v1/workspaces/$it/$path") }
        likeMissing("B's workspace delete data source", 404, "WORKSPACE_NOT_FOUND", wB) { s.delete("/api/v1/workspaces/$it/data-sources/${UUID.randomUUID()}") }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM data_source_bindings WHERE project_id IN (?, ?)", Long::class.java, p2.id, pB.id)).isZero()
    }

    @Test
    fun `R2 a project VIEWER (APP_VIEW + APP_USE) gets no QUERY_EXECUTE, no TEST mode, no bindings and no data-source authority - an EDITOR passes the same gate`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val owner = memberOf(ws, "EDITOR"); val p = fx.project(ws, owner)
        val viewer = loginAs(memberOf(ws, "VIEWER", p, "VIEWER")); val editor = loginAs(memberOf(ws, "VIEWER", p, "EDITOR"))
        val base = api(ws, p.id)
        assertThat(scopePerms(me(viewer), p.id)).containsExactlyInAnyOrder("APP_VIEW", "APP_USE")
        // AppRuntimeDataController.kt:66-67: APP_USE (LIVE) then QUERY_EXECUTE - a VIEWER stops at the second check
        val q = viewer.post("$base/app-runtime/queries/q1/run", "{}")
        forbidden(q, "VIEWER LIVE query"); assertThat(answer(q).body).contains("Missing permission: QUERY_EXECUTE")
        forbidden(viewer.post("$base/app-runtime/queries/q1/run", """{"mode":"TEST"}"""), "VIEWER TEST query (PROJECT_EDIT)")
        // AppRuntimeActionController.kt:53 / :79: TEST mode needs PROJECT_EDIT
        forbidden(viewer.post("$base/app-runtime/actions/a1/execute", """{"mode":"TEST"}"""), "VIEWER TEST action")
        forbidden(viewer.post("$base/app-runtime/workflows/wf1/runs", """{"mode":"TEST","idempotencyKey":"sm-wf-key-0002"}"""), "VIEWER TEST workflow")
        // DataBindingController.kt:340: bindings are application configuration (PROJECT_EDIT)
        forbidden(viewer.get("$base/data-bindings"), "VIEWER bindings")
        forbidden(viewer.put("$base/data-bindings/LIVE/slot1", """{"dataSourceId":"${UUID.randomUUID()}"}"""), "VIEWER bind")
        // data sources: workspace VIEWER has no DATA_SOURCE_VIEW / _MANAGE (GatewayGuard.require -> 403 PERMISSION_DENIED)
        expect(viewer.get("/api/v1/workspaces/$ws/data-sources"), 403, "PERMISSION_DENIED", "VIEWER data-source list")
        expect(viewer.delete("/api/v1/workspaces/$ws/data-sources/${UUID.randomUUID()}"), 403, "PERMISSION_DENIED", "VIEWER data-source delete")

        // the EDITOR holds QUERY_EXECUTE: the permission gate opens and the request reaches the definition lookup (no AppDefinition -> 404 QUERY_NOT_FOUND)
        expect(editor.post("$base/app-runtime/queries/q1/run", "{}"), 404, "QUERY_NOT_FOUND", "EDITOR LIVE query")
        assertThat(editor.get("$base/data-bindings").response.status).describedAs("EDITOR bindings").isEqualTo(200)
    }
}
