package com.systemwebstudio.admin

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

data class AdminUserRow(
    val id: UUID, val username: String, val displayName: String?, val email: String?, val enabled: Boolean, val systemAdmin: Boolean,
    val authSource: String, val createdAt: Instant, val workspaces: Int, val projects: Int, val lastLoginAt: Instant?,
    /** true until the account has a usable password (activation link not used yet) */
    val pending: Boolean = false, val departmentId: UUID? = null
)
data class Membership(val id: UUID, val name: String, val role: String, val workspaceId: UUID? = null, val workspaceName: String? = null, val owner: Boolean = false)
data class AuditRow(
    val id: UUID, val createdAt: Instant, val action: String, val resourceType: String, val resourceId: String?, val actorId: UUID?, val actor: String?,
    val workspaceId: UUID?, val projectId: UUID?, val ipAddress: String?, val requestId: String?, val newValue: String?, val oldValue: String?
)
data class AdminUserDetail(val user: AdminUserRow, val workspaces: List<Membership>, val projects: List<Membership>, val activeSessions: Int, val recentActivity: List<AuditRow>)
data class StatusRequest(val enabled: Boolean? = null)

internal const val AUDIT_SELECT = """SELECT a.id, a.created_at, a.action, a.resource_type, a.resource_id, a.actor_id, coalesce(u.display_name, u.username),
    a.workspace_id, a.project_id, a.ip_address, a.request_id, a.new_value::text, a.old_value::text FROM audit_events a LEFT JOIN users u ON u.id = a.actor_id"""
internal fun auditRow(rs: java.sql.ResultSet) = AuditRow(rs.getObject(1, UUID::class.java), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getString(5),
    rs.getObject(6, UUID::class.java), rs.getString(7), rs.getObject(8, UUID::class.java), rs.getObject(9, UUID::class.java), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13))

/** User administration: list, detail, enable/disable, revoke sessions. Disabling also revokes every session of that user. */
@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserController(
    private val guard: AdminGuard, private val jdbc: JdbcTemplate, private val audit: AuditService,
    private val sessions: FindByIndexNameSessionRepository<*>
) {
    private val select = """SELECT u.id, u.username, u.display_name, u.email, u.enabled, u.system_admin, u.auth_source, u.created_at,
        (SELECT count(*) FROM workspace_members m WHERE m.user_id = u.id AND m.active),
        (SELECT count(*) FROM project_members pm JOIN projects p ON p.id = pm.project_id AND p.active WHERE pm.user_id = u.id AND pm.active),
        (SELECT max(a.created_at) FROM audit_events a WHERE a.actor_id = u.id AND a.action = 'LOGIN_SUCCESS'), u.activated_at IS NULL, u.department_id FROM users u"""
    private fun row(rs: java.sql.ResultSet) = AdminUserRow(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
        rs.getBoolean(6), rs.getString(7), rs.getTimestamp(8).toInstant(), rs.getInt(9), rs.getInt(10), rs.getTimestamp(11)?.toInstant(), rs.getBoolean(12), rs.getObject(13, UUID::class.java))

    @GetMapping
    @Transactional(readOnly = true)
    fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int, @RequestParam(required = false) q: String?,
             @RequestParam(defaultValue = "all") status: String, @AuthenticationPrincipal me: StudioUserDetails): PageDto<AdminUserRow> {
        guard.require(me.userId)
        val (p, s) = pageArgs(page, size)
        val where = StringBuilder(" WHERE TRUE"); val args = mutableListOf<Any>()
        like(q)?.let { where.append(" AND (u.username ILIKE ? OR coalesce(u.display_name,'') ILIKE ? OR coalesce(u.email,'') ILIKE ?)"); repeat(3) { _ -> args += it } }
        when (status) { "active" -> where.append(" AND u.enabled"); "disabled" -> where.append(" AND NOT u.enabled"); "admin" -> where.append(" AND u.system_admin") }
        val total = jdbc.queryForObject("SELECT count(*) FROM users u$where", Long::class.java, *args.toTypedArray())!!
        val items = jdbc.query("$select$where ORDER BY u.created_at DESC, u.id LIMIT $s OFFSET ${p.toLong() * s}", { rs, _ -> row(rs) }, *args.toTypedArray())
        return PageDto(items, total, p, s)
    }

    private fun user(id: UUID): AdminUserRow = jdbc.query("$select WHERE u.id = ?", { rs, _ -> row(rs) }, id).firstOrNull()
        ?: throw ApiException.notFound("USER_NOT_FOUND", "User not found")

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    fun detail(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): AdminUserDetail {
        guard.require(me.userId)
        val u = user(id)
        val workspaces = jdbc.query("SELECT w.id, w.name, m.role FROM workspace_members m JOIN workspaces w ON w.id = m.workspace_id WHERE m.user_id = ? AND m.active ORDER BY w.name",
            { rs, _ -> Membership(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3)) }, id)
        val projects = jdbc.query("""SELECT p.id, p.name, pm.role, w.id, w.name, p.owner_user_id = pm.user_id FROM project_members pm JOIN projects p ON p.id = pm.project_id AND p.active
            JOIN workspaces w ON w.id = p.workspace_id WHERE pm.user_id = ? AND pm.active ORDER BY p.updated_at DESC LIMIT 200""",
            { rs, _ -> Membership(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getObject(4, UUID::class.java), rs.getString(5), rs.getBoolean(6)) }, id)
        val activity = jdbc.query("$AUDIT_SELECT WHERE a.actor_id = ? ORDER BY a.created_at DESC LIMIT 20", { rs, _ -> auditRow(rs) }, id)
        return AdminUserDetail(u, workspaces, projects, sessions.findByPrincipalName(u.username).size, activity)
    }

    @PatchMapping("/{id}/status")
    @Transactional
    fun status(@PathVariable id: UUID, @RequestBody body: StatusRequest, @AuthenticationPrincipal me: StudioUserDetails): AdminUserRow {
        guard.require(me.userId)
        val enabled = body.enabled ?: throw ApiException.badRequest("VALIDATION_FAILED", "enabled is required")
        if (id == me.userId && !enabled) throw ApiException.conflict("CANNOT_DISABLE_SELF", "You cannot disable your own account")
        val u = user(id)
        if (!enabled && u.systemAdmin) {
            val others = jdbc.queryForObject("SELECT count(*) FROM users WHERE system_admin AND enabled AND id <> ?", Long::class.java, id)!!
            if (others == 0L) throw ApiException.conflict("LAST_SYSTEM_ADMIN", "The last enabled system administrator cannot be disabled")
        }
        if (u.enabled != enabled) {
            jdbc.update("UPDATE users SET enabled = ? WHERE id = ?", enabled, id)
            val revoked = if (!enabled) revoke(u.username) else 0
            audit.record(if (enabled) "USER_ENABLED" else "USER_DISABLED", "USER", id, oldValue = mapOf("enabled" to u.enabled), newValue = mapOf("enabled" to enabled, "sessionsRevoked" to revoked))
        }
        return user(id)
    }

    @PostMapping("/{id}/revoke-sessions")
    fun revokeSessions(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): Map<String, Int> {
        guard.require(me.userId)
        val u = user(id)
        val n = revoke(u.username)
        audit.record("REVOKE_SESSIONS", "USER", id, newValue = mapOf("sessionsRevoked" to n))
        return mapOf("revoked" to n)
    }

    private fun revoke(username: String): Int {
        val ids = sessions.findByPrincipalName(username).keys
        ids.forEach { sessions.deleteById(it) }
        return ids.size
    }
}
