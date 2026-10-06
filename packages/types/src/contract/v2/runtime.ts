/**
 * MIRROR of docs/contracts/v2/runtime-api.md (frozen by D-C0-17…20, on integration/v2 @ f894cc6) — manual.
 * Browser-facing data / action / workflow routes below `/api/v1/workspaces/{w}/projects/{p}/app-runtime`.
 * Anything that is not in that document does not exist; do not add a route here before a DECISIONS entry exists.
 * The client NEVER sends tenantId / userId / dataSourceId / sql / url: the server parses bodies strictly and answers 400 INVALID_REQUEST.
 */
export const RUNTIME_API_SOURCE = { doc: "docs/contracts/v2/runtime-api.md", commit: "f894cc6" } as const;

export type RuntimeMode = "LIVE" | "TEST";

/** `^[A-Za-z0-9._:-]{1,128}$` (runtime-api.md §3) */
export const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;

/** R1 request. Every field is optional; unknown fields are rejected by the server. */
export type RunQueryRequest = { mode?: RuntimeMode; params?: Record<string, unknown>; page?: { limit: number; offset: number }; mappingRef?: string };
export type ViewModelData = {
  viewModelId: string; cardinality: string; fields: unknown[]; rows: Record<string, unknown>[]; truncated: boolean; warnings: unknown[]; skippedRows: number;
};
export type RunQueryResponse = { queryId: string; mode: RuntimeMode; cache: "HIT" | "MISS" | "BYPASS"; result: ViewModelData };

/** R2 request. `trigger.eventName` is `<section>.<event>`; `eventId` is not accepted. */
export type ExecuteActionRequest = { mode?: RuntimeMode; inputs?: Record<string, unknown>; idempotencyKey?: string; trigger?: { eventName: string } };
export type ActionFollowUp = { actionId: string; on: "SUCCESS" | "ERROR"; status: "OK" | "FAILED" | "WOULD_RUN"; [k: string]: unknown };
export type ActionErrorBody = { code: string; message?: string; retryable?: boolean; details?: unknown };
export type ActionEnvelope =
  | { status: "OK"; actionId: string; mode: RuntimeMode; output?: unknown; followUps?: ActionFollowUp[] }
  | { status: "FAILED"; actionId: string; mode: RuntimeMode; error: ActionErrorBody; followUps?: ActionFollowUp[] }
  | { status: "WOULD_RUN"; actionId: string; mode: "TEST"; type?: string; level: "NOT_EXECUTED" | "VALIDATED" | "SANDBOX"; output?: unknown; followUps?: ActionFollowUp[] };

/** R3 */
export type StartWorkflowRequest = { mode?: RuntimeMode; input?: Record<string, unknown>; idempotencyKey: string };
export type WorkflowRunStep = {
  stepId: string; status: string; attempt?: number; output?: unknown; errorCode?: string | null; errorMessage?: string | null;
  startedAt?: string | null; finishedAt?: string | null; simulated?: boolean; dryRunLevel?: string | null;
};
export type WorkflowRunView = {
  runId: string; workflowId: string; mode: RuntimeMode; status: string; currentStepId?: string | null; steps: WorkflowRunStep[];
  errorCode?: string | null; errorMessage?: string | null; compensation?: unknown; createdAt?: string; updatedAt?: string; finishedAt?: string | null;
};
/** Run states after which polling stops. The contract does not list the status names; anything else is treated as "still running". */
export const WORKFLOW_TERMINAL_STATUSES = ["SUCCEEDED", "SUCCESS", "COMPLETED", "FAILED", "CANCELLED", "CANCELED", "COMPENSATED", "TIMED_OUT"] as const;

/** 404 codes that mean "this id does not exist" (as opposed to a 404 with no code = the route/flag is not mounted on this server). */
export const RUNTIME_NOT_FOUND_CODES = ["PROJECT_NOT_FOUND", "QUERY_NOT_FOUND", "UNKNOWN_ACTION", "UNKNOWN_WORKFLOW", "WORKFLOW_RUN_NOT_FOUND"] as const;
/** 422 codes of R1/R2 that say "the current definition cannot be executed" (nothing was sent to a data source). */
export const RUNTIME_NOT_EXECUTABLE_CODES = ["MAPPING_REF_REQUIRED", "WRONG_MODE", "DATA_SOURCE_UNBOUND", "UNSUPPORTED_ACTION_TYPE"] as const;
