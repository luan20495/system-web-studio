> **SUPERSEDED_BY:** `docs/QA_FINAL.md` - historical document, kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# C5 → C4 handoff — C4 — action / workflow / RabbitMQ

From C5 (Studio/Frontend), baseline `integration/v2 @ f894cc6`. C5 changed nothing owned by C4. Nothing here was run against a live backend: evidence is the code, the BLOCKERS rows and the suite's blocker records.

## H-C4-01 (B-C4-05 / B-C4-06)

- **ID:** H-C4-01 (B-C4-05 / B-C4-06)
- **Owner:** C4 (C0 wiring)
- **Severity:** P1
- **Flow:** RabbitMQ outage and durable workflow
- **Frontend expectation:** Workflow starts depend on a queue whose outage yields a clear failure and recovers; runs survive a restart.
- **Actual backend behavior:** C4's workflow queue and run stores are in memory; no RabbitMQ consumer/adapter is wired on f894cc6.
- **Endpoint/event:** `POST …/app-runtime/workflows/{id}/runs`
- **Request:** `{mode, idempotencyKey}`
- **Response/status:** 202 + `runId` (TEST) / 503 `RUNTIME_STORES_VOLATILE` (LIVE)
- **Reproduction:** Stop RabbitMQ (none is used): nothing changes; restart the backend: runs are gone.
- **Evidence:** BLOCKERS B-C4-05, B-C4-06; `e2e-12`, `e2e-14` blocker text.
- **Impact:** No outage/recovery or durability behaviour can be asserted.
- **Suggested contract/fix:** Wire the queue adapter + V29 run stores; publish the outage error codes in the contract.
- **C5 workaround:** UI handles 503/network/timeouts generically with retry and reconnect; flows BLOCKED with hooks ready (`E2E_*_RABBIT_CMD`, `E2E_RESTART_BACKEND_CMD`).
- **Blocked test IDs:** E2E-12, E2E-14

## No new request (this round)

C4 reported the AMQP adapter coded, durable queue wiring partial and the real broker not verified. C5 changed nothing for it: E2E-12 and E2E-14 stay **BLOCKED** (not faked, not PASS). H-C4-01 above is unchanged. Client rule that does not depend on C4: an ambiguous mutation or workflow start is `IDEMPOTENCY_OUTCOME_UNKNOWN`, `retryable=false`, never auto-retried, never shown as success.

## H-C4-02 — NOTIFY is not executable; AMQP adapter not integrated (live evidence, 2026-10-06)

- **ID:** H-C4-02
- **Owner:** C4 (C0 wires)
- **Severity:** P2
- **Flow:** E2E-S1 / E2E-14
- **Frontend expectation:** an action of a declared type runs in TEST and answers `WOULD_RUN`; a workflow start survives a broker outage with a correct error and recovers.
- **Actual backend behavior:** (1) a `NOTIFY` action answers **501 `NOT_IMPLEMENTED` "NOTIFY is not available: ActionNotifyPort is not wired"** even in TEST. `START_WORKFLOW` answers `WOULD_RUN` correctly. (2) An action with no `trigger` is refused as 404 `UNKNOWN_ACTION` by the browser route (D-C4-10, as designed; C5 now disables that button with the reason). (3) The AMQP adapter and `app.workflow.queue=memory|amqp` selection exist only on `agent/c4-workflow` (`5712ac5`, `7e3c7c3`); that branch is 32 commits behind `integration/v2` and C4's own commit says "Not run with Gradle/Testcontainers". Nothing selects a queue on the tested stack.
- **Endpoint/event:** `POST …/app-runtime/actions/{id}/execute`
- **Request:** `{mode:"TEST", idempotencyKey}` on a NOTIFY action
- **Response/status:** 501 `{"status":"FAILED","error":{"code":"NOT_IMPLEMENTED",…}}`
- **Reproduction:** add `{type:"ADD_ACTION", definition:{id, type:"NOTIFY", channel:"IN_APP", templateRef, trigger:{sectionId, event:"onClick"}}}` then execute in TEST.
- **Evidence:** `docs/parallel/c5/evidence/mac/` (debug during E2E-S1; fixture comment in `tests/e2e-real/lib/fixtures.mjs`).
- **Impact:** NOTIFY cannot be tested end to end; E2E-14 stays BLOCKED.
- **Suggested contract/fix:** wire `ActionNotifyPort` (or hide NOTIFY from the contract until it is); rebase/merge the queue adapter onto the integration baseline, run it under Gradle + a real RabbitMQ, then C0 wires the selection.
- **C5 workaround:** fixture uses `START_WORKFLOW`; E2E-14 BLOCKED with `E2E_RABBITMQ_WIRED` / `E2E_STOP_RABBIT_CMD` / `E2E_START_RABBIT_CMD` hooks ready.
- **Blocked test IDs:** E2E-14 (NOTIFY has no flow of its own)

## H-C4-01 — UPDATE 2026-10-07

Run stores are durable on `integration/v2 @ 3333aa7` (V29, `app.workflow.run-store=jdbc` default). E2E-12 (rewritten: a **LIVE** WAIT-only run proven in flight, two backend restarts, same run id, idempotent replay returns the same run, SUCCEEDED after recovery) passes 7/7 repeated runs. What still blocks E2E-14 is only the unintegrated AMQP queue (H-C4-02).
