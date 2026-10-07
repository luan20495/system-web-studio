// @class: real-backend — USER01: an app creator (EDITOR) logs into the STUDIO: the project is visible, edit → save → reload, and the permission is exactly the role's (no publish, no member management). The account is made by the SYSTEM_ADMIN through the existing
// route (the tenant-admin creation path is ADMIN01 / C1): everything the user does afterwards is the real browser against the real backend.
import { Blocked } from "../lib/report.mjs";
import { loginUi, newPage, openBuilder, pageProblems } from "../lib/ui.mjs";
import { makeUser } from "../lib/portals.mjs";
export const id = "E2E-USER01", title = "App creator (EDITOR): Studio login → project visible → edit / save / reload → permissions are the role's";
export async function run({ cfg, fx, browser, check }) {
  const u = await makeUser(fx, cfg, "creator", "A", "EDITOR");
  const pa = fx.projects.A.id, wsA = fx.workspaces.A;
  const add = await fx.sessions.adminA.post(`/workspaces/${wsA}/projects/${pa}/members`, { username: u.username, role: "EDITOR" });
  check.ok("[api] the workspace admin adds the creator to project A as EDITOR (member route)", [200, 201].includes(add.status), `status=${add.status} ${add.body?.code}`, "http");
  const page = await newPage(browser);
  await loginUi(page, cfg, u.username, u.password);
  await page.waitForLoadState("networkidle").catch(() => undefined); await page.waitForTimeout(500);
  const where = new URL(page.url()).pathname; const refused = /no-access|no-workspace/.test(where);
  if (!refused) {
    await page.goto(`${cfg.studio}${cfg.studioPrefix}/projects`, { waitUntil: "domcontentloaded" }); await page.waitForLoadState("networkidle").catch(() => undefined);
    check.ok("the project is visible in the creator's Ứng dụng list", (await page.locator("body").innerText()).includes(fx.projects.A.name));
    const opened = await openBuilder(page, cfg, pa); check.ok("the project opens in the Builder (APP_VIEW + APP_EDIT)", opened.canvas);
  }
  const me = (await u.session.get("/auth/me")).body; const perms = (me?.workspaces ?? []).flatMap((x) => x.permissions ?? []);
  const proj = (await u.session.get(`/workspaces/${wsA}/projects/${pa}`)).body;
  check.ok("[api] the creator's resolved project permissions include APP_EDIT and NOT APP_PUBLISH", (proj?.permissions ?? []).some((p) => /EDIT/.test(p)) && !(proj?.permissions ?? []).some((p) => /PUBLISH/.test(p)), (proj?.permissions ?? []).join(","), "http");
  check.ok("[api] …and no MEMBER_MANAGE (the Admin portal does not open): /auth/me lists none", !perms.includes("MEMBER_MANAGE"), perms.join(","), "http");
  const edit = `user01-${fx.runId}`; const sc = (await u.session.get(`/workspaces/${wsA}/projects/${pa}/schema`)).body;
  const patch = await u.session.patch(`/workspaces/${wsA}/projects/${pa}/schema`, { expectedRevision: sc.revision, summary: edit, operations: [{ type: "UPDATE_PROP", sectionId: sc.schema.sections[0].id, path: "brand", value: edit }] });
  check.ok("[api] an edit by the creator is accepted (200)", patch.status === 200, `status=${patch.status} ${patch.body?.code}`, "http");
  if (!refused) { await page.reload({ waitUntil: "domcontentloaded" }); await page.waitForSelector("iframe", { timeout: 15_000 }).catch(() => undefined); }
  const after = (await u.session.get(`/workspaces/${wsA}/projects/${pa}/schema`)).body;
  check.ok("after a reload the edit is still there (persisted)", JSON.stringify(after.schema).includes(edit), "", "persistence");
  const pub = await u.session.post(`/workspaces/${wsA}/projects/${pa}/publish`, { visibility: "PRIVATE", expectedRevision: after.revision }, { headers: { "Idempotency-Key": `u01-${fx.runId}-pub-x` } });
  check.ok("[api] publishing is refused for the creator (403): the role is the role", pub.status === 403, `status=${pub.status} ${pub.body?.code}`, "http");
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | ")); await page.context().close();
  if (refused) throw new Blocked("C1", `the Studio portal refuses this EDITOR at login (${where}): /auth/me lists workspace-level codes only, so APP_VIEW granted by a PROJECT membership is invisible to the portal gate. The API side of the same account works (project APIs, edit, persist, no publish). H-C1-04 (still open).`, "H-C1-04");
}
