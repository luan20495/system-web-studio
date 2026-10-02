#!/usr/bin/env bash
# End-to-end point-in-time-recovery drill in THROWAWAY containers/volumes (all named drill-pitr-*):
#   primary with WAL archiving -> rows A -> base backup -> rows B -> record T -> rows C + accidental DELETE and DROP
#   -> switch WAL -> recover a NEW container to T -> assert A+B present, C absent, dropped table back.
# Then: second base backup + pitr-prune.sh, and a recover-to-latest from the pruned archive (proves retention kept enough WAL).
# Exit status is non-zero on any mismatch. The dev stack (hbl-*) is never touched.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PITR="$HERE/../infra/postgres-pitr"
P=drill-pitr-primary; R1=drill-pitr-restore-t; R2=drill-pitr-restore-latest
VD=drill-pitr-data; VA=drill-pitr-archive; VB=drill-pitr-base
IMG=postgres:17.6
export PITR_CONTAINER="$P" PITR_USER=studio PITR_ARCHIVE_VOLUME="$VA" PITR_BACKUP_VOLUME="$VB" PITR_IMAGE="$IMG"
FAILS=0
cleanup() {
  docker rm -f -v "$P" "$R1" "$R2" >/dev/null 2>&1 || true
  docker volume rm "$VD" "$VA" "$VB" drill-pitr-restore-t-data drill-pitr-restore-latest-data >/dev/null 2>&1 || true
}
trap cleanup EXIT
now() { date +%s; }
t0=$(now)
step() { echo; echo "== [$(( $(now) - t0 ))s] $*"; }
check() { if [ "$2" = "$3" ]; then echo "PASS  $1 (= $3)"; else echo "FAIL  $1: expected '$3', got '$2'"; FAILS=$((FAILS + 1)); fi; }
q() { docker exec "$1" psql -U studio -d drill -X -v ON_ERROR_STOP=1 -qAt -c "$2"; }

cleanup
step "start primary with archive_mode=on (config infra/postgres-pitr/postgresql.pitr.conf)"
docker volume create "$VD" >/dev/null; docker volume create "$VA" >/dev/null; docker volume create "$VB" >/dev/null
docker run --rm --user root -v "$VA:/wal-archive" -v "$VB:/base-backups" "$IMG" chown postgres:postgres /wal-archive /base-backups
docker run -d --name "$P" -e POSTGRES_PASSWORD="pitr-$$-$(now)" -e POSTGRES_USER=studio -e POSTGRES_DB=drill \
  -v "$VD:/var/lib/postgresql/data" -v "$VA:/wal-archive" -v "$VB:/base-backups" \
  -v "$PITR/postgresql.pitr.conf:/etc/postgresql/pitr.conf:ro" "$IMG" postgres -c config_file=/etc/postgresql/pitr.conf >/dev/null
for _ in $(seq 1 60); do
  if docker exec "$P" pg_isready -U studio -d drill -q 2>/dev/null && [ "$(q "$P" 'show archive_mode' 2>/dev/null)" = on ]; then break; fi; sleep 1
done
check "archive_mode" "$(q "$P" 'show archive_mode')" on
check "wal_level" "$(q "$P" 'show wal_level')" replica
check "archive_timeout" "$(q "$P" 'show archive_timeout')" 1min

step "phase A: schema + 1000 'A' rows (orders) + ledger table (200 rows)"
docker exec -i "$P" psql -U studio -d drill -X -v ON_ERROR_STOP=1 -q >/dev/null <<'SQL'
CREATE TABLE orders (id bigserial PRIMARY KEY, batch text NOT NULL, payload text NOT NULL);
CREATE TABLE ledger (id int PRIMARY KEY, note text NOT NULL);
INSERT INTO orders (batch, payload) SELECT 'A', md5(g::text) FROM generate_series(1,1000) g;
INSERT INTO ledger SELECT g, 'ledger-'||g FROM generate_series(1,200) g;
SQL

step "base backup #1 (pitr-basebackup.sh)"
OUT1="$("$PITR/pitr-basebackup.sh")" || { echo "FAIL  base backup failed"; exit 1; }
echo "$OUT1" | sed 's/^/   /'
BASE1="$(basename "$(echo "$OUT1" | tail -n1)")"
echo "   backup_label copy:"; docker exec "$P" cat "/base-backups/$BASE1/backup_label.txt" | sed 's/^/     /'

step "phase B: 500 'B' rows; then record recovery target T"
q "$P" "INSERT INTO orders (batch, payload) SELECT 'B', md5(g::text) FROM generate_series(1,500) g" >/dev/null
sleep 2
T="$(q "$P" "select to_char(clock_timestamp() at time zone 'UTC','YYYY-MM-DD HH24:MI:SS.US')||'+00'")"
echo "   T = $T"
sleep 2
check "orders before accident" "$(q "$P" "select count(*) from orders")" 1500

step "phase C (after T): 300 'C' rows, then the ACCIDENT: delete all A rows and DROP TABLE ledger"
q "$P" "INSERT INTO orders (batch, payload) SELECT 'C', md5(g::text) FROM generate_series(1,300) g" >/dev/null
q "$P" "DELETE FROM orders WHERE batch = 'A'" >/dev/null
q "$P" "DROP TABLE ledger" >/dev/null
check "orders after accident (B+C)" "$(q "$P" "select count(*) from orders")" 800
check "ledger dropped" "$(q "$P" "select to_regclass('public.ledger') is null")" t
END_WAL="$(q "$P" "select pg_walfile_name(pg_current_wal_lsn())")"   # segment holding the accident (before the switch)
q "$P" "select pg_switch_wal()" >/dev/null
SW=$(now)
for _ in $(seq 1 60); do
  [ "$(q "$P" "select coalesce(last_archived_wal,'') >= '$END_WAL' from pg_stat_archiver")" = t ] && break; sleep 1
done
echo "   switch -> archived latency: $(( $(now) - SW ))s"
echo "   archive: $(docker run --rm -v "$VA:/a:ro" "$IMG" sh -c 'ls -1 /a | grep -cE "^[0-9A-F]{24}$"') WAL segments archived; failed_count=$(q "$P" "select failed_count from pg_stat_archiver")"
check "WAL up to the accident is archived" "$(q "$P" "select coalesce(last_archived_wal,'') >= '$END_WAL' from pg_stat_archiver")" t

step "PITR: recover NEW container $R1 to T"
TR=$(now)
PITR_RESTORE_NAME="$R1" "$PITR/pitr-restore.sh" --base "$BASE1" --target-time "$T" 2>&1 | sed 's/^/   /'
RC=${PIPESTATUS[0]}
[ "$RC" = 0 ] || { echo "FAIL  pitr-restore.sh exited $RC"; docker logs "$R1" 2>&1 | tail -20; exit 1; }
echo "   recovery wall time: $(( $(now) - TR ))s"

step "verify recovered database (target T)"
check "recovered: total orders"     "$(q "$R1" "select count(*) from orders")" 1500
check "recovered: A rows present"   "$(q "$R1" "select count(*) from orders where batch='A'")" 1000
check "recovered: B rows present"   "$(q "$R1" "select count(*) from orders where batch='B'")" 500
check "recovered: C rows absent"    "$(q "$R1" "select count(*) from orders where batch='C'")" 0
check "recovered: ledger table is back" "$(q "$R1" "select count(*) from ledger")" 200
check "recovered: not in recovery (promoted)" "$(q "$R1" "select pg_is_in_recovery()")" f
check "recovered: read-write after promote" "$(q "$R1" "insert into ledger values (999,'post-recovery') returning id")" 999
check "recovered: new timeline" "$(q "$R1" "select timeline_id > 1 from pg_control_checkpoint()")" t
check "source untouched by the drill" "$(q "$P" "select count(*) from orders")" 800

step "retention: base backup #2, then pitr-prune.sh with PITR_KEEP_BASE=1"
sleep 1
OUT2="$("$PITR/pitr-basebackup.sh")" || { echo "FAIL  base backup #2 failed"; exit 1; }
echo "$OUT2" | head -n1 | sed 's/^/   /'
BASE2="$(basename "$(echo "$OUT2" | tail -n1)")"
START2="$(docker exec "$P" sh -c ". /base-backups/$BASE2/meta.env; echo \$START_WAL")"
before="$(docker run --rm -v "$VA:/a:ro" "$IMG" sh -c 'ls -1 /a | grep -cE "^[0-9A-F]{24}$"')"
PITR_KEEP_BASE=1 "$PITR/pitr-prune.sh" 2>&1 | sed 's/^/   /'
after="$(docker run --rm -v "$VA:/a:ro" "$IMG" sh -c 'ls -1 /a | grep -cE "^[0-9A-F]{24}$"')"
echo "   WAL segments before=$before after=$after; oldest needed (base #2 start) = $START2"
check "old base backup #1 pruned" "$(docker exec "$P" sh -c "test -d /base-backups/$BASE1 && echo yes || echo no")" no
check "newest base backup #2 kept" "$(docker exec "$P" sh -c "test -f /base-backups/$BASE2/base.tar.gz && echo yes || echo no")" yes
check "segments were pruned" "$([ "$after" -lt "$before" ] && echo yes || echo no)" yes
check "WAL needed by base #2 still present" "$(docker run --rm -v "$VA:/a:ro" "$IMG" sh -c "test -f /a/$START2 && echo yes || echo no")" yes
check "no WAL older than base #2 start remains" "$(docker run --rm -v "$VA:/a:ro" "$IMG" sh -c "ls -1 /a | grep -E '^[0-9A-F]{24}\$' | awk -v s=$START2 '\$0 < s' | wc -l | tr -d ' '")" 0

step "recover from the PRUNED archive: base #2 to end of WAL (proves retention kept enough WAL)"
PITR_RESTORE_NAME="$R2" "$PITR/pitr-restore.sh" --base "$BASE2" --latest 2>&1 | sed 's/^/   /'
RC=${PIPESTATUS[0]}
[ "$RC" = 0 ] || { echo "FAIL  recovery from pruned archive exited $RC"; docker logs "$R2" 2>&1 | tail -20; exit 1; }
check "latest: orders (B+C, after the accident)" "$(q "$R2" "select count(*) from orders")" 800
check "latest: ledger is gone (accident replayed)" "$(q "$R2" "select to_regclass('public.ledger') is null")" t

step "refusal: restore without a target must be rejected"
if PITR_RESTORE_NAME=drill-pitr-restore-bad "$PITR/pitr-restore.sh" --base "$BASE2" >/dev/null 2>&1; then echo "FAIL  accepted no target"; FAILS=$((FAILS + 1)); else echo "PASS  rejected (non-zero)"; fi
docker rm -f -v drill-pitr-restore-bad >/dev/null 2>&1; docker volume rm drill-pitr-restore-bad-data >/dev/null 2>&1 || true

echo
echo "total drill time: $(( $(now) - t0 ))s"
if [ "$FAILS" = 0 ]; then echo "PITR DRILL PASSED"; else echo "PITR DRILL FAILED ($FAILS check(s))" >&2; exit 1; fi
