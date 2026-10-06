# C5 — Frontend API layers vs the baseline backend (`integration/v2 @ f894cc6`)

Method: read `docs/contracts/v2/runtime-api.md` (frozen) and the f894cc6 controllers `AppRuntimeDataController`, `AppRuntimeActionController`, `RuntimeRequests`, the V28 data-runtime schema and the OPEN rows of `docs/parallel/BLOCKERS.md`; compared with `packages/api-client`, `packages/types/src/contract/v2`, `features/studio/builder/core/{errors,testMode,readiness}.ts` and the Test panel. Static reading only — **no backend was run** (no JDK/Docker in the C5 sandbox). C5 changed no backend file and no `docs/contracts/**` file.

## 1. Mismatches found

| # | Owner | Frontend expectation | Actual (f894cc6) | Impact | Fix | Status |
|---|---|---|---|---|---|---|
| M1 | C5 | read the error code from the top-level error body | action failures arrive as `{status:"FAILED", error:{code,message,retryable,details}}` | every action failure surfaced as `HTTP_409` → wrong message, wrong retry advice | `normaliseError()` in `core.ts` accepts both bodies | **fixed** (unit-tested) |
| M2 | C5 | outcome from `SUCCESS`/legacy only | success status is `OK`; TEST answers `WOULD_RUN` | a real result would render as unknown | `outcomeFromServer` handles `OK`, `FAILED`, `WOULD_RUN` | **fixed** |
| M3 | C5 | retry decided client-side | server sends `retryable` | unsafe retry of non-retryable failures | `ApiError.retryable`, used by `errors.ts` (default `retrySafe = retryable !== false`) | **fixed** |
| M4 | C5 | a timeout is a network error | needs a distinct state (the request may have reached the server) | double submit risk | code `TIMEOUT` (status 0), kind `timeout`; unknown outcome locks the item until the user confirms | **fixed** |
| M5 | C5 / C0 | 404 = not found | 404 **without** a domain code = the controller is not mounted (flag off); 404 **with** a code (`QUERY_NOT_FOUND`, `UNKNOWN_ACTION`…) = missing id | wrong "not found" vs "feature off" message | `runtimeReadinessFromError`; discoverability gap handed to C0 (`HANDOFF_C0.md` H-C0-04) | **fixed in UI**; contract gap open |
| M6 | C0 (doc) | contract text says `APP_EDIT` for TEST mode | controller checks `PROJECT_EDIT` (same permission, mapped in `PermissionCodes`) | none at runtime; doc naming divergence | none in C5 | open (H-C0-03) |
| M7 | C5 | Studio calls the runtime | the Studio had **no** `app-runtime` client | Test mode was static NOT_READY | `api.appRuntime.{runQuery,executeAction,startWorkflow,workflowRun,cancelWorkflowRun}`; bodies carry only contract fields (no tenant/user/dataSource ids); `idempotencyKey` in the body, validated against `^[A-Za-z0-9._:-]{1,128}$` before any request | **fixed** |
| M8 | — | permission names | `ProjectResponse.permissions` returns legacy `PROJECT_*` names | not a mismatch: `LEGACY_PERMISSION_ALIAS` in `core/permissions.ts` maps them | — | n/a |
| M9 | C1 (info) | TEST/LIVE gating | EDITOR has QUERY_EXECUTE and ACTION_EXECUTE; WORKFLOW_EXECUTE, DATA_MUTATE, DATA_SOURCE_MANAGE need WORKSPACE_ADMIN; VIEWER has no QUERY_EXECUTE | the Test panel disables runs with a reason per permission | `testModeReadiness` / panel `noEdit` | handled |

## 2. Backend facts that cap what the UI can do

`B-C0-W-01` V29 run stores not integrated (LIVE mutating actions / workflow starts → 503 `RUNTIME_STORES_VOLATILE`) · `B-C0-W-03` no management API for data sources, credentials, queries, bindings **on `f894cc6`** (route code now exists on C3's branch — see §4 — but it is neither compiled nor tested by C3 and not integrated) · `B-C0-W-04` production connectors read-only (`MUTATION_UNSUPPORTED`) · `B-C4-05/06` C4 queue and run stores in memory, no RabbitMQ dependency · `B-C5-06` the published app has no data runtime host · `B-C5-09`/Q-1 Admin stays platform-only · `B-C0-WEB-01` one OIDC redirect URI. Handoffs: `HANDOFF_C0.md` … `HANDOFF_C4.md`.

## 3. UX-state audit (spec item 6)

| State | Where | Result |
|---|---|---|
| loading / empty / error with retry | `useLoad`, project/workspace lists, Builder panels | existing, kept |
| validation | inspector `PropsForm`, `SCHEMA_INVALID` violations mapped to paths | existing, covered by `errors.test.ts` |
| permission denied | `permissions.ts`, read-only notice, disabled controls with `title` reason, Test panel `noEdit` | existing; Test panel added |
| timeout | api-client `TIMEOUT`; Test panel unknown-outcome lock + explicit unlock button | **added** |
| retry | save: top-bar "Thử lại" (`retry-save`), only after a retry-safe failure (status 0, ≥500, 429) — the failed ops are kept and re-sent once; other failures drop them | **added** |
| reconnect | workflow poll: 1.5 s interval, ≤120 s, manual "Tải lại trạng thái" after a poll failure; publish poll: backoff `800·(1+2n)` ms, "Đang thử lại…", after 5 failures a "Kiểm tra lại" button | **added** |
| save states / unsaved changes | top bar saved/saving/error; `beforeunload` guard while saving or failed; Publish and Test runs disabled while unsaved | **added** |
| duplicate-submit prevention | per-item pending locks in the Test panel; fresh idempotency key per click; workflow start requires a key; Publish carries `Idempotency-Key` | **added** |
| disabled buttons | all with a reason in `title` | existing + Test panel |
| slow network | pending/“Đang chạy…” states; poll limits above | **added** |
| workflow / publish progress | run id + server status line; publish dialog | **added** |
| public / private | publish dialog (PRIVATE only when policy forbids PUBLIC) | existing |
| responsive, refresh / direct URL, keyboard/a11y | harness spec (axe + keyboard + DnD); E2E-01/04 cover direct URL and refresh against a real backend | existing; real-backend part NOT RUN |
| no white screen / no unhandled rejection | error boundaries; E2E flows assert zero page errors | existing; real-backend part NOT RUN |

**Remaining (not fixed, with reason):** no real-backend evidence yet for any of the above (stack missing); the LIVE-mode UI does not exist in Studio on purpose (Test panel is TEST-only; LIVE needs a published app with a data host — `B-C5-06`); slow-network behaviour is only unit/SSR-checked.

## 4. Management API audit (C3, `agent/c3-data-prod @ e606465`) — added after the baseline audit

Method: read `docs/parallel/c3/MANAGEMENT_API.md` and `wiring/DataManagementControllers.kt` / `data/gateway/ManagementHttp.kt` from that commit (`git show`, read only). **Status stated by C3: Spring compile NOT verified, route test NOT verified, live backend NOT verified, production ready NO.** C5 could not run any of it either (no JDK in the sandbox). The client mirrors the document and the controller; nothing was added that neither contains. `MANAGEMENT_API_SOURCE.verifiedAgainstBackend` is `false` in `packages/types/src/contract/v2/management.ts`.

### 4.1 Contract as extracted (base `/api/v1/workspaces/{workspaceId}`, mounted only with `app.data-platform.enabled=true`, session cookie + `X-XSRF-TOKEN`)

| Route | Body | Answer | Frontend (`api.dataManagement.*`) |
|---|---|---|---|
| `GET /data-sources/connectors` | – | `{items: ConnectorDescriptor[]}` | `connectors` — the create form is built from this catalogue (AVAILABLE vs PLANNED), not hard-coded |
| `GET /data-sources` | – | `{items: DataSourceView[]}` | `list` |
| `POST /data-sources` | `{name,type,config?,credential?}` | 201 view | `create` (name checked locally first) |
| `GET/PATCH/DELETE /data-sources/{id}` | PATCH: any of `name`, `config` (replaces ALL), `status` | 200 / 200 / 204 (409 while bound) | `get`, `update` (empty change refused locally), `remove` |
| `GET /data-sources/{id}/credential` | – | `{configured,type,keys[],updatedAt,updatedBy}` — names only | `credential` |
| `PUT /data-sources/{id}/credential` | `{credential:{…}}` (1–8 text keys) | metadata (write-only body) | `setCredential` |
| `DELETE /data-sources/{id}/credential` | – | 204 | `removeCredential` |
| `POST /data-sources/{id}/test` | none | **always 200**: `{ok:true,latencyMs,warnings[]}` or `{ok:false,code,message}`; 409 `DISABLED`, 404, 403, 429 are real errors | `testConnection` (30 s signal) |
| `GET /projects/{p}/data-bindings` | – | `{items:[{mode,slotId,dataSourceId,updatedAt}]}` | `listBindings` |
| `PUT /projects/{p}/data-bindings/{mode}/{slotId}` | `{dataSourceId}` | 200 binding (mode LIVE/TEST, case-insensitive) | `bind` (mode upper-cased, slot checked against `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`) |
| `DELETE …/{mode}/{slotId}` | – | 204 (404 if none) | `unbind` |

Rules mirrored client-side only to save a round trip (the server stays the authority): name `^[A-Za-z0-9][A-Za-z0-9 ._-]{0,79}$`; config ≤ 30 keys; credential 1–8 text keys; bodies carry **only** contract fields (the server refuses `tenantId`, `workspaceId`, `credentialRef`, `id`, `createdBy` with 400 `INVALID_PARAMS`); ids are `encodeURIComponent`-encoded. Permissions: reads `DATA_SOURCE_VIEW`, changes `DATA_SOURCE_MANAGE` (WORKSPACE_ADMIN only today); bindings additionally need `PROJECT_EDIT`, also for the list. Foreign, unknown and other-tenant ids all answer the same 404 `NOT_FOUND` — the UI shows one text for all three. Admin changes: 60 per 60 s per tenant (429 `RATE_LIMITED`).

### 4.2 Error mapping (`core/errors.ts`, `core/dataManagement.ts`)

| Answer | UI kind | Retry |
|---|---|---|
| network / timeout on a **write** | `unknown-outcome` / `timeout` — "reload the list to check before retrying" | **never automatic**; list and bindings are re-read |
| 400 `INVALID_*`, `INVALID_CONFIG`, `INVALID_PARAMS`, 422, `UNSUPPORTED_TYPE` | `invalid` (field named when known) | after editing |
| 401, 403 `PERMISSION_DENIED` / `FORBIDDEN` | `forbidden` | no |
| 404 with a code | `not-found` (same text for foreign/unknown) | no |
| 404 / 501 **without** a code (reads) | NOT_READY "management not mounted (flag off)" | – |
| 409 `DISABLED` | `unavailable` ("source is switched off") | after enabling |
| 409 other | `conflict` (delete of a bound source says "unbind first") | no |
| 429 | `rate-limited` | after the wait |
| ≥ 500 on a **write** | `unknown-outcome`, `retrySafe=false` | reload first |
| ≥ 500 on a read | `error` | yes |
| `AUTH_REJECTED` (test result or error) | "the source rejected the credentials" | – |
| `MUTATION_REJECTED`, `IDEMPOTENCY_OUTCOME_UNKNOWN`, `READ_ONLY_VIOLATION`, `DISABLED` (runtime) | as before in `errors.ts`; `IDEMPOTENCY_OUTCOME_UNKNOWN` is `retryable=false`, no auto-retry, no success shown | – |

A failed connection test is **not** an error: it is a FAILED result (HTTP 200, `ok:false`) with its code (`AUTH_REJECTED`, `CONNECT_FAILED`, `HOST_UNRESOLVED`, `ADDRESS_BLOCKED`, `TLS_FAILED`, `TIMEOUT`, `ROLE_TOO_PRIVILEGED`, `INVALID_CREDENTIAL`, `NOT_IMPLEMENTED`, `INTERNAL`) and a plain-language reason; warnings turn an OK result amber and are shown next to it.

### 4.3 Documentation vs controller — observations (none silently normalised; sent to C3 as H-C3-02/03)

| # | Observation | Effect on the frontend |
|---|---|---|
| O1 | the error table of the doc omits `INVALID_CREDENTIAL` (400) and `SECRETS_UNAVAILABLE` (`GatewayProblems.status` maps it to 500 by default) | `INVALID_*` is handled generically; `SECRETS_UNAVAILABLE` is shown as a server error with an unknown-outcome rule on writes |
| O2 | PATCH applies name/config first and status second — not atomic | after any PATCH error the list is re-read; the UI never assumes a partial result |
| O3 | the bindings list silently filters entries by per-source read permission | a user may see fewer bindings than exist; the UI says nothing about hidden ones (it cannot know) |
| O4 | the runtime routes answer `FORBIDDEN`, the management routes `PERMISSION_DENIED` | both map to `forbidden` |
| O5 | precedence between `dataSources[].sourceRef` in the document and the `data_source_bindings` table is unspecified | the UI shows the bindings table and the document's declared slots side by side and does not claim which wins |
| O6 | **no approved-query / mutation management endpoint** (doc §5: seeded through repositories) | the Studio can neither create nor edit a query on the server; E2E-07 needs an operator-seeded project |
| O7 | **AppDefinition V2 has no typed operation for `dataSources[]`** (slots are "granted, not created"; queries must reference a declared `dataSourceRef`, validated in `definition.ts`) | the Studio can bind an existing slot but cannot declare one; a project without slots cannot be bound — the UI says so (`no-slots`, `slot-empty`) instead of inventing a slot (C2 H-C2-02) |

### 4.4 Public runtime config (C2 proposal; frontend ready, host not decided)

A published app loads `GET /runtime-config.json` = `{DATA_API_BASE_URL, ENVIRONMENT?, RELEASE_ID?, VERSION?}` **before** it creates a Data API client (`packages/api-client/src/runtimeConfig.ts`). Fail-closed rules, all unit-tested (`tests/builder/runtimeconfig.test.ts`): `DATA_API_BASE_URL` required, absolute `https:` (plain `http:` only for a loopback host in development), no credentials/query/fragment; a loopback host is refused in production or when `ENVIRONMENT=production`; broken JSON, a missing URL or a 5xx **never** fall back; the only fallback is development mode with an explicit valid `devFallback` when the file is unreachable, times out or answers 404; the failure is rendered visibly (`renderRuntimeConfigFailure`, `textContent` only); `dataApiUrl` refuses anything that could change the host. No host is hard-coded anywhere. **Not verified:** the public publish host, who serves the file, CORS/cookie model between the published origin and the Data API (C0/C2/C3).

### 4.5 Test evidence for this section (all class-tagged; none is real-backend)

`tests/builder/management-client.test.ts` (mock: exact method/path/body, CSRF, no identity fields, credential metadata only, 404 shapes, network vs timeout) · `management.test.tsx` (unit: texts per failure code, warnings, credential view, form checks, error classes, unknown-outcome reload, bindings/slots, SSR of the panel and wizard) · `runtimeconfig.test.ts` (mock: the cases above) · `tests/browser/datasources.spec.mjs` (harness, 47 checks: states, locks, secret handling, TEST/LIVE binding, read-only, a11y names). `window.__secretsSeenInDom()` is false after every credential/create submit.
