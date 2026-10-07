// @class: real-backend
import { loginUi, newPage, bodyText, pageProblems } from "../lib/ui.mjs";
import { deniedWriteProbe } from "../lib/fixtures.mjs";
export const id = "E2E-03", title = "Workspace isolation A/B";
export async function run({ cfg, fx, browser, check }) {
  const nameA = fx.projects.A.name, nameB = fx.projects.B.name;
  for (const [who, mine, theirs] of [["adminA", nameA, nameB], ["adminB", nameB, nameA]]) {
    const page = await newPage(browser);
    await loginUi(page, cfg, fx.users[who].username, fx.users[who].password);
    await page.goto(`${cfg.studio}${cfg.studioPrefix}/projects`, { waitUntil: "networkidle" });
    await page.waitForTimeout(500);
    const t = await bodyText(page);
    check.ok(`${who}: the project list shows their own project`, t.includes(mine));
    check.ok(`${who}: the project list does NOT show the other workspace's project`, !t.includes(theirs));
    check.ok(`${who}: no unhandled page errors`, pageProblems(page).length === 0, pageProblems(page).join(" | "));
    await page.context().close();
  }
  const B = fx.sessions.adminB, wa = fx.workspaces.A, pa = fx.projects.A.id;
  const rev = (await fx.sessions.adminA.get(`/workspaces/${wa}/projects/${pa}`)).body.revision;
  const direct = await B.get(`/workspaces/${wa}/projects/${pa}`);
  check.ok("B reads A's project by id → 404 (existence not disclosed)", direct.status === 404, `status=${direct.status} code=${direct.body?.code}`);
  check.ok("the 404 body does not carry A's data", !JSON.stringify(direct.body ?? {}).includes(fx.projects.A.name) && !JSON.stringify(direct.body ?? {}).includes(`secret-description-${fx.runId}`));
  const list = await B.get(`/workspaces/${wa}/projects`);
  check.ok("B lists A's workspace → refused (403/404), no rows", [403, 404].includes(list.status), `status=${list.status}`);
  const write = await B.patch(`/workspaces/${wa}/projects/${pa}/schema`, { expectedRevision: rev, ...deniedWriteProbe("isolation probe") });
  check.ok("B cannot write into A's project (403/404)", [403, 404].includes(write.status), `status=${write.status}`);
  check.ok("A's project revision is unchanged by the attempts", (await fx.sessions.adminA.get(`/workspaces/${wa}/projects/${pa}`)).body.revision === rev);
  const wrongWs = await fx.sessions.adminA.get(`/workspaces/${fx.workspaces.B}/projects/${fx.projects.B.id}`);
  check.ok("A reads B's project through B's workspace → 404", wrongWs.status === 404, `status=${wrongWs.status}`);
  const crossed = await fx.sessions.adminA.get(`/workspaces/${wa}/projects/${fx.projects.B.id}`);
  check.ok("a project id of B under A's workspace path → 404 (no cross-workspace lookup)", crossed.status === 404, `status=${crossed.status}`);
}
