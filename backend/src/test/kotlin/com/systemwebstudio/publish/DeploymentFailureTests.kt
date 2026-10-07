package com.systemwebstudio.publish

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/** A failure keeps its real reason, is classified as transient or permanent, and never leaks addresses or credentials. Pure: no Spring, no database. */
class DeploymentFailureTests {
    @Test
    fun `a permanent build failure keeps its message and is not retried`() {
        val f = FailureClassifier.classify("BUILDING", BuildFailure("Rendered page contains a script"))
        assertThat(f.code).isEqualTo(FailureCode.BUILD_FAILED)
        assertThat(f.transient).isFalse()
        assertThat(f.reason()).isEqualTo("[BUILD_FAILED] Rendered page contains a script")
    }

    @Test
    fun `a render worker that is down or answers 5xx is transient, a 401 is not`() {
        val down = FailureClassifier.classify("BUILDING", BuildFailure("Render worker answered HTTP 503", transient = true))
        assertThat(down.code).isEqualTo(FailureCode.RENDER_UNAVAILABLE); assertThat(down.transient).isTrue()
        val auth = FailureClassifier.classify("BUILDING", BuildFailure("Render worker answered HTTP 401", transient = false))
        assertThat(auth.code).isEqualTo(FailureCode.BUILD_FAILED); assertThat(auth.transient).isFalse()
        assertThat(auth.reason()).contains("Render worker answered HTTP 401")
    }

    @Test
    fun `a timeout while building is transient but a timeout while deploying is ambiguous and never retried`() {
        val build = FailureClassifier.classify("BUILDING", TimeoutException())
        assertThat(build.code).isEqualTo(FailureCode.STEP_TIMEOUT); assertThat(build.transient).isTrue(); assertThat(build.ambiguous).isFalse()
        val deploy = FailureClassifier.classify("DEPLOYING", RuntimeException("wrapped", SocketTimeoutException("read timed out")))
        assertThat(deploy.code).isEqualTo(FailureCode.DEPLOY_TIMEOUT); assertThat(deploy.ambiguous).isTrue(); assertThat(deploy.transient).isFalse()
        assertThat(RetryPolicy(3, 0).canRetry(deploy, 1)).isFalse()
    }

    @Test
    fun `an IO error is transient and says which step it hit`() {
        val f = FailureClassifier.classify("BUILDING", IOException("connection reset"))
        assertThat(f.code).isEqualTo(FailureCode.ARTIFACT_STORE_UNAVAILABLE); assertThat(f.transient).isTrue()
        assertThat(f.message).contains("connection reset")
    }

    @Test
    fun `an unknown exception is permanent but its type and message are kept, not replaced by a generic text`() {
        val f = FailureClassifier.classify("DEPLOYING", IllegalStateException("provider exploded"))
        assertThat(f.code).isEqualTo(FailureCode.INTERNAL_ERROR); assertThat(f.transient).isFalse()
        assertThat(f.reason()).contains("IllegalStateException").contains("provider exploded").contains("DEPLOYING")
    }

    @Test
    fun `addresses and credentials are removed from what is stored and shown`() {
        val f = FailureClassifier.classify("BUILDING", RuntimeException("PUT https://minio.internal:9000/bucket/key failed, secret=abc123 token: xyz"))
        assertThat(f.message).doesNotContain("minio.internal").doesNotContain("abc123").doesNotContain("xyz")
        assertThat(f.message).contains("<url>").contains("<redacted>")
    }

    @Test
    fun `the stored reason always fits the 1000 character column and carries the attempt count`() {
        val long = StepFailure(FailureCode.INTERNAL_ERROR, "x".repeat(5000))
        assertThat(long.reason(3).length).isLessThanOrEqualTo(StepFailure.MAX_LENGTH)
        assertThat(StepFailure(FailureCode.RENDER_UNAVAILABLE, "down", transient = true).reason(3)).isEqualTo("[RENDER_UNAVAILABLE] down (after 3 attempts)")
    }

    @Test
    fun `the code can be read back from a stored error, old unprefixed errors have none`() {
        assertThat(StepFailure.codeOf("[DEPLOY_STATE_UNKNOWN] could not confirm")).isEqualTo(FailureCode.DEPLOY_STATE_UNKNOWN)
        assertThat(StepFailure.codeOf("Mock provider simulated a deployment failure")).isNull()
        assertThat(StepFailure.codeOf("[NOT_A_CODE] x")).isNull()
        assertThat(StepFailure.codeOf(null)).isNull()
    }

    @Test
    fun `retry policy retries only transient failures, up to the limit, with growing backoff`() {
        val p = RetryPolicy(maxAttempts = 3, backoffMs = 100)
        val transient = StepFailure(FailureCode.RENDER_UNAVAILABLE, "down", transient = true)
        assertThat(p.canRetry(transient, 1)).isTrue(); assertThat(p.canRetry(transient, 2)).isTrue(); assertThat(p.canRetry(transient, 3)).isFalse()
        assertThat(p.canRetry(StepFailure(FailureCode.BUILD_FAILED, "bad"), 1)).isFalse()
        assertThat(p.delayBefore(2)).isEqualTo(100); assertThat(p.delayBefore(3)).isEqualTo(200)
        assertThat(RetryPolicy(1, 100).canRetry(transient, 1)).isFalse()
        assertThatThrownBy { RetryPolicy(0, 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a step that exceeds its deadline is cut off, and the step's own exceptions come through unwrapped`() {
        val runner = StepRunner()
        assertThatThrownBy { runner.bounded(50) { Thread.sleep(5_000) } }.isInstanceOf(TimeoutException::class.java)
        assertThatThrownBy { runner.bounded(1_000) { throw BuildFailure("boom", transient = true) } }.isInstanceOf(BuildFailure::class.java).hasMessage("boom")
        assertThat(runner.bounded(1_000) { 42 }).isEqualTo(42)
        assertThat(runner.bounded(0) { "no deadline" }).isEqualTo("no deadline")
    }
}
