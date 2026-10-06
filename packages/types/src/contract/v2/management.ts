/**
 * MIRROR of docs/parallel/c3/MANAGEMENT_API.md (C3, branch agent/c3-data-prod, commit e606465) and of the controller code it describes
 * (`wiring/DataManagementControllers.kt`, `data/gateway/ManagementHttp.kt`) — manual. NOT FROZEN: the document is C3's, the contracts folder of C0 does not contain it yet,
 * and the routes are NOT verified against a running backend (no Spring compile, no route test, no live call was available to C5).
 * Anything that is not in the document or the controller does not exist; do not add a route here before C3 has it.
 *
 * Routes (all under `/api/v1/workspaces/{workspaceId}`; tenant is derived by the server from the workspace — the client never sends a tenant id):
 *   GET    /data-sources/connectors                         → { items: ConnectorDescriptor[] }
 *   GET    /data-sources                                    → { items: DataSourceView[] }
 *   POST   /data-sources                                    → 201 DataSourceView
 *   GET    /data-sources/{id}                               → DataSourceView
 *   PATCH  /data-sources/{id}                               → DataSourceView          (name | config (replaces ALL) | status)
 *   DELETE /data-sources/{id}                               → 204                     (409 CONFLICT while bound)
 *   GET    /data-sources/{id}/credential                    → CredentialMetadata
 *   PUT    /data-sources/{id}/credential   {credential}     → CredentialMetadata      (write-only body)
 *   DELETE /data-sources/{id}/credential                    → 204
 *   POST   /data-sources/{id}/test         (no body)        → 200 ConnectionTestResult (a failed test is still HTTP 200)
 *   GET    /projects/{projectId}/data-bindings              → { items: DataBinding[] }
 *   PUT    /projects/{projectId}/data-bindings/{mode}/{slotId}  {dataSourceId} → DataBinding
 *   DELETE /projects/{projectId}/data-bindings/{mode}/{slotId}  → 204                 (404 when there is no such binding)
 */
export const MANAGEMENT_API_SOURCE = { doc: "docs/parallel/c3/MANAGEMENT_API.md", branch: "agent/c3-data-prod", commit: "e606465", verifiedAgainstBackend: false } as const;

export type BindingMode = "LIVE" | "TEST";
export const BINDING_MODES: readonly BindingMode[] = ["TEST", "LIVE"];

/** `postgres` and `rest` are AVAILABLE; `mysql csv graphql google_sheets odoo salesforce` are PLANNED (POST → 501 NOT_IMPLEMENTED). Build forms from the catalogue, not from this. */
export type DataSourceStatus = "ACTIVE" | "DISABLED";

/** every value is a string (the server writes numbers and booleans as text) */
export type DataSourceView = {
  id: string; workspaceId: string | null; name: string; type: string; config: Record<string, string>;
  hasCredential: boolean; status: DataSourceStatus; version: number; createdBy: string | null; createdAt: string; updatedAt: string;
};
export type DataSourceList = { items: DataSourceView[] };

/** the ONLY credential answer: key NAMES, never values, never the reference to the secret */
export type CredentialMetadata = { configured: boolean; type: string; keys: string[]; updatedAt: string | null; updatedBy: string | null };

/** `credential` and `config` accept only what the connector catalogue lists; unknown top-level keys are refused (400 INVALID_PARAMS). */
export type CreateDataSourceRequest = { name: string; type: string; config?: Record<string, string | number | boolean>; credential?: Record<string, string> };
export type UpdateDataSourceRequest = { name?: string; config?: Record<string, string | number | boolean>; status?: DataSourceStatus };
export type SetCredentialRequest = { credential: Record<string, string> };

export type ConnectionTestOk = { ok: true; latencyMs: number; warnings: string[] };
export type ConnectionTestFailed = { ok: false; code: string; message: string };
export type ConnectionTestResult = ConnectionTestOk | ConnectionTestFailed;
/** codes of a failed test (`ok:false`), MANAGEMENT_API.md §4 */
export const TEST_FAILURE_CODES = [
  "AUTH_REJECTED", "CONNECT_FAILED", "HOST_UNRESOLVED", "ADDRESS_BLOCKED", "TLS_FAILED", "TIMEOUT", "ROLE_TOO_PRIVILEGED", "INVALID_CREDENTIAL", "NOT_IMPLEMENTED", "INTERNAL",
] as const;

export type DataBinding = { mode: BindingMode; slotId: string; dataSourceId: string; updatedAt: string | null };
export type DataBindingList = { items: DataBinding[] };
export type BindDataSourceRequest = { dataSourceId: string };

export type ConnectorDescriptor = {
  type: string; displayName: string; status: "AVAILABLE" | "PLANNED"; capabilities: string[];
  configKeys: { name: string; required: boolean; description: string }[]; credentialKeys: string[]; notes: string;
};
export type ConnectorList = { items: ConnectorDescriptor[] };

/** request rules (ManagementRequests / DataSourceAdminService): the server is the authority, these only save a round trip */
export const DATA_SOURCE_NAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9 ._-]{0,79}$/;
export const SLOT_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
export const MAX_CONFIG_KEYS = 30;
export const MAX_CREDENTIAL_KEYS = 8;
/** body keys the server refuses (400 INVALID_PARAMS): identity and secret references come from the server, never from the client */
export const FORBIDDEN_BODY_KEYS = ["tenantId", "workspaceId", "credentialRef", "id", "createdBy"] as const;

/** error codes the management routes answer (MANAGEMENT_API.md §4, plus codes the controller maps that the table omits — see HANDOFF_C3 H-C3-02) */
export const MANAGEMENT_ERROR = {
  INVALID_PARAMS: "INVALID_PARAMS", INVALID_CONFIG: "INVALID_CONFIG", INVALID_CREDENTIAL: "INVALID_CREDENTIAL", PERMISSION_DENIED: "PERMISSION_DENIED", NOT_FOUND: "NOT_FOUND",
  CONFLICT: "CONFLICT", DISABLED: "DISABLED", UNSUPPORTED_TYPE: "UNSUPPORTED_TYPE", RATE_LIMITED: "RATE_LIMITED", NOT_IMPLEMENTED: "NOT_IMPLEMENTED", INTERNAL: "INTERNAL",
  SECRETS_UNAVAILABLE: "SECRETS_UNAVAILABLE",
} as const;
