// @class: real-backend — BLOCKED: there is no published host that serves the data runtime to a published app, so a LIVE result has no UI surface.
// SEPARATION: [fixture] operator-seeded project (E2E_DATA_*) · [api] LIVE run evidence · [ui] — none exists (published app) · [backend] — · [cleanup] nothing created.
// The frontend side is READY: a published app loads GET /runtime-config.json ({DATA_API_BASE_URL, ENVIRONMENT?, RELEASE_ID?, VERSION?}) before it creates a Data API client and fails visibly otherwise
// (packages/api-client/src/runtimeConfig.ts, unit-tested). What is missing is the HOST: C2's proposal is unverified and C0/C2/C3 have not fixed the public publish topology.
import { Blocked } from "../lib/report.mjs";
import { Session } from "../lib/api.mjs";
export const id = "E2E-08", title = "LIVE-binding query runs and shows results";
export const blocker = { owner: "C0", ref: "B-C5-06 + H-C2-04 + H-C2-02 + H-C0-07", reason: "LIVE needs (1) a RUNNING deployment and a published host that serves the Data API: C0 states there is no public DataGateway route and no published data host, and the runtime config that would carry its address is undecided (C0 `__factory/config.json` apiBase, served for code apps/previews only, vs C2 `/runtime-config.json` DATA_API_BASE_URL); (2) a declared slot (C2 H-C2-02); (3) a source the gateway may reach (H-C0-07). Studio's Test panel is TEST-only by design." };
export async function run({ cfg, check }) {
  const s = cfg.preseeded;
  if ([s.workspaceId, s.projectId, s.queryId, s.user, s.password].every(Boolean)) {
    // Evidence only (API level). The UI part of this flow cannot be exercised, so the flow stays BLOCKED even when this passes.
    const api = new Session(cfg.studio, "preseeded"); await api.login(s.user, s.password);
    const r = await api.post(`/workspaces/${s.workspaceId}/projects/${s.projectId}/app-runtime/queries/${encodeURIComponent(s.queryId)}/run`, { mode: "LIVE" });
    check.ok("(evidence) LIVE run answers 200 or a documented 404/422/503 — never 500", [200, 404, 422, 503].includes(r.status), `status=${r.status} code=${r.body?.code}`);
  }
  // READY to read `apiBase`: when the operator names the published origin (E2E_PUBLIC_BASE) the flow reads its /runtime-config.json exactly like the page's loader does
  // (https only, loopback only in development, no credentials/query/fragment) and probes the Data API origin it names. Without a published host this stays a fact, not a check.
  let published = "E2E_PUBLIC_BASE not set — no published host to ask", apiBase = null;
  if (cfg.publicBase) {
    const base = cfg.publicBase.replace(/\/+$/, "");
    // two candidate contracts, both asked: C0's existing one (`__factory/config.json` → `apiBase`, served for code apps and previews, null until a data host exists) and C2's proposal (`/runtime-config.json` → DATA_API_BASE_URL)
    for (const [rel, key] of [["__factory/config.json", "apiBase"], ["runtime-config.json", "DATA_API_BASE_URL"]]) {
      try {
        const r = await fetch(`${base}/${rel}`, { signal: AbortSignal.timeout(8000), headers: { Accept: "application/json" } });
        const j = r.ok ? await r.json().catch(() => null) : null;
        published += `${published.startsWith("E2E_PUBLIC") ? "" : " · "}GET /${rel} → ${r.status}`;
        const raw = j?.[key]; if (!j || raw == null) { if (j) published += ` (${key} is ${raw === null ? "null: no data host yet" : "absent"})`; continue; }
        const u = (() => { try { return new URL(raw); } catch { return null; } })();
        const loopback = !!u && /^(127\.0\.0\.1|localhost|\[::1\])$/.test(u.hostname);
        const valid = !!u && !u.username && !u.password && !u.search && !u.hash && (u.protocol === "https:" || (u.protocol === "http:" && loopback && j.ENVIRONMENT !== "production" && j.environment !== "production"));
        check.ok(`/${rel} carries a valid ${key} (https; loopback http only outside production; no credentials/query/fragment)`, valid, `base=${u ? u.origin : "unparsable"}`);
        if (valid && !apiBase) {
          apiBase = u.origin.replace(/\/+$/, ""); published += ` · apiBase ${apiBase}`;
          const probe = await fetch(`${apiBase}/api/v1/auth/config`, { signal: AbortSignal.timeout(8000) }).catch((e) => ({ status: 0, err: String(e?.message ?? e) }));
          check.ok("the Data API origin named by the config answers like the platform API (GET /api/v1/auth/config → 200)", probe.status === 200, `status=${probe.status} ${probe.err ?? ""}`);
        }
      } catch (e) { published += ` · GET /${rel} failed: ${e?.message ?? e}`; }
    }
  }
  // the LIVE query itself needs what is still undecided: how an anonymous published page authenticates to the Data API and which CORS model applies (C0/C2/C3)
  throw new Blocked(blocker.owner, `${blocker.reason} [published host: ${published}]`, blocker.ref);
}
