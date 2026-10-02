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

## Added in Phase 6 — multiple AI providers (2026-10-02, ADR 0007)
| Method | Path | Permission | Notes |
| --- | --- | --- | --- |
| GET | /ai/status | session | adds `providers[] {id, name, paid, models[], dataNotice}`; `models[]` items add `provider`, `paid`; `provider` may be `providers` (no OpenRouter, other providers enabled) |
| POST | …/{p}/prompts | PROJECT_EDIT | `model` may be `provider:model` (`openai:`, `anthropic:`, `gemini:`, `local:`); `400 MODEL_NOT_ALLOWED` unless configured, listed and enabled; response `provider` is the provider id |
| GET | /admin/ai/providers | system admin | every provider: `configured`, `paid`, `endpointHost` (no key), `defaultPolicy`, models with `enabled` and current `price`, `configHint` (env variable names) |
| POST | /admin/ai/providers/{id}/probe | system admin | live model listing → `{ok, latencyMs, detail}`; `409 NOT_CONFIGURED`, `404 PROVIDER_NOT_FOUND` |
| PUT | /admin/ai/models/policy | system admin | `{modelId, enabled}`; `404 MODEL_NOT_FOUND` for models that are not listed for a configured provider; audited `AI_MODEL_POLICY` |
| GET / POST | /admin/ai/pricing | system admin | catalog rows; POST `{modelId, inputUsdPerMTok, outputUsdPerMTok, note?}` (0–10000) applies from now; rows cannot be edited; audited `AI_PRICING_ADDED` |
| GET | /admin/ai/calls | system admin | rows add `costSource`, `requestId` |

## Added in Phase 5 — templates and contributed blocks (2026-10-02, ADR 0006)
| Method | Path | Permission | Notes |
| --- | --- | --- | --- |
| GET | /templates?scope=company\|mine&q | session | active templates (company, or mine), each with `schema`, `sections`, `componentTypes`, `version`, `canEdit` |
| GET | /templates/{id} | visible | 404 `TEMPLATE_NOT_FOUND` when not visible (private of someone else) |
| POST | /workspaces/{w}/projects/{p}/templates | PROJECT_EDIT | `{name, description?, templateId?}` → `{template, removedImages}`; copies the SAVED page, clears `asset://` refs; with `templateId` = new version (author while private, or admin; else 403) |
| PATCH / DELETE | /templates/{id} | author (private) or admin | rename / archive; COMPANY templates: admin only |
| POST | /workspaces/{w}/projects | PROJECT_CREATE | body adds optional `templateId`; 404 if not visible, `422 TEMPLATE_OUTDATED` if it no longer validates (nothing is created) |
| GET | /component-packages?scope=company\|mine | session | company = approved and not deprecated (`current` = approved version, no history); mine = all my blocks with versions and review history |
| GET | /component-packages/{id} | owner, admin, or listed | 404 `BLOCK_NOT_FOUND` otherwise |
| POST | /workspaces/{w}/projects/{p}/component-packages | PROJECT_EDIT | `{sectionId, name, description?, packageId?}` → `{block, removedImages}`; props are read from the SAVED section; only ACTIVE base components (`422 COMPONENT_NOT_ELIGIBLE`); `packageId`: same base (`422 BASE_MISMATCH`), not in review (`409 IN_REVIEW`) or deprecated |
| PATCH | /component-packages/{id} | owner (not in review) or admin | rename |
| POST | /component-packages/{id}/submit | owner, status PRIVATE | runs automated checks → `{block, passed, checks[]}`; passed → REVIEW |
| POST | /component-packages/{id}/withdraw | owner, status REVIEW | back to PRIVATE |
| DELETE | /component-packages/{id} | owner | only if never approved and not in review (`409 NOT_DELETABLE`) |
| GET | /admin/component-packages?status=REVIEW\|APPROVED\|PRIVATE\|DEPRECATED\|ALL&page | system admin | `{page, counts}` |
| GET | /admin/component-packages/{id} | system admin | versions, validation reports, review history |
| POST | /admin/component-packages/{id}/review | system admin, not the owner | `{decision: APPROVE\|REJECT, version?, comment?}`; `403 SELF_REVIEW`, `409 NOT_IN_REVIEW`, `409 STALE_REVIEW`, `409 VALIDATION_FAILED` (re-check at approval), `400 COMMENT_REQUIRED` for reject |
| POST | /admin/component-packages/{id}/deprecate, /restore | system admin | deprecate hides from the library; pages are not changed |
| GET | /admin/templates?visibility&status&q&page | system admin | all templates |
| POST | /admin/templates/{id}/visibility, /status | system admin | `{visibility: PRIVATE\|COMPANY}`, `{status: ACTIVE\|ARCHIVED}` |

## Added in Phase 4 — AI usage accounting (2026-10-02)
| Method | Path | Permission | Notes |
| --- | --- | --- | --- |
| POST | …/{p}/prompts | PROJECT_EDIT | response adds `usage: {attempts, promptTokens, completionTokens, totalTokens, costUsd, latencyMs}`; `null` for the simulator; token/cost fields `null` when the provider reported none. New error `429 AI_TOKEN_LIMIT` with `details {scope: user|workspace, used, limit}` when a token budget is used up (checked before calling the model) |
| GET | …/{p}/prompts | read | items add `provider`, `model`, `aiCalls`, `totalTokens`, `costUsd` |
| GET | /admin/ai/usage?days=1..366 | system admin | `{days, since, totals, byModel[], byUser[], byWorkspace[], daily[], limits, tokenSource, costSource}`; totals = `UsageTotals {calls, failedCalls, callsWithoutUsage, promptTokens, completionTokens, totalTokens, costUsd, costReportedCalls, avgLatencyMs}`; `days=1` = today |
| GET | /admin/ai/calls?page&size&outcome&model&userId&workspaceId | system admin | one row per upstream model call (fail-over attempts included): user, workspace, project, model, `outcome OK|BAD_OUTPUT|ERROR`, HTTP status, tokens, cost, latency |

Source of truth: table `ai_calls` (V10). Values are copied from OpenRouter's `usage` object (the request sets `usage.include=true`); nothing is estimated and an unreported value stays `null`.

## Added for the AI Software Factory (2026-10-02)
| Method | Path | Permission | Notes |
| --- | --- | --- | --- |
| GET | /auth/me | session | now includes `systemAdmin` (live value) |
| GET | /projects/{id} | read | resolves the workspace for deep links; 404 when not visible |
| GET | /workspaces/{w}/projects?page&size&q&scope | member | optional server paging (size ≤ 100), `scope=all|owned|shared`, total in `X-Total-Count`; without `page` the full list (backwards compatible) |
| GET | …/versions?limit, …/prompts?limit | read | default 100, max 500; prompts are now **newest first** |
| GET | /components?details=true | session | includes props schemas and `usedInProjects` (company-wide count) |
| GET | /me/usage | session | real AI quota from the enforcing Redis counter, prompts today; `tokensLast24h`, `tokensLimitPerDay` (null = off), `usageLast30Days` (UsageTotals) |
| GET | /me/activity?limit | session | the caller's own audit events |
| GET | /admin/overview | system admin | counts from the database (no cost/token figures) |
| GET | /admin/users?page&q&status, /admin/users/{id} | system admin | status = all|active|disabled|admin; detail has memberships, projects, active sessions, recent activity |
| PATCH | /admin/users/{id}/status `{enabled}` | system admin | disabling also revokes every session; `409 CANNOT_DISABLE_SELF`, `409 LAST_SYSTEM_ADMIN` |
| POST | /admin/users/{id}/revoke-sessions | system admin | `{revoked}`; uses the indexed Redis session repository |
| GET | /admin/workspaces?page&q, /admin/workspaces/{id} | system admin | members, projects, activity |
| GET | /admin/applications?page&q&visibility&status&workspaceId, /admin/applications/{id} | system admin | inventory: owner, members, visibility, revision, latest version, last publish; detail: members, versions, prompts (model/outcome), deployments, audit |
| POST | /admin/applications/{id}/transfer-ownership `{userId}` | system admin | new owner must be an enabled workspace member (422 otherwise); old owner becomes EDITOR |
| GET | /admin/audit?page&actor&action&workspaceId&projectId&requestId&from&to, /admin/audit/actions | system admin | organisation-wide, paged |
| GET | /admin/ai | system admin | provider, models, limits, counts by model/outcome, recent runs; `tokenAccounting`/`costAccounting` = `PROVIDER_REPORTED` |
| GET | /admin/components | system admin | registry + usage (projects, sections) + props schema |
| GET | /admin/system/health | system admin | live probes: API, PostgreSQL, Redis, RabbitMQ, MinIO, OpenRouter, OIDC → HEALTHY / DEGRADED / UNAVAILABLE / NOT_CONFIGURED; uptime, schema version, queue depths |
| GET | /admin/settings | system admin | effective configuration, read-only, no secrets |
Non-admins get `403 ADMIN_REQUIRED` on every `/admin/**` call. Asset references: component props with `format: "asset"` accept only `asset://<uuid>` of a READY asset of the same project (`422 SCHEMA_INVALID` / `400 ASSET_NOT_FOUND`).

## Schema operations
`{type, sectionId?, sectionType?, itemId?, arrayPath?, path?, value?, item?, props?, beforeSectionId?, afterSectionId?, index?}` with `type` ∈ `ADD_SECTION, REMOVE_SECTION, MOVE_SECTION, UPDATE_SECTION, UPDATE_PROP, ADD_ITEM, REMOVE_ITEM`. Invalid operations or a result that violates the registry → `422 SCHEMA_INVALID` (result violates the registry) / `400 INVALID_OPERATION` (operation cannot be applied); nothing is stored.

## Deployment states
`QUEUED → POLICY_CHECK → SECURITY_CHECK → BUILDING → DEPLOYING → RUNNING`, or `FAILED` from any non-terminal state. Terminal: RUNNING, FAILED, ROLLED_BACK (reserved; no rollback is implemented).

## Frontend modes
`NEXT_PUBLIC_API_MODE=mock` (default; GitHub Pages) never calls this API. `http` uses the endpoints above through the same-origin `/api` proxy and never falls back to mock data.
