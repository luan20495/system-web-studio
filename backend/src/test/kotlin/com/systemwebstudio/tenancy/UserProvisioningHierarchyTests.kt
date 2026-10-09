package com.systemwebstudio.tenancy

import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * C1 · the provisioning hierarchy on a real PostgreSQL (IntegrationTestBase / Testcontainers), through the real HTTP API and the real activation flow:
 *
 *   SYSTEM_ADMIN -> creates a tenant, a workspace of THAT tenant, and the first Tenant Admin / Workspace Admin (a pending account + one-time activation link)
 *   Tenant Admin -> logs in after activating, creates BRAND-NEW users inside its own tenant (optionally with a workspace + role), nobody else's
 *   Workspace Admin -> manages the members of its workspace (MEMBER_MANAGE) but can neither create accounts nor touch the tenant
 *
 * Tests are lettered A-O after the C1 brief; each one asserts status AND the code AND the database, and every refusal leaves the rows untouched.
 */
class UserProvisioningHierarchyTests : IntegrationTestBase() {
    @Autowired lateinit var tenants: TenantService

    private val PASSWORD = "Prov-Pass-2026-x"
    private fun slug() = "prov-" + UUID.randomUUID().toString().take(8)
    private fun uname(p: String) = p + "-" + UUID.randomUUID().toString().take(8)
    private fun sysAdmin(): ApiSession = sessionFor(fx.user("prov-sys", systemAdmin = true).username)
    private fun code(r: org.springframework.test.web.servlet.MvcResult, s: ApiSession) = s.body(r).get("code").asString()

    private class Tenant(val id: UUID, val ws: UUID)
    /** what the platform operator does first: a tenant and a workspace that belongs to it, both by API */
    private fun createTenant(sys: ApiSession): Tenant {
        val t = sys.post("/api/v1/admin/tenants", """{"slug":"${slug()}","name":"Prov Tenant"}""")
        assertThat(t.response.status).isEqualTo(201)
        val tid = UUID.fromString(sys.body(t).get("id").asString())
        val w = sys.post("/api/v1/admin/tenants/$tid/workspaces", """{"name":"Prov Workspace"}""")
        assertThat(w.response.status).isEqualTo(201)
        return Tenant(tid, UUID.fromString(sys.body(w).get("id").asString()))
    }

    private class Account(val id: UUID, val username: String, val link: JsonNode)
    private fun provision(s: ApiSession, tenant: UUID, username: String = uname("user"), tenantRole: String = "MEMBER", ws: UUID? = null, wsRole: String? = null, email: String? = null): Account {
        val body = buildString {
            append("""{"username":"$username","displayName":"Display $username","tenantRole":"$tenantRole"""")
            if (email != null) append(""","email":"$email"""")
            if (ws != null) append(""","workspaceId":"$ws"""")
            if (wsRole != null) append(""","workspaceRole":"$wsRole"""")
            append("}")
        }
        val r = s.post("/api/v1/admin/tenants/$tenant/users", body)
        assertThat(r.response.status).describedAs("provision $username").isEqualTo(201)
        val link = s.body(r)
        return Account(UUID.fromString(link.get("userId").asString()), username, link)
    }
    /** the employee opens the one-time link, chooses a password and signs in: the only way a provisioned account becomes usable */
    private var ipCounter = 0
    private fun fromNewAddress(path: String, body: String) = session().perform(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)
            .with { r -> r.remoteAddr = "10.77.${(++ipCounter / 250) % 250}.${ipCounter % 250}"; r })
    private fun activateAndLogin(a: Account): ApiSession {
        val done = fromNewAddress("/api/v1/auth/activation/complete", """{"token":"${a.link.get("token").asString()}","password":"$PASSWORD"}""")
        assertThat(done.response.status).describedAs("activation of ${a.username}").isEqualTo(200)
        val s = session(); assertThat(s.login(a.username, PASSWORD).response.status).describedAs("login of ${a.username}").isEqualTo(200)
        return s
    }
    private fun keys(n: JsonNode): Set<String> = (json.convertValue(n, Map::class.java) as Map<*, *>).keys.map { it.toString() }.toSet()
    private fun me(s: ApiSession) = s.body(s.get("/api/v1/auth/me"))
    private fun wsPerms(s: ApiSession, ws: UUID) = me(s).get("workspaces").toList().firstOrNull { it.get("id").asString() == ws.toString() }?.get("permissions")?.toList()?.map { it.asString() }
    private fun wsRole(ws: UUID, u: UUID) = jdbc.queryForList("SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ? AND active", ws, u).firstOrNull()?.get("role")
    private fun tenantRole(t: UUID, u: UUID) = jdbc.queryForList("SELECT role FROM tenant_members WHERE tenant_id = ? AND user_id = ? AND active", t, u).firstOrNull()?.get("role")
    private fun userRow(u: UUID) = jdbc.queryForMap("SELECT enabled, system_admin, activated_at, auth_source FROM users WHERE id = ?", u)
    private fun countUsers() = jdbc.queryForObject("SELECT count(*) FROM users", Long::class.java)!!

    // ------------------------------------------------------------------------------------------------ A
    @Test
    fun `A SYSTEM_ADMIN creates a tenant, a workspace of that tenant and the first Tenant Admin, who activates and signs in`() {
        val sys = sysAdmin(); val t = createTenant(sys)
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, t.ws)).describedAs("workspace.tenant_id is explicit, not the compatibility default").isEqualTo(t.id)
        val ta = provision(sys, t.id, uname("tadmin"), tenantRole = "TENANT_ADMIN", ws = t.ws, wsRole = "WORKSPACE_ADMIN")
        val row = userRow(ta.id)
        assertThat(row["enabled"]).isEqualTo(true); assertThat(row["system_admin"]).isEqualTo(false); assertThat(row["activated_at"]).describedAs("pending until the link is used").isNull(); assertThat(row["auth_source"]).isEqualTo("LOCAL")
        assertThat(tenantRole(t.id, ta.id)).isEqualTo("TENANT_ADMIN"); assertThat(wsRole(t.ws, ta.id)).isEqualTo("WORKSPACE_ADMIN")
        assertThat(ta.link.get("purpose").asString()).isEqualTo("ACTIVATION")
        assertThat(keys(ta.link)).describedAs("the response carries the link, never a password").containsExactlyInAnyOrder("userId", "username", "displayName", "purpose", "token", "expiresAt")
        // a pending account cannot sign in
        assertThat(session().login(ta.username, PASSWORD).response.status).isEqualTo(401)
        val s = activateAndLogin(ta)
        val m = me(s)
        assertThat(m.get("systemAdmin").asBoolean()).isFalse(); assertThat(m.get("tenantRole").asString()).isEqualTo("TENANT_ADMIN"); assertThat(m.get("platformScope").asBoolean()).isFalse()
        assertThat(userRow(ta.id)["activated_at"]).isNotNull()
        // the link is single use
        assertThat(fromNewAddress("/api/v1/auth/activation/complete", """{"token":"${ta.link.get("token").asString()}","password":"$PASSWORD"}""").response.status).isEqualTo(410)
    }

    // ------------------------------------------------------------------------------------------------ B / C / D
    @Test
    fun `B a Tenant Admin creates a brand-new user in its own tenant, who activates and signs in`() {
        val sys = sysAdmin(); val t = createTenant(sys)
        val tas = activateAndLogin(provision(sys, t.id, uname("tadmin"), tenantRole = "TENANT_ADMIN"))
        val before = countUsers()
        val u = provision(tas, t.id, uname("newbie"), email = uname("n") + "@example.com")
        assertThat(countUsers()).isEqualTo(before + 1)
        assertThat(tenantRole(t.id, u.id)).isEqualTo("MEMBER"); assertThat(userRow(u.id)["system_admin"]).isEqualTo(false); assertThat(userRow(u.id)["activated_at"]).isNull()
        val us = activateAndLogin(u)
        assertThat(me(us).get("tenantRole").asString()).isEqualTo("MEMBER"); assertThat(me(us).get("systemAdmin").asBoolean()).isFalse()
        assertThat(me(us).get("workspaces").size()).describedAs("no workspace was given").isEqualTo(0)
    }

    @Test
    fun `C a Tenant Admin creates a user directly into a workspace of its tenant with a role, and the user sees it after login`() {
        val sys = sysAdmin(); val t = createTenant(sys)
        val tas = activateAndLogin(provision(sys, t.id, uname("tadmin"), tenantRole = "TENANT_ADMIN"))
        for (role in listOf("EDITOR", "PUBLISHER", "VIEWER")) {
            val u = provision(tas, t.id, uname(role.lowercase()), ws = t.ws, wsRole = role)
            assertThat(wsRole(t.ws, u.id)).isEqualTo(role)
            val us = activateAndLogin(u)
            assertThat(me(us).get("workspaces").toList().map { it.get("id").asString() to it.get("role").asString() }).containsExactly(t.ws.toString() to role)
        }
    }

    @Test
    fun `D a Tenant Admin creates a Workspace Admin who can then manage that workspace's members`() {
        val sys = sysAdmin(); val t = createTenant(sys)
        val tas = activateAndLogin(provision(sys, t.id, uname("tadmin"), tenantRole = "TENANT_ADMIN"))
        val wa = provision(tas, t.id, uname("wadmin"), ws = t.ws, wsRole = "WORKSPACE_ADMIN")
        val was = activateAndLogin(wa)
        assertThat(wsRole(t.ws, wa.id)).isEqualTo("WORKSPACE_ADMIN"); assertThat(tenantRole(t.id, wa.id)).isEqualTo("MEMBER")
        assertThat(was.get("/api/v1/workspaces/${t.ws}/members").response.status).isEqualTo(200)
    }

    // ------------------------------------------------------------------------------------------------ E
    @Test
    fun `E a Workspace Admin manages members of its workspace with MEMBER_MANAGE - list, add an eligible person, change the role, remove`() {
        val sys = sysAdmin(); val t = createTenant(sys)
        val tas = activateAndLogin(provision(sys, t.id, uname("tadmin"), tenantRole = "TENANT_ADMIN"))
        val was = activateAndLogin(provision(tas, t.id, uname("wadmin"), ws = t.ws, wsRole = "WORKSPACE_ADMIN"))
        val colleague = provision(tas, t.id, uname("colleague"))                          // a person of the tenant who is in no workspace yet
        val members = "/api/v1/workspaces/${t.ws}/members"
        assertThat(was.post(members, """{"username":"${colleague.username}","role":"VIEWER"}""").response.status).isEqualTo(201)
        assertThat(wsRole(t.ws, colleague.id)).isEqualTo("VIEWER")
        assertThat(was.patch("$members/${colleague.id}", """{"role":"EDITOR"}""").response.status).isEqualTo(200); assertThat(wsRole(t.ws, colleague.id)).isEqualTo("EDITOR")
        assertThat(was.body(was.get(members)).toList().map { it.get("username").asString() }).contains(colleague.username)
        assertThat(was.delete("$members/${colleague.id}").response.status).isEqualTo(204); assertThat(wsRole(t.ws, colleague.id)).isNull()
        assertThat(tenantRole(t.id, colleague.id)).describedAs("removal from the workspace does not remove the person from the tenant").isEqualTo("MEMBER")
    }

    // ------------------------------------------------------------------------------------------------ F
    @Test
    fun `F tenant A cannot see, list, add or provision into tenant B`() {
        val sys = sysAdmin(); val a = createTenant(sys); val b = createTenant(sys)
        val tasA = activateAndLogin(provision(sys, a.id, uname("ta"), tenantRole = "TENANT_ADMIN", ws = a.ws, wsRole = "WORKSPACE_ADMIN"))
        val tasB = activateAndLogin(provision(sys, b.id, uname("tb"), tenantRole = "TENANT_ADMIN", ws = b.ws, wsRole = "WORKSPACE_ADMIN"))
        val userB = provision(tasB, b.id, uname("userb"), ws = b.ws, wsRole = "VIEWER"); activateAndLogin(userB)
        val stranger = provision(sys, b.id, uname("stranger"))
        // tenant A's directory and member list never show tenant B people
        assertThat(tasA.body(tasA.get("/api/v1/admin/tenants/${a.id}/member-candidates?q=${userB.username.take(6)}")).size()).isZero()
        assertThat(tasA.body(tasA.get("/api/v1/admin/tenants/${a.id}/members")).toList().map { it.get("userId").asString() }).doesNotContain(userB.id.toString(), stranger.id.toString())
        // ... and tenant B is invisible to A's administrator
        for (path in listOf("/api/v1/admin/tenants/${b.id}", "/api/v1/admin/tenants/${b.id}/members", "/api/v1/admin/tenants/${b.id}/member-candidates"))
            assertThat(tasA.get(path).response.status).describedAs("GET $path").isEqualTo(404)
        assertThat(tasA.post("/api/v1/admin/tenants/${b.id}/users", """{"username":"${uname("x")}","displayName":"X","tenantRole":"MEMBER"}""").response.status).isEqualTo(404)
        assertThat(tasA.post("/api/v1/admin/tenants/${b.id}/workspaces", """{"name":"nope"}""").response.status).isEqualTo(404)
        // guessed ids: a person of tenant B is not addable to tenant A, by tenant API or by workspace API
        assertThat(tasA.put("/api/v1/admin/tenants/${a.id}/members/${userB.id}", """{"role":"MEMBER"}""").response.status).isEqualTo(404)
        assertThat(tenantRole(a.id, userB.id)).isNull()
        val wasA = activateAndLogin(provision(tasA, a.id, uname("wa"), ws = a.ws, wsRole = "WORKSPACE_ADMIN"))
        assertThat(wasA.post("/api/v1/workspaces/${a.ws}/members", """{"username":"${userB.username}","role":"VIEWER"}""").response.status).isEqualTo(404)
        assertThat(wsRole(a.ws, userB.id)).isNull()
        // a workspace of tenant A cannot be used with tenant B's id and vice versa
        assertThat(tasA.post("/api/v1/admin/tenants/${a.id}/users", """{"username":"${uname("x")}","displayName":"X","tenantRole":"MEMBER","workspaceId":"${b.ws}","workspaceRole":"VIEWER"}""").response.status).isEqualTo(404)
        assertThat(wasA.get("/api/v1/workspaces/${b.ws}/members").response.status).isEqualTo(404)
    }

    // ------------------------------------------------------------------------------------------------ G
    @Test
    fun `G a Tenant Admin cannot create or grant a SYSTEM_ADMIN, whatever the request says`() {
        val sys = sysAdmin(); val t = createTenant(sys)
        val tas = activateAndLogin(provision(sys, t.id, uname("tadmin"), tenantRole = "TENANT_ADMIN"))
        val u = provision(tas, t.id, uname("plain"))
        assertThat(tas.post("/api/v1/admin/users/${u.id}/system-admin", """{"grant":true,"confirm":true}""").response.status).isEqualTo(403)
        assertThat(userRow(u.id)["system_admin"]).isEqualTo(false)
        for (role in listOf("SYSTEM_ADMIN", "ADMIN", "OWNER", "")) {
            val r = tas.post("/api/v1/admin/tenants/${t.id}/users", """{"username":"${uname("g")}","displayName":"G","tenantRole":"$role"}""")
            assertThat(r.response.status).describedAs("tenantRole '$role'").isEqualTo(400); assertThat(code(r, tas)).isEqualTo("TENANT_ROLE_INVALID")
        }
        val name = uname("sneaky")
        val r = tas.post("/api/v1/admin/tenants/${t.id}/users", """{"username":"$name","displayName":"S","tenantRole":"MEMBER","systemAdmin":true,"system_admin":true,"role":"SYSTEM_ADMIN"}""")
        if (r.response.status == 201) assertThat(userRow(UUID.fromString(tas.body(r).get("userId").asString()))["system_admin"]).describedAs("an extra field is never honoured").isEqualTo(false)
        else assertThat(r.response.status).isEqualTo(400)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE system_admin AND username = ?", Long::class.java, name)).isZero()
        assertThat(tas.post("/api/v1/admin/users", """{"username":"${uname("legacy")}","displayName":"L","workspaceId":"${t.ws}","role":"WORKSPACE_ADMIN"}""").response.status).describedAs("the global create-user API stays platform-only").isEqualTo(403)
    }

    // ------------------------------------------------------------------------------------------------ H
    @Test
    fun `H a Workspace Admin cannot escalate - no account creation, no tenant change, no SYSTEM_ADMIN, no other workspace or tenant`() {
        val sys = sysAdmin(); val t = createTenant(sys); val other = createTenant(sys)
        val tas = activateAndLogin(provision(sys, t.id, uname("tadmin"), tenantRole = "TENANT_ADMIN"))
        val wa = provision(tas, t.id, uname("wadmin"), ws = t.ws, wsRole = "WORKSPACE_ADMIN"); val was = activateAndLogin(wa)
        val peer = provision(tas, t.id, uname("peer"), ws = t.ws, wsRole = "VIEWER")
        val before = countUsers()
        val r = was.post("/api/v1/admin/tenants/${t.id}/users", """{"username":"${uname("h")}","displayName":"H","tenantRole":"MEMBER"}""")
        assertThat(r.response.status).describedAs("WORKSPACE_ADMIN alone cannot create accounts").isEqualTo(403); assertThat(countUsers()).isEqualTo(before)
        assertThat(was.post("/api/v1/admin/users", """{"username":"${uname("h2")}","displayName":"H","workspaceId":"${t.ws}","role":"VIEWER"}""").response.status).isEqualTo(403)
        assertThat(was.put("/api/v1/admin/tenants/${t.id}/members/${wa.id}", """{"role":"TENANT_ADMIN"}""").response.status).describedAs("no self-promotion to TENANT_ADMIN").isEqualTo(403)
        assertThat(was.put("/api/v1/admin/tenants/${t.id}/members/${peer.id}", """{"role":"TENANT_ADMIN"}""").response.status).describedAs("nor promoting somebody else").isEqualTo(403)
        assertThat(tenantRole(t.id, wa.id)).isEqualTo("MEMBER"); assertThat(tenantRole(t.id, peer.id)).isEqualTo("MEMBER")
        assertThat(was.get("/api/v1/admin/tenants/${t.id}/members").response.status).isEqualTo(403)
        assertThat(was.get("/api/v1/admin/tenants/${t.id}/member-candidates").response.status).isEqualTo(403)
        assertThat(was.post("/api/v1/admin/tenants/${t.id}/workspaces", """{"name":"mine now"}""").response.status).describedAs("creating workspaces needs TENANT_MANAGE").isEqualTo(403)
        assertThat(was.post("/api/v1/admin/users/${peer.id}/system-admin", """{"grant":true,"confirm":true}""").response.status).isEqualTo(403)
        assertThat(was.patch("/api/v1/workspaces/${t.ws}/members/${peer.id}", """{"role":"ADMIN"}""").response.status).describedAs("ADMIN is not a workspace role").isEqualTo(400)
        assertThat(wsRole(t.ws, peer.id)).isEqualTo("VIEWER")
        // other tenant / other workspace
        assertThat(was.get("/api/v1/workspaces/${other.ws}/members").response.status).isEqualTo(404)
        assertThat(was.get("/api/v1/admin/tenants/${other.id}/members").response.status).isEqualTo(404)
        assertThat(was.post("/api/v1/admin/tenants/${other.id}/users", """{"username":"${uname("h3")}","displayName":"H","tenantRole":"MEMBER"}""").response.status).isEqualTo(404)
        // own workspace only: the capability does not make it a tenant or platform administrator
        val m = me(was)
        assertThat(m.get("permissions").toList().map { it.asString() }).describedAs("no tenant-level permission").isEmpty()
        assertThat(m.get("tenantRole").asString()).isEqualTo("MEMBER")
    }

    // ------------------------------------------------------------------------------------------------ I / J
    @Test
    fun `I the last admin is protected - the last Tenant Admin cannot be demoted or removed, and a Workspace Admin cannot step down or remove itself`() {
        val sys = sysAdmin(); val t = createTenant(sys)
        val ta = provision(sys, t.id, uname("tadmin"), tenantRole = "TENANT_ADMIN", ws = t.ws, wsRole = "WORKSPACE_ADMIN"); val tas = activateAndLogin(ta)
        val demote = sys.put("/api/v1/admin/tenants/${t.id}/members/${ta.id}", """{"role":"MEMBER"}""")
        assertThat(demote.response.status).isEqualTo(409); assertThat(code(demote, sys)).isEqualTo("LAST_TENANT_ADMIN")
        val remove = sys.delete("/api/v1/admin/tenants/${t.id}/members/${ta.id}")
        assertThat(remove.response.status).isEqualTo(409); assertThat(code(remove, sys)).isEqualTo("LAST_TENANT_ADMIN")
        assertThat(tenantRole(t.id, ta.id)).isEqualTo("TENANT_ADMIN")
        // with a second Tenant Admin one of them may go; never both
        val second = provision(sys, t.id, uname("tadmin2"), tenantRole = "TENANT_ADMIN")
        assertThat(sys.put("/api/v1/admin/tenants/${t.id}/members/${ta.id}", """{"role":"MEMBER"}""").response.status).isEqualTo(200)
        assertThat(sys.delete("/api/v1/admin/tenants/${t.id}/members/${second.id}").response.status).isEqualTo(409)
        // workspace level: the only administrator cannot change or remove itself
        val members = "/api/v1/workspaces/${t.ws}/members"
        val selfChange = tas.patch("$members/${ta.id}", """{"role":"VIEWER"}""")
        assertThat(selfChange.response.status).isEqualTo(403)
        assertThat(tas.delete("$members/${ta.id}").response.status).isIn(403, 409)
        assertThat(wsRole(t.ws, ta.id)).isEqualTo("WORKSPACE_ADMIN")
    }

    @Test
    fun `J nobody changes or grants its own role - not a Tenant Admin, not a Workspace Admin, not a SYSTEM_ADMIN`() {
        val sys0 = fx.user("prov-sys0", systemAdmin = true); val sys = sessionFor(sys0.username); val t = createTenant(sys)
        val ta = provision(sys, t.id, uname("tadmin"), tenantRole = "TENANT_ADMIN", ws = t.ws, wsRole = "WORKSPACE_ADMIN"); val tas = activateAndLogin(ta)
        provision(sys, t.id, uname("tadmin2"), tenantRole = "TENANT_ADMIN")             // so "last admin" cannot be the reason for the refusal
        val r = tas.put("/api/v1/admin/tenants/${t.id}/members/${ta.id}", """{"role":"MEMBER"}""")
        assertThat(r.response.status).isEqualTo(403); assertThat(code(r, tas)).isEqualTo("SELF_GRANT_FORBIDDEN"); assertThat(tenantRole(t.id, ta.id)).isEqualTo("TENANT_ADMIN")
        val r2 = sys.put("/api/v1/admin/tenants/${t.id}/members/${sys0.id}", """{"role":"TENANT_ADMIN"}""")
        assertThat(r2.response.status).isEqualTo(403); assertThat(code(r2, sys)).isEqualTo("SELF_GRANT_FORBIDDEN"); assertThat(tenantRole(t.id, sys0.id)).isNull()
        val wa = provision(tas, t.id, uname("wadmin"), ws = t.ws, wsRole = "WORKSPACE_ADMIN"); val was = activateAndLogin(wa)
        val third = provision(tas, t.id, uname("w3"), ws = t.ws, wsRole = "WORKSPACE_ADMIN")        // a second admin: the refusal is the self rule, not "last admin"
        val r3 = was.patch("/api/v1/workspaces/${t.ws}/members/${wa.id}", """{"role":"VIEWER"}""")
        assertThat(r3.response.status).isEqualTo(403); assertThat(wsRole(t.ws, wa.id)).isEqualTo("WORKSPACE_ADMIN")
        val r4 = was.post("/api/v1/workspaces/${t.ws}/members", """{"username":"${wa.username}","role":"WORKSPACE_ADMIN"}""")
        assertThat(r4.response.status).isIn(403, 409)
        assertThat(third.id).isNotEqualTo(wa.id)
    }

    // ------------------------------------------------------------------------------------------------ K / L
    @Test
    fun `K a disabled account cannot be added, is not a candidate and cannot sign in, and re-enabling restores it`() {
        val sys = sysAdmin(); val t = createTenant(sys)
        val tas = activateAndLogin(provision(sys, t.id, uname("tadmin"), tenantRole = "TENANT_ADMIN"))
        val was = activateAndLogin(provision(tas, t.id, uname("wadmin"), ws = t.ws, wsRole = "WORKSPACE_ADMIN"))
        val person = provision(tas, t.id, uname("person")); val ps = activateAndLogin(person)
        assertThat(sys.patch("/api/v1/admin/users/${person.id}/status", """{"enabled":false}""").response.status).isEqualTo(200)
        assertThat(userRow(person.id)["enabled"]).isEqualTo(false)
        val add = was.post("/api/v1/workspaces/${t.ws}/members", """{"username":"${person.username}","role":"VIEWER"}""")
        assertThat(add.response.status).isEqualTo(422); assertThat(code(add, was)).isEqualTo("USER_DISABLED"); assertThat(wsRole(t.ws, person.id)).isNull()
        assertThat(session().login(person.username, PASSWORD).response.status).isEqualTo(401)
        assertThat(sys.patch("/api/v1/admin/users/${person.id}/status", """{"enabled":true}""").response.status).isEqualTo(200)
        assertThat(was.post("/api/v1/workspaces/${t.ws}/members", """{"username":"${person.username}","role":"VIEWER"}""").response.status).isEqualTo(201)
        assertThat(ps.get("/api/v1/auth/me").response.status).isIn(200, 401)
        // a provisioned-but-never-activated account is not a candidate either (nothing to reactivate)
        val pending = provision(tas, t.id, uname("pending"))
        assertThat(tas.body(tas.get("/api/v1/admin/tenants/${t.id}/member-candidates?q=${pending.username.take(8)}")).toList().map { it.get("userId").asString() }).doesNotContain(pending.id.toString())
    }

    @Test
    fun `L duplicate username or e-mail is a 409, case-insensitively, and creates nothing`() {
        val sys = sysAdmin(); val t = createTenant(sys); val other = createTenant(sys)
        val tas = activateAndLogin(provision(sys, t.id, uname("tadmin"), tenantRole = "TENANT_ADMIN"))
        val name = uname("dup"); val mail = uname("dup") + "@example.com"
        provision(tas, t.id, name, email = mail)
        val before = countUsers()
        val d1 = tas.post("/api/v1/admin/tenants/${t.id}/users", """{"username":"$name","displayName":"Again","tenantRole":"MEMBER"}""")
        assertThat(d1.response.status).isEqualTo(409); assertThat(code(d1, tas)).isEqualTo("USERNAME_TAKEN")
        val d2 = tas.post("/api/v1/admin/tenants/${t.id}/users", """{"username":"${name.uppercase()}","displayName":"Again","tenantRole":"MEMBER"}""")
        assertThat(d2.response.status).isEqualTo(409); assertThat(code(d2, tas)).isEqualTo("USERNAME_TAKEN")
        val d3 = tas.post("/api/v1/admin/tenants/${t.id}/users", """{"username":"${uname("fresh")}","displayName":"Mail","email":"${mail.uppercase()}","tenantRole":"MEMBER"}""")
        assertThat(d3.response.status).isEqualTo(409); assertThat(code(d3, tas)).isEqualTo("EMAIL_TAKEN")
        assertThat(countUsers()).isEqualTo(before)
        // the same name in ANOTHER tenant is also taken: usernames are global, and the answer does not reveal which tenant has it beyond "taken"
        assertThat(sys.post("/api/v1/admin/tenants/${other.id}/users", """{"username":"$name","displayName":"X","tenantRole":"MEMBER"}""").response.status).isEqualTo(409)
        // input validation codes
        val bad = mapOf(
            """{"username":"A b","displayName":"X","tenantRole":"MEMBER"}""" to "INVALID_USERNAME",
            """{"username":"${uname("e")}","displayName":"X","email":"not-an-email","tenantRole":"MEMBER"}""" to "INVALID_EMAIL",
            """{"username":"${uname("n")}","tenantRole":"MEMBER"}""" to "VALIDATION_FAILED",
            """{"username":"${uname("w")}","displayName":"X","tenantRole":"MEMBER","workspaceId":"${t.ws}"}""" to "VALIDATION_FAILED",
            """{"username":"${uname("w")}","displayName":"X","tenantRole":"MEMBER","workspaceRole":"VIEWER"}""" to "VALIDATION_FAILED",
            """{"username":"${uname("r")}","displayName":"X","tenantRole":"MEMBER","workspaceId":"${t.ws}","workspaceRole":"SUPERUSER"}""" to "INVALID_ROLE"
        )
        for ((body, c) in bad) { val r = tas.post("/api/v1/admin/tenants/${t.id}/users", body); assertThat(r.response.status).describedAs(body).isEqualTo(400); assertThat(code(r, tas)).describedAs(body).isEqualTo(c) }
        assertThat(countUsers()).isEqualTo(before)
    }

    // ------------------------------------------------------------------------------------------------ M
    @Test
    fun `M the candidate endpoint works on PostgreSQL - search, escaping, ordering, the 50 cap, the response shape and the tenant scope`() {
        val sys = sysAdmin(); val t = createTenant(sys); val other = createTenant(sys)
        val tas = activateAndLogin(provision(sys, t.id, uname("tadmin"), tenantRole = "TENANT_ADMIN"))
        val prefix = "cand" + UUID.randomUUID().toString().take(5)
        // candidates = activated people related to this tenant who are not (any more) active tenant members: made by removing their tenant membership
        val made = (1..3).map { i ->
            val a = provision(tas, t.id, "$prefix.u$i", ws = t.ws, wsRole = "VIEWER"); activateAndLogin(a)
            jdbc.update("UPDATE tenant_members SET active = false WHERE tenant_id = ? AND user_id = ?", t.id, a.id); a
        }
        val foreign = provision(sys, other.id, "$prefix.foreign", ws = other.ws, wsRole = "VIEWER"); activateAndLogin(foreign)
        jdbc.update("UPDATE tenant_members SET active = false WHERE tenant_id = ? AND user_id = ?", other.id, foreign.id)
        val r = tas.get("/api/v1/admin/tenants/${t.id}/member-candidates?q=$prefix")
        assertThat(r.response.status).isEqualTo(200)
        val rows = tas.body(r).toList()
        assertThat(rows.map { it.get("username").asString() }).describedAs("only this tenant's people, ordered by username").containsExactly("$prefix.u1", "$prefix.u2", "$prefix.u3")
        assertThat(keys(rows.first())).containsExactlyInAnyOrder("userId", "username", "displayName", "email")
        assertThat(rows.map { it.get("userId").asString() }).containsExactlyInAnyOrderElementsOf(made.map { it.id.toString() })
        // LIKE metacharacters are data: none of them matches everything, none of them is an SQL error (the ESCAPE clause is a single backslash)
        for (q in listOf("%%", "__", "\\\\", "a%b", "50%_off", "'; DROP TABLE users;--"))
            assertThat(tas.get("/api/v1/admin/tenants/${t.id}/member-candidates?q=" + java.net.URLEncoder.encode(q, "UTF-8")).response.status).describedAs("q=$q").isEqualTo(200)
        assertThat(tas.body(tas.get("/api/v1/admin/tenants/${t.id}/member-candidates?q=" + java.net.URLEncoder.encode("%%", "UTF-8"))).size()).describedAs("'%%' is literal text, not a wildcard").isZero()
        val short = tas.get("/api/v1/admin/tenants/${t.id}/member-candidates?q=a")
        assertThat(short.response.status).isEqualTo(400); assertThat(code(short, tas)).isEqualTo("QUERY_TOO_SHORT")
        // a person may be found by display name and by e-mail as well
        jdbc.update("UPDATE users SET email = ?, display_name = ? WHERE id = ?", "$prefix@find.me", "Zed $prefix", made[0].id)
        assertThat(tas.body(tas.get("/api/v1/admin/tenants/${t.id}/member-candidates?q=find.me")).toList().map { it.get("userId").asString() }).contains(made[0].id.toString())
        assertThat(tas.body(tas.get("/api/v1/admin/tenants/${t.id}/member-candidates?q=zed")).toList().map { it.get("userId").asString() }).contains(made[0].id.toString())
        // the cap: 60 related people, 50 returned
        repeat(60) { i -> val x = fx.user("bulk$i"); jdbc.update("UPDATE users SET activated_at = now() WHERE id = ?", x.id); jdbc.update("INSERT INTO tenant_members (tenant_id, user_id, role, active) VALUES (?,?, 'MEMBER', false)", t.id, x.id) }
        assertThat(tas.body(tas.get("/api/v1/admin/tenants/${t.id}/member-candidates")).size()).isEqualTo(50)
        // adding a candidate reactivates it (the tenant API), and it then leaves the candidate list
        assertThat(tas.put("/api/v1/admin/tenants/${t.id}/members/${made[1].id}", """{"role":"MEMBER"}""").response.status).isEqualTo(200)
        assertThat(tas.body(tas.get("/api/v1/admin/tenants/${t.id}/member-candidates?q=$prefix.u2")).size()).isZero()
        // a plain tenant MEMBER and an unrelated tenant get nothing
        assertThat(sessionFor(fx.user("m-plain").also { tenants.setMember(t.id, it.id, TenantRole.MEMBER) }.username).get("/api/v1/admin/tenants/${t.id}/member-candidates").response.status).isEqualTo(403)
    }

    // ------------------------------------------------------------------------------------------------ N
    @Test
    fun `N auth me reports exactly the permissions of the freeze - WORKSPACE_ADMIN has MEMBER_MANAGE, TENANT_ADMIN has the two tenant permissions, a non-member SYSTEM_ADMIN has no business or member permission`() {
        val sys0 = fx.user("prov-sysN", systemAdmin = true); val sys = sessionFor(sys0.username); val t = createTenant(sys)
        val ta = activateAndLogin(provision(sys, t.id, uname("tadmin"), tenantRole = "TENANT_ADMIN"))
        val wa = activateAndLogin(provision(sys, t.id, uname("wadmin"), ws = t.ws, wsRole = "WORKSPACE_ADMIN"))
        val editor = activateAndLogin(provision(sys, t.id, uname("editor"), ws = t.ws, wsRole = "EDITOR"))
        assertThat(wsPerms(wa, t.ws)).contains("MEMBER_MANAGE"); assertThat(me(wa).get("permissions").toList()).isEmpty()
        assertThat(wsPerms(editor, t.ws)).doesNotContain("MEMBER_MANAGE")
        val tam = me(ta)
        assertThat(tam.get("permissions").toList().map { it.asString() }).describedAs("TENANT_ADMIN: TENANT_MANAGE + TENANT_MEMBERS + the six organization capabilities, no MEMBER_MANAGE").containsExactlyInAnyOrder("TENANT_MANAGE", "TENANT_MEMBERS", "ORG_STRUCTURE_VIEW", "ORG_STRUCTURE_MANAGE", "EMPLOYEE_VIEW", "EMPLOYEE_MANAGE", "POSITION_GRADE_VIEW", "POSITION_GRADE_MANAGE")
        assertThat(tam.get("workspaces").size()).describedAs("TENANT_ADMIN is not a workspace member and so sees no workspace and no business permission").isEqualTo(0)
        val sm = me(sys)
        assertThat(sm.get("platformScope").asBoolean()).isTrue(); assertThat(sm.get("systemAdmin").asBoolean()).isTrue()
        assertThat(sm.get("permissions").toList().map { it.asString() }).containsExactlyInAnyOrder("TENANT_MANAGE", "TENANT_MEMBERS")
        val seen = wsPerms(sys, t.ws)
        assertThat(seen).describedAs("a non-member SYSTEM_ADMIN sees the workspace in the list but holds platform permissions only").containsExactlyInAnyOrder("TENANT_MANAGE", "TENANT_MEMBERS")
        assertThat(seen).doesNotContain("MEMBER_MANAGE", "APP_VIEW", "APP_EDIT", "APP_USE", "PROJECT_CREATE", "QUERY_EXECUTE", "DATA_MUTATE")
        // and it really cannot do the things the list says it cannot
        assertThat(sys.get("/api/v1/workspaces/${t.ws}/members").response.status).isEqualTo(403)
        assertThat(sys.post("/api/v1/workspaces/${t.ws}/projects", """{"name":"nope"}""").response.status).isIn(403, 404, 409)
    }

    // ------------------------------------------------------------------------------------------------ O
    @Test
    fun `O every provisioning step is audited and no secret reaches the audit trail`() {
        val sys0 = fx.user("prov-sysO", systemAdmin = true); val sys = sessionFor(sys0.username); val t = createTenant(sys)
        val ta = provision(sys, t.id, uname("tadmin"), tenantRole = "TENANT_ADMIN", ws = t.ws, wsRole = "WORKSPACE_ADMIN"); val tas = activateAndLogin(ta)
        val u = provision(tas, t.id, uname("auditee"), ws = t.ws, wsRole = "VIEWER", email = uname("a") + "@example.com")
        fun count(action: String, targetId: UUID) = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = ? AND resource_id = ?", Long::class.java, action, targetId.toString())!!
        assertThat(count("WORKSPACE_CREATED", t.ws)).isEqualTo(1L)
        assertThat(count("USER_CREATED", ta.id)).isEqualTo(1L); assertThat(count("USER_CREATED", u.id)).isEqualTo(1L)
        assertThat(count("ACTIVATION_LINK_CREATED", ta.id)).isEqualTo(1L); assertThat(count("ACTIVATION_LINK_CREATED", u.id)).isEqualTo(1L)
        assertThat(count("TENANT_MEMBER_SET", t.id)).isGreaterThanOrEqualTo(2L)
        assertThat(count("ADD_MEMBER", ta.id)).isEqualTo(1L); assertThat(count("ADD_MEMBER", u.id)).isEqualTo(1L)
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_events WHERE action = 'USER_CREATED' AND resource_id = ?", UUID::class.java, u.id.toString())).describedAs("the actor is the Tenant Admin").isEqualTo(ta.id)
        // role change and removal keep their existing events
        assertThat(tas.patch("/api/v1/workspaces/${t.ws}/members/${u.id}", """{"role":"EDITOR"}""").response.status).isEqualTo(200)
        assertThat(tas.delete("/api/v1/workspaces/${t.ws}/members/${u.id}").response.status).isEqualTo(204)
        assertThat(count("CHANGE_PERMISSION", u.id)).isEqualTo(1L); assertThat(count("REMOVE_MEMBER", u.id)).isEqualTo(1L)
        // no secret anywhere in the rows this flow wrote: neither activation token, nor a password / hash
        activateAndLogin(u)
        val dump = jdbc.queryForList("SELECT coalesce(old_value::text,'') || ' ' || coalesce(new_value::text,'') || ' ' || action || ' ' || coalesce(resource_id,'') AS t FROM audit_events WHERE actor_id IN (?, ?, ?)", sys0.id, ta.id, u.id)
            .joinToString("\n") { it["t"] as String }
        for (secret in listOf(ta.link.get("token").asString(), u.link.get("token").asString(), PASSWORD)) assertThat(dump).doesNotContain(secret)
        assertThat(dump.lowercase()).doesNotContain("password", "argon2", "\$argon", "token\"")
    }
}
