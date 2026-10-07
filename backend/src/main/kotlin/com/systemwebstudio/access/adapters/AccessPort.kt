package com.systemwebstudio.access.adapters

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.access.PermissionCodes
import org.springframework.stereotype.Component
import java.util.UUID

/** What the Action/Workflow runtime asks about. [permission] is a CANONICAL code (`PermissionCodes.CANONICAL`); [mode] is "LIVE" or "TEST". */
data class AppAccessRequest(
    val actor: Principal,
    val tenantId: UUID,
    val workspaceId: UUID?,
    val projectId: UUID?,
    val permission: String,
    val mode: String = "LIVE"
)

/**
 * Policy core of C4's `AccessPort`. Chain: tenant enabled (TenantGate) -> canonical code known -> actor is a USER -> workspace/project
 * resolved by AccessService (tenant re-derived, membership, archived rules) -> tenant equals the claimed one -> permission held.
 * `mode=TEST` additionally needs APP_EDIT (a test run must never be possible for someone who cannot edit the app).
 * Called before any input is looked at; any exception denies.
 */
@Component("c1AccessPort")
class AccessPort(private val access: AccessService, private val gate: TenantGate) {
    fun check(r: AppAccessRequest): AccessDecision = decide {
        if (!gate.isEnabled(r.tenantId)) return@decide AccessDecision.Denied("tenant disabled")
        val permission = PermissionCodes.fromCode(r.permission) ?: return@decide AccessDecision.Denied("unknown permission code")
        if (r.mode != "LIVE" && r.mode != "TEST") return@decide AccessDecision.Denied("unknown mode")
        val userId = ActorPolicy.userOrNull(r.actor) ?: return@decide AccessDecision.Denied(ActorPolicy.DENIED_REASON)   // TEMPORARY V2 POLICY
        val workspaceId = r.workspaceId ?: return@decide AccessDecision.Denied("workspace required")
        val ctx = if (r.projectId != null) access.forProject(userId, workspaceId, r.projectId) else access.forWorkspace(userId, workspaceId)
        if (ctx.tenantId != r.tenantId) return@decide AccessDecision.Denied("tenant mismatch")
        if (permission !in ctx.permissions) return@decide AccessDecision.Denied("missing permission")
        if (r.mode == "TEST" && Permission.PROJECT_EDIT !in ctx.permissions) return@decide AccessDecision.Denied("test mode needs APP_EDIT")
        AccessDecision.Allowed
    }
}
