# HBL-XWeb roadmap and V1 LOCAL MACOS target (D-C0-29)

Owner: C0. Date: 2026-10-06. Baseline of the audit below: `integration/v2 @ 8e91172` (local, `a9e07db` pushed). This file is the single statement of the release target; `DECISIONS.md` D-C0-29 is its decision record.

## 1. Roadmap

| Release | Target | Done when |
|---|---|---|
| **V1 — LOCAL MACOS** | The whole system runs end to end, for real, on one macOS machine; complete demo; no mock for the main flows; no public cloud or domain needed. | Section 5 is entirely GREEN **and C6 has run its independent final QA on the Mac and says GREEN.** Only C6 closes V1; C0 never reports "V1 LOCAL READY" on its own. |
| **V2 — VPS / CLOUD** | The *same* system on a VPS or cloud: domain, TLS, remote infrastructure, backup, monitoring, CI/CD. | Mostly deployment and configuration; no rewrite of business / domain core. |
| **V3 — SCALE / HA** | Multi-instance, HA database / cache / queue, autoscaling, multi-region only if there is a real need. | Not started; V1 must not block it. |

## 2. Architecture rule: PRODUCTION-SHAPED LOCAL

V1 is not a disposable demo. Every decision of C0–C6 is checked with one question: **"when this moves from V1 Mac to V2 VPS / cloud, must business or domain core be rewritten?"** If yes, it is redesigned before it is frozen. If only environment, configuration, deployment or a provider implementation changes, it is accepted.

1. **No hardcoded environment** in production or domain code: hosts, ports, base URLs, `apiBase`, public site host, database / Redis / RabbitMQ / MinIO host and port, bucket names, CORS origins, callback URLs, connector endpoints, secrets, credentials. Local values live only in environment variables, `application-local.yml`, compose / local deployment config and runtime config. Domain code never knows LOCAL, CLOUD or VPS.
2. **Abstractions stay** for: persistence, cache, queue, object storage, publish / deploy provider, secrets / config provider, external connectors, runtime host configuration, notification. V1: Postgres / Redis / RabbitMQ / MinIO in Docker; V2: managed or remote ones, business code unchanged.
3. **Stateless backend.** Anything the product needs after a restart or on a second instance lives in PostgreSQL, Redis, RabbitMQ, object storage or a durable runtime store; the local filesystem is for temp files, disposable cache and developer tooling only.
4. **Security is the production model from day one.** Authenticated principal, server-derived tenant, server-verified workspace and project, default deny, disclosure-safe 404, CSRF, canonical C1 permissions, credential redaction, audit, no client authority fields, no secret in logs / responses / audit. "V1 is local so security can be simpler" is not accepted.
5. **Three kinds of URL are kept apart:** (A) browser-facing URL, (B) service-to-service URL (backend → postgres / redis / rabbit / minio), (C) container-to-host URL (`host.docker.internal`, only where really needed and only as LOCAL CONFIG; V2 replaces it with an internal DNS name).
6. **`apiBase` is runtime config.** `SiteService.runtimeConfig` → `apiBase` (today `null`); C2 populates it at serve time, C5 only reads it at run time; the published frontend artifact is not rebuilt to change environment.
7. **Flyway is the schema truth.** Same chain local and remote; no edit of an integrated migration (V1..V29 immutable); no manual drift. Next number: V30 = C2.
8. **Observability hooks from V1:** `GET /actuator/health/liveness`, `GET /actuator/health/readiness`, structured logs (prod profile), `requestId`, the error envelope, audit, recovery diagnostics. Full centralized logging / metrics / alerting is V2.

## 3. Local topology (as it exists today — nothing here is invented)

Source of truth: `compose.yml` (profiles `lean` / `medium` / `full` / `sso`), `scripts/_env.sh`, `scripts/run-local.sh`, `application.yml` env keys. Values are config defaults, not code.

| Concern | Local (V1) | Config key / file | V2 replacement |
|---|---|---|---|
| Studio database | PostgreSQL 17, `127.0.0.1:15432`, db `system_web_studio` (container `hbl-postgres-1`) | `DATABASE_URL`, `DATABASE_USER/PASSWORD` | remote PostgreSQL URL |
| Apps database (server apps, `full` profile) | PostgreSQL, `127.0.0.1:15434` | `APPDB_URL`, `APPDB_HOST_FOR_APPS` | remote / separate DB |
| Redis | `127.0.0.1:16379` | `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | managed Redis |
| RabbitMQ | AMQP `127.0.0.1:15674`, management `15675` | `RABBITMQ_HOST`, `RABBITMQ_PORT` | managed RabbitMQ |
| Object storage | MinIO `127.0.0.1:19000` (console `19001`) | `MINIO_ENDPOINT`, `MINIO_PUBLIC_ENDPOINT`, `MINIO_*` | S3 / Blob / GCS / remote MinIO |
| Backend API | `127.0.0.1:8080` | `SERVER_ADDRESS`, `server.port` | behind reverse proxy / LB |
| Sites gateway (published sites) | nginx container `127.0.0.1:18088` → upstream `host.docker.internal:8080` | `SITES_ORIGIN`, `infra/sites-gateway/default.conf.template` | public host + TLS, upstream = internal DNS |
| Apps gateway (server apps) | `127.0.0.1:18090` | `APPS_GATEWAY_URL`, `APPS_GATEWAY_TOKEN` | same, remote |
| Render worker | local process `127.0.0.1:18095` | `RENDER_URL`, `RENDER_PORT` | service / container |
| Studio UI | **legacy root app `:3100` is what `run-local.sh` starts; the three portals `apps/platform|admin|studio` (`127.0.0.1:3001|3002|3003`) are NOT started by it** | `FRONTEND_PORT`, `WEB_ORIGIN_*`, `API_PROXY_TARGET`, `NEXT_PUBLIC_*` | one origin per portal, TLS |
| CORS / cookies | `CORS_ALLOWED_ORIGINS`, `127.0.0.1` used consistently | `application.yml`; prod requires https | public origins |
| Deploy provider | `DEPLOY_PROVIDER=static` (`_env.sh`); `mock` is rejected by the prod validator | `app.deploy.provider` | same provider, remote storage |
| Feature switches for V1 E2E | `app.data-platform.enabled=true`, `app.workflow.enabled=true`, `app.publish-configs.enabled=true` (all default false) | env | same flags, on |
| Not touched | `factory-postgres`, `factory-redis`, `chatwoot-*`, `xweb-v26-pg16` | – | – |

Other running containers of the local stack: Forgejo (`13000`), Verdaccio (`14873`) — `medium` / `full` profiles; the `hblpub-*` stack is the "public" environment (`compose.public.yml`).

## 4. Audit (evidence from the repository at `8e91172`)

**HARDCODE AUDIT — ISSUES (config hygiene, not domain coupling; none breaks V1).**
- H1. `application.yml` (base, all profiles) carries loopback defaults for `DATABASE_URL`, `REDIS_HOST`, `RABBITMQ_HOST`, `SERVER_ADDRESS`, `CORS_ALLOWED_ORIGINS`, `WEB_ORIGIN_*`, `SITES_ORIGIN`, `STUDIO_ORIGIN`, `BUILD_API_BASE`, `RENDER_URL`, `MINIO_ENDPOINT`, the OTLP endpoint and `app.deploy.mock.base-url`. `application-prod.yml` is fail-closed for database, Redis, RabbitMQ, bind address, CORS and storage **but not for `SITES_ORIGIN`, `STUDIO_ORIGIN`, `BUILD_API_BASE`, `RENDER_URL`, `WEB_ORIGIN_*`**: a V2 deploy that forgets them silently gets a loopback value. → C0 task **L-2** (move host defaults to `application-local.yml` / `_env.sh`, make those keys required in `application-prod.yml`, extend `ProductionConfigValidator`; with tests).
- H2. Seven production classes repeat a loopback literal as `@Value` default: `SiteService` (C2), `StaticSites` ×2 (C2), `CodeChangeController`, `BuildJobs`, `ServerRuntime`, `SecurityConfiguration`, `AuthController`. → same task L-2 (remove the literal; the value comes from config only). C2 owns the `publish/**` ones and is told in the C2 handoff.
- H3. Frontend: `packages/auth/src/server/nextConfig.ts` falls back to `http://127.0.0.1:8080` for `API_PROXY_TARGET`; `NEXT_PUBLIC_API_MODE` and `NEXT_PUBLIC_PORTAL_URL_*` are **build-time** (`run-local.sh`, C5 plan §3). That is a V2 risk (rebuild per environment). → C5 handoff: portal URLs and API mode from runtime config, no loopback fallback in the shipped build.
- OK: no `host.docker.internal` and no host / port literal in any domain class; `host.docker.internal` exists only in `infra/*.template` and `scripts/*` (container-to-host, local config). SSRF / address policy classes mention `localhost` only to **deny** it. `ProductionConfigValidator` already rejects `localhost` / `127.0.0.1` in CORS, `DEPLOY_PROVIDER=mock`, http MinIO public endpoint, weak secrets.

**STATELESS AUDIT — PASS WITH LIMITS (single instance is correct; multi-instance is V3).**
- Durable: action / workflow run state (`JdbcActionRunStore`, `JdbcWorkflowRunStore`, V29), data catalog / credentials / bindings / data idempotency (V28), sessions (Spring Session in Redis), query cache (`RedisCacheBackend`), rate-limit gate (`RedisRateLimitGate`), artifacts and assets (MinIO), deployments (PostgreSQL).
- Still per node (RAM): `WorkflowQueue` (`InMemoryWorkflowQueue`, bean `workflowQueue()`; a lost job is re-published by the durable sweeper, so a restart loses no work — verified by `WorkflowRestartRecoveryTests`); `DataEventBus` (`InMemoryDataEventBus`, no HTTP route uses it yet); per-node `TenantRateLimiter` (B-C4-08); cache-bypass flags (B-C3-09); `InMemory` approval / schedule / notification stores (not wired to any V1 route; those features are not persisted by design, D5).
- Local filesystem: only `maintenance/Backups.kt` (reads backup status files, diagnostics) and developer tooling; the render worker is a local process. No product state on disk.
- Verdict: a restart loses nothing durable; a second backend instance is functionally safe for the persisted paths but not for the in-memory ones above → V3 items, not V1.

**STORAGE ABSTRACTION — PARTIAL.** `StorageProvider` (interface, `MinioStorageProvider`) for assets; `ArtifactStore` is a concrete MinIO-client class (S3-compatible API, endpoint / bucket / credentials from config, so S3 / MinIO-remote work by configuration; Azure Blob / GCS native would need a new implementation). Accepted for V1; V2 note.

**QUEUE ABSTRACTION — INTERFACE PRESENT, RABBITMQ ADAPTER NOT IMPORTED.** `WorkflowQueue` interface; C4's RabbitMQ adapter and `app.workflow.queue` switch (C4 H-5) are not on `integration/v2`; production wiring is `InMemoryWorkflowQueue`. Business core does not change when the adapter lands. → V2 (V3 for multi-instance); not a V1 blocker.

**PUBLISH ABSTRACTION — PRESENT.** `DeployProvider` (`mock`, `static`), `StorageProvider`, `ArtifactStore`; provider chosen by `app.deploy.provider`. Concurrency / rollback guard = C2 V30.

**CONFIG / SECRETS — PARTIAL.** Environment-driven; secrets read through `SecretsCrypto` / config keys, never returned or logged (C3 credential vault, D-C0-28); prod profile requires the secrets and validates strength. No external secret-manager port yet (V2 provider boundary to add; the keys are already behind `app.*` properties).

**SECURITY:** unchanged, one model for V1 and V2 (C1 contracts untouched).

## 5. V1 exit criteria (all must be GREEN; C6 confirms)

1. **Local infra:** PostgreSQL, Redis, RabbitMQ, MinIO, backend, Studio frontend, published-site gateway running on the Mac (Docker for infra; unrelated stacks untouched).
2. **Backend local:** health, readiness, database, Redis, RabbitMQ, MinIO PASS; host / port from config.
3. **C1 security proven:** real login / session, CSRF, tenant / workspace / project isolation, cross-tenant / cross-workspace / non-member denied, permission denied correctly, non-USER actor per frozen policy, default deny, no browser authority override.
4. **C3 Management API integrated and callable** exactly as `docs/contracts/v2/management-api.md` (D-C0-28): routes, atomic PATCH, DELETE rules, credential redaction, TEST / LIVE without fallback, **no `POST /api/v1/data/mutate`**.
5. **C4:** ActionRuntime, WorkflowEngine, Jdbc run stores, restart recovery, lease semantics; mutating TIMEOUT / INTERRUPTED → `IDEMPOTENCY_OUTCOME_UNKNOWN`, `retryable=false`, no auto retry, no compensation of an ambiguous step.
6. **V29** DONE, integrated (`5f28adc`), immutable.
7. **C2 V30** (lease, fencing, CAS pointer, `pointer_version` / `active_seq`, stale publish fails explicitly, rollback and unpublish under the same guard, idempotent retry, recoverable lease; `ROLLING_BACK`; `ROLLBACK_FAILED` / `ROLLBACK_OFFLINE` events only; automatic rollback failure → `FAILED`).
8. **Local publish:** a site / app is really published, served by the local gateway, `runtimeConfig.apiBase` correct, the browser loads it and it calls the real backend; release switching and rollback work.
9. **C5 Studio on the real API** (login → workspace / project → app → data source → credential → test → schema → query → mutation / action → TEST bind → preview → LIVE bind → publish → open published site → real data renders → action / workflow runs). No mock called E2E.
10. **Published app runtime flow:** Browser → published site → runtime config → `apiBase` → backend → `app-runtime` → Query / Action / Workflow → C3 `DataGateway` → connector → PostgreSQL → UI.
11. **Restart recovery:** create action / workflow, persist state, restart backend, verify recovery, durable state, no duplicate ambiguous mutation, lease semantics.
12. **C6 final QA, independent, on the Mac:** backend compile + full regression, frontend typecheck / unit / build, security suite, browser suite, Management API, Action / Workflow, local publish, full-stack E2E, recovery, secret scan; critical = 0 fail.

**Not V1 blockers:** AWS / Azure / GCP, Kubernetes, Terraform, public domain, multi-region, HA database, autoscaling, CDN, managed Redis / RabbitMQ / object storage, cloud WAF, production load balancer, full alerting.

## 6. Blocker classification (from `BLOCKERS.md` / `BOARD.md`; status as last written there, to be re-verified when each is touched)

Classes: **V1_BLOCKER** (must be solved before V1), **V2_ONLY** (cloud / scale / hardening; excluded from V1), **DEFERRED** (a feature outside the 17-step V1 flow; neither blocks V1 nor is cloud-only), **CLOSE** (solved by an import, row only needs closing).

| Class | Items |
|---|---|
| **V1_BLOCKER** | ~~B-C0-W-06~~ DONE (D-C0-31, `6abeff0`) · **L-6** supported local data target for E2E (TLS PostgreSQL on `127.0.0.1:15440`, dev CA in the JVM trust store) · ~~B-C0-W-03 Management API, B-C0-W-04 writable connector~~ DONE (D-C0-30, `integration/v2 @ b557a0d`) · **C2 V30 + Batch 2** (guard, rollback) · **local publish with `apiBase`** (C2 populates `runtimeConfig.apiBase`; key name open, to agree with C0) and **B-C5-06** publish with data / render path (decision D-C5-03) · **B-C5-02** UI needs the Management API (unblocked by the above) · **B-C5-03** data-bound components (DataTable / DataList / KPI) in the registry — may need a registry-seed migration: **request goes to `BOARD.md`, C0 numbers it (V31), nobody picks it** · **C5 portal start path** (L-1 below) · **C5 Q-1 decision** (Admin portal for TENANT_ADMIN, `PHASE3_AUDIT.md`) — to be re-read and decided by C0 when C5 starts E2E · **B-C1-13** (ownership-transfer self-grant) — verify it is closed by the `AdminController` patch of B-C0-WEB-02 · **B-C1-18** (`appVersionId` must be a `project_versions` UUID) — confirm in the publish E2E |
| **V2_ONLY** | B-C0-WEB-01 and the OIDC part of B-C5-08 (SSO redirect per origin; V1 uses local login) · B-C3-09 shared event bus / cache bypass (multi-node) · B-C4-08 shared tenant rate limiter (multi-node) · B-C4-06 RabbitMQ `WorkflowQueue` adapter (single node is correct with the in-memory queue + durable sweeper) · B-C1-16 `tenant_compat_removal` migration · H1–H3 config hardening beyond L-2 (secret-manager port, runtime portal URLs) · backups, monitoring, alerting, CI/CD, TLS, domain |
| **DEFERRED** | B-C0-W-02 (AI data catalog adapter, public data webhook) · B-C3-07 / B-C3-08 (webhook ingest, sync trigger) · B-C3-10 (sync scheduler) · approvals inbox, schedules CRUD, notifications persistence (V29 D5) |
| **CLOSE** | B-C3-02, B-C3-05, B-C4-05, B-C4-07, B-C4-09, B-C0-C4-01/02, B-C0-W-01 (V28 / V29 imported; the volatile queue remains under V2_ONLY), B-C0-W-05 (workspace scope, D-C0-22), B-C4-04 / B-C3-03 (write adapter exists), B-C5-04 (TEST mode answers `WouldRun`), B-C1-14 (`system-admin-business-access` key present), B-C0-WEB-02 (covered by the full regression — confirm) · housekeeping B-001 / B-002 / B-003 stay |

## 7. C0 tasks that are mine (not feature work)

- **L-1 Local topology script.** `scripts/run-local.sh` starts the legacy root app; V1 needs the three portals (`127.0.0.1:3001|3002|3003`, same-origin `/api` proxy via `API_PROXY_TARGET`, flags of §3 on, `DEPLOY_PROVIDER=static`). Shared files, C0-owned. Starts after C3 is wired (so the E2E has something to call).
- **L-2 Config hygiene** (H1 / H2 above), small and tested.
- **L-3 Management API integration** (plan in `MANAGEMENT_API_REVIEW.md` §4), triggered by C3's SHA.
- **L-4 `apiBase` key** with C2: name and behaviour recorded in DECISIONS before C2 implements.
- **L-6 Local data target** (V1): a script / compose profile that starts a TLS PostgreSQL on `127.0.0.1:15440` with a dev CA (IP-SAN certificate), a SELECT-only role and demo tables, and starts the backend with the CA in its trust store. Config only; needs nothing in code (D-C0-31 item 7).
- **L-5 Import C2 Batch 2 / V30** after its own Mac gate; update `MIGRATION_LEDGER.md`.

## 8. Critical path (order, no side work)

1. C3 fixes the must-fix list and reports GREEN with SHA → 2. C0 imports the C3 branch → 3. C0 ownership review of `wiring/**` → 4. C0 mounts / wires the Management API → 5. C1 negative security, C3 integration tests, backend full regression → 6. GREEN: Management API READY to C5 → 7. C2 syncs `integration/v2` → 8. C2 Batch 2 / V30 → 9. C2 finishes local publish + `runtimeConfig.apiBase` → 10. C0 + C2 freeze local topology / config (L-1, L-4) → 11. C5 switches to the real Management API → 12. C5 runs the full local E2E → 13. C6 independent final QA → 14. all GREEN → **V1 LOCAL READY (declared by C6's result)**.

Steps 7–9 (C2) run in parallel with 1–6 once C2 has synced; they do not wait for C3.
