// @class: harness — real Chromium on the REAL PortalApp + AdminApp (Platform and Admin portals) with an in-page FAKE `fetch` (tests/browser/admin-harness.tsx). HARNESS, NOT REAL BACKEND, and NOT a backend E2E.
// It proves what the SCREENS do with the answers C1's contract describes (activation link, tenant provisioning, `/auth/me` scope); it never proves what a server answers.
// Run: node tests/browser/build-harness.mjs && CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/admin.spec.mjs
import { harnessOrigin, launch, makeChecks } from "./lib/spec.mjs";
const ORIGIN = harnessOrigin();
const { check, finish } = makeChecks();
const errors = [];
const browser = await launch();

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

// ===================================================================================================================== M-008 create company → first admin
await block("scenario 7", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/tenants" });
  await p.getByRole("button", { name: "+ Tạo công ty" }).click(); await settle(p, 300);
  const note = await p.locator("[role=dialog] .xp-note").innerText();
  check("CMP01 the dialog's note names the REAL next step (the 'Tạo tài khoản quản trị công ty' button of the company page), not a vague 'ở trang công ty'", /Tạo tài khoản quản trị công ty/.test(note), note);
  await p.getByTestId("tenant-name").fill("Công ty Mới"); await p.getByRole("button", { name: "Tạo công ty" }).last().click(); await settle(p, 600);
  check("CMP02 after creating, the company page opens", /\/platform\/tenants\/tn\d+$/.test(p.url()), p.url());
  const cta = p.getByRole("button", { name: "Tạo tài khoản quản trị công ty" });
  check("CMP03 the empty company page offers 'Tạo tài khoản quản trị công ty' (in the header AND in the empty members card) and says why", (await cta.count()) === 2 && /chưa có quản trị viên/i.test(await text(p)), `buttons=${await cta.count()}`);
  await cta.first().click(); await settle(p, 400);
  check("CMP04 the dialog opens with this company FIXED (read-only, from the page) and the type 'Quản trị công ty' preselected", (await p.getByTestId("acc-tenant-fixed").inputValue()) === "Công ty Mới" && (await p.getByTestId("acc-type").inputValue()) === "TENANT_ADMIN");
  const wsOpts = await p.getByTestId("acc-workspace").locator("option").allInnerTexts();
  check("CMP05 the workspace list is NOT another company's workspaces (no tenant→workspace listing exists): only 'none' and 'create one of this company'", wsOpts.length === 2 && /Không gán/.test(wsOpts[0]) && /Tạo workspace mới/.test(wsOpts[1]), wsOpts.join("|"));
  await p.getByTestId("acc-username").fill("giam.doc"); await p.getByTestId("acc-display").fill("Giám Đốc"); await p.getByTestId("acc-submit").click(); await settle(p, 500);
  const req = (await posts(p, /\/admin\/tenants\/tn\d+\/users$/))[0];
  check("CMP06 ONE existing provisioning route is called for the company on the page, with tenantRole TENANT_ADMIN and no workspace", !!req && req.body.tenantRole === "TENANT_ADMIN" && !("workspaceId" in req.body) && req.body.username === "giam.doc", JSON.stringify(req));
  check("CMP06b no other write was sent (no new route)", (await calls(p)).filter((c) => c.method !== "GET" && !/\/admin\/tenants(\/tn\d+\/users)?$/.test(c.path) && !/auth/.test(c.path)).length === 0);
  await p.getByRole("button", { name: "Sao chép liên kết" }).click(); await p.getByRole("button", { name: "Xong" }).click(); await settle(p, 250); await p.getByRole("button", { name: "Xong" }).click(); await settle(p, 500);
  check("CMP07 back on the company page the new admin is listed and the 'no admin yet' state is gone", /giam\.doc|Giám Đốc/.test(await p.locator("[data-testid=tenant-members]").innerText()) && !/chưa có quản trị viên/i.test(await text(p)));
  await p.__ctx.close(); });

await block("scenario 8", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/tenants/t2" }); // SUSPENDED
  check("CMP08 a suspended company offers no create-account button (unlock first)", (await p.getByRole("button", { name: "Tạo tài khoản quản trị công ty" }).count()) === 0);
  await p.__ctx.close(); });
await block("scenario 9", async () => { const p = await open({ portal: "admin", me: "tadmin", start: "/admin/company" });
  check("CMP09 a tenant admin's own company page does not get the platform button (they use 'Người dùng')", (await p.getByRole("button", { name: "Tạo tài khoản quản trị công ty" }).count()) === 0);
  await p.__ctx.close(); });

// ===================================================================================================================== M-009 SYSTEM_ADMIN application detail
await block("scenario 10", async () => { const p = await open({ portal: "admin", me: "sys", start: "/admin/applications/a1" }); // workspace w9: the person is NOT a member
  const t = await text(p);
  check("APP01 a platform admin who is not a member of the workspace gets no 'Xóa' button (the backend answers 404 for it)", (await p.getByRole("button", { name: "Xóa", exact: true }).count()) === 0);
  check("APP02 the reason is VISIBLE text on the page (not a title tooltip) and names the workspace", /không phải thành viên workspace “Kho Hà Nội”/.test(t), t.slice(t.indexOf("Cổng khách hàng"), t.indexOf("Cổng khách hàng") + 300));
  check("APP03 'Lưu trữ' (admin route) is still offered", (await p.getByRole("button", { name: "Lưu trữ", exact: true }).count()) === 1);
  await p.getByRole("tab", { name: "Phiên bản" }).click(); await settle(p, 200);
  check("APP04 versions: no 'Khôi phục' button for this person; the same reason is shown above the table", (await p.getByRole("button", { name: "Khôi phục", exact: true }).count()) === 0 && /không phải thành viên workspace/.test(await text(p)));
  check("APP05 nothing was sent to a workspace-scoped route", (await calls(p)).filter((c) => /^\/workspaces\//.test(c.path) && c.method !== "GET").length === 0);
  await p.__ctx.close(); });
await block("scenario 11", async () => { const p = await open({ portal: "admin", me: "sysmember", start: "/admin/applications/a2" }); // member of w1
  check("APP06 a platform admin who IS a member of the workspace keeps 'Xóa' (the server still decides)", (await p.getByRole("button", { name: "Xóa", exact: true }).count()) === 1 && !/không phải thành viên workspace/.test(await text(p)));
  await p.getByRole("button", { name: "Xóa", exact: true }).click(); await settle(p, 400);
  check("APP07 and it calls the workspace-scoped delete route as before", (await posts(p, /^\/workspaces\/w1\/projects\/a2/)).length === 1, JSON.stringify((await calls(p)).filter((c) => c.method === "DELETE")));
  await p.getByRole("tab", { name: "Phiên bản" }).click(); await settle(p, 200);
  check("APP08 member: 'Khôi phục' (old versions) stays available", (await p.getByRole("button", { name: "Khôi phục", exact: true }).count()) === 1);
  await p.__ctx.close(); });
await block("scenario 12", async () => { const p = await open({ portal: "admin", me: "sysatenant", start: "/admin/applications/a1" }); // businessAccess = true (legacy flag on)
  check("APP09 when the server says the person has business access (/auth/me businessAccess), the controls stay", (await p.getByRole("button", { name: "Xóa", exact: true }).count()) === 1);
  await p.__ctx.close(); });

// ===================================================================================================================== route / navigation snapshot (M-066: the split must not change behaviour)
// For every persona the sidebar labels and, for every section key (deep links, aliases, foreign and unknown keys included), the final path, the h1, the h2s and the kind of state view are recorded in admin-routes.snapshot.json
// (generated from the pre-split code, `UPDATE_SNAPSHOT=1` rewrites it). Dates / numbers are not compared: only structure.
{
  const KEYS = ["", "tenants", "tenants/t1", "users", "users/u2", "workspaces", "workspaces/w1", "applications", "applications/a1", "ai", "ai/providers", "ai/models", "ai/limits", "ai/usage", "ai-governance", "alerts", "security", "costs", "departments", "identity", "connectors", "backups", "components", "templates", "audit", "builds", "packages", "system", "settings", "groups", "sharing", "byok", "company", "organization", "employees", "people", "my-workspaces", "data-sources", "nope", "nope/deeper"];
  const PERSONAS = [["platform", "sys"], ["platform", "tadmin"], ["admin", "sys"], ["admin", "sysmember"], ["admin", "tadmin"], ["admin", "wsadmin"], ["admin", "plain"], ["all", "sys"], ["all", "tadmin"]];
  const snap = {};
  for (const [portal, me] of PERSONAS) {
    const p = await open({ portal, me, start: `/${portal === "all" ? "admin" : portal}` }); const prefix = portal === "all" ? "/admin" : `/${portal}`; const out = { nav: null, routes: {} };
    for (const k of KEYS) {
      await p.evaluate((u) => { history.pushState(null, "", u); window.dispatchEvent(new PopStateEvent("popstate")); }, `${prefix}${k ? "/" + k : ""}`);
      for (let i = 0; i < 20; i++) { await p.waitForTimeout(120); if (i > 2 && (await p.locator(".spinner").count()) === 0) break; }
      if (out.nav === null && (await p.locator("#admin-sidebar").count())) out.nav = await p.locator("#admin-sidebar nav a").allInnerTexts();
      out.routes[k || "(home)"] = { url: new URL(p.url()).pathname, h1: await p.locator("h1").allInnerTexts(), h2: await p.locator("main h2").allInnerTexts(), states: await p.locator(".stateView").evaluateAll((els) => els.map((e) => [...e.classList].filter((c) => c.startsWith("state-")).join(""))), coming: await p.locator(".comingSoon").count(), title: await p.title() };
    }
    snap[`${portal}/${me}`] = out; await p.__ctx.close();
  }
  const { readFileSync, writeFileSync } = await import("node:fs"); const file = new URL("./admin-routes.snapshot.json", import.meta.url).pathname;
  if (process.env.UPDATE_SNAPSHOT) { writeFileSync(file, JSON.stringify(snap, null, 1) + "\n"); console.log("snapshot written"); }
  const want = JSON.parse(readFileSync(file, "utf8")); const diffs = [];
  for (const persona of Object.keys(want)) {
    if (JSON.stringify(want[persona].nav) !== JSON.stringify(snap[persona]?.nav)) diffs.push(`${persona} nav: ${JSON.stringify(snap[persona]?.nav)}`);
    for (const k of Object.keys(want[persona].routes)) if (JSON.stringify(want[persona].routes[k]) !== JSON.stringify(snap[persona]?.routes[k])) diffs.push(`${persona} ${k}: ${JSON.stringify(snap[persona]?.routes[k])} (want ${JSON.stringify(want[persona].routes[k])})`);
  }
  check(`SNAP01 every persona's sidebar and every section's page (${Object.keys(want).length} personas × ${KEYS.length} paths) is exactly what it was before the split`, diffs.length === 0, diffs.slice(0, 3).join(" || "));
}

check("no console error / warning / uncaught exception in any page", errors.length === 0, errors.slice(0, 3).join(" | "));
await browser.close();
finish();
