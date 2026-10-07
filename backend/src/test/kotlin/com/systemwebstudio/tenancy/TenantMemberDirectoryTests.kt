package com.systemwebstudio.tenancy

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** C1 · H-C1-11: tenant member administration has a tenant-scoped candidate directory, enriched member metadata and no cross-tenant lookup. */
class TenantMemberDirectoryTests : IntegrationTestBase() {
    @Autowired lateinit var tenants: TenantService

    private fun slug(prefix: String) = prefix + "-" + UUID.randomUUID().toString().take(8)
    private fun base(t: UUID) = "/api/v1/admin/tenants/$t"

    @Test
    fun `tenant admin sees only eligible same-tenant candidates, can add them, and member list contains safe identity metadata`() {
        val admin = fx.user("ta-dir")
        jdbc.update("UPDATE users SET activated_at = now(), email = ? WHERE id = ?", "ta-dir@example.test", admin.id)
        val tenant = tenants.create(slug("dir"), "Directory Tenant", admin.id, admin.id)

        val candidate = fx.user("candidate")
        jdbc.update("UPDATE users SET activated_at = now(), email = ? WHERE id = ?", "candidate@example.test", candidate.id)
        // A previously removed member is eligible for reactivation and is already scoped to this tenant.
        jdbc.update(
            "INSERT INTO tenant_members (tenant_id, user_id, role, active, created_by) VALUES (?,?, 'MEMBER', FALSE, ?)",
            tenant.id, candidate.id, admin.id
        )

        val foreignAdmin = fx.user("foreign-admin")
        jdbc.update("UPDATE users SET activated_at = now() WHERE id = ?", foreignAdmin.id)
        val foreignTenant = tenants.create(slug("foreign"), "Foreign Tenant", foreignAdmin.id, foreignAdmin.id)
        val foreign = fx.user("foreign-user")
        jdbc.update("UPDATE users SET activated_at = now(), email = ? WHERE id = ?", "foreign@example.test", foreign.id)
        tenants.setMember(foreignTenant.id, foreign.id, TenantRole.MEMBER, foreignAdmin.id)

        val s = sessionFor(admin.username)
        val candidates = s.body(s.get("${base(tenant.id)}/member-candidates?q=candidate"))
        assertThat(candidates.size()).isEqualTo(1)
        assertThat(candidates[0].get("userId").asString()).isEqualTo(candidate.id.toString())
        assertThat(candidates[0].get("username").asString()).isEqualTo(candidate.username)
        assertThat(candidates[0].get("displayName").asString()).isEqualTo("candidate")
        assertThat(candidates[0].get("email").asString()).isEqualTo("candidate@example.test")

        // A user known only to another tenant is not discoverable and cannot be added by guessed UUID.
        assertThat(s.body(s.get("${base(tenant.id)}/member-candidates?q=foreign")).size()).isEqualTo(0)
        assertThat(s.put("${base(tenant.id)}/members/${foreign.id}", """{"role":"MEMBER"}""").response.status).isEqualTo(404)

        assertThat(s.put("${base(tenant.id)}/members/${candidate.id}", """{"role":"MEMBER"}""").response.status).isEqualTo(200)
        val members = s.body(s.get("${base(tenant.id)}/members")).toList()
        val row = members.single { it.get("userId").asString() == candidate.id.toString() }
        assertThat(row.get("username").asString()).isEqualTo(candidate.username)
        assertThat(row.get("displayName").asString()).isEqualTo("candidate")
        assertThat(row.get("email").asString()).isEqualTo("candidate@example.test")
        assertThat(s.body(s.get("${base(tenant.id)}/member-candidates?q=candidate")).size()).isEqualTo(0)
    }

    @Test
    fun `tenant directory and member operations are isolated and the last tenant admin is protected`() {
        val a = fx.user("ta-a"); jdbc.update("UPDATE users SET activated_at = now() WHERE id = ?", a.id)
        val b = fx.user("ta-b"); jdbc.update("UPDATE users SET activated_at = now() WHERE id = ?", b.id)
        val ta = tenants.create(slug("ta"), "Tenant A", a.id, a.id)
        val tb = tenants.create(slug("tb"), "Tenant B", b.id, b.id)

        val sb = sessionFor(b.username)
        assertThat(sb.get("${base(ta.id)}/member-candidates").response.status).isEqualTo(404)
        assertThat(sb.get("${base(ta.id)}/members").response.status).isEqualTo(404)

        val sa = sessionFor(a.username)
        val demote = sa.put("${base(ta.id)}/members/${a.id}", """{"role":"MEMBER"}""")
        assertThat(demote.response.status).isEqualTo(403)
        assertThat(sa.body(demote).get("code").asString()).isEqualTo("SELF_GRANT_FORBIDDEN")

        val remove = sa.delete("${base(ta.id)}/members/${a.id}")
        assertThat(remove.response.status).isEqualTo(409)
        assertThat(sa.body(remove).get("code").asString()).isEqualTo("LAST_TENANT_ADMIN")

        assertThat(tenants.roleOf(ta.id, a.id)).isEqualTo(TenantRole.TENANT_ADMIN)
        assertThat(tenants.roleOf(tb.id, b.id)).isEqualTo(TenantRole.TENANT_ADMIN)
    }
}
