// C6 FINAL RC — Business journey 02 (Dynamic Organization): shared fixtures + route catalogue for final-org.mjs and final-org-authz.mjs.
// Rate limits (coordinator note): activation/complete is limited to 30 per 600 s per IP on the shared c0rc API, so every fixture user that must LOG IN is activated ONCE
// (5 accounts: TA_A, TA_B, WS admin of A, plain MEMBER of A, "org-labelled" MEMBER of A). Their credentials are cached in a mode-600 file under the scratchpad (never in the repo,
// never printed) and the second script only re-logs in (successful logins do not count against the limit). A 429 on activation is waited out (Retry-After), never spoofed.
import { S, API, pw, sleep, st, tag, superAdmin } from "./final-lib.mjs";
import { existsSync, readFileSync, writeFileSync, chmodSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";

export const CACHE = process.env.ORG_FIXTURE_CACHE ?? "/private/tmp/claude-501/-Users-hoangluan-code-HBL/0a63d710-a6f1-4dad-8918-b63006286da6/scratchpad/final-org-fixture.json";
export const ZERO = "00000000-0000-0000-0000-000000000000";
export let activations = 0;

async function withRetry429(fn, label) {
  for (let i = 0; i < 25; i++) {
    const r = await fn(); if (r.status !== 429) return r;
    const ra = Number(r.headers.get("retry-after")) || 30; console.log(`429 on ${label}: waiting ${Math.min(ra, 120)}s (rate limit, not a product failure)`); await sleep(Math.min(ra, 120) * 1000 + 500);
  }
  throw new Error(`still 429 after retries: ${label}`);
}
export async function mkUserOnce(as, tenantId, name, o) {
  const r = await withRetry429(() => as.call("POST", `/api/v1/admin/tenants/${tenantId}/users`, { username: name, displayName: o.displayName ?? name, ...o }), "provision");
  if (r.status !== 201 || !r.json?.token) throw new Error(`provision ${name}: ${st(r)}`);
  const p = pw(); const a0 = new S(); a0.csrf = (await a0.call("GET", "/api/v1/auth/csrf")).json?.token;
  const a = await withRetry429(() => a0.call("POST", "/api/v1/auth/activation/complete", { token: r.json.token, password: p }), "activation"); activations++;
  if (a.status !== 200) throw new Error(`activate ${name}: ${st(a)}`);
  const s = new S(); const l = await s.login(name, p); if (l.status !== 200) throw new Error(`login ${name}: ${st(l)}`);
  return { s, name, id: r.json.userId, p };
}

export const stackEvents = { retries: 0, relogins: 0 };
/** shared stack: another workstream / the coordinator may restart the API under us. A connection failure waits (<= 10 min) for the API, re-logs in (the session may be gone) and retries ONCE; counted and reported. */
export function harden(u) {
  const raw = u.s.call.bind(u.s);
  u.s.call = async (...a) => {
    for (let attempt = 0; ; attempt++) {
      try { const r = await raw(...a); if (r.status === 401 && attempt === 0 && !a[0].startsWith?.("GET /auth")) { const l = await u.s.login(u.name, u.p); if (l.status === 200) { stackEvents.relogins++; continue; } } return r; }
      catch (e) {
        if (attempt >= 1) throw e; stackEvents.retries++; const t0 = Date.now(); let up = false;
        while (Date.now() - t0 < 600000) { await sleep(3000); try { const x = await fetch(API + "/api/v1/auth/csrf"); if (x.status === 200) { up = true; break; } } catch {} }
        if (!up) throw e; await sleep(3000); const l = await u.s.login(u.name, u.p); if (l.status !== 200) throw e; stackEvents.relogins++;
      }
    }
  };
  return u;
}
/** tenants A and B (+ workspaces), users: taA taB wsaA memA orgA — created once, cached, re-logged-in by later scripts */
export async function fixtures() {
  const sa = await superAdmin();
  if (existsSync(CACHE)) {
    try {
      const c = JSON.parse(readFileSync(CACHE, "utf8")); const f = { sa, ...c, users: {} };
      for (const [k, u] of Object.entries(c.creds)) { const s = new S(); const l = await s.login(u.name, u.p); if (l.status !== 200) throw new Error("relogin " + k + " " + st(l)); f.users[k] = { s, name: u.name, id: u.id, p: u.p }; }
      for (const u of Object.values(f.users)) harden(u); f.reused = true; return f;
    } catch (e) { console.log("fixture cache unusable (" + e.message + "): rebuilding"); }
  }
  const mkT = async (k) => { const t = (await sa.call("POST", "/api/v1/admin/tenants", { slug: `c6f-org-${k}-${tag}`, name: `c6f org ${k} ${tag}` })).json?.id; const w = (await sa.call("POST", `/api/v1/admin/tenants/${t}/workspaces`, { name: `c6f org ws ${k} ${tag}` })).json?.id; if (!t || !w) throw new Error("tenant/workspace fixture failed"); return { t, w }; };
  const A = await mkT("a"), B = await mkT("b");
  const users = {};
  users.taA = await mkUserOnce(sa, A.t, `c6f-org-taa-${tag}`, { tenantRole: "TENANT_ADMIN", workspaceId: A.w, workspaceRole: "WORKSPACE_ADMIN" });
  users.taB = await mkUserOnce(sa, B.t, `c6f-org-tab-${tag}`, { tenantRole: "TENANT_ADMIN", workspaceId: B.w, workspaceRole: "WORKSPACE_ADMIN" });
  users.wsaA = await mkUserOnce(users.taA.s, A.t, `c6f-org-wsa-${tag}`, { tenantRole: "MEMBER", workspaceId: A.w, workspaceRole: "WORKSPACE_ADMIN" });
  users.memA = await mkUserOnce(users.taA.s, A.t, `c6f-org-mem-${tag}`, { tenantRole: "MEMBER", workspaceId: A.w, workspaceRole: "VIEWER" });
  users.orgA = await mkUserOnce(users.taA.s, A.t, `c6f-org-lab-${tag}`, { tenantRole: "MEMBER", workspaceId: A.w, workspaceRole: "VIEWER" });
  const creds = Object.fromEntries(Object.entries(users).map(([k, u]) => [k, { name: u.name, id: u.id, p: u.p }]));
  writeFileSync(CACHE, JSON.stringify({ A, B, creds, tag }), { mode: 0o600 }); try { chmodSync(CACHE, 0o600); } catch {}
  for (const u of Object.values(users)) harden(u); return { sa, A, B, users, tag, reused: false };
}

/** read-only SQL against the stack's PostgreSQL (persistence confirmation only; never a write) */
export function sqlRO(q) {
  if (!/^\s*select\b/i.test(q) || /;\s*\S/.test(q)) throw new Error("read-only select only");
  return execFileSync("docker", ["exec", "c0rc-pg", "psql", "-U", "studio", "-d", "system_web_studio", "-Atc", q], { encoding: "utf8", timeout: 20000 }).trim();
}
export const uuid = () => randomUUID();
export const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/**
 * Every route of the organization API as a list of {fam, m, path, body, w (write?)}. `ids` supplies real ids of ONE tenant (the one in the path).
 * ids: { type, unit, position, grade, user, membership, assignment }
 */
export function routes(T, ids) {
  const B = `/api/v1/admin/tenants/${T}`; const v = { expectedVersion: 0 };
  const R = (fam, m, path, body, w) => ({ fam, m, path: B + path, body, w });
  return [
    R("types", "GET", "/organization-unit-types"), R("types", "GET", `/organization-unit-types/${ids.type}`),
    R("types", "POST", "/organization-unit-types", { name: "zz", code: "zz" }, true), R("types", "PATCH", `/organization-unit-types/${ids.type}`, { name: "zz", ...v }, true),
    R("types", "POST", `/organization-unit-types/${ids.type}/disable`, v, true), R("types", "POST", `/organization-unit-types/${ids.type}/enable`, v, true),
    R("units", "GET", "/organization-units"), R("units", "GET", "/organization-units?format=flat"), R("units", "GET", `/organization-units/${ids.unit}`),
    R("units", "POST", "/organization-units", { typeId: ids.type, name: "zz", code: "zz" }, true), R("units", "PATCH", `/organization-units/${ids.unit}`, { name: "zz", ...v }, true),
    R("units", "POST", `/organization-units/${ids.unit}/move`, { newParentId: null, ...v }, true), R("units", "POST", `/organization-units/${ids.unit}/archive`, v, true), R("units", "POST", `/organization-units/${ids.unit}/restore`, v, true),
    R("positions", "GET", "/positions"), R("positions", "GET", `/positions/${ids.position}`), R("positions", "POST", "/positions", { name: "zz", code: "zz" }, true),
    R("positions", "PATCH", `/positions/${ids.position}`, { name: "zz", ...v }, true), R("positions", "POST", `/positions/${ids.position}/disable`, v, true), R("positions", "POST", `/positions/${ids.position}/enable`, v, true),
    R("grades", "GET", "/grades"), R("grades", "GET", `/grades/${ids.grade}`), R("grades", "POST", "/grades", { name: "zz", code: "zz" }, true),
    R("grades", "PATCH", `/grades/${ids.grade}`, { name: "zz", ...v }, true), R("grades", "POST", `/grades/${ids.grade}/disable`, v, true), R("grades", "POST", `/grades/${ids.grade}/enable`, v, true),
    R("employees", "GET", "/employees"), R("employees", "GET", `/employees/${ids.user}`), R("employees", "POST", "/employees", { username: "zz-nobody", displayName: "zz" }, true),
    R("employees", "POST", `/employees/${ids.user}/disable`, undefined, true), R("employees", "POST", `/employees/${ids.user}/enable`, undefined, true),
    R("memberships", "GET", `/employees/${ids.user}/organization-memberships`), R("memberships", "POST", `/employees/${ids.user}/organization-memberships`, { organizationUnitId: ids.unit }, true),
    R("memberships", "PATCH", `/employees/${ids.user}/organization-memberships/${ids.membership}`, { relationType: "ZZ", ...v }, true), R("memberships", "DELETE", `/employees/${ids.user}/organization-memberships/${ids.membership}?expectedVersion=0`, undefined, true),
    R("emp-positions", "GET", `/employees/${ids.user}/positions`), R("emp-positions", "POST", `/employees/${ids.user}/positions`, { membershipId: ids.membership, positionId: ids.position }, true),
    R("emp-positions", "PATCH", `/employees/${ids.user}/positions/${ids.assignment}`, { clearGrade: true, ...v }, true), R("emp-positions", "DELETE", `/employees/${ids.user}/positions/${ids.assignment}?expectedVersion=0`, undefined, true),
  ];
}
export const call = (s, r, extraHeaders) => s.call(r.m, r.path, r.body, extraHeaders);
export const median = (a) => { const b = [...a].sort((x, y) => x - y); return b[Math.floor(b.length / 2)]; };
export { API };
