package com.systemwebstudio.access

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.tenancy.TenantResolution
import com.systemwebstudio.tenancy.TenantRole
import com.systemwebstudio.tenancy.TenantStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/** Effective permissions of the caller in one project it is an explicit, active member of. */
data class ProjectScope(
    val projectId: UUID,
    val workspaceId: UUID,
    /** project_members.role (informational) */
    val role: String?,
    val permissions: Set<Permission>
)

/**
 * Bulk form of [AccessService.forProject] for `/auth/me`: the effective permissions of a user in EVERY project it holds an active
 * explicit project_members row for, with a CONSTANT number of SQL statements (2, independent of the number of memberships):
 *  1. `users` flags (enabled, system_admin) of the caller;
 *  2. one joined statement project_members JOIN projects JOIN workspaces LEFT JOIN workspace_members LEFT JOIN tenants LEFT JOIN tenant_members.
 * Every row is then decided in memory by the SAME pure [AccessEvaluator] that [AccessService.forWorkspace] / [AccessService.forProject]
 * use, so a project the single-resource path would deny is never listed. Nothing is cached: every call reads the database again.
 * Only the evaluator's [ApiException] denials are dropped; SQL / infrastructure errors propagate.
 */
@Service
class ProjectScopeResolver(
    private val jdbc: JdbcTemplate,
    @Value("\${app.tenancy.system-admin-business-access:false}") private val systemAdminBusinessAccess: Boolean
) {
    private class Row(
        val projectId: UUID, val workspaceId: UUID, val projectRole: String, val lifecycle: String,
        val workspaceRole: String?, val tenant: TenantResolution?
    )

    /** Ordered by workspace_id, project_id. A disabled / unknown account has no project scope (forProject would answer 401). */
    fun scopesFor(userId: UUID): List<ProjectScope> {
        val flags = jdbc.query("SELECT enabled, system_admin FROM users WHERE id = ?", { rs, _ -> rs.getBoolean(1) to rs.getBoolean(2) }, userId).firstOrNull()
        try {
            AccessEvaluator.requireEnabledAccount(flags != null, flags?.first == true)
        } catch (_: ApiException) {
            return emptyList()
        }
        val systemAdmin = flags!!.second
        // Candidates exactly as before: an ACTIVE project_members row of this user in an ACTIVE project of the same workspace.
        // The LEFT JOINs load what forWorkspace / forProject would look up one by one: TenantResolver.resolveForWorkspace (tenant row + this
        // user's tenant_members row), the ACTIVE workspace_members row, the project lifecycle. Primary keys (workspace_id,user_id) and
        // (tenant_id,user_id) guarantee at most one row per candidate.
        val rows = jdbc.query(
            """SELECT pm.project_id, pm.workspace_id, pm.role AS project_role, p.lifecycle,
                      wm.role AS workspace_role, t.id AS tenant_id, t.status AS tenant_status, tm.role AS tenant_role, tm.active AS tm_active
               FROM project_members pm
               JOIN projects p ON p.id = pm.project_id AND p.workspace_id = pm.workspace_id
               JOIN workspaces w ON w.id = pm.workspace_id
               LEFT JOIN workspace_members wm ON wm.workspace_id = pm.workspace_id AND wm.user_id = pm.user_id AND wm.active
               LEFT JOIN tenants t ON t.id = w.tenant_id
               LEFT JOIN tenant_members tm ON tm.tenant_id = w.tenant_id AND tm.user_id = pm.user_id
               WHERE pm.user_id = ? AND pm.active AND p.active
               ORDER BY pm.workspace_id, pm.project_id""",
            { rs, _ ->
                val tenantId = rs.getObject("tenant_id", UUID::class.java)
                // same mapping as TenantResolver.resolveForWorkspace (inner join workspaces/tenants => no tenant row = no resolution)
                val tenant = tenantId?.let {
                    TenantResolution(
                        it,
                        parseStatus(rs.getString("tenant_status")),
                        rs.getString("tenant_role")?.let { r -> parseRole(r) },
                        rs.getObject("tm_active")?.let { _ -> rs.getBoolean("tm_active") }
                    )
                }
                Row(
                    rs.getObject("project_id", UUID::class.java), rs.getObject("workspace_id", UUID::class.java),
                    rs.getString("project_role"), rs.getString("lifecycle"), rs.getString("workspace_role"), tenant
                )
            },
            userId
        )
        return rows.mapNotNull { r ->
            try {
                val ws = AccessEvaluator.workspace(systemAdmin, r.tenant, r.workspaceRole, systemAdminBusinessAccess)
                val p = AccessEvaluator.project(ws, r.lifecycle, r.projectRole)
                ProjectScope(r.projectId, r.workspaceId, p.projectRole, p.permissions)
            } catch (_: ApiException) {
                null    // denied exactly where forProject would deny; not disclosed in /auth/me
            }
        }
    }

    // Mirrors TenantResolver's fail-closed parsing (tenants.status / tenant_members.role are CHECK-constrained, so these fallbacks are defensive only).
    private fun parseStatus(s: String) = TenantStatus.entries.firstOrNull { it.name == s } ?: TenantStatus.SUSPENDED
    private fun parseRole(s: String) = TenantRole.entries.firstOrNull { it.name == s } ?: TenantRole.MEMBER
}
