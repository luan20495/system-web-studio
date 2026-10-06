package com.systemwebstudio.tenancy

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** C1 · T2 — TenantService invariants (authorization is covered in TenantAccessTests). */
class TenantServiceTests : IntegrationTestBase() {
    @Autowired lateinit var tenants: TenantService
    private fun slug() = "svc-" + UUID.randomUUID().toString().take(8)
    private fun code(block: () -> Unit) = assertThatThrownBy { block() }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }

    @Test
    fun `create validates slug and name, refuses duplicates, makes the first admin a TENANT_ADMIN and audits`() {
        val admin = fx.user("tadm"); val s = slug()
        val t = tenants.create(s.uppercase(), " Acme ", admin.id, admin.id)
        assertThat(t.slug).isEqualTo(s); assertThat(t.name).isEqualTo("Acme"); assertThat(t.status).isEqualTo("ACTIVE")
        assertThat(tenants.roleOf(t.id, admin.id)).isEqualTo(TenantRole.TENANT_ADMIN)
        code { tenants.create(s, "Dup") }.isEqualTo("TENANT_SLUG_TAKEN")
        code { tenants.create("Bad Slug", "x") }.isEqualTo("TENANT_SLUG_INVALID")
        code { tenants.create(slug(), "  ") }.isEqualTo("TENANT_NAME_INVALID")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'TENANT_CREATED' AND resource_id = ?", Long::class.java, t.id.toString())).isEqualTo(1L)
    }

    @Test
    fun `the DEFAULT tenant cannot be suspended or deleted, other tenants can`() {
        code { tenants.setStatus(TenantIds.DEFAULT, TenantStatus.SUSPENDED) }.isEqualTo("DEFAULT_TENANT_PROTECTED")
        val t = tenants.create(slug(), "Temp")
        assertThat(tenants.setStatus(t.id, TenantStatus.SUSPENDED).status).isEqualTo("SUSPENDED")
        assertThat(tenants.setStatus(t.id, TenantStatus.ACTIVE).status).isEqualTo("ACTIVE")
        code { tenants.get(UUID.randomUUID()) }.isEqualTo("TENANT_NOT_FOUND")
    }

    @Test
    fun `the last TENANT_ADMIN can be neither demoted nor removed, and a user may hold roles in several tenants`() {
        val a = fx.user("a"); val b = fx.user("b"); val t = tenants.create(slug(), "T", a.id)
        code { tenants.setMember(t.id, a.id, TenantRole.MEMBER) }.isEqualTo("LAST_TENANT_ADMIN")
        code { tenants.removeMember(t.id, a.id) }.isEqualTo("LAST_TENANT_ADMIN")
        tenants.setMember(t.id, b.id, TenantRole.TENANT_ADMIN)
        tenants.setMember(t.id, a.id, TenantRole.MEMBER)                                   // allowed now that b is an admin
        assertThat(tenants.roleOf(t.id, a.id)).isEqualTo(TenantRole.MEMBER)
        tenants.removeMember(t.id, a.id)
        assertThat(tenants.roleOf(t.id, a.id)).isNull()                                    // deactivated, row kept
        assertThat(jdbc.queryForObject("SELECT active FROM tenant_members WHERE tenant_id = ? AND user_id = ?", Boolean::class.java, t.id, a.id)).isFalse()
        val other = tenants.create(slug(), "T2", a.id)
        assertThat(tenants.membershipsOf(a.id).map { it.tenantId }).containsExactly(other.id)
        tenants.setMember(t.id, a.id, TenantRole.MEMBER)                                   // re-activation
        assertThat(tenants.membershipsOf(a.id).map { it.tenantId }).containsExactlyInAnyOrder(t.id, other.id)
    }
}
