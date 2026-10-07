# C2 → C3 (and C0, C1) — what C2 delivers and expects for the Public Data Runtime (published-runtime.md §4, D-C0-35)

As built on `fix/c2-v3` (2026-10-07). C2 owns the published path, the gateway routing, the runtime config `apiBase` and the release context; C3 owns query execution, binding and LIVE semantics; C1 owns the security policy; C0 owns the controller and the principal adapter. **C2 changed no C1 / C3 / C0 code and invented no auth.**

## PUBLIC QUERY ROUTE
Browser (the page runtime, `workers/render/page-runtime.ts`): `POST {sitesOrigin}/{slug}/_data/queries/{queryId}/run`, `Content-Type: application/json`, body exactly `{"params":{}}` today (the runtime has no parameter source yet), `credentials: "omit"`.
Gateway (`infra/sites-gateway/default.conf.template`, verified on a real nginx by `tests/gateway/data-route.mjs`): only POST, only this path shape (`slug` = `^[a-z0-9][a-z0-9-]{1,79}$`, `queryId` = `^[a-z0-9][a-z0-9-]{0,63}$`, literal `/run`), body ≤ 16 KiB, 10 r/s per address with burst 20 (429), query string dropped, `Cookie` / `Authorization` / `X-XSRF-TOKEN` removed, forwards `Host`, `X-Forwarded-For`, `X-Forwarded-Proto`, `Content-Type`, `Accept`. It reaches the API as `POST /sites/{slug}/_data/queries/{queryId}/run`.
**Missing (blocks the whole path): an API handler for that path.** Needed from C0 / C1: the `SecurityConfiguration` sites chain currently ends in `anyRequest().denyAll()`, so a `permitAll` for exactly `POST /sites/*/_data/queries/*/run` (no CSRF, no CORS on that chain), the controller mounted behind `app.data-platform.enabled` + `app.sites.public-data.enabled`, and the `PUBLIC_SITE` actor kind (C1 has not confirmed it; `published-runtime.md` says so itself).

## QUERY ID
The LOCAL id of a query definition (`queries[].id`). Never a runtime UUID. The page only ever names ids that were resolved at publish time from its own release snapshot, and the server checks them again (below).

## RELEASE ID
Never sent by the browser, never trusted from it. The server derives the release from the slug on every request: **`PublishedRelease.active(slug): ActiveRelease?`** (`publish/PublishedRelease.kt`) = `{slug, tenantId, workspaceId, projectId, deploymentId, versionId, versionNumber, visibility, publicQueries}` — the pointer's release, same rule as what the gateway serves (`DEPLOYING` / `RUNNING`, active project). Server-side only; the object is never serialised to a browser.

## REQUIRED SERVER DERIVATION
slug → site → active release → project → workspace → tenant, all from `PublishedRelease`. Principal `PUBLIC_SITE{tenant, workspace, project, release}`; nothing from the request. Strict parsing: only `params`; any other field (`tenantId`, `workspaceId`, `release`, `mode`, `mappingRef`, `sql`, …) → `400 INVALID_REQUEST` (C0's rule).

## ALLOW-LIST CHECK LOCATION
**`PublishedRelease.resolve(slug, queryId): ActiveRelease?`** — C2 provides it; the controller must call it BEFORE anything of C3's and answer ONE uniform `404 QUERY_NOT_FOUND` for every null (unknown slug, offline site, archived / deleted project, a query not in the active release's list, a query that exists only in the draft, a query made public after the release). The list is `PublicQueries.of(<the version snapshot the release names>)` = the queries with `public: true` and mode READ — immutable per release (a release names a version, a version never changes), restored by a rollback, absent after an unpublish. `queries[].public` is a plain boolean in the AppDefinition; C3's own check "the source is read-only for this call" still applies on top (a WRITE query can never be public: the validator refuses it).

## LIVE MODE
Always LIVE, resolved through the application's LIVE binding of the query's slot (`dataSourceRef` → slot → Management API `data-bindings`); no TEST, no draft, no fallback; unbound → `422 DATA_SOURCE_UNBOUND`; a source of another workspace → 404 (already enforced by the binding writer). C2 asserts nothing about bindings.

## WHAT THE PAGE RUNTIME CONSUMES (response contract)
`200 {"queryId","mode":"LIVE","cache","result":{"viewModelId","cardinality","fields","rows":[{…}],"truncated","warnings","skippedRows"}}` — the runtime requires `result.rows` to be an array of objects, reads values by field name (a scalar binding takes the field named like the prop, else the only field), writes text only. Statuses mapped by the runtime: 401/403 → `forbidden`, 404 → `not-found`, 429 → `rate-limited`, ≥ 500 → `unavailable`, other non-2xx → `http-NNN`, 2xx without `result.rows` → `invalid-response`. Lower public `maxRows` / `maxResponseBytes` caps are C3's numbers (the runtime itself caps a list at 200 rows).

## QUESTIONS FOR C3 / C0
(1) Mapping resolution for the public route: C0's R1 resolves "the single mapping whose `queryRef == queryId`; zero or several → `422 MAPPING_REF_REQUIRED`". The runtime sends no `mappingRef` (the frozen public request has none), so a public query needs exactly one mapping to work. Same rule for the public route? (C2 does NOT enforce it at publish; say if it should.) (2) Where is the response-size / row cap for PUBLIC_SITE set. (3) Does the public route share the authenticated route's query-result cache key space (it must not leak across principals).
