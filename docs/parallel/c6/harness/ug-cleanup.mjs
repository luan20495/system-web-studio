// C6 user-guide QA cleanup: unpublish and delete every project named C6-UG-QA-* that demo01 owns (only those; nothing else is touched).
import { launch, BASE, secretFromDemoFile, login, OUT } from "./ug-lib.mjs";
import { writeFileSync } from "node:fs";
const pw = secretFromDemoFile("demo01"); const browser = await launch(); const ctx = await browser.newContext(); const p = await ctx.newPage();
await login(p, "demo01", pw);
const me = await (await ctx.request.get(BASE + "/api/v1/auth/me")).json(); const wsId = me.workspaces?.[0]?.id ?? me.workspaces?.[0]?.workspaceId;
const list = await (await ctx.request.get(`${BASE}/api/v1/workspaces/${wsId}/projects`)).json(); const items = Array.isArray(list) ? list : list.items ?? [];
const mine = items.filter((x) => String(x.name).startsWith("C6-UG-QA-")); const log = [];
const tok = async () => { const j = await (await ctx.request.get(BASE + "/api/v1/auth/csrf")).json(); return j.token ?? j.csrfToken; };
for (const pr of mine) {
  const h = { "X-XSRF-TOKEN": await tok() };
  const site = await ctx.request.delete(`${BASE}/api/v1/workspaces/${wsId}/projects/${pr.id}/site`, { headers: h }); log.push(`unpublish ${pr.name} -> ${site.status()}`);
  const cur = await (await ctx.request.get(`${BASE}/api/v1/projects/${pr.id}`)).json(); const del = await ctx.request.delete(`${BASE}/api/v1/workspaces/${wsId}/projects/${pr.id}?expectedRevision=${cur.revision}`, { headers: { "X-XSRF-TOKEN": await tok() } }); log.push(`delete ${pr.name} -> ${del.status()}`);
}
const after = await (await ctx.request.get(`${BASE}/api/v1/workspaces/${wsId}/projects`)).json(); const left = (Array.isArray(after) ? after : after.items ?? []).filter((x) => String(x.name).startsWith("C6-UG-QA-")).length;
log.push(`remaining C6-UG-QA projects: ${left}`); writeFileSync(OUT + "cleanup.log", log.join("\n") + "\n"); console.log(log.join("\n"));
await browser.close();
