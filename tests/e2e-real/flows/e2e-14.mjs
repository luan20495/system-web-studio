// @class: real-backend — dependency down → correct failure → recovery. BLOCKED: no RabbitMQ dependency exists in the baseline and the suite has no hooks.
import { exec } from "node:child_process";
import { promisify } from "node:util";
import { Blocked } from "../lib/report.mjs";
import { openTestPanel, settledOutcome } from "../lib/testpanel.mjs";
import { newPage } from "../lib/ui.mjs";
const sh = promisify(exec);
export const id = "E2E-14", title = "RabbitMQ (or another dependency) down → correct failure → recovery/retry";
export const blocker = { owner: "C4", ref: "B-C4-06 / B-C0-W-01", reason: "No RabbitMQ consumer or queue adapter is wired on integration/v2 (f894cc6): C4 workflow queue and run stores are in memory (B-C4-05, B-C4-06). There is no dependency whose outage can change a workflow start." };
export async function run({ cfg, fx, browser, check }) {
  if (!cfg.rabbitWired) throw new Blocked(blocker.owner, `${blocker.reason} Set E2E_RABBITMQ_WIRED=1 only for a stack that has the queue adapter.`, blocker.ref);
  if (!cfg.stopRabbitCmd || !cfg.startRabbitCmd) throw new Blocked("C0", "no dependency hooks: set E2E_STOP_RABBIT_CMD and E2E_START_RABBIT_CMD (the suite never guesses how to stop a service)", "E2E_*_RABBIT_CMD");
  const page = await newPage(browser);
  await openTestPanel(page, cfg, fx.users.adminA, fx.projects.A.id);
  await sh(cfg.stopRabbitCmd, { timeout: 120_000 });
  try {
    await page.getByTestId(`run-workflow:${fx.ids.workflowId}`).click();
    const down = await settledOutcome(page, `workflow-row:${fx.ids.workflowId}`);
    check.ok("while the dependency is down the UI shows an unavailable/error state, never success", ["NOT_READY", "ERROR", "UNKNOWN"].includes(down.state), `${down.state}: ${down.text.slice(0, 160)}`);
  } finally { await sh(cfg.startRabbitCmd, { timeout: 120_000 }); }
  await page.waitForTimeout(8000);
  await page.getByTestId(`run-workflow:${fx.ids.workflowId}`).click();
  const up = await settledOutcome(page, `workflow-row:${fx.ids.workflowId}`, 60_000);
  check.ok("after recovery the same action works", ["WOULD_RUN", "SUCCESS"].includes(up.state), `${up.state}: ${up.text.slice(0, 160)}`);
  await page.context().close();
}
