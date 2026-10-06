// @class: real-backend
import { Blocked } from "../lib/report.mjs";
import { loginUi, newPage, openBuilder, bodyText, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-04", title = "Authorized user reaches a valid Studio resource";
export async function run({ cfg, fx, browser, check }) {
  if (fx.notes.viewerOnProject !== "member") throw new Blocked("C1", `fixture: the VIEWER could not be added to the project (${fx.notes.viewerOnProject}); membership routes are C1's`, "member routes");
  for (const [who, expectEdit] of [["adminA", true], ["viewerA", false]]) {
    const page = await newPage(browser);
    await loginUi(page, cfg, fx.users[who].username, fx.users[who].password);
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
