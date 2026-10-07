package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.PortOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * How the worker uses the broker (C4 queue discipline). The broker is only a nudge: delivery retry is never the workflow's retry, nothing is requeued
 * in a hurry, and a message is acknowledged only once what it caused is saved. These tests wrap the queue and watch every acknowledgement.
 */
class WorkflowQueueDisciplineTests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }

    private val actions = listOf("w1", "w2", "w3", "c1", "c2", "wErr").map { Fx.write(it) }
    private val two = wf("two", act("a", "w1"), act("b", "w2"))

    private fun rig(vararg w: WorkflowDefinition = arrayOf(two), maxProcessFailures: Int = 3) =
        WorkflowRig(w.toList(), actions, executor, maxProcessFailures = maxProcessFailures)

    /** Delegates to the rig's queue and records what the worker does with each message. */
    private class SpyQueue(private val d: InMemoryWorkflowQueue) : WorkflowQueue by d {
        data class Settle(val lease: QueueLease, val kind: String)
        val settled = CopyOnWriteArrayList<Settle>()
        /** Runs just before an acknowledgement: what is the stored state at that moment? */
        @Volatile var beforeAck: ((QueueLease) -> Unit)? = null
        @Volatile var failNextAck = false
        override fun ack(lease: QueueLease) {
            beforeAck?.invoke(lease)
            if (failNextAck) { failNextAck = false; throw IllegalStateException("channel closed") }
            settled += Settle(lease, "ack"); d.ack(lease)
        }
        override fun nack(lease: QueueLease, requeue: Boolean) { settled += Settle(lease, if (requeue) "nack-requeue" else "nack-dead"); d.nack(lease, requeue) }
        val requeues get() = settled.count { it.kind == "nack-requeue" }
        val deadLetters get() = settled.count { it.kind == "nack-dead" }
    }

    private fun spied(r: WorkflowRig) = SpyQueue(r.queue).let { it to WorkflowWorker(r.engine, it) }

    @Test fun `the worker never asks the broker to requeue, on every path`() {
        val r = rig(maxProcessFailures = 3)
        val (spy, worker) = spied(r)
        val healthy = r.startOk("two", key = "h")
        worker.runUntilIdle()                                         // normal path
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(healthy).status)

        val outage = r.startOk("two", key = "o")
        r.runStore.failGet = true; worker.runUntilIdle(); r.runStore.failGet = false      // outage path
        r.advance(Duration.ofMinutes(3)); r.engine.sweep(); worker.runUntilIdle()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(outage).status)

        r.queue.publishRaw("garbage"); worker.runUntilIdle()           // malformed path

        val poison = r.startOk("two", key = "p")                       // poison path, up to its dead letter
        r.runStore.poison += poison
        repeat(5) { worker.runUntilIdle(); r.advance(Duration.ofMinutes(11)); r.engine.sweep() }
        worker.runUntilIdle()
        assertEquals(WorkflowRunStatus.FAILED, r.view(poison).status)

        assertEquals(0, spy.requeues, "nack(requeue=true) is never used to retry anything")
        assertEquals(2, spy.deadLetters, "exactly the malformed message and the poison run's last message are rejected")
        assertTrue(spy.settled.all { it.lease.deliveryCount == 1 }, "no message was ever delivered twice: retries are the sweeper's, not the broker's")
    }

    @Test fun `a message is acknowledged only after what it caused is saved`() {
        val r = rig()
        val (spy, worker) = spied(r)
        val id = r.startOk("two")
        val seen = mutableListOf<Pair<String, String>>()
        spy.beforeAck = { lease ->
            val job = WorkflowJob.decode(lease.body)!!
            val run = r.stored(id)
            seen += job.stepId to "${run.status}/${run.currentStepId}/${run.steps[job.stepId]?.status}"
        }
        worker.runUntilIdle()
        assertEquals(
            listOf("a" to "PENDING/b/SUCCEEDED", "b" to "SUCCEEDED/null/SUCCEEDED"), seen,
            "at ack time the step's outcome and the move to the next step (or the end of the run) are already stored"
        )
    }

    @Test fun `a worker that dies between saving and acknowledging only causes a harmless redelivery`() {
        val r = rig()
        val (spy, worker) = spied(r)
        val id = r.startOk("two")
        spy.failNextAck = true
        assertThrows(IllegalStateException::class.java) { worker.runOnce() }       // step a is saved, the ack never reached the broker
        assertEquals(StepStatus.SUCCEEDED, r.step(id, "a")!!.status)
        assertEquals(1, r.writes("w1"))
        r.queue.requeueInFlight()                                                  // the broker gives the unacknowledged message back
        worker.runUntilIdle()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertEquals(1, r.writes("w1"), "the redelivered message of an already saved step repeats nothing")
        assertEquals(1, r.writes("w2"))
    }

    @Test fun `after a crash in the middle of a write that the data layer reports as unknown, the step is not retried, not routed and not compensated`() {
        val saga = wf(
            "crashU",
            act("s1", "w1", comp = "c1"),
            act("s2", "w2", next = "z", onError = "h", retry = RetryPolicy(maxAttempts = 5), comp = "c2"),
            act("h", "wErr", next = "z"),
            WorkflowStep("z", StepKind.END)
        )
        val r = rig(saga)
        val id = r.startOk("crashU")
        r.worker.runOnce()                                                         // s1 done, the job of s2 is queued
        val before = r.stored(id)
        assertEquals("s2", before.currentStepId)
        assertNotNull(r.queue.poll())                                              // the worker took the job of s2 ... and died after marking it RUNNING
        assertTrue(r.baseStore.compareAndSet(before, before.withStep(before.steps["s2"]!!.copy(status = StepStatus.RUNNING, attempt = 1)).copy(status = WorkflowRunStatus.RUNNING)))
        // the re-drive carries the same idempotency key; the data layer cannot tell whether the first write landed
        r.data.script("w2", PortOutcome.Failure(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, false))
        r.advance(Duration.ofMinutes(3))
        assertEquals(1, r.engine.sweep().republished)
        r.drain()
        val run = r.view(id)
        assertEquals(WorkflowRunStatus.FAILED, run.status)
        assertEquals(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, run.errorCode)
        assertEquals(2, r.step(id, "s2")!!.attempt, "one re-drive of the same key, and no more although 5 attempts were allowed")
        assertEquals(listOf("w1", "w2", "c1"), r.writeOrder, "the finished step is compensated; the ambiguous one is not, and onError was not entered")
        r.advance(Duration.ofHours(1)); r.engine.sweep(); r.drain()
        assertEquals(listOf("w1", "w2", "c1"), r.writeOrder, "nothing more ever happens to it")
    }
}
