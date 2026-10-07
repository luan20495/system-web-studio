// @class: real-backend — C2 contract: publish from Studio. POST …/publish carries EXACTLY {visibility, expectedRevision} + Idempotency-Key + CSRF; the deployment is read by polling until a TERMINAL status; success is RUNNING only;
// SiteInfo is idle (operation = null) afterwards and pointerVersion moved by +1 and is shown to the user but NEVER sent back by the browser.
import { loginUi, newPage, pageProblems } from "../lib/ui.mjs";
import { releaseApi, openRelease, captureRelease, forbiddenSent, KEY_RE, until, TERMINAL, showRelease , closeRelease } from "../lib/release.mjs";
export const id = "E2E-P01", title = "Publish success: exact request, deployment polling, SiteInfo idle, pointerVersion observable but never sent";
export async function run({ cfg, fx, browser, check }) {
  const api = releaseApi(fx); const w = fx.workspaces.A, p = fx.projects.A.id;
  await api.edit(`p01-${fx.runId}`);
  const before = await api.site(), rev = await api.revision();
  check.ok("before: SiteInfo has the contract shape (pointerVersion integer, operation key present and null = idle)", Number.isInteger(before.pointerVersion) && "operation" in before && before.operation === null, JSON.stringify({ pv: before.pointerVersion, op: before.operation }), "http");
  const page = await newPage(browser); const log = captureRelease(page);
  await openRelease(page, cfg, fx.users.adminA, p);
  await page.getByTestId("pointer-version").waitFor({ timeout: 10_000 }).catch(() => undefined);
  const start = page.waitForResponse((r) => r.request().method() === "POST" && /\/publish$/.test(new URL(r.url()).pathname), { timeout: 20_000 });
  await page.getByTestId("publish").click();
  const r = await start;
  const req = r.request(), body = JSON.parse(req.postData() ?? "{}");
  check.ok("POST …/publish answered 202 (asynchronous)", r.status() === 202, `status=${r.status()}`, "http");
  check.ok("the body is EXACTLY {visibility, expectedRevision}", JSON.stringify(Object.keys(body).sort()) === JSON.stringify(["expectedRevision", "visibility"]) && body.expectedRevision === rev && ["PRIVATE", "PUBLIC"].includes(body.visibility), JSON.stringify(body), "http");
  check.ok("headers: Idempotency-Key in the contract format (8–120 of A-Za-z0-9_.:-) and X-XSRF-TOKEN", KEY_RE.test(req.headers()["idempotency-key"] ?? "") && !!req.headers()["x-xsrf-token"], req.headers()["idempotency-key"], "http");
  const dep = await r.json();
  check.ok("the deployment has the documented key set (id, projectId, versionId, versionNumber, visibility, status, url, error, provider, createdAt, updatedAt, finishedAt, events, mock)", ["id", "projectId", "versionId", "versionNumber", "visibility", "status", "url", "error", "provider", "createdAt", "updatedAt", "finishedAt", "events", "mock"].every((k) => k in dep), Object.keys(dep).join(","), "http");
  await page.getByTestId("deployment-success").waitFor({ timeout: 60_000 }).catch(() => undefined);
  const shown = await page.getByTestId("deployment").getAttribute("data-status");
  check.ok("the UI stopped on a TERMINAL status and shows success only for RUNNING", TERMINAL.includes(shown) && (shown !== "RUNNING" || (await page.getByTestId("deployment-success").count()) === 1), `status=${shown}`);
  check.ok("the dialog reports the site is up with the deployment URL", (await page.getByTestId("deployment-success").innerText().catch(() => "")).includes((await api.deployment(dep.id)).url ?? "§"));
  const server = await api.deployment(dep.id);
  check.ok("the server agrees: RUNNING, mock=false, provider static, finishedAt set", server.status === "RUNNING" && server.mock === false && server.provider === "static" && !!server.finishedAt, `status=${server.status}`, "persistence");
  const after = await until(() => api.site(), (s) => s.currentDeploymentId === dep.id && s.operation === null, 15_000);
  check.ok("SiteInfo: online, the new deployment is current, operation = null (idle)", after.online === true && after.currentDeploymentId === dep.id && after.operation === null, JSON.stringify({ cur: after.currentDeploymentId, op: after.operation }), "http");
  check.ok("pointerVersion moved by exactly +1 with the pointer", after.pointerVersion === before.pointerVersion + 1, `${before.pointerVersion} → ${after.pointerVersion}`, "persistence");
  // reopen the dialog: pointerVersion is displayed for observability
  await closeRelease(page);
  await showRelease(page);
  const pv = await page.getByTestId("pointer-version").innerText({ timeout: 10_000 }).catch(() => "");
  check.ok("pointerVersion is shown to the user as observability only", pv.includes(String(after.pointerVersion)) && /không gửi lại/.test(pv), pv);
  check.ok("the browser NEVER sent pointerVersion, tenantId or an invented concurrency field on any release route", forbiddenSent(log).length === 0, JSON.stringify(forbiddenSent(log)).slice(0, 200));
  check.ok("polling stopped: no deployment status request after the terminal answer for 3 s", await (async () => { const n = log.filter((x) => /\/deployments\//.test(x.path)).length; await page.waitForTimeout(3000); return log.filter((x) => /\/deployments\//.test(x.path)).length === n; })());
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
