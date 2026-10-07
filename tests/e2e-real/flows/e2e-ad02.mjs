// @class: real-backend — ADMIN portal (:3002) for a WORKSPACE ADMIN (adminA), retest of H-C1-05 on C1's fix (fix/c1-portal-authz-blockers @ 7ecea1a). `/auth/me` now lists MEMBER_MANAGE in workspaces[].permissions; the portal opens "Workspace của tôi"
// from THAT code only (never from a role name). Negative: workspace viewer / editor / publisher are not offered it and are refused at the door. Member management: add by username, re-role, remove, rules (self-change, last admin), isolation.
import { Blocked } from "../lib/report.mjs";
import { loginPortal, makeUser, navLabels, openPortal, watchApi } from "../lib/portals.mjs";
import { newPage, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-AD02", title = "Admin portal, workspace admin: 'Workspace của tôi' opens on MEMBER_MANAGE (not on a role name), members add / re-role / remove, rules, isolation, negative roles";
export async function run({ cfg, fx, browser, check }) {
  const a = fx.users.adminA, b = fx.users.adminB, wsA = fx.workspaces.A;
  const me = (await fx.sessions.adminA.get("/auth/me")).body;
  const w = (me?.workspaces ?? []).find((x) => x.id === wsA);
  check.ok("[api] /auth/me: the workspace admin's workspace lists MEMBER_MANAGE (and DATA_SOURCE_MANAGE)", !!w?.permissions?.includes("MEMBER_MANAGE") && w.permissions.includes("DATA_SOURCE_MANAGE"), JSON.stringify(w?.permissions), "http");
  if (!w?.permissions?.includes("MEMBER_MANAGE")) throw new Blocked("C1", "/auth/me still lists no MEMBER_MANAGE for a workspace admin: the build is not C1's fix (fix/c1-portal-authz-blockers)", "H-C1-05");

  // ---- negative roles: no MEMBER_MANAGE in /auth/me, no Workspace của tôi, refused at the door ---------------------------------------------------------------
  const editor = await makeUser(fx, cfg, "editorx", "A", "EDITOR"), publisher = await makeUser(fx, cfg, "pubx", "A", "PUBLISHER");
  for (const [label, u] of [["viewer", fx.users.viewerA], ["editor", editor], ["publisher", publisher]]) {
    const sess = u.session ?? fx.sessions.viewerA; const m = (await sess.get("/auth/me")).body;
    const perms = (m?.workspaces ?? []).flatMap((x) => x.permissions ?? []);
    check.ok(`[api] a workspace ${label}: /auth/me does NOT list MEMBER_MANAGE`, !perms.includes("MEMBER_MANAGE"), perms.join(","), "http");
    const direct = await sess.get(`/workspaces/${wsA}/members`);
    check.ok(`[api] …and the server refuses their member list (403/404) — the backend re-checks`, [403, 404].includes(direct.status), `status=${direct.status}`, "http");
    const pg = await newPage(browser); const where = await loginPortal(pg, cfg, "admin", u.username, u.password);
    check.ok(`a workspace ${label} is refused by the Admin portal (/auth/no-access) — not offered 'Workspace của tôi'`, /no-access/.test(where), where); await pg.context().close();
  }

  // ---- the workspace admin ---------------------------------------------------------------------------------------------------------------------------------
  const page = await newPage(browser); const bad = watchApi(page);
  const landed = await loginPortal(page, cfg, "admin", a.username, a.password);
  check.ok("a WORKSPACE_ADMIN logs in through the Admin portal and is not refused", landed.startsWith("/admin") && !/login|no-access/.test(landed), landed);
  const nav = await navLabels(page);
  check.ok("navigation = Tổng quan + Workspace của tôi + Nguồn dữ liệu; no Người dùng / Nhật ký kiểm toán / Công ty của tôi", nav.includes("Workspace của tôi") && nav.includes("Nguồn dữ liệu") && !nav.includes("Người dùng & Workspace") && !nav.includes("Nhật ký kiểm toán") && !nav.includes("Công ty của tôi"), nav.join(" | "));
  await openPortal(page, cfg, "admin", "/my-workspaces");
  const table = await page.getByTestId("ws-members").innerText();
  check.ok("the member table is the REAL list of workspace A (adminA, viewerA, lonelyA)", [a, fx.users.viewerA, fx.users.lonelyA].every((u) => table.includes(u.username)));
  const own = page.getByTestId(`wm:${a.username}`);
  check.ok("own row: role and remove are disabled (no self-change)", (await own.locator("select").isDisabled()) && (await own.getByRole("button", { name: "Gỡ" }).isDisabled()));
  await page.getByTestId("ws-add-who").fill("no-such-user-" + fx.runId); await page.getByRole("button", { name: "Thêm vào workspace" }).click(); await page.waitForTimeout(800);
  check.ok("an unknown username is refused by the server and said in words (nothing added)", /Không tìm thấy|không tìm thấy/.test(await page.getByTestId("ws-msg").innerText().catch(() => "")));
  await page.getByTestId("ws-add-who").fill(b.username); await page.getByTestId("ws-add-role").selectOption("VIEWER");
  await page.getByRole("button", { name: "Thêm vào workspace" }).click(); await page.getByText("Đã thêm vào workspace.").waitFor({ timeout: 10_000 });
  let list = (await fx.sessions.adminA.get(`/workspaces/${wsA}/members`)).body ?? [];
  check.ok("add by username as Người xem: the server lists adminB as VIEWER of workspace A", list.some((m) => m.userId === b.id && m.role === "VIEWER"), JSON.stringify(list.map((m) => [m.username, m.role])), "persistence");
  await page.getByTestId(`wm:${b.username}`).locator("select").selectOption("EDITOR"); await page.getByText("Đã đổi vai trò.").waitFor({ timeout: 10_000 });
  list = (await fx.sessions.adminA.get(`/workspaces/${wsA}/members`)).body ?? [];
  check.ok("change the role to Biên tập viên: persisted", list.find((m) => m.userId === b.id)?.role === "EDITOR", "", "persistence");
  const again = await fx.sessions.adminA.post(`/workspaces/${wsA}/members`, { username: b.username, role: "VIEWER" });
  check.ok("[api] adding the same person twice is refused (409 ALREADY_MEMBER)", again.status === 409 && again.body?.code === "ALREADY_MEMBER", `status=${again.status} ${again.body?.code}`, "http");
  const demote = await fx.sessions.adminA.patch(`/workspaces/${wsA}/members/${a.id}`, { role: "EDITOR" });
  check.ok("[api] the server refuses self-change (403 SELF_GRANT_FORBIDDEN), as the UI did before the click", demote.status === 403 && demote.body?.code === "SELF_GRANT_FORBIDDEN", `status=${demote.status} ${demote.body?.code}`, "http");
  page.once("dialog", (d) => void d.accept());
  await page.getByTestId(`wm:${b.username}`).getByRole("button", { name: "Gỡ" }).click(); await page.getByText("Đã gỡ khỏi workspace.").waitFor({ timeout: 10_000 });
  list = (await fx.sessions.adminA.get(`/workspaces/${wsA}/members`)).body ?? [];
  check.ok("remove: gone from the workspace on the server", !list.some((m) => m.userId === b.id), "", "persistence");
  const cross = await fx.sessions.adminA.get(`/workspaces/${fx.workspaces.B}/members`);
  check.ok("[api] adminA cannot read workspace B's members (403/404)", [403, 404].includes(cross.status), `status=${cross.status}`, "http");
  check.ok("the page offers no workspace picker with B (one workspace administered)", (await page.getByTestId("ws-pick").count()) === 0);

  await openPortal(page, cfg, "admin", "/data-sources");
  check.ok("Nguồn dữ liệu: the REAL panel of the workspace (catalogue / add form), not 'Chưa sẵn sàng'", (await page.getByTestId("ds-panel").count()) === 1 && (await page.getByText("Chưa sẵn sàng").count()) === 0, (await page.locator("main").innerText()).slice(0, 120));
  await openPortal(page, cfg, "admin", "/users");
  check.ok("a system-only URL says 'chỉ dành cho quản trị hệ thống' and makes no call to /admin/users", /chỉ dành cho quản trị hệ thống/.test(await page.locator("main").innerText()) && !bad.some((x) => /\/admin\/users/.test(x)), bad.join(" | "));
  const sys = await fx.sessions.adminA.get("/admin/users");
  check.ok("[api] …and the API refuses it too (403 ADMIN_REQUIRED)", sys.status === 403 && sys.body?.code === "ADMIN_REQUIRED", `status=${sys.status} ${sys.body?.code}`, "http");
  check.ok("no unexpected 4xx/5xx from the portal's calls (deliberate refusals excepted)", bad.filter((x) => !/→ (409|403|404)|auth\/me → 401/.test(x)).length === 0, bad.join(" | "));
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
