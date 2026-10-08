// @class: unit
// Explicit public API release pinning (C0, D-C0-50):  API RECOVERY != API DEPLOYMENT.
//
// The public API (port 18081) runs an immutable, approved BACKEND ARTIFACT, never "a jar rebuilt from the working tree":
//   release   = <run>/api/releases/<sha12>-<cfg8>/   app.jar (the Spring Boot jar) + release.json (immutable record, NO secrets): source SHA, backend tree SHA, jar sha256 + entry digest,
//               build fingerprint (what the build read), config fingerprint (NON-SECRET runtime configuration), the Flyway migrations (version + checksum) the jar carries, how it was verified.
//   approved  = <run>/api/approved.json -> { releaseId, releaseJsonSha256, previousKnownGood, history }  atomic rename; the ONLY authority for what runs. HEAD is not.
// up / restart / watchdog recovery start the approved jar (java -jar <release>/app.jar): no Gradle, no compile, no source checkout. No approved release / missing / corrupt jar => fail closed.
// `deploy-api <sha>` is the only way to change it: snapshot of that SHA -> build the jar -> backend tests -> candidate started on a TEMPORARY port against a SCRATCH database (schema-only copy of the
// real one: no production data) -> readiness (db, redis, rabbit, minio) -> schema verdict against the REAL database (read-only) -> pointer moves -> stop-then-start on the real port (NOT zero-downtime)
// -> readiness. Secrets are never recorded: they are read from public.env at start and handed to the child only.
//
// Database policy (Flyway community: forward migrations only, NO down migrations; a manual, human-run undo exists only for V28 / V29 under docs/parallel/c0/undo):
//   schemaVerdict(applied migrations of the REAL database, migrations of the artifact)
//     COMPATIBLE                    every applied migration is carried by the artifact with the SAME checksum (pending ones are applied by the artifact at start: a one-way step)
//     ROLLBACK_SCHEMA_INCOMPATIBLE  the database is AHEAD of the artifact (it has migrations the artifact does not know) and they were not declared expand-only (`deploy-api --expand-only`)
//     FLYWAY_CHECKSUM_MISMATCH      an applied migration differs from the artifact's file     SCHEMA_DIRTY   a failed migration is recorded
// Flyway itself would only WARN about an older jar on a newer schema (ignore-migration-patterns default *:future), so this check is ours and it fails closed.
import { spawn, spawnSync } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { context, ReleaseError, writeJsonAtomic, readJson, withLock, resolveSource, httpGet, freePort, sameStart } from "./public-release.mjs";
import { startOwned, stopOwned, identify, readMeta, writeMeta, listenerPids, commandOf, cwdOf, startTimeOf, exists } from "./owned-process.mjs";

export { ReleaseError, readJson, writeJsonAtomic };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sha = (b) => createHash("sha256").update(b).digest("hex");
const git = (root, args, o = {}) => spawnSync("git", ["-C", root, ...args], { encoding: "utf8", maxBuffer: 256 * 1024 * 1024, ...o });
const words = (s) => String(s).split(/\s+/).filter(Boolean);

// ------------------------------------------------------------------------------------------------------------------------------------------------ secrets vs configuration
/** keys whose VALUES never enter a record, a log or a status: they are read from public.env at start time and passed to the child only */
const SECRET = /SECRET|PASSWORD|PASSWD|TOKEN|API_KEY|MASTER_KEY|_KEY$|SALT|CREDENTIAL|PRIVATE|INVITE_CODE|^(DATABASE|RABBITMQ|REDIS|MINIO_ROOT)_USER$/i;
export const isSecretKey = (k) => SECRET.test(k);
export function readEnvFile(file) { const out = {}; if (!existsSync(file)) return out; for (const l of readFileSync(file, "utf8").split("\n")) { const m = /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/.exec(l.trim()); if (m) out[m[1]] = m[2].replace(/^["']|["']$/g, ""); } return out; }

export function apiContext(env = process.env, scriptsRoot = null) {
  const c = context(env, scriptsRoot); const api = join(c.run, "api"); const pub = readEnvFile(join(c.run, "public.env"));
  const javaHome = env.PUBLIC_API_JAVA_HOME ?? (existsSync("/opt/homebrew/opt/openjdk@21") ? "/opt/homebrew/opt/openjdk@21" : env.JAVA_HOME ?? "");
  const container = env.PUBLIC_API_PG_CONTAINER ?? "hblpub-postgres-1"; const dbUser = pub.DATABASE_USER ?? "studio";
  return { ...c, api, releasesDir: join(api, "releases"), approvedFile: join(api, "approved.json"), lockFile: join(api, "release.lock"), incidentFile: join(api, "incident.json"), stateFile: join(c.run, "api.owned.json"), buildDir: join(api, "build"),
    port: c.apiPort, javaHome, java: env.PUBLIC_API_JAVA ?? (javaHome ? join(javaHome, "bin", "java") : "java"), dbName: env.PUBLIC_API_DB_NAME ?? "studio", rehearse: env.PUBLIC_API_REHEARSE ?? "schema",
    dbCmd: words(env.PUBLIC_API_DB_CMD ?? `docker exec -i ${container} psql -U ${dbUser} -v ON_ERROR_STOP=1`), dumpCmd: words(env.PUBLIC_API_DUMP_CMD ?? `docker exec ${container} pg_dump -U ${dbUser}`),
    buildCmd: words(env.PUBLIC_API_BUILD_CMD ?? "./gradlew bootJar -x test --console=plain -q"), testCmd: words(env.PUBLIC_API_TEST_CMD ?? "./gradlew test --console=plain -q"),
    apiStartTimeoutMs: Number(env.PUBLIC_API_START_TIMEOUT_MS ?? 120000), jvmArgs: words(env.PUBLIC_API_JVM_ARGS ?? "-XX:MaxRAMPercentage=40") };
}

/** the NON-SECRET runtime configuration of the public API (what scripts/public-up.sh used to hand to java), derived from public.env; secrets are NOT part of it */
export function deriveRuntimeConfig(c) {
  const pub = readEnvFile(join(c.run, "public.env")); const P = (k, d) => (pub[k] !== undefined && pub[k] !== "" ? pub[k] : d);
  const host = P("PUBLIC_HOST", "studio.toolsmcp.uk"), sites = P("SITES_HOST", "sites.toolsmcp.uk"), files = P("PUBLIC_FILES_HOST", "studio-files.toolsmcp.uk"); const origin = `https://${host}`; const v1 = P("V1_FEATURES", "true");
  const portals = P("PUBLIC_PORTALS", "true") === "true"; const wp = portals ? `https://${P("PUBLIC_PLATFORM_HOST", "platform.toolsmcp.uk")}` : "", wa = portals ? `https://${P("PUBLIC_ADMIN_HOST", "admin.toolsmcp.uk")}` : "", ws = portals ? origin : "";
  const explicit = { SPRING_PROFILES_ACTIVE: "prod", APP_PROFILE: "prod", SERVER_ADDRESS: "127.0.0.1", SERVER_PORT: String(c.port), DATABASE_URL: `jdbc:postgresql://127.0.0.1:${P("PG_PORT", "25432")}/${c.dbName}`,
    REDIS_HOST: "127.0.0.1", REDIS_PORT: P("REDIS_PORT_PUBLIC", "26379"), RABBITMQ_HOST: "127.0.0.1", RABBITMQ_PORT: P("RABBITMQ_PORT_PUBLIC", "25674"), RABBITMQ_USER: "studio",
    MINIO_ENDPOINT: `http://127.0.0.1:${P("MINIO_PORT_PUBLIC", "29000")}`, MINIO_PUBLIC_ENDPOINT: `https://${files}`, DEPLOY_PROVIDER: "static", SITES_ORIGIN: `https://${sites}`, STUDIO_ORIGIN: origin, SITES_COOKIE_SECURE: "true",
    RENDER_URL: `http://127.0.0.1:${P("RENDER_PORT_PUBLIC", "28095")}`, CORS_ALLOWED_ORIGINS: portals ? [wp, wa, ws].join(",") : origin, WEB_ORIGIN_PLATFORM: wp, WEB_ORIGIN_ADMIN: wa, WEB_ORIGIN_STUDIO: ws, TRUST_PROXY: "true",
    TRUSTED_PROXY_CIDRS: P("PUBLIC_TRUSTED_PROXY_CIDRS", "127.0.0.1/32,::1/128"), DATA_PLATFORM_ENABLED: v1, WORKFLOW_ENABLED: v1, PUBLISH_CONFIGS_ENABLED: v1, SITES_PUBLIC_DATA_ENABLED: v1,
    SITES_DATA_API_BASE: `https://${sites}/{slug}/_data`, BACKUP_STATUS_DIRS: `public:${c.root}/backups/public`, APP_DEPLOY_MOCK_BASE_URL: `${origin}/mock-deployments`, OPENROUTER_REFERER: origin };
  const pass = Object.fromEntries(Object.entries(pub).filter(([k]) => !isSecretKey(k)));
  return { ...pass, ...explicit };
}
export const configList = (cfg) => Object.entries(cfg).map(([k, v]) => `${k}=${v}`).sort();
const listToObj = (l) => Object.fromEntries(l.map((kv) => [kv.slice(0, kv.indexOf("=")), kv.slice(kv.indexOf("=") + 1)]));
export const configFingerprintOf = (list, javaMajor) => sha(JSON.stringify([[...list].sort(), javaMajor])).slice(0, 16);
export const secretKeyNames = (c) => Object.keys(readEnvFile(join(c.run, "public.env"))).filter(isSecretKey).sort();
/** the environment of the API process: a small OS base + the pinned non-secret configuration + the secrets of public.env. Nothing else of the caller's environment is inherited. */
function apiEnv(c, rel, overrides = {}) {
  const base = {}; for (const k of ["PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "USER", "LOGNAME"]) if (process.env[k]) base[k] = process.env[k]; if (c.javaHome) base.JAVA_HOME = c.javaHome;
  const secrets = Object.fromEntries(Object.entries(readEnvFile(join(c.run, "public.env"))).filter(([k]) => isSecretKey(k)));
  return { ...base, ...listToObj(rel.runtimeConfig), ...secrets, ...overrides };
}

// ------------------------------------------------------------------------------------------------------------------------------------------------ jars, Flyway
const CRC = new Int32Array(256).map((_, n) => { let x = n; for (let k = 0; k < 8; k++) x = x & 1 ? 0xEDB88320 ^ (x >>> 1) : x >>> 1; return x; });
const crc32 = (buf, crc = 0) => { let x = ~crc; for (const b of buf) x = CRC[(x ^ b) & 0xff] ^ (x >>> 8); return ~x; };
/** Flyway's checksum of a SQL migration (CRC32 over the lines, BOM stripped, line terminators ignored) - verified equal to flyway_schema_history on the real database */
export function flywayChecksum(buf) { let s = Buffer.from(buf).toString("utf8"); if (s.charCodeAt(0) === 0xFEFF) s = s.slice(1); const lines = s.split(/\r\n|\n|\r/); if (lines.length && lines[lines.length - 1] === "") lines.pop(); let x = 0; for (const l of lines) x = crc32(Buffer.from(l, "utf8"), x); return x | 0; }
const unzip = (args) => spawnSync("unzip", args, { maxBuffer: 1024 * 1024 * 1024 });
/** [{name, length, crc}] of every file entry of a jar (central directory: no extraction) */
export function jarEntries(jar) {
  const r = unzip(["-v", jar]); if (r.status !== 0) throw new ReleaseError("JAR_UNREADABLE", `${jar} is not a readable jar`);
  const out = []; for (const l of r.stdout.toString("utf8").split("\n")) { const m = /^\s*(\d+)\s+\S+\s+\d+\s+\S+\s+\S+\s+\S+\s+([0-9a-f]{8})\s+(.+?)\s*$/.exec(l); if (m && !m[3].endsWith("/")) out.push({ name: m[3], length: Number(m[1]), crc: m[2] }); }
  return out;
}
export const entryDigest = (entries) => sha(entries.map((e) => `${e.name}\t${e.length}\t${e.crc}`).sort().join("\n")).slice(0, 32);
const verKey = (v) => v.split(".").map(Number);
const verCmp = (a, b) => { const x = verKey(a), y = verKey(b); for (let i = 0; i < Math.max(x.length, y.length); i++) { const d = (x[i] ?? 0) - (y[i] ?? 0); if (d) return d; } return 0; };
export function jarMigrations(jar, entries = jarEntries(jar)) {
  const out = []; for (const e of entries) { const m = /^BOOT-INF\/classes\/db\/migration\/V(\d+(?:_\d+)*)__.+\.sql$/.exec(e.name); if (!m) continue; const r = unzip(["-p", jar, e.name]); out.push({ version: m[1].replace(/_/g, "."), checksum: flywayChecksum(r.stdout) }); }
  return out.sort((a, b) => verCmp(a.version, b.version));
}
export function javaMajorOf(javaBin) { const r = spawnSync(javaBin, ["-version"], { encoding: "utf8" }); const m = /version "(\d+)/.exec((r.stderr || "") + (r.stdout || "")); return m ? Number(m[1]) : 0; }

/** the verdict that decides whether an artifact may run on THE database as it is now (see the header) */
export function schemaVerdict(applied, migrations, { allowAhead = [] } = {}) {
  const byV = new Map(migrations.map((m) => [m.version, m])); const ahead = [], mismatched = [], failed = [];
  for (const a of applied) { if (!a.success) failed.push(a.version); const m = byV.get(a.version); if (!m) ahead.push(a.version); else if (m.checksum !== a.checksum) mismatched.push(a.version); }
  const have = new Set(applied.map((a) => a.version)); const pending = migrations.filter((m) => !have.has(m.version)).map((m) => m.version); const aheadBlocked = ahead.filter((v) => !allowAhead.includes(v));
  const verdict = failed.length ? "SCHEMA_DIRTY" : mismatched.length ? "FLYWAY_CHECKSUM_MISMATCH" : aheadBlocked.length ? "ROLLBACK_SCHEMA_INCOMPATIBLE" : "COMPATIBLE";
  return { verdict, ok: verdict === "COMPATIBLE", ahead: ahead.sort(verCmp), aheadBlocked: aheadBlocked.sort(verCmp), mismatched, failed, pending: pending.sort(verCmp) };
}
const verdictText = (v) => `${v.verdict}${v.aheadBlocked.length ? ` (the database has migrations the artifact does not carry: ${v.aheadBlocked.join(", ")}; none of them was declared expand-only; Flyway has no down migrations)` : ""}${v.mismatched.length ? ` (checksum differs: ${v.mismatched.join(", ")})` : ""}${v.failed.length ? ` (failed migration: ${v.failed.join(", ")})` : ""}`;

// ------------------------------------------------------------------------------------------------------------------------------------------------ database (read-only except the scratch database)
function psql(c, sql, db) {
  const r = spawnSync(c.dbCmd[0], [...c.dbCmd.slice(1), "-d", db, "-tA", "-F", "|", "-c", sql], { encoding: "utf8", timeout: 120000 });
  if (r.status !== 0) throw new ReleaseError("DB_UNAVAILABLE", `database query failed on ${db}: ${(r.stderr || r.stdout || String(r.error ?? "")).trim().slice(0, 200)}`);
  return r.stdout.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => l.split("|"));
}
export const appliedMigrations = (c, db = c.dbName) => psql(c, "select version, checksum, success from flyway_schema_history where version is not null order by installed_rank", db).map(([version, checksum, success]) => ({ version, checksum: Number(checksum), success: success === "t" }));
async function withScratchDb(c, id, mode, fn) {
  const name = `cand_${id.replace(/[^a-z0-9]/gi, "").slice(0, 20).toLowerCase()}_${randomBytes(3).toString("hex")}`;
  psql(c, `create database ${name}`, "postgres");
  try {
    if (mode === "schema") for (const a of [["--schema-only", "--no-owner", "--no-privileges"], ["--data-only", "--no-owner", "-t", "flyway_schema_history"]]) {   // structure + the migration history only: NO production data
      const d = spawnSync(c.dumpCmd[0], [...c.dumpCmd.slice(1), ...a, c.dbName], { maxBuffer: 512 * 1024 * 1024, timeout: 300000 }); if (d.status !== 0) throw new ReleaseError("REHEARSAL_FAILED", `schema dump failed: ${String(d.stderr).slice(0, 200)}`);
      const l = spawnSync(c.dbCmd[0], [...c.dbCmd.slice(1), "-d", name, "-q", "-f", "-"], { input: d.stdout, maxBuffer: 64 * 1024 * 1024, timeout: 300000 }); if (l.status !== 0) throw new ReleaseError("REHEARSAL_FAILED", `schema restore into ${name} failed: ${String(l.stderr).slice(0, 200)}`);
    }
    return await fn(name);
  } finally { try { psql(c, `drop database if exists ${name} with (force)`, "postgres"); } catch { /* reported by the next run's leftovers listing */ } }
}

// ------------------------------------------------------------------------------------------------------------------------------------------------ release records
const relDir = (c, id) => join(c.releasesDir, id); const jarOf = (c, id) => join(relDir(c, id), "app.jar");
export const readRelease = (c, id) => readJson(join(relDir(c, id), "release.json"));
export const readApproved = (c) => readJson(c.approvedFile);
export function listReleases(c) { try { return readdirSync(c.releasesDir).filter((n) => existsSync(join(c.releasesDir, n, "release.json"))); } catch { return []; } }
const releaseSha = (c, id) => { try { return sha(readFileSync(join(relDir(c, id), "release.json"))); } catch { return null; } };
const allowAheadOf = (c) => [...new Set(listReleases(c).flatMap((id) => readRelease(c, id)?.schema?.expandOnly ?? []))];
/** problems start with APPROVED_API_BUILD_MISSING / API_RELEASE_TAMPERED. quick = record + jar present + size; deep = also the sha256 of the jar */
export function verifyRelease(c, id, { deep = false, approved = readApproved(c) } = {}) {
  const rel = readRelease(c, id); if (!rel) return { ok: false, problems: [`APPROVED_API_BUILD_MISSING: release ${id} has no release.json`] }; const problems = [];
  if (approved?.releaseId === id && approved.releaseJsonSha256 && approved.releaseJsonSha256 !== releaseSha(c, id)) problems.push(`API_RELEASE_TAMPERED: release.json of ${id} differs from the approved digest`);
  const jar = jarOf(c, id); if (!existsSync(jar)) problems.push(`APPROVED_API_BUILD_MISSING: ${jar} does not exist`);
  else { if (statSync(jar).size !== rel.jar?.size) problems.push(`APPROVED_API_BUILD_MISSING: ${jar} has another size than the record (truncated or replaced)`); else if (deep && sha(readFileSync(jar)) !== rel.jar.sha256) problems.push(`APPROVED_API_BUILD_MISSING: sha256 of ${jar} differs from the record (corrupt or modified artifact)`); }
  return { ok: problems.length === 0, problems };
}
function approvedRecord(c, id, { action, from = null, previousKnownGood = undefined }) {
  const prev = readApproved(c); const h = [...(prev?.history ?? []), { at: new Date().toISOString(), action, releaseId: id, from }].slice(-20);
  return { schema: 1, releaseId: id, releaseJsonSha256: releaseSha(c, id), approvedAt: new Date().toISOString(), approvedBy: process.env.USER ?? "unknown", previousKnownGood: previousKnownGood === undefined ? (prev?.releaseId && prev.releaseId !== id ? prev.releaseId : prev?.previousKnownGood ?? null) : previousKnownGood, history: h };
}
const withApiLock = (c, fn) => { mkdirSync(c.api, { recursive: true }); return withLock(c, fn); };

// ------------------------------------------------------------------------------------------------------------------------------------------------ the process
/** what listens on the API port: ownership by recorded identity ONLY (pid + start time + command + cwd) */
export function runningOf(c) {
  const pids = listenerPids(c.port); const meta = readMeta(c.stateFile); const id = meta ? identify(meta) : { state: "UNKNOWN" };
  const ownedPids = new Set(id.state === "OWNED" ? [meta.pid, ...(meta.members ?? []).map((m) => m.pid), ...(meta.listenerPid ? [meta.listenerPid] : [])] : []);
  if (!pids.length) return { port: c.port, listener: null, owned: false, foreign: false, meta, identity: id.state };
  const foreignPid = pids.find((p) => !ownedPids.has(p));
  return { port: c.port, listener: pids[0], owned: foreignPid == null, foreign: foreignPid != null, foreignPid, releaseId: meta?.extra?.releaseId ?? null, meta, identity: id.state };
}
/** a process the OLD lifecycle (public-up.sh: api.pid + cwd .run/public + java -jar) started and that is not pinned yet: ours, not foreign - `init-api --from-running` adopts it */
export function legacyApi(c) {
  const pid = listenerPids(c.port)[0]; if (!pid) return false; let recorded = ""; try { recorded = readFileSync(join(c.run, "api.pid"), "utf8").trim(); } catch { /* none */ }
  return recorded === String(pid) && cwdOf(pid) === c.run && /\s-jar\s+\S+\.jar/.test(commandOf(pid));
}
export async function waitReady(port, ms) { const end = Date.now() + ms; while (Date.now() < end) { const r = await httpGet(`http://127.0.0.1:${port}/actuator/health/readiness`, 3000); if (r.status === 200) return true; await sleep(400); } return false; }
export async function startApi(c, id, { port = c.port, overrides = {}, stateFile = c.stateFile, tag = "public" } = {}) {
  const rel = readRelease(c, id);
  return startOwned({ owner: tag, name: "api", stateFile, logFile: tag === "public" ? join(c.run, "api.log") : undefined, cwd: c.run, port, mode: tag === "public" ? "public" : "validate", group: true, inheritEnv: false,
    cmd: [c.java, ...c.jvmArgs, "-jar", jarOf(c, id)], env: apiEnv(c, rel, { SERVER_PORT: String(port), ...overrides }), ready: { port, timeoutMs: c.apiStartTimeoutMs },
    extra: { releaseId: id, jarSha256: rel.jar.sha256, configFingerprint: rel.configFingerprint } });
}
export async function stopApi(c) { const meta = readMeta(c.stateFile); if (!meta) return { state: "NO_RECORD" }; return stopOwned(meta, { graceMs: 15000, removeState: true }); }
/** the old pid file is kept in step with the helper metadata: public-down.sh and humans still read it */
const syncPidFile = (c, pid) => { try { if (pid) writeFileSync(join(c.run, "api.pid"), `${pid}\n`); else rmSync(join(c.run, "api.pid"), { force: true }); } catch { /* informational */ } };

// ------------------------------------------------------------------------------------------------------------------------------------------------ build a candidate
function extractBackend(root, sha40, dir) {
  return new Promise((res, rej) => {
    mkdirSync(dir, { recursive: true }); const a = spawn("git", ["-C", root, "archive", "--format=tar", sha40, "backend"], { stdio: ["ignore", "pipe", "pipe"] }); const t = spawn("tar", ["-x", "-C", dir], { stdio: ["pipe", "ignore", "pipe"] });
    let err = ""; a.stderr.on("data", (d) => (err += d)); t.stderr.on("data", (d) => (err += d)); a.stdout.pipe(t.stdin); let ca = null, ct = null;
    const fin = () => { if (ca != null && ct != null) (ca === 0 && ct === 0 ? res() : rej(new ReleaseError("SNAPSHOT_FAILED", `git archive ${sha40.slice(0, 12)} backend: ${err.trim().slice(0, 200)}`))); }; a.on("close", (x) => { ca = x; fin(); }); t.on("close", (x) => { ct = x; fin(); });
  });
}
function gradle(c, dir, cmd, label) {
  const log = join(dir, `${label}.log`); const r = spawnSync(cmd[0], cmd.slice(1), { cwd: join(dir, "backend"), env: { ...process.env, ...(c.javaHome ? { JAVA_HOME: c.javaHome } : {}) }, encoding: "utf8", timeout: 3600000, maxBuffer: 256 * 1024 * 1024 });
  writeFileSync(log, `${r.stdout ?? ""}\n${r.stderr ?? ""}`); return { ok: r.status === 0, tail: `${r.stderr || r.stdout || ""}`.trim().split("\n").slice(-6).join(" | ").slice(0, 400), log };
}
function builtJar(dir) { const d = join(dir, "backend", "build", "libs"); const jars = existsSync(d) ? readdirSync(d).filter((f) => f.endsWith(".jar") && !f.endsWith("-plain.jar")) : []; if (jars.length !== 1) throw new ReleaseError("CANDIDATE_BUILD_FAILED", `the build produced ${jars.length} runnable jars in ${d} (expected exactly one)`); return join(d, jars[0]); }
const buildFingerprintOf = (treeSha, javaMajor) => sha(JSON.stringify([treeSha, javaMajor])).slice(0, 16);

export async function buildCandidate(c, sha40, { skipTests = false, expandOnly = false, log = () => {} } = {}) {
  const javaMajor = javaMajorOf(c.java); const cfg = deriveRuntimeConfig(c); const list = configList(cfg); const cfgFp = configFingerprintOf(list, javaMajor); const id = `${sha40.slice(0, 12)}-${cfgFp.slice(0, 8)}`;
  const existing = readRelease(c, id); if (existing) { const v = verifyRelease(c, id, { deep: true, approved: null }); if (v.ok) { log(`release ${id} already built: reused (no rebuild)`); return { id, reused: true }; } log(`release ${id} exists but is incomplete / corrupt (${v.problems[0]}): rebuilt`); }
  const dir = join(c.buildDir, id); rmSync(dir, { recursive: true, force: true }); const stage = `${relDir(c, id)}.tmp.${process.pid}`; rmSync(stage, { recursive: true, force: true });
  try {
    const treeSha = git(c.root, ["rev-parse", `${sha40}:backend`]).stdout.trim(); log(`snapshot of backend @ ${sha40.slice(0, 12)} (tree ${treeSha.slice(0, 12)})`); await extractBackend(c.root, sha40, dir);
    let tests = "skipped"; if (!skipTests) { log("backend tests (full)"); const t = gradle(c, dir, c.testCmd, "tests"); if (!t.ok) throw new ReleaseError("CANDIDATE_TESTS_FAILED", `backend tests failed (log ${t.log}): ${t.tail}`); tests = "full"; }
    log("building the jar"); const b = gradle(c, dir, c.buildCmd, "build"); if (!b.ok) throw new ReleaseError("CANDIDATE_BUILD_FAILED", `jar build failed (log ${b.log}): ${b.tail}`);
    const jar = builtJar(dir); mkdirSync(stage, { recursive: true }); copyFileSync(jar, join(stage, "app.jar")); const entries = jarEntries(join(stage, "app.jar")); const migrations = jarMigrations(join(stage, "app.jar"), entries);
    const cur = readApproved(c); const curVers = new Set((cur ? readRelease(c, cur.releaseId)?.schema?.versions ?? [] : []).map((m) => m.version)); const added = migrations.map((m) => m.version).filter((v) => !curVers.has(v));
    const record = { schema: 1, id, kind: "built", sourceSha: sha40, sourceShort: sha40.slice(0, 12), backendTreeSha: treeSha, createdAt: new Date().toISOString(), javaMajor, buildFingerprint: buildFingerprintOf(treeSha, javaMajor), configFingerprint: cfgFp,
      runtimeConfig: list, secretKeyNames: secretKeyNames(c), jar: { file: "app.jar", sha256: sha(readFileSync(join(stage, "app.jar"))), size: statSync(join(stage, "app.jar")).size, entryDigest: entryDigest(entries) },
      schema: { versions: migrations, maxVersion: migrations.at(-1)?.version ?? null, expandOnly: expandOnly ? added : [] }, verification: { tests, build: c.buildCmd.join(" "), rehearsal: c.rehearse } };
    writeJsonAtomic(join(stage, "release.json"), record); rmSync(relDir(c, id), { recursive: true, force: true }); renameSync(stage, relDir(c, id)); return { id, reused: false };
  } catch (e) { rmSync(stage, { recursive: true, force: true }); throw e; } finally { rmSync(dir, { recursive: true, force: true }); }
}

/** prove the candidate on a TEMPORARY port against a SCRATCH database: it starts, Flyway applies, readiness (db, redis, rabbit, minio) is UP. The real API and the real database are not touched. */
export async function validateCandidate(c, id, log = () => {}) {
  const rel = readRelease(c, id); const problems = []; let started = null;
  try {
    await withScratchDb(c, id, c.rehearse === "fresh" ? "fresh" : "schema", async (scratch) => {
      const port = await freePort(); const url = (rel.runtimeConfig.find((x) => x.startsWith("DATABASE_URL=")) ?? "DATABASE_URL=jdbc:postgresql://127.0.0.1:25432/x").slice("DATABASE_URL=".length).replace(/\/[^/]+$/, `/${scratch}`);
      // WORKFLOW_ENABLED=false: the candidate must not consume messages of the REAL RabbitMQ queues
      try { started = await startApi(c, id, { port, tag: "public-validate", stateFile: join(c.run, "validate-api.owned.json"), overrides: { DATABASE_URL: url, WORKFLOW_ENABLED: "false" } }); } catch (e) { problems.push(`did not start on a temporary port (${e.code ?? "ERROR"}: ${String(e.message).slice(0, 160)})`); return; }
      if (!(await waitReady(port, c.apiStartTimeoutMs))) { problems.push("readiness (database, redis, rabbit, minio) did not become UP"); return; }
      const live = await httpGet(`http://127.0.0.1:${port}/actuator/health/liveness`); if (live.status !== 200) problems.push(`liveness answered ${live.status || live.error}`);
      const applied = appliedMigrations(c, scratch); const miss = rel.schema.versions.filter((m) => !applied.some((a) => a.version === m.version && a.success)).map((m) => m.version); if (miss.length) problems.push(`Flyway did not apply ${miss.join(", ")} on the scratch database`);
      log(`validate :${port} scratch=${scratch} (${c.rehearse}) -> ${problems.length ? problems.join("; ") : "ready, Flyway applied " + applied.length + " migrations"}`);
    });
  } catch (e) { problems.push(`${e.code ?? "ERROR"}: ${String(e.message).slice(0, 200)}`); } finally { if (started) await stopOwned(started, { graceMs: 5000, removeState: true }); rmSync(join(c.run, "validate-api.owned.json"), { force: true }); }
  return { ok: problems.length === 0, problems };
}

// ------------------------------------------------------------------------------------------------------------------------------------------------ activate / deploy / rollback / up
function checkSchema(c, id) {
  const rel = readRelease(c, id); let applied; try { applied = appliedMigrations(c); } catch (e) { throw new ReleaseError("SCHEMA_CHECK_UNAVAILABLE", `${e.message} - the artifact cannot be proven compatible with the database: failing closed`); }
  const v = schemaVerdict(applied, rel.schema.versions, { allowAhead: allowAheadOf(c) }); if (!v.ok) throw new ReleaseError(v.verdict, `release ${id}: ${verdictText(v)}`, { verdict: v }); return v;
}
async function replaceRunning(c, id, log) {
  const r = runningOf(c); if (r.foreign) throw new ReleaseError("FOREIGN_PROCESS", `port ${c.port} is used by pid ${r.foreignPid}, which is not the recorded public API: left alone`);
  if (r.owned) await stopApi(c);
  try { const m = await startApi(c, id); syncPidFile(c, m.pid); } catch (e) { return { ok: false, error: `${e.code ?? "ERROR"}: ${String(e.message).slice(0, 200)}` }; }
  if (!(await waitReady(c.port, c.apiStartTimeoutMs))) return { ok: false, error: "readiness did not become UP after the start" };
  log(`API :${c.port} runs ${id}`); return { ok: true };
}
/** verify -> schema verdict (REAL database, read-only) -> prove on a temporary port + scratch database -> move the pointer (atomic) -> replace the running API -> on failure return to the previous release when the schema still allows it */
export async function activate(c, id, { action, log = () => {} }) {
  const before = readApproved(c); foreignGuard(c);   // a foreign listener refuses BEFORE anything moves
  const v = verifyRelease(c, id, { deep: true, approved: null }); if (!v.ok) throw new ReleaseError("APPROVED_API_BUILD_MISSING", v.problems.join("; "), { problems: v.problems });
  const sv = checkSchema(c, id); log(`schema: ${sv.verdict}${sv.pending.length ? ` (the artifact will apply ${sv.pending.join(", ")} at start: a one-way step)` : ""}`);
  const val = await validateCandidate(c, id, log); if (!val.ok) throw new ReleaseError("CANDIDATE_VALIDATION_FAILED", `candidate ${id} is not healthy: ${val.problems.join("; ")}`, { problems: val.problems });
  writeJsonAtomic(c.approvedFile, approvedRecord(c, id, { action, from: before?.releaseId ?? null, previousKnownGood: action === "rollback" ? null : undefined }));
  const r = await replaceRunning(c, id, log);
  if (r.ok) { rmSync(c.crashFile, { force: true }); rmSync(c.incidentFile, { force: true }); return { ok: true, releaseId: id, previous: before?.releaseId ?? null, schema: sv }; }
  await stopApi(c);
  if (!before) { rmSync(c.approvedFile, { force: true }); throw new ReleaseError("ACTIVATION_FAILED", `${r.error}; no previous release to return to`); }
  writeJsonAtomic(c.approvedFile, before);
  let back; try { checkSchema(c, before.releaseId); back = await replaceRunning(c, before.releaseId, log); }
  catch (e) { writeJsonAtomic(c.incidentFile, { state: e.code ?? "ROLLBACK_BLOCKED", since: new Date().toISOString(), failed: id, previous: before.releaseId, reason: String(e.message).slice(0, 300) }); throw new ReleaseError("ACTIVATION_FAILED_ROLLBACK_BLOCKED", `${r.error}; the previous release ${before.releaseId} is approved again but was NOT restarted: ${e.message}`); }
  throw new ReleaseError("ACTIVATION_FAILED_ROLLED_BACK", `${r.error}; the previous release ${before.releaseId} is approved and ${back.ok ? "running again" : "COULD NOT be restarted: " + back.error}`, { rolledBack: back.ok });
}
export function prune(c, log = () => {}) {
  const a = readApproved(c); const keep = new Set([a?.releaseId, a?.previousKnownGood].filter(Boolean)); const removed = []; if (!a) return { removed, kept: [], skipped: "no approved release: nothing is pruned" };
  for (const id of listReleases(c)) if (!keep.has(id)) { rmSync(relDir(c, id), { recursive: true, force: true }); removed.push(id); log(`pruned ${id}`); }
  if (!existsSync(join(relDir(c, a.releaseId), "release.json"))) throw new ReleaseError("APPROVED_API_BUILD_MISSING", `prune would have removed the approved release ${a.releaseId}`); return { removed, kept: [...keep] };
}
export async function deployApi(c, ref, { skipTests = false, expandOnly = false, log = () => {} } = {}) {
  if (!ref || !String(ref).trim()) throw new ReleaseError("USAGE", "deploy-api needs an explicit source (a commit SHA or ref): the working tree HEAD is never assumed");
  const sha40 = resolveSource(c, ref);
  return withApiLock(c, async () => {
    const cand = await buildCandidate(c, sha40, { skipTests, expandOnly, log }); const cur = readApproved(c);
    if (cur?.releaseId === cand.id) { const up = await up_(c, log); return { ok: true, releaseId: cand.id, noop: true, ...up }; }
    try { const r = await activate(c, cand.id, { action: "deploy", log }); return { ...r, pruned: prune(c, log).removed }; }
    catch (e) { if (!cand.reused && !readApproved(c)?.history?.some((h) => h.releaseId === cand.id)) rmSync(relDir(c, cand.id), { recursive: true, force: true }); throw e; }
  });
}
export async function rollbackApi(c, target, log = () => {}) {
  return withApiLock(c, async () => {
    const a = readApproved(c); if (!a) throw new ReleaseError("NO_APPROVED_RELEASE", "nothing is approved: nothing to roll back");
    const to = target ?? a.previousKnownGood; if (!to) throw new ReleaseError("NO_PREVIOUS_KNOWN_GOOD", "there is no previous known-good API release recorded: give an explicit release id (releases-api)");
    if (to === a.releaseId) throw new ReleaseError("USAGE", `release ${to} is already the approved one`);
    const r = await activate(c, to, { action: "rollback", log }); return { ...r, prune: prune(c, log) };
  });
}
function foreignGuard(c) { const r = runningOf(c); if (r.foreign) throw new ReleaseError("FOREIGN_PROCESS", `port ${c.port} is used by pid ${r.foreignPid}, which is not the recorded public API${legacyApi(c) ? " (it is the legacy-started API: pin it with `init-api --from-running`)" : ""}: left alone`); return r; }
async function up_(c, log) {
  const a = readApproved(c); if (!a) throw new ReleaseError("NO_APPROVED_RELEASE", "no approved public API release: run `init-api --from-running` (pin what runs now) or `deploy-api <sha>`; nothing is built or started from the working tree");
  const v = verifyRelease(c, a.releaseId, { deep: true, approved: a }); if (!v.ok) throw new ReleaseError("APPROVED_API_BUILD_MISSING", `${v.problems.join("; ")} - the approved API is NOT rebuilt from HEAD, nor replaced by another jar: restore it or run deploy-api / rollback-api explicitly`, { problems: v.problems });
  const r = foreignGuard(c);
  if (r.owned && r.releaseId === a.releaseId && (await waitReady(c.port, 3000))) { log("API already runs the approved release"); return { releaseId: a.releaseId, started: false }; }
  if (r.owned) { log(`API runs ${r.releaseId ?? "an unapproved release"} / is unhealthy: replaced by the approved release ${a.releaseId}`); await stopApi(c); }
  const m = await startApi(c, a.releaseId); syncPidFile(c, m.pid); if (!(await waitReady(c.port, c.apiStartTimeoutMs))) throw new ReleaseError("START_FAILED", "the approved API did not become ready");
  rmSync(c.crashFile, { force: true }); rmSync(c.incidentFile, { force: true }); log(`API :${c.port} runs ${a.releaseId}`); return { releaseId: a.releaseId, started: true };
}
export const upApi = (c, log = () => {}) => withApiLock(c, () => up_(c, log));
export const restartApi = (c, log = () => {}) => withApiLock(c, async () => { const a = readApproved(c); if (!a) throw new ReleaseError("NO_APPROVED_RELEASE", "no approved public API release: nothing to restart"); const v = verifyRelease(c, a.releaseId, { deep: true, approved: a }); if (!v.ok) throw new ReleaseError("APPROVED_API_BUILD_MISSING", v.problems.join("; ")); const r = foreignGuard(c); if (r.owned) await stopApi(c); return up_(c, log); });
export const downApi = (c, log = () => {}) => withApiLock(c, async () => { const r = runningOf(c); if (r.foreign) return { api: "FOREIGN_PROCESS: left alone" }; const s = (await stopApi(c)).state ?? "STOPPED"; syncPidFile(c, null); log(`API: ${s}`); return { api: s }; });

// ------------------------------------------------------------------------------------------------------------------------------------------------ status
export function statusOf(c) {
  const approved = readApproved(c); const head = git(c.root, ["rev-parse", "HEAD"]).stdout.trim() || null; const crash = readJson(c.crashFile); const incident = readJson(c.incidentFile);
  const rel = approved ? readRelease(c, approved.releaseId) : null; const ver = approved ? verifyRelease(c, approved.releaseId, { deep: false, approved }) : null; const r = runningOf(c); const run = r.releaseId ? readRelease(c, r.releaseId) : null;
  let behind = null, behindTotal = null; if (rel && head && rel.sourceSha !== head) { const a = git(c.root, ["rev-list", "--count", `${rel.sourceSha}..${head}`, "--", "backend"]), b = git(c.root, ["rev-list", "--count", `${rel.sourceSha}..${head}`]); behind = a.status === 0 ? Number(a.stdout.trim()) : null; behindTotal = b.status === 0 ? Number(b.stdout.trim()) : null; }
  let state;
  if (r.foreign) state = legacyApi(c) ? "RUNNING_UNAPPROVED" : "FOREIGN_PROCESS";
  else if (!approved) state = r.listener ? "RUNNING_UNAPPROVED" : "NO_APPROVED_RELEASE";
  else if (!ver.ok) state = "APPROVED_API_BUILD_MISSING";
  else if (!r.listener) state = incident ? incident.state : crash ? "CRASH_LOOP" : "STOPPED";
  else if (r.releaseId !== approved.releaseId || (r.meta?.extra?.configFingerprint && r.meta.extra.configFingerprint !== rel.configFingerprint) || (r.meta?.extra?.jarSha256 && r.meta.extra.jarSha256 !== rel.jar.sha256)) state = "RUNNING_UNAPPROVED";
  else state = "CURRENT_APPROVED";
  const drift = rel ? driftOf(c, rel) : [];
  return { approved, rel, head, behind, behindTotal, crash, incident, verify: ver, drift, row: { port: c.port, pid: r.listener, health: null, runningSource: run?.sourceShort ?? (r.listener ? "unknown" : "-"), approvedSource: rel?.sourceShort ?? "-", integrationSource: head?.slice(0, 12) ?? "-",
    artifactDigest: (run?.jar?.sha256 ?? rel?.jar?.sha256 ?? "-").slice(0, 16), configFingerprint: rel?.configFingerprint ?? "-", state, releaseId: r.releaseId, approvedRelease: approved?.releaseId ?? null } };
}
/** informational: keys whose value in public.env now differs from the pinned approved configuration (a restart still uses the PINNED value) */
function driftOf(c, rel) { try { const now = deriveRuntimeConfig(c), pinned = listToObj(rel.runtimeConfig); return [...new Set([...Object.keys(now), ...Object.keys(pinned)])].filter((k) => now[k] !== pinned[k]).sort(); } catch { return []; } }
export async function statusWithHealth(c) {
  const s = statusOf(c); const r = s.row;
  if (!r.pid) r.health = "-"; else { const h = await httpGet(`http://127.0.0.1:${r.port}/actuator/health/readiness`, 3000); r.health = h.status === 200 ? "UP" : h.status ? `HTTP ${h.status}` : "DOWN"; if (r.state === "CURRENT_APPROVED" && r.health !== "UP") r.state = "UNHEALTHY"; }
  s.rollback = null; if (s.approved?.previousKnownGood) { const prev = readRelease(c, s.approved.previousKnownGood); if (prev) { try { const v = schemaVerdict(appliedMigrations(c), prev.schema.versions, { allowAhead: allowAheadOf(c) }); s.rollback = { to: prev.id, verdict: v.verdict, ahead: v.aheadBlocked }; } catch { s.rollback = { to: prev.id, verdict: "UNKNOWN" }; } } }
  return s;
}
export function formatStatus(s) {
  const r = s.row; const head = ["PORT", "PID", "HEALTH", "RUNNING_SOURCE", "APPROVED_SOURCE", "INTEGRATION_SOURCE", "ARTIFACT_DIGEST", "CONFIG_FINGERPRINT", "STATE"];
  const suffix = r.state === "CURRENT_APPROVED" ? (s.behind ? ` (BEHIND_INTEGRATION +${s.behind} backend commit${s.behind > 1 ? "s" : ""})` : s.behindTotal ? ` (integration +${s.behindTotal} commits, backend unchanged)` : "") : "";
  const row = [r.port, r.pid ?? "-", r.health ?? "-", r.runningSource, r.approvedSource, r.integrationSource, r.artifactDigest, r.configFingerprint, r.state + suffix]; const w = head.map((h, i) => Math.max(h.length, String(row[i]).length)); const line = (x) => x.map((v, i) => String(v).padEnd(w[i])).join("  ");
  const extra = [`approved API release: ${s.approved?.releaseId ?? "NONE"}${s.approved?.previousKnownGood ? `   previous known-good: ${s.approved.previousKnownGood}` : ""}`, ...(s.rollback ? [`rollback to ${s.rollback.to}: ${s.rollback.verdict}${s.rollback.ahead?.length ? ` (database ahead: ${s.rollback.ahead.join(", ")})` : ""}`] : []),
    ...(s.verify && !s.verify.ok ? s.verify.problems.map((p) => `! ${p}`) : []), ...(s.crash ? [`! crash loop since ${s.crash.since}: automatic restarts paused`] : []), ...(s.incident ? [`! ${s.incident.state}: ${s.incident.reason}`] : []), ...(s.drift.length ? [`config differs from public.env now (restart keeps the pinned value): ${s.drift.join(", ")}`] : [])];
  return [line(head), line(row), ...extra].join("\n");
}
export const okStatus = (s) => s.row.state === "CURRENT_APPROVED";

// ------------------------------------------------------------------------------------------------------------------------------------------------ adopt what runs now (evidence based)
function runningEnv(pid) {   // KEY=VALUE tokens of the process environment (kept in memory only; a secret is compared, never printed or stored)
  const r = spawnSync("ps", ["eww", "-p", String(pid)], { encoding: "utf8" }); const out = r.stdout.split("\n").slice(1).join(" "); const at = out.search(/\s-jar\s+\S+\.jar/); const tail = at < 0 ? out : out.slice(out.indexOf(".jar", at) + 4); const env = {};
  for (const m of tail.matchAll(/(?:^|\s)([A-Za-z_][A-Za-z0-9_]*)=(\S*)/g)) env[m[1]] = m[2]; return env;
}
function openInode(pid, path) { const r = spawnSync("lsof", ["-nP", "-p", String(pid), "-F", "ni"], { encoding: "utf8" }); let ino = null; for (const l of r.stdout.split("\n")) { if (l[0] === "i") ino = l.slice(1); else if (l[0] === "n" && l.slice(1) === path && ino) return Number(ino); } return null; }
const referencedEnv = (dir) => { const names = new Set(); const walk = (d) => { for (const e of readdirSync(d, { withFileTypes: true })) { const p = join(d, e.name); if (e.isDirectory()) walk(p); else if (/\.(ya?ml|kt|properties)$/.test(e.name)) { const t = readFileSync(p, "utf8"); for (const m of t.matchAll(/\$\{([A-Z][A-Z0-9_]*)/g)) names.add(m[1]); for (const m of t.matchAll(/getenv\("([A-Z][A-Z0-9_]*)"\)/g)) names.add(m[1]); } } }; try { walk(join(dir, "backend", "src", "main")); } catch { /* none */ } return names; };

/**
 * initApiFromRunning(c, { source, dryRun }): pin the API that runs RIGHT NOW. Nothing is restarted and nothing is deployed. Every proof must hold or it STOPS (no guessing):
 *  the listener is the process the old lifecycle recorded (api.pid, cwd = .run/public, java -jar <jar>) | the jar on disk is the very file the process has open (same inode, not modified after the process started)
 *  | readiness UP | the database's applied migrations equal the jar's migrations (versions + checksums, nothing pending, nothing ahead) | the source: a jar REBUILT from the commit has the same content digest
 *  (name + length + CRC of every entry) as the running jar | the non-secret environment of the process is recorded as it IS; every environment variable the backend reads and the process has is accounted for.
 */
export async function initApiFromRunning(c, { source = null, dryRun = false, log = () => {} } = {}) {
  return withApiLock(c, async () => {
    if (readApproved(c)) throw new ReleaseError("USAGE", "an approved API release already exists: use deploy-api / rollback-api to change it");
    const problems = []; const pid = listenerPids(c.port)[0]; if (!pid) throw new ReleaseError("ADOPT_REFUSED", `nothing listens on ${c.port}: cannot pin an API that is not running`);
    let recorded = ""; try { recorded = readFileSync(join(c.run, "api.pid"), "utf8").trim(); } catch { /* none */ } const meta0 = readMeta(c.stateFile);
    if (recorded !== String(pid) && !(meta0 && identify(meta0).state === "OWNED" && meta0.pid === pid)) throw new ReleaseError("ADOPT_REFUSED", `the listener on ${c.port} (pid ${pid}) is not the pid the lifecycle recorded (${recorded || "none"}): not ours`);
    if (cwdOf(pid) !== c.run) throw new ReleaseError("ADOPT_REFUSED", `the API's cwd ${cwdOf(pid)} is not ${c.run}`);
    const cmd = commandOf(pid); const jarPath = /\s-jar\s+(\S+\.jar)/.exec(cmd)?.[1]; if (!jarPath || !existsSync(jarPath)) throw new ReleaseError("ADOPT_REFUSED", `cannot find the jar of the running API in its command line (${cmd.slice(0, 120)})`);
    const st = statSync(jarPath); const ino = openInode(pid, jarPath); if (ino == null || ino !== st.ino) throw new ReleaseError("ADOPT_REFUSED", `the jar ${jarPath} on disk is not the file the process has open (inode ${st.ino} vs ${ino ?? "not open"}): it was replaced after the start`);
    const started = new Date(startTimeOf(pid)); if (st.mtimeMs > started.getTime() + 1000) throw new ReleaseError("ADOPT_REFUSED", `the jar was modified (${new Date(st.mtimeMs).toISOString()}) after the process started (${started.toISOString()})`);
    const jarBuf = readFileSync(jarPath); const jarSha = sha(jarBuf); const entries = jarEntries(jarPath); const eDigest = entryDigest(entries); const migrations = jarMigrations(jarPath, entries);
    const ready = await httpGet(`http://127.0.0.1:${c.port}/actuator/health/readiness`, 5000); if (ready.status !== 200) throw new ReleaseError("ADOPT_REFUSED", `the running API is not ready (readiness ${ready.status || ready.error})`);
    const sv = schemaVerdict(appliedMigrations(c), migrations); if (!sv.ok || sv.pending.length || sv.ahead.length) throw new ReleaseError("ADOPT_REFUSED", `the database does not match the running jar's migrations: ${verdictText(sv)}; pending ${sv.pending.join(",") || "-"}; ahead ${sv.ahead.join(",") || "-"}`);
    const javaBin = cmd.split(/\s+/).find((t) => /(^|\/)java$/.test(t)) ?? c.java; const javaMajor = javaMajorOf(javaBin);
    // ---- the source: a candidate commit (explicit, or the newest commit not newer than the jar) must REPRODUCE the running jar
    const cand = source ? resolveSource(c, source) : git(c.root, ["rev-list", "-1", `--before=${new Date(st.mtimeMs + 1000).toISOString()}`, "HEAD"]).stdout.trim(); if (!cand) throw new ReleaseError("ADOPT_REFUSED", "no candidate source commit found for the running jar");
    const treeSha = git(c.root, ["rev-parse", `${cand}:backend`]).stdout.trim(); const dir = join(c.buildDir, `adopt-${cand.slice(0, 12)}`); rmSync(dir, { recursive: true, force: true });
    let refEnv; try {
      log(`candidate source ${cand.slice(0, 12)} (backend tree ${treeSha.slice(0, 12)}): rebuilding the jar to compare it with the running one`); await extractBackend(c.root, cand, dir); refEnv = referencedEnv(dir);
      const b = gradle(c, dir, c.buildCmd, "build"); if (!b.ok) throw new ReleaseError("ADOPT_REFUSED", `the candidate source ${cand.slice(0, 12)} does not build (log ${b.log}): ${b.tail}`);
      const re = jarEntries(builtJar(dir)); const rd = entryDigest(re);
      if (rd !== eDigest) { const a = new Map(entries.map((e) => [e.name, `${e.length}/${e.crc}`])), bm = new Map(re.map((e) => [e.name, `${e.length}/${e.crc}`])); const diff = [...new Set([...a.keys(), ...bm.keys()])].filter((k) => a.get(k) !== bm.get(k)); throw new ReleaseError("ADOPT_REFUSED", `the exact source of the running jar cannot be proven: the jar rebuilt from ${cand.slice(0, 12)} differs from it in ${diff.length} of ${a.size} entries (e.g. ${diff.slice(0, 3).join(", ")}): STOPPED, nothing recorded`); }
    } finally { rmSync(dir, { recursive: true, force: true }); }
    // ---- environment: record the non-secret environment as it IS; secrets only compared
    const live = runningEnv(pid); const derived = deriveRuntimeConfig(c); const pub = readEnvFile(join(c.run, "public.env")); const pinned = {}; const drift = [];
    for (const [k, v] of Object.entries(derived)) { if (live[k] === undefined) { drift.push({ key: k, running: null, derived: v }); continue; } pinned[k] = live[k]; if (live[k] !== v) drift.push({ key: k, running: live[k], derived: v }); }
    const secretDiff = Object.keys(pub).filter(isSecretKey).filter((k) => live[k] !== pub[k]); const unaccounted = [...refEnv].filter((k) => live[k] !== undefined && pinned[k] === undefined && pub[k] === undefined).sort();
    if (unaccounted.length) throw new ReleaseError("ADOPT_REFUSED", `the running process has environment variables the backend reads that neither the pinned configuration nor public.env provides (${unaccounted.join(", ")}): a restart would not reproduce it`);
    const missing = drift.filter((d) => d.running === null).map((d) => d.key); if (missing.length) throw new ReleaseError("ADOPT_REFUSED", `the running process lacks configuration that the lifecycle would set (${missing.join(", ")})`);
    const list = configList(pinned); const cfgFp = configFingerprintOf(list, javaMajor); const id = `${cand.slice(0, 12)}-${cfgFp.slice(0, 8)}`;
    const evidence = { pid, command: cmd.replace(/\s+/g, " ").slice(0, 200), jarPath, jarInode: ino, jarSha256: jarSha, jarEntryDigest: eDigest, javaMajor, readiness: "UP", flyway: { applied: migrations.length, maxVersion: migrations.at(-1)?.version, checksumsEqual: true }, sourceReproduced: { commit: cand, backendTreeSha: treeSha, entriesCompared: entries.length },
      env: { nonSecretKeys: Object.keys(pinned).length, differingFromPublicEnv: drift.map((d) => ({ key: d.key, running: d.running, derived: d.derived })), secretKeysEqualToPublicEnv: Object.keys(pub).filter(isSecretKey).length - secretDiff.length, secretKeysDiffering: secretDiff } };
    if (dryRun) return { dryRun: true, releaseId: id, sourceSha: cand, evidence };
    const stage = `${relDir(c, id)}.tmp.${process.pid}`; rmSync(stage, { recursive: true, force: true }); mkdirSync(stage, { recursive: true }); const r0 = spawnSync("cp", ["-c", jarPath, join(stage, "app.jar")]); if (r0.status !== 0) copyFileSync(jarPath, join(stage, "app.jar"));
    if (sha(readFileSync(join(stage, "app.jar"))) !== jarSha) { rmSync(stage, { recursive: true, force: true }); throw new ReleaseError("ADOPT_REFUSED", "the copy of the jar differs from the running one"); }
    const record = { schema: 1, id, kind: "adopted", sourceSha: cand, sourceShort: cand.slice(0, 12), backendTreeSha: treeSha, createdAt: new Date().toISOString(), javaMajor, buildFingerprint: buildFingerprintOf(treeSha, javaMajor), configFingerprint: cfgFp, runtimeConfig: list, secretKeyNames: secretKeyNames(c),
      jar: { file: "app.jar", sha256: jarSha, size: st.size, entryDigest: eDigest }, schema: { versions: migrations, maxVersion: migrations.at(-1)?.version ?? null, expandOnly: [] }, verification: { tests: "not-run (adopted)", build: "reproduced from the commit", rehearsal: "n/a" }, adoption: { at: new Date().toISOString(), evidence } };
    writeJsonAtomic(join(stage, "release.json"), record); rmSync(relDir(c, id), { recursive: true, force: true }); renameSync(stage, relDir(c, id));
    const members = [{ pid, startTime: startTimeOf(pid), command: commandOf(pid) }];   // legacy ownership -> helper metadata, WITHOUT touching the process
    writeMeta(c.stateFile, { schema: 1, owner: "public", name: "api", mode: "public", port: c.port, pid, pgid: null, startTime: members[0].startTime, command: members[0].command, cwd: cwdOf(pid), listenerPid: pid, members, startedBy: "adopted", recordedAt: new Date().toISOString(), extra: { releaseId: id, jarSha256: jarSha, configFingerprint: cfgFp, adoptedFromLegacy: true } });
    writeJsonAtomic(c.approvedFile, approvedRecord(c, id, { action: "init", from: null, previousKnownGood: null })); log(`pinned API ${id} (source ${cand.slice(0, 12)})`); return { releaseId: id, sourceSha: cand, evidence };
  });
}
