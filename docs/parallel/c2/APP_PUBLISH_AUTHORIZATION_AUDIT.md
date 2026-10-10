# C2 — APP_PUBLISH authorization audit (publish, rollback, unpublish)

Owner C2 · baseline `integration/v2 @ 13855e5` · branch `fix/c2-app-publish-authorization` · 2026-10-09. **No production code and no permission definition changed**; the proof is `PublishAuthorizationTests` (14 tests, direct API calls, no UI).

`PROJECT_PUBLISH` is the storage constant of the canonical code `APP_PUBLISH` (`PermissionCodes`); it is not renamed. Frontend gating is **not** part of the security model: `FRONTEND_GATING_REQUIRED_FOR_SECURITY = NO`. `SERVER_AUTHORITY = AccessService.forProject(...)` + `AccessContext.require(PROJECT_PUBLISH)`, resolved from the database on every request.

## 1. User-triggerable mutation routes (all verified)

| Route | Check (exact) | Order |
|---|---|---|
| `POST /api/v1/workspaces/{w}/projects/{p}/publish` | `ctx = access.forProject(me, w, p); ctx.require(PROJECT_PUBLISH)` — `PublishController.publish` | before the idempotency key, the revision check, the policy checks and the deployment insert |
| `POST …/site/rollback` | `access.forProject(…).require(PROJECT_PUBLISH)` — `SiteControllers.rollback` | before `releases.operationId`, the deployment lookup and the scope; the deployment must also belong to the project (`d.project_id = ?`) |
| `DELETE …/site` | `access.forProject(…).require(PROJECT_PUBLISH)` — `SiteControllers.unpublish` | before the scope |
| `POST …/runtime/rollback`, `POST …/runtime/stop` (server apps) | `ServerRuntimeController.ctx(…, PROJECT_PUBLISH)` | before the application-kind check (a viewer gets 403, never `NOT_A_SERVER_APP`); `server_deployments` are looked up with `project_id = ?` |
| `POST/DELETE …/domains…` (add, verify, check-tls, remove) | `SiteDomainController.ctx(…, publish = true)` | before the kind check; `GET` needs only read access |
| `PUT/POST …/publish-config…` (policy and public-data approval) | `ctx.require(PROJECT_PUBLISH)` (`PublishConfigApi`, unchanged) | listed for completeness |

Read routes (`GET …/site`, `GET …/deployments`, `GET …/deployments/{id}`) need project read access only, by design.

There is **no** retry-deployment, re-serve/redeploy, manual-recovery or alternative rollback/unpublish HTTP route. Archive / restore of an application (`ProjectLifecycle`, `PROJECT_DELETE`, and the platform-admin variants) take a site offline through `ReleaseService.takeOffline`; they belong to the project lifecycle, not to publishing, and are not changed here.

## 2. Internal entry points — `INTERNAL_NOT_USER_AUTH_BOUNDARY`

| Entry point | Trigger | Reachable from HTTP? |
|---|---|---|
| `PublishWorker.onMessage` (`@RabbitListener(Queues.PUBLISH)`) | a message the API published **after** the commit of an accepted deployment | no |
| `DeploymentRecovery.republishStale` (`@Scheduled`, default 30 s) | the scheduler; re-publishes stale deployment ids it reads from the database | no |
| `DeploymentProcessor` (`@Service`) | called by the worker only | no |
| `BuildJobs` (code apps) → `queue.publish(PUBLISH, dep)` after the build runner reports | the runner, authenticated by the runner token (`/internal/**`), never a user session | not with a user identity |

The request was authorized when it was accepted (the 202 is only given after `require(PROJECT_PUBLISH)` and the insert of a durable row). The asynchronous steps act on that frozen operation; adding an `APP_PUBLISH` check there would only make a pipeline that was legitimately accepted fail half-way when a membership changes. No re-authorization is added.

## 3. What the tests prove (`PublishAuthorizationTests`)

APP_VIEW only and APP_EDIT only: 403 on publish, rollback, unpublish, server-runtime rollback / stop and domain routes, with deployments and their statuses, events, artifacts, idempotency keys, audit entries and the active pointer and its version unchanged; APP_PUBLISH: allowed (the publisher still cannot edit); no session: 401; another workspace and a forged workspace/project pair: 404; the publisher of application A has no authority over B (404); a forged `deploymentId` of another application in a rollback is `400 DEPLOYMENT_NOT_RESTORABLE` with both pointers unchanged; a grant downgraded or removed **while the same session stays open** is refused on the next request (403 / 404) and works again when the database says so; a user disabled while the session is open is `401 ACCOUNT_DISABLED`. A mutation check (the three `require()` calls removed) is caught by 5 tests.

## 4. Noted, not changed
* A rejected rollback by an **authorized** caller (for example a forged `deploymentId`) keeps its Idempotency-Key row: the key is judged before the deployment is looked at, by design (`SiteControllers.rollback`). It binds that key to that request and moves nothing, so the test compares the release records and not the key count in that one case.
* Request-body validation (`@Valid`) runs before the permission check on every controller in the application, so a malformed body is 400 whatever the caller holds; it discloses nothing about the project.
