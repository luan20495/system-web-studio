// @class: real-backend — supplementary: a save that loses to another editor (stale expectedRevision → 409) is shown as a failure, never as "saved", and the other editor's change survives.
import { loginUi, newPage, openBuilder, pageProblems, selectFirstSection, countRequests } from "../lib/ui.mjs";
export const id = "E2E-S5", title = "(supplementary) Save conflict: stale revision → 409, no fake 'saved', other editor's change kept";
export async function run({ cfg, fx, browser, check }) {
  const w = fx.workspaces.A, p = fx.projects.A.id, A = fx.sessions.adminA, base = `/workspaces/${w}/projects/${p}`;
  const page = await newPage(browser);
  await loginUi(page, cfg, fx.users.adminA.username, fx.users.adminA.password);
  await openBuilder(page, cfg, p);
  const sel = await selectFirstSection(page);
  check.ok("clicking a section on the canvas opens its properties", sel.ok, `attempts=${sel.attempts}`);
  if (!sel.ok) { await page.context().close(); return; }
  // another editor saves first (a real write through the API): the browser's revision is now stale
  const cur = (await A.get(`${base}/schema`)).body;
  const other = `E2E-OTHER-${fx.runId}`;
  const win = await A.patch(`${base}/schema`, { expectedRevision: cur.revision, summary: "e2e: other editor", operations: [{ type: "ADD_ACTION", definition: { id: "e2e-other-editor", name: other, type: "START_WORKFLOW", workflowRef: fx.ids.workflowId, idempotency: "REQUIRED" } }] });
  check.ok("(setup) the other editor's save was accepted (200)", win.status === 200, `status=${win.status} code=${win.body?.code}`);
  const revAfterOther = (await A.get(`${base}`)).body.revision;
  const mine = `E2E-MINE-${fx.runId}`;
  await page.locator(".bx-right input").first().fill(mine);
  const saves = countRequests(page, /\/schema$/, "PATCH");
  const [patch] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PATCH" && /\/schema$/.test(new URL(r.url()).pathname), { timeout: 20_000 }),
    page.getByRole("button", { name: /Lưu thay đổi/ }).click(),
  ]);
  check.ok("the stale save is refused by the server with 409 (answered)", patch.status() === 409, `status=${patch.status()} code=${(await patch.json().catch(() => ({}))).code}`);
  await page.waitForTimeout(1500);
  const bar = await page.locator(".saveState").innerText();
  check.ok("the top bar does NOT say saved after a refused save", !/Đã lưu/.test(bar) || /thất bại|xung đột|Lưu thất bại/.test(bar), bar);
  const after = (await A.get(`${base}/schema`)).body;
  check.ok("the other editor's change is on the server and mine is not (no silent overwrite)", JSON.stringify(after.schema).includes("e2e-other-editor") && !JSON.stringify(after.schema).includes(mine), `revision ${revAfterOther} → ${after.revision}`);
  check.ok("the revision did not move because of the refused save", after.revision === revAfterOther, `${revAfterOther} → ${after.revision}`);
  check.ok("the refused save was not retried in a loop (≤ 2 PATCH requests)", saves.count <= 2, `patches=${saves.count}`);
  check.ok("no unhandled page errors", pageProblems(page).filter((e) => !/409/.test(e)).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
