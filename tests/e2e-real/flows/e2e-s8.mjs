// @class: real-backend — supplementary: a workflow run that really FAILS on the server (BRANCH with no match and no default → INVALID_DEFINITION) is shown as failed, never as success, and polling stops.
import { openTestPanel, settledOutcome } from "../lib/testpanel.mjs";
import { newPage, pageProblems, countRequests } from "../lib/ui.mjs";
import { Blocked } from "../lib/report.mjs";
export const id = "E2E-S8", title = "(supplementary) Failed workflow run: shown as failed, no fake success, polling stops";
export async function run({ cfg, fx, browser, check }) {
  const w = fx.workspaces.A, p = fx.projects.A.id, A = fx.sessions.adminA, base = `/workspaces/${w}/projects/${p}`, wf = "e2e-wf-fail";
  const cur = (await A.get(`${base}/schema`)).body;
  const add = await A.patch(`${base}/schema`, { expectedRevision: cur.revision, summary: "e2e: failing workflow", operations: [{ type: "ADD_WORKFLOW_REF", definition: { id: wf, name: "E2E lỗi", trigger: "MANUAL", steps: [
    { id: "b", kind: "BRANCH", branches: [{ condition: { op: "EQ", left: { from: "INPUT", path: "x" }, right: { from: "LITERAL", value: "never" } }, next: "end" }] }, { id: "end", kind: "END" }] } }] });
  if (add.status !== 200) throw new Blocked("C2", `the failing-workflow definition was refused: ${add.status} ${add.body?.code}`, "ADD_WORKFLOW_REF");
  check.ok("(setup) the failing workflow was added (200)", true);
  const page = await newPage(browser);
  await openTestPanel(page, cfg, fx.users.adminA, p);
  const polls = countRequests(page, /\/app-runtime\/workflow-runs\/[^/]+$/, "GET");
  await page.getByTestId(`run-workflow:${wf}`).click();
  await page.waitForFunction((id) => /FAILED/.test(document.querySelector(`[data-testid="run-status:${id}"]`)?.textContent ?? ""), wf, { timeout: 30_000 }).catch(() => undefined);
  const status = await page.getByTestId(`run-status:${wf}`).innerText().catch(() => "");
  const runId = (status.match(/Lượt chạy (\S+)/) ?? [])[1];
  check.ok("the UI shows the run as FAILED with its run id and the failing step", /FAILED/.test(status) && !!runId && /b:FAILED/.test(status), status);
  const row = page.getByTestId(`workflow-row:${wf}`);
  const text = (await row.innerText()).replace(/\s+/g, " ");
  check.ok("no success is displayed anywhere in the row", (await row.locator('[data-outcome="SUCCESS"]').count()) === 0 && !/Thành công|SUCCEEDED/.test(text), text.slice(0, 200));
  const view = runId ? await A.get(`${base}/app-runtime/workflow-runs/${runId}`) : null;
  check.ok("the server agrees: status FAILED with errorCode INVALID_DEFINITION", view?.body?.status === "FAILED" && view?.body?.errorCode === "INVALID_DEFINITION", `${view?.status} ${view?.body?.status} ${view?.body?.errorCode}`, "persistence");
  const quiet = Date.now(); await page.waitForTimeout(4000);
  check.ok("no polling after the run failed (0 status requests in 4 s)", polls.since(quiet) === 0, `polls=${polls.since(quiet)}`);
  check.ok("the run button is usable again", !(await page.getByTestId(`run-workflow:${wf}`).isDisabled()));
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
