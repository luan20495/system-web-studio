package com.systemwebstudio.c6

import com.systemwebstudio.app.definition.AppDefinitionTestSupport
import com.systemwebstudio.project.ProjectRepository
import com.systemwebstudio.support.IntegrationTestBase
import com.systemwebstudio.version.SchemaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode

/**
 * C6 QA tests (owner C6; NOT part of the product test tree). They are copied into a throw-away worktree of the SHA under test by
 * docs/parallel/c6/harness/run-overlay-tests.sh and run with Gradle there; the production tree is never touched.
 *
 * They close two HTTP-level verification gaps of requirements that are IMPLEMENTED but had no HTTP test (runtime-api.md section 5):
 *   GAP-C6-05  a user who holds APP_USE but NOT QUERY_EXECUTE is refused on the LIVE query route (R1)          -> C1-PRM-07
 *   GAP-C6-06  a user who holds APP_USE + ACTION_EXECUTE but NOT WORKFLOW_EXECUTE is refused on workflow start (R3) -> C1-PRM-10
 * Each has a positive control (a workspace admin, who holds the permission, is NOT refused) so the denial is attributable to the permission.
 */
@TestPropertySource(properties = ["app.workflow.enabled=true", "app.data-platform.enabled=true", "app.workflow.run-store=memory", "app.workflow.allow-volatile-stores=false", "app.workflow.worker-delay-ms=3600000", "app.workflow.action-run-sweep-delay-ms=3600000"])
class C6RuntimePermissionHttpTests : IntegrationTestBase() {
    @Autowired lateinit var schemas: SchemaRepository
    @Autowired lateinit var projects: ProjectRepository

    private val sample: JsonNode get() = AppDefinitionTestSupport.resource("valid-v2-sample.json")
    private fun rt(sc: Scenario, path: String) = "${sc.base}/app-runtime/$path"
    private fun withDefinition(): Scenario = scenario().also { schemas.upsertSchema(it.projectId, it.ws, sample) }
    private fun admin(sc: Scenario) = sessionFor(fx.user("wsadmin").also { fx.member(sc.ws, it, "WORKSPACE_ADMIN") }.username)

    /** a project VIEWER: holds APP_VIEW + APP_USE and nothing else (PermissionMatrix, D-C1-14) */
    private fun viewer(sc: Scenario) = sessionFor(fx.user("viewer").also {
        fx.member(sc.ws, it, "VIEWER")
        fx.projectRole(projects.findById(sc.projectId).get(), it, "VIEWER")
    }.username)

    @Test
    fun `GAP-C6-05 a project viewer with APP_USE but without QUERY_EXECUTE is refused 403 on the LIVE query route`() {
        val sc = withDefinition()
        val v = viewer(sc)
        val denied = v.post(rt(sc, "queries/orders-list/run"), "{}")
        assertThat(denied.response.status).describedAs("viewer LIVE query").isEqualTo(403)
        val body = v.body(denied)
        assertThat(body.get("code").asString()).isEqualTo("FORBIDDEN")
        assertThat(body.toString()).describedAs("the refusal must name the missing permission, not a different one").contains("QUERY_EXECUTE")
        // the same viewer is a member (not a stranger): the project is visible to it, so this is a permission refusal, not a 404 disclosure rule
        assertThat(v.get(sc.base).response.status).isEqualTo(200)
        // positive control: a workspace admin holds QUERY_EXECUTE and is NOT refused (nothing is published, so LIVE finds no definition: 404, never 403)
        val a = admin(sc)
        assertThat(a.post(rt(sc, "queries/orders-list/run"), "{}").response.status).describedAs("admin LIVE query").isNotEqualTo(403)
    }

    @Test
    fun `GAP-C6-05 the same viewer is also refused TEST mode of a query (needs APP_EDIT) and sends nothing to a data source`() {
        val sc = withDefinition()
        val v = viewer(sc)
        val denied = v.post(rt(sc, "queries/orders-list/run"), """{"mode":"TEST"}""")
        assertThat(denied.response.status).isEqualTo(403)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM data_idempotency", Long::class.java)).describedAs("a denied query leaves no idempotency state").isZero()
    }

    @Test
    fun `GAP-C6-06 an editor holding APP_USE and ACTION_EXECUTE but not WORKFLOW_EXECUTE is refused 403 on workflow start over HTTP`() {
        val sc = withDefinition()        // the scenario owner is a workspace EDITOR: APP_USE + ACTION_EXECUTE + APP_EDIT, never WORKFLOW_EXECUTE
        val denied = sc.s.post(rt(sc, "workflows/notify-flow/runs"), """{"mode":"TEST","input":{},"idempotencyKey":"c6-wf-test-0001"}""")
        assertThat(denied.response.status).describedAs("editor workflow start").isEqualTo(403)
        val body = sc.s.body(denied)
        assertThat(body.get("code").asString()).isEqualTo("FORBIDDEN")
        // nothing was created for the denied caller: no run row for this application
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_runs WHERE app_id = ?", Long::class.java, sc.projectId)).describedAs("denied start creates no run").isZero()
        // the denial is audited (C4: start needs APP_USE + WORKFLOW_EXECUTE and audits the denial)
        // positive control: a workspace admin holds WORKFLOW_EXECUTE and is NOT refused for the same request
        val a = admin(sc)
        val ok = a.post(rt(sc, "workflows/notify-flow/runs"), """{"mode":"TEST","input":{},"idempotencyKey":"c6-wf-test-0002"}""")
        assertThat(ok.response.status).describedAs("admin workflow start").isNotEqualTo(403)
    }

    @Test
    fun `GAP-C6-06 a viewer is refused workflow start and cancel of someone else's run over HTTP`() {
        val sc = withDefinition()
        val v = viewer(sc)
        val start = v.post(rt(sc, "workflows/notify-flow/runs"), """{"input":{},"idempotencyKey":"c6-wf-viewer-001"}""")
        assertThat(start.response.status).describedAs("viewer workflow start").isIn(403, 404)
        assertThat(start.response.status).describedAs("must never be a success").isNotIn(200, 201, 202)
        val cancel = v.post(rt(sc, "workflow-runs/${java.util.UUID.randomUUID()}/cancel"), "{}")
        assertThat(cancel.response.status).describedAs("cancel of an unknown run").isEqualTo(404)
    }
}
