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
| H-5 | **C4 side done and verified on the Mac (G2-C4 GREEN); C0 side is the current BLOCKER (section 10.3, `B-C4-11`)** (`WorkflowQueueConfiguration`, `WorkflowQueueSelection`, `CachedBrokerConnection`; section 9). C0 asks: inject `WorkflowQueue` in the wiring, **delete any queue bean the wiring creates itself** (two beans = start-up failure, on purpose), set `app.workflow.queue=amqp` (or the `prod` profile) in production config, and give prod/test brokers the `spring.rabbitmq.*` settings. | G2 | `AppRuntimeConfiguration`, `application*.yml` |
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
| G1 Durable persistence | **PARTIAL / WAITING V29 integration gate** | V29 (`wire/v29-run-persistence`) carries `action_runs.mutating`, `workflow_runs.lease_owner/lease_until` and the JDBC stores (H-1, H-3 handled by C0); not on `integration/v2` yet, no durable adapter wired, C4 has not run it |
| G2-C4 RabbitMQ + DLQ (the adapter) | **GREEN** | `AmqpWorkflowQueueTests` 14/14 on a real RabbitMQ 4 (Testcontainers, macOS, JDK 21, Gradle 9.8); see section 10 |
| G2-INTEGRATED RabbitMQ + DLQ in the running application | **BLOCKED BY C0 H-5** | the wiring still creates `InMemoryWorkflowQueue` itself (`AppRuntimeConfiguration.workflowQueue()`), a second bean named `workflowQueue` next to C4's; see section 10 |
| G3 Restart / recovery | **PARTIAL (all 12 criteria have a passing test on `verify/c4-g3`; NOT integrated, so not GREEN)** | 21 tests on PostgreSQL 17.6 (V29 stores) + RabbitMQ 4 + real engine/worker, run 5x in a row 21/21 (section 12); they run on a verification branch because the stores and V29 are not on the C4 branch, and `integration/v2` still lacks H-5; G3 turns GREEN when the same tests pass on `integration/v2` after H-5 |
| G4 Real full-stack workflow E2E | **NOT DONE** | needs G1 + G2-INTEGRATED and a real wired stack |
| G5 C6 regression | **NOT DONE** | handoff above |


## 9. Batch 3 status (A-1, H-3, H-5) - what C4 changed and what C0 must still do

Verification level (updated): the queue adapter, its configuration and the queue tests were first verified by a harness (kotlinc against stubs) and are now **verified with Gradle on macOS** (JDK 21.0.12, Docker Desktop 4.94, Gradle 9.8.0): 52/52 targeted tests pass (section 10). The durable stores (V29) and the integrated runtime are **not** covered: G1, G3, G4, G5 are not GREEN.

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
`app.workflow.queue=memory|amqp`; unset = `amqp` under the `prod`/`production` profile, `memory` otherwise; `memory` in production and any other value are start-up errors. `WorkflowQueueConfiguration` provides the single `WorkflowQueue` bean (declares the topology at start-up for `amqp`, closes channels and connection at shutdown); `logic.*` knows nothing of the switch. Tests: `WorkflowQueueSelectionTests` (7). Verified on the Mac against the real Spring Boot / amqp-client (`WorkflowQueueConfigurationTests` 6/6, section 10). The first run found and fixed one real defect: `confirm-timeout` is now read as a String and parsed with `DurationStyle` (commit `a775094`).

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

### 9.5 Gates
See the table in section 8 (kept in one place). Short form: G1 PARTIAL, G2-C4 GREEN, G2-INTEGRATED BLOCKED BY C0 H-5, G3/G4/G5 NOT DONE.

## 10. Verification on the Mac (broker) and the current blocker

### 10.1 What was run
Worktree `xweb-c4`, branch `agent/c4-workflow`, JDK 21.0.12, Docker Desktop 4.94.0 (engine 29.8.2), Gradle 9.8.0, `rabbitmq:4-management-alpine` through Testcontainers, `--no-daemon --rerun-tasks`. Compile (`compileKotlin compileTestKotlin`) GREEN. Targeted tests: 52 run, 52 passed, 0 failed, 0 skipped: `WorkflowQueueDisciplineTests` 4, `InMemoryWorkflowQueueContractTests` 5, `WorkflowLeaseTests` 9, `ActionRunAbandonmentTests` 6, `WorkflowAbandonedWriteTests` 1, `WorkflowQueueSelectionTests` 7, `WorkflowQueueConfigurationTests` 6, `AmqpWorkflowQueueTests` 14. No mock of the broker.

### 10.2 C4 broker verification: GREEN
- the AMQP adapter compiles and runs against the real `amqp-client`;
- publisher confirm: a confirmed publish is persistent (`deliveryMode 2`) and carries `messageId`/`correlationId`; a broker `basic.nack` surfaces as an exception (measured: a quorum queue with `x-max-length=1` accepts one message over the limit and nacks the next, so the test publishes until the broker refuses);
- mandatory / unroutable: a publish to a queue that does not exist is refused (`IllegalStateException`), not lost;
- manual ack: ack only after the run is saved; a lease from a dead channel is stale and acknowledging it is a no-op, a reused delivery tag acknowledges nothing else;
- DLQ: `nack(requeue=false)` dead-letters exactly that message; the delivery limit dead-letters a message whose consumer keeps dying; a poison run reaches `xweb.workflow.jobs.dlq`, healthy runs are untouched;
- duplicate / redelivery discipline: duplicate delivery and consumer death before the ack do one effect; messages survive a broker restart.
Semantics impact: none. No queue, engine or action semantics changed; the frozen A-1 rules are unchanged.

### 10.3 Full runtime G2: PARTIAL / BLOCKED BY C0 WIRING (H-5, `B-C4-11`)
`G2-C4: GREEN`, `G2-INTEGRATED: BLOCKED`. Not claimed GREEN until C0 has done this:
- `AppRuntimeConfiguration.kt` (C0, on `integration/v2`, `wire/c3-c4-runtime`, `wire/v29-run-persistence`, `wire/c3-persistence`) declares `@Bean fun workflowQueue(): WorkflowQueue = InMemoryWorkflowQueue()`.
- C4's `WorkflowQueueConfiguration` declares a bean with the same name and role. After the merge, Spring refuses to start (bean definition override is off by default) - on purpose, "two beans = start-up failure".
- C0 must: (1) delete the hard-coded `workflowQueue()` bean from `AppRuntimeConfiguration`; (2) keep injecting the interface `WorkflowQueue` into `appRuntime(...)` / the worker wiring; (3) let `WorkflowQueueConfiguration` choose the implementation; (4) set `app.workflow.queue=amqp` (or the `prod` profile) in production config, plus `spring.rabbitmq.*`; (5) `memory` for dev/test where appropriate; (6) end with exactly one `WorkflowQueue` bean. C4 does not edit C0 files.

### 10.4 V29 status (read from `wire/v29-run-persistence`, `5f28adc`)
Present: `action_runs.mutating` (default TRUE), `workflow_runs.lease_owner/lease_until` (+ check that they come together, index for the lease predicate), `JdbcActionRunStore.begin(..., mutating)` and a `sweepStale` that applies `AbandonedRuns.result(mutating)`, lease mapping in `get/insert/compareAndSet`, sweep predicate on `lease_until`. A-1 and H-3 reached V29 as cherry-picks (`c0e4173`, `4095c30`), not the original C4 SHAs. H-1 and H-3 are therefore handled by C0; the remaining blocker is **H-5 wiring**, then **real restart/recovery verification (G3)** with PostgreSQL and RabbitMQ Testcontainers and no mocked persistence or broker (plan: 9.4 and the 12 scenarios of the C4 batch).

## 11. Batch 5 - H-5 status, V29 compatibility, G3 preparation

### 11.1 H-5 status (read on 2026-10-06, refs `integration/v2` `8e91172`, `wire/v29-run-persistence`, every other local ref): **still OPEN**
`AppRuntimeConfiguration.kt:111` still declares `@Bean fun workflowQueue(): WorkflowQueue = InMemoryWorkflowQueue()`, and `integration/v2` does not contain the C4 queue sources yet (`integration/queue/workflow/*`, the `requeueInFlight` change in `logic/workflow/WorkflowQueue.kt`, the queue tests). `B-C4-11` stays OPEN, G2-INTEGRATED stays BLOCKED.

Evidence for the exact C0 change (done only in the local verification branch `verify/c4-g3`, never in a C0 branch): `integration/v2` + the C4 queue files + C0's `AppRuntimeApiTests` (`app.workflow.enabled=true`):
- **without** the C0 change: 10/10 tests fail at context start with `BeanDefinitionOverrideException: Invalid bean definition with name 'workflowQueue' defined in ... AppRuntimeConfiguration ... A bean with that name has already been defined in ... WorkflowQueueConfiguration`;
- **with** the one-bean removal below: `AppRuntimeApiTests` 10/10, `RunStoreConfigurationTests` 6/6, and the new `WorkflowQueueWiringTests` 1/1 (`app.workflow.queue=amqp`: exactly one `WorkflowQueue`, its topology exists on the broker, publish / poll / ack round-trips).

C0 change (`AppRuntimeConfiguration.kt`): delete
```kotlin
    @Bean
    fun workflowQueue(): WorkflowQueue = InMemoryWorkflowQueue()
```
(and the then unused `InMemoryWorkflowQueue` import). `appRuntime(..., queue: WorkflowQueue, ...)` keeps injecting the interface. Nothing else in the wiring changes.

C0 must import from `agent/c4-workflow` (HEAD `dfeb6fc` or later), byte for byte:
- main: `integration/queue/workflow/{AmqpWorkflowQueue,WorkflowQueueConfiguration,WorkflowQueueSelection}.kt`, `logic/workflow/WorkflowQueue.kt` (only the `requeueInFlight` delta);
- test: `integration/queue/workflow/{AmqpWorkflowQueueTests,WorkflowQueueConfigurationTests,WorkflowQueueSelectionTests}.kt`, `logic/workflow/{WorkflowQueueContract,InMemoryWorkflowQueueContractTests,WorkflowQueueDisciplineTests}.kt`;
- no new dependency: `spring-boot-starter-amqp` (amqp-client 5.37.0) and `testcontainers-rabbitmq` are already in `build.gradle.kts`; `WorkflowFakes.kt` of `integration/v2` (with the `store` parameter) is the right one, do not take the C4 copy.

### 11.2 Production configuration (audit of `application.yml`, `application-prod.yml`, `.env.example`, `compose*.yml`)
The project already names the broker settings `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` (bound to `spring.rabbitmq.host|port|username|password`; `application-prod.yml` has no defaults, so a missing one stops start-up). C4's configuration reads exactly those `spring.rabbitmq.*` keys, so **nothing new is needed and the names `RABBITMQ_USERNAME` / `RABBITMQ_VHOST` must not be introduced**: the virtual host is `spring.rabbitmq.virtual-host`, default `/`. No credential is hard-coded in C4 (the code defaults `guest/guest` only apply when nothing at all is configured, which production cannot be).
C0 should add (optional, readable intent): `app.workflow.queue: ${WORKFLOW_QUEUE:}` in `application.yml` and `queue: amqp` in `application-prod.yml`; unset + `prod` profile already means amqp, `memory` in prod is a start-up error. `app.workflow.amqp.*` (queue names, `delivery-limit`, `confirm-timeout` as `PT5S` or `5s`) keep their defaults.
Known limit: C4's connection does not read `spring.rabbitmq.ssl.*` / `addresses` (none are used by the project today). A TLS broker needs a small C4 change first.
Since commit `dfeb6fc` the configuration exists only with `app.workflow.enabled=true`, like the rest of the runtime.

### 11.3 V29 compatibility (audit of `integration/v2`, `V29__workflow_run_persistence.sql`, `Jdbc{Action,Workflow}RunStore`, and the C0 tests)
The C4 interfaces are identical on both sides: `ActionRunStore.kt`, `ActionRuntime.kt`, `WorkflowRun.kt`, `WorkflowEngine.kt` do not differ between `agent/c4-workflow` and `integration/v2` (only `WorkflowQueue.kt` does).
- `action_runs.mutating BOOLEAN NOT NULL DEFAULT TRUE`; `JdbcActionRunStore.begin(..., mutating)` persists it (also on a retry attempt); `sweepStale` runs one statement per flag with `AbandonedRuns.result(mutating)`: mutating => `IDEMPOTENCY_OUTCOME_UNKNOWN`, `retryable=false`; non-mutating => retryable `TIMEOUT` (`JdbcActionRunStoreTests`).
- `workflow_runs.lease_owner/lease_until` (check: both or neither), inserted, read and written by the same CAS as the claim; `claimForSweep` takes a run whose lease ran out (`lease_until <= now`), leaves a valid lease alone however old `updated_at` is, and falls back to `updated_at` without a lease (`JdbcWorkflowRunStoreTests`).
- CAS from a stale version fails and writes nothing, not even the steps; of several concurrent CAS exactly one wins (`JdbcWorkflowRunStoreTests`). The engine-level ownership rules (stale reader cannot update, an old owner cannot commit or renew after the reclaim) are proved on the real stores by G3-04 and G3-03 (second test).
No mismatch found: no signature, column or predicate differs from the C4 contract.

G3 tests written and run (first batch; completed to 21 tests in section 12) (branch `verify/c4-g3`, class `wiring/persistence/WorkflowG3RecoveryTests`; real PostgreSQL via the V29 stores, real RabbitMQ 4 via `AmqpWorkflowQueue`, real `WorkflowEngine` / `WorkflowWorker` / `DefaultActionRuntime`; the only fake is the effect sink standing for C3): 8/8 pass.
| Test | What it proves |
|---|---|
| G3-01 (2 tests) | step SUCCEEDED persisted, consumer dies before the ack => the broker redelivers (`deliveryCount` 2 observed), a new worker finds the persisted state, effect count stays 1, row untouched (version unchanged) for a finished run, queue and DLQ empty |
| G3-02 | the same job 4x, also consumed by two workers at once, then again after the run ended => one effect per step, no write to a finished row |
| G3-03 (2 tests) | mutation sent and cut off => `IDEMPOTENCY_OUTCOME_UNKNOWN` (persisted in `action_runs` too), no retry, no `onError` route, ambiguous step not compensated, earlier steps compensated; and the worker-dies variant: lease expiry + action-run recovery, the survivor never resends, the old worker's late return changes nothing |
| G3-04 | worker dies after the claim and before execution: valid lease respected, expired lease taken over as attempt 2, one effect, the old owner's late return is dropped |
| G3-05 / G3-06 | cancel while queued (never executes) and cancel while running (lease released, the step already running is compensated, nothing after it starts) |
Mutation check (production code temporarily broken in the verification branch, then restored; each run = the whole class): abandoned mutating run => retryable TIMEOUT: G3-03 (worker-dies) fails; `onError` also for UNKNOWN: both G3-03 tests fail; step claim without state / current-step check: G3-02 fails; lease not released when a run leaves RUNNING: G3-01 (first), G3-04 and G3-06 fail; no compensation for a step that finishes after cancel: G3-06 fails. G3-05 was not mutated.
Not covered yet: broker restart together with run state (needs its own fixed-port container; the shared test broker must not be restarted), whole-application restart through Spring, DLQ consumer (`drainDeadLetters` is called by nobody in the wiring), `ACTION_IN_PROGRESS` retry ordering against the action-run sweeper.

### 11.4 Why the G3 tests are not on the C4 branch
They need `JdbcActionRunStore`, `JdbcWorkflowRunStore`, `DataRuntimeJdbcTestBase` and the Flyway migration V29, which exist on `integration/v2` and not on `agent/c4-workflow`; a copy would be a handwritten fake, which is forbidden for G3. They live in the local branch `verify/c4-g3` (worktree `/Users/hoangluan/code/xweb-c4-g3`, `integration/v2` + C4 queue overlay + G3 tests, commits separate so C0 can take them one by one). They are C4-authored tests for C0 / C6 to import after H-5.
Branch `verify/c4-g3` @ `568fd45` (base `integration/v2` `8e91172`): `e1c00aa` queue overlay (= C4 `dfeb6fc`), `5f73a45` G3 tests, `6bd39b3` proposed H-5 change + `WorkflowQueueWiringTests`, `568fd45` G3-03 isolation fix. Last run of the whole set there (G3 8, queue classes, wiring, `AppRuntimeApiTests`, `RunStoreConfigurationTests`, `Jdbc{Workflow,Action}RunStoreTests`, `WorkflowRestartRecoveryTests`, lease/abandonment): 139/139, twice in a row, on PostgreSQL 17.6 + RabbitMQ 4 (Testcontainers). One earlier combined run failed G3-03 because the table-wide `sweepStale` also swept rows other classes had left RUNNING (count 2, not 1): a test-isolation fault, fixed, not a product defect.

### 11.5 Health and observability (audit of `integration/v2`)
- Exists: Actuator `health` with `readiness = readinessState, db, redis, rabbit, minio` (`application.yml`), `rabbit` is Spring's own connection (broker reachable, **not** C4's channels), `db` is the datasource, `MinioHealth` is the only custom indicator. `show-details: never`.
- Missing, nothing in `wiring/**` or `logic/**`: a workflow worker indicator (last successful `WorkflowWorkerRunner.tick`, last sweep), queue depth / DLQ depth, any `MeterRegistry` metric. The worker polls with `basic.get`, so the broker shows **no consumer** for the queue: "worker consumer active" cannot be read from RabbitMQ and must come from the application.
- Handoff (not coded: outside `logic/**` and C0-owned): C0 adds a `HealthIndicator` for the workflow runtime (broker reachable through the workflow connection, last tick age below a threshold) and a DLQ consumer calling `WorkflowWorker.drainDeadLetters` (otherwise dead letters pile up unseen); C4 can supply `queueDepth()` / `deadLetterDepth()` on `AmqpWorkflowQueue` when C0 asks.
- Evidence a G3 run must keep: `workflow_runs` / `workflow_run_steps` / `action_runs` rows (status, attempt, lease, version), queue and DLQ message counts from the management API (`:15672`), and the worker logs with the run id (`WorkflowJob.correlationId` = run id, `messageId` = job id).

## 12. Batch 6 - G3 recovery suite completed on the verification branch, observability contract

### 12.1 What exists (branch `verify/c4-g3`, base `integration/v2`; classes in `wiring/persistence/`, shared base `G3Support.kt`)
Real infrastructure: PostgreSQL 17.6 (Testcontainers, Flyway V1..V29), RabbitMQ 4 (`rabbitmq:4-management-alpine`, Testcontainers; the restart class has a container of its own with a fixed host port, the broker of the other classes is never restarted), `JdbcActionRunStore`, `JdbcWorkflowRunStore`, `AmqpWorkflowQueue`, real `WorkflowEngine` / `WorkflowWorker` / `DefaultActionRuntime`. Only the effect sink (the C3 side) is a recorder. No in-memory store, no fake broker, no `Thread.sleep` for an expected event (bounded polling `await`).

| Class | Tests | Scenarios |
|---|---|---|
| `WorkflowG3RecoveryTests` | 8 | 01 persisted success before the ack (2), 02 duplicate delivery + two workers, 03 ambiguous mutation (sync timeout; worker death with lease expiry), 04 crash after claim, 05 cancel queued, 06 cancel running |
| `WorkflowG3SweeperRaceTests` | 8 | 09 ACTION_IN_PROGRESS vs the action-run sweeper (mutating; budget-exhausted characterization; non-mutating), 10 three workers x 5 rounds racing for an expired lease, 11 malformed message to the DLQ, broker delivery limit -> one process failure -> recovery, real poison run -> `DEAD_LETTERED`, 12 cancel after a worker death (compensation) |
| `WorkflowG3BrokerRestartTests` | 2 | 07A job in the durable queue across a broker restart, same worker reconnects; 07B SUCCEEDED persisted + ack never sent + broker restart -> redelivered, no repeated effect |
| `WorkflowG3SpringRestartTests` | 3 | 08A application restart while a step is RUNNING (real context close, new context, new worker id, lease reclaim, attempt 2, the old engine can no longer renew or write), 08B restart after persisted success before the ack, 12 cancel while queued survives a restart |
The Spring contexts contain the production `RunStoreConfiguration`, `WorkflowQueueConfiguration` (`app.workflow.queue=amqp`: topology declared at start-up, channels and connection closed at shutdown) and `WorkflowWorkerRunner` (`@Scheduled`) over an `AppRuntime` built from the same engine/worker; not the whole application (no HTTP, no published AppDefinition): the full-application variant needs H-5 and belongs to G4.

### 12.2 Findings
- **Isolation (fixed, guarded)**: the stale sweeps are table-wide, so one test's sweep touched rows left by another class (G3-03 once counted 2 instead of 1). `G3TestBase` now cleans every unfinished workflow run and every RUNNING action run before and after each test, asserts that none exists at the start of a test (regression guard), waits for "crashed" worker threads in teardown, and uses unique queue names; sweep counts are exact again.
- **A dead letter is ONE process failure of its run, not a verdict** (`failFromDeadLetter`): a message the broker dead-lettered after its delivery limit leaves the durable state untouched (no attempt, no effect, no retry created by redelivery); the DLQ consumer adds one `processFailures` + backoff, the sweeper republishes and the run completes. Only `maxProcessFailures` of them fail the run (`DEAD_LETTERED`, persisted).
- **ACTION_IN_PROGRESS vs the sweeper (semantics unchanged, one imprecision to decide)**: while the dead worker's action run is RUNNING a retry meets `ACTION_IN_PROGRESS` (retryable) and waits in backoff, bounded by the step's attempt budget; after the action-run sweep the mutating run is `IDEMPOTENCY_OUTCOME_UNKNOWN` and the next attempt ends the run there with no second send. If the budget ends **before** the sweeper ran, the run fails with `ACTION_IN_PROGRESS`, not `UNKNOWN`: nothing is resent and the step is not compensated, but the code understates "the write may have been applied". Pinned by a characterization test; changing it (fail with UNKNOWN when the last attempt meets IN_PROGRESS of a mutating action) is a semantic decision for the C4 owner / C0, not made here. Also: a non-mutating NAVIGATE has no run row at all unless a definition sets `idempotency` to OPTIONAL/REQUIRED, so ACTION_IN_PROGRESS cannot occur for it by default.
- **A cancelled run is not interrupted**: a step already running when the run is cancelled finishes its effect; `compensateOnCancel` then compensates it (G3 06), and nothing after it starts.
- **No flaky timing found**: 5 consecutive runs of the 21 G3 tests, 21/21 each; broker restart waits are bounded polling. The broad set (G3 21, queue classes, wiring, `AppRuntimeApiTests`, `RunStoreConfigurationTests`, `Jdbc{Workflow,Action}RunStoreTests`, `WorkflowRestartRecoveryTests`, `ActionRunRecoveryTests`, `WorkflowEngineTests` 65, `WorkflowSweeperTests` 13, lease / abandonment) at `verify/c4-g3` `ebe6fae`: 235/235, 0 failed, 0 skipped, `--rerun-tasks`.

### 12.3 G3 criteria (all on PostgreSQL/JDBC + RabbitMQ, real)
persisted success before ACK: 01, 07B, 08B; duplicate delivery: 02; ambiguous mutation recovery: 03 (x2), 09; crash after claim: 04, 08A; lease expiry / reclaim: 03, 04, 08A, 10; cancel queued / running: 05, 06, 12 (x2); RabbitMQ restart: 07A, 07B; Spring restart: 08A, 08B, 12; ACTION_IN_PROGRESS + sweeper: 09; multi-worker reclaim race: 10; DLQ terminal handling: 11 (x3); cancel survives restart: 12.
Mutation check (production code broken in the verification branch, restored afterwards): an abandoned run that is RUNNING is started again instead of ACTION_IN_PROGRESS -> 3 G3-09 tests fail; step claim without state check -> G3-09 fails (and G3-02 earlier); a dead letter not counted against its run -> G3-11 fails; and the earlier set (abandoned mutating -> TIMEOUT, onError for UNKNOWN, lease not released, no compensation after cancel).

### 12.4 Health and observability contract (design; no production code in this batch)
Facts: Actuator `readiness` = `readinessState, db, redis, rabbit, minio`; `rabbit` is Spring's own connection, not C4's channels; the worker polls with `basic.get`, so **`consumer_count = 0` is the normal state of a healthy worker and says nothing about it** - never use it as health evidence. Unacked messages (`messages_unacknowledged`) held for long = a stuck lease, a better signal.
Signals (names are the contract; the HealthIndicator / Micrometer binding live in `wiring/**` and `application*.yml`, C0-owned; the counters sit in `WorkflowWorker` / `AmqpWorkflowQueue`, C4-owned, to be added when C0 asks):
| Signal | Meaning | Needed before the full-stack E2E (G4) |
|---|---|---|
| `workflowQueue.connection` UP/DOWN | the workflow's own connection is open and the topology exists (passive declare) | required, readiness |
| `workflowWorker.running` | the runner is scheduled and its last tick finished | required, liveness |
| `workflowWorker.lastPollAt` / `lastSuccessfulPollAt` | updated by every tick, also when the queue is empty (idle is not dead); stale if older than max(30 s, 10 x `worker-delay-ms`) | required |
| `workflowWorker.lastSweepAt` (+ last report) | the lost-job / lease recovery is alive | required |
| `workflowQueue.deadLetterMessages` | alert when > 0; E2E asserts 0 | required |
| `workflowQueue.readyMessages` | backlog (unacked not included) | recommended |
| `workflowWorker.lastJobAt`, `lastAckAt`, `lastErrorAt` | activity / failure trail | optional |
| counters: jobs processed, redeliveries seen (`deliveryCount` > 1), process failures, sweeps republished | trend | optional |
Evidence a G3/G4 run keeps: the `workflow_runs` / `workflow_run_steps` / `action_runs` rows (status, attempt, lease, version), queue and DLQ counts from the management API (`:15672`), worker logs by run id.

### 12.5 C6 handoff delta
Use the 21 tests as the scenario list for the real-stack suite (no mock of the stores or the broker): the asserts that matter are persisted rows + effect counts + queue/DLQ depth. New in this batch: scenarios 09 (ACTION_IN_PROGRESS then UNKNOWN), 10 (reclaim race), 11 (DLQ joined to workflow state), 07 (broker restart needs its own container), 08 (restart through real context close). Do not restart the shared test broker.

## 13. F-1 (run scope before creator access) - fixed in `fa7ed6a`
Root cause: `WorkflowEngine.mayView()` (used by `status` and `cancel`) returned true for the creator before any scope check, and runs are loaded by `(tenant, runId)` only, so the workspace and project of the run were never compared with the request's. The permission branch checked `WORKFLOW_MANAGE` for the request's workspace against the run's application, the same hole for a non-creator. Fix: `sameResourceScope(run, ctx)` (tenant, workspace, project equal; `null` is a value, never a wildcard) first, then creator, then permission; a wrong scope is `RUN_NOT_FOUND`. Tests: `WorkflowRunScopeTests` (7); the fixture `Fx.ctx()` had a random workspace per call and is now fixed.
Consequence to decide (not changed here): a run started by the scheduler has `workspaceId = null` (`ScheduledRunRequest` carries no workspace and the JDBC store does not derive it from the project), so it is NOT visible through a request that names a workspace. Fail-closed is the intended direction, but the schedule UI must be able to see its runs: either the enqueuer (C0 wiring) supplies the project's workspace in the `ActionContext`, or `ScheduledRunRequest` gets a `workspaceId` (C4, additive). Also: whether a creator who lost W1 may still reach his run through W1/P1 is the route's membership check (C1 / C0), not the engine's.

