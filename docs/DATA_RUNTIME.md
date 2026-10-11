# Data Runtime

Canonical reference for how XWEB (System Web Studio) connects an application to customer data: data sources, credentials, network policy, queries, mutations, the Data Gateway, the Management API, the runtime API and the Public Runtime.

- Audience: engineers who have never seen the project. Language: English. No chronology; the system is described as it is.
- State described: `integration/v2` as of `{{INTEGRATION_SHA}}` (verified against the local `integration/v2` at HEAD `28376de`; this includes the C1 final hardening decision A, section 10.3, and the C4 approval runtime, which only touches `docs/ACTION_WORKFLOW.md`). The release candidate is `{{FINAL_RC_SHA}}`; deployed frontend `{{PUBLIC_FRONTEND_SHA}}`, deployed API `{{PUBLIC_API_SHA}}`.
- Status vocabulary: DONE / PARTIAL / BLOCKED / DEFERRED. `UNVERIFIED` = not checked against code or a document (the closing section says what would verify it).
- Related canonical docs: `docs/ARCHITECTURE.md`, `docs/contracts/v2/data-runtime.md`, `docs/contracts/v2/management-api.md`, `docs/contracts/v2/runtime-api.md`, `docs/contracts/v2/published-runtime.md`, and `docs/ACTION_WORKFLOW.md` (actions, workflows, queue, approvals).

Citation convention: `(src: B/<path>)` means `backend/src/main/kotlin/com/systemwebstudio/<path>`; `(src: docs/...)`, `(src: scripts/...)`, `(src: infra/...)` and `(src: backend/src/main/resources/...)` are repo-relative. Where a contract document and the code disagree, the code wins and the disagreement is noted.

---

## 1. What the Data Runtime is

The Data Runtime is the only path from an application to external data. Nothing in a browser, an AppDefinition or an action can carry SQL, a URL, a header or a credential to a data source.

```
UI -> AppDefinition / ViewModel -> Query | Action -> Auth + Permission -> Data Gateway -> Connector -> external system -> Mapping/Transform -> UI
```

(src: docs/contracts/v2/data-runtime.md section 1; CLAUDE.md "Luồng dữ liệu")

Layers and where they live (all under `B/`):

| Layer | Package / class | Responsibility |
|---|---|---|
| HTTP, Spring wiring, JDBC adapters | `wiring/**` | Controllers, error envelope, flags, `DataRuntimeConfiguration`, JDBC stores (`wiring/persistence/**`) |
| Gateway | `data/gateway/DataGateway.kt` (`DefaultDataGateway`) | The only front door: authorization, tenant scoping, definition lookup, cache, idempotency, audit |
| Data source service | `data/datasource/DataSourceService.kt` | Tenant/workspace-scoped lookup, status check, throttle, credential decrypt, connector call, audit |
| Admin services | `data/datasource/DataSourceAdminService.kt`, `data/query/DefinitionAdminService.kt`, `data/discovery/DiscoveryService.kt` | Management API domain rules |
| Connectors | `data/datasource/postgres/**`, `data/datasource/rest/**`, `ConnectorSpi.kt` | One class per source type |
| Mapping | `data/mapping/**` | Result rows to ViewModel data |
| Cache | `data/cache/QueryCache.kt` | Redis result cache keyed by tenant + data source + query + params + versions |

Authorization is not in the data layer: it is a port (`GatewayAuthorizer`) implemented by C1 policy code (`access/adapters/GatewayAuthorizer.kt`) and adapted in `wiring/C1PortAdapters.kt`. Default is deny (`DenyAllAuthorizer`). (src: B/data/gateway/GatewaySecurity.kt, B/wiring/C1PortAdapters.kt)

### 1.1 Terms

| Term | Meaning |
|---|---|
| Data source | A registered, tenant- and workspace-owned connection definition: type, name, non-secret config, credential reference, status, version. (src: B/data/datasource/DataSource.kt) |
| Slot | The local id of a data source inside an AppDefinition (`dataSources[].id`, for example `erp-db`). The document stores local ids; it never stores connection data. (src: B/app/definition/AppDefinitionModel.kt `DataSourceDef`) |
| Binding | A row linking (project, mode, slot) to a registered data source. `mode` is `TEST` or `LIVE`. (src: backend/src/main/resources/db/migration/V28__data_runtime.sql `data_source_bindings`) |
| Query definition | An approved, server-side read operation of one data source (SQL template or REST path template). The only thing a browser can name. |
| Mutation definition | An approved write operation of one data source (for PostgreSQL: an INSERT/UPDATE/DELETE target with typed parameters). |
| Operation key | `queries[].operationKey` in the AppDefinition = the id of the approved query or mutation in the data platform catalog. |
| Mapping / ViewModel | AppDefinition objects that shape result rows for the UI. Stored in the AppDefinition, evaluated by `data/mapping/**`. |

---

## 2. Feature flags and defaults

Every V2 switch is OFF unless set. A disabled flag means the controller/bean does not exist, so the route answers 404 (not 503). (src: backend/src/main/resources/application.yml lines 201-244 and 277-290; docs/contracts/v2/management-api.md section 1 rule 4)

| Property (env var) | Default | Effect |
|---|---|---|
| `app.data-platform.enabled` (`DATA_PLATFORM_ENABLED`) | `false` | Mounts the Management API (3 controllers), the runtime query route (R1), `DataRuntimeConfiguration` (stores, vault, registry, gateway) and `DataManagementConfiguration`. (src: B/wiring/DataRuntimeConfiguration.kt, B/wiring/DataManagementControllers.kt, B/wiring/AppRuntimeDataController.kt) |
| `app.sites.public-data.enabled` (`SITES_PUBLIC_DATA_ENABLED`) | `false` | Together with `app.data-platform.enabled`, mounts the Public Runtime route and the release allow-list provider. (src: B/wiring/PublicDataController.kt `CONDITION`) |
| `app.data-platform.webhooks-enabled` (`DATA_PLATFORM_WEBHOOKS_ENABLED`) | `false` | Declared in `application.yml`; **no code in `backend/src/main` reads this property** (grep). Webhook ingest/sync code exists in `data/sync/**` but no controller or bean is wired (section 15). |
| `app.data-platform.idempotency-retention` (`DATA_PLATFORM_IDEMPOTENCY_RETENTION`) | `P30D` | Minimum life of a `data_idempotency` row; the store refuses less than 7 days and the application does not start. (src: B/wiring/persistence/JdbcIdempotencyStore.kt) |
| `app.data-platform.idempotency-purge-delay-ms` | `3600000` | Period of the purge runner. (src: B/wiring/persistence/IdempotencyPurgeRunner.kt) |
| `app.data-platform.management.max-body-bytes` (`DATA_MANAGEMENT_MAX_BODY_BYTES`) | `65536` | Request body limit of Management routes; must be 1024..1048576; larger = 413. (src: B/wiring/ManagementTransport.kt) |
| `app.data-platform.postgres-targets.allowed-private` (`DATA_PLATFORM_POSTGRES_ALLOWED_PRIVATE`) | empty (base and `prod`); `127.0.0.1:15440` in profile `local` | Exact `host:port` private endpoints a PostgreSQL data source may reach (section 5). |
| `app.data-platform.postgres-targets.denied` (`DATA_PLATFORM_POSTGRES_DENIED`) | empty | Extra denied targets. |
| `app.sites.public-data.max-body-bytes` | `16384` | Public route body limit (256..1048576). |
| `app.sites.public-data.max-rows` | `100` | Row cap of a public call. |
| `app.sites.public-data.max-response-bytes` | `262144` | Larger response = 502 `RESPONSE_TOO_LARGE`. |
| `app.sites.public-data.rate.ip-burst` / `.ip-burst-window-seconds` | `30` / `10` | Per client address burst. |
| `app.sites.public-data.rate.site-ip-per-minute` | `120` | Per site and address. |
| `app.sites.public-data.rate.site-per-minute` | `1200` | Per site. |
| `app.sites.data-api-base` (`SITES_DATA_API_BASE`) | blank (local: set by `scripts/_env.sh` when `PORTALS=1`) | Browser-facing base of the public route, delivered in `__factory/config.json` as `apiBase`. |
| `SECRETS_MASTER_KEY` (env only) | none | Required to seal or open any credential; without it `SECRETS_UNAVAILABLE` (section 4). |

The local V1 stack turns the three runtime flags on in `scripts/_env.sh` when `PORTALS=1` (`DATA_PLATFORM_ENABLED=true WORKFLOW_ENABLED=true PUBLISH_CONFIGS_ENABLED=true SITES_PUBLIC_DATA_ENABLED=true`). Production sets none of them by default. (src: scripts/_env.sh)

`ProductionConfigValidator` refuses a loopback/`localhost`/`0.` entry in `allowed-private` under the production profile. (src: B/common/ProductionConfigValidator.kt lines 37-38)

---

## 3. Data source model

### 3.1 Types

| `type` | Status | Capabilities | Notes |
|---|---|---|---|
| `postgres` | AVAILABLE | DISCOVERY, QUERY, MUTATION | Read-only unless config `writable=true`. TLS `verify-full` only. |
| `rest` | AVAILABLE | DISCOVERY, QUERY | Read-only, HTTPS only, GET of declared path templates. |
| `mysql`, `csv`, `graphql`, `google_sheets`, `odoo`, `salesforce` | PLANNED | listed only | Listed by `GET /data-sources/connectors`; `POST` answers 501 `NOT_IMPLEMENTED`. |
| anything else | - | - | 422 `UNSUPPORTED_TYPE`. |

(src: B/data/datasource/ConnectorSpi.kt `PlannedConnectors`, B/data/datasource/DataConnector.kt `DataConnectorRegistry`, B/wiring/DataRuntimeConfiguration.kt registry bean)

The registry also accepts any other `DataConnector` Spring bean; tests use this to plug in a writable test connector. (src: B/wiring/DataRuntimeConfiguration.kt)

### 3.2 Row and rules

Table `data_sources` (src: backend/src/main/resources/db/migration/V28__data_runtime.sql): `id`, `tenant_id` (FK `tenants`), `workspace_id`, `type`, `name`, `status` (`ACTIVE`|`DISABLED`), `config_nonsecret` JSONB, `credential_ref`, `created_by`, timestamps, `version`.

- Name: `^[A-Za-z0-9][A-Za-z0-9 ._-]{0,79}$`, unique per tenant, case-insensitive (409 `CONFLICT`). Known limit: the uniqueness is per tenant, so a workspace admin can learn that a name is taken in a sibling workspace of the same tenant (never across tenants). (src: B/data/datasource/DataSourceAdminService.kt; docs/parallel/c3/MANAGEMENT_API.md section 6)
- Config: at most 30 keys, text values (numbers and booleans are accepted and stored as text). A key matching `password|passwd|secret|token|apikey|api_key|credential|privatekey|private_key` or a value of the form `scheme://user:pass@host` is refused with 400 `INVALID_CONFIG`. Config values are visible to holders of `DATA_SOURCE_VIEW`; secrets belong in the credential. (src: B/data/datasource/DataSourceAdminService.kt `ConfigSafety`)
- Every table that belongs to a workspace carries a composite FK `(workspace_id, tenant_id)` to `workspaces`; children reference their source with `(data_source_id, tenant_id)`. A source without a workspace can never be bound. (src: V28 comments)
- `version` increases by one on every change of configuration, credential or status. It is part of every cache key, so an edit makes older cached results unreachable before the explicit invalidation lands. (src: B/data/datasource/DataSource.kt, B/data/cache/QueryCache.kt)
- Scope: the production wiring constructs services with `DataSourceScope.WORKSPACE` (tenant AND the caller's workspace, default deny). A context without a workspace reaches nothing. (src: B/wiring/DataRuntimeConfiguration.kt `c3DataSourceService`, B/wiring/DataManagementControllers.kt)
- A `DISABLED` source answers 409 `DISABLED` on use (the ownership check runs first, so status is never revealed to a foreign caller). (src: B/data/datasource/DataSourceService.kt `load`)

### 3.3 Slots and TEST vs LIVE binding

An application never stores a data source UUID in normal use. Its AppDefinition names slots; a binding per mode points each slot to a registered source.

- Table `data_source_bindings` (V28): primary key `(project_id, mode, slot_id)`, `mode IN ('LIVE','TEST')`, composite FK `(data_source_id, tenant_id, workspace_id)` to `data_sources`, so a binding can only name a source of its own tenant and workspace.
- `TEST` = the working draft: editor preview and dry runs; it **never writes** (the action layer answers `WouldRun`; the data adapter refuses a non-LIVE write anyway). `LIVE` = the published version of the ACTIVE release. (src: docs/contracts/v2/management-api.md section 3.6; B/wiring/ActionDataPortAdapter.kt)
- **TEST and LIVE are separate rows and never fall back to each other.** A slot with no binding for the requested mode resolves to `DATA_SOURCE_UNBOUND` (422). (src: B/wiring/persistence/JdbcDataSourceSlotBindings.kt, B/app/definition/AppDataBindingResolver.kt `ResolutionCodes`)
- Resolution chain, the single path for UI queries and actions: `queryRef (local id) -> QueryDef -> dataSourceRef (local) -> DataSourceDef.sourceRef (UUID) or binding of the slot -> QueryDef.operationKey -> GatewayQuery / GatewayMutation`. (src: B/app/definition/AppDataBindingResolver.kt)
- **`sourceRef` versus bindings, exactly as coded.** `AppDataBindingResolver.dataSource` reads the slot's `sourceRef` from the document first: if it is non-null it must parse as a UUID (else `INVALID_SOURCE_ID`) and **that UUID is used in both TEST and LIVE, without consulting `data_source_bindings`**. Only when `sourceRef` is null does it look up the slot in the bindings of the requested mode; no binding gives `DATA_SOURCE_UNBOUND`. The class comment says "A `sourceRef` in the document wins", and `docs/contracts/v2/app-definition.md` defines `sourceRef` as "id of a C3 DataSource (UUID, may be null = unresolved slot)", so code and contract agree. The Management contract's "TEST and LIVE never fall back to each other" is about bindings and also holds. A foreign or unknown `sourceRef` ends in the canonical 404 (section 10.3). (src: B/app/definition/AppDataBindingResolver.kt lines 63-84)
- LIVE definition = the AppDefinition snapshot of the version pinned by the active release (`sites.current_deployment_id`, deployment status `DEPLOYING` or `RUNNING`); no pointer means nothing is published and the route answers 404. The LIVE **binding** is project configuration, not release-pinned: changing a binding changes the data an existing release reads, by design. (src: B/wiring/RuntimeAppDefinitions.kt; docs/contracts/v2/published-runtime.md section 4 "Rollback behaviour"; D-C0-33)

---

## 4. Credential model

- A credential is a map of 1 to 8 entries. Entry names match `^[A-Za-z][A-Za-z0-9_]{0,31}$`, values are non-empty text of at most 4000 characters. A connector that lists `credentialKeys` accepts no other entry (400 `INVALID_CREDENTIAL`). PostgreSQL keys: `username`, `password`. REST: `authValue` (optional). (src: B/data/datasource/Credential.kt `SecretsCryptoCredentialVault`, B/data/datasource/DataSourceAdminService.kt `checkCredentialKeys`)
- **Server-side only, encrypted, never returned.** Sealed with the platform `SecretsCrypto` (AES-256-GCM, key from environment `SECRETS_MASTER_KEY`), stored as ciphertext (`v1:...`) in a separate table `data_credentials` (primary key `(tenant_id, ref)`, `CHECK (ciphertext LIKE 'v1:%')`). `data_sources.credential_ref` is an opaque reference. A list or get of data sources never touches the credentials table. (src: V28; B/data/datasource/Credential.kt)
- Decrypted material exists only inside the call: `ResolvedCredential` is not a data class (no `toString`/`copy`), prints as `ResolvedCredential(***)`, and is handed to the connector for one call. (src: B/data/datasource/Credential.kt)
- No response, error body, log line, audit payload, header or URL carries a password, token, decrypted or encrypted credential, `credentialRef`, or a connection string with an embedded secret. Error messages are fixed text; nothing from the request is echoed. (src: docs/contracts/v2/management-api.md section 1 rule 7)
- Without `SECRETS_MASTER_KEY` nothing can be sealed or opened: `SECRETS_UNAVAILABLE`. That code maps to HTTP 500 in `GatewayProblems` (the `else` branch). (src: B/data/datasource/Credential.kt, B/data/gateway/GatewayHttp.kt)
- Read: `GET .../credential` returns metadata only: `{configured, type, keys, updatedAt, updatedBy}`; `keys` are names taken from the connector descriptor, the stored material is not opened. `updatedBy` comes from the audit trail (null if unavailable). (src: B/data/datasource/DataSourceAdminService.kt `credentialInfo`)
- Write/rotate: `PUT .../credential` with `{"credential":{...}}`. "Edit" means "replace": the old secret is never read. In one unit of work the new credential is stored under a fresh reference, the data source moves to it (version + 1), `DATASOURCE_CREDENTIAL_ROTATED` is audited (also for the first set), and only then the old credential row is discarded. A failure before the discard leaves the old credential in place. (src: B/data/datasource/DataSourceAdminService.kt `rotateCredential`)
- Remove: `DELETE .../credential` (204; removing nothing is also 204) detaches and destroys it; the source stays registered and its connector answers `INVALID_CREDENTIAL` until one is set again.
- The `PUT` body is never logged or audited. Operator rule: never set `org.springframework.web` to DEBUG/TRACE in production (Spring then logs JSON request bodies, including credential bodies); a C3 test pins the configured levels. (src: docs/parallel/c3/MANAGEMENT_API.md sections 6 and 8)
- Redaction: `Redactor.redact` replaces known secret values with `***` as a second line of defence; the first line is fixed-text messages. `ConnectorFailure` has no `cause`. (src: B/data/datasource/ConnectorFailure.kt)

---

## 5. Network target policy and TLS (PostgreSQL)

### 5.1 Policy

`PostgresTargetPolicy` decides where a PostgreSQL data source may point. (src: B/data/datasource/postgres/PostgresTargetPolicy.kt; B/wiring/PostgresTargetPolicies.kt; D-C0-31)

| Rule | Detail |
|---|---|
| Default | **Public internet addresses only**, judged by the platform's one SSRF guard `PublicAddress` (via `PublicAddressPolicy`): loopback, private ranges, link-local (including cloud metadata), CGNAT, reserved and embedding forms are refused. There is no second list of ranges in the data code. |
| Resolve once and pin | The host is resolved once; every returned address must pass; one bad address refuses the host. The connection uses those addresses through `PinnedSocketFactory` (no second resolution, closing the DNS-rebinding window). (src: B/data/datasource/AddressPolicy.kt `PinnedResolution`) |
| `allowed-private` | Server-side allow-list of **exact `host:port`** endpoints (comma-separated). Every entry must name a port; no wildcard, no CIDR, no host-wide entry, no `0.0.0.0`/`::`, no link-local/multicast/broadcast; at most 20 entries. A bad entry fails construction, so the application does not start. Settable only in configuration, never through a data source's own config. |
| `denied` | Extra `host` or `host:port` targets, refused even if allow-listed. **Deny always wins over allow.** |
| Platform databases | The platform DB (`spring.datasource.url`) and the apps DB (`app.runtime.appdb-url`) are always denied: a loopback platform DB as `host:port` (a developer machine runs the platform DB, apps DB and a data target on one `127.0.0.1`), any other host entirely. If neither URL is readable the application does not start (fail closed). |
| Alias check | A different name or literal for a denied target is refused by address: denied names are resolved and compared with the target's addresses; a relevant denied entry that cannot be resolved right now refuses the connection (fail closed). |
| Save-time check | `validateConfig` runs `checkSyntax` (no DNS): refuses a denied target, `localhost`, single-label names, `.internal`/`.local`/`.localdomain`/`.lan` and private/loopback IPv4 literals unless that exact `host:port` is allow-listed. A name that resolves to a private address passes at save time and is refused at use (`ADDRESS_BLOCKED`). |
| Run-time check | `policy.resolve(host, port)` runs on every session (query, discovery, sampling, test, mutation). Stored config edited directly in the database is refused again at run time. |

Defaults by profile: base and `prod`: nothing private allowed. Profile `local`: `127.0.0.1:15440` only (the V1 local data target). V2: the remote host name of an internal replica, by configuration only. (src: backend/src/main/resources/application.yml, application-local.yml)

### 5.2 TLS and trust store

- `sslmode` accepts only `verify-full` (also the default). `require`, `verify-ca`, `prefer`, `allow` and `disable` are rejected at config parse; a connection is built with `ssl=true`, `sslmode=verify-full`, `sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory`. Chain **and host name** are verified against the data source's `host`, not against the pinned address. (src: B/data/datasource/postgres/PostgresConnectorConfig.kt, B/data/datasource/postgres/PostgresConnector.kt `PgConnectionProperties`)
- The trust store is the **JVM trust store**. A tenant cannot supply a CA, a certificate path or a driver option. A customer database signed by a private CA works only if that CA is in the backend JVM's trust store (an operator action).
- Local V1 data target: `scripts/data-target.sh up` creates a dev CA and an IP-SAN certificate, builds `.run/data-target/truststore.jks` (JDK `cacerts` plus the dev CA, generated password), starts a TLS PostgreSQL on `127.0.0.1:15440` and seeds the `shop` demo database (roles `shop_ro` SELECT-only, `shop_rw`). `scripts/_env.sh` passes the trust store to the API JVM as `JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=... -Djavax.net.ssl.trustStorePassword=..."` and sets `DATA_PLATFORM_POSTGRES_ALLOWED_PRIVATE=127.0.0.1:15440`. (src: scripts/data-target.sh, scripts/_env.sh, docs/parallel/c5/e2e-stack.sh)
- Proof recorded for the release-candidate stack: `openssl s_client -starttls postgres -CAfile ca.crt -verify_return_error` returned `Verify return code: 0`; the API JVM carried `javax.net.ssl.trustStore`; a data source on `127.0.0.1:15440` with the `shop_ro` credential answered `POST .../test` with `{ok:true}`. (src: docs/parallel/DECISIONS.md D-C0-59 item 4; recorded evidence, not re-run here)
- Platform rule that follows: a TLS error in the middle of a write is classified `CONNECT_FAILED` (ambiguous) rather than `TLS_FAILED` (definite), because the statement may already have been sent (section 9.4).

---

## 6. Connectors

### 6.1 PostgreSQL

Config keys (src: B/data/datasource/postgres/PostgresConnectorConfig.kt): any other key is refused.

| Key | Meaning | Default / range |
|---|---|---|
| `host` | DNS name or IPv4 literal; `^[A-Za-z0-9.-]{1,253}$`; checked by the target policy | required |
| `port` | | 5432 (1..65535) |
| `database` | `^[A-Za-z0-9_.-]{1,63}$` | required |
| `sslmode` | only `verify-full` | `verify-full` |
| `schemas` | comma-separated schemas discovery lists and mutations may target (`^[A-Za-z_][A-Za-z0-9_]{0,62}$`, at most 20) | `public` |
| `timeoutMs` | connect/login/socket/statement timeout | 10000 (500..30000) |
| `maxRows` | rows per query | 1000 (1..10000) |
| `maxResponseBytes` | bytes per query result | 2,000,000 (1000..5,000,000) |
| `writable` | `"true"` lets approved mutations run | `false` |
| `maxAffectedRows` | most rows one mutation may change | 1000 (1..100000) |

Session guarantees (src: B/data/datasource/postgres/PostgresConnector.kt):

- Credentials are driver properties, never in the URL. Socket pinned to the checked addresses. Options: `statement_timeout`, `lock_timeout=2000`, `idle_in_transaction_session_timeout`.
- Query, discovery, sampling and connection-test sessions are **read-only** (`default_transaction_read_only=on`, `Connection.setReadOnly(true)`, a `SELECT 1` before anything caller-supplied to close the read-write switch window, always rolled back) whatever `writable` says.
- **Preflight on every connection** (never cached): transaction read-only (or READ WRITE for a mutation session), `standard_conforming_strings=on`, and the role must not be a superuser or reach one through membership, must not be a member of any `pg_*` role, and must not have `CREATEROLE`, `CREATEDB`, `REPLICATION` or `BYPASSRLS`. Violations are `ROLE_TOO_PRIVILEGED` (fixed text, role names never reported).
- `SqlGuard` (defence in depth, not the guarantee): single `SELECT`/`WITH` statement, only `:name` placeholders, no writing keywords or session-changing functions (including inside a data-modifying CTE), `U&"..."` Unicode-escaped identifiers refused, credential-bearing catalog relations refused. Registered at definition time and re-checked before every run.
- Operator role checklist for a data source: a dedicated login role `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`, member of no `pg_*` role; `GRANT USAGE ON SCHEMA`; for writes `GRANT SELECT, INSERT, UPDATE, DELETE` on only the tables concerned (SELECT is needed for `WHERE` and `RETURNING`) and `USAGE` on sequences of serial/identity columns. (src: docs/parallel/c3/WRITABLE_POSTGRES.md section 4)
- Connection test (`POST .../test`): a read-only source whose role can write returns `ok:true` with a warning ("use a SELECT-only role"); a writable source is tested through a READ WRITE session and warns when the role has no write privilege on the configured schemas.

### 6.2 REST

Config (src: B/data/datasource/rest/RestConnectorConfig.kt): `baseUrl` (`https://host[:port][/prefix]`, no credentials, no query; host must be a public name), `authHeader` (header carrying the `authValue` credential; hop-by-hop and forbidden headers refused), `testPath` (default `/`), `timeoutMs`, `maxResponseBytes`, `maxRows`. Unknown keys refused. Outbound traffic goes through `PinnedHttpsTransport` (resolve once, pin, no redirects, size/time/row caps). Read-only: a mutation definition on a REST source is 422 `READ_ONLY_VIOLATION`.

---

## 7. Schema discovery

- `POST /api/v1/workspaces/{ws}/data-sources/{id}/schema/discover` with optional `{"includeSamples": bool}` (default false) creates a new immutable snapshot version (table `source_schemas`: `version`, `fingerprint`, `includes_samples`, `snapshot` JSONB) and audits `DATASOURCE_SCHEMA_REFRESHED` in the same unit of work. `GET .../schema` returns the latest snapshot; 404 `NOT_FOUND` until one exists.
- Cooldown: one refresh per 30 s per (tenant, data source), else 429 `REFRESH_TOO_SOON`. Throttle of the underlying connector call: 10 per minute per (tenant, data source). A concurrent refresh that wins the version triggers one retry, then 409 `CONFLICT`.
- Content: entities (tables), fields (normalized type, nullable, primary key, source type), relations (foreign keys). PostgreSQL discovery reads `information_schema` and `pg_constraint` for the configured `schemas` only.
- Samples are off by default, bounded (at most 5 rows, 50 entities), **masked by field name and value shape** (`SampleMasker`) inside the connector and re-masked in `DiscoveryService`. `includeSamples:true` additionally requires `QUERY_EXECUTE` (permission code, section 11.2), because even masked rows are data. A per-table failure (privileges, timeout) costs a warning, not the discovery (savepoint per sample).
- `AiSafeSchema` is the projection that an AI model may see; it is built from the stored snapshot. (src: B/data/discovery/DiscoveryService.kt, SampleMasker.kt, AiDataCatalog.kt; docs/contracts/v2/data-runtime.md section 5)

---

## 8. Query definitions

Stored in `data_queries` (primary key `(tenant_id, data_source_id, query_id)`; `kind`, `definition` JSONB, `status` ACTIVE|DISABLED, `version`). Created only through the Management API; re-validated by the Kotlin definition types on every load, so stored JSON is never trusted. Only `ACTIVE` rows are visible to the gateway. (src: V28; B/wiring/persistence/JdbcDefinitionCatalogs.kt; B/data/query/DefinitionDocuments.kt)

`queryId` matches `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`.

### 8.1 SQL query (`kind: "SQL"`)

Document keys (strict, unknown key = 400 `INVALID_QUERY`): `sql` (at most 20,000 chars), `params`, `maxRows`, `cacheTtlSeconds`. The text uses named parameters `:name`; every placeholder must be declared in `params`. Values are only ever bound as prepared-statement parameters. The caller's page is applied by the database as `SELECT * FROM (<approved sql>) AS xweb_q LIMIT ? OFFSET ?`.

### 8.2 REST query (`kind: "REST"`)

Keys: `pathTemplate` (starts with `/`, plain segments or `{param}`, no `..`), `params`, `queryParams` (param name to query-string key), `rowsPointer` (JSON Pointer to the rows, empty = root), `maxRows`, `limitParam`, `offsetParam`, `discoverable`, `cacheTtlSeconds`.

### 8.3 Parameters

`params[]` entries: `{name, type, required (default true), default}`. Name `^[a-z][A-Za-z0-9_]{0,39}$`; types `STRING | INTEGER | NUMBER | BOOLEAN | TIMESTAMP | DATE`. At most 40 parameters per definition and per call. Coercion: strings at most 1000 chars and no NUL; INTEGER fits `long`; NUMBER finite; TIMESTAMP ISO offset date-time; DATE ISO date. Unknown names, missing required values and wrong types are `INVALID_PARAMS` (nothing executed). Canonical bound parameters over 4096 bytes are refused. (src: B/data/query/Query.kt `QueryParams`; B/data/gateway/DataGateway.kt `MAX_PARAMS`, `MAX_PARAMS_BYTES`)

### 8.4 Paging, caps, cache

| Item | Rule |
|---|---|
| Page | `{limit 1..10000, offset 0..1000000}` (`PageSpec`). |
| Rows returned | `min(definition.maxRows, data source maxRows, page.limit)`; the connector reads `limit+1` rows to report `truncated`. Definition `maxRows` 1..10000 (default 1000). |
| Bytes | Rows beyond `maxResponseBytes` are dropped and `truncated=true` (the offending row is not returned). |
| Time | `statement_timeout` / query timeout = `timeoutMs`. |
| Runtime default page | Route R1 uses the AppDefinition query's `maxRows` as the page limit when the client sends no page. |
| Cache TTL | `cacheTtlSeconds` 0..86400; `0` = never cached. A TTL applies to the **mapped** result. The cache key contains tenant, data source, query, data source version, query version, mapping id and version, view model id, canonical params and page. It does **not** contain the caller: a cached query must not depend on who asks. |
| Invalidation | Generation counters (no `SCAN`): any data source/definition change, successful mutation (`invalidates` list; empty = whole data source) or manual refresh bumps a counter. If a counter cannot be incremented the scope is bypassed in that process until the longest TTL passes. Multi-node bypass knowledge is per process (documented limit, D-C3-06). |
| Stale-write guard | The key is captured at lookup and reused for the write (`CacheTicket`), so an invalidation between read and fill cannot be overwritten by an older answer. |

(src: B/data/cache/QueryCache.kt, B/data/gateway/DataGateway.kt `runQuery`)

### 8.5 The `public` flag (public-site queries)

`public` is **not** a field of the data platform query definition. It is `queries[].public` (wire key `public`, Kotlin `QueryDef.isPublic`, default false) in the AppDefinition. The validator refuses `public: true` on a non-READ query. The release allow-list is the set of `public` READ queries in the immutable version snapshot of the release (section 13). (src: B/app/definition/AppDefinitionModel.kt `QueryDef`, `PublicQueries`; B/app/definition/AppDefinitionValidator.kt line 107)

---

## 9. Mutation definitions (writable PostgreSQL)

A mutation definition is the write-side twin of a query definition (table `data_mutations`). It never carries SQL, a URL or a credential; a caller never supplies one. (src: B/data/query/Mutation.kt)

```
MutationDefinition(id, kind CREATE|UPDATE|DELETE|SUBMIT, target (<=200 chars), params[], invalidates[], entity?, version)
```

### 9.1 Gate: `writable:true`

A PostgreSQL data source accepts a mutation definition only when its config has `writable: "true"`; otherwise the Management API answers 422 `READ_ONLY_VIOLATION`, and a mutation that reaches the connector anyway answers `READ_ONLY_VIOLATION` before any connection is opened. Queries, discovery and the connection test keep their read-only sessions whatever `writable` says. (src: B/data/datasource/postgres/PostgresConnector.kt `validateMutationDefinition`, B/data/datasource/postgres/PostgresMutationExecutor.kt)

### 9.2 Target grammar

```
[schema.]table [key=p1[,p2...]] [returning=col1[,col2...]]
```

| kind | Statement | Rules |
|---|---|---|
| `CREATE` | `INSERT INTO "s"."t" (supplied params) VALUES (?, ...) [RETURNING ...]` | no `key` |
| `UPDATE` | `UPDATE "s"."t" SET <supplied non-key params> WHERE key = ? [AND ...] [RETURNING ...]` | `key` required, declared and supplied; at least one column to set |
| `DELETE` | `DELETE FROM "s"."t" WHERE key = ? [AND ...] [RETURNING ...]` | `key` required; no parameter other than keys |
| `SUBMIT` | none | `MUTATION_UNSUPPORTED`; refused at definition time as `INVALID_QUERY` |

- A parameter's **name is its column name** (`^[a-z][A-Za-z0-9_]{0,39}$`). A key column such as `OrderId` therefore cannot be a key parameter (pre-existing limit).
- Identifiers are validated, always double-quoted, and the schema must be one of the data source's `schemas`. **Every value is a bound parameter**; no code path writes a value into SQL text; no log, exception or `toString` carries a value or SQL. UPDATE/DELETE without a key cannot be expressed. Optional parameters that are not supplied are omitted (INSERT: column default; UPDATE: column untouched).
- Result: `affected` = rows changed (with `returning=`: rows returned); `output` = the first returned row as a JSON object, or null. UPDATE/DELETE matching nothing succeed with `affected = 0` (the caller cannot tell "no such row" from "changed"; a "not found" answer would be a deliberate contract change). The gateway answer is `{operation, kind, affected, replayed, output}`; `output` is kept only if at most 16 KiB.
- Transaction: one connection, one explicit transaction, one statement, **one `commit()`**. A statement that would change more than `maxAffectedRows` is rolled back and refused (`MUTATION_REJECTED`). Uncommitted work is rolled back before the connection closes.

(src: B/data/datasource/postgres/PostgresMutationExecutor.kt; docs/parallel/c3/WRITABLE_POSTGRES.md sections 1-3)

### 9.3 Logical record identifier and the mutation target key metadata (FQ-ACT-02)

Actions name the row they change by the input `recordId` (a logical identifier). The physical key column is already approved metadata: the `key=` of the mutation target. `DefaultDataGateway.mutate` resolves the identifier before binding parameters:

```
QueryParams.bind(def.params, RecordKey.resolve(def, service.recordKey(ds, def), mutation.params))
```

Rules of `RecordKey.resolve` (src: B/data/query/RecordKey.kt; B/data/datasource/ConnectorSpi.kt `MutationExecutor.recordKey`):

1. Only UPDATE and DELETE, only when `recordId` is given, only when the definition does **not** itself declare a parameter named `recordId` (every pre-existing workaround keeps working).
2. Only when the connector reports exactly one key (PostgreSQL: `PgMutationTarget.parse(...).keys.singleOrNull()`; invalid, several or none = null) and that key is a declared parameter.
3. `recordId` together with the key parameter itself is refused (`INVALID_PARAMS`, ambiguous).
4. An INTEGER key accepts a whole-number text (`"42"` becomes 42).
5. Otherwise nothing changes and the usual `INVALID_PARAMS` answers (definite: nothing executed, idempotency key released).

The physical column never comes from the request: it comes from the approved definition, passes `PgMutationTarget` and is quoted. Authorization and idempotency run exactly as before. CREATE and SUBMIT never alias.

Operator fixture: define the mutation with the real key, for example `{"target":"shop.orders key=id","params":[{"name":"id","type":"STRING"},{"name":"status","type":"STRING","required":false}]}`; the action keeps its input `recordId`. (src: docs/parallel/audit/C4-FQ-ACT-02-record-key.md sections 3 and 5)

### 9.4 Outcome semantics (frozen, `data-runtime.md` section 4b, D-C3-14)

A write has exactly three kinds of outcome and the caller must tell them apart. A failure is **definite** (nothing applied) only when the server itself said so or the connector refused before sending; everything the connector cannot prove is **ambiguous**.

| Situation | Code thrown | Applied? | Idempotency key |
|---|---|---|---|
| Constraint violation (SQLSTATE 23xxx), schema mismatch (42xxx, 0A), transient conflict/lock/resource (40001, 40P01, 55P03, 55006, 53xxx) | `MUTATION_REJECTED` (fixed class message, never constraint or value) | no | released |
| Value does not fit column (22xxx), missing key, nothing to set, unbindable value | `INVALID_PARAMS` | no | released |
| Bad target, schema not configured, key not declared | `INVALID_CONFIG` | no | released |
| Role lacks privilege (42501) | `PERMISSION_DENIED` | no | released |
| Wrong password at connect (28P01/28000) | `AUTH_REJECTED` | no | released |
| Read-only session/source | `READ_ONLY_VIOLATION` | no | released |
| Role too privileged | `ROLE_TOO_PRIVILEGED` | no | released |
| Before any connection is usable: host not resolved, address blocked, TLS handshake | `HOST_UNRESOLVED`, `ADDRESS_BLOCKED`, `TLS_FAILED` | no | released |
| Row cap exceeded | `MUTATION_REJECTED` (rolled back) | no | released |
| Connection lost/refused/reset (08xxx, 57Pxx, I/O or TLS error mid-statement or at commit) | `CONNECT_FAILED` | **unknown** | kept as `UNKNOWN` |
| Socket timeout or `statement_timeout` (57014) | `TIMEOUT` | **unknown** | kept as `UNKNOWN` |
| Anything else the server answered or the connector cannot classify | `QUERY_FAILED` / `INTERNAL` | **unknown** | kept as `UNKNOWN` |

(src: B/data/datasource/postgres/PostgresConnector.kt `PgWriteErrors`; B/data/gateway/DataGateway.kt `NOT_EXECUTED`)

`retryable` is never true for anything a connector throws. Only `IDEMPOTENCY_IN_PROGRESS` and `RATE_LIMITED` (produced by the gateway) are retryable. A transient database conflict is `MUTATION_REJECTED` and is **not** retryable by the contract; a retryable transient code would need a new `FailureCodes` value, a `NOT_EXECUTED` entry, a `DataWriteErrors` branch, a `runtime-api.md` status and a C4 contract change (a decision, not made).

---

## 10. Data Gateway

`DataGateway` has: `runQuery(ctx, GatewayQuery)`, `mutate(ctx, GatewayMutation)`, `testConnection`, `discoverSchema`, `refreshCache`. `GatewayContext` carries tenant (`TenantContext`), actor user id and kind, workspace, project, app version id and request id; it is built by C0 wiring from `AccessContext` and is never read from a request body or client-controlled header. (src: B/data/gateway/DataGateway.kt, B/data/gateway/GatewaySecurity.kt, B/wiring/RuntimeContexts.kt)

### 10.1 Query pipeline (stops at the first refusal)

1. `guard.require(ctx, QUERY_EXECUTE, dataSourceId)` (nothing before this looks at the request's content).
2. Shape check: non-blank operation, mapping ref pattern, at most 40 params.
3. `service.resolve`: tenant- and workspace-scoped data source, then status.
4. Approved query definition (`QUERY_NOT_FOUND` if absent or if tenant/data source differ).
5. Mapping and view model from the AppDefinition version named by the context (the stored `queryRef` is the local id; the wiring translates it to the operation key on a copy); `MappingValidator.require`.
6. Bind and validate params **before** the cache is consulted (junk can neither reach a connector nor become a cache key).
7. Cache lookup (when TTL > 0), else connector call with the data source's limits, then mapping, cache fill, audit `DATA_QUERY_SERVED`.

### 10.2 Mutation pipeline

1. `guard.require(ctx, MUTATION_EXECUTE, dataSourceId)`.
2. Idempotency key must match `^[A-Za-z0-9_-]{8,128}$`; at most 40 params.
3. Resolve data source, approved mutation (`MUTATION_NOT_FOUND`), `RecordKey.resolve`, bind.
4. `idempotency.begin(tenant, dataSource, mutation, key, fingerprint)`: `Run`, `Replay` (stored result returned, `replayed:true`, connector not reached), `InProgress` (409 `IDEMPOTENCY_IN_PROGRESS`), `OutcomeUnknown` (409 `IDEMPOTENCY_OUTCOME_UNKNOWN`), `Conflict` (409 `IDEMPOTENCY_CONFLICT`: same key, different parameters).
5. `service.mutate` (per-source throttle 120/min, decrypt credential, connector).
6. On failure: only a code in `NOT_EXECUTED` releases the key; everything else calls `markUnknown`. Audit `DATA_MUTATION_RUN` with `outcomeKnown`.
7. On success: store `DONE` + affected + bounded output (failure to record does not turn a success into an error: the row stays `RESERVED`, which is the safe state), audit, cache invalidation event.

`NOT_EXECUTED` (certainly nothing applied): `PERMISSION_DENIED, INVALID_PARAMS, INVALID_CONFIG, INVALID_QUERY, INVALID_CREDENTIAL, INVALID_MAPPING, ADDRESS_BLOCKED, HOST_UNRESOLVED, TLS_FAILED, AUTH_REJECTED, RATE_LIMITED, DISABLED, NOT_FOUND, MUTATION_NOT_FOUND, MUTATION_UNSUPPORTED, MUTATION_REJECTED, TENANT_MISMATCH, ROLE_TOO_PRIVILEGED, READ_ONLY_VIOLATION, UNSUPPORTED_TYPE, PAYLOAD_TOO_LARGE`. (src: B/data/gateway/DataGateway.kt lines 245-250)

### 10.3 Authorization via `GatewayAuthorizer`, tenant/workspace isolation, canonical 404

`GatewayAuthorizer` (C1 policy core, `access/adapters/GatewayAuthorizer.kt`) maps a C3 operation to canonical permissions; **all** listed codes are required:

| Operation | Permission codes |
|---|---|
| `DATASOURCE_READ` | `DATA_SOURCE_VIEW` |
| `DATASOURCE_MANAGE`, `SCHEMA_DISCOVER`, `CACHE_REFRESH`, `SYNC_MANAGE`, `WEBHOOK_MANAGE` | `DATA_SOURCE_MANAGE` |
| `QUERY_EXECUTE`, `EVENTS_SUBSCRIBE` | `QUERY_EXECUTE` |
| `MUTATION_EXECUTE` | `DATA_MUTATE` |
| `SCHEMA_SAMPLE` | `DATA_SOURCE_MANAGE` and `QUERY_EXECUTE` |

Denied: unknown operation, non-USER actor (SYSTEM/SERVICE/APP_TOKEN: a TEMPORARY V2 policy), missing workspace (tenant-level sources have no permission holder), tenant mismatch, unknown project, app version that is not a `project_versions` row of this project and workspace. The one non-USER exception is `ActorKind.PUBLIC_SITE`, limited to `QUERY_EXECUTE` for the active public release (section 13). Any exception in the authorizer is a denial (fail closed). A denial is audited as `DATA_ACCESS_DENIED` and answers 403 `PERMISSION_DENIED`. (src: B/access/adapters/GatewayAuthorizer.kt, B/data/gateway/GatewaySecurity.kt)

**Decision A (C1 final hardening, integrated).** The authorizer does **not** check that a `dataSourceId` belongs to the caller's workspace. That check was added and then reverted: it turned the canonical 404 for a foreign or unknown data source into a 403 for the same id, which distinguishes an id that exists elsewhere from one that does not (an existence oracle) and silently changed the C3 contract. Workspace isolation of data sources stays C3's job: `DataSourceService.load` uses `findInWorkspace`, and **a data source of another workspace, another tenant, a tenant-level source, a non-UUID id and a random id all answer the same 404 `NOT_FOUND`**. `GatewayAuthorizer.kt` contains no data source ownership check (grep for `dataSourceBelongs` finds nothing on `integration/v2`), and `GatewayAuthorizerNoOracleTests` (2 `@Test`) pins that the decision does not depend on the data source id. (src: docs/parallel/c1/final-iam-hardening-report.md row 6 (marked REVERTED); B/access/adapters/GatewayAuthorizer.kt; B/data/datasource/DataSourceService.kt `load`). This matters because an AppDefinition author controls `dataSources[].sourceRef`.

Isolation layers, each independent: permission check; `findInWorkspace` in SQL; composite FKs in V28; `def.tenantId`/`def.dataSourceId` re-compared after catalog lookup ("a catalog bug must not cross tenants"); connectors compare `ds.tenantId` with the request tenant (`TENANT_MISMATCH`); cache key and envelope carry the tenant.

### 10.4 Error mapping (`GatewayProblems.status`)

| HTTP | Codes |
|---|---|
| 400 | `INVALID_PARAMS, INVALID_CONFIG, INVALID_QUERY, INVALID_CREDENTIAL, INVALID_EXPRESSION` |
| 403 | `PERMISSION_DENIED` |
| 404 | `NOT_FOUND, QUERY_NOT_FOUND, MUTATION_NOT_FOUND, SYNC_JOB_NOT_FOUND, TENANT_MISMATCH` |
| 409 | `DISABLED, CONFLICT, IDEMPOTENCY_CONFLICT, IDEMPOTENCY_IN_PROGRESS, IDEMPOTENCY_OUTCOME_UNKNOWN, SYNC_ORDER_VIOLATION` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 422 | `INVALID_MAPPING, MAPPING_FAILED, MUTATION_UNSUPPORTED, MUTATION_REJECTED, UNSUPPORTED_TYPE, READ_ONLY_VIOLATION` |
| 429 | `RATE_LIMITED, REFRESH_TOO_SOON` |
| 501 | `NOT_IMPLEMENTED` |
| 502 | `ADDRESS_BLOCKED, HOST_UNRESOLVED, CONNECT_FAILED, TLS_FAILED, AUTH_REJECTED, REDIRECT_BLOCKED, UPSTREAM_STATUS, RESPONSE_INVALID, RESPONSE_TOO_LARGE, RESPONSE_NOT_JSON, QUERY_FAILED` |
| 504 | `TIMEOUT` |
| 500 | everything else, including `INTERNAL`, `SECRETS_UNAVAILABLE`, `ROLE_TOO_PRIVILEGED` |

(src: B/data/gateway/GatewayHttp.kt `GatewayProblems.status`). Note: the first response to an ambiguous write may be 502/504/500, but the **next** attempt with the same key is 409 `IDEMPOTENCY_OUTCOME_UNKNOWN`.

For actions, `ActionDataPortAdapter` runs every write failure through `DataWriteErrors.forWrite`: `IDEMPOTENCY_OUTCOME_UNKNOWN` and `MUTATION_REJECTED` stay as they are (non-retryable); `IDEMPOTENCY_IN_PROGRESS` and `RATE_LIMITED` become retryable; `IDEMPOTENCY_CONFLICT` and every `NOT_EXECUTED` code become non-retryable "not applied"; every other code, and any non-`ConnectorFailure` throwable, becomes `IDEMPOTENCY_OUTCOME_UNKNOWN` (non-retryable). Failures before the gateway is called (unresolved reference, no gateway, refused actor) are definite and non-retryable. (src: B/wiring/DataWriteErrors.kt; docs/contracts/v2/runtime-api.md section 6; D-C0-15)

### 10.5 Idempotency store

Table `data_idempotency` (V28): primary key `(tenant_id, data_source_id, mutation_id, idem_key)`; `state` `RESERVED|DONE|UNKNOWN`; `fingerprint`; `affected`; `output_json` (at most 1 MiB); `lease_until`; `expires_at`. The key is the **derived** key of the action layer (43-char base64url of `sha256(tenantId|appId|userId|actionId|clientKey)`), never the client's raw key; C3 scopes by `(tenant, data source, mutation, key)` and has no user in the scope. Lease 300 s: a `RESERVED` row whose lease expired becomes `UNKNOWN` and is never run again. `begin` is atomic (one `INSERT ... ON CONFLICT DO UPDATE ... WHERE expires_at <= now`). Rows live at least `idempotency-retention` (default 30 days, minimum 7) whatever TTL the gateway asks (its constant is 24 h). Retention purge never touches an unexpired row. (src: B/wiring/persistence/JdbcIdempotencyStore.kt, V28)

### 10.6 Throttles (per data source per minute unless stated)

| Call | Limit |
|---|---|
| Query | 600 |
| Mutation | 120 |
| Connection test | 20 |
| Schema discover | 10 (plus the 30 s refresh cooldown) |
| Cache refresh | 30 |
| Management changes | 60 per 60 s per tenant (`RATE_LIMITED`, `Retry-After`); reads not throttled |

A Redis outage makes `RedisRateLimitGate` deny (the budget protects third-party systems). (src: B/data/datasource/DataSourceService.kt, DataSourceAdminService.kt, DataGateway.kt)

---

## 11. Management API

Frozen by D-C0-28; integrated (D-C0-30). Anything not listed here does not exist. (src: docs/contracts/v2/management-api.md; B/wiring/DataManagementControllers.kt)

### 11.1 Rules for every route

- Base: workspace routes `/api/v1/workspaces/{workspaceId}/...`; project routes `/api/v1/workspaces/{workspaceId}/projects/{projectId}/...`.
- The client never sends `tenantId`. Tenant comes from `AccessService.forWorkspace` / `forProject`. Any body key outside the route's allow-list (`tenantId`, `workspaceId`, `projectId`, `credentialRef`, `id`, `createdBy`, `updatedBy`, `userId`, `version`, anything unknown) is 400 `INVALID_PARAMS` (strict parsers).
- Session cookie plus `X-XSRF-TOKEN` on every state-changing call. 401 `AUTHENTICATION_REQUIRED` and 403 `CSRF_INVALID` are written by the platform.
- The caller is always `ActorKind.USER`.
- Default deny and disclosure: a non-member, an unknown workspace/project/resource, a resource of another workspace or tenant all answer the same 404. 403 only for a workspace member who lacks the permission.
- **Optimistic concurrency:** `version` is an integer on data sources, query definitions and mutation definitions. `PATCH` accepts optional `expectedVersion` (positive integer); a different stored version answers 409 `CONFLICT` and nothing changes. `expectedVersion` never counts as a change.
- **No raw mutation route.** `POST /api/v1/data/mutate` is not mounted and must not be added. A browser changes external data only through an action (`app-runtime/actions/{id}/execute`).
- Body limit 64 KiB default (413 `PAYLOAD_TOO_LARGE` before parsing). 405 `METHOD_NOT_ALLOWED`, 415 `UNSUPPORTED_MEDIA_TYPE`, 404 `NOT_FOUND` for unknown paths use the same envelope.

### 11.2 Permissions (canonical codes; no code is invented)

Today only `WORKSPACE_ADMIN` holds `DATA_SOURCE_MANAGE`, `DATA_MUTATE`, `WORKFLOW_EXECUTE` and `WORKFLOW_MANAGE`; project roles hold none of them (`PermissionMatrix`). Workspace EDITOR/PUBLISHER/VIEWER hold no data-source permission at workspace level. A different rule is a C1 policy request, not a C3/C0 change. (src: B/access/Permission.kt lines 98-120; docs/contracts/v2/management-api.md section 2)

| Route group | Required |
|---|---|
| Connector catalogue; list/get data source; credential metadata | `DATA_SOURCE_VIEW` |
| Create/update/delete data source; put/delete credential; test connection | `DATA_SOURCE_MANAGE` |
| Schema discover (no samples) | `DATA_SOURCE_MANAGE` |
| Schema discover with samples | `DATA_SOURCE_MANAGE` and `QUERY_EXECUTE` |
| Read stored schema snapshot | `DATA_SOURCE_MANAGE` |
| List query/mutation definitions (summary: id, kind, status, version, parameter names; no SQL, no target) | `DATA_SOURCE_VIEW` |
| Read one definition in full; create/update/delete a definition | `DATA_SOURCE_MANAGE` |
| List bindings | `PROJECT_EDIT` (canonical `APP_EDIT`) on the project and `DATA_SOURCE_VIEW` |
| Set/delete a binding | `PROJECT_EDIT` and `DATA_SOURCE_MANAGE` |

### 11.3 Routes

Data sources and credential (base `/api/v1/workspaces/{ws}`):

| Method and path | Success | Notes |
|---|---|---|
| `GET /data-sources/connectors` | 200 `{items:[ConnectorType]}` | available and planned types with `configKeys`, `credentialKeys`, `status`, `capabilities` |
| `GET /data-sources` | 200 `{items:[DataSource]}` | this workspace only; items the caller may not read are filtered |
| `POST /data-sources` | 201 DataSource | body `name`, `type`, `config?`, `credential?` |
| `GET /data-sources/{id}` | 200 | non-UUID id = 404 |
| `PATCH /data-sources/{id}` | 200 DataSource (as stored afterwards) | any of `name`, `config` (replaces the whole config), `status` (`ACTIVE`/`DISABLED`), `expectedVersion?`; at least one field; **one transaction, one version bump, all fields validated before any is applied**; nothing to change = no new version, no audit |
| `DELETE /data-sources/{id}` | 204 | see 11.4 |
| `GET /data-sources/{id}/credential` | 200 `{configured,type,keys,updatedAt,updatedBy}` | key names only |
| `PUT /data-sources/{id}/credential` | 200 CredentialMetadata | body `{"credential":{...}}`, write-only |
| `DELETE /data-sources/{id}/credential` | 204 | |
| `POST /data-sources/{id}/test` | 200 TestResult | empty body; `{ok:true, latencyMs, warnings[]}` or `{ok:false, code, message}`; `code` in `AUTH_REJECTED, CONNECT_FAILED, HOST_UNRESOLVED, ADDRESS_BLOCKED, TLS_FAILED, TIMEOUT, ROLE_TOO_PRIVILEGED, INVALID_CREDENTIAL, NOT_IMPLEMENTED, INTERNAL`; 409 `DISABLED`; a failed test is still HTTP 200 and never carries the raw driver message |
| `POST /data-sources/{id}/schema/discover` | 200 SchemaSnapshotSummary | section 7 |
| `GET /data-sources/{id}/schema` | 200 SchemaSnapshot | 404 until discovered |

Definitions (scoped by **data source**, not by project; project-scoped definition routes are not approved):

| Method and path | Success |
|---|---|
| `GET /data-sources/{id}/queries` / `/mutations` | 200 `{items:[Summary]}` |
| `POST /data-sources/{id}/queries` | 201 (body `queryId`, `kind` `SQL`/`REST`, `definition`, `status?`) |
| `POST /data-sources/{id}/mutations` | 201 (body `mutationId`, `kind` `CREATE`/`UPDATE`/`DELETE`/`SUBMIT`, `definition`, `status?`) |
| `GET /data-sources/{id}/queries/{queryId}` / `/mutations/{mutationId}` | 200 full definition |
| `PATCH ...` | 200 (`definition?` whole document, `status?`, `expectedVersion?`) |
| `DELETE ...` | 204 (mutation: see 11.4) |

A definition is validated by the same Kotlin types the gateway uses, then by the connector (`validateQueryDefinition` / `validateMutationDefinition`): an invalid definition is 400 `INVALID_QUERY` or `INVALID_CONFIG`; a mutation on a source with `writable` not `true` is 422 `READ_ONLY_VIOLATION`. A definition's `status` `DISABLED` hides it from the gateway.

Bindings (base `/api/v1/workspaces/{ws}/projects/{p}/data-bindings`):

| Method and path | Success | Notes |
|---|---|---|
| `GET` | 200 `{items:[{mode,slotId,dataSourceId,updatedAt}]}` | |
| `PUT /{mode}/{slotId}` | 200 Binding | body `{"dataSourceId":"..."}` only; `mode` exactly `TEST` or `LIVE` (anything else 400 `INVALID_PARAMS`); `slotId` `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`; the data source must be in the same tenant **and workspace** as the project, else 404; the source row is locked while the binding is written |
| `DELETE /{mode}/{slotId}` | 204 | 404 when no such binding |

### 11.4 DELETE 409 rules

- `DELETE /data-sources/{id}`: 409 `CONFLICT` while **any binding (TEST or LIVE)** uses it, and while **any mutation idempotency row is `RESERVED` or `UNKNOWN`** (the evidence of a possibly applied write is never destroyed). Otherwise one transaction removes queries, mutations, schema snapshots, finished idempotency rows, the source, its audit row and (last) its credential; if the vault cannot discard the credential nothing is deleted.
- `DELETE /data-sources/{id}/mutations/{mutationId}`: 409 `CONFLICT` while one of its idempotency rows is `RESERVED` or `UNKNOWN`.
- Query definition delete has no 409 rule in code.

(src: B/wiring/persistence/JdbcDataSourceRepository.kt `delete`; B/data/query/DefinitionAdminService.kt)

### 11.5 Error envelope

Every non-2xx body of Management routes (and of the R1 and public routes) is:

```json
{"code":"...","message":"fixed text","requestId":"...","retryable":false,"details":{}}
```

`retryable` is true only for `RATE_LIMITED`, `REFRESH_TOO_SOON` and `TIMEOUT` on Management routes; `details` is an object, empty unless a code defines fields; `RATE_LIMITED` also sends `Retry-After` (30 s for gateway-produced rate limits). Errors of the C1 access proof (`ApiException`: 404 `WORKSPACE_NOT_FOUND`/`PROJECT_NOT_FOUND`, 403 `FORBIDDEN`) keep their status and code; treat any 404 as "not there". (src: B/wiring/ManagementTransport.kt `ManagementErrors`, `ManagementExceptionAdvice`)

### 11.6 Audit

Append-only, written in the same unit of work as the change (a change that cannot be audited is not applied). Rows hold tenant, workspace, actor, request id, data source id, ids/versions/status names/counts; never a body, a credential, a config value or a definition body (a definition is identified by id + version + SHA-256 of its canonical document). (src: B/data/datasource/DataSourceService.kt `DataAuditActions`)

| Event | Action |
|---|---|
| Data source create / update / status change / delete | `DATASOURCE_CREATED`, `DATASOURCE_UPDATED`, `DATASOURCE_STATUS_CHANGED`, `DATASOURCE_DELETED` |
| Credential set/rotate, remove | `DATASOURCE_CREDENTIAL_ROTATED`, `DATASOURCE_CREDENTIAL_REMOVED` |
| Connection test | `DATASOURCE_TESTED` (outcome code only) |
| Schema discover | `DATASOURCE_SCHEMA_REFRESHED` (snapshot version, samples flag, fingerprint), `DATASOURCE_DISCOVERED` (service level) |
| Query / mutation definition create/update/delete | `DATA_QUERY_DEFINITION_CHANGED`, `DATA_MUTATION_DEFINITION_CHANGED` (`change`, id, version, status, hash) |
| Binding set/delete | `DATASOURCE_BINDING_CHANGED` (`mode`, `slot`, `change`, `project`) |
| Runtime | `DATASOURCE_QUERIED`, `DATASOURCE_MUTATED` (service), `DATA_QUERY_SERVED`, `DATA_MUTATION_RUN` (gateway), `DATA_CACHE_REFRESHED` |
| Denied | `DATA_ACCESS_DENIED` |
| Public route | `DATA_PUBLIC_QUERY_SERVED` (`actorKind=PUBLIC_SITE`, site, release, query, request id) |

Sync, webhook and AI-catalogue audit constants exist (`DATA_SYNC_*`, `DATA_WEBHOOK_*`, `DATA_AI_CATALOG_BUILT`) but those features have no mounted route (section 15).

---

## 12. Runtime API (authenticated, under `/app-runtime`)

Base `B_RT = /api/v1/workspaces/{workspaceId}/projects/{projectId}/app-runtime`. Only these segments exist below it: `queries`, `actions`, `workflows`, `workflow-runs`. Authentication is the normal session plus CSRF; `SecurityConfiguration` has no `permitAll` for these routes. `tenantId` is never read from the request; any unknown body field (`tenantId`, `userId`, `dataSourceId`, `sql`, `url`, ...) is 400 `INVALID_REQUEST`. (src: docs/contracts/v2/runtime-api.md; B/wiring/RuntimeRequests.kt)

`mode`: `LIVE` (default; the version of the active release) or `TEST` (the working draft; needs `PROJECT_EDIT`; no side effect).

### 12.1 R1 - run a data query (flag `app.data-platform.enabled`)

`POST {B_RT}/queries/{queryId}/run`

Request (strict; all optional): `{"mode":"LIVE|TEST", "params":{...plain values, at most 40}, "page":{"limit":1..10000,"offset":0..1000000}, "mappingRef":"<local mapping id>"}`. `queryId` is the LOCAL id of a `queries[]` entry with `mode: READ`. If `mappingRef` is absent it is the single mapping whose `queryRef == queryId`; zero or several = 422 `MAPPING_REF_REQUIRED`.

Response 200: `{"queryId","mode","cache":"HIT|MISS|BYPASS","result":{viewModelId, cardinality, fields, rows, truncated, warnings, skippedRows}}`.

Pipeline: `forProject` -> mode gate -> `APP_USE` (LIVE) or `PROJECT_EDIT` (TEST) -> `QUERY_EXECUTE` -> load AppDefinition (tenant-checked) -> `AppDataBindingResolver.query` -> `DataGateway.runQuery` (which authorizes `QUERY_EXECUTE` again through the C1 `GatewayAuthorizer`).

Failures: 400 `INVALID_REQUEST`; 403 `FORBIDDEN`; 404 `PROJECT_NOT_FOUND` / `QUERY_NOT_FOUND` (also for an unknown reference); 422 `MAPPING_REF_REQUIRED`, `WRONG_MODE` (not a READ query), `DATA_SOURCE_UNBOUND`, `INVALID_DEFINITION`, other resolution codes; gateway failures through the table in 10.4 (including 429, 502, 504); 503 `DATA_RUNTIME_UNAVAILABLE` when no `DataGateway` bean exists; 500 `INTERNAL`. The R1 route always sends `retryable:false` on these bodies (code-verified).

### 12.2 Actions and workflows

Execute action, start/read/cancel workflow runs, and the approval decision route are specified in `docs/ACTION_WORKFLOW.md` (sections 13 and 12). The data-relevant facts: there is no other write route; a mutation is an action of type `SUBMIT_FORM | CREATE_RECORD | UPDATE_RECORD | DELETE_RECORD | CALL_API`; a TEST action answers `WouldRun` and never reaches the gateway.

---

## 13. Public Runtime (anonymous published sites)

Status: IMPLEMENTED, integration-tested, flag OFF by default. Design frozen by D-C0-33..37; read-only LIVE queries of the active release only. (src: B/wiring/PublicDataController.kt; docs/contracts/v2/published-runtime.md sections 4, 5, 7a)

```
Public browser -> same-origin sites gateway {sites origin}/{slug}/_data/queries/{queryId}/run
  -> API POST /sites/{slug}/_data/queries/{queryId}/run   (anonymous: no cookie, no CSRF)
  -> slug -> site -> ACTIVE release -> project -> workspace -> tenant   (all server-derived)
  -> C1 PublicSiteAuthorizer -> release allow-list -> LIVE binding -> DataGateway.runQuery as PUBLIC_SITE -> mapped JSON
```

### 13.1 Route and request

`POST /sites/{slug}/_data/queries/{queryId}/run`, body optional `{"params":{...}}` and **nothing else**. Any other key (`tenantId`, `workspaceId`, `projectId`, `releaseId`, `role`, `mode`, `mappingRef`, `page`, `sql`, ...) is 400 `INVALID_REQUEST`; params must be plain values. The mapping is chosen by the server (`MappingPicker.pick`) and the page is the server's cap; the client cannot name either.

### 13.2 Resolution and refusal

`sites.live(slug)` -> tenant of the workspace -> `PublicSiteAuthorizer`, which re-verifies against the database that the pointer's deployment is `RUNNING` **and** `PUBLIC` (a PRIVATE site is not modelled in V1), the project is active, the tenant is ACTIVE, and the query is in the release allow-list. Then the release's own version is loaded, the LIVE binding resolved and `DataGateway.runQuery` called with `GatewayContext(actorKind = PUBLIC_SITE, actorUserId = null)`.

**One answer for every refusal: `404 QUERY_NOT_FOUND "Query not found"`** (unknown slug, offline, archived, deleted, private, not RUNNING, not approved, not public, other release, TEST). Never 401 or 403. The reason goes to the log at DEBUG only. A non-READ query in the resolved document also answers 404.

### 13.3 Allow-list (what is public)

A query is public iff `queries[].public === true` and `mode == READ` in the **immutable version snapshot of the release asked about**, **and** `publish_configs.public_data_approved` is true now (the author's explicit acknowledgement; revoking it closes the route at once). Draft edits never matter; a rollback restores the previous release's list because the pointer moves; no row, no approval, no snapshot or any error = empty set. The default provider is deny-all. (src: B/wiring/PublicDataController.kt `ReleaseSnapshotPublicQueryAllowList`; B/access/adapters/PublicSiteAuthorization.kt)

Disagreement with the contract: `published-runtime.md` section 5 says a query "whose source has `writable` data" is never eligible; the code does not check `writable`. A public query is READ, and READ always runs in a read-only session, so no write path is opened; the document statement is not enforced as written.

### 13.4 Flags, caps, headers

| Item | Value |
|---|---|
| Flags | `app.data-platform.enabled` AND `app.sites.public-data.enabled` (both default false) |
| Body | at most 16384 bytes (`413 PAYLOAD_TOO_LARGE` from `PublicDataBodyLimitFilter`; nginx also `client_max_body_size 16k`) |
| Rows | `min(query.maxRows, app.sites.public-data.max-rows (100))`, at least 1; the connector additionally applies the definition and data source caps |
| Response size | over 262144 bytes = 502 `RESPONSE_TOO_LARGE` (checked after the query ran) |
| Rate limits (Redis fixed windows, Redis outage = refuse) | per client address 30 per 10 s; per site and address 120 per minute; per site 1200 per minute; 429 `RATE_LIMITED` + `Retry-After`; nginx adds `limit_req zone=data_rl burst=20` |
| Headers | `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`; no CORS headers (same origin); no cookie read or set |
| Security chain | the existing STATELESS `/sites/**` chain; POST permitted for exactly `/sites/*/_forms/*` and `/sites/*/_data/queries/*/run`; every other POST under `/sites` denied |
| Other answers | 400 `INVALID_REQUEST`; 422 `DATA_SOURCE_UNBOUND`; 502 `DATA_UNAVAILABLE` (retryable true; never connector text, host, schema, SQL or credential metadata); 504 `DATA_TIMEOUT`; 503 `DATA_RUNTIME_UNAVAILABLE`; 500 `INTERNAL` |
| Audit | `DATA_PUBLIC_QUERY_SERVED` |

### 13.5 Cache behaviour (verified)

The public route **does** go through the gateway cache: `DefaultDataGateway.runQuery` has no actor-based bypass (no reference to `PUBLIC_SITE` in `data/**`), the cache key contains no caller, and the public response carries `"cache": r.cache.name`. So a query with `cacheTtlSeconds > 0` is served from the Redis result cache and the body can say `HIT`. "No cache" in the contract applies to the HTTP layer: `Cache-Control: no-store` on every public response and no proxy caching in nginx (`proxy_no_cache 1; proxy_cache_bypass 1`). The pointer itself is read on every request, so rollback and unpublish take effect on the next call. (src: B/data/gateway/DataGateway.kt, B/wiring/PublicDataController.kt, infra/sites-gateway/default.conf.template)

### 13.6 Gateway (nginx) and `apiBase`

`infra/sites-gateway/default.conf.template` routes only `^/[a-z0-9][a-z0-9-]{1,79}/_data/queries/[a-z0-9][a-z0-9-]{0,63}/run$`, POST only, strips `Cookie`, `Authorization` and `X-XSRF-TOKEN`, drops the query string, and sets `X-Forwarded-For` to the validated `$remote_addr`. **Consequence:** the gateway accepts query ids made of lowercase letters, digits and `-` only, while the API accepts `[A-Za-z0-9._:-]{0,127}`; a public query whose id has uppercase letters, `.`, `_` or `:` cannot be reached through the gateway.

`__factory/config.json` (served by `SiteService.runtimeConfig` at request time) carries `apiBase` = `app.sites.data-api-base` with `{slug}` replaced; blank means null. Page sites: `workers/render/page-runtime.ts` now emits one static script (`_runtime/page-runtime.js`) for a published page that has data bindings. It reads `<site root>/__factory/config.json`, requires an absolute same-origin `apiBase` (else NOT_READY), and for every distinct bound query sends `POST {apiBase}/queries/{queryId}/run` with body `{"params":{}}`, writing results with `textContent` only. At publish time it refuses a binding whose query is not a public READ query or whose id does not match `^[a-z0-9][a-z0-9-]{0,63}$` (at most 8 distinct queries). The contract's 2026-10-07 gap map ("a page site ships no client data runtime") is therefore superseded for pages with bindings. A page sends no parameters; only a direct caller (a code app) can pass `params`. (src: workers/render/page-runtime.ts lines 1-66; docs/contracts/v2/published-runtime.md section 7)

Client address behind the gateway (verified): the controller rate-limits on `request.remoteAddr` (`PublicDataController.kt` line 181). `ClientIpFilter` (`B/common/ClientIpFilter.kt`, highest precedence) overrides `remoteAddr` only when `app.proxy.trust=true` **and** the TCP peer is inside `app.proxy.trusted-cidrs` (`trust=true` without CIDRs stops the start); it then uses the right-most `X-Forwarded-For` entry that is not itself a trusted proxy and is a syntactically valid IP, so entries a client prepends are never used. With `trust=false` (the default) the headers are ignored and `remoteAddr` is the direct peer. The nginx data route sets `X-Forwarded-For` to its own validated `$remote_addr`, so behind the gateway the API sees the visitor's address only if the gateway's address is in `trusted-cidrs`; otherwise every visitor shares the gateway's address and therefore the same per-address budgets. Production requires `TRUST_PROXY` explicitly (`application-prod.yml`); `scripts/_env.sh` sets it true with RFC1918 CIDRs for the local V1 stack.

---

## 14. Limits summary

| Limit | Value | Where |
|---|---|---|
| Params per call / definition | 40 | gateway, definitions |
| Bound params JSON | 4096 bytes | gateway |
| String param | 1000 chars | `QueryParams` |
| Page limit / offset | 10000 / 1,000,000 | `PageSpec` |
| Query rows | definition 1..10000 (default 1000), data source `maxRows` 1..10000 | |
| Response bytes | `maxResponseBytes` 1000..5,000,000 (default 2,000,000) | |
| Timeout | 500..30000 ms (default 10000) | |
| Mutation output stored | 16 KiB (idempotency row cap 1 MiB) | |
| `maxAffectedRows` | 1..100000 (default 1000) | |
| SQL text | 20,000 chars | `SqlGuard` |
| Mutation target | 200 chars; at most 10 keys, 20 returning columns | |
| Credential | 8 entries, 4000 chars/value | |
| Config | 30 keys, 2000 chars/value | |
| Management body | 64 KiB default | |
| Idempotency retention | 30 days default, 7 minimum; lease 300 s | |
| Cache TTL | 0..86400 s | |
| Allowed private targets | 20 exact `host:port` | |

---

## 15. Known gaps and limits

### 15.1 Limits (plain list)

1. **Cannot write NULL.** Parameter binding has no JSON null (`QueryParams.bind` treats null as missing), so a column cannot be set to NULL by UPDATE or inserted explicitly as NULL (omit the parameter to get the column default). DEFERRED.
2. **`CONNECT_FAILED` is ambiguous by contract.** It is not in `NOT_EXECUTED`, so even "connection refused" (provably nothing sent) keeps the idempotency key as `UNKNOWN`. Improving it needs a C0/C4 decision. Likewise a timeout is `UNKNOWN` although the server cancels and rolls back (57014): the connector cannot distinguish a cancelled statement from one whose answer was lost. DEFERRED.
3. **A transient database conflict is not retryable** (`MUTATION_REJECTED`); no retryable transient code exists.
4. **UPDATE/DELETE of a missing row succeeds with `affected: 0`**; the action cannot tell "no such row" from "changed".
5. **`INVALID_PARAMS` from a write inside an action is answered with HTTP 500** by `RuntimeResponses` (nothing was executed; 422 would be right). Pre-existing, affects every `INVALID_PARAMS` and other codes not listed in `RuntimeResponses.status`. (src: docs/parallel/audit/C4-FQ-ACT-02-record-key.md section 7; B/wiring/RuntimeResponses.kt)
6. **Mutation key column naming:** a key column must match `[a-z][A-Za-z0-9_]{0,39}`.
7. **No management API for sync, webhooks, AI catalogue, sharing per data source, export/import, tenant-level (workspace-less) data sources.** The library code exists (`data/sync/**`, `data/sync/webhook/**`, `data/discovery/AiDataCatalog.kt`, realtime `data/cache/RealtimeService.kt`) but no controller or bean in `wiring/**` exposes it; `app.data-platform.webhooks-enabled` is read by nothing. The webhook ingest path `/api/v1/webhooks/data/{endpointId}` is declared in `SecurityConfiguration`. DEFERRED.
8. **Only `WORKSPACE_ADMIN` can manage data sources or bind slots** (current C1 policy). The UI should hide data panels for other roles.
9. **Name uniqueness is per tenant** (section 3.2).
10. **`PATCH` with `config` replaces the whole config**; send every key to keep.
11. **Cache invalidation across nodes** is per process when a counter bump fails (D-C3-06); the realtime event bus is per instance.
12. **Public route:** see 15.2.
13. **Out-of-schema TLS needs:** a customer CA must be added to the backend JVM trust store by an operator; there is no per-tenant CA.
14. **TLS broker/other transports:** not applicable to data sources; connectors other than `postgres` and `rest` answer `NOT_IMPLEMENTED`.
15. **A query or mutation definition can be deleted while an AppDefinition still names it as an `operationKey`** (`DefinitionAdminService` has no reference check; only a mutation with a `RESERVED`/`UNKNOWN` idempotency row is protected). A later run then answers `QUERY_NOT_FOUND` / `MUTATION_NOT_FOUND`. UNVERIFIED beyond reading the code path (no 409 rule found).

### 15.2 Differences between the code and the contracts (code wins)

| # | Contract says | Code does | Consequence |
|---|---|---|---|
| 1 | `published-runtime.md` section 4 "Cache: `Cache-Control: no-store` for V1 (rows are data)" | The public route calls `DefaultDataGateway.runQuery`, which has no actor-based cache bypass (no `PUBLIC_SITE` reference anywhere in `data/**`); the cache key has no caller component; the response carries `"cache": HIT\|MISS\|BYPASS`. | A public query with `cacheTtlSeconds > 0` is served from the Redis result cache. `no-store` and nginx `proxy_no_cache` apply to HTTP caching only. Set `cacheTtlSeconds: 0` on a query if server-side caching of public data is unwanted. |
| 2 | `published-runtime.md` section 5: "a query that mutates, **or whose source has `writable` data**, is never eligible" | `PublicQueries.of` = `queries[].public` AND `mode == READ`; `ReleaseSnapshotPublicQueryAllowList` adds only `publish_configs.public_data_approved`. Nothing inspects the data source's `writable` flag. | A READ query on a writable source can be public. It still runs in a read-only session, so no write path opens; but the sentence is not enforced and should be removed or implemented. |
| 3 | `published-runtime.md` section 7a / nginx text: query ids of the API are `[A-Za-z0-9._:-]{0,127}` | The gateway location only routes `^/[a-z0-9][a-z0-9-]{1,79}/_data/queries/[a-z0-9][a-z0-9-]{0,63}/run$`. | A public query whose id has uppercase letters, `.`, `_` or `:` (or is longer than 64) cannot be reached through the gateway. Page sites are not affected (the page publisher refuses such ids at build time); direct callers are. |
| 4 | `published-runtime.md` section 7 gap map: "nginx `limit_req` still to add (C2)" and "PAGE_SCHEMA site has no data runtime" | `infra/sites-gateway/default.conf.template` has `limit_req zone=data_rl burst=20` on the data route, and `workers/render/page-runtime.ts` exists. | The gap-map rows are stale. |
| 5 | `runtime-api.md` section 3 lists `WOULD_RUN` without `plan` | `RuntimeResponses` emits `plan` and `reason`. | Additive; clients may read them. |
| 6 | `management-api.md` section 4: "C3 as built omits `retryable`/`details`" | `ManagementErrors` adds both (`retryable` true only for `RATE_LIMITED`, `REFRESH_TOO_SOON`, `TIMEOUT`). | Stale remark; the envelope is as in 11.5. |
| 7 | `WRITABLE_POSTGRES.md` section 5 handoff: "`RuntimeResponses.status` maps unlisted codes to 500" | Still true (15.1 item 5). | Open. |

---

## 16. Error code reference (data layer)

`retryable` = the value on Management-envelope bodies; actions and the public route define their own (sections 10.4 and 13.4).

| Code | HTTP | Meaning | Retryable |
|---|---|---|---|
| `INVALID_PARAMS` | 400 | malformed body, unknown/authority key, bad id, bad `mode`/`slotId`, bad or missing parameter | no |
| `INVALID_CONFIG` | 400 | bad name/config, secret in config, bad mutation target | no |
| `INVALID_CREDENTIAL` | 400 | credential keys do not fit the connector, or stored credential unreadable | no |
| `INVALID_QUERY` | 400 | query/mutation definition does not validate | no |
| `AUTHENTICATION_REQUIRED` | 401 | not signed in (platform) | no |
| `PERMISSION_DENIED` / `FORBIDDEN` | 403 | member lacks the permission | no |
| `CSRF_INVALID` | 403 | platform | no |
| `NOT_FOUND`, `WORKSPACE_NOT_FOUND`, `PROJECT_NOT_FOUND`, `QUERY_NOT_FOUND`, `MUTATION_NOT_FOUND`, `TENANT_MISMATCH` | 404 | missing or not yours, identical on purpose | no |
| `CONFLICT` | 409 | duplicate name/id, delete while bound or in flight, `expectedVersion` mismatch, concurrent refresh | no |
| `DISABLED` | 409 | use or test of a `DISABLED` source | no |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | same key still running | yes (short wait) |
| `IDEMPOTENCY_CONFLICT` | 409 | same key, different parameters | no |
| `IDEMPOTENCY_OUTCOME_UNKNOWN` | 409 | earlier attempt ended ambiguously; may or may not have been applied; never run again | **never** |
| `PAYLOAD_TOO_LARGE` | 413 | body over the route limit | no |
| `UNSUPPORTED_TYPE` | 422 | unknown connector type | no |
| `READ_ONLY_VIOLATION` | 422 | mutation on a read-only source/session | no |
| `MUTATION_UNSUPPORTED` | 422 | SUBMIT or a read-only connector | no |
| `MUTATION_REJECTED` | 422 | source refused, nothing applied | **never** |
| `INVALID_MAPPING`, `MAPPING_FAILED` | 422 | mapping/view model problem | no |
| `DATA_SOURCE_UNBOUND` | 422 | slot has no binding for the mode (run-time code) | no |
| `RATE_LIMITED`, `REFRESH_TOO_SOON` | 429 | wait `Retry-After` | yes |
| `NOT_IMPLEMENTED` | 501 | planned connector | no |
| `ADDRESS_BLOCKED`, `HOST_UNRESOLVED`, `TLS_FAILED`, `AUTH_REJECTED`, `REDIRECT_BLOCKED` | 502 | refused before anything was sent; fix the source | no |
| `CONNECT_FAILED`, `UPSTREAM_STATUS`, `RESPONSE_INVALID`, `RESPONSE_TOO_LARGE`, `RESPONSE_NOT_JSON`, `QUERY_FAILED` | 502 | source or transport failure (for a write: ambiguous) | no (envelope) |
| `TIMEOUT` | 504 | source did not answer in time (for a write: ambiguous) | yes on reads (envelope) |
| `SECRETS_UNAVAILABLE`, `ROLE_TOO_PRIVILEGED`, `INTERNAL` | 500 | missing master key / role refused / unexpected error (fixed text) | no |

---

## 17. Test evidence (counted, not run)

Counts below are `@Test` / `@ParameterizedTest` annotations per class on `integration/v2` (HEAD `28376de`). They are a size indicator, not a pass result; nothing was executed while writing this document. Run `cd backend && ./gradlew test --tests '<class>'` (JDK 21, Docker for Testcontainers) to verify.

| Area | Class (`@Test` count) |
|---|---|
| Management domain and HTTP | `DataSourceManagementTests` (18), `ManagementHttpTests` (10), `DataManagementApiTests` (18), `DataManagementNegativeSecurityTests` (8; includes the route-table check that fails on any route outside the contract) |
| Writable PostgreSQL | `PostgresMutationTests` (28), `PostgresWritableIntegrationTests` (25), `DataWritableE2ETests` (4) |
| FQ-ACT-02 | `RecordKeyGatewayTests` (14), `RecordKeyActionE2ETests` (9) |
| Live runtime, public route | `DataRuntimeLiveApiTests` (21), `PublicDataEndpointTests` (15) |
| Authorizer | `GatewayAuthorizerNoOracleTests` (2) |

## Sources read

Code (`B/` = `backend/src/main/kotlin/com/systemwebstudio/`): `data/datasource/{DataSource,Credential,ConnectorSpi,ConnectorFailure,DataConnector,AddressPolicy,DataSourceService,DataSourceAdminService}.kt`, `data/datasource/postgres/{PostgresConnector,PostgresConnectorConfig,PostgresTargetPolicy,PostgresMutationExecutor,SqlGuard}.kt`, `data/datasource/rest/RestConnectorConfig.kt`, `data/query/{Query,Mutation,RecordKey,DefinitionDocuments,DefinitionAdminService}.kt`, `data/gateway/{DataGateway,GatewaySecurity,IdempotencyStore,GatewayHttp,ManagementHttp}.kt`, `data/discovery/DiscoveryService.kt`, `data/cache/QueryCache.kt`, `wiring/{DataRuntimeConfiguration,DataManagementControllers,ManagementTransport,AppRuntimeDataController,PublicDataController,RuntimeRequests,RuntimeResponses,DataWriteErrors,PostgresTargetPolicies,C1PortAdapters,RuntimeContexts,RuntimeAppDefinitions,ActionDataPortAdapter,AuditAdapters}.kt`, `wiring/persistence/{JdbcIdempotencyStore,JdbcDataSourceRepository,JdbcDataSourceSlotBindings,JdbcDefinitionCatalogs,IdempotencyPurgeRunner}.kt`, `access/adapters/{GatewayAuthorizer,PublicSiteAuthorization}.kt`, `access/Permission.kt`, `app/definition/{AppDataBindingResolver,AppDefinitionModel}.kt`, `common/ProductionConfigValidator.kt`; `backend/src/main/resources/{application,application-local,application-prod}.yml`; `db/migration/{V28__data_runtime,V29__workflow_run_persistence}.sql`; `infra/sites-gateway/default.conf.template`; `scripts/_env.sh`.

Documents: `docs/contracts/v2/{data-runtime,management-api,runtime-api,published-runtime}.md`; `docs/parallel/c3/{MANAGEMENT_API,WRITABLE_POSTGRES}.md`; `docs/parallel/c0/MANAGEMENT_API_REVIEW.md`; `docs/parallel/audit/C4-FQ-ACT-02-record-key.md`; `docs/parallel/MIGRATION_LEDGER.md`; `docs/parallel/BLOCKERS.md` (B-C0-W-06); `docs/parallel/DECISIONS.md` (D-C0-31, D-C0-59 passages via search); `docs/contracts/v2/app-definition.md`; C1 final hardening report `docs/parallel/c1/final-iam-hardening-report.md`; `backend/src/main/kotlin/com/systemwebstudio/common/ClientIpFilter.kt`; `workers/render/page-runtime.ts`.

## Open questions / UNVERIFIED

1. **Test results.** Only annotation counts are given (section 17); no test was run. Verify with `./gradlew test` (JDK 21, Docker). The full-regression totals quoted in older handoffs were dropped because they predate the current HEAD.
2. **Definition delete while referenced** (section 15.1 item 15): read from code (no 409 rule); verify with a test that deletes a definition still named by an AppDefinition.
3. **Deployed proxy settings.** The client-address behaviour behind the gateway is code-verified; the actual `TRUST_PROXY` / `TRUSTED_PROXY_CIDRS` of the deployed API (`{{PUBLIC_API_SHA}}`) are not visible from the repository and must be read from the deployment environment.
4. **Page sites at runtime.** `page-runtime.ts` was read; that a published page really fetches data end to end is evidenced by the C6/C5 flows (E2E-PD01 / PD02 in `docs/parallel/c6/**`), which were not read here.
5. **`PUT` slot binding by a non-admin editor** is refused today (needs `DATA_SOURCE_MANAGE`); whether the product wants EDITOR to bind TEST slots is a C1 policy request (documented, not decided).
6. **Real-stack behaviour of the TLS data target** is quoted from D-C0-59 (recorded evidence), not re-run.
