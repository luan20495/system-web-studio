// C6 FINAL RC — journey helpers (04/05/06): rate-limit-tolerant fixtures on top of final-lib.mjs. No SQL, no hooks, product routes only.
import { S, mkUser as mk0, superAdmin, sleep, st, tag, pw } from "./final-lib.mjs";
export * from "./final-lib.mjs";
export async function retry429(fn, tries = 12) { let e; for (let i = 0; i < tries; i++) { try { return await fn(); } catch (x) { e = x; if (!/429/.test(String(x))) throw x; await sleep(5000 + i * 3000); } } throw e; }
export const mkUser = (as, T, name, o) => retry429(() => mk0(as, T, name, o));
export async function call429(s, m, p, b, h) { for (let i = 0; i < 8; i++) { const r = await s.call(m, p, b, h); if (r.status !== 429) return r; await sleep(3000 + i * 2000); } return await s.call(m, p, b, h); }
export const poll = async (fn, ok, tries = 60, ms = 1000) => { let v; for (let i = 0; i < tries; i++) { v = await fn(); if (ok(v)) return v; await sleep(ms); } return v; };
export const eq = (a, b) => JSON.stringify(canon(a)) === JSON.stringify(canon(b));
export function canon(v) { if (Array.isArray(v)) return v.map(canon); if (v && typeof v === "object") return Object.fromEntries(Object.keys(v).sort().map((k) => [k, canon(v[k])])); return v; }
// Shared fixtures for journeys 04/05/06 (activation budget: the product limits activation to 30 / 600 s / IP and four workstreams share one IP):
// ONE set of accounts is created through the product routes and re-used by every journey script. Credentials live ONLY in a mode-600 file under the
// scratchpad (never in the repo, never printed). Re-login is free (only FAILED logins are rate limited).
import { readFileSync, writeFileSync, existsSync } from "node:fs";
export const FIXFILE = process.env.J_FIX ?? "/private/tmp/claude-501/-Users-hoangluan-code-HBL/0a63d710-a6f1-4dad-8918-b63006286da6/scratchpad/c6f-journeys-fix.json";
export let activations = 0;
async function mkAct(sa, T, name, o) { activations++; const u = await mkUser(sa, T, name, o); return { name: u.name, id: u.id, p: u.p }; }
export async function getFixtures() {
  const sa = await superAdmin(); let f = existsSync(FIXFILE) ? JSON.parse(readFileSync(FIXFILE, "utf8")) : null;
  if (!f) {
    const t = tag; f = { tag: t };
    f.T1 = (await call429(sa, "POST", "/api/v1/admin/tenants", { slug: `c6f-j-a-${t}`, name: `c6f journeys A ${t}` })).json?.id;
    f.W1 = (await call429(sa, "POST", `/api/v1/admin/tenants/${f.T1}/workspaces`, { name: `c6f j ws A ${t}` })).json?.id;
    f.W1b = (await call429(sa, "POST", `/api/v1/admin/tenants/${f.T1}/workspaces`, { name: `c6f j ws A2 ${t}` })).json?.id;
    f.T2 = (await call429(sa, "POST", "/api/v1/admin/tenants", { slug: `c6f-j-b-${t}`, name: `c6f journeys B ${t}` })).json?.id;
    f.W2 = (await call429(sa, "POST", `/api/v1/admin/tenants/${f.T2}/workspaces`, { name: `c6f j ws B ${t}` })).json?.id;
    writeFileSync(FIXFILE, JSON.stringify(f), { mode: 0o600 });
  }
  const spec = { wa: [f.T1, { workspaceId: f.W1, workspaceRole: "WORKSPACE_ADMIN" }], wa2: [f.T1, { workspaceId: f.W1, workspaceRole: "WORKSPACE_ADMIN" }], ed: [f.T1, { workspaceId: f.W1, workspaceRole: "EDITOR" }],
    vw: [f.T1, { workspaceId: f.W1, workspaceRole: "VIEWER" }], ta2: [f.T2, { tenantRole: "TENANT_ADMIN", workspaceId: f.W2, workspaceRole: "WORKSPACE_ADMIN" }] };
  for (const [k, [T, o]] of Object.entries(spec)) if (!f[k]) { f[k] = await mkAct(sa, T, `c6f-j-${k}-${f.tag}`, o); writeFileSync(FIXFILE, JSON.stringify(f), { mode: 0o600 }); }
  const out = { f, sa, activations };
  for (const k of ["wa", "wa2", "ed", "vw", "ta2"]) { const s = new S(); const l = await s.login(f[k].name, f[k].p); if (l.status !== 200) throw new Error(`login ${k}: ${st(l)}`); out[k] = { s, name: f[k].name, id: f[k].id }; }
  return out;
}
