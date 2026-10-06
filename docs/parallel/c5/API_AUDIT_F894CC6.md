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

`B-C0-W-01` V29 run stores not integrated (LIVE mutating actions / workflow starts → 503 `RUNTIME_STORES_VOLATILE`) · `B-C0-W-03` no management API for data sources, credentials, queries, bindings · `B-C0-W-04` production connectors read-only (`MUTATION_UNSUPPORTED`) · `B-C4-05/06` C4 queue and run stores in memory, no RabbitMQ dependency · `B-C5-06` the published app has no data runtime host · `B-C5-09`/Q-1 Admin stays platform-only · `B-C0-WEB-01` one OIDC redirect URI. Handoffs: `HANDOFF_C0.md` … `HANDOFF_C4.md`.

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
