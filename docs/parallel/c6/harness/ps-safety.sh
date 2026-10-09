#!/usr/bin/env bash
# C6 independent PROCESS-SAFETY test of the C5 owned-process tooling (candidate worktree $C5). Foreign Next A (started by C6, no C5 identity) vs C5-owned B; C5 cleanup; stale/reused PID.
# Only processes started by THIS script are ever signalled by C6 (by recorded pid), at the very end.
set -u
C5=${C5:-/Users/hoangluan/code/xweb-c6/../xweb-c6-c5}; C5=$(cd "$C5" && pwd); APP=${APP:-/Users/hoangluan/code/xweb-c6-rc/apps/platform}; CLI="$C5/tests/lib/owned-process-cli.mjs"; T=$(mktemp -d /tmp/c6-ps-XXXX)
export API_PROXY_TARGET=http://127.0.0.1:51080 HBL_ENV=local; PA=3499; PB=3498; PB2=3497; res=(); ok() { res+=("PASS $1"); echo "PASS $1"; }; bad() { res+=("FAIL $1 :: $2"); echo "FAIL $1 :: $2"; }
alive() { kill -0 "$1" 2>/dev/null; }
listener() { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1; }
code() { curl -s -o /dev/null -m 4 -w '%{http_code}' "http://127.0.0.1:$1/login" || true; }
waitup() { for _ in $(seq 1 40); do [ "$(code "$1")" != 000 ] && return 0; sleep 1; done; return 1; }
echo "C5 worktree HEAD: $(git -C "$C5" rev-parse HEAD)"
for p in $PA $PB $PB2; do [ -z "$(listener $p)" ] || { echo "port $p busy, abort"; exit 2; }; done
# ---- A: FOREIGN real Next server (started by hand: no C5 state, same command shape as the C5 ones)
( cd "$APP" && nohup npx next start -H 127.0.0.1 -p $PA > "$T/A.log" 2>&1 & ); waitup $PA || { echo "A did not start"; exit 2; }
A_PID=$(listener $PA); A_PPID=$(ps -o ppid= -p "$A_PID" | tr -d ' '); echo "A (foreign) listener pid $A_PID, launcher pid $A_PPID: $(ps -ww -o command= -p "$A_PID" | cut -c1-60) / $(ps -ww -o command= -p "$A_PPID" | cut -c1-60)"
snap() { ps -axo pid=,ppid=,pgid=,command= | grep -E "next (start|-server)|next-server|npm exec next" | grep -v grep; }
echo "--- process list BEFORE B"; snap | cut -c1-110
# ---- B: C5 owned
node "$CLI" start --state "$T/b.json" --cwd "$APP" --port $PB --name b -- npx next start -H 127.0.0.1 -p $PB > "$T/B.out" 2>&1; echo "start B exit=$? $(tail -1 "$T/B.out")"; waitup $PB || bad "B starts" "no answer on $PB"
node "$CLI" refresh --state "$T/b.json" >/dev/null 2>&1; B_PID=$(listener $PB); echo "B listener pid $B_PID; state keys: $(python3 -c "import json;d=json.load(open('$T/b.json'));print(d['pid'],d['pgid'],d['name'])")"
echo "--- process list DURING (A + B)"; snap | cut -c1-110
[ -n "$B_PID" ] && [ "$B_PID" != "$A_PID" ] && ok "B is a separate process from A" || bad "B separate" "B=$B_PID A=$A_PID"
# ---- C5 cleanup of B
node "$CLI" stop --state "$T/b.json" > "$T/stop.out" 2>&1; echo "stop B exit=$? $(tail -1 "$T/stop.out")"; sleep 1
echo "--- process list AFTER cleanup"; snap | cut -c1-110
[ -z "$(listener $PB)" ] && ! alive "$B_PID" && ok "FOREIGN-SAFE 1: B (owned) is stopped (port $PB free, pid $B_PID gone)" || bad "B stops" "listener=$(listener $PB) alive=$(alive "$B_PID" && echo y)"
alive "$A_PID" && alive "$A_PPID" && [ "$(code $PA)" = 200 ] && ok "FOREIGN_NEXT_SURVIVES: A (foreign next start, same command shape) alive and answering 200 after C5 cleanup" || bad "A survives" "A alive=$(alive "$A_PID" && echo y) launcher=$(alive "$A_PPID" && echo y) http=$(code $PA)"
# ---- stale / reused PID: a state file written for B2, B2 stopped, then the pid replaced by A's (simulated reuse), plus the real stop of an already-gone process
node "$CLI" start --state "$T/b2.json" --cwd "$APP" --port $PB2 --name b2 -- npx next start -H 127.0.0.1 -p $PB2 > "$T/B2.out" 2>&1; waitup $PB2; cp "$T/b2.json" "$T/stale-orig.json"; cp "$T/b2.json" "$T/stale-keep.json"; node "$CLI" stop --state "$T/b2.json" >/dev/null 2>&1
python3 - "$T" "$A_PID" <<'PY'
import json,sys,subprocess
t,a=sys.argv[1],int(sys.argv[2]); d=json.load(open(t+'/stale-orig.json'))
def w(name,**kw):
    x=dict(d); x.update(kw); json.dump(x,open(f'{t}/{name}.json','w'))
lst=subprocess.run(['ps','-o','lstart=','-p',str(a)],capture_output=True,text=True).stdout.strip(); cmd=subprocess.run(['ps','-ww','-o','command=','-p',str(a)],capture_output=True,text=True).stdout.strip()
w('reuse-pid-only',pid=a,pgid=a)                       # right (old) start time/command of B2, pid of A
w('reuse-same-start',pid=a,pgid=a,startedAt=lst) if 'startedAt' in d else w('reuse-same-start',pid=a,pgid=a)
w('reuse-start-ok-cmd-wrong',pid=a,pgid=a,**({'startedAt':lst} if 'startedAt' in d else {}))
print(sorted(d.keys()))
PY
for s in reuse-pid-only reuse-same-start reuse-start-ok-cmd-wrong; do node "$CLI" stop --state "$T/$s.json" > "$T/$s.out" 2>&1; rc=$?; if [ $rc = 4 ] && alive "$A_PID" && [ "$(code $PA)" = 200 ] && [ -f "$T/$s.json" ]; then ok "STALE_PID_SAFE: $s -> REFUSED (exit 4), A alive, state file KEPT"; else bad "STALE_PID_SAFE $s" "exit=$rc alive=$(alive "$A_PID" && echo y) out=$(tail -1 "$T/$s.out" | cut -c1-100)"; fi; done
node "$CLI" stop --state "$T/stale-orig.json" > "$T/gone.out" 2>&1; rc=$?; { [ $rc = 0 ] || [ $rc = 6 ]; } && alive "$A_PID" && ok "STALE_PID_SAFE: a state whose process is gone -> ALREADY_GONE/no signal ($(tail -1 "$T/gone.out" | cut -c1-60)); A alive" || bad "gone state" "exit=$rc"
for s in reuse-pid-only; do node "$CLI" signal --state "$T/$s.json" --sig STOP > "$T/sig.out" 2>&1; rc=$?; alive "$A_PID" && [ "$(code $PA)" = 200 ] && [ $rc != 0 ] && ok "STALE_PID_SAFE: signal STOP on a reused pid refused (exit $rc), A not frozen (still answers 200)" || bad "STOP refused" "exit=$rc http=$(code $PA)"; done
# ---- harness-server lifecycle (C5 harness): run -> always stops its own server; foreign survives; failing command still cleans
( cd "$C5" && node tests/browser/harness-server.mjs run -- node -e "const u=process.env.HARNESS_URL; fetch(u).then(r=>{console.log('harness url',u,r.status); process.exit(0)}).catch(()=>process.exit(1))" > "$T/h1.out" 2>&1 ); echo "harness run exit=$? $(head -2 "$T/h1.out" | tr '\n' ' ' | cut -c1-140)"
( cd "$C5" && node tests/browser/harness-server.mjs run -- node -e "process.exit(7)" > "$T/h2.out" 2>&1 ); rc=$?; echo "harness run (failing command) exit=$rc"
LEFT=$(ps -axo command= | grep -c "harness-server.mjs serve" ); [ "$LEFT" -le 1 ] && ok "HARNESS_PID_SCOPED: after two runs no harness server left behind (grep itself counted: $LEFT)" || bad "harness leftovers" "$LEFT"
[ "$rc" = 7 ] && ok "harness propagates the command's exit code (7) and still stops its server" || bad "exit code" "$rc"
alive "$A_PID" && ok "A still alive after the harness lifecycle runs" || bad "A after harness" "dead"
# ---- e2e-stack.sh down with stale state files pointing at A (no docker needed)
SD="$T/stack"; mkdir -p "$SD/run"; : > "$SD/stack.env"; for n in backend backend-app render studio; do python3 - "$T" "$SD" "$n" "$A_PID" <<'PY'
import json,sys
t,sd,n,a=sys.argv[1:5]; d=json.load(open(t+'/stale-keep.json')); d['pid']=int(a); d['pgid']=int(a); json.dump(d,open(f'{sd}/run/{n}.json','w'))
PY
done
( cd "$C5" && E2E_STACK_NAME=c6safety E2E_STACK_DIR="$SD" E2E_REPO="$C5" bash docs/parallel/c5/e2e-stack.sh down > "$T/down.out" 2>&1 ); echo "e2e-stack.sh down exit=$? : $(tail -3 "$T/down.out" | tr '\n' ' ' | cut -c1-240)"; ls "$SD/run" | tr '\n' ' '
alive "$A_PID" && [ "$(code $PA)" = 200 ] && ok "e2e-stack.sh down with stale state files naming A's pid: A untouched" || bad "e2e-stack down" "A dead or not answering"
# ---- static scan of C5-owned tooling for machine-wide / by-name / by-port kills
( cd "$C5" && grep -rnE "pkill|killall|kill_port|xargs +kill|lsof[^|]*\| *(xargs )?kill|kill +\\\$\(cat|kill +\\\$\((lsof|pgrep)|kill +-[A-Z0-9]+ +\\\$\(lsof" tests e2e docs/parallel/c5 scripts/ui-audit.mjs scripts/ui-c6-evidence.mjs scripts/ui-dialog-check.mjs scripts/ui-studio-shots.mjs scripts/verify-create-company-ui.mjs 2>/dev/null | grep -v "owned-process.test.mjs\|PROCESS_SAFETY.md\|^docs/parallel/c5/[A-Za-z_]*\.md" > "$T/scan.out"; echo "scan hits: $(wc -l < "$T/scan.out")"; cut -c1-170 "$T/scan.out" | head -8 )
HITS=$(grep -vE "^(tests/lib/owned-process\.test\.mjs|tests/.*test\.(mjs|ts)):|:[0-9]+: *(//|#|\*)" "$T/scan.out" | wc -l | tr -d ' '); [ "$HITS" = 0 ] && ok "UNSAFE_EXTERNAL_CLEANUP_REMOVED: no pkill/killall/kill-by-port/kill \$(cat pid) in C5-owned executable tooling (comments and the guard test excluded)" || bad "unsafe cleanup patterns" "$HITS hit(s) — see scan"
# ---- C5's own unit tests, as run on this HEAD
( cd "$C5" && node --test tests/lib/owned-process.test.mjs > "$T/unit.out" 2>&1 ); echo "C5 owned-process tests: $(grep -E '^# (tests|pass|fail)' "$T/unit.out" | tr '\n' ' ')"
# ---- teardown: only what this script started (recorded pids)
kill "$A_PID" "$A_PPID" 2>/dev/null; sleep 1; alive "$A_PID" && kill -9 "$A_PID" 2>/dev/null
cp "$T"/*.out "$T"/scan.out "${EVD:-/tmp}/" 2>/dev/null
printf '%s\n' "${res[@]}" > "${EVD:-/tmp}/ps-safety-results.txt"; echo; printf '%s\n' "${res[@]}"
echo "fails: $(printf '%s\n' "${res[@]}" | grep -c '^FAIL')"
