# Architecture

A modular monolith: one Kotlin/Spring Boot process plus a browser UI. External systems sit behind small ports so they can be swapped without touching business code.

```
Browser ── Next.js (same-origin /api proxy) ──► Spring Boot API ──► PostgreSQL   source of truth (schemas, versions, audit, deployments)
                                                   │         ├────► Redis        sessions (Spring Session), rate limits
                                                   │         ├────► MinIO        assets via presigned URLs (S3 API)
                                                   │         └────► RabbitMQ ──► publish worker (same JVM, @RabbitListener) ──► DeployProvider
                                                   └─ ports: LLMProvider (mock), DeployProvider (mock), GitProvider (DB-backed), StorageProvider, JobQueue, SecretProvider
```

## Data model (Flyway V1–V5)
`users`, `workspaces`, `workspace_members`, `projects` (revision counter + settings), `project_members`; `components`/`component_versions` (the registry, props schema per version); `page_schemas` (current schema per project), `project_versions` (immutable snapshots, kind INITIAL/PROMPT/EDIT/RESTORE), `prompts`, `prompt_runs`, `component_usage`; `assets`; `deployments`, `deployment_events`, `idempotency_keys`; `audit_events` (append-only, enforced by a trigger that rejects UPDATE/DELETE/TRUNCATE).

## Page schema and the AI boundary
A page is JSON: `{page, sections:[{id, type, componentVersion, props}]}`. The LLM port returns **declarative operations** (`ADD_SECTION`, `REMOVE_SECTION`, `MOVE_SECTION`, `UPDATE_SECTION`, `UPDATE_PROP`, `ADD_ITEM`, `REMOVE_ITEM`); there is no field that can carry code or SQL. `SchemaPatchEngine` applies them to a copy, `PageSchemaValidator` validates the result against the component registry (types, required props, lengths, item shapes), and only then is it committed. Direct editing in the UI uses the same `PATCH …/schema` endpoint, so AI edits and manual edits share validation, versioning and audit. Nothing generated is executed on the JVM; the preview renders escaped data into a sandboxed iframe.

## Concurrency
Every mutation carries `expectedRevision`. `SchemaCommitService` does `UPDATE projects SET revision = revision + 1 … WHERE revision = ?` in the same transaction as the new immutable version, usage rows and audit row; zero rows updated means `409 REVISION_CONFLICT`. Version numbers are gapless per project. Restore never rewrites history: it appends a RESTORE version copying the old snapshot.

## Publish pipeline
`POST …/publish` (needs `Idempotency-Key`) inserts a QUEUED deployment and an idempotency row in one transaction, then enqueues the id on RabbitMQ **after commit**. The worker advances `QUEUED → POLICY_CHECK → SECURITY_CHECK → BUILDING → DEPLOYING → RUNNING` (or `FAILED`) with compare-and-set transitions, one event and one audit row per step. Duplicate or redelivered messages lose the CAS and do nothing. A scheduled sweeper re-publishes deployments stuck in QUEUED or in progress (broker down at commit time, worker crash). Failed messages retry with backoff and then go to a dead-letter queue. Policy check = registry validation; security check = forbidden content scan (script tags, `javascript:` URLs, inline handlers). The deploy provider is a mock that returns a clearly labelled mock URL.

## Identity and access
Session login (Argon2id), server-side session in Redis, `HttpOnly` cookie, CSRF double-submit token for every unsafe method. Permissions come from `PermissionMatrix` (workspace role + project role + system admin) and are evaluated on the server for every request; the UI only hides buttons. A per-request active-user check makes a disabled account lose access immediately.

## What is mocked
The LLM (deterministic keyword planner), the cloud deploy provider (mock URL), and the Git provider (versions live in PostgreSQL; no SHA is invented). Everything else is real in local and in tests.
