// C6 API probe (read-only GETs, demo01 session): do the data-source and app-runtime APIs exist on the public build, regardless of what the Studio UI shows?
import { launch, BASE, OUT, secretFromDemoFile, login } from "./ug-lib.mjs";
import { writeFileSync } from "node:fs";
const pw = secretFromDemoFile("demo01"); const out = {};
const browser = await launch(); const ctx = await browser.newContext(); const p = await ctx.newPage(); await login(p, "demo01", pw);
const me = await (await ctx.request.get(BASE + "/api/v1/auth/me")).json(); const wsId = me.workspaces?.[0]?.id ?? me.workspaces?.[0]?.workspaceId;
const g = async (path) => { const r = await ctx.request.get(BASE + path); const t = await r.text(); return { path: path.replace(wsId, "{ws}"), status: r.status(), body: t.slice(0, 160) }; };
const rnd = "11111111-1111-1111-1111-111111111111";
out.dataSources = await g(`/api/v1/workspaces/${wsId}/data-sources`);
out.runtimeKnown = await g(`/api/v1/workspaces/${wsId}/projects/${rnd}/app-runtime/workflow-runs/${rnd}`);
out.runtimeBogus = await g(`/api/v1/workspaces/${wsId}/projects/${rnd}/app-runtime/zzz-no-such-route`);
out.bindings = await g(`/api/v1/workspaces/${wsId}/projects/${rnd}/data-bindings`);
writeFileSync(OUT + "probe6.json", JSON.stringify(out, null, 1)); await browser.close(); console.log(JSON.stringify(out, null, 1));
