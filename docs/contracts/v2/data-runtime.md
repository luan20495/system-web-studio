# Canonical contract v2 — Data runtime

Owner: **C3** (runtime shapes). Frozen by C0 on 2026-10-05 from `agent/c3-data` (`607c116`). Supersedes `docs/contracts/data-connector.md`. C2 only *references* these shapes (`app-definition.md`). Code: written, not compiled, not run (the "257 harness tests" ran on an ad-hoc harness, not Gradle).

## 1. Flow (unchanged)
`UI → AppDefinition/ViewModel → Query | Action → Auth → Permission → DataGateway → Connector → DB/REST/External → Mapping/Transform → UI`. No raw URL, SQL or credential crosses the gateway.

## 2. Canonical runtime shapes (= C3 code)
| Concept | Canonical type (package `data.*`) | Note |
|---|---|---|
| `DataSourceRef` | `DataSourceRef(id: UUID, tenantId: UUID, type: String, configNonSecret: Map)` | credentials only via `CredentialVault`/`ResolvedCredential` |
| `QueryDefinition` | sealed `QueryDefinition` → `SqlQueryDefinition`, `RestQueryDefinition` (`QueryCatalog`, read-only) | the id is what `queries[].operationKey` points to |
| `MutationDefinition` | `MutationDefinition(kind CREATE\|UPDATE\|DELETE\|SUBMIT, target, params, invalidates…)` | |
| Gateway call (canonical request) | `GatewayQuery(dataSourceId: UUID, operation, params, page?, mappingRef, viewModelRef?)`, `GatewayMutation(dataSourceId: UUID, operation, params, idempotencyKey)` | **replaces** the draft `runQuery(ctx, tenant, QueryRequest)` |
| `QueryRequest`/`QueryResult` | connector-internal (`QueryExecutor`) | `QueryRequest.tenant` becomes `tenancy.TenantContext`; the C3 placeholder `TenantContext`/`ActorKind` are deleted |
| `MutationRequest` | there is no such type; use `GatewayMutation` (client side) and `MutationExecRequest` (connector side) | |
| `ViewModelDefinition`, `MappingDefinition`, `FieldMapping`, `Transform` (sealed), `Expression` | `data.mapping` | stored verbatim in the AppDefinition (`app-definition.md` §3). **`fields[].transforms[]` is canonical; the legacy `transform` is read-only compatibility** (both present = rejected) |
| `ViewModelData` | `data.mapping` | what the UI renders |
| `SchemaSnapshot` | `data.discovery` (versioned, fingerprinted, samples off by default and masked) | |
| `DataBinding` | **not a C3 concept** — C2's `dataBindings[]` | |
`DataGateway` API: `runQuery(GatewayContext, GatewayQuery)`, `mutate`, `testConnection`, `discoverSchema`, `refreshCache`. `GatewayContext` is produced by the C0 adapter from `AccessContext` + `TenantContext`.

## 3. App → runtime resolution (single path for UI **and** actions)
`AppDataBindingResolver` (C2, package `app.definition`; reads the committed `AppDefinitionV2`, imports only C3 *shape* classes) turns a local reference into a gateway call:
`queryRef (local id) → QueryDef → dataSourceRef (local) → DataSourceDef.sourceRef (UUID) + QueryDef.operationKey → GatewayQuery/GatewayMutation`, plus `mappingRef`/`viewModelRef`. Both the UI query path and C4's `ActionDataPort` adapter use it. Mismatches to fix: C3 compares `mapping.queryRef` with its catalog id (`operationKey`), but the document stores the *local* query id — the resolver must translate; C2's `FieldMappingDef.from` is non-null while C3's is nullable → C3's wins.

## 4. Idempotency (writes)
`GatewayMutation.idempotencyKey` is mandatory and must match `^[A-Za-z0-9_-]{8,128}$`. The key that reaches C3 is **derived**: `base64url(sha256(tenantId | appId | userId | actionOrQueryId | clientKey))` (43 chars). C3 scopes by `(tenant, dataSource, mutation, key)` and has no user in the scope, so raw client keys must never be forwarded. **C3 keeps a key reserved after an ambiguous failure (TIMEOUT/CONNECT/5xx)** instead of releasing it, otherwise retries can double-write; the resulting HTTP/`code` semantics are in §4b.

### 4b. Mutation error semantics (frozen 2026-10-06; = C3 `GatewayProblem.status`, D-C3-14)
A write has exactly three kinds of outcome, and the caller must be able to tell them apart:

| HTTP | `code` | Meaning | Applied? | Idempotency key | Caller (C4 adapter / client) |
|---|---|---|---|---|---|
| 200 | — (`replayed: true/false`) | done, or the stored result of an earlier identical request | yes (once) | stays `DONE` | success; a replay is success |
| **409** | **`IDEMPOTENCY_OUTCOME_UNKNOWN`** | an earlier attempt with this key ended **ambiguously** (timeout, connection lost, upstream 5xx, unclassified error, lease expired); it **may or may not** have been applied | **unknown** | stays reserved as `UNKNOWN`; it is **never** run again | **do not auto-retry, do not retry with the same key** (it will answer 409 again). Surface `OUTCOME_UNKNOWN`: the user (or an operator) checks the data source and starts a *new* operation with a *new* client key. C4: non-retryable failure, run `FAILED` with this code, no compensation that assumes the write did not happen |
| **422** | **`MUTATION_REJECTED`** | the data source refused the change and **certainly applied nothing** (definite, non-ambiguous failure: constraint, validation, 4xx from the source) | **no** | released (`NOT_EXECUTED` allow-list) | non-retryable with the same inputs; fix the input. Safe to compensate-as-not-done |
| 409 | `IDEMPOTENCY_IN_PROGRESS` | another request with this key is still running | in flight | `RESERVED` | retryable after a short wait |
| 409 | `IDEMPOTENCY_CONFLICT` | the key was used with different parameters | — | untouched | non-retryable (client bug) |
| 422 | `MUTATION_UNSUPPORTED`, `READ_ONLY_VIOLATION`, `INVALID_MAPPING`, `MAPPING_FAILED`, `UNSUPPORTED_TYPE` | request is well-formed but not executable here | no | released | non-retryable |
| 400 / 403 / 404 / 429 | `INVALID_PARAMS` / `PERMISSION_DENIED` / `MUTATION_NOT_FOUND`, `NOT_FOUND`, `TENANT_MISMATCH` / `RATE_LIMITED` | as named; `404` also for another tenant's resource | no | released | `429` retryable with backoff; the others not |
| 502 / 504 / 500 | `TIMEOUT`, `CONNECT_FAILED`, `UPSTREAM_STATUS`, `QUERY_FAILED`, `RESPONSE_*`, `INTERNAL`, any unclassified error (**not** on C3's `NOT_EXECUTED` list) | transport or source failure of the **first** attempt: it may have been applied | **unknown** | moved to `UNKNOWN` | the first response is 502/504/500, but the **next** attempt is 409 `IDEMPOTENCY_OUTCOME_UNKNOWN`; the adapter therefore treats a write that ended this way as `OUTCOME_UNKNOWN`, **not** as retryable. (`ADDRESS_BLOCKED`, `HOST_UNRESOLVED`, `TLS_FAILED`, `AUTH_REJECTED` are on the `NOT_EXECUTED` list: certain that nothing was applied, key released, 502, non-retryable until the source is fixed) |

Rules: only a failure on C3's `NOT_EXECUTED` allow-list releases a key; everything else marks it `UNKNOWN`. A client never sees SQL, URL, headers, credentials or a cause in any of these bodies (fixed texts + stable `code` + `requestId`). `GatewayMutationResponse.replayed = true` marks a replay.

## 5. Security (all outbound traffic)
- **One network-address policy**: `PublicAddress` (canonical; **patched by C0 in `runtime/Gateway.kt` on `integration/v2`, not compiled until the Mac gate** — spec in `docs/parallel/INTEGRATION_V2.md` §8 and C3's `AddressRangeSpecTests`). C3 already deleted its `AddressPolicy.SupplementaryRanges` (`agent/c3-data` 02fe1d1) and calls `PublicAddress.isPublic`/`isPublicAddress` only. Resolve once, pin the address (REST `PinnedHttpsTransport`, PG `PinnedSocketFactory`), no redirects, caps on size/time/rows, credentials only in `RestRequests.build`.
- SQL: `SqlGuard` single `SELECT/WITH`, blocklist of writing keywords and functions. **Required fixes (C3 reports them done at `02fe1d1`; verified only at the Mac gate):** reject `U&"…"` unicode-escaped identifiers; run the superuser/`pg_read_server_files` check on **every** connection, not only in `test()`; `PostgresTargetPolicy.deniedHosts` must fail closed; drop `sslmode=require` (no certificate verification).
- Webhooks: the replay guard must key on the **signature** (or sign the delivery id). Cache: capture the cache key at `get` and reuse it for `put` (stale-write race). Sync runs must re-check the permission of the job owner.
- PII: discovery samples off by default, masked by name and value shape; `AiSafeSchema` is what AI may see.

## 6. HTTP surface (decided)
Webhook ingest path = **`/api/v1/webhooks/data/{endpointId}`** (the code). Design docs and `B-C3-07` are corrected to this path. C0 adds it to `permitAll` and CSRF-exempts only this route; SSE realtime stays authenticated.

## 7. Persistence
C3 submits **one** consolidated migration request (data_sources incl. credential reference, data_queries, data_mutations, source_schemas, data_idempotency, then sync_jobs/sync_state, webhook_endpoints, sink) — the current BOARD sketches contradict each other (`credential_enc` column vs `data_credentials` table). Every table: `tenant_id NOT NULL REFERENCES tenants(id)`; workspace-scoped tables use the composite FK `(workspace_id, tenant_id)`. No number is assigned yet (`integration-contract.md` §5).
