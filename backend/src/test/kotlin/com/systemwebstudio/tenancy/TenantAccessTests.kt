package com.systemwebstudio.tenancy

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.access.PermissionMatrix
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * C1 · T2 — AccessContext.tenantId, the SYSTEM_ADMIN platform-scope policy (D-C1-11) and the tenant admin API.
 * Default policy (app.tenancy.system-admin-business-access=false): see TenantAccessLegacyFlagTests for the flag-on behaviour.
 */
class TenantAccessTests : IntegrationTestBase() {
    @Autowired lateinit var access: AccessService
    @Autowired lateinit var tenants: TenantService
    private fun slug() = "acc-" + UUID.randomUUID().toString().take(8)
    private fun status(r: org.springframework.test.web.servlet.MvcResult) = r.response.status
    private fun codeOf(block: () -> Unit) = assertThatThrownBy { block() }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).status.value() to it.code }

    @Test
    fun `AccessContext carries the tenant resolved from the workspace`() {
        val sc = scenario()
        val ctx = access.forProject(sc.user.id, sc.ws, sc.projectId)
        assertThat(ctx.tenantId).isEqualTo(TenantIds.DEFAULT)
        assertThat(ctx.tenantContext.tenantId).isEqualTo(TenantIds.DEFAULT)
        assertThat(ctx.platformScope).isFalse()
        val t = tenants.create(slug(), "Moved")
        jdbc.update("UPDATE workspaces SET tenant_id = ? WHERE id = ?", t.id, sc.ws)
        assertThat(access.forWorkspace(sc.user.id, sc.ws).tenantId).isEqualTo(t.id)
    }

    @Test
    fun `a member removed from the tenant loses access to its workspaces, and a suspended tenant is closed to ordinary users`() {
        val sc = scenario(); val t = tenants.create(slug(), "Closed", fx.user("ta").id)
        jdbc.update("UPDATE workspaces SET tenant_id = ? WHERE id = ?", t.id, sc.ws)
        // the trigger created tenant membership in the DEFAULT tenant only; join the new one explicitly, then remove
        tenants.setMember(t.id, sc.user.id, TenantRole.MEMBER)
        assertThat(access.forWorkspace(sc.user.id, sc.ws).tenantContext.tenantRole).isEqualTo(TenantRole.MEMBER)
        tenants.setStatus(t.id, TenantStatus.SUSPENDED)
        codeOf { access.forWorkspace(sc.user.id, sc.ws) }.isEqualTo(403 to "TENANT_SUSPENDED")
        assertThat(status(sc.s.get(sc.base))).isEqualTo(403)
        tenants.setStatus(t.id, TenantStatus.ACTIVE)
        assertThat(status(sc.s.get(sc.base))).isEqualTo(200)
        tenants.removeMember(t.id, sc.user.id)
        codeOf { access.forWorkspace(sc.user.id, sc.ws) }.isEqualTo(404 to "WORKSPACE_NOT_FOUND")
        assertThat(status(sc.s.get(sc.base))).isEqualTo(404)
    }

    @Test
    fun `TENANT_ADMIN is a tenant-level role only - no implicit access to workspace business data`() {
        val sc = scenario(); val ta = fx.user("tenant-admin")
        tenants.setMember(TenantIds.DEFAULT, ta.id, TenantRole.TENANT_ADMIN)
        codeOf { access.forWorkspace(ta.id, sc.ws) }.isEqualTo(404 to "WORKSPACE_NOT_FOUND")
        assertThat(status(sessionFor(ta.username).get(sc.base))).isEqualTo(404)
        val t = access.forTenant(ta.id, TenantIds.DEFAULT)
        assertThat(t.permissions).containsExactlyInAnyOrder(Permission.TENANT_MANAGE, Permission.TENANT_MEMBERS)
        assertThat(t.platformScope).isFalse()
    }

    @Test
    fun `SYSTEM_ADMIN default policy - platform scope only, no business data of a workspace it is not a member of`() {
        val sc = scenario(); val sys = fx.user("sys", systemAdmin = true); val s = sessionFor(sys.username)
        val ctx = access.forWorkspace(sys.id, sc.ws)
        assertThat(ctx.platformScope).isTrue()
        assertThat(ctx.permissions).isEqualTo(PermissionMatrix.platformScope)
        assertThat(ctx.permissions).doesNotContain(Permission.PROJECT_READ, Permission.PROJECT_EDIT, Permission.PROJECT_PUBLISH, Permission.AUDIT_READ)
        assertThat(ctx.seesAllProjects).isFalse()
        assertThat(status(s.get(sc.base))).isEqualTo(404)                                   // project detail
        assertThat(status(s.get("${sc.base}/schema"))).isEqualTo(404)
        assertThat(status(s.get("${sc.base}/versions"))).isEqualTo(404)
        assertThat(s.body(s.get(api(sc.ws))).size()).isZero()                               // project list shows nothing it is not a member of
        assertThat(status(s.get("/api/v1/workspaces/${sc.ws}/audit-events"))).isEqualTo(403)       // workspace audit = business data
    }

    @Test
    fun `SYSTEM_ADMIN keeps the platform operations - member management and tenant administration`() {
        val sc = scenario(); val sys = fx.user("sys2", systemAdmin = true); val s = sessionFor(sys.username)
        assertThat(status(s.get("/api/v1/workspaces/${sc.ws}/members"))).isEqualTo(200)     // MEMBER_MANAGE retained (documented bypass #1)
        assertThat(status(s.get("/api/v1/admin/tenants"))).isEqualTo(200)
        assertThat(access.forTenant(sys.id, TenantIds.DEFAULT).platformScope).isTrue()
        assertThat(status(s.get("/api/v1/auth/me"))).isEqualTo(200)
        assertThat(s.body(s.get("/api/v1/auth/me")).get("workspaces").toList().any { it.get("id").asString() == sc.ws.toString() }).isTrue()  // /me lists all (documented bypass #2)
    }

    @Test
    fun `SYSTEM_ADMIN that is a member gets exactly its member permissions (no bypass on top)`() {
        val sc = scenario(); val sys = fx.user("sys3", systemAdmin = true); fx.member(sc.ws, sys, "VIEWER")
        val ctx = access.forWorkspace(sys.id, sc.ws)
        assertThat(ctx.platformScope).isFalse()
        assertThat(ctx.permissions).isEqualTo(PermissionMatrix.workspaceRoles.getValue("VIEWER"))
        assertThat(ctx.seesAllProjects).isFalse()
        assertThat(status(sessionFor(sys.username).get(sc.base))).isEqualTo(404)           // VIEWER of the workspace without project role
    }

    @Test
    fun `forPlatform needs an enabled system admin and forTenant hides foreign tenants`() {
        val u = fx.user("plain"); val ta = fx.user("tadm2"); val t = tenants.create(slug(), "Mine", ta.id)
        codeOf { access.forPlatform(u.id) }.isEqualTo(403 to "ADMIN_REQUIRED")
        codeOf { access.forTenant(u.id, t.id) }.isEqualTo(404 to "TENANT_NOT_FOUND")
        codeOf { access.forTenant(ta.id, TenantIds.DEFAULT) }.isEqualTo(404 to "TENANT_NOT_FOUND")
        codeOf { access.forTenant(ta.id, UUID.randomUUID()) }.isEqualTo(404 to "TENANT_NOT_FOUND")
        assertThat(access.forTenant(ta.id, t.id).tenantRole).isEqualTo(TenantRole.TENANT_ADMIN)
    }

    @Test
    fun `tenant admin API - platform operations need SYSTEM_ADMIN, membership operations need TENANT_MEMBERS on that tenant`() {
        val sys = sessionFor(fx.user("sys4", systemAdmin = true).username)
        val ta = fx.user("tadm3"); val member = fx.user("tmember"); val outsider = fx.user("outsider")
        val created = sys.post("/api/v1/admin/tenants", """{"slug":"${slug()}","name":"API Tenant","firstAdminUserId":"${ta.id}"}""")
        assertThat(status(created)).isEqualTo(201)
        val id = sys.body(created).get("id").asString()
        val tas = sessionFor(ta.username); val outs = sessionFor(outsider.username)
        assertThat(status(outs.get("/api/v1/admin/tenants"))).isEqualTo(403)
        assertThat(status(outs.post("/api/v1/admin/tenants", """{"slug":"${slug()}","name":"nope"}"""))).isEqualTo(403)
        assertThat(status(tas.get("/api/v1/admin/tenants"))).isEqualTo(403)                  // listing all tenants is platform-only
        assertThat(status(tas.get("/api/v1/admin/tenants/$id"))).isEqualTo(200)
        assertThat(status(tas.put("/api/v1/admin/tenants/$id/members/${member.id}", """{"role":"MEMBER"}"""))).isEqualTo(200)
        assertThat(tas.body(tas.get("/api/v1/admin/tenants/$id/members")).size()).isEqualTo(2)
        assertThat(status(tas.patch("/api/v1/admin/tenants/$id/status", """{"status":"SUSPENDED"}"""))).isEqualTo(403)   // suspending = platform-only
        assertThat(status(outs.get("/api/v1/admin/tenants/$id"))).isEqualTo(404)
        assertThat(status(outs.get("/api/v1/admin/tenants/$id/members"))).isEqualTo(404)
        assertThat(status(sessionFor(member.username).get("/api/v1/admin/tenants/$id/members"))).isEqualTo(403)          // plain MEMBER: visible but no permission
        assertThat(status(tas.delete("/api/v1/admin/tenants/$id/members/${ta.id}"))).isEqualTo(409)                       // last TENANT_ADMIN
        assertThat(status(sys.patch("/api/v1/admin/tenants/$id/status", """{"status":"SUSPENDED"}"""))).isEqualTo(200)
        assertThat(status(sys.patch("/api/v1/admin/tenants/${TenantIds.DEFAULT}/status", """{"status":"DELETED"}"""))).isEqualTo(409)
    }
}
