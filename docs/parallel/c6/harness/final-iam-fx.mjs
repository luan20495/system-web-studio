// C6 FINAL RC QA — shared fixture layer for final-onboarding / final-iam / final-adv.
// Goal: stay inside the shared activation budget (30 activations / 600 s per client IP, coordinator note): every fixture account is created ONCE through the product
// routes (provision -> activate), its credentials are kept ONLY in a mode-600 file under the scratchpad (never in the repo, never printed), and later scripts re-login.
// A 429 on activation is NOT a product failure: wait Retry-After and retry. X-Forwarded-For is never used. Every successful activation is counted and reported.
import { chmodSync, existsSync, readFileSync, writeFileSync } from "node:fs";
import { S, pw, sleep, st, tag } from "./final-lib.mjs";
export const STATE = process.env.C6_IAM_STATE ?? "/private/tmp/claude-501/-Users-hoangluan-code-HBL/0a63d710-a6f1-4dad-8918-b63006286da6/scratchpad/c6f-iam-state.json";
export const DEFAULT_TENANT = "00000000-0000-0000-0000-000000000001";
export const loadState = () => { try { return existsSync(STATE) ? JSON.parse(readFileSync(STATE, "utf8")) : { runTag: tag, activations: 0, users: {}, tenants: {}, ws: {}, proj: {} }; } catch { return { runTag: tag, activations: 0, users: {}, tenants: {}, ws: {}, proj: {} }; } };
export const saveState = (st0) => { writeFileSync(STATE, JSON.stringify(st0, null, 1), { mode: 0o600 }); try { chmodSync(STATE, 0o600); } catch {} };
export const counter = { activations: 0, retries429: 0, waitedMs: 0 };

/** activation with 429 handling (reads Retry-After / details.retryAfterSeconds, waits at most 10 min per attempt) */
export async function activate(token, password) {
  for (let i = 0; i < 6; i++) {
    const a0 = new S(); a0.csrf = (await a0.call("GET", "/api/v1/auth/csrf")).json?.token;
    const a = await a0.call("POST", "/api/v1/auth/activation/complete", { token, password });
    if (a.status === 200) { counter.activations++; return a; }
    if (a.status === 429) { const ra = Number(a.headers.get("retry-after") ?? a.json?.details?.retryAfterSeconds ?? 60); const w = Math.min(Math.max(ra, 5), 600) + 2; counter.retries429++; counter.waitedMs += w * 1000; console.log(`  (activation 429, waiting ${w}s)`); await sleep(w * 1000); continue; }
    return a;
  }
  throw new Error("activation: still 429 after 6 waits");
}
/** provision through the tenant route, activate, login. Returns {s,name,id,p}. `as` = the acting session. */
export async function provision(as, tenantId, name, o, label = name) {
  const r = await as.call("POST", `/api/v1/admin/tenants/${tenantId}/users`, { username: name, displayName: o.displayName ?? name, ...o });
  if (r.status !== 201 || !r.json?.token) throw new Error(`provision ${label}: ${st(r)}`);
  return await finish(r.json.token, r.json.userId, name, label);
}
export async function finish(token, userId, name, label = name) {
  const p = pw(); const a = await activate(token, p); if (a.status !== 200) throw new Error(`activate ${label}: ${st(a)}`);
  const s = new S(); const l = await s.login(name, p); if (l.status !== 200) throw new Error(`login ${label}: ${st(l)}`);
  return { s, name, id: userId, p, token };
}
/** keep/restore a fixture user: state.users[key] = {name,id,p}. Re-login only; creation is delegated to `make()` when the account is unknown. */
export async function ensureUser(state, key, make) {
  const u = state.users[key];
  if (u) { const s = new S(); const l = await s.login(u.name, u.p); if (l.status === 200) return { s, ...u }; console.log(`  (fixture ${key}: login ${st(l)} -> will try re-enable by caller)`); return { s: null, ...u, loginStatus: l.status }; }
  const made = await make(); const { token, ...keep } = made; state.users[key] = { name: made.name, id: made.id, p: made.p }; saveState(state); return made;
}
export const relogin = async (u) => { const s = new S(); const r = await s.login(u.name, u.p); u.s = s; return r; };
export const hasAll = (arr, want) => want.every((x) => (arr ?? []).includes(x));
export const eqSet = (a, b) => { const x = [...(a ?? [])].sort().join(","), y = [...b].sort().join(","); return x === y; };
export const TA_CODES = ["EMPLOYEE_MANAGE", "EMPLOYEE_VIEW", "ORG_STRUCTURE_MANAGE", "ORG_STRUCTURE_VIEW", "POSITION_GRADE_MANAGE", "POSITION_GRADE_VIEW", "TENANT_MANAGE", "TENANT_MEMBERS"];
export const WSADMIN_CODES = ["ACTION_EXECUTE", "APP_EDIT", "APP_PUBLISH", "APP_SHARE", "APP_USE", "APP_VIEW", "DATA_MUTATE", "DATA_SOURCE_MANAGE", "DATA_SOURCE_VIEW", "MEMBER_MANAGE", "QUERY_EXECUTE", "WORKFLOW_EXECUTE", "WORKFLOW_MANAGE"];
export const leakRe = /password|passwordhash|\$2[aby]\$|secret|apikey|api_key|"hash"|bearer |credential"?:\s*"/i;

// The c0rc API can be restarted by the coordinator (recovery tests) while a script runs. Network-level failures (never an HTTP status) wait for readiness and retry; counted and reported.
export const netStats = { retries: 0, waitedMs: 0 };
const realFetch = globalThis.fetch;
globalThis.fetch = async (url, init) => {
  for (let i = 0; ; i++) {
    try { return await realFetch(url, init); }
    catch (e) {
      if (i >= 4) throw e; const t0 = Date.now(); netStats.retries++;
      const base = String(url).replace(/(https?:\/\/[^/]+).*/, "$1");
      for (let k = 0; k < 90; k++) { try { const r = await realFetch(base + "/actuator/health/readiness"); if (r.status === 200) break; } catch {} await sleep(2000); }
      netStats.waitedMs += Date.now() - t0; console.log(`  (network error ${e?.cause?.code ?? e?.message}; API unreachable ${Math.round((Date.now() - t0) / 1000)}s, retry ${i + 1})`);
    }
  }
};
