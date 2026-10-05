package com.systemwebstudio.logic.action

import com.systemwebstudio.logic.action.Fx.json
import com.systemwebstudio.logic.action.Fx.str
import com.systemwebstudio.logic.action.handlers.DefaultActionHandlers
import com.systemwebstudio.logic.action.handlers.NavigateActionHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executors

class ActionRuntimeTests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }

    private class Rig(
        val defs: FakeDefinitions,
        val data: FakeDataPort = FakeDataPort(),
        val notify: FakeNotifyPort = FakeNotifyPort(),
        val workflow: FakeWorkflowPort = FakeWorkflowPort(),
        val authorizer: FakeAuthorizer = FakeAuthorizer(),
        val audit: RecordingAudit = RecordingAudit(),
        val runs: InMemoryActionRunStore = InMemoryActionRunStore(),
        val runtime: ActionRuntime
    )

    private fun rig(
        vararg defs: ActionDefinition,
        audit: RecordingAudit = RecordingAudit(),
        authorizer: FakeAuthorizer = FakeAuthorizer(),
        registry: ((FakeDataPort, FakeNotifyPort, FakeWorkflowPort) -> ActionHandlerRegistry)? = null,
        bindings: ActionBindingPort? = null,
        ceiling: ActionLimits = ActionLimits()
    ): Rig {
        val d = FakeDefinitions(*defs)
        val data = FakeDataPort(); val notify = FakeNotifyPort(); val wf = FakeWorkflowPort(); val runs = InMemoryActionRunStore()
        val reg = registry?.invoke(data, notify, wf) ?: DefaultActionHandlers.registry(json, ActionPorts(data, notify, wf))
        val rt = DefaultActionRuntime(d, reg, authorizer, runs, audit, bindings, ceiling, Fx.clock, executor)
        return Rig(d, data, notify, wf, authorizer, audit, runs, rt)
    }

    private fun failure(r: ActionResult) = assertInstanceOf(ActionResult.Failed::class.java, r)

    // ---- end to end ---------------------------------------------------------------------------------------------

    @Test fun `NAVIGATE runs end to end and is audited STARTED then SUCCEEDED`() {
        val r = rig(Fx.navigate(inputs = listOf(InputSpec("orderId", InputType.STRING))))
        val res = r.runtime.execute(Fx.ctx(), ActionRequest("go-home", mapOf("orderId" to str("42"))))
        val out = assertInstanceOf(ActionResult.Ok::class.java, res).output
        assertEquals("NAVIGATE", out.get("action").asString())
        assertEquals("home", out.get("pageId").asString())
        assertEquals("42", out.get("params").get("orderId").asString())
        assertEquals(listOf(AuditPhase.STARTED, AuditPhase.SUCCEEDED), r.audit.phases)
        assertEquals(Fx.tenantA, r.audit.entries.first().tenantId)
        assertEquals(ActionType.NAVIGATE, r.audit.entries.first().actionType)
    }

    @Test fun `each data type reaches the data port with the right kind and validated params`() {
        val cases = mapOf(
            ActionType.CREATE_RECORD to MutationKind.CREATE, ActionType.SUBMIT_FORM to MutationKind.SUBMIT
        )
        for ((type, kind) in cases) {
            val r = rig(Fx.mutation(type))
            val res = r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("hello")), idempotencyKey = "k-$type"))
            assertInstanceOf(ActionResult.Ok::class.java, res)
            val (ctx, req) = r.data.mutations.single()
            assertEquals(kind, req.kind); assertEquals("orders.create", req.mutationId)
            assertEquals("hello", req.params["title"]!!.asString()); assertEquals("k-$type", req.idempotencyKey)
            assertEquals(Fx.tenantA, ctx.tenantId)
        }
    }

    // ---- rejections ---------------------------------------------------------------------------------------------

    @Test fun `unknown action is UNKNOWN_ACTION and no port or handler is touched`() {
        val r = rig(Fx.navigate())
        val f = failure(r.runtime.execute(Fx.ctx(), ActionRequest("nope")))
        assertEquals(ActionErrorCodes.UNKNOWN_ACTION, f.code)
        assertTrue(r.authorizer.calls.isEmpty())
        assertEquals(listOf(AuditPhase.REJECTED), r.audit.phases)
    }

    @Test fun `another tenant's action is indistinguishable from an unknown one`() {
        val r = rig(Fx.navigate(tenant = Fx.tenantB))          // provider leaks it; runtime must still refuse
        val f = failure(r.runtime.execute(Fx.ctx(Fx.tenantA), ActionRequest("go-home")))
        assertEquals(ActionErrorCodes.UNKNOWN_ACTION, f.code)
        assertTrue(r.authorizer.calls.isEmpty())
    }

    @Test fun `disabled action is unknown`() {
        val r = rig(Fx.navigate().copy(enabled = false))
        assertEquals(ActionErrorCodes.UNKNOWN_ACTION, failure(r.runtime.execute(Fx.ctx(), ActionRequest("go-home"))).code)
    }

    @Test fun `missing permission is FORBIDDEN, audited DENIED, and input is never inspected`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD), authorizer = FakeAuthorizer(allow = false))
        val f = failure(r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("garbage" to str("x")))))
        assertEquals(ActionErrorCodes.FORBIDDEN, f.code)
        assertTrue(f.details.isEmpty())                                  // no validation detail leaks to an unauthorized caller
        assertTrue(r.data.mutations.isEmpty())
        assertEquals(listOf(AuditPhase.DENIED), r.audit.phases)
        assertEquals("missing ACTION_EXECUTE", r.audit.entries.single().detail)
    }

    @Test fun `an authorizer that throws fails closed as retryable DEPENDENCY_UNAVAILABLE`() {
        val boom = object : ActionAuthorizer { override fun authorize(ctx: ActionContext, action: ActionDefinition): AuthorizationDecision = error("db down") }
        val d = FakeDefinitions(Fx.navigate())
        val rt = DefaultActionRuntime(d, DefaultActionHandlers.registry(json), boom, InMemoryActionRunStore(), RecordingAudit(), clock = Fx.clock, executor = executor)
        val f = failure(rt.execute(Fx.ctx(), ActionRequest("go-home")))
        assertEquals(ActionErrorCodes.DEPENDENCY_UNAVAILABLE, f.code); assertTrue(f.retryable)
    }

    @Test fun `invalid input is INVALID_INPUT with per field details and nothing runs`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        val f = failure(r.runtime.execute(Fx.ctx(), ActionRequest("m1", emptyMap())))
        assertEquals(ActionErrorCodes.INVALID_INPUT, f.code); assertEquals("is required", f.details["title"])
        assertTrue(r.data.mutations.isEmpty())
        assertEquals(listOf(AuditPhase.REJECTED), r.audit.phases)
    }

    @Test fun `oversized input is LIMIT_EXCEEDED`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD), ceiling = ActionLimits(maxInputBytes = 50))
        val f = failure(r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("x".repeat(500))))))
        assertEquals(ActionErrorCodes.LIMIT_EXCEEDED, f.code)
    }

    @Test fun `call depth beyond the ceiling is rejected even if the definition asks for more`() {
        val greedy = Fx.navigate().copy(limits = ActionLimits(maxCallDepth = 100))
        val r = rig(greedy, ceiling = ActionLimits(maxCallDepth = 2))
        assertEquals(ActionErrorCodes.LIMIT_EXCEEDED, failure(r.runtime.execute(Fx.ctx(), ActionRequest("go-home", callDepth = 3))).code)
        assertInstanceOf(ActionResult.Ok::class.java, r.runtime.execute(Fx.ctx(), ActionRequest("go-home", callDepth = 2)))
    }

    @Test fun `definition with a smuggled url or missing reference is INVALID_DEFINITION`() {
        val bad = Fx.navigate().copy(config = Fx.cfg("pageId" to "home", "url" to "http://169.254.169.254/"))
        val r = rig(bad)
        val f = failure(r.runtime.execute(Fx.ctx(), ActionRequest("go-home")))
        assertEquals(ActionErrorCodes.INVALID_DEFINITION, f.code); assertTrue(f.details.containsKey("config.url"))

        val noRef = rig(Fx.mutation(ActionType.CREATE_RECORD).copy(config = emptyMap()))
        assertTrue(failure(noRef.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("t"))))).details.containsKey("config.mutationId"))
    }

    @Test fun `a type without a registered handler is UNSUPPORTED_ACTION_TYPE`() {
        val r = rig(Fx.navigate(), registry = { _, _, _ -> ActionHandlerRegistry.of(NavigateActionHandler(json)) })
        val r2 = rig(Fx.mutation(ActionType.CREATE_RECORD), registry = { _, _, _ -> ActionHandlerRegistry.of(NavigateActionHandler(json)) })
        assertInstanceOf(ActionResult.Ok::class.java, r.runtime.execute(Fx.ctx(), ActionRequest("go-home")))
        assertEquals(ActionErrorCodes.UNSUPPORTED_ACTION_TYPE, failure(r2.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("t"))))).code)
    }

    @Test fun `port not wired gives a typed non retryable NOT_IMPLEMENTED`() {
        val d = FakeDefinitions(Fx.mutation(ActionType.CREATE_RECORD))
        val rt = DefaultActionRuntime(d, DefaultActionHandlers.registry(json, ActionPorts()), FakeAuthorizer(), InMemoryActionRunStore(), RecordingAudit(), clock = Fx.clock, executor = executor)
        val f = failure(rt.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("t")))))
        assertEquals(ActionErrorCodes.NOT_IMPLEMENTED, f.code); assertEquals(false, f.retryable)
    }

    // ---- downstream failure model -------------------------------------------------------------------------------

    @Test fun `downstream failure keeps its code and retryable flag, audited FAILED`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        r.data.outcome = PortOutcome.Failure("QUERY_TIMEOUT", true, "slow")
        val f = failure(r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("t")))))
        assertEquals("QUERY_TIMEOUT", f.code); assertTrue(f.retryable)
        assertEquals(listOf(AuditPhase.STARTED, AuditPhase.FAILED), r.audit.phases)
        assertEquals("QUERY_TIMEOUT", r.audit.entries.last().errorCode)
    }

    @Test fun `a throwing handler becomes HANDLER_ERROR without leaking the message`() {
        val throwing = object : ActionHandler {
            override val type = ActionType.NAVIGATE
            override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult =
                error("jdbc:postgresql://secret-host/db password=hunter2")
        }
        val r = rig(Fx.navigate(), registry = { _, _, _ -> ActionHandlerRegistry.of(throwing) })
        val f = failure(r.runtime.execute(Fx.ctx(), ActionRequest("go-home")))
        assertEquals(ActionErrorCodes.HANDLER_ERROR, f.code)
        assertTrue(!f.message!!.contains("hunter2") && !f.message!!.contains("secret-host"))
        assertEquals(AuditPhase.FAILED, r.audit.phases.last())
    }

    @Test fun `timeout cancels the handler, and a mutating action is retryable only with an idempotency key`() {
        val slow = object : ActionHandler {
            override val type = ActionType.CREATE_RECORD
            override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult {
                Thread.sleep(5_000); return ActionResult.Ok(str("late"))
            }
        }
        val def = Fx.mutation(ActionType.CREATE_RECORD).copy(limits = ActionLimits(timeout = Duration.ofMillis(100)))
        val r = rig(def, registry = { _, _, _ -> ActionHandlerRegistry.of(slow) })
        val noKey = failure(r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("t")))))
        assertEquals(ActionErrorCodes.TIMEOUT, noKey.code); assertEquals(false, noKey.retryable)
        val withKey = failure(r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("t")), idempotencyKey = "k1")))
        assertEquals(ActionErrorCodes.TIMEOUT, withKey.code); assertTrue(withKey.retryable)
    }

    // ---- idempotency --------------------------------------------------------------------------------------------

    @Test fun `same key and same input replays the first result without calling the port again`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        val req = ActionRequest("m1", mapOf("title" to str("t")), idempotencyKey = "key-1")
        val first = r.runtime.execute(Fx.ctx(), req)
        val second = r.runtime.execute(Fx.ctx(), req)
        assertEquals(first, second)
        assertEquals(1, r.data.mutations.size)
        assertTrue(AuditPhase.REPLAYED in r.audit.phases)
    }

    @Test fun `same key with different input is IDEMPOTENCY_KEY_REUSED`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("a")), idempotencyKey = "key-1"))
        val f = failure(r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("b")), idempotencyKey = "key-1")))
        assertEquals(ActionErrorCodes.IDEMPOTENCY_KEY_REUSED, f.code)
        assertEquals(1, r.data.mutations.size)
    }

    @Test fun `a retryable failure can be retried with the same key and then succeeds once`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        r.data.outcome = PortOutcome.Failure("DEPENDENCY_UNAVAILABLE", true)
        val req = ActionRequest("m1", mapOf("title" to str("t")), idempotencyKey = "key-1")
        assertTrue(failure(r.runtime.execute(Fx.ctx(), req)).retryable)
        r.data.outcome = PortOutcome.Success(json.createObjectNode().put("id", "rec-9"))
        assertInstanceOf(ActionResult.Ok::class.java, r.runtime.execute(Fx.ctx(), req))
        assertInstanceOf(ActionResult.Ok::class.java, r.runtime.execute(Fx.ctx(), req))
        assertEquals(2, r.data.mutations.size)
    }

    @Test fun `REQUIRED policy rejects a missing key and keys are validated`() {
        val def = Fx.mutation(ActionType.DELETE_RECORD, inputs = listOf(InputSpec("recordId", InputType.STRING, required = true)))
            .copy(idempotency = IdempotencyPolicy.REQUIRED)
        val r = rig(def)
        assertEquals(ActionErrorCodes.IDEMPOTENCY_KEY_REQUIRED, failure(r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("recordId" to str("1"))))).code)
        assertEquals(ActionErrorCodes.IDEMPOTENCY_KEY_INVALID, failure(r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("recordId" to str("1")), idempotencyKey = "bad key!"))).code)
        assertTrue(r.data.mutations.isEmpty())
    }

    @Test fun `policy NONE ignores the key so repeats do run again`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD).copy(idempotency = IdempotencyPolicy.NONE))
        val req = ActionRequest("m1", mapOf("title" to str("t")), idempotencyKey = "key-1")
        r.runtime.execute(Fx.ctx(), req); r.runtime.execute(Fx.ctx(), req)
        assertEquals(2, r.data.mutations.size)
        assertNull(r.data.mutations.first().second.idempotencyKey)
    }

    @Test fun `the same key in two tenants does not collide`() {
        val a = Fx.mutation(ActionType.CREATE_RECORD)
        val b = a.copy(tenantId = Fx.tenantB)
        val r = rig(a)   // tenant B has its own provider row in real life; reuse the rig with a second runtime sharing the run store
        val rtB = DefaultActionRuntime(FakeDefinitions(b), DefaultActionHandlers.registry(json, ActionPorts(r.data)), FakeAuthorizer(), r.runs, RecordingAudit(), clock = Fx.clock, executor = executor)
        val req = ActionRequest("m1", mapOf("title" to str("t")), idempotencyKey = "key-1")
        r.runtime.execute(Fx.ctx(Fx.tenantA), req); rtB.execute(Fx.ctx(Fx.tenantB), req)
        assertEquals(2, r.data.mutations.size)
    }

    // ---- audit --------------------------------------------------------------------------------------------------

    @Test fun `audit failure at STARTED fails closed so the action never runs and the key stays retryable`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD), audit = RecordingAudit(failOn = setOf(AuditPhase.STARTED)))
        val req = ActionRequest("m1", mapOf("title" to str("t")), idempotencyKey = "key-1")
        val f = failure(r.runtime.execute(Fx.ctx(), req))
        assertEquals(ActionErrorCodes.AUDIT_UNAVAILABLE, f.code); assertTrue(f.retryable)
        assertTrue(r.data.mutations.isEmpty())
        assertEquals(RunStatus.FAILED, r.runs.find(RunKey(Fx.tenantA, "m1", "key-1"))!!.status)
    }

    @Test fun `audit failure after the action ran does not hide the result`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD), audit = RecordingAudit(failOn = setOf(AuditPhase.SUCCEEDED)))
        assertInstanceOf(ActionResult.Ok::class.java, r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("t")))))
        assertEquals(1, r.data.mutations.size)
    }

    @Test fun `audit entries carry identity and trigger but no input values`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        val trig = TriggerInfo(TriggerKind.WORKFLOW_STEP, "step-3")
        r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("TOP-SECRET")), trigger = trig))
        assertTrue(r.audit.entries.none { it.toString().contains("TOP-SECRET") })
        assertEquals(trig, r.audit.entries.first().trigger)
        assertEquals("req-1", r.audit.entries.first().requestId)
        assertEquals(Fx.user, r.audit.entries.first().actor.userId)
    }

    // ---- dispatch -----------------------------------------------------------------------------------------------

    @Test fun `dispatch runs bound actions only, forwards declared payload fields, and is safe to redeliver`() {
        val nav = Fx.navigate(inputs = listOf(InputSpec("orderId", InputType.STRING)))
        val create = Fx.mutation(ActionType.CREATE_RECORD)
        val refs = listOf(
            ActionRef("r1", "btn.click", "go-home"), ActionRef("r2", "btn.click", "m1"),
            ActionRef("r3", "other.event", "go-home"), ActionRef("r4", "btn.click", "missing")
        )
        val r = rig(nav, create, bindings = FakeBindings(refs))
        val event = Event("evt-1", "btn.click", mapOf("orderId" to str("7"), "title" to str("T"), "leak" to str("x")), Fx.now)

        val out = r.runtime.dispatch(Fx.ctx(), event)
        assertEquals(listOf("r1", "r2", "r4"), out.map { it.ref.id })
        val navOut = assertInstanceOf(ActionResult.Ok::class.java, out[0].result).output
        assertEquals("7", navOut.get("params").get("orderId").asString())
        assertNull(navOut.get("params").get("title"))                                   // not declared by go-home ⇒ not forwarded
        assertInstanceOf(ActionResult.Ok::class.java, out[1].result)
        assertEquals(ActionErrorCodes.UNKNOWN_ACTION, failure(out[2].result).code)       // one failure does not stop the others
        assertEquals(setOf("title"), r.data.mutations.single().second.params.keys)
        assertEquals("evt:evt-1:r2", r.data.mutations.single().second.idempotencyKey)
        assertEquals(TriggerInfo(TriggerKind.UI_EVENT, "btn.click", "evt-1"), r.audit.entries.first { it.phase == AuditPhase.STARTED }.trigger)

        r.runtime.dispatch(Fx.ctx(), event)                                              // redelivery
        assertEquals(1, r.data.mutations.size)
    }

    @Test fun `dispatch without a binding port does nothing`() {
        assertEquals(emptyList<DispatchOutcome>(), rig(Fx.navigate()).runtime.dispatch(Fx.ctx(), Event("e", "x", emptyMap(), Fx.now)))
    }

}
