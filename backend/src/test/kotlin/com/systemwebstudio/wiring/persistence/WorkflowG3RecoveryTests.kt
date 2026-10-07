package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.workflow.CompensationState
import com.systemwebstudio.logic.workflow.StepStatus
import com.systemwebstudio.logic.workflow.WorkflowJob
import com.systemwebstudio.logic.workflow.WorkflowResult
import com.systemwebstudio.logic.workflow.WorkflowRunStatus
import com.systemwebstudio.logic.workflow.act
import com.systemwebstudio.logic.workflow.wf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * G3 (restart / recovery), scenarios 01-06, on REAL infrastructure only: PostgreSQL (V29, Flyway) through [JdbcWorkflowRunStore] and [JdbcActionRunStore], RabbitMQ 4
 * through [com.systemwebstudio.integration.queue.workflow.AmqpWorkflowQueue], the real WorkflowEngine, WorkflowWorker and DefaultActionRuntime (see [G3TestBase] for the
 * isolation rules). The only fake is the effect sink (`FakeDataPort`, the C3 side). Crash boundaries are test code only; the production classes carry no testing hook.
 */
class WorkflowG3RecoveryTests : G3TestBase() {
    // ---- G3-01: persisted success before the ACK --------------------------------------------------------------------

    @Test
    fun `G3-01 a step recorded as SUCCEEDED whose consumer dies before the ack is redelivered and its effect is not repeated`() {
        val b = broker()
        val crashing = CrashableQueue(amqp(b))
        val first = process(listOf(two), crashing)
        val id = first.start("two", "g3-01")                                   // job for step a is published and confirmed by the broker

        crashing.crashOnNextAck = true
        assertThat(first.worker.runOnce()).isTrue()                            // step a runs, SUCCEEDED is persisted, job b is published - then the consumer dies before the ack
        assertThat(crashing.crashes.get()).isEqualTo(1)
        assertThat(first.writes("w1")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_run_steps WHERE tenant_id = ? AND run_id = ? AND step_id = 'a'", String::class.java, Fx.tenantA, id)).isEqualTo("SUCCEEDED")

        val secondQueue = CrashableQueue(amqp(b))
        val second = process(listOf(two), secondQueue)                         // a new worker, with no memory, over the same database and the same broker queue
        second.drain()

        assertThat(secondQueue.polled.map { it.deliveryCount }).describedAs("the unacked job of step a really came back").contains(2)
        val redelivered = secondQueue.polled.filter { it.deliveryCount >= 2 }.mapNotNull { WorkflowJob.decode(it.body) }
        assertThat(redelivered.map { it.runId to it.stepId }).describedAs("the redelivered message is the job of step a of this run").contains(id to "a")
        assertThat(total("w1", first, second)).describedAs("effect of step a, in all processes").isEqualTo(1)
        assertThat(total("w2", first, second)).isEqualTo(1)
        val run = second.stored(id)
        assertThat(run.status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(run.steps["a"]!!.status).isEqualTo(StepStatus.SUCCEEDED)
        assertThat(run.steps["a"]!!.attempt).isEqualTo(1)
        assertThat(run.steps["b"]!!.status).isEqualTo(StepStatus.SUCCEEDED)
        assertThat(run.leaseOwner).isNull()
        eventuallyEmpty(b)
    }

    @Test
    fun `G3-01 a run that finished in the persisted state but whose last message was never acked is a no-op when it comes back`() {
        val b = broker()
        val crashing = CrashableQueue(amqp(b))
        val first = process(listOf(one), crashing)
        val id = first.start("one", "g3-01b")
        crashing.crashOnNextAck = true
        first.worker.runOnce()                                                 // the run is SUCCEEDED in PostgreSQL; its only message was not acknowledged
        assertThat(first.stored(id).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        val versionBefore = jdbc.queryForObject("SELECT version FROM workflow_runs WHERE tenant_id = ? AND run_id = ?", Long::class.java, Fx.tenantA, id)

        val secondQueue = CrashableQueue(amqp(b))
        val second = process(listOf(one), secondQueue)
        second.drain()

        assertThat(secondQueue.polled.map { it.deliveryCount }).contains(2)
        assertThat(total("w1", first, second)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT version FROM workflow_runs WHERE tenant_id = ? AND run_id = ?", Long::class.java, Fx.tenantA, id)).describedAs("the redelivery wrote nothing").isEqualTo(versionBefore)
        eventuallyEmpty(b)
    }

    // ---- G3-02: duplicate delivery ----------------------------------------------------------------------------------

    @Test
    fun `G3-02 the same job delivered several times, also to two workers at once, produces one effect per step`() {
        val b = broker()
        val publisher = process(listOf(two), amqp(b))
        val id = publisher.start("two", "g3-02")
        val jobA = WorkflowJob.forStep(Fx.tenantA, id, "a")
        repeat(3) { publisher.queue.publish(jobA) }                            // four copies of job a on the broker in all
        val w1 = process(listOf(two), amqp(b)); val w2 = process(listOf(two), amqp(b))

        val pool = java.util.concurrent.Executors.newFixedThreadPool(2).also { pool2 -> own(AutoCloseable { pool2.shutdownNow() }) }
        val done = listOf(pool.submit<Int> { w1.drain(800) }, pool.submit<Int> { w2.drain(800) })
        done.forEach { it.get(60, TimeUnit.SECONDS) }

        assertThat(total("w1", w1, w2, publisher)).isEqualTo(1)
        assertThat(total("w2", w1, w2, publisher)).isEqualTo(1)
        val run = w1.stored(id)
        assertThat(run.status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(run.steps["a"]!!.attempt).isEqualTo(1)
        assertThat(run.steps["b"]!!.attempt).isEqualTo(1)
        eventuallyEmpty(b)

        // the same jobs again after the run ended: still a no-op, and not a single write to the row
        val version = jdbc.queryForObject("SELECT version FROM workflow_runs WHERE tenant_id = ? AND run_id = ?", Long::class.java, Fx.tenantA, id)
        w1.queue.publish(jobA); w1.queue.publish(WorkflowJob.forStep(Fx.tenantA, id, "b"))
        w1.drain()
        assertThat(total("w1", w1, w2, publisher)).isEqualTo(1)
        assertThat(total("w2", w1, w2, publisher)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT version FROM workflow_runs WHERE tenant_id = ? AND run_id = ?", Long::class.java, Fx.tenantA, id)).isEqualTo(version)
        eventuallyEmpty(b)
    }

    // ---- G3-03: ambiguous mutation after send ----------------------------------------------------------------------------

    private val saga = wf(
        "saga",
        act("s1", "w1", comp = "c1"), act("s2", "w2", comp = "c2"),
        act("s3", "w3", comp = "c3", onError = "h", timeout = Duration.ofMillis(400)),
        act("h", "w4")
    )

    /** the write of w3 is dispatched (recorded by the sink) and then never answers: the outcome is unknown */
    @Test
    fun `G3-03 a mutation that was sent and timed out is UNKNOWN and never retried, routed to onError or compensated, earlier steps are compensated`() {
        val b = broker()
        val p = process(listOf(saga), amqp(b))
        hangOnW3(p)
        val id = p.start("saga", "g3-03")
        p.drain(1500)

        val run = p.stored(id)
        assertThat(run.status).isEqualTo(WorkflowRunStatus.FAILED)
        assertThat(run.errorCode).isEqualTo(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
        assertThat(run.steps["s3"]!!.status).isEqualTo(StepStatus.FAILED)
        assertThat(run.steps["s3"]!!.compensated).isFalse()
        assertThat(run.steps.containsKey("h")).describedAs("no onError route after an unknown outcome").isFalse()
        assertThat(run.compensation).isEqualTo(CompensationState.DONE)
        assertThat(run.steps["s1"]!!.compensated).isTrue(); assertThat(run.steps["s2"]!!.compensated).isTrue()
        assertThat(p.data.writes.map { it.second.queryRef }).containsExactly("w1", "w2", "w3", "c2", "c1")
        assertActionRunUnknown("w3")

        // recovery: the same jobs again, a brand-new process, a day later with a full sweep - nothing is sent a second time
        val q2 = amqp(b); val again = process(listOf(saga), q2)
        q2.publish(WorkflowJob.forStep(Fx.tenantA, id, "s3")); q2.publish(WorkflowJob.forStep(Fx.tenantA, id, WorkflowJob.COMPENSATE))
        clock.advance(Duration.ofDays(1))
        again.engine.sweep(); again.drain()
        assertThat(again.data.writes).describedAs("nothing was retried, routed or compensated again").isEmpty()
        assertThat(again.stored(id).status).isEqualTo(WorkflowRunStatus.FAILED)
        assertThat(again.stored(id).errorCode).isEqualTo(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
        eventuallyEmpty(b)
    }

    @Test
    fun `G3-03 a worker that dies after the mutation was sent leaves an unknown outcome, the next worker neither resends nor compensates it, and the old worker cannot overwrite it`() {
        val b = broker()
        val dying = process(listOf(saga), amqp(b), staleAfter = Duration.ofMinutes(2))
        val gate = hangOnW3(dying)
        val id = dying.start("saga", "g3-03c")

        // the worker takes s1, s2 and then s3, whose write is sent and never answers (its thread is stuck: from the outside the process is dead)
        val stuck = inBackground { dying.drain(300) }
        val end = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (dying.writes("w3") == 0 && System.nanoTime() < end) Thread.sleep(25)
        assertThat(dying.writes("w3")).describedAs("the mutation was dispatched").isEqualTo(1)
        assertThat(dying.stored(id).steps["s3"]!!.status).isEqualTo(StepStatus.RUNNING)
        assertThat(dying.stored(id).leaseOwner).isEqualTo(dying.workerId)

        // time passes: the lease runs out and the action run is old enough for the action-run recovery to treat it as abandoned (C0's ActionRunRecovery tick)
        clock.advance(Duration.ofMinutes(11))
        val survivor = process(listOf(saga), amqp(b))
        val abandoned = survivor.actionRuns.sweepStale(clock.instant().minus(Duration.ofMinutes(10)), clock.instant())
        // the sweep is table-wide (like the production timer); the base class guarantees no foreign RUNNING row exists, so the count is exactly ours
        assertThat(abandoned).describedAs("the RUNNING mutating action run of the dead worker").isEqualTo(1)
        assertActionRunUnknown("w3")                                           // and this is ours: FAILED, mutating, IDEMPOTENCY_OUTCOME_UNKNOWN, retryable=false
        assertThat(survivor.engine.sweep().republished).isEqualTo(1)           // the lease ran out: the run is taken over
        survivor.drain(1500)
        survivor.engine.sweep(); survivor.drain(800)

        val run = survivor.stored(id)
        assertThat(run.status).isEqualTo(WorkflowRunStatus.FAILED)
        assertThat(run.errorCode).isEqualTo(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
        assertThat(run.steps["s3"]!!.compensated).isFalse()
        assertThat(run.steps.containsKey("h")).isFalse()
        assertThat(run.steps["s1"]!!.compensated).isTrue(); assertThat(run.steps["s2"]!!.compensated).isTrue()
        assertThat(survivor.writes("w3")).describedAs("the survivor never sent the mutation again").isZero()
        assertThat(total("w3", dying, survivor)).isEqualTo(1)
        assertThat(survivor.data.writes.map { it.second.queryRef }).containsExactly("c2", "c1")
        val settled = jdbc.queryForObject("SELECT version FROM workflow_runs WHERE tenant_id = ? AND run_id = ?", Long::class.java, Fx.tenantA, id)

        // the old worker finally comes back from its hang: its late answer is dropped, the stored outcome does not change and nothing is sent again
        gate.countDown()
        stuck.get(60, TimeUnit.SECONDS)
        assertThat(jdbc.queryForObject("SELECT version FROM workflow_runs WHERE tenant_id = ? AND run_id = ?", Long::class.java, Fx.tenantA, id)).isEqualTo(settled)
        assertThat(survivor.stored(id).status).isEqualTo(WorkflowRunStatus.FAILED)
        assertThat(total("w3", dying, survivor)).isEqualTo(1)
        eventuallyEmpty(b)
    }

    // ---- G3-04..06: crash after the claim, cancel while queued, cancel while running -----------------------------------------

    @Test
    fun `G3-04 a worker that dies after claiming a step and before running it is replaced when its lease runs out, and its late return changes nothing`() {
        val b = broker()
        val dying = process(listOf(one), amqp(b), staleAfter = Duration.ofMinutes(2))
        val gate = hangBeforeExecute(dying)
        val id = dying.start("one", "g3-04")
        val stuck = inBackground { dying.drain(300) }
        val end = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (dying.stored(id).steps["a"]?.status != StepStatus.RUNNING && System.nanoTime() < end) Thread.sleep(25)
        val claimed = dying.stored(id)
        assertThat(claimed.steps["a"]!!.status).isEqualTo(StepStatus.RUNNING)
        assertThat(claimed.leaseOwner).isEqualTo(dying.workerId)
        assertThat(claimed.leaseUntil).isEqualTo(clock.instant().plus(Duration.ofMinutes(2)))
        assertThat(dying.writes("w1")).describedAs("claimed but not executed").isZero()

        val survivor = process(listOf(one), amqp(b))
        assertThat(survivor.engine.sweep().republished).describedAs("a valid lease is respected").isZero()
        clock.advance(Duration.ofMinutes(3))                                     // the dead worker stopped renewing: the lease ran out
        assertThat(survivor.engine.sweep().republished).isEqualTo(1)
        survivor.drain(1500)
        val done = survivor.stored(id)
        assertThat(done.status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(done.steps["a"]!!.attempt).isEqualTo(2)
        assertThat(done.leaseOwner).isNull()
        assertThat(survivor.writes("w1")).isEqualTo(1)
        val settled = version(id)

        gate.countDown()                                                         // the old owner wakes up and carries on with its stale claim
        stuck.get(60, TimeUnit.SECONDS)
        assertThat(total("w1", dying, survivor)).describedAs("the derived idempotency key replays the recorded result").isEqualTo(1)
        assertThat(version(id)).describedAs("the old owner's late outcome is dropped").isEqualTo(settled)
        assertThat(survivor.stored(id).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        eventuallyEmpty(b)
    }

    private val cancellable = wf("cc", act("a", "w1", comp = "c1"), act("b", "w2"), compensateOnCancel = true)

    @Test
    fun `G3-05 a run cancelled while its job is still queued never executes, and the stale message is acknowledged`() {
        val b = broker()
        val p = process(listOf(cancellable), amqp(b))
        val id = p.start("cc", "g3-05")
        val cancelled = p.engine.cancel(ctx, id) as WorkflowResult.Ok
        assertThat(cancelled.value.status).isEqualTo(WorkflowRunStatus.CANCELLED)
        val other = process(listOf(cancellable), amqp(b))
        other.drain()
        assertThat(total("w1", p, other)).isZero()
        assertThat(other.stored(id).status).isEqualTo(WorkflowRunStatus.CANCELLED)
        assertThat(other.stored(id).leaseOwner).isNull()
        eventuallyEmpty(b)
    }

    @Test
    fun `G3-06 a run cancelled while a step is running ends CANCELLED with no lease, the step that was already running is compensated and nothing follows it`() {
        val b = broker()
        val running = process(listOf(cancellable), amqp(b))
        val gate = hangBeforeExecute(running)
        val id = running.start("cc", "g3-06")
        val stuck = inBackground { running.drain(300) }
        val end = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (running.stored(id).steps["a"]?.status != StepStatus.RUNNING && System.nanoTime() < end) Thread.sleep(25)
        assertThat(running.stored(id).leaseOwner).isEqualTo(running.workerId)

        val operator = process(listOf(cancellable), amqp(b))
        assertThat((operator.engine.cancel(ctx, id) as WorkflowResult.Ok).value.status).isEqualTo(WorkflowRunStatus.CANCELLED)
        assertThat(operator.stored(id).leaseOwner).describedAs("cancelling releases the lease").isNull()

        gate.countDown()                                                         // the step that was already running finishes after the cancel
        stuck.get(60, TimeUnit.SECONDS)
        operator.drain(500)
        val run = operator.stored(id)
        assertThat(run.status).isEqualTo(WorkflowRunStatus.CANCELLED)
        assertThat(run.leaseOwner).isNull()
        assertThat(total("w2", running, operator)).describedAs("the step after the cancelled one never starts").isZero()
        assertThat(total("w1", running, operator)).describedAs("the step that was running had its effect once").isEqualTo(1)
        assertThat(total("c1", running, operator)).describedAs("and it is compensated (compensateOnCancel)").isEqualTo(1)
        eventuallyEmpty(b)
    }

    private fun assertActionRunUnknown(actionId: String) {
        val row = jdbc.queryForMap("SELECT status, result::text AS result, mutating FROM action_runs WHERE tenant_id = ? AND action_id = ?", Fx.tenantA, actionId)
        assertThat(row["status"]).isEqualTo("FAILED")
        assertThat(row["mutating"]).isEqualTo(true)
        assertThat(row["result"].toString()).contains(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
        assertThat(row["result"].toString()).containsPattern("\"retryable\"\\s*:\\s*false")
    }
}
