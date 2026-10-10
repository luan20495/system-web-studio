package com.systemwebstudio.tenancy

import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.organization.InMemoryOrganizationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContext
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.test.annotation.DirtiesContext
import java.util.UUID

/**
 * C1 hardening - C: sessions. Cookie replay after a global disable / re-enable, a completed RESET link signs the account out everywhere, a disabled account cannot sign
 * in, removing the tenant membership keeps the account but closes the company, and no session ever carries `users.password_hash`.
 *
 * OIDC (6623b92, OidcLoginHandlers.kt): the success handler now calls `eraseCredentials()` on the StudioUserDetails it loads before the authentication is stored in
 * the Redis session. Driving the real OIDC flow needs an IdP (authorization-code round trip, signed id_token), which this suite does not run; the handler change is a
 * one-line call covered by code review. What it relies on is tested here: (1) `StudioUserDetails.eraseCredentials()` really nulls the password (unit level) and
 * (2) a stored session payload is free of the hash - checked on the password login path, whose session is written by the same SecurityContextRepository.
 */
@Import(InMemoryOrganizationConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HardeningSessionTests : FinalIamTestBase() {
    @Autowired lateinit var sessions: FindByIndexNameSessionRepository<out Session>
    @Autowired lateinit var redisFactory: RedisConnectionFactory

    private val NEW_PASSWORD = "Org-Reset-2026-y"

    @Test
    fun `C1 a session cookie is dead after a global disable and stays dead after re-enable, the disabled account cannot sign in`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val u = fx.user("hs-dis"); fx.member(ws, u, "VIEWER")
        val s = sessionFor(u.username); val cookie = s.cookie(SESSION_COOKIE)!!
        assertThat(replay(cookie)).describedAs("positive control").isEqualTo(200)
        assertThat(replay(cookie, api(ws))).isEqualTo(200)

        assertThat(sys.patch("/api/v1/admin/users/${u.id}/status", """{"enabled":false}""").response.status).isEqualTo(200)
        assertThat(replay(cookie)).isEqualTo(401); assertThat(replay(cookie, api(ws))).isEqualTo(401)
        val login = session().login(u.username)
        assertThat(login.response.status).describedAs("disabled account login").isEqualTo(401)
        assertThat(login.response.cookies.map { it.name }).describedAs("no session issued").doesNotContain(SESSION_COOKIE)

        assertThat(sys.patch("/api/v1/admin/users/${u.id}/status", """{"enabled":true}""").response.status).isEqualTo(200)
        assertThat(replay(cookie)).describedAs("old cookie after re-enable").isEqualTo(401)
        assertThat(replay(cookie, api(ws))).isEqualTo(401)
        val fresh = session(); assertThat(fresh.login(u.username).response.status).isEqualTo(200)
        assertThat(fresh.get(api(ws)).response.status).isEqualTo(200)
        assertThat(replay(cookie)).describedAs("a fresh login does not revive the old cookie").isEqualTo(401)
    }

    @Test
    fun `C2 completing a password RESET link signs the account out of every session, old password refused, new one works`() {
        val sys = sysAdmin(); val c = company(sys)
        val username = uname("hs-reset")
        val created = c.admin.post("${base(c)}/users", """{"username":"$username","displayName":"Reset Me"}""")
        assertThat(created.response.status).isEqualTo(201)
        val userId = UUID.fromString(c.admin.body(created).get("userId").asString())
        val first = activateAndLogin(username, c.admin.body(created).get("token").asString())
        val second = session(); assertThat(second.login(username, PASSWORD).response.status).isEqualTo(200)
        val firstCookie = first.cookie(SESSION_COOKIE)!!; val secondCookie = second.cookie(SESSION_COOKIE)!!
        assertThat(firstCookie.value).isNotEqualTo(secondCookie.value)
        assertThat(replay(firstCookie)).isEqualTo(200); assertThat(replay(secondCookie)).isEqualTo(200)
        assertThat(sessions.findByPrincipalName(username)).hasSize(2)

        val link = sys.post("/api/v1/admin/users/$userId/activation-link")
        assertThat(link.response.status).isEqualTo(200)
        assertThat(sys.body(link).get("purpose").asString()).isEqualTo("RESET")
        val done = fromNewAddress("/api/v1/auth/activation/complete", """{"token":"${sys.body(link).get("token").asString()}","password":"$NEW_PASSWORD"}""")
        assertThat(done.response.status).isEqualTo(200)

        assertThat(sessions.findByPrincipalName(username)).describedAs("no session of the account is left in Redis").isEmpty()
        assertThat(replay(firstCookie)).isEqualTo(401); assertThat(replay(secondCookie)).isEqualTo(401)
        assertThat(first.get("/api/v1/auth/me").response.status).isEqualTo(401); assertThat(second.get("/api/v1/auth/me").response.status).isEqualTo(401)
        assertThat(session().login(username, PASSWORD).response.status).describedAs("old password").isEqualTo(401)
        val again = session(); assertThat(again.login(username, NEW_PASSWORD).response.status).describedAs("new password").isEqualTo(200)
        assertThat(again.get("/api/v1/auth/me").response.status).isEqualTo(200)
        // the link is single use: completing it again changes nothing
        assertThat(fromNewAddress("/api/v1/auth/activation/complete", """{"token":"${sys.body(link).get("token").asString()}","password":"Org-Other-2026-z"}""").response.status).isEqualTo(410)
        assertThat(again.get("/api/v1/auth/me").response.status).isEqualTo(200)
    }

    @Test
    fun `C3 removing the tenant membership keeps the account usable - login works, every route of that company is 404`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val u = fx.user("hs-rm"); tenantService.setMember(c.id, u.id, TenantRole.MEMBER); fx.member(ws, u, "VIEWER")
        val s = sessionFor(u.username)
        assertThat(s.get(base(c)).response.status).describedAs("member sees its company").isEqualTo(200)
        assertThat(s.get(api(ws)).response.status).isEqualTo(200)
        assertThat(tenantIds(me(s))).contains(c.id.toString())

        assertThat(c.admin.delete("${base(c)}/members/${u.id}").response.status).isEqualTo(204)
        val fresh = session(); assertThat(fresh.login(u.username).response.status).describedAs("the account itself is untouched").isEqualTo(200)
        for (who in listOf(s, fresh)) {
            assertThat(who.get("/api/v1/auth/me").response.status).isEqualTo(200)
            assertThat(who.get(base(c)).response.status).describedAs("tenant").isEqualTo(404)
            assertThat(who.get(api(ws)).response.status).describedAs("workspace of the tenant").isEqualTo(404)
            assertThat(who.get("/api/v1/workspaces/$ws/members").response.status).isEqualTo(404)
            assertThat(tenantIds(me(who))).doesNotContain(c.id.toString())
            assertThat(workspaceIds(me(who))).doesNotContain(ws.toString())
        }
    }

    @Test
    fun `C4 StudioUserDetails eraseCredentials nulls the password hash (what the OIDC success handler now calls)`() {
        val d = StudioUserDetails(UUID.randomUUID(), "oidc-user", "\$argon2id\$v=19\$m=65536,t=3,p=4\$c2FsdA\$aGFzaA", true, "OIDC", false, listOf(SimpleGrantedAuthority("ROLE_USER")))
        assertThat(d.password).isNotNull()
        assertThat(d).isInstanceOf(org.springframework.security.core.CredentialsContainer::class.java)
        d.eraseCredentials()
        assertThat(d.password).isNull()
        assertThat(d.username).isEqualTo("oidc-user"); assertThat(d.isEnabled).isTrue(); assertThat(d.authorities.map { it.authority }).containsExactly("ROLE_USER")
    }

    @Test
    fun `C5 the Redis session of a password login does not contain the password hash`() {
        val u = fx.user("hs-hash"); val s = sessionFor(u.username)
        assertThat(s.get("/api/v1/auth/me").response.status).isEqualTo(200)
        val hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String::class.java, u.id)!!
        assertThat(hash).isNotBlank()

        val found = sessions.findByPrincipalName(u.username)
        assertThat(found).hasSize(1)
        val (id, session) = found.entries.single()
        val ctx = session.getAttribute<SecurityContext>("SPRING_SECURITY_CONTEXT")
        assertThat(ctx).isNotNull()
        val principal = ctx!!.authentication!!.principal as StudioUserDetails
        assertThat(principal.userId).isEqualTo(u.id)
        assertThat(principal.password).describedAs("principal password in the stored session").isNull()
        assertThat(ctx.authentication!!.credentials).describedAs("authentication credentials in the stored session").isNull()

        // raw bytes of the stored hash, whatever the serializer: the username is there (positive control), the hash is not
        val raw = redisFactory.connection.use { conn -> conn.hashCommands().hGetAll("spring:session:sessions:$id".toByteArray()) }
        assertThat(raw).describedAs("session hash in Redis").isNotNull.isNotEmpty
        val dump = raw!!.entries.joinToString("|") { String(it.key, Charsets.ISO_8859_1) + "=" + String(it.value, Charsets.ISO_8859_1) }
        assertThat(dump).contains(u.username)
        assertThat(dump).doesNotContain(hash)
        assertThat(dump).doesNotContain(hash.substringAfterLast('$'))
    }
}
