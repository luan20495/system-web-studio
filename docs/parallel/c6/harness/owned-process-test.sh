#!/usr/bin/env bash
# C6 small lifecycle test of the owned-process helpers in lib.sh (c6_own_start / c6_own_stop). No Docker, Gradle, stack or browser.
# Scenario: a FOREIGN python http.server A (started by hand) and an OWNED one B; stop B => B gone, A still answers. Then stale-state cases:
# state naming A's pid with B's start time / with another command / for a dead pid => REFUSED or ALREADY_GONE and A is untouched.
# The only processes signalled here are the ones this script started itself (A at the end, by its own pid).
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
export C6_OWN_DIR; C6_OWN_DIR="$(mktemp -d)"; T="$C6_OWN_DIR"; PASS=0; FAILN=0
ok() { PASS=$((PASS+1)); echo "ok   $1"; }; bad() { FAILN=$((FAILN+1)); echo "FAIL $1 :: $2"; }
eq() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected [$3] got [$2]"; fi; }
PA=$((20000 + RANDOM % 9000)); PB=$((PA + 1))
up() { curl -s -o /dev/null -m 2 -w '%{http_code}' "http://127.0.0.1:$1/" 2>/dev/null; }
W="$(mktemp -d)"; echo hi > "$W/index.html"
A_PID=$(cd "$W" || exit 1; python3 -m http.server "$PA" --bind 127.0.0.1 > "$T/A.log" 2>&1 < /dev/null & echo $!)
done_() { kill "$A_PID" 2>/dev/null; rm -rf "$T" "$W"; }; trap done_ EXIT
c6_own_start t-b "$W" "$T/B.log" python3 -m http.server "$PB" --bind 127.0.0.1; sleep 1.5
eq "foreign A answers"          "$(up $PA)" 200
eq "owned B answers"            "$(up $PB)" 200
c6_own_status t-b; eq "status OWNED" "$?" 0
c6_own_start t-b "$W" "$T/B2.log" python3 -m http.server "$PB" --bind 127.0.0.1 2>/dev/null; eq "second start of same name refused" "$?" 3
# stale state: A's pid with B's recorded identity => REUSED, REFUSED, nothing signalled
printf '%s\n%s\n%s\n' "$A_PID" "$(sed -n 2p "$T/t-b.state")" "$(sed -n 3p "$T/t-b.state")" > "$T/t-stale1.state"
out=$(c6_own_stop t-stale1); eq "stale pid (B's start time) REFUSED" "${out%%(*}" REFUSED; eq "stale state file kept" "$([ -f "$T/t-stale1.state" ] && echo y)" y
printf '%s\n%s\n%s\n' "$A_PID" "$(c6_ps_lstart "$A_PID")" "python3 -m http.server $PB --bind 127.0.0.1" > "$T/t-stale2.state"
out=$(c6_own_stop t-stale2); eq "stale pid (A's start time, B's command) REFUSED" "${out%%(*}" REFUSED
eq "A still answers after stale cases" "$(up $PA)" 200
out=$(c6_own_stop t-b); eq "stop owned B" "$out" STOPPED
sleep 0.5; eq "B port free" "$(up $PB)" 000
eq "foreign A still answers after stopping B" "$(up $PA)" 200
printf '%s\n%s\n%s\n' "999999" "x" "y" > "$T/t-dead.state"; out=$(c6_own_stop t-dead); eq "dead pid ALREADY_GONE" "$out" ALREADY_GONE
out=$(c6_own_stop t-never); eq "no state ALREADY_GONE" "$out" ALREADY_GONE
echo "owned-process-test: pass=$PASS fail=$FAILN"; [ $FAILN -eq 0 ]
