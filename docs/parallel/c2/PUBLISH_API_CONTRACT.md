# C2 — publish / site / rollback / unpublish: the HTTP contract as the code behaves (for C5)

Audited at `fix/c2-v3 @ 1ab26f9` plus the audit commits of 2026-10-07. Everything below is read from the code and **pinned by `PublishApiContractTests`** (real controllers, PostgreSQL, MinIO); a change to any line is a test failure. Nothing here is a proposal.
Common to every route: normal session cookie `STUDIO_SESSION` + CSRF header `X-XSRF-TOKEN` on non-GET; project permission `PROJECT_PUBLISH` for publish / rollback / unpublish (read access for GET); every error body is the standard envelope `{"code","message","requestId","details"}` — **there is no `retryable` field on these routes** (that field exists only on the `app-runtime` routes of C0's runtime-api contract). JSON `null` fields are present (not omitted); instants are ISO-8601 UTC strings; ids are UUID strings.

## 1. Publish

| | |
|---|---|
| Method / path | `POST /api/v1/workspaces/{workspaceId}/projects/{projectId}/publish` |
| Path params | `workspaceId`, `projectId` (UUID) |
| Query params | none |
| Headers | `Idempotency-Key` **required**, 8–120 chars of `A-Z a-z 0-9 _ . : -`; `X-XSRF-TOKEN` |
| Request | `{"visibility":"PRIVATE"\|"PUBLIC","expectedRevision":<long>}` — both required |
| Success | **`202 Accepted`**, body = deployment (below). **Asynchronous**: the work is done by a worker; poll `GET …/deployments/{id}` |
| Replay | same key + same payload (same user, same project) → `202`, header `Idempotent-Replay: true`, the ORIGINAL deployment |
| Poll | `GET /api/v1/workspaces/{workspaceId}/projects/{projectId}/deployments/{deploymentId}` → `200` deployment; `GET …/deployments` → latest 50 |

Deployment JSON (exact key set): `id, projectId, versionId, versionNumber (int), visibility, status, url (null until RUNNING), error (null unless FAILED), provider ("static"|"mock"), createdAt, updatedAt, finishedAt (null until terminal), events:[{status,message,createdAt}], mock (boolean)`. `error` is `"[CODE] reason"` (e.g. `[STALE_PUBLISH] …`), `events` is the history in order (it also carries the **event-only** names listed in §4).

Publish errors: `400 MISSING_HEADER` (no `Idempotency-Key`), `400 INVALID_IDEMPOTENCY_KEY`, `400 VALIDATION_FAILED` (`details.fields`), `400 MALFORMED_REQUEST`, `403 FORBIDDEN`, `403 PUBLIC_PUBLISH_DISABLED`, `403 CODE_APP_PUBLIC_DISABLED`, `404` (project not visible to the caller), `409 IDEMPOTENCY_KEY_REUSED` (same key, other payload), `409 IDEMPOTENCY_IN_PROGRESS`, `409 REVISION_CONFLICT` (`details.currentRevision`), `409 NO_VERSION`, `429 RATE_LIMITED` (`app.rate-limit.publish-max`, default 10 / minute / user).

## 2. Rollback

| | |
|---|---|
| Method / path | `POST /api/v1/workspaces/{workspaceId}/projects/{projectId}/site/rollback` |
| Headers | `Idempotency-Key` **optional** (same format; without it every request is its own operation) |
| Request | `{"deploymentId":"<uuid>" (required), "expectedActiveDeploymentId":"<uuid>" (optional)}` |
| Success | **`200`**, body = SiteInfo (§4). **Synchronous** (returns when the site really serves the restored release). A target that is already active also answers `200` and changes nothing |
| Errors | `400 DEPLOYMENT_NOT_RESTORABLE` (target not `RUNNING` with a live artifact of this project; a `ROLLED_BACK` release is not restorable — publish it again), `400 VALIDATION_FAILED`, `400 INVALID_IDEMPOTENCY_KEY`, `403 FORBIDDEN`, `404 SITE_NOT_FOUND`, `409 ROLLBACK_FAILED` (message `The release could not be restored: <reason>`; nothing changed), `409 SCOPE_BUSY`, `409 ROLLBACK_STALE`, `409 IDEMPOTENCY_KEY_REUSED` |

`expectedActiveDeploymentId` is the release the client believes is active: another active release → `409 ROLLBACK_STALE`, nothing changes — **except** when the target is already the active release (a retry that already succeeded answers `200`).

## 3. Unpublish

`DELETE /api/v1/workspaces/{workspaceId}/projects/{projectId}/site?expectedActiveDeploymentId=<uuid>` — **no body**; query param optional; header `Idempotency-Key` optional.
`200` + SiteInfo (`online:false`, `currentDeploymentId:null`, `slug` kept). **Already offline → `200`, nothing is written** (`pointerVersion` unchanged, no audit record). A project that never had a site → `200` with a SiteInfo whose fields are null. Errors: `403 FORBIDDEN`, `409 SCOPE_BUSY`, `409 ROLLBACK_STALE` (expected release is not the active one), `409 IDEMPOTENCY_KEY_REUSED`, `400 INVALID_IDEMPOTENCY_KEY`. Deployments and artifacts are kept (the site can be served again with rollback); no deployment status changes.

## 4. SiteInfo — `GET /api/v1/workspaces/{workspaceId}/projects/{projectId}/site` (also the body of rollback / unpublish)

```
{"slug":string|null, "url":string|null, "online":boolean, "visibility":"PUBLIC"|"PRIVATE"|null, "currentDeploymentId":uuid|null,
 "currentVersionNumber":int|null, "provider":"static"|"mock", "updatedAt":instant|null,
 "pointerVersion":integer, "operation":null | {"kind":"PUBLISH"|"ROLLBACK"|"UNPUBLISH","deploymentId":uuid|null,"since":instant,"leaseUntil":instant}}
```
* `pointerVersion` — Kotlin `Long`, non-null (default 0), JSON integer; `+1` on every change of the active pointer. **Observability / change detection only: no request accepts it.** A rollback guards with `expectedActiveDeploymentId`, an unpublish with the query param of the same name.
* `operation` — Kotlin `SiteOperation?`; the key is **always present**; **idle = JSON `null`**; non-null only while another release operation holds the scope **and its lease is alive** (an expired lease is shown as `null`). `deploymentId` is non-null only for `PUBLISH`. Value set of `kind`: exactly `PUBLISH`, `ROLLBACK`, `UNPUBLISH` (`ReleaseOperation`).
* `online` = the pointer is set **and** its deployment is `DEPLOYING` or `RUNNING`.

## 5. Deployment status (closed list; `deployments_status_check`)

| status | terminal | busy / non-terminal | servable (gateway serves it when the pointer targets it) | retryable |
|---|---|---|---|---|
| `QUEUED` | no | **yes** | no | n/a (re-queued automatically by the recovery sweeper) |
| `POLICY_CHECK`, `SECURITY_CHECK` | no | **yes** | no | no (a rejection is permanent) |
| `BUILDING` | no | **yes** | no | **conditional**: transient render / store / database failures are retried up to `app.deploy.step-max-attempts` (3) with backoff, then `FAILED` |
| `DEPLOYING` | no | **yes** | **yes** (known limit LIM-1: served from the switch until confirmed) | **conditional**: before the switch yes (same rule as BUILDING); once the switch was attempted **never** (it goes `ROLLING_BACK`). May wait for the scope (`SCOPE_BUSY` event) up to 300 s |
| `ROLLING_BACK` | no | **yes** | **no** | **no** — the undo is finished (also after a crash), never rolled forward; leaves only to `FAILED` |
| `RUNNING` | **yes** | no | **yes** (when it is the pointer target; older RUNNING releases are the restorable ones) | n/a |
| `FAILED` | **yes** | no | no | the deployment itself is never retried; the user publishes again (a NEW deployment) |
| `ROLLED_BACK` | **yes** | no | no | **no** — not restorable; publish that version again |

`terminal = {RUNNING, FAILED, ROLLED_BACK}` is **unchanged** by V30, so existing pollers stop on the same set. **`ROLLBACK_FAILED` and `ROLLBACK_OFFLINE` are EVENT-ONLY** (`deployment_events.status`), never deployment statuses (the CHECK constraint does not list them). Events a client may see in `events[]`: the statuses above plus `SWITCH`, `ROLLBACK_OK`, `ROLLBACK_FAILED`, `ROLLBACK_OFFLINE`, `SCOPE_BUSY`, `STALE_PUBLISH`, `ROLLED_BACK`.
Failure codes inside `error` (`[CODE] …`): `POLICY_REJECTED, SECURITY_REJECTED, BUILD_FAILED, ARTIFACT_MISSING, RENDER_UNAVAILABLE, ARTIFACT_STORE_UNAVAILABLE, DATABASE_UNAVAILABLE, STEP_TIMEOUT, DEPLOY_FAILED, DEPLOY_TIMEOUT, DEPLOY_STATE_UNKNOWN, VERIFICATION_FAILED, RUNTIME_DEPLOY_FAILED, STALE_PUBLISH, SCOPE_BUSY, INTERNAL_ERROR`.

## 6. The four errors C5 asked about (all verified)

| code | HTTP | message | `details` | retryable | `Retry-After` |
|---|---|---|---|---|---|
| `SCOPE_BUSY` (rollback / unpublish) | 409 | `Another release operation (<KIND>) is running for this app; try again when it has finished` (`(<KIND>)` absent if unknown) | `{appId, environment:"PRODUCTION", operation:{kind,deploymentId,since,leaseUntil}\|null}` — the holder | **yes** | **`5`** (seconds) |
| `SCOPE_BUSY` (publish) | **not an HTTP error**: the deployment stays `DEPLOYING`, one `SCOPE_BUSY` event, retried by the platform; after 300 s it ends `FAILED` with `error` `[SCOPE_BUSY] Another release operation owned the scope for more than 300 s` | | | publish again later | – |
| `ROLLBACK_STALE` | 409 | `The active release is not the one this request expected; reload and decide again` (or, when a newer operation overtook it: `a newer release operation already moved the active release`) | `{activeDeploymentId, expectedActiveDeploymentId}` (first form) | no — reload the site, decide again | none |
| `IDEMPOTENCY_KEY_REUSED` | 409 | `This Idempotency-Key was already used with a different request` | `{}` | no — use a new key | none |
| `STALE_PUBLISH` | **not an HTTP error**: a deployment ends `status:"FAILED"`, `error:"[STALE_PUBLISH] a newer release operation already moved the active release"` (variants: `another operation holds the same activation number`, `The release scope was taken over by another operation before this release could be activated`), event `STALE_PUBLISH` | | | no — publish again (a new request draws a new, higher number) | – |

## 7. Concurrency, as the code decides it (activation numbers are drawn when a publish is **accepted**; rollback / unpublish draw one when they take the scope)

| | situation | result |
|---|---|---|
| A | publish while a rollback holds the scope | `202` immediately; the deployment waits at `DEPLOYING` (one `SCOPE_BUSY` event, no worker held), proceeds when free, `FAILED [SCOPE_BUSY]` after 300 s. If the rollback moved the pointer meanwhile, the publish was requested **before** it, so it ends `FAILED [STALE_PUBLISH]` |
| B | rollback while a publish runs | the scope is held only during the publish's `DEPLOYING` step: then `409 SCOPE_BUSY` (+ `Retry-After: 5`, holder `PUBLISH`); during QUEUED…BUILDING the rollback goes through and the publish will end `STALE_PUBLISH` (it is older than the rollback) |
| C | rollback while a rollback runs | different keys / no key: `409 SCOPE_BUSY`. Same `Idempotency-Key`: the duplicate **waits** (≤ 60 s) for the first and answers `200` (`AlreadyActive`); still running after 60 s → `409 SCOPE_BUSY` |
| D | two publishes, different keys | two deployments, both `202`; they activate in activation-number order; the older one, if the newer already activated, ends `FAILED [STALE_PUBLISH]` |
| E | same key + same payload | publish: `202` + `Idempotent-Replay: true`, same deployment. Rollback / unpublish: one effect, both `200` |
| F | same key + different payload | `409 IDEMPOTENCY_KEY_REUSED` (judged before any eligibility check) |
| G | stale publisher finishes late | its pointer write is refused (compare-and-set); it writes nothing more; the deployment ends `FAILED [STALE_PUBLISH]` (or stays silent if the SAME operation was taken over and completes it); the newer release stays active |
| H | worker dies | lease (TTL 90 s, heartbeat 30 s) expires; the recovery sweeper re-delivers after `app.deploy.recovery-progress-seconds` (120 s); the same deployment resumes under a new fencing token; a deployment found `ROLLING_BACK` finishes its undo and ends `FAILED` |

## 8. Authoritative sources

Controllers `publish/PublishController.kt`, `publish/SiteControllers.kt` (`SiteManagementController`); DTOs `DeploymentDto` (`publish/DeploymentModel.kt`), `SiteInfo`, `SiteOperation`, `RollbackRequest` (`SiteControllers.kt`); status `DeploymentStatus` (`DeploymentModel.kt`) + V30; error envelope `common/ApiErrors.kt`, `common/ApiExceptionHandler.kt`; guard `publish/ReleaseScope.kt`, `publish/JdbcScopeGuard.kt`; service `publish/ReleaseService.kt`; deployer `publish/ReleaseDeployer.kt`; idempotency `ReleaseService.operationId` + `idempotency_keys`; tests `PublishApiContractTests`, `SiteOperationApiTests`, `ReleaseScopeLeaseTests`, `ReleaseCasTests`, `PublishScopeScenarioTests`, `DeploymentStatusTests`; contract docs `docs/parallel/c0/C2_DEPLOY_CONTRACT.md`, `docs/parallel/c2/BATCH2_HANDOFF.md`.
