#!/usr/bin/env bash
# Restore drill (stage L): proves the LATEST backup files are restorable — not a fresh dump. Everything is restored into a throwaway
# postgres:17.6 container (never the live servers), checked, and removed. Result in <dir>/drill.json (read by Admin → Sao lưu).
# Usage: scripts/restore-drill-all.sh [local|public]
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$ROOT"
ENVN="${1:-local}"; OUT="${BACKUP_ROOT:-$ROOT/backups}/$ENVN"
C="restore-drill-$$"; RESULTS=(); OK=1
log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
add() { RESULTS+=("$1|$2|$3"); [ "$2" = PASS ] || [ "$2" = SKIPPED ] || OK=0; log "$1: $2 $3"; }
cleanup() { docker rm -f "$C" >/dev/null 2>&1 || true; }
trap cleanup EXIT
check_sum() { [ -f "$1.sha256" ] && [ "$(shasum -a 256 "$1" | cut -d' ' -f1)" = "$(cut -d' ' -f1 < "$1.sha256")" ]; }
PW="$(openssl rand -hex 16)"
POSTGRES_PASSWORD="$PW" docker run -d --name "$C" --network none -e POSTGRES_PASSWORD postgres:17.6 >/dev/null
for _ in $(seq 1 60); do docker exec "$C" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
q() { docker exec "$C" psql -U postgres -tAc "$2" -d "$1"; }
# app dumps are app-controlled content: restore them as an unprivileged role so functions inside cannot act as superuser
q postgres "create role drill_restore nosuperuser nocreatedb nocreaterole login" >/dev/null

f=$(ls -1t "$OUT"/postgres/*.dump 2>/dev/null | head -1)
if [ -z "$f" ]; then add postgres SKIPPED "no backup file"
elif ! check_sum "$f"; then add postgres FAIL "checksum mismatch: $(basename "$f")"
else
  q postgres "create database drill" >/dev/null
  if docker exec -i "$C" pg_restore -U postgres -d drill --no-owner --no-privileges < "$f" 2>/tmp/drill-pg.err; then :; fi
  v=$(q drill "select version from flyway_schema_history where success order by installed_rank desc limit 1" 2>/dev/null)
  u=$(q drill "select count(*) from users" 2>/dev/null); p=$(q drill "select count(*) from projects" 2>/dev/null)
  if [ -n "$v" ] && [ -n "$u" ] && [ "$u" -gt 0 ]; then add postgres PASS "$(basename "$f"): schema V$v, $u users, $p projects"; else add postgres FAIL "restore incomplete ($(tail -1 /tmp/drill-pg.err 2>/dev/null))"; fi
fi

dumps=$(ls -1t "$OUT"/appdb/app_*.dump 2>/dev/null | awk -F/ '{print $NF}' | sed -E 's/-[0-9]{8}T[0-9]{6}Z\.dump$//' | sort -u)
if [ -z "$dumps" ]; then add appdb SKIPPED "no app database backups"
else
  n=0; bad=0
  for db in $dumps; do
    f=$(ls -1t "$OUT"/appdb/"$db"-*.dump | head -1)
    if check_sum "$f" && q postgres "create database $db owner drill_restore" >/dev/null && docker exec -i "$C" pg_restore -U postgres --role=drill_restore -d "$db" --no-owner --no-privileges < "$f" 2>/dev/null; then n=$((n + 1)); else bad=$((bad + 1)); fi
  done
  [ "$bad" = 0 ] && add appdb PASS "$n app database(s) restored" || add appdb FAIL "$bad of $((n + bad)) app databases did not restore"
fi

f=$(ls -1t "$OUT"/forgejo/*.tar.gz 2>/dev/null | head -1)
if [ -z "$f" ]; then add forgejo SKIPPED "no backup file"
elif check_sum "$f" && tar -tzf "$f" | grep -q '^repos/\|repos/' && tar -tzf "$f" | grep -q 'forgejo-db\|gitea-db\|\.db'; then add forgejo PASS "$(basename "$f"): repositories and database present"
else add forgejo FAIL "archive unreadable or incomplete"; fi

if [ -d "$OUT/minio" ]; then
  . scripts/_minio_common.sh; bad=0; n=0
  for d in "$OUT"/minio/*/; do [ -d "$d" ] || continue; if c=$(mn_manifest_verify "$d" 2>/dev/null); then n=$((n + c)); else bad=1; fi; done
  [ "$bad" = 0 ] && add minio PASS "$n object(s) match their manifest" || add minio FAIL "manifest verification failed"
else add minio SKIPPED "no backup"; fi

python3 - "$OUT/drill.json" "$OK" "${RESULTS[@]}" <<'PY'
import json, sys, datetime
out, ok, rows = sys.argv[1], sys.argv[2] == "1", sys.argv[3:]
json.dump({"at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "passed": ok,
           "checks": [dict(zip(("component", "result", "detail"), r.split("|", 2))) for r in rows]}, open(out, "w"), indent=2)
PY
[ "$OK" = 1 ] && log "RESTORE DRILL PASSED" || { log "RESTORE DRILL FAILED"; exit 1; }
