// Build runner (ADR 0010). A separate host process: generated code NEVER runs here or in the API — only inside throw-away Docker
// containers: non-root, read-only root filesystem, all capabilities dropped, no-new-privileges, CPU/memory/pid limits, no host
// mounts, network "build" (internal: package mirror only) for install and network "none" for build. The runner holds no Git
// credential (the API streams the source) and no platform secret except its own runner token.
//
// Env: RUNNER_API (http://127.0.0.1:8080), BUILD_RUNNER_TOKEN, BUILD_NETWORK (hbl_build), BUILD_IMAGE (node:22-alpine by digest),
//      RUNNER_NAME, RUNNER_POLL_MS (3000), OSV_URL (https://api.osv.dev)
import { spawn } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync, readFileSync, readdirSync, existsSync } from "node:fs";
import { tmpdir, hostname } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import { verdaccioConfig } from "./verdaccio-config.mjs";

const API = process.env.RUNNER_API ?? "http://127.0.0.1:8080";
const TOKEN = process.env.BUILD_RUNNER_TOKEN ?? "";
const NETWORK = process.env.BUILD_NETWORK ?? "hbl_build";
const NAME = process.env.RUNNER_NAME ?? `runner-${hostname()}`;
const POLL = Number(process.env.RUNNER_POLL_MS ?? 3000);
const OSV = process.env.OSV_URL ?? "https://api.osv.dev";
if (!TOKEN) { console.error("BUILD_RUNNER_TOKEN is required"); process.exit(1); }
const MIRROR_CONFIG = process.env.MIRROR_CONFIG ?? join(dirname(fileURLToPath(import.meta.url)), "../../infra/verdaccio/config.yaml");
const MIRROR_CONTAINER = process.env.MIRROR_CONTAINER ?? "hbl-verdaccio-1";
let mirrorHash = "";

const headers = { "X-Runner-Token": TOKEN };
const log = (...a) => console.log(new Date().toISOString(), ...a);

/** Runs a command; resolves {code, out}; kills the named container on timeout. Output is captured (bounded) for the build log. */
function run(cmd, args, { timeoutMs = 600_000, input, container, maxOut = 2_000_000, binary = false } = {}) {
  return new Promise((resolve) => {
    const p = spawn(cmd, args, { stdio: ["pipe", "pipe", "pipe"] });
    const chunks = []; let size = 0; let err = ""; let timedOut = false;
    p.stdout.on("data", (d) => { size += d.length; if (size <= maxOut) chunks.push(d); });
    p.stderr.on("data", (d) => { if (err.length < 200_000) err += d.toString(); });
    const t = setTimeout(() => { timedOut = true; if (container) spawn("docker", ["kill", container]); p.kill("SIGKILL"); }, timeoutMs);
    p.on("close", (code) => { clearTimeout(t); const buf = Buffer.concat(chunks); resolve({ code: timedOut ? 124 : code, timedOut, out: binary ? buf : buf.toString(), err, tooBig: size > maxOut }); });
    if (input) p.stdin.end(input); else p.stdin.end();
  });
}

const HARDEN = (limits) => ["--read-only", "--tmpfs", "/tmp:rw,exec,size=512m", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
  "--pids-limit", String(limits.pids ?? 512), "--memory", String(limits.memory ?? "2g"), "--cpus", String(limits.cpus ?? 2), "--user", "1000:1000", "-e", "HOME=/tmp"];
const redact = (s) => s.replace(/(sk-[A-Za-z0-9_-]{10})[A-Za-z0-9_-]+/g, "$1…").replace(/(token[=:]\s*)\S+/gi, "$1…");

/** Dependency scan: the lockfile's name@version list against the OSV database (a query from the runner, nothing is executed). */
async function osvScan(lock) {
  const pkgs = Object.entries(lock.packages ?? {}).filter(([k, v]) => k && v.version).map(([k, v]) => ({ name: k.slice(k.lastIndexOf("node_modules/") + 13), version: v.version }));
  const r = await fetch(`${OSV}/v1/querybatch`, { method: "POST", headers: { "Content-Type": "application/json" }, signal: AbortSignal.timeout(30_000),
    body: JSON.stringify({ queries: pkgs.map((p) => ({ package: { name: p.name, ecosystem: "npm" }, version: p.version })) }) });
  if (!r.ok) throw new Error(`OSV answered HTTP ${r.status}`);
  const results = (await r.json()).results ?? [];
  const findings = [];
  for (let i = 0; i < results.length; i++) for (const v of results[i].vulns ?? []) {
    let severity = "UNKNOWN";
    try { const d = await (await fetch(`${OSV}/v1/vulns/${v.id}`, { signal: AbortSignal.timeout(15_000) })).json(); severity = (d.database_specific?.severity ?? "UNKNOWN").toUpperCase(); } catch { /* keep UNKNOWN */ }
    findings.push({ package: `${pkgs[i].name}@${pkgs[i].version}`, id: v.id, severity });
  }
  return { tool: "OSV (osv.dev) on package-lock.json", packages: pkgs.length, findings, blocking: findings.filter((f) => f.severity === "CRITICAL" || f.severity === "HIGH") };
}

async function gitleaks(dir) {
  const report = join(dir, "..", `gitleaks-${Date.now()}.json`);
  const r = await run("gitleaks", ["dir", dir, "--no-banner", "--redact", "--exit-code", "3", "--report-format", "json", "--report-path", report], { timeoutMs: 120_000 });
  let findings = [];
  try { findings = JSON.parse(readFileSync(report, "utf8")).map((f) => ({ file: f.File?.replace(dir, "") , rule: f.RuleID, line: f.StartLine })); } catch { /* no report */ }
  rmSync(report, { force: true });
  if (r.code !== 0 && r.code !== 3) throw new Error(`gitleaks failed (${r.code})`);
  return { tool: "gitleaks " + "dir", findings };
}

async function api(method, path, body, type = "application/json") {
  return fetch(`${API}${path}`, { method, headers: { ...headers, ...(body ? { "Content-Type": type } : {}) }, body, signal: AbortSignal.timeout(120_000) });
}

/** Keep the mirror's allowlist equal to the API's (approved catalog). The runner is the build-plane operator, so it may restart the mirror. */
async function syncMirror(job) {
  if (!job.allowlistHash || job.allowlistHash === mirrorHash) return;
  const want = verdaccioConfig(job.allowlist ?? []);
  let have = ""; try { have = readFileSync(MIRROR_CONFIG, "utf8"); } catch { /* first run */ }
  if (createHash("sha256").update(have).digest("hex") !== createHash("sha256").update(want).digest("hex")) {
    writeFileSync(MIRROR_CONFIG, want); log(`mirror allowlist updated (${job.allowlist.length} names); restarting ${MIRROR_CONTAINER}`);
    await run("docker", ["restart", MIRROR_CONTAINER], { timeoutMs: 60_000 });
    for (let i = 0; i < 30; i++) { const p = await run("docker", ["exec", MIRROR_CONTAINER, "wget", "-qO-", "http://127.0.0.1:4873/-/ping"], { timeoutMs: 5000 }); if (p.code === 0) break; await new Promise((s) => setTimeout(s, 1000)); }
  }
  mirrorHash = job.allowlistHash;
}

const lockNames = (lock) => Object.keys(lock.packages ?? {}).filter(Boolean).map((k) => k.slice(k.lastIndexOf("node_modules/") + 13));
const SPLIT = "__FACTORY_SPLIT__";

/** LOCK: lockfile for an approved dependency, made by npm inside the sandbox (mirror only, scripts off). Output is validated by the API. */
async function processLock(job) {
  const t0 = Date.now(); const L = []; let stage = "SOURCE";
  const done = (status, error, result) => api("POST", `/internal/build-jobs/${job.id}/finish`, JSON.stringify({ status, stage, error, result, log: redact(L.join("\n")).slice(-30_000), durationMs: Date.now() - t0 }));
  const tmp = mkdtempSync(join(tmpdir(), `factory-lock-`)); const vol = `factory-lock-${job.id.slice(0, 8)}`;
  try {
    const src = await fetch(job.sourceUrl, { headers, signal: AbortSignal.timeout(120_000) }); if (!src.ok) throw new Error(`source HTTP ${src.status}`);
    writeFileSync(join(tmp, "src.tgz"), Buffer.from(await src.arrayBuffer())); await run("tar", ["-xzf", join(tmp, "src.tgz"), "-C", tmp]);
    const dir = join(tmp, readdirSync(tmp).find((n) => n !== "src.tgz"));
    await run("docker", ["volume", "create", vol]);
    const c = (await run("docker", ["create", "--network", "none", "-v", `${vol}:/work`, job.image, "true"])).out.trim();
    for (const f of ["package.json", "package-lock.json"]) await run("docker", ["cp", join(dir, f), `${c}:/work/${f}`]);
    await run("docker", ["rm", c]);
    await run("docker", ["run", "--rm", "--network", "none", "--cap-drop", "ALL", "--cap-add", "CHOWN", "-v", `${vol}:/work`, job.image, "chown", "-R", "1000:1000", "/work"]);
    stage = "LOCK";
    const spec = `${job.input.name}@${job.input.spec}`;
    const r = await run("docker", ["run", "--rm", "--name", `${vol}-l`, "--network", NETWORK, ...HARDEN(job.limits), "-v", `${vol}:/work`, "-w", "/work", "-e", "npm_config_cache=/tmp/npm",
      job.image, "sh", "-c", `npm install '${spec.replace(/'/g, "")}' ${job.input.exact ? "--save-exact" : ""} --package-lock-only --ignore-scripts --no-audit --no-fund --replace-registry-host=always --registry http://verdaccio:4873/ >&2 && cat package.json && echo ${SPLIT} && cat package-lock.json`],
      { timeoutMs: 180_000, container: `${vol}-l` });
    L.push(r.err.slice(-8000));
    if (r.code !== 0) throw new Error(r.timedOut ? "lock timed out" : "npm could not resolve the package from the approved mirror");
    const [packageJson, packageLock] = r.out.split(SPLIT).map((s) => s.trim());
    JSON.parse(packageJson); JSON.parse(packageLock);
    stage = "DONE"; await done("SUCCEEDED", null, { packageJson: packageJson + "\n", packageLock: packageLock + "\n" });
  } catch (e) { L.push(`ERROR: ${e.message}`); await done("FAILED", `${stage}: ${e.message}`); }
  finally { await run("docker", ["rm", "-f", `${vol}-l`]); await run("docker", ["volume", "rm", "-f", vol]); rmSync(tmp, { recursive: true, force: true }); }
}

/**
 * RESOLVE (admin approval of a package): dependency closure from the public registry. No user code is present and install scripts are
 * off — npm only reads metadata — so this one step may use the default bridge network. Then the closure is scanned with OSV.
 */
async function processResolve(job) {
  const t0 = Date.now(); const L = []; let stage = "RESOLVE";
  const done = (status, error, result) => api("POST", `/internal/build-jobs/${job.id}/finish`, JSON.stringify({ status, stage, error, result, log: redact(L.join("\n")).slice(-30_000), durationMs: Date.now() - t0 }));
  try {
    const spec = `${job.input.name}@${job.input.spec}`.replace(/'/g, "");
    const r = await run("docker", ["run", "--rm", "--name", `factory-resolve-${job.id.slice(0, 8)}`, "--network", "bridge", ...HARDEN(job.limits), "-e", "npm_config_cache=/tmp/npm",
      job.image, "sh", "-c", `mkdir -p /tmp/r && cd /tmp/r && echo '{"name":"resolve","private":true}' > package.json && npm install '${spec}' --package-lock-only --ignore-scripts --no-audit --no-fund --registry https://registry.npmjs.org/ >&2 && cat package-lock.json`],
      { timeoutMs: 180_000, container: `factory-resolve-${job.id.slice(0, 8)}` });
    L.push(r.err.slice(-8000));
    if (r.code !== 0) throw new Error(r.timedOut ? "resolve timed out" : "package not found or not resolvable");
    const lock = JSON.parse(r.out);
    stage = "SCAN"; const scan = await osvScan(lock);
    const packages = Object.entries(lock.packages ?? {}).filter(([k]) => k).map(([k, v]) => ({ name: k.slice(k.lastIndexOf("node_modules/") + 13), version: v.version }));
    L.push(`${packages.length} packages, ${scan.findings.length} advisories (${scan.blocking.length} high/critical)`);
    stage = "DONE"; await done("SUCCEEDED", null, { packages, findings: scan.findings, blocking: scan.blocking });
  } catch (e) { L.push(`ERROR: ${e.message}`); await done("FAILED", `${stage}: ${e.message}`); }
}

async function processJob(job) {
  const id8 = job.id.slice(0, 8), vol = `factory-job-${id8}`, tmp = mkdtempSync(join(tmpdir(), `factory-${id8}-`));
  const L = []; const step = (s) => { L.push(`\n== ${s}`); log(job.id, s); };
  const scans = {}; let stage = "SOURCE"; const t0 = Date.now(); let cpuMs = 0; let sourceBytes = 0;
  // CPU actually used by a sandbox step: the container's own cgroup counter (cgroup v2 cpu.stat usage_usec), printed as the last line
  const CPU = "; s=$?; awk '/usage_usec/{print \"__CPU__\" $2}' /sys/fs/cgroup/cpu.stat; exit $s";
  const takeCpu = (r) => { const m = r.out.match(/__CPU__(\d+)/); if (m) cpuMs += Math.round(Number(m[1]) / 1000); r.out = r.out.replace(/__CPU__\d+\n?/, ""); return r; };
  const finish = async (status, error) => {
    const r = await api("POST", `/internal/build-jobs/${job.id}/finish`, JSON.stringify({ status, stage, error, log: redact(L.join("\n")).slice(-60_000), scans,
      durationMs: Date.now() - t0, cpuMs, sourceBytes }));
    log(job.id, status, error ?? "", `(finish ${r.status})`);
  };
  try {
    step(`source ${job.commitSha.slice(0, 12)}`);
    const src = await fetch(job.sourceUrl, { headers, signal: AbortSignal.timeout(120_000) });
    if (!src.ok) throw new Error(`source download HTTP ${src.status}`);
    const srcBuf = Buffer.from(await src.arrayBuffer()); sourceBytes = srcBuf.length; writeFileSync(join(tmp, "src.tgz"), srcBuf);
    const x = await run("tar", ["-xzf", join(tmp, "src.tgz"), "-C", tmp], { timeoutMs: 60_000 });
    if (x.code !== 0) throw new Error("source archive could not be extracted");
    const top = readdirSync(tmp).find((n) => n !== "src.tgz"); const srcDir = join(tmp, top);
    if (!existsSync(join(srcDir, "package-lock.json"))) throw new Error("package-lock.json missing");

    stage = "SCAN_SOURCE"; step("secret scan (source)");
    scans.sourceSecrets = await gitleaks(srcDir);
    if (scans.sourceSecrets.findings.length) throw new Error(`secret scan: ${scans.sourceSecrets.findings.length} finding(s) in source`);
    step("dependency scan (OSV)");
    const lock = JSON.parse(readFileSync(join(srcDir, "package-lock.json"), "utf8"));
    const allowed = new Set(job.allowlist ?? []);
    const outside = lockNames(lock).filter((n) => !allowed.has(n) && !n.startsWith("@company/"));
    if (allowed.size && outside.length) throw new Error(`lockfile contains packages outside the approved catalog: ${outside.slice(0, 8).join(", ")}`);
    scans.dependencies = await osvScan(lock);
    scans.sbom = Object.entries(lock.packages ?? {}).filter(([k]) => k).map(([k, v]) => ({ name: k.slice(k.lastIndexOf("node_modules/") + 13), version: v.version, integrity: v.integrity }));
    L.push(`${scans.dependencies.packages} packages, ${scans.dependencies.findings.length} advisories (${scans.dependencies.blocking.length} high/critical)`);
    if (scans.dependencies.blocking.length) throw new Error(`dependency scan: ${scans.dependencies.blocking.map((f) => `${f.package} ${f.id}`).join(", ")}`);

    stage = "PREPARE"; step("workspace");
    await run("docker", ["volume", "create", vol]);
    const c = (await run("docker", ["create", "--network", "none", "-v", `${vol}:/work`, job.image, "true"])).out.trim();
    const cp = await run("docker", ["cp", `${srcDir}/.`, `${c}:/work`]); await run("docker", ["rm", c]);
    if (cp.code !== 0) throw new Error("copy into the sandbox failed");
    await run("docker", ["run", "--rm", "--network", "none", "--read-only", "--cap-drop", "ALL", "--cap-add", "CHOWN", "--cap-add", "FOWNER", "--cap-add", "DAC_OVERRIDE",
      "-v", `${vol}:/work`, job.image, "chown", "-R", "1000:1000", "/work"]);

    stage = "INSTALL"; step("install (mirror only, scripts disabled)");
    const inst = await run("docker", ["run", "--rm", "--name", `${vol}-i`, "--network", NETWORK, ...HARDEN(job.limits), "-v", `${vol}:/work`, "-w", "/work",
      "-e", "npm_config_cache=/tmp/npm", job.image, "sh", "-c", "npm ci --ignore-scripts --no-audit --no-fund --replace-registry-host=always --registry http://verdaccio:4873/" + CPU],
      { timeoutMs: (job.limits.installSeconds ?? 300) * 1000, container: `${vol}-i` }); takeCpu(inst);
    L.push(inst.out.slice(-8000), inst.err.slice(-8000));
    if (inst.code !== 0) throw new Error(inst.timedOut ? "install timed out" : `install failed (exit ${inst.code}) — packages outside the approved list are refused`);

    stage = "BUILD"; step("typecheck + build (no network)");
    const b = await run("docker", ["run", "--rm", "--name", `${vol}-b`, "--network", "none", ...HARDEN(job.limits), "-v", `${vol}:/work`, "-w", "/work",
      job.image, "sh", "-c", "(npm run -s typecheck && npm run -s build)" + CPU], { timeoutMs: (job.limits.buildSeconds ?? 600) * 1000, container: `${vol}-b` }); takeCpu(b);
    L.push(b.out.slice(-12000), b.err.slice(-12000));
    if (b.code !== 0) throw new Error(b.timedOut ? "build timed out" : `build failed (exit ${b.code})`);

    stage = "PACKAGE"; step("collect output");
    const t = await run("docker", ["run", "--rm", "--network", "none", ...HARDEN(job.limits), "-v", `${vol}:/work:ro`, job.image, "tar", "-C", "/work/dist", "-czf", "-", "."],
      { timeoutMs: 120_000, binary: true, maxOut: (job.limits.outputMiB ?? 100) * 1024 * 1024 });
    if (t.code !== 0 || t.tooBig) throw new Error(t.tooBig ? "output too large" : "no build output (dist)");
    const outDir = join(tmp, "dist"); writeFileSync(join(tmp, "dist.tgz"), t.out);
    await run("mkdir", ["-p", outDir]); await run("tar", ["-xzf", join(tmp, "dist.tgz"), "-C", outDir]);
    stage = "SCAN_OUTPUT"; step("secret scan (output)");
    scans.outputSecrets = await gitleaks(outDir);
    if (scans.outputSecrets.findings.length) throw new Error(`secret scan: ${scans.outputSecrets.findings.length} finding(s) in build output`);

    stage = "UPLOAD"; step("upload artifact");
    const up = await api("PUT", `/internal/build-jobs/${job.id}/artifact`, t.out, "application/gzip");
    if (!up.ok) throw new Error(`artifact rejected: ${(await up.text()).slice(0, 300)}`);
    stage = "DONE";
    await finish("SUCCEEDED");
  } catch (e) {
    L.push(`ERROR: ${e.message}`);
    await finish("FAILED", `${stage}: ${e.message}`);
  } finally {
    for (const s of ["i", "b"]) await run("docker", ["rm", "-f", `${vol}-${s}`], { timeoutMs: 30_000 });
    await run("docker", ["volume", "rm", "-f", vol], { timeoutMs: 30_000 });
    rmSync(tmp, { recursive: true, force: true });
  }
}

// ---------------------------------------------------------------- server app runtime (stage J, ADR 0017)
// Desired state comes from the API; the runner makes Docker match it. App containers: image built from the server bundle with no network,
// non-root, read-only rootfs, all capabilities dropped, no-new-privileges, CPU/memory/pid limits, network "apps" (internal: only the apps DB
// and the apps gateway), env from a 0600 temp file (never on the command line), a health check; optional OCI runtime (e.g. gVisor runsc).
const RUNTIME_IMAGE = process.env.RUNTIME_IMAGE ?? "node@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402";
const RUNTIME_OCI = process.env.RUNTIME_OCI ?? "";            // e.g. "runsc" on a Linux host with gVisor installed
// every app gets its OWN internal network holding only itself, the apps DB server and the apps gateway (apps cannot reach each other)
const APPDB_CONTAINER = process.env.RUNTIME_APPDB_CONTAINER ?? "hbl-appdb-1";
const GATEWAY_CONTAINER = process.env.RUNTIME_GATEWAY_CONTAINER ?? "hbl-apps-gateway-1";
const appNetwork = (container) => `factory-net-${container.split("-")[1]}`;   // app-<project8>-<deployment8> -> one network per project
async function ensureAppNetwork(net) {
  if ((await run("docker", ["network", "inspect", net], { timeoutMs: 15_000 })).code !== 0) {
    const c = await run("docker", ["network", "create", "--internal", "--label", "factory.app=1", net], { timeoutMs: 30_000 });
    if (c.code !== 0 && !c.err.includes("already exists")) throw new Error(`network create failed: ${c.err.slice(-200)}`);
  }
  for (const [ctr, alias] of [[APPDB_CONTAINER, "appdb"], [GATEWAY_CONTAINER, "apps-gateway"]]) {
    const on = await run("docker", ["inspect", "-f", `{{if index .NetworkSettings.Networks "${net}"}}yes{{end}}`, ctr], { timeoutMs: 15_000 });
    if (on.out.trim() !== "yes") {
      const r = await run("docker", ["network", "connect", "--alias", alias, net, ctr], { timeoutMs: 30_000 });
      if (r.code !== 0 && !r.err.includes("already exists")) throw new Error(`cannot attach ${alias} to ${net}: ${r.err.slice(-200)}`);
    }
  }
}
const starting = new Set(); let lastLogs = 0;
async function report(id, state, error, logs) {
  const r = await api("POST", `/internal/runtime/${id}/report`, JSON.stringify({ state, error, logs })); if (!r.ok) log("runtime report", id, r.status);
}
async function inspect(name) {
  const r = await run("docker", ["inspect", "-f", "{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}", name], { timeoutMs: 15_000 });
  return r.code === 0 ? r.out.trim() : null;
}
const logsOf = async (name) => { const r = await run("docker", ["logs", "--tail", "200", name], { timeoutMs: 15_000 }); return (r.out + r.err).slice(-20_000); };
async function startContainer(c) {
  const tmp = mkdtempSync(join(tmpdir(), "factory-app-"));
  try {
    await report(c.deploymentId, "STARTING");
    const res = await fetch(c.artifactUrl, { headers, signal: AbortSignal.timeout(60_000) });
    if (!res.ok) throw new Error(`bundle download HTTP ${res.status}`);
    const ctx = join(tmp, "ctx"), app = join(ctx, "app"); await run("mkdir", ["-p", app]);
    writeFileSync(join(tmp, "b.tgz"), Buffer.from(await res.arrayBuffer()));
    if ((await run("tar", ["-xzf", join(tmp, "b.tgz"), "-C", app], { timeoutMs: 60_000 })).code !== 0) throw new Error("bundle could not be extracted");
    if (!existsSync(join(app, "server.cjs"))) throw new Error("server.cjs missing in the bundle");
    writeFileSync(join(ctx, "Dockerfile"), `FROM ${RUNTIME_IMAGE}\nWORKDIR /app\nCOPY app/ /app/\nUSER 10001:10001\nEXPOSE 8080\nCMD ["node", "/app/server.cjs"]\n`);
    const tag = `factory-app:${c.deploymentId.slice(0, 12)}`;
    const tar = await run("tar", ["-C", ctx, "-cf", "-", "."], { binary: true, timeoutMs: 60_000 });
    const b = await run("docker", ["build", "--network", "none", "-q", "-t", tag, "-"], { input: tar.out, timeoutMs: 300_000 });
    if (b.code !== 0) throw new Error(`image build failed: ${b.err.slice(-300)}`);
    const envFile = join(tmp, "env"); writeFileSync(envFile, Object.entries(c.env).map(([k, v]) => `${k}=${String(v).replace(/\n/g, " ")}`).join("\n") + "\n", { mode: 0o600 });
    await run("docker", ["rm", "-f", c.container], { timeoutMs: 30_000 });
    const net = appNetwork(c.container); await ensureAppNetwork(net);
    const r = await run("docker", ["run", "-d", "--name", c.container, "--label", "factory.app=1", "--label", `factory.deployment=${c.deploymentId}`,
      "--network", net, ...(RUNTIME_OCI ? ["--runtime", RUNTIME_OCI] : []),
      "--read-only", "--tmpfs", "/tmp:rw,noexec,nosuid,size=32m", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
      "--pids-limit", String(c.limits.pids ?? 128), "--memory", String(c.limits.memory ?? "256m"), "--cpus", String(c.limits.cpus ?? 0.5),
      "--user", "10001:10001", "--restart", "on-failure:5", "--env-file", envFile,
      "--health-cmd", "wget -qO- http://127.0.0.1:8080/health >/dev/null || exit 1", "--health-interval", "3s", "--health-retries", "3", "--health-start-period", "3s", tag],
      { timeoutMs: 60_000 });
    rmSync(envFile, { force: true });
    if (r.code !== 0) throw new Error(`container did not start: ${r.err.slice(-300)}`);
    for (let i = 0; i < 40; i++) {
      const st = await inspect(c.container);
      if (st?.endsWith("healthy") && !st.endsWith("unhealthy")) { await report(c.deploymentId, "RUNNING", null, await logsOf(c.container)); log("app running", c.container); return; }
      if (!st || st.startsWith("exited") || st.startsWith("dead")) break;
      await new Promise((s) => setTimeout(s, 1500));
    }
    const logs = await logsOf(c.container);
    await run("docker", ["rm", "-f", c.container], { timeoutMs: 30_000 });
    await report(c.deploymentId, "FAILED", "health check did not pass within 60 s", logs);
  } catch (e) {
    log("app start failed", c.container, e.message);
    await report(c.deploymentId, "FAILED", e.message);
  } finally { rmSync(tmp, { recursive: true, force: true }); starting.delete(c.container); }
}
async function reconcile() {
  const r = await api("GET", "/internal/runtime/desired"); if (!r.ok) return;
  const desired = (await r.json()).flatMap((a) => a.containers);
  const want = new Set(desired.map((c) => c.container));
  // the apps DB / gateway may have been recreated by compose: re-attach them to every running app's network
  for (const c of desired) if (!starting.has(c.container)) await ensureAppNetwork(appNetwork(c.container)).catch((e) => log("app network:", e.message));
  for (const c of desired) {
    if (starting.has(c.container)) continue;
    const st = await inspect(c.container);
    if (!st) { starting.add(c.container); void startContainer(c); continue; }
    if (st.startsWith("exited") || st.startsWith("dead")) { await report(c.deploymentId, "FAILED", `container ${st}`, await logsOf(c.container)); await run("docker", ["rm", "-f", c.container]); }
  }
  if (Date.now() - lastLogs > 30_000) {
    lastLogs = Date.now();
    for (const c of desired.filter((x) => x.role === "current" && !starting.has(x.container))) await report(c.deploymentId, "RUNNING", null, await logsOf(c.container));
  }
  const ps = await run("docker", ["ps", "-a", "--filter", "label=factory.app=1", "--format", "{{.Names}}"], { timeoutMs: 15_000 });
  for (const name of ps.out.split("\n").map((x) => x.trim()).filter(Boolean)) if (!want.has(name) && !starting.has(name)) { log("removing app container", name); await run("docker", ["rm", "-f", name], { timeoutMs: 30_000 }); }
  // networks of apps that no longer run, and images of deployments no longer desired (a rollback rebuilds the image from its artifact)
  const nets = new Set(desired.map((c) => appNetwork(c.container)));
  const ls = await run("docker", ["network", "ls", "--filter", "label=factory.app=1", "--format", "{{.Name}}"], { timeoutMs: 15_000 });
  for (const n of ls.out.split("\n").map((x) => x.trim()).filter(Boolean)) if (!nets.has(n) && ![...starting].some((c) => appNetwork(c) === n)) {
    for (const ctr of [APPDB_CONTAINER, GATEWAY_CONTAINER]) await run("docker", ["network", "disconnect", "-f", n, ctr], { timeoutMs: 15_000 });
    await run("docker", ["network", "rm", n], { timeoutMs: 15_000 });
  }
  const keepTags = new Set(desired.map((c) => `factory-app:${c.deploymentId.slice(0, 12)}`));
  const imgs = await run("docker", ["images", "factory-app", "--format", "{{.Repository}}:{{.Tag}}"], { timeoutMs: 15_000 });
  for (const t of imgs.out.split("\n").map((x) => x.trim()).filter(Boolean)) if (!keepTags.has(t) && starting.size === 0) await run("docker", ["rmi", t], { timeoutMs: 30_000 });
}
let lastReconcile = 0;

log(`runner ${NAME} polling ${API} (network ${NETWORK})`);
for (;;) {
  if (Date.now() - lastReconcile > 5000) { lastReconcile = Date.now(); await reconcile().catch((e) => log("runtime reconcile error:", e.message)); }
  try {
    const r = await api("POST", `/internal/build-jobs/claim?runner=${encodeURIComponent(NAME)}`);
    if (r.status === 200) {
      const job = await r.json(); await syncMirror(job).catch((e) => log("mirror sync failed:", e.message));
      if (job.purpose === "LOCK") await processLock(job); else if (job.purpose === "RESOLVE") await processResolve(job); else await processJob(job);
      continue;
    }
    if (r.status !== 204) log("claim answered", r.status);
  } catch (e) { log("poll error:", e.message); }
  await new Promise((s) => setTimeout(s, POLL));
}
