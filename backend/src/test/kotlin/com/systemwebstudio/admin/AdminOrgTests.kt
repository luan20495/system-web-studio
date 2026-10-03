package com.systemwebstudio.admin

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.UUID

/** Stage H: departments/teams, application archive, hosting cost from measured quantities × explicit prices, security findings. */
class AdminOrgTests : IntegrationTestBase() {
    @Autowired lateinit var settings: com.systemwebstudio.settings.SettingsService
    @AfterEach fun clean() { jdbc.update("DELETE FROM system_settings"); settings.invalidate() }
    private fun admin() = sessionFor(fx.user("orgadm", systemAdmin = true).username)

    @Test
    fun `departments and teams - create, assign users and workspaces, delete only when empty, admin only, audited`() {
        val a = admin(); val sc = scenario()
        assertThat(sc.s.post("/api/v1/admin/departments", """{"name":"X"}""").response.status).isEqualTo(403)
        val dname = "Kinh doanh ${UUID.randomUUID().toString().take(5)}"
        val deps = a.body(a.post("/api/v1/admin/departments", """{"name":"$dname"}"""))
        val dep = deps.toList().first { it.get("name").asString() == dname }.get("id").asString()
        assertThat(a.post("/api/v1/admin/departments", """{"name":"$dname"}""").response.status).isEqualTo(409)
        assertThat(a.post("/api/v1/admin/departments", """{"name":"Nhóm lẻ","kind":"TEAM"}""").response.status).isEqualTo(400)       // a team needs a department
        val team = a.body(a.post("/api/v1/admin/departments", """{"name":"Nhóm Bắc","kind":"TEAM","parentId":"$dep"}""")).toList().first { it.get("name").asString() == "Nhóm Bắc" && it.get("parentId").asString() == dep }.get("id").asString()
        assertThat(a.put("/api/v1/admin/departments/assign/users/${sc.user.id}", """{"departmentId":"$team"}""").response.status).isEqualTo(200)
        assertThat(a.put("/api/v1/admin/departments/assign/workspaces/${sc.ws}", """{"departmentId":"$dep"}""").response.status).isEqualTo(200)
        assertThat(a.delete("/api/v1/admin/departments/$dep").response.status).isEqualTo(409)
        val list = a.body(a.get("/api/v1/admin/departments")).toList().associateBy { it.get("id").asString() }
        assertThat(list[team]!!.get("users").asInt()).isEqualTo(1); assertThat(list[dep]!!.get("workspaces").asInt()).isEqualTo(1)
        a.put("/api/v1/admin/departments/assign/users/${sc.user.id}", """{"departmentId":null}""")
        assertThat(a.delete("/api/v1/admin/departments/$team").response.status).isEqualTo(200)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action LIKE 'DEPARTMENT_%' OR action LIKE '%_DEPARTMENT_SET'", Long::class.java)).isGreaterThanOrEqualTo(5)
    }

    @Test
    fun `archive makes an application read-only and offline, restore makes it editable, admins see archived apps separately`() {
        val sc = scenario(); val a = admin()
        val rev = sc.revision()
        assertThat(sc.s.post("${sc.base}/archive").response.status).isEqualTo(200)
        assertThat(sc.s.body(sc.s.get(sc.base)).get("status").asString()).isEqualTo("ARCHIVED")
        assertThat(sc.s.get("${sc.base}/schema").response.status).isEqualTo(200)                       // still readable
        val edit = sc.s.patch("${sc.base}/schema", """{"expectedRevision":$rev,"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"x"}]}""")
        assertThat(edit.response.status).isEqualTo(403)
        assertThat(sc.s.post("${sc.base}/publish", """{"visibility":"PUBLIC","expectedRevision":$rev}""", "Idempotency-Key" to "arch-${UUID.randomUUID()}").response.status).isEqualTo(403)
        assertThat(sc.s.post("${sc.base}/archive").response.status).isEqualTo(409)
        val archived = a.body(a.get("/api/v1/admin/applications?status=archived&size=100")).get("items").toList().map { it.get("id").asString() }
        assertThat(archived).contains(sc.projectId.toString())
        assertThat(a.body(a.get("/api/v1/admin/applications?status=active&size=100")).get("items").toList().map { it.get("id").asString() }).doesNotContain(sc.projectId.toString())
        assertThat(a.post("/api/v1/admin/applications/${sc.projectId}/restore").response.status).isEqualTo(200)
        assertThat(sc.s.patch("${sc.base}/schema", """{"expectedRevision":${sc.revision()},"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"y"}]}""").response.status).isEqualTo(200)
        assertThat(sc.auditCount("APPLICATION_ARCHIVED")).isEqualTo(1); assertThat(sc.auditCount("APPLICATION_RESTORED")).isEqualTo(1)
    }

    @Test
    fun `hosting cost - measured quantities times explicit prices, unknown without a price, never estimated`() {
        val sc = scenario(); val a = admin()
        jdbc.update("DELETE FROM cost_prices")
        jdbc.update("""INSERT INTO artifacts (id, project_id, sha256, storage_prefix, file_count, total_bytes, manifest) VALUES (?,?,?,?,1,?, '[]'::jsonb)""",
            UUID.randomUUID(), sc.projectId, UUID.randomUUID().toString().replace("-", "").padEnd(64, '0'), "x/y", 2L * 1024 * 1024 * 1024)
        jdbc.update("""INSERT INTO build_jobs (id, project_id, purpose, status, queued_at, finished_at, cpu_ms, duration_ms) VALUES (?,?, 'PREVIEW', 'SUCCEEDED', now(), now(), 3600000, 120000)""",
            UUID.randomUUID(), sc.projectId)
        val before = a.body(a.get("/api/v1/admin/costs?days=30")).get("byApplication").toList().first { it.get("key").asString() == sc.projectId.toString() }
        assertThat(before.get("storageBytes").asLong()).isGreaterThanOrEqualTo(2L * 1024 * 1024 * 1024)
        assertThat(before.get("storageUsd").isNull).isTrue(); assertThat(before.get("cpuUsd").isNull).isTrue(); assertThat(before.get("complete").asBoolean()).isFalse()
        assertThat(sc.s.post("/api/v1/admin/costs/prices", """{"item":"BUILD_CPU_HOUR","unitPrice":1}""").response.status).isEqualTo(403)
        a.post("/api/v1/admin/costs/prices", """{"item":"BUILD_CPU_HOUR","unitPrice":0.05}""")
        a.post("/api/v1/admin/costs/prices", """{"item":"BUILD_MINUTE","unitPrice":0.008}""")
        assertThat(a.post("/api/v1/admin/costs/prices", """{"item":"STORAGE_GIB_MONTH","unitPrice":500,"currency":"VND"}""").response.status).isEqualTo(400)
        a.post("/api/v1/admin/costs/prices", """{"item":"STORAGE_GIB_MONTH","unitPrice":0.02}""")
        val r = a.body(a.get("/api/v1/admin/costs?days=30"))
        val app = r.get("byApplication").toList().first { it.get("key").asString() == sc.projectId.toString() }
        assertThat(BigDecimal(app.get("cpuUsd").asString())).isEqualByComparingTo("0.05")                     // 1 CPU hour × 0.05
        assertThat(BigDecimal(app.get("buildUsd").asString())).isEqualByComparingTo("0.016")                  // 2 minutes × 0.008
        assertThat(BigDecimal(app.get("storageUsd").asString())).isGreaterThanOrEqualTo(BigDecimal("0.04"))   // ≥ 2 GiB × 0.02 × 30/30
        assertThat(r.get("missingPrices").size()).isEqualTo(0); assertThat(r.get("egress").asString()).contains("chưa được đo")
    }

    @Test
    fun `security findings come from real scans, accepted package risks and settings, with counts and no score`() {
        val sc = scenario(); val a = admin()
        jdbc.update("""INSERT INTO build_jobs (id, project_id, purpose, status, queued_at, finished_at, scans) VALUES (?,?, 'PREVIEW', 'FAILED', now(), now(), CAST(? AS jsonb))""",
            UUID.randomUUID(), sc.projectId, """{"dependencies":{"findings":[{"package":"lodash@4.17.0","id":"GHSA-x","severity":"HIGH"}],"blocking":[]},
                "sourceSecrets":{"findings":[{"file":"/src/key.ts","rule":"generic-api-key","line":3}]},"outputSecrets":{"findings":[]}}""")
        jdbc.update("INSERT INTO system_settings (key, value) VALUES ('signup.enabled', 'true')"); settings.invalidate()
        assertThat(sc.s.get("/api/v1/admin/security/findings").response.status).isEqualTo(403)
        val r = a.body(a.get("/api/v1/admin/security/findings"))
        val mine = r.get("findings").toList().filter { it.get("resourceId")?.asString() == sc.projectId.toString() }
        assertThat(mine.map { it.get("source").asString() + ":" + it.get("severity").asString() }).contains("DEPENDENCY:HIGH", "SECRET:CRITICAL")
        assertThat(mine.first { it.get("source").asString() == "SECRET" }.get("detail").asString()).contains("/src/key.ts").doesNotContain("=")
        assertThat(r.get("findings").toList().any { it.get("source").asString() == "CONFIG" && it.get("resourceId").asString() == "signup.enabled" }).isTrue()
        assertThat(r.get("counts").get("CRITICAL").asInt()).isGreaterThanOrEqualTo(1)
        assertThat(r.has("score")).isFalse()
    }
}
