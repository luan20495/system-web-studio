/** MIRROR of docs/contracts/v2/tenant-permission.md @ 8b944cc — manual. See meta.ts. */

/** §5: the one permission vocabulary shared by C1–C5. A code that is not here is not allowed in `PermissionDef.permission`. */
export const PERMISSION_CODES = [
  "APP_VIEW", "APP_USE", "APP_EDIT", "APP_PUBLISH", "APP_SHARE",
  "DATA_SOURCE_VIEW", "DATA_SOURCE_MANAGE", "QUERY_EXECUTE", "DATA_MUTATE",
  "ACTION_EXECUTE", "WORKFLOW_EXECUTE", "WORKFLOW_MANAGE",
  "TENANT_MANAGE", "TENANT_MEMBERS",
] as const;
export type PermissionCode = (typeof PERMISSION_CODES)[number];

/**
 * §5 table, column "Permission constant": until C1 lands the canonical names, the API still returns the legacy `PROJECT_*` constants in
 * `project.permissions` (they are the storage of the `APP_*` codes). APP_SHARE is `PROJECT_MEMBERS` today (→ APP_SHARE when sharing lands, T15).
 * The frontend never invents a code: anything that is neither canonical nor in this map is dropped by `canonicalPermissions`.
 */
export const LEGACY_PERMISSION_ALIAS = {
  PROJECT_READ: "APP_VIEW",
  PROJECT_EDIT: "APP_EDIT",
  PROJECT_SETTINGS: "APP_EDIT",
  PROJECT_PUBLISH: "APP_PUBLISH",
  PROJECT_MEMBERS: "APP_SHARE",
} as const satisfies Record<string, PermissionCode>;

/** §1 */
export const TENANT_STATUSES = ["ACTIVE", "SUSPENDED", "DELETED"] as const;
export type TenantStatus = (typeof TENANT_STATUSES)[number];
export const TENANT_ROLES = ["TENANT_ADMIN", "MEMBER"] as const;
export type TenantRole = (typeof TENANT_ROLES)[number];
/** §2 ActorKind: ONE definition server-side (com.systemwebstudio.tenancy.ActorKind). */
export const ACTOR_KINDS = ["USER", "SYSTEM", "APP_TOKEN", "SERVICE"] as const;
export type ActorKind = (typeof ACTOR_KINDS)[number];

/**
 * §2 TenantContext (server-side shape, documented here so UI code can talk about it). It is resolved by the server from the workspace in the
 * path: THE CLIENT NEVER SENDS A TENANT ID and no request type in this package has a tenant field.
 * `MeResponse` now carries tenantId/tenantRole/platformScope/businessAccess/tenants[]/permissions[] (integration/v2 @ 4884be3; see Me in ../../index.ts); the
 * portal gate (packages/permissions capabilitiesOf) reads them and falls back to `systemAdmin` against an older backend (closes B-C5-05; D-C5-05 stays for tenant.administer, M-05).
 */
export type TenantContextShape = { tenantId: string; tenantRole: TenantRole | null; status: TenantStatus; platformScope: boolean };

/** HTTP error codes the Builder has to understand (C3 §4, C2 commit path, C4 runtime). */
export const API_ERROR = {
  REVISION_CONFLICT: "REVISION_CONFLICT",             // 409, CAS on projects.revision
  SCHEMA_INVALID: "SCHEMA_INVALID",                   // 422, details.violations[{path,message}]
  INVALID_OPERATION: "INVALID_OPERATION",             // 400, structural problem of an operation
  IDEMPOTENCY_OUTCOME_UNKNOWN: "IDEMPOTENCY_OUTCOME_UNKNOWN", // 409, an ambiguous failure: the write may or may not have happened
  MUTATION_REJECTED: "MUTATION_REJECTED",             // 422, the data source refused the write
  TENANT_SUSPENDED: "TENANT_SUSPENDED",               // 403
  // data-runtime.md §4b (frozen 2026-10-06): the rest of the write-path table
  IDEMPOTENCY_IN_PROGRESS: "IDEMPOTENCY_IN_PROGRESS", // 409, another request with this key is still running: retry after a short wait
  IDEMPOTENCY_CONFLICT: "IDEMPOTENCY_CONFLICT",       // 409, the key was reused with different parameters (client bug): never retry
  MUTATION_UNSUPPORTED: "MUTATION_UNSUPPORTED",       // 422 family: well-formed but not executable here (nothing applied, not retryable)
  READ_ONLY_VIOLATION: "READ_ONLY_VIOLATION",
  INVALID_MAPPING: "INVALID_MAPPING",
  MAPPING_FAILED: "MAPPING_FAILED",
  UNSUPPORTED_TYPE: "UNSUPPORTED_TYPE",
  RATE_LIMITED: "RATE_LIMITED",                       // 429, retry with backoff (Retry-After)
} as const;
/** §4b: 422 codes that certainly applied nothing and will fail again with the same input. */
export const NOT_EXECUTABLE_CODES = ["MUTATION_UNSUPPORTED", "READ_ONLY_VIOLATION", "INVALID_MAPPING", "MAPPING_FAILED", "UNSUPPORTED_TYPE"] as const;
export type ApiErrorCode = (typeof API_ERROR)[keyof typeof API_ERROR];
