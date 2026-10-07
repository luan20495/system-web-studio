// @class: real-backend — rollback in flight + browser refresh. The artifact store is PAUSED while the UI starts a rollback: SiteInfo.operation = {kind:"ROLLBACK"} (deploymentId null), a second rollback → 409 SCOPE_BUSY.
// The page is REFRESHED in the middle: the reloaded dialog must show the busy scope (not an idle one, not "done"), poll, and end on what the server finally holds. An accepted request is never shown as the final success.
import { newPage, pageProblems } from "../lib/ui.mjs";
import { releaseApi, baseline, openRelease, captureRelease, forbiddenSent, needHooks, paused, until, sleep } from "../lib/release.mjs";
export const id = "E2E-P05", title = "Rollback in flight: operation=ROLLBACK, refresh during rollback, polling until the server's final state";
export async function run({ cfg, fx, browser, check }) {
  needHooks(cfg, ["store"]);
  const api = releaseApi(fx); const { a, c } = await baseline(fx, api);
  const pv0 = (await api.site()).pointerVersion;
  const page = await newPage(browser); const log1 = captureRelease(page);
  await openRelease(page, cfg, fx.users.adminA, fx.projects.A.id);
  let op, second, finalState;
  await paused(cfg, "store", async () => {
    // start the rollback from the UI: the request hangs (the store is paused) until the client gives up or the store returns
    page.getByTestId(`rollback:${a.id}`).click().catch(() => undefined);   // the request hangs while the store is paused; a rejection after the refresh must not crash the runner
    op = await until(() => api.site(), (s) => s.operation !== null, 15_000, 150);
    check.ok("SiteInfo.operation = ROLLBACK while the rollback runs (deploymentId is null: only PUBLISH names one)", op.operation?.kind === "ROLLBACK" && op.operation.deploymentId === null && !!op.operation.leaseUntil, JSON.stringify(op.operation), "http");
    second = await api.rollback({ deploymentId: a.id });
    check.ok("a second rollback (no key) → 409 SCOPE_BUSY while the first runs", second.status === 409 && second.body?.code === "SCOPE_BUSY" && second.body?.details?.operation?.kind === "ROLLBACK", `status=${second.status} ${second.body?.code}`, "http");
    check.ok("the pointer has not moved yet: nothing is final while the operation runs", (await api.site()).currentDeploymentId === c.id, "", "persistence");
    // REFRESH the browser in the middle of the operation
    await page.reload({ waitUntil: "domcontentloaded" });
    await page.waitForSelector("iframe", { timeout: 20_000 }).catch(() => undefined);
    // the URL is `…/publish` after the first open: a refresh reopens the dialog by itself; otherwise open it from the header
    if (!(await page.getByTestId("release-modal").waitFor({ timeout: 8_000 }).then(() => true).catch(() => false))) { await page.locator("header.bx-top").getByRole("button", { name: /^Xuất bản/ }).click(); await page.getByTestId("release-modal").waitFor({ timeout: 15_000 }); }
    const banner = page.getByTestId("release-operation"); await banner.waitFor({ timeout: 15_000 });
    check.ok("after the refresh the dialog shows the busy scope: banner kind ROLLBACK", (await banner.getAttribute("data-kind")) === "ROLLBACK", await banner.innerText());
    check.ok("after the refresh Publish and the rollback buttons are disabled (no duplicate operation)", (await page.getByTestId("publish").isDisabled()) && (await page.getByTestId(`rollback:${a.id}`).isDisabled()));
    check.ok("after the refresh the page does NOT claim the rollback is done", (await page.getByTestId("release-note").count()) === 0);
  });
  // the store is back: the server finishes the operation
  finalState = await until(() => api.site(), (s) => s.operation === null, 60_000, 400);
  check.ok("the server's operation ends (operation = null)", finalState.operation === null, JSON.stringify(finalState.operation), "persistence");
  const rolled = finalState.currentDeploymentId === a.id;
  check.ok("the final pointer is either the restored release (the operation completed) or unchanged (the operation failed) — never anything else", rolled || finalState.currentDeploymentId === c.id, finalState.currentDeploymentId, "persistence");
  check.ok("pointerVersion is consistent with the outcome (+1 if restored, unchanged otherwise)", finalState.pointerVersion === pv0 + (rolled ? 1 : 0), `${pv0} → ${finalState.pointerVersion} restored=${rolled}`, "persistence");
  await page.getByTestId("release-operation").waitFor({ state: "detached", timeout: 30_000 }).catch(() => undefined);
  check.ok("the reloaded dialog follows the server by itself: busy banner gone, controls back", (await page.getByTestId("release-operation").count()) === 0);
  await page.waitForTimeout(1500);
  const shown = await page.getByTestId("site-box").innerText().catch(() => "");
  check.ok("the reloaded dialog shows the version the server really serves", rolled ? new RegExp(`phiên bản ${finalState.currentVersionNumber}\\b`, "i").test(shown) : true, shown.replace(/\s+/g, " ").slice(0, 120));
  check.ok("the first page (before the refresh) never sent pointerVersion", forbiddenSent(log1).length === 0);
  check.ok("no unhandled page errors other than the aborted request of the refreshed page", pageProblems(page).filter((e) => !/Failed to fetch|NetworkError|AbortError|net::/i.test(e)).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
