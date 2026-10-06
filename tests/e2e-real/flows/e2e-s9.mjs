// @class: real-backend — supplementary: the backend HANGS (process paused with SIGSTOP, not down). The client times out (15 s), says so, never claims "saved", does not storm; the request may still have
// reached the server (UNKNOWN OUTCOME): after the resume the invariants are that the edit is applied AT MOST once and the UI never says "saved" for something the server does not have.
// Needs hooks E2E_PAUSE_BACKEND_CMD / E2E_RESUME_BACKEND_CMD (pause/continue ONLY the API process).
import { exec } from "node:child_process";
import { promisify } from "node:util";
import { Blocked } from "../lib/report.mjs";
import { loginUi, newPage, openBuilder, pageProblems, selectFirstSection, countRequests, rememberMarker } from "../lib/ui.mjs";
const sh = promisify(exec);
export const id = "E2E-S9", title = "(supplementary) Backend hangs: timeout shown, no fake saved, unknown outcome applied at most once";
export async function run({ cfg, fx, browser, check }) {
  if (!cfg.pauseBackendCmd || !cfg.resumeBackendCmd) throw new Blocked("C0", "no hooks: set E2E_PAUSE_BACKEND_CMD and E2E_RESUME_BACKEND_CMD to commands that SIGSTOP / SIGCONT ONLY the API process of the stack under test", "E2E_*_BACKEND_CMD (pause/resume)");
  const w = fx.workspaces.A, p = fx.projects.A.id, A = fx.sessions.adminA, base = `/workspaces/${w}/projects/${p}`;
  const page = await newPage(browser);
  await loginUi(page, cfg, fx.users.adminA.username, fx.users.adminA.password);
  await openBuilder(page, cfg, p);
  const sel = await selectFirstSection(page);
  check.ok("clicking a section on the canvas opens its properties", sel.ok, `attempts=${sel.attempts}`);
  if (!sel.ok) { await page.context().close(); return; }
  const rev0 = (await A.get(base)).body.revision;
  const marker = rememberMarker(fx, `E2E-HANG-${fx.runId}`);
  await page.locator(".bx-right input").first().fill(marker);
  const api = countRequests(page, /\/api\/v1\//), saves = countRequests(page, /\/schema$/, "PATCH");
  await sh(cfg.pauseBackendCmd, { timeout: 30_000 });
  let resumed = false, seenMs = 0;
  try {
    const t0 = Date.now();
    await page.getByRole("button", { name: /Lưu thay đổi/ }).click();
    await page.waitForSelector(".saveState.error", { timeout: 40_000 }).catch(() => undefined);
    seenMs = Date.now() - t0;
    check.ok("the top bar says the save FAILED once the client gave up (not before ~15 s, not never)", (await page.locator(".saveState").innerText()).includes("Lưu thất bại") && seenMs >= 12_000 && seenMs <= 35_000, `after ${seenMs} ms: ${await page.locator(".saveState").innerText()}`);
    check.ok("a retry button is offered; Publish is disabled while the edit is unsaved", (await page.getByTestId("retry-save").count()) === 1 && (await page.locator("header.bx-top").getByRole("button", { name: /^Xuất bản/ }).isDisabled()));
    await page.waitForTimeout(4000);
    check.ok("no automatic retry loop while the backend hangs (≤ 1 save request)", saves.count <= 1, `patches=${saves.count}`);
    check.ok("no request storm while the backend hangs (< 20 API calls)", api.count < 20, `api calls=${api.count}`);
  } finally { await sh(cfg.resumeBackendCmd, { timeout: 30_000 }); resumed = true; }
  await page.waitForTimeout(2500);                                           // the paused request, if it reached the socket, is processed now (UNKNOWN OUTCOME)
  const afterResume = (await A.get(`${base}/schema`)).body;
  const applied = JSON.stringify(afterResume.schema).includes(marker);
  fx.notes.hangUnknownOutcome = { reachedServerBeforeResume: applied, revision: `${rev0} → ${afterResume.revision}` };
  console.log(`    [fact] the timed-out save ${applied ? "HAD reached the server and was applied after the resume" : "never reached the server"} (revision ${rev0} → ${afterResume.revision})`);
  const [retry] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PATCH" && /\/schema$/.test(new URL(r.url()).pathname), { timeout: 30_000 }).catch(() => null),
    page.getByTestId("retry-save").click(),
  ]);
  await page.waitForTimeout(1500);
  const bar = await page.locator(".saveState").innerText();
  const finalState = (await A.get(`${base}/schema`)).body;
  check.ok("the edit is applied AT MOST ONCE on the server (revision grew by 0 or 1 only when the marker is there)", finalState.revision - rev0 <= 1 && (finalState.revision === rev0 + 1) === JSON.stringify(finalState.schema).includes(marker), `${rev0} → ${finalState.revision}, marker=${JSON.stringify(finalState.schema).includes(marker)}`, "persistence");
  check.ok("the UI says 'saved' only if the server has the edit", !/Đã lưu/.test(bar) || JSON.stringify(finalState.schema).includes(marker), `bar="${bar}" retry=${retry?.status?.()}`, "persistence");
  check.ok("after the retry the answer is explicit (200, or 409 when the first attempt had landed) — never silence", [200, 409].includes(retry?.status?.()), `status=${retry?.status?.()}`);
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.waitForSelector("iframe", { timeout: 20_000 }).catch(() => undefined);
  const text = await page.frameLocator("iframe").locator("body").innerText().catch(() => "");
  check.ok("after a browser refresh the canvas shows what the server has (the marker iff it is persisted)", text.toLowerCase().includes(marker.toLowerCase()) === JSON.stringify((await A.get(`${base}/schema`)).body.schema).includes(marker), "", "persistence");
  check.ok("no unhandled page errors other than the expected timeout/network failures", pageProblems(page).filter((e) => !/timeout|TIMEOUT|Failed to fetch|NetworkError|AbortError|net::|50\d|409/i.test(e)).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
