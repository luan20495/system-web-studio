#!/usr/bin/env node
// HARNESS, NOT REAL BACKEND. Reproductions of defects found by the REAL-STACK audit (docs/parallel/c5/audit/S4-realstack-baseline.md) in the matching harness, with the same measurements the audit uses.
// Each case prints what the audit flagged on the real stack and whether the harness shows it too. A case that does NOT reproduce is reported as such (a real-data artefact or a data-dependent defect), never hidden.
//   node tests/browser/build-harness.mjs && node scripts/ui-repro.mjs [--only studio-header-600,...]
import { withEnv } from "./perf-env.mjs";
import { MEASURE, viewportOf } from "./audit/measure.mjs";
import { installFake, newState, registry } from "../tests/browser/studio-app/fake-api.mjs";

const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const ONLY = arg("only", null)?.split(",");
const LONG_WS = "Kinh doanh và Chăm sóc khách hàng";
const L = (x) => `${x} — ${"Nội dung rất dài của trường này ".repeat(3)}`;

const CASES = {
  "studio-header-600": { what: "Studio header at 600 px: the search field is squeezed to ~22 px and covered by the workspace picker when the workspace name is long (real: 'Kinh doanh và Chăm sóc khách hàng')",
    vp: 600, owner: "S1 Studio (StudioApp header) / S3 (css)", async run(page, base) { const s = newState(); s.me.workspaces[0].name = LONG_WS; s.me.workspaces[0].permissions.push("MEMBER_MANAGE"); s.me.workspaces[0].role = "WORKSPACE_ADMIN"; s.me.displayName = "Trần Văn Ưu Tú"; s.me.tenantId = "t1"; s.me.tenants = [{ id: "t1", slug: "x", name: "Công ty Cổ phần Ánh Dương", status: "ACTIVE", role: "MEMBER" }]; await installFake(page, s); await page.goto(`${base}/studio.html?start=${encodeURIComponent("/studio")}`); await page.waitForTimeout(1200); return page.evaluate(MEASURE); },
    hit: (m) => [...m.covered, ...m.smallMore].filter((x) => /Tìm/.test(x)) },
  "studio-ai-768": { what: "Studio AI view at 768 px: the starter chips are covered by the composer ('AI hiện chưa được quản trị' state)", vp: 768, owner: "S1 Studio",
    async run(page, base) { const s = newState(); await installFake(page, s); await page.goto(`${base}/studio.html?start=${encodeURIComponent("/studio/projects/p1/ai")}`); await page.waitForTimeout(1500); return page.evaluate(MEASURE); },
    hit: (m) => m.covered.filter((x) => /starter|composer/.test(x)) },
  "builder-rail-1024": { what: "Builder at 1024 px, rail 'Thành phần': the add / drag buttons of a component are covered by the properties panel (columns overlap)", vp: 1024, owner: "S1 Studio / S3 (builder.css)",
    async run(page, base) { const s = newState(); await installFake(page, s);
      const more = Array.from({ length: 18 }, (_, i) => ({ id: `Block${i}`, name: `Khối nội dung số ${i}`, category: "marketing", description: "mô tả", latestVersion: "1.0.0", status: "ACTIVE", usedInProjects: 1, versions: [{ version: "1.0.0", status: "ACTIVE", propsSchema: { required: ["title"], properties: { title: { type: "string" } } } }] }));
      await page.route("**/api/v1/components", (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([...registry, ...more]) }));   // the real registry has many more components than the harness fixture
      await page.goto(`${base}/studio.html?start=${encodeURIComponent("/studio/projects/p1/design")}`); await page.locator(".bx-left").first().waitFor(); await page.waitForTimeout(900); await page.getByRole("tab", { name: "Thành phần", exact: true }).click(); await page.waitForTimeout(500); return page.evaluate(MEASURE); },
    hit: (m) => m.covered.filter((x) => /bx-right|Thuộc tính/.test(x)) },
  "dialog-sticky-footer-360": { what: "Platform AI provider dialog at 360 px: 'Nâng cao' is covered by the sticky dialog footer", vp: 360, owner: "S2 Platform-Admin (dialog) / S3 (Modal footer)",
    async run(page, base) { await page.goto(`${base}/admin.html?portal=platform&me=sys&start=${encodeURIComponent("/platform/ai")}`); await page.waitForTimeout(1000); await page.getByRole("button", { name: /Thêm nhà cung cấp/ }).first().click(); await page.waitForTimeout(600); return page.evaluate(MEASURE); },
    hit: (m) => m.covered.filter((x) => /Nâng cao|xp-footer/.test(x)) },
};

await withEnv({ dir: ".test-build/browser", tag: "repro" }, async ({ base, browser, chromeVersion }) => {
  console.log(`HARNESS, NOT REAL BACKEND · Chrome ${chromeVersion}\n\n| case | width | reproduces in the harness | evidence | owner |\n|---|---:|---|---|---|`);
  for (const [name, c] of Object.entries(CASES)) {
    if ((ONLY && !ONLY.includes(name))) continue;
    const ctx = await browser.newContext({ viewport: viewportOf(c.vp) }); const page = await ctx.newPage();
    try { const m = await c.run(page, base); const h = c.hit(m); console.log(`| ${name} | ${c.vp} | ${h.length ? "YES" : "NO"} | ${h.length ? h.slice(0, 2).join(" ; ") : "not seen with the harness data"} | ${c.owner} |`); }
    catch (e) { console.log(`| ${name} | ${c.vp} | ERROR | ${String(e).slice(0, 80)} | ${c.owner} |`); }
    await ctx.close();
  }
});
