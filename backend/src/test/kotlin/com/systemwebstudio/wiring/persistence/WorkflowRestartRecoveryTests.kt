package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.logic.action.ActionActor
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.PortOutcome
import com.systemwebstudio.logic.workflow.CompensationState
import com.systemwebstudio.logic.workflow.RetryPolicy
import com.systemwebstudio.logic.workflow.StepKind
import com.systemwebstudio.logic.workflow.StepStatus
import com.systemwebstudio.logic.workflow.WorkflowDefinition
import com.systemwebstudio.logic.workflow.WorkflowJob
import com.systemwebstudio.logic.workflow.WorkflowRig
import com.systemwebstudio.logic.workflow.WorkflowRunStatus
import com.systemwebstudio.logic.workflow.WorkflowStep
import com.systemwebstudio.logic.workflow.act
import com.systemwebstudio.logic.workflow.wf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Restart safety of C4's real `WorkflowEngine` over the durable run store (V29). A "restart" is a brand-new rig - a new engine, a new in-memory queue, a new
 * action runtime with no memory at all - over the same PostgreSQL rows. The queue is not durable (RabbitMQ is a later phase): what survives is the run state, and
 * the sweeper brings every unfinished run back. These tests prove that doing so never repeats a finished step, never repeats a compensation and never retries or
 * compensates an ambiguous write.
 */
class WorkflowRestartRecoveryTests : DataRuntimeJdbcTestBase() {
    private val executor = Executors.newCachedThreadPool()
    private val workspace = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val ctx = ActionContext(Fx.tenantA, ActionActor(Fx.user), workspaceId = workspace, projectId = Fx.appA, requestId = "req-restart")
    private val actions = listOf("w1", "w2", "w3", "c1", "c2", "c3").map { Fx.write(it) }
    private val saga = wf("saga", act("s1", "w1", comp = "c1"), act("s2", "w2", comp = "c2"), act("s3", "w3", comp = "c3"))

    @BeforeEach
    fun fixtures() {
        // the C4 fixtures use fixed ids: make them real rows (idempotent)
        jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, 'fx-tenant-a', 'A') ON CONFLICT DO NOTHING", Fx.tenantA)
        jdbc.update("INSERT INTO workspaces (id, name, slug, tenant_id) VALUES (?, 'x', 'fx-ws-a', ?) ON CONFLICT DO NOTHING", workspace, Fx.tenantA)
        jdbc.update("INSERT INTO projects (id, workspace_id, name, owner_user_id, tenant_id) VALUES (?, ?, 'p', ?, ?) ON CONFLICT DO NOTHING", Fx.appA, workspace, fx.user().id, Fx.tenantA)
        clearUnfinishedRuns()
    }

    @AfterEach
    fun stop() {
        executor.shutdownNow()
        clearUnfinishedRuns()
    }

    /**
     * The sweeper claims across the whole table, exactly like in production, so a run that an earlier test class left unfinished (with another tenant's fixed or
     * random ids) would be re-driven by THIS test's engine and fakes. Every test therefore starts - and leaves - with no unfinished run, and none of this tenant.
     */
    private fun clearUnfinishedRuns() {
        jdbc.update(
            "DELETE FROM workflow_runs WHERE tenant_id = ? OR status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') OR compensation = 'IN_PROGRESS'", Fx.tenantA
        )
    }

    /** a process: its own engine, queue and action runtime over the shared database */
    private fun process(vararg w: WorkflowDefinition) = WorkflowRig(w.toList(), actions, executor, store = JdbcWorkflowRunStore(jdbc, json))

    private fun WorkflowRig.rowStatus(id: UUID) = jdbc.queryForObject("SELECT status FROM workflow_runs WHERE tenant_id = ? AND run_id = ?", String::class.java, Fx.tenantA, id)

    @Test
    fun `a run whose job was lost with the process is picked up by the next process and finishes without repeating a finished step`() {
        val before = process(wf("two", act("a", "w1"), act("b", "w2")))
        val id = before.startOk("two", ctx = ctx)
        before.worker.runOnce()                                                       // step a ran and was recorded; the job for step b dies with this process
        assertThat(before.writeOrder).containsExactly("w1")
        assertThat(before.step(id, "a")!!.status).isEqualTo(StepStatus.SUCCEEDED)

        val after = process(wf("two", act("a", "w1"), act("b", "w2")))
        assertThat(after.queue.readyCount()).isZero()                                 // nothing came back by itself: the queue is volatile until the RabbitMQ phase
        assertThat(after.engine.sweep().republished).isZero()                         // too fresh to be called lost
        after.advance(Duration.ofMinutes(3))
        assertThat(after.engine.sweep().republished).isEqualTo(1)
        after.drain()

        assertThat(after.view(id).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(after.writeOrder).containsExactly("w2")                           // the finished step a is NOT run again
        assertThat(after.step(id, "a")!!.attempt).isEqualTo(1)
    }

    @Test
    fun `a PENDING run that never got its job is started by the next process`() {
        val before = process(wf("two", act("a", "w1"), act("b", "w2")))
        before.queue.failPublish = true
        val id = before.startOk("two", ctx = ctx)
        assertThat(before.stored(id).status).isEqualTo(WorkflowRunStatus.PENDING)

        val after = process(wf("two", act("a", "w1"), act("b", "w2")))
        after.advance(Duration.ofMinutes(3))
        assertThat(after.engine.sweep().republished).isEqualTo(1)
        after.drain()
        assertThat(after.view(id).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(after.writeOrder).containsExactly("w1", "w2")
    }

    @Test
    fun `a step that was RUNNING when its worker died is retried by the next process as a new attempt of the same idempotency key`() {
        val before = process(wf("cr", act("a", "w1"), act("b", "w2")))
        val id = before.startOk("cr", ctx = ctx)
        val pending = before.stored(id)
        before.queue.poll()                                                           // the job is gone with the dead worker
        assertThat(before.baseStore.compareAndSet(pending, pending.withStep(pending.steps["a"]!!.copy(status = StepStatus.RUNNING, attempt = 1)).copy(status = WorkflowRunStatus.RUNNING))).isTrue()

        val after = process(wf("cr", act("a", "w1"), act("b", "w2")))
        assertThat(after.engine.sweep().republished).isZero()                         // inside its lease: a slow worker is not killed
        after.advance(Duration.ofMinutes(3))
        assertThat(after.engine.sweep().republished).isEqualTo(1)
        after.drain()
        assertThat(after.view(id).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(after.step(id, "a")!!.attempt).isEqualTo(2)                        // the attempt counter survived the restart
        assertThat(after.step(id, "b")!!.status).isEqualTo(StepStatus.SUCCEEDED)
    }

    @Test
    fun `a WAIT timer survives the restart and fires once`() {
        val def = wf("w", WorkflowStep("pause", StepKind.WAIT, wait = Duration.ofHours(1)), act("b", "w2"))
        val before = process(def)
        val id = before.startOk("w", ctx = ctx); before.drain()
        assertThat(before.step(id, "pause")!!.status).isEqualTo(StepStatus.WAITING)

        val after = process(def)
        after.advance(Duration.ofMinutes(30))
        assertThat(after.engine.sweep().timersCompleted).isZero()
        after.drain()
        assertThat(after.step(id, "pause")!!.status).isEqualTo(StepStatus.WAITING)
        after.advance(Duration.ofHours(1))
        assertThat(after.engine.sweep().timersCompleted).isEqualTo(1)
        after.drain()
        assertThat(after.view(id).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(after.writeOrder).containsExactly("w2")
    }

    @Test
    fun `a retry in backoff survives the restart and is attempted after its delay with the attempt count kept`() {
        val def = wf("rt", act("a", "w1", retry = RetryPolicy(maxAttempts = 3, initialBackoff = Duration.ofMinutes(5))))
        val before = process(def)
        before.data.script("w1", PortOutcome.Failure("DOWN", true))
        val id = before.startOk("rt", ctx = ctx); before.drain()
        assertThat(before.step(id, "a")!!.status).isEqualTo(StepStatus.RETRY_WAIT)
        assertThat(before.step(id, "a")!!.attempt).isEqualTo(1)

        val after = process(def)
        after.advance(Duration.ofMinutes(10))
        assertThat(after.engine.sweep().retriesPublished).isEqualTo(1)
        after.drain()
        assertThat(after.view(id).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(after.step(id, "a")!!.attempt).isEqualTo(2)
    }

    @Test
    fun `finished runs stay finished and are never executed again by a new process`() {
        val def = wf("two", act("a", "w1"), act("b", "w2"))
        val before = process(def)
        val id = before.startOk("two", ctx = ctx); before.drain()
        val failed = process(wf("bad", act("a", "w3"))).also { it.data.script("w3", PortOutcome.Failure("BOOM", false)) }
        val failedId = failed.startOk("bad", key = "k-bad", ctx = ctx); failed.drain()
        val cancelled = process(wf("cx", act("a", "w1")))
        val cancelledId = cancelled.startOk("cx", key = "k-cx", ctx = ctx)
        cancelled.engine.cancel(ctx, cancelledId)

        val after = process(def, wf("bad", act("a", "w3")), wf("cx", act("a", "w1")))
        after.advance(Duration.ofDays(3))
        after.engine.sweep(); after.drain()
        after.queue.publishRaw(WorkflowJob.forStep(Fx.tenantA, id, "a").encode()); after.drain()     // even a duplicate delivery
        assertThat(after.data.writes).isEmpty()
        assertThat(after.rowStatus(id)).isEqualTo("SUCCEEDED")
        assertThat(after.rowStatus(failedId)).isEqualTo("FAILED")
        assertThat(after.rowStatus(cancelledId)).isEqualTo("CANCELLED")
    }

    @Test
    fun `an ambiguous write stays unknown after a restart, is never retried and is never compensated`() {
        val before = process(saga)
        before.data.script("w3", PortOutcome.Failure(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, false))
        val id = before.startOk("saga", ctx = ctx); before.drain()
        assertThat(before.writeOrder).containsExactly("w1", "w2", "w3", "c2", "c1")           // earlier steps are compensated, the ambiguous one is not
        assertThat(before.view(id).compensation).isEqualTo(CompensationState.DONE)

        val after = process(saga)                                                              // a fresh process that has not heard of the failure: it would happily succeed
        after.advance(Duration.ofDays(1))
        after.engine.sweep(); after.drain()
        after.queue.publishRaw(WorkflowJob.forStep(Fx.tenantA, id, "s3").encode()); after.drain()
        after.queue.publishRaw(WorkflowJob.forStep(Fx.tenantA, id, WorkflowJob.COMPENSATE).encode()); after.drain()
        assertThat(after.data.writes).isEmpty()                                                // nothing retried, nothing compensated twice, no "c3"
        val run = after.stored(id)
        assertThat(run.status).isEqualTo(WorkflowRunStatus.FAILED)
        assertThat(run.errorCode).isEqualTo(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
        assertThat(run.steps["s3"]!!.status).isEqualTo(StepStatus.FAILED)
        assertThat(run.steps["s3"]!!.compensated).isFalse()
        assertThat(run.steps["s1"]!!.compensated).isTrue()
        assertThat(run.steps["s2"]!!.compensated).isTrue()
    }

    @Test
    fun `a compensation that was interrupted resumes after a restart and does not repeat a compensation that already ran`() {
        val before = process(saga)
        before.data.script("w3", PortOutcome.Failure("BOOM", false))
        val id = before.startOk("saga", ctx = ctx)
        before.worker.runOnce(); before.worker.runOnce(); before.worker.runOnce()               // s1, s2, s3 (fails) -> a compensation job is queued
        before.queue.poll()                                                                    // ... and dies with this process
        val stuck = before.stored(id)
        assertThat(stuck.compensation).isEqualTo(CompensationState.IN_PROGRESS)
        // c2 had already run (and was recorded) when the process died
        assertThat(before.baseStore.compareAndSet(stuck, stuck.withStep(stuck.steps["s2"]!!.copy(compensated = true)))).isTrue()

        val after = process(saga)
        after.advance(Duration.ofMinutes(3))
        assertThat(after.engine.sweep().compensationsResumed).isEqualTo(1)
        after.drain()
        assertThat(after.writeOrder).containsExactly("c1")                                    // only the missing compensation: c2 is not run a second time
        assertThat(after.view(id).compensation).isEqualTo(CompensationState.DONE)
        after.queue.publishRaw(WorkflowJob.forStep(Fx.tenantA, id, WorkflowJob.COMPENSATE).encode()); after.drain()
        assertThat(after.writeOrder).containsExactly("c1")
    }

    @Test
    fun `two processes sweeping one database never both take the same run`() {
        val def = wf("two", act("a", "w1"), act("b", "w2"))
        val origin = process(def)
        val ids = (1..4).map { origin.startOk("two", key = "k-$it", ctx = ctx) }
        origin.queue.poll(); origin.queue.poll(); origin.queue.poll(); origin.queue.poll()   // all four jobs lost
        val p1 = process(def); val p2 = process(def)
        p1.advance(Duration.ofMinutes(3)); p2.advance(Duration.ofMinutes(3))
        val first = p1.engine.sweep().republished
        val second = p2.engine.sweep().republished
        assertThat(first + second).isEqualTo(ids.size)                                       // each lost run is republished exactly once
        p1.drain(); p2.drain()
        assertThat(p1.writes("w1") + p2.writes("w1")).isEqualTo(ids.size)
        assertThat(ids.map { origin.rowStatusSafe(it) }).containsOnly("SUCCEEDED")
    }

    private fun WorkflowRig.rowStatusSafe(id: UUID) = rowStatus(id)
}
