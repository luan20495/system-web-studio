#!/usr/bin/env bash
# Restore a dump made by backup-postgres.sh into a NAMED target database.
#
# Usage: scripts/restore-postgres.sh [--force] --target-db NAME [DUMP_FILE]
#   DUMP_FILE defaults to the newest dump in $BACKUP_DIR.
#   The checksum (<dump>.sha256) is verified first; a missing or wrong checksum aborts.
#   If the target DB exists and contains any user relations the script refuses, unless --force is given;
#   --force drops and recreates the target (DROP DATABASE ... WITH (FORCE) disconnects clients).
#   Restoring over the live database is never the default: pick a new name, verify, then swap deliberately.
# Connection/env: see scripts/_backup_common.sh. Exit status is non-zero on any failure.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backup_common.sh"
bk_load_env

FORCE=0; TARGET="${RESTORE_DB:-}"; DUMP=""
while [ $# -gt 0 ]; do
  case "$1" in
    --force) FORCE=1 ;;
    --target-db) shift; TARGET="${1:-}" ;;
    --target-db=*) TARGET="${1#*=}" ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    -*) bk_die "unknown option $1" ;;
    *) [ -z "$DUMP" ] || bk_die "only one dump file allowed"; DUMP="$1" ;;
  esac
  shift
done
[ -n "$TARGET" ] || bk_die "--target-db NAME (or RESTORE_DB) is required"
case "$TARGET" in *[!A-Za-z0-9_]*) bk_die "target db name may only contain [A-Za-z0-9_]" ;; esac
case "$TARGET" in postgres|template0|template1) bk_die "refusing to restore into system database $TARGET" ;; esac
if [ -z "$DUMP" ]; then
  newest="$(ls -1 "$BACKUP_DIR" 2>/dev/null | grep -E '\.dump$' | sort | tail -n1 || true)"
  [ -n "$newest" ] || bk_die "no dumps found in $BACKUP_DIR"
  DUMP="$BACKUP_DIR/$newest"
fi
[ -f "$DUMP" ] || bk_die "dump not found: $DUMP"

bk_lock "$(dirname "$DUMP")/.restore-postgres.lock"
trap bk_unlock EXIT
START=$(date +%s)
bk_verify_checksum "$DUMP" || bk_die "checksum verification failed; not restoring"
ENTRIES="$(bk_verify_dump "$DUMP")" || bk_die "dump failed pg_restore --list; not restoring"
bk_log "dump verified ($ENTRIES toc entries): $DUMP"

exists="$(bk_psql -d postgres -c "select 1 from pg_database where datname='$TARGET'")"
if [ "$exists" = 1 ]; then
  tables="$(bk_psql -d "$TARGET" -c "select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where c.relkind in ('r','p','v','m','S') and n.nspname not in ('pg_catalog','information_schema') and n.nspname not like 'pg_toast%'")"
  if [ "$tables" != 0 ] && [ "$FORCE" != 1 ]; then
    bk_die "target database '$TARGET' is not empty ($tables relations). Re-run with --force to drop and recreate it."
  fi
  if [ "$FORCE" = 1 ]; then
    bk_log "--force: dropping existing database $TARGET"
    bk_psql -d postgres -c "DROP DATABASE \"$TARGET\" WITH (FORCE)"
    exists=""
  fi
fi
[ "$exists" = 1 ] || bk_psql -d postgres -c "CREATE DATABASE \"$TARGET\""

if ! bk_pg pg_restore --no-owner --exit-on-error -d "$TARGET" < "$DUMP"; then
  bk_die "pg_restore failed; database '$TARGET' may be partially restored"
fi
bk_log "OK restored $(basename "$DUMP") into $TARGET in $(( $(date +%s) - START ))s"
