// @class: harness — real Chromium on the release dialog with an in-page FAKE of its six calls (no backend). It proves how the dialog treats the answers the C2 contract describes: statuses (ROLLING_BACK busy, never success),
// STALE_PUBLISH as a FAILED deployment, SiteInfo.operation busy states, 409 SCOPE_BUSY / ROLLBACK_STALE / IDEMPOTENCY_KEY_REUSED, key lifecycle, APP_PUBLISH. NOT a backend E2E: see tests/e2e-real E2E-P01…P09.
// Run: node tests/browser/build-harness.mjs && CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/release.spec.mjs
import { harnessPage, launch, makeChecks } from "./lib/spec.mjs";
const BASE = harnessPage("release.html");
const { check, finish } = makeChecks();
const allErrors = [];
const browser = await launch();
async function open(s = "ok") {
  const p = await browser.newPage({ viewport: { width: 1200, height: 900 } });
  p.on("pageerror", (e) => allErrors.push(`pageerror: ${e.message}`)); p.on("console", (m) => { if (["error", "warning"].includes(m.type()) && !/favicon|404/.test(m.text())) allErrors.push(`${m.type()}: ${m.text()}`); });
  await p.goto(`${BASE}?s=${s}`); await p.getByTestId("release-modal").waitFor(); await p.waitForTimeout(400);
  return p;
}
const set = (p, patch) => p.evaluate((x) => window.__rel.set(x), patch);
const calls = (p, name) => p.evaluate((n) => window.__rel.calls.filter((c) => !n || c.name === n), name);
const T = (p, id) => p.getByTestId(id);
// M-019: rollback and unpublish ask first (shared confirm dialog, `.adminModal` portalled to body); answer it
const ask = (p) => p.locator(".adminModal");   // the shared Modal portals to <body>
const yes = async (p, name) => { await ask(p).getByRole("button", { name }).waitFor(); await ask(p).getByRole("button", { name }).click(); };
const no = async (p) => { await ask(p).getByRole("button", { name: "Hủy" }).waitFor(); await ask(p).getByRole("button", { name: "Hủy" }).click(); };
const rollbackTo = async (p, id) => { await T(p, id).click(); await yes(p, "Phục vụ lại bản này"); };
const dep = (id, v, status, extra = {}) => ({ id, projectId: "p1", versionId: `ver-${v}`, versionNumber: v, visibility: "PRIVATE", status, url: status === "RUNNING" ? "https://sites.example.test/s/n/" : null, error: null, provider: "static", mock: false, createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-07T00:00:00Z", finishedAt: null, events: [{ status: "QUEUED", message: null, createdAt: "2026-10-07T00:00:00Z" }], ...extra });
const op = (kind, deploymentId = null) => ({ kind, deploymentId, since: "2026-10-07T00:00:00Z", leaseUntil: "2099-10-07T00:01:30Z" });
const siteWith = async (p, operation) => set(p, { site: { slug: "demo", url: "https://sites.example.test/demo/", online: true, visibility: "PRIVATE", currentDeploymentId: "d3", currentVersionNumber: 3, provider: "static", updatedAt: "x", pointerVersion: 5, operation } });

// ---- idle ---------------------------------------------------------------------------------------------------------------------------------------------------
{ const p = await open();
  check("idle (operation = null): Publish, rollback and unpublish are enabled, no busy banner", (await T(p, "publish").isEnabled()) && (await T(p, "rollback:d2").isEnabled()) && (await T(p, "unpublish").isEnabled()) && (await T(p, "release-operation").count()) === 0);
  check("pointerVersion is displayed for observability, labelled as not sent back", /pointerVersion 5/.test(await T(p, "pointer-version").innerText()) && /không gửi lại/.test(await T(p, "pointer-version").innerText()));
  check("the release being served is marked, a ROLLED_BACK release is not offered", /Đang phục vụ/.test(await T(p, "release:d3").innerText()) && (await T(p, "release:d0").count()) === 0 && (await T(p, "rollback:d3").count()) === 0);
  await p.close(); }

// ---- SiteInfo.operation: busy for each kind ------------------------------------------------------------------------------------------------------------------
for (const [kind, text] of [["PUBLISH", /đang xuất bản/], ["ROLLBACK", /đang hoàn tác/], ["UNPUBLISH", /đang gỡ trang xuống/]]) {
  const p = await open(); await siteWith(p, op(kind, kind === "PUBLISH" ? "n1" : null)); await T(p, "release-operation").waitFor({ timeout: 5000 }).catch(() => undefined);   // noticed by the idle refresh (8 s)
  const banner = T(p, "release-operation");
  check(`operation=${kind}: the dialog mirrors it (banner kind ${kind}, text) and locks Publish / rollback / unpublish`, (await banner.count()) === 1 && (await banner.getAttribute("data-kind")) === kind && text.test(await banner.innerText()) && (await T(p, "publish").isDisabled()) && (await T(p, "rollback:d2").isDisabled()) && (await T(p, "unpublish").isDisabled()));
  const nBefore = (await calls(p, "site")).length; await p.waitForTimeout(2000);
  check(`operation=${kind}: it keeps polling SiteInfo while busy (bounded interval)`, (await calls(p, "site")).length > nBefore && (await calls(p, "site")).length - nBefore <= 8, `${(await calls(p, "site")).length - nBefore} site calls in 2 s (interval 400 ms in the harness)`);
  await set(p, { site: { slug: "demo", url: "https://sites.example.test/demo/", online: true, visibility: "PRIVATE", currentDeploymentId: "d3", currentVersionNumber: 3, provider: "static", updatedAt: "x", pointerVersion: 6, operation: null } });
  await p.waitForTimeout(1500);
  check(`operation=${kind} → null: the banner goes away by itself, controls are back, no request was sent for the locked controls`, (await banner.count()) === 0 && (await T(p, "publish").isEnabled()) && (await calls(p, "publish")).length === 0 && (await calls(p, "rollback")).length === 0);
  await p.close(); }
{ const p = await open(); await siteWith(p, op("ROLLBACK")); await T(p, "release-operation").waitFor({ timeout: 5000 }).catch(() => undefined);
  for (const id of ["publish", "rollback:d2", "unpublish"]) await T(p, id).click({ force: true, timeout: 1500 }).catch(() => undefined);
  await p.waitForTimeout(300);
  check("while an operation is shown, forcing clicks sends NOTHING (no duplicate operation)", (await calls(p)).filter((c) => ["publish", "rollback", "unpublish"].includes(c.name)).length === 0);
  await p.close(); }

// ---- statuses: ROLLING_BACK is busy, never success -----------------------------------------------------------------------------------------------------------
{ const p = await open();
  await set(p, { deployment: dep("n1", 4, "QUEUED") }); await T(p, "publish").click(); await T(p, "deployment").waitFor();
  const seq = [["POLICY_CHECK", "Kiểm tra chính sách"], ["BUILDING", "Đang build"], ["DEPLOYING", "Đang triển khai"], ["ROLLING_BACK", "Đang hoàn tác"]];
  for (const [st, label] of seq) { await set(p, { deployment: dep("n1", 4, st, { events: [{ status: st, message: null, createdAt: "x" }] }) }); await p.waitForFunction((s) => document.querySelector('[data-testid="deployment"]')?.getAttribute("data-status") === s, st, { timeout: 5000 }); check(`status ${st}: shown as "${label}", busy: no success text, dialog cannot be closed, no second publish`, (await T(p, "deployment-status").innerText()).includes(label) && (await T(p, "deployment-success").count()) === 0 && (await p.getByRole("button", { name: "Đang xử lý…" }).isDisabled()) && (await p.getByRole("button", { name: "Đóng" }).isDisabled())); }
  check("ROLLING_BACK says it is NOT success yet and that the final state will be 'Thất bại'", /chưa phải thành công/.test(await T(p, "deployment-rolling-back").innerText()));
  const polls = (await calls(p, "getDeployment")).length; await p.waitForTimeout(2600);
  check("ROLLING_BACK keeps polling (non-terminal)", (await calls(p, "getDeployment")).length > polls);
  await set(p, { deployment: dep("n1", 4, "FAILED", { error: "[DEPLOY_FAILED] switch failed, previous release restored", finishedAt: "x" }) }); await T(p, "deployment-failed").waitFor({ timeout: 6000 });
  check("ROLLING_BACK → FAILED: failure shown with its reason, never success; polling stops", (await T(p, "deployment-failed").getAttribute("data-code")) === "DEPLOY_FAILED" && /previous release restored/.test(await T(p, "deployment-failed").innerText()) && (await T(p, "deployment-success").count()) === 0);
  const n = (await calls(p, "getDeployment")).length; await p.waitForTimeout(3000);
  check("terminal FAILED: no more status requests", (await calls(p, "getDeployment")).length === n);
  await p.close(); }
{ const p = await open();
  await set(p, { deployment: dep("n1", 4, "RUNNING", { finishedAt: "x" }) }); await T(p, "publish").click(); await T(p, "deployment-success").waitFor();
  check("RUNNING is the only success (URL shown, polling not even started)", (await T(p, "deployment").getAttribute("data-status")) === "RUNNING" && (await calls(p, "getDeployment")).length === 0);
  await p.close(); }
{ const p = await open();
  await set(p, { deployment: dep("n1", 4, "ROLLED_BACK", { finishedAt: "x" }) }); await T(p, "publish").click(); await T(p, "deployment-rolled-back").waitFor();
  check("ROLLED_BACK: terminal, neutral message (not success, not an error), 'publish again' offered", (await T(p, "deployment-success").count()) === 0 && /không thể phục vụ lại/.test(await T(p, "deployment-rolled-back").innerText()) && (await T(p, "publish-again").count()) === 1);
  await p.close(); }

// ---- STALE_PUBLISH is a FAILED deployment ---------------------------------------------------------------------------------------------------------------------
{ const p = await open();
  await set(p, { deployment: dep("n1", 4, "FAILED", { error: "[STALE_PUBLISH] a newer release operation already moved the active release", finishedAt: "x", events: [{ status: "BUILDING", message: null, createdAt: "x" }, { status: "FAILED", message: null, createdAt: "x" }, { status: "STALE_PUBLISH", message: null, createdAt: "x" }] }) });
  await T(p, "publish").click(); await T(p, "deployment-failed").waitFor();
  check("STALE_PUBLISH: a failed DEPLOYMENT with its own title (data-code), the server's text, no error dialog, no success", (await T(p, "deployment-failed").getAttribute("data-code")) === "STALE_PUBLISH" && /cũ hơn bản đang chạy/.test(await T(p, "deployment-failed").innerText()) && /already moved the active release/.test(await T(p, "deployment-failed").innerText()) && (await T(p, "release-error").count()) === 0 && (await T(p, "deployment-success").count()) === 0);
  check("STALE_PUBLISH: the event is listed in plain words and 'publish again' is offered", (await T(p, "deployment").locator("li").allInnerTexts()).some((t) => /đã cũ hơn bản đang chạy/.test(t)) && (await T(p, "publish-again").count()) === 1);
  const k1 = (await calls(p, "publish"))[0].args[2];
  await T(p, "publish-again").click(); await set(p, { deployment: null }); await T(p, "publish").click(); await p.waitForTimeout(400);
  const k2 = (await calls(p, "publish"))[1]?.args[2];
  check("publish again after the failure uses a NEW Idempotency-Key (the old one would only return the failed deployment)", !!k2 && k1 !== k2, `${k1} → ${k2}`);
  await p.close(); }

// ---- publish request, keys ----------------------------------------------------------------------------------------------------------------------------------------
{ const p = await open();
  await set(p, { deployment: dep("n1", 4, "QUEUED") });
  await p.getByRole("radio", { name: /Công khai/ }).check(); await p.getByRole("radio", { name: /Riêng tư/ }).check();
  await T(p, "publish").dblclick(); await p.waitForTimeout(500);
  const c = await calls(p, "publish");
  check("a double click sends ONE publish (locked while submitting)", c.length === 1, `${c.length}`);
  check("the request is (visibility, expectedRevision, key) with a contract-format key", c[0].args[0] === "PRIVATE" && c[0].args[1] === 7 && /^[A-Za-z0-9_.:-]{8,120}$/.test(c[0].args[2]));
  await p.close(); }
{ const p = await open();
  await set(p, { errors: { publish: { status: 0, code: "NETWORK" } }, deployment: dep("n1", 4, "QUEUED") });
  await T(p, "publish").click(); await T(p, "release-error").waitFor();
  check("a lost answer (network): 'unknown outcome', retry offered with the SAME payload", (await T(p, "release-error").getAttribute("data-kind")) === "unreachable" && /Chưa rõ/.test(await T(p, "release-error").innerText()) && (await T(p, "release-retry").count()) === 1);
  await T(p, "release-retry").click(); await p.waitForTimeout(500);
  const c = await calls(p, "publish");
  check("the retry reuses the SAME Idempotency-Key (replay-safe: it can never create a second deployment)", c.length === 2 && c[0].args[2] === c[1].args[2], JSON.stringify(c.map((x) => x.args[2])));
  await p.close(); }
{ const p = await open();
  await set(p, { errors: { publish: { status: 409, code: "IDEMPOTENCY_KEY_REUSED" } } });
  await T(p, "publish").click(); await T(p, "release-error").waitFor();
  check("409 IDEMPOTENCY_KEY_REUSED: shown plainly, NO retry button (a new key is required)", (await T(p, "release-error").getAttribute("data-kind")) === "key-reused" && (await T(p, "release-retry").count()) === 0);
  await set(p, { deployment: dep("n1", 4, "QUEUED") }); await T(p, "publish").click(); await p.waitForTimeout(400);
  const c = await calls(p, "publish");
  check("the next attempt uses a NEW key", c.length === 2 && c[0].args[2] !== c[1].args[2]);
  await p.close(); }
{ const p = await open();
  await set(p, { errors: { publish: { status: 409, code: "REVISION_CONFLICT" } } });
  await T(p, "publish").click(); await T(p, "release-error").waitFor();
  check("409 REVISION_CONFLICT: 'the app changed' with a reload action, no blind retry", (await T(p, "release-error").getAttribute("data-kind")) === "revision" && (await T(p, "release-retry").count()) === 0 && (await T(p, "release-reload").count()) === 1);
  await p.close(); }

// ---- rollback ----------------------------------------------------------------------------------------------------------------------------------------------
{ const p = await open();
  await set(p, { hold: { rollback: true } });
  await rollbackTo(p, "rollback:d2"); await p.waitForTimeout(400);
  check("rollback pending: the button says it is working, every release control is locked, the dialog cannot be closed", /Đang hoàn tác/.test(await T(p, "rollback:d2").innerText()) && (await T(p, "publish").isDisabled()) && (await T(p, "unpublish").isDisabled()) && (await T(p, "rollback:d1").isDisabled()) && (await p.getByRole("button", { name: "Hủy" }).isDisabled()));
  check("rollback pending: NOT shown as done (no success note yet)", (await T(p, "release-note").count()) === 0);
  await T(p, "rollback:d1").click({ force: true, timeout: 1000 }).catch(() => undefined);
  check("a second click while one is in flight sends nothing", (await calls(p, "rollback")).length === 1);
  await p.evaluate(() => window.__rel.release()); await T(p, "release-note").waitFor({ timeout: 5000 });
  const c = (await calls(p, "rollback"))[0];
  check("rollback request: {deploymentId: target, expectedActiveDeploymentId: the release the dialog saw as active}, a contract-format key, nothing else", JSON.stringify(Object.keys(c.args[0]).sort()) === JSON.stringify(["deploymentId", "expectedActiveDeploymentId"]) && c.args[0].deploymentId === "d2" && c.args[0].expectedActiveDeploymentId === "d3" && /^[A-Za-z0-9_.:-]{8,120}$/.test(c.args[1]), JSON.stringify(c.args));
  check("only after the 200 + a SiteInfo that serves the target is it reported as done (names the version)", /Đã phục vụ lại phiên bản 2/.test(await T(p, "release-note").innerText()));
  check("pointerVersion shown moved (5 → 6) and the control set follows the new state", /pointerVersion 6/.test(await T(p, "pointer-version").innerText()) && (await T(p, "rollback:d3").count()) === 1);
  await p.close(); }
{ const p = await open();
  await set(p, { errors: { rollback: { status: 409, code: "SCOPE_BUSY", details: { appId: "a", environment: "PRODUCTION", operation: { kind: "PUBLISH", deploymentId: "n1", since: "x", leaseUntil: "y" } }, retryAfter: 5 } } });
  await rollbackTo(p, "rollback:d2"); const err = T(p, "release-error"); await err.waitFor();
  check("409 SCOPE_BUSY: names the running operation and the 5 s wait; retry exists but is disabled until Retry-After elapsed", (await err.getAttribute("data-kind")) === "scope-busy" && /đang xuất bản/.test(await err.innerText()) && /5 giây/.test(await err.innerText()) && (await T(p, "release-retry").isDisabled()) && /Thử lại sau [1-5]s/.test(await T(p, "release-retry").innerText()));
  await p.waitForFunction(() => { const b = document.querySelector('[data-testid="release-retry"]'); return b && !b.disabled; }, null, { timeout: 8000 });
  await T(p, "release-retry").click(); await T(p, "release-note").waitFor({ timeout: 5000 });
  const rb = await calls(p, "rollback");
  check("after Retry-After the retry is enabled and succeeds; it replays the SAME key (same logical request)", rb.length === 2 && rb[0].args[1] === rb[1].args[1] && /Đã phục vụ lại/.test(await T(p, "release-note").innerText()), JSON.stringify(rb.map((x) => x.args[1])));
  await p.close(); }
{ const p = await open();
  await set(p, { errors: { rollback: { status: 409, code: "ROLLBACK_STALE", details: { activeDeploymentId: "d2", expectedActiveDeploymentId: "d3" } } } });
  const siteCalls = (await calls(p, "site")).length;
  await rollbackTo(p, "rollback:d1"); const err = T(p, "release-error"); await err.waitFor();
  check("409 ROLLBACK_STALE: 'the active release changed', says nothing was changed, NO blind retry, a reload action", (await err.getAttribute("data-kind")) === "stale" && /Không có gì bị thay đổi/.test(await err.innerText()) && (await T(p, "release-retry").count()) === 0 && (await T(p, "release-reload").count()) === 1);
  await p.waitForTimeout(500);
  check("…and the dialog reloads SiteInfo by itself so the next decision is made on fresh state", (await calls(p, "site")).length > siteCalls);
  await set(p, { site: { slug: "demo", url: "https://sites.example.test/demo/", online: true, visibility: "PRIVATE", currentDeploymentId: "d2", currentVersionNumber: 2, provider: "static", updatedAt: "x", pointerVersion: 6, operation: null } });
  await T(p, "release-reload").click(); await p.waitForTimeout(500);
  await rollbackTo(p, "rollback:d1"); await T(p, "release-note").waitFor({ timeout: 5000 });
  const rb = await calls(p, "rollback");
  check("the decision on fresh state carries the NEW expectation and a NEW key", rb.length === 2 && rb[1].args[0].expectedActiveDeploymentId === "d2" && rb[0].args[1] !== rb[1].args[1], JSON.stringify(rb.map((x) => [x.args[0].expectedActiveDeploymentId, x.args[1]])));
  await p.close(); }
{ const p = await open();
  await set(p, { errors: { rollback: { status: 400, code: "DEPLOYMENT_NOT_RESTORABLE" } } });
  await rollbackTo(p, "rollback:d2"); await T(p, "release-error").waitFor();
  check("400 DEPLOYMENT_NOT_RESTORABLE: explained, not retried", (await T(p, "release-error").getAttribute("data-kind")) === "not-restorable" && (await T(p, "release-retry").count()) === 0);
  await p.close(); }
{ const p = await open();
  await set(p, { errors: { rollback: { status: 409, code: "ROLLBACK_FAILED", message: "The release could not be restored: artifact missing" } } });
  await rollbackTo(p, "rollback:d2"); await T(p, "release-error").waitFor();
  check("409 ROLLBACK_FAILED: the server's reason is shown, 'nothing changed', no automatic retry", (await T(p, "release-error").getAttribute("data-kind")) === "rollback-failed" && /artifact missing/.test(await T(p, "release-error").innerText()) && (await calls(p, "rollback")).length === 1);
  await p.close(); }
{ // unknown outcome: the answer is lost, but the server did the rollback: the dialog asks and reports what is true
  const p = await open();
  await set(p, { errors: { rollback: { status: 0, code: "NETWORK" } } });
  await set(p, { site: { slug: "demo", url: "https://sites.example.test/demo/", online: true, visibility: "PRIVATE", currentDeploymentId: "d2", currentVersionNumber: 2, provider: "static", updatedAt: "x", pointerVersion: 6, operation: null } });
  await rollbackTo(p, "rollback:d2"); await p.waitForTimeout(800);
  check("lost answer + the server did apply it: the dialog reconciles from SiteInfo and says so (never a bare failure)", /đã hoàn tất trên máy chủ/.test(await T(p, "release-note").innerText().catch(() => "")) && (await T(p, "release-error").count()) === 0);
  await p.close(); }
// ---- unpublish ---------------------------------------------------------------------------------------------------------------------------------------------
{ const p = await open(); await T(p, "unpublish").click(); await yes(p, "Gỡ trang xuống"); await T(p, "release-note").waitFor({ timeout: 5000 });
  const c = (await calls(p, "unpublish"))[0];
  check("unpublish: the expectation is the release the dialog saw as active, a contract-format key; the answer is reported after the 200", c.args[0] === "d3" && /^[A-Za-z0-9_.:-]{8,120}$/.test(c.args[1]) && /đã được gỡ xuống/.test(await T(p, "release-note").innerText()));
  check("after unpublish the dialog shows the site as offline and offers no unpublish", (await T(p, "unpublish").count()) === 0 && /Trang đang được gỡ xuống/.test(await T(p, "site-box").innerText()));
  await p.close(); }
{ const p = await open();
  await T(p, "unpublish").click(); await no(p); await p.waitForTimeout(300);
  check("unpublish needs confirmation: dismissing it sends nothing", (await calls(p, "unpublish")).length === 0);
  await p.close(); }
{ const p = await open();
  await set(p, { errors: { unpublish: { status: 409, code: "SCOPE_BUSY", retryAfter: 5, details: { operation: { kind: "ROLLBACK" } } } } }); await T(p, "unpublish").click(); await yes(p, "Gỡ trang xuống"); await T(p, "release-error").waitFor();
  check("unpublish 409 SCOPE_BUSY: the same explained error with a retry that waits", (await T(p, "release-error").getAttribute("data-kind")) === "scope-busy" && /đang hoàn tác/.test(await T(p, "release-error").innerText()) && (await T(p, "release-retry").count()) === 1);
  await p.close(); }

// ---- APP_PUBLISH ------------------------------------------------------------------------------------------------------------------------------------------
{ const p = await open("noperm");
  check("without APP_PUBLISH: a clear read-only note; Publish, rollback and unpublish are disabled with the reason", (await T(p, "release-no-permission").count()) === 1 && /APP_PUBLISH/.test(await T(p, "release-no-permission").innerText()) && (await T(p, "publish").isDisabled()) && (await T(p, "rollback:d2").isDisabled()) && (await T(p, "unpublish").isDisabled()) && /APP_PUBLISH/.test((await T(p, "rollback:d2").locator("xpath=following-sibling::small").innerText().catch(() => ""))));
  for (const id of ["publish", "rollback:d2", "unpublish"]) await T(p, id).click({ force: true, timeout: 1000 }).catch(() => undefined);
  check("without APP_PUBLISH: forcing the controls sends NOTHING (the server would answer 403 anyway)", (await calls(p)).filter((c) => ["publish", "rollback", "unpublish"].includes(c.name)).length === 0);
  await p.close(); }

// ---- reconnect: failed SiteInfo polling -----------------------------------------------------------------------------------------------------------------------
{ const p = await open(); await siteWith(p, op("PUBLISH", "n1")); await T(p, "release-operation").waitFor({ timeout: 5000 }).catch(() => undefined);
  await set(p, { errors: { site: { status: 0, code: "NETWORK", sticky: true } } }); await T(p, "site-reconnect").waitFor({ timeout: 15000 }).catch(() => undefined);
  check("SiteInfo polling that keeps failing stops and offers a manual reconnect (no endless retry storm)", (await T(p, "site-reconnect").count()) === 1);
  const n = (await calls(p, "site")).length; await p.waitForTimeout(3000);
  check("…and it really stopped polling", (await calls(p, "site")).length === n);
  await p.close(); }

// ---- M-019: switching the LIVE site asks first ----------------------------------------------------------------------------------------------------------------------
{ const p = await open();
  await T(p, "rollback:d2").click(); await p.waitForTimeout(300);
  const dlg = ask(p);
  const txt = await dlg.innerText().catch(() => "");
  check("M-019: 'Phục vụ lại bản này' opens a confirmation that names BOTH versions and says it is immediate", /phiên bản 2/.test(txt) && /phiên bản 3/.test(txt) && /ngay lập tức/.test(txt), txt.replace(/\n/g, " "));
  check("M-019: ...and sends NOTHING until it is confirmed", (await calls(p, "rollback")).length === 0);
  check("M-019: the destructive confirmation starts on 'Hủy' (a stray Enter must not switch the site)", await p.evaluate(() => document.activeElement?.textContent?.trim()) === "Hủy");
  await p.keyboard.press("Escape"); await p.waitForTimeout(300);
  check("M-019: Escape cancels: no request, the release dialog is still open", (await calls(p, "rollback")).length === 0 && (await T(p, "release-modal").count()) === 1);
  await rollbackTo(p, "rollback:d2"); await p.waitForTimeout(600);
  check("M-019: confirming sends exactly ONE rollback request", (await calls(p, "rollback")).length === 1);
  await p.close(); }

check("no console errors, warnings or uncaught exceptions in any scenario (React warnings included)", allErrors.length === 0, allErrors.slice(0, 3).join(" | "));
await browser.close();
finish();
