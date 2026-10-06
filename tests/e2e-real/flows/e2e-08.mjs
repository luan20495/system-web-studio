// @class: real-backend — BLOCKED: there is no published host that serves the data runtime to a published app, so a LIVE result has no UI surface.
// SEPARATION: [fixture] operator-seeded project (E2E_DATA_*) · [api] LIVE run evidence · [ui] — none exists (published app) · [backend] — · [cleanup] nothing created.
// The frontend side is READY: a published app loads GET /runtime-config.json ({DATA_API_BASE_URL, ENVIRONMENT?, RELEASE_ID?, VERSION?}) before it creates a Data API client and fails visibly otherwise
// (packages/api-client/src/runtimeConfig.ts, unit-tested). What is missing is the HOST: C2's proposal is unverified and C0/C2/C3 have not fixed the public publish topology.
import { Blocked } from "../lib/report.mjs";
import { Session } from "../lib/api.mjs";
export const id = "E2E-08", title = "LIVE-binding query runs and shows results";
export const blocker = { owner: "C0", ref: "B-C5-06 (published host) + B-C0-W-01 (V29)", reason: "LIVE needs (1) a RUNNING deployment and a published host that serves /runtime-config.json and the Data API (topology not decided: C0/C2/C3; C2's runtime-config proposal is unverified), (2) a query + declared slot (C2 H-C2-02, C3 §5), and (3) durable run stores for mutating work (V29 not in integration/v2). The LIVE binding route exists (C3 e606465) but is unverified. Studio's Test panel is TEST-only by design." };
export async function run({ cfg, check }) {
  const s = cfg.preseeded;
  if ([s.workspaceId, s.projectId, s.queryId, s.user, s.password].every(Boolean)) {
    // Evidence only (API level). The UI part of this flow cannot be exercised, so the flow stays BLOCKED even when this passes.
    const api = new Session(cfg.studio, "preseeded"); await api.login(s.user, s.password);
    const r = await api.post(`/workspaces/${s.workspaceId}/projects/${s.projectId}/app-runtime/queries/${encodeURIComponent(s.queryId)}/run`, { mode: "LIVE" });
    check.ok("(evidence) LIVE run answers 200 or a documented 404/422/503 — never 500", [200, 404, 422, 503].includes(r.status), `status=${r.status} code=${r.body?.code}`);
  }
  // fact for the report, NOT a check: what the public host answers for the runtime config today (absence is the expected state until the host exists)
  let published = "E2E_PUBLIC_BASE not set — no published host to ask";
  if (cfg.publicBase) {
    try {
      const r = await fetch(`${cfg.publicBase.replace(/\/+$/, "")}/runtime-config.json`, { signal: AbortSignal.timeout(8000), headers: { Accept: "application/json" } });
      const j = r.ok ? await r.json().catch(() => null) : null;
      published = `GET /runtime-config.json → ${r.status}${j ? ` · DATA_API_BASE_URL ${typeof j.DATA_API_BASE_URL === "string" && /^https:\/\//.test(j.DATA_API_BASE_URL) ? "present (https)" : "missing or not https"}` : ""}`;
    } catch (e) { published = `GET /runtime-config.json failed: ${e?.message ?? e}`; }
  }
  throw new Blocked(blocker.owner, `${blocker.reason} [published host: ${published}]`, blocker.ref);
}
