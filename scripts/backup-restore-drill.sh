#!/usr/bin/env bash
# Proves the PostgreSQL backup is restorable: dump -> restore into a scratch DB -> compare row counts -> drop it.
set -euo pipefail
C="${POSTGRES_CONTAINER:-hbl-postgres-1}"; DB="${POSTGRES_DB:-system_web_studio}"; U="${DATABASE_USER:-studio}"
psql_() { docker exec "$C" psql -U "$U" "$@"; }
docker exec "$C" pg_dump -U "$U" -Fc "$DB" -f /tmp/drill.dump
psql_ -d postgres -qc "DROP DATABASE IF EXISTS restore_drill" -c "CREATE DATABASE restore_drill"
docker exec "$C" pg_restore -U "$U" -d restore_drill --no-owner /tmp/drill.dump
ok=1
for t in users workspaces projects project_versions page_schemas prompts audit_events deployments deployment_events assets; do
  a=$(psql_ -d "$DB" -tAc "select count(*) from $t"); b=$(psql_ -d restore_drill -tAc "select count(*) from $t")
  echo "$t source=$a restored=$b"; [ "$a" = "$b" ] || ok=0
done
m=$(psql_ -d restore_drill -tAc "select count(*) from flyway_schema_history where success")
echo "migrations in restored DB: $m"
psql_ -d postgres -qc "DROP DATABASE restore_drill"; docker exec "$C" rm -f /tmp/drill.dump
[ "$ok" = 1 ] && echo "RESTORE DRILL PASSED" || { echo "RESTORE DRILL FAILED" >&2; exit 1; }
