package com.systemwebstudio.wiring

import com.systemwebstudio.logic.action.ActionExecution
import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.FollowUp
import com.systemwebstudio.logic.workflow.WorkflowResult
import com.systemwebstudio.logic.workflow.WorkflowRunView
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/** A status code and a JSON body; [retryAfterSeconds] becomes the `Retry-After` header. */
class RuntimeReply(val status: Int, val body: JsonNode, val retryAfterSeconds: Long? = null)

/**
 * C0 · result -> HTTP (docs/contracts/v2/runtime-api.md §3/§4). Pure. The HTTP status never makes a code retryable: `retryable` in the body is C4's value, and
 * `IDEMPOTENCY_OUTCOME_UNKNOWN` / `MUTATION_REJECTED` are forced to `false` here as well (belt and braces — C4's `PortOutcome.toResult` already does).
 * No credential, SQL, stack trace or downstream body can appear: only C4's stable code + safe message + per-field details.
 */
class RuntimeResponses(private val json: JsonMapper) {

    fun status(code: String): Int = when (code) {
        "INVALID_INPUT", "IDEMPOTENCY_KEY_INVALID", "IDEMPOTENCY_KEY_REQUIRED", "KEY_INVALID", RuntimeRequests.INVALID -> 400
        "FORBIDDEN", "TENANT_DISABLED" -> 403
        "UNKNOWN_ACTION", "UNKNOWN_WORKFLOW", "WORKFLOW_RUN_NOT_FOUND" -> 404
        "IDEMPOTENCY_OUTCOME_UNKNOWN", "ACTION_IN_PROGRESS", "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_IN_PROGRESS", "IDEMPOTENCY_CONFLICT", "CONFLICT" -> 409
        "MUTATION_REJECTED", "INVALID_DEFINITION", "LIMIT_EXCEEDED", "UNSUPPORTED_ACTION_TYPE", "MAPPING_REF_REQUIRED" -> 422
        "RATE_LIMITED" -> 429
        "NOT_IMPLEMENTED" -> 501
        "DEPENDENCY_UNAVAILABLE", "AUDIT_UNAVAILABLE", "DATA_RUNTIME_UNAVAILABLE", "RUNTIME_STORES_VOLATILE" -> 503
        "TIMEOUT" -> 504
        else -> 500
    }

    /** `RATE_LIMITED` carries `details.retryAfterMillis` (C4); the header is its rounded-up seconds, at least 1. */
    private fun retryAfter(code: String, details: Map<String, String>): Long? =
        if (code != "RATE_LIMITED") null else ((details["retryAfterMillis"]?.toLongOrNull() ?: 1000L) + 999L) / 1000L

    fun action(actionId: String, mode: ExecutionMode, execution: ActionExecution): RuntimeReply {
        val body = result(json.createObjectNode(), actionId, mode, execution.result)
        val followUps = body.putArray("followUps")
        for (f in execution.followUps) followUps.add(followUp(f, mode))
        val r = execution.result
        return when (r) {
            is ActionResult.Failed -> RuntimeReply(status(r.code), body, retryAfter(r.code, r.details))
            is ActionResult.Ok, is ActionResult.WouldRun -> RuntimeReply(200, body)
        }
    }

    /** An action-shaped failure that did not come from C4 (the volatile-store guard, a missing runtime). */
    fun actionFailure(actionId: String, mode: ExecutionMode, code: String, message: String): RuntimeReply =
        action(actionId, mode, ActionExecution(ActionResult.Failed(code, false, message)))

    private fun followUp(f: FollowUp, mode: ExecutionMode): JsonNode {
        val o = result(json.createObjectNode(), f.actionId, mode, f.result)
        o.put("on", f.on.name)
        return o
    }

    private fun result(o: ObjectNode, actionId: String, mode: ExecutionMode, r: ActionResult): ObjectNode {
        o.put("actionId", actionId)
        o.put("mode", mode.name)
        when (r) {
            is ActionResult.Ok -> { o.put("status", "OK"); o.set<JsonNode>("output", r.output) }
            is ActionResult.WouldRun -> {
                o.put("status", "WOULD_RUN")
                o.put("type", r.type.name)
                o.put("level", r.level.name)
                o.set<JsonNode>("plan", r.plan)
                r.output?.let { out -> o.set<JsonNode>("output", out) }
                r.reason?.let { reason -> o.put("reason", reason) }
            }
            is ActionResult.Failed -> {
                o.put("status", "FAILED")
                val e = o.putObject("error")
                e.put("code", r.code)
                e.put("message", r.message ?: r.code)
                e.put("retryable", r.retryable && !neverRetryable(r.code))
                val d = e.putObject("details")
                for ((k, v) in r.details) d.put(k, v)
            }
        }
        return o
    }

    private fun neverRetryable(code: String) = code == "IDEMPOTENCY_OUTCOME_UNKNOWN" || code == "MUTATION_REJECTED"

    // --- workflows -------------------------------------------------------------------------------------------------------------------------------------------

    fun workflowRun(v: WorkflowRunView, status: Int = 200): RuntimeReply {
        val o = json.createObjectNode()
        o.put("runId", v.runId.toString())
        o.put("workflowId", v.workflowId)
        o.put("mode", v.mode.name)
        o.put("status", v.status.name)
        if (v.currentStepId != null) o.put("currentStepId", v.currentStepId) else o.putNull("currentStepId")
        val steps = o.putArray("steps")
        for (s in v.steps) {
            val so = steps.addObject()
            so.put("stepId", s.stepId)
            so.put("status", s.status.name)
            so.put("attempt", s.attempt)
            if (s.output != null) so.set<JsonNode>("output", s.output) else so.putNull("output")
            if (s.errorCode != null) so.put("errorCode", s.errorCode) else so.putNull("errorCode")
            if (s.errorMessage != null) so.put("errorMessage", s.errorMessage) else so.putNull("errorMessage")
            if (s.startedAt != null) so.put("startedAt", s.startedAt.toString()) else so.putNull("startedAt")
            if (s.finishedAt != null) so.put("finishedAt", s.finishedAt.toString()) else so.putNull("finishedAt")
            so.put("simulated", s.simulated)
            if (s.dryRunLevel != null) so.put("dryRunLevel", s.dryRunLevel.name) else so.putNull("dryRunLevel")
        }
        if (v.errorCode != null) o.put("errorCode", v.errorCode) else o.putNull("errorCode")
        if (v.errorMessage != null) o.put("errorMessage", v.errorMessage) else o.putNull("errorMessage")
        o.put("compensation", v.compensation.name)
        o.put("createdAt", v.createdAt.toString())
        o.put("updatedAt", v.updatedAt.toString())
        if (v.finishedAt != null) o.put("finishedAt", v.finishedAt.toString()) else o.putNull("finishedAt")
        return RuntimeReply(status, o)
    }

    fun workflowFailure(f: WorkflowResult.Failed): RuntimeReply = error(status(f.code), f.code, f.message, f.retryable && !neverRetryable(f.code))

    /** The common error body (`ApiError` shape) for failures that are not an action/workflow result envelope. */
    fun error(status: Int, code: String, message: String, retryable: Boolean = false, requestId: String? = null): RuntimeReply {
        val o = json.createObjectNode()
        o.put("code", code)
        o.put("message", message)
        if (requestId != null) o.put("requestId", requestId) else o.putNull("requestId")
        o.put("retryable", retryable)
        o.putObject("details")
        return RuntimeReply(status, o)
    }
}
