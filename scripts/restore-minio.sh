#!/usr/bin/env bash
# Restore the MinIO/S3 asset bucket.
#
#   restore-minio.sh                         full restore: mirror the local backup (or the remote target) back into the bucket
#   restore-minio.sh --object KEY            recover ONE object from a previous VERSION in the (versioned) bucket itself:
#                                            picks the newest version below the current one that is not a delete marker (works after an
#                                            overwrite or a delete); add --version-id ID to choose a specific version
#   restore-minio.sh --list-versions KEY     show versions of KEY
# Full restore verifies MANIFEST.sha256 first (local) and refuses on any mismatch or missing manifest.
# It only ADDS/OVERWRITES objects; objects that exist in the bucket but not in the backup are kept unless
# MINIO_RESTORE_REMOVE=true. The target bucket is created if it does not exist. Configuration: _minio_common.sh.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_minio_common.sh"
mn_load_env
MODE=full; KEY=""; VID=""
while [ $# -gt 0 ]; do
  case "$1" in
    --object) MODE=object; shift; KEY="${1:-}" ;;
    --version-id) shift; VID="${1:-}" ;;
    --list-versions) MODE=list; shift; KEY="${1:-}" ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) bk_die "unknown argument $1" ;;
  esac; shift
done
case "$MODE" in object|list) [ -n "$KEY" ] || bk_die "object key required" ;; esac
case "$KEY$VID" in *[!A-Za-z0-9._/=@+-]*) bk_die "unsupported characters in key/version id" ;; esac

if [ "$MODE" = list ]; then mn_mc ls --versions "src/$MINIO_BUCKET/$KEY"; exit 0; fi

if [ "$MODE" = object ]; then
  if [ -z "$VID" ]; then
    # mc prints one JSON line per version. The version with the highest versionOrdinal is the current (latest)
    # one: skip it, then take the newest remaining version that is not a delete marker.
    tmp="$MINIO_BACKUP_DIR/.recover.tmp"
    while IFS= read -r line; do
      ord="$(printf '%s' "$line" | sed -n 's/.*"versionOrdinal":\([0-9]*\).*/\1/p')"
      v="$(printf '%s' "$line" | sed -n 's/.*"versionId":"\([^"]*\)".*/\1/p')"
      mark=0; case "$line" in *'"isDeleteMarker":true'*) mark=1 ;; esac
      [ -n "$ord" ] && [ -n "$v" ] && printf '%s %s %s\n' "$ord" "$mark" "$v"
    done < <(mn_mc ls --versions --json "src/$MINIO_BUCKET/$KEY") | sort -rn | sed '1d' | awk '$2 == 0 { print $3; exit }' > "$tmp"
    VID="$(head -n1 "$tmp")"; rm -f "$tmp"
    [ -n "$VID" ] || bk_die "no previous version of '$KEY' found (is versioning enabled on $MINIO_BUCKET, and did the object exist before?)"
  fi
  mn_mc cp --version-id "$VID" "src/$MINIO_BUCKET/$KEY" "src/$MINIO_BUCKET/$KEY" >/dev/null || bk_die "copy of version $VID failed"
  bk_log "OK recovered $KEY from version $VID (stored as a new latest version)"
  exit 0
fi

# ---- full restore
bk_lock "$MINIO_BACKUP_DIR/.restore-minio.lock"; trap bk_unlock EXIT
START=$(date +%s)
rm_flag=(); [ "${MINIO_RESTORE_REMOVE:-false}" != true ] || rm_flag=(--remove)
mn_mc mb --ignore-existing "src/$MINIO_BUCKET" >/dev/null || bk_die "cannot create/access bucket $MINIO_BUCKET"
if [ "$MN_REMOTE" = 1 ]; then
  mn_mc ls "dst/$MINIO_BACKUP_TARGET_BUCKET" >/dev/null 2>&1 || bk_die "cannot read backup bucket $MINIO_BACKUP_TARGET_BUCKET"
  mn_mc mirror --overwrite --exclude MANIFEST.sha256 --exclude .MANIFEST.tmp ${rm_flag[@]+"${rm_flag[@]}"} "dst/$MINIO_BACKUP_TARGET_BUCKET" "src/$MINIO_BUCKET" >/dev/null || bk_die "restore mirror failed"
else
  SRC="$MINIO_BACKUP_DIR/$MINIO_BUCKET"
  [ -d "$SRC" ] || bk_die "no backup directory $SRC"
  n="$(mn_manifest_verify "$SRC")" || bk_die "backup verification failed; not restoring"
  bk_log "backup verified ($n objects)"
  mn_mc mirror --overwrite --exclude MANIFEST.sha256 --exclude .MANIFEST.tmp ${rm_flag[@]+"${rm_flag[@]}"} "/backup/$MINIO_BUCKET" "src/$MINIO_BUCKET" >/dev/null || bk_die "restore mirror failed"
fi
bk_log "OK restored into $MINIO_BUCKET at $MINIO_ENDPOINT in $(( $(date +%s) - START ))s"
