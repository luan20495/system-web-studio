// @class: real-backend — a REAL backend error (404 UNKNOWN_ACTION) reaches a REAL error state in the UI; nothing is intercepted.
import { Blocked } from "../lib/report.mjs";
import { openTestPanel, settledOutcome, flagOff } from "../lib/testpanel.mjs";
import { newPage, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-10", title = "Mutation/action failure: real backend error, UI error state, no fake success";
export const blocker = { owner: "C2", ref: "definition ops", reason: "the fixture action could not be added (typed V2 definition operations were refused by the backend)" };
export async function run({ cfg, fx, browser, check }) {
  if (!fx.notes.definitionOps?.ok) throw new Blocked("C2", `${blocker.reason}: ${JSON.stringify(fx.notes.definitionOps)}`, "ADD_ACTION");
  const w = fx.workspaces.A, p = fx.projects.A.id, A = fx.sessions.adminA;
  const page = await newPage(browser);
  await openTestPanel(page, cfg, fx.users.adminA, p);
  const btn = page.getByTestId(`run-action:${fx.ids.actionId}`);
  check.ok("the action is listed and runnable for a workspace admin", (await btn.count()) === 1 && !(await btn.isDisabled()), await btn.getAttribute("title"));
  // make the panel stale: delete the action on the SERVER through another session, then run it from the (stale) panel
  const cur = (await A.get(`/workspaces/${w}/projects/${p}/schema`)).body;
  const del = await A.patch(`/workspaces/${w}/projects/${p}/schema`, { expectedRevision: cur.revision, operations: [{ type: "REMOVE_ACTION", definitionId: fx.ids.actionId }], summary: "e2e: remove action" });
  check.ok("(setup) the action was removed on the server", del.status === 200, `status=${del.status} code=${del.body?.code}`);
  await btn.click();
  const out = await settledOutcome(page, `action-row:${fx.ids.actionId}`);
  if (out.state === "NOT_READY" && flagOff(out.text)) { await restore(A, w, p, fx); throw new Blocked("C0", `server answered "not mounted": ${out.text.slice(0, 160)} (app.workflow.enabled must be true in the test stack)`, "app.workflow.enabled"); }
  check.ok("the UI shows an ERROR state for the real backend failure", out.state === "ERROR", `${out.state}: ${out.text.slice(0, 200)}`);
  check.ok("the message says the item was not found, in plain language (no raw status line)", /Không tìm thấy mục cần chạy/.test(out.text), out.text.slice(0, 200));
  check.ok("NO success is displayed anywhere in the row", (await page.getByTestId(`action-row:${fx.ids.actionId}`).locator('[data-outcome="SUCCESS"]').count()) === 0);
  check.ok("the run button is usable again (not stuck in 'Đang chạy…')", !(await btn.isDisabled()) && !/Đang chạy/.test(await btn.innerText()));
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await restore(A, w, p, fx);
  await page.context().close();
}
async function restore(A, w, p, fx) {
  const cur = (await A.get(`/workspaces/${w}/projects/${p}/schema`)).body;
  await A.patch(`/workspaces/${w}/projects/${p}/schema`, { expectedRevision: cur.revision, operations: [{ type: "ADD_ACTION", definition: { id: fx.ids.actionId, name: "E2E thông báo", type: "NOTIFY", channel: "IN_APP", templateRef: "e2e-template" } }], summary: "e2e: restore action" });
}
