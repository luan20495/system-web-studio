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

