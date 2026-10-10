package com.systemwebstudio.publish

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * S7 - a publish request that is refused BEFORE it is accepted must fail at the API and leave nothing behind: no deployment, no build / artifact, no
 * queued job (a job is only published for a persisted deployment id, after commit), no idempotency key, no event, no audit entry, no pointer move.
 * The 202 is reserved for a request that was accepted and persisted; a failure after acceptance is a FAILED deployment, which is a different thing.
 */
class PublishDeniedTests : IntegrationTestBase() {
    private class Footprint(val deployments: Long, val events: Long, val artifacts: Long, val keys: Long, val audits: Long, val pointer: String)

    private fun footprint(sc: Scenario) = Footprint(
        jdbc.queryForObject("SELECT count(*) FROM deployments WHERE project_id = ?", Long::class.java, sc.projectId)!!,
        jdbc.queryForObject("SELECT count(*) FROM deployment_events e JOIN deployments d ON d.id = e.deployment_id WHERE d.project_id = ?", Long::class.java, sc.projectId)!!,
        jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE project_id = ?", Long::class.java, sc.projectId)!!,
        jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE scope_key LIKE ?", Long::class.java, "publish:${sc.projectId}:%")!!,
        sc.auditCount("PUBLISH"),
        jdbc.queryForList("SELECT coalesce(current_deployment_id::text, '-') || '/' || coalesce(pointer_version::text, '-') FROM sites WHERE project_id = ?", String::class.java, sc.projectId).firstOrNull() ?: "no-site-row"
    )

    private fun assertUntouched(sc: Scenario, before: Footprint, what: String) {
        // a job would only ever be enqueued for a deployment row; give a (wrongly) queued one time to surface, then compare every record
        Thread.sleep(700)
        val after = footprint(sc)
        assertThat(after.deployments).describedAs("$what: deployments").isEqualTo(before.deployments)
        assertThat(after.events).describedAs("$what: deployment events").isEqualTo(before.events)
        assertThat(after.artifacts).describedAs("$what: artifacts").isEqualTo(before.artifacts)
        assertThat(after.keys).describedAs("$what: idempotency keys").isEqualTo(before.keys)
        assertThat(after.audits).describedAs("$what: PUBLISH audit entries").isEqualTo(before.audits)
        assertThat(after.pointer).describedAs("$what: active pointer").isEqualTo(before.pointer)
    }

    private fun body(sc: Scenario, visibility: String = "PUBLIC", rev: Long = sc.revision()) = """{"visibility":"$visibility","expectedRevision":$rev}"""

    @Test
    fun `a request without a session is 401 and creates nothing`() {
        val sc = scenario(); val before = footprint(sc)
        val r = session().post("${sc.base}/publish", body(sc), "Idempotency-Key" to "anon-key-0001")
        assertThat(r.response.status).isEqualTo(401)
        assertUntouched(sc, before, "anonymous")
    }

    @Test
    fun `a signed-in request without the CSRF token is refused and creates nothing`() {
        val sc = scenario(); val before = footprint(sc)
        val r = sc.s.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("${sc.base}/publish")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("Idempotency-Key", "csrf-key-0001").content(body(sc)), includeCsrf = false)
        assertThat(r.response.status).isIn(401, 403)
        assertUntouched(sc, before, "no CSRF")
    }

    @Test
    fun `viewer and editor roles are 403 and create nothing`() {
        val sc = scenario(); val project = fx.projects.findById(sc.projectId).get(); val before = footprint(sc)
        val viewer = fx.user("viewer"); fx.member(sc.ws, viewer, "VIEWER"); fx.projectRole(project, viewer, "VIEWER")
        val editor = fx.user("editor"); fx.member(sc.ws, editor, "VIEWER"); fx.projectRole(project, editor, "EDITOR")
        assertThat(sessionFor(viewer.username).post("${sc.base}/publish", body(sc), "Idempotency-Key" to "viewer-key-02").response.status).isEqualTo(403)
        assertThat(sessionFor(editor.username).post("${sc.base}/publish", body(sc), "Idempotency-Key" to "editor-key-02").response.status).isEqualTo(403)
        assertUntouched(sc, before, "viewer / editor")
    }

    @Test
    fun `a member of another workspace cannot publish and creates nothing`() {
        val sc = scenario(); val before = footprint(sc); val stranger = scenario()
        val r = stranger.s.post("${sc.base}/publish", body(sc), "Idempotency-Key" to "stranger-key-1")
        assertThat(r.response.status).isIn(403, 404)
        assertUntouched(sc, before, "foreign workspace")
    }

    @Test
    fun `the administrator policy that disables public publishing is 403 and the idempotency key is not kept`() {
        val sc = scenario(); val before = footprint(sc)
        jdbc.update("INSERT INTO system_settings (key, value) VALUES ('publish.public-enabled', 'false') ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value"); settingsService.invalidate()
        try {
            val r = sc.s.post("${sc.base}/publish", body(sc), "Idempotency-Key" to "policy-key-001")
            assertThat(r.response.status).isEqualTo(403)
            assertThat(sc.s.body(r).get("code").asString()).isEqualTo("PUBLIC_PUBLISH_DISABLED")
            assertUntouched(sc, before, "policy deny")                // the key row written before the policy check rolled back with the request
            // the same key can be used again once the policy allows it: it was never recorded as a successful publish
            jdbc.update("DELETE FROM system_settings WHERE key = 'publish.public-enabled'"); settingsService.invalidate()
            assertThat(sc.s.post("${sc.base}/publish", body(sc), "Idempotency-Key" to "policy-key-001").response.status).isEqualTo(202)
        } finally { jdbc.update("DELETE FROM system_settings WHERE key = 'publish.public-enabled'"); settingsService.invalidate() }
    }

    @Test
    fun `an invalid key, a missing key and a stale revision are 4xx and create nothing`() {
        val sc = scenario(); val before = footprint(sc)
        assertThat(sc.s.post("${sc.base}/publish", body(sc), "Idempotency-Key" to "short").response.status).isEqualTo(400)
        assertThat(sc.s.post("${sc.base}/publish", body(sc)).response.status).isEqualTo(400)
        assertThat(sc.s.post("${sc.base}/publish", body(sc, rev = sc.revision() - 1), "Idempotency-Key" to "stale-key-001").response.status).isEqualTo(409)
        assertThat(sc.s.post("${sc.base}/publish", """{"visibility":"WORLD","expectedRevision":${sc.revision()}}""", "Idempotency-Key" to "bad-vis-0001").response.status).isEqualTo(400)
        assertUntouched(sc, before, "invalid request")
    }

    @Test
    fun `an accepted publish is the only 202, and it is persisted`() {
        val sc = scenario(); val before = footprint(sc)
        val r = sc.s.post("${sc.base}/publish", body(sc), "Idempotency-Key" to "accepted-key-1")
        assertThat(r.response.status).isEqualTo(202)
        val id = UUID.fromString(sc.s.body(r).get("id").asString())
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments WHERE id = ?", Long::class.java, id)).isEqualTo(1L)
        assertThat(footprint(sc).deployments).isEqualTo(before.deployments + 1)
    }
}
