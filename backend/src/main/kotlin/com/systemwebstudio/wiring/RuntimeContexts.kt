package com.systemwebstudio.wiring

import com.systemwebstudio.access.AccessContext
import com.systemwebstudio.access.adapters.Principal
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.logic.action.ActionActor
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ActorKind as LogicActorKind
import com.systemwebstudio.tenancy.TenantContext
import com.systemwebstudio.tenancy.TenantStatus
import java.util.UUID

/**
 * C0 · the ONE place where server-side authorisation results become the contexts of C3/C4. Nothing here reads a request body, a query string or a
 * header: the inputs are an [AccessContext] that `AccessService.forProject` produced (tenant resolved from the workspace) and ids taken from the path
 * AFTER that call proved they belong together.
 */
object RuntimeContexts {
    /** HTTP: the caller is always a USER (D-C0-14). [projectId] is the application. */
    fun action(access: AccessContext, projectId: UUID, requestId: String?): ActionContext =
        ActionContext(access.tenantId, ActionActor(access.userId, LogicActorKind.USER), access.workspaceId, projectId, requestId)

    /** C1 principal of an action actor. Throws [IllegalArgumentException] for an unknown kind — callers deny. */
    fun principal(actor: ActionActor): Principal = Principal(ActorKinds.toTenancy(actor.kind), actor.userId)

    /**
     * C3 context of an action run. The tenant gate was applied by C4 and the permission check is repeated by C3's authorizer (C1 `GatewayAuthorizer`)
     * from actor + workspace + project, so the [TenantContext] only carries the tenant id (role/platform scope are not used for decisions here).
     * [appVersionId] is the published version for LIVE, null for the working draft (TEST).
     */
    fun gateway(ctx: ActionContext, appVersionId: String?): GatewayContext = GatewayContext(
        tenant = TenantContext(ctx.tenantId, null, TenantStatus.ACTIVE, false),
        actorUserId = ctx.actor.userId,
        actorKind = ActorKinds.toTenancy(ctx.actor.kind),
        requestId = ctx.requestId,
        workspaceId = ctx.workspaceId,
        projectId = ctx.projectId,
        appVersionId = appVersionId
    )

    /** HTTP read path (R1): same shape, built from the access result. */
    fun gateway(access: AccessContext, projectId: UUID, requestId: String?, appVersionId: String?): GatewayContext =
        gateway(action(access, projectId, requestId), appVersionId)
}
