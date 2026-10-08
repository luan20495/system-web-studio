package com.systemwebstudio.access

/**
 * Storage enum of the canonical permission vocabulary (docs/contracts/v2/tenant-permission.md §5).
 * Legacy `PROJECT_*` constants stay (many call sites) and are the storage of the canonical `APP_*` codes — see [PermissionCodes].
 * New constants carry exactly their canonical name. Deny by default: a constant is granted only where [PermissionMatrix] says so.
 */
enum class Permission {
    PROJECT_READ, PROJECT_EDIT, PROJECT_SETTINGS, PROJECT_DELETE, PROJECT_PUBLISH,
    PROJECT_CREATE, PROJECT_MEMBERS, MEMBER_MANAGE, AUDIT_READ, REGISTRY_WRITE,
    /** tenant-level administration (tenant settings / status) */
    TENANT_MANAGE,
    /** tenant membership administration (tenant_members) */
    TENANT_MEMBERS,
    /** read the company's organization structure (unit types and the unit tree) - tenant business data, NOT platform authority */
    ORG_STRUCTURE_VIEW,
    /** change the company's organization structure (unit types, units, move, archive / restore) */
    ORG_STRUCTURE_MANAGE,
    /** read the company's employee directory (profiles, organization memberships, position assignments) */
    EMPLOYEE_VIEW,
    /** create / invite employees, edit profiles, manage organization memberships and position assignments, enable / disable */
    EMPLOYEE_MANAGE,
    /** read the company's position and grade catalogs (a taxonomy separate from the organization tree) */
    POSITION_GRADE_VIEW,
    /** create / change / disable the company's position and grade catalogs */
    POSITION_GRADE_MANAGE,
    /** use a published application (run actions / see its data as an end user) */
    APP_USE,
    DATA_SOURCE_VIEW, DATA_SOURCE_MANAGE, QUERY_EXECUTE, DATA_MUTATE, ACTION_EXECUTE, WORKFLOW_EXECUTE, WORKFLOW_MANAGE
}

/**
 * Canonical code <-> storage constant. Canonical codes are the ONLY strings that cross a module boundary (C2 `PermissionDef`, C3
 * `GatewayOperation` mapping, C4 `AccessRequest.permission`); the enum names are storage and appear in legacy responses
 * (`ProjectResponse.permissions`) and must keep their current spelling.
 */
object PermissionCodes {
    /** storage constant -> canonical code, only where they differ */
    private val canonicalOfStorage: Map<Permission, String> = mapOf(
        Permission.PROJECT_READ to "APP_VIEW",
        Permission.PROJECT_EDIT to "APP_EDIT",
        Permission.PROJECT_PUBLISH to "APP_PUBLISH",
        Permission.PROJECT_MEMBERS to "APP_SHARE"      // TEMPORARY: APP_SHARE has no constant of its own until sharing (T15) lands
    )

    /** every code C2 may accept in `PermissionDef.permission` */
    val CANONICAL: Set<String> = setOf(
        "APP_VIEW", "APP_USE", "APP_EDIT", "APP_PUBLISH", "APP_SHARE",
        "DATA_SOURCE_VIEW", "DATA_SOURCE_MANAGE", "QUERY_EXECUTE", "DATA_MUTATE", "ACTION_EXECUTE", "WORKFLOW_EXECUTE", "WORKFLOW_MANAGE",
        "TENANT_MANAGE", "TENANT_MEMBERS", "MEMBER_MANAGE",
        "ORG_STRUCTURE_VIEW", "ORG_STRUCTURE_MANAGE", "EMPLOYEE_VIEW", "EMPLOYEE_MANAGE", "POSITION_GRADE_VIEW", "POSITION_GRADE_MANAGE"
    )

    private val storageOfCanonical: Map<String, Permission> =
        CANONICAL.associateWith { code -> canonicalOfStorage.entries.firstOrNull { it.value == code }?.key ?: Permission.valueOf(code) }

    /** canonical code of a storage constant; constants without a canonical code (PROJECT_SETTINGS, PROJECT_DELETE, PROJECT_CREATE, AUDIT_READ, REGISTRY_WRITE) keep their name */
    fun codeOf(p: Permission): String = canonicalOfStorage[p] ?: p.name

    /**
     * Canonical codes of [perms], sorted, for responses that cross a boundary (`/auth/me`). Storage constants that have no canonical code
     * (PROJECT_SETTINGS, PROJECT_DELETE, PROJECT_CREATE, AUDIT_READ, REGISTRY_WRITE) are internal and are NOT exposed. MEMBER_MANAGE is a portal-facing canonical capability.
     */
    fun canonicalCodesOf(perms: Collection<Permission>): List<String> = perms.map { codeOf(it) }.filter { it in CANONICAL }.distinct().sorted()

    /** null = not a canonical code. Legacy storage names such as PROJECT_READ are NOT accepted here (a boundary speaks canonical codes only). */
    fun fromCode(code: String?): Permission? = code?.let { storageOfCanonical[it] }
}

/**
 * Explicit permission matrix (server-side authority; frontend roles are never trusted). Default deny.
 *
 * Project roles (project_members.role):
 *   VIEWER     APP_VIEW, APP_USE
 *   EDITOR     APP_VIEW, APP_EDIT (+ settings), APP_USE, DATA_SOURCE_VIEW, QUERY_EXECUTE, ACTION_EXECUTE
 *   PUBLISHER  APP_VIEW, APP_PUBLISH, APP_USE
 *   OWNER      everything EDITOR has + APP_PUBLISH, delete, APP_SHARE (PROJECT_MEMBERS)
 * Workspace roles (workspace_members.role):
 *   WORKSPACE_ADMIN  every project permission on every project of the workspace, create, manage members, read audit and ALL data/logic
 *                    codes (DATA_SOURCE_MANAGE, DATA_MUTATE, WORKFLOW_EXECUTE, WORKFLOW_MANAGE, ...)
 *   EDITOR           may create projects (project permissions still come from project membership)
 *   PUBLISHER, VIEWER  no creation; project permissions only from project membership
 * DATA_SOURCE_MANAGE, DATA_MUTATE, WORKFLOW_EXECUTE and WORKFLOW_MANAGE are NOT held by any project role: they need WORKSPACE_ADMIN until
 * explicit grants exist (sharing, T15). VIEWER does not get QUERY_EXECUTE (the "only on published apps" rule needs published-state input).
 * Tenant roles (tenant_members.role, source of truth for tenant-level rights):
 *   TENANT_ADMIN  TENANT_MANAGE + TENANT_MEMBERS on its own tenant, plus the company organization / employee capabilities
 *                 (ORG_STRUCTURE_VIEW / ORG_STRUCTURE_MANAGE / EMPLOYEE_VIEW / EMPLOYEE_MANAGE / POSITION_GRADE_VIEW / POSITION_GRADE_MANAGE). Grants NO implicit access to
 *                 workspace/project business data. These six are tenant business data: a SYSTEM_ADMIN does NOT hold them by being a platform operator, a WORKSPACE_ADMIN does not
 *                 either, and an organization relation (manager / head / member of a unit) or a position / grade never grants any permission.
 *   MEMBER        no tenant-level permission.
 * System ADMIN (users.system_admin) is a PLATFORM role (D-C1-11):
 *   - may administer tenants (TENANT_MANAGE, TENANT_MEMBERS). A non-member SYSTEM_ADMIN gets no workspace/business permission;
 *     tenant-scoped provisioning is the authority for creating users/workspaces and assigning initial membership;
 *   - does NOT read tenant business data (projects, schema, versions, audit of a workspace) unless it is a member of the workspace,
 *     or the operator sets app.tenancy.system-admin-business-access=true (legacy behaviour: every permission everywhere).
 *   - can never grant ITSELF anything (see MemberController / TenantController: self-grant is rejected).
 */
object PermissionMatrix {
    private val projectAll = setOf(
        Permission.PROJECT_READ, Permission.PROJECT_EDIT, Permission.PROJECT_SETTINGS,
        Permission.PROJECT_DELETE, Permission.PROJECT_PUBLISH, Permission.PROJECT_MEMBERS
    )
    /** data/logic codes an end user of an application may hold */
    private val appUse = setOf(Permission.APP_USE, Permission.DATA_SOURCE_VIEW, Permission.QUERY_EXECUTE, Permission.ACTION_EXECUTE)
    private val managerOnly = setOf(Permission.DATA_SOURCE_MANAGE, Permission.DATA_MUTATE, Permission.WORKFLOW_EXECUTE, Permission.WORKFLOW_MANAGE)

    val projectRoles: Map<String, Set<Permission>> = mapOf(
        "VIEWER" to setOf(Permission.PROJECT_READ, Permission.APP_USE),
        "EDITOR" to setOf(Permission.PROJECT_READ, Permission.PROJECT_EDIT, Permission.PROJECT_SETTINGS) + appUse,
        "PUBLISHER" to setOf(Permission.PROJECT_READ, Permission.PROJECT_PUBLISH, Permission.APP_USE),
        "OWNER" to projectAll + appUse
    )

    val workspaceRoles: Map<String, Set<Permission>> = mapOf(
        "WORKSPACE_ADMIN" to projectAll + appUse + managerOnly + setOf(Permission.PROJECT_CREATE, Permission.MEMBER_MANAGE, Permission.AUDIT_READ),
        "EDITOR" to setOf(Permission.PROJECT_CREATE),
        "PUBLISHER" to emptySet(),
        "VIEWER" to emptySet()
    )

    /** Legacy "god mode"; only used when app.tenancy.system-admin-business-access=true. */
    val systemAdmin: Set<Permission> = Permission.entries.toSet()

    /** What a SYSTEM_ADMIN holds in a workspace it is not a member of (default policy): platform/tenant authority only. */
    val platformScope: Set<Permission> = setOf(
        Permission.TENANT_MANAGE, Permission.TENANT_MEMBERS
    )

    val tenantRoles: Map<String, Set<Permission>> = mapOf(
        "TENANT_ADMIN" to setOf(
            Permission.TENANT_MANAGE, Permission.TENANT_MEMBERS,
            Permission.ORG_STRUCTURE_VIEW, Permission.ORG_STRUCTURE_MANAGE, Permission.EMPLOYEE_VIEW, Permission.EMPLOYEE_MANAGE,
            Permission.POSITION_GRADE_VIEW, Permission.POSITION_GRADE_MANAGE
        ),
        "MEMBER" to emptySet()
    )
}
