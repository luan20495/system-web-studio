package com.systemwebstudio.access

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.tenancy.TenantResolution
import com.systemwebstudio.tenancy.TenantRole
import com.systemwebstudio.tenancy.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import java.util.UUID

/** Pure unit tests of [AccessEvaluator] (no Spring, no database): the decision shared by forWorkspace / forProject / ProjectScopeResolver. */
class AccessEvaluatorTests {
    private val tid = UUID.randomUUID()
    private fun tenant(status: TenantStatus = TenantStatus.ACTIVE, role: TenantRole? = TenantRole.MEMBER, active: Boolean? = true) = TenantResolution(tid, status, role, active)

    private fun denied(code: String, status: HttpStatus, block: () -> Unit) {
        val e = assertThrows<ApiException>(block)
        assertThat(e.code).isEqualTo(code)
        assertThat(e.status).isEqualTo(status)
    }

    @Test
    fun `disabled or unknown account is 401 ACCOUNT_DISABLED`() {
        denied("ACCOUNT_DISABLED", HttpStatus.UNAUTHORIZED) { AccessEvaluator.requireEnabledAccount(exists = false, enabled = false) }
        denied("ACCOUNT_DISABLED", HttpStatus.UNAUTHORIZED) { AccessEvaluator.requireEnabledAccount(exists = true, enabled = false) }
        AccessEvaluator.requireEnabledAccount(exists = true, enabled = true)
    }

    @Test
    fun `ordinary user - unknown workspace, non member, removed from tenant, deleted tenant are 404, suspended is 403`() {
        denied("WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND) { AccessEvaluator.workspace(false, null, "EDITOR", false) }
        denied("WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND) { AccessEvaluator.workspace(false, tenant(), null, false) }
        denied("WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND) { AccessEvaluator.workspace(false, tenant(active = false), "EDITOR", false) }
        denied("WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND) { AccessEvaluator.workspace(false, tenant(TenantStatus.DELETED), "EDITOR", false) }
        denied("TENANT_SUSPENDED", HttpStatus.FORBIDDEN) { AccessEvaluator.workspace(false, tenant(TenantStatus.SUSPENDED), "EDITOR", false) }
        // the legacy flag never relaxes the gates of an ordinary user
        denied("TENANT_SUSPENDED", HttpStatus.FORBIDDEN) { AccessEvaluator.workspace(false, tenant(TenantStatus.SUSPENDED), "EDITOR", true) }
    }

    @Test
    fun `ordinary member gets its workspace role permissions and tenant context`() {
        val d = AccessEvaluator.workspace(false, tenant(role = TenantRole.TENANT_ADMIN), "WORKSPACE_ADMIN", false)
        assertThat(d.permissions).isEqualTo(PermissionMatrix.workspaceRoles.getValue("WORKSPACE_ADMIN"))
        assertThat(d.workspaceRole).isEqualTo("WORKSPACE_ADMIN")
        assertThat(d.systemAdminBypass).isFalse()
        assertThat(d.tenantContext.tenantId).isEqualTo(tid)
        assertThat(d.tenantContext.tenantRole).isEqualTo(TenantRole.TENANT_ADMIN)
        assertThat(d.tenantContext.platformScope).isFalse()
        // no tenant_members row: no tenant role, still allowed (legacy rows)
        assertThat(AccessEvaluator.workspace(false, tenant(role = null, active = null), "VIEWER", false).tenantContext.tenantRole).isNull()
    }

    @Test
    fun `system admin non member gets platform scope only, even in a suspended or deleted tenant`() {
        for (st in TenantStatus.entries) {
            val d = AccessEvaluator.workspace(true, tenant(st, null, null), null, false)
            assertThat(d.permissions).isEqualTo(PermissionMatrix.platformScope)
            assertThat(d.tenantContext.platformScope).isTrue()
            assertThat(d.systemAdminBypass).isFalse()
        }
        denied("WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND) { AccessEvaluator.workspace(true, null, null, false) }
    }

    @Test
    fun `system admin through a membership is held to the tenant gates unless the legacy flag is on`() {
        denied("WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND) { AccessEvaluator.workspace(true, tenant(active = false), "EDITOR", false) }
        denied("WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND) { AccessEvaluator.workspace(true, tenant(TenantStatus.DELETED), "EDITOR", false) }
        denied("TENANT_SUSPENDED", HttpStatus.FORBIDDEN) { AccessEvaluator.workspace(true, tenant(TenantStatus.SUSPENDED), "EDITOR", false) }
        val legacy = AccessEvaluator.workspace(true, tenant(TenantStatus.SUSPENDED), "EDITOR", true)
        assertThat(legacy.systemAdminBypass).isTrue()
        assertThat(legacy.permissions).isEqualTo(PermissionMatrix.systemAdmin)
        assertThat(legacy.tenantContext.platformScope).isFalse()
        val member = AccessEvaluator.workspace(true, tenant(), "EDITOR", false)
        assertThat(member.permissions).isEqualTo(PermissionMatrix.workspaceRoles.getValue("EDITOR"))
        assertThat(member.tenantContext.platformScope).isFalse()
    }

    @Test
    fun `project - missing project or no PROJECT_READ is 404, project role adds permissions`() {
        val viewerWs = AccessEvaluator.workspace(false, tenant(), "VIEWER", false)
        denied("PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND) { AccessEvaluator.project(viewerWs, null, "OWNER") }
        denied("PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND) { AccessEvaluator.project(viewerWs, "ACTIVE", null) }
        val p = AccessEvaluator.project(viewerWs, "ACTIVE", "EDITOR")
        assertThat(p.projectRole).isEqualTo("EDITOR")
        assertThat(p.permissions).isEqualTo(PermissionMatrix.projectRoles.getValue("EDITOR"))
        // a non-member SYSTEM_ADMIN (platform scope) never reads a project it is not a member of
        val platform = AccessEvaluator.workspace(true, tenant(), null, false)
        denied("PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND) { AccessEvaluator.project(platform, "ACTIVE", null) }
        assertThat(AccessEvaluator.project(platform, "ACTIVE", "VIEWER").permissions)
            .isEqualTo(PermissionMatrix.platformScope + PermissionMatrix.projectRoles.getValue("VIEWER"))
    }

    @Test
    fun `archived project keeps only PROJECT_READ and AUDIT_READ unless ignoreArchive`() {
        val admin = AccessEvaluator.workspace(false, tenant(), "WORKSPACE_ADMIN", false)
        assertThat(AccessEvaluator.project(admin, "ARCHIVED", "OWNER").permissions).containsExactlyInAnyOrder(Permission.PROJECT_READ, Permission.AUDIT_READ)
        assertThat(AccessEvaluator.project(admin, "ARCHIVED", "OWNER", ignoreArchive = true).permissions)
            .isEqualTo(admin.permissions + PermissionMatrix.projectRoles.getValue("OWNER"))
    }
}
