package com.systemwebstudio.identity

import com.systemwebstudio.support.IntegrationTestBase
import com.systemwebstudio.support.TestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*

class AuthSecurityTests : IntegrationTestBase() {
    @Test
    fun `anonymous protected API is 401 with structured body and creates no session`() {
        val r = mvc.perform(get("/api/v1/auth/me")).andReturn()
        assertThat(r.response.status).isEqualTo(401)
        val body = json.readTree(r.response.contentAsString)
        assertThat(body.get("code").asString()).isEqualTo("AUTHENTICATION_REQUIRED")
        assertThat(body.get("requestId").asString()).isEqualTo(r.response.getHeader("X-Request-Id"))
        assertThat(r.response.getHeaders("Set-Cookie").filter { it.contains("STUDIO_SESSION") }).isEmpty()
        assertThat(r.response.contentType).containsIgnoringCase("utf-8")
    }

    @Test
    fun `me returns identity roles and workspaces`() {
        val u = fx.user("alice"); val ws = fx.workspace(); fx.member(ws, u, "EDITOR")
        val s = sessionFor(u.username)
        val me = s.body(s.get("/api/v1/auth/me"))
        assertThat(me.get("username").asString()).isEqualTo(u.username)
        assertThat(me.get("workspaces").get(0).get("role").asString()).isEqualTo("EDITOR")
        assertThat(me.has("passwordHash")).isFalse()
    }

    @Test
    fun `wrong password is 401 and repeated failures end in 429 with Retry-After`() {
        val u = fx.user("brute")
        val s = session()
        val first = s.login(u.username, "wrong-password-1")
        assertThat(first.response.status).isEqualTo(401)
        assertThat(s.body(first).get("code").asString()).isEqualTo("INVALID_CREDENTIALS")
        repeat(4) { assertThat(s.login(u.username, "wrong-password-x").response.status).isEqualTo(401) }
        val blocked = s.login(u.username, TestFixtures.PASSWORD)   // even the right password is refused now
        assertThat(blocked.response.status).isEqualTo(429)
        assertThat(blocked.response.getHeader("Retry-After")).isNotNull()
        assertThat(s.body(blocked).get("code").asString()).isEqualTo("RATE_LIMITED")
    }

    @Test
    fun `missing CSRF token is 403 CSRF_INVALID`() {
        val u = fx.user()
        val r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""{"username":"${u.username}","password":"${TestFixtures.PASSWORD}"}""")).andReturn()
        assertThat(r.response.status).isEqualTo(403)
        assertThat(json.readTree(r.response.contentAsString).get("code").asString()).isEqualTo("CSRF_INVALID")
    }

    @Test
    fun `logout invalidates the session`() {
        val u = fx.user(); val s = sessionFor(u.username)
        assertThat(s.post("/api/v1/auth/logout").response.status).isEqualTo(200)
        assertThat(s.get("/api/v1/auth/me").response.status).isEqualTo(401)
    }

    @Test
    fun `disabled user loses access on the next request`() {
        val u = fx.user(); val s = sessionFor(u.username)
        assertThat(s.get("/api/v1/auth/me").response.status).isEqualTo(200)
        fx.disable(u.id)
        val r = s.get("/api/v1/auth/me")
        assertThat(r.response.status).isEqualTo(401)
        assertThat(s.body(r).get("code").asString()).isEqualTo("ACCOUNT_DISABLED")
        assertThat(s.get("/api/v1/auth/me").response.status).isEqualTo(401)
    }

    @Test
    fun `CORS allows the configured origin and rejects others`() {
        val ok = mvc.perform(options("/api/v1/auth/login").header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "POST")).andReturn()
        assertThat(ok.response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000")
        assertThat(ok.response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true")
        val bad = mvc.perform(options("/api/v1/auth/login").header("Origin", "http://evil.example")
            .header("Access-Control-Request-Method", "POST")).andReturn()
        assertThat(bad.response.status).isEqualTo(403)
        assertThat(bad.response.getHeader("Access-Control-Allow-Origin")).isNull()
    }

    @Test
    fun `login and failures are audited with request id`() {
        val u = fx.user("aud"); val s = session()
        s.login(u.username, "nope-nope-nope")
        val ok = s.login(u.username)
        val rows = jdbc.queryForList("SELECT action, request_id FROM audit_events WHERE resource_id IN (?, ?) ORDER BY created_at", u.username, u.id.toString())
        assertThat(rows.map { it["action"] }).contains("LOGIN_FAILURE", "LOGIN_SUCCESS")
        assertThat(rows.last()["request_id"]).isEqualTo(ok.response.getHeader("X-Request-Id"))
    }
}
