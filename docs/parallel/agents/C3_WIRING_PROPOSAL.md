> **SUPERSEDED_BY:** `docs/DATA_RUNTIME.md` - historical document, kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# C3 → C0: Spring/API wiring proposal for the Data Platform

Status: PROPOSED · **proposal only**: everything below touches C0-owned files (`wiring/**`, `identity/SecurityConfiguration.kt`, `application*.yml`, `runtime/**`, `audit/**`) and is NOT done by C3. C3 delivers the framework-free pieces these controllers and beans call; they are tested without Spring.

Canonical runtime vocabulary is `GatewayQuery` / `GatewayMutation` (contract v2 data-runtime). The old `QueryRequest` is a connector-SPI type and is not part of any endpoint.

## 1. What C3 ships (all under `com.systemwebstudio.data.*`)

| Piece | Where | Purpose |
|---|---|---|
| `DataRoutes` / `DataRoute` | `data/api/DataApi.kt` | the route table as data: method, path, required `GatewayOperation`, streaming/public flags |
| `DataApiHandler` | `data/api/DataApi.kt` | one method per endpoint: strict parse → gateway → JSON or fixed-text problem; never takes a tenant from the request |
| `GatewayRequests/Responses/Problems` | `data/gateway/GatewayHttp.kt` | strict parsers, response bodies, failure → status map |
| `WebhookRoutes`, `WebhookHttp.readBody/request` | `data/sync/webhook/WebhookHttp.kt` | canonical webhook path, bounded body read, protocol-header extraction |
| `DataGateway`, `DefaultDataGateway` | `data/gateway/DataGateway.kt` | the pipeline (permission, tenant scope, approved definitions, cache, idempotency, audit) |
| `AiDataCatalogProvider`, `DefaultAiDataCatalogProvider` | `data/discovery/AiDataCatalog.kt` | masked catalog for the AI planner, filtered per caller |
| `RealtimeService.subscribe` | `data/cache/RealtimeService.kt` | SSE source (ring buffer, `Last-Event-ID`) |
| `WebhookIngress` | `data/sync/webhook/WebhookIngress.kt` | signature/timestamp/replay/rate-limit/audit/cache-invalidation |
| `PgConnectionProperties`, `PgSessionPreflight`, `PostgresTargetPolicy.denyingPlatformDatabases` | `data/datasource/postgres/**` | verify-full TLS, per-session role preflight, platform-DB deny-list |

## 2. Endpoints (controllers in C0's `wiring`, each a few lines)

Every controller: authenticate with the existing session, build `GatewayContext(tenant, actorUserId, ActorKind.USER, requestId, workspaceId, projectId, appVersionId)` **on the server** from the resolved tenant/session (C1 `TenantContext`), call the matching `DataApiHandler` method, write `ApiResult.status` + `body` (+ `Retry-After` when `retryAfterSeconds != null`). No controller reads a tenant from a path, query string or body.

| Name | Method + path | Gateway operation | Handler call |
|---|---|---|---|
| runQuery | `POST /api/v1/data/query` | QUERY_EXECUTE | `runQuery(ctx, body)` |
| mutate | `POST /api/v1/data/mutate` | MUTATION_EXECUTE | `mutate(ctx, body)` |
| testConnection | `POST /api/v1/data/sources/{dataSourceId}/test` | DATASOURCE_MANAGE | `testConnection(ctx, id)` |
| discoverSchema | `POST /api/v1/data/sources/{dataSourceId}/schema/refresh` | SCHEMA_DISCOVER | `discoverSchema(ctx, id, includeSamples)` |
| refreshCache | `POST /api/v1/data/sources/{dataSourceId}/cache/refresh` | CACHE_REFRESH | `refreshCache(ctx, id, queryId)` |
| events (SSE) | `GET /api/v1/data/events` | EVENTS_SUBSCRIBE | `RealtimeService.subscribe(ctx, ids, lastEventId)` — no buffering, heartbeat, `Last-Event-ID` |
| aiCatalog | `GET /api/v1/data/ai-catalog` | DATASOURCE_READ | `aiCatalog(ctx, includeMaskedSamples)` (internal use by C2's planner; `wiring.AiDataCatalogAdapter` calls the provider directly) |
| webhookIngest | `POST /api/v1/webhooks/data/{endpointId}` | none — HMAC | `WebhookHttp.readBody` → `WebhookHttp.request(endpointId, headers, body, peer)` → `WebhookIngress.handle(IngressRequest)` → `WebhookHttp.responseBody` |

`DataRoutes.ALL` is the machine-readable form; a C0 test can assert that every route in it has a mapped controller method and that `DataRoutes.WEBHOOK` is the only `public` one.

## 3. Security configuration (C0: `identity/SecurityConfiguration.kt`) — replaces B-C3-07

- `POST /api/v1/webhooks/data/{endpointId}` (**exactly this matcher, POST only**): `permitAll` and excluded from CSRF. It is authenticated by its signature and resolves the tenant from the endpoint row server-side. Nothing else under `/api/v1/webhooks/**` is opened.
- All `/api/v1/data/**` routes: authenticated session, CSRF on (they are same-site browser calls), `GatewayAuthorizer` decides permission.
- SSE route: disable response buffering/compression, idle timeout > heartbeat interval, no caching headers.
- The old path `POST /api/data/webhooks/{endpointId}/ingest` (earlier C3 doc) is **withdrawn** — never opened, no alias.

## 4. Peer address and body limit (webhook controller)

- Peer address for rate limiting = the server-trusted client address (C0's trusted-proxy handling), never a raw `X-Forwarded-For` value.
- Call `WebhookHttp.readBody(inputStream, declaredContentLength)`: it refuses a declared length above the limit before reading and reads at most limit + 1 bytes; `null` ⇒ answer 413 without reading further. Do not use `@RequestBody ByteArray` (unbounded).
- Pass only the three protocol headers (signature, timestamp, optional delivery id); `WebhookHttp.request` drops repeated headers.
- Respond with `WebhookHttp.responseBody(...)` (fixed JSON `{"status":"<code>"}`; one 401 `unauthorized` for every authentication failure — unknown endpoint, bad signature, stale timestamp alike; 202 `accepted`/`duplicate`, 400, 413, 429, 503 otherwise).

## 5. Beans (C0: `wiring/**`, `application*.yml`)

Ports C0 must implement or provide (C3 has in-memory fakes only in tests):

- `GatewayAuthorizer` → C1 `AccessService` (**default deny**; operation → permission code; `workspaceId` from the context).
- `DataSourceRepository`, `CredentialStore`, `QueryCatalog`, `MutationCatalog` (incl. `list`), `SourceSchemaStore`, `IdempotencyStore`, `SyncJobStore`, `SyncSink`, `WebhookEndpointStore`, `WebhookReplayGuard` → JDBC (after the migration, `agents/C3_SCHEMA_PROPOSAL.md`) or Redis where noted there.
- `MappingCatalog` → over the published AppDefinition (`fields[].transforms[]`).
- `DataAuditSink` → `AuditService` (fixed fields only), `RateLimitGate` → `RateLimiter`, `DataEventBus`/`CacheBackend` → Redis (B-C3-09), `WorkflowTriggerPort` → C4 adapter, `ActionDataPort` adapter → `DataGateway.mutate` (C4/C0, B-C3-03).
- `DataConnectorRegistry(listOf(PostgresConnector(...), RestConnector(...)))` with `PostgresTargetPolicy.denyingPlatformDatabases(spring.datasource.url, app.runtime.appdb-url, ...)` — it throws `IllegalArgumentException` for a missing/blank/unparsable URL, so a misconfigured deployment fails at startup instead of silently allowing the platform database.
- `JdbcPgConnectionFactory()` (public no-arg = TLS enforced). The JVM trust store must hold the CA of every customer database; `sslmode=verify-full` with the JDK default factory is not configurable down.
- Scheduler: `SyncRunner.tick()` on a fixed delay (B-C3-10).
- Today the webhook body limit (256 KiB), the cache TTL cap (24 h) and the webhook rate defaults are constants in C3. Only the allowed private DB hosts (default: none) need a config key; its name is C0's to choose.

## 6. Adapters C0 writes (outside C3)

- `wiring.AiDataCatalogAdapter` → `DefaultAiDataCatalogProvider.catalog(ctx[, options])`.
- `wiring.GatewayContextFactory` → builds `GatewayContext` from the session; `actorKind` is `USER` for browser calls and for sync runs (the job owner), never invented by the client.
- Idempotency: C4 sends the derived key (`base64url(sha256(tenant|app|user|action|clientKey))`, 43 chars); C3 stores it as given and never sees the raw client key. C4 must treat HTTP 409 `IDEMPOTENCY_OUTCOME_UNKNOWN` as "outcome ambiguous — reconcile, do not blindly retry" and 422 `MUTATION_REJECTED` as a definite refusal.
