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
