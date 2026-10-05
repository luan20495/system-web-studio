# Backup operations (day 2)

Design and test results: [BACKUP_DR.md](BACKUP_DR.md). All scripts live in `scripts/`; they read credentials from the environment or the
gitignored `.env` (explicit environment variables win). Nothing is stored in arguments or logs.

## PostgreSQL logical backup

```bash
scripts/backup-postgres.sh                     # defaults: container hbl-postgres-1, db system_web_studio, ./backups, 14 days, keep >= 3
POSTGRES_CONTAINER=my-pg BACKUP_DIR=/srv/backups/studio BACKUP_RETENTION_DAYS=30 BACKUP_KEEP_MIN=7 scripts/backup-postgres.sh
PGHOST=db.internal PGPORT=5432 PGUSER=studio PGPASSWORD=... PGDATABASE=system_web_studio scripts/backup-postgres.sh   # remote server
```
Output `backups/<db>-<UTC>.dump` + `.dump.sha256`; the last stdout line is the dump path. Non-zero exit = no usable backup was published. With `PGHOST` set and no
`pg_dump` on PATH the tools run in a throwaway `postgres:17.6` container (`PG_CLIENT_IMAGE` to change it). Keep the client major version >= the server's.

```bash
# restore into a NEW database (verify first, swap deliberately); newest dump is used when no file is given
scripts/restore-postgres.sh --target-db studio_restored backups/system_web_studio-20261001T030000Z.dump
docker exec hbl-postgres-1 psql -U studio -d studio_restored -c 'select count(*) from projects'
# replace an existing database (drops it, disconnects clients): only during an incident, API stopped
scripts/restore-postgres.sh --force --target-db system_web_studio
```
Verify the whole chain any time: `scripts/test-backup-restore.sh` (about 40 s, throwaway container on port 25432, override `DRILL_PG_PORT`).

## Point-in-time recovery

See `infra/postgres-pitr/README.md`. Short form: `infra/postgres-pitr/pitr-basebackup.sh` (daily), `PITR_KEEP_BASE=7 infra/postgres-pitr/pitr-prune.sh`, and
`infra/postgres-pitr/pitr-restore.sh --base <ts> --target-time '2026-10-01 14:05:00+00'` into a fresh container. Rehearse with `scripts/pitr-drill.sh` (~20 s).

## MinIO / object storage

```bash
scripts/backup-minio.sh            # versioning on + mirror to ./backups/minio/<bucket>/ (+ MANIFEST.sha256)
scripts/restore-minio.sh           # full restore (manifest verified first); adds/overwrites, never deletes unless MINIO_RESTORE_REMOVE=true
scripts/restore-minio.sh --list-versions img/hero.png
scripts/restore-minio.sh --object img/hero.png            # recover the previous version (after overwrite) or the last real version (after delete)
scripts/restore-minio.sh --object img/hero.png --version-id <id>
```
Environment: `MINIO_ENDPOINT` (default `http://127.0.0.1:19000`), `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` (or `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY`; use a scoped key in
real S3), `MINIO_BUCKET`, `MINIO_BACKUP_DIR`. The dev defaults are in `.env`/`.env.example`.
Offsite target (another MinIO, AWS S3, Backblaze B2 S3 API, Wasabi, R2...):
```bash
MINIO_BACKUP_TARGET_URL=https://s3.eu-central-003.backblazeb2.com MINIO_BACKUP_TARGET_ACCESS_KEY=... MINIO_BACKUP_TARGET_SECRET_KEY=... \
MINIO_BACKUP_TARGET_BUCKET=studio-assets-offsite scripts/backup-minio.sh     # same variables on restore read from that target
```
`mc` runs from the image compose.yml already uses (`MC_IMAGE`); `minio/mc` is not pullable. Only tested against MinIO; AWS/B2 endpoints were not tested.
Rehearse: `scripts/test-minio-backup.sh` (~60 s, throwaway MinIO on ports 29000/29001, override `DRILL_MINIO_PORT`).
Remember: the PostgreSQL backup does **not** contain asset bytes. Versions accumulate; add a lifecycle rule (`mc ilm rule add --noncurrent-expire-days 30 ...`) when you decide the retention.

## Scheduling (examples, not installed)

cron (Linux/macOS), daily 03:15 with a log and a failure marker; add a weekly drill:
```cron
15 3 * * *  cd /opt/system-web-studio && scripts/backup-postgres.sh >>/var/log/studio-backup.log 2>&1 && scripts/backup-minio.sh >>/var/log/studio-backup.log 2>&1 || echo "backup failed $(date -u)" >>/var/log/studio-backup.FAILED
30 3 * * 0  cd /opt/system-web-studio && scripts/test-backup-restore.sh >>/var/log/studio-drill.log 2>&1
```
systemd (`/etc/systemd/system/studio-backup.service` and `.timer`, then `systemctl enable --now studio-backup.timer`):
```ini
[Service]
Type=oneshot
WorkingDirectory=/opt/system-web-studio
EnvironmentFile=/etc/studio/backup.env          # BACKUP_DIR=..., POSTGRES_CONTAINER=..., credentials; mode 600
ExecStart=/opt/system-web-studio/scripts/backup-postgres.sh
ExecStart=/opt/system-web-studio/scripts/backup-minio.sh
[Timer]
OnCalendar=*-*-* 03:15:00
Persistent=true
RandomizedDelaySec=300
[Install]
WantedBy=timers.target
```
launchd (macOS, `~/Library/LaunchAgents/com.systemwebstudio.backup.plist`, `launchctl load` it; the process needs Docker running and a PATH containing docker):
```xml
<plist version="1.0"><dict>
  <key>Label</key><string>com.systemwebstudio.backup</string>
  <key>ProgramArguments</key><array><string>/bin/bash</string><string>-c</string>
    <string>cd /Users/me/code/HBL &amp;&amp; scripts/backup-postgres.sh &amp;&amp; scripts/backup-minio.sh</string></array>
  <key>EnvironmentVariables</key><dict><key>PATH</key><string>/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin</string></dict>
  <key>StartCalendarInterval</key><dict><key>Hour</key><integer>3</integer><key>Minute</key><integer>15</integer></dict>
  <key>StandardOutPath</key><string>/tmp/studio-backup.log</string><key>StandardErrorPath</key><string>/tmp/studio-backup.log</string>
</dict></plist>
```
After each run copy `backups/` off the host (rclone/restic/rsync/object storage) and alert when the newest dump is older than ~26 h.

## Quick incident checklist
1. Stop writes (stop the API), note the time of the mistake. 2. Logical mistake in the last hours and PITR enabled: PITR into a scratch container, extract the
data, repair production. 3. Otherwise restore the newest verified dump into a **new** DB and compare before swapping. 4. `restore-minio.sh` for assets (or `--object`).
5. Start the API (Flyway validates), run `scripts/smoke-test.sh`. 6. Take a fresh backup immediately.
