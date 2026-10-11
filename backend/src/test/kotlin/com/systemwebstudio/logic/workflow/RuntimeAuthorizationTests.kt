package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.AccessRequest
import com.systemwebstudio.logic.action.ActionActor
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ActionDefinition
import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.ActionPorts
import com.systemwebstudio.logic.action.ActionRequest
import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.ActionRig
import com.systemwebstudio.logic.action.AuthorizationDecision
import com.systemwebstudio.logic.action.FakeAccess
import com.systemwebstudio.logic.action.FakePrincipals
import com.systemwebstudio.logic.action.FakeTenants
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.LogicPermissions
import com.systemwebstudio.logic.action.PortOutcome
import com.systemwebstudio.logic.action.PrincipalSpec
import com.systemwebstudio.logic.action.RecordingLogicAudit
import com.systemwebstudio.logic.action.StartWorkflowRequest
import com.systemwebstudio.logic.action.TestClock
import com.systemwebstudio.logic.action.WorkflowStarterPort
import com.systemwebstudio.logic.action.handlers.DefaultActionHandlers
import com.systemwebstudio.logic.approval.ApprovalListener
import com.systemwebstudio.logic.approval.ApprovalService
import com.systemwebstudio.logic.approval.InMemoryApprovalStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Runtime authorization, server side, BEFORE any side effect. The access port here is shaped like C1's: it is evaluated live on every call (nothing is cached, so a
 * revocation or a disabled user is effective at once) and checks tenant, workspace, project, user state and the explicit permission, in that order. Nothing else
 * is mocked about authorization: C4's runtime asks it exactly as in production.
 *
 * The effect boundaries are observed, not assumed: the effect sink (C3 data port / outbound call / notification), the workflow queue (RabbitMQ in production), the run
 * store and the approval store. A denied request must leave every one of them at zero; an allowed one must show its authorization BEFORE its first effect in the
 * shared [timeline].
 */
class RuntimeAuthorizationTests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }

    // scope
    private val t1 = Fx.tenantA
    private val t2 = Fx.tenantB
    private val w1 = Fx.workspaceA
    private val w2 = UUID.fromString("00000000-0000-0000-0000-0000000000c2")
    private val p1 = Fx.appA
    private val p2 = Fx.appB
    // people
    private val runner = Fx.user                                          // holds what each test grants
    private val member = Fx.user2                                         // a member of the organization / workspace / project: nothing else
    private val viewer = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
    private val operator = UUID.fromString("00000000-0000-0000-0000-0000000000a4")

    /** C1-shaped, live access. */
    private class PolicyAccess(private val timeline: MutableList<String>) : FakeAccess() {
        class Grant(val tenant: UUID, val workspace: UUID, val project: UUID, val permissions: MutableSet<String>)
        val grants = java.util.concurrent.ConcurrentHashMap<UUID, MutableList<Grant>>()
        val disabled: MutableSet<UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        fun grant(user: UUID, tenant: UUID, workspace: UUID, project: UUID, vararg permissions: String): Grant =
            Grant(tenant, workspace, project, permissions.toMutableSet()).also { grants.getOrPut(user) { CopyOnWriteArrayList() } += it }

        override fun check(ctx: ActionContext, request: AccessRequest): AuthorizationDecision {
            val decision = decide(ctx, request)
            timeline += "auth:${request.permission}:" + if (decision is AuthorizationDecision.Allowed) "allowed" else "denied"
            return decision
        }

        private fun decide(ctx: ActionContext, request: AccessRequest): AuthorizationDecision {
            if (ctx.actor.userId in disabled) return AuthorizationDecision.Denied("ACCOUNT_DISABLED")
            val project = request.appId ?: ctx.projectId
            val g = grants[ctx.actor.userId]?.firstOrNull { it.tenant == ctx.tenantId && it.workspace == ctx.workspaceId && it.project == project }
                ?: return AuthorizationDecision.Denied("not a member of this tenant / workspace / project")
            return if (request.permission in g.permissions) AuthorizationDecision.Allowed else AuthorizationDecision.Denied("missing permission")
        }
    }

    private class SpyQueue(private val inner: InMemoryWorkflowQueue, private val timeline: MutableList<String>) : WorkflowQueue by inner {
        val published = java.util.concurrent.atomic.AtomicInteger()
        override fun publish(job: WorkflowJob) { published.incrementAndGet(); timeline += "effect:publish"; inner.publish(job) }
    }

    private inner class Rig(workflows: List<WorkflowDefinition>, actions: List<ActionDefinition>) {
        val timeline: MutableList<String> = CopyOnWriteArrayList()
        val policy = PolicyAccess(timeline)
        val tenants = FakeTenants()
        val clock = TestClock()
        val audit = RecordingLogicAudit()
        private var engineRef: WorkflowEngine? = null
        private val starter = object : WorkflowStarterPort {
            override fun start(ctx: ActionContext, request: StartWorkflowRequest): PortOutcome = engineRef!!.start(ctx, request)
        }
        val actionRig = ActionRig.build(
            *actions.toTypedArray(), access = policy, tenants = tenants, executor = executor, clock = clock,
            registry = { d, n, _ -> DefaultActionHandlers.registry(Fx.json, ActionPorts(d, n, starter)) }
        ).also { it.data.onWrite = { timeline += "effect:write" } }
        val data get() = actionRig.data
        val notify get() = actionRig.notify
        val memory = InMemoryWorkflowQueue()
        val queue = SpyQueue(memory, timeline)
        val runs = InMemoryWorkflowRunStore()
        val approvalStore = InMemoryApprovalStore()
        val approvals = ApprovalService(
            Fx.json, approvalStore, FakePrincipals(directory = setOf(runner, member, operator)), tenants, audit, actionRig.notify, ApprovalListener { engineRef?.onFinal(it) }, clock = clock
        )
        val engine = WorkflowEngine(Fx.json, FakeWorkflowDefs(*workflows.toTypedArray()), actionRig.runtime, runs, queue, policy, tenants, audit, approvals, WorkflowLimits(), clock)
            .also { engineRef = it }
        val worker = WorkflowWorker(engine, queue)

        fun ctx(user: UUID, tenant: UUID = t1, workspace: UUID? = w1, project: UUID? = p1) = ActionContext(tenant, ActionActor(user), workspaceId = workspace, projectId = project, requestId = "req-authz")
        fun writes(ref: String) = data.writes.count { it.second.queryRef == ref }
        fun start(user: UUID, wf: String, key: String = "k-" + UUID.randomUUID().toString().take(8), ctx: ActionContext = ctx(user)) = engine.start(ctx, WorkflowStartRequest(wf, Fx.obj(), key))
        fun runId(r: WorkflowResult<WorkflowRunView>) = (r as WorkflowResult.Ok).value.runId
        fun run(id: UUID) = runs.get(t1, id)!!
        fun drain() { while (worker.runOnce()) Unit }
        fun act(user: UUID, id: String, key: String = "k-" + UUID.randomUUID().toString().take(8), ctx: ActionContext = ctx(user), inputs: Map<String, tools.jackson.databind.JsonNode> = mapOf("title" to Fx.str("t"))) =
            actionRig.runtime.execute(ctx, ActionRequest(id, inputs, idempotencyKey = key))

        /** every observable effect boundary at once */
        fun effects() = Effects(data.writes.size, data.operations.size, notify.sent.size, queue.published.get(), approvalCount())
        fun approvalCount() = approvals.inbox(ctx(member)).size + approvals.inbox(ctx(operator)).size
        fun assertNoEffects(what: String) = assertThat(effects()).describedAs("effects after: $what").isEqualTo(Effects(0, 0, 0, 0, 0))
    }

    data class Effects(val writes: Int, val operations: Int, val notifications: Int, val publishes: Int, val approvals: Int)

    private val allOfRunner = arrayOf(LogicPermissions.APP_USE, LogicPermissions.ACTION_EXECUTE, LogicPermissions.WORKFLOW_EXECUTE, LogicPermissions.DATA_MUTATE)

    private val write = Fx.write("w1")
    private val write2 = Fx.write("w2")
    private val two = wf("two", act("a", "w1"), act("b", "w2"))
    private val one = wf("one", act("a", "w1"))
    private val approvalFlow = wf(
        "ap", act("a", "w1"),
        WorkflowStep("okay", StepKind.APPROVAL, approval = ApprovalSpec("Release", listOf(PrincipalSpec.User(member, null)), 1, Duration.ofHours(1), notifyTemplateRef = "welcome")),
        act("b", "w2")
    )

    private fun rig(vararg w: WorkflowDefinition, extra: List<ActionDefinition> = emptyList()) = Rig(w.toList(), listOf(write, write2, Fx.write("c1"), Fx.callApi(), Fx.notify(), Fx.startWorkflow("sw", "one")) + extra)
    private fun failed(r: ActionResult) = r as ActionResult.Failed
    private fun failed(r: WorkflowResult<*>) = r as WorkflowResult.Failed

    // =============================================================================================================================
    // ACTION_EXECUTE
    // =============================================================================================================================

    @Test
    fun `1 ACTION_EXECUTE present - the action runs, and every authorization precedes the first effect`() {
        val r = rig(one)
        r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        assertThat(r.act(runner, "w1")).isInstanceOf(ActionResult.Ok::class.java)
        assertThat(r.writes("w1")).isEqualTo(1)
        val firstEffect = r.timeline.indexOfFirst { it.startsWith("effect:") }
        assertThat(r.timeline.take(firstEffect)).contains("auth:APP_USE:allowed", "auth:ACTION_EXECUTE:allowed", "auth:DATA_MUTATE:allowed")
        assertThat(r.timeline.drop(firstEffect).none { it.startsWith("auth:") && it.endsWith("denied") }).isTrue()
    }

    @Test
    fun `2 missing ACTION_EXECUTE - denied FORBIDDEN, not a single effect`() {
        val r = rig(one)
        r.policy.grant(runner, t1, w1, p1, LogicPermissions.APP_USE, LogicPermissions.DATA_MUTATE)
        assertThat(failed(r.act(runner, "w1")).code).isEqualTo(ActionErrorCodes.FORBIDDEN)
        r.assertNoEffects("an action without ACTION_EXECUTE")
        assertThat(r.timeline.none { it.startsWith("effect:") }).isTrue()
    }

    @Test
    fun `3 APP_VIEW alone grants no runtime right - no action, no workflow start, no manage, no mutation`() {
        val r = rig(one)
        val owner = r.runId(run { r.policy.grant(runner, t1, w1, p1, *allOfRunner, LogicPermissions.WORKFLOW_MANAGE); r.start(runner, "one") })
        r.policy.grant(viewer, t1, w1, p1, "APP_VIEW")
        val before = r.effects()
        assertThat(failed(r.act(viewer, "w1")).code).describedAs("action").isEqualTo(ActionErrorCodes.FORBIDDEN)
        assertThat(failed(r.act(viewer, "sw")).code).describedAs("START_WORKFLOW action").isEqualTo(ActionErrorCodes.FORBIDDEN)
        assertThat(failed(r.start(viewer, "one")).code).describedAs("workflow start").isEqualTo(WorkflowErrorCodes.FORBIDDEN)
        assertThat(failed(r.engine.status(r.ctx(viewer), owner)).code).describedAs("history of somebody else's run").isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(failed(r.engine.cancel(r.ctx(viewer), owner)).code).describedAs("cancel").isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(r.run(owner).status).isEqualTo(WorkflowRunStatus.PENDING)
        assertThat(r.effects()).describedAs("nothing moved").isEqualTo(before)
    }

    @Test
    fun `4 wrong project - denied before the handler, also when the action exists in the other project`() {
        val inP2 = write.copy(id = "w1", appId = p2)
        val r = Rig(listOf(one), listOf(write, Fx.write("w1p2").copy(appId = p2), inP2.copy(id = "w9")))
        r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        assertThat(failed(r.act(runner, "w1", ctx = r.ctx(runner, project = p2))).code).describedAs("action of P1 asked through P2").isEqualTo(ActionErrorCodes.UNKNOWN_ACTION)
        assertThat(failed(r.act(runner, "w1p2", ctx = r.ctx(runner, project = p2))).code).describedAs("an action that exists in P2, without any right there").isEqualTo(ActionErrorCodes.FORBIDDEN)
        r.assertNoEffects("wrong project")
    }

    @Test
    fun `5 a permission revoked after login is effective on the next request, before the handler`() {
        val r = rig(one)
        val g = r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        assertThat(r.act(runner, "w1")).isInstanceOf(ActionResult.Ok::class.java)
        g.permissions.remove(LogicPermissions.ACTION_EXECUTE)
        assertThat(failed(r.act(runner, "w1")).code).isEqualTo(ActionErrorCodes.FORBIDDEN)
        assertThat(r.writes("w1")).describedAs("only the first call wrote").isEqualTo(1)
    }

    @Test
    fun `6 a disabled user is denied before the handler`() {
        val r = rig(one)
        r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        r.policy.disabled += runner
        assertThat(failed(r.act(runner, "w1")).code).isEqualTo(ActionErrorCodes.FORBIDDEN)
        r.assertNoEffects("disabled user")
    }

    @Test
    fun `other effect types of an action - notification, outbound call and START_WORKFLOW leave no effect without the right`() {
        val r = rig(one)
        r.policy.grant(runner, t1, w1, p1, LogicPermissions.APP_USE)            // member of the app, nothing else
        assertThat(failed(r.act(runner, "n1", inputs = mapOf("name" to Fx.str("Ann")))).code).isEqualTo(ActionErrorCodes.FORBIDDEN)
        assertThat(failed(r.act(runner, "call", inputs = mapOf("q" to Fx.str("x")))).code).isEqualTo(ActionErrorCodes.FORBIDDEN)
        assertThat(failed(r.act(runner, "sw", inputs = mapOf("who" to Fx.str("x")))).code).isEqualTo(ActionErrorCodes.FORBIDDEN)
        r.assertNoEffects("notification / CALL_API / START_WORKFLOW")
        // ACTION_EXECUTE alone: the notification passes (it is not a data mutation), the outbound call and the workflow start do not
        r.policy.grants[runner]!!.single().permissions += LogicPermissions.ACTION_EXECUTE
        assertThat(r.act(runner, "n1", inputs = mapOf("name" to Fx.str("Ann")))).isInstanceOf(ActionResult.Ok::class.java)
        assertThat(failed(r.act(runner, "call", inputs = mapOf("q" to Fx.str("x")))).code).describedAs("CALL_API needs DATA_MUTATE").isEqualTo(ActionErrorCodes.FORBIDDEN)
        assertThat(failed(r.act(runner, "sw", inputs = mapOf("who" to Fx.str("x")))).code).describedAs("START_WORKFLOW needs WORKFLOW_EXECUTE").isEqualTo(ActionErrorCodes.FORBIDDEN)
        assertThat(r.effects()).isEqualTo(Effects(0, 0, 1, 0, 0))
    }

    // =============================================================================================================================
    // WORKFLOW_EXECUTE
    // =============================================================================================================================

    @Test
    fun `7 WORKFLOW_EXECUTE present - the run exists, its job is published after the authorizations`() {
        val r = rig(one)
        r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        val id = r.runId(r.start(runner, "one"))
        assertThat(r.run(id).createdBy.userId).isEqualTo(runner)
        assertThat(r.queue.published.get()).isEqualTo(1)
        assertThat(r.timeline.indexOf("auth:WORKFLOW_EXECUTE:allowed")).isLessThan(r.timeline.indexOf("effect:publish"))
    }

    @Test
    fun `8 missing WORKFLOW_EXECUTE - denied before any run exists, before the queue, before the audit of a start`() {
        val r = rig(one)
        r.policy.grant(runner, t1, w1, p1, LogicPermissions.APP_USE, LogicPermissions.ACTION_EXECUTE, LogicPermissions.DATA_MUTATE)
        assertThat(failed(r.start(runner, "one")).code).isEqualTo(WorkflowErrorCodes.FORBIDDEN)
        r.assertNoEffects("a start without WORKFLOW_EXECUTE")
        assertThat(r.runs.get(t1, UUID.randomUUID())).isNull()
        assertThat(r.audit.records.map { it.event }).describedAs("only the denial is audited, never a START_REQUESTED").doesNotContain("START_REQUESTED")
    }

    @Test
    fun `9 wrong tenant - a start through another tenant is refused with no run and no job`() {
        val r = rig(one)
        r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        assertThat(r.start(runner, "one", ctx = r.ctx(runner, tenant = t2))).isInstanceOf(WorkflowResult.Failed::class.java)
        r.assertNoEffects("wrong tenant")
    }

    @Test
    fun `10 wrong project - refused, also when the workflow of that project exists and the user has rights only in the first one`() {
        val other = WorkflowDefinition("one", t1, p2, steps = listOf(act("a", "w1")))
        val r = Rig(listOf(one), listOf(write))
        r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        assertThat(failed(r.start(runner, "one", ctx = r.ctx(runner, project = p2))).code).isEqualTo(WorkflowErrorCodes.UNKNOWN_WORKFLOW)
        val r2 = Rig(listOf(other), listOf(write.copy(appId = p2)))
        r2.policy.grant(runner, t1, w1, p1, *allOfRunner)
        assertThat(failed(r2.start(runner, "one", ctx = r2.ctx(runner, project = p2))).code).describedAs("exists in P2, no right in P2").isEqualTo(WorkflowErrorCodes.FORBIDDEN)
        r.assertNoEffects("wrong project (workflow unknown there)"); r2.assertNoEffects("wrong project (no right there)")
    }

    @Test
    fun `11a an ACTION_EXECUTE revoked while the run waits stops the next step before its effect`() {
        val r = rig(two)
        val g = r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        val id = r.runId(r.start(runner, "two"))
        assertThat(r.worker.runOnce()).isTrue()                                    // step a
        assertThat(r.writes("w1")).isEqualTo(1)
        g.permissions.remove(LogicPermissions.ACTION_EXECUTE)
        r.drain()
        assertThat(r.writes("w2")).describedAs("step b never reached its effect").isZero()
        assertThat(r.run(id).status).isEqualTo(WorkflowRunStatus.FAILED)
        assertThat(r.run(id).errorCode).isEqualTo(WorkflowErrorCodes.FORBIDDEN)
    }

    @Test
    fun `11b WORKFLOW_EXECUTE revoked alone - a durable run is not an authority token, the next step is refused before its effect`() {
        val r = rig(two)
        val g = r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        val id = r.runId(r.start(runner, "two"))
        r.worker.runOnce()
        g.permissions.remove(LogicPermissions.WORKFLOW_EXECUTE)                      // the user still has every ACTION right
        r.drain()
        assertThat(r.writes("w2")).isZero()
        assertThat(r.run(id).status).isEqualTo(WorkflowRunStatus.FAILED)
        assertThat(r.run(id).errorCode).isEqualTo(WorkflowErrorCodes.FORBIDDEN)
        assertThat(r.audit.records.any { it.event == "DENIED" && it.attributes["permission"] == LogicPermissions.WORKFLOW_EXECUTE }).describedAs("the denial is audited").isTrue()
    }

    @Test
    fun `11c an approval step does not create the approval or notify anybody once the right is gone`() {
        val r = rig(approvalFlow)
        val g = r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        val id = r.runId(r.start(runner, "ap"))
        r.worker.runOnce()                                                           // step a
        val notificationsBefore = r.notify.sent.size
        g.permissions.remove(LogicPermissions.WORKFLOW_EXECUTE)
        r.drain()
        assertThat(r.approvalCount()).describedAs("no approval was requested").isZero()
        assertThat(r.notify.sent.size).describedAs("no approver was notified").isEqualTo(notificationsBefore)
        assertThat(r.run(id).status).isEqualTo(WorkflowRunStatus.FAILED)
        assertThat(r.run(id).errorCode).isEqualTo(WorkflowErrorCodes.FORBIDDEN)
        assertThat(r.writes("w2")).isZero()
    }

    @Test
    fun `11d with the right in place the same approval step does request and notify (the guard is not a blanket block)`() {
        val r = rig(approvalFlow)
        r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        val id = r.runId(r.start(runner, "ap"))
        r.drain()
        assertThat(r.run(id).status).isEqualTo(WorkflowRunStatus.WAITING)
        assertThat(r.approvalCount()).isEqualTo(1)
        assertThat(r.notify.sent).hasSize(1)
    }

    @Test
    fun `12 a disabled actor is refused at the start and, mid-run, before the next step's effect`() {
        val r = rig(two)
        r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        r.policy.disabled += runner
        assertThat(failed(r.start(runner, "two")).code).isEqualTo(WorkflowErrorCodes.FORBIDDEN)
        r.assertNoEffects("disabled at start")

        r.policy.disabled -= runner
        val id = r.runId(r.start(runner, "two"))
        r.worker.runOnce()
        r.policy.disabled += runner
        r.drain()
        assertThat(r.writes("w1")).isEqualTo(1); assertThat(r.writes("w2")).isZero()
        assertThat(r.run(id).status).isEqualTo(WorkflowRunStatus.FAILED)
    }

    // =============================================================================================================================
    // WORKFLOW_MANAGE (status / cancel)
    // =============================================================================================================================

    @Test
    fun `13 WORKFLOW_MANAGE in the right scope - a non-creator reads and cancels`() {
        val r = rig(one)
        r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        r.policy.grant(operator, t1, w1, p1, LogicPermissions.APP_USE, LogicPermissions.WORKFLOW_MANAGE)
        val id = r.runId(r.start(runner, "one"))
        assertThat((r.engine.status(r.ctx(operator), id) as WorkflowResult.Ok).value.runId).isEqualTo(id)
        assertThat((r.engine.cancel(r.ctx(operator), id) as WorkflowResult.Ok).value.status).isEqualTo(WorkflowRunStatus.CANCELLED)
    }

    @Test
    fun `14 a creator in the wrong workspace or project gets RUN_NOT_FOUND, even as admin elsewhere (F-1 stays closed)`() {
        val r = rig(one)
        r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        val id = r.runId(r.start(runner, "one"))
        r.policy.grants[runner]!!.clear()                                            // membership of W1 lost ...
        r.policy.grant(runner, t1, w2, p2, *allOfRunner, LogicPermissions.WORKFLOW_MANAGE)   // ... admin of W2 remains
        for (c in listOf(r.ctx(runner, workspace = w2, project = p2), r.ctx(runner, workspace = w2, project = p1), r.ctx(runner, workspace = w1, project = p2))) {
            assertThat(failed(r.engine.status(c, id)).code).isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
            assertThat(failed(r.engine.cancel(c, id)).code).isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        }
        assertThat(r.run(id).status).isEqualTo(WorkflowRunStatus.PENDING)
    }

    @Test
    fun `15 no WORKFLOW_MANAGE - somebody else's run is not found and stays untouched`() {
        val r = rig(one)
        r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        r.policy.grant(member, t1, w1, p1, LogicPermissions.APP_USE, LogicPermissions.ACTION_EXECUTE)
        val id = r.runId(r.start(runner, "one"))
        assertThat(failed(r.engine.status(r.ctx(member), id)).code).isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(failed(r.engine.cancel(r.ctx(member), id)).code).isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(r.run(id).status).isEqualTo(WorkflowRunStatus.PENDING)
    }

    @Test
    fun `16 a creator who is disabled, or no longer uses the application, cannot read or cancel his own run`() {
        val r = rig(one)
        val g = r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        val id = r.runId(r.start(runner, "one"))
        r.policy.disabled += runner
        assertThat(failed(r.engine.status(r.ctx(runner), id)).code).isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(failed(r.engine.cancel(r.ctx(runner), id)).code).isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        r.policy.disabled -= runner
        g.permissions.remove(LogicPermissions.APP_USE)
        assertThat(failed(r.engine.cancel(r.ctx(runner), id)).code).isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(r.run(id).status).isEqualTo(WorkflowRunStatus.PENDING)
    }

    // =============================================================================================================================
    // DATA_MUTATE
    // =============================================================================================================================

    @Test
    fun `18 an action mutation without DATA_MUTATE is denied before the data port, whatever else the user holds`() {
        val r = rig(one)
        r.policy.grant(runner, t1, w1, p1, LogicPermissions.APP_USE, LogicPermissions.ACTION_EXECUTE, LogicPermissions.WORKFLOW_EXECUTE)
        assertThat(failed(r.act(runner, "w1")).code).isEqualTo(ActionErrorCodes.FORBIDDEN)
        r.assertNoEffects("a mutation without DATA_MUTATE")
    }

    @Test
    fun `19 a workflow whose step mutates, without DATA_MUTATE, starts but its step is denied before the data port`() {
        val r = rig(one)
        r.policy.grant(runner, t1, w1, p1, LogicPermissions.APP_USE, LogicPermissions.ACTION_EXECUTE, LogicPermissions.WORKFLOW_EXECUTE)
        val id = r.runId(r.start(runner, "one"))
        r.drain()
        assertThat(r.data.writes).isEmpty()
        assertThat(r.run(id).status).isEqualTo(WorkflowRunStatus.FAILED)
        assertThat(r.run(id).errorCode).isEqualTo(ActionErrorCodes.FORBIDDEN)
    }

    @Test
    fun `20 DATA_MUTATE revoked while the run waits - denied before the data port`() {
        val r = rig(two)
        val g = r.policy.grant(runner, t1, w1, p1, *allOfRunner)
        val id = r.runId(r.start(runner, "two"))
        r.worker.runOnce()
        g.permissions.remove(LogicPermissions.DATA_MUTATE)
        r.drain()
        assertThat(r.writes("w2")).isZero()
        assertThat(r.run(id).status).isEqualTo(WorkflowRunStatus.FAILED)
    }

    // =============================================================================================================================
    // ORGANIZATION MEMBERSHIP
    // =============================================================================================================================

    @Test
    fun `21 being a member of the tenant, the workspace and the project grants nothing at runtime`() {
        val r = rig(one)
        r.policy.grant(member, t1, w1, p1)                                           // a member of everything, no explicit permission
        r.policy.grant(member, t1, w2, p2, *allOfRunner, LogicPermissions.WORKFLOW_MANAGE)   // rights in ANOTHER workspace do not travel
        val owner = run { r.policy.grant(runner, t1, w1, p1, *allOfRunner); r.runId(r.start(runner, "one")) }
        val before = r.effects()
        assertThat(failed(r.act(member, "w1")).code).describedAs("no ACTION_EXECUTE").isEqualTo(ActionErrorCodes.FORBIDDEN)
        assertThat(failed(r.start(member, "one")).code).describedAs("no WORKFLOW_EXECUTE").isEqualTo(WorkflowErrorCodes.FORBIDDEN)
        assertThat(failed(r.engine.status(r.ctx(member), owner)).code).describedAs("no WORKFLOW_MANAGE").isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(failed(r.engine.cancel(r.ctx(member), owner)).code).isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(r.effects()).describedAs("no mutation, no job").isEqualTo(before)
    }

    // =============================================================================================================================
    // A denied request leaves no trace anywhere
    // =============================================================================================================================

    @Test
    fun `a denied request changes no store - run store, approval store, queue, data port, notifications`() {
        val r = rig(one, approvalFlow)
        r.policy.grant(runner, t1, w1, p1, LogicPermissions.APP_USE)
        r.act(runner, "w1"); r.act(runner, "n1", inputs = mapOf("name" to Fx.str("x"))); r.act(runner, "sw", inputs = mapOf("who" to Fx.str("x")))
        r.start(runner, "one"); r.start(runner, "ap")
        r.assertNoEffects("every entry point denied")
        assertThat(r.memory.readyCount()).isZero()
    }

    // =============================================================================================================================
    // EVERY ActionType: unauthorized = no effect, exactly the needed rights = the one expected effect
    // =============================================================================================================================

    private class TypeCase(val def: ActionDefinition, val inputs: Map<String, tools.jackson.databind.JsonNode>, val needs: Set<String>, val effectful: Boolean)

    private val str = Fx::str
    private val typeCases: List<TypeCase> = listOf(
        TypeCase(Fx.mutation(com.systemwebstudio.logic.action.ActionType.SUBMIT_FORM, "t-submit"), mapOf("title" to Fx.str("t")), setOf(LogicPermissions.DATA_MUTATE), true),
        TypeCase(Fx.mutation(com.systemwebstudio.logic.action.ActionType.CREATE_RECORD, "t-create"), mapOf("title" to Fx.str("t")), setOf(LogicPermissions.DATA_MUTATE), true),
        TypeCase(
            Fx.mutation(com.systemwebstudio.logic.action.ActionType.UPDATE_RECORD, "t-update", inputs = listOf(com.systemwebstudio.logic.action.InputSpec("recordId", com.systemwebstudio.logic.action.InputType.STRING, required = true))),
            mapOf("recordId" to Fx.str("5")), setOf(LogicPermissions.DATA_MUTATE), true
        ),
        TypeCase(
            Fx.mutation(com.systemwebstudio.logic.action.ActionType.DELETE_RECORD, "t-delete", inputs = listOf(com.systemwebstudio.logic.action.InputSpec("recordId", com.systemwebstudio.logic.action.InputType.STRING, required = true))),
            mapOf("recordId" to Fx.str("5")), setOf(LogicPermissions.DATA_MUTATE), true
        ),
        TypeCase(Fx.callApi("t-call"), mapOf("q" to Fx.str("x")), setOf(LogicPermissions.DATA_MUTATE), true),
        TypeCase(Fx.notify("t-notify"), mapOf("name" to Fx.str("Ann")), emptySet(), true),
        TypeCase(Fx.startWorkflow("t-start", "one"), mapOf("who" to Fx.str("x")), setOf(LogicPermissions.WORKFLOW_EXECUTE), true),
        TypeCase(Fx.navigate("t-nav"), emptyMap(), emptySet(), false),
        TypeCase(ActionDefinition("t-refresh", t1, com.systemwebstudio.logic.action.ActionType.REFRESH_QUERY, config = Fx.cfg("queryRef" to "orders.list"), appId = p1), emptyMap(), emptySet(), false)
    )

    @Test
    fun `every ActionType - no right gives no effect, exactly its rights give exactly one effect, one right short gives none`() {
        val covered = typeCases.map { it.def.type }.toSet()
        assertThat(covered).describedAs("all nine canonical types are exercised").hasSize(9)
        for (c in typeCases) {
            val r = Rig(listOf(one), listOf(write, c.def))
            val g = r.policy.grant(runner, t1, w1, p1, LogicPermissions.APP_USE)             // a member of the app and nothing else
            assertThat(failed(r.act(runner, c.def.id, inputs = c.inputs)).code).describedAs("${c.def.type}: no rights").isEqualTo(ActionErrorCodes.FORBIDDEN)
            r.assertNoEffects("${c.def.type} without rights")

            g.permissions += LogicPermissions.ACTION_EXECUTE                                   // everything but its specific right
            if (c.needs.isNotEmpty()) {
                assertThat(failed(r.act(runner, c.def.id, inputs = c.inputs)).code).describedAs("${c.def.type}: one right short ${c.needs}").isEqualTo(ActionErrorCodes.FORBIDDEN)
                r.assertNoEffects("${c.def.type} one right short")
            }
            g.permissions += c.needs
            assertThat(r.act(runner, c.def.id, key = "ok-" + c.def.id, inputs = c.inputs)).describedAs("${c.def.type}: exactly its rights").isInstanceOf(ActionResult.Ok::class.java)
            val e = r.effects()
            val total = e.writes + e.operations + e.notifications + e.publishes
            assertThat(total).describedAs("${c.def.type}: effect count ($e)").isEqualTo(if (c.effectful) 1 else 0)
        }
    }

    @Test
    fun `workflow-triggered path of every effectful type - the run's actor needs the same rights at its step, and a missing one stops it before the effect`() {
        for (c in typeCases.filter { it.effectful && it.def.type != com.systemwebstudio.logic.action.ActionType.START_WORKFLOW }) {
            val def = c.def.copy(id = "wf-" + c.def.id)
            val flow = wf("flow", WorkflowStep("s", StepKind.ACTION, actionRef = def.id, inputs = c.inputs.mapValues { ValueRef.Literal(it.value) }))
            val r = Rig(listOf(flow), listOf(def))
            val g = r.policy.grant(runner, t1, w1, p1, LogicPermissions.APP_USE, LogicPermissions.WORKFLOW_EXECUTE, LogicPermissions.ACTION_EXECUTE)
            if (c.needs.isNotEmpty()) {
                val id = r.runId(r.start(runner, "flow")); r.drain()
                assertThat(r.run(id).status).describedAs("${def.type} one right short").isEqualTo(WorkflowRunStatus.FAILED)
                assertThat(r.run(id).errorCode).isEqualTo(ActionErrorCodes.FORBIDDEN)
                assertThat(r.effects().let { it.writes + it.operations + it.notifications }).describedAs("${def.type} no effect").isZero()
            }
            g.permissions += c.needs
            val ok = r.runId(r.start(runner, "flow")); r.drain()
            assertThat(r.run(ok).status).describedAs("${def.type} with its rights").isEqualTo(WorkflowRunStatus.SUCCEEDED)
            assertThat(r.effects().let { it.writes + it.operations + it.notifications }).describedAs("${def.type} one effect").isEqualTo(1)
        }
    }
}

