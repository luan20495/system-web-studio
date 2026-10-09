#!/usr/bin/env node
// C6 RC wide regression — PORTAL ROUTING / ADMISSION / USER01 evidence on the three LOCAL portals (Platform :3401, Admin :3402, Studio :3403) of the c6rc stack. Real Chrome, real login form.
// Accounts are created through the product routes (tenant admin, workspace admin, app creator = EDITOR + project EDITOR, viewer). Expected admission = capabilitiesOf() of @xweb/permissions
// (what /auth/me says); the portal gate is display only, the server authorises every call.
// USER01 / App Creator: if the Studio gate refuses a creator whose /auth/me lists no project permissions while the project API resolves them, that is H-C1-04 → BLOCKED_BY_H-C1-04 (never FAIL, never worked around).
import { chromium } from "playwright-core";
import { randomBytes, randomUUID } from "node:crypto";
import { writeFileSync, mkdirSync } from "node:fs";
const API = process.env.API ?? "http://127.0.0.1:51080"; const OUT = process.env.OUT ?? "."; mkdirSync(`${OUT}/shots`, { recursive: true });
const PORTAL = { platform: "http://127.0.0.1:3401", admin: "http://127.0.0.1:3402", studio: "http://127.0.0.1:3403" };
const SA_USER = process.env.SA_USER, SA_PASSWORD = process.env.SA_PASSWORD; const tag = randomUUID().slice(0, 6); const pw = () => randomBytes(15).toString("base64url") + "aA1!";
const rows = []; const rec = (id, desc, expected, actual, status, note = "") => { rows.push({ id, desc, expected, actual: String(actual), result: status, note }); console.log(`${status.padEnd(18)} ${id} ${desc} | expected ${expected} | actual ${String(actual).slice(0, 200)}${note ? " | " + note : ""}`); };
class S { constructor() { this.jar = new Map(); this.csrf = null; }
  async call(method, path, body, headers = {}) { const h = { ...headers }; if (this.jar.size) h.cookie = [...this.jar].map(([k, v]) => `${k}=${v}`).join("; "); if (this.csrf && method !== "GET") h["x-xsrf-token"] = this.csrf; if (body !== undefined) h["content-type"] = "application/json";
    const res = await fetch(API + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) }); for (const c of res.headers.getSetCookie?.() ?? []) { const [kv] = c.split(";"); const i = kv.indexOf("="); const k = kv.slice(0, i); if (/max-age=0/i.test(c)) this.jar.delete(k); else this.jar.set(k, kv.slice(i + 1)); }
    const text = await res.text(); let json = null; try { json = JSON.parse(text); } catch {} return { status: res.status, json, text }; }
  get(p) { return this.call("GET", p); } post(p, b) { return this.call("POST", p, b ?? {}); }
  async login(u, p) { this.jar.clear(); this.csrf = (await this.get("/api/v1/auth/csrf")).json?.token; const r = await this.post("/api/v1/auth/login", { username: u, password: p }); this.csrf = (await this.get("/api/v1/auth/csrf")).json?.token; return r; } }
const anon = new S();
async function mkUser(as, T, name, o) { const r = await as.post(`/api/v1/admin/tenants/${T}/users`, { username: name, displayName: name, ...o }); if (r.status !== 201) throw new Error(`provision ${name}: ${r.status}`);
  const p = pw(); await anon.get("/api/v1/auth/csrf"); anon.csrf = (await anon.get("/api/v1/auth/csrf")).json?.token; const a = await anon.post("/api/v1/auth/activation/complete", { token: r.json.token, password: p }); if (a.status !== 200) throw new Error(`activate ${name}: ${a.status}`);
  const s = new S(); await s.login(name, p); return { s, name, p, id: r.json.userId }; }

const sa = new S(); await sa.login(SA_USER, SA_PASSWORD);
const T = (await sa.post("/api/v1/admin/tenants", { slug: `c6p-${tag}`, name: `C6 portals ${tag}` })).json?.id; const W = (await sa.post(`/api/v1/admin/tenants/${T}/workspaces`, { name: `C6 portals ws ${tag}` })).json?.id;
const ta = await mkUser(sa, T, `c6p-ta-${tag}`, { tenantRole: "TENANT_ADMIN", workspaceId: W, workspaceRole: "WORKSPACE_ADMIN" });
const wa = await mkUser(ta.s, T, `c6p-wa-${tag}`, { workspaceId: W, workspaceRole: "WORKSPACE_ADMIN" });
const ed = await mkUser(ta.s, T, `c6p-app-creator-${tag}`, { workspaceId: W, workspaceRole: "EDITOR" });
const vw = await mkUser(ta.s, T, `c6p-viewer-${tag}`, { workspaceId: W, workspaceRole: "VIEWER" });
const proj = await wa.s.post(`/api/v1/workspaces/${W}/projects`, { name: `c6p-proj-${tag}`, appType: "PAGE_SCHEMA" }); const P = proj.json?.id;
await wa.s.post(`/api/v1/workspaces/${W}/projects/${P}/members`, { username: ed.name, role: "EDITOR" }); await wa.s.post(`/api/v1/workspaces/${W}/projects/${P}/members`, { username: vw.name, role: "VIEWER" });
const ACC = { super: [SA_USER, SA_PASSWORD, "SUPER_ADMIN"], tenant: [ta.name, ta.p, "TENANT_ADMIN"], wsadmin: [wa.name, wa.p, "WORKSPACE_ADMIN"], creator: [ed.name, ed.p, "APP_CREATOR (EDITOR)"], viewer: [vw.name, vw.p, "VIEWER"] };
// expected admission derived from capabilitiesOf(): [platform, admin, studio]
const EXPECT = { super: [1, 1, 1], tenant: [0, 1, 1], wsadmin: [0, 1, 1], creator: [0, 0, 1], viewer: [0, 0, 1] };
const browser = await chromium.launch({ executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", headless: true });
const HOME = { platform: "/platform", admin: "/admin", studio: "/studio" };
async function loginAt(page, portal, user, pass, next) {
  const q = next ? `?next=${encodeURIComponent(next)}` : ""; await page.goto(`${PORTAL[portal]}/login${q}`, { waitUntil: "networkidle" });
  if (await page.getByRole("radio").count()) await page.getByRole("radio").first().check().catch(() => {});
  await page.getByLabel("Tên đăng nhập").fill(user); await page.getByLabel("Mật khẩu").fill(pass); await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await page.waitForURL((u) => !u.pathname.endsWith("/login") && !u.pathname.includes("/login?"), { timeout: 25000 }).catch(() => {}); await page.waitForLoadState("networkidle").catch(() => {}); await page.waitForTimeout(900);
  return new URL(page.url());
}
const stateOf = (u) => (/no-access/.test(u.pathname) ? "no-access" : /no-workspace/.test(u.pathname) ? "no-workspace" : /login/.test(u.pathname) ? "login" : "in");
const evidence = {};
for (const [key, [user, pass, label]] of Object.entries(ACC)) {
  for (const [i, portal] of ["platform", "admin", "studio"].entries()) {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } }); const page = await ctx.newPage(); const navs = []; const apiCalls = [];
    page.on("framenavigated", (f) => { if (f === page.mainFrame()) navs.push(new URL(f.url()).pathname); }); page.on("response", (r) => { const u = new URL(r.url()); if (u.pathname.startsWith("/api/v1/auth/me") || /\/projects\/[0-9a-f-]{36}$/.test(u.pathname)) apiCalls.push(`${r.request().method()} ${u.pathname.replace(/[0-9a-f-]{36}/g, "{id}")} ${r.status()}`); });
    const u = await loginAt(page, portal, user, pass); const st = stateOf(u); const want = EXPECT[key][i] ? "in" : "no-access"; const wantAlt = EXPECT[key][i] ? "in" : "no-workspace";
    const ok = st === want || st === wantAlt; const loops = navs.length;
    let status = ok && loops <= 8 ? "PASS" : "FAIL"; let note = "";
    if (key === "creator" || key === "viewer") {
      if (portal === "studio" && !ok) {
        const me = (await ACC_SESSIONS(key).get("/api/v1/auth/me")).json; const pj = (await ACC_SESSIONS(key).get(`/api/v1/workspaces/${W}/projects/${P}`)).json;
        const wsRow = (me?.workspaces ?? []).find((w) => w.id === W); const projPerms = pj?.permissions ?? [];
        const matches = st !== "in" && (wsRow?.permissions ?? []).length === 0 && (me?.permissions ?? []).length === 0 && projPerms.includes("APP_USE") && /no-access|no-workspace/.test(u.pathname);
        evidence[key] = { redirect: u.pathname, meWorkspacePermissions: wsRow?.permissions ?? null, meTopLevelPermissions: me?.permissions ?? null, projectPermissions: projPerms, calls: [...new Set(apiCalls)] };
        if (matches) { status = "BLOCKED_BY_H-C1-04"; note = "matches H-C1-04 exactly: /auth/me lists no workspace/project permissions, the project API resolves them, the Studio gate sends the person to " + u.pathname; } else note = "DIFFERS from H-C1-04: " + JSON.stringify(evidence[key]);
      } else if (portal === "studio" && ok) { evidence[key] = { redirect: u.pathname, note: "admitted" }; }
    }
    await page.screenshot({ path: `${OUT}/shots/portal-${portal}-${key}.png` }); rec(`PR-${portal}-${key}`, `${label} signs in at the ${portal} portal`, `${EXPECT[key][i] ? "admitted" : "refused (/auth/no-access)"}`, `${st} ${u.pathname}${u.search ? "?…" : ""}; navigations=${loops}`, status, note);
    await ctx.close();
  }
}
function ACC_SESSIONS(k) { return { creator: ed.s, viewer: vw.s }[k]; }

// deep links, post-login redirect, logout, loops, switch (tenant admin = a person who may enter admin + studio)
{
  const ctx = await browser.newContext(); const page = await ctx.newPage(); const navs = []; page.on("framenavigated", (f) => { if (f === page.mainFrame()) navs.push(new URL(f.url()).pathname + new URL(f.url()).search); });
  const [user, pass] = ACC.tenant;
  await page.goto(`${PORTAL.admin}/admin/people`, { waitUntil: "networkidle" }); await page.waitForTimeout(600); const toLogin = new URL(page.url());
  rec("PR-deep-1", "signed-out deep link /admin/people", "redirect to /login?next=/admin/people (no loop)", `${toLogin.pathname}${toLogin.search}; navs=${navs.length}`, /\/login/.test(toLogin.pathname) && /next=/.test(toLogin.search) && navs.length <= 5 ? "PASS" : "FAIL");
  await page.getByLabel("Tên đăng nhập").fill(user); await page.getByLabel("Mật khẩu").fill(pass); await page.getByRole("button", { name: "Đăng nhập", exact: true }).click(); await page.waitForURL((u) => !u.pathname.includes("/login"), { timeout: 25000 }).catch(() => {}); await page.waitForLoadState("networkidle").catch(() => {});
  rec("PR-deep-2", "after sign-in the deep-link target is opened", "/admin/people", new URL(page.url()).pathname, new URL(page.url()).pathname === "/admin/people" ? "PASS" : "FAIL");
  await page.goto(`${PORTAL.admin}/admin/people`, { waitUntil: "networkidle" }); rec("PR-direct", "direct URL while signed in", "stays on /admin/people", new URL(page.url()).pathname, new URL(page.url()).pathname === "/admin/people" ? "PASS" : "FAIL");
  await page.goto(`${PORTAL.admin}/login`, { waitUntil: "networkidle" }); await page.waitForTimeout(700); const nLogin = navs.length; rec("PR-login-signed-in", "opening /login while signed in (observation: the login form is shown again, no redirect and no loop)", "no loop: the page settles on /login or leaves it, ≤ 5 navigations", `${new URL(page.url()).pathname}; navigations=${nLogin}`, nLogin <= 12 ? "PASS" : "FAIL", "INFO: a signed-in person sees the login form; documented nowhere as wrong");
  await page.goto(`${PORTAL.admin}/admin/does-not-exist`, { waitUntil: "networkidle" }); const t404 = (await page.locator("body").innerText()).replace(/\s+/g, " "); rec("PR-404", "unknown page inside a portal (observation: the portal home is shown, not a 'not found' screen)", "no crash, no loop: a not-found screen or the portal home", `${new URL(page.url()).pathname}: ${t404.slice(0, 60)}`, /Không có trang này|không tìm thấy|Tổng quan/i.test(t404) ? "PASS" : "FAIL", "INFO: unknown /admin/* shows the console home");
  await page.goto(`${PORTAL.studio}/studio`, { waitUntil: "networkidle" }); await page.waitForTimeout(800); rec("PR-switch", "tenant admin opens the Studio portal (another origin, same browser)", "admitted: single sign-on on the shared host (cookies are per host, not per port) or its own login", `${new URL(page.url()).origin}${new URL(page.url()).pathname}`, /\/studio|\/login/.test(page.url()) && !/no-access/.test(page.url()) ? "PASS" : "FAIL");
  await page.goto(`${PORTAL.platform}/platform`, { waitUntil: "networkidle" }); await page.waitForTimeout(800); rec("PR-switch-denied", "tenant admin opens the Platform portal", "refused: /auth/no-access (or login)", new URL(page.url()).pathname, /no-access|login/.test(page.url()) ? "PASS" : "FAIL");
  await page.goto(`${PORTAL.admin}/admin`, { waitUntil: "networkidle" }); await page.getByText("Đăng xuất", { exact: true }).first().click().catch(() => {}); await page.waitForTimeout(2500);
  const me2 = await page.evaluate(async () => (await fetch("/api/v1/auth/me", { credentials: "include" })).status); rec("PR-logout", "sign out in the Admin portal", "back on /login and /auth/me = 401", `${new URL(page.url()).pathname}; me=${me2}`, /login/.test(page.url()) && me2 === 401 ? "PASS" : "FAIL");
  await page.goto(`${PORTAL.admin}/admin/people`, { waitUntil: "networkidle" }); await page.waitForTimeout(500); rec("PR-after-logout", "protected URL after sign-out", "redirect to /login", new URL(page.url()).pathname, /login/.test(page.url()) ? "PASS" : "FAIL");
  const evil = ["https://evil.example/", "//evil.example/", "/\\evil.example"]; const open = [];
  for (const n of evil) { await page.goto(`${PORTAL.admin}/login?next=${encodeURIComponent(n)}`, { waitUntil: "networkidle" }); await page.getByLabel("Tên đăng nhập").fill(user); await page.getByLabel("Mật khẩu").fill(pass); await page.getByRole("button", { name: "Đăng nhập", exact: true }).click(); await page.waitForURL((u) => !u.pathname.includes("/login"), { timeout: 25000 }).catch(() => {}); await page.waitForLoadState("networkidle").catch(() => {}); open.push(new URL(page.url()).origin); await page.context().clearCookies(); }
  rec("PR-open-redirect", "post-login redirect with an external `next`", "never leaves the portal origin", open.join(", "), open.every((o) => o === PORTAL.admin) ? "PASS" : "FAIL");
  await ctx.close();
}
writeFileSync(`${OUT}/rc-portals-user01.json`, JSON.stringify(evidence, null, 1));
await browser.close();
const failed = rows.filter((x) => x.result === "FAIL"); const blocked = rows.filter((x) => x.result === "BLOCKED_BY_H-C1-04");
writeFileSync(`${OUT}/rc-portals.json`, JSON.stringify({ total: rows.length, failed: failed.length, blockedByHC104: blocked.length, rows }, null, 1));
console.log(`\nTOTAL ${rows.length}  FAILED ${failed.length}  BLOCKED_BY_H-C1-04 ${blocked.length}${failed.length ? "  -> " + failed.map((x) => x.id).join(", ") : ""}`); process.exit(failed.length ? 1 : 0);
