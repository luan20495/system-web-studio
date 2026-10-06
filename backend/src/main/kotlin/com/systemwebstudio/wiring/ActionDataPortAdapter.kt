package com.systemwebstudio.wiring

import com.systemwebstudio.app.definition.AppDefinitionFormatException
import com.systemwebstudio.app.definition.AppResolutionException
import com.systemwebstudio.app.definition.QueryMode
import com.systemwebstudio.app.definition.ResolutionCodes
import com.systemwebstudio.data.gateway.GatewayResponses
import com.systemwebstudio.data.gateway.DataGateway
import com.systemwebstudio.data.gateway.GatewayMutation
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ActionDataPort
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.OperationRequest
import com.systemwebstudio.logic.action.PortOutcome
import com.systemwebstudio.logic.action.WriteRequest
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * C0 · W-05 — THE data path of an action (action-workflow.md §4, D-C0-18):
 * `Action -> ActionDataPort -> [this adapter + C2 AppDataBindingResolver] -> C3 DataGateway.mutate -> data source`.
 *
 *  * the AppDefinition is loaded tenant-scoped (another tenant's app = missing), local ids become a registered source + an approved operation key per
 *    call; nothing resolved is stored anywhere;
 *  * the idempotency key arriving here is the DERIVED key of C4 (`WriteRequest`/`OperationRequest` refuse anything else) and is forwarded unchanged;
 *  * a mutation is only ever sent in LIVE mode; TEST is answered by C4 as WouldRun before it gets here, and this adapter refuses a TEST request anyway;
 *  * `dryRunWrite`/`dryRunOperation` are NOT overridden: `Unsupported` -> C4 reports `NOT_EXECUTED`. A dry run is never emulated;
 *  * failures: everything that happens before the gateway is called is a definite, non-retryable "not executed"; what the gateway throws goes through
 *    [DataWriteErrors] (D-C0-15). [gateway] returns null when the data platform is not configured (D-C0-20) -> `DATA_RUNTIME_UNAVAILABLE`.
 */
class ActionDataPortAdapter(
    private val definitions: DefinitionLoader,
    private val resolvers: RuntimeResolvers,
    private val gateway: () -> DataGateway?
) : ActionDataPort {

    override fun write(ctx: ActionContext, request: WriteRequest): PortOutcome {
        if (request.mode != ExecutionMode.LIVE) return refused("INVALID_MODE", "A change is only sent in LIVE mode.")
        val target = try {
            resolve(ctx, request.appId, request.mode) { r -> r.query(request.queryRef).also { if (it.mode != QueryMode.WRITE) throw AppResolutionException(ResolutionCodes.WRONG_MODE, "query is not a WRITE query") }.let { Target(it.dataSource.sourceId, it.operationKey) } }
        } catch (e: Refusal) {
            return e.failure
        }
        return send(ctx, target, request.params, request.idempotencyKey)
    }

    override fun callOperation(ctx: ActionContext, request: OperationRequest): PortOutcome {
        if (request.mode != ExecutionMode.LIVE) return refused("INVALID_MODE", "An operation is only called in LIVE mode.")
        val target = try {
            resolve(ctx, request.appId, request.mode) { r -> Target(r.dataSource(request.dataSourceRef).sourceId, request.operationKey) }
        } catch (e: Refusal) {
            return e.failure
        }
        return send(ctx, target, request.params, request.idempotencyKey)
    }

    private class Target(val sourceId: UUID, val operation: String, var versionId: String? = null)

    private class Refusal(val failure: PortOutcome.Failure) : RuntimeException(null, null, false, false)

    private fun refused(code: String, message: String): PortOutcome.Failure = DataWriteErrors.notExecuted(code, message)

    private fun resolve(ctx: ActionContext, appId: UUID, mode: ExecutionMode, pick: (com.systemwebstudio.app.definition.AppDataBindingResolver) -> Target): Target {
        if (ctx.projectId != appId) throw Refusal(refused("APP_MISMATCH", "The request does not belong to this application."))
        val loaded = definitions.load(ctx.tenantId, appId, mode) ?: throw Refusal(refused("APP_NOT_FOUND", "The application is not available."))
        return try {
            val target = pick(resolvers.forDocument(ctx.tenantId, appId, mode, loaded.document))
            target.versionId = loaded.versionId
            target
        } catch (e: AppResolutionException) {
            throw Refusal(refused(e.code, "The data reference of this action cannot be resolved."))
        } catch (e: AppDefinitionFormatException) {
            throw Refusal(refused(ResolutionCodes.UNKNOWN_REFERENCE, "The application definition is not valid."))
        } catch (e: RuntimeException) {
            throw Refusal(refused("INVALID_DEFINITION", "The application definition cannot be used."))   // nothing was sent
        }
    }

    private fun send(ctx: ActionContext, target: Target, params: Map<String, JsonNode>, derivedKey: String): PortOutcome {
        val gw = gateway() ?: return refused(DATA_RUNTIME_UNAVAILABLE, "The data runtime is not available.")
        val gatewayContext = try {
            RuntimeContexts.gateway(ctx, target.versionId)
        } catch (e: IllegalArgumentException) {
            return refused("FORBIDDEN", "This actor is not allowed to change data.")   // nothing was sent
        }
        // From here on the gateway may have reached the source: every failure is classified by DataWriteErrors.
        return try {
            PortOutcome.Success(GatewayResponses.mutation(gw.mutate(gatewayContext, GatewayMutation(target.sourceId, target.operation, params, derivedKey))))
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            DataWriteErrors.forWrite(e)
        }
    }

    companion object {
        const val DATA_RUNTIME_UNAVAILABLE = "DATA_RUNTIME_UNAVAILABLE"
    }
}
