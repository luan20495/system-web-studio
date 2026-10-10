package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ExecutionMode
import tools.jackson.databind.JsonNode
import com.systemwebstudio.logic.approval.ApprovalStatus
import com.systemwebstudio.logic.approval.DecisionKind
import java.util.UUID

/** Tenant- and app-scoped lookup of workflow definitions. [ExecutionMode.TEST] reads the working draft, LIVE the published version. */
interface WorkflowDefinitionProvider {
    fun find(tenantId: UUID, appId: UUID, workflowId: String, mode: ExecutionMode): WorkflowDefinition?
}

data class WorkflowStartRequest(
    val workflowRef: String,
    val input: JsonNode,
    /** Required: starting twice with the same key and input returns the same run. */
    val idempotencyKey: String,
    val mode: ExecutionMode = ExecutionMode.LIVE,
    /** Nesting depth of the caller (0 for a top-level start). */
    val callDepth: Int = 0
)

object WorkflowErrorCodes {
    const val UNKNOWN_WORKFLOW = "UNKNOWN_WORKFLOW"
    const val RUN_NOT_FOUND = "WORKFLOW_RUN_NOT_FOUND"
    const val FORBIDDEN = "FORBIDDEN"
    const val INVALID_INPUT = "INVALID_INPUT"
    const val INVALID_DEFINITION = "INVALID_DEFINITION"
    const val LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    const val KEY_INVALID = "IDEMPOTENCY_KEY_INVALID"
    const val KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"
    const val TENANT_DISABLED = "TENANT_DISABLED"
    const val DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE"
    const val AUDIT_UNAVAILABLE = "AUDIT_UNAVAILABLE"
    const val CONFLICT = "CONFLICT"
    const val TIMEOUT = "TIMEOUT"
    const val DEAD_LETTERED = "DEAD_LETTERED"
    const val APPROVAL_REJECTED = "APPROVAL_REJECTED"
    const val APPROVAL_EXPIRED = "APPROVAL_EXPIRED"
    const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
    /** the approval named in the request is not an approval of THIS run (or does not exist): the same answer for every cause */
    const val APPROVAL_NOT_FOUND = "APPROVAL_NOT_FOUND"
    const val APPROVAL_ALREADY_DECIDED = "APPROVAL_ALREADY_DECIDED"
    const val APPROVAL_CONFLICT = "APPROVAL_CONFLICT"
    const val RATE_LIMITED = "RATE_LIMITED"
}

sealed interface WorkflowResult<out T> {
    data class Ok<T>(val value: T) : WorkflowResult<T>
    data class Failed(val code: String, val message: String, val retryable: Boolean = false) : WorkflowResult<Nothing>
}

/**
 * Public surface of the workflow runtime (what an HTTP controller, written by C0/C5 wiring, calls). Nothing here blocks on the workflow:
 * [start] persists the run, publishes the first job and returns; workers do the rest.
 */
interface WorkflowRuntime {
    /** Idempotent on (tenant, app, workflow, caller, [WorkflowStartRequest.idempotencyKey]). Needs APP_USE + WORKFLOW_EXECUTE. */
    fun start(ctx: ActionContext, request: WorkflowStartRequest): WorkflowResult<WorkflowRunView>

    /** The run's creator, or anyone with WORKFLOW_MANAGE. Anything else sees "not found". */
    fun status(ctx: ActionContext, runId: UUID): WorkflowResult<WorkflowRunView>

    /** Idempotent. Stops further steps; optionally compensates (definition's `compensateOnCancel`). */
    fun cancel(ctx: ActionContext, runId: UUID): WorkflowResult<WorkflowRunView>

    /**
     * An approver decides the approval that [runId] waits for. Order: tenant gate, the run must live in exactly the caller's scope (tenant / workspace / project, else
     * "run not found"), the explicit `WORKFLOW_MANAGE` permission, the approval must be an approval of THIS run and application, and the caller must be one of the approvers
     * snapshotted when the request was created (never an org relation, role string, position or grade). The same user deciding the same way again is idempotent; deciding
     * the other way, or deciding a finished approval, is a deterministic conflict. The decision resumes the run exactly once (the step is claimed by compare-and-set).
     */
    fun decideApproval(ctx: ActionContext, runId: UUID, approvalId: UUID, decision: DecisionKind, comment: String? = null): WorkflowResult<ApprovalDecisionView>
}

/** The answer to a decision: the approval's state and the run as it is now (the resumed step runs on a worker, so the run may still say WAITING for a moment). */
data class ApprovalDecisionView(val approvalId: UUID, val approvalStatus: ApprovalStatus, val requiredApprovals: Int, val approvals: Int, val run: WorkflowRunView)
