#!/usr/bin/env node
// C6 RC wide regression — SITES GATEWAY + PUBLIC_SITE against the c6rc stack (gateway container on :51088 in front of the API).
//   MODE=up   : healthz, slug route, published app, static assets, runtime config, public data route (PUBLIC_SITE authority), what must NOT be reachable through the gateway
//   MODE=down : the API is stopped by the caller: the gateway answers a bounded 5xx (never hangs, never leaks), healthz still answers
// Env: SITES (http://127.0.0.1:51088)  SITE_URL (full URL of a published page, from the data run)  OUT
import { writeFileSync, mkdirSync } from "node:fs";
const SITES = (process.env.SITES ?? "http://127.0.0.1:51088").replace(/\/$/, ""); const SITE_URL = process.env.SITE_URL; const MODE = process.env.MODE ?? "up"; const OUT = process.env.OUT ?? "."; mkdirSync(OUT, { recursive: true });
const rows = []; const rec = (id, desc, expected, actual, ok, note = "") => { rows.push({ id, mode: MODE, desc, expected, actual: String(actual), result: ok ? "PASS" : "FAIL", note }); console.log(`${ok ? "PASS" : "FAIL"} ${id} [${MODE}] ${desc} | expected ${expected} | actual ${String(actual).slice(0, 200)}${note ? " | " + note : ""}`); };
const get = async (url, init = {}) => { const t0 = Date.now(); try { const r = await fetch(url, { redirect: "manual", signal: AbortSignal.timeout(20000), ...init }); const text = await r.text(); return { status: r.status, text, headers: r.headers, ms: Date.now() - t0 }; } catch (e) { return { status: 0, text: "", headers: new Headers(), ms: Date.now() - t0, err: String(e?.message ?? e) }; } };
const slug = SITE_URL ? new URL(SITE_URL).pathname.split("/").filter(Boolean)[0] : null;
if (MODE === "up") {
  let r = await get(SITES + "/healthz"); rec("G01", "gateway /healthz", "200", r.status, r.status === 200);
  r = await get(SITE_URL); rec("G02", "published app by slug route", "200 HTML", `${r.status} ${r.headers.get("content-type")} ${r.ms}ms`, r.status === 200 && /<html/i.test(r.text));
  const html = r.text; const assets = [...html.matchAll(/(?:src|href)="([^"#?]+\.(?:js|css|png|jpg|svg|woff2?|ico))"/g)].map((m) => m[1]).slice(0, 12);
  let bad = 0; for (const a of assets) { const u = new URL(a, SITE_URL); const x = await get(u.toString()); if (x.status !== 200) bad++; }
  rec("G03", "static assets referenced by the page", "all 200 (or none referenced: static HTML)", `${assets.length} referenced, ${bad} broken`, bad === 0);
  r = await get(new URL("__factory/config.json", SITE_URL).toString()); const cfg = (() => { try { return JSON.parse(r.text); } catch { return null; } })();
  rec("G04", "runtime config of the page site", "200 no-store JSON with a same-origin apiBase for THIS slug, no tenant/workspace/credential — or 404 when the page binds no public query", `${r.status} cache=${r.headers.get("cache-control")} apiBase=${cfg?.apiBase ?? "-"}`, (r.status === 404) || (r.status === 200 && /no-store/.test(r.headers.get("cache-control") ?? "") && (!cfg?.apiBase || cfg.apiBase.includes(`/${slug}/_data`)) && !/tenant|workspace|password|secret|credential/i.test(r.text)));
  r = await get(`${SITES}/${slug}/_data/queries/orders-list/run`, { method: "POST", headers: { "content-type": "application/json", origin: SITES }, body: JSON.stringify({ params: { status: "open" } }) }); rec("G05", "anonymous POST on the public data route of a query that is NOT public", "refused (403/404), no rows", `${r.status} ${/SO-100/.test(r.text) ? "ROWS LEAKED" : ""}`, [400, 403, 404, 409, 422].includes(r.status) && !/SO-100/.test(r.text));
  r = await get(`${SITES}/${slug}/_data/queries/orders-list/run`); rec("G06", "GET on the data route", "not 200 (POST only)", r.status, r.status !== 200);
  r = await get(`${SITES}/${slug}/_data/queries/orders-list/run`, { method: "POST", headers: { "content-type": "application/json", origin: "https://evil.example" }, body: "{}" }); rec("G07", "data route from a foreign Origin", "refused (403) or no CORS grant", `${r.status} acao=${r.headers.get("access-control-allow-origin")}`, r.headers.get("access-control-allow-origin") !== "https://evil.example");
  for (const [id, path, exp] of [["G08", "/api/v1/auth/me", "404/403 (the API is not published on the sites host)"], ["G09", "/actuator/health", "404/403"], ["G10", "/actuator/env", "404/403"], ["G11", "/", "404/403 (no listing)"], ["G12", "/../etc/passwd", "400/404"], ["G13", "/%2e%2e/%2e%2e/etc/passwd", "400/403/404"], ["G14", `/${slug}/../api/v1/auth/me`, "400/404/403"], ["G15", "/swagger-ui.html", "404"], ["G16", "/v3/api-docs", "404"]]) {
    r = await get(SITES + path); rec(id, `GET ${path} on the sites host`, exp, r.status, [400, 401, 403, 404].includes(r.status) && !/"username"|root:|systemAdmin/.test(r.text));
  }
  r = await get(SITES + `/${slug}/`, { method: "POST", body: "{}" }); rec("G17", "POST to a page path", "403/404/405", r.status, [403, 404, 405].includes(r.status));
  r = await get(SITES + "/c6-no-such-slug-rc/"); rec("G18", "unknown slug", "404 without detail", `${r.status} ${r.text.length}b`, r.status === 404);
  r = await get(SITES + "/healthz", { headers: { "x-forwarded-for": "6.6.6.6, 203.0.113.9", "x-real-ip": "9.9.9.9" } }); rec("G19", "forged client-IP headers on a request", "200, ignored (no 5xx)", r.status, r.status === 200);
  // direct exposure of internals through any published port of the stack
  const { spawnSync } = await import("node:child_process"); const ports = spawnSync("docker", ["ps", "--format", "{{.Names}} {{.Ports}}"], { encoding: "utf8" }).stdout.split("\n").filter((l) => l.startsWith("c6rc-"));
  const exposed = ports.filter((l) => /0\.0\.0\.0:|:::/.test(l)); rec("G20", "no c6rc container (PostgreSQL, Redis, MinIO, RabbitMQ, gateway) is published beyond loopback", "all 127.0.0.1", exposed.length ? exposed.join(" | ") : `${ports.length} containers, all loopback`, exposed.length === 0);
} else {
  let r = await get(SITES + "/healthz"); rec("H01", "gateway /healthz while the API is DOWN", "200 (the gateway itself is alive)", r.status, r.status === 200);
  r = await get(SITE_URL); rec("H02", "published page while the API is DOWN", "bounded 5xx (502/503/504) within 20 s, no stack trace / upstream address", `${r.status} ${r.ms}ms body=${r.text.length}b`, [502, 503, 504].includes(r.status) && r.ms < 20000 && !/127\.0\.0\.1|host\.docker\.internal|nginx\/\d|java\.|Exception/.test(r.text), "static pages are served through the API (artifact store), so the outage is visible");
  r = await get(`${SITES}/${slug}/_data/queries/orders-list/run`, { method: "POST", headers: { "content-type": "application/json", origin: SITES }, body: "{}" }); rec("H03", "public data route while the API is DOWN", "bounded 5xx, no rows", `${r.status} ${r.ms}ms`, [502, 503, 504].includes(r.status) && r.ms < 20000);
  r = await get(SITES + "/c6-no-such-slug-rc/"); rec("H04", "unknown slug while the API is DOWN", "404 or bounded 5xx", `${r.status} ${r.ms}ms`, [404, 502, 503, 504].includes(r.status));
}
const failed = rows.filter((x) => x.result === "FAIL"); writeFileSync(`${OUT}/rc-gateway-${MODE}.json`, JSON.stringify({ mode: MODE, total: rows.length, failed: failed.length, rows }, null, 1));
console.log(`\nTOTAL ${rows.length}  FAILED ${failed.length}${failed.length ? "  -> " + failed.map((x) => x.id).join(", ") : ""}`); process.exit(failed.length ? 1 : 0);
