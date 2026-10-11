#!/usr/bin/env bash
# C6 harness library — sourced by mac-full.sh and mac-gate.sh (and selftest.sh). Never edits production code.
# Written to run on macOS /bin/bash 3.2: no associative arrays, no mapfile, no ${var,,}, no `timeout`, no GNU-only flags. No `set -e/-u` here:
# every command's exit code is captured and RECORDED, never silently turned into success.
# Result line format (machine-readable, one per line):   KEY|STATUS|field|field|…|at=<UTC>      STATUS ∈ PASS FAIL NOT_RUN WARN INFO GAP
# Header lines have no status:  START|<ts>  HEAD|<sha>  BRANCH|<name> …

C6_HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C6_ROOT="${C6_ROOT:-$(cd "$C6_HARNESS_DIR/../../../.." && pwd)}"
C6_EV="${C6_EV:-$C6_ROOT/docs/parallel/c6/evidence/mac}"
C6_RESULTS="$C6_EV/RESULTS.txt"
C6_EXPECT_BRANCH="${C6_EXPECT_BRANCH:-agent/c6-qa}"
C6_EXPECT_BASE="${C6_EXPECT_BASE:-f894cc6}"
C6_PY="python3"
# Kotlin compile daemon OOM'd on a 16 GB Mac under load with the default heap (BUG-C6-006); passed on the command line, repo files untouched.
C6_GRADLE_ARGS="${C6_GRADLE_ARGS:--Pkotlin.daemon.jvmargs=-Xmx3g}"
export PYTHONDONTWRITEBYTECODE=1                       # never leave __pycache__ in the repo

c6_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
c6_san() { tr '|\r\n' '/  ' | cut -c1-240; }                       # make arbitrary text safe for one result field
c6_last() { grep -v '^[[:space:]]*$' "$1" 2>/dev/null | tail -n 1 | c6_san; }
c6_init() { mkdir -p "$C6_EV"; : > "$C6_RESULTS"; : > "$C6_EV/STEPS.psv"; }
hdr() { printf '%s\n' "$*" >> "$C6_RESULTS"; printf '%s\n' "$*"; }  # hdr KEY|value
# res KEY STATUS [field…]   → KEY|STATUS|field|…|at=ts
res() { local k="$1" s="$2"; shift 2; local line="$k|$s"; local f; for f in "$@"; do line="$line|$f"; done; line="$line|at=$(c6_now)"; printf '%s\n' "$line" >> "$C6_RESULTS"; printf '%s\n' "$line"; }

# c6_step KEY SECONDS cmd…   run from repo root with a timeout; sets STEP_RC STEP_DUR STEP_LOG; appends a row to STEPS.psv (exit code, start, end, duration, log)
c6_step() {
  local key="$1" secs="$2"; shift 2
  STEP_LOG="$C6_EV/$key.log"; local s e ts; s=$(date +%s); ts=$(c6_now)
  { echo "# start: $ts"; echo "# cwd:   $C6_ROOT"; echo "# cmd:   $*"; echo; } > "$STEP_LOG"
  ( cd "$C6_ROOT" && "$C6_PY" "$C6_HARNESS_DIR/tmo.py" "$secs" "$@" ) >> "$STEP_LOG" 2>&1
  STEP_RC=$?; e=$(date +%s); STEP_DUR=$((e - s))
  echo "$key|exit=$STEP_RC|start=$ts|end=$(c6_now)|duration=${STEP_DUR}s|log=${STEP_LOG#$C6_ROOT/}" >> "$C6_EV/STEPS.psv"
}
c6_rc_status() { if [ "$1" -eq 0 ]; then echo PASS; else echo FAIL; fi; }
c6_logref() { echo "log=${STEP_LOG#$C6_ROOT/}"; }
c6_num() { echo "$1" | sed -E "s/.*$2=([0-9]+).*/\1/"; }          # c6_num "a=1 b=2" b -> 2

c6_find_java21() {
  local h
  h=$(/usr/libexec/java_home -v 21 2>/dev/null) && [ -x "$h/bin/java" ] && { echo "$h"; return 0; }
  for h in /opt/homebrew/opt/openjdk@21 /usr/local/opt/openjdk@21 "${JAVA_HOME:-}"; do
    [ -n "$h" ] && [ -x "$h/bin/java" ] && "$h/bin/java" -version 2>&1 | grep -q '"21\.' && { echo "$h"; return 0; }
  done
  return 1
}

# ---- worktree scope: anything changed outside docs/parallel/c6/ (tracked or untracked, not ignored)
c6_scope_snapshot() { git -C "$C6_ROOT" status --porcelain 2>/dev/null | grep -v 'docs/parallel/c6/' | sort; }

# ---- A. environment / HEAD / prerequisites.  Sets C6_JAVA_HOME, C6_DOCKER_OK. Returns 0 if all critical prerequisites exist.
#      env flags: C6_NEED_BACKEND=1 (JDK 21)  C6_NEED_DOCKER=1 (daemon running; Testcontainers + stack need it)
phase_env() {
  c6_init
  hdr "START|$(c6_now)"
  hdr "HOST|$(uname -sm | c6_san)|$( (sw_vers -productVersion 2>/dev/null || echo n/a) | c6_san)"
  local head br
  head=$(git -C "$C6_ROOT" rev-parse --short HEAD 2>/dev/null); br=$(git -C "$C6_ROOT" branch --show-current 2>/dev/null)
  hdr "HEAD|${head:-UNKNOWN}"; hdr "BRANCH|${br:-UNKNOWN}"

  local missing=""
  [ -n "$head" ] || missing="$missing git-head(unreadable repo or worktree)"
  command -v node >/dev/null 2>&1 || missing="$missing node"
  if command -v node >/dev/null 2>&1; then
    local nmaj; nmaj=$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null); [ "${nmaj:-0}" -ge 22 ] 2>/dev/null || missing="$missing node>=22"
  fi
  command -v npm >/dev/null 2>&1 || missing="$missing npm"
  command -v python3 >/dev/null 2>&1 || missing="$missing python3"
  command -v curl >/dev/null 2>&1 || missing="$missing curl"
  C6_JAVA_HOME=""
  if [ "${C6_NEED_BACKEND:-1}" = 1 ]; then
    C6_JAVA_HOME=$(c6_find_java21) || { missing="$missing jdk21"; C6_JAVA_HOME=""; }
    if [ -n "$C6_JAVA_HOME" ]; then export JAVA_HOME="$C6_JAVA_HOME"; export PATH="$JAVA_HOME/bin:$PATH"; fi
  fi
  C6_DOCKER_OK=0
  if docker info >/dev/null 2>&1; then C6_DOCKER_OK=1; fi
  if [ "${C6_NEED_DOCKER:-1}" = 1 ] && [ $C6_DOCKER_OK -ne 1 ]; then missing="$missing docker(daemon not running)"; fi

  [ -n "${C6_SELFTEST_FORCE_MISSING:-}" ] && missing="$missing selftest-forced"   # test hook for selftest.sh (fail-fast path); unused otherwise
  {
    echo "# environment — $(c6_now)"; echo "## git"; git -C "$C6_ROOT" rev-parse HEAD 2>&1; git -C "$C6_ROOT" branch --show-current 2>&1
    git -C "$C6_ROOT" log -1 --oneline 2>&1; echo "## git status --short"; git -C "$C6_ROOT" status --short 2>&1
    echo "## uname"; uname -a; echo "## sw_vers"; sw_vers 2>&1
    echo "## node / npm / python3"; node -v 2>&1; npm -v 2>&1; python3 --version 2>&1
    echo "## java (selected: ${C6_JAVA_HOME:-none})"; if [ -n "$C6_JAVA_HOME" ]; then "$C6_JAVA_HOME/bin/java" -version 2>&1; else echo "no JDK 21 selected"; fi
    echo "## java_home -V"; /usr/libexec/java_home -V 2>&1
    echo "## docker"; docker version 2>&1 | head -30; docker compose version 2>&1; docker info 2>&1 | head -12
  } > "$C6_EV/ENV.txt" 2>&1

  if [ -n "$C6_JAVA_HOME" ]; then res JAVA INFO "home=$C6_JAVA_HOME" "version=$("$C6_JAVA_HOME/bin/java" -version 2>&1 | head -1 | c6_san)"; else res JAVA NOT_RUN "reason=no_jdk21_found_or_not_needed"; fi
  if [ $C6_DOCKER_OK -eq 1 ]; then res DOCKER INFO "client=$(docker version --format '{{.Client.Version}}' 2>/dev/null | c6_san)" "server=$(docker version --format '{{.Server.Version}}' 2>/dev/null | c6_san)" "compose=$(docker compose version --short 2>/dev/null | c6_san)"; else res DOCKER NOT_RUN "reason=daemon_not_running_or_missing"; fi

  # branch / baseline checks (WARN only; nothing is ever reset)
  if [ "$br" = "$C6_EXPECT_BRANCH" ]; then res BRANCH_CHECK PASS "expected=$C6_EXPECT_BRANCH"; else res BRANCH_CHECK WARN "expected=$C6_EXPECT_BRANCH" "actual=${br:-UNKNOWN}" "action=testing_actual_HEAD_nothing_reset"; fi
  if git -C "$C6_ROOT" rev-parse --verify -q "$C6_EXPECT_BASE^{commit}" >/dev/null 2>&1; then
    local full_base mb; full_base=$(git -C "$C6_ROOT" rev-parse "$C6_EXPECT_BASE^{commit}"); mb=$(git -C "$C6_ROOT" merge-base HEAD "$C6_EXPECT_BASE" 2>/dev/null)
    if [ "$mb" = "$full_base" ]; then
      res BASELINE PASS "base=$C6_EXPECT_BASE" "head_descends_from_base=yes" "commits_ahead=$(git -C "$C6_ROOT" rev-list --count "$C6_EXPECT_BASE..HEAD" 2>/dev/null)"
    else res BASELINE WARN "base=$C6_EXPECT_BASE" "head_descends_from_base=no" "merge_base=$(echo "$mb" | cut -c1-7)"; fi
    { echo "# files changed vs $C6_EXPECT_BASE (outside docs/parallel/c6/)"; git -C "$C6_ROOT" diff --name-status "$C6_EXPECT_BASE" 2>&1 | grep -v 'docs/parallel/c6/'; } > "$C6_EV/diff-vs-baseline.txt"
  else res BASELINE WARN "base=$C6_EXPECT_BASE" "reason=commit_not_found_in_this_clone"; fi
  c6_scope_snapshot > "$C6_EV/scope-start.txt"
  local nstart; nstart=$(grep -c . "$C6_EV/scope-start.txt")
  if [ "$nstart" -eq 0 ]; then res WORKTREE_START PASS "dirty_outside_c6=0"; else res WORKTREE_START WARN "dirty_outside_c6=$nstart" "file=${C6_EV#$C6_ROOT/}/scope-start.txt"; fi

  if [ -n "$missing" ]; then res PREREQ FAIL "missing=$(echo "$missing" | sed 's/^ //; s/ /,/g' | c6_san)"; return 1; fi
  res PREREQ PASS "node=$(node -v)" "head=$head"; return 0
}

# ---- B + Phase 3. backend compile + test + JUnit counts + per-suite security evidence
phase_backend() {
  c6_step BACKEND_COMPILE 1800 bash -c "cd backend && ./gradlew compileKotlin compileTestKotlin --no-daemon --console=plain $C6_GRADLE_ARGS"
  res BACKEND_COMPILE "$(c6_rc_status $STEP_RC)" "exit=$STEP_RC" "duration=${STEP_DUR}s" "$(c6_logref)"
  if [ $STEP_RC -ne 0 ]; then res BACKEND_TEST NOT_RUN "reason=compile_failed"; res SECURITY_SUITES NOT_RUN "reason=compile_failed"; return 1; fi
  # --no-build-cache + --rerun-tasks: Gradle must EXECUTE the tests. A ':test FROM-CACHE' result is a replay of an older run, not evidence (BUG-C6-005).
  c6_step BACKEND_TEST 5400 bash -c "cd backend && ./gradlew clean test --no-daemon --no-build-cache --rerun-tasks --console=plain $C6_GRADLE_ARGS"
  local rc=$STEP_RC dur=$STEP_DUR jl; jl=$(c6_logref)
  if grep -qE '^> Task :test (FROM-CACHE|UP-TO-DATE|NO-SOURCE)' "$STEP_LOG"; then
    res BACKEND_TEST FAIL "exit=$rc" "reason=tests_not_executed(:test_was_served_from_cache_or_up-to-date)" "$jl"; res SECURITY_SUITES NOT_RUN "reason=tests_not_executed"; return 1
  fi
  local counts; counts=$("$C6_PY" "$C6_HARNESS_DIR/parse.py" junit "$C6_ROOT/backend/build/test-results/test" "$C6_EV" 2>/dev/null)
  if [ -z "$counts" ]; then res BACKEND_TEST FAIL "exit=$rc" "duration=${dur}s" "reason=no_junit_results" "$jl"; res SECURITY_SUITES NOT_RUN "reason=no_junit_results"; return 1; fi
  local t f e s; t=$(c6_num "$counts" tests); f=$(c6_num "$counts" failures); e=$(c6_num "$counts" errors); s=$(c6_num "$counts" skipped)
  local st=PASS; if [ "$rc" -ne 0 ] || [ "$t" -eq 0 ] || [ $((f + e)) -ne 0 ]; then st=FAIL; fi
  res BACKEND_TEST "$st" "tests=$t" "passed=$((t - f - e - s))" "failed=$((f + e))" "skipped=$s" "exit=$rc" "duration=${dur}s" "skipped_list=${C6_EV#$C6_ROOT/}/backend-skipped.txt" "failures=${C6_EV#$C6_ROOT/}/backend-failures.txt" "$jl"
  if [ -d "$C6_ROOT/backend/build/reports/tests" ]; then tar -czf "$C6_EV/backend-test-report.tgz" -C "$C6_ROOT/backend/build/reports" tests 2>/dev/null; fi
  # Phase 3 — security / isolation suites, each with its own evidence line
  local sec; sec=$("$C6_PY" "$C6_HARNESS_DIR/parse.py" security "$C6_ROOT/backend/build/test-results/test" 2>/dev/null)
  if [ -z "$sec" ]; then res SECURITY_SUITES NOT_RUN "reason=no_junit_results"; return 0; fi
  echo "$sec" > "$C6_EV/security-suites.txt"
  local any_fail=0 any_missing=0 line k stt tcount
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    k=$(echo "$line" | cut -d'|' -f2); stt=$(echo "$line" | awk -F'|' '{print $NF}')
    case "$stt" in
      PASS)  res "SEC_$k" PASS "$(echo "$line" | cut -d'|' -f3)" "$(echo "$line" | cut -d'|' -f4)" "$(echo "$line" | cut -d'|' -f5)";;
      FAIL)  any_fail=1;    res "SEC_$k" FAIL "$(echo "$line" | cut -d'|' -f3)" "$(echo "$line" | cut -d'|' -f4)" "$(echo "$line" | cut -d'|' -f5)";;
      *)     any_missing=1; res "SEC_$k" NOT_RUN "reason=suite_missing_or_empty";;
    esac
  done < "$C6_EV/security-suites.txt"
  tcount=$(grep -c . "$C6_EV/security-suites.txt")
  if [ $any_fail -eq 1 ]; then res SECURITY_SUITES FAIL "suites=$tcount" "detail=${C6_EV#$C6_ROOT/}/security-suites.txt"
  elif [ $any_missing -eq 1 ]; then res SECURITY_SUITES NOT_RUN "reason=some_suites_missing_or_empty" "detail=${C6_EV#$C6_ROOT/}/security-suites.txt"
  else res SECURITY_SUITES PASS "suites=$tcount"; fi
  return 0
}

# ---- C/D/E. frontend: install (if needed), typecheck, unit + conformance, builds
phase_frontend() {
  if [ ! -d "$C6_ROOT/node_modules" ]; then
    c6_step FRONTEND_INSTALL 900 npm ci
    res FRONTEND_INSTALL "$(c6_rc_status $STEP_RC)" "exit=$STEP_RC" "duration=${STEP_DUR}s" "$(c6_logref)"
    if [ $STEP_RC -ne 0 ]; then res FRONTEND_TYPECHECK NOT_RUN "reason=npm_ci_failed"; res FRONTEND_UNIT NOT_RUN "reason=npm_ci_failed"; res FRONTEND_BUILD NOT_RUN "reason=npm_ci_failed"; return 1; fi
  else res FRONTEND_INSTALL INFO "node_modules=present(not_reinstalled)"; fi
  local ok=1
  c6_step FRONTEND_TYPECHECK_ROOT 900 npx tsc --noEmit -p tsconfig.json; res FRONTEND_TYPECHECK_ROOT "$(c6_rc_status $STEP_RC)" "exit=$STEP_RC" "$(c6_logref)"; [ $STEP_RC -ne 0 ] && ok=0
  c6_step FRONTEND_TYPECHECK_ALL 900 npm run typecheck:all;              res FRONTEND_TYPECHECK_ALL "$(c6_rc_status $STEP_RC)" "exit=$STEP_RC" "$(c6_logref)"; [ $STEP_RC -ne 0 ] && ok=0
  c6_step FRONTEND_TYPECHECK_APPS 900 npm run typecheck:apps;            res FRONTEND_TYPECHECK_APPS "$(c6_rc_status $STEP_RC)" "exit=$STEP_RC" "$(c6_logref)"; [ $STEP_RC -ne 0 ] && ok=0
  if [ $ok -eq 1 ]; then res FRONTEND_TYPECHECK PASS "root=ok" "packages=ok" "apps=ok"; else res FRONTEND_TYPECHECK FAIL "see=FRONTEND_TYPECHECK_*"; fi
  # unit + conformance (env var set -> conformance must NOT skip)
  c6_step FRONTEND_UNIT 900 env "XWEB_CONFORMANCE_DIR=$C6_ROOT/backend/src/test/resources/app-definition" npm run test:unit
  local p; p=$("$C6_PY" "$C6_HARNESS_DIR/parse.py" node-test "$STEP_LOG" 2>/dev/null)
  if [ -z "$p" ]; then res FRONTEND_UNIT FAIL "exit=$STEP_RC" "reason=unparsable_log" "$(c6_logref)"
  else
    local tt pp ff ss; tt=$(c6_num "$p" tests); pp=$(c6_num "$p" pass); ff=$(c6_num "$p" fail); ss=$(c6_num "$p" skipped)
    local st=PASS; if [ $STEP_RC -ne 0 ] || [ "$ff" -ne 0 ] || [ "$ss" -ne 0 ]; then st=FAIL; fi
    res FRONTEND_UNIT "$st" "tests=$tt" "passed=$pp" "failed=$ff" "skipped=$ss" "exit=$STEP_RC" "conformance_env=set" "$(c6_logref)"
  fi
  # default command, no env var: revalidates BUG-C6-004 (informational — never part of FINAL)
  c6_step FRONTEND_UNIT_DEFAULT 900 npm run test:unit
  p=$("$C6_PY" "$C6_HARNESS_DIR/parse.py" node-test "$STEP_LOG" 2>/dev/null)
  res BUG_C6_004 INFO "default_npm_test_unit=${p:-unparsable}" "rule=closed_if_skipped_is_0"
  # builds
  ok=1
  c6_step FRONTEND_BUILD_APPS 1800 npm run build:apps; res FRONTEND_BUILD_APPS "$(c6_rc_status $STEP_RC)" "exit=$STEP_RC" "duration=${STEP_DUR}s" "$(c6_logref)"; [ $STEP_RC -ne 0 ] && ok=0
  c6_step FRONTEND_BUILD_ROOT 1800 npm run build;      res FRONTEND_BUILD_ROOT "$(c6_rc_status $STEP_RC)" "exit=$STEP_RC" "duration=${STEP_DUR}s" "$(c6_logref)"; [ $STEP_RC -ne 0 ] && ok=0
  if [ $ok -eq 1 ]; then res FRONTEND_BUILD PASS "apps=ok" "root=ok"; else res FRONTEND_BUILD FAIL "see=FRONTEND_BUILD_*"; fi
  return 0
}

# ---- F. static QA
phase_static() {
  c6_step STATIC_QA 300 node docs/parallel/c6/harness/static-qa.mjs
  local p pass total; p=$("$C6_PY" "$C6_HARNESS_DIR/parse.py" static-qa "$STEP_LOG" 2>/dev/null)
  pass=$(c6_num "$p" pass); total=$(c6_num "$p" total)
  if [ -n "$p" ] && [ $STEP_RC -eq 0 ] && [ "$pass" = "$total" ]; then res STATIC_QA PASS "$pass/$total" "$(c6_logref)"; else res STATIC_QA FAIL "${pass:-?}/${total:-?}" "exit=$STEP_RC" "$(c6_logref)"; fi
}

# ---- end: scope check (nothing outside docs/parallel/c6 may change) + FINAL.  Return: 0 PASS, 1 FAIL, 2 INCOMPLETE
phase_finish() { # phase_finish <full|gate>
  c6_scope_snapshot > "$C6_EV/scope-end.txt"
  local added; added=$(comm -13 "$C6_EV/scope-start.txt" "$C6_EV/scope-end.txt")
  if [ -z "$added" ]; then res WORKTREE_SCOPE PASS "new_changes_outside_c6=0"; else res WORKTREE_SCOPE FAIL "new_changes_outside_c6=$(echo "$added" | grep -c .)" "file=${C6_EV#$C6_ROOT/}/scope-end.txt"; fi
  hdr "END|$(c6_now)"
  local f; f=$("$C6_PY" "$C6_HARNESS_DIR/final.py" "$C6_RESULTS" "$1"); hdr "$f"
  case "$f" in FINAL\|PASS*) return 0;; FINAL\|FAIL*) return 1;; *) return 2;; esac
}

# ---------------- owned-process helpers (CLAUDE.md "máy dùng chung": no kill by name or by port) ----------------
# c6_own_start NAME DIR LOG CMD ARGS…  starts CMD in its OWN session/process group (pgid == pid) and records pid, start time and command line in
#   $C6_OWN_DIR/NAME.state. c6_own_stop NAME signals that process group ONLY if the recorded pid still has the recorded start time, command and
#   pgid == pid. Anything else (pid gone, reused, someone else's process) => REFUSED / ALREADY_GONE and NOTHING is signalled. Signals only a
#   recorded process group; nothing is ever looked up by name or by port, so a foreign listener on the same port is never touched.
C6_OWN_DIR="${C6_OWN_DIR:-${TMPDIR:-/tmp}/c6-owned-$(id -u)}"
c6_ps_lstart() { ps -o lstart= -p "$1" 2>/dev/null | sed 's/^ *//; s/ *$//'; }
c6_ps_cmd()    { ps -ww -o command= -p "$1" 2>/dev/null | sed 's/^ *//; s/ *$//'; }
c6_ps_pgid()   { ps -o pgid= -p "$1" 2>/dev/null | tr -d ' '; }
c6_own_start() {
  local name="$1" dir="$2" log="$3"; shift 3; mkdir -p "$C6_OWN_DIR"
  local sf="$C6_OWN_DIR/$name.state"
  if [ -f "$sf" ]; then c6_own_status "$name" >/dev/null 2>&1 && { echo "c6_own_start: $name already running (pid $(sed -n 1p "$sf"))" >&2; return 3; }; rm -f "$sf"; fi
  local pid
  # python3 setsid+exec: the child becomes a session/group leader (pgid == pid) and is replaced by CMD, so $! is the real process.
  pid=$(cd "$dir" || exit 1; python3 -c 'import os,sys; os.setsid(); os.execvp(sys.argv[1], sys.argv[1:])' "$@" > "$log" 2>&1 < /dev/null & echo $!) || return 1
  [ -n "$pid" ] || return 1
  sleep 0.3
  local ls cm; ls=$(c6_ps_lstart "$pid"); cm=$(c6_ps_cmd "$pid")
  if [ -z "$ls" ]; then echo "c6_own_start: $name exited immediately (see $log)" >&2; return 1; fi
  printf '%s\n%s\n%s\n' "$pid" "$ls" "$cm" > "$sf"; return 0
}
c6_own_status() {   # 0 OWNED+running · 1 GONE/no state · 2 REUSED (pid is someone else's)
  local sf="$C6_OWN_DIR/$1.state"; [ -f "$sf" ] || return 1
  local pid ls cm; pid=$(sed -n 1p "$sf"); ls=$(sed -n 2p "$sf"); cm=$(sed -n 3p "$sf")
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  [ "$(c6_ps_lstart "$pid")" = "$ls" ] && [ "$(c6_ps_cmd "$pid")" = "$cm" ] && [ "$(c6_ps_pgid "$pid")" = "$pid" ] && return 0
  return 2
}
c6_own_stop() {     # prints STOPPED | ALREADY_GONE | REFUSED ; returns 0 for the first two, 2 for REFUSED (state file kept, nothing signalled)
  local name="$1" sf="$C6_OWN_DIR/$1.state"; [ -f "$sf" ] || { echo ALREADY_GONE; return 0; }
  local pid; pid=$(sed -n 1p "$sf"); c6_own_status "$name"; local st=$?
  if [ $st -eq 1 ]; then rm -f "$sf"; echo ALREADY_GONE; return 0; fi
  if [ $st -ne 0 ]; then echo "REFUSED(pid $pid is not the recorded process)"; return 2; fi
  kill -TERM -- "-$pid" 2>/dev/null; local i; for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16; do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
  if kill -0 "$pid" 2>/dev/null && c6_own_status "$name"; then kill -KILL -- "-$pid" 2>/dev/null; sleep 0.5; fi
  rm -f "$sf"; echo STOPPED; return 0
}
