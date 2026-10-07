// @class: real-backend — USER01: the app creator made by the tenant admin (ADMIN01's chain: invitation → activation) signs in to the STUDIO: the project is visible, edit → save → reload, and the permission is exactly the role's
// (no publish, no member management). H-C1-04 is retested here: the Studio gate must admit a person by the server's permission, whatever route gave it. If it still refuses, the flow ends BLOCKED(C1) with the exact evidence — no workaround.
// If ADMIN01 did not run, the same chain is rebuilt here through the product's tenant routes (API) so this flow stands alone.
import { Blocked } from "../lib/report.mjs";
import { loginUi, newPage, openBuilder, pageProblems } from "../lib/ui.mjs";
import { makeTenant, makeTenantUser } from "../lib/portals.mjs";
export const id = "E2E-USER01", title = "App creator (EDITOR, made by the tenant admin): Studio login → project visible → edit / save / reload → permissions are the role's";
export async function run({ cfg, fx, browser, check }) {
  let chain = fx.users.a01;
  if (!chain) {
    const t = await makeTenant(fx, "u01", { workspace: false }); const ta = await makeTenantUser(fx, cfg, t.id, "u01ta", { tenantRole: "TENANT_ADMIN" });
    const w = await ta.session.post(`/admin/tenants/${t.id}/workspaces`, { name: `e2e-${fx.runId}-u01-ws` }); fx.created.workspaces.push(w.body.id);
    const wsa = await makeTenantUser(fx, cfg, t.id, "u01wsa", { as: ta.session, workspaceId: w.body.id, workspaceRole: "WORKSPACE_ADMIN" });
    const cr = await makeTenantUser(fx, cfg, t.id, "u01cr", { as: ta.session, workspaceId: w.body.id, workspaceRole: "EDITOR" });
    chain = { tenantId: t.id, workspaceId: w.body.id, wsa, creator: cr };
  }
  const { workspaceId: ws, wsa, creator: u } = chain;
  const p = await wsa.session.post(`/workspaces/${ws}/projects`, { name: `e2e-${fx.runId}-u01-proj`, appType: "PAGE_SCHEMA" });
  check.ok("[api] the workspace admin creates a project in the new workspace (201)", p.status === 201, `status=${p.status} ${p.body?.code}`, "http");
  const pid = p.body.id; fx.created.projects.push({ workspaceId: ws, projectId: pid, owner: "u01wsa" }); fx.sessions.u01wsa = wsa.session;
  const add = await wsa.session.post(`/workspaces/${ws}/projects/${pid}/members`, { username: u.username, role: "EDITOR" });
  check.ok("[api] …and adds the creator to the project as EDITOR (member route)", [200, 201].includes(add.status), `status=${add.status} ${add.body?.code}`, "http");
  const me = (await u.session.get("/auth/me")).body; const wsRow = (me?.workspaces ?? []).find((x) => x.id === ws);
  const page = await newPage(browser);
  await loginUi(page, cfg, u.username, u.password);
  await page.waitForLoadState("networkidle").catch(() => undefined); await page.waitForTimeout(500);
  const where = new URL(page.url()).pathname; const refused = /no-access|no-workspace/.test(where);
  if (!refused) {
    await page.goto(`${cfg.studio}${cfg.studioPrefix}/projects`, { waitUntil: "domcontentloaded" }); await page.waitForLoadState("networkidle").catch(() => undefined);
    check.ok("the project is visible in the creator's Ứng dụng list", (await page.locator("body").innerText()).includes(`e2e-${fx.runId}-u01-proj`));
    const opened = await openBuilder(page, cfg, pid); check.ok("the project opens in the Builder (APP_VIEW + APP_EDIT)", opened.canvas);
  }
  const proj = (await u.session.get(`/workspaces/${ws}/projects/${pid}`)).body; const perms = proj?.permissions ?? [];
  check.ok("[api] the creator's resolved project permissions include APP_EDIT and NOT APP_PUBLISH", perms.some((x) => /EDIT/.test(x)) && !perms.some((x) => /PUBLISH/.test(x)), perms.join(","), "http");
  check.ok("[api] …and no MEMBER_MANAGE: /auth/me lists none in the workspace", !(wsRow?.permissions ?? []).includes("MEMBER_MANAGE"), (wsRow?.permissions ?? []).join(","), "http");
  const edit = `user01-${fx.runId}`; const sc = (await u.session.get(`/workspaces/${ws}/projects/${pid}/schema`)).body;
  const patch = await u.session.patch(`/workspaces/${ws}/projects/${pid}/schema`, { expectedRevision: sc.revision, summary: edit, operations: [{ type: "UPDATE_PROP", sectionId: sc.schema.sections[0].id, path: "brand", value: edit }] });
  check.ok("[api] an edit by the creator is accepted (200)", patch.status === 200, `status=${patch.status} ${patch.body?.code}`, "http");
  if (!refused) { await page.reload({ waitUntil: "domcontentloaded" }); await page.waitForSelector("iframe", { timeout: 15_000 }).catch(() => undefined); }
  const after = (await u.session.get(`/workspaces/${ws}/projects/${pid}/schema`)).body;
  check.ok("after a reload the edit is still there (persisted)", JSON.stringify(after.schema).includes(edit), "", "persistence");
  const pub = await u.session.post(`/workspaces/${ws}/projects/${pid}/publish`, { visibility: "PRIVATE", expectedRevision: after.revision }, { headers: { "Idempotency-Key": `u01-${fx.runId}-pub-x` } });
  check.ok("[api] publishing is refused for the creator (403): the role is the role", pub.status === 403, `status=${pub.status} ${pub.body?.code}`, "http");
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | ")); await page.context().close();
  if (refused) throw new Blocked("C1", `the Studio portal refuses this creator at login (${where}) although /auth/me lists the workspace with ${JSON.stringify(wsRow?.permissions)}. The API side of the same account works (project APIs, edit, persist, no publish). H-C1-04 (still open).`, "H-C1-04");
}
