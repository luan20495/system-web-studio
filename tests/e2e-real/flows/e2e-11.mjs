// @class: real-backend — workflow start from Studio (TEST mode), backend executes, UI reads the run state.
import { Blocked } from "../lib/report.mjs";
import { openTestPanel, settledOutcome, flagOff, volatileStores } from "../lib/testpanel.mjs";
import { newPage, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-11", title = "Workflow start from Studio, backend executes, UI reads state/result";
export async function run({ cfg, fx, browser, check }) {
  if (!fx.notes.definitionOps?.ok) throw new Blocked("C2", `typed V2 definition operations were refused: ${JSON.stringify(fx.notes.definitionOps)}`, "ADD_WORKFLOW_REF");
  const page = await newPage(browser);
  await openTestPanel(page, cfg, fx.users.adminA, fx.projects.A.id);
  const start = page.waitForResponse((r) => /\/app-runtime\/workflows\/.+\/runs$/.test(new URL(r.url()).pathname) && r.request().method() === "POST", { timeout: 30_000 }).catch(() => null);
  const btn = page.getByTestId(`run-workflow:${fx.ids.workflowId}`);
  check.ok("the workflow is listed and startable for a workspace admin", (await btn.count()) === 1 && !(await btn.isDisabled()), await btn.getAttribute("title"));
  await btn.click();
  const r = await start;
  const out = await settledOutcome(page, `workflow-row:${fx.ids.workflowId}`, 60_000);
  if (out.state === "NOT_READY" && flagOff(out.text)) throw new Blocked("C0", `server answered "not mounted": ${out.text.slice(0, 160)} (app.workflow.enabled must be true in the test stack)`, "app.workflow.enabled");
  if (volatileStores(out.text)) throw new Blocked("C0", "503 RUNTIME_STORES_VOLATILE: run stores are in memory (V29 not integrated) and app.workflow.allow-volatile-stores is false", "B-C0-W-01");
  const body = await r?.json().catch(() => null);
  check.ok("POST …/workflows/{id}/runs answered 202 with a run id", r?.status() === 202 && typeof body?.runId === "string", `status=${r?.status()} code=${body?.code}`);
  check.ok("the start request carried an idempotencyKey (required by the contract)", (() => { try { return typeof JSON.parse(r.request().postData() ?? "{}").idempotencyKey === "string"; } catch { return false; } })());
  const status = await page.getByTestId(`run-status:${fx.ids.workflowId}`).innerText().catch(() => "");
  check.ok("the UI shows the run id and a status read from the server", !!body?.runId && status.includes(body.runId), status.slice(0, 160));
  check.ok("the run finished as a simulated (TEST) run: 'would run', not a real success", out.state === "WOULD_RUN", `${out.state}: ${out.text.slice(0, 160)}`);
  const view = await fx.sessions.adminA.get(`/workspaces/${fx.workspaces.A}/projects/${fx.projects.A.id}/app-runtime/workflow-runs/${body?.runId}`);
  check.ok("the run view read through a separate session agrees (terminal status)", view.status === 200 && view.body?.runId === body?.runId, `status=${view.status} ${view.body?.status}`);
  check.ok("the run belongs to the TEST mode", view.body?.mode === "TEST", view.body?.mode);
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
