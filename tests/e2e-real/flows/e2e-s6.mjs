// @class: real-backend — supplementary: the backend is really STOPPED and started again. The UI shows errors (never success), does not storm the API, and works again after the restart.
// Needs hooks (the suite never guesses how to stop a service): E2E_STOP_BACKEND_CMD and E2E_START_BACKEND_CMD (the start hook must return once the process is launched; the flow waits for the API).
import { exec } from "node:child_process";
import { promisify } from "node:util";
import { Blocked } from "../lib/report.mjs";
import { Session } from "../lib/api.mjs";
import { loginUi, newPage, openBuilder, pageProblems, selectFirstSection, countRequests, bodyText, rememberMarker } from "../lib/ui.mjs";
const sh = promisify(exec);
export const id = "E2E-S6", title = "(supplementary) Backend stopped: errors not success, no request storm, recovery after start";
export async function run({ cfg, fx, browser, check }) {
  if (!cfg.stopBackendCmd || !cfg.startBackendCmd) throw new Blocked("C0", "no hooks: set E2E_STOP_BACKEND_CMD and E2E_START_BACKEND_CMD to commands that stop/start ONLY the API of the stack under test", "E2E_*_BACKEND_CMD");
  const w = fx.workspaces.A, p = fx.projects.A.id, A = fx.sessions.adminA, base = `/workspaces/${w}/projects/${p}`;
  const page = await newPage(browser);
  await loginUi(page, cfg, fx.users.adminA.username, fx.users.adminA.password);
  await openBuilder(page, cfg, p);
  const sel = await selectFirstSection(page);
  check.ok("clicking a section on the canvas opens its properties", sel.ok, `attempts=${sel.attempts}`);
  if (!sel.ok) { await page.context().close(); return; }
  const rev0 = (await A.get(base)).body.revision;
  const marker = rememberMarker(fx, `E2E-DOWN-${fx.runId}`);
  await page.locator(".bx-right input").first().fill(marker);
  const api = countRequests(page, /\/api\/v1\//);
  const saves = countRequests(page, /\/schema$/, "PATCH");
  await sh(cfg.stopBackendCmd, { timeout: 60_000 });
  let recovered = false;
  try {
    const t0 = Date.now();
    await page.getByRole("button", { name: /Lưu thay đổi/ }).click();
    await page.waitForSelector(".saveState.error", { timeout: 25_000 }).catch(() => undefined);
    check.ok("save while the backend is down: the top bar says the save FAILED (not saved)", (await page.locator(".saveState").innerText()).includes("Lưu thất bại"), await page.locator(".saveState").innerText());
    check.ok("a retry button is offered and Publish is disabled while the edit is unsaved", (await page.getByTestId("retry-save").count()) === 1 && (await page.locator("header.bx-top").getByRole("button", { name: /^Xuất bản/ }).isDisabled()));
    await page.waitForTimeout(6000);
    check.ok("no automatic retry loop: at most 2 save requests in ~30 s of outage", saves.count <= 2, `patches=${saves.count}`);
    check.ok("no request storm while the backend is down (< 30 API calls)", api.count < 30, `api calls=${api.count} in ${Date.now() - t0} ms`);
    check.ok("the page stayed usable: it still shows the Builder and the unsaved text", (await page.locator(".bx-right input").first().inputValue()) === marker);
  } finally {
    await sh(cfg.startBackendCmd, { timeout: 60_000 });
    recovered = await waitUp(cfg.studio, 150_000);
  }
  check.ok("the API answers again after the start hook", recovered, "", "recovery");
  const A2 = new Session(cfg.studio, "adminA-after"); await A2.login(fx.users.adminA.username, fx.users.adminA.password);
  check.ok("nothing was written while it was down (revision unchanged)", (await A2.get(base)).body.revision === rev0);
  // the browser session cookie is a server session in Redis: it may survive; the retry must work either way (or ask to log in again, never claim success)
  const [patch] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PATCH" && /\/schema$/.test(new URL(r.url()).pathname), { timeout: 30_000 }).catch(() => null),
    page.getByTestId("retry-save").click(),
  ]);
  check.ok("retry after recovery reaches the backend and is accepted (200)", patch?.status() === 200, `status=${patch?.status?.()}`, "recovery");
  await page.waitForSelector(".saveState.saved", { timeout: 10_000 }).catch(() => undefined);
  const after = (await A2.get(`${base}/schema`)).body;
  check.ok("the edit is persisted exactly once (revision + 1)", JSON.stringify(after.schema).includes(marker) && after.revision === rev0 + 1, `${rev0} → ${after.revision}`, "persistence");
  await page.reload({ waitUntil: "domcontentloaded" });
  check.ok("after a browser refresh the Builder opens again by direct URL", await page.waitForSelector("iframe", { timeout: 20_000 }).then(() => true).catch(() => false), "", "recovery");
  check.ok("no unhandled page errors other than the expected network failures", pageProblems(page).filter((e) => !/Failed to fetch|NetworkError|ECONNREFUSED|50\d|net::|Internal Server Error/i.test(e)).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
async function waitUp(base, ms) { const end = Date.now() + ms; while (Date.now() < end) { try { const r = await fetch(`${base}/api/v1/auth/config`, { signal: AbortSignal.timeout(4000) }); if (r.ok) return true; } catch { /* still down */ } await new Promise((r) => setTimeout(r, 2000)); } return false; }
