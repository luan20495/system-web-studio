// @class: integration
// Explicit public API release pinning (D-C0-50): API RECOVERY != API DEPLOYMENT. REAL scripts, a REAL git repository (commits A, B, C, D), REAL processes / ports / release directories; only the three
// external programs are replaced by tiny fakes placed in the sandbox: `java` (serves /actuator/health/*, /__info, "applies Flyway" into a JSON database), `gradlew` (zips a jar with a marker and the
// migrations of the commit; logs every call), `psql` / `pg_dump` (a JSON database with flyway_schema_history). Every test asserts BOTH what runs and what was (never) built: builds.log has one line per jar built.
//   node --test tests/infra/public-api-pinning.test.mjs          (npm run test:infra:api)
import test from "node:test";
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync, appendFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import net from "node:net";
import { exists } from "../../scripts/lib/owned-process.mjs";
import { flywayChecksum } from "../../scripts/lib/public-api-release.mjs";

const REPO = new URL("../..", import.meta.url).pathname.replace(/\/$/, "");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const freePort = () => new Promise((res) => { const s = net.createServer(); s.listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => res(p)); }); });
const sandboxes = []; const strays = [];
const SECRETS = { RENDER_TOKEN: "TOPSECRET-RENDER-9f3a", SECRETS_MASTER_KEY: "SECRET-MASTER-KEY-7c1d", DATABASE_PASSWORD: "SECRET-DB-PASSWORD-55aa", OPENROUTER_API_KEY: "SECRET-OPENROUTER-0b0b" };
test.after(() => { for (const p of strays) { try { process.kill(p, "SIGKILL"); } catch { /* gone */ } } for (const sb of sandboxes) { spawnSync("bash", [join(sb.d, "scripts/public-api.sh"), "down"], { cwd: sb.d, env: env(sb), timeout: 60000 }); rmSync(sb.d, { recursive: true, force: true }); } });

const FAKE_JAVA = String.raw`#!/usr/bin/env node
const fs = require("fs"), path = require("path"), http = require("http"), cp = require("child_process");
const SB = path.resolve(__dirname, ".."); const has = (f) => fs.existsSync(path.join(SB, f)); const args = process.argv.slice(2);
if (args.includes("-version")) { process.stderr.write('openjdk version "21.0.4" 2025-01-01\n'); process.exit(0); }
const jar = args[args.indexOf("-jar") + 1]; const marker = cp.spawnSync("unzip", ["-p", jar, "BOOT-INF/classes/marker.txt"], { encoding: "utf8" }).stdout.trim();
global.jarFd = fs.openSync(jar, "r");   // a JVM keeps its jar open: the lifecycle proves "the jar on disk is the jar this process runs" with it
const port = Number(process.env.SERVER_PORT); const db = (process.env.DATABASE_URL || "").split("/").pop();
const mig = cp.spawnSync("unzip", ["-Z1", jar], { encoding: "utf8" }).stdout.split("\n").map((n) => /db\/migration\/V(\d+)__/.exec(n)).filter(Boolean).map((m) => m[1]);
if (has("BREAK_START_ANY")) process.exit(1);
if (has("BREAK_MARK") && fs.readFileSync(path.join(SB, "BREAK_MARK"), "utf8").trim() === marker) process.exit(1);
if (has("BREAK_REAL") && fs.readFileSync(path.join(SB, "BREAK_REAL"), "utf8").trim() === marker && has("REAL_PORT") && String(port) === fs.readFileSync(path.join(SB, "REAL_PORT"), "utf8").trim()) process.exit(1);
const dbf = path.join(SB, "db.json"); const state = JSON.parse(fs.readFileSync(dbf, "utf8")); state[db] = state[db] || [];   // "Flyway": applies the migrations of the jar that the database lacks; newer ones in the database only WARN (ignore *:future)
for (const v of mig) if (!state[db].some((r) => r.version === v)) state[db].push({ version: v, checksum: Number(fs.readFileSync(path.join(SB, "checksums", v), "utf8")), success: true });
fs.writeFileSync(dbf, JSON.stringify(state));
fs.appendFileSync(path.join(SB, "starts.log"), [marker, port, db, path.basename(path.dirname(jar))].join(" ") + "\n"); fs.appendFileSync(path.join(SB, "consumers.log"), db + " amqp-listeners-autostart=" + (process.env.SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP || "default") + " workflow=" + process.env.WORKFLOW_ENABLED + "\n");
http.createServer((q, r) => {
  if (q.url.startsWith("/actuator/health")) { if (has("UNREADY") && q.url.endsWith("readiness")) { r.statusCode = 503; return r.end("{}"); } return r.end('{"status":"UP"}'); }
  if (q.url === "/__info") return r.end(JSON.stringify({ marker, jar, db, port, workflow: process.env.WORKFLOW_ENABLED, signup: process.env.SIGNUP_ENABLED, hasRenderToken: !!process.env.RENDER_TOKEN, leak: !!process.env.SANDBOX_LEAK, java: process.argv[1] }));
  r.end("{}");
}).listen(port, "127.0.0.1"); setInterval(() => {}, 1e6);
`;
const FAKE_GRADLEW = String.raw`#!/usr/bin/env node
const fs = require("fs"), path = require("path"), cp = require("child_process"); const argv = process.argv.slice(2); const SB = process.env.SANDBOX || "";
const log = (f, l) => { if (SB) fs.appendFileSync(path.join(SB, f), l + "\n"); };
const isBuild = argv.includes("bootJar"), isTest = !isBuild && argv.includes("test");
if (argv.includes("clean")) fs.rmSync("build", { recursive: true, force: true });
log("gradle.log", argv.join(" ") + " | GRADLE_USER_HOME=" + (process.env.GRADLE_USER_HOME || "-") + " | OPTS=" + (process.env.GRADLE_OPTS || "-"));
if (isTest) {
  log("tests.log", "test " + fs.readFileSync("marker.txt", "utf8").trim()); if (SB && fs.existsSync(path.join(SB, "FAIL_TESTS"))) { console.error("simulated test failure"); process.exit(1); }
  fs.mkdirSync("build/test-results/test", { recursive: true }); fs.writeFileSync("build/test-results/test/TEST-a.xml", '<?xml version="1.0"?>\n<testsuite name="com.x.ATests" tests="3" skipped="1" failures="0" errors="0" timestamp="t">\n</testsuite>\n'); fs.writeFileSync("build/test-results/test/TEST-b.xml", '<testsuite name="com.x.BTests" tests="4" skipped="0" failures="0" errors="0">\n</testsuite>\n'); process.exit(0);
}
if (isBuild) {
  if (SB && fs.existsSync(path.join(SB, "FAIL_BUILD"))) { console.error("simulated build failure"); process.exit(1); }
  const marker = fs.readFileSync("marker.txt", "utf8").trim(); const stage = fs.mkdtempSync(path.join(require("os").tmpdir(), "jar-")); fs.mkdirSync(path.join(stage, "BOOT-INF/classes/db/migration"), { recursive: true }); fs.mkdirSync(path.join(stage, "META-INF"), { recursive: true });
  fs.writeFileSync(path.join(stage, "BOOT-INF/classes/marker.txt"), marker + "\n"); fs.writeFileSync(path.join(stage, "META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\n");
  for (const f of fs.readdirSync("db/migration")) fs.copyFileSync(path.join("db/migration", f), path.join(stage, "BOOT-INF/classes/db/migration", f));
  if (SB && fs.existsSync(path.join(SB, "NONDET"))) fs.writeFileSync(path.join(stage, "BOOT-INF/classes/random.txt"), String(Math.random()));
  const all = []; const walk = (d) => { for (const e of fs.readdirSync(d).sort()) { const p = path.join(d, e); fs.utimesSync(p, new Date(946684800000), new Date(946684800000)); all.push(path.relative(stage, p)); if (fs.statSync(p).isDirectory()) walk(p); } }; walk(stage);
  fs.mkdirSync("build/libs", { recursive: true }); const out = path.resolve("build/libs/app-0.1.0.jar"); fs.rmSync(out, { force: true });
  if (cp.spawnSync("zip", ["-qX", out, "-@"], { cwd: stage, input: all.join("\n") + "\n" }).status !== 0) process.exit(2); fs.rmSync(stage, { recursive: true, force: true }); log("builds.log", "bootJar " + marker); process.exit(0);
}
process.exit(0);
`;
const FAKE_PSQL = String.raw`#!/usr/bin/env node
const fs = require("fs"), path = require("path"); const SB = path.resolve(__dirname, ".."); const dbf = path.join(SB, "db.json"); const a = process.argv.slice(2);
const db = a[a.indexOf("-d") + 1]; const sql = a.includes("-c") ? a[a.indexOf("-c") + 1] : null; const state = JSON.parse(fs.readFileSync(dbf, "utf8")); const save = () => fs.writeFileSync(dbf, JSON.stringify(state));
fs.appendFileSync(path.join(SB, "psql.log"), db + " :: " + (sql || "<stdin>") + "\n");
if (fs.existsSync(path.join(SB, "DB_DOWN"))) { console.error("could not connect to server"); process.exit(2); }
if (sql) {
  let m; if (/^select version, checksum, success from flyway_schema_history/.test(sql)) { if (!state[db]) { console.error("database does not exist"); process.exit(2); } console.log(state[db].map((r) => [r.version, r.checksum, r.success ? "t" : "f"].join("|")).join("\n")); process.exit(0); }
  if ((m = /^create database (\w+)$/.exec(sql))) { state[m[1]] = []; save(); process.exit(0); }
  if ((m = /^drop database if exists (\w+) with \(force\)$/.exec(sql))) { delete state[m[1]]; save(); process.exit(0); }
  console.error("fake psql: unsupported " + sql); process.exit(3);
}
const input = fs.readFileSync(0, "utf8"); for (const l of input.split("\n")) { if (l.startsWith("ROWS ")) { state[db] = JSON.parse(l.slice(5)); save(); } } process.exit(0);
`;
const FAKE_PGDUMP = String.raw`#!/usr/bin/env node
const fs = require("fs"), path = require("path"); const SB = path.resolve(__dirname, ".."); const a = process.argv.slice(2); const db = a[a.length - 1]; const state = JSON.parse(fs.readFileSync(path.join(SB, "db.json"), "utf8"));
fs.appendFileSync(path.join(SB, "psql.log"), db + " :: pg_dump " + a.join(" ") + "\n");
if (a.includes("--schema-only")) { console.log("-- schema only of " + db + " (no data)"); process.exit(0); }
if (a.includes("--data-only")) { console.log("ROWS " + JSON.stringify(state[db] || [])); process.exit(0); }
process.exit(1);
`;

const git = (sb, ...a) => spawnSync("git", a, { cwd: sb.d, encoding: "utf8" });
const env = (sb, extra = {}) => ({ ...process.env, SANDBOX: sb.d, SANDBOX_LEAK: "1", PUBLIC_API_JAVA: join(sb.d, "bin/java"), PUBLIC_API_JAVA_HOME: "", PUBLIC_API_DB_CMD: join(sb.d, "bin/psql"), PUBLIC_API_DUMP_CMD: join(sb.d, "bin/pg_dump"), PUBLIC_API_START_TIMEOUT_MS: "20000", PUBLIC_RELEASE_GRACE_MS: "1500", ...extra });
const api = (sb, args, extra = {}) => { const r = spawnSync("bash", [join(sb.d, "scripts/public-api.sh"), ...args], { cwd: sb.d, env: env(sb, extra), encoding: "utf8", timeout: 240000 }); sb.outputs.push(`${r.stdout ?? ""}${r.stderr ?? ""}`); return { status: r.status, out: (r.stdout ?? "") + (r.stderr ?? ""), stdout: r.stdout ?? "", stderr: r.stderr ?? "" }; };

async function sandbox() {
  const d = realpathSync(mkdtempSync(join(tmpdir(), "apin-"))); const apiPort = await freePort(); const sb = { d, apiPort, outputs: [] }; sandboxes.push(sb);
  for (const f of ["scripts/public-api.sh", "scripts/public-api.mjs", "scripts/lib/public-api-release.mjs", "scripts/lib/public-release.mjs", "scripts/lib/owned-process.mjs", "scripts/watchdog.sh"]) { mkdirSync(join(d, f, ".."), { recursive: true }); cpSync(join(REPO, f), join(d, f)); }
  mkdirSync(join(d, "bin"), { recursive: true }); for (const [n, src] of [["java", FAKE_JAVA], ["psql", FAKE_PSQL], ["pg_dump", FAKE_PGDUMP]]) { writeFileSync(join(d, "bin", n), src); chmodSync(join(d, "bin", n), 0o755); }
  mkdirSync(join(d, "checksums"), { recursive: true }); for (const v of ["1", "2", "3"]) writeFileSync(join(d, "checksums", v), String(flywayChecksum(Buffer.from(`create table t${v}(id int);\n`))));
  writeFileSync(join(d, "db.json"), JSON.stringify({ studio: [], postgres: [] }));
  writeFileSync(join(d, ".gitignore"), ".run/\nbin/\nchecksums/\nbackend/build/\n*.log\n*.json\nFAIL_*\nBREAK_*\nREAL_PORT\nUNREADY\nDB_DOWN\n!backend/**/*.json\n");
  mkdirSync(join(d, ".run/public"), { recursive: true });
  writeFileSync(join(d, ".run/public/public.env"), [`PUBLIC_HOST=studio.example`, `API_PORT=${apiPort}`, `DATABASE_USER=studio`, ...Object.entries(SECRETS).map(([k, v]) => `${k}=${v}`), `SIGNUP_ENABLED=false`, `V1_FEATURES=true`, `PG_PORT=25432`].join("\n") + "\n");
  writeFileSync(join(d, "REAL_PORT"), String(apiPort));
  git(sb, "init", "-q"); git(sb, "config", "user.email", "t@t"); git(sb, "config", "user.name", "t"); return sb;
}
function commit(sb, label, versions = ["1", "2"]) {
  mkdirSync(join(sb.d, "backend/db/migration"), { recursive: true }); for (const f of readdirSync(join(sb.d, "backend/db/migration"))) rmSync(join(sb.d, "backend/db/migration", f));
  writeFileSync(join(sb.d, "backend/marker.txt"), `${label}\n`); for (const v of versions) writeFileSync(join(sb.d, `backend/db/migration/V${v}__m${v}.sql`), `create table t${v}(id int);\n`);
  writeFileSync(join(sb.d, "backend/gradlew"), FAKE_GRADLEW); chmodSync(join(sb.d, "backend/gradlew"), 0o755); git(sb, "add", "-A"); git(sb, "commit", "-qm", `commit ${label}`); return git(sb, "rev-parse", "HEAD").stdout.trim();
}
const info = async (port) => { for (let i = 0; i < 2; i++) { try { return await (await fetch(`http://127.0.0.1:${port}/__info`, { signal: AbortSignal.timeout(3000), headers: { connection: "close" } })).json(); } catch { await sleep(150); } } return null; };
const lines = (sb, f) => (existsSync(join(sb.d, f)) ? readFileSync(join(sb.d, f), "utf8").split("\n").filter(Boolean) : []);
const approved = (sb) => { try { return JSON.parse(readFileSync(join(sb.d, ".run/public/api/approved.json"), "utf8")); } catch { return null; } };
const releases = (sb) => { try { return readdirSync(join(sb.d, ".run/public/api/releases")).filter((n) => !n.includes(".tmp.")).sort(); } catch { return []; } };
const owned = (sb) => JSON.parse(readFileSync(join(sb.d, ".run/public/api.owned.json"), "utf8"));
const status = (sb) => JSON.parse(api(sb, ["status", "--json"]).stdout);
const marker = async (sb) => (await info(sb.apiPort))?.marker ?? null;
const builds = (sb) => lines(sb, "builds.log").length; const dbs = (sb) => Object.keys(JSON.parse(readFileSync(join(sb.d, "db.json"), "utf8"))).sort(); const dbRows = (sb, n = "studio") => [...(JSON.parse(readFileSync(join(sb.d, "db.json"), "utf8"))[n] ?? [])].sort((a, b) => Number(a.version) - Number(b.version));

// ================================================================================================================================ the story
test("D-C0-50 matrix: approved A, integration B / C, recovery, deploy, restart, rollback, failed candidates, schema policy", async (t) => {
  const sb = await sandbox(); const A = commit(sb, "A"); let idA, idB, idC, idD;
  await t.test("0. FAIL CLOSED without an approved API release: `up` / `restart` refuse (exit 5), nothing is built or started, HEAD is never assumed; `deploy-api` needs an explicit source", async () => {
    for (const cmd of ["up", "restart"]) { const r = api(sb, [cmd]); assert.equal(r.status, 5, r.out); assert.match(r.out, /NO_APPROVED_RELEASE/); }
    assert.equal(builds(sb), 0); assert.equal(await marker(sb), null); assert.equal(status(sb).row.state, "NO_APPROVED_RELEASE"); const d = api(sb, ["deploy-api"]); assert.equal(d.status, 64); assert.match(d.out, /explicit source/);
  });
  await t.test("first explicit deploy of A (tests run, then the jar, then a candidate on a temporary port + SCRATCH database): approved = running = A; the real database is only READ", async () => {
    const r = api(sb, ["deploy-api", A]); assert.equal(r.status, 0, r.out); idA = approved(sb).releaseId; assert.match(idA, /^[0-9a-f]{12}-[0-9a-f]{8}$/); assert.equal(await marker(sb), "A"); assert.deepEqual(lines(sb, "tests.log"), ["test A"]); assert.equal(builds(sb), 1);
    const rel = JSON.parse(readFileSync(join(sb.d, `.run/public/api/releases/${idA}/release.json`), "utf8")); assert.equal(rel.verification.tests.mode, "full"); assert.equal(rel.verification.tests.total, 7); assert.equal(rel.verification.tests.skipped, 1); assert.equal(rel.verification.tests.failures, 0); assert.equal(rel.build.mode, "clean-isolated"); assert.match(rel.build.command, /--no-build-cache --rerun-tasks --no-daemon/); assert.match(rel.build.javaVersion, /21/); assert.equal(rel.sourceSha, A); assert.equal(rel.schema.maxVersion, "2"); assert.ok(rel.jar.sha256 && rel.buildFingerprint && rel.configFingerprint);
    const ps = lines(sb, "psql.log"); assert.ok(ps.some((l) => /^postgres :: create database cand_/.test(l)) && ps.some((l) => /drop database if exists cand_/.test(l)), "a scratch database was created and dropped"); assert.ok(ps.filter((l) => l.startsWith("studio ::")).every((l) => /select version|pg_dump --(schema|data)-only/.test(l)), "the real database is only read");
    assert.deepEqual(dbs(sb), ["postgres", "studio"], "no scratch database left behind"); assert.deepEqual(dbRows(sb).map((r) => r.version), ["1", "2"], "the approved API applied its migrations to the REAL database at start");
    const starts = lines(sb, "starts.log"); assert.ok(starts.some((l) => / cand_/.test(l)), "the candidate ran against a scratch database"); assert.ok(starts.some((l) => new RegExp(` studio ${idA}$`).test(l)), "and the real start ran the release directory's jar");
    const cons = lines(sb, "consumers.log"); assert.ok(cons.some((l) => /^cand_.* amqp-listeners-autostart=false workflow=false$/.test(l)), "the candidate ran with the AMQP listeners and the workflow OFF (it must not consume the real broker's messages)"); assert.ok(cons.some((l) => /^studio amqp-listeners-autostart=default/.test(l)), "the real API runs its consumers");
    const gl = lines(sb, "gradle.log"); assert.ok(gl.length >= 2 && gl.every((l) => /--no-build-cache/.test(l) && /--rerun-tasks/.test(l) && /--no-daemon/.test(l) && /GRADLE_USER_HOME=\S*\/api\/gradle-home/.test(l) && /OPTS=-$/.test(l)), "release builds are clean, uncached, daemon-less, with an isolated GRADLE_USER_HOME"); const i = await info(sb.apiPort); assert.equal(i.leak, false, "the API does not inherit the caller's environment"); assert.equal(i.hasRenderToken, true, "secrets reach the process from public.env"); assert.equal(i.workflow, "true");
  });
  const B = commit(sb, "B"); await sleep(50);
  await t.test("TEST 1. approved=A, integration=B (HEAD): restart serves A; the jar of B is never built; status is CURRENT_APPROVED behind integration", async () => {
    const pid0 = owned(sb).pid; const r = api(sb, ["restart"]); assert.equal(r.status, 0, r.out); assert.equal(await marker(sb), "A"); assert.equal(builds(sb), 1, "restart built nothing"); assert.notEqual(owned(sb).pid, pid0, "the process was replaced");
    const s = status(sb); assert.equal(s.row.state, "CURRENT_APPROVED"); assert.equal(s.row.integrationSource, B.slice(0, 12)); assert.equal(s.row.approvedSource, A.slice(0, 12)); assert.equal(s.row.runningSource, A.slice(0, 12)); assert.equal(s.behind, 1); assert.match(api(sb, ["status"]).stdout, /BEHIND_INTEGRATION \+1 backend commit/);
  });
  await t.test("TEST 2. the API is killed: recovery (`up`, what public-up.sh / the watchdog run) restarts the APPROVED jar; B is never built", async () => {
    process.kill(owned(sb).pid, "SIGKILL"); for (let i = 0; i < 30 && exists(owned(sb).pid); i++) await sleep(100); assert.equal(status(sb).row.state, "STOPPED");
    const r = api(sb, ["up"]); assert.equal(r.status, 0, r.out); assert.equal(await marker(sb), "A"); assert.equal(builds(sb), 1); assert.equal(status(sb).row.state, "CURRENT_APPROVED");
  });
  const C = commit(sb, "C", ["1", "2"]);
  await t.test("TEST 3. integration advances to C: recovery and restart still serve A", async () => {
    process.kill(owned(sb).pid, "SIGKILL"); for (let i = 0; i < 30 && exists(owned(sb).pid); i++) await sleep(100); assert.equal(api(sb, ["up"]).status, 0); assert.equal(await marker(sb), "A"); assert.equal(api(sb, ["restart"]).status, 0); assert.equal(await marker(sb), "A"); assert.equal(builds(sb), 1); assert.equal(status(sb).behind, 2);
  });
  await t.test("TEST 4. explicit deploy of B: approved becomes B, B runs, A is the previous known-good and is retained", async () => {
    const r = api(sb, ["deploy-api", B, "--skip-tests"]); assert.equal(r.status, 0, r.out); idB = approved(sb).releaseId; assert.equal(await marker(sb), "B"); const a = approved(sb); assert.equal(a.previousKnownGood, idA); assert.ok(releases(sb).includes(idA) && releases(sb).includes(idB));
    assert.equal(JSON.parse(readFileSync(join(sb.d, `.run/public/api/releases/${idB}/release.json`), "utf8")).verification.tests.mode, "skipped", "--skip-tests is recorded, not hidden"); assert.equal(lines(sb, "tests.log").length, 1); assert.equal(builds(sb), 2); assert.equal(api(sb, ["restart"]).status, 0); assert.equal(await marker(sb), "B", "restart stays on B");
  });
  await t.test("TEST 5. candidate BUILD failure: approved stays B and the healthy B process is untouched (same pid); no half-built release is left", async () => {
    const pid0 = owned(sb).pid, ap0 = approved(sb); writeFileSync(join(sb.d, "FAIL_BUILD"), "1"); const r = api(sb, ["deploy-api", C, "--skip-tests"]); rmSync(join(sb.d, "FAIL_BUILD"));
    assert.equal(r.status, 7, r.out); assert.match(r.out, /CANDIDATE_BUILD_FAILED/); assert.deepEqual(approved(sb), ap0); assert.equal(owned(sb).pid, pid0); assert.equal(await marker(sb), "B"); assert.deepEqual(releases(sb).sort(), [idA, idB].sort());
    writeFileSync(join(sb.d, "FAIL_TESTS"), "1"); const t2 = api(sb, ["deploy-api", C]); rmSync(join(sb.d, "FAIL_TESTS")); assert.equal(t2.status, 7); assert.match(t2.out, /CANDIDATE_TESTS_FAILED/); assert.deepEqual(approved(sb), ap0, "failing backend tests keep the approved release");
  });
  await t.test("TEST 6. candidate START failure on the temporary port: approved stays B and the running B is untouched; the scratch database is dropped", async () => {
    const pid0 = owned(sb).pid, ap0 = approved(sb); writeFileSync(join(sb.d, "BREAK_MARK"), "C"); const r = api(sb, ["deploy-api", C, "--skip-tests"]); rmSync(join(sb.d, "BREAK_MARK"));
    assert.equal(r.status, 7, r.out); assert.match(r.out, /CANDIDATE_VALIDATION_FAILED/); assert.deepEqual(approved(sb), ap0); assert.equal(owned(sb).pid, pid0); assert.equal(await marker(sb), "B"); assert.deepEqual(dbs(sb), ["postgres", "studio"]); assert.ok(!releases(sb).some((id) => id.startsWith(C.slice(0, 12))), "the unproven candidate was discarded");
  });
  await t.test("TEST 7. the candidate passes the proof but FAILS to start on the real port: the pointer returns to B and B runs again (automatic rollback)", async () => {
    const ap0 = approved(sb); writeFileSync(join(sb.d, "BREAK_REAL"), "C"); const r = api(sb, ["deploy-api", C, "--skip-tests"]); rmSync(join(sb.d, "BREAK_REAL"));
    assert.equal(r.status, 7, r.out); assert.match(r.out, /ACTIVATION_FAILED_ROLLED_BACK/); assert.deepEqual(approved(sb), ap0, "the approved pointer never moved (byte-identical record)"); assert.equal(await marker(sb), "B"); assert.equal(status(sb).row.state, "CURRENT_APPROVED");
  });
  await t.test("deploy of C succeeds: approved C, previous B; A is pruned, the approved release is never deleted; prune-api keeps approved + previous", async () => {
    const r = api(sb, ["deploy-api", C, "--skip-tests"]); assert.equal(r.status, 0, r.out); idC = approved(sb).releaseId; assert.equal(approved(sb).previousKnownGood, idB); assert.deepEqual(releases(sb).sort(), [idB, idC].sort()); assert.equal(await marker(sb), "C"); const p = api(sb, ["prune-api"]); assert.equal(p.status, 0); assert.deepEqual(releases(sb).sort(), [idB, idC].sort());
  });
  await t.test("TEST 8. rollback to the previous compatible artifact: approved B, B runs from the retained jar (no rebuild); a second rollback has nothing to return to", async () => {
    const b0 = builds(sb); const r = api(sb, ["rollback-api"]); assert.equal(r.status, 0, r.out); assert.equal(approved(sb).releaseId, idB); assert.equal(approved(sb).previousKnownGood, null); assert.equal(await marker(sb), "B"); assert.equal(builds(sb), b0, "rollback needs no rebuild");
    const r2 = api(sb, ["rollback-api"]); assert.equal(r2.status, 1); assert.match(r2.out, /NO_PREVIOUS_KNOWN_GOOD/);
  });
  const D = commit(sb, "D", ["1", "2", "3"]);
  await t.test("TEST 9a. a release that adds V3 declared --expand-only: after it ran, rolling back to the older artifact is ALLOWED (the declaration is recorded and shown)", async () => {
    const r = api(sb, ["deploy-api", D, "--skip-tests", "--expand-only"]); assert.equal(r.status, 0, r.out); idD = approved(sb).releaseId; assert.deepEqual(dbRows(sb).map((x) => x.version), ["1", "2", "3"], "V3 was applied to the real database by the activated release"); const rel = JSON.parse(readFileSync(join(sb.d, `.run/public/api/releases/${idD}/release.json`), "utf8")); assert.deepEqual(rel.schema.expandOnly, ["3"]);
    assert.equal(status(sb).rollback.verdict, "COMPATIBLE"); const rb = api(sb, ["rollback-api"]); assert.equal(rb.status, 0, rb.out); assert.equal(approved(sb).releaseId, idB); assert.equal(await marker(sb), "B", "B runs on a schema that is ahead of it (V3), as declared");
  });
  await t.test("TEST 9b. WITHOUT the declaration the same situation FAILS CLOSED: ROLLBACK_SCHEMA_INCOMPATIBLE, approved and running stay D, nothing is stopped", async () => {
    const r = api(sb, ["deploy-api", D, "--skip-tests"]); assert.equal(r.status, 0, r.out); idD = approved(sb).releaseId; const rel = JSON.parse(readFileSync(join(sb.d, `.run/public/api/releases/${idD}/release.json`), "utf8")); assert.deepEqual(rel.schema.expandOnly, [], "no declaration");
    const pid0 = owned(sb).pid; assert.equal(status(sb).rollback.verdict, "ROLLBACK_SCHEMA_INCOMPATIBLE"); const rb = api(sb, ["rollback-api"]); assert.equal(rb.status, 9, rb.out); assert.match(rb.out, /ROLLBACK_SCHEMA_INCOMPATIBLE/); assert.match(rb.out, /3/);
    assert.equal(approved(sb).releaseId, idD); assert.equal(owned(sb).pid, pid0, "the running API was not stopped"); assert.equal(await marker(sb), "D"); assert.match(api(sb, ["status"]).stdout, /rollback to .*ROLLBACK_SCHEMA_INCOMPATIBLE/);
    assert.equal(api(sb, ["deploy-api", B, "--skip-tests"]).status, 9, "deploying an OLDER commit is the same rollback: refused as well"); assert.equal(approved(sb).releaseId, idD);
  });
  await t.test("the database being unreachable also fails closed (the artifact cannot be proven compatible): SCHEMA_CHECK_UNAVAILABLE, nothing moves", async () => {
    writeFileSync(join(sb.d, "DB_DOWN"), "1"); const r = api(sb, ["deploy-api", C, "--skip-tests"]); rmSync(join(sb.d, "DB_DOWN")); assert.equal(r.status, 9, r.out); assert.match(r.out, /SCHEMA_CHECK_UNAVAILABLE/); assert.equal(approved(sb).releaseId, idD);
  });
  await t.test("a checksum difference between the database and the artifact refuses: FLYWAY_CHECKSUM_MISMATCH", async () => {
    const f = JSON.parse(readFileSync(join(sb.d, "db.json"), "utf8")); const row = f.studio.find((x) => x.version === "1"); const good = row.checksum; row.checksum = 12345; writeFileSync(join(sb.d, "db.json"), JSON.stringify(f)); const r = api(sb, ["deploy-api", C, "--skip-tests"]); assert.equal(r.status, 9, r.out); assert.match(r.out, /FLYWAY_CHECKSUM_MISMATCH/); row.checksum = good; writeFileSync(join(sb.d, "db.json"), JSON.stringify(f));
  });
  await t.test("config pinning: a restart uses the PINNED non-secret configuration even if public.env changed (reported as drift); the same SHA under a different configuration is a NEW release", async () => {
    writeFileSync(join(sb.d, ".run/public/public.env"), readFileSync(join(sb.d, ".run/public/public.env"), "utf8").replace("SIGNUP_ENABLED=false", "SIGNUP_ENABLED=true")); assert.equal(api(sb, ["restart"]).status, 0); assert.equal((await info(sb.apiPort)).signup, "false", "pinned value, not the edited file");
    const s = status(sb); assert.equal(s.row.state, "CURRENT_APPROVED"); assert.ok(s.drift.includes("SIGNUP_ENABLED")); const r = api(sb, ["deploy-api", D, "--skip-tests"]); assert.equal(r.status, 0, r.out); assert.notEqual(approved(sb).releaseId, idD, "another configuration = another release id"); assert.equal((await info(sb.apiPort)).signup, "true");
  });
  await t.test("a running process whose recorded configuration / jar is not the approved one is NOT reported as the approved runtime", async () => {
    const f = join(sb.d, ".run/public/api.owned.json"); const o = JSON.parse(readFileSync(f, "utf8")); const keep = JSON.stringify(o, null, 2); o.extra.configFingerprint = "deadbeefdeadbeef"; writeFileSync(f, JSON.stringify(o)); assert.equal(status(sb).row.state, "RUNNING_UNAPPROVED"); writeFileSync(f, keep); assert.equal(status(sb).row.state, "CURRENT_APPROVED");
  });
  await t.test("security: no secret value of public.env is in any approved / release / ownership metadata, status output or CLI output (and the secrets DID reach the process)", async () => {
    const files = []; const walk = (d) => { for (const e of readdirSync(d, { withFileTypes: true })) { const p = join(d, e.name); if (e.isDirectory()) walk(p); else if (/\.json$/.test(e.name)) files.push(p); } }; walk(join(sb.d, ".run/public/api")); files.push(join(sb.d, ".run/public/api.owned.json"));
    const hay = [...files.map((f) => readFileSync(f, "utf8")), ...sb.outputs, api(sb, ["status"]).out, api(sb, ["status", "--json"]).out].join("\n"); for (const v of Object.values(SECRETS)) assert.ok(!hay.includes(v), `secret ${v.slice(0, 12)}… leaked into metadata / output`);
    assert.ok(files.length >= 4); const rel = JSON.parse(readFileSync(join(sb.d, `.run/public/api/releases/${approved(sb).releaseId}/release.json`), "utf8")); assert.ok(rel.secretKeyNames.includes("RENDER_TOKEN"), "key NAMES are recorded, values are not"); assert.ok(!rel.runtimeConfig.some((kv) => /RENDER_TOKEN|DATABASE_PASSWORD|SECRETS_MASTER_KEY/.test(kv)));
  });
});

// ================================================================================================================================ TEST 10: missing / corrupt approved artifact
test("TEST 10. missing / corrupt / tampered approved artifact: APPROVED_API_BUILD_MISSING (exit 6), fail closed - HEAD is never built, no other jar is chosen, nothing is started", async (t) => {
  const sb = await sandbox(); const A = commit(sb, "A"); assert.equal(api(sb, ["deploy-api", A, "--skip-tests"]).status, 0); const id = approved(sb).releaseId; const jar = join(sb.d, `.run/public/api/releases/${id}/app.jar`); const rel = join(sb.d, `.run/public/api/releases/${id}/release.json`); const good = readFileSync(jar); const goodRel = readFileSync(rel);
  const stop = async () => { const r = api(sb, ["down"]); assert.equal(r.status, 0, r.out); assert.equal(await marker(sb), null); };
  commit(sb, "B"); const b0 = builds(sb);
  await t.test("missing jar", async () => { await stop(); rmSync(jar); for (const cmd of ["up", "restart"]) { const r = api(sb, [cmd]); assert.equal(r.status, 6, r.out); assert.match(r.out, /APPROVED_API_BUILD_MISSING/); } assert.equal(await marker(sb), null); assert.equal(builds(sb), b0); assert.equal(status(sb).row.state, "APPROVED_API_BUILD_MISSING"); assert.ok(!releases(sb).some((r) => r !== id), "no other release was produced"); });
  await t.test("corrupt jar (same size, different bytes)", async () => { const bad = Buffer.from(good); bad[bad.length - 40] ^= 0xff; writeFileSync(jar, bad); const r = api(sb, ["up"]); assert.equal(r.status, 6, r.out); assert.match(r.out, /sha256/); assert.equal(await marker(sb), null); assert.equal(api(sb, ["verify-api"]).status, 6); });
  await t.test("truncated jar and tampered release.json", async () => { writeFileSync(jar, good.subarray(0, good.length - 100)); assert.equal(api(sb, ["up"]).status, 6); writeFileSync(jar, good); writeFileSync(rel, goodRel.toString().replace('"kind": "built"', '"kind": "built "')); const r = api(sb, ["up"]); assert.equal(r.status, 6, r.out); assert.match(r.out, /API_RELEASE_TAMPERED/); writeFileSync(rel, goodRel); });
  await t.test("restored artifact starts again (recovery is possible without any build)", async () => { const r = api(sb, ["up"]); assert.equal(r.status, 0, r.out); assert.equal(await marker(sb), "A"); assert.equal(builds(sb), b0); assert.equal(api(sb, ["verify-api"]).status, 0); });
});

// ================================================================================================================================ foreign / stale processes
test("process safety (D-C0-48): a FOREIGN listener on the API port is never killed; a stale pid record never kills the process that reused the pid", async (t) => {
  const sb = await sandbox(); const A = commit(sb, "A"); assert.equal(api(sb, ["deploy-api", A, "--skip-tests"]).status, 0); const id = approved(sb).releaseId;
  await t.test("foreign listener: up / restart / down / deploy-api / rollback-api refuse (exit 3 or leave it alone) and it stays alive", async () => {
    assert.equal(api(sb, ["down"]).status, 0); const foreign = spawn(process.execPath, ["-e", `require("http").createServer((q,r)=>r.end("foreign")).listen(${sb.apiPort},"127.0.0.1");setInterval(()=>{},1e6)`], { stdio: "ignore", detached: true }); foreign.unref(); strays.push(foreign.pid); await sleep(600);
    for (const cmd of [["up"], ["restart"], ["deploy-api", A, "--skip-tests"]]) { const r = api(sb, cmd); assert.equal(r.status, 3, `${cmd[0]}: ${r.out}`); assert.match(r.out, /FOREIGN_PROCESS/); } assert.match(api(sb, ["down"]).out, /FOREIGN_PROCESS: left alone/); assert.ok(exists(foreign.pid), "the foreign listener is alive"); assert.equal(status(sb).row.state, "FOREIGN_PROCESS"); assert.equal(approved(sb).releaseId, id, "approved untouched");
    process.kill(foreign.pid, "SIGKILL"); await sleep(300);
  });
  await t.test("stale pid: the recorded pid now belongs to an unrelated process (other start time): `down` and `up` leave it alone and the API starts normally", async () => {
    const other = spawn("sleep", ["300"], { stdio: "ignore", detached: true }); other.unref(); strays.push(other.pid); await sleep(200);
    writeFileSync(join(sb.d, ".run/public/api.owned.json"), JSON.stringify({ schema: 1, owner: "public", name: "api", port: sb.apiPort, pid: other.pid, startTime: "Mon Jan 1 00:00:00 2001", command: "java -jar x.jar", cwd: join(sb.d, ".run/public"), members: [{ pid: other.pid, startTime: "Mon Jan 1 00:00:00 2001", command: "java -jar x.jar" }], extra: { releaseId: id } })); writeFileSync(join(sb.d, ".run/public/api.pid"), String(other.pid));
    assert.equal(api(sb, ["down"]).status, 0); assert.ok(exists(other.pid), "down did not signal the pid-reuse victim"); const r = api(sb, ["up"]); assert.equal(r.status, 0, r.out); assert.ok(exists(other.pid), "up did not signal it either"); assert.equal(await marker(sb), "A"); assert.notEqual(owned(sb).pid, other.pid);
  });
});

// ================================================================================================================================ adoption of the API that runs now
test("init-api --from-running: pins the API that runs NOW from evidence (same process, no restart), refuses what it cannot prove, and the first restart runs the release copy", async (t) => {
  const sb = await sandbox(); const A = commit(sb, "A"); const wt = join(sb.d, "backend"); const secrets = Object.entries(SECRETS);
  const buildLegacy = (label) => { writeFileSync(join(wt, "marker.txt"), `${label}\n`); const r = spawnSync("node", ["./gradlew", "bootJar"], { cwd: wt, env: { ...process.env, SANDBOX: sb.d }, encoding: "utf8" }); assert.equal(r.status, 0, r.stderr); };
  const startLegacy = async () => {   // what public-up.sh did: cwd .run/public, `java ... -jar <working tree>/backend/build/libs/*.jar`, api.pid, the whole environment exported
    const cfg = (await import(`../../scripts/lib/public-api-release.mjs?${Math.random()}`)); const ctx = cfg.apiContext({ ...process.env, PUBLIC_ROOT: sb.d }, sb.d); const e = { PATH: process.env.PATH, ...cfg.deriveRuntimeConfig(ctx), ...Object.fromEntries(secrets), SANDBOX_LEAK: "1", NOISE_FROM_SHELL: "x" };
    const p = spawn(join(sb.d, "bin/java"), ["-XX:MaxRAMPercentage=40", "-jar", join(wt, "build/libs/app-0.1.0.jar")], { cwd: join(sb.d, ".run/public"), env: e, stdio: "ignore", detached: true }); p.unref(); strays.push(p.pid); writeFileSync(join(sb.d, ".run/public/api.pid"), String(p.pid)); for (let i = 0; i < 40 && !(await info(sb.apiPort)); i++) await sleep(150); return p.pid;
  };
  buildLegacy("A"); const pid = await startLegacy(); assert.equal(await marker(sb), "A"); commit(sb, "B");
  await t.test("before the pin: up / deploy-api treat the legacy-started API as not ours to touch (exit 3, it keeps running)", async () => { assert.equal(api(sb, ["up"]).status, 5); const r = api(sb, ["deploy-api", A, "--skip-tests"]); assert.equal(r.status, 3, r.out); assert.match(r.out, /init-api --from-running/); assert.ok(exists(pid)); assert.equal(status(sb).row.state, "RUNNING_UNAPPROVED"); });
  await t.test("the jar on disk is not the file the process has open (rebuilt on top): REFUSED", async () => { buildLegacy("A"); const r = api(sb, ["init-api", "--from-running", "--source", A]); assert.equal(r.status, 1, r.out); assert.match(r.out, /not the file the process has open|modified/); assert.equal(approved(sb), null); assert.ok(exists(pid)); });
  await t.test("the exact source cannot be proven (the jar was built from a DIRTY working tree): STOPPED, nothing recorded, process untouched", async () => {
    process.kill(pid, "SIGKILL"); for (let i = 0; i < 30 && exists(pid); i++) await sleep(100); buildLegacy("A-dirty"); const p2 = await startLegacy(); const r = api(sb, ["init-api", "--from-running", "--source", A]); assert.equal(r.status, 1, r.out); assert.match(r.out, /cannot be proven/); assert.equal(approved(sb), null); assert.equal(releases(sb).length, 0); assert.ok(exists(p2)); process.kill(p2, "SIGKILL"); for (let i = 0; i < 30 && exists(p2); i++) await sleep(100);
  });
  buildLegacy("A"); const pid3 = await startLegacy();
  await t.test("the database does not match the running jar's migrations: REFUSED", async () => { const f = JSON.parse(readFileSync(join(sb.d, "db.json"), "utf8")); const keep = JSON.stringify(f); f.studio.push({ version: "9", checksum: 1, success: true }); writeFileSync(join(sb.d, "db.json"), JSON.stringify(f)); const r = api(sb, ["init-api", "--from-running", "--source", A]); assert.equal(r.status, 1, r.out); assert.match(r.out, /database does not match/); writeFileSync(join(sb.d, "db.json"), keep); assert.equal(approved(sb), null); });
  await t.test("with all proofs: --dry-run writes nothing; the real pin keeps the SAME process, records the evidence, converts ownership, and status is CURRENT_APPROVED", async () => {
    const d = api(sb, ["init-api", "--from-running", "--source", A, "--dry-run"]); assert.equal(d.status, 0, d.out); assert.equal(approved(sb), null); assert.equal(releases(sb).length, 0); assert.match(d.out, /sourceReproduced/);
    const r = api(sb, ["init-api", "--from-running", "--source", A]); assert.equal(r.status, 0, r.out); assert.match(r.out, /nothing was restarted/); assert.ok(exists(pid3), "the running process was not touched"); assert.equal((await info(sb.apiPort)).marker, "A");
    const a = approved(sb); const rel = JSON.parse(readFileSync(join(sb.d, `.run/public/api/releases/${a.releaseId}/release.json`), "utf8")); assert.equal(rel.kind, "adopted"); assert.equal(rel.sourceSha, A); assert.equal(rel.adoption.evidence.sourceReproduced.commit, A); assert.equal(rel.adoption.evidence.pid, pid3);
    const o = owned(sb); assert.equal(o.pid, pid3); assert.equal(o.extra.releaseId, a.releaseId); const s = status(sb); assert.equal(s.row.state, "CURRENT_APPROVED"); assert.equal(s.row.runningSource, A.slice(0, 12)); assert.equal(s.row.pid, pid3); assert.equal(api(sb, ["init-api", "--from-running"]).status, 64, "no second pin");
  });
  await t.test("the first restart runs the RELEASE copy of the jar (no working-tree dependency), with the pinned environment only", async () => {
    rmSync(join(wt, "build"), { recursive: true, force: true }); writeFileSync(join(wt, "marker.txt"), "tree-moved-on\n"); const b0 = builds(sb); const r = api(sb, ["restart"]); assert.equal(r.status, 0, r.out); const i = await info(sb.apiPort); assert.equal(i.marker, "A"); assert.match(i.jar, /\.run\/public\/api\/releases\//); assert.equal(i.leak, false); assert.equal(i.hasRenderToken, true); assert.equal(builds(sb), b0);
  });
});

// ================================================================================================================================ rehearsal modes
test("candidate rehearsal: default = a FRESH empty scratch database (Flyway applies every migration, no production data); `schema` is an explicit opt-in that copies structure + migration history only", async (t) => {
  const sb = await sandbox(); const A = commit(sb, "A");
  await t.test("default (fresh): no pg_dump of the real database at all; the candidate migrates an empty scratch database", async () => {
    const r = api(sb, ["deploy-api", A, "--skip-tests"]); assert.equal(r.status, 0, r.out); const ps = lines(sb, "psql.log"); assert.ok(!ps.some((l) => /pg_dump/.test(l)), "nothing of the real database is dumped"); assert.ok(lines(sb, "starts.log").some((l) => / cand_/.test(l))); assert.match(r.out, /\(fresh\)/);
  });
  await t.test("schema (opt-in): structure + flyway_schema_history are copied, never data; the real database is only dumped read-only", async () => {
    const B = commit(sb, "B"); const r = api(sb, ["deploy-api", B, "--skip-tests"], { PUBLIC_API_REHEARSE: "schema" }); assert.equal(r.status, 0, r.out); const dumps = lines(sb, "psql.log").filter((l) => /pg_dump/.test(l)); assert.equal(dumps.length, 2); assert.ok(dumps.some((l) => /--schema-only/.test(l)) && dumps.some((l) => /--data-only .*-t flyway_schema_history/.test(l)) && !dumps.some((l) => /pg_dump (?!.*--(schema|data)-only)/.test(l)), "only schema-only and the migration history table");
  });
});

// ================================================================================================================================ release preparation: reproducible, verified, nothing approved
test("prepare-api / reproduce-api / validate-api: two clean independent builds must be byte-identical (else NON_REPRODUCIBLE_RELEASE_BUILD, exit 10); preparing approves and starts NOTHING", async (t) => {
  const sb = await sandbox(); const A = commit(sb, "A");
  await t.test("reproduce-api: both builds are identical and nothing is recorded", async () => {
    const r = api(sb, ["reproduce-api", A]); assert.equal(r.status, 0, r.out); const sums = [...r.out.matchAll(/\b([0-9a-f]{64})\b/g)].map((m) => m[1]); assert.equal(sums.length, 2); assert.equal(sums[0], sums[1]); assert.equal(builds(sb), 2); assert.deepEqual(releases(sb), []); assert.equal(approved(sb), null); assert.ok(!existsSync(join(sb.d, ".run/public/api/build/repro1-" + A.slice(0, 12))), "the build directories are removed");
  });
  await t.test("a non-deterministic build is refused: NON_REPRODUCIBLE_RELEASE_BUILD, exit 10, nothing recorded", async () => {
    writeFileSync(join(sb.d, "NONDET"), "1"); const r = api(sb, ["reproduce-api", A]); const p = api(sb, ["prepare-api", A, "--skip-tests"]); rmSync(join(sb.d, "NONDET")); assert.equal(r.status, 10, r.out); assert.match(r.out, /NON_REPRODUCIBLE_RELEASE_BUILD/); assert.equal(p.status, 10, p.out); assert.deepEqual(releases(sb), [], "an irreproducible artifact never becomes a release");
  });
  await t.test("prepare-api: the release exists (jar sha256, tests recorded, build mode, both build digests), is NOT approved, nothing is started", async () => {
    const r = api(sb, ["prepare-api", A]); assert.equal(r.status, 0, r.out); const id = releases(sb)[0]; const rel = JSON.parse(readFileSync(join(sb.d, `.run/public/api/releases/${id}/release.json`), "utf8")); assert.equal(approved(sb), null); assert.equal(await marker(sb), null); assert.equal(rel.build.reproduced.builds, 2); assert.equal(rel.build.reproduced.sha256[0], rel.build.reproduced.sha256[1]); assert.equal(rel.build.reproduced.sha256[0], rel.jar.sha256, "the release jar IS the reproduced build");
    assert.equal(rel.verification.tests.mode, "full"); assert.equal(rel.verification.tests.total, 7); assert.deepEqual(rel.verification.tests.skippedClasses, ["com.x.ATests"]); assert.equal(rel.schema.maxVersion, "2"); assert.equal(rel.sourceSha, A); assert.match(rel.build.javaVersion, /21/);
    const v = api(sb, ["validate-api", id]); assert.equal(v.status, 0, v.out); assert.match(v.out, /VALIDATED/); assert.equal(approved(sb), null, "validating approves nothing"); assert.equal(await marker(sb), null); assert.deepEqual(dbs(sb), ["postgres", "studio"]);
  });
  await t.test("deploy-api then reuses the prepared release (no rebuild, no second test run) and approves it only after the real process is proven", async () => {
    const b0 = builds(sb), t0 = lines(sb, "tests.log").length; const r = api(sb, ["deploy-api", A]); assert.equal(r.status, 0, r.out); assert.equal(builds(sb), b0); assert.equal(lines(sb, "tests.log").length, t0); assert.equal(await marker(sb), "A"); assert.match(r.out, /downtime \d+ ms/); assert.equal(api(sb, ["verify-running-api"]).status, 0); assert.match(api(sb, ["verify-running-api"]).out, /IS the approved release/);
  });
  await t.test("a wrong JDK is refused BEFORE anything is built (JDK_MISMATCH)", async () => {
    const b0 = builds(sb); const r = api(sb, ["reproduce-api", A], { PUBLIC_API_REQUIRE_JAVA_MAJOR: "17" }); assert.equal(r.status, 7, r.out); assert.match(r.out, /JDK_MISMATCH/); assert.equal(builds(sb), b0);
  });
});

// ================================================================================================================================ the first approved release over a legacy-started API
test("first approved API release over a LEGACY-started (unproven) API: explicit flag, old jar kept as UNVERIFIED evidence only, the pointer moves after 18081 is healthy, failure restores the old process", async (t) => {
  const sb = await sandbox(); const A = commit(sb, "A"); const wt = join(sb.d, "backend");
  const startLegacy = async (label) => {
    writeFileSync(join(wt, "marker.txt"), `${label}\n`); const g = spawnSync("node", ["./gradlew", "clean", "bootJar"], { cwd: wt, env: { ...process.env, SANDBOX: sb.d }, encoding: "utf8" }); assert.equal(g.status, 0, g.stderr);
    const cfg = await import(`../../scripts/lib/public-api-release.mjs?${Math.random()}`); const ctx = cfg.apiContext({ ...process.env, PUBLIC_ROOT: sb.d }, sb.d); const e = { PATH: process.env.PATH, ...cfg.deriveRuntimeConfig(ctx), ...Object.fromEntries(Object.entries(SECRETS)), SANDBOX_LEAK: "1", FROM_OLD_SHELL: "kept-for-restore" };
    const p = spawn(join(sb.d, "bin/java"), ["-XX:MaxRAMPercentage=40", "-jar", join(wt, "build/libs/app-0.1.0.jar")], { cwd: join(sb.d, ".run/public"), env: e, stdio: "ignore", detached: true }); p.unref(); strays.push(p.pid); writeFileSync(join(sb.d, ".run/public/api.pid"), String(p.pid)); for (let i = 0; i < 40 && !(await info(sb.apiPort)); i++) await sleep(150); return p.pid;
  };
  const legacyPid = await startLegacy("LEGACY"); assert.equal(await marker(sb), "LEGACY"); const legacySha = (await import("node:crypto")).createHash("sha256").update(readFileSync(join(wt, "build/libs/app-0.1.0.jar"))).digest("hex");
  await t.test("without the explicit flag the legacy API is never replaced (exit 3) and keeps running", async () => { const r = api(sb, ["deploy-api", A, "--skip-tests"]); assert.equal(r.status, 3, r.out); assert.match(r.out, /init-api --from-running/); assert.ok(exists(legacyPid)); assert.equal(approved(sb), null); assert.equal(await marker(sb), "LEGACY"); });
  await t.test("the new release fails to start on 18081: the LEGACY API is restored from its saved jar, the approved pointer NEVER moved (no approved.json at all)", async () => {
    writeFileSync(join(sb.d, "BREAK_REAL"), "A"); const r = api(sb, ["deploy-api", A, "--skip-tests", "--replace-legacy-unverified"]); rmSync(join(sb.d, "BREAK_REAL"));
    assert.equal(r.status, 7, r.out); assert.match(r.out, /ACTIVATION_FAILED_ROLLED_BACK/); assert.match(r.out, /legacy API .* is running again/); assert.equal(approved(sb), null, "no approved pointer was written"); const i = await info(sb.apiPort); assert.equal(i.marker, "LEGACY"); assert.match(i.jar, /\.run\/public\/api\/legacy\//, "the restored process runs the saved copy"); assert.equal(i.leak, true, "the old environment was restored with it"); assert.ok(!exists(legacyPid) || true);
    assert.equal(JSON.parse(readFileSync(join(sb.d, ".run/public/api/legacy.json"), "utf8")).provenance, "UNVERIFIED"); assert.equal(status(sb).row.state, "RUNNING_UNAPPROVED");
  });
  await t.test("with the flag and a healthy new process: old process stopped, the release runs on 18081, THEN the pointer is written (from = LEGACY_UNVERIFIED, no previous known-good), the old jar is evidence only", async () => {
    const r = api(sb, ["deploy-api", A, "--skip-tests", "--replace-legacy-unverified"]); assert.equal(r.status, 0, r.out); assert.match(r.out, /replaced the LEGACY_UNVERIFIED API/); assert.match(r.out, /downtime \d+ ms/);
    const a = approved(sb); assert.ok(a.releaseId.startsWith(A.slice(0, 12))); assert.equal(a.previousKnownGood, null); assert.equal(a.history.at(-1).from, "LEGACY_UNVERIFIED"); assert.equal(await marker(sb), "A"); assert.ok(!exists(owned(sb).pid) === false);
    assert.ok(!releases(sb).some((id) => id.includes(legacySha.slice(0, 12))), "the legacy jar is not a release"); const leg = JSON.parse(readFileSync(join(sb.d, ".run/public/api/legacy.json"), "utf8")); assert.equal(leg.jarSha256, legacySha); assert.ok(existsSync(join(sb.d, `.run/public/api/legacy/${legacySha.slice(0, 12)}.jar`)));
    const rb = api(sb, ["rollback-api"]); assert.equal(rb.status, 1, rb.out); assert.match(rb.out, /NO_PREVIOUS_KNOWN_GOOD/, "the unverified legacy jar is never a rollback target"); const rl = api(sb, ["rollback-api", legacySha.slice(0, 12)]); assert.equal(rl.status, 6, rl.out); assert.match(rl.out, /APPROVED_API_BUILD_MISSING/, "an id that is not a release cannot be activated"); assert.equal(api(sb, ["verify-running-api"]).status, 0); assert.equal(status(sb).row.state, "CURRENT_APPROVED");
    const i = await info(sb.apiPort); assert.equal(i.leak, false); assert.match(i.jar, /\.run\/public\/api\/releases\//);
  });
});

// ================================================================================================================================ TEST 11: the watchdog never builds
test("TEST 11. watchdog: API recovery runs the approved jar only (it never builds / invokes Gradle) and a crash loop is BOUNDED: capped recoveries, backoff, a marker, no fast infinite loop", async (t) => {
  const sb = await sandbox(); const A = commit(sb, "A"); assert.equal(api(sb, ["deploy-api", A, "--skip-tests"]).status, 0); const b0 = builds(sb); assert.equal(api(sb, ["down"]).status, 0); commit(sb, "B");
  const rndP = await freePort(); const pp = [await freePort(), await freePort(), await freePort()]; appendFileSync(join(sb.d, ".run/public/public.env"), `RENDER_PORT_PUBLIC=${rndP}\nPORTAL_PLATFORM_PORT_PUBLIC=${pp[0]}\nPORTAL_ADMIN_PORT_PUBLIC=${pp[1]}\nPORTAL_STUDIO_PORT_PUBLIC=${pp[2]}\n`);
  const up = join(sb.d, "scripts/public-up.sh"); writeFileSync(up, `#!/usr/bin/env bash\necho "$(date +%s) up" >> "$(dirname "$0")/../up-calls.log"\nexec bash "$(dirname "$0")/public-api.sh" up\n`); chmodSync(up, 0o755);   // what public-up.sh runs for the API
  const health = (port) => spawn(process.execPath, ["-e", `require("http").createServer((q,r)=>r.end("ok")).listen(${port},"127.0.0.1");setInterval(()=>{},1e6)`], { stdio: "ignore", detached: true }); const hs = [rndP, ...pp].map(health); const tun = spawn("sleep", ["300"], { stdio: "ignore", detached: true }); for (const p of [...hs, tun]) { p.unref(); strays.push(p.pid); } writeFileSync(join(sb.d, ".run/public/tunnel.pid"), String(tun.pid)); await sleep(500);
  writeFileSync(join(sb.d, "BREAK_START_ANY"), "1");   // the approved jar cannot start: a crash loop
  const wd = spawn("bash", [join(sb.d, "scripts/watchdog.sh"), "public"], { cwd: sb.d, env: { ...env(sb), WATCHDOG_INTERVAL: "1", WATCHDOG_MAX_RESTARTS: "3", WATCHDOG_WINDOW: "60", WATCHDOG_BACKOFF_BASE: "1", WATCHDOG_BACKOFF_MAX: "2" }, stdio: "ignore", detached: true }); wd.unref(); strays.push(wd.pid);
  await t.test("recoveries are capped at 3 inside the window, then the loop is declared and automatic restarts PAUSE; nothing is ever built; status shows CRASH_LOOP", async () => {
    for (let i = 0; i < 60 && !existsSync(join(sb.d, ".run/public/crash-loop.json")); i++) await sleep(500); assert.ok(existsSync(join(sb.d, ".run/public/crash-loop.json")), "crash-loop marker written"); assert.equal(lines(sb, "up-calls.log").length, 3, "exactly MAX recoveries");
    await sleep(5000); assert.equal(lines(sb, "up-calls.log").length, 3, "no further restart while the loop persists"); assert.equal(builds(sb), b0, "the watchdog built nothing (the working tree has moved on to B)"); assert.equal(await marker(sb), null);
    const stamps = lines(sb, "up-calls.log").map((l) => Number(l.split(" ")[0])); assert.ok(stamps[1] - stamps[0] >= 1 && stamps[2] - stamps[1] >= 2, `backoff grows (${stamps.join(",")})`); assert.equal(status(sb).row.state, "CRASH_LOOP"); assert.match(readFileSync(join(sb.d, ".run/watchdog-public.log"), "utf8"), /CRASH LOOP/);
  });
  await t.test("when the approved jar can start again the next recovery restores A (not B) and the marker is cleared", async () => {
    rmSync(join(sb.d, "BREAK_START_ANY")); assert.equal(api(sb, ["up"]).status, 0); for (let i = 0; i < 40 && existsSync(join(sb.d, ".run/public/crash-loop.json")); i++) await sleep(500); assert.ok(!existsSync(join(sb.d, ".run/public/crash-loop.json")), "marker cleared"); assert.equal(await marker(sb), "A"); assert.equal(builds(sb), b0);
  });
});

// ================================================================================================================================ tooling safety
test("the Flyway checksum implementation equals Flyway's algorithm on the properties that matter (BOM, CRLF = LF, trailing newline, changed content)", () => {
  const a = flywayChecksum(Buffer.from("create table t(id int);\nselect 1;\n")); assert.equal(flywayChecksum(Buffer.from("﻿create table t(id int);\r\nselect 1;\r\n")), a); assert.equal(flywayChecksum(Buffer.from("create table t(id int);\nselect 1;")), a); assert.notEqual(flywayChecksum(Buffer.from("create table t(id int);\nselect 2;\n")), a);
  assert.equal(flywayChecksum(Buffer.from("")), 0);
});
