package com.systemwebstudio.code

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.settings.SettingsService
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** Enforcement (not just configuration) of every build quota: the request is refused with a stable code, recorded, and visible to admins with its reason. */
class QuotaEnforcementTests : IntegrationTestBase() {
    @Autowired lateinit var policy: BuildPolicyService
    @Autowired lateinit var settings: SettingsService
    private val wide = mapOf("build.max-per-org-per-day" to 100000, "build.max-per-workspace-per-day" to 100000, "build.max-per-project-per-day" to 100000,
        "build.max-per-user-per-day" to 100000, "build.max-concurrent-per-user" to 100000, "build.max-concurrent-per-workspace" to 100000)

    @BeforeEach fun open() { set(wide); jdbc.update("DELETE FROM build_rejections") }
    @AfterEach fun reset() { jdbc.update("DELETE FROM system_settings"); settings.invalidate() }
    private fun set(m: Map<String, Int>) { m.forEach { (k, v) -> jdbc.update("INSERT INTO system_settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", k, v.toString()) }; settings.invalidate() }
    private fun job(sc: Scenario, status: String = "SUCCEEDED") = jdbc.update("INSERT INTO build_jobs (id, project_id, purpose, status, queued_at, workspace_id, requested_by) VALUES (?,?, 'PREVIEW', ?, now(), ?, ?)",
        UUID.randomUUID(), sc.projectId, status, sc.ws, sc.user.id)
    private fun code(sc: Scenario) = assertThatThrownBy { policy.requireCapacity(sc.projectId, sc.ws, sc.user.id) }.isInstanceOf(ApiException::class.java)

    @Test
    fun `every build quota scope refuses with its own code and the reason is recorded`() {
        val today = "queued_at >= date_trunc('day', now())"
        fun used(where: String, vararg a: Any) = jdbc.queryForObject("SELECT count(*) FROM build_jobs WHERE $today$where", Int::class.java, *a)!!
        val cases = listOf("build.max-per-org-per-day" to "BUILD_QUOTA_ORG_DAILY", "build.max-per-workspace-per-day" to "BUILD_QUOTA_WORKSPACE_DAILY",
            "build.max-per-project-per-day" to "BUILD_QUOTA_PROJECT_DAILY", "build.max-per-user-per-day" to "BUILD_QUOTA_USER_DAILY")
        for ((key, expected) in cases) {
            val sc = scenario(); set(wide); job(sc)
            policy.requireCapacity(sc.projectId, sc.ws, sc.user.id)                                   // within quota: allowed
            val n = when (key) { "build.max-per-org-per-day" -> used(""); "build.max-per-workspace-per-day" -> used(" AND workspace_id = ?", sc.ws)
                "build.max-per-project-per-day" -> used(" AND project_id = ?", sc.projectId); else -> used(" AND requested_by = ?", sc.user.id) }
            set(mapOf(key to n))                                                                      // limit = used
            val e = org.junit.jupiter.api.Assertions.assertThrows(ApiException::class.java) { policy.requireCapacity(sc.projectId, sc.ws, sc.user.id) }
            assertThat(e.code).describedAs(key).isEqualTo(expected); assertThat(e.status.value()).isEqualTo(429)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM build_rejections WHERE reason_code = ? AND project_id = ?", Long::class.java, expected, sc.projectId)).isEqualTo(1)
        }
    }

    @Test
    fun `concurrent build limits count only queued and running jobs`() {
        val sc = scenario()
        set(mapOf("build.max-concurrent-per-user" to 2)); job(sc, "RUNNING"); job(sc, "SUCCEEDED"); job(sc, "FAILED")
        policy.requireCapacity(sc.projectId, sc.ws, sc.user.id)                                       // 1 active < 2
        job(sc, "QUEUED")
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(ApiException::class.java) { policy.requireCapacity(sc.projectId, sc.ws, sc.user.id) }.code).isEqualTo("BUILD_QUOTA_USER_CONCURRENT")
        set(wide); set(mapOf("build.max-concurrent-per-workspace" to 2))
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(ApiException::class.java) { policy.requireCapacity(sc.projectId, sc.ws, sc.user.id) }.code).isEqualTo("BUILD_QUOTA_WORKSPACE_CONCURRENT")
    }

    @Test
    fun `oversized source is refused, build duration and artifact size limits come from policy, builds can be switched off`() {
        val sc = scenario()
        set(mapOf("build.max-repo-mib" to 1)); policy.requireRepoSize(sc.projectId, sc.ws, sc.user.id, 1024 * 1024)
        val e = org.junit.jupiter.api.Assertions.assertThrows(ApiException::class.java) { policy.requireRepoSize(sc.projectId, sc.ws, sc.user.id, 1024 * 1024 + 1) }
        assertThat(e.code).isEqualTo("REPO_TOO_LARGE"); assertThat(e.status.value()).isEqualTo(413)
        set(mapOf("build.max-duration-seconds" to 60, "build.max-artifact-mib" to 3))
        assertThat(policy.maxDurationSeconds()).isEqualTo(60); assertThat(policy.maxArtifactBytes()).isEqualTo(3L * 1024 * 1024)
        set(mapOf("source-apps.build-enabled" to 0)); jdbc.update("UPDATE system_settings SET value = 'false' WHERE key = 'source-apps.build-enabled'"); settings.invalidate()
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(ApiException::class.java) { policy.requireCapacity(sc.projectId, sc.ws, sc.user.id) }.code).isEqualTo("BUILDS_DISABLED")
    }

    @Test
    fun `admins see why requests were rejected`() {
        val sc = scenario(); set(mapOf("build.max-per-project-per-day" to 0))
        org.junit.jupiter.api.Assertions.assertThrows(ApiException::class.java) { policy.requireCapacity(sc.projectId, sc.ws, sc.user.id) }
        val admin = sessionFor(fx.user("quotaadm", systemAdmin = true).username)
        val rej = admin.body(admin.get("/api/v1/admin/builds")).get("rejections").toList()
        assertThat(rej.map { it.get("reason").asString() }).contains("BUILD_QUOTA_PROJECT_DAILY")
        assertThat(rej.first { it.get("reason").asString() == "BUILD_QUOTA_PROJECT_DAILY" }.get("detail").asString()).contains("PROJECT_DAILY")
        assertThat(sc.s.get("/api/v1/admin/builds").response.status).isEqualTo(403)
    }
}
