// @class: integration
// Explicit public deployment pinning (D-C0-49): PROCESS RECOVERY != DEPLOYMENT. REAL scripts, a REAL git repository (commits A, B, C), REAL processes / ports / release directories;
// only the Next.js compiler and server are replaced by a tiny fake `next` bin placed in the sandbox node_modules (it writes BUILD_ID + a marker into the dist and serves /login, an asset,
// /api/v1/auth/config and /__info). Every test asserts BOTH what runs and what was (never) built: `builds.log` has one line per built portal.
//   node --test tests/infra/public-pinning.test.mjs          (npm run test:infra:public)
import test from "node:test";
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync, appendFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import net from "node:net";
import { startTimeOf, exists } from "../../scripts/lib/owned-process.mjs";
import { context, buildEnvOf } from "../../scripts/lib/public-release.mjs";

const REPO = new URL("../..", import.meta.url).pathname.replace(/\/$/, "");
const NAMES = ["platform", "admin", "studio"];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const freePort = () => new Promise((res) => { const s = net.createServer(); s.listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => res(p)); }); });
const sandboxes = []; const strays = [];
test.after(() => { for (const p of strays) { try { process.kill(p, "SIGKILL"); } catch { /* gone */ } } for (const sb of sandboxes) { spawnSync("bash", [join(sb.d, "scripts/public-portals.sh"), "down"], { cwd: sb.d, env: env(sb), timeout: 60000 }); rmSync(sb.d, { recursive: true, force: true }); } });

const FAKE_NEXT = String.raw`#!/usr/bin/env node
const fs = require("fs"), path = require("path"), http = require("http");
const [cmd, ...rest] = process.argv.slice(2); const SB = process.env.SANDBOX; const dist = process.env.NEXT_DIST_DIR || ".next"; const has = (f) => fs.existsSync(path.join(SB, f));
if (cmd === "build") {
  if (has("FAIL_BUILD")) { console.error("simulated build failure"); process.exit(1); }
  const mark = fs.readFileSync("marker.txt", "utf8").trim(); fs.mkdirSync(path.join(dist, "static/chunks"), { recursive: true });
  fs.writeFileSync(path.join(dist, "BUILD_ID"), Math.random().toString(36).slice(2) + Date.now()); fs.writeFileSync(path.join(dist, "MARK"), mark); fs.writeFileSync(path.join(dist, "static/chunks/app.js"), "//" + mark);
  fs.writeFileSync(path.join(dist, "BAKED"), JSON.stringify({ api: process.env.API_PROXY_TARGET, admin: process.env.NEXT_PUBLIC_PORTAL_URL_ADMIN }));
  fs.appendFileSync(path.join(SB, "builds.log"), path.basename(process.cwd()) + " " + mark + " " + dist + "\n"); process.exit(0);
}
if (cmd === "start") {
  const port = Number(rest[rest.indexOf("-p") + 1]); const mark = fs.readFileSync(path.join(dist, "MARK"), "utf8").trim();
  if (has("BREAK_START_ANY")) process.exit(1);
  if (has("BREAK_MARK") && fs.readFileSync(path.join(SB, "BREAK_MARK"), "utf8").trim() === mark && String(port) === fs.readFileSync(path.join(SB, "BREAK_PORT"), "utf8").trim()) process.exit(1);
  fs.appendFileSync(path.join(SB, "starts.log"), [path.basename(process.cwd()), mark, port, process.cwd()].join(" ") + "\n");
  http.createServer((q, r) => {
    if (q.url === "/login") { r.setHeader("content-security-policy", "default-src 'self'"); return r.end('<html><script src="/_next/static/chunks/app.js"></script>' + mark + "</html>"); }
    if (q.url.startsWith("/_next/static/")) { try { return r.end(fs.readFileSync(path.join(dist, q.url.replace("/_next/", "")))); } catch { r.statusCode = 404; return r.end("no"); } }
    if (q.url === "/__info") return r.end(JSON.stringify({ mark, cwd: process.cwd(), dist, hsts: process.env.STUDIO_HSTS, sites: process.env.SITES_ORIGIN, files: process.env.MINIO_PUBLIC_ENDPOINT, baked: JSON.parse(fs.readFileSync(path.join(dist, "BAKED"), "utf8")) }));
    r.end("{}");
  }).listen(port, "127.0.0.1"); setInterval(() => {}, 1e6);
}
`;

async function sandbox() {
  const d = realpathSync(mkdtempSync(join(tmpdir(), "pin-"))); const ports = { platform: await freePort(), admin: await freePort(), studio: await freePort() }; const sb = { d, ports }; sandboxes.push(sb);
  for (const f of ["scripts/public-portals.sh", "scripts/public-release.mjs", "scripts/lib/public-release.mjs", "scripts/lib/owned-process.mjs", "scripts/portal-fingerprint.mjs", "scripts/watchdog.sh"]) { mkdirSync(join(d, f, ".."), { recursive: true }); cpSync(join(REPO, f), join(d, f)); }
  writeFileSync(join(d, "package.json"), `{"name":"sandbox"}`); writeFileSync(join(d, "package-lock.json"), `{"lock":1}`); writeFileSync(join(d, ".gitignore"), ".run/\nnode_modules/\n*.log\nFAIL_BUILD\nBREAK_*\n");
  for (const n of NAMES) { mkdirSync(join(d, "apps", n), { recursive: true }); writeFileSync(join(d, "apps", n, "next.config.ts"), "export default {};\n"); writeFileSync(join(d, "apps", n, "tsconfig.json"), "{}\n"); }
  mkdirSync(join(d, "packages/ui"), { recursive: true }); writeFileSync(join(d, "packages/ui/index.ts"), "export const ui = 1;\n");
  mkdirSync(join(d, "node_modules/next/dist/bin"), { recursive: true }); writeFileSync(join(d, "node_modules/next/dist/bin/next"), FAKE_NEXT); chmodSync(join(d, "node_modules/next/dist/bin/next"), 0o755);
  mkdirSync(join(d, ".run/public"), { recursive: true });
  writeFileSync(join(d, ".run/public/public.env"), `PUBLIC_HOST=studio.example\nPUBLIC_PLATFORM_HOST=platform.example\nPUBLIC_ADMIN_HOST=admin.example\nPORTAL_PLATFORM_PORT_PUBLIC=${ports.platform}\nPORTAL_ADMIN_PORT_PUBLIC=${ports.admin}\nPORTAL_STUDIO_PORT_PUBLIC=${ports.studio}\nAPI_PORT=18081\nSITES_HOST=sites.example\nPUBLIC_FILES_HOST=files.example\nRENDER_TOKEN=TOPSECRET-SENTINEL-9f3a\nSECRETS_MASTER_KEY=ANOTHER-SECRET-SENTINEL\n`);
  git(sb, "init", "-q"); git(sb, "config", "user.email", "t@t"); git(sb, "config", "user.name", "t"); return sb;
}
const git = (sb, ...a) => spawnSync("git", a, { cwd: sb.d, encoding: "utf8" });
const env = (sb, extra = {}) => ({ ...process.env, SANDBOX: sb.d, PUBLIC_RELEASE_DEPS: "clone", PUBLIC_RELEASE_START_TIMEOUT_MS: "15000", PUBLIC_RELEASE_GRACE_MS: "1500", ...extra });
const pub = (sb, args, extra = {}) => { const r = spawnSync("bash", [join(sb.d, "scripts/public-portals.sh"), ...args], { cwd: sb.d, env: env(sb, extra), encoding: "utf8", timeout: 240000 }); return { status: r.status, out: (r.stdout ?? "") + (r.stderr ?? ""), stdout: r.stdout ?? "", stderr: r.stderr ?? "" }; };
function commit(sb, label) { for (const n of NAMES) writeFileSync(join(sb.d, "apps", n, "marker.txt"), `${n}-${label}\n`); git(sb, "add", "-A"); git(sb, "commit", "-qm", `commit ${label}`); return git(sb, "rev-parse", "HEAD").stdout.trim(); }
const info = async (port) => { for (let i = 0; i < 2; i++) { try { return await (await fetch(`http://127.0.0.1:${port}/__info`, { signal: AbortSignal.timeout(3000), headers: { connection: "close" } })).json(); } catch { await sleep(150); } } return null; };
const lines = (sb, f) => (existsSync(join(sb.d, f)) ? readFileSync(join(sb.d, f), "utf8").split("\n").filter(Boolean) : []);
const approved = (sb) => { try { return JSON.parse(readFileSync(join(sb.d, ".run/public/approved.json"), "utf8")); } catch { return null; } };
const releases = (sb) => { try { return readdirSync(join(sb.d, ".run/public/releases")).sort(); } catch { return []; } };
const owned = (sb, n) => JSON.parse(readFileSync(join(sb.d, `.run/public/portal-${n}.owned.json`), "utf8"));
const status = (sb) => JSON.parse(pub(sb, ["status", "--json"]).stdout);
const marksOf = async (sb) => { const m = await Promise.all(NAMES.map(async (n) => (await info(sb.ports[n]))?.mark ?? null)); if (process.env.PIN_DEBUG && m.includes(null)) { const ls = (p) => spawnSync("lsof", ["-nP", `-iTCP:${p}`, "-sTCP:LISTEN"], { encoding: "utf8" }).stdout.split("\n").slice(1).join("|"); console.error("DEBUG marks", m, "listeners", NAMES.map((n) => `${n}:${ls(sb.ports[n])}`), "starts.tail", lines(sb, "starts.log").slice(-4), "owned", NAMES.map((n) => { try { const o = owned(sb, n); return `${n}:${o.pid}${exists(o.pid) ? "+" : "-"}`; } catch { return `${n}:none`; } })); } return m; };
const stateOf = (sb) => Object.fromEntries(status(sb).rows.map((r) => [r.portal, r.state]));
const buildsOf = (sb, label) => lines(sb, "builds.log").filter((l) => l.includes(`-${label} `)).length;

// ================================================================================================================================ the story: matrix tests 1-9
test("D-C0-49 matrix: approved release A, integration B/C, crash recovery, deploy, restart, rollback, failed candidates", async (t) => {
  const sb = await sandbox(); const A = commit(sb, "A"); let B, C;

  await t.test("0. FAIL CLOSED without an approved release: `up` / `restart` / `status` refuse (exit 5), nothing is built or started, HEAD is never assumed; `deploy` needs an explicit source", async () => {
    for (const cmd of ["up", "restart"]) { const r = pub(sb, [cmd]); assert.equal(r.status, 5, cmd + ": " + r.out); assert.match(r.out, /NO_APPROVED_RELEASE/); }
    assert.equal(pub(sb, ["status"]).status, 5); assert.equal(lines(sb, "builds.log").length, 0, "nothing was built"); assert.equal(await info(sb.ports.platform), null, "nothing runs");
    const d = pub(sb, ["deploy"]); assert.equal(d.status, 64, d.out); assert.match(d.out, /explicit source/); assert.equal(lines(sb, "builds.log").length, 0, "no implicit HEAD build");
    const u = pub(sb, ["deploy", "no-such-ref"]); assert.equal(u.status, 64); assert.deepEqual(releases(sb), []);
    const b = pub(sb, ["build"]); assert.equal(b.status, 64); assert.match(b.out, /deploy <sha>/);
  });

  await t.test("TEST 1. approved=A, integration=B: the public portals start A (B is never built or served)", async () => {
    const r = pub(sb, ["deploy", A]); assert.equal(r.status, 0, r.out); assert.match(r.out, /NOT zero-downtime/);
    const ap = approved(sb); assert.ok(ap.releaseId.startsWith(A.slice(0, 12)) && ap.previousKnownGood === null); assert.equal(buildsOf(sb, "A"), 3);
    B = commit(sb, "B"); assert.equal(pub(sb, ["down"]).status, 0); assert.deepEqual(await marksOf(sb), [null, null, null], "stopped");
    const up = pub(sb, ["up"]); assert.equal(up.status, 0, up.out); assert.deepEqual(await marksOf(sb), ["platform-A", "admin-A", "studio-A"], "A runs although HEAD is B"); assert.equal(buildsOf(sb, "B"), 0, "B was never built");
    const s = status(sb); assert.ok(s.rows.every((x) => x.state === "CURRENT_APPROVED" && x.runningSource === A.slice(0, 12) && x.approvedSource === A.slice(0, 12) && x.integrationSource === B.slice(0, 12)), JSON.stringify(s.rows[0]));
    assert.equal(s.behind, 1, "BEHIND_INTEGRATION is information, not an instruction to deploy"); const txt = pub(sb, ["status"]); assert.equal(txt.status, 0); assert.match(txt.stdout, /CURRENT_APPROVED \(BEHIND_INTEGRATION \+1\)/);
    for (const col of ["PORTAL", "PORT", "PID", "HEALTH", "RUNNING", "APPROVED", "INTEGRATION", "BUILD", "CONFIG", "STATE"]) assert.match(txt.stdout, new RegExp(col));
  });

  await t.test("TEST 2. a portal dies: recovery restarts the APPROVED release A from its release directory; B is never built or served", async () => {
    const m = owned(sb, "admin"); process.kill(m.pid, "SIGKILL"); await sleep(500); assert.equal(await info(sb.ports.admin), null);
    assert.equal(stateOf(sb).admin, "STOPPED"); assert.equal(stateOf(sb).platform, "CURRENT_APPROVED");
    const n0 = lines(sb, "builds.log").length; const r = pub(sb, ["up"]); assert.equal(r.status, 0, r.out);
    assert.equal((await info(sb.ports.admin)).mark, "admin-A"); assert.equal(lines(sb, "builds.log").length, n0, "recovery built nothing"); assert.equal(buildsOf(sb, "B"), 0);
    const last = lines(sb, "starts.log").filter((l) => l.startsWith("admin ")).at(-1).split(" "); assert.match(last[3], /\.run\/public\/releases\/[0-9a-f]{12}-[0-9a-f]{8}\/apps\/admin$/, "started from the RELEASE directory, not from the working tree"); assert.ok(!last[3].startsWith(join(sb.d, "apps")));
    for (const n of NAMES) process.kill(owned(sb, n).pid, "SIGKILL"); await sleep(500);                                  // all three die
    assert.equal(pub(sb, ["up"]).status, 0); assert.deepEqual(await marksOf(sb), ["platform-A", "admin-A", "studio-A"]); assert.equal(buildsOf(sb, "B"), 0);
  });

  await t.test("TEST 3. explicit deploy B: candidate built + verified on temporary ports, approved moves to B, B runs, A is the previous known-good (retained)", async () => {
    const startsBefore = lines(sb, "starts.log").length; const r = pub(sb, ["deploy", B]); assert.equal(r.status, 0, r.out);
    const ap = approved(sb); assert.ok(ap.releaseId.startsWith(B.slice(0, 12))); assert.ok(ap.previousKnownGood.startsWith(A.slice(0, 12))); assert.equal(buildsOf(sb, "B"), 3, "candidate built once per portal");
    const extra = lines(sb, "starts.log").slice(startsBefore).filter((l) => !Object.values(sb.ports).includes(Number(l.split(" ")[2]))); assert.equal(extra.length, 3, "the candidate was proven on 3 TEMPORARY ports before the pointer moved");
    assert.deepEqual(await marksOf(sb), ["platform-B", "admin-B", "studio-B"]); assert.equal(releases(sb).length, 2, "approved + previous known-good retained");
    assert.ok(ap.history.at(-1).action === "deploy" && ap.history.at(-1).from.startsWith(A.slice(0, 12))); assert.ok(ap.releaseJsonSha256);
  });

  await t.test("TEST 4. restart after deploy: B remains B (the SAME approved release; nothing is rebuilt)", async () => {
    const pids = NAMES.map((n) => owned(sb, n).pid); const n0 = lines(sb, "builds.log").length; commit(sb, "B2-working-tree-noise");      // HEAD moves on while B is approved
    const r = pub(sb, ["restart"]); assert.equal(r.status, 0, r.out); assert.match(r.out, /no build, no source change/);
    assert.deepEqual(await marksOf(sb), ["platform-B", "admin-B", "studio-B"]); assert.equal(lines(sb, "builds.log").length, n0); NAMES.forEach((n, i) => assert.notEqual(owned(sb, n).pid, pids[i], n + " was really restarted"));
  });

  await t.test("TEST 5. rollback: the approved release returns to A (artifacts retained), A runs, B is dropped; a second rollback has nothing to return to", async () => {
    const r = pub(sb, ["rollback"]); assert.equal(r.status, 0, r.out); const ap = approved(sb); assert.ok(ap.releaseId.startsWith(A.slice(0, 12))); assert.equal(ap.previousKnownGood, null); assert.deepEqual(await marksOf(sb), ["platform-A", "admin-A", "studio-A"]);
    assert.equal(releases(sb).length, 1, "only the approved release is kept after a rollback"); assert.equal(ap.history.at(-1).action, "rollback"); assert.equal(pub(sb, ["rollback"]).status, 1);
    const again = pub(sb, ["deploy", B]); assert.equal(again.status, 0, again.out); assert.deepEqual(await marksOf(sb), ["platform-B", "admin-B", "studio-B"]); assert.ok(approved(sb).previousKnownGood.startsWith(A.slice(0, 12)));
  });

  await t.test("TEST 7. integration advances to C: the public portals stay B across a restart and a crash recovery", async () => {
    C = commit(sb, "C"); const n0 = lines(sb, "builds.log").length;
    assert.equal(pub(sb, ["restart"]).status, 0); assert.deepEqual(await marksOf(sb), ["platform-B", "admin-B", "studio-B"]);
    process.kill(owned(sb, "studio").pid, "SIGKILL"); await sleep(400); assert.equal(pub(sb, ["up"]).status, 0); assert.equal((await info(sb.ports.studio)).mark, "studio-B");
    assert.equal(lines(sb, "builds.log").length, n0, "C was never built"); assert.equal(buildsOf(sb, "C"), 0); assert.equal(status(sb).behind, 2, "B2 and C are ahead of the approved B");
  });

  await t.test("status CRASH_LOOP: a stopped portal is reported CRASH_LOOP (not STOPPED) while the watchdog's crash-loop marker exists; an explicit `up` recovers and clears it", async () => {
    writeFileSync(join(sb.d, ".run/public/crash-loop.json"), JSON.stringify({ since: new Date().toISOString(), restarts: 5, windowSec: 900, down: "portal:" + sb.ports.admin })); process.kill(owned(sb, "admin").pid, "SIGKILL"); await sleep(500);
    assert.equal(stateOf(sb).admin, "CRASH_LOOP"); assert.equal(stateOf(sb).platform, "CURRENT_APPROVED"); assert.match(pub(sb, ["status"]).stdout, /crash loop since/);
    assert.equal(pub(sb, ["up"]).status, 0); assert.equal(stateOf(sb).admin, "CURRENT_APPROVED"); assert.ok(!existsSync(join(sb.d, ".run/public/crash-loop.json")), "recovery clears the marker"); assert.equal((await info(sb.ports.admin)).mark, "admin-B");
  });

  await t.test("TEST 8. candidate BUILD failure: the approved release stays B and the healthy B processes are untouched (same pids)", async () => {
    const pids = NAMES.map((n) => owned(sb, n).pid); const before = approved(sb); const rel0 = releases(sb); writeFileSync(join(sb.d, "FAIL_BUILD"), "1");
    const r = pub(sb, ["deploy", C]); assert.equal(r.status, 7, r.out); assert.match(r.out, /CANDIDATE_BUILD_FAILED/); rmSync(join(sb.d, "FAIL_BUILD"));
    assert.deepEqual(approved(sb), before); NAMES.forEach((n, i) => assert.equal(owned(sb, n).pid, pids[i])); assert.deepEqual(await marksOf(sb), ["platform-B", "admin-B", "studio-B"]); assert.deepEqual(releases(sb), rel0, "the failed candidate left no release directory");
  });

  await t.test("TEST 9a. candidate START failure (it does not come up on the temporary ports): approved stays B, the running B is untouched", async () => {
    const pids = NAMES.map((n) => owned(sb, n).pid); const before = approved(sb); writeFileSync(join(sb.d, "BREAK_START_ANY"), "1");
    const r = pub(sb, ["deploy", C]); assert.equal(r.status, 7, r.out); assert.match(r.out, /CANDIDATE_VALIDATION_FAILED/); rmSync(join(sb.d, "BREAK_START_ANY"));
    assert.deepEqual(approved(sb), before); NAMES.forEach((n, i) => assert.equal(owned(sb, n).pid, pids[i])); assert.deepEqual(await marksOf(sb), ["platform-B", "admin-B", "studio-B"]); assert.equal(releases(sb).length, 2);
  });

  await t.test("TEST 9b. the candidate passes the proof but FAILS to start on the real port: the pointer moves back, B runs again on all three portals (automatic rollback)", async () => {
    const before = readFileSync(join(sb.d, ".run/public/approved.json"), "utf8"); writeFileSync(join(sb.d, "BREAK_MARK"), "studio-C"); writeFileSync(join(sb.d, "BREAK_PORT"), String(sb.ports.studio));
    const r = pub(sb, ["deploy", C]); assert.equal(r.status, 7, r.out); assert.match(r.out, /ACTIVATION_FAILED_ROLLED_BACK/); rmSync(join(sb.d, "BREAK_MARK")); rmSync(join(sb.d, "BREAK_PORT"));
    assert.equal(readFileSync(join(sb.d, ".run/public/approved.json"), "utf8"), before, "the approved record is byte-identical to before");
    assert.deepEqual(await marksOf(sb), ["platform-B", "admin-B", "studio-B"], "B runs on all three real ports again"); assert.deepEqual(stateOf(sb), { platform: "CURRENT_APPROVED", admin: "CURRENT_APPROVED", studio: "CURRENT_APPROVED" }); assert.equal(releases(sb).length, 2, "the failed candidate was dropped");
  });

  await t.test("retention + successful deploy C: approved C, previous B; A is pruned; the approved release is never deleted; explicit `prune` keeps approved + previous", async () => {
    const r = pub(sb, ["deploy", C]); assert.equal(r.status, 0, r.out); const ap = approved(sb); assert.ok(ap.releaseId.startsWith(C.slice(0, 12))); assert.ok(ap.previousKnownGood.startsWith(B.slice(0, 12)));
    assert.deepEqual(releases(sb).sort(), [ap.releaseId, ap.previousKnownGood].sort(), "A (older) was pruned"); assert.ok(existsSync(join(sb.d, ".run/public/releases", ap.releaseId, "release.json")));
    const p = pub(sb, ["prune"]); assert.equal(p.status, 0); assert.equal(releases(sb).length, 2); assert.deepEqual(await marksOf(sb), ["platform-C", "admin-C", "studio-C"]);
    assert.equal(pub(sb, ["deploy", C]).status, 0, "deploying the already approved release is a no-op"); assert.match(pub(sb, ["deploy", C]).out, /already approved/);
  });

  await t.test("config pinning: a restart uses the PINNED runtime env even if public.env changed; the same SHA under a different build config is a NEW release built by an explicit deploy", async () => {
    const f = join(sb.d, ".run/public/public.env"); writeFileSync(f, readFileSync(f, "utf8").replace("SITES_HOST=sites.example", "SITES_HOST=sites-changed.example").replace("PUBLIC_ADMIN_HOST=admin.example", "PUBLIC_ADMIN_HOST=admin-changed.example"));
    assert.equal(pub(sb, ["restart"]).status, 0); const i = await info(sb.ports.admin); assert.equal(i.sites, "https://sites.example", "runtime env pinned"); assert.equal(i.baked.admin, "https://admin.example", "build env pinned");
    const n0 = lines(sb, "builds.log").length; const id0 = approved(sb).releaseId; const r = pub(sb, ["deploy", C]); assert.equal(r.status, 0, r.out);
    assert.notEqual(approved(sb).releaseId, id0, "different configuration fingerprint => a different release"); assert.equal(lines(sb, "builds.log").length, n0 + 3); assert.equal((await info(sb.ports.admin)).sites, "https://sites-changed.example"); assert.equal((await info(sb.ports.admin)).baked.admin, "https://admin-changed.example");
  });

  await t.test("security: no secret of public.env is copied into any approved / release metadata", () => {
    const hits = []; const walk = (d) => { for (const e of readdirSync(d, { withFileTypes: true })) { const p = join(d, e.name); if (e.isDirectory()) { if (e.name === "node_modules" || e.name === ".next") continue; walk(p); } else if (/\.json$/.test(e.name) || /build-.*\.log$/.test(e.name)) { const t = readFileSync(p, "utf8"); if (/TOPSECRET-SENTINEL|ANOTHER-SECRET-SENTINEL/.test(t)) hits.push(p); } } };
    walk(join(sb.d, ".run/public")); assert.deepEqual(hits, [], "metadata and logs carry no secret");
  });
});

// ================================================================================================================================ TEST 6: missing / corrupt approved artifact
test("TEST 6. approved artifact missing / corrupt / tampered => APPROVED_BUILD_MISSING, FAIL CLOSED: HEAD is not built, nothing newer is picked, nothing is started", async (t) => {
  const sb = await sandbox(); const A = commit(sb, "A"); assert.equal(pub(sb, ["deploy", A]).status, 0); commit(sb, "B"); const id = approved(sb).releaseId; const rdir = join(sb.d, ".run/public/releases", id); assert.equal(pub(sb, ["down"]).status, 0);
  const guard = async (what, code = 6, count = 1) => { const n0 = lines(sb, "builds.log").length; for (const cmd of ["up", "restart"]) { const r = pub(sb, [cmd]); assert.equal(r.status, code, `${what} / ${cmd}: ${r.out}`); assert.match(r.out, code === 6 ? /APPROVED_BUILD_MISSING|RELEASE_TAMPERED/ : /./); } assert.equal(lines(sb, "builds.log").length, n0, `${what}: HEAD was not built`); assert.deepEqual(await marksOf(sb), [null, null, null], `${what}: nothing started`); assert.equal(releases(sb).length, count, "no other release was created or picked"); };
  await t.test("a dist directory removed", async () => { cpSync(join(rdir, "apps/admin/.next"), join(sb.d, "bak-admin-next"), { recursive: true }); rmSync(join(rdir, "apps/admin/.next"), { recursive: true }); await guard("dist removed"); assert.equal(stateOf(sb).admin, "APPROVED_BUILD_MISSING"); assert.equal(pub(sb, ["status"]).status, 6); assert.equal(pub(sb, ["verify"]).status, 6); cpSync(join(sb.d, "bak-admin-next"), join(rdir, "apps/admin/.next"), { recursive: true }); });
  await t.test("a dist file corrupted (BUILD_ID intact, content digest differs)", async () => { appendFileSync(join(rdir, "apps/studio/.next/static/chunks/app.js"), "// corrupted"); await guard("corrupt dist"); writeFileSync(join(rdir, "apps/studio/.next/static/chunks/app.js"), "//studio-A"); });
  await t.test("release.json tampered after approval => RELEASE_TAMPERED", async () => { const f = join(rdir, "release.json"); const orig = readFileSync(f, "utf8"); writeFileSync(f, orig.replace('"kind": "built"', '"kind": "built" ')); await guard("tampered record"); writeFileSync(f, orig); });
  await t.test("node_modules missing", async () => { rmSync(join(rdir, "node_modules"), { recursive: true }); await guard("no node_modules"); });
  await t.test("the whole release directory missing", async () => { rmSync(rdir, { recursive: true }); await guard("no release dir", 6, 0); assert.ok(approved(sb), "the approval pointer itself is untouched (not silently re-pointed)"); });
});

// ================================================================================================================================ a foreign process on a public port
test("a FOREIGN process on a public port: `up` refuses (exit 3), names it, never kills it, and starts nothing else", async () => {
  const sb = await sandbox(); const A = commit(sb, "A"); assert.equal(pub(sb, ["deploy", A]).status, 0); assert.equal(pub(sb, ["down"]).status, 0);
  const f = spawn(process.execPath, ["-e", `require("http").createServer((q,r)=>r.end("foreign")).listen(${sb.ports.admin},"127.0.0.1")`], { detached: true, stdio: "ignore" }); f.unref(); strays.push(f.pid); await sleep(600);
  const r = pub(sb, ["up"]); assert.equal(r.status, 3, r.out); assert.match(r.out, /FOREIGN_PROCESS/); assert.ok(exists(f.pid), "the foreign process survived"); assert.equal(await info(sb.ports.platform), null, "all or nothing: platform was not started"); assert.equal(pub(sb, ["restart"]).status, 3);
  assert.equal(pub(sb, ["down"]).status, 0); assert.ok(exists(f.pid), "`down` never touches it either"); assert.equal(stateOf(sb).admin, "FOREIGN_PROCESS");
});

// ================================================================================================================================ pinning what runs now (evidence based)
test("init --from-running: pins the release the portals run NOW without restarting them; recovery then starts that pinned release, never HEAD; unreproducible evidence is refused", async (t) => {
  const sb = await sandbox(); const A = commit(sb, "A"); const c = context({ PUBLIC_ROOT: sb.d }); const bEnv = buildEnvOf(c); const legacy = {};
  async function legacyRun(extra = {}) {   // what the PREVIOUS lifecycle left behind: a dist named after the fingerprint inside the working tree, a process in apps/<p>, and its state files
    for (const n of NAMES) {
      const fp = spawnSync(process.execPath, [join(sb.d, "scripts/portal-fingerprint.mjs"), "--root", sb.d, "--app", n, "--", ...bEnv], { encoding: "utf8" }).stdout.trim(); const dist = `.next-public-${fp.slice(0, 12)}`;
      const bEnvObj = Object.fromEntries(bEnv.map((kv) => [kv.split("=")[0], kv.slice(kv.indexOf("=") + 1)]));
      assert.equal(spawnSync(process.execPath, [join(sb.d, "node_modules/next/dist/bin/next"), "build"], { cwd: join(sb.d, "apps", n), env: { ...process.env, ...bEnvObj, SANDBOX: sb.d, NEXT_DIST_DIR: dist }, encoding: "utf8" }).status, 0);
      const p = spawn(process.execPath, [join(sb.d, "node_modules/next/dist/bin/next"), "start", "-p", String(sb.ports[n])], { cwd: join(sb.d, "apps", n), env: { ...process.env, SANDBOX: sb.d, NEXT_DIST_DIR: dist }, detached: true, stdio: "ignore" }); p.unref(); strays.push(p.pid); legacy[n] = { pid: p.pid, dist, fp };
      for (let i = 0; i < 50 && !(await info(sb.ports[n])); i++) await sleep(100);
      const w = (e, v) => writeFileSync(join(sb.d, `.run/public/portal-${n}.${e}`), v + "\n"); w("pid", p.pid); w("pidstart", startTimeOf(p.pid).replace(/^(\w+ \w+) (\d) /, "$1  $2 ")); w("runbfp", fp); w("rundist", dist); w("runmeta", `${A.slice(0, 12)} 0`); w("launcher", p.pid);
    }
    if (extra.dirty) writeFileSync(join(sb.d, ".run/public/portal-admin.runmeta"), `${A.slice(0, 12)} 1\n`);
  }
  await legacyRun({ dirty: true });
  await t.test("a process started from a DIRTY tree is refused (not reproducible); nothing is pinned, nothing restarted", async () => { const r = pub(sb, ["init", "--from-running"]); assert.equal(r.status, 1, r.out); assert.match(r.out, /DIRTY/); assert.equal(approved(sb), null); assert.ok(exists(legacy.admin.pid)); assert.deepEqual(releases(sb), []); });
  writeFileSync(join(sb.d, ".run/public/portal-admin.runmeta"), `${A.slice(0, 12)} 0\n`);
  await t.test("evidence that does not hold is refused: a served asset missing from the recorded dist", async () => { const f = join(sb.d, "apps/studio", legacy.studio.dist, "static/chunks/app.js"); const keep = readFileSync(f, "utf8"); rmSync(f); const r = pub(sb, ["init", "--from-running"]); assert.equal(r.status, 1, r.out); assert.match(r.out, /assets/); assert.equal(approved(sb), null); writeFileSync(f, keep); });
  await t.test("with every piece of evidence the running release is pinned; the processes are NOT restarted (same pids, same dist) and are CURRENT_APPROVED", async () => {
    const r = pub(sb, ["init", "--from-running"]); assert.equal(r.status, 0, r.out); const ap = approved(sb); assert.ok(ap.releaseId.startsWith(A.slice(0, 12)));
    for (const n of NAMES) assert.ok(exists(legacy[n].pid), n + " was not restarted"); assert.deepEqual(stateOf(sb), { platform: "CURRENT_APPROVED", admin: "CURRENT_APPROVED", studio: "CURRENT_APPROVED" });
    const rel = JSON.parse(readFileSync(join(sb.d, ".run/public/releases", ap.releaseId, "release.json"), "utf8")); assert.equal(rel.kind, "adopted"); assert.equal(rel.adoption.evidence.length, 3); assert.ok(rel.adoption.evidence.every((e) => e.fingerprintReproduced && e.commitCleanTree));
    assert.equal(lines(sb, "builds.log").filter((l) => l.includes(" .next ")).length, 0, "adoption rebuilt nothing"); assert.notEqual(pub(sb, ["init", "--from-running"]).status, 0, "a second init is refused: an approved release already exists");
  });
  await t.test("HEAD moves on; an adopted portal dies; recovery starts the PINNED release from its release directory (the legacy dist in the working tree is not used, HEAD is not built)", async () => {
    commit(sb, "B"); const n0 = lines(sb, "builds.log").length; process.kill(legacy.platform.pid, "SIGKILL"); await sleep(500); assert.equal(stateOf(sb).platform, "STOPPED");
    const r = pub(sb, ["up"]); assert.equal(r.status, 0, r.out); assert.equal((await info(sb.ports.platform)).mark, "platform-A"); assert.equal(lines(sb, "builds.log").length, n0, "B was never built");
    assert.match((await info(sb.ports.platform)).cwd, /\.run\/public\/releases\//); assert.deepEqual(stateOf(sb), { platform: "CURRENT_APPROVED", admin: "CURRENT_APPROVED", studio: "CURRENT_APPROVED" });
    assert.equal(pub(sb, ["restart"]).status, 0); assert.deepEqual(await marksOf(sb), ["platform-A", "admin-A", "studio-A"]);
  });
});

// ================================================================================================================================ TEST 10: the watchdog
test("TEST 10. watchdog: it only starts the approved release (it never builds) and a crash loop is BOUNDED: capped recoveries, backoff, a marker, no fast infinite loop", async (t) => {
  const sb = await sandbox(); const apiP = await freePort(), rndP = await freePort(); const up = join(sb.d, "scripts/public-up.sh");
  appendFileSync(join(sb.d, ".run/public/public.env"), `RENDER_PORT_PUBLIC=${rndP}\n`); writeFileSync(join(sb.d, ".run/public/public.env"), readFileSync(join(sb.d, ".run/public/public.env"), "utf8").replace("API_PORT=18081", `API_PORT=${apiP}`));
  writeFileSync(up, `#!/usr/bin/env bash\necho "$(date +%s) up" >> "$(dirname "$0")/../up-calls.log"\nexit 0\n`); chmodSync(up, 0o755);   // a stand-in for public-up.sh that never recovers anything
  const health = (port) => spawn(process.execPath, ["-e", `require("http").createServer((q,r)=>r.end("ok")).listen(${port},"127.0.0.1");setInterval(()=>{},1e6)`], { stdio: "ignore", detached: true }); const api = health(apiP), rnd = health(rndP), tun = spawn("sleep", ["300"], { stdio: "ignore", detached: true }); for (const p of [api, rnd, tun]) { p.unref(); strays.push(p.pid); } writeFileSync(join(sb.d, ".run/public/tunnel.pid"), String(tun.pid)); await sleep(500);
  const wdEnv = { ...process.env, WATCHDOG_INTERVAL: "1", WATCHDOG_MAX_RESTARTS: "3", WATCHDOG_WINDOW: "60", WATCHDOG_BACKOFF_BASE: "1", WATCHDOG_BACKOFF_MAX: "2" };
  const wd = spawn("bash", [join(sb.d, "scripts/watchdog.sh"), "public"], { cwd: sb.d, env: wdEnv, stdio: "ignore", detached: true }); wd.unref(); strays.push(wd.pid);
  await t.test("the three portal ports stay down: recoveries are capped at 3 inside the window, then the loop is declared and automatic restarts PAUSE", async () => {
    for (let i = 0; i < 40 && !existsSync(join(sb.d, ".run/public/crash-loop.json")); i++) await sleep(500);
    assert.ok(existsSync(join(sb.d, ".run/public/crash-loop.json")), "crash-loop marker written"); const calls = lines(sb, "up-calls.log").length; assert.equal(calls, 3, "exactly MAX recoveries were attempted");
    await sleep(6000); assert.equal(lines(sb, "up-calls.log").length, 3, "no further restart while the loop persists (no fast infinite loop)");
    const m = JSON.parse(readFileSync(join(sb.d, ".run/public/crash-loop.json"), "utf8")); assert.equal(m.restarts, 3); assert.match(m.down, /portal:/); assert.match(readFileSync(join(sb.d, ".run/watchdog-public.log"), "utf8"), /CRASH LOOP/);
    const stamps = lines(sb, "up-calls.log").map((l) => Number(l.split(" ")[0])); assert.ok(stamps[1] - stamps[0] >= 1 && stamps[2] - stamps[1] >= 2, `backoff grows between recoveries (${stamps.join(",")})`);
  });
  await t.test("when the portals are healthy again the marker is cleared (the watchdog recovered)", async () => {
    const ps = NAMES.map((n) => spawn(process.execPath, ["-e", `require("http").createServer((q,r)=>r.end("ok")).listen(${sb.ports[n]},"127.0.0.1");setInterval(()=>{},1e6)`], { stdio: "ignore", detached: true })); for (const p of ps) { p.unref(); strays.push(p.pid); }
    for (let i = 0; i < 30 && existsSync(join(sb.d, ".run/public/crash-loop.json")); i++) await sleep(500); assert.ok(!existsSync(join(sb.d, ".run/public/crash-loop.json")), "marker cleared once healthy");
  });
  assert.ok(true);
});

// ================================================================================================================================ the fingerprint of a snapshot that sits INSIDE a repository
test("fingerprint of a release snapshot is the same inside a parent repository (.run/public/releases/<id>) as outside it: git lists the PARENT's paths there, so the tool must walk instead", async () => {
  const sb = await sandbox(); const A = commit(sb, "A"); const out = realpathSync(mkdtempSync(join(tmpdir(), "snap-out-"))); const inner = join(sb.d, ".run/public/releases/x"); mkdirSync(inner, { recursive: true });
  for (const dir of [out, inner]) { const a = spawn("git", ["-C", sb.d, "archive", "--format=tar", A], { stdio: ["ignore", "pipe", "ignore"] }); const t = spawn("tar", ["-x", "-C", dir], { stdio: ["pipe", "ignore", "ignore"] }); a.stdout.pipe(t.stdin); await new Promise((r) => t.on("close", r)); }
  const fp = (root) => spawnSync(process.execPath, [join(sb.d, "scripts/portal-fingerprint.mjs"), "--root", root, "--app", "admin", "--", "K=1"], { encoding: "utf8" }).stdout.trim(); const list = (root) => spawnSync(process.execPath, [join(sb.d, "scripts/portal-fingerprint.mjs"), "--root", root, "--app", "admin", "--list"], { encoding: "utf8" }).stdout.split("\n").filter(Boolean);
  assert.ok(list(inner).length >= 4, "the nested snapshot is listed (not empty)"); assert.equal(fp(inner), fp(out), "same content, same fingerprint wherever the directory sits"); assert.equal(fp(inner), fp(sb.d), "and equal to the fingerprint of the committed working tree");
  rmSync(out, { recursive: true, force: true });
});
