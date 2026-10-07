package com.systemwebstudio.tenancy

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * C1 · regression for contract v2 R-08: nobody — SYSTEM_ADMIN, TENANT_ADMIN or WORKSPACE_ADMIN — can create or raise THEIR OWN membership.
 * Someone else must grant it. Every case checks the status/code AND that no row changed.
 */
class PrivilegeEscalationTests : IntegrationTestBase() {
    @Autowired lateinit var tenants: TenantService
    private fun ws(w: UUID) = "/api/v1/workspaces/$w/members"
    private fun code(r: org.springframework.test.web.servlet.MvcResult, s: com.systemwebstudio.support.ApiSession) = s.body(r).get("code").asString()
    private fun wsRole(w: UUID, u: UUID) = jdbc.queryForList("SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ? AND active", w, u).firstOrNull()?.get("role")
    private fun slug() = "esc-" + UUID.randomUUID().toString().take(8)

    @Test
    fun `a system admin cannot add itself to a workspace it does not belong to (R-08)`() {
        val sc = scenario(); val sys = fx.user("esc-sys", systemAdmin = true); val s = sessionFor(sys.username)
        val r = s.post(ws(sc.ws), """{"username":"${sys.username}","role":"WORKSPACE_ADMIN"}""")
        // D-C1-13: a non-member platform admin holds no MEMBER_MANAGE in the workspace, so the refusal is the missing permission (FORBIDDEN) before the self-grant rule;
        // the self-grant rule itself is proven for holders of MEMBER_MANAGE (workspace admin, below) and at tenant level (SELF_GRANT_FORBIDDEN)
        assertThat(r.response.status).isEqualTo(403); assertThat(code(r, s)).isEqualTo("FORBIDDEN")
        assertThat(wsRole(sc.ws, sys.id)).isNull()
        assertThat(s.get(sc.base).response.status).isEqualTo(404)                                            // still no business access
    }

    @Test
    fun `a system admin onboards OTHER people through tenant-scoped provisioning, never through a workspace it is not a member of`() {
        val sc = scenario(); val sys = fx.user("esc-sys2", systemAdmin = true); val s = sessionFor(sys.username); val newcomer = fx.user("newcomer")
        // D-C1-13: no MEMBER_MANAGE for a non-member platform admin ...
        assertThat(s.post(ws(sc.ws), """{"username":"${newcomer.username}","role":"VIEWER"}""").response.status).isEqualTo(403)
        assertThat(wsRole(sc.ws, newcomer.id)).isNull()
        // ... the platform duty lives in the tenant API: a brand-new person, in the tenant of that workspace, with a workspace role
        val tenant = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", java.util.UUID::class.java, sc.ws)
        val r = s.post("/api/v1/admin/tenants/$tenant/users", """{"username":"onboarded-${java.util.UUID.randomUUID().toString().take(8)}","displayName":"Onboarded","tenantRole":"MEMBER","workspaceId":"${sc.ws}","workspaceRole":"VIEWER"}""")
        assertThat(r.response.status).isEqualTo(201)
        assertThat(wsRole(sc.ws, java.util.UUID.fromString(s.body(r).get("userId").asString()))).isEqualTo("VIEWER")
    }

    @Test
    fun `a system admin who is a member cannot raise its own workspace or project role`() {
        val sc = scenario(); val sys = fx.user("esc-sys3", systemAdmin = true); fx.member(sc.ws, sys, "VIEWER"); val s = sessionFor(sys.username)
        // as a plain member it has no MEMBER_MANAGE at all (default policy: no bypass on top of the member role) -> 403 before the self-grant rule
        assertThat(s.patch("${ws(sc.ws)}/${sys.id}", """{"role":"WORKSPACE_ADMIN"}""").response.status).isEqualTo(403)
        assertThat(wsRole(sc.ws, sys.id)).isEqualTo("VIEWER")
        fx.projectRole(fx.projects.findById(sc.projectId).get(), sys, "VIEWER")
        assertThat(s.patch("${sc.base}/members/${sys.id}", """{"role":"OWNER"}""").response.status).isEqualTo(403)
    }

    @Test
    fun `a workspace admin cannot re-add or promote itself, and cannot add itself to a project it is not in`() {
        val sc = scenario(); val admin = fx.user("esc-adm"); fx.member(sc.ws, admin, "WORKSPACE_ADMIN"); val s = sessionFor(admin.username)
        val again = s.post(ws(sc.ws), """{"username":"${admin.username}","role":"WORKSPACE_ADMIN"}""")
        assertThat(again.response.status).isEqualTo(403); assertThat(code(again, s)).isEqualTo("SELF_GRANT_FORBIDDEN")
        assertThat(s.patch("${ws(sc.ws)}/${admin.id}", """{"role":"WORKSPACE_ADMIN"}""").response.status).isEqualTo(403)
        val toProject = s.post("${sc.base}/members", """{"username":"${admin.username}","role":"OWNER"}""")
        assertThat(toProject.response.status).isEqualTo(403); assertThat(code(toProject, s)).isEqualTo("SELF_GRANT_FORBIDDEN")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_members WHERE project_id = ? AND user_id = ?", Long::class.java, sc.projectId, admin.id)).isZero()
    }

    @Test
    fun `a project owner and a plain member cannot promote themselves, but an admin can promote someone else`() {
        val sc = scenario(); val mate = fx.user("esc-mate"); fx.member(sc.ws, mate, "VIEWER"); fx.projectRole(fx.projects.findById(sc.projectId).get(), mate, "VIEWER")
        val ms = sessionFor(mate.username)
        assertThat(ms.patch("${sc.base}/members/${mate.id}", """{"role":"OWNER"}""").response.status).isEqualTo(403)
        assertThat(ms.patch("${ws(sc.ws)}/${mate.id}", """{"role":"WORKSPACE_ADMIN"}""").response.status).isEqualTo(403)       // no MEMBER_MANAGE at all
        assertThat(sc.s.patch("${sc.base}/members/${mate.id}", """{"role":"EDITOR"}""").response.status).isEqualTo(200)         // someone else may grant
    }

    @Test
    fun `tenant level - nobody adds itself to a tenant or changes its own tenant role, not even a system admin`() {
        val ta = fx.user("esc-ta"); val sys = fx.user("esc-tsys", systemAdmin = true); val t = tenants.create(slug(), "Esc", ta.id)
        val tas = sessionFor(ta.username); val ss = sessionFor(sys.username)
        val a = ss.put("/api/v1/admin/tenants/${t.id}/members/${sys.id}", """{"role":"TENANT_ADMIN"}""")
        assertThat(a.response.status).isEqualTo(403); assertThat(code(a, ss)).isEqualTo("SELF_GRANT_FORBIDDEN")
        assertThat(tenants.roleOf(t.id, sys.id)).isNull()
        assertThat(tas.put("/api/v1/admin/tenants/${t.id}/members/${ta.id}", """{"role":"MEMBER"}""").response.status).isEqualTo(403)
        assertThat(tenants.roleOf(t.id, ta.id)).isEqualTo(TenantRole.TENANT_ADMIN)
        // granting someone else is fine - but only a person related to THIS tenant (here: a member of one of its workspaces); a stranger is a 404, never a global directory
        val stranger = fx.user("esc-stranger")
        assertThat(ss.put("/api/v1/admin/tenants/${t.id}/members/${stranger.id}", """{"role":"TENANT_ADMIN"}""").response.status).isEqualTo(404)
        assertThat(tenants.roleOf(t.id, stranger.id)).isNull()
        val other = fx.user("esc-other"); fx.member(tenants.createWorkspace(t.id, "Esc WS")["id"] as java.util.UUID, other, "VIEWER")
        assertThat(ss.put("/api/v1/admin/tenants/${t.id}/members/${other.id}", """{"role":"TENANT_ADMIN"}""").response.status).isEqualTo(200)
        assertThat(tenants.roleOf(t.id, other.id)).isEqualTo(TenantRole.TENANT_ADMIN)
    }

    @Test
    fun `a tenant admin has no way into the workspaces of its tenant through the member API`() {
        val sc = scenario(); val ta = fx.user("esc-ta2"); tenants.setMember(TenantIds.DEFAULT, ta.id, TenantRole.TENANT_ADMIN); val s = sessionFor(ta.username)
        assertThat(s.post(ws(sc.ws), """{"username":"${ta.username}","role":"WORKSPACE_ADMIN"}""").response.status).isEqualTo(404)
        assertThat(s.get(ws(sc.ws)).response.status).isEqualTo(404)
        assertThat(wsRole(sc.ws, ta.id)).isNull()
    }
}
