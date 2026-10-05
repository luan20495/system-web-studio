#!/usr/bin/env bash
# Shared helpers for backup-minio.sh / restore-minio.sh (sourced). Needs docker; the MinIO client `mc` runs in a
# container (default image: the one compose.yml uses, which bundles mc; minio/mc itself is not pullable any more).
#
# Source (the bucket to protect):
#   MINIO_ENDPOINT (default http://127.0.0.1:19000)  MINIO_ROOT_USER / MINIO_ROOT_PASSWORD (or MINIO_ACCESS_KEY / MINIO_SECRET_KEY)  MINIO_BUCKET
# Backup target (pick one):
#   local directory:  MINIO_BACKUP_DIR (default ./backups/minio)  -> <dir>/<bucket>/ plus MANIFEST.sha256
#   another S3/MinIO: MINIO_BACKUP_TARGET_URL, MINIO_BACKUP_TARGET_ACCESS_KEY, MINIO_BACKUP_TARGET_SECRET_KEY,
#                     MINIO_BACKUP_TARGET_BUCKET (default <bucket>-backup)
# Credentials are passed to mc through MC_HOST_<alias> environment variables (never on the command line).
# Endpoints on 127.0.0.1/localhost are rewritten to host.docker.internal for the mc container; set
# MINIO_DOCKER_NETWORK to run mc on a docker network instead (then use container names in the endpoints).
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backup_common.sh"

mn_urlencode() { # percent-encode every byte that is not unreserved
  local s="$1" i c out=""
  local LC_ALL=C
  for ((i = 0; i < ${#s}; i++)); do
    c="${s:i:1}"
    case "$c" in [A-Za-z0-9._~-]) out="$out$c" ;; *) out="$out$(printf '%%%02X' "'$c")" ;; esac
  done
  printf '%s' "$out"
}

mn_alias_url() { # endpoint user secret -> scheme://user:secret@host (credentials URL-encoded)
  local ep="$1" user="$2" secret="$3" scheme rest
  scheme="${ep%%://*}"; rest="${ep#*://}"; rest="${rest%/}"
  if [ -z "${MINIO_DOCKER_NETWORK:-}" ]; then
    rest="${rest/#127.0.0.1/host.docker.internal}"; rest="${rest/#localhost/host.docker.internal}"
  fi
  printf '%s://%s:%s@%s' "$scheme" "$(mn_urlencode "$user")" "$(mn_urlencode "$secret")" "$rest"
}

mn_load_env() {
  if [ -f "$BK_ROOT/.env" ] && [ -z "${BACKUP_SKIP_DOTENV:-}" ]; then
    local line k v
    while IFS= read -r line || [ -n "$line" ]; do
      case "$line" in ''|'#'*) continue ;; esac
      k="${line%%=*}"; v="${line#*=}"
      case "$k" in *[!A-Za-z0-9_]*|'') continue ;; esac
      v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
      eval "[ -n \"\${$k+x}\" ]" || export "$k=$v"
    done < "$BK_ROOT/.env"
  fi
  MINIO_ENDPOINT="${MINIO_ENDPOINT:-http://127.0.0.1:19000}"
  MINIO_ACCESS="${MINIO_ROOT_USER:-${MINIO_ACCESS_KEY:-}}"
  MINIO_SECRET="${MINIO_ROOT_PASSWORD:-${MINIO_SECRET_KEY:-}}"
  [ -n "$MINIO_ACCESS" ] && [ -n "$MINIO_SECRET" ] || bk_die "set MINIO_ROOT_USER and MINIO_ROOT_PASSWORD (or MINIO_ACCESS_KEY/MINIO_SECRET_KEY) in the environment or .env"
  MINIO_BUCKET="${MINIO_BUCKET:-studio-assets}"
  case "$MINIO_BUCKET" in *[!a-z0-9.-]*|'') bk_die "invalid MINIO_BUCKET" ;; esac
  MC_IMAGE="${MC_IMAGE:-bitnamilegacy/minio:2025.7.23-debian-12-r5}"
  MINIO_BACKUP_DIR="${MINIO_BACKUP_DIR:-$BK_ROOT/backups/minio}"
  MC_HOST_src="$(mn_alias_url "$MINIO_ENDPOINT" "$MINIO_ACCESS" "$MINIO_SECRET")"; export MC_HOST_src
  MN_REMOTE=0
  if [ -n "${MINIO_BACKUP_TARGET_URL:-}" ]; then
    MN_REMOTE=1
    [ -n "${MINIO_BACKUP_TARGET_ACCESS_KEY:-}" ] && [ -n "${MINIO_BACKUP_TARGET_SECRET_KEY:-}" ] || bk_die "MINIO_BACKUP_TARGET_ACCESS_KEY/SECRET_KEY are required with MINIO_BACKUP_TARGET_URL"
    MINIO_BACKUP_TARGET_BUCKET="${MINIO_BACKUP_TARGET_BUCKET:-$MINIO_BUCKET-backup}"
    MC_HOST_dst="$(mn_alias_url "$MINIO_BACKUP_TARGET_URL" "$MINIO_BACKUP_TARGET_ACCESS_KEY" "$MINIO_BACKUP_TARGET_SECRET_KEY")"; export MC_HOST_dst
  fi
  mkdir -p "$MINIO_BACKUP_DIR"
  MINIO_BACKUP_DIR="$(cd "$MINIO_BACKUP_DIR" && pwd)"
}

# mn_mc <mc args...>: run mc in a throwaway container. The local backup directory is mounted at /backup.
mn_mc() {
  local net=() i rc=0
  [ -z "${MINIO_DOCKER_NETWORK:-}" ] || net=(--network "$MINIO_DOCKER_NETWORK")
  # Up to 3 attempts: Docker Desktop's port proxy occasionally drops the first connection of a burst ("Connection
  # closed by foreign host"). Every mc operation used by the backup/restore scripts is idempotent.
  for i in 1 2 3; do
    rc=0
    docker run --rm -i --user "$(id -u):$(id -g)" --entrypoint mc ${net[@]+"${net[@]}"} \
      -e MC_CONFIG_DIR=/tmp/.mc -e MC_HOST_src -e MC_HOST_dst \
      -v "$MINIO_BACKUP_DIR:/backup" "$MC_IMAGE" --no-color "$@" </dev/null && return 0 || rc=$?
    [ "$i" = 3 ] || { bk_log "mc exited $rc (attempt $i/3), retrying"; sleep 2; }
  done
  return "$rc"
}

# Manifest of a mirrored directory: "<sha256>  <relative path>" for every file, sorted. Written/verified on the host.
mn_manifest_write() { # dir
  ( cd "$1" && find . -type f ! -name MANIFEST.sha256 ! -name '.MANIFEST.tmp' | LC_ALL=C sort | while IFS= read -r f; do
      printf '%s  %s\n' "$(bk_sha256 "$f")" "$f"; done > .MANIFEST.tmp ) && mv "$1/.MANIFEST.tmp" "$1/MANIFEST.sha256"
}
mn_manifest_verify() { # dir -> 0 if every file listed exists with the recorded sha256 and no unlisted file exists
  local dir="$1" line want path got n=0 listed unlisted
  [ -f "$dir/MANIFEST.sha256" ] || { bk_log "manifest missing: $dir/MANIFEST.sha256"; return 1; }
  while IFS= read -r line; do
    want="${line%%  *}"; path="${line#*  }"; n=$((n + 1))
    [ -f "$dir/$path" ] || { bk_log "file listed in manifest is missing: $path"; return 1; }
    got="$(bk_sha256 "$dir/$path")"
    [ "$got" = "$want" ] || { bk_log "checksum MISMATCH: $path"; return 1; }
  done < "$dir/MANIFEST.sha256"
  listed="$(cut -d' ' -f3- < "$dir/MANIFEST.sha256" | LC_ALL=C sort)"
  unlisted="$(cd "$dir" && find . -type f ! -name MANIFEST.sha256 | LC_ALL=C sort | LC_ALL=C comm -13 <(printf '%s\n' "$listed") - )"
  [ -z "$unlisted" ] || { bk_log "files not in manifest: $unlisted"; return 1; }
  echo "$n"
}
