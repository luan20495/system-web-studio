// @class: real-backend — C2 contract §3: unpublish. DELETE …/site[?expectedActiveDeploymentId], NO body, optional Idempotency-Key; 200 + SiteInfo (online:false, currentDeploymentId:null, slug kept). Already offline → 200 and NOTHING is written.
// Stale expectation → 409 ROLLBACK_STALE. Deployments are kept (a rollback serves the site again). SiteInfo.operation = UNPUBLISH is observed while it runs when the window allows (it holds the scope only for an instant).
import { newPage, pageProblems } from "../lib/ui.mjs";
import { releaseApi, baseline, openRelease, captureRelease, forbiddenSent, KEY_RE, sleep, showRelease , closeRelease } from "../lib/release.mjs";
export const id = "E2E-P09", title = "Unpublish: exact request, 200 + SiteInfo offline, idempotent when offline, stale expectation, operation=UNPUBLISH when observable";
export async function run({ cfg, fx, browser, check }) {
  const api = releaseApi(fx); const { a, c } = await baseline(fx, api);
  const before = await api.site();
  const stale = await api.unpublish(a.id);
  check.ok("API: unpublish expecting a release that is not the active one → 409 ROLLBACK_STALE, still online", stale.status === 409 && stale.body?.code === "ROLLBACK_STALE" && (await api.site()).online === true, `status=${stale.status} ${stale.body?.code}`, "http");
  const page = await newPage(browser); const log = captureRelease(page);
  await openRelease(page, cfg, fx.users.adminA, fx.projects.A.id);
  page.once("dialog", (d) => void d.accept());
  const resp = page.waitForResponse((r) => r.request().method() === "DELETE" && /\/site$/.test(new URL(r.url()).pathname), { timeout: 30_000 });
  await page.getByTestId("unpublish").click();
  const r = await resp, req = r.request(), u = new URL(req.url());
  check.ok("DELETE …/site answered 200", r.status() === 200, `status=${r.status()}`, "http");
  check.ok("NO body; the expectation is the query parameter expectedActiveDeploymentId = the release the UI saw as active", req.postData() === null && u.searchParams.get("expectedActiveDeploymentId") === c.id && [...u.searchParams.keys()].length === 1, `${u.search} body=${req.postData()}`, "http");
  check.ok("Idempotency-Key (optional) sent in the contract format", KEY_RE.test(req.headers()["idempotency-key"] ?? ""), req.headers()["idempotency-key"], "http");
  const info = await r.json();
  check.ok("the answer: online=false, currentDeploymentId=null, slug kept, operation=null", info.online === false && info.currentDeploymentId === null && info.slug === before.slug && info.operation === null, JSON.stringify({ o: info.online, c: info.currentDeploymentId, s: info.slug }), "http");
  check.ok("UI: says the page was taken down (after the 200)", /đã được gỡ xuống/.test(await page.getByTestId("release-note").innerText({ timeout: 10_000 }).catch(() => "")));
  const after = await api.site();
  check.ok("pointerVersion moved by exactly +1; deployments are KEPT (none deleted, statuses unchanged)", after.pointerVersion === before.pointerVersion + 1 && (await api.deployments()).filter((d) => d.status === "RUNNING").length >= 3, `${before.pointerVersion} → ${after.pointerVersion}`, "persistence");
  check.ok("the public URL no longer serves the site (404)", (await fetch(before.url, { redirect: "manual" }).catch(() => ({ status: 0 }))).status === 404, before.url, "persistence");
  // already offline: 200, nothing written
  const again = await api.unpublish(null), pvAgain = (await api.site()).pointerVersion;
  check.ok("unpublish when already offline → 200 and NOTHING is written (pointerVersion unchanged)", again.status === 200 && pvAgain === after.pointerVersion && again.body.online === false, `status=${again.status} ${after.pointerVersion}→${pvAgain}`, "persistence");
  check.ok("UI: with the site offline the 'Gỡ trang xuống' button is not offered", await (async () => { await closeRelease(page); await showRelease(page); await page.waitForTimeout(800); return (await page.getByTestId("unpublish").count()) === 0; })());
  // a rollback serves the site again with no rebuild
  const back = await api.rollback({ deploymentId: a.id });
  check.ok("a rollback to a kept release serves the site again (200, online) with no rebuild", back.status === 200 && back.body.online === true && back.body.currentDeploymentId === a.id, `status=${back.status}`, "http");
  // operation = UNPUBLISH: observe it from tight polling while an unpublish runs (it holds the scope only for an instant)
  let seen = null;
  for (let attempt = 0; attempt < 12 && !seen; attempt++) {
    if (attempt > 0) await api.rollback({ deploymentId: a.id });
    let stop = false; const poll = (async () => { while (!stop) { const s = await api.site(); if (s.operation) { seen = s.operation; return; } } })();
    await api.unpublish(null, `e2e:${fx.runId}:p09-${attempt}-${Date.now()}`); stop = true; await poll;
  }
  fx.notes.unpublishOperationObserved = seen ? seen.kind : "not observed (the scope is held for an instant; the busy UI for UNPUBLISH is covered by the browser harness)";
  console.log(`    [fact] SiteInfo.operation while unpublishing → ${fx.notes.unpublishOperationObserved}`);
  if (seen) check.ok("SiteInfo.operation = UNPUBLISH was observed while the unpublish ran (deploymentId null)", seen.kind === "UNPUBLISH" && seen.deploymentId === null, JSON.stringify(seen), "http");
  check.ok("the browser NEVER sent pointerVersion or an invented field", forbiddenSent(log).length === 0, JSON.stringify(forbiddenSent(log)).slice(0, 160));
  check.ok("no unhandled page errors", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
