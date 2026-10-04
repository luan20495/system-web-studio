#!/usr/bin/env bash
# Scheduled backup of everything a deployment needs to be restored (stage L, ADR 0020):
#   platform PostgreSQL (pg_dump -Fc, verified, checksummed), every server-app database on the apps DB server (+ its roles),
#   MinIO buckets (assets + artifacts, mirrored with a sha256 manifest), Forgejo (forgejo dump: repositories + its database).
# Retention: BACKUP_RETENTION_DAYS (default 14), never fewer than BACKUP_KEEP_MIN (3) per component.
# Writes <dir>/status.json (per component: last success, size, error) — read by Admin → Sao lưu. Components that are not deployed in this
# environment are reported as SKIPPED, never as success.
# Usage: scripts/backup-all.sh [local|public]   (default local; public uses the hblpub-* containers and backups/public)
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$ROOT"
ENVN="${1:-local}"
if [ "$ENVN" = public ]; then P=hblpub; ENVF=.run/public/public.env; else P=hbl; ENVF=.env; fi
set -a; [ -f "$ENVF" ] && . "./$ENVF"; [ "$ENVN" = local ] && [ -f .run/appdb.env ] && . ./.run/appdb.env; set +a
# MinIO endpoint/credentials per environment (local defaults are the dev-only values of scripts/_env.sh)
if [ "$ENVN" = public ]; then export MINIO_ENDPOINT="http://127.0.0.1:${MINIO_PORT_PUBLIC:-29000}" POSTGRES_DB=studio
else export MINIO_ENDPOINT="${MINIO_ENDPOINT:-http://127.0.0.1:19000}" MINIO_ROOT_USER="${MINIO_ROOT_USER:-studio-minio}" MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-studio-local-only}"; fi
export MINIO_ROOT_USER MINIO_ROOT_PASSWORD BACKUP_SKIP_DOTENV=1
OUT="${BACKUP_ROOT:-$ROOT/backups}/$ENVN"; mkdir -p "$OUT"; chmod 700 "$OUT"
DAYS="${BACKUP_RETENTION_DAYS:-14}"; KEEP="${BACKUP_KEEP_MIN:-3}"
STATUS="$OUT/status.json"; [ -f "$STATUS" ] || echo '{}' > "$STATUS"
log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
running() { docker ps --format '{{.Names}}' | grep -qx "$1"; }
record() { # component state file size_bytes error duration_s
  python3 - "$STATUS" "$1" "$2" "$3" "$4" "$5" "$6" <<'PY'
import json, sys, datetime
p, comp, state, f, size, err, dur = sys.argv[1:8]
d = json.load(open(p))
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
c = d.get(comp, {})
c.update({"lastRun": now, "lastState": state, "lastError": err or None, "durationSeconds": int(dur or 0)})
if state == "OK": c.update({"lastSuccess": now, "file": f, "sizeBytes": int(size or 0)})
d[comp] = c
json.dump(d, open(p, "w"), indent=2)
PY
}
prune() { # dir glob — delete files older than DAYS but keep the newest KEEP
  local dir="$1" pat="$2"; local all; all=$(ls -1t "$dir"/$pat 2>/dev/null | grep -v '\.sha256$' || true)
  echo "$all" | tail -n +$((KEEP + 1)) | while read -r f; do [ -n "$f" ] && [ -n "$(find "$f" -mtime +"$DAYS" -print 2>/dev/null)" ] && { rm -f "$f" "$f.sha256"; log "pruned $f"; }; done
}
sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
TS="$(date -u +%Y%m%dT%H%M%SZ)"; FAILED=0
umask 077

# 1. platform PostgreSQL (existing verified/atomic script)
t0=$(date +%s); mkdir -p "$OUT/postgres"
if running "$P-postgres-1"; then
  if POSTGRES_CONTAINER="$P-postgres-1" BACKUP_DIR="$OUT/postgres" BACKUP_RETENTION_DAYS="$DAYS" BACKUP_KEEP_MIN="$KEEP" BACKUP_SKIP_DOTENV=1 \
     PGUSER="${DATABASE_USER:-studio}" PGDATABASE="${POSTGRES_DB:-system_web_studio}" scripts/backup-postgres.sh 2>>"$OUT/backup.log"; then
    f=$(ls -1t "$OUT"/postgres/*.dump | head -1); record postgres OK "$f" "$(wc -c < "$f" | tr -d ' ')" "" $(( $(date +%s) - t0 ))
  else record postgres FAILED "" 0 "backup-postgres.sh failed (see backup.log)" $(( $(date +%s) - t0 )); FAILED=1; fi
else record postgres SKIPPED "" 0 "container $P-postgres-1 not running" 0; fi

# 2. apps DB server: every app database + roles (only where server apps are deployed)
t0=$(date +%s); mkdir -p "$OUT/appdb"
if running "$P-appdb-1"; then
  ok=1; total=0; n=0
  dbs=$(docker exec -e PGPASSWORD="$APPDB_ADMIN_PASSWORD" "$P-appdb-1" psql -U appdb_admin -d appdb -tAc "select datname from pg_database where datname like 'app\_%' order by 1") || ok=0
  docker exec -e PGPASSWORD="$APPDB_ADMIN_PASSWORD" "$P-appdb-1" pg_dumpall -U appdb_admin --roles-only > "$OUT/appdb/roles-$TS.sql" 2>>"$OUT/backup.log" || ok=0
  for db in $dbs; do
    f="$OUT/appdb/$db-$TS.dump"
    if docker exec -e PGPASSWORD="$APPDB_ADMIN_PASSWORD" "$P-appdb-1" pg_dump -U appdb_admin -Fc "$db" > "$f.tmp" 2>>"$OUT/backup.log" && [ -s "$f.tmp" ]; then
      mv "$f.tmp" "$f"; echo "$(sha "$f")  $(basename "$f")" > "$f.sha256"; total=$((total + $(wc -c < "$f" | tr -d ' '))); n=$((n + 1))
    else rm -f "$f.tmp"; ok=0; fi
    prune "$OUT/appdb" "$db-*.dump"
  done
  prune "$OUT/appdb" "roles-*.sql"
  if [ "$ok" = 1 ]; then record appdb OK "$OUT/appdb ($n databases)" "$total" "" $(( $(date +%s) - t0 )); else record appdb FAILED "" 0 "pg_dump of an app database failed" $(( $(date +%s) - t0 )); FAILED=1; fi
else record appdb SKIPPED "" 0 "no apps DB server in this environment (server apps not deployed)" 0; fi

# 3. MinIO buckets (existing mirror + manifest script, once per bucket)
t0=$(date +%s); ok=1; total=0
if running "$P-minio-1"; then
  for b in "${MINIO_BUCKET:-studio-assets}" "${MINIO_ARTIFACTS_BUCKET:-studio-artifacts}"; do
    MINIO_BUCKET="$b" MINIO_BACKUP_DIR="$OUT/minio" scripts/backup-minio.sh 2>>"$OUT/backup.log" || ok=0
  done
  total=$(du -sk "$OUT/minio" 2>/dev/null | cut -f1); total=$(( ${total:-0} * 1024 ))
  if [ "$ok" = 1 ]; then record minio OK "$OUT/minio" "$total" "" $(( $(date +%s) - t0 )); else record minio FAILED "" 0 "backup-minio.sh failed (see backup.log)" $(( $(date +%s) - t0 )); FAILED=1; fi
else record minio SKIPPED "" 0 "container $P-minio-1 not running" 0; fi

# 4. Forgejo (repositories + its sqlite database), consistent dump made by Forgejo itself
t0=$(date +%s); mkdir -p "$OUT/forgejo"
if running "$P-forgejo-1"; then
  f="$OUT/forgejo/forgejo-$TS.tar.gz"
  if docker exec "$P-forgejo-1" sh -c 'cd /tmp && rm -f fj.tar.gz && forgejo dump --type tar.gz --file /tmp/fj.tar.gz --skip-log >/dev/null 2>&1' \
     && docker cp "$P-forgejo-1:/tmp/fj.tar.gz" "$f" >/dev/null && docker exec "$P-forgejo-1" rm -f /tmp/fj.tar.gz && tar -tzf "$f" >/dev/null; then
    echo "$(sha "$f")  $(basename "$f")" > "$f.sha256"; record forgejo OK "$f" "$(wc -c < "$f" | tr -d ' ')" "" $(( $(date +%s) - t0 ))
  else rm -f "$f"; record forgejo FAILED "" 0 "forgejo dump failed" $(( $(date +%s) - t0 )); FAILED=1; fi
  prune "$OUT/forgejo" "forgejo-*.tar.gz"
else record forgejo SKIPPED "" 0 "no Git server in this environment" 0; fi

log "backup-all ($ENVN) finished, failures=$FAILED"
exit "$FAILED"
