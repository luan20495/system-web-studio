#!/usr/bin/env node
// UI/UX audit runner (a developer tool, NOT a test): opens EVERY real route of the three portals in real Chromium against a real stack, at several viewports, and records per route what a human reviewer would notice:
// glyph characters that may render as tofu (□ ?), U+FFFD, mojibake, horizontal overflow, text spilling out of the viewport, clipped text without an ellipsis, broken images, icon-only controls without an accessible name,
// form controls without a label, console errors, failing API calls, blank pages, axe (wcag2a/aa) serious + critical violations. It also writes a screenshot per route + viewport.
// Nothing is intercepted or faked: the data is created through the product's own API (a NEW tenant with a tenant admin, a workspace admin, a project, 25 employees with long Vietnamese names).
//   node scripts/ui-audit.mjs --out <dir> [--only platform,admin,studio] [--viewports 1440,1024,768,390]
// env: AUDIT_STACK_ENV (default ~/.xweb-e2e-stack/c5e2e-ae/stack.env), AUDIT_STUDIO (http://127.0.0.1:3086), AUDIT_PLATFORM (http://127.0.0.1:3001), AUDIT_ADMIN (http://127.0.0.1:3002), CHROME
import { createRequire } from "node:module";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { Session, randomSecret } from "../tests/e2e-real/lib/api.mjs";
const require = createRequire(new URL("../package.json", import.meta.url).pathname);
const { chromium } = require("playwright-core");
const AXE = require.resolve("axe-core/axe.min.js");
const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const OUT = arg("out", "/tmp/ui-audit"); const ONLY = (arg("only", "platform,admin,studio")).split(","); const VIEWPORTS = arg("viewports", "1440,1024,768,390").split(",").map(Number);
const ENV = Object.fromEntries(readFileSync(process.env.AUDIT_STACK_ENV ?? `${process.env.HOME}/.xweb-e2e-stack/c5e2e-ae/stack.env`, "utf8").split("\n").filter((l) => l.includes("=")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const URLS = { studio: process.env.AUDIT_STUDIO ?? "http://127.0.0.1:3086", platform: process.env.AUDIT_PLATFORM ?? "http://127.0.0.1:3001", admin: process.env.AUDIT_ADMIN ?? "http://127.0.0.1:3002" };
const run = Date.now().toString(36); const created = {};

// ------------------------------------------------------------------------------------------------------------------------------- data (product API only)
async function seed() {
  const sys = new Session(URLS.studio, "sys"); await sys.login("local.admin", ENV.LOCAL_ADMIN_PASSWORD);
  const must = (r, w) => { if (r.status < 200 || r.status >= 300) throw new Error(`${w}: ${r.status} ${r.body?.code ?? ""}`); return r.body; };
  const tenant = must(await sys.post("/admin/tenants", { slug: `audit-${run}`, name: "Công ty Cổ phần Ánh Dương" }), "tenant");
  const ws = must(await sys.post(`/admin/tenants/${tenant.id}/workspaces`, { name: "Kinh doanh và Chăm sóc khách hàng" }), "workspace");
  const user = async (key, body) => { const l = must(await sys.post(`/admin/tenants/${tenant.id}/users`, { username: `audit-${run}-${key}`, displayName: body.displayName, tenantRole: body.tenantRole ?? "MEMBER", ...(body.ws ? { workspaceId: ws.id, workspaceRole: body.ws } : {}), email: `${key}@${run}.anh-duong.example.vn` }), `user ${key}`);
    const pw = randomSecret(); must(await new Session(URLS.studio, "act").post("/auth/activation/complete", { token: l.token, password: pw }), `activate ${key}`); return { username: `audit-${run}-${key}`, password: pw, id: l.userId }; };
  const ta = await user("ta", { displayName: "Nguyễn Thị Quản Trị Viên Đầu Tiên Của Công Ty", tenantRole: "TENANT_ADMIN" });
  const wsa = await user("wsa", { displayName: "Trần Văn Ưu Tú", ws: "WORKSPACE_ADMIN" });
  const names = ["Lê Hoàng Anh", "Phạm Thị Ngọc Ánh", "Đặng Quốc Việt", "Ngô Thị Hồng Nhung", "Bùi Văn Đức", "Hồ Thị Thu Hà", "Vũ Minh Quân", "Dương Thị Mỹ Linh"];
  for (let i = 0; i < 24; i++) { const n = names[i % names.length] + (i >= names.length ? ` (${i})` : ""); must(await sys.post(`/admin/tenants/${tenant.id}/users`, { username: `audit-${run}-e${i}`, displayName: i === 3 ? "Nguyễn Hoàng Thiên Phúc Bảo Long Quang Vinh Hiển Đạt Thịnh Khang An Phú (tên rất dài để thử cắt chữ)" : n, tenantRole: "MEMBER", email: i === 3 ? `nguyen.hoang.thien.phuc.bao.long.quang.vinh@${run}.cong-ty-co-phan-anh-duong-viet-nam.example.vn` : `e${i}@${run}.anh-duong.example.vn` }), `employee ${i}`); }
  const wsaS = new Session(URLS.studio, "wsa"); await wsaS.login(wsa.username, wsa.password);
  const proj = must(await wsaS.post(`/workspaces/${ws.id}/projects`, { name: "Trang giới thiệu sản phẩm mới của Công ty Cổ phần Ánh Dương", description: "Dự án thử giao diện", appType: "PAGE_SCHEMA" }), "project");
  Object.assign(created, { tenant, ws, ta, wsa, proj });
  return { sys, tenant, ws, ta, wsa, proj };
}

// ------------------------------------------------------------------------------------------------------------------------------- measurements
const MEASURE = () => {
  const text = document.body.innerText; const isIconish = (c) => /[←-⇿⌀-⏿■-➿⬀-⯿]/.test(c);
  const glyphs = [...new Set([...text].filter(isIconish))].join("");
  const replacement = text.includes("�"); const mojibake = /â€|Ã[\u0080-¿]|Â[ -¿]|áº|á»/.test(text);
  const entities = /&(amp|lt|gt|nbsp|hellip|mdash|#\d+);/.test(text);
  const vw = window.innerWidth; const overflowX = document.documentElement.scrollWidth - vw;
  const vis = (e) => { const r = e.getBoundingClientRect(); const cs = getComputedStyle(e); return r.width > 0 && r.height > 0 && cs.visibility !== "hidden" && cs.display !== "none"; };
  const leafs = [...document.body.querySelectorAll("*")].filter((e) => vis(e) && ![...e.childNodes].some((n) => n.nodeType === 1) && e.textContent.trim() && !e.closest("svg,iframe,[aria-hidden=true],.srOnly"));
  const spill = leafs.filter((e) => e.getBoundingClientRect().right > vw + 1).slice(0, 4).map((e) => e.textContent.trim().slice(0, 40));
  const clipped = leafs.filter((e) => { const cs = getComputedStyle(e); return (cs.overflow === "hidden" || cs.overflowX === "hidden") && cs.textOverflow !== "ellipsis" && e.scrollWidth > e.clientWidth + 2; }).slice(0, 4).map((e) => e.textContent.trim().slice(0, 40));
  const tiny = leafs.filter((e) => parseFloat(getComputedStyle(e).fontSize) < 11).length;
  const imgs = [...document.images].filter((i) => i.complete && i.naturalWidth === 0).map((i) => i.src.slice(-40));
  const name = (e) => (e.getAttribute("aria-label") || e.getAttribute("aria-labelledby") || e.getAttribute("title") || e.textContent || "").trim();
  const noName = [...document.querySelectorAll("button,[role=button],a[href]")].filter((e) => vis(e) && !name(e) && !e.querySelector("img[alt]:not([alt=''])")).slice(0, 4).map((e) => e.outerHTML.slice(0, 80));
  const noLabel = [...document.querySelectorAll("input:not([type=hidden]),select,textarea")].filter((e) => vis(e) && !e.getAttribute("aria-label") && !e.getAttribute("aria-labelledby") && !e.closest("label") && !(e.id && document.querySelector(`label[for="${CSS.escape(e.id)}"]`))).slice(0, 4).map((e) => e.outerHTML.slice(0, 80));
  const native = [...document.querySelectorAll("select:not(.srOnly)")].filter(vis).length;
  const h1 = document.querySelectorAll("h1").length; const main = document.querySelectorAll("main").length;
  return { glyphs, replacement, mojibake, entities, overflowX, spill, clipped, tiny, imgs, noName, noLabel, native, h1, main, title: document.title, textLen: text.trim().length, h: document.documentElement.scrollHeight };
};
async function axe(page) {
  try { await page.addScriptTag({ path: AXE }); return await page.evaluate(async () => (await window.axe.run(document, { runOnly: ["wcag2a", "wcag2aa"], resultTypes: ["violations"] })).violations.filter((v) => ["critical", "serious"].includes(v.impact)).map((v) => ({ id: v.id, impact: v.impact, n: v.nodes.length, sel: v.nodes[0]?.target?.join(" ").slice(0, 70) }))); } catch (e) { return [{ id: "axe-failed", impact: "n/a", n: 0, sel: String(e).slice(0, 60) }]; }
}

// ------------------------------------------------------------------------------------------------------------------------------- runner
const rows = [];
async function visit(page, portal, vp, route, label, shotDir, extra) {
  const errs = []; const bad = [];
  const onErr = (m) => { if (["error"].includes(m.type()) && !/favicon|Failed to load resource/.test(m.text())) errs.push(m.text().slice(0, 140)); }; const onPE = (e) => errs.push(`pageerror: ${e.message.slice(0, 140)}`);
  const onResp = (r) => { if (r.status() >= 400 && /\/api\//.test(r.url())) bad.push(`${r.request().method()} ${new URL(r.url()).pathname.replace(/[0-9a-f-]{36}/g, "{id}")} ${r.status()}`); };
  page.on("console", onErr); page.on("pageerror", onPE); page.on("response", onResp);
  let ok = true; try { await page.goto(route, { waitUntil: "domcontentloaded" }); await page.waitForLoadState("networkidle", { timeout: 15000 }).catch(() => undefined); await page.waitForTimeout(500); if (extra) await extra(page); } catch (e) { ok = false; errs.push(`navigation: ${String(e).slice(0, 100)}`); }
  const m = await page.evaluate(MEASURE).catch(() => ({ glyphs: "", textLen: 0 })); const ax = await axe(page);
  const slug = label.replace(/[^a-z0-9]+/gi, "-").toLowerCase();
  mkdirSync(shotDir, { recursive: true }); await page.screenshot({ path: join(shotDir, `${slug}.png`), fullPage: vp >= 1000 || vp <= 430 }).catch(() => undefined);
  page.off("console", onErr); page.off("pageerror", onPE); page.off("response", onResp);
  rows.push({ portal, vp, route: new URL(route).pathname, label, ok, ...m, axe: ax, errs, bad });
}

async function loginPortal(page, portal, username, password) {
  const base = URLS[portal]; await page.goto(`${base}/${portal}/login`, { waitUntil: "domcontentloaded" });
  await page.getByLabel("Tên đăng nhập").fill(username); await page.getByLabel("Mật khẩu").fill(password); await page.getByRole("button", { name: "Đăng nhập" }).click();
  await page.waitForURL((u) => !/\/login$/.test(u.pathname), { timeout: 20000 }); await page.waitForLoadState("networkidle").catch(() => undefined);
}
async function loginStudio(page, username, password) {
  await page.goto(`${URLS.studio}/login`, { waitUntil: "networkidle" }); await page.getByLabel("Tên đăng nhập").fill(username); await page.getByLabel("Mật khẩu").fill(password);
  await Promise.all([page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 20000 }), page.getByRole("button", { name: "Đăng nhập" }).click()]); await page.waitForLoadState("networkidle").catch(() => undefined);
}

const browser = await chromium.launch({ executablePath: process.env.CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
const d = await seed(); const T = d.tenant.id;
const PLATFORM = ["", "/tenants", `/tenants/${T}`, "/users", "/ai", "/components", "/templates", "/builds", "/packages", "/system", "/backups", "/costs", "/alerts", "/security", "/settings", "/audit", "/connectors", "/nope"];
const ADMIN_TENANT = ["", "/company", "/organization", "/employees", "/people", "/my-workspaces", "/data-sources", "/groups", "/sharing", "/byok", "/nope"];
const ADMIN_SYSTEM = ["", "/users", "/applications", "/departments", "/identity", "/ai-governance", "/templates", "/audit", "/sharing", "/data-sources"];
const P = d.proj.id; const STUDIO = ["", "/projects", "/new", "/templates", "/components", "/activity", "/site-access", `/projects/${P}/ai`, `/projects/${P}/design`, `/projects/${P}/code`, `/projects/${P}/members`, `/projects/${P}/versions`, `/projects/${P}/assets`, `/projects/${P}/publish`, `/projects/${P}/settings`, `/projects/${P}/site`, "/nope"];
for (const vp of VIEWPORTS) {
  const viewport = { width: vp, height: vp >= 1000 ? 900 : 800 };
  if (ONLY.includes("platform")) { const ctx = await browser.newContext({ viewport }); const page = await ctx.newPage(); await loginPortal(page, "platform", "local.admin", ENV.LOCAL_ADMIN_PASSWORD); for (const r of PLATFORM) await visit(page, "platform", vp, `${URLS.platform}/platform${r}`, `platform${r || "-home"}`, join(OUT, "platform", String(vp))); await ctx.close(); }
  if (ONLY.includes("admin")) {
    let ctx = await browser.newContext({ viewport }); let page = await ctx.newPage(); await loginPortal(page, "admin", d.ta.username, d.ta.password); for (const r of ADMIN_TENANT) await visit(page, "admin", vp, `${URLS.admin}/admin${r}`, `admin-tenant${r || "-home"}`, join(OUT, "admin", String(vp))); await ctx.close();
    ctx = await browser.newContext({ viewport }); page = await ctx.newPage(); await loginPortal(page, "admin", "local.admin", ENV.LOCAL_ADMIN_PASSWORD); for (const r of ADMIN_SYSTEM) await visit(page, "admin", vp, `${URLS.admin}/admin${r}`, `admin-system${r || "-home"}`, join(OUT, "admin", String(vp))); await ctx.close();
  }
  if (ONLY.includes("studio")) { const ctx = await browser.newContext({ viewport }); const page = await ctx.newPage(); await loginStudio(page, d.wsa.username, d.wsa.password); for (const r of STUDIO) await visit(page, "studio", vp, `${URLS.studio}/studio${r}`, `studio${r.replace(P, "P") || "-home"}`, join(OUT, "studio", String(vp))); await ctx.close(); }
}
await browser.close();
mkdirSync(OUT, { recursive: true }); writeFileSync(join(OUT, "audit.json"), JSON.stringify({ run, created: { tenant: d.tenant.id, project: P }, rows }, null, 1));
const flag = (r) => [r.glyphs ? `glyphs[${r.glyphs}]` : "", r.replacement ? "U+FFFD" : "", r.mojibake ? "mojibake" : "", r.entities ? "entity" : "", r.overflowX > 1 ? `overflowX+${r.overflowX}` : "", r.spill?.length ? `spill(${r.spill.join("|")})` : "", r.clipped?.length ? `clipped(${r.clipped.join("|")})` : "", r.imgs?.length ? "brokenImg" : "", r.noName?.length ? `noName(${r.noName.length})` : "", r.noLabel?.length ? `noLabel(${r.noLabel.length})` : "", r.axe?.length ? `axe[${r.axe.map((a) => `${a.id}:${a.impact}x${a.n}`).join(",")}]` : "", r.errs?.length ? `console(${r.errs.length})` : "", r.bad?.length ? `api(${[...new Set(r.bad)].join(",")})` : "", (r.textLen ?? 0) < 20 ? "BLANK" : "", r.h1 === 0 ? "noH1" : ""].filter(Boolean).join(" ");
const md = ["| portal | vp | route | issues |", "|---|---|---|---|", ...rows.map((r) => `| ${r.portal} | ${r.vp} | ${r.route} | ${flag(r) || "ok"} |`)].join("\n");
writeFileSync(join(OUT, "audit.md"), md + "\n"); console.log(`audited ${rows.length} (route × viewport) · routes ${new Set(rows.map((r) => r.portal + r.route)).size} · with issues ${rows.filter((r) => flag(r)).length}\nreport: ${OUT}/audit.md`);
