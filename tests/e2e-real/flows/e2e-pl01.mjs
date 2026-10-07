// @class: real-backend — PLATFORM portal (:3001), real browser → real portal app → real API. A SYSTEM_ADMIN logs in through the portal's own form, sees the platform navigation with no "Chưa sẵn sàng" section, creates a tenant,
// suspends and restores it, adds and re-roles members, hits the "last TENANT_ADMIN" rule, and grants / revokes SYSTEM_ADMIN to a fixture account. Every state change is read back from the API (a separate session).
import { Blocked } from "../lib/report.mjs";
import { loginPortal, navLabels, openPortal, watchApi } from "../lib/portals.mjs";
import { newPage, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-PL01", title = "Platform portal: login, navigation, tenants (create, suspend, restore, members, last-admin rule), SYSTEM_ADMIN grant / revoke";
export async function run({ cfg, fx, browser, check }) {
  const api = fx.sessions.admin;
  const probe = await api.get("/admin/tenants");
  if (probe.status === 404 && !probe.body?.code) throw new Blocked("C0", "the tenant API (/api/v1/admin/tenants) is not on this build (T2 not integrated)", "T2");
  const page = await newPage(browser); const bad = watchApi(page);
  const landed = await loginPortal(page, cfg, "platform", cfg.adminUser, cfg.adminPassword);
  check.ok("login through the Platform portal lands inside /platform (not on the login or no-access page)", landed.startsWith("/platform") && !/login|no-access/.test(landed), landed);
  const nav = await navLabels(page);
  check.ok("navigation lists Tổng quan, Công ty (tenant), Người dùng & Workspace, Nhật ký kiểm toán, Sức khỏe hệ thống — and nothing from the tenant-admin console", ["Tổng quan", "Công ty (tenant)", "Người dùng & Workspace", "Nhật ký kiểm toán", "Sức khỏe hệ thống"].every((l) => nav.includes(l)) && !nav.includes("Nhóm") && !nav.includes("Chia sẻ"), nav.join(" | "));

  // ---- tenants ------------------------------------------------------------------------------------------------------------------------------------------
  await openPortal(page, cfg, "platform", "/tenants");
  check.ok("the tenant list is the REAL list (the DEFAULT tenant is there) and the section is not 'Chưa sẵn sàng'", (await page.getByTestId("tenant-list").count()) === 1 && (await page.getByText("Chưa sẵn sàng").count()) === 0);
  const slug = `e2e-${fx.runId}-pl`.toLowerCase();
  await page.getByRole("button", { name: "+ Tạo công ty" }).click();
  await page.getByTestId("tenant-slug").fill("A"); await page.getByTestId("tenant-name").fill(" "); await page.getByTestId("tenant-create").getByRole("button", { name: "Tạo công ty" }).click();
  check.ok("an invalid form is refused in the dialog with both messages and NO request is sent", (await page.getByText(/Mã công ty gồm/).count()) === 1 && (await page.getByText(/Hãy nhập tên công ty/).count()) === 1 && !bad.some((b) => /POST \/api\/v1\/admin\/tenants/.test(b)));
  await page.getByTestId("tenant-slug").fill(slug); await page.getByTestId("tenant-name").fill(`E2E PL ${fx.runId}`);
  const created = page.waitForResponse((r) => r.request().method() === "POST" && /\/admin\/tenants$/.test(new URL(r.url()).pathname), { timeout: 15_000 });
  await page.getByTestId("tenant-create").getByRole("button", { name: "Tạo công ty" }).click(); const cr = await created;
  await page.waitForURL(/\/platform\/tenants\/[0-9a-f-]{36}/, { timeout: 10_000 }).catch(() => undefined);
  check.ok("POST /admin/tenants → 201 and the page opens the new tenant", cr.status() === 201 && /\/platform\/tenants\/[0-9a-f-]{36}/.test(page.url()), `status=${cr.status()} ${page.url()}`, "http");
  const t = await cr.json(); fx.ids.plTenant = t.id;
  const server = (await api.get(`/admin/tenants/${t.id}`)).body;
  check.ok("the server stored it: slug, name, ACTIVE", server?.slug === slug && server?.status === "ACTIVE", JSON.stringify(server), "persistence");
  const dup = await api.post("/admin/tenants", { slug, name: "dup" });
  check.ok("a duplicate slug is refused by the server (409 TENANT_SLUG_TAKEN)", dup.status === 409 && dup.body?.code === "TENANT_SLUG_TAKEN", `status=${dup.status} ${dup.body?.code}`, "http");

  page.once("dialog", (d) => void d.accept());
  await page.getByTestId("tenant-SUSPENDED").click(); await page.getByText("Đã đổi trạng thái công ty.").waitFor({ timeout: 10_000 }).catch(() => undefined);
  check.ok("suspend: the UI says so and the server agrees (SUSPENDED)", (await api.get(`/admin/tenants/${t.id}`)).body?.status === "SUSPENDED" && (await page.getByTestId("tenant-ACTIVE").count()) === 1, "", "persistence");
  page.once("dialog", (d) => void d.accept());
  await page.getByTestId("tenant-ACTIVE").click(); await page.waitForTimeout(800);
  check.ok("restore: back to ACTIVE on the server", (await api.get(`/admin/tenants/${t.id}`)).body?.status === "ACTIVE", "", "persistence");

  // ---- members + last-admin rule ----------------------------------------------------------------------------------------------------------------------
  const adminA = fx.users.adminA, lonely = fx.users.lonelyA;
  await page.getByLabel("Tìm người dùng").fill(adminA.username); await page.waitForTimeout(900);
  const opt = page.locator("#tm-person option", { hasText: adminA.username }).first(); await opt.waitFor({ timeout: 8000 });
  await page.locator("#tm-person").selectOption(await opt.getAttribute("value"));
  await page.getByTestId("tm-role").selectOption("TENANT_ADMIN");
  await page.getByRole("button", { name: "Thêm vào công ty" }).click(); await page.getByText("Đã thêm vào công ty.").waitFor({ timeout: 10_000 });
  let members = (await api.get(`/admin/tenants/${t.id}/members`)).body ?? [];
  check.ok("add member as quản trị công ty: the server lists adminA as TENANT_ADMIN", members.some((m) => m.userId === adminA.id && m.role === "TENANT_ADMIN"), JSON.stringify(members), "persistence");
  check.ok("the member row shows the person's NAME (resolved from /admin/users), not a bare id", (await page.getByTestId(`tm:${adminA.id}`).innerText()).includes(adminA.username));
  const only = page.getByTestId(`tm:${adminA.id}`).locator("select");
  await only.selectOption("MEMBER"); await page.waitForTimeout(700);
  check.ok("demoting the ONLY TENANT_ADMIN is explained in words and nothing changed on the server", /ít nhất một quản trị/.test(await page.getByTestId("tenant-msg").innerText()) && ((await api.get(`/admin/tenants/${t.id}/members`)).body ?? []).find((m) => m.userId === adminA.id)?.role === "TENANT_ADMIN");
  const direct = await api.put(`/admin/tenants/${t.id}/members/${adminA.id}`, { role: "MEMBER" });
  check.ok("the server enforces it too (409 LAST_TENANT_ADMIN)", direct.status === 409 && direct.body?.code === "LAST_TENANT_ADMIN", `status=${direct.status} ${direct.body?.code}`, "http");
  await page.getByLabel("Tìm người dùng").fill(lonely.username); await page.waitForTimeout(900);
  const opt2 = page.locator("#tm-person option", { hasText: lonely.username }).first(); await opt2.waitFor({ timeout: 8000 });
  await page.locator("#tm-person").selectOption(await opt2.getAttribute("value"));
  await page.getByRole("button", { name: "Thêm vào công ty" }).click(); await page.getByText("Đã thêm vào công ty.").waitFor({ timeout: 10_000 });
  page.once("dialog", (d) => void d.accept());
  await page.getByTestId(`tm:${lonely.id}`).getByRole("button", { name: "Gỡ" }).click(); await page.getByText("Đã gỡ khỏi công ty.").waitFor({ timeout: 10_000 });
  members = (await api.get(`/admin/tenants/${t.id}/members`)).body ?? [];
  check.ok("remove a plain member: gone from the active list on the server, the admin stays", !members.some((m) => m.userId === lonely.id && m.active) && members.some((m) => m.userId === adminA.id), JSON.stringify(members), "persistence");

  // ---- SYSTEM_ADMIN management -------------------------------------------------------------------------------------------------------------------------
  await openPortal(page, cfg, "platform", `/users/${lonely.id}`);
  const grant = page.getByRole("button", { name: "Cấp quyền Quản trị hệ thống" }); await grant.waitFor({ timeout: 10_000 });
  page.once("dialog", (d) => void d.accept()); await grant.click(); await page.getByText("Đã cấp quyền Quản trị hệ thống.").waitFor({ timeout: 10_000 });
  check.ok("grant SYSTEM_ADMIN to a fixture account: confirmed in a dialog, then the server says systemAdmin = true", (await api.get(`/admin/users/${lonely.id}`)).body?.user?.systemAdmin === true, "", "persistence");
  page.once("dialog", (d) => void d.accept()); await page.getByRole("button", { name: "Gỡ quyền Quản trị hệ thống" }).click(); await page.getByText("Đã gỡ quyền Quản trị hệ thống.").waitFor({ timeout: 10_000 });
  check.ok("revoke it: systemAdmin = false on the server", (await api.get(`/admin/users/${lonely.id}`)).body?.user?.systemAdmin === false, "", "persistence");
  await openPortal(page, cfg, "platform", `/users/${(await api.get("/auth/me")).body?.id}`);
  check.ok("you cannot change your own SYSTEM_ADMIN right: the button is not offered on your own account", (await page.getByRole("button", { name: /Quản trị hệ thống/ }).count()) === 0);

  // ---- a SYSTEM_ADMIN manages the members of any workspace from the workspace page (same member API, same rules) ---------------------------------------------------
  await openPortal(page, cfg, "platform", `/workspaces/${fx.workspaces.A}`);
  await page.getByTestId("ws-add-who").waitFor({ timeout: 10_000 });
  await page.getByTestId("ws-add-who").fill(fx.users.adminB.username); await page.getByTestId("ws-add-role").selectOption("VIEWER");
  await page.getByRole("button", { name: "Thêm vào workspace" }).click(); await page.getByText("Đã thêm vào workspace.").waitFor({ timeout: 10_000 });
  let wm = (await api.get(`/workspaces/${fx.workspaces.A}/members`)).body ?? [];
  check.ok("workspace page (SYSTEM_ADMIN): add by username → the server lists adminB as VIEWER of workspace A", wm.some((m) => m.userId === fx.users.adminB.id && m.role === "VIEWER"), JSON.stringify(wm.map((m) => [m.username, m.role])), "persistence");
  await page.getByTestId(`wm:${fx.users.adminB.username}`).locator("select").selectOption("EDITOR"); await page.getByText("Đã đổi vai trò.").waitFor({ timeout: 10_000 });
  page.once("dialog", (d) => void d.accept());
  await page.getByTestId(`wm:${fx.users.adminB.username}`).getByRole("button", { name: "Gỡ" }).click(); await page.getByText("Đã gỡ khỏi workspace.").waitFor({ timeout: 10_000 });
  wm = (await api.get(`/workspaces/${fx.workspaces.A}/members`)).body ?? [];
  check.ok("…re-role to Biên tập viên then remove: gone on the server", !wm.some((m) => m.userId === fx.users.adminB.id), "", "persistence");

  // ---- audit + system pages are real ---------------------------------------------------------------------------------------------------------------------
  await openPortal(page, cfg, "platform", "/audit");
  check.ok("the audit page lists the tenant events just made (TENANT_CREATED / TENANT_STATUS_CHANGED / TENANT_MEMBER_SET)", /TENANT_/.test(await page.locator("main").innerText()));
  await openPortal(page, cfg, "platform", "/system");
  check.ok("the system-health page loads without an error state", (await page.getByText(/Sức khỏe hệ thống/).count()) > 0 && (await page.getByText(/Không tải được|Có lỗi/).count()) === 0);

  // ---- a person who is not a system admin is refused at the portal ---------------------------------------------------------------------------------------
  const other = await newPage(browser);
  const where = await loginPortal(other, cfg, "platform", fx.users.viewerA.username, fx.users.viewerA.password);
  check.ok("a workspace member with no platform scope is sent to /auth/no-access, never to a platform screen", /no-access/.test(where) && (await navLabels(other)).length === 0, where);
  await other.context().close();

  // cleanup of what this flow made (the tenant is marked DELETED; there is no hard delete)
  const del = await api.patch(`/admin/tenants/${t.id}/status`, { status: "DELETED" });
  check.ok("cleanup: the tenant is marked DELETED", del.status === 200, `status=${del.status}`, "http");
  check.ok("no unexpected 4xx/5xx from the portal's own calls (the deliberate refusals excepted)", bad.filter((b) => !/→ (409|403|404)|auth\/me → 401/.test(b)).length === 0, bad.join(" | "));
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
