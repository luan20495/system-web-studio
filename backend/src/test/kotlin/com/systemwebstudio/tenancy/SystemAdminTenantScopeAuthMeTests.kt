package com.systemwebstudio.tenancy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `/auth/me` for a SYSTEM_ADMIN that is also a MEMBER of a company: its `tenants[]` entry carries ONLY the codes of its member role there (what AccessService.forTenant adds on top of
 * the platform scope), the platform scope (TENANT_MANAGE + TENANT_MEMBERS on any tenant) stays in the root list. Pins `systemAdmin && systemAdminBusinessAccess` (legacy flag OFF by default).
 */
class SystemAdminTenantScopeAuthMeTests : FinalIamTestBase() {
    private val adminCodes = listOf("EMPLOYEE_MANAGE", "EMPLOYEE_VIEW", "ORG_STRUCTURE_MANAGE", "ORG_STRUCTURE_VIEW", "POSITION_GRADE_MANAGE", "POSITION_GRADE_VIEW", "TENANT_MANAGE", "TENANT_MEMBERS")

    @Test
    fun `a SYSTEM_ADMIN member of a company - the entry has the member role codes only, the platform scope stays in the root permissions`() {
        val c = company(); val op = fx.user("m052-sys", systemAdmin = true)
        jdbc.update("INSERT INTO tenant_members (tenant_id, user_id, role, active) VALUES (?, ?, 'MEMBER', true)", c.id, op.id)
        val s = sessionFor(op.username)
        val m = me(s)
        val e = m.get("tenants").toList().single { it.get("id").asString() == c.id.toString() }
        assertThat(e.get("role").asString()).isEqualTo("MEMBER")
        assertThat(strings(e.get("permissions"))).describedAs("member role codes only (none), NOT the platform scope").isEmpty()
        assertThat(strings(m.get("permissions"))).describedAs("platform scope in the root list").containsExactlyInAnyOrder("TENANT_MANAGE", "TENANT_MEMBERS")
        // promoted to TENANT_ADMIN of the company: the entry shows the eight, the platform scope is still the root's business
        jdbc.update("UPDATE tenant_members SET role = 'TENANT_ADMIN' WHERE tenant_id = ? AND user_id = ?", c.id, op.id)
        val m2 = me(s)
        val e2 = m2.get("tenants").toList().single { it.get("id").asString() == c.id.toString() }
        assertThat(strings(e2.get("permissions"))).isEqualTo(adminCodes)
        assertThat(m2.get("systemAdmin").asBoolean()).isTrue(); assertThat(m2.get("platformScope").asBoolean()).isTrue()
    }
}
