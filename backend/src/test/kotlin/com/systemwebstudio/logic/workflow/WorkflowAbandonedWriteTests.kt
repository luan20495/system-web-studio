package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.Fx
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A worker dies in the middle of a write; the action sweeper and the workflow sweeper both run (A-1). The outcome is unknown, once, for good. */
class WorkflowAbandonedWriteTests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }

    private val actions = listOf("w1", "w2", "c1", "c2", "wErr").map { Fx.write(it) }

    @Test fun `a write whose worker died is unknown for good - no retry, no onError, not compensated, earlier steps compensated`() {
        val saga = wf(
            "abandon",
            act("s1", "w1", comp = "c1"),
            act("s2", "w2", next = "z", onError = "h", retry = RetryPolicy(maxAttempts = 5), comp = "c2"),
            act("h", "wErr", next = "z"),
            WorkflowStep("z", StepKind.END)
        )
        val r = WorkflowRig(listOf(saga), actions, executor)
        val id = r.startOk("abandon")
        r.worker.runOnce()                                                                 // s1 done, s2 queued
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val block = AtomicBoolean(true)
        r.data.onWrite = { if (block.compareAndSet(true, false)) { entered.countDown(); release.await(10, TimeUnit.SECONDS) } }
        val dying = executor.submit { r.worker.runOnce() }                                 // the node that dies while s2's write is on its way
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        r.advance(Duration.ofMinutes(15))
        assertEquals(1, r.actionRig.runs.sweepStale(r.now.minus(Duration.ofMinutes(10)), r.now), "the action sweeper gives up on the abandoned write")
        assertEquals(1, r.engine.sweep().republished, "and the workflow sweeper puts the step back")
        r.worker.runOnce()                                                                 // the re-drive of the same step
        val run = r.view(id)
        assertEquals(WorkflowRunStatus.FAILED, run.status)
        assertEquals(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, run.errorCode)
        release.countDown(); dying.get(10, TimeUnit.SECONDS)
        r.advance(Duration.ofHours(1)); r.engine.sweep(); r.drain()
        assertEquals(listOf("w1", "w2", "c1"), r.writeOrder, "w2 was sent once; it was not sent again, onError was not entered, c2 was not run, c1 was")
        assertEquals(WorkflowRunStatus.FAILED, r.view(id).status)
    }
}
