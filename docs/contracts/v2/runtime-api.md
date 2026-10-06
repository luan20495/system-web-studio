# Runtime API v2 — browser-facing data, action and workflow routes (FROZEN by D-C0-17…20)

Status: **frozen 2026-10-06 (C0)**. Owner: C0 (wiring). Consumers: C5 (Phase 3 backend-connected E2E). Producers: C3 `DataGateway`, C4 `ActionRuntime` / `WorkflowRuntime`.
Anything not listed here does not exist; a new route needs a new DECISIONS entry first. This file never overrides `data-runtime.md` (§4b mutation semantics),
`action-workflow.md`, `tenant-permission.md` or `integration-contract.md`; it only fixes the HTTP surface and the wiring between them.

## 0. Audit result (why these routes)

| Question | Finding |
|---|---|
| Do canonical browser routes already exist for data/action/workflow? | **No.** `logic/**` and `data/**` ship framework-free handlers only; no controller exists. |
| C3 `DataRoutes` (`/api/v1/data/query`, `/mutate`, `/sources/**`, …) | A *data-platform administration* family keyed by runtime `dataSourceId` UUIDs, tenant-wide, with a browser-callable `/mutate`. **Not mounted.** An app only knows LOCAL ids (`AppDefinition` stores no runtime ids) and a browser-callable mutate bypasses action permissions, idempotency derivation and audit. Reserved for a later tenant-admin contract (Q-1). |
| C4 proposal B-C4-09 (`/api/apps/{appId}/events`, …) | Not workspace/project-scoped like every other route of this backend. Replaced by the project-scoped family below. Event fan-out (`dispatch`), approvals inbox and schedules CRUD are **not** in this phase. |
| C5 D-C5-02 | Asked for `/api/v1/workspaces/{workspaceId}/projects/{projectId}/…`, tenantId never sent by the client. **Accepted**, with the segment `app-runtime`. |
| Admin portal (Q-1) | Stays platform-only. `/api/v1/admin/**` is untouched. No accepted contract gives tenant admins admin routes, so none are added. |

## 1. Common rules

* Base path `B = /api/v1/workspaces/{workspaceId}/projects/{projectId}/app-runtime`. Only these segments exist below `B`: `queries`, `actions`, `workflows`, `workflow-runs`.
* Authentication: the normal session (cookie) + CSRF (`X-XSRF-TOKEN`) as for every other `POST`. `SecurityConfiguration` is **unchanged**: the routes fall under `anyRequest().authenticated()`; there is no `permitAll`.
* **tenantId is never read from the request.** The server derives it from the workspace (`AccessService.forProject` → `AccessContext.tenantId`). Any unknown body field (`tenantId`, `userId`, `dataSourceId`, `sql`, `url`, …) is rejected `400 INVALID_REQUEST` (strict parsing).
* Workspace/project ownership is checked by `AccessService.forProject` (404 `PROJECT_NOT_FOUND` for a non-member, a project of another workspace or of another tenant — existence is never disclosed).
* The caller is always `ActorKind.USER`. The wiring converts `logic.action.ActorKind` ⇄ `tenancy.ActorKind` by name (D-C0-14); C1 policy denies every non-USER actor (TEMPORARY V2 POLICY).
* `mode`: `LIVE` (default; the published version = the project's latest `RUNNING` deployment) or `TEST` (the working draft; needs `APP_EDIT`; no side effect; C4 answers `WouldRun`).
* Default deny: an unknown permission code, mode, actor kind or missing wiring answers with an error, never with data.
* Error body for every non-2xx response that is not an action result envelope: `{"code","message","requestId","retryable","details"}` (`ApiError` plus `retryable`, always `false` unless C4/C3 say otherwise). Errors raised by `AccessService` (404 `PROJECT_NOT_FOUND`, 403 `FORBIDDEN`) use the standard `ApiError`.
* Feature flags (all **false** by default; a disabled flag means the controller bean does not exist → 404): `app.data-platform.enabled` → R1; `app.workflow.enabled` → R2, R3 and the C4 runtime beans (+ worker).
* No runtime ids are persisted or returned: the derived `dataSourceId`/`appVersionId` UUIDs exist only inside one call.

## 2. R1 — run a data query

`POST {B}/queries/{queryId}/run`   (flag `app.data-platform.enabled`)

Request: `{"mode":"LIVE|TEST" (opt), "params":{…} (opt, ≤ 40 plain values — the gateway limit), "page":{"limit":1..10000,"offset":0..1000000} (opt), "mappingRef":"<local mapping id>" (opt)}`.
`queryId` is the LOCAL id of a `queries[]` entry with `mode: READ`. `mappingRef`, when absent, is the single mapping whose `queryRef == queryId`; zero or several → `422 MAPPING_REF_REQUIRED`.

Response `200`: `{"queryId","mode","cache":"HIT|MISS|BYPASS","result":{<ViewModelData: viewModelId, cardinality, fields, rows, truncated, warnings, skippedRows>}}`.

Pipeline: `forProject` → mode gate → `APP_USE` (LIVE) / `APP_EDIT` (TEST) + `QUERY_EXECUTE` → load AppDefinition (tenant-checked) → `AppDataBindingResolver.query` → `DataGateway.runQuery` (which authorizes `QUERY_EXECUTE` again through C1 `GatewayAuthorizer`).
Failures: `400 INVALID_REQUEST`, `403 FORBIDDEN`, `404 PROJECT_NOT_FOUND | QUERY_NOT_FOUND`, `422 MAPPING_REF_REQUIRED | WRONG_MODE | DATA_SOURCE_UNBOUND | INVALID_PARAMS`, `429 RATE_LIMITED`, `502/504` for source failures (C3 safe messages), `503 DATA_RUNTIME_UNAVAILABLE` (no `DataGateway` bean — D-C0-20).

## 3. R2 — execute an action

`POST {B}/actions/{actionId}/execute`   (flag `app.workflow.enabled`)

Request: `{"mode":"LIVE|TEST" (opt), "inputs":{…} (opt), "idempotencyKey":"^[A-Za-z0-9._:-]{1,128}$" (opt; required by C4 for mutating LIVE types), "trigger":{"eventName":"<section>.<event>"} (opt)}`.
The trigger kind is always `UI_EVENT`; `eventId` is not accepted. `context`-mapped inputs (user id …) cannot be supplied by the client (C4 rule).

Response envelope (HTTP status in the table):
```
{"status":"OK","actionId","mode","output":{…},"followUps":[{"actionId","on":"SUCCESS|ERROR","status":"OK|FAILED|WOULD_RUN", …}]}
{"status":"FAILED","actionId","mode","error":{"code","message","retryable","details":{…}},"followUps":[…]}
{"status":"WOULD_RUN","actionId","mode":"TEST","type","level":"NOT_EXECUTED|VALIDATED|SANDBOX","output":{…},"followUps":[]}
```

| Result | HTTP |
|---|---|
| `OK`, `WOULD_RUN` | 200 |
| `INVALID_INPUT`, `IDEMPOTENCY_KEY_INVALID`, `IDEMPOTENCY_KEY_REQUIRED` | 400 |
| `FORBIDDEN`, `TENANT_DISABLED` | 403 |
| `UNKNOWN_ACTION` | 404 |
| `IDEMPOTENCY_OUTCOME_UNKNOWN`, `ACTION_IN_PROGRESS`, `IDEMPOTENCY_KEY_REUSED`, `IDEMPOTENCY_IN_PROGRESS`, `IDEMPOTENCY_CONFLICT` | 409 |
| `MUTATION_REJECTED`, `INVALID_DEFINITION`, `LIMIT_EXCEEDED`, `UNSUPPORTED_ACTION_TYPE` | 422 |
| `RATE_LIMITED` | 429 + `Retry-After` |
| `NOT_IMPLEMENTED` | 501 |
| `DEPENDENCY_UNAVAILABLE`, `AUDIT_UNAVAILABLE`, `DATA_RUNTIME_UNAVAILABLE`, `RUNTIME_STORES_VOLATILE` | 503 |
| `TIMEOUT` | 504 |
| anything else | 500 |

`retryable` is copied from C4 (`ActionResult.Failed.retryable`) and is **never true** for `IDEMPOTENCY_OUTCOME_UNKNOWN` and `MUTATION_REJECTED`. A UI must not run its own `onError` handling after `IDEMPOTENCY_OUTCOME_UNKNOWN` (D-C0-13).
There is **no other write route**: a data mutation is an action of type `SUBMIT_FORM | CREATE_RECORD | UPDATE_RECORD | DELETE_RECORD | CALL_API` (D-C0-18).

## 4. R3 — workflows

* `POST {B}/workflows/{workflowId}/runs` → `202` + run view. Body `{"mode":"LIVE|TEST" (opt), "input":{…} (opt), "idempotencyKey":"…" (required)}`.
* `GET {B}/workflow-runs/{runId}` → `200` run view (creator, or `WORKFLOW_MANAGE`; anything else `404 WORKFLOW_RUN_NOT_FOUND`).
* `POST {B}/workflow-runs/{runId}/cancel` → `200` run view (idempotent).

Run view: `{"runId","workflowId","mode","status","currentStepId","steps":[{"stepId","status","attempt","output","errorCode","errorMessage","startedAt","finishedAt","simulated","dryRunLevel"}],"errorCode","errorMessage","compensation","createdAt","updatedAt","finishedAt"}` (no `appId`, no creator id, no approval id, no definition snapshot).
Failures use `WorkflowResult.Failed` codes mapped like §3 (`UNKNOWN_WORKFLOW` 404, `WORKFLOW_RUN_NOT_FOUND` 404, `RATE_LIMITED` 429, `IDEMPOTENCY_KEY_*` 400/409, …).
A workflow step that ends `IDEMPOTENCY_OUTCOME_UNKNOWN` fails the run with that code, is not routed to `onError` and is not compensated (D-C4-17).

## 5. Permission mapping (canonical codes, C1 `PermissionCodes`)

| Route | Controller gate | Enforced again by |
|---|---|---|
| R1 LIVE | `forProject`, `APP_USE`, `QUERY_EXECUTE` | C3 `DataGateway` → C1 `GatewayAuthorizer(QUERY_EXECUTE)` |
| R1 TEST | `APP_EDIT` instead of `APP_USE` | same |
| R2 | `forProject` (+ `APP_EDIT` for TEST) | C4 pipeline step 4 → C1 `AccessPort`: `APP_USE`, `ACTION_EXECUTE`, `DATA_MUTATE` for data types, the action's declared permission, `WORKFLOW_EXECUTE` for `START_WORKFLOW`; mutations: C3 `GatewayAuthorizer(MUTATION_EXECUTE)` |
| R3 start | `forProject` (+ `APP_EDIT` for TEST) | C4 `WorkflowEngine.start`: `APP_USE` + `WORKFLOW_EXECUTE` |
| R3 status/cancel | `forProject` | creator or `WORKFLOW_MANAGE` |

## 6. C3 → C4 write error mapping (`ActionDataPortAdapter`, D-C0-15)

`IDEMPOTENCY_OUTCOME_UNKNOWN` → same, non-retryable · `MUTATION_REJECTED` → same, non-retryable · `IDEMPOTENCY_IN_PROGRESS`, `RATE_LIMITED` → same, retryable · `IDEMPOTENCY_CONFLICT` and every code in `DataGateway.NOT_EXECUTED` → same, non-retryable · every other `ConnectorFailure` code and every non-`ConnectorFailure` throwable on a WRITE (timeout, connect failure, 5xx, unclassified) → `IDEMPOTENCY_OUTCOME_UNKNOWN`, non-retryable. Resolution errors (`AppResolutionException`) and "no gateway" happen before anything is sent → definite, non-retryable.

## 7. Deliberately not here

Event fan-out (`dispatch`), approvals inbox/decision, schedules CRUD, AI data catalog, data webhook ingest, tenant-admin data-source management, SSE events, a database-backed C3 catalog or C4 stores. Each needs its own accepted contract; the persistence ones need a reviewed migration (V28 stays unallocated).
