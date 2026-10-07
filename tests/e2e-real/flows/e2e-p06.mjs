// @class: real-backend — C2 contract §2/§6: ROLLBACK_STALE. The dialog is open (it saw release C active); another session rolls back to B; the UI's rollback to A carries expectedActiveDeploymentId = C → 409 ROLLBACK_STALE, NOTHING changes,
// Studio says the active release changed and offers a reload (no blind retry). A retry decided on fresh state works. Unpublish with a stale expectation answers the same.
import { newPage, pageProblems } from "../lib/ui.mjs";
import { releaseApi, baseline, openRelease, captureRelease } from "../lib/release.mjs";
export const id = "E2E-P06", title = "Rollback stale: 409 ROLLBACK_STALE, nothing changed, reload and decide again";
export async function run({ cfg, fx, browser, check }) {
  const api = releaseApi(fx); const { a, b, c } = await baseline(fx, api);
  const page = await newPage(browser); const log = captureRelease(page);
  await openRelease(page, cfg, fx.users.adminA, fx.projects.A.id);
  // another editor moves the active release behind the dialog's back
  const other = await api.rollback({ deploymentId: b.id }, `e2e:${fx.runId}:p06-other`);
  check.ok("(setup) another session rolled back to B (200)", other.status === 200 && other.body.currentDeploymentId === b.id, `status=${other.status}`, "http");
  const pv = (await api.site()).pointerVersion;
  const resp = page.waitForResponse((r) => r.request().method() === "POST" && /\/site\/rollback$/.test(new URL(r.url()).pathname), { timeout: 30_000 });
  await page.getByTestId(`rollback:${a.id}`).click();
  const r = await resp; const body = await r.json().catch(() => ({}));
  check.ok("the UI's rollback carried the release it saw as active (C) and the server answered 409 ROLLBACK_STALE", r.status() === 409 && body.code === "ROLLBACK_STALE" && JSON.parse(r.request().postData()).expectedActiveDeploymentId === c.id, `status=${r.status()} ${body.code}`, "http");
  check.ok("…details carry the real active release and the expected one; no Retry-After (not retryable as is)", body.details?.activeDeploymentId === b.id && body.details?.expectedActiveDeploymentId === c.id && r.headers()["retry-after"] === undefined, JSON.stringify(body.details), "http");
  const err = page.getByTestId("release-error"); await err.waitFor({ timeout: 10_000 });
  check.ok("UI: 'the active release changed — reload and decide again' (kind stale), says nothing was changed", (await err.getAttribute("data-kind")) === "stale" && /Không có gì bị thay đổi/.test(await err.innerText()), (await err.innerText()).replace(/\s+/g, " "));
  check.ok("UI: NO blind retry button (not retryable as is); a reload action is offered", (await page.getByTestId("release-retry").count()) === 0 && (await page.getByTestId("release-reload").count()) === 1);
  check.ok("server: nothing changed (still B, same pointerVersion)", (await api.site()).currentDeploymentId === b.id && (await api.site()).pointerVersion === pv, "", "persistence");
  // reload: the dialog now shows B as the served release and decides again
  await page.getByTestId("release-reload").click(); await page.waitForTimeout(1200);
  check.ok("after the reload the served release is B and C (rolled away from) is no longer offered", (await page.getByTestId(`release:${b.id}`).innerText().catch(() => "")).includes("Đang phục vụ") && (await page.getByTestId(`rollback:${c.id}`).count()) === 0);
  const again = page.waitForResponse((x) => x.request().method() === "POST" && /\/site\/rollback$/.test(new URL(x.url()).pathname), { timeout: 30_000 });
  await page.getByTestId(`rollback:${a.id}`).click(); const r2 = await again;
  check.ok("the decision taken on fresh state succeeds (200) with the NEW expectation (B)", r2.status() === 200 && JSON.parse(r2.request().postData()).expectedActiveDeploymentId === b.id, `status=${r2.status()}`, "http");
  check.ok("the new request used a NEW Idempotency-Key (the refused one was not replayed)", log.filter((x) => x.method === "POST" && /site\/rollback/.test(x.path)).map((x) => x.headers["idempotency-key"]).filter(Boolean).length === new Set(log.filter((x) => x.method === "POST" && /site\/rollback/.test(x.path)).map((x) => x.headers["idempotency-key"])).size);
  // unpublish with a stale expectation: same code
  const un = await api.unpublish(c.id); check.ok("API: unpublish expecting a release that is not active → 409 ROLLBACK_STALE, still online", un.status === 409 && un.body?.code === "ROLLBACK_STALE" && (await api.site()).online === true, `status=${un.status} ${un.body?.code}`, "http");
  const pvNow = (await api.site()).pointerVersion;
  const already = await api.rollback({ deploymentId: a.id, expectedActiveDeploymentId: c.id });   // A is active now; the expectation (C) is stale
  check.ok("API: a target that is ALREADY active answers 200 even with a stale expectation (a retry that already succeeded), and changes nothing", already.status === 200 && (await api.site()).pointerVersion === pvNow, `status=${already.status}`, "http");
  check.ok("no unhandled page errors", pageProblems(page).filter((e) => !/409/.test(e)).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
