# XWEB architecture

Canonical description of what the system is, how it is divided, which rules must never be broken, and where each decision lives. It replaces the earlier 29-line stub. It describes the system **as it is** on branch `integration/v2` at `9d2fc8b9758077ac880d80cfbec767e2e776f9c5` (release candidate `75643ad8700f42df05c3151c1d0875b063c81620`). The finalization pass of 2026-10-11 was verified against the local ref `integration/v2` @ `28376de`, which already contains V33 approvals, the approval decision route, the C1 final hardening, the C5 UI fixes, the C4 interrupted-worker fix and the C6 QA documents (section 14).

- Citation convention: `(src: path:line)`. `backend/...` paths are written relative to `backend/src/main/kotlin/com/systemwebstudio/` when they start with a module name (for example `publish/SiteService.kt`); `db/Vn` means `backend/src/main/resources/db/migration/Vn__*.sql`.
- Where code and a document disagree the code wins and the point is marked **DISAGREEMENT**. Unverified claims are marked `UNVERIFIED` with what would verify them (collected in the last section).
- Status vocabulary where a status is given: DONE / PARTIAL / BLOCKED / DEFERRED.
- Companion documents (canonical set): [`docs/PUBLISH_RUNTIME.md`](PUBLISH_RUNTIME.md) (publish and published sites), `docs/contracts/v2/*` (frozen contracts, section 11), `docs/adr/*` (decisions, section 12). Feature-level status per capability is in [`docs/IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md) (dated 2026-10-04 in its header, partly older than this document).

---

## 1. What XWEB is

XWEB (repository `luan20495/system-web-studio`, product names "AI Software Factory" and "System Web Studio") is an **internal platform on which employees of a company build and run company software under governance**. One login serves several audiences:

- **Builders** create *websites* (multi-page, forms, custom domains) from an approved component registry with AI or a visual Design mode; *web apps and dashboards* (React source in a platform-owned Git repository, sandboxed builds, approved packages); and *server apps, internal tools and workflows* (React + Node in an isolated container with its own database, write-only secrets and approved connectors).
- **Tenant administrators** run one company: organization structure, employees, accounts, workspaces, data sources.
- **Platform operators** (SYSTEM_ADMIN) run the whole installation: tenants, users, AI providers and budgets, components, builds, backups, health, policies.
- **Visitors** open the published results (public or private sites) and, for data-bound public pages, read data through a constrained public route.

(src: `README.md`; [`docs/FRONTEND_ONBOARDING.md`](FRONTEND_ONBOARDING.md) section 1; [`docs/contracts/v2/tenant-permission.md`](contracts/v2/tenant-permission.md) section 4)

The product is **not** microservices, not a generic low-code database, and not an AI that writes data models: AI output is always a typed, validated, versioned proposal (section 8).

Application model. A project (`projects` table; the name is kept for backward compatibility, no table was renamed) has a build model `app_type` and a product kind `app_kind`:

| `app_type` | Representation | Built by | Published as |
|---|---|---|---|
| `PAGE_SCHEMA` | JSON document (Page Schema, extended by AppDefinition v2: data sources slots, view models, queries, mappings, data bindings, actions, workflows, permissions, publish draft) | edited by typed operations; rendered by a fixed renderer | static site artifact |
| `STATIC_APP` | source repository in the platform Git server | sandbox build job on a runner | static or server artifact |

`app_kind` (`projects.app_kind`, V22): `WEBSITE_STATIC`, `SOURCE_WEB_APP`, `DASHBOARD`, `INTERNAL_TOOL`, `WORKFLOW`, `SERVER_APP`; `INTERNAL_TOOL`, `WORKFLOW` and `SERVER_APP` have a server runtime. (src: [`docs/adr/0008-application-types-and-factory-roadmap.md`](adr/0008-application-types-and-factory-roadmap.md); [`docs/adr/0017-server-runtime.md`](adr/0017-server-runtime.md); `code/CodeProjects.kt:71`)

---

## 2. Architectural style

**Modular monolith.** One Kotlin/Spring Boot deployable (`backend/`, root package `com.systemwebstudio`, Spring Boot 4.1.1, Kotlin 2.2.21, JDK 21) plus a Next.js frontend monorepo at the repository root. There is no microservice and splitting a module out requires a new ADR with evidence (load, team boundary, failure isolation). External systems sit behind small ports so they can be swapped without touching business code. (src: `backend/build.gradle.kts:1-30`; `CLAUDE.md`; [`docs/adr/0022-frontend-monorepo-three-deployments.md`](adr/0022-frontend-monorepo-three-deployments.md) decision 4)

Processes **outside the JVM** exist only where untrusted or non-Kotlin work is needed; they are not services of the business model:

| Process | Why it is separate | Source |
|---|---|---|
| Render worker (`workers/render`, Node) | the *same* TypeScript renderer produces Studio preview and published HTML (no second renderer in Kotlin); AST service for Design mode of code apps; JS-free screenshot preview | ADR 0009; `publish/StaticSites.kt:19-66` (called over HTTP with `X-Render-Token`) |
| Build / runtime runner (`workers/runner`, Node + Docker) | generated code must never run inside the API JVM or on the control-plane host | ADR 0010, 0017, 0019 |
| Forgejo (Git), Verdaccio (package mirror) | platform-owned repositories and an approved-package mirror for code apps | ADR 0011, 0013 |
| App containers + apps DB + apps gateway | server apps run isolated, one database and one login role per app | ADR 0017, 0018 |
| Sites gateway (nginx) | anonymous reverse proxy in front of published sites | [`docs/PUBLISH_RUNTIME.md`](PUBLISH_RUNTIME.md) section 11 |

### Layering inside the backend

Allowed imports (a hard rule, [`docs/contracts/v2/integration-contract.md`](contracts/v2/integration-contract.md) section 1):

```
common, audit, runtime (SecretsCrypto, PublicAddress)   base layer, read-only for feature owners
tenancy, access                      (identity / permission)    imports base only
data.*                               (data platform)            imports base + tenancy types; NEVER logic.*, app.*
logic.*                              (action / workflow)        imports base only (ports); NEVER access.*, tenancy.*, data.*, app.*
app.definition                       (AppDefinition v2)         imports base + data.{query,mapping} shape/parser classes + logic.action validator; NEVER data connectors / gateway
wiring.*                             (integration)              the ONLY package that imports across all of the above: adapters, Spring beans, runtime controllers
```

`wiring.*` holds the glue that turns ports into implementations (`C1PortAdapters`, `ActionDataPortAdapter`, `RuntimeAppDefinitions`, `AppRuntimeDataController`, `AppRuntimeActionController`, `PublicDataController`, the JDBC stores under `wiring/persistence`). One definition per concept: `ActorKind` and `TenantContext` live in `tenancy`; parameter / field types and mapping shapes in `data.*`; action / event / trigger types in `logic.*`; `DataBindingDef`, `PublishConfigDef` and the operations in `app.definition`. (src: `integration-contract.md` sections 1-2; `ls` of `wiring/`)

### Backend filter chains

Four Spring Security chains: `/sites/**` (stateless, no CORS, no CSRF, GET/HEAD plus two named anonymous POSTs, everything else denied), `/internal/**` (runner / gateway tokens, stateless), `/scim/v2/**` (stateless, SCIM token), and the main API chain (session in Redis, CSRF cookie `XSRF-TOKEN` with header `X-XSRF-TOKEN`, CORS from an exact origin list, `ActiveUserFilter` re-checks that the account is enabled on every request). Everything not listed as public is `authenticated()`. (src: `identity/SecurityConfiguration.kt:93-197`)

---

## 3. Component diagram

```
 People                      Frontend monorepo (Next.js 16, React 19)                 Backend (one JVM, Spring Boot 4)
 ------                      ---------------------------------------                 --------------------------------------------
 SYSTEM_ADMIN ─► Platform  :3001  ┐                                                 ┌── identity ─ access ─ tenancy ─ organization ─ member
 TENANT_ADMIN ─► Admin     :3002  ├─ same-origin /api, /oauth2, /login/oauth2 ─────►│── project ─ schema ─ version ─ component ─ template ─ asset ─ prompt
 members      ─► Studio    :3003  ┘   (rewrite fixed at build time)                 │── app.definition ─ ai (+planner, tenant) ─ integration.llm
 visitors     ─► Published sites ─► Sites gateway (nginx) :18088 ───► /sites/** ────►│── publish (deploy pipeline, sites, forms, domains) ─ maintenance
                                                                                    │── data.* (datasource, query, mapping, gateway, cache, sync, discovery, org)
                                                                                    │── logic.* (action, workflow, approval, scheduler)  ─ wiring.* (glue)
                                                                                    │── code (git, builds, packages) ─ runtime (server apps, connector proxy, SSRF guard)
                                                                                    └── admin ─ settings ─ audit ─ common
                                                                                                │            │           │            │
                                                              PostgreSQL 17.6 (Flyway)    Redis 8 (sessions,   RabbitMQ 4          MinIO (S3)
                                                              source of truth, audit      rate limits, tickets) publish queue +      assets bucket +
                                                                                                                workflow queue       artifacts bucket (private)
 Outside the JVM:  Render worker :18095 (HTTP)   Build/runtime runner (Docker)   Forgejo :13000   Verdaccio :14873   apps DB :15434 + apps gateway :18090 + app containers
                   Keycloak :18080 (optional IdP; OIDC, SAML by brokering)
```

(src: `compose.yml` services and ports; `application.yml`; [`docs/ARCHITECTURE_ASSESSMENT.md`](ARCHITECTURE_ASSESSMENT.md) section 2; [`docs/SOFTWARE_FACTORY_DESIGN.md`](SOFTWARE_FACTORY_DESIGN.md) section 0; `apps/*/package.json`). Ports above are *defaults of the local stack*; every port is an environment value (section 9.4).

---

## 4. The four portals

Three are Next.js applications in the monorepo; the fourth is the published-site surface, which is served by the backend through the sites gateway, not by Next.js. Each Next portal has its own origin, session cookie, CSRF cookie, CSP (nonce, per request) and same-origin `/api` proxy; an app imports packages and never another app. (src: ADR 0022 decisions 1-3; [`docs/FRONTEND_ONBOARDING.md`](FRONTEND_ONBOARDING.md) section 1)

| Portal | Code | Default dev port / local-lifecycle / public | Audience (UI gate; the **server** is the authority) | Purpose |
|---|---|---|---|---|
| **Platform** | `apps/platform` (`@xweb/app-platform`) | 3001 / 3301 / 3201 | `SYSTEM_ADMIN` (`platformScope`) | tenants (companies), all users, AI providers and usage, components, templates, builds, packages, system health, backups, costs, alerts, security, settings, audit, connectors |
| **Admin** | `apps/admin` (`@xweb/app-admin`) | 3002 / 3302 / 3202 | `TENANT_ADMIN` of the tenant, or a person the server lists `TENANT_MEMBERS` / `MEMBER_MANAGE` / `DATA_SOURCE_MANAGE` for | company profile, organization structure, employees, accounts, workspaces, data sources |
| **Studio** | `apps/studio` (`@xweb/app-studio`) | 3003 / 3303 / 3203 | a tenant member for whom the server lists `APP_VIEW` on some workspace | projects, AI chat, Builder (design mode), code mode, templates, components, versions, assets, site settings, publish |
| **Published Site** | no Next app; API `SiteServingController` behind `infra/sites-gateway` | sites gateway 18088 local, 28088 on the public profile | visitors (PUBLIC: anonymous; PRIVATE: host-only session obtained by a Studio ticket) | the released result of a project, plus forms and the public data route |

Notes:

- Platform and Admin are rendered by the **same screen module** (`features/admin/AdminApp.tsx`) with `portal="platform"` / `portal="admin"`. (src: FRONTEND_ONBOARDING section 1)
- Ports 3001-3003 are the `next dev` / `next start` defaults (`apps/*/package.json`). Other values come from scripts: local pinned lifecycle `3301-3303` (`scripts/portals.sh`), public portals `3201-3203` behind the portal gateway `3210` with one hostname per portal, isolated test stacks use free ports of their own (for example `c0rc`: API 47300, Studio 47307, Platform 47308, Admin 47309, sites gateway 47305). (src: [`docs/PUBLIC_DEPLOYMENT.md`](PUBLIC_DEPLOYMENT.md); `docs/parallel/c0/PUBLIC_DEPLOYMENT_PINNING.md`; `docs/parallel/DECISIONS.md` D-C0-59)
- Choosing a portal is navigation intent, never authorization (ADR 0002); a hidden button is not security. The UI decides what to *show*; the backend checks tenant, workspace, project and permission on every call. (src: ADR 0002, ADR 0022 decision 3; `packages/permissions`)
- Sessions and cookies are per origin: signing in on one portal does not sign in on another. OIDC login is currently enabled for one portal at a time (interim rule), see `docs/parallel/WEB_SECURITY_CONFIG.md` section 4. (src: that file)
- A **legacy root app** (`app/`, `components/StudioShell.tsx`, `lib/api-client.ts`, port 3100, mock static export for GitHub Pages) still compiles and shares `features/*`; it is not part of V2 and no new feature should be built on it. (src: FRONTEND_ONBOARDING section 1; ADR 0001 is superseded by ADR 0022)
- The production profile refuses to start with public sign-up on (unless `SIGNUP_ALLOW_IN_PROD=true`); the public pilot runs on a developer machine behind a Cloudflare tunnel ([`docs/PUBLIC_DEPLOYMENT.md`](PUBLIC_DEPLOYMENT.md)), not on a production host ([`docs/DEPLOYMENT.md`](DEPLOYMENT.md)). [`docs/PUBLIC_DEPLOYMENT.md`](PUBLIC_DEPLOYMENT.md) and [`docs/IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md) disagree on whether the pilot allows sign-up (UNVERIFIED which is current).

---

## 5. Frontend structure

```
apps/{platform,admin,studio}/   thin Next apps: app/{layout,entry,[[...slug]]/page}.tsx, proxy.ts (CSP nonce), next.config.ts (same-origin rewrites)
packages/
  types/        the ONLY mirror of the backend contracts ("MIRROR of docs/contracts/v2/<file>.md @ <commit> - manual"); a change is a contract change
  api-client/   core.call (CSRF, Idempotency-Key, 15 s timeout, ApiError, 401 hook), `api` object, release + runtime-config helpers
  auth/         PortalApp (router + gates), session provider, auth pages, server/{csp,nextConfig}
  permissions/  canonical server permission codes -> UX decisions; portal gates, safeNext
  ui/           shared components, hooks, icons, design-system CSS
  i18n/         Vietnamese label maps (action names, roles, portal text)
  app-sdk/, company-ui/   (@company/*) published to the internal registry for GENERATED code apps; not workspaces, not used by the portals
features/
  admin/        AdminApp (Platform + Admin consoles), *Model.ts pure rules, *Screens.tsx presentational, *Live.tsx / *Adapter.ts wiring
  studio/       StudioApp (shell, project list), ProjectWorkspace (AI / Design / Code), ReleaseModal, drawers, builder/ (the Builder)
  auth/ ui.tsx session.tsx routing.ts useLoad.ts   2-line re-exports kept for old imports; library.tsx is real
app/ components/ lib/     legacy root app + live helpers (lib/schema-preview.ts and preview-document.ts are the page renderer shared with workers/render)
workers/{render,runner}/  render worker, build/runtime runner (not UI)
tests/ e2e/               unit, browser harness, real-backend E2E; owned-process helper is the only way tests start or stop processes
```

(src: [`docs/FRONTEND_ONBOARDING.md`](FRONTEND_ONBOARDING.md) sections 3-4; root `package.json` workspaces list: the three apps and `packages/{api-client,auth,i18n,permissions,types,ui}`)

Rules (FRONTEND_ONBOARDING section 4): screens call an adapter / model, which calls `packages/api-client`; a screen does not build URLs or call `fetch`. A write in the Builder funnels through one function (`ctx.commit(ops, summary)`) into `PATCH .../schema` with `expectedRevision`; `409 REVISION_CONFLICT` reloads. Unavailable backend capabilities are shown honestly as `NOT_READY`, never faked.

Stack: Next 16.3.8, React 19.2.0, TypeScript 5.8 (`package.json`). Root scripts of note: `build:apps`, `typecheck:all`, `test:unit`, `guard:static`, `gate:frontend` (includes the process-safety guard), `test:e2e:real`.

---

## 6. Backend module map

All packages under `backend/src/main/kotlin/com/systemwebstudio/`. "Role" is the logical owner role of section 13 (C0..C7).

| Package | Owns | Role | Key types / notes |
|---|---|---|---|
| `common` | request id, standard error envelope, rate limiter (Redis), client-IP filter, request size, dependency-failure -> 503, `ProductionConfigValidator` (refuses unsafe prod values), OpenAPI config | C0 | `ApiException`, `RateLimiter`, `ProductionConfigValidator` |
| `identity` | password login (Argon2id), OIDC, SAML by IdP brokering, SCIM 2.0, account activation links, bootstrap admin, session config, security filter chains, `ActiveUserFilter` | C1 | `SecurityConfiguration` (C0-gated file) |
| `tenancy` | tenants, tenant members, `TenantContext`, `ActorKind`, tenant resolver | C1 | `ActorKind` includes `PUBLIC_SITE` |
| `access` | `Permission`, `PermissionMatrix`, `AccessService.forWorkspace/forProject/forTenant/forPlatform`, `/auth/me` tenancy; `access.adapters` = ports and policies the other modules call (gateway authorizer, principal resolver, tenant gate, `PublicSiteAuthorizer`) | C1 | deny by default; 404 for non-members |
| `organization` | company organization: unit types, units, employees, positions, grades (contract, controllers, services) | C1 contract, C3 persistence | persistence in `data/org` (V32) |
| `member` | workspace and project members | C1 | |
| `project` | project lifecycle, lookup, `publishconfig` (publish policy API) | C2 | `ProjectLifecycle` archives / restores through the release scope |
| `schema`, `version` | Page Schema, `SchemaOperation`, `SchemaPatchEngine`, `PageSchemaValidator`, `SchemaService`, `SchemaCommitService` (the single write path), version repository | C2 | hot files |
| `app.definition` | AppDefinition v2: model, reader / codec, validator, operations, `PublicQueries`, data-binding resolver | C2 | superset of Page Schema, backward compatible |
| `component` | component registry (`components`, `component_versions`), metadata API | C2 | seeded by V5 |
| `template` | templates, contributed blocks, sanitizer, library catalog, review workflow | C2 | data, not code (ADR 0006) |
| `asset`, `prompt` | project assets (presigned MinIO URLs), prompt runs | C2 | `asset://<id>` references (ADR 0004) |
| `ai`, `ai.planner`, `ai.tenant`, `integration.llm` | AI Gateway (`AiGate`: model access -> token budget -> money budget -> daily quota), providers (mock, OpenRouter, others), usage / pricing / alerts / tools, App Planner (proposals only), tenant AI sources | C2 | `app.ai-planner.enabled` and `app.tenant-ai.enabled` default OFF |
| `publish` | deployment pipeline, release scope, sites, serving, forms, domains, artifacts | C2 (delegated, D-C0-26) | see [`docs/PUBLISH_RUNTIME.md`](PUBLISH_RUNTIME.md) |
| `integration.deploy`, `integration.storage` | `DeployProvider` port, `PointerFence`; MinIO `ArtifactStore`, `StorageProvider` | C2 (delegated) | |
| `integration.queue`, `integration.queue.workflow` | `JobQueue` (publish queues), workflow queue selection (`amqp` / `memory`) | C0 / C4 | `memory` refused in prod |
| `integration.git`, `integration.secrets` | Forgejo client, secret provider | C0-gated | |
| `code` | code projects, repositories, build jobs and policy, packages, code AI, Git access | C0-gated | `STATIC_APP` |
| `runtime` | server apps (`ServerRuntimeService`), app gateway, connector proxy, `SecretsCrypto`, **`PublicAddress` (SSRF guard)**, app DB provisioning | C0-gated | `runtime/Gateway.kt` not to be edited in place |
| `data.datasource` | data sources, credentials vault, connectors (`postgres`, `rest`), address / target policies, SQL guard | C3 | `DataConnector` is a hot interface |
| `data.discovery`, `data.query`, `data.mapping`, `data.cache`, `data.sync`, `data.api` | schema discovery, approved query / mutation definitions, view models and mappings, cache, one-way sync and webhook ingest, HTTP helpers | C3 | |
| `data.gateway` | `DataGateway` (the only door to the data layer), authorization port, idempotency store | C3 | section 7 |
| `data.org` | JDBC persistence of the organization (V32) | C3 | flag `ORGANIZATION_PERSISTENCE_ENABLED` |
| `logic.action`, `logic.workflow`, `logic.approval`, `logic.scheduler`, `logic.limits`, `logic.notification`, `logic.retention` | action runtime (`SUBMIT_FORM`, `CREATE/UPDATE/DELETE_RECORD`, `CALL_API`, ...), workflow engine (queue worker, retry, DLQ, compensation, TEST mode), approvals, scheduler | C4 | framework-free domain layer; ports only |
| `wiring`, `wiring.persistence` | adapters between layers, runtime controllers (`app-runtime` data / action / workflow routes, Management API transport, public data route), JDBC stores for data and run state | C0 | the only package that imports across layers |
| `admin`, `settings`, `maintenance` | Admin Console API, editable policies with audit (`system_settings`), health probes, retention / cleanup, backups monitor | C0-gated | |
| `audit` | `AuditService.record`; table `audit_events` made append-only by DB triggers | C0-gated | |

(src: directory listing of `integration/v2`; `docs/parallel/OWNERSHIP.md` sections 2-4; [`docs/contracts/v2/integration-contract.md`](contracts/v2/integration-contract.md); D-C0-26 in `docs/parallel/DECISIONS.md`; per-package notes from the cited files in sections 7-10). `docs/parallel/OWNERSHIP.md` itself is older than the code (it lists migrations to V25 and roles C0-C5; see section 13).

---

## 7. Data flow: from the UI to an external system

Fixed chain (CLAUDE.md invariant):

```
UI (published page or Studio preview)
  -> AppDefinition / ViewModel        what the page shows: sections, data bindings -> view models -> queries -> mappings
  -> Query | Action                   by LOCAL id (queryId / actionId) - the client never names a data source, SQL, URL or tenant
  -> Auth + Permission                session -> AccessService.forProject -> canonical permission (APP_USE / QUERY_EXECUTE / ACTION_EXECUTE / DATA_MUTATE ...) ; deny by default
  -> Data Gateway                     C3: tenant context, permission (port again), tenant-scoped data source, approved definition, mapping, cache, audit
  -> Connector                        postgres | rest ; credentials injected server-side ; SSRF guard / target policy ; pinned address
  -> external system                  a company database or HTTP API
```

### 7.1 Authenticated request flow (diagram)

```
Browser (portal origin)                                          API (same JVM)
  POST /api/v1/workspaces/{w}/projects/{p}/app-runtime/queries/{q}/run      <- cookie STUDIO_SESSION + X-XSRF-TOKEN
     |  portal's Next rewrite (/api -> API, fixed at build) -------------->  RequestId / RequestSize / ClientIp filters
     |                                                                        Spring Session (Redis)  -> 503 if Redis is down
     |                                                                        CSRF check ; ActiveUserFilter (account enabled?)
     |                                                                        AppRuntimeDataController (wiring)
     |                                                                          1. AccessService.forProject(user, w, p)  -> tenant from the workspace, membership, archive state; 404 for strangers
     |                                                                          2. strict body parse (unknown field such as tenantId/sql/url -> 400 INVALID_REQUEST)
     |                                                                          3. mode gate: LIVE = active release's version ; TEST = working draft (needs APP_EDIT)
     |                                                                          4. require APP_USE (LIVE) / APP_EDIT (TEST) + QUERY_EXECUTE
     |                                                                          5. load AppDefinition (tenant-checked) -> AppDataBindingResolver: local id -> data source + operation key
     |                                                                          6. DataGateway.runQuery(GatewayContext, GatewayQuery)
     |                                                                               -> GatewayAuthorizer (C1 port, again) -> data source in tenant/workspace -> approved query -> mapping
     |                                                                               -> cache | connector (credential from vault, address policy) -> external system
     |                                                                               -> mapped ViewModelData -> audit
     |  <-- 200 {queryId, mode, cache, result}  or  {code,message,requestId,retryable,details}
```

(src: [`docs/contracts/v2/runtime-api.md`](contracts/v2/runtime-api.md) sections 1-2; `wiring/AppRuntimeDataController.kt:39-42`; `data/gateway/DataGateway.kt:44-76`; `identity/SecurityConfiguration.kt`)

- Writes are **actions**, not a data route: a data mutation is an action of type `SUBMIT_FORM | CREATE_RECORD | UPDATE_RECORD | DELETE_RECORD | CALL_API`; there is no other write route. Mutating LIVE actions need an idempotency key; `IDEMPOTENCY_OUTCOME_UNKNOWN` is never retryable. (src: `runtime-api.md` section 3, D-C0-13 / D-C0-18)
- Workflows: `POST .../workflows/{id}/runs` -> `202`; run and step state live in PostgreSQL (V29: `action_runs`, `workflow_runs`, `workflow_run_steps`), the queue is RabbitMQ in production (`app.workflow.queue=amqp`; `memory` makes the application refuse to start under `prod`). Restart-safe: a run whose lease expired is resumed by the sweeper. (src: `application.yml` `app.workflow`; `db/V29`)
- **Anonymous public read** for published pages is the same chain with a different principal: `PUBLIC_SITE` -> release allow-list -> LIVE binding -> `DataGateway.runQuery`. Described in [`docs/PUBLISH_RUNTIME.md`](PUBLISH_RUNTIME.md) section 14. `PUBLIC_SITE` can never be converted for the action / workflow runtime (`wiring.ActorKinds.toLogic` throws). (src: `tenancy/TenantContext.kt:5-14`)
- Data-source ownership check: **Decision A, final (D-C0-60)**: the gateway authorizer does **not** check data-source ownership (the attempted `GatewayAuthorizer.dataSourceBelongs`, commit `cfb064b`, was reverted and does not exist on `integration/v2`). A foreign or unknown data source stays C3's canonical `404`, so there is no existence oracle (a `403` for the same id would make 403 vs 404 depend on the id). Test `GatewayAuthorizerNoOracleTests`. (src: `docs/parallel/DECISIONS.md` D-C0-60 item 2; `docs/parallel/c1/final-iam-hardening-report.md` row 6)
- **Approval decision** (workflow `APPROVAL` step): `POST .../app-runtime/workflow-runs/{runId}/approvals/{approvalId}/decision`, body `{"decision":"APPROVE"|"REJECT","comment"?}`, answer `{approvalId, approvalStatus, requiredApprovals, approvals, run}`. Authorization in order: tenant gate -> run scope (wrong scope = `404 RUN_NOT_FOUND`) -> existing permission `WORKFLOW_MANAGE` -> the approval belongs to this run and app -> the caller is in the approver snapshot taken when the step started. There is **no** `APPROVAL_DECIDE` permission in this RC; because `WORKFLOW_MANAGE` is held only by `WORKSPACE_ADMIN`, a decider must be a named approver **and** a workspace admin. The step view carries a nullable `approvalId`. Not wired: `notifyTemplateRef` notifications; approvals inbox / list endpoints are deferred. Wiring switch `app.workflow.approvals=auto|jdbc|memory|off` (default `auto`: durable when the `approvals` table exists). (src: [`docs/contracts/v2/runtime-api.md`](contracts/v2/runtime-api.md) section 4 and section 5 table; D-C0-61 items 2-6; `wiring/AppRuntimeConfiguration.kt:132-187`)
- Feature flags that mount these routes (all default **false**, a disabled flag means the controller bean does not exist and the path is a 404): `app.data-platform.enabled` (data runtime and Management API), `app.workflow.enabled` (action / workflow routes and worker). (src: `application.yml`; `runtime-api.md` section 1)
- The connector proxy for **server apps** is separate (`runtime/Gateway.kt`: `ConnectorProxyController` injects the credential server-side; server apps cannot see credentials) and shares the SSRF guard `PublicAddress`. (src: `docs/parallel/OWNERSHIP.md` section 5; ARCHITECTURE_ASSESSMENT section 3)

### 7.2 Identity, tenancy and permissions (summary)

- **Tenant model** (V26): `tenants`, `tenant_members(role TENANT_ADMIN|MEMBER)`; a workspace belongs to exactly one tenant; `workspace_members`, `projects`, `project_members` carry a `tenant_id` equal to their workspace's (composite FKs). The tenant is **always resolved server-side** from the workspace in the path; clients never send it. Unknown tenant / no membership -> `404` (existence is never disclosed); suspended tenant -> `403 TENANT_SUSPENDED`; deleted -> `404`. (src: [`docs/contracts/v2/tenant-permission.md`](contracts/v2/tenant-permission.md) sections 1-2; C1 report section 2)
- **Permission vocabulary**: 14 canonical application codes (`APP_VIEW, APP_USE, APP_EDIT, APP_PUBLISH, APP_SHARE, DATA_SOURCE_VIEW, DATA_SOURCE_MANAGE, QUERY_EXECUTE, DATA_MUTATE, ACTION_EXECUTE, WORKFLOW_EXECUTE, WORKFLOW_MANAGE, TENANT_MANAGE, TENANT_MEMBERS`), plus `MEMBER_MANAGE` and six organization codes in `/auth/me` (21 codes). Legacy `PROJECT_*` enum constants remain as storage of the `APP_*` codes (for example `PROJECT_PUBLISH` = `APP_PUBLISH`). `PermissionMatrix` is explicit and deny-by-default; roles: project `VIEWER/EDITOR/PUBLISHER/OWNER`, workspace `WORKSPACE_ADMIN/EDITOR/PUBLISHER/VIEWER`, tenant `TENANT_ADMIN/MEMBER`; `SYSTEM_ADMIN` is a **platform** role that does not read tenant business data unless `app.tenancy.system-admin-business-access=true` (default false). A decision is never made by comparing a role name; it is recomputed from the database on every request, so a removed membership or disabled account loses access on the next request of the same session. (src: `access/Permission.kt:1-137`; tenant-permission.md sections 4-5; C1 report section 1 "ROLE_SHORTCUTS: 0")
- A tenant relation such as "manager of a unit", a position or a grade never grants a permission. (src: `access/Permission.kt:86-89`)

---

## 8. AI flow

```
Prompt (text, from a permitted user)
  -> AiGate.authorize   model enabled -> access rules (org / workspace / role / user) -> token budget -> money budget -> daily quota ; AiGate.after prices and records every call
  -> LLMProvider        mock simulator | OpenRouter | other providers (keys encrypted, write-only; model output is UNTRUSTED DATA)
  -> Structured Operation   typed operations from a closed vocabulary (ADD_SECTION, UPDATE_PROP, ADD_ITEM, ... + V2 ops such as ADD_QUERY, ADD_DATA_BINDING) ; no field can carry code, SQL, URL or credential
  -> AppDefinition      applied to a COPY of the current document by SchemaPatchEngine
  -> Validator          PageSchemaValidator (component registry: types, required props, lengths, item shapes) + AppDefinitionValidator (references, permissions vocabulary, publish draft)
  -> Version            SchemaCommitService: validate -> compare-and-swap project revision (409 REVISION_CONFLICT) -> save -> IMMUTABLE version (gapless number) -> usage -> audit, in ONE transaction
```

(src: [`docs/contracts/v2/app-definition.md`](contracts/v2/app-definition.md) sections 1, 4, 6; `version/SchemaCommitService.kt:21-78`; `ai/planner/PlanGuard.kt:13-24`; `ai/AiGovernance.kt:329`; ADR 0014; `docs/ARCHITECTURE.md` stub content on operations)

Properties:

- **Manual edits and AI edits are the same path** (`PATCH .../schema` with `expectedRevision`): same validation, versioning and audit. An AI edit that is applied is indistinguishable from the same operations sent by Design mode.
- AI has **no data model of its own**. The **App Planner** (`ai/planner`) turns one sentence into a *proposal* (operations plus the resulting document); it stores nothing, publishes nothing, runs no query. `PlanGuard` refuses, whatever the document looks like: writing the document directly, creating or changing a data source, using a data source / operation not granted to the user, weakening permissions, changing how the app is published, carrying a URL / SQL / credential / script in any value. It may only use sources and operations from an `AiDataCatalog` supplied by the data layer. Flag `app.ai-planner.enabled` defaults OFF (turning it on is a recorded decision). (src: `PlanGuard.kt`, `AppPlanner.kt`, `integration-contract.md` section 6)
- Nothing generated is executed on the JVM. Websites: the preview renders escaped data into a sandboxed iframe (ADR 0003); published pages contain no author script (build fails on a `<script>`, except the one runtime reference of data-bound pages). Code apps: generated source is text, committed on a branch, built in a sandbox, scanned, reviewed before merge (ADR 0010-0013).
- Until a provider key is configured a labelled **simulator** answers; keys are read from the environment / stored encrypted, never returned by an API. Real provider calls are not exercised by the default test suite (`OpenRouterLiveTests` is skipped without a key). (src: `README.md`; [`docs/IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md) AI rows; D-C0-27 note on 3 skipped tests)

---

## 9. Infrastructure

### 9.1 Data stores and brokers

| Component | Use | Facts |
|---|---|---|
| **PostgreSQL 17.6** + **Flyway** | source of truth: identity, tenants, projects, versions, deployments, audit, data-runtime definitions, run state, organization | one schema; Hibernate `ddl-auto: validate` (drift fails fast); Flyway forward-only, `outOfOrder` never enabled; Hikari pool default max 10. A separate **apps DB server** (`appdb`, port 15434) hosts one database + one login role per server app and is on a different network from the platform DB. (src: `application.yml:1-40`; `compose.yml`; ADR 0018) |
| **Redis 8.2.1** | Spring Session (indexed, so sessions can be revoked by user), rate limits (`RateLimiter`), private-site tickets and sessions | `appendonly yes`, `maxmemory 256mb`, `maxmemory-policy noeviction`: keys are never evicted silently. Redis down = every API request needs it, so the app answers `503` with `Retry-After` via `DependencyFailureFilter`. (src: `compose.yml:22-31`; `common/DependencyFailureFilter.kt`) |
| **RabbitMQ 4** | queue `studio.publish` (+ dead letter `studio.publish.dlq`); workflow queue under `app.workflow.queue=amqp` | the publish worker is a `@RabbitListener` in the **same JVM** (concurrency 2, prefetch 1, failed messages not requeued). A scheduled sweeper re-publishes stuck deployments, so the broker being down at commit time is tolerated. (src: `integration/queue/JobQueue.kt`; `publish/PublishWorker.kt`) |
| **MinIO** (S3 API) | bucket `studio-assets` (project files, presigned URLs) and private bucket `studio-artifacts` (immutable release artifacts; sites are served **through the API**, never by a public bucket) | `bitnamilegacy/minio` pinned for local development only; production uses any S3-compatible store. (src: `application.yml` `app.storage`; `compose.yml`) |
| **Sites gateway** (nginx-unprivileged) | one gateway for all published sites; forms, public data route and server-app API routes are the only non-GET routes | [`docs/PUBLISH_RUNTIME.md`](PUBLISH_RUNTIME.md) section 11 |
| Keycloak (optional) | OIDC IdP; SAML via **brokering** (ADR 0021: no native SAML, to keep OpenSAML out of the supply chain) | |
| Forgejo, Verdaccio, build runner, apps gateway | stack profiles **lean / medium / full** (ADR 0020): lean = websites, templates, AI, admin; medium + source-code apps and dashboards; full + server apps / internal tools / workflows | `STACK_PROFILE=lean|medium|full ./scripts/run-local.sh` (src: `README.md`; [`docs/DEPLOYMENT.md`](DEPLOYMENT.md)) |

### 9.2 Cross-cutting runtime behaviour

- **Standard error envelope** `{code, message, requestId, details}` (plus `retryable` on runtime routes); request id in every log line; `404` instead of `403` where existence must not leak. (src: `common/ApiErrors.kt`; `runtime-api.md` section 1)
- **Rate limiting**: Redis fixed windows (per user for publish, per address / site for public routes). A Redis outage refuses (fail closed) on the public data route. (src: `PublicDataController.kt`)
- **Secrets**: provider keys, connector credentials and project secrets are stored AES-GCM encrypted with `SECRETS_MASTER_KEY`, write-only (no API returns them). Credentials exist only server-side. (src: `runtime/RuntimeSupport.kt:19`; `data/datasource/Credential.kt:66`)
- **Observability**: `/actuator/health/liveness|readiness` (readiness group: db, redis, rabbit, minio), Prometheus meter registry (metrics endpoint opt-in), OTLP tracing export opt-in, structured deploy logs. Admin console shows live probes in five honest states. (src: `application.yml:100-120`; `build.gradle.kts`; [`docs/DEPLOYMENT.md`](DEPLOYMENT.md) "Health")
- **Backups**: `scripts/backup-all.sh` (platform DB, app DBs, MinIO, Forgejo) with verification and weekly restore drills; production requires an off-host copy (BLOCKED_EXTERNAL_INPUT). (src: ADR 0020, [`docs/DEPLOYMENT.md`](DEPLOYMENT.md))
- **Production guard**: `ProductionConfigValidator` refuses to start under `prod` with `DEPLOY_PROVIDER=mock`, profile `local`, weak or dev-looking passwords, non-secure cookies, Swagger on, public sign-up on, non-https / private `SITES_DATA_API_BASE`, missing `SITES_ORIGIN` / `STUDIO_ORIGIN` / `RENDER_URL`, an in-memory workflow queue, and others. (src: `common/ProductionConfigValidator.kt`; [`docs/DEPLOYMENT.md`](DEPLOYMENT.md))

### 9.3 Production readiness (honest summary)

The pilot and the local stack run; production is **not** ready: a Linux runtime host with gVisor for server apps (Docker Desktop's VM is not an isolation boundary, ADR 0019), off-host backups, real provider keys and a public host for the HTTP release probe are outstanding. (src: [`docs/IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md) header, [`docs/DEPLOYMENT.md`](DEPLOYMENT.md), [`docs/PUBLISH_RUNTIME.md`](PUBLISH_RUNTIME.md) section 10). The target definition (V1 = whole system on one macOS machine, production-shaped; V2 = VPS / cloud changes configuration only) is `docs/parallel/V1_LOCAL_TARGET.md`.

### 9.4 Configuration principle

Everything that differs between a laptop and a VPS is an environment value read at run time, never baked into an artifact (for example the published app's `apiBase`, `app.sites.origin`). `application.yml` lists a code default and an env name for each key; `application-local.yml` holds local values; `application-prod.yml` requires the environment-specific ones with no default. (src: `published-runtime.md` section 3, D-C0-34)

---

## 10. Invariants that must be preserved

These are the load-bearing rules. Changing one needs an ADR / `DECISIONS.md` entry and the owner's review; they are listed as a boundary in `CLAUDE.md`, `docs/parallel/OWNERSHIP.md` section 5 and the frozen contracts.

| # | Invariant | Where it is enforced | Why |
|---|---|---|---|
| I1 | **Page Schema + `SchemaPatchEngine` + `PageSchemaValidator`**: documents change only by typed operations applied to a copy and validated against the registry; legacy documents round-trip unchanged | `schema/*`, `version/SchemaCommitService`, `app/definition` validator | the safety of "AI never writes code" rests on a closed operation vocabulary |
| I2 | **Component registry and versions**: sections reference `type` + `componentVersion` in `components` / `component_versions`; props schema per version; contributed blocks are presets of approved components | `component/*`, `schema/PageSchemaValidator`, V5 / V11 | rendering is fixed code for approved components |
| I3 | **Immutable project versions**: `project_versions` snapshots never change; restore appends a RESTORE version; numbers are gapless; commits use compare-and-swap on `projects.revision` | `SchemaCommitService`, V4 | releases pin a version; the public allow-list is derived from the snapshot |
| I4 | **AI Gateway**: every model call passes `AiGate` (access, budgets, quota) and is recorded; provider keys encrypted; model output is untrusted data | `ai/*`, `integration/llm/*`, ADR 0007 / 0014 | cost and trust boundary |
| I5 | **Connector Proxy + SSRF guard + credentials only on the server**: outbound addresses are checked by `PublicAddress` (resolve once, connect to that address; private, loopback, link-local, metadata, NAT64 / mapped forms denied), the data layer adds pinned transports and an explicit target allow-list; secrets never in artifacts, documents, logs or API responses. Do not copy-paste or fork the guard: reuse it | `runtime/Gateway.kt` (`PublicAddress`), `data/datasource/*` (`AddressPolicy`, `PinnedSocketFactory`, `PinnedHttpsTransport`), `wiring/PostgresTargetPolicies` | server-side request forgery and credential theft |
| I6 | **Audit is append-only**: `audit_events` rejects UPDATE, DELETE and TRUNCATE by trigger; code only calls `AuditService.record`, and never changes the table or trigger | V3 (`audit_events_immutable`), `audit/AuditService` | forensic integrity |
| I7 | **Publish pipeline**: accepted request -> durable deployment -> queue after commit -> CAS steps -> verified artifact -> pointer moved only by CAS under a lease with a fencing token; rollback uses the immutable artifact; fail closed | `publish/*`, V30 | no half-published or mixed releases (see [`docs/PUBLISH_RUNTIME.md`](PUBLISH_RUNTIME.md)) |
| I8 | **Render / build / runtime planes** stay separate: the API JVM never executes generated code; the render worker is data-in / HTML-out; builds run in sandboxes; server apps run in isolated containers with a per-app database | `workers/*`, `code/*`, `runtime/*`, ADR 0009-0010, 0017-0019 | blast radius |
| I9 | **Backward compatibility**: existing API, the `projects` table, Page Schema, `STATIC_APP` keep working; no feature of V1 is removed to build V2; no renaming of `projects` | whole codebase, `CLAUDE.md` | |
| I10 | **Tenant isolation is server-derived**: tenant id is never read from a request; every query joins tenancy through the project / workspace; non-members get `404`; public routes derive everything from the site slug | `access/*`, `tenancy/*`, `wiring/*`, `publish/*` | |
| I11 | **Deny by default** for permissions, actor kinds, modes and wiring; an unknown value answers an error, never data | `Permission.kt`, `GatewayAuthorizer`, `PublicSiteAuthorizer` | |
| I12 | **No second source of truth for what is live**: the active release is `sites.current_deployment_id`, moved only by the fenced compare-and-set; the data runtime resolves LIVE from the same pointer | `JdbcScopeGuard`, `ReleaseCasTests` (source-scan) | |
| I13 | **Migrations**: forward-only, numbers allocated by one role in ascending order, `outOfOrder` never enabled, applied migrations never edited | `docs/parallel/MIGRATION_LEDGER.md`, `CLAUDE.md` | |

Process rules that protect the invariants (from `CLAUDE.md`): no merge to `main`, no force-push, no `reset --hard`, no `clean -fd`, no GitHub Actions, no killing processes by name or by port (only a pid or process group the actor started, or the owned-process tool; guard `tests/guards/process-safety.mjs` is part of `npm run gate:frontend`).

---

## 11. Contracts index (`docs/contracts/**`)

Contracts are frozen by the integrator role (C0); a change needs a `DECISIONS.md` entry first. The directory `docs/contracts/` holds five **superseded** Phase 0 drafts (kept for history, do not implement from them) and the current set in `docs/contracts/v2/`.

| File | Owner | Content | Status |
|---|---|---|---|
| `v2/integration-contract.md` | C0 | package layering, one definition per concept, conformance fixtures, migration order, switches that stay OFF, verification gate, frontend rules | frozen 2026-10-05 |
| `v2/tenant-permission.md` | C1 | tenant model, `TenantContext`, `AccessContext`, SYSTEM_ADMIN vs TENANT_ADMIN, the 14 / 21 canonical permission codes, role matrix | frozen; supersedes `tenant-context.md`, `permission-model.md` |
| `v2/app-definition.md` | C2 | AppDefinition v2 stored keys, who owns each list, operations, publish model (draft vs policy vs served state), AI, component metadata | frozen; supersedes `app-definition-v2.md` |
| `v2/data-runtime.md` | C3 | data runtime shapes (data source, query / mutation definitions, view models, mappings, gateway, mutation semantics) | frozen; supersedes `data-connector.md` |
| `v2/action-workflow.md` | C4 | action and workflow declaration and runtime model | frozen; supersedes `action-workflow.md` |
| `v2/runtime-api.md` | C0 | browser-facing `app-runtime` routes: query run, action execute, workflow runs (frozen by D-C0-17..20), plus the approval decision route and nullable step `approvalId` (D-C0-61) | frozen 2026-10-06; extended 2026-10-11 (D-C0-61) |
| `v2/management-api.md` | C0 / C3 | Management API: data sources, credentials, schema, query / mutation definitions, TEST / LIVE bindings | frozen D-C0-28; integrated D-C0-30 |
| `v2/published-runtime.md` | C0 | release activation (LIM-1), `apiBase`, the Public Runtime (`PUBLIC_SITE`), allow-list, gap map | frozen D-C0-33..35 |
| `docs/contracts/action-workflow.md`, `app-definition-v2.md`, `data-connector.md`, `permission-model.md`, `tenant-context.md` | - | Phase 0 drafts (each file says which v2 contract replaced it) | **SUPERSEDED** |

(src: first lines of each file; [`docs/contracts/v2/integration-contract.md`](contracts/v2/integration-contract.md))

Other normative contract-like documents: `docs/parallel/c0/C2_DEPLOY_CONTRACT.md` (release scope and rollback), `docs/parallel/c2/PUBLISH_API_CONTRACT.md` (publish HTTP contract, pinned by tests), `docs/parallel/c0/ORGANIZATION_API_CONTRACT_FOR_C5.md`, [`docs/API_CONTRACT.md`](API_CONTRACT.md) (legacy API contract, older than the v2 set, UNVERIFIED how current). `packages/types` is the mirror used by the frontend.

---

## 12. ADR index (`docs/adr/*`)

| ADR | Meaning in one line | Status (per file header) |
|---|---|---|
| 0001 One frontend, two shells | single Next app with a catch-all client router, Admin and Studio shells | superseded by 0022 |
| 0002 Portal choice is intent | the chosen portal is navigation only; admin access is the live `system_admin AND enabled` checked on every request | accepted, in force |
| 0003 Preview selection via postMessage | preview iframe is sandboxed; Design mode alone allows a script posting `studio:select`; no `allow-same-origin` | accepted |
| 0004 Asset references | images are `asset://<uuid>` only; commit rejects non-READY / foreign assets; renderer resolves through the project's own list | accepted |
| 0005 Software Factory boundary | the first statement of the "code is generated somewhere" trust change; detailed by 0008-0012 | proposed (superseded in scope by 0008) |
| 0006 Templates and blocks are data | a template is a validated page schema; a block is a preset of an approved component; no code | accepted |
| 0007 Multi-provider AI | extend the in-process provider abstraction instead of a LiteLLM gateway; one trust boundary | accepted |
| 0008 Application types and roadmap | `PAGE_SCHEMA` and `STATIC_APP` (`DYNAMIC_APP` not designed), capabilities enforced server-side | proposed (header); implemented in parts by 0009-0020 |
| 0009 Runtime plane: static artifacts | immutable content-addressed artifacts in object storage, pointer switch = deploy, gateway serves | accepted and implemented |
| 0010 Build plane and sandbox | per-job sandboxes on a separate Linux host, never in the API JVM; gVisor for production | accepted; implemented locally |
| 0011 Git for code projects | self-hosted Forgejo, platform-owned repos, protected `main`, bot merges | accepted; implemented locally |
| 0012 Code generation | spec first, patches on `ai/<change>` branches, validated by builds, preview before merge | accepted; implemented locally |
| 0013 Package policy | users and AI request packages; admin approval, OSV scan, mirror allowlist, locked installs | accepted and implemented |
| 0014 AI gateway governance | one gate: model access -> token -> money budgets -> quota; alerts; streaming; tools | accepted and implemented |
| 0015 Websites, forms, domains, CDN | multi-page schema, atomic multi-page artifacts, forms, custom domains, caching rules | accepted and implemented |
| 0016 Identity: OIDC logout, SAML, SCIM, MFA | RP-initiated logout, SAML by brokering, SCIM 2.0, MFA at the IdP | accepted and implemented |
| 0017 Server runtime | Node/TypeScript server apps in isolated containers; app kinds; off by policy by default | accepted; implemented locally |
| 0018 App database isolation | separate Postgres server, one database + role per app, platform DB unreachable | accepted and implemented |
| 0019 Sandbox isolation levels | level 2 hardened container today; gVisor level 3 for Linux production; Kata / Firecracker later | accepted (level 2 implemented) |
| 0020 Backup, DR, profiles | verified backups of every store, restore drills, auto restart, lean / medium / full profiles | accepted and implemented |
| 0021 SAML via broker | do not add native SAML (OpenSAML supply-chain, second login path) | accepted |
| 0022 Three frontend deployments | Platform / Admin / Studio apps, one monorepo, shared packages carry contracts not authority, one backend | accepted; supersedes 0001 |

(src: first lines of each `docs/adr/*.md`; Status words are the files' own, not a judgement of this document.)

---

## 13. Ownership and module boundaries (logical roles C0..C7)

The system was built by parallel roles with strict boundaries. The roles are a **way of dividing responsibility**, not folders: a role decides the concepts below, and no one else changes them. Folder mapping is in section 6; the authoritative ownership table is `docs/parallel/OWNERSHIP.md` (older than the code in places: it names V1-V25 and roles C0-C5 only).

| Role | Responsibility | Decides (hot concepts, changed by this role only) | Must ask another role for |
|---|---|---|---|
| **C0 - Architect / integrator** | review and freeze contracts, import branches, integration tests, wiring of ports into runtime routes, shared build and configuration, **allocation of migration numbers**, release tooling (portals / API pinning, process safety), coordination documents | contracts (`docs/contracts/**`), ADRs, `docs/parallel/**`, build files and `application*.yml`, `common` base layer, security chains, `wiring.*`, compose, `CLAUDE.md`, test support classes | feature semantics of C1-C5 |
| **C1 - Tenant, identity, permission, sharing, organization contract** | authentication, sessions, tenants, roles, the permission vocabulary and matrix, `AccessService`, SYSTEM_ADMIN / TENANT_ADMIN semantics, tenant lifecycle, organization and employee contract, `PublicSiteAuthorizer` | `Permission` / `PermissionMatrix` / `AccessContext`, `ActorKind`, `TenantContext` | a migration number (C0); persistence of organization data (C3) |
| **C2 - App definition, schema, version, component, templates, AI planning, publish** | Page Schema and AppDefinition v2, validators and the commit path, component registry, templates and blocks, assets, prompts, AI Gateway use of structured operations, publish pipeline and sites (delegated by D-C0-26) | `AppDefinition*`, `SchemaOperation`, `PageSchemaValidator`, `SchemaPatchEngine`, `SchemaCommitService`, `publish/**`, `integration/deploy`, `integration/storage` | a migration number; no change of C1 / C3 / C4 contracts without a handoff; `runtime/**` and `code/**` changes are C0-gated |
| **C3 - Data platform** | data sources and credentials, connectors, schema discovery, approved query / mutation definitions, view models and mappings, `DataGateway`, cache, sync, webhook ingest, JDBC persistence of the data runtime and (V32) the organization | `DataGateway`, `DataConnector` and their interfaces | permission decisions (C1 port); SSRF primitive `PublicAddress` is C0-gated and reused read-only |
| **C4 - Action, workflow, approval** | action runtime, workflow engine (queue worker, retry, DLQ, compensation, TEST mode), approvals, scheduler, limits, notification ports | `ActionRuntime`, `WorkflowRuntime`, approval service | permissions (C1), data access (C3 via port), persistence wiring (C0); migration numbers |
| **C5 - Web builder, admin UI, E2E** | the three portals, shared frontend packages, Builder, real-backend E2E harness | `features/studio/ProjectWorkspace`, `lib/schema-preview`, `lib/http-api` / `packages/api-client`, `StudioApp` | **never creates migrations**; backend contract changes via C0 |
| **C6 - Independent QA** | independent regression and final-RC QA on the exact stack, evidence archives, bug ledger; does not fix product code | QA verdicts per commit / stack | fixes go to the owning role |
| **C7 - Release decision maker** | owner-level decisions (for example D-C0-26 "decision by C7", scope of V29 / V30, the choice of approach in the API-pinning adoption) and final release acceptance together with C0 | go / no-go and scope decisions | - |

(src: `docs/parallel/OWNERSHIP.md` sections 2-4; `docs/parallel/BOARD.md`; `docs/parallel/DECISIONS.md` D-C0-26 ("decision by C7"), D-C0-27, D-C0-52, D-C0-60 ("C0 (final consolidation, C7)"); C6 / C7: `docs/parallel/c6/FINAL_RC_QA_REPORT.md` header "Written for: C7 / C0 (release decision makers)", `docs/parallel/c0/PUBLIC_API_PINNING.md` "Decision (C0 / C7)". `OWNERSHIP.md` and `CLAUDE.md` still name only C0-C5 (re-checked at `28376de`), so C6 = independent QA and C7 = release decision maker are taken from these usages, not from a formal role statement: **kept as a note, not hedged away**.)

Rules that bind every role (CLAUDE.md; OWNERSHIP sections 3-7):

1. Read the ownership table before changing code; do not edit another role's files, especially hot concepts. If a change in another area is needed, record it in `docs/parallel/BLOCKERS.md` or the Depends column of `BOARD.md` and wait for the owner.
2. **Only C0 allocates Flyway versions**: a role requests a number in the migration-requests section of `BOARD.md`, C0 allocates the next free version, one version belongs to one task, the file is created only after allocation, branches merge migrations in ascending order, `outOfOrder` stays off, applied migrations are never edited.
3. Shared contracts (`docs/contracts/**`) change only with a `DECISIONS.md` entry. Decision ids are namespaced (`D-C0-*`, `D-C1-*`, ...; blockers `B-*`, handoffs `H-*`).
4. Legacy modules without an owner in the original assignment (`runtime`, `code`, `integration/{git,queue,secrets}`, `audit`, `admin`, `settings`, `maintenance`, `common`) are **C0-gated**: every change needs a board task and C0 approval. `audit` is call-only.
5. Preserve backward compatibility (I9); do not move to microservices; per task: test and commit separately; the end-of-task report lists files changed, tests, blockers and commit SHA.

---

## 14. Database migrations V1..V33 (V31 void)

Flyway, forward-only, applied at startup; `V1..V33` give **32 migrations** on a fresh database (32 files in `db/migration`) because **V31 is a permanent void gap** (the number was reserved for a candidate-activation change and then overtaken by V32). **The next free number is V34; none is allocated** and nobody may pick or reserve it (only C0 hands numbers out). Undo scripts for some migrations exist **outside Flyway** (`docs/parallel/c0/undo/U28`, `U29`, `docs/parallel/c2/undo/U27`, `U30`, `docs/parallel/c3/undo/U32`, `docs/parallel/c0/undo/U33`) and are run by hand. (src: `docs/parallel/BOARD.md` migration table; `docs/parallel/DECISIONS.md` D-C0-52, D-C0-59 recorded 31 migrations at V32; D-C0-61 added V33; `docs/parallel/MIGRATION_LEDGER.md`)

| Version | File | Adds |
|---|---|---|
| V1 | `identity_workspace_project` | `users`, `workspaces`, `workspace_members`, `projects`, `project_members` |
| V2 | `remove_shared_workspace_seed` | deletes the shared demo workspace seed |
| V3 | `rbac_audit_project_settings` | RBAC columns, `audit_events` (append-only triggers), project settings |
| V4 | `schema_versions_assets_deployments` | component registry tables, `page_schemas`, `project_versions`, `prompts`, `prompt_runs`, `component_usage`, `assets`, `deployments`, `deployment_events`, `idempotency_keys` |
| V5 | `seed_component_registry` | seeds the approved components |
| V6 | `sso_email_external_identities` | e-mail, auth source, `external_identities` |
| V7 | `prompt_run_model` | which model answered a prompt |
| V8 | `admin_indexes` | audit indexes for the admin console |
| V9 | `asset_image_props` | optional images (`asset://`) on Hero / ProductGrid |
| V10 | `ai_calls` | one row per upstream model call (usage accounting) |
| V11 | `templates_component_packages` | templates, contributed blocks (`component_packages`, versions, reviews) |
| V12 | `ai_providers_pricing` | `ai_model_policies`, `ai_model_pricing` |
| V13 | `static_sites` | `artifacts`, `sites`, `deployments.artifact_id` |
| V14 | `code_projects` | `projects.app_type`, `repositories`, `code_changes`, `build_jobs` |
| V15 | `settings_quotas_retention` | `system_settings`, `build_rejections` (lockdown, quotas, retention) |
| V16 | `packages_review_access` | approved packages, dependency requests, Git access |
| V17 | `ai_governance` | model access rules, budgets, `admin_alerts`, tool calls |
| V18 | `library_catalog` | template / block catalog metadata, template reviews |
| V19 | `forms_domains` | `form_submissions`, `site_domains` |
| V20 | `admin_org_costs` | `departments`, `cost_prices` |
| V21 | `scim` | SCIM groups, members, mappings |
| V22 | `server_runtime` | `projects.app_kind`, `app_runtimes`, `server_deployments`, `project_secrets`, `connectors`, `project_connectors` |
| V23 | `domain_claims` | domain claim rules (first to verify wins) |
| V24 | `accounts_activation` | `activated_at`, `account_tokens` (one-time activation links) |
| V25 | `ai_providers_limits` | `ai_providers` (encrypted keys), `ai_limit_overrides` |
| V26 | `tenant_foundation` | `tenants`, `tenant_members`, `tenant_id` on workspace-scoped tables, default tenant backfill, composite FKs |
| V27 | `publish_configs` | persistent per-project publish policy (+ backfill) |
| V28 | `data_runtime` | `data_sources`, `data_credentials`, `source_schemas`, `data_queries`, `data_mutations`, `data_idempotency`, `data_source_bindings` |
| V29 | `workflow_run_persistence` | `action_runs`, `workflow_runs`, `workflow_run_steps` |
| V30 | `deployment_rollback_and_scope_lease` | `ROLLING_BACK` status, `activation_seq`, `previous_deployment_id`, release scope lease / fencing / pointer version on `sites` |
| V31 | - | **void** (never applied; permanent gap) |
| V32 | `dynamic_organization` | `organization_unit_types`, `organization_units`, `positions`, `grades`, `employee_organization_units`, `employee_positions` |
| V33 | `approvals` | durable workflow approvals: one table `approvals` + five indexes (idempotency key, expiry sweep, inbox GIN on `approvers`, source run, retention); `tenant_id` FK, `app_id` plain FK to `projects` (tenant consistency enforced by the engine, a documented limit); no FK to `workflow_runs` on purpose. Owner C4, number allocated by C0 (D-C0-61). Undo `docs/parallel/c0/undo/U33__approvals.sql` (refuses while an approval is PENDING) |

(src: filenames and header lines of `backend/src/main/resources/db/migration/*` on `integration/v2` @ `28376de`; `V33__approvals.sql`; `docs/parallel/BOARD.md` migration table; D-C0-61.) V2 and V5 are data migrations; the table lists principal objects only.

### What the last finalization imported (2026-10-11)

Factual list of what is on `integration/v2` @ `28376de` beyond the earlier RC candidate `7d46ec5`:

1. **C4 durable workflow approvals** (`fa42ad2`, D-C0-61): V33, `JdbcApprovalStore`, `WorkflowRuntime.decideApproval`, the decision route and nullable `approvalId` (section 7.1). `notifyTemplateRef` notifications are not wired; approvals inbox / list is deferred; a dedicated `APPROVAL_DECIDE` permission is only a proposal to C1.
2. **C1 final IAM hardening** (merge `35c5d7d`, D-C0-60): a suspended company is frozen for its tenant admins (`403 TENANT_SUSPENDED`), a deleted company refuses workspace creation and member changes for everybody, last `TENANT_ADMIN` / `SYSTEM_ADMIN` rules are race-safe (row locks), OIDC login erases credentials before the session is saved, `seesAllProjects` is decided by `PROJECT_READ`, **Decision A final** (no data-source ownership check in the gateway authorizer). Open items owned by other roles are listed in `docs/parallel/c1/final-iam-hardening-report.md` section 3.
3. **C5 UI fixes** (`2cf2ed6`): FQ-UI-01 (`/admin/employees` clipping with long names) and FQ-A11Y-02 (focus returns to the opener after a route-style dialog closes).
4. **C4 interrupted-worker fix** (`140dc36`): an interrupted workflow worker takes no further message (`Thread.isInterrupted` guard in `WorkflowWorker`), with `WorkflowHardeningTests`.
5. **C6 QA documents** (`28376de`): final RC QA report, QA master, matrices, harness and text evidence under `docs/parallel/c6/`; the large screenshot archives stay outside git.

---

## 15. Request flow: publish and view a site (summary)

Full description: [`docs/PUBLISH_RUNTIME.md`](PUBLISH_RUNTIME.md).

```
Studio ──POST .../publish (Idempotency-Key, expectedRevision, visibility)──► API: PROJECT_PUBLISH, revision, policy, approval checks (sync refusals create nothing)
                                                                       └─► 202 + deployment QUEUED  (row + audit, then RabbitMQ message after commit)
RabbitMQ studio.publish ─► PublishWorker ─► QUEUED → POLICY_CHECK → SECURITY_CHECK → BUILDING (render worker → artifact in MinIO + manifest)
                                          → DEPLOYING: take the release scope (lease + fencing token) → verify artifact (hash) → stage → [server runtime]
                                                       → pointer CAS sites.current_deployment_id → confirm → RUNNING   (failure after the switch: ROLLING_BACK → previous release or offline, FAILED)
Visitor ─► sites gateway ─► API /sites/{slug}/** ─► read pointer EVERY request ─► artifact bytes (hash-checked) ─► CSP / cache headers
Visitor page with data ─► POST /{slug}/_data/queries/{id}/run ─► PUBLIC_SITE ─► release allow-list + approval ─► LIVE binding ─► DataGateway ─► connector
```

---

## 16. Where things are documented (map)

| Need | Read |
|---|---|
| Start here, run it | `README.md`, [`docs/LOCAL_DEVELOPMENT.md`](LOCAL_DEVELOPMENT.md), [`docs/FRONTEND_ONBOARDING.md`](FRONTEND_ONBOARDING.md) |
| Rules for contributors / agents | `CLAUDE.md`, `docs/parallel/OWNERSHIP.md`, `docs/parallel/WORKTREE_SETUP.md`, `docs/parallel/BOARD.md` |
| Publish and published sites | [`docs/PUBLISH_RUNTIME.md`](PUBLISH_RUNTIME.md) |
| Frozen contracts | `docs/contracts/v2/*` (section 11) |
| Decisions | `docs/adr/*` (section 12), `docs/parallel/DECISIONS.md` (very long, namespaced `D-*` ids) |
| Migrations ledger | `docs/parallel/MIGRATION_LEDGER.md`, `docs/parallel/BOARD.md` (migration requests) |
| Security posture | [`docs/SECURITY.md`](SECURITY.md), [`docs/AUTHENTICATION.md`](AUTHENTICATION.md), `docs/parallel/WEB_SECURITY_CONFIG.md` |
| Deployment / pilot | [`docs/DEPLOYMENT.md`](DEPLOYMENT.md), [`docs/PUBLIC_DEPLOYMENT.md`](PUBLIC_DEPLOYMENT.md), `docs/parallel/c0/PUBLIC_DEPLOYMENT_PINNING.md`, `docs/parallel/c0/PUBLIC_API_PINNING.md`, `docs/parallel/c0/DEMO_STACK_RUNBOOK.md` |
| Status of features | [`docs/IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md) (2026-10-04, older than this document), `docs/parallel/V1_LOCAL_TARGET.md` |
| Earlier architecture analysis (Vietnamese, 2026-10-05, describes V1-V25 and a single-tenant system before V26) | [`docs/ARCHITECTURE_ASSESSMENT.md`](ARCHITECTURE_ASSESSMENT.md): historical, **superseded** by this document where they differ (it states "no tenant concept", which V26 changed) |

---

## Sources read

Documents: `README.md`; `CLAUDE.md` (project, provided in the session); `docs/ARCHITECTURE.md` (old stub, 29 lines); [`docs/ARCHITECTURE_ASSESSMENT.md`](ARCHITECTURE_ASSESSMENT.md) (sections 1-3); [`docs/SOFTWARE_FACTORY_DESIGN.md`](SOFTWARE_FACTORY_DESIGN.md) (sections 0-2); [`docs/IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md) (first 40 lines); [`docs/DEPLOYMENT.md`](DEPLOYMENT.md); [`docs/PUBLIC_DEPLOYMENT.md`](PUBLIC_DEPLOYMENT.md); [`docs/LOCAL_DEVELOPMENT.md`](LOCAL_DEVELOPMENT.md); [`docs/FRONTEND_ONBOARDING.md`](FRONTEND_ONBOARDING.md) (sections 1-4); `docs/adr/0001..0022` (headers and decisions of 0001-0022); `docs/contracts/**` (first lines of all, full of `integration-contract.md`, `published-runtime.md`; large parts of `runtime-api.md`, `app-definition.md`, `tenant-permission.md`); `docs/parallel/OWNERSHIP.md`; `docs/parallel/BOARD.md` (task and migration tables); `docs/parallel/DECISIONS.md` (D-C0-26, 27, 59 and grep of C6 / C7); `docs/parallel/c0/PORTAL_LIFECYCLE.md`, `DEMO_STACK_RUNBOOK.md`, `PUBLIC_API_PINNING.md`, `PUBLIC_DEPLOYMENT_PINNING.md`, `C2_DEPLOY_CONTRACT.md`; `docs/parallel/WEB_SECURITY_CONFIG.md` (CORS / portals); `docs/parallel/c1/final-iam-hardening-report.md`, `docs/parallel/c6/FINAL_RC_QA_REPORT.md` (header), `docs/parallel/MIGRATION_LEDGER.md`, `docs/parallel/DECISIONS.md` D-C0-60 / D-C0-61, `backend/src/main/resources/db/migration/V33__approvals.sql`, `wiring/AppRuntimeConfiguration.kt`, [`docs/contracts/v2/runtime-api.md`](contracts/v2/runtime-api.md) (approval route).

Code / config (all `git show integration/v2:<path>`; first pass at `7d46ec5c5f98`, re-verified at `28376de`): directory listing of `backend/src/main/kotlin/com/systemwebstudio`; `SystemWebStudioApplication.kt`; `identity/SecurityConfiguration.kt` (chains); `access/Permission.kt`; `tenancy/TenantContext.kt`; `version/SchemaCommitService.kt`; `ai/planner/PlanGuard.kt` (header); `data/gateway/DataGateway.kt` (header); `wiring/AppRuntimeDataController.kt`, `AppRuntimeActionController.kt`, `PublicDataController.kt` (headers); `integration/queue/JobQueue.kt`; `runtime/Gateway.kt` (structure); `publish/*` (see [`docs/PUBLISH_RUNTIME.md`](PUBLISH_RUNTIME.md)); `application.yml`, `application-local.yml`, `application-prod.yml`; `backend/build.gradle.kts`; `compose.yml`; root and `apps/*/package.json`, `packages/*/package.json`; migrations V1..V32 (headers and created tables).

## Open questions / UNVERIFIED

Resolved in the finalization pass (from code at `28376de`): V33 file, undo and numbering; the next free migration number is V34 (none allocated, `MIGRATION_LEDGER.md`); the session timeout keys (`spring.session.timeout` and `server.servlet.session.timeout` are `20m` fallbacks in `application.yml:28-29,76-78`; the effective inactivity interval of a login is the admin setting `session.timeout-minutes`, env `SESSION_TIMEOUT_MINUTES`, default 480, applied with `setMaxInactiveInterval` at login: `identity/AuthController.kt:129`, `identity/oidc/OidcLoginHandlers.kt:68`, `settings/Settings.kt:34`); the organization persistence flag (`app.organization.persistence-enabled`, env `ORGANIZATION_PERSISTENCE_ENABLED`, default false: `application.yml:133-135`); `app.workflow.approvals` default `auto`.

Still unverified:

1. **C6 / C7** have no formal definition in `OWNERSHIP.md` / `CLAUDE.md` (section 13 note). `OWNERSHIP.md` itself is older than the code (migrations to V25, roles C0-C5).
2. **Module role** of `integration.queue.workflow`, `data.org`, `access.adapters` was assigned from decision text (D-C0-32, V32 notes), not from one table.
3. **[`docs/API_CONTRACT.md`](API_CONTRACT.md)** (titled "API contract (v1)"), [`docs/SECURITY.md`](SECURITY.md), [`docs/AUTHENTICATION.md`](AUTHENTICATION.md) were not reconciled with the v2 contracts.
4. **Portal ports** `3301-3303` (local lifecycle) and `3201-3203 / 3210` (public) come from `docs/parallel/c0/PUBLIC_DEPLOYMENT_PINNING.md` and [`docs/PUBLIC_DEPLOYMENT.md`](PUBLIC_DEPLOYMENT.md), not from the scripts.
5. [`docs/PUBLIC_DEPLOYMENT.md`](PUBLIC_DEPLOYMENT.md) ("Anyone can sign up") and [`docs/IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md) ("sign-up closed") disagree on the pilot sign-up posture.
6. Feature, test and screen counts are intentionally not stated; no test was run in this session.
