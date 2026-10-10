package com.systemwebstudio.wiring

import com.systemwebstudio.logic.action.AccessPort
import com.systemwebstudio.logic.action.AccessRequest
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ActionDefinitionProvider
import com.systemwebstudio.logic.action.ActionExecution
import com.systemwebstudio.logic.action.ActionRequest
import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.ActionRuntime
import com.systemwebstudio.logic.action.AuthorizationDecision
import com.systemwebstudio.logic.action.DispatchOutcome
import com.systemwebstudio.logic.action.Event
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.LogicPermissions
import com.systemwebstudio.logic.action.ResourceKind
import com.systemwebstudio.logic.approval.DecisionKind
import com.systemwebstudio.logic.workflow.ApprovalDecisionView
import com.systemwebstudio.logic.workflow.WorkflowResult
import com.systemwebstudio.logic.workflow.WorkflowRunView
import com.systemwebstudio.logic.workflow.WorkflowRuntime
import com.systemwebstudio.logic.workflow.WorkflowStartRequest
import java.util.UUID

/**
 * C0 · D-C0-20. C4's run stores, queue and idempotency records are in memory (single node, lost on restart) until a reviewed persistence migration
 * exists, so the replay guarantee that makes a retried write safe does NOT hold across a restart or a second node. Until that is accepted explicitly
 * (`app.workflow.allow-volatile-stores=true`, dev / E2E) a LIVE run that can change state is refused with `RUNTIME_STORES_VOLATILE` (503, not retryable,
 * nothing was executed). TEST runs and non-mutating actions are never affected. The refusal is only shown to a caller who passed the permission check
 * for the operation, so it discloses nothing to anybody else.
 */
const val RUNTIME_STORES_VOLATILE = "RUNTIME_STORES_VOLATILE"
private const val VOLATILE_MESSAGE = "Live execution of changes is switched off until the run store is persistent."

class VolatileActionGuard(
    private val delegate: ActionRuntime,
    private val definitions: ActionDefinitionProvider,
    private val access: AccessPort,
    private val allowVolatile: Boolean
) : ActionRuntime {

    override fun execute(ctx: ActionContext, request: ActionRequest): ActionResult = blocked(ctx, request) ?: delegate.execute(ctx, request)

    override fun run(ctx: ActionContext, request: ActionRequest): ActionExecution =
        blocked(ctx, request)?.let { ActionExecution(it) } ?: delegate.run(ctx, request)

    /** Not reachable from any route of runtime-api.md; delegated unchanged. A route that exposes it needs its own decision. */
    override fun dispatch(ctx: ActionContext, event: Event): List<DispatchOutcome> = delegate.dispatch(ctx, event)

    private fun blocked(ctx: ActionContext, request: ActionRequest): ActionResult.Failed? {
        if (allowVolatile || request.mode != ExecutionMode.LIVE) return null
        val appId = ctx.projectId ?: return null
        if (!chainMutates(ctx, appId, request)) return null
        val decision = access.check(ctx, AccessRequest(LogicPermissions.ACTION_EXECUTE, ResourceKind.ACTION, request.actionId, appId, request.mode))
        if (decision !is AuthorizationDecision.Allowed) return null           // the delegate answers the unauthorised exactly as before
        return ActionResult.Failed(RUNTIME_STORES_VOLATILE, false, VOLATILE_MESSAGE)
    }

    /** The action itself or anything its onSuccess / onError chain can start (bounded) changes state. */
    private fun chainMutates(ctx: ActionContext, appId: UUID, request: ActionRequest): Boolean {
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.addLast(request.actionId)
        while (queue.isNotEmpty() && seen.size < MAX_CHAIN_SCAN) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            val def = definitions.find(ctx.tenantId, appId, id, request.mode) ?: continue
            if (def.type.mutatesState) return true
            def.onSuccess.forEach { queue.addLast(it) }
            def.onError.forEach { queue.addLast(it) }
        }
        return false
    }

    companion object { const val MAX_CHAIN_SCAN = 64 }
}

class VolatileWorkflowGuard(
    private val delegate: WorkflowRuntime,
    private val access: AccessPort,
    private val allowVolatile: Boolean
) : WorkflowRuntime {

    override fun start(ctx: ActionContext, request: WorkflowStartRequest): WorkflowResult<WorkflowRunView> {
        if (!allowVolatile && request.mode == ExecutionMode.LIVE) {
            val decision = access.check(ctx, AccessRequest(LogicPermissions.WORKFLOW_EXECUTE, ResourceKind.WORKFLOW, request.workflowRef, ctx.projectId, request.mode))
            if (decision is AuthorizationDecision.Allowed) return WorkflowResult.Failed(RUNTIME_STORES_VOLATILE, VOLATILE_MESSAGE, false)
        }
        return delegate.start(ctx, request)
    }

    override fun status(ctx: ActionContext, runId: UUID): WorkflowResult<WorkflowRunView> = delegate.status(ctx, runId)

    override fun cancel(ctx: ActionContext, runId: UUID): WorkflowResult<WorkflowRunView> = delegate.cancel(ctx, runId)

    // a decision acts on an approval that already exists (it could only be created by a run that was admitted), so the volatile-store refusal of `start` has nothing to add
    override fun decideApproval(ctx: ActionContext, runId: UUID, approvalId: UUID, decision: DecisionKind, comment: String?): WorkflowResult<ApprovalDecisionView> =
        delegate.decideApproval(ctx, runId, approvalId, decision, comment)
}
