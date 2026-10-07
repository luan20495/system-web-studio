# C1 — PUBLIC_SITE: confirmation of `published-runtime.md` §4, implementation map and handoffs

Owner C1 · baseline `integration/v2 @ ef0d890` (D-C0-35) · branch `fix/c1-public-site-v1` · 2026-10-07 · answers handoff **H-C1** (items 2–4).

**Verdict: `PUBLIC_SITE_CONTRACT = APPROVED`** (section 4 holds; the amendments below only make it stricter). **C1 CODE READY: YES. END-TO-END PUBLIC QUERY READY: NO — BLOCKED BY: C2/C0 allow-list source and the C0 route/adapter wiring.** Not production-ready.

## 1. What C1 confirms (and the three clarifications)

Confirmed unchanged: `PUBLIC_SITE` is a new `ActorKind`, **not** a USER, **not** SYSTEM / SERVICE / APP_TOKEN; attributes only `{tenantId, workspaceId, projectId, siteSlug, releaseId}` (+ the release's own `appVersionId`, which the policy reads from the release, it does not accept it); all server-derived; no user id, role, membership or permission set. The operation set is exactly `QUERY_EXECUTE` on a query the ACTIVE release lists as public, in LIVE mode. Public action / mutation / workflow, TEST, draft, raw DataGateway, schema discovery, cache refresh, sync, webhooks, events and every Management route are denied. No public token, site token, API key, Authorization header, fake USER or Studio-cookie forwarding in V1.

Clarifications C1 adds (they narrow, never widen):

1. **ACTIVE = `deployments.status = 'RUNNING'`** and it is `sites.current_deployment_id`. This is stricter than `SiteService.live`, which also serves `DEPLOYING` static files: a release that is still deploying does not serve data.
2. **`visibility = 'PUBLIC'` only.** A PRIVATE deployment's data route needs the site's own session (section 4, "Unpublished / out of scope"); that session is not modelled in V1, so a PRIVATE release answers 404 for PUBLIC_SITE. Nothing in C1 reads the Studio session for it.
3. **Status mapping:** there is **no 401** (nobody to authenticate) and **no 403** (it would reveal that a query exists but is not public). Every refusal of this policy is the same `404 QUERY_NOT_FOUND` / `"Query not found"`. `400 INVALID_REQUEST` (strict body), `429 RATE_LIMITED`, `422 DATA_SOURCE_UNBOUND` and `503` belong to C0 / C3 and must not be used to signal an authorization refusal.

`logic.action.ActorKind` (C4) deliberately has **no** `PUBLIC_SITE`: `wiring.ActorKinds.toLogic(PUBLIC_SITE)` throws and the adapters turn that into a denial, so a public call can never enter the action / workflow runtime (`ActorKindsTests` pins it).

## 2. What C1 implemented (all under C1-owned paths unless marked)

| File | Change |
|---|---|
| `tenancy/TenantContext.kt` | `ActorKind.PUBLIC_SITE` added; USER / SYSTEM / APP_TOKEN / SERVICE keep their names, order and meaning |
| `access/adapters/PublicSiteAuthorization.kt` (new) | `PublicSiteGatewayContext` (trusted input), `PublicSiteRequest`, `PublicSitePrincipal`, `PublicSiteDecision` (`Allowed` / `NotFound`), `PublicSiteResponses`, port `PublicQueryAllowList` + `DenyAllPublicQueryAllowList`, `PublicSiteAuthorizer` |
| `access/adapters/GatewayAuthorizer.kt` | PUBLIC_SITE branch: only `QUERY_EXECUTE`, needs workspace + project + the app version of the project's ACTIVE public release, no user id; every other operation and actor kind unchanged (SYSTEM / SERVICE / APP_TOKEN stay denied) |
| `identity/SecurityConfiguration.kt` | the `/sites/**` chain additionally permits exactly `POST /sites/*/_data/queries/*/run` |
| `test/.../tenancy/PublicSiteAuthorizationTests.kt` (new, 16 tests) | see section 5 |
| `test/.../wiring/ActorKindsTests.kt` (**C0-owned, minimal edit**) | the drift guard now says: the four shared names are identical in order, PUBLIC_SITE is the only extra and exists only in tenancy; converting it for logic throws |

No C2 / C3 / C4 code and no schema were touched. No migration was requested: the policy reads `sites`, `deployments`, `projects`, `workspaces`, `tenants` read-only.

## 3. How a public call is authorized (what C0 must call, in this order)

```text
POST /sites/{slug}/_data/queries/{queryId}/run                        (anonymous; the security chain has no session, no CSRF, no Studio filter)
 1. C0 controller: strict-parse the body (queryId from the PATH, params only; any authority-looking field -> 400 INVALID_REQUEST)
 2. C0/C2: SiteService.live(slug) -> project / workspace / release id; tenant from the workspace         (server derivation, never from the request)
 3. PublicSiteAuthorizer.authorize(PublicSiteRequest(PublicSiteGatewayContext(tenant, workspace, project, slug, release),
                                                      operation = "QUERY_EXECUTE", mode = "LIVE", queryId))
      -> NotFound(auditReason)  => throw decision.toException()        (404 QUERY_NOT_FOUND, identical for every cause; audit the reason, never return it)
      -> Allowed(principal)     => continue
 4. C0: GatewayContext(tenant = TenantContext(principal.tenantId, null), actorUserId = null, actorKind = ActorKind.PUBLIC_SITE,
                       workspaceId, projectId, appVersionId = principal.appVersionId.toString()) -> C3 DataGateway.runQuery   (QUERY_EXECUTE only)
      C3GatewayAuthorizerAdapter -> GatewayAuthorizer re-checks (actor PUBLIC_SITE, QUERY_EXECUTE, active release of that version)
 5. C0: audit with principal.auditAttributes(queryId); response `Cache-Control: no-store`
```

Inside [`PublicSiteAuthorizer.authorize`]: operation must be exactly `QUERY_EXECUTE` -> mode exactly `LIVE` -> query id shape (`^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$`) -> **the release is re-verified against the database on every call** (site slug -> project -> workspace -> ACTIVE tenant -> `current_deployment_id` = the given release, RUNNING, PUBLIC, of that project and workspace; archived / deleted project = no) -> if the adapter supplied an `appVersionId` it must equal the release's -> **the release allow-list must be non-empty and contain the query id**. The allow-list provider is asked only about the release that passed the previous check. Any exception is a refusal. After a rollback the old release id is refused and the restored one is allowed; after unpublish everything is refused (the pointer is read per request, no cache).

`GatewayAuthorizer` cannot see the query id (C3's `GatewayContext` has none), so its PUBLIC_SITE branch is only the second line of defence: **a PUBLIC_SITE gateway request is not enough on its own to name a query; step 3 must come first.**

## 4. The allow-list boundary (what exists, what is missing)

* `PublicQueryAllowList { publicQueryIds(tenantId, projectId, releaseId): Set<String> }` is the only thing C1 consumes. **No provider exists on `ef0d890`**: there is no immutable release allow-list in the AppDefinition or in the V27 publish configuration, and nothing is snapshotted into a deployment. C1 did not invent storage.
* Until a provider bean exists the policy uses `DenyAllPublicQueryAllowList`, so **every public query is refused** (proved by a test against the Spring-wired bean). Empty list = deny. A throwing provider = deny.
* **Handoff C2 (H-C2 item 5) / C0:** declare where the list lives and snapshot it into the release at publish time (V31 if it is a column or table; V30 is immutable). Constraints for the provider: derived by the server from the app's own definition, **immutable per release**, default empty, never a mutating or writable query, answers only for the release it is asked about, and the publish confirmation shows the author what becomes public. C0 then exposes it as a `PublicQueryAllowList` bean (no C1 change needed).
* **Handoff C0:** the controller and the principal-to-`GatewayContext` adapter (steps 1, 2, 4, 5 above), the `app.sites.public-data.enabled` switch (default false), strict request parsing, the Redis rate limit per site and IP, `no-store`, and recording the decision below in `DECISIONS.md` (C0 owns that file; C1 did not edit it).
* **Handoff C3 (H-C3):** `PublicQueryRequest` strict parsing, "the query is read-only for this call", and lower `maxRows` / `maxResponseBytes` for a public caller; C3 must reach only `runQuery` for `actorKind = PUBLIC_SITE`.

## 5. Tests (`PublicSiteAuthorizationTests`, 16, Spring + PostgreSQL, no mocks of the policy)

Actor and structure (no user id / role / permission field; the trusted context and the request have no slot for a header, token or actor kind; ids and kinds unchanged) · the USER model refuses PUBLIC_SITE (`AccessPort`, `PrincipalResolver`), even with a real user id attached · allowed: LIVE + active public release + listed query, with the release's own version · LIVE only (TEST, test, Live, DRAFT, whitespace ...) · inactive release (QUEUED ... ROLLED_BACK, PRIVATE, offline, archived and deleted project, suspended tenant, wrong slug) · pointer read per request (rollback / unpublish) · empty / missing / failing / Spring-wired (absent) allow-list = deny · unlisted query and malformed ids · foreign release query and the provider's arguments · QUERY_EXECUTE only (every other `GatewayOperation`, explicit MUTATION / DATASOURCE_MANAGE / SCHEMA_* / CACHE_REFRESH, unknown names) at both the public policy and C3's gateway port · gateway scope preconditions (workspace, project, version, foreign version, user id attached, release no longer active) · SYSTEM / SERVICE / APP_TOKEN denied for every operation with and without a user id (and the USER path unchanged) · foreign tenant / workspace / project / release / slug / nil ids = one identical 404 body, audit reasons are constants · HTTP edge: anonymous POST passes the chain with no session and no CSRF, exactly one path shape is opened (nine neighbouring POST shapes, PUT, `/api/v1/**` stay closed), forged tenant / workspace / project / release / role / actor-kind / token / cookie in headers or body change nothing, a Studio login neither unlocks nor blocks it, the sites chain has no `SecurityContextHolderFilter` / `CsrfFilter` / `ActiveUserFilter` while the Studio chain still does.

Mutation check (rules deliberately broken, each caught): remove the LIVE check (2 tests fail), drop `visibility = PUBLIC` (1), drop the allow-list check (3), widen the matcher to `POST /sites/**` (1), drop the gateway operation check (1).

## 6. Proposed `DECISIONS.md` text (for C0 to record)

> **D-C1-PUBLIC — PUBLIC_SITE confirmed.** `published-runtime.md` §4 is confirmed. `tenancy.ActorKind.PUBLIC_SITE` carries only server-derived `{tenantId, workspaceId, projectId, siteSlug, releaseId, appVersionId}`; it is not a USER and has no user id; `logic.action.ActorKind` has no such value. Allowed: `QUERY_EXECUTE` of a query the ACTIVE (`RUNNING`, `PUBLIC`) release lists, LIVE only; everything else is denied; SYSTEM / SERVICE / APP_TOKEN stay denied. Every refusal is `404 QUERY_NOT_FOUND`; no 401 / 403. The release allow-list is consumed through `PublicQueryAllowList` (default deny-all) and its source is C2 / C0's. Contract files to update: `tenant-context.md` (ActorKind), `permission-model.md` (PUBLIC_SITE row), `published-runtime.md` §4 (the three clarifications of section 1).

## 7. Limits

* Nothing here makes the public query work end to end: there is no controller, no allow-list source and no gateway adapter yet.
* The release is checked at the start of the request and again at the data gateway; a rollback between the two is caught by the second check, a change after it is not (same as the authenticated path).
* PRIVATE sites and non-same-origin embedding (a signed short-lived token) are V2.
* Rate limiting, response caps, CORS and `no-store` are C0 / C3 / nginx.
