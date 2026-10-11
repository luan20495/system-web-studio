package com.systemwebstudio.tenancy

import com.systemwebstudio.organization.InMemoryOrganizationConfig
import com.systemwebstudio.support.ApiSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MvcResult
import java.util.UUID

/**
 * C1 hardening (6623b92) - A: a SUSPENDED company is frozen for its own Tenant Admin on the account / member / workspace routes of TenantController
 * (POST workspaces, POST users, PUT / DELETE members) with 403 TENANT_SUSPENDED, a DELETED one answers 404 TENANT_NOT_FOUND; the platform operator keeps its reach
 * on a suspended company; reactivation restores the Tenant Admin. D: the project LIST of a workspace is decided by PROJECT_READ at workspace level (seesAllProjects).
 */
@Import(InMemoryOrganizationConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HardeningLifecycleTests : FinalIamTestBase() {
    private fun status(r: MvcResult, s: ApiSession, expected: Int, code: String?, what: String) {
        assertThat(r.response.status).describedAs(what).isEqualTo(expected)
        if (code != null) assertThat(errorCode(s, r)).describedAs(what).isEqualTo(code)
    }
    private fun workspaceCount(c: Company) = jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE tenant_id = ?", Long::class.java, c.id)!!
    private fun membership(c: Company, u: UUID): Map<String, Any?>? = jdbc.queryForList("SELECT role, active FROM tenant_members WHERE tenant_id = ? AND user_id = ?", c.id, u).firstOrNull()
    private fun userExists(username: String) = jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Long::class.java, username)!! > 0
    private fun newUserBody(username: String) = """{"username":"$username","displayName":"User $username","tenantRole":"MEMBER"}"""
    private fun plainMember(c: Company, prefix: String): UUID { val u = fx.user(prefix); tenantService.setMember(c.id, u.id, TenantRole.MEMBER); return u.id }

    // ------------------------------------------------------------------------------------------------ A. suspended / deleted freeze
    @Test
    fun `A1 SUSPENDED company - Tenant Admin gets 403 TENANT_SUSPENDED on workspace, account and member writes, reads stay, the platform operator is not blocked, reactivation restores`() {
        val sys = sysAdmin(); val c = company(sys); wsIn(c)
        val m1 = plainMember(c, "hl-m1"); val m2 = plainMember(c, "hl-m2")
        assertThat(sys.patch("${base(c)}/status", """{"status":"SUSPENDED"}""").response.status).isEqualTo(200)
        val wsBefore = workspaceCount(c)

        // the Tenant Admin: every one of the four write routes is refused, nothing changes
        val frozenUser = uname("hl-frozen")
        status(c.admin.post("${base(c)}/workspaces", """{"name":"Frozen WS"}"""), c.admin, 403, "TENANT_SUSPENDED", "POST workspaces")
        status(c.admin.post("${base(c)}/users", newUserBody(frozenUser)), c.admin, 403, "TENANT_SUSPENDED", "POST users")
        status(c.admin.put("${base(c)}/members/$m1", """{"role":"TENANT_ADMIN"}"""), c.admin, 403, "TENANT_SUSPENDED", "PUT member")
        status(c.admin.delete("${base(c)}/members/$m1"), c.admin, 403, "TENANT_SUSPENDED", "DELETE member")
        status(c.admin.put("${base(c)}/members/${c.adminId}", """{"role":"MEMBER"}"""), c.admin, 403, "TENANT_SUSPENDED", "PUT on itself: the freeze is judged before self-grant")
        assertThat(workspaceCount(c)).isEqualTo(wsBefore)
        assertThat(userExists(frozenUser)).isFalse()
        assertThat(membership(c, m1)).isEqualTo(mapOf("role" to "MEMBER", "active" to true))
        assertThat(membership(c, c.adminId)).isEqualTo(mapOf("role" to "TENANT_ADMIN", "active" to true))
        // reads are not frozen
        assertThat(c.admin.get("${base(c)}/members").response.status).isEqualTo(200)
        assertThat(c.admin.get(base(c)).response.status).isEqualTo(200)

        // the platform operator repairs a suspended company: all four routes work
        val opUser = uname("hl-op")
        assertThat(sys.post("${base(c)}/workspaces", """{"name":"Operator WS"}""").response.status).describedAs("operator POST workspaces").isEqualTo(201)
        assertThat(workspaceCount(c)).isEqualTo(wsBefore + 1)
        assertThat(sys.post("${base(c)}/users", newUserBody(opUser)).response.status).describedAs("operator POST users").isEqualTo(201)
        assertThat(userExists(opUser)).isTrue()
        assertThat(sys.put("${base(c)}/members/$m2", """{"role":"TENANT_ADMIN"}""").response.status).describedAs("operator PUT member").isEqualTo(200)
        assertThat(membership(c, m2)).isEqualTo(mapOf("role" to "TENANT_ADMIN", "active" to true))
        assertThat(sys.delete("${base(c)}/members/$m2").response.status).describedAs("operator DELETE member").isEqualTo(204)
        assertThat(membership(c, m2)!!["active"]).isEqualTo(false)

        // reactivation: the Tenant Admin may write again
        assertThat(sys.patch("${base(c)}/status", """{"status":"ACTIVE"}""").response.status).isEqualTo(200)
        assertThat(c.admin.post("${base(c)}/workspaces", """{"name":"Back WS"}""").response.status).isEqualTo(201)
        assertThat(c.admin.post("${base(c)}/users", newUserBody(frozenUser)).response.status).isEqualTo(201)
        assertThat(userExists(frozenUser)).isTrue()
        assertThat(c.admin.put("${base(c)}/members/$m1", """{"role":"TENANT_ADMIN"}""").response.status).isEqualTo(200)
        assertThat(membership(c, m1)).isEqualTo(mapOf("role" to "TENANT_ADMIN", "active" to true))
        assertThat(c.admin.delete("${base(c)}/members/$m1").response.status).isEqualTo(204)
        assertThat(membership(c, m1)!!["active"]).isEqualTo(false)
    }

    @Test
    fun `A2 the freeze is per company - a suspended company does not freeze the Tenant Admin of another company`() {
        val sys = sysAdmin(); val a = company(sys); val b = company(sys)
        val mb = plainMember(b, "hl-mb")
        assertThat(sys.patch("${base(a)}/status", """{"status":"SUSPENDED"}""").response.status).isEqualTo(200)
        assertThat(b.admin.post("${base(b)}/workspaces", """{"name":"B WS"}""").response.status).isEqualTo(201)
        assertThat(b.admin.put("${base(b)}/members/$mb", """{"role":"TENANT_ADMIN"}""").response.status).isEqualTo(200)
        // and the Tenant Admin of B still cannot reach A at all (404 before any status is disclosed)
        status(b.admin.post("${base(a)}/workspaces", """{"name":"x"}"""), b.admin, 404, "TENANT_NOT_FOUND", "foreign company")
    }

    @Test
    fun `A3 DELETED company - the Tenant Admin gets 404 TENANT_NOT_FOUND on workspace, account and member writes, nothing changes`() {
        val sys = sysAdmin(); val c = company(sys); wsIn(c)
        val m1 = plainMember(c, "hl-dm")
        assertThat(sys.patch("${base(c)}/status", """{"status":"DELETED"}""").response.status).isEqualTo(200)
        val wsBefore = workspaceCount(c); val u = uname("hl-del")
        status(c.admin.post("${base(c)}/workspaces", """{"name":"x"}"""), c.admin, 404, "TENANT_NOT_FOUND", "POST workspaces")
        status(c.admin.post("${base(c)}/users", newUserBody(u)), c.admin, 404, "TENANT_NOT_FOUND", "POST users")
        status(c.admin.put("${base(c)}/members/$m1", """{"role":"TENANT_ADMIN"}"""), c.admin, 404, "TENANT_NOT_FOUND", "PUT member")
        status(c.admin.delete("${base(c)}/members/$m1"), c.admin, 404, "TENANT_NOT_FOUND", "DELETE member")
        assertThat(workspaceCount(c)).isEqualTo(wsBefore); assertThat(userExists(u)).isFalse()
        assertThat(membership(c, m1)).isEqualTo(mapOf("role" to "MEMBER", "active" to true))
        // the platform operator cannot create an account in a deleted company either (AccountService checks the status itself)
        status(sys.post("${base(c)}/users", newUserBody(u)), sys, 404, "TENANT_NOT_FOUND", "operator POST users")
        assertThat(userExists(u)).isFalse()
    }

    @Test
    fun `A4 DELETED company - the platform operator is refused too on workspace and member writes (gone for everybody), restoring the company brings them back`() {
        val sys = sysAdmin(); val c = company(sys); wsIn(c)
        val m1 = plainMember(c, "hl-dm2")
        assertThat(sys.patch("${base(c)}/status", """{"status":"DELETED"}""").response.status).isEqualTo(200)
        val wsBefore = workspaceCount(c)
        status(sys.post("${base(c)}/workspaces", """{"name":"x"}"""), sys, 404, "TENANT_NOT_FOUND", "operator POST workspaces")
        status(sys.put("${base(c)}/members/$m1", """{"role":"TENANT_ADMIN"}"""), sys, 404, "TENANT_NOT_FOUND", "operator PUT member")
        status(sys.delete("${base(c)}/members/$m1"), sys, 404, "TENANT_NOT_FOUND", "operator DELETE member")
        assertThat(workspaceCount(c)).isEqualTo(wsBefore); assertThat(membership(c, m1)).isEqualTo(mapOf("role" to "MEMBER", "active" to true))
        // the only way back is the platform status route
        assertThat(sys.patch("${base(c)}/status", """{"status":"ACTIVE"}""").response.status).isEqualTo(200)
        assertThat(sys.put("${base(c)}/members/$m1", """{"role":"TENANT_ADMIN"}""").response.status).isEqualTo(200)
    }

    // ------------------------------------------------------------------------------------------------ D. project list visibility (seesAllProjects)
    @Test
    fun `D project list - WORKSPACE_ADMIN sees every project, EDITOR PUBLISHER VIEWER only the projects they are members of`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val owner = fx.user("hl-own"); fx.member(ws, owner, "EDITOR")
        val p1 = fx.project(ws, owner, "P1"); val p2 = fx.project(ws, owner, "P2"); val p3 = fx.project(ws, owner, "P3")
        val wa = fx.user("hl-wa"); fx.member(ws, wa, "WORKSPACE_ADMIN")                                 // member of NO project
        val ed = fx.user("hl-ed"); fx.member(ws, ed, "EDITOR"); fx.projectRole(p1, ed, "EDITOR")
        val pub = fx.user("hl-pub"); fx.member(ws, pub, "PUBLISHER"); fx.projectRole(p2, pub, "PUBLISHER"); fx.projectRole(p3, pub, "VIEWER")
        val vw = fx.user("hl-vw"); fx.member(ws, vw, "VIEWER"); fx.projectRole(p3, vw, "VIEWER")
        val none = fx.user("hl-none"); fx.member(ws, none, "VIEWER")                                     // workspace member, no project

        fun listed(username: String): List<String> {
            val s = sessionFor(username); val r = s.get(api(ws))
            assertThat(r.response.status).describedAs("list of $username").isEqualTo(200)
            assertThat(r.response.getHeader("X-Total-Count")).isEqualTo(s.body(r).size().toString())
            return s.body(r).toList().map { it.get("id").asString() }
        }
        val all = listOf(p1, p2, p3).map { it.id.toString() }
        assertThat(listed(wa.username)).describedAs("WORKSPACE_ADMIN").containsExactlyInAnyOrderElementsOf(all)
        assertThat(listed(owner.username)).describedAs("EDITOR owner").containsExactlyInAnyOrderElementsOf(all)
        assertThat(listed(ed.username)).describedAs("EDITOR").containsExactly(p1.id.toString())
        assertThat(listed(pub.username)).describedAs("PUBLISHER").containsExactlyInAnyOrder(p2.id.toString(), p3.id.toString())
        assertThat(listed(vw.username)).describedAs("VIEWER").containsExactly(p3.id.toString())
        assertThat(listed(none.username)).describedAs("VIEWER without project").isEmpty()

        // a deactivated project membership no longer lists the project; a promotion to WORKSPACE_ADMIN lists everything at once
        jdbc.update("UPDATE project_members SET active = FALSE WHERE project_id = ? AND user_id = ?", p1.id, ed.id)
        assertThat(listed(ed.username)).isEmpty()
        jdbc.update("UPDATE workspace_members SET role = 'WORKSPACE_ADMIN' WHERE workspace_id = ? AND user_id = ?", ws, vw.id)
        assertThat(listed(vw.username)).containsExactlyInAnyOrderElementsOf(all)
    }
}
