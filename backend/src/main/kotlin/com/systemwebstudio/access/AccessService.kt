package com.systemwebstudio.access

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.UserEntity
import com.systemwebstudio.identity.UserRepository
import com.systemwebstudio.project.ProjectEntity
import com.systemwebstudio.project.ProjectMemberRepository
import com.systemwebstudio.project.ProjectRepository
import com.systemwebstudio.project.WorkspaceMemberRepository
import com.systemwebstudio.tenancy.TenantContext
import com.systemwebstudio.tenancy.TenantMemberRepository
import com.systemwebstudio.tenancy.TenantRepository
import com.systemwebstudio.tenancy.TenantResolver
import com.systemwebstudio.tenancy.TenantRole
import com.systemwebstudio.tenancy.TenantStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Result of an authorization check on a workspace / project. The three trailing parameters are additive (T2) and default to the
 * legacy meaning, so existing callers keep compiling and behaving the same.
 */
class AccessContext(
    val user: UserEntity,
    val workspaceId: UUID,
    val workspaceRole: String?,
    val projectRole: String?,
    val permissions: Set<Permission>,
    val project: ProjectEntity?,
    /** Tenant the workspace belongs to — resolved from the workspace, never from request input. */
    val tenantId: UUID = TenantContext.DEFAULT.tenantId,
    val tenantContext: TenantContext = TenantContext.DEFAULT,
    /** true only when a SYSTEM_ADMIN acts with the legacy business-data bypass (flag app.tenancy.system-admin-business-access=true). */
    val systemAdminBypass: Boolean = false
) {
    val userId: UUID get() = user.id
    val seesAllProjects: Boolean get() = systemAdminBypass || workspaceRole == "WORKSPACE_ADMIN"
    /** true when the caller holds only the platform-scope permissions of a SYSTEM_ADMIN (no workspace membership, no bypass). */
    val platformScope: Boolean get() = tenantContext.platformScope

    fun require(permission: Permission) {
        if (permission !in permissions) throw ApiException.forbidden("Missing permission: $permission")
    }
}

/** Result of a tenant-level authorization check. */
class TenantAccess(
    val user: UserEntity,
    val tenantId: UUID,
    val tenantRole: TenantRole?,
    val permissions: Set<Permission>,
    val platformScope: Boolean
) {
    val userId: UUID get() = user.id
    fun require(permission: Permission) {
        if (permission !in permissions) throw ApiException.forbidden("Missing permission: $permission")
    }
}

@Service
class AccessService(
    private val users: UserRepository,
    private val workspaceMembers: WorkspaceMemberRepository,
    private val projects: ProjectRepository,
    private val projectMembers: ProjectMemberRepository,
    private val tenantResolver: TenantResolver,
    private val tenants: TenantRepository,
    private val tenantMembers: TenantMemberRepository,
    /**
     * Legacy switch (D-C1-11). false (default): SYSTEM_ADMIN is platform-scope only. true: restores "every permission in every
     * workspace". The ONLY place the old bypass lives is the `bypass ->` branch in [forWorkspace].
     */
    @Value("\${app.tenancy.system-admin-business-access:false}") private val systemAdminBusinessAccess: Boolean
) {
    private fun enabledUser(userId: UUID): UserEntity = users.findById(userId).filter { it.enabled }.orElseThrow {
        ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "Account is disabled or no longer exists")
    }

    /**
     * Chain: authenticate (enabled user) -> resolve tenant from the workspace -> check tenant status/membership -> authorize.
     * Unknown or not-a-member workspaces are reported as 404 so existence is not disclosed.
     */
    fun forWorkspace(userId: UUID, workspaceId: UUID): AccessContext {
        val user = enabledUser(userId)
        val tenant = tenantResolver.resolveForWorkspace(userId, workspaceId)
        val member = workspaceMembers.findByWorkspaceIdAndUserIdAndActiveTrue(workspaceId, userId)
        if (tenant == null || (member == null && !user.systemAdmin)) throw ApiException.notFound("WORKSPACE_NOT_FOUND", "Workspace not found")
        if (!user.systemAdmin) {
            // tenant-level gates for ordinary users: removed from the tenant, or tenant not usable => no access to its workspaces
            if (tenant.membershipActive == false || tenant.status == TenantStatus.DELETED) throw ApiException.notFound("WORKSPACE_NOT_FOUND", "Workspace not found")
            if (tenant.status == TenantStatus.SUSPENDED) throw ApiException.forbidden("This tenant is suspended", "TENANT_SUSPENDED")
        } else if (member != null && !systemAdminBusinessAccess) {
            // a platform operator that acts through a workspace MEMBERSHIP is held to the same tenant-status gates as everybody else (no business authority in a dead / suspended company)
            if (tenant.status == TenantStatus.DELETED) throw ApiException.notFound("WORKSPACE_NOT_FOUND", "Workspace not found")
            if (tenant.status == TenantStatus.SUSPENDED) throw ApiException.forbidden("This tenant is suspended", "TENANT_SUSPENDED")
        }
        val bypass = user.systemAdmin && systemAdminBusinessAccess
        val permissions = when {
            bypass -> PermissionMatrix.systemAdmin                                              // LEGACY BYPASS (flag-gated)
            member != null -> PermissionMatrix.workspaceRoles[member.role].orEmpty()
            else -> PermissionMatrix.platformScope                                              // SYSTEM_ADMIN, non-member: platform scope only
        }
        val platform = user.systemAdmin && member == null && !bypass
        val tenantRole = if (tenant.membershipActive == true) tenant.tenantRole else null
        val ctx = TenantContext(tenant.tenantId, tenantRole, tenant.status, platform)
        return AccessContext(user, workspaceId, member?.role, null, permissions, null, tenant.tenantId, ctx, bypass)
    }

    /** An ARCHIVED application keeps only read access (and audit reading); [ignoreArchive] is for archive/restore itself. */
    fun forProject(userId: UUID, workspaceId: UUID, projectId: UUID, ignoreArchive: Boolean = false): AccessContext {
        val ws = forWorkspace(userId, workspaceId)
        val project = projects.findByIdAndWorkspaceIdAndActiveTrue(projectId, workspaceId)
            ?: throw ApiException.notFound("PROJECT_NOT_FOUND", "Project not found")
        val projectRole = projectMembers.findByProjectIdAndUserIdAndActiveTrue(projectId, userId)?.role
        var permissions = ws.permissions + PermissionMatrix.projectRoles[projectRole].orEmpty()
        if (Permission.PROJECT_READ !in permissions) throw ApiException.notFound("PROJECT_NOT_FOUND", "Project not found")
        if (project.lifecycle == "ARCHIVED" && !ignoreArchive) permissions = permissions.intersect(setOf(Permission.PROJECT_READ, Permission.AUDIT_READ))
        return AccessContext(ws.user, workspaceId, ws.workspaceRole, projectRole, permissions, project, ws.tenantId, ws.tenantContext, ws.systemAdminBypass)
    }

    /**
     * Tenant-level check. SYSTEM_ADMIN (platform) may administer any existing tenant; a TENANT_ADMIN only its own; a plain MEMBER
     * can see its tenant but holds no tenant permission; everybody else gets 404.
     */
    fun forTenant(userId: UUID, tenantId: UUID): TenantAccess {
        val user = enabledUser(userId)
        val tenant = tenants.findById(tenantId).orElse(null) ?: throw ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found")
        val m = tenantMembers.findByTenantIdAndUserId(tenantId, userId)?.takeIf { it.active }
        if (user.systemAdmin) {
            // Platform operator: TENANT_MANAGE + TENANT_MEMBERS on any existing tenant (provisioning), NOTHING of the tenant's business data (organization, employees) -
            // unless it is an active member of that tenant (then exactly its member role on top) or the legacy flag app.tenancy.system-admin-business-access is on.
            val permissions = when {
                systemAdminBusinessAccess -> PermissionMatrix.tenantRoles.getValue("TENANT_ADMIN")       // LEGACY BYPASS (flag-gated)
                m != null && tenant.status != TenantStatus.DELETED.name -> PermissionMatrix.platformScope + PermissionMatrix.tenantRoles[m.role].orEmpty()
                else -> PermissionMatrix.platformScope
            }
            return TenantAccess(user, tenantId, m?.let { TenantRole.valueOf(it.role) }, permissions, true)
        }
        if (m == null || tenant.status == TenantStatus.DELETED.name) throw ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found")
        return TenantAccess(user, tenantId, TenantRole.valueOf(m.role), PermissionMatrix.tenantRoles[m.role].orEmpty(), false)
    }

    /**
     * Organization / employee / position data of a SUSPENDED company is read-only: a write answers 403 TENANT_SUSPENDED (the same code [forWorkspace] uses), a read stays allowed.
     * Call AFTER [forTenant] + the permission check (a stranger never learns the status).
     */
    fun requireTenantWritable(tenantId: UUID) {
        val t = tenants.findById(tenantId).orElse(null) ?: return
        if (t.status == TenantStatus.SUSPENDED.name) throw ApiException.forbidden("This tenant is suspended", "TENANT_SUSPENDED")
    }

    /** Platform-only operations (tenant creation/listing). Requires an enabled SYSTEM_ADMIN. */
    fun forPlatform(userId: UUID): UserEntity {
        val user = enabledUser(userId)
        if (!user.systemAdmin) throw ApiException.forbidden("Platform administration requires a system administrator account", "ADMIN_REQUIRED")
        return user
    }
}
