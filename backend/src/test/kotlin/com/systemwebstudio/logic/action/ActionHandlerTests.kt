package com.systemwebstudio.logic.action

import com.systemwebstudio.logic.action.Fx.json
import com.systemwebstudio.logic.action.Fx.str
import com.systemwebstudio.logic.action.handlers.DefaultActionHandlers
import com.systemwebstudio.logic.action.handlers.NavigateActionHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID

class ActionHandlerTests {
    private val run = ActionRun("run-1", "idem-1", TriggerInfo(TriggerKind.UI_EVENT), 0, Fx.now)
    private val ctx = Fx.ctx()

    @Test fun `registry rejects duplicates and exposes lookup`() {
        val b = ActionHandlerRegistry.builder().register(NavigateActionHandler(json))
        assertThrows(IllegalArgumentException::class.java) { b.register(NavigateActionHandler(json)) }
        val reg = b.build()
        assertEquals(setOf(ActionType.NAVIGATE), reg.types)
        assertEquals(null, reg[ActionType.NOTIFY])
    }

    @Test fun `default registry covers all eight types even with no ports wired`() {
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

    @Test fun `UPDATE and DELETE map to their mutation kinds`() {
        val data = FakeDataPort()
        val reg = DefaultActionHandlers.registry(json, ActionPorts(data))
        val inputs = listOf(InputSpec("recordId", InputType.STRING, required = true))
        val input = ActionInput(mapOf("recordId" to str("9")))
        reg[ActionType.UPDATE_RECORD]!!.execute(ctx, Fx.mutation(ActionType.UPDATE_RECORD, inputs = inputs), input, run)
        reg[ActionType.DELETE_RECORD]!!.execute(ctx, Fx.mutation(ActionType.DELETE_RECORD, inputs = inputs), input, run)
        assertEquals(listOf(MutationKind.UPDATE, MutationKind.DELETE), data.mutations.map { it.second.kind })
    }

    @Test fun `CALL_API forwards only a declared operation id`() {
        val data = FakeDataPort()
        val h = DefaultActionHandlers.registry(json, ActionPorts(data))[ActionType.CALL_API]!!
        val def = ActionDefinition("call", Fx.tenantA, ActionType.CALL_API, config = Fx.cfg("operationId" to "crm.lookup"))
        assertEquals(emptyList<DefinitionIssue>(), h.validate(def))
        assertEquals("config.operationId", h.validate(def.copy(config = emptyMap())).single().path)
        h.execute(ctx, def, ActionInput(mapOf("q" to str("x"))), run)
        val (_, req) = data.apiCalls.single()
        assertEquals("crm.lookup", req.operationId); assertEquals("idem-1", req.idempotencyKey)
    }

    @Test fun `NOTIFY validates channel template and recipients and sends to the port`() {
        val port = FakeNotifyPort()
        val h = DefaultActionHandlers.registry(json, ActionPorts(notify = port))[ActionType.NOTIFY]!!
        val uid = UUID.randomUUID()
        val arr = json.createArrayNode().add(uid.toString())
        val def = ActionDefinition("n", Fx.tenantA, ActionType.NOTIFY, config = Fx.cfg("channel" to "EMAIL", "templateId" to "welcome") + mapOf("recipientUserIds" to (arr as JsonNode)))
        assertEquals(emptyList<DefinitionIssue>(), h.validate(def))
        assertEquals("config.channel", h.validate(def.copy(config = Fx.cfg("channel" to "SMS", "templateId" to "welcome"))).single().path)
        assertEquals("config.templateId", h.validate(def.copy(config = Fx.cfg("channel" to "EMAIL"))).single().path)
        val badRecipients = json.createArrayNode().add("not-a-uuid")
        assertEquals("config.recipientUserIds", h.validate(def.copy(config = def.config + mapOf("recipientUserIds" to (badRecipients as JsonNode)))).single().path)

        assertInstanceOf(ActionResult.Ok::class.java, h.execute(ctx, def, ActionInput(mapOf("name" to str("Ann"))), run))
        val sent = port.sent.single()
        assertEquals(NotifyChannel.EMAIL, sent.channel); assertEquals("welcome", sent.templateId); assertEquals(listOf(uid), sent.recipientUserIds)
    }

    @Test fun `START_WORKFLOW demands IdempotencyPolicy REQUIRED and passes the key`() {
        val port = FakeWorkflowPort()
        val h = DefaultActionHandlers.registry(json, ActionPorts(workflow = port))[ActionType.START_WORKFLOW]!!
        val def = ActionDefinition("wf", Fx.tenantA, ActionType.START_WORKFLOW, config = Fx.cfg("workflowId" to "onboarding"), idempotency = IdempotencyPolicy.REQUIRED)
        assertEquals(emptyList<DefinitionIssue>(), h.validate(def))
        assertEquals("idempotency", h.validate(def.copy(idempotency = IdempotencyPolicy.OPTIONAL)).single().path)
        h.execute(ctx, def, ActionInput(mapOf("who" to str("x"))), run)
        val req = port.started.single()
        assertEquals("onboarding", req.workflowId); assertEquals("idem-1", req.idempotencyKey); assertEquals("x", req.input.get("who").asString())
        val noKey = h.execute(ctx, def, ActionInput.EMPTY, run.copy(idempotencyKey = null))
        assertEquals(ActionErrorCodes.IDEMPOTENCY_KEY_REQUIRED, (noKey as ActionResult.Failed).code)
    }

    @Test fun `port failure and success map one to one`() {
        assertEquals(ActionResult.Failed("X", true, "m"), PortOutcome.Failure("X", true, "m").toResult())
        val ok = json.createObjectNode().put("a", 1)
        assertEquals(ActionResult.Ok(ok), PortOutcome.Success(ok).toResult())
    }
}
