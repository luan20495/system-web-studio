package com.systemwebstudio.access

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.tenancy.TenantContext
import com.systemwebstudio.tenancy.TenantResolution
import com.systemwebstudio.tenancy.TenantStatus
import org.springframework.http.HttpStatus

/** Outcome of [AccessEvaluator.workspace]: what the caller holds in one workspace. */
data class WorkspaceDecision(
    val workspaceRole: String?,
    val permissions: Set<Permission>,
    val tenantContext: TenantContext,
    /** true only when a SYSTEM_ADMIN acts with the legacy business-data bypass (app.tenancy.system-admin-business-access=true) */
    val systemAdminBypass: Boolean
)

/** Outcome of [AccessEvaluator.project]: the effective permissions in one project of an already-authorized workspace. */
data class ProjectDecision(val projectRole: String?, val permissions: Set<Permission>)

/**
 * The ONLY implementation of the workspace / project authorization decision. Pure and side-effect free: it takes data that was
 * already loaded from the database and either returns the decision or throws the denial ([ApiException]) the API answers with.
 *
 * Callers:
 *  - [AccessService.forWorkspace] / [AccessService.forProject]: single-resource lookups, then this evaluator;
 *  - [ProjectScopeResolver]: one bulk statement for all of a user's project memberships (`/auth/me`), then this evaluator per row.
 * Both paths therefore cannot diverge. Do not re-implement any of these rules elsewhere.
 */
object AccessEvaluator {
    /** Authentication gate: the account must exist and be enabled ([exists]=false or [enabled]=false -> 401 ACCOUNT_DISABLED). */
    fun requireEnabledAccount(exists: Boolean, enabled: Boolean) {
        if (!exists || !enabled) throw ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "Account is disabled or no longer exists")
    }

    /**
     * Workspace decision.
     * @param systemAdmin live users.system_admin of the (enabled) caller
     * @param tenant tenant resolution of the workspace for the caller (null = unknown workspace / no tenant)
     * @param memberRole role of the caller's ACTIVE workspace_members row, null when there is none
     * @param systemAdminBusinessAccess legacy flag app.tenancy.system-admin-business-access
     *
     * Unknown or not-a-member workspaces -> 404 WORKSPACE_NOT_FOUND (existence is not disclosed). Ordinary users, and a SYSTEM_ADMIN
     * acting through a workspace MEMBERSHIP without the legacy flag, are held to the tenant gates: removed from the tenant or tenant
     * DELETED -> 404 WORKSPACE_NOT_FOUND, tenant SUSPENDED -> 403 TENANT_SUSPENDED. A non-member SYSTEM_ADMIN gets platform scope only.
     */
    fun workspace(systemAdmin: Boolean, tenant: TenantResolution?, memberRole: String?, systemAdminBusinessAccess: Boolean): WorkspaceDecision {
        if (tenant == null || (memberRole == null && !systemAdmin)) throw ApiException.notFound("WORKSPACE_NOT_FOUND", "Workspace not found")
        if (!systemAdmin) {
            // tenant-level gates for ordinary users: removed from the tenant, or tenant not usable => no access to its workspaces
            tenantGates(tenant)
        } else if (memberRole != null && !systemAdminBusinessAccess) {
            // a platform operator that acts through a workspace MEMBERSHIP is held to the same tenant-status gates as everybody else (no business authority in a dead / suspended company)
            tenantGates(tenant)
        }
        val bypass = systemAdmin && systemAdminBusinessAccess
        val permissions = when {
            bypass -> PermissionMatrix.systemAdmin                                              // LEGACY BYPASS (flag-gated)
            memberRole != null -> PermissionMatrix.workspaceRoles[memberRole].orEmpty()
            else -> PermissionMatrix.platformScope                                              // SYSTEM_ADMIN, non-member: platform scope only
        }
        val platform = systemAdmin && memberRole == null && !bypass
        val tenantRole = if (tenant.membershipActive == true) tenant.tenantRole else null
        return WorkspaceDecision(memberRole, permissions, TenantContext(tenant.tenantId, tenantRole, tenant.status, platform), bypass)
    }

    /**
     * Project decision on top of an already-granted [ws].
     * @param projectLifecycle lifecycle of the ACTIVE project row of that workspace; null = no such project (404 PROJECT_NOT_FOUND)
     * @param projectRole role of the caller's ACTIVE project_members row, null when there is none
     * @param ignoreArchive true only for archive / restore itself
     *
     * Without PROJECT_READ the project is reported as 404 PROJECT_NOT_FOUND. An ARCHIVED project keeps only PROJECT_READ + AUDIT_READ.
     */
    fun project(ws: WorkspaceDecision, projectLifecycle: String?, projectRole: String?, ignoreArchive: Boolean = false): ProjectDecision {
        if (projectLifecycle == null) throw ApiException.notFound("PROJECT_NOT_FOUND", "Project not found")
        var permissions = ws.permissions + PermissionMatrix.projectRoles[projectRole].orEmpty()
        if (Permission.PROJECT_READ !in permissions) throw ApiException.notFound("PROJECT_NOT_FOUND", "Project not found")
        if (projectLifecycle == "ARCHIVED" && !ignoreArchive) permissions = permissions.intersect(setOf(Permission.PROJECT_READ, Permission.AUDIT_READ))
        return ProjectDecision(projectRole, permissions)
    }

    private fun tenantGates(tenant: TenantResolution) {
        if (tenant.membershipActive == false || tenant.status == TenantStatus.DELETED) throw ApiException.notFound("WORKSPACE_NOT_FOUND", "Workspace not found")
        if (tenant.status == TenantStatus.SUSPENDED) throw ApiException.forbidden("This tenant is suspended", "TENANT_SUSPENDED")
    }
}
