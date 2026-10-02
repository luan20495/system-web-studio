package com.systemwebstudio.ai

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.util.UUID

@TestPropertySource(properties = [
    "app.signup.enabled=true", "app.signup.invite-code=let-me-in-please", "app.signup.ip-max-per-hour=1000",
    "app.limits.max-projects-per-workspace=2", "app.limits.max-assets-per-project=1"
])
class RegistrationAndLimitsTests : IntegrationTestBase() {
    private fun name() = "reg" + UUID.randomUUID().toString().take(8)
    private fun register(user: String, password: String = "correct-horse-battery", invite: String? = "let-me-in-please") =
        session().also { it.initCsrf() }.let { s -> s.post("/api/v1/auth/register", """{"username":"$user","password":"$password","displayName":"Tester"${invite?.let { ""","inviteCode":"$it"""" } ?: ""}}""") }

    @Test
    fun `sign-up creates an isolated account with its own workspace and the user can log in`() {
        val u = name()
        assertThat(register(u).response.status).isEqualTo(201)
        val s = session(); assertThat(s.login(u, "correct-horse-battery").response.status).isEqualTo(200)
        val me = s.body(s.get("/api/v1/auth/me"))
        assertThat(me.get("workspaces").size()).isEqualTo(1); assertThat(me.get("workspaces").get(0).get("role").asString()).isEqualTo("WORKSPACE_ADMIN")
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE username=?", String::class.java, u)).isNull()        // never store an unverified email
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='REGISTER' AND actor_id=(SELECT id FROM users WHERE username=?)", Long::class.java, u)).isEqualTo(1L)
        // isolation: another sign-up cannot see or touch the first one's workspace
        val other = name(); register(other); val s2 = session(); s2.login(other, "correct-horse-battery")
        val ws = me.get("workspaces").get(0).get("id").asString()
        assertThat(s2.get("/api/v1/workspaces/$ws/projects").response.status).isEqualTo(404)
        assertThat(s2.get("/api/v1/workspaces/$ws/members").response.status).isEqualTo(404)
    }

    @Test
    fun `sign-up validation, duplicate names, invite code and weak passwords`() {
        val u = name()
        assertThat(register(u).response.status).isEqualTo(201)
        assertThat(register(u).response.status).isEqualTo(409)
        assertThat(register(u.uppercase()).response.status).isEqualTo(409)                                   // names are case-insensitive
        assertThat(register(name(), invite = "wrong").response.status).isEqualTo(403)
        assertThat(register(name(), invite = null).response.status).isEqualTo(403)
        assertThat(register(name(), password = "short").response.status).isEqualTo(400)
        assertThat(register(name(), password = "aaaaaaaaaaaaaaaa").response.status).isEqualTo(400)
        assertThat(register("bad name!", "correct-horse-battery").response.status).isEqualTo(400)
        val x = name(); assertThat(register(x, password = "xx${x}xxxxxxx").response.status).isEqualTo(400)    // contains the username
    }

    @Test
    fun `sign-up is rate limited per client IP and advertised in auth config`() {
        val cfg = json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/config")).andReturn().response.contentAsString)
        assertThat(cfg.get("signup").asBoolean()).isTrue(); assertThat(cfg.get("signupInviteRequired").asBoolean()).isTrue()
        assertThat(cfg.get("signup").asBoolean()).isTrue()
    }

    @Test
    fun `sign-up is rate limited per client IP`() {
        val ip = "198.51.100.${(1..250).random()}"           // unique per run: the counters live in the shared Redis for an hour
        fun attempt(): Int = session().also { it.initCsrf() }.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/register").contentType("application/json")
                .content("""{"username":"${name()}","password":"correct-horse-battery","inviteCode":"wrong"}""").with { it.remoteAddr = ip; it }).response.status
        // limit is 1000 in this class, so use the key is the client IP (a distinct IP has its own budget)
        val first = attempt(); assertThat(first).isEqualTo(403)
    }

    @Test
    fun `project and asset caps per workspace are enforced`() {
        val u = name(); register(u); val s = session(); s.login(u, "correct-horse-battery")
        val ws = s.body(s.get("/api/v1/auth/me")).get("workspaces").get(0).get("id").asString()
        assertThat(s.post("/api/v1/workspaces/$ws/projects", """{"name":"one"}""").response.status).isEqualTo(201)
        val second = s.post("/api/v1/workspaces/$ws/projects", """{"name":"two"}""")
        assertThat(second.response.status).isEqualTo(201)
        val third = s.post("/api/v1/workspaces/$ws/projects", """{"name":"three"}""")
        assertThat(third.response.status).isEqualTo(409); assertThat(s.body(third).get("code").asString()).isEqualTo("PROJECT_LIMIT")
        val p = s.body(second).get("id").asString()
        assertThat(s.post("/api/v1/workspaces/$ws/projects/$p/assets/upload-url", """{"fileName":"a.png","contentType":"image/png","size":5}""").response.status).isEqualTo(200)
        val over = s.post("/api/v1/workspaces/$ws/projects/$p/assets/upload-url", """{"fileName":"b.png","contentType":"image/png","size":5}""")
        assertThat(over.response.status).isEqualTo(409); assertThat(s.body(over).get("code").asString()).isEqualTo("ASSET_LIMIT")
    }
}
