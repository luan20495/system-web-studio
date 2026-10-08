// @class: integration
// Isolation tests of the owned-process helper (D-C0-48): REAL processes on REAL temporary ports, no mocks. The point of every test is the same: what the helper does NOT own survives.
//   foreign A = started by the test itself, outside the helper (like another agent's server); owned B = started through the helper.
//   node --test tests/infra/owned-process.test.mjs        (npm run test:infra:processes)
// Nothing here ever runs a broad name-based kill; the "control" tests only LIST (pgrep) what such a command would have matched.
import test from "node:test";
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync, existsSync, realpathSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import net from "node:net";
import { startOwned, stopOwned, stopOwnedPort, identify, statusOf, listenerPids, exists, startTimeOf, commandOf, cwdOf, isZombie, readMeta, writeMeta, withOwned } from "../../scripts/lib/owned-process.mjs";

const REPO = new URL("../..", import.meta.url).pathname.replace(/\/$/, "");
const CLI = join(REPO, "scripts/owned-process.mjs");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const freePort = () => new Promise((res) => { const s = net.createServer(); s.listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => res(p)); }); });
const work = realpathSync(mkdtempSync(join(tmpdir(), "owned-")));
const foreigners = [];                                              // every foreign process the TESTS created: killed by pid at the end (never by name)
test.after(() => { for (const p of foreigners) { try { process.kill(p, "SIGKILL"); } catch { /* gone */ } } rmSync(work, { recursive: true, force: true }); });

// a tiny HTTP server whose COMMAND LINE contains `next start -p PORT` (what a Next portal looks like to a name-based kill of "next start")
const SERVER = `require("http").createServer((q,r)=>r.end("up")).listen(+process.argv.at(-1),"127.0.0.1")`;
// the port is the LAST argument so SERVER can read it; keep the "next start" words in front of it
const nextLike2 = (port, tag = "") => [process.execPath, "-e", `require("http").createServer((q,r)=>r.end("up")).listen(+process.argv.at(-1),"127.0.0.1")`, "--", `ISO${tag}`, "next", "start", "-p", String(port)];
async function foreignNext(port, tag) { const c = nextLike2(port, tag); const ch = spawn(c[0], c.slice(1), { detached: true, stdio: "ignore" }); ch.unref(); foreigners.push(ch.pid); await untilUp(port); return ch.pid; }
async function foreignHttp(port) { const ch = spawn("python3", ["-m", "http.server", String(port), "--bind", "127.0.0.1"], { detached: true, stdio: "ignore", cwd: work }); ch.unref(); foreigners.push(ch.pid); await untilUp(port); return ch.pid; }
async function untilUp(port) { for (let i = 0; i < 80; i++) { if (await probe(port)) return; await sleep(100); } throw new Error(`nothing listens on ${port}`); }
const probe = (port) => new Promise((res) => { const s = net.connect({ host: "127.0.0.1", port }); s.once("connect", () => { s.destroy(); res(true); }); s.once("error", () => res(false)); });
const owned = (name, port, extra = {}) => ({ owner: "iso", name, stateFile: join(work, `${name}.json`), cwd: work, port, ...extra });
const SIG_IGNORING = `process.on("SIGTERM",()=>{});require("http").createServer((q,r)=>r.end("stubborn")).listen(+process.argv.at(-1),"127.0.0.1")`;

test("control: the OLD broad pattern would have matched BOTH the foreign and the owned server (this is the hazard; the test only lists, it kills nothing)", async () => {
  const [a, b] = [await freePort(), await freePort()]; const A = await foreignNext(a, "ctl"); const B = await startOwned({ ...owned("ctl-b", b), cmd: nextLike2(b, "ctl") });
  const matched = spawnSync("pgrep", ["-f", "next start"], { encoding: "utf8" }).stdout.split("\n").map(Number);
  assert.ok(matched.includes(A) && matched.includes(B.pid), "a name-based kill of \"next start\" would have hit both: that is why name-based kills are forbidden");
  assert.equal((await stopOwned(B)).state, "STOPPED"); assert.ok(exists(A));
});

test("A. foreign Next-like server A survives while owned B (same command shape) is stopped", async () => {
  const [a, b] = [await freePort(), await freePort()]; const A = await foreignNext(a, "A"); const B = await startOwned({ ...owned("a-b", b), cmd: nextLike2(b, "A") });
  assert.ok(await probe(a) && await probe(b)); const r = await stopOwned(B);
  assert.equal(r.state, "STOPPED"); assert.ok(!(await probe(b)), "B is stopped and its port is free"); assert.ok(exists(A) && (await probe(a)), "foreign A is untouched and still serving");
});

test("B. a foreign python http.server survives while the owned http.server is stopped", async () => {
  const [a, b] = [await freePort(), await freePort()]; const A = await foreignHttp(a);
  const B = await startOwned({ ...owned("b-b", b), cmd: ["python3", "-m", "http.server", String(b), "--bind", "127.0.0.1"] });
  assert.equal((await stopOwned(B)).state, "STOPPED"); assert.ok(!(await probe(b))); assert.ok(exists(A) && (await probe(a)));
});

test("C. stale / reused pid: the metadata of a dead owned process now points at a FOREIGN live pid => REUSED, the foreign process survives, the stale metadata is removed", async () => {
  const [a, b] = [await freePort(), await freePort()]; const B = await startOwned({ ...owned("c-b", b), cmd: nextLike2(b, "C") }); const recorded = readMeta(B.stateFile);
  assert.equal((await stopOwned(B, { removeState: false })).state, "STOPPED"); assert.equal(identify(recorded).state, "GONE");
  const A = await foreignNext(a, "C");                                                     // an unrelated process now exists, as if the OS had handed our pid to it
  const stale = { ...recorded, pid: A, pgid: null, members: [] }; const file = join(work, "c-stale.json"); writeMeta(file, stale);
  assert.equal(statusOf(file).state, "REUSED", "same command shape, different start time / pid identity => not ours");
  const r = await stopOwned(file); assert.equal(r.state, "STALE_FOREIGN"); assert.ok(exists(A) && (await probe(a)), "the foreign process was NOT signalled"); assert.ok(!existsSync(file), "the stale metadata is gone");
});

test("C2. even an IDENTICAL command line does not make a younger process ours: the start time differs", async () => {
  const a = await freePort(); const first = await foreignNext(a, "C2"); const t1 = startTimeOf(first); const cmd1 = (await import("../../scripts/lib/owned-process.mjs")).commandOf(first);
  await foreignNextStop(first, a); await sleep(1300); const b = a; const second = await foreignNext(b, "C2");                    // same port, same argv, later start second
  const meta = { schema: 1, owner: "iso", name: "c2", pid: second, pgid: null, startTime: t1, command: cmd1, cwd: null, members: [] };
  assert.equal(identify(meta).state, "REUSED"); const r = await stopOwned(meta, { removeState: false }); assert.equal(r.state, "STALE_FOREIGN"); assert.ok(exists(second));
});
async function foreignNextStop(pid, port) { process.kill(pid, "SIGKILL"); for (let i = 0; i < 50 && (await probe(port)); i++) await sleep(100); }

test("C3. a pid that is gone is ALREADY_GONE and nothing else is touched; unusable metadata is REFUSED", async () => {
  const [b, a] = [await freePort(), await freePort()]; const A = await foreignNext(a, "C3"); const B = await startOwned({ ...owned("c3-b", b), cmd: nextLike2(b, "C3") });
  process.kill(B.pid, "SIGKILL"); for (let i = 0; i < 50 && exists(B.pid); i++) await sleep(100);
  const r = await stopOwned(B.stateFile); assert.ok(["ALREADY_GONE", "STOPPED"].includes(r.state)); assert.ok(exists(A));
  assert.equal((await stopOwned({ pid: 1 })).state, "REFUSED", "metadata without identity is never acted on"); assert.ok(exists(1));
});

test("D. exact owned pid: stopped (TERM); a process that IGNORES TERM is stopped with KILL after the grace period; both are verified gone", async () => {
  const b = await freePort(); const B = await startOwned({ ...owned("d-b", b), cmd: nextLike2(b, "D") });
  const r = await stopOwned(B, { graceMs: 4000 }); assert.equal(r.state, "STOPPED"); assert.equal(r.signal, "TERM"); assert.ok(!exists(B.pid));
  const c = await freePort(); const C = await startOwned({ ...owned("d-c", c), cmd: [process.execPath, "-e", SIG_IGNORING, "--", "next", "start", "-p", String(c)] });
  const r2 = await stopOwned(C, { graceMs: 800 }); assert.equal(r2.state, "STOPPED"); assert.equal(r2.signal, "KILL", "TERM was ignored, KILL was needed"); assert.ok(!exists(C.pid)); assert.ok(!(await probe(c)));
});

test("E. owned process GROUP: the leader and its children are stopped together; a foreign process in ANOTHER group survives; with the leader already dead the orphans are stopped one by one", async () => {
  const a = await freePort(); const A = await foreignNext(a, "E");
  const mk = async (name) => { const p = await freePort(); const script = `node -e 'setInterval(()=>{},1000)' & node -e 'setInterval(()=>{},1000)' & ${process.execPath.replace(/'/g, "")} -e 'require("http").createServer((q,r)=>r.end("g")).listen(${p},"127.0.0.1")' & wait`; return [p, await startOwned({ ...owned(name, p), cmd: ["bash", "-c", script] })]; };
  const [p1, G] = await mk("e-g"); assert.ok(G.members.length >= 3, `the group has several members (${G.members.length})`); assert.equal(G.pgid, G.pid);
  const r = await stopOwned(G); assert.equal(r.state, "STOPPED"); assert.equal(r.group, true); for (const m of G.members) assert.ok(!exists(m.pid) || startTimeOf(m.pid) !== m.startTime, `member ${m.pid} stopped`); assert.ok(!(await probe(p1)));
  assert.ok(exists(A) && (await probe(a)), "the foreign process in another group survives the group stop");
  const [p2, H] = await mk("e-h"); process.kill(H.pid, "SIGKILL"); await sleep(400);                    // the leader dies, the children are orphaned and keep the group number alive
  assert.equal(identify(H).state, "GONE"); const orphansBefore = H.members.filter((m) => m.pid !== H.pid && exists(m.pid)).length; assert.ok(orphansBefore >= 1, "orphans survive their leader");
  const r2 = await stopOwned(H); assert.ok(["STOPPED"].includes(r2.state), JSON.stringify(r2)); assert.ok(!(await probe(p2))); assert.ok(exists(A), "foreign survives the orphan clean-up too");
});

test("F. a FOREIGN process occupies the requested port: stop-port refuses (FOREIGN_PROCESS) and kills nothing; starting on that port is refused (PORT_BUSY)", async () => {
  const a = await freePort(); const A = await foreignNext(a, "F");
  const r = await stopOwnedPort(a, {}); assert.equal(r.ok, false); assert.equal(r.error, "FOREIGN_PROCESS"); assert.equal(r.pid, A); assert.ok(exists(A) && (await probe(a)));
  const op = await freePort(); const meta = await startOwned({ ...owned("f-other", op), cmd: nextLike2(op, "F") });
  const r2 = await stopOwnedPort(a, { stateFile: meta.stateFile }); assert.equal(r2.error, "FOREIGN_PROCESS", "our metadata does not cover someone else's listener"); assert.ok(exists(A) && exists(meta.pid), "neither the foreign nor our own process was touched"); await stopOwned(meta);
  await assert.rejects(startOwned({ ...owned("f-busy", a), cmd: nextLike2(a, "F") }), (e) => e.code === "PORT_BUSY"); assert.ok(exists(A) && (await probe(a)), "still untouched");
  const cli = spawnSync(process.execPath, [CLI, "stop-port", "--port", String(a), "--cwd-under", join(work, "nowhere")], { encoding: "utf8" }); assert.equal(cli.status, 3, cli.stdout); assert.match(cli.stdout, /FOREIGN_PROCESS/); assert.ok(exists(A));
});

test("G. a KNOWN owned listener on the port is stopped safely (by recorded identity, and by cwd inside the worktree), a foreign one next to it is not", async () => {
  const [b, a] = [await freePort(), await freePort()]; const A = await foreignNext(a, "G"); const B = await startOwned({ ...owned("g-b", b), cmd: nextLike2(b, "G") });
  const r = await stopOwnedPort(b, { stateFile: B.stateFile }); assert.equal(r.ok, true); assert.equal(r.state, "STOPPED"); assert.ok(!(await probe(b)) && !exists(B.pid)); assert.ok(exists(A));
  // ownership by working directory (what scripts/stop-local.sh uses): a process whose cwd is inside THIS worktree is ours, one elsewhere is not
  const wt = join(work, "worktree"), elsewhere = join(work, "elsewhere"); mkdirSync(wt, { recursive: true }); mkdirSync(elsewhere, { recursive: true });
  const [c, d] = [await freePort(), await freePort()]; const mine = spawn(nextLike2(c)[0], nextLike2(c).slice(1), { cwd: wt, detached: true, stdio: "ignore" }); mine.unref(); foreigners.push(mine.pid); await untilUp(c);
  const theirs = spawn(nextLike2(d)[0], nextLike2(d).slice(1), { cwd: elsewhere, detached: true, stdio: "ignore" }); theirs.unref(); foreigners.push(theirs.pid); await untilUp(d);
  assert.equal((await stopOwnedPort(d, { cwdUnder: wt })).error, "FOREIGN_PROCESS", "cwd outside the worktree: refused"); assert.ok(exists(theirs.pid));
  assert.equal((await stopOwnedPort(c, { cwdUnder: wt })).state, "STOPPED", "cwd inside the worktree: stopped"); assert.ok(!(await probe(c))); assert.ok(exists(theirs.pid) && exists(A));
});

test("start refuses a port someone else holds, cleans up a process that never becomes ready, and never leaves a state file for it", async () => {
  const dead = await freePort(); const f = join(work, "never.json");
  await assert.rejects(startOwned({ owner: "iso", name: "never", stateFile: f, cwd: work, port: dead, cmd: ["bash", "-c", "sleep 60"], ready: { port: dead, timeoutMs: 1500 } }), (e) => e.code === "NOT_READY");
  assert.ok(!existsSync(f), "no metadata for a process that was never ready"); await sleep(300);
  await assert.rejects(startOwned({ owner: "iso", name: "quick", stateFile: join(work, "q.json"), cwd: work, port: await freePort(), cmd: ["bash", "-c", "exit 3"] }), (e) => e.code === "PROCESS_EXITED");
});

test("withOwned: the process is stopped in `finally` even when the body throws; a second start of the same name is refused while it runs", async () => {
  const p = await freePort(); let pid;
  await assert.rejects(withOwned({ ...owned("w", p), cmd: nextLike2(p, "W") }, async (m) => { pid = m.pid; await assert.rejects(startOwned({ ...owned("w", await freePort()), cmd: nextLike2(await freePort(), "W2") }), (e) => e.code === "ALREADY_RUNNING"); throw new Error("body failed"); }), /body failed/);
  assert.ok(!exists(pid) && !(await probe(p)), "cleaned up although the body threw");
});

test("CLI (what shell scripts use): start -> status -> stop-port by recorded identity -> status GONE; a foreign listener gives exit 3", async () => {
  const p = await freePort(); const f = join(work, "cli.json"); const a = await freePort(); const A = await foreignNext(a, "CLI");
  const s = spawnSync(process.execPath, [CLI, "start", "--owner", "iso", "--name", "cli", "--state", f, "--cwd", work, "--port", String(p), "--timeout", "20", "--", ...nextLike2(p, "CLI")], { encoding: "utf8" });
  assert.equal(s.status, 0, s.stdout + s.stderr); const pid = JSON.parse(s.stdout).pid; assert.ok(await probe(p));
  assert.equal(spawnSync(process.execPath, [CLI, "status", "--state", f]).status, 0);
  const sp = spawnSync(process.execPath, [CLI, "stop-port", "--port", String(p), "--state", f], { encoding: "utf8" }); assert.equal(sp.status, 0, sp.stdout); assert.ok(!exists(pid));
  assert.equal(spawnSync(process.execPath, [CLI, "status", "--state", f]).status, 1, "stopped and removed: GONE");
  assert.equal(spawnSync(process.execPath, [CLI, "stop-port", "--port", String(a), "--state", f], { encoding: "utf8" }).status, 3); assert.ok(exists(A));
  assert.equal(spawnSync(process.execPath, [CLI, "stop-port", "--port", String(a)], { encoding: "utf8" }).status, 64, "a port alone proves nothing: usage error, nothing killed"); assert.ok(exists(A));
});

test("scripts/stop-local.sh (rewritten, D-C0-48): stops what runs from ITS checkout, leaves a foreign listener alone, and never signals a pid-file pid that is not from this checkout", async () => {
  const root = join(work, "slroot"), other = join(work, "other-checkout"); mkdirSync(join(root, "scripts/lib"), { recursive: true }); mkdirSync(join(root, "backend"), { recursive: true }); mkdirSync(join(root, ".run"), { recursive: true }); mkdirSync(other, { recursive: true });
  for (const f of ["stop-local.sh", "owned-process.mjs", "lib/owned-process.mjs"]) writeFileSync(join(root, "scripts", f), readFileSync(join(REPO, "scripts", f)));
  writeFileSync(join(root, "scripts/_env.sh"), `ROOT="$(cd "$(dirname "\${BASH_SOURCE[0]}")/.." && pwd)"; cd "$ROOT"\nexport FRONTEND_PORT="\${FRONTEND_PORT:?}"\n`);   // the real _env.sh needs .env and the whole stack
  const [api, front, render] = [await freePort(), await freePort(), await freePort()]; const code = nextLike2;
  const spawnIn = async (cwd, port) => { const c = code(port, "SL"); const ch = spawn(c[0], c.slice(1), { cwd, detached: true, stdio: "ignore" }); ch.unref(); foreigners.push(ch.pid); await untilUp(port); return ch.pid; };
  const ourApi = await spawnIn(join(root, "backend"), api);                                  // like the Gradle-forked API: cwd = <checkout>/backend
  const ourFront = await spawnIn(root, front);
  const foreignRender = await spawnIn(other, render);                                        // a listener on the render port that belongs to ANOTHER checkout
  const bystander = await spawnIn(other, await freePort());                                  // a foreign process that a stale pid file will point at
  writeFileSync(join(root, ".run/render.pid"), String(bystander)); writeFileSync(join(root, ".run/runner.pid"), "999999");
  const r = spawnSync("bash", [join(root, "scripts/stop-local.sh")], { cwd: root, encoding: "utf8", env: { ...process.env, STOP_API_PORT: String(api), FRONTEND_PORT: String(front), RENDER_PORT: String(render) }, timeout: 90000 });
  assert.equal(r.status, 0, r.stdout + r.stderr); assert.match(r.stdout, /stopped/);
  assert.ok(!exists(ourApi) && !(await probe(api)), "the API of this checkout was stopped"); assert.ok(!exists(ourFront) && !(await probe(front)), "the UI of this checkout was stopped");
  assert.ok(exists(foreignRender) && (await probe(render)), "the listener of ANOTHER checkout on the render port survived"); assert.match(r.stderr, new RegExp(`port ${render}: used by a process that is NOT from this checkout`));
  assert.ok(exists(bystander), "the pid file pointed at a foreign process (as after pid reuse): it was not signalled"); assert.match(r.stderr, /not a running process of this checkout/);
  assert.ok(!existsSync(join(root, ".run/render.pid")) && !existsSync(join(root, ".run/runner.pid")), "the stale pid files were removed");
});

test("identity: start time, command and cwd are each checked ON THEIR OWN (the other two correct, one wrong => REUSED, never signalled); the all-correct control is OWNED", async () => {
  const a = await freePort(); const A = await foreignNext(a, "ID"); const st = startTimeOf(A), cmd = commandOf(A), cwd = cwdOf(A); assert.ok(st && cmd && cwd, "the live process is observable");
  const base = { schema: 1, owner: "iso", name: "id", pid: A, pgid: null, members: [] };
  assert.equal(identify({ ...base, startTime: st, command: cmd, cwd }).state, "OWNED", "control: all three match");
  const wrong = {
    "start time only": { startTime: "Mon Jan  1 00:00:00 2001", command: cmd, cwd },
    "command only": { startTime: st, command: cmd + " --some-other-argument", cwd },
    "cwd only": { startTime: st, command: cmd, cwd: join(work, "a-directory-it-is-not-in") },
  };
  for (const [what, m] of Object.entries(wrong)) {
    const meta = { ...base, ...m }; assert.equal(identify(meta).state, "REUSED", `${what} differs => REUSED`);
    const r = await stopOwned(meta, { removeState: false }); assert.equal(r.state, "STALE_FOREIGN", what); assert.ok(exists(A) && (await probe(a)), `${what}: the foreign process was not signalled`);
  }
});

test("zombie handling, explicitly: a child that exited but was never reaped answers kill -0 and shows in ps, yet counts as GONE; its (foreign) parent is untouched", async () => {
  const parent = spawn("python3", ["-c", "import subprocess,time; subprocess.Popen(['sleep','0.4']); time.sleep(60)"], { detached: true, stdio: "ignore" }); parent.unref(); foreigners.push(parent.pid);
  let child = null; for (let i = 0; i < 50 && !child; i++) { await sleep(100); child = Number(spawnSync("pgrep", ["-P", String(parent.pid)], { encoding: "utf8" }).stdout.trim().split("\n")[0]) || null; }
  assert.ok(child, "the parent started a child"); const rec = { startTime: startTimeOf(child), command: commandOf(child) }; assert.ok(rec.startTime, "observed while alive");
  for (let i = 0; i < 40 && !isZombie(child); i++) await sleep(100);
  assert.ok(isZombie(child), "the child exited and was not reaped: a zombie"); assert.doesNotThrow(() => process.kill(child, 0), "kill -0 still succeeds for a zombie (why it cannot be trusted)");
  assert.equal(exists(child), false, "exists() counts a zombie as dead");
  const meta = { schema: 1, owner: "iso", name: "zombie", pid: child, pgid: null, members: [], ...rec, cwd: null };
  assert.equal(identify(meta).state, "GONE"); assert.equal((await stopOwned(meta, { removeState: false })).state, "ALREADY_GONE"); assert.ok(exists(parent.pid), "the foreign parent was not touched");
});
