# Publish and Published-Site Runtime

Canonical description of how an application is published, how a release is activated, rolled back and taken offline, how a published site is served, and how a visitor's page reaches public data.

- Baseline described: branch `integration/v2` at `{{INTEGRATION_SHA}}` (first pass at `7d46ec5c5f98`; every cited file was re-checked at `28376de`: nothing under `publish/**`, `project/publishconfig/**`, `settings/**`, `application*.yml` or the sites gateway changed between the two, so all `file:line` citations stand). Public API running state: `{{PUBLIC_API_SHA}}`; public portals: `{{PUBLIC_FRONTEND_SHA}}`. Release candidate: `{{FINAL_RC_SHA}}`.
- Related canonical documents: [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) (system map, invariants, ownership), [`docs/contracts/v2/published-runtime.md`](contracts/v2/published-runtime.md) (frozen contract of the public runtime), [`docs/contracts/v2/runtime-api.md`](contracts/v2/runtime-api.md) (authenticated runtime routes).
- Citation convention: `(src: path:line)`. `publish/X.kt` means `backend/src/main/kotlin/com/systemwebstudio/publish/X.kt`; `db/V30` means `backend/src/main/resources/db/migration/V30__deployment_rollback_and_scope_lease.sql`. Where code and a document disagree, the code wins and the disagreement is noted as **DISAGREEMENT**.
- Status vocabulary used for capabilities: DONE / PARTIAL / BLOCKED / DEFERRED.

---

## 1. Vocabulary

| Term | Meaning | Where it lives |
|---|---|---|
| Project / application | A row of `projects`. `app_type` is `PAGE_SCHEMA` (page document, rendered to static HTML) or `STATIC_APP` (code repository built in a sandbox); `app_kind` refines it (`WEBSITE_STATIC`, `SOURCE_WEB_APP`, `DASHBOARD`, `INTERNAL_TOOL`, `WORKFLOW`, `SERVER_APP`). | `db/V14`, `db/V22`; `code/CodeProjects.kt:71` (`SERVER_KINDS = SERVER_APP, INTERNAL_TOOL, WORKFLOW`) |
| Version | Immutable snapshot of the application document (`project_versions`). A deployment pins exactly one version (`deployments.version_id`). | `db/V4` |
| Deployment | One publish attempt: a row of `deployments` with a status (section 5), events (`deployment_events`) and, once built, an `artifact_id`. The deployment id is also the **release id** shown to browsers (`releaseId`). | `publish/DeploymentModel.kt`, `db/V4` |
| Artifact | Immutable, content-addressed build output: a set of files in the private MinIO bucket plus a manifest (path, size, SHA-256, content type per file). Row of `artifacts`, unique on `(project_id, sha256)`. | `db/V13`, `publish/StaticSites.kt:93-140` |
| Site | One public address per project: row of `sites` (`slug`, `current_deployment_id`, release-scope columns). The slug is `^[a-z0-9][a-z0-9-]{1,79}$`, created on first publish as `<readable name>-<first 6 hex of project id>`. | `db/V13`, `publish/SiteService.kt:66-73` |
| Active release | The deployment named by `sites.current_deployment_id`. It is what the sites gateway serves and what the Public Runtime answers from. "RUNNING" does not mean active: older RUNNING deployments stay RUNNING and are the restorable releases. | `publish/SiteService.kt:88-96`; `docs/parallel/c0/C2_DEPLOY_CONTRACT.md` section 1.1 |
| Release scope | The unit that publish, rollback and unpublish serialize on: `(tenant, app, environment = PRODUCTION)`. Physically the `sites` row. Only `PRODUCTION` exists. | `publish/ReleaseScope.kt:14` |
| Visibility | Deployment-level: `PRIVATE` or `PUBLIC` only (`deployments_visibility_check`). The publish-config policy has a wider enum (`PRIVATE`, `TENANT`, `PUBLIC`, `PRIVATE_LINK`), see section 6.3. | `db/V4:127`, `db/V27` |
| Sites gateway | One nginx for all published sites (no container per site), path-per-site: `https://<sites-host>/<slug>/...` is rewritten to the API `/sites/<slug>/...`. | `infra/sites-gateway/default.conf.template` |

---

## 2. Component map

```
 Studio (browser, studio origin)                          Visitor (browser, sites origin)
   |  POST .../publish, GET .../deployments                  |  GET /<slug>/..., POST /<slug>/_data/queries/<id>/run,
   |  POST .../site/rollback, DELETE .../site                |  POST /<slug>/_forms/<id>
   v                                                         v
 API  PublishController ----------------+              Sites gateway (nginx, anonymous reverse proxy)
   |   (auth + policy, 202)             |                      |  rewrite /<slug>/x -> /sites/<slug>/x
   v                                    |                      v
 PostgreSQL: deployments (QUEUED) ------+              API  SiteServingController / PublicDataController
   |  after commit                                            |  (own stateless security chain, no Studio session)
   v                                                          v
 RabbitMQ  studio.publish  --(dead-letter)--> studio.publish.dlq        reads sites.current_deployment_id on EVERY request
   |                                                                    -> artifact bytes from MinIO (hash-checked) / C3 DataGateway
   v
 PublishWorker -> DeploymentProcessor -> StaticSiteBuilder -> render worker (workers/render, HTTP, X-Render-Token)
                         |                    |            -> MinIO studio-artifacts (write-once keys)
                         v                    v
                  ReleaseService -> ReleaseScopeGuard (lease + fence on `sites`) -> ReleaseDeployer
                                                      -> DeployProvider (static | mock) -> pointer CAS (sites.current_deployment_id)
                                                      -> RuntimePlane (server apps) -> ServerRuntimeService -> runner -> app container
```

Backend packages (all `com.systemwebstudio`): `publish` (this document), `integration.deploy` (`DeployProvider`, `PointerFence`), `integration.storage` (`ArtifactStore`), `integration.queue` (`JobQueue`, queue names), `project.publishconfig` (publish policy), `maintenance` (retention, cleanup), `runtime` (server apps, `PublicAddress`), `wiring` (`PublicDataController`), `access.adapters` (`PublicSiteAuthorizer`). Ownership of these paths is described in [`docs/ARCHITECTURE.md`](ARCHITECTURE.md).

---

## 3. HTTP surface

All Studio-origin routes use the normal session cookie plus CSRF header on non-GET. Error body is the standard envelope `{code, message, requestId, details}`; **these routes have no `retryable` field** (that exists only on `app-runtime` and the public data route). (src: `docs/parallel/c2/PUBLISH_API_CONTRACT.md` header; pinned by `PublishApiContractTests`, not re-run in this session, UNVERIFIED as to current green state.)

Base: `B = /api/v1/workspaces/{workspaceId}/projects/{projectId}`.

### 3.1 Publish and deployments

| Route | Auth | Request | Success | Notes |
|---|---|---|---|---|
| `POST B/publish` | `PROJECT_PUBLISH` (canonical `APP_PUBLISH`) | header `Idempotency-Key` (required, 8-120 chars of `A-Za-z0-9_.:-`); body `{"visibility":"PRIVATE"\|"PUBLIC","expectedRevision":<long>}`, both required | `202 Accepted` + deployment (below); replay: `202` + header `Idempotent-Replay: true` + the original deployment | **Asynchronous**: poll the deployment. (src: `publish/PublishController.kt:33-36,60-107`) |
| `GET B/deployments/{deploymentId}` | project read | - | `200` deployment, with `events[]` | `404 DEPLOYMENT_NOT_FOUND` if not of this project. (src: `PublishController.kt:136-141`) |
| `GET B/deployments` | project read | - | `200` latest 50, newest first, **without** `events` | (src: `DeploymentRepository.kt:26`) |

Deployment JSON, exact keys (src: `publish/DeploymentModel.kt:43-49`, `docs/parallel/c2/PUBLISH_API_CONTRACT.md` section 1): `id, projectId, versionId, versionNumber, visibility, status, url (null until RUNNING), error (null unless FAILED; "[CODE] reason"), provider ("static"|"mock"), createdAt, updatedAt, finishedAt (null until terminal), events:[{status,message,createdAt}], mock (true when provider is "mock")`.

### 3.2 Site, rollback, unpublish

| Route | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `GET B/site` | project read | - | `200` SiteInfo | - (src: `publish/SiteControllers.kt:243-248`) |
| `POST B/site/rollback` | `PROJECT_PUBLISH` | header `Idempotency-Key` optional; body `{"deploymentId":uuid (required), "expectedActiveDeploymentId":uuid (optional)}` | `200` SiteInfo (**synchronous**). Target already active: `200`, nothing changes | `400 DEPLOYMENT_NOT_RESTORABLE`, `400 INVALID_IDEMPOTENCY_KEY`, `403`, `404 SITE_NOT_FOUND`, `409 ROLLBACK_FAILED`, `409 SCOPE_BUSY` (+`Retry-After: 5`), `409 ROLLBACK_STALE`, `409 IDEMPOTENCY_KEY_REUSED` (src: `SiteControllers.kt:256-274`) |
| `DELETE B/site?expectedActiveDeploymentId=<uuid>` | `PROJECT_PUBLISH` | no body; header `Idempotency-Key` optional | `200` SiteInfo with `online:false`, `currentDeploymentId:null`, slug kept. Already offline, or no site: `200`, nothing written | `403`, `409 SCOPE_BUSY`, `409 ROLLBACK_STALE`, `409 IDEMPOTENCY_KEY_REUSED`, `400 INVALID_IDEMPOTENCY_KEY` (src: `SiteControllers.kt:280-289`) |
| `POST /api/v1/sites/{slug}/access-ticket` | `PROJECT_READ` (404 for non-members) | body `{"path": <=512 chars}` optional | `200 {"redirect": "<sites origin>/_access?ticket=<t>"}` | single-use, 60 s, stored in Redis (src: `SiteControllers.kt:292-299`, `SiteService.kt:179-191`) |

SiteInfo (src: `SiteControllers.kt:213-218`): `{slug, url, online, visibility, currentDeploymentId, currentVersionNumber, provider, updatedAt, pointerVersion (long, +1 per pointer change; observability only, no request accepts it), operation: null | {kind: PUBLISH|ROLLBACK|UNPUBLISH, deploymentId (non-null only for PUBLISH), since, leaseUntil}}`. `online` = pointer set AND its deployment is `DEPLOYING` or `RUNNING`. `operation` is non-null only while another release operation holds the scope with a live lease.

### 3.3 Publish configuration (policy)

Mounted only with `app.publish-configs.enabled=true` (default **false**). (src: `project/publishconfig/PublishConfigApi.kt:64,96-97`; `application.yml` `app.publish-configs.enabled`)

| Route | Auth | Notes |
|---|---|---|
| `GET B/publish-config` | project read | `{config: PublishConfigDto\|null, draft: PublishConfigDef\|null, linkToken: null}`; the link token is never returned here |
| `PUT B/publish-config` | `PROJECT_PUBLISH` | body `{mode, visibility, requiresAuth (effectively required, omitting it is 400 MALFORMED_REQUEST per H-C2-07 handoff), cacheSeconds 0..86400, acknowledgePublicData, expectedRevision}`; `422 PUBLISH_CONFIG_INVALID` with `details.issues[]`; `409 REVISION_CONFLICT` |
| `POST B/publish-config/adopt-draft` | `PROJECT_PUBLISH` | body `{acknowledgePublicData}` optional; copies the draft `publishConfig` of the current document into the policy through the same rules; never automatic |
| `POST B/publish-config/link/rotate` | `PROJECT_PUBLISH` | only for a `PRIVATE_LINK` policy; returns the new token once |

### 3.4 Other routes in the same family (summary)

| Route | Purpose | Authorization |
|---|---|---|
| `GET/POST/DELETE B/domains[...]`, `POST B/domains/{id}/verify`, `POST B/domains/{id}/check-tls` | custom domains (public websites only) | read for GET, `PROJECT_PUBLISH` for mutation (src: `publish/Domains.kt:158-190`, `docs/parallel/c2/APP_PUBLISH_AUTHORIZATION_AUDIT.md` section 1) |
| `GET B/runtime`, `POST B/runtime/rollback`, `POST B/runtime/stop`, `PUT B/runtime/secrets`, `DELETE B/runtime/secrets/{name}` | server runtime of server apps (`409 NOT_A_SERVER_APP` otherwise) | `PROJECT_PUBLISH` for rollback / stop, `PROJECT_SETTINGS` for secrets (src: `runtime/ServerRuntime.kt:272-305`) |
| `GET B/form-submissions`, `GET B/form-submissions/export`, `DELETE B/form-submissions/{id}` | website contact-form submissions (personal data, retention `retention.form-submission-days`, default 180) | see `publish/Forms.kt:136-170` |

### 3.5 Visitor-facing routes (API, behind the gateway)

| API route | Gateway path | Method | Auth | Purpose |
|---|---|---|---|---|
| `/sites/{slug}` | `/{slug}` | GET | none | `301` to `/{slug}/` |
| `/sites/{slug}/**` | `/{slug}/**` | GET, HEAD | PUBLIC: none. PRIVATE: host-only cookie `site_session` | serve a file of the active release; `/__factory/config.json` = runtime config |
| `/sites/_access?ticket=` | `/_access` | GET | single-use ticket | redeem ticket, set `site_session`, `302` to `/<slug><path>` |
| `/sites/_app/{token}/**` | `/_app/{token}/**` | GET | capability token (membership re-checked each request) | private **code app** content |
| `/sites/_preview/{token}/**` | `/_preview/{token}/**` | GET | unguessable expiring token | preview of a code change (sandboxed) |
| `/sites/_host/**` | any other `Host` | GET, HEAD | none | verified custom domain, public websites only |
| `/sites/{slug}/_forms/{formId}`, `/sites/_host/_forms/{formId}` | `/{slug}/_forms/{formId}` | POST (urlencoded) | none | website form submission |
| `/sites/{slug}/_data/queries/{queryId}/run` | `/{slug}/_data/queries/{queryId}/run` | POST | none (anonymous) | Public Runtime, section 12 |
| `/sites/{slug}/api/**`, `/sites/_app/{token}/api/**` | `/{slug}/api/**` | all | per-app route table | server-app API, section 13 |

(src: `publish/SiteControllers.kt:64-142`, `publish/Forms.kt:123-128`, `wiring/PublicDataController.kt`, `identity/SecurityConfiguration.kt:94-107`)

---

## 4. Synchronous refusals versus asynchronous rejection

A publish request is judged in two different places. The split matters to clients: a synchronous refusal returns an error, creates **no deployment, no queue message and no idempotency reservation**; an asynchronous rejection returns `202` first and then ends `FAILED` with `error = "[CODE] reason"`.

### 4.1 Synchronous (inside `POST B/publish`, one DB transaction)

Order exactly as in code (src: `publish/PublishController.kt:60-107`):

| # | Check | Answer |
|---|---|---|
| 1 | Session, CSRF, `access.forProject` (membership, tenant, archive state) | `401`, `404` (project not visible to the caller, no existence disclosure) |
| 2 | `ctx.require(PROJECT_PUBLISH)` | `403 FORBIDDEN` |
| 3 | `Idempotency-Key` present and well formed | `400 MISSING_HEADER`, `400 INVALID_IDEMPOTENCY_KEY`; body validation `400 VALIDATION_FAILED` / `400 MALFORMED_REQUEST` (body validation runs before the permission check in every controller, `APP_PUBLISH_AUTHORIZATION_AUDIT.md` section 4) |
| 4 | Insert into `idempotency_keys` (scope `publish:<project>:<user>:<key>`, hash of `visibility|expectedRevision`) | conflict + same hash: `202` replay with `Idempotent-Replay: true`; same key, other payload: `409 IDEMPOTENCY_KEY_REUSED`; original not yet visible: `409 IDEMPOTENCY_IN_PROGRESS` |
| 5 | Per-user rate limit `app.rate-limit.publish-max` (default 10 per 60 s) | `429 RATE_LIMITED` + `Retry-After` |
| 6 | `expectedRevision == project.revision` | `409 REVISION_CONFLICT`, `details.currentRevision` |
| 7 | `PUBLIC` requested and setting `publish.public-enabled` false | `403 PUBLIC_PUBLISH_DISABLED` |
| 8 | Code app (`app_type = STATIC_APP`): `PUBLIC` requested and `source-apps.public-publish-enabled` false | `403 CODE_APP_PUBLIC_DISABLED` |
| 9 | Code app: build capacity (`BuildPolicyService.requireCapacity`) | `409 BUILDS_DISABLED`; quota: `429 BUILD_QUOTA_<ORG_DAILY\|WORKSPACE_DAILY\|PROJECT_DAILY\|USER_DAILY\|USER_CONCURRENT\|WORKSPACE_CONCURRENT>` (src: `code/BuildPolicy.kt:32-50`); the rejection is also recorded in `build_rejections` and audited in a separate transaction |
| 10 | Page app: `schemas.ensureInitialized` (creates version 1 if the project has none) | - |
| 11 | A version exists | `409 NO_VERSION` |
| 12 | Public-data approval (H-C2-07), section 6.4 | `409 PUBLISH_POLICY_MISMATCH`, `422 PUBLIC_DATA_NOT_APPROVED` |
| 13 | Insert `deployments` (`QUEUED`, `activation_seq` drawn from `deployment_activation_seq`), event `QUEUED`, audit `PUBLISH`; message to RabbitMQ **after commit** | `202` |

Because the request is one transaction, any refusal after step 4 also rolls back the idempotency row: a retry of the same key after the cause was removed (for example after the approval was granted) is a first request, not a replay of the refusal. (src: `PublishController.kt:109-117`, `docs/parallel/c2/H_C2_07_PUBLIC_DATA_APPROVAL.md` "Idempotency")

If the broker is unreachable after commit the request still answers `202`; the recovery sweeper re-publishes the id (section 5.5). (src: `PublishController.kt:99-105`)

Rollback and unpublish have their own synchronous refusals (section 3.2); they never return `202`.

### 4.2 Asynchronous (worker; request already answered `202`)

| Where | Rejection | Final state |
|---|---|---|
| `POLICY_CHECK` | project no longer active; code app without an ACTIVE repository; page document violates the component registry (`PageSchemaValidator.validate`); navigation link to a domain that is not approved; referenced assets missing / not READY | `FAILED`, `[POLICY_REJECTED] <reason>`, never retried (src: `DeploymentProcessor.kt:237-255`) |
| `SECURITY_CHECK` | page text (lower-cased snapshot) contains `<script`, `javascript:`, `onerror=`, `onload=` or `data:text/html`. Code apps are not scanned here (they are scanned in the build sandbox and when output is stored) | `FAILED`, `[SECURITY_REJECTED] ...` (src: `DeploymentProcessor.kt:59,257-262`) |
| `BUILDING` | render worker unreachable or 5xx (transient), render refusal 422 e.g. a data binding to a non-public query (permanent), build job failure, store errors | `[RENDER_UNAVAILABLE]`, `[BUILD_FAILED]`, `[ARTIFACT_STORE_UNAVAILABLE]`, ... (section 5.4) |
| `DEPLOYING` | artifact verification, staging, server runtime, switch, confirmation, scope contention, stale intent | `[VERIFICATION_FAILED]`, `[DEPLOY_FAILED]`, `[RUNTIME_DEPLOY_FAILED]`, `[STALE_PUBLISH]`, `[SCOPE_BUSY]`, ... |

The asynchronous steps are **not** a second authorization layer: the request was authorized when it was accepted, and a membership change after acceptance does not fail a pipeline that was legitimately accepted. (src: `docs/parallel/c2/APP_PUBLISH_AUTHORIZATION_AUDIT.md` section 2)

---

## 5. The deployment pipeline

### 5.1 Statuses (closed list)

`QUEUED -> POLICY_CHECK -> SECURITY_CHECK -> BUILDING -> DEPLOYING -> RUNNING`, plus `FAILED`, `ROLLING_BACK`, `ROLLED_BACK`. The list is the CHECK constraint `deployments_status_check` as replaced by V30. (src: `db/V30` section 1; `publish/DeploymentModel.kt:6-22`)

`ROLLBACK_FAILED` and `ROLLBACK_OFFLINE` are **not** statuses; they are only events in `deployment_events.status` (no CHECK there). The other event-only names a client may see in `events[]`: `SWITCH`, `ROLLBACK_OK`, `ROLLBACK_FAILED`, `ROLLBACK_OFFLINE`, `SCOPE_BUSY`, `STALE_PUBLISH`, `PUBLIC_QUERIES`, `ROLLED_BACK`, plus `Retry n/m: ...` messages on the retried step. (src: `docs/parallel/c2/PUBLISH_API_CONTRACT.md` section 5; `DeploymentProcessor.kt:152-156`)

| Status | Terminal | Served by the gateway when it is the pointer target | Meaning |
|---|---|---|---|
| `QUEUED` | no | no | accepted, waiting for a worker |
| `POLICY_CHECK`, `SECURITY_CHECK` | no | no | permanent rejections only |
| `BUILDING` | no | no | render + store the artifact (page apps) or wait for the sandbox build job (code apps) |
| `DEPLOYING` | no | **yes** (known limit LIM-1, section 8.6) | holds the release scope; verify, stage, runtime, switch, confirm |
| `ROLLING_BACK` | no | **no** | the decision to undo this deployment is durable; the undo is finished (also after a crash), never rolled forward; leaves only to `FAILED` |
| `RUNNING` | yes | yes | deployed **and verified HEALTHY** |
| `FAILED` | yes | no | never became healthy, or was undone; `error` carries `[CODE]` and any rollback summary. The deployment is never retried; the user publishes again (a new deployment) |
| `ROLLED_BACK` | yes | no | was the active release and an operator rolled back **away from it to an older release**; not restorable (publish that version again) |

Terminal set is `{RUNNING, FAILED, ROLLED_BACK}` (unchanged by V30, so existing pollers stop on the same set). `ROLLING_BACK` is non-terminal. (src: `DeploymentModel.kt:20-22`)

Legal edges, enforced by `DeploymentStatus.allowed` and by a compare-and-set `UPDATE ... WHERE id = ? AND status = ?` in `DeploymentRepository.transition`:

- one step forward along the pipeline;
- any in-progress status `-> FAILED`;
- `DEPLOYING -> ROLLING_BACK` (verification failed or unknown, or deploy error after the switch was attempted);
- `ROLLING_BACK -> FAILED` and nothing else;
- `RUNNING -> ROLLED_BACK` (manual rollback to an older release);
- nothing leaves `RUNNING`, `FAILED`, `ROLLED_BACK` otherwise; nothing returns to a pipeline status. (src: `DeploymentModel.kt:24-38`, `DeploymentRepository.kt:42-51`)

Every transition writes one `deployment_events` row; the worker also writes an audit row `DEPLOY_STATUS_CHANGE` (actor null) for each move. (src: `DeploymentProcessor.kt:275-299`)

### 5.2 Queue and worker

- `POST publish` commits the row first, then `queue.publish("studio.publish", deploymentId)` in `afterCommit`. Queue `studio.publish` is durable with dead-letter routing to `studio.publish.dlq`. (src: `integration/queue/JobQueue.kt:10-29`)
- `PublishWorker` is a `@RabbitListener` in the **same JVM** as the API (no separate worker process). Concurrency `PUBLISH_WORKER_CONCURRENCY` (default 2), prefetch 1, `default-requeue-rejected: false`. (src: `publish/PublishWorker.kt:14-17`, `application.yml` `spring.rabbitmq.listener`)
- Duplicate or redelivered messages are harmless: each step is a CAS, a loser does nothing. A worker that crashes is resumed from the status it left behind.
- A deployment already terminal is ignored; `ROLLING_BACK` goes straight to the undo (`resumeRollingBack`). (src: `DeploymentProcessor.kt:84-90`)

### 5.3 What each step does

| Step | Page app (`PAGE_SCHEMA`) | Code app (`STATIC_APP`) |
|---|---|---|
| `POLICY_CHECK` | registry validation of the pinned snapshot, approved navigation domains, referenced assets READY | project active, repository ACTIVE |
| `SECURITY_CHECK` | forbidden-content scan of the snapshot | skipped (scanned in the build sandbox) |
| `BUILDING` | `StaticSiteBuilder.build` within `app.deploy.build-timeout-seconds` (300): assets copied to `assets/<id>.<ext>`, every page rendered from ONE schema version by the render worker (`/render-site`), manifest written, artifact id derived from the manifest; then the `PUBLIC_QUERIES` event | enqueue a `build_jobs` row (`PUBLISH`); the step returns *Wait* and the job completion re-queues the deployment; `SUCCEEDED` copies `artifact_id`, `commit_sha` onto the deployment |
| `DEPLOYING` | `ReleaseService.publish` -> `ReleaseDeployer.deploy` (section 8) | same; additionally the server runtime moves first when the app has a server part (section 13) |

Code apps require `DEPLOY_PROVIDER=static` (`ERR: Code apps need the static site provider`); a code app version must carry a `commit`. (src: `DeploymentProcessor.kt:135-146,218-235`)

The `PUBLIC_QUERIES` event is the publish confirmation of what becomes public: `Public queries of this release (anyone who can open the site may run them, read-only): <ids>`; it is written once and only when the release has public queries. (src: `DeploymentProcessor.kt:152-156`)

### 5.4 Failure classification, retries, codes

Every failure becomes a `StepFailure(code, message, transient, ambiguous)`; `deployments.error` stores `"[CODE] message"` (max 1000 chars) and may carry `" | rollback: <summary>"`. URLs and secrets in messages are redacted. (src: `publish/DeploymentFailure.kt`)

`FailureCode` (closed): `POLICY_REJECTED, SECURITY_REJECTED, BUILD_FAILED, ARTIFACT_MISSING, RENDER_UNAVAILABLE, ARTIFACT_STORE_UNAVAILABLE, DATABASE_UNAVAILABLE, STEP_TIMEOUT, DEPLOY_FAILED, DEPLOY_TIMEOUT, DEPLOY_STATE_UNKNOWN, VERIFICATION_FAILED, RUNTIME_DEPLOY_FAILED, STALE_PUBLISH, SCOPE_BUSY, INTERNAL_ERROR`.

- **Transient** failures (render worker restarting or 5xx, store or database blip, I/O) retry the same step up to `app.deploy.step-max-attempts` (default 3) with backoff `app.deploy.retry-backoff-ms` (500 ms, attempt n waits `(n-1) x backoff`, minimum one backoff); the count is read from the event history (`Retry n/m` events), so a restart does not reset it. (src: `publish/StepRunner.kt:41-46`, `DeploymentRepository.kt:66-67`)
- **Permanent** failures and **ambiguous** ones (a deploy that timed out, an unconfirmable verification) are never retried blindly; an ambiguous DEPLOYING failure goes to `ROLLING_BACK`. A transient failure that exhausts its attempts ends `FAILED` with `(after n attempts)`.
- A `ROLLING_BACK` deployment is never retried and never rolls forward.

### 5.5 Recovery sweeper

`DeploymentRecovery.republishStale` (`@Scheduled`, every `app.deploy.recovery-interval-ms` = 30 s) re-publishes ids of deployments in `QUEUED` older than 30 s (`recovery-queued-seconds`) or in `POLICY_CHECK / SECURITY_CHECK / BUILDING / DEPLOYING / ROLLING_BACK` not updated for 120 s (`recovery-progress-seconds`). A deployment that is deliberately waiting for the scope calls `touch` so the sweeper does not re-publish it. (src: `publish/PublishWorker.kt:24-41`, `DeploymentRepository.kt:60-72`)

### 5.6 Providers

| `DEPLOY_PROVIDER` | Behaviour | Production |
|---|---|---|
| `mock` (default) | no build, no artifact, no site: the deployment ends `RUNNING` with a clearly labelled mock URL (`app.deploy.mock.base-url`); `deploymentTarget = "fail"` simulates a failure; the deployment JSON has `mock:true`. The pointer `sites.current_deployment_id` is **never** written. | refused: `ProductionConfigValidator` ("DEPLOY_PROVIDER=mock must not be used in production") |
| `static` | real: artifacts in MinIO, pointer switch, served through the sites gateway | the only production provider |

(src: `integration/deploy/DeployProvider.kt` `MockDeployProvider`, `publish/SiteService.kt:215-268`, `common/ProductionConfigValidator.kt:51`, `application.yml` `app.deploy.provider`)

`DeployProvider` is a port (`stage`, `deploy`, `verify`, `restore`); production providers must honour: artifact verified before `stage`; `deploy` idempotent; `verify` is the only source of "healthy" and a provider that cannot verify answers `UNKNOWN`, never success; `restore` makes an earlier release active again **without building**; any provider that moves the platform pointer must do it through `PointerFence.commit`. (src: `integration/deploy/DeployProvider.kt:66-87`)

---

## 6. Visibility and publish authorization

### 6.1 Who may publish

- **Permission**: `PROJECT_PUBLISH`, the storage constant of the canonical code `APP_PUBLISH` (`PermissionCodes`). It guards publish, rollback, unpublish, `runtime/rollback`, `runtime/stop`, domain mutations, and every `publish-config` write. Reads (`GET site`, `GET deployments`, `GET deployments/{id}`, `GET publish-config`) need project read only. (src: `access/Permission.kt:37-58`; `docs/parallel/c2/APP_PUBLISH_AUTHORIZATION_AUDIT.md` section 1)
- **Who holds it** (matrix, deny by default, resolved from the database on every request, never from the browser): project role `PUBLISHER` (read, publish, use) and `OWNER`; workspace role `WORKSPACE_ADMIN` (every project permission on every project of the workspace). Project `VIEWER`/`EDITOR` and workspace `EDITOR`/`PUBLISHER`/`VIEWER` do not hold it through workspace role. A SYSTEM_ADMIN who is not a member holds only tenant-management permissions unless `app.tenancy.system-admin-business-access=true`. (src: `access/Permission.kt:70-136`)
- Frontend gating is not part of the security model (`FRONTEND_GATING_REQUIRED_FOR_SECURITY = NO`). A grant removed while the session stays open is refused on the next request. (src: `APP_PUBLISH_AUTHORIZATION_AUDIT.md` sections 1, 3)

### 6.2 Administrator switches (lockdown)

| Setting key (Admin console `PUT /api/v1/admin/settings/policies/{key}`) | Default | Effect |
|---|---|---|
| `publish.public-enabled` (HIGH risk) | `true` (env `PUBLIC_PUBLISH_ENABLED`) | `false`: any `PUBLIC` publish is `403 PUBLIC_PUBLISH_DISABLED`, and a `PUBLIC` publish-config is `422` with the same issue code |
| `source-apps.public-publish-enabled` | `true` (env `SOURCE_APP_PUBLIC_PUBLISH_ENABLED`) | `false`: a `PUBLIC` publish of a code app is `403 CODE_APP_PUBLIC_DISABLED` |
| `source-apps.build-enabled`, `build.max-*` | see `settings/Settings.kt` | code-app build capacity (section 4.1 step 9) |

(src: `settings/Settings.kt:50,65,67-71,132`; `PublishController.kt:87-91`; `project/publishconfig/PublishConfigPolicy.kt:31-37`). These are read through `SettingsService`: a `system_settings` override (V15, set by a system admin, audited) or else the configured default; the overrides are cached for about 5 seconds, so another API instance picks a change up within that TTL. (src: `settings/Settings.kt:84-93`)

### 6.3 Private versus public, and what the policy does (and does not do)

Deployment visibility is `PRIVATE` or `PUBLIC`, fixed when the deployment is created and immutable. The gateway decides per request from the **served deployment's** visibility:

- **PUBLIC**: served to anyone, no session.
- **PRIVATE**: needs the host-only cookie `site_session` on the sites origin, obtained through the ticket flow (Studio member -> single-use 60 s ticket -> `/_access` -> `Set-Cookie site_session` HttpOnly, SameSite=Lax, `Secure` if `SITES_COOKIE_SECURE`, `app.sites.session-hours` = 8). A missing session is `302` to `<studio origin>/studio/site-access?site=<slug>&path=...`. Membership is re-checked live on every request (`canRead` = `PROJECT_READ`); a non-member gets `403`. Private **code apps** are redirected to a per-session capability path `/_app/<token>/`. (src: `SiteControllers.kt:115-142`, `SiteService.kt:165-211`)
- Studio cookies are host-only on the Studio origin and never reach the sites origin (separate hosts; ADR 0009).
- Private sites check `PROJECT_READ` (APP_VIEW). The C1 final hardening report (imported, D-C0-60) lists "private sites / apps check APP_VIEW where APP_USE is meant" as an **open P2** owned by C2 / C0 / C4, not fixed. (src: `docs/parallel/c1/final-iam-hardening-report.md` section 3)

**Publish policy (`publish_configs`, one row per project, table from V27)** holds `mode` (`STATIC|DYNAMIC|SERVER_APP`), `visibility` (`PRIVATE|TENANT|PUBLIC|PRIVATE_LINK`), `requires_auth`, `cache_seconds`, `public_data_approved`, `link_token_hash`, `revision` (CAS). Rules (`PublishConfigPolicy`): server-app projects only `SERVER_APP`, source web apps only `STATIC`; `PUBLIC` needs the admin switches, cannot `requiresAuth`, and with data bindings needs `acknowledgePublicData`; `cacheSeconds` 0..86400.

What the policy **actually affects today** (verified by grep of callers in `backend/src/main` on `integration/v2`):

| Policy field | Consumed by | State |
|---|---|---|
| `visibility = PUBLIC` vs request | `PublishController.requirePublicDataApproval` (409 mismatch) | DONE, only when the flag is on and a row exists |
| `public_data_approved` | `PublishController` (422) and `ReleaseSnapshotPublicQueryAllowList` (Public Runtime) | DONE |
| `requires_auth`, `cache_seconds`, `link_token_hash`, `visibility = TENANT / PRIVATE_LINK`, `mode` | **no consumer found** outside `project.publishconfig` (`PublishConfigService.decisionFor` and `linkMatches` have no caller); deployments can only be `PRIVATE` or `PUBLIC`, and the gateway never reads these | PARTIAL: stored and validated, **not enforced by the serving plane**. UNVERIFIED if a consumer exists on an unmerged branch |

(src: `publish/PublishController.kt:118-127`; `project/publishconfig/PublishConfigService.kt:83 (linkMatches), :93 (decisionFor)`; grep `linkMatches\|decisionFor` on `integration/v2` = no hits outside the package; `AppDefinition` contract [`docs/contracts/v2/app-definition.md`](contracts/v2/app-definition.md) section 5: "Visibility TENANT and PRIVATE_LINK are not allowed by the base deployments_visibility_check")

The draft `AppDefinition.publishConfig` inside a version is **intent only**: it has no effect on what is live until adopted into the policy through the API. A rollback moves only the pointer; it neither reads nor rewrites the policy. (src: `project/publishconfig/PublishConfigModel.kt:9-22`)

### 6.4 Public-data approval (H-C2-07)

Goal: a private dataset must never reach the open web as a side effect of publishing `PUBLIC`.

**Authority** is `publish_configs.public_data_approved`, a **Boolean on the policy**. It cannot be set through `POST publish` (not a field of `PublishRequest`; forged fields are ignored). It becomes true only through `PUT publish-config` or `adopt-draft` with `acknowledgePublicData = true`, by a holder of `PROJECT_PUBLISH`, and only when `visibility = PUBLIC`. (src: `PublishConfigService.kt:52`)

At publish acceptance, after the revision check, the admin switches and the version lookup, **before** the deployment row, queue message, build, scope lease and pointer (src: `PublishController.kt:118-127`):

| Request | Stored policy | Snapshot of the version about to be deployed | Result |
|---|---|---|---|
| `PRIVATE` | any | any | unaffected |
| `PUBLIC` | none (no row, or `app.publish-configs.enabled=false`) | any | **unchanged legacy behaviour: the request decides, accepted** |
| `PUBLIC` | not `PUBLIC` | any | `409 PUBLISH_POLICY_MISMATCH` (`details.policyVisibility`, `requestedVisibility`) |
| `PUBLIC` | `PUBLIC` | no `dataBindings` | accepted |
| `PUBLIC` | `PUBLIC`, `public_data_approved = false` | non-empty `dataBindings` | `422 PUBLIC_DATA_NOT_APPROVED`, `details.issues[0] = {field:"acknowledgePublicData", code, message}` |
| `PUBLIC` | `PUBLIC`, `public_data_approved = true` | non-empty `dataBindings` | accepted |

Properties, all from code or the H-C2-07 handoff:

1. **Not version-bound.** The approval is one Boolean per project. Data bindings added to a later version are covered by the existing approval. Whether the product wants "approval per version" is an open contract question for C0, not implemented. (src: `H_C2_07_PUBLIC_DATA_APPROVAL.md` "Open")
2. "Binds data" is `ProjectFacts.bindsData` = non-empty `dataBindings` array. Publish evaluates it on the **immutable snapshot** of the latest version (`versions.version(projectId, versionId).schemaSnapshot`); the publish-config API evaluates it on the **current draft document**. A draft with bindings removed and no new version cannot dodge the approval of a version that has them.
3. **Legacy PUBLIC without a stored approval.** With no stored policy a `PUBLIC` publish with data bindings is accepted as before. This is not a data leak because the Public Runtime serves no query unless the same table says `public_data_approved = true`; the published page then shows only its authored content (its data panels get no data; how a page renders that failure is UNVERIFIED, see section 14). Existing projects that were already published have a `publish_configs` row from the V27 backfill (`visibility` mirrors `site_visibility`, approval false), so **with the flag on** a legacy `PUBLIC` project whose backfilled policy is `PRIVATE` gets `409 PUBLISH_POLICY_MISMATCH` until its policy is changed. (src: `db/V27` backfill; `PublishController.kt:120-123`)
4. **Reset rule**: `PublishConfigService.set` computes `publicDataApproved = (visibility == PUBLIC && acknowledgePublicData)`, so **every** `PUT`/`adopt-draft` that does not repeat `acknowledgePublicData = true` resets the approval to false, including a `PUT` that keeps `PUBLIC`. `rotateLink` copies the existing value. **DISAGREEMENT**: the H-C2-07 handoff says "setting the policy to anything but PUBLIC resets it"; the code resets on any save without the acknowledgement. (src: `PublishConfigService.kt:38-70 (computation at :52)`)
5. **Revocation is live for the data route**: the Public Runtime reads the approval per request, so withdrawing it closes public queries at once (the already-served pages stay). A replay of an accepted publish returns the original deployment and does not re-judge it; a new key after the withdrawal is refused. (src: `PublicDataController.kt:85-100`, `H_C2_07_PUBLIC_DATA_APPROVAL.md` "Idempotency")
6. **Naming**: this approval is unrelated to the *workflow approval* runtime (table `approvals`, V33, `logic/approval`, route `.../workflow-runs/{runId}/approvals/{approvalId}/decision`), described in [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) section 7.1.
7. To approve from the Studio: `PUT B/publish-config` with `visibility:"PUBLIC"`, `requiresAuth:false`, `acknowledgePublicData:true`, current `expectedRevision` (omitted when no policy exists), or `POST B/publish-config/adopt-draft` with `{"acknowledgePublicData":true}`, then publish.

---

## 7. Release artifacts

### 7.1 Build and immutability

- Page apps: the render worker (`workers/render`, same renderer as the Studio preview; called over HTTP at `app.render.url` with `X-Render-Token`; `/render-site` for every page, 30 s timeout) returns `index.html`, `<page-slug>/index.html`, `404.html` and, only for data-bound pages, `_runtime/page-runtime.js`. `StaticSiteBuilder` adds referenced images as `assets/<assetId>.<ext>` (images only), writes a manifest `[ {path,size,sha256,contentType} ]`, and derives the **artifact id's content hash = SHA-256 of the canonical manifest JSON**. Files are stored under `<projectId>/<sha256>/<path>` in the private bucket `app.storage.artifacts-bucket` (`studio-artifacts`) with write-once keys (`putOnce`). (src: `publish/StaticSites.kt:44-52,93-140`; `application.yml` `app.storage.artifacts-bucket`)
- A rendered page may contain **no** `<script`, except the single reference `<script src="./_runtime/page-runtime.js" defer>` (or `../`); the runtime file is at most 32 KiB and must be referenced by at least one page. Otherwise the build fails (`BUILD_FAILED`). (src: `StaticSites.kt:112-131,163-169`)
- The same content is the same artifact: `ON CONFLICT (project_id, sha256) DO NOTHING`; an existing row is reused; a row already removed by retention is **revived** (files written again, `deleted_at = NULL`, `created_at = now()`) so a build never hands back a record without files. (src: `StaticSites.kt:134,154-161`)
- A deployment links to its artifact via `deployments.artifact_id`; rollback restores a **previous artifact without rebuilding**.
- Per-file `Cache-Control` and `ETag` at serving time are in section 11.2.

### 7.2 Verification: three layers

| Layer | What it proves | Where | When |
|---|---|---|---|
| Cheap check `verify` | artifact record exists and `deleted_at IS NULL`; manifest not empty; SHA-256 of the canonical manifest equals `artifacts.sha256`; every manifest file exists in the store with the recorded size (one `stat` each) | `StoredArtifactVerifier.check(deep=false)` | right before the pointer moves (static provider), after the switch, and when checking a rollback target after the switch |
| Content check `verifyContent` | additionally re-reads every file and compares its SHA-256 with the manifest, **only when the artifact's total size is <= `app.deploy.verify-bytes-limit`** (default 16 MiB = 16777216; `0` = never) | `StoredArtifactVerifier.check(deep=true)` | once, **before** a release is made active (publish and manual rollback target); same-size tampering passes the cheap check and fails this one |
| Serve-time check | every file the gateway serves is hashed against the manifest; a mismatch answers `500` "Nội dung không khớp bản đã xuất bản" instead of the bytes | `SiteServingController.serveFile` | every request, for every artifact, including artifacts above the byte limit |

(src: `publish/ReleaseService.kt` `StoredArtifactVerifier`; `SiteControllers.kt:199-205`; `docs/parallel/c2/RELEASE_ENVIRONMENTS.md` sections 2, 6)

Limit (stated, not hidden): artifacts larger than the byte limit are protected by the cheap check and the serve-time check only. A V2 recommendation (store the object ETag / checksum at upload, periodic scrub of the active and rollback-target artifacts) is a proposal, DEFERRED. (src: `RELEASE_ENVIRONMENTS.md` section 6)

### 7.3 Lifecycle, retention, orphan cleanup

`CleanupService` (`@Scheduled` every `app.cleanup.interval-ms` = 1 h, `app.cleanup.enabled` default true, Postgres advisory lock 727001 so several instances do not race, one `CLEANUP` audit event per non-empty run) calls `ArtifactRetentionService.run`; the admin console can run it by hand (`GET /api/v1/admin/retention/preview` dry run, `POST /api/v1/admin/retention/run`). (src: `maintenance/CleanupService.kt`, `admin/AdminRetentionController.kt:17-24`)

An artifact is **kept** when any of these holds (src: `maintenance/ArtifactRetention.kt` `candidates`):

- it is served by a site (`sites.current_deployment_id`);
- it is among the last N `RUNNING` deployments of its project (`retention.rollback-deployments`, default 5; only for active projects or projects updated within `retention.deleted-project-days`, default 30);
- it is the release right before the served one (the rollback target), whatever N is;
- a deployment still `QUEUED / POLICY_CHECK / SECURITY_CHECK / BUILDING / DEPLOYING / ROLLING_BACK` uses it, or is the `previous_deployment_id` target of one;
- it backs a live code-change preview, a running/queued build job, or the current/desired server runtime release;
- it is younger than one hour.

Deletion is two steps in a fixed order: (1) the record is marked `deleted_at` by a guarded `UPDATE` (not served by a site, older than 1 h) and the audit `ARTIFACT_DELETED` is written; (2) its objects are deleted. A crash or store outage in between leaves objects without a live record, never a record without objects. `cleanOrphans` reclaims objects whose key shape is `<projectId>/<sha256>/<file>` and for which no live record exists, only when the newest object of the group is older than 1 h (`ORPHAN_GRACE`), checked again right before deleting; other bucket users are untouched; audit `ARTIFACT_ORPHAN_DELETED`. The orphan sweep runs inside every non-dry retention run. (src: same file)

Other cleanup of publish data: `FAILED` deployments and their events older than `app.cleanup.failed-deployment-days` (30) are deleted; `idempotency_keys` older than 7 days; form submissions per `retention.form-submission-days`. A deleted project's site is taken offline through the release scope (section 8.5) and its repository archived. (src: `CleanupService.kt:70-90`, `ArtifactRetention.kt` `onProjectDeleted`)

The per-project artifact quota `storage.max-artifacts-mib-per-project` (default 1000) is enforced in the **code-app build** path (`code/BuildJobs.kt:147`); an equivalent check for page-app artifacts was not found, UNVERIFIED.

---

## 8. The release scope, fencing and the active pointer

### 8.1 Why it exists

Before V30 the pointer was a blind `UPDATE`: the last writer won and a slow older publish could overwrite a newer release; rollback and unpublish raced publishes. V30 serialises every mutation of a site's release state through one lease row and writes the pointer only through a compare-and-set. (src: `docs/parallel/c0/C2_DEPLOY_CONTRACT.md` section 5 findings F-1..F-12; decision D-C0-24 / D-C0-27 / D-C0-32 in `docs/parallel/DECISIONS.md`)

### 8.2 Data (V30, on `sites` and `deployments`)

| Column | Meaning |
|---|---|
| `sites.current_deployment_id` | the active pointer (NULL = offline) |
| `sites.pointer_version` | +1 on every pointer change; the CAS guard |
| `sites.active_seq`, `sites.active_operation_id` | activation number and identity of the operation that last moved the pointer |
| `sites.fence_counter` | monotonic counter; **every** acquisition of the lease (a takeover, or the same operation taking the scope again) draws `fence_counter + 1` as its **fencing token** |
| `sites.lease_operation_id`, `lease_kind` (`PUBLISH|ROLLBACK|UNPUBLISH`), `lease_deployment_id`, `lease_seq`, `lease_fence`, `lease_holder`, `lease_started_at`, `lease_until` | the current owner; all NULL = free; all-or-none (`sites_lease_shape_check`) |
| `deployments.activation_seq` | the intent number, drawn from sequence `deployment_activation_seq` **when the publish request is accepted** (column default); rollback and unpublish draw one when they take the lease |
| `deployments.previous_deployment_id` | the release that was active when this deployment started switching (typed; replaced parsing a `SWITCH` event text) |

(src: `db/V30`; `publish/JdbcScopeGuard.kt`)

### 8.3 Operations on the scope

Publish (the DEPLOYING step), automatic rollback of a failed publish, manual rollback, unpublish, and archive / delete of an application (`ReleaseService.takeOffline`) all take the scope first. There is **no unfenced pointer write left in the product**, pinned by a source-scan test (`ReleaseCasTests`, D-C0-33). (src: [`docs/contracts/v2/published-runtime.md`](contracts/v2/published-runtime.md) I9; `publish/ReleaseService.kt`)

| Mechanism | Value (default) | Key |
|---|---|---|
| Lease TTL | 90 s (minimum 30 s once configured) | `app.deploy.scope-lease-seconds` (`DEPLOY_SCOPE_LEASE_SECONDS`) |
| Heartbeat | TTL / 3 (30 s) | derived |
| Longest one operation may hold the scope | 900 s | `app.deploy.scope-lease-max-seconds` |
| A publish waits for a busy scope | 300 s, then `FAILED [SCOPE_BUSY]` | `app.deploy.scope-wait-seconds` |
| Re-queue delay of a waiting publish | 2000 ms | `app.deploy.scope-retry-ms` |
| A duplicate rollback/unpublish (same Idempotency-Key) waits for its original | 60 s | `app.deploy.scope-duplicate-wait-seconds` |
| Archive / delete waits for a release operation in flight | 10 s, then `409 SCOPE_BUSY` | `app.deploy.scope-lifecycle-wait-seconds` |
| Deploy step timeout / verify timeout | 120 s / 60 s | `app.deploy.deploy-timeout-seconds`, `verify-timeout-seconds` |

(src: `publish/JdbcScopeGuard.kt:24-37`, `publish/ReleaseService.kt` constructor, `application.yml` `app.deploy`)

**Acquire** is one atomic `UPDATE sites ... RETURNING` (each statement its own `REQUIRES_NEW` transaction; no advisory lock, no transaction held across provider calls). It succeeds when the scope is free, expired, or held by the same operation (re-entry allowed for PUBLISH only), within the 900 s maximum for the same operation, and the project belongs to the claimed tenant (`projects.tenant_id`). The heartbeat extends `lease_until` only while the operation's own token is current; **0 rows updated = lease lost** and the holder stops writing at once (`fencedOut`). (src: `JdbcScopeGuard.kt:44-70,133-154`)

**Pointer commit (the only way the pointer moves), compare-and-set**:

```sql
UPDATE sites SET current_deployment_id = ?, pointer_version = pointer_version + 1, active_seq = ?, active_operation_id = ?, updated_at = now()
 WHERE project_id = ? AND lease_operation_id = ? AND lease_fence = ? AND lease_until > now()
   AND pointer_version = ? AND (active_seq < ? OR (active_seq = ? AND active_operation_id = ?))
```

(src: `JdbcScopeGuard.kt:160-163`). One row = committed (and in the same transaction the optional extra step runs, for example `RUNNING -> ROLLED_BACK` of the release left, and the visibility copy back to `projects.site_visibility`); zero rows = the writer is fenced out: nothing else is written by that operation.

### 8.4 Order of intents: `STALE_PUBLISH`, `SCOPE_BUSY`

`ScopeOrdering.of(seq, operationId, activeSeq, activeOperationId)` (src: `publish/ReleaseScope.kt:58-67`):

| Relation | Result |
|---|---|
| `seq > active_seq` | `NEWER`: may go on while it still holds lease, token and `pointer_version` at every write |
| `seq < active_seq` | `STALE`: a newer intent already won; the publish ends `FAILED [STALE_PUBLISH]` |
| equal, same operation | `RESUME` (it moved the pointer itself; resuming after a crash or retry) |
| equal, other operation | `CONFLICT`: nothing may overwrite the pointer |

**DISAGREEMENT** (resolved in favour of the code): `C2_DEPLOY_CONTRACT.md` rule R2 reads `activation_seq <= active_seq => stale`; the code and `BATCH2_HANDOFF.md` clarification 1 use the table above. A stale or conflicting acquisition releases the scope immediately.

`STALE_PUBLISH` and `SCOPE_BUSY` are failure codes of a **deployment**, not HTTP errors, for publish:

- `FAILED [STALE_PUBLISH]` variants: `a newer release operation already moved the active release`; `another operation holds the same activation number`; `The release scope was taken over by another operation before this release could be activated`; event `STALE_PUBLISH`. Recovery: publish again (a new request draws a higher number).
- A publish that finds the scope owned is **not a failure**: one `SCOPE_BUSY` event, the deployment stays `DEPLOYING`, goes back to the queue (no worker held), and ends `FAILED [SCOPE_BUSY] Another release operation owned the scope for more than 300 s` only after the wait budget.
- Rollback / unpublish never wait for another operation: busy = `409 SCOPE_BUSY` with `Retry-After: 5` and `details = {appId, environment:"PRODUCTION", operation:{kind, deploymentId, since, leaseUntil}|null}`. A duplicate of the **same** Idempotency-Key waits (<= 60 s) for its original and then answers `200` (already active).
- `ROLLBACK_STALE` (409): the `expectedActiveDeploymentId` differs from the pointer read under the lease (details `{activeDeploymentId, expectedActiveDeploymentId}`), or a newer operation overtook the request. Not raised when the target is already the active release (a retry that already succeeded).

(src: `publish/ReleaseService.kt` `rollback`, `unpublish`, `hold`, `staleCheck`; `ReleaseScope.kt:120`; `DeploymentProcessor.kt:194-202`; `PUBLISH_API_CONTRACT.md` sections 6-7)

### 8.5 Idempotency of rollback and unpublish

Without `Idempotency-Key` every request is its own operation. With one, the operation id is `UUID.nameUUIDFromBytes("site-op:<KIND>:<project>:<user>:<key>")`, with the body hash stored in `idempotency_keys` (`resource_type = SITE_OPERATION`): the same key is the same operation however often it is sent (one effect, both calls `200`), and the same key with another body is `409 IDEMPOTENCY_KEY_REUSED`, judged before any eligibility check. The key row of an authorized-but-rejected rollback (for example a forged `deploymentId`) is kept by design. (src: `ReleaseService.operationId`, `APP_PUBLISH_AUTHORIZATION_AUDIT.md` section 4)

### 8.6 Known limit LIM-1 and the deferred candidate pointer

Order inside DEPLOYING today: verify artifact (cheap + content) -> `stage` -> server runtime healthy (server apps) -> **pointer CAS (the site now serves the new release; deployment still `DEPLOYING`)** -> confirm (`provider.verify`, <= 60 s) -> `RUNNING`, or compensate. `SiteService.live` serves `DEPLOYING` and `RUNNING`, so between the switch and the confirmation a release that already passed pre-switch verification is served for up to the verify time. The Data Runtime resolves from the same pointer and statuses, so a page and its data are always one release. Accepted for V1. (src: `published-runtime.md` section 2.1; `docs/parallel/c0/C2_DEPLOY_CONTRACT.md` F-8)

The target design (candidate pointer: `sites.candidate_deployment_id` on the same scope row, one CAS flips `current := D, candidate := NULL` together with `DEPLOYING -> RUNNING`, so `DEPLOYING` is never served and a failure before activation needs no rollback) is **DEFERRED**: "Batch 3" is not authorised, `V31` is a permanent void gap, and a future migration takes the next free number, **V34 (none allocated)** per `docs/parallel/MIGRATION_LEDGER.md`. (src: `published-runtime.md` section 2.2; `docs/parallel/BOARD.md` migration table row "Candidate activation (LIM-1)"; `docs/parallel/c2/V31_CANDIDATE_ACTIVATION_PROPOSAL.md`)

Note: the Public Runtime is stricter than the gateway: it requires the active deployment to be `RUNNING` and `PUBLIC` (`PublicSiteAuthorizer`), not `DEPLOYING`. (src: `access/adapters/PublicSiteAuthorization.kt` `PublicReleaseLookup`)

---

## 9. Rollback, unpublish, ROLLING_BACK

### 9.1 Automatic rollback of a failed publish (ROLLING_BACK)

If anything fails **after the switch was attempted** (deploy error or timeout, verification `UNHEALTHY` or `UNKNOWN`, provider returned no address), `ReleaseDeployer.failedAfterSwitch`:

1. `DEPLOYING -> ROLLING_BACK` durably, **before** any undo runs, so a crash resumes the undo and never rolls forward;
2. compensates under the lease already held: the static site goes back to `previous` (the recorded `previous_deployment_id`, verified from its immutable artifact, no rebuild), then the server runtime goes back to that release's artifact (or stops if there was none);
3. if `previous` is NULL (first release): pointer to NULL, event `ROLLBACK_OFFLINE` (benign);
4. if the previous release cannot be served (artifact removed or damaged, provider error): event `ROLLBACK_FAILED`, then **fail closed**: pointer to NULL, event `ROLLBACK_OFFLINE`. The site serves nothing rather than an unhealthy or unconfirmed release. If static site and server runtime cannot be put on the same release the site is taken offline and the result is flagged `inconsistent`; no mixed release is ever served;
5. `ROLLING_BACK -> FAILED` with `error = "[CODE] reason | rollback: <summary>"` (restored release X / site offline / ROLLBACK FAILED: reason).

Net result is deterministic: pointer = previous release, or NULL; never a release that failed. A failure **before** the switch (artifact verification, staging, runtime) leaves the serving release untouched, writes no rollback, and ends `FAILED`. The rollback only acts when the site still points at *this* deployment: a newer pointer is never clobbered. (src: `publish/ReleaseDeployer.kt`; `docs/parallel/c0/C2_DEPLOY_CONTRACT.md` section 3.1)

If the pointer write of the fail-closed step itself cannot be made (database outage), the deployment stays `ROLLING_BACK` and the sweeper retries; the gateway never serves `ROLLING_BACK`. (src: contract 3.1 step 6)

### 9.2 Manual rollback (`POST B/site/rollback`)

Eligibility (400 `DEPLOYMENT_NOT_RESTORABLE` otherwise): the target is a deployment of this project in status `RUNNING` with `artifact_id` set and the artifact `deleted_at IS NULL`. A `ROLLED_BACK` release is not restorable. Under the scope (`restoreRelease`): target already active -> `AlreadyActive` (`200`); verify the target artifact **deeply**; for server apps `RuntimePlane.serve` the target's server artifact; switch the static site by CAS; in the **same transaction** the release that was left becomes `ROLLED_BACK` **only if** it is newer than the target (`left.activation_seq > target.activation_seq`; a roll-forward leaves it `RUNNING`) and the visibility the target was published with is copied back to `projects.site_visibility`; confirm; event `ROLLBACK_OK`.

Failure semantics: nothing is switched -> **no deployment status changes**, the active release keeps serving and the target stays restorable; the event `ROLLBACK_FAILED` is written on the target and the answer is `409 ROLLBACK_FAILED` with the reason (`The release could not be restored: <reason>`). If a step after the first move fails, everything is put back (site and runtime); if even that fails the site is taken offline and the result says so. A rollback needs no rebuild and no cache purge: pages are revalidated by ETag (`public, no-cache`) and `__factory/config.json` is `no-store`. After a rollback the runtime config `releaseId` and `version` change, `apiBase` does not. (src: `ReleaseDeployer.restoreRelease/restoreManually`, `SiteControllers.kt:256-274`, `PUBLISHED_RUNTIME_TOPOLOGY.md` section 8)

### 9.3 Unpublish

`DELETE B/site`: the pointer goes to NULL through the fence of the scope the request holds (`ReleaseService.unpublish`). Deployments and artifacts are kept, the slug is kept, **no deployment status changes**, so `POST site/rollback` brings the site back with no rebuild. Already offline writes nothing (no pointer version change, no audit). Audit `SITE_UNPUBLISHED`. After unpublish every file and the runtime config answer `404` (the site's "removed" page) and every public data call is `404 QUERY_NOT_FOUND`. (src: `SiteControllers.kt:280-289`, `ReleaseService.kt`)

Archive / delete of an application takes the site offline through the same door (`ReleaseService.takeOffline`: bounded wait, then `409 SCOPE_BUSY` and the caller's transaction rolls back), so a project is never "archived but still being published". (src: `ReleaseService.takeOffline`, `ArtifactRetentionService.onProjectDeleted`)

### 9.4 Rollback policy and the stored publish policy

Rollback restores a **release** (artifact + the visibility it was published with). It does not read or rewrite `publish_configs`. In particular it does not re-judge public-data approval: after a rollback to a `PUBLIC` release the Public Runtime answers from that release's allow-list, but only while `public_data_approved` is currently true. (src: `PublishConfigModel.kt:9-22`, `PublicDataController.kt` `ReleaseSnapshotPublicQueryAllowList`)

---

## 10. Health and verification

| Layer | What it proves | State |
|---|---|---|
| Artifact verification (section 7.2) | record, manifest checksum, sizes, bytes (<= 16 MiB) | DONE |
| Runtime health | the server part of a server app is healthy on the runner before the pointer moves; waits <= `app.deploy.runtime-timeout-seconds` (120) polling every 500 ms; a late runner cannot start an abandoned release | DONE (needs runner; a server-app publish without a running runner now fails after the timeout) |
| Post-switch confirmation | `StaticSiteDeployProvider.verify`: the pointer is this release, the artifact passes the cheap check, and, only if configured, the public HTTP probe answers; `UNKNOWN` is never success | DONE |
| Public HTTP probe | `HttpReleaseHealthProbe`: GET of the published URL, redirects not followed; 2xx/3xx healthy; a private site answering 401/403 to an anonymous probe is healthy; 404/5xx/other = `UNHEALTHY`; no answer = `UNKNOWN` | **default OFF** (`app.deploy.health-probe.enabled=false`; timeout `app.deploy.health-probe.timeout-seconds` 5, clamp 1..60). Needs a real public host; without it the verification reports "HTTP probe of the public address: not configured". Enabling against a real host is BLOCKED (no reachable public host was available when audited, `RELEASE_ENVIRONMENTS.md` section 2) |
| Process readiness | `GET /actuator/health/readiness` (db, redis, rabbit, minio) and `/liveness`; sites gateway `GET /healthz` returns `200 ok` | DONE, not part of publish verification |

(src: `publish/ReleaseHealthProbe.kt`, `publish/SiteService.kt:236-251`, `publish/RuntimePlane.kt:35-77`)

---

## 11. Sites gateway and serving

### 11.1 nginx (`infra/sites-gateway/default.conf.template`)

One container image `nginxinc/nginx-unprivileged`, listening on 8080 inside, host port `SITES_GATEWAY_PORT` (default 18088, bound to 127.0.0.1 in `compose.yml`; the public profile uses 28088). Template variables: `API_UPSTREAM`, `SITES_HOST`, `GATEWAY_REAL_IP_FROM` (default `127.0.0.1` = trust no proxy), `GATEWAY_FORCE_HTTPS`. It is an **anonymous reverse proxy**: it never authenticates; the API decides. (src: `compose.yml` service `sites-gateway`; template header; `docs/parallel/c2/PUBLISHED_RUNTIME_TOPOLOGY.md` section 2)

Server block 1 (the sites host, per slug), in order of specificity:

| Location (regex) | Methods | Limits | Upstream |
|---|---|---|---|
| `/healthz` | - | - | `200 ok` |
| `/favicon.ico` | - | - | `204` |
| `/<slug>/_forms/<formId>` | POST only | body <= 16 KiB, `limit_req` 6 per minute per address (burst 5) | `/sites/<slug>/_forms/<formId>` |
| `/<slug>/_data/queries/<queryId>/run` where `queryId = [a-z0-9][a-z0-9-]{0,63}` | POST only | body <= 16 KiB, 10 r/s per address (burst 20, `429`), query string dropped, `Cookie`, `Authorization`, `X-XSRF-TOKEN` cleared, `X-Forwarded-For` set to the validated `$remote_addr`, never cached | `/sites/<slug>/_data/queries/<queryId>/run` |
| `/(_app/<token>\|<slug>)/api(/.*)?` | all methods | body <= 1 MiB, 30 r/s zone `sites_rl` (burst 60) | `/sites/.../api/**` (server-app runtime) |
| `/(_access\|_preview\|_app\|<slug>)(/.*)?` | GET, HEAD | `sites_rl`, request body dropped, `proxy_cache sites` keyed `$host$request_uri`, bypassed when a `site_session` cookie is present | `/sites/...` |
| everything else | - | - | `404 not found` |

Server block 2 (`default_server`, any other `Host` = a verified custom domain): `/healthz`, `POST /_forms/<id>` -> `/sites/_host/_forms/<id>`, everything else GET/HEAD -> `/sites/_host/...`. The API decides from `Host`; only **public websites** (never code apps) are served on a custom domain.

**DISAGREEMENT / gap**: the gateway restricts `queryId` to `[a-z0-9][a-z0-9-]{0,63}`, while `PublicSiteAuthorizer` and the contract accept `^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$`. A query id with an upper-case letter, `.`, `_` or `:` cannot be called through the gateway. The page runtime and the definition id shape (`^[a-z0-9][a-z0-9-]{0,63}$`) use the narrower form, so bound pages are not affected in practice; a direct API call is. (src: template; `access/adapters/PublicSiteAuthorization.kt` `QUERY_ID`; `workers/render/page-runtime.ts` `ID`)

Client addresses: `X-Forwarded-For` is believed only from proxies named in `GATEWAY_REAL_IP_FROM`. On the API side `app.proxy.trust` / `trusted-cidrs` (`TRUST_PROXY`, `TRUSTED_PROXY_CIDRS`) decide whether `ClientIpFilter` uses the right-most untrusted entry; until they name the gateway, the per-address budgets are shared by all visitors of that gateway. (src: template; `published-runtime.md` section 7a end)

### 11.2 API serving (`SiteServingController`)

- **What is served**: `SiteService.live(slug)` = the site whose project is `active` and `lifecycle = 'ACTIVE'`, whose pointer deployment is `DEPLOYING` or `RUNNING`, and whose artifact has `deleted_at IS NULL`. Unknown slug, offline site, archived or deleted project: `404` HTML page, `no-store`. (src: `SiteService.kt:88-96`)
- **Path handling**: URL-decoded; `..`, leading `/`, backslash or control characters -> `400`; `""` -> `index.html`; `dir/` -> `dir/index.html`; `dir` -> `301 dir/` when only `dir/index.html` exists; unknown -> the site's own `404.html` (websites only, hash-verified, `__SITE_ROOT__` replaced by the site root computed server side) else a generic 404. For code apps `server/**` and `openapi.json` are never served as files. (src: `SiteControllers.kt:144-206`)
- **Integrity**: bytes are read from the store and must hash to the manifest value or the answer is `500`.
- **Headers (page-schema sites)**: `Content-Security-Policy: default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; font-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`. When the artifact contains `_runtime/page-runtime.js` (data-bound page) the CSP becomes `default-src 'none'; script-src 'self'; connect-src 'self'; ...` (same list otherwise): **one first-party script file and requests to its own origin, nothing else**. Always: `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()`, `Cross-Origin-Opener-Policy: same-origin`, `ETag: "<sha256>"` (304 on `If-None-Match`).
- **Headers (code apps)**: `Content-Security-Policy: sandbox allow-scripts; default-src 'none'; script-src 'self' <sites origin>; ...; connect-src 'self' <sites origin>; form-action 'none'; frame-ancestors 'none'` (no `allow-same-origin`: the document has an opaque origin and cannot read cookies or storage), `Access-Control-Allow-Origin: *` without credentials, `Cross-Origin-Resource-Policy: cross-origin`, `Cross-Origin-Opener-Policy: unsafe-none`.
- **Caching**: `private, no-store, no-transform` for PRIVATE sites and previews; `public, max-age=31536000, immutable, no-transform` only for `assets/**` of **public websites** (the asset id never changes bytes); every other public file `public, no-cache, no-transform` (revalidate by ETag, so rollback, unpublish and visibility changes apply at once). `no-transform` because an edge once injected an analytics script into a script-free page (ADR 0009). A CDN must never cache `__factory/config.json`, `/_app/**`, `/_access`, `/_preview/**`. (src: `SiteControllers.kt:36-44,177-198`; `RELEASE_ENVIRONMENTS.md` section 4)
- **Purge-based edge caching is not built** (DEFERRED; noted in code).

### 11.3 Origins and configuration of the sites side

| Key | Env | Local | Production |
|---|---|---|---|
| `app.sites.origin` | `SITES_ORIGIN` | `http://127.0.0.1:18088` (`application-local.yml`) | required, https, public host |
| `app.sites.studio-origin` | `STUDIO_ORIGIN` | `http://127.0.0.1:3003` | required |
| `app.sites.data-api-base` | `SITES_DATA_API_BASE` | blank | optional; https, not loopback / private / docker / `*.internal` |
| `app.sites.cookie-secure` | `SITES_COOKIE_SECURE` | false | must be true |
| `app.render.url`, `app.render.token` | `RENDER_URL`, `RENDER_TOKEN` | `http://127.0.0.1:18095` | required (service-to-service) |

(src: `application.yml`, `application-local.yml`, `application-prod.yml`, `common/ProductionConfigValidator.kt:41-50`)

Sites live on a **different host from the Studio** so a site can never read Studio cookies. Custom domains: a project owner adds a hostname, proves DNS ownership (TXT), the platform checks TLS; several projects may claim a host, the first to verify wins (V23). TLS termination and certificates are owned by whoever owns the public edge. (src: `publish/Domains.kt`, `db/V23`, `RELEASE_ENVIRONMENTS.md` section 4)

---

## 12. Runtime config and `apiBase`

`GET {sites origin}/{slug}/__factory/config.json` (also `/_app/{token}/__factory/config.json` for a PRIVATE code app and `/_preview/{token}/__factory/config.json`). Produced by `SiteService.runtimeConfig` **per request**; served for **code apps** and for **page-schema sites that ship the runtime** (`_runtime/page-runtime.js` in the artifact). A page-schema site without data bindings has no config (the path is a missing file). There is **no** `/runtime-config.json` route. Headers: `Content-Type: application/json`, `Cache-Control: no-store, no-transform`, `Access-Control-Allow-Origin: *` (no credentials), the site CSP, `nosniff`, `Referrer-Policy: no-referrer`, `COOP: same-origin`. (src: `SiteControllers.kt:139-160`, `SiteService.kt:106-120`)

Exact key set (all present, none omitted):

| Field | Type | Nullable | Source |
|---|---|---|---|
| `appId` | uuid string | no | `projects.id` |
| `appName` | string | no | `projects.name` |
| `environment` | `"production"` \| `"preview"` | no | fixed per route (lower case: vocabulary of `@company/app-sdk`; the scope's `PRODUCTION` is internal) |
| `visibility` | `"PUBLIC"` \| `"PRIVATE"` | no | served deployment's visibility |
| `version` | string | yes | version number of the **served release** (preview: latest) |
| `user` | `{displayName}` | yes | the signed-in member of a PRIVATE app; null on PUBLIC; no user id |
| `flags` | object of booleans | no | always `{}` today |
| `apiBase` | string | yes | `app.sites.data-api-base`, see below |
| `releaseId` | uuid string | yes | served deployment id; null for preview |
| `generatedAt` | instant | no | per request |

No secret, token, tenant id, workspace id or credential is in it (asserted by test: body contains none of `token`, `secret`, `password`, `Authorization`). (src: `PUBLISHED_RUNTIME_TOPOLOGY.md` section 4; `SiteService.kt:115-119`)

**`app.sites.data-api-base`** (env `SITES_DATA_API_BASE`):

- Meaning: the **browser-facing** absolute http(s) base where a published app calls its Data Runtime. The same-origin form is `{sites origin}/{slug}/_data`. Never an internal host.
- Token `{slug}`: the value may contain `{slug}` (any number of times), replaced by the site's slug when the config is produced, so one value serves every site of an environment, e.g. `https://sites.example.com/{slug}/_data`. Any other brace, `${...}` syntax, credentials, fragment, non-http(s), or a missing host makes the value unusable: `apiBase` is `null` (no address is invented). A value that needs a slug but is asked for none is also null. (src: `SiteService.kt:57-61,127-138`)
- **Read once at API process start** (constructor of `SiteService`): a change needs a restart; it is never baked into an artifact, so the same artifact runs on a Mac, staging and production and a rollback needs no rebuild. **DISAGREEMENT**: a comment in `application.yml` says "read at request time"; the code reads it in the constructor (`SiteService.kt:46-49`), as `PUBLISHED_RUNTIME_TOPOLOGY.md` section 3 corrects. The **response** is built per request.
- Startup warning if the value is not on the sites origin: pages only call their own origin, so their data panels would stay `NOT_READY`. (src: `SiteService.kt:50-54`)
- A **PRIVATE page-schema site** always gets `apiBase: null` (the public data route is anonymous; there is no contract yet for a member's browser to reach data). (src: `SiteService.kt:117-118`)
- Local default is blank; the isolated test stacks set `http://127.0.0.1:<sites port>/{slug}/_data` when public data is on. (src: `docs/parallel/c0/DEMO_STACK_RUNBOOK.md`)
- Production validator: when set it must be https and not loopback / private / docker / `*.internal`. (src: `ProductionConfigValidator.kt:46-50`)

---

## 13. Static runtime versus server runtime

| | Static (page apps, `SOURCE_WEB_APP`, `DASHBOARD`) | Server (`SERVER_APP`, `INTERNAL_TOOL`, `WORKFLOW`) |
|---|---|---|
| Artifact | files only | files plus a `server/` bundle and `openapi.json` (never served as files) |
| Served by | API from the artifact store, behind the sites gateway | the static part as left; the API under `/<slug>/api/**` goes through `AppGatewayController` to an isolated container |
| Release | pointer CAS | the **runtime moves first, the site pointer last** (`RuntimePlane`: slow, fallible, reversible first; one local instant step last), so static site and server runtime end on the **same** release or the operation fails and says so |
| Rollback | pointer | pointer **and** `ServerRuntimeService` (blue/green, `rollback_of`); if the two cannot be reconciled the site is taken offline instead of serving a mix |
| Needs | `DEPLOY_PROVIDER=static` | additionally the build runner, the apps DB server, the apps gateway, admin policy `server-apps.enabled` (HIGH risk, off by default), `SECRETS_MASTER_KEY` etc. in production |

(src: `publish/RuntimePlane.kt`, `publish/ReleaseDeployer.kt`; [`docs/DEPLOYMENT.md`](DEPLOYMENT.md) "Services (by stack profile)", ADR 0017, ADR 0018, ADR 0019). Detail of the server runtime (containers, per-app database, secrets, connector proxy, isolation levels) is out of scope here; see ADR 0017-0019 and `runtime/**`. `PublishConfigPolicy` forces `mode = SERVER_APP` for server-app projects. A publish of a server app without a running runner fails with `[RUNTIME_DEPLOY_FAILED]` after `app.deploy.runtime-timeout-seconds` (120 s) rather than reporting success. (src: `BATCH2_HANDOFF.md` section 2)

---

## 14. The Public Runtime: how a visitor's page reaches public data

### 14.1 Principles

1. A visitor's browser never talks to a database, connector or the DataGateway. It talks to **one same-origin address**, the sites gateway.
2. The browser is never an authority for tenant, workspace, project, release, role, permission or actor kind: all are derived on the server from the site slug.
3. What is served and what is queried is the **active release** (`sites.current_deployment_id`), never "the latest RUNNING deployment".
4. Everything that differs between a Mac and a VPS is an environment value read at run time. (src: [`docs/contracts/v2/published-runtime.md`](contracts/v2/published-runtime.md) section 1)

### 14.2 Request path

```
Visitor page (PAGE_SCHEMA site with data bindings; script = _runtime/page-runtime.js, CSP script-src 'self'; connect-src 'self')
  1. GET  {site root}/__factory/config.json        -> apiBase (e.g. https://sites.example.com/<slug>/_data) or null => page shows NOT_READY
  2. POST {apiBase}/queries/{queryId}/run          body {"params":{}}   no cookie, no Authorization
        |
        v   Sites gateway: POST only, body <= 16 KiB, limit_req 10 r/s, Cookie/Authorization/XSRF headers cleared, never cached
        v   API  POST /sites/{slug}/_data/queries/{queryId}/run        (stateless chain, permitAll for exactly this POST + forms, no CSRF: no session to ride)
        1. rate limit: per client address (30 / 10 s) BEFORE any lookup; Redis outage = refuse (429)
        2. strict body: only `params` allowed (any other key 400 INVALID_REQUEST)
        3. SiteService.live(slug) -> project -> workspace -> tenant ; per site+address (120/min), per site (1200/min)
        4. PublicSiteAuthorizer (C1): operation QUERY_EXECUTE only; mode LIVE only; query id shape;
              release re-verified in DB: site slug + deployment == pointer, status RUNNING, visibility PUBLIC, project active, tenant ACTIVE;
              release allow-list: queryId in {queries[] with public:true and mode READ in THE RELEASE's immutable version snapshot}
                                  AND publish_configs.public_data_approved = true NOW
              any failure -> the SAME 404 QUERY_NOT_FOUND "Query not found" (no existence oracle; never 401 / 403)
        5. load the release's own pinned definition (never the draft) ; LIVE data-source binding of the slot (no TEST, no fallback)
        6. C3 DataGateway.runQuery with GatewayContext(actorKind = PUBLIC_SITE, actorUserId = null), caps: rows <= app.sites.public-data.max-rows (100, the query's maxRows may only lower it)
        7. response {queryId, mode:"LIVE", cache, result:{ViewModelData}} ; > 256 KiB -> 502 RESPONSE_TOO_LARGE ; audit DATA_PUBLIC_QUERY_SERVED (no visitor identity)
```

(src: `wiring/PublicDataController.kt` `run`, `ReleaseSnapshotPublicQueryAllowList`; `access/adapters/PublicSiteAuthorization.kt`; `workers/render/page-runtime.ts` header; `infra/sites-gateway/default.conf.template`)

### 14.3 Rules

| Rule | Behaviour |
|---|---|
| Actor | `PUBLIC_SITE` is a new `ActorKind` value: no user id, no role, no membership, never `SYSTEM / SERVICE / APP_TOKEN`. Converting it for the action / workflow runtime is a denial (`wiring.ActorKinds.toLogic` throws), so a public call can never reach actions or workflows. (src: `tenancy/TenantContext.kt:5-14`) |
| Scope V1 | read-only LIVE query listed as public by the release. **Not** public mutation, action, workflow, TEST, draft, raw DataGateway, raw SQL, schema discovery, any Management route. |
| Where "public" is declared | `queries[].public: true` (JSON boolean) on a READ query in the app definition. The renderer **refuses to publish** a data-bound page whose binding names a query that is not `public: true` or not READ (build fails with the reason, `BUILD_FAILED`); the server checks again on every request. Max 8 distinct queries per page, bindable props limited to a fixed list per registered component. (src: `workers/render/page-runtime.ts` `resolveBindings`) |
| Immutability | the allow-list is a pure function of the release's version snapshot (`project_versions.schema_snapshot`, never updated; `deployments.version_id` never changes). Draft edits cannot change what an active release exposes; a query that is only in the draft, or marked public after the release, is not public for that release. After a rollback the answer comes from the restored release's list. |
| Live approval switch | `publish_configs.public_data_approved`; revoking closes the route at once. No `publish_configs` row = empty allow-list = deny. (src: `ReleaseSnapshotPublicQueryAllowList`) |
| Data binding | LIVE bindings are **project configuration, not release-pinned**: changing a binding changes the data a release reads, by design; a missing binding is `422 DATA_SOURCE_UNBOUND`. |
| Refusals | one `404 QUERY_NOT_FOUND "Query not found"` for: unknown/offline/archived/deleted site, private site, deployment not `RUNNING`, not approved, not public, other release, TEST, unreadable snapshot, unauthorised. |
| Other answers | `400 INVALID_REQUEST`, `413 PAYLOAD_TOO_LARGE` (> 16 KiB, by a body-limit filter so the API is safe without the gateway), `422 DATA_SOURCE_UNBOUND`, `429 RATE_LIMITED` + `Retry-After`, `502 DATA_UNAVAILABLE` / `RESPONSE_TOO_LARGE`, `504 DATA_TIMEOUT`, `503 DATA_RUNTIME_UNAVAILABLE`, `500 INTERNAL`. Envelope `{code,message,requestId,retryable,details}`; connector text, hosts, schema, SQL and credential metadata never leave. |
| Headers | `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`; no CORS headers (same origin); no cookie read or set. |
| Same code path | the principal differs, the query path does not: allow-list -> LIVE binding -> C3 `DataGateway`. There is no second query engine. |
| Data-source ownership | **Decision A, final (D-C0-60)**: the gateway authorizer does **not** check data-source ownership; a foreign or unknown data source is C3's canonical `404` (no existence oracle). The attempted check was reverted and is not on `integration/v2`; test `GatewayAuthorizerNoOracleTests`. (src: `docs/parallel/DECISIONS.md` D-C0-60 item 2) |

(src: `published-runtime.md` sections 4, 5, 7a; verified against `PublicDataController.kt`)

### 14.4 Switches and defaults

The route exists only when **both** `app.data-platform.enabled` (`DATA_PLATFORM_ENABLED`) and `app.sites.public-data.enabled` (`SITES_PUBLIC_DATA_ENABLED`) are true; both default **false**. Without them there is no controller bean and the path is simply a missing route. The allow-list provider bean is created under the same condition; without it C1 uses `DenyAllPublicQueryAllowList` (deny all). (src: `PublicDataController.kt` `CONDITION`, `PublicDataConfiguration`)

### 14.5 Limits and unresolved points

- **How a page shows a refused public query** (read from `workers/render/page-runtime.ts`): every request is bounded (15 s) and never throws. A bound element whose query fails is marked `data-xw-state="error"` with `data-xw-error` = `not-found` (404), `rate-limited` (429), `forbidden` (401/403), `unavailable` (>= 500), `timeout`, `network`, `invalid-response` or `http-<n>`; the page root becomes `data-xw-state="error"` with the list of `query:code`. A missing or unusable `apiBase` (absent, invalid, other origin) puts the root in `not-ready` (`api-base-missing` / `api-base-invalid` / `api-base-cross-origin`, or `config-unavailable`). Authored text stays; no data is invented. Behaviour pinned by `tests/browser/page-runtime.spec.mjs`.
- The nginx `queryId` pattern is narrower than the API's (section 11.1).
- Rate limiting at nginx (`limit_req`) and in the API (`common.RateLimiter`, Redis, fixed windows) both exist; with the API reached through a gateway that is not in `TRUSTED_PROXY_CIDRS` the per-address budgets are shared by all visitors (section 11.1).
- `PRIVATE` page sites have no data address (`apiBase: null`) until a contract defines a member's path. DEFERRED.
- [`docs/contracts/v2/published-runtime.md`](contracts/v2/published-runtime.md) section 4 table "Cache" mentions per-query `public, max-age` as a later option: not implemented (DEFERRED).
- Old statements: `PUBLISHED_RUNTIME_TOPOLOGY.md` section 0 ("a page CANNOT today call the Data Runtime") describes the state **before** the public route, gateway route and `PUBLIC_SITE` actor existed. For **authenticated user** data calls from a sandboxed code app the four blockers listed there still describe the design (opaque-origin CSP, SDK `credentials: "omit"`, CORS list, gateway route to `app-runtime`): no proxy mode for the authenticated Data Runtime from a published code app exists. UNVERIFIED whether any later change addressed it; no source found.

---

## 15. Flags, keys and defaults

| Key (env) | Default | Meaning |
|---|---|---|
| `app.deploy.provider` (`DEPLOY_PROVIDER`) | `mock` | `static` for real sites; `mock` refused in production |
| `app.deploy.step-delay-ms` | 0 (local profile 700) | artificial pause between steps (UI demos) |
| `app.deploy.step-max-attempts` / `retry-backoff-ms` / `build-timeout-seconds` | 3 / 500 / 300 | retry policy of transient failures, build bound |
| `app.deploy.deploy-timeout-seconds` / `verify-timeout-seconds` / `runtime-timeout-seconds` / `runtime-poll-ms` | 120 / 60 / 120 / 500 | provider calls and server-runtime wait |
| `app.deploy.verify-bytes-limit` | 16777216 | content re-hash limit (0 = off) |
| `app.deploy.health-probe.enabled` / `timeout-seconds` | false / 5 | public HTTP probe |
| `app.deploy.recovery-interval-ms` / `recovery-queued-seconds` / `recovery-progress-seconds` | 30000 / 30 / 120 | sweeper |
| `app.deploy.scope-*` | see section 8.3 | release scope |
| `app.rate-limit.publish-max` (`RATE_LIMIT_PUBLISH_MAX`) | 10 per minute per user | publish rate limit |
| `app.publish-configs.enabled` (`PUBLISH_CONFIGS_ENABLED`) | false | publish-config API + enforcement of the stored policy at publish |
| `app.data-platform.enabled` (`DATA_PLATFORM_ENABLED`) | false | data platform (needed by the public data route) |
| `app.sites.public-data.enabled` (`SITES_PUBLIC_DATA_ENABLED`) | false | Public Runtime route |
| `app.sites.public-data.max-body-bytes` / `max-rows` / `max-response-bytes` | 16384 / 100 / 262144 | caps (bounds 256..1048576 / 1..PageSpec.MAX_PAGE_LIMIT / 1024..4194304, validated at start) |
| `app.sites.public-data.rate.ip-burst` (+ `ip-burst-window-seconds`) / `site-ip-per-minute` / `site-per-minute` | 30 per 10 s / 120 / 1200 | API-side rate limits |
| `app.sites.data-api-base` (`SITES_DATA_API_BASE`) | blank | `apiBase`, section 12 |
| `app.sites.cookie-name` / `cookie-secure` / `session-hours` | `site_session` / false / 8 | private-site session |
| `app.cleanup.enabled` / `interval-ms` / `failed-deployment-days` | true / 3600000 / 30 | operational cleanup |
| `retention.rollback-deployments` (setting) | 5 | RUNNING deployments kept per project |
| `PUBLISH_WORKER_CONCURRENCY` | 2 | worker concurrency |
| `publish.public-enabled`, `source-apps.public-publish-enabled` (settings) | true, true | public publishing switches |

(src: `application.yml`, `application-local.yml`, `application-prod.yml`, code `@Value` defaults cited above; `settings/Settings.kt`)

Local-profile and test-stack notes: the isolated stacks set `PUBLISH_CONFIGS_ENABLED`, `SITES_PUBLIC_DATA_ENABLED` and `ORGANIZATION_PERSISTENCE_ENABLED` **in that stack only**; the application defaults stay off. (src: `docs/parallel/c0/DEMO_STACK_RUNBOOK.md`; `docs/parallel/DECISIONS.md` D-C0-59)

---

## 16. Capability status

| Capability | Status | Evidence / remaining gap |
|---|---|---|
| Publish pipeline QUEUED..RUNNING with CAS steps, retries, recovery | DONE | `publish/DeploymentProcessor.kt`; integration tests named in `PUBLISH_API_CONTRACT.md` section 8 |
| Synchronous refusals and idempotent publish | DONE | `PublishController` |
| Release scope: lease, fencing, CAS pointer, STALE_PUBLISH / SCOPE_BUSY | DONE | V30; `JdbcScopeGuard` |
| Verified rollback / unpublish / lifecycle takeOffline | DONE | `ReleaseService`, `ReleaseDeployer` |
| Artifact hash verification before activation and at serve time | DONE (content re-hash bounded to 16 MiB) | `StoredArtifactVerifier` |
| Retention, orphan cleanup, revive | DONE | `ArtifactRetentionService` |
| Public HTTP health probe | BLOCKED | needs a real public host; default off |
| Candidate-pointer activation (LIM-1, Batch 3) | DEFERRED | not authorised; V31 void |
| Purge-based edge caching, object-checksum scrub, Micrometer meters for lease contention / step durations | DEFERRED | `RELEASE_ENVIRONMENTS.md` sections 5-6 |
| Public Runtime route, `PUBLIC_SITE`, allow-list, gateway route, `{slug}` apiBase | DONE (flag OFF by default) | `PublicDataController`; contract section 7 |
| Studio authoring of `public: true` and of the approval | DONE | Builder step "Dữ liệu công khai" (`features/studio/builder/PublicDataPanels.tsx`, `core/publicData.ts:117-135`) sets `public` on READ queries only; the release dialog persists the approval through `PUT publish-config` with `acknowledgePublicData:true` and the freshly read revision (`features/studio/ReleaseModal.tsx:125-140`, `packages/api-client/src/release.ts:145-156`) |
| Enforcement of `requires_auth`, `cache_seconds`, `PRIVATE_LINK`, `TENANT` | PARTIAL | stored and validated, no serving-plane consumer (see 16.1) |
| Per-version public-data approval | DEFERRED | Boolean today |
| Public pages for PRIVATE sites with data | DEFERRED | `apiBase: null` |
| Page-schema artifact storage quota | DEFERRED | the setting `storage.max-artifacts-mib-per-project` is referenced only by the code-build path (`code/BuildJobs.kt:147`); no page-app check exists (grep of the key) |

### 16.1 Known gaps (code versus documents, stated plainly)

| # | Gap | Where | Effect |
|---|---|---|---|
| G1 | **Stored publish policy fields are not enforced when serving.** `requires_auth`, `cache_seconds`, `link_token_hash`, `visibility = TENANT / PRIVATE_LINK` and `mode` have no consumer outside `project.publishconfig`; `PublishConfigService.decisionFor` (`:93`) and `linkMatches` (`:83`) have no caller. Deployments are only `PRIVATE` or `PUBLIC` (`deployments_visibility_check`). | `project/publishconfig/*`, `publish/*` | an app can store `PRIVATE_LINK` or `requiresAuth` and nothing in the sites gateway honours it; only the PUBLIC mismatch check and the public-data approval take effect |
| G2 | **Approval reset behaviour.** Code: every save without `acknowledgePublicData = true` sets `public_data_approved = false` (even a `PUT` that keeps `PUBLIC`); `rotateLink` keeps it. The H-C2-07 handoff says only a switch away from PUBLIC resets it. Code wins. | `PublishConfigService.kt:52` | a Studio client must resend the acknowledgement on every policy save (the release dialog reads the fresh policy and sends it) |
| G3 | **The approval is a Boolean, not version-bound.** Data bindings added in a later version are covered by the existing approval. "Approval per version" would need a contract change by C0. | `publish_configs.public_data_approved` | DEFERRED |
| G4 | **nginx query-id alphabet is narrower than the API's.** The gateway routes `queryId` = `[a-z0-9][a-z0-9-]{0,63}`; `PublicSiteAuthorizer` and the contract accept `^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$`. A query id with an upper-case letter, `.`, `_` or `:` cannot be called through the gateway. Bound pages are not affected (the definition id shape is the narrower one). | `infra/sites-gateway/default.conf.template`, `access/adapters/PublicSiteAuthorization.kt` | direct API calls only |
| G5 | `app.sites.data-api-base` is read **once at API start** (`SiteService.kt:46-49`); the comment in `application.yml:270-271` still says "read at request time". Code wins; a change needs a restart. | `application.yml` | documentation only |
| G6 | `C2_DEPLOY_CONTRACT.md` rule R2 (`activation_seq <= active_seq` is stale) differs from the code (`ScopeOrdering`: strictly lower is stale; equal is a resume only for the same operation). Code wins. | `publish/ReleaseScope.kt:58-67` | none |
| G7 | No page-app artifact storage quota (section 16 table). | | |
| G8 | The authenticated Data Runtime cannot be called from a published **code app** with a user session (opaque-origin CSP, SDK `credentials: "omit"`, CORS list, no gateway route); no proxy mode exists and no later source was found that changes this. | `docs/parallel/c2/PUBLISHED_RUNTIME_TOPOLOGY.md` section 0 | public pages use the anonymous route only |

---

## 17. Not to be confused: portal and API pinning

The public Studio / Platform / Admin portals and the public API (ports 3201-3203 behind the portal gateway 3210, API 18081 on the pilot host) run **approved, immutable release artifacts** managed by `scripts/public-portals.sh` and `scripts/public-api.sh` (explicit `deploy <sha>`; recovery never builds). This is infrastructure release management and is **different** from a published *site* release (this document). (src: [`docs/PUBLIC_DEPLOYMENT.md`](PUBLIC_DEPLOYMENT.md), `docs/parallel/c0/PUBLIC_DEPLOYMENT_PINNING.md`, `docs/parallel/c0/PUBLIC_API_PINNING.md`)

---

## Sources read

Code (all via `git show integration/v2:<path>`, first pass `7d46ec5c5f98`, re-checked at `28376de`):
`backend/src/main/kotlin/com/systemwebstudio/publish/` (`PublishController`, `DeploymentModel`, `DeploymentRepository`, `DeploymentFailure`, `DeploymentProcessor`, `PublishWorker`, `StepRunner`, `ReleaseDeployer`, `ReleaseService`, `ReleaseScope`, `JdbcScopeGuard`, `PublishedRelease`, `ReleaseHealthProbe`, `RuntimePlane`, `SiteService`, `SiteControllers`, `StaticSites`; `Domains`, `Forms` skimmed); `project/publishconfig/*` (4 files); `wiring/PublicDataController.kt`; `access/adapters/PublicSiteAuthorization.kt`; `access/Permission.kt`; `integration/deploy/DeployProvider.kt`; `integration/queue/JobQueue.kt`; `maintenance/ArtifactRetention.kt`, `CleanupService.kt`; `admin/AdminRetentionController.kt` (routes); `code/BuildPolicy.kt`; `settings/Settings.kt` (publish/retention rows); `runtime/ServerRuntime.kt` (routes), `common/ProductionConfigValidator.kt` (grep), `identity/SecurityConfiguration.kt` (grep), `tenancy/TenantContext.kt` (header); `workers/render/page-runtime.ts` (header, `resolveBindings`); `infra/sites-gateway/default.conf.template`; `compose.yml` (ports); `application.yml`, `application-local.yml`, `application-prod.yml`; migrations `V4`, `V13`, `V27`, `V30` (full) and filenames of V1..V32.

Documents: [`docs/contracts/v2/published-runtime.md`](contracts/v2/published-runtime.md); `docs/parallel/c2/PUBLISH_API_CONTRACT.md`, `PUBLISHED_RUNTIME_TOPOLOGY.md`, `RELEASE_ENVIRONMENTS.md`, `H_C2_07_PUBLIC_DATA_APPROVAL.md`, `APP_PUBLISH_AUTHORIZATION_AUDIT.md`, `BATCH2_HANDOFF.md`; `docs/parallel/c0/C2_DEPLOY_CONTRACT.md`, `PUBLIC_API_PINNING.md`, `PUBLIC_DEPLOYMENT_PINNING.md`, `DEMO_STACK_RUNBOOK.md`; [`docs/PUBLIC_DEPLOYMENT.md`](PUBLIC_DEPLOYMENT.md), [`docs/DEPLOYMENT.md`](DEPLOYMENT.md); [`docs/adr/0009-runtime-plane-static-artifacts.md`](adr/0009-runtime-plane-static-artifacts.md); `docs/parallel/DECISIONS.md` (selected entries), `docs/parallel/BOARD.md` (migration table); pending-import evidence: C1 `docs/parallel/c1/final-iam-hardening-report.md` .

Not opened (named in the request): `docs/parallel/c2/V31_CANDIDATE_ACTIVATION_PROPOSAL.md` (cited from BOARD only), `HANDOFF_C3_PUBLIC_QUERY.md`, `HANDOFF_C5_PAGE_SCHEMA_DATA.md`, `DECISION_REQUEST_PUBLISHED_RUNTIME.md`.

## Open questions / UNVERIFIED

Resolved in the finalization pass (code at `28376de`): Studio authoring of `public` and of the approval (exists, section 16); how `page-runtime.js` renders a refused query (section 14.5); the next free migration number is V34, none allocated; the C1 report and Decision A are now on `integration/v2`; the `data-api-base` read-once behaviour is confirmed against the yml comment (gap G5); page-app artifact quota has no enforcement (gap G7).

Still unverified:

1. Current green state of the pinned tests (`PublishApiContractTests`, `PublicDataEndpointTests`, `ReleaseCasTests`, `PublishAuthorizationTests`, ...): none were run (read-only rules).
2. Whether any consumer of the stored policy fields (G1) exists on a branch not merged; none on `integration/v2`.
3. Whether any change after 2026-10-07 enables the **authenticated** Data Runtime from a published code app (G8).
4. Exact HTTP shape of `BUILD_QUOTA_*` / `BUILDS_DISABLED` was read from `BuildPolicy.kt` only.
5. `docs/parallel/c2/V31_CANDIDATE_ACTIVATION_PROPOSAL.md` and the three C2 handoffs (`HANDOFF_C3_PUBLIC_QUERY.md`, `HANDOFF_C5_PAGE_SCHEMA_DATA.md`, `DECISION_REQUEST_PUBLISHED_RUNTIME.md`) were not opened.
