// @class: real-backend — ADMIN01: a TENANT ADMIN in the ADMIN portal → Người dùng → Tạo tài khoản. The create route for a tenant admin does not exist yet (C1 contract pending): the screen must say so, disable the action and send nothing.
// When C1's contract is wired into the adapter (provisioning.ts CAPABILITIES.createTenantUser) this flow's tail (marked) becomes the real creation + activation; until then it ends BLOCKED(C1) AFTER the checks that run.
import { Blocked } from "../lib/report.mjs";
import { loginPortal, navLabels, openPortal, watchApi } from "../lib/portals.mjs";
import { newPage, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-ADMIN01", title = "Tenant admin: Admin portal → Người dùng → create account (NOT_READY until C1's contract: disabled, reason shown, nothing sent); add-existing is not offered without MEMBER_MANAGE";
export async function run({ cfg, fx, browser, check }) {
  const sys = fx.sessions.admin, ta = fx.users.lonelyA;
  const t = await sys.post("/admin/tenants", { slug: `e2e-${fx.runId}-a01`.toLowerCase(), name: `E2E A01 ${fx.runId}`, firstAdminUserId: ta.id });
  if (t.status !== 201) throw new Blocked("C1", `fixture tenant (${t.status} ${t.body?.code})`, "T2");
  const page = await newPage(browser); const bad = watchApi(page);
  await loginPortal(page, cfg, "admin", ta.username, ta.password);
  check.ok("navigation has Người dùng and Công ty của tôi for a tenant admin", (await navLabels(page)).includes("Người dùng") && (await navLabels(page)).includes("Công ty của tôi"));
  await openPortal(page, cfg, "admin", "/people");
  check.ok("the screen shows the tenant from the SESSION (read-only), never an editable tenant id", (await page.getByTestId("people-tenant").getAttribute("readonly")) !== null && (await page.getByTestId("people-tenant").inputValue()).includes("E2E A01") || (await page.getByTestId("people-tenant").inputValue()).length > 0);
  const calls = []; page.on("request", (r) => { if (/\/api\/v1\/admin\/(users|tenants\/[^/]+\/users)/.test(r.url()) && r.method() === "POST") calls.push(r.url()); });
  const notReady = (await page.getByTestId("people-not-ready").count()) === 1;
  if (notReady) {
    check.ok("create is NOT_READY: the button is disabled, the reason is shown ('Backend provisioning chưa sẵn sàng'), nothing is sent", (await page.getByTestId("people-create").isDisabled()) && /chưa sẵn sàng/.test(await page.getByTestId("people-not-ready").innerText()));
    await page.getByTestId("people-create").click({ force: true, timeout: 800 }).catch(() => undefined); await page.waitForTimeout(400);
    check.ok("a forced click opens no dialog and POSTs nothing", (await page.getByTestId("create-account").count()) === 0 && calls.length === 0);
  }
  const api = await fx.sessions.lonelyA.post("/admin/users", { username: `e2e-${fx.runId}-x`, displayName: "x", workspaceId: fx.workspaces.A, role: "VIEWER" });
  check.ok("[api] the platform route is NOT a workaround: a tenant admin gets 403 ADMIN_REQUIRED from POST /admin/users", api.status === 403 && api.body?.code === "ADMIN_REQUIRED", `status=${api.status} ${api.body?.code}`, "http");
  check.ok("add-existing is not offered to a tenant admin who holds no MEMBER_MANAGE (the reason is shown)", (await page.getByTestId("add-existing-unavailable").count()) === 1);
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | ")); await page.context().close();
  await sys.patch(`/admin/tenants/${t.body.id}/status`, { status: "DELETED" });
  if (notReady) throw new Blocked("C1", "tenant-scoped account creation has no backend route yet (createTenantUser NOT_READY). When C1 delivers it: flip CAPABILITIES.createTenantUser, then this flow creates the account in the UI, activates it through the link, and USER01 continues with it.", "C1 provisioning contract");
}
