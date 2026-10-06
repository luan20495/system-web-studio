// @class: real-backend — supplementary: a double click starts ONE workflow run (duplicate-submit lock), the UI polls only while the run is not finished, and nothing storms the API.
import { openTestPanel, settledOutcome } from "../lib/testpanel.mjs";
import { newPage, pageProblems, countRequests } from "../lib/ui.mjs";
import { Blocked } from "../lib/report.mjs";
export const id = "E2E-S4", title = "(supplementary) Workflow start: one run per double click, bounded polling, no request storm";
const TERMINAL = ["SUCCEEDED", "FAILED", "CANCELLED"];
export async function run({ cfg, fx, browser, check }) {
  if (!fx.notes.definitionOps?.ok) throw new Blocked("C2", `typed V2 definition operations were refused: ${JSON.stringify(fx.notes.definitionOps)}`, "ADD_WORKFLOW_REF");
  const page = await newPage(browser);
  await openTestPanel(page, cfg, fx.users.adminA, fx.projects.A.id);
  const starts = countRequests(page, /\/app-runtime\/workflows\/[^/]+\/runs$/, "POST");
  const polls = countRequests(page, /\/app-runtime\/workflow-runs\/[^/]+$/, "GET");
  const all = countRequests(page, /\/api\/v1\//);
  const t0 = Date.now();
  const btn = page.getByTestId(`run-workflow:${fx.ids.workflowId}`);
  await btn.dblclick();                                                     // two clicks in the same instant
  const out = await settledOutcome(page, `workflow-row:${fx.ids.workflowId}`, 30_000);
  await page.waitForTimeout(1500);
  check.ok("a double click sent exactly ONE start request (POST …/workflows/{id}/runs)", starts.count === 1, `starts=${starts.count}`);
  const status = await page.getByTestId(`run-status:${fx.ids.workflowId}`).innerText().catch(() => "");
  const runId = (status.match(/Lượt chạy (\S+)/) ?? [])[1];
  check.ok("the UI shows ONE run id and its status", !!runId, status);
  const view = runId ? (await fx.sessions.adminA.get(`/workspaces/${fx.workspaces.A}/projects/${fx.projects.A.id}/app-runtime/workflow-runs/${runId}`)) : null;
  check.ok("the server knows that run and it is terminal (TEST runs simulate every step)", view?.status === 200 && TERMINAL.includes(String(view.body?.status).toUpperCase()), `status=${view?.status} ${view?.body?.status}`);
  check.ok("the outcome is a simulated run, not a success", ["WOULD_RUN", "NOT_EXECUTED"].includes(out.state) || /sẽ chạy|mô phỏng|would/i.test(out.text), `${out.state}: ${out.text.slice(0, 120)}`);
  // polling must stop once the run is terminal
  const quietFrom = Date.now(); await page.waitForTimeout(4000);
  check.ok("no polling after the run is terminal (0 status requests in 4 s)", polls.since(quietFrom) === 0, `polls=${polls.since(quietFrom)}`);
  check.ok("bounded polling: at most 5 status requests for one finished run", polls.count <= 5, `polls=${polls.count}`);
  check.ok("no request storm: the whole panel interaction made fewer than 40 API calls", all.count < 40, `api calls=${all.count} in ${Date.now() - t0} ms`);
  check.ok("the run button is usable again afterwards (lock released)", !(await btn.isDisabled()));
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
