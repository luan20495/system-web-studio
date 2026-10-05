package com.systemwebstudio.logic.action

import tools.jackson.databind.JsonNode

/** Typed outcome of a run. Shape from `docs/contracts/action-workflow.md`, plus a message and per-field details. */
sealed interface ActionResult {
    data class Ok(val output: JsonNode) : ActionResult

    /**
     * [code] is a stable machine code (see [ActionErrorCodes]); [retryable] tells a caller/worker whether repeating
     * the *same* request (same idempotency key) can succeed. [message] is safe to show; it never carries
     * credentials, SQL, stack traces or raw downstream bodies. [details] maps an input name to a violation.
     */
    data class Failed(
        val code: String,
        val retryable: Boolean,
        val message: String? = null,
        val details: Map<String, String> = emptyMap()
    ) : ActionResult
}

/**
 * Stable codes. HTTP mapping for a future controller (not built here), following the existing convention
 * "unknown → 404, known-but-no-permission → 403":
 * UNKNOWN_ACTION 404 · FORBIDDEN 403 · INVALID_INPUT / INVALID_DEFINITION / IDEMPOTENCY_KEY_REQUIRED 400/422 ·
 * LIMIT_EXCEEDED 413/422 · IDEMPOTENCY_KEY_REUSED / ACTION_IN_PROGRESS 409 · NOT_IMPLEMENTED 501 ·
 * TIMEOUT 504 · DEPENDENCY_UNAVAILABLE / AUDIT_UNAVAILABLE 503 · HANDLER_ERROR 500.
 */
object ActionErrorCodes {
    const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
    const val UNSUPPORTED_ACTION_TYPE = "UNSUPPORTED_ACTION_TYPE"
    const val FORBIDDEN = "FORBIDDEN"
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
}

internal fun failed(code: String, message: String, retryable: Boolean = false, details: Map<String, String> = emptyMap()) =
    ActionResult.Failed(code, retryable, message, details)
