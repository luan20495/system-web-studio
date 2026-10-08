// @class: unit
// Explicit public deployment pinning (C0, D-C0-49):  PROCESS RECOVERY != DEPLOYMENT.
//
// The three PUBLIC portals (Platform, Admin, Studio) run from an immutable RELEASE that an operator approved, never from "whatever the working tree is now":
//   release   = a self-contained directory  <run>/releases/<sha12>-<cfg8>/   with  src (git archive of the approved SHA: its next.config and every file the config imports),
//               apps/<p>/<dist> (the BUILT portals), node_modules (own copy), release.json (immutable record, NO secrets).
//   approved  = <run>/approved.json  -> { releaseId, releaseSha256, previousKnownGood, history }   the ONLY authority for what runs. Working-tree HEAD is not.
// Measured, not assumed (Next 16): the proxy target, headers and rewrites are baked at build; `next start` still LOADS next.config.ts and its imports (a missing import = no start) and reads
// STUDIO_HSTS / SITES_ORIGIN / MINIO_PUBLIC_ENDPOINT at run time. So a release freezes the config sources (snapshot) and pins both env groups (non-secret URLs):
//   buildEnv     baked into the bundle / manifests      -> part of the build fingerprint
//   runtimeEnv   read by the running server             -> pinned in the record too; a restart uses the PINNED values
//   configFingerprint = sha(buildEnv + runtimeEnv + node major + lockfile sha)
// A restart / crash recovery starts the approved release: never a build, never HEAD, never "the newest dist found". Changing what is public needs `deploy <sha>`, which builds a candidate in its own
// directory, proves it healthy on temporary ports, only THEN moves the approved pointer (atomic rename), then replaces the running portals (stop-then-start: NOT zero-downtime), and rolls back
// automatically when the replacement does not come up. Missing / corrupt approved artifact => APPROVED_BUILD_MISSING, fail closed.
import { spawn, spawnSync } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync, openSync, closeSync } from "node:fs";
import { dirname, join, resolve, relative } from "node:path";
import { tmpdir } from "node:os";
import net from "node:net";
import { startOwned, stopOwned, identify, readMeta, writeMeta, listenerPids, commandOf, cwdOf, startTimeOf, exists } from "./owned-process.mjs";

export const NAMES = ["platform", "admin", "studio"];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sha = (b) => createHash("sha256").update(b).digest("hex");
const git = (root, args, o = {}) => spawnSync("git", ["-C", root, ...args], { encoding: "utf8", ...o });
export class ReleaseError extends Error { constructor(code, message, extra = {}) { super(message); this.code = code; Object.assign(this, extra); } }

// ------------------------------------------------------------------------------------------------------------------------------------------------ context / config
const ENV_KEYS = ["PUBLIC_HOST", "PUBLIC_PLATFORM_HOST", "PUBLIC_ADMIN_HOST", "PORTAL_PLATFORM_PORT_PUBLIC", "PORTAL_ADMIN_PORT_PUBLIC", "PORTAL_STUDIO_PORT_PUBLIC", "API_PORT", "SITES_HOST", "PUBLIC_FILES_HOST"];
/** only the NON-SECRET keys the portals need are read from public.env; everything else in that file is never loaded into this process or into any record */
export function parsePublicEnv(file) {
  const out = {}; if (!existsSync(file)) return out;
  for (const l of readFileSync(file, "utf8").split("\n")) { const m = /^([A-Z0-9_]+)=(.*)$/.exec(l.trim()); if (m && ENV_KEYS.includes(m[1])) out[m[1]] = m[2].replace(/^["']|["']$/g, ""); }
  return out;
}
export function context(env = process.env, scriptsRoot = null) {
  const root = resolve(env.PUBLIC_ROOT ?? scriptsRoot ?? process.cwd()); const run = resolve(root, env.PL_PUBLIC_RUN ?? ".run/public"); const pe = parsePublicEnv(join(run, "public.env"));
  const host = (k, d) => pe[k] ?? d;
  return {
    root, run, releasesDir: join(run, "releases"), approvedFile: join(run, "approved.json"), lockFile: join(run, "release.lock"), crashFile: join(run, "crash-loop.json"),
    ports: { platform: Number(pe.PORTAL_PLATFORM_PORT_PUBLIC ?? 3201), admin: Number(pe.PORTAL_ADMIN_PORT_PUBLIC ?? 3202), studio: Number(pe.PORTAL_STUDIO_PORT_PUBLIC ?? 3203) },
    hosts: { platform: host("PUBLIC_PLATFORM_HOST", "platform.toolsmcp.uk"), admin: host("PUBLIC_ADMIN_HOST", "admin.toolsmcp.uk"), studio: host("PUBLIC_HOST", "studio.toolsmcp.uk") },
    apiPort: Number(pe.API_PORT ?? 18081), sitesHost: host("SITES_HOST", "sites.toolsmcp.uk"), filesHost: host("PUBLIC_FILES_HOST", "studio-files.toolsmcp.uk"),
    deps: env.PUBLIC_RELEASE_DEPS ?? "install", smokeApi: env.PUBLIC_RELEASE_SMOKE_API !== "0",
    startTimeoutMs: Number(env.PUBLIC_RELEASE_START_TIMEOUT_MS ?? 60000), graceMs: Number(env.PUBLIC_RELEASE_GRACE_MS ?? 8000),
  };
}
/** baked into the bundle and the manifests: a change is a different BUILD */
export const buildEnvOf = (c) => [`NEXT_PUBLIC_API_MODE=http`, `API_PROXY_TARGET=http://127.0.0.1:${c.apiPort}`, `NEXT_PUBLIC_PORTAL_URL_PLATFORM=https://${c.hosts.platform}`, `NEXT_PUBLIC_PORTAL_URL_ADMIN=https://${c.hosts.admin}`, `NEXT_PUBLIC_PORTAL_URL_STUDIO=https://${c.hosts.studio}`];
/** read by the running server (CSP, HSTS); pinned in the release so a restart cannot silently change them */
export const runtimeEnvOf = (c) => [`STUDIO_HSTS=true`, `MINIO_PUBLIC_ENDPOINT=https://${c.filesHost}`, `SITES_ORIGIN=https://${c.sitesHost}`];
const envObj = (list) => Object.fromEntries(list.map((kv) => [kv.slice(0, kv.indexOf("=")), kv.slice(kv.indexOf("=") + 1)]));
export const configFingerprint = ({ buildEnv, runtimeEnv, nodeMajor, lockfileSha }) => sha(JSON.stringify([[...buildEnv].sort(), [...runtimeEnv].sort(), nodeMajor, lockfileSha])).slice(0, 16);

// ------------------------------------------------------------------------------------------------------------------------------------------------ files, digests
export function writeJsonAtomic(file, obj) { mkdirSync(dirname(file), { recursive: true }); const t = `${file}.tmp.${process.pid}.${randomBytes(3).toString("hex")}`; writeFileSync(t, JSON.stringify(obj, null, 2) + "\n", { mode: 0o600 }); renameSync(t, file); }
export const readJson = (file) => { try { return JSON.parse(readFileSync(file, "utf8")); } catch { return null; } };
function walkFiles(dir, base = dir, acc = []) { let n; try { n = readdirSync(dir, { withFileTypes: true }); } catch { return acc; } for (const e of n) { const p = join(dir, e.name); if (e.isDirectory()) { if (e.name === "cache" && dir === base) continue; walkFiles(p, base, acc); } else if (e.isFile()) acc.push(relative(base, p)); } return acc; }
/** content digest of a built dist directory (the runtime `cache/` Next writes is excluded): detects a missing, truncated or modified artifact */
export function distDigest(dir) { const h = createHash("sha256"); for (const f of walkFiles(dir).sort()) { h.update(f + "\0"); h.update(readFileSync(join(dir, f))); h.update("\0"); } return h.digest("hex").slice(0, 16); }
const lockSha = (root) => { try { return sha(readFileSync(join(root, "package-lock.json"))).slice(0, 16); } catch { return "no-lock"; } };
const relDir = (c, id) => join(c.releasesDir, id);

// ------------------------------------------------------------------------------------------------------------------------------------------------ release records
export const readRelease = (c, id) => readJson(join(relDir(c, id), "release.json"));
export const readApproved = (c) => readJson(c.approvedFile);
export function listReleases(c) { try { return readdirSync(c.releasesDir).filter((n) => existsSync(join(c.releasesDir, n, "release.json"))); } catch { return []; } }
const releaseSha = (c, id) => { try { return sha(readFileSync(join(relDir(c, id), "release.json"))); } catch { return null; } };

/**
 * verifyRelease(c, id, { deep }) -> { ok, problems[] }   (problems start with APPROVED_BUILD_MISSING / RELEASE_TAMPERED / ...)
 * quick: record present and unmodified (sha256 of release.json), every dist has its BUILD_ID;  deep: also the content digest of every dist.
 */
export function verifyRelease(c, id, { deep = false, approved = readApproved(c) } = {}) {
  const problems = []; const rel = readRelease(c, id);
  if (!rel) return { ok: false, problems: [`APPROVED_BUILD_MISSING: release ${id} has no release.json`] };
  if (approved?.releaseId === id && approved.releaseJsonSha256 && approved.releaseJsonSha256 !== releaseSha(c, id)) problems.push(`RELEASE_TAMPERED: release.json of ${id} differs from the approved digest`);
  for (const n of NAMES) {
    const p = rel.portals?.[n]; const dist = join(relDir(c, id), "apps", n, p?.distDir ?? ".next");
    if (!p) { problems.push(`APPROVED_BUILD_MISSING: ${n} is not part of release ${id}`); continue; }
    if (!existsSync(join(dist, "BUILD_ID"))) { problems.push(`APPROVED_BUILD_MISSING: ${n} dist ${dist} has no BUILD_ID`); continue; }
    if (readFileSync(join(dist, "BUILD_ID"), "utf8").trim() !== p.buildId) problems.push(`APPROVED_BUILD_MISSING: ${n} BUILD_ID differs from the record (artifact replaced)`);
    if (deep && distDigest(dist) !== p.distDigest) problems.push(`APPROVED_BUILD_MISSING: ${n} dist content digest differs from the record (corrupt or modified artifact)`);
    if (!existsSync(join(relDir(c, id), "node_modules"))) problems.push(`APPROVED_BUILD_MISSING: release ${id} has no node_modules`);
  }
  return { ok: problems.length === 0, problems };
}

// ------------------------------------------------------------------------------------------------------------------------------------------------ lock
export async function withLock(c, fn) {
  mkdirSync(c.run, { recursive: true });
  for (let tries = 0; ; tries++) {
    try { const fd = openSync(c.lockFile, "wx"); writeFileSync(fd, JSON.stringify({ pid: process.pid, startTime: startTimeOf(process.pid), at: new Date().toISOString() })); closeSync(fd); break; }
    catch (e) {
      if (e.code !== "EEXIST") throw e; const o = readJson(c.lockFile);
      const live = o && exists(o.pid) && startTimeOf(o.pid) === o.startTime;
      if (!live) { rmSync(c.lockFile, { force: true }); continue; }                                  // stale lock of a dead / reused pid
      if (tries >= 3) throw new ReleaseError("LOCKED", `another public release operation is running (pid ${o.pid} since ${o.at})`);
      await sleep(500);
    }
  }
  try { return await fn(); } finally { rmSync(c.lockFile, { force: true }); }
}

// ------------------------------------------------------------------------------------------------------------------------------------------------ processes
const stateFileOf = (c, name) => join(c.run, `portal-${name}.owned.json`);
const nextBinOf = (c, id) => join(relDir(c, id), "node_modules", "next", "dist", "bin", "next");
const tcpOpen = (port) => new Promise((res) => { const s = net.connect({ host: "127.0.0.1", port }); const d = (v) => { s.destroy(); res(v); }; s.once("connect", () => d(true)); s.once("error", () => d(false)); s.setTimeout(1500, () => d(false)); });
async function freePort() { return new Promise((res) => { const s = net.createServer(); s.listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => res(p)); }); }); }

/** start portal `name` of release `id` (cwd = the release's own apps/<name>; env = the release's PINNED build + runtime env, nothing from the working tree) */
export async function startPortal(c, id, name, { port, stateFile = stateFileOf(c, name), tag = "public" } = {}) {
  const rel = readRelease(c, id); const p = rel.portals[name]; const env = { ...envObj(rel.buildEnv), ...envObj(rel.runtimeEnv), NODE_ENV: "production", ...(p.distDir !== ".next" ? { NEXT_DIST_DIR: p.distDir } : {}) };
  return startOwned({ owner: tag, name, stateFile, cwd: join(relDir(c, id), "apps", name), port, mode: tag === "public" ? "public" : "validate", group: true,
    cmd: [process.execPath, nextBinOf(c, id), "start", "-H", "127.0.0.1", "-p", String(port)], env, ready: { port, timeoutMs: c.startTimeoutMs }, extra: { releaseId: id, buildId: p.buildId } });
}
export async function stopPortal(c, name) {
  const f = stateFileOf(c, name); const meta = readMeta(f); if (!meta) return { state: "NO_RECORD" };
  return stopOwned(meta, { graceMs: c.graceMs, removeState: true });
}
/** the old lifecycle stored `ps -o lstart=` verbatim (day padded: "Oct  8"); the helper normalises whitespace ("Oct 8"): same instant, compare normalised */
const sameStart = (a, b) => String(a).trim().replace(/\s+/g, " ") === String(b).trim().replace(/\s+/g, " ");
/** a process the OLD lifecycle recorded (pid file + start time + cwd in this checkout) but that is not pinned yet: ours, not foreign - `init --from-running` adopts it */
export function legacyOwned(c, name) {
  const pids = listenerPids(c.ports[name]); const pid = pids[0]; const get = (e) => { try { return readFileSync(join(c.run, `portal-${name}.${e}`), "utf8").trim(); } catch { return ""; } };
  return !!pid && get("pid") === String(pid) && (!get("pidstart") || sameStart(get("pidstart"), startTimeOf(pid))) && cwdOf(pid) === join(c.root, "apps", name);
}
/** what runs on the real port of `name`: { listener, owned, releaseId, buildId, foreign } - ownership by recorded identity ONLY */
export function runningOf(c, name) {
  const port = c.ports[name]; const pids = listenerPids(port); const meta = readMeta(stateFileOf(c, name)); const id = meta ? identify(meta) : { state: "UNKNOWN" };
  const ownedPids = new Set(id.state === "OWNED" ? [meta.pid, ...(meta.members ?? []).map((m) => m.pid), ...(meta.listenerPid ? [meta.listenerPid] : [])] : []);
  if (!pids.length) return { port, listener: null, owned: false, foreign: false, meta, identity: id.state };
  const foreignPid = pids.find((p) => !ownedPids.has(p));
  return { port, listener: pids[0], owned: foreignPid == null, foreign: foreignPid != null, foreignPid, releaseId: meta?.extra?.releaseId ?? null, buildId: meta?.extra?.buildId ?? null, meta, identity: id.state };
}
/** one fresh connection per request (`Connection: close`) and one retry: a pooled keep-alive socket to the PREVIOUS process on the same port must never read as "unhealthy" */
async function httpGet(url, ms = 4000) {
  let last = null;
  for (let i = 0; i < 2; i++) { try { const r = await fetch(url, { signal: AbortSignal.timeout(ms), redirect: "manual", headers: { connection: "close" } }); return { status: r.status, headers: r.headers, text: await r.text() }; } catch (e) { last = { status: 0, error: String(e.message ?? e), headers: new Headers(), text: "" }; await sleep(150); } }
  return last;
}
/** smoke of a started portal: the login page renders HTML that links Next assets, an asset loads, CSP is present, the same-origin API proxy answers (not a 5xx) */
export async function smoke(c, port) {
  const base = `http://127.0.0.1:${port}`; const problems = [];
  const login = await httpGet(`${base}/login`); if (login.status !== 200) problems.push(`/login answered ${login.status || login.error}`);
  const asset = /\/_next\/static\/[^"'\s]+/.exec(login.text)?.[0]; if (!asset) problems.push("/login links no /_next/static asset"); else { const a = await httpGet(`${base}${asset}`); if (a.status !== 200) problems.push(`asset ${asset} answered ${a.status}`); }
  if (!login.headers.get("content-security-policy")) problems.push("no Content-Security-Policy header");
  if (c.smokeApi) { const api = await httpGet(`${base}/api/v1/auth/config`); if (api.status === 0 || api.status >= 500) problems.push(`same-origin API proxy answered ${api.status || api.error}`); }
  return { ok: problems.length === 0, problems };
}
async function waitHealthy(port, ms = 20000) { const end = Date.now() + ms; while (Date.now() < end) { const r = await httpGet(`http://127.0.0.1:${port}/login`, 3000); if (r.status > 0 && r.status < 500) return true; await sleep(300); } return false; }

// ------------------------------------------------------------------------------------------------------------------------------------------------ build a candidate
function extractSnapshot(root, sha12, dir) {
  return new Promise((res, rej) => {
    mkdirSync(dir, { recursive: true }); const a = spawn("git", ["-C", root, "archive", "--format=tar", sha12], { stdio: ["ignore", "pipe", "pipe"] }); const t = spawn("tar", ["-x", "-C", dir], { stdio: ["pipe", "ignore", "pipe"] });
    let err = ""; a.stderr.on("data", (d) => (err += d)); t.stderr.on("data", (d) => (err += d)); a.stdout.pipe(t.stdin);
    let ca = null, ct = null; const fin = () => { if (ca != null && ct != null) (ca === 0 && ct === 0 ? res() : rej(new ReleaseError("SNAPSHOT_FAILED", `git archive ${sha12}: ${err.trim().slice(0, 200)}`))); };
    a.on("close", (x) => { ca = x; fin(); }); t.on("close", (x) => { ct = x; fin(); });
  });
}
const cloneTree = (src, dst) => { const r = spawnSync("cp", ["-Rc", src, dst], { encoding: "utf8" }); if (r.status !== 0) { rmSync(dst, { recursive: true, force: true }); cpSync(src, dst, { recursive: true, verbatimSymlinks: true }); } };
function installDeps(c, dir) {
  if (c.deps === "skip") { mkdirSync(join(dir, "node_modules"), { recursive: true }); return; }
  if (c.deps === "clone") { const src = join(c.root, "node_modules"); if (!existsSync(src)) throw new ReleaseError("DEPS_FAILED", `no node_modules to clone at ${src}`); cloneTree(src, join(dir, "node_modules")); return; }
  const r = spawnSync("npm", ["ci", "--no-audit", "--no-fund"], { cwd: dir, encoding: "utf8", timeout: 600000 });
  if (r.status !== 0) throw new ReleaseError("DEPS_FAILED", `npm ci failed in the candidate: ${(r.stderr || r.stdout).trim().slice(-300)}`);
}
function buildPortal(c, dir, name, buildEnv) {
  const app = join(dir, "apps", name); const log = join(dir, `build-${name}.log`); const fd = openSync(log, "w");
  const r = spawnSync(process.execPath, [join(dir, "node_modules", "next", "dist", "bin", "next"), "build"], { cwd: app, env: { ...process.env, ...envObj(buildEnv), NODE_ENV: "production", NEXT_DIST_DIR: ".next" }, stdio: ["ignore", fd, fd], timeout: 1200000 }); closeSync(fd);
  if (r.status !== 0 || !existsSync(join(app, ".next", "BUILD_ID"))) throw new ReleaseError("CANDIDATE_BUILD_FAILED", `build of ${name} failed (log ${log}): ${readFileSync(log, "utf8").split("\n").slice(-6).join(" | ").slice(0, 300)}`);
}
/** the fingerprint TOOL is the one of the running lifecycle (next to this file), applied to the snapshot: never the copy inside the snapshot (an older SHA carries an older tool) */
const FP_TOOL = new URL("../portal-fingerprint.mjs", import.meta.url).pathname;
const fingerprintOf = (dir, name, buildEnv) => { const r = spawnSync(process.execPath, [FP_TOOL, "--root", dir, "--app", name, "--", ...buildEnv], { encoding: "utf8" }); return r.status === 0 ? r.stdout.trim() : null; };

/** resolve an explicit commit-ish; refuses an empty ref (no implicit HEAD) */
export function resolveSource(c, ref) {
  if (!ref || !String(ref).trim()) throw new ReleaseError("USAGE", "deploy needs an explicit source (a commit SHA or ref): the working tree HEAD is never assumed");
  const r = git(c.root, ["rev-parse", "--verify", `${ref}^{commit}`]); if (r.status !== 0) throw new ReleaseError("UNKNOWN_SOURCE", `'${ref}' is not a commit of ${c.root}`);
  return r.stdout.trim();
}

/** build (or reuse) the candidate release for `sha` under the CURRENT public.env build / runtime configuration */
export async function buildCandidate(c, sha40, log = () => {}) {
  const buildEnv = buildEnvOf(c), runtimeEnv = runtimeEnvOf(c); const nodeMajor = Number(process.versions.node.split(".")[0]);
  const lockOfSource = (() => { const r = git(c.root, ["show", `${sha40}:package-lock.json`], { maxBuffer: 64 * 1024 * 1024 }); return r.status === 0 ? sha(r.stdout).slice(0, 16) : "no-lock"; })();
  const cfg = configFingerprint({ buildEnv, runtimeEnv, nodeMajor, lockfileSha: lockOfSource }); const id = `${sha40.slice(0, 12)}-${cfg.slice(0, 8)}`; const dir = relDir(c, id);
  const existing = readRelease(c, id); if (existing) { const v = verifyRelease(c, id, { deep: true, approved: null }); if (v.ok) { log(`release ${id} already built: reused (no rebuild)`); return { id, reused: true }; } log(`release ${id} exists but is incomplete / corrupt (${v.problems[0]}): rebuilt`); }
  rmSync(dir, { recursive: true, force: true });
  try {
    log(`snapshot of ${sha40.slice(0, 12)} -> ${dir}`); await extractSnapshot(c.root, sha40, dir);
    log(`dependencies (${c.deps})`); installDeps(c, dir);
    const portals = {};
    for (const n of NAMES) { log(`building portal ${n}`); buildPortal(c, dir, n, buildEnv); const distDir = ".next"; const d = join(dir, "apps", n, distDir); portals[n] = { distDir, buildId: readFileSync(join(d, "BUILD_ID"), "utf8").trim(), buildFingerprint: fingerprintOf(dir, n, buildEnv), distDigest: distDigest(d) }; }
    const record = { schema: 1, id, kind: "built", sourceSha: sha40, sourceShort: sha40.slice(0, 12), createdAt: new Date().toISOString(), nodeMajor, lockfileSha: lockOfSource, buildEnv, runtimeEnv, configFingerprint: cfg, portals };
    writeJsonAtomic(join(dir, "release.json"), record); return { id, reused: false };
  } catch (e) { rmSync(dir, { recursive: true, force: true }); throw e; }
}

// ------------------------------------------------------------------------------------------------------------------------------------------------ activate (shared by deploy / rollback / init)
function approvedRecord(c, id, { action, from = null, previousKnownGood = undefined, history }) {
  const prev = readApproved(c); const h = [...(prev?.history ?? []), { at: new Date().toISOString(), action, releaseId: id, from }].slice(-20);
  return { schema: 1, releaseId: id, releaseJsonSha256: releaseSha(c, id), approvedAt: new Date().toISOString(), approvedBy: process.env.USER ?? "unknown", previousKnownGood: previousKnownGood === undefined ? (prev?.releaseId && prev.releaseId !== id ? prev.releaseId : prev?.previousKnownGood ?? null) : previousKnownGood, history: history ?? h };
}
/** prove the candidate healthy on TEMPORARY ports (the real portals are not touched) */
export async function validateCandidate(c, id, log = () => {}) {
  const problems = []; const started = [];
  try {
    for (const n of NAMES) {
      const port = await freePort(); const sf = join(c.run, `validate-${n}.owned.json`);
      try { const m = await startPortal(c, id, n, { port, stateFile: sf, tag: "public-validate" }); started.push(m); } catch (e) { problems.push(`${n}: did not start on a temporary port (${e.code ?? "ERROR"}: ${String(e.message).slice(0, 160)})`); continue; }
      const s = await smoke(c, port); log(`validate ${n} :${port} -> ${s.ok ? "ok" : s.problems.join("; ")}`); for (const p of s.problems) problems.push(`${n}: ${p}`);
    }
  } finally { for (const m of started) await stopOwned(m, { graceMs: 3000, removeState: true }); }
  return { ok: problems.length === 0, problems };
}
async function replaceRunning(c, id, log) {
  const started = [];
  for (const n of NAMES) {
    await stopPortal(c, n);
    try { await startPortal(c, id, n, { port: c.ports[n] }); started.push(n); } catch (e) { return { ok: false, failed: n, error: `${e.code ?? "ERROR"}: ${String(e.message).slice(0, 200)}`, started }; }
    if (!(await waitHealthy(c.ports[n]))) return { ok: false, failed: n, error: "not healthy after start", started: [...started] };
    log(`portal ${n} :${c.ports[n]} runs ${id}`);
  }
  return { ok: true, started };
}
/**
 * activate(c, id, { action }): verify -> prove healthy on temporary ports -> move the approved pointer (atomic) -> replace the running portals -> on failure roll back automatically.
 * The pointer moves ONLY after the candidate is proven healthy; if the replacement fails the previous release is approved and started again.
 */
export async function activate(c, id, { action, log = () => {} }) {
  const before = readApproved(c); const v = verifyRelease(c, id, { deep: true, approved: null }); if (!v.ok) throw new ReleaseError("APPROVED_BUILD_MISSING", v.problems.join("; "), { problems: v.problems });
  const val = await validateCandidate(c, id, log); if (!val.ok) throw new ReleaseError("CANDIDATE_VALIDATION_FAILED", `candidate ${id} is not healthy: ${val.problems.join("; ")}`, { problems: val.problems });
  writeJsonAtomic(c.approvedFile, approvedRecord(c, id, { action, from: before?.releaseId ?? null, previousKnownGood: action === "rollback" ? null : undefined }));
  const r = await replaceRunning(c, id, log);
  if (r.ok) { rmSync(c.crashFile, { force: true }); return { ok: true, releaseId: id, previous: before?.releaseId ?? null }; }
  // roll back: the previous approved release becomes approved again and runs again
  for (const n of r.started) await stopPortal(c, n); await stopPortal(c, r.failed);
  if (before) { writeJsonAtomic(c.approvedFile, before); const back = await replaceRunning(c, before.releaseId, log); throw new ReleaseError("ACTIVATION_FAILED_ROLLED_BACK", `${r.failed}: ${r.error}; the previous release ${before.releaseId} is approved and ${back.ok ? "running again" : "COULD NOT be restarted: " + back.error}`, { rolledBack: back.ok }); }
  rmSync(c.approvedFile, { force: true }); throw new ReleaseError("ACTIVATION_FAILED", `${r.failed}: ${r.error}; no previous release to return to`);
}
export function prune(c, log = () => {}) {
  const a = readApproved(c); const keep = new Set([a?.releaseId, a?.previousKnownGood].filter(Boolean)); const removed = [];
  if (!a) return { removed, kept: [...keep], skipped: "no approved release: nothing is pruned" };
  for (const id of listReleases(c)) if (!keep.has(id)) { rmSync(relDir(c, id), { recursive: true, force: true }); removed.push(id); log(`pruned ${id}`); }
  if (a.releaseId && !existsSync(join(relDir(c, a.releaseId), "release.json"))) throw new ReleaseError("APPROVED_BUILD_MISSING", `prune would have removed the approved release ${a.releaseId}`);
  return { removed, kept: [...keep] };
}

// ------------------------------------------------------------------------------------------------------------------------------------------------ public operations
export async function deploy(c, ref, log = () => {}) {
  const sha40 = resolveSource(c, ref);
  return withLock(c, async () => {
    const cand = await buildCandidate(c, sha40, log); const cur = readApproved(c);
    if (cur?.releaseId === cand.id) { const up = await up_(c, log); return { ok: true, releaseId: cand.id, noop: true, ...up }; }
    try { const r = await activate(c, cand.id, { action: "deploy", log }); const p = prune(c, log); return { ...r, pruned: p.removed }; }
    catch (e) { if (!cand.reused && !readApproved(c)?.history?.some((h) => h.releaseId === cand.id)) rmSync(relDir(c, cand.id), { recursive: true, force: true }); throw e; }
  });
}
export async function rollback(c, target, log = () => {}) {
  return withLock(c, async () => {
    const a = readApproved(c); if (!a) throw new ReleaseError("NO_APPROVED_RELEASE", "nothing is approved: nothing to roll back");
    const to = target ?? a.previousKnownGood; if (!to) throw new ReleaseError("NO_PREVIOUS_KNOWN_GOOD", "there is no previous known-good release recorded: give an explicit release id (releases)");
    if (to === a.releaseId) throw new ReleaseError("USAGE", `release ${to} is already the approved one`);
    const r = await activate(c, to, { action: "rollback", log }); return { ...r, prune: prune(c, log) };
  });
}
async function foreignGuard(c) { const bad = NAMES.map((n) => ({ n, r: runningOf(c, n) })).filter((x) => x.r.foreign && !legacyOwned(c, x.n)); if (bad.length) throw new ReleaseError("FOREIGN_PROCESS", bad.map((x) => `port ${x.r.port} (${x.n}) is used by pid ${x.r.foreignPid}, which is not a recorded public portal: left alone`).join("; ")); }
async function up_(c, log) {
  const a = readApproved(c); if (!a) throw new ReleaseError("NO_APPROVED_RELEASE", "no approved public release: run `init --from-running` (pin what runs now) or `deploy <sha>`; nothing is built or started from the working tree");
  const v = verifyRelease(c, a.releaseId, { deep: true, approved: a }); if (!v.ok) throw new ReleaseError("APPROVED_BUILD_MISSING", `${v.problems.join("; ")} - the approved release is NOT rebuilt from HEAD or replaced by another build: restore it or run deploy / rollback explicitly`, { problems: v.problems });
  await foreignGuard(c); const did = [];
  for (const n of NAMES) {
    const r = runningOf(c, n);
    if (r.owned && r.releaseId === a.releaseId && (await waitHealthy(c.ports[n], 3000))) { log(`portal ${n}: already runs the approved release`); continue; }
    if (r.owned) { log(`portal ${n}: runs ${r.releaseId ?? "an unapproved release"} / is unhealthy: replaced by the approved release ${a.releaseId}`); await stopPortal(c, n); }
    await startPortal(c, a.releaseId, n, { port: c.ports[n] }); if (!(await waitHealthy(c.ports[n]))) throw new ReleaseError("START_FAILED", `portal ${n} of the approved release did not become healthy`); did.push(n); log(`portal ${n} :${c.ports[n]} runs ${a.releaseId}`);
  }
  rmSync(c.crashFile, { force: true }); return { releaseId: a.releaseId, started: did };
}
export const up = (c, log = () => {}) => withLock(c, () => up_(c, log));
export const restart = (c, log = () => {}) => withLock(c, async () => { const a = readApproved(c); if (!a) throw new ReleaseError("NO_APPROVED_RELEASE", "no approved public release: nothing to restart"); const v = verifyRelease(c, a.releaseId, { deep: true, approved: a }); if (!v.ok) throw new ReleaseError("APPROVED_BUILD_MISSING", v.problems.join("; ")); await foreignGuard(c); for (const n of NAMES) await stopPortal(c, n); return up_(c, log); });
export const down = (c, log = () => {}) => withLock(c, async () => { const out = {}; for (const n of NAMES) { const r = runningOf(c, n); if (r.foreign) { out[n] = "FOREIGN_PROCESS: left alone"; continue; } out[n] = (await stopPortal(c, n)).state ?? "STOPPED"; log(`portal ${n}: ${out[n]}`); } return out; });

// ------------------------------------------------------------------------------------------------------------------------------------------------ status
export function statusOf(c) {
  const approved = readApproved(c); const head = git(c.root, ["rev-parse", "HEAD"]).stdout.trim() || null; const crash = readJson(c.crashFile);
  const rel = approved ? readRelease(c, approved.releaseId) : null; const ver = approved ? verifyRelease(c, approved.releaseId, { deep: false, approved }) : null;
  let behind = null; if (rel && head && rel.sourceSha !== head) { const r = git(c.root, ["rev-list", "--count", `${rel.sourceSha}..${head}`]); behind = r.status === 0 ? Number(r.stdout.trim()) : null; }
  const rows = NAMES.map((n) => {
    const r = runningOf(c, n); const run = r.releaseId ? readRelease(c, r.releaseId) : null; const ap = rel?.portals?.[n];
    let state;
    if (r.foreign && legacyOwned(c, n)) state = approved ? "RUNNING_UNAPPROVED" : "RUNNING_UNAPPROVED";
    else if (r.foreign) state = "FOREIGN_PROCESS";
    else if (!approved) state = r.listener ? "RUNNING_UNAPPROVED" : "NO_APPROVED_RELEASE";
    else if (!ver.ok) state = "APPROVED_BUILD_MISSING";
    else if (!r.listener) state = crash ? "CRASH_LOOP" : "STOPPED";
    else if (r.releaseId !== approved.releaseId) state = "RUNNING_UNAPPROVED";
    else state = "CURRENT_APPROVED";
    return { portal: n, port: c.ports[n], pid: r.listener, health: null, runningSource: run?.sourceShort ?? (r.listener ? "unknown" : "-"), approvedSource: rel?.sourceShort ?? "-", integrationSource: head?.slice(0, 12) ?? "-",
      buildFingerprint: ap?.buildFingerprint ?? "-", configFingerprint: rel?.configFingerprint ?? "-", state, behindIntegration: behind, releaseId: r.releaseId, approvedRelease: approved?.releaseId ?? null };
  });
  return { approved, rel, head, behind, crash, verify: ver, rows };
}
export async function statusWithHealth(c) { const s = statusOf(c); for (const r of s.rows) { if (!r.pid) { r.health = "-"; continue; } const h = await httpGet(`http://127.0.0.1:${r.port}/login`, 3000); r.health = h.status > 0 && h.status < 500 ? `HTTP ${h.status}` : "DOWN"; if (r.state === "CURRENT_APPROVED" && r.health === "DOWN") r.state = "UNHEALTHY"; } return s; }
export function formatStatus(s) {
  const head = ["PORTAL", "PORT", "PID", "HEALTH", "RUNNING", "APPROVED", "INTEGRATION", "BUILD", "CONFIG", "STATE"]; const rows = s.rows.map((r) => [r.portal, r.port, r.pid ?? "-", r.health ?? "-", r.runningSource, r.approvedSource, r.integrationSource, r.buildFingerprint, r.configFingerprint, r.state + (r.state === "CURRENT_APPROVED" && s.behind ? ` (BEHIND_INTEGRATION +${s.behind})` : "")]);
  const w = head.map((h, i) => Math.max(h.length, ...rows.map((r) => String(r[i]).length))); const line = (r) => r.map((x, i) => String(x).padEnd(w[i])).join("  ");
  const extra = [`approved release: ${s.approved?.releaseId ?? "NONE"}${s.approved?.previousKnownGood ? `   previous known-good: ${s.approved.previousKnownGood}` : ""}`, ...(s.verify && !s.verify.ok ? s.verify.problems.map((p) => `! ${p}`) : []), ...(s.crash ? [`! crash loop since ${s.crash.since}: automatic restarts paused (${s.crash.restarts} in ${s.crash.windowSec}s)`] : [])];
  return [line(head), ...rows.map(line), ...extra].join("\n");
}
export const okStatus = (s) => s.rows.every((r) => r.state === "CURRENT_APPROVED");

// ------------------------------------------------------------------------------------------------------------------------------------------------ adopt what runs now (evidence based)
/**
 * initFromRunning(c): pin the release the public portals are running RIGHT NOW. Nothing is restarted, nothing is rebuilt.
 * Evidence required for every portal (any gap => refused): the process is the one the lifecycle recorded (pid file, listener, start time), its cwd is apps/<p> of this checkout, the dist it runs exists
 * with its BUILD_ID, the assets its login page links all exist in that dist, it was built from a CLEAN tree of one commit, and the fingerprint of that commit's snapshot equals the recorded build fingerprint.
 */
export async function initFromRunning(c, log = () => {}, { dryRun = false } = {}) {
  return withLock(c, async () => {
    if (readApproved(c)) throw new ReleaseError("USAGE", "an approved release already exists: use deploy / rollback to change it");
    const buildEnv = buildEnvOf(c), runtimeEnv = runtimeEnvOf(c); const per = {}; const problems = []; const get = (n, e) => { try { return readFileSync(join(c.run, `portal-${n}.${e}`), "utf8").trim(); } catch { return ""; } };
    for (const n of NAMES) {
      const port = c.ports[n]; const pids = listenerPids(port); const pid = pids[0]; const e = { portal: n, port };
      if (!pid) { problems.push(`${n}: nothing listens on ${port}: cannot pin a release that is not running`); continue; }
      if (get(n, "pid") !== String(pid)) { problems.push(`${n}: the listener ${pid} is not the pid the lifecycle recorded (${get(n, "pid") || "none"})`); continue; }
      if (get(n, "pidstart") && !sameStart(get(n, "pidstart"), startTimeOf(pid))) { problems.push(`${n}: the recorded start time does not match pid ${pid} (pid reused?)`); continue; }
      const cwd = cwdOf(pid); if (cwd !== join(c.root, "apps", n)) { problems.push(`${n}: cwd ${cwd} is not apps/${n} of this checkout`); continue; }
      const distName = get(n, "rundist"); const dist = join(c.root, "apps", n, distName); if (!distName || !existsSync(join(dist, "BUILD_ID"))) { problems.push(`${n}: dist '${distName}' not found or without BUILD_ID`); continue; }
      const meta = get(n, "runmeta").split(" "); if (!/^[0-9a-f]{7,40}$/.test(meta[0] ?? "")) { problems.push(`${n}: no commit recorded for the running process`); continue; } if (meta[1] !== "0") { problems.push(`${n}: it was started from a DIRTY tree (${meta[0]}+dirty): not reproducible, not pinned`); continue; }
      const login = await httpGet(`http://127.0.0.1:${port}/login`); const assets = [...new Set(login.text.match(/\/_next\/static\/[^"'\s\\]+/g) ?? [])];
      const missing = assets.filter((a) => !existsSync(join(dist, "static", a.replace(/^\/_next\/static\//, "")))); if (login.status !== 200 || !assets.length || missing.length) { problems.push(`${n}: served assets are not all in ${distName} (${assets.length} linked, ${missing.length} missing)`); continue; }
      Object.assign(e, { pid, distName, dist, commit: meta[0], recordedFp: get(n, "runbfp"), buildId: readFileSync(join(dist, "BUILD_ID"), "utf8").trim(), assets: assets.length, launcher: Number(get(n, "launcher")) || null }); per[n] = e;
    }
    if (problems.length) throw new ReleaseError("ADOPT_REFUSED", `the running public portals cannot be pinned: ${problems.join("; ")}`, { problems });
    const commits = new Set(NAMES.map((n) => per[n].commit)); if (commits.size !== 1) throw new ReleaseError("ADOPT_REFUSED", `the portals run different commits (${[...commits].join(", ")})`);
    const sha40 = resolveSource(c, [...commits][0]); const nodeMajor = Number(process.versions.node.split(".")[0]); const lockOf = (() => { const r = git(c.root, ["show", `${sha40}:package-lock.json`], { maxBuffer: 64 * 1024 * 1024 }); return r.status === 0 ? sha(r.stdout).slice(0, 16) : "no-lock"; })();
    const cfg = configFingerprint({ buildEnv, runtimeEnv, nodeMajor, lockfileSha: lockOf }); const id = `${sha40.slice(0, 12)}-${cfg.slice(0, 8)}`; const dir = dryRun ? mkdtempSync(join(tmpdir(), "pin-dry-")) : relDir(c, id); rmSync(dir, { recursive: true, force: true });
    try {
      await extractSnapshot(c.root, sha40, dir); const portals = {};
      for (const n of NAMES) {
        const fp = fingerprintOf(dir, n, buildEnv);
        if (fp !== per[n].recordedFp) {
          const list = (root) => new Set(spawnSync(process.execPath, [FP_TOOL, "--root", root, "--app", n, "--list"], { encoding: "utf8" }).stdout.split("\n").filter(Boolean)); const a = list(c.root), b = list(dir);
          const only = [...[...a].filter((x) => !b.has(x)).map((x) => `+tree:${x}`), ...[...b].filter((x) => !a.has(x)).map((x) => `+snapshot:${x}`)].slice(0, 6);
          throw new ReleaseError("ADOPT_REFUSED", `${n}: the snapshot of ${sha40.slice(0, 12)} has build fingerprint ${fp}, the running build recorded ${per[n].recordedFp}: source or build environment is not the one that was built${only.length ? ` (files that differ between the working tree and the snapshot: ${only.join(", ")})` : " (same file list: a file content or the build environment differs)"}`);
        }
        if (dryRun) { portals[n] = { fingerprintReproduced: true, fp }; continue; }
        const dst = join(dir, "apps", n, per[n].distName); mkdirSync(dirname(dst), { recursive: true }); cpSync(per[n].dist, dst, { recursive: true, filter: (s) => !/[\\/]cache([\\/]|$)/.test(relative(per[n].dist, s)) });
        const d0 = distDigest(per[n].dist), d1 = distDigest(dst); if (d0 !== d1) throw new ReleaseError("ADOPT_REFUSED", `${n}: the copy of the dist differs from the running one`);
        portals[n] = { distDir: per[n].distName, buildId: per[n].buildId, buildFingerprint: fp, distDigest: d1 };
      }
      if (dryRun) { rmSync(dir, { recursive: true, force: true }); return { dryRun: true, releaseId: id, sourceSha: sha40, evidence: NAMES.map((n) => ({ portal: n, pid: per[n].pid, dist: per[n].distName, buildId: per[n].buildId, commit: per[n].commit, servedAssetsInDist: per[n].assets, recordedFingerprint: per[n].recordedFp, snapshotFingerprint: portals[n].fp })) }; }
      const prevDeps = c.deps; installDeps({ ...c, deps: prevDeps === "skip" ? "skip" : "clone" }, dir);
      const record = { schema: 1, id, kind: "adopted", sourceSha: sha40, sourceShort: sha40.slice(0, 12), createdAt: new Date().toISOString(), nodeMajor, lockfileSha: lockOf, buildEnv, runtimeEnv, configFingerprint: cfg, portals,
        adoption: { at: new Date().toISOString(), evidence: NAMES.map((n) => ({ portal: n, pid: per[n].pid, dist: per[n].distName, buildId: per[n].buildId, commitCleanTree: true, servedAssetsInDist: per[n].assets, fingerprintReproduced: true })), runtimeEnvVerified: false, note: "runtime env values are the current derivation from public.env (the process environment is not readable); the build was reproduced from the commit snapshot" } };
      writeJsonAtomic(join(dir, "release.json"), record);
    } catch (e) { rmSync(dir, { recursive: true, force: true }); throw e; }
    for (const n of NAMES) {   // convert the legacy lifecycle ownership (pid file + start time, verified above) into helper metadata WITHOUT touching the process
      const pid = per[n].pid; const members = [{ pid, startTime: startTimeOf(pid), command: commandOf(pid) }]; if (per[n].launcher && per[n].launcher !== pid && exists(per[n].launcher) && /next/.test(commandOf(per[n].launcher))) members.push({ pid: per[n].launcher, startTime: startTimeOf(per[n].launcher), command: commandOf(per[n].launcher) });
      writeMeta(stateFileOf(c, n), { schema: 1, owner: "public", name: n, mode: "public", port: c.ports[n], pid, pgid: null, startTime: members[0].startTime, command: members[0].command, cwd: cwdOf(pid), listenerPid: pid, members, startedBy: "adopted", recordedAt: new Date().toISOString(), extra: { releaseId: id, buildId: per[n].buildId, adoptedFromLegacy: true } });
    }
    writeJsonAtomic(c.approvedFile, approvedRecord(c, id, { action: "init", from: null, previousKnownGood: null })); log(`pinned ${id} (source ${sha40.slice(0, 12)})`); return { releaseId: id, sourceSha: sha40 };
  });
}
