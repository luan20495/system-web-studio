// @class: real-backend
import { Blocked } from "../lib/report.mjs";
import { viewerGate, VIEWER_BLOCKER } from "../lib/viewer.mjs";
import { loginUi, newPage, openBuilder, bodyText, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-04", title = "Authorized user reaches a valid Studio resource";
export async function run({ cfg, fx, browser, check }) {
  if (fx.notes.viewerOnProject !== "member") throw new Blocked("C1", `fixture: the VIEWER could not be added to the project (${fx.notes.viewerOnProject}); membership routes are C1's`, "member routes");
  for (const [who, expectEdit] of [["adminA", true], ["viewerA", false]]) {
    const page = await newPage(browser);
    await loginUi(page, cfg, fx.users[who].username, fx.users[who].password);
    if (!expectEdit) {
      const gated = /no-access/.test(page.url());
      const next = viewerGate(cfg, gated, check, page.url());
      if (gated) {
        // observed: GET /auth/me gives a workspace VIEWER permissions [] → the portal gate refuses Studio
        check.ok("viewerA: the refusal page does not show project data", !(await bodyText(page)).includes(fx.projects.A.name));
        const api = await fx.sessions.viewerA.get(`/workspaces/${fx.workspaces.A}/projects/${fx.projects.A.id}/schema`);
        fx.notes.viewerSchemaViaApi = api.status;
        check.ok("viewerA: the API itself still lets a project member READ the schema (200)", api.status === 200, `status=${api.status}`);
        await page.context().close();
        if (next === "blocked") throw new Blocked("C1", VIEWER_BLOCKER, "viewer portal access");
        continue;
      }
    }
    check.ok(`${who}: after login the Studio shell is reachable (not sent to /auth/no-access)`, !/no-access/.test(page.url()), page.url());
    const { schemaResponse, canvas } = await openBuilder(page, cfg, fx.projects.A.id);   // direct URL, no click-through
    check.ok(`${who}: direct URL → schema 200`, schemaResponse?.status() === 200, `status=${schemaResponse?.status()}`);
    check.ok(`${who}: direct URL → Builder canvas`, canvas);
    const t = await bodyText(page);
    check.ok(`${who}: ${expectEdit ? "editor is not read-only" : "viewer sees the read-only notice"}`, expectEdit ? !t.includes("Bạn chỉ có quyền xem") : t.includes("Bạn chỉ có quyền xem"), t.slice(0, 100));
    await page.reload({ waitUntil: "domcontentloaded" }); await page.waitForSelector("iframe", { timeout: 15_000 }).catch(() => undefined);
    check.ok(`${who}: refresh keeps the project open`, page.url().includes(fx.projects.A.id));
    check.ok(`${who}: no unhandled page errors`, pageProblems(page).length === 0, pageProblems(page).join(" | "));
    await page.context().close();
  }
}
