package com.systemwebstudio.logic.action

import com.systemwebstudio.logic.action.Fx.json
import com.systemwebstudio.logic.action.Fx.str
import com.systemwebstudio.logic.action.handlers.DefaultActionHandlers
import com.systemwebstudio.logic.action.handlers.NavigateActionHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID

class ActionHandlerTests {
    private val run = ActionRun("run-1", "idem-1", TriggerInfo(TriggerKind.UI_EVENT), 0, Fx.now)
    private val testRun = run.copy(idempotencyKey = null, mode = ExecutionMode.TEST)
    private val ctx = Fx.ctx()

    @Test fun `registry rejects duplicates and exposes lookup`() {
        val b = ActionHandlerRegistry.builder().register(NavigateActionHandler(json))
        assertThrows(IllegalArgumentException::class.java) { b.register(NavigateActionHandler(json)) }
        val reg = b.build()
        assertEquals(setOf(ActionType.NAVIGATE), reg.types)
        assertEquals(null, reg[ActionType.NOTIFY])
    }

    @Test fun `default registry covers every action type even with no ports wired`() {
        assertEquals(ActionType.entries.toSet(), DefaultActionHandlers.registry(json).types)
        assertEquals(ActionType.entries.toSet(), DefaultActionHandlers.registry(json, ActionPorts(FakeDataPort(), FakeNotifyPort(), FakeWorkflowPort())).types)
    }

    @Test fun `NAVIGATE requires a plain page id, not a url`() {
        val h = NavigateActionHandler(json)
        assertEquals(emptyList<DefinitionIssue>(), h.validate(Fx.navigate()))
        assertEquals("config.pageId", h.validate(Fx.navigate().copy(config = emptyMap())).single().path)
        assertEquals("config.pageId", h.validate(Fx.navigate().copy(config = Fx.cfg("pageId" to "https://evil.example"))).single().path)
        assertEquals("config.pageId", h.validate(Fx.navigate().copy(config = Fx.cfg("pageId" to "../admin"))).single().path)
    }

    @Test fun `REFRESH_QUERY returns a client instruction and reads no data`() {
        val data = FakeDataPort()
        val h = DefaultActionHandlers.registry(json, ActionPorts(data))[ActionType.REFRESH_QUERY]!!
        val def = ActionDefinition("r", Fx.tenantA, ActionType.REFRESH_QUERY, config = Fx.cfg("queryRef" to "orders.list"))
        assertEquals(emptyList<DefinitionIssue>(), h.validate(def))
        assertEquals("config.queryRef", h.validate(def.copy(config = emptyMap())).single().path)
        val out = assertInstanceOf(ActionResult.Ok::class.java, h.execute(ctx, def, ActionInput.EMPTY, run)).output
        assertEquals("REFRESH_QUERY", out.get("action").asString()); assertEquals("orders.list", out.get("queryRef").asString())
        assertTrue(data.writes.isEmpty() && data.operations.isEmpty())
    }

    @Test fun `UPDATE and DELETE need a required string recordId, CREATE does not`() {
        val reg = DefaultActionHandlers.registry(json, ActionPorts(FakeDataPort()))
        for (t in listOf(ActionType.UPDATE_RECORD, ActionType.DELETE_RECORD)) {
            assertEquals("inputs.recordId", reg[t]!!.validate(Fx.mutation(t, inputs = emptyList())).single().path)
            assertEquals("inputs.recordId", reg[t]!!.validate(Fx.mutation(t, inputs = listOf(InputSpec("recordId", InputType.NUMBER, required = true)))).single().path)
            assertEquals("inputs.recordId", reg[t]!!.validate(Fx.mutation(t, inputs = listOf(InputSpec("recordId", InputType.STRING)))).single().path)
            assertEquals(emptyList<DefinitionIssue>(), reg[t]!!.validate(Fx.mutation(t, inputs = listOf(InputSpec("recordId", InputType.STRING, required = true)))))
        }
        assertEquals(emptyList<DefinitionIssue>(), reg[ActionType.CREATE_RECORD]!!.validate(Fx.mutation(ActionType.CREATE_RECORD)))
    }

    @Test fun `every write type reaches the data port as a write of a WRITE query reference with its kind`() {
        val data = FakeDataPort()
        val reg = DefaultActionHandlers.registry(json, ActionPorts(data))
        val inputs = listOf(InputSpec("recordId", InputType.STRING, required = true))
        val input = ActionInput(mapOf("recordId" to str("9")))
        for (t in listOf(ActionType.CREATE_RECORD, ActionType.UPDATE_RECORD, ActionType.DELETE_RECORD, ActionType.SUBMIT_FORM)) {
            reg[t]!!.execute(ctx, Fx.mutation(t, inputs = inputs), input, run)
        }
        assertEquals(listOf(WriteKind.CREATE, WriteKind.UPDATE, WriteKind.DELETE, WriteKind.SUBMIT), data.writes.map { it.second.kind })
        assertTrue(data.writes.all { it.second.queryRef == "orders.create" && it.second.idempotencyKey == "idem-1" })
    }

    @Test fun `CALL_API takes an approved datasource operation, never a url`() {
        val data = FakeDataPort()
        val h = DefaultActionHandlers.registry(json, ActionPorts(data))[ActionType.CALL_API]!!
        val def = Fx.callApi()
        assertEquals(emptyList<DefinitionIssue>(), h.validate(def))
        assertEquals("config.dataSourceRef", h.validate(def.copy(config = Fx.cfg("operationKey" to "lookup"))).single().path)
        assertEquals("config.operationKey", h.validate(def.copy(config = Fx.cfg("dataSourceRef" to "crm"))).single().path)
        assertEquals("config.dataSourceRef", h.validate(def.copy(config = Fx.cfg("dataSourceRef" to "https://evil.example/x", "operationKey" to "lookup"))).single().path)
        assertEquals("config.operationKey", h.validate(def.copy(config = Fx.cfg("dataSourceRef" to "crm", "operationKey" to "../../etc"))).single().path)
        // a raw url key is rejected by the generic validator before any handler runs
        assertTrue(ActionDefinitionValidator.validate(def.copy(config = def.config + Fx.cfg("url" to "http://169.254.169.254/")), h).any { it.path == "config.url" })

        h.execute(ctx, def, ActionInput(mapOf("q" to str("x"))), run)
        val (_, req) = data.operations.single()
        assertEquals("crm", req.dataSourceRef); assertEquals("lookup", req.operationKey); assertEquals("idem-1", req.idempotencyKey)
    }

    @Test fun `NOTIFY validates channel template recipients and endpoint and sends to the port`() {
        val port = FakeNotifyPort()
        val h = DefaultActionHandlers.registry(json, ActionPorts(notify = port))[ActionType.NOTIFY]!!
        val uid = UUID.randomUUID()
        val recipients = json.createArrayNode().add(Fx.obj("kind" to str("USER"), "userId" to str(uid.toString()))).add(Fx.obj("kind" to str("ROLE"), "role" to str("managers")))
        val def = Fx.notify().copy(config = Fx.cfg("channel" to "EMAIL", "templateRef" to "welcome") + mapOf("recipients" to (recipients as JsonNode)))
        assertEquals(emptyList<DefinitionIssue>(), h.validate(def))
        assertEquals("config.channel", h.validate(def.copy(config = Fx.cfg("channel" to "PIGEON", "templateRef" to "welcome"))).single().path)
        assertEquals("config.templateRef", h.validate(def.copy(config = Fx.cfg("channel" to "EMAIL"))).single().path)
        assertEquals("config.recipients[0]", h.validate(def.copy(config = def.config + mapOf("recipients" to (json.createArrayNode().add(Fx.obj("kind" to str("USER"), "userId" to str("nope"))) as JsonNode)))).single().path)
        assertEquals("config.endpointRef", h.validate(def.copy(config = def.config + Fx.cfg("endpointRef" to "hook1"))).single().path)   // only for WEBHOOK
        assertEquals("config.endpointRef", h.validate(def.copy(config = Fx.cfg("channel" to "WEBHOOK", "templateRef" to "t"))).single().path)
        assertEquals(emptyList<DefinitionIssue>(), h.validate(def.copy(config = Fx.cfg("channel" to "WEBHOOK", "templateRef" to "t", "endpointRef" to "hook1"))))

        assertInstanceOf(ActionResult.Ok::class.java, h.execute(ctx, def, ActionInput(mapOf("name" to str("Ann"))), run))
        val sent = port.sent.single()
        assertEquals(NotifyChannel.EMAIL, sent.channel); assertEquals("welcome", sent.templateRef)
        assertEquals(listOf<PrincipalSpec>(PrincipalSpec.User(uid), PrincipalSpec.Role("managers")), sent.recipients)
    }

    @Test fun `START_WORKFLOW demands IdempotencyPolicy REQUIRED and passes the key and call depth`() {
        val port = FakeWorkflowPort()
        val h = DefaultActionHandlers.registry(json, ActionPorts(workflow = port))[ActionType.START_WORKFLOW]!!
        val def = Fx.startWorkflow()
        assertEquals(emptyList<DefinitionIssue>(), h.validate(def))
        assertEquals("idempotency", h.validate(def.copy(idempotency = IdempotencyPolicy.OPTIONAL)).single().path)
        h.execute(ctx, def, ActionInput(mapOf("who" to str("x"))), run.copy(callDepth = 2))
        val req = port.started.single()
        assertEquals("onboarding", req.workflowRef); assertEquals("idem-1", req.idempotencyKey); assertEquals("x", req.input.get("who").asString()); assertEquals(2, req.callDepth)
        val noKey = h.execute(ctx, def, ActionInput.EMPTY, run.copy(idempotencyKey = null))
        assertEquals(ActionErrorCodes.IDEMPOTENCY_KEY_REQUIRED, (noKey as ActionResult.Failed).code)
    }

    @Test fun `port failure and success map one to one`() {
        assertEquals(ActionResult.Failed("X", true, "m"), PortOutcome.Failure("X", true, "m").toResult())
        val ok = json.createObjectNode().put("a", 1)
        assertEquals(ActionResult.Ok(ok), PortOutcome.Success(ok).toResult())
    }

    // ---- TEST mode previews ------------------------------------------------------------------------------------

    @Test fun `preview of a write without dry run support says NOT_EXECUTED and sends nothing`() {
        val data = FakeDataPort()
        val h = DefaultActionHandlers.registry(json, ActionPorts(data))[ActionType.CREATE_RECORD]!!
        val w = assertInstanceOf(ActionResult.WouldRun::class.java, h.preview(ctx, Fx.mutation(ActionType.CREATE_RECORD), ActionInput(mapOf("title" to str("T"))), testRun))
        assertEquals(DryRunLevel.NOT_EXECUTED, w.level)
        assertEquals(ActionType.CREATE_RECORD, w.type); assertEquals("m1", w.actionId)
        assertEquals("orders.create", w.plan.get("target").get("queryRef").asString()); assertEquals("CREATE", w.plan.get("target").get("writeKind").asString())
        assertEquals("title", w.plan.get("inputNames").get(0).asString())
        assertFalse(w.plan.toString().contains("\"T\""))                    // input values are not echoed into the plan
        assertTrue(data.writes.isEmpty())
        assertTrue(w.reason!!.contains("no dry-run"))
    }

    @Test fun `preview uses the dry run capability only when the data platform offers it`() {
        val data = FakeDataPort()
        val h = DefaultActionHandlers.registry(json, ActionPorts(data))[ActionType.UPDATE_RECORD]!!
        val def = Fx.mutation(ActionType.UPDATE_RECORD, inputs = listOf(InputSpec("recordId", InputType.STRING, required = true)))
        val input = ActionInput(mapOf("recordId" to str("1")))

        data.dryRunOutcome = DryRunOutcome.Validated(json.createObjectNode().put("rows", 1))
        assertEquals(DryRunLevel.VALIDATED, (h.preview(ctx, def, input, testRun) as ActionResult.WouldRun).level)
        data.dryRunOutcome = DryRunOutcome.Sandboxed(json.createObjectNode().put("rows", 1))
        val sb = h.preview(ctx, def, input, testRun) as ActionResult.WouldRun
        assertEquals(DryRunLevel.SANDBOX, sb.level); assertEquals(1, sb.output!!.get("rows").asInt())
        data.dryRunOutcome = DryRunOutcome.Failure("FORBIDDEN", false, "no")
        assertEquals("FORBIDDEN", (h.preview(ctx, def, input, testRun) as ActionResult.Failed).code)
        assertTrue(data.writes.isEmpty())                                   // a preview never calls the real write
    }

    @Test fun `preview of CALL_API validates or sandboxes only through the connector's own capability`() {
        val data = FakeDataPort()
        val h = DefaultActionHandlers.registry(json, ActionPorts(data))[ActionType.CALL_API]!!
        val w = h.preview(ctx, Fx.callApi(), ActionInput(mapOf("q" to str("x"))), testRun) as ActionResult.WouldRun
        assertEquals(DryRunLevel.NOT_EXECUTED, w.level)
        assertEquals("crm", w.plan.get("target").get("dataSourceRef").asString())
        assertTrue(data.operations.isEmpty())
    }

    @Test fun `preview of NOTIFY never sends and START_WORKFLOW never starts`() {
        val notify = FakeNotifyPort(); val wf = FakeWorkflowPort()
        val reg = DefaultActionHandlers.registry(json, ActionPorts(notify = notify, workflow = wf))
        val n = reg[ActionType.NOTIFY]!!.preview(ctx, Fx.notify(), ActionInput.EMPTY, testRun) as ActionResult.WouldRun
        assertEquals("IN_APP", n.plan.get("target").get("channel").asString()); assertEquals(DryRunLevel.NOT_EXECUTED, n.level)
        val s = reg[ActionType.START_WORKFLOW]!!.preview(ctx, Fx.startWorkflow(), ActionInput.EMPTY, testRun) as ActionResult.WouldRun
        assertEquals("onboarding", s.plan.get("target").get("workflowRef").asString())
        assertTrue(notify.sent.isEmpty() && wf.started.isEmpty())
    }

    @Test fun `preview of client instructions shows the instruction that would be sent`() {
        val w = NavigateActionHandler(json).preview(ctx, Fx.navigate(), ActionInput.EMPTY, testRun) as ActionResult.WouldRun
        assertEquals("NAVIGATE", w.output!!.get("action").asString()); assertEquals(DryRunLevel.NOT_EXECUTED, w.level)
    }

    @Test fun `an unwired port is NOT_IMPLEMENTED in TEST mode too, not a pretend success`() {
        val h = DefaultActionHandlers.registry(json, ActionPorts())[ActionType.CREATE_RECORD]!!
        assertEquals(ActionErrorCodes.NOT_IMPLEMENTED, (h.preview(ctx, Fx.mutation(ActionType.CREATE_RECORD), ActionInput.EMPTY, testRun) as ActionResult.Failed).code)
    }
}
