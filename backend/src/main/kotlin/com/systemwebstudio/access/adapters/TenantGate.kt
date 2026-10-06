package com.systemwebstudio.access.adapters

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/** "A disabled tenant runs nothing": true only for an existing ACTIVE tenant. Unknown tenant or any error => false (fail closed). */
@Component("c1TenantGate")
class TenantGate(private val jdbc: JdbcTemplate) {
    fun isEnabled(tenantId: UUID): Boolean = try {
        jdbc.queryForList("SELECT status FROM tenants WHERE id = ?", String::class.java, tenantId).firstOrNull() == "ACTIVE"
    } catch (e: Exception) {
        false
    }
}
