#!/usr/bin/env bash
# Logical PostgreSQL backup: timestamped pg_dump -Fc, verified, checksummed, atomically published, pruned.
#
# Configuration (environment; see scripts/_backup_common.sh for connection modes):
#   POSTGRES_CONTAINER | PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE
#   BACKUP_DIR              default ./backups (gitignored)
#   BACKUP_RETENTION_DAYS   delete dumps older than this many days (default 14)
#   BACKUP_KEEP_MIN         never prune below this many newest dumps (default 3)
# Output: $BACKUP_DIR/<db>-<UTC timestamp>.dump and .dump.sha256. Exit status is non-zero on any failure.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backup_common.sh"
bk_load_env

case "$BACKUP_RETENTION_DAYS" in ''|*[!0-9]*) bk_die "BACKUP_RETENTION_DAYS must be a non-negative integer" ;; esac
case "$BACKUP_KEEP_MIN" in ''|*[!0-9]*) bk_die "BACKUP_KEEP_MIN must be a non-negative integer" ;; esac
mkdir -p "$BACKUP_DIR"
umask 077   # dumps contain everything: owner-only
bk_lock "$BACKUP_DIR/.backup-postgres.lock"
TMP=""
cleanup() { if [ -n "$TMP" ]; then rm -f "${TMP:?}" "${TMP:?}.sha256"; fi; bk_unlock; }
trap cleanup EXIT

TS="$(date -u +%Y%m%dT%H%M%SZ)"
FINAL="$BACKUP_DIR/$PGDATABASE-$TS.dump"
TMP="$BACKUP_DIR/.tmp-$PGDATABASE-$TS.$$.dump"
[ ! -e "$FINAL" ] || bk_die "refusing to overwrite $FINAL"

START=$(date +%s)
bk_log "dumping $PGDATABASE (${POSTGRES_CONTAINER:-$PGHOST}) -> $FINAL"
bk_pg pg_dump -Fc -Z 6 --no-password "$PGDATABASE" > "$TMP" || bk_die "pg_dump failed"
[ -s "$TMP" ] || bk_die "dump is empty"

ENTRIES="$(bk_verify_dump "$TMP")" || bk_die "integrity check failed; backup discarded"
SUM="$(bk_sha256 "$TMP")"
printf '%s  %s\n' "$SUM" "$(basename "$FINAL")" > "$TMP.sha256"
# Publish: checksum first, dump last (a dump without a checksum is refused by restore anyway).
mv "$TMP.sha256" "$FINAL.sha256"
mv "$TMP" "$FINAL"; TMP=""
bk_verify_checksum "$FINAL" || bk_die "post-publish checksum verification failed"
SIZE="$(wc -c < "$FINAL" | tr -d ' ')"
bk_log "OK $(basename "$FINAL") size=${SIZE}B toc_entries=$ENTRIES sha256=$SUM elapsed=$(( $(date +%s) - START ))s"

# ---- retention (only after a successful backup; the newest BACKUP_KEEP_MIN dumps are always kept)
idx=0; pruned=0
for f in $(ls -1 "$BACKUP_DIR" | grep -E "^$PGDATABASE-[0-9]{8}T[0-9]{6}Z\.dump$" | sort -r); do
  idx=$((idx + 1))
  [ "$idx" -gt "$BACKUP_KEEP_MIN" ] || continue
  if [ -n "$(find "$BACKUP_DIR/$f" -maxdepth 0 -mtime +"$BACKUP_RETENTION_DAYS" 2>/dev/null)" ]; then
    rm -f "${BACKUP_DIR:?}/${f:?}" "${BACKUP_DIR:?}/${f:?}.sha256"; pruned=$((pruned + 1)); bk_log "pruned $f"
  fi
done
bk_log "retention: kept $((idx - pruned)) dump(s), pruned $pruned (days=$BACKUP_RETENTION_DAYS, min=$BACKUP_KEEP_MIN)"
echo "$FINAL"
