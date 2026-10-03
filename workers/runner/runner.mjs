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
import { join } from "node:path";

const API = process.env.RUNNER_API ?? "http://127.0.0.1:8080";
const TOKEN = process.env.BUILD_RUNNER_TOKEN ?? "";
const NETWORK = process.env.BUILD_NETWORK ?? "hbl_build";
const NAME = process.env.RUNNER_NAME ?? `runner-${hostname()}`;
const POLL = Number(process.env.RUNNER_POLL_MS ?? 3000);
const OSV = process.env.OSV_URL ?? "https://api.osv.dev";
if (!TOKEN) { console.error("BUILD_RUNNER_TOKEN is required"); process.exit(1); }

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

async function processJob(job) {
  const id8 = job.id.slice(0, 8), vol = `factory-job-${id8}`, tmp = mkdtempSync(join(tmpdir(), `factory-${id8}-`));
  const L = []; const step = (s) => { L.push(`\n== ${s}`); log(job.id, s); };
  const scans = {}; let stage = "SOURCE";
  const finish = async (status, error) => {
    const r = await api("POST", `/internal/build-jobs/${job.id}/finish`, JSON.stringify({ status, stage, error, log: redact(L.join("\n")).slice(-60_000), scans }));
    log(job.id, status, error ?? "", `(finish ${r.status})`);
  };
  try {
    step(`source ${job.commitSha.slice(0, 12)}`);
    const src = await fetch(job.sourceUrl, { headers, signal: AbortSignal.timeout(120_000) });
    if (!src.ok) throw new Error(`source download HTTP ${src.status}`);
    writeFileSync(join(tmp, "src.tgz"), Buffer.from(await src.arrayBuffer()));
    const x = await run("tar", ["-xzf", join(tmp, "src.tgz"), "-C", tmp], { timeoutMs: 60_000 });
    if (x.code !== 0) throw new Error("source archive could not be extracted");
    const top = readdirSync(tmp).find((n) => n !== "src.tgz"); const srcDir = join(tmp, top);
    if (!existsSync(join(srcDir, "package-lock.json"))) throw new Error("package-lock.json missing");

    stage = "SCAN_SOURCE"; step("secret scan (source)");
    scans.sourceSecrets = await gitleaks(srcDir);
    if (scans.sourceSecrets.findings.length) throw new Error(`secret scan: ${scans.sourceSecrets.findings.length} finding(s) in source`);
    step("dependency scan (OSV)");
    const lock = JSON.parse(readFileSync(join(srcDir, "package-lock.json"), "utf8"));
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
      "-e", "npm_config_cache=/tmp/npm", job.image, "npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund", "--registry", "http://verdaccio:4873/"],
      { timeoutMs: (job.limits.installSeconds ?? 300) * 1000, container: `${vol}-i` });
    L.push(inst.out.slice(-8000), inst.err.slice(-8000));
    if (inst.code !== 0) throw new Error(inst.timedOut ? "install timed out" : `install failed (exit ${inst.code}) — packages outside the approved list are refused`);

    stage = "BUILD"; step("typecheck + build (no network)");
    const b = await run("docker", ["run", "--rm", "--name", `${vol}-b`, "--network", "none", ...HARDEN(job.limits), "-v", `${vol}:/work`, "-w", "/work",
      job.image, "sh", "-c", "npm run -s typecheck && npm run -s build"], { timeoutMs: (job.limits.buildSeconds ?? 600) * 1000, container: `${vol}-b` });
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

log(`runner ${NAME} polling ${API} (network ${NETWORK})`);
for (;;) {
  try {
    const r = await api("POST", `/internal/build-jobs/claim?runner=${encodeURIComponent(NAME)}`);
    if (r.status === 200) { await processJob(await r.json()); continue; }
    if (r.status !== 204) log("claim answered", r.status);
  } catch (e) { log("poll error:", e.message); }
  await new Promise((s) => setTimeout(s, POLL));
}
