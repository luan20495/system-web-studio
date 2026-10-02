#!/usr/bin/env bash
# Shared helpers for backup-postgres.sh / restore-postgres.sh (sourced, not executed).
#
# Connection modes (first match wins):
#   1. POSTGRES_CONTAINER=<name>        run pg_dump/pg_restore/psql inside that container (docker exec, unix socket)
#   2. PGHOST set (+PGPORT/PGUSER/PGPASSWORD/PGDATABASE)  use client tools on PATH; if none are installed, or
#      PG_CLIENT_IMAGE is set, run them in a throwaway client container (127.0.0.1/localhost -> host.docker.internal)
#   3. neither set                      defaults to the dev container hbl-postgres-1
# Credentials are only ever passed through the environment (PGPASSWORD), never as command-line arguments.
# A repo-root .env (gitignored) is loaded if present, but explicit environment variables win.

BK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

bk_load_env() {
  if [ -f "$BK_ROOT/.env" ] && [ -z "${BACKUP_SKIP_DOTENV:-}" ]; then
    # Only fill variables that are not already set in the environment.
    local line k v
    while IFS= read -r line || [ -n "$line" ]; do
      case "$line" in ''|'#'*) continue ;; esac
      k="${line%%=*}"; v="${line#*=}"
      case "$k" in *[!A-Za-z0-9_]*|'') continue ;; esac
      v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
      eval "[ -n \"\${$k+x}\" ]" || export "$k=$v"
    done < "$BK_ROOT/.env"
  fi
  PGUSER="${PGUSER:-${DATABASE_USER:-studio}}"
  PGDATABASE="${PGDATABASE:-${POSTGRES_DB:-system_web_studio}}"
  if [ -z "${PGPASSWORD:-}" ] && [ -n "${DATABASE_PASSWORD:-}" ]; then PGPASSWORD="$DATABASE_PASSWORD"; fi
  export PGUSER PGDATABASE
  if [ -n "${PGPASSWORD:-}" ]; then export PGPASSWORD; fi
  if [ -z "${POSTGRES_CONTAINER:-}" ] && [ -z "${PGHOST:-}" ]; then POSTGRES_CONTAINER="hbl-postgres-1"; fi
  BACKUP_DIR="${BACKUP_DIR:-$BK_ROOT/backups}"
  BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
  BACKUP_KEEP_MIN="${BACKUP_KEEP_MIN:-3}"
}

bk_log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
bk_die() { bk_log "ERROR: $*"; exit 1; }

# bk_pg <tool> [args...] : run a PostgreSQL client tool in the configured mode. stdin/stdout pass through.
bk_pg() {
  local tool="$1"; shift
  if [ -n "${POSTGRES_CONTAINER:-}" ]; then
    # "-e NAME" without a value forwards the variable from our environment (not visible in argv).
    docker exec -i -e PGPASSWORD "$POSTGRES_CONTAINER" "$tool" -U "$PGUSER" "$@"
  elif [ -z "${PG_CLIENT_IMAGE:-}" ] && command -v "$tool" >/dev/null 2>&1; then
    "$tool" "$@"
  else
    local host="${PGHOST}"
    case "$host" in 127.0.0.1|localhost|::1) host="host.docker.internal" ;; esac
    docker run --rm -i -e PGPASSWORD -e "PGHOST=$host" -e "PGPORT=${PGPORT:-5432}" -e "PGUSER=$PGUSER" \
      "${PG_CLIENT_IMAGE:-postgres:17.6}" "$tool" "$@"
  fi
}

bk_psql() { bk_pg psql -X -v ON_ERROR_STOP=1 -qAt "$@"; }

bk_sha256() { # prints the hex digest of a file
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# Verifies <file>.sha256 ("<hex>  <basename>") against the file. Fails if the sidecar is missing or malformed.
bk_verify_checksum() {
  local f="$1" side="$1.sha256" want got
  [ -f "$f" ] || { bk_log "dump not found: $f"; return 1; }
  [ -f "$side" ] || { bk_log "checksum file missing: $side"; return 1; }
  want="$(cut -d' ' -f1 < "$side" | head -n1)"
  case "$want" in *[!0-9a-f]*|'') bk_log "malformed checksum file: $side"; return 1 ;; esac
  [ "${#want}" = 64 ] || { bk_log "malformed checksum file: $side"; return 1; }
  got="$(bk_sha256 "$f")"
  [ "$want" = "$got" ] || { bk_log "checksum MISMATCH for $f"; return 1; }
}

# Structural check of a custom-format dump: pg_restore --list must succeed and list at least one entry.
bk_verify_dump() {
  local out n
  out="$(bk_pg pg_restore --list < "$1")" || { bk_log "pg_restore --list failed for $1"; return 1; }
  n="$(printf '%s\n' "$out" | grep -vc '^;' || true)"
  [ "$n" -gt 0 ] || { bk_log "dump has no entries: $1"; return 1; }
  echo "$n"
}

# Lock: mkdir is atomic everywhere. A lock whose pid is dead is considered stale and taken over.
BK_LOCK_DIR=""
bk_lock() {
  BK_LOCK_DIR="${1:?lock path}"
  if ! mkdir "$BK_LOCK_DIR" 2>/dev/null; then
    local pid; pid="$(cat "$BK_LOCK_DIR/pid" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then BK_LOCK_DIR=""; bk_die "another run holds the lock ($1, pid $pid)"; fi
    bk_log "removing stale lock (pid ${pid:-unknown})"; rm -rf "${BK_LOCK_DIR:?}"
    mkdir "$BK_LOCK_DIR" 2>/dev/null || { BK_LOCK_DIR=""; bk_die "cannot take lock $1"; }
  fi
  echo "$$" > "$BK_LOCK_DIR/pid"
}
bk_unlock() { if [ -n "$BK_LOCK_DIR" ]; then rm -rf "${BK_LOCK_DIR:?}"; fi; BK_LOCK_DIR=""; }
