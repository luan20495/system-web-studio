package com.systemwebstudio.wiring.persistence

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.systemwebstudio.integration.queue.workflow.AmqpWorkflowQueue
import com.systemwebstudio.integration.queue.workflow.WorkflowQueueTopology
import com.systemwebstudio.logic.action.AccessPort
import com.systemwebstudio.logic.action.AccessRequest
import com.systemwebstudio.logic.action.ActionActor
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ActionDefinition
import com.systemwebstudio.logic.action.ActionHandler
import com.systemwebstudio.logic.action.ActionHandlerRegistry
import com.systemwebstudio.logic.action.ActionLimits
import com.systemwebstudio.logic.action.ActionPorts
import com.systemwebstudio.logic.action.ActionRunStore
import com.systemwebstudio.logic.action.ActionType
import com.systemwebstudio.logic.action.AuthorizationDecision
import com.systemwebstudio.logic.action.DefaultActionRuntime
import com.systemwebstudio.logic.action.FakeAccess
import com.systemwebstudio.logic.action.FakeDataPort
import com.systemwebstudio.logic.action.FakeDefinitions
import com.systemwebstudio.logic.action.FakeNotifyPort
import com.systemwebstudio.logic.action.FakeTenants
import com.systemwebstudio.logic.action.FakePrincipals
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.approval.ApprovalListener
import com.systemwebstudio.logic.approval.ApprovalService
import com.systemwebstudio.logic.approval.ApprovalStore
import com.systemwebstudio.logic.action.InputResolver
import com.systemwebstudio.logic.action.PortOutcome
import com.systemwebstudio.logic.action.RecordingAudit
import com.systemwebstudio.logic.action.RecordingLogicAudit
import com.systemwebstudio.logic.action.StartWorkflowRequest
import com.systemwebstudio.logic.action.TestClock
import com.systemwebstudio.logic.action.WorkflowStarterPort
import com.systemwebstudio.logic.action.handlers.DefaultActionHandlers
import com.systemwebstudio.logic.limits.TenantRateLimiter
import com.systemwebstudio.logic.workflow.FakeWorkflowDefs
import com.systemwebstudio.logic.workflow.FlakyRunStore
import com.systemwebstudio.logic.workflow.QueueLease
import com.systemwebstudio.logic.workflow.WorkflowDefinition
import com.systemwebstudio.logic.workflow.WorkflowEngine
import com.systemwebstudio.logic.workflow.WorkflowLimits
import com.systemwebstudio.logic.workflow.WorkflowQueue
import com.systemwebstudio.logic.workflow.WorkflowResult
import com.systemwebstudio.logic.workflow.WorkflowRunStore
import com.systemwebstudio.logic.workflow.WorkflowStartRequest
import com.systemwebstudio.logic.workflow.WorkflowWorker
import com.systemwebstudio.logic.workflow.act
import com.systemwebstudio.logic.workflow.wf
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What a process that is killed at an exact point looks like from the inside: an [Error] that no `catch (Exception)` of the production code swallows. */
class ProcessDeath(what: String) : Error(what)

/**
 * A consumer that dies instead of acknowledging (test code only). `crashOnNextAck` closes the channel without acking; `skipNextAck` neither acks nor closes (the
 * connection is lost later, e.g. a broker restart); `dieOnNextAck` throws [ProcessDeath] without acking (a process that is gone, used with a Spring context that is
 * then closed). Everything polled is recorded so a test can prove that a redelivery really happened.
 */
class CrashableQueue(private val inner: WorkflowQueue) : WorkflowQueue by inner {
    @Volatile var crashOnNextAck = false
    @Volatile var skipNextAck = false
    @Volatile var dieOnNextAck = false
    /** a process that died stays dead: it takes no further message */
    @Volatile var dead = false
    val crashes = AtomicInteger()
    val polled = CopyOnWriteArrayList<QueueLease>()
    override fun poll(): QueueLease? = if (dead) null else inner.poll()?.also { polled += it }
    override fun ack(lease: QueueLease) {
        when {
            crashOnNextAck -> { crashOnNextAck = false; crashes.incrementAndGet(); (inner as AmqpWorkflowQueue).dropConsumer() }
            skipNextAck -> { skipNextAck = false; crashes.incrementAndGet() }
            dieOnNextAck -> { dieOnNextAck = false; dead = true; crashes.incrementAndGet(); throw ProcessDeath("died before the ack") }
            else -> inner.ack(lease)
        }
    }
}

/** NAVIGATE (a non-mutating type) with a crash boundary inside its execution. */
private class HookedHandler(private val delegate: ActionHandler, private val hook: () -> (() -> Unit)?) : ActionHandler by delegate {
    override fun execute(ctx: ActionContext, definition: ActionDefinition, input: com.systemwebstudio.logic.action.ActionInput, run: com.systemwebstudio.logic.action.ActionRun) =
        delegate.execute(ctx.also { hook()?.invoke() }, definition, input, run)
}

/**
 * One process (or one Spring context) of the workflow runtime: the real [WorkflowEngine], [WorkflowWorker] and [DefaultActionRuntime] over the stores and the
 * queue it is given. The effect sink [data] is the only fake (C3 stands behind it) and may be shared between processes so that effects are counted across a restart.
 */
class G3Process(
    workflows: List<WorkflowDefinition>, actionDefs: List<ActionDefinition>, val queue: WorkflowQueue, val actionRuns: ActionRunStore, workflowStore: WorkflowRunStore,
    clock: Clock, val ctx: ActionContext, staleAfter: Duration = Duration.ofMinutes(2), maxProcessFailures: Int = 5, val workerId: String = "g3-" + UUID.randomUUID().toString().take(8),
    val data: FakeDataPort = FakeDataPort(), val executor: ExecutorService = Executors.newCachedThreadPool(), sweepMinInterval: Duration = Duration.ofSeconds(5),
    /** when set, the process has approvals on this (durable) store - FQ-WF-01 */ approvalStore: ApprovalStore? = null, approverDirectory: Set<UUID> = setOf(Fx.user, Fx.user2)
) {
    val audit = RecordingLogicAudit()
    val tenants = FakeTenants()
    val runStore = FlakyRunStore(workflowStore)
    val definitions = FakeDefinitions(*actionDefs.map { d -> if (d.trigger == null) d.copy(trigger = Fx.uiTrigger) else d }.toTypedArray())

    /** Test-only crash boundary "after the step was claimed, before the action runs": runs once, on the first ACTION_EXECUTE check of the process. */
    @Volatile var beforeExecute: (() -> Unit)? = null
    /** Same boundary inside a non-mutating action (NAVIGATE). */
    @Volatile var navigateHook: (() -> Unit)? = null
    private val fakeAccess = FakeAccess()
    val access = object : AccessPort {
        override fun check(ctx: ActionContext, request: AccessRequest): AuthorizationDecision {
            if (request.permission == "ACTION_EXECUTE") beforeExecute?.let { hook -> beforeExecute = null; hook() }
            return fakeAccess.check(ctx, request)
        }
    }

    private var engineRef: WorkflowEngine? = null
    private val starter = object : WorkflowStarterPort {
        override fun start(ctx: ActionContext, request: StartWorkflowRequest): PortOutcome = engineRef!!.start(ctx, request)
    }
    private val handlers: ActionHandlerRegistry = DefaultActionHandlers.registry(Fx.json, ActionPorts(data, FakeNotifyPort(), starter)).let { base ->
        ActionHandlerRegistry.Builder().also { b ->
            base.types.forEach { t -> base[t]!!.let { h -> b.register(if (t == ActionType.NAVIGATE) HookedHandler(h) { navigateHook?.also { navigateHook = null } } else h) } }
        }.build()
    }
    val runtime = DefaultActionRuntime(
        definitions, handlers, access, tenants, actionRuns, RecordingAudit(), InputResolver(Fx.json), null, ActionLimits(), clock, executor, 16, TenantRateLimiter.UNLIMITED
    )
    val approvals: ApprovalService? = approvalStore?.let { st ->
        ApprovalService(Fx.json, st, FakePrincipals(directory = approverDirectory), tenants, audit, null, ApprovalListener { a -> engineRef?.onFinal(a) }, clock = clock)
    }
    val engine = WorkflowEngine(
        Fx.json, FakeWorkflowDefs(*workflows.toTypedArray()), runtime, runStore, queue, access, tenants, audit, approvals, WorkflowLimits(), clock, staleAfter,
        TenantRateLimiter.UNLIMITED, maxProcessFailures, sweepMinInterval = sweepMinInterval, workerId = workerId
    ).also { engineRef = it }
    val worker = WorkflowWorker(engine, queue)

    fun start(workflow: String, key: String): UUID = (engine.start(ctx, WorkflowStartRequest(workflow, Fx.obj(), key)) as WorkflowResult.Ok).value.runId

    /** Drains the broker; a message may become visible a moment after it was published or redelivered, so an empty poll is retried for [patienceMillis]. */
    fun drain(patienceMillis: Long = 400): Int {
        var n = 0
        var idleSince = System.nanoTime()
        while (System.nanoTime() - idleSince < Duration.ofMillis(patienceMillis).toNanos()) {
            if (worker.runOnce()) { n++; idleSince = System.nanoTime() } else Thread.sleep(25)
        }
        return n
    }
    fun stored(id: UUID) = runStore.get(Fx.tenantA, id)!!
    fun writes(queryRef: String) = data.writes.count { it.second.queryRef == queryRef }
}

/** The broker of [IntegrationTestBase], one connection for the whole JVM; each test declares its own uniquely named queues on it. */
object SharedBroker {
    private val factory: ConnectionFactory by lazy {
        ConnectionFactory().apply {
            host = IntegrationTestBase.rabbit.host; port = IntegrationTestBase.rabbit.getMappedPort(5672)
            username = "studio"; password = "test-rabbit-123"; isAutomaticRecoveryEnabled = false; connectionTimeout = 5_000
        }
    }
    @Volatile private var shared: Connection? = null
    @Synchronized fun connection(): Connection = shared?.takeIf { it.isOpen } ?: factory.newConnection("g3-tests").also { shared = it }
}

class G3Broker(val topology: WorkflowQueueTopology)

/**
 * Base of the G3 recovery tests: PostgreSQL (V29) through [IntegrationTestBase], a broker (the shared one, or an own container when a subclass overrides
 * [connection]), and the isolation rules every test relies on:
 *  - every test starts and ends with no unfinished workflow run and no RUNNING action run in the table (the stale sweeps are table-wide, exactly as in production,
 *    so a foreign row would be swept - and counted - by this test's sweep), and with none of this tenant's rows;
 *  - every test declares its own uniquely named queue, dead-letter queue and exchange;
 *  - a "crashed" worker thread is waited for before the next test starts, so a zombie can never write into the next test's rows.
 */
abstract class G3TestBase : DataRuntimeJdbcTestBase() {
    protected val workspace: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    protected val ctx = ActionContext(Fx.tenantA, ActionActor(Fx.user), workspaceId = workspace, projectId = Fx.appA, requestId = "req-g3")
    protected val actionDefs: List<ActionDefinition> = listOf("w1", "w2", "w3", "w4", "c1", "c2", "c3").map { Fx.write(it) }
    protected val clock = TestClock()                                              // one clock for every process of a test: leases are compared across processes
    private val opened = CopyOnWriteArrayList<AutoCloseable>()
    private val executors = CopyOnWriteArrayList<ExecutorService>()
    private val releases = CopyOnWriteArrayList<CountDownLatch>()
    private val background = CopyOnWriteArrayList<Future<*>>()

    protected open fun connection(): Connection = SharedBroker.connection()

    @BeforeEach
    fun fixtures() {
        jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, 'fx-tenant-a', 'A') ON CONFLICT DO NOTHING", Fx.tenantA)
        jdbc.update("INSERT INTO workspaces (id, name, slug, tenant_id) VALUES (?, 'x', 'fx-ws-a', ?) ON CONFLICT DO NOTHING", workspace, Fx.tenantA)
        jdbc.update("INSERT INTO projects (id, workspace_id, name, owner_user_id, tenant_id) VALUES (?, ?, 'p', ?, ?) ON CONFLICT DO NOTHING", Fx.appA, workspace, fx.user().id, Fx.tenantA)
        clearRows()
        // regression guard of the G3-03 isolation fault: a stale sweep may only ever see rows of the running test
        assertThat(jdbc.queryForObject("SELECT count(*) FROM action_runs WHERE status = 'RUNNING'", Long::class.java)).describedAs("foreign RUNNING action runs at test start").isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_runs WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') OR compensation = 'IN_PROGRESS'", Long::class.java))
            .describedAs("foreign unfinished workflow runs at test start").isZero()
    }

    @AfterEach
    fun stop() {
        releases.forEach { it.countDown() }                                       // let a "crashed" worker thread end ...
        background.forEach { runCatching { it.get(30, TimeUnit.SECONDS) } }       // ... and wait until it has
        executors.forEach { it.shutdownNow() }
        opened.forEach { runCatching { it.close() } }
        clearRows()
    }

    private fun clearRows() {
        if (jdbc.queryForObject("SELECT to_regclass('public.approvals') IS NOT NULL", Boolean::class.java) == true) jdbc.update("DELETE FROM approvals WHERE tenant_id = ?", Fx.tenantA)
        jdbc.update("DELETE FROM workflow_runs WHERE tenant_id = ? OR status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') OR compensation = 'IN_PROGRESS'", Fx.tenantA)
        jdbc.update("DELETE FROM action_runs WHERE tenant_id = ? OR status = 'RUNNING'", Fx.tenantA)
    }

    // ---- processes -----------------------------------------------------------------------------------------------------

    protected fun process(
        workflows: List<WorkflowDefinition>, queue: WorkflowQueue, staleAfter: Duration = Duration.ofMinutes(2), maxProcessFailures: Int = 5,
        extraActions: List<ActionDefinition> = emptyList(), sweepMinInterval: Duration = Duration.ofSeconds(5)
    ): G3Process = G3Process(
        workflows, actionDefs + extraActions, queue, JdbcActionRunStore(jdbc, json), JdbcWorkflowRunStore(jdbc, json), clock, ctx, staleAfter, maxProcessFailures,
        executor = Executors.newCachedThreadPool().also { executors += it }, sweepMinInterval = sweepMinInterval
    )

    /** A node with approvals on the durable `approvals` table (FQ-WF-01): everything else as [process]. */
    protected fun approvalProcess(workflows: List<WorkflowDefinition>, queue: WorkflowQueue, staleAfter: Duration = Duration.ofMinutes(2), sweepMinInterval: Duration = Duration.ofSeconds(1)): G3Process {
        ApprovalTestSchema.ensure(jdbc)
        return G3Process(
            workflows, actionDefs, queue, JdbcActionRunStore(jdbc, json), JdbcWorkflowRunStore(jdbc, json), clock, ctx, staleAfter, 5,
            executor = Executors.newCachedThreadPool().also { executors += it }, sweepMinInterval = sweepMinInterval, approvalStore = JdbcApprovalStore(jdbc, json)
        )
    }

    protected fun own(c: AutoCloseable) { opened += c }

    /** A latch that is released at the end of the test whatever happens. */
    protected fun gate(): CountDownLatch = CountDownLatch(1).also { releases += it }

    /** Runs [task] on a thread of its own (a worker that is about to "die" or hang); the test waits for it in teardown. */
    protected fun inBackground(task: () -> Unit): Future<*> = Executors.newCachedThreadPool().also { executors += it }.submit { task() }.also { background += it }

    // ---- broker ----------------------------------------------------------------------------------------------------------

    protected fun broker(deliveryLimit: Int = 5) = UUID.randomUUID().toString().take(8).let {
        G3Broker(WorkflowQueueTopology("g3.jobs.$it", "g3.jobs.$it.dlq", "g3.dlx.$it", deliveryLimit = deliveryLimit, confirmTimeout = Duration.ofSeconds(10)))
    }

    /** One consumer node: its own channels, as a separate JVM would have. */
    protected fun amqp(b: G3Broker): AmqpWorkflowQueue = AmqpWorkflowQueue({ connection() }, b.topology).also { it.declareTopology(); own(it) }

    protected fun ready(queue: String): Int = connection().createChannel().let { ch -> try { ch.queueDeclarePassive(queue).messageCount } finally { runCatching { ch.close() } } }

    protected fun eventuallyEmpty(b: G3Broker, what: String = "queue") {
        await("$what is empty and nothing was dead-lettered", Duration.ofSeconds(10)) { if (ready(b.topology.queue) == 0 && ready(b.topology.deadLetterQueue) == 0) true else null }
    }

    // ---- polling -----------------------------------------------------------------------------------------------------------

    /** Polls [f] until it returns non-null; fails with [what] after [timeout]. Never a fixed sleep for a thing that is expected to happen. */
    protected fun <T : Any> await(what: String, timeout: Duration = Duration.ofSeconds(30), f: () -> T?): T {
        val end = System.nanoTime() + timeout.toNanos()
        var last: Throwable? = null
        while (System.nanoTime() < end) {
            try { f()?.let { return it } } catch (e: Exception) { last = e }
            Thread.sleep(25)
        }
        throw AssertionError("timed out after $timeout waiting for: $what" + (last?.let { " (last error: $it)" } ?: ""))
    }

    protected fun version(id: UUID): Long = jdbc.queryForObject("SELECT version FROM workflow_runs WHERE tenant_id = ? AND run_id = ?", Long::class.java, Fx.tenantA, id)!!
    protected fun total(queryRef: String, vararg ps: G3Process) = ps.sumOf { it.writes(queryRef) }
    protected fun hangBeforeExecute(p: G3Process): CountDownLatch = gate().also { g -> p.beforeExecute = { g.await(60, TimeUnit.SECONDS) } }
    /** the write of [queryRef] is dispatched (recorded by the sink) and then never answers until the latch is released: the outcome is unknown */
    protected fun hangOnWrite(p: G3Process, queryRef: String): CountDownLatch = gate().also { g -> p.data.onWrite = { if (p.data.writes.last().second.queryRef == queryRef) g.await(60, TimeUnit.SECONDS) } }
    protected fun hangOnW3(p: G3Process): CountDownLatch = hangOnWrite(p, "w3")

    protected val two = wf("two", act("a", "w1"), act("b", "w2"))
    protected val one = wf("one", act("a", "w1"))
}
