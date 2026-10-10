// @class: real-backend — idempotency (C2 contract §1/§7 E,F). Same key + same payload = ONE effect (publish: 202 + Idempotent-Replay + the original deployment; rollback/unpublish: both 200, pointer moved once).
// Same key + a different payload = 409 IDEMPOTENCY_KEY_REUSED (judged before any eligibility check). The UI never reuses a key for another payload and uses a fresh key after an outcome the user repeats.
import { newPage, pageProblems, chooseVisibility } from "../lib/ui.mjs";
import { releaseApi, baseline, openRelease, captureRelease, KEY_RE, showRelease , closeRelease } from "../lib/release.mjs";
export const id = "E2E-P07", title = "Idempotency: replay, 409 IDEMPOTENCY_KEY_REUSED, and the UI's key lifecycle";
export async function run({ cfg, fx, browser, check }) {
  const api = releaseApi(fx); const { a, b, c } = await baseline(fx, api);
  const rev = await api.revision(), key = `e2e:${fx.runId}:p07-${Date.now()}`;
  // ---- publish
  const first = await api.publish(key, "PRIVATE", rev);
  const replay = await api.publish(key, "PRIVATE", rev);
  check.ok("publish: same key + same payload → 202, Idempotent-Replay: true, the ORIGINAL deployment", first.status === 202 && replay.status === 202 && replay.headers.get("idempotent-replay") === "true" && replay.body.id === first.body.id && first.headers.get("idempotent-replay") === null, `${first.status}/${replay.status}`, "http");
  const reused = await api.publish(key, "PUBLIC", rev);
  check.ok("publish: same key + DIFFERENT payload → 409 IDEMPOTENCY_KEY_REUSED", reused.status === 409 && reused.body?.code === "IDEMPOTENCY_KEY_REUSED", `status=${reused.status} ${reused.body?.code}`, "http");
  check.ok("…the standard envelope without `retryable`, and the documented message", JSON.stringify(Object.keys(reused.body).sort()) === JSON.stringify(["code", "details", "message", "requestId"]) && reused.body.message === "This Idempotency-Key was already used with a different request", JSON.stringify(Object.keys(reused.body)), "http");
  check.ok("a missing key is 400 MISSING_HEADER and a malformed one 400 INVALID_IDEMPOTENCY_KEY", (await api.S.post(`${api.base}/publish`, { visibility: "PRIVATE", expectedRevision: rev })).body?.code === "MISSING_HEADER" && (await api.publish("short", "PRIVATE", rev)).body?.code === "INVALID_IDEMPOTENCY_KEY", "", "http");
  await api.settle(first.body.id);
  // ---- rollback: one effect for two identical requests
  const rk = `e2e:${fx.runId}:p07rb-${Date.now()}`, site0 = await api.site(), tgt = (await api.deployments()).find((d) => d.status === "RUNNING" && d.id !== site0.currentDeploymentId);
  const r1 = await api.rollback({ deploymentId: tgt.id }, rk), r2 = await api.rollback({ deploymentId: tgt.id }, rk), site1 = await api.site();
  check.ok("rollback: same key + same payload → both 200 and ONE effect (pointerVersion +1, not +2)", r1.status === 200 && r2.status === 200 && site1.currentDeploymentId === tgt.id && site1.pointerVersion === site0.pointerVersion + 1, `${r1.status}/${r2.status} ${site0.pointerVersion}→${site1.pointerVersion}`, "persistence");
  const other = (await api.deployments()).find((d) => d.status === "RUNNING" && d.id !== tgt.id);
  const rr = await api.rollback({ deploymentId: other.id }, rk);
  check.ok("rollback: same key + a DIFFERENT target → 409 IDEMPOTENCY_KEY_REUSED (judged before any eligibility check)", rr.status === 409 && rr.body?.code === "IDEMPOTENCY_KEY_REUSED", `status=${rr.status} ${rr.body?.code}`, "http");
  const uk = `e2e:${fx.runId}:p07un-${Date.now()}`, u1 = await api.unpublish(null, uk), pvU = (await api.site()).pointerVersion, u2 = await api.unpublish(null, uk);
  check.ok("unpublish: same key twice → both 200, pointerVersion unchanged by the second", u1.status === 200 && u2.status === 200 && (await api.site()).pointerVersion === pvU, `${u1.status}/${u2.status}`, "persistence");
  const uu = await api.unpublish(site1.currentDeploymentId, uk);
  check.ok("unpublish: same key + a different expectation → 409 IDEMPOTENCY_KEY_REUSED", uu.status === 409 && uu.body?.code === "IDEMPOTENCY_KEY_REUSED", `status=${uu.status} ${uu.body?.code}`, "http");
  await api.rollback({ deploymentId: tgt.id });   // site online again for the UI part
  // ---- the UI's key lifecycle
  const page = await newPage(browser); const log = captureRelease(page);
  await openRelease(page, cfg, fx.users.adminA, fx.projects.A.id);
  await page.getByTestId("publish").click();
  await page.getByTestId("deployment-success").waitFor({ timeout: 60_000 });
  await page.getByTestId("deployment").waitFor();
  check.ok("UI: after a finished publish the dialog offers 'Xuất bản lại' only for non-success outcomes (RUNNING offers Close)", (await page.getByTestId("publish-again").count()) === 0);
  await closeRelease(page);
  await showRelease(page);
  check.ok("UI: the 'Công khai' radio is offered and checked after choosing it", (await chooseVisibility(page, "PUBLIC")).checked);
  await page.getByTestId("publish").click();
  await page.getByTestId("deployment").waitFor({ timeout: 30_000 });
  const posts = log.filter((x) => x.method === "POST" && /\/publish$/.test(x.path));
  const keys = posts.map((x) => x.headers["idempotency-key"]);
  check.ok("UI: two publishes with different payloads (PRIVATE then PUBLIC) used two DIFFERENT valid keys", posts.length === 2 && keys.every((k) => KEY_RE.test(k ?? "")) && keys[0] !== keys[1], JSON.stringify(keys), "http");
  check.ok("UI: neither was refused with IDEMPOTENCY_KEY_REUSED (a key per dialog would have been)", await page.getByTestId("release-error").count() === 0 && (await page.getByTestId("deployment").getAttribute("data-status")) !== null);
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
