package com.systemwebstudio.logic.action

import tools.jackson.databind.JsonNode

/** How far a TEST-mode answer got. Never pretends: [NOT_EXECUTED] means nothing ran and nothing was validated downstream. */
enum class DryRunLevel {
    /** Nothing was sent anywhere; the plan is derived from the definition and validated input only. */
    NOT_EXECUTED,
    /** The downstream system validated the request (no side effect) — only when it explicitly supports that. */
    VALIDATED,
    /** The request ran against a sandbox the downstream system explicitly provides; [WouldRun.output] is the sandbox's answer. */
    SANDBOX
}

/** Typed outcome of a run. Shape from `docs/contracts/action-workflow.md`, plus a message, per-field details and the TEST-mode answer. */
sealed interface ActionResult {
    data class Ok(val output: JsonNode) : ActionResult

    /**
     * [code] is a stable machine code (see [ActionErrorCodes]); [retryable] tells a caller/worker whether repeating the *same* request (same
     * idempotency key) can succeed. [message] is safe to show; it never carries credentials, SQL, stack traces or raw downstream bodies.
     * [details] maps an input name to a violation.
     */
    data class Failed(
        val code: String,
        val retryable: Boolean,
        val message: String? = null,
        val details: Map<String, String> = emptyMap()
    ) : ActionResult

    /** TEST mode only: this is what the action *would* do. No side effect happened ([level] says what, if anything, was verified). */
    data class WouldRun(
        val actionId: String,
        val type: ActionType,
        val level: DryRunLevel,
        val plan: JsonNode,
        val output: JsonNode? = null,
        val reason: String? = null
    ) : ActionResult
}

/**
 * Stable codes. HTTP mapping for a future controller (not built here), following the existing convention
 * "unknown → 404, known-but-no-permission → 403":
 * UNKNOWN_ACTION 404 · FORBIDDEN / TENANT_DISABLED 403 · INVALID_INPUT / INVALID_DEFINITION / IDEMPOTENCY_KEY_REQUIRED 400/422 ·
 * LIMIT_EXCEEDED 413/422 · IDEMPOTENCY_KEY_REUSED / ACTION_IN_PROGRESS 409 · NOT_IMPLEMENTED 501 ·
 * TIMEOUT 504 · DEPENDENCY_UNAVAILABLE / AUDIT_UNAVAILABLE 503 · HANDLER_ERROR 500 · RATE_LIMITED 429 (with `details.retryAfterMillis`).
 */
object ActionErrorCodes {
    const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
    const val UNSUPPORTED_ACTION_TYPE = "UNSUPPORTED_ACTION_TYPE"
    const val FORBIDDEN = "FORBIDDEN"
    const val TENANT_DISABLED = "TENANT_DISABLED"
    const val INVALID_INPUT = "INVALID_INPUT"
    const val INVALID_DEFINITION = "INVALID_DEFINITION"
    const val LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    const val IDEMPOTENCY_KEY_REQUIRED = "IDEMPOTENCY_KEY_REQUIRED"
    const val IDEMPOTENCY_KEY_INVALID = "IDEMPOTENCY_KEY_INVALID"
    const val IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"
    const val ACTION_IN_PROGRESS = "ACTION_IN_PROGRESS"
    const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
    const val TIMEOUT = "TIMEOUT"
    const val INTERRUPTED = "INTERRUPTED"
    const val DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE"
    const val AUDIT_UNAVAILABLE = "AUDIT_UNAVAILABLE"
    const val HANDLER_ERROR = "HANDLER_ERROR"
    /** The tenant exceeded its action rate. Always retryable; `details["retryAfterMillis"]` says when. */
    const val RATE_LIMITED = "RATE_LIMITED"
}

internal fun failed(code: String, message: String, retryable: Boolean = false, details: Map<String, String> = emptyMap()) =
    ActionResult.Failed(code, retryable, message, details)
