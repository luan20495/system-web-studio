// @class: real-backend — shared steps of the publish / rollback / unpublish flows (C2 contract: docs/parallel/c2/PUBLISH_API_CONTRACT.md @ fix/c2-v3 8d40218). API calls go through the product API with a real session;
// fault injection (E2E_PAUSE_STORE_CMD / E2E_PAUSE_RENDER_CMD) PAUSES the artifact store or the render worker so a release stays in a known step; nothing is stubbed or intercepted.
import { exec } from "node:child_process";
import { promisify } from "node:util";
import { Blocked } from "./report.mjs";
import { loginUi, openBuilder, boundedRetry } from "./ui.mjs";
const sh = promisify(exec);
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export const TERMINAL = ["RUNNING", "FAILED", "ROLLED_BACK"];
export const KEY_RE = /^[A-Za-z0-9_.:-]{8,120}$/;

export function releaseApi(fx, who = "adminA") {
  const S = fx.sessions[who], w = fx.workspaces.A, p = fx.projects.A.id, base = `/workspaces/${w}/projects/${p}`;
  const api = {
    S, base, w, p,
    revision: async () => (await S.get(base)).body.revision,
    site: async () => (await S.get(`${base}/site`)).body,
    deployments: async () => (await S.get(`${base}/deployments`)).body ?? [],
    deployment: async (id) => (await S.get(`${base}/deployments/${id}`)).body,
    publish: async (key, visibility = "PRIVATE", expectedRevision) => S.post(`${base}/publish`, { visibility, expectedRevision: expectedRevision ?? (await api.revision()) }, { headers: { "Idempotency-Key": key } }),
    rollback: (body, key) => S.post(`${base}/site/rollback`, body, key ? { headers: { "Idempotency-Key": key } } : {}),
    unpublish: (expected, key) => S.del(`${base}/site${expected ? `?expectedActiveDeploymentId=${expected}` : ""}`, key ? { headers: { "Idempotency-Key": key } } : {}),
    /** a real content change (a new version to publish) */
    async edit(text) {
      const sc = (await S.get(`${base}/schema`)).body;
      return S.patch(`${base}/schema`, { expectedRevision: sc.revision, summary: `e2e release: ${text}`, operations: [{ type: "UPDATE_PROP", sectionId: sc.schema.sections[0].id, path: "brand", value: text }] });
    },
    async settle(id, ms = 60_000) {
      const end = Date.now() + ms; let d;
      while (Date.now() < end) { d = await api.deployment(id); if (TERMINAL.includes(d?.status)) return d; await sleep(400); }
      return d;
    },
    /** edit + publish + settle: one more RUNNING release (the newest becomes the active one) */
    async release(fx, label) {
      await api.edit(`${label}-${fx.runId}`);
      let r = await api.publish(`e2e:${fx.runId}:${label}:${Date.now()}`);
      // the contract's own throttle (429 RATE_LIMITED, app.rate-limit.publish-max, default 10/min/user): wait what the server says, once
      if (r.status === 429) { await sleep(Math.min(65, Number(r.headers.get("retry-after") ?? 61)) * 1000); r = await api.publish(`e2e:${fx.runId}:${label}:${Date.now()}`); }
      if (r.status !== 202) throw new Error(`baseline publish refused: ${r.status} ${r.body?.code}`);
      const d = await api.settle(r.body.id);
      if (d?.status !== "RUNNING") throw new Blocked("C2", `baseline publish did not reach RUNNING (${d?.status} ${d?.error}); is the stack on DEPLOY_PROVIDER=static?`, "static provider");
      return d;
    },
  };
  return api;
}

/** three RUNNING releases, the newest active: [oldest, middle, active] */
export async function baseline(fx, api) {
  const a = await api.release(fx, "rel-a"), b = await api.release(fx, "rel-b"), c = await api.release(fx, "rel-c");
  return { a, b, c };
}

export function needHooks(cfg, kinds) {
  const miss = [];
  if (kinds.includes("store") && !(cfg.pauseStoreCmd && cfg.resumeStoreCmd)) miss.push("E2E_PAUSE_STORE_CMD / E2E_RESUME_STORE_CMD (pause/unpause ONLY the artifact store)");
  if (kinds.includes("render") && !(cfg.pauseRenderCmd && cfg.resumeRenderCmd)) miss.push("E2E_PAUSE_RENDER_CMD / E2E_RESUME_RENDER_CMD (SIGSTOP/SIGCONT ONLY the render worker)");
  if (miss.length) throw new Blocked("C0", `no fault-injection hooks: ${miss.join("; ")}. The suite never guesses how to pause a service.`, "release fault injection");
}
/** runs `fn` with the artifact store / render worker paused and ALWAYS resumes it */
export async function paused(cfg, kind, fn) {
  const [pause, resume] = kind === "store" ? [cfg.pauseStoreCmd, cfg.resumeStoreCmd] : [cfg.pauseRenderCmd, cfg.resumeRenderCmd];
  await sh(pause, { timeout: 30_000 });
  try { return await fn(); } finally { await sh(resume, { timeout: 30_000 }).catch(() => undefined); }
}
/** Starts nothing itself: polls `read()` every `step` ms and PAUSES the store/render worker the moment `pred(value)` holds (e.g. the deployment reached DEPLOYING, where the scope lease is held),
 *  then runs `fn`; always resumes. Returns false (and runs nothing) if the moment never came within `ms`. */
export async function pauseWhen(cfg, kind, read, pred, fn, ms = 30_000, step = 40) {
  const [pause, resume] = kind === "store" ? [cfg.pauseStoreCmd, cfg.resumeStoreCmd] : [cfg.pauseRenderCmd, cfg.resumeRenderCmd];
  const end = Date.now() + ms; let v;
  while (Date.now() < end) { v = await read(); if (pred(v)) break; await sleep(step); }
  if (!pred(v)) return { reached: false, value: v };
  await sh(pause, { timeout: 30_000 });
  try { return { reached: true, value: v, result: await fn() }; } finally { await sh(resume, { timeout: 30_000 }).catch(() => undefined); }
}
/** polls `read()` until `pred(value)` or `ms`; returns the last value */
export async function until(read, pred, ms = 20_000, step = 200) {
  const end = Date.now() + ms; let v;
  while (Date.now() < end) { v = await read(); if (pred(v)) return v; await sleep(step); }
  return v;
}
/** opens the release dialog from the Builder header (the URL becomes `…/publish`): bounded retry, because a click that lands while the router is still moving does nothing */
export async function showRelease(page) {
  const modal = page.getByTestId("release-modal");
  const r = await boundedRetry(3, async () => {
    if (await modal.count()) return true;
    const btn = page.locator("header.bx-top").getByRole("button", { name: /^Xuất bản/ });
    if (await btn.count()) await btn.click({ timeout: 8_000 }).catch(() => undefined);
    const proceed = page.getByRole("button", { name: /Vẫn xuất bản|Tiếp tục xuất bản|Vẫn tiếp tục/ });
    if (await proceed.count()) await proceed.first().click().catch(() => undefined);
    return modal.waitFor({ timeout: 6_000 }).then(() => true).catch(() => false);
  });
  if (!r.ok) await modal.waitFor({ timeout: 5_000 });   // fail with Playwright's own message
  return modal;
}
/** closes the release dialog and waits until it is really gone (a header click that lands while the old dialog is still unmounting would otherwise be taken for "already open") */
export async function closeRelease(page) {
  await page.getByTestId("release-modal").getByRole("button", { name: /^(Đóng|Hủy)$/ }).first().click();
  await page.getByTestId("release-modal").waitFor({ state: "detached", timeout: 10_000 });
}
/** the release dialog of the Builder (login → Builder → "Xuất bản"), with request capture for the publish / site routes; waits until SiteInfo and the history are loaded */
export async function openRelease(page, cfg, user, projectId) {
  await loginUi(page, cfg, user.username, user.password);
  await openBuilder(page, cfg, projectId);
  const modal = await showRelease(page);
  await page.getByTestId("site-box").waitFor({ timeout: 15_000 }).catch(() => undefined);   // `real` provider: SiteInfo loaded
  await page.waitForTimeout(300);
  return modal;
}
/** every request the page makes to release routes: method, path+query, headers, body */
export function captureRelease(page) {
  const log = [];
  page.on("request", (r) => { const u = new URL(r.url()); if (/\/(publish|site|site\/rollback|deployments(\/[^/]+)?)$/.test(u.pathname)) log.push({ method: r.method(), path: u.pathname + u.search, headers: r.headers(), body: r.postData() }); });
  return log;
}
/** anything the browser sent that must never be sent */
export const forbiddenSent = (log) => log.filter((x) => /pointerVersion|tenantId|expectedPointer|if-match/i.test(`${x.path} ${x.body ?? ""} ${JSON.stringify(x.headers)}`));
