// @class: real-backend — C2 contract §2: rollback from Studio. POST …/site/rollback {deploymentId, expectedActiveDeploymentId} (optional Idempotency-Key) is SYNCHRONOUS: 200 + SiteInfo, and the site then serves the
// restored release. The release that was rolled away from becomes ROLLED_BACK (terminal, not restorable). Requires APP_PUBLISH (a project VIEWER forging the call gets 403). pointerVersion +1, never sent.
import { newPage, pageProblems } from "../lib/ui.mjs";
import { releaseApi, baseline, openRelease, captureRelease, forbiddenSent, KEY_RE, until } from "../lib/release.mjs";
export const id = "E2E-P03", title = "Rollback success: exact request, 200 + SiteInfo, rolled-away release is ROLLED_BACK, APP_PUBLISH, pointerVersion";
export async function run({ cfg, fx, browser, check }) {
  const api = releaseApi(fx); const { a, b, c } = await baseline(fx, api);
  const before = await api.site();
  check.ok("(setup) three RUNNING releases, the newest is active", before.currentDeploymentId === c.id && [a, b, c].every((d) => d.status === "RUNNING"), `${before.currentDeploymentId} vs ${c.id}`, "http");
  const page = await newPage(browser); const log = captureRelease(page);
  await openRelease(page, cfg, fx.users.adminA, fx.projects.A.id);
  await page.getByTestId(`release:${c.id}`).waitFor({ timeout: 15_000 }).catch(() => undefined);
  check.ok("the older releases are offered for rollback; the one being served is not", (await page.getByTestId(`rollback:${a.id}`).count()) === 1 && (await page.getByTestId(`rollback:${b.id}`).count()) === 1 && (await page.getByTestId(`rollback:${c.id}`).count()) === 0);
  const resp = page.waitForResponse((r) => r.request().method() === "POST" && /\/site\/rollback$/.test(new URL(r.url()).pathname), { timeout: 30_000 });
  await page.getByTestId(`rollback:${b.id}`).click();
  check.ok("pending state is shown while the request is in flight (the button says it is working)", /Đang hoàn tác|Phục vụ lại/.test(await page.getByTestId(`release:${b.id}`).innerText().catch(() => "x")));
  const r = await resp; const req = r.request(), body = JSON.parse(req.postData() ?? "{}");
  check.ok("POST …/site/rollback answered 200 (synchronous)", r.status() === 200, `status=${r.status()}`, "http");
  check.ok("the body is {deploymentId, expectedActiveDeploymentId} — the target and the release the UI saw as active — and nothing else", JSON.stringify(Object.keys(body).sort()) === JSON.stringify(["deploymentId", "expectedActiveDeploymentId"]) && body.deploymentId === b.id && body.expectedActiveDeploymentId === c.id, JSON.stringify(body), "http");
  check.ok("Idempotency-Key (optional) is sent in the contract format; CSRF header present", KEY_RE.test(req.headers()["idempotency-key"] ?? "") && !!req.headers()["x-xsrf-token"], req.headers()["idempotency-key"], "http");
  const info = await r.json();
  check.ok("the answer is a SiteInfo whose current deployment is the target and whose operation is idle", info.currentDeploymentId === b.id && info.operation === null && info.online === true, JSON.stringify({ cur: info.currentDeploymentId, op: info.operation }), "http");
  const note = await page.getByTestId("release-note").innerText({ timeout: 10_000 }).catch(() => "");
  check.ok("the UI says the release is served again only AFTER the 200 (final message names the version)", /Đã phục vụ lại phiên bản/.test(note), note);
  const site = await until(() => api.site(), (s) => s.currentDeploymentId === b.id, 10_000);
  check.ok("server: the site serves the target and pointerVersion moved by exactly +1", site.currentDeploymentId === b.id && site.pointerVersion === before.pointerVersion + 1, `${before.pointerVersion} → ${site.pointerVersion}`, "persistence");
  const list = await api.deployments(); const byId = Object.fromEntries(list.map((d) => [d.id, d.status]));
  check.ok("server: the release that was rolled away from is ROLLED_BACK (terminal); the others stay RUNNING", byId[c.id] === "ROLLED_BACK" && byId[a.id] === "RUNNING" && byId[b.id] === "RUNNING", JSON.stringify(byId), "persistence");
  check.ok("a ROLLED_BACK release is not restorable: the server answers 400 DEPLOYMENT_NOT_RESTORABLE", (await api.rollback({ deploymentId: c.id })).body?.code === "DEPLOYMENT_NOT_RESTORABLE", "", "http");
  await page.getByTestId("release-modal").locator("summary").first().click().catch(() => undefined);
  await page.waitForTimeout(800);
  check.ok("the UI no longer offers the ROLLED_BACK release", (await page.getByTestId(`rollback:${c.id}`).count()) === 0);
  check.ok("the browser NEVER sent pointerVersion or an invented field", forbiddenSent(log).length === 0, JSON.stringify(forbiddenSent(log)).slice(0, 160));
  // APP_PUBLISH: a person without it is refused by the SERVER (the UI state is UX only)
  const viewer = fx.sessions.viewerA, forged = await viewer.post(`${api.base}/site/rollback`, { deploymentId: a.id });
  check.ok("permission: a project VIEWER forging the rollback is refused (403) — rollback needs APP_PUBLISH, not APP_EDIT", forged.status === 403, `status=${forged.status} ${forged.body?.code}`, "http");
  const forgedUn = await viewer.del(`${api.base}/site`); check.ok("permission: …and the same for unpublish (403)", forgedUn.status === 403, `status=${forgedUn.status}`, "http");
  check.ok("…which changed nothing", (await api.site()).currentDeploymentId === b.id, "", "persistence");
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
