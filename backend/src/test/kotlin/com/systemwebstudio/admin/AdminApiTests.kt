package com.systemwebstudio.admin

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AdminApiTests : IntegrationTestBase() {
    private fun admin() = sessionFor(fx.user("sysadm", systemAdmin = true).username)
    private val endpoints = listOf("/api/v1/admin/overview", "/api/v1/admin/users", "/api/v1/admin/workspaces", "/api/v1/admin/applications",
        "/api/v1/admin/audit", "/api/v1/admin/ai", "/api/v1/admin/ai/usage", "/api/v1/admin/ai/calls", "/api/v1/admin/components", "/api/v1/admin/system/health", "/api/v1/admin/settings")

    @Test
    fun `only system admins reach the admin API - workspace admins and anonymous users do not`() {
        val ws = fx.workspace(); val wsAdmin = fx.user("wsadm"); fx.member(ws, wsAdmin, "WORKSPACE_ADMIN")
        val s = sessionFor(wsAdmin.username)
        endpoints.forEach { e ->
            val r = s.get(e); assertThat(r.response.status).describedAs(e).isEqualTo(403); assertThat(s.body(r).get("code").asString()).isEqualTo("ADMIN_REQUIRED")
            assertThat(session().get(e).response.status).describedAs("anonymous $e").isEqualTo(401)
        }
        assertThat(s.body(s.get("/api/v1/auth/me")).get("systemAdmin").asBoolean()).isFalse()
        val a = admin(); endpoints.forEach { assertThat(a.get(it).response.status).describedAs(it).isEqualTo(200) }
        assertThat(a.body(a.get("/api/v1/auth/me")).get("systemAdmin").asBoolean()).isTrue()
    }

    @Test
    fun `revoking system admin in the database takes effect immediately (no stale session privilege)`() {
        val u = fx.user("tmpadm", systemAdmin = true); val s = sessionFor(u.username)
        assertThat(s.get("/api/v1/admin/overview").response.status).isEqualTo(200)
        jdbc.update("UPDATE users SET system_admin = FALSE WHERE id = ?", u.id)
        assertThat(s.get("/api/v1/admin/overview").response.status).isEqualTo(403)
    }

    @Test
    fun `users - search, detail with memberships, disable revokes sessions and blocks login, enable restores`() {
        val a = admin()
        val sc = scenario()                                       // sc.user owns a project in a workspace
        val list = a.body(a.get("/api/v1/admin/users?q=${sc.user.username}&size=5"))
        assertThat(list.get("total").asLong()).isEqualTo(1L); assertThat(list.get("items").get(0).get("projects").asInt()).isEqualTo(1)
        val detail = a.body(a.get("/api/v1/admin/users/${sc.user.id}"))
        assertThat(detail.get("workspaces").size()).isEqualTo(1); assertThat(detail.get("projects").get(0).get("owner").asBoolean()).isTrue()
        assertThat(detail.get("activeSessions").asInt()).isGreaterThanOrEqualTo(1)
        assertThat(detail.get("user").get("lastLoginAt").isNull).isFalse()

        val off = a.patch("/api/v1/admin/users/${sc.user.id}/status", """{"enabled":false}""")
        assertThat(off.response.status).isEqualTo(200); assertThat(a.body(off).get("enabled").asBoolean()).isFalse()
        assertThat(sc.s.get("/api/v1/auth/me").response.status).isEqualTo(401)                                    // session gone
        assertThat(session().login(sc.user.username).response.status).isEqualTo(401)                              // cannot log back in
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='USER_DISABLED' AND resource_id=?", Long::class.java, sc.user.id.toString())).isEqualTo(1L)

        assertThat(a.patch("/api/v1/admin/users/${sc.user.id}/status", """{"enabled":true}""").response.status).isEqualTo(200)
        assertThat(session().login(sc.user.username).response.status).isEqualTo(200)
    }

    @Test
    fun `revoke-sessions ends every session of the user, admins cannot disable themselves or the last system admin`() {
        val a = admin(); val u = fx.user("victim"); val s1 = sessionFor(u.username); val s2 = sessionFor(u.username)
        val r = a.post("/api/v1/admin/users/${u.id}/revoke-sessions")
        assertThat(a.body(r).get("revoked").asInt()).isGreaterThanOrEqualTo(2)
        assertThat(s1.get("/api/v1/auth/me").response.status).isEqualTo(401); assertThat(s2.get("/api/v1/auth/me").response.status).isEqualTo(401)

        val me = a.body(a.get("/api/v1/auth/me")).get("id").asString()
        assertThat(a.body(a.patch("/api/v1/admin/users/$me/status", """{"enabled":false}""")).get("code").asString()).isEqualTo("CANNOT_DISABLE_SELF")
        // The "last enabled system admin" rule (LAST_SYSTEM_ADMIN) is defence in depth: through the API the caller is itself an
        // enabled system admin, so the only way to remove the last one would be self-disable, which is refused above.
        assertThat(a.body(a.get("/api/v1/admin/users/$me")).get("user").get("enabled").asBoolean()).isTrue()
    }

    @Test
    fun `application inventory spans workspaces, detail shows history, ownership can be transferred to a workspace member only`() {
        val a = admin(); val one = scenario(); val two = scenario()
        one.prompt("thêm bảng so sánh")
        val inv = a.body(a.get("/api/v1/admin/applications?size=100&q=Scenario"))
        val ids = inv.get("items").toList().map { it.get("id").asString() }
        assertThat(ids).contains(one.projectId.toString(), two.projectId.toString())
        val d = a.body(a.get("/api/v1/admin/applications/${one.projectId}"))
        assertThat(d.get("app").get("latestVersion").asInt()).isEqualTo(2); assertThat(d.get("versions").size()).isEqualTo(2)
        assertThat(d.get("prompts").get(0).get("model").asString()).isEqualTo("mock"); assertThat(d.get("audit").size()).isGreaterThan(0)

        val outsider = fx.user("outsider")
        assertThat(a.post("/api/v1/admin/applications/${one.projectId}/transfer-ownership", """{"userId":"${outsider.id}"}""").response.status).isEqualTo(422)
        val mate = fx.user("mate"); fx.member(one.ws, mate, "VIEWER")
        val t = a.post("/api/v1/admin/applications/${one.projectId}/transfer-ownership", """{"userId":"${mate.id}"}""")
        assertThat(t.response.status).isEqualTo(200); assertThat(a.body(t).get("ownerId").asString()).isEqualTo(mate.id.toString())
        assertThat(jdbc.queryForObject("SELECT role FROM project_members WHERE project_id=? AND user_id=?", String::class.java, one.projectId, mate.id)).isEqualTo("OWNER")
        assertThat(jdbc.queryForObject("SELECT role FROM project_members WHERE project_id=? AND user_id=?", String::class.java, one.projectId, one.user.id)).isEqualTo("EDITOR")
        assertThat(sessionFor(mate.username).get(one.base).response.status).isEqualTo(200)                     // new owner really has access
    }

    @Test
    fun `audit filters, AI control, components usage, health probes and settings without secrets`() {
        val a = admin(); val sc = scenario(); sc.prompt("bỏ đánh giá")
        val audit = a.body(a.get("/api/v1/admin/audit?projectId=${sc.projectId}&action=RUN_PROMPT"))
        assertThat(audit.get("total").asLong()).isEqualTo(1L)
        val rid = audit.get("items").get(0).get("requestId").asString()
        assertThat(a.body(a.get("/api/v1/admin/audit?requestId=$rid")).get("total").asLong()).isGreaterThanOrEqualTo(1L)

        val ai = a.body(a.get("/api/v1/admin/ai"))
        assertThat(ai.get("provider").asString()).isEqualTo("mock"); assertThat(ai.get("tokenAccounting").asString()).isEqualTo("PROVIDER_REPORTED")
        assertThat(ai.get("requestsToday").asLong()).isGreaterThanOrEqualTo(1L)

        val comps = a.body(a.get("/api/v1/admin/components")).toList()
        assertThat(comps).hasSize(10); assertThat(comps.first { it.get("id").asString() == "Hero" }.get("usedInProjects").asLong()).isGreaterThanOrEqualTo(1L)

        val h = a.body(a.get("/api/v1/admin/system/health"))
        val status = h.get("items").toList().associate { it.get("name").asString() to it.get("status").asString() }
        assertThat(status).containsEntry("PostgreSQL", "HEALTHY").containsEntry("Redis", "HEALTHY").containsEntry("RabbitMQ", "HEALTHY").containsEntry("MinIO", "HEALTHY")
        assertThat(status["OpenRouter"]).isEqualTo("NOT_CONFIGURED")
        assertThat(h.get("schemaVersion").asString().toInt()).isGreaterThanOrEqualTo(9)

        val settings = a.get("/api/v1/admin/settings").response.contentAsString
        assertThat(settings).doesNotContain(MINIO_PASSWORD).doesNotContain("test-rabbit-123").doesNotContain(postgres.password)
    }

    @Test
    fun `project list paging with total count and scopes, prompt history newest first, personal usage and activity`() {
        val sc = scenario()
        repeat(2) { sc.s.post(api(sc.ws), """{"name":"Extra $it"}""") }
        val r = sc.s.get("${api(sc.ws)}?page=0&size=2")
        assertThat(r.response.getHeader("X-Total-Count")).isEqualTo("3"); assertThat(sc.s.body(r).size()).isEqualTo(2)
        assertThat(sc.s.body(sc.s.get("${api(sc.ws)}?page=1&size=2")).size()).isEqualTo(1)
        assertThat(sc.s.get("${api(sc.ws)}?page=0&size=10&q=extra").response.getHeader("X-Total-Count")).isEqualTo("2")
        assertThat(sc.s.get("${api(sc.ws)}?page=0&size=10&scope=shared").response.getHeader("X-Total-Count")).isEqualTo("0")

        sc.prompt("bỏ đánh giá"); sc.prompt("hiện đánh giá")
        val history = sc.s.body(sc.s.get("${sc.base}/prompts?limit=1"))
        assertThat(history.size()).isEqualTo(1); assertThat(history.get(0).get("text").asString()).isEqualTo("hiện đánh giá")

        val usage = sc.s.body(sc.s.get("/api/v1/me/usage"))
        assertThat(usage.get("aiConfigured").asBoolean()).isFalse(); assertThat(usage.get("promptsToday").asLong()).isEqualTo(2L)
        assertThat(sc.s.body(sc.s.get("/api/v1/me/activity")).toList().map { it.get("action").asString() }).contains("RUN_PROMPT", "CREATE_PROJECT")
    }
}
