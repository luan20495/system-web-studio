package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.approval.DecisionKind
import com.systemwebstudio.logic.action.PrincipalSpec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Sweeper fairness and dead-letter isolation (V2 hardening): a failing run, a big tenant or a poison message must not delay healthy runs,
 * push them into the dead-letter queue or make the sweeper spin.
 */
class WorkflowSweeperTests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }

    private val actions = listOf("w1", "w2").map { Fx.write(it) }
    private val two = wf("two", act("a", "w1"), act("b", "w2"))
    private val twoB = WorkflowDefinition("twoB", Fx.tenantB, Fx.appA, steps = listOf(act("a", "w1", next = "b"), act("b", "w2")))

    private fun rig(
        maxProcessFailures: Int = 5, perTenant: Int = Int.MAX_VALUE, minInterval: Duration = Duration.ofSeconds(5), approvalInterval: Duration = Duration.ofSeconds(60),
        vararg w: WorkflowDefinition = arrayOf(two, twoB)
    ) = WorkflowRig(w.toList(), actions, executor, maxProcessFailures = maxProcessFailures, sweepPerTenant = perTenant, sweepMinInterval = minInterval, sweepApprovalInterval = approvalInterval)

    /** Starts [n] runs that stay PENDING without a message (the broker is "down" while they are created), then makes them stale. */
    private fun staleRuns(r: WorkflowRig, wfId: String, n: Int, tenant: UUID, prefix: String): List<UUID> {
        r.queue.failPublish = true
        val ids = (1..n).map { i -> r.advance(Duration.ofSeconds(1)); r.startOk(wfId, key = "$prefix$i", ctx = r.ctx(tenant = tenant)) }
        r.queue.failPublish = false
        r.advance(Duration.ofMinutes(3))
        return ids
    }

    // ---- fair claim ----------------------------------------------------------------------------------------------------

    @Test fun `a tenant with a large backlog cannot starve another tenant`() {
        val r = rig()
        val a = staleRuns(r, "two", 20, Fx.tenantA, "a")
        val b = staleRuns(r, "twoB", 1, Fx.tenantB, "b").single()
        val rep = r.engine.sweep(limit = 4)
        assertEquals(4, rep.examined)
        assertNotNull(r.stored(b, Fx.tenantB).lastSweptAt)   // tenant B's only run is examined in the first small batch
        assertEquals(3, a.count { r.stored(it).lastSweptAt != null })
    }

    @Test fun `the per-tenant cap bounds one tenant even when it is alone with a backlog`() {
        val r = rig(perTenant = 2)
        val a = staleRuns(r, "two", 10, Fx.tenantA, "a")
        assertEquals(2, r.engine.sweep(limit = 50).examined)
        assertEquals(2, a.count { r.stored(it).lastSweptAt != null })
    }

    @Test fun `oldest eligible run is served first and every run is examined within ceil(n over limit) passes`() {
        val r = rig()
        val ids = staleRuns(r, "two", 10, Fx.tenantA, "a")
        val seen = mutableListOf<UUID>()
        repeat(4) { pass ->
            val before = ids.filter { r.stored(it).lastSweptAt != null }.toSet()
            val rep = r.engine.sweep(limit = 3)
            assertEquals(if (pass < 3) 3 else 1, rep.examined, "pass $pass")
            seen += ids.filter { r.stored(it).lastSweptAt != null && it !in before }
        }
        assertEquals(ids, seen, "runs are served oldest first and none is skipped or served twice")
        assertEquals(10, r.queue.readyCount())
        assertEquals(0, r.engine.sweep(limit = 3).examined, "everything was examined; nothing is eligible again within the interval")
    }

    @Test fun `a run is not examined again before the minimum interval and is again after it`() {
        val r = rig()
        staleRuns(r, "two", 1, Fx.tenantA, "a")
        assertEquals(1, r.engine.sweep().examined)
        assertEquals(0, r.engine.sweep().examined)
        r.advance(Duration.ofSeconds(4)); assertEquals(0, r.engine.sweep().examined)
        r.advance(Duration.ofSeconds(1)); assertEquals(1, r.engine.sweep().examined)
    }

    @Test fun `runs whose publish fails are backed off and do not hog the next passes`() {
        val r = rig()
        val a = staleRuns(r, "two", 12, Fx.tenantA, "a")
        r.queue.failPublish = true
        val first = r.engine.sweep(limit = 4)
        assertEquals(4, first.examined); assertEquals(4, first.failed)
        val failing = a.filter { r.stored(it).sweepFailures == 1 }
        assertEquals(4, failing.size)
        r.queue.failPublish = false
        r.advance(Duration.ofSeconds(6))   // past the minimum re-examination interval (5s) but inside the 10s failure backoff
        // the broker is back, but the four failing runs are in their backoff: the next passes serve the others, 4 at a time
        var published = 0
        repeat(2) { val rep = r.engine.sweep(limit = 4); assertEquals(4, rep.examined); assertEquals(0, rep.failed); published += rep.republished }
        assertEquals(8, published)
        assertTrue(failing.all { r.stored(it).sweepFailures == 1 }, "failing runs were not retried inside their backoff")
        assertEquals(0, r.engine.sweep(limit = 4).examined)
        // after the backoff they come back and succeed; success clears the failure count
        r.advance(Duration.ofSeconds(5))
        assertEquals(4, r.engine.sweep(limit = 4).republished)
        assertTrue(failing.all { r.stored(it).sweepFailures == 0 && r.stored(it).notBefore == null })
        assertEquals(12, r.queue.readyCount())
    }

    @Test fun `sweep backoff of a repeatedly failing run is exponential with a floor and a cap`() {
        val r = rig()
        val id = staleRuns(r, "two", 1, Fx.tenantA, "a").single()
        r.queue.failPublish = true
        val delays = mutableListOf<Long>()
        repeat(4) {
            val t0 = r.now
            r.engine.sweep()
            delays += Duration.between(t0, r.stored(id).notBefore!!).seconds
            r.advance(Duration.ofSeconds(delays.last()))
        }
        assertEquals(listOf(10L, 20L, 40L, 80L), delays)
        assertTrue(delays.all { it >= 1 }, "never below the floor")
    }

    @Test fun `a run that only waits for an approval is polled at the slower approval interval`() {
        val flow = wf("ap", WorkflowStep("okay", StepKind.APPROVAL, approval = ApprovalSpec("Release", listOf(PrincipalSpec.User(Fx.user2, null)), 1, Duration.ofDays(2))), act("b", "w2"))
        val r = WorkflowRig(listOf(flow), actions, executor, wireApprovalListener = false, sweepApprovalInterval = Duration.ofSeconds(60),
            principals = com.systemwebstudio.logic.action.FakePrincipals(directory = setOf(Fx.user, Fx.user2)))
        val id = r.startOk("ap"); r.drain()
        assertEquals(WorkflowRunStatus.WAITING, r.view(id).status)
        r.advance(Duration.ofSeconds(10))
        assertEquals(1, r.engine.sweep().examined)
        r.advance(Duration.ofSeconds(30)); assertEquals(0, r.engine.sweep().examined)
        r.advance(Duration.ofSeconds(31)); assertEquals(1, r.engine.sweep().examined)
        // …and a lost decision callback is still reconciled
        val approvalId = r.stored(id).currentStep!!.approvalId!!
        r.approvals.decide(r.ctx(Fx.user2), approvalId, DecisionKind.APPROVE, null)
        r.advance(Duration.ofSeconds(61))
        assertEquals(1, r.engine.sweep().approvalsReconciled)
    }

    // ---- poison message / dead-letter isolation -------------------------------------------------------------------------

    @Test fun `a poison run is retried with growing backoff and only its own message is dead-lettered`() {
        val r = rig(maxProcessFailures = 5)
        val healthy = (1..3).map { r.startOk("two", key = "h$it") }
        val poison = r.startOk("two", key = "poison")
        r.runStore.poison += poison
        r.drain()
        assertTrue(healthy.all { r.view(it).status == WorkflowRunStatus.SUCCEEDED })
        assertEquals(WorkflowRunStatus.PENDING, r.view(poison).status)
        assertEquals(1, r.stored(poison).processFailures)
        assertEquals(0, r.queue.deadLetterBodies().size)
        assertEquals(0, r.queue.readyCount(), "nothing is requeued immediately")

        val backoffs = mutableListOf(Duration.between(r.now, r.stored(poison).notBefore!!).seconds)
        for (i in 2..4) {
            r.advance(Duration.ofMinutes(11))
            assertEquals(1, r.engine.sweep().republished)
            r.drain()
            assertEquals(i, r.stored(poison).processFailures)
            backoffs += Duration.between(r.now, r.stored(poison).notBefore!!).seconds
        }
        assertEquals(listOf(10L, 20L, 40L, 80L), backoffs)
        assertEquals(0, r.queue.deadLetterBodies().size, "still within the budget")

        r.advance(Duration.ofMinutes(11)); r.engine.sweep(); r.drain()
        assertEquals(WorkflowRunStatus.FAILED, r.view(poison).status)
        assertEquals("DEAD_LETTERED", r.view(poison).errorCode)
        assertEquals(1, r.queue.deadLetterBodies().size)
        assertTrue(r.queue.deadLetterBodies().single().contains(poison.toString()), "only the poison run's message is dead-lettered")
        assertTrue(healthy.all { r.view(it).status == WorkflowRunStatus.SUCCEEDED })
        assertEquals(1, r.engine.let { r.worker.drainDeadLetters() })
        assertEquals(WorkflowRunStatus.FAILED, r.view(poison).status)      // a late dead letter changes nothing
        assertEquals(3, r.writes("w1")); assertEquals(3, r.writes("w2"))
    }

    @Test fun `a message that arrives while its run is backing off does not count as another failure`() {
        val r = rig()
        val poison = r.startOk("two", key = "p")
        r.runStore.poison += poison
        r.drain()
        assertEquals(1, r.stored(poison).processFailures)
        r.queue.publishRaw(WorkflowJob.forStep(Fx.tenantA, poison, "a").encode())
        r.queue.publishRaw(WorkflowJob.forStep(Fx.tenantA, poison, "a").encode())
        r.drain()
        assertEquals(1, r.stored(poison).processFailures, "within the backoff the run is left alone")
        assertEquals(0, r.queue.deadLetterBodies().size)
    }

    @Test fun `the failure budget is explicit and a budget of one fails the run on the first poison message`() {
        val r = rig(maxProcessFailures = 1)
        val poison = r.startOk("two", key = "p"); val ok = r.startOk("two", key = "ok")
        r.runStore.poison += poison
        r.drain()
        assertEquals(WorkflowRunStatus.FAILED, r.view(poison).status)
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(ok).status)
        assertEquals(1, r.queue.deadLetterBodies().size)
    }

    @Test fun `a dead letter counts as one failure of its run and does not kill a healthy run`() {
        val r = rig(maxProcessFailures = 3)
        val id = r.startOk("two")
        val body = WorkflowJob.forStep(Fx.tenantA, id, "a").encode()
        assertTrue(r.engine.failFromDeadLetter(body))
        assertEquals(1, r.stored(id).processFailures)
        assertEquals(WorkflowRunStatus.PENDING, r.view(id).status)
        r.advance(Duration.ofMinutes(11)); r.engine.sweep(); r.drain()      // the broker's give-up was transient: the run finishes
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertEquals(0, r.stored(id).processFailures, "progress resets the count")
        // three dead letters in a row do fail a run that makes no progress
        val stuck = r.startOk("two", key = "stuck")
        repeat(3) { r.advance(Duration.ofMinutes(11)); r.engine.failFromDeadLetter(WorkflowJob.forStep(Fx.tenantA, stuck, "a").encode()) }
        assertEquals(WorkflowRunStatus.FAILED, r.view(stuck).status)
        assertEquals("DEAD_LETTERED", r.view(stuck).errorCode)
    }

    @Test fun `a dead letter naming an unknown or foreign run is ignored`() {
        val r = rig()
        val id = r.startOk("two")
        assertTrue(r.engine.failFromDeadLetter(WorkflowJob.forStep(UUID.randomUUID(), id, "a").encode()))       // wrong tenant
        assertTrue(r.engine.failFromDeadLetter(WorkflowJob.forStep(Fx.tenantA, UUID.randomUUID(), "a").encode())) // no such run
        assertTrue(r.engine.failFromDeadLetter("garbage"))
        assertEquals(0, r.stored(id).processFailures)
        assertEquals(WorkflowRunStatus.PENDING, r.view(id).status)
    }

    @Test fun `a sweep over runs that all fail does not throw and leaves healthy ones for later passes`() {
        val r = rig()
        val ids = staleRuns(r, "two", 6, Fx.tenantA, "a")
        r.queue.failPublish = true
        val rep = r.engine.sweep(limit = 6)
        assertEquals(6, rep.failed)
        assertFalse(ids.any { r.stored(it).notBefore == null })
        assertNull(r.stored(ids.first()).currentStep?.errorCode)
    }
}
