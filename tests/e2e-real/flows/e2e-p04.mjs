// @class: real-backend — C2 contract §4/§6/§7B: while a PUBLISH holds the scope (its DEPLOYING step; the artifact store is PAUSED so the step stays), SiteInfo.operation = {kind:"PUBLISH", deploymentId, since, leaseUntil},
// a rollback / unpublish answers 409 SCOPE_BUSY with `Retry-After: 5` and the holder in details, and Studio mirrors the busy scope. Needs the artifact-store hooks.
import { newPage, pageProblems } from "../lib/ui.mjs";
import { releaseApi, baseline, openRelease, needHooks, pauseWhen, until, sleep } from "../lib/release.mjs";
export const id = "E2E-P04", title = "Scope busy: operation=PUBLISH, 409 SCOPE_BUSY + Retry-After 5, busy UI, retry after the scope is free";
export async function run({ cfg, fx, browser, check }) {
  needHooks(cfg, ["store"]);
  const api = releaseApi(fx); const { a, c } = await baseline(fx, api);
  const pv0 = (await api.site()).pointerVersion;
  // the UI is opened while the scope is IDLE (so it has not seen any operation yet)
  const page = await newPage(browser);
  await openRelease(page, cfg, fx.users.adminA, fx.projects.A.id);
  check.ok("the dialog opened on an idle scope: no busy banner, controls enabled", (await page.getByTestId("release-operation").count()) === 0 && (await page.getByTestId("publish").isEnabled()));
  await api.edit(`p04-${fx.runId}`);
  let held, busy, un, rbUi;
  const r = await api.publish(`e2e:${fx.runId}:p04-${Date.now()}`);
  check.ok("(setup) a publish was accepted (202)", r.status === 202, `${r.status}`, "http");
  // freeze the artifact store the moment the publish reaches DEPLOYING (the only step that holds the scope): the lease stays alive and the step stays
  const at = await pauseWhen(cfg, "store", () => api.deployment(r.body.id), (d) => d.status === "DEPLOYING", async () => {
    held = await until(() => api.site(), (s) => s.operation !== null, 10_000, 200);
    check.ok("SiteInfo.operation = PUBLISH with this deployment's id, since and leaseUntil", held.operation?.kind === "PUBLISH" && held.operation.deploymentId === r.body.id && !!held.operation.since && !!held.operation.leaseUntil, JSON.stringify(held.operation), "http");
    check.ok("the lease is in the future (a live operation, not an expired one)", !!held.operation && new Date(held.operation.leaseUntil) > new Date(), held.operation?.leaseUntil, "http");
    busy = await api.rollback({ deploymentId: a.id });
    check.ok("rollback while the PUBLISH holds the scope → 409 SCOPE_BUSY", busy.status === 409 && busy.body?.code === "SCOPE_BUSY", `status=${busy.status} ${busy.body?.code}`, "http");
    check.ok("…with Retry-After: 5", busy.headers.get("retry-after") === "5", busy.headers.get("retry-after"), "http");
    check.ok("…the standard envelope (no `retryable` field) and the holder in details.operation", JSON.stringify(Object.keys(busy.body).sort()) === JSON.stringify(["code", "details", "message", "requestId"]) && busy.body.details?.operation?.kind === "PUBLISH" && busy.body.details?.environment === "PRODUCTION", JSON.stringify(Object.keys(busy.body)), "http");
    un = await api.unpublish(); check.ok("unpublish while the PUBLISH holds the scope → 409 SCOPE_BUSY", un.status === 409 && un.body?.code === "SCOPE_BUSY", `status=${un.status} ${un.body?.code}`, "http");
    // UI: the dialog was opened before the operation: the first click is judged by the SERVER; the answer must be explained, not swallowed
    await page.getByTestId(`rollback:${a.id}`).click();
    const err = page.getByTestId("release-error"); await err.waitFor({ timeout: 15_000 });
    rbUi = { kind: await err.getAttribute("data-kind"), text: await err.innerText() };
    check.ok("UI: the server's SCOPE_BUSY is shown (kind scope-busy) naming the running operation and the 5 s wait", rbUi.kind === "scope-busy" && /đang xuất bản/.test(rbUi.text) && /5 giây/.test(rbUi.text), rbUi.text.replace(/\s+/g, " "));
    check.ok("UI: a retry affordance exists and waits for Retry-After (disabled at first)", (await page.getByTestId("release-retry").count()) === 1 && (await page.getByTestId("release-retry").isDisabled()));
    const banner = page.getByTestId("release-operation"); await banner.waitFor({ timeout: 15_000 });
    check.ok("UI: SiteInfo.operation = PUBLISH is reflected (banner with kind PUBLISH)", (await banner.getAttribute("data-kind")) === "PUBLISH");
    check.ok("UI: Publish and every rollback/unpublish control are disabled while the scope is held", (await page.getByTestId("publish").isDisabled()) && (await page.getByTestId(`rollback:${a.id}`).isDisabled()));
  });
  check.ok("(setup) the publish reached DEPLOYING and the store was frozen there", at.reached, at.value?.status);
  if (!at.reached) { await page.context().close(); return; }
  // the store is back: the publish finishes, the operation disappears, the UI follows by itself
  const done = await until(() => api.site(), (s) => s.operation === null && s.currentDeploymentId !== c.id, 60_000, 500);
  check.ok("after the store is resumed the publish completes and the scope is idle again (operation = null)", done.operation === null && done.currentDeploymentId !== c.id, JSON.stringify({ cur: done.currentDeploymentId, op: done.operation }), "persistence");
  await page.getByTestId("release-operation").waitFor({ state: "detached", timeout: 30_000 }).catch(() => undefined);
  check.ok("UI: the busy banner goes away by itself (polling) and the controls come back", (await page.getByTestId("release-operation").count()) === 0 && await page.getByTestId("publish").isEnabled({ timeout: 10_000 }).catch(() => false));
  check.ok("pointerVersion advanced by the one completed publish", done.pointerVersion === pv0 + 1, `${pv0} → ${done.pointerVersion}`, "persistence");
  check.ok("the refused rollback / unpublish changed nothing (still online, pointer moved only by the publish)", done.online === true, "", "persistence");
  // retry after the scope is free: the same request now succeeds
  await page.getByTestId("release-reload").click().catch(() => undefined);
  await sleep(800);
  const retry = page.getByTestId("release-retry");
  if (await retry.count()) { await retry.waitFor({ state: "visible" }); await page.waitForFunction(() => { const b = document.querySelector('[data-testid="release-retry"]'); return !b || !b.disabled; }, null, { timeout: 15_000 }).catch(() => undefined); }
  const ok = await api.rollback({ deploymentId: a.id }); check.ok("retry after the scope is free: the rollback is accepted (200)", ok.status === 200, `status=${ok.status} ${ok.body?.code}`, "http");
  check.ok("no unhandled page errors (the 409 of the refused rollback is the expected console line)", pageProblems(page).filter((e) => !/409/.test(e)).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
