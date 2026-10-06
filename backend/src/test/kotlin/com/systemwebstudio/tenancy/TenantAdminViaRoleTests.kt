package com.systemwebstudio.tenancy

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * C1 · contract v2 §4: tenant-level authority comes from `tenantRole` (tenant_members) + membership + scope — nothing else. A workspace
 * admin is not a tenant admin, a deactivated or removed tenant admin has no tenant authority, and a tenant admin has no workspace authority.
 */
class TenantAdminViaRoleTests : IntegrationTestBase() {
    @Autowired lateinit var access: AccessService
    @Autowired lateinit var tenants: TenantService
    private fun slug() = "role-" + UUID.randomUUID().toString().take(8)
    private fun status(block: () -> Any?): Int = try { block(); 200 } catch (e: ApiException) { e.status.value() }

    @Test
    fun `tenant admin authority = tenantRole TENANT_ADMIN on that tenant only`() {
        val ta = fx.user("role-ta"); val t = tenants.create(slug(), "Role", ta.id); val other = tenants.create(slug(), "Role2", fx.user("role-ta2").id)
        val a = access.forTenant(ta.id, t.id)
        assertThat(a.tenantRole).isEqualTo(TenantRole.TENANT_ADMIN)
        assertThat(a.permissions).containsExactlyInAnyOrder(Permission.TENANT_MANAGE, Permission.TENANT_MEMBERS)
        assertThat(a.platformScope).isFalse()
        assertThat(status { access.forTenant(ta.id, other.id) }).isEqualTo(404)                       // another tenant: no membership, no authority
    }

    @Test
    fun `a workspace admin is not a tenant admin and a plain tenant MEMBER holds no tenant permission`() {
        val sc = scenario(); val wsAdmin = fx.user("role-wsa"); fx.member(sc.ws, wsAdmin, "WORKSPACE_ADMIN")
        val a = access.forTenant(wsAdmin.id, TenantIds.DEFAULT)
        assertThat(a.tenantRole).isEqualTo(TenantRole.MEMBER)
        assertThat(a.permissions).isEmpty()
    }

    @Test
    fun `demoted, deactivated and removed tenant admins lose the authority at once`() {
        val ta = fx.user("role-ta3"); val boss = fx.user("role-boss"); val t = tenants.create(slug(), "Role3", boss.id)
        tenants.setMember(t.id, ta.id, TenantRole.TENANT_ADMIN)
        assertThat(access.forTenant(ta.id, t.id).permissions).isNotEmpty()
        tenants.setMember(t.id, ta.id, TenantRole.MEMBER)
        assertThat(access.forTenant(ta.id, t.id).permissions).isEmpty()
        tenants.setMember(t.id, ta.id, TenantRole.TENANT_ADMIN)
        jdbc.update("UPDATE tenant_members SET active = FALSE WHERE tenant_id = ? AND user_id = ?", t.id, ta.id)
        assertThat(status { access.forTenant(ta.id, t.id) }).isEqualTo(404)
    }

    @Test
    fun `tenant admin has no implicit workspace, app or data authority`() {
        val sc = scenario(); val ta = fx.user("role-ta4"); tenants.setMember(TenantIds.DEFAULT, ta.id, TenantRole.TENANT_ADMIN)
        assertThat(status { access.forWorkspace(ta.id, sc.ws) }).isEqualTo(404)
        assertThat(status { access.forProject(ta.id, sc.ws, sc.projectId) }).isEqualTo(404)
    }
}
