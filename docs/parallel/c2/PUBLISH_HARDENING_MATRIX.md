# C2 — publish / deployment / release / rollback hardening matrix

Owner C2 · baseline `integration/v2 @ fad4a7b` · branch `fix/c2-publish-hardening` · 2026-10-10. **No production code changed**: every row below is proved by a test on the baseline; the gaps found were test gaps, and they are closed by `PublishHardeningMatrixTests` plus the authorization classes that had not reached integration yet (`PublishAuthorizationTests`, `PublishDeniedTests`, `APP_PUBLISH_AUTHORIZATION_AUDIT.md`).

| Requirement | Proved by |
|---|---|
| APP_PUBLISH on every user-triggerable route (publish, rollback, unpublish, server-runtime rollback / stop, domains) | `PublishAuthorizationTests` (14) |
| direct API without it denied; APP_VIEW only; APP_EDIT only; wrong tenant / project / forged ids; revoked permission with the same session; disabled user | `PublishAuthorizationTests`; `PublishDeniedTests` (7: nothing created on a pre-accept denial) |
| publish PRIVATE / PUBLIC, deployment progression, RUNNING, failure, retry | `PublishApiTests`, `DeploymentFailureRecoveryTests` (transient retry, permanent failure, verification), `PublicDataApprovalTests` (PRIVATE with data bound) |
| same request idempotency, same key same payload, same key different payload | `PublishApiContractTests` (`IDEMPOTENCY_KEY_REUSED`, replay header), `PublishApiTests`, `PublicDataApprovalTests` (E: a refusal keeps no key; an accepted key replays) |
| concurrent publish | `PublishHardeningMatrixTests` (4 truly simultaneous POSTs: all accepted, ordered by the scope, pointer = newest activation, one pointer move per activation, one artifact, no repeated step, no lease left), `PublishApiTests` (same key), `PublishScopeScenarioTests` (ordering) |
| stale publish | `PublishScopeScenarioTests`, `ReleaseCasTests` (`STALE_PUBLISH`), `PublishApiContractTests` (`REVISION_CONFLICT` at acceptance) |
| `PUBLISH_POLICY_MISMATCH` 409, `PUBLIC_DATA_NOT_APPROVED` 422 | `PublicDataApprovalTests` (11) |
| persisted publish approval | `PublicDataApprovalTests`; `PublishHardeningMatrixTests` (stored, kept by a refused update, reset by a policy change, audited without a secret) |
| rollback, rollback stale, duplicate rollback, unpublish | `SiteOperationApiTests` (`ROLLBACK_STALE`, `SCOPE_BUSY`, duplicate / concurrent same key), `ReleaseCasTests`, `PublishApiContractTests`, `PublishHardeningMatrixTests` |
| lease / CAS / fencing semantics | `ReleaseScopeLeaseTests`, `ReleaseCasTests`, `ScopeOrderingTests`, `PublishScopeScenarioTests` |
| recovery: worker restart, stuck deployment, duplicate delivery, retry, rollback after restart | `QueueRecoveryTests` (worker stopped, lost enqueue, died mid-pipeline), `PublishScopeScenarioTests` (died in DEPLOYING, taken over under a new fence), `DeploymentFailureRecoveryTests` (crash in ROLLING_BACK resumed), `PublishHardeningMatrixTests` (one deployment delivered as a duplicate queue message + the sweeper + three direct workers at once = one effect; a late delivery of a finished deployment changes nothing; rollback / duplicate rollback / unpublish / roll-forward after the consumers were stopped and started, no rebuild, no second effect) |

Canonical errors asserted over HTTP or in the pipeline: `PUBLISH_POLICY_MISMATCH`, `PUBLIC_DATA_NOT_APPROVED`, `SCOPE_BUSY`, `ROLLBACK_STALE`, `IDEMPOTENCY_KEY_REUSED`, `STALE_PUBLISH`, `REVISION_CONFLICT`, `DEPLOYMENT_NOT_RESTORABLE`.

## Findings

**P0: none.**

**P1 (decisions, not defects of this change)**
1. **A rollback does not read the publish policy** (documented in `PublishConfigModel`, pinned by `P1 pinned …`): after an app was published PUBLIC and its policy was later set to PRIVATE, rolling back to the earlier PUBLIC release serves it PUBLIC again (the stored policy stays PRIVATE). The deployment keeps the visibility it was accepted with. If the product wants a rollback to be judged by the current policy, that is a contract change for C0 (it also changes the "existing deployments are never judged again" rule); it is not made here.
2. **No stored policy = legacy**: a PUBLIC publish whose version binds data is accepted (the Public Runtime still serves no data without `public_data_approved`). Making a stored policy mandatory is a product decision.
3. The approval is a Boolean on the policy, not tied to a version (open since H-C2-07).
4. `PUT …/publish-config` without `requiresAuth` is `400 MALFORMED_REQUEST` (request-class quirk; the handoff tells C5 to send it).

**P2 / limits**
* "Process restart" is simulated at the seams the code recovers from: queue consumers stopped and started, a stale deployment, a duplicate message, an expired lease taken over under a new fence. No test kills the JVM.
* Under very heavy machine load (load average above 40) two test classes have shown infrastructure errors that never occur at normal load and never when run alone: `HikariPool … Connection is not available … This connection has been closed` (a Docker / Postgres test container dropping idle pooled connections) and the 3 s duplicate-wait timing of `SiteOperationApiTests`. Neither is reproducible and neither is a product defect that was found.
