#!/usr/bin/env node
// HARNESS, NOT REAL BACKEND. The UI audit engine (scripts/audit/engine.mjs: the same measurements as scripts/ui-audit.mjs) on the harness pages, so the tool can be PROVEN without a backend:
//   Platform / Admin consoles: tests/browser/admin-harness.tsx (the real PortalApp + AdminApp over an in-page fake `fetch`), personas me=sys (Platform and Admin system admin) and me=tadmin (company admin);
//   Studio: tests/browser/studio-app (the real StudioApp over the Playwright-side fake API).
// The route list comes from the SOURCE (scripts/audit/inventory.mjs) and the run FAILS (exit 1) when a route found in the source was not visited.
//   HARNESS_NODE_ENV=development node tests/browser/build-harness.mjs        # -> .test-build/browser
//   node scripts/ui-audit-harness.mjs [--out /tmp/ui-audit-harness] [--only platform,admin,studio] [--viewports 1920,...,360] [--shots]
// The static server and Chrome are started and stopped only through tests/lib/owned-process.mjs (scripts/perf-env.mjs). No screenshots unless --shots.
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { withEnv } from "./perf-env.mjs";
import { VIEWPORTS, viewportOf } from "./audit/measure.mjs";
import { visit as engineVisit, flag, summarize } from "./audit/engine.mjs";
import { inventory, visitPaths, missingRoutes } from "./audit/inventory.mjs";
import { installFake, newState } from "../tests/browser/studio-app/fake-api.mjs";

const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const OUT = arg("out", "/tmp/ui-audit-harness"); const ONLY = arg("only", "platform,admin,studio").split(","); const VPS = arg("viewports", VIEWPORTS.join(",")).split(",").map(Number);
if (!process.argv.includes("--shots")) process.env.AUDIT_NO_SHOTS = "1";
const rows = []; const visited = new Set();
const IDS = { tenants: "t1", users: "u2", workspaces: "w1", applications: "a1", project: "p1" };

await withEnv({ dir: ".test-build/browser", tag: "audith" }, async ({ base, browser, chromeVersion }) => {
  const inv = inventory();
  const rel = (c, p) => p.slice(`/${c}`.length);
  const items = (c) => [...visitPaths(inv, c, { ids: IDS }).map(({ route, path }) => ({ r: rel(c, path), id: route.id })), { r: "/nope", id: null }];
  const adminUrl = (portal, me, path) => `${base}/admin.html?portal=${portal}&me=${me}&start=${encodeURIComponent(path)}`;
  for (const vp of VPS) {
    const viewport = viewportOf(vp); const shots = join(OUT, "shots", String(vp));
    const run = async (portal, tag, me, console_) => {
      const ctx = await browser.newContext({ viewport }); const page = await ctx.newPage();
      for (const { r, id } of items(console_)) { const vpath = `/${console_}${r}`; await engineVisit(rows, page, portal, vp, adminUrl(portal, me, vpath), `${tag}${r || "-home"}`, shots, undefined, id, vpath); if (id) visited.add(id); }
      await ctx.close();
    };
    if (ONLY.includes("platform")) await run("platform", "platform", "sys", "platform");
    if (ONLY.includes("admin")) { await run("admin", "admin-tenant", "tadmin", "admin"); await run("admin", "admin-system", "sys", "admin"); }
    if (ONLY.includes("studio")) {
      const ctx = await browser.newContext({ viewport }); const page = await ctx.newPage(); await installFake(page, newState());
      for (const { r, id } of items("studio")) { const vpath = `/studio${r}`; await engineVisit(rows, page, "studio", vp, `${base}/studio.html?start=${encodeURIComponent(vpath)}`, `studio${r.replace("p1", "P") || "-home"}`, shots, undefined, id, vpath); if (id) visited.add(id); }
      await ctx.close();
    }
  }
  const missing = missingRoutes(inv, visited, { consoles: ONLY });
  mkdirSync(OUT, { recursive: true });
  const summary = summarize(rows);
  writeFileSync(join(OUT, "audit.json"), JSON.stringify({ mode: "HARNESS, NOT REAL BACKEND", chrome: chromeVersion, viewports: VPS, inventory: { source: inv.source, routes: inv.routes.length, consoles: ONLY, missing }, summary, rows }, null, 1));
  writeFileSync(join(OUT, "audit.md"), ["| portal | vp | route | issues |", "|---|---|---|---|", ...rows.map((r) => `| ${r.portal} | ${r.vp} | ${r.route} | ${flag(r) || "ok"} |`)].join("\n") + "\n");
  console.log(`HARNESS, NOT REAL BACKEND · Chrome ${chromeVersion} · ${rows.length} visits (route x viewport x state) · viewports ${VPS.join(",")} · routes ${summary.routes} · with findings ${summary.withIssues}\nsummary ${JSON.stringify(summary)}\ninventory (${inv.source}): ${inv.routes.length} routes in source, ${missing.length} not visited${missing.length ? ": " + missing.join(", ") : ""}\nreport: ${OUT}/audit.md  json: ${OUT}/audit.json`);
  if (missing.length) { console.error("FAIL: routes found in the source were not visited"); process.exitCode = 1; }
});
