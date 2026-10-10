// @class: real-backend — publish through the UI, then read the artifact as an anonymous visitor.
import { Blocked } from "../lib/report.mjs";
import { loginUi, newPage, openBuilder, bodyText, pageProblems, containsText, liveMarkers, chooseVisibility } from "../lib/ui.mjs";
export const id = "E2E-13", title = "Publish → real artifact/runtime state → public page reachable";
export async function run({ cfg, fx, browser, check }) {
  const w = fx.workspaces.A, p = fx.projects.A.id, A = fx.sessions.adminA;
  const page = await newPage(browser);
  await loginUi(page, cfg, fx.users.adminA.username, fx.users.adminA.password);
  await openBuilder(page, cfg, p);
  await page.locator("header.bx-top").getByRole("button", { name: /^Xuất bản/ }).click();
  const proceed = page.getByRole("button", { name: /Vẫn xuất bản|Tiếp tục xuất bản|Vẫn tiếp tục/ });
  if (await proceed.count()) await proceed.first().click();
  const dialog = page.getByRole("dialog");
  await dialog.waitFor({ timeout: 10_000 });
  const pub = await chooseVisibility(dialog, "PUBLIC");
  if (!pub.offered) throw new Blocked("C2", "PUBLIC publishing is switched off by policy for this app type (the dialog offers PRIVATE only)", "publish policy");
  check.ok("UI: the 'Công khai' radio is checked after choosing it", pub.checked);
  const start = page.waitForResponse((r) => r.request().method() === "POST" && /\/publish$/.test(new URL(r.url()).pathname), { timeout: 20_000 }).catch(() => null);
  await dialog.getByRole("button", { name: /^Xuất bản$/ }).click();
  const r = await start;
  const dep = await r?.json().catch(() => null);
  check.ok("POST …/publish accepted by the backend (2xx) with a deployment id", !!r && r.status() < 300 && !!dep?.id, `status=${r?.status()} code=${dep?.code}`);
  check.ok("the publish request carried an Idempotency-Key", !!r?.request().headers()["idempotency-key"]);
  if (!dep?.id) { await page.context().close(); return; }
  const done = page.getByText(/Website đã lên|Demo deployment|Không xuất bản được/).first();
  await done.waitFor({ timeout: 150_000 }).catch(() => undefined);
  const txt = await dialog.innerText().catch(() => "");
  if (/Demo deployment/.test(txt)) throw new Blocked("C2", "the deployment provider is MOCK: a demo URL is shown but no real artifact is served (configure a SELF_HOSTED provider for this flow)", "deployment provider");
  check.ok("the dialog reports the site is up (RUNNING)", /Website đã lên/.test(txt), txt.replace(/\s+/g, " ").slice(0, 200));
  const server = (await A.get(`/workspaces/${w}/projects/${p}/deployments/${dep.id}`)).body;
  check.ok("the deployment is RUNNING on the server", server?.status === "RUNNING" && server?.mock === false, `status=${server?.status} mock=${server?.mock}`);
  const url = server?.url ? new URL(server.url, cfg.publicBase ?? cfg.studio).toString() : null;
  check.ok("the deployment has a URL", !!url);
  // the text the SERVER holds now (the latest persisted marker of any flow), not "E2E-02's marker": flows run in any order and a later edit overwrites the same field
  const draft = (await A.get(`/workspaces/${w}/projects/${p}/schema`)).body?.schema;
  const expectText = liveMarkers(fx, draft).at(-1) ?? null;
  if (url) {
    const visitor = await newPage(browser);                                    // fresh context: NO cookies, anonymous
    const resp = await visitor.goto(url, { waitUntil: "domcontentloaded", timeout: 30_000 }).catch((e) => ({ status: () => 0, err: String(e) }));
    check.ok("anonymous visitor: the public page answers 200", resp?.status?.() === 200, `status=${resp?.status?.()} ${resp?.err ?? ""}`);
    const body = await bodyText(visitor);
    check.ok("the published page is the saved content (contains the text saved in E2E-02 — compared case-insensitively, the eyebrow is rendered with CSS uppercase — or is non-empty)", expectText ? containsText(body, expectText) : body.length > 20, body.slice(0, 100));
    check.ok("no Studio chrome leaks into the public page", !/Chế độ dùng thử|Lưu thay đổi/.test(body));
    // ---- hardening: reload, artifact still reachable, basic cache behaviour, no failing sub-resource
    const failedSub = []; visitor.on("response", (r) => { if (r.status() >= 400 && !/favicon/.test(r.url())) failedSub.push(`${r.status()} ${new URL(r.url()).pathname}`); });
    const again = await visitor.reload({ waitUntil: "domcontentloaded" }).catch(() => null);
    check.ok("anonymous visitor: reloading the public URL answers 200 again with the same saved content", again?.status() === 200 && (expectText ? containsText(await bodyText(visitor), expectText) : true), `status=${again?.status()}`);
    check.ok("no sub-resource of the public page answers 4xx/5xx", failedSub.length === 0, failedSub.join(", "));
    const h1 = await fetch(url, { redirect: "manual", signal: AbortSignal.timeout(10_000) }).catch(() => null);
    const cc = h1?.headers.get("cache-control") ?? "", etag = h1?.headers.get("etag");
    fx.notes.publicPageCache = { status: h1?.status, cacheControl: cc, etag: !!etag };
    check.ok("the page is served with an explicit Cache-Control that forces revalidation (visibility changes and rollbacks must apply at once)", h1?.status === 200 && /no-cache|no-store|max-age=0|must-revalidate/.test(cc), `status=${h1?.status} cache-control=${cc}`);
    // FACT, not a check (no contract promises 304): does a conditional request revalidate? Seen on the Mac run: ETag present, If-None-Match answered 200 (H-C2-05, P3)
    if (etag) { const h2 = await fetch(url, { headers: { "If-None-Match": etag }, redirect: "manual", signal: AbortSignal.timeout(10_000) }).catch(() => null); fx.notes.publicPageCache.conditionalStatus = h2?.status; console.log(`    [fact] conditional GET with the page's ETag → ${h2?.status}`); }
    // the Studio side after a browser refresh: the project is still published (server truth), nothing claims otherwise
    const dep2 = (await A.get(`/workspaces/${w}/projects/${p}/deployments/${dep.id}`)).body;
    check.ok("after the visitor's reload the deployment is still RUNNING on the server", dep2?.status === "RUNNING", `status=${dep2?.status}`);
    // FACTS for the report, not checks. C0 (HANDOFFS_2026-10-06 H-C2 §7): the published runtime config is `<site>/__factory/config.json` and carries `apiBase` (null until a data host exists).
    // C5's loader and the earlier probes used `/runtime-config.json` (C2's unverified proposal): both are recorded so the mismatch stays visible.
    const siteBase = url.endsWith("/") ? url : `${url}/`; fx.notes.publishedRuntimeConfig = {};
    for (const rel of ["__factory/config.json", "runtime-config.json"]) {
      const rc = await fetch(new URL(rel, siteBase), { signal: AbortSignal.timeout(8000), headers: { Accept: "application/json" } }).catch((e) => ({ status: 0, err: String(e) }));
      const j = rc.status === 200 ? await rc.json().catch(() => null) : null;
      fx.notes.publishedRuntimeConfig[rel] = { status: rc.status, keys: j ? Object.keys(j) : null, apiBase: j && "apiBase" in j ? j.apiBase : undefined };
      console.log(`    [fact] published site GET ${rel} → ${rc.status}${j ? ` keys=${Object.keys(j).join(",")} apiBase=${JSON.stringify(j.apiBase)}` : ""}`);
    }
    await visitor.context().close();
  }
  check.ok("no unhandled page errors in Studio", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
