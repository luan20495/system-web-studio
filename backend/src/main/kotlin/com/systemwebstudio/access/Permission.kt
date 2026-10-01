package com.systemwebstudio.access

enum class Permission {
    PROJECT_READ, PROJECT_EDIT, PROJECT_SETTINGS, PROJECT_DELETE, PROJECT_PUBLISH,
    PROJECT_CREATE, MEMBER_MANAGE, AUDIT_READ, REGISTRY_WRITE
}

/**
 * Explicit permission matrix (server-side authority; frontend roles are never trusted).
 *
 * Project roles (project_members.role):
 *   VIEWER     read
 *   EDITOR     read, edit schema/prompts/assets/restore, settings
 *   PUBLISHER  read, publish
 *   OWNER      read, edit, settings, delete, publish
 * Workspace roles (workspace_members.role):
 *   WORKSPACE_ADMIN  every project permission on every project of the workspace, create, manage members, read audit
 *   EDITOR           may create projects (project permissions still come from project membership)
 *   PUBLISHER, VIEWER  no creation; project permissions only from project membership
 * System ADMIN (users.system_admin): everything, in every workspace.
 */
object PermissionMatrix {
    private val projectAll = setOf(
        Permission.PROJECT_READ, Permission.PROJECT_EDIT, Permission.PROJECT_SETTINGS,
        Permission.PROJECT_DELETE, Permission.PROJECT_PUBLISH
    )

    val projectRoles: Map<String, Set<Permission>> = mapOf(
        "VIEWER" to setOf(Permission.PROJECT_READ),
        "EDITOR" to setOf(Permission.PROJECT_READ, Permission.PROJECT_EDIT, Permission.PROJECT_SETTINGS),
        "PUBLISHER" to setOf(Permission.PROJECT_READ, Permission.PROJECT_PUBLISH),
        "OWNER" to projectAll
    )

    val workspaceRoles: Map<String, Set<Permission>> = mapOf(
        "WORKSPACE_ADMIN" to projectAll + setOf(Permission.PROJECT_CREATE, Permission.MEMBER_MANAGE, Permission.AUDIT_READ),
        "EDITOR" to setOf(Permission.PROJECT_CREATE),
        "PUBLISHER" to emptySet(),
        "VIEWER" to emptySet()
    )

    val systemAdmin: Set<Permission> = Permission.entries.toSet()
}
