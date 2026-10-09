#!/usr/bin/env bash
# C6 FULL Mac run — one command, every gate C6 can run, results in docs/parallel/c6/evidence/mac/RESULTS.txt. Never edits production code.
#
#   cd /Users/hoangluan/code/xweb-c6
#   export JAVA_HOME=$(/usr/libexec/java_home -v 21)
#   bash docs/parallel/c6/harness/mac-full.sh
#
# Options:  --allow-partial  keep going when a prerequisite is missing (affected phases are recorded NOT_RUN; FINAL can then only be INCOMPLETE)
#           --no-backend --no-stack --no-browser   skip a group (recorded NOT_RUN, so FINAL is INCOMPLETE)   --stop-stack  stop API/UI at the end
# Env:      RUN_SSO=1 (Keycloak + API restart + e2e/sso-flow)   RUN_PUBLIC=1 (e2e/public-flow; needs scripts/public-up.sh already running)
#           STACK_PROFILE=full|medium|lean (default full; code-flow and runtime-flow need full)   CHROME=/path/to/chrome   C6_EXPECT_BRANCH / C6_EXPECT_BASE
# Phase order (letters = the C6 batch spec): A env/HEAD · B backend · C/D/E frontend typecheck/unit+conformance/build · F static QA ·
#   H secret scan · I stack up · G smoke (needs the stack, so it runs right after I) · J E2E ×8 · K browser · L recovery · (SSO last).
# Exit: 0 FINAL PASS · 1 FINAL FAIL · 2 FINAL INCOMPLETE.   NOT_RUN is never turned into PASS.  FINAL PASS is not "production ready" (GAP lines stay listed).
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ALLOW_PARTIAL=0; NO_BACKEND=0; NO_STACK=0; NO_BROWSER=0; STOP_STACK=0
for a in "$@"; do case "$a" in --allow-partial) ALLOW_PARTIAL=1;; --no-backend) NO_BACKEND=1;; --no-stack) NO_STACK=1;; --no-browser) NO_BROWSER=1;; --stop-stack) STOP_STACK=1;; *) echo "unknown option $a" >&2; exit 64;; esac; done
export STACK_PROFILE="${STACK_PROFILE:-full}"
export C6_NEED_BACKEND=$((1 - NO_BACKEND)) C6_NEED_DOCKER=$(( (1 - NO_BACKEND) | (1 - NO_STACK) ))
STACK_OK=0; BACKEND_OK=0; RAN_FINAL=0

cleanup() {
  if [ "${STARTED_HTTP:-0}" = 1 ]; then c6_own_stop c6-http >/dev/null; fi
  if [ "${STARTED_NEXT:-0}" = 1 ]; then for n in c6-next-platform c6-next-admin c6-next-studio; do c6_own_stop $n >/dev/null; done; fi
  if [ $RAN_FINAL -ne 1 ] && [ -f "$C6_RESULTS" ]; then hdr "END|$(c6_now)"; hdr "FINAL|INCOMPLETE|scope=full|reason=aborted_before_finish(see_last_lines)"; fi
}
trap cleanup EXIT
notrun_all() { local reason="$1" k; shift; for k in "$@"; do res "$k" NOT_RUN "reason=$reason"; done; }

# ---------------- A. environment / HEAD / prerequisites (fail-fast)
phase_env; PRE=$?
res SCOPE INFO "full"
if [ $PRE -ne 0 ] && [ $ALLOW_PARTIAL -ne 1 ]; then
  notrun_all "prerequisite_missing(fail-fast; fix PREREQ line and re-run)" BACKEND_COMPILE BACKEND_TEST SECURITY_SUITES FRONTEND_TYPECHECK FRONTEND_UNIT FRONTEND_BUILD STATIC_QA SMOKE STACK_UP E2E_01 E2E_02 E2E_03 E2E_04 E2E_05 E2E_06 BROWSER RECOVERY
  RAN_FINAL=1; phase_finish full; rc=$?; echo "RESULTS: $C6_RESULTS"; exit $rc
fi

# ---------------- B. backend compile + test (+ Phase 3 security suites)
if [ $NO_BACKEND -eq 1 ]; then notrun_all "flag_--no-backend" BACKEND_COMPILE BACKEND_TEST SECURITY_SUITES
elif [ -z "$C6_JAVA_HOME" ] || [ $C6_DOCKER_OK -ne 1 ]; then notrun_all "jdk21_or_docker_missing" BACKEND_COMPILE BACKEND_TEST SECURITY_SUITES
else phase_backend; if [ "$(grep '^BACKEND_TEST|' "$C6_RESULTS" | tail -n 1 | cut -d'|' -f2)" = PASS ]; then BACKEND_OK=1; fi; fi

# ---------------- C/D/E frontend, F static QA
phase_frontend
phase_static

# ---------------- H. secret scan (optional tool)
if command -v gitleaks >/dev/null 2>&1; then
  c6_step SECRET_SCAN 600 bash scripts/secret-scan.sh; res SECRET_SCAN "$(c6_rc_status $STEP_RC)" "exit=$STEP_RC" "$(c6_logref)"
else res SECRET_SCAN NOT_RUN "reason=gitleaks_not_installed(brew install gitleaks)"; fi

# ---------------- I. real stack (never mocked)
stack_up() {
  if [ $NO_STACK -eq 1 ]; then notrun_all "flag_--no-stack" STACK_UP SMOKE; return 1; fi
  if [ $C6_DOCKER_OK -ne 1 ]; then notrun_all "docker_not_running" STACK_UP SMOKE; return 1; fi
  if [ $NO_BACKEND -eq 0 ] && [ $BACKEND_OK -ne 1 ]; then notrun_all "backend_gate_not_green(will_not_start_a_stack_on_a_red_backend)" STACK_UP SMOKE; return 1; fi
  if [ ! -f "$C6_ROOT/.env" ]; then
    if [ -f "$C6_ROOT/../HBL/.env" ]; then cp "$C6_ROOT/../HBL/.env" "$C6_ROOT/.env"; res STACK_ENV INFO "copied=../HBL/.env->.env(gitignored,local only)"
    else notrun_all "no_.env(cp .env.example .env and set LOCAL_ADMIN_PASSWORD>=14 chars)" STACK_UP SMOKE; return 1; fi
  fi
  c6_step STACK_UP 1800 ./scripts/run-local.sh
  local rc=$STEP_RC dur=$STEP_DUR ref; ref=$(c6_logref)
  local i rd=0 ui=0; for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do
    [ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health/readiness)" = 200 ] && { rd=1; break; }; sleep 4; done
  [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${FRONTEND_PORT:-3100}/")" = 200 ] && ui=1
  { docker compose --profile "$STACK_PROFILE" ps 2>&1; echo; tail -n 120 "$C6_ROOT/.run/backend.log" 2>/dev/null; } > "$C6_EV/stack-state.txt" 2>&1
  if [ $rc -eq 0 ] && [ $rd -eq 1 ] && [ $ui -eq 1 ]; then res STACK_UP PASS "profile=$STACK_PROFILE" "readiness=200" "ui=200" "duration=${dur}s" "$ref"; STACK_OK=1; return 0
  else res STACK_UP FAIL "profile=$STACK_PROFILE" "run_local_exit=$rc" "readiness_ok=$rd" "ui_ok=$ui" "state=docs/parallel/c6/evidence/mac/stack-state.txt" "$ref"; notrun_all "stack_up_failed" SMOKE; return 1; fi
}
stack_up
# ---------------- G. smoke
if [ $STACK_OK -eq 1 ]; then c6_step SMOKE 900 bash scripts/smoke-test.sh; res SMOKE "$(c6_rc_status $STEP_RC)" "exit=$STEP_RC" "$(c6_logref)" "last=$(c6_last "$STEP_LOG")"; fi

# ---------------- J. real E2E: every script in e2e/ is EXECUTED (or recorded NOT_RUN with the reason). E2E_01..08 = the 8 scripts.
e2e() { # e2e <ID> <script> <needs_stack 1|0>
  local id="$1" f="$2" need="$3"
  if [ "$need" = 1 ] && [ $STACK_OK -ne 1 ]; then res "$id" NOT_RUN "script=e2e/$f" "reason=stack_not_ready"; return; fi
  c6_step "$id" 2400 node "e2e/$f"
  res "$id" "$(c6_rc_status $STEP_RC)" "script=e2e/$f" "exit=$STEP_RC" "duration=${STEP_DUR}s" "$(c6_logref)" "last=$(c6_last "$STEP_LOG")"
}
e2e E2E_01 pages-mock.mjs 0
e2e E2E_02 factory-flow.mjs 1
e2e E2E_03 admin-setup-flow.mjs 1
e2e E2E_04 code-flow.mjs 1
e2e E2E_05 runtime-flow.mjs 1
e2e E2E_06 a11y.mjs 1
if [ "${RUN_PUBLIC:-0}" = 1 ]; then e2e E2E_08 public-flow.mjs 0; else res E2E_08 NOT_RUN "script=e2e/public-flow.mjs" "reason=set_RUN_PUBLIC=1(needs_scripts/public-up.sh_and_Cloudflare;not_a_local-stack_test)"; fi
res GAP_E2E_CHAIN GAP "login>tenant/workspace/project>Studio>data_source>query>mutation>action>workflow>publish>runtime:no_real_E2E_exists(tests/e2e-real_not_created;E-06..E-09_blocked_by_Q-2,B-C3-11,B-C4-09,B-C0-W-03)" "owner=C5/C0"

# ---------------- K. browser specs (real Chrome; the Builder spec is a component harness, NOT a backend E2E)
phase_browser() {
  if [ $NO_BROWSER -eq 1 ]; then notrun_all "flag_--no-browser" BROWSER_BUILDER BROWSER_PORTALS BROWSER; return; fi
  export CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
  if [ ! -x "$CHROME" ]; then notrun_all "no_chrome_at_'$CHROME'(set_CHROME=...)" BROWSER_BUILDER BROWSER_PORTALS BROWSER; return; fi
  if [ ! -d /tmp/esb/node_modules/esbuild ]; then
    c6_step BROWSER_ESBUILD 300 npm i --prefix /tmp/esb esbuild
    if [ $STEP_RC -ne 0 ]; then notrun_all "esbuild_install_failed(needs_npm_registry)" BROWSER_BUILDER; fi
  fi
  if [ -d /tmp/esb/node_modules/esbuild ]; then
    if [ -n "$(lsof -ti tcp:4000 -sTCP:LISTEN 2>/dev/null)" ]; then res BROWSER_BUILDER NOT_RUN "reason=port_4000_busy"
    else
      c6_step BROWSER_HARNESS_BUILD 300 node tests/browser/build-harness.mjs
      if [ $STEP_RC -ne 0 ]; then res BROWSER_BUILDER FAIL "reason=harness_build_failed" "$(c6_logref)"
      else
        STARTED_HTTP=1; c6_own_start c6-http "$C6_ROOT/.test-build/browser" "$C6_EV/BROWSER_HTTP.log" python3 -m http.server 4000 --bind 127.0.0.1; sleep 2
        c6_step BROWSER_BUILDER 1200 env "CHROME=$CHROME" node tests/browser/builder.spec.mjs
        res BROWSER_BUILDER "$(c6_rc_status $STEP_RC)" "spec=tests/browser/builder.spec.mjs(component_harness_not_backend_E2E)" "exit=$STEP_RC" "duration=${STEP_DUR}s" "$(c6_logref)" "last=$(c6_last "$STEP_LOG")"
        c6_own_stop c6-http >/dev/null; STARTED_HTTP=0
      fi
    fi
  fi
  local busy=""; for p in 3001 3002 3003; do [ -n "$(lsof -ti tcp:$p -sTCP:LISTEN 2>/dev/null)" ] && busy="$busy $p"; done
  if [ -n "$busy" ]; then res BROWSER_PORTALS NOT_RUN "reason=ports_busy:$(echo $busy | tr ' ' ',')"
  elif [ "$(grep '^FRONTEND_BUILD_APPS|' "$C6_RESULTS" | cut -d'|' -f2)" != PASS ]; then res BROWSER_PORTALS NOT_RUN "reason=apps_build_not_green"
  else
    STARTED_NEXT=1
    c6_own_start c6-next-platform "$C6_ROOT/apps/platform" "$C6_EV/BROWSER_platform.log" npx next start -H 127.0.0.1 -p 3001
    c6_own_start c6-next-admin    "$C6_ROOT/apps/admin"    "$C6_EV/BROWSER_admin.log"    npx next start -H 127.0.0.1 -p 3002
    c6_own_start c6-next-studio   "$C6_ROOT/apps/studio"   "$C6_EV/BROWSER_studio.log"   npx next start -H 127.0.0.1 -p 3003
    local i up=0; for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
      up=0; for p in 3001 3002 3003; do [ -n "$(lsof -ti tcp:$p -sTCP:LISTEN 2>/dev/null)" ] && up=$((up + 1)); done; [ $up -eq 3 ] && break; sleep 2; done
    if [ $up -ne 3 ]; then res BROWSER_PORTALS FAIL "reason=portals_did_not_start($up/3)" "logs=docs/parallel/c6/evidence/mac/BROWSER_*.log"
    else
      c6_step BROWSER_PORTALS 1200 env "CHROME=$CHROME" node tests/browser/portals.spec.mjs
      res BROWSER_PORTALS "$(c6_rc_status $STEP_RC)" "spec=tests/browser/portals.spec.mjs(no_backend)" "exit=$STEP_RC" "duration=${STEP_DUR}s" "$(c6_logref)" "last=$(c6_last "$STEP_LOG")"
    fi
    for n in c6-next-platform c6-next-admin c6-next-studio; do c6_own_stop $n >/dev/null; done; STARTED_NEXT=0
  fi
  local b pt; b=$(grep '^BROWSER_BUILDER|' "$C6_RESULTS" | tail -n 1 | cut -d'|' -f2); pt=$(grep '^BROWSER_PORTALS|' "$C6_RESULTS" | tail -n 1 | cut -d'|' -f2)
  if [ "$b" = FAIL ] || [ "$pt" = FAIL ]; then res BROWSER FAIL "builder=$b" "portals=$pt"
  elif [ "$b" = PASS ] && [ "$pt" = PASS ]; then res BROWSER PASS "builder=$b" "portals=$pt"
  else res BROWSER NOT_RUN "builder=${b:-?}" "portals=${pt:-?}"; fi
}
phase_browser

# ---------------- L. recovery / failure suite (needs the stack; restarts containers and the API)
if [ $STACK_OK -eq 1 ]; then
  c6_step RECOVERY_RUN 3600 bash docs/parallel/c6/harness/recovery.sh
  [ -f "$C6_EV/recovery.psv" ] && cat "$C6_EV/recovery.psv" >> "$C6_RESULTS"
  nf=$(grep -c '^RECOVERY_[A-Z0-9_]*|FAIL|' "$C6_EV/recovery.psv" 2>/dev/null)
  miss=""; for k in R01 R02 R03 R04 R05_RECOVERED R06_RECOVERED R07_RECOVERED R08; do grep -q "^RECOVERY_$k|PASS|" "$C6_EV/recovery.psv" 2>/dev/null || miss="$miss $k"; done
  if [ "${nf:-0}" -gt 0 ]; then res RECOVERY FAIL "failed_cases=$nf" "detail=docs/parallel/c6/evidence/mac/recovery.psv"
  elif [ -n "$miss" ]; then res RECOVERY NOT_RUN "reason=cases_not_passed_or_not_run:$(echo $miss | tr ' ' ',')" "exit=$STEP_RC" "$(c6_logref)"
  else res RECOVERY PASS "automatable_cases=R01-R08" "gaps=R09,R10,R11"; fi
else res RECOVERY NOT_RUN "reason=stack_not_ready"; fi

# ---------------- optional SSO (last: it restarts the API with OIDC settings)
if [ "${RUN_SSO:-0}" = 1 ] && [ $STACK_OK -eq 1 ]; then
  c6_step E2E_07_SSO_UP 900 bash scripts/sso-up.sh
  if [ $STEP_RC -ne 0 ]; then res E2E_07 FAIL "reason=sso-up.sh_failed" "$(c6_logref)"
  else
    ./scripts/stop-local.sh > "$C6_EV/E2E_07_stop.log" 2>&1; sleep 3; c6_step E2E_07_RESTART 1800 ./scripts/run-local.sh
    e2e E2E_07 sso-flow.mjs 1
  fi
else res E2E_07 NOT_RUN "script=e2e/sso-flow.mjs" "reason=set_RUN_SSO=1(starts_Keycloak_and_restarts_the_API)"; fi

# ---------------- known gaps (listed, never hidden)
res GAP_C6_05 GAP "no_test_named_for:user_with_a_role_but_without_QUERY_EXECUTE_refused_on_the_LIVE_query_route" "owner=C1/C3"
res GAP_C6_06 GAP "WORKFLOW_EXECUTE_denial_tested_at_logic/adapter_level_only,_not_over_HTTP" "owner=C1/C4"
res GAP_V29 GAP "run_stores_volatile:LIVE_mutating_actions_and_workflow_starts_answer_503_unless_allow-volatile-stores" "owner=C0/C4"
res GAP_MGMT_API GAP "no_management_API_for_data_sources_credentials_queries(B-C0-W-03)" "owner=C0/C3"

[ $STOP_STACK -eq 1 ] && ./scripts/stop-local.sh > "$C6_EV/stop-stack.log" 2>&1
RAN_FINAL=1; phase_finish full; rc=$?
echo; echo "RESULTS:  $C6_RESULTS"; echo "STEPS:    $C6_EV/STEPS.psv"; echo "Send back: docs/parallel/c6/evidence/mac/  (whole folder)"; exit $rc
