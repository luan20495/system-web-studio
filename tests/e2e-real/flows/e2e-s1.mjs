// @class: real-backend — supplementary: TEST-mode action through the real R2 route. Never a success, never a write.
import { Blocked } from "../lib/report.mjs";
import { openTestPanel, settledOutcome, flagOff } from "../lib/testpanel.mjs";
import { newPage, pageProblems } from "../lib/ui.mjs";
export const id = "E2E-S1", title = "(supplementary) TEST-mode action answers WOULD_RUN from the real backend";
export async function run({ cfg, fx, browser, check }) {
  if (!fx.notes.definitionOps?.ok) throw new Blocked("C2", `typed V2 definition operations were refused: ${JSON.stringify(fx.notes.definitionOps)}`, "ADD_ACTION");
  const page = await newPage(browser);
  await openTestPanel(page, cfg, fx.users.adminA, fx.projects.A.id);
  const row = `action-row:${fx.ids.actionId}`;
  const resp = page.waitForResponse((r) => /\/app-runtime\/actions\/.+\/execute$/.test(new URL(r.url()).pathname), { timeout: 30_000 }).catch(() => null);
  await page.getByTestId(`run-action:${fx.ids.actionId}`).click();
  const r = await resp;
  const out = await settledOutcome(page, row);
  if (out.state === "NOT_READY" && flagOff(out.text)) throw new Blocked("C0", `server answered "not mounted": ${out.text.slice(0, 160)} (app.workflow.enabled must be true in the test stack)`, "app.workflow.enabled");
  const body = await r?.json().catch(() => null);
  check.ok("the browser called POST …/app-runtime/actions/{id}/execute", !!r, "no request seen");
  check.ok("the request body only carries contract fields (mode TEST, idempotencyKey)", (() => { try { const b = JSON.parse(r.request().postData() ?? "{}"); return b.mode === "TEST" && typeof b.idempotencyKey === "string" && Object.keys(b).every((k) => ["mode", "inputs", "idempotencyKey", "trigger"].includes(k)); } catch { return false; } })());
  check.ok("the server answered 200 with status WOULD_RUN", r?.status() === 200 && body?.status === "WOULD_RUN", `status=${r?.status()} body.status=${body?.status} code=${body?.error?.code ?? body?.code}`);
  check.ok("the UI shows 'would run', NOT success", out.state === "WOULD_RUN", `${out.state}: ${out.text.slice(0, 160)}`);
  check.ok("no SUCCESS state is shown in the row", (await page.getByTestId(row).locator('[data-outcome="SUCCESS"]').count()) === 0);
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
