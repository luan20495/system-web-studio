package com.systemwebstudio.wiring

import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.ActionRunStore
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.InMemoryActionRunStore
import com.systemwebstudio.logic.action.RunBegin
import com.systemwebstudio.logic.action.RunKey
import com.systemwebstudio.logic.action.RunStatus
import com.systemwebstudio.logic.action.TestClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** The scheduled recovery of abandoned action runs (V29): lease handling, ambiguity is never turned into a retry, a failing store never stops the schedule. */
class ActionRunRecoveryTests {
    private val clock = TestClock(Fx.now)
    private val store = InMemoryActionRunStore()
    private val key = RunKey(Fx.tenantA, Fx.appA, "create", Fx.user, "k1")

    private fun started(mutating: Boolean = false) = store.begin(key, "fp", clock.instant(), mutating) as RunBegin.Started

    @Test
    fun `a run inside its lease is left alone, an abandoned NON-mutating one becomes a retryable timeout`() {
        val recovery = ActionRunRecovery(store, "PT10M", clock)
        val run = started(mutating = false)
        clock.advance(Duration.ofMinutes(9))
        recovery.tick()
        assertThat(store.find(key)!!.status).isEqualTo(RunStatus.RUNNING)
        clock.advance(Duration.ofMinutes(2))
        recovery.tick()
        val swept = store.find(key)!!
        assertThat(swept.status).isEqualTo(RunStatus.FAILED)
        val failure = swept.result as ActionResult.Failed
        assertThat(failure.code).isEqualTo(ActionErrorCodes.TIMEOUT)
        assertThat(failure.retryable).isTrue()
        assertThat(store.complete(key, run.runId, ActionResult.Ok(Fx.obj()), clock.instant())).isFalse()   // a late result from the dead worker is refused
        assertThat((store.begin(key, "fp", clock.instant()) as RunBegin.Started).attempt).isEqualTo(2)
    }

    @Test
    fun `an abandoned MUTATING run is an unknown outcome - not retryable, replayed as it is, never started again (A-1)`() {
        val recovery = ActionRunRecovery(store, "PT10M", clock)
        val run = started(mutating = true)
        clock.advance(Duration.ofMinutes(11))
        recovery.tick()
        val swept = store.find(key)!!
        assertThat(swept.status).isEqualTo(RunStatus.FAILED)
        assertThat(swept.mutating).isTrue()
        val failure = swept.result as ActionResult.Failed
        assertThat(failure.code).isEqualTo(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
        assertThat(failure.retryable).isFalse()
        assertThat(store.complete(key, run.runId, ActionResult.Ok(Fx.obj()), clock.instant())).isFalse()   // a late result from the dead worker is refused
        assertThat(store.begin(key, "fp", clock.instant(), mutating = true)).isEqualTo(RunBegin.Replay(failure))   // the second caller is told, the handler does not run again
    }

    @Test
    fun `a recorded ambiguous write is never turned into a retryable run`() {
        val recovery = ActionRunRecovery(store, "PT10M", clock)
        val unknown = ActionResult.Failed(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, retryable = false, message = "unknown")
        store.complete(key, started().runId, unknown, clock.instant())
        clock.advance(Duration.ofDays(1))
        recovery.tick(); recovery.tick()
        assertThat(store.begin(key, "fp", clock.instant())).isEqualTo(RunBegin.Replay(unknown))
    }

    @Test
    fun `a lease shorter than a minute is refused at construction`() {
        assertThatThrownBy { ActionRunRecovery(store, "PT30S", clock) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ActionRunRecovery(store, "not-a-duration", clock) }.isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `a store failure is contained and the next tick still runs`() {
        var calls = 0
        val flaky = object : ActionRunStore by store {
            override fun sweepStale(staleBefore: Instant, now: Instant): Int { calls++; if (calls == 1) error("db down"); return store.sweepStale(staleBefore, now) }
        }
        val recovery = ActionRunRecovery(flaky, "PT10M", clock)
        started()
        clock.advance(Duration.ofMinutes(11))
        recovery.tick()                                                             // throws inside, must not escape
        assertThat(store.find(key)!!.status).isEqualTo(RunStatus.RUNNING)
        recovery.tick()
        assertThat(store.find(key)!!.status).isEqualTo(RunStatus.FAILED)
        assertThat(calls).isEqualTo(2)
    }
}
