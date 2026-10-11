// @class: real-backend — FQ-UI-01: the Admin employee directory (/admin/employees) with REAL data whose unit / position / grade / person names are as long as real companies write them: at 360 · 390 · 430 · 600 · 768 · 1024 · 1280 · 1440 · 1920 px
// no control, table cell or dialog field sits outside the viewport (a <select> sizes to its longest option, so one long name used to widen the filter bar to 560-1500 px, hidden behind the scrolling main).
// Needs a stack with ORGANIZATION_PERSISTENCE_ENABLED=true (blocked otherwise).
import { Blocked } from "../lib/report.mjs";
import { loginPortal, makeTenant, makeTenantUser, openPortal } from "../lib/portals.mjs";
import { newPage, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-UI01", title = "(ui) Admin employee directory with long real names: nothing clipped or outside the viewport at 9 widths (list, filters, create dialog, detail)";
const WIDTHS = [360, 390, 430, 600, 768, 1024, 1280, 1440, 1920];
const LONG = " - Phòng Phát triển Sản phẩm và Chuyển đổi số khu vực phía Nam";

export async function run({ cfg, fx, browser, check }) {
  const A = await makeTenant(fx, "uiA");
  const admin = await makeTenantUser(fx, cfg, A.id, "uiAadmin", { tenantRole: "TENANT_ADMIN", workspaceId: A.workspaceId, workspaceRole: "WORKSPACE_ADMIN" });
  const sa = admin.session, T = `/admin/tenants/${A.id}`;
  if ((await sa.get(`${T}/organization-units`)).status === 501) throw new Blocked("C0", "the organization store is OFF on this stack (ORGANIZATION_PERSISTENCE_ENABLED=false)", "flag");
  const type = (await sa.post(`${T}/organization-unit-types`, { name: "Khối", code: "khoi", icon: "folder", rules: {} })).body;
  const units = [];
  for (const [i, n] of ["Khối Công nghệ", "Khối Vận hành", "Khối Kinh doanh"].entries()) units.push((await sa.post(`${T}/organization-units`, { typeId: type.id, parentId: null, name: n + LONG, code: `ui${i}` })).body);
  const pos = (await sa.post(`${T}/positions`, { name: "Kỹ sư phần mềm cao cấp khối vận hành", code: "ksp" })).body;
  const grade = (await sa.post(`${T}/grades`, { name: "Bậc 5 (bậc chuyên gia cao cấp)", code: "b5", rank: 5 })).body;
  for (let i = 1; i <= 6; i++) await sa.post(`${T}/employees`, { username: `ui-${fx.runId}-${i}`.toLowerCase(), displayName: i === 2 ? "Nguyễn Đức Anh Tuấn Minh Hoàng Phương Thảo Nguyên Vĩ" : `Nhân viên ${i}`, tenantRole: "MEMBER", organizationMemberships: [{ organizationUnitId: units[i % 3].id, primary: true, positions: [{ positionId: pos.id, gradeId: grade.id, primary: true }] }] });
  check.ok("[setup] a company with long unit / position / grade names and six employees exists on the real server", units.every((u) => u?.id) && !!pos?.id && !!grade?.id, "", "http");

  const probe = (page, sel) => page.evaluate((s) => { const vw = document.documentElement.clientWidth; const root = (s && document.querySelector(s)) || document.body; const out = []; for (const e of root.querySelectorAll("button,input,select,a[href],th,td,h1,h2,h3,[role=tab]")) { const cs = getComputedStyle(e); if (cs.visibility === "hidden" || cs.display === "none") continue; const r = e.getBoundingClientRect(); if (r.width && (r.right > vw + 1 || r.left < -1)) out.push(`${e.tagName.toLowerCase()}[${(e.getAttribute("aria-label") || e.textContent || "").trim().slice(0, 22)}] ${Math.round(r.left)}..${Math.round(r.right)}`); } return out; }, sel);
  for (const w of WIDTHS) {
    const page = await newPage(browser, { width: w, height: 900 });
    await loginPortal(page, cfg, "admin", admin.username, admin.password);
    await openPortal(page, cfg, "admin", "/employees"); await page.getByTestId("emp-table").waitFor({ timeout: 15_000 }).catch(() => undefined);
    const list = await probe(page, "main");
    const doc = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    await page.getByRole("button", { name: /Thêm nhân viên/ }).first().click(); await page.getByRole("dialog").first().waitFor({ timeout: 8000 }).catch(() => undefined);
    const create = await probe(page, "[role=dialog] .modalBody"); await page.keyboard.press("Escape"); await page.waitForTimeout(300);
    await page.locator("tr.clickRow, [data-testid^='emp:']").first().click().catch(() => undefined); await page.getByRole("dialog").first().waitFor({ timeout: 8000 }).catch(() => undefined);
    const detail = await probe(page, "[role=dialog] .modalBody");
    check.ok(`${w} px: the directory (filters, table, pager), the create dialog and the detail dialog keep every control inside the viewport; no page-level horizontal scroll`, list.length === 0 && create.length === 0 && detail.length === 0 && doc <= 1, `list=${JSON.stringify(list.slice(0, 3))} create=${JSON.stringify(create.slice(0, 3))} detail=${JSON.stringify(detail.slice(0, 3))} doc+${doc}`, "ui");
    if (w === WIDTHS[0] || w === 1440) check.ok(`${w} px: no unhandled page errors`, pageProblems(page).length === 0, pageProblems(page).join(" | "));
    await page.context().close();
  }
}
