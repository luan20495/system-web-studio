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

## 3. Concurrency contract — what is needed from C0 (not implemented)

Scope `(tenantId, appId, PRODUCTION)`. Contract from C0: lease + fencing token, CAS on the active pointer, stale publish → FAILED / STALE_PUBLISH, publish waits ≤ 300 s, rollback/unpublish busy → 409 SCOPE_BUSY, TTL 90 s, heartbeat 30 s, max lease 900 s, same Idempotency-Key converges, a dead worker's lease is taken over.

Code is ready for it: implement `ReleaseScopeGuard` (replace `PassThroughScopeGuard`), pass `lease.fencingToken` into the pointer update (`SiteService.point`) as a CAS condition. Schema needed (one version, tenant-scoped like V26):

- a lease table keyed by the scope (holder, fencing token, expiry, operation, idempotency key);
- a fencing column next to `sites.current_deployment_id`;
- `deployments.previous_deployment_id` (typed replacement for the `SWITCH` event text).

Requested in `BOARD.md` (Migration requests) without a number.
