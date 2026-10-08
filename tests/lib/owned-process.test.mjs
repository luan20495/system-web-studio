// @class: unit
// Proof that the C5 harness cleanup can never touch a process it did not start. Real processes on random free ports; nothing here touches a port or a process that this test did not create.
//   node --test tests/lib/owned-process.test.mjs        (also run by `npm run test:unit` through tests/lib/owned-process.wire.test.ts)
import test, { after } from "node:test";
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative, resolve } from "node:path";
import net from "node:net";
import { PortBusyError, assertPortFree, commandOf, exists, freePort, identify, pgidOf, readState, signalOwned, spawnOwned, startTimeOf, statusOf, stopOwned, withOwned, writeState } from "./owned-process.mjs";

const ROOT = resolve(new URL("../..", import.meta.url).pathname);
const TMP = mkdtempSync(join(tmpdir(), "owned-proc-test-"));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const foreignKids = [];                                                  // processes THIS TEST created to play "somebody else's": reaped by pid at the end (their own pid, nothing else)

/** a long-lived stranger. Its argv contains `next start -H 127.0.0.1 -p <port>`; when `listen` it holds the TCP port. NOT started through the helper, detached so a bug cannot hit the test runner's group. */
async function foreignNext(listen = true) {
  const port = await freePort(); const script = join(TMP, `foreign-${port}.cjs`);
  writeFileSync(script, listen ? `require("net").createServer(()=>{}).listen(${port},"127.0.0.1");setInterval(()=>{},1e6)` : `setInterval(()=>{},1e6)`);
  const child = spawn(process.execPath, [script, "next", "start", "-H", "127.0.0.1", "-p", String(port)], { detached: true, stdio: "ignore" }); child.unref(); foreignKids.push(child);
  await new Promise((r) => child.once("spawn", r));
  if (listen) for (let i = 0; i < 50; i++) { if (await new Promise((res) => { const s = net.connect(port, "127.0.0.1"); s.once("connect", () => { s.destroy(); res(true); }); s.once("error", () => res(false)); })) break; await sleep(100); }
  return { pid: child.pid, port, child, alive: () => exists(child.pid) && !isReaped(child), listening: () => new Promise((res) => { const s = net.connect(port, "127.0.0.1"); s.once("connect", () => { s.destroy(); res(true); }); s.once("error", () => res(false)); }) };
}
const isReaped = (c) => c.exitCode !== null || c.signalCode !== null;
const idle = (extra = []) => ["-e", "setInterval(()=>{},1e6)", "--", ...extra];                // an unrelated long-lived node process
after(() => { for (const c of foreignKids) { try { if (!isReaped(c)) process.kill(c.pid, "SIGKILL"); } catch { /* gone */ } } rmSync(TMP, { recursive: true, force: true }); });

// ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ FOREIGN_NEXT_SURVIVES
test("FOREIGN_NEXT_SURVIVES: cleaning an unrelated owned process leaves a foreign `next start` alone", async () => {
  const foreign = await foreignNext();
  assert.ok(commandOf(foreign.pid).includes("next start"), "the stranger must look like `next start`");
  const mine = await spawnOwned(process.execPath, idle(["owned-unrelated"]), { stateFile: join(TMP, "mine.json"), name: "mine" });
  assert.equal(identify(mine).state, "OWNED");
  const r = await stopOwned(mine.stateFile, { graceMs: 4000 });
  assert.equal(r.state, "STOPPED"); assert.equal(exists(mine.pid), false, "the owned process is gone");
  assert.ok(exists(foreign.pid), "foreign `next start` survived the cleanup"); assert.ok(await foreign.listening(), "foreign still listens");
});

test("FOREIGN_NEXT_SURVIVES: a start that finds its port occupied FAILS (names the port and the holder) and kills nothing", async () => {
  const foreign = await foreignNext();
  await assert.rejects(assertPortFree(foreign.port), (e) => e instanceof PortBusyError && e.message.includes(String(foreign.port)) && /next start|pid \d+/.test(e.message));
  await assert.rejects(spawnOwned(process.execPath, idle(["wants-the-port"]), { port: foreign.port, stateFile: join(TMP, "wants.json") }), (e) => e.code === "PORT_BUSY" && e.message.includes(`port ${foreign.port}`));
  assert.equal(readState(join(TMP, "wants.json")), null, "nothing was started, nothing recorded");
  assert.ok(exists(foreign.pid) && await foreign.listening(), "foreign `next start` survived the refused start");
});

test("FOREIGN_NEXT_SURVIVES: the CLI exits 3 with a message naming the port, and the foreign listener survives (start AND stop of an unrelated state)", async () => {
  const foreign = await foreignNext(); const cli = join(ROOT, "tests/lib/owned-process-cli.mjs");
  const st = spawnSync(process.execPath, [cli, "start", "--state", join(TMP, "cli.json"), "--port", String(foreign.port), "--", process.execPath, ...idle()], { encoding: "utf8" });
  assert.equal(st.status, 3, st.stderr); assert.match(st.stderr, new RegExp(`port ${foreign.port}`)); assert.match(st.stderr, /Not ours|not ours/);
  const sp = spawnSync(process.execPath, [cli, "stop", "--state", join(TMP, "cli.json")], { encoding: "utf8" }); assert.equal(sp.status, 0);          // no state = nothing to stop
  assert.ok(exists(foreign.pid) && await foreign.listening(), "foreign survived");
});

// ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ STALE_PID_SAFE
test("STALE_PID_SAFE: a PID file that points at a live unrelated process is never signalled (wrong start time, wrong command, both, group form)", async () => {
  const stranger = await foreignNext(false);
  const live = { pid: stranger.pid, startedAt: startTimeOf(stranger.pid), command: commandOf(stranger.pid) };
  const cases = {
    "pid only (what an old .pid file knows)": { pid: stranger.pid, startedAt: "Mon Jan 1 00:00:00 2001", command: "next start" },
    "right pid + right command, wrong start time": { ...live, startedAt: "Mon Jan 1 00:00:00 2001" },
    "right pid + right start time, wrong command": { ...live, command: "/usr/bin/some-other-program --flag" },
    "group form: pgid claimed, wrong start time": { ...live, startedAt: "Tue Feb 2 02:02:02 2002", pgid: stranger.pid, scope: "group" },
    "no identity at all": { pid: stranger.pid },
  };
  for (const [label, state] of Object.entries(cases)) {
    const file = join(TMP, `stale-${Math.abs(label.length * 31)}.json`); writeState(file, state);
    const r = await stopOwned(file, { graceMs: 500 }); assert.equal(r.state, "REFUSED", `${label}: ${JSON.stringify(r)}`);
    const s = signalOwned(state, "SIGSTOP"); assert.equal(s.ok, false, `${label}: signalOwned must refuse`);
    assert.ok(stranger.alive(), `${label}: the stranger is alive`); assert.notEqual(statusOf(file).state, "OWNED");
  }
  const jobState = spawnSync("ps", ["-o", "stat=", "-p", String(stranger.pid)], { encoding: "utf8" }).stdout.trim(); assert.ok(!jobState.startsWith("T"), `never stopped (SIGSTOP) either: ${jobState}`);
  const ok = identify(live); assert.equal(ok.state, "OWNED", "control: the SAME recorded identity with the true start time and command IS accepted");   // proves the refusals above are about identity, not a broken validator
});

test("STALE_PID_SAFE: pid reuse is detected - a state of a process that died and whose pid now belongs to a stranger is refused; a dead pid is ALREADY_GONE", async () => {
  const mine = await spawnOwned(process.execPath, idle(["will-die"]), { stateFile: join(TMP, "reuse.json") });
  const recorded = { ...readState(mine.stateFile) };
  assert.equal((await stopOwned(mine.stateFile)).state, "STOPPED");
  assert.equal((await stopOwned(writeAndGet("gone.json", recorded))).state, "ALREADY_GONE", "dead pid: nothing to do, nothing signalled");
  const stranger = await foreignNext(false);                                            // simulate reuse: the recorded identity of the dead process, attached to the stranger's pid
  const reused = writeAndGet("reused.json", { ...recorded, pid: stranger.pid, pgid: stranger.pid });
  const r = await stopOwned(reused); assert.equal(r.state, "REFUSED"); assert.match(r.reason, /start time differs|command differs/); assert.ok(stranger.alive());
});
function writeAndGet(name, state) { const f = join(TMP, name); writeState(f, state); return f; }

test("STALE_PID_SAFE: a zombie / exited owned process counts as gone, and a group whose leader died is still stopped through its members", async () => {
  const mine = await spawnOwned("sh", ["-c", `${process.execPath} -e 'setInterval(()=>{},1e6)' -- orphan-child & echo $! > ${join(TMP, "orphan.pid")}; sleep 0.3; exit 0`], { stateFile: join(TMP, "grp.json") });
  for (let i = 0; i < 30 && exists(mine.pid); i++) await sleep(100);                  // the leader (sh) exits, the node child keeps the process group alive
  const kid = Number(readFileSync(join(TMP, "orphan.pid"), "utf8")); assert.ok(exists(kid), "orphan child alive");
  assert.equal(pgidOf(kid), mine.pgid, "the child is in the owned group");
  const r = await stopOwned(mine.stateFile, { graceMs: 4000 }); assert.equal(r.state, "STOPPED"); assert.equal(exists(kid), false, "member of the owned group stopped");
});

// ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ stop semantics
test("stopOwned: SIGTERM first; SIGKILL only the owned group if it ignores SIGTERM; a paused (SIGSTOP) process is stopped too; the group's children go with it", async () => {
  const stranger = await foreignNext(false);
  const code = `process.on("SIGTERM",()=>{});require("child_process").spawn(process.execPath,["-e","process.on('SIGTERM',()=>{});setInterval(()=>{},1e6)"],{stdio:"ignore"});setInterval(()=>{},1e6)`;
  const mine = await spawnOwned(process.execPath, ["-e", code, "--", "stubborn"], { stateFile: join(TMP, "stubborn.json") });
  await sleep(500); const kids = spawnSync("ps", ["-axo", "pid=,pgid="], { encoding: "utf8" }).stdout.split("\n").map((l) => l.trim().split(/\s+/).map(Number)).filter((r) => r[1] === mine.pgid).map((r) => r[0]);
  assert.ok(kids.length >= 2, `leader + child in the group (${kids})`);
  assert.equal(signalOwned(mine.stateFile, "SIGSTOP").ok, true);
  const r = await stopOwned(mine.stateFile, { graceMs: 700, killMs: 4000 });
  assert.equal(r.state, "STOPPED"); assert.equal(r.signal, "KILL", "it ignored SIGTERM, so SIGKILL of the owned group was needed");
  for (const k of kids) assert.equal(exists(k), false, `pid ${k} gone`);
  assert.ok(stranger.alive(), "unrelated process untouched");
});

test("withOwned: the owned process is stopped in finally, also when the body throws", async () => {
  let pid = null;
  await assert.rejects(withOwned(process.execPath, idle(["with-owned"]), { stateFile: join(TMP, "wo.json") }, async (o) => { pid = o.pid; assert.ok(exists(pid)); throw new Error("body failed"); }), /body failed/);
  assert.equal(exists(pid), false); assert.equal(readState(join(TMP, "wo.json")), null, "state removed");
});

// ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ HARNESS_PID_SCOPED
const psAll = () => new Map(spawnSync("ps", ["-axo", "pid=,command="], { encoding: "utf8", maxBuffer: 1 << 26 }).stdout.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => { const i = l.indexOf(" "); return [Number(l.slice(0, i)), l.slice(i + 1)]; }));
test("HARNESS_PID_SCOPED: start/stop of the browser harness server touches only the PID it spawned (process list before/after)", async () => {
  const dir = join(TMP, "site"); mkdirSync(dir); writeFileSync(join(dir, "index.html"), "<!doctype html><title>x</title>ok");
  const bystander = await foreignNext(false); const lookalike = spawn(process.execPath, ["-e", "setInterval(()=>{},1e6)", "--", "python3", "-m", "http.server", "4000", "--bind", "127.0.0.1"], { detached: true, stdio: "ignore" }); lookalike.unref(); foreignKids.push(lookalike);
  await sleep(300);
  const hs = join(ROOT, "tests/browser/harness-server.mjs"); const before = psAll();
  const st = spawnSync(process.execPath, [hs, "start", "--dir", dir], { encoding: "utf8", cwd: ROOT, timeout: 30000 }); assert.equal(st.status, 0, st.stderr + st.stdout);
  const pid = Number(/HARNESS_PID=(\d+)/.exec(st.stdout)[1]), port = Number(/HARNESS_PORT=(\d+)/.exec(st.stdout)[1]);
  const res = await fetch(`http://127.0.0.1:${port}/index.html`); assert.equal(res.status, 200); assert.match(await res.text(), /ok/);
  assert.equal((await fetch(`http://127.0.0.1:${port}/../../etc/passwd`)).status === 200, false, "no path traversal");
  const during = psAll();
  assert.ok(!before.has(pid) && during.has(pid), "the harness pid is new and alive");
  // every process of THIS harness (other people's processes come and go on a shared machine; they are not in this set)
  const mineNow = [...during.entries()].filter(([, c]) => c.includes(hs) && c.includes(dir)).map(([p]) => p);
  assert.deepEqual(mineNow, [pid], "exactly one process belongs to the harness: the pid it spawned");
  const sp = spawnSync(process.execPath, [hs, "stop", "--dir", dir], { encoding: "utf8", cwd: ROOT, timeout: 30000 }); assert.equal(sp.status, 0, sp.stderr + sp.stdout);
  const afterList = psAll(); assert.equal(afterList.has(pid), false, "the harness pid is gone");
  // look-alikes that existed BEFORE (ours and anybody else's `next start` / `http.server`): none of the ones this test created may have vanished
  const lookalikes = [...before.entries()].filter(([, c]) => /next start|next-server|http\.server/.test(c) && !c.includes(hs)).map(([p]) => p);
  assert.ok(lookalikes.includes(bystander.pid) && lookalikes.includes(lookalike.pid), "both foreign look-alikes were visible before");
  assert.ok(lookalikes.filter((p) => foreignKids.some((c) => c.pid === p)).every((p) => afterList.has(p)), "none of the look-alikes this test created vanished");
  assert.ok(bystander.alive() && exists(lookalike.pid), "the foreign `next start` and the `http.server` look-alike survived");
});

test("HARNESS_PID_SCOPED: `run` stops its server in finally (exit code of the command is propagated), and refuses a busy --port without touching the holder", async () => {
  const dir = join(TMP, "site2"); mkdirSync(dir); writeFileSync(join(dir, "index.html"), "ok");
  const hs = join(ROOT, "tests/browser/harness-server.mjs"); const port = await freePort();
  const probe = `fetch(process.env.HARNESS_URL).then(r=>{console.log("S="+r.status);process.exit(r.status===200?7:1)})`;
  const r = spawnSync(process.execPath, [hs, "run", "--dir", dir, "--port", String(port), "--", process.execPath, "-e", probe], { encoding: "utf8", cwd: ROOT, timeout: 30000 });
  assert.equal(r.status, 7, r.stderr + r.stdout); assert.match(r.stdout, /S=200/);
  assert.equal(await new Promise((res) => { const s = net.connect(port, "127.0.0.1"); s.once("connect", () => { s.destroy(); res(true); }); s.once("error", () => res(false)); }), false, "server gone after run");
  const foreign = await foreignNext();
  const busy = spawnSync(process.execPath, [hs, "run", "--dir", dir, "--port", String(foreign.port), "--", process.execPath, "-e", "process.exit(0)"], { encoding: "utf8", cwd: ROOT, timeout: 30000 });
  assert.equal(busy.status, 3, busy.stderr); assert.match(busy.stderr, new RegExp(`port ${foreign.port}`)); assert.ok(foreign.alive() && await foreign.listening());
});

// ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ static guard
/** the unsafe patterns of the 2026-10-08 incident. Built from fragments so this file does not trip its own scan. */
const K = "kill";
export const UNSAFE = [
  [new RegExp(`\\bp${K}\\b`), `p${K}`], [new RegExp(`\\b${K}all\\b`), `${K}all`],
  [new RegExp(`lsof[^\\n|]*\\|\\s*(?:xargs\\s+)?${K}\\b`), `lsof | ${K}`], [new RegExp(`\\b${K}\\s+(?:-\\w+\\s+)?\\$\\(\\s*(?:lsof|pgrep)`), `${K} $(lsof|pgrep)`], [new RegExp(`\\b${K}\\s+\\$\\(\\s*(?:cat|<)[^)]*\\.pid`), `${K} $(cat x.pid) unvalidated`],
  [new RegExp(`\\b${K}_port\\b`), `${K}_port helper`], [new RegExp(`\\bxargs\\s+${K}\\b`), `xargs ${K}`],
  [new RegExp(`\\b${K}\\s+(?:-\\w+\\s+)?\\$p\\b`), `${K} $p (pid taken from a port lookup)`],
];
export function scan(text) { const hits = []; text.split("\n").forEach((l, i) => { for (const [re, name] of UNSAFE) if (re.test(l)) hits.push({ line: i + 1, name, text: l.trim().slice(0, 120) }); }); return hits; }
function c5ToolingFiles() {
  const ls = spawnSync("git", ["ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", "tests", "e2e", "docs/parallel/c5", "package.json", "scripts/test-unit.mjs", "scripts/test-classify.mjs", "scripts/ui-audit.mjs", "scripts/ui-c6-evidence.mjs", "scripts/ui-dialog-check.mjs", "scripts/ui-studio-shots.mjs"], { cwd: ROOT, encoding: "utf8" });
  let files = ls.status === 0 ? ls.stdout.split("\0").filter(Boolean) : null;
  if (!files) { files = []; const walk = (d) => { for (const n of readdirSync(join(ROOT, d), { withFileTypes: true })) { if (n.name === "node_modules" || n.name.startsWith(".")) continue; const p = join(d, n.name); if (n.isDirectory()) walk(p); else files.push(p); } }; walk("tests"); walk("e2e"); walk("docs/parallel/c5"); }
  const SELF = new Set(["tests/lib/owned-process.test.mjs"]);                       // defines the patterns
  return files.filter((f) => /\.(sh|mjs|cjs|js|ts|tsx|json)$/.test(f) || /^tests\/.*\.md$/.test(f)).filter((f) => !SELF.has(f) && !/\/evidence\//.test(f));
}
test("guard: the scanner detects every unsafe pattern (self-test, so a green guard means something)", () => {
  const bad = [`${"p" + K} -f "next start"`, `${K}all node`, `lsof -ti tcp:3003 -sTCP:LISTEN | xargs ${K}`, `${K} $(lsof -ti tcp:3003)`, `${K} $(pgrep -f next)`, `${K} $(cat run/x.pid)`, `${K}_port 3003`, `echo 1 | xargs ${K}`, `${K} -STOP $p`];
  for (const b of bad) assert.ok(scan(b).length > 0, `must flag: ${b}`);
  const good = ["lsof -nP -iTCP:3003 -sTCP:LISTEN", "node owned-process-cli.mjs stop --state x.json", "process.kill(-pgid, 'SIGTERM')", "child.kill('SIGINT')", "kill -0 is not a signal"];
  for (const g of good) assert.deepEqual(scan(g), [], `must not flag: ${g}`);
});
test("guard: no pkill / killall / `lsof | kill` / kill-by-port / unvalidated `kill $(cat x.pid)` in C5-owned tracked tooling", () => {
  const files = c5ToolingFiles(); assert.ok(files.length > 20, `scanned ${files.length} files`);
  assert.ok(files.includes("docs/parallel/c5/e2e-stack.sh") && files.includes("tests/lib/owned-process.mjs"));
  const problems = [];
  for (const f of files) { let t; try { t = readFileSync(join(ROOT, f), "utf8"); } catch { continue; } for (const h of scan(t)) problems.push(`${f}:${h.line} [${h.name}] ${h.text}`); }
  assert.deepEqual(problems, [], "unsafe process cleanup in C5-owned tooling:\n" + problems.join("\n"));
});
test("guard: no unmanaged background `http.server` / `next start ... &` instructions in tests/ (use harness-server.mjs / owned-process-cli.mjs)", () => {
  const problems = [];
  for (const f of c5ToolingFiles().filter((x) => x.startsWith("tests/") && x !== "tests/lib/owned-process.test.mjs")) {
    readFileSync(join(ROOT, f), "utf8").split("\n").forEach((l, i) => { if (/http\.server[^\n]*&\s*\)?\s*(&&|;|$)/.test(l) || /next start[^\n]*&\s*\)?\s*(;|$)/.test(l)) problems.push(`${f}:${i + 1} ${l.trim().slice(0, 120)}`); });
  }
  assert.deepEqual(problems, [], problems.join("\n"));
});
test("e2e-stack.sh: stops/pauses only through owned state files (no listener lookup feeds a signal)", () => {
  const sh = readFileSync(join(ROOT, "docs/parallel/c5/e2e-stack.sh"), "utf8");
  assert.ok(/owned-process-cli\.mjs/.test(sh), "e2e-stack.sh must use the owned-process CLI");
  assert.ok(!new RegExp(`\\b${K}\\b\\s`).test(sh.replace(/^\s*#.*$/gm, "").replace(/#.*$/gm, "")), "no direct kill in e2e-stack.sh; every signal goes through the validated CLI");
});

// ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ e2e-stack.sh behaviour
const STACK = join(ROOT, "docs/parallel/c5/e2e-stack.sh");
const stackEnv = (name, extra = {}) => { const d = join(TMP, `stack-${name}`); mkdirSync(d, { recursive: true }); writeFileSync(join(d, "stack.env"), "JAVA_HOME=/nonexistent\nRENDER_TOKEN=x\n"); return { dir: d, env: { ...process.env, E2E_STACK_DIR: d, E2E_STACK_NAME: `t${name}`, E2E_STOP_WAIT_SECS: "2", ...extra } }; };
const stack = (cmd, env) => spawnSync("bash", [STACK, ...cmd], { env, encoding: "utf8", timeout: 60000 });
test("e2e-stack.sh: `studio-up` on an occupied port fails naming the port and leaves the foreign `next start` alone (before any build)", async () => {
  const foreign = await foreignNext(); const { env } = stackEnv("studio", { E2E_STUDIO_PORT: String(foreign.port) });
  const r = stack(["studio-up"], env);
  assert.notEqual(r.status, 0); assert.match(r.stderr, new RegExp(`port ${foreign.port}`)); assert.match(r.stderr, /NOT touched|not ours/i);
  assert.ok(foreign.alive() && await foreign.listening(), "foreign `next start` survived studio-up");
});
test("e2e-stack.sh: `backend-down` / `down` / `render-pause` with only a FOREIGN process around never signal it (stale state files included)", async () => {
  const foreign = await foreignNext(); const { dir, env } = stackEnv("down", { E2E_API_PORT: String(foreign.port), E2E_RENDER_PORT: String(foreign.port), E2E_STUDIO_PORT: String(foreign.port) });
  mkdirSync(join(dir, "run"));
  for (const n of ["backend", "backend-app", "render", "studio"]) writeState(join(dir, "run", `${n}.json`), { pid: foreign.pid, pgid: foreign.pid, scope: "group", startedAt: "Mon Jan 1 00:00:00 2001", command: "next start" });   // a PID file whose pid was reused by the stranger
  const bd = stack(["backend-down"], env); assert.notEqual(bd.status, 0, "the API port is still held by the stranger: backend-down must say so"); assert.match(bd.stderr, new RegExp(`port ${foreign.port}`)); assert.match(bd.stderr, /NOT touched/);
  const rp = stack(["render-pause"], env); assert.notEqual(rp.status, 0);
  const dn = stack(["down"], env); assert.equal(dn.status, 0, dn.stderr);
  assert.ok(foreign.alive() && await foreign.listening(), "the stranger survived backend-down + render-pause + down");
  const pause = spawnSync("ps", ["-o", "stat=", "-p", String(foreign.pid)], { encoding: "utf8" }).stdout.trim(); assert.ok(!pause.startsWith("T"), "never stopped by SIGSTOP");
});
test("e2e-stack.sh: render worker lifecycle through the CLI - start on a free port, pause/resume/stop touch only it", async () => {
  const stranger = await foreignNext(false); const port = await freePort(); const { dir } = stackEnv("life");
  const cli = join(ROOT, "tests/lib/owned-process-cli.mjs"); const sf = join(dir, "run", "render.json");
  const srv = `require("http").createServer((q,s)=>s.end("ok")).listen(${port},"127.0.0.1")`;
  assert.equal(spawnSync(process.execPath, [cli, "start", "--state", sf, "--port", String(port), "--", process.execPath, "-e", srv], { encoding: "utf8" }).status, 0);
  for (let i = 0; i < 50 && !(await fetch(`http://127.0.0.1:${port}/`).then(() => true, () => false)); i++) await sleep(100);
  assert.equal(spawnSync(process.execPath, [cli, "refresh", "--state", sf]).status, 0);
  assert.equal(spawnSync(process.execPath, [cli, "signal", "--state", sf, "--sig", "STOP"]).status, 0);
  assert.equal(spawnSync(process.execPath, [cli, "signal", "--state", sf, "--sig", "CONT"]).status, 0);
  const sp = spawnSync(process.execPath, [cli, "stop", "--state", sf], { encoding: "utf8" }); assert.equal(sp.status, 0, sp.stderr); assert.match(sp.stdout, /STOPPED/);
  assert.equal(await fetch(`http://127.0.0.1:${port}/`).then(() => true, () => false), false, "the owned server is gone");
  assert.ok(stranger.alive(), "unrelated process untouched");
});
void relative;
