package com.systemwebstudio.logic.scheduler

import com.systemwebstudio.logic.action.AuditDomains
import com.systemwebstudio.logic.action.FakeAccess
import com.systemwebstudio.logic.action.FakeTenants
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.LogicPermissions
import com.systemwebstudio.logic.action.RecordingLogicAudit
import com.systemwebstudio.logic.action.TestClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class SchedulerServiceTests {
    private class FakeEnqueuer(var next: () -> EnqueueOutcome = { EnqueueOutcome.Enqueued("run-1") }) : ScheduledRunEnqueuer {
        val requests = CopyOnWriteArrayList<ScheduledRunRequest>()
        override fun enqueue(request: ScheduledRunRequest): EnqueueOutcome { requests += request; return next() }
    }

    private class Rig {
        val clock = TestClock(Instant.parse("2026-10-05T00:00:30Z"))
        val store = InMemoryScheduleStore()
        val enq = FakeEnqueuer()
        val access = FakeAccess()
        val tenants = FakeTenants()
        val audit = RecordingLogicAudit()
        val svc = SchedulerService(store, enq, access, tenants, audit, clock, limiter = com.systemwebstudio.logic.limits.TenantRateLimiter.UNLIMITED)
        fun create(cron: String = "* * * * *", tz: String = "UTC", enabled: Boolean = true, misfire: MisfirePolicy = MisfirePolicy.SKIP, target: ScheduleTarget = ScheduleTarget.Workflow("nightly")) =
            (svc.create(Fx.ctx(), ScheduleSpec(Fx.appA, "job", cron, tz, target, null, enabled, misfire)) as ScheduleResult.Ok).value
    }

    private fun failed(r: ScheduleResult<*>) = assertInstanceOf(ScheduleResult.Failed::class.java, r)

    // ---- CRUD ------------------------------------------------------------------------------------------------------

    @Test fun `create stores the schedule with its next run and audits it`() {
        val r = Rig()
        val s = r.create("0 9 * * *", "Asia/Tokyo")
        assertEquals(Instant.parse("2026-10-05T00:00:00Z").plus(Duration.ofDays(1)), s.nextRunAt)
        assertEquals(Fx.user, s.createdBy)
        assertTrue(r.audit.events(AuditDomains.SCHEDULE).contains("CREATED"))
    }

    @Test fun `create validates cron timezone name target and input size`() {
        val r = Rig(); val c = Fx.ctx()
        fun spec(cron: String = "* * * * *", tz: String = "UTC", name: String = "n", target: ScheduleTarget = ScheduleTarget.Workflow("w"), input: tools.jackson.databind.JsonNode? = null) =
            ScheduleSpec(Fx.appA, name, cron, tz, target, input)
        assertEquals("INVALID_CRON", failed(r.svc.create(c, spec(cron = "nope"))).code)
        assertEquals("INVALID_CRON", failed(r.svc.create(c, spec(cron = "0 0 31 2 *"))).code)
        assertEquals("INVALID_TIMEZONE", failed(r.svc.create(c, spec(tz = "Mars/Base"))).code)
        assertEquals("INVALID", failed(r.svc.create(c, spec(name = " "))).code)
        assertEquals("INVALID", failed(r.svc.create(c, spec(target = ScheduleTarget.Workflow("http://evil.example/x")))).code)
        assertEquals("INVALID", failed(r.svc.create(c, spec(target = ScheduleTarget.Action("a b")))).code)
        assertEquals("LIMIT_EXCEEDED", failed(r.svc.create(c, spec(input = Fx.str("x".repeat(20_000))))).code)
    }

    @Test fun `managing schedules needs WORKFLOW_MANAGE and an enabled tenant`() {
        val r = Rig()
        r.access.denyPermissions = setOf(LogicPermissions.WORKFLOW_MANAGE)
        assertEquals("FORBIDDEN", failed(r.svc.create(Fx.ctx(), ScheduleSpec(Fx.appA, "n", "* * * * *", "UTC", ScheduleTarget.Workflow("w")))).code)
        r.access.denyPermissions = emptySet(); r.tenants.disabled = setOf(Fx.tenantA)
        assertEquals("TENANT_DISABLED", failed(r.svc.create(Fx.ctx(), ScheduleSpec(Fx.appA, "n", "* * * * *", "UTC", ScheduleTarget.Workflow("w")))).code)
        r.tenants.disabled = emptySet(); r.access.throwing = true
        assertEquals("DEPENDENCY_UNAVAILABLE", failed(r.svc.create(Fx.ctx(), ScheduleSpec(Fx.appA, "n", "* * * * *", "UTC", ScheduleTarget.Workflow("w")))).code)
    }

    @Test fun `schedules are tenant scoped`() {
        val r = Rig(); val s = r.create()
        val other = Fx.ctx(tenant = Fx.tenantB)
        assertEquals("SCHEDULE_NOT_FOUND", failed(r.svc.setEnabled(other, s.id, false)).code)
        assertEquals("SCHEDULE_NOT_FOUND", failed(r.svc.delete(other, s.id)).code)
        assertTrue((r.svc.list(other, null) as ScheduleResult.Ok).value.isEmpty())
        assertEquals(1, (r.svc.list(Fx.ctx(), null) as ScheduleResult.Ok).value.size)
    }

    @Test fun `disable clears the next run and enable recomputes it`() {
        val r = Rig(); val s = r.create("*/5 * * * *")
        val off = (r.svc.setEnabled(Fx.ctx(), s.id, false) as ScheduleResult.Ok).value
        assertFalse(off.enabled); assertNull(off.nextRunAt)
        r.clock.advance(Duration.ofMinutes(7))
        val on = (r.svc.setEnabled(Fx.ctx(), s.id, true) as ScheduleResult.Ok).value
        assertEquals(Instant.parse("2026-10-05T00:10:00Z"), on.nextRunAt)
    }

    @Test fun `delete removes the schedule`() {
        val r = Rig(); val s = r.create()
        assertInstanceOf(ScheduleResult.Ok::class.java, r.svc.delete(Fx.ctx(), s.id))
        assertEquals("SCHEDULE_NOT_FOUND", failed(r.svc.delete(Fx.ctx(), s.id)).code)
    }

    @Test fun `the number of schedules per app is limited`() {
        val r = Rig()
        val svc = SchedulerService(r.store, r.enq, r.access, r.tenants, r.audit, r.clock, maxSchedulesPerApp = 2)
        repeat(2) { svc.create(Fx.ctx(), ScheduleSpec(Fx.appA, "n$it", "* * * * *", "UTC", ScheduleTarget.Workflow("w"))) }
        assertEquals("LIMIT_EXCEEDED", failed(svc.create(Fx.ctx(), ScheduleSpec(Fx.appA, "n3", "* * * * *", "UTC", ScheduleTarget.Workflow("w")))).code)
    }

    // ---- tick: only enqueues ---------------------------------------------------------------------------------------

    @Test fun `a due schedule is handed to the enqueuer with a deterministic idempotency key`() {
        val r = Rig(); val s = r.create("* * * * *")                    // next = 00:01:00
        assertEquals(0, r.svc.tick().fired)                               // not due yet
        r.clock.advance(Duration.ofSeconds(35))                           // 00:01:05
        assertEquals(1, r.svc.tick().fired)
        val req = r.enq.requests.single()
        assertEquals(Fx.tenantA, req.tenantId); assertEquals(s.id, req.scheduleId); assertEquals(Fx.user, req.actorUserId)
        assertEquals(ScheduleTarget.Workflow("nightly"), req.target)
        assertEquals("sched:${s.id}:${Instant.parse("2026-10-05T00:01:00Z").toEpochMilli()}", req.idempotencyKey)
        val after = r.store.get(Fx.tenantA, s.id)!!
        assertNull(after.pendingFireAt)
        assertEquals(Instant.parse("2026-10-05T00:02:00Z"), after.nextRunAt)
        assertEquals(Instant.parse("2026-10-05T00:01:00Z"), after.lastRunAt)
        assertEquals("FIRED", after.lastRunStatus)
        assertEquals(0, r.svc.tick().fired)                               // the same minute never fires twice
    }

    @Test fun `two nodes ticking at once enqueue a fire exactly once`() {
        val r = Rig(); r.create("* * * * *")
        val other = SchedulerService(r.store, r.enq, r.access, r.tenants, r.audit, r.clock)
        r.clock.advance(Duration.ofSeconds(35))
        val a = r.svc.tick().fired + other.tick().fired
        assertEquals(1, a)
        assertEquals(1, r.enq.requests.size)
    }

    @Test fun `a disabled schedule never fires`() {
        val r = Rig(); r.create(enabled = false)
        r.clock.advance(Duration.ofHours(1))
        assertEquals(0, r.svc.tick().fired); assertTrue(r.enq.requests.isEmpty())
    }

    @Test fun `a missed fire is skipped under SKIP and fires once under FIRE_ONCE`() {
        val skip = Rig(); val s1 = skip.create("*/10 * * * *", misfire = MisfirePolicy.SKIP)
        skip.clock.advance(Duration.ofHours(3))
        val rs = skip.svc.tick()
        assertEquals(1, rs.skippedMisfire); assertEquals(0, rs.fired)
        assertTrue(skip.enq.requests.isEmpty())
        assertTrue(skip.store.get(Fx.tenantA, s1.id)!!.nextRunAt!!.isAfter(skip.clock.instant()))

        val once = Rig(); once.create("*/10 * * * *", misfire = MisfirePolicy.FIRE_ONCE)
        once.clock.advance(Duration.ofHours(3))
        assertEquals(1, once.svc.tick().fired)
        assertEquals(1, once.enq.requests.size)                           // not 18 catch-up runs
        assertEquals(0, once.svc.tick().fired)
    }

    @Test fun `a disabled tenant's schedules do not fire`() {
        val r = Rig(); val s = r.create()
        r.tenants.disabled = setOf(Fx.tenantA)
        r.clock.advance(Duration.ofSeconds(35))
        val t = r.svc.tick()
        assertEquals(1, t.skippedTenant); assertEquals(0, t.fired)
        assertTrue(r.enq.requests.isEmpty())
        assertEquals("SKIPPED_TENANT_DISABLED", r.store.get(Fx.tenantA, s.id)!!.lastRunStatus)
    }

    @Test fun `an unreadable tenant state leaves the schedule due for the next tick`() {
        val r = Rig(); val s = r.create()
        r.tenants.throwing = true
        r.clock.advance(Duration.ofSeconds(35))
        r.svc.tick()
        assertTrue(r.enq.requests.isEmpty())
        assertEquals(Instant.parse("2026-10-05T00:01:00Z"), r.store.get(Fx.tenantA, s.id)!!.nextRunAt)
        r.tenants.throwing = false
        assertEquals(1, r.svc.tick().fired)
    }

    @Test fun `a transient enqueue failure keeps the fire pending and the next tick retries it with the same key`() {
        val r = Rig(); val s = r.create()
        r.enq.next = { EnqueueOutcome.Failed("DEPENDENCY_UNAVAILABLE", true) }
        r.clock.advance(Duration.ofSeconds(35))
        assertEquals(1, r.svc.tick().enqueueFailed)
        assertEquals(Instant.parse("2026-10-05T00:01:00Z"), r.store.get(Fx.tenantA, s.id)!!.pendingFireAt)
        r.enq.next = { EnqueueOutcome.Enqueued("run-9") }
        assertEquals(0, r.svc.tick().retried)                             // too soon to retry
        r.clock.advance(Duration.ofSeconds(31))
        val t = r.svc.tick()
        assertEquals(1, t.retried)
        assertEquals(r.enq.requests[0].idempotencyKey, r.enq.requests[1].idempotencyKey)
        assertNull(r.store.get(Fx.tenantA, s.id)!!.pendingFireAt)
    }

    @Test fun `a crash between claim and confirmation is repaired and a duplicate answer counts as confirmed`() {
        val r = Rig(); val s = r.create()
        r.enq.next = { throw IllegalStateException("node died") }
        r.clock.advance(Duration.ofSeconds(35))
        r.svc.tick()
        assertTrue(r.store.get(Fx.tenantA, s.id)!!.pendingFireAt != null)
        r.enq.next = { EnqueueOutcome.Duplicate("run-1") }
        r.clock.advance(Duration.ofMinutes(1))
        r.svc.tick()
        assertNull(r.store.get(Fx.tenantA, s.id)!!.pendingFireAt)
    }

    @Test fun `a permanent refusal drops the pending fire instead of retrying forever`() {
        val r = Rig(); val s = r.create()
        r.enq.next = { EnqueueOutcome.Failed("FORBIDDEN", false) }
        r.clock.advance(Duration.ofSeconds(35))
        r.svc.tick()
        val after = r.store.get(Fx.tenantA, s.id)!!
        assertNull(after.pendingFireAt)
        assertEquals("FAILED_FORBIDDEN", after.lastRunStatus)
        r.clock.advance(Duration.ofSeconds(60)); r.svc.tick()
        assertEquals(2, r.enq.requests.size)                              // the next minute's fire, not a retry of the first
        assertTrue(r.enq.requests[0].idempotencyKey != r.enq.requests[1].idempotencyKey)
    }

    @Test fun `the scheduler itself never runs business logic - it only talks to the enqueuer`() {
        val r = Rig(); r.create(target = ScheduleTarget.Action("sendReport"))
        r.clock.advance(Duration.ofSeconds(35)); r.svc.tick()
        assertEquals(ScheduleTarget.Action("sendReport"), r.enq.requests.single().target)
    }

    @Test fun `ids of different tenants never collide in the store`() {
        val r = Rig(); val a = r.create()
        val b = (r.svc.create(Fx.ctx(tenant = Fx.tenantB), ScheduleSpec(Fx.appB, "x", "* * * * *", "UTC", ScheduleTarget.Workflow("w"))) as ScheduleResult.Ok).value
        r.clock.advance(Duration.ofSeconds(35))
        assertEquals(2, r.svc.tick().fired)
        assertEquals(setOf(Fx.tenantA, Fx.tenantB), r.enq.requests.map { it.tenantId }.toSet())
        assertEquals(setOf(a.id, b.id), r.enq.requests.map { it.scheduleId }.toSet())
        assertTrue(UUID.randomUUID() != a.id)
    }
}
