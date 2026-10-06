// @class: real-backend — durable workflow: the run survives a backend restart. BLOCKED unless the stack has durable run stores AND the operator gave a restart hook.
import { exec } from "node:child_process";
import { promisify } from "node:util";
import { Blocked } from "../lib/report.mjs";
import { Session } from "../lib/api.mjs";
const sh = promisify(exec);
export const id = "E2E-12", title = "Durable workflow: restart the backend, state recovers";
export const blocker = { owner: "C0", ref: "B-C0-W-01 (V29) / B-C4-05", reason: "C4 run stores are in memory on integration/v2 (f894cc6): V29 run-persistence is a branch (wire/v29-run-persistence), not integrated. A restart loses every run by design today." };
const FAILED = ["FAILED", "TIMED_OUT", "CANCELLED", "CANCELED"];
export async function run({ cfg, fx, check }) {
  if (!cfg.durableRunStores) throw new Blocked(blocker.owner, `${blocker.reason} Set E2E_DURABLE_RUN_STORES=1 only for a stack that has V29.`, blocker.ref);
  if (!cfg.restartBackendCmd) throw new Blocked("C0", "no restart hook: set E2E_RESTART_BACKEND_CMD to a command that restarts the backend of the stack under test (the suite never guesses how to stop a service)", "E2E_RESTART_BACKEND_CMD");
  if (!fx.notes.definitionOps?.ok) throw new Blocked("C2", `typed V2 definition operations were refused: ${JSON.stringify(fx.notes.definitionOps)}`, "ADD_WORKFLOW_REF");
  const w = fx.workspaces.A, p = fx.projects.A.id, A = fx.sessions.adminA, base = `/workspaces/${w}/projects/${p}`;
  const cur = (await A.get(`${base}/schema`)).body;
  const add = await A.patch(`${base}/schema`, { expectedRevision: cur.revision, summary: "e2e: wait workflow", operations: [{ type: "ADD_WORKFLOW_REF", definition: { id: "e2e-wf-wait", name: "E2E wait", trigger: "MANUAL", steps: [{ id: "w", kind: "WAIT", waitSeconds: 45, next: "end" }, { id: "end", kind: "END" }] } }] });
  check.ok("(setup) wait-workflow added", add.status === 200, `status=${add.status} code=${add.body?.code}`);
  const started = await A.post(`${base}/app-runtime/workflows/e2e-wf-wait/runs`, { mode: "TEST", idempotencyKey: `e2e:${fx.runId}:durable` });
  if (started.status === 503 && started.body?.code === "RUNTIME_STORES_VOLATILE") throw new Blocked("C0", "503 RUNTIME_STORES_VOLATILE although E2E_DURABLE_RUN_STORES=1: the stack does not have durable stores", blocker.ref);
  check.ok("the run was accepted (202) before the restart", started.status === 202 && !!started.body?.runId, `status=${started.status} code=${started.body?.code}`);
  const runId = started.body?.runId;
  const before = (await A.get(`${base}/app-runtime/workflow-runs/${runId}`)).body;
  await sh(cfg.restartBackendCmd, { timeout: 170_000 });
  const up = await waitUp(cfg.studio, 150_000);
  check.ok("the backend is reachable again after the restart", up);
  const A2 = new Session(cfg.studio, "adminA-after"); await A2.login(fx.users.adminA.username, fx.users.adminA.password);
  const after = await A2.get(`${base}/app-runtime/workflow-runs/${runId}`);
  check.ok("the run is still readable after the restart (not 404)", after.status === 200 && after.body?.runId === runId, `status=${after.status} code=${after.body?.code}`);
  check.ok("its state was not lost or failed by the restart", !FAILED.includes(String(after.body?.status).toUpperCase()), `before=${before?.status} after=${after.body?.status}`);
  const deadline = Date.now() + 120_000; let last = after.body;
  while (Date.now() < deadline && !["SUCCEEDED", "SUCCESS", "COMPLETED", ...FAILED].includes(String(last?.status).toUpperCase())) { await new Promise((r) => setTimeout(r, 3000)); last = (await A2.get(`${base}/app-runtime/workflow-runs/${runId}`)).body; }
  check.ok("the run completes after the restart", ["SUCCEEDED", "SUCCESS", "COMPLETED"].includes(String(last?.status).toUpperCase()), `final=${last?.status}`);
}
async function waitUp(base, ms) { const end = Date.now() + ms; while (Date.now() < end) { try { const r = await fetch(`${base}/api/v1/auth/config`, { signal: AbortSignal.timeout(4000) }); if (r.ok) return true; } catch { /* still down */ } await new Promise((r) => setTimeout(r, 2000)); } return false; }
