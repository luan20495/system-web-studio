# C5 → C0 handoff — C0 — architecture / integration / runtime / migration / wiring

From C5 (Studio/Frontend), baseline `integration/v2 @ f894cc6`. C5 changed nothing owned by C0. Nothing here was run against a live backend: evidence is the code, the BLOCKERS rows and the suite's blocker records.

## H-C0-01 (B-C0-W-03)

- **ID:** H-C0-01 (B-C0-W-03)
- **Owner:** C0 (C3 implements)
- **Severity:** P1
- **Flow:** Configure DataSource / Query / binding from Studio
- **Frontend expectation:** Studio can create or pick a data source, define a query and bind it (TEST, later LIVE) through an HTTP management API.
- **Update (C5 re-audit, `API_AUDIT_F894CC6.md` §4):** C3 reports route code for sources, credentials, test-connection and bindings (`agent/c3-data-prod @ e606465`, `docs/parallel/c3/MANAGEMENT_API.md`). The Studio client and panel are built against it. It is **not in `integration/v2`**, C3 states Spring compile / route tests / live backend are NOT verified, and there is still **no query or mutation management endpoint** and no AppDefinition op to declare a slot (H-C3-03, H-C2-02). This item stays open for those parts.
- **Actual backend behavior (on f894cc6):** No controller creates data sources, credentials, queries or source bindings; V28 stores credentials as SecretsCrypto ciphertext that only the server key can produce; typed AppDefinition ops cannot add a source (it is granted, not created).
- **Endpoint/event:** (missing) e.g. `/api/v1/workspaces/{w}/data-sources`, `…/projects/{p}/queries`, `…/bindings`
- **Request:** n/a
- **Response/status:** n/a (route does not exist)
- **Reproduction:** Grep the f894cc6 controllers for data-source/query management mappings: only `AppRuntimeDataController` (run) and `AppRuntimeActionController` exist.
- **Evidence:** `docs/parallel/BLOCKERS.md` B-C0-W-03; V28 schema; `tests/e2e-real/flows/e2e-06.mjs` blocker text.
- **Impact:** Studio Data panel stays NOT_READY by design; E2E-06 cannot run, E2E-07/08 need an operator-seeded project.
- **Suggested contract/fix:** Add management routes + a contract section in `docs/contracts/v2` (C0 decision first), including credential write-only input and permission DATA_SOURCE_MANAGE.
- **C5 workaround:** Panel shows NOT_READY with the reason; `E2E_DATA_*` env lets an operator supply a pre-seeded project for E2E-07.
- **Blocked test IDs:** E2E-06, E2E-07 (unless pre-seeded), E2E-08, E2E-09

## H-C0-02 (B-C0-W-01)

- **ID:** H-C0-02 (B-C0-W-01)
- **Owner:** C0 (C4)
- **Severity:** P1
- **Flow:** LIVE mutating actions, workflow starts, durable runs
- **Frontend expectation:** Run records survive a restart; LIVE writes and workflow starts are accepted.
- **Actual backend behavior:** V28 data runtime is integrated; V29 run stores are not. LIVE mutating actions and workflow starts answer 503 `RUNTIME_STORES_VOLATILE` unless `app.workflow.allow-volatile-stores=true`; run stores are in memory.
- **Endpoint/event:** `POST …/app-runtime/actions/{id}/execute`, `POST …/app-runtime/workflows/{id}/runs`
- **Request:** `{mode:"LIVE", idempotencyKey}`
- **Response/status:** 503 `RUNTIME_STORES_VOLATILE`
- **Reproduction:** Start the stack without V29 / without the flag and POST a LIVE execute.
- **Evidence:** BLOCKERS B-C0-W-01; `e2e-11` / `e2e-12` detect the code and report BLOCKED.
- **Impact:** No durable workflow evidence; LIVE write cannot be tested.
- **Suggested contract/fix:** Integrate `wire/v29-run-persistence` (Flyway number from C0), or document the volatile flag as test-stack-only.
- **C5 workaround:** UI maps the code to a plain message (`errors.ts`); TEST mode is unaffected.
- **Blocked test IDs:** E2E-09, E2E-12 (E2E-11 only on LIVE)

## H-C0-03

- **ID:** H-C0-03
- **Owner:** C0 (docs)
- **Severity:** P3
- **Flow:** TEST-mode permission naming
- **Frontend expectation:** Contract says TEST needs `APP_EDIT`.
- **Actual backend behavior:** Controller checks `PROJECT_EDIT`; `PermissionCodes` maps it to the same permission.
- **Endpoint/event:** `AppRuntimeDataController`, `AppRuntimeActionController`
- **Request:** any TEST-mode run
- **Response/status:** 403 for users without it (same set)
- **Reproduction:** Read the controller annotations and `PermissionCodes`.
- **Evidence:** `API_AUDIT_F894CC6.md` M6
- **Impact:** None at runtime; confusing for implementers.
- **Suggested contract/fix:** Use one name in `runtime-api.md` (C5 does not edit `docs/contracts/**`).
- **C5 workaround:** `core/permissions.ts` alias table.
- **Blocked test IDs:** none

## H-C0-04

- **ID:** H-C0-04
- **Owner:** C0
- **Severity:** P2
- **Flow:** Detect that the data/workflow runtime is switched off
- **Frontend expectation:** A way to tell "feature off" from "id not found".
- **Actual backend behavior:** With the flag off the controllers are not mounted: a plain 404 with no `code`. With the flag on, missing ids answer 404 with a code.
- **Endpoint/event:** `…/app-runtime/queries/*`, `…/actions/*`, `…/workflows/*`
- **Request:** any
- **Response/status:** 404 (no code) vs 404 `QUERY_NOT_FOUND`/`UNKNOWN_ACTION`
- **Reproduction:** Start with `app.data-platform.enabled=false` and POST a query run.
- **Evidence:** `runtimeReadinessFromError` unit test; run.mjs route probe.
- **Impact:** Heuristic detection only.
- **Suggested contract/fix:** A capabilities endpoint, or 501 with a code such as `RUNTIME_DISABLED`.
- **C5 workaround:** 404 without code or `HTTP_404`/501 ⇒ NOT_READY "flag off".
- **Blocked test IDs:** none (heuristic works)

## H-C0-05 — what C5 needs from C0 to run the real-backend suite (the only requests)

- **ID:** H-C0-05
- **Owner:** C0
- **Severity:** P1
- **Flow:** every real-backend flow (PASS is 0/14 only because there is no stack)
- **Frontend expectation:** a stack that C5/C6 can point `E2E_STUDIO_URL` at.
- **Requests (nothing else):**
  1. the **official staging/backend base URL** (none exists yet; C0 reports no official host);
  2. an **integration-ready baseline/branch** that contains, together: C3's Management API (`agent/c3-data-prod @ e606465` or its successor, compiled and route-tested by C3) and the V29 run stores (`wire/v29-run-persistence @ 4b1d4aa`, targeted gate green, full regression NOT green, not integrated into `integration/v2`);
  3. the **finalised runtime topology**: which origin serves the published app's `/runtime-config.json`, which origin serves the Data API, and the CORS/cookie model between them (C2's runtime-config proposal is unverified);
  4. the **auth/session assumptions for E2E**: local login enabled on the test stack, a system-admin account supplied through the environment, the Studio same-origin `/api` proxy target, `app.data-platform.enabled=true`, `app.workflow.enabled=true`, `app.workflow.run-store=jdbc`.
- **Evidence:** `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md` §3–4; `tests/e2e-real/run.mjs` guard.
- **Impact:** without 1–4 the suite exits 2 (NOT RUN) and the C5 status cannot go beyond "implementation ready / backend integration pending".
- **C5 workaround:** none that is honest; no fake backend is used anywhere.
- **Blocked test IDs:** all 14 (real-backend result)

## H-C0-06 — what the Mac run needed beyond `integration/v2` (live evidence, 2026-10-06)

- **ID:** H-C0-06
- **Owner:** C0
- **Severity:** P1 (integration)
- **Flow:** all
- **Actual backend behavior:** `integration/v2 @ f894cc6` alone cannot run E2E-06/12 (no C3 Management routes, in-memory run stores). A detached local merge of `wire/v29-run-persistence 5f28adc` + `agent/c3-data-prod e5e69c0` onto it is conflict-free (no common file, no extra migration), applies V1–V29, starts in ~24 s and passes: E2E-01/02/03/10/11/12/13/S1/S2 (Management API: 23 `[api]` + 9 `[ui]` checks of E2E-06). E2E-12 (kill and restart the API, a 45 s WAIT run survives and completes) is the first live proof of V29 restart safety through the product API.
- **Needs from C0 (nothing new beyond H-C0-05):** integrate V29 and C3 Management into the baseline C5/C6 test; document `SECRETS_MASTER_KEY` (without it a credential write is 500 `SECRETS_UNAVAILABLE`) and the static provider env (`DEPLOY_PROVIDER`, `SITES_ORIGIN`, `RENDER_URL`, `RENDER_TOKEN`) in the local-stack docs; Gradle/Kotlin heap (`-Xmx3g`) for a clean `bootRun` on a 16 GB Mac.
- **Evidence:** `docs/parallel/c5/MAC_RUN_2026-10-06.md` §2–§3, `docs/parallel/c5/evidence/mac/`.
- **Blocked test IDs:** none new.

## H-C0-06 — UPDATE 2026-10-07: integration/v2 @ 3333aa7 alone is now sufficient

Verified: `integration/v2 @ 3333aa7` (no merge, `docs/parallel/c5/e2e-stack.sh`) passes every runnable flow (E2E-01/02/03/10/11/12/13 and S1–S9; E2E-06 with 32 `[api]` + 9 `[ui]` checks incl. query/mutation definitions). The manual merge described above is obsolete; the "needs" about `SECRETS_MASTER_KEY`, static provider env and Gradle heap stand.

## H-C0-07 — a local stack cannot reach any data source (new, 2026-10-07)

- **ID:** H-C0-07
- **Owner:** C0 (C3 owns the policy class)
- **Severity:** P2 for the V1 local macOS target (`docs/parallel/V1_LOCAL_TARGET.md`)
- **Flow:** E2E-07 / 08 / 09 (and the "connection test succeeds" path of E2E-06)
- **Actual backend behavior:** every connector target goes through `PublicAddressPolicy` (`PostgresTargetPolicy`, `Gateway.kt:254`): loopback and private addresses are refused (`ADDRESS_BLOCKED` on test, 403 on run) and there is no configuration switch. A Mac running the platform locally has no public PostgreSQL to point at.
- **Impact:** the data runtime cannot be exercised end to end on the local target; E2E-07/08/09 need either a public test database or a deliberate, off-by-default, never-in-production dev allow-list.
- **Suggested contract/fix:** C0 decides: (a) provide a documented public test source, or (b) add an explicit dev-only allow-list (e.g. `app.data-platform.dev-allow-addresses`, refused by `ProductionConfigValidator` in prod) with tests that prove prod still refuses. C5 does not propose how.
- **Blocked test IDs:** E2E-07, 08, 09

## H-C0-08 — C0's own handoff item H3 for C5 (acknowledged, not done in this batch)

Portal URLs and the API mode are `NEXT_PUBLIC_*` build-time values and `nextConfig.ts` falls back to `http://127.0.0.1:8080` for `API_PROXY_TARGET`: one build per environment. C5 owns the fix (runtime config, no loopback fallback in the shipped build) but it is a feature, not part of the stability batch. Listed in NEXT.

## H-C0-09 — the C2 publish contract is not in integration/v2 (2026-10-07)

`fix/c2-v3 @ 8d40218` (publish/rollback/unpublish contract, V30 lease and fencing, `PublishApiContractTests`) does not merge cleanly into `integration/v2 @ ef0d890`: add/add conflicts in `ReleaseService.kt`, `ReleaseCasTests.kt`, `SiteRuntimeConfigTests.kt`, `BATCH2_HANDOFF.md`, content conflicts in `SiteService.kt`, `BLOCKERS.md`, `BOARD.md` (C0 imported an older C2 slice). C5 verified the Studio against a backend built from `8d40218` itself (P01–P09 PASS ×3). Please integrate it (C0's decision which side wins), then C6 reruns `E2E_BASE_REF=integration/v2 docs/parallel/c5/e2e-stack.sh e2e E2E-P01,…,E2E-P09`. The stack also needs `RATE_LIMIT_PUBLISH_MAX` raised (default 10/min/user) for a suite that publishes a lot.

## H-C0-13 — import `agent/c5-web @ ddc130f` and rebuild the portals (2026-10-08)

- **Request (C0):** import `agent/c5-web @ ddc130f` into `integration/v2`, then rebuild Platform / Admin / Studio and restart them. The branch is a fast-forward of the previously pushed `a3f9a4e` (merge of `integration/v2 @ ae0432f` with `824bdb6` as base, one conflict in `tests/browser/README.md`, both kept). `git diff ae0432f agent/c5-web -- backend docs/contracts` is empty.
- **Why now:** the running portals still show the OLD "Tạo công ty" dialog (bare multi-line `<select>` and a loose search box). The redesign (`e632558`: header, company code proposed from the name, searchable person picker with avatars) is only on `agent/c5-web`, so no rebuild of `integration/v2` can show it.
- **Also in this import:** the legacy `POST /admin/workspaces` is gone from the portals (guard `tests/guards/no-legacy-admin-workspaces.mjs` PASS), E2E 401 / CSRF / body-injection checks, `scripts/test-classify.mjs` lists C0/C2-owned tests as external, `docs/parallel/c5/e2e-stack.sh` passes `API_PROXY_TARGET` to the Studio `next start`.
- **Evidence:** `docs/parallel/c5/evidence/mac/e2e-real/2026-10-07-sync-ae0432f/` (SUPER01, ADMIN01, SEC01–03, PL01, AD01, AD02, AD03 PASS on an unpatched ae0432f stack; USER01 BLOCKED = H-C1-04, owner C1); screenshots `docs/parallel/c5/evidence/screenshots/tenant-create/`.
- **Gates before import:** `npm ci`, typecheck (root / packages / apps), unit 278 (277 pass, 1 skip), harness specs (builder 80, datasources 54, release 56, publicdata 44, aiproviders 27, sanity 8, provisioning 39), 3 portal builds.
- **Not done by C5:** the public deployment itself (C0 owns ingress and the public build).
