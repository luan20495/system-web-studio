# C5 → C3 handoff — C3 — data / database / connector

From C5 (Studio/Frontend), baseline `integration/v2 @ f894cc6`. C5 changed nothing owned by C3. Nothing here was run against a live backend: evidence is the code, the BLOCKERS rows and the suite's blocker records.

## H-C3-01 (B-C0-W-04)

- **ID:** H-C3-01 (B-C0-W-04)
- **Owner:** C3
- **Severity:** P1
- **Flow:** Mutation with a real side effect
- **Frontend expectation:** A LIVE `CREATE_RECORD` action writes a row.
- **Actual backend behavior:** Production connectors (postgres, rest) are read-only: LIVE data mutations answer `MUTATION_UNSUPPORTED`.
- **Endpoint/event:** `POST …/app-runtime/actions/{id}/execute` (LIVE)
- **Request:** `{mode:"LIVE", idempotencyKey}`
- **Response/status:** `status:"FAILED"`, `error.code:"MUTATION_UNSUPPORTED"`
- **Reproduction:** Execute a mutating action against a postgres/rest source in LIVE.
- **Evidence:** BLOCKERS B-C0-W-04; `e2e-09` blocker.
- **Impact:** No mutation success path exists to test.
- **Suggested contract/fix:** Implement write support in at least one production connector, behind DATA_MUTATE.
- **C5 workaround:** TEST mode returns `WOULD_RUN`, shown as such (E2E-S1).
- **Blocked test IDs:** E2E-09

