// @class: real-backend — real browser -> real backend
// @legacy: pre-V2 single-origin root app (:3100) + scripts/run-local.sh; NOT run against integration/v2; some steps seed via SQL or stub the AI provider
// Server runtime E2E (stage J/K): app kinds through the REAL pipeline — sandbox build, isolated runtime container, API through the sites gateway,
// own database, signed identity, blue/green switch, isolation between apps and from the platform. Needs the FULL local stack
// (./scripts/run-local.sh) with the Docker daemon; it switches the `server-apps.enabled` policy on for the run and restores it afterwards.
import fs from "node:fs";
import { execSync } from "node:child_process";

const env = Object.fromEntries(fs.readFileSync(new URL("../.env", import.meta.url), "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const BASE = "http://127.0.0.1:3100", SITES = "http://127.0.0.1:18088";
const sql = (q) => execSync(`docker exec hbl-postgres-1 psql -U studio -d system_web_studio -tAc "${q.replace(/"/g, '\\"')}"`).toString().trim();
const sh = (c) => { try { return execSync(c, { stdio: ["ignore", "pipe", "pipe"] }).toString().trim(); } catch (e) { return `ERR:${((e.stdout?.toString() ?? "") + (e.stderr?.toString() ?? "") || e.message).trim().slice(0, 300)}`; } };
const results = []; let failed = false;
const check = async (name, fn) => { try { await fn(); results.push(1); console.log("PASS", name); } catch (e) { failed = true; results.push(0); console.log("FAIL", name, "-", String(e.message).split("\n")[0]); } };
const expect = (c, m) => { if (!c) throw new Error(m); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let cookie = "";
const keep = (r) => { for (const c of r.headers.getSetCookie?.() ?? []) { const [kv] = c.split(";"); const [k] = kv.split("="); cookie = cookie.split("; ").filter((x) => x && !x.startsWith(k + "=")).concat(kv).join("; "); } };
async function csrf() { const r = await fetch(BASE + "/api/v1/auth/csrf", { headers: { cookie } }); keep(r); return (await r.json()).token; }
async function call(method, path, body, extra = {}) { const t = await csrf(); const r = await fetch(BASE + "/api/v1" + path, { method, headers: { cookie, "Content-Type": "application/json", "X-XSRF-TOKEN": t, ...extra }, body: body ? JSON.stringify(body) : undefined }); keep(r); return { status: r.status, body: await r.json().catch(() => null) }; }

// policy + quota lifted for this run, restored at the end
const prev = { policy: sql("select value from system_settings where key='server-apps.enabled'"), quota: sql("select value from system_settings where key='build.max-per-user-per-day'") };
const setting = (k, v) => sql(`insert into system_settings (key, value) values ('${k}', '${v}') on conflict (key) do update set value = excluded.value`);
setting("server-apps.enabled", "true"); setting("build.max-per-user-per-day", "1000"); setting("build.max-per-org-per-day", "1000"); setting("build.max-per-workspace-per-day", "1000"); setting("build.max-per-project-per-day", "1000");
await sleep(5500);

await call("POST", "/auth/login", { username: "local.admin", password: env.LOCAL_ADMIN_PASSWORD });
const ws = (await call("GET", "/auth/me")).body.workspaces[0].id;
const stamp = Date.now().toString(36);
const apps = {};

async function make(kind, visibility, setup) {
  const c = await call("POST", `/workspaces/${ws}/projects`, { name: `rt-${kind.toLowerCase()}-${stamp}`, appType: "STATIC_APP", appKind: kind });
  expect(c.status === 201, `create ${kind}: ${c.status} ${JSON.stringify(c.body)}`);
  const P = `/workspaces/${ws}/projects/${c.body.id}`;
  if (setup) await setup(P);
  const pub = await call("POST", `${P}/publish`, { visibility, expectedRevision: (await call("GET", P)).body.revision }, { "Idempotency-Key": `rt-${kind}-${Date.now()}` });
  expect(pub.status === 202, `publish ${pub.status}`);
  let d; for (let i = 0; i < 240; i++) { d = (await call("GET", `${P}/deployments/${pub.body.id}`)).body; if (["RUNNING", "FAILED"].includes(d.status)) break; await sleep(1500); }
  expect(d.status === "RUNNING", `site deployment ${d.status} ${d.error ?? ""}`);
  return { id: c.body.id, P, slug: d.url.split("/").filter(Boolean).pop() };
}
async function runtimeUp(P) {
  let rt; for (let i = 0; i < 120; i++) { rt = (await call("GET", `${P}/runtime`)).body; if (rt.currentDeploymentId || rt.deployments?.[0]?.status === "FAILED") break; await sleep(1500); }
  expect(rt.currentDeploymentId, `runtime ${rt.deployments?.[0]?.status} ${rt.deployments?.[0]?.error ?? ""}`); return rt;
}
async function memberPath(slug) {
  const t = await call("POST", `/sites/${slug}/access-ticket`, { path: "/" });
  const acc = await fetch(SITES + t.body.redirect.replace(/^https?:\/\/[^/]+/, ""), { redirect: "manual" });
  const sess = (acc.headers.getSetCookie?.() ?? []).map((c) => c.split(";")[0]).join("; ");
  const entry = await fetch(`${SITES}/${slug}/`, { headers: { cookie: sess }, redirect: "manual" });
  const loc = entry.headers.get("location"); expect(entry.status === 302 && loc?.startsWith("/_app/"), `private entry ${entry.status} ${loc}`); return loc.replace(/\/$/, "");
}
const json = { "Content-Type": "application/json" };

try {
  await check("server app (public): sandbox build → isolated container → API through the gateway → its own database; undeclared routes and the bundle are not reachable", async () => {
    apps.server = await make("SERVER_APP", "PUBLIC", async (P) => expect((await call("PUT", `${P}/runtime/secrets`, { name: "GREETING", value: "e2e-secret-value" })).status === 200, "secret"));
    const rt = await runtimeUp(apps.server.P); apps.server.rt = rt;
    const base = `${SITES}/${apps.server.slug}`;
    expect((await fetch(`${base}/api/items`)).status === 200, "GET items");
    const add = await fetch(`${base}/api/items`, { method: "POST", headers: json, body: JSON.stringify({ title: "e2e item" }) }); expect(add.status === 201, `POST ${add.status}`);
    expect((await (await fetch(`${base}/api/items`)).json()).some((i) => i.title === "e2e item"), "item not stored in the app database");
    expect((await fetch(`${base}/api/secret-route`)).status === 404, "undeclared route reachable");
    expect((await fetch(`${base}/server/server.cjs`)).status === 404, "server bundle served as a file");
    expect((await fetch(`${base}/`)).status === 200, "UI");
    const status = JSON.stringify((await call("GET", `${apps.server.P}/runtime`)).body); expect(!status.includes("e2e-secret-value") && !/postgresql:\/\/[^*]/.test(status), "secret or DB URL returned by the API");
  });
  await check("container hardening: non-root, read-only rootfs, caps dropped, no-new-privileges, limits, no mounts, no Docker socket, no privileges", async () => {
    const c = sh(`docker ps --filter label=factory.app=1 --format '{{.Names}}'`).split("\n").find((n) => n.startsWith(`app-${apps.server.id.slice(0, 8)}`)); expect(c, "container not found");
    const i = JSON.parse(sh(`docker inspect ${c}`))[0];
    expect(i.Config.User === "10001:10001", `user ${i.Config.User}`); expect(i.HostConfig.ReadonlyRootfs === true, "rootfs writable");
    expect(JSON.stringify(i.HostConfig.CapDrop) === '["ALL"]' && !i.HostConfig.CapAdd, "capabilities"); expect(i.HostConfig.SecurityOpt.includes("no-new-privileges"), "no-new-privileges");
    expect(i.HostConfig.Memory > 0 && i.HostConfig.NanoCpus > 0 && i.HostConfig.PidsLimit > 0, "limits missing"); expect(i.Mounts.length === 0, "host mounts"); expect(i.HostConfig.Privileged === false, "privileged");
    expect(!sh(`docker exec ${c} ls /var/run/docker.sock`).startsWith("/var/run"), "docker socket visible");
    expect(sh(`docker exec ${c} sh -c 'touch /app/x 2>&1'`).includes("Read-only"), "app dir writable");
  });
  await check("isolation: no internet, no platform database, no host, not reachable from another app; per-app database role cannot open other databases", async () => {
    const c = sh(`docker ps --filter label=factory.app=1 --format '{{.Names}}'`).split("\n").find((n) => n.startsWith(`app-${apps.server.id.slice(0, 8)}`));
    expect(sh(`docker exec ${c} sh -c 'wget -T 3 -qO- https://example.com >/dev/null 2>&1 && echo OPEN || echo blocked'`) === "blocked", "internet reachable");
    expect(sh(`docker exec ${c} sh -c 'nc -z -w 3 postgres 5432 2>/dev/null && echo OPEN || echo blocked'`) === "blocked", "platform DB reachable");
    expect(sh(`docker exec ${c} sh -c 'nc -z -w 3 host.docker.internal 8080 2>/dev/null && echo OPEN || echo blocked'`) === "blocked", "host reachable");
    const url = sh(`docker exec ${c} printenv DATABASE_URL`); const m = /postgresql:\/\/([^:]+):([^@]+)@/.exec(url); expect(m, "no DATABASE_URL");
    for (const db of ["appdb", "postgres", "template1"]) expect(/permission denied/.test(sh(`docker exec -e PGPASSWORD='${m[2]}' hbl-appdb-1 psql -h 127.0.0.1 -U ${m[1]} -d ${db} -tAc 'select 1' 2>&1`)), `role can connect to ${db}`);
    expect(/^f\|f\|f$/.test(sh(`docker exec -e PGPASSWORD='${m[2]}' hbl-appdb-1 psql -h 127.0.0.1 -U ${m[1]} -d ${m[1]} -tAc "select rolsuper, rolcreatedb, rolcreaterole from pg_roles where rolname = current_user"`)), "role is privileged");
  });
  await check("blue/green: redeploying the same build keeps serving (no failed request during the switch), old container removed, rollback target recorded", async () => {
    const P = apps.server.P; const before = (await call("GET", `${P}/runtime`)).body.currentDeploymentId;
    const r = await call("POST", `${P}/runtime/rollback`, { deploymentId: before }); expect(r.status === 200, `rollback ${r.status}`);
    let ok = 0, bad = 0, rt; const t0 = Date.now();
    while (Date.now() - t0 < 120_000) { const x = await fetch(`${SITES}/${apps.server.slug}/api/items`).catch(() => null); x?.status === 200 ? ok++ : bad++; rt = (await call("GET", `${P}/runtime`)).body; if (rt.currentDeploymentId && rt.currentDeploymentId !== before) break; await sleep(400); }
    expect(rt.currentDeploymentId !== before, "did not switch"); expect(bad === 0, `${bad} failed request(s) during the switch (${ok} ok)`);
    expect(rt.deployments.some((d) => d.rollbackOf === before), "rollbackOf not recorded");
  });
  await check("DASHBOARD: React dashboard built in the sandbox and published", async () => {
    const a = await make("DASHBOARD", "PUBLIC"); const r = await fetch(`${SITES}/${a.slug}/`); expect(r.status === 200, `UI ${r.status}`);
    expect(!(await call("GET", `${a.P}/runtime`)).body?.provisioned, "a dashboard must not get a server runtime");
  });
  await check("INTERNAL_TOOL (private): members only, identity comes signed from the platform, records and history in its own database", async () => {
    apps.tool = await make("INTERNAL_TOOL", "PRIVATE"); await runtimeUp(apps.tool.P);
    expect((await fetch(`${SITES}/${apps.tool.slug}/api/records`)).status === 403, "anonymous reached a private app API");
    const mp = await memberPath(apps.tool.slug);
    const c = await fetch(`${SITES}${mp}/api/records`, { method: "POST", headers: json, body: JSON.stringify({ title: "Hồ sơ A" }) }); const rec = await c.json();
    expect(c.status === 201 && rec.created_by === "local.admin", `create ${c.status} by ${rec.created_by}`);
    const u = await fetch(`${SITES}${mp}/api/records/${rec.id}`, { method: "PATCH", headers: json, body: JSON.stringify({ status: "DONE" }) }); expect(u.status === 200 && (await u.json()).status === "DONE", "update");
    expect((await (await fetch(`${SITES}${mp}/api/me`)).json()).username === "local.admin", "signed identity not accepted");
    // a header forged straight at the app (no valid signature) is ignored
    const cn = sh(`docker ps --filter label=factory.app=1 --format '{{.Names}}'`).split("\n").find((n) => n.startsWith(`app-${apps.tool.id.slice(0, 8)}`));
    expect(/anonymous/.test(sh(`docker exec ${cn} sh -c "wget -T 3 -qO- --header='X-Factory-User: {\\"username\\":\\"admin\\"}' --header='X-Factory-Signature: t=1,sig=${"0".repeat(64)}' http://127.0.0.1:8080/api/me 2>&1"`)), "forged identity header accepted by the app");
    // app A cannot reach app B
    const other = sh(`docker ps --filter label=factory.app=1 --format '{{.Names}}'`).split("\n").find((n) => n.startsWith(`app-${apps.server.id.slice(0, 8)}`));
    expect(/unreachable/.test(sh(`docker exec ${cn} sh -c "wget -T 3 -qO- http://${other}:8080/health >/dev/null 2>&1 && echo REACHED || echo unreachable"`)), "app-to-app traffic possible");
  });
  await check("WORKFLOW (private): submit → approvers only → history; non-approver refused", async () => {
    apps.wf = await make("WORKFLOW", "PRIVATE", (P) => call("PUT", `${P}/runtime/secrets`, { name: "APPROVERS", value: "local.admin" })); await runtimeUp(apps.wf.P);
    const mp = await memberPath(apps.wf.slug);
    const s = await fetch(`${SITES}${mp}/api/requests`, { method: "POST", headers: json, body: JSON.stringify({ title: "Mua máy in", amount: 3500000 }) }); const req = await s.json(); expect(s.status === 201 && req.state === "SUBMITTED", `submit ${s.status}`);
    const d = await fetch(`${SITES}${mp}/api/requests/${req.id}/decision`, { method: "POST", headers: json, body: JSON.stringify({ decision: "APPROVE" }) }); expect(d.status === 200 && (await d.json()).state === "APPROVED", `approve ${d.status}`);
    const again = await fetch(`${SITES}${mp}/api/requests/${req.id}/decision`, { method: "POST", headers: json, body: JSON.stringify({ decision: "REJECT" }) }); expect(again.status === 409, "second decision must be refused");
    const h = (await (await fetch(`${SITES}${mp}/api/requests/${req.id}/history`)).json()).map((x) => `${x.action}:${x.actor}`).join(" ");
    expect(h.startsWith("SUBMITTED:local.admin APPROVED:local.admin"), `history ${h}`);
  });
  await check("archive stops the server app and removes its container; restore keeps the data", async () => {
    const P = apps.server.P; expect((await call("POST", `${P}/archive`)).status === 200, "archive");
    for (let i = 0; i < 40 && sh(`docker ps --filter label=factory.app=1 --format '{{.Names}}'`).split("\n").some((n) => n.startsWith(`app-${apps.server.id.slice(0, 8)}`)); i++) await sleep(1500);
    expect(!sh(`docker ps --filter label=factory.app=1 --format '{{.Names}}'`).split("\n").some((n) => n.startsWith(`app-${apps.server.id.slice(0, 8)}`)), "container still running after archive");
    expect((await fetch(`${SITES}/${apps.server.slug}/api/items`)).status !== 200, "archived app still answers");
    expect((await call("POST", `${P}/restore`)).status === 200, "restore");
  });
} finally {
  // clean up what the run created and restore policy/quotas
  for (const a of Object.values(apps)) { if (!a?.id) continue; await call("POST", `${a.P}/runtime/stop`).catch(() => undefined); await call("DELETE", `${a.P}?expectedRevision=${(await call("GET", a.P)).body?.revision ?? 0}`).catch(() => undefined); }
  for (const [k, p] of [["server-apps.enabled", prev.policy], ["build.max-per-user-per-day", prev.quota]]) sql(p ? `update system_settings set value='${p}' where key='${k}'` : `delete from system_settings where key='${k}'`);
  sql("delete from system_settings where key in ('build.max-per-org-per-day','build.max-per-workspace-per-day','build.max-per-project-per-day')");
}
console.log(`\n${results.filter(Boolean).length}/${results.length} passed`);
process.exit(failed ? 1 : 0);
