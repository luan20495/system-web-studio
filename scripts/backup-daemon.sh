#!/usr/bin/env bash
# Runs backup-all daily at BACKUP_HOUR_UTC (default 19 = 02:00 Vietnam time) and the restore drill after each backup (BACKUP_DRILL=always)
# or on Sundays (default weekly). Started (detached) by run-local.sh / public-up.sh; one instance per environment (lock file).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; ENVN="${1:-local}"; HOUR="${BACKUP_HOUR_UTC:-19}"
LOCK="$ROOT/.run/backup-daemon-$ENVN.pid"
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK")" 2>/dev/null; then echo "backup daemon ($ENVN) already running"; exit 0; fi
echo $$ > "$LOCK"
last=""
while true; do
  today=$(date -u +%Y-%m-%d); h=$(date -u +%H)
  if [ "$last" != "$today" ] && [ "$((10#$h))" -ge "$HOUR" ]; then
    last="$today"
    "$ROOT/scripts/backup-all.sh" "$ENVN" >> "$ROOT/.run/backup-$ENVN.log" 2>&1
    if [ "${BACKUP_DRILL:-weekly}" = always ] || [ "$(date -u +%u)" = 7 ]; then "$ROOT/scripts/restore-drill-all.sh" "$ENVN" >> "$ROOT/.run/backup-$ENVN.log" 2>&1; fi
  fi
  sleep 300
done
