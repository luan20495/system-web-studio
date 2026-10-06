package com.systemwebstudio.logic.scheduler

import com.systemwebstudio.logic.action.FakeAccess
import com.systemwebstudio.logic.action.FakeTenants
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.RecordingLogicAudit
import com.systemwebstudio.logic.action.TestClock
import com.systemwebstudio.logic.limits.TenantRateLimiter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Scheduler dedupe (V2 hardening): a fire time reaches the workflow runtime at most once across restarts, several workers, skewed or
 * backwards clocks and DST changes; failing enqueues back off and give up; declared schedules are registered idempotently.
 */
class SchedulerDedupeTests {
    private class Enq(var next: () -> EnqueueOutcome = { EnqueueOutcome.Enqueued("run") }) : ScheduledRunEnqueuer {
        val requests = CopyOnWriteArrayList<ScheduledRunRequest>()
        override fun enqueue(request: ScheduledRunRequest): EnqueueOutcome { requests += request; return next() }
    }

    private class Rig(start: String = "2026-10-05T00:00:30Z", val maxAttempts: Int = 8) {
        val clock = TestClock(Instant.parse(start))
        val store = InMemoryScheduleStore()
        val enq = Enq()
        val access = FakeAccess(); val tenants = FakeTenants(); val audit = RecordingLogicAudit()
        fun service() = SchedulerService(store, enq, access, tenants, audit, clock, maxEnqueueAttempts = maxAttempts, limiter = TenantRateLimiter.UNLIMITED)
        val svc = service()
        fun create(cron: String = "* * * * *", tz: String = "UTC", tenant: UUID = Fx.tenantA, app: UUID = Fx.appA) =
            (svc.create(Fx.ctx(tenant = tenant, app = app), ScheduleSpec(app, "job", cron, tz, ScheduleTarget.Workflow("nightly"))) as ScheduleResult.Ok).value
        fun get(s: Schedule) = store.get(s.tenantId, s.id)!!
        fun at(iso: String) { clock.now = Instant.parse(iso) }
    }

    // ---- restart / rewound row ---------------------------------------------------------------------------------------

    @Test fun `a restart (new service on the same store) does not fire the same time again`() {
        val r = Rig(); val s = r.create()
        r.clock.advance(Duration.ofSeconds(35))
        assertEquals(1, r.svc.tick().fired)
        val restarted = r.service()
        assertEquals(0, restarted.tick().fired)
        r.clock.advance(Duration.ofSeconds(10))
        assertEquals(0, restarted.tick().fired)
        assertEquals(1, r.enq.requests.size)
        assertEquals(ExecutionStatus.ENQUEUED, r.store.execution(s.tenantId, s.id, Instant.parse("2026-10-05T00:01:00Z"))!!.status)
    }

    @Test fun `a rewound schedule row cannot make the ledger hand the same fire over twice`() {
        val r = Rig(); val s = r.create()
        val before = r.get(s)
        r.clock.advance(Duration.ofSeconds(35))
        assertEquals(1, r.svc.tick().fired)
        // restore from a backup / lost write: the row looks like it never fired
        assertTrue(r.store.compareAndSet(r.get(s), before))
        val t = r.service().tick()
        assertEquals(0, t.fired); assertEquals(1, t.deduplicated)
        assertEquals(1, r.enq.requests.size)
        assertNull(r.get(s).pendingFireAt)
        assertEquals(Instant.parse("2026-10-05T00:02:00Z"), r.get(s).nextRunAt)   // and it moved on to the next minute
    }

    @Test fun `a nextRunAt that is not after the last fire is skipped by the monotonic guard`() {
        val r = Rig(); val s = r.create()
        r.clock.advance(Duration.ofSeconds(35)); r.svc.tick()
        r.store.compareAndSet(r.get(s), r.get(s).copy(nextRunAt = Instant.parse("2026-10-05T00:01:00Z")))   // already fired
        val t = r.svc.tick()
        assertEquals(0, t.fired); assertEquals(1, t.deduplicated)
        assertEquals("SKIPPED_DUPLICATE", r.get(s).lastRunStatus)
        assertEquals(1, r.enq.requests.size)
    }

    @Test fun `a confirmation lost after a successful hand-over is repaired without a second hand-over`() {
        val r = Rig(); val s = r.create()
        r.clock.advance(Duration.ofSeconds(35)); r.svc.tick()
        val fireAt = Instant.parse("2026-10-05T00:01:00Z")
        // the process died before the schedule row was confirmed: the row says "pending", the ledger says "enqueued"
        r.store.compareAndSet(r.get(s), r.get(s).copy(pendingFireAt = fireAt, pendingNotBefore = r.clock.instant()))
        val t = r.svc.tick()
        assertEquals(1, t.deduplicated); assertEquals(0, t.retried)
        assertEquals(1, r.enq.requests.size)
        assertNull(r.get(s).pendingFireAt)
    }

    // ---- several workers -----------------------------------------------------------------------------------------------

    @Test fun `many workers ticking concurrently hand every fire over exactly once`() {
        val r = Rig(); val schedules = (1..15).map { r.create() }
        val workers = 8; val pool = Executors.newFixedThreadPool(workers)
        try {
            repeat(5) { // five consecutive minutes
                r.clock.advance(Duration.ofSeconds(if (it == 0) 35 else 60))
                val go = CountDownLatch(1); val done = CountDownLatch(workers)
                repeat(workers) { pool.execute { val svc = r.service(); go.await(); repeat(3) { svc.tick() }; done.countDown() } }
                go.countDown(); assertTrue(done.await(30, TimeUnit.SECONDS))
            }
        } finally { pool.shutdownNow() }
        assertEquals(15 * 5, r.enq.requests.size)
        assertEquals(15 * 5, r.enq.requests.map { it.idempotencyKey }.toSet().size, "every (schedule, fire time) has its own key and it was used once")
        assertTrue(schedules.all { s -> r.enq.requests.count { it.scheduleId == s.id } == 5 })
    }

    @Test fun `claiming one execution from many threads creates exactly one ledger row`() {
        val store = InMemoryScheduleStore()
        val sid = UUID.randomUUID(); val fire = Instant.parse("2026-10-05T00:01:00Z"); val now = Instant.parse("2026-10-05T00:01:05Z")
        val pool = Executors.newFixedThreadPool(16); val go = CountDownLatch(1); val won = java.util.concurrent.atomic.AtomicInteger()
        val done = CountDownLatch(16)
        repeat(16) { pool.execute { go.await(); if (store.claimExecution(ScheduleExecution(Fx.tenantA, sid, Fx.appA, fire, ScheduleExecution.dedupeKey(sid, fire), ExecutionStatus.CLAIMED, 0, null, null, now, now)) is ClaimResult.Claimed) won.incrementAndGet(); done.countDown() } }
        go.countDown(); assertTrue(done.await(10, TimeUnit.SECONDS)); pool.shutdownNow()
        assertEquals(1, won.get())
    }

    @Test fun `a final ledger row never moves to another status`() {
        val store = InMemoryScheduleStore(); val sid = UUID.randomUUID(); val fire = Instant.parse("2026-10-05T00:01:00Z"); val now = Instant.parse("2026-10-05T00:01:05Z")
        store.claimExecution(ScheduleExecution(Fx.tenantA, sid, Fx.appA, fire, "k", ExecutionStatus.CLAIMED, 0, null, null, now, now))
        store.updateExecution(Fx.tenantA, sid, fire, ExecutionStatus.ENQUEUED, 1, "run-1", null, now)
        val again = store.updateExecution(Fx.tenantA, sid, fire, ExecutionStatus.FAILED, 2, null, "X", now)!!
        assertEquals(ExecutionStatus.ENQUEUED, again.status); assertEquals("run-1", again.runRef)
    }

    // ---- skewed / backwards clock ------------------------------------------------------------------------------------------

    @Test fun `a clock that jumps back does not refire an already fired time`() {
        val r = Rig(); val s = r.create()
        r.clock.advance(Duration.ofSeconds(35)); assertEquals(1, r.svc.tick().fired)           // fired 00:01:00
        r.at("2026-10-05T00:00:10Z")                                                           // NTP step back
        assertEquals(0, r.svc.tick().fired)
        r.svc.setEnabled(Fx.ctx(), s.id, false); r.svc.setEnabled(Fx.ctx(), s.id, true)        // re-enable on the wrong clock
        assertEquals(Instant.parse("2026-10-05T00:02:00Z"), r.get(s).nextRunAt, "never earlier than the last fire")
        r.at("2026-10-05T00:01:30Z"); assertEquals(0, r.svc.tick().fired)
        r.at("2026-10-05T00:02:05Z"); assertEquals(1, r.svc.tick().fired)
        assertEquals(2, r.enq.requests.map { it.idempotencyKey }.toSet().size)
    }

    @Test fun `two nodes with clocks a few seconds apart still fire a time once`() {
        val r = Rig()
        val s = r.create()
        val skewed = TestClock(Instant.parse("2026-10-05T00:01:03Z"))
        val other = SchedulerService(r.store, r.enq, r.access, r.tenants, r.audit, skewed, limiter = TenantRateLimiter.UNLIMITED)
        r.at("2026-10-05T00:01:01Z")
        val fired = r.svc.tick().fired + other.tick().fired
        r.at("2026-10-05T00:01:20Z"); skewed.now = Instant.parse("2026-10-05T00:01:22Z")
        val more = r.svc.tick().fired + other.tick().fired
        assertEquals(1, fired + more)
        assertEquals(1, r.enq.requests.count { it.scheduleId == s.id })
    }

    // ---- DST ---------------------------------------------------------------------------------------------------------------------

    private fun runTicks(r: Rig, from: String, to: String, step: Duration = Duration.ofSeconds(30)) {
        r.at(from); val end = Instant.parse(to)
        while (r.clock.instant().isBefore(end)) { r.svc.tick(); r.clock.advance(step) }
    }

    @Test fun `fall-back hour - a daily time inside the repeated hour fires once and no key repeats`() {
        val r = Rig("2026-11-01T03:00:00Z")
        r.create("30 1 * * *", "America/New_York")
        runTicks(r, "2026-11-01T03:00:00Z", "2026-11-01T12:00:00Z")
        assertEquals(1, r.enq.requests.size)
        assertEquals(r.enq.requests.size, r.enq.requests.map { it.idempotencyKey }.toSet().size)
    }

    @Test fun `fall-back hour - an hourly schedule never fires two times at the same instant or key`() {
        val r = Rig("2026-11-01T03:00:00Z")
        r.create("30 * * * *", "America/New_York")
        runTicks(r, "2026-11-01T03:00:00Z", "2026-11-01T10:00:00Z")
        val fires = r.enq.requests.map { it.fireAt }
        assertEquals(fires.toSet().size, fires.size)
        assertEquals(fires.sorted(), fires, "strictly increasing")
        assertEquals(fires.size, r.enq.requests.map { it.idempotencyKey }.toSet().size)
        assertTrue(fires.size >= 5)
    }

    @Test fun `spring-forward gap - a daily time inside the skipped hour fires once that day`() {
        val r = Rig("2026-03-08T04:00:00Z")
        r.create("30 2 * * *", "America/New_York")
        runTicks(r, "2026-03-08T04:00:00Z", "2026-03-08T12:00:00Z")
        assertEquals(1, r.enq.requests.size)
    }

    // ---- failing enqueues: backoff and give-up ------------------------------------------------------------------------------

    @Test fun `a failing enqueue backs off exponentially and is given up after the attempt budget`() {
        val r = Rig(maxAttempts = 3); val s = r.create()
        r.enq.next = { EnqueueOutcome.Failed("DEPENDENCY_UNAVAILABLE", true) }
        r.at("2026-10-05T00:01:05Z")
        assertEquals(1, r.svc.tick().enqueueFailed)                                   // attempt 1, next try in 30s
        assertEquals(1, r.enq.requests.size)
        assertEquals(Instant.parse("2026-10-05T00:01:35Z"), r.get(s).pendingNotBefore)
        r.at("2026-10-05T00:01:34Z"); r.svc.tick(); assertEquals(1, r.enq.requests.size, "inside the backoff nothing is retried")
        r.at("2026-10-05T00:01:36Z"); r.svc.tick()                                    // attempt 2, next try in 60s
        assertEquals(2, r.enq.requests.size)
        assertEquals(Instant.parse("2026-10-05T00:02:36Z"), r.get(s).pendingNotBefore)
        r.at("2026-10-05T00:02:30Z"); r.svc.tick(); assertEquals(2, r.enq.requests.size)
        r.at("2026-10-05T00:02:37Z")
        val t = r.svc.tick()                                                          // attempt 3: budget used up
        assertEquals(1, t.gaveUp)
        // the given-up fire is gone; what is pending now is the NEXT minute's fire (which fails too), with a fresh attempt count
        assertEquals(Instant.parse("2026-10-05T00:02:00Z"), r.get(s).pendingFireAt)
        assertEquals(1, r.get(s).pendingAttempts)
        assertEquals(ExecutionStatus.FAILED, r.store.execution(s.tenantId, s.id, Instant.parse("2026-10-05T00:01:00Z"))!!.status)
        assertTrue(r.get(s).enabled, "the schedule itself carries on")
        assertNotNull(r.get(s).nextRunAt)
    }

    @Test fun `one failing schedule does not delay a healthy one`() {
        val r = Rig(); val bad = r.create(); val good = r.create()
        r.enq.next = { EnqueueOutcome.Failed("DEPENDENCY_UNAVAILABLE", true) }
        r.at("2026-10-05T00:01:05Z")
        val t = r.svc.tick()
        assertEquals(2, t.enqueueFailed)
        r.enq.next = { EnqueueOutcome.Enqueued("run") }
        r.at("2026-10-05T00:01:40Z")
        assertEquals(2, r.svc.tick().retried)
        assertNull(r.get(bad).pendingFireAt); assertNull(r.get(good).pendingFireAt)
    }

    @Test fun `an unavailable ledger blocks the hand-over and the fire is retried later`() {
        val real = InMemoryScheduleStore(); var down = true
        val flaky = object : ScheduleStore by real {
            override fun claimExecution(execution: ScheduleExecution): ClaimResult { if (down) error("ledger down"); return real.claimExecution(execution) }
        }
        val clock = TestClock(Instant.parse("2026-10-05T00:00:30Z")); val enq = Enq()
        val svc = SchedulerService(flaky, enq, FakeAccess(), FakeTenants(), RecordingLogicAudit(), clock, limiter = TenantRateLimiter.UNLIMITED)
        svc.create(Fx.ctx(), ScheduleSpec(Fx.appA, "j", "* * * * *", "UTC", ScheduleTarget.Workflow("w")))
        clock.advance(Duration.ofSeconds(35))
        assertEquals(1, svc.tick().enqueueFailed)
        assertTrue(enq.requests.isEmpty(), "no hand-over without a ledger row")
        down = false; clock.advance(Duration.ofSeconds(31))
        svc.tick()
        assertEquals(1, enq.requests.size)
    }

    @Test fun `the execution history records what happened to each fire`() {
        val r = Rig(); val s = r.create()
        r.enq.next = { EnqueueOutcome.Enqueued("run-77") }
        r.clock.advance(Duration.ofSeconds(35)); r.svc.tick()
        val h = (r.svc.executions(Fx.ctx(), s.id) as ScheduleResult.Ok).value.single()
        assertEquals(ExecutionStatus.ENQUEUED, h.status); assertEquals("run-77", h.runRef)
        assertEquals("sched:${s.id}:${Instant.parse("2026-10-05T00:01:00Z").toEpochMilli()}", h.dedupeKey)
        assertEquals("SCHEDULE_NOT_FOUND", (r.svc.executions(Fx.ctx(tenant = Fx.tenantB), s.id) as ScheduleResult.Failed).code)
    }

    // ---- declared schedules: idempotent registration -------------------------------------------------------------------------------

    private fun decl(wf: String = "nightly", cron: String = "0 3 * * *", tz: String = "UTC") = DeclaredScheduleSpec(wf, cron, tz)

    @Test fun `publishing the same declaration again creates nothing and leaves the next run alone`() {
        val r = Rig()
        val first = (r.svc.syncDeclared(Fx.ctx(), Fx.appA, listOf(decl())) as ScheduleResult.Ok).value
        assertEquals(SyncReport(1, 0, 0, 0), first)
        val s = r.store.list(Fx.tenantA, Fx.appA).single(); val next = s.nextRunAt
        r.clock.advance(Duration.ofMinutes(5))
        assertEquals(SyncReport(0, 0, 1, 0), (r.service().syncDeclared(Fx.ctx(), Fx.appA, listOf(decl())) as ScheduleResult.Ok).value)
        assertEquals(1, r.store.list(Fx.tenantA, Fx.appA).size)
        assertEquals(next, r.get(s).nextRunAt)
    }

    @Test fun `a changed declaration updates the existing schedule and an undeclared one is disabled, not deleted`() {
        val r = Rig()
        r.svc.syncDeclared(Fx.ctx(), Fx.appA, listOf(decl("a"), decl("b")))
        assertEquals(2, r.store.list(Fx.tenantA, Fx.appA).size)
        val upd = (r.svc.syncDeclared(Fx.ctx(), Fx.appA, listOf(decl("a", "30 4 * * *"))) as ScheduleResult.Ok).value
        assertEquals(SyncReport(0, 1, 0, 1), upd)
        val all = r.store.list(Fx.tenantA, Fx.appA)
        assertEquals(2, all.size)
        assertEquals("30 4 * * *", all.first { it.declaredKey == "workflow:a" }.cron)
        assertFalse(all.first { it.declaredKey == "workflow:b" }.enabled)
        // declaring b again re-enables the same row
        r.svc.syncDeclared(Fx.ctx(), Fx.appA, listOf(decl("a", "30 4 * * *"), decl("b")))
        assertEquals(2, r.store.list(Fx.tenantA, Fx.appA).size)
        assertTrue(r.store.list(Fx.tenantA, Fx.appA).all { it.enabled })
    }

    @Test fun `racing publishers end up with one schedule per declaration`() {
        val r = Rig(); val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1); val done = CountDownLatch(8)
        repeat(8) { pool.execute { val svc = r.service(); go.await(); svc.syncDeclared(Fx.ctx(), Fx.appA, listOf(decl("a"), decl("b"))); done.countDown() } }
        go.countDown(); assertTrue(done.await(20, TimeUnit.SECONDS)); pool.shutdownNow()
        assertEquals(2, r.store.list(Fx.tenantA, Fx.appA).size)
        assertEquals(setOf("workflow:a", "workflow:b"), r.store.list(Fx.tenantA, Fx.appA).mapNotNull { it.declaredKey }.toSet())
    }

    @Test fun `declared schedules are tenant and app scoped and need the manage permission`() {
        val r = Rig()
        r.svc.syncDeclared(Fx.ctx(), Fx.appA, listOf(decl()))
        r.svc.syncDeclared(Fx.ctx(tenant = Fx.tenantB, app = Fx.appB), Fx.appB, listOf(decl()))
        assertEquals(1, r.store.list(Fx.tenantA, null).size); assertEquals(1, r.store.list(Fx.tenantB, null).size)
        r.access.denyAll = true
        assertEquals("FORBIDDEN", (r.svc.syncDeclared(Fx.ctx(), Fx.appA, listOf(decl("x"))) as ScheduleResult.Failed).code)
        r.access.denyAll = false
        assertEquals("INVALID_CRON", (r.svc.syncDeclared(Fx.ctx(), Fx.appA, listOf(decl("y", "nope"))) as ScheduleResult.Failed).code)
    }
}
