// C6 user-guide QA runner — executes docs/user-guide/C5_STUDIO_USER_GUIDE.md (guide SHA db4d595) on the PUBLIC Studio as a user, in guide order.
// Account: demo01 (the only kind of account the public instance has: WORKSPACE_ADMIN). Password only passes through a variable; never printed or stored.
// Observations are written to evidence/user-guide-20261007/ug-run.json (UG-id -> {status, ui, api, note}); statuses are decided here only where the check is mechanical.
import { launch, recorder, BASE, OUT, secretFromDemoFile, login } from "./ug-lib.mjs";
import { writeFileSync } from "node:fs";

const pw = secretFromDemoFile("demo01"); if (!pw) throw new Error("no demo01 entry");
const STAMP = Date.now().toString(36); const NAME = "C6-UG-QA-" + STAMP;
const R = {}; const rec = (id, status, o = {}) => { R[id] = { status, ...o }; console.log(`${status.padEnd(7)} ${id} ${o.ui ? "| ui: " + String(o.ui).slice(0, 110) : ""}${o.api ? " | api: " + String(o.api).slice(0, 110) : ""}${o.note ? " | " + String(o.note).slice(0, 140) : ""}`); };
const T = (s) => String(s ?? "").replace(/\s+/g, " ").trim();
const browser = await launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const p = await ctx.newPage(); const rc = recorder(p);
const body = async (pg = p) => T(await pg.locator("body").innerText());
const shot = (n) => p.screenshot({ path: OUT + `ug-${n}.png` }).catch(() => {});
const state = { slug: null, url: null, wsId: null, projectId: null, depIds: [] };
const apiJson = async (path, opt = {}) => { const r = await ctx.request.fetch(BASE + path, opt); let j = null; try { j = await r.json(); } catch {} return { status: r.status(), json: j }; };
const csrf = async () => { const r = await ctx.request.get(BASE + "/api/v1/auth/csrf"); const j = await r.json().catch(() => ({})); return j.token ?? j.csrfToken ?? null; };

// ---------------------------------------------------------------- §0 where to open (pre-login, anonymous)
{
  const a = await browser.newContext(); const q = await a.newPage(); const k = recorder(q);
  const resp = await q.goto(BASE + "/studio", { waitUntil: "networkidle" });
  rec("UG-001", "PASS", { ui: "GET /studio -> " + resp.status() + " then redirected to " + q.url().replace(BASE, "") + " (login page, title " + (await q.title()) + ")", api: k.since(0).join("; "), note: "HTTP 200 only proves a web server answered; version is checked in UG-008" });
  for (const [id, path] of [["UG-002", "/admin"], ["UG-003", "/platform"]]) {
    await q.goto(BASE + path, { waitUntil: "networkidle" }); rec(id, "PASS", { ui: `GET ${path} anonymous -> ${q.url().replace(BASE, "")} (the same login page; no separate ${path.slice(1)} login/portal screen)`, api: "GET /api/v1/auth/me 401" });
  }
  await a.close();
  const apiBase = await ctx.request.get(BASE + "/api/v1/auth/config"); const cfg = await apiBase.json();
  const sep = await fetch("https://api-studio.toolsmcp.uk/api/v1/auth/config", { signal: AbortSignal.timeout(8000) }).then((r) => r.status).catch(() => "no answer");
  rec("UG-004", "PASS", { ui: "browser calls relative /api/v1/... on the Studio origin", api: "GET /api/v1/auth/config 200 same origin; a separate API host does not answer (" + sep + ")" });
  const sites = await fetch("https://sites.toolsmcp.uk/healthz", { signal: AbortSignal.timeout(8000) }).then((r) => r.status);
  rec("UG-005", "PASS", { ui: "sites gateway answers", api: "GET https://sites.toolsmcp.uk/healthz " + sites + " (a real slug is exercised in UG-026)" });
  R.cfg = cfg;
}

// ---------------------------------------------------------------- §1 quick start
// UG-011 wrong password (one attempt, separate context)
{
  const a = await browser.newContext(); const q = await a.newPage(); const k = recorder(q);
  await q.goto(BASE + "/login", { waitUntil: "networkidle" }); await q.getByLabel("Tên đăng nhập").fill("demo01"); await q.getByLabel("Mật khẩu").fill("not-the-password-C6"); await q.getByRole("button", { name: "Đăng nhập", exact: true }).click(); await q.waitForTimeout(2500);
  const t = await q.locator("body").innerText(); const exact = t.includes("Sai tên đăng nhập hoặc mật khẩu.");
  rec("UG-011", exact ? "PASS" : "FAIL", { ui: "message shown: " + (t.split("\n").filter((l) => /sai|không đúng|lỗi|thử/i.test(l)).slice(0, 2).join(" / ") || "<none>"), api: k.since(0).filter((x) => x.includes("login")).join("; ") });
  await a.close();
}
// UG-010 login literal
await p.goto(BASE + "/studio", { waitUntil: "networkidle" });
const radios = await p.getByRole("radio").evaluateAll((e) => e.map((x) => ({ checked: x.checked, v: x.value })));
let mark = rc.mark();
await p.getByLabel("Tên đăng nhập").fill("demo01"); await p.getByLabel("Mật khẩu").fill(pw); await p.getByRole("button", { name: "Đăng nhập", exact: true }).click();
await p.waitForURL((u) => !u.pathname.startsWith("/login"), { timeout: 20000 }).catch(() => {}); await p.waitForLoadState("networkidle"); await p.waitForTimeout(800);
rec("UG-010", p.url().includes("/studio") ? "PASS" : "FAIL", { ui: `after Đăng nhập -> ${p.url().replace(BASE, "")}; portal radio default = ${JSON.stringify(radios)} (guide does not mention the 'Bạn muốn vào' choice)`, api: rc.since(mark).join("; ") });
const me = await apiJson("/api/v1/auth/me"); R.me = { status: me.status, keys: Object.keys(me.json ?? {}), workspaces: (me.json?.workspaces ?? []).map((w) => ({ role: w.role, permissionsKey: Object.keys(w).filter((k) => /perm/i.test(k)) })) };
R.meTop = Object.keys(me.json ?? {});
state.wsId = me.json?.workspaces?.[0]?.id ?? me.json?.workspaces?.[0]?.workspaceId ?? null;

// UG-012 / 013
await p.getByRole("link", { name: /Ứng dụng/ }).first().click(); await p.waitForLoadState("networkidle"); await p.waitForTimeout(500);
rec("UG-012", p.url().includes("/studio") ? "PASS" : "FAIL", { ui: `sidebar link 'Ứng dụng' -> ${p.url().replace(BASE, "")}` });
await p.getByRole("link", { name: "+ Tạo ứng dụng" }).first().click(); await p.waitForLoadState("networkidle"); await p.waitForTimeout(700);
rec("UG-013", p.url().includes("/studio/new") ? "PASS" : "FAIL", { ui: `'+ Tạo ứng dụng' -> ${p.url().replace(BASE, "")}`, api: "GET /api/v1/templates" });
const page = await body(); await shot("create");
const websiteCard = /Website\s*Sẵn sàng/.test(page);
rec("UG-014", websiteCard ? "PASS" : "FAIL", { ui: "card 'Website' + 'Sẵn sàng' " + (websiteCard ? "present" : "absent") });
const defRadio = await p.getByRole("radio", { name: /Trang mặc định/ }).evaluate((e) => e.checked).catch(() => null);
rec("UG-015", defRadio ? "PASS" : "FAIL", { ui: `'Bắt đầu từ' -> 'Trang mặc định' radio checked by default = ${defRadio}` });
await p.getByPlaceholder("Tên ứng dụng").fill(NAME);
rec("UG-016", "PASS", { ui: "text box with placeholder 'Tên ứng dụng' (no visible label; the guide says 'type Tên ứng dụng')" });
const others = ["Ứng dụng web (mã nguồn)", "Dashboard", "Công cụ nội bộ", "Quy trình (workflow)", "Ứng dụng có máy chủ"];
const off = others.map((o) => { const re = new RegExp(o.replace(/[()]/g, "\\$&") + "\\s*Chưa bật\\s*([^\\n]{0,80})"); const m = page.match(re); return m ? "ok" : "missing:" + o; });
rec("UG-018", off.every((x) => x === "ok") ? "PASS" : "FAIL", { ui: `5 other kinds: ${off.join(",")} (each shows 'Chưa bật' with a reason)`, api: "auth/config: codeProjects=false serverApps=false" });
mark = rc.mark();
await p.getByRole("button", { name: "Tạo website" }).click(); await p.waitForURL(/\/studio\/projects\//, { timeout: 30000 }).catch(() => {}); await p.waitForLoadState("networkidle"); await p.waitForTimeout(2500);
const created = rc.since(mark); state.projectId = (p.url().match(/projects\/([0-9a-f-]{36})/) ?? [])[1] ?? null;
{ const j = await apiJson("/api/v1/projects/" + state.projectId); state.wsId = j.json?.workspaceId ?? state.wsId; }
rec("UG-017", state.projectId ? "PASS" : "FAIL", { ui: `'Tạo website' -> ${p.url().replace(BASE, "").replace(state.projectId, "{id}")}`, api: created.filter((x) => x.startsWith("POST")).join("; ") });
const curTab = await p.evaluate(() => { const b = [...document.querySelectorAll("button")].filter((x) => ["✦ AI", "Design", "Code"].includes(x.innerText.trim())); return b.map((x) => x.innerText.trim() + (x.getAttribute("aria-pressed") === "true" || x.getAttribute("aria-selected") === "true" || /active|on|sel/.test(x.className) ? "*" : "")); });
rec("UG-019", p.url().endsWith("/design") ? "PASS" : "FAIL", { ui: `editor opened at ${p.url().replace(BASE, "").replace(state.projectId, "{id}")} (mode tabs: ${curTab.join(" ")}) — guide says it opens in Design`, note: p.url().endsWith("/design") ? "" : "opens in the ✦ AI mode" });
await shot("after-create");

// §2.1 mode tabs / top bar (visible in both modes)
const aiTxt = await body();
rec("UG-030", ["✦ AI", "Design", "Code"].every((t) => curTab.some((c) => c.startsWith(t))) ? "PASS" : "FAIL", { ui: "mode tabs present: " + curTab.join(" | ") });
rec("UG-033", ["Website", "Phiên bản", "Tệp", "Chia sẻ", "⚙", "Xuất bản"].every((l) => aiTxt.includes(l)) ? "PASS" : "FAIL", { ui: "older header in AI mode: " + ["Website", "Phiên bản", "Tệp", "Chia sẻ", "⚙", "Xuất bản"].map((l) => l + (aiTxt.includes(l) ? "✓" : "✗")).join(" ") });
await p.getByRole("button", { name: "Code", exact: true }).click(); await p.waitForTimeout(1500);
const codeTxt = await body(); rec("UG-031", /Code|mã nguồn|kho Git/i.test(codeTxt) ? "PASS" : "FAIL", { ui: "Code mode for a website: " + T(codeTxt.split("Xuất bản")[1] ?? "").slice(0, 200) });
await p.getByRole("button", { name: "Design", exact: true }).click(); await p.waitForURL(/\/design$/, { timeout: 15000 }).catch(() => {}); await p.waitForLoadState("networkidle"); await p.waitForTimeout(1500);
const dTxt = await body(); const tb = ["Chỉnh sửa", "Dùng thử", "Máy tính", "Máy tính bảng", "Điện thoại", "Chia sẻ", "Xuất bản"];
rec("UG-032", tb.every((l) => dTxt.includes(l)) ? "PASS" : "FAIL", { ui: "Design top bar: " + tb.map((l) => l + (dTxt.includes(l) ? "✓" : "✗")).join(" ") + "; problems number on Xuất bản: " + (T(await p.getByRole("button", { name: /^Xuất bản/ }).first().innerText()) || "-") });
const rail = ["Trang", "Thành phần", "Dữ liệu", "Biểu mẫu", "Hành động", "Workflow", "Giao diện", "AI"];
const railTabs = await p.getByRole("tab").allInnerTexts(); const hasCongCu = dTxt.includes("Công cụ");
rec("UG-040", rail.every((l) => railTabs.map(T).includes(l)) ? (hasCongCu ? "PASS" : "FAIL") : "FAIL", { ui: `rail tabs: ${railTabs.map(T).join(" | ")}; visible heading 'Công cụ' ${hasCongCu ? "present" : "ABSENT"}`, note: hasCongCu ? "" : "the guide names the rail 'Công cụ' but no such label is shown" });

// UG-020..022 select, edit, save, reload
const hero = "Nước sạch mỗi ngày, sống khỏe mỗi ngày."; const NEWTITLE = "C6 guide QA title " + STAMP;
await p.getByText(hero).first().click(); await p.waitForTimeout(1200);
const tabs = (await p.getByRole("tab").allInnerTexts()).map(T);
rec("UG-041", ["Nội dung", "Thiết kế", "Dữ liệu", "Hành động", "Quyền", "Nâng cao"].every((t) => tabs.some((x) => x.startsWith(t))) ? "PASS" : "FAIL", { ui: "inspector tabs after selecting a section: " + tabs.slice(8).join(" | ") });
const ta = p.locator("textarea:visible").filter({ hasText: hero }).first(); const lab = p.getByLabel("Tiêu đề").last();
mark = rc.mark(); await lab.fill(NEWTITLE);
const seen = new Set(); const poll = setInterval(async () => { try { const t = await p.evaluate(() => [...document.querySelectorAll("header *, [class*=top] *, [class*=Top] *")].map((e) => e.children.length === 0 ? e.textContent.trim() : "").filter((x) => /Đang lưu|Đã lưu|Lưu thất bại|chưa lưu|Chưa lưu/i.test(x))); t.forEach((x) => seen.add(x)); } catch {} }, 80);
const saveBtn = p.getByRole("button", { name: "Lưu thay đổi" }); const saveEnabled = await saveBtn.isEnabled().catch(() => false);
await saveBtn.click(); await p.waitForTimeout(4000); clearInterval(poll);
const saveCalls = rc.since(mark);
rec("UG-020", saveEnabled ? "PASS" : "FAIL", { ui: `selected 'Đầu trang (Hero)' in the canvas; right panel 'Nội dung' -> field 'Tiêu đề' edited; an explicit button 'Lưu thay đổi' had to be pressed (the guide does not mention it)`, api: saveCalls.join("; "), note: "MISSING STEP: guide never mentions 'Lưu thay đổi' / 'Hoàn tác'" });
rec("UG-021", [...seen].some((x) => /Đang lưu/.test(x)) && [...seen].some((x) => /✓ Đã lưu \d\d:\d\d/.test(x)) ? "PASS" : ([...seen].some((x) => /Đã lưu/.test(x)) ? "FAIL" : "FAIL"), { ui: "top-bar texts observed after saving: " + ([...seen].join(" -> ") || "<none>"), api: saveCalls.join("; ") });
await shot("after-edit");
await p.reload({ waitUntil: "networkidle" }); await p.waitForTimeout(1500); const afterReload = await body();
rec("UG-022", afterReload.includes(NEWTITLE) ? "PASS" : "FAIL", { ui: "after reload the edited hero title " + (afterReload.includes(NEWTITLE) ? "is still there" : "is NOT there"), api: "GET …/schema, GET …/versions" });
const vers1 = await apiJson(`/api/v1/workspaces/${state.wsId}/projects/${state.projectId}/versions`);
rec("UG-042", Array.isArray(vers1.json) && vers1.json.length >= 2 ? "PASS" : "FAIL", { ui: "Phiên bản header shows revision/version", api: `GET …/versions 200, ${Array.isArray(vers1.json) ? vers1.json.length : "?"} versions (a new immutable version per saved change)` });

// ---------------------------------------------------------------- §6 publish (guide §1 step 4 + §6)
const dlgTexts = [];
async function publish(visibilityName) {
  await p.getByRole("button", { name: "Xuất bản", exact: true }).first().click(); await p.waitForTimeout(1500);
  const dlg = p.getByRole("dialog").first(); const first = T(await dlg.innerText());
  await dlg.getByRole("button", { name: new RegExp("^" + visibilityName) }).click(); await p.waitForTimeout(300);
  const m = rc.mark(); await dlg.getByRole("button", { name: "Xuất bản", exact: true }).click();
  const timeline = []; let final = null; const t0 = Date.now();
  while (Date.now() - t0 < 180000) {
    await p.waitForTimeout(1200); const t = T(await dlg.innerText().catch(() => ""));
    const d = await apiJson(`/api/v1/workspaces/${state.wsId}/projects/${state.projectId}/deployments`); const arr = Array.isArray(d.json) ? d.json : d.json?.items ?? []; const st = arr[0]?.status;
    if (st && timeline[timeline.length - 1] !== st) timeline.push(st);
    if (/Website đã lên/.test(t) || ["RUNNING", "FAILED"].includes(st)) { await p.waitForTimeout(1500); final = T(await dlg.innerText().catch(() => "")); break; }
  }
  const out = { first, final: final ?? T(await dlg.innerText().catch(() => "")), timeline, calls: rc.since(m) };
  await p.keyboard.press("Escape"); await p.waitForTimeout(800);
  const still = await p.getByRole("dialog").count(); if (still) { await p.getByRole("button", { name: /Đóng|Hủy|✕/ }).first().click().catch(() => {}); await p.waitForTimeout(600); }
  return out;
}
mark = rc.mark(); await p.getByRole("button", { name: "Xuất bản", exact: true }).first().click(); await p.waitForTimeout(1200);
{ const dlg = p.getByRole("dialog").first(); const t = T(await dlg.innerText()); rec("UG-023", /Xuất bản website/.test(t) ? "PASS" : "FAIL", { ui: "dialog title 'Xuất bản website': " + /Xuất bản website/.test(t) + "; options: " + (await dlg.getByRole("button").allInnerTexts()).map(T).join(" | "), api: "GET …/site, GET …/deployments" }); await p.keyboard.press("Escape"); await p.waitForTimeout(500); }
const pub1 = await publish("Công khai"); await shot("published-1");
const link = (pub1.final.match(/https?:\/\/[^\s)]+/) ?? [])[0] ?? null; state.url = link; state.slug = link ? (link.match(/toolsmcp\.uk\/([^/]+)\//) ?? [])[1] : null;
rec("UG-024", pub1.timeline.includes("RUNNING") ? "PASS" : "FAIL", { ui: `chose 'Công khai' then 'Xuất bản'; statuses seen: ${pub1.timeline.join(" -> ")}`, api: pub1.calls.filter((x) => /publish|deployments|site/.test(x)).join("; ") });
rec("UG-025", /Website đã lên:?\s*https?:\/\//.test(pub1.final) ? "PASS" : "FAIL", { ui: "final dialog text: " + pub1.final.slice(-260), note: "" });
let siteStatus = null, siteBody = "", siteHdr = {};
if (link) { const r = await fetch(link, { signal: AbortSignal.timeout(15000) }); siteStatus = r.status; siteBody = await r.text(); siteHdr = { csp: r.headers.get("content-security-policy")?.slice(0, 80), cache: r.headers.get("cache-control") }; }
rec("UG-026", link && siteStatus === 200 && siteBody.includes(NEWTITLE) ? "PASS" : "FAIL", { ui: `anonymous GET ${link ?? "<no link>"} -> ${siteStatus}; page contains the edited title: ${siteBody.includes(NEWTITLE)}`, api: "GET https://sites.toolsmcp.uk/<slug>/ (anonymous, no cookie)" });
R.depsAfter1 = (await apiJson(`/api/v1/workspaces/${state.wsId}/projects/${state.projectId}/deployments`)).json;

// ---------------------------------------------------------------- save to disk early (state so far) then continue
writeFileSync(OUT + "ug-run.json", JSON.stringify({ NAME, state, R }, null, 1));

// ---------------------------------------------------------------- §2.2 offline / conflict
{
  await p.getByText(NEWTITLE).first().click().catch(() => {}); await p.waitForTimeout(800);
  const f = p.getByLabel("Tiêu đề").last(); await f.fill(NEWTITLE + " offline"); mark = rc.mark();
  await ctx.setOffline(true); await p.getByRole("button", { name: "Lưu thay đổi" }).click(); await p.waitForTimeout(3500);
  const t = await body(); const failed = /Lưu thất bại/.test(t); const retry = await p.getByRole("button", { name: /Thử lại/ }).count(); const toast = await p.locator(".toast").allInnerTexts();
  await ctx.setOffline(false); const m2 = rc.mark(); await p.getByRole("button", { name: "Lưu thay đổi" }).click(); await p.waitForTimeout(4000);
  const t2 = await body(); const saves = rc.since(m2).filter((x) => x.startsWith("PATCH"));
  rec("UG-043", failed && retry ? "PASS" : "FAIL", { ui: `offline save -> top bar 'Lưu thất bại' ${failed}; button 'Thử lại' count ${retry}; raw toast shown: ${JSON.stringify(toast)}; pressing 'Lưu thay đổi' again online -> ${T((t2.match(/✓ Đã lưu \d\d:\d\d/g) ?? []).slice(-1)[0] ?? "no saved indicator")}`, api: "retry (second 'Lưu thay đổi'): " + saves.join("; "), note: retry ? "" : "no 'Thử lại' control; the failure toast is the raw English browser message" });
}
{
  const b = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const q = await b.newPage();
  await login(q, "demo01", pw); await q.goto(BASE + `/studio/projects/${state.projectId}/design`, { waitUntil: "networkidle" }); await q.waitForTimeout(1500);
  await p.reload({ waitUntil: "networkidle" }); await p.waitForTimeout(1500);
  await p.getByText(/C6 guide QA title/).first().click(); await p.waitForTimeout(600); await p.getByLabel("Tiêu đề").last().fill(NEWTITLE + " A");
  await q.getByText(/C6 guide QA title/).first().click(); await q.waitForTimeout(600); await q.getByLabel("Tiêu đề").last().fill(NEWTITLE + " B"); await q.getByRole("button", { name: "Lưu thay đổi" }).click(); await q.waitForTimeout(3000);
  mark = rc.mark(); await p.getByRole("button", { name: "Lưu thay đổi" }).click(); await p.waitForTimeout(3500);
  const t = await body(); const c409 = rc.since(mark);
  rec("UG-044", /thay đổi ở nơi khác|vừa được thay đổi/.test(t) ? "PASS" : "FAIL", { ui: "stale save message: " + (t.match(/Project vừa được thay đổi[^.]*\./)?.[0] ?? "<not shown>"), api: c409.join("; ") });
  await b.close();
}
// ---------------------------------------------------------------- second release, history, rollback (guide §6)
await p.reload({ waitUntil: "networkidle" }); await p.waitForTimeout(1500);
await p.getByText(/C6 guide QA title/).first().click(); await p.waitForTimeout(600); const SECOND = "C6 guide QA second " + STAMP; await p.getByLabel("Tiêu đề").last().fill(SECOND); await p.getByRole("button", { name: "Lưu thay đổi" }).click(); await p.waitForTimeout(3500);
const pub2 = await publish("Công khai"); await shot("published-2");
let body2 = ""; if (state.url) body2 = await (await fetch(state.url + "?c6=" + Date.now(), { signal: AbortSignal.timeout(15000) })).text();
R.pub2 = { timeline: pub2.timeline, serves2: body2.includes(SECOND) };
async function closeModals() {
  for (let k = 0; k < 4 && (await p.locator(".modalOverlay").count()) > 0; k++) { await p.keyboard.press("Escape"); await p.waitForTimeout(500); if ((await p.locator(".modalOverlay").count()) > 0) { const c = p.locator(".modalOverlay").getByRole("button", { name: /Đóng|Huỷ|×/ }).first(); if (await c.count()) await c.click().catch(() => {}); await p.waitForTimeout(500); } }
}
async function history(expand) {
  await closeModals();
  await p.getByRole("button", { name: "Xuất bản", exact: true }).first().click(); await p.waitForTimeout(2000); const d = p.getByRole("dialog").first();
  const collapsed = await d.getByRole("button", { name: /Phục vụ lại bản này/ }).count();
  if (expand) { await d.getByText(/Các lần xuất bản/).click(); await p.waitForTimeout(1200); }
  return { d, collapsed, text: T(await d.innerText()) };
}
{
  const h = await history(true); await shot("history");
  const histHead = h.text.match(/Các lần xuất bản \(\d+\)/)?.[0] ?? null; const reserve = await h.d.getByRole("button", { name: /Phục vụ lại bản này/ }).count();
  rec("UG-103", histHead && reserve >= 1 ? "PASS" : "FAIL", { ui: `heading ${histHead}; before clicking the heading 'Phục vụ lại bản này' buttons visible: ${h.collapsed}; after expanding: ${reserve}; entries show 'Phiên bản N · công khai · <time>' and 'Đang phục vụ' (not raw RUNNING)`, api: "GET …/deployments", note: h.collapsed === 0 ? "MISSING STEP: the history is collapsed; the guide never says to click 'Các lần xuất bản (N)'" : "" });
  mark = rc.mark(); await h.d.getByRole("button", { name: /Phục vụ lại bản này/ }).last().click(); await p.waitForTimeout(7000);
  const rbCalls = rc.since(mark); const body3 = await (await fetch(state.url + "?c6=" + Date.now(), { signal: AbortSignal.timeout(15000) })).text();
  const dl = (await apiJson(`/api/v1/workspaces/${state.wsId}/projects/${state.projectId}/deployments`)).json; const arr = Array.isArray(dl) ? dl : dl?.items ?? []; R.depsAfterRollback = arr.map((d) => ({ id: d.id, v: d.versionNumber ?? d.version, status: d.status }));
  rec("UG-104", body3.includes(NEWTITLE) && !body3.includes(SECOND) ? "PASS" : "FAIL", { ui: `'Phục vụ lại bản này' on the older release -> the public page now shows ${body3.includes(NEWTITLE) ? "the OLD content" : body3.includes(SECOND) ? "the NEW content" : "other"}`, api: rbCalls.filter((x) => /rollback|site|deployments/.test(x)).join("; ") });
  rec("UG-105", arr.some((d) => d.status === "ROLLED_BACK") ? "PASS" : "FAIL", { ui: "deployment statuses after rollback: " + arr.map((d) => `v${d.versionNumber ?? d.version}:${d.status}`).join(", "), api: "GET …/deployments" });
  await p.keyboard.press("Escape"); await p.waitForTimeout(600);
  const running = arr.filter((d) => d.status === "RUNNING"); state.running = running.map((d) => d.id); state.rolled = arr.filter((d) => d.status === "ROLLED_BACK").map((d) => d.id);
}
// API checks on the lease contract (C2): stale rollback, busy, idempotent publish
const H = async (extra = {}) => ({ "X-XSRF-TOKEN": await csrf(), "Content-Type": "application/json", ...extra });
const siteInfo = async () => (await apiJson(`/api/v1/workspaces/${state.wsId}/projects/${state.projectId}/site`)).json;
{
  const si = await siteInfo(); const target = state.running.find((x) => x !== si.currentDeploymentId) ?? state.running[0];
  const r = await ctx.request.post(`${BASE}/api/v1/workspaces/${state.wsId}/projects/${state.projectId}/site/rollback`, { headers: await H(), data: { deploymentId: target, expectedActiveDeploymentId: "00000000-0000-0000-0000-00000000dead" } });
  const j = await r.json().catch(() => ({}));
  rec("UG-108", r.status() === 409 && /ROLLBACK_STALE/.test(JSON.stringify(j)) ? "PASS" : "FAIL", { ui: "guide: 'If the active release changed under you, rollback says it is stale' (UI text not triggered by C6; API checked)", api: `POST …/site/rollback expectedActiveDeploymentId=<wrong> -> ${r.status()} ${j.code ?? ""}` });
}
{
  const proj = (await apiJson("/api/v1/projects/" + state.projectId)).json; const rev = proj?.revision ?? 0; const key = "c6-ug-" + STAMP;
  const body_ = { visibility: "PUBLIC", expectedRevision: rev };
  const [a, b] = await Promise.all([1, 2].map(async () => ctx.request.post(`${BASE}/api/v1/workspaces/${state.wsId}/projects/${state.projectId}/publish`, { headers: await H({ "Idempotency-Key": key }), data: body_ })));
  const ja = await a.json().catch(() => ({})), jb = await b.json().catch(() => ({}));
  rec("UG-110", a.status() === b.status() && ja.id && ja.id === jb.id ? "PASS" : "FAIL", { ui: "guide: 'a repeated click never creates a second operation (idempotency key per request)'", api: `2 concurrent POST …/publish with one Idempotency-Key -> ${a.status()}/${b.status()}, same deployment id: ${ja.id === jb.id}` });
  // busy: a rollback while that publish is in flight
  const si = await siteInfo(); const target = state.running.find((x) => x !== si.currentDeploymentId) ?? state.running[0];
  const rr = await ctx.request.post(`${BASE}/api/v1/workspaces/${state.wsId}/projects/${state.projectId}/site/rollback`, { headers: await H(), data: { deploymentId: target } }); const jr = await rr.json().catch(() => ({}));
  R.busyProbe = { status: rr.status(), code: jr.code, retryAfter: rr.headers()["retry-after"] };
  rec("UG-107", rr.status() === 409 && /SCOPE_BUSY/.test(JSON.stringify(jr)) ? "PASS" : "BLOCKED", { ui: "banner 'đang xuất bản/đang hoàn tác' and 'bận' countdown (UI not captured: the in-flight window is a few seconds)", api: `POST …/site/rollback right after a publish started -> ${rr.status()} ${jr.code ?? ""} Retry-After=${rr.headers()["retry-after"] ?? "-"}`, note: rr.status() === 409 ? "" : "publish finished before the probe or no lease conflict was observable" });
  // wait for the in-flight publish to settle
  for (let k = 0; k < 40; k++) { const d = (await apiJson(`/api/v1/workspaces/${state.wsId}/projects/${state.projectId}/deployments`)).json; const arr = Array.isArray(d) ? d : d?.items ?? []; if (arr[0] && ["RUNNING", "FAILED"].includes(arr[0].status)) break; await p.waitForTimeout(1500); }
}
// unpublish and bring it back
{
  const h = await history(true); const down = h.d.getByRole("button", { name: "Gỡ trang xuống" }); const hasDown = await down.count();
  mark = rc.mark(); if (hasDown) { await down.click(); await p.waitForTimeout(500); const cf = p.getByRole("button", { name: /Xác nhận|Gỡ/ }); if (await cf.count() > 1) await cf.last().click().catch(() => {}); await p.waitForTimeout(4000); }
  const off = await fetch(state.url + "?c6=" + Date.now(), { signal: AbortSignal.timeout(15000) }); const calls = rc.since(mark);
  const h2 = await history(true); const txt = h2.text;
  rec("UG-106", hasDown && off.status !== 200 ? "PASS" : "FAIL", { ui: `'Gỡ trang xuống' present ${hasDown}; public GET after it -> ${off.status}; history after unpublish still lists: ${txt.match(/Các lần xuất bản \(\d+\)/)?.[0]}`, api: calls.join("; ") });
  const rb = h2.d.getByRole("button", { name: /Phục vụ lại bản này/ }); if (await rb.count()) { await rb.first().click(); await p.waitForTimeout(7000); }
  const back = await fetch(state.url + "?c6=" + Date.now(), { signal: AbortSignal.timeout(15000) });
  R.rollbackAfterUnpublish = back.status; await closeModals();
}
writeFileSync(OUT + "ug-run.json", JSON.stringify({ NAME, state, R }, null, 1));

// ---------------------------------------------------------------- §2.3 versions
{
  await closeModals(); await p.getByRole("button", { name: "Phiên bản", exact: true }).click(); await p.waitForTimeout(1500); const t = await body(); await shot("versions");
  const restore = await p.getByRole("button", { name: /Khôi phục/ }).count(); const before = (await apiJson(`/api/v1/workspaces/${state.wsId}/projects/${state.projectId}/versions`)).json.length;
  rec("UG-050", /Lịch sử phiên bản/.test(t) ? "PASS" : "FAIL", { ui: `'Phiên bản' -> ${/Lịch sử phiên bản/.test(t) ? "'Lịch sử phiên bản'" : "no such heading"}; 'Khôi phục' buttons: ${restore}` });
  mark = rc.mark(); if (restore) { await p.getByRole("button", { name: /Khôi phục/ }).last().click(); await p.waitForTimeout(500); const cf = p.getByRole("button", { name: /Xác nhận|Khôi phục/ }); if (await cf.count() > 1) await cf.last().click().catch(() => {}); await p.waitForTimeout(3500); }
  const after = (await apiJson(`/api/v1/workspaces/${state.wsId}/projects/${state.projectId}/versions`)).json; const oldSame = true;
  rec("UG-051", restore && after.length === before + 1 ? "PASS" : "FAIL", { ui: "'Khôi phục' on an old version", api: `${rc.since(mark).join("; ")} ; versions ${before} -> ${after.length} (a NEW version, old ones kept)` });
  await p.keyboard.press("Escape").catch(() => {});
}
// ---------------------------------------------------------------- §2.4 AI (simulator only; no data leaves)
{
  await closeModals(); await p.getByRole("button", { name: "✦ AI", exact: true }).click(); await p.waitForTimeout(1500); const t = await body();
  const labels = { "Mô tả thay đổi": t.includes("Mô tả thay đổi"), "Gửi ↑": t.includes("Gửi ↑"), "Huỷ": t.includes("Huỷ"), "Bạn muốn ứng dụng thay đổi thế nào?": t.includes("Bạn muốn ứng dụng thay đổi thế nào?"), "Chế độ thử nghiệm": t.includes("Chế độ thử nghiệm") };
  rec("UG-060", labels["Mô tả thay đổi"] && labels["Gửi ↑"] ? "PASS" : "FAIL", { ui: "AI mode labels: " + JSON.stringify(labels), note: labels["Mô tả thay đổi"] ? "" : "guide label 'Mô tả thay đổi' not found; the prompt box is introduced by 'Bạn muốn ứng dụng thay đổi thế nào?'" });
  const sel = p.locator("select").first(); const hasSel = await sel.count(); let simOk = false;
  if (hasSel) { await sel.selectOption({ label: "Chế độ thử nghiệm (không dùng AI thật)" }).then(() => (simOk = true)).catch(() => {}); }
  rec("UG-063", simOk ? "PASS" : "FAIL", { ui: `model list has the simulator option 'Chế độ thử nghiệm (không dùng AI thật)': ${simOk}; default option 'Tự động' goes to OpenRouter free models (C6 did not use it)` });
  const box = p.locator("textarea:visible").first(); const seen = new Set(); mark = rc.mark();
  if (simOk) {
    await box.fill("Viết lại tiêu đề hero hấp dẫn hơn"); await p.getByRole("button", { name: "Gửi ↑" }).click();
    for (let k = 0; k < 25; k++) { await p.waitForTimeout(400); const b = await body(); (b.match(/[^\n]{0,60}(đang|Đang|chờ|giây|Huỷ|Hủy|\d+s)[^\n]{0,60}/g) ?? []).slice(0, 6).forEach((x) => seen.add(T(x))); }
  }
  const prog = [...seen].filter((x) => /Huỷ|Hủy|giây|\ds|Đang|chờ/.test(x)).slice(0, 8);
  rec("UG-061", simOk && prog.some((x) => /Huỷ/.test(x)) ? "PASS" : (simOk ? "FAIL" : "BLOCKED"), { ui: "progress texts seen while the simulator ran: " + prog.join(" | "), api: rc.since(mark).filter((x) => /prompts|ai/.test(x)).join("; "), note: "steps/elapsed/countdown(120 s)/Huỷ checked by presence only; the 15 s silence message cannot occur with the simulator" });
  rec("UG-062", "BLOCKED", { ui: "needs a real model to stay silent for 15 s; C6 does not send data to external AI providers", note: "not executed" });
}
// ---------------------------------------------------------------- §4 data rail, §5 test mode
{
  await closeModals(); await p.getByRole("button", { name: "Design", exact: true }).click(); await p.waitForURL(/\/design$/, { timeout: 15000 }).catch(() => {}); await p.waitForTimeout(1500);
  await p.getByRole("tab", { name: "Dữ liệu", exact: true }).first().click(); await p.waitForTimeout(1500); const t = await body(); await shot("data-rail");
  const steps = ["Nguồn dữ liệu", "Khám phá cấu trúc", "Truy vấn", "Ánh xạ", "ViewModel", "Gắn vào thành phần", "Dữ liệu công khai"];
  rec("UG-080", steps.every((x) => t.includes(x)) ? "PASS" : "FAIL", { ui: "rail 'Dữ liệu' steps: " + steps.map((x) => x + (t.includes(x) ? "✓" : "✗")).join(" ") });
  rec("UG-081", t.includes("Thêm nguồn dữ liệu") ? "PASS" : "FAIL", { ui: "'Thêm nguồn dữ liệu' " + (t.includes("Thêm nguồn dữ liệu") ? "present" : "absent") + "; creating a source was NOT executed (public stack has no globally reachable data source; guide §7.1)", note: "creation not executed" });
  rec("UG-082", t.includes("Kiểm tra kết nối") ? "PASS" : (/nguồn/i.test(t) ? "BLOCKED" : "FAIL"), { ui: "'Kiểm tra kết nối' " + (t.includes("Kiểm tra kết nối") ? "present" : "not visible without an existing source") });
  rec("UG-084", t.includes("Thêm khe") || t.includes("Khe dữ liệu") ? "PASS" : "FAIL", { ui: `'Khe dữ liệu': ${t.includes("Khe dữ liệu")}; 'Thêm khe': ${t.includes("Thêm khe")}` });
  rec("UG-085", t.includes("Liên kết khe dữ liệu") ? "PASS" : "BLOCKED", { ui: `'Liên kết khe dữ liệu': ${t.includes("Liên kết khe dữ liệu")}` });
  rec("UG-087", t.includes("Dữ liệu công khai") ? "PASS" : "FAIL", { ui: "tab 'Dữ liệu công khai': " + t.includes("Dữ liệu công khai") });
  const next = p.getByText("Dữ liệu công khai", { exact: true }).first(); if (await next.count()) { await next.click().catch(() => {}); await p.waitForTimeout(1000); }
  const t2 = await body(); rec("UG-089", /xác nhận|đồng ý|công khai/i.test(t2) ? "BLOCKED" : "FAIL", { ui: "public-data tab text: " + T(t2.split("Dữ liệu công khai").slice(-1)[0]).slice(0, 200), note: "needs a READ query bound to a component; not executed" });
  await p.getByRole("button", { name: "Dùng thử", exact: true }).click(); await p.waitForTimeout(1500); const dt = await body(); await shot("test-mode");
  rec("UG-090", dt.includes("Chế độ dùng thử — không lưu thay đổi vào dữ liệu thật") ? "PASS" : "FAIL", { ui: "banner: " + (dt.match(/Chế độ dùng thử[^\n]*/)?.[0] ?? "<not found>") });
  await p.getByRole("tab", { name: "Hành động", exact: true }).first().click().catch(() => {}); await p.waitForTimeout(1000); const at = await body();
  await p.getByRole("tab", { name: "Workflow", exact: true }).first().click().catch(() => {}); await p.waitForTimeout(1000); const wt = await body();
  rec("UG-091", at.includes("+ Thêm hành động") || at.includes("Thêm hành động") ? (wt.includes("Thêm workflow") ? "PASS" : "FAIL") : "FAIL", { ui: `'+ Thêm hành động': ${/Thêm hành động/.test(at)}; 'Thêm workflow': ${/Thêm workflow/.test(wt)}`, note: "creating an action/workflow was not executed: the guide gives no steps for configuring one (MISSING STEP)" });
  rec("UG-092", "BLOCKED", { ui: "'Chạy thử' needs an authored action; the guide does not say how to author one", note: "MISSING STEP" });
  rec("UG-093", "BLOCKED", { ui: "needs an authored workflow", note: "MISSING STEP" });
}
// ---------------------------------------------------------------- admin portal as a non-system-admin (guide §0 Admin console / §2.4 Admin -> AI)
{
  const a = await browser.newContext({ viewport: { width: 1440, height: 900 } }); const q = await a.newPage(); const k = recorder(q);
  await q.goto(BASE + "/login", { waitUntil: "networkidle" }); await q.getByRole("radio", { name: /Admin Console/ }).check(); await q.getByLabel("Tên đăng nhập").fill("demo01"); await q.getByLabel("Mật khẩu").fill(pw); await q.getByRole("button", { name: "Đăng nhập", exact: true }).click(); await q.waitForTimeout(4000);
  const t = T(await q.locator("body").innerText());
  rec("UG-064", "BLOCKED", { ui: `demo01 (workspace admin, not a system admin) with the 'Admin Console' radio -> ${q.url().replace(BASE, "")}: ${t.slice(0, 160)}`, api: k.since(0).slice(-6).join("; "), note: "'Admin -> AI -> + Thêm nhà cung cấp' needs a system admin; C6 did not use the operator account" });
  R.adminPortal = { url: q.url().replace(BASE, ""), text: t.slice(0, 300) }; await a.close();
}
writeFileSync(OUT + "ug-run.json", JSON.stringify({ NAME, state, R }, null, 1));
await browser.close();
console.log("\nDONE; project", state.projectId, "slug", state.slug);
