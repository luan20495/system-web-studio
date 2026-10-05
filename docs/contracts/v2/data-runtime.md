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
| `ViewModelDefinition`, `MappingDefinition`, `FieldMapping`, `Transform` (sealed), `Expression` | `data.mapping` | stored verbatim in the AppDefinition (`app-definition.md` §3) |
| `ViewModelData` | `data.mapping` | what the UI renders |
| `SchemaSnapshot` | `data.discovery` (versioned, fingerprinted, samples off by default and masked) | |
| `DataBinding` | **not a C3 concept** — C2's `dataBindings[]` | |
`DataGateway` API: `runQuery(GatewayContext, GatewayQuery)`, `mutate`, `testConnection`, `discoverSchema`, `refreshCache`. `GatewayContext` is produced by the C0 adapter from `AccessContext` + `TenantContext`.

## 3. App → runtime resolution (single path for UI **and** actions)
`AppDataBindingResolver` (C2, package `app.definition`; reads the committed `AppDefinitionV2`, imports only C3 *shape* classes) turns a local reference into a gateway call:
`queryRef (local id) → QueryDef → dataSourceRef (local) → DataSourceDef.sourceRef (UUID) + QueryDef.operationKey → GatewayQuery/GatewayMutation`, plus `mappingRef`/`viewModelRef`. Both the UI query path and C4's `ActionDataPort` adapter use it. Mismatches to fix: C3 compares `mapping.queryRef` with its catalog id (`operationKey`), but the document stores the *local* query id — the resolver must translate; C2's `FieldMappingDef.from` is non-null while C3's is nullable → C3's wins.

## 4. Idempotency (writes)
`GatewayMutation.idempotencyKey` is mandatory and must match `^[A-Za-z0-9_-]{8,128}$`. The key that reaches C3 is **derived**: `base64url(sha256(tenantId | appId | userId | actionOrQueryId | clientKey))` (43 chars). C3 scopes by `(tenant, dataSource, mutation, key)` and has no user in the scope, so raw client keys must never be forwarded. **C3 must keep a key reserved after an ambiguous failure (TIMEOUT/CONNECT)** instead of releasing it, otherwise retries can double-write.

## 5. Security (all outbound traffic)
- **One network-address policy**: `PublicAddress` (canonical, patched by C0 — see `docs/parallel/INTEGRATION_V2.md` §8). C3's `AddressPolicy.SupplementaryRanges` is deleted afterwards. Resolve once, pin the address (REST `PinnedHttpsTransport`, PG `PinnedSocketFactory`), no redirects, caps on size/time/rows, credentials only in `RestRequests.build`.
- SQL: `SqlGuard` single `SELECT/WITH`, blocklist of writing keywords and functions. **Required fixes:** reject `U&"…"` unicode-escaped identifiers; run the superuser/`pg_read_server_files` check on **every** connection, not only in `test()`; `PostgresTargetPolicy.deniedHosts` must fail closed; drop `sslmode=require` (no certificate verification).
- Webhooks: the replay guard must key on the **signature** (or sign the delivery id). Cache: capture the cache key at `get` and reuse it for `put` (stale-write race). Sync runs must re-check the permission of the job owner.
- PII: discovery samples off by default, masked by name and value shape; `AiSafeSchema` is what AI may see.

## 6. HTTP surface (decided)
Webhook ingest path = **`/api/v1/webhooks/data/{endpointId}`** (the code). Design docs and `B-C3-07` are corrected to this path. C0 adds it to `permitAll` and CSRF-exempts only this route; SSE realtime stays authenticated.

## 7. Persistence
C3 submits **one** consolidated migration request (data_sources incl. credential reference, data_queries, data_mutations, source_schemas, data_idempotency, then sync_jobs/sync_state, webhook_endpoints, sink) — the current BOARD sketches contradict each other (`credential_enc` column vs `data_credentials` table). Every table: `tenant_id NOT NULL REFERENCES tenants(id)`; workspace-scoped tables use the composite FK `(workspace_id, tenant_id)`. No number is assigned yet (`integration-contract.md` §5).
