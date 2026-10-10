#!/usr/bin/env node
// C0 - RC SHA PROOF (D-C0-58). Verifies that EVERY component of the final isolated stack and of the public portals runs the same FINAL_RC_SHA, from LIVE evidence, and writes the stamp.
//   node scripts/rc-verify.mjs --stack c0rc --sha <FINAL_RC_SHA> [--public] [--out file]
// Reads $HOME/.xweb-e2e-stack/<stack>/SERVING.json (the build stamp written by `e2e-stack.sh up`) and compares it with what is running NOW: the API / worker / portal processes (pid, cwd, command), the served
// build ids, the sites gateway container + its mounted template, the migration level, the readiness. With --public it also reads .run/public/approved.json -> release.json (portals) and the API release pointer.
// Exit 0 only when every stack component matches; the public API is reported separately (it is NOT part of the RC while D-C0-57 says DEFER). No secret is read or printed.
import { execFileSync } from "node:child_process";
import { readFileSync, existsSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const stack = arg("--stack", "c0rc"); const FINAL = arg("--sha"); const wantPublic = process.argv.includes("--public"); const out = arg("--out");
if (!FINAL || !/^[0-9a-f]{40}$/.test(FINAL)) { console.error("usage: rc-verify.mjs --stack <name> --sha <40-hex FINAL_RC_SHA> [--public] [--out file]"); process.exit(64); }
const DIR = join(homedir(), ".xweb-e2e-stack", stack); const REPO = process.env.RC_REPO ?? new URL("..", import.meta.url).pathname.replace(/\/$/, "");
const sh = (cmd, args, o = {}) => { try { return execFileSync(cmd, args, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"], ...o }).trim(); } catch { return ""; } };
const rows = []; const add = (component, expected, actual, ok, note = "") => rows.push({ component, expected, actual, ok: !!ok, note });
const short = (s) => (s ? String(s).slice(0, 12) : "?");

if (!existsSync(join(DIR, "SERVING.json"))) { console.error(`no ${DIR}/SERVING.json: the stack was not built with the stamping tooling`); process.exit(2); }
const st = JSON.parse(readFileSync(join(DIR, "SERVING.json"), "utf8"));
add("stamp: pinned SHA", FINAL, st.sha, st.sha === FINAL);
add("stamp: portal checkout HEAD at build", FINAL, st.repoHead, st.repoHead === FINAL);
const WT = join(DIR, "backend-worktree");
const wtHead = sh("git", ["-C", WT, "rev-parse", "HEAD"]);
add("backend worktree HEAD (API + worker + gateway template source)", FINAL, wtHead, wtHead === FINAL);
add("stamp: backend worktree HEAD at build", FINAL, st.backendWorktreeHead, st.backendWorktreeHead === FINAL);

const listener = (port) => sh("lsof", ["-nP", `-iTCP:${port}`, "-sTCP:LISTEN", "-t"]).split("\n")[0];
const cwdOf = (pid) => sh("lsof", ["-a", "-p", pid, "-d", "cwd", "-Fn"]).split("\n").find((l) => l.startsWith("n"))?.slice(1) ?? "";
const cmdOf = (pid) => sh("ps", ["-o", "command=", "-p", pid]);
// API: the JVM of this stack runs from its own backend worktree
const apiPid = listener(st.ports.api); const apiCwd = apiPid ? cwdOf(apiPid) : ""; const apiCmd = apiPid ? cmdOf(apiPid) : "";
add("API process (listener on the API port)", `cwd under ${WT}`, apiPid ? `pid ${apiPid} cwd ${apiCwd}` : "no listener", apiPid && (apiCwd.startsWith(WT) || apiCmd.includes(WT)));
let ready = ""; try { ready = await (await fetch(`http://127.0.0.1:${st.ports.api}/actuator/health/readiness`, { signal: AbortSignal.timeout(8000) })).text(); } catch {}
add("API readiness", "UP", ready.slice(0, 40), /"UP"/.test(ready));
const mig = sh("docker", ["exec", `${stack}-pg`, "psql", "-U", "studio", "-d", "system_web_studio", "-Atc", "select max(version::int)||'/'||count(*) from flyway_schema_history where success"]);
add("migrations applied (max version / count)", "32 / (V31 is a gap)", mig, /^32\//.test(mig));
// render worker (same worktree)
const rPid = listener(st.ports.render); const rCwd = rPid ? cwdOf(rPid) : "";
add("render worker process", `cwd ${WT}`, rPid ? `pid ${rPid} cwd ${rCwd}` : "no listener", rPid && rCwd.startsWith(WT));
// portals: owned process + served build id == stamped build id
for (const app of ["studio", "platform", "admin"]) {
  const port = st.ports[app]; const pid = listener(port); const cwd = pid ? cwdOf(pid) : ""; const stamped = st.buildIds?.[app];
  let html = ""; try { html = await (await fetch(`http://127.0.0.1:${port}/${app}/login`, { signal: AbortSignal.timeout(10000) })).text(); } catch {}
  add(`${app} process`, `cwd ${REPO}/apps/${app}`, pid ? `pid ${pid} cwd ${cwd}` : "no listener", pid && cwd === `${REPO}/apps/${app}`);
  add(`${app} served build id == stamped build id`, stamped, stamped && html.includes(stamped) ? "served" : "NOT in served HTML", stamped && stamped !== "none" && html.includes(stamped));
}
// sites gateway: running, mounts the template of FINAL
const cid = sh("docker", ["ps", "-q", "--filter", `name=^${stack}-sites$`]);
const mounts = cid ? sh("docker", ["inspect", cid, "--format", "{{range .Mounts}}{{.Source}} {{end}}"]) : "";
const tplNow = existsSync(join(WT, "infra/sites-gateway/default.conf.template")) ? createHash("sha256").update(readFileSync(join(WT, "infra/sites-gateway/default.conf.template"))).digest("hex") : "";
add("sites gateway container (running, mounts the FINAL template)", st.sitesGatewayTemplateSha256, cid ? `container ${cid.slice(0, 12)}` : "not running", cid && mounts.includes(WT) && tplNow === st.sitesGatewayTemplateSha256);
add("flags (stack only)", "org=true publish-configs=true public-data=true", `org=${st.organizationPersistence} publish-configs=${st.publishConfigs} public-data=${st.sitesPublicData}`, st.organizationPersistence === "true" && st.publishConfigs === "true" && st.sitesPublicData === "true");

let pub = null;
if (wantPublic) {
  const a = JSON.parse(readFileSync(join(REPO, ".run/public/approved.json"), "utf8")); const rel = JSON.parse(readFileSync(join(REPO, ".run/public/releases", a.releaseId, "release.json"), "utf8"));
  add("PUBLIC portals: approved release source", FINAL, rel.sourceSha, rel.sourceSha === FINAL, `release ${a.releaseId}`);
  for (const [p, port, host, pre] of [["platform", 3201, "platform.toolsmcp.uk", "/platform"], ["admin", 3202, "admin.toolsmcp.uk", "/admin"], ["studio", 3203, "studio.toolsmcp.uk", "/studio"]]) {
    const bid = rel.portals?.[p]?.buildId; let localHtml = "", pubHtml = "";
    try { localHtml = await (await fetch(`http://127.0.0.1:${port}${pre}/login`, { signal: AbortSignal.timeout(10000) })).text(); } catch {}
    try { pubHtml = await (await fetch(`https://${host}${pre}/login`, { signal: AbortSignal.timeout(15000) })).text(); } catch {}
    add(`PUBLIC ${p} serves the release build id (local port + public domain)`, bid, `local=${localHtml.includes(bid)} public=${pubHtml.includes(bid)}`, bid && localHtml.includes(bid) && pubHtml.includes(bid));
  }
  const apiRel = JSON.parse(readFileSync(join(REPO, ".run/public/api/approved.json"), "utf8")).releaseId;
  pub = { publicApiRelease: apiRel, note: "NOT part of the RC while the public API decision is DEFER (D-C0-57)" };
}
const all = rows.every((r) => r.ok);
const stamp = { finalRcSha: FINAL, stack, verifiedAt: new Date().toISOString(), allMatch: all, components: rows, public: pub };
for (const r of rows) console.log(`${r.ok ? "PASS" : "FAIL"}  ${r.component.padEnd(66)} expected ${short(r.expected)}  actual ${String(r.actual).slice(0, 90)}${r.note ? "  (" + r.note + ")" : ""}`);
if (pub) console.log(`NOTE  public API release ${pub.publicApiRelease}: ${pub.note}`);
console.log(all ? `SHA_ALL_MATCH YES (${FINAL})` : "SHA_ALL_MATCH NO");
if (out) writeFileSync(out, JSON.stringify(stamp, null, 2) + "\n");
process.exit(all ? 0 : 1);
