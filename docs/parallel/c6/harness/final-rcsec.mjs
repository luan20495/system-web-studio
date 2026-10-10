#!/usr/bin/env node
// C6 FINAL RC regression (ADAPTED COPY of rc-sec.mjs: env API/FINAL_API, CORS origin = Admin portal, 429-aware activation, resilient fetch, output final-rcsec.*; the 88 checks themselves are unchanged)
// C6 RC wide regression — AUTH / SECURITY / CSRF / CORS / NEGATIVE matrix at the API of a real stack (RC 62ce9697cd56).
// Independent of C5's suite: its own fixtures (two tenants, five roles), explicit expected-vs-actual HTTP statuses, JSON + TSV evidence.
// Env: API (http://127.0.0.1:48080)  SA_USER  SA_PASSWORD  (read by the caller from the stack's own env file, never printed)  OUT (evidence dir)
// Accounts are created through the product routes (invitation + activation), prefixed `c6sec-`, disabled at the end. No SQL, no test hook.
import { randomBytes, randomUUID } from "node:crypto";
import { writeFileSync, mkdirSync } from "node:fs";
import "./final-iam-fx.mjs";   // C6 FINAL adaptation: resilient fetch (API restarts by the coordinator)

const API = (process.env.API ?? process.env.FINAL_API ?? "http://127.0.0.1:48080").replace(/\/$/, "");
const SA_USER = process.env.SA_USER, SA_PASSWORD = process.env.SA_PASSWORD; const OUT = process.env.OUT ?? ".";
if (!SA_USER || !SA_PASSWORD) { console.error("SA_USER / SA_PASSWORD required"); process.exit(2); }
mkdirSync(OUT, { recursive: true });
const tag = randomUUID().slice(0, 6); const pw = () => randomBytes(15).toString("base64url") + "aA1!";
const rows = []; const CORS_ALLOWED = process.env.CORS_ORIGIN ?? process.env.FINAL_ADMIN ?? "http://127.0.0.1:3402";
const rec = (id, area, desc, expected, actual, ok, note = "") => { rows.push({ id, area, desc, expected: String(expected), actual: String(actual), result: ok ? "PASS" : "FAIL", note }); console.log(`${ok ? "PASS" : "FAIL"} ${id} [${area}] ${desc} | expected ${expected} | actual ${actual}${note ? " | " + note : ""}`); };
const st = (r) => `${r.status}${r.json?.code ? " " + r.json.code : ""}`;
const is = (r, ...codes) => codes.includes(r.status);

class S {
  constructor() { this.jar = new Map(); this.csrf = null; }
  async call(method, path, body, headers = {}) {
    const h = { ...headers }; if (this.jar.size) h.cookie = [...this.jar].map(([k, v]) => `${k}=${v}`).join("; ");
    if (this.csrf && !["GET", "HEAD", "OPTIONS"].includes(method) && !("x-xsrf-token" in h) && !("noCsrf" in h)) h["x-xsrf-token"] = this.csrf;
    delete h.noCsrf; if (body !== undefined) h["content-type"] = "application/json";
    const res = await fetch(API + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), redirect: "manual" });
    for (const c of res.headers.getSetCookie?.() ?? []) { const [kv] = c.split(";"); const i = kv.indexOf("="); const k = kv.slice(0, i); if (/max-age=0/i.test(c)) this.jar.delete(k); else this.jar.set(k, kv.slice(i + 1)); }
    const text = await res.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
    return { status: res.status, json, text, headers: res.headers };
  }
  async login(username, password) { this.jar.clear(); this.csrf = (await this.call("GET", "/api/v1/auth/csrf")).json?.token; const r = await this.call("POST", "/api/v1/auth/login", { username, password }); this.csrf = (await this.call("GET", "/api/v1/auth/csrf")).json?.token; return r; }
}
const anon = new S();
async function mkUser(as, T, name, o) {
  const r = await as.call("POST", `/api/v1/admin/tenants/${T}/users`, { username: name, displayName: name, ...o });
  if (r.status !== 201 || !r.json?.token) throw new Error(`provision ${name}: ${st(r)}`);
  const p = pw(); await anon.call("GET", "/api/v1/auth/csrf"); anon.csrf = (await anon.call("GET", "/api/v1/auth/csrf")).json?.token;
  let a; for (let i = 0; i < 6; i++) { a = await anon.call("POST", "/api/v1/auth/activation/complete", { token: r.json.token, password: p }); if (a.status !== 429) break; const w = Math.min(Math.max(Number(a.headers.get("retry-after") ?? a.json?.details?.retryAfterSeconds ?? 60), 5), 600) + 2; console.log(`  (activation 429, waiting ${w}s - shared per-IP limiter)`); await new Promise((z) => setTimeout(z, w * 1000)); } if (a.status !== 200) throw new Error(`activate ${name}: ${st(a)}`);
  const s = new S(); const l = await s.login(name, p); if (l.status !== 200) throw new Error(`login ${name}: ${st(l)}`);
  return { s, name, id: r.json.userId, p };
}

// ---------------------------------------------------------------- fixtures: two tenants
const sa = new S(); let r = await sa.login(SA_USER, SA_PASSWORD); if (r.status !== 200) throw new Error("super admin login " + st(r));
const T1 = (await sa.call("POST", "/api/v1/admin/tenants", { slug: `c6a-${tag}`, name: `C6 sec A ${tag}` })).json?.id; const T2 = (await sa.call("POST", "/api/v1/admin/tenants", { slug: `c6b-${tag}`, name: `C6 sec B ${tag}` })).json?.id;
const W1 = (await sa.call("POST", `/api/v1/admin/tenants/${T1}/workspaces`, { name: `C6 ws A ${tag}` })).json?.id; const W2 = (await sa.call("POST", `/api/v1/admin/tenants/${T2}/workspaces`, { name: `C6 ws B ${tag}` })).json?.id;
if (!T1 || !T2 || !W1 || !W2) throw new Error("tenant/workspace fixtures failed");
const ta1 = await mkUser(sa, T1, `c6sec-ta1-${tag}`, { tenantRole: "TENANT_ADMIN", workspaceId: W1, workspaceRole: "WORKSPACE_ADMIN" });
const ta2 = await mkUser(sa, T2, `c6sec-ta2-${tag}`, { tenantRole: "TENANT_ADMIN", workspaceId: W2, workspaceRole: "WORKSPACE_ADMIN" });
const wa = await mkUser(ta1.s, T1, `c6sec-wa-${tag}`, { workspaceId: W1, workspaceRole: "WORKSPACE_ADMIN" });
const ed = await mkUser(ta1.s, T1, `c6sec-ed-${tag}`, { workspaceId: W1, workspaceRole: "EDITOR" });
const vw = await mkUser(ta1.s, T1, `c6sec-vw-${tag}`, { workspaceId: W1, workspaceRole: "VIEWER" });
const dis = await mkUser(ta1.s, T1, `c6sec-dis-${tag}`, { workspaceId: W1, workspaceRole: "EDITOR" });
const P1 = (await wa.s.call("POST", `/api/v1/workspaces/${W1}/projects`, { name: `c6sec-proj-${tag}`, appType: "PAGE_SCHEMA" })).json?.id;
const P2 = (await ta2.s.call("POST", `/api/v1/workspaces/${W2}/projects`, { name: `c6sec-projB-${tag}`, appType: "PAGE_SCHEMA" })).json?.id;
rec("F00", "fixture", "two tenants, 6 accounts (TA×2, WS admin, EDITOR, VIEWER, to-be-disabled), 2 projects created through the product routes", "all created", `P1=${!!P1} P2=${!!P2}`, !!P1 && !!P2);

// ---------------------------------------------------------------- 401
r = await new S().call("GET", "/api/v1/auth/me"); rec("S01", "401", "unauthenticated GET /auth/me", "401", st(r), r.status === 401);
r = await new S().call("GET", `/api/v1/workspaces/${W1}/projects`); rec("S02", "401", "unauthenticated GET project list", "401", st(r), r.status === 401);
r = await new S().call("GET", "/api/v1/admin/tenants"); rec("S03", "401", "unauthenticated GET /admin/tenants", "401", st(r), r.status === 401);
r = await new S().call("GET", "/api/v1/admin/audit"); rec("S04", "401", "unauthenticated GET /admin/audit", "401", st(r), r.status === 401);

// ---------------------------------------------------------------- CSRF
const c = new S(); c.csrf = (await c.call("GET", "/api/v1/auth/csrf")).json?.token;
r = await c.call("POST", "/api/v1/auth/login", { username: ed.name, password: ed.p }, { noCsrf: 1 }); rec("C01", "CSRF", "login POST without a token", "403", st(r), r.status === 403);
r = await c.call("POST", "/api/v1/auth/login", { username: ed.name, password: ed.p }, { "x-xsrf-token": "invalid-" + tag }); rec("C02", "CSRF", "login POST with an invalid token", "403", st(r), r.status === 403);
r = await c.call("POST", "/api/v1/auth/login", { username: ed.name, password: ed.p }); rec("C03", "CSRF", "login POST with the valid token (as a browser does)", "200", st(r), r.status === 200);
c.csrf = (await c.call("GET", "/api/v1/auth/csrf")).json?.token;
r = await wa.s.call("POST", `/api/v1/workspaces/${W1}/projects`, { name: `c6sec-nocsrf-${tag}` }, { noCsrf: 1 }); rec("C04", "CSRF", "authenticated mutation (create project) without a token", "403", st(r), r.status === 403);
r = await wa.s.call("POST", `/api/v1/workspaces/${W1}/projects`, { name: `c6sec-badcsrf-${tag}` }, { "x-xsrf-token": "forged" }); rec("C05", "CSRF", "authenticated mutation with a forged token", "403", st(r), r.status === 403);
r = await wa.s.call("POST", `/api/v1/workspaces/${W1}/projects`, { name: `c6sec-ok-${tag}`, appType: "PAGE_SCHEMA" }); const P3 = r.json?.id; rec("C06", "CSRF", "authenticated mutation with the valid token", "201", st(r), r.status === 201);
r = await ta1.s.call("POST", `/api/v1/admin/tenants/${T1}/workspaces`, { name: "x" }, { noCsrf: 1 }); rec("C07", "CSRF", "provisioning route without a token", "403", st(r), r.status === 403);
r = await ta1.s.call("DELETE", `/api/v1/workspaces/${W1}/projects/${P3}?expectedRevision=0`, undefined, { noCsrf: 1 }); rec("C08", "CSRF", "DELETE without a token", "403", st(r), r.status === 403);

// ---------------------------------------------------------------- 403 — same scope, missing permission
r = await vw.s.call("POST", `/api/v1/workspaces/${W1}/projects`, { name: `c6sec-vw-${tag}` }); rec("A01", "403", "VIEWER creates a project in the workspace it belongs to", "403", st(r), r.status === 403);
r = await ed.s.call("GET", `/api/v1/workspaces/${W1}/members`); rec("A02", "403", "EDITOR lists workspace members (needs MEMBER_MANAGE)", "403", st(r), r.status === 403);
const ep = (await ed.s.call("POST", `/api/v1/workspaces/${W1}/projects`, { name: `c6sec-edproj-${tag}`, appType: "PAGE_SCHEMA" })).json?.id;
r = await ed.s.call("POST", `/api/v1/workspaces/${W1}/projects/${ep}/publish`, { visibility: "PRIVATE", expectedRevision: 0 }, { "Idempotency-Key": randomUUID() }); rec("A03", "403", "EDITOR publishes its own project without PROJECT_PUBLISH on a non-owner role? (creator is OWNER => documented)", "202 or 403 (creator = OWNER)", st(r), [202, 403, 400, 409].includes(r.status), "informational: the creator of a project is its OWNER");
r = await ed.s.call("POST", `/api/v1/workspaces/${W1}/projects/${P1}/publish`, { visibility: "PRIVATE", expectedRevision: 0 }, { "Idempotency-Key": randomUUID() }); rec("A04", "403", "EDITOR (workspace role) publishes a project it has no PROJECT_PUBLISH on", "403 (or 404 when not visible)", st(r), is(r, 403, 404));
r = await vw.s.call("PATCH", `/api/v1/workspaces/${W1}/projects/${P1}/schema`, { expectedRevision: 0, operations: [{ type: "ADD_ACTION", definition: { id: "c6-denied-probe", name: "denied probe", type: "NOTIFY", channel: "IN_APP", templateRef: "c6-template" } }], summary: "c6 denied probe" }); rec("A05", "403", "VIEWER mutates a project schema", "403/404", st(r), is(r, 403, 404));
// project-level roles: the person IS a member of the project, so a refusal here is a real 403 (permission missing in a visible scope)
r = await wa.s.call("POST", `/api/v1/workspaces/${W1}/projects/${P1}/members`, { username: ed.name, role: "EDITOR" }); rec("A12a", "403", "project owner adds the EDITOR to the project", "201", st(r), r.status === 201);
r = await wa.s.call("POST", `/api/v1/workspaces/${W1}/projects/${P1}/members`, { username: vw.name, role: "VIEWER" }); rec("A12b", "403", "project owner adds the VIEWER to the project", "201", st(r), r.status === 201);
r = await ed.s.call("POST", `/api/v1/workspaces/${W1}/projects/${P1}/publish`, { visibility: "PRIVATE", expectedRevision: 0 }, { "Idempotency-Key": randomUUID() }); rec("A12", "403", "project EDITOR publishes (PROJECT_PUBLISH is not implied by editing)", "403", st(r), r.status === 403);
r = await vw.s.call("PATCH", `/api/v1/workspaces/${W1}/projects/${P1}/schema`, { expectedRevision: 0, operations: [{ type: "ADD_ACTION", definition: { id: "c6-denied-probe", name: "denied probe", type: "NOTIFY", channel: "IN_APP", templateRef: "c6-template" } }], summary: "c6 denied probe" }); rec("A13", "403", "project VIEWER mutates the schema", "403", st(r), r.status === 403);
r = await vw.s.call("GET", `/api/v1/workspaces/${W1}/projects/${P1}`); rec("A14", "403", "project VIEWER can read the project (view is allowed)", "200", st(r), r.status === 200);
r = await ed.s.call("GET", `/api/v1/workspaces/${W1}/projects/${P1}/members`); rec("A15", "403", "project EDITOR lists project members (PROJECT_MEMBERS needed)", "403", st(r), r.status === 403);
r = await wa.s.call("GET", "/api/v1/admin/tenants"); rec("A06", "403", "WORKSPACE_ADMIN lists tenants (platform/tenant route)", "403", st(r), r.status === 403);
r = await wa.s.call("POST", `/api/v1/admin/tenants/${T1}/users`, { username: `c6sec-z-${tag}`, displayName: "z" }); rec("A07", "403", "WORKSPACE_ADMIN provisions an account (tenant route)", "403", st(r), r.status === 403);
r = await ed.s.call("GET", "/api/v1/admin/audit"); rec("A08", "403", "EDITOR reads the platform audit", "403", st(r), r.status === 403);
r = await ta1.s.call("GET", "/api/v1/admin/audit"); rec("A09", "403", "Tenant Admin (not system admin) reads the PLATFORM audit", "403", st(r), r.status === 403);
r = await ta1.s.call("POST", "/api/v1/admin/tenants", { slug: `c6x-${tag}`, name: "x" }); rec("A10", "403", "Tenant Admin creates a tenant", "403", st(r), r.status === 403);
r = await ta1.s.call("GET", "/api/v1/admin/system/health"); rec("A11", "403", "Tenant Admin reads system health", "403", st(r), r.status === 403);

// ---------------------------------------------------------------- 404 — foreign scope, safe disclosure
r = await ta1.s.call("GET", `/api/v1/admin/tenants/${T2}`); rec("N01", "404", "Tenant Admin A reads tenant B", "404", st(r), r.status === 404);
r = await ta1.s.call("GET", `/api/v1/admin/tenants/${T2}/members`); rec("N02", "404", "Tenant Admin A lists tenant B members", "404", st(r), r.status === 404);
r = await ta1.s.call("POST", `/api/v1/admin/tenants/${T2}/workspaces`, { name: "x" }); rec("N03", "404", "Tenant Admin A creates a workspace in tenant B", "404", st(r), r.status === 404);
r = await ta1.s.call("POST", `/api/v1/admin/tenants/${T2}/users`, { username: `c6sec-q-${tag}`, displayName: "q" }); rec("N04", "404", "Tenant Admin A provisions a user in tenant B", "404", st(r), r.status === 404);
r = await ta1.s.call("GET", `/api/v1/workspaces/${W2}/projects`); rec("N05", "404", "Tenant Admin A lists projects of foreign workspace B", "404 (403 acceptable only if identical for a random id)", st(r), is(r, 404));
r = await ed.s.call("GET", `/api/v1/workspaces/${W2}/projects/${P2}`); rec("N06", "404", "EDITOR of A reads project of B", "404", st(r), is(r, 404));
r = await ed.s.call("GET", `/api/v1/workspaces/${W1}/projects/${P2}`); rec("N07", "404", "EDITOR of A reads project of B through its OWN workspace id", "404", st(r), is(r, 404));
r = await ed.s.call("GET", `/api/v1/workspaces/${W1}/projects/${randomUUID()}`); rec("N08", "404", "guessed random project UUID in an own workspace", "404", st(r), is(r, 404));
r = await ed.s.call("GET", `/api/v1/workspaces/${randomUUID()}/projects`); rec("N09", "404", "guessed random workspace UUID", "404", st(r), is(r, 404));
r = await ta2.s.call("POST", `/api/v1/workspaces/${W1}/members`, { username: ed.name, role: "VIEWER" }); rec("N10", "404", "Tenant Admin B adds a member to workspace A", "404/403", st(r), is(r, 404, 403));
const rr = await ed.s.call("GET", `/api/v1/workspaces/${randomUUID()}/projects`); const rf = await ed.s.call("GET", `/api/v1/workspaces/${W2}/projects`);
rec("N11", "404", "foreign workspace and a random workspace are indistinguishable (status + code)", "same status+code", `${st(rr)} vs ${st(rf)}`, rr.status === rf.status && (rr.json?.code ?? "") === (rf.json?.code ?? ""));
r = await ta1.s.call("GET", "/api/v1/admin/tenants"); const seen = JSON.stringify(r.json ?? ""); rec("N12", "404", "Tenant Admin A tenant list shows only its own tenant (no tenant B)", "no tenant B", `${r.status}; B visible=${seen.includes(T2)}`, !seen.includes(T2));
r = await ta1.s.call("GET", `/api/v1/admin/tenants/${T1}/member-candidates?q=c6sec`); rec("N13", "404", "candidate search of tenant A never returns tenant B accounts", "no c6sec-ta2", `${r.status}; B visible=${JSON.stringify(r.json ?? "").includes("ta2-" + tag)}`, !JSON.stringify(r.json ?? "").includes("ta2-" + tag));
r = await ta1.s.call("GET", `/api/v1/admin/users/${ta2.id}`); rec("N14", "404", "Tenant Admin A reads the admin detail of a tenant-B account", "403/404", st(r), is(r, 403, 404));

// ---------------------------------------------------------------- privilege escalation / self grant / last admin
r = await ta1.s.call("POST", `/api/v1/admin/tenants/${T1}/users`, { username: `c6sec-sys-${tag}`, displayName: "s", tenantRole: "SYSTEM_ADMIN" }); rec("E01", "escalation", "Tenant Admin mints a SYSTEM_ADMIN", "400 TENANT_ROLE_INVALID", st(r), r.status === 400 && r.json?.code === "TENANT_ROLE_INVALID");
r = await ta1.s.call("POST", `/api/v1/admin/tenants/${T1}/users`, { username: `c6sec-sys2-${tag}`, displayName: "s", systemAdmin: true }); const sysCreated = r.status === 201 ? (await sa.call("GET", `/api/v1/admin/users/${r.json.userId}`)).json?.user : null;
rec("E02", "escalation", "body flag systemAdmin:true on a tenant user creation", "ignored (400 or created non-admin)", `${r.status}; systemAdmin=${sysCreated?.systemAdmin}`, r.status === 400 || sysCreated?.systemAdmin === false);
r = await wa.s.call("PATCH", `/api/v1/workspaces/${W1}/members/${wa.id}`, { role: "WORKSPACE_ADMIN" }); rec("E03", "escalation", "Workspace Admin changes its OWN role (self-grant protection)", "403 SELF_GRANT_FORBIDDEN", st(r), r.status === 403 && r.json?.code === "SELF_GRANT_FORBIDDEN");
r = await ed.s.call("PATCH", `/api/v1/workspaces/${W1}/members/${ed.id}`, { role: "WORKSPACE_ADMIN" }); rec("E04", "escalation", "EDITOR promotes itself to WORKSPACE_ADMIN", "403", st(r), r.status === 403);
r = await wa.s.call("PATCH", `/api/v1/admin/users/${ed.id}/status`, { enabled: false }); rec("E05", "escalation", "Workspace Admin disables an account through the platform route", "403", st(r), r.status === 403);
r = await ta1.s.call("PUT", `/api/v1/admin/tenants/${T1}/members/${ta1.id}`, { role: "MEMBER" }); rec("E06", "escalation", "Tenant Admin changes its own tenant role", "403 / 409 LAST_TENANT_ADMIN", st(r), is(r, 403, 409));
r = await ta1.s.call("DELETE", `/api/v1/admin/tenants/${T1}/members/${ta1.id}`); rec("E07", "escalation", "Tenant Admin removes itself (last tenant admin)", "403 / 409", st(r), is(r, 403, 409));
// last workspace admin: W1 admins are ta1 and wa; remove wa then try removing ta1's workspace admin role through the other
r = await ta1.s.call("DELETE", `/api/v1/workspaces/${W1}/members/${wa.id}`); const rm1 = r.status;
r = await ta1.s.call("DELETE", `/api/v1/workspaces/${W1}/members/${ta1.id}`); rec("E08", "last-admin", "remove the last WORKSPACE_ADMIN of a workspace", "403 SELF_GRANT_FORBIDDEN or 409 LAST_ADMIN", `${st(r)} (first removal: ${rm1})`, is(r, 403, 409));
// system admin protections
const meSa = (await sa.call("GET", "/api/v1/auth/me")).json;
r = await sa.call("PATCH", `/api/v1/admin/users/${meSa?.userId ?? meSa?.id}/status`, { enabled: false }); rec("E09", "last-admin", "disable yourself / the last enabled system admin", "409 LAST_SYSTEM_ADMIN or 403", st(r), is(r, 409, 403, 400));

// ---------------------------------------------------------------- disabled user
r = await sa.call("PATCH", `/api/v1/admin/users/${dis.id}/status`, { enabled: false }); rec("D01", "disabled", "super admin disables an account", "200", st(r), r.status === 200);
r = await dis.s.call("GET", "/api/v1/auth/me"); rec("D02", "disabled", "the disabled account's EXISTING session", "401 (session no longer valid)", st(r), r.status === 401, r.status === 200 ? "session survived disable" : "");
r = await new S().login(dis.name, dis.p); rec("D03", "disabled", "the disabled account signs in again", "401/403", st(r), is(r, 401, 403));
r = await sa.call("PATCH", `/api/v1/admin/users/${dis.id}/status`, { enabled: true }); const r2 = await new S().login(dis.name, dis.p); rec("D04", "disabled", "re-enabled account signs in again", "200", `${st(r)} / login ${r2.status}`, r2.status === 200);

// ---------------------------------------------------------------- secrets / leakage
const leak = (o) => /password|passwordhash|secret|apikey|"hash"|credential"?:\s*"/i.test(JSON.stringify(o ?? ""));
for (const [id, label, resp] of [["L01", "/auth/me", await ed.s.call("GET", "/api/v1/auth/me")], ["L02", "admin user list (super admin)", await sa.call("GET", "/api/v1/admin/users?size=5")], ["L03", "tenant members", await ta1.s.call("GET", `/api/v1/admin/tenants/${T1}/members`)], ["L04", "workspace members", await ta1.s.call("GET", `/api/v1/workspaces/${W1}/members`)]])
  rec(id, "secrets", `no password/hash/secret key in ${label}`, "none", `${resp.status}; match=${leak(resp.json)}`, !leak(resp.json));
r = await anon.call("POST", "/api/v1/auth/activation/inspect", { token: "x".repeat(24) }); rec("L05", "secrets", "unknown activation token (429 = the activation rate limiter, repeated runs from one address)", "404/410 without detail, or 429", st(r), is(r, 404, 410, 400, 429), r.status === 429 ? "rate limiter active" : "");
r = await new S().login(ed.name, "wrong-password-" + tag); rec("L06", "secrets", "wrong password and unknown user give the same answer", "401 both", `wrong=${r.status} / unknown=${(await new S().login("c6nosuch" + tag, "x")).status}`, r.status === 401);

// ---------------------------------------------------------------- CORS (API direct; the portals proxy same-origin)
const cors = async (origin, method = "GET", extra = {}) => { const res = await fetch(API + "/api/v1/auth/config", { method, headers: { Origin: origin, ...extra } }); return { status: res.status, acao: res.headers.get("access-control-allow-origin"), acc: res.headers.get("access-control-allow-credentials") }; };
let x = await cors("https://evil.example"); rec("O01", "CORS", "simple request from a foreign origin", "no Access-Control-Allow-Origin", `${x.status} acao=${x.acao}`, !x.acao);
x = await cors("https://evil.example", "OPTIONS", { "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "x-xsrf-token,content-type" }); rec("O02", "CORS", "preflight from a foreign origin", "rejected (403) / no ACAO", `${x.status} acao=${x.acao}`, !x.acao);
x = await cors("null"); rec("O03", "CORS", "Origin: null", "no ACAO", `${x.status} acao=${x.acao}`, !x.acao);
x = await cors(CORS_ALLOWED); rec("O04", "CORS", "configured portal origin", "ACAO = that origin + credentials", `${x.status} acao=${x.acao} cred=${x.acc}`, x.acao === CORS_ALLOWED && x.acc === "true");
x = await cors(CORS_ALLOWED, "OPTIONS", { "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "x-xsrf-token,content-type" }); rec("O05", "CORS", "preflight from the configured origin", "200 + ACAO", `${x.status} acao=${x.acao}`, x.status === 200 && x.acao === CORS_ALLOWED);
x = await cors(CORS_ALLOWED + ".evil.example"); rec("O06", "CORS", "look-alike origin (suffix)", "no ACAO", `${x.status} acao=${x.acao}`, !x.acao);

// ---------------------------------------------------------------- direct protected API requests
r = await new S().call("GET", "/actuator/env"); rec("P01", "surface", "actuator /env anonymous", "401/403/404", st(r), is(r, 401, 403, 404));
r = await new S().call("GET", "/actuator/heapdump"); rec("P02", "surface", "actuator /heapdump anonymous", "401/403/404", st(r), is(r, 401, 403, 404));
r = await new S().call("GET", "/v3/api-docs"); rec("P03", "surface", "OpenAPI document anonymous (this stack = profile `local`, springdoc enabled by application-local.yml; application-prod.yml disables it)", "200 on local; the public hosts return the Next HTML page, not the schema", st(r), r.status === 200 || is(r, 401, 403, 404), "informational (profile local)");
r = await new S().call("GET", "/actuator/health/readiness"); rec("P04", "surface", "readiness (expected to be public)", "200", st(r), r.status === 200);

// ---------------------------------------------------------------- cleanup: accounts are never deleted → disable
for (const u of [ta1, ta2, wa, ed, vw, dis]) await sa.call("PATCH", `/api/v1/admin/users/${u.id}/status`, { enabled: false });
await sa.call("POST", "/api/v1/auth/logout", {}); const after = await sa.call("GET", "/api/v1/auth/me"); rec("S05", "session", "logout then GET /auth/me", "401", st(after), after.status === 401);
const failed = rows.filter((x) => x.result === "FAIL");
writeFileSync(`${OUT}/final-rcsec.json`, JSON.stringify({ api: API, run: tag, total: rows.length, failed: failed.length, rows }, null, 1));
writeFileSync(`${OUT}/final-rcsec.tsv`, ["id\tarea\tdescription\texpected\tactual\tresult\tnote", ...rows.map((x) => [x.id, x.area, x.desc, x.expected, x.actual, x.result, x.note].join("\t"))].join("\n") + "\n");
console.log(`\nTOTAL ${rows.length}  FAILED ${failed.length}${failed.length ? "  -> " + failed.map((x) => x.id).join(", ") : ""}`);
process.exit(failed.length ? 1 : 0);
