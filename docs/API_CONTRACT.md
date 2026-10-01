# API contract (v1)

Base path `/api/v1`, JSON, session cookie auth. The live OpenAPI document is at `/v3/api-docs` (Swagger UI at `/swagger-ui.html`) when the `local` profile is active; it is disabled by default.

## Conventions
- **CSRF**: `GET /auth/csrf` → `{token}` (also sets `XSRF-TOKEN`). Send it as `X-XSRF-TOKEN` on every POST/PATCH/PUT/DELETE, including login. Missing/invalid → `403 CSRF_INVALID`.
- **Errors** always `{code, message, requestId, details}`; `requestId` equals the `X-Request-Id` response header and the `request_id` in logs and audit rows. Never a stack trace.
- **Optimistic concurrency**: mutations of a project carry `expectedRevision` (the project's `revision`). A stale value → `409 REVISION_CONFLICT` with `details.currentRevision`. Clients reload; they never overwrite silently.
- **Idempotency**: `POST …/publish` requires `Idempotency-Key` (8–120 chars `A-Za-z0-9_.:-`). Same key + same body → the original deployment (`Idempotent-Replay: true`); same key + different body → `409 IDEMPOTENCY_KEY_REUSED`.
- **Authorization** is evaluated on the server per request. A workspace or project the caller cannot see is `404`; a visible one lacking the permission is `403`.
- **Rate limits** (Redis fixed windows): login 5 failures / 15 min per username and 50 per IP, prompts 30/min, publish 10/min, uploads 60/min per user → `429` with `Retry-After`.

## Endpoints
| Method | Path | Permission | Notes |
| --- | --- | --- | --- |
| GET | /auth/csrf | public | |
| POST | /auth/login | public | `{username,password}`; 401 `INVALID_CREDENTIALS`/`ACCOUNT_DISABLED`; 429 when throttled |
| GET | /auth/me | session | `{id,username,displayName,roles,workspaces[{id,name,role}]}` |
| POST | /auth/logout | session | invalidates the Redis session |
| GET | /components, /components/{id} | session | component registry with props schemas |
| GET/POST | /workspaces/{w}/projects | member / PROJECT_CREATE | list is filtered by project membership (workspace admins see all) |
| GET/PATCH/DELETE | /workspaces/{w}/projects/{p} | read / SETTINGS / DELETE | PATCH is partial + `expectedRevision`; DELETE is a soft delete; GET includes `permissions[]` |
| GET | …/{p}/schema | read | `{schema, revision, version}` |
| PATCH | …/{p}/schema | PROJECT_EDIT | `{expectedRevision, operations[], summary?}` → new EDIT version |
| POST/GET | …/{p}/prompts | PROJECT_EDIT / read | `{prompt, expectedRevision}` → `{outcome: UPDATED|NO_CHANGE|UNSUPPORTED, message, schemaPatch[], pageSchema, revision, version, registryReuse}` |
| GET | …/{p}/versions, …/versions/{id} | read | newest first; `current`, `restorable` flags |
| POST | …/{p}/versions/{id}/restore | PROJECT_EDIT | `{expectedRevision}` → appends a RESTORE version |
| POST | …/{p}/assets/upload-url | PROJECT_EDIT | `{fileName,contentType,size}` → presigned PUT; allowed: png, jpeg, webp, gif (≤10 MB), pdf (≤20 MB) |
| POST | …/{p}/assets/complete | PROJECT_EDIT | server verifies size and type in MinIO before marking READY |
| GET/DELETE | …/{p}/assets, …/assets/{id} | read / PROJECT_EDIT | download URLs are short-lived presigned GETs |
| POST | …/{p}/publish | PROJECT_PUBLISH | `{visibility: PRIVATE|PUBLIC, expectedRevision}` + `Idempotency-Key` → `202` deployment |
| GET | …/{p}/deployments, …/deployments/{id} | read | status + ordered events; `mock:true` when the provider is the mock |
| GET | /workspaces/{w}/audit-events | AUDIT_READ (workspace admin) | filters `projectId`, `action`, `limit` ≤ 200 |

## Schema operations
`{type, sectionId?, sectionType?, itemId?, arrayPath?, path?, value?, item?, props?, beforeSectionId?, afterSectionId?, index?}` with `type` ∈ `ADD_SECTION, REMOVE_SECTION, MOVE_SECTION, UPDATE_SECTION, UPDATE_PROP, ADD_ITEM, REMOVE_ITEM`. Invalid operations or a result that violates the registry → `400 SCHEMA_INVALID`/`INVALID_OPERATION`; nothing is stored.

## Deployment states
`QUEUED → POLICY_CHECK → SECURITY_CHECK → BUILDING → DEPLOYING → RUNNING`, or `FAILED` from any non-terminal state. Terminal: RUNNING, FAILED, ROLLED_BACK (reserved; no rollback is implemented).

## Frontend modes
`NEXT_PUBLIC_API_MODE=mock` (default; GitHub Pages) never calls this API. `http` uses the endpoints above through the same-origin `/api` proxy and never falls back to mock data.
