# C0 review of the C3 Management API proposal (D-C0-28) — 2026-10-06

Reviewed: `docs/parallel/c3/MANAGEMENT_API.md` and the code of `agent/c3-data-prod @ 69ee16f` (base `integration/v2 @ f894cc6`, 7 commits, 25 files, +3047/−39; read from the C3 worktree, nothing was run there). Baseline of this review: `integration/v2 @ a9e07db`.
Result: **CHANGES REQUIRED.** The frozen target is `docs/contracts/v2/management-api.md`. Nothing is mounted, nothing is READY.

## 1. What is approved as proposed

Data-source CRUD, credential routes (metadata only), connection test (always 200, `ok:false` with a stable code), TEST / LIVE binding routes, connector catalogue; workspace-scoped paths; tenant from the workspace; strict body parsing; identical 404 for foreign / unknown; no `credentialRef` in any projection; admin-change throttle; canonical permissions `DATA_SOURCE_VIEW` / `DATA_SOURCE_MANAGE` (+ `APP_EDIT` for bindings) — all exist in C1 `Permission.kt` and in `GatewayAuthorizer.REQUIRED`, so **no C1 change is needed**.

## 2. What changes (the reason for CHANGES REQUIRED)

| # | Finding | Required change |
|---|---|---|
| R1 | The proposal has **no** routes for schema discovery, query definitions or mutation definitions (its §5 says so). The C0 request lists them as project routes (`/projects/{p}/data-queries`, `/data-mutations`). V28 keys `data_queries` / `data_mutations` by `(tenant_id, data_source_id, id)`: a definition belongs to a data source, not to a project. | Contract §3.4 / §3.5: schema and definitions are **data-source-scoped** (`/data-sources/{id}/schema/discover`, `/schema`, `/queries`, `/mutations`). Project-scoped definition routes are not approved. |
| R2 | `PATCH /data-sources/{id}` calls `admin.update` and then `admin.setStatus` as two operations: a failure of the second leaves the first applied (partial update, two versions, two audit rows). | One transaction, all fields validated first, one version bump, and an optional `expectedVersion` (`409 CONFLICT`). |
| R3 | `DataSourceAdminService.delete`: `repository.delete` then `vault.discard`, not atomic; V28 FKs from `data_queries`, `data_mutations`, `source_schemas`, `data_idempotency` have no cascade, so a source with definitions or snapshots cannot be deleted (a 500 or an undefined error today). | Contract §3.1 `DELETE` rules: 409 while bound; 409 while a mutation idempotency row is `RESERVED` / `UNKNOWN`; otherwise definitions, snapshots and finished rows go in the same transaction as the credential. Test it with all of them present. |
| R4 | Error body is `{code,message,requestId}`; the frozen runtime envelope also has `retryable` and `details`. 401 is written by the platform as `AUTHENTICATION_REQUIRED` (not `UNAUTHENTICATED`), CSRF as `403 CSRF_INVALID`. | Contract §4. C0 adds `retryable` / `details` in the wiring layer; C3 only fixes its tests. |
| R5 | `mode` is accepted case-insensitively (`live`, `Test`). | Exactly `TEST` or `LIVE`; anything else `400 INVALID_PARAMS`. Update the tests. |
| R6 | Audit exists for data sources, credentials, bindings, tests; **none** for query / mutation definitions (the routes do not exist). The doc does not say whether a failed audit write blocks the change. | New actions `DATA_QUERY_DEFINITION_CHANGED`, `DATA_MUTATION_DEFINITION_CHANGED`; write and audit are one unit (contract §5). Prove it with a test where the audit sink fails. |
| R7 | The new controllers live in `wiring/` (C0 path) and C3 edited `wiring/DataRuntimeConfiguration.kt`, `wiring/persistence/JdbcCredentialStore.kt`, `JdbcDataSourceRepository.kt`, `JdbcDataSourceSlotBindings.kt` (C0 files). | Accepted as a **proposal diff**: C0 re-reviews every line of those files at import and owns them from then on. C3 makes no further edit to `wiring/**` and `access/**`. |
| R8 | `GET /data-bindings` and the writes require `APP_EDIT` + data-source permission. A workspace EDITOR therefore cannot bind (only WORKSPACE_ADMIN holds `DATA_SOURCE_MANAGE`). | **Not a defect and not changed.** Current C1 policy, recorded in the contract §2. A different rule is a C1 policy request. |

## 3. C3 status: NOT GREEN

Evidence reported for the branch (not re-run by C0): `compileKotlin` / `compileTestKotlin` PASS; `DataSourceManagementTests` 12/12; `ManagementHttpTests` 5/5; `PostgresMutationTests` 28/28; `PostgresWritableIntegrationTests` 23/23; `DataManagementApiTests` **15/16**; `DataWritableE2ETests` **1/2**. The branch has a later commit `69ee16f` ("fix credential lifecycle assertions"); C0 does not count it as green until C3 reports the rerun.

### C3 MUST FIX before C0 will sync

1. Make the two failing tests pass (`DataManagementApiTests`, `DataWritableE2ETests`) by fixing the fixture or the code, not by loosening an assertion that guards a secret, a tenant or a status; rerun the targeted set to GREEN and report the numbers.
2. Implement schema discover / read, query definitions, mutation definitions exactly as contract §3.4 / §3.5 (strict parsers, safe projections, summary vs full definition, `expectedVersion`).
3. R2, R3, R5, R6 above.
4. Negative tests (real PostgreSQL, Testcontainers, no mock of the guard): foreign tenant → 404; foreign workspace → 404; non-member → 404; member without `DATA_SOURCE_MANAGE` → 403 on every write route and on schema routes; EDITOR cannot bind; non-USER actor denied; unknown / authority key in every body → 400; flag off → 404; binding a source of another workspace → 404; TEST binding is not used by a LIVE run and vice versa (`DATA_SOURCE_UNBOUND`); delete while bound → 409; **no-secret sweep**: after a create / put / rotate / test / discover with a recognisable secret string, that string appears in no response body, no audit row, no log line of the test run.
5. Send C0 the exact branch, head SHA, file list (marking which are under `wiring/**`), the test names and counts, and the `compileKotlin compileTestKotlin` result — rebased or cherry-picked onto the current `integration/v2` HEAD (`a9e07db` or newer). No merge of main; do not mount anything on `integration/v2`; keep untracked files (`c3_final_gate.sh`, `app/*.css`) out of every commit.

## 4. C0 integration plan (runs only after C3 reports GREEN)

1. Sync the C3 branch onto the latest `integration/v2` (cherry-pick, history kept; no squash) in a C0 worktree; conflicts resolved by owner, contract conflicts stop the import.
2. Review the `wiring/**` and `wiring/persistence/**` lines C3 touched (R7); add the envelope fields (`retryable`, `details`) in the wiring layer; confirm the route table equals contract §3 and nothing else is mounted (a test that lists every mapping under `data-sources`, `data-bindings` and fails on an unknown one; a test that `/api/v1/data/mutate` is 404).
3. Run the C1 negative authorization suite and the existing `AuthSecurityTests`, `SecurityConfiguration` tests.
4. Run the C3 and C0 management integration tests (including the ones of §3 item 4) against Testcontainers.
5. `compileKotlin compileTestKotlin`, then `clean test --rerun-tasks` (backend full regression) on the synced branch and again on `integration/v2` after the import.
6. Record the result in `DECISIONS.md` (D-C0-29 or later), flip `management-api.md` status to INTEGRATED, update B-C0-W-03 / W-04, then — and only then — tell C5.

## 5. C5 status

**Management API: NOT READY. C5 browser production API: BLOCKED.** C5 may write client adapters and types **against `docs/contracts/v2/management-api.md`**, behind the `NOT_READY` panels, with no call to a route; it may not invent a route, mock the API and call the result a real E2E, send a `tenantId`, or call any raw DataGateway mutation. The contract file is a target, not an advertisement.
