// @class: integration
// Source-aware portal lifecycle (D-C0-45): scripts/portals.sh (local) and scripts/public-portals.sh (public) against a SANDBOX — a throw-away git repository with the three apps, the REAL scripts and
// fingerprint tool copied in, a fake `npx` on PATH (`next build` writes a marker-carrying dist directory and, like the real one, edits tsconfig.json; `next start` execs a tiny node HTTP server on the
// requested port that serves what its dist directory contains) and free loopback ports. Real processes, real ports, real pid files: only the Next.js compiler is replaced.
//   node --test tests/infra/portals-reliability.mjs        (npm run test:infra:portals)
import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, readdirSync, cpSync, rmSync, chmodSync, appendFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync, spawn } from "node:child_process";
import { createServer } from "node:net";

const REPO = new URL("../..", import.meta.url).pathname.replace(/\/$/, "");
const APPS = ["platform", "admin", "studio"];
const sandboxes = [];

const freePort = () => new Promise((res) => { const s = createServer(); s.listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => res(p)); }); });
const ports = async () => ({ platform: await freePort(), admin: await freePort(), studio: await freePort() });

const NPX = `#!/usr/bin/env bash
# fake npx for the sandbox: only \`next build\` and \`next start\`
[ "$1" = next ] || { echo "fake npx: unsupported $*" >&2; exit 99; }; shift
case "$1" in
  build)
    [ -f "$SANDBOX/FAIL_BUILD" ] && { echo "sandbox: simulated build failure"; exit 1; }
    d="\${NEXT_DIST_DIR:-.next}"; mkdir -p "$d"; cp marker.txt "$d/MARK"; echo "$RANDOM-$$" > "$d/BUILD_ID"
    echo "// next build touched this for $d" >> tsconfig.json                                  # the real build rewrites tsconfig.json for a custom distDir
    echo "$(basename "$PWD") $d $API_PROXY_TARGET" >> "$SANDBOX/builds.log"; sleep 0.2; exit 0;;
  start)
    shift; port=""; while [ $# -gt 0 ]; do case "$1" in -p) port="$2"; shift 2;; -H) shift 2;; *) shift;; esac; done
    echo "$(basename "$PWD") $NEXT_DIST_DIR" >> "$SANDBOX/starts.log"
    exec node "$SANDBOX/bin/server.js" "$port" "$NEXT_DIST_DIR";;
esac`;
const SERVER = `const http=require("http"),fs=require("fs");const [port,dist]=process.argv.slice(2);
const mark=fs.readFileSync(dist+"/MARK","utf8").trim();           // read ONCE at start: a stale process keeps serving what it started with
http.createServer((q,r)=>r.end(JSON.stringify({mark,dist,sites:process.env.SITES_ORIGIN||"",api:process.env.API_PROXY_TARGET||""}))).listen(+port,"127.0.0.1");`;

async function sandbox({ withPublic = true } = {}) {
  const d = mkdtempSync(join(tmpdir(), "portals-sb-")); const p = await ports(); const pp = await ports();
  const sb = { d, p, pp, bin: join(d, "bin") };
  mkdirSync(join(d, "bin"), { recursive: true }); writeFileSync(join(d, "bin/npx"), NPX); chmodSync(join(d, "bin/npx"), 0o755); writeFileSync(join(d, "bin/server.js"), SERVER);
  mkdirSync(join(d, "scripts"), { recursive: true });
  for (const f of ["portals.sh", "public-portals.sh", "_portals_lib.sh", "portal-fingerprint.mjs"]) cpSync(join(REPO, "scripts", f), join(d, "scripts", f));
  // the real _env.sh needs .env, JDK and the whole local stack: the sandbox gets a stub that exports what portals.sh reads from it
  writeFileSync(join(d, "scripts/_env.sh"), `ROOT="$(cd "$(dirname "\${BASH_SOURCE[0]}")/.." && pwd)"; cd "$ROOT"\nexport PORTAL_HOST=127.0.0.1\nexport API_PROXY_TARGET="\${API_PROXY_TARGET:-http://127.0.0.1:8080}"\n`);
  writeFileSync(join(d, "package.json"), `{"name":"sandbox"}`); writeFileSync(join(d, "package-lock.json"), `{"lock":1}`); mkdirSync(join(d, "node_modules"));
  for (const a of APPS) { mkdirSync(join(d, "apps", a), { recursive: true }); writeFileSync(join(d, "apps", a, "tsconfig.json"), `{"app":"${a}"}\n`); writeFileSync(join(d, "apps", a, "next.config.ts"), "export default {};\n"); writeFileSync(join(d, "apps", a, "marker.txt"), `${a}-v1\n`); }
  mkdirSync(join(d, "packages/ui"), { recursive: true }); writeFileSync(join(d, "packages/ui/index.ts"), "export const ui = 1;\n"); mkdirSync(join(d, "features")); writeFileSync(join(d, "features/f.ts"), "export const f = 1;\n");
  writeFileSync(join(d, ".gitignore"), ".run/\nnode_modules/\n.next*\n");
  if (withPublic) { mkdirSync(join(d, ".run/public"), { recursive: true }); writeFileSync(join(d, ".run/public/public.env"), `PUBLIC_HOST=studio.example\nPUBLIC_PLATFORM_HOST=platform.example\nPUBLIC_ADMIN_HOST=admin.example\nPORTAL_PLATFORM_PORT_PUBLIC=${pp.platform}\nPORTAL_ADMIN_PORT_PUBLIC=${pp.admin}\nPORTAL_STUDIO_PORT_PUBLIC=${pp.studio}\nAPI_PORT=18081\nSITES_HOST=sites.example\nPUBLIC_FILES_HOST=files.example\n`); }
  const g = (...a) => spawnSync("git", a, { cwd: d, encoding: "utf8" });
  g("init", "-q"); g("config", "user.email", "t@t"); g("config", "user.name", "t"); g("add", "-A"); g("commit", "-qm", "init");
  sandboxes.push(sb); return sb;
}
const env = (sb, extra = {}) => ({ ...process.env, PATH: `${sb.bin}:${process.env.PATH}`, SANDBOX: sb.d, PORTAL_PLATFORM_PORT: String(sb.p.platform), PORTAL_ADMIN_PORT: String(sb.p.admin), PORTAL_STUDIO_PORT: String(sb.p.studio), HOME: sb.d, ...extra });
const run = (sb, script, args = [], extra = {}) => { const r = spawnSync("bash", [join(sb.d, "scripts", script), ...args], { cwd: sb.d, env: env(sb, extra), encoding: "utf8", timeout: 120000 }); return { status: r.status, out: (r.stdout ?? "") + (r.stderr ?? "") }; };
const local = (sb, args, extra) => run(sb, "portals.sh", args, extra);
const pub = (sb, args, extra) => run(sb, "public-portals.sh", args, extra);
const get = async (port) => { try { return await (await fetch(`http://127.0.0.1:${port}/`, { signal: AbortSignal.timeout(3000) })).json(); } catch { return null; } };
const lines = (sb, f) => (existsSync(join(sb.d, f)) ? readFileSync(join(sb.d, f), "utf8").split("\n").filter(Boolean) : []);
const builds = (sb, app) => lines(sb, "builds.log").filter((l) => l.startsWith(app + " ")).length;
const state = (sb, dir, prefix, app, ext) => { try { return readFileSync(join(sb.d, dir, `${prefix}${app}.${ext}`), "utf8").trim(); } catch { return ""; } };
const pidOf = (sb, app) => state(sb, ".run/portals", "", app, "pid"); const ppidOf = (sb, app) => state(sb, ".run/public", "portal-", app, "pid");
const alive = (pid) => { try { process.kill(Number(pid), 0); return true; } catch { return false; } };
const edit = (sb, rel, text) => appendFileSync(join(sb.d, rel), text);
const dists = (sb, app, mode) => readdirSync(join(sb.d, "apps", app)).filter((n) => n.startsWith(`.next-${mode}-`));
const git = (sb, ...a) => spawnSync("git", a, { cwd: sb.d, encoding: "utf8" });

test.after(() => {
  for (const sb of sandboxes) {
    spawnSync("bash", [join(sb.d, "scripts/portals.sh"), "down"], { cwd: sb.d, env: env(sb), timeout: 60000 }); spawnSync("bash", [join(sb.d, "scripts/public-portals.sh"), "down"], { cwd: sb.d, env: env(sb), timeout: 60000 });
    if (sb.foreign) try { process.kill(sb.foreign.pid, "SIGKILL"); } catch { /* gone */ }
    rmSync(sb.d, { recursive: true, force: true });
  }
});

// ================================================================================================================================ local lifecycle
test("LOCAL lifecycle: first build, no-op, per-portal and shared source changes, dirty tree, env, stale / dead / legacy processes, stale output impossible", async (t) => {
  const sb = await sandbox({ withPublic: false });

  await t.test("1. first up: all three portals are built (3 builds), started, healthy, serve their own source, status CURRENT", async () => {
    const r = local(sb, ["up"]); assert.equal(r.status, 0, r.out);
    for (const a of APPS) { assert.equal(builds(sb, a), 1, a); const s = await get(sb.p[a]); assert.equal(s?.mark, `${a}-v1`); assert.match(s.dist, /^\.next-local-[0-9a-f]{12}$/, "a fingerprint-named dist directory, never .next"); }
    assert.ok(!existsSync(join(sb.d, "apps/admin/.next")), "the shared `.next` directory is never created");
    const st = local(sb, ["status"]); assert.equal(st.status, 0, st.out); assert.equal((st.out.match(/CURRENT/g) ?? []).length, 3, st.out);
    assert.match(st.out, /PORTAL\s+PORT\s+PID\s+OWNER\s+BUILT\s+RUNNING\s+DESIRED\s+COMMIT\s+STATE/);
    assert.match(st.out, new RegExp(`admin\\s+${sb.p.admin}\\s+\\d+\\s+ours\\s+[0-9a-f]{16}\\s+[0-9a-f]{16}\\s+[0-9a-f]{16}\\s+[0-9a-f]{12}\\s+CURRENT`), "port, pid, owner, built / running / desired fingerprint, the running commit (a clean tree: no +dirty), state");
  });

  await t.test("2. unchanged source: `up` rebuilds nothing, restarts nothing (same pids), says so", async () => {
    const before = Object.fromEntries(APPS.map((a) => [a, pidOf(sb, a)])); const n0 = lines(sb, "builds.log").length, s0 = lines(sb, "starts.log").length;
    const r = local(sb, ["up"]); assert.equal(r.status, 0, r.out);
    assert.equal(lines(sb, "builds.log").length, n0, "no build"); assert.equal(lines(sb, "starts.log").length, s0, "no start");
    for (const a of APPS) assert.equal(pidOf(sb, a), before[a]); assert.equal((r.out.match(/already current/g) ?? []).length, 3, r.out);
  });

  await t.test("3a. a change inside ONE app (uncommitted: a dirty tree) rebuilds and restarts ONLY that portal; the others keep their pid", async () => {
    const before = Object.fromEntries(APPS.map((a) => [a, pidOf(sb, a)]));
    writeFileSync(join(sb.d, "apps/admin/marker.txt"), "admin-v2\n");
    const stale = local(sb, ["status"]); assert.notEqual(stale.status, 0); assert.match(stale.out, /admin[^\n]*STALE: source/); assert.match(stale.out, /platform[^\n]*CURRENT/, stale.out);
    const r = local(sb, ["up"]); assert.equal(r.status, 0, r.out);
    assert.equal(builds(sb, "admin"), 2); assert.equal(builds(sb, "platform"), 1); assert.equal(builds(sb, "studio"), 1);
    assert.notEqual(pidOf(sb, "admin"), before.admin); assert.ok(!alive(before.admin), "the stale process is gone"); assert.equal(pidOf(sb, "platform"), before.platform); assert.equal(pidOf(sb, "studio"), before.studio);
    assert.equal((await get(sb.p.admin)).mark, "admin-v2"); assert.equal((await get(sb.p.platform)).mark, "platform-v1");
    assert.match(local(sb, ["status"]).out, /admin[^\n]*\+dirty/, "status says the running source was a dirty tree");
  });

  await t.test("3b. a change in a SHARED directory (packages/) rebuilds and restarts ALL three", async () => {
    edit(sb, "packages/ui/index.ts", "export const ui2 = 2;\n");
    const r = local(sb, ["up"]); assert.equal(r.status, 0, r.out);
    for (const a of APPS) assert.equal(builds(sb, a), a === "admin" ? 3 : 2, a);
  });

  await t.test("3c. a NEW untracked file in an app counts (and a gitignored / node_modules / .next* file does not)", async () => {
    const n0 = lines(sb, "builds.log").length;
    mkdirSync(join(sb.d, "apps/studio/app"), { recursive: true }); writeFileSync(join(sb.d, "apps/studio/app/page.tsx"), "export default 1;\n");
    writeFileSync(join(sb.d, "node_modules/junk.js"), "x"); mkdirSync(join(sb.d, "apps/studio/.next-cache"), { recursive: true }); writeFileSync(join(sb.d, "apps/studio/.next-cache/x"), "x"); writeFileSync(join(sb.d, "apps/studio/dev.log"), "x");
    const r = local(sb, ["up"]); assert.equal(r.status, 0, r.out); assert.equal(lines(sb, "builds.log").length, n0 + 1, "only studio rebuilt");
    const n1 = lines(sb, "builds.log").length; writeFileSync(join(sb.d, "node_modules/junk.js"), "y"); writeFileSync(join(sb.d, "apps/studio/dev.log"), "y");
    assert.equal(local(sb, ["up"]).status, 0); assert.equal(lines(sb, "builds.log").length, n1, "ignored files never trigger a build");
  });

  await t.test("3d. `next build` rewriting tsconfig.json does not leave the tree dirty (the file is restored), so the next fingerprint is stable", () => {
    assert.equal(readFileSync(join(sb.d, "apps/admin/tsconfig.json"), "utf8"), `{"app":"admin"}\n`);
    assert.equal(local(sb, ["status"]).status, 0);
  });

  await t.test("4. a changed BUILD environment (API_PROXY_TARGET) rebuilds every portal and the new process uses it", async () => {
    const n0 = lines(sb, "builds.log").length;
    const r = local(sb, ["up"], { API_PROXY_TARGET: "http://127.0.0.1:9999" }); assert.equal(r.status, 0, r.out);
    assert.equal(lines(sb, "builds.log").length, n0 + 3); for (const a of APPS) assert.equal((await get(sb.p[a])).api, "http://127.0.0.1:9999");
  });

  await t.test("7a. a portal that DIED is started again WITHOUT a rebuild (its build is current)", async () => {
    const pid = pidOf(sb, "studio"); process.kill(Number(pid), "SIGKILL"); await new Promise((r) => setTimeout(r, 400));
    const down = local(sb, ["status"], { API_PROXY_TARGET: "http://127.0.0.1:9999" }); assert.match(down.out, /studio[^\n]*DOWN \(build current\)/, down.out);
    const n0 = lines(sb, "builds.log").length; const r = local(sb, ["up"], { API_PROXY_TARGET: "http://127.0.0.1:9999" }); assert.equal(r.status, 0, r.out);
    assert.equal(lines(sb, "builds.log").length, n0, "no rebuild"); assert.ok((await get(sb.p.studio)) !== null);
  });

  await t.test("7b. a process started by an OLDER version of the script (no run fingerprint recorded) is recognised as ours and replaced", async () => {
    const env0 = { API_PROXY_TARGET: "http://127.0.0.1:9999" }; const pid = pidOf(sb, "platform");
    rmSync(join(sb.d, ".run/portals/platform.runfp")); rmSync(join(sb.d, ".run/portals/platform.runbfp"));
    const r = local(sb, ["up"], env0); assert.equal(r.status, 0, r.out); assert.match(r.out, /platform: running build .* is stale/); assert.notEqual(pidOf(sb, "platform"), pid); assert.ok(!alive(pid));
  });

  await t.test("10. stale output is impossible: a poisoned legacy `.next`, a corrupted dist directory and old dist directories never get served", async () => {
    const env0 = { API_PROXY_TARGET: "http://127.0.0.1:9999" };
    mkdirSync(join(sb.d, "apps/admin/.next"), { recursive: true }); writeFileSync(join(sb.d, "apps/admin/.next/MARK"), "POISONED-LEGACY-BUILD"); writeFileSync(join(sb.d, "apps/admin/.next/BUILD_ID"), "old");
    writeFileSync(join(sb.d, "apps/admin/marker.txt"), "admin-v3\n"); const oldDist = (await get(sb.p.admin)).dist;
    assert.equal(local(sb, ["up"], env0).status, 0);
    const s = await get(sb.p.admin); assert.equal(s.mark, "admin-v3"); assert.notEqual(s.dist, oldDist, "served from the NEW fingerprint directory"); assert.ok(!s.mark.includes("POISONED"));
    assert.deepEqual(dists(sb, "admin", "local"), [s.dist], "the previous dist directory was removed once the new one was serving");
    // corrupt the CURRENT dist (BUILD_ID gone): `up` must not trust it
    const n0 = builds(sb, "admin"); rmSync(join(sb.d, "apps/admin", s.dist, "BUILD_ID"));
    assert.equal(local(sb, ["up"], env0).status, 0); assert.equal(builds(sb, "admin"), n0 + 1, "an incomplete build directory is rebuilt, not reused");
    // the served marker of EVERY portal equals the source on disk right now
    for (const a of APPS) assert.equal((await get(sb.p[a])).mark, readFileSync(join(sb.d, "apps", a, "marker.txt"), "utf8").trim(), a);
  });

  await t.test("--force-rebuild rebuilds everything even when nothing changed; `restart` restarts without rebuilding", async () => {
    const env0 = { API_PROXY_TARGET: "http://127.0.0.1:9999" }; let n0 = lines(sb, "builds.log").length;
    assert.equal(local(sb, ["up", "--force-rebuild"], env0).status, 0); assert.equal(lines(sb, "builds.log").length, n0 + 3);
    n0 = lines(sb, "builds.log").length; const p0 = pidOf(sb, "studio"); assert.equal(local(sb, ["restart"], env0).status, 0);
    assert.equal(lines(sb, "builds.log").length, n0, "restart does not rebuild"); assert.notEqual(pidOf(sb, "studio"), p0);
    assert.equal(local(sb, ["build"], env0).status, 0); assert.equal(lines(sb, "builds.log").length, n0, "build with a current build is a no-op");
  });

  await t.test("`down` stops only what the script started and leaves nothing behind", async () => {
    const pids = APPS.map((a) => pidOf(sb, a)); const r = local(sb, ["down"], { API_PROXY_TARGET: "http://127.0.0.1:9999" }); assert.equal(r.status, 0, r.out);
    for (const pid of pids) assert.ok(!alive(pid)); for (const a of APPS) assert.equal(await get(sb.p[a]), null);
  });
});

// ================================================================================================================================ atomicity
test("5. a FAILED build never marks the new fingerprint and never takes down a healthy portal", async (t) => {
  const sb = await sandbox({ withPublic: true });
  assert.equal(pub(sb, ["up"]).status, 0);
  const before = Object.fromEntries(APPS.map((a) => [a, ppidOf(sb, a)])); const fp0 = state(sb, ".run/public", "portal-", "admin", "fp"); const dist0 = state(sb, ".run/public", "portal-", "admin", "dist");
  writeFileSync(join(sb.d, "apps/admin/marker.txt"), "admin-BROKEN\n"); writeFileSync(join(sb.d, "FAIL_BUILD"), "1");
  const r = pub(sb, ["up"]);
  await t.test("up exits 1, says nothing was stopped, and shows the build log", () => { assert.equal(r.status, 1, r.out); assert.match(r.out, /build of portal admin failed/); assert.match(r.out, /nothing was stopped or started/); });
  await t.test("the old healthy process of EVERY portal is still running and still serves the OLD source", async () => {
    for (const a of APPS) { assert.equal(ppidOf(sb, a), before[a]); assert.ok(alive(before[a])); } assert.equal((await get(sb.pp.admin)).mark, "admin-v1");
  });
  await t.test("the fingerprint / dist state of the last SUCCESSFUL build is untouched and no partial dist directory is left", () => {
    assert.equal(state(sb, ".run/public", "portal-", "admin", "fp"), fp0); assert.equal(state(sb, ".run/public", "portal-", "admin", "dist"), dist0); assert.deepEqual(dists(sb, "admin", "public"), [dist0]);
    assert.equal(readFileSync(join(sb.d, "apps/admin/tsconfig.json"), "utf8"), `{"app":"admin"}\n`, "tsconfig restored even though the build failed");
  });
  await t.test("status reports the stale running portal; and once the build can succeed the next `up` swaps it", async () => {
    assert.match(pub(sb, ["status"]).out, /admin[^\n]*STALE: source/); rmSync(join(sb.d, "FAIL_BUILD"));
    assert.equal(pub(sb, ["up"]).status, 0); assert.equal((await get(sb.pp.admin)).mark, "admin-BROKEN"); assert.notEqual(ppidOf(sb, "admin"), before.admin);
  });
  await t.test("a NEW build that does not START is rolled back to the previous build (and reported), not left as a dead portal", async () => {
    writeFileSync(join(sb.d, "apps/admin/marker.txt"), "admin-v9\n"); const prevPid = ppidOf(sb, "admin");
    // the fake `next start` dies for this dist when the marker says so: simulate with a MARK the server cannot read (the build copies marker.txt; make the dist unreadable after build via a wrapper)
    writeFileSync(join(sb.d, "bin/npx"), NPX.replace('sleep 0.2; exit 0;;', 'sleep 0.2; [ -f "$SANDBOX/BREAK_START" ] && rm -f "$d/MARK"; exit 0;;')); chmodSync(join(sb.d, "bin/npx"), 0o755); writeFileSync(join(sb.d, "BREAK_START"), "1");
    const r2 = pub(sb, ["up"]); assert.equal(r2.status, 1, r2.out); assert.match(r2.out, /did not start/); assert.match(r2.out, /rolled back/);
    assert.equal((await get(sb.pp.admin))?.mark, "admin-BROKEN", "the previous build serves again"); assert.ok(prevPid && ppidOf(sb, "admin"), "a recorded process");
    assert.match(pub(sb, ["status"]).out, /admin[^\n]*STALE: source/);
  });
});

// ================================================================================================================================ foreign process
test("6. a port used by a FOREIGN process is refused (exit 2), named, never killed, and nothing else is built or started", async () => {
  const sb = await sandbox({ withPublic: true });
  const foreign = spawn(process.execPath, ["-e", `require("http").createServer((q,r)=>r.end("foreign")).listen(${sb.p.admin},"127.0.0.1")`], { stdio: "ignore", detached: true }); sb.foreign = foreign; foreign.unref();
  await new Promise((r) => setTimeout(r, 600));
  const r = local(sb, ["up"]); assert.equal(r.status, 2, r.out); assert.match(r.out, new RegExp(`port ${sb.p.admin} \\(portal admin, local\\) is used by pid ${foreign.pid}`)); assert.match(r.out, /did not start/);
  assert.ok(alive(foreign.pid), "the foreign process is alive"); assert.equal(await (await fetch(`http://127.0.0.1:${sb.p.admin}/`)).text(), "foreign");
  assert.equal(lines(sb, "builds.log").length, 0, "nothing was built"); assert.equal(await get(sb.p.platform), null, "the free portals were NOT started either (all or nothing)");
  const st = local(sb, ["status"]); assert.match(st.out, /admin[^\n]*foreign[^\n]*FOREIGN/); assert.notEqual(st.status, 0);
  const d = local(sb, ["down"]); assert.equal(d.status, 0, d.out); assert.match(d.out, /not started by this script: left alone/); assert.ok(alive(foreign.pid), "`down` never kills a foreign process");
  const rr = local(sb, ["restart"]); assert.equal(rr.status, 2); assert.ok(alive(foreign.pid));
  const pu = pub(sb, ["up"]); assert.equal(pu.status, 0, "the PUBLIC portals (other ports) are not affected by a foreign process on a LOCAL port: " + pu.out);
  // a stale pid file pointing at the foreign pid with a different start time must not make it "ours"
  writeFileSync(join(sb.d, ".run/portals/admin.pid"), String(foreign.pid)); writeFileSync(join(sb.d, ".run/portals/admin.pidstart"), "Mon Jan  1 00:00:00 2001");
  assert.equal(local(sb, ["down"]).status, 0); assert.ok(alive(foreign.pid), "a pid file that matches the listener but not its start time is not ownership");
});

// ================================================================================================================================ local + public
test("9. local and public are independent: separate state, ports and dist directories; a change rebuilds each only when ITS script runs; `down` of one leaves the other", async (t) => {
  const sb = await sandbox({ withPublic: true });
  mkdirSync(join(sb.d, "apps/admin/.next-public"), { recursive: true }); writeFileSync(join(sb.d, "apps/admin/.next-public/BUILD_ID"), "legacy"); mkdirSync(join(sb.d, "apps/admin/.next"), { recursive: true });
  assert.equal(local(sb, ["up"]).status, 0); assert.ok(existsSync(join(sb.d, "apps/admin/.next-public")), "the local script never removes the public legacy directory"); assert.equal(pub(sb, ["up"]).status, 0);
  assert.ok(!existsSync(join(sb.d, "apps/admin/.next-public")), "the orphaned legacy public dist directory is removed once the portal serves a fingerprint directory"); assert.ok(existsSync(join(sb.d, "apps/admin/.next")), "the local legacy .next is NEVER touched");
  const lp = Object.fromEntries(APPS.map((a) => [a, pidOf(sb, a)])), pp = Object.fromEntries(APPS.map((a) => [a, ppidOf(sb, a)]));
  for (const a of APPS) assert.notEqual(lp[a], pp[a]);
  assert.ok(existsSync(join(sb.d, ".run/portals")) && existsSync(join(sb.d, ".run/public"))); for (const a of APPS) { assert.equal(dists(sb, a, "local").length, 1); assert.equal(dists(sb, a, "public").length, 1); }
  await t.test("a source change: only the script that runs rebuilds; the other mode reports STALE until ITS up", async () => {
    writeFileSync(join(sb.d, "apps/studio/marker.txt"), "studio-v2\n"); const n0 = lines(sb, "builds.log").length;
    assert.equal(local(sb, ["up"]).status, 0); assert.equal(lines(sb, "builds.log").length, n0 + 1); assert.equal((await get(sb.p.studio)).mark, "studio-v2"); assert.equal((await get(sb.pp.studio)).mark, "studio-v1", "public untouched");
    assert.match(pub(sb, ["status"]).out, /studio[^\n]*STALE: source/); assert.match(local(sb, ["status"]).out, /studio[^\n]*CURRENT/);
    assert.equal(pub(sb, ["up"]).status, 0); assert.equal((await get(sb.pp.studio)).mark, "studio-v2"); assert.equal(dists(sb, "studio", "local").length, 1, "pruning one mode never removes the other's directory"); assert.equal(dists(sb, "studio", "public").length, 1);
  });
  await t.test("a RUN-environment-only change (public: SITES_ORIGIN) restarts the public portals WITHOUT rebuilding", async () => {
    const n0 = lines(sb, "builds.log").length, before = ppidOf(sb, "admin"); const pe = readFileSync(join(sb.d, ".run/public/public.env"), "utf8").replace("SITES_HOST=sites.example", "SITES_HOST=sites2.example"); writeFileSync(join(sb.d, ".run/public/public.env"), pe);
    assert.match(pub(sb, ["status"]).out, /STALE: run environment changed/); assert.equal(pub(sb, ["up"]).status, 0);
    assert.equal(lines(sb, "builds.log").length, n0, "no build"); assert.notEqual(ppidOf(sb, "admin"), before); assert.equal((await get(sb.pp.admin)).sites, "https://sites2.example");
  });
  await t.test("a changed public hostname is a BUILD input (it is baked into the browser bundle): rebuild", async () => {
    const n0 = lines(sb, "builds.log").length; writeFileSync(join(sb.d, ".run/public/public.env"), readFileSync(join(sb.d, ".run/public/public.env"), "utf8").replace("PUBLIC_ADMIN_HOST=admin.example", "PUBLIC_ADMIN_HOST=admin2.example"));
    assert.equal(pub(sb, ["up"]).status, 0); assert.equal(lines(sb, "builds.log").length, n0 + 3);
  });
  await t.test("`public down` leaves the local portals running and `local down` leaves the public ones", async () => {
    const lpids = APPS.map((a) => pidOf(sb, a)); assert.equal(pub(sb, ["down"]).status, 0); for (const a of APPS) assert.equal(await get(sb.pp[a]), null); for (const pid of lpids) assert.ok(alive(pid));
    assert.equal(pub(sb, ["up"]).status, 0); const ppids = APPS.map((a) => ppidOf(sb, a)); assert.equal(local(sb, ["down"]).status, 0); for (const pid of ppids) assert.ok(alive(pid)); for (const a of APPS) assert.equal(await get(sb.p[a]), null);
  });
});

// ================================================================================================================================ the fingerprint itself
test("fingerprint: stable, content-based, app-scoped, ignores generated files, sees deletions, includes the build environment", async () => {
  const sb = await sandbox({ withPublic: false }); const fp = (app, ...envw) => spawnSync(process.execPath, [join(sb.d, "scripts/portal-fingerprint.mjs"), "--root", sb.d, "--app", app, "--", ...envw], { encoding: "utf8" }).stdout.trim();
  const a0 = fp("admin", "K=1"); assert.match(a0, /^[0-9a-f]{16}$/); assert.equal(fp("admin", "K=1"), a0, "stable"); assert.equal(fp("admin", "K=1"), fp("admin", "K=1"));
  assert.notEqual(fp("admin", "K=2"), a0, "environment"); assert.equal(fp("admin", "B=2", "K=1"), fp("admin", "K=1", "B=2"), "environment order does not matter");
  assert.notEqual(fp("platform", "K=1"), a0, "app scoped");
  git(sb, "commit", "--allow-empty", "-qm", "empty"); assert.equal(fp("admin", "K=1"), a0, "a new commit with the same content is the same fingerprint (no needless rebuild)");
  writeFileSync(join(sb.d, "apps/platform/marker.txt"), "x\n"); assert.equal(fp("admin", "K=1"), a0, "another app's change does not move this app's fingerprint"); assert.notEqual(fp("platform", "K=1"), fp("platform", "K=2"));
  mkdirSync(join(sb.d, "apps/admin/.next-x")); writeFileSync(join(sb.d, "apps/admin/.next-x/a"), "1"); writeFileSync(join(sb.d, "apps/admin/next-env.d.ts"), "1"); writeFileSync(join(sb.d, "apps/admin/x.tsbuildinfo"), "1"); assert.equal(fp("admin", "K=1"), a0, "generated files are not inputs");
  edit(sb, "features/f.ts", "//x\n"); const a1 = fp("admin", "K=1"); assert.notEqual(a1, a0, "shared source"); git(sb, "checkout", "features/f.ts"); assert.equal(fp("admin", "K=1"), a0, "reverting the content restores the fingerprint");
  rmSync(join(sb.d, "packages/ui/index.ts")); assert.notEqual(fp("admin", "K=1"), a0, "a deleted tracked file changes it"); git(sb, "checkout", "packages/ui/index.ts");
  writeFileSync(join(sb.d, "package-lock.json"), `{"lock":2}`); assert.notEqual(fp("admin", "K=1"), a0, "lockfile");
  const noGit = mkdtempSync(join(tmpdir(), "nogit-")); sandboxes.push({ d: noGit, p: {}, pp: {}, bin: "" }); cpSync(join(sb.d, "apps"), join(noGit, "apps"), { recursive: true }); cpSync(join(sb.d, "features"), join(noGit, "features"), { recursive: true }); writeFileSync(join(noGit, "package.json"), "{}");
  assert.match(spawnSync(process.execPath, [join(sb.d, "scripts/portal-fingerprint.mjs"), "--root", noGit, "--app", "admin"], { encoding: "utf8" }).stdout.trim(), /^[0-9a-f]{16}$/, "works without git too");
});
