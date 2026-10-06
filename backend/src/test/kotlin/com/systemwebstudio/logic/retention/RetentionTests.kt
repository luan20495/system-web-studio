package com.systemwebstudio.logic.retention

import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.InMemoryActionRunStore
import com.systemwebstudio.logic.action.PrincipalSpec
import com.systemwebstudio.logic.action.RunBegin
import com.systemwebstudio.logic.action.RunKey
import com.systemwebstudio.logic.action.TestClock
import com.systemwebstudio.logic.approval.Approval
import com.systemwebstudio.logic.approval.ApprovalSource
import com.systemwebstudio.logic.approval.ApprovalStatus
import com.systemwebstudio.logic.approval.InMemoryApprovalStore
import com.systemwebstudio.logic.scheduler.ExecutionStatus
import com.systemwebstudio.logic.scheduler.InMemoryScheduleStore
import com.systemwebstudio.logic.scheduler.ScheduleExecution
import com.systemwebstudio.logic.workflow.CompensationState
import com.systemwebstudio.logic.workflow.WorkflowRig
import com.systemwebstudio.logic.workflow.WorkflowRunStatus
import com.systemwebstudio.logic.workflow.act
import com.systemwebstudio.logic.workflow.WorkflowStep
import com.systemwebstudio.logic.workflow.StepKind
import com.systemwebstudio.logic.workflow.wf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/** Retention: finished rows age out in bounded batches; active / in-flight rows survive however old they are. */
class RetentionTests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }

    private val day = Duration.ofDays(1)
    private val policy = RetentionPolicy()      // actions 30d, payloads 14d, workflow runs 90d, approvals 180d, ledger 30d

    // ---- policy --------------------------------------------------------------------------------------------------------------------

    @Test fun `a horizon shorter than the replay window is refused`() {
        assertThrows(IllegalArgumentException::class.java) { RetentionPolicy(actionRuns = Duration.ofHours(1)) }
        assertThrows(IllegalArgumentException::class.java) { RetentionPolicy(workflowRuns = Duration.ofDays(3)) }
        assertThrows(IllegalArgumentException::class.java) { RetentionPolicy(workflowRunPayloads = Duration.ofDays(100), workflowRuns = Duration.ofDays(90)) }
        assertThrows(IllegalArgumentException::class.java) { RetentionPolicy(batchSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { RetentionPolicy(batchSize = 100_000) }
    }

    // ---- action_runs -----------------------------------------------------------------------------------------------------------------

    private fun key(i: Int, tenant: UUID = Fx.tenantA) = RunKey(tenant, Fx.appA, "act", Fx.user, "k$i")

    @Test fun `action runs - finished rows age out, a RUNNING row never does`() {
        val store = InMemoryActionRunStore(); val t0 = Instant.parse("2026-01-01T00:00:00Z")
        fun finish(i: Int, ok: Boolean, at: Instant) {
            val b = store.begin(key(i), "fp", at) as RunBegin.Started
            store.complete(key(i), b.runId, if (ok) ActionResult.Ok(Fx.obj()) else ActionResult.Failed("X", false), at)
        }
        finish(1, true, t0); finish(2, false, t0)                            // old, finished
        store.begin(key(3), "fp", t0)                                        // old, still RUNNING
        finish(4, true, t0.plus(day.multipliedBy(100)))                      // recent
        val clock = TestClock(t0.plus(day.multipliedBy(101)))
        val svc = RetentionService(listOf(RetentionTargets.actionRuns(store, policy)), policy, clock)
        val rep = svc.runOnce()
        assertEquals(2, rep.purged)
        assertNull(store.find(key(1))); assertNull(store.find(key(2)))
        assertTrue(store.find(key(3)) != null, "an in-flight run is never deleted")
        assertNotNull(store.find(key(4)))
        assertEquals(0, svc.runOnce().purged)
    }

    @Test fun `an abandoned RUNNING run becomes eligible only after the sweeper failed it and it aged again`() {
        val store = InMemoryActionRunStore(); val t0 = Instant.parse("2026-01-01T00:00:00Z")
        store.begin(key(1), "fp", t0)
        val clock = TestClock(t0.plus(day.multipliedBy(200)))
        val svc = RetentionService(listOf(RetentionTargets.actionRuns(store, policy)), policy, clock)
        assertEquals(0, svc.runOnce().purged)
        assertEquals(1, store.sweepStale(clock.instant().minus(Duration.ofMinutes(10)), clock.instant()))
        assertEquals(0, svc.runOnce().purged, "just failed: it starts aging now")
        clock.advance(day.multipliedBy(31))
        assertEquals(1, svc.runOnce().purged)
    }

    // ---- workflow_runs -----------------------------------------------------------------------------------------------------------------

    private val actions = listOf("w1", "w2").map { Fx.write(it) }
    private fun wfRig(clock: TestClock) = WorkflowRig(
        listOf(wf("two", act("a", "w1"), act("b", "w2")), wf("pausing", act("a", "w1"), WorkflowStep("pause", StepKind.WAIT, wait = Duration.ofHours(2)), act("b", "w2"))),
        actions, executor, clock = clock
    )

    @Test fun `workflow runs - payloads are blanked first and rows deleted later, active runs are never touched`() {
        val clock = TestClock(Instant.parse("2026-01-01T00:00:00Z")); val r = wfRig(clock)
        val done = r.startOk("two", key = "done"); r.drain()
        val waiting = r.startOk("pausing", key = "wait"); r.drain()
        r.queue.failPublish = true
        val pending = r.startOk("two", key = "pending")                                   // PENDING: never published
        r.queue.failPublish = false
        val running = r.startOk("two", key = "run"); r.drain()
        r.baseStore.compareAndSet(r.stored(running), r.stored(running).copy(status = WorkflowRunStatus.RUNNING, finishedAt = null))
        val compensating = r.startOk("two", key = "comp"); r.drain()
        r.baseStore.compareAndSet(r.stored(compensating), r.stored(compensating).copy(status = WorkflowRunStatus.FAILED, compensation = CompensationState.IN_PROGRESS))

        val json = Fx.json
        val placeholder = json.createObjectNode().put("redacted", true)
        val svc = RetentionService(listOf(RetentionTargets.workflowRuns(r.baseStore, policy, placeholder, clock)), policy, clock)
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(done).status)
        assertEquals(WorkflowRunStatus.WAITING, r.view(waiting).status)

        // 15 days: stage 1 only
        clock.advance(day.multipliedBy(15))
        var rep = svc.runOnce()
        assertEquals(1, rep.redacted); assertEquals(0, rep.purged)       // only `done`: terminal and not compensating
        assertNotNull(r.stored(done).redactedAt)
        assertTrue(r.stored(done).steps.values.all { it.input == null && it.output == null })
        assertEquals(placeholder, r.stored(done).input)
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(done).status, "status and timings survive redaction")
        assertEquals(0, svc.runOnce().redacted, "idempotent")

        // 100 days: stage 2
        clock.advance(day.multipliedBy(85))
        rep = svc.runOnce()
        assertEquals(1, rep.purged)
        assertNull(r.baseStore.get(Fx.tenantA, done))
        for ((name, id) in listOf("waiting" to waiting, "pending" to pending, "running" to running, "compensating" to compensating)) assertTrue(r.baseStore.get(Fx.tenantA, id) != null, "$name survives")
        assertNull(r.stored(waiting).redactedAt); assertNull(r.stored(pending).redactedAt); assertNull(r.stored(running).redactedAt)

        // compensation finished: it ages like any other finished run
        r.baseStore.compareAndSet(r.stored(compensating), r.stored(compensating).copy(compensation = CompensationState.DONE, updatedAt = clock.instant(), finishedAt = clock.instant()))
        clock.advance(day.multipliedBy(91))
        assertEquals(1, svc.runOnce().purged)
        assertNull(r.baseStore.get(Fx.tenantA, compensating))
        assertNotNull(r.baseStore.get(Fx.tenantA, waiting)); assertNotNull(r.baseStore.get(Fx.tenantA, pending)); assertNotNull(r.baseStore.get(Fx.tenantA, running))
    }

    @Test fun `a cancelled and a failed run age out like a succeeded one`() {
        val clock = TestClock(Instant.parse("2026-01-01T00:00:00Z")); val r = wfRig(clock)
        val a = r.startOk("two", key = "a"); r.drain()
        val b = r.startOk("pausing", key = "b"); r.drain(); r.engine.cancel(r.ctx(), b)
        val c = r.startOk("two", key = "c"); r.drain()
        r.baseStore.compareAndSet(r.stored(c), r.stored(c).copy(status = WorkflowRunStatus.FAILED, errorCode = "X"))
        clock.advance(day.multipliedBy(91))
        val svc = RetentionService(listOf(RetentionTargets.workflowRuns(r.baseStore, policy, Fx.json.createObjectNode(), clock)), policy, clock)
        assertEquals(3, svc.runOnce().purged)
        assertTrue(listOf(a, b, c).all { r.baseStore.get(Fx.tenantA, it) == null })
    }

    // ---- approvals ----------------------------------------------------------------------------------------------------------------------

    private fun approval(status: ApprovalStatus, finishedAt: Instant?, run: UUID? = null) = Approval(
        UUID.randomUUID(), Fx.tenantA, Fx.appA, "t", Fx.user, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-08T00:00:00Z"),
        listOf(PrincipalSpec.User(Fx.user2, null)), setOf(Fx.user2), 1, false, status, source = ApprovalSource(run, run?.let { "s" }), finishedAt = finishedAt
    )

    @Test fun `approvals - a pending one never ages out, a final one does`() {
        val store = InMemoryApprovalStore(); val t0 = Instant.parse("2026-01-01T00:00:00Z")
        val pending = approval(ApprovalStatus.PENDING, null); val approved = approval(ApprovalStatus.APPROVED, t0); val expired = approval(ApprovalStatus.EXPIRED, t0)
        listOf(pending, approved, expired).forEach { store.create(it) }
        val clock = TestClock(t0.plus(day.multipliedBy(400)))
        val rep = RetentionService(listOf(RetentionTargets.approvals(store, policy)), policy, clock).runOnce()
        assertEquals(2, rep.purged)
        assertNotNull(store.get(Fx.tenantA, pending.id)); assertNull(store.get(Fx.tenantA, approved.id)); assertNull(store.get(Fx.tenantA, expired.id))
    }

    @Test fun `a final approval whose workflow run is still active is kept`() {
        val clock = TestClock(Instant.parse("2026-01-01T00:00:00Z")); val r = wfRig(clock)
        val active = r.startOk("pausing", key = "w"); r.drain()
        val finished = r.startOk("two", key = "f"); r.drain()
        val store = InMemoryApprovalStore()
        val forActive = approval(ApprovalStatus.APPROVED, clock.instant(), active); val forFinished = approval(ApprovalStatus.APPROVED, clock.instant(), finished)
        store.create(forActive); store.create(forFinished)
        clock.advance(day.multipliedBy(400))
        val rep = RetentionService(listOf(RetentionTargets.approvals(store, policy, r.baseStore)), policy, clock).runOnce()
        assertEquals(1, rep.purged)
        assertNotNull(store.get(Fx.tenantA, forActive.id)); assertNull(store.get(Fx.tenantA, forFinished.id))
    }

    // ---- schedule ledger -------------------------------------------------------------------------------------------------------------------

    @Test fun `schedule ledger - a CLAIMED fire is never deleted, a final one is`() {
        val store = InMemoryScheduleStore(); val t0 = Instant.parse("2026-01-01T00:00:00Z"); val sid = UUID.randomUUID()
        fun row(min: Long, st: ExecutionStatus) { val f = t0.plusSeconds(60 * min); store.claimExecution(ScheduleExecution(Fx.tenantA, sid, Fx.appA, f, ScheduleExecution.dedupeKey(sid, f), st, 0, null, null, t0, t0)) }
        row(1, ExecutionStatus.CLAIMED); row(2, ExecutionStatus.ENQUEUED); row(3, ExecutionStatus.SKIPPED); row(4, ExecutionStatus.FAILED)
        val clock = TestClock(t0.plus(day.multipliedBy(60)))
        val rep = RetentionService(listOf(RetentionTargets.scheduleExecutions(store, policy)), policy, clock).runOnce()
        assertEquals(3, rep.purged)
        assertEquals(1, store.executions(Fx.tenantA, sid, 10).size)
        assertEquals(ExecutionStatus.CLAIMED, store.executions(Fx.tenantA, sid, 10).single().status)
    }

    // ---- bounded batches, isolation ------------------------------------------------------------------------------------------------------------

    @Test fun `a backlog is worked off in bounded batches, oldest first, over several runs`() {
        val store = InMemoryActionRunStore(); val t0 = Instant.parse("2026-01-01T00:00:00Z")
        repeat(250) { i -> val at = t0.plusSeconds(i.toLong()); val b = store.begin(key(i), "fp", at) as RunBegin.Started; store.complete(key(i), b.runId, ActionResult.Ok(Fx.obj()), at) }
        val small = RetentionPolicy(batchSize = 50, maxBatchesPerTarget = 2)
        val clock = TestClock(t0.plus(day.multipliedBy(40)))
        val svc = RetentionService(listOf(RetentionTargets.actionRuns(store, small)), small, clock)
        val first = svc.runOnce()
        assertEquals(100, first.purged); assertTrue(first.targets.single().moreToDo)
        assertEquals(150, store.size())
        assertNull(store.find(key(0))); assertNull(store.find(key(99))); assertTrue(store.find(key(100)) != null, "oldest first")
        svc.runOnce(); val third = svc.runOnce()
        assertEquals(50, third.purged); assertFalse(third.targets.single().moreToDo)
        assertEquals(0, store.size())
    }

    @Test fun `a failing target does not stop the others and cannot spin`() {
        val calls = java.util.concurrent.atomic.AtomicInteger(); val ok = java.util.concurrent.atomic.AtomicInteger()
        val bad = RetentionTarget("bad", null, Duration.ofDays(30), purge = { _, _ -> calls.incrementAndGet(); error("db down") })
        val good = RetentionTarget("good", null, Duration.ofDays(30), purge = { _, _ -> ok.incrementAndGet(); 3 })
        val rep = RetentionService(listOf(bad, good), RetentionPolicy(batchSize = 10), TestClock()).runOnce()
        assertEquals(1, calls.get(), "a failing store is called once per run, not retried in a loop")
        assertEquals(1, rep.errors); assertEquals(3, rep.purged)
        assertEquals(1, ok.get())
    }

    @Test fun `tenants are not distinguished - the horizon is age only, and other tenants' recent rows survive`() {
        val store = InMemoryActionRunStore(); val t0 = Instant.parse("2026-01-01T00:00:00Z")
        for ((i, tenant) in listOf(Fx.tenantA, Fx.tenantB).withIndex()) { val b = store.begin(key(i, tenant), "fp", t0) as RunBegin.Started; store.complete(key(i, tenant), b.runId, ActionResult.Ok(Fx.obj()), t0) }
        val recent = key(9, Fx.tenantB); val b = store.begin(recent, "fp", t0.plus(day.multipliedBy(39))) as RunBegin.Started; store.complete(recent, b.runId, ActionResult.Ok(Fx.obj()), t0.plus(day.multipliedBy(39)))
        val rep = RetentionService(listOf(RetentionTargets.actionRuns(store, policy)), policy, TestClock(t0.plus(day.multipliedBy(40)))).runOnce()
        assertEquals(2, rep.purged); assertNotNull(store.find(recent))
    }
}
