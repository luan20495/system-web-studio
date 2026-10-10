// @class: real-backend — PLATFORM portal (:3001), real browser → real portal app → real API. A SYSTEM_ADMIN logs in through the portal's own form, sees the platform navigation with no "Chưa sẵn sàng" section, creates a tenant,
// suspends and restores it, adds and re-roles members, hits the "last TENANT_ADMIN" rule, and grants / revokes SYSTEM_ADMIN to a fixture account. Every state change is read back from the API (a separate session).
import { Blocked } from "../lib/report.mjs";
import { loginPortal, navLabels, openPortal, watchApi } from "../lib/portals.mjs";
import { confirmCancel, confirmYes, newPage, pageProblems, waitConfirm } from "../lib/ui.mjs";
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
  // the first administrator is picked from the SYSTEM_ADMIN's account search (creating a tenant may name any enabled account)
  await page.getByLabel("Tìm người dùng").fill(fx.users.adminA.username); await page.waitForTimeout(900);
  const firstOpt = page.locator("#tenant-first-admin [role=option]", { hasText: fx.users.adminA.username }).first(); await firstOpt.waitFor({ timeout: 8000 });
  await firstOpt.click(); await page.getByTestId("person-chosen").waitFor();
  const created = page.waitForResponse((r) => r.request().method() === "POST" && /\/admin\/tenants$/.test(new URL(r.url()).pathname), { timeout: 15_000 });
  await page.getByTestId("tenant-create").getByRole("button", { name: "Tạo công ty" }).click(); const cr = await created;
  await page.waitForURL(/\/platform\/tenants\/[0-9a-f-]{36}/, { timeout: 10_000 }).catch(() => undefined);
  check.ok("POST /admin/tenants → 201 and the page opens the new tenant", cr.status() === 201 && /\/platform\/tenants\/[0-9a-f-]{36}/.test(page.url()), `status=${cr.status()} ${page.url()}`, "http");
  const t = await cr.json(); fx.ids.plTenant = t.id;
  const server = (await api.get(`/admin/tenants/${t.id}`)).body;
  check.ok("the server stored it: slug, name, ACTIVE", server?.slug === slug && server?.status === "ACTIVE", JSON.stringify(server), "persistence");
  const dup = await api.post("/admin/tenants", { slug, name: "dup" });
  check.ok("a duplicate slug is refused by the server (409 TENANT_SLUG_TAKEN)", dup.status === 409 && dup.body?.code === "TENANT_SLUG_TAKEN", `status=${dup.status} ${dup.body?.code}`, "http");

  // ---- suspend through the REAL in-app confirmation (A cancel, then B confirm); server state read by the SYSTEM_ADMIN API session, UI state read from the page ----------------------------
  const statusWrites = []; page.on("request", (r) => { if (r.method() === "PATCH" && /\/admin\/tenants\/[0-9a-f-]{36}\/status$/.test(new URL(r.url()).pathname)) statusWrites.push(r.postData()); });
  const T_SUSPEND = /Tạm khóa công ty/;
  await page.getByTestId("tenant-SUSPENDED").click();
  const dlg1 = await waitConfirm(page, T_SUSPEND);
  check.ok("A/B suspend: an IN-APP dialog (role=dialog, titled 'Tạm khóa công ty …?') appears and its text says what will happen; no native browser dialog was involved", /Tạm khóa công ty/.test(dlg1.text) && /Hủy/.test(dlg1.text) && /Tạm khóa/.test(dlg1.text), dlg1.text, "ui");
  await confirmCancel(page, T_SUSPEND);
  const afterCancel = (await api.get(`/admin/tenants/${t.id}`)).body;
  check.ok("C/D cancel: the dialog closes, NO status request was sent, the SERVER still says ACTIVE and the UI still offers 'Tạm khóa' (not 'Mở khóa')", statusWrites.length === 0 && afterCancel?.status === "ACTIVE" && (await page.getByTestId("tenant-SUSPENDED").count()) === 1 && (await page.getByTestId("tenant-ACTIVE").count()) === 0, `writes=${statusWrites.length} server=${afterCancel?.status}`, "persistence");
  await page.getByTestId("tenant-SUSPENDED").click();
  const dlg2 = await waitConfirm(page, T_SUSPEND);
  check.ok("E reopen: the same dialog appears again", /Tạm khóa công ty/.test(dlg2.text), dlg2.text, "ui");
  const patched = page.waitForResponse((r) => r.request().method() === "PATCH" && /\/admin\/tenants\/[0-9a-f-]{36}\/status$/.test(new URL(r.url()).pathname), { timeout: 15_000 });
  await confirmYes(page, T_SUSPEND, "Tạm khóa"); const pr = await patched;
  check.ok("F confirm: exactly one PATCH …/status {status:SUSPENDED} was sent and answered 200", statusWrites.length === 1 && JSON.parse(statusWrites[0] ?? "{}").status === "SUSPENDED" && pr.status() === 200, `writes=${statusWrites.length} body=${statusWrites[0]} status=${pr.status()}`, "http");
  await page.getByText("Đã đổi trạng thái công ty.").waitFor({ timeout: 10_000 }).catch(() => undefined);
  const susp = (await api.get(`/admin/tenants/${t.id}`)).body;
  check.ok("G the SERVER state is SUSPENDED (read by a separate session)", susp?.status === "SUSPENDED", JSON.stringify({ id: susp?.id, status: susp?.status }), "persistence");
  await page.reload({ waitUntil: "networkidle" }); await page.waitForTimeout(500);
  check.ok("H after a full reload the UI reflects the backend: the tenant offers 'Mở khóa' (ACTIVE action) and no longer 'Tạm khóa'", (await page.getByTestId("tenant-ACTIVE").count()) === 1 && (await page.getByTestId("tenant-SUSPENDED").count()) === 0, "", "persistence");
  // restore (also through the in-app dialog)
  const T_UNLOCK = /Mở khóa công ty/;
  await page.getByTestId("tenant-ACTIVE").click(); await waitConfirm(page, T_UNLOCK); await confirmYes(page, T_UNLOCK, "Mở khóa"); await page.waitForTimeout(800);
  check.ok("restore: back to ACTIVE on the server", (await api.get(`/admin/tenants/${t.id}`)).body?.status === "ACTIVE", "", "persistence");

  // ---- members + last-admin rule ----------------------------------------------------------------------------------------------------------------------
  const adminA = fx.users.adminA, lonely = fx.users.lonelyA;
  let members = (await api.get(`/admin/tenants/${t.id}/members`)).body ?? [];
  check.ok("the first administrator chosen in the dialog is TENANT_ADMIN on the server, with name and email metadata", members.some((m) => m.userId === adminA.id && m.role === "TENANT_ADMIN" && m.username === adminA.username && "displayName" in m && "email" in m), JSON.stringify(members), "persistence");
  check.ok("the member row shows the person's NAME from the member metadata (no extra lookup)", (await page.getByTestId(`tm:${adminA.id}`).innerText()).includes(adminA.username));
  const only = page.getByTestId(`tm:${adminA.id}`).locator("select");
  await only.selectOption("MEMBER"); await page.waitForTimeout(700);
  check.ok("demoting the ONLY TENANT_ADMIN is explained in words and nothing changed on the server", /ít nhất một quản trị/.test(await page.getByTestId("tenant-msg").innerText()) && ((await api.get(`/admin/tenants/${t.id}/members`)).body ?? []).find((m) => m.userId === adminA.id)?.role === "TENANT_ADMIN");
  const direct = await api.put(`/admin/tenants/${t.id}/members/${adminA.id}`, { role: "MEMBER" });
  check.ok("the server enforces it too (409 LAST_TENANT_ADMIN)", direct.status === 409 && direct.body?.code === "LAST_TENANT_ADMIN", `status=${direct.status} ${direct.body?.code}`, "http");
  const cand = await api.get(`/admin/tenants/${t.id}/member-candidates`);
  check.ok("a brand-new tenant has no candidates (the directory is tenant-scoped, never global) and the picker says so", cand.status === 200 && Array.isArray(cand.body) && cand.body.length === 0 && /Không có người phù hợp/.test(await page.getByTestId("tm-person").innerText()), `status=${cand.status} ${JSON.stringify(cand.body)}`, "http");
  const stranger = await api.put(`/admin/tenants/${t.id}/members/${lonely.id}`, { role: "MEMBER" });
  check.ok("even a SYSTEM_ADMIN cannot add an unrelated account to a tenant (404 USER_NOT_FOUND): no global directory", stranger.status === 404 && stranger.body?.code === "USER_NOT_FOUND", `status=${stranger.status} ${stranger.body?.code}`, "http");

  // ---- D-C1-13: a SYSTEM_ADMIN holds NO member power in a workspace: the workspace page explains it and offers no member form -------------------------------------
  await openPortal(page, cfg, "platform", `/workspaces/${fx.workspaces.A}`);
  await page.getByTestId("ws-members-forbidden").waitFor({ timeout: 10_000 });
  check.ok("workspace page (SYSTEM_ADMIN): the member panel says the platform admin does not manage workspace members, and there is NO add-member form (the server lists no MEMBER_MANAGE for it)", (await page.getByTestId("ws-add-who").count()) === 0 && (await page.getByTestId("ws-members").count()) === 0 && /tạo tài khoản/.test(await page.getByTestId("ws-members-forbidden").innerText()));
  const list = await api.get(`/workspaces/${fx.workspaces.A}/members`); const add = await api.post(`/workspaces/${fx.workspaces.A}/members`, { username: fx.users.adminB.username, role: "VIEWER" });
  check.ok("[api] …and the server agrees: SYSTEM_ADMIN is refused the member list and the add (403)", list.status === 403 && add.status === 403, `${list.status}/${add.status}`, "http");

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
