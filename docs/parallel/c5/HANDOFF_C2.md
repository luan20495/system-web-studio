# C5 → C2 handoff — C2 — build / publish / deploy / artifact

From C5 (Studio/Frontend), baseline `integration/v2 @ f894cc6`. C5 changed nothing owned by C2. Nothing here was run against a live backend: evidence is the code, the BLOCKERS rows and the suite's blocker records.

## H-C2-01 (B-C5-06)

- **ID:** H-C2-01 (B-C5-06)
- **Owner:** C2
- **Severity:** P1
- **Flow:** Published app runs queries/actions
- **Frontend expectation:** A published (LIVE) app can call the data runtime.
- **Actual backend behavior:** The published app has no data runtime host: `workers/render` only takes `{schema, assets}`, so nothing can call `…/app-runtime/*` with LIVE mode from the public page.
- **Endpoint/event:** publish pipeline → render worker
- **Request:** publish a project that has queries
- **Response/status:** static page without data
- **Reproduction:** Publish a project with a bound query; the public page cannot fetch rows.
- **Evidence:** BLOCKERS B-C5-06; `e2e-08` blocker.
- **Impact:** LIVE query/mutation cannot be shown to an end user.
- **Suggested contract/fix:** Define how the published artifact reaches the runtime (host/proxy + session/token model).
- **C5 workaround:** Studio Test panel is TEST-only; LIVE flows reported BLOCKED.
- **Blocked test IDs:** E2E-08, E2E-09

## H-C2-02 — no operation to declare a data slot (`dataSources[]`)

- **ID:** H-C2-02
- **Owner:** C2 / C0 (contract decision first; C5 does not edit `docs/contracts/**`)
- **Severity:** P2
- **Flow:** Configure DataSource/Query in Studio, bind a source to a slot
- **Frontend expectation:** the Studio can declare a slot, bind it (TEST/LIVE) and define a query that references it.
- **Actual behavior (AppDefinition V2):** the typed operations are `ADD|UPDATE|REMOVE_` × {VIEW_MODEL, QUERY, MAPPING, DATA_BINDING, ACTION, WORKFLOW_REF, PERMISSION_REF} plus `UPDATE_THEME`, `UPDATE_PUBLISH_CONFIG`. There is none for `dataSources[]` ("granted, not created"), while a query must reference a `dataSourceRef` that exists in `dataSources[]` (validated in `definition.ts`).
- **Reproduction:** open any project made in Studio: the Data panel lists no slots; "Khe dữ liệu" says none are declared and nothing can be bound.
- **Impact:** C3's binding routes (`PUT …/data-bindings/{mode}/{slotId}`) can only be used for a slot some other tool wrote into the document. E2E-06 (query half) and E2E-07 need an operator-seeded project.
- **Question to C2/C0:** where are slots authored — a new typed op (`ADD_DATA_SOURCE_SLOT`…), or by the management API? C5 will not invent a slot client-side.
- **C5 workaround:** the UI says honestly that no slot is declared (`no-slots`, `slot-empty`) and shows the bind controls only for declared slots.
- **Blocked test IDs:** E2E-06 (query half), E2E-07 (unless pre-seeded), E2E-08, E2E-09

## H-C2-03 — runtime-config contract: frontend support confirmed (no request)

C5 supports the C2 proposal on the client side: a published app loads `GET /runtime-config.json` = `{DATA_API_BASE_URL, ENVIRONMENT?, RELEASE_ID?, VERSION?}` **before** any Data API client is created (`packages/api-client/src/runtimeConfig.ts`; `loadRuntimeConfig`, `initDataApi`, `dataApiUrl`, `describeRuntimeConfigError`, `renderRuntimeConfigFailure`). Fail-closed: https required (http only for loopback in development), loopback refused in production, no silent default, a visible error otherwise; development mode may use an explicit `devFallback` only when the file is unreachable/404. Unit-tested (`tests/builder/runtimeconfig.test.ts`). **C5 is not asking C2 to choose a production host**: that is C0's topology decision (H-C0-05). C5 has not wired the loader into `workers/render` / the published page because that artifact is C2's; the module is ready to import.

## H-C2-04 — a statically published site does not serve `/runtime-config.json` (live evidence, 2026-10-06)

- **ID:** H-C2-04
- **Owner:** C2 (C0 topology)
- **Severity:** P1 for E2E-08/09, info otherwise
- **Flow:** E2E-13 (PASS) → E2E-08/09
- **Frontend expectation:** the published page loads `GET /runtime-config.json` next to the artifact (`{DATA_API_BASE_URL, ENVIRONMENT, RELEASE_ID, VERSION}`) and fails closed otherwise.
- **Actual backend behavior:** with `DEPLOY_PROVIDER=static`, publish works end to end (RUNNING, `mock=false`, anonymous visitor 200 with the saved content) but `GET <site>/runtime-config.json` answers **404**.
- **Endpoint/event:** `GET <deployment.url>runtime-config.json`
- **Reproduction:** `E2E_ONLY=E2E-02,E2E-13 npm run test:e2e:real`; the run prints `[fact] published site GET runtime-config.json → 404` and records it in the report (`fixtureNotes.publishedRuntimeConfig`).
- **Impact:** a published app cannot learn its Data API host; the frontend loader is ready and tested (11 unit tests) but has nothing to load.
- **Suggested contract/fix:** C2/C0 decide who writes the file at publish time and which Data API host/CORS/cookie model it names.
- **Blocked test IDs:** E2E-08, E2E-09

## H-C2-04 — CORRECTION 2026-10-07 (supersedes the first text)

The first text said a statically published site "does not serve `/runtime-config.json`" (404). That path is C2's **proposal**, not a contract. C0's handoff names a different file: `__factory/config.json` with `apiBase` (null until a data host exists), produced by `SiteService.runtimeConfig` and served for **code apps and previews only** (`SiteControllers.kt:81,92,130`). A page-schema site answers 404 for both paths, which is by design today.

- **What C2/C0 must decide (one contract):** `GET <site>/__factory/config.json → {apiBase}` (existing, code apps) vs `GET /runtime-config.json → {DATA_API_BASE_URL, ENVIRONMENT, RELEASE_ID, VERSION}` (what C5's loader and unit tests implement). C5 will align its loader to whichever is chosen; until then neither is served for page-schema sites. Evidence: every E2E-13 run prints both probes (`[fact] published site GET … → 404`) and stores them in the report (`fixtureNotes.publishedRuntimeConfig`).
- **Blocked test IDs:** E2E-08, 09

## H-C2-05 — published page: ETag present, conditional GET not honoured (P3)

`GET <site>/<slug>/` answers `200` with `Cache-Control: public, no-cache, no-transform` and an `ETag`, but a repeat request with `If-None-Match: <that ETag>` answers `200` again, not `304`. Revalidation therefore re-downloads the page. Not a contract promise (so not a failing check); recorded as a fact in every E2E-13 run (`fixtureNotes.publicPageCache`).
