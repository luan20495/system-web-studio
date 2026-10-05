package com.systemwebstudio.logic.action

import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Domain model of the declarative Action layer (PREP-T13, owner C4).
 *
 * This package is framework-free on purpose: no Spring annotations, no JPA, no RabbitMQ, and no import of any
 * class owned by C1 (access/tenancy), C2 (app.definition) or C3 (data.*). Everything outside the package is
 * reached through the ports in [ActionPorts.kt]; adapters live outside `logic.action` and are written once the
 * owning agent has published a stable interface.
 *
 * An Action is *declarative data with a type* — it never carries code, SQL or a raw URL. References to other
 * resources (pages, mutations, connector operations, workflows) are typed ids.
 */

/** The closed set of action kinds. Adding one is a contract change (DECISIONS.md), not a runtime config. */
enum class ActionType(val mutatesState: Boolean) {
    /** Client-side navigation instruction to a declared page. No server side effect. */
    NAVIGATE(false),
    SUBMIT_FORM(true),
    CREATE_RECORD(true),
    UPDATE_RECORD(true),
    DELETE_RECORD(true),
    /** Calls an *already declared* connector operation (never a raw URL). */
    CALL_API(true),
    NOTIFY(true),
    START_WORKFLOW(true)
}

/** Who/what started the run (mirrors `TriggerInfo` in `docs/contracts/action-workflow.md`). */
enum class TriggerKind { UI_EVENT, WORKFLOW_STEP, SCHEDULE }

data class TriggerInfo(
    val kind: TriggerKind,
    /** e.g. `hero-1.click` for a UI event; the step id for a workflow step. */
    val eventName: String? = null,
    val eventId: String? = null
)

/** Same four kinds as `ActorKind` in `docs/contracts/tenant-context.md`; kept local so this package has no C1 import. */
enum class ActorKind { USER, SYSTEM, APP_TOKEN, SERVICE }

data class ActionActor(val userId: UUID, val kind: ActorKind = ActorKind.USER)

/**
 * Who is calling, and for which tenant. Built **server-side** by an adapter from C1's `AccessContext` +
 * `TenantContext` (HTTP request) or from a queue message (`tenantId` + `actorUserId`, context rebuilt by the
 * worker — permissions are never read from a payload). Never constructed from client-controlled data.
 *
 * Passed explicitly (no ThreadLocal) so it works unchanged for asynchronous execution.
 */
data class ActionContext(
    val tenantId: UUID,
    val actor: ActionActor,
    val workspaceId: UUID? = null,
    val projectId: UUID? = null,
    val requestId: String? = null
)

/** A domain event raised by a UI interaction, a workflow step or a schedule. */
data class Event(
    /** Unique per occurrence; used to derive idempotency keys when the event fans out to actions. */
    val id: String,
    /** Matches `ActionRef.trigger` of the AppDefinition, e.g. `sectionId.event`. */
    val name: String,
    val payload: Map<String, JsonNode> = emptyMap(),
    val occurredAt: Instant,
    val kind: TriggerKind = TriggerKind.UI_EVENT
) {
    fun trigger() = TriggerInfo(kind, name, id)
}

/**
 * Mirror of C2's `ActionRef(id, trigger, actionId)` (app-definition-v2.md): "when [trigger] fires, run [actionId]".
 * Kept as our own type so C4 does not import C2; the adapter converts.
 */
data class ActionRef(val id: String, val trigger: String, val actionId: String)

/** What a caller asks for (shape from `docs/contracts/action-workflow.md`). */
data class ActionRequest(
    val actionId: String,
    val inputs: Map<String, JsonNode> = emptyMap(),
    val idempotencyKey: String? = null,
    val trigger: TriggerInfo = TriggerInfo(TriggerKind.UI_EVENT),
    /** 0 for a top-level call; a workflow engine passes its own nesting depth so loops are bounded. */
    val callDepth: Int = 0
)

/** Per-run facts the runtime hands to a handler (separate from the caller identity in [ActionContext]). */
data class ActionRun(
    val runId: String,
    val idempotencyKey: String?,
    val trigger: TriggerInfo,
    val callDepth: Int,
    val startedAt: Instant
)

enum class IdempotencyPolicy {
    /** A supplied key is ignored. */
    NONE,
    /** A supplied key de-duplicates; absence is allowed. */
    OPTIONAL,
    /** The request is rejected without a key (e.g. START_WORKFLOW, destructive writes). */
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
 * Per-tenant rate limiting is intentionally not here: it needs shared state and belongs to the integration step.
 */
data class ActionLimits(
    val timeout: Duration = Duration.ofSeconds(30),
    val maxInputBytes: Int = 64 * 1024,
    val maxInputDepth: Int = 8,
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
 * A tenant-scoped, declarative action. [config] holds only type-specific *references and flags*
 * (see each handler); [ActionDefinitionValidator] rejects keys that would smuggle code, SQL or URLs.
 */
data class ActionDefinition(
    val id: String,
    val tenantId: UUID,
    val type: ActionType,
    val name: String = id,
    val inputs: List<InputSpec> = emptyList(),
    val config: Map<String, JsonNode> = emptyMap(),
    val idempotency: IdempotencyPolicy = IdempotencyPolicy.OPTIONAL,
    val limits: ActionLimits = ActionLimits(),
    val enabled: Boolean = true
)
