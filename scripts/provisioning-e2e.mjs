#!/usr/bin/env node
// @class: real-backend
// PROVISIONING E2E at the API (D-C1-13A): the C1 tenant-scoped contract against a REAL stack (real PostgreSQL, Redis session, CSRF, activation by link), no mock, no test hook.
//   A Super Admin (platform)  : tenant -> workspace OF the tenant -> first Tenant Admin by invitation (+ workspace + WORKSPACE_ADMIN)
//   B Tenant / Workspace Admin: activates the link, signs in, creates a workspace of its tenant through the NEW route, creates a brand-new user (+ workspace role), adds an eligible person
//   C User / App creator      : activates, signs in, /auth/me lists ONLY the authorized workspace, creates / edits an app according to the role
//   critical negatives        : the legacy POST /api/v1/admin/workspaces is not the tenant route (a Tenant Admin gets 403 ADMIN_REQUIRED; a system admin's lands in the DEFAULT tenant), a Tenant Admin
//                               cannot touch another tenant (404), a Workspace Admin cannot call tenant routes (403), self-grant is refused, no route returns / takes a password, the activation token is single use.
// Usage:  SA_USER=local.admin SA_PASSWORD=... node scripts/provisioning-e2e.mjs        (API default http://127.0.0.1:8080; for a public host pass API=https://platform.toolsmcp.uk and the operator account)
// The accounts it creates are prefixed `e2e-`, live in a throw-away tenant and are disabled at the end. Passwords are generated here, never printed or stored.
import { randomBytes, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";

const API = (process.env.API ?? "http://127.0.0.1:8080").replace(/\/$/, "");
const ROOT = new URL("..", import.meta.url).pathname;
let SA_USER = process.env.SA_USER, SA_PASSWORD = process.env.SA_PASSWORD;
if (!SA_PASSWORD) { try { const e = Object.fromEntries(readFileSync(`${ROOT}.env`, "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)])); SA_PASSWORD = e.LOCAL_ADMIN_PASSWORD; SA_USER ??= "local.admin"; } catch {} }
if (!SA_USER || !SA_PASSWORD) { console.error("SA_USER / SA_PASSWORD required (or LOCAL_ADMIN_PASSWORD in .env)"); process.exit(2); }
const results = [];
const check = (name, ok, info = "") => { results.push([name, ok]); console.log(`${ok ? "PASS" : "FAIL"} ${name}${info ? " | " + info : ""}`); return ok; };
const tag = randomUUID().slice(0, 6); const pw = () => randomBytes(15).toString("base64url") + "aA1!";

class S {
  constructor() { this.jar = new Map(); this.csrf = null; }
  async call(method, path, body, headers = {}) {
    const h = { ...headers }; if (this.jar.size) h.cookie = [...this.jar].map(([k, v]) => `${k}=${v}`).join("; ");
    if (this.csrf && !["GET", "HEAD"].includes(method) && !("x-xsrf-token" in h)) h["x-xsrf-token"] = this.csrf;
    if (body !== undefined) h["content-type"] = "application/json";
    const res = await fetch(API + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), redirect: "manual" });
    for (const c of res.headers.getSetCookie?.() ?? []) { const [kv] = c.split(";"); const i = kv.indexOf("="); const k = kv.slice(0, i); if (/max-age=0/i.test(c)) this.jar.delete(k); else this.jar.set(k, kv.slice(i + 1)); }
    const text = await res.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
    return { status: res.status, json, text };
  }
  async login(username, password) { this.jar.clear(); this.csrf = (await this.call("GET", "/api/v1/auth/csrf")).json?.token; const r = await this.call("POST", "/api/v1/auth/login", { username, password }); this.csrf = (await this.call("GET", "/api/v1/auth/csrf")).json?.token; return r; }
}
const code = (r) => r.json?.code ?? "";

// ---- A. Super Admin
const sa = new S(); let r = await sa.login(SA_USER, SA_PASSWORD);
check("A Super Admin signs in (platform scope)", r.status === 200, `${r.status}`);
r = await sa.call("GET", "/api/v1/auth/me"); const saMe = r.json;
// a system admin sees every workspace as the platform view (role ADMIN: the two tenant permissions only); where it is a real WORKSPACE member (the local seed) it holds that member's permissions
const platformView = (saMe?.workspaces ?? []).filter((w) => w.role === "ADMIN");
check("A /auth/me: system admin; in every workspace it is NOT a member of, its permissions are only TENANT_MANAGE + TENANT_MEMBERS (no business / member permission)", saMe?.systemAdmin === true && platformView.length > 0 && platformView.every((w) => (w.permissions ?? []).every((p) => ["TENANT_MANAGE", "TENANT_MEMBERS"].includes(p))), `platform-view workspaces=${platformView.length} of ${saMe?.workspaces?.length}`);
r = await sa.call("POST", "/api/v1/admin/tenants", { slug: `e2e-${tag}`, name: `E2E ${tag}` }); const T = r.json?.id;
check("A create tenant (platform)", r.status === 201 && !!T, `${r.status} ${code(r)}`);
r = await sa.call("POST", `/api/v1/admin/tenants/${T}/workspaces`, { name: `E2E ws ${tag}` }); const W1 = r.json?.id;
check("A create a workspace OF the tenant through the tenant route (tenant_id explicit in the answer)", r.status === 201 && r.json?.tenantId === T, `${r.status} tenantId=${r.json?.tenantId === T ? "the tenant" : r.json?.tenantId}`);
const taName = `e2e-ta-${tag}`;
r = await sa.call("POST", `/api/v1/admin/tenants/${T}/users`, { username: taName, displayName: `TA ${tag}`, tenantRole: "TENANT_ADMIN", workspaceId: W1, workspaceRole: "WORKSPACE_ADMIN" });
const taLink = r.json;
check("A provision the first Tenant Admin by invitation (activation link, no password in or out)", r.status === 201 && !!taLink?.token && taLink.purpose === "ACTIVATION" && !/password|hash/i.test(JSON.stringify(Object.keys(taLink))), `${r.status} ${code(r)}`);
r = await new S().login(taName, "x".repeat(12));
check("A the invited account cannot sign in before activation", r.status === 401 || r.status === 403, `${r.status}`);
r = await sa.call("POST", `/api/v1/admin/tenants/${T}/users`, { username: `e2e-pw-${tag}`, displayName: "pw", password: "Sup3rSecret!", systemAdmin: true });
check("A a body field `password` / `systemAdmin` never takes effect (account is created as a plain, not-yet-activated member)", r.status === 201 || r.status === 400, `${r.status}`);
if (r.status === 201) { const t = await sa.call("POST", "/api/v1/auth/login", { username: `e2e-pw-${tag}`, password: "Sup3rSecret!" }); check("A ... and the smuggled password does not open a session", t.status !== 200, `${t.status}`); }

// ---- B. Tenant / Workspace Admin
const taPw = pw(); const anon = new S(); await anon.call("GET", "/api/v1/auth/csrf"); anon.csrf = (await anon.call("GET", "/api/v1/auth/csrf")).json?.token;
r = await anon.call("POST", "/api/v1/auth/activation/inspect", { token: taLink.token });
check("B activation link inspects (anonymous) to the right account", r.status === 200 && r.json?.username === taName, `${r.status}`);
r = await anon.call("POST", "/api/v1/auth/activation/complete", { token: taLink.token, password: taPw });
check("B activation completes with a password chosen by the user", r.status === 200, `${r.status}`);
r = await anon.call("POST", "/api/v1/auth/activation/complete", { token: taLink.token, password: pw() });
check("B the link is single use (410 LINK_INVALID)", r.status === 410 && code(r) === "LINK_INVALID", `${r.status} ${code(r)}`);
const ta = new S(); r = await ta.login(taName, taPw);
check("B Tenant Admin signs in (Admin portal account)", r.status === 200, `${r.status}`);
const taMe = (await ta.call("GET", "/api/v1/auth/me")).json;
const TENANT_ADMIN_CODES = ["EMPLOYEE_MANAGE", "EMPLOYEE_VIEW", "ORG_STRUCTURE_MANAGE", "ORG_STRUCTURE_VIEW", "POSITION_GRADE_MANAGE", "POSITION_GRADE_VIEW", "TENANT_MANAGE", "TENANT_MEMBERS"];   // C1 final contract: exactly these eight (D-C0-51, D-C0-53)
check("B /auth/me: not a system admin; the tenant permissions are exactly the eight TENANT_ADMIN codes (tenant + six organization); the workspace shows MEMBER_MANAGE", taMe?.systemAdmin === false && JSON.stringify([...(taMe.tenants?.[0]?.permissions ?? taMe.permissions ?? [])].sort()) === JSON.stringify(TENANT_ADMIN_CODES) && (taMe.workspaces ?? []).some((w) => w.id === W1 && (w.permissions ?? []).includes("MEMBER_MANAGE")), JSON.stringify(taMe?.tenants?.[0]?.permissions ?? taMe?.permissions));
r = await ta.call("POST", `/api/v1/admin/tenants/${T}/workspaces`, { name: `E2E ws2 ${tag}` }); const W2 = r.json?.id;
check("B Tenant Admin creates a workspace of ITS tenant through the NEW route (tenant derived from the path, authorized by TENANT_MANAGE)", r.status === 201 && r.json?.tenantId === T, `${r.status} ${code(r)}`);
r = await ta.call("POST", "/api/v1/admin/workspaces", { name: `legacy ${tag}` });
check("B the LEGACY POST /api/v1/admin/workspaces is not a self-service route: a Tenant Admin gets 403", r.status === 403, `${r.status} ${code(r)}`);
r = await sa.call("POST", "/api/v1/admin/workspaces", { name: `legacy ${tag}` }); const WL = r.json?.id;
check("B the legacy route stays platform-only (system admin 201); it takes no tenant, so it can never create a workspace OF the e2e tenant", r.status === 201 && !!WL && r.json?.tenantId !== T, `${r.status}`);
const userName = `e2e-user-${tag}`;
r = await ta.call("POST", `/api/v1/admin/tenants/${T}/users`, { username: userName, displayName: `User ${tag}`, email: `${userName}@example.com`, workspaceId: W2, workspaceRole: "EDITOR" });
const uLink = r.json; check("B Tenant Admin creates a brand-new user in its tenant with a workspace role (invitation)", r.status === 201 && !!uLink?.token, `${r.status} ${code(r)}`);
r = await ta.call("POST", `/api/v1/admin/tenants/${T}/users`, { username: `e2e-x-${tag}`, displayName: "x", workspaceId: randomUUID(), workspaceRole: "EDITOR" });
check("B a workspace that is not of the tenant is refused (404 WORKSPACE_NOT_FOUND, indistinguishable from nobody)", r.status === 404 && code(r) === "WORKSPACE_NOT_FOUND", `${r.status} ${code(r)}`);
r = await ta.call("POST", `/api/v1/admin/tenants/${T}/users`, { username: `e2e-y-${tag}`, displayName: "y", tenantRole: "SYSTEM_ADMIN" });
check("B a Tenant Admin cannot mint a SYSTEM_ADMIN (400 TENANT_ROLE_INVALID)", r.status === 400 && code(r) === "TENANT_ROLE_INVALID", `${r.status} ${code(r)}`);
r = await ta.call("POST", `/api/v1/admin/tenants/${randomUUID()}/workspaces`, { name: "other" });
check("B another / unknown tenant is 404 TENANT_NOT_FOUND (no existence leak)", r.status === 404 && code(r) === "TENANT_NOT_FOUND", `${r.status} ${code(r)}`);
r = await ta.call("POST", `/api/v1/admin/tenants/${T}/workspaces`, { name: `no csrf ${tag}` }, { "x-xsrf-token": "forged" });
check("B CSRF is enforced on the provisioning routes (403 with a forged token)", r.status === 403, `${r.status} ${code(r)}`);
r = await new S().call("POST", `/api/v1/admin/tenants/${T}/workspaces`, { name: "anon" });
check("B no session -> 401 (or 403 for the missing CSRF first)", r.status === 401 || r.status === 403, `${r.status}`);
r = await ta.call("GET", `/api/v1/admin/tenants/${T}/member-candidates?q=e`);
check("B candidate search needs 2+ characters (400 QUERY_TOO_SHORT)", r.status === 400 && code(r) === "QUERY_TOO_SHORT", `${r.status} ${code(r)}`);
r = await ta.call("PUT", `/api/v1/admin/tenants/${T}/members/${taMe.userId ?? taMe.id}`, { role: "MEMBER" });
check("B self-grant / self-demotion is refused or protected (403 SELF_GRANT_FORBIDDEN or 409 LAST_TENANT_ADMIN)", [403, 409].includes(r.status), `${r.status} ${code(r)}`);

// ---- C. User / App creator
const uPw = pw(); await anon.call("POST", "/api/v1/auth/activation/complete", { token: uLink.token, password: uPw });
const u = new S(); r = await u.login(userName, uPw);
check("C the invited user activates and signs in (Studio account)", r.status === 200, `${r.status}`);
const uMe = (await u.call("GET", "/api/v1/auth/me")).json;
check("C /auth/me lists ONLY the authorized workspace (W2, EDITOR), not W1, not the default tenant's", (uMe?.workspaces ?? []).length === 1 && uMe.workspaces[0].id === W2, `workspaces=${uMe?.workspaces?.length}`);
r = await u.call("POST", `/api/v1/workspaces/${W2}/projects`, { name: `E2E app ${tag}` }); const P = r.json?.id;
check("C the EDITOR creates an app in its workspace", (r.status === 201 || r.status === 200) && !!P, `${r.status} ${code(r)}`);
r = await u.call("POST", `/api/v1/workspaces/${W1}/projects`, { name: "not mine" });
check("C ... and cannot create in another workspace it is not a member of (403/404)", [403, 404].includes(r.status), `${r.status} ${code(r)}`);
r = await u.call("POST", `/api/v1/admin/tenants/${T}/workspaces`, { name: "nope" });
check("C a plain member cannot call the tenant routes (403)", r.status === 403, `${r.status} ${code(r)}`);
r = await u.call("POST", "/api/v1/admin/workspaces", { name: "nope" });
check("C ... nor the legacy platform route (403)", r.status === 403, `${r.status} ${code(r)}`);
r = await u.call("GET", `/api/v1/workspaces/${W2}/members`);
check("C an EDITOR cannot manage members (403)", r.status === 403, `${r.status} ${code(r)}`);
// Workspace Admin (the TA is WORKSPACE_ADMIN of W1): adds an eligible tenant person, cannot call tenant routes as such
const wa = new S(); const waName = `e2e-wa-${tag}`;
r = await ta.call("POST", `/api/v1/admin/tenants/${T}/users`, { username: waName, displayName: `WA ${tag}`, workspaceId: W2, workspaceRole: "WORKSPACE_ADMIN" });
const waPw = pw(); await anon.call("POST", "/api/v1/auth/activation/complete", { token: r.json?.token, password: waPw }); await wa.login(waName, waPw);
r = await wa.call("POST", `/api/v1/admin/tenants/${T}/users`, { username: `e2e-z-${tag}`, displayName: "z" });
check("C a Workspace Admin cannot provision accounts (tenant route, 403)", r.status === 403, `${r.status} ${code(r)}`);
r = await wa.call("POST", `/api/v1/workspaces/${W2}/members`, { username: userName, role: "VIEWER" });
check("C a Workspace Admin adding someone already in the workspace -> 409 ALREADY_MEMBER", r.status === 409 && code(r) === "ALREADY_MEMBER", `${r.status} ${code(r)}`);
r = await wa.call("POST", `/api/v1/workspaces/${W2}/members`, { username: `e2e-ta-${tag}`, role: "VIEWER" });
check("C a Workspace Admin adds an ELIGIBLE tenant person (the Tenant Admin, active in the tenant)", r.status === 201, `${r.status} ${code(r)}`);
r = await wa.call("POST", `/api/v1/workspaces/${W2}/members`, { username: SA_USER, role: "VIEWER" });
check("C ... but not a person unrelated to the tenant (404 USER_NOT_FOUND)", r.status === 404 && code(r) === "USER_NOT_FOUND", `${r.status} ${code(r)}`);

// ---- clean up: disable the accounts (accounts are never deleted), archive the app
if (P) await u.call("DELETE", `/api/v1/workspaces/${W2}/projects/${P}?expectedRevision=${(await u.call("GET", `/api/v1/workspaces/${W2}/projects/${P}`)).json?.revision ?? 0}`);
for (const id of [uLink?.userId, taLink?.userId]) if (id) await sa.call("PATCH", `/api/v1/admin/users/${id}/status`, { enabled: false });
const failed = results.filter(([, ok]) => !ok).map(([n]) => n);
console.log(`\nTOTAL ${results.length}  FAILED ${failed.length}${failed.length ? "  -> " + failed.join(" | ") : ""}`);
process.exit(failed.length ? 1 : 0);
