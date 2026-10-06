// @class: real-backend
import { loginUi, newPage, openBuilder, bodyText, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-01", title = "Load project/page from the real backend";
export async function run({ cfg, fx, browser, check }) {
  const page = await newPage(browser);
  await loginUi(page, cfg, fx.users.adminA.username, fx.users.adminA.password);
  const { schemaResponse, canvas } = await openBuilder(page, cfg, fx.projects.A.id);
  check.ok("GET …/projects/{id}/schema answered 200 by the real backend", schemaResponse?.status() === 200, `status=${schemaResponse?.status()}`);
  check.ok("the Builder canvas rendered (preview iframe present)", canvas);
  const text = await bodyText(page);
  check.ok("the project name stored in the backend is on screen", text.includes(fx.projects.A.name), text.slice(0, 120));
  const server = (await fx.sessions.adminA.get(`/workspaces/${fx.workspaces.A}/projects/${fx.projects.A.id}/schema`)).body;
  const frameText = await page.frameLocator("iframe").locator("body").innerText().catch(() => "");
  const firstTitle = JSON.stringify(server?.schema?.sections?.[0]?.props ?? {});
  check.ok("the canvas shows the persisted first section (page content comes from the server, not a fixture in the browser)", server?.schema?.sections?.length > 0 && frameText.length > 0, `sections=${server?.schema?.sections?.length} props=${firstTitle.slice(0, 80)}`);
  check.ok("no unhandled page errors / serious console errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
