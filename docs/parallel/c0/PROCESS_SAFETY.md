# Process safety on the shared Mac (C0, D-C0-48)

Several agents run their own stacks, builds and test servers on ONE machine. A kill by name or by port alone does not know whose process it hits.
Incident 2026-10-08: `pkill -f "gradlew"` (11:38) ended another stack's backend, `pkill -f "next start"` (six times, 12:17–13:05) ended the public and local Next portals.

## Policy (every agent, every script, every test)
**NEVER** kill by name or by port alone on this machine: `pkill -f …`, `pkill <name>`, `killall <name>`, `kill $(pgrep …)`, `ps | grep | xargs kill`, `kill $(lsof -ti tcp:P)`, `lsof -ti … | xargs kill`, `fuser -k`, `kill 0`, `kill -1`.
**ALWAYS** terminate only what you can PROVE you started: a pid you captured at launch, the process group you created, children of a known parent (`pkill -TERM -P "$pid"`), or the owned-process helper below.
A port is cleaned only after the listener is proven yours; a foreign listener is reported and left alone.

## Safe patterns
```bash
# 1. your own process: capture the pid when you launch it, kill that pid in a trap
node server.js & PID=$!; trap 'kill "$PID" 2>/dev/null' EXIT
# 2. children of a known parent
pkill -TERM -P "$PID"
# 3. the helper: records pid + start time + command + cwd (+ process group), refuses anything else
. scripts/lib/owned-process.sh
op_start me web .run/me-web.json --port 4100 --ready-port 4100 -- python3 -m http.server 4100 --bind 127.0.0.1
op_status .run/me-web.json                       # 0 running · 1 gone · 2 the pid now belongs to someone else
op_stop   .run/me-web.json                       # stops exactly that group, verifies, removes the state file
op_stop_port 4100 --state .run/me-web.json       # by port, but ONLY if the listener is the recorded process; foreign => exit 3, nothing killed
op_stop_port 8080 --cwd-under "$ROOT"            # ownership by working directory (a checkout): what scripts/stop-local.sh uses
```
```js
import { withOwned } from "./scripts/lib/owned-process.mjs";      // start, run, and ALWAYS stop exactly that process (try / finally)
await withOwned({ owner: "me", name: "web", cmd: ["python3", "-m", "http.server", "4100"], port: 4100, stateFile: ".run/me-web.json" }, async (m) => { /* test */ });
```
Also fine: `docker stop|kill <exact container name>`, `kill -0 "$pid"` (existence), `process.kill(pid)` / `child.kill()` of your own child, `kill -- -"$pgid"` of a group you created.
Use your OWN ports (not 3001–3003, 3201–3203, 3301–3303, 4000 unless you started it); check a port is free before you start; never "clean up" before a test, only after your own start.

## The helper (scripts/lib/owned-process.mjs, CLI scripts/owned-process.mjs, shell scripts/lib/owned-process.sh)
* **Identity** of an owned process = pid **and** start time (`ps -o lstart=`, kernel start time, 1 s resolution, `LC_ALL=C`) **and** full command (`ps -ww -o command=`) **and** cwd (`lsof -a -d cwd -Fn`). `kill -0` is never trusted: it succeeds for any live pid, including one reused after the original died. A zombie counts as dead.
* **PID reuse:** the recorded start time / command no longer match => state `REUSED`, the process is **not signalled**, the stale metadata is removed (`STALE_FOREIGN`). Residual risk (same pid, same second, same command line, same cwd) is not reachable in practice and any doubt means "refused".
* **Process group:** started `detached` (own session and group, pgid = pid). While the leader's identity matches, `kill(-pgid)` reaches exactly its tree; TERM, then KILL after the grace period, then verified. If the leader is already dead, the children are stopped one by one from the snapshot taken at readiness (pid + start time + command each), never by group number.
* **Port:** `stopOwnedPort` / `stop-port` looks at the real listener(s); each must be the recorded process / member or (with `--cwd-under`) a process whose cwd is inside that directory. Otherwise `FOREIGN_PROCESS` (exit 3) and nothing is signalled. A port alone, without `--state` or `--cwd-under`, is a usage error.
* `start` refuses a port that already has a listener (`PORT_BUSY`), cleans up a process that never becomes ready, and writes no metadata for it.
* Exit codes: 0 ok · 1 failed · 2 reused / stale · 3 FOREIGN_PROCESS · 4 PORT_BUSY · 64 usage.

## Enforcement
`tests/guards/process-safety.mjs` (part of `npm run guard:static` and `npm run gate:frontend`) fails on the forbidden patterns in committed code under `scripts/ tests/ e2e/ workers/ infra/ tooling/` (comments blanked; strings ARE scanned, because a JS command is a string — a message that merely names a forbidden command is flagged: rephrase it). One justified exception: `# guard-allow: PROCESS-SAFETY — <reason>`.
It does NOT look at /tmp, shell history, agent transcripts, other worktrees or docs: a command typed by hand is not caught by any guard — this policy is. `tests/infra/owned-process.test.mjs` (`npm run test:infra:processes`) proves with real processes that what the helper does not own survives.
