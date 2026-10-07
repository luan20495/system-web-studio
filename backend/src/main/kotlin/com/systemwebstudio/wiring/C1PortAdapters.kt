package com.systemwebstudio.wiring

import com.systemwebstudio.access.adapters.AccessDecision
import com.systemwebstudio.access.adapters.AppAccessRequest
import com.systemwebstudio.access.adapters.GatewayAuthRequest
import com.systemwebstudio.access.adapters.Principal
import com.systemwebstudio.access.adapters.PrincipalContext
import com.systemwebstudio.access.adapters.PrincipalOutcome
import com.systemwebstudio.access.adapters.PrincipalQuery
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayDecision
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.logic.action.AccessRequest
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.AuthorizationDecision
import com.systemwebstudio.logic.action.PrincipalResolution
import com.systemwebstudio.logic.action.PrincipalSpec
import java.util.UUID
import com.systemwebstudio.access.adapters.AccessPort as C1AccessPort
import com.systemwebstudio.access.adapters.GatewayAuthorizer as C1GatewayAuthorizer
import com.systemwebstudio.access.adapters.PrincipalResolver as C1PrincipalResolver
import com.systemwebstudio.access.adapters.TenantGate as C1TenantGate
import com.systemwebstudio.data.gateway.GatewayAuthorizer as C3GatewayAuthorizer
import com.systemwebstudio.logic.action.AccessPort as C4AccessPort
import com.systemwebstudio.logic.action.PrincipalResolver as C4PrincipalResolver
import com.systemwebstudio.logic.action.TenantGate as C4TenantGate

/*
 * C0 · the four adapters between C1's policy cores (`access.adapters.*`, they speak tenancy types and canonical permission codes) and the ports that
 * C3/C4 declare. `logic.*` and `data.*` never see an `access.*` class; these classes are plain (no Spring) and are created by the runtime configuration.
 * Everything is default-deny: an actor kind that cannot be converted, an unknown mode or a mismatch of app ids is a denial, never an exception.
 */

/** C4 [C4AccessPort] -> C1 `AccessPort`. The canonical permission code is passed through unchanged; C1 maps it to the stored permission. */
class C4AccessPortAdapter(private val c1: C1AccessPort) : C4AccessPort {
    override fun check(ctx: ActionContext, request: AccessRequest): AuthorizationDecision {
        val app = request.appId
        // The permission is checked for the application of the context; a request that names another application is refused, not re-targeted.
        if (app != null && ctx.projectId != null && app != ctx.projectId) return AuthorizationDecision.Denied("app mismatch")
        val principal = try { RuntimeContexts.principal(ctx.actor) } catch (e: IllegalArgumentException) { return AuthorizationDecision.Denied("actor kind not authorised") }
        val decision = c1.check(AppAccessRequest(principal, ctx.tenantId, ctx.workspaceId, app ?: ctx.projectId, request.permission, request.mode.name))
        return when (decision) {
            is AccessDecision.Allowed -> AuthorizationDecision.Allowed
            is AccessDecision.Denied -> AuthorizationDecision.Denied(decision.reason)
        }
    }
}

/** C4 [C4TenantGate] -> C1 `TenantGate` (only an ACTIVE tenant passes; a lookup failure is "disabled"). */
class C4TenantGateAdapter(private val c1: C1TenantGate) : C4TenantGate {
    override fun isEnabled(tenantId: UUID): Boolean = c1.isEnabled(tenantId)
}

/** C4 [C4PrincipalResolver] -> C1 `PrincipalResolver` (users / roles only; groups and department managers are denied by C1 today). */
class C4PrincipalResolverAdapter(private val c1: C1PrincipalResolver) : C4PrincipalResolver {
    override fun resolve(ctx: ActionContext, spec: PrincipalSpec): PrincipalResolution {
        val principal = try { RuntimeContexts.principal(ctx.actor) } catch (e: IllegalArgumentException) { return PrincipalResolution.Denied("actor kind not authorised") }
        val query = when (spec) {
            is PrincipalSpec.User -> PrincipalQuery.User(spec.userId, spec.tenantId)
            is PrincipalSpec.Group -> PrincipalQuery.Group(spec.groupId)
            is PrincipalSpec.DepartmentManager -> PrincipalQuery.DepartmentManager(spec.departmentId)
            is PrincipalSpec.Role -> PrincipalQuery.Role(spec.role)
        }
        return when (val outcome = c1.resolve(PrincipalContext(principal, ctx.tenantId, ctx.workspaceId), query)) {
            is PrincipalOutcome.Resolved -> PrincipalResolution.Resolved(outcome.userIds)
            is PrincipalOutcome.Denied -> PrincipalResolution.Denied(outcome.reason)
        }
    }
}

/** C3 `GatewayAuthorizer` (the interface the data gateway asks) -> C1 `GatewayAuthorizer` (the policy core). Default deny. */
class C3GatewayAuthorizerAdapter(private val c1: C1GatewayAuthorizer) : C3GatewayAuthorizer {
    override fun authorize(ctx: GatewayContext, operation: GatewayOperation, dataSourceId: UUID?): GatewayDecision {
        val decision = c1.authorize(
            GatewayAuthRequest(
                actor = Principal(ctx.actorKind, ctx.actorUserId),
                tenantId = ctx.tenantId,
                workspaceId = ctx.workspaceId,
                projectId = ctx.projectId,
                appVersionId = ctx.appVersionId,
                operation = operation.name,
                dataSourceId = dataSourceId
            )
        )
        return when (decision) {
            is AccessDecision.Allowed -> GatewayDecision.Allowed
            is AccessDecision.Denied -> GatewayDecision.Denied(decision.reason)
        }
    }
}
