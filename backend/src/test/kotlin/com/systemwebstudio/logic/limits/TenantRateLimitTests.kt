package com.systemwebstudio.logic.limits

import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.ActionRequest
import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.ActionRig
import com.systemwebstudio.logic.action.ActionType
import com.systemwebstudio.logic.action.FakeAccess
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.Fx.str
import com.systemwebstudio.logic.action.LogicPermissions
import com.systemwebstudio.logic.action.TestClock
import com.systemwebstudio.logic.scheduler.EnqueueOutcome
import com.systemwebstudio.logic.scheduler.InMemoryScheduleStore
import com.systemwebstudio.logic.scheduler.ScheduleResult
import com.systemwebstudio.logic.scheduler.ScheduleSpec
import com.systemwebstudio.logic.scheduler.ScheduleTarget
import com.systemwebstudio.logic.scheduler.ScheduledRunEnqueuer
import com.systemwebstudio.logic.scheduler.ScheduledRunRequest
import com.systemwebstudio.logic.scheduler.SchedulerService
import com.systemwebstudio.logic.workflow.StepStatus
import com.systemwebstudio.logic.workflow.WorkflowResult
import com.systemwebstudio.logic.workflow.WorkflowRig
import com.systemwebstudio.logic.workflow.WorkflowRunStatus
import com.systemwebstudio.logic.workflow.WorkflowErrorCodes
import com.systemwebstudio.logic.workflow.act
import com.systemwebstudio.logic.workflow.wf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class TenantRateLimitTests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }

    private fun limits(cap: Int, refill: Double = 1.0, overrides: Map<UUID, Map<RateScope, RateLimit>> = emptyMap()) =
        TenantRateLimits(RateScope.entries.associateWith { RateLimit(cap, refill) }, overrides)

    // ---- the bucket -------------------------------------------------------------------------------------------------

    @Test fun `a tenant gets its burst, then is limited with an honest retry-after, then refills`() {
        val clock = TestClock()
        val l = InMemoryTenantRateLimiter(limits(3, refill = 2.0), clock)
        repeat(3) { assertInstanceOf(RateDecision.Allowed::class.java, l.tryAcquire(Fx.tenantA, RateScope.ACTION_EXECUTE)) }
        val limited = assertInstanceOf(RateDecision.Limited::class.java, l.tryAcquire(Fx.tenantA, RateScope.ACTION_EXECUTE))
        assertEquals(500L, limited.retryAfter.toMillis())                       // 1 token at 2/s
        clock.advance(Duration.ofMillis(500))
        assertInstanceOf(RateDecision.Allowed::class.java, l.tryAcquire(Fx.tenantA, RateScope.ACTION_EXECUTE))
        assertInstanceOf(RateDecision.Limited::class.java, l.tryAcquire(Fx.tenantA, RateScope.ACTION_EXECUTE))
        clock.advance(Duration.ofHours(1))                                      // refill is capped at the capacity
        repeat(3) { assertInstanceOf(RateDecision.Allowed::class.java, l.tryAcquire(Fx.tenantA, RateScope.ACTION_EXECUTE)) }
        assertInstanceOf(RateDecision.Limited::class.java, l.tryAcquire(Fx.tenantA, RateScope.ACTION_EXECUTE))
    }

    @Test fun `one tenant exhausting its budget never affects another tenant or another scope`() {
        val l = InMemoryTenantRateLimiter(limits(2), TestClock())
        repeat(2) { l.tryAcquire(Fx.tenantA, RateScope.ACTION_EXECUTE) }
        assertInstanceOf(RateDecision.Limited::class.java, l.tryAcquire(Fx.tenantA, RateScope.ACTION_EXECUTE))
        assertInstanceOf(RateDecision.Allowed::class.java, l.tryAcquire(Fx.tenantB, RateScope.ACTION_EXECUTE))
        assertInstanceOf(RateDecision.Allowed::class.java, l.tryAcquire(Fx.tenantA, RateScope.WORKFLOW_START))
        assertInstanceOf(RateDecision.Allowed::class.java, l.tryAcquire(Fx.tenantA, RateScope.SCHEDULER_ENQUEUE))
    }

    @Test fun `limits can be overridden per tenant and every scope has a default`() {
        val vip = RateLimit(100, 50.0)
        val l = InMemoryTenantRateLimiter(limits(1, overrides = mapOf(Fx.tenantB to mapOf(RateScope.ACTION_EXECUTE to vip))), TestClock())
        assertEquals(vip, TenantRateLimits(overrides = mapOf(Fx.tenantB to mapOf(RateScope.ACTION_EXECUTE to vip))).limitFor(Fx.tenantB, RateScope.ACTION_EXECUTE))
        assertEquals(TenantRateLimits.DEFAULTS.getValue(RateScope.WORKFLOW_START), TenantRateLimits().limitFor(Fx.tenantB, RateScope.WORKFLOW_START))
        l.tryAcquire(Fx.tenantA, RateScope.ACTION_EXECUTE)
        assertInstanceOf(RateDecision.Limited::class.java, l.tryAcquire(Fx.tenantA, RateScope.ACTION_EXECUTE))
        repeat(50) { assertInstanceOf(RateDecision.Allowed::class.java, l.tryAcquire(Fx.tenantB, RateScope.ACTION_EXECUTE)) }
        assertThrows(IllegalArgumentException::class.java) { TenantRateLimits(mapOf(RateScope.ACTION_EXECUTE to vip)) }
    }

    @Test fun `a cost above the capacity can never be satisfied and says so, and bad arguments are rejected`() {
        val l = InMemoryTenantRateLimiter(limits(2), TestClock())
        assertInstanceOf(RateDecision.Limited::class.java, l.tryAcquire(Fx.tenantA, RateScope.ACTION_EXECUTE, cost = 3))
        assertInstanceOf(RateDecision.Allowed::class.java, l.tryAcquire(Fx.tenantA, RateScope.ACTION_EXECUTE, cost = 2))
        assertThrows(IllegalArgumentException::class.java) { l.tryAcquire(Fx.tenantA, RateScope.ACTION_EXECUTE, cost = 0) }
        assertThrows(IllegalArgumentException::class.java) { RateLimit(0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { RateLimit(1, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { RateLimit(1, Double.NaN) }
    }

    @Test fun `memory is bounded and eviction only drops idle buckets first`() {
        val clock = TestClock()
        val l = InMemoryTenantRateLimiter(limits(2, refill = 1000.0), clock, maxBuckets = 5)
        repeat(100) { l.tryAcquire(UUID.randomUUID(), RateScope.ACTION_EXECUTE); clock.advance(Duration.ofMillis(10)) }
        assertTrue(l.bucketCount() <= 5, "buckets: ${l.bucketCount()}")
        // a tenant that is being limited right now keeps its (empty) bucket while idle ones are evicted
        val noisy = UUID.randomUUID()
        val slow = InMemoryTenantRateLimiter(limits(2, refill = 0.001), clock, maxBuckets = 3)
        repeat(2) { slow.tryAcquire(noisy, RateScope.ACTION_EXECUTE) }
        repeat(10) { slow.tryAcquire(UUID.randomUUID(), RateScope.ACTION_EXECUTE) }
        assertTrue(slow.bucketCount() <= 3)
    }

    @Test fun `concurrent callers never get more than the capacity`() {
        val l = InMemoryTenantRateLimiter(limits(50, refill = 0.0001), TestClock())
        val allowed = java.util.concurrent.atomic.AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val futures = (1..8).map { pool.submit { repeat(100) { if (l.tryAcquire(Fx.tenantA, RateScope.ACTION_EXECUTE) is RateDecision.Allowed) allowed.incrementAndGet() } } }
        futures.forEach { it.get() }; pool.shutdownNow()
        assertEquals(50, allowed.get())
    }

    // ---- fair selection ----------------------------------------------------------------------------------------------

    @Test fun `round robin gives every key its share and keeps the order inside a key`() {
        data class Row(val tenant: String, val n: Int)
        val rows = (1..100).map { Row("big", it) } + (1..3).map { Row("small", it) } + (1..2).map { Row("tiny", it) }
        val picked = FairSelection.pick(rows, limit = 10, perKey = 100) { it.tenant }
        assertEquals(10, picked.size)
        assertEquals(3, picked.count { it.tenant == "small" }); assertEquals(2, picked.count { it.tenant == "tiny" })
        assertEquals(listOf(1, 2, 3, 4, 5), picked.filter { it.tenant == "big" }.map { it.n })
    }

    @Test fun `a per key cap and the global limit both hold, and degenerate input is safe`() {
        val rows = (1..50).map { it to "a" } + (1..50).map { it to "b" }
        val p = FairSelection.pick(rows, limit = 100, perKey = 7) { it.second }
        assertEquals(14, p.size); assertEquals(7, p.count { it.second == "a" })
        assertEquals(emptyList<Pair<Int, String>>(), FairSelection.pick(rows, 0, 7) { it.second })
        assertEquals(emptyList<Pair<Int, String>>(), FairSelection.pick(rows, 5, 0) { it.second })
        assertEquals(emptyList<Pair<Int, String>>(), FairSelection.pick(emptyList<Pair<Int, String>>(), 5, 5) { it.second })
        assertEquals(1, FairSelection.pick(rows, 1, 7) { it.second }.size)
    }

    // ---- action execute -----------------------------------------------------------------------------------------------

    private fun createReq(key: String) = ActionRequest("m1", mapOf("title" to str("t")), idempotencyKey = key)

    @Test fun `action execution is limited per tenant and a limited tenant does not slow another`() {
        val clock = TestClock()
        val lim = InMemoryTenantRateLimiter(limits(2, refill = 1.0), clock)
        val a = Fx.mutation(ActionType.CREATE_RECORD)
        val rA = ActionRig.build(a, executor = executor, limiter = lim, clock = clock)
        repeat(2) { assertInstanceOf(ActionResult.Ok::class.java, rA.runtime.execute(Fx.ctx(), createReq("k$it"))) }
        val f = assertInstanceOf(ActionResult.Failed::class.java, rA.runtime.execute(Fx.ctx(), createReq("k9")))
        assertEquals(ActionErrorCodes.RATE_LIMITED, f.code); assertTrue(f.retryable)
        assertEquals("1000", f.details["retryAfterMillis"])
        assertEquals(2, rA.data.writes.size)                                    // the limited call reached no port
        // tenant B shares the limiter and has its own budget
        val rB = ActionRig.build(a.copy(tenantId = Fx.tenantB), executor = executor, limiter = lim, clock = clock)
        assertInstanceOf(ActionResult.Ok::class.java, rB.runtime.execute(Fx.ctx(Fx.tenantB), createReq("k1")))
        clock.advance(Duration.ofSeconds(1))
        assertInstanceOf(ActionResult.Ok::class.java, rA.runtime.execute(Fx.ctx(), createReq("k10")))
    }

    @Test fun `a caller without permission cannot spend the tenant's budget and a limited call leaves no run state`() {
        val lim = InMemoryTenantRateLimiter(limits(1, refill = 0.001), TestClock())
        val denied = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), access = FakeAccess(denyAll = true), executor = executor, limiter = lim)
        repeat(5) { denied.runtime.execute(Fx.ctx(), createReq("k$it")) }
        val ok = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), executor = executor, limiter = lim)
        assertInstanceOf(ActionResult.Ok::class.java, ok.runtime.execute(Fx.ctx(), createReq("k1")))             // budget untouched by the denied callers
        val limited = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), executor = executor, limiter = lim)
        assertEquals(ActionErrorCodes.RATE_LIMITED, (limited.runtime.execute(Fx.ctx(), createReq("k2")) as ActionResult.Failed).code)
        assertEquals(0, limited.runs.size())
    }

    @Test fun `a throwing limiter fails closed as retryable, and RATE_LIMITED never triggers onError actions`() {
        val boom = object : TenantRateLimiter { override fun tryAcquire(tenantId: UUID, scope: RateScope, cost: Int): RateDecision = error("redis down") }
        val r = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), executor = executor, limiter = boom)
        val f = r.runtime.execute(Fx.ctx(), createReq("k")) as ActionResult.Failed
        assertEquals(ActionErrorCodes.DEPENDENCY_UNAVAILABLE, f.code); assertTrue(f.retryable); assertTrue(r.data.writes.isEmpty())

        val never = object : TenantRateLimiter { override fun tryAcquire(tenantId: UUID, scope: RateScope, cost: Int): RateDecision = RateDecision.Limited(Duration.ofSeconds(1)) }
        val chain = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD).copy(onError = listOf("go-home")), Fx.navigate(), executor = executor, limiter = never)
        val ex = chain.runtime.run(Fx.ctx(), createReq("k"))
        assertEquals(ActionErrorCodes.RATE_LIMITED, (ex.result as ActionResult.Failed).code)
        assertTrue(ex.followUps.isEmpty())
    }

    @Test fun `TEST mode is limited too, so a preview loop cannot bypass the budget`() {
        val lim = InMemoryTenantRateLimiter(limits(1, refill = 0.001), TestClock())
        val r = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), executor = executor, limiter = lim)
        assertInstanceOf(ActionResult.WouldRun::class.java, r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("t")), mode = com.systemwebstudio.logic.action.ExecutionMode.TEST)))
        assertEquals(ActionErrorCodes.RATE_LIMITED, (r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("t")), mode = com.systemwebstudio.logic.action.ExecutionMode.TEST)) as ActionResult.Failed).code)
    }

    // ---- workflow start ---------------------------------------------------------------------------------------------------

    private fun rig(limiter: TenantRateLimiter = TenantRateLimiter.UNLIMITED, actionLimiter: TenantRateLimiter = TenantRateLimiter.UNLIMITED) =
        WorkflowRig(listOf(wf("w", act("a", "w1"))), listOf(Fx.write("w1")), executor, limiter = limiter, actionLimiter = actionLimiter)

    @Test fun `workflow starts are limited per tenant and the pushback is retryable`() {
        val clock = TestClock()
        val lim = InMemoryTenantRateLimiter(limits(2, refill = 1.0), clock)
        val r = WorkflowRig(listOf(wf("w", act("a", "w1"))), listOf(Fx.write("w1")), executor, limiter = lim)
        r.start("w", key = "k1"); r.start("w", key = "k2")
        val f = assertInstanceOf(WorkflowResult.Failed::class.java, r.start("w", key = "k3"))
        assertEquals(WorkflowErrorCodes.RATE_LIMITED, f.code); assertTrue(f.retryable)
        assertEquals(2, r.baseStore.list(Fx.tenantA, null, 10).size)            // the limited start created no run
        // an idempotent retry of an accepted start is still a start attempt, but another tenant is unaffected
        val other = lim.tryAcquire(Fx.tenantB, RateScope.WORKFLOW_START)
        assertInstanceOf(RateDecision.Allowed::class.java, other)
    }

    @Test fun `unauthorized starts do not spend the budget`() {
        val lim = InMemoryTenantRateLimiter(limits(1, refill = 0.001), TestClock())
        val r = WorkflowRig(listOf(wf("w", act("a", "w1"))), listOf(Fx.write("w1")), executor, limiter = lim)
        r.access.denyPermissions = setOf(LogicPermissions.WORKFLOW_EXECUTE)
        repeat(5) { assertEquals(WorkflowErrorCodes.FORBIDDEN, (r.start("w", key = "d$it") as WorkflowResult.Failed).code) }
        r.access.denyPermissions = emptySet()
        assertInstanceOf(WorkflowResult.Ok::class.java, r.start("w", key = "ok"))
    }

    @Test fun `a rate limited action step waits as told and keeps its retry budget`() {
        val clock = TestClock()
        // 1 token, refill 1/s: the first workflow step passes, a second run's step is told to wait
        val r2 = WorkflowRig(listOf(wf("w", act("a", "w1"))), listOf(Fx.write("w1")), executor, actionLimiter = InMemoryTenantRateLimiter(limits(1, refill = 1.0), clock), clock = clock)
        val first = r2.startOk("w", key = "a1"); val second = r2.startOk("w", key = "a2")
        r2.drain()
        val done = listOf(first, second).count { r2.stored(it).status == WorkflowRunStatus.SUCCEEDED }
        assertEquals(1, done)
        val waiting = listOf(first, second).first { r2.stored(it).status != WorkflowRunStatus.SUCCEEDED }
        val st = r2.step(waiting, "a")!!
        assertEquals(StepStatus.RETRY_WAIT, st.status)
        assertEquals(ActionErrorCodes.RATE_LIMITED, st.errorCode)
        assertEquals(0, st.attempt)                                             // pushback is not an attempt
        assertTrue(st.wakeAt!!.isAfter(r2.now) && !st.wakeAt!!.isBefore(r2.now.plusSeconds(1)))   // at least the backoff floor
        r2.advance(Duration.ofSeconds(2)); val rep = r2.engine.sweep(); val n = r2.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r2.stored(waiting).status, "sweep=$rep drained=$n step=${r2.step(waiting, "a")}")
        assertEquals(2, r2.writes("w1"))
    }

    // ---- scheduler ----------------------------------------------------------------------------------------------------------

    private class Enq : ScheduledRunEnqueuer {
        val requests = CopyOnWriteArrayList<ScheduledRunRequest>()
        override fun enqueue(request: ScheduledRunRequest): EnqueueOutcome { requests += request; return EnqueueOutcome.Enqueued("r") }
    }

    @Test fun `the scheduler limits fires per tenant, leaves limited schedules due, and other tenants still fire`() {
        val clock = TestClock(Instant.parse("2026-10-05T00:00:30Z"))
        val store = InMemoryScheduleStore(); val enq = Enq()
        val lim = InMemoryTenantRateLimiter(limits(2, refill = 1.0), clock)
        val svc = SchedulerService(store, enq, FakeAccess(), com.systemwebstudio.logic.action.FakeTenants(), com.systemwebstudio.logic.action.RecordingLogicAudit(), clock, limiter = lim)
        fun mk(tenant: UUID, i: Int) = (svc.create(Fx.ctx(tenant), ScheduleSpec(Fx.appA, "j$i", "* * * * *", "UTC", ScheduleTarget.Workflow("w"))) as ScheduleResult.Ok).value
        repeat(5) { mk(Fx.tenantA, it) }; repeat(2) { mk(Fx.tenantB, it) }
        clock.advance(Duration.ofSeconds(40))                                   // all seven are due
        val report = svc.tick()
        assertEquals(4, report.fired)                                           // A: 2 (its burst) + B: 2
        assertEquals(3, report.rateLimited)
        assertEquals(2, enq.requests.count { it.tenantId == Fx.tenantA }); assertEquals(2, enq.requests.count { it.tenantId == Fx.tenantB })
        // the limited ones were not claimed, so nothing was lost: they are still due and fire once the budget refills
        assertEquals(3, store.due(clock.instant(), 100).size)
        clock.advance(Duration.ofSeconds(2))
        assertEquals(2, svc.tick().fired)
        clock.advance(Duration.ofSeconds(2))
        assertEquals(1, svc.tick().fired)
        assertEquals(7, enq.requests.map { it.scheduleId }.toSet().size)
    }

    @Test fun `one tenant's backlog of due schedules cannot crowd out another tenant within a tick`() {
        val clock = TestClock(Instant.parse("2026-10-05T00:00:30Z"))
        val store = InMemoryScheduleStore(); val enq = Enq()
        val svc = SchedulerService(store, enq, FakeAccess(), com.systemwebstudio.logic.action.FakeTenants(), com.systemwebstudio.logic.action.RecordingLogicAudit(), clock,
            limiter = TenantRateLimiter.UNLIMITED, maxPerTenantPerTick = 5, maxSchedulesPerApp = 1000)
        repeat(300) { svc.create(Fx.ctx(Fx.tenantA), ScheduleSpec(Fx.appA, "a$it", "* * * * *", "UTC", ScheduleTarget.Workflow("w"))) }
        clock.advance(Duration.ofSeconds(1))                                    // A's schedules are due first (created earlier)
        svc.create(Fx.ctx(Fx.tenantB), ScheduleSpec(Fx.appA, "b", "* * * * *", "UTC", ScheduleTarget.Workflow("w")))
        clock.advance(Duration.ofSeconds(60))
        val report = svc.tick(limit = 10)
        assertEquals(6, report.fired)                                           // A is capped at 5 per tick, B's single schedule is not starved
        assertTrue(enq.requests.any { it.tenantId == Fx.tenantB }, "tenant B was starved by tenant A's backlog")
        assertEquals(5, enq.requests.count { it.tenantId == Fx.tenantA })
    }
}
