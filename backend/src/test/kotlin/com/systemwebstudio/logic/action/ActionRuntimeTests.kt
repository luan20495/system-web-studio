package com.systemwebstudio.logic.action

import com.systemwebstudio.logic.action.Fx.json
import com.systemwebstudio.logic.action.Fx.str
import com.systemwebstudio.logic.action.handlers.DefaultActionHandlers
import com.systemwebstudio.logic.action.handlers.NavigateActionHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.concurrent.Executors

class ActionRuntimeTests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }

    private fun rig(
        vararg defs: ActionDefinition,
        audit: RecordingAudit = RecordingAudit(),
        access: FakeAccess = FakeAccess(),
        tenants: FakeTenants = FakeTenants(),
        registry: ((FakeDataPort, FakeNotifyPort, FakeWorkflowPort) -> ActionHandlerRegistry)? = null,
        bindings: ActionBindingPort? = null,
        ceiling: ActionLimits = ActionLimits(),
        maxChain: Int = 16
    ) = ActionRig.build(*defs, audit = audit, access = access, tenants = tenants, registry = registry, bindings = bindings, ceiling = ceiling, executor = executor, maxChain = maxChain)

    private fun failure(r: ActionResult) = assertInstanceOf(ActionResult.Failed::class.java, r)
    private fun createReq(key: String? = "k-1", title: String = "t") = ActionRequest("m1", mapOf("title" to str(title)), idempotencyKey = key)

    // ---- end to end (T13: Event → ActionRef → definition → tenant/authz → input → validate → handler → audit → result) --------

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
        assertEquals(ExecutionMode.LIVE, r.audit.entries.first().mode)
    }

    @Test fun `each mutation type reaches the data port with the right kind and validated params`() {
        val cases = mapOf(
            ActionType.CREATE_RECORD to WriteKind.CREATE, ActionType.SUBMIT_FORM to WriteKind.SUBMIT
        )
        for ((type, kind) in cases) {
            val r = rig(Fx.mutation(type))
            val res = r.runtime.execute(Fx.ctx(), createReq("k-$type", "hello"))
            assertInstanceOf(ActionResult.Ok::class.java, res)
            val (ctx, req) = r.data.writes.single()
            assertEquals(kind, req.kind); assertEquals("orders.create", req.queryRef)
            assertEquals("hello", req.params["title"]!!.asString()); assertEquals("k-$type", req.idempotencyKey)
            assertEquals(Fx.tenantA, ctx.tenantId)
        }
        val u = rig(Fx.mutation(ActionType.UPDATE_RECORD, inputs = listOf(InputSpec("recordId", InputType.STRING, required = true))))
        u.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("recordId" to str("5")), idempotencyKey = "k-u"))
        assertEquals(WriteKind.UPDATE, u.data.writes.single().second.kind)
        val d = rig(Fx.mutation(ActionType.DELETE_RECORD, inputs = listOf(InputSpec("recordId", InputType.STRING, required = true))))
        d.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("recordId" to str("5")), idempotencyKey = "k-d"))
        assertEquals(WriteKind.DELETE, d.data.writes.single().second.kind)
    }

    @Test fun `CALL_API calls an approved datasource operation through the port`() {
        val r = rig(Fx.callApi())
        val res = r.runtime.execute(Fx.ctx(), ActionRequest("call", mapOf("q" to str("acme")), idempotencyKey = "k-c"))
        assertInstanceOf(ActionResult.Ok::class.java, res)
        val (_, req) = r.data.operations.single()
        assertEquals("crm", req.dataSourceRef); assertEquals("lookup", req.operationKey); assertEquals("acme", req.params["q"]!!.asString())
        assertTrue(r.data.writes.isEmpty())
    }

    @Test fun `CALL_API cannot be smuggled a url through its definition or its input`() {
        val withUrl = Fx.callApi().copy(config = Fx.callApi().config + Fx.cfg("url" to "http://169.254.169.254/latest"))
        val r = rig(withUrl)
        val f = failure(r.runtime.execute(Fx.ctx(), ActionRequest("call", emptyMap(), idempotencyKey = "k")))
        assertEquals(ActionErrorCodes.INVALID_DEFINITION, f.code); assertTrue(f.details.containsKey("config.url"))
        assertTrue(r.data.operations.isEmpty())
        val ok = rig(Fx.callApi())
        val f2 = failure(ok.runtime.execute(Fx.ctx(), ActionRequest("call", mapOf("url" to str("http://evil.example")), idempotencyKey = "k")))
        assertEquals("not a declared input", f2.details["url"])
        assertTrue(ok.data.operations.isEmpty())
    }

    @Test fun `NOTIFY reaches the notification port`() {
        val r = rig(Fx.notify())
        assertInstanceOf(ActionResult.Ok::class.java, r.runtime.execute(Fx.ctx(), ActionRequest("n1", mapOf("name" to str("Ann")), idempotencyKey = "k-n")))
        val sent = r.notify.sent.single()
        assertEquals(NotifyChannel.IN_APP, sent.channel); assertEquals("welcome", sent.templateRef); assertEquals("k-n", sent.idempotencyKey)
    }

    @Test fun `START_WORKFLOW needs WORKFLOW_EXECUTE and a key, then starts one run with the caller depth`() {
        val r = rig(Fx.startWorkflow())
        assertEquals(ActionErrorCodes.IDEMPOTENCY_KEY_REQUIRED, failure(r.runtime.execute(Fx.ctx(), ActionRequest("sw", mapOf("who" to str("x"))))).code)
        assertInstanceOf(ActionResult.Ok::class.java, r.runtime.execute(Fx.ctx(), ActionRequest("sw", mapOf("who" to str("x")), idempotencyKey = "k-w", callDepth = 1)))
        assertEquals(1, r.workflow.started.single().callDepth)
        assertTrue(LogicPermissions.WORKFLOW_EXECUTE in r.access.permissions)

        val denied = rig(Fx.startWorkflow(), access = FakeAccess(denyPermissions = setOf(LogicPermissions.WORKFLOW_EXECUTE)))
        assertEquals(ActionErrorCodes.FORBIDDEN, failure(denied.runtime.execute(Fx.ctx(), ActionRequest("sw", mapOf("who" to str("x")), idempotencyKey = "k-w"))).code)
        assertTrue(denied.workflow.started.isEmpty())
    }

    // ---- rejections ---------------------------------------------------------------------------------------------

    @Test fun `unknown action is UNKNOWN_ACTION and no port or handler is touched`() {
        val r = rig(Fx.navigate())
        val f = failure(r.runtime.execute(Fx.ctx(), ActionRequest("nope")))
        assertEquals(ActionErrorCodes.UNKNOWN_ACTION, f.code)
        assertTrue(r.access.requests.isEmpty())
        assertEquals(listOf(AuditPhase.REJECTED), r.audit.phases)
    }

    @Test fun `another tenant's action is indistinguishable from an unknown one`() {
        val r = rig(Fx.navigate(tenant = Fx.tenantB))          // provider leaks it; runtime must still refuse
        val f = failure(r.runtime.execute(Fx.ctx(Fx.tenantA), ActionRequest("go-home")))
        assertEquals(ActionErrorCodes.UNKNOWN_ACTION, f.code)
        assertTrue(r.access.requests.isEmpty())
    }

    @Test fun `another app's action is unknown too`() {
        val r = rig(Fx.navigate().copy(appId = Fx.appB))
        assertEquals(ActionErrorCodes.UNKNOWN_ACTION, failure(r.runtime.execute(Fx.ctx(app = Fx.appA), ActionRequest("go-home"))).code)
    }

    @Test fun `disabled action is unknown`() {
        val r = rig(Fx.navigate().copy(enabled = false))
        assertEquals(ActionErrorCodes.UNKNOWN_ACTION, failure(r.runtime.execute(Fx.ctx(), ActionRequest("go-home"))).code)
    }

    @Test fun `a request without an application context is rejected before anything is looked up`() {
        val r = rig(Fx.navigate())
        assertEquals(ActionErrorCodes.INVALID_INPUT, failure(r.runtime.execute(Fx.ctx(app = null), ActionRequest("go-home"))).code)
        assertTrue(r.defs.modes.isEmpty())
    }

    // ---- authorization (tenant, user, resource, permission) -----------------------------------------------------

    @Test fun `APP_USE and ACTION_EXECUTE are both required, checked per execution on the right resource`() {
        val r = rig(Fx.navigate())
        r.runtime.execute(Fx.ctx(), ActionRequest("go-home"))
        r.runtime.execute(Fx.ctx(), ActionRequest("go-home"))
        assertEquals(listOf("APP_USE", "ACTION_EXECUTE", "APP_USE", "ACTION_EXECUTE"), r.access.permissions)
        val actionCheck = r.access.requests[1].second
        assertEquals(ResourceKind.ACTION, actionCheck.resourceKind); assertEquals("go-home", actionCheck.resourceId); assertEquals(Fx.appA, actionCheck.appId)
        assertEquals(Fx.user, r.access.requests[1].first.actor.userId)
    }

    @Test fun `each missing permission is FORBIDDEN, audited DENIED, and input is never inspected`() {
        for (perm in listOf(LogicPermissions.APP_USE, LogicPermissions.ACTION_EXECUTE)) {
            val r = rig(Fx.mutation(ActionType.CREATE_RECORD), access = FakeAccess(denyPermissions = setOf(perm)))
            val f = failure(r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("garbage" to str("x")))))
            assertEquals(ActionErrorCodes.FORBIDDEN, f.code)
            assertTrue(f.details.isEmpty())                                  // no validation detail leaks to an unauthorized caller
            assertTrue(r.data.writes.isEmpty())
            assertEquals(listOf(AuditPhase.DENIED), r.audit.phases)
            assertEquals("$perm: missing $perm", r.audit.entries.single().detail)
        }
    }

    @Test fun `a permission declared by the definition is enforced in addition`() {
        val def = Fx.navigate().copy(requiredPermission = "ORDERS_WRITE")
        val ok = rig(def)
        assertInstanceOf(ActionResult.Ok::class.java, ok.runtime.execute(Fx.ctx(), ActionRequest("go-home")))
        assertTrue("ORDERS_WRITE" in ok.access.permissions)
        val no = rig(def, access = FakeAccess(denyPermissions = setOf("ORDERS_WRITE")))
        assertEquals(ActionErrorCodes.FORBIDDEN, failure(no.runtime.execute(Fx.ctx(), ActionRequest("go-home"))).code)
    }

    @Test fun `the UI cannot grant itself anything - payload and explicit inputs never reach the permission check`() {
        val def = Fx.navigate(inputs = listOf(InputSpec("permission"), InputSpec("tenantId")))
            .copy(inputMapping = mapOf("permission" to InputSource.FormField("permission"), "tenantId" to InputSource.FormField("tenantId")))
        val r = rig(def, access = FakeAccess(denyAll = true))
        val ev = EventPayload(form = mapOf("permission" to str("ACTION_EXECUTE"), "tenantId" to str(Fx.tenantB.toString())))
        assertEquals(ActionErrorCodes.FORBIDDEN, failure(r.runtime.execute(Fx.ctx(), ActionRequest("go-home", payload = ev))).code)
        // the first (default-deny) check fails and nothing the client put in the payload became a permission, tenant or resource
        assertEquals(listOf("APP_USE"), r.access.permissions)
        assertEquals(Fx.tenantA, r.access.requests.single().first.tenantId)
        assertEquals("APP_USE", r.access.requests.single().second.permission)
    }

    @Test fun `an access port that throws fails closed as retryable DEPENDENCY_UNAVAILABLE`() {
        val r = rig(Fx.navigate(), access = FakeAccess(throwing = true))
        val f = failure(r.runtime.execute(Fx.ctx(), ActionRequest("go-home")))
        assertEquals(ActionErrorCodes.DEPENDENCY_UNAVAILABLE, f.code); assertTrue(f.retryable)
    }

    @Test fun `a disabled tenant runs nothing and touches no port`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD), tenants = FakeTenants(disabled = setOf(Fx.tenantA)))
        val f = failure(r.runtime.execute(Fx.ctx(), createReq()))
        assertEquals(ActionErrorCodes.TENANT_DISABLED, f.code)
        assertTrue(r.access.requests.isEmpty() && r.data.writes.isEmpty() && r.defs.modes.isEmpty())
        assertEquals(listOf(AuditPhase.REJECTED), r.audit.phases)
    }

    @Test fun `a tenant gate that throws fails closed and retryable`() {
        val r = rig(Fx.navigate(), tenants = FakeTenants(throwing = true))
        val f = failure(r.runtime.execute(Fx.ctx(), ActionRequest("go-home")))
        assertEquals(ActionErrorCodes.DEPENDENCY_UNAVAILABLE, f.code); assertTrue(f.retryable)
    }

    @Test fun `tenant isolation - two tenants with the same action id and key never see each other`() {
        val a = Fx.mutation(ActionType.CREATE_RECORD)
        val b = a.copy(tenantId = Fx.tenantB)
        val r = rig(a)
        val rtB = DefaultActionRuntime(FakeDefinitions(b), DefaultActionHandlers.registry(json, ActionPorts(r.data)), FakeAccess(), FakeTenants(), r.runs, RecordingAudit(), InputResolver(json), clock = Fx.clock, executor = executor)
        r.runtime.execute(Fx.ctx(Fx.tenantA), createReq("key-1")); rtB.execute(Fx.ctx(Fx.tenantB), createReq("key-1"))
        assertEquals(listOf(Fx.tenantA, Fx.tenantB), r.data.writes.map { it.first.tenantId })
        // and tenant A cannot run tenant B's definition even if the provider hands it over
        val leaky = rig(b)
        assertEquals(ActionErrorCodes.UNKNOWN_ACTION, failure(leaky.runtime.execute(Fx.ctx(Fx.tenantA), createReq())).code)
    }

    // ---- validation ---------------------------------------------------------------------------------------------

    @Test fun `invalid input is INVALID_INPUT with per field details and nothing runs`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        val f = failure(r.runtime.execute(Fx.ctx(), ActionRequest("m1", emptyMap(), idempotencyKey = "k")))
        assertEquals(ActionErrorCodes.INVALID_INPUT, f.code); assertEquals("is required", f.details["title"])
        assertTrue(r.data.writes.isEmpty())
        assertEquals(listOf(AuditPhase.REJECTED), r.audit.phases)
    }

    @Test fun `oversized input is LIMIT_EXCEEDED`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD), ceiling = ActionLimits(maxInputBytes = 50))
        val f = failure(r.runtime.execute(Fx.ctx(), createReq(title = "x".repeat(500))))
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
        assertTrue(failure(noRef.runtime.execute(Fx.ctx(), createReq())).details.containsKey("config.queryRef"))
    }

    @Test fun `a type without a registered handler is UNSUPPORTED_ACTION_TYPE`() {
        val r = rig(Fx.navigate(), registry = { _, _, _ -> ActionHandlerRegistry.of(NavigateActionHandler(json)) })
        val r2 = rig(Fx.mutation(ActionType.CREATE_RECORD), registry = { _, _, _ -> ActionHandlerRegistry.of(NavigateActionHandler(json)) })
        assertInstanceOf(ActionResult.Ok::class.java, r.runtime.execute(Fx.ctx(), ActionRequest("go-home")))
        assertEquals(ActionErrorCodes.UNSUPPORTED_ACTION_TYPE, failure(r2.runtime.execute(Fx.ctx(), createReq())).code)
    }

    @Test fun `port not wired gives a typed non retryable NOT_IMPLEMENTED`() {
        val rt = DefaultActionRuntime(
            FakeDefinitions(Fx.mutation(ActionType.CREATE_RECORD)), DefaultActionHandlers.registry(json, ActionPorts()), FakeAccess(), FakeTenants(),
            InMemoryActionRunStore(), RecordingAudit(), InputResolver(json), clock = Fx.clock, executor = executor
        )
        val f = failure(rt.execute(Fx.ctx(), createReq()))
        assertEquals(ActionErrorCodes.NOT_IMPLEMENTED, f.code); assertEquals(false, f.retryable)
    }

    // ---- downstream failure model -------------------------------------------------------------------------------

    @Test fun `downstream failure keeps its code and retryable flag, audited FAILED`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        r.data.outcome = PortOutcome.Failure("QUERY_TIMEOUT", true, "slow")
        val f = failure(r.runtime.execute(Fx.ctx(), createReq()))
        assertEquals("QUERY_TIMEOUT", f.code); assertTrue(f.retryable)
        assertEquals(listOf(AuditPhase.STARTED, AuditPhase.FAILED), r.audit.phases)
        assertEquals("QUERY_TIMEOUT", r.audit.entries.last().errorCode)
    }

    @Test fun `a throwing handler becomes HANDLER_ERROR without leaking the message`() {
        val throwing = object : ActionHandler {
            override val type = ActionType.NAVIGATE
            override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult =
                error("jdbc:postgresql://secret-host/db password=hunter2")
            override fun preview(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult = execute(ctx, definition, input, run)
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
            override fun preview(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult = execute(ctx, definition, input, run)
        }
        val def = Fx.mutation(ActionType.CREATE_RECORD).copy(limits = ActionLimits(timeout = Duration.ofMillis(100)), idempotency = IdempotencyPolicy.OPTIONAL)
        val r = rig(def, registry = { _, _, _ -> ActionHandlerRegistry.of(slow) })
        val noKey = failure(r.runtime.execute(Fx.ctx(), createReq(key = null)))
        assertEquals(ActionErrorCodes.TIMEOUT, noKey.code); assertEquals(false, noKey.retryable)
        val withKey = failure(r.runtime.execute(Fx.ctx(), createReq(key = "k1")))
        assertEquals(ActionErrorCodes.TIMEOUT, withKey.code); assertTrue(withKey.retryable)
    }

    @Test fun `a request can shorten the timeout but never lengthen it`() {
        val slow = object : ActionHandler {
            override val type = ActionType.NAVIGATE
            override fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult { Thread.sleep(400); return ActionResult.Ok(str("done")) }
            override fun preview(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult = execute(ctx, definition, input, run)
        }
        val def = Fx.navigate().copy(limits = ActionLimits(timeout = Duration.ofMillis(100)))
        val r = rig(def, registry = { _, _, _ -> ActionHandlerRegistry.of(slow) })
        assertEquals(ActionErrorCodes.TIMEOUT, failure(r.runtime.execute(Fx.ctx(), ActionRequest("go-home", timeout = Duration.ofSeconds(60)))).code)   // longer request ignored
    }

    // ---- idempotency (write actions) ---------------------------------------------------------------------------

    @Test fun `a write action without a key is rejected - writes are never fire and forget`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        assertEquals(ActionErrorCodes.IDEMPOTENCY_KEY_REQUIRED, failure(r.runtime.execute(Fx.ctx(), createReq(key = null))).code)
        assertTrue(r.data.writes.isEmpty())
    }

    @Test fun `same key and same input replays the first result without writing again`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        val req = createReq("key-1")
        val first = r.runtime.execute(Fx.ctx(), req)
        val second = r.runtime.execute(Fx.ctx(), req)
        assertEquals(first, second)
        assertEquals(1, r.data.writes.size)
        assertTrue(AuditPhase.REPLAYED in r.audit.phases)
    }

    @Test fun `retrying after a lost response creates no duplicate business record`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        repeat(5) { r.runtime.execute(Fx.ctx(), createReq("same-key")) }
        assertEquals(1, r.data.writes.size)
        assertEquals(1, r.runs.size())
    }

    @Test fun `same key with different input is IDEMPOTENCY_KEY_REUSED`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        r.runtime.execute(Fx.ctx(), createReq("key-1", "a"))
        val f = failure(r.runtime.execute(Fx.ctx(), createReq("key-1", "b")))
        assertEquals(ActionErrorCodes.IDEMPOTENCY_KEY_REUSED, f.code)
        assertEquals(1, r.data.writes.size)
    }

    @Test fun `a retryable failure can be retried with the same key and then succeeds once`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        r.data.outcome = PortOutcome.Failure("DEPENDENCY_UNAVAILABLE", true)
        val req = createReq("key-1")
        assertTrue(failure(r.runtime.execute(Fx.ctx(), req)).retryable)
        r.data.outcome = PortOutcome.Success(json.createObjectNode().put("id", "rec-9"))
        assertInstanceOf(ActionResult.Ok::class.java, r.runtime.execute(Fx.ctx(), req))
        assertInstanceOf(ActionResult.Ok::class.java, r.runtime.execute(Fx.ctx(), req))
        assertEquals(2, r.data.writes.size)                       // the failed attempt + the successful one; the third call was a replay
    }

    @Test fun `keys are validated`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        assertEquals(ActionErrorCodes.IDEMPOTENCY_KEY_INVALID, failure(r.runtime.execute(Fx.ctx(), createReq("bad key!"))).code)
        assertTrue(r.data.writes.isEmpty())
    }

    @Test fun `policy OPTIONAL allows a missing key and still de-duplicates a supplied one`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD).copy(idempotency = IdempotencyPolicy.OPTIONAL))
        r.runtime.execute(Fx.ctx(), createReq(null)); r.runtime.execute(Fx.ctx(), createReq(null))
        assertEquals(2, r.data.writes.size)
        r.runtime.execute(Fx.ctx(), createReq("k")); r.runtime.execute(Fx.ctx(), createReq("k"))
        assertEquals(3, r.data.writes.size)
    }

    @Test fun `the same key by another user is a separate run and never replays someone else's result`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        r.runtime.execute(Fx.ctx(userId = Fx.user), createReq("shared-key"))
        r.runtime.execute(Fx.ctx(userId = Fx.user2), createReq("shared-key"))
        assertEquals(2, r.data.writes.size)
        assertEquals(setOf(Fx.user, Fx.user2), r.data.writes.map { it.first.actor.userId }.toSet())
    }

    // ---- audit --------------------------------------------------------------------------------------------------

    @Test fun `audit failure at STARTED fails closed so the action never runs and the key stays retryable`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD), audit = RecordingAudit(failOn = setOf(AuditPhase.STARTED)))
        val f = failure(r.runtime.execute(Fx.ctx(), createReq("key-1")))
        assertEquals(ActionErrorCodes.AUDIT_UNAVAILABLE, f.code); assertTrue(f.retryable)
        assertTrue(r.data.writes.isEmpty())
        assertEquals(RunStatus.FAILED, r.runs.find(RunKey(Fx.tenantA, Fx.appA, "m1", Fx.user, "key-1"))!!.status)
    }

    @Test fun `audit failure after the action ran does not hide the result`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD), audit = RecordingAudit(failOn = setOf(AuditPhase.SUCCEEDED)))
        assertInstanceOf(ActionResult.Ok::class.java, r.runtime.execute(Fx.ctx(), createReq()))
        assertEquals(1, r.data.writes.size)
    }

    @Test fun `audit entries carry identity and trigger but no input values`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        val trig = TriggerInfo(TriggerKind.WORKFLOW_STEP, "step-3")
        r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("TOP-SECRET")), idempotencyKey = "k", trigger = trig))
        assertTrue(r.audit.entries.none { it.toString().contains("TOP-SECRET") })
        assertEquals(trig, r.audit.entries.first().trigger)
        assertEquals("req-1", r.audit.entries.first().requestId)
        assertEquals(Fx.user, r.audit.entries.first().actor.userId)
        assertEquals(Fx.appA, r.audit.entries.first().projectId)
    }

    // ---- chaining -----------------------------------------------------------------------------------------------

    private fun chainRig(maxChain: Int = 16, vararg extra: ActionDefinition, create: ActionDefinition = Fx.mutation(ActionType.CREATE_RECORD).copy(onSuccess = listOf("go-home"), onError = listOf("oops"))) =
        rig(
            create,
            Fx.navigate("go-home", inputs = listOf(InputSpec("id", InputType.STRING))).copy(inputMapping = mapOf("id" to InputSource.PreviousResult("id"))),
            Fx.navigate("oops"), *extra, maxChain = maxChain
        )

    @Test fun `onSuccess runs after a successful action with its result as input`() {
        val r = chainRig()
        val ex = r.runtime.run(Fx.ctx(), createReq("k-chain"))
        assertInstanceOf(ActionResult.Ok::class.java, ex.result)
        val f = ex.followUps.single()
        assertEquals("go-home", f.actionId); assertEquals(ChainOn.SUCCESS, f.on)
        assertEquals("rec-1", (f.result as ActionResult.Ok).output.get("params").get("id").asString())   // PreviousResult.id from the write's output
    }

    @Test fun `onError runs after a failure but not after a refusal`() {
        val r = chainRig()
        r.data.outcome = PortOutcome.Failure("QUERY_TIMEOUT", false, "x")
        val ex = r.runtime.run(Fx.ctx(), createReq("k-e"))
        assertEquals(ChainOn.ERROR, ex.followUps.single().on); assertEquals("oops", ex.followUps.single().actionId)

        val denied = rig(Fx.mutation(ActionType.CREATE_RECORD).copy(onError = listOf("oops")), Fx.navigate("oops"), access = FakeAccess(denyPermissions = setOf(LogicPermissions.ACTION_EXECUTE)))
        assertTrue(denied.runtime.run(Fx.ctx(), createReq("k-d")).followUps.isEmpty())            // a denied caller learns nothing from side effects
        val badInput = chainRig()
        assertEquals(ChainOn.ERROR, badInput.runtime.run(Fx.ctx(), ActionRequest("m1", emptyMap(), idempotencyKey = "k-i")).followUps.single().on)
        val replayGate = rig(Fx.mutation(ActionType.CREATE_RECORD).copy(onError = listOf("oops")), Fx.navigate("oops"))
        assertTrue(replayGate.runtime.run(Fx.ctx(), createReq(null)).followUps.isEmpty())          // missing key is a gate failure, not an action failure
    }

    @Test fun `chained writes get derived keys so re-running the chain is idempotent`() {
        val withTitle = Fx.mutation(ActionType.CREATE_RECORD).copy(inputMapping = mapOf("title" to InputSource.Literal(str("T"))))
        val r = rig(Fx.navigate("first").copy(onSuccess = listOf("m1")), withTitle)
        val req = ActionRequest("first", idempotencyKey = "root")
        r.runtime.run(Fx.ctx(), req); r.runtime.run(Fx.ctx(), req)
        assertEquals(1, r.data.writes.size)
        assertEquals("root:s:m1", r.data.writes.single().second.idempotencyKey)
        // a chained write without any root key cannot be made safe, so it is refused instead of run unprotected
        val noKey = r.runtime.run(Fx.ctx(), ActionRequest("first"))
        assertEquals(ActionErrorCodes.IDEMPOTENCY_KEY_REQUIRED, (noKey.followUps.single().result as ActionResult.Failed).code)
        assertEquals(1, r.data.writes.size)
    }

    @Test fun `a chain cycle is stopped by the call depth limit`() {
        val a = Fx.navigate("a").copy(onSuccess = listOf("b"))
        val b = Fx.navigate("b").copy(onSuccess = listOf("a"))
        val r = rig(a, b, ceiling = ActionLimits(maxCallDepth = 3))
        val ex = r.runtime.run(Fx.ctx(), ActionRequest("a", idempotencyKey = "root"))
        assertEquals(listOf("b", "a", "b", "a"), ex.followUps.map { it.actionId }.take(4))
        assertEquals(ActionErrorCodes.LIMIT_EXCEEDED, (ex.followUps.last().result as ActionResult.Failed).code)
        assertTrue(ex.followUps.size <= 4)
    }

    @Test fun `a fan out is bounded by the chain budget`() {
        val fan = Fx.navigate("fan").copy(onSuccess = (1..5).map { "n$it" })
        val r = rig(fan, *(1..5).map { Fx.navigate("n$it") }.toTypedArray(), maxChain = 3)
        val ex = r.runtime.run(Fx.ctx(), ActionRequest("fan"))
        assertEquals(3, ex.followUps.count { it.result is ActionResult.Ok })
        assertEquals(2, ex.followUps.count { (it.result as? ActionResult.Failed)?.code == ActionErrorCodes.LIMIT_EXCEEDED })
    }

    // ---- dispatch (UI events) -----------------------------------------------------------------------------------

    private fun ev(id: String = "evt-1", section: String = "btn", type: EventType = EventType.ON_CLICK, payload: EventPayload = EventPayload.EMPTY, mode: ExecutionMode = ExecutionMode.LIVE) =
        Event(id, type, section, payload, Fx.now, mode)

    @Test fun `dispatch runs bound actions only, maps payload through the definition, and is safe to redeliver`() {
        val nav = Fx.navigate(inputs = listOf(InputSpec("orderId", InputType.STRING))).copy(inputMapping = mapOf("orderId" to InputSource.RouteParam("orderId")))
        val create = Fx.mutation(ActionType.CREATE_RECORD).copy(inputMapping = mapOf("title" to InputSource.FormField("title")))
        val refs = listOf(
            ActionRef("r1", "btn", EventType.ON_CLICK, "go-home"), ActionRef("r2", "btn", EventType.ON_CLICK, "m1"),
            ActionRef("r3", "other", EventType.ON_CLICK, "go-home"), ActionRef("r4", "btn", EventType.ON_CLICK, "missing"),
            ActionRef("r5", "btn", EventType.ON_SUBMIT, "go-home")
        )
        val r = rig(nav, create, bindings = FakeBindings(refs))
        val event = ev(payload = EventPayload(routeParams = mapOf("orderId" to str("7")), form = mapOf("title" to str("T"), "leak" to str("x"))))

        val out = r.runtime.dispatch(Fx.ctx(), event)
        assertEquals(listOf("r1", "r2", "r4"), out.map { it.ref.id })                      // other section / other event type are not run
        val navOut = assertInstanceOf(ActionResult.Ok::class.java, out[0].result).output
        assertEquals("7", navOut.get("params").get("orderId").asString())
        assertInstanceOf(ActionResult.Ok::class.java, out[1].result)
        assertEquals(ActionErrorCodes.UNKNOWN_ACTION, failure(out[2].result).code)         // one failure does not stop the others
        assertEquals(setOf("title"), r.data.writes.single().second.params.keys)           // "leak" is not mapped ⇒ never forwarded
        assertEquals("evt:evt-1:r2", r.data.writes.single().second.idempotencyKey)
        assertEquals(TriggerInfo(TriggerKind.UI_EVENT, "btn.onClick", "evt-1"), r.audit.entries.first { it.phase == AuditPhase.STARTED }.trigger)

        r.runtime.dispatch(Fx.ctx(), event)                                                // redelivery
        assertEquals(1, r.data.writes.size)
    }

    @Test fun `dispatch cannot spoof the acting user through the payload`() {
        val def = Fx.mutation(ActionType.CREATE_RECORD, inputs = listOf(InputSpec("title", InputType.STRING, required = true), InputSpec("owner", InputType.STRING)))
            .copy(inputMapping = mapOf("title" to InputSource.FormField("title"), "owner" to InputSource.Context(ContextKey.USER_ID)))
        val r = rig(def, bindings = FakeBindings(listOf(ActionRef("r", "btn", EventType.ON_CLICK, "m1"))))
        r.runtime.dispatch(Fx.ctx(), ev(payload = EventPayload(form = mapOf("title" to str("T"), "owner" to str("someone-else")))))
        assertEquals(Fx.user.toString(), r.data.writes.single().second.params["owner"]!!.asString())
    }

    @Test fun `dispatch sanitises a client event id before it becomes an idempotency key`() {
        val create = Fx.mutation(ActionType.CREATE_RECORD).copy(inputMapping = mapOf("title" to InputSource.Literal(str("T"))))
        val r = rig(create, bindings = FakeBindings(listOf(ActionRef("r", "btn", EventType.ON_CLICK, "m1"))))
        val weird = ev(id = "evt with spaces & ünïcode " + "x".repeat(300))
        val out = r.runtime.dispatch(Fx.ctx(), weird)
        assertInstanceOf(ActionResult.Ok::class.java, out.single().result)
        val key = r.data.writes.single().second.idempotencyKey!!
        assertTrue(key.length <= 128 && key.matches(Regex("^[A-Za-z0-9._:-]+$")))
        r.runtime.dispatch(Fx.ctx(), weird)
        assertEquals(1, r.data.writes.size)                                                // still de-duplicated
    }

    @Test fun `dispatch without a binding port or application does nothing`() {
        assertEquals(emptyList<DispatchOutcome>(), rig(Fx.navigate()).runtime.dispatch(Fx.ctx(), ev()))
        val r = rig(Fx.navigate(), bindings = FakeBindings(listOf(ActionRef("r", "btn", EventType.ON_CLICK, "go-home"))))
        assertEquals(emptyList<DispatchOutcome>(), r.runtime.dispatch(Fx.ctx(app = null), ev()))
    }

    // ---- TEST mode ----------------------------------------------------------------------------------------------

    @Test fun `TEST mode never writes, never needs a key, keeps no run state and says what would run`() {
        val r = rig(Fx.mutation(ActionType.CREATE_RECORD))
        val res = r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("T")), mode = ExecutionMode.TEST))
        val w = assertInstanceOf(ActionResult.WouldRun::class.java, res)
        assertEquals("m1", w.actionId); assertEquals(ActionType.CREATE_RECORD, w.type); assertEquals(DryRunLevel.NOT_EXECUTED, w.level)
        assertTrue(r.data.writes.isEmpty() && r.data.operations.isEmpty())
        assertEquals(0, r.runs.size())
        assertEquals(listOf(AuditPhase.PREVIEWED), r.audit.phases)
        assertEquals(ExecutionMode.TEST, r.audit.entries.single().mode)
    }

    @Test fun `TEST mode does not pretend a dry run happened when the connector cannot do one`() {
        val r = rig(Fx.callApi())
        val w = r.runtime.execute(Fx.ctx(), ActionRequest("call", mapOf("q" to str("x")), mode = ExecutionMode.TEST)) as ActionResult.WouldRun
        assertEquals(DryRunLevel.NOT_EXECUTED, w.level)
        assertNull(w.output)
        assertTrue(r.data.operations.isEmpty())
        r.data.dryRunOutcome = DryRunOutcome.Sandboxed(json.createObjectNode().put("found", true))
        val sb = r.runtime.execute(Fx.ctx(), ActionRequest("call", mapOf("q" to str("x")), mode = ExecutionMode.TEST)) as ActionResult.WouldRun
        assertEquals(DryRunLevel.SANDBOX, sb.level); assertTrue(sb.output!!.get("found").asBoolean(false))
        assertTrue(r.data.operations.isEmpty())
    }

    @Test fun `TEST mode sends no notification and starts no workflow`() {
        val r = rig(Fx.notify(), Fx.startWorkflow())
        assertInstanceOf(ActionResult.WouldRun::class.java, r.runtime.execute(Fx.ctx(), ActionRequest("n1", mode = ExecutionMode.TEST)))
        assertInstanceOf(ActionResult.WouldRun::class.java, r.runtime.execute(Fx.ctx(), ActionRequest("sw", mode = ExecutionMode.TEST)))
        assertTrue(r.notify.sent.isEmpty() && r.workflow.started.isEmpty())
    }

    @Test fun `TEST mode still enforces tenant gate, permissions and input validation`() {
        val denied = rig(Fx.mutation(ActionType.CREATE_RECORD), access = FakeAccess(denyAll = true))
        assertEquals(ActionErrorCodes.FORBIDDEN, failure(denied.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("title" to str("T")), mode = ExecutionMode.TEST))).code)
        assertTrue(denied.access.requests.all { it.second.mode == ExecutionMode.TEST })     // the access port can demand more for the draft
        val off = rig(Fx.navigate(), tenants = FakeTenants(disabled = setOf(Fx.tenantA)))
        assertEquals(ActionErrorCodes.TENANT_DISABLED, failure(off.runtime.execute(Fx.ctx(), ActionRequest("go-home", mode = ExecutionMode.TEST))).code)
        val bad = rig(Fx.mutation(ActionType.CREATE_RECORD))
        assertEquals(ActionErrorCodes.INVALID_INPUT, failure(bad.runtime.execute(Fx.ctx(), ActionRequest("m1", emptyMap(), mode = ExecutionMode.TEST))).code)
    }

    @Test fun `TEST mode reads the draft definition and lists the chain instead of running it`() {
        val r = rig(Fx.navigate().copy(onSuccess = listOf("other")), Fx.navigate("other"))
        val ex = r.runtime.run(Fx.ctx(), ActionRequest("go-home", mode = ExecutionMode.TEST))
        assertEquals(listOf(ExecutionMode.TEST), r.defs.modes.distinct())
        val w = ex.result as ActionResult.WouldRun
        assertEquals("other", w.plan.get("onSuccess").get(0).asString())
        assertTrue(ex.followUps.isEmpty())
    }

    @Test fun `dispatch of a TEST event previews every bound action`() {
        val create = Fx.mutation(ActionType.CREATE_RECORD).copy(inputMapping = mapOf("title" to InputSource.FormField("title")))
        val r = rig(create, bindings = FakeBindings(listOf(ActionRef("r", "btn", EventType.ON_CLICK, "m1"))))
        val out = r.runtime.dispatch(Fx.ctx(), ev(mode = ExecutionMode.TEST, payload = EventPayload(form = mapOf("title" to str("T")))))
        assertInstanceOf(ActionResult.WouldRun::class.java, out.single().result)
        assertTrue(r.data.writes.isEmpty())
        assertFalse(r.audit.phases.contains(AuditPhase.STARTED))
    }
}
