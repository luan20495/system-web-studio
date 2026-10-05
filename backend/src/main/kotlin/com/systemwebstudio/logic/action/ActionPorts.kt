package com.systemwebstudio.logic.action

import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/*
 * Ports of the Action layer. C4 owns these interfaces; other agents supply the implementations (adapters) *outside*
 * `logic.action`, so this package never imports C1/C2/C3 classes and C3 never has to guess C4's needs.
 *
 *   port                      supplied by               adapts
 *   ------------------------  ------------------------  --------------------------------------------------
 *   ActionDefinitionProvider  C4 (+C0 migration) / C2   where ActionDefinitions are stored (see design doc §6)
 *   ActionBindingPort         C2                        AppDefinition.actions[] (ActionRef) of the app version
 *   ActionAuthorizer          C1                        AccessService.forResource + AccessContext.require(ACTION_EXECUTE)
 *   ActionAuditPort           C0-gated (audit module)       AuditService.record (append-only)
 *   ActionDataPort            C3                        DataGateway (mutations) + connector operations
 *   ActionNotifyPort          C4/C0                     in-app / e-mail notification (no provider chosen yet)
 *   WorkflowStarterPort       C4 (logic.workflow)       WorkflowRuntime.start
 *   ActionRunStore            C4 (+C0 migration)        run state + idempotency (in-memory impl provided)
 */

/** Tenant-scoped lookup. There is deliberately no `find(actionId)` without a tenant. */
interface ActionDefinitionProvider {
    fun find(tenantId: UUID, actionId: String): ActionDefinition?
}

/** Which actions the AppDefinition wires to an event name. Used only by [ActionRuntime.dispatch]. */
interface ActionBindingPort {
    fun refsFor(ctx: ActionContext, eventName: String): List<ActionRef>
}

sealed interface AuthorizationDecision {
    data object Allowed : AuthorizationDecision
    /** [reason] is for the audit trail only; it is never returned to the caller. */
    data class Denied(val reason: String) : AuthorizationDecision
}

/**
 * Default-deny permission check, called **before** input is looked at and before any port is touched.
 * The implementation resolves the real permissions server-side from [ActionContext.actor] and the tenant
 * (it must not trust anything the client sent). Handlers' downstream ports must still authorize on their own
 * (defense in depth): this check does not replace `DataGateway`'s own `QUERY_EXECUTE`-style checks.
 */
interface ActionAuthorizer {
    fun authorize(ctx: ActionContext, action: ActionDefinition): AuthorizationDecision
}

enum class AuditPhase { STARTED, SUCCEEDED, FAILED, DENIED, REJECTED, REPLAYED }

/** Audit record for a run. Intentionally has **no input or output values**: they may be sensitive. */
data class ActionAuditEntry(
    val phase: AuditPhase,
    val tenantId: UUID,
    val actor: ActionActor,
    val workspaceId: UUID?,
    val projectId: UUID?,
    val actionId: String,
    val actionType: ActionType?,
    val runId: String?,
    val trigger: TriggerInfo?,
    val requestId: String?,
    val errorCode: String? = null,
    val retryable: Boolean? = null,
    val durationMillis: Long? = null,
    /** Free-form, short, non-sensitive (e.g. the authorizer's denial reason). */
    val detail: String? = null,
    val at: Instant
)

interface ActionAuditPort {
    /** May throw; the runtime fails closed for [AuditPhase.STARTED] and is best-effort for the other phases. */
    fun record(entry: ActionAuditEntry)
}

/** Result of calling a downstream port. Mapped 1:1 to [ActionResult]. */
sealed interface PortOutcome {
    data class Success(val output: JsonNode) : PortOutcome
    data class Failure(val code: String, val retryable: Boolean, val message: String? = null) : PortOutcome

    fun toResult(): ActionResult = when (this) {
        is Success -> ActionResult.Ok(output)
        is Failure -> ActionResult.Failed(code, retryable, message)
    }
}

enum class MutationKind { CREATE, UPDATE, DELETE, SUBMIT }

/**
 * A write expressed as a reference to a **declared, approved mutation** (the write-side twin of `queryId` in
 * `data-connector.md`) — never a table name, SQL or URL. [params] are the validated action inputs; binding them into
 * a prepared statement / path template is the connector's job. [idempotencyKey] is forwarded so the downstream write
 * can de-duplicate as well.
 */
data class MutationRequest(
    val mutationId: String,
    val kind: MutationKind,
    val params: Map<String, JsonNode>,
    val idempotencyKey: String?
)

/** Call of an operation already declared on a connector/grant (ADR 0017 model). No URL, header or credential here. */
data class ApiCallRequest(val operationId: String, val params: Map<String, JsonNode>, val idempotencyKey: String?)

/** C3 side. Both methods must run the caller's own authorization/tenant checks and the connector safety rules (SSRF guard, limits). */
interface ActionDataPort {
    fun mutate(ctx: ActionContext, request: MutationRequest): PortOutcome
    fun callApi(ctx: ActionContext, request: ApiCallRequest): PortOutcome
}

enum class NotifyChannel { IN_APP, EMAIL }

/** [recipientUserIds] empty ⇒ the acting user. Recipients come from the definition, never from run-time input. */
data class NotifyRequest(
    val channel: NotifyChannel,
    val templateId: String,
    val recipientUserIds: List<UUID>,
    val params: Map<String, JsonNode>,
    val idempotencyKey: String?
)

interface ActionNotifyPort {
    fun send(ctx: ActionContext, request: NotifyRequest): PortOutcome
}

data class StartWorkflowRequest(val workflowId: String, val input: JsonNode, val idempotencyKey: String)

/** Dependency inversion: `logic.action` never imports `logic.workflow`. */
interface WorkflowStarterPort {
    fun start(ctx: ActionContext, request: StartWorkflowRequest): PortOutcome
}

/** Runtime configuration of which optional ports are wired; an absent port yields a typed NOT_IMPLEMENTED handler. */
data class ActionPorts(
    val data: ActionDataPort? = null,
    val notify: ActionNotifyPort? = null,
    val workflow: WorkflowStarterPort? = null
)
