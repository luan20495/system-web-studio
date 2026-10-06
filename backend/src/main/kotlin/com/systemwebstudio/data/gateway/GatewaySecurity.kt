package com.systemwebstudio.data.gateway

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataAuditSink
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.tenancy.ActorKind
import com.systemwebstudio.tenancy.TenantContext
import java.util.UUID

/**
 * What a request to the Data Platform carries. Canonical contract v2 (`docs/contracts/v2/data-runtime.md` §2): [tenant] is C1's
 * `tenancy.TenantContext` (the one definition; C3's old placeholder is gone) and the rest — who acts, in which workspace, for which app version —
 * is what `TenantContext` deliberately does not carry. The C0 adapter builds this from `AccessContext` + `TenantContext`; nothing in it is ever
 * taken from a request body, query string or a header the client controls. [projectId]/[appVersionId] (C2) say which AppDefinition version its
 * mapping/view-model references are read from; [requestId] only correlates log and audit lines.
 */
data class GatewayContext(
    val tenant: TenantContext,
    val actorUserId: UUID? = null,
    val actorKind: ActorKind = ActorKind.USER,
    val requestId: String? = null,
    val workspaceId: UUID? = null,
    val projectId: UUID? = null,
    val appVersionId: String? = null
) {
    val tenantId: UUID get() = tenant.tenantId
}

/** `actor` / `workspace` / `request` audit fields of a request context, in the convention `AuditServiceSink` reads */
internal fun GatewayContext.auditFields(): Map<String, Any?> =
    mapOf("actor" to actorUserId?.toString(), "workspace" to workspaceId?.toString(), "request" to requestId)

/**
 * Every operation the Data Platform can be asked to do. The set is the demand C3 puts on C1's permission catalogue (BLOCKERS B-C3-01):
 * `DATASOURCE_READ`, `DATASOURCE_MANAGE`, `QUERY_EXECUTE` are named in `permission-model.md`; the rest are proposed there.
 */
enum class GatewayOperation {
    DATASOURCE_READ, DATASOURCE_MANAGE, QUERY_EXECUTE, MUTATION_EXECUTE, SCHEMA_DISCOVER, SCHEMA_SAMPLE,
    CACHE_REFRESH, EVENTS_SUBSCRIBE, SYNC_MANAGE, WEBHOOK_MANAGE
}

sealed interface GatewayDecision {
    data object Allowed : GatewayDecision
    /** [reason] is for the audit trail only; it is never returned to the caller. */
    data class Denied(val reason: String) : GatewayDecision
}

/**
 * Port to C1's `AccessService`/`AccessContext.require` (permission-model.md). Default-deny: an implementation resolves the real permission
 * on the server from [GatewayContext.tenant] (actor, tenant membership, workspace role) and never trusts anything the client sent.
 * [dataSourceId] is null for tenant-level operations. There is deliberately **no** implementation that allows.
 */
fun interface GatewayAuthorizer {
    fun authorize(ctx: GatewayContext, operation: GatewayOperation, dataSourceId: UUID?): GatewayDecision
}

/** The safe default until C1's adapter exists: nothing is permitted. */
object DenyAllAuthorizer : GatewayAuthorizer {
    override fun authorize(ctx: GatewayContext, operation: GatewayOperation, dataSourceId: UUID?): GatewayDecision = GatewayDecision.Denied("no authorizer configured")
}

/**
 * The one place a permission is enforced and a denial is audited. Every public entry point of the platform (gateway, discovery, sync,
 * webhook admin, events) calls [require] **before** it looks at the request, so an unauthorised caller learns nothing about what exists.
 */
class GatewayGuard(private val authorizer: GatewayAuthorizer, private val audit: DataAuditSink) {
    /** non-throwing, non-auditing form for filtering lists (a denied row simply is not listed) */
    fun allowed(ctx: GatewayContext, operation: GatewayOperation, dataSourceId: UUID?): Boolean =
        try { authorizer.authorize(ctx, operation, dataSourceId) is GatewayDecision.Allowed } catch (e: Exception) { false }


    fun require(ctx: GatewayContext, operation: GatewayOperation, dataSourceId: UUID?) {
        val decision = try { authorizer.authorize(ctx, operation, dataSourceId) } catch (e: Exception) { GatewayDecision.Denied("authorizer failed") }   // fail closed
        if (decision is GatewayDecision.Denied) {
            runCatching { audit.record(DataAuditActions.DENIED, ctx.tenantId, dataSourceId, mapOf("operation" to operation.name) + ctx.auditFields()) }
            throw ConnectorFailure(FailureCodes.PERMISSION_DENIED, "not permitted")
        }
    }
}
