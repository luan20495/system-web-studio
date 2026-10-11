package com.systemwebstudio.settings

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.UUID

/**
 * Lockdown defaults, editable policy settings, storage quota and artifact retention.
 * `app.signup.max-users` (default 500) is raised for this class: all test classes share one PostgreSQL and the suite has grown past 500 accounts, so in a full run the
 * registration inside the sign-up test answered 503 SIGNUP_FULL (the sign-up cap itself is tested in RegistrationAndLimitsTests).
 */
@TestPropertySource(properties = ["app.deploy.provider=static", "app.render.url=http://127.0.0.1:9", "app.signup.max-users=1000000"])
class LockdownSettingsTests : IntegrationTestBase() {
    @Autowired lateinit var settings: SettingsService
    @Autowired lateinit var redis: org.springframework.data.redis.core.StringRedisTemplate
    @Autowired lateinit var retention: com.systemwebstudio.maintenance.ArtifactRetentionService

    @AfterEach fun reset() { jdbc.update("DELETE FROM system_settings"); settings.invalidate() }
    private fun admin() = sessionFor(fx.user("setadm", systemAdmin = true).username)

    @Test
    fun `public sign-up is off by default, existing accounts still log in, an admin can enable it only with confirmation`() {
        val r = session().post("/api/v1/auth/register", """{"username":"newbie-${UUID.randomUUID().toString().take(6)}","password":"abc123def"}""")
        assertThat(r.response.status).isEqualTo(404); assertThat(session().body(r).get("code").asString()).isEqualTo("SIGNUP_DISABLED")
        assertThat(session().body(session().get("/api/v1/auth/config")).get("signup").asBoolean()).isFalse()
        assertThat(sessionFor(fx.user("existing").username).get("/api/v1/auth/me").response.status).isEqualTo(200)

        val a = admin()
        val noConfirm = a.put("/api/v1/admin/settings/policies/signup.enabled", """{"value":"true"}""")
        assertThat(noConfirm.response.status).isEqualTo(428); assertThat(a.body(noConfirm).get("code").asString()).isEqualTo("CONFIRMATION_REQUIRED")
        assertThat(sessionFor(fx.user("plain").username).put("/api/v1/admin/settings/policies/signup.enabled", """{"value":"true","confirm":true}""").response.status).isEqualTo(403)
        assertThat(a.put("/api/v1/admin/settings/policies/signup.enabled", """{"value":"maybe","confirm":true}""").response.status).isEqualTo(400)
        val on = a.body(a.put("/api/v1/admin/settings/policies/signup.enabled", """{"value":"true","confirm":true}"""))
        redis.keys("*signup*").forEach { redis.delete(it) }                 // other test classes share Redis and the client IP
        assertThat(on.get("value").asString()).isEqualTo("true"); assertThat(on.get("overridden").asBoolean()).isTrue()
        assertThat(session().post("/api/v1/auth/register", """{"username":"newbie-${UUID.randomUUID().toString().take(6)}","password":"abc123def"}""").response.status).isEqualTo(201)
        assertThat(a.delete("/api/v1/admin/settings/policies/signup.enabled").response.status).isEqualTo(200)
        assertThat(session().body(session().get("/api/v1/auth/config")).get("signup").asBoolean()).isFalse()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action IN ('SETTING_CHANGED','SETTING_RESET') AND resource_id = 'signup.enabled'", Long::class.java)).isEqualTo(2)
    }

    @Test
    fun `numeric settings are range checked and the catalog lists defaults`() {
        val a = admin()
        assertThat(a.put("/api/v1/admin/settings/policies/build.max-per-user-per-day", """{"value":"-1"}""").response.status).isEqualTo(400)
        assertThat(a.put("/api/v1/admin/settings/policies/no.such.key", """{"value":"1"}""").response.status).isEqualTo(404)
        val list = a.body(a.get("/api/v1/admin/settings/policies")).toList().associateBy { it.get("key").asString() }
        assertThat(list.keys).contains("signup.enabled", "build.max-per-user-per-day", "retention.preview-days", "storage.max-assets-mib-per-project")
        assertThat(list["signup.enabled"]!!.get("risk").asString()).isEqualTo("HIGH")
    }

    @Test
    fun `asset storage quota per project`() {
        val sc = scenario()
        jdbc.update("INSERT INTO system_settings (key, value) VALUES ('storage.max-assets-mib-per-project', '1')"); settings.invalidate()
        val r = sc.s.post("${sc.base}/assets/upload-url", """{"fileName":"big.png","contentType":"image/png","size":${2 * 1024 * 1024}}""")
        assertThat(r.response.status).isEqualTo(413); assertThat(sc.s.body(r).get("code").asString()).isEqualTo("STORAGE_QUOTA")
        assertThat(sc.s.post("${sc.base}/assets/upload-url", """{"fileName":"small.png","contentType":"image/png","size":1000}""").response.status).isEqualTo(200)
    }

    @Test
    fun `public publishing can be disabled by policy`() {
        val sc = scenario()
        jdbc.update("INSERT INTO system_settings (key, value) VALUES ('publish.public-enabled', 'false')"); settings.invalidate()
        val r = sc.s.post("${sc.base}/publish", """{"visibility":"PUBLIC","expectedRevision":${sc.revision()}}""", "Idempotency-Key" to "pub-off-${UUID.randomUUID()}")
        assertThat(r.response.status).isEqualTo(403); assertThat(sc.s.body(r).get("code").asString()).isEqualTo("PUBLIC_PUBLISH_DISABLED")
    }

    @Test
    fun `retention keeps the served artifact and the last N rollbacks, deletes older ones and audits each deletion`() {
        val sc = scenario()
        // three successful deployments with their own artifacts (built directly; the render worker is not needed here)
        jdbc.update("INSERT INTO sites (project_id, slug) VALUES (?, ?)", sc.projectId, "ret-${sc.projectId.toString().take(8)}")
        val version = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id = ? LIMIT 1", UUID::class.java, sc.projectId)
        val deps = (1..3).map { i ->
            val art = UUID.randomUUID(); val dep = UUID.randomUUID()
            jdbc.update("""INSERT INTO artifacts (id, project_id, sha256, storage_prefix, file_count, total_bytes, manifest, created_at)
                VALUES (?,?,?,?,1,10,'[]'::jsonb, now() - interval '2 hours')""", art, sc.projectId, "%064d".format(i), "${sc.projectId}/x$i")
            jdbc.update("""INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, artifact_id, created_at)
                VALUES (?,?,?,?,?,'PUBLIC','RUNNING','static',?, now() - make_interval(mins => ?))""", dep, sc.ws, sc.projectId, version, sc.user.id, art, 30 - i)
            dep to art
        }
        jdbc.update("UPDATE sites SET current_deployment_id = ? WHERE project_id = ?", deps[0].first, sc.projectId)       // serving the OLDEST
        jdbc.update("INSERT INTO system_settings (key, value) VALUES ('retention.rollback-deployments', '1')"); settings.invalidate()
        val result = retention.run(dryRun = false)
        val alive = jdbc.queryForList("SELECT id FROM artifacts WHERE project_id = ? AND deleted_at IS NULL", UUID::class.java, sc.projectId).toSet()
        assertThat(alive).containsExactlyInAnyOrder(deps[0].second, deps[2].second)          // served + newest rollback
        assertThat(result.artifactsDeleted).isGreaterThanOrEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'ARTIFACT_DELETED' AND resource_id = ?", Long::class.java, deps[1].second.toString())).isEqualTo(1)
        val back = sc.s.post("${sc.base}/site/rollback", """{"deploymentId":"${deps[1].first}"}""")
        assertThat(back.response.status).isEqualTo(400)                                          // its artifact is gone
        // admin preview and run endpoints
        val a = admin()
        assertThat(a.get("/api/v1/admin/retention/preview").response.status).isEqualTo(200)
        assertThat(sc.s.get("/api/v1/admin/retention/preview").response.status).isEqualTo(403)
    }
}
