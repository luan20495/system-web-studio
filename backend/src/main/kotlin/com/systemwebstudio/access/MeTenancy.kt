package com.systemwebstudio.access

import com.systemwebstudio.tenancy.TenantIds
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

data class TenantMembershipSummary(val id: UUID, val slug: String, val name: String, val status: String, val role: String)

/** What `/auth/me` tells the portals so they can route and gate. Everything here is derived server-side; the UI never decides authorisation. */
data class MeTenancy(
    /** primary tenant (DEFAULT if the user belongs to it, else the oldest membership); null = no tenant membership (e.g. a pure platform operator) */
    val tenantId: UUID?,
    val tenantRole: String?,
    /** true for SYSTEM_ADMIN: acts on the platform scope; says nothing about business data (see [businessAccess]) */
    val platformScope: Boolean,
    /** true only when a SYSTEM_ADMIN also has the legacy business-data bypass (app.tenancy.system-admin-business-access=true) */
    val businessAccess: Boolean,
    val tenants: List<TenantMembershipSummary>,
    /** platform + primary-tenant level permissions as canonical codes (see PermissionCodes) */
    val permissions: List<String>
)

@Service
class MeTenancyService(
    private val jdbc: JdbcTemplate,
    @Value("\${app.tenancy.system-admin-business-access:false}") private val systemAdminBusinessAccess: Boolean
) {
    fun forUser(userId: UUID, systemAdmin: Boolean): MeTenancy {
        val tenants = jdbc.query(
            """SELECT t.id, t.slug, t.name, t.status, tm.role FROM tenant_members tm JOIN tenants t ON t.id = tm.tenant_id
               WHERE tm.user_id = ? AND tm.active AND t.status <> 'DELETED'
               ORDER BY (t.id = ?) DESC, tm.created_at, t.slug""", { rs, _ ->
                TenantMembershipSummary(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))
            }, userId, TenantIds.DEFAULT)
        val primary = tenants.firstOrNull()
        val perms = linkedSetOf<Permission>()
        if (systemAdmin) perms += PermissionMatrix.platformScope
        primary?.let { perms += PermissionMatrix.tenantRoles[it.role].orEmpty() }
        return MeTenancy(primary?.id, primary?.role, systemAdmin, systemAdmin && systemAdminBusinessAccess, tenants, PermissionCodes.canonicalCodesOf(perms))
    }

    /** Permissions (canonical codes) of a workspace row in `/auth/me`; role "ADMIN" = a SYSTEM_ADMIN that is not a member. */
    fun workspacePermissions(role: String): List<String> {
        val set = if (role == "ADMIN") (if (systemAdminBusinessAccess) PermissionMatrix.systemAdmin else PermissionMatrix.platformScope)
        else PermissionMatrix.workspaceRoles[role].orEmpty()
        return PermissionCodes.canonicalCodesOf(set)
    }
}
