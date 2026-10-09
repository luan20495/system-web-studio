package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.AccessPort
import com.systemwebstudio.logic.action.AccessRequest
import com.systemwebstudio.logic.action.ActionActor
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.AuthorizationDecision
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.LogicPermissions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors

/**
 * F-1 (found by C1 with a real execution): `status` and `cancel` let the creator of a run through before the scope of the run was compared with the scope of the
 * request, so a creator who lost workspace W1 but is admin of W2 could read and cancel his W1 run through W2/P2.
 *
 * The order that must hold: tenant -> workspace -> project -> creator shortcut -> permission. A wrong scope is answered exactly like a run that does not exist
 * (RUN_NOT_FOUND, never FORBIDDEN), and a failed attempt changes nothing.
 */
class WorkflowRunScopeTests {
    private val executor = Executors.newCachedThreadPool()
    private val t1 = Fx.tenantA
    private val w1 = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val w2 = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
    private val p1 = Fx.appA                                                      // the application of the workflow definitions of the rig
    private val p2 = Fx.appB
    private val u1 = Fx.user
    private val u2 = Fx.user2
    private val rig = WorkflowRig(listOf(wf("one", act("a", "w1"))), listOf(Fx.write("w1")), executor)

    @AfterEach fun stop() { executor.shutdownNow() }

    private fun at(workspace: UUID?, project: UUID?, user: UUID = u1, tenant: UUID = t1) =
        ActionContext(tenant, ActionActor(user), workspaceId = workspace, projectId = project, requestId = "req-f1")

    /** a run of [creator] in T1 / [workspace] / [project] */
    private fun runIn(workspace: UUID?, project: UUID?, creator: UUID = u1, key: String = UUID.randomUUID().toString()): UUID =
        rig.startOk("one", key = key, ctx = at(workspace, project, creator))

    private fun WorkflowResult<WorkflowRunView>.code() = (this as WorkflowResult.Failed).code
    private fun status(ctx: ActionContext, id: UUID) = rig.engine.status(ctx, id)
    private fun cancel(ctx: ActionContext, id: UUID) = rig.engine.cancel(ctx, id)
    private fun stored(id: UUID) = rig.baseStore.get(t1, id)!!

    private fun assertNotFound(ctx: ActionContext, id: UUID, what: String) {
        val before = stored(id)
        assertThat(status(ctx, id).code()).describedAs("status: $what").isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(cancel(ctx, id).code()).describedAs("cancel: $what").isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(stored(id)).describedAs("a refused cancel changes nothing: $what").isEqualTo(before)
        assertThat(stored(id).status).isEqualTo(WorkflowRunStatus.PENDING)
    }

    // ---- A / B: wrong scope, same creator ----------------------------------------------------------------------------

    @Test
    fun `A the creator of a run in W1-P1 gets RUN_NOT_FOUND through another workspace and project`() {
        val id = runIn(w1, p1)
        assertNotFound(at(w2, p2), id, "W2/P2")
        assertNotFound(at(w2, p1), id, "right project path, wrong workspace")
    }

    @Test
    fun `B the creator gets RUN_NOT_FOUND through the same workspace and another project`() {
        val id = runIn(w1, p1)
        assertNotFound(at(w1, p2), id, "W1/P2")
    }

    @Test
    fun `tenant first - another tenant never finds the run, the creator or not`() {
        val id = runIn(w1, p1)
        val other = UUID.fromString("00000000-0000-0000-0000-00000000000b")
        assertThat(rig.engine.status(at(w1, p1, u1, tenant = other), id).code()).isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(rig.engine.cancel(at(w1, p1, u1, tenant = other), id).code()).isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(stored(id).status).isEqualTo(WorkflowRunStatus.PENDING)
    }

    // ---- C: right scope --------------------------------------------------------------------------------------------------

    @Test
    fun `C the creator in the right scope keeps the creator shortcut - no WORKFLOW_MANAGE needed - while he still uses the application`() {
        val id = runIn(w1, p1)
        rig.access.denyPermissions = setOf(LogicPermissions.WORKFLOW_MANAGE)       // no manage right at all: the shortcut alone, backed by a live APP_USE
        assertThat((status(at(w1, p1), id) as WorkflowResult.Ok).value.runId).isEqualTo(id)
        assertThat((cancel(at(w1, p1), id) as WorkflowResult.Ok).value.status).isEqualTo(WorkflowRunStatus.CANCELLED)
    }

    @Test
    fun `C2 the shortcut needs a live creator - a creator who lost APP_USE (or was disabled, which C1 reports the same way) is answered RUN_NOT_FOUND`() {
        val id = runIn(w1, p1)
        rig.access.denyPermissions = setOf(LogicPermissions.WORKFLOW_MANAGE, LogicPermissions.APP_USE)
        assertNotFound(at(w1, p1), id, "creator without APP_USE")
    }

    // ---- D: null is a value, never a wildcard -------------------------------------------------------------------------------

    @Test
    fun `D null is not a wildcard - a run with a workspace is not seen without one, a run without one is not seen from a workspace, a request without a project sees nothing`() {
        val withWorkspace = runIn(w1, p1)
        assertNotFound(at(null, p1), withWorkspace, "request without workspace, run has W1")
        assertNotFound(at(w1, null), withWorkspace, "request without project")
        assertNotFound(at(null, null), withWorkspace, "request without workspace and project")

        val withoutWorkspace = runIn(null, p1)
        assertNotFound(at(w1, p1), withoutWorkspace, "request names W1, run has no workspace")
        // the exact same scope - both without a workspace - is an equality of values, not a wildcard: the creator still reaches it
        assertThat((status(at(null, p1), withoutWorkspace) as WorkflowResult.Ok).value.runId).isEqualTo(withoutWorkspace)
    }

    // ---- E: the normal permission path ------------------------------------------------------------------------------------

    @Test
    fun `E a non-creator in the right scope needs WORKFLOW_MANAGE and in the wrong scope has no access even with it`() {
        val id = runIn(w1, p1, creator = u1)
        val other = at(w1, p1, u2)
        assertThat((status(other, id) as WorkflowResult.Ok).value.runId).describedAs("permission granted").isEqualTo(id)

        rig.access.denyPermissions = setOf(LogicPermissions.WORKFLOW_MANAGE)
        assertThat(status(other, id).code()).describedAs("permission missing").isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(cancel(other, id).code()).isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        rig.access.denyPermissions = emptySet()

        assertNotFound(at(w2, p2, u2), id, "non-creator with the permission, but in W2/P2")
        assertThat((cancel(other, id) as WorkflowResult.Ok).value.status).describedAs("granted again in the right scope").isEqualTo(WorkflowRunStatus.CANCELLED)
    }

    // ---- F: the C1 reproduction -------------------------------------------------------------------------------------------

    /** C1's world in one object: a user is "admin" of the workspaces listed, and holds every permission there, in none of the others. */
    private class AdminOf(val workspaces: MutableSet<UUID>) : AccessPort {
        override fun check(ctx: ActionContext, request: AccessRequest): AuthorizationDecision =
            if (ctx.workspaceId in workspaces) AuthorizationDecision.Allowed else AuthorizationDecision.Denied("not a member of ${ctx.workspaceId}")
    }

    @Test
    fun `F a creator who lost W1 and is admin of W2 cannot read or cancel his W1-P1 run through W2-P2`() {
        val admin = AdminOf(mutableSetOf(w1))
        val engine = WorkflowEngine(
            Fx.json, rig.defs, rig.actionRig.runtime, rig.runStore, rig.queue, admin, rig.tenants, rig.audit, rig.approvals, WorkflowLimits(), rig.clock
        )
        val id = (engine.start(at(w1, p1), WorkflowStartRequest("one", Fx.obj(), "f1")) as WorkflowResult.Ok).value.runId

        // C1's situation: membership of W1 is gone, admin of W2 remains
        admin.workspaces.clear(); admin.workspaces.add(w2)

        val before = stored(id)
        assertThat(engine.status(at(w2, p2), id).code()).describedAs("GET through W2/P2").isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(engine.cancel(at(w2, p2), id).code()).describedAs("cancel through W2/P2").isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(engine.status(at(w2, p1), id).code()).describedAs("W2 with the project of W1").isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(stored(id)).isEqualTo(before)
        assertThat(stored(id).status).isEqualTo(WorkflowRunStatus.PENDING)
        // Not the engine's job: whether a creator who lost W1 may still reach his run through W1/P1 is decided by the route's membership check (C1 / C0) before the engine is called.
    }
}
