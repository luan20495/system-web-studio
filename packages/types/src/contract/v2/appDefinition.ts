/**
 * MIRROR of docs/contracts/v2/app-definition.md (+ data-runtime.md §2, action-workflow.md §2–3) @ 8b944cc — manual. See meta.ts.
 *
 * The stored document is the legacy Page Schema (`page`, `sections`, `pages[]`, `site`, `seo` — typed in ../../index.ts as PageSchema) PLUS the
 * optional V2 keys below. `pages`, `components` and `navigation` of the "target list" are derived read views, not stored keys.
 * Canonical rules encoded here: mapping fields carry `transforms[]` (the legacy key `transform` is never written); `REFRESH_QUERY` is an
 * ActionType and `RUN_QUERY` is not; `ParamType` has DATE; `ParamDef.required` defaults to true when absent; `ActionDef.trigger` is OPTIONAL;
 * every cross-reference is a LOCAL id and nothing here is a runtime database id, SQL, URL or credential.
 */
import type { PageSchema, Seo } from "../../index";
import type { PermissionCode } from "./permissions";

export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

/** `kind` of the document. NOT the product `AppKind` of the project table (D-C2-02): hence the different name. */
export const APP_DEFINITION_KINDS = ["PAGE_SCHEMA"] as const;
export type AppDefinitionKind = (typeof APP_DEFINITION_KINDS)[number];

// ----------------------------------------------------------------------------------------------------------------------- data
/** C3's ParamType; C2 adds DATE (canonical). */
export const PARAM_TYPES = ["STRING", "INTEGER", "NUMBER", "BOOLEAN", "TIMESTAMP", "DATE"] as const;
export type ParamType = (typeof PARAM_TYPES)[number];
export const QUERY_MODES = ["READ", "WRITE"] as const;
export type QueryMode = (typeof QUERY_MODES)[number];
/** C3's FieldType (docs: "FieldType enum values must equal C3's"). */
export const FIELD_TYPES = ["STRING", "NUMBER", "BOOLEAN", "DATE", "DATETIME", "OBJECT", "ARRAY"] as const;
export type FieldType = (typeof FIELD_TYPES)[number];
export const CARDINALITIES = ["SINGLE", "LIST"] as const;
export type Cardinality = (typeof CARDINALITIES)[number];
export const MAPPING_ERROR_POLICIES = ["FAIL", "NULL_FIELD", "SKIP_ROW"] as const;
export type MappingErrorPolicy = (typeof MAPPING_ERROR_POLICIES)[number];
export const VALUE_FORMATS = ["EMAIL", "URL", "UUID", "DATE", "DATETIME", "INTEGER"] as const;
export type ValueFormat = (typeof VALUE_FORMATS)[number];

/** `sourceRef` = id of a C3 DataSource (UUID, null = unresolved slot). NEVER a URL or connection string. The document cannot create a source. */
export type DataSourceDef = { id: string; name?: string; type: string; sourceRef?: string | null; description?: string };
/** `ParamDef.required` is TRUE when the key is absent (C3's default): use `paramRequired`, never `p.required ?? false`. */
export type ParamDef = { name: string; type: ParamType; required?: boolean; defaultValue?: JsonValue };
export const paramRequired = (p: Pick<ParamDef, "required">): boolean => p.required ?? true;
/** In the document a query is a BINDING: local id + local dataSourceRef + `operationKey` = id of the C3 query/mutation definition. */
export type QueryDef = { id: string; name?: string; dataSourceRef: string; mode?: QueryMode; operationKey?: string; params?: ParamDef[]; maxRows?: number };

/** C3's `Transform`: an object with `type` (toString, toNumber, trim, lower, upper, toBoolean, date, enumMap, join, split, formula) + its own keys. Kept verbatim. */
export const TRANSFORM_TYPES = ["toString", "toNumber", "trim", "lower", "upper", "toBoolean", "date", "enumMap", "join", "split", "formula"] as const;
export type TransformType = (typeof TRANSFORM_TYPES)[number];
export type TransformDef = { type: string; [key: string]: JsonValue };
export type FieldValidationDef = { minLength?: number; maxLength?: number; min?: number; max?: number; oneOf?: JsonValue[]; format?: ValueFormat };
/** Canonical field: `transforms[]` is the only key written; `from` is nullable (C3 wins over C2's old non-null `from`). */
export type FieldMappingDef = { from?: string | null; to: string; transforms: TransformDef[]; default?: JsonValue; nullable?: boolean; validation?: FieldValidationDef; description?: string };
/** `queryRef` is the LOCAL id of a query of this document; the resolver (server) translates it. */
export type MappingDef = { id: string; name?: string; queryRef: string; errorPolicy?: MappingErrorPolicy; version?: number; fields: FieldMappingDef[]; description?: string };
export type ViewModelFieldDef = { name: string; type?: FieldType; label?: string; description?: string };
export type ViewModelDef = { id: string; name?: string; queryRef?: string; mappingRef?: string; cardinality?: Cardinality; fields: ViewModelFieldDef[]; description?: string };
/** C2's `dataBindings[]` is the ONLY binding concept: one prop of one section ← exactly one of viewModelRef / queryRef (mappingRef only with queryRef). */
export type DataBindingDef = { id: string; sectionId: string; prop: string; viewModelRef?: string; queryRef?: string; mappingRef?: string };

// ------------------------------------------------------------------------------------------------------------------- actions
/** action-workflow.md §1: ONE list of nine. `RUN_QUERY`, `WRITE_DATA`, `CALL_CONNECTOR_OPERATION`, `SET_VALUE` are rejected by the backend. */
export const ACTION_TYPES = ["NAVIGATE", "REFRESH_QUERY", "SUBMIT_FORM", "CREATE_RECORD", "UPDATE_RECORD", "DELETE_RECORD", "CALL_API", "NOTIFY", "START_WORKFLOW"] as const;
export type ActionType = (typeof ACTION_TYPES)[number];
/** Client instructions without a server side effect; every other type is mutating and needs an idempotency key. */
export const CLIENT_ONLY_ACTION_TYPES = ["NAVIGATE", "REFRESH_QUERY"] as const satisfies readonly ActionType[];
/** Names that never existed in stored data (contract §1): the frontend must not offer or send them. */
export const REJECTED_ACTION_ALIASES = ["RUN_QUERY", "WRITE_DATA", "CALL_CONNECTOR_OPERATION", "SET_VALUE"] as const;
/** EventType wire names; `Event.name = "$sectionId.$wire"`. */
export const EVENT_TYPES = ["onLoad", "onClick", "onChange", "onSubmit", "onSuccess", "onError"] as const;
export type EventType = (typeof EVENT_TYPES)[number];
export const ACTION_INPUT_TYPES = ["STRING", "INTEGER", "NUMBER", "BOOLEAN", "TIMESTAMP", "DATE", "OBJECT", "ARRAY", "ANY"] as const;
export type ActionInputType = (typeof ACTION_INPUT_TYPES)[number];
export const INPUT_SOURCE_KINDS = ["COMPONENT_STATE", "ROUTE_PARAM", "FORM_FIELD", "VIEW_MODEL", "PREVIOUS_RESULT", "LITERAL", "CONTEXT"] as const;
export type InputSourceKind = (typeof INPUT_SOURCE_KINDS)[number];
export const CONTEXT_KEYS = ["USER_ID", "TENANT_ID", "WORKSPACE_ID", "APP_ID", "REQUEST_ID", "NOW"] as const;
export type ContextKey = (typeof CONTEXT_KEYS)[number];
export const IDEMPOTENCY_POLICIES = ["NONE", "OPTIONAL", "REQUIRED"] as const;
export type IdempotencyPolicy = (typeof IDEMPOTENCY_POLICIES)[number];
export const NOTIFY_CHANNELS = ["IN_APP", "EMAIL", "WEBHOOK", "SMS"] as const;
export type NotifyChannel = (typeof NOTIFY_CHANNELS)[number];
export const PRINCIPAL_KINDS = ["USER", "GROUP", "ROLE", "DEPARTMENT_MANAGER"] as const;
export type PrincipalKind = (typeof PRINCIPAL_KINDS)[number];

/** `trigger` is OPTIONAL (contract v2): a UI-bound action has one; a workflow step / schedule / chained (`onSuccess`/`onError`) child action does not. */
export type ActionTriggerDef = { sectionId: string; event: EventType };
export type ActionInputDef = { name: string; type?: ActionInputType; required?: boolean; maxLength?: number };
export type InputSourceDef = { source: InputSourceKind; path?: string; name?: string; value?: JsonValue; key?: ContextKey };
export type PrincipalDef = { kind: PrincipalKind; userId?: string; tenantId?: string; groupId?: string; role?: string; departmentId?: string };
export type ActionDef = {
  id: string; name?: string; type: ActionType; enabled?: boolean; trigger?: ActionTriggerDef;
  pageRef?: string; queryRef?: string; viewModelRef?: string; dataSourceRef?: string; operationKey?: string; workflowRef?: string;
  channel?: NotifyChannel; templateRef?: string; endpointRef?: string; recipients?: PrincipalDef[]; permissionRef?: string;
  inputs?: ActionInputDef[]; inputMapping?: Record<string, InputSourceDef>;
  idempotency?: IdempotencyPolicy; limits?: { timeoutMillis?: number };
  onSuccess?: string[]; onError?: string[];
};

// ----------------------------------------------------------------------------------------------------------------- workflows
export const WORKFLOW_TRIGGERS = ["MANUAL", "SCHEDULE", "ACTION"] as const;
export type WorkflowTriggerType = (typeof WORKFLOW_TRIGGERS)[number];
export const STEP_KINDS = ["ACTION", "WAIT", "APPROVAL", "BRANCH", "END"] as const;
export type StepKind = (typeof STEP_KINDS)[number];
export type ValueRefDef = { from: "INPUT" | "STEP" | "LITERAL"; path?: string; stepId?: string; value?: JsonValue };
export type RetryDef = { maxAttempts?: number; initialBackoffMillis?: number; multiplier?: number; maxBackoffMillis?: number };
export type ApprovalDef = { title?: string; approvers?: PrincipalDef[]; requiredApprovals?: number; expiresInSeconds?: number; allowSelfApproval?: boolean; notifyTemplateRef?: string; onReject?: string; onExpire?: string };
/** C4 `CompareOp`. */
export const COMPARE_OPS = ["EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "CONTAINS"] as const;
export type CompareOp = (typeof COMPARE_OPS)[number];
/** C4's typed condition tree, kept as JSON: `{op,left,right}`, `{all:[..]}`, `{any:[..]}`, `{not:..}`, `{exists:ref}`. */
export type ConditionDef =
  | { op: CompareOp; left: ValueRefDef; right: ValueRefDef }
  | { all: ConditionDef[] } | { any: ConditionDef[] } | { not: ConditionDef } | { exists: ValueRefDef };
export type BranchDef = { condition: ConditionDef; next: string };
export type WorkflowStepDef = {
  id: string; kind?: StepKind; actionRef?: string; inputs?: Record<string, ValueRefDef>; next?: string; onError?: string;
  retry?: RetryDef; timeoutMillis?: number; waitSeconds?: number; approval?: ApprovalDef; branches?: BranchDef[]; defaultNext?: string; compensationActionRef?: string;
};
export type WorkflowLimitsDef = { maxSteps?: number; maxStepExecutions?: number; maxRetries?: number; maxDurationSeconds?: number; maxPayloadBytes?: number; maxDepth?: number; maxStepTimeoutMillis?: number };
export type WorkflowDef = {
  id: string; name?: string; enabled?: boolean; trigger?: WorkflowTriggerType; schedule?: string; timezone?: string; startStepId?: string;
  compensateOnCancel?: boolean; limits?: WorkflowLimitsDef; steps: WorkflowStepDef[];
};

// -------------------------------------------------------------------------------------------------------------- permissions etc.
export const PERMISSION_RESOURCE_TYPES = ["QUERY", "ACTION", "WORKFLOW", "VIEW_MODEL", "DATA_SOURCE"] as const;
export type PermissionResourceType = (typeof PERMISSION_RESOURCE_TYPES)[number];
/** `permission` must be one of the 14 canonical codes (tenant-permission.md §5). */
export type PermissionDef = { id: string; name?: string; permission: PermissionCode; resourceType: PermissionResourceType; resourceRef: string };

/** §5: DRAFT intent only; the policy is `publish_configs` (V27) and the served state is `deployments`. SERVER_APP is not legal in a draft. */
export const PUBLISH_MODES = ["STATIC", "DYNAMIC"] as const;
export type PublishMode = (typeof PUBLISH_MODES)[number];
export const PUBLISH_VISIBILITIES = ["PRIVATE", "TENANT", "PUBLIC", "PRIVATE_LINK"] as const;
export type PublishVisibility = (typeof PUBLISH_VISIBILITIES)[number];
export type PublishConfigDef = { mode?: PublishMode; visibility?: PublishVisibility; requiresAuth?: boolean; cacheSeconds?: number };

export const THEME_FONTS = ["SYSTEM", "SERIF", "MONO", "ROUNDED"] as const;
export type ThemeFont = (typeof THEME_FONTS)[number];
export const THEME_RADII = ["NONE", "SM", "MD", "LG"] as const;
export type ThemeRadius = (typeof THEME_RADII)[number];
/** colours are `#RRGGBB`; fonts and radii come from fixed lists, so a theme can never carry CSS or a font URL. */
export type ThemeDef = { colors?: Record<string, string>; fontFamily?: ThemeFont; radius?: ThemeRadius };

/** The V2 keys of the stored document (every one optional; a document is strict only when it declares `schemaVersion`). */
export type AppDefinitionV2Keys = {
  schemaVersion?: number; kind?: AppDefinitionKind; seo?: Seo;
  theme?: ThemeDef; dataSources?: DataSourceDef[]; viewModels?: ViewModelDef[]; queries?: QueryDef[]; mappings?: MappingDef[];
  dataBindings?: DataBindingDef[]; actions?: ActionDef[]; workflows?: WorkflowDef[]; permissions?: PermissionDef[]; publishConfig?: PublishConfigDef;
  /** keys must be namespaced `xweb.*` */
  extensions?: Record<string, JsonValue>;
};
export type AppDefinitionV2 = PageSchema & AppDefinitionV2Keys;

/** The collections an author edits with typed operations, and the operation family of each (DefinitionPatch). Data sources are NOT here: they are granted, not created. */
export const DEFINITION_COLLECTIONS = {
  viewModels: "VIEW_MODEL", queries: "QUERY", mappings: "MAPPING", dataBindings: "DATA_BINDING", actions: "ACTION", workflows: "WORKFLOW_REF", permissions: "PERMISSION_REF",
} as const;
export type DefinitionCollection = keyof typeof DEFINITION_COLLECTIONS;
/** The 23 typed V2 operations (app-definition.md §4). The 12 legacy page operations are unchanged (SchemaOperation in ../../index.ts). */
export type DefinitionOperationType =
  | `${"ADD" | "UPDATE" | "REMOVE"}_${"VIEW_MODEL" | "QUERY" | "MAPPING" | "DATA_BINDING" | "ACTION" | "WORKFLOW_REF" | "PERMISSION_REF"}`
  | "UPDATE_THEME" | "UPDATE_PUBLISH_CONFIG";
/** ADD_*: `definition` = the whole object; UPDATE_*: the fields to change (null = clear the field); REMOVE_*: only `definitionId`. REMOVE does not cascade. */
export type DefinitionOperation = { type: DefinitionOperationType; definitionId?: string; definition?: Record<string, JsonValue | null> };

/** component-metadata (GET /api/v1/component-metadata) — ComponentMetadataV2. Event names are the canonical EventType wire names. */
export type ComponentEventMeta = { name: EventType; label: string; supportedActions: ActionType[] };
export type BindablePropMeta = { prop: string; cardinality: "LIST" | "SINGLE"; itemFields: string[] };
export type ComponentMetadataV2 = {
  id: string; version: string; category: string; propsSchema: unknown;
  bindableProps: BindablePropMeta[]; events: ComponentEventMeta[]; supportedActions: ActionType[];
  visibilityConditions: { prop: string; kind: string; description: string }[];
  preview: { layout: "FULL_WIDTH" | "CONTAINED"; sampleProps: unknown };
  source: "OVERLAY" | "DERIVED";
};
