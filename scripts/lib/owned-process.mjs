// @class: unit
// Owned-process helper (C0, D-C0-48): start, identify, stop and verify ONLY processes this tool started, on a shared macOS machine where several agents run their own stacks.
//
//   WHY      `pkill -f "next start"` / `killall node` / "kill whoever listens on 3001" kill other people's processes (2026-10-08 incident). A pid number alone is not an identity either:
//            numbers are reused, and `kill -0 <pid>` succeeds for ANY live process.
//   RULE     never signal a process unless it is PROVEN to be the one this tool started. Never scan by name. A port is only cleaned when its listener is proven ours.
//
//   IDENTITY of an owned process (all must match; recorded at readiness, re-checked before every signal; a zombie counts as dead):
//     pid + start time   `ps -o lstart=` (kernel p_starttime, 1-second resolution, LC_ALL=C so the text is stable) : a reused pid has a different start time
//     command            `ps -ww -o command=` (full argv)                                                       : a different program with the same start second does not match
//     cwd                `lsof -a -d cwd -Fn -p <pid>`                                                          : when lsof can read it (a different worktree does not match)
//   Residual risk, stated: a foreign process with the same pid, started in the SAME second, with the SAME command line AND cwd. A pid is not handed out again before ~100 000 others, so this
//   is not reachable in practice; and the answer to any doubt is "REFUSED, nothing signalled".
//
//   PROCESS GROUP  the process is launched `detached` = its own session and process group (pgid == pid). While the leader (whose identity matches) is alive, its pgid cannot belong to anyone
//                  else, so `kill(-pgid)` reaches exactly its tree. If the leader is gone, children are stopped ONE BY ONE from the snapshot taken at readiness (pid + start time + command each),
//                  never by group number (a number with no live member may be re-issued).
//
//   PORT           stopOwnedPort(port, ...) looks at the real listener(s): each must be (a) the recorded leader / a recorded member whose identity still matches, or (b) a process whose cwd is inside
//                  an allowed directory (a worktree) when the caller says so. A foreign listener => { ok:false, error:"FOREIGN_PROCESS" } and NOTHING is signalled.
//
// API (ES module):  startOwned(spec) · identify(meta) · stopOwned(metaOrFile, opts) · stopOwnedPort(port, opts) · statusOf(file) · listenerPids(port) · readMeta / writeMeta · withOwned(spec, fn)
import { spawn, spawnSync } from "node:child_process";
import { closeSync, mkdirSync, openSync, readFileSync, renameSync, rmSync, writeFileSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import net from "node:net";

const ENV = { ...process.env, LC_ALL: "C", LANG: "C" };
const sh = (cmd, args) => { const r = spawnSync(cmd, args, { encoding: "utf8", env: ENV, timeout: 15000 }); return r.status === 0 ? r.stdout : ""; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ------------------------------------------------------------------------------------------------------------------------------------------------ process facts (macOS + Linux)
/** a ZOMBIE (exited, not yet reaped by its parent) answers `kill -0` and still shows in `ps`, with a mangled command "(node)": it is DEAD for every purpose here */
export const isZombie = (pid) => sh("ps", ["-p", String(pid), "-o", "stat="]).trim().startsWith("Z");
export const exists = (pid) => { try { process.kill(Number(pid), 0); } catch (e) { if (e.code !== "EPERM") return false; } return !isZombie(pid); };
export const startTimeOf = (pid) => sh("ps", ["-p", String(pid), "-o", "lstart="]).trim().replace(/\s+/g, " ");
export const commandOf = (pid) => sh("ps", ["-ww", "-p", String(pid), "-o", "command="]).trim();
export const pgidOf = (pid) => { const t = sh("ps", ["-p", String(pid), "-o", "pgid="]).trim(); return t ? Number(t) : null; };
export const cwdOf = (pid) => { const t = sh("lsof", ["-a", "-d", "cwd", "-Fn", "-p", String(pid)]); const m = /^n(.+)$/m.exec(t); return m ? m[1] : null; };
export const listenerPids = (port) => sh("lsof", ["-nP", `-iTCP:${port}`, "-sTCP:LISTEN", "-t"]).split("\n").map((s) => s.trim()).filter(Boolean).map(Number);
/** every live process of a process group: [{pid, startTime, command}] */
export function groupMembers(pgid) {
  const rows = sh("ps", ["-axo", "pid=,pgid="]).split("\n").map((l) => l.trim().split(/\s+/).map(Number)).filter((r) => r.length === 2 && r[1] === Number(pgid));
  return rows.map(([pid]) => ({ pid, startTime: startTimeOf(pid), command: commandOf(pid) })).filter((m) => m.startTime);
}
const snapshot = (pid) => ({ pid: Number(pid), startTime: startTimeOf(pid), command: commandOf(pid) });
const sameProcess = (rec, live) => !!rec && !!live.startTime && rec.startTime === live.startTime && rec.command === live.command;

// ------------------------------------------------------------------------------------------------------------------------------------------------ metadata
export function writeMeta(file, meta) { mkdirSync(dirname(resolve(file)), { recursive: true }); const t = `${file}.tmp.${process.pid}`; writeFileSync(t, JSON.stringify(meta, null, 2) + "\n", { mode: 0o600 }); renameSync(t, file); }
export function readMeta(file) { try { return JSON.parse(readFileSync(file, "utf8")); } catch { return null; } }
const metaOf = (x) => (typeof x === "string" ? readMeta(x) : x);

/**
 * identify(meta) -> { state, reason }   state: OWNED | GONE | REUSED | UNKNOWN
 *   OWNED   the recorded pid is alive AND start time, command (and cwd when readable) all match
 *   GONE    no such pid
 *   REUSED  the pid is alive but is a DIFFERENT process (start time / command / cwd differ): it is foreign and must never be signalled
 *   UNKNOWN the metadata is unusable
 */
export function identify(meta) {
  if (!meta || !Number.isInteger(meta.pid) || !meta.startTime || !meta.command) return { state: "UNKNOWN", reason: "incomplete metadata" };
  if (!exists(meta.pid)) return { state: "GONE", reason: "no such pid" };
  const live = snapshot(meta.pid);
  if (!live.startTime) return { state: "GONE", reason: "pid vanished" };
  if (live.startTime !== meta.startTime) return { state: "REUSED", reason: `start time differs (recorded "${meta.startTime}", live "${live.startTime}")` };
  if (live.command !== meta.command) return { state: "REUSED", reason: "command differs" };
  if (meta.cwd) { const c = cwdOf(meta.pid); if (c && c !== meta.cwd) return { state: "REUSED", reason: `cwd differs (${c})` }; }
  return { state: "OWNED", reason: "pid, start time, command and cwd match" };
}

// ------------------------------------------------------------------------------------------------------------------------------------------------ start
const tcpOpen = (port) => new Promise((res) => { const s = net.connect({ host: "127.0.0.1", port }); const done = (v) => { s.destroy(); res(v); }; s.once("connect", () => done(true)); s.once("error", () => done(false)); s.setTimeout(1500, () => done(false)); });
const httpOk = async (url) => { try { const r = await fetch(url, { signal: AbortSignal.timeout(2500) }); return r.status < 500; } catch { return false; } };

/**
 * startOwned({ owner, name, cmd:[...], cwd, env, port, mode, stateFile, logFile, ready:{port|url, timeoutMs}, group=true, extra }) -> meta   (`extra`: free JSON stored in the metadata, e.g. a release id; `inheritEnv: false` = the child gets EXACTLY `env`, nothing of the caller's environment)
 * Refuses with Error code PORT_BUSY when `port` already has a listener (it would be someone else's), PROCESS_EXITED / NOT_READY when it does not come up (the child is then stopped).
 */
export async function startOwned(spec) {
  const { owner, name, cmd, cwd = process.cwd(), env = {}, port = null, mode = null, group = true } = spec;
  if (!owner || !name || !Array.isArray(cmd) || !cmd.length) throw Object.assign(new Error("startOwned needs owner, name and cmd[]"), { code: "USAGE" });
  const stateFile = resolve(spec.stateFile ?? `.run/owned/${owner}-${name}.json`); const logFile = resolve(spec.logFile ?? stateFile.replace(/\.json$/, "") + ".log");
  const prev = identify(readMeta(stateFile)); if (prev.state === "OWNED") throw Object.assign(new Error(`${owner}/${name} is already running (pid ${readMeta(stateFile).pid})`), { code: "ALREADY_RUNNING" });
  if (port != null) { const l = listenerPids(port); if (l.length) throw Object.assign(new Error(`port ${port} is already used by pid ${l.join(",")}: not ours, not started`), { code: "PORT_BUSY", pids: l }); }
  mkdirSync(dirname(logFile), { recursive: true }); const fd = openSync(logFile, "a");
  const child = spawn(cmd[0], cmd.slice(1), { cwd, env: spec.inheritEnv === false ? { ...env } : { ...process.env, ...env }, detached: group, stdio: ["ignore", fd, fd] }); closeSync(fd); child.unref();
  let exited = null; child.once("exit", (code, sig) => { exited = { code, sig }; });
  const need = spec.ready ?? (port != null ? { port } : null); const deadline = Date.now() + (need?.timeoutMs ?? 60000);
  const fail = async (code, msg) => { try { if (exists(child.pid)) process.kill(group ? -child.pid : child.pid, "SIGKILL"); } catch { /* gone */ } throw Object.assign(new Error(`${msg} (log: ${logFile})`), { code }); };
  if (need) for (;;) {
    if (exited) await fail("PROCESS_EXITED", `${owner}/${name} exited early (${JSON.stringify(exited)})`);
    if (need.port != null ? await tcpOpen(need.port) : need.url ? await httpOk(need.url) : true) break;
    if (Date.now() > deadline) await fail("NOT_READY", `${owner}/${name} was not ready in time`);
    await sleep(250);
  } else await sleep(200);
  const members = group ? groupMembers(child.pid) : [snapshot(child.pid)];
  const leader = members.find((m) => m.pid === child.pid) ?? snapshot(child.pid);
  if (!leader.startTime) await fail("PROCESS_EXITED", `${owner}/${name} is not running after start`);
  const listener = port != null ? listenerPids(port)[0] ?? null : null;
  const meta = { schema: 1, owner, name, mode, port, pid: child.pid, pgid: group ? pgidOf(child.pid) : null, startTime: leader.startTime, command: leader.command, cwd: cwdOf(child.pid) ?? resolve(cwd), listenerPid: listener, members, startedBy: process.pid, recordedAt: new Date().toISOString(), logFile, ...(spec.extra ? { extra: spec.extra } : {}) };
  writeMeta(stateFile, meta); return { ...meta, stateFile };
}

// ------------------------------------------------------------------------------------------------------------------------------------------------ stop
const alive = (m) => exists(m.pid) && sameProcess(m, snapshot(m.pid));
async function waitGone(list, ms) { const end = Date.now() + ms; while (Date.now() < end) { if (!list.some(alive)) return true; await sleep(150); } return !list.some(alive); }

/**
 * stopOwned(metaOrFile, { graceMs=8000, removeState=true }) -> { state, ... }
 *   STOPPED         the owned process (group) was signalled, TERM first then KILL after graceMs, and is verified gone
 *   ALREADY_GONE    nothing of it is running
 *   STALE_FOREIGN   the recorded pid is alive but is NOT our process (pid reuse): NOT signalled; the stale metadata is removed
 *   REFUSED         the metadata is unusable: nothing signalled
 */
export async function stopOwned(metaOrFile, opts = {}) {
  const { graceMs = 8000, removeState = true } = opts; const file = typeof metaOrFile === "string" ? metaOrFile : metaOrFile?.stateFile; const meta = metaOrFile && typeof metaOrFile === "object" ? metaOrFile : readMeta(metaOrFile);
  const clean = () => { if (removeState && file) rmSync(file, { force: true }); };
  const id = identify(meta);
  if (id.state === "UNKNOWN") return { state: "REFUSED", reason: id.reason };
  const orphans = (meta.members ?? []).filter((m) => m.pid !== meta.pid && alive(m));            // snapshot members that are provably the same processes as at readiness
  if (id.state === "REUSED") { const killed = await killEach(orphans, graceMs); clean(); return { state: "STALE_FOREIGN", reason: id.reason, foreignPid: meta.pid, orphansStopped: killed }; }
  if (id.state === "GONE") { const killed = await killEach(orphans, graceMs); clean(); return { state: killed.length ? "STOPPED" : "ALREADY_GONE", via: "orphans", pids: killed }; }
  // OWNED: the leader is ours, so its group (pgid == its pid) is ours; signal the group, else the leader alone
  const useGroup = meta.pgid && meta.pgid === meta.pid && pgidOf(meta.pid) === meta.pid;
  const group = useGroup ? groupMembers(meta.pid) : [snapshot(meta.pid)]; const everyone = [...group, ...orphans.filter((o) => !group.some((g) => g.pid === o.pid))];
  const sig = (s) => { try { if (useGroup) process.kill(-meta.pid, s); else process.kill(meta.pid, s); } catch { /* gone */ } for (const o of orphans) if (alive(o)) { try { process.kill(o.pid, s); } catch { /* gone */ } } };
  sig("SIGTERM"); let how = "TERM";
  if (!(await waitGone(everyone, graceMs))) { how = "KILL"; sig("SIGKILL"); await waitGone(everyone, 3000); }
  const left = everyone.filter(alive); if (left.length) return { state: "FAILED", reason: "still running after KILL", pids: left.map((m) => m.pid) };
  clean(); return { state: "STOPPED", signal: how, group: useGroup, pids: everyone.map((m) => m.pid) };
}
async function killEach(list, graceMs) { for (const m of list) { try { process.kill(m.pid, "SIGTERM"); } catch { /* gone */ } } if (!(await waitGone(list, graceMs))) for (const m of list) if (alive(m)) { try { process.kill(m.pid, "SIGKILL"); } catch { /* gone */ } } await waitGone(list, 3000); return list.filter((m) => !alive(m)).map((m) => m.pid); }

/**
 * stopOwnedPort(port, { stateFile | meta, cwdUnder, graceMs }) -> { ok, state | error }
 * Every listener of the port must be PROVEN ours: the recorded leader or a recorded member (identity matches), or - only when `cwdUnder` is given - a process whose cwd is inside that directory.
 * Any foreign listener => { ok:false, error:"FOREIGN_PROCESS", pid, command } and nothing is signalled.
 */
export async function stopOwnedPort(port, opts = {}) {
  const { graceMs = 8000, cwdUnder = null } = opts; const file = opts.stateFile ?? null; const meta = opts.meta ?? (file ? readMeta(file) : null);
  const pids = listenerPids(port); if (!pids.length) { if (file && meta) rmSync(file, { force: true }); return { ok: true, state: "NO_LISTENER" }; }
  const leaderOwned = meta && identify(meta).state === "OWNED";
  const proof = (pid) => {
    if (leaderOwned && (pid === meta.pid || (meta.pgid && pgidOf(pid) === meta.pgid))) return "recorded process group";
    if (meta && (meta.members ?? []).some((m) => m.pid === pid && alive(m))) return "recorded member";
    if (cwdUnder) { const c = cwdOf(pid); const base = resolve(cwdUnder); if (c && (c === base || c.startsWith(base + "/"))) return `cwd inside ${base}`; }
    return null;
  };
  const verdicts = pids.map((pid) => ({ pid, why: proof(pid) })); const foreign = verdicts.find((v) => !v.why);
  if (foreign) return { ok: false, error: "FOREIGN_PROCESS", pid: foreign.pid, command: commandOf(foreign.pid), cwd: cwdOf(foreign.pid), port };
  if (leaderOwned) { const r = await stopOwned(meta, { graceMs, removeState: !!file }); if (r.state === "FAILED") return { ok: false, error: "STOP_FAILED", ...r }; }
  const rest = listenerPids(port).filter((p) => verdicts.some((v) => v.pid === p));            // listeners proven ours by cwd only (or still up): TERM, then KILL, exactly those pids
  for (const p of rest) { try { process.kill(p, "SIGTERM"); } catch { /* gone */ } }
  const end = Date.now() + graceMs; while (Date.now() < end && rest.some(exists)) await sleep(150);
  for (const p of rest) if (exists(p)) { try { process.kill(p, "SIGKILL"); } catch { /* gone */ } }
  await sleep(300); const still = listenerPids(port);
  return still.length ? { ok: false, error: "PORT_STILL_BUSY", pids: still } : { ok: true, state: "STOPPED", pids: pids };
}

/** { state:'OWNED'|'GONE'|'REUSED'|'UNKNOWN', meta } for a state file */
export function statusOf(file) { const meta = readMeta(file); if (!meta) return { state: "GONE", reason: "no state file: nothing recorded, nothing owned", meta: null }; return { ...identify(meta), meta }; }

/** start, run fn(meta), and ALWAYS stop exactly what was started (try / finally); a cleanup failure is thrown, never swallowed */
export async function withOwned(spec, fn) {
  const meta = await startOwned(spec);
  try { return await fn(meta); }
  finally { const r = await stopOwned(meta, { removeState: true }); if (!["STOPPED", "ALREADY_GONE"].includes(r.state)) throw new Error(`owned process ${spec.owner}/${spec.name} was not stopped cleanly: ${JSON.stringify(r)}`); }
}
