// @class: real-backend — durable workflow: a run that is IN FLIGHT survives backend restarts. BLOCKED unless the stack has durable run stores AND the operator gave a restart hook.
// WHY LIVE: the engine SIMULATES every step in TEST mode (a WAIT finishes at once), so a TEST run is already terminal before any restart and would only prove that a finished record is readable.
// This flow therefore publishes the project, starts a LIVE run of a WAIT-only workflow (no external effect) and asserts it is still in flight (non-terminal) at the moment of the restart.
import { exec } from "node:child_process";
import { promisify } from "node:util";
import { Blocked } from "../lib/report.mjs";
import { Session } from "../lib/api.mjs";
import { loginUi, newPage, openBuilder, pageProblems } from "../lib/ui.mjs";
const sh = promisify(exec);
export const id = "E2E-12", title = "Durable workflow: restart the backend while a LIVE run is in flight, state recovers";
export const blocker = { owner: "C0", ref: "V29 / run-store", reason: "Needs durable run stores (V29, integrated on integration/v2 >= a9e07db with app.workflow.run-store=jdbc) and a restart hook. On a build with the in-memory stores a restart loses every run by design." };
const FAILED = ["FAILED", "CANCELLED"], DONE = ["SUCCEEDED", ...FAILED], IN_FLIGHT = ["PENDING", "RUNNING", "WAITING"];
const WAIT_SECONDS = 90;   // longer than one backend restart (~45 s), shorter than the whole flow budget
export async function run({ cfg, fx, browser, check }) {
  if (!cfg.durableRunStores) throw new Blocked(blocker.owner, `${blocker.reason} Set E2E_DURABLE_RUN_STORES=1 only for a stack that has V29.`, blocker.ref);
  if (!cfg.restartBackendCmd) throw new Blocked("C0", "no restart hook: set E2E_RESTART_BACKEND_CMD to a command that restarts the backend of the stack under test (the suite never guesses how to stop a service)", "E2E_RESTART_BACKEND_CMD");
  if (!fx.notes.definitionOps?.ok) throw new Blocked("C2", `typed V2 definition operations were refused: ${JSON.stringify(fx.notes.definitionOps)}`, "ADD_WORKFLOW_REF");
  const w = fx.workspaces.A, p = fx.projects.A.id, A = fx.sessions.adminA, base = `/workspaces/${w}/projects/${p}`, rt = `${base}/app-runtime`;
  const wf = "e2e-wf-wait";
  const cur = (await A.get(`${base}/schema`)).body;
  const add = await A.patch(`${base}/schema`, { expectedRevision: cur.revision, summary: "e2e: wait workflow", operations: [{ type: "ADD_WORKFLOW_REF", definition: { id: wf, name: "E2E wait", trigger: "MANUAL", steps: [{ id: "w", kind: "WAIT", waitSeconds: WAIT_SECONDS, next: "end" }, { id: "end", kind: "END" }] } }] });
  check.ok("(setup) wait-workflow added", add.status === 200, `status=${add.status} code=${add.body?.code}`);
  // LIVE runs the PUBLISHED version: publish it and wait for RUNNING
  const rev = (await A.get(base)).body.revision;
  const pub = await A.post(`${base}/publish`, { visibility: "PRIVATE", expectedRevision: rev }, { headers: { "Idempotency-Key": `e2e:${fx.runId}:pub12` } });
  check.ok("(setup) publish accepted (2xx) with a deployment id", pub.status < 300 && !!pub.body?.id, `status=${pub.status} code=${pub.body?.code}`);
  let dep = pub.body; const until = Date.now() + 150_000;
  while (dep?.id && dep.status !== "RUNNING" && !["FAILED", "CANCELLED"].includes(dep.status) && Date.now() < until) { await new Promise((r) => setTimeout(r, 2000)); dep = (await A.get(`${base}/deployments/${dep.id}`)).body; }
  if (dep?.status !== "RUNNING") throw new Blocked("C2", `the published version never reached RUNNING (status ${dep?.status}); LIVE workflow runs need a RUNNING deployment`, "deployment provider");

  const page = await newPage(browser);
  await loginUi(page, cfg, fx.users.adminA.username, fx.users.adminA.password);
  await openBuilder(page, cfg, p);

  const key = `e2e:${fx.runId}:durable`;
  const started = await A.post(`${rt}/workflows/${wf}/runs`, { mode: "LIVE", idempotencyKey: key });
  if (started.status === 503 && started.body?.code === "RUNTIME_STORES_VOLATILE") throw new Blocked("C0", "503 RUNTIME_STORES_VOLATILE although E2E_DURABLE_RUN_STORES=1: the stack does not have durable stores", blocker.ref);
  check.ok("the LIVE run was accepted (202) before the restart", started.status === 202 && !!started.body?.runId, `status=${started.status} code=${started.body?.code}`);
  const runId = started.body?.runId;
  if (!runId) { await page.context().close(); return; }
  const read = async (s) => (await s.get(`${rt}/workflow-runs/${runId}`));
  const before = await read(A);
  check.ok("PRECONDITION the run is still IN FLIGHT when the backend is restarted (not terminal)", IN_FLIGHT.includes(String(before.body?.status).toUpperCase()) && before.body?.mode === "LIVE", `status=${before.body?.status} mode=${before.body?.mode}`);

  // ---- restart #1: the run is in flight
  await sh(cfg.restartBackendCmd, { timeout: 170_000 });
  check.ok("the backend is reachable again after restart #1", await waitUp(cfg.studio, 150_000), "", "recovery");
  const A2 = new Session(cfg.studio, "adminA-after-1"); await A2.login(fx.users.adminA.username, fx.users.adminA.password);
  const after1 = await read(A2);
  check.ok("the same run id is still readable after restart #1 (not 404)", after1.status === 200 && after1.body?.runId === runId, `status=${after1.status} code=${after1.body?.code}`, "recovery");
  check.ok("restart #1 neither failed nor cancelled the run", !FAILED.includes(String(after1.body?.status).toUpperCase()), `before=${before.body?.status} after=${after1.body?.status}`, "recovery");
  const replay = await A2.post(`${rt}/workflows/${wf}/runs`, { mode: "LIVE", idempotencyKey: key });
  check.ok("replaying the same idempotency key returns the SAME run id (no second run was created)", [200, 202].includes(replay.status) && replay.body?.runId === runId, `status=${replay.status} runId=${replay.body?.runId} code=${replay.body?.code}`, "recovery");

  // ---- the browser that was open across the restart: reload it; it must open the Builder or ask to log in again, never hang or crash
  await page.reload({ waitUntil: "domcontentloaded" });
  const landed = await Promise.race([page.waitForSelector("iframe", { timeout: 25_000 }).then(() => "builder"), page.waitForURL(/\/login/, { timeout: 25_000 }).then(() => "login")]).catch(() => "neither");
  check.ok("the browser open across the restart reloads into the Builder or the login page (never a blank/hung page)", landed !== "neither", landed, "recovery");
  fx.notes.browserAfterRestart = landed;

  // ---- completion: the wake timer fires after the restart (the run is recovered, not restarted)
  const deadline = Date.now() + (WAIT_SECONDS + 120) * 1000; let last = after1.body;
  while (Date.now() < deadline && !DONE.includes(String(last?.status).toUpperCase())) { await new Promise((r) => setTimeout(r, 3000)); const r = await read(A2); if (r.status === 200) last = r.body; }
  check.ok("the run completes with SUCCEEDED after the restart", String(last?.status).toUpperCase() === "SUCCEEDED", `final=${last?.status}`, "recovery");
  check.ok("the final state is stable (same status on two later reads)", await (async () => { const a = await read(A2); await new Promise((r) => setTimeout(r, 2500)); const b = await read(A2); return a.body?.status === b.body?.status && a.body?.runId === runId && b.body?.runId === runId; })(), "", "persistence");

  // ---- restart #2: the run is already terminal and persisted
  await sh(cfg.restartBackendCmd, { timeout: 170_000 });
  check.ok("the backend is reachable again after restart #2", await waitUp(cfg.studio, 150_000), "", "recovery");
  const A3 = new Session(cfg.studio, "adminA-after-2"); await A3.login(fx.users.adminA.username, fx.users.adminA.password);
  const after2 = await read(A3);
  check.ok("after restart #2 the terminal run keeps its id and its final status", after2.status === 200 && after2.body?.runId === runId && after2.body?.status === last?.status, `status=${after2.status} ${after2.body?.status} (was ${last?.status})`, "persistence");
  const replay2 = await A3.post(`${rt}/workflows/${wf}/runs`, { mode: "LIVE", idempotencyKey: key });
  check.ok("after restart #2 the same key still resolves to the same run (no new run)", [200, 202].includes(replay2.status) && replay2.body?.runId === runId, `status=${replay2.status} runId=${replay2.body?.runId}`, "persistence");
  check.ok("no unhandled page errors other than the expected network failures", pageProblems(page).filter((e) => !/Failed to fetch|NetworkError|ECONNREFUSED|50\d|net::|Internal Server Error/i.test(e)).length === 0, pageProblems(page).join(" | "));
  await page.context().close();
}
async function waitUp(base, ms) { const end = Date.now() + ms; while (Date.now() < end) { try { const r = await fetch(`${base}/api/v1/auth/config`, { signal: AbortSignal.timeout(4000) }); if (r.ok) return true; } catch { /* still down */ } await new Promise((r) => setTimeout(r, 2000)); } return false; }
