package com.systemwebstudio.admin

import com.systemwebstudio.common.ApiException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

data class PageDto<T>(val items: List<T>, val total: Long, val page: Int, val size: Int)

/**
 * Admin Console authorization: the caller must be an enabled SYSTEM ADMIN *right now* (read from the database on every
 * request, not from the login-time session snapshot). Choosing the "Admin" portal on the login screen grants nothing.
 */
@Component
class AdminGuard(private val jdbc: JdbcTemplate) {
    fun isAdmin(userId: UUID): Boolean =
        jdbc.queryForList("SELECT system_admin AND enabled AS ok FROM users WHERE id = ?", userId).firstOrNull()?.get("ok") == true

    fun require(userId: UUID) {
        if (!isAdmin(userId)) throw ApiException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "Admin Console requires a system administrator account")
    }
}

internal fun pageArgs(page: Int, size: Int): Pair<Int, Int> = page.coerceAtLeast(0) to size.coerceIn(1, 100)
internal fun like(q: String?): String? = q?.trim()?.takeIf { it.isNotEmpty() }?.let { "%" + it.replace("%", "\\%").replace("_", "\\_") + "%" }
