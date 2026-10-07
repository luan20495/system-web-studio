// @class: real-backend — supplementary: a publish that is interrupted by a REAL backend outage never shows "Website đã lên" unless the server says RUNNING, and after recovery the UI reports the server's truth.
// (A stale-revision publish is NOT a failure path: the UI publishes the latest saved version — observed 2026-10-06.) Needs hooks E2E_STOP_BACKEND_CMD / E2E_START_BACKEND_CMD.
import { exec } from "node:child_process";
import { promisify } from "node:util";
import { Blocked } from "../lib/report.mjs";
import { Session } from "../lib/api.mjs";
import { loginUi, newPage, openBuilder, pageProblems, bodyText, countRequests } from "../lib/ui.mjs";
const sh = promisify(exec);
export const id = "E2E-S7", title = "(supplementary) Publish interrupted by a backend outage: no fake success, UI ends on the server's truth";
export async function run({ cfg, fx, browser, check }) {
  if (!cfg.stopBackendCmd || !cfg.startBackendCmd) throw new Blocked("C0", "no hooks: set E2E_STOP_BACKEND_CMD and E2E_START_BACKEND_CMD to commands that stop/start ONLY the API of the stack under test", "E2E_*_BACKEND_CMD");
  const w = fx.workspaces.A, p = fx.projects.A.id, A = fx.sessions.adminA, base = `/workspaces/${w}/projects/${p}`;
  const page = await newPage(browser);
  await loginUi(page, cfg, fx.users.adminA.username, fx.users.adminA.password);
  await openBuilder(page, cfg, p);
  // the fixture project is shared with other flows (E2E-12/13 publish it too): compare with what existed BEFORE this flow, never with an absolute count
  const idsBefore = new Set(((await A.get(`${base}/deployments`)).body ?? []).map((d) => d.id));
  const api = countRequests(page, /\/api\/v1\//);
  await page.locator("header.bx-top").getByRole("button", { name: /^Xuất bản/ }).click();
  const proceed = page.getByRole("button", { name: /Vẫn xuất bản|Tiếp tục xuất bản|Vẫn tiếp tục/ });
  if (await proceed.count()) await proceed.first().click();
  const dialog = page.getByRole("dialog"); await dialog.waitFor({ timeout: 10_000 });
  const start = page.waitForResponse((r) => r.request().method() === "POST" && /\/publish$/.test(new URL(r.url()).pathname), { timeout: 20_000 }).catch(() => null);
  await dialog.getByRole("button", { name: /^Xuất bản$/ }).click();
  const r = await start; const dep = await r?.json().catch(() => null);
  check.ok("POST …/publish accepted (2xx) with a deployment id before the outage", !!r && r.status() < 300 && !!dep?.id, `status=${r?.status()}`);
  if (!dep?.id) { await page.context().close(); return; }
  // the outage hits while the pipeline is running and the UI is polling
  await sh(cfg.stopBackendCmd, { timeout: 60_000 });
  const outageFrom = Date.now(); let recovered = false, sawSuccessWhileDown = false;
  try {
    for (let i = 0; i < 6; i++) { await page.waitForTimeout(1500); if (/Website đã lên/.test(await dialog.innerText().catch(() => ""))) sawSuccessWhileDown = true; }
    check.ok("while the backend is down the UI never claims the site is up", !sawSuccessWhileDown);
    check.ok("no request storm while polling against a dead backend (< 40 API calls in the outage window)", api.since(outageFrom) < 40, `calls=${api.since(outageFrom)}`);
  } finally { await sh(cfg.startBackendCmd, { timeout: 60_000 }); recovered = await waitUp(cfg.studio, 150_000); }
  check.ok("the API answers again after the start hook", recovered, "", "recovery");
  // the truth lives on the server: whatever the pipeline ended as (it may have been recovered or marked failed), the UI must say the same thing
  const A2 = new Session(cfg.studio, "adminA-after"); await A2.login(fx.users.adminA.username, fx.users.adminA.password);
  let server = null; const until = Date.now() + 180_000;
  while (Date.now() < until) { server = (await A2.get(`${base}/deployments/${dep.id}`)).body; if (["RUNNING", "FAILED", "CANCELLED"].includes(server?.status)) break; await new Promise((r) => setTimeout(r, 3000)); }
  fx.notes.publishAfterOutage = server?.status ?? "unknown";
  check.ok("the deployment reaches a terminal state on the server after recovery (RUNNING or FAILED; never stuck)", ["RUNNING", "FAILED", "CANCELLED"].includes(server?.status), `status=${server?.status}`, "recovery");
  // reload: the UI of a fresh page load reads the server's truth
  await openBuilder(page, cfg, p);
  const deps = (await A2.get(`${base}/deployments`)).body;
  const fresh = Array.isArray(deps) ? deps.filter((d) => !idsBefore.has(d.id)) : [];
  check.ok("the outage created exactly ONE new deployment (the one this publish returned; no duplicate)", fresh.length === 1 && fresh[0].id === dep.id, `new=${fresh.map((d) => d.id).join(",")}`, "persistence");
  const txt = await dialog.innerText().catch(() => "");
  if (txt) {
    const ui = /Website đã lên/.test(txt) ? "RUNNING" : /Không xuất bản được|thất bại/.test(txt) ? "FAILED" : "other";
    check.ok("if the publish dialog is still shown it agrees with the server (never RUNNING when the server says FAILED, and vice versa)", ui === "other" || ui === server?.status, `ui=${ui} server=${server?.status}`);
  }
  check.ok("no unhandled page errors other than the expected network failures", pageProblems(page).filter((e) => !/Failed to fetch|NetworkError|ECONNREFUSED|50\d|net::|Internal Server Error/i.test(e)).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
async function waitUp(base, ms) { const end = Date.now() + ms; while (Date.now() < end) { try { const r = await fetch(`${base}/api/v1/auth/config`, { signal: AbortSignal.timeout(4000) }); if (r.ok) return true; } catch { /* still down */ } await new Promise((r) => setTimeout(r, 2000)); } return false; }
