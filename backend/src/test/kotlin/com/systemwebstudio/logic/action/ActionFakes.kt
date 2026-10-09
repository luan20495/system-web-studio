package com.systemwebstudio.logic.action

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Test doubles + builders for the Action/Workflow layer. Pure unit-test support: no Spring, no DB, no broker. */
object Fx {
    val json: JsonMapper = JsonMapper.builder().build()
    val tenantA: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    val tenantB: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    val appA: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000f1")
    val appB: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000f2")
    val user: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    val user2: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
    val now: Instant = Instant.parse("2026-10-05T00:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    /** The workspace of [appA] in every fixture request. It used to be a new random id per call, so two requests "of the same scope" never were one. */
    val workspaceA: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c1")

    fun ctx(tenant: UUID = tenantA, app: UUID? = appA, userId: UUID = user, kind: ActorKind = ActorKind.USER, workspace: UUID? = workspaceA) =
        ActionContext(tenant, ActionActor(userId, kind), workspaceId = workspace, projectId = app, requestId = "req-1")

    val uiTrigger = ActionTrigger("btn", EventType.ON_CLICK)
    /** The key C4 derives for a client key (what stores and ports see). */
    fun dk(action: String, clientKey: String, tenant: UUID = tenantA, app: UUID = appA, userId: UUID = user) = IdempotencyKeys.derive(tenant, app, userId, action, clientKey)

    fun str(s: String): JsonNode = json.createObjectNode().put("v", s).get("v")
    fun num(n: Int): JsonNode = json.createObjectNode().put("v", n).get("v")
    fun bool(b: Boolean): JsonNode = json.createObjectNode().put("v", b).get("v")
    fun obj(vararg pairs: Pair<String, JsonNode>): JsonNode = json.createObjectNode().also { o -> pairs.forEach { o.set(it.first, it.second) } }
    fun cfg(vararg pairs: Pair<String, String>): Map<String, JsonNode> = pairs.associate { it.first to str(it.second) }

    fun navigate(id: String = "go-home", tenant: UUID = tenantA, inputs: List<InputSpec> = emptyList()) =
        ActionDefinition(id, tenant, ActionType.NAVIGATE, inputs = inputs, config = cfg("pageId" to "home"), appId = appA)

    fun mutation(type: ActionType, id: String = "m1", inputs: List<InputSpec> = listOf(InputSpec("title", InputType.STRING, required = true)), tenant: UUID = tenantA) =
        ActionDefinition(id, tenant, type, inputs = inputs, config = cfg("queryRef" to "orders.create"), appId = appA)

    /** A CREATE_RECORD with a `title` input writing to [queryRef]; the workhorse of workflow tests (the query name tells writes apart). */
    fun write(id: String, queryRef: String = id) =
        ActionDefinition(id, tenantA, ActionType.CREATE_RECORD, inputs = listOf(InputSpec("title", InputType.STRING)), config = cfg("queryRef" to queryRef), appId = appA)

    fun callApi(id: String = "call", tenant: UUID = tenantA) =
        ActionDefinition(id, tenant, ActionType.CALL_API, inputs = listOf(InputSpec("q", InputType.STRING)), config = cfg("dataSourceRef" to "crm", "operationKey" to "lookup"), appId = appA)

    fun notify(id: String = "n1", tenant: UUID = tenantA) =
        ActionDefinition(id, tenant, ActionType.NOTIFY, inputs = listOf(InputSpec("name", InputType.STRING)), config = cfg("channel" to "IN_APP", "templateRef" to "welcome"), appId = appA)

    fun startWorkflow(id: String = "sw", ref: String = "onboarding", tenant: UUID = tenantA) =
        ActionDefinition(id, tenant, ActionType.START_WORKFLOW, inputs = listOf(InputSpec("who", InputType.STRING)), config = cfg("workflowRef" to ref), appId = appA)
}

/** A clock tests can move. */
class TestClock(start: Instant = Fx.now) : Clock() {
    @Volatile var now: Instant = start
    fun advance(d: java.time.Duration) { now = now.plus(d) }
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

class FakeDefinitions(vararg defs: ActionDefinition) : ActionDefinitionProvider {
    private val all = defs.associateBy { it.id }
    val modes = CopyOnWriteArrayList<ExecutionMode>()
    /** Intentionally returns other tenants'/apps' rows too: the runtime must still refuse them. */
    override fun find(tenantId: UUID, appId: UUID, actionId: String, mode: ExecutionMode): ActionDefinition? { modes += mode; return all[actionId] }
}

/** Default-allow access fake that records every request and can deny single permission codes or fail outright. */
open class FakeAccess(var denyPermissions: Set<String> = emptySet(), var denyAll: Boolean = false, var throwing: Boolean = false) : AccessPort {
    val requests = CopyOnWriteArrayList<Pair<ActionContext, AccessRequest>>()
    override fun check(ctx: ActionContext, request: AccessRequest): AuthorizationDecision {
        requests += ctx to request
        if (throwing) error("access down")
        return if (denyAll || request.permission in denyPermissions) AuthorizationDecision.Denied("missing ${request.permission}") else AuthorizationDecision.Allowed
    }
    val permissions get() = requests.map { it.second.permission }
}

class FakeTenants(var disabled: Set<UUID> = emptySet(), var throwing: Boolean = false) : TenantGate {
    override fun isEnabled(tenantId: UUID): Boolean { if (throwing) error("tenant store down"); return tenantId !in disabled }
}

class RecordingAudit(private val failOn: Set<AuditPhase> = emptySet()) : ActionAuditPort {
    val entries = CopyOnWriteArrayList<ActionAuditEntry>()
    override fun record(entry: ActionAuditEntry) {
        if (entry.phase in failOn) throw IllegalStateException("audit down")
        entries += entry
    }
    val phases get() = entries.map { it.phase }
}

class RecordingLogicAudit(var failEvents: Set<String> = emptySet(), var failAll: Boolean = false) : LogicAuditPort {
    val records = CopyOnWriteArrayList<LogicAuditRecord>()
    override fun record(record: LogicAuditRecord) {
        if (failAll || record.event in failEvents) throw IllegalStateException("audit down")
        records += record
    }
    fun events(domain: String? = null) = records.filter { domain == null || it.domain == domain }.map { it.event }
}

class FakeDataPort(var outcome: PortOutcome = PortOutcome.Success(Fx.json.createObjectNode().put("id", "rec-1"))) : ActionDataPort {
    val writes = CopyOnWriteArrayList<Pair<ActionContext, WriteRequest>>()
    val operations = CopyOnWriteArrayList<Pair<ActionContext, OperationRequest>>()
    val dryRuns = CopyOnWriteArrayList<String>()
    /** Set to opt in to dry-run support; null = the honest default (unsupported). */
    var dryRunOutcome: DryRunOutcome? = null
    /** Per query outcomes consumed in order before falling back to [outcome] (lets a test fail a step N times, then succeed). */
    private val scripted = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<PortOutcome>>()
    fun script(queryRef: String, vararg outcomes: PortOutcome) { scripted.getOrPut(queryRef) { java.util.concurrent.ConcurrentLinkedQueue() }.addAll(outcomes) }
    /** Runs inside every write, before it answers: lets a test make "something else happens while the step is executing". */
    @Volatile var onWrite: (() -> Unit)? = null
    override fun write(ctx: ActionContext, request: WriteRequest): PortOutcome { writes += ctx to request; onWrite?.invoke(); return scripted[request.queryRef]?.poll() ?: outcome }
    override fun callOperation(ctx: ActionContext, request: OperationRequest): PortOutcome { operations += ctx to request; return outcome }
    override fun dryRunWrite(ctx: ActionContext, request: WriteRequest): DryRunOutcome { dryRuns += "write"; return dryRunOutcome ?: DryRunOutcome.Unsupported }
    override fun dryRunOperation(ctx: ActionContext, request: OperationRequest): DryRunOutcome { dryRuns += "op"; return dryRunOutcome ?: DryRunOutcome.Unsupported }
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
    override fun refsFor(tenantId: UUID, appId: UUID, sectionId: String, event: EventType, mode: ExecutionMode) = refs
}

/** Resolves principals from a map; anything else is denied (like C1's policy would for an unknown/cross-tenant principal). */
class FakePrincipals(private val groups: Map<String, Set<UUID>> = emptyMap(), private val roles: Map<String, Set<UUID>> = emptyMap(),
                     private val managers: Map<String, UUID> = emptyMap(), private val allowedForeignUsers: Set<UUID> = emptySet(),
                     private val directory: Set<UUID> = setOf(Fx.user, Fx.user2)) : PrincipalResolver {
    override fun resolve(ctx: ActionContext, spec: PrincipalSpec): PrincipalResolution = when (spec) {
        is PrincipalSpec.User ->
            if (spec.tenantId != null && spec.tenantId != ctx.tenantId) {
                if (spec.userId in allowedForeignUsers) PrincipalResolution.Resolved(setOf(spec.userId)) else PrincipalResolution.Denied("cross-tenant")
            } else if (spec.userId in directory || spec.userId in allowedForeignUsers) PrincipalResolution.Resolved(setOf(spec.userId)) else PrincipalResolution.Denied("unknown user")
        is PrincipalSpec.Group -> groups[spec.groupId]?.let { PrincipalResolution.Resolved(it) } ?: PrincipalResolution.Denied("unknown group")
        is PrincipalSpec.Role -> roles[spec.role]?.let { PrincipalResolution.Resolved(it) } ?: PrincipalResolution.Denied("unknown role")
        is PrincipalSpec.DepartmentManager -> managers[spec.departmentId ?: "own"]?.let { PrincipalResolution.Resolved(setOf(it)) } ?: PrincipalResolution.Denied("no manager")
    }
}

/** Everything needed to run the action runtime in a test. */
class ActionRig(
    val defs: FakeDefinitions,
    val data: FakeDataPort = FakeDataPort(),
    val notify: FakeNotifyPort = FakeNotifyPort(),
    val workflow: FakeWorkflowPort = FakeWorkflowPort(),
    val access: FakeAccess = FakeAccess(),
    val tenants: FakeTenants = FakeTenants(),
    val audit: RecordingAudit = RecordingAudit(),
    val runs: InMemoryActionRunStore = InMemoryActionRunStore(),
    val runtime: ActionRuntime
) {
    companion object {
        fun build(
            vararg defs: ActionDefinition,
            audit: RecordingAudit = RecordingAudit(),
            access: FakeAccess = FakeAccess(),
            tenants: FakeTenants = FakeTenants(),
            registry: ((FakeDataPort, FakeNotifyPort, FakeWorkflowPort) -> ActionHandlerRegistry)? = null,
            bindings: ActionBindingPort? = null,
            ceiling: ActionLimits = ActionLimits(),
            executor: java.util.concurrent.ExecutorService,
            clock: Clock = Fx.clock,
            maxChain: Int = 16,
            /** true (default): every definition without a trigger gets a UI trigger, so tests exercise the pipeline; false: definitions are used as given. */
            bindTriggers: Boolean = true,
            /** Rigs are unlimited unless a test is about rate limiting. */
            limiter: com.systemwebstudio.logic.limits.TenantRateLimiter = com.systemwebstudio.logic.limits.TenantRateLimiter.UNLIMITED
        ): ActionRig {
            val bound = if (bindTriggers) defs.map { if (it.trigger == null) it.copy(trigger = Fx.uiTrigger) else it }.toTypedArray() else defs
            val d = FakeDefinitions(*bound)
            val data = FakeDataPort(); val notify = FakeNotifyPort(); val wf = FakeWorkflowPort(); val runs = InMemoryActionRunStore()
            val reg = registry?.invoke(data, notify, wf) ?: com.systemwebstudio.logic.action.handlers.DefaultActionHandlers.registry(Fx.json, ActionPorts(data, notify, wf))
            val rt = DefaultActionRuntime(d, reg, access, tenants, runs, audit, InputResolver(Fx.json), bindings, ceiling, clock, executor, maxChain, limiter)
            return ActionRig(d, data, notify, wf, access, tenants, audit, runs, rt)
        }
    }
}
