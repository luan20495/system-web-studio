# C6 — RC WIDE REGRESSION · `integration/v2 @ 62ce9697cd56`

Independent QA (C6), 2026-10-08. RC for wide regression, **not** Final V1 sign-off. No production code was changed, nothing was committed.
Evidence: `docs/parallel/c6/evidence/rc-62ce9697cd56/` (logs, JUnit XML, JSON/TSV per suite, browser network captures, screenshots). Harness: `docs/parallel/c6/harness/rc-*.mjs|sh`.

## 0. Preflight (`evidence/…/preflight.txt`, `public-version-check.txt`)

| Item | Value |
|---|---|
| RC SHA | `62ce9697cd56e8dc86b3260923f5addbcf555743` = `integration/v2` HEAD (HBL checkout: branch `integration/v2`, clean, 0 modified files) |
| Where tested | detached worktree `/Users/hoangluan/code/xweb-c6-rc` at exactly that SHA, clean (`.env` and `.run/data-target` are git-ignored local files, removed at the end). `integration/v2` itself cannot be checked out twice, so the C6 worktree is a detached checkout of the same commit; nothing in `/Users/hoangluan/code/HBL` was touched. |
| Runtime used for the real flows | isolated stack **c6rc** built from that worktree: API `http://127.0.0.1:51080` (Flyway 30, profile `local` + the V1 flags of `scripts/_env.sh`: `WORKFLOW_QUEUE=amqp`, `PUBLISH_CONFIGS_ENABLED`, public data runtime, TLS data target allow-listed), PostgreSQL 51432, Redis 51379, MinIO 51900, RabbitMQ 51672, sites gateway **http://127.0.0.1:51088**, render worker 51095, portals **Platform http://127.0.0.1:3401 · Admin :3402 · Studio :3403**. Own containers `c6rc-*`, own ports, no other stack touched. |
| Real data source | the V1 TLS data target `127.0.0.1:15440` (`hbl-v1-data-target`, roles `shop_ro` / `shop_rw`), used as a client only. |
| Public URLs (actual availability, 2026-10-08 06:50–08:17 +07) | `https://platform.toolsmcp.uk/`, `https://admin.toolsmcp.uk/`, `https://studio.toolsmcp.uk/` → **HTTP 200 over HTTPS** (Cloudflare edge); `https://sites.toolsmcp.uk/` → 404 by design (no listing), `/healthz` 200; plain http → 308 to https. |
| Public build = RC? | artifacts are newer than the last code commit (API jar 23:27, started 23:27:39; three portal builds 23:27–23:28; last backend src change `1006cbf` 23:13, last frontend src change `35ca3a9` 22:43; RC commit 23:30 is docs only) → **consistent, not provable**: no SHA is embedded or exposed (BUG-C6-012, open since the guide QA). |
| Tools | JDK 21.0.12, Node 22.23.1, Docker 29.8.2, Chrome (system) via playwright-core |

## 1. Final result

```
RC_SHA: 62ce9697cd56
WIDE_REGRESSION: PASS          (0 regressions, 0 product NEW_BUG; 2 known blockers; 6 low test/tooling defects)
SUPER_ADMIN: PASS
TENANT_ADMIN: PASS
APP_CREATOR: BLOCKED_BY_H-C1-04
PROVISIONING: PASS
AUTH_SECURITY: PASS
CSRF: PASS
CORS: PASS
PORTAL_ROUTING: PASS
PUBLIC_HOSTS: PASS
MANAGEMENT_API: PASS
DATA: PASS
ACTION: PASS
WORKFLOW: PASS
PUBLISH: PASS
ROLLBACK: PASS
PUBLIC_SITE: BLOCKED           (H-C2-07, C2 / C5 — runtime itself verified, Studio-authored path blocked)
GATEWAY: PASS
RECOVERY: PASS
NEGATIVE_SECURITY: PASS
PERFORMANCE_SMOKE: PASS        (smoke suites only; load test NOT_RUN: no threshold is defined for this RC)
READY_FOR_FINAL_RETEST_AFTER_C1: YES
FINAL_V1_READY: NO
```

## 2. Gates and suites run

| # | Suite (script) | Target | Result |
|---|---|---|---|
| 1 | Backend `./gradlew clean test --no-daemon --no-build-cache --rerun-tasks` (real execution, no `FROM-CACHE`) | RC worktree | **211 classes · 2003 tests · 2000 pass · 0 fail · 0 error · 3 skipped** (`OpenRouterLiveTests`, no key). BUILD SUCCESSFUL 14m 8s. = C0's D-C0-41 number. `backend-junit-xml/`, `logs/backend-test.log` |
| 2 | Frontend: `tsc --noEmit`, `typecheck:all`, `typecheck:apps`, legacy-route guard, `test:unit`, `test:classify`, runner selftest, production build of the 3 portals (`portals.sh up`) | RC worktree | tsc ×3 exit 0 · guard PASS (137 files, no portal code posts to `POST /api/v1/admin/workspaces`) · unit **277 tests, 276 pass, 0 fail, 1 skipped** · selftest 25/25 · 3 builds exit 0 |
| 3 | Browser harness specs (class `harness`, no backend; `tests/browser/*.spec.mjs`) | `.test-build/browser` | sanity 8/8 · builder 80/80 · datasources 54/54 · publicdata 44/44 · aiproviders 27/27 · provisioning 39/39 · page-runtime 37/37 · release 56/56 = **345/345**. `portals.spec.mjs` **not applicable** (it expects backend-less portals on fixed ports 3001/3002/3003, which belong to C5's stack; it timed out clicking a disabled button there — not run against RC). |
| 4 | `scripts/provisioning-e2e.mjs` (API: super admin → tenant → workspace → tenant admin → user → app creator, critical negatives) | c6rc API | run 2: **35/35**. Run 1 on the empty database: 34/35, see TD-02 |
| 5 | **C6 `rc-sec.mjs`** (401/403/404/CSRF/CORS/escalation/last-admin/disabled/secret leakage, 2 tenants × 6 roles) | c6rc API | **74/74** (`rc-sec.tsv`) |
| 6 | **C6 `rc-data.mjs`** (Management API + Data + Action + Workflow through the real API, no SQL) | c6rc API + TLS target | **99/99** (`rc-data.tsv`) |
| 7 | C5 real-backend suite `npm run test:e2e:real` (real Chrome → real portals → real API), full run ×2 + targeted re-runs | c6rc | Run 1 (default flags, memory queue, no data source) PASS 31 · FAIL 4 · BLOCKED 7 (PD02 failed there only because `SITES_DATA_API_BASE` was not set: `apiBase = null`). Run 2 (V1 configuration, AMQP queue, TLS data source, public data runtime) PASS 32 · FAIL 7 · BLOCKED 3. Every non-PASS is classified in §4; per-flow table in `evidence/rc-62ce9697cd56/e2e-flow-table.md` |
| 8 | **C6 `rc-rabbit.mjs`** (RabbitMQ stop/start under a live workflow start, backend restart with a parked run) | c6rc | **16/16** |
| 9 | **C6 `rc-portals.mjs`** (3 local portals × 5 accounts, deep links, redirects, logout, open-redirect, USER01 evidence) | c6rc portals | 25 checks: 23 PASS, 2 `BLOCKED_BY_H-C1-04`, 0 FAIL |
| 10 | **C6 `rc-user01-api.mjs`** (what the app creator can do through the API) | c6rc API | 10 steps, see §3.3 |
| 11 | Existing public suites: `global-portals-smoke.mjs`, `global-portals-browser.mjs`, `global-smoke.mjs` | Internet hostnames | **104/104 · 38/38 · 69/69** (same as C0's D-C0-41 numbers). Notes: unknown `/_next/static/**` answers the HTML shell (FINDING, C5, harmless); public query PASS = NOT_READY (no globally reachable data source) |
| 12 | **C6 `rc-public-net.mjs`** — real Chrome, empty profile, per portal: load, deep link, real sign-in, reload, protected page, same-origin API, sign-out; every request/response/console message recorded (`net/public-*.json`, `shots/pub-*.png`) | 3 public portals + sites host | **49/49**: HTTPS only, no loopback/private/foreign host, no http/ws request (no mixed content), all `/_next` assets 200, no failed request/5xx, no unexpected 4xx, no console error, first-party bundles contain no `http://localhost|127.0.0.1` origin and no mock-mode switch |
| 13 | `tests/gateway/data-route.mjs` (real nginx, both modes) | throwaway containers | 31/31 · 31/31 |
| 14 | `tests/gateway/portal-route.mjs` | throwaway containers | passes 15/15 when it runs to the end; **5 of 8 runs died at check 12** ("trusted proxy", `socket hang up`): test race, TD-03 |
| 15 | **C6 `rc-gateway.mjs`** (sites gateway in front of the c6rc API) | c6rc | API up **20/20**; API stopped **4/4** (bounded 502, `/healthz` still 200) |
| 16 | `scripts/v1-public-smoke.mjs` (visitor flow, PUBLIC_SITE, rollback, unpublish) — derived copy `rc-public-smoke.mjs` | c6rc | acknowledged variant **42/42**; "Studio-like" variant without the `publish-config` acknowledgement 34/42, see KB-02 |

## 3. Findings per area

### 3.1 SUPER_ADMIN — PASS
E2E-PL01 (login Platform, navigation, tenants create/suspend/restore/members/last-admin, SYSTEM_ADMIN grant/revoke, audit page, system-health page) PASS in both runs; E2E-SUPER01 (tenant → tenant-scoped workspace → first Tenant Admin by invitation → activation → Admin login) PASS; provisioning-e2e A-block (super admin); `rc-sec` D01–D04 (disable / session killed / re-enable), E09 (cannot disable yourself), X01 (audit shows `DATASOURCE_*`, `DATA_QUERY_DEFINITION_CHANGED`, binding events); portals: super admin admitted to all three portals; logout → `/login`, `/auth/me` 401.
**Network:** `POST /api/v1/admin/tenants/{tenantId}/workspaces` with body `{name}` only; the platform portal's create-account request is `POST /admin/tenants/{tenantId}/users` with the tenant in the path, `X-XSRF-TOKEN` present and no `tenantId` in the body (SUPER01 check); **0 browser requests to the legacy `POST /api/v1/admin/workspaces`** (ADMIN01 check + static guard over 137 files). The legacy route still exists server-side for system admins and is closed to tenant admins (403 `ADMIN_REQUIRED`, SEC01 and `rc-sec` A06).

### 3.2 TENANT_ADMIN — PASS
E2E-AD01/AD03/ADMIN01/SEC01 PASS; provisioning-e2e B-block. Own company only, no foreign visibility (`rc-sec` N01–N04, N12–N14 → 404 `TENANT_NOT_FOUND`, candidate search never returns tenant-B accounts), members with metadata, user created with `POST /admin/tenants/{tenantId}/users` (tenant in path), App Creator created with a workspace role, candidate search ≥ 2 chars (400 `QUERY_TOO_SHORT`), re-role/remove protected (E06/E07 `LAST_TENANT_ADMIN`, self-change 403), cannot mint SYSTEM_ADMIN (400 `TENANT_ROLE_INVALID`; a body flag `systemAdmin:true` is ignored), no cross-tenant enumeration.

### 3.3 APP_CREATOR (USER01) — **BLOCKED_BY_H-C1-04** (matches the known behaviour exactly)
Chain executed: tenant admin → create `app.creator` (workspace role EDITOR) → activation → project EDITOR membership → login. Evidence (`rc-user01-api.json`, `rc-portals-user01.json`, `shots/portal-studio-creator.png`):

| Step | HTTP | Result |
|---|---|---|
| `POST /admin/tenants/{t}/users` → `POST /auth/activation/complete` → `POST /auth/login` | 201 · 200 · 200 | account active |
| `POST …/projects/{p}/members {EDITOR}` | 201 | project access assigned |
| `GET /auth/me` | 200 | `systemAdmin:false`, `tenantRole:MEMBER`, top-level `permissions: []`, `workspaces[0].role:"EDITOR"`, `workspaces[0].permissions: []` |
| `GET …/projects` | 200 | project visible |
| `GET …/projects/{p}` | 200 | `permissions: [ACTION_EXECUTE, APP_USE, DATA_SOURCE_VIEW, PROJECT_CREATE, PROJECT_EDIT, PROJECT_READ, PROJECT_SETTINGS, QUERY_EXECUTE]` |
| `PATCH …/schema` (edit) then reload | 200 | persisted, revision 0 → 1 |
| `POST …/publish` | **403** `FORBIDDEN` | no PROJECT_PUBLISH, as designed |
| `GET …/members` | 403 `FORBIDDEN` | no member management |
| foreign workspace id | 404 `WORKSPACE_NOT_FOUND` | |
| Studio portal sign-in (real UI) | redirect **`/auth/no-access`** | the Studio gate reads `/auth/me` only |

Same for a project VIEWER (`APP_USE`, `PROJECT_READ` on the project API; `/auth/me` empty; refused at the gate). E2E-USER01 → BLOCKED(C1); E2E-04 / E2E-05 FAIL in the runner with the same "CONTRACT MISMATCH VIEWER" (identical to C5's recorded baseline "FAIL 2/14, E2E-04/05: contract mismatch"; the API half of E2E-05 PASSES: 403 same scope, 404 foreign). No role-name workaround, no injected permission.

### 3.4 AUTH / SECURITY / CSRF / CORS / NEGATIVE — PASS (`rc-sec.tsv`, 74/74; E2E-SEC01 24/24; E2E-05 API half; backend 83 security-named tests)
Expected vs actual HTTP statuses (every row in the TSV):
* **401** unauthenticated protected request (`/auth/me`, projects, `/admin/tenants`, `/admin/audit`) → 401 `AUTHENTICATION_REQUIRED`.
* **403** same scope, missing permission: VIEWER create project, EDITOR list members, project EDITOR publish, project VIEWER schema patch, project EDITOR list project members, WORKSPACE_ADMIN on tenant/platform routes, Tenant Admin on platform audit/system health/tenant creation → 403 (`FORBIDDEN` / `ADMIN_REQUIRED`).
* **404** foreign tenant / workspace / project / guessed UUID → 404 with identical status+code for "foreign" and "random" (no existence oracle, N11).
* **CSRF** login without token / invalid token → 403 `CSRF_INVALID`; authenticated mutation (create project, provisioning route, DELETE) without or with a forged token → 403; with the valid token → 201.
* **Escalation**: self-grant 403 `SELF_GRANT_FORBIDDEN`; EDITOR self-promotion 403; WS admin disabling an account 403; last workspace admin 409 `LAST_ADMIN`; last tenant admin 409 `LAST_TENANT_ADMIN`; self-disable 409 `CANNOT_DISABLE_SELF`; disabled user: existing session 401, login 401, re-enabled login 200.
* **Secrets**: no password/hash/secret key in `/auth/me`, admin user list, tenant/workspace members; unknown activation token 410 `LINK_INVALID` (429 `RATE_LIMITED` after 30/10 min per IP); wrong password and unknown user both 401.
* **CORS** (API direct): foreign origin / `null` / look-alike suffix → no `Access-Control-Allow-Origin` (403); preflight from a foreign origin 403; the configured portal origin → `ACAO` = that origin + `credentials:true`. Public portals use same-origin `/api` (public suites assert the same).
* **Surface**: `/actuator/env`, `/actuator/heapdump` 401; `/actuator/health/readiness` 200; `/v3/api-docs` 200 **on the local profile only** (springdoc enabled by `application-local.yml`; `application-prod.yml` disables it; on the three public hosts that path returns the portal HTML, not the schema).

### 3.5 PORTAL ROUTING — PASS (local `rc-portals.mjs` 25 checks + public `rc-public-net`/`global-portals-browser`)
Admission = `capabilitiesOf()` of `@xweb/permissions`: super admin → Platform ✓ Admin ✓ Studio ✓; tenant admin → Platform ✗ (`/auth/no-access`) Admin ✓ Studio ✓; workspace admin → ✗ ✓ ✓; app creator → ✗ ✗ ✗(H-C1-04); viewer → ✗ ✗ ✗(H-C1-04). Signed-out deep link → `/login?next=…` (3 navigations, no loop); after sign-in the target opens; direct URL while signed in; signed-out after logout redirects to `/login`; external `next` (`https://evil.example/`, `//evil.example/`, `/\evil.example`) never leaves the portal origin; tenant admin switching to another portal origin is admitted or asked to log in, to Platform refused. Observations (not defects, no documented rule): opening `/login` while signed in shows the login form again (no loop); an unknown `/admin/*` or `/platform/*` path shows the portal home, while an unknown path outside a portal prefix shows "Không có trang này".

### 3.6 PUBLIC HOSTS — PASS (§2 rows 11, 12)
All three portals and the sites host answer on HTTPS; assets load; login loads; sign-in works; same-origin API reachable; no localhost/127.0.0.1 request, no mixed content, no mock fallback, no broken `/_next` asset. Real sign-in used the operator (platform, admin) and `demo01` (studio) accounts; credentials went only into the login form.

### 3.7 MANAGEMENT API — PASS (`rc-data` M01–M30, E2E-06 stage checks, backend 396 tests by class name)
Connector catalogue; data-source CRUD (201, duplicate name case-insensitive 409, invalid name 400 `INVALID_CONFIG`, unknown type 422 `UNSUPPORTED_TYPE`, planned type 501 `NOT_IMPLEMENTED`, secret key in config 400, authority key `tenantId` 400 `INVALID_PARAMS`, SSRF: platform DB 15432, apps DB 15434, `localhost`, non-allow-listed loopback port, `169.254.169.254` all 400 `INVALID_CONFIG`); credential **write-only** (PUT 200 metadata only, GET returns key names only, list/detail show `hasCredential`, no `credentialRef`, **no response of the whole run contained either target password**, audit payloads clean); test connection over TLS verify-full `ok:true` 40 ms, wrong password `ok:false AUTH_REJECTED`, privileged role on a non-writable source → warning "the database role can write to 1 table(s); use a SELECT-only role"; schema discovery (customers, orders); query definitions (DDL / stacked statements / writes refused 400 `INVALID_QUERY`, summary has no SQL, `expectedVersion` mismatch 409); TEST/LIVE bindings (lower-case mode 400, foreign tenant 404, delete while bound 409); EDITOR/VIEWER 403, anonymous 401, foreign tenant 404, missing CSRF 403; raw `POST /api/v1/data/mutate` 404; audit shows the management actions.

### 3.8 DATA — PASS
Real TLS PostgreSQL: Data Source → credential → test → discover → query definition → slot (`ADD_DATA_SOURCE`) → `ADD_QUERY`/`ADD_MAPPING`/`ADD_VIEW_MODEL` → TEST/LIVE binding → TEST run (parameter filters rows, mapping gives `name`/`description`) → LIVE before publish = 404 (no fallback to the draft) → publish RUNNING → LIVE run = active release. Failure states: wrong parameter type / client `sql` field / unknown query → 4xx without rows; source DISABLED → 409 `DISABLED`; credential replaced by a wrong one → 502 `AUTH_REJECTED`, no rows, no secret; credential restored → ok; TEST binding removed → 422 `DATA_SOURCE_UNBOUND` (never falls back to LIVE). E2E-07 (Studio Test panel against the real source, UI) PASS. **Not exercisable live:** a statement timeout (the SQL guard refuses `pg_sleep`, 400 `INVALID_QUERY`); timeout/unknown-outcome semantics are covered by 28 + 36 passing backend test cases (`PostgresSessionSecurityTests`, `WorkflowG3RecoveryTests`, …).

### 3.9 ACTION — PASS
Canonical `ActionType` list enforced (`WRITE_DATA` → 422 `SCHEMA_INVALID` listing the nine canonical types). Real runtime: TEST = `WOULD_RUN`, row count unchanged; LIVE write without idempotency key → 400 `IDEMPOTENCY_KEY_REQUIRED`; LIVE `CREATE_RECORD` with a key writes **exactly one real row** in `shop.orders` (read back by an uncached TEST read); replay with the same key → same outcome, still one row; same key with different inputs → 409; three concurrent submits with one key → 409/409/200, one row; project EDITOR (no `DATA_MUTATE`) → 403, nothing written; EDITOR without project membership 403/404; foreign tenant 404; client-supplied `sql` in inputs 400; `script` / raw `url` in an action definition rejected (422 `SCHEMA_INVALID`, "unknown field") → no arbitrary JS. E2E-S1, 10, S3, S4 PASS; E2E-S9 (backend hang) PASS.

### 3.10 WORKFLOW — PASS
Start (TEST creates a run row, 202), LIVE start, status with steps (`SUCCEEDED`, step `end`), same idempotency key → same run, WAIT step parks the run (`WAITING`), cancel → `CANCELLED`, cancel again 200, unknown workflow 404, foreign tenant reading a run 404, a VIEWER without project membership starting a workflow 403/404, a project EDITOR (no `WORKFLOW_EXECUTE`) 403. E2E-11, 12, S4, S8 PASS; E2E-12 restarts the backend with a run in flight and it ends `SUCCEEDED` (durable run store V29). Retry/queue/recovery cases: §3.13 and backend `WorkflowEngineTests` etc. (157 workflow-class tests, 44 queue tests, all passing).

### 3.11 PUBLISH / ROLLBACK — PASS
E2E-13, P01–P09 PASS in both runs (exact request, polling, only `RUNNING` is success, concurrent publish + idempotent replay, rollback success, **`409 ROLLBACK_STALE`** with nothing changed, `409 SCOPE_BUSY` + `Retry-After`, rollback in flight (operation `ROLLBACK`), stale publish ends `FAILED [STALE_PUBLISH]`, unpublish idempotent); `rc-data` D11–D13, A05; public smoke rollback (visitor sees release 1 again; `q-extra` of release 2 is 404 after the rollback) and unpublish (data route 404, page 404). Runtime config: `__factory/config.json` has a slug-scoped same-origin `apiBase`, `no-store`, no tenant/workspace/credential/lease/fence field; no localhost hard-code in any published artifact or portal bundle.

### 3.12 PUBLIC_SITE — **BLOCKED (H-C2-07)** — runtime verified, Studio-authored path blocked
Verified (`rc-public-smoke` 42/42, `rc-gateway` 20/20, E2E-PD02 PASS in run 2): anonymous visitor in a real Chrome with an empty profile → page → `__factory/config.json` → `/{slug}/_data` POST (no cookie, no authorization, same origin) → PUBLIC_SITE authority → LIVE query → real TLS PostgreSQL → rendered text equals the database value (`Open orders: N`, `SO-1001`); forged `tenantId` in the body 400; no public mutation route; unknown query 404 `QUERY_NOT_FOUND`; a draft change does not reach the active release; non-public query / foreign Origin refused; no credential or definition in any response.
**Blocked:** a page published **from Studio** with a public query shows its authored fallback and the runtime ends in state `error` (data route 404). See KB-02.

### 3.13 RECOVERY — PASS
`rc-rabbit` Q01–Q15: with RabbitMQ stopped a workflow start is answered in 79 ms with `202 PENDING` (never `SUCCEEDED`), readiness reports `DOWN` (503), unrelated reads keep working; after the broker is back the run accepted during the outage completes **106 s later** (the sweeper republishes a run stale for `app.workflow.stale-after = PT2M`), replaying its idempotency key returns the same run, a new run succeeds; a `WAIT` run parked before a **backend restart** survives it (durable store), completes `SUCCEEDED`, replay returns the same run, the active release and operation state are unchanged. Also E2E-12, S6 (backend stopped: no request storm, retry persists exactly once), S7 alone, S9, P-flows; backend recovery-named classes 167 tests.

### 3.14 GATEWAY — PASS
Sites gateway: `/healthz`, slug route, published page, static assets, runtime config, public data route (non-public query refused, GET refused, foreign Origin not granted); `/api/v1/*`, `/actuator/*`, `/swagger-ui.html`, `/v3/api-docs`, path traversal (`/../`, `%2e%2e`) → 400/403/404 without content; unknown slug 404; forged `X-Forwarded-For` ignored; no c6rc container published beyond loopback. API stopped: bounded **502** in 2–5 ms for page, data route and unknown slug, `/healthz` still 200, no upstream address or stack trace in the body. Template tests: data-route 31/31 in both modes; portal-route see TD-03.

### 3.15 PERFORMANCE / SMOKE — PASS (smoke) · load test NOT_RUN
Suites used and results: `global-smoke` 69/69, `global-portals-smoke` 104/104, `global-portals-browser` 38/38, `provisioning-e2e` 35/35, `rc-public-smoke` 42/42. **Threshold:** none is defined for this RC. `scripts/load-test.sh` (k6) measures CPU/RSS and latency but has no pass/fail threshold, needs the API on :8080 and a restart with raised rate limits → **not run, no threshold invented**. `scripts/v1-smoke.sh` needs an SQL seed into the platform DB (the shortcut this task forbids) and the same workspace assumption as TD-05; `scripts/smoke-test.sh` hard-codes `127.0.0.1:8080` → **not run** on the isolated stack (their ground is covered by `rc-data`, `rc-public-smoke`, `global-smoke`). Regressions: none.

## 4. Classification of every non-PASS result

| ID | Class | Owner | Flow | Expected | Actual | HTTP | Severity |
|---|---|---|---|---|---|---|---|
| **KB-01 H-C1-04** | KNOWN_BLOCKER | **C1** | E2E-USER01, E2E-04, E2E-05 (UI half), rc-portals `PR-studio-creator`, `PR-studio-viewer` | Studio opens for a person whose rights come from a project/workspace membership | gate reads `/auth/me`: `permissions:[]`, `workspaces[].permissions:[]`; project API resolves the permissions; redirect `/auth/no-access` | `/auth/me` 200 · project 200 · creator edit 200 · publish 403 | final blocker |
| **KB-02 H-C2-07 (refined)** | KNOWN_BLOCKER (open handoff, P1) | **C2** (enforce/propagate) + **C5** (Studio never calls it) | E2E-PD01 (BLOCKED), `rc-public-smoke` no-ack variant | a page published from Studio with a public query serves its data | The Studio publish dialog asks for a **local** acknowledgement and `POST /publish` carries no such field; the server **does** need `publish_configs.public_data_approved` (set only by `PUT …/publish-config`, which no Studio code calls) before the public data route serves anything. Without it: data route **404**, visitor page runtime `error`, authored text only (8 of 42 checks fail). With the API acknowledgement: 42/42. The flow's own diagnosis ("Public Runtime controller not wired") is wrong: the route is wired. | `POST /{slug}/_data/queries/q-title/run` → 404 | P1, **not in the stated blocker list** — the known handoff understates the effect |
| TD-01 | NEW_BUG (test order) | C5 | E2E-S7 | passes whatever ran before | fails (`getByRole('dialog').getByRole('button',{name:/^Xuất bản$/})` times out) when run after PD01/PD02: those flows leave public-data bindings on the shared project A, the publish dialog then requires the acknowledgement tick and the button stays disabled. Alone: PASS; after the 8 portal/provisioning flows: PASS; `E2E-PD02,E2E-S7`: FAIL. Product behaviour is correct. Failed in full run 1 and 2. | — | Low |
| TD-02 | NEW_BUG (test precondition) | C1 (author of `provisioning-e2e.mjs`) | provisioning-e2e check "A /auth/me: … permissions are only TENANT_MANAGE + TENANT_MEMBERS" | passes on any database | on an empty DB the check reads `/auth/me` before any foreign workspace exists (`platform-view workspaces=0`) and fails; 35/35 on the second run | — | Low |
| TD-03 | NEW_BUG (test race) | C0 | `tests/gateway/portal-route.mjs` | deterministic | waits for the *untrusted* gateway container only; the *trusted* one is used at check 12 before it is ready (`ECONNRESET socket hang up`): 5 of 8 runs fail, 3 pass 15/15. Header says `GATEWAY_FORCE_HTTPS`, code reads `PORTAL_FORCE_HTTPS`. | — | Low |
| TD-04 | ENVIRONMENT/TOOLING | C5 | `docs/parallel/c5/e2e-stack.sh` | starts the sites gateway of the RC | the container exits (`nginx [emerg] host not found in set_real_ip_from "${GATEWAY_REAL_IP_FROM}"`): the script does not pass `GATEWAY_REAL_IP_FROM` / `GATEWAY_FORCE_HTTPS` that the RC template requires (C6 started the container itself with the compose values) | — | Low |
| TD-05 | NEW_BUG (test assumption) | C0 | `scripts/v1-public-smoke.mjs`, `scripts/v1-smoke.mjs` | run on any database | take `/auth/me` `workspaces[0]` as the member workspace; the list is ordered by name, so any non-empty DB fails with `project created \| 403`. C6 ran a derived copy that picks the `WORKSPACE_ADMIN` workspace (2 documented deviations) | 403 | Low |
| TD-06 | NEW_BUG (stale skeleton) | C5 | E2E-06 / 08 / 09 / 14 | reflect the RC | E2E-06 ends BLOCKED after all its checks pass, citing "no op to declare a slot (H-C2-02)" and "no reachable source"; E2E-08/09 always `throw Blocked` with the same obsolete reasons although `ADD_DATA_SOURCE`, the public runtime and the writable connector exist at the RC (verified by `rc-data` / `rc-public-smoke`); E2E-14 expects an *error state* during a RabbitMQ outage, while the product (correctly, verified by `rc-rabbit`) accepts the run as `PENDING` and recovers ~2 min after the broker returns, so the Test panel never settles (`TIMEOUT`) and the button is not offered again within the flow's 8 s | 202 PENDING | Low |
| TD-07 | OBSERVATION | C5 | E2E-07 fixture | — | the Studio Test panel runs a query without parameters; a query with a required parameter and only a document-level `default` answers 400 `INVALID_PARAMS` ("missing parameter status"). C6's own seeding error, fixed by seeding a parameterless query; noted because a parameterised query cannot be tried from the Test panel | 400 | info |
| EV-01 | ENVIRONMENT | C0 | RC version proof | build SHA verifiable | no SHA in jar/Next builds or public endpoints (BUG-C6-012, open) | — | Medium |

**NEW_BUG (product): none. REGRESSION: none.** Compared with D-C0-41 (backend 2003/2000/0/3, unit 277/276/0/1, public suites 104 + 38 + 69, SUPER01/ADMIN01 PASS, USER01 BLOCKED) and C5's recorded baseline (E2E-04/05 contract-mismatch FAIL): identical.

## 5. Reproduce

```
# stack (isolated, own ports): once
. docs/parallel/c6/harness/rc-env.sh            # ports, names, RC SHA
git worktree add --detach /Users/hoangluan/code/xweb-c6-rc 62ce9697cd56 && (cd /Users/hoangluan/code/xweb-c6-rc && npm ci)
cd /Users/hoangluan/code/xweb-c6-rc && bash docs/parallel/c5/e2e-stack.sh prepare      # then add the V1 flags + the three portal origins to $E2E_STACK_DIR/stack.env (see §0)
bash docs/parallel/c5/e2e-stack.sh infra-up && bash docs/parallel/c5/e2e-stack.sh backend-up && bash docs/parallel/c5/e2e-stack.sh render-up
# sites gateway container: see TD-04 (GATEWAY_REAL_IP_FROM=127.0.0.1 GATEWAY_FORCE_HTTPS=0); portals: PORTALS ports 3401-3403 via scripts/portals.sh up (needs a git-ignored .env with LOCAL_ADMIN_PASSWORD)
# suites
node docs/parallel/c6/harness/rc-sec.mjs | rc-data.mjs | rc-rabbit.mjs | rc-portals.mjs | rc-user01-api.mjs | rc-gateway.mjs (MODE=up|down) | rc-public-net.mjs
bash docs/parallel/c6/harness/rc-e2e-full2.sh [E2E-ID,E2E-ID] [outdir-suffix]       # C5 real-backend suite under the V1 configuration
```
Env/secrets are read from `$E2E_STACK_DIR/stack.env`, `/Users/hoangluan/code/HBL/.run/data-target/*.pw` and `.run/public/*` inside the scripts; none is printed or stored in evidence.

## 6. State left behind
The c6rc stack, the portals and the throw-away `http.server` were stopped after the run; the RC worktree and `~/.xweb-e2e-stack/c6rc` are kept for the retest (the worktree's git-ignored `.env` and `.run/data-target` link were removed). Test data on the c6rc database (tenants `c6*`, `e2e-*` workspaces — there is no delete route for workspaces) lives and dies with that stack. The public stack received only the suites' own sign-ins and self-cleaning projects. Nothing was committed or pushed.
