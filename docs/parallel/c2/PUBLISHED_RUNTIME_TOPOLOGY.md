# C2 — what a published app can actually reach: topology, runtime config, auth boundary (for C3, C0, C1)

Audited at `fix/c2-v3 @ 1ab26f9` + audit commits of 2026-10-07; services checked on the Mac on 2026-10-07 with read-only `GET`s on localhost. Facts are marked **[code]**, **[test]** (pinned by `PublishApiContractTests`), **[running]** (observed) or **[config]**. Nothing was invented; where something does not exist it says so.

## 0. Result in one paragraph (read this first)

**A browser page served by the sites gateway CANNOT today call the Data Runtime (`…/app-runtime/…`) with the user's session.** Four independent facts, each from code, stop it:
1. a published code app is served with `Content-Security-Policy: sandbox allow-scripts; … connect-src 'self' <sites origin>` — **no `allow-same-origin`** — so the document has an opaque origin and the browser refuses any `fetch` to another origin, including the API origin [test];
2. `@company/app-sdk` calls the API with `credentials: "omit"` and its own doc says the app has "no cookies or storage" [code: `packages/app-sdk/src/index.tsx`];
3. the Data Runtime authenticates with the Studio session cookie `STUDIO_SESSION` (`SameSite=Lax`) **plus** `X-XSRF-TOKEN`, and its CORS allows only the exact origins of `app.cors.allowed-origins` with credentials; origin `"null"` is rejected by `requireExactOrigins`; the sites origin is not in the default list [code: `identity/SecurityConfiguration.kt`];
4. the sites gateway has **no route** to the Data Runtime: nginx proxies only `GET/HEAD` of slug paths, the website form `POST`, and `/{slug}/api/**` — which goes to the app's OWN server runtime, never to `app-runtime` [config: `infra/sites-gateway/default.conf.template`].
`apiBase` (`app.sites.data-api-base`) is delivered correctly to the page, but a page can only use it if it equals the sites origin itself (CSP). Making the "browser → published site → apiBase → app-runtime with a USER session" flow work needs a **C0 + C1 + C3 contract** (and a CSP / gateway change in C2 once that contract exists). C2 did not invent a token, header, proxy, anonymous mode or CSP relaxation. See §9.

## 1. Origins

| | LOCAL (this Mac) | STAGING | PRODUCTION |
|---|---|---|---|
| backend API origin | `http://127.0.0.1:8080` [config: `SERVER_ADDRESS`/`server.port`]; **not running at audit time** [running] | **NOT CONFIGURED** (no `application-staging.yml`) | **NOT CONFIGURED** — `application-prod.yml` exists but defines no sites key; `SITES_ORIGIN` / `STUDIO_ORIGIN` are not required there (C0 hygiene note H1) |
| sites gateway origin = published-site origin | `http://127.0.0.1:18088` (container `hbl-sites-gateway-1`, nginx, host port `SITES_GATEWAY_PORT`, default 18088) — **running and healthy**, upstream `host.docker.internal:8080` is down so pages answer 502 [running] | NOT CONFIGURED | NOT CONFIGURED |
| published URL of one site | `{SITES_ORIGIN}/{slug}/` — protocol `http`, host `127.0.0.1`, port `18088`, path `/{slug}/` [code: `SiteService.url`] | – | – |
| Studio origin (private-site visitors are sent here to sign in) | `http://localhost:3100` default (`STUDIO_ORIGIN`); not running [running] | – | – |
| "public" demo profile | `compose.public.yml`: gateway `127.0.0.1:28088` (running, healthy); `.run/public/public.env` names `SITES_HOST=sites.toolsmcp.uk`, `PUBLIC_HOST=studio.toolsmcp.uk`; its API (`18081`) and the tunnel process are **not running** [running] → **not reachable now** | | |

Three origins are different things: **backend origin** (API, session, `app-runtime`), **site gateway origin** (nginx, GET-only static serving + the `/api` proxy of server apps), **published site origin** = the gateway origin (a site is a path `/{slug}/` under it, there is no per-site host except verified custom domains for PUBLIC websites, which are never code apps).

## 2. Site gateway

* Component: nginx container from `infra/sites-gateway/default.conf.template`; port in the container `8080`; host port `SITES_GATEWAY_PORT` (18088 local, 28088 public profile); `SITES_HOST` / `API_UPSTREAM` are template variables. There is no other gateway for browsers (`apps-gateway` on 18090 is container → API for server apps' connectors, not browser facing).
* Routing: `GET/HEAD /{slug}/**`, `/_access`, `/_preview/**`, `/_app/**` → upstream `GET /sites/…`; `POST /{slug}/_forms/{id}` (≤ 16 KiB, 6 / min); `ANY /{slug}/api/**` and `/_app/<token>/api/**` (≤ 1 MiB) → upstream `/sites/…/api/**` (server-app runtime); everything else `404 not found`. Fallback / index routing is done by the API: `""` → `index.html`, `dir/` → `dir/index.html`, `dir` → `301 dir/`, unknown → the site's `404.html`.
* Public artifact serving: the API reads the verified bytes from the artifact store (each served file is hashed against the manifest; a mismatch is not served) and sets `Cache-Control: public, no-cache, no-transform` (pages), `public, max-age=31536000, immutable` (`assets/` of websites), `private, no-store` (private).
* Runtime config serving: the same route family, file name `__factory/config.json` (§4). Health: `GET /healthz` → `200 ok` [running].
* AUTH at the gateway: **none**; it is an anonymous reverse proxy. The API decides: PUBLIC → anonymous; PRIVATE → cookie `site_session` (host-only on the sites origin, `HttpOnly; SameSite=Lax; Secure` if `SITES_COOKIE_SECURE`) → redirect to the capability path `/_app/<token>/` for code apps. `/sites/**` has its own filter chain: CORS and CSRF **disabled**, `GET/HEAD` permitAll, `/sites/*/api/**` permitAll (the route table of the app decides).

## 3. `app.sites.data-api-base`

| | |
|---|---|
| Key / default | `app.sites.data-api-base` / empty = not configured → `apiBase: null` |
| Env override | Spring relaxed binding: `APP_SITES_DATA_API_BASE` |
| Declared in | **code default only** (`@Value("${app.sites.data-api-base:}")`, `publish/SiteService.kt`); **not** in `application.yml`; C0 may add it with an env mapping (C0 hygiene task L-2/L-4) |
| Read | **once, at API process start** (constructor of `SiteService`) → a change needs a restart. Not per request, not per deployment, not at build time; it is never in an artifact |
| Validation | `SiteService.resolveDataApiBase`: absolute `http`/`https` URL with a host, no user-info, no fragment; anything else → `null` (no address is invented) |

(An earlier hand-off note said "read at request time"; that was wrong and is corrected: the value is fixed per process, the response is built per request.)

## 4. Public config — where the browser gets `apiBase`

* **Endpoint** [code, test]: `GET {sitesOrigin}/{slug}/__factory/config.json` (PUBLIC code app); `GET {sitesOrigin}/_app/{token}/__factory/config.json` (PRIVATE code app, after the ticket flow); `GET {sitesOrigin}/_preview/{token}/__factory/config.json` (preview). **Only code apps** (`artifacts.kind = STATIC_APP`) have it; a page-schema website has no runtime config (the same path is just a missing file). There is **no** `/runtime-config.json` route.
* Headers [test]: `Content-Type: application/json`, `Cache-Control: no-store, no-transform`, `Access-Control-Allow-Origin: *` (no credentials), `Content-Security-Policy` (site CSP), `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Cross-Origin-Opener-Policy: same-origin`.
* JSON [test] — exact key set, none omitted:

| field | type | nullable | source |
|---|---|---|---|
| `appId` | string (UUID) | no | `projects.id` (the site key is the project id) |
| `appName` | string | no | `projects.name` |
| `environment` | `"production"` \| `"preview"` | no | fixed per route (lower case: the vocabulary of `@company/app-sdk`; the scope's `PRODUCTION` is internal) |
| `visibility` | `"PUBLIC"` \| `"PRIVATE"` | no | the served deployment's visibility |
| `version` | string (version number) | **yes** | the version of the **served release** (fixed 2026-10-07: it used to be the project's latest version); preview → latest |
| `user` | `{"displayName":string}` | **yes** | the signed-in member on a PRIVATE app; `null` on a PUBLIC app. No user id |
| `flags` | object of booleans | no | always `{}` today |
| `apiBase` | string | **yes** | `app.sites.data-api-base` (§3) |
| `releaseId` | string (UUID) | **yes** | the served deployment id; `null` for preview |
| `generatedAt` | instant | no | per request |
No secret, token, tenant id, workspace id or credential is in this document and none may be added (test: the body contains none of `token`, `secret`, `password`, `Authorization`).

## 5. Proxy mode for the Data Runtime: **NONE** (PROXY_MODE: C-not-implemented)

* Browser → `apiBase` + runtime path **directly** is the only conceptual mode today, and it is blocked by §0 (CSP, opaque origin, `credentials: "omit"`, CORS).
* The one same-origin proxy that exists serves the app's own **server runtime**, not the Data Runtime: SOURCE `^/(_app/<token>|<slug>)/api(/.*)?$` on the gateway → TARGET API `/sites/…/api/**` → `ServerRuntime` gateway → the app container. Methods: all; query: passed; body ≤ 1 MiB; headers forwarded to the app: `Accept`, `Content-Type`, an internal `X-Gateway-Token`, and for a PRIVATE app `X-Factory-User` + `X-Factory-Signature` (HMAC with the app's own token); **cookies and `Authorization` never reach the app**; CSRF: not applicable (disabled on `/sites/**`); CORS: `ACAO: *`, no credentials, preflight answered by the gateway (`GET, POST, PUT, PATCH, DELETE`; headers `Content-Type, Accept`).

## 6. Cookie / CSRF / CORS today

* Studio session cookie `STUDIO_SESSION`, `SameSite=Lax`, `Secure` unless `COOKIE_SECURE=false`; CSRF cookie `XSRF-TOKEN` (not HttpOnly) with header `X-XSRF-TOKEN` required on every non-GET (`GET /api/v1/auth/csrf` returns it).
* Studio CORS (`/api/**`): exact origins of `app.cors.allowed-origins` (default `http://localhost:3000,http://127.0.0.1:3000`), credentials allowed, headers `Content-Type, X-XSRF-TOKEN, X-Request-Id, Idempotency-Key`; never `*`, never `null`.
* Sites: cookie `site_session` is host-only on the sites origin and authenticates **only** the sites API (`SiteService.sessionUser`); it is not accepted by `/api/**`.
* **C1/C3 CONTRACT REQUIRED** for anything that lets a published page present a user identity to the Data Runtime.

## 7. Identifiers

| id | available to C2 server | available to browser (published page) | source | stable across rollback |
|---|---|---|---|---|
| tenantId | yes (`projects.tenant_id`) | **no — must not be exposed** | derived from the project | yes |
| workspaceId | yes (`projects.workspace_id`, `LiveSite.workspaceId`) | **no** (not in the config; the Studio knows it) | project | yes |
| projectId = appId | yes | **yes** (`appId`) | `sites.project_id` | yes |
| siteId | = projectId (no separate id); public handle is the `slug` | slug (it is in the URL) | `sites.slug` | yes |
| deploymentId = releaseId | yes | **yes** (`releaseId`) | `sites.current_deployment_id` | **changes** (the restored deployment) |
| versionId | yes (`deployments.version_id`) | no | deployment | changes |
| version number | yes | yes (`version`, now the served one) | deployment → version | changes |
| pointerVersion | yes | no (Studio `SiteInfo` only) | `sites.pointer_version` | +1 on every change |
| environment | yes (scope `PRODUCTION`) | yes (`"production"`) | fixed | yes |
| lease / fencing token / operation id | yes | **no — must not be exposed** | `sites.lease_*` | – |
| artifact id / sha / storage prefix | yes | no | artifacts | changes |

Classification for the Data Runtime (browser should send the fewest ids): **REQUIRED IN PUBLIC CONFIG** `apiBase` and `appId` (+ `releaseId` for cache keys); `workspaceId` only if C0 keeps the workspace-scoped route for browser calls (a C0/C1 decision; it is not an authority field); **DERIVED SERVER-SIDE** tenantId, userId, dataSourceId, appVersionId, permissions; **MUST NOT EXPOSE** tenantId, userId, credentials, tokens, lease / fence / operation ids, storage prefixes, artifact hashes; **NOT NEEDED** pointerVersion, versionId, siteId.

## 8. What rollback and unpublish change in the runtime config [test]

| field | rollback to an older release | unpublish |
|---|---|---|
| `apiBase` | **unchanged** (environment-level, fixed per process) | n/a |
| `appId`, `appName`, `environment` | unchanged | n/a |
| `visibility` | recomputed from the restored deployment | n/a |
| `releaseId` | **changes** to the restored deployment | n/a |
| `version` | recomputed (the restored release's version) | n/a |
| `generatedAt` | per request | n/a |
| active pointer | `current_deployment_id` = restored; `pointer_version` +1; the newer release becomes `ROLLED_BACK` | `current_deployment_id = NULL`; `pointer_version` +1 (not again if already offline) |
Unpublish: runtime config and every file answer **`404` HTML** (the site's own "gỡ xuống" page, `Cache-Control: no-store`); the slug is kept, so a later rollback serves the older release again with no rebuild.

## 9. The flow C2 supports today, and where it stops

| # | step | owner | method / URL | auth | expected |
|---|---|---|---|---|---|
| 1 | sign in, get CSRF | C1/C5 | `POST /api/v1/auth/login`; `GET /api/v1/auth/csrf` | – → session | `200`, cookie `STUDIO_SESSION`, `X-XSRF-TOKEN` token |
| 2 | publish | C2 | `POST /api/v1/workspaces/{w}/projects/{p}/publish` | USER session + CSRF, `Idempotency-Key` | `202` deployment `QUEUED` |
| 3 | poll | C2 | `GET …/deployments/{id}` | USER session | until `RUNNING` (or `FAILED` with `error`) |
| 4 | site info | C2 | `GET …/site` | USER session | `online:true`, `url = {sitesOrigin}/{slug}/`, `operation:null` |
| 5a | open a PUBLIC code app | C2 | `GET {sitesOrigin}/{slug}/` | anonymous | `200`, CSP `sandbox allow-scripts … connect-src 'self' {sitesOrigin}` |
| 5b | open a PRIVATE code app | C2 | Studio `POST /api/v1/sites/{slug}/access-ticket` → `{redirect}`; browser `GET {sitesOrigin}/_access?ticket=…` → `302 Set-Cookie site_session` → `GET /{slug}/` → `302 /_app/<token>/` → `200` | USER session (ticket), then `site_session`, then the capability token | the page, as that member |
| 6 | runtime config | C2 | `GET {sitesOrigin}/{slug}/__factory/config.json` (public) / `…/_app/<token>/__factory/config.json` (private) | as 5 | `200` JSON of §4 |
| 7 | resolve `apiBase` | browser / SDK | `config.apiBase` | – | string or `null` |
| **8** | **call the Data Runtime** | **C3 / C0** | `POST {apiBase}/api/v1/workspaces/{w}/projects/{p}/app-runtime/queries/<C3_QUERY_ID>/run` (C0's frozen path, runtime-api.md; not a C2 route) | **USER session + CSRF** | **BLOCKED by §0** from a published page |
| 9–10 | real data returned / rendered | C3, C5 | – | – | depends on 8 |
| 11 | rollback | C2 | `POST …/site/rollback` `{"deploymentId":"<older>"}` | USER session + CSRF | `200` SiteInfo, `currentDeploymentId=<older>` |
| 12–13 | reload; older release active | C2 | step 6 again | as 5 | `releaseId = <older>`, same `apiBase`, same `appId` |
| **14** | Data Runtime again | C3 / C0 | as step 8 | USER session + CSRF | **BLOCKED** like step 8 |

Steps 1–7 and 11–13 are verified by tests. Steps 8–10 and 14 are not implementable inside C2 without a contract: the three decisions below are for **C0 (owner of the contract), C1 (identity / session / CSRF / CORS) and C3 (what the runtime accepts)**; C2 will then change only the CSP and gateway routes it owns.

| option | what it needs | C2's view |
|---|---|---|
| **A. same-origin capability path** (`/_app/<token>/…` already authenticates a member for the app's server runtime; the gateway would call the Data Runtime server-side AS that member) | a C0/C1 contract (the member is a USER with C1 permissions; no cookie leaves the sites origin; no header minted for the browser), a C3-callable server-side entry, a gateway route in `infra/sites-gateway` + `SiteControllers` | keeps the sandbox and CSP (`connect-src 'self'`) intact; consistent with how private apps already work; **recommended for a decision** |
| B. relax the sandbox (`allow-same-origin`) and use credentialed CORS to the API | C1: CORS origin list, cookie `SameSite=None`, CSRF handling for a foreign origin | weakens the isolation of untrusted generated code; not recommended |
| C. anonymous / public runtime (token, API key, signed runtime token) | **not supported**; C0 + C1 + C3 design | out of scope; do not build |
