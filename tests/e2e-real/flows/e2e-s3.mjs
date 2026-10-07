// @class: real-backend — supplementary: an action WITHOUT a UI trigger is not runnable from the Test panel; the UI says why and sends nothing (the server would answer 404 UNKNOWN_ACTION, D-C4-10).
import { openTestPanel } from "../lib/testpanel.mjs";
import { newPage, pageProblems, countRequests } from "../lib/ui.mjs";
import { Blocked } from "../lib/report.mjs";
export const id = "E2E-S3", title = "(supplementary) Action without a trigger: button disabled with a reason, no request sent";
export async function run({ cfg, fx, browser, check }) {
  if (!fx.notes.definitionOps?.ok) throw new Blocked("C2", `typed V2 definition operations were refused: ${JSON.stringify(fx.notes.definitionOps)}`, "ADD_ACTION");
  const w = fx.workspaces.A, p = fx.projects.A.id, A = fx.sessions.adminA, base = `/workspaces/${w}/projects/${p}`;
  const noTrigger = "e2e-no-trigger";
  const cur = (await A.get(`${base}/schema`)).body;
  const add = await A.patch(`${base}/schema`, { expectedRevision: cur.revision, summary: "e2e: action without trigger", operations: [{ type: "ADD_ACTION", definition: { id: noTrigger, name: "E2E không trigger", type: "START_WORKFLOW", workflowRef: fx.ids.workflowId, idempotency: "REQUIRED" } }] });
  check.ok("(setup) an action without a trigger was added", add.status === 200, `status=${add.status} code=${add.body?.code}`);
  // contract evidence: this is what the server answers if the browser WERE to send it
  const direct = await A.post(`${base}/app-runtime/actions/${noTrigger}/execute`, { mode: "TEST", idempotencyKey: `e2e:${fx.runId}:s3` });
  check.ok("the server refuses it as 404 UNKNOWN_ACTION (why the UI must not offer it)", direct.status === 404 && direct.body?.error?.code === "UNKNOWN_ACTION", `status=${direct.status} code=${direct.body?.error?.code ?? direct.body?.code}`);
  const page = await newPage(browser);
  await openTestPanel(page, cfg, fx.users.adminA, p);
  const execs = countRequests(page, /\/app-runtime\/actions\/[^/]+\/execute$/, "POST");
  const reason = page.getByTestId(`no-trigger:${noTrigger}`);
  check.ok("the row says why it cannot be run (no UI event: only a workflow or another action can start it)", (await reason.count()) === 1 && /không gắn với sự kiện/.test(await reason.innerText()));
  const btn = page.getByTestId(`run-action:${noTrigger}`);
  check.ok("the 'Chạy thử' button is disabled and carries the reason as its title", (await btn.isDisabled()) && /không gắn với sự kiện/.test((await btn.getAttribute("title")) ?? ""));
  await btn.click({ force: true, timeout: 3000 }).catch(() => undefined);   // a real user cannot click a disabled button; force makes sure no handler is wired to it either
  await page.keyboard.press("Enter").catch(() => undefined);
  await page.waitForTimeout(1200);
  check.ok("NO execute request was sent for it", execs.count === 0, `requests=${JSON.stringify(execs.seen)}`);
  const ok = page.getByTestId(`run-action:${fx.ids.actionId}`);
  check.ok("control: the action that DOES declare a trigger is runnable", (await ok.count()) === 1 && !(await ok.isDisabled()));
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
