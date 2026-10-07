// @class: real-backend — C2 contract §6/§7B/§7G: STALE_PUBLISH is NOT an HTTP conflict. A publish accepted BEFORE a rollback (the render worker is PAUSED, so it sits in BUILDING) is older than the rollback: when the worker
// resumes, its pointer write is refused and the deployment ends status FAILED with error "[STALE_PUBLISH] …". Studio must show that failed deployment clearly (never success, never a conflict dialog) and let the user publish again with a NEW key.
import { newPage, pageProblems } from "../lib/ui.mjs";
import { releaseApi, baseline, openRelease, captureRelease, needHooks, paused, until, KEY_RE } from "../lib/release.mjs";
export const id = "E2E-P08", title = "Stale publish becomes terminal FAILED [STALE_PUBLISH]; Studio shows it; publish again works";
export async function run({ cfg, fx, browser, check }) {
  needHooks(cfg, ["render"]);
  const api = releaseApi(fx); const { b, c } = await baseline(fx, api);
  await api.edit(`p08-${fx.runId}`);
  const page = await newPage(browser); const log = captureRelease(page);
  await openRelease(page, cfg, fx.users.adminA, fx.projects.A.id);
  let rb, seenBuilding = false;
  await paused(cfg, "render", async () => {
    await page.getByTestId("publish").click();
    const dep = page.getByTestId("deployment"); await dep.waitFor({ timeout: 15_000 });
    await page.waitForFunction(() => document.querySelector('[data-testid="deployment"]')?.getAttribute("data-status") === "BUILDING", null, { timeout: 20_000 }).then(() => { seenBuilding = true; }).catch(() => undefined);
    check.ok("UI: the publish is shown as a BUSY step (BUILDING) while the worker is paused — not success", seenBuilding && (await page.getByTestId("deployment-success").count()) === 0 && (await page.getByTestId("publish").count()) === 0);
    check.ok("UI: while busy the dialog cannot be dismissed and offers no second publish", (await page.getByRole("button", { name: "Đang xử lý…" }).isDisabled()) && (await page.getByRole("button", { name: "Đóng" }).isDisabled()));
    const list = await api.deployments(); const mine = list.find((d) => d.status === "BUILDING");
    // a newer release operation: rollback to B (allowed: the scope is held only during DEPLOYING)
    rb = await api.rollback({ deploymentId: b.id });
    check.ok("a rollback while the publish is still BUILDING goes through (200)", rb.status === 200 && rb.body.currentDeploymentId === b.id, `status=${rb.status} ${rb.body?.code}`, "http");
    fx.notes.p08Building = mine?.id;
  });
  const failed = page.getByTestId("deployment-failed");
  await failed.waitFor({ timeout: 90_000 });
  check.ok("UI: the deployment ends as a FAILED deployment with the STALE_PUBLISH explanation (data-code)", (await failed.getAttribute("data-code")) === "STALE_PUBLISH" && /cũ hơn bản đang chạy/.test(await failed.innerText()), (await failed.innerText()).replace(/\s+/g, " ").slice(0, 160));
  check.ok("UI: it is not shown as success and not as a conflict/HTTP error (no error dialog)", (await page.getByTestId("deployment-success").count()) === 0 && (await page.getByTestId("release-error").count()) === 0 && (await page.getByTestId("deployment").getAttribute("data-status")) === "FAILED");
  const events = await page.getByTestId("deployment").locator("li").allInnerTexts();
  check.ok("UI: the history lists the STALE_PUBLISH event in plain words", events.some((t) => /đã cũ hơn bản đang chạy/.test(t)), events.join(" | "));
  const staleId = (await api.deployments()).find((d) => d.status === "FAILED" && /STALE_PUBLISH/.test(d.error ?? ""))?.id;
  const dep = staleId ? await api.deployment(staleId) : null;       // the list has no events; the single read has
  check.ok("server: the deployment is terminal FAILED with error '[STALE_PUBLISH] …' and a STALE_PUBLISH event", !!dep && /^\[STALE_PUBLISH\]/.test(dep.error) && dep.events.some((e) => e.status === "STALE_PUBLISH") && !!dep.finishedAt, JSON.stringify({ s: dep?.status, e: dep?.error }), "persistence");
  const site = await api.site();
  check.ok("server: the stale publish wrote nothing — the rollback target B is still the active release, operation idle", site.currentDeploymentId === b.id && site.operation === null, JSON.stringify({ cur: site.currentDeploymentId, op: site.operation }), "persistence");
  // publish again: a NEW key, a NEW deployment, and it succeeds
  const keyBefore = log.filter((x) => x.method === "POST" && /\/publish$/.test(x.path)).at(-1).headers["idempotency-key"];
  await page.getByTestId("publish-again").click();
  await page.getByTestId("publish").click();
  await page.getByTestId("deployment-success").waitFor({ timeout: 60_000 });
  const posts = log.filter((x) => x.method === "POST" && /\/publish$/.test(x.path));
  check.ok("publish again used a NEW Idempotency-Key (replaying the old one would only return the FAILED deployment)", posts.length === 2 && KEY_RE.test(posts[1].headers["idempotency-key"]) && posts[1].headers["idempotency-key"] !== keyBefore, JSON.stringify(posts.map((x) => x.headers["idempotency-key"])), "http");
  check.ok("…and it succeeded: the site now serves the new deployment", (await api.site()).currentDeploymentId !== b.id, "", "persistence");
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
