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

/** open the Builder of a project by direct URL and wait until the canvas exists (or return what the user sees instead) */
export async function openBuilder(page, cfg, projectId) {
  const resp = page.waitForResponse((r) => /\/schema$/.test(new URL(r.url()).pathname), { timeout: 20_000 }).catch(() => null);
  await page.goto(projectUrl(cfg, projectId), { waitUntil: "domcontentloaded" });
  const schemaResponse = await resp;
  const canvas = await page.waitForSelector("iframe", { timeout: 15_000 }).then(() => true).catch(() => false);
  return { schemaResponse, canvas };
}

/** serious console problems: unhandled exceptions / rejections and console errors (404/401/403 of probes are expected and filtered) */
export const pageProblems = (page) => page.errors.slice();
export const bodyText = async (page) => (await page.locator("body").innerText()).replace(/\s+/g, " ");
