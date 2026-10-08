// @class: real-backend — SUPER01 on C1's tenant-scoped contract: the SYSTEM_ADMIN, in the PLATFORM portal, creates a tenant admin of a NEW tenant through the real dialog
// (POST /admin/tenants/{t}/users, the tenant is the path), reads the one-time activation link from the DOM, the person activates through the real activation page and logs in to the ADMIN portal.
// The tenant and its workspace are made through the product's own routes (POST /admin/tenants, POST /admin/tenants/{t}/workspaces): nothing here uses the legacy workspace route.
import { activateByLink, loginPortal, navLabels, openPortal, watchApi } from "../lib/portals.mjs";
import { newPage, pageProblems } from "../lib/ui.mjs";
import { Session, randomSecret } from "../lib/api.mjs";
export const id = "E2E-SUPER01", title = "SYSTEM_ADMIN (Platform) → create tenant admin of a new tenant (+ workspace) → activation link → activate → login Admin portal";
export async function run({ cfg, fx, browser, check }) {
  const sys = fx.sessions.admin; const slug = `e2e-${fx.runId}-s01`.toLowerCase(); const username = `e2e-${fx.runId}-ta1`.toLowerCase();
  const t = await sys.post("/admin/tenants", { slug, name: `E2E S01 ${fx.runId}` });
  check.ok("[api] SYSTEM_ADMIN creates a NEW tenant (201, no first admin yet)", t.status === 201 && !!t.body?.id, `status=${t.status} ${t.body?.code}`, "http");
  fx.created.tenants = [...(fx.created.tenants ?? []), t.body.id]; const tenantId = t.body.id;
  const w = await sys.post(`/admin/tenants/${tenantId}/workspaces`, { name: `e2e-${fx.runId}-s01-ws` });
  check.ok("[api] …and a workspace OF THAT TENANT through the tenant route (201, tenantId explicit)", w.status === 201 && w.body?.tenantId === tenantId, `status=${w.status} ${JSON.stringify(w.body)}`, "http");
  fx.created.workspaces.push(w.body.id);

  const page = await newPage(browser); const bad = watchApi(page);
  const sent = []; page.on("request", (r) => { if (r.method() !== "GET" && /\/api\/v1\/admin\//.test(r.url())) sent.push({ m: r.method(), p: new URL(r.url()).pathname, csrf: !!r.headers()["x-xsrf-token"], body: r.postData() }); });
  const landed = await loginPortal(page, cfg, "platform", cfg.adminUser, cfg.adminPassword);
  check.ok("the SYSTEM_ADMIN logs in through the Platform portal", landed.startsWith("/platform") && !/login|no-access/.test(landed), landed);
  await openPortal(page, cfg, "platform", "/users");
  await page.getByTestId("users-create").click(); await page.getByTestId("create-account").waitFor({ timeout: 15_000 });
  check.ok("the dialog: four sections, a tenant selector, NO not-ready notice, NO SYSTEM_ADMIN type", (await page.locator("form[data-testid=create-account] fieldset").count()) === 4 && (await page.getByTestId("acc-tenant").count()) === 1 && (await page.getByTestId("prov-not-ready").count()) === 0 && !(await page.getByTestId("acc-type").innerText()).includes("SYSTEM"));
  await page.getByTestId("acc-username").fill(username); await page.getByTestId("acc-display").fill(`E2E TA ${fx.runId}`); await page.getByTestId("acc-email").fill(`${username}@example.test`);
  await page.getByTestId("acc-tenant").selectOption(tenantId); await page.getByTestId("acc-type").selectOption("TENANT_ADMIN");
  const wsOpts = await page.getByTestId("acc-workspace").locator("option").evaluateAll((o) => o.map((x) => x.value));
  const listed = wsOpts.includes(w.body.id);
  if (listed) await page.getByTestId("acc-workspace").selectOption(w.body.id);
  check.ok("the workspace of the chosen tenant is offered (optional; assigned here)", listed, `options=${wsOpts.length} (H-C1-16: /admin/workspaces is not filtered by tenant; the dialog lists its first page)`);
  if (listed) await page.getByTestId("acc-role").selectOption("WORKSPACE_ADMIN");
  const created = page.waitForResponse((r) => r.request().method() === "POST" && /\/admin\/tenants\/[^/]+\/users$/.test(new URL(r.url()).pathname), { timeout: 15_000 });
  await page.getByTestId("acc-submit").click(); const cr = await created; const body = JSON.parse(cr.request().postData());
  check.ok("POST /admin/tenants/{tenantId}/users → 201; the tenant is the PATH, never in the body; tenantRole TENANT_ADMIN; workspaceId + workspaceRole together; no password", cr.status() === 201 && new URL(cr.url()).pathname.endsWith(`/admin/tenants/${tenantId}/users`) && !("tenantId" in body) && body.tenantRole === "TENANT_ADMIN" && (!listed || (body.workspaceId === w.body.id && body.workspaceRole === "WORKSPACE_ADMIN")) && !("password" in body), `status=${cr.status()} keys=${Object.keys(body).sort()}`, "http");
  const link = await page.locator('input[aria-label="Liên kết"]').inputValue();
  check.ok("the activation link is shown once in the dialog (/auth/activate#token); no password anywhere", /\/auth\/activate#/.test(link) && !/mật khẩu:/i.test(await page.locator("body").innerText()));
  await page.getByRole("button", { name: "Xong" }).click(); await page.getByRole("button", { name: /Tôi đã lưu liên kết/ }).click(); await page.getByTestId("account-created").waitFor();
  const urlAfter = page.url(); const stored = await page.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]));
  const tokenPart = link.split("#")[1] ?? "";
  check.ok("after 'Xong' the token is gone: not in the summary, the URL, localStorage or sessionStorage", tokenPart.length > 20 && !(await page.locator("body").innerHTML()).includes(tokenPart) && !urlAfter.includes(tokenPart) && !stored.includes(tokenPart));
  check.ok("the summary: Chờ kích hoạt, the company AND its role already assigned, the workspace; the only next step is the person's activation", /Chờ kích hoạt/.test(await page.getByTestId("res-status").innerText()) && /Quản trị công ty/.test(await page.getByTestId("res-tenant").innerText()) && (!listed || /Quản trị workspace|ws/.test(await page.getByTestId("res-workspace").innerText())) && (await page.getByTestId("res-pending").locator("li").count()) === 1);
  const members = (await sys.get(`/admin/tenants/${tenantId}/members`)).body ?? [];
  const m = members.find((x) => x.username === username); if (m) fx.created.users.push(m.userId);
  check.ok("[api] the server lists the account as TENANT_ADMIN of the new tenant (already, before activation)", m?.role === "TENANT_ADMIN", JSON.stringify(m), "persistence");
  const tu = sent.filter((x) => x.m === "POST" && x.p === `/api/v1/admin/tenants/${tenantId}/users`);
  check.ok("CSRF + body: the create-account request carries X-XSRF-TOKEN and its body has no tenant / tenantId; no browser request to the legacy workspace route", tu.length === 1 && tu[0].csrf && !/"tenant(Id)?"/.test(tu[0].body ?? "") && !sent.some((x) => x.m === "POST" && /\/api\/v1\/admin\/workspaces$/.test(x.p)), `n=${tu.length} csrf=${tu[0]?.csrf}`, "http");
  const dup = await sys.post(`/admin/tenants/${tenantId}/users`, { username, displayName: "dup" });
  check.ok("[api] the same username again is refused: 409 USERNAME_TAKEN", dup.status === 409 && dup.body?.code === "USERNAME_TAKEN", `status=${dup.status} ${dup.body?.code}`, "http");

  const password = randomSecret();
  check.ok("the new admin activates through the REAL activation page", await activateByLink(browser, link.replace(/^https?:\/\/[^/]+/, cfg.platformUrl), password));
  const own = new Session(cfg.studio, "s01ta"); const meTa = await own.login(username, password); const wsRow = (meTa?.workspaces ?? []).find((x) => x.id === w.body.id);
  check.ok("[api] after activation /auth/me lists the chosen workspace with MEMBER_MANAGE (WORKSPACE_ADMIN, from the server) and the tenant TENANT_MEMBERS", !listed || (!!wsRow && (wsRow.permissions ?? []).includes("MEMBER_MANAGE") && (meTa.permissions ?? []).includes("TENANT_MEMBERS")), JSON.stringify({ ws: wsRow?.permissions, tenant: meTa?.permissions }), "http");
  const adminPage = await newPage(browser); const where = await loginPortal(adminPage, cfg, "admin", username, password);
  check.ok("…and logs in through the ADMIN portal: not refused", where.startsWith("/admin") && !/login|no-access/.test(where), where);
  const nav = await navLabels(adminPage);
  check.ok("…sees Công ty của tôi and Người dùng, not the system-wide sections", nav.includes("Công ty của tôi") && nav.includes("Người dùng") && !nav.includes("Nhật ký kiểm toán"), nav.join(" | "));
  fx.users.superTa = { id: m?.userId, username, password, tenantId, workspaceId: listed ? w.body.id : null };
  await adminPage.context().close();
  check.ok("no unexpected 4xx/5xx from the portal's calls", bad.filter((x) => !/→ (409|403|404)|auth\/me → 401/.test(x)).length === 0, bad.join(" | "));
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
