package com.systemwebstudio.audit

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.identity.StudioUserDetails
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

data class AuditEventDto(
    val id: UUID, val action: String, val resourceType: String, val resourceId: String?, val projectId: UUID?, val actorId: UUID?,
    val requestId: String?, val createdAt: Instant, val oldValue: String?, val newValue: String?
)

/** Read-only. There is deliberately no write or delete endpoint; the table also rejects UPDATE/DELETE in the database. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/audit-events")
class AuditController(private val access: AccessService, private val jdbc: JdbcTemplate) {
    @GetMapping
    @Transactional(readOnly = true)
    fun list(
        @PathVariable workspaceId: UUID,
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(required = false) action: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @AuthenticationPrincipal me: StudioUserDetails
    ): List<AuditEventDto> {
        access.forWorkspace(me.userId, workspaceId).require(Permission.AUDIT_READ)
        return jdbc.query(
            """SELECT id, action, resource_type, resource_id, project_id, actor_id, request_id, created_at, old_value::text, new_value::text
               FROM audit_events WHERE workspace_id = ? AND (?::uuid IS NULL OR project_id = ?::uuid) AND (?::text IS NULL OR action = ?::text)
               ORDER BY created_at DESC, id LIMIT ?""",
            { rs, _ -> AuditEventDto(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, UUID::class.java),
                rs.getObject(6, UUID::class.java), rs.getString(7), rs.getTimestamp(8).toInstant(), rs.getString(9), rs.getString(10)) },
            workspaceId, projectId, projectId, action, action, limit.coerceIn(1, 200))
    }
}
