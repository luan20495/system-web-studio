# C5 — Phase 3 browser E2E plan for the REAL backend

Status: **PLAN ONLY. No real-backend E2E has run. Nothing below is green, and nothing may be reported green until it has run against a real backend.**
Written 2026-10-06 against `integration/v2` @ `4884be3`. The three portals are not started by `scripts/run-local.sh` (it starts the legacy root app on :3100), so a new start path is needed first (§3).

## 1. Labels: what each existing test is (and is not)

Every test must carry one of these labels in its report line. "E2E" without a label is not allowed.

| Label | What runs | Backend? | Files | Counts as Phase 3 evidence? |
|---|---|---|---|---|
| `UNIT` | pure functions (ops, DnD planning, validators, permissions, errors) | none | `tests/builder/*.test.ts(x)` via `npm run test:unit` | no |
| `CONFORMANCE` | client validator vs C2's shared fixtures | none (reads fixture files) | `tests/builder/conformance.test.ts` (needs `XWEB_CONFORMANCE_DIR`; SKIPPED without it) | no — a contract check, not a flow |
| `SSR` | server-rendered component markup | none | `tests/builder/components.test.tsx` | no |
| `BROWSER-HARNESS` | real Chromium on the **test-only harness** (`tests/browser/harness.tsx` mounts `<BuilderWorkspace>`; the host records ops in `window.__ops`) | **none, fake host** | `tests/browser/builder.spec.mjs` | **no — NOT a backend E2E** |
| `PORTALS-NO-BACKEND` | the 3 real Next apps with no API behind them (redirects, headers/CSP, labels, login error with API down) | **none** | `tests/browser/portals.spec.mjs` | **no — NOT a backend E2E** |
| `LEGACY-REAL-BACKEND` | old **single-origin root app** (:3100, `/studio` `/admin`) against the full local stack; pre-V2 and pre-monorepo | yes, legacy stack | `e2e/{factory,public,admin-setup,code,runtime,sso}-flow.mjs`, `e2e/a11y.mjs` | no — different front end; a regression guard for the legacy app only (checklist S19). Not run by C5. |
| `LEGACY-MOCK` | mock static export (GitHub Pages), no backend | none | `e2e/pages-mock.mjs` | no |
| `PHASE3-REAL-BACKEND` | the three portals + real Spring backend + Postgres, real browser | **yes** | to be written under `tests/e2e-real/` (§5) | **yes — and the only kind that counts** |

The existing browser specs keep their labels and stay separate. They are never merged into a Phase 3 result and never run as a stand-in for it.

## 2. What must be true before the first real run (go / no-go)

1. Backend built and started from `integration/v2` after the Mac gates S1–S16 (not yet done: Gradle cannot run in the C5 container). **No-go until C0 reports S16 green.**
2. Flags for the flows that need them, all set explicitly in the test stack and recorded in the report: `app.publish-configs.enabled=true` (E-10), `app.data-platform.enabled=true` + wiring B-C3-11 (E-06, E-07), `app.workflow.enabled=true` + wiring B-C4-09 / B-C0-C4-02 (E-08, E-09). `app.tenancy.system-admin-business-access` stays **false** (a flow that signs in as `local.admin` and works inside a workspace needs a seed membership, not the flag — checklist S19).
3. CORS: `app.web.origins.{platform,admin,studio}` = `http://127.0.0.1:3001|3002|3003` exactly (the C5 import switches the default and `AuthSecurityTests`, `WEB_SECURITY_CONFIG.md` §3). The browser must use `127.0.0.1` consistently — `localhost` is a different origin and cookie jar.
4. Auth: **local login on all three portals; OIDC only on one** (one redirect URI, B-C0-WEB-01). OIDC E2E is out of scope until that is fixed.
5. Seed data: one tenant with ≥ 2 workspaces, users with distinct roles (admin / editor / publisher / viewer — `local.admin|editor|publisher|viewer` exist in the legacy seed), a **second tenant** with its own workspace and project (for isolation), one project with a published version, and a reachable test data source for E-06/E-07 (a local Postgres / REST stub — never production credentials; the secret is entered through the server-side secret field only).
6. The Q-1 and Q-2 decisions of `PHASE3_AUDIT.md` §2 are answered (they decide E-06…E-09 and the Admin flow).

## 3. How the stack starts (needs C0: shared scripts)

Proposed, **not applied** (`scripts/run-local.sh`, `scripts/_env.sh` are shared): an opt-in `PORTALS=1` mode that, instead of the legacy root app on :3100, builds and starts `apps/platform|admin|studio` with `next start -H 127.0.0.1 -p 3001|3002|3003`, each with `API_PROXY_TARGET=http://127.0.0.1:8080` (the same-origin `/api`, `/oauth2`, `/login/oauth2` rewrites already exist in each `apps/*/next.config.ts`), and `NEXT_PUBLIC_PORTAL_URL_{PLATFORM,ADMIN,STUDIO}` set to the three origins at **build** time. Legacy mode stays the default.

## 4. Flows (each is one spec; `R` = can be written once a backend runs, `B` = blocked on a decision or on wiring that does not exist yet)

Common rules for every flow: real browser (Chromium), `127.0.0.1` origins, fresh browser context per role, no mocked network, no `page.route`, no direct DB edits, a failed assertion is a failure (no retry-until-green), the backend `requestId` of the first failing call is printed, and the report states the label `PHASE3-REAL-BACKEND`, the backend commit, the flags, and the seed.

| ID | Flow | Status | Steps (assert) |
|---|---|---|---|
| E-01 | Login | R | Studio `/login` (local): wrong password → Vietnamese error, no cookie; right password → session cookie is `HttpOnly`, scoped to :3003 only; reload keeps the session; logout → `/login`, protected URL redirects with `next`. Repeat on :3001 and :3002 (separate sessions: signing in on one does not sign in the others — records B-C0-WEB-01 behaviour). |
| E-02 | Session expiry | R | Sign in, invalidate the session server-side (admin `revoke-sessions`), then trigger any API call (navigate, save): lands on `/auth/session-expired?next=…`, no data shown, signing in again returns to the same path. A 401 during an AI stream behaves the same. |
| E-03 | Portal gate | R | `local.viewer`/editor: Studio opens, Admin and Platform show "no access" and their APIs answer 403; platform-only SYSTEM_ADMIN (no membership, `businessAccess=false`): Platform/Admin open, Studio shows no-workspace (not a list of 403 pages); TENANT_ADMIN: per Q-1. Assert the screen choice **and** the server status of one API call per portal. |
| E-04 | Tenant → workspace → project selection | R | The workspace list shows only the user's workspaces; opening one lists only its projects; a second-tenant user never sees the first tenant's names (list, search, counts). The URL carries workspace and project ids; **no request contains a tenant id** (collect all `/api/v1` requests via `request` events and assert none has a `tenant` key/header/query). |
| E-05 | Create / open project; Studio edit; save / version | R | Create a PAGE_SCHEMA project; open the Builder; add a section (click and real drag), edit a prop, rename; each change is one PATCH `/schema`; after reload the document is identical; a version appears in history with the right summary; restore an older version → new current version, nothing lost. Concurrency: two contexts edit the same project, the second save gets 409 `REVISION_CONFLICT`, the UI reloads the latest and says so. Invalid change → 422 `SCHEMA_INVALID` with the violations listed (use a server-side-only rule, not a client one). |
| E-06 | Data query | **B** (Q-2, B-C3-11) | Needs: a data source for the project (created by whatever the backend offers — **no Builder op creates one**), a query + mapping + ViewModel + binding added through the Builder, then Test mode/preview returns real rows from the stub source, masked as the server masks them; a user without `QUERY_EXECUTE` gets 403 and the UI explains; another tenant's data source id → 404, no data. Until then the panels must show `NOT_READY(reason)` (assert that, as a `PHASE3` precondition check, not as a pass of E-06). |
| E-07 | Data mutation | **B** (same) | Needs the mutation route. Assert: success shows the server's `replayed` flag honestly; 422 `MUTATION_REJECTED` shows "nothing applied" and offers retry; **409 `IDEMPOTENCY_OUTCOME_UNKNOWN` shows "check the data first", no retry button, and a second click does not send a second request with the same key**; 409 `IDEMPOTENCY_IN_PROGRESS` shows the wait message; 429 shows back-off; a 502/504 on a write is shown as "outcome unknown". Fault injection through the **stub source** (it delays / drops the connection / returns 4xx), never through browser interception. |
| E-08 | Action execution | **B** (Q-2, B-C4-09) | Needs the action route. Add an action (REFRESH_QUERY, then CREATE_RECORD) in the Builder; in Test mode the answer is `WouldRun` at its `level` and **never** shown as success; a user without `ACTION_EXECUTE` is refused; the action's `onError` chain does **not** run after `IDEMPOTENCY_OUTCOME_UNKNOWN`. |
| E-09 | Workflow execution | **B** (same) | Needs the workflow routes. Start a MANUAL workflow with ACTION/WAIT/APPROVAL/BRANCH/END steps: run status polling shows each step; approval step blocks until a different user decides; cancel works; `WORKFLOW_MANAGE` required to edit, `WORKFLOW_EXECUTE` to start. |
| E-10 | Publish policy + publish + published-site verification | R (E-10a) / R with flag (E-10b) | (a) Publish a page project (PRIVATE then PUBLIC): the deployment appears, the site URL answers the served version, a PRIVATE site refuses an anonymous request and accepts a ticket; publish a second version, **roll back**, the first is served again. (b) With `app.publish-configs.enabled`: read/update the publish policy (revision CAS), adopt the draft, rotate the private link (token shown once, old link dies). Published-site check is done with a request **without** the studio cookie and asserts the served HTML contains the published (not draft) content and no secret/credential string. |
| E-11 | Error and empty states | R | Backend stopped mid-session: every list screen shows the network error state with retry (no blank page, no infinite spinner); an empty workspace shows the empty state with the create action; 403 on a project the user lost access to shows "no permission", 404 shows "not available" — and the two are not distinguishable for another tenant's project. |
| E-12 | Tenant isolation (negative) | R | With user A (tenant 1) and user B (tenant 2): B opens A's project URL → 404 page (same as a non-existent id), B's PATCH `/schema` on A's project → 404, B's `/admin/tenants/{A}` → 403/404; none of A's names appear in B's shell, search, audit or error text. |
| E-13 | CORS / cookies (security) | R | The browser only ever talks to **its own origin** (`/api` is a same-origin Next rewrite): collect all requests and assert none targets `:8080` or another portal. Because the rewrite forwards the browser's `Origin` header, Spring's CORS filter still judges it: every state-changing call from :3001/:3002/:3003 must succeed (a missing origin in `app.web.origins.*` shows up as 403 here — that is the check that the import switched the default). A direct call to the API from `http://evil.example` is refused and no response carries `Access-Control-Allow-Origin: *`. The session cookie of :3003 is not sent to :3001. Use `request` contexts with a real `Origin`, not header editing in the page. |
| E-14 | Accessibility smoke | R | `e2e/a11y.mjs`-style axe pass on login, Platform shell, Admin shell, Studio shell, Builder (with a real project loaded). |

Flows E-06…E-09 are the Phase 3 core. They stay **BLOCKED** (reported as `NOT RUN — blocked by Q-2/B-C3-11/B-C4-09`) until the routes exist; they are not replaced by the harness spec and the Builder's `NOT_READY` panels are not described as passing them.

## 5. Proposed layout (not created yet, only after a backend can run it)

```
tests/e2e-real/
  README.md            how to start the stack, env, the label rule
  guard.mjs            exits 2 ("NOT RUN: backend unreachable") when GET $E2E_API/api/v1/auth/config or any portal origin does not answer; never exits 0 without a backend
  lib.mjs              context-per-role, login helper, request collector (tenant-id assertion), requestId printer
  e01-login.spec.mjs … e14-a11y.spec.mjs
  report.mjs           prints one table: id, label PHASE3-REAL-BACKEND, PASS/FAIL/NOT RUN(reason), backend commit, flags, seed
```

Exit codes: 0 = every selected flow passed; 1 = a flow failed; **2 = NOT RUN** (no backend, or blocked). CI or a person reading "2" must read it as "no evidence", never as success.

Env: `E2E_API` (default `http://127.0.0.1:8080`), `E2E_PLATFORM` / `E2E_ADMIN` / `E2E_STUDIO` (default the three `127.0.0.1` origins), `CHROME`, `E2E_USERS` (JSON of seed logins read from the environment, never committed), `E2E_FLAGS` (the flags the stack was started with, echoed into the report).

## 6. Run order once the backend exists

1. Preconditions: E-01, E-03, E-13 (cheap, find config problems first).
2. E-04, E-05, E-02, E-11, E-12 (core studio + isolation).
3. E-10 (publish and published-site).
4. E-06 … E-09 only after their routes exist and Q-2 is answered.
5. E-14 last.

Evidence to hand to C0 with each run: the table of `report.mjs`, the Playwright trace of any failure, the backend `requestId`s of failed calls. A run on the Mac and a run in a container are different environments; say which one produced the result.
