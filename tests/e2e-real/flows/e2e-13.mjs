// @class: real-backend — publish through the UI, then read the artifact as an anonymous visitor.
import { Blocked } from "../lib/report.mjs";
import { loginUi, newPage, openBuilder, bodyText, pageProblems } from "../lib/ui.mjs";
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
  const pub = dialog.getByRole("button", { name: /Công khai/ });
  if (!(await pub.count())) throw new Blocked("C2", "PUBLIC publishing is switched off by policy for this app type (the dialog offers PRIVATE only)", "publish policy");
  await pub.click();
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
  if (url) {
    const visitor = await newPage(browser);                                    // fresh context: NO cookies, anonymous
    const resp = await visitor.goto(url, { waitUntil: "domcontentloaded", timeout: 30_000 }).catch((e) => ({ status: () => 0, err: String(e) }));
    check.ok("anonymous visitor: the public page answers 200", resp?.status?.() === 200, `status=${resp?.status?.()} ${resp?.err ?? ""}`);
    const body = await bodyText(visitor);
    check.ok("the published page is the saved content (contains the text saved in E2E-02 — compared case-insensitively, the eyebrow is rendered with CSS uppercase — or is non-empty)", fx.notes.marker ? body.toLowerCase().includes(fx.notes.marker.toLowerCase()) : body.length > 20, body.slice(0, 100));
    check.ok("no Studio chrome leaks into the public page", !/Chế độ dùng thử|Lưu thay đổi/.test(body));
    // a FACT for the report, not a check: does the published site serve /runtime-config.json (the published-runtime contract)? C0/C2 own the answer (B-C5-06)
    const rc = await fetch(new URL("runtime-config.json", url.endsWith("/") ? url : `${url}/`), { signal: AbortSignal.timeout(8000) }).catch((e) => ({ status: 0, err: String(e) }));
    fx.notes.publishedRuntimeConfig = { url: new URL("runtime-config.json", url.endsWith("/") ? url : `${url}/`).pathname, status: rc.status };
    console.log(`    [fact] published site GET runtime-config.json → ${rc.status}`);
    await visitor.context().close();
  }
  check.ok("no unhandled page errors in Studio", pageProblems(page).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
