#!/usr/bin/env node
// GOLDEN COMPANY FLOW (docs/GOLDEN_COMPANY_FLOW.md): the 27 acceptance steps of ONE company, executed as ONE chain on ONE real stack and ONE SHA.
//   real API + PostgreSQL + Redis + RabbitMQ + MinIO + render worker + sites gateway + the real TLS data target; real Chrome for the UI steps (03, 10, 22, 23, 25, 27).
//   No mock, no fixture row inserted by SQL, no test hook: every account / company / project is created through the product routes. The data target is only READ with SELECT.
// Output: <OUT>/golden-company-flow.json and .tsv (one row per check, one verdict per step) and the last line `GOLDEN_COMPANY_FLOW: <n>/27 PASS`. Exit code 1 when any step is not PASS.
// Never prints a secret: passwords come from the stack env file / the data-target files and are never logged.
// Usage (export these yourself; the isolated-stack tool `docs/parallel/c5/e2e-stack.sh` exports none of them; see docs/GOLDEN_COMPANY_FLOW.md section 6):
//   GC_API=http://127.0.0.1:47400 GC_SITES=http://127.0.0.1:47405 GC_STUDIO=... GC_ADMIN=... GC_SA_USER=... GC_SA_PASSWORD=... \
//   GC_RO_PW_FILE=.run/data-target/ro.pw GC_RW_PW_FILE=.run/data-target/rw.pw GC_RESTART_CMD='docs/parallel/c5/e2e-stack.sh backend-restart' node scripts/golden-company-flow.mjs
import { randomBytes, randomUUID } from "node:crypto";
import { execFileSync, execSync } from "node:child_process";
import { createRequire } from "node:module";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

const env = (k, d) => process.env[k] ?? d;
const need = (k) => { const v = process.env[k]; if (!v) { console.error(`${k} is required`); process.exit(2); } return v; };
const API = need("GC_API").replace(/\/$/, ""), SITES = need("GC_SITES").replace(/\/$/, "");
const STUDIO = need("GC_STUDIO").replace(/\/$/, ""), ADMIN = need("GC_ADMIN").replace(/\/$/, "");
const SA_USER = need("GC_SA_USER"), SA_PASSWORD = need("GC_SA_PASSWORD");
const RO = readFileSync(need("GC_RO_PW_FILE"), "utf8").trim(), RW = readFileSync(need("GC_RW_PW_FILE"), "utf8").trim();
const DATA_PORT = env("GC_DATA_PORT", "15440"), DATA_CONTAINER = env("GC_DATA_CONTAINER", "hbl-v1-data-target");
const OUT = env("GC_OUT", "."), CHROME = env("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
const RESTART = env("GC_RESTART_CMD", ""), FINAL_SHA = env("GC_FINAL_SHA", "");
const tag = randomUUID().slice(0, 6);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const pw = () => randomBytes(15).toString("base64url") + "aA1!";
const st = (r) => `${r.status}${r.json?.code ? " " + r.json.code : r.json?.error?.code ? " " + r.json.error.code : typeof r.json?.error === "string" ? " " + r.json.error : ""}`;
const psql = (sql) => execFileSync("docker", ["exec", DATA_CONTAINER, "psql", "-U", "postgres", "-d", "shop", "-At", "-c", sql], { encoding: "utf8", timeout: 20000 }).trim();   // SELECT only

class S {
  constructor(base = API) { this.base = base; this.jar = new Map(); this.csrf = null; }
  async call(method, path, body, headers = {}) {
    const h = { ...headers }; if (this.jar.size) h.cookie = [...this.jar].map(([k, v]) => `${k}=${v}`).join("; ");
    if (this.csrf && !["GET", "HEAD", "OPTIONS"].includes(method)) h["x-xsrf-token"] = this.csrf;
    if (body !== undefined) h["content-type"] = "application/json";
    const res = await fetch(this.base + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), redirect: "manual" });
    for (const c of res.headers.getSetCookie?.() ?? []) { const [kv] = c.split(";"); const i = kv.indexOf("="); const k = kv.slice(0, i); if (/max-age=0/i.test(c)) this.jar.delete(k); else this.jar.set(k, kv.slice(i + 1)); }
    const text = await res.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
    return { status: res.status, json, text, headers: res.headers };
  }
  async fetchCsrf() { this.csrf = (await this.call("GET", "/api/v1/auth/csrf")).json?.token ?? null; return this.csrf; }
  async login(username, password) { this.jar.clear(); await this.fetchCsrf(); const r = await this.call("POST", "/api/v1/auth/login", { username, password }); await this.fetchCsrf(); return r; }
}

// ---------------------------------------------------------------- recorder: a step is PASS only when every one of its checks passed
const rows = []; const steps = new Map(); let cur = null;
const chk = (name, ok, info = "") => { const r = { step: cur.n, check: name, result: ok ? "PASS" : "FAIL", info: String(info).slice(0, 300) }; rows.push(r); if (!ok) cur.failed++; cur.checks++; console.log(`  ${ok ? "PASS" : "FAIL"} ${cur.n} ${name}${info ? " | " + String(info).slice(0, 160) : ""}`); return ok; };
async function step(n, title, fn) {
  cur = { n, title, failed: 0, checks: 0, error: "" }; steps.set(n, cur); console.log(`\n== STEP ${n} ${title}`);
  try { await fn(); } catch (e) { cur.failed++; cur.error = String(e?.stack ?? e).split("\n").slice(0, 3).join(" ").slice(0, 300); rows.push({ step: n, check: "step completed without error", result: "FAIL", info: cur.error }); console.log(`  FAIL ${n} exception | ${cur.error}`); }
  cur.verdict = cur.failed === 0 && cur.checks > 0 ? "PASS" : "FAIL"; console.log(`== STEP ${n} ${cur.verdict} (${cur.checks - cur.failed}/${cur.checks} checks)`);
}
const must = (v, what) => { if (v === undefined || v === null || v === "") throw new Error(`prerequisite missing: ${what}`); return v; };
async function poll(fn, ok, tries = 60, ms = 1000) { let last; for (let i = 0; i < tries; i++) { last = await fn(); if (ok(last)) return last; await sleep(ms); } return last; }

// ---------------------------------------------------------------- browser (lazy, one Chrome, closed at the end)
let browser = null; const pageErrors = [];
async function newPage() {
  if (!browser) { const { chromium } = createRequire(new URL("../package.json", import.meta.url).pathname)("playwright-core"); browser = await chromium.launch({ executablePath: CHROME, headless: true }); }
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } }); const page = await ctx.newPage();
  page.on("pageerror", (e) => pageErrors.push(String(e.message).slice(0, 120)));
  return { ctx, page };
}
async function uiLogin(page, base, path, user, pass) {
  await page.goto(`${base}${path}`, { waitUntil: "networkidle" });
  await page.getByLabel("Tên đăng nhập").fill(user); await page.getByLabel("Mật khẩu").fill(pass);
  await Promise.all([page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 25000 }), page.getByRole("button", { name: "Đăng nhập", exact: true }).click()]);
}

// ---------------------------------------------------------------- state of the company (filled by the steps)
const C = { slug: `meridian-${tag}`, name: `Meridian Logistics ${tag}`, users: {}, orderNo: `GC-${tag.toUpperCase()}`, v: {} };
const U = (key) => `gc${tag}-${key}`;
async function provision(admin, tenantId, key, body) {
  const username = U(key); const r = await admin.call("POST", `/api/v1/admin/tenants/${tenantId}/users`, { username, displayName: `GC ${key}`, ...body });
  if (r.status !== 201 || !r.json?.token) throw new Error(`provision ${key}: ${st(r)}`);
  const a = new S(); await a.fetchCsrf(); const password = pw();
  const act = await a.call("POST", "/api/v1/auth/activation/complete", { token: r.json.token, password }); if (act.status !== 200) throw new Error(`activate ${key}: ${st(act)}`);
  const s = new S(); const l = await s.login(username, password); if (l.status !== 200) throw new Error(`login ${key}: ${st(l)}`);
  return { s, username, password, id: r.json.userId };
}
const rowsOf = (r) => r.json?.result?.rows ?? r.json?.rows ?? [];

// ================================================================= 01 System Admin creates Company
let SA;
await step("01", "System Admin creates Company", async () => {
  SA = new S(); const l = await SA.login(SA_USER, SA_PASSWORD); chk("system admin login", l.status === 200, st(l));
  const r = await SA.call("POST", "/api/v1/admin/tenants", { slug: C.slug, name: C.name }); C.t = r.json?.id;
  chk("POST /admin/tenants -> 201 ACTIVE", r.status === 201 && r.json?.status === "ACTIVE" && !!C.t, st(r));
  const g = await SA.call("GET", `/api/v1/admin/tenants/${must(C.t, "tenant id")}`); chk("company readable and ACTIVE", g.status === 200 && g.json?.slug === C.slug, st(g));
  const dup = await SA.call("POST", "/api/v1/admin/tenants", { slug: C.slug, name: "dup" }); chk("ERROR: duplicate slug -> 409 TENANT_SLUG_TAKEN", dup.status === 409 && dup.json?.code === "TENANT_SLUG_TAKEN", st(dup));
  const bad = await SA.call("POST", "/api/v1/admin/tenants", { slug: "Bad Slug!", name: "x" }); chk("ERROR: malformed slug -> 400", bad.status === 400, st(bad));
});

// ================================================================= 02 assign Tenant Admin
await step("02", "System Admin assigns Tenant Admin", async () => {
  const an = await provision(SA, must(C.t, "tenant id"), "an", { tenantRole: "TENANT_ADMIN" }); C.users.an = an; chk("Tenant Admin account created through the product route and activated", !!an.id);
  const me = await an.s.call("GET", "/api/v1/auth/me"); const perms = [...(me.json?.permissions ?? [])].sort();
  chk("/auth/me: tenantRole TENANT_ADMIN, systemAdmin=false, exactly the 8 tenant codes", me.json?.tenantRole === "TENANT_ADMIN" && me.json?.systemAdmin === false && perms.length === 8, `${me.json?.tenantRole} perms=${perms.join(",")}`);
  const dupUser = await SA.call("POST", `/api/v1/admin/tenants/${C.t}/users`, { username: U("an"), displayName: "x", tenantRole: "MEMBER" }); chk("ERROR: username taken -> 409 USERNAME_TAKEN", dupUser.status === 409 && dupUser.json?.code === "USERNAME_TAKEN", st(dupUser));
});

// ================================================================= 03 Tenant Admin logs in (API + real Admin portal)
await step("03", "Tenant Admin logs in", async () => {
  const an = must(C.users.an, "Tenant Admin");
  const l = await new S().login(an.username, an.password); chk("API login 200", l.status === 200, st(l));
  const bad = await new S().login(an.username, "wrong-password-1A"); chk("ERROR: wrong password -> 401", bad.status === 401, st(bad));
  const me = (await an.s.call("GET", "/api/v1/auth/me")).json; chk("no workspace and no project scope yet; one tenant", (me?.workspaces ?? []).length === 0 && (me?.tenants ?? []).length === 1);
  const plat = await an.s.call("GET", "/api/v1/admin/tenants"); chk("ERROR: platform-only route as Tenant Admin -> 403 ADMIN_REQUIRED", plat.status === 403, st(plat));
  const { ctx, page } = await newPage();
  try { await uiLogin(page, ADMIN, "/admin/login", an.username, an.password); await page.waitForSelector("text=/Công ty của tôi/", { timeout: 20000 }); chk("UI: Admin portal admits the Tenant Admin and shows 'Công ty của tôi'", true); }
  catch (e) { chk("UI: Admin portal admits the Tenant Admin and shows 'Công ty của tôi'", false, String(e.message).slice(0, 120)); } finally { await ctx.close(); }
});

// ================================================================= 04 Tenant Admin creates Workspace (+ first Workspace Admin)
await step("04", "Tenant Admin creates Workspace and its first Workspace Admin", async () => {
  const an = must(C.users.an, "Tenant Admin").s;
  const w = await an.call("POST", `/api/v1/admin/tenants/${C.t}/workspaces`, { name: `Operations ${tag}` }); C.ws = w.json?.id; chk("POST /admin/tenants/{t}/workspaces -> 201 with this tenantId", w.status === 201 && w.json?.tenantId === C.t, st(w));
  const wa = await provision(an, C.t, "binh", { tenantRole: "MEMBER", workspaceId: must(C.ws, "workspace id"), workspaceRole: "WORKSPACE_ADMIN" }); C.users.binh = wa; chk("first Workspace Admin created and activated", !!wa.id);
  const me = (await wa.s.call("GET", "/api/v1/auth/me")).json; const ws = (me?.workspaces ?? []).find((x) => x.id === C.ws);
  chk("Workspace Admin /auth/me lists Operations with WORKFLOW_MANAGE and DATA_MUTATE", !!ws && (ws.permissions ?? []).includes("WORKFLOW_MANAGE") && (ws.permissions ?? []).includes("DATA_MUTATE"), (ws?.permissions ?? []).join(","));
  const peek = await an.call("GET", `/api/v1/workspaces/${C.ws}/projects`); chk("ERROR: the Tenant Admin is not a workspace member -> 404 WORKSPACE_NOT_FOUND", peek.status === 404, st(peek));
  const blank = await an.call("POST", `/api/v1/admin/tenants/${C.t}/workspaces`, { name: "" }); chk("ERROR: blank workspace name -> 400", blank.status === 400, st(blank));
});

// ================================================================= 05 create Project (Workspace Admin)
await step("05", "Workspace Admin creates the Project", async () => {
  const wa = must(C.users.binh, "Workspace Admin").s;
  const r = await wa.call("POST", `/api/v1/workspaces/${C.ws}/projects`, { name: `Order Approvals ${tag}`, appType: "PAGE_SCHEMA" }); C.p = r.json?.id; chk("POST /projects -> 201", r.status === 201 && !!C.p, st(r));
  C.PB = `/api/v1/workspaces/${C.ws}/projects/${must(C.p, "project id")}`;
  const sc = await wa.call("GET", `${C.PB}/schema`); chk("initial schema readable (revision, version)", sc.status === 200 && sc.json?.revision !== undefined, st(sc));
  const an = C.users.an.s; const cross = await an.call("POST", `/api/v1/workspaces/${C.ws}/projects`, { name: "x", appType: "PAGE_SCHEMA" }); chk("ERROR: Tenant Admin (not a member) cannot create a project -> 404", cross.status === 404, st(cross));
  const blank = await wa.call("POST", `/api/v1/workspaces/${C.ws}/projects`, { name: "", appType: "PAGE_SCHEMA" }); chk("ERROR: blank project name -> 400", blank.status === 400, st(blank));
});

// ================================================================= 06 create users (Tenant Admin)
await step("06", "Tenant Admin creates the users", async () => {
  const an = must(C.users.an, "Tenant Admin").s;
  C.users.em = await provision(an, C.t, "em", { tenantRole: "MEMBER", workspaceId: C.ws, workspaceRole: "EDITOR" });
  C.users.giang = await provision(an, C.t, "giang", { tenantRole: "MEMBER", workspaceId: C.ws, workspaceRole: "PUBLISHER" });
  C.users.chi = await provision(an, C.t, "chi", { tenantRole: "MEMBER", workspaceId: C.ws, workspaceRole: "WORKSPACE_ADMIN" });
  C.users.dung = await provision(an, C.t, "dung", { tenantRole: "MEMBER", workspaceId: C.ws, workspaceRole: "WORKSPACE_ADMIN" });
  chk("em (workspace EDITOR), giang (PUBLISHER), chi and dung (WORKSPACE_ADMIN) created, activated and able to log in", ["em", "giang", "chi", "dung"].every((k) => !!C.users[k]?.id));
  const members = await an.call("GET", `/api/v1/admin/tenants/${C.t}/members`); chk("Tenant Admin lists the company members", members.status === 200 && JSON.stringify(members.json).includes(U("dung")), st(members));
  const noRight = await C.users.binh.s.call("POST", `/api/v1/admin/tenants/${C.t}/users`, { username: U("zz"), displayName: "zz", tenantRole: "MEMBER" }); chk("ERROR: a Workspace Admin cannot create accounts -> 403", noRight.status === 403, st(noRight));
});

// ================================================================= 07 assign permissions
await step("07", "Workspace Admin assigns project permissions", async () => {
  const wa = must(C.users.binh, "Workspace Admin").s;
  const a = await wa.call("POST", `${C.PB}/members`, { username: C.users.em.username, role: "EDITOR" }); chk("em added as project EDITOR", [200, 201].includes(a.status), st(a));
  const b = await wa.call("POST", `${C.PB}/members`, { username: C.users.giang.username, role: "PUBLISHER" }); chk("giang added as project PUBLISHER", [200, 201].includes(b.status), st(b));
  const me = (await C.users.em.s.call("GET", "/api/v1/auth/me")).json; const scope = (me?.projectScopes ?? []).find((x) => x.projectId === C.p || x.id === C.p);
  const codes = scope?.permissions ?? []; chk("em project scope: APP_EDIT, no APP_PUBLISH, no DATA_MUTATE", codes.includes("APP_EDIT") && !codes.includes("APP_PUBLISH") && !codes.includes("DATA_MUTATE"), codes.join(","));
  const g = (await C.users.giang.s.call("GET", "/api/v1/auth/me")).json; const gs = (g?.projectScopes ?? []).find((x) => x.projectId === C.p || x.id === C.p)?.permissions ?? [];
  chk("giang project scope: APP_PUBLISH, no APP_EDIT", gs.includes("APP_PUBLISH") && !gs.includes("APP_EDIT"), gs.join(","));
  const self = await wa.call("PATCH", `/api/v1/workspaces/${C.ws}/members/${C.users.binh.id}`, { role: "VIEWER" }); chk("ERROR: nobody changes their own role -> 403 SELF_GRANT_FORBIDDEN (or last-admin 409)", [403, 409].includes(self.status), st(self));
  const dup = await wa.call("POST", `${C.PB}/members`, { username: C.users.em.username, role: "EDITOR" }); chk("ERROR: already a member -> 409", dup.status === 409, st(dup));
});

// ================================================================= 08 organization structure (Tenant Admin)
await step("08", "Tenant Admin creates the organization structure", async () => {
  const an = must(C.users.an, "Tenant Admin").s; const O = `/api/v1/admin/tenants/${C.t}`;
  const t1 = await an.call("POST", `${O}/organization-unit-types`, { name: "Company", code: "company" }); const t2 = await an.call("POST", `${O}/organization-unit-types`, { name: "Department", code: "department" });
  chk("two unit types created (version 0)", t1.status === 201 && t2.status === 201, `${st(t1)} / ${st(t2)}`);
  const hq = await an.call("POST", `${O}/organization-units`, { typeId: t1.json?.id, name: "HQ", code: "HQ" }); const dp = await an.call("POST", `${O}/organization-units`, { typeId: t2.json?.id, name: "Dispatch", code: "DISPATCH", parentId: hq.json?.id });
  C.unitDispatch = dp.json?.id; chk("units HQ -> Dispatch created", hq.status === 201 && dp.status === 201, `${st(hq)} / ${st(dp)}`);
  const p1 = await an.call("POST", `${O}/positions`, { name: "Dispatch Manager", code: "DISPATCH_MGR" }); const p2 = await an.call("POST", `${O}/positions`, { name: "Dispatcher", code: "DISPATCHER" });
  C.posMgr = p1.json?.id; C.posDisp = p2.json?.id; chk("positions created", p1.status === 201 && p2.status === 201, `${st(p1)} / ${st(p2)}`);
  const tree = await an.call("GET", `${O}/organization-units`); chk("tree readable", tree.status === 200 && JSON.stringify(tree.json).includes("Dispatch"), st(tree));
  const dupCode = await an.call("POST", `${O}/organization-units`, { typeId: t2.json?.id, name: "Dispatch 2", code: "DISPATCH", parentId: hq.json?.id }); chk("ERROR: sibling code reuse -> 409 ORG_UNIT_CODE_TAKEN", dupCode.status === 409, st(dupCode));
  const sys = await SA.call("POST", `${O}/organization-unit-types`, { name: "zz", code: "zz" }); chk("ERROR: a SYSTEM_ADMIN holds no organization permission -> 403", sys.status === 403, st(sys));
});

// ================================================================= 09 employees (Tenant Admin)
await step("09", "Tenant Admin places the employees in the organization", async () => {
  const an = must(C.users.an, "Tenant Admin").s; const O = `/api/v1/admin/tenants/${C.t}`;
  for (const [key, rel, pos] of [["dung", "MANAGER", C.posMgr], ["chi", "MEMBER", C.posDisp]]) {
    const m = await an.call("POST", `${O}/employees/${C.users[key].id}/organization-memberships`, { organizationUnitId: must(C.unitDispatch, "unit"), relationType: rel });
    chk(`${key}: membership in Dispatch (${rel})`, m.status === 201, st(m));
    const p = await an.call("POST", `${O}/employees/${C.users[key].id}/positions`, { membershipId: m.json?.id, positionId: pos }); chk(`${key}: position assigned`, p.status === 201, st(p));
  }
  const d = await an.call("GET", `${O}/employees/${C.users.dung.id}`); chk("employee read shows the membership", d.status === 200 && JSON.stringify(d.json).includes("MANAGER"), st(d));
  const dupM = await an.call("POST", `${O}/employees/${C.users.dung.id}/organization-memberships`, { organizationUnitId: C.unitDispatch, relationType: "MEMBER" }); chk("ERROR: duplicate membership -> 409", dupM.status === 409, st(dupM));
  const unk = await an.call("POST", `${O}/employees/${C.users.chi.id}/positions`, { membershipId: randomUUID(), positionId: randomUUID() }); chk("ERROR: unknown membership / position -> 404", unk.status === 404, st(unk));
});

// ================================================================= 10 Editor enters Studio (API + real Studio)
await step("10", "Editor enters Studio", async () => {
  const em = must(C.users.em, "Editor");
  const me = (await em.s.call("GET", "/api/v1/auth/me")).json; chk("Editor /auth/me has a project scope for the app", (me?.projectScopes ?? []).some((x) => x.projectId === C.p || x.id === C.p));
  const list = await em.s.call("GET", `/api/v1/workspaces/${C.ws}/projects`); chk("project is in the Editor's list", list.status === 200 && JSON.stringify(list.json).includes(C.p), st(list));
  const noEdit = await C.users.giang.s.call("PATCH", `${C.PB}/schema`, { expectedRevision: 0, operations: [{ type: "UPDATE_SITE", props: { title: "x" } }] }); chk("ERROR: the Publisher cannot edit -> 403", noEdit.status === 403, st(noEdit));
  const { ctx, page } = await newPage();
  try { await uiLogin(page, STUDIO, "/studio/login", em.username, em.password); await page.goto(`${STUDIO}/studio/projects/${C.p}/design`, { waitUntil: "domcontentloaded" }); await page.waitForSelector("header.bx-top", { timeout: 30000 }); chk("UI: Studio opens the project in the Builder for the Editor (top bar rendered)", true); }
  catch (e) { chk("UI: Studio opens the project in the Builder for the Editor", false, String(e.message).slice(0, 120)); } finally { await ctx.close(); }
});

// ================================================================= 11 Editor creates the App (first authoring)
const patch = (s, ops, summary) => s.call("PATCH", `${C.PB}/schema`, { expectedRevision: undefined, summary, operations: ops });
const rev = async (s) => (await s.call("GET", `${C.PB}/schema`)).json?.revision;
const edit = async (s, ops, summary) => { const r = await s.call("GET", `${C.PB}/schema`); return s.call("PATCH", `${C.PB}/schema`, { expectedRevision: r.json?.revision, summary, operations: ops }); };
await step("11", "Editor authors the application definition", async () => {
  const em = must(C.users.em, "Editor").s;
  const r = await edit(em, [{ type: "UPDATE_SITE", props: { title: `Order Approvals ${tag}` } }], "gc: site title"); chk("UPDATE_SITE -> 200, revision advanced", r.status === 200, st(r));
  const sc = (await em.call("GET", `${C.PB}/schema`)).json; C.v.hero = (sc?.schema?.sections ?? []).find((s) => s.type === "Hero")?.id; C.v.navbar = (sc?.schema?.sections ?? []).find((s) => s.type === "Navbar")?.id;
  chk("initial home has a Navbar section (for the public data binding)", !!C.v.navbar, JSON.stringify((sc?.schema?.sections ?? []).map((s) => s.type)));
  const stale = await em.call("PATCH", `${C.PB}/schema`, { expectedRevision: 0, operations: [{ type: "UPDATE_SITE", props: { title: "stale" } }] }); chk("ERROR: stale expectedRevision -> 409 REVISION_CONFLICT", stale.status === 409, st(stale));
  const vw = await C.users.chi.s.call("GET", `${C.PB}/schema`); chk("a Workspace Admin reads the same document", vw.status === 200 && vw.json?.schema?.site?.title === `Order Approvals ${tag}`, st(vw));
});

// ================================================================= 12 Editor creates a Page
await step("12", "Editor creates a Page", async () => {
  const em = must(C.users.em, "Editor").s;
  let r = await edit(em, [{ type: "ADD_PAGE", pageId: "orders", props: { slug: "orders", title: "Orders" } }], "gc: page"); chk("ADD_PAGE orders -> 200", r.status === 200, st(r));
  r = await edit(em, [{ type: "ADD_SECTION", pageId: "orders", sectionId: "hero-orders", sectionType: "Hero", props: { title: "Orders board", description: "gc", ctaLabel: "Open" } }], "gc: section"); chk("ADD_SECTION Hero on the page -> 200", r.status === 200, st(r));
  const sc = (await em.call("GET", `${C.PB}/schema`)).json; chk("a fresh GET shows the page", JSON.stringify(sc?.schema?.pages ?? []).includes("orders"));
  const dup = await edit(em, [{ type: "ADD_PAGE", pageId: "orders", props: { slug: "orders2", title: "dup" } }], "gc: dup"); chk("ERROR: duplicate page id -> 400", dup.status === 400, st(dup));
  const bad = await edit(em, [{ type: "ADD_SECTION", pageId: "orders", sectionId: "nope", sectionType: "NoSuchComponent", props: {} }], "gc: bad"); chk("ERROR: component not in the registry -> 400", bad.status === 400, st(bad));
});

// ================================================================= 13 Data Source (Workspace Admin)
const DS = () => `/api/v1/workspaces/${C.ws}/data-sources`;
await step("13", "Workspace Admin configures the Data Sources", async () => {
  const wa = must(C.users.binh, "Workspace Admin").s; const cfg = { host: "127.0.0.1", port: String(DATA_PORT), database: "shop", schemas: "shop" };
  const rw = await wa.call("POST", DS(), { name: `gc-rw-${tag}`, type: "postgres", config: { ...cfg, writable: "true" } }); C.dsRw = rw.json?.id; chk("writable data source created (hasCredential false)", rw.status === 201 && !!C.dsRw, st(rw));
  const ro = await wa.call("POST", DS(), { name: `gc-ro-${tag}`, type: "postgres", config: cfg }); C.dsRo = ro.json?.id; chk("read-only data source created", ro.status === 201 && !!C.dsRo, st(ro));
  const c1 = await wa.call("PUT", `${DS()}/${C.dsRw}/credential`, { credential: { username: "shop_rw", password: RW } }); const c2 = await wa.call("PUT", `${DS()}/${C.dsRo}/credential`, { credential: { username: "shop_ro", password: RO } });
  chk("credentials stored write-only (no secret in the answer)", c1.status === 200 && c2.status === 200 && !JSON.stringify(c1.json).includes(RW) && !JSON.stringify(c2.json).includes(RO), `${st(c1)} / ${st(c2)}`);
  const t1 = await wa.call("POST", `${DS()}/${C.dsRw}/test`, {}); const t2 = await wa.call("POST", `${DS()}/${C.dsRo}/test`, {});
  chk("Test connection over TLS verify-full: ok:true for both", t1.json?.ok === true && t2.json?.ok === true, `rw=${t1.json?.ok}/${t1.json?.code ?? ""} ro=${t2.json?.ok}/${t2.json?.code ?? ""}`);
  const disc = await wa.call("POST", `${DS()}/${C.dsRo}/schema/discover`, {}); const sch = await wa.call("GET", `${DS()}/${C.dsRo}/schema`); chk("schema discovery stores the real tables (orders, customers)", disc.status === 200 && /orders/.test(JSON.stringify(sch.json)) && /customers/.test(JSON.stringify(sch.json)), `${st(disc)} / ${st(sch)}`);
  const em = await C.users.em.s.call("POST", DS(), { name: "ed", type: "postgres", config: cfg }); chk("ERROR: an Editor cannot manage data sources -> 403", em.status === 403, st(em));
  const other = await wa.call("GET", `${DS()}/${randomUUID()}`); chk("ERROR: unknown data source -> 404 (canonical, no oracle)", other.status === 404, st(other));
  // document side: the slots, then the TEST and LIVE bindings
  const r = await edit(C.users.em.s, [{ type: "ADD_DATA_SOURCE", definition: { id: "erp-rw", name: "Shop RW", type: "postgres" } }, { type: "ADD_DATA_SOURCE", definition: { id: "erp-ro", name: "Shop RO", type: "postgres" } }], "gc: slots"); chk("Editor declares the two data source slots -> 200", r.status === 200, st(r));
  for (const mode of ["TEST", "LIVE"]) { const a = await wa.call("PUT", `${C.PB}/data-bindings/${mode}/erp-rw`, { dataSourceId: C.dsRw }); const b = await wa.call("PUT", `${C.PB}/data-bindings/${mode}/erp-ro`, { dataSourceId: C.dsRo }); chk(`${mode} bindings -> 200`, a.status === 200 && b.status === 200, `${st(a)} / ${st(b)}`); }
});

// ================================================================= 14 Query
await step("14", "Create Query definitions and run one", async () => {
  const wa = must(C.users.binh, "Workspace Admin").s; const em = C.users.em.s; const Q = `${DS()}/${must(C.dsRo, "read-only data source")}/queries`;
  const q1 = await wa.call("POST", Q, { queryId: "orders.byno", kind: "SQL", definition: { sql: "SELECT o.order_no, o.status, o.amount FROM shop.orders o WHERE o.order_no = :order_no", params: [{ name: "order_no", type: "STRING" }], maxRows: 5 } });
  const q2 = await wa.call("POST", Q, { queryId: "orders.public", kind: "SQL", definition: { sql: `SELECT order_no || ' ' || status AS name FROM shop.orders WHERE order_no = '${C.orderNo}'`, params: [], maxRows: 1 } });
  chk("approved query definitions created (201)", q1.status === 201 && q2.status === 201, `${st(q1)} / ${st(q2)}`);
  const ops = [
    { type: "ADD_QUERY", definition: { id: "order-byno", name: "Order by number", dataSourceRef: "erp-ro", mode: "READ", operationKey: "orders.byno", params: [{ name: "order_no", type: "STRING" }], maxRows: 5 } },
    { type: "ADD_QUERY", definition: { id: "order-public", name: "Public order status", dataSourceRef: "erp-ro", mode: "READ", operationKey: "orders.public", public: true, params: [], maxRows: 1 } },
    { type: "ADD_MAPPING", definition: { id: "order-map", queryRef: "order-public", fields: [{ from: "name", to: "name" }] } },
    { type: "ADD_MAPPING", definition: { id: "byno-map", queryRef: "order-byno", fields: [{ from: "order_no", to: "order_no" }, { from: "status", to: "status" }, { from: "amount", to: "amount" }] } },
    { type: "ADD_DATA_BINDING", definitionId: "b-brand", definition: { id: "b-brand", sectionId: must(C.v.navbar, "navbar section"), prop: "brand", queryRef: "order-public" } }
  ];
  const r = await edit(em, ops, "gc: queries"); chk("Editor adds the READ queries, the mapping and the binding -> 200", r.status === 200, `${st(r)} ${r.status >= 400 ? JSON.stringify(r.json).slice(0, 160) : ""}`);
  const run = await em.call("POST", `${C.PB}/app-runtime/queries/order-byno/run`, { mode: "TEST", params: { order_no: "SO-1001" }, mappingRef: "byno-map" }); const row = rowsOf(run)[0];
  chk("TEST run returns the real row of shop.orders", run.status === 200 && row?.order_no === "SO-1001", `${st(run)} ${JSON.stringify(row)}`);
  const dbRow = psql("select status from shop.orders where order_no='SO-1001'"); chk("DB-confirmed: status equals the API value", row?.status === dbRow, `api=${row?.status} db=${dbRow}`);
  const wrong = await em.call("POST", `${C.PB}/app-runtime/queries/order-byno/run`, { mode: "TEST", params: { order_no: "x" }, sql: "select 1" }); chk("ERROR: a client-supplied sql is refused -> 400", wrong.status === 400, st(wrong));
  const ed = await em.call("POST", Q, { queryId: "ed.q", kind: "SQL", definition: { sql: "SELECT 1", params: [], maxRows: 1 } }); chk("ERROR: an Editor cannot create a definition -> 403", ed.status === 403, st(ed));
});

// ================================================================= 15 Mutation (Workspace Admin)
await step("15", "Workspace Admin defines the Mutations", async () => {
  const wa = must(C.users.binh, "Workspace Admin").s; const M = `${DS()}/${must(C.dsRw, "writable data source")}/mutations`;
  const params = [{ name: "order_no", type: "STRING", required: true }, { name: "customer_id", type: "INTEGER", required: true }, { name: "status", type: "STRING", required: true }, { name: "amount", type: "NUMBER", required: true }];
  const m1 = await wa.call("POST", M, { mutationId: "orders.create", kind: "CREATE", definition: { target: "shop.orders returning=order_no,status", params, invalidates: [], entity: "order" } });
  const m2 = await wa.call("POST", M, { mutationId: "orders.setstatus", kind: "UPDATE", definition: { target: "shop.orders key=order_no returning=order_no,status", params: [{ name: "order_no", type: "STRING", required: true }, { name: "status", type: "STRING", required: true }] } });
  chk("mutation definitions orders.create / orders.setstatus -> 201", m1.status === 201 && m2.status === 201, `${st(m1)} / ${st(m2)}`);
  const dup = await wa.call("POST", M, { mutationId: "orders.create", kind: "CREATE", definition: { target: "shop.orders", params } }); chk("ERROR: duplicate id -> 409", dup.status === 409, st(dup));
  const ed = await C.users.em.s.call("POST", M, { mutationId: "ed.m", kind: "CREATE", definition: { target: "shop.orders", params } }); chk("ERROR: an Editor cannot define mutations -> 403", ed.status === 403, st(ed));
  const ro = await wa.call("POST", `${DS()}/${C.dsRo}/mutations`, { mutationId: "ro.m", kind: "CREATE", definition: { target: "shop.orders", params } }); chk("ERROR: a mutation on a read-only source is refused (4xx)", ro.status >= 400 && ro.status < 500, st(ro));
});

// ================================================================= 16 Action (Editor) + workflow definition
await step("16", "Editor creates the Actions and the approval Workflow", async () => {
  const em = must(C.users.em, "Editor").s;
  const orderInputs = [{ name: "order_no", type: "STRING", required: true }, { name: "customer_id", type: "INTEGER", required: true }, { name: "status", type: "STRING", required: true }, { name: "amount", type: "NUMBER", required: true }];
  const ops = [
    { type: "ADD_QUERY", definition: { id: "order-create", name: "Create order", dataSourceRef: "erp-rw", mode: "WRITE", operationKey: "orders.create", params: orderInputs.map(({ name, type }) => ({ name, type })) } },
    { type: "ADD_QUERY", definition: { id: "order-status", name: "Set status", dataSourceRef: "erp-rw", mode: "WRITE", operationKey: "orders.setstatus", params: [{ name: "order_no", type: "STRING" }, { name: "status", type: "STRING" }] } },
    { type: "ADD_SECTION", pageId: "orders", sectionId: "form-order", sectionType: "ContactForm", props: { heading: "New order", submitLabel: "Submit" } },
    { type: "ADD_ACTION", definition: { id: "submit-order", name: "Submit order", type: "CREATE_RECORD", queryRef: "order-create", trigger: { sectionId: "form-order", event: "onSubmit" }, inputs: orderInputs, onSuccess: ["start-approval"] } },
    { type: "ADD_ACTION", definition: { id: "start-approval", name: "Start approval", type: "START_WORKFLOW", workflowRef: "wf-order-approval", idempotency: "REQUIRED", inputs: [{ name: "order_no", type: "STRING", required: true }, { name: "amount", type: "NUMBER", required: false }], inputMapping: { order_no: { source: "PREVIOUS_RESULT", path: "output.order_no" }, amount: { source: "LITERAL", value: 7 } } } },
    { type: "ADD_ACTION", definition: { id: "mark-approved", name: "Mark approved", type: "UPDATE_RECORD", queryRef: "order-status", inputs: [{ name: "recordId", type: "STRING", required: true }, { name: "status", type: "STRING", required: true }] } },
    { type: "ADD_WORKFLOW_REF", definition: { id: "wf-order-approval", name: "Order approval", trigger: "MANUAL", startStepId: "validate", steps: [
      { id: "validate", kind: "BRANCH", branches: [{ condition: { op: "GT", left: { from: "INPUT", path: "amount" }, right: { from: "LITERAL", value: 0 } }, next: "approve" }], defaultNext: "invalid" },
      { id: "approve", kind: "APPROVAL", approval: { title: "Approve the order", approvers: [{ kind: "USER", userId: must(C.users.dung?.id, "approver id") }], requiredApprovals: 1, expiresInSeconds: 3600 }, next: "mark" },
      { id: "mark", kind: "ACTION", actionRef: "mark-approved", inputs: { recordId: { from: "INPUT", path: "order_no" }, status: { from: "LITERAL", value: "approved" } }, retry: { maxAttempts: 3, initialBackoffMillis: 1000 }, next: "done" },
      { id: "done", kind: "END" }, { id: "invalid", kind: "END" }] } }
  ];
  const r = await edit(em, ops, "gc: actions and workflow"); chk("Editor defines the queries, actions and the approval workflow -> 200", r.status === 200, `${st(r)} ${r.status >= 400 ? JSON.stringify(r.json).slice(0, 200) : ""}`);
  const t = await C.users.binh.s.call("POST", `${C.PB}/app-runtime/actions/submit-order/execute`, { mode: "TEST", inputs: { order_no: C.orderNo, customer_id: 1, status: "open", amount: 7 } });
  chk("TEST execute of the write action (Workspace Admin) is WOULD_RUN", t.status === 200 && t.json?.status === "WOULD_RUN", st(t) + " " + (t.json?.status ?? ""));
  const tEd = await em.call("POST", `${C.PB}/app-runtime/actions/submit-order/execute`, { mode: "TEST", inputs: { order_no: C.orderNo, customer_id: 1, status: "open", amount: 7 } });
  chk("ERROR: an Editor cannot run even the TEST of a data-mutating action (DATA_MUTATE) -> 403", tEd.status === 403, st(tEd));
  chk("DB-confirmed: the TEST run wrote no row", psql(`select count(*) from shop.orders where order_no='${C.orderNo}'`) === "0");
  const bad = await edit(em, [{ type: "ADD_ACTION", definition: { id: "bad", name: "bad", type: "CREATE_RECORD", queryRef: "no-such-query", inputs: [] } }], "gc: bad"); chk("ERROR: an action with an unknown queryRef -> 422 SCHEMA_INVALID", bad.status === 422, st(bad));
  const vw = await C.users.giang.s.call("PATCH", `${C.PB}/schema`, { expectedRevision: await rev(em), operations: [{ type: "UPDATE_SITE", props: { title: "publisher must not edit" } }] }); chk("ERROR: the Publisher cannot patch the schema -> 403", vw.status === 403, st(vw));
});

// ================================================================= 17 start the Workflow (LIVE)
const B = () => `${C.PB}/app-runtime`;
const waitDep = async (s, id) => poll(async () => (await s.call("GET", `${C.PB}/deployments/${id}`)).json, (d) => ["RUNNING", "FAILED", "ROLLED_BACK"].includes(d?.status), 120, 1000);
await step("17", "Requester starts the Workflow (LIVE)", async () => {
  const wa = must(C.users.binh, "Workspace Admin").s; const chi = must(C.users.chi, "Requester").s;
  const pub = await wa.call("POST", `${C.PB}/publish`, { visibility: "PRIVATE", expectedRevision: await rev(wa) }, { "idempotency-key": `gc-r0-${tag}` }); const d = await waitDep(wa, pub.json?.id);
  chk("precondition: a PRIVATE release R0 is RUNNING (LIVE needs an active release)", pub.status === 202 && d?.status === "RUNNING", `${st(pub)} ${d?.status} ${d?.status === "FAILED" ? String(d?.error).slice(0, 120) : ""}`);
  const noKey = await chi.call("POST", `${B()}/actions/submit-order/execute`, { mode: "LIVE", inputs: { order_no: C.orderNo, customer_id: 1, status: "open", amount: 7 } }); chk("ERROR: a LIVE write without idempotencyKey -> 400 IDEMPOTENCY_KEY_REQUIRED", noKey.status === 400, st(noKey));
  const ed = await C.users.em.s.call("POST", `${B()}/actions/submit-order/execute`, { mode: "LIVE", inputs: { order_no: C.orderNo, customer_id: 1, status: "open", amount: 7 }, idempotencyKey: `gc-ed-${tag}` }); chk("ERROR: a project Editor cannot run the LIVE write -> 403 and no row", ed.status === 403 && psql(`select count(*) from shop.orders where order_no='${C.orderNo}'`) === "0", st(ed));
  C.submitKey = `gc-submit-${tag}`; const inputs = { order_no: C.orderNo, customer_id: 1, status: "open", amount: 7 };
  const r = await chi.call("POST", `${B()}/actions/submit-order/execute`, { mode: "LIVE", inputs, idempotencyKey: C.submitKey }); C.submitInputs = inputs;
  chk("LIVE submit-order -> 200 OK", r.status === 200 && r.json?.status === "OK", `${st(r)} ${r.json?.status ?? ""} ${r.json?.error?.code ?? ""}`);
  chk("DB-confirmed: the row exists with status open", psql(`select status||'|'||amount from shop.orders where order_no='${C.orderNo}'`) === "open|7.00", psql(`select status from shop.orders where order_no='${C.orderNo}'`));
  const fu = (r.json?.followUps ?? []).find((q) => q.actionId === "start-approval"); C.runId = fu?.output?.runId ?? fu?.runId ?? fu?.output?.id;
  chk("the onSuccess START_WORKFLOW follow-up ran and returned a runId", fu?.status === "OK" && !!C.runId, JSON.stringify(fu ?? r.json?.followUps).slice(0, 200));
  const replay = await chi.call("POST", `${B()}/actions/submit-order/execute`, { mode: "LIVE", inputs, idempotencyKey: C.submitKey }); chk("same idempotency key replays: still one row", replay.status === 200 && psql(`select count(*) from shop.orders where order_no='${C.orderNo}'`) === "1", st(replay));
  const reuse = await chi.call("POST", `${B()}/actions/submit-order/execute`, { mode: "LIVE", inputs: { ...inputs, amount: 9 }, idempotencyKey: C.submitKey }); chk("ERROR: same key with other inputs -> 409 IDEMPOTENCY_KEY_REUSED", reuse.status === 409, st(reuse));
});

// ================================================================= 18 the Workflow waits for Approval
await step("18", "Workflow waits for Approval", async () => {
  const chi = must(C.users.chi, "Requester").s; must(C.runId, "workflow run id");
  const v = await poll(async () => (await chi.call("GET", `${B()}/workflow-runs/${C.runId}`)).json, (j) => j?.status === "WAITING" || ["SUCCEEDED", "FAILED", "CANCELLED"].includes(j?.status), 40, 1000);
  chk("run is WAITING at the approval step", v?.status === "WAITING", `${v?.status} ${v?.errorCode ?? ""}`);
  const ap = (v?.steps ?? []).find((s) => s.stepId === "approve"); C.approvalId = ap?.approvalId; chk("the approve step carries a nullable approvalId (set)", !!C.approvalId, JSON.stringify(ap ?? {}).slice(0, 160));
  chk("the steps after the approval did not run (row still open)", psql(`select status from shop.orders where order_no='${C.orderNo}'`) === "open");
  const other = await C.users.em.s.call("GET", `${B()}/workflow-runs/${C.runId}`); chk("ERROR: a stranger project member cannot read the run -> 404", other.status === 404 || other.status === 403, st(other));
});

// ================================================================= 19 manager approves
await step("19", "Manager approves", async () => {
  const dung = must(C.users.dung, "Manager").s; const D = `${B()}/workflow-runs/${must(C.runId, "run")}/approvals/${must(C.approvalId, "approval")}/decision`;
  const req = await C.users.chi.s.call("POST", D, { decision: "APPROVE" }); chk("ERROR: the requester cannot decide -> 403", req.status === 403, st(req));
  const un = await C.users.binh.s.call("POST", D, { decision: "APPROVE" }); chk("ERROR: a workspace admin the definition did not name -> 403", un.status === 403, st(un));
  const bad = await dung.call("POST", D, { decision: "MAYBE" }); chk("ERROR: decision MAYBE -> 400", bad.status === 400, st(bad));
  const idf = await dung.call("POST", D, { decision: "APPROVE", userId: randomUUID() }); chk("ERROR: an identity field in the body -> 400", idf.status === 400, st(idf));
  const r = await dung.call("POST", D, { decision: "APPROVE", comment: "ok" }); chk("the named approver APPROVES -> 200 APPROVED", r.status === 200 && r.json?.approvalStatus === "APPROVED", `${st(r)} ${r.json?.approvalStatus ?? ""}`);
  const dup = await dung.call("POST", D, { decision: "APPROVE", comment: "ok" }); chk("the same decision again is idempotent (200)", dup.status === 200, st(dup));
  const opp = await dung.call("POST", D, { decision: "REJECT" }); chk("ERROR: the opposite decision -> 409 (conflict / already decided)", opp.status === 409, st(opp));
});

// ================================================================= 20 UPDATE_RECORD changes a real row
await step("20", "UPDATE_RECORD changes the real row", async () => {
  const chi = must(C.users.chi, "Requester").s;
  const v = await poll(async () => (await chi.call("GET", `${B()}/workflow-runs/${C.runId}`)).json, (j) => ["SUCCEEDED", "FAILED", "CANCELLED"].includes(j?.status), 60, 1000);
  chk("the run SUCCEEDED after the decision", v?.status === "SUCCEEDED", `${v?.status} ${v?.errorCode ?? ""} ${v?.errorMessage ?? ""}`);
  chk("DB-confirmed: shop.orders.status of the order is now approved", psql(`select status from shop.orders where order_no='${C.orderNo}'`) === "approved", psql(`select status from shop.orders where order_no='${C.orderNo}'`));
  chk("DB-confirmed: exactly one row for the order number", psql(`select count(*) from shop.orders where order_no='${C.orderNo}'`) === "1");
  const del = psql("select has_table_privilege('shop_rw','shop.orders','DELETE')"); chk("the writer role has no DELETE privilege (a delete can never happen)", del === "f", del);
});

// ================================================================= 21 Publisher publishes PUBLIC
const SITEURL = () => `${C.PB}/site`;
await step("21", "Publisher publishes PUBLIC", async () => {
  const gi = must(C.users.giang, "Publisher").s;
  const ed = await C.users.em.s.call("POST", `${C.PB}/publish`, { visibility: "PUBLIC", expectedRevision: await rev(gi) }, { "idempotency-key": `gc-ed-pub-${tag}` }); chk("ERROR: an Editor cannot publish -> 403", ed.status === 403, st(ed));
  const noAck = await gi.call("PUT", `${C.PB}/publish-config`, { mode: "STATIC", visibility: "PUBLIC", requiresAuth: false, acknowledgePublicData: false }); chk("ERROR: PUBLIC policy without the public-data acknowledgement is refused (422 PUBLIC_DATA_NOT_APPROVED or 400)", [422, 400].includes(noAck.status), st(noAck));
  const cfg = await gi.call("PUT", `${C.PB}/publish-config`, { mode: "STATIC", visibility: "PUBLIC", requiresAuth: false, acknowledgePublicData: true }); chk("PUT publish-config stores PUBLIC and the public-data approval", cfg.status === 200 && cfg.json?.config?.publicDataApproved === true, st(cfg));
  const p = await gi.call("POST", `${C.PB}/publish`, { visibility: "PUBLIC", expectedRevision: await rev(gi) }, { "idempotency-key": `gc-v1-${tag}` }); C.v.dep1 = p.json?.id; const d = await waitDep(gi, C.v.dep1);
  chk("publish PUBLIC -> 202 and the deployment is RUNNING", p.status === 202 && d?.status === "RUNNING", `${st(p)} ${d?.status} ${d?.status === "FAILED" ? String(d?.error).slice(0, 160) : ""}`);
  const si = await gi.call("GET", SITEURL()); C.slug = si.json?.slug; C.siteUrl = si.json?.url;
  chk("site online, PUBLIC, pointing at this deployment", si.json?.online === true && si.json?.visibility === "PUBLIC" && si.json?.currentDeploymentId === C.v.dep1, JSON.stringify({ online: si.json?.online, vis: si.json?.visibility }));
});

// ================================================================= 22 Visitor opens the site
const siteBase = () => `${SITES}/${must(C.slug, "site slug")}/`;
const visitorHtml = async () => { const r = await fetch(siteBase(), { redirect: "manual" }); return { status: r.status, text: await r.text(), etag: r.headers.get("etag"), cookie: r.headers.get("set-cookie") }; };
await step("22", "Visitor opens the site", async () => {
  const v = await visitorHtml(); C.v.etag1 = v.etag; C.v.html1 = v.text;
  chk("anonymous GET of the site -> 200 text/html, no cookie is set", v.status === 200 && /<html/i.test(v.text) && !v.cookie, `${v.status} cookie=${!!v.cookie}`);
  const cfg = await fetch(`${siteBase()}__factory/config.json`); const j = await cfg.json().catch(() => null); chk("runtime config: no-store, releaseId = the active deployment, apiBase same-origin", cfg.status === 200 && j?.releaseId === C.v.dep1 && typeof j?.apiBase === "string", JSON.stringify({ rel: j?.releaseId === C.v.dep1, api: j?.apiBase }));
  const nf = await fetch(`${SITES}/no-such-site-${tag}/`); chk("ERROR: an unknown slug -> 404", nf.status === 404, String(nf.status));
  const { ctx, page } = await newPage();
  try { const resp = await page.goto(siteBase(), { waitUntil: "networkidle" }); chk("UI: the page opens in a real browser", resp?.status() === 200); } catch (e) { chk("UI: the page opens in a real browser", false, String(e.message).slice(0, 100)); } finally { await ctx.close(); }
});

// ================================================================= 23 public data loads
await step("23", "Public data loads for the visitor", async () => {
  const want = `${C.orderNo} approved`; const base = `${SITES}/${must(C.slug, "slug")}`;
  const r = await fetch(`${base}/_data/queries/order-public/run`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ params: {} }) }); const j = await r.json().catch(() => null);
  chk("anonymous POST of the public query -> 200 with the real row (order number + approved)", r.status === 200 && JSON.stringify(j).includes(want), `${r.status} ${JSON.stringify(j).slice(0, 160)}`);
  const priv = await fetch(`${base}/_data/queries/order-byno/run`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ params: { order_no: "SO-1001" } }) }); chk("ERROR: a non-public query -> 404 QUERY_NOT_FOUND (no oracle)", priv.status === 404, String(priv.status));
  const extra = await fetch(`${base}/_data/queries/order-public/run`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ params: {}, tenantId: C.t }) }); chk("ERROR: an authority key in the body -> 400", extra.status === 400, String(extra.status));
  const { ctx, page } = await newPage();
  try { await page.goto(siteBase(), { waitUntil: "networkidle" }); await page.waitForFunction((t) => document.body.innerText.includes(t), want, { timeout: 25000 }); chk("UI: the visitor's page renders the real row from PostgreSQL", true); }
  catch (e) { chk("UI: the visitor's page renders the real row from PostgreSQL", false, `${String(e.message).slice(0, 80)} | text=${(await page.evaluate(() => document.body.innerText).catch(() => "")).slice(0, 120)}`); } finally { await ctx.close(); }
});

// ================================================================= 24 publish v2
await step("24", "Editor edits, Publisher publishes v2", async () => {
  const em = must(C.users.em, "Editor").s, gi = must(C.users.giang, "Publisher").s; const marker = `GC v2 ${tag}`; C.v.marker2 = marker;
  const e = await edit(em, [{ type: "UPDATE_SECTION", sectionId: must(C.v.hero, "home hero section"), props: { title: marker } }], "gc: v2 marker"); chk("Editor changes the page marker -> 200", e.status === 200, st(e));
  const stale = await gi.call("POST", `${C.PB}/publish`, { visibility: "PUBLIC", expectedRevision: 0 }, { "idempotency-key": `gc-stale-${tag}` }); chk("ERROR: stale expectedRevision -> 409 REVISION_CONFLICT", stale.status === 409, st(stale));
  const p = await gi.call("POST", `${C.PB}/publish`, { visibility: "PUBLIC", expectedRevision: await rev(gi) }, { "idempotency-key": `gc-v2-${tag}` }); C.v.dep2 = p.json?.id; const d = await waitDep(gi, C.v.dep2);
  chk("publish v2 -> 202 and RUNNING", p.status === 202 && d?.status === "RUNNING", `${st(p)} ${d?.status}`);
  const v = await visitorHtml(); chk("the visitor gets v2 (marker present) and not v1", v.text.includes(marker) && v.etag !== C.v.etag1, `etagChanged=${v.etag !== C.v.etag1}`);
  const dl = await gi.call("GET", `${C.PB}/deployments`); chk("release history lists v1 and v2", dl.status === 200 && JSON.stringify(dl.json).includes(C.v.dep1) && JSON.stringify(dl.json).includes(C.v.dep2), st(dl));
  const reuse = await gi.call("POST", `${C.PB}/publish`, { visibility: "PUBLIC", expectedRevision: await rev(gi) }, { "idempotency-key": `gc-v2-${tag}` }); chk("ERROR: the same idempotency key with another payload is not a second release (409 or replay)", reuse.status === 409 || reuse.status === 202, st(reuse));
});

// ================================================================= 25 rollback to v1
await step("25", "Publisher rolls back to v1", async () => {
  const gi = must(C.users.giang, "Publisher").s;
  const stale = await gi.call("POST", `${SITEURL()}/rollback`, { deploymentId: C.v.dep1, expectedActiveDeploymentId: randomUUID() }); chk("ERROR: stale expectedActiveDeploymentId -> 409 ROLLBACK_STALE", stale.status === 409, st(stale));
  const ed = await C.users.em.s.call("POST", `${SITEURL()}/rollback`, { deploymentId: C.v.dep1, expectedActiveDeploymentId: C.v.dep2 }); chk("ERROR: an Editor cannot roll back -> 403", ed.status === 403, st(ed));
  const r = await gi.call("POST", `${SITEURL()}/rollback`, { deploymentId: C.v.dep1, expectedActiveDeploymentId: C.v.dep2 }); chk("rollback to v1 -> 200, currentDeploymentId = v1", r.status === 200 && r.json?.currentDeploymentId === C.v.dep1, st(r));
  const v = await visitorHtml(); chk("the visitor gets v1 byte for byte (same ETag as before), no v2 marker", v.etag === C.v.etag1 && !v.text.includes(C.v.marker2), `etagSame=${v.etag === C.v.etag1}`);
  const j = await fetch(`${SITES}/${C.slug}/_data/queries/order-public/run`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ params: {} }) }); chk("public data is still served from the restored release", j.status === 200 && (await j.text()).includes(`${C.orderNo} approved`), String(j.status));
  const gone = await gi.call("POST", `${SITEURL()}/rollback`, { deploymentId: C.v.dep2, expectedActiveDeploymentId: C.v.dep1 }); chk("ERROR: v2 is ROLLED_BACK and not restorable -> 400", gone.status === 400, st(gone));
  const { ctx, page } = await newPage();
  try { await page.goto(siteBase(), { waitUntil: "networkidle" }); await page.waitForFunction((t) => document.body.innerText.includes(t), `${C.orderNo} approved`, { timeout: 25000 }); chk("UI: the browser shows v1 with the real public row", !(await page.content()).includes(C.v.marker2)); }
  catch (e) { chk("UI: the browser shows v1 with the real public row", false, String(e.message).slice(0, 100)); } finally { await ctx.close(); }
});

// ================================================================= 26 restart backend / worker
const snap = {};
await step("26", "Restart the backend and the workflow worker", async () => {
  const chi = must(C.users.chi, "Requester").s; snap.audit = (await C.users.binh.s.call("GET", `/api/v1/workspaces/${C.ws}/audit-events?limit=200`)).json; snap.schema = (await C.users.em.s.call("GET", `${C.PB}/schema`)).json?.schema;
  snap.site = (await C.users.giang.s.call("GET", SITEURL())).json; snap.deployments = (await C.users.giang.s.call("GET", `${C.PB}/deployments`)).json;
  chk("pre-restart state captured (schema, site pointer, deployments, audit)", !!snap.schema && !!snap.site);
  if (!RESTART) { chk("restart command configured (GC_RESTART_CMD)", false, "not set"); return; }
  const t0 = Date.now(); let out = ""; try { out = execSync(RESTART, { encoding: "utf8", timeout: 240000, stdio: ["ignore", "pipe", "pipe"] }); chk("the stack tooling restarted the backend (own pid, never kill by name/port)", true); } catch (e) { chk("the stack tooling restarted the backend", false, String(e.message).slice(0, 160)); }
  const ready = await poll(async () => { try { const r = await fetch(`${API}/actuator/health/readiness`); return r.status === 200; } catch { return false; } }, (x) => x === true, 120, 2000);
  chk("readiness UP again", ready === true, `${Math.round((Date.now() - t0) / 1000)}s`);
  const during = await fetch(siteBase()); chk("the published site answers 200 after the restart", during.status === 200, String(during.status));
  const me = await chi.call("GET", "/api/v1/auth/me"); chk("the Redis-backed session of the Requester is still valid", me.status === 200, st(me));
});

// ================================================================= 27 verify the state survives
await step("27", "Company, app, data, workflow and release state survive", async () => {
  const an = C.users.an.s, wa = C.users.binh.s, em = C.users.em.s, gi = C.users.giang.s, chi = C.users.chi.s;
  const t = await an.call("GET", `/api/v1/admin/tenants/${C.t}/members`); chk("company and members present", t.status === 200 && JSON.stringify(t.json).includes(U("dung")), st(t));
  const org = await an.call("GET", `/api/v1/admin/tenants/${C.t}/organization-units`); chk("organization data present", org.status === 200 && JSON.stringify(org.json).includes("Dispatch"), st(org));
  const sc = (await em.call("GET", `${C.PB}/schema`)).json?.schema; chk("project document is deep-equal to before the restart", JSON.stringify(sc) === JSON.stringify(snap.schema));
  const site = (await gi.call("GET", SITEURL())).json; chk("site pointer unchanged (v1 active, pointerVersion same)", site?.currentDeploymentId === snap.site?.currentDeploymentId && site?.pointerVersion === snap.site?.pointerVersion, `${site?.currentDeploymentId === C.v.dep1}`);
  const deps = await gi.call("GET", `${C.PB}/deployments`); chk("release history intact", JSON.stringify(deps.json) === JSON.stringify(snap.deployments));
  const v = await visitorHtml(); chk("the visitor still gets v1 byte for byte (ETag)", v.etag === C.v.etag1, `${v.etag === C.v.etag1}`);
  const ds = await wa.call("GET", `${DS()}/${C.dsRw}`); const qs = await wa.call("GET", `${DS()}/${C.dsRo}/queries`); const ms = await wa.call("GET", `${DS()}/${C.dsRw}/mutations`); const bd = await wa.call("GET", `${C.PB}/data-bindings`);
  chk("data source, query and mutation definitions, bindings present", ds.status === 200 && JSON.stringify(qs.json).includes("orders.public") && JSON.stringify(ms.json).includes("orders.setstatus") && bd.status === 200, `${st(ds)} ${st(qs)} ${st(ms)} ${st(bd)}`);
  const run = (await chi.call("GET", `${B()}/workflow-runs/${C.runId}`)).json; chk("the approved run is still SUCCEEDED", run?.status === "SUCCEEDED", run?.status);
  const replay = await chi.call("POST", `${B()}/actions/submit-order/execute`, { mode: "LIVE", inputs: C.submitInputs, idempotencyKey: C.submitKey }); chk("the idempotency key survived: replay gives no second row", replay.status === 200 && psql(`select count(*) from shop.orders where order_no='${C.orderNo}'`) === "1", st(replay));
  chk("DB-confirmed: the order row is still approved", psql(`select status from shop.orders where order_no='${C.orderNo}'`) === "approved");
  const j = await fetch(`${SITES}/${C.slug}/_data/queries/order-public/run`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ params: {} }) }); chk("public data still loads", j.status === 200 && (await j.text()).includes(`${C.orderNo} approved`), String(j.status));
  const aud = (await wa.call("GET", `/api/v1/workspaces/${C.ws}/audit-events?limit=200`)).json; const n0 = (snap.audit?.items ?? snap.audit ?? []).length, n1 = (aud?.items ?? aud ?? []).length; chk("the audit trail is not shorter", n1 >= n0 && n1 > 0, `${n0} -> ${n1}`);
  const { ctx, page } = await newPage();
  try { await uiLogin(page, ADMIN, "/admin/login", C.users.an.username, C.users.an.password); await page.waitForSelector("text=/Công ty của tôi/", { timeout: 20000 }); chk("UI: the Tenant Admin still signs in to the Admin portal and sees the company", true); }
  catch (e) { chk("UI: the Tenant Admin still signs in to the Admin portal and sees the company", false, String(e.message).slice(0, 100)); } finally { await ctx.close(); }
});

// ================================================================= REGRESSION (not part of the 27): approval across a restart, duplicate decision, tenant and project isolation
async function restartBackend() {
  if (!RESTART) { chk("restart command configured (GC_RESTART_CMD)", false, "not set"); return false; }
  const t0 = Date.now(); try { execSync(RESTART, { encoding: "utf8", timeout: 240000, stdio: ["ignore", "pipe", "pipe"] }); } catch (e) { chk("the stack tooling restarted the backend", false, String(e.message).slice(0, 160)); return false; }
  const ready = await poll(async () => { try { return (await fetch(`${API}/actuator/health/readiness`)).status === 200; } catch { return false; } }, (x) => x === true, 120, 2000);
  chk("readiness UP again", ready === true, `${Math.round((Date.now() - t0) / 1000)}s`); return ready === true;
}
await step("R1", "An approval WAITING survives a backend restart; the decision after it is idempotent", async () => {
  const chi = must(C.users.chi, "Requester").s, dung = must(C.users.dung, "Manager").s; const o2 = `${C.orderNo}-R`;
  const r = await chi.call("POST", `${B()}/actions/submit-order/execute`, { mode: "LIVE", inputs: { order_no: o2, customer_id: 1, status: "open", amount: 7 }, idempotencyKey: `gc-r1-${tag}` });
  const fu = (r.json?.followUps ?? []).find((q) => q.actionId === "start-approval"); const run2 = fu?.output?.runId ?? fu?.runId ?? fu?.output?.id; chk("second order submitted, workflow started", r.status === 200 && !!run2, st(r));
  const v = await poll(async () => (await chi.call("GET", `${B()}/workflow-runs/${must(run2, "run id")}`)).json, (j) => j?.status === "WAITING" || ["SUCCEEDED", "FAILED"].includes(j?.status), 40, 1000);
  const ap = (v?.steps ?? []).find((x) => x.stepId === "approve")?.approvalId; chk("run WAITING with an approvalId before the restart", v?.status === "WAITING" && !!ap, `${v?.status}`);
  if (!(await restartBackend())) return;
  const v2 = (await chi.call("GET", `${B()}/workflow-runs/${run2}`)).json; const ap2 = (v2?.steps ?? []).find((x) => x.stepId === "approve")?.approvalId;
  chk("after the restart the run is still WAITING with the same approvalId (state is in PostgreSQL)", v2?.status === "WAITING" && ap2 === ap, `${v2?.status} same=${ap2 === ap}`);
  chk("DB-confirmed: the order row is still open (nothing ran without the decision)", psql(`select status from shop.orders where order_no='${o2}'`) === "open");
  const D = `${B()}/workflow-runs/${run2}/approvals/${ap}/decision`;
  const d1 = await dung.call("POST", D, { decision: "APPROVE" }); chk("the approver decides AFTER the restart -> 200 APPROVED", d1.status === 200 && d1.json?.approvalStatus === "APPROVED", st(d1));
  const d2 = await dung.call("POST", D, { decision: "APPROVE" }); chk("the same decision again is idempotent (200)", d2.status === 200, st(d2));
  const d3 = await dung.call("POST", D, { decision: "REJECT" }); chk("ERROR: the opposite decision after a final one -> 409", d3.status === 409, st(d3));
  const fin = await poll(async () => (await chi.call("GET", `${B()}/workflow-runs/${run2}`)).json, (j) => ["SUCCEEDED", "FAILED", "CANCELLED"].includes(j?.status), 60, 1000);
  chk("the run SUCCEEDED and the real row is approved (exactly one row)", fin?.status === "SUCCEEDED" && psql(`select status||'|'||count(*) from shop.orders where order_no='${o2}' group by status`) === "approved|1", `${fin?.status}`);
});
await step("R2", "Tenant and project isolation hold for runs, approvals, data sources and documents", async () => {
  const tB = (await SA.call("POST", "/api/v1/admin/tenants", { slug: `meridianb-${tag}`, name: `Meridian B ${tag}` })).json?.id; must(tB, "tenant B");
  const bta = await provision(SA, tB, "bta", { tenantRole: "TENANT_ADMIN" }); const wsB = (await bta.s.call("POST", `/api/v1/admin/tenants/${tB}/workspaces`, { name: `Ops B ${tag}` })).json?.id;
  const bwa = await provision(bta.s, tB, "bwa", { tenantRole: "MEMBER", workspaceId: must(wsB, "workspace B"), workspaceRole: "WORKSPACE_ADMIN" });
  const pB = (await bwa.s.call("POST", `/api/v1/workspaces/${wsB}/projects`, { name: `B app ${tag}`, appType: "PAGE_SCHEMA" })).json?.id; const PBB = `/api/v1/workspaces/${wsB}/projects/${must(pB, "project B")}`;
  const is404 = async (label, r) => chk(`ISOLATION: ${label} -> 404`, r.status === 404, st(r));
  await is404("company B reads company A's workspace", await bwa.s.call("GET", `/api/v1/workspaces/${C.ws}/projects`));
  await is404("company B reads company A's document", await bwa.s.call("GET", `${C.PB}/schema`));
  await is404("company B reads company A's workflow run through its OWN project path", await bwa.s.call("GET", `${PBB}/app-runtime/workflow-runs/${must(C.runId, "run A")}`));
  await is404("company B decides company A's approval through its own project path", await bwa.s.call("POST", `${PBB}/app-runtime/workflow-runs/${C.runId}/approvals/${must(C.approvalId, "approval A")}/decision`, { decision: "APPROVE" }));
  await is404("company B reads company A's data source through its own workspace path", await bwa.s.call("GET", `/api/v1/workspaces/${wsB}/data-sources/${must(C.dsRw, "data source A")}`));
  await is404("Tenant Admin B reads company A's members", await bta.s.call("GET", `/api/v1/admin/tenants/${C.t}/members`));
  // project isolation inside one company: a second project of company A cannot act on the first project's run
  const p2 = (await C.users.binh.s.call("POST", `/api/v1/workspaces/${C.ws}/projects`, { name: `Second app ${tag}`, appType: "PAGE_SCHEMA" })).json?.id; must(p2, "second project");
  await is404("a second project of the same company cannot read the first project's run", await C.users.dung.s.call("GET", `/api/v1/workspaces/${C.ws}/projects/${p2}/app-runtime/workflow-runs/${C.runId}`));
  await is404("a second project of the same company cannot decide the first project's approval", await C.users.dung.s.call("POST", `/api/v1/workspaces/${C.ws}/projects/${p2}/app-runtime/workflow-runs/${C.runId}/approvals/${C.approvalId}/decision`, { decision: "APPROVE" }));
});

// ---------------------------------------------------------------- report
if (browser) await browser.close().catch(() => {});
const verdicts = [...steps.values()]; const core = verdicts.filter((s) => /^\d+$/.test(s.n)); const reg = verdicts.filter((s) => !/^\d+$/.test(s.n)); const passed = core.filter((s) => s.verdict === "PASS").length; const regPassed = reg.filter((s) => s.verdict === "PASS").length;
mkdirSync(OUT, { recursive: true });
const failedChecks = rows.filter((r) => r.result === "FAIL");
writeFileSync(`${OUT}/golden-company-flow.json`, JSON.stringify({ api: API, finalSha: FINAL_SHA, tag, company: C.slug, passedSteps: passed, totalSteps: 27, regressionPassed: regPassed, regressionTotal: reg.length, steps: verdicts.map(({ n, title, verdict, checks, failed, error }) => ({ n, title, verdict, checks, failed, error })), rows, pageErrors: pageErrors.slice(0, 20) }, null, 1));
const esc = (v) => String(v ?? "").replace(/[\t\r\n]+/g, " ");
writeFileSync(`${OUT}/golden-company-flow.tsv`, ["step\tcheck\tresult\tinfo", ...rows.map((r) => [r.step, r.check, r.result, r.info].map(esc).join("\t"))].join("\n") + "\n");
console.log(`\nfailed checks: ${failedChecks.length}`); for (const f of failedChecks.slice(0, 40)) console.log(`  ${f.step} ${f.check} | ${f.info.slice(0, 120)}`);
console.log(`\nREGRESSION (approval across a restart, isolation): ${regPassed}/${reg.length} PASS`);
console.log(`GOLDEN_COMPANY_FLOW: ${passed}/27 PASS`);
process.exit(passed === 27 && regPassed === reg.length ? 0 : 1);
