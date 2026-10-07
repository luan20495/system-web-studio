# C5 → C3 handoff — C3 — data / database / connector

From C5 (Studio/Frontend), baseline `integration/v2 @ f894cc6`. C5 changed nothing owned by C3. Nothing here was run against a live backend: evidence is the code, the BLOCKERS rows and the suite's blocker records.

## H-C3-01 (B-C0-W-04)

- **ID:** H-C3-01 (B-C0-W-04)
- **Owner:** C3
- **Severity:** P1
- **Flow:** Mutation with a real side effect
- **Frontend expectation:** A LIVE `CREATE_RECORD` action writes a row.
- **Actual backend behavior:** Production connectors (postgres, rest) are read-only: LIVE data mutations answer `MUTATION_UNSUPPORTED`.
- **Endpoint/event:** `POST …/app-runtime/actions/{id}/execute` (LIVE)
- **Request:** `{mode:"LIVE", idempotencyKey}`
- **Response/status:** `status:"FAILED"`, `error.code:"MUTATION_UNSUPPORTED"`
- **Reproduction:** Execute a mutating action against a postgres/rest source in LIVE.
- **Evidence:** BLOCKERS B-C0-W-04; `e2e-09` blocker.
- **Impact:** No mutation success path exists to test.
- **Suggested contract/fix:** Implement write support in at least one production connector, behind DATA_MUTATE.
- **C5 workaround:** TEST mode returns `WOULD_RUN`, shown as such (E2E-S1).
- **Blocked test IDs:** E2E-09

## H-C3-02 — Management API: documentation vs controller (`agent/c3-data-prod @ e606465`)

- **ID:** H-C3-02
- **Owner:** C3
- **Severity:** P2
- **Flow:** Studio "Dữ liệu" panel against the Management API
- **Frontend status:** client + panel are built against `MANAGEMENT_API.md` and `DataManagementControllers.kt`. **Spring compile, route tests and a live call are NOT verified (by C3 or C5).** Details: `API_AUDIT_F894CC6.md` §4.
- **Mismatches / missing semantics (none silently normalised in the client):**
  1. the error table omits `INVALID_CREDENTIAL` (400) and `SECRETS_UNAVAILABLE` (`GatewayProblems.status` maps it to 500 by default) — please document both and the intended status for `SECRETS_UNAVAILABLE` (503?);
  2. `PATCH` applies name/config first and status second, non-atomically — please state it or make it atomic; the UI re-reads after any error;
  3. the bindings list silently filters entries by per-source read permission — please state it (a user can see fewer bindings than exist);
  4. runtime routes answer `FORBIDDEN`, management routes `PERMISSION_DENIED` — one code would simplify clients (both map to "forbidden" today);
  5. precedence between `dataSources[].sourceRef` in the document and the `data_source_bindings` table is unspecified — which one does the runtime use?
  6. request an answer shape for "management disabled" (today: a plain 404 with no code when `app.data-platform.enabled=false`, indistinguishable from an unknown route; see H-C0-04).
- **Request:** confirm the document is authoritative after compile + route tests; tell C5 when the routes are in an integration branch.
- **C5 workaround:** `explainManagementError` maps every case above generically; the panel shows NOT_READY (flag off) on a code-less 404/501.
- **Blocked test IDs:** E2E-06 (needs the routes mounted), E2E-07, E2E-08

## H-C3-03 — no approved-query / mutation management endpoint (route family incomplete)

- **ID:** H-C3-03
- **Owner:** C3
- **Severity:** P1
- **Flow:** define a query/mutation from Studio, run it in TEST and LIVE
- **Frontend expectation:** list/create/update approved queries and mutations for a workspace, with the same permission, isolation and error model as the sources.
- **Actual:** `MANAGEMENT_API.md` §5 states queries/mutations are seeded through repositories; there is no HTTP route.
- **Impact:** the Studio cannot create or edit a server-side query; the Test panel can only run queries somebody seeded. E2E-07 needs an operator-seeded project; E2E-09 has no approved mutation to run.
- **Suggested fix:** a route family `…/queries`, `…/mutations` (read-only SQL checked server-side, parameter schema, `dataSourceRef`), or an explicit decision that queries are never authored over HTTP.
- **C5 workaround:** none; the wizard's query step says no slot is declared and does not fabricate a query.
- **Blocked test IDs:** E2E-06 (query half), E2E-07, E2E-09

## H-C3-04 — Management API live verification (positive result, 2026-10-06)

- **ID:** H-C3-04
- **Owner:** C3
- **Severity:** info
- **Result:** on a detached merge `integration/v2 f894cc6` + `wire/v29-run-persistence 5f28adc` + `agent/c3-data-prod e5e69c0` (`app.data-platform.enabled=true`, `SECRETS_MASTER_KEY` set), Spring started, all Management routes mounted (`/v3/api-docs`), and E2E-06 ran **23 `[api]` and 9 `[ui]` checks, 0 failed**: connector catalogue (postgres AVAILABLE, planned ones not), create → 201 with no secret and no credential reference in the view, credential metadata = key names only, duplicate name 409, secret in config 400, identity field 400 `INVALID_PARAMS`, planned connector 501, unknown type 422, tenant isolation (B reads A's id → 404, foreign and unknown answers identical, B lists A's workspace refused), VIEWER create/patch 403 and nothing changed, test connection of an unreachable host → 200 `ok:false` with a documented code and no credential in the text, DISABLED 409, TEST binding put/list, project VIEWER 403 on bindings, delete-while-bound 409, Studio panel (source listed, credential "Đã cấu hình", test result FAILED + code, no secret anywhere in the DOM).
- **Not exercised:** "connection test succeeds" (needs a reachable public PostgreSQL with a valid certificate; the local database is correctly refused as `ADDRESS_BLOCKED`), LIVE binding, writable PostgreSQL through HTTP, queries, mutations (H-C3-03 stands).
- **Evidence:** `docs/parallel/c5/evidence/mac/E2E_ALL_final.log`, `e2e-real/report-2026-10-06T14-14-09-556Z.json`.

## H-C3-04 — UPDATE 2026-10-07: query/mutation definitions live-verified on integration/v2 @ 3333aa7

E2E-06 now also exercises management-api.md §3.5 against the live backend: list `{items:[]}` 200, create 201, read back 200, a refused definition 400 `INVALID_QUERY`, an authority key 400 `INVALID_PARAMS`, a mutation against a non-writable source 422 `READ_ONLY_VIOLATION`, tenant isolation 404, VIEWER 403, delete 204 — 32 `[api]` and 9 `[ui]` checks, 0 failed, on the frozen error envelope. Still not exercised (needs H-C0-07 / H-C2-02): running a query through a binding, writable PostgreSQL over HTTP, schema discovery.
