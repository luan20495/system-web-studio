# Management API v2 — data sources, credentials, schema, query / mutation definitions, TEST/LIVE bindings (FROZEN by D-C0-28)

Status: **contract FROZEN 2026-10-06 (C0). Implementation status: NOT INTEGRATED. No route below is mounted on `integration/v2`, none may be advertised to C5 as READY, and none may be called by a frontend until C0 reports it integrated and tested** (see `docs/parallel/c0/MANAGEMENT_API_REVIEW.md` §5).
Owners: **C3** (domain, services, repositories, connector semantics, strict request parsers, safe projections) · **C0** (Spring controllers and routing, server-derived context, integration tests, this contract) · **C1** (authorization policy; no change here) · **C5** (consumer only).
This file never overrides `tenant-permission.md`, `data-runtime.md` (section 4b mutation semantics), `action-workflow.md` or `runtime-api.md`. Anything not listed here does not exist; a new route needs a new DECISIONS entry first.

## 1. Rules for every route

1. **Base paths.** Workspace routes: `/api/v1/workspaces/{workspaceId}/…`. Project routes: `/api/v1/workspaces/{workspaceId}/projects/{projectId}/…`.
2. **Server-derived scope.** The client never sends `tenantId`. The tenant comes from `AccessService.forWorkspace` / `forProject`. A body that carries any authority-related key (`tenantId`, `workspaceId`, `projectId`, `credentialRef`, `id`, `createdBy`, `updatedBy`, `userId`, `version` outside `expectedVersion`, …) or any unknown key is refused `400 INVALID_PARAMS` (strict parsing, same rule as `runtime-api.md`).
3. **Session.** Authenticated session cookie; every state-changing call carries `X-XSRF-TOKEN`. `SecurityConfiguration` is unchanged (no `permitAll`). Not signed in: `401 AUTHENTICATION_REQUIRED`; bad CSRF: `403 CSRF_INVALID` (both written by the platform, not by C3).
4. **Flag.** All routes are mounted only with `app.data-platform.enabled=true` (default false; a disabled flag = the bean does not exist = 404).
5. **Default deny and disclosure.** A non-member, an unknown workspace/project/resource, a resource of another workspace and a resource of another tenant all answer the same `404` (`NOT_FOUND`; the platform's `WORKSPACE_NOT_FOUND` / `PROJECT_NOT_FOUND` from the access proof are the same thing for a client). `403` is returned only to a member of the workspace who lacks the permission.
6. **Actors.** The caller is always `ActorKind.USER`; C1 denies every other actor kind (TEMPORARY V2 POLICY).
7. **No secret anywhere.** No response, error body, log line, audit payload, header or URL can carry a password, token, API secret, decrypted or encrypted credential, `credentialRef`, or a connection string with embedded secret. Error messages are fixed text; nothing from the request is echoed.
8. **Optimistic concurrency.** Every mutable resource has an integer `version`. `PATCH` / `PUT` of a data source, query definition or mutation definition accept an optional `expectedVersion`; if present and different from the stored version the answer is `409 CONFLICT` and nothing changes.
9. **Throttle.** Changing calls are limited per tenant (C3 `RateLimitGate`, 60 changes / 60 s as built): `429 RATE_LIMITED` with `Retry-After`. Reads are not throttled.
10. **No raw mutation.** There is **no browser-callable mutation execution route**. `POST /api/v1/data/mutate` is not mounted and must not be added. A browser changes external data only through C5 → `app-runtime/actions/{id}/execute` → C4 `ActionRuntime` → C3 `DataGateway` → connector. The Management API only *defines* mutations.

## 2. Permissions (canonical C1 codes — no new code is invented)

C1 `GatewayAuthorizer.REQUIRED` maps the C3 operation to the code (all listed codes are required). Today only `WORKSPACE_ADMIN` holds `DATA_SOURCE_MANAGE`; workspace EDITOR / PUBLISHER / VIEWER do not (`Permission.kt`, D-C1-12). That is the **current policy and it is not changed by this contract**. `APP_EDIT` is the canonical name of the storage constant `PROJECT_EDIT`.

| Route group | Required canonical codes (C3 operation) |
|---|---|
| connector catalogue, list / get data source, credential metadata | `DATA_SOURCE_VIEW` (`DATASOURCE_READ`) |
| create / update / delete data source, put / delete credential, test connection | `DATA_SOURCE_MANAGE` (`DATASOURCE_MANAGE`) |
| schema discover (no samples) | `DATA_SOURCE_MANAGE` (`SCHEMA_DISCOVER`) |
| schema discover with `includeSamples:true` | `DATA_SOURCE_MANAGE` **and** `QUERY_EXECUTE` (`SCHEMA_SAMPLE`) |
| read the stored schema snapshot | `DATA_SOURCE_MANAGE` (default deny; relaxing it is a C1 decision) |
| list query / mutation definitions (summary projection: id, kind, status, version, parameter names; **no SQL, no template**) | `DATA_SOURCE_VIEW` |
| read one definition in full; create / update / delete a definition | `DATA_SOURCE_MANAGE` |
| list bindings | `APP_EDIT` on the project **and** `DATA_SOURCE_VIEW` |
| set / delete a binding | `APP_EDIT` on the project **and** `DATA_SOURCE_MANAGE` |

Not used and not created: `QUERY_MANAGE`, `MUTATION_MANAGE`, `SCHEMA_*` as permission codes, `DATA_MUTATE` for definitions (`DATA_MUTATE` is the *execution* permission and stays that). If the product later wants an editor to manage definitions or TEST bindings, that is a **C1 policy request**, not a C3 / C0 change.

## 3. Routes (approved list)

### 3.1 Connector catalogue and data sources — base `/api/v1/workspaces/{workspaceId}`

| Method + path | Success | Notes |
|---|---|---|
| `GET /data-sources/connectors` | 200 `{items:[ConnectorType]}` | available + planned types |
| `GET /data-sources` | 200 `{items:[DataSource]}` | only this workspace's sources |
| `POST /data-sources` | **201** DataSource | body `name`, `type`, `config?`, `credential?` |
| `GET /data-sources/{id}` | 200 DataSource | |
| `PATCH /data-sources/{id}` | 200 DataSource (as stored afterwards) | body: any of `name`, `config` (replaces the whole config), `status` (`ACTIVE` / `DISABLED`), plus `expectedVersion?`; at least one field. **All-or-nothing: one transaction, one version bump, all fields validated before any is applied** |
| `DELETE /data-sources/{id}` | **204** | `409 CONFLICT` while any binding (TEST or LIVE) uses it. Definitions, schema snapshots and finished idempotency rows are removed with it in the same transaction; `409 CONFLICT` while a mutation idempotency row is `RESERVED` or `UNKNOWN` (the evidence of a possibly applied write is never destroyed). The credential is discarded in the same unit of work |

`ConnectorType`, `DataSource` shapes: as in `docs/parallel/c3/MANAGEMENT_API.md` §2.1 (`config` values are strings; `hasCredential`; `status`; `version`; no `credentialRef`). Name rule `^[A-Za-z0-9][A-Za-z0-9 ._-]{0,79}$`, unique per tenant, case-insensitive (`409 CONFLICT`); a config key or value that looks like a secret is `400 INVALID_CONFIG`.

### 3.2 Credential — base `…/data-sources/{id}/credential`

| Method | Success | Notes |
|---|---|---|
| `GET` | 200 CredentialMetadata | exactly `{configured, type, keys, updatedAt, updatedBy}` (+ `hasCredential` is on DataSource) — **key names only** |
| `PUT` | 200 CredentialMetadata | body `{"credential":{…}}`, write-only, 1–8 text keys that match the connector's `credentialKeys`; replaces any existing one |
| `DELETE` | **204** | removing nothing is also 204 |

The browser never receives: password, token, API secret, decrypted credential, ciphertext, a secret-bearing connection string, or `credentialRef`. "Edit" means "replace". The request body of `PUT` is never logged or audited.

### 3.3 Connection test — `POST …/data-sources/{id}/test`

Empty body. `200 TestResult` always when the test ran: `{ok:true, latencyMs, warnings:[…]}` or `{ok:false, code, message}` with `code` in `AUTH_REJECTED, CONNECT_FAILED, HOST_UNRESOLVED, ADDRESS_BLOCKED, TLS_FAILED, TIMEOUT, ROLE_TOO_PRIVILEGED, INVALID_CREDENTIAL, NOT_IMPLEMENTED, INTERNAL`. `409 DISABLED` when the source is `DISABLED`; `429 RATE_LIMITED`. A test never returns the credential or the raw driver message.

### 3.4 Schema — base `…/data-sources/{id}/schema`

| Method + path | Success | Notes |
|---|---|---|
| `POST …/schema/discover` | 200 SchemaSnapshotSummary | body `{includeSamples?: boolean}` (default false); creates a new snapshot version; sampled values are masked as the gateway masks them; `429 RATE_LIMITED` / `REFRESH_TOO_SOON` as built |
| `GET …/schema` | 200 SchemaSnapshot | the latest snapshot; `404 NOT_FOUND` when none was discovered yet |

### 3.5 Query and mutation definitions — **scoped by data source** (not by project)

Why: `data_queries` and `data_mutations` are keyed `(tenant_id, data_source_id, query_id | mutation_id)` (V28). A definition belongs to a data source; a project reaches it through a binding. A route under `/projects/{projectId}/data-queries` would claim an ownership the schema does not have, so **the project-scoped routes are NOT approved.**

Base `/api/v1/workspaces/{workspaceId}/data-sources/{id}`

| Method + path | Success |
|---|---|
| `GET /queries` | 200 `{items:[QuerySummary]}` |
| `POST /queries` | **201** QueryDefinition (body `queryId`, `kind`, `definition`, `status?`) |
| `GET /queries/{queryId}` | 200 QueryDefinition (full) |
| `PATCH /queries/{queryId}` | 200 QueryDefinition (`definition?`, `status?`, `expectedVersion?`) |
| `DELETE /queries/{queryId}` | **204** |
| `GET /mutations` | 200 `{items:[MutationSummary]}` |
| `POST /mutations` | **201** MutationDefinition (body `mutationId`, `kind`, `definition`, `status?`) |
| `GET /mutations/{mutationId}` | 200 MutationDefinition (full) |
| `PATCH /mutations/{mutationId}` | 200 MutationDefinition |
| `DELETE /mutations/{mutationId}` | **204** (`409 CONFLICT` while one of its idempotency rows is `RESERVED` / `UNKNOWN`) |

`queryId` / `mutationId` match `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` (column is `VARCHAR(64)`). A definition is validated by the same Kotlin definition types the gateway uses on every load (`definition` is never trusted as stored JSON); an invalid one is `400 INVALID_QUERY` / `400 INVALID_CONFIG`; a mutation against a source with `writable` not `true` is `422 READ_ONLY_VIOLATION` (existing code). A definition can contain only parameterised templates; no tenant, no credential, no host. A browser can name only an existing definition id at run time (`data-runtime.md`), which is why these routes are the *only* way a tenant defines new ones.

### 3.6 TEST / LIVE bindings — base `/api/v1/workspaces/{workspaceId}/projects/{projectId}/data-bindings`

| Method + path | Success | Notes |
|---|---|---|
| `GET` | 200 `{items:[Binding]}` | |
| `PUT /{mode}/{slotId}` | 200 Binding | body `{"dataSourceId":"…"}` (only that key) |
| `DELETE /{mode}/{slotId}` | **204** | `404 NOT_FOUND` when there is no such binding |

- `mode` is exactly `TEST` or `LIVE` (upper case; anything else `400 INVALID_PARAMS`). `slotId` = an AppDefinition `dataSources[].id`, `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`.
- The data source must belong to the **same tenant and workspace** as the project (V28 composite FK); otherwise `404 NOT_FOUND` (same as unknown).
- **TEST and LIVE are separate rows and never fall back to each other.** A TEST run reads only the TEST binding; a LIVE run reads only the LIVE binding. An unbound slot answers `422 DATA_SOURCE_UNBOUND` at run time (`runtime-api.md`); it is never resolved from the other mode. TEST never writes (C4 answers `WouldRun`); LIVE is the published version's binding.
- A binding is configuration of the application: `APP_EDIT` is always required in addition to the data-source permission.

## 4. Error envelope (frozen)

Every non-2xx body written by these routes (C3 `ConnectorFailure` family and the C0 wiring) is the `runtime-api.md` envelope:

```json
{"code":"…","message":"fixed text","requestId":"…","retryable":false,"details":{}}
```

`retryable` is always present (`true` only for `RATE_LIMITED`, `REFRESH_TOO_SOON` and `TIMEOUT`-class source failures; everything else `false`); `details` is an object, empty unless the code defines fields (for example `{"field":"name"}` for `INVALID_PARAMS` — **never a submitted value**); `RATE_LIMITED` also sends `Retry-After`. (C3 as built omits `retryable` and `details`: C0 adds them in the wiring layer, C3 changes its `toJson` only if C0 asks.)

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `INVALID_PARAMS` | malformed body, unknown / authority key, bad id, bad `mode` / `slotId` |
| 400 | `INVALID_CONFIG` | bad name or configuration, secret in configuration |
| 400 | `INVALID_CREDENTIAL` | credential keys do not fit the connector |
| 400 | `INVALID_QUERY` | a query / mutation definition does not validate |
| 401 | `AUTHENTICATION_REQUIRED` | not signed in (platform) |
| 403 | `PERMISSION_DENIED` (C3 guard) or `FORBIDDEN` (C1 `AccessContext.require`) | member without the permission. Clients key off the status |
| 403 | `CSRF_INVALID` | platform |
| 404 | `NOT_FOUND` (+ `WORKSPACE_NOT_FOUND`, `PROJECT_NOT_FOUND`) | missing **or** not yours — identical on purpose |
| 409 | `CONFLICT` | duplicate name / id, delete while bound or in flight, `expectedVersion` mismatch |
| 409 | `DISABLED` | test / use of a `DISABLED` source (kept: current frozen C3 mapping) |
| 413 | `PAYLOAD_TOO_LARGE` | body over the route limit |
| 422 | `UNSUPPORTED_TYPE` | unknown connector type |
| 422 | `READ_ONLY_VIOLATION` | mutation definition on a read-only source |
| 422 | `DATA_SOURCE_UNBOUND` | run-time answer for an unbound slot (not a Management route code) |
| 429 | `RATE_LIMITED` | wait `Retry-After` |
| 500 | `INTERNAL` | fixed message, no detail |
| 501 | `NOT_IMPLEMENTED` | planned connector (`mysql`, `csv`, `graphql`, `google_sheets`, `odoo`, `salesforce`) |

C3 / C4 **runtime** codes are unchanged and never remapped by this API: `MUTATION_REJECTED` (422, nothing applied) and `IDEMPOTENCY_OUTCOME_UNKNOWN` (409, `retryable:false`, never retried, `onError` never run) keep the semantics of `data-runtime.md` section 4b and `runtime-api.md` section 6.

## 5. Audit (append-only, no secret)

Every row records tenant, workspace, actor user, request id, data source id and the ids / versions / status names / counts below; it never records a request body, a credential, a config *value* or a definition body (a definition is identified by id + version + a content hash).

| Event | Action (existing constant unless marked new) |
|---|---|
| data source create / update (name, config) / status change / delete | `DATASOURCE_CREATED`, `DATASOURCE_UPDATED`, `DATASOURCE_STATUS_CHANGED`, `DATASOURCE_DELETED` |
| credential set / rotate / delete | `DATASOURCE_CREDENTIAL_ROTATED` (also for the first set), `DATASOURCE_CREDENTIAL_REMOVED` |
| connection test (ok or not) | `DATASOURCE_TESTED` (outcome code only) |
| schema discover | `DATASOURCE_SCHEMA_REFRESHED` / `DATASOURCE_DISCOVERED` (snapshot version, `includeSamples`) |
| query definition create / update / delete | **new** `DATA_QUERY_DEFINITION_CHANGED` (`change` = create / update / delete, `queryId`, version, hash) |
| mutation definition create / update / delete | **new** `DATA_MUTATION_DEFINITION_CHANGED` (same fields) |
| binding set / delete | `DATASOURCE_BINDING_CHANGED` (`mode`, `slot`, `change`, `project`) |
| denied attempt | `DATA_ACCESS_DENIED` (as built) |

A write and its audit row are one unit: a change that cannot be audited is not applied.

## 6. What is deliberately not here

Raw data execution from a browser, tenant-level (workspace-less) data sources, sync / webhook / AI-catalogue management, sharing / grants per data source, data-source export / import, a published (anonymous) runtime route. Each needs its own accepted contract.
