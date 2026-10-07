package com.systemwebstudio.access.adapters

import com.systemwebstudio.access.AccessService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/** Who the runtime wants to address (approver, notification target, schedule owner). Mirrors C4's `PrincipalSpec`; converted by C0's adapter. */
sealed interface PrincipalQuery {
    /** [tenantId] other than the context tenant = cross-tenant principal: refused until the cross-tenant share policy (T16) exists */
    data class User(val userId: UUID, val tenantId: UUID? = null) : PrincipalQuery
    data class Group(val groupId: String) : PrincipalQuery
    data class DepartmentManager(val departmentId: String? = null) : PrincipalQuery
    /** TENANT_ADMIN (tenant_members) or a workspace role of the context workspace */
    data class Role(val role: String) : PrincipalQuery
}

sealed interface PrincipalOutcome {
    data class Resolved(val userIds: Set<UUID>) : PrincipalOutcome
    data class Denied(val reason: String) : PrincipalOutcome
}

data class PrincipalContext(val actor: Principal, val tenantId: UUID, val workspaceId: UUID? = null)

/**
 * Policy core of C4's `PrincipalResolver`. It never returns a user outside the context tenant: targets must be ENABLED users with an ACTIVE
 * `tenant_members` row of that tenant. Only a USER requester is accepted (TEMPORARY V2 POLICY: SYSTEM / SERVICE / APP_TOKEN are denied, see
 * ActorPolicy) and it must itself be authorised for the workspace (when given) in that tenant. Groups and department managers do not exist yet (T4) => denied.
 * Reasons are for audit only.
 */
@Component("c1PrincipalResolver")
class PrincipalResolver(private val jdbc: JdbcTemplate, private val gate: TenantGate, private val access: AccessService) {
    private val workspaceRoles = setOf("WORKSPACE_ADMIN", "EDITOR", "PUBLISHER", "VIEWER")

    fun resolve(ctx: PrincipalContext, query: PrincipalQuery): PrincipalOutcome = try {
        resolveUnsafe(ctx, query)
    } catch (e: Exception) {
        PrincipalOutcome.Denied("resolution failed")           // fail closed
    }

    private fun resolveUnsafe(ctx: PrincipalContext, q: PrincipalQuery): PrincipalOutcome {
        if (!gate.isEnabled(ctx.tenantId)) return PrincipalOutcome.Denied("tenant disabled")
        val uid = ActorPolicy.userOrNull(ctx.actor) ?: return PrincipalOutcome.Denied(ActorPolicy.DENIED_REASON)   // TEMPORARY V2 POLICY
        if (ctx.workspaceId != null) {
            val a = access.forWorkspace(uid, ctx.workspaceId)              // throws -> Denied by resolve()
            if (a.tenantId != ctx.tenantId) return PrincipalOutcome.Denied("tenant mismatch")
        } else if (!isActiveTenantMember(ctx.tenantId, uid)) return PrincipalOutcome.Denied("requester not in tenant")
        return when (q) {
            is PrincipalQuery.User -> {
                if (q.tenantId != null && q.tenantId != ctx.tenantId) PrincipalOutcome.Denied("cross-tenant principal")
                else if (isActiveTenantMember(ctx.tenantId, q.userId)) PrincipalOutcome.Resolved(setOf(q.userId))
                else PrincipalOutcome.Denied("principal not addressable")
            }
            is PrincipalQuery.Group -> PrincipalOutcome.Denied("groups are not available")
            is PrincipalQuery.DepartmentManager -> PrincipalOutcome.Denied("department managers are not available")
            is PrincipalQuery.Role -> byRole(ctx, q.role)
        }
    }

    private fun byRole(ctx: PrincipalContext, role: String): PrincipalOutcome {
        val ids: List<UUID> = when {
            role == "TENANT_ADMIN" -> jdbc.queryForList(
                """SELECT tm.user_id FROM tenant_members tm JOIN users u ON u.id = tm.user_id
                   WHERE tm.tenant_id = ? AND tm.role = 'TENANT_ADMIN' AND tm.active AND u.enabled""", UUID::class.java, ctx.tenantId)
            role in workspaceRoles -> {
                val ws = ctx.workspaceId ?: return PrincipalOutcome.Denied("workspace required for a workspace role")
                jdbc.queryForList(
                    """SELECT wm.user_id FROM workspace_members wm JOIN workspaces w ON w.id = wm.workspace_id
                       JOIN users u ON u.id = wm.user_id
                       JOIN tenant_members tm ON tm.tenant_id = w.tenant_id AND tm.user_id = wm.user_id AND tm.active
                       WHERE wm.workspace_id = ? AND w.tenant_id = ? AND wm.role = ? AND wm.active AND u.enabled""",
                    UUID::class.java, ws, ctx.tenantId, role)
            }
            else -> return PrincipalOutcome.Denied("unknown role")
        }
        return if (ids.isEmpty()) PrincipalOutcome.Denied("no principal matches") else PrincipalOutcome.Resolved(ids.toSet())
    }

    private fun isActiveTenantMember(tenantId: UUID, userId: UUID): Boolean = jdbc.queryForList(
        """SELECT 1 FROM tenant_members tm JOIN users u ON u.id = tm.user_id
           WHERE tm.tenant_id = ? AND tm.user_id = ? AND tm.active AND u.enabled""", tenantId, userId).isNotEmpty()
}
