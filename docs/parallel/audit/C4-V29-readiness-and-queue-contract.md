# C4 — V29 readiness, restart/recovery contract and RabbitMQ contract

Author: C4 · Date: 2026-10-06 · Branch `agent/c4-workflow` · Baseline `integration/v2 @ f894cc6` · V29 under review: `wire/v29-run-persistence @ c46fe1c` (C0, branch only)

Status vocabulary: **VERIFIED** = a test ran and passed where stated · **WRITTEN** = code/test exists but has not been run · **OPEN** = nothing exists yet.
Nothing in this file claims Gradle, Testcontainers or RabbitMQ results that were not observed. See section 8 for the exact gate state.

## 1. Where things are (observed with git, not assumed)

| Item | State |
|---|---|
| `integration/v2 @ f894cc6` | contains C4's logic **identical** to the overlay (`git diff verify/c4-overlay integration/v2 -- logic` is empty): Jackson 3 alignment, 409/422 semantics, **D-C4-17** (`normalizeAmbiguous`). |
| `agent/c4-workflow` | was `f6bb475`, behind integration by exactly four C4-owned files (`ActionResult.kt`, `ActionRuntime.kt`, `ActionRuntimeTests.kt`, `WorkflowShapeTests.kt`). Those four were re-applied byte-for-byte from `integration/v2` as C4's own change (no merge, no rebase). |
| V29 | **exists only on `wire/v29-run-persistence`** (5 C0 commits: schema, JDBC stores, recovery wiring, tests, docs). Not on integration/v2. C0's own status: "IMPLEMENTED - MAC GATE PENDING, statically checked, nothing compiled or run". |
| RabbitMQ | `integration/queue/JobQueue.kt` serves **publish** only (`studio.publish`). There was **no** workflow queue adapter: `AppRuntimeConfiguration.workflowQueue()` is `InMemoryWorkflowQueue()` in both integration/v2 and the V29 branch. |
| Evidence found, not produced by C4 | `backend/build/test-results` in the C4 worktree: a full Gradle `test` of 63 suites, **575 tests, 0 failures, 0 errors, 3 skipped**, 04:59-05:03 on 2026-10-06 (includes `publish.QueueRecoveryTests` on a real RabbitMQ). It contains the *old* retryable-timeout tests, so it predates D-C4-17 and V29. It is useful as "the Mac toolchain works"; it is **not** a gate for anything below. |

## 2. Audit of the contracts C4 needs from V29

### 2.1 `ActionRunStore` ↔ V29 `JdbcActionRunStore`

| Contract | V29 | Verdict |
|---|---|---|
| `begin`: atomic, key scope (tenant, app, action, **user**, derived key), `Started/Replay/InProgress/KeyReused` | unique index with `COALESCE(app_id, nil)`; tests for concurrent begins | OK |
| non-retryable FAILED replayed as is (UNKNOWN / MUTATION_REJECTED survive a restart) | result stored as JSON with `code` + `retryable` | OK |
| `complete` only by the owning `runId` | guarded update | OK |
| `sweepStale`: abandoned RUNNING → FAILED | writes **`TIMEOUT`, `retryable=true`** for every abandoned row | **see A-1** |
| `purgeFinished` never deletes RUNNING | yes | OK |
| owner of a RUNNING row | `worker_id` column, *diagnostics only* | OK (not a lease) |

**A-1 (decision needed).** D-C4-17 says an executor-level `TIMEOUT`/`INTERRUPTED` of a *mutating* action is an unknown outcome. The store's sweep produces the same situation (a process died after possibly sending the write) but records a retryable `TIMEOUT`. C0's D-C0-23 item 3 argues this is safe because a retry re-enters the data layer with the **same derived key**, whose V28 `data_idempotency` record answers Replay / UNKNOWN. That holds for `CREATE/UPDATE/DELETE/SUBMIT/CALL_API` (all carry the derived key into C3). It does **not** hold for a connector that does not honour the key: `NOTIFY` has no port yet (`ActionPorts(notify = null)`), and a future SMTP/webhook connector would send twice. Two acceptable resolutions, C0/owner to choose:
1. keep the sweep as is **and** make "honours the derived key, or reports UNKNOWN" a hard requirement of every writable connector / notify port (H-2), or
2. record whether the run was mutating (`action_runs.mutating BOOLEAN`, set by `begin`) and let `sweepStale` write `IDEMPOTENCY_OUTCOME_UNKNOWN`, `retryable=false` for mutating rows (H-1). Strictly stronger, costs one column in a V29 that is not on integration yet.

C4's recommendation: **2**, because it makes the frozen rule true by construction instead of by the discipline of every future connector. Until decided, behaviour is unchanged and documented here.

### 2.2 `WorkflowRunStore` ↔ V29 `JdbcWorkflowRunStore`

| Contract | V29 | Verdict |
|---|---|---|
| `create` idempotent on (tenant, app, workflow, creator, mode, key); fingerprint mismatch → `KeyReused` | `UNIQUE` + concurrent-create test | OK |
| `compareAndSet(expected, next)` on `version`; changed steps written **in the same transaction** | yes (tested: stale version writes nothing, not even steps) | OK |
| persisted step state incl. `attempt`, `visit`, `wake_at`, `approval_id`, `compensated`, `error_code` | `workflow_run_steps` | OK - this is what makes "UNKNOWN stays UNKNOWN across a restart" true |
| `claimForSweep`: atomic, fair, stamps `last_swept_at`, skip-locked | implemented + tests incl. "two sweepers never take the same run" | OK |
| `recordProcessFailure`, `recordSweepResult` | yes | OK |
| retention (`redactFinished`, `purgeFinished`) | implemented, **not scheduled** | OPEN (C0) |
| **explicit worker ownership / lease** | none: no `owner`, no `lease_until`; lease = `updated_at` + engine `staleAfter` | **see L-1** |
| transactional outbox for the first job | none | **see O-1** |

**L-1 - ownership is implicit.** Today: a step is claimed by a compare-and-set that writes `status=RUNNING, attempt+1, updatedAt=now`; only one worker can win an attempt; a run untouched for `staleAfter` (default PT2M, wiring minimum PT30S) is "lost" and the sweeper puts the step to `RETRY_WAIT` (attempt kept) and republishes. A late result of the old worker is dropped (`applyOutcome` checks `status==RUNNING && attempt==expected`). That already gives *one effective owner per attempt* and *lease expiry + reclaim*, and it is tested (`a crashed worker is recovered...`, `an outcome from a worker the sweeper already gave up on is dropped`, V29 `WorkflowRestartRecoveryTests`). What it does **not** give:
- **no renewal**: a step that legitimately runs longer than `staleAfter` is reclaimed while still running → two executions of the same attempt key (the second is a replay/IN_PROGRESS at the action and data layers, so no second effect, but it wastes an attempt and can end in a spurious UNKNOWN);
- **no visible owner**: operators cannot see which node holds a run;
- the safe minimum is a *derived* rule, not an enforced one: `staleAfter` must exceed the longest step (`ActionLimits.timeout` ceiling, default 30 s; step `timeout`) with margin. The wiring only requires ≥ 30 s - exactly the action ceiling, i.e. **no margin**.

C4 proposal (needs C0 schema, so handoff H-3, not done by C4): `workflow_runs.lease_owner VARCHAR(64)`, `lease_until TIMESTAMPTZ`; claim = CAS that also sets `lease_owner=:worker, lease_until=:now+lease`; `renew` = CAS extending `lease_until` while `lease_owner=:worker` (called by the worker every `lease/3` while a step runs); the sweeper's staleness test becomes `lease_until < now` (falling back to `updated_at` when null). C4 adds the matching `StepClaim(workerId, leaseUntil)` to the store port in the same change. **Until then** C4/C0 should (a) raise the wiring minimum to `2 × max action timeout` (H-4) and (b) keep the `staleAfter > step timeout` rule in the runbook.

**O-1 - run row vs first message.** `start` = audit → `create` (committed) → `publish`. If `publish` fails or the process dies in between, the run is `PENDING` with no message. This is **not a permanent loss**: `PENDING` + `updatedAt` older than `staleAfter` is stale, `claimForSweep` picks it, the sweeper republishes (tested: `a job lost before delivery is published again by the sweeper`, V29 `a PENDING run that never got its job is started by the next process`). Same for every later step (state saved, then publish). So the sweeper *is* the outbox, with a worst-case delay of `staleAfter + sweep period`. If a tighter bound is wanted, the options are an outbox table (V30, **not requested now**) or lowering `staleAfter` once L-1 (renewal) exists. No change proposed in this batch.

### 2.3 Wiring seams (C0-owned files, not touched)
- `workflowQueue()` bean must become selectable: `app.workflow.queue = amqp | memory` (default `memory` until G2 is green). Snippet in section 5.
- `WorkflowWorkerRunner` polls every `worker-delay-ms` (500 ms) with `basic.get`; correct but latency-bound and one thread. A push consumer is a later optimisation, not a correctness issue.
- `ActionRunRecovery` (10 min) vs workflow `staleAfter` (2 min): consistent - the workflow re-drive gets `ACTION_IN_PROGRESS` (retryable) until the action row is swept, then re-enters the data layer with the same key.

## 3. Restart / recovery contract (what is promised, by crash point)

Persisted truth is the run store; the queue only nudges. **No workflow state is inferred from a message.**

| Crash point | Persisted state | Recovery | Guarantee | Test |
|---|---|---|---|---|
| after `create`, before publish / publish fails | run `PENDING`, step `PENDING` | sweeper republishes after `staleAfter` | run starts once; late, never lost | engine `a job lost before delivery...`; V29 restart test; AMQP IT (publish failure) WRITTEN |
| after step claim, **before** the action runs | step `RUNNING(attempt n)` | sweeper → `RETRY_WAIT` → attempt n+1, same derived key | action runs once (nothing was sent) | `a crashed worker is recovered...` |
| action finished + saved, **before ACK** | step `SUCCEEDED`, next step `PENDING` | broker redelivers the old message; engine sees `currentStepId != job.stepId` → stale → ACK | no repeat | **new** `a worker that dies between saving and acknowledging...` |
| mutation sent, outcome unknown, worker dies | step `RUNNING(attempt n)` | re-drive attempt n+1 with the **same** derived key → data layer answers Replay (applied) or `IDEMPOTENCY_OUTCOME_UNKNOWN` | never a second effect; UNKNOWN ends the run, **no further retry, no `onError`, ambiguous step not compensated, earlier steps compensated** | **new** `after a crash in the middle of a write that the data layer reports as unknown...`; V29 `an ambiguous write stays unknown after a restart...` |
| UNKNOWN already persisted, then restart | step `FAILED(IDEMPOTENCY_OUTCOME_UNKNOWN)`, run `FAILED` | nothing re-drives a terminal run; action store replays non-retryable FAILED | UNKNOWN never becomes retry | V29 `an ambiguous write stays unknown...`, `JdbcActionRunStoreTests` |
| consumer dies holding the message | unchanged | broker redelivers (counted, limit → DLQ) | duplicate harmless | engine `a consumer that dies before acking...`; contract tests; AMQP IT WRITTEN |
| two workers on one run | CAS on `version` | loser's claim fails → DONE | one owner per attempt | `a duplicate job for a step that another worker is running does nothing` |
| lease expiry | `updated_at` stale | sweeper reclaims | see L-1 (no renewal) | `a crashed worker...`; V29 `two processes sweeping...` |
| backend restart | all of the above in PostgreSQL | sweeper at start | resumes without repeating a finished step | V29 `WorkflowRestartRecoveryTests` (9 tests, **not run**) |
| broker restart | quorum queue + persistent messages | consumers reconnect; leases from before are stale | no lost job (and if one were lost, the sweeper republishes) | AMQP IT WRITTEN |

Rules the code enforces and the tests now pin:
1. `WorkflowWorker` **never** calls `nack(requeue=true)` (test `the worker never asks the broker to requeue, on every path`, mutation-checked: switching the dead-letter `nack` to `requeue=true` fails it and two existing poison tests).
2. A message is acknowledged **only after** its outcome is saved (test `a message is acknowledged only after what it caused is saved`).
3. Workflow retry is the run store's `RETRY_WAIT` + sweeper publish; broker redelivery is never a retry (every message in the discipline test has `deliveryCount == 1`).
4. `ProcessOutcome.RETRY` (outage) is ACKed, not requeued: the sweeper is the retry.

## 4. RabbitMQ contract (adapter: `integration/queue/workflow/AmqpWorkflowQueue.kt`)

| Item | Contract |
|---|---|
| Queue | `xweb.workflow.jobs`, durable, **quorum** (`x-queue-type=quorum`) |
| Dead letter | exchange `xweb.workflow.dlx` (direct, durable) → queue `xweb.workflow.jobs.dlq` (quorum), routing key = DLQ name; queue args `x-dead-letter-exchange`, `x-dead-letter-routing-key`, `x-dead-letter-strategy=at-least-once`, `x-overflow=reject-publish` |
| Delivery limit | `x-delivery-limit` (default 5): returns to the queue after consumer death/channel close; above it the **broker** dead-letters. Never reached by normal operation because the worker never requeues |
| Message | persistent (`deliveryMode=2`), `text/plain`, body `WorkflowJob.encode()` (`v1|jobId|tenantId|runId|stepId`: no actor, no permission, no payload), `messageId=jobId`, `correlationId=runId`, `type=workflow.job.v1` |
| Publish | publisher confirms on a dedicated channel, `mandatory=true`, `waitForConfirmsOrDie(confirmTimeout)`; throws on nack / timeout / unroutable. Engine behaviour on throw: run stays `PENDING`, sweeper republishes |
| Consume | `basic.get`, **manual ack**; lease = `generation:deliveryTag`; a lease from a dead channel is ignored (broker already returned the message; a reused tag can never ack another message) |
| ACK matrix | success + state saved → `ack` · retry already persisted (`RETRY_WAIT`) → `ack` · outage durably deferred → `ack` · malformed message → `nack(requeue=false)` · run's failure budget exhausted → `nack(requeue=false)` (run is failed `DEAD_LETTERED` first) · **never** `nack(requeue=true)` |
| DLQ | `WorkflowWorker.drainDeadLetters` → `failFromDeadLetter` counts one process failure for the named run (an unknown/foreign/terminal run is ignored); `ackDeadLetter` after |
| Duplicate delivery | safe by construction: the engine re-reads the run, a stale step message is ACKed, the step claim is a CAS |
| Topology drift | `declareTopology()` is idempotent; different arguments → broker `PRECONDITION_FAILED` → start-up fails (an operator must fix it) |
| Not in the adapter | connection management and bean selection (C0 wiring), alerting on DLQ depth, a push consumer |

Design choices worth a reviewer's attention: (a) plain `amqp-client` instead of Spring AMQP templates so confirm/return/ack behaviour is explicit; (b) explicit DLX instead of the default exchange because `at-least-once` dead-lettering is specified against an explicit exchange; (c) ack/nack failures are logged and swallowed (work is saved; redelivery is harmless), publish/poll failures propagate; (d) `x-delivery-count` is only informational - nothing in the engine branches on it.

## 5. Handoff to C0 (all optional to start; none blocks the C4 files below)

| Id | Ask | Why | Files (C0-owned) |
|---|---|---|---|
| H-1 | `action_runs.mutating BOOLEAN NOT NULL` set by `begin`; `sweepStale` writes `IDEMPOTENCY_OUTCOME_UNKNOWN`, `retryable=false` for mutating rows, `TIMEOUT` retryable for the rest. C4 will add `mutating` to `RunBegin`/`begin(...)` in the same change, with tests, once C0 confirms the column. | A-1 | `V29__...sql` (before it reaches integration), `JdbcActionRunStore` |
| H-2 | Every writable connector / `ActionNotifyPort` must either dedupe on the **derived** key or report `IDEMPOTENCY_OUTCOME_UNKNOWN` when it cannot tell. Add to the connector contract tests. | A-1 option 1 | C3/C0 |
| H-3 | `workflow_runs.lease_owner VARCHAR(64) NULL`, `lease_until TIMESTAMPTZ NULL`; store ops `claimStep(run, workerId, leaseUntil)` / `renewLease(run, workerId, leaseUntil)` as CAS; sweeper staleness = `lease_until < now` (fallback `updated_at`). C4 writes the port change + engine heartbeat + tests. **Schema goes into V29 before it is imported, or V30 if C0 allocates it; C4 allocates nothing.** | L-1 | `V29__...sql` / V30, `JdbcWorkflowRunStore` |
| H-4 | Raise the wiring minimum `app.workflow.stale-after` from PT30S to `2 × ActionLimits ceiling timeout` (60 s for the 30 s default) until H-3 exists. | L-1 | `AppRuntimeConfiguration` |
| H-5 | **C4 side done** (`WorkflowQueueConfiguration`, `WorkflowQueueSelection`, `CachedBrokerConnection`; section 9). C0 asks: inject `WorkflowQueue` in the wiring, **delete any queue bean the wiring creates itself** (two beans = start-up failure, on purpose), set `app.workflow.queue=amqp` (or the `prod` profile) in production config, and give prod/test brokers the `spring.rabbitmq.*` settings. | G2 | `AppRuntimeConfiguration`, `application*.yml` |
| H-6 | Schedule retention (`redactFinished`/`purgeFinished`) - already implemented in the stores, nobody calls it. | hygiene | wiring |
| H-7 | Import V29 only after its Mac gate; C4 will re-run `WorkflowRestartRecoveryTests`-equivalents against `AmqpWorkflowQueue` once both exist (G3). | G1/G3 | - |

## 6. Handoff to C6 (QA / regression gate)

Scenarios that must exist in the real-stack suite (PostgreSQL V28+V29, RabbitMQ, real `WorkflowWorkerRunner`), each ending in an assertion on **persisted** rows:
1. start → success; start with the broker down (run PENDING) → broker up → success without a second start;
2. kill the backend between step claim and result, restart → one effect;
3. kill the backend after a mutation was sent (fault-inject the connector after the write) → UNKNOWN, run FAILED, earlier step compensated, **no** retry, **no** `onError`;
4. publish the same job 5× → one effect; 5. kill the consumer before ACK → one effect;
6. poison message → DLQ, healthy runs unaffected; 7. retry budget exhausted → FAILED with the last code; 8. cancel while queued and while running;
9. two nodes, one run; 10. broker restart with queued jobs; 11. database restart mid-run.
C6 must not mock RabbitMQ or the stores for G4.

## 7. Proposed decisions (hand-merge into `DECISIONS.md` at import; C4 does not edit shared docs)

- **D-C4-18 - Workflow queue discipline.** The workflow queue is a nudge. No `nack(requeue=true)`, ack only after save, retry only through `RETRY_WAIT`+sweeper, DLQ only for malformed messages and exhausted run budgets. Enforced by `WorkflowQueueDisciplineTests`.
- **D-C4-19 - RabbitMQ topology** as in section 4 (quorum, explicit DLX, confirms, manual ack, delivery limit).
- **D-C4-20 - Ownership model.** Until H-3, ownership = step CAS + `staleAfter` heartbeat on `updated_at`; with H-3, explicit `lease_owner`/`lease_until` with renewal.
- **D-C4-21 (needs owner/C0 decision)** - abandoned mutating action runs (A-1).

## 8. Gates (honest state)

| Gate | State | Evidence / missing |
|---|---|---|
| G1 Durable persistence | **NOT DONE** | V29 on a branch, Mac gate pending, no durable adapter is wired into integration; C4 has not run it |
| G2 RabbitMQ + DLQ | **NOT DONE** | adapter + contract/IT **written**, compiled only against hand-written API stubs; **not run** (no Gradle/Docker where C4 works) |
| G3 Restart / recovery | **NOT DONE** | unit-level scenarios VERIFIED (harness 399/399); durable + broker restart scenarios WRITTEN, not run |
| G4 Real full-stack workflow E2E | **NOT DONE** | needs G1+G2 and a real wired stack |
| G5 C6 regression | **NOT DONE** | handoff above |


## 9. Batch 3 status (A-1, H-3, H-5) - what C4 changed and what C0 must still do

Verification level: **harness only** (Kotlin compiled with kotlinc against hand-written stubs, 422 tests run by a mini JUnit runner). Gradle, Testcontainers and a real broker have **not** been run by C4. Nothing here is GREEN for G1-G5.

### 9.1 A-1 - mutating abandoned run is an unknown outcome (decided: option "UNKNOWN", `retryable=false`)
C4 side (done): `ActionRunRecord.mutating`, `ActionRunStore.begin(key, fingerprint, now, mutating)`, `AbandonedRuns.result(mutating)` = `IDEMPOTENCY_OUTCOME_UNKNOWN`, `retryable=false` for mutating, `TIMEOUT` retryable for non-mutating; `ActionRuntime` passes `def.type.mutatesState`; `InMemoryActionRunStore` honours it. A workflow step whose action run was abandoned therefore fails the run with UNKNOWN (no retry, no onError, not compensated; earlier steps are).
C0 must (nothing done by C4 in C0 files):
1. `action_runs.mutating BOOLEAN NOT NULL DEFAULT TRUE` in V29 (before it reaches integration); default TRUE = a legacy row is treated as mutating (safe).
2. `JdbcActionRunStore.begin(..., mutating)` persists it; `sweepStale` writes `AbandonedRuns.result(row.mutating)` instead of the fixed retryable TIMEOUT. **`JdbcActionRunStore` does not compile against the new interface until this is done** (the 3-argument `begin` still exists as a default method, the 4-argument one is abstract).
3. `ActionRunRecoveryTests` first test and D-C0-23 item 3 change: an abandoned mutating run is UNKNOWN, not retryable TIMEOUT.
Behaviour cost (accepted by the A-1 decision): a write that was sent and committed before the crash is no longer reconciled automatically by replay-by-key; it surfaces as UNKNOWN after the action sweep (default PT10M). A crash before the write was sent is indistinguishable and is also UNKNOWN for mutating actions.

### 9.2 H-3 - durable lease (C4 model and engine done; schema is C0's)
C4 side (done): `WorkflowRun.leaseOwner` / `leaseUntil`; the claim CAS sets both (`workerId`, `now + staleAfter`); a heartbeat renews them every `staleAfter/3` (min 1 s) while the step runs (`renewLease` is a CAS that only the owner and only for the running attempt can win); every transition that leaves RUNNING, and every terminal run, clears them; abandonment = `leaseUntil <= now` when a lease is present, otherwise the old `updated_at < staleBefore` rule (so a store without the columns keeps working).
C0 must (V29, or V30 if C0 allocates it; C4 allocates nothing):
- `workflow_runs.lease_owner VARCHAR(64) NULL`, `workflow_runs.lease_until TIMESTAMPTZ NULL`; `JdbcWorkflowRunStore` maps them in `get/insert/compareAndSet` (same row, same `version` CAS: claim and lease are one atomic write).
- Sweep query: `status NOT IN (terminal) AND ((lease_until IS NOT NULL AND lease_until <= :now) OR (lease_until IS NULL AND updated_at < :staleBefore))` plus the existing timer / approval predicates; keep the fair, bounded `last_swept_at` ordering.
- Wire `workerId` (one per node, e.g. `node-<uuid>` or hostname+pid) when constructing `WorkflowEngine`.
- H-4 (minimum `stale-after`) is mitigated by the heartbeat but not removed: the heartbeat thread stops with the process, which is exactly when the lease must expire.
Tests: `WorkflowLeaseTests` (9), `WorkflowAbandonedWriteTests`; mutants (lease ignored, owner not checked) fail them.

### 9.3 H-5 - queue adapter selection (C4 side done, new files only)
`app.workflow.queue=memory|amqp`; unset = `amqp` under the `prod`/`production` profile, `memory` otherwise; `memory` in production and any other value are start-up errors. `WorkflowQueueConfiguration` provides the single `WorkflowQueue` bean (declares the topology at start-up for `amqp`, closes channels and connection at shutdown); `logic.*` knows nothing of the switch. Tests: `WorkflowQueueSelectionTests` (7). Not verified against Spring Boot 4 / the real amqp-client (stubs only): first Mac compile may report API mismatches, to be fixed by C4.

### 9.4 Integration test plan for the 16 scenarios (code only after `AmqpWorkflowQueueTests` is green on the Mac)
Needs C0's JDBC stores (V29) + RabbitMQ container + PostgreSQL container, in one `@SpringBootTest` base reusing `IntegrationTestBase`. Tests that restart the broker or backend reuse the fixed-port technique of `AmqpWorkflowQueueTests`; "backend restart" = new engine/worker/queue instances over the same database.

| # | Scenario | Asserts |
|---|---|---|
| 1 | create workflow | definition persisted, version pinned |
| 2 | persist run | `workflow_runs` row PENDING, steps rows, before any publish |
| 3 | publish Rabbit message | message in `xweb.workflow.jobs`, persistent, `messageId`/`correlationId` |
| 4 | consume | `basic.get` delivery, no auto-ack, unacked count 1 |
| 5 | claim persisted run | row RUNNING, `lease_owner`/`lease_until` set, attempt 1 |
| 6 | execute step | action run row + C3 write once |
| 7 | persist result | step SUCCEEDED, run terminal, lease cleared |
| 8 | ACK | queue empty, unacked 0, only after 7 |
| 9 | backend restart | PENDING/RUNNING run completes after new instances start |
| 10 | broker restart | persistent message survives, run completes |
| 11 | duplicate delivery | second delivery is a no-op, one write |
| 12 | consumer crash before ACK | redelivered, one write |
| 13 | worker crash after persisted result, before ACK | redelivery is DONE with no second write |
| 14 | ambiguous mutation crash | run `FAILED`, `IDEMPOTENCY_OUTCOME_UNKNOWN`, no retry, no compensation of that step |
| 15 | lease expiry + reclaim | sweeper republishes, new owner, attempt 2, same idempotency key |
| 16 | cancel queued / running | CANCELLED, lease cleared, late result ignored |

### 9.5 Gates (unchanged: none GREEN)
G1 durable persistence: NOT DONE (needs C0 JDBC stores + tests). G2 RabbitMQ + DLQ: NOT DONE (adapter and tests written, never run). G3 restart/recovery: NOT DONE. G4 real full-stack E2E: NOT DONE. G5 C6 regression: NOT DONE.
