# C3 - Final data / database / query / mutation hardening (C7 batch)

Base `integration/v2 @ fad4a7b4356fecd016e707423d426d1eb4b45c4d`. Branch `agent/c3-data-hardening`. **Test-only change**: no production source, no migration and no `application.yml` change (one existing test, `PostgresConnectorIntegrationTests`, got a polling wait instead of a 300 ms sleep). Every number below was produced on a developer Mac shared with other agents (load average 20-60, 100+ containers on the Docker VM): absolute timings are inflated and noisy, the SHAPE and the plans are the evidence.

## 1. Verdict

| Area | Result | Where it is proved |
|---|---|---|
| Data source (create, edit metadata, write-only credential, test connection, TEST/LIVE binding, tenant + workspace isolation) | PASS | `DataManagementApiTests`, `ManagementHttpTests`, `DataManagementLifecycleTests`, `DataManagementNegativeSecurityTests`, `DataSourceScopeTests`, `DataSourceAdminTests`, `DataSourceManagementTests`, `DataWritableE2ETests`, `DataRuntimeMigrationTests` |
| Query (create, execute, parameters, validation, errors, large result, pagination, binding) | PASS | `DataDefinitionManagementApiTests`, `GatewayTests`, `DataApiTests`, `DataRuntimeLiveApiTests`, `PostgresConnectorIntegrationTests`, **new** `DataQueryScaleOnPostgresTests` (1,000,000 rows) |
| QUERY_EXECUTE | PASS | `AccessAdaptersTests`, **new** `DataPermissionMatrixTests` (real HTTP matrix) |
| Mutation (create / update / delete where supported, idempotency, timeout, ambiguous result, no unsafe retry) | PASS | `PostgresWritableIntegrationTests`, `PostgresMutationTests`, `IdempotencyTests`, `JdbcIdempotencyStoreTests`, `DataRuntimeLiveApiTests`, **new** `DataMutationOnPostgresTests` (production gateway + production JDBC stores on real PostgreSQL) |
| DATA_MUTATE | PASS | `AppRuntimeApiTests`, **new** `DataPermissionMatrixTests` |
| Dynamic organization persistence | PASS | `PostgresOrganizationConformanceTest` (C1 kit 13/13), `OrgSchemaTests`, `OrgTreeTests`, `OrgStructuralLockTests`, `OrgRaceTests`, `OrgCountsDirectoryTests`, `OrgV32FlywayTests`, `OrganizationRulesOnPostgresTests`, `OrganizationOnPostgresApiTests`, `OrganizationIntegrationWiringTests` (C0), **new** `OrgReloadPersistenceTests` (new pool + real server restart) |
| Tenant isolation | PASS | raw-SQL composite-FK tests on org and V28 tables, gateway / runtime / HTTP cross-tenant, cross-workspace and cross-project tests, **new** structure audit (every cross-table FK of the 13 tables carries `tenant_id`) |
| Migration (Flyway, additive) | PASS | `OrgV32FlywayTests` (clean V1->V32, upgrade V30->V32, checksum, duplicate guard), `FlywayHarnessTests`, **new** no-destructive-statement check on V28 / V29 / V32 |
| Performance | PASS | `ORGANIZATION_FINAL_BENCHMARK.md`, `data-query-scale` and `data-plan-audit` snapshots below |

## 2. What was added (tests only)

| Test class | What it proves that was not proved before |
|---|---|
| `DataMutationOnPostgresTests` (5) | The idempotency reservation is COMMITTED and visible to another connection BEFORE the external write starts; ambiguous failure persists UNKNOWN and is never retried; definite refusal frees the key; 32 simultaneous requests with one key write exactly once; **a pool of 3 serves 12 simultaneous external writes and 12 external reads** (the application pool is not held across an external call); characterisation of the caller rule (finding F-1). |
| `DataQueryScaleOnPostgresTests` (4) | A 1,000,000-row table: `maxRows`, the page and the byte cap bound the result without scanning the table; pages are contiguous at offsets 0 .. 900,000 and empty past the end; a runaway sort is stopped by the statement timeout and leaves no session; 20 concurrent readers. |
| `DataSchemaAndPlanAuditTests` (6) | Structure of the 13 data + organization tables, FK index coverage, additive-only migrations, and `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` of the statements the adapters run on 20,000 data sources / 200,000 queries / 100,000 mutations / 500,000 idempotency rows / 10,000 bindings / 1,000,000 audit events. |
| `DataPermissionMatrixTests` (3) | QUERY_EXECUTE and DATA_MUTATE over real HTTP for every kind of caller (matrix in section 4). A denied call never reaches the connector, never reserves a key, never writes. |
| `OrgReloadPersistenceTests` (1) | Everything written through the organization repositories is identical (row counts + content hashes of the 6 tables, tree, counts, directory, primaries) after a new pool AND after a real `docker restart` of PostgreSQL; versions (CAS) and the cycle guard keep working on the reloaded data. |

## 3. Findings

| # | Severity | Finding | Recommendation / owner |
|---|---|---|---|
| F-1 | P2 (latent, no current path) | The idempotency reservation (`JdbcIdempotencyStore.begin`, autocommit) is only a fence while it commits on its own. A caller that wrapped `DataGateway.mutate` in its own Spring transaction and rolled it back would lose the reservation while the external write stays, and a retry would write twice (proved by the characterisation test). Today no caller wraps it (`ActionDataPortAdapter` is the only caller; no `@Transactional` / `TransactionTemplate` on that path; `DataRuntimeLiveApiTests` ambiguous-retry test would fail otherwise). | **C0** (owner of `wiring/persistence`): add a fail-closed guard `check(!TransactionSynchronizationManager.isActualTransactionActive())` at the top of `JdbcIdempotencyStore.begin`, with a test. `REQUIRES_NEW` is NOT recommended (a second pooled connection per request can exhaust a pool of 10). |
| F-2 | P2 | `PageSpec.MAX_PAGE_OFFSET = 1,000,000` with `LIMIT ? OFFSET ?` is O(offset) on the TARGET database: measured 109-222 ms at offset 100,000 and 1.1-2.4 s (p95 up to 4.3 s) at 900,000 on a 1M-row table; on a heavily loaded host a probe at the cap exceeded the 10 s default timeout and answered TIMEOUT (a safe, typed failure). Each such request holds a target connection for seconds. Bounded by the statement timeout and the rate limits, never unbounded. | C3 / C1 contract: lower the offset cap (100,000 is ~0.2 s) or offer a keyset cursor. No change made here (contract value). |
| F-3 | P3 | `JdbcCredentialActorLookup` reads `audit_events` by `resource_type` + `resource_id` + payload fields; there is no index on them, it relies on `audit_events_action_idx` (BitmapOr on two rare actions). Measured 7.5 ms on 1,000,000 events. Becomes slower only if DATASOURCE_CREATED / CREDENTIAL_ROTATED events become very numerous. | Watch; an index on `(resource_type, resource_id)` is the fix if it ever shows. |
| F-4 | P3 | Organization FKs `employee_organization_units_unit_fk`, `employee_positions_{membership,position,grade}_fk` are served only by partial (`WHERE active`) indexes, so a HARD delete of a unit / position / grade / membership would scan the child table. The application never hard-deletes them (soft archive, RESTRICT). | None needed for V1; V32 is immutable - a later migration could add full indexes if a purge job appears. |
| F-5 | P2 (test infrastructure) | `IntegrationTestBase` starts RabbitMQ / Redis / MinIO containers in a static initializer. On a loaded Docker host (100+ containers, load 40-60) one startup timeout turns EVERY Spring test class into `NoClassDefFoundError`: a run produced 336 false failures (migration 34/34, isolation 10/10, wiring 238/329 ...). The same classes pass when the host is quieter. | **C0 / C7**: run the gate on a quiet machine or retry container startup; do not read a mass `NoClassDefFoundError` as a product failure. |
| F-7 | P3 (test, C0) | `OrganizationIntegrationWiringTests` "non-structural operations never wait for the structural lock" asserts a 2.5 s wall-clock for nine HTTP calls; it failed once at load ~35 and passes on a quieter host (9/9). A wall-clock threshold is a load detector, not a lock detector. | C0: assert on the lock itself (e.g. `pg_locks` / each call faster than the lock timeout individually) instead of total time. |
| F-6 | P3 (test) | `PostgresConnectorIntegrationTests."connections are closed after every call"` slept 300 ms before counting server sessions and failed once under load. Fixed in this branch (poll up to 10 s). `WorkflowG3RecoveryTests` G3-03 (C4) failed once under load and passed on rerun (8/8). | C4 to harden G3-03 timing. |

**P0: none. P1: none.** No current code path was found that loses isolation, duplicates a write, leaks a credential, or breaks a migration.

## 4. Evidence

### QUERY_EXECUTE / DATA_MUTATE over HTTP (real LIVE data path, published release, V28 binding)
| Caller | LIVE query | LIVE data action |
|---|---|---|
| WORKSPACE_ADMIN | 200 | 200 (one write, one idempotency row) |
| project EDITOR | 200 | **403** (no DATA_MUTATE) |
| project VIEWER | **403** | 403 |
| workspace EDITOR without a project role | 404 | 404 |
| member of ANOTHER workspace | 404 | 404 |
| signed-in user of no workspace | 404 | 404 |
| anonymous | 401 | 401 |
Every refused call: connector not reached, no idempotency row, no write. Cross-workspace path forgeries (B's workspace with A's project, A's project with B's admin): 404.

### Large result behaviour (PostgreSQL connector, 1,000,000 rows) - `data-query-scale.md`
| case | rows returned | truncated | median ms | p95 ms |
|---|---|---|---|---|
| SELECT * of 1M rows, maxRows 500 (no ORDER BY) | 500 | true | 98.3 | 188.7 |
| ORDER BY id, maxRows 200 | 200 | true | 45.6 | 71.6 |
| indexed filter kind = :k, page 100 | 100 | true | 43.5 | 89.4 |
| byte cap 64 KiB over ~230-byte rows | 274 | true | 63.4 | 124.2 |
| 20 concurrent paged queries (indexed filter) | 50 each | - | 493 total | - |
| page of 100 at offset 0 | 100 | true | 91.2 | 120.2 |
| page of 100 at offset 100 | 100 | true | 118.0 | 191.2 |
| page of 100 at offset 9900 | 100 | true | 70.4 | 115.6 |
| page of 100 at offset 100000 | 100 | true | 109.2 | 155.1 |
| page of 100 at offset 900000 | 100 | true | 1065.3 | 1590.8 |
| page of 100 at the largest allowed offset 1000000 | 0 | false | 1458.2 | 1598.8 |
| page of 10,000 (the largest page, source maxRows 10000) at offset 500,000 | 10000 | true | 857.3 | 1215.0 |
| runaway ORDER BY md5(...) over 1M rows, timeoutMs 500 | - | - | 662 | - |

The 1,000,000-row table is read with `maxRows` 200-500 in 40-100 ms (median) because `LIMIT` stops the scan; the page / byte caps hold; a deliberately runaway sort is cut at the statement timeout (0.66 s for a 0.5 s limit) and leaves no session behind. Deep OFFSET is the only expensive shape (finding F-2).

### Plans (seeded schema, `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`) - `data-plan-audit.md`
Every adapter statement is an index scan / index-only scan; the idempotency purge walks `data_idempotency_expiry_idx`; the runtime binding ownership join is three index lookups (4 ms); latest-schema snapshot, delete guards and definition lookups < 25 ms on 200,000 / 100,000 rows. Full table in the snapshot file.

### Organization (final schema, production repositories, integration head)
Wall-clock median / p95 (ms), 2,000 units, depth 64, 10,000 employees, 12,824 memberships, 8,885 employee positions, ONE run at load ~21-30 (full tables: `ORGANIZATION_FINAL_BENCHMARK.md`):

- children of the 301-child node: 12.50 / 28.78
- ancestors, depth 64: 4.02 / 8.94
- large subtree (1,602), C1 seam: 8.91 / 20.82
- full tree (2,000, bounded): 54.24 / 78.37
- directory first page: 14.39 / 19.69
- directory deepest page (offset 9,900): 37.71 / 75.62
- directory name search: 44.14 / 84.91
- directory org filter, large subtree: 91.51 / 164.50
- move of a 151-unit subtree (lock + cycle check + CAS): 7.12 / 14.42
- 100 mixed ops over 10 tenants, pool 10: 133.34 ops/s, p95 528.86 ms, lock timeouts 0, deadlocks 0, outcomes {CYCLE=1, DUPLICATE:code=1, OK=98}
- 100 structural moves in one tenant, pool 10: 71.12 ops/s, p95 1360.68 ms, lock timeouts 0, deadlocks 0, outcomes {CYCLE=1, DUPLICATE:code=13, OK=86}
- 50 A<->B pairs (100 conflicting moves), pool 32: 143.79 ops/s, p95 656.17 ms, lock timeouts 0, deadlocks 0, outcomes {CYCLE=50, OK=50}
- graph acyclic in every tenant after every scenario; C0 / C7 targets (children p95 < 200 ms, search page p95 < 300 ms) met with a wide margin.

### Transaction boundaries and pool
- Data gateway: reservation, completion and release are single autocommit statements; the external call runs with NO application connection held (pool 3 vs 12 simultaneous external calls); a store failure after a successful write never turns the success into an error (`IdempotencyTests`).
- Management writes: one `DataTransactions` unit per change with its audit row (`DataManagementLifecycleTests` A-E, rollback on step or audit failure).
- Organization: one `TransactionTemplate` (REQUIRED, never REQUIRES_NEW) per write; structural lock + `SET LOCAL lock_timeout` inside it; bounded waits -> `503 ORG_STRUCTURE_BUSY`.
- Pool: Hikari 10 (`DB_POOL_MAX`), `connection-timeout` 5 s; with 100 simultaneous threads the pool is the first queue (org concurrency report), 0 deadlocks.

### Migrations
V28 (data runtime), V29 and V32 contain no DROP / TRUNCATE / DELETE / UPDATE / destructive ALTER (checked by test). The older DROP CONSTRAINT / UPDATE statements in V3..V30 are historical, already applied and immutable (constraint widening and backfills only). Flyway on the repository's own `db/migration`: clean V1->V32 and upgrade V30->V32 pass, V32 checksum identical on both paths.

## 5. Regression run (JDK 21, real PostgreSQL / Redis / RabbitMQ / MinIO via Testcontainers)

A single `clean test` of the whole suite could not be completed reliably: the Docker host carried 100+ containers from other agents and `IntegrationTestBase` (static container start) failed to start in several attempts, turning whole groups of Spring classes into `NoClassDefFoundError` (finding F-5). The suite was therefore run in package groups, each in its own JVM, a group being repeated until its containers started; every group ended green:

| Group | Tests | Result |
|---|---|---|
| tenancy, identity | 180 | 0 failures |
| publish | 185 | 0 failures |
| app, project, version, schema | 170 | 0 failures |
| ai, prompt, template, asset, component, code | 152 (3 skipped: OpenRouter live) | 0 failures |
| admin, audit, settings, member, maintenance, common, runtime, access, integration, logic | 540 | 0 failures |
| wiring, migration, isolation, organization | 422 | 0 failures (3rd attempt; attempts 1-2 died in container start) |
| data (includes the 5 new classes and the C0 organization wiring tests) | 557 | 2 failures in the first pass (a deep-offset probe past the 10 s default timeout on a loaded host, fixed; the C0 timing assertion F-7, green on rerun); the three affected classes then pass 19/19 |

Production source is identical to `integration/v2 @ fad4a7b`; this branch adds tests and one test-wait fix only.
