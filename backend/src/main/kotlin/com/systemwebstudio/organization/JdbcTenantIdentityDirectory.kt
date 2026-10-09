package com.systemwebstudio.organization

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/** C1-owned, read-only projection of the user system for ONE tenant (`users` + `tenant_members`); the identity half of an employee. Never exposes credentials. */
@Component
class JdbcTenantIdentityDirectory(private val jdbc: JdbcTemplate) : TenantIdentityDirectory {
    private val select = """SELECT u.id, u.username, u.display_name, u.email, u.enabled, (u.activated_at IS NOT NULL) AS activated, tm.role, tm.active
        FROM tenant_members tm JOIN users u ON u.id = tm.user_id WHERE tm.tenant_id = ?"""
    private fun map(rs: java.sql.ResultSet) = TenantIdentity(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getBoolean(6), rs.getString(7), rs.getBoolean(8))

    override fun find(tenantId: UUID, userId: UUID): TenantIdentity? = jdbc.query("$select AND u.id = ?", { rs, _ -> map(rs) }, tenantId, userId).firstOrNull()
    override fun findAll(tenantId: UUID, userIds: Collection<UUID>): Map<UUID, TenantIdentity> =
        if (userIds.isEmpty()) emptyMap() else jdbc.query("$select AND u.id IN (${userIds.joinToString(",") { "?" }})", { rs, _ -> map(rs) }, tenantId, *userIds.toTypedArray()).associateBy { it.userId }
    override fun members(tenantId: UUID): List<TenantIdentity> = jdbc.query("$select ORDER BY u.id", { rs, _ -> map(rs) }, tenantId)
}
