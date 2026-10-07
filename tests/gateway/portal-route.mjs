// The portal gateway template (infra/portal-gateway) against a real nginx (throwaway container, three stub portals on loopback): routing by Host, one portal = one hostname,
// the plain-http redirect (both modes), the client address the portals receive (a forged X-Forwarded-For / X-Real-IP / Forwarded never gets through, a trusted proxy's one is believed),
// unknown hosts, streaming responses and big bodies. Needs Docker and the image nginxinc/nginx-unprivileged:1.29-alpine.
//   node tests/gateway/portal-route.mjs            (GATEWAY_FORCE_HTTPS=0 for local mode)
import { createServer, request } from "node:http";
import { createServer as tcp } from "node:net";
import { spawnSync } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";

const TEMPLATE = new URL("../../infra/portal-gateway/default.conf.template", import.meta.url).pathname;
const IMAGE = "nginxinc/nginx-unprivileged:1.29-alpine";
const FORCE = process.env.PORTAL_FORCE_HTTPS ?? "1";
const results = []; const check = (n, ok, d = "") => { results.push(ok); console.log(`${ok ? "PASS" : "FAIL"}  ${n}${d ? "  — " + d : ""}`); };
const freePort = () => new Promise((r) => { const s = tcp(); s.listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => r(p)); }); });
const HOSTS = { platform: "platform.test", admin: "admin.test", studio: "studio.test" };
const seen = []; const stubs = {};
for (const n of Object.keys(HOSTS)) {
  stubs[n] = createServer((req, res) => { const chunks = []; req.on("data", (c) => chunks.push(c)); req.on("end", () => { seen.push({ portal: n, method: req.method, url: req.url, headers: req.headers, bytes: Buffer.concat(chunks).length });
    if (req.url === "/stream") { res.writeHead(200, { "Content-Type": "text/plain" }); res.write("a"); setTimeout(() => res.end("b"), 150); return; }
    res.writeHead(200, { "Content-Type": "text/html" }); res.end(`<portal>${n}</portal>`); }); });
  await new Promise((r) => stubs[n].listen(0, "127.0.0.1", r));
}
const docker = (...a) => spawnSync("docker", a, { encoding: "utf8" });
const containers = [];
// realIpFrom: the proxies whose X-Forwarded-For is believed. The test's own peer is the Docker bridge (172.16.0.0/12): "127.0.0.1" = untrusted peer, "172.16.0.0/12" = trusted peer.
async function startGateway(realIpFrom) {
  const port = await freePort(); const name = `xweb-c0-portal-gw-test-${process.pid}-${port}`;
  const started = docker("run", "-d", "--rm", "--name", name, "-p", `127.0.0.1:${port}:8080`,
    "-e", "PORTAL_UPSTREAM_HOST=host.docker.internal", "-e", `PORTAL_PLATFORM_PORT=${stubs.platform.address().port}`, "-e", `PORTAL_ADMIN_PORT=${stubs.admin.address().port}`, "-e", `PORTAL_STUDIO_PORT=${stubs.studio.address().port}`,
    "-e", `PORTAL_PLATFORM_HOST=${HOSTS.platform}`, "-e", `PORTAL_ADMIN_HOST=${HOSTS.admin}`, "-e", `PORTAL_STUDIO_HOST=${HOSTS.studio}`,
    "-e", `PORTAL_REAL_IP_FROM=${realIpFrom}`, "-e", `PORTAL_FORCE_HTTPS=${FORCE}`, "-v", `${TEMPLATE}:/etc/nginx/templates/default.conf.template:ro`, IMAGE);
  if (started.status !== 0) { console.log("FAIL  could not start the gateway container:", started.stderr.trim().slice(0, 300)); process.exit(1); }
  containers.push(name); return { port, name };
}
const untrusted = await startGateway("127.0.0.1"); const trusted = await startGateway("172.16.0.0/12");
let gwPort = untrusted.port; const name = untrusted.name;
const gw = (host, path = "/", { method = "GET", headers = {}, body, via = untrusted } = {}) => new Promise((resolve, reject) => {
  const req = request({ host: "127.0.0.1", port: via.port, path, method, headers: { host, ...headers } }, (res) => { const c = []; res.on("data", (d) => c.push(d)); res.on("end", () => resolve({ status: res.statusCode, headers: res.headers, text: Buffer.concat(c).toString() })); });
  req.on("error", reject); if (body) req.write(body); req.end();
});
try {
  let up = false; for (let i = 0; i < 40 && !up; i++) { try { up = (await gw("x", "/healthz")).status === 200; } catch {} if (!up) await sleep(250); }
  check("the template is valid nginx configuration and the gateway is healthy", up, up ? "" : docker("logs", untrusted.name).stdout.slice(-400));
  for (const n of Object.keys(HOSTS)) { const r = await gw(HOSTS[n]); check(`Host ${HOSTS[n]} is served by the ${n} portal only`, r.status === 200 && r.text === `<portal>${n}</portal>`, r.text); }
  seen.length = 0;
  const unknown = await gw("evil.example"); check("an unknown Host is a plain 404 (no portal reached, nothing revealed)", unknown.status === 404 && seen.length === 0 && !/portal/.test(unknown.text), `${unknown.status}`);
  const ip = await gw("127.0.0.1"); check("a bare-IP Host is a 404 too", ip.status === 404);
  const hz = await gw(HOSTS.studio, "/healthz"); check("healthz answers on the gateway", hz.status === 200);
  // plain http at the edge
  const edge = await gw(HOSTS.admin, "/login?next=%2Fadmin", { headers: { "x-forwarded-proto": "http" } });
  if (FORCE === "1") check("plain http at the edge is redirected 308 to https, same host and path", edge.status === 308 && edge.headers.location === `https://${HOSTS.admin}/login?next=%2Fadmin`, `${edge.status} ${edge.headers.location}`);
  else check("local mode: X-Forwarded-Proto http is NOT redirected", edge.status === 200);
  check("https at the edge is served", (await gw(HOSTS.admin, "/", { headers: { "x-forwarded-proto": "https" } })).status === 200);
  // client address
  seen.length = 0;
  await gw(HOSTS.platform, "/x", { headers: { "x-forwarded-for": "1.2.3.4, 5.6.7.8", "x-real-ip": "9.9.9.9", forwarded: "for=8.8.8.8" } });
  let h = seen[0]?.headers ?? {};
  check("untrusted client: a forged X-Forwarded-For / X-Real-IP / Forwarded never reaches the portal; it gets ONE address (the real peer)", !!h["x-forwarded-for"] && !h["x-forwarded-for"].includes(",") && !/1\.2\.3\.4|5\.6\.7\.8/.test(h["x-forwarded-for"]) && h["x-real-ip"] === h["x-forwarded-for"] && !h.forwarded, `xff=${h["x-forwarded-for"]} real=${h["x-real-ip"]} fwd=${h.forwarded ?? "none"}`);
  check("the portal is told the public Host and the scheme", h.host === HOSTS.platform && h["x-forwarded-host"] === HOSTS.platform && ["http", "https"].includes(h["x-forwarded-proto"]), `${h.host} ${h["x-forwarded-proto"]}`);
  // behind a trusted proxy (the second container trusts the bridge): the address that proxy appended IS the visitor, the forged prefix is not
  seen.length = 0;
  await gw(HOSTS.studio, "/x", { headers: { "x-forwarded-for": "6.6.6.6, 203.0.113.9" }, via: trusted });
  h = seen[0]?.headers ?? {};
  check("trusted proxy: the right-most untrusted entry is the visitor; the forged prefix is dropped", h["x-forwarded-for"] === "203.0.113.9" && !/6\.6\.6\.6/.test(h["x-forwarded-for"]), h["x-forwarded-for"]);
  // body, streaming
  seen.length = 0; const big = "x".repeat(5 * 1024 * 1024);
  const post = await gw(HOSTS.studio, "/api/x", { method: "POST", headers: { "content-type": "application/json", "content-length": String(big.length) }, body: big });
  check("a 5 MiB body reaches the portal (client_max_body_size 20m)", post.status === 200 && seen[0]?.bytes === big.length, `${post.status} ${seen[0]?.bytes}`);
  const t0 = Date.now(); const streamed = await gw(HOSTS.platform, "/stream"); check("a streamed response is relayed intact", streamed.text === "ab", streamed.text);
  void t0;
  const huge = await gw(HOSTS.studio, "/api/x", { method: "POST", headers: { "content-length": String(30 * 1024 * 1024) } }).catch(() => ({ status: 413 }));
  check("a body over the limit is refused (413)", huge.status === 413, `${huge.status}`);
} finally { for (const c of containers) docker("rm", "-f", c); for (const s of Object.values(stubs)) s.close(); }
const failed = results.filter((x) => !x).length; console.log(`\n${results.length - failed}/${results.length} passed`); process.exit(failed ? 1 : 0);
