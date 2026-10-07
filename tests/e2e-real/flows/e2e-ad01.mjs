// @class: real-backend — ADMIN portal (:3002) for a TENANT ADMIN who is NOT a system admin. The tenant is created by the SYSTEM_ADMIN (API fixture); the tenant admin then logs in through the portal's own form
// and manages THEIR company: a navigation with only what the server lets them do, their tenant and members, the rules (no self-change), the SYSTEM_ADMIN-only screens refused in words AND by the API (403 ADMIN_REQUIRED).
import { Blocked } from "../lib/report.mjs";
import { loginPortal, navLabels, openPortal, watchApi } from "../lib/portals.mjs";
import { newPage, pageProblems } from "../lib/ui.mjs";
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
  check.ok("adding a person is explained, not hidden: this account cannot look people up (needs a workspace to administer / H-C1-11)", (await page.getByTestId("tenant-add-blocked").count()) === 1);

  // the API agrees with what the UI showed
  const sysOnly = await fx.sessions.lonelyA.get("/admin/users");
  check.ok("[api] the same person is refused the system-wide user list (403 ADMIN_REQUIRED)", sysOnly.status === 403 && sysOnly.body?.code === "ADMIN_REQUIRED", `status=${sysOnly.status} ${sysOnly.body?.code}`, "http");
  const mem = await fx.sessions.lonelyA.get(`/admin/tenants/${tid}/members`);
  check.ok("[api] …but allowed to read the members of THEIR tenant (TENANT_MEMBERS)", mem.status === 200 && Array.isArray(mem.body), `status=${mem.status}`, "http");
  const suspend = await fx.sessions.lonelyA.patch(`/admin/tenants/${tid}/status`, { status: "SUSPENDED" });
  check.ok("[api] …and refused to suspend it (platform only: 403)", suspend.status === 403, `status=${suspend.status} ${suspend.body?.code}`, "http");
  const foreign = await fx.sessions.lonelyA.get(`/admin/tenants/00000000-0000-0000-0000-000000000001/members`);
  check.ok("[api] …and refused the members of another tenant (403/404, no list)", [403, 404].includes(foreign.status), `status=${foreign.status}`, "http");

  await openPortal(page, cfg, "admin", "/users");
  check.ok("typing a system-only URL shows 'chỉ dành cho quản trị hệ thống' and makes NO call to /admin/users", /chỉ dành cho quản trị hệ thống/.test(await page.locator("main").innerText()) && !bad.some((b) => /\/admin\/users/.test(b)), bad.join(" | "));
  await openPortal(page, cfg, "admin", "/my-workspaces");
  check.ok("Workspace của tôi: this account administers no workspace, so the page says so (no empty fake table)", /chưa quản trị workspace nào/.test(await page.locator("main").innerText()));

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
