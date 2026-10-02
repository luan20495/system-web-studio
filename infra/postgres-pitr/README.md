# PostgreSQL point-in-time recovery (WAL archiving)

## Status: PITR was actually tested

`scripts/pitr-drill.sh` ran end-to-end on this machine (macOS arm64, Docker, `postgres:17.6`) and **passed** (all checks, total 20 s,
last run 2026-10-01). It only uses throwaway containers/volumes named `drill-pitr-*` and removes them on exit. What it proves:
base backup + continuous WAL archive can rebuild the database as of a chosen moment in a **new** container, after an accidental
`DELETE` and `DROP TABLE`, and the retention script leaves enough WAL to still recover. What it does **not** prove: behaviour
at production data volumes/WAL rates, recovery over object storage, multi-timeline histories beyond one promotion, or the dev
stack (`hbl-postgres-1`) - that database does **not** have archiving enabled (see "Enabling it" below).

| File | Purpose |
| --- | --- |
| `postgresql.pitr.conf` | `wal_level=replica`, `archive_mode=on`, safe `archive_command`, `archive_timeout=60` |
| `docker-compose.pitr.example.yml` | postgres + `wal-archive` and `base-backups` volumes (+ one-shot chown init) |
| `pitr-basebackup.sh` | `pg_basebackup -Ft -z -X none`, backup_label copy, metadata, atomic publish |
| `pitr-restore.sh` | new container + new volume, `restore_command`, `recovery_target_time`, `recovery_target_action=promote`, `recovery.signal` |
| `pitr-prune.sh` | keep N base backups, `pg_archivecleanup` older WAL |
| `../../scripts/pitr-drill.sh` | the automated end-to-end drill |

## How it works

* **Archive**: `archive_command = 'test ! -f /wal-archive/%f && cp %p /wal-archive/.%f.tmp && mv /wal-archive/.%f.tmp /wal-archive/%f'`.
  It refuses to overwrite an existing segment (a second cluster pointed at the same archive makes archiving fail loudly instead of
  corrupting history - this was observed during development: `failed_count` rose when an old archive volume was reused), and renames
  into place so readers never see a partial file. `archive_timeout=60` forces a segment switch at least every minute, which bounds the
  loss window (RPO) to roughly one minute **of the archive**, plus whatever is only in the not-yet-archived tail. Each forced
  segment is a full 16 MB file (they compress well; see pruning).
* **Base backup**: `pitr-basebackup.sh` runs `pg_basebackup -Ft -z -X none -c fast` as the `postgres` user inside the running container
  into `/base-backups/<UTC ts>/`. `-X none` means the WAL is not embedded: recovery needs the archive. `pg_basebackup` blocks until the
  WAL needed for consistency is archived (the log line `all required WAL segments have been archived`), so a successful exit means restorable.
  **backup_label handling**: it lives inside `base.tar.gz` and must stay there until extraction (recovery needs it; never delete it from
  a restored data directory). The script also saves a readable `backup_label.txt` and writes `meta.env` with `START_WAL`/`START_LSN`;
  the prune script uses `START_WAL`. The directory is built as `<ts>.partial` and renamed, so a half-written backup is never picked up.
* **Recovery**: `pitr-restore.sh --base <ts> --target-time 'YYYY-MM-DD HH:MM:SS.ffffff+00'` creates a fresh volume, extracts the base
  backup as the postgres user, appends to `postgresql.auto.conf`:
  `restore_command='cp /wal-archive/%f %p'`, `recovery_target_time=...`, `recovery_target_inclusive=on`,
  `recovery_target_action='promote'`, `archive_mode=off` (the restored cluster must not write into the original archive), creates
  `recovery.signal`, and starts a new container with the archive mounted **read-only**. It waits for promotion and prints the container name.
  Without `--target-time` or an explicit `--latest` it refuses. The original cluster is never modified.
* **Pick the target time** just *before* the mistake: from application/audit logs, or by scanning
  `pg_waldump`/`log_statement`. The recovery log line `recovery stopping before commit of transaction N, time ...` shows where replay stopped.
  Time zones: always give an explicit offset (the drill uses UTC `+00`).
* **After recovery**: verify (row counts, application smoke test), then either dump the missing data back into production
  (`pg_dump -t` from the recovered container -> `psql` into prod) or switch the application to the recovered instance. The recovered
  cluster is on a new timeline (2); take a new base backup of whichever instance becomes production **before** relying on it.

## Run it

```bash
scripts/pitr-drill.sh                 # self-contained, ~20 s, needs only docker and the postgres:17.6 image
```

Operating it by hand (production-like single node, `docker compose -f infra/postgres-pitr/docker-compose.pitr.example.yml up -d`):

```bash
export PITR_CONTAINER=<compose project>-postgres-pitr-1 PITR_ARCHIVE_VOLUME=<project>_wal-archive PITR_BACKUP_VOLUME=<project>_base-backups
infra/postgres-pitr/pitr-basebackup.sh                      # e.g. daily from cron
PITR_KEEP_BASE=7 infra/postgres-pitr/pitr-prune.sh          # after each successful base backup
PITR_RESTORE_NAME=restore-check infra/postgres-pitr/pitr-restore.sh --base 20261001T030000Z --target-time '2026-10-01 14:05:00+00'
docker exec restore-check psql -U studio -d system_web_studio -c '\dt'   # inspect, then: docker rm -f -v restore-check; docker volume rm restore-check-data
```

### Measured drill output (2026-10-01, last run; abridged)

```
base backup #1: size=4208KiB elapsed=2s start_wal=000000010000000000000003
phase A: 1000 orders(A) + 200 ledger rows | base backup | phase B: +500 (B) | T = 2026-10-01 10:46:59.644593+00
phase C (after T): +300 (C), DELETE all A rows, DROP TABLE ledger   -> orders=800, ledger gone   (WAL archived: 0 s after pg_switch_wal, failed_count=0)
recover NEW container to T: recovery finished in 2s; "recovery stopping before commit of transaction 745, time 10:47:01.86"; "selected new timeline ID: 2"
  PASS orders=1500 | A=1000 | B=500 | C=0 | ledger back with 200 rows | promoted & read-write | new timeline | source untouched (800)
retention: base backup #2 (elapsed 2s), pitr-prune.sh PITR_KEEP_BASE=1:
  pruned base backup #1; pg_archivecleanup removed segments before 000000010000000000000005; WAL segments 7 -> 2
  PASS old base pruned | newest kept | WAL needed by base #2 present | nothing older than base #2 start remains
recover from the PRUNED archive, base #2 to end of WAL: PASS orders=800, ledger gone (the accident is replayed, as it must be)
refusal: pitr-restore.sh without --target-time/--latest -> non-zero
PITR DRILL PASSED   total drill time: 20s
```

Timings are for a ~4 MB database; recovery time grows with base-backup size plus the volume of WAL to replay.

## Retention

* Keep base backups for as long as you want to be able to recover back to: `PITR_KEEP_BASE` newest backups are kept
  (default 3), older ones are deleted.
* WAL is deleted only up to the `START_WAL` of the **oldest retained** base backup (`pg_archivecleanup`), so every retained base
  backup can still be rolled forward to any later time. Consequence: you cannot recover to a time before the oldest retained backup.
* Prune only after a *successful* new base backup (the order in the drill). Never delete archive files by age alone.
* Size planning: with `archive_timeout=60` an idle database still produces up to 1440 x 16 MB = 23 GB of raw (very compressible) WAL per day;
  raise `archive_timeout`, or compress in the archive command (`gzip` + matching `gunzip` in `restore_command`) if that matters.
* Verify restores regularly: the drill is the verification; run it (or a restore of the real latest base backup into a scratch
  container, see above) on a schedule and alert on failure. `pg_stat_archiver.failed_count` / `last_failed_time` should be monitored: a
  failing `archive_command` makes `pg_wal` grow until the disk is full.

## Enabling it for the dev/production database

Not enabled in `compose.yml` (the dev stack is untouched). To use it: switch the service to
`docker-compose.pitr.example.yml` (or add `command: ["postgres","-c","config_file=/etc/postgresql/pitr.conf"]`, the two extra
volumes and the chown init to your compose), restart once (`archive_mode` needs a restart), take a base backup, and copy the `wal-archive`
and `base-backups` volumes **off the host** (a PITR archive on the same disk as the database does not survive a disk loss).

## Production notes

* This setup is a reproducible, understandable baseline. For production prefer a purpose-built tool that ships WAL **to object storage**
  with compression, encryption, retention policies, parallelism and verification: **pgBackRest** (S3/GCS/Azure/SFTP, incremental/differential
  backups, `verify`) or **wal-g** (`archive_command = 'wal-g wal-push %p'`, `restore_command = 'wal-g wal-fetch %f %p'`, `wal-g backup-push`,
  `delete retain`). Both slot into the same `recovery_target_time` procedure. Documented only; neither was installed or tested here.
* Managed PostgreSQL (RDS/Cloud SQL/Neon) gives PITR as a product; use it if you are on one and skip this.
* Encrypt the archive and base backups at rest and in transit (they contain all data); restrict who can read the archive volume.
* `archive_command` must stay idempotent and must never overwrite; do not point two clusters at the same archive directory.
* For high availability use streaming replication (a standby) *in addition*: PITR protects against logical mistakes, a standby against host loss.
* Keep `pg_hba.conf` replication access limited; `pitr-basebackup.sh` uses the local socket of the container.
