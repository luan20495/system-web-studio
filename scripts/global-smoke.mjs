#!/usr/bin/env node
// @class: real-backend
// GLOBAL smoke (D-C0-38): the public prod-profile stack, reached ONLY through its Internet hostnames (Cloudflare -> hbl-studio tunnel).
//   A Studio loads  B authenticated API through the same-origin proxy  C session + CSRF over HTTPS (cookie flags)  D CORS  E published PAGE_SCHEMA site on the sites host
//   F CSP of the published page  G runtime config  H public query route (PASS only when a data-bound page is integrated, else NOT_READY with the reason)
//   I unknown path / host: no leakage  J actuator and private surfaces  K plain http  L forged client-IP headers
// Credentials: a member account of .run/public/demo-accounts.txt (or SMOKE_USER/SMOKE_PASSWORD); read here, never printed, sent only to the login route.
// Usage: node scripts/global-smoke.mjs     Env: STUDIO (https://studio.toolsmcp.uk)  SITES (https://sites.toolsmcp.uk)
import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";

const ROOT = new URL("..", import.meta.url).pathname;
const env = Object.fromEntries(readFileSync(`${ROOT}.run/public/public.env`, "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const STUDIO = process.env.STUDIO ?? `https://${env.PUBLIC_HOST}`;
const SITES = process.env.SITES ?? `https://${env.SITES_HOST}`;
// A system admin is not a member of a workspace (creating an application answers 409 ADMIN_NOT_MEMBER), so the smoke signs in as the first operator-created member account.
const demo = readFileSync(`${ROOT}.run/public/demo-accounts.txt`, "utf8").split("\n").map((l) => l.trim().split(/\s+/)).find((p) => p.length >= 2 && /^demo/.test(p[0]));
const USER = process.env.SMOKE_USER ?? demo?.[0], PASSWORD = process.env.SMOKE_PASSWORD ?? demo?.[1];
if (!USER || !PASSWORD) { console.error("no member account: .run/public/demo-accounts.txt (or SMOKE_USER / SMOKE_PASSWORD)"); process.exit(2); }

const results = []; const notes = [];
const check = (name, ok, info = "") => { results.push([name, ok]); console.log(`${ok ? "PASS" : "FAIL"} ${name}${info ? " | " + info : ""}`); return ok; };
const note = (name, status, info) => { notes.push([name, status]); console.log(`${status} ${name} | ${info}`); };
const jar = new Map(); let csrf = null; const rawCookies = [];
async function req(base, method, path, { body, headers = {}, auth = true, redirect = "manual" } = {}) {
  const h = { ...headers };
  if (auth && jar.size) h.cookie = [...jar].map(([k, v]) => `${k}=${v}`).join("; ");
  if (auth && csrf && method !== "GET" && method !== "HEAD" && !("x-xsrf-token" in h)) h["x-xsrf-token"] = csrf;
  if (body !== undefined) h["content-type"] = "application/json";
  const res = await fetch(base + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), redirect });
  const sc = res.headers.getSetCookie?.() ?? [];
  for (const c of auth ? sc : (path === "/api/v1/auth/csrf" && !jar.size ? sc : [])) { rawCookies.push(c); const [kv] = c.split(";"); const i = kv.indexOf("="); const k = kv.slice(0, i); if (/max-age=0/i.test(c)) jar.delete(k); else jar.set(k, kv.slice(i + 1)); }
  const text = await res.text(); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: res.status, json, text, headers: res.headers, setCookies: sc };
}
const cookieOf = (list, name) => list.find((c) => c.startsWith(name + "="));
const hasAttr = (c, a) => new RegExp(`(^|;\\s*)${a}(;|$|=)`, "i").test(c ?? "");

// ---- A. Studio loads
let r = await req(STUDIO, "GET", "/", { auth: false });
check("A Studio shell over HTTPS", r.status === 200 && /<html/i.test(r.text), `${r.status}`);
check("A HSTS present", /max-age=\d{7,}/.test(r.headers.get("strict-transport-security") ?? ""), r.headers.get("strict-transport-security") ?? "none");
check("A Studio CSP: nonce, object-src 'none', frame-ancestors 'none'", /nonce-/.test(r.headers.get("content-security-policy") ?? "") && /object-src 'none'/.test(r.headers.get("content-security-policy") ?? "") && /frame-ancestors 'none'/.test(r.headers.get("content-security-policy") ?? ""));

// ---- C. session + CSRF
r = await req(STUDIO, "GET", "/api/v1/auth/csrf", { auth: false });
csrf = r.json?.token;
const xsrfCookie = cookieOf(r.setCookies, "XSRF-TOKEN");
check("C CSRF token through the same-origin proxy", r.status === 200 && !!csrf, `${r.status}`);
check("C XSRF-TOKEN cookie: Secure, SameSite=Lax, no Domain", hasAttr(xsrfCookie, "Secure") && /samesite=lax/i.test(xsrfCookie ?? "") && !hasAttr(xsrfCookie, "Domain"), (xsrfCookie ?? "none").replace(/=[^;]*/, "=<v>"));
r = await req(STUDIO, "POST", "/api/v1/auth/login", { body: { username: USER, password: PASSWORD }, headers: { "x-xsrf-token": "forged" } });
check("C login with a forged CSRF token is refused", r.status === 403, `${r.status}`);
r = await req(STUDIO, "POST", "/api/v1/auth/login", { body: { username: USER, password: PASSWORD } });
const sess = cookieOf(r.setCookies, "STUDIO_SESSION");
check("C login with CSRF over HTTPS", r.status === 200, `${r.status}`);
check("C STUDIO_SESSION cookie: Secure, HttpOnly, SameSite=Lax, host-only, Path=/", hasAttr(sess, "Secure") && hasAttr(sess, "HttpOnly") && /samesite=lax/i.test(sess ?? "") && !hasAttr(sess, "Domain") && /path=\//i.test(sess ?? ""), (sess ?? "none").replace(/=[^;]*/, "=<v>"));
const fresh = await req(STUDIO, "GET", "/api/v1/auth/csrf"); if (fresh.json?.token) csrf = fresh.json.token;

// ---- B. authenticated API, same-origin proxy
r = await req(STUDIO, "GET", "/api/v1/auth/me");
const ws = r.json?.workspaces?.[0]?.id ?? r.json?.workspaces?.[0]?.workspaceId;
check("B authenticated API through the Studio origin (/api proxy)", r.status === 200 && !!ws, `${r.status}`);
r = await req(STUDIO, "GET", "/api/v1/auth/me", { auth: false });
check("B the same call without a session is 401", r.status === 401, `${r.status}`);
r = await req(STUDIO, "POST", "/api/v1/auth/logout", { headers: { "x-xsrf-token": "" } });
check("C an unsafe authenticated call without a CSRF token is refused (403)", r.status === 403, `${r.status}`);
r = await req(STUDIO, "GET", "/api/v1/auth/me"); check("C the refused logout did not end the session", r.status === 200, `${r.status}`);
{ const t = await req(STUDIO, "GET", "/api/v1/auth/csrf"); if (t.json?.token) csrf = t.json.token; }

// ---- D. CORS
const probeCors = async (origin, path = "/api/v1/auth/csrf") => (await req(STUDIO, "GET", path, { auth: false, headers: { origin } })).headers;
let h = await probeCors("https://evil.example");
check("D untrusted Origin gets no Access-Control-Allow-Origin", !h.get("access-control-allow-origin"), h.get("access-control-allow-origin") ?? "none");
h = await probeCors("null"); check("D Origin: null gets no Access-Control-Allow-Origin", !h.get("access-control-allow-origin"), h.get("access-control-allow-origin") ?? "none");
let pre = await req(STUDIO, "OPTIONS", "/api/v1/auth/login", { auth: false, headers: { origin: "https://evil.example", "access-control-request-method": "POST", "access-control-request-headers": "content-type,x-xsrf-token" } });
check("D preflight from an untrusted Origin is not allowed", !pre.headers.get("access-control-allow-origin") && pre.status !== 200 || !pre.headers.get("access-control-allow-origin"), `${pre.status} acao=${pre.headers.get("access-control-allow-origin") ?? "none"}`);
h = await probeCors(STUDIO); const acao = h.get("access-control-allow-origin");
check("D never `*` (with or without credentials)", acao !== "*", acao ?? "none (same-origin, no CORS header needed)");
if (acao) check("D a trusted Origin is echoed exactly, with credentials", acao === STUDIO && h.get("access-control-allow-credentials") === "true", `${acao} credentials=${h.get("access-control-allow-credentials")}`);
r = await req(SITES, "GET", "/__nope/", { auth: false, headers: { origin: "https://evil.example" } });
check("D the sites host sends no CORS headers to a foreign Origin", !r.headers.get("access-control-allow-origin"), r.headers.get("access-control-allow-origin") ?? "none");

// ---- E-G. author + publish a PAGE_SCHEMA site, then read it as a visitor
const P0 = `/api/v1/workspaces/${ws}/projects`;
r = await req(STUDIO, "POST", P0, { body: { name: "Global smoke " + randomUUID().slice(0, 6) } });
const pid = r.json?.id; check("E project created through the API", (r.status === 200 || r.status === 201) && !!pid, `${r.status}`);
const P = `${P0}/${pid}`;
const rev = (await req(STUDIO, "GET", P)).json?.revision;
r = await req(STUDIO, "POST", `${P}/publish`, { body: { visibility: "PUBLIC", expectedRevision: rev }, headers: { "idempotency-key": "global-smoke-" + randomUUID() } });
const dep = r.json?.id; check("E publish accepted", [200, 201, 202].includes(r.status) && !!dep, `${r.status} ${r.status < 300 ? "" : r.text.slice(0, 200)}`);
let status = ""; for (let i = 0; i < 90 && !["RUNNING", "FAILED"].includes(status); i++) { await new Promise((s) => setTimeout(s, 1000)); status = (await req(STUDIO, "GET", `${P}/deployments/${dep}`)).json?.status ?? ""; }
check("E deployment reaches RUNNING", status === "RUNNING", status);
r = await req(STUDIO, "GET", `${P}/site`); const siteUrl = r.json?.url;
check("E the site address is on the sites host over https", !!siteUrl && siteUrl.startsWith(SITES + "/"), siteUrl ?? "none");
let slug = null;
if (siteUrl) {
  slug = new URL(siteUrl).pathname.split("/").filter(Boolean)[0];
  const page = await req(SITES, "GET", `/${slug}/`, { auth: false, headers: { cookie: "" } });
  check("E published page served on the sites host (no session)", page.status === 200 && /<html/i.test(page.text), `${page.status}`);
  const csp = page.headers.get("content-security-policy") ?? "";
  check("F page CSP: default-src 'none'", /default-src 'none'/.test(csp), csp.slice(0, 120));
  const scriptSrc = /script-src ([^;]*)/.exec(csp)?.[1];
  check("F page CSP: scripts only 'self' (a page without a script has no script-src: default-src 'none' blocks them); connect-src 'self' or none; no wildcard, no unsafe-eval", (scriptSrc === undefined || scriptSrc.trim() === "'self'") && (!/connect-src/.test(csp) || /connect-src 'self'(;|$)/.test(csp)) && !/\*/.test(csp) && !/unsafe-eval/.test(csp), scriptSrc === undefined ? "no script-src" : scriptSrc);
  check("F page CSP: no inline script allowance (script-src has no 'unsafe-inline')", !/script-src[^;]*'unsafe-inline'/.test(csp));
  check("F page CSP: frame-ancestors 'none', form-action 'self', base-uri 'none'", /frame-ancestors 'none'/.test(csp) && /form-action 'self'/.test(csp) && /base-uri 'none'/.test(csp));
  check("F page sends nosniff and no Set-Cookie (public page, no session)", page.headers.get("x-content-type-options") === "nosniff" && page.setCookies.length === 0);
  const iframeSandbox = /sandbox[^;]*allow-same-origin/.test(csp);
  check("F no `sandbox allow-same-origin` in the page CSP", !iframeSandbox);
  const cfg = await req(SITES, "GET", `/${slug}/__factory/config.json`, { auth: false });
  if (cfg.status === 200) { check("G runtime config on the sites host", !!cfg.json?.appId && cfg.json?.environment !== undefined && !/password|secret|token|credential/i.test(cfg.text), `visibility=${cfg.json?.visibility} apiBase=${cfg.json?.apiBase ?? "none"}`); }
  else note("G runtime config of a PAGE_SCHEMA site without data", "NOT_APPLICABLE", `${cfg.status}: only a page that binds a public query or a code app is given __factory/config.json (D-C0-35); the apiBase flow is covered by the local public smoke`);
  const q = await req(SITES, "POST", `/${slug}/_data/queries/orders-list/run`, { auth: false, body: { params: {} }, headers: { origin: SITES } });
  const body = q.json ?? {};
  check("H public query route answers without leaking (no data bound: 404 envelope, no stack, no SQL)", [403, 404].includes(q.status) && !/Exception|stack|SELECT |jdbc|postgres/i.test(q.text), `${q.status} ${body.code ?? ""}`);
  note("H public query PASS needs a data-bound page + a public-CA data source", "NOT_READY", "prod forbids private/loopback targets (postgres-targets.allowed-private empty); no globally reachable data source is provisioned in this stack. Route, flags and allow-list are live and fail closed (above)");
  const bad = await req(SITES, "POST", `/${slug}/_data/queries/orders-list/run`, { auth: false, body: { params: {} }, headers: { origin: "https://evil.example" } });
  check("H public query from a foreign Origin never gets Access-Control-Allow-Origin", !bad.headers.get("access-control-allow-origin"), `${bad.status}`);
  const get = await req(SITES, "GET", `/${slug}/_data/queries/orders-list/run`, { auth: false });
  check("H the data route is POST only (GET does not run a query)", get.status !== 200, `${get.status}`);
  const posted = await req(SITES, "POST", `/${slug}/`, { auth: false, body: {} });
  check("H POST to a page path is refused at the gateway", [403, 404, 405].includes(posted.status), `${posted.status}`);
}

// ---- I. no leakage
for (const [label, base, path] of [["unknown sites path", SITES, "/zz-unknown-slug-0/"], ["sites internals /actuator", SITES, "/actuator/health"], ["sites API prefix", SITES, "/api/v1/auth/me"], ["sites oauth", SITES, "/oauth2/authorization/x"], ["sites .git", SITES, "/.git/config"], ["sites _preview", SITES, "/_preview/x/"], ["sites traversal", SITES, "/%2e%2e/%2e%2e/etc/passwd"]]) {
  r = await req(base, "GET", path, { auth: false });
  check(`I ${label}: 4xx, no backend identity`, r.status >= 400 && r.status < 500 && !/Whitelabel|Spring|stacktrace|Exception|jdbc|X-Application-Context/i.test(r.text + [...r.headers.keys()].join(" ")), `${r.status}`);
}
r = await fetch(`${SITES}/`, { headers: { host: "evil.example" }, redirect: "manual" }).catch((e) => ({ status: 0, text: async () => "" }));
check("I a wrong Host header is not served by the tunnel (not 200)", r.status !== 200, `${r.status}`);
r = await req("https://studio-files.toolsmcp.uk", "GET", "/", { auth: false });
check("I studio-files host: no listing, no 200 on the root", r.status !== 200 && !/<ListBucketResult/.test(r.text), `${r.status}`);

// ---- J. actuator and private surfaces (Studio origin, authenticated AND anonymous)
for (const auth of [false, true]) for (const p of ["env", "beans", "heapdump", "prometheus", "shutdown", "loggers", "metrics", "configprops", "mappings", "threaddump"]) {
  r = await req(STUDIO, p === "shutdown" ? "POST" : "GET", `/actuator/${p}`, { auth });
  check(`J /actuator/${p} not exposed${auth ? " (even with a session)" : ""}`, !(r.status === 200 && !/<html/i.test(r.text.slice(0, 200))) && !/"propertySources"|"contexts"|"beans"/.test(r.text.slice(0, 2000)), `${r.status}${/<html/i.test(r.text.slice(0, 200)) ? " (Studio HTML shell, not the backend)" : ""}`);
}
for (const p of ["/v3/api-docs", "/swagger-ui/index.html", "/swagger-ui.html"]) { r = await req(STUDIO, "GET", p, { auth: false }); check(`J ${p} is not an OpenAPI/Swagger surface`, !/"openapi"|swagger-ui-bundle/i.test(r.text), `${r.status}`); }

// ---- K/L. plain http, forged client-IP headers
r = await fetch(STUDIO.replace("https:", "http:") + "/", { redirect: "manual" });
if (r.status >= 300 && r.status < 400) check("K plain http to Studio redirects to https", (r.headers.get("location") ?? "").startsWith("https://"), `${r.status}`);
else note("K plain http to Studio", "GAP", `${r.status} (no redirect): the edge zone option 'Always Use HTTPS' is shared with other systems and is not C0's; HSTS (2y, includeSubDomains) is sent on HTTPS`);
r = await fetch(`${SITES.replace("https:", "http:")}/zz-probe/`, { redirect: "manual" });
check("K plain http to the sites host redirects to https", r.status >= 300 && r.status < 400 && (r.headers.get("location") ?? "").startsWith("https://"), `${r.status}`);
// the forged-header effect is asserted from the gateway log by scripts/global-smoke.sh (needs the container); here only that the request is not rejected differently
r = await fetch(`${SITES}/zz-probe-xff/`, { headers: { "x-forwarded-for": "1.2.3.4", "x-real-ip": "5.6.7.8", forwarded: "for=9.9.9.9" }, redirect: "manual" });
check("L forged X-Forwarded-For / X-Real-IP / Forwarded do not change the answer", r.status === 404, `${r.status}`);
r = await fetch(`${SITES}/zz-probe-cf/`, { headers: { "cf-connecting-ip": "1.2.3.4" }, redirect: "manual" });
note("L forged CF-Connecting-IP from the Internet", r.status === 403 ? "PASS" : "CHECK", `${r.status} (Cloudflare rejects a client-supplied CF-Connecting-IP; it is never used by the origin)`);

// ---- cleanup: unpublish and archive what this run created
if (pid) { await req(STUDIO, "DELETE", `${P}/site`); await req(STUDIO, "DELETE", `${P}?expectedRevision=${(await req(STUDIO, "GET", P)).json?.revision ?? 0}`); }
if (slug) { r = await req(SITES, "GET", `/${slug}/`, { auth: false }); check("E after unpublish the site is offline (404)", r.status === 404, `${r.status}`); }
const failed = results.filter(([, ok]) => !ok).map(([n]) => n);
console.log(`\nTOTAL ${results.length}  FAILED ${failed.length}${failed.length ? "  -> " + failed.join(" | ") : ""}   NOTES ${notes.map(([n, s]) => `${s}`).join(",")}`);
process.exit(failed.length ? 1 : 0);
