// @class: tooling (S4 audit). Shared environment for the measurement scripts: an OWNED static server for a harness bundle and an OWNED headless Chrome, both started and stopped through
// tests/lib/owned-process.mjs (identity recorded, only what we started is stopped, a busy port fails, nothing is found by name or by port). Chrome is driven with Playwright connectOverCDP.
import { createRequire } from "node:module";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { freePort, withOwned } from "../tests/lib/owned-process.mjs";

export const ROOT = resolve(new URL("..", import.meta.url).pathname);
const require = createRequire(join(ROOT, "package.json"));
export const { chromium } = require("playwright-core");
export const CHROME = process.env.CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";

async function waitHttp(url, ms = 20000) {
  const end = Date.now() + ms;
  while (Date.now() < end) { try { const r = await fetch(url, { signal: AbortSignal.timeout(1000) }); if (r.status < 500) return; } catch { /* not yet */ } await new Promise((r) => setTimeout(r, 150)); }
  throw new Error(`not ready: ${url}`);
}

/** runs fn({ base, browser, chromeVersion }) with an owned static server on `dir` (relative to the repo) and an owned headless Chrome; both are ALWAYS stopped afterwards. */
export async function withEnv({ dir = ".test-build/browser-prod", tag = "perf" } = {}, fn) {
  // dir === null: no static server (the caller measures servers it started itself through the owned-process CLI); `base` is then null
  const abs = dir ? resolve(ROOT, dir) : null; const sport = dir ? await freePort() : null; const cport = await freePort(); const profile = mkdtempSync(join(tmpdir(), "xweb-perf-chrome-"));
  const state = (n) => join(ROOT, ".run", "owned", `${tag}-${n}.json`);
  const server = (inner) => (dir ? withOwned(process.execPath, [join(ROOT, "tests/browser/harness-server.mjs"), "serve", "--dir", abs, "--port", String(sport)], { stateFile: state("server"), name: `${tag}-server`, port: sport, cwd: ROOT }, async () => { await waitHttp(`http://127.0.0.1:${sport}/`); return inner(); }) : inner());
  try {
    return await server(async () => {
      return withOwned(CHROME, ["--headless=new", `--remote-debugging-port=${cport}`, `--user-data-dir=${profile}`, "--no-first-run", "--no-default-browser-check", "--enable-precise-memory-info", "--window-size=1440,900", "about:blank"],
        { stateFile: state("chrome"), name: `${tag}-chrome`, port: cport, cwd: ROOT }, async () => {
          await waitHttp(`http://127.0.0.1:${cport}/json/version`);
          const browser = await chromium.connectOverCDP(`http://127.0.0.1:${cport}`);
          try { return await fn({ base: sport ? `http://127.0.0.1:${sport}` : null, browser, chromeVersion: browser.version() }); } finally { await browser.close().catch(() => undefined); }
        });
    });
  } finally { rmSync(profile, { recursive: true, force: true }); }
}
