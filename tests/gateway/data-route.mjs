// @class: integration
// The sites gateway template against a real nginx (throwaway container, stub upstream on 127.0.0.1): the public data route of published-runtime.md
// section 4 and that the routes that existed before it still behave. Needs Docker and the image nginxinc/nginx-unprivileged:1.29-alpine (the one compose.yml uses).
//   node tests/gateway/data-route.mjs
import { createServer } from "node:http";
import { createServer as tcp } from "node:net";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { setTimeout as sleep } from "node:timers/promises";

const TEMPLATE = new URL("../../infra/sites-gateway/default.conf.template", import.meta.url).pathname;
const IMAGE = "nginxinc/nginx-unprivileged:1.29-alpine";
const FORCE_HTTPS = process.env.GATEWAY_FORCE_HTTPS ?? "1";   // run twice: 1 = global mode (redirect plain http), 0 = local mode
const results = []; const check = (n, ok, d = "") => { results.push(ok); console.log(`${ok ? "PASS" : "FAIL"}  ${n}${d ? "  — " + d : ""}`); };
const freePort = () => new Promise((r) => { const s = tcp(); s.listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => r(p)); }); });

const calls = [];
const upstream = createServer((req, res) => {
  const chunks = []; req.on("data", (c) => chunks.push(c));
  req.on("end", () => { calls.push({ method: req.method, url: req.url, headers: req.headers, body: Buffer.concat(chunks).toString() }); res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store", "X-From": "stub" }); res.end('{"stub":true}'); });
});
await new Promise((r) => upstream.listen(0, "127.0.0.1", r));
const upPort = upstream.address().port; const gwPort = await freePort();
const name = `xweb-c2-gateway-test-${process.pid}`;
const docker = (...a) => spawnSync("docker", a, { encoding: "utf8" });
const started = docker("run", "-d", "--rm", "--name", name, "-p", `127.0.0.1:${gwPort}:8080`, "-e", `GATEWAY_FORCE_HTTPS=${FORCE_HTTPS}`, "-e", `API_UPSTREAM=host.docker.internal:${upPort}`, "-e", "SITES_HOST=sites.test", "-e", "GATEWAY_REAL_IP_FROM=127.0.0.1",
  "-v", `${TEMPLATE}:/etc/nginx/templates/default.conf.template:ro`, IMAGE);
if (started.status !== 0) { console.log("FAIL  could not start the gateway container:", started.stderr.trim().slice(0, 300)); process.exit(1); }
const gw = (path, init = {}) => fetch(`http://127.0.0.1:${gwPort}${path}`, { redirect: "manual", ...init });
try {
  let up = false; for (let i = 0; i < 40 && !up; i++) { try { up = (await gw("/healthz")).status === 200; } catch {} if (!up) await sleep(250); }
  check("the template is valid nginx configuration and the gateway is healthy", up, up ? "" : docker("logs", name).stdout.slice(-400));
  const called = (m, u) => calls.filter((c) => c.method === m && c.url === u);

  // ---- the data route ----------------------------------------------------------------------------------------------------------------------------
  calls.length = 0;
  const body = JSON.stringify({ params: { a: 1 } });
  const r = await gw("/demo/_data/queries/q-title/run", { method: "POST", headers: { "Content-Type": "application/json", Cookie: "site_session=abc; STUDIO_SESSION=xyz", Authorization: "Bearer secret", "X-XSRF-TOKEN": "tok", Host: "sites.test" }, body });
  const c = calls[0];
  check("POST /<slug>/_data/queries/<id>/run reaches the API as POST /sites/<slug>/_data/queries/<id>/run, body intact, answer relayed", r.status === 200 && (await r.text()) === '{"stub":true}' && c?.method === "POST" && c.url === "/sites/demo/_data/queries/q-title/run" && c.body === body, JSON.stringify(c && { m: c.method, u: c.url }));
  check("no Cookie, no Authorization, no CSRF header ever reaches the API on this route", !!c && !c.headers.cookie && !c.headers.authorization && !c.headers["x-xsrf-token"], c ? Object.keys(c.headers).join(",") : "no call");
  check("the API still gets the Host and the forwarding headers it needs for its own limits", c?.headers.host === "127.0.0.1" && !!c.headers["x-forwarded-proto"] && c.headers["content-type"] === "application/json", c?.headers.host);
  check("the answer is not cached by the gateway", !/HIT/.test(r.headers.get("x-cache") ?? "") && r.headers.get("cache-control") === "no-store");

  // client address: a visitor-supplied X-Forwarded-For is NOT believed (the gateway is not behind a trusted proxy here), the API gets ONE validated address
  calls.length = 0; await gw("/demo/_data/queries/q-title/run", { method: "POST", headers: { "Content-Type": "application/json", "X-Forwarded-For": "1.2.3.4, 5.6.7.8" }, body: "{}" });
  const xff = calls[0]?.headers["x-forwarded-for"] ?? "";
  check("a spoofed X-Forwarded-For never reaches the API; it gets exactly one address (the real peer)", !!xff && !/1\.2\.3\.4|5\.6\.7\.8/.test(xff) && !xff.includes(","), xff);

  for (const method of ["GET", "HEAD", "PUT", "PATCH", "DELETE"]) {
    calls.length = 0; const x = await gw("/demo/_data/queries/q-title/run", { method, ...(["PUT", "PATCH"].includes(method) ? { body: "{}" } : {}) });
    check(`${method} on the data route is refused by the gateway and never reaches the API`, x.status === 403 && calls.length === 0, `status ${x.status}, upstream calls ${calls.length}`);
  }
  calls.length = 0; const big = await gw("/demo/_data/queries/q-title/run", { method: "POST", headers: { "Content-Type": "application/json" }, body: "x".repeat(17 * 1024) });
  check("a body over 16 KiB is refused (413) and never reaches the API", big.status === 413 && calls.length === 0, `status ${big.status}`);

  // only this exact path shape is routed
  for (const path of ["/demo/_data/queries/Q-TITLE/run", "/demo/_data/queries/q-title/run/extra", "/demo/_data/queries//run", "/demo/_data/queries/q-title", "/demo/_data/queries/q_title/run",
    "/demo/_data/mutate", "/_data/queries/q-title/run", "/demo/_data/sources", "/demo/_data/queries/%2e%2e/run"]) {
    calls.length = 0; const x = await gw(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
    const viaData = calls.some((q) => q.method === "POST" && q.url.includes("_data"));
    check(`POST ${path} is not routed to the data runtime`, !viaData && x.status >= 400, `status ${x.status}${viaData ? " REACHED " + calls[0].url : ""}`);
  }
  // the runtime never sends a query string: the gateway drops it, so tenant / workspace / release look-alikes in it never reach the API
  calls.length = 0; await gw("/demo/_data/queries/q-title/run?tenantId=t&workspaceId=w&release=r", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
  check("a query string on the exact path is dropped before the API sees the request", calls[0]?.url === "/sites/demo/_data/queries/q-title/run", calls[0]?.url);

  // ---- the same visitor cannot hammer it ---------------------------------------------------------------------------------------------------------
  calls.length = 0;
  const burst = await Promise.all(Array.from({ length: 80 }, () => gw("/demo/_data/queries/q-title/run", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" }).then((x) => x.status)));
  const limited = burst.filter((s) => s === 429).length;
  check("a burst from one address is throttled with 429 (rate-limited), and the API sees fewer calls than were sent", limited > 20 && calls.length < 80 && burst.every((s) => s === 200 || s === 429), `200:${burst.filter((s) => s === 200).length} 429:${limited} upstream:${calls.length}`);
  await sleep(1500);

  // ---- what existed before is untouched ---------------------------------------------------------------------------------------------------------
  calls.length = 0;
  const page = await gw("/demo/");
  check("GET /<slug>/ still goes to the API as GET /sites/<slug>/", page.status === 200 && called("GET", "/sites/demo/").length === 1);
  const post = await gw("/demo/", { method: "POST", body: "x" });
  check("POST to a page is still refused", post.status === 403);
  calls.length = 0; await gw("/demo/_forms/contact", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: "a=b" });
  check("the website form POST still goes to the API", called("POST", "/sites/demo/_forms/contact").length === 1);
  calls.length = 0; await gw("/demo/api/items", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
  check("the server-app API proxy still goes to the API", called("POST", "/sites/demo/api/items").length === 1);
  check("an unknown top-level path is still a 404 of the gateway", (await gw("/")).status === 404);
  // plain http at the edge (D-C0-38): redirected only when GATEWAY_FORCE_HTTPS=1; direct local requests carry no X-Forwarded-Proto and are never redirected
  const edgeHttp = await gw("/demo/", { headers: { "X-Forwarded-Proto": "http", Host: "sites.test" } });
  const edgeHttps = await gw("/demo/", { headers: { "X-Forwarded-Proto": "https" } });
  if (FORCE_HTTPS === "1") check("plain http at the edge is redirected 308 to https on the requested host and path (fetch cannot set Host: 127.0.0.1)", edgeHttp.status === 308 && edgeHttp.headers.get("location") === "https://127.0.0.1/demo/", `${edgeHttp.status} ${edgeHttp.headers.get("location")}`);
  else check("local mode: X-Forwarded-Proto http is NOT redirected (GATEWAY_FORCE_HTTPS=0)", edgeHttp.status === 200, `${edgeHttp.status}`);
  check("https at the edge and direct requests are served", edgeHttps.status === 200 && (await gw("/demo/")).status === 200);
  const hz = await gw("/healthz"); check("healthz is still 200 ok", hz.status === 200 && (await hz.text()).trim() === "ok");
} finally { docker("rm", "-f", name); upstream.close(); }
const failed = results.filter((x) => !x).length; console.log(`\n${results.length - failed}/${results.length} passed`); process.exit(failed ? 1 : 0);
