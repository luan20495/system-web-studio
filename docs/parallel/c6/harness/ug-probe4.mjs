// C6 re-probe of UG-107/108 (guide §6): stale rollback and busy scope, with a rollback target that is NOT the active release. API only, session of demo01.
import { launch, BASE, OUT, secretFromDemoFile, login } from "./ug-lib.mjs";
import { readFileSync, writeFileSync } from "node:fs";
const st = JSON.parse(readFileSync(OUT + "ug-run.json", "utf8")).state; const pw = secretFromDemoFile("demo01"); const out = {};
const browser = await launch(); const ctx = await browser.newContext(); const p = await ctx.newPage(); await login(p, "demo01", pw);
const base = `${BASE}/api/v1/workspaces/${st.wsId}/projects/${st.projectId}`; const tok = async () => (await (await ctx.request.get(BASE + "/api/v1/auth/csrf")).json()).token;
const H = async (x = {}) => ({ "X-XSRF-TOKEN": await tok(), "Content-Type": "application/json", ...x });
const deps = async () => { const j = await (await ctx.request.get(base + "/deployments")).json(); return Array.isArray(j) ? j : j.items ?? []; };
const site = async () => (await ctx.request.get(base + "/site")).json();
const wait = async () => { for (let i = 0; i < 40; i++) { const d = await deps(); if (d[0] && ["RUNNING", "FAILED"].includes(d[0].status)) return d[0]; await new Promise((r) => setTimeout(r, 1500)); } };
const rev = async () => (await (await ctx.request.get(BASE + "/api/v1/projects/" + st.projectId)).json()).revision;
const old = (await deps()).find((d) => d.status === "RUNNING")?.id; out.oldRunning = old;
const pub = await ctx.request.post(base + "/publish", { headers: await H({ "Idempotency-Key": "c6-p4-" + Date.now() }), data: { visibility: "PUBLIC", expectedRevision: await rev() } }); out.publish = pub.status(); const nd = await wait(); out.newDeployment = { id: nd?.id, status: nd?.status };
const s0 = await site(); out.active = s0.currentDeploymentId; out.targetIsActive = old === s0.currentDeploymentId;
const bad = await ctx.request.post(base + "/site/rollback", { headers: await H(), data: { deploymentId: old, expectedActiveDeploymentId: "00000000-0000-0000-0000-00000000dead" } }); out.staleBogus = { status: bad.status(), body: (await bad.text()).slice(0, 300) };
out.afterStale = (await site()).currentDeploymentId === s0.currentDeploymentId ? "active unchanged" : "ACTIVE CHANGED";
// busy: start a publish, immediately try to roll back
const [pp, rr] = await Promise.all([ctx.request.post(base + "/publish", { headers: await H({ "Idempotency-Key": "c6-p4b-" + Date.now() }), data: { visibility: "PUBLIC", expectedRevision: await rev() } }), (async () => ctx.request.post(base + "/site/rollback", { headers: await H(), data: { deploymentId: old } }))()]);
out.busy = { publish: pp.status(), rollback: rr.status(), rollbackBody: (await rr.text()).slice(0, 300), retryAfter: rr.headers()["retry-after"] ?? null };
await wait(); out.final = (await deps()).map((d) => ({ v: d.versionNumber ?? d.version, s: d.status }));
writeFileSync(OUT + "probe4.json", JSON.stringify(out, null, 1)); await browser.close(); console.log(JSON.stringify(out, null, 1));
