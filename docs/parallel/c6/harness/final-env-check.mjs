#!/usr/bin/env node
// C6 FINAL RC QA — PART 1: environment / SHA / health of the c0rc stack. Read-only except one throw-away tenant (c6f-env-*) to prove the org flag.
// Usage: . harness/final-env.sh && node harness/final-env-check.mjs
import { execFileSync } from "node:child_process";
import { readFileSync, existsSync, writeFileSync } from "node:fs";
import { recorder, S, API, OUT, tag, superAdmin, mkUser, st } from "./final-lib.mjs";
const { rec, save } = recorder("part1-environment", "REAL_STACK");
const sh = (cmd, args, o = {}) => { try { return execFileSync(cmd, args, { encoding: "utf-8", timeout: 20000, stdio: ["ignore", "pipe", "pipe"], ...o }).trim(); } catch (e) { return "ERR " + String(e.stderr || e.message).split("\n")[0].slice(0, 160); } };
const get = async (url, o = {}) => { try { const r = await fetch(url, { redirect: "manual", signal: AbortSignal.timeout(8000), ...o }); return { s: r.status, t: await r.text(), h: r.headers }; } catch (e) { return { s: 0, t: String(e.message) , h: new Headers() }; } };
const PRODUCT = process.env.FINAL_SHA_PRODUCT, STACKDIR = `${process.env.HOME}/.xweb-e2e-stack/c0rc`; const identity = {};
// ---- 1-5 health of the five planes
let r = await get(API + "/actuator/health"); rec("ENV-01", "health", "API /actuator/health", "200 UP", `${r.s} ${r.t.slice(0, 60)}`, r.s === 200 && /UP/.test(r.t));
r = await get(API + "/actuator/health/readiness"); rec("ENV-01b", "health", "API readiness", "200 UP", `${r.s} ${r.t.slice(0, 60)}`, r.s === 200 && /UP/.test(r.t));
for (const [id, name, url, re] of [["ENV-02", "Platform", process.env.FINAL_PLATFORM + "/platform", /./], ["ENV-03", "Admin", process.env.FINAL_ADMIN + "/admin", /./], ["ENV-04", "Studio", process.env.FINAL_STUDIO + "/studio", /./]]) {
  r = await get(url, { redirect: "follow" }); const title = (r.t.match(/<title>([^<]*)/) ?? [])[1] ?? ""; rec(id, "portal", `${name} loads`, "200 + title", `${r.s} title="${title.slice(0, 50)}" bytes=${r.t.length}`, r.s === 200 && title.length > 0); identity[name] = { url, title };
}
r = await get(process.env.FINAL_SITES + "/"); rec("ENV-05", "sites", "Sites gateway answers", "any HTTP answer (404 for an unknown host/slug is fine)", `${r.s}`, r.s > 0 && r.s < 500);
const pg = sh("docker", ["exec", "c0rc-pg", "pg_isready", "-U", "studio", "-d", "system_web_studio"]); rec("ENV-06", "postgres", "PostgreSQL accepts connections", "accepting connections", pg, /accepting/.test(pg));
const fly = sh("docker", ["exec", "c0rc-pg", "psql", "-U", "studio", "-d", "system_web_studio", "-Atc", "select max(version::int)||' / '||count(*)||' migrations, failed='||count(*) filter (where not success) from flyway_schema_history where version is not null"]);
rec("ENV-06b", "postgres", "Flyway history readable (read-only)", "latest version + 0 failed", fly, /failed=0/.test(fly)); identity.flyway = fly;
const rd = sh("docker", ["exec", "c0rc-redis", "redis-cli", "ping"]); rec("ENV-07", "redis", "Redis answers PING", "PONG", rd.slice(0, 80), /PONG|NOAUTH/.test(rd), { note: /NOAUTH/.test(rd) ? "server up, auth required" : "" });
const rb = sh("docker", ["exec", "c0rc-rabbit", "rabbitmq-diagnostics", "-q", "ping"]); rec("ENV-08", "rabbitmq", "RabbitMQ node answers", "ping ok", rb.slice(0, 100) || "ok", !/^ERR/.test(rb));
r = await get(`http://127.0.0.1:${process.env.MINIO_ENDPOINT?.split(":").pop()}/minio/health/live`); rec("ENV-09", "minio", "MinIO live", "200", `${r.s}`, r.s === 200);
// ---- 10, 11 flags proved by behaviour, with a throw-away tenant
const sa = await superAdmin(); const T = (await sa.call("POST", "/api/v1/admin/tenants", { slug: `c6f-env-${tag}`, name: `C6 final env ${tag}` })).json?.id;
const W = (await sa.call("POST", `/api/v1/admin/tenants/${T}/workspaces`, { name: `ws-${tag}` })).json?.id;
const ta = await mkUser(sa, T, `c6f-envta-${tag}`, { tenantRole: "TENANT_ADMIN", workspaceId: W, workspaceRole: "WORKSPACE_ADMIN" });
r = await ta.s.call("GET", `/api/v1/admin/tenants/${T}/organization-units?format=tree`); rec("ENV-10", "flag", "Organization persistence ON (a tenant admin reads the unit tree: 200, not 501 ORG_PERSISTENCE_NOT_AVAILABLE)", "200", st(r), r.status === 200);
const P = (await ta.s.call("POST", `/api/v1/workspaces/${W}/projects`, { name: `p-${tag}`, appType: "PAGE_SCHEMA" })).json?.id;
r = await ta.s.call("GET", `/api/v1/workspaces/${W}/projects/${P}/publish-config`); rec("ENV-11", "flag", "publish-config ON (GET publish-config answers 200, not 404)", "200", st(r), r.status === 200);
// ---- 12/13 no mock, no localStorage fallback: static checks of the served bundles (behavioural check is in the UI part)
const jsSrc = await get(process.env.FINAL_STUDIO + "/studio/login", { redirect: "follow" }); const refs = [...jsSrc.t.matchAll(/\/_next\/static\/[^"' ]+\.js/g)].map((m) => m[0]).slice(0, 12); let hits = [];
for (const u of refs) { const js = await get(process.env.FINAL_STUDIO + u); if (/NEXT_PUBLIC_MOCK|mock-data|USE_MOCK_API/.test(js.t)) hits.push(u.split("/").pop()); }
rec("ENV-12", "mock", "Studio login bundles carry no mock-API switch (first 12 chunks scanned)", "0 hits", `hits=${hits.length}${hits.length ? " " + hits.join(",") : ""} chunks=${refs.length}`, hits.length === 0, { note: "behavioural proof (network shows real /api calls, no localStorage data store) is part of the UI run" });
rec("ENV-13", "mock", "No localStorage persistence fallback", "proved in UI run", "see ui-final-* evidence", null, { cls: "MANUAL", note: "NOT proved here: browser part (final-ui) records localStorage keys after each journey" });
// ---- 14 identity
const git = (dir, ...a) => sh("git", ["-C", dir, ...a]);
const co = "/Users/hoangluan/code/xweb-c0-rc"; identity.frontendCheckout = { dir: co, head: git(co, "rev-parse", "HEAD"), dirty: git(co, "status", "--porcelain").split("\n").filter(Boolean).length };
const bw = `${STACKDIR}/backend-worktree`; identity.backendWorktree = { dir: bw, head: git(bw, "rev-parse", "HEAD"), dirty: git(bw, "status", "--porcelain").split("\n").filter(Boolean).length };
const prodDiff = (a) => git(process.env.FINAL_TESTS, "diff", "--name-only", PRODUCT, a).split("\n").filter((f) => f && !/^(docs|tests|scripts)\//.test(f) && !/^\.github/.test(f));
const dFront = prodDiff(identity.frontendCheckout.head), dBack = prodDiff(identity.backendWorktree.head);
rec("ENV-14a", "identity", `frontend checkout ${identity.frontendCheckout.head.slice(0, 12)}: clean tree, product files identical to deployed product SHA ${PRODUCT.slice(0, 12)}`, "clean + 0 product-file diff", `dirty=${identity.frontendCheckout.dirty} productDiff=${dFront.length}${dFront.length ? " " + dFront.slice(0, 3).join(",") : ""}`, identity.frontendCheckout.dirty === 0 && dFront.length === 0);
rec("ENV-14b", "identity", `backend worktree ${identity.backendWorktree.head.slice(0, 12)}: clean tree, product files identical to ${PRODUCT.slice(0, 12)}`, "clean + 0 product-file diff", `dirty=${identity.backendWorktree.dirty} productDiff=${dBack.length}${dBack.length ? " " + dBack.slice(0, 3).join(",") : ""}`, identity.backendWorktree.dirty === 0 && dBack.length === 0);
const stackJson = existsSync(`${STACKDIR}/SERVING.json`) ? JSON.parse(readFileSync(`${STACKDIR}/SERVING.json`, "utf-8")) : {}; identity.serving = stackJson;
rec("ENV-14c", "identity", "stack SERVING.json sha == backend worktree HEAD", "equal", `${(stackJson.sha ?? "").slice(0, 12)} vs ${identity.backendWorktree.head.slice(0, 12)}`, stackJson.sha === identity.backendWorktree.head);
// running processes: cwd of the API java process and of the three portals, and whether the build is newer than the sources' last change
const pidOf = (name) => { try { return JSON.parse(readFileSync(`${STACKDIR}/run/${name}.json`, "utf-8")).pid; } catch { return null; } };
const listener = (port) => sh("lsof", ["-ti", `tcp:${port}`, "-sTCP:LISTEN"]).split("\n")[0];
const cwdOf = (pid) => sh("lsof", ["-a", "-p", String(pid), "-d", "cwd", "-Fn"]).split("\n").find((l) => l.startsWith("n"))?.slice(1) ?? "?";
const procs = {}; for (const [n, port] of [["API", 47300], ["Studio", 47307], ["Platform", 47308], ["Admin", 47309]]) { const p = listener(port); procs[n] = { pid: p, cwd: cwdOf(p), started: sh("ps", ["-o", "lstart=", "-p", p]) }; }
identity.processes = procs;
rec("ENV-14d", "identity", "API listener runs from the stack's backend worktree", `cwd under ${bw}`, procs.API.cwd, procs.API.cwd.startsWith(bw));
for (const n of ["Studio", "Platform", "Admin"]) rec(`ENV-14e-${n}`, "identity", `${n} listener runs from the frontend checkout`, `cwd under ${co}/apps`, procs[n].cwd, procs[n].cwd.startsWith(co));
const builds = {}; for (const [n, a] of [["Platform", "platform"], ["Admin", "admin"], ["Studio", "studio"]]) { const f = `${co}/apps/${a}/.next-check-c0rc/BUILD_ID`; builds[n] = existsSync(f) ? readFileSync(f, "utf-8").trim() : null; }
identity.buildIds = builds; rec("ENV-14f", "identity", "BUILD_ID of the three portals recorded", "3 ids", JSON.stringify(builds), Object.values(builds).every(Boolean));
// the HTML served by each portal must reference the build id of that checkout (proves the served build is that build)
for (const [n, base, a] of [["Platform", process.env.FINAL_PLATFORM + "/platform", "platform"], ["Admin", process.env.FINAL_ADMIN + "/admin", "admin"], ["Studio", process.env.FINAL_STUDIO + "/studio", "studio"]]) { const x = await get(base, { redirect: "follow" }); const ok = builds[n] && x.t.includes(builds[n]); rec(`ENV-14g-${n}`, "identity", `${n} HTML carries BUILD_ID ${builds[n]}`, "present", ok ? "present" : "ABSENT", !!ok); }
identity.STACK_SHA = identity.backendWorktree.head; identity.FRONTEND_SHA = identity.frontendCheckout.head; identity.BACKEND_SHA = identity.backendWorktree.head; identity.PRODUCT_SHA_DEPLOYED = PRODUCT;
writeFileSync(`${OUT}/part1-identity.json`, JSON.stringify(identity, null, 1)); await sa.call("POST", "/api/v1/auth/logout");
const failed = save(); console.log(JSON.stringify({ STACK_SHA: identity.STACK_SHA, FRONTEND_SHA: identity.FRONTEND_SHA, BACKEND_SHA: identity.BACKEND_SHA, BUILD_ID: builds }, null, 1)); process.exit(failed ? 1 : 0);
