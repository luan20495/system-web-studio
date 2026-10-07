// Real-Chrome checks of the PAGE_SCHEMA client runtime (C2, V1 option b): the page the real renderer produces, the real runtime script, a stub origin that
// plays the sites gateway (page + runtime + __factory/config.json + the public data route) with the CSP a published data-bound page is served with.
// This is NOT an end-to-end test: the data route is a test double (the public query controller is C0 + C3 + C1 and does not exist yet).
//
//   npx tsc -p tests/tsconfig.json        # compiles the renderer and the worker module into .test-build
//   CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" node tests/browser/page-runtime.spec.mjs
import { createRequire } from "node:module";
import { createServer } from "node:http";
const require = createRequire(new URL("../../package.json", import.meta.url).pathname);
const { chromium } = require("playwright-core");
const { renderSitePages } = require("./.test-build/lib/schema-preview.js");
const { resolveBindings, PAGE_RUNTIME_JS } = require("./.test-build/workers/render/page-runtime.js");

// the CSP the API sends for a PAGE_SCHEMA page whose artifact contains the runtime (SiteControllers.DATA_BOUND_SITE_CSP; pinned by PublishApiContractTests)
const CSP = "default-src 'none'; script-src 'self'; connect-src 'self'; img-src 'self' data:; style-src 'unsafe-inline'; font-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
const schema = {
  schemaVersion: 2,
  sections: [
    { id: "hero-1", type: "Hero", componentVersion: "1.0.0", props: { eyebrow: "Eb", title: "Authored title", description: "Authored description" } },
    { id: "grid-1", type: "ProductGrid", componentVersion: "1.0.0", props: { heading: "Products", items: [{ id: "i1", name: "Authored item", description: "d" }] } },
    { id: "talk-1", type: "Testimonials", componentVersion: "1.0.0", props: { heading: "Voices", items: [{ id: "t1", quote: "Authored quote", author: "Authored author" }] } },
  ],
  pages: [{ id: "about", slug: "about", title: "About", sections: [{ id: "tech-1", type: "TechnologySection", componentVersion: "1.0.0", props: { heading: "Tech", body: "Authored body" } }] }],
  dataSources: [{ id: "ds", type: "postgres" }],
  queries: [{ id: "q-title", dataSourceRef: "ds", mode: "READ", operationKey: "k.title", public: true }, { id: "q-items", dataSourceRef: "ds", mode: "READ", operationKey: "k.items", public: true },
    { id: "q-voices", dataSourceRef: "ds", mode: "READ", operationKey: "k.voices", public: true }],
  dataBindings: [{ id: "b-title", sectionId: "hero-1", prop: "title", queryRef: "q-title" }, { id: "b-items", sectionId: "grid-1", prop: "items", queryRef: "q-items" },
    { id: "b-voices", sectionId: "talk-1", prop: "items", queryRef: "q-voices" }, { id: "b-body", sectionId: "tech-1", prop: "body", queryRef: "q-title" }],
};
const files = renderSitePages(schema, {}, resolveBindings(schema));

const results = []; const check = (n, ok, d = "") => { results.push(ok); console.log(`${ok ? "PASS" : "FAIL"}  ${n}${d ? "  — " + d : ""}`); };
let scn; const seen = [];
const ok = (rows, extra = {}) => ({ status: 200, body: { queryId: "x", mode: "LIVE", cache: "MISS", result: { viewModelId: "vm", cardinality: "LIST", fields: [], rows, truncated: false, warnings: [], skippedRows: 0, ...extra } } });
const server = createServer((req, res) => {
  const url = new URL(req.url, "http://x"); const chunks = []; req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    const body = Buffer.concat(chunks).toString("utf8"); const send = (s, t, b, h = {}) => { res.writeHead(s, { "Content-Type": t, "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", ...h }); res.end(b); };
    seen.push({ method: req.method, path: url.pathname, headers: req.headers, body });
    const html = (name) => send(200, "text/html; charset=utf-8", files[name], { "Content-Security-Policy": CSP });
    if (req.method === "GET" && url.pathname === "/demo/") return html("index.html");
    if (req.method === "GET" && url.pathname === "/demo/about/") return html("about/index.html");
    if (req.method === "GET" && url.pathname === "/demo/_runtime/page-runtime.js") return send(200, "application/javascript; charset=utf-8", PAGE_RUNTIME_JS);
    if (req.method === "GET" && url.pathname === "/demo/__factory/config.json") { const c = scn.config(origin()); return send(c.status, "application/json", typeof c.body === "string" ? c.body : JSON.stringify(c.body)); }
    const m = /^\/demo\/_data\/queries\/([a-z0-9-]+)\/run$/.exec(url.pathname);
    if (req.method === "POST" && m) { const d = scn.data(m[1], body); return setTimeout(() => send(d.status, "application/json", typeof d.body === "string" ? d.body : JSON.stringify(d.body)), d.delay ?? 60); }
    send(404, "text/plain", "not found");
  });
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const PORT = server.address().port; const origin = () => `http://127.0.0.1:${PORT}`;
const cfg = (apiBase) => (o) => ({ status: 200, body: { appId: "00000000-0000-0000-0000-000000000001", appName: "Demo", environment: "production", visibility: "PUBLIC", version: "3", user: null, flags: {}, apiBase, releaseId: "00000000-0000-0000-0000-0000000000a1", generatedAt: "2026-10-07T00:00:00Z" } });
const sameOrigin = (o) => cfg(`${o}/demo/_data`)(o);
const happy = (q) => q === "q-title" ? ok([{ title: "Real title" }], { cardinality: "ONE" })
  : q === "q-items" ? ok([{ name: "Máy A", description: "Mô tả A" }, { name: "Máy B", description: "Mô tả B" }])
  : ok([{ quote: "Great", author: "Lan", location: "HN", rating: 4 }, { quote: "Fine", author: "Minh", rating: 9 }]);

const browser = await chromium.launch({ executablePath: process.env.CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" });
async function load(path, scenario, { cookie = false } = {}) {
  scn = scenario; seen.length = 0;
  const ctx = await browser.newContext({ viewport: { width: 1200, height: 800 } });
  if (cookie) await ctx.addCookies([{ name: "site_session", value: "visitor-cookie", url: origin() }]);
  const page = await ctx.newPage(); const problems = []; const hosts = new Set();
  page.on("console", (m) => { if (/Content Security Policy|Refused to/i.test(m.text())) problems.push(m.text().slice(0, 140)); });
  page.on("pageerror", (e) => problems.push("pageerror " + e.message.slice(0, 100)));
  page.on("request", (r) => hosts.add(new URL(r.url()).origin));
  await page.addInitScript(() => { window.__states = []; document.addEventListener("xw:state", (e) => window.__states.push(e.detail.state + (e.detail.detail ? ":" + e.detail.detail : "")), true); });
  await page.goto(origin() + path);
  await page.waitForFunction(() => ["ready", "error", "not-ready"].includes(document.querySelector("[data-xw-runtime]")?.getAttribute("data-xw-state")), null, { timeout: 8000 });
  const st = () => page.evaluate(() => ({ state: document.querySelector("[data-xw-runtime]").getAttribute("data-xw-state"), detail: document.querySelector("[data-xw-runtime]").getAttribute("data-xw-detail"), states: window.__states, busy: document.querySelector("[data-xw-runtime]").getAttribute("aria-busy") }));
  return { page, ctx, problems, hosts, st, data: () => seen.filter((s) => s.method === "POST") };
}
const text = (page, sel) => page.locator(sel).first().innerText();

// ---- 1. the happy path ---------------------------------------------------------------------------------------------------------------------------
{
  const t = await load("/demo/", { config: sameOrigin, data: (q) => ({ ...happy(q), delay: 150 }) }, { cookie: true });
  const s = await t.st();
  check("READY: the page walks LOADING_CONFIG -> LOADING_DATA -> READY, once, and ends not busy", JSON.stringify(s.states) === JSON.stringify(["loading-config", "loading-data", "ready"]) && s.busy === "false", JSON.stringify(s.states));
  check("READY: a bound scalar shows the data, not the authored fallback", (await text(t.page, "h1")) === "Real title");
  check("READY: a bound list is rebuilt from the rows (the authored row is gone)", (await t.page.locator(".product-grid article").count()) === 2 && (await text(t.page, ".product-grid article h3")) === "Máy A" && !(await t.page.content()).includes("Authored item</h3>"));
  check("READY: the testimonials list fills quote, author, location separator and stars (rating clamped to 5)", await t.page.evaluate(() => { const a = [...document.querySelectorAll(".testimonial-grid article")]; return a.length === 2 && a[0].querySelector(".stars").textContent === "★★★★" && a[0].querySelector("strong").textContent === "Lan · HN" && a[1].querySelector("strong").textContent === "Minh" && a[1].querySelector(".stars").textContent === "★★★★★"; }));
  const posts = t.data();
  check("READY: exactly one POST per distinct query (3), to the same origin, body only {\"params\":{}}", posts.length === 3 && new Set(posts.map((p) => p.path)).size === 3 && posts.every((p) => /^\/demo\/_data\/queries\/q-(title|items|voices)\/run$/.test(p.path) && p.body === '{"params":{}}' && /application\/json/.test(p.headers["content-type"])));
  check("READY: no cookie, no Authorization, no CSRF header, no identifier is sent to the data route (even with a site cookie present)", posts.every((p) => !p.headers.cookie && !p.headers.authorization && !p.headers["x-xsrf-token"]) && (await t.ctx.cookies()).length === 1);
  check("READY: nothing outside the page's own origin was contacted", [...t.hosts].every((h) => h === origin()), [...t.hosts].join(","));
  check("READY: the page works under the published CSP (no violation, no page error)", t.problems.length === 0, t.problems.join(" ; "));
  check("READY: every bound element is marked ready", await t.page.evaluate(() => [...document.querySelectorAll("[data-xw-bind],[data-xw-list]")].every((e) => e.getAttribute("data-xw-state") === "ready")));
  await t.ctx.close();
}
// ---- 2. NOT_READY: nothing is guessed --------------------------------------------------------------------------------------------------------------
for (const [label, config, detail] of [
  ["apiBase is null", cfg(null), "api-base-missing"], ["apiBase is empty", cfg(""), "api-base-missing"], ["apiBase is not a URL", cfg("not a url"), "api-base-invalid"],
  ["apiBase is ftp", cfg("ftp://127.0.0.1/demo/_data"), "api-base-invalid"], ["apiBase has credentials", (o) => cfg(`http://u:p@127.0.0.1:${PORT}/demo/_data`)(o), "api-base-invalid"],
  ["apiBase is another origin (localhost vs 127.0.0.1)", (o) => cfg(`http://localhost:${PORT}/demo/_data`)(o), "api-base-cross-origin"],
  ["apiBase is an internal host", cfg("http://host.docker.internal:8080/demo/_data"), "api-base-cross-origin"],
  ["the config is a 404", () => ({ status: 404, body: "nope" }), "config-unavailable"], ["the config is not JSON", () => ({ status: 200, body: "<html>" }), "config-unavailable"],
  ["the config is a 500", () => ({ status: 500, body: {} }), "config-unavailable"],
]) {
  const t = await load("/demo/", { config, data: happy }); const s = await t.st();
  await t.page.waitForTimeout(400);
  check(`NOT_READY (${label}): state not-ready:${detail}, authored content untouched, NO data request, no other host contacted`,
    s.state === "not-ready" && s.detail === detail && (await text(t.page, "h1")) === "Authored title" && t.data().length === 0 && [...t.hosts].every((h) => h === origin()), `${s.state}:${s.detail} posts=${t.data().length}`);
  await t.ctx.close();
}
// ---- 3. runtime errors: deterministic, one attempt --------------------------------------------------------------------------------------------------
for (const [label, status, body, code] of [["401", 401, { code: "UNAUTHENTICATED" }, "forbidden"], ["403", 403, {}, "forbidden"], ["404", 404, { code: "QUERY_NOT_FOUND" }, "not-found"], ["429", 429, {}, "rate-limited"],
  ["500", 500, {}, "unavailable"], ["503", 503, { code: "DATA_RUNTIME_UNAVAILABLE" }, "unavailable"], ["422", 422, {}, "http-422"], ["200 with a body that is not a result", 200, { nope: 1 }, "invalid-response"],
  ["200 that is not JSON", 200, "<html>", "invalid-response"], ["200 with rows that are not a list", 200, { result: { rows: "x" } }, "invalid-response"]]) {
  const t = await load("/demo/", { config: sameOrigin, data: (q) => (q === "q-title" ? { status, body } : happy(q)) }); const s = await t.st();
  await t.page.waitForTimeout(500);
  const el = await t.page.locator("h1").getAttribute("data-xw-error");
  check(`ERROR (${label} on one query): root error:q-title:${code}, that element error=${code} with its authored content, the other queries still render, no retry`,
    s.state === "error" && s.detail === `q-title:${code}` && el === code && (await text(t.page, "h1")) === "Authored title" && (await t.page.locator(".product-grid article").count()) === 2 && t.data().filter((p) => p.path.includes("q-title")).length === 1, `${s.state}:${s.detail} el=${el}`);
  await t.ctx.close();
}
{
  const t = await load("/demo/", { config: sameOrigin, data: () => ({ status: 429, body: {} }) }); const s = await t.st(); await t.page.waitForTimeout(1500);
  check("ERROR: every query failing leaves the whole authored page, and there is no automatic retry (1.5 s later still exactly 3 requests)", s.state === "error" && t.data().length === 3 && (await text(t.page, "h1")) === "Authored title");
  await t.ctx.close();
}
// ---- 4. data shapes ---------------------------------------------------------------------------------------------------------------------------------
{
  const evil = `<img src=x onerror="window.__pwned=1"><script>window.__pwned=2</script>`;
  const t = await load("/demo/", { config: sameOrigin, data: (q) => (q === "q-title" ? ok([{ title: evil }], { cardinality: "ONE" }) : q === "q-items" ? ok([{ name: evil, description: evil }]) : happy(q)) }); const s = await t.st();
  check("SECURITY: HTML in data is shown as text - nothing is injected, nothing runs", s.state === "ready" && (await text(t.page, "h1")) === evil && (await t.page.locator(".product-grid img, h1 img, .product-grid script").count()) === 0 && (await t.page.evaluate(() => window.__pwned)) === undefined);
  await t.ctx.close();
}
{
  const t = await load("/demo/", { config: sameOrigin, data: (q) => (q === "q-title" ? ok([], { cardinality: "ONE" }) : q === "q-items" ? ok([]) : happy(q)) }); const s = await t.st();
  check("EMPTY: no rows keeps a bound scalar's authored text, and empties a bound list; the page is ready", s.state === "ready" && (await text(t.page, "h1")) === "Authored title" && (await t.page.locator("h1").getAttribute("data-xw-state")) === "empty" && (await t.page.locator(".product-grid article").count()) === 0 && (await t.page.locator(".product-grid").getAttribute("data-xw-state")) === "empty");
  await t.ctx.close();
}
{
  const t = await load("/demo/", { config: sameOrigin, data: (q) => (q === "q-title" ? ok([{ a: 1, b: 2 }], { cardinality: "ONE" }) : happy(q)) }); const s = await t.st();
  check("CONTRACT: a scalar whose row has no field of that name and more than one field is a contract error, not a guess", s.state === "error" && (await t.page.locator("h1").getAttribute("data-xw-error")) === "field-missing" && (await text(t.page, "h1")) === "Authored title");
  await t.ctx.close();
  const u = await load("/demo/", { config: sameOrigin, data: (q) => (q === "q-title" ? ok([{ whatever: "One field" }], { cardinality: "ONE" }) : happy(q)) });
  check("a single-field row feeds a scalar whatever the field is called", (await text(u.page, "h1")) === "One field");
  await u.ctx.close();
}
{
  const t = await load("/demo/", { config: sameOrigin, data: (q) => (q === "q-items" ? ok(Array.from({ length: 250 }, (_, i) => ({ name: "n" + i, description: "d" }))) : happy(q)) });
  check("a list is capped at 200 rows and says so", (await t.page.locator(".product-grid article").count()) === 200 && (await t.page.locator(".product-grid").getAttribute("data-xw-truncated")) === "1");
  await t.ctx.close();
}
// ---- 5. a sub-page: paths are relative to the site root --------------------------------------------------------------------------------------------
{
  const t = await load("/demo/about/", { config: sameOrigin, data: happy }); const s = await t.st();
  const paths = seen.map((x) => x.method + " " + x.path);
  check("SUB-PAGE: runtime and config are found one level up, the page's own query is called, its prop is bound", s.state === "ready" && paths.includes("GET /demo/_runtime/page-runtime.js") && paths.includes("GET /demo/__factory/config.json") && paths.includes("POST /demo/_data/queries/q-title/run") && (await text(t.page, "p[data-xw-bind]")) === "Real title", paths.join(" | "));
  check("SUB-PAGE: only the queries this page binds are called", t.data().length === 1);
  await t.ctx.close();
}
await browser.close(); server.close();
const failed = results.filter((r) => !r).length;
console.log(`\n${results.length - failed}/${results.length} passed`); process.exit(failed ? 1 : 0);
