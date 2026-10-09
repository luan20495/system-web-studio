// @class: tooling
// Builds the three portal apps into PRIVATE dist dirs (NEXT_DIST_DIR) with API_PROXY_TARGET pointing at an existing backend, and serves each on a free high port. Every process is started and stopped ONLY through
// tests/lib/owned-process.mjs (identity recorded, a busy port fails, nothing is found or stopped by name or by port). The backend itself is never started or stopped here.
//   import { startPrivatePortals } from "./private-portals.mjs";
//   const p = await startPrivatePortals({ api: "http://127.0.0.1:47080" }); ... p.urls.platform / admin / studio ...; await p.stop();
// `next build` rewrites tsconfig.json files; their original text is put back afterwards. The builds need ~10 s each.
import { spawnSync } from "node:child_process";
import { createServer, request as httpRequest } from "node:http";
import { readFileSync, writeFileSync, existsSync, rmSync } from "node:fs";
import { join, resolve } from "node:path";
import { freePort, spawnOwned, stopOwned } from "../../tests/lib/owned-process.mjs";

const ROOT = resolve(new URL("../..", import.meta.url).pathname);
const APPS = ["platform", "admin", "studio"];

async function waitHttp(url, ms = 30000) {
  const end = Date.now() + ms;
  while (Date.now() < end) { try { const r = await fetch(url, { signal: AbortSignal.timeout(1500) }); if (r.status < 500) return; } catch { /* not yet */ } await new Promise((r) => setTimeout(r, 200)); }
  throw new Error(`not ready: ${url}`);
}

// ---------------------------------------------------------------------------------------------------------------------------- the origin shim
// The backend answers 403 "Invalid CORS request" to a browser POST whose Origin is not on CORS_ALLOWED_ORIGINS (the e2e stack lists ports 3086 / 3001 / 3002, which belong to someone else), and a private port is never on that list.
// The shim is a plain pass-through to the backend that rewrites ONLY the Origin (and Referer) request header to an allowed origin. It does not touch the backend, its configuration, cookies, bodies or responses.
function runShim(port, target, origin) {
  const t = new URL(target);
  createServer((req, res) => {
    const headers = { ...req.headers, host: t.host }; if (headers.origin) headers.origin = origin; if (headers.referer) headers.referer = origin + "/";
    const up = httpRequest({ host: t.hostname, port: t.port, method: req.method, path: req.url, headers }, (r) => { res.writeHead(r.statusCode ?? 502, r.headers); r.pipe(res); });
    up.on("error", (e) => { if (!res.headersSent) res.writeHead(502); res.end(String(e.message)); }); req.pipe(up);
  }).listen(Number(port), "127.0.0.1");
}
if (process.argv[2] === "--shim") runShim(process.argv[3], process.argv[4], process.argv[5]);

export async function startPrivatePortals({ api, allowOrigin = null, tag = "audit", build = true, log = (m) => console.error(`[private-portals] ${m}`) } = {}) {
  if (!api) throw new Error("startPrivatePortals needs `api` (the backend base URL, e.g. http://127.0.0.1:47080)");
  const dist = (a) => `.next-check-${tag}`;                                                        // private, git-ignored (.next-*), one per app directory
  const tsconfigs = [join(ROOT, "tsconfig.json"), ...APPS.map((a) => join(ROOT, "apps", a, "tsconfig.json"))].filter(existsSync);
  const saved = new Map(tsconfigs.map((f) => [f, readFileSync(f, "utf8")]));
  const started = [];
  const urls = {};
  let target = api;
  if (allowOrigin) {                                                                             // route the apps' /api proxy through the origin shim (see above)
    const sp = await freePort(); const o = await spawnOwned(process.execPath, [new URL(import.meta.url).pathname, "--shim", String(sp), api, allowOrigin], { stateFile: join(ROOT, ".run", "owned", `${tag}-shim.json`), name: `${tag}-shim`, port: sp });
    started.push(o); target = `http://127.0.0.1:${sp}`; log(`origin shim on ${target} -> ${api} (Origin rewritten to ${allowOrigin})`);
  }
  try {
    if (build) for (const a of APPS) {
      log(`build ${a} -> ${dist(a)} (API_PROXY_TARGET=${target})`);
      const r = spawnSync("npx", ["next", "build"], { cwd: join(ROOT, "apps", a), env: { ...process.env, NEXT_DIST_DIR: dist(a), API_PROXY_TARGET: target }, encoding: "utf8", timeout: 600000 });
      if (r.status !== 0) throw new Error(`next build of ${a} failed:\n${(r.stdout ?? "").slice(-1500)}\n${(r.stderr ?? "").slice(-800)}`);
    }
  } catch (e) { for (const o of started) await stopOwned(o.stateFile).catch(() => undefined); throw e; }
  finally { for (const [f, t] of saved) writeFileSync(f, t); }                                 // put the tsconfig files back as they were
  try {
    for (const a of APPS) {
      const port = await freePort(); const stateFile = join(ROOT, ".run", "owned", `${tag}-${a}.json`);
      const o = await spawnOwned("npx", ["next", "start", "-H", "127.0.0.1", "-p", String(port)], { cwd: join(ROOT, "apps", a), env: { NEXT_DIST_DIR: dist(a), API_PROXY_TARGET: target }, stateFile, name: `${tag}-${a}`, port });
      started.push(o); urls[a] = `http://127.0.0.1:${port}`; log(`${a} on ${urls[a]} (owned pid ${o.pid})`);
    }
    for (const a of APPS) await waitHttp(`${urls[a]}/login`);
  } catch (e) { for (const o of started) await stopOwned(o.stateFile).catch(() => undefined); throw e; }
  return {
    urls,
    async stop() {
      const out = []; for (const o of started) { const r = await stopOwned(o.stateFile); out.push(`${o.name}:${r.state}`); }
      for (const a of APPS) rmSync(join(ROOT, "apps", a, dist(a)), { recursive: true, force: true });
      log(`stopped ${out.join(" ")}`); return out;
    },
  };
}
