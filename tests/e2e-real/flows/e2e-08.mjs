// @class: real-backend — BLOCKED skeleton: LIVE results have no UI surface and no LIVE binding can be created.
import { Blocked } from "../lib/report.mjs";
import { Session } from "../lib/api.mjs";
export const id = "E2E-08", title = "LIVE-binding query runs and shows results";
export const blocker = { owner: "C2", ref: "B-C5-06 + B-C0-W-03", reason: "LIVE needs (1) a LIVE source binding, which no HTTP route creates (B-C0-W-03), (2) a RUNNING deployment of the project, and (3) a UI that shows the result: the published app has no data runtime host (workers/render only takes {schema, assets}; B-C5-06). Studio's Test panel is TEST-only by design." };
export async function run({ cfg, check }) {
  const s = cfg.preseeded;
  if ([s.workspaceId, s.projectId, s.queryId, s.user, s.password].every(Boolean)) {
    // Evidence only (API level). The UI part of this flow cannot be exercised, so the flow stays BLOCKED even when this passes.
    const api = new Session(cfg.studio, "preseeded"); await api.login(s.user, s.password);
    const r = await api.post(`/workspaces/${s.workspaceId}/projects/${s.projectId}/app-runtime/queries/${encodeURIComponent(s.queryId)}/run`, { mode: "LIVE" });
    check.ok("(evidence) LIVE run answers 200 or a documented 404/422/503 — never 500", [200, 404, 422, 503].includes(r.status), `status=${r.status} code=${r.body?.code}`);
  }
  throw new Blocked(blocker.owner, blocker.reason, blocker.ref);
}
