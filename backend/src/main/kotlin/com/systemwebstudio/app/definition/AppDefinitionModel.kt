package com.systemwebstudio.app.definition

import tools.jackson.databind.JsonNode

/*
 * AppDefinitionV2 (contract: docs/contracts/app-definition-v2.md).
 *
 * A SUPERSET of the Page Schema: the legacy keys (page, sections, pages, site, seo ...) are carried verbatim in [AppDefinitionV2.pageSchema]
 * and are still validated by PageSchemaValidator and edited by SchemaPatchEngine; nothing here re-interprets them. Everything new is OPTIONAL
 * (absent / empty for every existing project) and purely declarative:
 *   - definitions refer to each other by typed id only (query -> dataSource, mapping -> query, viewModel -> query | mapping,
 *     action -> query | viewModel | workflow, workflow step -> action, binding -> section + viewModel | query, permission -> resource);
 *   - there is no field that can carry SQL, a raw URL, a credential or code. Free text is display text; identifiers are pattern-checked.
 * Nothing in this file executes anything: data runtime (C3), action/workflow runtime (C4) and tenancy/permissions (C1) are separate.
 */

/**
 * What kind of application the document describes. Only the page-schema family exists in this phase (STATIC_APP is not an AppDefinition).
 * Named AppDefinitionKind (not AppKind) so it cannot be confused with the AppKind of the project table / frontend (D-C2-02, B-C5-01 G2);
 * the JSON value stays `"kind":"PAGE_SCHEMA"`.
 */
enum class AppDefinitionKind { PAGE_SCHEMA }

/**
 * Same set, same order as the data platform's ParamType (C3 `data.query.ParamType`, canonical: docs/contracts/v2/app-definition.md §3), so a
 * declared parameter can be passed to a query unchanged. DATE = a calendar date `yyyy-MM-dd`. A conformance test (ContractConformanceTests)
 * keeps the names equal to the contract list.
 */
enum class ParamType { STRING, INTEGER, NUMBER, BOOLEAN, TIMESTAMP, DATE }

/**
 * READ queries back view models and data bindings (reads are never actions, D-C4-009). A WRITE query is the app-side name of a
 * *declared mutation* (C3 has not defined mutations yet, B-006 of C4): only the write actions may use it. PROVISIONAL until C3 settles.
 */
enum class QueryMode { READ, WRITE }

enum class FieldType { STRING, NUMBER, BOOLEAN, DATE, DATETIME, OBJECT, ARRAY }

enum class Cardinality { SINGLE, LIST }

/**
 * Declarative action kinds = C4's `ActionType`, one list (docs/contracts/v2/action-workflow.md §1). `REFRESH_QUERY` re-runs a declared READ
 * query (it replaces the draft name RUN_QUERY, which is NOT accepted: neither it nor WRITE_DATA / CALL_CONNECTOR_OPERATION / SET_VALUE ever
 * existed in stored data). No action carries SQL / a URL / code. Required references per type are checked by [AppDefinitionValidator]:
 *   NAVIGATE → pageRef · REFRESH_QUERY → queryRef (READ) · SUBMIT_FORM / CREATE_RECORD / UPDATE_RECORD / DELETE_RECORD → queryRef (WRITE) ·
 *   CALL_API → dataSourceRef + operationKey (a declared connector operation) · START_WORKFLOW → workflowRef · NOTIFY → channel + templateRef
 */
enum class ActionType { NAVIGATE, REFRESH_QUERY, SUBMIT_FORM, CREATE_RECORD, UPDATE_RECORD, DELETE_RECORD, CALL_API, NOTIFY, START_WORKFLOW }

enum class WorkflowTriggerType { MANUAL, SCHEDULE, ACTION }

enum class PermissionResourceType { QUERY, ACTION, WORKFLOW, VIEW_MODEL, DATA_SOURCE }

/**
 * How a published app is served. STATIC = HTML built once; DYNAMIC = data-bound sections are filled from data (snapshot at publish time
 * or per request: decided by D-C5-03, PROVISIONAL); SERVER_APP = a source project running on the server runtime. SERVER_APP belongs to
 * `publish_configs` only: it is not a legal value of the declarative draft in an AppDefinition.
 */
enum class PublishMode { STATIC, DYNAMIC, SERVER_APP }

/** Who may open the published app. SHARE (who may edit / view in the studio) is a different thing and is never stored here. */
enum class PublishVisibility { PRIVATE, TENANT, PUBLIC, PRIVATE_LINK }

enum class ThemeFont { SYSTEM, SERIF, MONO, ROUNDED }

enum class ThemeRadius { NONE, SM, MD, LG }

/**
 * A declared parameter of a query. [required] defaults to TRUE when the document omits it (C3's `QueryParamSpec.required` default, canonical).
 * [defaultValue] is a scalar of the declared type (never an expression).
 */
data class ParamDef(val name: String, val type: ParamType, val required: Boolean = true, val defaultValue: JsonNode? = null)

/**
 * A data source USED by this app. It carries no connection information at all: [sourceRef] is the opaque id of a source registered in the
 * data platform (credentials stay server-side there); [type] only names the kind ("rest", "postgres" ...).
 */
data class DataSourceDef(val id: String, val name: String? = null, val type: String, val sourceRef: String? = null, val description: String? = null)

/**
 * A declared query: which data source it reads/writes ([dataSourceRef] = id in dataSources[]) and which approved, pre-defined operation
 * ([operationKey]) of that source it uses. It never contains SQL or a URL.
 */
data class QueryDef(
    val id: String, val name: String? = null, val dataSourceRef: String, val mode: QueryMode = QueryMode.READ,
    val operationKey: String? = null, val params: List<ParamDef> = emptyList(), val maxRows: Int? = null,
    /**
     * Wire key `public`. Listed in the public query allow-list of every release published from a version of this document (see [PublicQueries]):
     * a visitor of the published site may run it, read-only, through the Public Runtime. Default false = deny. Only a READ query can be public.
     */
    val isPublic: Boolean = false
)

/** What to do with a value that cannot be mapped (C3 `MappingErrorPolicy`; default NULL_FIELD). */
enum class MappingErrorPolicy { FAIL, NULL_FIELD, SKIP_ROW }

/** Closed set of value checks (C3 `ValueFormat`). */
enum class ValueFormat { EMAIL, URL, UUID, DATE, DATETIME, INTEGER }

/** Closed set of checks on a mapped value (C3 `FieldValidation`): deliberately no free-form regex. [min]/[max] are JSON numbers, [oneOf] plain values. */
data class FieldValidationDef(
    val minLength: Int? = null, val maxLength: Int? = null, val min: JsonNode? = null, val max: JsonNode? = null,
    val oneOf: List<JsonNode>? = null, val format: ValueFormat? = null
)

/**
 * One column of a mapping, canonical = the frozen contract (`docs/contracts/v2/app-definition.md` §3): [from] (a column / dotted path of the query
 * result; absent for a constant or a computed value), [transforms] (an ordered list of at most 8 transform objects, each kept as JSON and parsed by
 * C3's `Transforms`; WRITTEN under the canonical key `transforms`), [defaultValue] (key `default`), [nullable] (default true), [validation].
 * The reader also accepts the LEGACY key `transform` (C3's old reader: one object, or an array) and normalises it into [transforms]; the codec never
 * writes `transform`. A field that carries both keys is rejected (ambiguous).
 */
data class FieldMappingDef(
    val from: String? = null, val to: String, val transforms: List<JsonNode> = emptyList(), val defaultValue: JsonNode? = null,
    val nullable: Boolean = true, val validation: FieldValidationDef? = null, val description: String? = null
)

/** Canonical = C3's `MappingDefinition` JSON. [queryRef] is the LOCAL id of a query of this document (the resolver translates it, see [AppDataBindingResolver]). */
data class MappingDef(
    val id: String, val name: String? = null, val queryRef: String, val fields: List<FieldMappingDef> = emptyList(),
    val errorPolicy: MappingErrorPolicy = MappingErrorPolicy.NULL_FIELD, val version: Long = 1, val description: String? = null
)

data class ViewModelFieldDef(val name: String, val type: FieldType = FieldType.STRING, val label: String? = null, val description: String? = null)

data class ViewModelDef(
    val id: String, val name: String? = null, val queryRef: String? = null, val mappingRef: String? = null,
    val cardinality: Cardinality = Cardinality.LIST, val fields: List<ViewModelFieldDef> = emptyList(), val description: String? = null
)

/**
 * UI event that starts an action: `sectionId` is a section of the page schema, `event` is an `EventType` wire name
 * (`onLoad, onClick, onChange, onSubmit, onSuccess, onError`, see [ActionEvents]).
 */
data class ActionTriggerDef(val sectionId: String, val event: String)

/** Wire names of C4's `EventType` (`Event.name = "$sectionId.$wire"`): the only events an action may be bound to. */
object ActionEvents { val ALL = listOf("onLoad", "onClick", "onChange", "onSubmit", "onSuccess", "onError") }

/** Type of a declared action input: C4's `InputType` plus the query parameter names (C4 maps INTEGER / TIMESTAMP / DATE onto its own types). */
enum class ActionInputType { STRING, INTEGER, NUMBER, BOOLEAN, TIMESTAMP, DATE, OBJECT, ARRAY, ANY }

/** A declared action input. [maxLength] is only valid for STRING. [required] defaults to false (C4's reader). */
data class ActionInputDef(val name: String, val type: ActionInputType = ActionInputType.ANY, val required: Boolean = false, val maxLength: Int? = null)

/** Where an action input comes from (C4 `InputSource`): typed lookups only, no expression language. Which attribute is used depends on [source]. */
enum class InputSourceKind { COMPONENT_STATE, ROUTE_PARAM, FORM_FIELD, VIEW_MODEL, PREVIOUS_RESULT, LITERAL, CONTEXT }

/** Server-derived values (C4 `ContextKey`); never client-controlled. */
enum class ContextKeyName { USER_ID, TENANT_ID, WORKSPACE_ID, APP_ID, REQUEST_ID, NOW }

/**
 * `{ "source": "FORM_FIELD", "name": "email" }`. COMPONENT_STATE / VIEW_MODEL / PREVIOUS_RESULT use [path] (dotted plain segments; may be empty only
 * for PREVIOUS_RESULT), ROUTE_PARAM / FORM_FIELD use [name], LITERAL uses [value] (a plain value), CONTEXT uses [key].
 */
data class InputSourceDef(
    val source: InputSourceKind, val path: String? = null, val name: String? = null, val value: JsonNode? = null, val key: ContextKeyName? = null
)

/** C4 `IdempotencyPolicy`: state-changing actions default to REQUIRED at runtime; NONE on a state-changing action is rejected by the validator. */
enum class IdempotencyPolicyName { NONE, OPTIONAL, REQUIRED }

/** C4 `NotifyChannel`. */
enum class NotifyChannelName { IN_APP, EMAIL, WEBHOOK, SMS }

/** Principal of NOTIFY recipients / approvers (C4 `PrincipalSpecs`): USER → userId (+ tenantId), GROUP → groupId, ROLE → role, DEPARTMENT_MANAGER → departmentId?. */
enum class PrincipalKind { USER, GROUP, ROLE, DEPARTMENT_MANAGER }

data class PrincipalDef(
    val kind: PrincipalKind, val userId: String? = null, val tenantId: String? = null, val groupId: String? = null,
    val role: String? = null, val departmentId: String? = null
)

/** `limits` of an action: it may only LOWER the platform ceiling (C4). */
data class ActionLimitsDef(val timeoutMillis: Int? = null)

/**
 * One declared action of the app, canonical = C4's `actions[]` entry (docs/contracts/v2/action-workflow.md §2). The definition lives in the
 * app (D-C2-04); C4's runtime reads it through an adapter. [trigger] + [id] are what C4 calls `ActionRef` (see [actionRefs]).
 * Every field of the contract is typed here so a read → write cycle loses nothing; unknown fields are rejected (C4's reader ignores them, so
 * this validator is the gatekeeper).
 */
data class ActionDef(
    val id: String, val name: String? = null, val type: ActionType, val trigger: ActionTriggerDef? = null,
    val queryRef: String? = null, val viewModelRef: String? = null, val workflowRef: String? = null,
    val dataSourceRef: String? = null, val operationKey: String? = null,
    val inputs: List<ActionInputDef> = emptyList(), val permissionRef: String? = null,
    /** NAVIGATE target: the id of a page of this app, or "home". Never a URL. */
    val pageRef: String? = null,
    val enabled: Boolean = true,
    /** NOTIFY */
    val channel: NotifyChannelName? = null, val templateRef: String? = null, val endpointRef: String? = null, val recipients: List<PrincipalDef> = emptyList(),
    /** input name -> source; every key must be a declared input */
    val inputMapping: Map<String, InputSourceDef> = emptyMap(),
    val idempotency: IdempotencyPolicyName? = null, val limits: ActionLimitsDef? = null,
    /** ids of actions chained after success / failure (C4: at most 8, no self reference) */
    val onSuccess: List<String> = emptyList(), val onError: List<String> = emptyList()
)

enum class StepKind { ACTION, WAIT, APPROVAL, BRANCH, END }

/** `{ "from": "INPUT", "path": "a.b" }`, `{ "from": "STEP", "stepId": "s1", "path": "x" }`, `{ "from": "LITERAL", "value": … }` (C4 `ValueRef`). */
enum class ValueRefFrom { INPUT, STEP, LITERAL }

data class ValueRefDef(val from: ValueRefFrom, val path: String? = null, val stepId: String? = null, val value: JsonNode? = null)

data class RetryDef(val maxAttempts: Int? = null, val initialBackoffMillis: Int? = null, val multiplier: JsonNode? = null, val maxBackoffMillis: Int? = null)

data class ApprovalDef(
    val title: String? = null, val approvers: List<PrincipalDef> = emptyList(), val requiredApprovals: Int? = null, val expiresInSeconds: Int? = null,
    val allowSelfApproval: Boolean? = null, val notifyTemplateRef: String? = null, val onReject: String? = null, val onExpire: String? = null
)

/**
 * First matching branch wins. [condition] is C4's typed condition tree kept as JSON (`{op,left,right}`, `{all:[..]}`, `{any:[..]}`, `{not:..}`,
 * `{exists:ref}`); its structure, depth and size are checked here, its meaning by C4.
 */
data class BranchDef(val condition: JsonNode, val next: String)

/**
 * One step, canonical = C4's `steps[]` entry. [kind] absent = ACTION when [actionRef] is set, END otherwise (C4's reader). [next] / [onError]
 * name another step of the same workflow (default: the following step).
 */
data class WorkflowStepDef(
    val id: String, val actionRef: String? = null, val next: String? = null, val onError: String? = null,
    val kind: StepKind? = null, val inputs: Map<String, ValueRefDef> = emptyMap(), val retry: RetryDef? = null,
    val timeoutMillis: Int? = null, val waitSeconds: Int? = null, val approval: ApprovalDef? = null,
    val branches: List<BranchDef> = emptyList(), val defaultNext: String? = null, val compensationActionRef: String? = null
)

/** Workflow-level ceilings (C4 `WorkflowLimits`); a definition may only lower the platform's. */
data class WorkflowLimitsDef(
    val maxSteps: Int? = null, val maxStepExecutions: Int? = null, val maxRetries: Int? = null, val maxDurationSeconds: Int? = null,
    val maxPayloadBytes: Int? = null, val maxDepth: Int? = null, val maxStepTimeoutMillis: Int? = null
)

data class WorkflowDef(
    val id: String, val name: String? = null, val trigger: WorkflowTriggerType = WorkflowTriggerType.MANUAL,
    /** 5-field cron expression; required for SCHEDULE triggers, forbidden otherwise */
    val schedule: String? = null, val steps: List<WorkflowStepDef> = emptyList(),
    val enabled: Boolean = true, val timezone: String? = null, val startStepId: String? = null,
    val compensateOnCancel: Boolean = false, val limits: WorkflowLimitsDef? = null
)

/**
 * Names a permission code (catalogue owned by C1, e.g. QUERY_EXECUTE) required for a resource of this app. Enforcement is the access layer's
 * job (AccessContext.require); here it is only declared and checked for dangling references.
 */
data class PermissionDef(val id: String, val name: String? = null, val permission: String, val resourceType: PermissionResourceType, val resourceRef: String)

/**
 * Binds one prop of one section to data: exactly one of [viewModelRef] / [queryRef]; [mappingRef] only together with [queryRef].
 * (Contract name: DataBinding. Stored in `dataBindings[]` so section props keep following the component registry unchanged.)
 */
data class DataBindingDef(
    val id: String, val sectionId: String, val prop: String,
    val viewModelRef: String? = null, val queryRef: String? = null, val mappingRef: String? = null
)

/**
 * DECLARATIVE DRAFT of how the author wants the app published. It is intent only: the authoritative state is the `publish_configs` row and
 * the `deployments` (D-C2-06), and nothing is published because this exists. The same document can be saved, versioned and restored without
 * changing what is live.
 */
data class PublishConfigDef(
    val mode: PublishMode = PublishMode.STATIC, val visibility: PublishVisibility = PublishVisibility.PRIVATE,
    val requiresAuth: Boolean = false, val cacheSeconds: Int? = null
)

/**
 * Design tokens of the app (PROVISIONAL: the renderers do not consume them yet). Bounded and typed: colours are #RRGGBB, fonts and radii
 * come from fixed lists, so a theme can never carry CSS, a font URL or script.
 */
data class ThemeDef(val colors: Map<String, String> = emptyMap(), val fontFamily: ThemeFont? = null, val radius: ThemeRadius? = null)

/** A section of the page schema as seen by the V2 reference checks (any page). */
data class SectionRef(val id: String, val type: String?, val componentVersion: String?, val pageId: String)

data class AppDefinitionV2(
    /** null = a legacy document that never declared a version (read with version-2 semantics and empty extensions); otherwise 2 */
    val schemaVersion: Int? = null,
    val kind: AppDefinitionKind? = null,
    /** every non-V2 top-level key, verbatim and in order: page, sections, pages, site, seo and anything unknown to this model */
    val pageSchema: Map<String, JsonNode> = emptyMap(),
    val dataSources: List<DataSourceDef> = emptyList(),
    val viewModels: List<ViewModelDef> = emptyList(),
    val queries: List<QueryDef> = emptyList(),
    val mappings: List<MappingDef> = emptyList(),
    val actions: List<ActionDef> = emptyList(),
    val workflows: List<WorkflowDef> = emptyList(),
    val permissions: List<PermissionDef> = emptyList(),
    val dataBindings: List<DataBindingDef> = emptyList(),
    val theme: ThemeDef? = null,
    val publishConfig: PublishConfigDef? = null,
    /** namespaced extension area (key like "acme.crm"); free-form JSON data, still scanned for URLs / credentials / code-like keys */
    val extensions: Map<String, JsonNode> = emptyMap()
) {
    /** true when the document uses nothing beyond the legacy Page Schema */
    val isLegacyOnly: Boolean
        get() = schemaVersion == null && kind == null && dataSources.isEmpty() && viewModels.isEmpty() && queries.isEmpty() && mappings.isEmpty() &&
            actions.isEmpty() && workflows.isEmpty() && permissions.isEmpty() && dataBindings.isEmpty() && theme == null && publishConfig == null && extensions.isEmpty()

    /** every section of the home page and of every extra page (malformed entries are skipped; PageSchemaValidator reports those) */
    fun sections(): List<SectionRef> {
        val out = ArrayList<SectionRef>()
        fun collect(sections: JsonNode?, pageId: String) {
            if (sections == null || !sections.isArray) return
            sections.forEach { s ->
                if (!s.isObject) return@forEach
                val id = s.get("id")?.takeIf { it.isString }?.asString() ?: return@forEach
                out += SectionRef(id, s.get("type")?.takeIf { it.isString }?.asString(), s.get("componentVersion")?.takeIf { it.isString }?.asString(), pageId)
            }
        }
        collect(pageSchema["sections"], "home")
        val pages = pageSchema["pages"]
        if (pages != null && pages.isArray) pages.forEach { p ->
            if (p.isObject) collect(p.get("sections"), p.get("id")?.takeIf { it.isString }?.asString() ?: "?")
        }
        return out
    }
}

/**
 * The public query allow-list of a document (V1 Public Runtime, D-C0-35): the local ids of the queries marked `public` that are READ queries, in
 * document order. Pure function of the document, so it is exactly as immutable as the project version snapshot it is read from: a release names
 * a version, a version never changes, hence the allow-list of a release can never change, a rollback restores the allow-list of the restored
 * release by moving the pointer, and an edit of the draft cannot reach it.
 */
object PublicQueries {
    fun of(def: AppDefinitionV2): List<String> = def.queries.filter { it.isPublic && it.mode == QueryMode.READ }.map { it.id }
}
