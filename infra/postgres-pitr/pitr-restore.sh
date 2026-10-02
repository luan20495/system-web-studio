#!/usr/bin/env bash
# Point-in-time recovery of a base backup into a NEW throwaway container + NEW volume.
# The source cluster, its volumes and the live archive are only READ (archive is mounted read-only).
#
# Usage: pitr-restore.sh --base <backup dir name, e.g. 20261001T103000Z> [--target-time 'YYYY-MM-DD HH:MM:SS+00' | --latest]
#   PITR_ARCHIVE_VOLUME  volume or host path holding the WAL archive      (default: hbl-pitr-wal-archive)
#   PITR_BACKUP_VOLUME   volume or host path holding the base backups     (default: hbl-pitr-base-backups)
#   PITR_RESTORE_NAME    name of the new container/volume                 (default: pitr-restore-<epoch>)
#   PITR_IMAGE           postgres image, must match the major version     (default: postgres:17.6)
#   PITR_RESTORE_PORT    optional host port (127.0.0.1) to publish
# Without --target-time/--latest the script refuses (an unbounded recovery replays everything, including the mistake).
# On success the new container is left running (promoted, read-write) and its name is printed on the last line.
set -euo pipefail
ARCH="${PITR_ARCHIVE_VOLUME:-hbl-pitr-wal-archive}"; BAKV="${PITR_BACKUP_VOLUME:-hbl-pitr-base-backups}"
NAME="${PITR_RESTORE_NAME:-pitr-restore-$(date +%s)}"; IMG="${PITR_IMAGE:-postgres:17.6}"
BASE=""; TARGET=""; LATEST=0
while [ $# -gt 0 ]; do
  case "$1" in
    --base) shift; BASE="${1:-}" ;;
    --target-time) shift; TARGET="${1:-}" ;;
    --latest) LATEST=1 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac; shift
done
[ -n "$BASE" ] || { echo "--base is required" >&2; exit 2; }
case "$BASE" in *[!A-Za-z0-9_.-]*) echo "invalid base name" >&2; exit 2 ;; esac
[ -n "$TARGET" ] || [ "$LATEST" = 1 ] || { echo "give --target-time or --latest" >&2; exit 2; }
case "$TARGET" in *\'*|*\;*) echo "invalid target time" >&2; exit 2 ;; esac
docker container inspect "$NAME" >/dev/null 2>&1 && { echo "container $NAME already exists" >&2; exit 1; }
docker volume inspect "$NAME-data" >/dev/null 2>&1 && { echo "volume $NAME-data already exists" >&2; exit 1; }

NL=$'\n'
RCV="restore_command = 'cp /wal-archive/%f %p'${NL}archive_mode = off${NL}recovery_target_action = 'promote'"
if [ -n "$TARGET" ]; then RCV="${RCV}${NL}recovery_target_time = '$TARGET'${NL}recovery_target_inclusive = on"; fi
START=$(date +%s)
docker volume create "$NAME-data" >/dev/null
# 1. prepare PGDATA as the postgres user: extract base.tar.gz (contains backup_label), signal file, recovery settings
docker run --rm --user postgres -e "RCV=$RCV" -e "BASE=$BASE" -v "$NAME-data:/var/lib/postgresql/data" -v "$BAKV:/base-backups:ro" -v "$ARCH:/wal-archive:ro" "$IMG" sh -ec '
  b=/base-backups/$BASE
  test -f "$b/base.tar.gz" || { echo "base backup not found: $b" >&2; exit 1; }
  tar -xzf "$b/base.tar.gz" -C /var/lib/postgresql/data
  test -f /var/lib/postgresql/data/backup_label || { echo "backup_label missing after extract" >&2; exit 1; }
  chmod 700 /var/lib/postgresql/data
  printf "%s\n" "$RCV" >> /var/lib/postgresql/data/postgresql.auto.conf
  touch /var/lib/postgresql/data/recovery.signal
'
# 2. start a fresh postgres on that data directory; it replays WAL from the read-only archive, then promotes
PORT_ARG=""; [ -z "${PITR_RESTORE_PORT:-}" ] || PORT_ARG="-p 127.0.0.1:${PITR_RESTORE_PORT}:5432"
# shellcheck disable=SC2086
docker run -d --name "$NAME" $PORT_ARG -v "$NAME-data:/var/lib/postgresql/data" -v "$ARCH:/wal-archive:ro" "$IMG" >/dev/null
for _ in $(seq 1 300); do
  state="$(docker exec "$NAME" psql -U "${PITR_USER:-studio}" -d postgres -X -qAt -c 'select pg_is_in_recovery()' 2>/dev/null || true)"
  [ "$state" = f ] && break
  [ "$(docker inspect -f '{{.State.Running}}' "$NAME")" = true ] || { echo "ERROR: restore container exited; logs:" >&2; docker logs "$NAME" 2>&1 | tail -30 >&2; exit 1; }
  sleep 1
done
[ "$state" = f ] || { echo "ERROR: recovery did not finish in 300s" >&2; docker logs "$NAME" 2>&1 | tail -30 >&2; exit 1; }
echo "recovery finished in $(( $(date +%s) - START ))s; promoted" >&2
docker logs "$NAME" 2>&1 | grep -E 'recovery stopping|redo done|selected new timeline|archive recovery complete|database system is ready' | sed 's/^/  pg: /' >&2 || true
echo "$NAME"
