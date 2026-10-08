// @class: harness — real Chromium on the REAL PortalApp + AdminApp (Platform and Admin portals) with an in-page FAKE `fetch` (tests/browser/admin-harness.tsx). HARNESS, NOT REAL BACKEND, and NOT a backend E2E.
// It proves what the SCREENS do with the answers C1's contract describes (activation link, tenant provisioning, `/auth/me` scope); it never proves what a server answers.
// Run: node tests/browser/build-harness.mjs && CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/admin.spec.mjs
import { createRequire } from "node:module";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
const { chromium } = require("playwright-core");
const ORIGIN = (process.env.HARNESS_URL ?? "http://127.0.0.1:4000/index.html").replace(/\/[^/]*$/, "");
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`); };
const errors = [];
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? "/opt/pw-browsers/chromium-1194/chrome-linux/chrome" });

/** opens the harness page: q = {portal, me, start, ...}; `clipboard: "deny"` makes navigator.clipboard.writeText reject (a blocked / insecure origin) */
async function open(q, { w = 1280, h = 900, clipboard = "ok" } = {}) {
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, permissions: ["clipboard-read", "clipboard-write"] });
  const p = await ctx.newPage(); p.setDefaultTimeout(6000);
  p.on("pageerror", (e) => errors.push(e.message)); p.on("console", (m) => { if (["error", "warning"].includes(m.type()) && !/favicon|404/.test(m.text())) errors.push(m.text()); });
  p.on("dialog", (d) => { p.__dialogs.push({ type: d.type(), message: d.message() }); void d.accept(); }); p.__dialogs = [];
  if (clipboard === "deny") await p.addInitScript(() => { Object.defineProperty(navigator, "clipboard", { value: { writeText: () => Promise.reject(new DOMException("blocked", "NotAllowedError")), readText: () => Promise.resolve("") }, configurable: true }); });
  await p.goto(`${ORIGIN}/admin.html?${new URLSearchParams(q)}`); await p.waitForTimeout(500);
  p.__ctx = ctx; return p;
}
const calls = (p) => p.evaluate(() => window.__calls);
const posts = async (p, re) => (await calls(p)).filter((c) => c.method !== "GET" && re.test(c.path));
const focusName = (p) => p.evaluate(() => { const a = document.activeElement; return a ? (a.getAttribute("aria-label") || a.textContent || "").trim() : ""; });
/** a scenario that throws (a control that does not exist yet) is reported as ONE failed check and the run goes on */
async function block(name, fn) { try { await fn(); } catch (e) { check(`${name} aborted: ${String(e.message).split("\n")[0].slice(0, 120)}`, false); } }
const dlg = (p) => p.locator("[role=dialog]");
const text = async (p) => (await p.locator("body").innerText()).replace(/\s+/g, " ");
const settle = (p, ms = 350) => p.waitForTimeout(ms);
const linkValue = (p) => p.getByLabel("Liên kết", { exact: true }).inputValue();

/** Platform → Người dùng → "+ Tạo tài khoản" → fill → submit; the one-time link dialog is open afterwards */
async function createAccountToLink(p) {
  await p.getByTestId("users-create").click(); await settle(p, 600);
  await p.getByTestId("acc-tenant").selectOption("t1"); await p.getByTestId("acc-username").fill("moi.user"); await p.getByTestId("acc-display").fill("Người Mới");
  await p.getByTestId("acc-submit").click(); await settle(p, 500);
}

// ===================================================================================================================== smoke (the harness itself)
await block("scenario 1", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/tenants" });
  check("ADM00 the real Platform portal renders from the fake backend: nav + tenants table, no console error", (await p.getByRole("heading", { level: 1 }).innerText()) === "Công ty (tenant)" && (await p.locator("[data-testid=tenant-list] tbody tr").count()) === 3 && errors.length === 0, errors.join("|"));
  check("ADM00b a request with no fixture would be recorded as unknown (none made here)", !(await calls(p)).some((c) => c.unknown));
  await p.__ctx.close(); });

check("no console error / warning / uncaught exception in any page", errors.length === 0, errors.slice(0, 3).join(" | "));
await browser.close();
const failed = results.filter((r) => !r.ok).length; console.log(`\n${results.length - failed}/${results.length} checks passed`); process.exit(failed ? 1 : 0);
