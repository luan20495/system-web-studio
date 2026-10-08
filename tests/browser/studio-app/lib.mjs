// @class: harness — helpers of the Studio-app harness specs; NOT a backend and NOT a backend E2E (HARNESS, NOT REAL BACKEND)
import { createRequire } from "node:module";
import { mkdirSync } from "node:fs";
import { installFake, newState } from "./fake-api.mjs";
const require = createRequire(new URL("../../../package.json", import.meta.url).pathname);
export const { chromium } = require("playwright-core");
export { newState };
export const BASE = new URL("studio.html", process.env.HARNESS_URL ?? "http://127.0.0.1:4000/index.html").href;
export const SHOTS = process.env.SHOTS ?? "/tmp/shots"; mkdirSync(SHOTS, { recursive: true });
export const results = [];
export const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  -- " + detail : ""}`); };
export const wait = (ms) => new Promise((r) => setTimeout(r, ms));
export const launch = () => chromium.launch({ executablePath: process.env.CHROME ?? "/opt/pw-browsers/chromium-1194/chrome-linux/chrome" });
/** opens the Studio app at a virtual path with a fake API state; `page.state` is the state, `page.dialogs` collects native dialogs */
export async function open(browser, start, { state = newState(), viewport = { width: 1440, height: 900 } } = {}) {
  const ctx = await browser.newContext({ viewport }); const page = await ctx.newPage();
  page.errors = []; page.on("pageerror", (e) => page.errors.push(e.message));
  page.on("dialog", (d) => { (page.dialogs ??= []).push({ type: d.type(), message: d.message() }); void d.accept(); });
  await installFake(page, state);
  await page.goto(`${BASE}?start=${encodeURIComponent(start)}`, { waitUntil: "domcontentloaded" });
  page.state = state;
  return page;
}
export const finish = () => { const bad = results.filter((r) => !r.ok); console.log(`\n${results.length - bad.length}/${results.length} passed`); if (bad.length) process.exitCode = 1; };
