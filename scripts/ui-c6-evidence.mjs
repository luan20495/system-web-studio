#!/usr/bin/env node
// Evidence for the C6 QA findings on a real stack (nothing mocked): UX-001 (Studio Data with a stored mapping that has no transforms[]), UX-002 / UX-003 / UX-006 / UX-008 (builder at 1440 / 768 / 430 / 390: toolbar, rail, small targets),
// UX-005 (Costs table reachable and scrollable by keyboard) and the accessible names of the device switch. A developer tool (NOT a test).
//   node scripts/ui-c6-evidence.mjs --out <dir>      env: AUDIT_STACK_ENV, AUDIT_STUDIO, AUDIT_PLATFORM, CHROME
import { createRequire } from "node:module";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { Session, randomSecret } from "../tests/e2e-real/lib/api.mjs";
const require = createRequire(new URL("../package.json", import.meta.url).pathname);
const { chromium } = require("playwright-core"); const AXE = require.resolve("axe-core/axe.min.js");
const OUT = (() => { const i = process.argv.indexOf("--out"); return i > 0 ? process.argv[i + 1] : "/tmp/c6-evidence"; })(); mkdirSync(OUT, { recursive: true });
const ENV = Object.fromEntries(readFileSync(process.env.AUDIT_STACK_ENV ?? `${process.env.HOME}/.xweb-e2e-stack/c5e2e-ae/stack.env`, "utf8").split("\n").filter((l) => l.includes("=")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const STUDIO = process.env.AUDIT_STUDIO ?? "http://127.0.0.1:3086"; const PLATFORM = process.env.AUDIT_PLATFORM ?? "http://127.0.0.1:3001"; const run = Date.now().toString(36);
const log = []; const say = (k, v) => { log.push({ k, v }); console.log(`${k}: ${typeof v === "string" ? v : JSON.stringify(v)}`); };
const must = (r, w) => { if (r.status < 200 || r.status >= 300) throw new Error(`${w}: ${r.status} ${r.body?.code ?? ""}`); return r.body; };
const sys = new Session(STUDIO, "sys"); await sys.login("local.admin", ENV.LOCAL_ADMIN_PASSWORD);
const tenant = must(await sys.post("/admin/tenants", { slug: `c6fix-${run}`, name: "Công ty Cổ phần Ánh Dương" }), "tenant");
const ws = must(await sys.post(`/admin/tenants/${tenant.id}/workspaces`, { name: "Kinh doanh" }), "ws");
const l = must(await sys.post(`/admin/tenants/${tenant.id}/users`, { username: `c6fix-${run}`, displayName: "Trần Văn Ưu Tú", tenantRole: "MEMBER", workspaceId: ws.id, workspaceRole: "WORKSPACE_ADMIN" }), "user");
const pw = randomSecret(); must(await new Session(STUDIO, "a").post("/auth/activation/complete", { token: l.token, password: pw }), "activate");
const u = new Session(STUDIO, "u"); await u.login(`c6fix-${run}`, pw);
const proj = must(await u.post(`/workspaces/${ws.id}/projects`, { name: "Trang giới thiệu sản phẩm", appType: "PAGE_SCHEMA" }), "project");
// UX-001 data: a data source, a query and a MAPPING WHOSE FIELDS HAVE NO transforms[] (written through the product's own schema route)
const sc = must(await u.get(`/workspaces/${ws.id}/projects/${proj.id}/schema`), "schema");
const ops = [
  { type: "ADD_DATA_SOURCE", definitionId: "erp", definition: { id: "erp", name: "ERP", type: "postgres" } },
  { type: "ADD_QUERY", definitionId: "q1", definition: { id: "q1", name: "Sản phẩm", dataSourceRef: "erp", operationKey: "list" } },
  { type: "ADD_MAPPING", definitionId: "m1", definition: { id: "m1", name: "Ánh xạ không biến đổi", queryRef: "q1", errorPolicy: "NULL_FIELD", fields: [{ from: "price", to: "price" }, { from: "name", to: "name" }] } },
];
const patch = await u.patch(`/workspaces/${ws.id}/projects/${proj.id}/schema`, { expectedRevision: sc.revision, summary: "ux-001 fixture: mapping without transforms", operations: ops });
say("UX-001 fixture (PATCH /schema, mapping fields without transforms[])", { status: patch.status, code: patch.body?.code ?? null, violations: (patch.body?.details?.violations ?? []).slice(0, 4) });
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
const axe = async (p) => { await p.addScriptTag({ path: AXE }); return p.evaluate(async () => (await window.axe.run(document, { runOnly: ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"], resultTypes: ["violations"] })).violations.filter((v) => ["critical", "serious"].includes(v.impact)).map((v) => `${v.id}(${v.nodes.length})`)); };
async function studio(w) {
  const ctx = await browser.newContext({ viewport: { width: w, height: w >= 1000 ? 900 : 800 } }); const p = await ctx.newPage(); p.setDefaultTimeout(15000);
  const errs = []; p.on("pageerror", (e) => errs.push(`pageerror: ${e.message.slice(0, 120)}`)); p.on("console", (m) => { if (m.type() === "error" && !/favicon|Failed to load resource/.test(m.text())) errs.push(`console: ${m.text().slice(0, 120)}`); });
  await p.goto(`${STUDIO}/login`, { waitUntil: "networkidle" }); await p.getByLabel("Tên đăng nhập").fill(`c6fix-${run}`); await p.getByLabel("Mật khẩu").fill(pw);
  await Promise.all([p.waitForURL((x) => !/\/login/.test(x.pathname)), p.getByRole("button", { name: "Đăng nhập" }).click()]); await p.waitForLoadState("networkidle").catch(() => undefined);
  await p.goto(`${STUDIO}/studio/projects/${proj.id}/design`, { waitUntil: "domcontentloaded" }); await p.waitForSelector("iframe", { timeout: 20000 }).catch(() => undefined); await p.waitForTimeout(900);
  return { ctx, p, errs };
}
for (const w of [1440, 768, 430, 390]) {
  const { ctx, p, errs } = await studio(w);
  const bar = await p.evaluate(() => { const top = document.querySelector(".bx-top").getBoundingClientRect(); const c = document.querySelector(".bx-center")?.getBoundingClientRect(); return { toolbarHeightPx: Math.round(top.height), canvasHeightPx: Math.round(c?.height ?? 0), viewportH: innerHeight, canvasShare: Math.round(((c?.height ?? 0) / innerHeight) * 100) + "%", pageOverflowX: document.documentElement.scrollWidth - innerWidth }; });
  say(`UX-002 builder @${w}`, bar); await p.screenshot({ path: join(OUT, `builder-${w}.png`) });
  const dev = await p.evaluate(() => [...document.querySelectorAll('.bx-top [role=group][aria-label="Kích thước màn hình xem trước"] button')].map((b) => ({ name: b.getAttribute("aria-label"), pressed: b.getAttribute("aria-pressed"), w: Math.round(b.getBoundingClientRect().width), h: Math.round(b.getBoundingClientRect().height) })));
  say(`device switch @${w} (name / size)`, dev);
  const tab = p.getByRole("tab", { name: "Dữ liệu", exact: true }).first(); await tab.click(); await p.waitForTimeout(600);
  const rail = await p.evaluate(() => { const r = document.querySelector(".bx-tabs-vertical, .bx-left [role=tablist]")?.getBoundingClientRect(); const panel = document.querySelector(".bx-left-panel")?.getBoundingClientRect(); const labels = [...document.querySelectorAll(".bx-left [role=tab]")].map((t) => ({ t: t.textContent.trim(), clipped: t.scrollWidth > t.clientWidth + 1, h: Math.round(t.getBoundingClientRect().height), w: Math.round(t.getBoundingClientRect().width) })); return { railW: Math.round(r?.width ?? 0), panelW: Math.round(panel?.width ?? 0), anyClipped: labels.some((x) => x.clipped), minTabH: Math.min(...labels.map((x) => x.h)), labels: labels.map((x) => x.t).join(" | ") }; });
  say(`UX-003 rail @${w}`, rail);
  const existing = p.locator("details.bx-existing summary"); if (await existing.count()) await existing.first().click();
  await p.waitForTimeout(300);
  const note = await p.evaluate(() => document.querySelector(".bx-existing")?.innerText.slice(0, 200) ?? null);
  say(`UX-001 Studio Data @${w}`, { pageErrors: errs.length, errors: errs.slice(0, 3), declaredList: note });
  await p.screenshot({ path: join(OUT, `data-rail-${w}.png`) });
  const small = await p.evaluate(() => [...document.querySelectorAll("button,[role=button],[role=tab],input[type=checkbox],input[type=radio],select")].filter((e) => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(e).visibility !== "hidden" && (r.width < 24 || r.height < 24); }).map((e) => `${e.tagName.toLowerCase()}.${(e.className || "").toString().slice(0, 20)} ${Math.round(e.getBoundingClientRect().width)}x${Math.round(e.getBoundingClientRect().height)}`).slice(0, 8));
  say(`UX-008 controls < 24 px @${w}`, { count: small.length, examples: small });
  say(`axe serious+critical (builder, Dữ liệu rail) @${w}`, await axe(p));
  await ctx.close();
}
// UX-005: the Costs table by keyboard (Platform, SYSTEM_ADMIN)
for (const w of [1440, 768, 390]) {
  const ctx = await browser.newContext({ viewport: { width: w, height: 800 } }); const p = await ctx.newPage(); p.setDefaultTimeout(15000);
  await p.goto(`${PLATFORM}/platform/login`, { waitUntil: "domcontentloaded" }); await p.getByLabel("Tên đăng nhập").fill("local.admin"); await p.getByLabel("Mật khẩu").fill(ENV.LOCAL_ADMIN_PASSWORD); await p.getByRole("button", { name: "Đăng nhập" }).click(); await p.waitForURL((x) => !/\/login$/.test(x.pathname));
  // the stack has no cost measurements (empty tables), so ONLY the GET /admin/costs response is replaced in the browser with realistic rows; the page, table and keyboard behaviour are the real build
  const line = (i) => ({ key: `k${i}`, label: `Workspace Kinh doanh miền Nam – Ứng dụng quản lý đơn hàng ${i}`, storageBytes: 12345678901 * (i + 1), buildCpuMs: 9876543 * (i + 1), buildMs: 765432 * (i + 1), aiUsd: 12.3456 * i, aiUnknownCalls: i, storageUsd: 1.2 * i, cpuUsd: 3.4 * i, buildUsd: 0.5 * i, totalKnownUsd: 17.5 * i, complete: i % 2 === 0 });
  await p.route(/\/admin\/costs\?/, (r) => r.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ days: 30, prices: [], missingPrices: ["STORAGE_GIB_MONTH"], egress: "Lưu lượng mạng (egress) chưa được đo: không tính.", total: line(0), byDepartment: [line(1), line(2), line(3)], byWorkspace: [line(4), line(5), line(6), line(7)], byApplication: [line(8), line(9)] }) }));
  await p.goto(`${PLATFORM}/platform/costs`, { waitUntil: "domcontentloaded" }); await p.waitForLoadState("networkidle").catch(() => undefined); await p.waitForTimeout(600);
  const regions = await p.evaluate(() => [...document.querySelectorAll("section.card")].map((c) => ({ title: c.querySelector("h2")?.textContent ?? "", scrolls: c.scrollWidth > c.clientWidth + 1, focusable: c.tabIndex >= 0, role: c.getAttribute("role"), name: c.getAttribute("aria-label") })));
  const scrolling = regions.filter((r) => r.scrolls);
  let reached = null, moved = null;
  if (scrolling.length) {
    for (let i = 0; i < 40 && !reached; i++) { await p.keyboard.press("Tab"); reached = await p.evaluate(() => { const a = document.activeElement; return a && a.matches("section.card[role=region]") ? { name: a.getAttribute("aria-label"), outline: getComputedStyle(a).outlineStyle + " " + getComputedStyle(a).outlineWidth } : null; }); }
    if (reached) { const before = await p.evaluate(() => document.activeElement.scrollLeft); await p.keyboard.press("ArrowRight"); await p.keyboard.press("ArrowRight"); await p.waitForTimeout(250); moved = { scrollLeftBefore: before, scrollLeftAfter: await p.evaluate(() => document.activeElement.scrollLeft) }; await p.screenshot({ path: join(OUT, `costs-focus-${w}.png`) }); }
  }
  say(`UX-005 Costs @${w}`, { cards: regions.length, scrollingCards: scrolling.length, scrollingAreFocusableRegions: scrolling.every((r) => r.focusable && r.role === "region" && r.name), reachedByTab: reached, scrolledByArrowKeys: moved });
  say(`axe serious+critical (costs) @${w}`, await axe(p));
  await ctx.close();
}
await browser.close(); writeFileSync(join(OUT, "evidence.json"), JSON.stringify(log, null, 1)); console.log("evidence in " + OUT);
