# C3 · Data Source Management API (B-C0-W-03)

Status: implemented on branch `agent/c3-data-prod` (off `integration/v2 @ f894cc6`); **Mac gate pending** (see `WRITABLE_POSTGRES.md` §6 for the commands and what has and has not been run).
Audience: **C5** (UI/clients), C0 (wiring owner), C6 (verification). Code: `wiring/DataManagementControllers.kt` (routes, C0's wiring layer), `data/gateway/ManagementHttp.kt` (strict parsers and safe projections), `data/datasource/DataSourceAdminService.kt` (rules).

Mounted only with `app.data-platform.enabled=true`, like every other data route. Same session cookie + CSRF header (`X-XSRF-TOKEN`) as the rest of `/api/v1`.

## 1. Rules that hold for every route

- **Tenant and workspace come from the path, never from the body.** `AccessService.forWorkspace` resolves the tenant from the workspace and proves membership; a non-member, an unknown workspace and another tenant's workspace are all **404** (the existing C1 behaviour, unchanged). A body that carries `tenantId`, `workspaceId`, `credentialRef`, `id`, `createdBy` … is refused with **400** (strict parsers: unknown key = error).
- **Authorisation is C1's, unchanged.** Every call goes `GatewayGuard` → `C3GatewayAuthorizerAdapter` → C1 `GatewayAuthorizer`, default deny. Reads need `DATA_SOURCE_VIEW`, every change (and test connection, and binding changes) needs `DATA_SOURCE_MANAGE`. Today only `WORKSPACE_ADMIN` holds `DATA_SOURCE_MANAGE`; workspace `EDITOR`/`PUBLISHER`/`VIEWER` hold neither at workspace level. Not allowed → **403** `PERMISSION_DENIED`. No policy was added or changed.
- **No existence oracle.** A data source (or project) of another workspace, of another tenant, a tenant-level source and a random id all answer the **same 404** `NOT_FOUND` with the same message. Binding a source that is not the caller's workspace's is the same 404.
- **No secret is ever returned, logged or audited.** No response field can hold a credential or the reference to one (`credentialRef` is not in any projection). Error messages are fixed text; none contains a value from the request. Audit payloads carry ids, versions, status names and counts only.
- Error body (all `ConnectorFailure`-based errors): `{"code": "...", "message": "...", "requestId": "..."}`. `RATE_LIMITED` also sends `Retry-After`. A 404 from the workspace/project proof (`access.*`) uses the platform's `ApiError` shape instead (`code` = `WORKSPACE_NOT_FOUND` / `PROJECT_NOT_FOUND`) — treat any 404 as "not there".
- Admin changes are throttled per tenant: 60 changing calls / 60 s (`RATE_LIMITED` 429). Reads are not throttled.

## 2. Endpoints

Base: `/api/v1/workspaces/{workspaceId}`

| Method + path | Needs | Success | Notes |
|---|---|---|---|
| `GET /data-sources/connectors` | VIEW | 200 | catalogue of types (available + planned) |
| `GET /data-sources` | VIEW | 200 `{items:[DataSource]}` | the workspace's data sources only |
| `POST /data-sources` | MANAGE | **201** DataSource | body: `name`, `type`, `config?`, `credential?` |
| `GET /data-sources/{id}` | VIEW | 200 DataSource | |
| `PATCH /data-sources/{id}` | MANAGE | 200 DataSource (as it is afterwards) | body: any of `name`, `config` (replaces the whole config), `status` (`ACTIVE`/`DISABLED`); at least one |
| `DELETE /data-sources/{id}` | MANAGE | **204** | 409 `CONFLICT` while an application binding uses it |
| `GET /data-sources/{id}/credential` | VIEW | 200 CredentialMetadata | never the secret |
| `PUT /data-sources/{id}/credential` | MANAGE | 200 CredentialMetadata | body `{"credential":{…}}`, write-only; replaces any existing one |
| `DELETE /data-sources/{id}/credential` | MANAGE | **204** | removing nothing is also 204 |
| `POST /data-sources/{id}/test` | MANAGE | 200 TestResult | runs the connector's own connection test with the stored credential |
| `GET /projects/{projectId}/data-bindings` | VIEW (+ `PROJECT_EDIT`) | 200 `{items:[Binding]}` | |
| `PUT /projects/{projectId}/data-bindings/{mode}/{slotId}` | MANAGE (+ `PROJECT_EDIT`) | 200 Binding | `mode` = `LIVE` or `TEST` (case-insensitive); body `{"dataSourceId":"…"}` |
| `DELETE /projects/{projectId}/data-bindings/{mode}/{slotId}` | MANAGE (+ `PROJECT_EDIT`) | **204** | 404 if there is no such binding |

`{slotId}` = the data-source id used inside the AppDefinition (`dataSources[].id`, e.g. `erp-db`), `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`.

### 2.1 Shapes

```jsonc
// DataSource
{ "id": "uuid", "workspaceId": "uuid", "name": "billing-db", "type": "postgres",
  "config": { "host": "db.example.com", "port": "5432", "database": "shop", "schemas": "shop", "writable": "true" },   // all values are strings
  "hasCredential": true, "status": "ACTIVE", "version": 3,
  "createdBy": "uuid|null", "createdAt": "ISO-8601", "updatedAt": "ISO-8601" }

// CredentialMetadata   (the ONLY credential answer; keys are NAMES, never values)
{ "configured": true, "type": "postgres", "keys": ["username", "password"],
  "updatedAt": "ISO-8601|null", "updatedBy": "uuid|null" }          // configured=false -> updatedAt/updatedBy null

// TestResult   (a failed test is still HTTP 200)
{ "ok": true, "latencyMs": 12, "warnings": [] }           // warnings = fixed-text advisories, e.g. "the database role can write to 3 table(s); use a SELECT-only role"
{ "ok": false, "code": "AUTH_REJECTED", "message": "…fixed text…" }

// Binding
{ "mode": "LIVE", "slotId": "erp-db", "dataSourceId": "uuid", "updatedAt": "ISO-8601|null" }

// Connector catalogue item
{ "type": "postgres", "displayName": "PostgreSQL", "status": "AVAILABLE|PLANNED", "capabilities": ["DISCOVERY","MUTATION","QUERY"],
  "configKeys": [{ "name": "host", "required": true, "description": "…" }], "credentialKeys": ["username","password"], "notes": "…" }
```

Request rules: `name` `^[A-Za-z0-9][A-Za-z0-9 ._-]{0,79}$`, unique per tenant, case-insensitive (409 `CONFLICT`). `config`: at most 30 keys; values may be text, whole numbers or booleans (written as text); a key or value that looks like a secret (`password`, `token`, `apikey`, `scheme://user:pass@host`, …) is refused with 400 `INVALID_CONFIG` — secrets go in `credential`. `credential`: 1–8 keys, text values only, must match the connector's `credentialKeys` shape.

`updatedBy` is the user of the last `DATASOURCE_CREDENTIAL_ROTATED` (or the creation that carried a credential) audit event — no schema change. `updatedAt` is the credential row's own timestamp.

## 3. Data source types

| `type` | Status | Notes |
|---|---|---|
| `postgres` | AVAILABLE | read-only by default; `writable:true` enables approved INSERT/UPDATE/DELETE (see `WRITABLE_POSTGRES.md`). config: `host`*, `database`*, `port`, `schemas`, `timeoutMs`, `maxRows`, `maxResponseBytes`, `writable`, `maxAffectedRows`. credential: `username`, `password`. `sslmode` can only be `verify-full` (the default). Target must be a public host with a valid certificate; the platform's own databases are refused. |
| `rest` | AVAILABLE | read-only; see the catalogue for its keys |
| `mysql`, `csv`, `graphql`, `google_sheets`, `odoo`, `salesforce` | PLANNED | listed in the catalogue; `POST` answers **501** `NOT_IMPLEMENTED` |
| anything else | – | **422** `UNSUPPORTED_TYPE` |

C5 should build the form from `GET /data-sources/connectors` (`configKeys`, `credentialKeys`, `status`) rather than hard-coding the table above.

## 4. Contracts C5 needs

**Credential metadata.** After `POST` with a credential, `PUT` or `DELETE`, call (or use the answer of) `GET …/credential`. Show `configured`, the key names, `updatedAt`, `updatedBy`. There is no way to read or reveal a secret; "edit" means "replace" (`PUT` the whole credential again — the old secret is never read). Never prefill a password field. Clear any password input as soon as the request is sent.

**Test connection.** `POST …/{id}/test` with an empty body. HTTP 200 always means "the test ran"; read `ok`. When `ok:false`, `code` is one of: `AUTH_REJECTED` (wrong user/password), `CONNECT_FAILED`, `HOST_UNRESOLVED`, `ADDRESS_BLOCKED` (private/local/platform address), `TLS_FAILED`, `TIMEOUT`, `ROLE_TOO_PRIVILEGED` (superuser / admin role), `INVALID_CREDENTIAL` (credential missing a key), `NOT_IMPLEMENTED`, `INTERNAL`. A writable PostgreSQL source is tested through a read-write session; if its role has no write privilege on the configured schemas the test still passes (`ok:true`) with a `warnings` entry ("writable is enabled but the database role has no write privilege…"); a read-only source whose role *can* write also gets a warning. Show warnings next to a green result. Non-200: 404 (not yours/not there), 409 `DISABLED` (enable it first), 403, 429.

**Bindings.** An application's AppDefinition names slots (`dataSources[].id`); an operator binds each slot per mode: `TEST` (editor preview and dry runs, never writes) and `LIVE` (published app, real data). `PUT` creates or re-points; `DELETE` unbinds. A source can only be bound to a project of its own workspace. Until `LIVE` is bound, LIVE data calls answer `DATA_SOURCE_UNBOUND` (422). Deleting a data source that is still bound is **409** — unbind first.

**Error codes the UI must handle** (routes above):

| HTTP | `code` | Meaning / UI |
|---|---|---|
| 400 | `INVALID_PARAMS` | malformed body / unknown field / bad slot or mode — fix the form |
| 400 | `INVALID_CONFIG` | invalid name or configuration, or a secret in the configuration |
| 401/403 | – / `PERMISSION_DENIED` | not signed in / lacks `DATA_SOURCE_MANAGE`: hide or disable the controls |
| 404 | `NOT_FOUND` (and workspace/project 404s) | does not exist **or** not yours — identical on purpose |
| 409 | `CONFLICT` | name already used in the tenant, or delete while bound |
| 409 | `DISABLED` | test connection on a disabled source |
| 422 | `UNSUPPORTED_TYPE` | unknown type |
| 429 | `RATE_LIMITED` | wait `Retry-After` |
| 501 | `NOT_IMPLEMENTED` | planned connector |
| 500 | `INTERNAL` | fixed message, no detail |

## 5. What is deliberately not in this API

Approved queries and mutations (`data_queries`, `data_mutations`) have **no management endpoint**: they are still seeded through the repositories/fixtures (`JdbcQueryCatalog`, `JdbcMutationCatalog`). A tenant that creates a data source over HTTP cannot yet define a mutation over HTTP. This is a known limitation, not a hidden gap; it needs its own contract (it is the next item for C0/C3/C5 — see the handoff). Schema discovery, sync, webhooks and the AI catalogue endpoints are unchanged.

## 6. Known limitations

- `UNIQUE (tenant_id, name)`: a name conflict (409) is visible across the workspaces of **one tenant** — a workspace admin can learn that a name is taken in a sibling workspace of the same tenant. It never crosses tenants. Fixing it needs a schema change (unique per workspace) and is not part of this batch.
- `updatedBy` comes from the audit trail; if the audit row is unavailable it is `null`.
- `PATCH` with `config` replaces the whole configuration (send every key you want to keep).
- Operator note: never enable `DEBUG` for `org.springframework.web` in production — Spring then logs request bodies of JSON endpoints (platform-wide, including login and `PUT …/credential`). Default is INFO.
