> **SUPERSEDED_BY:** `docs/PUBLISH_RUNTIME.md` - historical document, kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# C2 — Batch 2 (build / publish / deploy): what changed, what is not done

Branch `fix/c2-v3`. No migration was created or chosen. Written for C0 (integration) and whoever picks up the concurrency work.

## 1. What changed (backend/src/main/kotlin/com/systemwebstudio)

| Area | Change |
|---|---|
| Order of a release | verify artifact → `stage()` → server runtime healthy → switch the site → confirm. Was switch → verify. `publish/ReleaseDeployer.kt` |
| Verify / restore race (TOCTOU) | the artifact is checked before the switch, again by the static provider right before the pointer moves, and again after it; a failure after the switch is compensated, never reported as success |
| Server runtime = same release as the site | `publish/RuntimePlane.kt`: the runtime moves first (slow, fallible), the site pointer last (instant, local). A runtime that fails or times out is abandoned (`ServerRuntimeService.abandon`) so a late runner cannot start it |
| Failure semantics | automatic rollback failure → site pointer NULL, deployment FAILED; manual rollback failure → the state it started from is restored; static and runtime that cannot be reconciled → `RollbackResult.Failed(inconsistent = true)`, site offline, never a rollback "success" |
| Artifact lifecycle | `ArtifactStore.list`; orphan identification and cleanup (`ArtifactRetentionService.cleanOrphans`); retention soft-deletes the record first, objects second; a rebuild of content whose artifact was deleted revives it (it used to return a row with no files) |
| Retention protection | also keeps: the release right before the served one (rollback target), artifacts of deployments still building/publishing, the current/desired server runtime release; nothing younger than 1 h |
| Concurrency (shape only) | `publish/ReleaseScope.kt`: `ReleaseScopeGuard`, `ScopeLease(fencingToken)`, `SCOPE_BUSY`; publish, rollback and unpublish go through `ReleaseService`; default `PassThroughScopeGuard` excludes nothing |
| Previous release | still read from the `SWITCH` event text, now accepted only if it is another deployment of the same app |
| Health probe | `ReleaseHealthProbe` port + `HttpReleaseHealthProbe` (default OFF), wired into `StaticSiteDeployProvider.verify` |

## 2. Known limits (not hidden)

- Artifact verification compares **sizes** from a store `stat`, not the bytes; the manifest checksum is verified, the per-file checksum is not re-read on the publish path.
- The orphan sweep and the soft-delete guard close the retention-vs-revive window to a single statement, not to zero.
- `ServerRuntimePlane` waits for the runner by polling `server_deployments` (`app.deploy.runtime-timeout-seconds`, default 120). A publish of a server app without a running runner now FAILS after that time instead of reporting success.
- Manual rollback / unpublish audit actor for the server-runtime redeploy is the system (the controller still audits `SITE_ROLLBACK` with the user).
- `project/ProjectLifecycle` still takes a site offline directly (`sites.point(projectId, null)`); it is not behind the guard (not a C2 file).

## 3. Concurrency contract — implemented with V30 (C2_DEPLOY_CONTRACT.md, D-C0-24 / D-C0-27)

Scope `(tenantId = projects.tenant_id, appId = projects.id, PRODUCTION)` = the `sites` row. `V30__deployment_rollback_and_scope_lease.sql` + `c2/undo/U30__…` (refuses while a deployment is `ROLLING_BACK` or a lease is held).

| Contract item | Where |
|---|---|
| lease row, TTL 90 s, heartbeat 30 s, max 900 s, takeover of an expired lease | `publish/JdbcScopeGuard.kt` (every statement is its own `REQUIRES_NEW` transaction) |
| fencing token | `sites.fence_counter` / `lease_fence`: every acquisition draws the next value (a takeover, and also the same operation taking the scope again); heartbeat, release and pointer commit check the token the scope currently holds |
| active pointer CAS | `PointerFence.commit` (pointer_version + lease + token + order of intents); `StaticSiteDeployProvider` moves the pointer only through it and refuses without a fence; `SiteService.point` (unfenced, used by the project-archive path) bumps `pointer_version` so any operation in flight is fenced out |
| publish busy → wait ≤ 300 s | `DeployOutcome.Busy` → one `SCOPE_BUSY` event, re-queued after `app.deploy.scope-retry-ms` (2 s) with the recovery sweeper as safety net (no worker is held), then FAILED `SCOPE_BUSY` |
| stale publish | FAILED `STALE_PUBLISH` + event |
| rollback / unpublish busy | `409 SCOPE_BUSY` + `Retry-After`; `expectedActiveDeploymentId` → `409 ROLLBACK_STALE`; `Idempotency-Key` (`idempotency_keys`, `SITE_OPERATION`); `SiteInfo.pointerVersion` / `operation` |
| `ROLLING_BACK`, `ROLLED_BACK`, typed previous release | `DeploymentStatus.allowed`, `JdbcReleaseStore` (`deployments.previous_deployment_id`, `markRolledBackIfNewer` in the pointer's transaction), processor resumes a crashed `ROLLING_BACK` |

### Clarifications decided with C0 (R2) and where this implementation goes beyond the contract text

1. **R2 is not `activation_seq <= active_seq ⇒ stale`.** `seq < active_seq` = `STALE_PUBLISH`; `seq > active_seq` = may go on if lease, fencing token and CAS are valid; `seq == active_seq` = resumes only for the SAME operation (`sites.active_operation_id` = the operation id), otherwise refused. Same-operation takeover keeps the activation number and gets a NEW fencing token. For this V30 adds `sites.active_operation_id` and `sites.fence_counter` / `lease_fence` (the contract text only has `pointer_version`, `active_seq`, `lease_*`). `ScopeOrdering` (pure) and `ReleaseScopeLeaseTests` pin it.
2. **Rollback / unpublish do not re-enter a LIVE lease of their own operation** (a publish may, as the contract says for redelivery). A duplicate request of the same Idempotency-Key waits (≤ `app.deploy.scope-duplicate-wait-seconds`, default 60) for the first, then runs as the retry that finds the work done (`AlreadyActive`). An expired lease is taken over at once by anyone.
3. **Fail-closed rollback writes two events** (`ROLLBACK_FAILED` then `ROLLBACK_OFFLINE`), as contract §3.1 step 4; the deployment ends `FAILED`, pointer NULL.
4. **A worker that lost the scope stops writing**: the same operation took over → silent (`DeployOutcome.Lost`), another operation → `FAILED / STALE_PUBLISH`.
5. Config keys (code defaults, no `application.yml` edit — that file is C0's): `app.deploy.scope-lease-seconds=90` (min 30), `app.deploy.scope-lease-max-seconds=900`, `app.deploy.scope-wait-seconds=300`, `app.deploy.scope-retry-ms=2000`, `app.deploy.scope-duplicate-wait-seconds=60`.

### Runtime config key (asked by C0)

`app.sites.data-api-base` → `apiBase` in `__factory/config.json`, together with `releaseId`. Read **once when the API process starts** (not per request; a change needs a restart; corrected 2026-10-07), never baked into an artifact; blank / relative / non-http(s) / credentials / fragment ⇒ `null` (no address is invented). **Deviation from the proposed payload:** `environment` stays `"preview" | "production"` (lower case) because `@company/app-sdk` types it so; the scope's `PRODUCTION` is internal. Real HTTP probing stays BLOCKED (no public host).

### Known limits of the concurrency work

- A lease lost AFTER the server runtime was moved but before the site pointer was written is not compensated by the loser (the new owner of the scope owns the reconciliation; `RuntimePlane.serve` is idempotent by artifact so its next runtime step converges).
- A publish that lost the scope after its pointer CAS succeeded ends FAILED / STALE_PUBLISH while the pointer may still name it until the new owner moves it; `live()` serves `DEPLOYING` and `RUNNING` only, so a FAILED deployment is not served.
- `ProjectLifecycle` (not a C2 file) still takes the site offline with the unfenced `SiteService.point`; it fences operations out but is not itself behind the guard.
- LIM-1 (C0 F-8, a release is served while `DEPLOYING` until verified) is unchanged for the post-switch confirmation; the pre-switch verification is the Batch 2 mitigation. The candidate-pointer flip is Batch 3.

### Update 2026-10-07 (audit of the published runtime)

`PUBLISH_API_CONTRACT.md` (for C5), `PUBLISHED_RUNTIME_TOPOLOGY.md` (for C3 / C0 / C1) and `RELEASE_ENVIRONMENTS.md` (Batch 3) hold the audited contract, topology and readiness findings. Code changes made by the audit: the runtime config `version` is the served release's (it was the project's latest), artifacts up to 16 MiB are re-read and hashed before activation (`app.deploy.verify-bytes-limit`), structured logs for scope / pointer / step durations, no loopback literals left in `publish/**`, and `PublishApiContractTests` pin the HTTP contract.
