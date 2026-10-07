# C2 publish / release / rollback — production contract (D-C0-24)

Owner of the decision: **C0**. Implementer: **C2** (`publish/**`, `integration/deploy/**`, `integration/storage/**`, migration **V30**).
Status: **ACCEPTED 2026-10-06. Migration number V30 is RESERVED for C2 (D-C0-27)**: V29 is on `integration/v2` (immutable) and its gate is green. The design below is normative; where an older sentence in this file says "not reserved" or "draft number", D-C0-27 supersedes it.
Evidence base: code of `DeploymentProcessor`, `ReleaseDeployer`, `ReleaseService`, `StaticSiteDeployProvider`, `SiteService`, `SiteControllers.rollback/unpublish`,
`DeploymentRepository`, `DeploymentModel`, `DeploymentRecovery`, migrations V4 / V13 / V15 / V26 / V27, frontend consumers (`features/studio/drawers.tsx`, `packages/types`).

## 0. Decisions in one table

| # | Decision |
|---|---|
| 1 | Baseline strategy **APPROVED**: `integration/v2 @ f894cc6` → new branch **`fix/c2-v3`** → cherry-pick exactly `2eaf0b1`, `6d56c9e`, `1e6c561` (done by C0: `649e5ed`, `5ea321d`, `51a09ed`). `fix/c2-v2` is never merged; the 12 older commits are already in the baseline (70 files byte-identical). |
| 2 | Migration: **V30, RESERVED for C2 by D-C0-27** (purpose "deployment rollback status + concurrent publish guard"); the number may be used by nobody else. C2 first syncs `fix/c2-v3` onto the `integration/v2` HEAD that contains V29, then writes the V30 file there; C0 imports it after its own Mac gate. |
| 3 | Scope of serialization: **(tenantId, appId = projects.id, environment = `PRODUCTION`)**. Today a project has exactly one site (`sites.project_id` is the primary key) and no other environment exists in the schema, so the scope row **is the `sites` row**. Tenant comes from `projects.tenant_id`; every ownership query joins it. A second environment is a future migration, not part of V30. |
| 4 | Mechanism = **lease row + optimistic version + intent sequence** (section 3). Not a global lock, not an advisory lock. |
| 5 | **One new deployment status: `ROLLING_BACK`.** `ROLLED_BACK` (already in the CHECK constraint) gets its first real meaning. `ROLLBACK_FAILED` and `ROLLBACK_OFFLINE` are **not** statuses. |
| 6 | **Release status: no change.** There is no `releases` table and none is added. A release is a derived view (section 2.2). |
| 7 | Rollback failure is **fail-closed**: the pointer ends either on a restored release or on `NULL`; never on a release that failed. |

## 1. Vocabulary: three different things

### 1.1 Deployment status (`deployments.status`) — the lifecycle of ONE deployment attempt

| Status | Terminal | Served by the gateway | Meaning |
|---|---|---|---|
| `QUEUED` `POLICY_CHECK` `SECURITY_CHECK` `BUILDING` | no | no | pipeline steps (unchanged) |
| `DEPLOYING` | no | **yes today** (see LIM-1) | the lease is held, the pointer may already point at it, verification is not finished |
| `ROLLING_BACK` (**new, V30**) | no | **no** | the decision "this deployment must be undone" is durable; the lease holder (or the recovery sweeper after a crash) finishes the undo and never rolls forward again |
| `RUNNING` | yes | yes (when it is the pointer target) | deployed **and verified HEALTHY**. RUNNING does not mean "active": older RUNNING deployments stay RUNNING when a newer one is activated, they are the restorable releases |
| `FAILED` | yes | no | never became healthy. The `error` text carries the stable `FailureCode` and, if a rollback ran, its outcome (`rollback: restored …` / `ROLLBACK FAILED: …` / `site taken offline`). This is also the final status after a failed or successful auto-rollback |
| `ROLLED_BACK` | yes | no | this deployment **was active and an operator rolled back away from it** to an older release. Set in the same transaction as the pointer change |

Transitions (the only legal ones; `DeploymentStatus.allowed` must enforce them and `DeploymentRepository.transition` stays compare-and-set):

- `QUEUED → POLICY_CHECK → SECURITY_CHECK → BUILDING → DEPLOYING → RUNNING` (unchanged)
- any non-terminal, non-`ROLLING_BACK` → `FAILED` (unchanged)
- `DEPLOYING → ROLLING_BACK` (new: verification failed / unknown / deploy error after the switch was attempted)
- `ROLLING_BACK → FAILED` (the undo finished: previous release restored, or site taken offline)
- `RUNNING → ROLLED_BACK` (manual rollback away from the active release, only when the left release is **newer** than the target, i.e. `left.activation_seq > target.activation_seq`; a roll-forward leaves the left release RUNNING)
- no other edge. `ROLLED_BACK`, `FAILED`, `RUNNING` never go back to a pipeline status.

`terminal = {RUNNING, FAILED, ROLLED_BACK}` (**unchanged**). `ROLLING_BACK` is non-terminal, so existing pollers keep polling until `FAILED` and nothing spins forever. (A new terminal status such as `ROLLBACK_FAILED` would break `features/studio/drawers.tsx` `TERMINAL`, `packages/types`, `packages/ui` and `e2e/runtime-flow.mjs`: that is the reason it is rejected, besides being redundant, see 4.3.)

`deployments_status_check` is replaced by the same list plus `ROLLING_BACK`. The partial index `deployments_status_idx` (`WHERE status NOT IN ('RUNNING','FAILED','ROLLED_BACK')`) already covers `ROLLING_BACK`; keep it as is. `DeploymentRepository.staleIds` must include `ROLLING_BACK` among the in-progress statuses so a crashed undo is resumed.

### 1.2 Release status — derived, **not stored**

A release is a deployment that reached `RUNNING` at least once and owns an immutable artifact. Its state is computed:

- **ACTIVE** — `sites.current_deployment_id = deployments.id`
- **RESTORABLE** — `status = 'RUNNING'` and `artifact_id IS NOT NULL` and `artifacts.deleted_at IS NULL` and the artifact verifies (`StoredArtifactVerifier`)
- **NOT RESTORABLE** — everything else (`ROLLED_BACK`, `FAILED`, artifact removed or damaged). A `ROLLED_BACK` release is deliberately not restorable: to bring its content back, publish that version again (new deployment, new verification).

The **active release pointer** is `sites.current_deployment_id` (+ `pointer_version`). It is always either `NULL` (offline) or a deployment that is `RUNNING`, or the single deployment currently in `DEPLOYING` under the scope lease.

### 1.3 Event (`deployment_events.status`, free text ≤ 24, no CHECK) — history and diagnostics, never state

| Event | Written when | Why it is not a status |
|---|---|---|
| `SWITCH` | pointer is about to move to this deployment (message keeps the previous release; V30 also stores it typed in `previous_deployment_id`) | history |
| `ROLLBACK_OK` | a release was restored (auto or manual) | outcome of an operation on the scope, the deployment keeps its own status |
| `ROLLBACK_FAILED` | the restore could not happen (artifact missing/damaged, provider error) | a failed **attempt**; a manual attempt must not taint the target (still restorable later) nor the active release |
| `ROLLBACK_OFFLINE` | the pointer was set to `NULL`: either no earlier release existed (benign) or, after `ROLLBACK_FAILED`, fail-closed | describes the pointer state, not the deployment |
| `SCOPE_BUSY` (new) | the deployment waited because another operation owns the scope | diagnostic |
| `STALE_PUBLISH` (new) | the deployment lost to a newer intent and was failed | diagnostic |

A rule for every future status/event: **a status is added only when it changes what the deployment may do next or what the recovery sweeper must do**. `ROLLING_BACK` does (resume, never roll forward). The others do not.

## 2. Concurrency contract (exact)

### 2.1 Mechanism

1. **Ownership = a lease row.** Columns on the scope row (`sites`): `lease_operation_id`, `lease_kind`, `lease_deployment_id`, `lease_seq`, `lease_holder`, `lease_started_at`, `lease_until`. One operation per scope at a time. The lease is a **durable row state**, not a held transaction and not a connection-bound lock.
2. **Commit guard = optimistic version.** `sites.pointer_version` increases by 1 on every pointer change. Every pointer write is a compare-and-set that also proves the writer still owns the lease (fencing).
3. **Ordering of intents = `activation_seq`.** A global PostgreSQL sequence `deployment_activation_seq`. A deployment gets its number when the publish request is accepted; a rollback or unpublish gets one when it acquires the lease. `sites.active_seq` is the number of the last operation that moved the pointer.

Why not the alternatives (decision record):
- *Advisory lock*: session level locks vanish silently with a pooled connection and are invisible to other nodes and to diagnostics; transaction level locks would hold a transaction open across provider calls (up to 120 s + 60 s).
- *`SELECT … FOR UPDATE` alone*: same long-transaction problem; used only for the short lease statements below.
- *Version only (no lease)*: two workers would both run builds/provider calls and one would lose at the very end, after external side effects. The lease keeps the side effects serialized.
- *Global lock*: forbidden; the scope is one app in one environment.

### 2.2 Statements (normative; C2 may restructure code, not semantics)

Acquire (one statement, atomic under READ COMMITTED; `:ttl` = `app.deploy.scope-lease-seconds`, default 90; `:max` = `app.deploy.scope-lease-max-seconds`, default 900):

```sql
UPDATE sites s SET lease_operation_id = :op, lease_kind = :kind, lease_deployment_id = :dep, lease_seq = :seq, lease_holder = :worker,
       lease_started_at = CASE WHEN s.lease_operation_id = :op THEN s.lease_started_at ELSE now() END,
       lease_until = now() + make_interval(secs => :ttl)
 WHERE s.project_id = :project
   AND (s.lease_operation_id IS NULL OR s.lease_until <= now() OR s.lease_operation_id = :op)
   AND (s.lease_operation_id IS DISTINCT FROM :op OR now() < s.lease_started_at + make_interval(secs => :max))
RETURNING s.pointer_version, s.current_deployment_id, s.active_seq
```

0 rows = **BUSY** (read the holder for the message). The row for the project must exist first (`ensureSlug` already does `INSERT … ON CONFLICT DO NOTHING`).

Heartbeat every `ttl/3` while the operation runs: `UPDATE sites SET lease_until = now() + ttl WHERE project_id = :p AND lease_operation_id = :op AND lease_until > now() AND now() < lease_started_at + max`. 0 rows = the lease is **lost**: the worker stops at once (no further pointer or status write) and reports `ScopeLost`.

Pointer commit (the **only** way the pointer may change; replaces the blind `UPDATE sites SET current_deployment_id` in `SiteService.point/restore` and in `StaticSiteDeployProvider.deploy`):

```sql
UPDATE sites SET current_deployment_id = :new, pointer_version = pointer_version + 1, active_seq = :seq, updated_at = now()
 WHERE project_id = :p AND lease_operation_id = :op AND lease_until > now() AND pointer_version = :expectedVersion AND active_seq <= :seq
```

1 row = committed; 0 rows = the operation is fenced out (lost lease or someone else moved the pointer): nothing else is written for that operation. Status change and pointer change of one operation happen in **one transaction** (for example manual rollback: pointer CAS + `RUNNING → ROLLED_BACK` of the left release + event).

Release: `UPDATE sites SET lease_* = NULL WHERE project_id = :p AND lease_operation_id = :op` (always in `finally`; a crash relies on expiry).

### 2.3 Rules

- **R1 Single owner.** Publish (the `DEPLOYING` step), automatic rollback, manual rollback and unpublish all need the lease. `SiteControllers.unpublish` (today an unguarded `sites.point(projectId, null)`) is included.
- **R2 Publish ordering.** At lease acquisition a publish with `deployments.activation_seq <= sites.active_seq` is **stale**: it does not activate. It ends `FAILED` with code `STALE_PUBLISH` (permanent, not retried), event `STALE_PUBLISH`, message names the newer release. A publish requested *after* a rollback has a larger number and may activate (newer intent). A publish requested *before* a rollback began cannot overwrite it.
- **R3 Expectation.** For a queued publish the "expected active release" is not knowable at request time, so it is expressed by `activation_seq` (R2) and the commit CAS. For rollback and unpublish the client may send `expectedActiveDeploymentId` (optional, additive); if it differs from the pointer read at acquisition the answer is `409 ROLLBACK_STALE` and nothing changes. Without it the operation is relative to the pointer read under the lease.
- **R4 Busy is explicit and not a failure.** A publish that finds the scope owned does not fail: the processor returns to the queue (like `StepResult.Wait`) and retries with the existing backoff, writes one `SCOPE_BUSY` event, and gives up only after `app.deploy.scope-wait-seconds` (default 300) with `FAILED` code `SCOPE_BUSY` (retryable = true). A manual rollback or unpublish that finds the scope owned answers `409 SCOPE_BUSY` with `Retry-After` and the holder (`kind`, `deploymentId`, `since`).
- **R5 No race between publish and rollback.** They share the lease and the version: whichever acquires first completes its pointer write; the other sees BUSY or a changed `pointer_version`. A rollback never overwrites a publish that activated after the rollback's view (R3), a publish never overwrites a rollback that began after it was requested (R2).
- **R6 Idempotent retry.** The operation id is stable: publish = the deployment id; rollback / unpublish = a UUID derived from the `Idempotency-Key` (new optional header, stored in the existing `idempotency_keys`, `resource_type = 'SITE_OPERATION'`; same key with another body → `409 IDEMPOTENCY_KEY_REUSED`). The lease is re-entrant for the same operation id, so a retry or a redelivered queue message continues instead of colliding with itself. Every step is idempotent: target already active → `AlreadyActive`, `200` and no change.
- **R7 Recoverable.** If the worker dies the lease expires after `ttl` and the next acquirer takes over; the dead worker is fenced by the CAS. A deployment left in `DEPLOYING` or `ROLLING_BACK` is picked up by `DeploymentRecovery` and resumes: `DEPLOYING` is re-run (provider `deploy` and `verify` are idempotent); `ROLLING_BACK` goes straight to the undo, it never re-verifies and never rolls forward.
- **R8 Previous release under the lease.** `previous_deployment_id` (typed column, V30) is read and stored while the lease is held, in the same statement flow as the switch. The regex over a `SWITCH` event message (`JdbcReleaseStore.recordedPrevious`) is replaced by this column (events keep the text for humans).
- **R9 No global lock, no cross-scope blocking.** Other projects, other tenants and reads (`GET /site`, serving) are never blocked.

## 3. Rollback contract (exact)

### 3.1 Automatic rollback (a deployment N failed after the switch was attempted)

Under the lease already held by N:

1. `DEPLOYING → ROLLING_BACK` (CAS). Durable. From here only the undo may run.
2. If `previous` exists: verify its artifact (`ArtifactVerifier`), `provider.restore(project, previous)`, pointer CAS `N → previous`. Success → event `ROLLBACK_OK`.
3. If `previous` is `NULL` (first release): pointer CAS `N → NULL`, event `ROLLBACK_OFFLINE` (benign).
4. If the restore fails (artifact removed or damaged, provider error): event `ROLLBACK_FAILED` with the reason, then **fail-closed**: pointer CAS `N → NULL` and event `ROLLBACK_OFFLINE` ("fail-closed after a failed restore"). The site serves nothing rather than an unhealthy or unconfirmed release.
5. `ROLLING_BACK → FAILED` (CAS) with `error` = failure code + reason + rollback summary (the format Batch 1 already produces). Release the lease.
6. If step 4's pointer CAS itself cannot be written (database outage), the deployment stays `ROLLING_BACK` and the sweeper retries the undo; the gateway never serves it (`live()` serves `DEPLOYING` and `RUNNING` only).

Net result is deterministic: pointer = previous release, or `NULL`. Never a failed release.

### 3.2 Manual rollback (`POST …/site/rollback`)

Eligibility (unchanged): target is `RUNNING`, belongs to the project, has an artifact with `deleted_at IS NULL`. Under the lease:

1. read pointer; target already active → `AlreadyActive`, `200`, no change.
2. verify the target artifact; provider `restore`; pointer CAS `current → target` (`active_seq` = the operation's number).
3. same transaction: if `left.activation_seq > target.activation_seq` then `left: RUNNING → ROLLED_BACK`; event `ROLLBACK_OK` on the target.
4. On failure at step 2 (nothing was switched): **no status of any deployment changes** (the active release keeps serving and stays `RUNNING`; the target stays restorable). Event `ROLLBACK_FAILED` on the target, answer `409 ROLLBACK_FAILED` with the reason (as today).
5. If the provider restore succeeded but the pointer CAS did not (fenced): `409 SCOPE_BUSY` or `409 ROLLBACK_STALE`; the retry with the same `Idempotency-Key` converges (R6).

Unpublish uses the same lease and CAS; it writes pointer `NULL` and event `ROLLBACK_OFFLINE`-free history (`SITE_UNPUBLISHED` audit as today); no deployment status changes.

### 3.3 Server apps

`ServerRuntimeService` keeps its own blue/green (`server_deployments`, `rollback_of`). It is **not** changed by this contract. Finding F-6 (section 5) is the open item.

## 4. API contract (additive only)

- `POST …/site/rollback`: body adds optional `expectedActiveDeploymentId`; optional header `Idempotency-Key`. New error codes `SCOPE_BUSY` (409, `Retry-After`), `ROLLBACK_STALE` (409). `ROLLBACK_FAILED` (409) unchanged.
- `DELETE …/site`: optional `expectedActiveDeploymentId` (query) and `Idempotency-Key`; `409 SCOPE_BUSY` / `ROLLBACK_STALE`.
- `GET …/site` (`SiteInfo`): add `operation` = `null` or `{kind, deploymentId, since, leaseUntil}`, and `pointerVersion`.
- `POST …/publish`: unchanged request; a deployment may now end `FAILED` with `STALE_PUBLISH` or `SCOPE_BUSY`.
- Deployment status values: `ROLLING_BACK` added. Clients must treat unknown non-terminal statuses as "still running" (see handoff H-1).

## 5. Audit findings (real, from the code)

| ID | Where | Finding | Resolution |
|---|---|---|---|
| F-1 | `StaticSiteDeployProvider.deploy`, `SiteService.point` | pointer is written with a blind `UPDATE`: **last writer wins**; a slow older publish can overwrite a newer release | section 2 (lease + CAS + `activation_seq`) |
| F-2 | `ReleaseDeployer.deploy` | `previous` is read from `sites` without any guard, so it can be a release that another publish already replaced | R8 (read under the lease) |
| F-3 | `ReleaseDeployer.rollbackFailedDeployment` | check-then-act: reads `activeDeployment`, then restores; another operation can move the pointer in between | lease + pointer CAS |
| F-4 | `SiteControllers.rollback` / `unpublish` | no ownership, no expected version, no idempotency key; `unpublish` races any publish | R1, R3, R6 |
| F-5 | `JdbcReleaseStore.recordedPrevious` | previous release stored as free text in a `SWITCH` event and parsed with a regex | `previous_deployment_id` column (R8) |
| F-6 | `DeploymentProcessor.deployServerRuntime` + `ReleaseDeployer.deploy` | for server apps the runtime step runs after the site switch; if verification later fails only the site pointer is restored, `server_deployments` stays on the new version (site and server can be on different releases) | **C2 Batch 2**: on auto-rollback also roll the server runtime back through `ServerRuntimeService.rollback` (its existing `rollback_of`), idempotently; if the previous release has no server part, stop the new one. Not a contract change for C4 |
| F-7 | `DeploymentRecovery` / `DeploymentProcessor.run` | `ROLLING_BACK` does not exist; a crash between "decided to undo" and "undone" re-runs verification and could roll forward by chance | `ROLLING_BACK` (section 1.1, R7) |
| F-8 | `SiteService.live` | serves `DEPLOYING` and `RUNNING` deployments, so a release is **served before it is verified** for up to the verify timeout (`app.deploy.verify-timeout-seconds`, 60 s) | accepted limitation **LIM-1** for Batch 2; the real fix (candidate pointer: the old release serves until verification, then one CAS flip) is a Batch 3 topic and needs a `DeployProvider` amendment signed by C0 |
| F-9 | `StaticSiteDeployProvider.verify` | verifies the pointer and the presence and size of the stored files, not an HTTP response of the served site; `HEALTHY` proves storage, not serving | note for Batch 3 (HTTP probe); `StoredArtifactVerifier` checks sizes only, not per-file hashes (acceptable for Batch 2) |
| F-10 | `DeployProvider.restore(projectId, previous)` | no operation id and no expected version, so an external provider cannot fence a stale call | add `restore(projectId, target, operationId)` as a default method delegating to the old one (source compatible); providers that can fence must |
| F-11 | `DeploymentStatus.allowed` | `to == FAILED -> true` from any non-terminal state | keep, but `ROLLING_BACK → FAILED` is the only exit of `ROLLING_BACK` (add a test) |
| F-12 | `deployments_status_idx` | partial index predicate lists the three terminal statuses: stays correct with `ROLLING_BACK`; **would be wrong** if a new terminal status were added | another reason for not adding `ROLLBACK_FAILED` |

## 6. V30 (content of the future migration; a draft until C0 hands the number out, then normative)

`V30__deployment_rollback_and_scope_lease.sql`, additive, no data loss:

- `ALTER TABLE deployments DROP CONSTRAINT deployments_status_check; ADD CONSTRAINT deployments_status_check CHECK (status IN ('QUEUED','POLICY_CHECK','SECURITY_CHECK','BUILDING','DEPLOYING','ROLLING_BACK','RUNNING','FAILED','ROLLED_BACK'))`
- `CREATE SEQUENCE deployment_activation_seq`; `ALTER TABLE deployments ADD COLUMN activation_seq BIGINT`; backfill `row_number() OVER (ORDER BY created_at, id)`; `setval` to the max; then `SET NOT NULL` and `DEFAULT nextval('deployment_activation_seq')`.
- `ALTER TABLE deployments ADD COLUMN previous_deployment_id UUID REFERENCES deployments(id)`
- `ALTER TABLE sites ADD COLUMN pointer_version BIGINT NOT NULL DEFAULT 0, active_seq BIGINT NOT NULL DEFAULT 0, lease_operation_id UUID, lease_kind VARCHAR(16), lease_deployment_id UUID REFERENCES deployments(id), lease_seq BIGINT, lease_holder VARCHAR(64), lease_started_at TIMESTAMPTZ, lease_until TIMESTAMPTZ`; backfill `active_seq` from the current deployment's `activation_seq`.
- `CHECK (lease_kind IN ('PUBLISH','ROLLBACK','UNPUBLISH'))`; `CHECK ((lease_operation_id IS NULL) = (lease_until IS NULL))` and the same all-or-none for `lease_kind`, `lease_seq`, `lease_started_at`.
- Index `sites (lease_until) WHERE lease_operation_id IS NOT NULL` (diagnostics and expired-lease sweep).
- Tenant: no new tenant column (tenant is `projects.tenant_id`, the same as every V4/V13 table today); the "tenant resources" item of the ledger adds it to `sites`/`deployments` later. Every query C2 adds must still filter through `projects.tenant_id`.
- Undo `docs/parallel/c2/undo/U30__deployment_rollback_and_scope_lease.sql`: refuses (RAISE) if any deployment is `ROLLING_BACK` or any lease is held; otherwise restores the previous CHECK and drops the added columns and the sequence.
- Tests: a schema test (constraint accepts `ROLLING_BACK`, rejects anything else; lease all-or-none; backfill), undo-refusal test.

Config keys (all with the defaults above): `app.deploy.scope-lease-seconds=90` (minimum 30), `app.deploy.scope-lease-max-seconds=900`, `app.deploy.scope-wait-seconds=300`.

## 7. Tests C2 must deliver with Batch 2 (real PostgreSQL, no mocks for the lease)

1. Two publishes of the same app race: exactly one holds the lease; the other waits (`SCOPE_BUSY` event) and then either activates in order or ends `STALE_PUBLISH`; the pointer is never older than the newest activated intent.
2. Stale publisher: older `activation_seq` finishing after a newer one → `FAILED / STALE_PUBLISH`, pointer unchanged.
3. Publish racing a manual rollback, both orders; rollback with a wrong `expectedActiveDeploymentId` → `409 ROLLBACK_STALE`.
4. Lease expiry: a worker stops heartbeating; another takes over; the dead worker's pointer CAS affects 0 rows (fencing).
5. Idempotency: same `Idempotency-Key` twice → one effect, same answer; another body → `IDEMPOTENCY_KEY_REUSED`; queue redelivery of a deployment continues under its own lease.
6. Crash inside `ROLLING_BACK` (kill after the status write): sweeper resumes and ends `FAILED` with pointer = previous or `NULL`; never `RUNNING`.
7. Failed restore → events `ROLLBACK_FAILED` then `ROLLBACK_OFFLINE`, pointer `NULL`, deployment `FAILED`.
8. Manual rollback failure changes no deployment status; `ROLLED_BACK` only when moving to an older release.
9. Unpublish vs publish race.
10. Existing Batch 1 tests stay green unchanged.

## 8. Batch scope

- **Batch 2 (BLOCKED on the migration number only; design, status model and tests are final):** this contract (V30, lease, CAS, `ROLLING_BACK`, `ROLLED_BACK`, idempotent rollback), artifact lifecycle, rollback artifact protection (retention must never delete the artifact of an ACTIVE or RESTORABLE release, nor the previous release of a deployment in progress), orphan cleanup, `ArtifactStore.list` (only if it stays the existing storage contract: tenant/project prefix, no cross-project listing), retention correctness, F-6.
- **Batch 3: NOT YET** (candidate pointer / HTTP probe, F-8, F-9).

## 9. Gates and handoffs

- **Order gate (hard):** `spring.flyway.out-of-order` is off. V30 may be applied to an environment only after V29. Therefore the V30 file may live on `fix/c2-v3`, but it is imported into `integration/v2` **only after V29 is imported and Mac-verified**. Importing V30 first would make V29 unappliable on every database that already ran V30.
- **Ledger:** no ledger edit on this branch. When V29 is on `integration/v2` and its gate is ticked, C0 writes the V30 row in section 1 of `MIGRATION_LEDGER.md` (one number per commit), then tells C2. The decision text lives in `DECISIONS.md` as D-C0-26 on `wire/v29-run-persistence`; this file is its design annex.
- **What C2 may do before the number exists:** everything in Batch 2 that needs no schema (artifact lifecycle, rollback artifact protection, orphan cleanup, `ArtifactStore.list`, retention correctness, F-6), as separate commits on `fix/c2-v3`. The lease, `activation_seq`, `ROLLING_BACK` and `previous_deployment_id` wait for V30; code that depends on them must not be merged ahead of the migration.
- **Handoff H-1 → C5** (additive, not blocking Batch 2 code): add `ROLLING_BACK` to `packages/types` `DeploymentStatus`, a label and tone in `features/studio/drawers.tsx` / `packages/ui` (keep `TERMINAL = [RUNNING, FAILED, ROLLED_BACK]`; treat unknown non-terminal statuses as in progress); optional `expectedActiveDeploymentId`, `Idempotency-Key` on rollback/unpublish; handle `SCOPE_BUSY` (retry after) and `ROLLBACK_STALE` (reload); show `SiteInfo.operation`.
- **Handoff H-2 → C6**: the scenarios of section 7 as QA cases (concurrent publish, stale publisher, publish vs rollback, lease takeover, crash in `ROLLING_BACK`).
- **No change to C1, C3 or C4 contracts.** Permissions stay `PROJECT_PUBLISH`; tenant isolation unchanged (the scope is reached through `projects.tenant_id`); C4/C3 runtime (V28, V29) is not touched.
- **Batch 1 is not GREEN until the owner or C0 shows a green full Gradle run on `fix/c2-v3`** (see the C0 report of this batch for what was and was not run).
