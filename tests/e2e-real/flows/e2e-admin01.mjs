// @class: real-backend — ADMIN01 on C1's tenant-scoped contract: a TENANT ADMIN, in the ADMIN portal → Người dùng → Tạo tài khoản, makes a workspace admin (with a workspace created through the TENANT route) and an app creator,
// each by invitation (one-time activation link read from the DOM), and the people activate through the real activation page. The tenant comes from the session; there is no tenant selector and no SYSTEM_ADMIN type.
// Setup (API, product routes): a NEW tenant, its first tenant admin. Everything the tenant admin does after that is the real browser against the real backend.
import { activateByLink, loginPortal, makeTenant, makeTenantUser, navLabels, openPortal, watchApi } from "../lib/portals.mjs";
import { newPage, pageProblems } from "../lib/ui.mjs";
import { Session, randomSecret } from "../lib/api.mjs";
export const id = "E2E-ADMIN01", title = "Tenant admin (Admin portal) → Người dùng → create workspace admin + app creator by invitation → activation → the creator can sign in";
export async function run({ cfg, fx, browser, check }) {
  const sys = fx.sessions.admin;
  const tenant = await makeTenant(fx, "a01", { workspace: false });
  const ta = await makeTenantUser(fx, cfg, tenant.id, "a01ta", { tenantRole: "TENANT_ADMIN" });
  const page = await newPage(browser); const bad = watchApi(page);
  const where = await loginPortal(page, cfg, "admin", ta.username, ta.password);
  check.ok("the tenant admin logs in through the ADMIN portal", where.startsWith("/admin") && !/login|no-access/.test(where), where);
  check.ok("navigation has Người dùng and Công ty của tôi", (await navLabels(page)).includes("Người dùng") && (await navLabels(page)).includes("Công ty của tôi"), (await navLabels(page)).join(" | "));
  await openPortal(page, cfg, "admin", "/people");
  check.ok("Người dùng: the tenant is the SESSION's (read-only), the create action is enabled, no not-ready notice", (await page.getByTestId("people-tenant").getAttribute("readonly")) !== null && (await page.getByTestId("people-tenant").inputValue()).includes("E2E a01") && (await page.getByTestId("people-create").isEnabled()) && (await page.getByTestId("people-not-ready").count()) === 0);

  // ---- 1. a workspace admin + a NEW workspace of this tenant ---------------------------------------------------------------------------------------------
  const wsaName = `e2e-${fx.runId}-a01wsa`.toLowerCase(); const wsName = `e2e-${fx.runId}-a01-ws`;
  await page.getByTestId("people-create").click(); await page.getByTestId("create-account").waitFor({ timeout: 15_000 });
  const types = await page.getByTestId("acc-type").locator("option").evaluateAll((o) => o.map((x) => x.value));
  check.ok("the dialog: NO tenant selector (fixed, read-only tenant), the types never include SYSTEM_ADMIN (tenant admin may be offered)", (await page.getByTestId("acc-tenant").count()) === 0 && (await page.getByTestId("acc-tenant-fixed").inputValue()).includes("E2E a01") && !types.some((t) => /SYSTEM/.test(t)) && types.includes("TENANT_ADMIN") && types.includes("USER"), types.join(","));
  await page.getByTestId("acc-username").fill(wsaName); await page.getByTestId("acc-display").fill("E2E WS admin"); await page.getByTestId("acc-type").selectOption("WORKSPACE_ADMIN");
  await page.getByTestId("acc-workspace").selectOption("__new"); await page.getByTestId("acc-new-ws").fill(wsName);
  const wsResp = page.waitForResponse((r) => r.request().method() === "POST" && /\/admin\/tenants\/[^/]+\/workspaces$/.test(new URL(r.url()).pathname), { timeout: 20_000 });
  const userResp = page.waitForResponse((r) => r.request().method() === "POST" && /\/admin\/tenants\/[^/]+\/users$/.test(new URL(r.url()).pathname), { timeout: 20_000 });
  await page.getByTestId("acc-submit").click(); const wr = await wsResp, ur = await userResp; const ub = JSON.parse(ur.request().postData());
  const wsBody = await wr.json();
  check.ok("the workspace is created through the TENANT route (POST /admin/tenants/{tenant}/workspaces → 201, tenantId = the session's tenant) — never the legacy POST /admin/workspaces", wr.status() === 201 && new URL(wr.url()).pathname.endsWith(`/admin/tenants/${tenant.id}/workspaces`) && wsBody.tenantId === tenant.id, `status=${wr.status()} ${new URL(wr.url()).pathname}`, "http");
  const wsId = wsBody.id; fx.created.workspaces.push(wsId);
  check.ok("POST /admin/tenants/{tenant}/users → 201 with tenantRole MEMBER, that workspace and WORKSPACE_ADMIN together; the tenant only in the path", ur.status() === 201 && new URL(ur.url()).pathname.endsWith(`/admin/tenants/${tenant.id}/users`) && !("tenantId" in ub) && ub.tenantRole === "MEMBER" && ub.workspaceId === wsId && ub.workspaceRole === "WORKSPACE_ADMIN", `status=${ur.status()} ${JSON.stringify(Object.keys(ub))}`, "http");
  const linkA = await page.locator('input[aria-label="Liên kết"]').inputValue(); const tokenA = linkA.split("#")[1] ?? "";
  check.ok("the activation link is shown once (/auth/activate#token)", /\/auth\/activate#/.test(linkA) && tokenA.length > 20);
  await page.getByRole("button", { name: "Xong" }).click(); await page.getByTestId("account-created").waitFor();
  check.ok("the summary shows account, Chờ kích hoạt, the company and the workspace with its role; the token is gone from the page", /Chờ kích hoạt/.test(await page.getByTestId("res-status").innerText()) && (await page.getByTestId("res-workspace").innerText()).includes(wsName) && !(await page.locator("body").innerHTML()).includes(tokenA));
  const pwA = randomSecret();
  check.ok("the workspace admin activates through the REAL activation page", await activateByLink(browser, linkA.replace(/^https?:\/\/[^/]+/, cfg.adminUrl), pwA));
  const wsaSession = new Session(cfg.studio, "a01wsa"); await wsaSession.login(wsaName, pwA);
  const wsaMe = (await wsaSession.get("/auth/me")).body; const wsaWs = (wsaMe?.workspaces ?? []).find((w) => w.id === wsId);
  check.ok("[api] the workspace admin signs in; /auth/me lists the new workspace with MEMBER_MANAGE (server permission, not a role name)", !!wsaWs && (wsaWs.permissions ?? []).includes("MEMBER_MANAGE"), JSON.stringify(wsaWs?.permissions), "http");
  fx.created.users.push(...((await sys.get(`/admin/tenants/${tenant.id}/members`)).body ?? []).filter((m) => m.username === wsaName).map((m) => m.userId));

  // ---- 2. an app creator, optionally into the workspace -----------------------------------------------------------------------------------------------------
  const creatorName = `e2e-${fx.runId}-a01cr`.toLowerCase();
  await openPortal(page, cfg, "admin", "/people"); await page.getByTestId("people-create").click();
  await page.getByTestId("create-account").waitFor({ timeout: 15_000 });
  const offered = await page.getByTestId("acc-workspace").locator("option").evaluateAll((o) => o.map((x) => x.value));
  const canPick = offered.includes(wsId);
  await page.getByTestId("acc-username").fill(creatorName); await page.getByTestId("acc-display").fill("E2E App Creator"); await page.getByTestId("acc-email").fill(`${creatorName}@example.test`); await page.getByTestId("acc-type").selectOption("USER");
  if (canPick) { await page.getByTestId("acc-workspace").selectOption(wsId); await page.getByTestId("acc-role").selectOption("EDITOR"); }
  check.ok("[observed] is the workspace the tenant admin just created offered to it for the next account? (H-C1-16: a tenant admin is not a member of it; /auth/me lists member workspaces only)", true, `offered=${offered.join(",")} canPick=${canPick}`);
  const cResp = page.waitForResponse((r) => r.request().method() === "POST" && /\/admin\/tenants\/[^/]+\/users$/.test(new URL(r.url()).pathname), { timeout: 20_000 });
  await page.getByTestId("acc-submit").click(); const cr = await cResp; const cb = JSON.parse(cr.request().postData());
  check.ok("the app creator is created (201): MEMBER, email as given, workspace + role together or none", cr.status() === 201 && cb.tenantRole === "MEMBER" && cb.email === `${creatorName}@example.test` && (canPick ? cb.workspaceId === wsId && cb.workspaceRole === "EDITOR" : !("workspaceId" in cb) && !("workspaceRole" in cb)), `status=${cr.status()} ${JSON.stringify(Object.keys(cb))}`, "http");
  const linkC = await page.locator('input[aria-label="Liên kết"]').inputValue(); await page.getByRole("button", { name: "Xong" }).click(); await page.getByTestId("account-created").waitFor();
  const pwC = randomSecret();
  check.ok("the app creator activates through the REAL activation page", await activateByLink(browser, linkC.replace(/^https?:\/\/[^/]+/, cfg.adminUrl), pwC));
  const creatorId = ((await sys.get(`/admin/tenants/${tenant.id}/members`)).body ?? []).find((m) => m.username === creatorName)?.userId; if (creatorId) fx.created.users.push(creatorId);
  let assigned = canPick;
  if (!canPick) {
    // an EXISTING tenant person into a workspace is a WORKSPACE ADMIN action by contract (§6.1): the workspace admin adds the tenant's own member
    const add = await wsaSession.post(`/workspaces/${wsId}/members`, { username: creatorName, role: "EDITOR" });
    check.ok("workspace assignment of an EXISTING tenant member is the WORKSPACE ADMIN's action: POST /workspaces/{w}/members → 201", add.status === 201, `status=${add.status} ${add.body?.code}`, "http");
    assigned = add.status === 201;
  }
  const creator = new Session(cfg.studio, "a01cr"); await creator.login(creatorName, pwC);
  const cMe = (await creator.get("/auth/me")).body; const cWs = (cMe?.workspaces ?? []).find((w) => w.id === wsId);
  check.ok("[api] the app creator signs in with the chosen password; /auth/me lists the workspace and no MEMBER_MANAGE / TENANT_*", assigned && !!cWs && !(cWs.permissions ?? []).includes("MEMBER_MANAGE") && !(cMe.permissions ?? []).some((p) => /TENANT_/.test(p)), `workspace=${!!cWs} perms=${JSON.stringify(cWs?.permissions)} tenantPerms=${JSON.stringify(cMe?.permissions)}`, "http");
  fx.users.a01 = { tenantId: tenant.id, workspaceId: wsId, wsa: { username: wsaName, password: pwA, session: wsaSession }, creator: { id: creatorId, username: creatorName, password: pwC, session: creator } };
  // the creator has no admin capability: the Admin portal must not open for it
  const cp = await newPage(browser); const cwhere = await loginPortal(cp, cfg, "admin", creatorName, pwC);
  check.ok("the app creator is NOT admitted to the Admin portal (no admin capability from the server)", /no-access|login/.test(cwhere) || !(await navLabels(cp)).includes("Người dùng"), cwhere);
  await cp.context().close();
  check.ok("no unexpected 4xx/5xx from the portal's calls", bad.filter((x) => !/auth\/me → 401/.test(x)).length === 0, bad.join(" | "));
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
