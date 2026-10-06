package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.Fx
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Worker ownership of a RUNNING step (H-3): one owner per attempt, a lease that is renewed while the owner works, expiry when it stops, reclaim by the sweeper,
 * and a lease that exists only while a step is RUNNING.
 */
class WorkflowLeaseTests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }

    private val actions = listOf("w1", "w2").map { Fx.write(it) }
    private val one = wf("one", act("s", "w1"))
    private val lease = Duration.ofMinutes(2)                 // the rig's staleAfter

    /** Starts the run and leaves a worker blocked inside the write of its only step (it is alive, working, and holds the lease). */
    private class Busy(val rig: WorkflowRig, val id: UUID, val release: CountDownLatch, val worker: java.util.concurrent.Future<*>) {
        fun finish() { release.countDown(); worker.get(10, TimeUnit.SECONDS) }
    }

    private fun busyWorker(): Busy {
        val r = WorkflowRig(listOf(one), actions, executor)
        val id = r.startOk("one")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val block = AtomicBoolean(true)
        r.data.onWrite = { if (block.compareAndSet(true, false)) { entered.countDown(); release.await(15, TimeUnit.SECONDS) } }
        val worker = executor.submit { r.worker.runOnce() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        return Busy(r, id, release, worker)
    }

    private fun intruder(r: WorkflowRig) = WorkflowEngine(Fx.json, r.defs, r.actionRig.runtime, r.runStore, r.queue, r.access, r.tenants, r.audit, r.approvals, WorkflowLimits(), r.clock, workerId = "intruder")

    @Test fun `claiming a step records its owner and a lease, and finishing the step releases both`() {
        val b = busyWorker()
        val held = b.rig.stored(b.id)
        assertNotNull(held.leaseOwner)
        assertTrue(held.leaseOwner!!.startsWith("node-"))
        assertEquals(b.rig.now.plus(lease), held.leaseUntil)
        assertEquals(StepStatus.RUNNING, held.currentStep!!.status)
        b.finish()
        val done = b.rig.stored(b.id)
        assertEquals(WorkflowRunStatus.SUCCEEDED, done.status)
        assertNull(done.leaseOwner); assertNull(done.leaseUntil)
    }

    @Test fun `only the owner renews, and a renewal moves the lease forward`() {
        val b = busyWorker()
        val tenant = Fx.tenantA
        b.rig.advance(Duration.ofSeconds(90))
        assertFalse(intruder(b.rig).renewLease(tenant, b.id, "s", 1), "another worker cannot extend a lease it does not hold")
        assertTrue(b.rig.engine.renewLease(tenant, b.id, "s", 1))
        assertEquals(b.rig.now.plus(lease), b.rig.stored(b.id).leaseUntil)
        assertFalse(b.rig.engine.renewLease(tenant, b.id, "s", 2), "a renewal names the attempt it belongs to")
        assertFalse(b.rig.engine.renewLease(tenant, b.id, "other", 1))
        assertFalse(b.rig.engine.renewLease(tenant, UUID.randomUUID(), "s", 1))
        b.finish()
        assertFalse(b.rig.engine.renewLease(tenant, b.id, "s", 1), "nothing to renew after the step finished")
    }

    @Test fun `an owner that keeps renewing is not reclaimed, one that stops is`() {
        val b = busyWorker()
        val r = b.rig
        r.advance(Duration.ofSeconds(90));  assertTrue(r.engine.renewLease(Fx.tenantA, b.id, "s", 1))
        r.advance(Duration.ofSeconds(90));  assertTrue(r.engine.renewLease(Fx.tenantA, b.id, "s", 1))
        r.advance(Duration.ofSeconds(90))                                                         // 4.5 minutes in, far past the first lease
        assertEquals(0, r.engine.sweep().republished, "alive: the lease was renewed")
        assertEquals(StepStatus.RUNNING, r.stored(b.id).currentStep!!.status)
        r.advance(Duration.ofMinutes(3))                                                          // the owner goes silent
        assertEquals(1, r.engine.sweep().republished, "the lease ran out")
        val reclaimed = r.stored(b.id)
        assertEquals(StepStatus.RETRY_WAIT, reclaimed.currentStep!!.status)
        assertEquals(1, reclaimed.currentStep!!.attempt, "attempt kept; the next claimer makes it attempt 2 with the same idempotency key")
        assertNull(reclaimed.leaseOwner); assertNull(reclaimed.leaseUntil)
        assertFalse(r.engine.renewLease(Fx.tenantA, b.id, "s", 1), "the old owner has lost the step")
        b.finish()
    }

    @Test fun `a lease is judged by its own expiry, not by how long ago the run last changed`() {
        val b = busyWorker()
        val r = b.rig
        r.advance(Duration.ofSeconds(100))                                // updatedAt is 100 s old, the lease has 20 s left
        assertEquals(0, r.engine.sweep().republished)
        r.advance(Duration.ofSeconds(30))                                 // lease over
        assertEquals(1, r.engine.sweep().republished)
        b.finish()
    }

    @Test fun `cancelling a run releases the lease`() {
        val b = busyWorker()
        assertNotNull(b.rig.stored(b.id).leaseOwner)
        b.rig.engine.cancel(b.rig.ctx(), b.id)
        val cancelled = b.rig.stored(b.id)
        assertEquals(WorkflowRunStatus.CANCELLED, cancelled.status)
        assertNull(cancelled.leaseOwner); assertNull(cancelled.leaseUntil)
        b.finish()
    }

    @Test fun `a run without a lease keeps the old rule - untouched for the stale time means abandoned`() {
        val r = WorkflowRig(listOf(one), actions, executor)
        val id = r.startOk("one")
        val before = r.stored(id)
        r.queue.poll()                                                                // the job is lost
        assertTrue(r.baseStore.compareAndSet(before, before.withStep(before.steps["s"]!!.copy(status = StepStatus.RUNNING, attempt = 1)).copy(status = WorkflowRunStatus.RUNNING)))
        assertNull(r.stored(id).leaseUntil)
        r.advance(Duration.ofMinutes(1)); assertEquals(0, r.engine.sweep().republished)
        r.advance(Duration.ofMinutes(2)); assertEquals(1, r.engine.sweep().republished)
    }

    @Test fun `a lease longer than the stale time is honoured, and one that already ran out is reclaimed at once`() {
        val r = WorkflowRig(listOf(one), actions, executor)
        val id = r.startOk("one")
        val before = r.stored(id)
        r.queue.poll()                                                                // the job is lost
        val running = before.withStep(before.steps["s"]!!.copy(status = StepStatus.RUNNING, attempt = 1)).copy(status = WorkflowRunStatus.RUNNING, leaseOwner = "node-x", leaseUntil = r.now.plus(Duration.ofMinutes(10)))
        assertTrue(r.baseStore.compareAndSet(before, running))
        r.advance(Duration.ofMinutes(5))                                              // updatedAt is older than staleAfter, the lease is not over
        assertEquals(0, r.engine.sweep().republished, "the lease decides, not updatedAt")
        r.advance(Duration.ofMinutes(6))
        assertEquals(1, r.engine.sweep().republished)
        assertNull(r.stored(id).leaseOwner)
    }

    @Test fun `a lease that ran out is reclaimed even though the run was touched recently`() {
        val r = WorkflowRig(listOf(one), actions, executor)
        val id = r.startOk("one")
        val before = r.stored(id)
        r.queue.poll()
        val running = before.withStep(before.steps["s"]!!.copy(status = StepStatus.RUNNING, attempt = 1)).copy(status = WorkflowRunStatus.RUNNING, leaseOwner = "node-x", leaseUntil = r.now.minusSeconds(1))
        assertTrue(r.baseStore.compareAndSet(before, running))
        assertEquals(1, r.engine.sweep().republished, "updatedAt is fresh but the lease is over")
        assertEquals(StepStatus.RETRY_WAIT, r.stored(id).currentStep!!.status)
    }

    @Test fun `two workers racing for one step - exactly one owns it`() {
        val r = WorkflowRig(listOf(one), actions, executor)
        val id = r.startOk("one")
        val job = WorkflowJob.forStep(Fx.tenantA, id, "s")
        val other = intruder(r)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val block = AtomicBoolean(true)
        r.data.onWrite = { if (block.compareAndSet(true, false)) { entered.countDown(); release.await(15, TimeUnit.SECONDS) } }
        val first = executor.submit<ProcessOutcome> { r.engine.process(job) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val owner = r.stored(id).leaseOwner
        assertEquals(ProcessOutcome.DONE, other.process(job), "the second worker finds the step taken and does nothing")
        assertEquals(owner, r.stored(id).leaseOwner)
        assertEquals(1, r.writes("w1"))
        release.countDown(); first.get(10, TimeUnit.SECONDS)
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
    }
}
