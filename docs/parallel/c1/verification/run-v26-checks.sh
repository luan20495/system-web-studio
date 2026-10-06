#!/usr/bin/env bash
# Verifies V26 (and its undo script) on a REAL PostgreSQL, without Gradle/Docker/Flyway. Needs: psql + a reachable server (PGHOST/PGPORT/PGUSER/PGPASSWORD).
# Usage (repo root):  PGHOST=localhost PGUSER=postgres docs/parallel/c1/verification/run-v26-checks.sh
# Applies V1..V25, seeds rows (seed25.sql), applies V26 in ONE transaction (like Flyway), runs checks.sql, then exercises the undo script.
# Exit code != 0 and a "BAD"/"FAILED" line on any failure. Creates and drops scratch databases c1_v26_*.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
MIG="${MIG:-$ROOT/backend/src/main/resources/db/migration}"
UNDO="${UNDO:-$ROOT/docs/parallel/c1/undo/U26__tenant_foundation.sql}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PSQL="psql -X -q -v ON_ERROR_STOP=1"
SUF="$$"; BASE="c1_v26_base_$SUF"; WORK="c1_v26_work_$SUF"; UND="c1_v26_undo_$SUF"
cleanup() { for d in "$WORK" "$UND" "$BASE"; do $PSQL -d postgres -c "DROP DATABASE IF EXISTS $d" >/dev/null 2>&1 || true; done; }
trap cleanup EXIT
fail() { echo "FAILED: $*"; exit 1; }
# psql exits non-zero when the guard raises; pipefail would hide the grep result, so capture the output first
refused() { local out; out="$($PSQL -d "$1" -f "$UNDO" 2>&1 || true)"; echo "$out" | grep -q "U26 refused"; }

$PSQL -d postgres -c "CREATE DATABASE $BASE"
for v in $(seq 1 25); do f=$(ls "$MIG"/V${v}__*.sql); $PSQL -d "$BASE" -f "$f" >/dev/null || fail "applying $f"; done
echo "V1..V25 applied"

mk() { $PSQL -d postgres -c "CREATE DATABASE $1 TEMPLATE $BASE"; $PSQL -d "$1" -f "$HERE/seed25.sql"; $PSQL -d "$1" -1 -f "$MIG/V26__tenant_foundation.sql"; }
mk "$WORK"; echo "V26 applied on V25 data (single transaction)"
OUT="$($PSQL -d "$WORK" -f "$HERE/checks.sql")"; echo "$OUT"
echo "$OUT" | grep -q '^BAD' && fail "checks.sql reported BAD"
[ "$(echo "$OUT" | grep -c '^ok')" -ge 20 ] || fail "too few checks ran"

echo "== undo: guard refusals (nothing may change) =="
refused "$WORK" || fail "undo ran although a non-DEFAULT tenant exists"
[ "$($PSQL -d "$WORK" -Atc "select count(*) from tenants")" -ge 2 ] || fail "guarded undo changed data"

mk "$UND"
$PSQL -d "$UND" -c "create table v27_probe(id uuid primary key, tenant_id uuid references tenants(id))"
refused "$UND" || fail "undo ran although another table references tenants"
$PSQL -d "$UND" -c "drop table v27_probe" -c "update tenant_members set role='TENANT_ADMIN' where user_id='11111111-0000-0000-0000-000000000001'"
refused "$UND" || fail "undo ran although a TENANT_ADMIN exists"
$PSQL -d "$UND" -c "update tenant_members set role='MEMBER'" -c "create table flyway_schema_history(version varchar, success boolean)" -c "insert into flyway_schema_history values ('27', true)"
refused "$UND" || fail "undo ran although V27 is applied"
$PSQL -d "$UND" -c "drop table flyway_schema_history"
echo "== undo: clean case =="
B="$($PSQL -d "$UND" -Atc "select (select count(*) from workspaces)||','||(select count(*) from projects)||','||(select count(*) from users)")"
$PSQL -d "$UND" -f "$UNDO" >/dev/null
A="$($PSQL -d "$UND" -Atc "select (select count(*) from workspaces)||','||(select count(*) from projects)||','||(select count(*) from users)")"
[ "$A" = "$B" ] || fail "undo lost business rows ($B -> $A)"
[ "$($PSQL -d "$UND" -Atc "select count(*) from information_schema.tables where table_name in ('tenants','tenant_members')")" = "0" ] || fail "undo left tenant tables"
$PSQL -d "$UND" -1 -f "$MIG/V26__tenant_foundation.sql" || fail "V26 does not re-apply after undo"
echo "ALL V26 CHECKS PASSED"
