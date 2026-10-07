# Published runtime v2 — release activation (LIM-1), `apiBase`, the Public Runtime (FROZEN by D-C0-33 … D-C0-35)

Status: **frozen 2026-10-07 (C0)** as the V1 LOCAL target and the V2 deployment shape. Owner of the decision: C0. Implementers are named per section.
**This file is a target for the parts marked NOT_IMPLEMENTED (section 7); nothing marked so may be advertised as working.** It never overrides
`tenant-permission.md`, `data-runtime.md`, `runtime-api.md`, `management-api.md`, `action-workflow.md` or `C2_DEPLOY_CONTRACT.md`; it adds what they leave open.
Note on sources: the instruction that produced this file cites an approved C1 security contract for the public runtime. No such text exists in this repository
(`PUBLIC_SITE` is not in any doc, branch or source). The security requirements below are therefore frozen from that instruction; C1 confirms or amends them in a
DECISIONS entry before any code (section 8, H-C1).

## 1. Principles (V1 local = production shape; V2 changes configuration only)

1. A visitor's browser never talks to a database, a connector or the DataGateway. It talks to ONE same-origin address, the sites gateway.
2. The browser is never an authority for tenant, workspace, project, deployment / release, role, permission or actor kind. All of them are derived on the server from the request's site.
3. What is served and what is queried is the **active release**: `sites.current_deployment_id`, moved only through the fenced release scope. Never "the latest RUNNING deployment".
4. Everything that differs between a Mac and a VPS is an environment value read at run time (section 6); nothing is baked into an artifact.

## 2. Release activation — LIM-1 (C0 F-8 / F-9) — the candidate-pointer contract

### 2.1 What is integrated today (Batch 2, V30) and its exposure
Order: verify the artifact (record, manifest checksum, file sizes, and the bytes of artifacts ≤ `app.deploy.verify-bytes-limit`) → stage → server runtime healthy → **move the pointer
(site is served, deployment `DEPLOYING`)** → confirm (`verify`, ≤ `app.deploy.verify-timeout-seconds`, 60 s) → `RUNNING`, or compensate (`ROLLING_BACK`, previous release or offline).
Exposure (accepted for V1): between the pointer move and the confirmation a release that has already passed the pre-switch verification may be served for up to the verify time.
`SiteService.live` serves `DEPLOYING` and `RUNNING`; the Data Runtime resolves LIVE from the same pointer and the same two statuses (D-C0-33), so a page and its data are always one release.

### 2.2 Target (Batch 3, needs V31 — NOT allocated; C2 requests it in BOARD after V30 has its Mac gate)
`prepare → verify → fenced / CAS activation`. An upload is not a release being served.

| # | Invariant |
|---|---|
| I1 | The served set is exactly `{ sites.current_deployment_id }` and only while its status is `RUNNING`. `DEPLOYING` is never served. |
| I2 | A **candidate** is `sites.candidate_deployment_id` on the SAME scope row as the active pointer, not a second source of truth: it is written and cleared only by the lease holder, in a CAS that checks lease, fencing token, `pointer_version` and the order of intents, exactly like the active pointer. It is never read by the gateway, the Data Runtime or any visitor-facing code. |
| I3 | Sequence under one lease: (1) CAS `candidate := D` (fails for a stale or fenced-out publisher), (2) stage the artifact and probe the candidate through an internal path that does not exist for visitors (storage check + HTTP probe of the candidate), (3) **one** CAS statement: `current := D, candidate := NULL, pointer_version + 1, active_seq := seq, active_operation_id := op` and, in the same transaction, `D: DEPLOYING → RUNNING`. |
| I4 | A failure before step 3 clears the candidate (fenced) and ends the deployment `FAILED`; nothing was served, so there is no rollback to perform and no `ROLLING_BACK`. A failure of step 3 changes nothing (all or nothing). |
| I5 | A stale publisher (older `activation_seq`, expired / taken-over lease, wrong fencing token, moved `pointer_version`) cannot write the candidate and cannot activate: it ends `FAILED / STALE_PUBLISH`. |
| I6 | Rollback and unpublish use the same guard, clear any candidate in their own CAS, and take effect for visitors with the next request (no cache of the pointer beyond one request). |
| I7 | Recovery: a candidate whose lease expired is cleared (or resumed, if the same operation takes the scope again) by the next acquirer; a worker death never leaves a candidate that a later activation could pick up unfenced. |
| I8 | `ROLLING_BACK`, `ROLLED_BACK`, `FAILED` keep the V30 meaning; `ROLLBACK_FAILED` / `ROLLBACK_OFFLINE` stay events. Server apps: the runtime plane moves with the same CAS (C2 RuntimePlane), the site pointer last. |
| I9 | Only `JdbcScopeGuard` writes `current_deployment_id` (and, with V31, `candidate_deployment_id`). A source-scan test pins it (`ReleaseCasTests`, D-C0-33). Archive and delete of an application take the site offline through the same scope (`ReleaseService.takeOffline`, bounded wait, `409 SCOPE_BUSY`). |

Owner: C2 (implementation, V31 file, tests); C0 (V31 number, import, Mac gate). Not authorised yet: Batch 3 (D-C0-26 stays).

## 3. `apiBase` — runtime configuration of a published app

| Item | Frozen value |
|---|---|
| Key | `app.sites.data-api-base`, environment `SITES_DATA_API_BASE` (C2 implemented the key; C0 approves it unchanged) |
| Meaning | the **browser-facing** absolute http(s) base URL where the published app calls its Data Runtime. Same-origin form for V1 and V2: `{sites origin}/{slug}/_data`. Never an internal host, never `host.docker.internal`, never a docker-bridge or private address. |
| Delivery | `GET {site}/__factory/config.json` → `"apiBase"`, produced by `SiteService.runtimeConfig` at request time; **not** a build-time value; the same artifact runs on a Mac, on staging and in production, and a rollback needs no rebuild. Blank / unusable = `null` (nothing invented). C5's runtime config loader reads it once at start. |
| `{slug}` token | the value may contain `{slug}`, replaced with the site's slug when the config is produced (C2 to implement; until then a value with `{…}` is "not configured"). The same value is then valid for every site of an environment. |
| `releaseId` | the config names the release being served (C2: `releaseId` = the active deployment id), so a client can detect a rollback. |

Placement of every C2 key (D-C0-34): `application.yml` lists the code default of each and an env name; `application-local.yml` holds the local values (the origins, render, build api; `data-api-base` stays blank until the route of section 4 exists); `application-prod.yml` requires
`SITES_ORIGIN`, `STUDIO_ORIGIN`, `RENDER_URL` (no default) and takes `SITES_DATA_API_BASE` optionally; `ProductionConfigValidator` refuses http, loopback, private, docker and `*.internal` hosts for it, and non-https public origins.

| Key | Env | Base (yml) | Local | Prod |
|---|---|---|---|---|
| `app.sites.origin` | `SITES_ORIGIN` | blank | `http://127.0.0.1:18088` | **required**, https, public |
| `app.sites.studio-origin` | `STUDIO_ORIGIN` | blank | `http://127.0.0.1:3003` | **required**, https, public |
| `app.sites.data-api-base` | `SITES_DATA_API_BASE` | blank | blank (until section 4) | optional; https, public |
| `app.render.url` | `RENDER_URL` | blank | `http://127.0.0.1:18095` | **required** (service-to-service; http allowed) |
| `app.build.api-base` | `BUILD_API_BASE` | blank | `http://127.0.0.1:8080` | optional (runner reach) |
| `app.deploy.scope-lease-seconds` / `-max-seconds` / `scope-wait-seconds` / `scope-retry-ms` / `scope-duplicate-wait-seconds` / `scope-lifecycle-wait-seconds` | `DEPLOY_SCOPE_*` | 90 / 900 / 300 / 2000 / 60 / 10 | same | same |
| `app.workflow.queue` | `WORKFLOW_QUEUE` | unset (memory outside prod) | unset; `scripts/_env.sh`: `amqp` | `amqp` only |

## 4. The Public Runtime — V1 architecture (read-only LIVE queries)

```
Public browser
  → SAME-ORIGIN sites gateway   {sites origin}/{slug}/_data/…       (nginx: only POST on this path, body ≤ 16 KiB, rate limit; rewrite to the API)
  → API  POST /sites/{slug}/_data/queries/{queryId}/run              (anonymous, no cookie, no CSRF: there is no session to ride)
  → derive: slug → site → ACTIVE release (current_deployment_id, RUNNING) → project / app → workspace → tenant
  → principal PUBLIC_SITE { tenant, workspace, project, release }   (NOT a USER; nothing from the request)
  → release query allow-list (immutable, snapshotted at publish; default empty = deny)
  → LIVE binding of the application's slot (no TEST, no draft, no fallback)
  → C3 DataGateway.runQuery  → connector → database
  → mapped view-model JSON (same response shape as runtime-api.md R1) → UI
```

| Rule | Frozen |
|---|---|
| `PUBLIC_SITE` ≠ `USER` | a new `ActorKind` value (C1 contract change; `tenant-context.md`, `permission-model.md`) carrying only server-derived ids. It has no user id, no role, no membership, and it is never `SYSTEM` / `SERVICE` / `APP_TOKEN`. Audit actor kind `PUBLIC_SITE` + site id. |
| V1 scope | **YES:** read-only LIVE query of a query the release lists as public. **NO:** public mutation, public action, public workflow, TEST, draft, raw DataGateway, raw SQL, schema discovery, any Management route. |
| Authority | the request names only `queryId` (local id) and `params` of that query. Any other field, header or path value that looks like tenant / workspace / project / deployment / release / role / permission / actor kind is refused `400 INVALID_REQUEST` (strict parsing, as `runtime-api.md`). |
| Unpublished / out of scope | unknown slug, offline site, archived or deleted project, a release without that public query, a private site without its session: the same `404 QUERY_NOT_FOUND`-style answer (no existence oracle). A PRIVATE site's data route needs the site's host-only session (existing `_app/<token>` capability), never the Studio cookie. |
| Same origin | preferred and the V1 design. Credentialed wildcard CORS is **prohibited**. A code app served in a sandboxed (opaque-origin) document may call it only uncredentialed (`Access-Control-Allow-Origin: *`, no `Allow-Credentials`, no cookie, no `Authorization`), exactly like the existing `/{slug}/api/**` gateway; V1 PAGE_SCHEMA sites are same-origin and need no CORS. |
| Long-lived browser token | none. The gateway-derived principal replaces it. A token (signed, short-lived, release-bound) is a V2 option only for non-same-origin embedding. |
| Rate limit | nginx `limit_req` per IP (zone like `sites_rl`), plus a Redis `RateLimiter` per site and per IP in the API (`RATE_LIMITED`, `Retry-After`), plus C3's per-tenant gate; response size and row caps are C3's (`maxRows`, `maxResponseBytes`). |
| Cache | `Cache-Control: no-store` for V1 (rows are data). A per-query `public, max-age` is a later, explicit, release-listed option. The pointer is read on every request (no cache), so rollback / unpublish bite at once. |
| Rollback behaviour | resolved from the active pointer per request: after a rollback the data route answers from the restored release's allow-list and AppDefinition; after unpublish every call is 404. LIVE bindings are project configuration (not release-pinned): changing a binding changes the data a release reads, by design; a missing binding is `422 DATA_SOURCE_UNBOUND`. |
| Health / readiness | sites gateway `GET /healthz` (200 `ok`); API `GET /actuator/health/liveness` and `/readiness` (db, redis, rabbit, minio). The route answers `503` when the data platform is disabled or not ready; it is mounted only with `app.data-platform.enabled=true` and a separate switch `app.sites.public-data.enabled` (default false). |
| Same code path | the principal differs, the query path does not: allow-list check → binding → C3 `DataGateway`. No second query engine. |

## 5. Release-bound allow-list (what is public)

The set of public queries of a release is **immutable once the release exists** and **derived by the server at publish time** from the app's own definition (never from the request, never from the browser). Default empty. Where it is declared (an AppDefinition field on the query versus the V27 publish configuration) is C2's proposal to C0 (H-C2); either way: snapshotted into the release (deployment) metadata, checked by the Public Runtime against the release in force at request time, and visible in the publish confirmation so an author knows what becomes public. A query that mutates, or whose source has `writable` data, is never eligible.

## 6. Local V1 topology (config only; every value is an environment value)

| Concern | Local value | Key |
|---|---|---|
| Sites gateway (nginx container) | `127.0.0.1:18088` → upstream = the API on the host (`host.docker.internal:8080`, **container-to-host, local config only**) | `SITES_ORIGIN`, `infra/sites-gateway/default.conf.template` |
| API (browser-facing for portals through their same-origin `/api` proxy) | `127.0.0.1:8080` | `SERVER_ADDRESS`, `SERVER_PORT`, `API_PROXY_TARGET` |
| `apiBase` | blank now; `http://127.0.0.1:18088/{slug}/_data` when section 4 is implemented | `SITES_DATA_API_BASE` |
| V2 | `SITES_ORIGIN=https://sites.example.com`, `SITES_DATA_API_BASE=https://sites.example.com/{slug}/_data`, nginx upstream = internal DNS of the API | env / deployment only |

## 7. Gap map (verified in the code of `integration/v2`)

| Capability | State | Evidence / what is missing |
|---|---|---|
| `__factory/config.json` runtime config | RUNNING **for code apps (kind `STATIC_APP`) only** | `SiteControllers` serves it only when `app` is true; `SiteRuntimeConfigTests`, `PublishApiContractTests`. `apiBase` is `null` until configured. **A PAGE_SCHEMA site is static HTML rendered at publish (workers/render): it has no runtime config and no data runtime in the page** (verified live by `scripts/v1-smoke.mjs`: `/__factory/config.json` = 404 on a page site). The published-app flow Browser -> runtime config -> apiBase -> backend therefore exists only for a code app (needs the medium / full stack: Forgejo + build runner), until the renderer ships a client runtime for page sites (B-C5-06, decision D-C5-03). |
| Authenticated `app-runtime` LIVE / TEST query | RUNNING (integration-tested) | `AppRuntimeDataController`, `AppRuntimeApiTests`, `DataRuntimeLiveApiTests` (real PostgreSQL / Redis); LIVE now = active release (D-C0-33). |
| Authenticated actions / workflows | RUNNING (integration-tested) | `AppRuntimeActionController`, V29 stores, restart / recovery suites. |
| Release-pinned LIVE definition | RUNNING | D-C0-33 (`RuntimeAppDefinitions`). |
| Management API | RUNNING (`management-api.md`, D-C0-30) | |
| Public query route | NOT_IMPLEMENTED | no controller under `/sites/{slug}/_data`. Owner: C3 (query semantics) + C0 (controller wiring). |
| Sites gateway proxy for the data route | NOT_IMPLEMENTED | `infra/sites-gateway/default.conf.template` proxies GET / HEAD of sites, the forms POST and `/{slug}/api/**` of server apps only. Owner: C2 (+ C0 for the template). |
| `PUBLIC_SITE` principal | NOT_IMPLEMENTED | `ActorKind` = `USER, SYSTEM, APP_TOKEN, SERVICE`; `ActorPolicy` denies all but USER. Owner: C1 (decision + policy), C0 (adapter wiring). |
| Release query allow-list | NOT_IMPLEMENTED | no field, table or snapshot. Owner: C2 (+ V31 request). |
| Deterministic release binding | PARTIAL | the pointer decides the definition (done); the LIVE data-source binding is per project, not per release (by design, section 4); the public route must still read the release's allow-list. |
| Rate limiting of a public data route | NOT_IMPLEMENTED | nginx `sites_rl` covers sites GET; Management has a tenant throttle; C3's gate is per tenant. Owner: C0 (Redis limiter) with C2 (nginx). |
| `apiBase` with `{slug}` | NOT_IMPLEMENTED | `SiteService.resolveDataApiBase` accepts a plain URL only. Owner: C2. |
| Candidate pointer (LIM-1 target) | NOT_IMPLEMENTED | section 2.2; needs V31. Owner: C2. |

## 8. Handoffs (exact) — see `docs/parallel/c0/HANDOFFS_2026-10-07.md`
