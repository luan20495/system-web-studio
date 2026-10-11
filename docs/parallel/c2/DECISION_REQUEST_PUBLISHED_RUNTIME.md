> **SUPERSEDED_BY:** `docs/PUBLISH_RUNTIME.md` - historical document, kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

> **2026-10-07 update — superseded for V1 by D-C0-35.** C0 chose the same-origin ANONYMOUS public data route for PAGE_SCHEMA sites (`/{slug}/_data/…`, principal `PUBLIC_SITE`), not the capability-path / user-session design below. What C2 built for it: `HANDOFF_C3_PUBLIC_QUERY.md` and `HANDOFF_C5_PAGE_SCHEMA_DATA.md`. The question below (a published CODE app calling the Data Runtime with a user session) is **not the V1 path** and stays open and unimplemented; nothing here was built.

# DECISION REQUEST — how a published app reaches the Data Runtime (B-C2-B3-01)

Raised by C2 on 2026-10-07 at `fix/c2-v3 @ 8d40218`. **C2 decides nothing here**: the contract belongs to C0, identity / session / CSRF / CORS to C1, the runtime surface to C3. This file is C2's proposal (Option A) with every question the owners must answer, so they can approve, amend or reject it in one pass. Evidence: `PUBLISHED_RUNTIME_TOPOLOGY.md` §0 (the four facts that block the current design) and §9.

Hard boundary (from C0 / the owner, unchanged): the published runtime supports **only an authenticated USER session**. No public token, site token, deployment token, signed runtime token, API key, custom Authorization header, anonymous mode or proxy invented to unblock a demo. Anonymous / public runtime = NOT SUPPORTED unless C1 approves a new design.

## 1. C2's proposal — Option A: same-origin gateway path, member identified by the existing capability path (PROPOSAL, not frozen)

Why this shape: a PRIVATE code app is already authenticated this way today — after the ticket flow the browser sits at `{sitesOrigin}/_app/<token>/…`, the token maps (Redis, TTL `app.sites.session-hours` = 8 h) to `(userId, slug)`, membership is re-checked on every request (`SiteService.canRead`), cookies and `Authorization` never reach the app, and the sandbox + `connect-src 'self' <sitesOrigin>` stay exactly as they are. Option A only adds a **data** sub-path under the same capability path; the browser never holds a cookie, never talks to another origin, never sends an identifier it could forge.

| item | proposal |
|---|---|
| TOPOLOGY | same-origin gateway → Data Runtime. Browser (sandboxed document at `{sitesOrigin}/_app/<token>/…`) → `{sitesOrigin}/_app/<token>/data/<rest>` → nginx sites gateway → API `POST /sites/_app/<token>/data/<rest>` → **in-process** call of the same runtime logic as C0's `…/app-runtime/<rest>` (no second HTTP hop, no session) |
| `<rest>` | exactly the tails C0 froze in `runtime-api.md` and nothing else: `queries/{queryId}/run`, `actions/{actionId}/execute`, `workflows/{workflowId}/runs`, `workflow-runs/{runId}`, `workflow-runs/{runId}/cancel` |
| AUTH | the USER is the member the capability token was issued to (ticket flow: Studio USER session → single-use ticket → token). The API resolves token → `(userId, slug)`, slug → live site → `(projectId, workspaceId)`, re-checks membership, then applies C1's normal `AccessService.forProject` + permissions (`APP_USE`, `QUERY_EXECUTE`, `ACTION_EXECUTE`, …). Actor kind is always `USER`. `mode` is forced `LIVE`; a published page can never ask for `TEST` |
| COOKIE | none. `STUDIO_SESSION` and `site_session` are neither sent by the page nor accepted on this route; the gateway strips `Cookie` for this location |
| CSRF | the route is not cookie-authenticated, so cookie CSRF does not apply; the capability token is an unguessable (256-bit) bearer secret that lives only in the sandboxed document's URL (`Referrer-Policy: no-referrer`). **C1 must confirm** this is acceptable and whether to add: `Content-Type: application/json` required (forces a preflight), strict body parsing (C0 already rejects unknown fields) |
| Authorization header | never forwarded, never accepted on this route |
| CORS | the page has the opaque origin `null`, so the route answers like the existing `/api` proxy: `Access-Control-Allow-Origin: *`, **no** `Allow-Credentials`, preflight answered for `POST` with headers `Content-Type, Accept`. The Studio CORS list (exact origins, credentials) is untouched |
| CSP | **no change**: `sandbox allow-scripts; … connect-src 'self' <sitesOrigin>`, no `allow-same-origin`. `app.sites.origin` must equal the real public origin |
| GATEWAY ROUTE | `location ~ "^/_app/[A-Za-z0-9_-]{20,100}/data/.+$"` in `infra/sites-gateway/default.conf.template`: `POST` (and `GET` for run status) only; body limit and `limit_req` per C3's numbers; `rewrite ^/(.*)$ /sites/$1 break;` → `studio_api`; `proxy_no_cache`; **never forward** `Cookie`, `Authorization`, `X-XSRF-TOKEN`, `X-Factory-*`, `X-Gateway-Token`, any client-supplied identity header; forward only `Host`, `X-Forwarded-For`, `X-Forwarded-Proto`, `Content-Type`, `Accept`. No `/{slug}/data` route: a PUBLIC (anonymous) app has no data route (`404`) |
| DATA API BASE | for a PRIVATE code app the runtime config (`/_app/<token>/__factory/config.json`) would carry `apiBase = {sitesOrigin}/_app/<token>/data/` (computed per request from `app.sites.origin` and the token; no new secret — the page already has this URL). So `app.sites.data-api-base` is **not used** by this mode (C0 to decide: keep it as an override for other modes, or retire it). Open for C0 / C5: a relative `apiBase` (`./data/`) needs an `@company/app-sdk` change because `createApiClient` builds `new URL(path, base)` |
| DATA QUERY PATH | `POST {apiBase}queries/{queryId}/run` = `POST {sitesOrigin}/_app/<token>/data/queries/<queryId>/run`, body per C0 R1 `{"mode":"LIVE" (ignored/forced), "params":{…}, "page":{…}, "mappingRef":"…"}` |
| REQUIRED IDS | **browser sends only the LOCAL id** (`queryId` / `actionId` / `workflowId` / `runId`) and params. `workspaceId`, `projectId`, `tenantId`, `userId`, `dataSourceId`, `appVersionId` are all derived server-side (token → user, slug → project → workspace → tenant). No tenant / workspace / user identifier is added to the public config |
| RESPONSES | pass-through of C0's contract: R1 `200 {"queryId","mode","cache","result":{ViewModelData}}`; errors `{"code","message","requestId","retryable","details"}` with C0's statuses (`400 INVALID_REQUEST`, `403 FORBIDDEN`, `404 PROJECT_NOT_FOUND\|QUERY_NOT_FOUND`, `422 …`, `429 RATE_LIMITED`, `502/504`, `503 DATA_RUNTIME_UNAVAILABLE`); plus one route-level error proposed by C2: unknown / expired capability token → `401 SESSION_REQUIRED` JSON envelope (the page then asks the user to reopen the app from Studio); **C1 to confirm 401 vs the disclosure-safe 404 used by other `_app` routes** |
| ANONYMOUS | NOT SUPPORTED (PUBLIC apps have no member, hence no data route) |

Risks C2 sees, for the owners to weigh: (1) the capability token appears in nginx / API access logs (path) — logs must be treated as sensitive and the 8 h TTL / re-check stays; (2) a bearer-in-path is not a cookie: C1 must explicitly bless it for data calls (it already authorises `/api` calls into a user's server app); (3) C0's controllers (`wiring/AppRuntimeDataController`, `AppRuntimeActionController`) take `@AuthenticationPrincipal StudioUserDetails` and use only `me.userId`, so the logic needs a service entry that takes a `userId` (C0's files; C2 will not touch them); (4) no unauthenticated path to the runtime is created.

Alternatives, for completeness: **B** direct browser → API with credentialed CORS (needs `allow-same-origin` → weakens the sandbox for untrusted generated code, `SameSite=None` cookies, the sites origin in the CORS list, CSRF for a foreign origin, and still the SDK's `credentials: "omit"`): not recommended. **C** anonymous / token runtime: not supported.

## 2. Questions, by owner (answer each; "PENDING" until then)

**C0** — (1) freeze the topology: A / B / other; (2) is `app.sites.data-api-base` kept (override / other modes) or retired, and does it go into `application.yml` + `application-prod.yml` (required there) with `APP_SITES_DATA_API_BASE`; (3) LIM-1: approve the candidate flow of `RELEASE_ENVIRONMENTS.md` §1 (no migration; changes `DeploymentStatus.allowed`, the served set, adds `DeployProvider.verifyCandidate` + an internal signed candidate route); (4) STAGING / PRODUCTION site and data origins (today NOT CONFIGURED; `SITES_ORIGIN` / `STUDIO_ORIGIN` are not required by `application-prod.yml`); (5) who exposes a `userId`-based entry to the runtime logic (C0 wiring) and the in-process contract.
**C1** — (1) is the capability-path member (token → userId, membership re-checked per request) an accepted way to act as a USER for the Data Runtime, and which permissions apply; (2) CSRF model for a token-in-path route (§1 CSRF); (3) cookie / session policy across the site and API origins (C2's position: no Studio or site cookie is ever sent to the runtime); (4) 401 vs 404 for an invalid token; (5) **confirm in writing that anonymous / public runtime stays unsupported**.
**C3** — (1) exact runtime surface a published page may use (the C0 R1–R3 tails only?); (2) request limits (body size, rate) for the gateway; (3) the identifiers C3 needs besides `queryId` (C2's claim: none); (4) query execution contract for a published app: LIVE only, `APP_USE` + `QUERY_EXECUTE`, binding must be LIVE.
**C5** — the SDK: use `apiBase` from the config, `credentials: "omit"` stays (Option A needs no credentials), relative base support, error handling for `SESSION_REQUIRED`.

## 3. What C2 will do once frozen (and only that)

C2 owns `infra/sites-gateway/**` route and the `/sites/_app/{token}/data/**` handler in `publish/SiteControllers.kt`, the runtime-config `apiBase` for that mode, and the CSP: for Option A the handler + nginx location + config value + tests (token resolution, membership re-check, cookie / Authorization never forwarded, PUBLIC app → 404, forced LIVE, no id from the browser). C2 will not touch C0's controllers, C1's access code, C3's runtime or the SDK.

## 4. Status

| | |
|---|---|
| READY FOR C2 | **NO** — blocked on the decisions above (not on C2 work) |
| READY FOR C5 | publish / rollback / unpublish / SiteInfo: **YES** (`PUBLISH_API_CONTRACT.md`); published-app data calls: **NO** |
| READY FOR C6 | publish, rollback, unpublish, runtime config, concurrency: **YES**; published-app → data E2E: **NO** (blocked) |
