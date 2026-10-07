package com.systemwebstudio.wiring

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.DefaultDataGateway
import com.systemwebstudio.logic.action.PortOutcome

/**
 * C0 · D-C0-15 / `data-runtime.md` §4b — the ONE mapping from what C3 threw on a WRITE to the C4 [PortOutcome.Failure].
 * Order matters and is the whole point: the two "never retry" codes first, then the two "retry later" codes, then the codes after which C3 is certain
 * nothing was applied; EVERYTHING ELSE is ambiguous and becomes `IDEMPOTENCY_OUTCOME_UNKNOWN` (not retryable): C3 has already moved the key to UNKNOWN,
 * so a retry would only answer 409 and a "not done" assumption could duplicate a business record.
 * Messages are fixed text per code — never the exception message, never a parameter, never a downstream body.
 */
object DataWriteErrors {
    const val OUTCOME_UNKNOWN = FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN
    const val REJECTED = FailureCodes.MUTATION_REJECTED

    private const val MSG_UNKNOWN = "The outcome of the change is unknown. It may have been applied; check the data source before trying again with a new operation."
    private const val MSG_REJECTED = "The data source refused the change. Nothing was applied."
    private const val MSG_RETRY = "The data layer is busy. Try again later with the same operation key."
    private const val MSG_NOT_APPLIED = "The change was not applied."

    /** [t] is whatever the gateway call threw. */
    fun forWrite(t: Throwable): PortOutcome.Failure {
        // not a ConnectorFailure (transport timeout, interrupted, unexpected bug): C3 has already marked the key UNKNOWN -> ambiguous
        val code = (t as? ConnectorFailure)?.code ?: return unknown()
        return when {
            code == FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN -> unknown()
            code == FailureCodes.MUTATION_REJECTED -> PortOutcome.Failure(REJECTED, false, MSG_REJECTED)
            code == FailureCodes.IDEMPOTENCY_IN_PROGRESS || code == FailureCodes.RATE_LIMITED -> PortOutcome.Failure(code, true, MSG_RETRY)
            code == FailureCodes.IDEMPOTENCY_CONFLICT || code in DefaultDataGateway.NOT_EXECUTED -> PortOutcome.Failure(code, false, MSG_NOT_APPLIED)
            else -> unknown()
        }
    }

    private fun unknown() = PortOutcome.Failure(OUTCOME_UNKNOWN, false, MSG_UNKNOWN)

    /** A failure BEFORE anything could be sent (no gateway, unresolved reference, refused actor): certain "not executed", never retryable. */
    fun notExecuted(code: String, message: String): PortOutcome.Failure = PortOutcome.Failure(code, false, message)
}
