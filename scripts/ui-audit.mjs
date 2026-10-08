#!/usr/bin/env node
// UI/UX audit runner (a developer tool, NOT a test): opens EVERY route of the three portals in real Chromium against a REAL stack, over the full RESPONSIVE MATRIX (1920 1440 1280 1024 768 600 430 390 360), and records per visit what a reviewer would notice:
// horizontal overflow, text spilling or clipped, controls that are unreachable (outside the viewport / clipped) or covered, controls under 24 px, a visible label that is not in the accessible name, focus-visible and a sticky header covering the focused
// element on the first 10 Tab stops (WCAG 2.4.11), icon-only controls without a name, unlabeled fields, glyphs / mojibake, console errors, failing API calls, blank pages, axe violations of EVERY impact. One screenshot per visit.
// The ROUTE INVENTORY comes from the SOURCE (scripts/audit/inventory.mjs: the admin section registry / tables, the Studio route switch and project views). The run FAILS (exit 1) when a route found in the source was not visited.
// Nothing is intercepted or faked: the data is created through the product's own API (a NEW tenant with a tenant admin, a workspace admin, a project, 25 employees with long Vietnamese names).
//   node scripts/ui-audit.mjs --out <dir> [--only platform,admin,studio] [--viewports 1920,1440,...] [--private-api http://127.0.0.1:47080]
// --private-api: build the three apps into PRIVATE dist dirs with API_PROXY_TARGET=<that backend>, serve them on free ports through the owned-process library, audit those, stop them. The backend is never started or stopped by this tool.
//   The backend refuses browser POSTs from origins that are not on its CORS list (the e2e stack lists someone else's ports), so a tiny pass-through shim in front of it rewrites only the Origin / Referer header (scripts/audit/private-portals.mjs).
// env (without --private-api): AUDIT_STACK_ENV (default ~/.xweb-e2e-stack/c5e2e-ae/stack.env), AUDIT_STUDIO (http://127.0.0.1:3086), AUDIT_PLATFORM (http://127.0.0.1:3001), AUDIT_ADMIN (http://127.0.0.1:3002), CHROME
// The same engine runs on the harnesses without a backend: scripts/ui-audit-harness.mjs (HARNESS, NOT REAL BACKEND).
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { chromium, chromePath } from "../tests/browser/lib/spec.mjs";
import { Session, randomSecret } from "../tests/e2e-real/lib/api.mjs";
import { VIEWPORTS, viewportOf } from "./audit/measure.mjs";
import { visit as engineVisit, flag, summarize } from "./audit/engine.mjs";
import { inventory, visitPaths, missingRoutes } from "./audit/inventory.mjs";
import { startPrivatePortals } from "./audit/private-portals.mjs";
const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const OUT = arg("out", "/tmp/ui-audit"); const ONLY = (arg("only", "platform,admin,studio")).split(","); const VPS = arg("viewports", VIEWPORTS.join(",")).split(",").map(Number); const PRIVATE_API = arg("private-api", null);
const ENV = Object.fromEntries(readFileSync(process.env.AUDIT_STACK_ENV ?? `${process.env.HOME}/.xweb-e2e-stack/c5e2e-ae/stack.env`, "utf8").split("\n").filter((l) => l.includes("=")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
let URLS = { studio: process.env.AUDIT_STUDIO ?? "http://127.0.0.1:3086", platform: process.env.AUDIT_PLATFORM ?? "http://127.0.0.1:3001", admin: process.env.AUDIT_ADMIN ?? "http://127.0.0.1:3002" };
const run = Date.now().toString(36); const created = {};
const rows = []; const visited = new Set();
const visit = async (page, portal, vp, route, label, shotDir, extra, routeId) => { await engineVisit(rows, page, portal, vp, route, label, shotDir, extra, routeId); if (routeId) visited.add(routeId); };

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

async function loginPortal(page, portal, username, password) {
  const base = URLS[portal]; await page.goto(`${base}/${portal}/login`, { waitUntil: "domcontentloaded" });
  await page.getByLabel("Tên đăng nhập").fill(username); await page.getByLabel("Mật khẩu").fill(password); await page.getByRole("button", { name: "Đăng nhập" }).click();
  await page.waitForURL((u) => !/\/login$/.test(u.pathname), { timeout: 20000 }); await page.waitForLoadState("networkidle").catch(() => undefined);
}
async function loginStudio(page, username, password) {
  await page.goto(`${URLS.studio}/login`, { waitUntil: "networkidle" }); await page.getByLabel("Tên đăng nhập").fill(username); await page.getByLabel("Mật khẩu").fill(password);
  await Promise.all([page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 20000 }), page.getByRole("button", { name: "Đăng nhập" }).click()]); await page.waitForLoadState("networkidle").catch(() => undefined);
}


const priv = PRIVATE_API ? await startPrivatePortals({ api: PRIVATE_API, allowOrigin: ENV.STUDIO_ORIGIN ?? ENV.CORS_ALLOWED_ORIGINS?.split(",")[0] ?? null, tag: "audit" }) : null; if (priv) URLS = priv.urls;
let exitCode = 0;
try {
  const browser = await chromium.launch({ executablePath: chromePath(), headless: true });
  const d = await seed(); const T = d.tenant.id; const P = d.proj.id;
  const inv = inventory();
  const ids = { tenants: T, users: d.ta.id, workspaces: d.ws.id, applications: P, project: P };
  const rel = (c, p) => p.slice(`/${c}`.length);
  const list = (c) => [...visitPaths(inv, c, { ids }).map(({ route, path }) => ({ r: rel(c, path), id: route.id })), { r: "/nope", id: null }];
  for (const vp of VPS) {
    const viewport = viewportOf(vp);
    const go = async (page, portal, items, tag) => { for (const { r, id } of items) await visit(page, portal, vp, `${URLS[portal]}/${portal}${r}`, `${tag}${r.replace(P, "P") || "-home"}`, join(OUT, portal, String(vp)), undefined, id); };
    if (ONLY.includes("platform")) { const ctx = await browser.newContext({ viewport }); const page = await ctx.newPage(); await loginPortal(page, "platform", "local.admin", ENV.LOCAL_ADMIN_PASSWORD); await go(page, "platform", list("platform"), "platform"); await ctx.close(); }
    if (ONLY.includes("admin")) {
      let ctx = await browser.newContext({ viewport }); let page = await ctx.newPage(); await loginPortal(page, "admin", d.ta.username, d.ta.password); await go(page, "admin", list("admin"), "admin-tenant"); await ctx.close();
      ctx = await browser.newContext({ viewport }); page = await ctx.newPage(); await loginPortal(page, "admin", "local.admin", ENV.LOCAL_ADMIN_PASSWORD); await go(page, "admin", list("admin"), "admin-system"); await ctx.close();
    }
    if (ONLY.includes("studio")) { const ctx = await browser.newContext({ viewport }); const page = await ctx.newPage(); await loginStudio(page, d.wsa.username, d.wsa.password); await go(page, "studio", list("studio"), "studio"); await ctx.close(); }
  }
  await browser.close();
  const missing = missingRoutes(inv, visited, { consoles: ONLY });
  mkdirSync(OUT, { recursive: true });
  const summary = summarize(rows);
  writeFileSync(join(OUT, "audit.json"), JSON.stringify({ run, mode: "REAL STACK (private portal builds)", api: PRIVATE_API, viewports: VPS, created: { tenant: T, project: P }, inventory: { source: inv.source, routes: inv.routes.length, consoles: ONLY, missing }, summary, rows }, null, 1));
  const md = ["| portal | vp | route | issues |", "|---|---|---|---|", ...rows.map((r) => `| ${r.portal} | ${r.vp} | ${r.route} | ${flag(r) || "ok"} |`)].join("\n");
  writeFileSync(join(OUT, "audit.md"), md + "\n");
  console.log(`audited ${rows.length} visits (route x viewport x state) · viewports ${VPS.join(",")} · routes ${summary.routes} · with issues ${summary.withIssues}\nsummary ${JSON.stringify(summary)}\ninventory (${inv.source}): ${inv.routes.length} routes in source, ${missing.length} not visited${missing.length ? ": " + missing.join(", ") : ""}\nreport: ${OUT}/audit.md`);
  if (missing.length) { console.error("FAIL: routes found in the source were not visited"); exitCode = 1; }
} finally { if (priv) await priv.stop(); }
process.exit(exitCode);
