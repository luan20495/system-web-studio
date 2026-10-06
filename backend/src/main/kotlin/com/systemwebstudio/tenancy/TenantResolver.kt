package com.systemwebstudio.tenancy

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/** Result of resolving the tenant of a workspace for one user. [membershipActive] is null when the user has no tenant_members row. */
data class TenantResolution(
    val tenantId: UUID,
    val status: TenantStatus,
    val tenantRole: TenantRole?,
    val membershipActive: Boolean?
)

/**
 * Resolves tenancy from RESOURCES, never from request input: the workspace id comes from an already-routed path variable and the
 * tenant is read from the database. One query; no cross-tenant lookup is possible because the tenant is derived from the resource.
 */
@Component
class TenantResolver(private val jdbc: JdbcTemplate) {
    fun resolveForWorkspace(userId: UUID, workspaceId: UUID): TenantResolution? =
        jdbc.query(
            """SELECT w.tenant_id, t.status, tm.role, tm.active
               FROM workspaces w JOIN tenants t ON t.id = w.tenant_id
               LEFT JOIN tenant_members tm ON tm.tenant_id = w.tenant_id AND tm.user_id = ?
               WHERE w.id = ?""",
            { rs, _ ->
                TenantResolution(
                    rs.getObject(1, UUID::class.java),
                    parseStatus(rs.getString(2)),
                    rs.getString(3)?.let { parseRole(it) },
                    rs.getObject(4)?.let { rs.getBoolean(4) }
                )
            },
            userId, workspaceId
        ).firstOrNull()

    fun tenantOfWorkspace(workspaceId: UUID): UUID? =
        jdbc.queryForList("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, workspaceId).firstOrNull()

    fun tenantOfProject(projectId: UUID): UUID? =
        jdbc.queryForList("SELECT tenant_id FROM projects WHERE id = ?", UUID::class.java, projectId).firstOrNull()

    private fun parseStatus(s: String) = TenantStatus.entries.firstOrNull { it.name == s } ?: TenantStatus.SUSPENDED   // unknown => fail closed
    private fun parseRole(s: String) = TenantRole.entries.firstOrNull { it.name == s } ?: TenantRole.MEMBER            // unknown => least privilege
}
