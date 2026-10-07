package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.ActionDefinition
import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.ActionRequest
import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.AuditDomains
import com.systemwebstudio.logic.action.DryRunLevel
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.LogicPermissions
import com.systemwebstudio.logic.action.PortOutcome
import com.systemwebstudio.logic.approval.ApprovalStatus
import com.systemwebstudio.logic.approval.DecisionKind
import com.systemwebstudio.logic.action.PrincipalSpec
import com.systemwebstudio.logic.scheduler.EnqueueOutcome
import com.systemwebstudio.logic.scheduler.ScheduleTarget
import com.systemwebstudio.logic.scheduler.ScheduledRunRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors

class WorkflowEngineTests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }

    private val actions = listOf("w1", "w2", "w3", "c1", "c2", "wBig", "wSmall", "wErr").map { Fx.write(it) } + listOf(Fx.startWorkflow("sw", "child"), Fx.startWorkflow("swSelf", "loopy"))

    private val user3: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
    private val crowd = com.systemwebstudio.logic.action.FakePrincipals(
        groups = mapOf("finance" to setOf(Fx.user2, user3)), roles = mapOf("approvers" to setOf(Fx.user2)), managers = mapOf("own" to Fx.user2), directory = setOf(Fx.user, Fx.user2, user3)
    )

    private fun rig(vararg w: WorkflowDefinition, extra: List<ActionDefinition> = emptyList(), approvalListener: Boolean = true, ceiling: WorkflowLimits = WorkflowLimits(), maxDeliveries: Int = 3) =
        WorkflowRig(w.toList(), actions + extra, executor, approvalListener, ceiling, maxDeliveries = maxDeliveries, principals = crowd)

    private val two = wf("two", act("a", "w1"), act("b", "w2"))
    private fun failed(r: WorkflowResult<*>) = assertInstanceOf(WorkflowResult.Failed::class.java, r)

    // ---- start / run to completion -----------------------------------------------------------------------------------

    @Test fun `start does not execute anything on the calling thread and returns a PENDING run`() {
        val r = rig(two)
        val view = (r.start("two") as WorkflowResult.Ok).value
        assertEquals(WorkflowRunStatus.PENDING, view.status)
        assertEquals(0, r.data.writes.size)
        assertEquals(1, r.queue.readyCount())
    }

    @Test fun `a worker runs the steps in order and the run succeeds`() {
        val r = rig(two)
        val id = r.startOk("two")
        r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertEquals(listOf("w1", "w2"), r.writeOrder)
        assertEquals(listOf(StepStatus.SUCCEEDED, StepStatus.SUCCEEDED), r.view(id).steps.map { it.status })
        assertNotNull(r.stored(id).finishedAt)
    }

    @Test fun `start is idempotent and a reused key with other input is refused`() {
        val r = rig(two)
        val a = r.startOk("two", Fx.obj("x" to Fx.num(1)))
        val b = r.startOk("two", Fx.obj("x" to Fx.num(1)))
        assertEquals(a, b)
        assertEquals(1, r.queue.readyCount())
        assertEquals("IDEMPOTENCY_KEY_REUSED", failed(r.start("two", Fx.obj("x" to Fx.num(2)))).code)
        r.drain()
        assertEquals(1, r.writes("w1"))
    }

    @Test fun `the same key from another user is a different run`() {
        val r = rig(two)
        val a = r.startOk("two", ctx = r.ctx(Fx.user))
        val b = r.startOk("two", ctx = r.ctx(Fx.user2))
        assertNotEquals(a, b)
    }

    @Test fun `start refuses bad keys unknown workflows and a missing app context`() {
        val r = rig(two)
        assertEquals("IDEMPOTENCY_KEY_INVALID", failed(r.start("two", key = "bad key!")).code)
        assertEquals("UNKNOWN_WORKFLOW", failed(r.start("nope")).code)
        assertEquals("INVALID_INPUT", failed(r.start("two", ctx = Fx.ctx(app = null))).code)
    }

    @Test fun `a workflow of another tenant is never started`() {
        val r = rig(two)
        assertEquals("UNKNOWN_WORKFLOW", failed(r.start("two", ctx = r.ctx(tenant = Fx.tenantB))).code)
        assertEquals(0, r.queue.readyCount())
    }

    @Test fun `start needs APP_USE and WORKFLOW_EXECUTE and audits the denial`() {
        val r = rig(two)
        r.access.denyPermissions = setOf(LogicPermissions.WORKFLOW_EXECUTE)
        assertEquals("FORBIDDEN", failed(r.start("two")).code)
        assertTrue(r.audit.events(AuditDomains.WORKFLOW).contains("DENIED"))
        r.access.denyPermissions = setOf(LogicPermissions.APP_USE)
        assertEquals("FORBIDDEN", failed(r.start("two", key = "k2")).code)
        assertEquals(0, r.queue.readyCount())
    }

    @Test fun `start fails closed when authorization or the tenant gate is unavailable`() {
        val r = rig(two)
        r.access.throwing = true
        assertEquals("DEPENDENCY_UNAVAILABLE", failed(r.start("two")).code)
        r.access.throwing = false
        r.tenants.disabled = setOf(Fx.tenantA)
        assertEquals("TENANT_DISABLED", failed(r.start("two")).code)
        r.tenants.disabled = emptySet(); r.tenants.throwing = true
        assertEquals("DEPENDENCY_UNAVAILABLE", failed(r.start("two")).code)
    }

    @Test fun `no run exists without its audit record`() {
        val r = rig(two)
        r.audit.failEvents = setOf("START_REQUESTED")
        val f = failed(r.start("two"))
        assertEquals("AUDIT_UNAVAILABLE", f.code); assertTrue(f.retryable)
        assertEquals(0, r.queue.readyCount())
        assertTrue(r.baseStore.list(Fx.tenantA, null, 10).isEmpty())
    }

    @Test fun `an oversized input is refused before anything is stored`() {
        val r = rig(wf("small", act("a", "w1"), limits = WorkflowLimits(maxPayloadBytes = 100)))
        assertEquals("LIMIT_EXCEEDED", failed(r.start("small", Fx.obj("blob" to Fx.str("x".repeat(500))))).code)
        assertTrue(r.baseStore.list(Fx.tenantA, null, 10).isEmpty())
    }

    @Test fun `an invalid definition is refused at start`() {
        val r = rig(wf("bad", act("a", "w1", next = "ghost")))
        assertEquals("INVALID_DEFINITION", failed(r.start("bad")).code)
    }

    // ---- status ------------------------------------------------------------------------------------------------------

    @Test fun `status is visible to the creator and to WORKFLOW_MANAGE only`() {
        val r = rig(two)
        val id = r.startOk("two")
        assertInstanceOf(WorkflowResult.Ok::class.java, r.engine.status(r.ctx(Fx.user), id))
        r.access.denyPermissions = setOf(LogicPermissions.WORKFLOW_MANAGE)
        assertEquals("WORKFLOW_RUN_NOT_FOUND", failed(r.engine.status(r.ctx(Fx.user2), id)).code)
        r.access.denyPermissions = emptySet()
        assertInstanceOf(WorkflowResult.Ok::class.java, r.engine.status(r.ctx(Fx.user2), id))
    }

    @Test fun `status and cancel of a run in another tenant answer not found`() {
        val r = rig(two)
        val id = r.startOk("two")
        assertEquals("WORKFLOW_RUN_NOT_FOUND", failed(r.engine.status(r.ctx(Fx.user, Fx.tenantB), id)).code)
        assertEquals("WORKFLOW_RUN_NOT_FOUND", failed(r.engine.cancel(r.ctx(Fx.user, Fx.tenantB), id)).code)
        r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
    }

    @Test fun `the view never exposes the definition snapshot or the input`() {
        val r = rig(two)
        val id = r.startOk("two", Fx.obj("secretish" to Fx.str("v")))
        r.drain()
        val text = r.view(id).toString()
        assertFalse(text.contains("secretish"))
    }

    // ---- data flow ---------------------------------------------------------------------------------------------------

    @Test fun `step inputs are mapped from the workflow input and from earlier step outputs`() {
        val r = rig(wf("flow",
            act("a", "w1", title = ValueRef.Input("customer.name")),
            act("b", "w2", title = ValueRef.Step("a", "id"))
        ))
        r.startOk("flow", Fx.obj("customer" to Fx.obj("name" to Fx.str("Ada"))))
        r.drain()
        assertEquals("Ada", r.data.writes[0].second.params["title"]!!.asString())
        assertEquals("rec-1", r.data.writes[1].second.params["title"]!!.asString())
    }

    @Test fun `a missing mapped value is simply absent from the step input`() {
        val r = rig(wf("flow", act("a", "w1", title = ValueRef.Input("nothing.here"))))
        val id = r.startOk("flow")
        r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertFalse(r.data.writes.single().second.params.containsKey("title"))
    }

    @Test fun `BRANCH follows the first matching condition else the default`() {
        val def = wf("br",
            WorkflowStep("br", StepKind.BRANCH, branches = listOf(Branch(Condition.Compare(ValueRef.Input("amount"), CompareOp.GT, ValueRef.Literal(Fx.num(100))), "big")), defaultNext = "small"),
            act("big", "wBig", next = "fin"), act("small", "wSmall", next = "fin"), WorkflowStep("fin", StepKind.END)
        )
        val r = rig(def)
        r.startOk("br", Fx.obj("amount" to Fx.num(500)), key = "k-big"); r.drain()
        r.startOk("br", Fx.obj("amount" to Fx.num(5)), key = "k-small"); r.drain()
        assertEquals(listOf("wBig", "wSmall"), r.writeOrder)
        assertEquals(setOf(WorkflowRunStatus.SUCCEEDED), r.baseStore.list(Fx.tenantA, null, 10).map { it.status }.toSet())
    }

    @Test fun `BRANCH without a match and without default fails the run`() {
        val def = wf("br2",
            WorkflowStep("br", StepKind.BRANCH, branches = listOf(Branch(Condition.Exists(ValueRef.Input("flag")), "a"))),
            act("a", "w1")
        )
        val r = rig(def)
        val id = r.startOk("br2"); r.drain()
        assertEquals(WorkflowRunStatus.FAILED, r.view(id).status)
        assertEquals("INVALID_DEFINITION", r.view(id).errorCode)
    }

    // ---- retry / failure routing -----------------------------------------------------------------------------------

    @Test fun `a retryable failure waits for its backoff and is retried by the sweeper`() {
        val retry = RetryPolicy(maxAttempts = 3, initialBackoff = Duration.ofSeconds(10))
        val r = rig(wf("rt", act("a", "w1", retry = retry), act("b", "w2")))
        r.data.script("w1", PortOutcome.Failure("DOWN", true), PortOutcome.Failure("DOWN", true))
        val id = r.startOk("rt"); r.drain()
        assertEquals(WorkflowRunStatus.WAITING, r.view(id).status)
        assertEquals(StepStatus.RETRY_WAIT, r.step(id, "a")!!.status)
        assertEquals(0, r.engine.sweep().retriesPublished)           // not due yet
        r.advance(Duration.ofSeconds(10)); assertEquals(1, r.engine.sweep().retriesPublished); r.drain()
        assertEquals(2, r.step(id, "a")!!.attempt)
        assertEquals(WorkflowRunStatus.WAITING, r.view(id).status)
        r.advance(Duration.ofSeconds(19)); assertEquals(0, r.engine.sweep().retriesPublished)   // second backoff is 20s (exponential)
        r.advance(Duration.ofSeconds(1)); assertEquals(1, r.engine.sweep().retriesPublished); r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertEquals(3, r.step(id, "a")!!.attempt)
        assertEquals(listOf("w1", "w1", "w1", "w2"), r.writeOrder)
        assertTrue(r.audit.events(AuditDomains.WORKFLOW).contains("STEP_RETRY"))
    }

    @Test fun `when retries are exhausted the run fails with the last error`() {
        val retry = RetryPolicy(maxAttempts = 2, initialBackoff = Duration.ofSeconds(1))
        val r = rig(wf("rt", act("a", "w1", retry = retry)))
        r.data.script("w1", PortOutcome.Failure("DOWN", true), PortOutcome.Failure("STILL_DOWN", true))
        val id = r.startOk("rt"); r.drain()
        r.advance(Duration.ofSeconds(1)); r.engine.sweep(); r.drain()
        assertEquals(WorkflowRunStatus.FAILED, r.view(id).status)
        assertEquals("STILL_DOWN", r.view(id).errorCode)
        assertEquals(2, r.step(id, "a")!!.attempt)
    }

    @Test fun `a non retryable failure is not retried`() {
        val r = rig(wf("rt", act("a", "w1", retry = RetryPolicy(maxAttempts = 5))))
        r.data.script("w1", PortOutcome.Failure("INVALID", false))
        val id = r.startOk("rt"); r.drain()
        assertEquals(WorkflowRunStatus.FAILED, r.view(id).status)
        assertEquals(1, r.writes("w1"))
    }

    @Test fun `onError routes a failed step to a handler step and the run can still succeed`() {
        val r = rig(wf("er", act("a", "w1", next = "z", onError = "h"), act("h", "wErr", next = "z"), WorkflowStep("z", StepKind.END)))
        r.data.script("w1", PortOutcome.Failure("BOOM", false))
        val id = r.startOk("er"); r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertEquals(StepStatus.FAILED, r.step(id, "a")!!.status)
        assertEquals("BOOM", r.step(id, "a")!!.errorCode)
        assertEquals(listOf("w1", "wErr"), r.writeOrder)
    }

    @Test fun `an unknown write outcome fails the run with that code and skips onError and retries`() {
        val r = rig(wf("uk", act("a", "w1", next = "z", onError = "h", retry = RetryPolicy(maxAttempts = 5)), act("h", "wErr", next = "z"), WorkflowStep("z", StepKind.END)))
        r.data.script("w1", PortOutcome.Failure(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, true))   // even a retryable claim is overruled
        val id = r.startOk("uk"); r.drain()
        assertEquals(WorkflowRunStatus.FAILED, r.view(id).status)
        assertEquals(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, r.view(id).errorCode)
        assertEquals(listOf("w1"), r.writeOrder)                                                       // one attempt, error branch not entered
        assertEquals(StepStatus.FAILED, r.step(id, "a")!!.status)
    }

    @Test fun `a rejected write is a definite failure that onError may handle and is not retried`() {
        val r = rig(wf("rj", act("a", "w1", next = "z", onError = "h", retry = RetryPolicy(maxAttempts = 5)), act("h", "wErr", next = "z"), WorkflowStep("z", StepKind.END)))
        r.data.script("w1", PortOutcome.Failure(ActionErrorCodes.MUTATION_REJECTED, true))
        val id = r.startOk("rj"); r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertEquals(ActionErrorCodes.MUTATION_REJECTED, r.step(id, "a")!!.errorCode)
        assertEquals(listOf("w1", "wErr"), r.writeOrder)
    }

    @Test fun `a step with an unknown outcome is never compensated but earlier finished steps still are`() {
        val r = rig(wf("sagaU", act("s1", "w1", comp = "c1"), act("s2", "w2", comp = "c2"), act("s3", "w3", comp = "c3")))
        r.data.script("w3", PortOutcome.Failure(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, false))
        val id = r.startOk("sagaU"); r.drain()
        assertEquals(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, r.view(id).errorCode)
        assertEquals(listOf("w1", "w2", "w3", "c2", "c1"), r.writeOrder)                               // no "c3": it must not assume w3 was not applied
        assertEquals(0, r.writes("c3"))
    }

    @Test fun `a thrown exception inside a step becomes a failed step not a lost run`() {
        val r = rig(wf("ex", act("a", "w1")))
        r.data.outcome = PortOutcome.Failure("X", false)
        val id = r.startOk("ex"); r.drain()
        assertEquals(WorkflowRunStatus.FAILED, r.view(id).status)
        assertEquals(0, r.queue.readyCount()); assertEquals(0, r.queue.inFlightCount())
    }

    // ---- queue: redelivery, DLQ, crash recovery ------------------------------------------------------------------

    @Test fun `a duplicated message does not repeat the effect`() {
        val r = rig(two)
        val id = r.startOk("two")
        val body = WorkflowJob.forStep(Fx.tenantA, id, "a").encode()
        r.queue.publishRaw(body); r.queue.publishRaw(body)
        r.drain()
        assertEquals(1, r.writes("w1")); assertEquals(1, r.writes("w2"))
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
    }

    @Test fun `a consumer that dies before acking only causes a redelivery`() {
        val r = rig(two)
        val id = r.startOk("two")
        assertNotNull(r.queue.poll())               // taken, never acked
        r.queue.requeueInFlight()
        r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertEquals(1, r.writes("w1"))
    }

    @Test fun `an outage does not dead-letter anything and the sweeper recovers the run afterwards`() {
        val r = rig(two, maxDeliveries = 3)
        val id = r.startOk("two")
        r.runStore.failGet = true
        r.drain()
        assertEquals(0, r.queue.deadLetterBodies().size)             // outage != poison
        assertEquals(0, r.queue.readyCount())                        // acked, not hot-requeued
        r.runStore.failGet = false
        assertEquals(WorkflowRunStatus.PENDING, r.view(id).status)
        assertEquals(0, r.stored(id).processFailures)                // an outage is not the run's fault
        r.advance(Duration.ofMinutes(3))
        assertEquals(1, r.engine.sweep().republished)
        r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertEquals(1, r.writes("w1")); assertEquals(1, r.writes("w2"))
    }

    @Test fun `a malformed message is rejected straight to the dead letter queue and cannot touch a run`() {
        val r = rig(two)
        val id = r.startOk("two")
        r.queue.publishRaw("garbage"); r.queue.publishRaw("v1|not-a-uuid|x|y|z"); r.queue.publishRaw("v1|${UUID.randomUUID()}|${Fx.tenantA}|$id|bad step!")
        r.drain()
        assertEquals(3, r.queue.deadLetterBodies().size)
        assertEquals(3, r.worker.drainDeadLetters())
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)   // the real job still ran
    }

    @Test fun `a forged job for another tenant finds nothing`() {
        val r = rig(two)
        val id = r.startOk("two")
        r.queue.publishRaw(WorkflowJob.forStep(Fx.tenantB, id, "a").encode())
        assertEquals(3, r.drain())
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertEquals(1, r.writes("w1"))
    }

    @Test fun `a crashed worker is recovered by the sweeper and the effect happens exactly once`() {
        val r = rig(wf("cr", act("a", "w1"), act("b", "w2")))
        val id = r.startOk("cr")
        // the worker executed the action (the effect happened) but died before recording the step
        val before = r.stored(id)
        val ctx = Fx.ctx()
        r.actionRig.runtime.execute(ctx, ActionRequest("w1", mapOf("title" to Fx.str("T")), idempotencyKey = "wf:$id:a"))
        r.queue.poll()                                 // the job is gone with the dead worker
        assertTrue(r.baseStore.compareAndSet(before, before.withStep(before.steps["a"]!!.copy(status = StepStatus.RUNNING, attempt = 1)).copy(status = WorkflowRunStatus.RUNNING)))
        assertEquals(0, r.engine.sweep().republished)  // too fresh to be called lost
        r.advance(Duration.ofMinutes(3))
        assertEquals(1, r.engine.sweep().republished)
        r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertEquals(1, r.writes("w1"))                // replayed by the action idempotency key, not written again
        assertEquals(2, r.step(id, "a")!!.attempt)
    }

    @Test fun `a job lost before delivery is published again by the sweeper`() {
        val r = rig(two)
        r.queue.failPublish = true
        val id = r.startOk("two")
        assertEquals(0, r.queue.readyCount())
        r.queue.failPublish = false
        r.advance(Duration.ofMinutes(3))
        assertEquals(1, r.engine.sweep().republished)
        r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
    }

    @Test fun `an early nudge for a step that is still in its retry backoff does not run it`() {
        val r = rig(wf("rt", act("a", "w1", retry = RetryPolicy(maxAttempts = 3, initialBackoff = Duration.ofMinutes(5)))))
        r.data.script("w1", PortOutcome.Failure("DOWN", true))
        val id = r.startOk("rt"); r.drain()
        assertEquals(1, r.writes("w1"))
        r.queue.publishRaw(WorkflowJob.forStep(Fx.tenantA, id, "a").encode()); r.drain()
        assertEquals(1, r.writes("w1"))
        assertEquals(1, r.step(id, "a")!!.attempt)
        assertEquals(StepStatus.RETRY_WAIT, r.step(id, "a")!!.status)
    }

    @Test fun `a duplicate job for a step that another worker is running does nothing`() {
        val r = rig(two)
        val id = r.startOk("two")
        val before = r.stored(id)
        assertTrue(r.baseStore.compareAndSet(before, before.withStep(before.steps["a"]!!.copy(status = StepStatus.RUNNING, attempt = 1)).copy(status = WorkflowRunStatus.RUNNING)))
        r.drain()
        assertEquals(0, r.data.writes.size)
        assertEquals(StepStatus.RUNNING, r.step(id, "a")!!.status)
    }

    @Test fun `a job for a step that is waiting for its timer or an approval does not run it`() {
        val r = rig(wf("w", WorkflowStep("pause", StepKind.WAIT, wait = Duration.ofHours(1)), act("b", "w2")))
        val id = r.startOk("w"); r.drain()
        r.queue.publishRaw(WorkflowJob.forStep(Fx.tenantA, id, "pause").encode()); r.drain()
        assertEquals(StepStatus.WAITING, r.step(id, "pause")!!.status)
        assertEquals(0, r.data.writes.size)
    }

    @Test fun `an outcome from a worker the sweeper already gave up on is dropped, and the step is simply redone as a replay`() {
        val r = rig(two)
        val id = r.startOk("two")
        var once = true
        r.data.onWrite = { if (once) { once = false; r.advance(Duration.ofMinutes(3)); r.engine.sweep() } }   // the sweeper thinks the worker died
        r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertEquals(2, r.step(id, "a")!!.attempt)          // attempt 1's late result was discarded, attempt 2 recorded it
        assertEquals(1, r.writes("w1"))                      // ...and attempt 2 was a replay, not a second write
        assertEquals(1, r.writes("w2"))
    }

    // ---- WAIT --------------------------------------------------------------------------------------------------------

    @Test fun `WAIT holds the run until its time and then continues`() {
        val r = rig(wf("w", act("a", "w1"), WorkflowStep("pause", StepKind.WAIT, wait = Duration.ofMinutes(10)), act("b", "w2")))
        val id = r.startOk("w"); r.drain()
        assertEquals(WorkflowRunStatus.WAITING, r.view(id).status)
        assertEquals(listOf("w1"), r.writeOrder)
        r.advance(Duration.ofMinutes(9)); assertEquals(0, r.engine.sweep().timersCompleted)
        r.advance(Duration.ofMinutes(1)); assertEquals(1, r.engine.sweep().timersCompleted); r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertEquals(listOf("w1", "w2"), r.writeOrder)
    }

    // ---- approval ----------------------------------------------------------------------------------------------------

    private fun approvalFlow(onReject: String? = null, onExpire: String? = null, required: Int = 1, approvers: List<PrincipalSpec> = listOf(PrincipalSpec.User(Fx.user2, null)), expiresIn: Duration = Duration.ofHours(1)) = wf("ap",
        act("a", "w1"),
        WorkflowStep("okay", StepKind.APPROVAL, approval = ApprovalSpec("Release order", approvers, required, expiresIn, onReject = onReject, onExpire = onExpire)),
        act("b", "w2"),
        act("rej", "wErr", next = "fin"),
        WorkflowStep("fin", StepKind.END)
    ).let { d -> if (onReject == null && onExpire == null) d.copy(steps = d.steps.filter { it.id != "rej" && it.id != "fin" }) else d }

    private fun approvalId(r: WorkflowRig, id: UUID) = r.step(id, "okay")!!.approvalId!!

    @Test fun `an APPROVAL step pauses the run until an approver approves`() {
        val r = rig(approvalFlow())
        val id = r.startOk("ap"); r.drain()
        assertEquals(WorkflowRunStatus.WAITING, r.view(id).status)
        val aid = approvalId(r, id)
        assertEquals(1, r.approvals.inbox(r.ctx(Fx.user2)).size)
        assertInstanceOf(com.systemwebstudio.logic.approval.ApprovalResult.Ok::class.java, r.approvals.decide(r.ctx(Fx.user2), aid, DecisionKind.APPROVE, "ok"))
        r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertEquals(listOf("w1", "w2"), r.writeOrder)
    }

    @Test fun `a rejection fails the run with APPROVAL_REJECTED and never runs the next step`() {
        val r = rig(approvalFlow())
        val id = r.startOk("ap"); r.drain()
        r.approvals.decide(r.ctx(Fx.user2), approvalId(r, id), DecisionKind.REJECT, "no")
        r.drain()
        assertEquals(WorkflowRunStatus.FAILED, r.view(id).status)
        assertEquals("APPROVAL_REJECTED", r.view(id).errorCode)
        assertEquals(listOf("w1"), r.writeOrder)
    }

    @Test fun `onReject routes a rejected approval to another step`() {
        val r = rig(approvalFlow(onReject = "rej"))
        val id = r.startOk("ap"); r.drain()
        r.approvals.decide(r.ctx(Fx.user2), approvalId(r, id), DecisionKind.REJECT, null)
        r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertEquals(listOf("w1", "wErr"), r.writeOrder)
    }

    @Test fun `an unanswered approval expires and fails the run`() {
        val r = rig(approvalFlow())
        val id = r.startOk("ap"); r.drain()
        r.advance(Duration.ofHours(2))
        assertEquals(1, r.engine.sweep().approvalsExpired)
        r.drain()
        assertEquals(WorkflowRunStatus.FAILED, r.view(id).status)
        assertEquals("APPROVAL_EXPIRED", r.view(id).errorCode)
        assertEquals(ApprovalStatus.EXPIRED, r.approvalStore.get(Fx.tenantA, approvalId(r, id))!!.status)
    }

    @Test fun `a decision whose callback was lost is reconciled by the sweeper`() {
        val r = rig(approvalFlow(), approvalListener = false)
        val id = r.startOk("ap"); r.drain()
        r.approvals.decide(r.ctx(Fx.user2), approvalId(r, id), DecisionKind.APPROVE, null)
        r.drain()
        assertEquals(WorkflowRunStatus.WAITING, r.view(id).status)   // nobody told the engine
        assertEquals(1, r.engine.sweep().approvalsReconciled)
        r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
    }

    @Test fun `only a snapshotted approver can decide and a quorum needs enough approvals`() {
        val r = rig(approvalFlow(required = 2, approvers = listOf(PrincipalSpec.Group("finance"))))
        val id = r.startOk("ap"); r.drain()
        val aid = approvalId(r, id)
        val stranger = UUID.randomUUID()
        assertEquals("FORBIDDEN", (r.approvals.decide(r.ctx(stranger), aid, DecisionKind.APPROVE, null) as com.systemwebstudio.logic.approval.ApprovalResult.Failed).code)
        r.approvals.decide(r.ctx(Fx.user2), aid, DecisionKind.APPROVE, null); r.drain()
        assertEquals(WorkflowRunStatus.WAITING, r.view(id).status)          // 1 of 2
        r.approvals.decide(r.ctx(user3), aid, DecisionKind.APPROVE, null); r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
    }

    @Test fun `cancelling a run cancels its pending approval`() {
        val r = rig(approvalFlow())
        val id = r.startOk("ap"); r.drain()
        r.engine.cancel(r.ctx(), id)
        assertEquals(WorkflowRunStatus.CANCELLED, r.view(id).status)
        assertEquals(ApprovalStatus.CANCELLED, r.approvalStore.get(Fx.tenantA, approvalId(r, id))!!.status)
    }

    // ---- compensation / cancel -------------------------------------------------------------------------------------

    private val saga = wf("saga", act("s1", "w1", comp = "c1"), act("s2", "w2", comp = "c2"), act("s3", "w3"))

    @Test fun `a failing step compensates the finished steps in reverse order`() {
        val r = rig(saga)
        r.data.script("w3", PortOutcome.Failure("BOOM", false))
        val id = r.startOk("saga"); r.drain()
        assertEquals(WorkflowRunStatus.FAILED, r.view(id).status)
        assertEquals(listOf("w1", "w2", "w3", "c2", "c1"), r.writeOrder)
        assertEquals(CompensationState.DONE, r.view(id).compensation)
        assertTrue(r.audit.events(AuditDomains.WORKFLOW).contains("COMPENSATED"))
    }

    @Test fun `a compensation that fails is reported as PARTIAL and the others still run`() {
        val r = rig(saga)
        r.data.script("w3", PortOutcome.Failure("BOOM", false))
        r.data.script("c2", PortOutcome.Failure("NO", false))
        val id = r.startOk("saga"); r.drain()
        assertEquals(CompensationState.PARTIAL, r.view(id).compensation)
        assertEquals(1, r.writes("c1"))
    }

    @Test fun `replaying the compensation job does not compensate twice`() {
        val r = rig(saga)
        r.data.script("w3", PortOutcome.Failure("BOOM", false))
        val id = r.startOk("saga"); r.drain()
        r.queue.publishRaw(WorkflowJob.forStep(Fx.tenantA, id, WorkflowJob.COMPENSATE).encode()); r.drain()
        assertEquals(1, r.writes("c1")); assertEquals(1, r.writes("c2"))
    }

    @Test fun `cancel stops the run before any step and is idempotent`() {
        val r = rig(two)
        val id = r.startOk("two")
        assertEquals(WorkflowRunStatus.CANCELLED, (r.engine.cancel(r.ctx(), id) as WorkflowResult.Ok).value.status)
        assertEquals(WorkflowRunStatus.CANCELLED, (r.engine.cancel(r.ctx(), id) as WorkflowResult.Ok).value.status)
        r.drain()
        assertEquals(0, r.data.writes.size)
    }

    @Test fun `cancel can compensate what already ran when the workflow asks for it`() {
        val r = rig(wf("cc", act("s1", "w1", comp = "c1"), WorkflowStep("pause", StepKind.WAIT, wait = Duration.ofHours(1)), act("s2", "w2"), compensateOnCancel = true))
        val id = r.startOk("cc"); r.drain()
        r.engine.cancel(r.ctx(), id); r.drain()
        assertEquals(listOf("w1", "c1"), r.writeOrder)
        assertEquals(CompensationState.DONE, r.view(id).compensation)
        r.advance(Duration.ofHours(2)); r.engine.sweep(); r.drain()
        assertEquals(listOf("w1", "c1"), r.writeOrder)    // the cancelled run never continues
    }

    @Test fun `a stuck compensation is resumed by the sweeper`() {
        val r = rig(saga)
        r.data.script("w3", PortOutcome.Failure("BOOM", false))
        val id = r.startOk("saga")
        r.worker.runOnce(); r.worker.runOnce(); r.worker.runOnce()      // s1, s2, s3 fail → compensation job queued
        assertNotNull(r.queue.poll())                                     // the compensation job is lost with a dead consumer
        assertEquals(CompensationState.IN_PROGRESS, r.view(id).compensation)
        r.advance(Duration.ofMinutes(3))
        assertEquals(1, r.engine.sweep().compensationsResumed)
        r.drain()
        assertEquals(CompensationState.DONE, r.view(id).compensation)
    }

    // ---- limits, isolation, permissions ------------------------------------------------------------------------------

    @Test fun `a cycle in the definition ends at the step execution limit and each pass really runs`() {
        val r = rig(wf("loopy", act("a", "w1", next = "a"), limits = WorkflowLimits(maxStepExecutions = 4)))
        val id = r.startOk("loopy"); r.drain()
        assertEquals(WorkflowRunStatus.FAILED, r.view(id).status)
        assertEquals("LIMIT_EXCEEDED", r.view(id).errorCode)
        assertEquals(4, r.writes("w1"))                 // a revisit gets its own idempotency key; it is not a replay
    }

    @Test fun `a run that exceeds its maximum duration times out`() {
        val r = rig(wf("slow", act("a", "w1"), limits = WorkflowLimits(maxDuration = Duration.ofHours(1))))
        val id = r.startOk("slow")
        r.advance(Duration.ofHours(2)); r.drain()
        assertEquals("TIMEOUT", r.view(id).errorCode)
        assertEquals(0, r.data.writes.size)
    }

    @Test fun `the sweeper times out a waiting run that exceeded its duration`() {
        val r = rig(approvalFlow(expiresIn = Duration.ofDays(1)).copy(limits = WorkflowLimits(maxDuration = Duration.ofHours(1))))
        val id = r.startOk("ap"); r.drain()
        r.advance(Duration.ofHours(2))
        assertEquals(1, r.engine.sweep().timedOut)
        assertEquals("TIMEOUT", r.view(id).errorCode)
    }

    @Test fun `a tenant disabled while the run is in flight stops it`() {
        val r = rig(two)
        val id = r.startOk("two")
        r.tenants.disabled = setOf(Fx.tenantA)
        r.drain()
        assertEquals(WorkflowRunStatus.FAILED, r.view(id).status)
        assertEquals("TENANT_DISABLED", r.view(id).errorCode)
        assertEquals(0, r.data.writes.size)
    }

    @Test fun `permissions are re-checked at every step`() {
        val r = rig(two)
        val id = r.startOk("two")
        r.worker.runOnce()                               // step a ran
        r.access.denyPermissions = setOf(LogicPermissions.ACTION_EXECUTE)
        r.drain()
        assertEquals(WorkflowRunStatus.FAILED, r.view(id).status)
        assertEquals("FORBIDDEN", r.view(id).errorCode)
        assertEquals(listOf("w1"), r.writeOrder)
    }

    @Test fun `the run acts as the user who started it`() {
        val r = rig(two)
        r.startOk("two", ctx = r.ctx(Fx.user2)); r.drain()
        assertEquals(setOf(Fx.user2), r.data.writes.map { it.first.actor.userId }.toSet())
    }

    @Test fun `a workflow step may start a child workflow and the nesting is bounded`() {
        val child = wf("child", act("a", "w1"))
        val who = mapOf("who" to ValueRef.Literal(Fx.str("x")))
        val loopy = wf("loopy", WorkflowStep("s", StepKind.ACTION, actionRef = "swSelf", inputs = who))
        val r = rig(wf("parent", WorkflowStep("s", StepKind.ACTION, actionRef = "sw", inputs = who)), child, loopy)
        r.startOk("parent"); r.drain()
        assertEquals(2, r.baseStore.list(Fx.tenantA, null, 50).size)
        assertTrue(r.baseStore.list(Fx.tenantA, null, 50).all { it.status == WorkflowRunStatus.SUCCEEDED })

        val r2 = rig(loopy)
        r2.startOk("loopy"); r2.drain()
        val all = r2.baseStore.list(Fx.tenantA, null, 50)
        assertTrue(all.size in 2..5, "recursion must stop, got ${all.size} runs")
        assertTrue(all.all { it.status.terminal })
        assertEquals(WorkflowRunStatus.FAILED, all.maxByOrNull { it.depth }!!.status)
    }

    // ---- TEST mode ---------------------------------------------------------------------------------------------------

    @Test fun `a TEST run executes nothing real and says so`() {
        val r = rig(approvalFlow())
        val id = r.startOk("ap", mode = ExecutionMode.TEST); r.drain()
        val v = r.view(id)
        assertEquals(WorkflowRunStatus.SUCCEEDED, v.status)
        assertEquals(ExecutionMode.TEST, v.mode)
        assertEquals(0, r.data.writes.size)
        assertEquals(0, r.approvalStore.pendingFor(Fx.tenantA, Fx.user2, 10).size)
        assertTrue(v.steps.all { it.simulated })
        assertEquals(DryRunLevel.NOT_EXECUTED, v.steps.first { it.stepId == "a" }.dryRunLevel)
        assertTrue(r.defs.modes.contains(ExecutionMode.TEST))
    }

    @Test fun `a TEST run does not wait and does not compensate`() {
        val r = rig(wf("t", act("s1", "w1", comp = "c1"), WorkflowStep("pause", StepKind.WAIT, wait = Duration.ofDays(3)), act("s2", "w2")))
        val id = r.startOk("t", mode = ExecutionMode.TEST); r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
        assertEquals(CompensationState.NONE, r.view(id).compensation)
        assertEquals(0, r.data.writes.size)
    }

    @Test fun `a TEST run uses a different idempotency scope than the LIVE run`() {
        val r = rig(two)
        val live = r.startOk("two", key = "same")
        val test = r.startOk("two", key = "same", mode = ExecutionMode.TEST)
        assertNotEquals(live, test)
    }

    // ---- scheduler hand-over ---------------------------------------------------------------------------------------

    private fun fire(target: ScheduleTarget, key: String = "sched:s1:1", input: JsonNode? = null) =
        ScheduledRunRequest(Fx.tenantA, Fx.appA, UUID.randomUUID(), Fx.user, target, input, Fx.now, key)

    @Test fun `a scheduled fire starts a workflow run as the schedule owner and is idempotent`() {
        val r = rig(two)
        val req = fire(ScheduleTarget.Workflow("two"))
        val a = assertInstanceOf(EnqueueOutcome.Enqueued::class.java, r.engine.enqueue(req))
        val b = assertInstanceOf(EnqueueOutcome.Enqueued::class.java, r.engine.enqueue(req))
        assertEquals(a.runRef, b.runRef)
        r.drain()
        assertEquals(1, r.writes("w1"))
    }

    @Test fun `a scheduled action runs as a one step run with retries`() {
        val r = rig()
        r.data.script("w1", PortOutcome.Failure("DOWN", true))
        assertInstanceOf(EnqueueOutcome.Enqueued::class.java, r.engine.enqueue(fire(ScheduleTarget.Action("w1"), input = Fx.obj("title" to Fx.str("nightly")))))
        r.drain()
        r.advance(Duration.ofSeconds(2)); r.engine.sweep(); r.drain()
        assertEquals(2, r.writes("w1"))
        assertEquals("nightly", r.data.writes.last().second.params["title"]!!.asString())
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.baseStore.list(Fx.tenantA, null, 5).single().status)
    }

    @Test fun `a scheduled fire for a disabled tenant or a missing permission is refused`() {
        val r = rig(two)
        r.tenants.disabled = setOf(Fx.tenantA)
        val t = assertInstanceOf(EnqueueOutcome.Failed::class.java, r.engine.enqueue(fire(ScheduleTarget.Workflow("two"))))
        assertEquals("TENANT_DISABLED", t.code)
        r.tenants.disabled = emptySet(); r.access.denyPermissions = setOf(LogicPermissions.WORKFLOW_EXECUTE)
        assertEquals("FORBIDDEN", assertInstanceOf(EnqueueOutcome.Failed::class.java, r.engine.enqueue(fire(ScheduleTarget.Workflow("two"), key = "sched:s1:2"))).code)
    }

    @Test fun `START_WORKFLOW via a plain action result is audited at the workflow level`() {
        val r = rig(two)
        val id = r.startOk("two"); r.drain()
        val ev = r.audit.events(AuditDomains.WORKFLOW)
        assertTrue(ev.contains("START_REQUESTED") && ev.contains("SUCCEEDED"), "got $ev")
        assertEquals(id, UUID.fromString(r.audit.records.first { it.event == "SUCCEEDED" }.runId))
    }
}
