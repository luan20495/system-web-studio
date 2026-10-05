# Deployment notes (single node / internal pilot)

What exists: the pilot on this Mac (`./scripts/public-up.sh`, Cloudflare tunnel, websites only) and the local full stack. Nothing has run on a
production-grade Linux host yet; the sections below say what the code expects there. Status of every feature: [IMPLEMENTATION_STATUS](IMPLEMENTATION_STATUS.md).

## Services (by stack profile, ADR 0020)
| Profile | Containers / processes | Adds |
|---|---|---|
| LEAN | PostgreSQL 17, Redis 8 (AOF, `noeviction`), RabbitMQ 4, MinIO/S3, sites gateway (nginx), API, Next.js server, render worker | websites, templates/blocks, AI, admin |
| MEDIUM | + Forgejo, Verdaccio package mirror, build runner (host process driving Docker) | source-code apps, dashboards |
| FULL | + apps DB server (separate PostgreSQL), apps gateway (nginx), per-app containers on per-app internal networks | server apps, internal tools, workflows |
`STACK_PROFILE=lean|medium|full ./scripts/run-local.sh`. Server apps additionally need the admin policy `server-apps.enabled` (HIGH risk, off by default).

## Production environment (the `prod` profile refuses to start on unsafe values)
Required (no defaults): `DATABASE_URL/USER/PASSWORD`, `REDIS_HOST/PORT/PASSWORD`, `RABBITMQ_HOST/PORT/USER/PASSWORD`, `MINIO_ENDPOINT`, `MINIO_PUBLIC_ENDPOINT` (https),
`MINIO_ROOT_USER/PASSWORD`, `CORS_ALLOWED_ORIGINS` (https, no localhost, no `*`), `SERVER_ADDRESS`, `TRUST_PROXY` (+ `TRUSTED_PROXY_CIDRS` when true), `FORMS_IP_SALT`.
`ProductionConfigValidator` additionally refuses: profile `local` together with `prod`, passwords shorter than 12 or looking like dev defaults, non-Secure
session cookie, Swagger enabled, **public sign-up on** (unless `SIGNUP_ALLOW_IN_PROD=true`), `DEPLOY_PROVIDER=mock`, a weak `BOOTSTRAP_ADMIN_PASSWORD`,
`SCIM_ENABLED` without a 32-char `SCIM_TOKEN`, `SAML_ENABLED` without OIDC, `SERVER_APPS_ENABLED` without `SECRETS_MASTER_KEY` (base64 of 32 bytes) /
`APPS_GATEWAY_TOKEN` / `APPDB_ADMIN_PASSWORD`, a short `BUILD_RUNNER_TOKEN`. AI provider keys are optional for boot (providers off = simulator).
Terminate TLS in front (Cloudflare tunnel or a reverse proxy); keep API, DB, Redis, RabbitMQ, MinIO API, Forgejo, gateways on loopback/private networks.

## Linux runtime host (required before server apps are production)
Docker Desktop's VM is not a production isolation boundary (ADR 0019). Target: a Linux VM running Docker with **gVisor** (`runsc`) and the same runner:
`RUNTIME_OCI=runsc BUILD_RUNNER_TOKEN=… RUNNER_API=https://<api-private-address> node workers/runner/runner.mjs`, with `hbl_build` (internal) and the per-app
internal networks created by the runner, the apps DB server and apps gateway on that host, and nothing from the host mounted. Use systemd `Restart=always`
units for the runner (and the API/UI if they run there) instead of `scripts/watchdog.sh`.

## Health
`/actuator/health/liveness|readiness` (db, redis, rabbit, minio). Admin → Sức khỏe hệ thống shows live probes in five honest states
(Healthy, Degraded, Unavailable, Unknown, Not configured): API, PostgreSQL, Redis, RabbitMQ, MinIO, AI provider, OIDC, render worker, Git server,
build runner (poll heartbeat, queue age, 24 h failures), server runtime (apps DB + gateway, failed deployments), backups. Backup and provider failures also raise admin alerts.

## Migrations (Flyway, V1–V23)
Runs at startup; Hibernate is in `validate` mode so drift fails fast. Verified on a fresh database (every integration test class migrates V1→latest in a
throwaway PostgreSQL) and on the pilot database (V15→V23, 2026-10-04). Migrations are forward-only and never edited after release; Flyway history is never
edited by hand. **Before any migration on a live database take a backup** (`./scripts/backup-all.sh public`, which writes a verified, checksummed dump).
Recovery if a release must be undone: stop the API, restore the pre-migration dump into a NEW database (`scripts/restore-postgres.sh --target-db …`), point
`DATABASE_URL` at it, start the previous release. A failed migration leaves Flyway marked failed: restore the dump instead of repairing history.

## Backups
See [BACKUP_DR](BACKUP_DR.md): daily `backup-all.sh` (platform DB, app DBs, MinIO, Forgejo), weekly restore drill, monitoring in the admin console, and the
optional off-host mirror (`OFFSITE_S3_*`). **Production requires the off-host copy.**

## GitHub Pages
Static mock demo only: `NEXT_PUBLIC_API_MODE=mock STUDIO_BASE_PATH=/system-web-studio npm run build`, publish `out/` (verified by `e2e/pages-mock.mjs`).
There is no CI in this repository by decision.
