# C5 — Phase 3 preparation audit (web apps vs the frozen C1–C4 contracts)

Branch `agent/c5-web`. Audited read-only against `integration/v2` @ `4884be3` (C1–C4 imported; contract docs last changed in `8b944cc`). Nothing on `integration/v2` or in C0–C4 code was touched.
Date 2026-10-06. Verification here ran in a Linux container: **not** on the Mac, **not** against a running backend. Nothing in this file claims a backend E2E result.

## 1. Verdict per focus area

| Area | State | Evidence / what is missing |
|---|---|---|
| Tenant / workspace / project context | **OK, one gap** | The client never sends a tenant id: no request type, header or query in `packages/api-client` carries one (grep clean; the only hit is the read-only label of the `CONTEXT` input source `TENANT_ID`, which the server derives). Tenant comes from the workspace in the URL (`tenant-permission.md` §2). Gap M-07: `Me.tenants[]` / `workspaces[].tenantId` are now typed but no screen shows the tenant name. |
| Canonical permissions | **Fixed (M-01, M-02)** | `Me.permissions` + `workspaces[].permissions` are typed; `ApiProject.permissions` still carries storage names (`PROJECT_EDIT`…) by backend design (`PermissionCodes` comment): `canonicalPermissions()` maps them and drops anything unknown. `APP_SHARE` is temporarily `PROJECT_MEMBERS` on the server (T15), so `canShare` can be true while SHARING stays NOT_READY (correct). |
| AppDefinition V2 | **OK** | All 18 enums of the mirror equal the owners' Kotlin enums on `integration/v2` (checked by hand: ParamType, QueryMode, FieldType, Cardinality, MappingErrorPolicy, ValueFormat, ActionType, EventType, InputSourceKind, ContextKeyName, IdempotencyPolicy, NotifyChannel, WorkflowTriggerType, StepKind, PermissionResourceType, PublishMode (SERVER_APP deliberately not a draft value), PublishVisibility, ThemeFont). The 12 VALID C2 conformance fixtures pass the client validator and every INVALID fixture the client is responsible for reports the manifest paths (new test `tests/builder/conformance.test.ts`, runs only when `XWEB_CONFORMANCE_DIR` points at C2's `app-definition/` directory; skipped otherwise). Two client checks added: action type outside the 9 canonical (aliases `RUN_QUERY`, `WRITE_DATA`…) and mapping field carrying both `transform` and `transforms`. Unknown fields stay a server-side 422 by design. |
| Data query / mutation contracts | **Not wired — by the backend, not by C5** | There is no HTTP route for query, mutation, discovery, preview or cache on `integration/v2`: `/api/v1/data/**` is only a proposal (`agents/C3_WIRING_PROPOSAL.md`, B-C3-11, OPEN) and `app.data-platform.enabled` is OFF and unread. The Builder keeps DATA_SOURCES / SCHEMA_DISCOVERY / QUERY_PREVIEW as `NOT_READY(reason)`. No fake data. |
| Action / workflow invocation | **Not wired — by the backend** | C4 ports exist but no controller (B-C4-09 OPEN; proposal uses `/api/apps/{appId}/…`, not `/api/v1`; wiring W-01…W-07 in B-C0-C4-02). ACTION_RUNTIME / WORKFLOW_RUNTIME / TEST_MODE stay `NOT_READY`. Test-mode answer shape is already handled: C4 `ActionResult.WouldRun{level,reason}` is shown as "would run" at every level, never as success (M-10). |
| Publish / share | **Publish OK, publish-config + share open** | Publish (`POST …/publish`, `…/site`, `…/site/rollback`, deployments, domains, form-submissions) are existing endpoints the client already uses (all 102 distinct client calls found in the backend controllers). `GET/PUT …/publish-config`, `…/publish-config/adopt-draft`, `…/publish-config/link/rotate` exist behind `app.publish-configs.enabled` (OFF) and have **no client code yet** (M-06). Sharing (APP_SHARE) has no endpoint: NOT_READY. |
| Error handling | **Fixed (M-03)** | See §3. |
| Loading / error / empty states | **OK** | `useLoad` + `StateView`/`ErrorState` on every list screen; Builder panels have explicit LOADING / ERROR / NOT_READY(reason) states (`core/readiness.ts`); probe `GET /component-metadata` 404/501 → NOT_READY, so the flag-OFF backend degrades visibly. Verified in the browser harness (64/64) and SSR tests, not against the real backend. |
| Auth / session expiry | **OK, one fix** | `call()` sends 401 (outside `/auth/*`) to one handler → `/auth/session-expired?next=…` keeping the current path; CSRF is fetched lazily and retried once on `CSRF_INVALID`. `stream()` (AI SSE) did not use the handler: fixed. Per-origin cookies mean a session on one portal does not exist on another (B-C0-WEB-01) — to be observed in real E2E, not assumed. |
| Tenant isolation | **OK on the client; to be proven on the server** | The UI cannot choose a tenant, hides nothing as security, and treats 403/404 uniformly. A cross-tenant 404 (data-runtime §4b "404 also for another tenant's resource") renders as "not available", not as a data error. Isolation itself is a server property: planned in E2E (E-12, E-13). |

## 2. Contract mismatches and how each is resolved

| # | Mismatch | Where | Status |
|---|---|---|---|
| M-01 | `Me` / `WorkspaceSummary` lacked `tenantId, tenantRole, platformScope, businessAccess, tenants[], permissions[]` and `workspaces[].tenantId/permissions[]` that `/auth/me` returns (`MeResponse`, `MeTenancy`) | `packages/types/src/index.ts` | **FIXED** (all new fields optional: an older backend and the legacy mock still work) |
| M-02 | Portal gate followed `systemAdmin` only; and a platform-only SYSTEM_ADMIN (role `ADMIN` rows, `businessAccess=false`) was offered Studio because any workspace row counted | `packages/permissions` `capabilitiesOf`, `isAdmin`, `hasWorkspace`; `StudioApp`, `AuthPages` (two `me.systemAdmin` reads) | **FIXED**: `platform.operate` = `platformScope ?? systemAdmin`; `studio.build` = some workspace holds a non-tenant-level code; new `tenant.members` (TENANT_MEMBERS). 6 tests. |
| M-03 | Error classes: generic `409 → "app changed elsewhere"` mislabelled `IDEMPOTENCY_IN_PROGRESS` / `IDEMPOTENCY_CONFLICT`; 429, `MUTATION_UNSUPPORTED/READ_ONLY_VIOLATION/INVALID_MAPPING/MAPPING_FAILED/UNSUPPORTED_TYPE` and a 500/502/504 on the first write attempt were generic errors; C4 codes (`ACTION_IN_PROGRESS`, `IDEMPOTENCY_KEY_*`, `TENANT_DISABLED`, `DEPENDENCY_UNAVAILABLE`, `INVALID_INPUT`) were unknown | `features/studio/builder/core/errors.ts`, `testMode.ts`, `TestPanel.tsx`, contract mirror `API_ERROR` | **FIXED** per `data-runtime.md` §4b (frozen 2026-10-06): `explainError(e, {write})`; a failed **write** with 500/502/504/network = "outcome unknown", not retry-safe (reads keep the plain error) |
| M-04 | Contract mirror header said `@ c59604b`; `docs/contracts/v2` changed in `8b944cc` (§4b, `ActionDef.trigger` optional, mapping `transforms[]` canonical, 14-code list re-confirmed, fixtures moved to C2's directory) | `packages/types/src/contract/v2/*` | **FIXED**: `CONTRACT_SOURCE.commit = 8b944cc`, `firstMirroredAt = c59604b`, `verifiedAgainst = 4884be3`; header text updated; test pins all three |
| M-05 | ADR 0022 §5 says Admin = TENANT_ADMIN (or scoped manager), but **every** `/api/v1/admin/**` route except `/admin/tenants/{id}/members*` is guarded by `AdminGuard` (system admin; T1 audit 96/96). Opening the Admin portal to a TENANT_ADMIN today gives 403 on every screen | `capabilitiesOf` (`tenant.administer`) | **OPEN — decision needed from C0/C1** (Q-1). Interim, safe: `tenant.administer` = platform scope only. Switch is one line once a tenant-scoped admin API and a screen exist. `/admin/tenants` (GET/POST/PATCH status, members PUT/DELETE) has **no UI** (remaining C5 work R-1). |
| M-06 | `GET/PUT /publish-config`, `/adopt-draft`, `/link/rotate` have no client code; the Builder reads only the draft `publishConfig` of the AppDefinition for preflight | `packages/api-client`, Publish dialog | **OPEN — remaining C5 work R-2**, gated by the same readiness probe (route absent while the flag is OFF → 404 → NOT_READY) |
| M-07 | `Me.tenants[]` unused; no tenant name in the shell, no multi-tenant grouping of workspaces | shells | **OPEN, low** (R-6) |
| M-08 | Data/Action/Workflow/Test HTTP surface is only a proposal and the two proposals disagree on prefix and identity: C3 `/api/v1/data/**` with body `{dataSourceId, operation, mappingRef, …}` (runtime ids, `MUTATION_EXECUTE`/`SCHEMA_DISCOVER`… operation names) vs C4 `/api/apps/{appId}/…`; the AppDefinition uses **local ids** and no operation creates a DataSource. C5 cannot know how a local `dataSourceRef` becomes a runtime id | — | **OPEN — decision needed from C0 (Q-2)**; C5 codes against nothing until it is frozen |
| M-09 | Root `.env.example` documents `WEB_ORIGIN_{PLATFORM,ADMIN,STUDIO}` as "not read for CORS until the C5 import"; `application.yml` keys are `app.web.origins.*` | C0-gated | C0's step in checklist 17b (see manifest §4) |
| M-10 | Test-mode answer: C4 returns `ActionResult.WouldRun{actionId,type,level,plan,output?,reason?}` (B-C4-09), while `outcomeFromServer` only knew `status`/`outcome` strings and would have shown it as "unrecognised" | `core/testMode.ts` | **FIXED**: `level` NOT_EXECUTED / VALIDATED / SANDBOX → state `WOULD_RUN` with the server's `reason`; unknown level → NOT_READY. Never SUCCESS. |

Open questions (for C0; C5 does not decide them):

- **Q-1** Should the Admin portal open to `TENANT_ADMIN` now (needs C1 tenant-scoped admin endpoints for members/settings, and a C5 screen), or stay platform-only until then? ADR 0022 §5 and the current code disagree; interim = platform-only.
- **Q-2** What is the browser-facing contract for running a query / mutation / action / workflow / test run: path prefix, how a **local** ref (`queryRef`, `actionRef`) is resolved to the server's resources (project id? workspace id?), request/response bodies, and the SSE route? Until frozen, the Builder Test panel and the published runtime stay NOT_READY.

## 3. Error handling table (what the user sees; `features/studio/builder/core/errors.ts`)

| Server answer | UI class | Retry offered |
|---|---|---|
| 409 `IDEMPOTENCY_OUTCOME_UNKNOWN` (also `ActionErrorCodes` with the same code) | unknown-outcome: "check the data first, do not press again" | **no** |
| 500/502/504/network on a **write** (first attempt) | unknown-outcome (same text) | **no** |
| 422 `MUTATION_REJECTED` | rejected: nothing applied, fix the input | yes |
| 409 `IDEMPOTENCY_IN_PROGRESS` / `ACTION_IN_PROGRESS` | in-progress: wait, then check | yes (after a wait) |
| 409 `IDEMPOTENCY_CONFLICT` / `IDEMPOTENCY_KEY_REUSED|REQUIRED|INVALID` | key-conflict: app bug, reload | no |
| 422 `MUTATION_UNSUPPORTED`, `READ_ONLY_VIOLATION`, `INVALID_MAPPING`, `MAPPING_FAILED`, `UNSUPPORTED_TYPE` | not-executable: nothing applied, fix the definition | no |
| 429 `RATE_LIMITED` | rate-limited, backoff (`Retry-After` is appended by `call()`) | yes |
| 409 `REVISION_CONFLICT` | conflict: reloaded the latest, redo | yes |
| 422 `SCHEMA_INVALID` | invalid, `details.violations` listed | yes |
| 403 `TENANT_SUSPENDED` / `TENANT_DISABLED` | suspended | no |
| 401 | session-expired page (keeps `next`) | sign in again |
| 403 | forbidden ("checked on the server") | no |
| 404 / 501 | "feature not available" (NOT_READY) | no |
| 503 `DEPENDENCY_UNAVAILABLE` / `AUDIT_UNAVAILABLE` | unavailable; not retry-safe for a write | read: yes |

## 4. Remaining C5 work (not started, depends on backend or a C0 decision)

| # | Work | Depends on |
|---|---|---|
| R-1 | Tenant admin screens (`/admin/tenants`, members PUT/DELETE) in `apps/admin`; gate opens per Q-1 | Q-1, C1 |
| R-2 | `api.getPublishConfig/putPublishConfig/adoptDraft/rotateLink` + publish-policy panel (revision CAS, `publicDataApproved`, link token shown once) | `app.publish-configs.enabled` ON in the test stack |
| R-3 | Data source / discovery / query preview panels become AVAILABLE | Q-2 + wiring B-C3-11, `app.data-platform.enabled` |
| R-4 | Test panel `runAction/runWorkflow` real calls, run history, approvals inbox (`/api/approvals/**` proposal) | Q-2 + B-C4-09, `app.workflow.enabled` |
| R-5 | Share dialog (APP_SHARE) | backend sharing endpoint (T15) |
| R-6 | Tenant name / switcher from `Me.tenants[]` | UX call |
| R-7 | Real-backend browser E2E (`PHASE3_E2E_PLAN.md`) | a running backend; none of it has run |
| R-8 | Re-run all Mac gates (npm ci, three builds, portals spec) on macOS | Mac shell |

## 5. What was run for this audit (all lightweight, cloud container)

`npm run typecheck:all` → 0 · `npm run test:unit` → 113 tests / 112 pass / 1 skipped (conformance, no directory) · with `XWEB_CONFORMANCE_DIR` → 130 pass, 0 skipped · browser **harness** spec 64/64 (not a backend E2E) · `npm run build:apps` (platform, admin, studio) → OK. Not run: the portals spec, any real-backend flow, anything on macOS.
