# Implementation status

Honest status of this repository. Levels: **Development Ready: YES · Internal Demo Ready: YES · Production Ready: NO.**

| Area | Status | Evidence |
| --- | --- | --- |
| Sessions in Redis | Done | 52 backend tests incl. `SessionRestartTests` (two app contexts, same Redis) and `RedisOutageTests`; browser E2E restarts the API and the session survives; Redis container restart keeps sessions (AOF) |
| Auth, CSRF, CORS, throttling | Done | `AuthSecurityTests`; failures audited without password |
| RBAC + tenant isolation | Done | `ProjectApiTests`, `AuditApiTests`, E2E viewer/editor/publisher/disabled-user checks |
| Projects + settings | Done | CRUD, PATCH with revision CAS, validation, audit |
| Component registry, schema, patch engine | Done | `SchemaApiTests`; 10 components seeded |
| Prompts (mock LLM) | Done, LLM mocked | `PromptApiTests`; unsupported prompts create no version |
| Versions, restore, direct edit | Done | `VersionApiTests`; E2E verifies persistence across reload |
| Assets (MinIO presigned) | Done | `AssetApiTests` against a real MinIO container; E2E upload/delete |
| Publish (RabbitMQ state machine) | Done, provider mocked | `PublishApiTests` (idempotency, concurrent duplicates, failure path, content scan, RBAC) |
| Audit log | Done | append-only trigger tested; read API for workspace admins |
| Frontend http mode | Done | login, project list, studio, history/restore, edit, settings, assets, publish with polling |
| GitHub Pages (mock) | Unchanged behaviour | `e2e/pages-mock.mjs` 6/6 on the static export under `/system-web-studio/`; live URL returned 200 (it serves the previously deployed build until you push) |
| Backup/restore | Procedure proven, not automated | `scripts/backup-restore-drill.sh` passed |
| Ops (scripts, compose, health) | Done for local | `run-local.sh`, `smoke-test.sh`, `check.sh` |

## Evidence summary (this machine, 2026-10-01)
- Backend: 52 tests, 0 failures (Testcontainers Postgres 17.6, Redis 8.2.1, MinIO, RabbitMQ 4).
- `./scripts/check.sh`: typecheck, mock build, http build, backend tests, `npm audit --omit=dev` (0 vulnerabilities) all passed.
- Browser E2E (`e2e/full-flow.mjs`, Chrome, real stack): login/logout, create project, prompts, history/restore, direct edit, settings, assets, publish to RUNNING, backend restart, RBAC, disabled user, stale-revision conflict, layouts at 390/1280/1440/1920/2560 px with no horizontal overflow.
- Real Redis restart: sessions preserved. Real Redis stop: the pre-fix build returned 500; fixed to a structured 503 and covered by a test.

## Remaining (not blockers for local/demo, blockers for production)
Medium: no CSP on the UI; no MFA/SSO/password reset; no member-management API/UI; no retention for audit/idempotency/deployment-event tables; rate limiting keys on `remoteAddr` (needs trusted-proxy config); no upload malware scanning; no backup automation, PITR or timed recovery drill; no load or accessibility (WCAG) testing; single-node only.
Low: dev compose uses well-known local-only passwords (loopback-bound); `bitnamilegacy/minio` is a frozen local-only image; `registryReuse` is a V1 constant; no real Git provider; no rollback/unpublish; e2e/smoke scripts target macOS + Chrome.
Mocked by design: LLM, cloud deploy provider, external Git provider.
