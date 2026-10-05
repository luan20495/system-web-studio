package com.systemwebstudio.logic.action

import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Domain model of the declarative Action layer (owner C4).
 *
 * Framework-free on purpose: no Spring, no JPA, no RabbitMQ, and no import of classes owned by C1 (access/tenancy), C2 (app.definition)
 * or C3 (data.*). The canonical *document* is the AppDefinition JSON (`docs/contracts/app-definition-v2.md`, C2); [com.systemwebstudio.logic.action.canonical]
 * reads that JSON, so the contract is the JSON shape, not a Kotlin type. Everything else outside is reached through the ports in `ActionPorts.kt`.
 *
 * An Action is declarative data with a type — it never carries code, SQL or a raw URL. References to other resources are typed ids.
 */

/** The closed set of action kinds. Adding one is a contract change (DECISIONS.md D-C4-01), not runtime config. */
enum class ActionType(val mutatesState: Boolean, val clientInstruction: Boolean = false) {
    /** Client-side navigation instruction to a declared page. No server side effect. */
    NAVIGATE(false, true),
    /** Client-side instruction to re-run a declared READ query through the data gateway under the user's own permission. */
    REFRESH_QUERY(false, true),
    SUBMIT_FORM(true),
    CREATE_RECORD(true),
    UPDATE_RECORD(true),
    DELETE_RECORD(true),
    /** Calls an approved operation of a registered data source (never a raw URL). */
    CALL_API(true),
    NOTIFY(true),
    START_WORKFLOW(true)
}

/** LIVE runs for real. TEST never performs a side effect: it answers what *would* run (see [ActionResult.WouldRun]). */
enum class ExecutionMode { LIVE, TEST }

/** Who/what started the run (mirrors `TriggerInfo` in `docs/contracts/action-workflow.md`). */
enum class TriggerKind { UI_EVENT, WORKFLOW_STEP, SCHEDULE }

data class TriggerInfo(
    val kind: TriggerKind,
    /** e.g. `hero-1.onClick` for a UI event; the step id for a workflow step. */
    val eventName: String? = null,
    val eventId: String? = null
)

/** Same four kinds as `ActorKind` in `docs/contracts/tenant-context.md`; kept local so this package has no C1 import. */
enum class ActorKind { USER, SYSTEM, APP_TOKEN, SERVICE }

data class ActionActor(val userId: UUID, val kind: ActorKind = ActorKind.USER)

/**
 * Who is calling, and for which tenant/app. Built **server-side** by an adapter from C1's `AccessContext` + `TenantContext` (HTTP) or from a
 * queue message (`tenantId` + `actorUserId`, context rebuilt by the worker — permissions are never read from a payload). Never client data.
 * [projectId] is the application (an AppDefinition lives in a project).
 */
data class ActionContext(
    val tenantId: UUID,
    val actor: ActionActor,
    val workspaceId: UUID? = null,
    val projectId: UUID? = null,
    val requestId: String? = null
)

/** UI events an action can be bound to. [wire] is the name used in the AppDefinition (`trigger.event`). */
enum class EventType(val wire: String) {
    ON_LOAD("onLoad"), ON_CLICK("onClick"), ON_CHANGE("onChange"), ON_SUBMIT("onSubmit"), ON_SUCCESS("onSuccess"), ON_ERROR("onError");

    companion object { fun fromWire(s: String?): EventType? = entries.firstOrNull { it.wire == s } }
}

/**
 * Data the browser reports with an event. **All of it is client-controlled and therefore untrusted**: it can only ever feed *declared* action
 * inputs (which are type-checked and authorized like any other input); it cannot name an action, a tenant, a user or a permission.
 */
data class EventPayload(
    val componentState: Map<String, JsonNode> = emptyMap(),
    val routeParams: Map<String, JsonNode> = emptyMap(),
    val form: Map<String, JsonNode> = emptyMap(),
    val viewModel: Map<String, JsonNode> = emptyMap()
) { companion object { val EMPTY = EventPayload() } }

/** A UI event. Contains no executable code: [sectionId] + [type] are matched against the AppDefinition's declared triggers. */
data class Event(
    /** Unique per occurrence; used to derive idempotency keys when the event fans out to actions. */
    val id: String,
    val type: EventType,
    val sectionId: String,
    val payload: EventPayload = EventPayload.EMPTY,
    val occurredAt: Instant,
    val mode: ExecutionMode = ExecutionMode.LIVE
) {
    val name: String get() = "$sectionId.${type.wire}"
    fun trigger() = TriggerInfo(TriggerKind.UI_EVENT, name, id)
}

/** `ActionDef.trigger` of the AppDefinition: the UI event an action is bound to. Optional on a definition (see [ActionDefinition.trigger]). */
data class ActionTrigger(val sectionId: String, val event: EventType)

/** "When [event] happens on [sectionId], run [actionId]" — derived from `ActionDef.trigger` of the canonical AppDefinition. */
data class ActionRef(val id: String, val sectionId: String, val event: EventType, val actionId: String)

/** Where an action input comes from. Declarative; there is no expression language. */
sealed interface InputSource {
    data class ComponentState(val path: String) : InputSource
    data class RouteParam(val name: String) : InputSource
    data class FormField(val name: String) : InputSource
    data class ViewModelField(val path: String) : InputSource
    /** Result of the action that triggered this one (chained actions). */
    data class PreviousResult(val path: String) : InputSource
    data class Literal(val value: JsonNode) : InputSource
    /** Server-derived and **not overridable by the client** — the only way to get the acting user's id into an input. */
    data class Context(val key: ContextKey) : InputSource
}

enum class ContextKey { USER_ID, TENANT_ID, WORKSPACE_ID, APP_ID, REQUEST_ID, NOW }

/** What a caller asks for (shape from `docs/contracts/action-workflow.md`, extended). */
data class ActionRequest(
    val actionId: String,
    /** Explicit inputs (API callers, workflow steps). Anything mapped from [InputSource.Context] cannot be supplied here. */
    val inputs: Map<String, JsonNode> = emptyMap(),
    /**
     * The **client** key. It is only an input: the runtime derives the key it stores, audits and forwards ([IdempotencyKeys.derive]) and never
     * passes this raw value to a downstream port or a log.
     */
    val idempotencyKey: String? = null,
    val trigger: TriggerInfo = TriggerInfo(TriggerKind.UI_EVENT),
    /** 0 for a top-level call; chains and workflow engines pass their own nesting depth so loops are bounded. */
    val callDepth: Int = 0,
    val mode: ExecutionMode = ExecutionMode.LIVE,
    /** Event data used to resolve the definition's `inputMapping`. */
    val payload: EventPayload? = null,
    val previousResult: JsonNode? = null,
    /** May only shorten the definition's/ceiling's timeout. */
    val timeout: Duration? = null
)

/** Per-run facts the runtime hands to a handler (separate from the caller identity in [ActionContext]). */
data class ActionRun(
    val runId: String,
    /** The **derived** key ([IdempotencyKeys.derive]); never the client's raw key. Non-null for every state-mutating type (also in TEST mode). */
    val idempotencyKey: String?,
    val trigger: TriggerInfo,
    val callDepth: Int,
    val startedAt: Instant,
    val mode: ExecutionMode = ExecutionMode.LIVE,
    /** The application the action runs in (= [ActionContext.projectId]); restated so adapters of the data path need not read the context. */
    val appId: UUID? = null
)

enum class IdempotencyPolicy {
    /** A supplied key is ignored. */
    NONE,
    /** A supplied key de-duplicates; absence is allowed. */
    OPTIONAL,
    /** The request is rejected without a key. Default for every state-mutating action type (see [ActionDefinition.idempotency]). */
    REQUIRED
}

enum class InputType { STRING, NUMBER, BOOLEAN, OBJECT, ARRAY, ANY }

data class InputSpec(
    val name: String,
    val type: InputType = InputType.ANY,
    val required: Boolean = false,
    /** Only for [InputType.STRING]. */
    val maxLength: Int? = null
)

/**
 * Resource ceilings. A definition may only *lower* the platform ceiling held by the runtime ([coerceAtMost]).
 * Per-tenant rate limiting needs shared state and belongs to the integration step (B-C4-08).
 */
data class ActionLimits(
    val timeout: Duration = Duration.ofSeconds(30),
    val maxInputBytes: Int = 64 * 1024,
    val maxInputDepth: Int = 8,
    /** Max length of an onSuccess/onError chain or workflow→action nesting. */
    val maxCallDepth: Int = 5
) {
    init {
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
        require(maxInputBytes > 0 && maxInputDepth > 0 && maxCallDepth >= 0) { "limits must be positive" }
    }

    fun coerceAtMost(ceiling: ActionLimits) = ActionLimits(
        timeout = if (timeout < ceiling.timeout) timeout else ceiling.timeout,
        maxInputBytes = minOf(maxInputBytes, ceiling.maxInputBytes),
        maxInputDepth = minOf(maxInputDepth, ceiling.maxInputDepth),
        maxCallDepth = minOf(maxCallDepth, ceiling.maxCallDepth)
    )
}

/**
 * A tenant- and app-scoped declarative action, as the runtime sees it. [config] holds only type-specific *references and flags*
 * (see each handler); [ActionDefinitionValidator] rejects keys that would smuggle code, SQL or URLs.
 */
data class ActionDefinition(
    val id: String,
    val tenantId: UUID,
    val type: ActionType,
    val name: String = id,
    val inputs: List<InputSpec> = emptyList(),
    val config: Map<String, JsonNode> = emptyMap(),
    /**
     * Idempotency for write actions is not optional: state-mutating types default to REQUIRED, client-instruction types to NONE.
     * A definition may lower a mutating action to OPTIONAL/NONE only explicitly (and the validator flags NONE on a mutating type).
     */
    val idempotency: IdempotencyPolicy = if (type.mutatesState) IdempotencyPolicy.REQUIRED else IdempotencyPolicy.NONE,
    val limits: ActionLimits = ActionLimits(),
    val enabled: Boolean = true,
    /** The application this action belongs to (null only in unit tests of isolated pieces). */
    val appId: UUID? = null,
    val inputMapping: Map<String, InputSource> = emptyMap(),
    /** Permission code declared by the definition (`permissionRef` → `PermissionDef.permission`), checked in addition to ACTION_EXECUTE. */
    val requiredPermission: String? = null,
    /** Action ids to run after this one succeeded / failed (chained with callDepth + 1, bounded by [ActionLimits.maxCallDepth]). */
    val onSuccess: List<String> = emptyList(),
    val onError: List<String> = emptyList(),
    /**
     * The UI event that fires this action (`ActionDef.trigger`). **Optional**: an action that only a workflow step (or a chain) invokes has no UI
     * trigger. It is required only for UI-bound execution: a run whose trigger is a top-level UI event (not a workflow step, a schedule or a
     * chain) is rejected as UNKNOWN_ACTION when the definition declares none (D-C4-10).
     */
    val trigger: ActionTrigger? = null
)
