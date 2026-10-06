# C3 · Production writable PostgreSQL connector (B-C0-W-04) and the C3 production-readiness handoff

Status: implemented on branch `agent/c3-data-prod` (off `integration/v2 @ f894cc6`). **Not yet declared production-ready: the Mac gate has not run** (§6, §8).
Audience: **C0** (runtime/wiring), **C4** (action/workflow semantics), **C5** (see `MANAGEMENT_API.md`), **C6** (verification). No C4 workflow semantics, no C1 policy, no C5 UI and no schema (migration) was changed.

## 1. What it is

`PostgresConnector` now has a real `mutator()`: approved mutations (`MutationDefinition`, never client SQL) run as **INSERT / UPDATE / DELETE** against a customer PostgreSQL. It is off by default: a data source has to be configured `writable:true`; otherwise the very same mutation answers `READ_ONLY_VIOLATION` and nothing is sent. Queries and discovery keep their read-only session whatever `writable` says.

Everything that protected the read-only connector still applies to a write session: public addresses only (platform databases refused), TLS `verify-full` mandatory, pinned already-checked addresses, no superuser / `CREATEROLE` / `CREATEDB` / `REPLICATION` / `BYPASSRLS` / `pg_*` member roles (`ROLE_TOO_PRIVILEGED`, before the statement is sent), connect/login/socket/statement/lock timeouts, credential only as driver properties.

Config keys added (`PostgresConnectorConfig`): `writable` (`"true"`/`"false"`, default false) and `maxAffectedRows` (1…100000, default 1000). Descriptor: capabilities `DISCOVERY`, `QUERY`, `MUTATION`; credential keys `username`, `password`.

## 2. Mutation target grammar (`MutationDefinition.target`, ≤ 200 characters)

```
[schema.]table [key=p1[,p2…]] [returning=col1[,col2…]]
```

| kind | statement | rules |
|---|---|---|
| `CREATE` | `INSERT INTO "s"."t" (supplied params) VALUES (?, …) [RETURNING …]` | no `key` |
| `UPDATE` | `UPDATE "s"."t" SET <supplied non-key params> WHERE key = ? [AND …] [RETURNING …]` | `key` required, declared, supplied; at least one column to set |
| `DELETE` | `DELETE FROM "s"."t" WHERE key = ? [AND …] [RETURNING …]` | `key` required; no parameter other than the keys |
| `SUBMIT` | – | `MUTATION_UNSUPPORTED` (not a database operation) |

A parameter's **name is its column name** (`^[a-z][A-Za-z0-9_]{0,39}$`). SQL text is built only from identifiers validated against a strict pattern and always double-quoted; the schema must be one of the data source's `schemas`. **Every value is a bound parameter** — there is no code path that writes a value into SQL text, and no log, exception or `toString` carries a value or the SQL. UPDATE/DELETE without a key (would touch the whole table) cannot be expressed. Optional params that are not supplied are left out (INSERT → column default; UPDATE → column untouched). JSON `null` cannot be bound (see §7).

## 3. Runtime contract (for C0 / C4)

**Interface (unchanged SPI):** `DataConnector.mutator(): MutationExecutor`; `MutationExecutor.execute(MutationExecRequest(definition, params, idempotencyKey), DataSourceRef, ResolvedCredential): MutationOutcome(affected: Long?, output: JsonNode?)`, failure = `ConnectorFailure(code, fixed message)`.

**Result:** `affected` = rows changed (with `returning=` = rows returned, capped by `maxAffectedRows`); `output` = the **first** returned row as a JSON object (column → value), or `null` without `returning=`. UPDATE/DELETE that match nothing succeed with `affected = 0`. The gateway answer is `{operation, kind, affected, replayed, output}`.

**Transaction:** one connection, one explicit transaction, one statement, **one `commit()`**. A statement that would change more than `maxAffectedRows` is rolled back and refused (`MUTATION_REJECTED`). Whatever is uncommitted is rolled back before the connection is closed; no connection survives the call.

**Timeouts:** `timeoutMs` (500–30000, default 10000) drives connect/login/socket timeouts (whole seconds, rounded up), the server-side `statement_timeout` (ms), `idle_in_transaction_session_timeout`, and the JDBC query timeout; `lock_timeout` is 2 s.

**Outcome semantics (frozen `data-runtime.md` §4b, nothing new on the wire).** The connector only uses existing `FailureCodes`. A failure is **definite** (nothing applied) only when the server itself said so *or* the connector refused before sending; everything the connector cannot prove is **ambiguous**:

| Group (task wording) | Code thrown | Source | Applied? | Idempotency key (gateway) | C4 action error · retryable |
|---|---|---|---|---|---|
| VALIDATION_ERROR | `INVALID_PARAMS` | missing key, nothing to set, unbindable value, value out of range (SQLSTATE 22xxx) | no | released | same code · false |
| VALIDATION_ERROR | `INVALID_CONFIG` | bad target, schema not configured, key not declared, DELETE with non-key param, missing key in target | no | released | same code · false |
| AUTHENTICATION_FAILED | `AUTH_REJECTED` | 28P01 / 28000 at connect | no | released | same code · false |
| PERMISSION_DENIED | `PERMISSION_DENIED` | 42501: role lacks the privilege (also C1 denial before the connector) | no | released | same code · false |
| CONSTRAINT_VIOLATION | `MUTATION_REJECTED` | 23xxx unique / FK / NOT NULL / check / exclusion; fixed class message, never the constraint name or value | no | released | `MUTATION_REJECTED` · **false** |
| TRANSIENT_FAILURE | `MUTATION_REJECTED` (message says "transient conflict, lock or resource limit") | 40001, 40P01, 55P03, 55006, 53xxx: the server aborted the transaction | no | released | `MUTATION_REJECTED` · false (the frozen contract never makes it retryable) |
| (config) | `READ_ONLY_VIOLATION`, `ROLE_TOO_PRIVILEGED`, `TENANT_MISMATCH`, `MUTATION_UNSUPPORTED`, `MUTATION_REJECTED` (row cap) | refused before the statement / rolled back | no | released | same code · false |
| CONNECTION_FAILED | `CONNECT_FAILED` | connection refused/unreachable at connect, or the connection is lost while the statement or the commit runs | **unknown** | **kept as UNKNOWN** | `IDEMPOTENCY_OUTCOME_UNKNOWN` · false |
| CONNECTION_FAILED (pre-send) | `HOST_UNRESOLVED`, `ADDRESS_BLOCKED`, `TLS_FAILED` | before any connection is usable | no | released | same code · false |
| TIMEOUT | `TIMEOUT` | socket timeout or 57014 (statement timeout) | **unknown** | **kept as UNKNOWN** | `IDEMPOTENCY_OUTCOME_UNKNOWN` · false |
| AMBIGUOUS_RESULT | `TIMEOUT` / `CONNECT_FAILED` / `QUERY_FAILED` | anything after the statement was sent that the connector cannot classify | **unknown** | **kept as UNKNOWN** | `IDEMPOTENCY_OUTCOME_UNKNOWN` · false |
| INTERNAL_CONNECTOR_ERROR | `INTERNAL` | unexpected exception inside the executor (message never logged) | **unknown** | **kept as UNKNOWN** | `IDEMPOTENCY_OUTCOME_UNKNOWN` · false |

`retryable` is **never** true for anything the connector throws; only `IDEMPOTENCY_IN_PROGRESS` and `RATE_LIMITED` (produced by the gateway, not the connector) are retryable, exactly as `DataWriteErrors.forWrite` already maps. A TLS error in the middle of a statement is deliberately `CONNECT_FAILED` (ambiguous), not `TLS_FAILED` (which would release the key). A retry of an UNKNOWN key answers 409 `IDEMPOTENCY_OUTCOME_UNKNOWN` without sending anything (proved in `DataWritableE2ETests` with a non-transactional sequence that counts real attempts).

**Idempotency assumptions:** enforced by the gateway store (`data_idempotency`, V28), not by the connector. The gateway reserves the key before `execute`, stores `DONE` + affected + bounded output afterwards, keeps `UNKNOWN` after an ambiguous failure, releases it after a definite one. A replay never reaches the connector. The connector receives `idempotencyKey` but does not use it (a customer table has no place to keep it); two *different* operation keys with the same payload each write — business uniqueness is the customer's constraint (which is exactly what turns a duplicate into a clean `MUTATION_REJECTED`).

## 4. Role to give the data source (operator checklist)

A dedicated login role: `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`, member of no `pg_*` role; `GRANT USAGE ON SCHEMA`; `GRANT SELECT, INSERT, UPDATE, DELETE` on **only** the tables concerned (SELECT is needed for `WHERE` and `RETURNING`); `GRANT USAGE` on the sequences of `serial`/`identity` columns. Then `POST …/test`: `ok:true` with no warning means it can write; a warning says it cannot (or, on a read-only source, that it can).

## 5. Handoffs

**C0 (runtime / wiring).**
1. `RuntimeResponses.status` maps every connector code that is not in its list to **HTTP 500** — including deterministic, definite failures such as `AUTH_REJECTED`, `PERMISSION_DENIED`, `DISABLED`, `READ_ONLY_VIOLATION`, `INVALID_PARAMS`, `INVALID_CONFIG`, `ROLE_TOO_PRIVILEGED`. The body is correct (`status:"FAILED"`, `error.code`, `retryable:false`); only the status is misleading for monitoring and clients. Suggested: 422/403/409 as `GatewayProblems.status` does. Not changed here (C0's file).
2. `BLOCKERS.md`: B-C0-W-03 and B-C0-W-04 can move to "implemented on `agent/c3-data-prod`, Mac gate pending" — flip to CLOSED only after the Mac results of §6. Not edited by C3 (shared file).
3. Query/mutation definition management endpoints still do not exist (§7); `DataRuntimeConfiguration.kt` KDoc about "read-only production connectors" was corrected.
4. Test wiring note: `DataWritableE2ETests` replaces the connector registry with a `@Primary` bean holding a real `PostgresConnector` (`JdbcPgConnectionFactory(enforceTls = false)`, private-host allow-list). Do not also expose a `PostgresConnector` as a `DataConnector` bean (duplicate type).

**C4 (action/workflow).** No change requested and none made. Facts to rely on: every failure the PG connector can throw is mapped by the existing `DataWriteErrors.forWrite` to `MUTATION_REJECTED` (definite, not retryable), a NOT_EXECUTED code (definite, not retryable) or `IDEMPOTENCY_OUTCOME_UNKNOWN` (ambiguous, not retryable, no `onError` chain, no compensation) — see the table in §3. A `TRANSIENT_FAILURE` that C4 could retry automatically does **not** exist: transient conflicts are `MUTATION_REJECTED`. If C4/C0 want a retryable transient code, it needs a new `FailureCodes` value, an entry in `DefaultDataGateway.NOT_EXECUTED`, a branch in `DataWriteErrors`, a `runtime-api.md` status and a C4 contract change — a decision for C0/C4, not made here.

**C5.** `MANAGEMENT_API.md` (endpoints, shapes, types, credential metadata, test connection incl. `warnings`, bindings, error codes). Runtime action errors the UI may now see from a writable source: `MUTATION_REJECTED` (a constraint said no — show "not accepted", nothing was saved), `IDEMPOTENCY_OUTCOME_UNKNOWN` (409: do not retry; tell the user to check the data), `AUTH_REJECTED`, `READ_ONLY_VIOLATION`, `DISABLED`, `PERMISSION_DENIED`.

**C6.** §6.

## 6. Verification: commands, expected results, scope

Run from `backend/` on the Mac (Docker running; the integration tests start PostgreSQL/Redis/MinIO/RabbitMQ with Testcontainers; `ShopDb` starts a second PostgreSQL).

```bash
cd backend
./gradlew compileKotlin compileTestKotlin --console=plain                       # expect BUILD SUCCESSFUL (the wiring and Spring tests were NOT compiled before this run)

# 1. no-Docker unit tests of this batch  (expect all pass: 12 + 5 + 28 tests)
./gradlew test --tests 'com.systemwebstudio.data.datasource.DataSourceManagementTests' \
               --tests 'com.systemwebstudio.data.gateway.ManagementHttpTests' \
               --tests 'com.systemwebstudio.data.datasource.postgres.PostgresMutationTests' --console=plain

# 2. real PostgreSQL connector, Spring Management API, full mutation E2E  (expect all pass: 23 + 16 + 2 tests)
./gradlew test --tests 'com.systemwebstudio.data.datasource.postgres.PostgresWritableIntegrationTests' \
               --tests 'com.systemwebstudio.wiring.DataManagementApiTests' \
               --tests 'com.systemwebstudio.wiring.DataWritableE2ETests' --console=plain

# 3. C3 regression (data + wiring + the connector it extends + isolation)
./gradlew test --tests 'com.systemwebstudio.data.*' --tests 'com.systemwebstudio.wiring.*' \
               --tests 'com.systemwebstudio.tenancy.*' --tests 'com.systemwebstudio.isolation.*' --console=plain

# 4. full suite
./gradlew test --console=plain
```

Expected: everything PASS, 0 SKIP (the Testcontainers-based tests are skipped only if Docker is unavailable — a SKIP in step 2 means the gate did **not** run). Regression scope: `PostgresConnectorIntegrationTests` (read-only behaviour unchanged), `PostgresConnectorTests`, `PostgresSessionSecurityTests`, `PostgresDiscoveryTests`, `GatewayTests`, `DataRuntimeLiveApiTests` (LIVE query/action with the fake connector), the tenancy/isolation suites.

What each new test class proves: `DataSourceManagementTests`/`ManagementHttpTests` (admin service rules, strict parsers, safe projections, no secret in any projection), `PostgresMutationTests` (grammar, SQL construction with no value in SQL text, session flow, error mapping, and the real `DefaultDataGateway` key semantics), `PostgresWritableIntegrationTests` (real INSERT/UPDATE/DELETE, affected counts, unique/FK/NOT NULL/check violations, injection payloads stored as data, `maxAffectedRows` rollback, wrong password, closed port, timeout, killed backend = ambiguous, role/superuser refusal, connection test), `DataManagementApiTests` (CRUD, write-only credential, test connection, TEST/LIVE binding, workspace/tenant isolation with identical 404s, C1 permission denial, no secret in responses/audit/tables), `DataWritableE2ETests` (HTTP → real row → runtime result/error, replay, race, rejection, timeout/UNKNOWN with no second send, wrong password, disable, read-only toggle, delete, TEST preview).

Run so far, **on the cloud VM, not the Mac**: the Spring-free part only (a stand-in harness, Kotlin 2.0.21 with a Jackson-2 shim): **407 tests, 0 failures**, including `DataSourceManagementTests`, `ManagementHttpTests`, `PostgresMutationTests` and the existing data package. **Not run anywhere yet:** `compileKotlin` of the wiring (controllers, JDBC adapters), the three Spring/Testcontainers classes. Items most likely to need a fix on the first Mac run: Kotlin type inference of `ResponseEntity.build<JsonNode>()` in the controllers, `JsonNode.isIntegralNumber`/`isBoolean` on Jackson 3, `JdbcCredentialActorLookup` reading the audit `new_value` (affects `updatedBy` only), exact `error.code`/HTTP status of AUTH_REJECTED/DISABLED/READ_ONLY_VIOLATION at the action level (C4 may rewrap them — the E2E asserts the code).

## 7. Known limitations

- **Cannot write NULL**: parameter binding has no JSON null, so a column cannot be set to NULL (UPDATE) or explicitly inserted as NULL (omit the param to get the column default).
- **Connect-time failures are ambiguous by contract**: `CONNECT_FAILED` is not in `NOT_EXECUTED`, so even "connection refused" (provably nothing sent) keeps the key as UNKNOWN. Conservative, per §4b; improving it needs the C0/C4 decision above.
- **Timeouts are UNKNOWN even though the server cancels and rolls back** (57014): the connector cannot distinguish a cancelled statement from one whose answer was lost.
- **Operations have no management API**: queries/mutations (`data_queries`, `data_mutations`) are still seeded through the repositories.
- **A writable target must be a public host with a valid TLS certificate** (`verify-full`); private/local targets only through the platform operator's explicit allow-list (tests use it).
- `UNIQUE (tenant_id, name)`: a name conflict is visible between workspaces of one tenant (never across tenants). `updatedBy` comes from the audit trail.
- A multi-row statement is bounded by `maxAffectedRows` only after it ran (it is then rolled back); statement-level cost limits are the role's and the database's.
- `SUBMIT` mutations (form-style endpoints) are not database operations → `MUTATION_UNSUPPORTED`.
- The platform logs request bodies of JSON endpoints if `org.springframework.web` is set to DEBUG; keep it at INFO in production (credential bodies pass through those endpoints).

## 8. Production-readiness gate

| Item | Status |
|---|---|
| Management API (CRUD, test, routes) | implemented; unit-verified; **Mac gate pending** |
| Credential management (write-only, metadata, destroy-on-replace) | implemented; unit-verified; **Mac gate pending** |
| Binding management (TEST/LIVE) | implemented; **Mac gate pending** |
| Tenant/workspace isolation | implemented; unit-verified; HTTP-level tests written; **Mac gate pending** |
| Secret-leak checks | unit-verified; HTTP/audit/table/log checks written; **Mac gate pending** |
| Writable PostgreSQL connector | implemented; unit-verified; real-database tests written; **Mac gate pending** |
| LIVE mutation E2E | written; **never run**; **Mac gate pending** |
| C4-compatible error semantics | unit-verified through the real `DefaultDataGateway`; E2E pending |
| Targeted regression | **pending** |

C3 is therefore **not** called production-ready by this batch. It becomes so when step 1–3 of §6 are green on the Mac.
