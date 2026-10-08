#!/usr/bin/env node
// Phone builder check (C6 UX-002b / UX-003) on a REAL stack, nothing mocked: at 390 / 430 the builder shows ONE workspace at a time (Bản xem trước | Công cụ | Thuộc tính). Measures usable sizes, state preservation,
// keyboard / focus, overflow and axe (all impacts) for every view, and that 768 / 1024 / 1440 keep their layout (the switch is hidden, all panes visible). Screenshots go to --out. A developer tool (NOT a test).
//   node scripts/ui-c6-mobile.mjs --out <dir>      env: AUDIT_STACK_ENV, AUDIT_STUDIO, CHROME
import { createRequire } from "node:module";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { Session, randomSecret } from "../tests/e2e-real/lib/api.mjs";
const require = createRequire(new URL("../package.json", import.meta.url).pathname);
const { chromium } = require("playwright-core"); const AXE = require.resolve("axe-core/axe.min.js");
const OUT = (() => { const i = process.argv.indexOf("--out"); return i > 0 ? process.argv[i + 1] : "/tmp/c6-mobile"; })(); mkdirSync(OUT, { recursive: true });
const ENV = Object.fromEntries(readFileSync(process.env.AUDIT_STACK_ENV ?? `${process.env.HOME}/.xweb-e2e-stack/c5e2e-ae/stack.env`, "utf8").split("\n").filter((l) => l.includes("=")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const STUDIO = process.env.AUDIT_STUDIO ?? "http://127.0.0.1:3086"; const run = Date.now().toString(36);
const results = []; const check = (n, ok, d = "") => { results.push({ name: n, ok: !!ok, detail: d }); console.log(`${ok ? "PASS" : "FAIL"}  ${n}${d ? "  — " + d : ""}`); };
const info = (k, v) => { results.push({ name: k, info: v }); console.log(`INFO  ${k}: ${typeof v === "string" ? v : JSON.stringify(v)}`); };
const must = (r, w) => { if (r.status < 200 || r.status >= 300) throw new Error(`${w}: ${r.status} ${r.body?.code ?? ""}`); return r.body; };
const sys = new Session(STUDIO, "sys"); await sys.login("local.admin", ENV.LOCAL_ADMIN_PASSWORD);
const tenant = must(await sys.post("/admin/tenants", { slug: `mob-${run}`, name: "Công ty Cổ phần Ánh Dương" }), "tenant");
const ws = must(await sys.post(`/admin/tenants/${tenant.id}/workspaces`, { name: "Kinh doanh" }), "ws");
const l = must(await sys.post(`/admin/tenants/${tenant.id}/users`, { username: `mob-${run}`, displayName: "Trần Văn Ưu Tú", tenantRole: "MEMBER", workspaceId: ws.id, workspaceRole: "WORKSPACE_ADMIN" }), "user");
const pw = randomSecret(); must(await new Session(STUDIO, "a").post("/auth/activation/complete", { token: l.token, password: pw }), "activate");
const u = new Session(STUDIO, "u"); await u.login(`mob-${run}`, pw);
const proj = must(await u.post(`/workspaces/${ws.id}/projects`, { name: "Trang giới thiệu sản phẩm mới của Công ty Cổ phần Ánh Dương", appType: "PAGE_SCHEMA" }), "project");
const sc = must(await u.get(`/workspaces/${ws.id}/projects/${proj.id}/schema`), "schema");
const fx = await u.patch(`/workspaces/${ws.id}/projects/${proj.id}/schema`, { expectedRevision: sc.revision, summary: "mobile fixture", operations: [
  { type: "ADD_DATA_SOURCE", definitionId: "erp", definition: { id: "erp", name: "ERP", type: "postgres" } },
  { type: "ADD_QUERY", definitionId: "q1", definition: { id: "q1", name: "Sản phẩm", dataSourceRef: "erp", operationKey: "list" } },
  { type: "ADD_MAPPING", definitionId: "m1", definition: { id: "m1", name: "Ánh xạ không biến đổi", queryRef: "q1", errorPolicy: "NULL_FIELD", fields: [{ from: "price", to: "price" }, { from: "name", to: "name" }] } }] });
info("fixture (query + mapping without transforms)", fx.status);
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
const axe = async (p) => { await p.addScriptTag({ path: AXE }); return p.evaluate(async () => (await window.axe.run(document, { runOnly: ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"], resultTypes: ["violations"] })).violations.map((v) => `${v.id}:${v.impact}(${v.nodes.length})`)); };
async function open(w, h) {
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, hasTouch: w <= 760, isMobile: false }); const p = await ctx.newPage(); p.setDefaultTimeout(15000);
  const errs = []; p.on("pageerror", (e) => errs.push(e.message.slice(0, 120))); p.on("console", (m) => { if (m.type() === "error" && !/favicon|Failed to load resource/.test(m.text())) errs.push(m.text().slice(0, 120)); });
  await p.goto(`${STUDIO}/login`, { waitUntil: "networkidle" }); await p.getByLabel("Tên đăng nhập").fill(`mob-${run}`); await p.getByLabel("Mật khẩu").fill(pw);
  await Promise.all([p.waitForURL((x) => !/\/login/.test(x.pathname)), p.getByRole("button", { name: "Đăng nhập" }).click()]); await p.waitForLoadState("networkidle").catch(() => undefined);
  await p.goto(`${STUDIO}/studio/projects/${proj.id}/design`, { waitUntil: "domcontentloaded" }); await p.waitForSelector("iframe", { timeout: 20000 }); await p.waitForTimeout(900);
  return { ctx, p, errs };
}
const vis = (p, sel) => p.evaluate((s) => { const e = document.querySelector(s); if (!e) return null; const r = e.getBoundingClientRect(); const cs = getComputedStyle(e); return { shown: cs.display !== "none" && r.width > 0 && r.height > 0, w: Math.round(r.width), h: Math.round(r.height), top: Math.round(r.top) }; }, sel);
const overflow = (p) => p.evaluate(() => document.documentElement.scrollWidth - innerWidth);
// controls (buttons, fields) that are in the visible workspace but beyond the right edge or smaller than 24 px
const bad = (p, root) => p.evaluate((sel) => { const r = document.querySelector(sel); if (!r) return ["no root"]; const out = []; for (const e of r.querySelectorAll("button,input:not([type=hidden]),select,textarea,[role=tab]")) { const b = e.getBoundingClientRect(); if (!b.width || !b.height || getComputedStyle(e).visibility === "hidden") continue; if (e.classList.contains("srOnly")) continue; if (b.right > innerWidth + 1 || b.left < -1) out.push(`outside:${(e.getAttribute("aria-label") || e.textContent || e.tagName).trim().slice(0, 24)}`); else if (b.width < 24 || b.height < 24) out.push(`small:${(e.getAttribute("aria-label") || e.textContent || e.tagName).trim().slice(0, 24)} ${Math.round(b.width)}x${Math.round(b.height)}`); } return out.slice(0, 6); }, root);
const view = (p, name) => p.getByRole("tab", { name, exact: name === "Bản xem trước" || name === "Công cụ" }).first();

for (const w of [390, 430]) {
  const h = w === 390 ? 844 : 932; const { ctx, p, errs } = await open(w, h); const T = `@${w}`;
  const tabs = await p.evaluate(() => [...document.querySelectorAll('.bx-mview [role=tab]')].map((t) => ({ n: t.textContent.trim(), sel: t.getAttribute("aria-selected"), h: Math.round(t.getBoundingClientRect().height), w: Math.round(t.getBoundingClientRect().width) })));
  check(`${T} the switch shows 3 named tabs, Bản xem trước selected, each ≥ 44 px high`, tabs.length === 3 && tabs[0].sel === "true" && tabs.every((t) => t.h >= 44 && t.w >= 100), JSON.stringify(tabs));
  const c = await vis(p, ".bx-center"); const lf = await vis(p, ".bx-left"); const rt = await vis(p, ".bx-right");
  check(`${T} canvas view: the canvas is the ONLY workspace shown and is ≥ 60 % of the screen high`, c?.shown && !lf?.shown && !rt?.shown && c.h >= h * 0.6, JSON.stringify({ c, lf: lf?.shown, rt: rt?.shown }));
  check(`${T} canvas view: no horizontal overflow`, (await overflow(p)) <= 0, `overflow=${await overflow(p)}`);
  await p.screenshot({ path: join(OUT, `${w}-canvas.png`) });
  // iframe identity to prove the canvas never reloads
  const fr = () => p.frames().find((x) => x !== p.mainFrame()); await fr().evaluate(() => { window.__alive = 7; }); await p.evaluate(() => { window.__frame = document.querySelector("iframe"); });
  // tools
  await view(p, "Công cụ").click(); await p.waitForTimeout(300);
  const tl = await vis(p, ".bx-left"); const panel = await vis(p, ".bx-left-panel"); const ce = await vis(p, ".bx-center");
  check(`${T} tools view: the tools pane is full width (≥ ${w - 24} px), the panel content is ≥ ${w - 24} px wide and the canvas is hidden`, tl?.shown && !ce?.shown && tl.w >= w - 4 && panel.w >= w - 24, JSON.stringify({ tl, panel }));
  check(`${T} tools view: the rail tabs are readable (all 8, each ≥ 36 px high)`, await p.evaluate(() => { const t = [...document.querySelectorAll(".bx-left > .bx-tabs [role=tab]")]; return t.length === 8 && t.every((x) => x.getBoundingClientRect().height >= 36 && x.scrollWidth <= x.clientWidth + 1); }));
  for (const [rail, file] of [["Dữ liệu", "data"], ["Hành động", "action"], ["Workflow", "workflow"], ["Trang", "pages"]]) {
    await p.locator(".bx-left > .bx-tabs").getByRole("tab", { name: rail, exact: true }).click(); await p.waitForTimeout(450);
    const pn = await vis(p, ".bx-left-panel"); const pb = await bad(p, ".bx-left-panel");
    check(`${T} ${rail}: panel ≥ ${w - 24} px wide and tall enough to work (≥ 250 px), no control outside the screen or < 24 px, no page overflow`, pn.w >= w - 24 && pn.h >= 250 && pb.length === 0 && (await overflow(p)) <= 0, JSON.stringify({ pn, pb, overflow: await overflow(p) }));
    await p.screenshot({ path: join(OUT, `${w}-tools-${file}.png`) });
    if (file === "data" || file === "action" || file === "workflow") { const v = await axe(p); check(`${T} ${rail}: axe (all impacts) 0 violations`, v.length === 0, v.join(",")); }
  }
  // state preservation: type in the Data wizard, leave, come back
  await p.locator(".bx-left > .bx-tabs").getByRole("tab", { name: "Dữ liệu", exact: true }).click(); await p.waitForTimeout(300);
  const step = p.locator(".bx-left-panel").getByRole("tab", { name: "Truy vấn", exact: true }); if (await step.count()) { await step.first().click(); await p.waitForTimeout(250); }
  const field = p.locator(".bx-left-panel input:not([type=checkbox]):not([type=radio]):not([type=hidden])").first(); await field.fill("giữ-lại-123");
  await view(p, "Bản xem trước").click(); await p.waitForTimeout(250);
  const alive = (await p.evaluate(() => document.querySelector("iframe") === window.__frame)) && (await fr().evaluate(() => window.__alive === 7));
  await view(p, "Công cụ").click(); await p.waitForTimeout(250);
  const kept = await p.evaluate(() => { const i = document.querySelector(".bx-left-panel input:not([type=checkbox]):not([type=radio]):not([type=hidden])"); const sel = document.querySelector('.bx-left > .bx-tabs [aria-selected="true"]'); return { value: i?.value, rail: sel?.textContent.trim() }; });
  check(`${T} state: Canvas → Data (typed) → Canvas → Tools keeps the typed text, the active rail and does NOT reload the canvas`, alive && kept.value === "giữ-lại-123" && kept.rail === "Dữ liệu", JSON.stringify({ alive, kept }));
  // selection + inspector
  await p.locator(".bx-left > .bx-tabs").getByRole("tab", { name: "Trang", exact: true }).click(); await p.waitForTimeout(250);
  await p.getByText("Đầu trang (Hero)").first().click(); await p.waitForTimeout(300);
  const badge = await p.evaluate(() => document.querySelector('.bx-mview [role=tab]:nth-child(3)').textContent.trim());
  check(`${T} selecting a section marks the Thuộc tính tab (badge) without leaving the tools view`, /●/.test(badge) && (await vis(p, ".bx-left"))?.shown, badge);
  await view(p, "Thuộc tính").click(); await p.waitForTimeout(350);
  const ins = await vis(p, ".bx-right"); const ib = await bad(p, ".bx-right");
  check(`${T} properties view: Inspector full width (≥ ${w - 24} px), tall (≥ 400 px), controls inside the screen and ≥ 24 px`, ins.shown && ins.w >= w - 4 && ins.h >= 400 && ib.length === 0, JSON.stringify({ ins, ib }));
  await p.screenshot({ path: join(OUT, `${w}-properties.png`) });
  const iv = await axe(p); check(`${T} properties: axe (all impacts) 0 violations`, iv.length === 0, iv.join(","));
  for (const t of ["Nội dung", "Thiết kế", "Dữ liệu", "Hành động", "Quyền", "Nâng cao"]) { const tab = p.locator(".bx-right").getByRole("tab", { name: t, exact: true }); if (await tab.count()) { await tab.first().click(); await p.waitForTimeout(150); if ((await overflow(p)) > 0) check(`${T} inspector tab ${t}: no overflow`, false, `overflow=${await overflow(p)}`); } }
  check(`${T} every Inspector tab opens without page overflow`, (await overflow(p)) <= 0);
  await view(p, "Bản xem trước").click(); await p.waitForTimeout(250);
  check(`${T} the selection survives going back to the canvas (the tab keeps its badge)`, /●/.test(await p.evaluate(() => document.querySelector('.bx-mview [role=tab]:nth-child(3)').textContent)));
  const cv = await axe(p); check(`${T} canvas: axe (all impacts) 0 violations`, cv.length === 0, cv.join(","));
  // keyboard
  await view(p, "Bản xem trước").focus(); await p.keyboard.press("ArrowRight"); await p.waitForTimeout(250);
  const kb = await p.evaluate(() => ({ active: document.activeElement?.textContent.trim(), sel: document.querySelector('.bx-mview [aria-selected="true"]')?.textContent.trim(), tools: getComputedStyle(document.querySelector(".bx-left")).display, outline: getComputedStyle(document.activeElement).outlineStyle + " " + getComputedStyle(document.activeElement).outlineWidth }));
  check(`${T} keyboard: ArrowRight moves selection AND focus to Công cụ, the tools pane shows, the focus ring is visible`, kb.active === "Công cụ" && kb.sel === "Công cụ" && kb.tools !== "none" && /solid [1-9]/.test(kb.outline), JSON.stringify(kb));
  await p.keyboard.press("End"); await p.waitForTimeout(200); const end = await p.evaluate(() => document.activeElement?.textContent.trim()); await p.keyboard.press("Home"); await p.waitForTimeout(200); const home = await p.evaluate(() => document.activeElement?.textContent.trim());
  check(`${T} keyboard: End → last tab, Home → first tab`, /^Thuộc tính/.test(end) && home === "Bản xem trước", `${end} / ${home}`);
  // skip link
  await p.evaluate(() => { document.activeElement?.blur(); window.scrollTo(0, 0); }); let reached = false; for (let i = 0; i < 40 && !reached; i++) { await p.keyboard.press("Tab"); reached = await p.evaluate(() => document.activeElement?.classList.contains("bx-skip")); }
  if (reached) { await view(p, "Công cụ").click().catch(() => undefined); }
  check(`${T} the skip link is reachable by Tab`, reached);
  check(`${T} no uncaught error / console error during the whole run`, errs.length === 0, errs.join(" | "));
  await ctx.close();
}
// no regression above the phone breakpoint
for (const w of [768, 1024, 1440]) {
  const { ctx, p, errs } = await open(w, w === 1440 ? 900 : 800);
  const m = await p.evaluate(() => { const g = (s) => { const e = document.querySelector(s); if (!e) return null; const r = e.getBoundingClientRect(); return { shown: getComputedStyle(e).display !== "none" && r.width > 0, w: Math.round(r.width), h: Math.round(r.height) }; }; return { mview: getComputedStyle(document.querySelector(".bx-mview")).display, left: g(".bx-left"), center: g(".bx-center"), right: g(".bx-right"), cols: getComputedStyle(document.querySelector(".bx-body")).gridTemplateColumns, overflow: document.documentElement.scrollWidth - innerWidth, bodyOverflow: getComputedStyle(document.body).overflow }; });
  check(`@${w} no regression: the phone switch is hidden and the canvas, tools and properties are all shown`, m.mview === "none" && m.left.shown && m.center.shown && m.right.shown && m.overflow <= 0, JSON.stringify(m));
  await p.screenshot({ path: join(OUT, `${w}-builder.png`) });
  const v = await axe(p); check(`@${w} axe (all impacts) 0 violations`, v.length === 0, v.join(","));
  check(`@${w} no console error`, errs.length === 0, errs.join(" | ")); await ctx.close();
}
await browser.close(); writeFileSync(join(OUT, "mobile-results.json"), JSON.stringify(results, null, 1));
const failed = results.filter((r) => r.ok === false); console.log(`\n${results.filter((r) => r.ok !== undefined).length - failed.length}/${results.filter((r) => r.ok !== undefined).length} checks passed`); process.exit(failed.length ? 1 : 0);
