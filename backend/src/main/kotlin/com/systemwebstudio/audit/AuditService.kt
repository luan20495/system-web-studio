package com.systemwebstudio.audit

import com.systemwebstudio.common.RequestIdFilter
import com.systemwebstudio.identity.StudioUserDetails
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Append-only audit writer. Joins the caller's transaction when there is one, so a rolled-back
 * business change leaves no audit row; outside a transaction it commits on its own.
 */
@Service
class AuditService(private val jdbc: JdbcTemplate, private val json: JsonMapper) {
    fun record(
        action: String,
        resourceType: String,
        resourceId: Any? = null,
        workspaceId: UUID? = null,
        projectId: UUID? = null,
        actorId: UUID? = currentActorId(),
        oldValue: Any? = null,
        newValue: Any? = null
    ) {
        val req = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        jdbc.update(
            """INSERT INTO audit_events (id, workspace_id, project_id, actor_id, action, resource_type, resource_id,
               old_value, new_value, ip_address, user_agent, request_id)
               VALUES (?,?,?,?,?,?,?, CAST(? AS jsonb), CAST(? AS jsonb), ?,?,?)""",
            UUID.randomUUID(), workspaceId, projectId, actorId, action, resourceType, resourceId?.toString(),
            oldValue?.let { json.writeValueAsString(it) }, newValue?.let { json.writeValueAsString(it) },
            req?.remoteAddr, req?.getHeader("User-Agent")?.take(400), RequestIdFilter.current()
        )
    }

    companion object {
        fun currentActorId(): UUID? =
            (SecurityContextHolder.getContext().authentication?.principal as? StudioUserDetails)?.userId
    }
}
