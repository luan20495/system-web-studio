package com.systemwebstudio.tenancy

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** C1 · `/auth/me` exposes tenant id/role, platform scope and canonical permissions for portal routing (default policy). */
class MeTenancyTests : IntegrationTestBase() {
    @Autowired lateinit var tenants: TenantService
    private fun me(username: String) = sessionFor(username).let { s -> s.body(s.get("/api/v1/auth/me")) }
    private fun strings(n: tools.jackson.databind.JsonNode) = n.toList().map { it.asString() }.also { codes ->
        assertThat(codes).describedAs("/me exposes canonical permission codes only").isSubsetOf(com.systemwebstudio.access.PermissionCodes.CANONICAL)
    }

    @Test
    fun `ordinary member - tenant MEMBER of the DEFAULT tenant, no platform scope, workspace permissions as canonical codes`() {
        val u = fx.user("me-u"); val w = fx.workspace(); fx.member(w, u, "WORKSPACE_ADMIN")
        val m = me(u.username)
        assertThat(m.get("tenantId").asString()).isEqualTo(TenantIds.DEFAULT.toString())
        assertThat(m.get("tenantRole").asString()).isEqualTo("MEMBER")
        assertThat(m.get("platformScope").asBoolean()).isFalse(); assertThat(m.get("businessAccess").asBoolean()).isFalse()
        assertThat(strings(m.get("permissions"))).isEmpty()
        assertThat(m.get("tenants").toList().map { it.get("id").asString() }).contains(TenantIds.DEFAULT.toString())
        val row = m.get("workspaces").toList().single { it.get("id").asString() == w.toString() }
        assertThat(row.get("tenantId").asString()).isEqualTo(TenantIds.DEFAULT.toString())
        assertThat(strings(row.get("permissions"))).contains("APP_VIEW", "APP_EDIT", "APP_PUBLISH", "APP_SHARE", "DATA_SOURCE_MANAGE", "WORKFLOW_MANAGE", "MEMBER_MANAGE")
    }

    @Test
    fun `viewer and workspace editor rows carry no canonical permission (PROJECT_CREATE is not part of the vocabulary)`() {
        val u = fx.user("me-v"); val w1 = fx.workspace(); val w2 = fx.workspace(); fx.member(w1, u, "VIEWER"); fx.member(w2, u, "EDITOR")
        val rows = me(u.username).get("workspaces").toList().associateBy { it.get("id").asString() }
        assertThat(strings(rows.getValue(w1.toString()).get("permissions"))).isEmpty()
        assertThat(strings(rows.getValue(w2.toString()).get("permissions"))).isEmpty()
    }

    @Test
    fun `tenant admin - tenantRole TENANT_ADMIN and the tenant permissions, still no platform scope`() {
        val u = fx.user("me-ta"); val t = tenants.create("me-" + UUID.randomUUID().toString().take(8), "MeTenant", u.id)
        val m = me(u.username)
        assertThat(m.get("tenantId").asString()).isEqualTo(t.id.toString())                                  // the only membership is the primary tenant
        assertThat(m.get("tenantRole").asString()).isEqualTo("TENANT_ADMIN")
        assertThat(strings(m.get("permissions"))).containsExactly("TENANT_MANAGE", "TENANT_MEMBERS")
        assertThat(m.get("platformScope").asBoolean()).isFalse()
    }

    @Test
    fun `system admin - platform scope without business access, non-member workspaces show platform permissions only`() {
        val sc = scenario(); val sys = fx.user("me-sys", systemAdmin = true)
        val m = me(sys.username)
        assertThat(m.get("systemAdmin").asBoolean()).isTrue(); assertThat(m.get("platformScope").asBoolean()).isTrue(); assertThat(m.get("businessAccess").asBoolean()).isFalse()
        assertThat(strings(m.get("permissions"))).containsExactly("TENANT_MANAGE", "TENANT_MEMBERS")
        assertThat(strings(m.get("permissions"))).doesNotContain("APP_VIEW", "DATA_MUTATE")
        val row = m.get("workspaces").toList().single { it.get("id").asString() == sc.ws.toString() }
        assertThat(row.get("role").asString()).isEqualTo("ADMIN")
        assertThat(strings(row.get("permissions"))).containsExactlyInAnyOrder("TENANT_MANAGE", "TENANT_MEMBERS")
        assertThat(m.path("tenantId").let { it.isNull || it.isMissingNode }).isTrue()                                                         // no membership of its own
    }
}
