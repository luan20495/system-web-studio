package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.logic.action.ActionActor
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.PrincipalSpec
import com.systemwebstudio.logic.approval.ApprovalStatus
import com.systemwebstudio.logic.approval.DecisionKind
import com.systemwebstudio.logic.workflow.ApprovalSpec
import com.systemwebstudio.logic.workflow.StepKind
import com.systemwebstudio.logic.workflow.StepStatus
import com.systemwebstudio.logic.workflow.WorkflowDefinition
import com.systemwebstudio.logic.workflow.WorkflowErrorCodes
import com.systemwebstudio.logic.workflow.WorkflowResult
import com.systemwebstudio.logic.workflow.WorkflowRunStatus
import com.systemwebstudio.logic.workflow.WorkflowStep
import com.systemwebstudio.logic.workflow.act
import com.systemwebstudio.logic.workflow.wf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * FQ-WF-01 / FQ-WF-02 on REAL infrastructure (PostgreSQL: runs, steps, approvals; RabbitMQ 4: jobs), restart and redelivery. "Restart" = a new node with no memory over the same
 * database and broker queue (see [G3TestBase]). The only fake is the effect sink: what the approved step "writes" is counted across every node.
 */
class WorkflowApprovalG3Tests : G3TestBase() {
    private val approver = Fx.user2
    private fun decider(user: UUID = approver) = ActionContext(Fx.tenantA, ActionActor(user), workspaceId = workspace, projectId = Fx.appA, requestId = "req-ap")

    private fun flow(onReject: String? = null): WorkflowDefinition = wf(
        "ap", act("a", "w1"),
        WorkflowStep("okay", StepKind.APPROVAL, approval = ApprovalSpec("Release order", listOf(PrincipalSpec.User(approver, null)), 1, Duration.ofHours(1), onReject = onReject)),
        act("b", "w2"), act("rej", "w3", next = "fin"), WorkflowStep("fin", StepKind.END)
    ).let { d -> if (onReject == null) d.copy(steps = d.steps.filter { it.id != "rej" && it.id != "fin" }) else d }

    private fun approvalOf(p: com.systemwebstudio.wiring.persistence.G3Process, id: UUID) = p.stored(id).steps["okay"]!!.approvalId!!
    private fun approvalStatus(aid: UUID) = jdbc.queryForObject("SELECT status FROM approvals WHERE tenant_id = ? AND id = ?", String::class.java, Fx.tenantA, aid)

    @Test
    fun `AP-01 the wait is durable - a new node finds the run WAITING, runs nothing, and an approval after the restart executes the next step exactly once`() {
        val b = broker()
        val first = approvalProcess(listOf(flow()), amqp(b))
        val id = first.start("ap", "ap-01")
        first.drain()
        assertThat(first.stored(id).status).isEqualTo(WorkflowRunStatus.WAITING)
        val aid = approvalOf(first, id)
        assertThat(approvalStatus(aid)).isEqualTo("PENDING")
        assertThat(first.writes("w1")).isEqualTo(1); assertThat(first.writes("w2")).isZero()

        // the first node is gone; a second one has no memory of the run
        val second = approvalProcess(listOf(flow()), amqp(b))
        clock.advance(Duration.ofMinutes(10)); second.engine.sweep(); second.drain()
        assertThat(second.stored(id).status).describedAs("time and a sweep alone never approve").isEqualTo(WorkflowRunStatus.WAITING)
        assertThat(total("w2", first, second)).isZero()

        val decided = second.engine.decideApproval(decider(), id, aid, DecisionKind.APPROVE, "ok")
        assertThat((decided as WorkflowResult.Ok).value.approvalStatus).isEqualTo(ApprovalStatus.APPROVED)
        second.drain()
        val run = second.stored(id)
        assertThat(run.status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(run.steps["okay"]!!.status).isEqualTo(StepStatus.SUCCEEDED)
        assertThat(total("w1", first, second)).isEqualTo(1); assertThat(total("w2", first, second)).isEqualTo(1)
        assertThat(run.steps["b"]!!.attempt).isEqualTo(1)
        eventuallyEmpty(b)
    }

    @Test
    fun `AP-02 a rejection after the restart ends the run deterministically, or follows onReject, and the approved branch never runs`() {
        val b = broker()
        val first = approvalProcess(listOf(flow()), amqp(b))
        val id = first.start("ap", "ap-02"); first.drain()
        val aid = approvalOf(first, id)
        val second = approvalProcess(listOf(flow()), amqp(b))
        assertThat(second.engine.decideApproval(decider(), id, aid, DecisionKind.REJECT) is WorkflowResult.Ok).isTrue()
        second.drain()
        assertThat(second.stored(id).status).isEqualTo(WorkflowRunStatus.FAILED)
        assertThat(second.stored(id).errorCode).isEqualTo(WorkflowErrorCodes.APPROVAL_REJECTED)
        assertThat(total("w2", first, second)).isZero()

        val routedQueue = broker()
        val n1 = approvalProcess(listOf(flow(onReject = "rej")), amqp(routedQueue))
        val id2 = n1.start("ap", "ap-02b"); n1.drain()
        val aid2 = approvalOf(n1, id2)
        val n2 = approvalProcess(listOf(flow(onReject = "rej")), amqp(routedQueue))
        n2.engine.decideApproval(decider(), id2, aid2, DecisionKind.REJECT); n2.drain()
        assertThat(n2.stored(id2).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(total("w3", n1, n2)).describedAs("the configured reject branch ran once").isEqualTo(1)
        assertThat(total("w2", n1, n2)).isZero()
    }

    @Test
    fun `AP-03 two nodes deciding at the same moment with opposite decisions - exactly one decision stands and the effect is consistent with it`() {
        val b = broker()
        val first = approvalProcess(listOf(flow()), amqp(b))
        val id = first.start("ap", "ap-03"); first.drain()
        val aid = approvalOf(first, id)
        val second = approvalProcess(listOf(flow()), amqp(b))
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        try {
            val a = pool.submit<WorkflowResult<*>> { go.await(); first.engine.decideApproval(decider(), id, aid, DecisionKind.APPROVE) }
            val r = pool.submit<WorkflowResult<*>> { go.await(); second.engine.decideApproval(decider(), id, aid, DecisionKind.REJECT) }
            go.countDown()
            val results = listOf(a.get(30, TimeUnit.SECONDS), r.get(30, TimeUnit.SECONDS))
            assertThat(results.count { it is WorkflowResult.Ok }).isEqualTo(1)
            assertThat((results.single { it is WorkflowResult.Failed } as WorkflowResult.Failed).code).isIn(WorkflowErrorCodes.APPROVAL_CONFLICT, WorkflowErrorCodes.APPROVAL_ALREADY_DECIDED)
        } finally { pool.shutdownNow() }
        first.drain(); second.drain()
        val approved = approvalStatus(aid) == "APPROVED"
        assertThat(total("w2", first, second)).describedAs("the next step ran exactly when the standing decision is APPROVE, and then once").isEqualTo(if (approved) 1 else 0)
        assertThat(first.stored(id).status).isEqualTo(if (approved) WorkflowRunStatus.SUCCEEDED else WorkflowRunStatus.FAILED)
        // a repeat of the standing decision is harmless
        val standing = if (approved) DecisionKind.APPROVE else DecisionKind.REJECT
        assertThat(first.engine.decideApproval(decider(), id, aid, standing) is WorkflowResult.Ok).isTrue()
        first.drain(); assertThat(total("w2", first, second)).isEqualTo(if (approved) 1 else 0)
    }

    @Test
    fun `AP-04 the resume job is redelivered after its consumer dies before the ack - the next step still runs exactly once`() {
        val b = broker()
        val first = approvalProcess(listOf(flow()), amqp(b))
        val id = first.start("ap", "ap-04"); first.drain()
        val aid = approvalOf(first, id)
        first.engine.decideApproval(decider(), id, aid, DecisionKind.APPROVE)                       // publishes the resume job

        val crashing = CrashableQueue(amqp(b))
        val dying = approvalProcess(listOf(flow()), crashing)
        crashing.crashOnNextAck = true
        dying.worker.runOnce()                                                                      // the next step runs and is persisted - then the consumer dies before the ack
        assertThat(crashing.crashes.get()).isEqualTo(1)

        val healthy = CrashableQueue(amqp(b))
        val third = approvalProcess(listOf(flow()), healthy)
        third.drain()
        assertThat(healthy.polled.map { it.deliveryCount }).describedAs("the unacked job came back").contains(2)
        assertThat(total("w2", first, dying, third)).isEqualTo(1)
        assertThat(third.stored(id).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
        assertThat(third.stored(id).steps["b"]!!.attempt).isEqualTo(1)
        eventuallyEmpty(b)
    }

    @Test
    fun `AP-05 a decision whose resume job was never delivered is recovered by the sweeper of another node, once`() {
        val b = broker()
        val first = approvalProcess(listOf(flow()), amqp(b))
        val id = first.start("ap", "ap-05"); first.drain()
        val aid = approvalOf(first, id)
        // the decision is stored, then the node dies before anything consumed the resume job; the queue is emptied to model a lost nudge
        first.engine.decideApproval(decider(), id, aid, DecisionKind.APPROVE)
        val purge = amqp(b); while (purge.poll() != null) { /* drop every job: the nudge is lost */ }
        assertThat(total("w2", first)).isZero()

        val second = approvalProcess(listOf(flow()), amqp(b))
        repeat(3) { clock.advance(Duration.ofMinutes(3)); second.engine.sweep(); second.drain() }
        assertThat(total("w2", first, second)).isEqualTo(1)
        assertThat(second.stored(id).status).isEqualTo(WorkflowRunStatus.SUCCEEDED)
    }

    @Test
    fun `AP-06 cancel while waiting, then a decision after the restart - the run stays cancelled and nothing runs`() {
        val b = broker()
        val first = approvalProcess(listOf(flow()), amqp(b))
        val id = first.start("ap", "ap-06"); first.drain()
        val aid = approvalOf(first, id)
        assertThat(first.engine.cancel(ctx, id) is WorkflowResult.Ok).isTrue()
        assertThat(approvalStatus(aid)).isEqualTo("CANCELLED")

        val second = approvalProcess(listOf(flow()), amqp(b))
        val late = second.engine.decideApproval(decider(), id, aid, DecisionKind.APPROVE)
        assertThat((late as WorkflowResult.Failed).code).isEqualTo(WorkflowErrorCodes.APPROVAL_ALREADY_DECIDED)
        second.drain(); clock.advance(Duration.ofMinutes(10)); second.engine.sweep(); second.drain()
        assertThat(second.stored(id).status).isEqualTo(WorkflowRunStatus.CANCELLED)
        assertThat(total("w2", first, second)).isZero()
    }

    @Test
    fun `AP-07 a wrong tenant or a user that is not an approver cannot decide, also after the restart`() {
        val b = broker()
        val first = approvalProcess(listOf(flow()), amqp(b))
        val id = first.start("ap", "ap-07"); first.drain()
        val aid = approvalOf(first, id)
        val second = approvalProcess(listOf(flow()), amqp(b))
        val foreign = ActionContext(UUID.fromString("00000000-0000-0000-0000-00000000000b"), ActionActor(approver), workspaceId = workspace, projectId = Fx.appA, requestId = "x")
        assertThat((second.engine.decideApproval(foreign, id, aid, DecisionKind.APPROVE) as WorkflowResult.Failed).code).isEqualTo(WorkflowErrorCodes.RUN_NOT_FOUND)
        assertThat((second.engine.decideApproval(decider(Fx.user), id, aid, DecisionKind.APPROVE) as WorkflowResult.Failed).code).describedAs("the requester").isEqualTo(WorkflowErrorCodes.FORBIDDEN)
        assertThat((second.engine.decideApproval(decider(UUID.randomUUID()), id, aid, DecisionKind.APPROVE) as WorkflowResult.Failed).code).describedAs("an outsider").isEqualTo(WorkflowErrorCodes.FORBIDDEN)
        assertThat(approvalStatus(aid)).isEqualTo("PENDING")
        second.drain(); assertThat(total("w2", first, second)).isZero()
    }
}
