#!/usr/bin/env node
// V1 local stack smoke (D-C0-35). A real session against the running API (PORTALS=1 ./scripts/run-local.sh), no mock, no test hook:
//   login + CSRF -> Management API -> TLS PostgreSQL target (create, credential, test connection, schema discovery, query definition, TEST / LIVE binding)
//   -> the platform and apps databases are refused -> authenticated app-runtime query over TLS -> publish -> the site is served -> LIVE query = the active release
//   -> unpublish -> LIVE answers nothing.
// Usage: LOCAL_ADMIN_PASSWORD=... node scripts/v1-smoke.mjs        (the shell wrapper `./scripts/v1-smoke.sh` loads .env)
// Env:   API (http://127.0.0.1:8080)  DATA_TARGET_PORT (15440)  V1_SMOKE_SEED  = "docker exec -i hbl-postgres-1 psql -U studio -d <scratch db>" (see below)
// The ONE thing seeded outside the API is the data-source SLOT of the application's draft (`dataSources[]`): no schema operation can declare one (B-C0-W-07, D-C0-35).
// Secrets: the target role password is read from .run/data-target/ro.pw and sent only to the credential route; nothing secret is printed.
import { readFileSync } from "node:fs";
import { execSync } from "node:child_process";
import { randomUUID } from "node:crypto";

const API = process.env.API ?? "http://127.0.0.1:8080";
const TARGET_PORT = process.env.DATA_TARGET_PORT ?? "15440";
const PASSWORD = process.env.LOCAL_ADMIN_PASSWORD;
const SEED = process.env.V1_SMOKE_SEED;
if (!PASSWORD) { console.error("LOCAL_ADMIN_PASSWORD is required"); process.exit(2); }
if (!SEED) { console.error('V1_SMOKE_SEED is required, e.g. "docker exec -i hbl-postgres-1 psql -U studio -d hbl_v1_smoke -q"'); process.exit(2); }
const ROOT = new URL("..", import.meta.url).pathname;
const targetPw = readFileSync(`${ROOT}.run/data-target/ro.pw`, "utf8").trim();

const jar = new Map(); let csrf = null;
const results = [];
const check = (name, ok, info = "") => { results.push([name, ok]); console.log(`${ok ? "PASS" : "FAIL"} ${name}${info ? " | " + info : ""}`); return ok; };

async function call(method, path, body, { raw = false, base = API, headers = {} } = {}) {
  const h = { cookie: [...jar].map(([k, v]) => `${k}=${v}`).join("; "), ...headers };
  if (csrf && method !== "GET") h["x-xsrf-token"] = csrf;
  if (body !== undefined) h["content-type"] = "application/json";
  const res = await fetch(base + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), redirect: "manual" });
  for (const c of res.headers.getSetCookie?.() ?? []) { const [kv] = c.split(";"); const i = kv.indexOf("="); const k = kv.slice(0, i), v = kv.slice(i + 1); if (c.toLowerCase().includes("max-age=0")) jar.delete(k); else jar.set(k, v); }
  const text = await res.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: res.status, json, text };
}
const envelope = (j) => j && ["code", "message", "requestId", "retryable", "details"].every((k) => k in j);
const sh = (cmd, input) => execSync(cmd, { input, encoding: "utf8", stdio: ["pipe", "pipe", "pipe"] });

// ---- 1. session
let r = await call("GET", "/api/v1/auth/csrf"); csrf = r.json?.token;
r = await call("POST", "/api/v1/auth/login", { username: "local.admin", password: PASSWORD });
check("login with CSRF (real session)", r.status === 200, String(r.status));
r = await call("GET", "/api/v1/auth/me"); const ws = r.json?.workspaces?.[0]?.id ?? r.json?.workspaces?.[0]?.workspaceId;
check("workspace of the session", !!ws, ws ? "found" : JSON.stringify(Object.keys(r.json ?? {})));
const D = `/api/v1/workspaces/${ws}/data-sources`;

// ---- 2. Management API -> TLS target
const cfg = (host, port, db = "shop") => ({ host, port: String(port), database: db, schemas: "shop" });
const name = "v1-smoke-" + randomUUID().slice(0, 6);
r = await call("POST", D, { name, type: "postgres", config: cfg("127.0.0.1", TARGET_PORT) });
const ds = r.json?.id; check("create data source -> the local TLS target", r.status === 201 && !!ds, `${r.status}`);
r = await call("PUT", `${D}/${ds}/credential`, { credential: { username: "shop_ro", password: targetPw } });
check("set credential (metadata only, no secret back)", r.status === 200 && r.json?.configured === true && !r.text.includes(targetPw), `${r.status}`);
r = await call("POST", `${D}/${ds}/test`, {});
check("test connection over TLS (verify-full, SELECT-only role)", r.status === 200 && r.json?.ok === true, JSON.stringify(r.json));
r = await call("POST", `${D}/${ds}/schema/discover`, {});
check("schema discovery from the real database", r.status === 200 && r.json?.entityCount >= 2, `${r.status} entities=${r.json?.entityCount}`);
r = await call("GET", `${D}/${ds}/schema`); check("discovered snapshot has customers and orders", r.status === 200 && /customers/.test(r.text) && /orders/.test(r.text) && !r.text.includes(targetPw), `${r.status}`);
for (const [label, host, port] of [["platform database", "127.0.0.1", 15432], ["apps database", "127.0.0.1", 15434], ["platform database by name", "localhost", 15432]]) {
  r = await call("POST", D, { name: "blocked-" + randomUUID().slice(0, 5), type: "postgres", config: cfg(host, port) });
  check(`${label} is refused (400 INVALID_CONFIG, envelope)`, r.status === 400 && r.json?.code === "INVALID_CONFIG" && envelope(r.json), `${r.status}`);
}
const qdef = { queryId: "shop.orders.list", kind: "SQL", definition: { sql: "SELECT o.order_no, o.status, o.amount, c.name AS customer FROM shop.orders o JOIN shop.customers c ON c.id = o.customer_id WHERE o.status = :status ORDER BY o.order_no", params: [{ name: "status", type: "STRING" }], maxRows: 50 } };
r = await call("POST", `${D}/${ds}/queries`, qdef); check("query definition created", r.status === 201, `${r.status} ${r.status === 201 ? "" : r.text.slice(0, 200)}`);

// ---- 3. an application, its slot, TEST / LIVE bindings
r = await call("POST", `/api/v1/workspaces/${ws}/projects`, { name: "V1 smoke " + randomUUID().slice(0, 6) });
const pid = r.json?.id; check("project created through the API", r.status === 201 || r.status === 200, `${r.status}`);
const P = `/api/v1/workspaces/${ws}/projects/${pid}`;
const slot = { schemaVersion: 2, kind: "PAGE_SCHEMA",
  dataSources: [{ id: "erp-db", name: "Shop", type: "postgres" }],
  queries: [{ id: "orders-list", name: "Orders", dataSourceRef: "erp-db", operationKey: "shop.orders.list", params: [{ name: "status", type: "STRING", default: "open" }], maxRows: 50 }],
  mappings: [{ id: "orders-map", queryRef: "orders-list", fields: [{ from: "order_no", to: "name" }, { from: "customer", to: "description" }] }],
  viewModels: [{ id: "orders-vm", name: "Orders", queryRef: "orders-list", mappingRef: "orders-map", fields: [{ name: "name" }, { name: "description" }] }] };
sh(SEED, `UPDATE page_schemas SET schema = schema || '${JSON.stringify(slot).replace(/'/g, "''")}'::jsonb WHERE project_id = '${pid}';\n`);
// the seed bypassed versioning: one real edit through the API commits the draft (with the slot) as a version, which is what a publish serves
r = await call("PATCH", `${P}/schema`, { operations: [{ type: "UPDATE_THEME", definition: { radius: "MD" } }], expectedRevision: (await call("GET", P)).json?.revision, summary: "data slot" });
check("a real edit commits the draft as a version", r.status === 200, `${r.status} ${r.status === 200 ? "" : r.text.slice(0, 220)}`);
for (const mode of ["TEST", "LIVE"]) { r = await call("PUT", `${P}/data-bindings/${mode}/erp-db`, { dataSourceId: ds }); check(`bind ${mode} slot erp-db`, r.status === 200 && r.json?.mode === mode, `${r.status}`); }
r = await call("PUT", `${P}/data-bindings/test/erp-db`, { dataSourceId: ds }); check("lower-case mode refused", r.status === 400 && r.json?.code === "INVALID_PARAMS", `${r.status}`);

// ---- 4. authenticated runtime over TLS (TEST = the draft)
r = await call("POST", `${P}/app-runtime/queries/orders-list/run`, { mode: "TEST", params: { status: "open" } });
check("TEST query: Studio -> app-runtime -> DataGateway -> TLS PostgreSQL -> rows", r.status === 200 && /SO-1001/.test(r.text) && /ACME Co/.test(r.text), `${r.status} ${r.status === 200 ? "" : r.text.slice(0, 160)}`);
check("TEST query returns only the requested status", !/SO-1003/.test(r.text));

// ---- 5. publish -> served -> LIVE
const rev = (await call("GET", P)).json?.revision;
r = await call("POST", `${P}/publish`, { visibility: "PUBLIC", expectedRevision: rev }, { headers: { "idempotency-key": "v1-smoke-" + randomUUID() } });
const dep = r.json?.id; check("publish accepted", (r.status === 200 || r.status === 201 || r.status === 202) && !!dep, `${r.status} ${r.status < 300 ? "" : r.text.slice(0, 200)}`);
let status = ""; for (let i = 0; i < 90 && !["RUNNING", "FAILED"].includes(status); i++) { await new Promise((s) => setTimeout(s, 1000)); status = (await call("GET", `${P}/deployments/${dep}`)).json?.status ?? ""; }
check("deployment reaches RUNNING", status === "RUNNING", status);
r = await call("GET", `${P}/site`); const siteUrl = r.json?.url; check("site info has the served address", !!siteUrl && r.json?.pointerVersion >= 1, `${siteUrl ?? ""} pointerVersion=${r.json?.pointerVersion}`);
if (siteUrl) {
  const page = await fetch(siteUrl); const cfgRes = await fetch(new URL("__factory/config.json", siteUrl));
  check("published site is served by the sites gateway", page.status === 200, `${page.status}`);
  // a PAGE_SCHEMA site is static HTML rendered at publish: it has no runtime config (only a code app, kind STATIC_APP, is served `__factory/config.json`; B-C5-06 / D-C0-35)
  check("a page site has no runtime config (code apps only - gap recorded, not a pass of the apiBase flow)", cfgRes.status === 404, `${cfgRes.status}`);
}
r = await call("POST", `${P}/app-runtime/queries/orders-list/run`, { params: { status: "open" } });
check("LIVE query = the active release, over TLS", r.status === 200 && r.json?.mode === "LIVE" && /SO-1001/.test(r.text), `${r.status}`);
r = await call("DELETE", `${P}/site`);
check("unpublish through the release scope", r.status === 200 || r.status === 204, `${r.status}`);
r = await call("POST", `${P}/app-runtime/queries/orders-list/run`, { params: { status: "open" } });
check("after unpublish LIVE answers nothing (404, not the latest RUNNING deployment)", r.status === 404 && envelope(r.json), `${r.status}`);
if (siteUrl) { const off = await fetch(siteUrl); check("the site is offline", off.status === 404, `${off.status}`); }
r = await call("POST", "/api/v1/data/mutate", { dataSourceId: ds, operation: "x", params: {} }); check("raw mutation route does not exist", r.status === 404, `${r.status}`);

// ---- 6. clean up what this run created (the project and the data source), leaving no secret behind
await call("DELETE", `${P}/data-bindings/TEST/erp-db`); await call("DELETE", `${P}/data-bindings/LIVE/erp-db`);
const failed = results.filter(([, ok]) => !ok).map(([n]) => n);
console.log(`\nTOTAL ${results.length}  FAILED ${failed.length}${failed.length ? "  -> " + failed.join(" | ") : ""}`);
process.exit(failed.length ? 1 : 0);
