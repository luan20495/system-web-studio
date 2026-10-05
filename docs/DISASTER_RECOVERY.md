# Disaster recovery runbook

Companion to [BACKUP_DR.md](BACKUP_DR.md) (what is backed up, drill evidence) and [BACKUP_OPERATIONS.md](BACKUP_OPERATIONS.md) (commands). Targets below are **configured goals for a single-node pilot**, not guarantees; nothing here has been measured on a production-sized data set.

## Targets
| Data | RPO target | RTO target | How it is met |
| --- | --- | --- | --- |
| PostgreSQL (business data, audit) | ≤ 5 min with PITR enabled; ≤ 24 h with daily dumps only | ≤ 1 h | WAL archiving (`infra/postgres-pitr`) + base backup, or `scripts/backup-postgres.sh` on a schedule |
| MinIO assets | ≤ 24 h | ≤ 2 h | bucket versioning + `scripts/backup-minio.sh` mirror to a second target |
| Redis (sessions, rate-limit counters) | none (disposable) | minutes | restart; users sign in again |
| RabbitMQ (in-flight publish jobs) | none needed | minutes | durable queue; the sweeper re-publishes from PostgreSQL |
Drill-size evidence (tiny DBs): logical restore seconds; PITR base backup 2 s + recovery 2–3 s; MinIO restore 1 s.

## Scenarios
### 1. PostgreSQL lost or corrupted
Impact: API readiness 503, everything stops (it is the source of truth).
1. Stop the API (`./scripts/stop-local.sh` or the service). Keep the damaged volume for forensics.
2. Choose: **PITR** to just before the incident (`infra/postgres-pitr/pitr-restore.sh`, `recovery_target_time`) or **latest dump** (`scripts/restore-postgres.sh <dump> --target <db>`; verifies the checksum first).
3. Point `DATABASE_URL` at the restored database, start the API; Flyway validates the schema (it refuses to boot on drift).
4. Verify: `scripts/smoke-test.sh`, compare row counts, check `audit_events` is intact (the append-only trigger is restored with the schema).
5. PITR-restored clusters start with archiving **off**: re-enable it and take a new base backup before declaring recovery complete.
6. Reconcile MinIO: assets uploaded after the restore point are orphan objects (harmless); asset rows whose object is missing show as broken assets.

### 2. Redis lost
Impact: everyone is signed out; login throttle counters reset; API returns `503 DEPENDENCY_UNAVAILABLE` (fast, 2 s timeout) while Redis is down and recovers by itself afterwards. **Business data is safe.** Start Redis (AOF replays if the volume survived). Verified: a Redis restart keeps sessions (AOF); a Redis outage gives structured 503s and the same session works again afterwards.

### 3. MinIO lost
Impact: uploads/downloads fail, readiness 503; page schemas and versions are unaffected (they live in PostgreSQL). Restore the bucket from the mirror (`scripts/restore-minio.sh`, or `--object` for one file / a previous version). Rows for assets newer than the mirror will point at missing objects: delete them in the UI or let the cleanup job reap abandoned `PENDING` ones.

### 4. RabbitMQ lost
Impact: publishing is accepted (`202`) but jobs do not run. Queues are durable, so a broker **restart** loses nothing. If the broker **volume** is lost, queued messages are gone, but every unfinished deployment is still a row in PostgreSQL and the recovery sweeper re-publishes stale `QUEUED`/in-progress deployments (every `app.deploy.recovery-interval-ms`). Verified by `QueueRecoveryTests` (worker stopped, lost enqueue, worker died mid-pipeline). Dead-lettered jobs (`studio.publish.dlq`) need a human decision: inspect, then re-publish or mark failed.

### 5. API crash / host reboot
Sessions survive (Redis). In-flight HTTP requests fail and clients retry; deployments interrupted mid-step are resumed from their last state by redelivery or the sweeper, without repeating finished steps (tested). Graceful shutdown (SIGTERM) drains requests first.

### 6. Frontend failure
The UI is stateless; restart it. The API and published data are unaffected. The static mock demo (GitHub Pages) is independent of all of this.

### 7. Failed deployment
`FAILED` is terminal and shows the reason in the UI and `deployment_events`; fix the cause (usually page content rejected by the policy/security check) and publish again (new idempotency key). No rollback of a RUNNING deployment is implemented (the provider is a mock).

### 8. Credential compromise
* **User account / session:** disable the user (`users.enabled=false`): access ends on the next request. Review `audit_events` by `actor_id`.
* **All sessions:** `redis-cli --scan --pattern 'spring:session:*' | xargs redis-cli del` (everyone signs in again).
* **OIDC client secret:** rotate at the IdP, update `OIDC_CLIENT_SECRET`, restart.
* **Database / Redis / RabbitMQ / MinIO credentials:** rotate in the service, update the secret store, restart the API. Presigned URLs expire after `presign-minutes` (10).
* **Metrics token:** change `METRICS_TOKEN`, update the scraper.
* **Leaked repository secret:** rotate first, then clean history; `scripts/secret-scan.sh` checks history and tree.
* Afterwards: review the audit log for the exposure window, force sign-out, document the incident.

## Drills to schedule
Quarterly: logical restore (`scripts/test-backup-restore.sh`), PITR (`scripts/pitr-drill.sh`), MinIO (`scripts/test-minio-backup.sh`); after any schema change: restore the latest dump into a scratch DB and run the smoke test. A timed full-stack rehearsal on an empty host has **not** been done.
