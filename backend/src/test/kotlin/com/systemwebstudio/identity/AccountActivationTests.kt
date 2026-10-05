package com.systemwebstudio.identity

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Core product: admin creates an account, the user activates it with a one-time link; no temporary passwords, no self-promotion. */
class AccountActivationTests : IntegrationTestBase() {
    private fun admin() = sessionFor(fx.user("actadm", systemAdmin = true).username)
    private fun uname() = "nv" + UUID.randomUUID().toString().replace("-", "").take(8)
    private fun create(a: com.systemwebstudio.support.ApiSession, ws: UUID, u: String, role: String = "EDITOR") =
        a.post("/api/v1/admin/users", """{"username":"$u","displayName":"Nhân viên","email":"$u@example.com","workspaceId":"$ws","role":"$role"}""")

    @Test
    fun `create, activate once with own password, link is single use, token stored only as hash`() {
        val a = admin(); val ws = fx.workspace(); val u = uname()
        val r = create(a, ws, u); assertThat(r.response.status).isEqualTo(201)
        val link = a.body(r); val token = link.get("token").asString()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_tokens WHERE token_hash = ?", Long::class.java, token)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_tokens WHERE token_hash = ?", Long::class.java, AccountService.sha256(token))).isEqualTo(1)
        assertThat(session().login(u, "anything-123").response.status).isEqualTo(401)                     // pending: no password exists
        val pub = session()
        assertThat(pub.post("/api/v1/auth/activation/complete", """{"token":"$token","password":"short1"}""").response.status).isEqualTo(400)
        assertThat(pub.post("/api/v1/auth/activation/complete", """{"token":"$token","password":"Matkhau-moi-42"}""").response.status).isEqualTo(200)
        assertThat(pub.post("/api/v1/auth/activation/complete", """{"token":"$token","password":"Matkhau-khac-42"}""").response.status).isEqualTo(410)
        assertThat(session().login(u, "Matkhau-moi-42").response.status).isEqualTo(200)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'ACCOUNT_ACTIVATED' AND resource_id = ?", Long::class.java, link.get("userId").asString())).isEqualTo(1)
    }

    @Test
    fun `expired or forged links are refused, a new link replaces the old one, reset signs out`() {
        val a = admin(); val ws = fx.workspace(); val u = uname()
        val first = a.body(create(a, ws, u)); val id = first.get("userId").asString()
        val second = a.body(a.post("/api/v1/admin/users/$id/activation-link"))
        val pub = session()
        assertThat(pub.post("/api/v1/auth/activation/inspect", """{"token":"${first.get("token").asString()}"}""").response.status).isEqualTo(410)
        assertThat(pub.post("/api/v1/auth/activation/inspect", """{"token":"${"x".repeat(43)}"}""").response.status).isEqualTo(410)
        jdbc.update("UPDATE account_tokens SET expires_at = now() - interval '1 minute' WHERE user_id = ?", UUID.fromString(id))
        assertThat(pub.post("/api/v1/auth/activation/inspect", """{"token":"${second.get("token").asString()}"}""").response.status).isEqualTo(410)
        val third = a.body(a.post("/api/v1/admin/users/$id/activation-link"))
        assertThat(pub.post("/api/v1/auth/activation/complete", """{"token":"${third.get("token").asString()}","password":"Matkhau-moi-42"}""").response.status).isEqualTo(200)
        val reset = a.body(a.post("/api/v1/admin/users/$id/activation-link"))
        assertThat(reset.get("purpose").asString()).isEqualTo("RESET")
    }

    @Test
    fun `only admins create users, duplicates and bad roles refused, system admin needs confirmation, never self, never the last one`() {
        val a = admin(); val ws = fx.workspace(); val u = uname(); val emp = fx.user("emp"); fx.member(ws, emp, "EDITOR")
        assertThat(create(sessionFor(emp.username), ws, u).response.status).isEqualTo(403)
        assertThat(create(a, ws, u).response.status).isEqualTo(201)
        assertThat(create(a, ws, u).response.status).isEqualTo(409)
        assertThat(create(a, ws, uname(), "OWNER").response.status).isEqualTo(400)
        val id = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", UUID::class.java, u)
        assertThat(a.post("/api/v1/admin/users/$id/system-admin", """{"grant":true}""").response.status).isEqualTo(428)
        assertThat(a.post("/api/v1/admin/users/$id/system-admin", """{"grant":true,"confirm":true}""").response.status).isEqualTo(409)   // not activated yet
        assertThat(sessionFor(emp.username).post("/api/v1/admin/users/${emp.id}/system-admin", """{"grant":true,"confirm":true}""").response.status).isEqualTo(403)
        val boss = fx.user("boss", systemAdmin = true); val bs = sessionFor(boss.username)
        assertThat(bs.post("/api/v1/admin/users/${boss.id}/system-admin", """{"grant":false,"confirm":true}""").response.status).isEqualTo(409)
    }

    @Test
    fun `config reports needsSetup only when no enabled system admin exists`() {
        admin()
        assertThat(session().body(session().get("/api/v1/auth/config")).get("needsSetup").asBoolean()).isFalse()
    }
}
