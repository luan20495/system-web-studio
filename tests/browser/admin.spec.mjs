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
/** client-side navigation inside the page (no reload): the app's router listens to popstate */
const nav = async (p, path) => { await p.evaluate((u) => { history.pushState(null, "", u); window.dispatchEvent(new PopStateEvent("popstate")); }, path); await settle(p, 450); };
const linkValue = (p) => p.getByLabel("Liên kết", { exact: true }).inputValue();


/**
 * A confirmation is the app's Modal (M-017), never a native dialog: it names what happens, Esc sends NOTHING, and the confirm button sends the request.
 * `field`: a prompt (reason / name) whose empty answer is refused with an inline error.
 */
async function ask(p, { open, title, confirm, re, field, method }) {
  const hits = async () => (await calls(p)).filter((c) => c.method !== "GET" && (!method || c.method === method) && re.test(c.path)).length;
  const n0 = p.__dialogs.length; const h0 = await hits();   // earlier requests of the same kind do not count
  await open(); await settle(p, 250);
  const d = dlg(p);
  const okTitle = (await d.count()) === 1 && title.test(await d.locator("h2").first().innerText().catch(() => ""));
  const noNative = p.__dialogs.length === n0;
  await p.keyboard.press("Escape"); await settle(p, 200);
  const cancelled = (await dlg(p).count()) === 0 && (await hits()) === h0;
  await open(); await settle(p, 250);
  let emptyRefused = true;
  if (field) { await dlg(p).locator("textarea, input").first().fill(""); await dlg(p).getByRole("button", { name: confirm, exact: true }).click(); await settle(p, 150); emptyRefused = (await dlg(p).count()) === 1 && (await dlg(p).locator("[role=alert]").count()) === 1 && (await hits()) === h0; await dlg(p).locator("textarea, input").first().fill(field); }
  await dlg(p).getByRole("button", { name: confirm, exact: true }).click(); await settle(p, 350);
  return { okTitle, noNative, cancelled, emptyRefused, sent: (await hits()) > h0 };
}
const verdict = (name, r) => check(name, r.okTitle && r.noNative && r.cancelled && r.emptyRefused && r.sent, JSON.stringify(r));

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
  check("LNK04 after the link was copied and the dialog closed the token is GONE: not in the DOM, and there is no 'Xem lại liên kết' (M-088: a secret is kept only until copied / confirmed)", !/ACT-TOKEN/.test(await p.content()) && (await p.getByRole("button", { name: "Xem lại liên kết" }).count()) === 0);
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

// ===================================================================================================================== M-010 (S2 side) a create dialog cannot be dismissed while its request is in flight
await block("scenario 6a", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/tenants", slow: "/admin/tenants" });
  await p.getByRole("button", { name: "+ Tạo công ty" }).click(); await settle(p, 300);
  await p.getByTestId("tenant-name").fill("Công ty Chậm"); await p.getByRole("button", { name: "Tạo công ty" }).last().click(); await settle(p, 300);
  await p.keyboard.press("Escape"); await settle(p, 200);
  check("INF01 create company: Esc while the POST is in flight does not close the dialog", (await dlg(p).count()) === 1);
  await p.waitForTimeout(2600);
  check("INF01b after the answer the company page opens as usual", /\/platform\/tenants\/tn\d+$/.test(p.url()), p.url());
  await p.__ctx.close(); });
await block("scenario 6b", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/users" });
  await p.getByTestId("users-create").click(); await settle(p, 600);
  await p.getByTestId("acc-tenant").selectOption("t1"); await p.getByTestId("acc-username").fill("cham.user"); await p.getByTestId("acc-display").fill("Chậm");
  await p.evaluate(() => { window.__cfg.slow = "/admin/tenants/t1/users"; }); await p.getByTestId("acc-submit").click(); await settle(p, 300);
  await p.keyboard.press("Escape"); await settle(p, 200);
  check("INF02 create account: Esc while the POST is in flight does not close the dialog", (await dlg(p).count()) === 1);
  await p.waitForTimeout(2600);
  check("INF02b the one-time link is shown when the answer arrives", /ACT-TOKEN/.test(await linkValue(p)));
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
  await p.getByRole("button", { name: "Xóa", exact: true }).click(); await settle(p, 300); await dlg(p).getByRole("button", { name: "Xóa ứng dụng", exact: true }).click(); await settle(p, 400);
  check("APP07 and it calls the workspace-scoped delete route as before", (await posts(p, /^\/workspaces\/w1\/projects\/a2/)).length === 1, JSON.stringify((await calls(p)).filter((c) => c.method === "DELETE")));
  await p.getByRole("tab", { name: "Phiên bản" }).click(); await settle(p, 200);
  check("APP08 member: 'Khôi phục' (old versions) stays available", (await p.getByRole("button", { name: "Khôi phục", exact: true }).count()) === 1);
  await p.__ctx.close(); });
await block("scenario 12", async () => { const p = await open({ portal: "admin", me: "sysatenant", start: "/admin/applications/a1" }); // businessAccess = true (legacy flag on)
  check("APP09 when the server says the person has business access (/auth/me businessAccess), the controls stay", (await p.getByRole("button", { name: "Xóa", exact: true }).count()) === 1);
  await p.__ctx.close(); });

// ===================================================================================================================== M-076 values that are not what the screen expects must not blank the portal
await block("scenario 8", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/audit", bad: "audit" });
  await p.locator(".xp-discBtn").first().click(); await settle(p, 300);
  check("DAT01 an audit row whose value is NOT JSON expands: the page stays, the raw text is shown", (await p.locator("h1").count()) === 1 && /not json at all/.test(await p.locator(".xp-discDetail pre").innerText()), `h1=${await p.locator("h1").count()}`);
  await p.__ctx.close(); });
await block("scenario 9", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/audit" });
  await p.locator(".xp-discBtn").first().click(); await settle(p, 300);
  check("DAT02 a JSON audit value still shows pretty-printed JSON (unchanged)", /"a": 1/.test(await p.locator(".xp-discDetail pre").innerText()));
  // M-029: the row opens from the keyboard, says so, and closes again
  await p.locator(".xp-discBtn").first().click(); await settle(p, 200);
  const b0 = p.locator(".xp-discBtn").first(); await b0.focus();
  const ex0 = await b0.getAttribute("aria-expanded"); await p.keyboard.press("Enter"); await settle(p, 200);
  const ex1 = await b0.getAttribute("aria-expanded"); const ctl = await b0.getAttribute("aria-controls"); const shown = await p.locator(".xp-discDetail pre").count();
  await p.keyboard.press("Space"); await settle(p, 200);
  check("AUD01 an audit row is a real button: Enter opens (aria-expanded + aria-controls + detail), Space closes", ex0 === "false" && ex1 === "true" && !!ctl && shown === 1 && (await b0.getAttribute("aria-expanded")) === "false" && (await p.locator(".xp-discDetail").count()) === 0, `${ex0} ${ex1} ${ctl} ${shown}`);
  check("AUD02 the button has a name that says which event (not just an icon)", /^Chi tiết: /.test(((await b0.innerText()) || (await b0.evaluate((e) => e.textContent))).trim()));
  await p.__ctx.close(); });
await block("scenario 10", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/components", bad: "schema" });
  await p.getByRole("button", { name: "Schema" }).click(); await settle(p, 300);
  check("DAT03 a component whose props schema is not JSON opens its row: the page stays, the raw text is shown", (await p.locator("h1").count()) === 1 && /not json at all/.test(await p.locator(".detailRow pre").innerText()));
  await p.__ctx.close(); });
await block("scenario 11", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/ai/usage", daily: "empty" });
  await settle(p, 400);
  check("DAT04 AI usage with an empty daily series renders (no 'Invalid time value')", (await p.locator("h1").count()) === 1 && /Mức sử dụng mô hình/.test(await text(p)));
  await p.__ctx.close(); });

// ===================================================================================================================== M-054 the Platform overview speaks to the platform operator
await block("scenario 12", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform" }); await settle(p, 500);
  const main = await p.locator("main").innerText();
  check("OVW01 the Platform overview does NOT show the company checklist (no 'Tạo ứng dụng đầu tiên', no 'Mở Builder Studio', no 'Thêm người dùng')", !/Tạo (website|ứng dụng) đầu tiên|Mở Builder Studio|Thêm người dùng|Thiết lập ban đầu/.test(main), main.slice(0, 220));
  check("OVW02 it shows the platform's own checklist: companies, AI provider, model, limits", /Thiết lập nền tảng/.test(main) && /Tạo công ty đầu tiên/.test(main) && /Thêm nhà cung cấp AI/.test(main) && /Thiết lập hạn mức AI/.test(main));
  await p.__ctx.close(); });
await block("scenario 13", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform", empty: "1" }); await settle(p, 500);
  const link = p.locator("main .checklist a", { hasText: "Tạo công ty" });
  check("OVW03 no company yet: the first step links to the Platform's company list", (await link.count()) === 1 && (await link.getAttribute("href")) === "/platform/tenants");
  await p.__ctx.close(); });
await block("scenario 14", async () => { const p = await open({ portal: "all", me: "sys", start: "/admin" }); await settle(p, 500);
  check("OVW04 the legacy combined console keeps the company checklist (first step says 'ứng dụng', not 'website')", /Thiết lập ban đầu/.test(await p.locator("main").innerText()) && /Tạo ứng dụng đầu tiên/.test(await p.locator("main").innerText()) && !/website/i.test(await p.locator(".checklist").innerText()));
  await p.__ctx.close(); });
await block("scenario 15", async () => { const p = await open({ portal: "admin", me: "sys", start: "/admin" }); await settle(p, 500);
  check("OVW05 the Admin console's overview for a SYSTEM_ADMIN shows no setup checklist (unchanged)", !/Thiết lập (ban đầu|nền tảng)/.test(await p.locator("main").innerText()));
  await p.__ctx.close(); });

// ===================================================================================================================== M-059 the AI usage tab does not present the legacy /admin/ai provider / key status as the truth
await block("scenario 16", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/ai/usage" }); await settle(p, 600);
  const main = await p.locator("main").innerText(); const kpi = await p.locator(".kpi", { hasText: "Nhà cung cấp AI" }).innerText().catch(() => "");
  check("AIS01 the provider tile comes from the configured providers (OpenAI công ty is enabled): not 'OpenRouter / Chưa có OPENROUTER_API_KEY' from the legacy single-provider endpoint", /1 đang bật/.test(kpi) && /OpenAI công ty/.test(kpi) && !/OPENROUTER_API_KEY/.test(main) && !/Chưa có OPENROUTER/.test(main), kpi.replace(/\s+/g, " "));
  check("AIS02 'qua OpenRouter' is gone from the daily tile (those calls go through whichever provider is configured)", !/qua OpenRouter/.test(main));
  check("AIS03 no new route: the tile reads /admin/ai/providers, which the Providers tab already uses", (await calls(p)).some((c) => c.path === "/admin/ai/providers") && !(await calls(p)).some((c) => c.unknown));
  await p.__ctx.close(); });
await block("scenario 17", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/ai/usage", empty: "1" }); await settle(p, 600);
  const kpi = await p.locator(".kpi", { hasText: "Nhà cung cấp AI" }).innerText().catch(() => "");
  check("AIS04 no provider configured: the tile says the system runs in trial (simulated) mode, without an env var name", /Chế độ thử nghiệm/.test(kpi) && !/OPENROUTER|API_KEY/.test(kpi), kpi.replace(/\s+/g, " "));
  await p.__ctx.close(); });

// ===================================================================================================================== M-064 copy that contradicts the product (password rule per flow, stale "not implemented", "website")
await block("scenario 18", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/users" });
  await p.getByTestId("users-create").click(); await settle(p, 600);
  check("TXT01 the create-account dialog states the password rule of the ACTIVATION flow (the person sets it: at least 8 characters)", /tối thiểu 8 ký tự/.test(await p.locator("[role=dialog]").innerText()));
  await p.getByTestId("acc-tenant").selectOption("t1"); await p.getByTestId("acc-username").fill("moi.user"); await p.getByTestId("acc-display").fill("Người Mới"); await p.getByTestId("acc-submit").click(); await settle(p, 500);
  check("TXT02 the activation link dialog states it too", /tối thiểu 8 ký tự/.test(await p.locator("[role=dialog]").innerText()));
  await p.__ctx.close(); });
await block("scenario 19", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/users/u2" });
  await p.getByRole("button", { name: "Đặt lại mật khẩu" }).click(); await settle(p, 400);
  check("TXT03 the password-reset link dialog states the rule", /tối thiểu 8 ký tự/.test(await p.locator("[role=dialog]").innerText()));
  await p.__ctx.close(); });
await block("scenario 20", async () => { const p = await open({ portal: "admin", me: "sys", start: "/admin/applications/a1" }); await settle(p, 400);
  const main = await p.locator("main").innerText();
  check("TXT04 application detail does not list 'Lưu trữ (archive)' as not implemented beside a working 'Lưu trữ' button", !/Lưu trữ \(archive\)/.test(main) && (await p.getByRole("button", { name: "Lưu trữ", exact: true }).count()) === 1 && /Chặn xuất bản công khai/.test(main), main.slice(main.indexOf("Chưa triển khai"), main.indexOf("Chưa triển khai") + 160));
  await p.__ctx.close(); });
await block("scenario 21", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform" }); await settle(p, 600);
  const t = await p.locator("main").innerText();
  check("TXT05 the AI month card does not say budgets are not implemented (they are: 'Quyền & ngân sách AI')", !/chưa triển khai/.test(t) && /Quyền & ngân sách AI/.test(t));
  await p.__ctx.close(); });

// ===================================================================================================================== M-017 every native confirm / prompt is the app's dialog
await block("scenario 22", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/users/u2", fx: "1" });
  verdict("CNF01 lock account", await ask(p, { open: () => p.getByRole("button", { name: "Khóa tài khoản", exact: true }).click(), title: /Khóa tài khoản binh\?/, confirm: "Khóa tài khoản", re: /^\/admin\/users\/u2\/status/ }));
  verdict("CNF02 grant system admin", await ask(p, { open: () => p.getByRole("button", { name: "Cấp quyền Quản trị hệ thống" }).click(), title: /Cấp quyền Quản trị hệ thống cho binh\?/, confirm: "Cấp quyền", re: /system-admin$/ }));
  verdict("CNF03 revoke sessions", await ask(p, { open: () => p.getByRole("button", { name: /Thu hồi phiên/ }).click(), title: /Thu hồi mọi phiên đăng nhập của binh\?/, confirm: "Thu hồi phiên", re: /revoke-sessions$/ }));
  await p.__ctx.close(); });
await block("scenario 23", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/tenants/t1", fx: "1" });
  verdict("CNF04 suspend a company", await ask(p, { open: () => p.getByTestId("tenant-SUSPENDED").click(), title: /Tạm khóa công ty “Acme”\?/, confirm: "Tạm khóa", re: /tenants\/t1\/status/ }));
  verdict("CNF05 remove a company member (the person is named)", await ask(p, { open: () => p.locator('[data-testid="tm:u2"] button').click(), title: /Gỡ .*binh.* khỏi công ty\?/i, confirm: "Gỡ khỏi công ty", re: /tenants\/t1\/members\/u2/ }));
  await p.__ctx.close(); });
await block("scenario 24", async () => { const p = await open({ portal: "admin", me: "tadmin", start: "/admin/my-workspaces", fx: "1" });
  verdict("CNF06 remove a workspace member", await ask(p, { open: () => p.locator('[data-testid="wm:binh"] button').click(), title: /Gỡ Bình khỏi workspace\?/, confirm: "Gỡ khỏi workspace", re: /workspaces\/w1\/members\/u2/ }));
  await p.__ctx.close(); });
await block("scenario 25", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/ai/providers", fx: "1" });
  verdict("CNF07 delete an AI provider", await ask(p, { open: () => p.getByRole("button", { name: "Xóa", exact: true }).first().click(), title: /Xóa nhà cung cấp “OpenAI công ty”\?/, confirm: "Xóa nhà cung cấp", re: /ai\/providers\/p1$/ }));
  await nav(p, "/platform/ai/limits"); await settle(p, 500);
  verdict("CNF08 delete an AI limit override", await ask(p, { open: () => p.getByRole("button", { name: "Xóa", exact: true }).first().click(), title: /Xóa hạn mức riêng này\?/, confirm: "Xóa hạn mức", re: /limits\/overrides\/o1$/ }));
  await p.__ctx.close(); });
await block("scenario 26", async () => { const p = await open({ portal: "admin", me: "sys", start: "/admin/ai-governance", fx: "1" });
  verdict("CNF09 delete an AI budget", await ask(p, { open: () => p.getByRole("button", { name: "Xóa", exact: true }).first().click(), title: /Xóa ngân sách này\?/, confirm: "Xóa ngân sách", re: /ai\/budgets\/b1$/ }));
  await nav(p, "/admin/departments"); await settle(p, 500);
  verdict("CNF10 delete a department", await ask(p, { open: () => p.getByRole("button", { name: "Xóa", exact: true }).first().click(), title: /Xóa “Kỹ thuật”\?/, confirm: "Xóa", re: /departments\/d1$/, method: "DELETE" }));
  verdict("CNF11 rename a department (a prompt: empty is refused)", await ask(p, { open: () => p.getByRole("button", { name: "Đổi tên" }).click(), title: /Đổi tên “Kỹ thuật”/, confirm: "Đổi tên", re: /departments\/d1$/, method: "PATCH", field: "Kỹ thuật mới" }));
  await p.__ctx.close(); });
await block("scenario 27", async () => { const p = await open({ portal: "admin", me: "sys", start: "/admin/applications/a1", fx: "1" });
  verdict("CNF12 archive an application", await ask(p, { open: () => p.getByRole("button", { name: "Lưu trữ", exact: true }).click(), title: /Lưu trữ “Cổng khách hàng”\?/, confirm: "Lưu trữ", re: /applications\/a1\/archive$/ }));
  await p.__ctx.close(); });
await block("scenario 28", async () => { const p = await open({ portal: "admin", me: "sysmember", start: "/admin/applications/a2", fx: "1" });
  await p.getByRole("tab", { name: "Phiên bản" }).click(); await settle(p, 200);
  verdict("CNF13 restore an old version", await ask(p, { open: () => p.getByRole("button", { name: "Khôi phục", exact: true }).click(), title: /Khôi phục v1\?/, confirm: "Khôi phục v1", re: /projects\/a2\/versions|restore/ }));
  await p.__ctx.close(); });
await block("scenario 29", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/builds", fx: "1" });
  verdict("CNF14 permanently delete a repository", await ask(p, { open: () => p.getByRole("button", { name: "Xóa vĩnh viễn" }).click(), title: /Xóa vĩnh viễn kho mã “repo-1”\?/, confirm: "Xóa vĩnh viễn", re: /repositories\/pr1\/delete$/ }));
  await p.__ctx.close(); });
await block("scenario 30", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/packages", fx: "1" });
  verdict("CNF15 accept a package's risk (a prompt: the reason is required)", await ask(p, { open: () => p.getByRole("button", { name: "Cho phép", exact: true }).click(), title: /Cho phép “date-fns” dù có lỗ hổng/, confirm: "Chấp nhận rủi ro", re: /packages\/date-fns\/decision$/, field: "Đã rà soát, chỉ dùng ở phía máy chủ" }));
  await p.__ctx.close(); });
await block("scenario 31", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/settings", fx: "1" });
  await p.getByLabel("Tên miền cho phép").fill("evil.com");
  verdict("CNF16 change a HIGH-risk setting", await ask(p, { open: () => p.getByRole("button", { name: "Lưu", exact: true }).first().click(), title: /Đổi “Tên miền cho phép”\?/, confirm: "Đổi cài đặt", re: /settings\/policies\/net\.domains$/ }));
  await p.__ctx.close(); });
await block("scenario 32", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/templates", fx: "1" });
  verdict("CNF17 reject a template (a prompt: the reason is required)", await ask(p, { open: () => p.getByRole("button", { name: "Từ chối", exact: true }).click(), title: /Từ chối mẫu “Trang chủ mẫu”/, confirm: "Từ chối", re: /templates\/tp1\/review$/, field: "Thiếu tiêu đề" }));
  verdict("CNF18 archive a template", await ask(p, { open: () => p.getByRole("button", { name: "Lưu trữ", exact: true }).first().click(), title: /Lưu trữ mẫu “Trang chủ mẫu”\?/, confirm: "Lưu trữ", re: /templates\/tp1\/status$/ }));
  await p.__ctx.close(); });

// ===================================================================================================================== M-018 privileged / high-impact actions are explicit
await block("scenario 33", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/tenants/t1" });
  const sel = p.locator('[data-testid="tm:u2"] select');
  await sel.selectOption("TENANT_ADMIN"); await settle(p, 250);
  check("ROL01 choosing a company role in the select sends NOTHING; 'Lưu' / 'Hủy' appear", (await posts(p, /tenants\/t1\/members\/u2/)).length === 0 && (await p.getByTestId("tm-save:u2").count()) === 1);
  await p.locator('[data-testid="tm:u2"]').getByRole("button", { name: "Hủy", exact: true }).click(); await settle(p, 150);
  check("ROL02 'Hủy' puts the select back and removes the buttons", (await sel.inputValue()) === "MEMBER" && (await p.getByTestId("tm-save:u2").count()) === 0);
  await sel.selectOption("TENANT_ADMIN");
  verdict("ROL03 promoting to company admin asks first (names the person and the power)", await ask(p, { open: () => p.getByTestId("tm-save:u2").click(), title: /Cho .*binh.* làm quản trị công ty\?/i, confirm: "Cho làm quản trị", re: /tenants\/t1\/members\/u2/, method: "PUT" }));
  check("ROL03b the PUT carries the chosen role", (await posts(p, /tenants\/t1\/members\/u2/)).some((c) => c.method === "PUT" && c.body.role === "TENANT_ADMIN"));
  await settle(p, 400);
  await p.locator('[data-testid="tm:u-ta"] select').selectOption("MEMBER");
  verdict("ROL04 taking the admin role away asks first", await ask(p, { open: () => p.getByTestId("tm-save:u-ta").click(), title: /Bỏ quyền quản trị công ty của/, confirm: "Bỏ quyền quản trị", re: /tenants\/t1\/members\/u-ta/, method: "PUT" }));
  await p.__ctx.close(); });
await block("scenario 34", async () => { const p = await open({ portal: "admin", me: "tadmin", start: "/admin/my-workspaces" });
  const sel = p.locator('[data-testid="wm:binh"] select');
  await sel.selectOption("PUBLISHER"); await settle(p, 200);
  check("ROL05 a workspace role is applied by 'Lưu', not by the select", (await posts(p, /workspaces\/w1\/members\/u2/)).length === 0 && (await p.getByTestId("wm-save:binh").count()) === 1);
  const n0 = (await dlg(p).count()); await p.getByTestId("wm-save:binh").click(); await settle(p, 350);
  check("ROL06 a non-admin role change needs no confirmation (one PATCH, no dialog)", n0 === 0 && (await dlg(p).count()) === 0 && (await posts(p, /workspaces\/w1\/members\/u2/)).some((c) => c.method === "PATCH" && c.body.role === "PUBLISHER"));
  await sel.selectOption("WORKSPACE_ADMIN");
  verdict("ROL07 making someone workspace admin asks first", await ask(p, { open: () => p.getByTestId("wm-save:binh").click(), title: /Cho Bình làm quản trị workspace\?/, confirm: "Cho làm quản trị", re: /workspaces\/w1\/members\/u2/, method: "PATCH" }));
  await p.__ctx.close(); });
await block("scenario 35", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/ai/providers", fx: "1" });
  verdict("HIC01 turning an AI provider off (every model of it, for everyone) asks first", await ask(p, { open: () => p.getByRole("button", { name: "Tắt", exact: true }).first().click(), title: /Tắt nhà cung cấp “OpenAI công ty”\?/, confirm: "Tắt nhà cung cấp", re: /ai\/providers\/p1$/, method: "PUT" }));
  await p.__ctx.close(); });
await block("scenario 36", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/connectors", fx: "1" });
  verdict("HIC02 turning a connector off asks first", await ask(p, { open: () => p.getByRole("button", { name: "Tắt", exact: true }).first().click(), title: /Tắt connector “CRM”\?/, confirm: "Tắt connector", re: /connectors\/crm\/status/, method: "POST" }));
  await p.__ctx.close(); });
await block("scenario 37", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/packages", fx: "1" });
  verdict("HIC03 denying an allowed package asks first", await ask(p, { open: () => p.getByRole("button", { name: "Từ chối", exact: true }).click(), title: /Từ chối package “left-pad”\?/, confirm: "Từ chối package", re: /packages\/left-pad\/decision$/, method: "PUT" }));
  await p.__ctx.close(); });
await block("scenario 38", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/templates", fx: "1" });
  verdict("HIC04 sharing a template with the whole company asks first", await ask(p, { open: () => p.getByRole("button", { name: "Chia sẻ toàn công ty" }).click(), title: /Chia sẻ mẫu “Bảng giá” cho cả công ty\?/, confirm: "Chia sẻ toàn công ty", re: /templates\/tp2\/visibility$/, method: "POST" }));
  await p.__ctx.close(); });
await block("scenario 39", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/builds", fx: "1" });
  verdict("HIC05 running the retention cleanup now asks first (it deletes data)", await ask(p, { open: () => p.getByRole("button", { name: "Chạy dọn dẹp ngay" }).click(), title: /Chạy dọn dẹp ngay\?/, confirm: "Chạy dọn dẹp", re: /retention\/run$/, method: "POST" }));
  await p.__ctx.close(); });

// ===================================================================================================================== M-025 (Admin part) skip link, focus and scroll on route change
await block("scenario 40", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/users" }); await settle(p, 500);
  await p.keyboard.press("Tab");
  check("SKP01 the FIRST Tab stop is the skip link", /Bỏ qua điều hướng/.test(await focusName(p)), await focusName(p));
  await p.keyboard.press("Enter"); await settle(p, 200);
  check("SKP02 Enter on it lands in <main> (the sidebar's 16 links are skipped)", await p.evaluate(() => document.activeElement?.id === "main"));
  const tabindex = await p.locator("main").getAttribute("tabindex"); const scrolls = await p.evaluate(() => { const m = document.querySelector("main"); return m.scrollHeight > m.clientHeight; });
  check("SKP03 <main> is a Tab stop (0) only while it scrolls, otherwise -1", tabindex === (scrolls ? "0" : "-1"), `tabindex=${tabindex} scrolls=${scrolls}`);
  await p.__ctx.close(); });
await block("scenario 41", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/system" }, { h: 400 }); await settle(p, 500);
  await p.evaluate(() => { document.querySelector("main").scrollTop = 99999; });
  await p.getByRole("link", { name: "Cài đặt" }).click(); await settle(p, 700);
  check("SKP04 following a sidebar link moves focus to the new page's h1", await p.evaluate(() => document.activeElement?.tagName === "H1" && /Cài đặt/.test(document.activeElement.textContent)), await p.evaluate(() => document.activeElement?.tagName + ":" + (document.activeElement?.textContent ?? "").slice(0, 30)));
  check("SKP05 and scrolls <main> back to the top", (await p.evaluate(() => document.querySelector("main").scrollTop)) === 0);
  await p.__ctx.close(); });

// ===================================================================================================================== M-055 an unknown address is a 404, not "it is in the other console"
await block("scenario 42", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/nope" }); await settle(p, 400);
  const h1 = await p.locator("h1").innerText(); const t = await p.locator("main").innerText();
  check("NF01 /platform/nope says it was not found (no 'nằm ở trang khác', no button to the other console)", /Không tìm thấy/.test(h1) && !/trang khác/.test(t) && (await p.locator("main a[href^='/admin']").count()) === 0, h1);
  check("NF02 and offers a way back to the overview of THIS console", (await p.locator("main").getByRole("link", { name: /tổng quan/i }).getAttribute("href")) === "/platform");
  await nav(p, "/platform/people"); check("NF03 /platform/people (a SYSTEM_ADMIN has no such screen anywhere) is a 404, not a loop to the Admin console", /Không tìm thấy/.test(await p.locator("h1").innerText()));
  await nav(p, "/platform/company"); check("NF04 /platform/company exists in the Admin console: the pointer stays", /nằm ở trang khác/.test(await p.locator("h1").innerText()) && (await p.locator("main a[href='/admin/company']").count()) === 1);
  await p.__ctx.close(); });
await block("scenario 43", async () => { const p = await open({ portal: "admin", me: "sys", start: "/admin/nope/deeper" }); await settle(p, 400);
  check("NF05 /admin/nope/deeper is a 404", /Không tìm thấy/.test(await p.locator("h1").innerText()));
  await nav(p, "/admin/people"); check("NF06 /admin/people as a SYSTEM_ADMIN is a 404 (no ping-pong)", /Không tìm thấy/.test(await p.locator("h1").innerText()));
  await nav(p, "/admin/ai"); check("NF07 /admin/ai exists in the Platform: the pointer stays", /nằm ở trang khác/.test(await p.locator("h1").innerText()));
  await p.__ctx.close(); });

// ===================================================================================================================== M-056 a failed secondary load says so (error + retry), never a spinner or '…' forever
await block("scenario 44", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/builds", fail: "/admin/retention" }); await settle(p, 900);
  const main = p.locator("main");
  check("LDG01 Builds: the two failed panels (cleanup preview, repositories) show an error each, NO spinner left", (await main.locator(".spinner").count()) === 0 && (await main.locator(".state-error, .state-network").count()) === 2, `spinners=${await main.locator(".spinner").count()} errors=${await main.locator(".state-error, .state-network").count()}`);
  check("LDG02 the report that DID load is still on screen", /Build 30 ngày/.test(await main.innerText()));
  await p.evaluate(() => { window.__cfg.fail = null; });
  await main.getByRole("button", { name: "Thử lại" }).first().click(); await settle(p, 600);
  check("LDG03 'Thử lại' reloads that panel (the other one still offers it)", (await main.locator(".state-error, .state-network").count()) === 1 && /Sẽ xóa/.test(await main.innerText()));
  await p.__ctx.close(); });
await block("scenario 45", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/builds", slow: "/admin/retention" }); await settle(p, 500);
  check("LDG04 a loading panel says WHAT is loading", /Đang tải bản xem trước dọn dẹp/.test(await p.locator("main").innerText()));
  await p.__ctx.close(); });
await block("scenario 46", async () => { const p = await open({ portal: "admin", me: "sys", start: "/admin/identity", fail: "/admin/scim" }); await settle(p, 900);
  const main = p.locator("main");
  check("LDG05 Identity: a failed SCIM load leaves no '…' KPI and no spinner; it says so with a retry", !/…/.test(await main.locator(".kpiGrid").innerText()) && (await main.locator(".spinner").count()) === 0 && (await main.getByRole("button", { name: "Thử lại" }).count()) >= 1, (await main.locator(".kpiGrid").innerText()).replace(/\s+/g, " "));
  await p.__ctx.close(); });
await block("scenario 47", async () => { const p = await open({ portal: "admin", me: "sys", start: "/admin/identity", fail: "/auth/config" }); await settle(p, 900);
  check("LDG06 Identity: a failed /auth/config says so in the OIDC / SAML tiles (not '…')", !/…/.test(await p.locator("main .kpiGrid").innerText()) && (await p.locator("main .kpiGrid .state-error, main .kpiGrid .state-network").count()) >= 2);
  await p.__ctx.close(); });
await block("scenario 48", async () => { const p = await open({ portal: "admin", me: "sys", start: "/admin/ai-governance", fail: "/admin/users" }); await settle(p, 500);
  await p.getByLabel("Phạm vi").first().selectOption("USER"); await settle(p, 900);
  check("LDG07 a scope picker whose search failed says so (it used to show an empty list)", /Chưa tải được danh sách để chọn/.test(await p.locator("main").innerText()));
  await p.__ctx.close(); });
await block("scenario 49", async () => { const p = await open({ portal: "admin", me: "sys", start: "/admin/applications/a1", fail: "/admin/workspaces" }); await settle(p, 900);
  check("LDG08 application detail: the owner choices that failed to load say so", /Chưa tải được danh sách thành viên workspace/.test(await p.locator("main").innerText()));
  await p.__ctx.close(); });
await block("scenario 50", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/users/u2", slow: "/admin/users/u2" }); await settle(p, 400);
  check("LDG09 a detail page that is still loading already has its one h1, naming what loads", (await p.locator("h1").count()) === 1 && /Đang tải thông tin người dùng/.test(await p.locator("h1").innerText()));
  await p.__ctx.close(); });
await block("scenario 51", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/users/u2", fail: "/admin/users/u2" }); await settle(p, 900);
  check("LDG10 a detail page whose load failed has one h1 (the error) and a retry", (await p.locator("h1").count()) === 1 && (await p.getByRole("button", { name: "Thử lại" }).count()) === 1);
  await p.__ctx.close(); });
await block("scenario 52", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/tenants", fail: "/admin/users" }); await settle(p, 400);
  await p.getByRole("button", { name: "+ Tạo công ty" }).click(); await settle(p, 900);
  check("LDG11 create company: the first-admin picker whose account list failed says so", /Chưa tải được danh sách tài khoản/.test(await p.locator("[role=dialog]").innerText()));
  await p.__ctx.close(); });

// ===================================================================================================================== M-057 the employee status button is never a dead control
await block("scenario 53", async () => { const p = await open({ portal: "admin", me: "sysatenant", start: "/admin/employees" }); await settle(p, 600);
  await p.locator("[data-testid^='emp:']").first().click(); await settle(p, 300);
  const btn = p.getByTestId("detail-toggle");
  check("EMP57a a SYSTEM_ADMIN who is also a company admin: the status button is UNAVAILABLE (aria-disabled), not an enabled control with no handler", (await btn.getAttribute("aria-disabled")) === "true");
  check("EMP57b the reason is visible text next to it and says where the action is", /Platform/.test(await p.locator("[role=dialog] .xp-reason").innerText()), await p.locator("[role=dialog] .xp-reason").innerText().catch(() => "none"));
  await p.evaluate(() => { window.__calls.length = 0; }); await btn.click({ force: true }); await settle(p, 300);
  check("EMP57c clicking it sends nothing", (await calls(p)).filter((c) => c.method !== "GET").length === 0);
  await p.__ctx.close(); });
await block("scenario 54", async () => { const p = await open({ portal: "admin", me: "tadmin", start: "/admin/employees" }); await settle(p, 600);
  await p.locator("[data-testid^='emp:']").first().click(); await settle(p, 300);
  check("EMP57d a company admin: unavailable too, with the existing explanation as visible text", (await p.getByTestId("detail-toggle").getAttribute("aria-disabled")) === "true" && /Chỉ quản trị hệ thống/.test(await p.locator("[role=dialog] .xp-reason").innerText()));
  await p.__ctx.close(); });

// ===================================================================================================================== M-058 the company list can be searched and paged (client-side, over what the API returns)
await block("scenario 55", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/tenants", big: "1" }); await settle(p, 500);
  check("TEN01 63 companies: a search box and a pager exist; one page of rows is rendered (not all 63)", (await p.getByLabel("Tìm công ty").count()) === 1 && (await p.locator("[data-testid=tenant-list] tbody tr").count()) === 25 && /1–25 \/ 63/.test(await p.locator(".pager").innerText()), `rows=${await p.locator("[data-testid=tenant-list] tbody tr").count()}`);
  await p.locator(".pager").getByRole("button", { name: /Sau/ }).click(); await settle(p, 200);
  check("TEN02 'Sau' shows the next page", /26–50 \/ 63/.test(await p.locator(".pager").innerText()));
  await p.getByLabel("Tìm công ty").fill("Công ty 5"); await settle(p, 400);
  const names = await p.locator("[data-testid=tenant-list] tbody tr b").allInnerTexts();
  check("TEN03 searching by name filters (client-side) and goes back to page 1; the pager follows the filtered total", names.length > 0 && names.every((n) => /Công ty 5/.test(n)) && !/\/ 63/.test(await p.locator(".pager").innerText().catch(() => "")), `${names.length} rows`);
  await p.getByLabel("Tìm công ty").fill("c-7"); await settle(p, 300);
  check("TEN04 the company CODE is searched too", (await p.locator("[data-testid=tenant-list] tbody tr").count()) >= 1);
  await p.getByLabel("Tìm công ty").fill("không-có-công-ty-này"); await settle(p, 300);
  check("TEN05 no match: a clear message (not an empty table)", /Không có công ty phù hợp/.test(await p.locator("main").innerText()) && (await p.locator("[data-testid=tenant-list]").count()) === 0);
  await p.__ctx.close(); });
await block("scenario 56", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/tenants" }); await settle(p, 500);
  check("TEN06 3 companies: no pager (everything fits one page)", (await p.locator(".pager").count()) === 0 && (await p.locator("[data-testid=tenant-list] tbody tr").count()) === 3);
  await p.__ctx.close(); });

// ===================================================================================================================== M-123 after a successful "Tạo công ty" focus is not lost
await block("scenario 57", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/tenants" }); await settle(p, 500);
  await p.getByRole("button", { name: "+ Tạo công ty" }).click(); await settle(p, 300);
  await p.getByTestId("tenant-name").fill("Công ty Tập Trung"); await p.getByRole("button", { name: "Tạo công ty" }).last().click(); await settle(p, 1000);
  const f = await p.evaluate(() => ({ tag: document.activeElement?.tagName, text: (document.activeElement?.textContent ?? "").slice(0, 40) }));
  check("FOC01 after creating a company the focus is on the new company's heading (not <body>)", f.tag === "H1" && /Công ty Tập Trung/.test(f.text), JSON.stringify(f));
  await p.__ctx.close(); });

// ===================================================================================================================== M-119 / M-120 / M-121 empty answers (the state-matrix tool's 'empty' mode: emptify=1)
const emptyOf = async (path) => { const p = await open({ portal: "platform", me: "sys", start: path, emptify: "1" }); await settle(p, 800); const t = await p.locator("main").innerText(); return { p, t }; };
await block("scenario 58", async () => { const { p, t } = await emptyOf("/platform/costs");
  check("EMP01 costs with nothing in it shows no NaN / undefined and says there is no data", !/NaN|undefined|\[object/.test(t) && /Chưa có số liệu chi phí/.test(t), t.slice(0, 160));
  await p.__ctx.close(); });
await block("scenario 59", async () => { const { p, t } = await emptyOf("/platform/components");
  check("EMP02 components: an empty registry says so", /Chưa có thành phần nào/.test(t));
  await p.__ctx.close(); });
await block("scenario 60", async () => { const { p, t } = await emptyOf("/platform/system");
  check("EMP03 system health: no checks says so", /Chưa có thành phần nào được kiểm tra/.test(t));
  await p.__ctx.close(); });
await block("scenario 61", async () => { const { p, t } = await emptyOf("/platform/settings");
  check("EMP04 settings: no editable policies says so", /Chưa có chính sách nào chỉnh được/.test(t));
  await p.__ctx.close(); });
await block("scenario 62", async () => { const { p, t } = await emptyOf("/platform/ai/usage");
  check("EMP05 AI usage with an empty daily series (M-120) renders: heading, no crash", (await p.locator("h1").count()) === 1 && /Mức sử dụng mô hình/.test(t) && !/Invalid time value/.test(t));
  await p.__ctx.close(); });

// ===================================================================================================================== M-088 secrets do not outlive their use
await block("scenario 63", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/connectors" }); await settle(p, 500);
  await p.getByLabel("Mã", { exact: true }).fill("kho"); await p.getByLabel("Tên", { exact: true }).fill("Kho"); await p.getByLabel("Giá trị xác thực").fill("S3CR3T-VALUE");
  await p.getByLabel("Thao tác cho phép").fill("GET /a b");
  check("SEC01 a path with a space inside is refused (it used to be glued into '/ab' silently)", /đường dẫn không có khoảng trắng/.test(await p.locator("main").innerText()) && (await p.getByRole("button", { name: "Lưu", exact: true }).isDisabled()));
  await p.getByLabel("Thao tác cho phép").fill("GET /a");
  await p.getByRole("button", { name: "Lưu", exact: true }).click(); await settle(p, 500);
  check("SEC02 after a successful save the credential field is EMPTY again (value gone from the input)", (await p.getByLabel("Giá trị xác thực").inputValue()) === "");
  const sent = (await calls(p)).filter((c) => c.method === "PUT" && /admin\/connectors/.test(c.path))[0];
  check("SEC03 the credential was sent exactly once and the operations are the cleaned ones", !!sent && sent.body.authValue === "S3CR3T-VALUE" && JSON.stringify(sent.body.operations) === JSON.stringify([{ method: "GET", path: "/a" }]), JSON.stringify(sent?.body));
  await p.__ctx.close(); });

// ===================================================================================================================== M-020 one click / double click / Enter + click = ONE request
/** the first request stays in flight (`slow`), the second activation arrives before the answer: only one write may have been sent */
const writes = async (p, re, method) => (await calls(p)).filter((c) => c.method === method && re.test(c.path)).length;
await block("scenario 64", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/costs" }); await settle(p, 500);
  await p.evaluate(() => { window.__cfg.slow = "/admin/costs/prices"; }); await p.getByPlaceholder("Đơn giá", { exact: true }).fill("5");
  await p.getByRole("button", { name: "Thêm đơn giá" }).dblclick(); await settle(p, 400);
  check("DBL01 costs: a double click on 'Thêm đơn giá' sends ONE price row", (await writes(p, /costs\/prices$/, "POST")) === 1, `${await writes(p, /costs\/prices$/, "POST")}`);
  await p.__ctx.close(); });
await block("scenario 65", async () => { const p = await open({ portal: "admin", me: "sys", start: "/admin/departments" }); await settle(p, 500);
  await p.evaluate(() => { window.__cfg.slow = "/admin/departments"; }); await p.getByLabel("Tên", { exact: true }).fill("Phòng A");
  await p.getByRole("button", { name: "Thêm", exact: true }).first().dblclick(); await settle(p, 400);
  check("DBL02 departments: a double click on 'Thêm' creates ONE department", (await writes(p, /departments$/, "POST")) === 1);
  await p.__ctx.close(); });
await block("scenario 66", async () => { const p = await open({ portal: "admin", me: "sys", start: "/admin/ai-governance" }); await settle(p, 500);
  await p.evaluate(() => { window.__cfg.slow = "/admin/ai/budgets"; }); await p.getByLabel("Số tiền").fill("10");
  await p.getByRole("button", { name: "Lưu ngân sách" }).dblclick(); await settle(p, 400);
  check("DBL03 AI budget: a double click on 'Lưu ngân sách' sends ONE PUT", (await writes(p, /ai\/budgets$/, "PUT")) === 1);
  await p.evaluate(() => { window.__cfg.slow = "/admin/ai/access"; });
  await p.getByRole("button", { name: "Thêm quy tắc chặn" }).dblclick(); await settle(p, 400);
  check("DBL04 AI access rule: a double click on 'Thêm quy tắc chặn' sends ONE POST", (await writes(p, /ai\/access$/, "POST")) === 1);
  await p.__ctx.close(); });
await block("scenario 67", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/ai/models" }); await settle(p, 600);
  await p.evaluate(() => { window.__cfg.slow = "/admin/ai/pricing"; });
  await p.getByLabel("Mô hình", { exact: true }).selectOption({ index: 1 }); await p.getByLabel("Giá token vào (USD / 1 triệu)").fill("1"); await p.getByLabel("Giá token ra (USD / 1 triệu)").fill("2");
  await p.getByRole("button", { name: "Thêm giá" }).dblclick(); await settle(p, 400);
  check("DBL05 model price: a double click on 'Thêm giá' adds ONE (immutable) price row", (await writes(p, /ai\/pricing$/, "POST")) === 1);
  await p.__ctx.close(); });
await block("scenario 68", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/ai/limits" }); await settle(p, 600);
  await p.evaluate(() => { window.__cfg.slow = "/admin/ai/limits/defaults"; });
  await p.getByRole("button", { name: "Lưu hạn mức mặc định" }).dblclick(); await settle(p, 400);
  check("DBL06 AI default limits: a double click on 'Lưu hạn mức mặc định' sends ONE PUT", (await writes(p, /limits\/defaults$/, "PUT")) === 1);
  await p.__ctx.close(); });
await block("scenario 69", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/packages" }); await settle(p, 500);
  await p.evaluate(() => { window.__cfg.slow = "/admin/packages"; }); await p.getByLabel("Tên package").fill("zod");
  await p.getByRole("button", { name: "Kiểm tra & duyệt" }).first().dblclick(); await settle(p, 400);
  check("DBL07 packages: a double click on 'Kiểm tra & duyệt' sends ONE request", (await writes(p, /admin\/packages$/, "POST")) === 1);
  await p.__ctx.close(); });
await block("scenario 70", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/tenants/t1" }); await settle(p, 500);
  await p.getByTestId("tm-search").fill("cu"); await settle(p, 800); await p.getByTestId("tm-person").selectOption("u9");
  await p.evaluate(() => { window.__cfg.slow = "/admin/tenants/t1/members"; });
  await p.getByTestId("tm-add").dblclick(); await settle(p, 400);
  check("DBL08 company members: a double click on 'Thêm vào công ty' sends ONE PUT", (await writes(p, /tenants\/t1\/members\/u9$/, "PUT")) === 1);
  await p.__ctx.close(); });
await block("scenario 71", async () => { const p = await open({ portal: "admin", me: "tadmin", start: "/admin/my-workspaces" }); await settle(p, 500);
  await p.evaluate(() => { window.__cfg.slow = "/workspaces/w1/members"; }); await p.getByTestId("ws-add-who").fill("nguoi.moi");
  await p.getByRole("button", { name: "Thêm vào workspace" }).dblclick(); await settle(p, 400);
  check("DBL09 workspace members: a double click on 'Thêm vào workspace' sends ONE POST", (await writes(p, /workspaces\/w1\/members$/, "POST")) === 1);
  await p.__ctx.close(); });
await block("scenario 72", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/connectors" }); await settle(p, 500);
  await p.evaluate(() => { window.__cfg.slow = "/admin/connectors"; });
  await p.getByLabel("Mã", { exact: true }).fill("kho"); await p.getByLabel("Tên", { exact: true }).fill("Kho");
  await p.getByRole("button", { name: "Lưu", exact: true }).dblclick(); await settle(p, 400);
  check("DBL10 connectors: a double click on 'Lưu' sends ONE PUT", (await writes(p, /admin\/connectors$/, "PUT")) === 1);
  await p.__ctx.close(); });
await block("scenario 73", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/tenants", slow: "/admin/tenants" }); await settle(p, 500);
  await p.getByRole("button", { name: "+ Tạo công ty" }).click(); await settle(p, 300);
  await p.getByTestId("tenant-name").fill("Công ty Hai Lần");
  await p.keyboard.press("Enter"); await p.getByRole("button", { name: /Tạo công ty|Đang tạo/ }).last().dblclick({ force: true }).catch(() => undefined); await settle(p, 500);
  check("DBL11 create company: Enter + double click sends ONE POST", (await writes(p, /admin\/tenants$/, "POST")) === 1);
  await p.__ctx.close(); });
await block("scenario 74", async () => { const p = await open({ portal: "admin", me: "sys", start: "/admin/identity" }); await settle(p, 500);
  check("DBL12 (control) the page that has no write loads without a request storm", (await calls(p)).filter((c) => c.method !== "GET").length === 0);
  await p.__ctx.close(); });

// ===================================================================================================================== route / navigation snapshot (M-066: the split must not change behaviour)
// For every persona the sidebar labels and, for every section key (deep links, aliases, foreign and unknown keys included), the final path, the h1, the h2s and the kind of state view are recorded in admin-routes.snapshot.json
// (generated from the pre-split code, `UPDATE_SNAPSHOT=1` rewrites it). Dates / numbers are not compared: only structure.
if (!process.env.SKIP_SNAPSHOT) {
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

// ===================================================================================================================== M-031 a disabled action says WHY in visible text (never title-only)
await block("scenario 75", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/users/u-sys" });
  const lock = p.getByRole("button", { name: /Khóa tài khoản/ }); await lock.waitFor();
  const reason = /Bạn không thể tự khóa tài khoản của mình/;
  check("RSN01 your own account: 'Khóa tài khoản' is aria-disabled, FOCUSABLE, has no title, and the reason is visible text tied to it", (await lock.getAttribute("aria-disabled")) === "true" && !(await lock.getAttribute("title")) && reason.test(await text(p)) && !!(await lock.getAttribute("aria-describedby")));
  await lock.focus(); const h0 = (await calls(p)).length; await lock.click({ force: true }); await settle(p, 200);
  check("RSN02 …and activating it sends nothing and opens no dialog", (await calls(p)).length === h0 && (await dlg(p).count()) === 0);
  await p.__ctx.close(); });

// ===================================================================================================================== M-032 a status chip's colour means what it says (warning = amber, high risk = red, not grey)
await block("scenario 76", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/alerts" });
  await p.locator(".pill", { hasText: "WARNING" }).first().waitFor();
  check("PIL01 an alert of severity WARNING is an amber (pill-warn) chip, not grey", /pill-warn/.test(await p.locator(".pill", { hasText: "WARNING" }).first().getAttribute("class")));
  await nav(p, "/platform/settings"); await p.locator(".pill", { hasText: "Rủi ro cao" }).first().waitFor();
  check("PIL02 'Rủi ro cao' is a red (pill-bad) chip, not grey", /pill-bad/.test(await p.locator(".pill", { hasText: "Rủi ro cao" }).first().getAttribute("class")));
  await p.__ctx.close(); });

// ===================================================================================================================== M-028 route switches are links (aria-current), in-page switches are real tabs (arrows, tabpanel)
await block("scenario 77", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/ai/models" });
  await p.getByRole("navigation", { name: "Mục AI" }).waitFor();
  check("TAB01 AI sections are a nav of LINKS: no role=tab, the current one has aria-current=page", (await p.locator("[role=tab]").count()) === 0 && (await p.getByRole("link", { name: "Mô hình", exact: true }).getAttribute("aria-current")) === "page" && (await p.getByRole("link", { name: "Nhà cung cấp", exact: true }).getAttribute("aria-current")) === null);
  await nav(p, "/platform/users"); await p.getByRole("navigation", { name: "Người dùng và workspace" }).waitFor();
  check("TAB02 Users / Workspaces are links with aria-current too", (await p.locator("[role=tab]").count()) === 0 && (await p.getByRole("link", { name: "Người dùng", exact: true }).first().getAttribute("aria-current")) === "page");
  await p.__ctx.close(); });
await block("scenario 78", async () => { const p = await open({ portal: "admin", me: "sys", start: "/admin/applications/a1" });
  const tabs = p.getByRole("tab"); await tabs.first().waitFor();
  const t0 = tabs.first(); await t0.focus(); await p.keyboard.press("ArrowRight"); await settle(p, 200);
  const sel = await p.locator("[role=tab][aria-selected=true]").innerText();
  const panel = p.locator("[role=tabpanel]"); const lab = await panel.getAttribute("aria-labelledby"); const selId = await p.locator("[role=tab][aria-selected=true]").getAttribute("id");
  check("TAB03 application detail: ArrowRight moves to the next tab, which is selected; the tabpanel is labelled by it; only the selected tab is a tab stop", /^Thành viên/.test(sel) && lab === selId && (await p.locator("[role=tab][tabindex='0']").count()) === 1, `${sel} ${lab} ${selId}`);
  check("TAB04 the member count is part of the tab name (read with a comma)", /Thành viên\s*,?\s*\d+/.test(((await p.locator("[role=tab][aria-selected=true]").evaluate((e) => e.textContent)) ?? "").replace(/\s+/g, " ")));
  await p.__ctx.close(); });
await block("scenario 79", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/components" });
  await p.getByRole("tab", { name: "Khối đóng góp" }).click(); await settle(p, 200);
  check("TAB05 components: 'Khối đóng góp' is a selected tab with its own tabpanel", (await p.getByRole("tab", { name: "Khối đóng góp" }).getAttribute("aria-selected")) === "true" && (await p.locator("[role=tabpanel]").count()) === 1);
  await p.__ctx.close(); });

// ===================================================================================================================== M-034 a server error in a dialog is IN VIEW, above the sticky footer
await block("scenario 80", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/tenants" }, { h: 460 });
  await p.evaluate(() => { window.__cfg.failw = "/admin/tenants"; });
  await p.getByRole("button", { name: "+ Tạo công ty" }).click(); await settle(p, 300);
  await p.getByTestId("tenant-name").fill("Công ty Lỗi"); await p.getByRole("button", { name: "Tạo công ty" }).last().click(); await settle(p, 700);
  const geo = await p.evaluate(() => { const a = document.querySelector("[role=dialog] .formError[role=alert]"); const f = document.querySelector("[role=dialog] .xp-footer"); if (!a || !f) return null; const ar = a.getBoundingClientRect(), fr = f.getBoundingClientRect(); return { aTop: ar.top, aBottom: ar.bottom, fTop: fr.top, vh: innerHeight }; });
  check("DLG01 a server refusal in 'Tạo công ty' is an alert that is fully visible: inside the viewport and above the sticky footer", !!geo && geo.aTop >= 0 && geo.aBottom <= geo.fTop + 1 && geo.aBottom <= geo.vh, JSON.stringify(geo));
  await p.__ctx.close(); });

// ===================================================================================================================== M-105 Admin dialogs use the shared header and a plain fieldset (no UA groove, no bare 24px h2)
await block("scenario 81", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/users" });
  await p.getByTestId("users-create").click(); await settle(p, 600);
  const shape = await p.evaluate(() => { const d = document.querySelector("[role=dialog]"); const fs = d.querySelector("fieldset.stack"); const lg = fs.querySelector("legend"); const cs = getComputedStyle(fs), ls = getComputedStyle(lg);
    return { head: d.querySelectorAll(".xp-modalHead h2").length, bare: d.querySelectorAll(".modalBody > h2").length, border: cs.borderTopWidth, legend: ls.fontSize, weight: ls.fontWeight }; });
  check("DLG02 create account: the title is the shared ModalHeader (one h2 in .xp-modalHead, no bare h2), the fieldset has no UA border and a small legend", shape.head === 1 && shape.bare === 0 && shape.border === "0px" && parseFloat(shape.legend) <= 14 && +shape.weight >= 600, JSON.stringify(shape));
  await p.keyboard.press("Escape"); await settle(p, 200); await p.__ctx.close(); });
await block("scenario 82", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/users/u2" });
  await p.getByRole("button", { name: "Đặt lại mật khẩu" }).click(); await settle(p, 400);
  check("DLG03 the activation-link dialog uses the shared ModalHeader", (await p.locator("[role=dialog] .xp-modalHead h2").count()) === 1 && (await p.locator("[role=dialog] .modalBody > h2").count()) === 0);
  await p.__ctx.close(); });

// ===================================================================================================================== M-128 (REAL-STACK finding, HARNESS repro): the sticky dialog footer must never hide a focused control (WCAG 2.4.11)
await block("scenario 85", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/ai/providers" });
  await p.setViewportSize({ width: 360, height: 640 });
  await p.getByRole("button", { name: /Thêm nhà cung/ }).first().click(); await p.locator("[role=dialog]").waitFor(); await p.waitForTimeout(300);
  let hit = false; for (let i = 0; i < 30 && !hit; i++) { await p.keyboard.press("Tab"); hit = await p.evaluate(() => document.activeElement?.classList.contains("xp-advBtn")); } await p.waitForTimeout(250);
  const g = await p.evaluate(() => { const a = document.querySelector(".xp-advBtn").getBoundingClientRect(), f = document.querySelector(".xp-footer").getBoundingClientRect(); return { advBottom: Math.round(a.bottom), advTop: Math.round(a.top), footerTop: Math.round(f.top) }; });
  check("DLG04 at 360x640 the provider dialog's 'Nâng cao' reached by Tab is fully above the sticky footer (focus not obscured; the footer only hides it at the resting scroll position)", hit && g.advBottom <= g.footerTop && g.advTop >= 0, JSON.stringify(g));
  await p.__ctx.close(); });

// ===================================================================================================================== M-095 / M-096 placeholders in the nav are marked; the AI page says it is platform-wide
await block("scenario 83", async () => { const p = await open({ portal: "admin", me: "tadmin", start: "/admin" });
  const nav = (await p.locator("aside nav").innerText()).replace(/\s+/g, " ");
  check("NAV01 the admin nav marks the sections that do not exist yet (Nhóm, Chia sẻ, AI riêng của công ty are 'Sắp có'); a real section is not marked", /Nhóm Sắp có/.test(nav) && /Chia sẻ Sắp có/.test(nav) && /AI riêng của công ty Sắp có/.test(nav) && !/Nhân viên Sắp có/.test(nav), nav);
  await p.__ctx.close(); });
await block("scenario 84", async () => { const p = await open({ portal: "platform", me: "sys", start: "/platform/ai/providers" });
  await p.getByRole("heading", { level: 1, name: "AI" }).waitFor();
  const t = await text(p);
  check("AI01 the Platform AI page says the configuration is platform-wide (not 'cho cả công ty')", /toàn nền tảng/.test(t) && !/cho cả công ty/.test(t));
  await p.__ctx.close(); });

check("no console error / warning / uncaught exception in any page", errors.length === 0, errors.slice(0, 3).join(" | "));
await browser.close();
finish();
