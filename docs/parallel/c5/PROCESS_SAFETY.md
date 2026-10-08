# C5 process safety (task from C0: external harness process cleanup)

Other people's processes run on the same machine (their `next start`, `http.server`, gradle, a live stack). A harness that cleans up by process name, by port or by a bare PID file kills them.
This note is the inventory of every place in **C5-owned tooling** that starts or stops a process, what was unsafe, and what replaced it. Files of other owners are only reported.

Base of this change: `a6dc217` (agent/c5-web). Line numbers below are at that base.

## Process model (what C5 tooling does now)

1. start the exact command, **detached** (own session and process group, `pgid == pid`) - `spawnOwned` in `tests/lib/owned-process.mjs`;
2. capture `pid` / `pgid` at once (after Node's `spawn` event, i.e. after exec, so `ps` shows the real command);
3. record identity - start time (`ps -o lstart=`) and command (`ps -ww -o command=`) - in a state file next to the pid (`$E2E_STACK_DIR/run/<name>.json`, `.run/owned/*.json`; JSON, mode 600);
4. every later signal (`stop`, `STOP`, `CONT`) first re-validates the identity: pid alive AND start time equal AND command equal. A reused pid (or a stale PID file) is `REFUSED` and **nothing is signalled**;
5. stop = `SIGCONT` + `SIGTERM` to the owned group, wait, then `SIGKILL` of the owned group only (identity re-checked), then verify the group is gone; `withOwned` / the harness `run` do this in `finally`;
6. a busy port **fails** (`PortBusyError` / CLI exit 3) naming the port and, read-only, its holder (`lsof`). Nothing is stopped to make room.

If the group leader is already dead but members remain, they are still ours (the kernel does not reuse a pid that is a live process-group id) and are stopped; if the leader pid exists but its identity differs, it is foreign.
`next start` / node servers rename themselves after start: the identity accepted for a pid is the spawn-time command plus, after `refresh` (called once the server answered), the command shown then. `refresh` requires the same pid, start time and pgid.
Launcher re-exec (fix 2026-10-08, branch agent/c5-s4-tooling-2): `npx` re-execs itself as `npm exec`, a shell wrapper execs its program. After spawn, `spawnOwned` / `start` therefore watches the process for up to 3 s (returns early once the command line has been stable for 600 ms) and records every command it shows, **only while pid, start time and pgid still match**; no explicit `refresh` is needed for the documented `start` ... `stop` flow (verified with a real `npx tsc -w`: recorded `node .../npx ...`, settled `npm exec ...`). `identify()` additionally accepts exactly one rename, `node .../npx <args>` = `npx <args>` = `npm exec <args>` with IDENTICAL arguments - never "any command"; a wrong start time is still REUSED.
State file retention: a REFUSED stop (pid reused, wrong start time/command, unusable state) **keeps** the state file while the recorded pid is alive (exit 4, message says "KEPT"); only STOPPED / ALREADY_GONE (or a REFUSED whose pid is gone) remove it. Deleting it on REFUSED had orphaned live servers (nothing recorded = nothing to stop).
Gradle runs the app JVM under its daemon, outside our group: `adopt` records the API listener as owned **only** if its command line contains this stack's private worktree path and it started after our launch; otherwise it is foreign and is not touched.

Residual risk, stated: a foreign process with the same pid, started in the same second, with the same command line (not reachable in practice; any doubt answers REFUSED).
Equivalent helper on `integration/v2` (C0, D-C0-48, `scripts/lib/owned-process.mjs`, `tests/infra/owned-process.test.mjs`): same model (it also records cwd and group members). The C5 copy lives in `tests/lib/` so the C5 branch is self-contained; once both are on one branch they can be consolidated (C0 decision) by pointing `owned-process-cli.mjs` / `harness-server.mjs` at the C0 module.

## Inventory (C5-owned)

| File:line (at a6dc217) | Pattern found | Owner | Status |
|---|---|---|---|
| `docs/parallel/c5/e2e-stack.sh:29-30` `listener()` / `kill_port()` | `lsof -ti tcp:$port -sTCP:LISTEN` then `kill $pids`: stops WHATEVER listens on the port | C5 | **FIXED** - removed; `owned stop --state` only |
| `e2e-stack.sh:109` `backend_down` | `kill_port "$API_PORT"` | C5 | **FIXED** - stops the recorded `backend-app` (adopted listener) then the `backend` launcher group |
| `e2e-stack.sh:125` `studio_up` | `kill_port "$STUDIO_PORT" \|\| true` before every start; default port **3003** = the live Studio of another stack | C5 | **FIXED** - stops only the Studio this script started; a foreign holder of the port aborts `studio-up` (names port + holder) **before** the build |
| `e2e-stack.sh:153` `down` | `kill_port` x3 (Studio, render, API) | C5 | **FIXED** - stops the four state files, each validated |
| `e2e-stack.sh:111,115,116,117` pause/resume | `kill -STOP/-CONT $(listener ...)`: freezes a foreign process | C5 | **FIXED** - `owned signal --state ... --sig STOP/CONT`, validated |
| `e2e-stack.sh:100` `backend_launch` | "port has a listener -> assume it is ours, return 0" + `nohup ./gradlew ... &` with no recorded pid | C5 | **FIXED** - `owned start` (own group, identity recorded); a busy port fails clearly |
| `e2e-stack.sh:119,121` `render_up` | same assumption; `nohup node ... &` | C5 | **FIXED** - `owned start`; `refresh` after `/health` |
| `e2e-stack.sh:133` `studio_up` start | `nohup npx next start ... &` | C5 | **FIXED** - `owned start`; stopped again if it never answers |
| `e2e-stack.sh:147` `status` | `listener()` (read-only) | C5 | **FIXED** - read-only `port-free`, shows `(owned)` when the recorded process matches |
| `tests/browser/README.md:14,21,30` and the `// Run:` headers of 9 `*.spec.mjs` | `(cd .test-build/browser && python3 -m http.server 4000 ... &)` - never stopped, fixed port 4000, invites a name-based cleanup | C5 | **FIXED** - `tests/browser/harness-server.mjs run -- <spec>`: free port, owned, always stopped in `finally`; specs already read `HARNESS_URL` / `DS_HARNESS_URL` |
| `tests/browser/README.md:35` | `(cd apps/<app> && npx next start -p 3001 &)` for the portals spec | C5 | **FIXED (docs)** - `owned-process-cli.mjs start/stop`; a taken port 3001/3002/3003 fails, never stops the holder |
| `tests/browser/page-runtime.spec.mjs:50` | in-process `server.listen(0)` | C2 (imported) | not mine; port 0, closed by the process |
| `tests/e2e-real/run.mjs` | spawns nothing; `execFileSync("git", ...)` only | C5 | ok (no process management) |
| `tests/e2e-real/selftest.mjs:13` | `execFile(node, run.mjs, { timeout })`: Node kills only the child it spawned | C5 | ok (own child by construction), ports `listen(0)` |
| `tests/e2e-real/flows/e2e-{12,14,s6,s7,s9}.mjs`, `lib/release.mjs` | `exec(cfg.*Cmd)`: run the operator-supplied `E2E_*_CMD` hooks; the suite never guesses how to stop a service | C5 | ok - the hooks are `e2e-stack.sh backend-restart/backend-down/...` which are now safe; the runbook line was reworded |
| `e2e/*.mjs` | `execSync` of `docker exec ... psql`, `git`, `next build`; `chromium.launch` closed with `browser.close()`; `listen(0)` / `listen(18099)` stubs closed in-process | C5 | ok (own children; no kill) |
| `package.json` scripts (`start`, `test:*`) | `next start` for a dev run (foreground, user's terminal) | C0 file | not mine, no cleanup involved |
| `scripts/ui-*.mjs`, `global-portals-*.mjs`, `v1-smoke.mjs` | Playwright / `execSync` only | C0/C6 | no process management |
| `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md:54` | hook description "never kill by name" | C5 | reworded to point at the owned hooks |

## Reported, NOT mine (not edited)

| File:line | Pattern | Owner | Note |
|---|---|---|---|
| `scripts/stop-local.sh:10` | `pkill -f "system-web-studio.*bootRun"` | C0 | **fixed on `integration/v2`** (`ac700f8`, D-C0-48); still present on this base |
| `scripts/stop-local.sh:13,14,16` | `lsof -ti tcp:8080 \| xargs kill` (also `$FRONTEND_PORT`, render port) | C0 | same, fixed in `ac700f8` |
| `scripts/stop-local.sh:5,8` | `kill "$(cat .run/<name>.pid)"` with no identity check | C0 | same |
| `scripts/public-down.sh:5,7` | `kill "$(cat .run/public/<name>.pid)"` | C0 | unvalidated pid file: a reused pid is killed |
| `scripts/public-up.sh:133,197` | `kill "$(cat .../ui.pid)"` / `tunnel.pid` (the line 133 also looks up a listener) | C0 | unvalidated pid file |
| `scripts/set-openrouter-key.sh:15` | `kill "$p"` from `.run/public/api.pid` | C0 | unvalidated pid file |
| `scripts/portals.sh:63-69` | kills the pid file only if the port's listener equals it (better), but `kill -TERM "$(cat $n.launcher)"` is unvalidated | C0 | partially safe |
| `scripts/public-portals.sh`, `scripts/run-local.sh`, `scripts/watchdog.sh`, `scripts/load-test.sh` | `nohup ... & echo $! > x.pid`, `kill -0 $(cat x.pid)` as "is it running" | C0 | a live reused pid reads as "running"; no signal sent by `kill -0` itself |
| `scripts/data-target.sh:71`, `portals.sh:14`, `public-portals.sh:16` | `lsof -iTCP:$p -sTCP:LISTEN` read-only | C0 | read-only, fine |

## Tests (all `node --test`, real processes, random free ports; run by `npm run test:unit` through `tests/lib/owned-process.wire.test.ts`)

`tests/lib/owned-process.test.mjs`
- `FOREIGN_NEXT_SURVIVES` (3): a foreign long-lived process whose argv contains `next start`, holding a port, survives (a) the cleanup of an unrelated owned process, (b) a start that finds the port occupied (fails with `port N ...`), (c) the CLI start (exit 3) and stop.
- `STALE_PID_SAFE` (3): a PID file pointing at a live unrelated process is REFUSED - pid only, right pid + wrong start time, right pid + wrong command, group form, no identity; a control proves the same identity with the TRUE start time/command is accepted; pid reuse; dead pid = `ALREADY_GONE`; a group whose leader died is still stopped through its members.
- `stopOwned`: SIGTERM first, SIGKILL only the owned group when SIGTERM is ignored, a SIGSTOPped process is stopped, children of the group go with it, the unrelated process is untouched. `withOwned` stops in `finally` also when the body throws.
- `HARNESS_PID_SCOPED` (2): process list before / during / after - exactly one process belongs to the harness (the pid it spawned), it is gone after `stop`, a `next start` look-alike and a `python3 -m http.server` look-alike survive; `run` propagates the command's exit code, stops its server, and a busy `--port` exits 3 without touching the holder.
- guard: scanner self-test; no `pkill`, `killall`, `lsof ... | kill`, `kill $(lsof|pgrep)`, `kill $(cat x.pid)`, `kill_port`, `xargs kill`, `kill $p` in C5-owned tracked tooling (`tests/`, `e2e/`, `docs/parallel/c5/`, C5 scripts); no backgrounded `http.server` / `next start ... &` instruction in `tests/`; `e2e-stack.sh` has no direct `kill` and uses the owned CLI.
- `e2e-stack.sh` behaviour (3): `studio-up` on an occupied port fails naming the port and leaves a foreign `next start` alone (before any build); `backend-down` / `render-pause` / `down` with stale state files pointing at a foreign pid signal nothing; a full start / refresh / STOP / CONT / stop cycle through the CLI.

## Not verified here (honest limits)

- `e2e-stack.sh up` against a real gradle backend (Docker + JDK 21 + minutes of compile) was **not** run: the shell plumbing is exercised with fake servers and foreign processes only. `adopt` of the real bootRun JVM relies on its command line containing the worktree path (`$E2E_STACK_DIR/backend-worktree`, in the classpath); if that does not hold, `backend_up` fails with "not provably this stack's API" instead of guessing, and `backend-down` still stops the gradle launcher group.
- A stack started by the previous version of `e2e-stack.sh` (no state files) is not recognised as ours: its ports then count as foreign and the script refuses to stop them. Stop that stack by hand once (its pid, not its port).
- The ports 3001/3002/3003/4000/47xxx were not used by any test here.
