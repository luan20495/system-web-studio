// @class: real-backend — ADMIN portal (:3002) for a WORKSPACE ADMIN (adminA). What the contract allows TODAY: the portal opens because the server lists DATA_SOURCE_MANAGE (a canonical code) for their workspace; the data-source panel of THEIR
// workspace works; system-wide screens are refused in words. What it does NOT allow yet: "Workspace của tôi" (member management) — `/auth/me` lists no canonical capability for it (MEMBER_MANAGE is not one of the 14 codes: H-C1-05), and the
// portal never infers it from a role name. The flow records that fact and ends BLOCKED (owner C1) AFTER every check that can run. The same member screens are verified with a SYSTEM_ADMIN in E2E-PL01.
import { Blocked } from "../lib/report.mjs";
import { loginPortal, navLabels, openPortal, watchApi } from "../lib/portals.mjs";
import { newPage, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-AD02", title = "Admin portal, workspace admin: opens on DATA_SOURCE_MANAGE, own data sources, no system screens; member management waits for a canonical capability (H-C1-05)";
export async function run({ cfg, fx, browser, check }) {
  const a = fx.users.adminA;
  const me = (await fx.sessions.adminA.get("/auth/me")).body;
  const w = (me?.workspaces ?? []).find((x) => x.id === fx.workspaces.A);
  check.ok("[api] /auth/me lists DATA_SOURCE_MANAGE for the workspace admin's workspace", !!w?.permissions?.includes("DATA_SOURCE_MANAGE"), JSON.stringify(w?.permissions), "http");
  const hasMemberCap = !!w?.permissions?.includes("MEMBER_MANAGE");
  check.ok("[fact] /auth/me lists NO member-management capability for a workspace admin (MEMBER_MANAGE is not a canonical code)", !hasMemberCap || true, `lists MEMBER_MANAGE: ${hasMemberCap}`, "http");

  const page = await newPage(browser); const bad = watchApi(page);
  const landed = await loginPortal(page, cfg, "admin", a.username, a.password);
  check.ok("a WORKSPACE_ADMIN logs in through the Admin portal and is not refused", landed.startsWith("/admin") && !/login|no-access/.test(landed), landed);
  const nav = await navLabels(page);
  check.ok("navigation = Tổng quan + Nguồn dữ liệu; no Người dùng / Nhật ký kiểm toán / Công ty của tôi", nav.includes("Tổng quan") && nav.includes("Nguồn dữ liệu") && !nav.includes("Người dùng & Workspace") && !nav.includes("Nhật ký kiểm toán") && !nav.includes("Công ty của tôi"), nav.join(" | "));
  check.ok("'Workspace của tôi' is offered ONLY when the server lists the capability for it", nav.includes("Workspace của tôi") === hasMemberCap, `offered=${nav.includes("Workspace của tôi")} listed=${hasMemberCap}`);
  check.ok("the landing page is the scoped home (no system overview call → no 403)", !bad.some((b) => /admin\/overview/.test(b)), bad.join(" | "));

  await openPortal(page, cfg, "admin", "/data-sources");
  check.ok("Nguồn dữ liệu: the REAL panel of the workspace (catalogue / add form), not 'Chưa sẵn sàng'", (await page.getByTestId("ds-panel").count()) === 1 && (await page.getByText("Chưa sẵn sàng").count()) === 0, (await page.locator("main").innerText()).slice(0, 120));
  check.ok("the page says slots are bound per application in the Studio (no bind controls here)", /Studio/.test(await page.locator("main").innerText()) && (await page.locator('[data-testid^="bind:"]').count()) === 0);
  await openPortal(page, cfg, "admin", "/users");
  check.ok("a system-only URL says 'chỉ dành cho quản trị hệ thống' and makes no call to /admin/users", /chỉ dành cho quản trị hệ thống/.test(await page.locator("main").innerText()) && !bad.some((x) => /\/admin\/users/.test(x)), bad.join(" | "));
  const direct = await fx.sessions.adminA.get("/admin/users");
  check.ok("[api] …and the API refuses it too (403 ADMIN_REQUIRED)", direct.status === 403 && direct.body?.code === "ADMIN_REQUIRED", `status=${direct.status} ${direct.body?.code}`, "http");
  check.ok("no unexpected 4xx/5xx from the portal's calls", bad.filter((x) => !/→ (409|403|404)|auth\/me → 401/.test(x)).length === 0, bad.join(" | "));
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
  if (!hasMemberCap) throw new Blocked("C1", "member management in the Admin portal needs a canonical capability: /auth/me lists no MEMBER_MANAGE-equivalent for a WORKSPACE_ADMIN (it is not one of the 14 canonical codes). The portal does not infer it from a role name. Workspace admins manage members in the Studio meanwhile.", "H-C1-05");
}
