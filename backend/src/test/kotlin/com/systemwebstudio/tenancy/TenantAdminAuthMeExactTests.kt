package com.systemwebstudio.tenancy

import com.systemwebstudio.access.PermissionCodes
import com.systemwebstudio.access.PermissionMatrix
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * C1 final - the ONE dedicated exact regression of the tenant-level permission sets, through the real HTTP `/auth/me`:
 * a TENANT_ADMIN holds exactly the eight canonical tenant + organization codes (sorted, as the API returns them), a plain tenant MEMBER (and a
 * WORKSPACE_ADMIN who is not a tenant admin) holds none of them, and a non-member SYSTEM_ADMIN holds exactly the two platform codes.
 */
class TenantAdminAuthMeExactTests : IntegrationTestBase() {
    @Autowired lateinit var tenants: TenantService

    private val tenantAdminCodes = listOf(
        "EMPLOYEE_MANAGE", "EMPLOYEE_VIEW", "ORG_STRUCTURE_MANAGE", "ORG_STRUCTURE_VIEW", "POSITION_GRADE_MANAGE", "POSITION_GRADE_VIEW", "TENANT_MANAGE", "TENANT_MEMBERS"
    )
    private val platformCodes = listOf("TENANT_MANAGE", "TENANT_MEMBERS")

    private fun me(username: String) = sessionFor(username).let { s -> s.body(s.get("/api/v1/auth/me")) }
    private fun codes(n: tools.jackson.databind.JsonNode) = n.toList().map { it.asString() }

    @Test
    fun `the eight codes are the matrix of TENANT_ADMIN and nothing else (guards the constant the HTTP assertions below rely on)`() {
        assertThat(PermissionCodes.canonicalCodesOf(PermissionMatrix.tenantRoles.getValue("TENANT_ADMIN"))).isEqualTo(tenantAdminCodes)
        assertThat(PermissionCodes.canonicalCodesOf(PermissionMatrix.platformScope)).isEqualTo(platformCodes)
        assertThat(tenantAdminCodes).isSorted()
    }

    @Test
    fun `GET auth me - TENANT_ADMIN exactly the eight codes, tenant MEMBER none of them, non-member SYSTEM_ADMIN exactly the two platform codes`() {
        val ta = fx.user("exact-ta"); val t = tenants.create("exact-" + UUID.randomUUID().toString().take(8), "ExactMe", ta.id)
        val taMe = me(ta.username)
        assertThat(taMe.get("tenantId").asString()).isEqualTo(t.id.toString())
        assertThat(taMe.get("tenantRole").asString()).isEqualTo("TENANT_ADMIN")
        assertThat(taMe.get("platformScope").asBoolean()).isFalse()
        assertThat(codes(taMe.get("permissions"))).describedAs("TENANT_ADMIN top-level permissions, in the order the API returns them").isEqualTo(tenantAdminCodes)
        assertThat(codes(taMe.get("permissions"))).hasSize(8).doesNotContain("MEMBER_MANAGE", "PROJECT_CREATE")

        val member = fx.user("exact-member"); val ws = fx.workspace(); fx.member(ws, member, "WORKSPACE_ADMIN")   // DEFAULT-tenant MEMBER, even a workspace admin
        val mMe = me(member.username)
        assertThat(mMe.get("tenantRole").asString()).isEqualTo("MEMBER")
        assertThat(codes(mMe.get("permissions"))).describedAs("a plain tenant MEMBER holds no tenant-level permission").isEmpty()
        assertThat(codes(mMe.get("permissions"))).doesNotContainAnyElementsOf(tenantAdminCodes)

        val sys = fx.user("exact-sys", systemAdmin = true)
        val sMe = me(sys.username)
        assertThat(sMe.get("platformScope").asBoolean()).isTrue(); assertThat(sMe.get("businessAccess").asBoolean()).isFalse()
        assertThat(codes(sMe.get("permissions"))).describedAs("a non-member SYSTEM_ADMIN holds the platform pair only").isEqualTo(platformCodes)
        assertThat(codes(sMe.get("permissions"))).doesNotContain("ORG_STRUCTURE_VIEW", "ORG_STRUCTURE_MANAGE", "EMPLOYEE_VIEW", "EMPLOYEE_MANAGE", "POSITION_GRADE_VIEW", "POSITION_GRADE_MANAGE")
    }
}
