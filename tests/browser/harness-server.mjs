// @class: unit
// Owned static server for the browser-harness specs (replaces the unmanaged `python3 -m http.server 4000 &` + name-based cleanup pattern). It starts ONE server on a free port, records its identity
// (tests/lib/owned-process.mjs) and stops exactly that process afterwards. A busy port is a clear failure, never a kill.
//
//   node tests/browser/harness-server.mjs run   [--dir .test-build/browser] [--port N] -- node tests/browser/builder.spec.mjs     start, run the command with HARNESS_URL / DS_HARNESS_URL set, ALWAYS stop (finally)
//   node tests/browser/harness-server.mjs start [--dir ...] [--port N]                                                            start detached, print HARNESS_URL=...
//   node tests/browser/harness-server.mjs stop  [--dir ...]                                                                       stop the server this tool started for that dir (validated), nothing else
//   node tests/browser/harness-server.mjs serve --dir D --port P                                                                  (internal) the static server itself
// --port omitted or 0: a free high port is chosen. Never 3001/3002/3003/4000/... implicitly.
import { createServer } from "node:http";
import { createReadStream, statSync } from "node:fs";
import { spawn } from "node:child_process";
import { extname, join, normalize, resolve } from "node:path";
import { createHash } from "node:crypto";
import { PortBusyError, freePort, spawnOwned, stopOwned, readState, identify, refreshIdentity } from "../lib/owned-process.mjs";

const ROOT = resolve(new URL("../..", import.meta.url).pathname);
const argv = process.argv.slice(2); const sub = argv.shift();
const dd = argv.indexOf("--"); const after = dd >= 0 ? argv.splice(dd).slice(1) : [];
const opt = (k, d = null) => { const i = argv.indexOf(`--${k}`); return i >= 0 ? argv[i + 1] : d; };
const dir = resolve(ROOT, opt("dir", ".test-build/browser"));
const stateFile = join(ROOT, ".run", "owned", `harness-${createHash("sha1").update(dir).digest("hex").slice(0, 10)}.json`);
const MIME = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".json": "application/json", ".svg": "image/svg+xml", ".map": "application/json", ".png": "image/png", ".ico": "image/x-icon", ".woff2": "font/woff2" };

function serve(port) {
  const server = createServer((req, res) => {
    const url = new URL(req.url ?? "/", "http://x"); let p = normalize(decodeURIComponent(url.pathname)); if (p.endsWith("/")) p += "index.html";
    const file = join(dir, p);
    if (!file.startsWith(dir + "/") && file !== dir) { res.writeHead(403).end("forbidden"); return; }
    let st; try { st = statSync(file); } catch { res.writeHead(404).end("not found"); return; }      // read per request: a rebuilt `.test-build` is picked up without a restart
    if (!st.isFile()) { res.writeHead(404).end("not found"); return; }
    res.writeHead(200, { "content-type": MIME[extname(file)] ?? "application/octet-stream", "cache-control": "no-store", "content-length": st.size }); createReadStream(file).pipe(res);
  });
  server.on("error", (e) => { console.error(`harness server: ${e.code === "EADDRINUSE" ? `port ${port} is already in use (not ours)` : e.message}`); process.exit(3); });
  server.listen(port, "127.0.0.1");
  for (const s of ["SIGTERM", "SIGINT"]) process.on(s, () => server.close(() => process.exit(0)));
}

async function waitReady(port, owned, ms = 15000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try { const r = await fetch(`http://127.0.0.1:${port}/`, { signal: AbortSignal.timeout(1000) }); if (r.status < 500) return; } catch { /* not yet */ }
    if (identify(owned).state !== "OWNED") throw new Error("harness server exited before it was ready (port busy or crash)");
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error("harness server was not ready in time");
}
async function start() {
  const port = Number(opt("port", "0")) || (await freePort());
  const owned = await spawnOwned(process.execPath, [new URL(import.meta.url).pathname, "serve", "--dir", dir, "--port", String(port)], { stateFile, name: "harness-server", port, cwd: ROOT });   // PortBusyError if occupied
  try { await waitReady(port, owned); refreshIdentity(owned.stateFile); }
  catch (e) { await stopOwned(owned.stateFile); throw e; }
  return { port, owned };
}

try {
  if (sub === "serve") serve(Number(opt("port")));
  else if (sub === "start") { const { port, owned } = await start(); console.log(`HARNESS_URL=http://127.0.0.1:${port}/index.html\nHARNESS_PORT=${port}\nHARNESS_PID=${owned.pid}`); }
  else if (sub === "stop") { const s = readState(stateFile); if (!s) console.log("no harness server recorded for this directory"); else { const r = await stopOwned(stateFile); console.log(`harness server: ${r.state}${r.reason ? ` (${r.reason})` : ""}`); if (["REFUSED", "FAILED"].includes(r.state)) process.exit(r.state === "REFUSED" ? 4 : 5); } }
  else if (sub === "run") {
    if (!after.length) { console.error("run: command after --"); process.exit(1); }
    const { port } = await start(); let code = 1, child = null;
    const forward = (sig) => () => { if (child?.pid) { try { child.kill(sig); } catch { /* gone */ } } };      // only the child THIS process spawned
    process.on("SIGINT", forward("SIGINT")); process.on("SIGTERM", forward("SIGTERM"));
    try {
      const base = `http://127.0.0.1:${port}`;
      child = spawn(after[0], after.slice(1), { stdio: "inherit", env: { ...process.env, HARNESS_URL: `${base}/index.html`, DS_HARNESS_URL: `${base}/ds.html` } });
      code = await new Promise((res) => { child.once("exit", (c, s) => res(c ?? (s ? 128 : 1))); child.once("error", () => res(127)); });
    } finally {
      const r = await stopOwned(stateFile); if (!["STOPPED", "ALREADY_GONE"].includes(r.state)) { console.error(`harness server was not stopped cleanly: ${JSON.stringify(r)}`); code = code || 5; }
    }
    process.exit(code);
  } else { console.error("usage: harness-server.mjs run|start|stop [--dir D] [--port N] [-- cmd args]"); process.exit(1); }
} catch (e) {
  if (e instanceof PortBusyError) { console.error(`harness-server: ${e.message}`); process.exit(3); }
  console.error(`harness-server: ${e.message ?? e}`); process.exit(1);
}
