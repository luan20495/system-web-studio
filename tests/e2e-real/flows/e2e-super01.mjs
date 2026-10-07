// @class: real-backend — SUPER01: the SYSTEM_ADMIN creates a TENANT ADMIN account through the PLATFORM portal (real UI → existing POST /admin/users), the account activates through the real activation link, the company role is assigned in the
// tenant page, and the new admin can open the ADMIN portal. What is NOT possible today is recorded as BLOCKED with the owner: choosing a tenant for a NEW workspace / listing workspaces by tenant (H-C1-14); the Admin-side creation is ADMIN01.
import { Blocked } from "../lib/report.mjs";
import { activateByLink, loginPortal, navLabels, openPortal, watchApi } from "../lib/portals.mjs";
import { newPage, pageProblems } from "../lib/ui.mjs";
import { randomSecret } from "../lib/api.mjs";
export const id = "E2E-SUPER01", title = "Super admin creates a tenant admin (Platform UI) → activation link → company role → the new admin opens the Admin portal";
const DEFAULT_TENANT = "00000000-0000-0000-0000-000000000001";
export async function run({ cfg, fx, browser, check }) {
  const sys = fx.sessions.admin; const username = `e2e-${fx.runId}-ta`.toLowerCase();
  const page = await newPage(browser); const bad = watchApi(page);
  const landed = await loginPortal(page, cfg, "platform", cfg.adminUser, cfg.adminPassword);
  check.ok("the SYSTEM_ADMIN logs in through the Platform portal", landed.startsWith("/platform") && !/login|no-access/.test(landed), landed);
  await openPortal(page, cfg, "platform", "/users");
  await page.getByTestId("users-create").click(); await page.getByTestId("create-account").waitFor({ timeout: 15_000 });
  check.ok("the dialog is the new 'Tạo tài khoản': four sections, no NOT_READY notice, no SYSTEM_ADMIN type", (await page.locator("form[data-testid=create-account] fieldset").count()) === 4 && (await page.getByTestId("prov-not-ready").count()) === 0 && !(await page.getByTestId("acc-type").innerText()).includes("SYSTEM"));
  await page.getByTestId("acc-username").fill(username); await page.getByTestId("acc-display").fill(`E2E TA ${fx.runId}`); await page.getByTestId("acc-email").fill(`${username}@example.test`);
  await page.getByTestId("acc-type").selectOption("TENANT_ADMIN"); await page.getByTestId("acc-tenant").selectOption(DEFAULT_TENANT);
  const wsName = `e2e-${fx.runId}-ws-A`; const wsOpt = page.locator("#x", {}).first();
  const val = await page.getByTestId("acc-workspace").locator("option", { hasText: wsName }).first().getAttribute("value").catch(() => null);
  if (!val) throw new Blocked("C5", `the fixture workspace ${wsName} is not in the first page of /admin/workspaces (25): the dialog lists the first page only`, "fixture");
  await page.getByTestId("acc-workspace").selectOption(val);
  const created = page.waitForResponse((r) => r.request().method() === "POST" && /\/admin\/users$/.test(new URL(r.url()).pathname), { timeout: 15_000 });
  await page.getByTestId("acc-submit").click(); const cr = await created;
  check.ok("POST /admin/users → 201 with exactly {username, displayName, email, workspaceId, role}: no tenant id, no password", cr.status() === 201 && JSON.stringify(Object.keys(JSON.parse(cr.request().postData())).sort()) === JSON.stringify(["displayName", "email", "role", "username", "workspaceId"]) && JSON.parse(cr.request().postData()).role === "WORKSPACE_ADMIN", `status=${cr.status()} ${cr.request().postData()}`, "http");
  const link = await page.locator('input[aria-label="Liên kết"]').inputValue();
  check.ok("the activation link is shown once in the dialog (one-time, /auth/activate#token), the platform never shows a password", /\/auth\/activate#/.test(link) && !/password|mật khẩu:/i.test(await page.locator("body").innerText()));
  await page.getByRole("button", { name: "Xong" }).click(); await page.getByTestId("account-created").waitFor();
  check.ok("the summary: account, status Chờ kích hoạt, workspace, role, and the company role as a NEXT step (not done)", /Chờ kích hoạt/.test(await page.getByTestId("res-status").innerText()) && /ws-A/.test(await page.getByTestId("res-workspace").innerText()) && /Quản trị công ty/.test(await page.getByTestId("res-pending").innerText()) && /Chưa gán/.test(await page.getByTestId("res-tenant").innerText()));
  const u = (await sys.get("/admin/users?q=" + encodeURIComponent(username))).body?.items?.[0]; fx.created.users.push(u.id);
  const wm = (await sys.get(`/workspaces/${fx.workspaces.A}/members`)).body ?? [];
  check.ok("[api] the account exists, is pending (not activated) and is WORKSPACE_ADMIN of the chosen workspace", u?.username === username && u.pending === true && wm.some((m) => m.username === username && m.role === "WORKSPACE_ADMIN"), JSON.stringify({ pending: u?.pending }), "persistence");
  const dup = await sys.post("/admin/users", { username, displayName: "dup", workspaceId: fx.workspaces.A, role: "VIEWER" });
  check.ok("[api] a second account with the same username is refused (409 USERNAME_TAKEN)", dup.status === 409 && dup.body?.code === "USERNAME_TAKEN", `status=${dup.status} ${dup.body?.code}`, "http");

  const password = randomSecret();
  check.ok("the new admin activates through the REAL activation page (set password)", await activateByLink(browser, link.replace(/^https?:\/\/[^/]+/, cfg.platformUrl), password));
  await openPortal(page, cfg, "platform", `/tenants/${DEFAULT_TENANT}`);
  const row = page.locator(`[data-testid^="tm:"]`, { hasText: username }).first(); await row.waitFor({ timeout: 15_000 });
  await row.locator("select").selectOption("TENANT_ADMIN"); await page.getByText("Đã đổi vai trò.").waitFor({ timeout: 10_000 });
  const tm = (await sys.get(`/admin/tenants/${DEFAULT_TENANT}/members`)).body ?? [];
  check.ok("the company role is assigned AFTER activation in the tenant page: the server lists the account as TENANT_ADMIN", tm.some((m) => m.userId === u.id && m.role === "TENANT_ADMIN"), "", "persistence");
  const adminPage = await newPage(browser); const where = await loginPortal(adminPage, cfg, "admin", username, password);
  check.ok("the new tenant admin logs in through the ADMIN portal and is not refused", where.startsWith("/admin") && !/login|no-access/.test(where), where);
  check.ok("…and sees Công ty của tôi and Người dùng, not the system-wide sections", (await navLabels(adminPage)).includes("Công ty của tôi") && (await navLabels(adminPage)).includes("Người dùng") && !(await navLabels(adminPage)).includes("Nhật ký kiểm toán"));
  await adminPage.context().close();
  check.ok("no unexpected 4xx/5xx from the portal's calls", bad.filter((x) => !/→ (409|403|404)|auth\/me → 401/.test(x)).length === 0, bad.join(" | "));
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
  fx.users.superTa = { id: u.id, username, password };
  await sys.put(`/admin/tenants/${DEFAULT_TENANT}/members/${u.id}`, { role: "MEMBER" });
  throw new Blocked("C1", "a tenant admin can be made only for a tenant that already has workspaces (DEFAULT here): a NEW tenant's workspace cannot be created or listed by tenant (POST /admin/workspaces takes no tenant, /admin/workspaces rows carry no tenantId) — H-C1-14", "H-C1-14");
}
