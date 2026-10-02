package com.systemwebstudio.identity

import com.systemwebstudio.common.ApiException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PasswordHashGateTests {
    @Test
    fun `at most N verifications run at once and the overflow gets 503 with Retry-After`() {
        val gate = PasswordHashGate(permits = 2, waitMs = 150)
        val release = CountDownLatch(1); val started = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        repeat(2) { pool.submit { gate.run { started.countDown(); release.await(5, TimeUnit.SECONDS) } } }
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue()
        assertThatThrownBy { gate.run { "never" } }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.status.value()).isEqualTo(503); assertThat(it.code).isEqualTo("LOGIN_BUSY"); assertThat(it.headers["Retry-After"]).isEqualTo("2")
        }
        release.countDown(); pool.shutdown()
        assertThat(gate.run { "ok" }).isEqualTo("ok")                                    // permits are returned
    }

    @Test
    fun `a failing verification still releases its permit`() {
        val gate = PasswordHashGate(permits = 1, waitMs = 100)
        repeat(3) { assertThatThrownBy { gate.run { error("boom") } }.hasMessage("boom") }
        assertThat(gate.run { 1 }).isEqualTo(1)
    }
}
