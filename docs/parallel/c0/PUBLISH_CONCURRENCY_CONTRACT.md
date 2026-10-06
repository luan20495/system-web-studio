# Publish / rollback concurrency and status contract (C0 → C2)

Status: **contract, not implemented.** Written 2026-10-06 by C0 from the code of `fix/c2-v2 @ 1e6c561` (= the three Batch-1 commits on `integration/v2 @ f894cc6`, branch `fix/c2-v3`). Nothing here was compiled or run. Ownership of the paths: D-C0-25. **No migration number is allocated** (D-C0-24): the schema section is a draft for C2 to request after V29 is on `integration/v2` and its gate is ticked.

## 1. What the code does today (facts, with the file they come from)

* Release = deployment + its immutable artifact. There is no `releases` table. The **active release** is `sites.current_deployment_id` (V13; `sites` has one row per project, so today the only environment is "production"). History is `deployment_events` (V4, `status` has no CHECK). `deployments.status` has `deployments_status_check` = QUEUED, POLICY_CHECK, SECURITY_CHECK, BUILDING, DEPLOYING, RUNNING, FAILED, ROLLED_BACK. `DeploymentStatus.allowed()` lets only `RUNNING → ROLLED_BACK` leave a terminal status, and **nothing ever writes ROLLED_BACK**.
* `StaticSiteDeployProvider.deploy` = `SiteService.point(project, deployment)`: an unconditional `UPDATE sites SET current_deployment_id = ?`. `restore` = `SiteService.restore`: the same, plus the visibility, in one transaction. `DELETE …/site` (unpublish) is `point(project, null)`.
* `ReleaseDeployer.deploy` reads `activeDeployment` once (`previous`), calls `provider.deploy`, the optional server-runtime step, then `provider.verify`; any failure after the switch calls `rollbackFailedDeployment`, which restores `previous` only if the site still points at the failed deployment. `restoreRelease` (manual rollback) reads the pointer, then calls `switchTo`. Both are check-then-act with no guard on the write.
* The rollback endpoint is `@Transactional(noRollbackFor = ApiException)` around those provider calls (up to `deployTimeoutMs` = 120 s).
* Publish idempotency exists: `Idempotency-Key` → `idempotency_keys` (scope `publish:<project>:<user>:<key>`) → the deployment id. Rollback has none.

## 2. Audit of `DeployProvider.verify` / `restore` and the release lifecycle (real mismatches)

| # | Mismatch | Effect | Owner of the fix |
|---|---|---|---|
| M1 | `restore(projectId, previousDeploymentId)` and `restoreRelease` do not say what the pointer is expected to be when they write. | A rollback can overwrite a publish that switched between the read and the write. Rollback can lose a race it must not lose. | C2 (`DeployProvider` signature, `SiteService`) |
| M2 | `deploy` → `point` is last-writer-wins; two publishes of one project run DEPLOYING in parallel. | Whoever switches last wins, whichever was newer. | C2 |
| M3 | The automatic rollback target `previous` is not required to be restorable (the manual path requires `RUNNING`). With publishes A then B in parallel, B's `previous` can be A; if A later fails, B's failure restores A, a FAILED release. `SiteService.live` serves only DEPLOYING/RUNNING, so the pointer then names a release that is not served. | The site is silently offline while the pointer looks valid. | C2 |
| M4 | `ROLLED_BACK` is declared but never written, and a terminal status cannot leave it. A manual rollback leaves the replaced release `RUNNING`; roll-forward to it works only because it was never marked. | The status column cannot say "this release was undone". | C2 + schema (§5) |
| M5 | No persisted "rolling back" state. A worker that dies after `restore` but before the FAILED transition leaves the deployment in DEPLOYING; the stale sweeper re-runs `run()`, which runs DEPLOYING again and can switch the site back to the release that was just rolled back. | After a crash the outcome depends on timing, not on what was done. | C2 + schema |
| M6 | Unpublish (`DELETE …/site`) writes the pointer without any guard or event. | Can race a publish or rollback. | C2 |
| M7 | `verify` for the static provider = "the pointer is this deployment" + "every artifact file is in the store" (metadata). It does not request the address through the sites gateway and does not know whether it still holds the right to write. | HEALTHY means "recorded and stored", not "served". Acceptable only if stated so. | C2 (documented limit now; gateway probe later) |
| M8 | The previous release of a switch is kept as free text in a `SWITCH` event and parsed with a regex (`[uuid]`). | Durable state in a message string. | C2 + schema (a column) |
| M9 | `MockDeployProvider.restore` succeeds and does nothing, and nothing sets the pointer for the mock, so `activeDeployment` is always null there. | Rollback semantics are only exercised by the static provider. Labelled mock; low. | C2 (tests) |
| M10 | The manual rollback runs provider calls (artifact verification, restore; up to 120 s) inside one DB transaction. | A pooled connection is held for the whole call. | C2 |
| M11 | Server apps: `afterSwitch` calls `ServerRuntimeService.deploy`; `restoreRelease` restores the site pointer only. `server_deployments.rollback_of` and `ServerRuntimeService.rollback` exist but the release rollback does not call them. | After a rollback of a SERVER_APP the site and the isolated runtime can be different releases. **Not verified by running; read from the code.** Needs `runtime/**`, which is not delegated: C0 decides how the two are coordinated. | C0 (contract) → C2 |
| M12 | A rollback target is verified (record, checksum, files) only at rollback time; nothing protects it from retention in between. | A release can be unrestorable when it is needed. | C2, Batch 2 (retention) |

No mismatch changes a C1, C3, C4 or C5 contract today. C5 will see two new deployment statuses and two new error codes (§5, §6) and must render them: handoff to C5 when the schema is numbered.

## 3. Concurrent publish contract

**Scope** = `(tenantId, appId, environment)`. The tenant is `projects.tenant_id`; the app is `sites.project_id`; `environment` is the constant `PRODUCTION` until a second environment exists (the `sites` row is the scope row, so no new column or table for the scope itself).

**Mechanism: a lease on the scope row + optimistic version on the pointer.** A database advisory lock or a long transaction is rejected because DEPLOYING can run for minutes (provider call, server runtime, verification) and a connection must not be held that long. BUILDING stays fully parallel; only the switch is serialised.

* `sites.version BIGINT NOT NULL DEFAULT 0`: increments on every change of `current_deployment_id` (the compare-and-set token).
* `sites.lease_owner VARCHAR(64)`, `lease_operation_id UUID`, `lease_kind VARCHAR(16)` (PUBLISH | ROLLBACK | UNPUBLISH), `lease_until TIMESTAMPTZ`, all NULL or all set.
* `sites.last_change_kind VARCHAR(16)` (PUBLISH | ROLLBACK | UNPUBLISH | ROLLBACK_AUTO).
* `deployments.base_version BIGINT`: `sites.version` observed when the publish was accepted ("publish starts with an expected version").
* `deployments.previous_deployment_id UUID`: replaces the regex over the `SWITCH` event (M8).

Rules:

1. **Acquire.** `UPDATE sites SET lease_owner=:w, lease_operation_id=:op, lease_kind=:k, lease_until=now()+:ttl WHERE project_id=:p AND (lease_until IS NULL OR lease_until < now() OR lease_operation_id = :op)`. One row = owned. The same `op` may re-enter (resume after a restart). No row = the scope is busy.
2. **Operation id.** Publish: the deployment id. Rollback and unpublish: the client's `Idempotency-Key` (stored in `idempotency_keys` with `resource_type` ROLLBACK / UNPUBLISH, like publish). A repeated request with the same key and the same body answers the recorded outcome and changes nothing; the same key with another body is `409 IDEMPOTENCY_KEY_REUSED`.
3. **TTL and recovery.** TTL 120 s, renewed by the holder at a third of it while it works (`WHERE lease_owner=:w AND lease_operation_id=:op`). A holder that died stops renewing; after expiry another operation may acquire, and a sweeper may resume the dead one by its operation id. No global lock exists anywhere.
4. **Write fence.** Every write of the pointer is `UPDATE sites SET current_deployment_id=:d, version=version+1, last_change_kind=:k WHERE project_id=:p AND lease_operation_id=:op AND lease_owner=:w AND version=:v`. A holder whose lease expired and was taken over fails this statement, so a zombie can never activate or restore.
5. **Publish ordering (stale publisher).** A publish may activate only if `sites.version = deployment.base_version`, **or** every change since then was a PUBLISH of an older project version (the release it would replace has a lower `version_number`): it may rebase. Any intervening ROLLBACK, UNPUBLISH or a publish of an equal or newer version makes it stale: it ends `FAILED` with code `STALE_BASE` (not retryable, nothing was switched, so no rollback is needed). A publish that started before a rollback therefore cannot undo that rollback.
6. **Rollback and publish exclude each other.** While a ROLLBACK holds the lease a publish waits (`SCOPE_BUSY`, retried with the bounded backoff already in `RetryPolicy`; the event says so); while a PUBLISH holds it a manual rollback answers `409 SCOPE_BUSY` with `Retry-After`. A rollback cannot overwrite a newer publish because it needs the lease and the version it read under the lease.
7. **Conflict is explicit.** `409 SCOPE_BUSY` (another operation holds the lease), `409 STALE_BASE` (the site changed since the publish started), `409 ROLLBACK_FAILED` (as today). Never a silent last-writer-wins.
8. **Short critical sections.** The lease covers switch + server step + verification + finalisation. Reading and artifact verification happen under the lease but outside a DB transaction; each pointer write is its own short transaction (`SELECT … FOR UPDATE` on the `sites` row is allowed there).

## 4. Rollback contract

* **Automatic** (deploy of X failed after the switch): under the lease, restore `deployments.previous_deployment_id` of X only if that release is `RUNNING` (or `ROLLED_BACK`, i.e. restorable) and its artifact verifies (M3). If there is none, the site is taken offline on purpose (`ROLLBACK_OFFLINE`). Idempotent: running it again after a crash converges on the same state.
* **Manual** (`POST …/site/rollback`, also roll-forward): the target must be `RUNNING` or `ROLLED_BACK` with a non-deleted artifact; verification before the switch; `AlreadyActive` changes nothing and writes nothing.
* **Server apps** (M11): until C0 decides, a rollback of a SERVER_APP is **not** declared consistent. Contract proposal: the release rollback calls `ServerRuntimeService.rollback(projectId, to, user)` inside the same lease and the rollback is `ROLLBACK_FAILED` if the runtime step fails.
* **Pointer determinism.** `sites.current_deployment_id` is NULL (offline) or a deployment of that project. The site is **served** iff the pointer's deployment status is DEPLOYING or RUNNING (`SiteService.live`). After `ROLLBACK_FAILED` the pointer is left at the failed deployment, whose status is not served: the site is offline, visibly, and an operator can roll back manually or republish. No state exists in which the pointer names a release that is served but unverified.

## 5. Status model: three different things

| Kind | Where | Values | Terminal? |
|---|---|---|---|
| **Event** (what happened; append-only; no CHECK) | `deployment_events.status` | the step statuses, `SWITCH`, `ROLLBACK_OK`, `ROLLBACK_OFFLINE`, `ROLLBACK_FAILED`; (`SWITCH_STALE` optional) | n/a |
| **Deployment status** (state of one deployment) | `deployments.status` | existing QUEUED, POLICY_CHECK, SECURITY_CHECK, BUILDING, DEPLOYING, RUNNING, FAILED, ROLLED_BACK; **add** `ROLLING_BACK`, `ROLLBACK_FAILED` | FAILED, ROLLBACK_FAILED: terminal, never restorable. RUNNING, ROLLED_BACK: terminal for the pipeline, **restorable**. ROLLING_BACK: non-terminal. |
| **Release status** (is a release usable) | derived, no column | active = `sites.current_deployment_id`; restorable = deployment RUNNING or ROLLED_BACK **and** artifact not deleted **and** verifies | n/a |

Meaning and transitions:

* `ROLLING_BACK`: this deployment is being undone, under the lease. Automatic: `DEPLOYING → ROLLING_BACK → FAILED` (rollback ok or offline) or `→ ROLLBACK_FAILED` (could not restore). Manual: the release being replaced goes `RUNNING → ROLLING_BACK → ROLLED_BACK` (or back to `RUNNING` if the switch failed and nothing changed). It is what makes M5 recoverable: a stale `ROLLING_BACK` whose lease expired is resumed by the sweeper by finishing the rollback, never by deploying again.
* `ROLLED_BACK`: a release that was active and was deliberately replaced by a rollback. It stays restorable: `ROLLED_BACK → RUNNING` when it is activated again (roll-forward). `DeploymentStatus.allowed()` must allow `RUNNING → ROLLING_BACK → ROLLED_BACK` and `ROLLED_BACK → RUNNING`.
* `ROLLBACK_FAILED`: the deployment failed after the switch and the previous release could not be made active. The `error` says why (classified), the site is offline. Needs an operator.
* `ROLLBACK_OFFLINE` stays an **event** only: the resulting deployment status is FAILED and the site is offline by design. `ROLLBACK_OK` and `ROLLBACK_FAILED` events remain for history; the status is the queryable truth.
* No release status column and no `releases` table: a second source of truth for what `sites` + `deployments` + `artifacts` already say.

## 6. Schema draft (to request after V29 is imported; NOT a migration, NOT numbered)

* `ALTER TABLE sites ADD COLUMN version BIGINT NOT NULL DEFAULT 0, ADD COLUMN lease_owner VARCHAR(64), ADD COLUMN lease_operation_id UUID, ADD COLUMN lease_kind VARCHAR(16), ADD COLUMN lease_until TIMESTAMPTZ, ADD COLUMN last_change_kind VARCHAR(16)` + checks (lease columns all-or-none; `lease_kind` and `last_change_kind` value lists).
* `ALTER TABLE deployments ADD COLUMN base_version BIGINT, ADD COLUMN previous_deployment_id UUID REFERENCES deployments(id)`.
* Replace `deployments_status_check` with the ten values of §5 and `deployments_status_idx` so that its `NOT IN` list also excludes `ROLLBACK_FAILED` (and keeps `ROLLED_BACK`). Widening only; no row changes.
* No new table, so no new `tenant_id` column (the scope's tenant is `projects.tenant_id`). Undo: guarded like U27/U29 (refuses while a row uses a new status or a lease is held). The widening of `deployments_visibility_check` (TENANT / PRIVATE_LINK) stays a separate item of the ledger.

## 7. Batch 2 gate (C2)

Authorised only when all five hold: (1) baseline strategy approved (done: `fix/c2-v3`); (2) official branch exists; (3) **a migration number is allocated** (not yet: D-C0-24); (4) this concurrency contract accepted by C2; (5) this rollback/status contract accepted by C2. Until (3), C2 may implement what needs no schema (the audit items M3, M6, M9, M10, M12, `ArtifactStore.list` if it fits, tests) and may keep the schema as a draft.
