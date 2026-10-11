package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.LogicPermissions
import com.systemwebstudio.logic.action.PrincipalSpec
import com.systemwebstudio.logic.approval.ApprovalStatus
import com.systemwebstudio.logic.approval.DecisionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors

/**
 * FQ-WF-01 / FQ-WF-02 at engine level: the decision operation of an approval step (`WorkflowRuntime.decideApproval`) - wait state, approve, reject, scope, permission,
 * approver, duplicates, cancel. The durable (PostgreSQL) restart and the whole HTTP path are in the real-stack tests.
 */
class ApprovalDecisionTests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }
    private val actions = listOf("w1", "w2", "wErr").map { Fx.write(it) }
    private val manager = Fx.user2                                          // the approver of the definition (the requester is Fx.user)

    private fun flow(onReject: String? = null, required: Int = 1, approvers: List<PrincipalSpec> = listOf(PrincipalSpec.User(manager, null))) = wf(
        "ap", act("a", "w1"),
        WorkflowStep("okay", StepKind.APPROVAL, approval = ApprovalSpec("Release order", approvers, required, Duration.ofHours(1), onReject = onReject)),
        act("b", "w2"), act("rej", "wErr", next = "fin"), WorkflowStep("fin", StepKind.END)
    ).let { d -> if (onReject == null) d.copy(steps = d.steps.filter { it.id != "rej" && it.id != "fin" }) else d }

    private fun rig(def: WorkflowDefinition) = WorkflowRig(listOf(def), actions, executor)
    private fun waiting(r: WorkflowRig): Pair<UUID, UUID> { val id = r.startOk("ap"); r.drain(); return id to r.step(id, "okay")!!.approvalId!! }
    private fun failed(r: WorkflowResult<*>) = (r as WorkflowResult.Failed).code
    private fun decide(r: WorkflowRig, run: UUID, approval: UUID, kind: DecisionKind, who: UUID = manager, ctx: com.systemwebstudio.logic.action.ActionContext = r.ctx(who)) =
        r.engine.decideApproval(ctx, run, approval, kind, "because")

    @Test fun `WAITING - the run waits durably for the approval, nothing after it runs, the approval is persisted with its approvers`() {
        val r = rig(flow()); val (id, aid) = waiting(r)
        assertThat(r.view(id).status).isEqualTo(WorkflowRunStatus.WAITING)
        assertThat(r.step(id, "okay")!!.status).isEqualTo(StepStatus.WAITING)
        assertThat(r.writeOrder).containsExactly("w1")                                       // b did not run
        val approval = r.approvalStore.get(Fx.tenantA, aid)!!
        assertThat(approval.status).isEqualTo(ApprovalStatus.PENDING)
        assertThat(approval.approvers).containsExactly(manager)
        assertThat(approval.source.workflowRunId).isEqualTo(id)
        r.advance(Duration.ofMinutes(10)); r.engine.sweep(); r.drain()                       // time passes, the sweeper runs: still waiting, still nothing
        assertThat(r.writeOrder).containsExactly("w1")
    }

    @Test fun `APPROVE - the decision is stored, the run resumes once and the next step executes once`() {
        val r = rig(flow()); val (id, aid) = waiting(r)
        val out = (decide(r, id, aid, DecisionKind.APPROVE) as WorkflowResult.Ok).value
        assertThat(out.approvalStatus).isEqualTo(ApprovalStatus.APPROVED); assertThat(out.approvals).isEqualTo(1)
        r.drain(); r.drain()
        assertThat(r.view(id).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(r.writeOrder).containsExactly("w1", "w2")
        assertThat(r.step(id, "b")!!.attempt).isEqualTo(1)
        assertThat(r.audit.records.map { it.event }).contains("WAITING_APPROVAL")
    }

    @Test fun `REJECT - the run fails deterministically as APPROVAL_REJECTED, or follows the configured onReject branch, and the next step never runs`() {
        val r = rig(flow()); val (id, aid) = waiting(r)
        assertThat((decide(r, id, aid, DecisionKind.REJECT) as WorkflowResult.Ok).value.approvalStatus).isEqualTo(ApprovalStatus.REJECTED)
        r.drain()
        assertThat(r.view(id).status).isEqualTo(WorkflowRunStatus.FAILED); assertThat(r.view(id).errorCode).isEqualTo(WorkflowErrorCodes.APPROVAL_REJECTED)
        assertThat(r.writeOrder).containsExactly("w1")

        val routed = rig(flow(onReject = "rej")); val (id2, aid2) = waiting(routed)
        decide(routed, id2, aid2, DecisionKind.REJECT); routed.drain()
        assertThat(routed.view(id2).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(routed.writeOrder).containsExactly("w1", "wErr")                          // the configured branch, not b
    }

    @Test fun `a quorum needs enough approvals, any rejection ends it`() {
        val approvers = listOf(PrincipalSpec.User(manager, null), PrincipalSpec.User(UUID.fromString("00000000-0000-0000-0000-0000000000a3"), null))
        val r = WorkflowRig(listOf(flow(required = 2, approvers = approvers)), actions, executor, principals = com.systemwebstudio.logic.action.FakePrincipals(directory = setOf(Fx.user, manager, UUID.fromString("00000000-0000-0000-0000-0000000000a3"))))
        val (id, aid) = waiting(r)
        decide(r, id, aid, DecisionKind.APPROVE); r.drain()
        assertThat(r.view(id).status).describedAs("one of two").isEqualTo(WorkflowRunStatus.WAITING); assertThat(r.writeOrder).containsExactly("w1")
        decide(r, id, aid, DecisionKind.APPROVE, UUID.fromString("00000000-0000-0000-0000-0000000000a3")); r.drain()
        assertThat(r.view(id).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
    }

    // ---- authorization ------------------------------------------------------------------------------------------------------

    @Test fun `wrong tenant, wrong project or another run's approval are not found, and nothing changes`() {
        val r = rig(flow()); val (id, aid) = waiting(r)
        val other = r.startOk("ap", key = "k2"); r.drain(); val otherApproval = r.step(other, "okay")!!.approvalId!!
        assertThat(failed(decide(r, id, aid, DecisionKind.APPROVE, ctx = r.ctx(manager, Fx.tenantB)))).isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(failed(decide(r, id, aid, DecisionKind.APPROVE, ctx = r.ctx(manager).copy(projectId = Fx.appB)))).isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(failed(decide(r, id, aid, DecisionKind.APPROVE, ctx = r.ctx(manager).copy(workspaceId = UUID.randomUUID())))).isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat(failed(decide(r, id, otherApproval, DecisionKind.APPROVE))).describedAs("the approval of another run").isEqualTo(WorkflowErrorCodes.APPROVAL_NOT_FOUND)
        assertThat(failed(decide(r, id, UUID.randomUUID(), DecisionKind.APPROVE))).isEqualTo(WorkflowErrorCodes.APPROVAL_NOT_FOUND)
        assertThat(r.approvalStore.get(Fx.tenantA, aid)!!.status).isEqualTo(ApprovalStatus.PENDING)
        r.drain(); assertThat(r.writeOrder.count { it == "w2" }).isZero()
    }

    @Test fun `without WORKFLOW_MANAGE the decision is refused before the approval is looked at`() {
        val r = rig(flow()); val (id, aid) = waiting(r)
        r.access.denyPermissions = setOf(LogicPermissions.WORKFLOW_MANAGE)
        assertThat(failed(decide(r, id, aid, DecisionKind.APPROVE))).isEqualTo(WorkflowErrorCodes.FORBIDDEN)
        assertThat(failed(decide(r, id, UUID.randomUUID(), DecisionKind.APPROVE))).describedAs("same answer for an unknown approval: nothing is revealed").isEqualTo(WorkflowErrorCodes.FORBIDDEN)
        assertThat(r.approvalStore.get(Fx.tenantA, aid)!!.status).isEqualTo(ApprovalStatus.PENDING)
        r.drain(); assertThat(r.writeOrder).containsExactly("w1")
    }

    @Test fun `only a snapshotted approver decides - not the requester, not a user the definition did not name`() {
        val r = rig(flow()); val (id, aid) = waiting(r)
        assertThat(failed(decide(r, id, aid, DecisionKind.APPROVE, who = Fx.user))).describedAs("the requester").isEqualTo(WorkflowErrorCodes.FORBIDDEN)
        assertThat(failed(decide(r, id, aid, DecisionKind.APPROVE, who = UUID.fromString("00000000-0000-0000-0000-0000000000a9")))).describedAs("an outsider").isEqualTo(WorkflowErrorCodes.FORBIDDEN)
        assertThat(r.approvalStore.get(Fx.tenantA, aid)!!.status).isEqualTo(ApprovalStatus.PENDING)
        assertThat(r.audit.records.count { it.event == "DECISION_DENIED" }).isGreaterThanOrEqualTo(2)
    }

    // ---- duplicates and cancel -------------------------------------------------------------------------------------------------

    @Test fun `a duplicate decision is idempotent, the opposite or a late one is a deterministic conflict, and the next step runs once`() {
        val r = rig(flow()); val (id, aid) = waiting(r)
        decide(r, id, aid, DecisionKind.APPROVE)
        val again = (decide(r, id, aid, DecisionKind.APPROVE) as WorkflowResult.Ok).value
        assertThat(again.approvalStatus).isEqualTo(ApprovalStatus.APPROVED)
        assertThat(failed(decide(r, id, aid, DecisionKind.REJECT))).isEqualTo(WorkflowErrorCodes.APPROVAL_CONFLICT)
        r.drain(); r.drain()
        // a redelivered / forged job of the waiting step and of the next one
        r.queue.publishRaw(WorkflowJob.forStep(Fx.tenantA, id, "okay").encode()); r.queue.publishRaw(WorkflowJob.forStep(Fx.tenantA, id, "b").encode()); r.drain()
        assertThat(r.writeOrder).containsExactly("w1", "w2")
        assertThat(r.view(id).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
    }

    @Test fun `cancel while waiting cancels the approval, a later decision cannot revive the run`() {
        val r = rig(flow()); val (id, aid) = waiting(r)
        assertThat((r.engine.cancel(r.ctx(Fx.user), id) as WorkflowResult.Ok).value.status).isEqualTo(WorkflowRunStatus.CANCELLED)
        assertThat(r.approvalStore.get(Fx.tenantA, aid)!!.status).isEqualTo(ApprovalStatus.CANCELLED)
        assertThat(failed(decide(r, id, aid, DecisionKind.APPROVE))).isEqualTo(WorkflowErrorCodes.APPROVAL_ALREADY_DECIDED)
        r.drain(); assertThat(r.writeOrder).containsExactly("w1"); assertThat(r.view(id).status).isEqualTo(WorkflowRunStatus.CANCELLED)
    }

    @Test fun `a decision whose resume job is lost is picked up by the sweeper, still exactly once`() {
        val r = rig(flow()); val (id, aid) = waiting(r)
        decide(r, id, aid, DecisionKind.APPROVE)
        r.queue.poll()                                                                       // the resume job dies with the process
        r.advance(Duration.ofMinutes(3)); r.engine.sweep(); r.drain(); r.advance(Duration.ofMinutes(3)); r.engine.sweep(); r.drain()
        assertThat(r.writeOrder).containsExactly("w1", "w2")
        assertThat(r.view(id).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
    }
}
