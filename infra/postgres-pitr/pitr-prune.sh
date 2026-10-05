#!/usr/bin/env bash
# Retention for base backups and archived WAL.
#   1. keeps the newest PITR_KEEP_BASE base backups (default 3) and deletes older ones,
#   2. deletes archived WAL older than the START WAL of the OLDEST RETAINED base backup (pg_archivecleanup),
#      so every retained base backup can still be recovered to any later point in time.
# Recovery to a time before the oldest retained base backup is no longer possible after pruning (by design).
#   PITR_ARCHIVE_VOLUME / PITR_BACKUP_VOLUME  as in pitr-restore.sh;  PITR_IMAGE  postgres image (has pg_archivecleanup)
# Safety: it refuses to run with fewer than 1 retained backup and never touches WAL if no meta.env is found.
set -euo pipefail
ARCH="${PITR_ARCHIVE_VOLUME:-hbl-pitr-wal-archive}"; BAKV="${PITR_BACKUP_VOLUME:-hbl-pitr-base-backups}"
IMG="${PITR_IMAGE:-postgres:17.6}"; KEEP="${PITR_KEEP_BASE:-3}"
case "$KEEP" in ''|*[!0-9]*|0) echo "PITR_KEEP_BASE must be an integer >= 1" >&2; exit 2 ;; esac
docker run --rm --user postgres -v "$BAKV:/base-backups" -v "$ARCH:/wal-archive" -e "KEEP=$KEEP" "$IMG" sh -ec '
  cd /base-backups
  list=$(ls -1d [0-9]*T[0-9]*Z 2>/dev/null | sort -r)
  n=0; oldest=""
  for d in $list; do
    n=$((n+1))
    if [ "$n" -le "$KEEP" ]; then oldest="$d"; else rm -rf "/base-backups/$d"; echo "pruned base backup $d"; fi
  done
  [ -n "$oldest" ] || { echo "no base backups found; WAL left untouched" >&2; exit 0; }
  . "/base-backups/$oldest/meta.env"
  [ -n "$START_WAL" ] || { echo "no START_WAL for $oldest; WAL left untouched" >&2; exit 1; }
  echo "oldest retained base backup: $oldest (needs WAL from $START_WAL)"
  pg_archivecleanup -d /wal-archive "$START_WAL" 2>&1 | sed "s/^/  /"
  echo "WAL segments left in archive: $(ls -1 /wal-archive | grep -cE "^[0-9A-F]{24}$")"
'
