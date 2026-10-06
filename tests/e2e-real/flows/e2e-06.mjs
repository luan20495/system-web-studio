// @class: real-backend — BLOCKED skeleton: no HTTP route can create what this flow configures.
import { Blocked } from "../lib/report.mjs";
import { loginUi, newPage, openBuilder, bodyText } from "../lib/ui.mjs";
export const id = "E2E-06", title = "Configure DataSource/Query and query via the real backend";
export const blocker = { owner: "C0", ref: "B-C0-W-03", reason: "No management HTTP API for data sources, credentials, queries or source bindings exists on integration/v2 (f894cc6); a source needs a credential ciphertext only the server key can produce. AppDefinition typed operations cannot add a data source either (it is granted, not created)." };
export async function run({ cfg, fx, browser, check }) {
  // Evidence that the UI says so honestly instead of faking a configuration (a failing check here turns BLOCKED into FAIL).
  const page = await newPage(browser);
  await loginUi(page, cfg, fx.users.adminA.username, fx.users.adminA.password);
  await openBuilder(page, cfg, fx.projects.A.id);
  await page.getByRole("button", { name: /Dữ liệu/ }).first().click().catch(() => undefined);
  await page.waitForTimeout(500);
  const t = await bodyText(page);
  check.ok("the Data panel says 'Chưa sẵn sàng' (no fake data source / query / mapping can be created)", /Chưa sẵn sàng/.test(t), t.slice(0, 160));
  await page.context().close();
  throw new Blocked(blocker.owner, blocker.reason, blocker.ref);
}
