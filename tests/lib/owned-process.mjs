// @class: unit
// Owned-process helper (C5-S4, task "external harness process cleanup"). Start, identify, signal and stop ONLY the processes this tool started, on a shared machine where other people
// run their own `next start`, `http.server`, gradle ... at the same time.
//
//   WHY      a process-name sweep for "next start", stopping "whoever listens on :3003" or signalling a bare pid-file value stop OTHER people's processes. A pid number is not an identity: numbers are reused and
//            `kill -0 <pid>` succeeds for any live process.
//   RULE     never signal a process unless it is PROVEN to be the one this tool spawned. Never look a process up by name. A busy port is never "cleaned": it is reported and the caller fails.
//
//   MODEL    1. spawn the exact command DETACHED (own session = own process group, pgid == pid)      -> spawnOwned
//            2. capture pid / pgid at once                                                              -> state.pid, state.pgid
//            3. record identity = start time (`ps -o lstart=`) + command (`ps -ww -o command=`)        -> state file next to the pid (JSON, mode 600)
//            4. every later signal re-validates that identity first (REUSED pid => REFUSED, nothing signalled)
//            5. stop = SIGTERM, wait, SIGKILL ONLY the owned group, then VERIFY it is gone             -> stopOwned (withOwned = try/finally around it)
//            6. a busy port FAILS with the port and (read-only) its holder                             -> assertPortFree / PortBusyError
//
//   A process group is only signalled while its leader's identity matches, or - leader already dead - while processes still exist in that pgid (the kernel never hands out a pid that is
//   still a live process-group id, so those processes can only be the old group). Residual risk, stated: a foreign process with the same pid started in the SAME second with the SAME
//   command line; a pid is not reused within ~100k allocations, and any doubt answers REFUSED.
import { spawn, spawnSync } from "node:child_process";
import { chmodSync, closeSync, mkdirSync, openSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import net from "node:net";

const ENV = { ...process.env, LC_ALL: "C", LANG: "C" };                       // stable `ps` text
const run = (cmd, args) => { const r = spawnSync(cmd, args, { encoding: "utf8", env: ENV, timeout: 15000 }); return r.status === 0 ? r.stdout : ""; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ----------------------------------------------------------------------------------------------------------------------------------------------------------- process facts (read-only)
const norm = (s) => s.trim().replace(/\s+/g, " ");
export const startTimeOf = (pid) => norm(run("ps", ["-p", String(pid), "-o", "lstart="]));
export const commandOf = (pid) => run("ps", ["-ww", "-p", String(pid), "-o", "command="]).trim();
export const pgidOf = (pid) => { const t = run("ps", ["-p", String(pid), "-o", "pgid="]).trim(); return t ? Number(t) : null; };
const statOf = (pid) => run("ps", ["-p", String(pid), "-o", "stat="]).trim();
/** exited but not yet reaped (answers kill -0, shows in ps): DEAD for every purpose here */
export const isZombie = (pid) => statOf(pid).startsWith("Z");
export const exists = (pid) => { try { process.kill(Number(pid), 0); } catch (e) { if (e.code !== "EPERM") return false; } return !isZombie(pid); };
/** live (non-zombie) members of a process group: [{pid, startTime, command}] */
export function groupMembers(pgid) {
  const rows = run("ps", ["-axo", "pid=,pgid=,stat="]).split("\n").map((l) => l.trim().split(/\s+/)).filter((r) => r.length >= 3 && Number(r[1]) === Number(pgid) && !r[2].startsWith("Z"));
  return rows.map((r) => Number(r[0])).map((pid) => ({ pid, startTime: startTimeOf(pid), command: commandOf(pid) })).filter((m) => m.startTime);
}
/** read-only: pids LISTENing on a TCP port (lsof; [] when lsof is missing or nothing listens) */
export const listenerPids = (port) => run("lsof", ["-nP", `-iTCP:${port}`, "-sTCP:LISTEN", "-t"]).split("\n").map((s) => s.trim()).filter(Boolean).map(Number);
export const describeHolder = (port) => { const p = listenerPids(port); return p.length ? p.map((pid) => `pid ${pid} (${commandOf(pid).slice(0, 160) || "?"})`).join(", ") : "holder unknown (lsof shows no listener; the port may be held by another user or in another stack namespace)"; };
const epochOf = (lstart) => { const t = Date.parse(lstart); return Number.isNaN(t) ? null : t; };

// ----------------------------------------------------------------------------------------------------------------------------------------------------------- ports
export class PortBusyError extends Error {
  constructor(port, holder) { super(`port ${port} is already in use by ${holder}. Not ours: nothing was started and nothing was killed. Free it yourself or choose another port.`); this.name = "PortBusyError"; this.code = "PORT_BUSY"; this.port = port; }
}
const connects = (port, host) => new Promise((res) => { const s = net.connect({ host, port }); const done = (v) => { s.destroy(); res(v); }; s.once("connect", () => done(true)); s.once("error", () => done(false)); s.setTimeout(1500, () => done(false)); });
const binds = (port, host) => new Promise((res) => { const s = net.createServer(); s.once("error", (e) => res(e.code === "EADDRINUSE" ? false : true)); s.listen({ port, host, exclusive: true }, () => s.close(() => res(true))); });
/** true when something listens on the port (lsof OR a successful connect OR EADDRINUSE: each alone misses a case, e.g. a wildcard listener on macOS). Never signals anything. */
export async function portInUse(port, host = "127.0.0.1") { return listenerPids(port).length > 0 || (await connects(port, host)) || !(await binds(port, host)); }
/** throws PortBusyError (naming the port and its read-only holder); NEVER kills anything */
export async function assertPortFree(port, host = "127.0.0.1") { if (await portInUse(port, host)) throw new PortBusyError(port, describeHolder(port)); }
/** a free high port chosen by the kernel (the port is free at return time; a race is possible and is caught by assertPortFree/EADDRINUSE in the child) */
export const freePort = () => new Promise((res, rej) => { const s = net.createServer(); s.once("error", rej); s.listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => res(p)); }); });

// ----------------------------------------------------------------------------------------------------------------------------------------------------------- state file
export function writeState(file, state) {
  mkdirSync(dirname(resolve(file)), { recursive: true });
  const tmp = `${file}.tmp.${process.pid}`; writeFileSync(tmp, JSON.stringify(state, null, 2) + "\n", { mode: 0o600 }); renameSync(tmp, file); try { chmodSync(file, 0o600); } catch { /* best effort */ }
}
export function readState(file) { try { return JSON.parse(readFileSync(file, "utf8")); } catch { return null; } }
const stateOf = (x) => (typeof x === "string" ? readState(x) : x);
const fileOf = (x) => (typeof x === "string" ? x : x?.stateFile ?? null);

// ----------------------------------------------------------------------------------------------------------------------------------------------------------- identity
const commandsOf = (s) => [s.command, ...(s.settledCommands ?? [])].filter(Boolean);
/** the ONE accepted rename: `npx` re-execs itself as `npm exec`, so `node .../npx <args>`, `npx <args>` and `npm exec <args>` are the same launch. Returns the <args> part (non-empty) or null for any other command. */
const base = (p) => p.replace(/^.*\//, "");
export function npxArgs(cmd) {
  const t = String(cmd).trim().split(/\s+/);
  let rest = null;
  if (base(t[0]) === "node" && /^(npx|npx-cli\.js)$/.test(base(t[1] ?? ""))) rest = t.slice(2);
  else if (/^(npx|npx-cli\.js)$/.test(base(t[0]))) rest = t.slice(1);
  else if (base(t[0]) === "npm" && t[1] === "exec") rest = t.slice(2);
  return rest && rest.length ? rest.join(" ") : null;
}
/** exact match, or the npx <-> npm exec allowlist with IDENTICAL arguments; never "any command" */
const commandMatches = (state, live) => commandsOf(state).some((c) => c === live || (npxArgs(c) !== null && npxArgs(c) === npxArgs(live)));
/**
 * identify(state) -> { state, reason }
 *   OWNED    the recorded pid is alive AND start time AND command match
 *   GONE     no such pid (and, for a group, no member left)
 *   REUSED   the pid is alive but is a DIFFERENT process (start time or command differs): foreign, never signalled
 *   UNKNOWN  unusable state (no pid / start time / command)
 */
export function identify(state) {
  if (!state || !Number.isInteger(state.pid) || state.pid <= 1 || !state.startedAt || !state.command) return { state: "UNKNOWN", reason: "incomplete state (needs pid, startedAt, command)" };
  if (!exists(state.pid)) return { state: "GONE", reason: "no such pid" };
  const startTime = startTimeOf(state.pid); if (!startTime) return { state: "GONE", reason: "pid vanished" };
  if (startTime !== state.startedAt) return { state: "REUSED", reason: `start time differs (recorded "${state.startedAt}", live "${startTime}")` };
  const cmd = commandOf(state.pid);
  if (!commandMatches(state, cmd)) return { state: "REUSED", reason: `command differs (recorded "${state.command.slice(0, 80)}", live "${cmd.slice(0, 80)}")` };
  return { state: "OWNED", reason: "pid, start time and command match" };
}
const isGroup = (s) => s.scope !== "pid" && Number.isInteger(s.pgid) && s.pgid > 1;
/** group members left after the leader died; only counted as ours when each started no earlier than the spawn (clock slack 2 s) */
function orphansOf(state) {
  if (!isGroup(state)) return [];
  const t0 = epochOf(state.spawnedAtLocal ?? state.startedAt);
  return groupMembers(state.pgid).filter((m) => m.pid !== state.pid && (t0 === null || (epochOf(m.startTime) ?? t0) >= t0 - 2000));
}
const selfPgid = () => pgidOf(process.pid);

// ----------------------------------------------------------------------------------------------------------------------------------------------------------- spawn
/**
 * spawnOwned(cmd, args, { cwd, env, logFile, stateFile, name, port, host, group=true, settleMs=3000 }) -> { pid, pgid, startedAt, command, stateFile }
 * `port` => assertPortFree first (PortBusyError, nothing started). The child is DETACHED (own session / process group), stdout+stderr go to logFile (append, else discarded).
 */
export async function spawnOwned(cmd, args = [], opts = {}) {
  const { cwd = process.cwd(), env = {}, logFile = null, stateFile = null, name = null, port = null, host = "127.0.0.1", group = true, settleMs = 3000 } = opts;
  if (!cmd) throw new Error("spawnOwned needs a command");
  if (stateFile) { const prev = readState(stateFile); if (prev && identify(prev).state === "OWNED") throw Object.assign(new Error(`${name ?? cmd} is already running (pid ${prev.pid}, state ${stateFile})`), { code: "ALREADY_RUNNING", state: prev }); }
  if (port != null) await assertPortFree(port, host);
  let fd = null; if (logFile) { mkdirSync(dirname(resolve(logFile)), { recursive: true }); fd = openSync(logFile, "a"); }
  const spawnedAtMs = Date.now();
  const child = spawn(cmd, args, { cwd, env: { ...process.env, ...env }, detached: group, stdio: ["ignore", fd ?? "ignore", fd ?? "ignore"] });
  if (fd !== null) closeSync(fd);
  await new Promise((res, rej) => { child.once("spawn", res); child.once("error", rej); });                 // 'spawn' = exec succeeded: `ps` shows the target command, not the fork copy of this process
  child.unref();
  const pid = child.pid;
  const startedAt = startTimeOf(pid), command = commandOf(pid);
  if (!startedAt || !command || isZombie(pid)) throw Object.assign(new Error(`${cmd} exited before its identity could be recorded (log: ${logFile ?? "none"})`), { code: "EXITED_EARLY" });
  const state = { schema: 1, name, pid, pgid: group ? pgidOf(pid) : null, scope: group ? "group" : "pid", startedAt, command, settledCommands: [], spawnedAtLocal: new Date(spawnedAtMs).toString(), argv: [cmd, ...args], cwd: resolve(cwd), logFile: logFile ? resolve(logFile) : null, port, startedBy: process.pid };
  if (group && state.pgid !== pid) { try { process.kill(pid, "SIGKILL"); } catch { /* gone */ } throw new Error(`pgid ${state.pgid} != pid ${pid}: the child did not get its own process group, refusing to track it`); }
  if (settleMs > 0) await settleIdentity(state, settleMs);
  if (stateFile) writeState(stateFile, state);
  return { ...state, stateFile: stateFile ? resolve(stateFile) : null, child };
}

/** A launcher may re-exec itself (`npx` -> `npm exec`, a shell script -> its program): the command line changes while pid and start time stay. Watch up to `maxMs` (return early once the command
 *  has been stable for 600 ms) and record every command seen, ONLY while the pid still has the recorded start time (and pgid): a pid that changed hands is never blessed. */
async function settleIdentity(state, maxMs, stableMs = 600) {
  const end = Date.now() + maxMs; let lastChange = Date.now();
  while (Date.now() < end && Date.now() - lastChange < stableMs) {
    await sleep(100);
    if (!exists(state.pid) || startTimeOf(state.pid) !== state.startedAt || (isGroup(state) && pgidOf(state.pid) !== state.pgid)) return;
    const cmd = commandOf(state.pid);
    if (cmd && !commandsOf(state).includes(cmd)) { state.settledCommands = [...(state.settledCommands ?? []), cmd]; lastChange = Date.now(); }
  }
}

/** after readiness: record the command the process shows NOW (a node server renames itself, `npx` execs ...). Requires the same pid + start time and (group) the same pgid: never re-blesses a reused pid. */
export function refreshIdentity(stateOrFile) {
  const state = stateOf(stateOrFile); const file = fileOf(stateOrFile);
  if (!state || !exists(state.pid) || startTimeOf(state.pid) !== state.startedAt) return { ok: false, reason: "pid is not the recorded process (start time differs or gone)" };
  if (isGroup(state) && pgidOf(state.pid) !== state.pgid) return { ok: false, reason: "pgid changed" };
  const cmd = commandOf(state.pid); if (!commandsOf(state).includes(cmd)) { state.settledCommands = [...(state.settledCommands ?? []), cmd]; if (file) writeState(file, state); }
  return { ok: true, command: cmd };
}

/**
 * adoptListener({ port, parent, contains, stateFile }) -> state | throws. For a server whose process is NOT in our group (gradle bootRun runs the app JVM under the gradle daemon): the listener on `port`
 * is recorded as owned only when it started at/after the parent's spawn AND its command line contains `contains` (a path that is private to this stack, e.g. its worktree). Anything else is foreign.
 */
export function adoptListener({ port, parent, contains, stateFile, name = null }) {
  const par = stateOf(parent); if (!par || identify(par).state !== "OWNED") throw Object.assign(new Error("adopt: the parent process is not owned/alive"), { code: "NOT_OWNED" });
  if (!contains) throw new Error("adopt needs `contains`");
  const pids = listenerPids(port); if (!pids.length) throw Object.assign(new Error(`adopt: nothing listens on ${port}`), { code: "NO_LISTENER" });
  const t0 = epochOf(par.spawnedAtLocal);
  const out = [];
  for (const pid of pids) {
    const startedAt = startTimeOf(pid), command = commandOf(pid), st = epochOf(startedAt);
    if (!command.includes(contains) || (t0 !== null && (st ?? 0) < t0 - 2000)) throw Object.assign(new Error(`adopt: listener pid ${pid} on port ${port} is NOT provably ours (command must contain "${contains}" and start after our spawn): ${command.slice(0, 160)}`), { code: "FOREIGN_PROCESS", pid });
    out.push({ pid, startedAt, command });
  }
  const first = out[0]; const state = { schema: 1, name, pid: first.pid, pgid: null, scope: "pid", startedAt: first.startedAt, command: first.command, settledCommands: [], spawnedAtLocal: par.spawnedAtLocal, adoptedFrom: par.pid, port, extraPids: out.slice(1) };
  if (stateFile) writeState(stateFile, state);
  return { ...state, stateFile: stateFile ? resolve(stateFile) : null };
}

// ----------------------------------------------------------------------------------------------------------------------------------------------------------- signal / stop
function targetAlive(state) {
  const id = identify(state);
  if (id.state === "OWNED") return true;
  return id.state === "GONE" && orphansOf(state).length > 0;
}
const sendSignal = (state, sig) => {
  if (state.scope === "pid") { try { process.kill(state.pid, sig); } catch { /* gone */ } return; }
  if (state.pgid === selfPgid()) throw new Error("refusing to signal my own process group");
  try { process.kill(-state.pgid, sig); } catch { /* gone */ }
};
async function waitGone(state, ms) { const end = Date.now() + ms; while (Date.now() < end) { if (!targetAlive(state)) return true; await sleep(100); } return !targetAlive(state); }

/** signalOwned(stateOrFile, "SIGSTOP"|"SIGCONT"|...) -> { ok, state, reason }: signals ONLY after identity is re-validated; REFUSED otherwise */
export function signalOwned(stateOrFile, sig) {
  const state = stateOf(stateOrFile); const id = identify(state);
  if (id.state === "OWNED" || (id.state === "GONE" && orphansOf(state ?? {}).length)) { sendSignal(state, sig); return { ok: true, state: "SIGNALLED", signal: sig }; }
  return { ok: false, state: id.state === "GONE" ? "GONE" : "REFUSED", reason: id.reason };
}

/**
 * stopOwned(stateOrFile, { graceMs=8000, killMs=4000, removeState=true }) -> { state, ... }
 *   STOPPED       SIGTERM (after SIGCONT, in case it is paused), then SIGKILL of the owned group only after graceMs; verified gone
 *   ALREADY_GONE  nothing of it is running
 *   REFUSED       the recorded pid is alive but is NOT our process (pid reuse / wrong start time / wrong command), or the state is unusable: NOTHING signalled
 *   FAILED        still running after SIGKILL
 */
export async function stopOwned(stateOrFile, opts = {}) {
  const { graceMs = 8000, killMs = 4000, removeState = true } = opts; const state = stateOf(stateOrFile); const file = fileOf(stateOrFile);
  // the state file is removed only for STOPPED / ALREADY_GONE (and for a REFUSED whose recorded pid is provably gone). A REFUSED result KEEPS it while the recorded pid is alive: the operator still needs to see what was recorded.
  const done = (r) => {
    const drop = removeState && file && (["STOPPED", "ALREADY_GONE"].includes(r.state) || (r.state === "REFUSED" && !exists(state?.pid)));
    if (drop) rmSync(file, { force: true }); else if (file && r.state === "REFUSED") r.stateKept = true;
    return r;
  };
  const id = identify(state);
  if (id.state === "UNKNOWN") return { state: "REFUSED", reason: id.reason, removeStale: false };
  if (id.state === "REUSED") return done({ state: "REFUSED", reason: `${id.reason}: pid ${state.pid} belongs to someone else, not signalled` });
  if (id.state === "GONE" && !orphansOf(state).length) return done({ state: "ALREADY_GONE", reason: id.reason });
  sendSignal(state, "SIGCONT"); sendSignal(state, "SIGTERM");
  let how = "TERM";
  if (!(await waitGone(state, graceMs))) {
    if (!targetAlive(state)) how = "TERM";                                                                         // re-validate right before SIGKILL: identity must still hold
    else { how = "KILL"; sendSignal(state, "SIGKILL"); await waitGone(state, killMs); }
  }
  if (targetAlive(state)) return { state: "FAILED", reason: "still running after SIGKILL", pid: state.pid };
  for (const extra of state.extraPids ?? []) { if (exists(extra.pid) && startTimeOf(extra.pid) === extra.startedAt && commandOf(extra.pid) === extra.command) { try { process.kill(extra.pid, "SIGTERM"); } catch { /* gone */ } } }
  return done({ state: "STOPPED", signal: how, pid: state.pid });
}

/** { state: OWNED|GONE|REUSED|UNKNOWN, ... } for a state file; a missing file is GONE ("nothing recorded, nothing owned") */
export function statusOf(file) { const s = readState(file); if (!s) return { state: "GONE", reason: "no state file", stateData: null }; return { ...identify(s), stateData: s }; }

/** spawn, run fn(owned), and ALWAYS stop exactly what was spawned (try/finally + exit/SIGINT/SIGTERM hooks of this process). A cleanup failure is thrown, never swallowed. */
export async function withOwned(cmd, args, opts, fn) {
  const owned = await spawnOwned(cmd, args, opts);
  const onSignal = (sig) => () => { try { sendSignal(owned, "SIGKILL"); } catch { /* ignore */ } process.exit(128 + (sig === "SIGINT" ? 2 : 15)); };
  const hi = onSignal("SIGINT"), ht = onSignal("SIGTERM"); process.once("SIGINT", hi); process.once("SIGTERM", ht);
  const onExit = () => { try { if (identify(owned).state === "OWNED") sendSignal(owned, "SIGKILL"); } catch { /* ignore */ } };
  process.once("exit", onExit);
  try { return await fn(owned); }
  finally {
    process.off("SIGINT", hi); process.off("SIGTERM", ht);
    const r = await stopOwned(owned, { removeState: true }); process.off("exit", onExit);
    if (!["STOPPED", "ALREADY_GONE"].includes(r.state)) throw new Error(`owned process ${opts?.name ?? cmd} was not stopped cleanly: ${JSON.stringify(r)}`);
  }
}
