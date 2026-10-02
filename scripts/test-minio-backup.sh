#!/usr/bin/env bash
# Drill for scripts/backup-minio.sh and scripts/restore-minio.sh against THROWAWAY MinIO containers
# (drill-minio-src on 127.0.0.1:$DRILL_MINIO_PORT, drill-minio-dst on port+1). The dev MinIO (hbl-minio-1) is never touched.
# Covers: upload -> backup (+versioning) -> delete/overwrite -> version recovery -> full restore -> checksum compare,
# integrity refusals, and mirroring to a second S3 endpoint.
# Usage: scripts/test-minio-backup.sh      (env: DRILL_MINIO_PORT default 29000)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${DRILL_MINIO_PORT:-29000}"; PORT2=$((PORT + 1))
IMG="${MC_IMAGE:-bitnamilegacy/minio:2025.7.23-debian-12-r5}"
S=drill-minio-src; D=drill-minio-dst
WORK="$(mktemp -d "${TMPDIR:-/tmp}/drill-minio.XXXXXX")"
USERK="drillkey$$"; PASSK="drill-$(date +%s)-$$-secret"   # throwaway credentials, exist only in this process
BUCKET=drill-assets
PASS=0; FAIL=0
cleanup() { docker rm -f -v "$S" "$D" >/dev/null 2>&1 || true; rm -rf "${WORK:?}"; }
trap cleanup EXIT
pass() { PASS=$((PASS + 1)); echo "PASS  $1"; }
fail() { FAIL=$((FAIL + 1)); echo "FAIL  $1"; }
ok()   { local d="$1"; shift; if "$@" >"$WORK/out.log" 2>&1; then pass "$d"; else fail "$d"; tail -8 "$WORK/out.log" | sed 's/^/      /'; fi; }
nok()  { local d="$1"; shift; if "$@" >"$WORK/out.log" 2>&1; then fail "$d (exited 0)"; else pass "$d (exit non-zero: $(tail -n1 "$WORK/out.log" | cut -c1-100))"; fi; }

for p in "$PORT" "$PORT2"; do lsof -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1 && { echo "port $p busy; set DRILL_MINIO_PORT" >&2; exit 2; }; done
docker rm -f -v "$S" "$D" >/dev/null 2>&1 || true

echo "== start two throwaway MinIO servers ($IMG)"
# The bitnami MINIO_ROOT_* values are only visible to the container; the host process keeps them in env vars.
docker run -d --name "$S" -e MINIO_ROOT_USER="$USERK" -e MINIO_ROOT_PASSWORD="$PASSK" -p "127.0.0.1:$PORT:9000" "$IMG" >/dev/null
docker run -d --name "$D" -e MINIO_ROOT_USER="$USERK" -e MINIO_ROOT_PASSWORD="$PASSK" -p "127.0.0.1:$PORT2:9000" "$IMG" >/dev/null
for p in "$PORT" "$PORT2"; do for _ in $(seq 1 60); do curl -fsS "http://127.0.0.1:$p/minio/health/live" >/dev/null 2>&1 && break; sleep 1; done; done

export BACKUP_SKIP_DOTENV=1 MINIO_ENDPOINT="http://127.0.0.1:$PORT" MINIO_ROOT_USER="$USERK" MINIO_ROOT_PASSWORD="$PASSK" MINIO_BUCKET="$BUCKET"
export MINIO_BACKUP_DIR="$WORK/backup" MC_IMAGE="$IMG"
unset MINIO_BACKUP_TARGET_URL MINIO_BACKUP_TARGET_ACCESS_KEY MINIO_BACKUP_TARGET_SECRET_KEY MINIO_BACKUP_TARGET_BUCKET
. "$HERE/_minio_common.sh"
mn_load_env
mc() { mn_mc "$@"; }          # alias src = source server; dst only when MINIO_BACKUP_TARGET_URL is set
# stage files on the host and expose them to mc through the mounted backup dir (/backup)
STAGE="$MINIO_BACKUP_DIR/_stage"; mkdir -p "$STAGE"

echo "== create bucket and upload objects"
mc mb --ignore-existing "src/$BUCKET" >/dev/null
mkdir -p "$STAGE/up/img" "$STAGE/up/docs/2026"
head -c 1048576 /dev/urandom > "$STAGE/up/img/hero.png"
head -c 524288  /dev/urandom > "$STAGE/up/img/logo.svg"
head -c 262144  /dev/urandom > "$STAGE/up/docs/2026/spec.pdf"
printf 'hello assets\n' > "$STAGE/up/readme.txt"
head -c 3145728 /dev/urandom > "$STAGE/up/big.bin"
printf 'unicode ok\n' > "$STAGE/up/docs/with space.txt"
mc cp --recursive /backup/_stage/up/ "src/$BUCKET/" >/dev/null
( cd "$STAGE/up" && find . -type f | LC_ALL=C sort | while IFS= read -r f; do printf '%s  %s\n' "$(bk_sha256 "$f")" "$f"; done ) > "$WORK/baseline.sha"
echo "   objects: $(wc -l < "$WORK/baseline.sha" | tr -d ' ')"
mc version info "src/$BUCKET" | sed 's/^/   before backup: /'

# fetch the whole bucket (latest versions) to a host directory and print "<sha>  ./key" lines
snapshot() { # label
  rm -rf "$STAGE/dl"; mkdir -p "$STAGE/dl"
  mc mirror --overwrite "src/$BUCKET" /backup/_stage/dl >/dev/null 2>&1
  ( cd "$STAGE/dl" && find . -type f | LC_ALL=C sort | while IFS= read -r f; do printf '%s  %s\n' "$(bk_sha256 "$f")" "$f"; done )
}

echo "== 1. backup-minio.sh (enables versioning, mirrors to a directory, writes a manifest)"
T0=$(date +%s)
ok "backup succeeds" "$HERE/backup-minio.sh"
echo "   backup took $(( $(date +%s) - T0 ))s"
mc version info "src/$BUCKET" | sed 's/^/   after backup: /'
mc version info "src/$BUCKET" | grep -qi enabled && pass "versioning enabled on the bucket" || fail "versioning not enabled"
[ -f "$MINIO_BACKUP_DIR/$BUCKET/MANIFEST.sha256" ] && pass "manifest written ($(wc -l < "$MINIO_BACKUP_DIR/$BUCKET/MANIFEST.sha256" | tr -d ' ') entries)" || fail "manifest missing"
( cd "$MINIO_BACKUP_DIR/$BUCKET" && find . -type f ! -name MANIFEST.sha256 | LC_ALL=C sort | while IFS= read -r f; do printf '%s  %s\n' "$(bk_sha256 "$f")" "$f"; done ) | diff -q - "$WORK/baseline.sha" >/dev/null \
  && pass "mirror files are byte-identical to the originals" || fail "mirror differs from originals"
grep -q "$PASSK" "$WORK/out.log" && fail "secret in backup log" || pass "secret not in script output"

echo "== 2. disaster: overwrite one object, delete two, add one (versioning is on)"
printf 'CORRUPTED CONTENT\n' > "$STAGE/overwrite.bin"
mc cp /backup/_stage/overwrite.bin "src/$BUCKET/img/hero.png" >/dev/null
mc rm "src/$BUCKET/big.bin" >/dev/null
mc rm "src/$BUCKET/docs/2026/spec.pdf" >/dev/null
printf 'rogue upload\n' > "$STAGE/rogue.txt"; mc cp /backup/_stage/rogue.txt "src/$BUCKET/rogue.txt" >/dev/null
snapshot disaster > "$WORK/disaster.sha"
diff -q "$WORK/disaster.sha" "$WORK/baseline.sha" >/dev/null && fail "disaster had no effect" || pass "bucket content now differs from the original"
[ "$(grep ' ./img/hero.png' "$WORK/disaster.sha" | cut -d' ' -f1)" != "$(grep ' ./img/hero.png' "$WORK/baseline.sha" | cut -d' ' -f1)" ] && pass "hero.png was overwritten" || fail "hero.png was NOT overwritten"
grep -q ' ./big.bin' "$WORK/disaster.sha" && fail "big.bin still present" || pass "big.bin deleted"
grep -q ' ./docs/2026/spec.pdf' "$WORK/disaster.sha" && fail "spec.pdf still present" || pass "spec.pdf deleted"
echo "   versions of img/hero.png:"; "$HERE/restore-minio.sh" --list-versions img/hero.png 2>&1 | sed 's/^/     /'

echo "== 3. recover from PREVIOUS VERSIONS (no mirror needed)"
ok "restore-minio.sh --object img/hero.png (overwritten object)" "$HERE/restore-minio.sh" --object img/hero.png
ok "restore-minio.sh --object big.bin (deleted object, delete marker on top)" "$HERE/restore-minio.sh" --object big.bin
ok "restore-minio.sh --object docs/2026/spec.pdf (deleted object)" "$HERE/restore-minio.sh" --object docs/2026/spec.pdf
nok "no previous version for an object that never existed" "$HERE/restore-minio.sh" --object never/existed.txt
snapshot versions > "$WORK/versions.sha"
grep -v 'rogue.txt' "$WORK/versions.sha" | diff -q - "$WORK/baseline.sha" >/dev/null && pass "all three objects recovered with their ORIGINAL checksums (rogue.txt remains, as expected)" \
  || { fail "version recovery checksum mismatch"; diff <(grep -v rogue.txt "$WORK/versions.sha") "$WORK/baseline.sha"; }

echo "== 4. total loss: delete every object (markers) and overwrite, then FULL restore from the mirror"
mc rm --recursive --force "src/$BUCKET" >/dev/null
[ -z "$(mc ls --recursive "src/$BUCKET")" ] && pass "bucket appears empty (all objects deleted)" || fail "bucket not empty"
T0=$(date +%s)
ok "restore-minio.sh full restore" "$HERE/restore-minio.sh"
echo "   restore took $(( $(date +%s) - T0 ))s"
[ -z "$(mc ls --recursive "src/$BUCKET" | grep -i manifest)" ] && pass "MANIFEST.sha256 was not restored into the bucket" || fail "manifest leaked into the bucket"
snapshot restored > "$WORK/restored.sha"
diff -q "$WORK/restored.sha" "$WORK/baseline.sha" >/dev/null && pass "restored bucket == original (all $(wc -l < "$WORK/baseline.sha" | tr -d ' ') objects, sha256 equal)" \
  || { fail "restored data differs"; diff "$WORK/restored.sha" "$WORK/baseline.sha"; }

echo "== 5. integrity refusals"
BIG="$MINIO_BACKUP_DIR/$BUCKET/img/hero.png"
cp "$BIG" "$WORK/hero.keep"; printf 'X' | dd of="$BIG" bs=1 seek=100 conv=notrunc 2>/dev/null
nok "corrupted backup file refuses full restore" "$HERE/restore-minio.sh"
cp "$WORK/hero.keep" "$BIG"
mv "$MINIO_BACKUP_DIR/$BUCKET/MANIFEST.sha256" "$WORK/manifest.keep"
nok "missing manifest refuses full restore" "$HERE/restore-minio.sh"
cp "$WORK/manifest.keep" "$MINIO_BACKUP_DIR/$BUCKET/MANIFEST.sha256"
echo stray > "$MINIO_BACKUP_DIR/$BUCKET/stray.txt"
nok "file not in manifest refuses full restore" "$HERE/restore-minio.sh"
rm -f "$MINIO_BACKUP_DIR/$BUCKET/stray.txt"
ok "restore works again after the backup is repaired" "$HERE/restore-minio.sh"
nok "backup with wrong credentials fails" env MINIO_ROOT_PASSWORD=wrong "$HERE/backup-minio.sh"
nok "backup of a missing bucket fails" env MINIO_BUCKET=does-not-exist "$HERE/backup-minio.sh"

echo "== 6. mirror to ANOTHER S3 endpoint (second throwaway MinIO), wipe source, restore from it"
export MINIO_BACKUP_TARGET_URL="http://127.0.0.1:$PORT2" MINIO_BACKUP_TARGET_ACCESS_KEY="$USERK" MINIO_BACKUP_TARGET_SECRET_KEY="$PASSK" MINIO_BACKUP_TARGET_BUCKET=offsite
mn_load_env
ok "backup to remote endpoint" "$HERE/backup-minio.sh"
dst_count="$(mc ls --recursive "dst/offsite" | wc -l | tr -d ' ')"
[ "$dst_count" = "$(wc -l < "$WORK/baseline.sha" | tr -d ' ')" ] && pass "remote bucket holds all objects ($dst_count)" || fail "remote object count $dst_count"
mc rm --recursive --force --versions "src/$BUCKET" >/dev/null 2>&1 || mc rm --recursive --force "src/$BUCKET" >/dev/null
ok "restore from the remote endpoint" "$HERE/restore-minio.sh"
snapshot remote > "$WORK/remote.sha"
diff -q "$WORK/remote.sha" "$WORK/baseline.sha" >/dev/null && pass "restore from remote == original" || { fail "remote restore differs"; diff "$WORK/remote.sha" "$WORK/baseline.sha"; }
unset MINIO_BACKUP_TARGET_URL MINIO_BACKUP_TARGET_ACCESS_KEY MINIO_BACKUP_TARGET_SECRET_KEY MINIO_BACKUP_TARGET_BUCKET

echo
echo "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ] && echo "MINIO BACKUP TEST PASSED" || { echo "MINIO BACKUP TEST FAILED" >&2; exit 1; }
