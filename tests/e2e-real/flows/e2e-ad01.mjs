// @class: real-backend — ADMIN portal (:3002) for a TENANT ADMIN who is NOT a system admin. The tenant is created by the SYSTEM_ADMIN (API fixture); the tenant admin then logs in through the portal's own form
// and manages THEIR company: a navigation with only what the server lets them do, their tenant and members, the rules (no self-change), the SYSTEM_ADMIN-only screens refused in words AND by the API (403 ADMIN_REQUIRED).
import { Blocked } from "../lib/report.mjs";
import { loginPortal, navLabels, openPortal, watchApi } from "../lib/portals.mjs";
import { newPage, pageProblems } from "../lib/ui.mjs";
const DEFAULT_TENANT = "00000000-0000-0000-0000-000000000001";
const EIGHT = ["TENANT_MEMBERS", "TENANT_MANAGE", "ORG_STRUCTURE_VIEW", "ORG_STRUCTURE_MANAGE", "EMPLOYEE_VIEW", "EMPLOYEE_MANAGE", "POSITION_GRADE_VIEW", "POSITION_GRADE_MANAGE"];
const sameSet = (a, b) => Array.isArray(a) && a.length === b.length && b.every((x) => a.includes(x));
export const id = "E2E-AD01", title = "Admin portal, tenant admin: own company + members, no system-admin screens, rules explained, refused by the API as well";
export async function run({ cfg, fx, browser, check }) {
  const sys = fx.sessions.admin, lonely = fx.users.lonelyA, viewer = fx.users.viewerA;
  const probe = await sys.get("/admin/tenants");
  if (probe.status === 404 && !probe.body?.code) throw new Blocked("C0", "the tenant API is not on this build (T2 not integrated)", "T2");
  const slug = `e2e-${fx.runId}-ad`.toLowerCase();
  const t = (await sys.post("/admin/tenants", { slug, name: `E2E AD ${fx.runId}`, firstAdminUserId: lonely.id }));
  if (t.status !== 201) throw new Blocked("C1", `could not create the fixture tenant (${t.status} ${t.body?.code})`, "T2");
  const tid = t.body.id; fx.ids.adTenant = tid;
  const me = (await fx.sessions.lonelyA.get("/auth/me")).body;
  check.ok("[api] /auth/me of the tenant admin lists the tenant with role TENANT_ADMIN and is NOT platform scope", me?.platformScope === false && (me?.tenants ?? []).some((x) => x.id === tid && x.role === "TENANT_ADMIN"), JSON.stringify({ ps: me?.platformScope, t: (me?.tenants ?? []).map((x) => [x.slug, x.role]), perms: me?.permissions }), "http");

  // M-052 / AD01 (C0 directive 2026-10-10): the fixture is primary DEFAULT (role MEMBER, root permissions []) + a SECOND company where the person is TENANT_ADMIN. The Admin portal admits them by the codes of THAT company
  // (tenants[].permissions, the exact canonical eight), never by the role label. A backend without the per-tenant field cannot say so: the flow records that as BLOCKED(C1) with the evidence, it does not work around it.
  const coRow = me?.tenants?.find((x) => x.id === tid), defRow = me?.tenants?.find((x) => x.id === DEFAULT_TENANT);
  check.ok("[api] AD01 fixture shape: the primary tenant is DEFAULT with role MEMBER and the top-level permissions are [] (nothing leaks from the second company into the root list)", me?.tenantId === DEFAULT_TENANT && defRow?.role === "MEMBER" && Array.isArray(me?.permissions) && me.permissions.length === 0, JSON.stringify({ primary: me?.tenantId, defRole: defRow?.role, root: me?.permissions }), "http");
  const perTenant = Array.isArray(coRow?.permissions);
  check.ok("[api] M-052: the second company's membership row lists its OWN permissions — exactly the canonical eight — and the DEFAULT row lists none", perTenant && sameSet(coRow.permissions, EIGHT) && Array.isArray(defRow?.permissions) && defRow.permissions.length === 0, JSON.stringify({ company: coRow?.permissions ?? "field absent", def: defRow?.permissions ?? "field absent" }), "http");
  if (!perTenant) throw new Blocked("C1", `M-052: /auth/me tenants[] carries no per-tenant permissions on this backend (the field is absent), so a Tenant Admin of a NON-primary tenant cannot be admitted by codes. The frontend resolver (packages/permissions/src/tenantScope.ts) is ready and fail-closed; no role-based workaround exists. Re-run after the C1 M-052 import.`, "M-052");
  const page = await newPage(browser); const bad = watchApi(page);
  const landed = await loginPortal(page, cfg, "admin", lonely.username, lonely.password);
  check.ok("login through the Admin portal lands inside /admin (a tenant admin is NOT refused)", landed.startsWith("/admin") && !/login|no-access/.test(landed), landed);
  const nav = await navLabels(page);
  check.ok("navigation = Tổng quan + Công ty của tôi (+ the sections still waiting for a backend); NO Người dùng, Nhật ký kiểm toán, AI…", nav.includes("Tổng quan") && nav.includes("Công ty của tôi") && !nav.includes("Người dùng & Workspace") && !nav.includes("Nhật ký kiểm toán") && !nav.includes("Workspace của tôi"), nav.join(" | "));
  check.ok("the landing page lists the tenant this person administers (no system overview, no 403 from /admin/overview)", /E2E AD/.test(await page.locator("main").innerText()) && !bad.some((b) => /admin\/overview/.test(b)), bad.join(" | "));

  await openPortal(page, cfg, "admin", "/company");
  check.ok("Công ty của tôi shows the tenant, ACTIVE, with its member list from the API", /E2E AD/.test(await page.locator("h1").first().innerText()) && (await page.getByTestId("tenant-members").count()) === 1);
  const row = page.getByTestId(`tm:${lonely.id}`);
  check.ok("the person's own row is named (from the session), marked 'bạn', and its role / remove controls are disabled (no self-change)", /bạn/.test(await row.innerText()) && (await row.locator("select").isDisabled()) && (await row.getByRole("button", { name: "Gỡ" }).isDisabled()));
  check.ok("a tenant admin gets NO suspend / delete buttons (platform operations)", (await page.getByTestId("tenant-SUSPENDED").count()) === 0 && (await page.getByTestId("tenant-DELETED").count()) === 0);
  check.ok("the add form is real: a search box and a candidate list (empty here: this fresh tenant has no workspace and no former member) — not a 'cannot look people up' notice", (await page.getByTestId("tm-search").count()) === 1 && (await page.getByTestId("tenant-add-blocked").count()) === 0 && /Không có người phù hợp/.test(await page.getByTestId("tm-person").innerText()));
  await page.getByTestId("tm-search").fill("a"); await page.waitForTimeout(600);
  check.ok("a 1-character search is not sent (the server would answer 400 QUERY_TOO_SHORT): the form asks for 2", /ít nhất 2 ký tự/.test(await page.getByTestId("tm-hint").innerText()) && !bad.some((b) => /member-candidates/.test(b)), bad.join(" | "));

  // the API agrees with what the UI showed
  const sysOnly = await fx.sessions.lonelyA.get("/admin/users");
  check.ok("[api] the same person is refused the system-wide user list (403 ADMIN_REQUIRED)", sysOnly.status === 403 && sysOnly.body?.code === "ADMIN_REQUIRED", `status=${sysOnly.status} ${sysOnly.body?.code}`, "http");
  const mem = await fx.sessions.lonelyA.get(`/admin/tenants/${tid}/members`);
  check.ok("[api] …but allowed to read the members of THEIR tenant (TENANT_MEMBERS), each row with userId, username, displayName, email, role, active", mem.status === 200 && Array.isArray(mem.body) && mem.body.length > 0 && mem.body.every((m) => ["userId", "username", "displayName", "email", "role", "active"].every((k) => k in m)), `status=${mem.status} ${JSON.stringify(mem.body).slice(0, 160)}`, "http");
  const suspend = await fx.sessions.lonelyA.patch(`/admin/tenants/${tid}/status`, { status: "SUSPENDED" });
  check.ok("[api] …and refused to suspend it (platform only: 403)", suspend.status === 403, `status=${suspend.status} ${suspend.body?.code}`, "http");
  const foreign = await fx.sessions.lonelyA.get(`/admin/tenants/00000000-0000-0000-0000-000000000001/members`);
  check.ok("[api] …and refused the members of another tenant (403/404, no list)", [403, 404].includes(foreign.status), `status=${foreign.status}`, "http");

  await openPortal(page, cfg, "admin", "/users");
  check.ok("typing a system-only URL shows 'chỉ dành cho quản trị hệ thống' and makes NO call to /admin/users", /chỉ dành cho quản trị hệ thống/.test(await page.locator("main").innerText()) && !bad.some((b) => /\/admin\/users/.test(b)), bad.join(" | "));
  await openPortal(page, cfg, "admin", "/my-workspaces");
  check.ok("Workspace của tôi: this account administers no workspace, so the page says so (no empty fake table)", /chưa quản trị workspace nào/.test(await page.locator("main").innerText()));

  // ---- M-052 / AD01: what the second company's administrator may do, and may NOT do outside it
  await openPortal(page, cfg, "admin", "/company");
  check.ok("AD01 select company: the Admin portal admits the person and opens the company page of THEIR company (not DEFAULT), with no company switch (exactly one company is administered)", /E2E AD/.test(await page.locator("h1").first().innerText()) && (await page.getByTestId("tenant-switch").count()) === 0 && !/mặc định/i.test(await page.locator("main").innerText()), "", "ui");
  const g1 = await fx.sessions.lonelyA.get(`/admin/tenants/${tid}`), g2 = await fx.sessions.lonelyA.get(`/admin/tenants/${tid}/members`);
  check.ok("[api] GET tenant → 200 and GET members → 200 for the company", g1.status === 200 && g2.status === 200, `tenant=${g1.status} members=${g2.status}`, "http");
  const newName = `E2E AD renamed ${fx.runId}`; const patched = page.waitForResponse((r) => r.request().method() === "PATCH" && new RegExp(`/admin/tenants/${tid}$`).test(new URL(r.url()).pathname), { timeout: 15_000 });
  await page.getByTestId("tenant-rename").click(); await page.getByTestId("tenant-rename-name").fill(newName); await page.getByTestId("tenant-rename-submit").click(); const pr = await patched;
  const renamed = (await sys.get(`/admin/tenants/${tid}`)).body;
  check.ok("PATCH tenant (rename) through the screen → 200 with {name} only; the server holds the new name (read by a separate session) and the page shows it", pr.status() === 200 && JSON.stringify(pr.request().postDataJSON()) === JSON.stringify({ name: newName }) && renamed?.name === newName && renamed?.slug === slug, `status=${pr.status()} server=${renamed?.name}`, "persistence");
  const mk = await fx.sessions.lonelyA.post(`/admin/tenants/${tid}/users`, { username: `e2e-${fx.runId}-adnew`.toLowerCase(), displayName: "AD new", tenantRole: "MEMBER" });
  if (mk.status === 201 && mk.body?.userId) fx.created.users.push(mk.body.userId);
  check.ok("[api] POST user in the company → 201 (TENANT_MEMBERS of THAT company)", mk.status === 201, `status=${mk.status} ${mk.body?.code}`, "http");
  const orgRead = await fx.sessions.lonelyA.get(`/admin/tenants/${tid}/organization-units`), orgWrite = await fx.sessions.lonelyA.post(`/admin/tenants/${tid}/organization-unit-types`, { name: "ad01", code: "ad01" });
  check.ok("[api] organization read and write are AUTHORIZED for the company (the codes are held): 200 / 201 with the store on, 501 ORG_PERSISTENCE_NOT_AVAILABLE with the store off — never 403", [200, 501].includes(orgRead.status) && [201, 501].includes(orgWrite.status), `read=${orgRead.status} write=${orgWrite.status}`, "http");
  // switching to DEFAULT: everything the company's codes allowed is refused there (company A's permissions never authorize another tenant)
  const dRead = await fx.sessions.lonelyA.get(`/admin/tenants/${DEFAULT_TENANT}`), dRename = await fx.sessions.lonelyA.patch(`/admin/tenants/${DEFAULT_TENANT}`, { name: "hijack" }), dUser = await fx.sessions.lonelyA.post(`/admin/tenants/${DEFAULT_TENANT}/users`, { username: `e2e-${fx.runId}-leak`.toLowerCase(), displayName: "leak", tenantRole: "MEMBER" }), dOrg = await fx.sessions.lonelyA.get(`/admin/tenants/${DEFAULT_TENANT}/organization-units`);
  check.ok("[api] CROSS-TENANT: on DEFAULT the same person (a plain MEMBER there) gets NO admin power: rename, user creation and organization read are refused (403/404, never 2xx); the tenant RECORD itself is readable by any active member (contract: GET /{t} = forTenant), which is not an administration right", [dRename, dUser, dOrg].every((r) => r.status === 403 || r.status === 404) && [200, 403, 404].includes(dRead.status), `read=${dRead.status} rename=${dRename.status} user=${dUser.status} org=${dOrg.status}`, "http");
  const dDefault = (await sys.get(`/admin/tenants/${DEFAULT_TENANT}`)).body;
  check.ok("[api] …and DEFAULT's name is untouched", dDefault?.name !== "hijack", String(dDefault?.name), "persistence");
  // SUSPENDED: still listed WITH its codes; reads stay allowed; a rename by the company administrator is refused by the backend and shown as such (no success)
  const sus = await sys.patch(`/admin/tenants/${tid}/status`, { status: "SUSPENDED" });
  await page.reload({ waitUntil: "networkidle" }); await page.waitForTimeout(500);
  await page.getByTestId("tenant-rename").click(); await page.getByTestId("tenant-rename-name").fill("không được"); await page.getByTestId("tenant-rename-submit").click(); await page.getByTestId("tenant-rename-error").waitFor({ timeout: 10_000 }).catch(() => undefined);
  const stillName = (await sys.get(`/admin/tenants/${tid}`)).body?.name;
  check.ok("SUSPENDED tenant: PATCH rename → 403 TENANT_SUSPENDED is shown in the dialog ('tạm khóa … Tên chưa thay đổi'); the server name is unchanged; no success message", sus.status === 200 && (await page.getByTestId("tenant-rename-error").getAttribute("data-code")) === "TENANT_SUSPENDED" && stillName === newName && !/Đã đổi tên công ty/.test(await page.locator("main").innerText()), `server=${stillName}`, "ui");
  await page.keyboard.press("Escape");
  const asRead = await fx.sessions.lonelyA.get(`/admin/tenants/${tid}/members`);
  check.ok("[api] a SUSPENDED company still lists its members (reads allowed)", asRead.status === 200, `status=${asRead.status}`, "http");
  await sys.patch(`/admin/tenants/${tid}/status`, { status: "ACTIVE" });

  // a person with no administrative scope is refused at the door
  const v = await newPage(browser);
  const where = await loginPortal(v, cfg, "admin", viewer.username, viewer.password);
  check.ok("a workspace viewer is sent to /auth/no-access by the Admin portal", /no-access/.test(where), where);
  await v.context().close();

  const del = await sys.patch(`/admin/tenants/${tid}/status`, { status: "DELETED" });
  check.ok("cleanup: the fixture tenant is marked DELETED", del.status === 200, `status=${del.status}`, "http");
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
