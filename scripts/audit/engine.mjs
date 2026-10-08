// @class: tooling
// The audit ENGINE shared by scripts/ui-audit.mjs (real stack, private portal builds) and scripts/ui-audit-harness.mjs (HARNESS, NOT REAL BACKEND): visits one URL, measures it (scripts/audit/measure.mjs),
// probes the first Tab stops, runs axe (every impact), captures console errors and failing API calls, takes a screenshot, then repeats the same for the known dialog / drawer / inspector states of the screen.
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { MEASURE, axe, focusProbe } from "./measure.mjs";

const shot = (pg, opts) => (process.env.AUDIT_NO_SHOTS ? Promise.resolve() : pg.screenshot(opts));   // AUDIT_NO_SHOTS=1: measurements only
const mv = async (pg, name) => { const t = pg.locator(".bx-mview [role=tab]").filter({ hasText: name }).first(); if ((await t.count()) && (await t.isVisible())) { await t.click(); await pg.waitForTimeout(250); } };

export async function visit(rows, page, portal, vp, route, label, shotDir, extra, routeId, vpath) {
  const PATHNAME = vpath ?? new URL(route).pathname;           // vpath: the virtual path of a harness page (the URL itself is admin.html?start=...)
  const errs = []; const bad = [];
  const onErr = (m) => { if (["error"].includes(m.type()) && !/favicon|Failed to load resource/.test(m.text())) errs.push(m.text().slice(0, 140)); }; const onPE = (e) => errs.push(`pageerror: ${e.message.slice(0, 140)}`);
  const onResp = (r) => { if (r.status() >= 400 && /\/api\//.test(r.url())) bad.push(`${r.request().method()} ${new URL(r.url()).pathname.replace(/[0-9a-f-]{36}/g, "{id}")} ${r.status()}`); };
  page.on("console", onErr); page.on("pageerror", onPE); page.on("response", onResp);
  let ok = true; try { await page.goto(route, { waitUntil: "domcontentloaded" }); await page.waitForLoadState("networkidle", { timeout: 15000 }).catch(() => undefined); await page.waitForTimeout(500); if (extra) await extra(page); } catch (e) { ok = false; errs.push(`navigation: ${String(e).slice(0, 100)}`); }
  const m = await page.evaluate(MEASURE).catch(() => ({ glyphs: "", textLen: 0 })); const ax = await axe(page);
  const slug = label.replace(/[^a-z0-9]+/gi, "-").toLowerCase();
  mkdirSync(shotDir, { recursive: true }); await shot(page, { path: join(shotDir, `${slug}.png`), fullPage: vp >= 1000 || vp <= 430 }).catch(() => undefined);
  page.off("console", onErr); page.off("pageerror", onPE); page.off("response", onResp);
  const focus = await focusProbe(page).catch(() => null);
  rows.push({ portal, vp, route: PATHNAME, routeId, label, ok, ...m, axe: ax, focus, errs, bad });
  // dialogs and inspector states: the same route, one more interaction, audited again (a dialog / drawer / inspector tab is where most small controls live)
  const path = PATHNAME;
  const steps = [];
  if (/\/platform\/tenants$/.test(path)) steps.push(["dialog-create-company", async (pg) => { await pg.getByRole("button", { name: /Tạo công ty/ }).first().click(); await pg.getByTestId("tenant-create").waitFor(); }]);
  if (/\/platform\/users$/.test(path)) steps.push(["dialog-create-account", async (pg) => { await pg.getByTestId("users-create").click(); await pg.getByTestId("create-account").waitFor(); }]);
  if (/\/platform\/ai$/.test(path)) steps.push(["dialog-ai-provider", async (pg) => { const b = pg.getByRole("button", { name: /Thêm nhà cung cấp/ }).first(); await b.click(); await pg.waitForTimeout(400); }]);
  if (/\/admin\/employees$/.test(path)) { steps.push(["dialog-add-employee", async (pg) => { await pg.getByTestId("emp-create").click(); await pg.getByTestId("create-account").waitFor(); }]); steps.push(["dialog-employee-detail", async (pg) => { await pg.locator("[data-testid^=emp\\:]").first().click(); await pg.getByTestId("emp-detail").waitFor(); }]); }
  if (/\/design$/.test(path) && portal === "studio") {
    steps.push(["builder-section-selected", async (pg) => { await pg.frameLocator("iframe").locator("section").first().click({ position: { x: 30, y: 30 } }); await pg.waitForTimeout(500); }]);
    for (const tab of ["Nội dung", "Thiết kế", "Dữ liệu", "Hành động", "Quyền", "Nâng cao"]) steps.push([`inspector-tab-${tab}`, async (pg) => { await mv(pg, /^Thuộc tính/); const t = pg.locator(".bx-right").getByRole("tab", { name: new RegExp(tab, "i") }).first(); if (await t.count()) { await t.click(); await pg.waitForTimeout(300); } }]);
    steps.push(["dialog-add-page", async (pg) => { await mv(pg, /^Công cụ$/); await pg.getByRole("tab", { name: "Trang", exact: true }).click(); await pg.getByRole("button", { name: /^Trang$/ }).first().click(); await pg.getByRole("dialog").waitFor(); }]);
  }
  for (const [name, run] of steps) {
    try { await run(page); await page.waitForTimeout(350); rows.push({ portal, vp, route: `${path}#${name}`, label: `${label}-${name}`, ok, ...(await page.evaluate(MEASURE).catch(() => ({}))), axe: await axe(page), errs: [], bad: [] });
      mkdirSync(shotDir, { recursive: true }); await shot(page, { path: join(shotDir, `${slug}-${name}.png`) }).catch(() => undefined);
      await page.keyboard.press("Escape").catch(() => undefined); await page.waitForTimeout(200);
    } catch (e) { rows.push({ portal, vp, route: `${path}#${name}`, label: `${label}-${name}`, ok: false, errs: [`state not reached: ${String(e).slice(0, 90)}`], axe: [], bad: [] }); await page.keyboard.press("Escape").catch(() => undefined); }
  }
  if (portal === "studio" && /\/design$/.test(PATHNAME)) {
    for (const tab of ["Thành phần", "Dữ liệu", "Biểu mẫu", "Hành động", "Workflow", "Giao diện", "AI"]) {
      const t = page.getByRole("tab", { name: tab, exact: true }).first(); if (!(await t.count())) continue;
      await mv(page, /^Công cụ$/);
      await t.click().catch(() => undefined); await page.waitForTimeout(450);
      rows.push({ portal, vp, route: `${PATHNAME}#rail:${tab}`, label: `${label}-rail-${tab}`, ok, ...(await page.evaluate(MEASURE).catch(() => ({}))), axe: await axe(page), errs: [], bad: [] });
    }
    const test = page.getByRole("button", { name: /Dùng thử/ }).first();
    if (await test.count()) { await test.click().catch(() => undefined); await page.waitForTimeout(500); await mv(page, /^(Thuộc tính|Kiểm thử)/); rows.push({ portal, vp, route: `${PATHNAME}#test-mode`, label: `${label}-test-mode`, ok, ...(await page.evaluate(MEASURE).catch(() => ({}))), axe: await axe(page), errs: [], bad: [] }); }
  }
}


/** one line per row: everything the reviewer should look at (empty string = nothing found) */
export const flag = (r) => [r.glyphs ? `glyphs[${r.glyphs}]` : "", r.replacement ? "U+FFFD" : "", r.mojibake ? "mojibake" : "", r.entities ? "entity" : "", r.overflowX > 1 ? `overflowX+${r.overflowX}` : "", r.spill?.length ? `spill(${r.spill.join("|")})` : "", r.clipped?.length ? `clipped(${r.clipped.join("|")})` : "",
  r.clippedCtl?.length ? `unreachable(${r.clippedCtl.join("|")})` : "", r.covered?.length ? `covered(${r.covered.join("|")})` : "", r.imgs?.length ? "brokenImg" : "", r.noName?.length ? `noName(${r.noName.length})` : "", r.noLabel?.length ? `noLabel(${r.noLabel.length})` : "", r.nameMismatch?.length ? `nameMismatch(${r.nameMismatch.length})` : "",
  (r.small?.length || r.smallMore?.length) ? `<24px(${(r.small?.length ?? 0) + (r.smallMore?.length ?? 0)})` : "", r.focus?.noIndicator?.length ? `noFocusRing(${r.focus.noIndicator.join("|")})` : "", r.focus?.obscuredFully?.length ? `focusObscured(${r.focus.obscuredFully.join("|")})` : "",
  r.focus?.offscreen?.length ? `focusOffscreen(${r.focus.offscreen.join("|")})` : "", r.axe?.length ? `axe[${r.axe.map((a) => `${a.id}:${a.impact}x${a.n}`).join(",")}]` : "", r.errs?.length ? `console(${r.errs.length})` : "", r.bad?.length ? `api(${[...new Set(r.bad)].join(",")})` : "",
  (r.textLen ?? 0) < 20 ? "BLANK" : "", r.h1 === 0 ? "noH1" : ""].filter(Boolean).join(" ");

/** per-check counts over all rows: how many visits (route x viewport) show each finding */
export function summarize(rows) {
  const n = (f) => rows.filter(f).length; const ax = (imp) => rows.filter((r) => r.axe?.some((a) => imp.includes(a.impact))).length;
  return {
    visits: rows.length, routes: new Set(rows.map((r) => r.portal + r.route)).size, withIssues: n((r) => flag(r)),
    overflowX: n((r) => r.overflowX > 1), unreachable: n((r) => r.clippedCtl?.length), covered: n((r) => r.covered?.length), smallTargets: n((r) => r.small?.length || r.smallMore?.length), nameMismatch: n((r) => r.nameMismatch?.length),
    noFocusRing: n((r) => r.focus?.noIndicator?.length), focusObscured: n((r) => r.focus?.obscuredFully?.length), axeCriticalSerious: ax(["critical", "serious"]), axeModerateMinor: ax(["moderate", "minor"]),
    consoleErrors: n((r) => r.errs?.length), failingApi: n((r) => r.bad?.length), blank: n((r) => (r.textLen ?? 0) < 20),
  };
}
