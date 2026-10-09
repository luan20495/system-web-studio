#!/usr/bin/env bash
# C6 harness SELF-TEST — checks the runner's own logic (parsers, FINAL rules, timeout, step recording, fail-fast). Safe anywhere (VM or Mac):
# it never starts Gradle, Docker, the stack, E2E or a browser. Run before mac-full.sh:   bash docs/parallel/c6/harness/selftest.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT; PASS=0; FAILN=0
ok() { PASS=$((PASS+1)); echo "ok   $1"; }
bad() { FAILN=$((FAILN+1)); echo "FAIL $1 :: $2"; }
eq() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected [$3] got [$2]"; fi; }
H="$C6_HARNESS_DIR"; EVB="$C6_ROOT/docs/parallel/c6/evidence"

# 1 syntax
for f in "$H"/*.sh; do bash -n "$f" 2>/dev/null && ok "bash -n $(basename "$f")" || bad "bash -n $(basename "$f")" "syntax"; done
python3 -B -c 'import ast,sys; [ast.parse(open(f,encoding="utf-8").read(), f) for f in sys.argv[1:]]' "$H/tmo.py" "$H/parse.py" "$H/final.py" 2>/dev/null && ok "python syntax" || bad "python syntax" "error"

# 2 parsers against REAL batch-1 evidence (same numbers QA_STATUS reports)
if [ -f "$EVB/unit-with-conformance.log" ]; then eq "parse node-test conformance log" "$(python3 "$H/parse.py" node-test "$EVB/unit-with-conformance.log")" "tests=130 pass=130 fail=0 skipped=0"; fi
if [ -f "$EVB/unit-default.log" ]; then eq "parse node-test default log" "$(python3 "$H/parse.py" node-test "$EVB/unit-default.log")" "tests=115 pass=114 fail=0 skipped=1"; fi
if [ -f "$EVB/static-qa.log" ]; then eq "parse static-qa log" "$(python3 "$H/parse.py" static-qa "$EVB/static-qa.log")" "pass=10 total=10"; fi
python3 "$H/parse.py" node-test "$T/missing.log" >/dev/null 2>&1; eq "node-test missing file -> exit 3" "$?" "3"

# 3 JUnit parser with synthetic XML (one failing, one skipped, one security suite)
mkdir -p "$T/junit"
cat > "$T/junit/TEST-a.IsolationApiTests.xml" <<'X'
<testsuite name="com.x.IsolationApiTests" tests="3" failures="1" errors="0" skipped="1"><testcase name="t1" classname="a"/><testcase name="t2" classname="a"><failure message="boom">stack line 1
stack line 2</failure></testcase><testcase name="t3" classname="a"><skipped/></testcase></testsuite>
X
cat > "$T/junit/TEST-b.TenantAccessTests.xml" <<'X'
<testsuite name="com.x.TenantAccessTests" tests="2" failures="0" errors="0" skipped="0"><testcase name="u1" classname="b"/><testcase name="u2" classname="b"/></testsuite>
X
eq "junit totals" "$(python3 "$H/parse.py" junit "$T/junit" "$T/out")" "suites=2 tests=5 failures=1 errors=0 skipped=1"
grep -q "IsolationApiTests :: t2" "$T/out/backend-failures.txt" && grep -q "boom" "$T/out/backend-failures.txt" && ok "junit failure detail written" || bad "junit failure detail" "missing"
grep -q "IsolationApiTests :: t3" "$T/out/backend-skipped.txt" && ok "junit skipped list written" || bad "junit skipped list" "missing"
python3 "$H/parse.py" security "$T/junit" > "$T/sec.txt"
grep -q '^SEC|IsolationApiTests|tests=3|fail=1|skipped=1|FAIL$' "$T/sec.txt" && ok "security: failing suite -> FAIL" || bad "security FAIL" "$(grep Isolation "$T/sec.txt")"
grep -q '^SEC|TenantAccessTests|tests=2|fail=0|skipped=0|PASS$' "$T/sec.txt" && ok "security: green suite -> PASS" || bad "security PASS" "?"
grep -q '^SEC|PrivilegeEscalationTests|.*|MISSING$' "$T/sec.txt" && ok "security: absent suite -> MISSING (never PASS)" || bad "security MISSING" "?"
python3 "$H/parse.py" junit "$T/nodir" "$T/o2" >/dev/null 2>&1; eq "junit no results -> exit 3" "$?" "3"

# 4 timeout wrapper + exit code pass-through
python3 "$H/tmo.py" 1 sleep 5 >/dev/null 2>&1; eq "tmo: timeout -> 124" "$?" "124"
python3 "$H/tmo.py" 5 bash -c 'exit 7' >/dev/null 2>&1; eq "tmo: exit code preserved" "$?" "7"
python3 "$H/tmo.py" 5 true; eq "tmo: success -> 0" "$?" "0"

# 5 step recording (exit code, timestamps, log)
export C6_EV="$T/ev"; C6_RESULTS="$C6_EV/RESULTS.txt"; c6_init
c6_step ST_OK 10 true; eq "step rc ok" "$STEP_RC" "0"
c6_step ST_BAD 10 bash -c 'echo out; exit 3'; eq "step rc bad" "$STEP_RC" "3"
grep -q '^ST_BAD|exit=3|start=.*|end=.*|duration=' "$C6_EV/STEPS.psv" && ok "STEPS.psv has exit/start/end/duration" || bad "STEPS.psv" "$(cat "$C6_EV/STEPS.psv")"
grep -q "echo out" "$C6_EV/ST_BAD.log" && grep -q "^out$" "$C6_EV/ST_BAD.log" && ok "step log keeps command + output" || bad "step log" "?"
res X_KEY FAIL "a|b" >/dev/null; eq "res line shape" "$(tail -n1 "$C6_RESULTS" | cut -d'|' -f1-4)" "X_KEY|FAIL|a|b"
eq "san strips pipes/newlines" "$(printf 'a|b\nc' | c6_san)" "a/b c"

# 6 FINAL rules
mk() { : > "$T/R.txt"; echo "HEAD|abc1234" >> "$T/R.txt"; for k in PREREQ WORKTREE_SCOPE BACKEND_COMPILE BACKEND_TEST SECURITY_SUITES FRONTEND_TYPECHECK FRONTEND_UNIT FRONTEND_BUILD STATIC_QA SMOKE STACK_UP E2E_01 E2E_02 E2E_03 E2E_04 E2E_05 E2E_06 BROWSER RECOVERY; do echo "$k|PASS|x" >> "$T/R.txt"; done; echo "E2E_07|NOT_RUN|reason=optional" >> "$T/R.txt"; echo "E2E_08|NOT_RUN|reason=optional" >> "$T/R.txt"; echo "GAP_X|GAP|y" >> "$T/R.txt"; }
fin() { python3 "$H/final.py" "$T/R.txt" "${1:-full}" | cut -d'|' -f2; }
mk;                                                              eq "FINAL all green (+optional NOT_RUN, +GAP) -> PASS" "$(fin)" "PASS"
mk; sed -i.b 's/^BROWSER|PASS/BROWSER|NOT_RUN/' "$T/R.txt";     eq "FINAL critical NOT_RUN -> INCOMPLETE" "$(fin)" "INCOMPLETE"
mk; sed -i.b 's/^E2E_03|PASS/E2E_03|FAIL/' "$T/R.txt";          eq "FINAL e2e FAIL -> FAIL" "$(fin)" "FAIL"
mk; sed -i.b 's/^PREREQ|PASS/PREREQ|FAIL/' "$T/R.txt";          eq "FINAL prereq missing only -> INCOMPLETE" "$(fin)" "INCOMPLETE"
mk; grep -v '^RECOVERY|' "$T/R.txt" > "$T/R2.txt"; mv "$T/R2.txt" "$T/R.txt"; eq "FINAL critical key absent -> INCOMPLETE" "$(fin)" "INCOMPLETE"
mk; echo "SEC_X|FAIL|tests=1" >> "$T/R.txt";                    eq "FINAL sub-key FAIL -> FAIL" "$(fin)" "FAIL"
mk; sed -i.b '/^SMOKE|/d;/^STACK_UP|/d;/^E2E_0[1-6]|/d;/^BROWSER|/d;/^RECOVERY|/d' "$T/R.txt"; eq "FINAL gate scope ignores stack/E2E keys -> PASS" "$(fin gate)" "PASS"
mk; sed -i.b 's/^HEAD|abc1234/HEAD|UNKNOWN/' "$T/R.txt";        eq "FINAL unknown HEAD -> INCOMPLETE" "$(fin)" "INCOMPLETE"

# 7 fail-fast path of the real runners (forced missing prerequisite; nothing heavy is started)
for r in mac-gate.sh mac-full.sh; do
  rm -rf "$T/ff"; C6_EV="$T/ff" C6_SELFTEST_FORCE_MISSING=1 bash "$H/$r" > "$T/ff.out" 2>&1; rc=$?
  eq "$r fail-fast exit code (2 = INCOMPLETE)" "$rc" "2"
  grep -q '^FINAL|INCOMPLETE|' "$T/ff/RESULTS.txt" && ok "$r fail-fast FINAL INCOMPLETE" || bad "$r FINAL" "$(tail -n 2 "$T/ff/RESULTS.txt")"
  grep -q '^PREREQ|FAIL|missing=.*selftest-forced' "$T/ff/RESULTS.txt" && ok "$r records PREREQ FAIL" || bad "$r PREREQ" "?"
  if grep -q '^BACKEND_TEST|PASS' "$T/ff/RESULTS.txt"; then bad "$r never PASS when skipped" "BACKEND_TEST PASS found"; else ok "$r: skipped phases are NOT_RUN, not PASS"; fi
  [ -f "$T/ff/ENV.txt" ] && ok "$r wrote ENV.txt" || bad "$r ENV.txt" "missing"
done

echo; echo "SELFTEST passed=$PASS failed=$FAILN"; [ $FAILN -eq 0 ]
