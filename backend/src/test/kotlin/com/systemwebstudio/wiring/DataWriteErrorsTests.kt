package com.systemwebstudio.wiring

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.DefaultDataGateway
import com.systemwebstudio.logic.action.PortOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** D-C0-15 / data-runtime.md §4b: the C3 -> C4 mapping of a failed WRITE. */
class DataWriteErrorsTests {
    private fun map(code: String) = DataWriteErrors.forWrite(ConnectorFailure(code, "downstream text that must never be copied"))

    private fun assertFailure(f: PortOutcome.Failure, code: String, retryable: Boolean) {
        assertThat(f.code).isEqualTo(code)
        assertThat(f.retryable).isEqualTo(retryable)
        assertThat(f.message).doesNotContain("downstream text")
    }

    @Test
    fun `an unknown outcome stays unknown and is never retryable`() = assertFailure(map(FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN), "IDEMPOTENCY_OUTCOME_UNKNOWN", false)

    @Test
    fun `a rejected mutation stays rejected and is never retryable`() = assertFailure(map(FailureCodes.MUTATION_REJECTED), "MUTATION_REJECTED", false)

    @Test
    fun `in progress and rate limited are retryable with the same code`() {
        assertFailure(map(FailureCodes.IDEMPOTENCY_IN_PROGRESS), "IDEMPOTENCY_IN_PROGRESS", true)
        assertFailure(map(FailureCodes.RATE_LIMITED), "RATE_LIMITED", true)
    }

    @Test
    fun `a key conflict is definite and not retryable`() = assertFailure(map(FailureCodes.IDEMPOTENCY_CONFLICT), "IDEMPOTENCY_CONFLICT", false)

    @Test
    fun `every code C3 guarantees nothing was applied for keeps its code and is not retryable`() {
        for (code in DefaultDataGateway.NOT_EXECUTED - setOf(FailureCodes.RATE_LIMITED, FailureCodes.MUTATION_REJECTED)) {
            assertFailure(map(code), code, false)
        }
    }

    @Test
    fun `an ambiguous connector failure becomes an unknown outcome`() {
        val ambiguous = listOf(
            FailureCodes.TIMEOUT, FailureCodes.CONNECT_FAILED, FailureCodes.UPSTREAM_STATUS, FailureCodes.QUERY_FAILED, FailureCodes.RESPONSE_INVALID,
            FailureCodes.RESPONSE_TOO_LARGE, FailureCodes.RESPONSE_NOT_JSON, FailureCodes.INTERNAL, FailureCodes.SECRETS_UNAVAILABLE, "SOMETHING_NEW"
        )
        for (code in ambiguous) {
            assertThat(code in DefaultDataGateway.NOT_EXECUTED).describedAs(code).isFalse()
            assertFailure(map(code), "IDEMPOTENCY_OUTCOME_UNKNOWN", false)
        }
    }

    @Test
    fun `a transport style exception on a write is an unknown outcome`() {
        for (t in listOf<Throwable>(java.net.SocketTimeoutException("read timed out"), java.io.IOException("connection reset"), RuntimeException("boom"), InterruptedException())) {
            assertFailure(DataWriteErrors.forWrite(t), "IDEMPOTENCY_OUTCOME_UNKNOWN", false)
        }
    }

    @Test
    fun `no failure of a write is ever a bare generic code`() {
        val codes = (DefaultDataGateway.NOT_EXECUTED + listOf(FailureCodes.TIMEOUT, FailureCodes.INTERNAL, "X")).map { map(it).code }.toSet()
        assertThat(codes).doesNotContain("INTERNAL", "TIMEOUT", "X", "HANDLER_ERROR")
    }
}
