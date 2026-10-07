// @class: real-backend — Playwright helpers. NO request interception, NO route stubbing, NO fake responses anywhere in this suite.
// The only network manipulation allowed is link-level emulation (offline / throttling) via the browser context, which changes the NETWORK, not any answer.
import { createRequire } from "node:module";
const require = createRequire(new URL("../../../package.json", import.meta.url).pathname);

export async function launch(cfg) {
  const { chromium } = require("playwright-core");
  return chromium.launch({ executablePath: cfg.chrome, headless: cfg.headless });
}

/** a fresh, isolated browser context that records page errors + serious console errors + every response URL (for leak checks) */
export async function newPage(browser, viewport = { width: 1440, height: 900 }) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  page.errors = []; page.responses = [];
  page.on("pageerror", (e) => page.errors.push(`pageerror: ${e.message}`));
  page.on("console", (m) => { if (m.type() === "error" && !/favicon|Failed to load resource.*(401|403|404)/i.test(m.text())) page.errors.push(`console: ${m.text()}`); });
  page.on("response", (r) => { page.responses.push({ url: r.url(), status: r.status() }); });
  return page;
}

export async function loginUi(page, cfg, username, password) {
  await page.goto(`${cfg.studio}/login`, { waitUntil: "networkidle" });
  await page.getByLabel("Tên đăng nhập").fill(username);
  await page.getByLabel("Mật khẩu").fill(password);
  await Promise.all([page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 20_000 }), page.getByRole("button", { name: "Đăng nhập" }).click()]);
}

export const projectUrl = (cfg, projectId) => `${cfg.studio}${cfg.studioPrefix}/projects/${projectId}`;

/** `/projects/<id>` alone opens the AI mode (or the session's last mode); the Builder is the `design` view (ProjectWorkspace MODES). */
export const builderUrl = (cfg, projectId) => `${projectUrl(cfg, projectId)}/design`;

/** open the Builder of a project by direct URL and wait until the canvas exists (or return what the user sees instead) */
export async function openBuilder(page, cfg, projectId) {
  const resp = page.waitForResponse((r) => /\/schema$/.test(new URL(r.url()).pathname), { timeout: 20_000 }).catch(() => null);
  await page.goto(builderUrl(cfg, projectId), { waitUntil: "domcontentloaded" });
  const schemaResponse = await resp;
  const canvas = await page.waitForSelector("iframe", { timeout: 15_000 }).then(() => true).catch(() => false);
  return { schemaResponse, canvas };
}

/** Runs `fn(attempt)` at most `attempts` times (min 1) until it returns truthy. Never loops without a bound; the caller must treat `ok:false` as a failure. */
export async function boundedRetry(attempts, fn) {
  const max = Math.max(1, Math.floor(attempts));
  for (let n = 1; n <= max; n++) if (await fn(n)) return { ok: true, attempts: n };
  return { ok: false, attempts: max };
}

/** Text comparison that does not depend on CSS `text-transform` (innerText returns the transformed text) but still tells two different markers apart. */
export const containsText = (haystack, needle) => typeof needle === "string" && needle.length > 0 && String(haystack).toLowerCase().includes(needle.toLowerCase());

/** Clicks the first section on the canvas until the inspector shows an input. The iframe can exist before the Builder attached its selection listener
 *  (seen right after a backend restart): a bounded number of attempts, then `ok:false`. */
export async function selectFirstSection(page, attempts = 4) {
  const fr = page.frameLocator("iframe");
  return boundedRetry(attempts, async () => {
    await fr.locator("section").first().click({ position: { x: 30, y: 30 } });
    return page.waitForSelector(".bx-right input", { timeout: 5_000 }).then(() => true).catch(() => false);
  });
}

/** Every flow that types text into the shared fixture project records the marker here; E2E-13 then compares the PUBLISHED page with the latest marker the SERVER actually holds,
 *  not with "the marker of E2E-02" (flows run in any order, and a later edit overwrites the same field). */
export const rememberMarker = (fx, marker) => { (fx.notes.markers ??= []).push(marker); return marker; };
/** the markers that are still in the server's draft, oldest first (an overwritten marker is gone) */
export const liveMarkers = (fx, schema) => (fx.notes.markers ?? []).filter((m) => JSON.stringify(schema).includes(m));

/** Counts the requests a page makes to URLs matching `re` (optionally one method). Used to prove "no duplicate submit" and "no request storm". */
export function countRequests(page, re, method) {
  const seen = [];
  page.on("request", (r) => { if (re.test(new URL(r.url()).pathname) && (!method || r.method() === method)) seen.push({ t: Date.now(), method: r.method(), path: new URL(r.url()).pathname }); });
  return { get count() { return seen.length; }, seen, since: (t) => seen.filter((x) => x.t >= t).length };
}

/** serious console problems: unhandled exceptions / rejections and console errors (404/401/403 of probes are expected and filtered) */
export const pageProblems = (page) => page.errors.slice();
export const bodyText = async (page) => (await page.locator("body").innerText()).replace(/\s+/g, " ");
