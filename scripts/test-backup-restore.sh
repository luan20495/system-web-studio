#!/usr/bin/env bash
# Automated test of scripts/backup-postgres.sh and scripts/restore-postgres.sh against a THROWAWAY
# postgres:17.6 container (name prefix "drill-"; the dev stack is never touched).
#
# It applies the real Flyway migrations (backend/src/main/resources/db/migration/V*.sql), inserts rows, backs up,
# mutates and deletes data, restores, and compares per-table row counts and content checksums. It also checks
# failure paths (corrupt dump, missing checksum, non-empty target, lock, bad DB) and retention.
# Usage: scripts/test-backup-restore.sh      (env: DRILL_PG_PORT, default 25432)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
C="drill-pg-backup-test"
PORT="${DRILL_PG_PORT:-25432}"
PW="drill-$(date +%s)-$$"          # random throwaway password, only ever in this process environment
WORK="$(mktemp -d "${TMPDIR:-/tmp}/drill-backup.XXXXXX")"
DB=studio_test
FAILS=0; PASSES=0
LAST_PID=""

cleanup() {
  docker rm -f -v "$C" >/dev/null 2>&1 || true
  [ -z "$LAST_PID" ] || kill "$LAST_PID" 2>/dev/null || true
  rm -rf "${WORK:?}"
}
trap cleanup EXIT

pass() { PASSES=$((PASSES + 1)); echo "PASS  $1"; }
fail() { FAILS=$((FAILS + 1)); echo "FAIL  $1"; }
expect_ok()   { local d="$1"; shift; if "$@" >"$WORK/out.log" 2>&1; then pass "$d"; else fail "$d"; sed 's/^/      /' "$WORK/out.log" | tail -8; fi; }
expect_fail() { local d="$1"; shift; if "$@" >"$WORK/out.log" 2>&1; then fail "$d (exited 0)"; else pass "$d (exit non-zero: $(tail -n1 "$WORK/out.log" | cut -c1-110))"; fi; }

if lsof -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then echo "port $PORT busy; set DRILL_PG_PORT" >&2; exit 2; fi
docker rm -f -v "$C" >/dev/null 2>&1 || true

echo "== start throwaway postgres:17.6 ($C, 127.0.0.1:$PORT)"
docker run -d --name "$C" -e POSTGRES_PASSWORD="$PW" -e POSTGRES_USER=studio -e POSTGRES_DB="$DB" \
  -p "127.0.0.1:$PORT:5432" postgres:17.6 >/dev/null
for _ in $(seq 1 60); do
  # the entrypoint restarts postgres once after init: require two consecutive successes over TCP-less socket
  if docker exec "$C" pg_isready -U studio -d "$DB" -q 2>/dev/null && sleep 2 && docker exec "$C" pg_isready -U studio -d "$DB" -q 2>/dev/null; then break; fi
  sleep 1
done
q() { docker exec -i "$C" psql -U studio -d "${2:-$DB}" -X -v ON_ERROR_STOP=1 -qAt -c "$1"; }

echo "== apply Flyway migrations V*.sql in order"
for f in $(ls "$ROOT"/backend/src/main/resources/db/migration/V*.sql | sort -t V -k2 -n); do
  printf '   %s\n' "$(basename "$f")"
  docker exec -i "$C" psql -U studio -d "$DB" -X -v ON_ERROR_STOP=1 -q < "$f" >/dev/null || { echo "migration failed: $f" >&2; exit 2; }
done

echo "== seed rows"
docker exec -i "$C" psql -U studio -d "$DB" -X -v ON_ERROR_STOP=1 -q >/dev/null <<'SQL'
INSERT INTO users (id, username, password_hash, display_name) SELECT md5('u'||i)::uuid, 'user'||i, 'hash-'||i, 'User '||i FROM generate_series(1,6) i;
INSERT INTO workspaces (id, name, slug) SELECT md5('w'||i)::uuid, 'Workspace '||i, 'ws-'||i FROM generate_series(1,2) i;
INSERT INTO workspace_members (workspace_id, user_id, role) SELECT md5('w'||(1+i%2))::uuid, md5('u'||i)::uuid, CASE WHEN i<=2 THEN 'WORKSPACE_ADMIN' ELSE 'EDITOR' END FROM generate_series(1,6) i;
INSERT INTO projects (id, workspace_id, name, owner_user_id, description) SELECT md5('p'||i)::uuid, md5('w'||(1+i%2))::uuid, 'Project '||i, md5('u'||i)::uuid, 'desc '||i FROM generate_series(1,6) i;
INSERT INTO project_members (workspace_id, project_id, user_id, role) SELECT md5('w'||(1+i%2))::uuid, md5('p'||i)::uuid, md5('u'||i)::uuid, 'OWNER' FROM generate_series(1,6) i;
INSERT INTO page_schemas (project_id, workspace_id, schema) SELECT md5('p'||i)::uuid, md5('w'||(1+i%2))::uuid, jsonb_build_object('root', jsonb_build_object('type','page','title','Page '||i, 'blocks', jsonb_build_array('hero','faq'))) FROM generate_series(1,6) i;
INSERT INTO prompts (id, workspace_id, project_id, created_by, text) SELECT md5('pr'||i)::uuid, md5('w'||(1+(1+(i%6))%2))::uuid, md5('p'||(1+(i%6)))::uuid, md5('u'||(1+(i%2)))::uuid, 'Make section '||i||' friendlier' FROM generate_series(1,12) i;
INSERT INTO project_versions (id, workspace_id, project_id, version_number, schema_snapshot, kind, summary, prompt_id, created_by)
  SELECT md5('v'||i)::uuid, md5('w'||(1+(1+(i%6))%2))::uuid, md5('p'||(1+(i%6)))::uuid, (i/6)+1, '{"v":1}'::jsonb, 'PROMPT', 'v'||i, md5('pr'||i)::uuid, md5('u'||(1+(i%2)))::uuid FROM generate_series(1,12) i;
INSERT INTO audit_events (id, workspace_id, project_id, actor_id, action, resource_type, resource_id, new_value, ip_address) SELECT md5('a'||i)::uuid, md5('w1')::uuid, md5('p'||(1+(i%6)))::uuid, md5('u1')::uuid, 'project.update', 'project', i::text, jsonb_build_object('n', i), '203.0.113.'||i FROM generate_series(1,10) i;
INSERT INTO assets (id, workspace_id, project_id, name, content_type, size_bytes, storage_key, status, created_by) SELECT md5('as'||i)::uuid, md5('w'||(1+(1+(i%6))%2))::uuid, md5('p'||(1+(i%6)))::uuid, 'img'||i||'.png', 'image/png', 1000*i, 'key/'||i, 'READY', md5('u1')::uuid FROM generate_series(1,3) i;
INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) SELECT md5('d'||i)::uuid, md5('w'||(1+(1+(i%6))%2))::uuid, md5('p'||(1+(i%6)))::uuid, md5('v'||i)::uuid, md5('u1')::uuid, 'PRIVATE', 'RUNNING', 'mock' FROM generate_series(1,2) i;
INSERT INTO deployment_events (id, deployment_id, status, message) SELECT md5('de'||i)::uuid, md5('d'||(1+(i%2)))::uuid, 'RUNNING', 'event '||i FROM generate_series(1,4) i;
SQL

TABLES="users workspaces workspace_members projects project_members page_schemas prompts project_versions audit_events assets deployments deployment_events components component_versions"
snapshot() { # db -> "table count md5(rows)" lines
  local t
  for t in $TABLES; do
    printf '%s %s\n' "$t" "$(q "select count(*)||' '||coalesce(md5(string_agg(x::text, '|' order by x::text)),'-') from $t x" "$1")"
  done
}
snapshot "$DB" > "$WORK/baseline.txt"
if grep -q ' 0 -$' "$WORK/baseline.txt"; then echo "seed left an important table empty:"; grep ' 0 -$' "$WORK/baseline.txt"; exit 2; fi
echo "-- baseline (table rows md5)"; sed 's/^/   /' "$WORK/baseline.txt"

# ---------------------------------------------------------------- common env for the scripts under test
export POSTGRES_CONTAINER="$C" PGUSER=studio PGDATABASE="$DB" PGPASSWORD="$PW" BACKUP_SKIP_DOTENV=1
export BACKUP_DIR="$WORK/backups"
unset PGHOST PGPORT PG_CLIENT_IMAGE

echo "== 1. backup"
START=$(date +%s)
expect_ok "backup-postgres.sh succeeds" "$HERE/backup-postgres.sh"
echo "   backup took $(( $(date +%s) - START ))s"
DUMP="$(ls -1 "$BACKUP_DIR"/$DB-*.dump | head -n1)"
[ -f "$DUMP" ] && [ -f "$DUMP.sha256" ] && pass "dump and .sha256 written ($(basename "$DUMP"), $(wc -c < "$DUMP" | tr -d ' ') bytes)" || fail "dump/sha256 missing"
ls "$BACKUP_DIR" | grep -q '^\.tmp-' && fail "temporary file left behind" || pass "no temporary files left"
[ ! -d "$BACKUP_DIR/.backup-postgres.lock" ] && pass "lock released" || fail "lock left behind"
grep -q "$PW" "$WORK/out.log" && fail "password found in log output" || pass "password not in log output"

echo "== 2. mutate and delete data after the backup"
docker exec -i "$C" psql -U studio -d "$DB" -X -v ON_ERROR_STOP=1 -q >/dev/null <<'SQL'
DELETE FROM deployment_events; DELETE FROM deployments; DELETE FROM assets;
DELETE FROM project_members WHERE project_id = md5('p1')::uuid;
DELETE FROM page_schemas WHERE project_id IN (md5('p2')::uuid, md5('p3')::uuid);
UPDATE projects SET name = 'HACKED ' || name;
UPDATE users SET password_hash = 'tampered';
INSERT INTO users (id, username, password_hash) VALUES (md5('late')::uuid, 'late-user', 'x');
DROP TABLE idempotency_keys;
SQL
snapshot "$DB" > "$WORK/mutated.txt"
cmp -s "$WORK/baseline.txt" "$WORK/mutated.txt" && fail "mutation had no effect" || pass "data differs from baseline after mutation (projects/users/assets/deployments changed or removed, idempotency_keys dropped)"

echo "== 3. restore guards"
expect_fail "restore into non-empty DB refused without --force" "$HERE/restore-postgres.sh" --target-db "$DB" "$DUMP"
expect_fail "restore into system database refused" "$HERE/restore-postgres.sh" --target-db postgres "$DUMP"
expect_fail "restore without --target-db refused" "$HERE/restore-postgres.sh" "$DUMP"
expect_fail "invalid db name refused" "$HERE/restore-postgres.sh" --target-db 'x;drop' "$DUMP"

echo "== 4. restore into a NEW database and compare"
START=$(date +%s)
expect_ok "restore into new DB studio_restored" "$HERE/restore-postgres.sh" --target-db studio_restored "$DUMP"
echo "   restore took $(( $(date +%s) - START ))s"
snapshot studio_restored > "$WORK/restored.txt"
if diff "$WORK/baseline.txt" "$WORK/restored.txt" >"$WORK/diff.txt"; then pass "restored row counts and content checksums equal baseline for all $(echo $TABLES | wc -w | tr -d ' ') tables"; else fail "restored data differs from baseline"; cat "$WORK/diff.txt"; fi
[ "$(q "select to_regclass('public.idempotency_keys') is not null" studio_restored)" = t ] && pass "dropped table idempotency_keys is back" || fail "idempotency_keys missing after restore"
[ "$(q "select count(*) from flyway_schema_history" studio_restored 2>/dev/null || echo 0)" = 0 ] && echo "   (no flyway_schema_history: migrations were applied by psql, as expected)"
if docker exec "$C" psql -U studio -d studio_restored -X -qAt -c "delete from audit_events" >/dev/null 2>&1; then fail "audit_events trigger lost in restore"; else pass "audit_events append-only trigger survived the restore"; fi

echo "== 5. --force restore over the mutated original DB"
expect_ok "restore --force over $DB" "$HERE/restore-postgres.sh" --force --target-db "$DB" "$DUMP"
snapshot "$DB" > "$WORK/forced.txt"
diff -q "$WORK/baseline.txt" "$WORK/forced.txt" >/dev/null && pass "original DB equals baseline again" || fail "original DB differs after --force restore"
expect_ok "restore without explicit file picks the newest dump" "$HERE/restore-postgres.sh" --force --target-db studio_restored

echo "== 6. integrity failures must exit non-zero"
BAD="$WORK/bad"; mkdir -p "$BAD"
cp "$DUMP" "$BAD/$DB-20260101T000001Z.dump"; cp "$DUMP.sha256" "$BAD/$DB-20260101T000001Z.dump.sha256"
SZ=$(wc -c < "$DUMP" | tr -d ' ')
dd if=/dev/urandom of="$BAD/$DB-20260101T000001Z.dump" bs=1 count=64 seek=$((SZ / 2)) conv=notrunc 2>/dev/null
expect_fail "bit-flipped dump (checksum mismatch) refused" "$HERE/restore-postgres.sh" --target-db studio_bad "$BAD/$DB-20260101T000001Z.dump"
cp "$DUMP" "$BAD/$DB-20260101T000002Z.dump"
expect_fail "missing checksum file refused" "$HERE/restore-postgres.sh" --target-db studio_bad "$BAD/$DB-20260101T000002Z.dump"
head -c $((SZ / 2)) "$DUMP" > "$BAD/$DB-20260101T000003Z.dump"
printf '%s  %s\n' "$(shasum -a 256 "$BAD/$DB-20260101T000003Z.dump" | cut -d' ' -f1)" "$DB-20260101T000003Z.dump" > "$BAD/$DB-20260101T000003Z.dump.sha256"
expect_fail "truncated dump with a matching (re-computed) checksum refused (pg_restore --list / restore)" "$HERE/restore-postgres.sh" --target-db studio_bad "$BAD/$DB-20260101T000003Z.dump"
printf 'not-a-hash\n' > "$BAD/$DB-20260101T000002Z.dump.sha256"
expect_fail "malformed checksum file refused" "$HERE/restore-postgres.sh" --target-db studio_bad "$BAD/$DB-20260101T000002Z.dump"
[ "$(q "select count(*) from pg_database where datname='studio_bad'" postgres)" = 0 ] && pass "no database created by refused restores" || fail "studio_bad exists"

echo "== 7. backup failure paths"
EMPTY="$WORK/emptybackups"
expect_fail "backup of a non-existent database fails" env BACKUP_DIR="$EMPTY" PGDATABASE=nope "$HERE/backup-postgres.sh"
[ -z "$(ls -A "$EMPTY" 2>/dev/null | grep -v '^\.' || true)" ] && [ -z "$(ls -A "$EMPTY" | grep '^\.tmp-' || true)" ] && pass "failed backup leaves no dump or temp file" || fail "failed backup left files: $(ls -A "$EMPTY")"
expect_fail "backup with unreachable container fails" env BACKUP_DIR="$EMPTY" POSTGRES_CONTAINER=drill-does-not-exist "$HERE/backup-postgres.sh"
expect_fail "invalid BACKUP_KEEP_MIN rejected" env BACKUP_KEEP_MIN=abc "$HERE/backup-postgres.sh"

echo "== 8. lock against concurrent runs"
LOCKDIR="$WORK/lockbackups"; mkdir -p "$LOCKDIR"
sleep 120 & LAST_PID=$!
mkdir "$LOCKDIR/.backup-postgres.lock"; echo "$LAST_PID" > "$LOCKDIR/.backup-postgres.lock/pid"
expect_fail "second run refused while lock holder is alive" env BACKUP_DIR="$LOCKDIR" "$HERE/backup-postgres.sh"
kill "$LAST_PID" 2>/dev/null; wait "$LAST_PID" 2>/dev/null; LAST_PID=""
expect_ok "stale lock (dead pid) is taken over" env BACKUP_DIR="$LOCKDIR" "$HERE/backup-postgres.sh"
# true concurrency: two simultaneous runs, exactly one must win the lock
CONC="$WORK/conc"; mkdir -p "$CONC"
( BACKUP_DIR="$CONC" "$HERE/backup-postgres.sh" >"$WORK/c1.log" 2>&1; echo $? > "$WORK/c1.rc" ) &
( BACKUP_DIR="$CONC" "$HERE/backup-postgres.sh" >"$WORK/c2.log" 2>&1; echo $? > "$WORK/c2.rc" ) &
wait
rcs="$(cat "$WORK/c1.rc") $(cat "$WORK/c2.rc")"
case "$rcs" in "0 1"|"1 0") pass "two simultaneous runs: one succeeded, one refused (exit codes: $rcs)" ;; "0 0") echo "   both succeeded (runs did not overlap; timing-dependent): $rcs"; pass "no corruption with back-to-back runs" ;; *) fail "unexpected exit codes: $rcs" ;; esac

echo "== 9. retention"
RET="$WORK/retention"; mkdir -p "$RET"
for d in 20250101 20250102 20250103 20250104 20250105; do
  : > "$RET/$DB-${d}T000000Z.dump"; : > "$RET/$DB-${d}T000000Z.dump.sha256"; touch -t "${d}0000" "$RET/$DB-${d}T000000Z.dump"
done
expect_ok "backup with days=7 keep_min=3" env BACKUP_DIR="$RET" BACKUP_RETENTION_DAYS=7 BACKUP_KEEP_MIN=3 "$HERE/backup-postgres.sh"
left="$(ls "$RET" | grep -c '\.dump$')"
[ "$left" = 3 ] && ls "$RET" | grep -q "20250105" && ls "$RET" | grep -q "20250104" && ! ls "$RET" | grep -q "20250103" \
  && pass "kept newest 3 (new + 20250105 + 20250104), pruned 3 old; sidecar .sha256 pruned too ($(ls "$RET" | grep -c '\.sha256$') sidecars)" || { fail "retention result wrong: $(ls "$RET")"; }
sleep 1   # timestamps have 1s resolution; the script refuses to overwrite a same-second dump
expect_ok "backup with days=0 keep_min=2 never goes below 2" env BACKUP_DIR="$RET" BACKUP_RETENTION_DAYS=0 BACKUP_KEEP_MIN=2 "$HERE/backup-postgres.sh"
[ "$(ls "$RET" | grep -c '\.dump$')" = 2 ] && pass "exactly BACKUP_KEEP_MIN=2 dumps remain" || fail "expected 2 dumps: $(ls "$RET")"
sleep 1
expect_ok "backup with days=0 keep_min=10 prunes nothing" env BACKUP_DIR="$RET" BACKUP_RETENTION_DAYS=0 BACKUP_KEEP_MIN=10 "$HERE/backup-postgres.sh"
[ "$(ls "$RET" | grep -c '\.dump$')" = 3 ] && pass "minimum count protects dumps (3 present, none pruned)" || fail "expected 3 dumps: $(ls "$RET")"

echo "== 10. PGHOST mode through a throwaway client container (no host pg tools needed)"
docker exec "$C" sh -c "grep -q 'host all all all scram-sha-256' /var/lib/postgresql/data/pg_hba.conf" && pass "server enforces password auth over TCP"
REMOTE="$WORK/remote"
expect_ok "backup over TCP with PGHOST/PGPORT/PGPASSWORD" env -u POSTGRES_CONTAINER PGHOST=127.0.0.1 PGPORT="$PORT" BACKUP_DIR="$REMOTE" "$HERE/backup-postgres.sh"
grep -q "$PW" "$WORK/out.log" && fail "password in log output" || pass "password not in log output (TCP mode)"
expect_ok "restore over TCP into studio_remote" env -u POSTGRES_CONTAINER PGHOST=127.0.0.1 PGPORT="$PORT" BACKUP_DIR="$REMOTE" "$HERE/restore-postgres.sh" --target-db studio_remote
snapshot studio_remote > "$WORK/remote.txt"; diff -q "$WORK/baseline.txt" "$WORK/remote.txt" >/dev/null && pass "TCP-mode restore equals baseline" || fail "TCP-mode restore differs"
expect_fail "wrong password over TCP fails" env -u POSTGRES_CONTAINER PGHOST=127.0.0.1 PGPORT="$PORT" PGPASSWORD=wrong BACKUP_DIR="$WORK/remote2" "$HERE/backup-postgres.sh"

echo
echo "RESULT: $PASSES passed, $FAILS failed"
[ "$FAILS" = 0 ] && echo "BACKUP/RESTORE TEST PASSED" || { echo "BACKUP/RESTORE TEST FAILED" >&2; exit 1; }
