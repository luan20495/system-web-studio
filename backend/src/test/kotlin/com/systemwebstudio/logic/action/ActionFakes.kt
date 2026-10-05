package com.systemwebstudio.logic.action

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Test doubles + builders for the Action layer. Pure unit-test support: no Spring, no DB, no queue. */
object Fx {
    val json: JsonMapper = JsonMapper.builder().build()
    val tenantA: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    val tenantB: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    val user: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    val now: Instant = Instant.parse("2026-10-05T00:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    fun ctx(tenant: UUID = tenantA) = ActionContext(tenant, ActionActor(user), workspaceId = UUID.randomUUID(), requestId = "req-1")

    fun str(s: String): JsonNode = json.createObjectNode().put("v", s).get("v")
    fun num(n: Int): JsonNode = json.createObjectNode().put("v", n).get("v")
    fun cfg(vararg pairs: Pair<String, String>): Map<String, JsonNode> = pairs.associate { it.first to str(it.second) }

    fun navigate(id: String = "go-home", tenant: UUID = tenantA, inputs: List<InputSpec> = emptyList()) =
        ActionDefinition(id, tenant, ActionType.NAVIGATE, inputs = inputs, config = cfg("pageId" to "home"))

    fun mutation(type: ActionType, id: String = "m1", inputs: List<InputSpec> = listOf(InputSpec("title", InputType.STRING, required = true))) =
        ActionDefinition(id, tenantA, type, inputs = inputs, config = cfg("mutationId" to "orders.create"))
}

class FakeDefinitions(vararg defs: ActionDefinition) : ActionDefinitionProvider {
    private val all = defs.associateBy { it.id }
    override fun find(tenantId: UUID, actionId: String) = all[actionId]   // intentionally returns other tenants' rows too: the runtime must still refuse them
}

class FakeAuthorizer(var allow: Boolean = true) : ActionAuthorizer {
    val calls = CopyOnWriteArrayList<ActionDefinition>()
    override fun authorize(ctx: ActionContext, action: ActionDefinition): AuthorizationDecision {
        calls += action
        return if (allow) AuthorizationDecision.Allowed else AuthorizationDecision.Denied("missing ACTION_EXECUTE")
    }
}

class RecordingAudit(private val failOn: Set<AuditPhase> = emptySet()) : ActionAuditPort {
    val entries = CopyOnWriteArrayList<ActionAuditEntry>()
    override fun record(entry: ActionAuditEntry) {
        if (entry.phase in failOn) throw IllegalStateException("audit down")
        entries += entry
    }
    val phases get() = entries.map { it.phase }
}

class FakeDataPort(var outcome: PortOutcome = PortOutcome.Success(Fx.json.createObjectNode().put("id", "rec-1"))) : ActionDataPort {
    val mutations = CopyOnWriteArrayList<Pair<ActionContext, MutationRequest>>()
    val apiCalls = CopyOnWriteArrayList<Pair<ActionContext, ApiCallRequest>>()
    override fun mutate(ctx: ActionContext, request: MutationRequest): PortOutcome { mutations += ctx to request; return outcome }
    override fun callApi(ctx: ActionContext, request: ApiCallRequest): PortOutcome { apiCalls += ctx to request; return outcome }
}

class FakeNotifyPort(var outcome: PortOutcome = PortOutcome.Success(Fx.json.createObjectNode().put("queued", true))) : ActionNotifyPort {
    val sent = CopyOnWriteArrayList<NotifyRequest>()
    override fun send(ctx: ActionContext, request: NotifyRequest): PortOutcome { sent += request; return outcome }
}

class FakeWorkflowPort(var outcome: PortOutcome = PortOutcome.Success(Fx.json.createObjectNode().put("runId", "wf-1"))) : WorkflowStarterPort {
    val started = CopyOnWriteArrayList<StartWorkflowRequest>()
    override fun start(ctx: ActionContext, request: StartWorkflowRequest): PortOutcome { started += request; return outcome }
}

class FakeBindings(private val refs: List<ActionRef>) : ActionBindingPort {
    override fun refsFor(ctx: ActionContext, eventName: String) = refs
}
