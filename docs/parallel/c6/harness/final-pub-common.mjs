// C6 FINAL RC QA — shared fixture + helpers of the publish / PD02 / published-site scripts (final-publish, final-pd02, final-site).
// Product routes only (invite -> activate), names prefixed c6f-, random per-run passwords, no SQL, no test hook. Never prints a secret.
// The activation endpoint is rate limited per address (429 + retryAfterSeconds) and the machine is shared: activation waits and retries (bounded).
// Fixture credentials are cached in a mode-600 file OUTSIDE the repo (scratchpad) so the three scripts of one run share one tenant; the file is removed by `cleanup()`.
import { readFileSync, writeFileSync, existsSync, mkdirSync, unlinkSync } from "node:fs";
import { S, API, tag, pw, sleep, st, superAdmin } from "./final-lib.mjs";
export { S, API, tag, sleep, st, superAdmin };
export const SITES = (process.env.FINAL_SITES ?? "http://127.0.0.1:47305").replace(/\/$/, "");
const CACHE_DIR = process.env.C6_SCRATCH ?? "/private/tmp/claude-501/-Users-hoangluan-code-HBL/0a63d710-a6f1-4dad-8918-b63006286da6/scratchpad/c6pub";
const CACHE = `${CACHE_DIR}/fixture.json`;
export const TERMINAL = ["RUNNING", "FAILED", "ROLLED_BACK"];

export async function activate(token, label) {
  for (let i = 0; i < 6; i++) {
    const a0 = new S(); a0.csrf = (await a0.call("GET", "/api/v1/auth/csrf")).json?.token; const p = pw();
    const a = await a0.call("POST", "/api/v1/auth/activation/complete", { token, password: p });
    if (a.status === 200) return p;
    if (a.status === 429) { const w = Math.min(130, (a.json?.details?.retryAfterSeconds ?? 60) + 2); console.log(`  (activation rate limited for ${label}: waiting ${w}s)`); await sleep(w * 1000); continue; }
    throw new Error(`activate ${label}: ${st(a)}`);
  }
  throw new Error(`activate ${label}: still rate limited after 6 tries`);
}
export async function mkUserW(as, tenantId, name, o) {
  const r = await as.call("POST", `/api/v1/admin/tenants/${tenantId}/users`, { username: name, displayName: o.displayName ?? name, ...o });
  if (r.status !== 201 || !r.json?.token) throw new Error(`provision ${name}: ${st(r)}`);
  const p = await activate(r.json.token, name);
  return { name, id: r.json.userId, p };
}
export async function loginAs(u) { const s = new S(); for (let i = 0; i < 4; i++) { const l = await s.login(u.name, u.p); if (l.status === 200) return s; if (l.status === 429) { await sleep(((l.json?.details?.retryAfterSeconds ?? 30) + 2) * 1000); continue; } throw new Error(`login ${u.name}: ${st(l)}`); } throw new Error(`login ${u.name}: rate limited`); }

/** tenant A (workspace WA): ta = TENANT_ADMIN + WORKSPACE_ADMIN (project owner), pub = project PUBLISHER, edt = project EDITOR, vwr = project VIEWER; tenant B (workspace WB): tb = TENANT_ADMIN + WORKSPACE_ADMIN. */
export async function fixture() {
  if (existsSync(CACHE)) { try { const f = JSON.parse(readFileSync(CACHE, "utf8")); if (f.api === API) { f.s = {}; for (const k of ["ta", "pub", "edt", "vwr", "tb"]) f.s[k] = await loginAs(f.users[k]); return f; } } catch (e) { console.log("fixture cache unusable: " + e.message); } }
  const sa = await superAdmin(); const f = { api: API, tag, users: {}, s: {} };
  f.T1 = (await sa.call("POST", "/api/v1/admin/tenants", { slug: `c6f-pa-${tag}`, name: `C6 final publish A ${tag}` })).json?.id;
  f.T2 = (await sa.call("POST", "/api/v1/admin/tenants", { slug: `c6f-pb-${tag}`, name: `C6 final publish B ${tag}` })).json?.id;
  f.WA = (await sa.call("POST", `/api/v1/admin/tenants/${f.T1}/workspaces`, { name: `c6f ws A ${tag}` })).json?.id;
  f.WB = (await sa.call("POST", `/api/v1/admin/tenants/${f.T2}/workspaces`, { name: `c6f ws B ${tag}` })).json?.id;
  f.users.ta = await mkUserW(sa, f.T1, `c6f-pta-${tag}`, { tenantRole: "TENANT_ADMIN", workspaceId: f.WA, workspaceRole: "WORKSPACE_ADMIN" });
  f.s.ta = await loginAs(f.users.ta);
  f.users.pub = await mkUserW(f.s.ta, f.T1, `c6f-ppub-${tag}`, { workspaceId: f.WA, workspaceRole: "VIEWER" });
  f.users.edt = await mkUserW(f.s.ta, f.T1, `c6f-pedt-${tag}`, { workspaceId: f.WA, workspaceRole: "VIEWER" });
  f.users.vwr = await mkUserW(f.s.ta, f.T1, `c6f-pvwr-${tag}`, { workspaceId: f.WA, workspaceRole: "VIEWER" });
  f.users.tb = await mkUserW(sa, f.T2, `c6f-ptb-${tag}`, { tenantRole: "TENANT_ADMIN", workspaceId: f.WB, workspaceRole: "WORKSPACE_ADMIN" });
  for (const k of ["pub", "edt", "vwr", "tb"]) f.s[k] = await loginAs(f.users[k]);
  mkdirSync(CACHE_DIR, { recursive: true, mode: 0o700 }); writeFileSync(CACHE, JSON.stringify({ ...f, s: undefined }), { mode: 0o600 });
  return f;
}
/** a new PAGE_SCHEMA project owned by ta, with pub = PUBLISHER, edt = EDITOR, vwr = VIEWER; returns an api helper bound to it */
export async function newProject(f, label) {
  const w = f.WA; const r = await f.s.ta.call("POST", `/api/v1/workspaces/${w}/projects`, { name: `c6f-${label}-${tag}`, appType: "PAGE_SCHEMA" });
  if (r.status !== 201) throw new Error(`create project ${label}: ${st(r)}`); const p = r.json.id; const base = `/api/v1/workspaces/${w}/projects/${p}`;
  const members = {}; for (const [k, role] of [["pub", "PUBLISHER"], ["edt", "EDITOR"], ["vwr", "VIEWER"]]) members[k] = (await f.s.ta.call("POST", `${base}/members`, { username: f.users[k].name, role })).status;
  return projectApi(f, f.s.ta, w, p, members);
}
export function projectApi(f, S0, w, p, members = {}) {
  const base = `/api/v1/workspaces/${w}/projects/${p}`; let n = 0;
  const api = {
    w, p, base, members, S: S0,
    get: (path = "") => S0.call("GET", base + path), project: async () => (await S0.call("GET", base)).json,
    revision: async () => (await S0.call("GET", base)).json?.revision,
    site: async (as = S0) => (await as.call("GET", `${base}/site`)).json,
    config: async (as = S0) => (await as.call("GET", `${base}/publish-config`)),
    putConfig: (body, as = S0) => as.call("PUT", `${base}/publish-config`, body),
    key: (l) => `c6f:${tag}:${l}:${Date.now()}:${n++}`,
    publish: async (visibility, { as = S0, key, expectedRevision, extra = {} } = {}) => as.call("POST", `${base}/publish`, { visibility, expectedRevision: expectedRevision ?? await api.revision(), ...extra }, { "idempotency-key": key ?? api.key("pub") }),
    rollback: (body, as = S0, key) => as.call("POST", `${base}/site/rollback`, body, key ? { "idempotency-key": key } : {}),
    unpublish: (expected, as = S0) => as.call("DELETE", `${base}/site${expected ? `?expectedActiveDeploymentId=${expected}` : ""}`, undefined),
    deployment: async (id) => (await S0.call("GET", `${base}/deployments/${id}`)).json,
    deployments: async () => (await S0.call("GET", `${base}/deployments`)).json ?? [],
    async settle(id, ms = 120000) { const end = Date.now() + ms; let d; while (Date.now() < end) { d = await api.deployment(id); if (TERMINAL.includes(d?.status)) return d; await sleep(500); } return d; },
    async edit(text) { const sc = (await S0.call("GET", `${base}/schema`)).json; return S0.call("PATCH", `${base}/schema`, { expectedRevision: sc.revision, summary: `c6f ${text}`, operations: [{ type: "UPDATE_PROP", sectionId: sc.schema.sections[0].id, path: "brand", value: text }] }); },
    async patch(operations, summary = "c6f patch") { const sc = (await S0.call("GET", `${base}/schema`)).json; return S0.call("PATCH", `${base}/schema`, { expectedRevision: sc.revision, summary, operations }); },
    schema: async () => (await S0.call("GET", `${base}/schema`)).json,
    /** publish and settle, with one wait on the contract's own throttle (429 RATE_LIMITED, 10/min/user) */
    async release(visibility, label, o = {}) {
      let r = await api.publish(visibility, { key: api.key(label), ...o });
      if (r.status === 429) { await sleep(Math.min(65, Number(r.headers.get("retry-after") ?? 61)) * 1000); r = await api.publish(visibility, { key: api.key(label), ...o }); }
      if (r.status !== 202) return { r, d: null };
      return { r, d: await api.settle(r.json.id) };
    },
  };
  return api;
}
export const hdrs = (r) => Object.fromEntries([...r.headers.entries()]);
/** plain anonymous HTTP (no cookies at all) */
export async function raw(url, init = {}) {
  const t0 = Date.now();
  try { const r = await fetch(url, { redirect: "manual", signal: AbortSignal.timeout(20000), ...init, headers: { ...(init.headers ?? {}) } }); const buf = Buffer.from(await r.arrayBuffer()); if (String(url).startsWith(SITES)) { gw.total++; if (r.status >= 500) gw.s5xx.push(`${r.status} ${new URL(url).pathname}`); } const text = buf.toString("utf8"); let json = null; try { json = JSON.parse(text); } catch {} return { status: r.status, text, buf, json, headers: r.headers, ms: Date.now() - t0 }; }
  catch (e) { if (String(url).startsWith(SITES)) { gw.total++; gw.s0.push(`${String(e?.message ?? e).slice(0, 60)} ${new URL(url).pathname}`); } return { status: 0, text: "", buf: Buffer.alloc(0), json: null, headers: new Headers(), ms: Date.now() - t0, err: String(e?.message ?? e) }; }
}
export const hdr = (r, n) => r.headers.get(n) ?? "";
export const gw = { total: 0, s5xx: [], s0: [] };   // every request to the sites gateway made through raw(): count, 5xx and transport failures
export async function cleanup(f) {
  if (!f) return; const sa = await superAdmin().catch(() => null);
  if (sa) for (const k of Object.keys(f.users)) { await sa.call("PATCH", `/api/v1/admin/users/${f.users[k].id}/status`, { enabled: false }); }
  try { unlinkSync(CACHE); } catch {}
}

/** the PAGE_SCHEMA public-data chain on project `a` through product routes only: Management API data source + credential + approved query definition + LIVE binding,
 *  then typed operations: slot, public READ query, binding of Navbar.brand. Returns what was created and every status (so the caller records, never assumes). */
export async function authorPublicData(f, a, { slot = "pd-orders", qid = "pd-public", opKey = "c6f.pd.read" } = {}) {
  const { readFileSync } = await import("node:fs"); const out = { slot, qid, opKey, steps: {} };
  const DS = `/api/v1/workspaces/${f.WA}/data-sources`; const ta = f.s.ta;
  let RO = ""; try { RO = readFileSync("/Users/hoangluan/code/HBL/.run/data-target/ro.pw", "utf8").trim(); } catch {}
  let r = await ta.call("POST", DS, { name: `c6f-ds-${tag}-${Date.now().toString(36)}`, type: "postgres", config: { host: "127.0.0.1", port: "15440", database: "shop", schemas: "shop" } });
  out.steps.createDs = r.status; out.dsId = r.json?.id;
  if (out.dsId) {
    r = await ta.call("PUT", `${DS}/${out.dsId}/credential`, { credential: { username: "shop_ro", password: RO } }); out.steps.credential = r.status;
    r = await ta.call("POST", `${DS}/${out.dsId}/test`, {}); out.steps.test = `${r.status} ok=${r.json?.ok} code=${r.json?.code ?? ""}`; out.dsTestOk = r.json?.ok === true;
    r = await ta.call("POST", `${DS}/${out.dsId}/queries`, { queryId: opKey, kind: "SQL", definition: { sql: "SELECT order_no AS name FROM shop.orders ORDER BY order_no LIMIT 1", params: [], maxRows: 5 } }); out.steps.queryDef = r.status;
  }
  const sc = await a.schema(); const sectionId = sc.schema.sections.find((s) => s.type === "Navbar")?.id ?? sc.schema.sections[0].id;
  r = await a.patch([{ type: "ADD_DATA_SOURCE", definition: { id: slot, name: "Orders", type: "postgres" } }], "c6f slot"); out.steps.slot = `${r.status} ${r.json?.code ?? ""}`;
  r = await a.patch([{ type: "ADD_QUERY", definition: { id: qid, name: "Public brand", dataSourceRef: slot, mode: "READ", operationKey: opKey, public: true } }], "c6f public query"); out.steps.query = `${r.status} ${r.json?.code ?? ""}`;
  r = await a.patch([{ type: "ADD_MAPPING", definition: { id: "pd-map", queryRef: qid, fields: [{ from: "name", to: "name" }] } }], "c6f mapping"); out.steps.mapping = `${r.status} ${r.json?.code ?? ""}`;
  r = await a.patch([{ type: "ADD_DATA_BINDING", definitionId: "b1", definition: { id: "b1", sectionId, prop: "brand", queryRef: qid } }], "c6f binding"); out.steps.binding = `${r.status} ${r.json?.code ?? ""} ${r.status >= 400 ? JSON.stringify(r.json).slice(0, 160) : ""}`;
  if (out.dsId) { r = await a.S.call("PUT", `${a.base}/data-bindings/LIVE/${encodeURIComponent(slot)}`, { dataSourceId: out.dsId }); out.steps.liveBind = r.status; }
  out.ok = out.steps.slot.startsWith("200") && out.steps.query.startsWith("200") && out.steps.mapping.startsWith("200") && out.steps.binding.startsWith("200");
  return out;
}
