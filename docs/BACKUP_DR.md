# Backup and disaster recovery

Day-2 commands are in [BACKUP_OPERATIONS.md](BACKUP_OPERATIONS.md). This page states what exists, what was actually run, and what is missing.
Every "tested" claim below refers to an automated drill that was run on a developer machine (macOS arm64, Docker, `postgres:17.6`,
`bitnamilegacy/minio:2025.7.23-debian-12-r5`) against **throwaway containers**, never against live production data.

## What holds state

| Store | Contains | Loss impact | Protection implemented |
| --- | --- | --- | --- |
| PostgreSQL | everything authoritative (users, projects, schemas, versions, audit, deployments) | total | (1) scheduled-ready logical backup `scripts/backup-postgres.sh`; (2) optional WAL archiving + PITR in `infra/postgres-pitr/` |
| MinIO/S3 | uploaded asset bytes (the DB only stores keys/metadata) | assets unavailable, rows remain | `scripts/backup-minio.sh` (versioning + mirror to a directory or another S3), `scripts/restore-minio.sh`. **The PostgreSQL backup does NOT contain MinIO data.** |
| Redis | sessions, rate-limit counters | everyone logs in again; counters reset | none needed (AOF is on) |
| RabbitMQ | in-flight publish jobs | none permanent: the recovery sweeper re-queues stale deployments from PostgreSQL | durable queues |

## 1. PostgreSQL logical backup - tested

`scripts/backup-postgres.sh`: timestamped `pg_dump -Fc` into `./backups` (gitignored), written as a temp file, verified with `pg_restore --list`,
SHA-256 sidecar, atomic `mv`, lock against concurrent runs, count-protected retention (`BACKUP_RETENTION_DAYS`, `BACKUP_KEEP_MIN`), no
credentials in arguments or logs, non-zero exit on any failure. `scripts/restore-postgres.sh` verifies checksum and structure first, restores
only into a **named** database and refuses a non-empty one without `--force`.

`scripts/test-backup-restore.sh` (throwaway `postgres:17.6`, real Flyway migrations V1-V6 applied, 14 tables seeded incl. components registry): **41 checks, 0 failed**:
* backup -> mutate (UPDATE all projects/users, DELETE assets/deployments/project_members/page_schemas, DROP TABLE, extra INSERT) -> restore into a new DB: row counts **and** content md5 of every table equal the pre-backup state; the dropped table is back; the append-only `audit_events` trigger survives the restore.
* `--force` restore over the mutated original DB equals the baseline; restore without a file picks the newest dump.
* refusals (all non-zero): non-empty target without `--force`, system DB names, missing `--target-db`, invalid names, bit-flipped dump, missing checksum file, malformed checksum, truncated dump with a recomputed checksum.
* failed backups (unknown DB, unreachable container, wrong password, bad settings) leave no dump or temp file; stale lock is taken over, live lock refused, two simultaneous runs -> one wins;
* retention: prunes old dumps but never below `BACKUP_KEEP_MIN`; sidecars are pruned with their dump;
* TCP mode (`PGHOST/PGPORT/PGPASSWORD` through a throwaway client container) works, password never appears in output.
Measured: ~50 KB dump of the seeded schema, backup 1 s, restore 1 s. Also run (read-only) against the dev DB: 87,527 B, 114 TOC entries, checksum verified, <1 s.
Not an RPO/RTO measurement at realistic size. `scripts/backup-restore-drill.sh` (older) still exists and is superseded by the test above.

## 2. Point-in-time recovery (WAL archiving) - tested in a drill, not enabled on the dev stack

`infra/postgres-pitr/` + `scripts/pitr-drill.sh`: archive_mode on with a non-overwriting `archive_command`, `pg_basebackup -Ft -z -X none`,
`restore_command` + `recovery_target_time` + `promote` in a new container. **Drill passed (all checks, 20 s total)**: rows A (1000) + B (500) present at T,
rows C (300, written after T) absent, the accidentally dropped table is back with its 200 rows, restored cluster promoted on a new timeline and writable,
source cluster untouched; then base backup #2 + `pitr-prune.sh` (keep 1) removed the old base backup and WAL older than its start, and a recovery from the
pruned archive still worked. Measured on a ~4 MB database: base backup 2 s, recovery 2-3 s. Details, caveats and production advice (pgBackRest / wal-g to
object storage) are in `infra/postgres-pitr/README.md`.

## 3. MinIO / object storage - tested

`scripts/backup-minio.sh` enables versioning (idempotent), mirrors the bucket to `./backups/minio/<bucket>/` (with a sha256 `MANIFEST.sha256`) or to another S3/MinIO
(`MINIO_BACKUP_TARGET_*`) and verifies the copy. `scripts/restore-minio.sh` restores the whole bucket from the directory/remote (manifest verified first) or recovers a
single object from a **previous version**. `scripts/test-minio-backup.sh` (two throwaway MinIO servers): **28 checks, 0 failed**:
* backup of 6 objects (1 MB-3 MB binaries, nested keys, a key with a space): mirror byte-identical, versioning on;
* overwrite + delete 2 objects: `restore-minio.sh --object KEY` brings each back with its original sha256 (including an object written before versioning was enabled, i.e. version `null`);
* delete everything (delete markers) -> full restore from the mirror -> all objects equal the original; the manifest file is not restored into the bucket;
* corrupted backup file, missing manifest, extra file, wrong credentials, missing bucket -> non-zero, nothing restored;
* mirror to a second MinIO, wipe the source, restore from the second endpoint: equal.
Measured: 6 objects (~5 MB) backup 2 s, restore 1 s. One transient `Connection closed by foreign host` from Docker Desktop's port proxy was seen; `mn_mc` retries (3 attempts).
The dev MinIO (`hbl-minio-1`) was deliberately not used for tests.

## Recovery objectives (not guaranteed)

| | Logical dump | PITR (if enabled) |
| --- | --- | --- |
| RPO | time since the last dump (schedule it; daily = up to 24 h) | ~`archive_timeout` (60 s) plus the unarchived tail; no automatic measurement |
| RTO | seconds at drill size; scales with database size | seconds at drill size; scales with base backup size + WAL to replay |

These are drill-size numbers, not promises for real data volumes.

## Restore order
1. PostgreSQL (dump restore or PITR), 2. object store (`restore-minio.sh`), 3. start the API (Flyway validates the schema), 4. Redis/RabbitMQ start empty, 5. the sweeper re-publishes unfinished deployments.
Restore PostgreSQL and MinIO to *compatible* points in time; assets uploaded after the DB backup have no row (orphan objects, harmless), rows referencing assets missing from the bucket show as broken assets.

## Not done (honest list)
* No scheduler is installed anywhere; cron/launchd/systemd units in `BACKUP_OPERATIONS.md` are examples that were not run.
* No off-host copy is configured by default (the scripts support a second MinIO/S3 for objects; dumps and the WAL archive must still be copied off the host: rsync/rclone/restic/object storage).
* Backups are not encrypted (dumps are mode 600 in a gitignored directory); encrypt before they leave the host.
* PITR is not enabled on `hbl-postgres-1` (`compose.yml` is unchanged); production would need a restart to switch it on, and a managed/wal-g/pgBackRest setup for WAL in object storage.
* No monitoring/alerting on backup age, `pg_stat_archiver.failed_count`, or drill failures.
* MinIO: single mirror copy (no dated snapshots), no lifecycle rule for old versions (versions accumulate), no object-lock/WORM, no cross-site replication; the mirror keeps objects deleted from the source unless `MINIO_MIRROR_REMOVE=true`.
* Redis and RabbitMQ are not backed up (by design) and a restore was not rehearsed with them.
* No timed full-stack recovery rehearsal (empty host -> DB + objects + API + UI) and no RPO/RTO measured at realistic data sizes.
* Only macOS/Docker was used; scripts target bash 3.2+ and GNU/BSD tools but were not run on Linux. Host-installed `pg_dump`/`pg_restore` mode (without Docker) was not exercised (the machine has no PostgreSQL client); container and client-container modes were.
* Retention uses file mtime; restoring files from another backup system with new mtimes will reset their age.
