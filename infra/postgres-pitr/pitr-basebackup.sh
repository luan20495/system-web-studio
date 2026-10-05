#!/usr/bin/env bash
# Take a physical base backup (pg_basebackup, tar + gzip, no embedded WAL: WAL comes from the archive).
#
#   PITR_CONTAINER   running postgres container with archive_mode=on   (default: hbl-postgres-pitr-1)
#   PITR_USER        superuser/replication role                        (default: studio)
#   PITR_BASE_DIR    base-backup directory INSIDE the container        (default: /base-backups)
# Writes $PITR_BASE_DIR/<UTC timestamp>/{base.tar.gz,backup_manifest,backup_label.txt,meta.env}.
# backup_label handling: pg_basebackup stores backup_label inside base.tar.gz (recovery reads it from there
# after extraction). A readable copy is saved as backup_label.txt, and START WAL/LSN are recorded in meta.env;
# pitr-prune.sh uses START_WAL to decide which archived WAL is still needed.
# The script returns only after the WAL needed to make the backup consistent has been ARCHIVED (pg_basebackup waits).
set -euo pipefail
C="${PITR_CONTAINER:-hbl-postgres-pitr-1}"; U="${PITR_USER:-studio}"; BASE="${PITR_BASE_DIR:-/base-backups}"
dx() { docker exec -i -u postgres -e PGPASSWORD "$C" "$@"; }
sql() { dx psql -U "$U" -d postgres -X -qAt -c "$1"; }
TS="$(date -u +%Y%m%dT%H%M%SZ)"; DIR="$BASE/$TS"
[ "$(sql 'show archive_mode')" = on ] || { echo "ERROR: archive_mode is not on; this backup could not be recovered" >&2; exit 1; }
dx test ! -e "$DIR" || { echo "ERROR: $DIR exists" >&2; exit 1; }
dx mkdir -p "$DIR.partial"
START=$(date +%s)
dx pg_basebackup -U "$U" -D "$DIR.partial" -Ft -z -X none -c fast --label "pitr-$TS" --no-password
LABEL="$(dx tar -xzOf "$DIR.partial/base.tar.gz" backup_label)"
printf '%s\n' "$LABEL" | dx sh -c "cat > '$DIR.partial/backup_label.txt'"
START_WAL="$(printf '%s\n' "$LABEL" | sed -n 's/^START WAL LOCATION: .*(file \([0-9A-F]*\))$/\1/p')"
START_LSN="$(printf '%s\n' "$LABEL" | sed -n 's/^START WAL LOCATION: \([0-9A-F/]*\) .*/\1/p')"
[ -n "$START_WAL" ] || { echo "ERROR: could not parse START WAL from backup_label" >&2; exit 1; }
# pg_basebackup (without --no-wait) forces a WAL switch at the end of the backup and BLOCKS until every WAL segment
# needed to make the backup consistent has been archived ("all required WAL segments have been archived"), so a
# successful exit means the backup is restorable. (An extra pg_switch_wal here would only wait for archive_timeout.)
printf 'LABEL=pitr-%s\nSTART_WAL=%s\nSTART_LSN=%s\nTAKEN_AT=%s\nELAPSED_S=%s\n' "$TS" "$START_WAL" "$START_LSN" "$TS" "$(( $(date +%s) - START ))" \
  | dx sh -c "cat > '$DIR.partial/meta.env'"
dx mv "$DIR.partial" "$DIR"      # atomic publish
SIZE="$(dx du -sk "$DIR" | cut -f1)"
echo "base backup $DIR size=${SIZE}KiB start_wal=$START_WAL start_lsn=$START_LSN elapsed=$(( $(date +%s) - START ))s"
echo "$DIR"
