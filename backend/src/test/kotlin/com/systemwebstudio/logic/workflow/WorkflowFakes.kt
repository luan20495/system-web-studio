package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ActionDefinition
import com.systemwebstudio.logic.action.ActionPorts
import com.systemwebstudio.logic.action.ActionRig
import com.systemwebstudio.logic.action.PortOutcome
import com.systemwebstudio.logic.action.StartWorkflowRequest
import com.systemwebstudio.logic.action.WorkflowStarterPort
import com.systemwebstudio.logic.action.handlers.DefaultActionHandlers
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.FakeAccess
import com.systemwebstudio.logic.action.FakePrincipals
import com.systemwebstudio.logic.action.FakeTenants
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.RecordingAudit
import com.systemwebstudio.logic.action.RecordingLogicAudit
import com.systemwebstudio.logic.action.TestClock
import com.systemwebstudio.logic.approval.ApprovalListener
import com.systemwebstudio.logic.approval.ApprovalService
import com.systemwebstudio.logic.approval.InMemoryApprovalStore
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService

class FakeWorkflowDefs(vararg defs: WorkflowDefinition) : WorkflowDefinitionProvider {
    private val all = defs.associateBy { it.id }
    val modes = java.util.concurrent.CopyOnWriteArrayList<ExecutionMode>()
    /** Leaks other tenants' rows on purpose; the engine must still refuse them. */
    override fun find(tenantId: UUID, appId: UUID, workflowId: String, mode: ExecutionMode): WorkflowDefinition? { modes += mode; return all[workflowId] }
}

/** Run store that can be told to fail, to exercise redelivery and the dead-letter path. */
class FlakyRunStore(private val delegate: WorkflowRunStore) : WorkflowRunStore by delegate {
    @Volatile var failGet = false
    override fun get(tenantId: UUID, runId: UUID): WorkflowRun? { if (failGet) error("db down"); return delegate.get(tenantId, runId) }
}

/** Everything needed to drive the engine deterministically: fake clock, in-memory store and broker, a real action runtime over fakes. */
class WorkflowRig(
    workflows: List<WorkflowDefinition>,
    actions: List<ActionDefinition>,
    executor: ExecutorService,
    wireApprovalListener: Boolean = true,
    ceiling: WorkflowLimits = WorkflowLimits(),
    staleAfter: Duration = Duration.ofMinutes(2),
    maxDeliveries: Int = 3,
    principals: FakePrincipals = FakePrincipals(
        groups = mapOf("finance" to setOf(Fx.user2)), roles = mapOf("approvers" to setOf(Fx.user2)), managers = mapOf("own" to Fx.user2)
    )
) {
    val clock = TestClock()
    val access = FakeAccess()
    val tenants = FakeTenants()
    val actionAudit = RecordingAudit()
    val audit = RecordingLogicAudit()
    private var engineRef: WorkflowEngine? = null
    /** START_WORKFLOW reaches the real engine (the engine is built after the action runtime, hence the forwarding port). */
    private val starter = object : WorkflowStarterPort {
        override fun start(ctx: ActionContext, request: StartWorkflowRequest): PortOutcome = engineRef!!.start(ctx, request)
    }
    val actionRig = ActionRig.build(
        *actions.toTypedArray(), audit = actionAudit, access = access, tenants = tenants, executor = executor, clock = clock,
        registry = { d, n, _ -> DefaultActionHandlers.registry(Fx.json, ActionPorts(d, n, starter)) }
    )
    val data get() = actionRig.data
    val defs = FakeWorkflowDefs(*workflows.toTypedArray())
    val baseStore = InMemoryWorkflowRunStore()
    val runStore = FlakyRunStore(baseStore)
    val queue = InMemoryWorkflowQueue(maxDeliveries)
    val approvalStore = InMemoryApprovalStore()
    val approvals = ApprovalService(
        Fx.json, approvalStore, principals, tenants, audit, null, if (wireApprovalListener) ApprovalListener { engineRef?.onFinal(it) } else null, clock = clock
    )
    val engine = WorkflowEngine(Fx.json, defs, actionRig.runtime, runStore, queue, access, tenants, audit, approvals, ceiling, clock, staleAfter).also { engineRef = it }
    val worker = WorkflowWorker(engine, queue)

    fun ctx(userId: UUID = Fx.user, tenant: UUID = Fx.tenantA) = Fx.ctx(tenant = tenant, userId = userId)

    fun start(wf: String, input: JsonNode = Fx.obj(), key: String = "k1", ctx: ActionContext = ctx(), mode: ExecutionMode = ExecutionMode.LIVE, depth: Int = 0) =
        engine.start(ctx, WorkflowStartRequest(wf, input, key, mode, depth))

    /** Starts and returns the run id (asserting success). */
    fun startOk(wf: String, input: JsonNode = Fx.obj(), key: String = "k1", ctx: ActionContext = ctx(), mode: ExecutionMode = ExecutionMode.LIVE): UUID =
        (start(wf, input, key, ctx, mode) as WorkflowResult.Ok).value.runId

    fun drain() = worker.runUntilIdle()
    fun stored(id: UUID, tenant: UUID = Fx.tenantA): WorkflowRun = baseStore.get(tenant, id)!!
    fun view(id: UUID) = stored(id).toView()
    fun step(id: UUID, stepId: String) = stored(id).steps[stepId]
    fun advance(d: Duration) = clock.advance(d)
    val now: Instant get() = clock.instant()

    fun writes(queryRef: String) = data.writes.count { it.second.queryRef == queryRef }
    val writeOrder get() = data.writes.map { it.second.queryRef }
}

// ---- definition builders ------------------------------------------------------------------------------------------

fun act(id: String, actionRef: String, next: String? = null, onError: String? = null, retry: RetryPolicy = RetryPolicy(), comp: String? = null,
        title: ValueRef = ValueRef.Literal(Fx.str("T")), timeout: Duration? = null) =
    WorkflowStep(id, StepKind.ACTION, actionRef = actionRef, inputs = mapOf("title" to title), next = next, onError = onError, retry = retry, compensationActionRef = comp, timeout = timeout)

fun wf(id: String, vararg steps: WorkflowStep, limits: WorkflowLimits = WorkflowLimits(), compensateOnCancel: Boolean = false, startStepId: String? = null) =
    WorkflowDefinition(id, Fx.tenantA, Fx.appA, steps = steps.toList(), limits = limits, compensateOnCancel = compensateOnCancel, startStepId = startStepId)
