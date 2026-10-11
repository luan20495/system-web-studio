package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.ActionDefinition
import com.systemwebstudio.logic.action.ActionHandler
import com.systemwebstudio.logic.action.ActionHandlerRegistry
import com.systemwebstudio.logic.action.ActionPorts
import com.systemwebstudio.logic.action.ActionRig
import com.systemwebstudio.logic.action.ActionType
import com.systemwebstudio.logic.action.FakeAccess
import com.systemwebstudio.logic.action.FakeTenants
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.PortOutcome
import com.systemwebstudio.logic.action.RecordingLogicAudit
import com.systemwebstudio.logic.action.StartWorkflowRequest
import com.systemwebstudio.logic.action.TestClock
import com.systemwebstudio.logic.action.WorkflowStarterPort
import com.systemwebstudio.logic.action.handlers.DefaultActionHandlers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The remaining runtime behaviours the hardening review asks for, at workflow level and in memory (the real PostgreSQL / RabbitMQ / process-kill variants are in the
 * G3 suite): INTERRUPTED and TIMEOUT of a step (mutating = unknown for good, non-mutating = retried within its budget and still routed to onError), queue messages that
 * arrive out of order, and what status / history tells.
 */
class WorkflowHardeningTests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }
    private val actions = listOf("w1", "w2", "w3", "c1", "c2", "c3", "h").map { Fx.write(it) }

    // ---- INTERRUPTED -----------------------------------------------------------------------------------------------------------

    @Test
    fun `a mutating step whose worker is interrupted mid-write is an unknown outcome - no retry, no onError, not compensated, earlier steps are`() {
        val saga = wf(
            "saga", act("s1", "w1", comp = "c1"),
            act("s2", "w2", comp = "c2", retry = RetryPolicy(maxAttempts = 3), onError = "h"),
            act("h", "h")
        )
        val r = WorkflowRig(listOf(saga), actions, executor)
        val dispatched = CountDownLatch(1); val hold = CountDownLatch(1)
        r.data.onWrite = { if (r.data.writes.last().second.queryRef == "w2") { dispatched.countDown(); hold.await(30, TimeUnit.SECONDS) } }
        val id = r.startOk("saga")
        val worker = Thread { r.drain() }.also { it.start() }
        assertThat(dispatched.await(20, TimeUnit.SECONDS)).describedAs("the write of w2 was sent").isTrue()
        worker.interrupt()                                                           // the process is being shut down while the write is in flight
        worker.join(20_000)
        hold.countDown()
        assertThat(worker.isAlive).isFalse()
        r.drain()

        val view = r.view(id)
        assertThat(view.status).isEqualTo(WorkflowRunStatus.FAILED)
        assertThat(view.errorCode).isEqualTo(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
        assertThat(view.steps.first { it.stepId == "s2" }.attempt).describedAs("never retried").isEqualTo(1)
        assertThat(r.writes("h")).describedAs("no onError route").isZero()
        assertThat(r.writes("w2")).describedAs("the mutation was sent once and never again").isEqualTo(1)
        assertThat(r.writes("c2")).describedAs("the ambiguous step is not compensated").isZero()
        assertThat(r.writes("c1")).describedAs("the earlier definite step is").isEqualTo(1)
    }

    // ---- TIMEOUT of a non-mutating step ------------------------------------------------------------------------------------------

    /** NAVIGATE (non-mutating) with a controllable delay: the registry of the rig is wrapped, the production handler does the work. */
    private class Rig2(executor: java.util.concurrent.ExecutorService, flow: WorkflowDefinition) {
        @Volatile var delayMillis = 0L
        val clock = TestClock()
        private var engineRef: WorkflowEngine? = null
        private val starter = object : WorkflowStarterPort {
            override fun start(ctx: com.systemwebstudio.logic.action.ActionContext, request: StartWorkflowRequest): PortOutcome = engineRef!!.start(ctx, request)
        }
        val nav: ActionDefinition = Fx.navigate("nav", inputs = listOf(com.systemwebstudio.logic.action.InputSpec("title", com.systemwebstudio.logic.action.InputType.STRING)))
        val actionRig = ActionRig.build(nav, Fx.write("h"), executor = executor, clock = clock, registry = { d, n, _ ->
            val base = DefaultActionHandlers.registry(Fx.json, ActionPorts(d, n, starter))
            ActionHandlerRegistry.Builder().also { b ->
                base.types.forEach { t ->
                    val h = base[t]!!
                    b.register(if (t == ActionType.NAVIGATE) object : ActionHandler by h {
                        override fun execute(ctx: com.systemwebstudio.logic.action.ActionContext, definition: ActionDefinition, input: com.systemwebstudio.logic.action.ActionInput, run: com.systemwebstudio.logic.action.ActionRun) =
                            h.execute(ctx, definition, input, run).also { if (delayMillis > 0) Thread.sleep(delayMillis) }
                    } else h)
                }
            }.build()
        })
        val queue = InMemoryWorkflowQueue()
        val runs = InMemoryWorkflowRunStore()
        val engine = WorkflowEngine(Fx.json, FakeWorkflowDefs(flow), actionRig.runtime, runs, queue, FakeAccess(), FakeTenants(), RecordingLogicAudit(), null, WorkflowLimits(), clock)
            .also { engineRef = it }
        val worker = WorkflowWorker(engine, queue)
        fun start() = (engine.start(Fx.ctx(), WorkflowStartRequest(engine.let { "flow" }, Fx.obj(), "k-1")) as WorkflowResult.Ok).value.runId
        fun view(id: java.util.UUID) = runs.get(Fx.tenantA, id)!!.toView()
        fun drain() = worker.runUntilIdle()
    }

    @Test
    fun `a non-mutating step that times out is retried within its budget and then succeeds`() {
        val flow = wf("flow", act("a", "nav", timeout = Duration.ofMillis(120), retry = RetryPolicy(maxAttempts = 3, initialBackoff = Duration.ofSeconds(2))))
        val r = Rig2(executor, flow)
        r.delayMillis = 700
        val id = r.start(); r.drain()
        assertThat(r.view(id).steps.single().errorCode).isEqualTo(ActionErrorCodes.TIMEOUT)
        assertThat(r.view(id).status).describedAs("waiting for its backoff, not failed").isEqualTo(WorkflowRunStatus.WAITING)
        r.delayMillis = 0
        r.clock.advance(Duration.ofSeconds(5)); r.engine.sweep(); r.drain()
        val done = r.view(id)
        assertThat(done.status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(done.steps.single().attempt).isEqualTo(2)
    }

    @Test
    fun `a non-mutating step that keeps timing out exhausts its budget and its onError route still runs`() {
        val flow = wf(
            "flow", act("a", "nav", timeout = Duration.ofMillis(100), retry = RetryPolicy(maxAttempts = 2, initialBackoff = Duration.ofSeconds(1)), onError = "h"),
            act("h", "h")
        )
        val r = Rig2(executor, flow)
        r.delayMillis = 600
        val id = r.start(); r.drain()
        r.clock.advance(Duration.ofSeconds(5)); r.engine.sweep(); r.drain()
        val view = r.view(id)
        assertThat(view.steps.first { it.stepId == "a" }.attempt).isEqualTo(2)
        assertThat(view.steps.first { it.stepId == "a" }.errorCode).isEqualTo(ActionErrorCodes.TIMEOUT)
        assertThat(view.steps.any { it.stepId == "h" }).describedAs("the handler step was reached (a TIMEOUT of a read is a definite non-effect)").isTrue()
    }

    // ---- ordering ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `messages of one run delivered out of order still execute the steps in order, once each`() {
        val r = WorkflowRig(listOf(wf("three", act("a", "w1"), act("b", "w2"), act("c", "w3"))), actions, executor)
        val id = r.startOk("three")
        val first = r.queue.poll()!!; r.queue.ack(first)                               // the job of step a is taken away ...
        r.queue.publishRaw(WorkflowJob.forStep(Fx.tenantA, id, "c").encode())          // ... and the later steps arrive first
        r.queue.publishRaw(WorkflowJob.forStep(Fx.tenantA, id, "b").encode())
        r.queue.publishRaw(first.body)                                                // step a last
        r.drain()
        assertThat(r.writeOrder).containsExactly("w1", "w2", "w3")
        assertThat(r.view(id).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(r.view(id).steps.map { it.attempt }).containsExactly(1, 1, 1)
    }

    // ---- status / history ----------------------------------------------------------------------------------------------------------

    @Test
    fun `status tells the history - every step with its state, attempt and code, the run error and the compensation`() {
        val saga = wf("hist", act("s1", "w1", comp = "c1"), act("s2", "w2", retry = RetryPolicy(maxAttempts = 2, initialBackoff = Duration.ofSeconds(1))))
        val r = WorkflowRig(listOf(saga), actions, executor)
        r.data.script("w2", PortOutcome.Failure("DOWN", true), PortOutcome.Failure("DOWN", true))
        val id = r.startOk("hist"); r.drain()
        r.advance(Duration.ofSeconds(5)); r.engine.sweep(); r.drain()
        val view = (r.engine.status(r.ctx(), id) as WorkflowResult.Ok).value
        assertThat(view.status).isEqualTo(WorkflowRunStatus.FAILED)
        assertThat(view.errorCode).isEqualTo("DOWN")
        assertThat(view.steps.map { it.stepId to it.status }).containsExactly("s1" to StepStatus.SUCCEEDED, "s2" to StepStatus.FAILED)
        assertThat(view.steps.map { it.attempt }).containsExactly(1, 2)
        assertThat(view.steps.last().errorCode).isEqualTo("DOWN")
        assertThat(view.compensation).isEqualTo(CompensationState.DONE)
        assertThat(view.finishedAt).isNotNull()
    }

    @Test
    fun `an interrupted worker thread takes no further message - the job stays queued for a live worker`() {
        val r = WorkflowRig(listOf(wf("one", act("a", "w1"))), actions, executor)
        r.startOk("one")
        Thread.currentThread().interrupt()
        try {
            assertThat(r.worker.runOnce()).describedAs("nothing is taken").isFalse()
            assertThat(r.worker.runUntilIdle()).isZero()
        } finally { Thread.interrupted() }                                               // clear the flag for the rest of the test run
        assertThat(r.queue.readyCount()).isEqualTo(1)
        assertThat(r.data.writes).isEmpty()
        r.drain()
        assertThat(r.writes("w1")).isEqualTo(1)
    }
}
