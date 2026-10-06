package com.systemwebstudio.wiring

import com.systemwebstudio.logic.action.AccessPort
import com.systemwebstudio.logic.action.AccessRequest
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ActionDefinition
import com.systemwebstudio.logic.action.ActionDefinitionProvider
import com.systemwebstudio.logic.action.ActionExecution
import com.systemwebstudio.logic.action.ActionRequest
import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.ActionRuntime
import com.systemwebstudio.logic.action.ActionType
import com.systemwebstudio.logic.action.AuthorizationDecision
import com.systemwebstudio.logic.action.DispatchOutcome
import com.systemwebstudio.logic.action.Event
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.workflow.WorkflowResult
import com.systemwebstudio.logic.workflow.WorkflowRunView
import com.systemwebstudio.logic.workflow.WorkflowRuntime
import com.systemwebstudio.logic.workflow.WorkflowStartRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** D-C0-20: LIVE runs that can change state are refused while C4's stores are in memory, unless acknowledged. */
class VolatileStoreGuardsTests {
    private class CountingRuntime : ActionRuntime {
        var calls = 0
        override fun execute(ctx: ActionContext, request: ActionRequest): ActionResult { calls++; return ActionResult.Ok(Fx.obj()) }
        override fun run(ctx: ActionContext, request: ActionRequest): ActionExecution { calls++; return ActionExecution(ActionResult.Ok(Fx.obj())) }
        override fun dispatch(ctx: ActionContext, event: Event): List<DispatchOutcome> { calls++; return emptyList() }
    }

    private fun defs(vararg d: ActionDefinition) = object : ActionDefinitionProvider {
        private val byId = d.associateBy { it.id }
        override fun find(tenantId: UUID, appId: UUID, actionId: String, mode: ExecutionMode): ActionDefinition? = byId[actionId]
    }

    private fun access(allow: Boolean) = object : AccessPort {
        override fun check(ctx: ActionContext, request: AccessRequest): AuthorizationDecision =
            if (allow) AuthorizationDecision.Allowed else AuthorizationDecision.Denied("no")
    }

    private val mutation = Fx.mutation(ActionType.CREATE_RECORD, "m1")
    private val navigate = Fx.navigate("go-home")

    private fun guard(rt: ActionRuntime, allowVolatile: Boolean = false, permit: Boolean = true, d: Array<ActionDefinition> = emptyArray()) =
        VolatileActionGuard(rt, defs(*d), access(permit), allowVolatile)

    private fun req(id: String, mode: ExecutionMode = ExecutionMode.LIVE) = ActionRequest(actionId = id, mode = mode)

    @Test
    fun `a LIVE mutating action is refused with RUNTIME_STORES_VOLATILE and nothing runs`() {
        val rt = CountingRuntime()
        val r = guard(rt, d = arrayOf(mutation)).run(Fx.ctx(), req("m1")).result as ActionResult.Failed
        assertThat(r.code).isEqualTo("RUNTIME_STORES_VOLATILE")
        assertThat(r.retryable).isFalse()
        assertThat(rt.calls).isZero()
        assertThat((guard(rt, d = arrayOf(mutation)).execute(Fx.ctx(), req("m1")) as ActionResult.Failed).code).isEqualTo("RUNTIME_STORES_VOLATILE")
    }

    @Test
    fun `TEST mode and a non mutating action are not affected`() {
        val rt = CountingRuntime()
        guard(rt, d = arrayOf(mutation, navigate)).run(Fx.ctx(), req("m1", ExecutionMode.TEST))
        guard(rt, d = arrayOf(mutation, navigate)).run(Fx.ctx(), req("go-home"))
        assertThat(rt.calls).isEqualTo(2)
    }

    @Test
    fun `a non mutating action that chains a mutating one is refused too`() {
        val rt = CountingRuntime()
        val chained = navigate.copy(onSuccess = listOf("m1"))
        val r = guard(rt, d = arrayOf(chained, mutation)).run(Fx.ctx(), req("go-home")).result as ActionResult.Failed
        assertThat(r.code).isEqualTo("RUNTIME_STORES_VOLATILE")
        assertThat(rt.calls).isZero()
    }

    @Test
    fun `an acknowledged deployment passes through`() {
        val rt = CountingRuntime()
        guard(rt, allowVolatile = true, d = arrayOf(mutation)).run(Fx.ctx(), req("m1"))
        assertThat(rt.calls).isEqualTo(1)
    }

    @Test
    fun `a caller without permission gets the delegate's own answer, not the refusal`() {
        val rt = CountingRuntime()
        guard(rt, permit = false, d = arrayOf(mutation)).run(Fx.ctx(), req("m1"))
        assertThat(rt.calls).isEqualTo(1)
    }

    @Test
    fun `an unknown action is left to the delegate`() {
        val rt = CountingRuntime()
        guard(rt).run(Fx.ctx(), req("ghost"))
        assertThat(rt.calls).isEqualTo(1)
    }

    private class CountingWorkflows : WorkflowRuntime {
        var starts = 0
        override fun start(ctx: ActionContext, request: WorkflowStartRequest): WorkflowResult<WorkflowRunView> { starts++; return WorkflowResult.Failed("UNKNOWN_WORKFLOW", "x") }
        override fun status(ctx: ActionContext, runId: UUID): WorkflowResult<WorkflowRunView> = WorkflowResult.Failed("WORKFLOW_RUN_NOT_FOUND", "x")
        override fun cancel(ctx: ActionContext, runId: UUID): WorkflowResult<WorkflowRunView> = WorkflowResult.Failed("WORKFLOW_RUN_NOT_FOUND", "x")
    }

    @Test
    fun `a LIVE workflow start is refused while the stores are volatile, TEST is not`() {
        val wf = CountingWorkflows()
        val g = VolatileWorkflowGuard(wf, access(true), false)
        val live = g.start(Fx.ctx(), WorkflowStartRequest("w1", Fx.obj(), "key-1"))
        assertThat((live as WorkflowResult.Failed).code).isEqualTo("RUNTIME_STORES_VOLATILE")
        assertThat(wf.starts).isZero()
        g.start(Fx.ctx(), WorkflowStartRequest("w1", Fx.obj(), "key-1", ExecutionMode.TEST))
        assertThat(wf.starts).isEqualTo(1)
        VolatileWorkflowGuard(wf, access(false), false).start(Fx.ctx(), WorkflowStartRequest("w1", Fx.obj(), "key-2"))
        assertThat(wf.starts).isEqualTo(2)
    }
}
