// @class: harness
// Shared toolkit of the real-browser specs (tests/browser/*.spec.mjs): the check list, the console capture, the browser launch (Chrome by default) and the harness URL that every spec used to copy.
// It is test tooling, not a test: HARNESS, NOT REAL BACKEND, and nothing here starts, finds or stops a process.
//
//   import { chromium, harnessOrigin, launch, makeChecks, watchConsole } from "./lib/spec.mjs";
//   const { check, finish } = makeChecks();
//   const browser = await launch();
//   ... check("name", ok, "detail") ...
//   await browser.close(); finish();
//
// BROWSER=chromium|firefox|webkit picks the engine (default chromium = the installed Chrome, exactly as before). firefox and webkit are the Playwright-managed builds (`node node_modules/playwright-core/cli.js install firefox webkit`).
// Evidence labels are CHROMIUM / FIREFOX / WEBKIT (browserLabel()). Playwright WebKit is NOT Safari: never write "Safari" for a WEBKIT run.
//
// HARNESS_URL is REQUIRED. A spec started without it (i.e. not through `node tests/browser/harness-server.mjs run -- node tests/browser/<spec>`) fails at once with exit code 2. It never falls back to
// a fixed port such as 4000: that port may be someone else's server.
import { createRequire } from "node:module";
import { existsSync } from "node:fs";

const require = createRequire(new URL("../../../package.json", import.meta.url).pathname);
export const { chromium, firefox, webkit } = require("playwright-core");

/** print a fatal setup problem and stop with exit code 2 (a spec that cannot start is never a pass) */
export function die(message) { console.error(`\nspec setup error: ${message}\n`); process.exit(2); }

/** the harness page URL the owned server was started for; `name` is the environment variable (HARNESS_URL, or DS_HARNESS_URL for the data-source harness) */
export function harnessUrl(name = "HARNESS_URL") {
  const v = process.env[name];
  if (!v) die(`${name} is not set. Run the spec through the owned harness server so it gets a free port and is stopped afterwards:\n  node tests/browser/harness-server.mjs run -- node ${process.argv[1] ? process.argv[1].replace(process.cwd() + "/", "") : "tests/browser/<spec>.spec.mjs"}\n(no fixed port is ever assumed; port 4000 in particular may belong to another process)`);
  try { new URL(v); } catch { die(`${name}="${v}" is not a URL`); }
  return v;
}
/** the harness server's origin without the page name, e.g. http://127.0.0.1:51234 */
export const harnessOrigin = (name = "HARNESS_URL") => harnessUrl(name).replace(/\/[^/]*$/, "");
/** the URL of another harness page on the same server (`org.html`, `release.html`...) */
export const harnessPage = (page, name = "HARNESS_URL") => `${harnessOrigin(name)}/${page}`;

const CANDIDATES = {
  darwin: ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "/Applications/Chromium.app/Contents/MacOS/Chromium", "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"],
  linux: ["/opt/pw-browsers/chromium-1194/chrome-linux/chrome", "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium", "/usr/bin/chromium-browser"],
  win32: ["C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe", "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"],
};
/** the Chromium-family browser to drive: $CHROME if set (must exist), else the first installed default of this OS; otherwise a clear error naming what was looked for */
export function chromePath(env = process.env, platform = process.platform, exists = existsSync) {
  if (env.CHROME) { if (!exists(env.CHROME)) die(`CHROME=${env.CHROME} does not exist`); return env.CHROME; }
  const list = CANDIDATES[platform] ?? [];
  const hit = list.find((p) => exists(p));
  if (!hit) die(`no Chrome / Chromium found for ${platform}. Looked for:\n  ${list.join("\n  ") || "(no default for this OS)"}\nSet CHROME=/path/to/chrome`);
  return hit;
}
/** the engines a spec can run on; the evidence label of each is its upper-case name (CHROMIUM / FIREFOX / WEBKIT) */
export const BROWSERS = ["chromium", "firefox", "webkit"];
/** the engine selected by $BROWSER (default chromium); an unknown value stops with exit 2 instead of silently running Chrome */
export function browserName(env = process.env) {
  const v = (env.BROWSER ?? "").trim().toLowerCase() || "chromium";
  if (!BROWSERS.includes(v)) die(`BROWSER=${env.BROWSER} is not supported. Use one of: ${BROWSERS.join(", ")} (default chromium). There is no "safari": WebKit is the Playwright WebKit build.`);
  return v;
}
/** the evidence label of the selected engine: CHROMIUM, FIREFOX or WEBKIT */
export const browserLabel = (env = process.env) => browserName(env).toUpperCase();
/**
 * Launch the selected engine. chromium: the OS default Chrome path ($CHROME overrides); firefox / webkit: the Playwright-managed build (no executablePath, $CHROME is ignored).
 * `extra` is passed to Playwright (for example `{ args: ["--enable-precise-memory-info"] }`, which only makes sense for chromium). A non-default engine prints its label once so a log can never be mistaken for Chrome.
 */
export function launch(extra = {}, env = process.env) {
  const name = browserName(env);
  if (name === "chromium") return chromium.launch({ executablePath: chromePath(env), ...extra });
  console.log(`BROWSER=${name.toUpperCase()}`);
  return { firefox, webkit }[name].launch(extra);
}

/**
 * The check list every spec keeps: `check(name, ok, detail)` prints `PASS|FAIL  name  — detail` and records it, `skip(name, why)` records a skip, `finish()` prints the summary and exits 1 on any FAIL.
 * `clip`: shorten the detail to this many characters on one line (long DOM text). `results` is exposed for specs that report their own extras.
 */
export function makeChecks({ clip = 0 } = {}) {
  const results = [];
  const text = (d) => (clip ? String(d).replace(/\s+/g, " ").slice(0, clip) : d);
  const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + text(detail) : ""}`); };
  const skip = (name, why) => { results.push({ name, ok: null, detail: why }); console.log(`SKIP  ${name}  — ${why}`); };
  const finish = () => {
    const failed = results.filter((r) => r.ok === false), skipped = results.filter((r) => r.ok === null);
    console.log(`\n${results.length - failed.length - skipped.length}/${results.length - skipped.length} checks passed${skipped.length ? `, ${skipped.length} skipped` : ""}`);
    process.exit(failed.length ? 1 : 0);
  };
  return { results, check, skip, finish };
}

/** collect console errors / warnings and uncaught exceptions of a page into `into` (an array); `ignore` drops matching text (default: favicon and 404 noise of a harness) */
export function watchConsole(page, into, { ignore = /favicon|404/ } = {}) {
  page.on("pageerror", (e) => into.push(e.message));
  page.on("console", (m) => { if (["error", "warning"].includes(m.type()) && !ignore.test(m.text())) into.push(m.text()); });
}
