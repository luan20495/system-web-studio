package com.systemwebstudio.access

import com.systemwebstudio.tenancy.TenantIds
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * One ACTIVE tenant membership of the caller (DELETED tenants and inactive memberships are never listed).
 * [role] is INFORMATIONAL ONLY (display / routing): a client must NEVER authorize from it. [permissions] is the canonical authorization signal for THIS tenant and ONLY this
 * tenant: the codes `PermissionMatrix.tenantRoles[role]` grants there (TENANT_ADMIN: the eight tenant + organization codes; MEMBER: none), recomputed from the database on every
 * `/auth/me` call, in memory (no SQL per tenant). It is independent of the root `permissions[]` (which describe the primary tenant + the platform scope) and of the tenant [status]:
 * in a SUSPENDED tenant the capabilities are still listed, but the server refuses organization / employee / position writes there with 403 TENANT_SUSPENDED.
 */
data class TenantMembershipSummary(val id: UUID, val slug: String, val name: String, val status: String, val role: String, val permissions: List<String> = emptyList())

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
                val role = rs.getString(5)
                // the SAME set AccessService.forTenant grants in that tenant: the member role's codes; with the legacy business bypass a SYSTEM_ADMIN holds the TENANT_ADMIN set everywhere
                val set = if (systemAdmin && systemAdminBusinessAccess) PermissionMatrix.tenantRoles.getValue("TENANT_ADMIN") else PermissionMatrix.tenantRoles[role].orEmpty()
                TenantMembershipSummary(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), role, PermissionCodes.canonicalCodesOf(set))
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
