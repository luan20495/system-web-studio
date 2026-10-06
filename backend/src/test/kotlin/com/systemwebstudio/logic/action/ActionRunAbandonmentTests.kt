package com.systemwebstudio.logic.action

import com.systemwebstudio.logic.action.Fx.str
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A run left RUNNING by a dead worker (A-1). Whether it may be tried again depends on one thing: could it already have changed something?
 * A mutating action: unknown outcome, never retryable (replayed as is, no `onError`, no workflow retry). A non-mutating one: a retryable timeout.
 */
class ActionRunAbandonmentTests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }

    private val store = InMemoryActionRunStore()
    private val key = RunKey(Fx.tenantA, Fx.appA, "a1", Fx.user, "k1")
    private val later = Fx.now.plus(Duration.ofHours(1))

    private fun sweep() = store.sweepStale(Fx.now.plus(Duration.ofMinutes(30)), later)

    @Test fun `an abandoned mutating run becomes an unknown outcome that is never retryable and is replayed as is`() {
        val s = store.begin(key, "f", Fx.now, mutating = true) as RunBegin.Started
        assertEquals(1, sweep())
        val record = store.find(key)!!
        assertEquals(RunStatus.FAILED, record.status)
        val result = assertInstanceOf(ActionResult.Failed::class.java, record.result)
        assertEquals(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, result.code)
        assertFalse(result.retryable)
        assertEquals("ABANDONED", result.details["cause"])
        val again = assertInstanceOf(RunBegin.Replay::class.java, store.begin(key, "f", later, mutating = true))
        assertEquals(result, again.result)
        assertFalse(store.complete(key, s.runId, ActionResult.Ok(str("late")), later), "the dead worker's late result is refused")
        assertEquals(0, sweep(), "sweeping again changes nothing")
    }

    @Test fun `an abandoned non-mutating run is a retryable timeout and starts again with a new attempt`() {
        store.begin(key, "f", Fx.now, mutating = false)
        assertEquals(1, sweep())
        val result = assertInstanceOf(ActionResult.Failed::class.java, store.find(key)!!.result)
        assertEquals(ActionErrorCodes.TIMEOUT, result.code)
        assertTrue(result.retryable)
        assertEquals(2, (store.begin(key, "f", later, mutating = false) as RunBegin.Started).attempt)
    }

    @Test fun `a caller that does not say is assumed to mutate - the safe side`() {
        store.begin(key, "f", Fx.now)
        assertTrue(store.find(key)!!.mutating)
        sweep()
        assertEquals(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, (store.find(key)!!.result as ActionResult.Failed).code)
    }

    @Test fun `mutating and non-mutating runs in one sweep each get their own outcome, and a recorded unknown outcome is left alone`() {
        val other = key.copy(actionId = "read")
        val done = key.copy(actionId = "done")
        store.begin(key, "f", Fx.now, mutating = true)
        store.begin(other, "f", Fx.now, mutating = false)
        val d = store.begin(done, "f", Fx.now, mutating = true) as RunBegin.Started
        val unknown = ActionResult.Failed(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, false, "recorded by the data layer")
        store.complete(done, d.runId, unknown, Fx.now)
        assertEquals(2, sweep())
        assertEquals(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, (store.find(key)!!.result as ActionResult.Failed).code)
        assertEquals(ActionErrorCodes.TIMEOUT, (store.find(other)!!.result as ActionResult.Failed).code)
        assertEquals(unknown, store.find(done)!!.result)
    }

    @Test fun `a run that is restarted after a retryable failure takes the mutating flag of the new call`() {
        val s = store.begin(key, "f", Fx.now, mutating = false) as RunBegin.Started
        store.complete(key, s.runId, ActionResult.Failed("DEPENDENCY_UNAVAILABLE", true, "x"), Fx.now)
        store.begin(key, "f", Fx.now, mutating = true)
        assertTrue(store.find(key)!!.mutating)
    }

    // ---- through the runtime: a worker that dies in the middle of a write --------------------------------------------------

    private fun createReq(k: String) = ActionRequest("m1", mapOf("title" to str("t")), idempotencyKey = k)

    @Test fun `the runtime records the mutating flag and an abandoned write is never run again nor answered as a retryable timeout`() {
        val r = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), executor = executor)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        r.data.onWrite = { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
        val firstCall = executor.submit<ActionResult> { r.runtime.execute(Fx.ctx(), createReq("dies")) }    // the node that is about to be lost
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val running = r.runs.sweepStale(Fx.now.plus(Duration.ofHours(1)), Fx.now.plus(Duration.ofHours(2)))
        assertEquals(1, running, "the run was RUNNING and mutating; the sweeper turns it into an unknown outcome")
        r.data.onWrite = null
        val retry = assertInstanceOf(ActionResult.Failed::class.java, r.runtime.execute(Fx.ctx(), createReq("dies")))
        assertEquals(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, retry.code)
        assertFalse(retry.retryable)
        assertEquals(1, r.data.writes.size, "the write was not sent a second time")
        release.countDown(); firstCall.get(10, TimeUnit.SECONDS)
        assertEquals(1, r.data.writes.size)
    }
}
