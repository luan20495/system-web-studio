# ADR 0020: Backups, restore drills, auto restart and stack profiles
Status: accepted and implemented (2026-10-04). Stage L.

## Backups
`scripts/backup-all.sh <local|public>` backs up everything a restore needs, each component verified:
| Component | Method | Verification |
|---|---|---|
| Platform PostgreSQL | `pg_dump -Fc` (existing atomic script) | `pg_restore --list`, sha256 sidecar |
| Apps DB server (server apps) | `pg_dump -Fc` per `app_*` database + `pg_dumpall --roles-only` | sha256 sidecar |
| MinIO (assets + artifacts buckets) | `mc mirror` to a local directory (versioning enabled on the source) | every object present with the same size; sha256 `MANIFEST` |
| Forgejo (repositories + its SQLite DB) | `forgejo dump` (consistent, made by Forgejo) | `tar -t` readable, sha256 sidecar |
Files are owner-only (umask 077, directory 700) under `backups/<env>/` (git-ignored). Retention: `BACKUP_RETENTION_DAYS` (14), never
fewer than `BACKUP_KEEP_MIN` (3) per component. Components not deployed in an environment are recorded as SKIPPED, never as success.
Off-host copies (S3/MinIO target, `MINIO_BACKUP_TARGET_URL`, or a synced folder) are the operator's choice and need credentials the
project does not hold — **required user action** for real disaster recovery (a backup on the same Mac does not survive losing the Mac).

## Restore drill
`scripts/restore-drill-all.sh` restores the **latest backup files** (not a fresh dump) into a throwaway `postgres:17.6` container with no
network: platform DB (schema version, users, projects), every app database; checks the Forgejo archive content and the MinIO manifests;
writes `drill.json`. Verified 2026-10-04: local (V22, 3 app DBs, Forgejo, 202 objects) and public (V15, 8 objects) PASS.

## Scheduling and monitoring
`scripts/backup-daemon.sh` runs the backup daily at `BACKUP_HOUR_UTC` (19 = 02:00 ICT) and the drill weekly (or after every backup with
`BACKUP_DRILL=always`); `public-up.sh` starts it by default, `run-local.sh` with `BACKUP_DAEMON=true`. The API only reads the status files
(`BACKUP_STATUS_DIRS`): Admin → Sao lưu shows per component the last success, age, size and errors and the drill result; an hourly check
raises CRITICAL alerts when a backup is older than 26 h, failed, or the drill failed / is older than 8 days.

## Auto restart
Containers: `restart: unless-stopped` (local and public compose). Host processes (API, UI, render worker): `scripts/watchdog.sh` checks
health every 30 s and, after two failures, re-runs the idempotent start script; stop scripts stop the watchdog first so an intentional
stop is not undone. Server app containers: Docker `on-failure:5` + the runner's reconcile loop. Production on Linux: systemd units with
`Restart=always` replace the watchdog.

## Stack profiles (`STACK_PROFILE`)
| Profile | Services | Features | Measured idle memory (2026-10-04, this Mac) |
|---|---|---|---|
| LEAN | postgres, redis, minio, rabbitmq, sites gateway + API, UI, render worker | websites, templates/blocks, AI, admin | containers ≈ 0.5 GiB + API 0.64 + UI 0.11 + render 0.08 ≈ **1.3 GiB** |
| MEDIUM | LEAN + Forgejo, package mirror, build runner | + source web apps, dashboards (sandbox builds up to 2 GiB each) | **≈ 1.6 GiB** + builds |
| FULL | MEDIUM + apps DB server, apps gateway | + server apps, internal tools, workflows (≤ 256 MiB per running app) | **≈ 1.7 GiB** + builds + apps |
The API reports features as unavailable when their services are not part of the profile (no Git URL → no code projects; no apps DB →
no server apps).
