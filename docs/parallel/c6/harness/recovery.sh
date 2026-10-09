#!/usr/bin/env bash
# C6 failure / recovery suite against the RUNNING local stack (scripts/run-local.sh). Never edits production code.
# Creates throw-away "C6 recovery" projects in the local dev workspace; stops and restarts local containers (data volumes are kept).
# Run from repo root on the Mac (normally called by mac-full.sh):  bash docs/parallel/c6/harness/recovery.sh
# Output: docs/parallel/c6/evidence/mac/recovery.psv  (KEY|STATUS|text|at=…)  and a human copy on stdout. Exit 0 = no FAIL and every automatable case ran.
# R09-R11 are recorded as GAP (feature not wired yet) — never as PASS.
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$C6_ROOT" || exit 2
. scripts/_env.sh; set +e +u +o pipefail            # _env.sh switches on `set -euo pipefail`; this runner must keep going and record every result
mkdir -p "$C6_EV"; C6_RESULTS="$C6_EV/recovery.psv"; : > "$C6_RESULTS"
B="${API_BASE:-http://127.0.0.1:8080/api/v1}"; W=00000000-0000-0000-0000-000000000001; PROFILE="${STACK_PROFILE:-full}"
J="$(mktemp)"; trap 'rm -f "$J"' EXIT
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval('d'+sys.argv[1]))" "$1"; }
ready() { [ "$(code http://127.0.0.1:8080/actuator/health/readiness)" = 200 ]; }
wait_ready() { local i; for i in $(seq 1 "${1:-90}"); do ready && return 0; sleep 2; done; return 1; }
login() {
  : > "$J"
  local T L; T=$(curl -s -c "$J" -b "$J" "$B/auth/csrf" | jget '["token"]') || return 1
  L=$(python3 -c 'import json,sys;print(json.dumps({"username":"local.admin","password":sys.argv[1]}))' "$LOCAL_ADMIN_PASSWORD")
  [ "$(code -c "$J" -b "$J" -X POST "$B/auth/login" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d "$L")" = 200 ] || return 1
  CSRF=$(curl -s -c "$J" -b "$J" "$B/auth/csrf" | jget '["token"]')
}
newproject() { curl -s -b "$J" -c "$J" -H "X-XSRF-TOKEN: $CSRF" -H 'Content-Type: application/json' -X POST "$B/workspaces/$W/projects" -d '{"name":"C6 recovery"}' | jget '["id"]'; }
revision() { curl -s -b "$J" "$B/workspaces/$W/projects/$1" | jget '["revision"]'; }
prompt() { curl -s -o /dev/null -w '%{http_code}' -b "$J" -H "X-XSRF-TOKEN: $CSRF" -H 'Content-Type: application/json' -X POST "$B/workspaces/$W/projects/$1/prompts" -d "{\"prompt\":\"thêm bảng so sánh\",\"expectedRevision\":$2}"; }

if ! ready; then res RECOVERY_R00 FAIL "stack_not_ready(readiness!=200)"; exit 2; fi
if ! login; then res RECOVERY_R00 FAIL "login_failed"; exit 2; fi
P=$(newproject); if [ -z "$P" ]; then res RECOVERY_R00 FAIL "cannot_create_project"; exit 2; fi
res RECOVERY_R00 INFO "project=$P" "profile=$PROFILE"

# R01 concurrent writes, same expectedRevision: optimistic lock → exactly one 200, the rest 409
REV=$(revision "$P"); RES=$(for i in 1 2 3 4 5; do prompt "$P" "$REV" & done; wait)
OK=$(echo "$RES" | grep -c '^200$'); CF=$(echo "$RES" | grep -c '^409$')
if [ "$OK" = 1 ] && [ $((OK + CF)) = 5 ]; then res RECOVERY_R01 PASS "5_concurrent_same_revision:1x200+${CF}x409"; else res RECOVERY_R01 FAIL "codes=$(echo $RES | tr '\n' ' ')" "expected=exactly_one_200_rest_409"; fi
# R02 retry/replay with a stale revision is a conflict, never a second write
if [ "$(prompt "$P" "$REV")" = 409 ]; then res RECOVERY_R02 PASS "stale_revision_replay=409"; else res RECOVERY_R02 FAIL "stale_revision_replay_not_409"; fi
# R03 duplicate request (same Idempotency-Key, sequential) → same deployment; R04 same key in parallel → one deployment
REV=$(revision "$P"); KEY="c6-$(date +%s)"
pub() { curl -s -b "$J" -H "X-XSRF-TOKEN: $CSRF" -H 'Content-Type: application/json' -H "Idempotency-Key: $1" -X POST "$B/workspaces/$W/projects/$P/publish" -d "{\"visibility\":\"PRIVATE\",\"expectedRevision\":$REV}" | jget '["id"]' 2>/dev/null; }
A=$(pub "$KEY"); Bd=$(pub "$KEY")
if [ -n "$A" ] && [ "$A" = "$Bd" ]; then res RECOVERY_R03 PASS "duplicate_publish_same_key=same_deployment" "deployment=$A"; else res RECOVERY_R03 FAIL "ids=[$A]vs[$Bd]"; fi
IDS=$( { for i in 1 2 3 4 5; do pub "c6-par-$KEY" & done; wait; } | sort -u | grep -v '^$' | wc -l | tr -d ' ')
if [ "$IDS" = 1 ]; then res RECOVERY_R04 PASS "5_parallel_same_key=1_deployment"; else res RECOVERY_R04 FAIL "distinct_deployments=$IDS(expected 1)"; fi

dep_out() { # dep_out <compose service> <Rnn>
  local S="$1" R="$2" RC LC
  docker compose --profile "$PROFILE" stop "$S" >/dev/null 2>&1; sleep 6
  RC=$(code http://127.0.0.1:8080/actuator/health/readiness); LC=$(code http://127.0.0.1:8080/actuator/health/liveness)
  if [ "$RC" != 200 ]; then res "RECOVERY_${R}_NOTREADY" PASS "${S}_down=readiness_$RC"; else res "RECOVERY_${R}_NOTREADY" FAIL "${S}_down_but_readiness_still_200"; fi
  res "RECOVERY_${R}_LIVENESS" INFO "${S}_down=liveness_$LC"
  docker compose --profile "$PROFILE" start "$S" >/dev/null 2>&1
  if wait_ready 90; then
    res "RECOVERY_${R}_RECOVERED" PASS "${S}_back=readiness_200"
    if login && [ "$(code -b "$J" "$B/workspaces/$W/projects/$P")" = 200 ]; then res "RECOVERY_${R}_FUNCTIONAL" PASS "login_and_read_after_recovery"; else res "RECOVERY_${R}_FUNCTIONAL" FAIL "login_or_read_failed_after_${S}_recovery"; fi
  else res "RECOVERY_${R}_RECOVERED" FAIL "${S}_back_but_readiness_never_returned(180s)"; res "RECOVERY_${R}_FUNCTIONAL" NOT_RUN "reason=not_recovered"; fi
}
dep_out postgres R05; dep_out redis R06; dep_out rabbitmq R07

# R08 backend restart: stop API+UI via the repo's own script, start again, the project is still there and login works
./scripts/stop-local.sh > "$C6_EV/recovery-stop-local.log" 2>&1
sleep 3
./scripts/run-local.sh > "$C6_EV/recovery-run-local.log" 2>&1
if wait_ready 90; then
  if login && [ "$(code -b "$J" "$B/workspaces/$W/projects/$P")" = 200 ]; then res RECOVERY_R08 PASS "restart=ok" "project_survived=$P"; else res RECOVERY_R08 FAIL "after_restart_login_or_read_failed"; fi
else res RECOVERY_R08 FAIL "api_not_ready_after_restart" "log=docs/parallel/c6/evidence/mac/recovery-run-local.log"; fi

# R09-R11: not automatable through any public API today. GAP + owner, never PASS.
res RECOVERY_R09 GAP "workflow_interrupted:needs_C4_run_stores(V29,B-C0-W-01);LIVE_workflow_start_answers_503_RUNTIME_STORES_VOLATILE" "owner=C0/C4"
res RECOVERY_R10 GAP "ambiguous_mutation_timeout:production_connector_is_read_only(B-C0-W-04);semantics_only_in_DataRuntimeLiveApiTests" "owner=C3/C0"
res RECOVERY_R11 GAP "queue_replay_DLQ_with_real_broker:WorkflowQueue_not_wired(B-C4-06)" "owner=C4/C0"
n=$(grep -c '|FAIL|' "$C6_RESULTS"); echo "recovery FAIL count: $n"; [ "$n" = 0 ]
