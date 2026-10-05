package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.ActionContext
import tools.jackson.databind.JsonNode
import java.util.UUID

/*
 * PLACEHOLDER (PREP-T13). Only the contract surface from `docs/contracts/action-workflow.md` so that
 * `WorkflowStarterPort` (logic.action) has a documented counterpart. No engine, no persistence, no RabbitMQ here;
 * that is T13/T14 work. `logic.workflow` may depend on `logic.action`, never the other way around.
 */

@JvmInline value class WorkflowRunId(val value: UUID)

enum class WorkflowRunStatus { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

interface WorkflowRuntime {
    /** Idempotent on (tenant, workflowId, [idempotencyKey]). */
    fun start(ctx: ActionContext, workflowId: String, input: JsonNode, idempotencyKey: String): WorkflowRunId
    fun status(ctx: ActionContext, runId: WorkflowRunId): WorkflowRunStatus
    fun cancel(ctx: ActionContext, runId: WorkflowRunId)
}
