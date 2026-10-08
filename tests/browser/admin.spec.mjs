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

// ===================================================================================================================== M-007 the one-time activation link
await block("scenario 2", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/users" });
  await createAccountToLink(p);
  check("LNK01 the link dialog opens with the link; initial focus is on the COPY control, not on 'Xong'", (await dlg(p).count()) === 1 && /ACT-TOKEN/.test(await linkValue(p)) && (await focusName(p)) === "Sao chép liên kết", `focus = "${await focusName(p)}"`);
  await p.keyboard.press("Enter"); await settle(p, 250);
  check("LNK02 Enter on the initial focus copies the link (clipboard holds it) and says so", (await p.evaluate(() => navigator.clipboard.readText())).includes("/auth/activate#ACT-TOKEN") && /Đã sao chép/.test(await dlg(p).innerText()));
  await p.getByRole("button", { name: "Xong" }).click(); await settle(p, 250);
  check("LNK03 once copied, 'Xong' closes at once and the result dialog follows", /Đã tạo tài khoản/.test(await text(p)) && (await dlg(p).count()) === 1);
  await p.getByRole("button", { name: "Xem lại liên kết" }).click(); await settle(p, 250);
  check("LNK04 the result dialog can show the link again while this dialog lives (the link is only in its state, never stored)", /ACT-TOKEN/.test(await linkValue(p)));
  await p.__ctx.close(); });

await block("scenario 3", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/users" });
  await createAccountToLink(p);
  await p.keyboard.press("Escape"); await settle(p, 250);
  check("LNK05 Esc before the link was copied does NOT discard it: the dialog stays, the link is still there, a confirmation explains", (await dlg(p).count()) === 1 && /ACT-TOKEN/.test(await linkValue(p)) && /chưa sao chép/i.test(await p.locator("[role=alert]").first().innerText()));
  check("LNK05b the confirmation puts focus on the SAFE choice (back to the link), not on 'discard'", /Quay lại/.test(await focusName(p)), await focusName(p));
  await p.keyboard.press("Enter"); await settle(p, 200);
  check("LNK05c Enter on the safe choice returns to the link with nothing lost", (await dlg(p).count()) === 1 && /ACT-TOKEN/.test(await linkValue(p)) && (await p.locator("[role=alert]").count()) === 0);
  await p.getByRole("button", { name: "Xong" }).click(); await settle(p, 200);
  check("LNK06 'Xong' before copying asks the same question instead of closing", (await dlg(p).count()) === 1 && /chưa sao chép/i.test(await dlg(p).innerText()));
  await p.keyboard.press("Escape"); await settle(p, 200);
  check("LNK06b Esc inside the question cancels the question (still on the link), it does not close", (await dlg(p).count()) === 1 && /ACT-TOKEN/.test(await linkValue(p)) && (await p.locator("[role=alert]").count()) === 0);
  await p.getByRole("button", { name: "Xong" }).click(); await p.getByRole("button", { name: /Tôi đã lưu liên kết/ }).click(); await settle(p, 250);
  check("LNK07 'Tôi đã lưu liên kết, đóng' is the explicit confirmation: the link dialog closes and the result dialog follows", /Đã tạo tài khoản/.test(await text(p)));
  await p.__ctx.close(); });

await block("scenario 4", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/users" }, { clipboard: "deny" });
  await createAccountToLink(p);
  await p.getByRole("button", { name: "Sao chép liên kết" }).click(); await settle(p, 250);
  const alert = await p.locator("[role=alert]").first().innerText().catch(() => "");
  check("LNK08 a refused clipboard is VISIBLE (role=alert) and tells the person what to do; the button does not claim success", /Không sao chép được/.test(alert) && !/Đã sao chép/.test(await dlg(p).innerText()), alert);
  await p.getByRole("button", { name: "Xong" }).click(); await settle(p, 200);
  check("LNK08b after a failed copy 'Xong' still asks before discarding", (await dlg(p).count()) === 1 && /chưa sao chép/i.test(await dlg(p).innerText()));
  await p.__ctx.close(); });

await block("scenario 5", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/users" });
  await createAccountToLink(p);
  await p.getByLabel("Liên kết", { exact: true }).evaluate((i) => { i.focus(); i.select(); i.dispatchEvent(new ClipboardEvent("copy", { bubbles: true })); }); await settle(p, 150);
  await p.getByRole("button", { name: "Xong" }).click(); await settle(p, 250);
  check("LNK09 copying by hand from the field (Ctrl/Cmd+C) counts as copied", /Đã tạo tài khoản/.test(await text(p)));
  await p.__ctx.close(); });

await block("scenario 6", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/users/u2" });
  await p.getByRole("button", { name: "Đặt lại mật khẩu" }).click(); await settle(p, 400);
  check("LNK10 password-reset link (user detail): same rules, focus on Copy", (await focusName(p)) === "Sao chép liên kết", await focusName(p));
  await p.keyboard.press("Escape"); await settle(p, 200);
  check("LNK10b Esc does not discard the reset link either", (await dlg(p).count()) === 1 && /RESET|SECRET-TOKEN/.test(await linkValue(p)));
  await p.__ctx.close(); });

// Admin portal (tenant admin): the person who cannot re-issue the link in this UI
await block("scenario 7", async () => { const p = await open({ portal: "admin", me: "tadmin", start: "/admin/people" });
  await p.getByTestId("people-create").click(); await settle(p, 300);
  await p.getByTestId("acc-username").fill("moi.nv"); await p.getByTestId("acc-display").fill("Nhân Viên Mới"); await p.getByTestId("acc-submit").click(); await settle(p, 500);
  await p.keyboard.press("Enter"); await settle(p, 250);
  check("LNK11 tenant admin: creating an account, then Enter on the initial focus copies (never discards) the link", /Đã sao chép/.test(await dlg(p).innerText()) && /ACT-TOKEN/.test(await linkValue(p)));
  await p.__ctx.close(); });

check("no console error / warning / uncaught exception in any page", errors.length === 0, errors.slice(0, 3).join(" | "));
await browser.close();
const failed = results.filter((r) => !r.ok).length; console.log(`\n${results.length - failed}/${results.length} checks passed`); process.exit(failed ? 1 : 0);
