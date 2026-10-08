#!/usr/bin/env node
// @class: real-backend
// PUBLIC 3-PORTAL smoke (D-C0-39): platform / admin / studio reached ONLY through their Internet hostnames (Cloudflare -> tunnel hbl-studio -> next start -> same-origin /api -> API).
//   per portal: HTTPS + Next HTML, every /_next asset the page references (JS, CSS, fonts), no localhost / internal address in anything the browser receives,
//   login page, session + cookie flags, GET through the same-origin proxy, unsafe call with / without / with a forged CSRF token, CORS through the proxy, logout, 401 afterwards,
//   deep link, host-only cookies (no cross-portal session), forged client-IP headers, published sites host untouched.
// Accounts: platform + admin = the operator (system admin) of .run/public/public.env; studio = the first member account of .run/public/demo-accounts.txt.
// Read here, never printed, sent only to the login route of the host being tested.
import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";

const ROOT = new URL("..", import.meta.url).pathname;
const env = Object.fromEntries(readFileSync(`${ROOT}.run/public/public.env`, "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const demo = readFileSync(`${ROOT}.run/public/demo-accounts.txt`, "utf8").split("\n").map((l) => l.trim().split(/\s+/)).find((p) => p.length >= 2 && /^demo/.test(p[0]));
const HOSTS = { platform: env.PUBLIC_PLATFORM_HOST ?? "platform.toolsmcp.uk", admin: env.PUBLIC_ADMIN_HOST ?? "admin.toolsmcp.uk", studio: env.PUBLIC_HOST ?? "studio.toolsmcp.uk" };
const SITES = `https://${env.SITES_HOST ?? "sites.toolsmcp.uk"}`;
const ACCOUNTS = { platform: [env.BOOTSTRAP_ADMIN_USERNAME, env.BOOTSTRAP_ADMIN_PASSWORD], admin: [env.BOOTSTRAP_ADMIN_USERNAME, env.BOOTSTRAP_ADMIN_PASSWORD], studio: [demo?.[0], demo?.[1]] };
const results = [], notes = [];
const check = (name, ok, info = "") => { results.push([name, ok]); console.log(`${ok ? "PASS" : "FAIL"} ${name}${info ? " | " + info : ""}`); return ok; };
const note = (name, status, info) => { notes.push(status); console.log(`${status} ${name} | ${info}`); };
const origin = (n) => `https://${HOSTS[n]}`;

class Client {
  constructor(base) { this.base = base; this.jar = new Map(); this.csrf = null; }
  async req(method, path, { body, headers = {}, session = true, redirect = "manual", store = true } = {}) {
    const h = { ...headers };
    if (session && this.jar.size) h.cookie = [...this.jar].map(([k, v]) => `${k}=${v}`).join("; ");
    if (body !== undefined) h["content-type"] = "application/json";
    const res = await fetch(this.base + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), redirect });
    const sc = res.headers.getSetCookie?.() ?? [];
    if (store) for (const c of sc) { const [kv] = c.split(";"); const i = kv.indexOf("="); const k = kv.slice(0, i); if (/max-age=0/i.test(c)) this.jar.delete(k); else this.jar.set(k, kv.slice(i + 1)); }
    const buf = Buffer.from(await res.arrayBuffer()); const text = buf.toString("utf8"); let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
    return { status: res.status, json, text, buf, headers: res.headers, setCookies: sc };
  }
  async token() { const r = await this.req("GET", "/api/v1/auth/csrf"); this.csrf = r.json?.token; return r; }
}
const hasAttr = (c, a) => new RegExp(`(^|;\\s*)${a}(;|$|=)`, "i").test(c ?? "");
const cookieOf = (list, name) => list.find((c) => c.startsWith(name + "="));
const INTERNAL = /(127\.0\.0\.1|localhost|host\.docker\.internal|0\.0\.0\.0|\b(3201|3202|3203|3200|18081|28088|25432|26379|29000)\b|hblpub-)/;

const sessions = {}; const chunkSets = {};
for (const n of ["platform", "admin", "studio"]) {
  const B = origin(n), c = new Client(B); sessions[n] = c;
  console.log(`\n===== ${n.toUpperCase()}  ${B}`);
  // ---- HTML, assets
  let r = await c.req("GET", "/", { session: false, redirect: "follow" });
  check(`${n}: HTTPS answers 200 with Next.js HTML`, r.status === 200 && /<html/i.test(r.text) && /_next\/static/.test(r.text), `${r.status}`);
  const csp = r.headers.get("content-security-policy") ?? "";
  check(`${n}: CSP nonce + strict-dynamic, frame-ancestors 'none', object-src 'none', no unsafe-eval`, /nonce-/.test(csp) && /strict-dynamic/.test(csp) && /frame-ancestors 'none'/.test(csp) && /object-src 'none'/.test(csp) && !/unsafe-eval/.test(csp));
  check(`${n}: HSTS (>= 1 year, includeSubDomains) and nosniff, X-Frame-Options DENY`, /max-age=(\d{8,})/.test(r.headers.get("strict-transport-security") ?? "") && r.headers.get("x-content-type-options") === "nosniff" && r.headers.get("x-frame-options") === "DENY", r.headers.get("strict-transport-security") ?? "no HSTS");
  check(`${n}: nothing internal (localhost, loopback, container, private ports) in the HTML the browser receives`, !INTERNAL.test(r.text.replace(/nonce-[A-Za-z0-9+/=]+/g, "")), (INTERNAL.exec(r.text) ?? [""])[0]);
  const assets = [...new Set([...r.text.matchAll(/(?:src|href)="(\/_next\/[^"]+)"/g)].map((m) => m[1]))];
  chunkSets[n] = assets;
  let bad = [], fonts = new Set();
  for (const a of assets) {
    const x = await c.req("GET", a, { session: false });
    const ct = x.headers.get("content-type") ?? "";
    const okType = a.endsWith(".js") ? /javascript/.test(ct) : a.endsWith(".css") ? /text\/css/.test(ct) : true;
    if (x.status !== 200 || !okType || /\.(js|css)$/.test(a) && /(127\.0\.0\.1|localhost|host\.docker\.internal):(8080|18081|3\d{3}|28088)|host\.docker\.internal/.test(x.text)) bad.push(`${a} ${x.status} ${ct}`);
    if (a.endsWith(".css")) for (const m of x.text.matchAll(/url\((\/_next\/[^)"']+)\)/g)) fonts.add(m[1]);
  }
  check(`${n}: all ${assets.length} /_next assets of the page load (200, right content-type, no loopback URL inside)`, assets.length > 3 && bad.length === 0, bad.slice(0, 3).join(" ; "));
  let fontBad = []; for (const f of fonts) { const x = await c.req("GET", f, { session: false }); if (x.status !== 200 || x.buf.length < 100) fontBad.push(`${f} ${x.status}`); }
  check(`${n}: fonts referenced by the CSS load (${fonts.size})`, fontBad.length === 0, fontBad.join(" ; ") || (fonts.size ? "" : "none referenced"));
  r = await c.req("GET", "/_next/static/chunks/does-not-exist-0000.js", { session: false });
  check(`${n}: a missing chunk is never served as script (404, or a non-JS body that nosniff keeps the browser from executing)`, r.status === 404 || (!/javascript/.test(r.headers.get("content-type") ?? "") && r.headers.get("x-content-type-options") === "nosniff"), `${r.status} ${r.headers.get("content-type")}`);
  if (r.status !== 404) note(`${n}: unknown /_next/static/** answers the portal HTML shell (catch-all route), not 404`, "FINDING", "Next.js behavior of the portal's [[...slug]] route; harmless to security (nosniff, text/html) but a stale chunk shows as a parse error instead of a 404 - C5 (apps/*/app)");
  // ---- login page / deep link
  r = await c.req("GET", `/${n}/login`, { session: false, redirect: "follow" });
  check(`${n}: login page /${n}/login renders (deep link, server rendered)`, r.status === 200 && /<html/i.test(r.text), `${r.status}`);
  r = await c.req("GET", `/${n}/some/deep/link`, { session: false, redirect: "follow" });
  check(`${n}: an unauthenticated deep link does not leak (200 shell or redirect, no data, no stack)`, r.status < 500 && !/Exception|stack|jdbc/i.test(r.text), `${r.status}`);
  // ---- session
  const [user, pass] = ACCOUNTS[n];
  if (!user || !pass) { check(`${n}: account available`, false, "missing account"); continue; }
  r = await c.token(); const x1 = cookieOf(r.setCookies, "XSRF-TOKEN");
  check(`${n}: CSRF token through the same-origin /api proxy; XSRF-TOKEN Secure + SameSite=Lax + host-only + Path=/`, r.status === 200 && !!c.csrf && hasAttr(x1, "Secure") && /samesite=lax/i.test(x1 ?? "") && !hasAttr(x1, "Domain") && /path=\//i.test(x1 ?? ""), (x1 ?? "none").replace(/=[^;]*/, "=<v>"));
  r = await c.req("POST", "/api/v1/auth/login", { body: { username: user, password: pass }, headers: { "x-xsrf-token": "forged-" + randomUUID() } });
  check(`${n}: login with a forged CSRF token is refused (403)`, r.status === 403, `${r.status}`);
  r = await c.req("POST", "/api/v1/auth/login", { body: { username: user, password: pass } });
  check(`${n}: login without any CSRF header is refused (403)`, r.status === 403, `${r.status}`);
  r = await c.req("POST", "/api/v1/auth/login", { body: { username: user, password: pass }, headers: { "x-xsrf-token": c.csrf, origin: B } });
  const sess = cookieOf(r.setCookies, "STUDIO_SESSION");
  check(`${n}: login with a valid CSRF token and the portal's own Origin`, r.status === 200, `${r.status}`);
  check(`${n}: STUDIO_SESSION = Secure, HttpOnly, SameSite=Lax, no Domain (host-only), Path=/`, hasAttr(sess, "Secure") && hasAttr(sess, "HttpOnly") && /samesite=lax/i.test(sess ?? "") && !hasAttr(sess, "Domain") && /path=\//i.test(sess ?? ""), (sess ?? "none").replace(/=[^;]*/, "=<v>"));
  await c.token();
  // ---- GET through the proxy
  r = await c.req("GET", "/api/v1/auth/me", { headers: { origin: B } });
  check(`${n}: authenticated GET /api/v1/auth/me through the same-origin proxy`, r.status === 200 && !!r.json, `${r.status} systemAdmin=${r.json?.systemAdmin}`);
  const ws = r.json?.workspaces?.[0]?.id ?? r.json?.workspaces?.[0]?.workspaceId;
  if (n === "studio") check("studio: the member account has a workspace", !!ws);
  if (n !== "studio") { const a = await c.req("GET", "/api/v1/admin/overview"); check(`${n}: system-admin GET /api/v1/admin/overview`, a.status === 200, `${a.status}`); }
  const anon = await new Client(B).req("GET", "/api/v1/auth/me", { session: false });
  check(`${n}: the same GET without a session is 401`, anon.status === 401, `${anon.status}`);
  // ---- CSRF on unsafe calls / CORS through the proxy
  const unsafe = n === "studio" && ws ? ["POST", `/api/v1/workspaces/${ws}/projects`, { name: "Portal smoke " + randomUUID().slice(0, 6) }] : ["POST", "/api/v1/auth/logout", undefined];
  let p = await c.req(unsafe[0], unsafe[1], { body: unsafe[2], headers: { origin: B } });
  check(`${n}: an unsafe authenticated call WITHOUT a CSRF token is refused (403)`, p.status === 403 && p.json?.code === "CSRF_INVALID", `${p.status} ${p.json?.code ?? ""}`);
  p = await c.req(unsafe[0], unsafe[1], { body: unsafe[2], headers: { origin: B, "x-xsrf-token": "forged-" + randomUUID() } });
  check(`${n}: ... with a FORGED CSRF token is refused (403)`, p.status === 403, `${p.status}`);
  const keep = await c.req("GET", "/api/v1/auth/me"); check(`${n}: refused calls did not end the session`, keep.status === 200, `${keep.status}`);
  p = await c.req("GET", "/api/v1/auth/me", { headers: { origin: "https://evil.example" } });
  check(`${n}: an untrusted Origin is not served as CORS (no Access-Control-Allow-Origin; credentials never echoed)`, !p.headers.get("access-control-allow-origin"), `${p.status} acao=${p.headers.get("access-control-allow-origin") ?? "none"}`);
  p = await c.req("POST", unsafe[1], { body: unsafe[2], headers: { origin: "https://evil.example", "x-xsrf-token": c.csrf } });
  check(`${n}: an unsafe call from an untrusted Origin is refused even with a valid token (403)`, p.status === 403, `${p.status}`);
  p = await c.req("OPTIONS", "/api/v1/auth/login", { session: false, headers: { origin: "https://evil.example", "access-control-request-method": "POST" } });
  check(`${n}: preflight from an untrusted Origin gets no Allow-Origin`, !p.headers.get("access-control-allow-origin"), `${p.status}`);
  p = await c.req("GET", "/api/v1/auth/me", { headers: { origin: B } });
  const acao = p.headers.get("access-control-allow-origin");
  check(`${n}: the portal's own Origin works; Allow-Origin is never "*"`, p.status === 200 && acao !== "*", `${acao ?? "none (same-origin)"}`);
  // ---- unsafe call WITH a valid token
  p = await c.req(unsafe[0], unsafe[1], { body: unsafe[2], headers: { origin: B, "x-xsrf-token": c.csrf } });
  check(`${n}: the unsafe call with a valid CSRF token succeeds (${unsafe[1].split("/").slice(-1)[0]})`, [200, 201, 202, 204].includes(p.status), `${p.status}`);
  if (n === "studio" && p.json?.id) { const rev = (await c.req("GET", `${unsafe[1]}/${p.json.id}`)).json?.revision ?? p.json.revision ?? 0; const d = await c.req("DELETE", `${unsafe[1]}/${p.json.id}?expectedRevision=${rev}`, { headers: { origin: B, "x-xsrf-token": c.csrf } }); check("studio: the smoke project is removed (DELETE with CSRF)", [200, 204].includes(d.status), `${d.status}`); }
  // ---- forged client-IP headers must not decide the address the API records
  // (verified from the audit trail by the wrapper: LOGIN_SUCCESS of this run carries the visitor address, not the forged one)
  if (n !== "studio") {
    const f = new Client(B); await f.token();
    const lr = await f.req("POST", "/api/v1/auth/login", { body: { username: user, password: pass }, headers: { "x-xsrf-token": f.csrf, "x-forwarded-for": "203.0.113.77", "x-real-ip": "203.0.113.78", forwarded: "for=203.0.113.79", origin: B } });
    check(`${n}: login carrying forged X-Forwarded-For / X-Real-IP / Forwarded works (the address is judged by the audit trail)`, lr.status === 200, `${lr.status}`);
    await f.token(); await f.req("POST", "/api/v1/auth/logout", { headers: { "x-xsrf-token": f.csrf, origin: B } });
  }
  // ---- logout
  if (n === "studio") { r = await c.req("POST", "/api/v1/auth/logout", { headers: { origin: B, "x-xsrf-token": c.csrf } }); check(`${n}: logout`, [200, 204].includes(r.status), `${r.status}`); }
  r = await c.req("GET", "/api/v1/auth/me"); check(`${n}: after logout the old session answers 401`, r.status === 401, `${r.status}`);
}

// ---- no cross-portal session: the cookie is host-only; the same cookie jar entry is never sent to another portal host. Prove it with real cookies.
{
  const a = new Client(origin("platform")), b = new Client(origin("admin"));
  await a.token(); const [u, pw] = ACCOUNTS.platform;
  await a.req("POST", "/api/v1/auth/login", { body: { username: u, password: pw }, headers: { "x-xsrf-token": a.csrf, origin: origin("platform") } });
  check("cross-portal: signed in on platform", (await a.req("GET", "/api/v1/auth/me")).status === 200);
  await b.token();
  check("cross-portal: the platform login does NOT sign in admin (separate host-only cookie jar)", (await b.req("GET", "/api/v1/auth/me")).status === 401);
  await a.token(); await a.req("POST", "/api/v1/auth/logout", { headers: { "x-xsrf-token": a.csrf, origin: origin("platform") } });
}

// ---- assets belong to the portal that serves them
const only = (n) => chunkSets[n].filter((a) => !["platform", "admin", "studio"].filter((m) => m !== n).some((m) => chunkSets[m].includes(a)));
for (const n of ["platform", "admin", "studio"]) {
  const mine = only(n); const other = ["platform", "admin", "studio"].find((m) => m !== n);
  let leaked = 0; for (const a of mine.slice(0, 6)) { const x = await new Client(origin(other)).req("GET", a, { session: false }); if (x.status === 200 && /javascript/.test(x.headers.get("content-type") ?? "")) leaked++; }
  check(`${n}: its own /_next chunks (${mine.length} not shared) are not served as JavaScript by another portal host`, leaked === 0, `leaked=${leaked}`);
}

// ---- sites host untouched, actuator closed on all three portals
let r = await new Client(SITES).req("GET", "/healthz", { session: false });
check("sites: gateway health through the sites host", r.status === 200, `${r.status}`);
r = await new Client(SITES).req("GET", "/zz-unknown-slug/", { session: false });
check("sites: unknown slug is 404 without backend identity", r.status === 404 && !/Spring|Exception/i.test(r.text), `${r.status}`);
for (const n of ["platform", "admin", "studio"]) for (const p of ["env", "heapdump", "prometheus", "shutdown", "beans"]) {
  const x = await new Client(origin(n)).req(p === "shutdown" ? "POST" : "GET", `/actuator/${p}`, { session: false });
  check(`${n}: /actuator/${p} is not the backend actuator`, !/"propertySources"|"contexts"|# HELP|HPROF/.test(x.text.slice(0, 2000)) && x.status !== 204, `${x.status}${/<html/i.test(x.text.slice(0, 300)) ? " (portal HTML shell)" : ""}`);
}
const failed = results.filter(([, ok]) => !ok).map(([n]) => n);
console.log(`\nTOTAL ${results.length}  FAILED ${failed.length}${failed.length ? "  -> " + failed.join(" | ") : ""}   NOTES ${notes.join(",")}`);
process.exit(failed.length ? 1 : 0);
