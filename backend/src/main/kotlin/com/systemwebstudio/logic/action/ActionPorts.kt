package com.systemwebstudio.logic.action

import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/*
 * Ports of the Action/Workflow/Approval/Scheduler/Notification layer. C4 owns these interfaces; other agents supply the implementations (adapters)
 * *outside* `logic.*`, so this code never imports C1/C2/C3 classes and C3 never has to guess C4's needs.
 *
 *   port                      supplied by               adapts
 *   ------------------------  ------------------------  --------------------------------------------------
 *   AppDefinitionSource       C2 (+C0 wiring)           the AppDefinition JSON of an app (published / draft version), tenant-scoped
 *   AccessPort                C1                        AccessService.forResource + AccessContext.require(...)
 *   TenantGate                C1                        tenant enabled / disabled
 *   PrincipalResolver         C1                        user / group / department manager / role  -> user ids (+ cross-tenant policy)
 *   ActionAuditPort           C0-gated (audit module)   AuditService.record (append-only), action runs
 *   LogicAuditPort            C0-gated (audit module)   AuditService.record, workflow / approval / schedule / notification events
 *   ActionDataPort            C3                        DataGateway (WRITE queries, approved operations, optional dry-run)
 *   ActionNotifyPort          C4 (logic.notification)   NotificationService
 *   WorkflowStarterPort       C4 (logic.workflow)       WorkflowRuntime.start
 *   ActionRunStore            C4 + migration            run state + idempotency (in-memory impl provided)
 */

/** Tenant-scoped lookup of an app's actions. There is deliberately no lookup without a tenant and an app. */
interface ActionDefinitionProvider {
    /** [mode] LIVE reads the published version, TEST the working draft (the adapter decides how). */
    fun find(tenantId: UUID, appId: UUID, actionId: String, mode: ExecutionMode): ActionDefinition?
}

/** Which actions the AppDefinition wires to (sectionId, event). Used only by [ActionRuntime.dispatch]. */
interface ActionBindingPort {
    fun refsFor(tenantId: UUID, appId: UUID, sectionId: String, event: EventType, mode: ExecutionMode): List<ActionRef>
}

sealed interface AuthorizationDecision {
    data object Allowed : AuthorizationDecision
    /** [reason] is for the audit trail only; it is never returned to the caller. */
    data class Denied(val reason: String) : AuthorizationDecision
}

/** Permission codes C4 asks C1 for. The *catalogue* is C1's (B-C4-01); only these strings cross the boundary. */
object LogicPermissions {
    const val APP_USE = "APP_USE"
    const val ACTION_EXECUTE = "ACTION_EXECUTE"
    /** Canonical code for the write side (`tenant-permission.md` §5); required for every data-mutating action type, in addition to ACTION_EXECUTE. */
    const val DATA_MUTATE = "DATA_MUTATE"
    const val WORKFLOW_EXECUTE = "WORKFLOW_EXECUTE"
    /** Cancel/inspect runs started by someone else, manage schedules. */
    const val WORKFLOW_MANAGE = "WORKFLOW_MANAGE"
}

enum class ResourceKind { APP, ACTION, WORKFLOW, WORKFLOW_RUN, APPROVAL, SCHEDULE, NOTIFICATION }

data class AccessRequest(
    val permission: String,
    val resourceKind: ResourceKind,
    val resourceId: String?,
    val appId: UUID?,
    val mode: ExecutionMode = ExecutionMode.LIVE
)

/**
 * Default-deny permission check, called **before** input is looked at and before any other port is touched. The implementation resolves the
 * real permissions and data scope server-side from [ActionContext.actor] and the tenant (it must not trust anything the client sent). A throwing
 * implementation is treated as "cannot decide" and fails closed. Downstream ports must still authorize on their own (defense in depth).
 */
interface AccessPort {
    fun check(ctx: ActionContext, request: AccessRequest): AuthorizationDecision
}

/** A disabled tenant runs nothing — no action, no workflow step, no schedule fire. */
interface TenantGate {
    fun isEnabled(tenantId: UUID): Boolean
}

/** Who may act on something: shared by approvals, notifications and schedules. */
sealed interface PrincipalSpec {
    /** [tenantId] other than the caller's tenant is a cross-tenant principal: only the resolver (C1 policy) may accept it. */
    data class User(val userId: UUID, val tenantId: UUID? = null) : PrincipalSpec
    data class Group(val groupId: String) : PrincipalSpec
    /** [departmentId] null = the department of the requester. */
    data class DepartmentManager(val departmentId: String? = null) : PrincipalSpec
    data class Role(val role: String) : PrincipalSpec
}

sealed interface PrincipalResolution {
    data class Resolved(val userIds: Set<UUID>) : PrincipalResolution
    data class Denied(val reason: String) : PrincipalResolution
}

interface PrincipalResolver {
    /** Applies C1's membership and cross-tenant policy. Must never return users outside what [ctx]'s tenant is allowed to address. */
    fun resolve(ctx: ActionContext, spec: PrincipalSpec): PrincipalResolution
}

enum class AuditPhase { STARTED, SUCCEEDED, FAILED, DENIED, REJECTED, REPLAYED, PREVIEWED }

/** Audit record for an action run. Intentionally has **no input or output values**: they may be sensitive. */
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
    val mode: ExecutionMode = ExecutionMode.LIVE,
    val at: Instant
)

interface ActionAuditPort {
    /** May throw; the runtime fails closed for [AuditPhase.STARTED] and is best-effort for the other phases. */
    fun record(entry: ActionAuditEntry)
}

/** Audit record for workflow / approval / schedule / notification events. [attributes] must be non-sensitive scalars. */
data class LogicAuditRecord(
    val domain: String,
    val event: String,
    val tenantId: UUID,
    val actor: ActionActor?,
    val appId: UUID?,
    val resourceType: String,
    val resourceId: String?,
    val runId: String? = null,
    val mode: ExecutionMode = ExecutionMode.LIVE,
    val errorCode: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val at: Instant
)

object AuditDomains { const val WORKFLOW = "WORKFLOW"; const val APPROVAL = "APPROVAL"; const val SCHEDULE = "SCHEDULE"; const val NOTIFICATION = "NOTIFICATION" }

interface LogicAuditPort {
    /** May throw; callers decide whether that is fatal (state-changing transitions) or best-effort (informational). */
    fun record(record: LogicAuditRecord)
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

enum class WriteKind { CREATE, UPDATE, DELETE, SUBMIT }

/**
 * A write expressed as a reference to a **WRITE query declared in the AppDefinition** (`QueryDef.mode = WRITE`, `queryRef`), which the C0 adapter
 * (`ActionDataPortAdapter` + C2's `AppDataBindingResolver`) turns into a C3 `GatewayMutation`: `queryRef (local id) → QueryDef → dataSourceRef →
 * DataSourceDef.sourceRef + QueryDef.operationKey` — never a table name, SQL or URL. [params] are the validated action inputs.
 *
 * Canonical v2 fields (`docs/contracts/v2/action-workflow.md` §4):
 *  - [appId] and [mode]: the resolver reads the *published* version for LIVE and the *draft* for TEST; both are explicit so the adapter never guesses.
 *  - [idempotencyKey]: **always the derived key** ([IdempotencyKeys.derive]) and never null — a mutating call without a key is not expressible, and
 *    the raw client key cannot be passed because this class has no field for it. It is 43 characters of base64url, which satisfies C3's `^[A-Za-z0-9_-]{8,128}$`.
 */
data class WriteRequest(
    val appId: UUID,
    val mode: ExecutionMode,
    val queryRef: String,
    val kind: WriteKind,
    val params: Map<String, JsonNode>,
    val idempotencyKey: String
) {
    init { require(IdempotencyKeys.DERIVED_KEY.matches(idempotencyKey)) { "idempotencyKey must be a derived key" } }
    override fun toString() = "WriteRequest(appId=$appId, mode=$mode, queryRef=$queryRef, kind=$kind, params=${params.keys}, idempotencyKey=${IdempotencyKeys.redact(idempotencyKey)})"
}

/** Call of an approved operation of a registered data source (`ActionDef.dataSourceRef` + `operationKey`). No URL, header or credential. Same canonical fields as [WriteRequest]. */
data class OperationRequest(
    val appId: UUID,
    val mode: ExecutionMode,
    val dataSourceRef: String,
    val operationKey: String,
    val params: Map<String, JsonNode>,
    val idempotencyKey: String
) {
    init { require(IdempotencyKeys.DERIVED_KEY.matches(idempotencyKey)) { "idempotencyKey must be a derived key" } }
    override fun toString() = "OperationRequest(appId=$appId, mode=$mode, dataSourceRef=$dataSourceRef, operationKey=$operationKey, params=${params.keys}, idempotencyKey=${IdempotencyKeys.redact(idempotencyKey)})"
}

/** What a data system says about a TEST-mode request. [Unsupported] is the honest default: the runtime then reports NOT_EXECUTED. */
sealed interface DryRunOutcome {
    data object Unsupported : DryRunOutcome
    /** The system checked the request (permissions, params, constraints) without any side effect. */
    data class Validated(val output: JsonNode) : DryRunOutcome
    /** The system ran the request in an explicit sandbox it provides. */
    data class Sandboxed(val output: JsonNode) : DryRunOutcome
    data class Failure(val code: String, val retryable: Boolean, val message: String? = null) : DryRunOutcome
}

/**
 * **The only door from the Action layer to data** (`DataPath`): `ActionRuntime → ActionDataPort → [C0 adapter + AppDataBindingResolver] → DataGateway`.
 * No handler, workflow step or scheduler touches a repository, a connector, JDBC or HTTP; the architecture test `ActionDataPathTests` enforces it.
 * The C0 adapter (`wiring.ActionDataPortAdapter`, not written here) builds `GatewayContext` from [ActionContext], resolves the refs through the
 * AppDefinition of ([ActionContext.tenantId], [WriteRequest.appId], [WriteRequest.mode]) and calls `DataGateway.mutate`/operation. C4 implements none of that.
 *
 * C3 side. All methods must run the caller's own authorization/tenant checks and the connector safety rules (SSRF guard, limits). */
interface ActionDataPort {
    fun write(ctx: ActionContext, request: WriteRequest): PortOutcome
    fun callOperation(ctx: ActionContext, request: OperationRequest): PortOutcome

    /** Override only if the data platform really supports validate-only or sandbox execution; never emulate it here. */
    fun dryRunWrite(ctx: ActionContext, request: WriteRequest): DryRunOutcome = DryRunOutcome.Unsupported
    fun dryRunOperation(ctx: ActionContext, request: OperationRequest): DryRunOutcome = DryRunOutcome.Unsupported
}

enum class NotifyChannel { IN_APP, EMAIL, WEBHOOK, SMS }

/**
 * [recipients] are principals from the *definition* (or an approval), never run-time input; empty ⇒ the acting user. [templateRef] names an
 * approved template, [endpointRef] (WEBHOOK only) an approved, pre-registered endpoint — there is no URL anywhere.
 */
data class NotifyRequest(
    val channel: NotifyChannel,
    val templateRef: String,
    val recipients: List<PrincipalSpec>,
    val params: Map<String, JsonNode>,
    val idempotencyKey: String?,
    val endpointRef: String? = null
)

interface ActionNotifyPort {
    fun send(ctx: ActionContext, request: NotifyRequest): PortOutcome
}

/** [callDepth] is the nesting depth of the caller (an action run inside a workflow step passes the workflow's depth) so workflow→workflow loops are bounded. */
data class StartWorkflowRequest(val workflowRef: String, val input: JsonNode, val idempotencyKey: String, val callDepth: Int = 0)

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
