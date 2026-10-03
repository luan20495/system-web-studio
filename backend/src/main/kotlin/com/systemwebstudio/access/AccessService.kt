package com.systemwebstudio.access

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.UserEntity
import com.systemwebstudio.identity.UserRepository
import com.systemwebstudio.project.ProjectEntity
import com.systemwebstudio.project.ProjectMemberRepository
import com.systemwebstudio.project.ProjectRepository
import com.systemwebstudio.project.WorkspaceMemberRepository
import org.springframework.stereotype.Service
import java.util.UUID

class AccessContext(
    val user: UserEntity,
    val workspaceId: UUID,
    val workspaceRole: String?,
    val projectRole: String?,
    val permissions: Set<Permission>,
    val project: ProjectEntity?
) {
    val userId: UUID get() = user.id
    val seesAllProjects: Boolean get() = user.systemAdmin || workspaceRole == "WORKSPACE_ADMIN"

    fun require(permission: Permission) {
        if (permission !in permissions) throw ApiException.forbidden("Missing permission: $permission")
    }
}

@Service
class AccessService(
    private val users: UserRepository,
    private val workspaceMembers: WorkspaceMemberRepository,
    private val projects: ProjectRepository,
    private val projectMembers: ProjectMemberRepository
) {
    /** Unknown or not-a-member workspaces are reported as 404 so existence is not disclosed. */
    fun forWorkspace(userId: UUID, workspaceId: UUID): AccessContext {
        val user = users.findById(userId).filter { it.enabled }.orElseThrow {
            ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "Account is disabled or no longer exists")
        }
        val member = workspaceMembers.findByWorkspaceIdAndUserIdAndActiveTrue(workspaceId, userId)
        if (member == null && !user.systemAdmin) throw ApiException.notFound("WORKSPACE_NOT_FOUND", "Workspace not found")
        val permissions = if (user.systemAdmin) PermissionMatrix.systemAdmin
        else PermissionMatrix.workspaceRoles[member!!.role].orEmpty()
        return AccessContext(user, workspaceId, member?.role, null, permissions, null)
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
        return AccessContext(ws.user, workspaceId, ws.workspaceRole, projectRole, permissions, project)
    }
}
