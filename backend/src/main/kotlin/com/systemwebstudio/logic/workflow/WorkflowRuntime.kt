package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ExecutionMode
import tools.jackson.databind.JsonNode
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
}
