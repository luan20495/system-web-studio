#!/usr/bin/env node
// V1 PUBLIC smoke (D-C0-37): the first real public path, no seeded database edit, no test double, a real browser with NO session.
//   author (authenticated API): declare the data-source slot, data source on the TLS target, credential, query definitions, bind TEST + LIVE, queries `public`, data bindings,
//   acknowledge public data, publish a PAGE_SCHEMA page
//   visitor (Chrome, empty profile): page -> same-origin __factory/config.json -> apiBase -> /{slug}/_data -> PUBLIC_SITE -> LIVE query -> real TLS PostgreSQL -> the page text
//   then: a draft edit must not change the release; a second release and a rollback; unpublish -> the data route answers 404.
// Needs: `PORTALS=1 ./scripts/run-local.sh` (or the same environment: SITES_PUBLIC_DATA_ENABLED, SITES_DATA_API_BASE, TRUST_PROXY), the data target, Google Chrome.
// Usage: ./scripts/v1-public-smoke.sh
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { randomUUID } from "node:crypto";

const API = process.env.API ?? "http://127.0.0.1:8080";
const PASSWORD = process.env.LOCAL_ADMIN_PASSWORD;
const CHROME = process.env.CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
if (!PASSWORD) { console.error("LOCAL_ADMIN_PASSWORD is required"); process.exit(2); }
const ROOT = new URL("..", import.meta.url).pathname;
const targetPw = readFileSync(`${ROOT}.run/data-target/ro.pw`, "utf8").trim();
const { chromium } = createRequire(`${ROOT}package.json`)("playwright-core");

const jar = new Map(); let csrf = null; const results = [];
const check = (name, ok, info = "") => { results.push([name, ok]); console.log(`${ok ? "PASS" : "FAIL"} ${name}${info ? " | " + info : ""}`); return ok; };
async function call(method, path, body, headers = {}) {
  const h = { cookie: [...jar].map(([k, v]) => `${k}=${v}`).join("; "), ...headers };
  if (csrf && method !== "GET") h["x-xsrf-token"] = csrf;
  if (body !== undefined) h["content-type"] = "application/json";
  const res = await fetch(API + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), redirect: "manual" });
  for (const c of res.headers.getSetCookie?.() ?? []) { const [kv] = c.split(";"); const i = kv.indexOf("="); const k = kv.slice(0, i); if (c.toLowerCase().includes("max-age=0")) jar.delete(k); else jar.set(k, kv.slice(i + 1)); }
  const text = await res.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: res.status, json, text };
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let r = await call("GET", "/api/v1/auth/csrf"); csrf = r.json?.token;
r = await call("POST", "/api/v1/auth/login", { username: "local.admin", password: PASSWORD }); check("author login with CSRF", r.status === 200);
const ws = (await call("GET", "/api/v1/auth/me")).json?.workspaces?.[0]?.id;
const D = `/api/v1/workspaces/${ws}/data-sources`;

// ---- authoring through the API only
r = await call("POST", `/api/v1/workspaces/${ws}/projects`, { name: "Public smoke " + randomUUID().slice(0, 6) });
const pid = r.json?.id; const P = `/api/v1/workspaces/${ws}/projects/${pid}`; check("project created", !!pid, String(r.status));
const rev = async () => (await call("GET", P)).json?.revision;
const patch = async (operations, summary) => { const x = await call("PATCH", `${P}/schema`, { operations, expectedRevision: await rev(), summary }); return x; };
r = await patch([{ type: "ADD_DATA_SOURCE", definition: { id: "erp-db", name: "Shop", type: "postgres" } }], "slot");
check("declare the data-source slot (ADD_DATA_SOURCE, no SQL seed)", r.status === 200, `${r.status} ${r.status === 200 ? "" : r.text.slice(0, 200)}`);
r = await patch([{ type: "ADD_DATA_SOURCE", definition: { id: "bad", type: "postgres", sourceRef: randomUUID() } }], "x");
check("a slot can never carry sourceRef", r.status === 400 || r.status === 422, String(r.status));

r = await call("POST", D, { name: "pub-" + randomUUID().slice(0, 6), type: "postgres", config: { host: "127.0.0.1", port: "15440", database: "shop", schemas: "shop" } });
const ds = r.json?.id; check("data source on the TLS target", r.status === 201, String(r.status));
r = await call("PUT", `${D}/${ds}/credential`, { credential: { username: "shop_ro", password: targetPw } }); check("credential (metadata only)", r.status === 200 && !r.text.includes(targetPw));
r = await call("POST", `${D}/${ds}/test`, {}); check("test connection over TLS", r.json?.ok === true, JSON.stringify(r.json));
for (const [id, sql] of [["shop.title", "SELECT 'Open orders: ' || count(*)::text AS title FROM shop.orders WHERE status = 'open'"], ["shop.title2", "SELECT 'Second release: ' || count(*)::text AS title FROM shop.orders"],
  ["shop.items", "SELECT order_no AS name, status AS description FROM shop.orders ORDER BY order_no"]]) {
  r = await call("POST", `${D}/${ds}/queries`, { queryId: id, kind: "SQL", definition: { sql, params: [], maxRows: 20 } }); check(`query definition ${id}`, r.status === 201, `${r.status}`);
}
for (const mode of ["TEST", "LIVE"]) { r = await call("PUT", `${P}/data-bindings/${mode}/erp-db`, { dataSourceId: ds }); check(`bind ${mode}`, r.status === 200); }

const q = (id, key, extra = {}) => ({ type: "ADD_QUERY", definition: { id, name: id, dataSourceRef: "erp-db", operationKey: key, public: true, maxRows: 20, ...extra } });
r = await patch([q("q-title", "shop.title", { maxRows: 1 }), q("q-title2", "shop.title2", { maxRows: 1 }), q("q-items", "shop.items"),
  { type: "ADD_MAPPING", definition: { id: "m-title", queryRef: "q-title", fields: [{ from: "title", to: "title" }] } },
  { type: "ADD_MAPPING", definition: { id: "m-title2", queryRef: "q-title2", fields: [{ from: "title", to: "title" }] } },
  { type: "ADD_MAPPING", definition: { id: "m-items", queryRef: "q-items", fields: [{ from: "name", to: "name" }, { from: "description", to: "description" }] } }], "queries");
check("queries (public: true) and mappings added through operations", r.status === 200, `${r.status} ${r.status === 200 ? "" : r.text.slice(0, 300)}`);
r = await patch([{ type: "ADD_DATA_BINDING", definition: { id: "b-title", sectionId: "hero-1", prop: "title", queryRef: "q-title", mappingRef: "m-title" } },
  { type: "ADD_DATA_BINDING", definition: { id: "b-items", sectionId: "products-1", prop: "items", queryRef: "q-items", mappingRef: "m-items" } }], "bindings");
check("data bindings (Hero title, ProductGrid items)", r.status === 200, `${r.status} ${r.status === 200 ? "" : r.text.slice(0, 300)}`);

// public data needs the author's explicit acknowledgement
let rev1 = await call("GET", `${P}/publish-config`);
r = await call("PUT", `${P}/publish-config`, { mode: "STATIC", visibility: "PUBLIC", requiresAuth: false, acknowledgePublicData: false, ...(rev1.json?.config?.revision ? { expectedRevision: rev1.json.config.revision } : {}) });
check("a PUBLIC data-bound page is refused without the public-data acknowledgement", r.status >= 400 || r.json?.config?.publicDataApproved === false, `${r.status}`);
rev1 = await call("GET", `${P}/publish-config`);
r = await call("PUT", `${P}/publish-config`, { mode: "STATIC", visibility: "PUBLIC", requiresAuth: false, acknowledgePublicData: true, ...(rev1.json?.config?.revision ? { expectedRevision: rev1.json.config.revision } : {}) });
check("acknowledge public data", r.status === 200 && r.json?.config?.publicDataApproved === true, `${r.status} ${r.status === 200 ? "" : r.text.slice(0, 200)}`);

async function publish() {
  const x = await call("POST", `${P}/publish`, { visibility: "PUBLIC", expectedRevision: await rev() }, { "idempotency-key": "pub-smoke-" + randomUUID() });
  const dep = x.json?.id; let st = ""; for (let i = 0; i < 90 && !["RUNNING", "FAILED"].includes(st); i++) { await sleep(1000); st = (await call("GET", `${P}/deployments/${dep}`)).json?.status ?? ""; }
  return { dep, st, x };
}
const rel1 = await publish(); check("publish release 1 (PAGE_SCHEMA with a runtime)", rel1.st === "RUNNING", `${rel1.x.status} ${rel1.st}`);
const siteUrl = (await call("GET", `${P}/site`)).json?.url; const slug = new URL(siteUrl).pathname.replace(/\//g, "");
const events = (await call("GET", `${P}/deployments/${rel1.dep}`)).text; check("the publish confirmation lists the public queries", /PUBLIC_QUERIES|q-title/.test(events), "");

// ---- the visitor: a real browser, empty profile, no session
const browser = await chromium.launch({ executablePath: CHROME, headless: true });
async function visit(label) {
  const ctx = await browser.newContext(); const page = await ctx.newPage(); const reqs = [];
  page.on("request", (q) => reqs.push({ url: q.url(), method: q.method(), headers: q.headers() }));
  await page.goto(siteUrl, { waitUntil: "load" });
  const state = await page.waitForSelector("[data-xw-runtime][data-xw-state='ready'], [data-xw-runtime][data-xw-state='error'], [data-xw-runtime][data-xw-state='not-ready']", { timeout: 20000 }).then((e) => e.getAttribute("data-xw-state")).catch(() => "timeout");
  const title = await page.locator("[data-xw-bind='b-title']").first().textContent().catch(() => null);
  const items = await page.locator("[data-xw-list='b-items'] article").allTextContents().catch(() => []);
  const cookies = await ctx.cookies(); await ctx.close();
  return { state, title: title?.trim(), items, reqs, cookies };
}
let v = await visit("release 1");
check("visitor: runtime READY", v.state === "ready", v.state);
check("visitor: the page text is the live database value (TLS PostgreSQL)", /^Open orders: \d+$/.test(v.title ?? ""), v.title ?? "");
check("visitor: the list is rendered from the database", v.items.some((t) => /SO-1001/.test(t)), `${v.items.length} rows`);
const dataReqs = v.reqs.filter((q) => q.url.includes("/_data/"));
check("visitor: data calls are POST to the same origin /{slug}/_data, no cookie, no authorization", dataReqs.length >= 2 && dataReqs.every((q) => q.method === "POST" && new URL(q.url).origin === new URL(siteUrl).origin && !q.headers.cookie && !q.headers.authorization), dataReqs.map((q) => new URL(q.url).pathname).join(" "));
check("visitor: no request leaves the sites origin", v.reqs.every((q) => new URL(q.url).origin === new URL(siteUrl).origin || q.url.startsWith("data:")), "");
check("visitor: no cookie in the profile", v.cookies.length === 0, JSON.stringify(v.cookies.map((c) => c.name)));
const cfg = await (await fetch(new URL("__factory/config.json", siteUrl))).json();
check("runtime config: slug-scoped apiBase, no internal host, release id", cfg.apiBase === `${new URL(siteUrl).origin}/${slug}/_data` && cfg.releaseId === rel1.dep && !/docker|localhost/.test(cfg.apiBase ?? ""), JSON.stringify({ apiBase: cfg.apiBase, version: cfg.version }));
const keys = Object.keys(cfg).sort().join(","); check("runtime config exposes no tenant, workspace, credential, lease or fence field", Object.keys(cfg).every((k) => !/tenant|workspace|credential|lease|fence|secret|artifact|token/i.test(k.replace("releaseId", ""))), keys);

// raw requests a visitor could try
const post = (path, body, headers = {}) => fetch(new URL("/" + path, siteUrl), { method: "POST", headers: { "content-type": "application/json", ...headers }, body: JSON.stringify(body) });
let x = await post(`${slug}/_data/queries/q-title/run`, { params: {}, tenantId: randomUUID() }); check("forged tenantId in the body is refused (400)", x.status === 400, String(x.status));
x = await post(`${slug}/_data/queries/q-title/run`, { params: {} }, { "x-forwarded-for": "6.6.6.6" }); check("a public query by plain POST works", x.status === 200, String(x.status));
x = await post(`${slug}/_data/mutations/orders.create/run`, { params: {} }); check("there is no public mutation route", x.status >= 400 && x.status !== 429 && ![200, 202].includes(x.status), String(x.status));
x = await post(`${slug}/_data/queries/no-such-query/run`, { params: {} }); const bodyUnknown = await x.json().catch(() => null); check("an unknown query is 404 QUERY_NOT_FOUND", x.status === 404 && bodyUnknown?.code === "QUERY_NOT_FOUND", String(x.status));

// ---- a draft change does not reach the active release
r = await patch([{ type: "UPDATE_QUERY", definitionId: "q-title", definition: { public: false } }], "draft: q-title no longer public");
check("draft edit: q-title is no longer public IN THE DRAFT", r.status === 200, String(r.status));
x = await post(`${slug}/_data/queries/q-title/run`, { params: {} }); check("the active release still serves q-title (draft change has no effect)", x.status === 200, String(x.status));
await patch([{ type: "UPDATE_QUERY", definitionId: "q-title", definition: { public: true } }], "draft restored");

// ---- release 2 uses another query for the title; rollback restores release 1
r = await patch([q("q-extra", "shop.items"), { type: "ADD_MAPPING", definition: { id: "m-extra", queryRef: "q-extra", fields: [{ from: "name", to: "name" }] } },
  { type: "UPDATE_DATA_BINDING", definitionId: "b-title", definition: { queryRef: "q-title2", mappingRef: "m-title2" } }], "release 2: a new public query and another title binding");
check("release 2 binding edit", r.status === 200, `${r.status} ${r.status === 200 ? "" : r.text.slice(0, 200)}`);
const rel2 = await publish(); check("publish release 2", rel2.st === "RUNNING", rel2.st);
x = await post(`${slug}/_data/queries/q-extra/run`, { params: {} }); check("release 2 serves its own new public query q-extra", x.status === 200, String(x.status));
v = await visit("release 2"); check("visitor sees release 2 (Second release)", /^Second release: \d+$/.test(v.title ?? ""), v.title ?? "");
r = await call("POST", `${P}/site/rollback`, { deploymentId: rel1.dep }, { "idempotency-key": "rb-" + randomUUID() }); check("rollback to release 1", r.status === 200, String(r.status));
v = await visit("after rollback"); check("visitor sees release 1 again after the rollback", /^Open orders: \d+$/.test(v.title ?? ""), v.title ?? "");
x = await post(`${slug}/_data/queries/q-extra/run`, { params: {} }); check("q-extra belongs to release 2 only: 404 after the rollback (the allow-list follows the release)", x.status === 404, String(x.status));

// ---- unpublish
r = await call("DELETE", `${P}/site`); check("unpublish", r.status === 200 || r.status === 204, String(r.status));
x = await post(`${slug}/_data/queries/q-title/run`, { params: {} }); check("after unpublish the data route is 404", x.status === 404, String(x.status));
const off = await fetch(siteUrl); check("the page is offline", off.status === 404, String(off.status));
await browser.close();
const failed = results.filter(([, ok]) => !ok).map(([n]) => n);
console.log(`\nTOTAL ${results.length}  FAILED ${failed.length}${failed.length ? "  -> " + failed.join(" | ") : ""}`);
process.exit(failed.length ? 1 : 0);
