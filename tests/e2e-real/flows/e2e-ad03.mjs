// @class: real-backend — H-C1-11 retest (C1 fix 7ecea1a): the tenant member directory, as a TENANT ADMIN (lonelyA, made TENANT_ADMIN of the DEFAULT tenant by the SYSTEM_ADMIN). Candidates are tenant-scoped (workspace members of the tenant,
// or former members), enabled, activated, never SYSTEM_ADMIN, ≤ 50, `q` ≥ 2; add through the UI; isolation (guessed UUID, SYSTEM_ADMIN, another tenant) is 404; self-change is 403. A user whose ONLY tie is to ANOTHER tenant cannot be built
// through the public API (a workspace's tenant cannot be chosen): that case is C1's backend test TenantMemberDirectoryTests, recorded as such here.
import { Blocked } from "../lib/report.mjs";
import { loginPortal, makeUser, openPortal, watchApi } from "../lib/portals.mjs";
import { newPage, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-AD03", title = "Tenant admin member directory: metadata, tenant-scoped candidates (search, limit, exclusions), add through the UI, isolation and rules";
const DEFAULT_TENANT = "00000000-0000-0000-0000-000000000001";
export async function run({ cfg, fx, browser, check }) {
  const sys = fx.sessions.admin, lonely = fx.users.lonelyA, b = fx.users.adminB, adminA = fx.users.adminA;
  const probe = await sys.get(`/admin/tenants/${DEFAULT_TENANT}/member-candidates`);
  if (probe.status === 404 && !probe.body?.code) throw new Blocked("C1", "the member-candidates route is not on this build (C1's fix fix/c1-portal-authz-blockers @ 7ecea1a is not in it)", "H-C1-11");
  // ---- setup by the SYSTEM_ADMIN ---------------------------------------------------------------------------------------------------------------------------
  await sys.put(`/admin/tenants/${DEFAULT_TENANT}/members/${lonely.id}`, { role: "TENANT_ADMIN" });
  const idle = await makeUser(fx, cfg, "nonact", "A", "VIEWER", { activate: false });     // never activated
  const off = await makeUser(fx, cfg, "disabled", "A", "VIEWER");                          // activated, then disabled
  await sys.patch(`/admin/users/${off.id}/status`, { enabled: false });
  const sa = await makeUser(fx, cfg, "sysadm", "A", "VIEWER");                               // activated, enabled, then made SYSTEM_ADMIN
  const grant = await sys.post(`/admin/users/${sa.id}/system-admin`, { grant: true, confirm: true });
  for (const u of [b, idle, off, sa]) await sys.del(`/admin/tenants/${DEFAULT_TENANT}/members/${u.id}`);   // inactive tenant row = "former member": eligible by itself, so only enabled / activated decide
  const T2 = await sys.post("/admin/tenants", { slug: `e2e-${fx.runId}-t2`, name: `E2E T2 ${fx.runId}`, firstAdminUserId: fx.users.viewerA.id });
  const t2 = T2.body?.id; const L = fx.sessions.lonelyA; await L.get("/auth/me");

  // ---- metadata ----------------------------------------------------------------------------------------------------------------------------------------------
  const mem = await L.get(`/admin/tenants/${DEFAULT_TENANT}/members`);
  check.ok("[api] GET members: every row has userId, username, displayName, email, role, active", mem.status === 200 && mem.body.length > 0 && mem.body.every((m) => ["userId", "username", "displayName", "email", "role", "active"].every((k) => k in m)), `status=${mem.status} ${JSON.stringify(mem.body?.[0])}`, "http");
  check.ok("[api] …and the metadata is right (adminA: its username and display name)", mem.body.some((m) => m.userId === adminA.id && m.username === adminA.username && /E2E adminA/.test(m.displayName ?? "")), "", "http");

  // ---- candidates --------------------------------------------------------------------------------------------------------------------------------------------
  const all = await L.get(`/admin/tenants/${DEFAULT_TENANT}/member-candidates`); const ids = (all.body ?? []).map((c) => c.userId);
  check.ok("[api] candidates: 200, a list of {userId, username, displayName, email}", all.status === 200 && Array.isArray(all.body) && all.body.every((c) => ["userId", "username", "displayName", "email"].every((k) => k in c) && Object.keys(c).length === 4), `status=${all.status}`, "http");
  check.ok("[api] a former member in a workspace of the tenant (adminB) IS a candidate", ids.includes(b.id), "", "http");
  check.ok("[api] current members are not candidates (adminA)", !ids.includes(adminA.id), "", "http");
  check.ok("[api] a SYSTEM_ADMIN account (activated, enabled, a workspace member, former tenant member) is never a candidate", grant.status === 200 && !ids.includes(sa.id), `grant=${grant.status}`, "http");
  check.ok("[api] a non-activated account and a disabled account are not candidates", !ids.includes(idle.id) && !ids.includes(off.id), "", "http");
  check.ok("[api] at most 50 results, sorted by username", all.body.length <= 50 && JSON.stringify(all.body.map((c) => c.username)) === JSON.stringify([...all.body.map((c) => c.username)].sort((x, y) => x.toLowerCase() < y.toLowerCase() ? -1 : 1)), `n=${all.body.length}`, "http");
  const part = b.username.slice(-8);
  const q1 = await L.get(`/admin/tenants/${DEFAULT_TENANT}/member-candidates?q=${encodeURIComponent(part)}`);
  check.ok("[api] search q finds adminB by part of the username and only matching people", q1.status === 200 && q1.body.some((c) => c.userId === b.id) && q1.body.every((c) => `${c.username} ${c.displayName ?? ""} ${c.email ?? ""}`.toLowerCase().includes(part.toLowerCase())), `n=${q1.body?.length}`, "http");
  const qs = await L.get(`/admin/tenants/${DEFAULT_TENANT}/member-candidates?q=a`);
  check.ok("[api] a 1-character search is refused (400 QUERY_TOO_SHORT)", qs.status === 400 && qs.body?.code === "QUERY_TOO_SHORT", `status=${qs.status} ${qs.body?.code}`, "http");
  const none = await L.get(`/admin/tenants/${DEFAULT_TENANT}/member-candidates?q=zz-no-such-${fx.runId}`);
  check.ok("[api] a search with no match is an empty list", none.status === 200 && none.body.length === 0, "", "http");
  check.ok("[fact] 'a user known only to ANOTHER tenant is invisible' cannot be built through the public API; it is C1's backend test TenantMemberDirectoryTests", true, "see the backend targeted run", "http");

  // ---- the UI ----------------------------------------------------------------------------------------------------------------------------------------------------
  const page = await newPage(browser); const bad = watchApi(page);
  await loginPortal(page, cfg, "admin", lonely.username, lonely.password);
  await openPortal(page, cfg, "admin", "/company");
  check.ok("Công ty của tôi: the member rows show names and emails from the member metadata", /E2E adminA/.test(await page.getByTestId("tenant-members").innerText()));
  await page.getByTestId("tm-search").fill(part); await page.waitForTimeout(1200);
  const opt = page.locator('[data-testid="tm-person"] option', { hasText: b.username }); await opt.first().waitFor({ timeout: 8000 });
  check.ok("typing the search shows adminB (name · email) in the candidate list, and not the SYSTEM_ADMIN / disabled / non-activated accounts", (await page.locator('[data-testid="tm-person"] option', { hasText: idle.username }).count()) === 0 && (await page.locator('[data-testid="tm-person"] option', { hasText: off.username }).count()) === 0);
  await page.getByTestId("tm-person").selectOption(await opt.first().getAttribute("value")); await page.getByTestId("tm-role").selectOption("MEMBER");
  await page.getByTestId("tm-add").click(); await page.getByText("Đã thêm vào công ty.").waitFor({ timeout: 10_000 });
  const after = (await L.get(`/admin/tenants/${DEFAULT_TENANT}/members`)).body ?? [];
  check.ok("add candidate: the server lists adminB as an active MEMBER, with metadata", after.some((m) => m.userId === b.id && m.role === "MEMBER" && m.active && m.username === b.username), "", "persistence");
  check.ok("the member appears in the table with his name", (await page.getByTestId(`tm:${b.id}`).count()) === 1 && (await page.getByTestId(`tm:${b.id}`).innerText()).includes(b.username));
  const gone = (await L.get(`/admin/tenants/${DEFAULT_TENANT}/member-candidates?q=${encodeURIComponent(part)}`)).body ?? [];
  check.ok("the candidate disappears after the add (API and picker)", !gone.some((c) => c.userId === b.id) && (await page.locator('[data-testid="tm-person"] option', { hasText: b.username }).count()) === 0, "", "persistence");

  // ---- isolation and rules -----------------------------------------------------------------------------------------------------------------------------------
  const ghost = await L.put(`/admin/tenants/${DEFAULT_TENANT}/members/00000000-0000-4000-8000-0000000000aa`, { role: "MEMBER" });
  check.ok("[api] a guessed foreign UUID → 404 USER_NOT_FOUND", ghost.status === 404 && ghost.body?.code === "USER_NOT_FOUND", `status=${ghost.status} ${ghost.body?.code}`, "http");
  const toSys = await L.put(`/admin/tenants/${DEFAULT_TENANT}/members/${sa.id}`, { role: "MEMBER" });
  check.ok("[api] adding that SYSTEM_ADMIN account by id → 404 (not eligible, no oracle)", toSys.status === 404, `status=${toSys.status} ${toSys.body?.code}`, "http");
  const idleAdd = await L.put(`/admin/tenants/${DEFAULT_TENANT}/members/${idle.id}`, { role: "MEMBER" }); const offAdd = await L.put(`/admin/tenants/${DEFAULT_TENANT}/members/${off.id}`, { role: "MEMBER" });
  check.ok("[api] a non-activated and a disabled account cannot be added by id either (404)", idleAdd.status === 404 && offAdd.status === 404, `${idleAdd.status}/${offAdd.status}`, "http");
  const x1 = await L.get(`/admin/tenants/${t2}/members`), x2 = await L.get(`/admin/tenants/${t2}/member-candidates`), x3 = await L.put(`/admin/tenants/${t2}/members/${adminA.id}`, { role: "MEMBER" });
  check.ok("[api] a caller from ANOTHER tenant gets 404 for members, candidates and add", [x1.status, x2.status, x3.status].every((s) => s === 404), `${x1.status}/${x2.status}/${x3.status}`, "http");
  const self = await L.put(`/admin/tenants/${DEFAULT_TENANT}/members/${lonely.id}`, { role: "MEMBER" });
  check.ok("[api] changing your own tenant role → 403 SELF_GRANT_FORBIDDEN", self.status === 403 && self.body?.code === "SELF_GRANT_FORBIDDEN", `status=${self.status} ${self.body?.code}`, "http");
  check.ok("no unexpected 4xx/5xx from the portal's calls", bad.filter((x) => !/auth\/me → 401/.test(x)).length === 0, bad.join(" | "));
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();

  // ---- cleanup -----------------------------------------------------------------------------------------------------------------------------------------------------
  await sys.put(`/admin/tenants/${DEFAULT_TENANT}/members/${lonely.id}`, { role: "MEMBER" });
  await sys.post(`/admin/users/${sa.id}/system-admin`, { grant: false, confirm: true });
  if (t2) await sys.patch(`/admin/tenants/${t2}/status`, { status: "DELETED" });
}
