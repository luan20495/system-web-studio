#!/usr/bin/env node
// C6 RC wide regression — MANAGEMENT API + DATA + ACTION + WORKFLOW through the REAL backend API (no SQL, no test hook, no stub).
// Real data source = the V1 local TLS PostgreSQL target (127.0.0.1:15440, scripts/data-target.sh; roles shop_ro = SELECT, shop_rw = SELECT/INSERT/UPDATE on shop.orders).
// Chain: Data Source → credential → test → discover → Query definition → slot (ADD_DATA_SOURCE) → ADD_QUERY/ADD_MAPPING/ADD_VIEW_MODEL → TEST/LIVE binding → TEST run → publish → LIVE run
//        → mutation definition → CREATE_RECORD action (TEST = WOULD_RUN, LIVE = real row, same key twice = one row) → failure states → isolation → workflow runs.
// Env: API  SA_USER  SA_PASSWORD  (read by the caller from the stack's own env file)  RO_PW_FILE  RW_PW_FILE  (target role passwords, files outside the repo)  OUT  FACTS_FILE (optional, mode 600)
// Secrets never printed and never written to evidence: every response body is scanned for the target passwords and a hit fails the check.
import { randomBytes, randomUUID } from "node:crypto";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";

const API = (process.env.API ?? "http://127.0.0.1:51080").replace(/\/$/, ""); const OUT = process.env.OUT ?? "."; mkdirSync(OUT, { recursive: true });
const SA_USER = process.env.SA_USER, SA_PASSWORD = process.env.SA_PASSWORD; const RO = readFileSync(process.env.RO_PW_FILE, "utf8").trim(), RW = readFileSync(process.env.RW_PW_FILE, "utf8").trim();
const TARGET_PORT = process.env.DATA_TARGET_PORT ?? "15440"; const tag = randomUUID().slice(0, 6); const pw = () => randomBytes(15).toString("base64url") + "aA1!";
const rows = []; let leaked = false;
const rec = (id, area, desc, expected, actual, ok, note = "") => { if (!ok) note = (note ? note + " | " : "") + "last: " + (globalThis.__last ?? ""); rows.push({ id, area, desc, expected: String(expected), actual: String(actual).slice(0, 300), result: ok ? "PASS" : "FAIL", note }); console.log(`${ok ? "PASS" : "FAIL"} ${id} [${area}] ${desc} | expected ${expected} | actual ${String(actual).slice(0, 220)}${note ? " | " + note : ""}`); };
const st = (r) => `${r.status}${r.json?.code ? " " + r.json.code : r.json?.status ? " " + r.json.status : ""}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class S {
  constructor() { this.jar = new Map(); this.csrf = null; }
  async call(method, path, body, headers = {}) {
    const h = { ...headers }; if (this.jar.size) h.cookie = [...this.jar].map(([k, v]) => `${k}=${v}`).join("; ");
    if (this.csrf && !["GET", "HEAD"].includes(method) && !("x-xsrf-token" in h)) h["x-xsrf-token"] = this.csrf;
    if (body !== undefined) h["content-type"] = "application/json";
    const res = await fetch(API + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), redirect: "manual" });
    for (const c of res.headers.getSetCookie?.() ?? []) { const [kv] = c.split(";"); const i = kv.indexOf("="); const k = kv.slice(0, i); if (/max-age=0/i.test(c)) this.jar.delete(k); else this.jar.set(k, kv.slice(i + 1)); }
    const text = await res.text(); if (text.includes(RO) || text.includes(RW)) leaked = true; globalThis.__last = `${method} ${path.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g, "{id}")} -> ${res.status} ${text.slice(0, 380)}`;
    let json = null; try { json = text ? JSON.parse(text) : null; } catch {} return { status: res.status, json, text, headers: res.headers };
  }
  get(p, h) { return this.call("GET", p, undefined, h); } post(p, b, h) { return this.call("POST", p, b ?? {}, h); } put(p, b) { return this.call("PUT", p, b); } patch(p, b) { return this.call("PATCH", p, b); } del(p) { return this.call("DELETE", p); }
  async login(u, p) { this.jar.clear(); this.csrf = (await this.call("GET", "/api/v1/auth/csrf")).json?.token; const r = await this.call("POST", "/api/v1/auth/login", { username: u, password: p }); this.csrf = (await this.call("GET", "/api/v1/auth/csrf")).json?.token; return r; }
}
const anon = new S();
async function mkUser(as, T, name, o) {
  const r = await as.post(`/api/v1/admin/tenants/${T}/users`, { username: name, displayName: name, ...o }); if (r.status !== 201) throw new Error(`provision ${name}: ${st(r)}`);
  const p = pw(); await anon.get("/api/v1/auth/csrf"); anon.csrf = (await anon.get("/api/v1/auth/csrf")).json?.token;
  const a = await anon.post("/api/v1/auth/activation/complete", { token: r.json.token, password: p }); if (a.status !== 200) throw new Error(`activate ${name}: ${st(a)}`);
  const s = new S(); const l = await s.login(name, p); if (l.status !== 200) throw new Error(`login ${name}: ${st(l)}`); return { s, name, id: r.json.userId, p };
}
const poll = async (fn, ok, tries = 60, ms = 1000) => { let v; for (let i = 0; i < tries; i++) { v = await fn(); if (ok(v)) return v; await sleep(ms); } return v; };

try {
// ---------------------------------------------------------------- fixtures: tenant A (WS admin, editor, viewer), tenant B (foreign)
const sa = new S(); const l0 = await sa.login(SA_USER, SA_PASSWORD); if (l0.status !== 200) throw new Error("super admin login " + st(l0));
const T1 = (await sa.post("/api/v1/admin/tenants", { slug: `c6d-a-${tag}`, name: `C6 data A ${tag}` })).json?.id, T2 = (await sa.post("/api/v1/admin/tenants", { slug: `c6d-b-${tag}`, name: `C6 data B ${tag}` })).json?.id;
const W1 = (await sa.post(`/api/v1/admin/tenants/${T1}/workspaces`, { name: `C6 data ws A ${tag}` })).json?.id, W2 = (await sa.post(`/api/v1/admin/tenants/${T2}/workspaces`, { name: `C6 data ws B ${tag}` })).json?.id;
const ta1 = await mkUser(sa, T1, `c6d-ta1-${tag}`, { tenantRole: "TENANT_ADMIN", workspaceId: W1, workspaceRole: "WORKSPACE_ADMIN" });
const wa = await mkUser(ta1.s, T1, `c6d-wa-${tag}`, { workspaceId: W1, workspaceRole: "WORKSPACE_ADMIN" });
const ed = await mkUser(ta1.s, T1, `c6d-ed-${tag}`, { workspaceId: W1, workspaceRole: "EDITOR" });
const vw = await mkUser(ta1.s, T1, `c6d-vw-${tag}`, { workspaceId: W1, workspaceRole: "VIEWER" });
const ta2 = await mkUser(sa, T2, `c6d-ta2-${tag}`, { tenantRole: "TENANT_ADMIN", workspaceId: W2, workspaceRole: "WORKSPACE_ADMIN" });
rec("F00", "fixture", "two tenants, WS admin / EDITOR / VIEWER of A, TA of B created through the product routes", "ready", `W1=${!!W1} W2=${!!W2}`, !!(W1 && W2 && wa && ed && vw && ta2));
const DS = `/api/v1/workspaces/${W1}/data-sources`; const cfg = { host: "127.0.0.1", port: String(TARGET_PORT), database: "shop", schemas: "shop" };

// ================================================================= MANAGEMENT API
let r = await wa.s.get(`${DS}/connectors`); rec("M01", "mgmt", "connector catalogue", "200 {items} with postgres available", st(r) + " " + (r.json?.items ?? []).map((c) => c.type ?? c.id).join(","), r.status === 200 && JSON.stringify(r.json).includes("postgres"));
const name1 = `c6-ro-${tag}`;
r = await wa.s.post(DS, { name: name1, type: "postgres", config: cfg }); const ds1 = r.json?.id; rec("M02", "mgmt", "create data source -> the TLS target (WORKSPACE_ADMIN)", "201 DataSource without credentialRef", `${st(r)} v=${r.json?.version} status=${r.json?.status} hasCredential=${r.json?.hasCredential}`, r.status === 201 && !!ds1 && !/credentialRef/.test(r.text) && r.json?.hasCredential === false);
r = await wa.s.post(DS, { name: name1.toUpperCase(), type: "postgres", config: cfg }); rec("M03", "mgmt", "duplicate name differing only by case", "409 CONFLICT", st(r), r.status === 409);
r = await wa.s.post(DS, { name: "bad name !", type: "postgres", config: cfg }); rec("M04a", "mgmt", "invalid name", "400 INVALID_CONFIG", st(r), r.status === 400 && r.json?.code === "INVALID_CONFIG");
r = await wa.s.post(DS, { name: `c6-x-${tag}`, type: "nosuchdb", config: {} }); rec("M04b", "mgmt", "unknown connector type", "422 UNSUPPORTED_TYPE", st(r), r.status === 422 && r.json?.code === "UNSUPPORTED_TYPE");
r = await wa.s.post(DS, { name: `c6-y-${tag}`, type: "mysql", config: {} }); rec("M04c", "mgmt", "planned connector (mysql)", "501 NOT_IMPLEMENTED", st(r), r.status === 501 && r.json?.code === "NOT_IMPLEMENTED");
r = await wa.s.post(DS, { name: `c6-z-${tag}`, type: "postgres", config: { ...cfg, password: "x" } }); rec("M04d", "mgmt", "secret key inside config", "400 INVALID_CONFIG", st(r), r.status === 400 && r.json?.code === "INVALID_CONFIG");
r = await wa.s.post(DS, { name: `c6-w-${tag}`, type: "postgres", config: cfg, tenantId: T2 }); rec("M04e", "mgmt", "authority key (tenantId) in the body", "400 INVALID_PARAMS", st(r), r.status === 400 && r.json?.code === "INVALID_PARAMS");
for (const [label, host, port] of [["platform database", "127.0.0.1", 15432], ["apps database", "127.0.0.1", 15434], ["platform DB by name", "localhost", 15432], ["not allow-listed port on loopback", "127.0.0.1", 15441], ["cloud metadata address", "169.254.169.254", 5432]]) {
  r = await wa.s.post(DS, { name: `c6-b-${randomUUID().slice(0, 5)}`, type: "postgres", config: { host, port: String(port), database: "x" } }); rec(`M05-${port}-${host.slice(0, 3)}`, "mgmt-ssrf", `${label} is refused by the address policy`, "400 INVALID_CONFIG", st(r), r.status === 400 && r.json?.code === "INVALID_CONFIG");
}
r = await wa.s.put(`${DS}/${ds1}/credential`, { credential: { username: "shop_ro", password: RO } }); rec("M06", "mgmt-secret", "PUT credential: metadata only, no secret in the answer", "200 {configured:true, keys:[…]} without the password", `${st(r)} keys=${JSON.stringify(r.json?.keys)}`, r.status === 200 && r.json?.configured === true && !r.text.includes(RO));
r = await wa.s.get(`${DS}/${ds1}/credential`); rec("M07", "mgmt-secret", "GET credential returns key names only", "200 keys only", `${st(r)} ${JSON.stringify(Object.keys(r.json ?? {}))}`, r.status === 200 && !r.text.includes(RO) && !/password"\s*:\s*"/.test(r.text));
r = await wa.s.get(`${DS}/${ds1}`); const r2 = await wa.s.get(DS); rec("M08", "mgmt-secret", "data source detail and list expose hasCredential, never the credential / credentialRef", "no secret", `detail ${r.status}/list ${r2.status} hasCredential=${r.json?.hasCredential}`, r.json?.hasCredential === true && !/credentialRef|ciphertext/.test(r.text + r2.text));
r = await wa.s.put(`${DS}/${ds1}/credential`, { credential: { username: "shop_ro", password: RO, apiKey: "x" } }); rec("M09", "mgmt", "credential with a key the connector does not declare", "400 INVALID_CREDENTIAL", st(r), r.status === 400 && r.json?.code === "INVALID_CREDENTIAL");
r = await wa.s.post(`${DS}/${ds1}/test`, {}); rec("M10", "mgmt", "test connection over TLS (verify-full, SELECT-only role)", "200 {ok:true, latencyMs, warnings[]}", `${st(r)} ${JSON.stringify({ ok: r.json?.ok, ms: r.json?.latencyMs, warnings: r.json?.warnings })}`, r.status === 200 && r.json?.ok === true && Array.isArray(r.json?.warnings ?? []));
r = await wa.s.post(`${DS}/${ds1}/schema/discover`, {}); rec("M11", "mgmt", "schema discovery from the real database", "200, ≥ 2 entities (customers, orders)", `${st(r)} entities=${r.json?.entityCount}`, r.status === 200 && r.json?.entityCount >= 2);
r = await wa.s.get(`${DS}/${ds1}/schema`); rec("M12", "mgmt", "stored snapshot has customers and orders and no secret", "200", `${r.status}`, r.status === 200 && /customers/.test(r.text) && /orders/.test(r.text));
// failure states of the connection test
const dsBad = (await wa.s.post(DS, { name: `c6-bad-${tag}`, type: "postgres", config: cfg })).json?.id;
await wa.s.put(`${DS}/${dsBad}/credential`, { credential: { username: "shop_ro", password: "definitely-wrong-" + tag } }); r = await wa.s.post(`${DS}/${dsBad}/test`, {});
rec("M13", "mgmt-failure", "test with a wrong password", "200 {ok:false, code:AUTH_REJECTED}", `${st(r)} ok=${r.json?.ok} code=${r.json?.code}`, r.status === 200 && r.json?.ok === false && r.json?.code === "AUTH_REJECTED");
const dsRw = (await wa.s.post(DS, { name: `c6-rw-${tag}`, type: "postgres", config: { ...cfg, writable: "true" } })); const rwId = dsRw.json?.id;
await wa.s.put(`${DS}/${rwId}/credential`, { credential: { username: "shop_rw", password: RW } }); r = await wa.s.post(`${DS}/${rwId}/test`, {});
rec("M14", "mgmt", "writable source with the shop_rw role (create status / test result)", "create 201; test 200 with a defined result (ok, or ROLE_TOO_PRIVILEGED when not writable)", `create ${dsRw.status}; test ${st(r)} ok=${r.json?.ok} code=${r.json?.code} warnings=${JSON.stringify(r.json?.warnings)}`, dsRw.status === 201 && r.status === 200 && typeof r.json?.ok === "boolean");
const dsRoWritable = (await wa.s.post(DS, { name: `c6-rowrite-${tag}`, type: "postgres", config: cfg })).json?.id; await wa.s.put(`${DS}/${dsRoWritable}/credential`, { credential: { username: "shop_rw", password: RW } }); r = await wa.s.post(`${DS}/${dsRoWritable}/test`, {});
rec("M15", "mgmt", "a privileged role on a source NOT declared writable is flagged", "ok:false ROLE_TOO_PRIVILEGED (or ok:true with a warning)", `${st(r)} ok=${r.json?.ok} code=${r.json?.code} warnings=${JSON.stringify(r.json?.warnings)}`, r.status === 200 && (r.json?.code === "ROLE_TOO_PRIVILEGED" || (r.json?.warnings ?? []).length > 0 || r.json?.ok === false));
// definitions
const Q = `${DS}/${ds1}/queries`; const qdef = { queryId: "shop.orders.list", kind: "SQL", definition: { sql: "SELECT o.order_no, o.status, o.amount, c.name AS customer FROM shop.orders o JOIN shop.customers c ON c.id = o.customer_id WHERE o.status = :status ORDER BY o.order_no", params: [{ name: "status", type: "STRING" }], maxRows: 50 } };
r = await wa.s.post(Q, qdef); rec("M16", "mgmt", "query definition created", "201", st(r), r.status === 201);
r = await wa.s.post(Q, { queryId: "shop.items", kind: "SQL", definition: { sql: "SELECT order_no AS name, status AS description FROM shop.orders ORDER BY order_no", params: [], maxRows: 50 } }); rec("M16b", "mgmt", "parameterless listing query definition (used by the Studio Test panel flow E2E-07)", "201", st(r), r.status === 201);
r = await wa.s.post(Q, { queryId: "bad.drop", kind: "SQL", definition: { sql: "DROP TABLE shop.orders", params: [], maxRows: 5 } }); rec("M17a", "mgmt", "DDL in a query definition", "400 INVALID_QUERY", st(r), r.status === 400 && r.json?.code === "INVALID_QUERY");
r = await wa.s.post(Q, { queryId: "bad.multi", kind: "SQL", definition: { sql: "SELECT 1; DELETE FROM shop.orders", params: [], maxRows: 5 } }); rec("M17b", "mgmt", "stacked statements in a query definition", "400 INVALID_QUERY", st(r), r.status === 400 && r.json?.code === "INVALID_QUERY");
r = await wa.s.post(Q, { queryId: "bad.write", kind: "SQL", definition: { sql: "UPDATE shop.orders SET status='x'", params: [], maxRows: 5 } }); rec("M17c", "mgmt", "write statement as a READ query definition", "400 INVALID_QUERY", st(r), r.status === 400 && r.json?.code === "INVALID_QUERY");
r = await wa.s.get(Q); rec("M18", "mgmt", "definition list is a summary (no SQL)", "200, no `sql` text", st(r), r.status === 200 && !/SELECT/i.test(r.text));
r = await wa.s.get(`${Q}/shop.orders.list`); const ver = r.json?.version; rec("M19", "mgmt", "one definition in full (admin)", "200 with the SQL", `${st(r)} v=${ver}`, r.status === 200 && /SELECT/i.test(r.text));
r = await wa.s.patch(`${Q}/shop.orders.list`, { status: "ACTIVE", expectedVersion: 999 }); rec("M20", "mgmt", "expectedVersion mismatch", "409 CONFLICT, nothing changes", st(r), r.status === 409);
// permissions / isolation
r = await ed.s.post(DS, { name: `c6-ed-${tag}`, type: "postgres", config: cfg }); rec("M21", "mgmt-authz", "EDITOR creates a data source (needs DATA_SOURCE_MANAGE)", "403", st(r), r.status === 403);
r = await vw.s.post(`${DS}/${ds1}/test`, {}); rec("M22", "mgmt-authz", "VIEWER tests a connection", "403", st(r), r.status === 403);
r = await ed.s.get(`${Q}/shop.orders.list`); rec("M23", "mgmt-authz", "EDITOR reads a full query definition (needs DATA_SOURCE_MANAGE)", "403", st(r), r.status === 403);
r = await new S().get(DS); rec("M24", "mgmt-authz", "anonymous list", "401", st(r), r.status === 401);
r = await ta2.s.get(DS); rec("M25", "mgmt-iso", "foreign tenant lists workspace A's sources", "404", st(r), r.status === 404);
r = await ta2.s.get(`/api/v1/workspaces/${W2}/data-sources/${ds1}`); rec("M26", "mgmt-iso", "foreign tenant reads A's source through ITS OWN workspace id", "404", st(r), r.status === 404);
r = await ta2.s.del(`/api/v1/workspaces/${W2}/data-sources/${ds1}`); rec("M27", "mgmt-iso", "foreign tenant deletes A's source", "404", st(r), r.status === 404);
r = await wa.s.post(DS, { name: `c6-csrf-${tag}`, type: "postgres", config: cfg }); const csrfTok = wa.s.csrf; wa.s.csrf = null; r = await wa.s.call("POST", DS, { name: `c6-csrf2-${tag}`, type: "postgres", config: cfg }, { "x-xsrf-token": "" }); wa.s.csrf = csrfTok; rec("M28", "mgmt-csrf", "state-changing management call without a CSRF token", "403 CSRF_INVALID", st(r), r.status === 403);
r = await wa.s.post("/api/v1/data/mutate", { dataSourceId: ds1, operation: "x", params: {} }); rec("M29", "mgmt", "raw browser mutation route does not exist", "404", st(r), r.status === 404);
rec("M30", "mgmt-secret", "no response of this run (any route above or below) contained the target passwords", "none", leaked ? "LEAK" : "none", !leaked);

// ================================================================= DATA: app with slot → query → mapping → viewmodel → binding
const proj = await wa.s.post(`/api/v1/workspaces/${W1}/projects`, { name: `c6-data-${tag}`, appType: "PAGE_SCHEMA" }); const P = proj.json?.id; const PB = `/api/v1/workspaces/${W1}/projects/${P}`;
let sc = (await wa.s.get(`${PB}/schema`)).json; const sectionId = sc.schema?.sections?.find((x) => x.type === "ContactForm")?.id ?? sc.schema?.sections?.[0]?.id; const pageSections = sc.schema?.sections ?? [];
const brandSection = pageSections.find((s) => /navbar/i.test(s.type ?? s.component ?? "")) ?? pageSections[0];
const ops = [
  { type: "ADD_DATA_SOURCE", definition: { id: "erp-db", name: "Shop", type: "postgres" } },
  { type: "ADD_QUERY", definition: { id: "orders-list", name: "Orders", dataSourceRef: "erp-db", mode: "READ", operationKey: "shop.orders.list", params: [{ name: "status", type: "STRING", default: "open" }], maxRows: 50 } },
  { type: "ADD_MAPPING", definition: { id: "orders-map", queryRef: "orders-list", fields: [{ from: "order_no", to: "name" }, { from: "customer", to: "description" }] } },
  { type: "ADD_VIEW_MODEL", definition: { id: "orders-vm", name: "Orders", queryRef: "orders-list", mappingRef: "orders-map", cardinality: "LIST", fields: [{ name: "name", type: "STRING" }, { name: "description", type: "STRING" }] } },
  { type: "ADD_QUERY", definition: { id: "items-list", name: "Items", dataSourceRef: "erp-db", mode: "READ", operationKey: "shop.items", params: [], maxRows: 50 } },
  { type: "ADD_MAPPING", definition: { id: "items-map", queryRef: "items-list", fields: [{ from: "name", to: "name" }, { from: "description", to: "description" }] } },
  { type: "ADD_VIEW_MODEL", definition: { id: "items-vm", name: "Items", queryRef: "items-list", mappingRef: "items-map", cardinality: "LIST", fields: [{ name: "name", type: "STRING" }, { name: "description", type: "STRING" }] } },
];
r = await wa.s.patch(`${PB}/schema`, { expectedRevision: sc.revision, operations: ops, summary: "c6 data chain" }); rec("D01", "data", "declare the slot + READ query + mapping + view model through typed operations (no SQL, no seed)", "200", `${st(r)} ${r.status === 200 ? "" : r.text.slice(0, 260)}`, r.status === 200);
for (const mode of ["TEST", "LIVE"]) { r = await wa.s.put(`${PB}/data-bindings/${mode}/erp-db`, { dataSourceId: ds1 }); rec(`D02-${mode}`, "data", `bind slot erp-db → source (${mode})`, "200 {mode}", `${st(r)} ${r.json?.mode}`, r.status === 200 && r.json?.mode === mode); }
r = await wa.s.put(`${PB}/data-bindings/test/erp-db`, { dataSourceId: ds1 }); rec("D03", "data", "lower-case mode refused", "400 INVALID_PARAMS", st(r), r.status === 400 && r.json?.code === "INVALID_PARAMS");
r = await ta2.s.put(`/api/v1/workspaces/${W2}/projects/${P}/data-bindings/LIVE/erp-db`, { dataSourceId: ds1 }); rec("D04", "data-iso", "foreign tenant binds A's source to a project id of A", "404", st(r), r.status === 404);
const RQ = `${PB}/app-runtime/queries/orders-list/run`;
r = await wa.s.post(RQ, { mode: "TEST", params: { status: "open" } }); const rowsTest = r.json?.result?.rows ?? [];
rec("D05", "data", "TEST run: Studio → app-runtime → DataGateway → TLS PostgreSQL → rows, filtered by the parameter, mapped to name/description", "200; SO-1001 + SO-1002, no SO-1003; fields name/description", `${st(r)} rows=${rowsTest.length} first=${JSON.stringify(rowsTest[0])?.slice(0, 120)}`, r.status === 200 && /SO-1001/.test(r.text) && /SO-1002/.test(r.text) && !/SO-1003/.test(r.text) && /description/.test(r.text));
r = await wa.s.post(RQ, { mode: "TEST", params: { status: "closed" } }); rec("D06", "data", "TEST run with another parameter value", "200; only SO-1003", st(r), r.status === 200 && /SO-1003/.test(r.text) && !/SO-1001/.test(r.text));
r = await wa.s.post(RQ, { params: { status: "open" } }); rec("D07", "data", "LIVE run before anything is published (no release)", "404 (nothing published; no fallback to the draft)", st(r), r.status === 404);
r = await wa.s.post(RQ, { mode: "TEST", params: { status: 5 } }); rec("D08a", "data-failure", "wrong parameter type", "422 INVALID_PARAMS (or 400), no rows", st(r), [400, 422].includes(r.status) && !/SO-100/.test(r.text));
r = await wa.s.post(RQ, { mode: "TEST", params: { status: "open", sql: "DROP TABLE x" } }); rec("D08b", "data-failure", "client-supplied sql/unknown field", "400 INVALID_REQUEST (strict parsing)", st(r), r.status === 400);
r = await wa.s.post(`${PB}/app-runtime/queries/nope/run`, { mode: "TEST", params: {} }); rec("D08c", "data-failure", "unknown query id", "404 QUERY_NOT_FOUND", st(r), r.status === 404);
r = await ed.s.post(RQ, { mode: "TEST", params: { status: "open" } }); rec("D09", "data-authz", "EDITOR of the workspace (not a member of the project) runs the query", "404 (not visible) or 403", st(r), [403, 404].includes(r.status));
r = await ta2.s.post(`/api/v1/workspaces/${W2}/projects/${P}/app-runtime/queries/orders-list/run`, { mode: "TEST", params: { status: "open" } }); rec("D10", "data-iso", "foreign tenant runs A's query", "404", st(r), r.status === 404);
// publish → LIVE
const rev0 = (await wa.s.get(PB)).json?.revision;
r = await wa.s.post(`${PB}/publish`, { visibility: "PUBLIC", expectedRevision: rev0 }, { "idempotency-key": "c6-data-" + randomUUID() }); const dep = r.json?.id; rec("D11", "publish", "publish the data app", "202 + deployment id", st(r), [200, 201, 202].includes(r.status) && !!dep);
const fin = await poll(async () => (await wa.s.get(`${PB}/deployments/${dep}`)).json?.status, (s) => ["RUNNING", "FAILED"].includes(s), 90); rec("D12", "publish", "deployment reaches RUNNING (the only success)", "RUNNING", fin, fin === "RUNNING");
r = await wa.s.post(RQ, { params: { status: "open" } }); rec("D13", "data", "LIVE run = the ACTIVE release, over TLS", "200 mode LIVE with SO-1001", `${st(r)} mode=${r.json?.mode}`, r.status === 200 && r.json?.mode === "LIVE" && /SO-1001/.test(r.text));
const site = (await wa.s.get(`${PB}/site`)).json; const siteUrl = site?.url;
// failure states: disable the source, rotate credential to a wrong one, unbind
r = await wa.s.patch(`${DS}/${ds1}`, { status: "DISABLED" }); const r1 = await wa.s.post(RQ, { params: { status: "open" } });
rec("D14", "data-failure", "source DISABLED → query fails closed, no data", "PATCH 200; run ≥ 400 and no rows", `patch ${r.status}; run ${st(r1)}`, r.status === 200 && r1.status >= 400 && !/SO-100/.test(r1.text));
await wa.s.patch(`${DS}/${ds1}`, { status: "ACTIVE" }); r = await wa.s.post(RQ, { params: { status: "open" } }); rec("D15", "data-failure", "re-enabled source works again (no sticky failure)", "200", st(r), r.status === 200);
await wa.s.put(`${DS}/${ds1}/credential`, { credential: { username: "shop_ro", password: "rotated-wrong-" + tag } }); await sleep(500); const rb = await wa.s.post(RQ, { params: { status: "open" } }); const rtt = await wa.s.post(`${DS}/${ds1}/test`, {});
rec("D16", "data-failure", "credential replaced by a wrong one → LIVE fails closed with a safe error (never stale rows, never a secret)", "run ≥ 400 without rows or the old password", `run ${st(rb)}; test ok=${rtt.json?.ok} ${rtt.json?.code ?? ""}`, rb.status >= 400 && !/SO-100/.test(rb.text) && rtt.json?.ok === false, "result cache may serve a hit within its ttl: a 200 here would be reported");
await wa.s.put(`${DS}/${ds1}/credential`, { credential: { username: "shop_ro", password: RO } }); r = await wa.s.post(`${DS}/${ds1}/test`, {}); rec("D17", "data-failure", "credential restored → connection test ok again", "ok:true", `${st(r)} ok=${r.json?.ok}`, r.json?.ok === true);
r = await wa.s.del(`${PB}/data-bindings/TEST/erp-db`); const ru = await wa.s.post(RQ, { mode: "TEST", params: { status: "open" } }); rec("D18", "data-failure", "TEST binding removed → run answers DATA_SOURCE_UNBOUND (never falls back to LIVE)", "422 DATA_SOURCE_UNBOUND", `unbind ${r.status}; run ${st(ru)}`, r.status === 204 && ru.status === 422 && ru.json?.code === "DATA_SOURCE_UNBOUND");
await wa.s.put(`${PB}/data-bindings/TEST/erp-db`, { dataSourceId: ds1 });
r = await wa.s.del(`${DS}/${ds1}`); rec("D19", "data", "delete a source that is still bound", "409 CONFLICT", st(r), r.status === 409);
// timeout: a slow approved query
r = await wa.s.post(Q, { queryId: "slow.sleep", kind: "SQL", definition: { sql: "SELECT pg_sleep(20) AS s", params: [], maxRows: 1 } });
if (r.status === 201) { r = await wa.s.patch(`${PB}/schema`, { expectedRevision: (await wa.s.get(PB)).json?.revision, operations: [{ type: "ADD_QUERY", definition: { id: "slow", name: "Slow", dataSourceRef: "erp-db", mode: "READ", operationKey: "slow.sleep", params: [], maxRows: 1 } }, { type: "ADD_MAPPING", definition: { id: "slow-map", queryRef: "slow", fields: [{ from: "s", to: "name" }] } }, { type: "ADD_VIEW_MODEL", definition: { id: "slow-vm", name: "Slow", queryRef: "slow", mappingRef: "slow-map", cardinality: "LIST", fields: [{ name: "name", type: "STRING" }] } }], summary: "c6 slow" });
  const t0 = Date.now(); const rs = await wa.s.post(`${PB}/app-runtime/queries/slow/run`, { mode: "TEST", params: {} }); const dt = Date.now() - t0;
  rec("D20", "data-failure", "a query that outlives the gateway timeout", "answered with a bounded error (504/502/TIMEOUT) well before 20 s, or the pg_sleep call is refused by the SQL guard (400 INVALID_QUERY)", `schema ${r.status}; run ${st(rs)} after ${dt} ms`, (rs.status >= 400 && dt < 19000) || rs.status === 200 && dt >= 19000 === false, rs.status === 200 ? "completed: no timeout enforced below 20 s" : "");
} else rec("D20", "data-failure", "a slow query definition", "400 INVALID_QUERY (guard) or 201", st(r), r.status === 400 && r.json?.code === "INVALID_QUERY", "pg_sleep refused by the SQL guard");

// ================================================================= MUTATION + ACTION (real side effect on the writable source)
const dsW = rwId; const WB = `${DS}/${dsW}`;
r = await wa.s.post(`${WB}/mutations`, { mutationId: "orders.create", kind: "CREATE", definition: { target: "shop.orders", params: [{ name: "order_no", type: "STRING", required: true }, { name: "customer_id", type: "INTEGER", required: true }, { name: "status", type: "STRING", required: true }, { name: "amount", type: "NUMBER", required: true }], invalidates: [], entity: "order" } });
rec("A00", "action", "mutation definition CREATE on the writable source", "201", `${st(r)} ${r.status === 201 ? "" : r.text.slice(0, 200)}`, r.status === 201);
const mutOk = r.status === 201;
const roMut = await wa.s.post(`${DS}/${ds1}/mutations`, { mutationId: "orders.create", kind: "CREATE", definition: { target: "shop.orders", params: [{ name: "order_no", type: "STRING", required: true }] } }); rec("A01", "action", "mutation definition on a source NOT declared writable", "422 READ_ONLY_VIOLATION", st(roMut), roMut.status === 422 && roMut.json?.code === "READ_ONLY_VIOLATION");
const orderNo = `C6-${tag.toUpperCase()}`;
r = await wa.s.patch(`${PB}/schema`, { expectedRevision: (await wa.s.get(PB)).json?.revision, summary: "c6 action", operations: [
  { type: "ADD_DATA_SOURCE", definition: { id: "erp-rw", name: "Shop RW", type: "postgres" } },
  { type: "ADD_QUERY", definition: { id: "orders-create", name: "Create order", dataSourceRef: "erp-rw", mode: "WRITE", operationKey: "orders.create", params: [{ name: "order_no", type: "STRING" }, { name: "customer_id", type: "INTEGER" }, { name: "status", type: "STRING" }, { name: "amount", type: "NUMBER" }] } },
  { type: "ADD_ACTION", definition: { id: "create-order", name: "Create order", type: "CREATE_RECORD", queryRef: "orders-create", trigger: { sectionId, event: "onSubmit" }, inputs: [{ name: "order_no", type: "STRING", required: true }, { name: "customer_id", type: "INTEGER", required: true }, { name: "status", type: "STRING", required: true }, { name: "amount", type: "NUMBER", required: true }], inputMapping: { order_no: { source: "LITERAL", value: "x" } } } },
] });
const actionSchema = r; rec("A02", "action", "declare the WRITE query + CREATE_RECORD action (typed operations; canonical ActionType)", "200", `${st(r)} ${r.status === 200 ? "" : r.text.slice(0, 260)}`, r.status === 200);
for (const mode of ["TEST", "LIVE"]) await wa.s.put(`${PB}/data-bindings/${mode}/erp-rw`, { dataSourceId: dsW });
const AX = `${PB}/app-runtime/actions/create-order/execute`; const inputs = { order_no: orderNo, customer_id: 1, status: "open", amount: 9.99 };
const countRows = async () => { const x = await wa.s.post(RQ, { mode: "TEST", params: { status: "open" } }); return (x.json?.result?.rows ?? []).filter((q) => q.name === orderNo).length; };
const before = await countRows();
r = await wa.s.post(AX, { mode: "TEST", inputs, trigger: { eventName: `${sectionId}.onSubmit` } }); const after1 = await countRows();
rec("A03", "action", "TEST mode: WOULD_RUN, no side effect", "200 WOULD_RUN; row count unchanged", `${st(r)} rows ${before}→${after1}`, r.status === 200 && r.json?.status === "WOULD_RUN" && after1 === before);
// publish the version that carries the action (LIVE = the active release)
const rev1 = (await wa.s.get(PB)).json?.revision; const pub2 = await wa.s.post(`${PB}/publish`, { visibility: "PUBLIC", expectedRevision: rev1 }, { "idempotency-key": "c6-data2-" + randomUUID() }); const dep2 = pub2.json?.id;
const fin2 = await poll(async () => (await wa.s.get(`${PB}/deployments/${dep2}`)).json?.status, (s) => ["RUNNING", "FAILED"].includes(s), 90); rec("A05", "publish", "publish the release that carries the action", "RUNNING", fin2, fin2 === "RUNNING");
r = await wa.s.post(AX, { mode: "LIVE", inputs, trigger: { eventName: `${sectionId}.onSubmit` } }); rec("A04", "action", "LIVE mutating action without an idempotency key", "400 IDEMPOTENCY_KEY_REQUIRED, nothing written", `${st(r)}`, r.status === 400 && /IDEMPOTENCY_KEY_REQUIRED/.test(r.text));
const key = "c6-act-" + randomUUID();
r = await wa.s.post(AX, { mode: "LIVE", inputs, idempotencyKey: key, trigger: { eventName: `${sectionId}.onSubmit` } }); const first = r;
await sleep(600); const liveRows = await wa.s.post(RQ, { params: { status: "open" } }); const present = (liveRows.json?.result?.rows ?? []).filter((q) => q.name === orderNo).length;
rec("A06", "action", "LIVE action with a key: a REAL row is written to the source and read back through the READ query", "200 OK; exactly 1 row C6-…", `${st(r)}; rows found=${present} (LIVE read may be cached)`, r.status === 200 && r.json?.status === "OK", present === 0 ? "READ result cache (ttl) may hide the row: verified again below by a TEST read" : "");
const trow = await countRows(); rec("A06b", "action", "the same row seen by an uncached TEST read", "exactly 1", trow, trow === 1);
r = await wa.s.post(AX, { mode: "LIVE", inputs, idempotencyKey: key, trigger: { eventName: `${sectionId}.onSubmit` } }); const trow2 = await countRows();
rec("A07", "action", "duplicate submit with the SAME key (replay)", "200 same outcome, still exactly 1 row", `${st(r)}; rows=${trow2}; same status=${r.json?.status === first.json?.status}`, r.status === 200 && trow2 === 1);
r = await wa.s.post(AX, { mode: "LIVE", inputs: { ...inputs, amount: 1.5 }, idempotencyKey: key, trigger: { eventName: `${sectionId}.onSubmit` } }); const trow3 = await countRows();
rec("A08", "action", "the SAME key with DIFFERENT inputs", "409 (key reused) and no second row", `${st(r)}; rows=${trow3}`, r.status === 409 && trow3 === 1);
const dupConcurrent = await Promise.all([1, 2, 3].map(() => wa.s.post(AX, { mode: "LIVE", inputs: { ...inputs, order_no: orderNo + "-P" }, idempotencyKey: "c6-par-" + key, trigger: { eventName: `${sectionId}.onSubmit` } })));
const parRows = (await wa.s.post(RQ, { mode: "TEST", params: { status: "open" } })).json?.result?.rows?.filter((q) => q.name === orderNo + "-P").length;
rec("A09", "action", "3 concurrent submits with one key (double click)", "one row; no 500", `${dupConcurrent.map((x) => x.status).join("/")}; rows=${parRows}`, parRows === 1 && dupConcurrent.every((x) => x.status < 500));
r = await ed.s.post(AX, { mode: "LIVE", inputs, idempotencyKey: "c6-ed-" + randomUUID(), trigger: { eventName: `${sectionId}.onSubmit` } }); rec("A10", "action-authz", "EDITOR of the workspace without project membership executes", "403/404", st(r), [403, 404].includes(r.status));
await wa.s.post(`${PB}/members`, { username: ed.name, role: "EDITOR" }); r = await ed.s.post(AX, { mode: "LIVE", inputs, idempotencyKey: "c6-ed2-" + randomUUID(), trigger: { eventName: `${sectionId}.onSubmit` } }); const edRows = await countRows();
rec("A11", "action-authz", "project EDITOR (APP_USE, no DATA_MUTATE) executes a LIVE write action", "403 FORBIDDEN, nothing written", `${st(r)}; rows=${edRows}`, r.status === 403 && edRows === 1);
r = await ta2.s.post(`/api/v1/workspaces/${W2}/projects/${P}/app-runtime/actions/create-order/execute`, { mode: "LIVE", inputs, idempotencyKey: "c6-f-" + randomUUID() }); rec("A12", "action-iso", "foreign tenant executes A's action", "404", st(r), r.status === 404);
r = await wa.s.post(AX, { mode: "LIVE", inputs: { ...inputs, order_no: orderNo + "-X", sql: "DROP TABLE shop.orders" }, idempotencyKey: "c6-sql-" + randomUUID(), trigger: { eventName: `${sectionId}.onSubmit` } }); rec("A13", "action-safety", "client-supplied sql / unknown field in action inputs", "400 INVALID_REQUEST/INVALID_INPUT, nothing executed", st(r), r.status === 400);
const jsOp = await wa.s.patch(`${PB}/schema`, { expectedRevision: (await wa.s.get(PB)).json?.revision, summary: "c6 forbidden", operations: [{ type: "ADD_ACTION", definition: { id: "js-action", name: "js", type: "CALL_API", script: "fetch('http://evil')", endpointRef: "x", trigger: { sectionId, event: "onSubmit" } } }] });
rec("A14", "action-safety", "no arbitrary JS / script in an action definition", "rejected: 422 SCHEMA_INVALID (unknown field) or 400", st(jsOp) + " " + JSON.stringify(jsOp.json?.details?.violations ?? []).slice(0, 120), [400, 422].includes(jsOp.status));
const urlOp = await wa.s.patch(`${PB}/schema`, { expectedRevision: (await wa.s.get(PB)).json?.revision, summary: "c6 forbidden url", operations: [{ type: "ADD_ACTION", definition: { id: "url-action", name: "u", type: "CALL_API", url: "http://169.254.169.254/", trigger: { sectionId, event: "onSubmit" } } }] });
rec("A15", "action-safety", "no raw URL in an action definition (SSRF)", "rejected: 422 SCHEMA_INVALID (unknown field) or 400", st(urlOp) + " " + JSON.stringify(urlOp.json?.details?.violations ?? []).slice(0, 120), [400, 422].includes(urlOp.status));
const legacyOp = await wa.s.patch(`${PB}/schema`, { expectedRevision: (await wa.s.get(PB)).json?.revision, summary: "c6 legacy type", operations: [{ type: "ADD_ACTION", definition: { id: "legacy-act", name: "l", type: "WRITE_DATA", queryRef: "orders-create", trigger: { sectionId, event: "onSubmit" } } }] });
rec("A16", "action-types", "non-canonical ActionType (WRITE_DATA) is rejected", "rejected: 422 SCHEMA_INVALID listing the canonical types, or 400", st(legacyOp) + " " + JSON.stringify(legacyOp.json?.details?.violations ?? []).slice(0, 140), [400, 422].includes(legacyOp.status));

// ================================================================= WORKFLOW
const wfSchema = await wa.s.patch(`${PB}/schema`, { expectedRevision: (await wa.s.get(PB)).json?.revision, summary: "c6 workflows", operations: [
  { type: "ADD_WORKFLOW_REF", definition: { id: "wf-end", name: "End only", trigger: "MANUAL", steps: [{ id: "end", kind: "END" }] } },
  { type: "ADD_WORKFLOW_REF", definition: { id: "wf-wait", name: "Wait", trigger: "MANUAL", startStepId: "park", steps: [{ id: "park", kind: "WAIT", waitSeconds: 600, next: "end" }, { id: "end", kind: "END" }] } },
] });
rec("W00", "workflow", "declare two workflows (END only; WAIT 600 s then END) through typed operations", "200", `${st(wfSchema)} ${wfSchema.status === 200 ? "" : wfSchema.text.slice(0, 240)}`, wfSchema.status === 200);
const WFX = (id) => `${PB}/app-runtime/workflows/${id}/runs`;
r = await wa.s.post(WFX("wf-end"), { mode: "TEST", input: {}, idempotencyKey: "c6-wft-" + tag }); const runTest = r.json?.runId ?? r.json?.id; rec("W01", "workflow", "TEST start creates a run (documented: a TEST workflow still creates a run row)", "200/202 with a run id", `${st(r)} id=${runTest}`, [200, 201, 202].includes(r.status) && !!runTest);
const pubRev = (await wa.s.get(PB)).json?.revision; const pub3 = await wa.s.post(`${PB}/publish`, { visibility: "PUBLIC", expectedRevision: pubRev }, { "idempotency-key": "c6-data3-" + randomUUID() }); const fin3 = await poll(async () => (await wa.s.get(`${PB}/deployments/${pub3.json?.id}`)).json?.status, (x) => ["RUNNING", "FAILED"].includes(x), 90); rec("W01b", "publish", "publish the release that carries the workflows", "RUNNING", fin3, fin3 === "RUNNING");
r = await wa.s.post(WFX("wf-end"), { input: {}, idempotencyKey: "c6-wf-" + tag }); const runLive = r.json?.runId ?? r.json?.id; rec("W02", "workflow", "LIVE start of an END-only workflow", "200/202 with a run id", `${st(r)} id=${runLive}`, [200, 201, 202].includes(r.status) && !!runLive);
const same = await wa.s.post(WFX("wf-end"), { input: {}, idempotencyKey: "c6-wf-" + tag }); rec("W03", "workflow", "replay with the same key returns the SAME run (no second run)", "same run id", `${st(same)} id=${same.json?.runId ?? same.json?.id}`, (same.json?.runId ?? same.json?.id) === runLive);
const runSt = await poll(async () => (await wa.s.get(`${PB}/app-runtime/workflow-runs/${runLive}`)).json, (j) => ["SUCCEEDED", "FAILED", "CANCELLED"].includes(j?.status), 40); rec("W04", "workflow", "status: the run completes and shows its steps", "SUCCEEDED with steps", `${runSt?.status} steps=${JSON.stringify((runSt?.steps ?? []).map((s) => s.stepId ?? s.id)).slice(0, 80)}`, runSt?.status === "SUCCEEDED");
r = await wa.s.post(WFX("wf-wait"), { input: {}, idempotencyKey: "c6-wfw-" + tag }); const waitRun = r.json?.runId ?? r.json?.id; const waiting = await poll(async () => (await wa.s.get(`${PB}/app-runtime/workflow-runs/${waitRun}`)).json, (j) => ["WAITING", "RUNNING", "SUCCEEDED", "FAILED"].includes(j?.status) && j?.status !== "RUNNING", 20);
rec("W05", "workflow", "a WAIT step parks the run", "WAITING (not SUCCEEDED, not failed)", `${st(r)}; ${waiting?.status}`, waiting?.status === "WAITING");
r = await wa.s.post(`${PB}/app-runtime/workflow-runs/${waitRun}/cancel`, {}); const cancelled = await poll(async () => (await wa.s.get(`${PB}/app-runtime/workflow-runs/${waitRun}`)).json, (j) => j?.status === "CANCELLED", 15);
rec("W06", "workflow", "cancel a WAITING run", "200 and CANCELLED", `${st(r)}; ${cancelled?.status}`, [200, 202].includes(r.status) && cancelled?.status === "CANCELLED");
r = await wa.s.post(`${PB}/app-runtime/workflow-runs/${waitRun}/cancel`, {}); rec("W07", "workflow", "cancel again (idempotent / terminal)", "200 or 409, never 500", st(r), [200, 202, 409].includes(r.status));
r = await ta2.s.get(`/api/v1/workspaces/${W2}/projects/${P}/app-runtime/workflow-runs/${runLive}`); rec("W08", "workflow-iso", "foreign tenant reads A's run", "404", st(r), r.status === 404);
r = await vw.s.post(WFX("wf-end"), { input: {}, idempotencyKey: "c6-vw-" + tag }); rec("W09", "workflow-authz", "VIEWER (workspace role, no project membership) starts a workflow", "403/404", st(r), [403, 404].includes(r.status));
r = await ed.s.post(WFX("wf-end"), { input: {}, idempotencyKey: "c6-ed-wf-" + tag }); rec("W10", "workflow-authz", "project EDITOR (no WORKFLOW_EXECUTE) starts a LIVE workflow", "403", st(r), r.status === 403);
r = await wa.s.post(WFX("no-such"), { input: {}, idempotencyKey: "c6-nx-" + tag }); rec("W11", "workflow", "unknown workflow id", "404", st(r), r.status === 404);

// ================================================================= audit visibility (system admin) + secret scan
r = await sa.get("/api/v1/admin/audit?size=200"); const audit = JSON.stringify(r.json ?? ""); const acts = ["DATASOURCE_CREATED", "DATASOURCE_CREDENTIAL_ROTATED", "DATASOURCE_TESTED", "DATA_QUERY_DEFINITION_CHANGED", "DATASOURCE_BINDING_CHANGED"];
rec("X01", "audit", "audit shows the management actions (append-only log, system admin)", "200 with " + acts.join(", "), `${st(r)}; found=${acts.filter((a) => audit.includes(a)).length}/${acts.length}`, r.status === 200 && acts.filter((a) => audit.includes(a)).length >= 4);
rec("X02", "audit", "no target password in the audit payloads", "none", audit.includes(RO) || audit.includes(RW) ? "LEAK" : "none", !(audit.includes(RO) || audit.includes(RW)));
rec("X03", "mgmt-secret", "no response of the whole run contained the target passwords", "none", leaked ? "LEAK" : "none", !leaked);
// facts for the real-backend suite (E2E-07 pre-seeded variant): a mode-600 file outside the repo
if (process.env.FACTS_FILE) writeFileSync(process.env.FACTS_FILE, `E2E_DATA_WORKSPACE_ID=${W1}\nE2E_DATA_PROJECT_ID=${P}\nE2E_DATA_QUERY_ID=items-list\nE2E_DATA_USER=${wa.name}\nE2E_DATA_PASSWORD=${wa.p}\nE2E_PUBLIC_BASE=${siteUrl ? new URL(siteUrl).origin + "/" + new URL(siteUrl).pathname.split("/")[1] : ""}\n`, { mode: 0o600 });
} catch (e) { rec("ERR", "script", "unexpected exception", "none", String(e?.stack ?? e).slice(0, 400), false); }
const failed = rows.filter((x) => x.result === "FAIL");
writeFileSync(`${OUT}/rc-data.json`, JSON.stringify({ api: API, run: tag, total: rows.length, failed: failed.length, rows }, null, 1));
writeFileSync(`${OUT}/rc-data.tsv`, ["id\tarea\tdescription\texpected\tactual\tresult\tnote", ...rows.map((x) => [x.id, x.area, x.desc, x.expected, x.actual, x.result, x.note].join("\t"))].join("\n") + "\n");
console.log(`\nTOTAL ${rows.length}  FAILED ${failed.length}${failed.length ? "  -> " + failed.map((x) => x.id).join(", ") : ""}`);
process.exit(failed.length ? 1 : 0);
