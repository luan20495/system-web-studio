#!/usr/bin/env bash
# Back up the MinIO/S3 asset bucket. The PostgreSQL backup does NOT contain asset bytes; this does.
#   1. enables bucket versioning on the source (idempotent; MINIO_ENSURE_VERSIONING=false to skip),
#   2. mirrors the bucket (latest versions) to a local directory or to another S3/MinIO bucket,
#   3. verifies the copy (local: sha256 MANIFEST.sha256 written; remote: `mc diff` must be empty).
# Deleted source objects are KEPT in the backup unless MINIO_MIRROR_REMOVE=true (safer default: an accidental
# mass delete must not propagate to the backup). Configuration: see scripts/_minio_common.sh.
# Exit status is non-zero on any failure.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_minio_common.sh"
mn_load_env
command -v docker >/dev/null || bk_die "docker is required (mc runs in a container)"
bk_lock "$MINIO_BACKUP_DIR/.backup-minio.lock"
trap bk_unlock EXIT
START=$(date +%s)
rm_flag=(); [ "${MINIO_MIRROR_REMOVE:-false}" != true ] || rm_flag=(--remove)

mn_mc ls "src/$MINIO_BUCKET" >/dev/null 2>&1 || bk_die "cannot list source bucket '$MINIO_BUCKET' at $MINIO_ENDPOINT (endpoint, credentials, bucket name?)"
if [ "${MINIO_ENSURE_VERSIONING:-true}" != false ]; then
  mn_mc version enable "src/$MINIO_BUCKET" >/dev/null || bk_die "could not enable versioning on $MINIO_BUCKET"
  state="$(mn_mc version info "src/$MINIO_BUCKET" 2>&1 || true)"
  case "$state" in *nabled*) bk_log "versioning on $MINIO_BUCKET: enabled" ;; *) bk_die "versioning is not enabled on $MINIO_BUCKET: $state" ;; esac
fi

if [ "$MN_REMOTE" = 1 ]; then
  mn_mc mb --ignore-existing "dst/$MINIO_BACKUP_TARGET_BUCKET" >/dev/null || bk_die "cannot create/access target bucket"
  bk_log "mirroring $MINIO_BUCKET -> $MINIO_BACKUP_TARGET_URL/$MINIO_BACKUP_TARGET_BUCKET"
  mn_mc mirror --overwrite ${rm_flag[@]+"${rm_flag[@]}"} "src/$MINIO_BUCKET" "dst/$MINIO_BACKUP_TARGET_BUCKET" >/dev/null || bk_die "mirror failed"
  diff_out="$(mn_mc diff "src/$MINIO_BUCKET" "dst/$MINIO_BACKUP_TARGET_BUCKET" 2>&1 || true)"
  # objects deleted from the source are intentionally kept in the target ("only in second" is fine)
  if printf '%s\n' "$diff_out" | grep -v '^$' | grep -qv 'in second\|only in'; then bk_die "verification failed: source and target differ: $diff_out"; fi
  bk_log "OK remote mirror verified in $(( $(date +%s) - START ))s"
else
  DEST="$MINIO_BACKUP_DIR/$MINIO_BUCKET"; mkdir -p "$DEST"
  bk_log "mirroring $MINIO_BUCKET -> $DEST"
  mn_mc mirror --overwrite ${rm_flag[@]+"${rm_flag[@]}"} "src/$MINIO_BUCKET" "/backup/$MINIO_BUCKET" >/dev/null || bk_die "mirror failed"
  # Every object currently in the bucket must exist locally with the same size.
  missing=0
  while IFS='|' read -r key size; do
    [ -n "$key" ] || continue
    [ -f "$DEST/$key" ] && [ "$(wc -c < "$DEST/$key" | tr -d ' ')" = "$size" ] || { bk_log "verify: $key missing or wrong size in backup"; missing=1; }
  done < <(mn_mc ls --recursive --json "src/$MINIO_BUCKET" | sed -n 's/.*"size":\([0-9]*\),"key":"\([^"]*\)".*/\2|\1/p')
  [ "$missing" = 0 ] || bk_die "verification failed"
  mn_manifest_write "$DEST"
  n="$(mn_manifest_verify "$DEST")" || bk_die "manifest verification failed"
  bk_log "OK $n object(s) mirrored to $DEST, manifest written, in $(( $(date +%s) - START ))s"
fi
