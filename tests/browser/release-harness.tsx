// @class: harness — in-page fake of the release dialog's calls; NOT a backend and NOT a backend E2E (tests/browser/release.spec.mjs)
/**
 * TEST-ONLY harness for <PublishModal> (publish / rollback / unpublish). NOT a backend and NOT an E2E of the product: `window.__rel` is an in-page fake of the six calls the dialog makes, driven by the spec
 * (statuses, SiteInfo, errors). It records every call (with the Idempotency-Key it was given) and proves what the DIALOG does with the answers the C2 contract describes. What the server answers is the
 * real-backend suite's job (E2E-P01…P09). States that cannot be produced on a real stack on demand (ROLLING_BACK, an expired-lease race, a lost answer) are exercised here.
 */
import { createRoot } from "react-dom/client";
import { ApiError } from "@xweb/api-client";
import type { Deployment, SiteInfo } from "@xweb/types";
import type { AppDefinitionV2 } from "@xweb/types";
import { PublishModal, type ReleaseCalls } from "../../features/studio/ReleaseModal";
import "../../packages/ui/src/styles/globals.css";
import "../../packages/ui/src/styles/responsive.css";
import "../../packages/ui/src/styles/http.css";
import "../../packages/ui/src/styles/factory.css";
import "../../packages/ui/src/styles/builder.css";
import "../../packages/ui/src/styles/ui.css";   // the real layouts load it last (apps/*/app/layout.tsx); a harness without it is not the product

type ErrSpec = { status: number; code: string; message?: string; details?: unknown; retryAfter?: number; sticky?: boolean } | null;
type Rel = {
  calls: { name: string; args: unknown[] }[];
  site: SiteInfo; history: Deployment[]; deployment: Deployment | null;
  /** next answer of each call: an ErrSpec makes it throw ApiError(status, code, …); `null` = answer normally */
  errors: Partial<Record<"publish" | "getDeployment" | "site" | "rollback" | "unpublish" | "list" | "putConfig" | "getConfig", ErrSpec>>;
  /** the SERVER's persisted publish policy (publish_configs), the authority H-C2-07 enforces at POST /publish; null = no stored policy (legacy: the request decides) */
  policy: null | { mode: string; visibility: string; requiresAuth: boolean; cacheSeconds: number | null; publicDataApproved: boolean; revision: number };
  /** does the IMMUTABLE version being published bind data (the server reads the version's snapshot, not the draft the browser holds) */
  binds: boolean;
  /** hold the next call until release() is called (a request in flight) */
  hold: { rollback?: boolean; publish?: boolean }; release: () => void;
  set: (patch: Partial<Pick<Rel, "site" | "history" | "deployment" | "errors" | "hold">>) => void;
};
declare global { interface Window { __rel: Rel } }

const dep = (id: string, v: number, status: Deployment["status"], extra: Partial<Deployment> = {}): Deployment => ({ id, projectId: "p1", versionId: `ver-${v}`, versionNumber: v, visibility: "PRIVATE", status, url: status === "RUNNING" ? `https://sites.example.test/s/${v}/` : null, error: null,
  provider: "static", mock: false, createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-07T00:00:00Z", finishedAt: ["RUNNING", "FAILED", "ROLLED_BACK"].includes(status) ? "2026-10-07T00:00:01Z" : null,
  events: [{ status: "QUEUED", message: null, createdAt: "2026-10-07T00:00:00Z" }], ...extra });
const idle = (current: string | null, v: number | null, pv = 5): SiteInfo => ({ slug: "demo", url: current ? "https://sites.example.test/demo/" : null, online: !!current, visibility: current ? "PRIVATE" : null, currentDeploymentId: current, currentVersionNumber: v, provider: "static", updatedAt: "2026-10-07T00:00:00Z", pointerVersion: pv, operation: null });

const S = new URLSearchParams(location.search).get("s") ?? "ok";
const canPublish = S !== "noperm";
let releaseFn: (() => void) | null = null;
const rel: Rel = {
  calls: [], site: idle("d3", 3), history: [dep("d3", 3, "RUNNING"), dep("d2", 2, "RUNNING"), dep("d1", 1, "RUNNING"), dep("d0", 0, "ROLLED_BACK")], deployment: null, errors: {}, hold: {}, policy: null, binds: false,
  release: () => { releaseFn?.(); releaseFn = null; },
  set(patch) { Object.assign(rel, patch); },
};
window.__rel = rel;

const rec = (name: string, args: unknown[]) => rel.calls.push({ name, args });
const maybeThrow = (k: keyof Rel["errors"]) => { const e = rel.errors[k]; if (e) { if (!e.sticky) rel.errors[k] = null; throw new ApiError(e.status, e.code, e.message ?? e.code, "req-1", e.details, undefined, e.retryAfter); } };
const held = (k: "rollback" | "publish") => (rel.hold[k] ? new Promise<void>((r) => { releaseFn = () => { rel.hold[k] = false; r(); }; }) : Promise.resolve());

const calls = {
  publish: async (_w: string, _p: string, visibility: string, expectedRevision: number, key: string) => {
    rec("publish", [visibility, expectedRevision, key]); await held("publish"); maybeThrow("publish");
    // H-C2-07: the server judges the PERSISTED policy; nothing the browser holds (a ticked box, the draft) is consulted
    const pol = rel.policy;
    if (pol && visibility === "PUBLIC") {
      if (pol.visibility !== "PUBLIC") throw new ApiError(409, "PUBLISH_POLICY_MISMATCH", "mismatch", "req-1");
      if (rel.binds && !pol.publicDataApproved) throw new ApiError(422, "PUBLIC_DATA_NOT_APPROVED", "not approved", "req-1", { issues: [{ field: "acknowledgePublicData", code: "PUBLIC_DATA_NOT_APPROVED", message: "approval required" }] });
    }
    rel.deployment = rel.deployment ?? dep("n1", 4, "QUEUED"); return rel.deployment; },
  getPublishConfig: async () => { rec("getConfig", []); maybeThrow("getConfig"); return { config: rel.policy ? { ...rel.policy, linkTokenSet: false, updatedAt: null } : null, draft: null }; },
  putPublishConfig: async (_w: string, _p: string, b: { mode: string; visibility: string; requiresAuth: boolean; cacheSeconds?: number | null; acknowledgePublicData?: boolean; expectedRevision?: number | null }) => {
    rec("putConfig", [b]); maybeThrow("putConfig");
    if (!rel.policy) throw new ApiError(404, "NOT_FOUND", "no config api", "req-1");
    if (b.expectedRevision !== undefined && b.expectedRevision !== null && b.expectedRevision !== rel.policy.revision) throw new ApiError(409, "REVISION_CONFLICT", "stale", "req-1", { currentRevision: rel.policy.revision });
    rel.policy = { ...rel.policy, mode: b.mode, visibility: b.visibility, requiresAuth: b.requiresAuth, publicDataApproved: b.visibility === "PUBLIC" && b.acknowledgePublicData === true, revision: rel.policy.revision + 1 };
    return { config: { ...rel.policy, linkTokenSet: false, updatedAt: null }, draft: null };
  },
  getDeployment: async (_w: string, _p: string, id: string) => { rec("getDeployment", [id]); maybeThrow("getDeployment"); return rel.deployment!; },
  listDeployments: async () => { rec("list", []); maybeThrow("list"); return rel.history; },
  site: async () => { rec("site", []); maybeThrow("site"); return rel.site; },
  rollbackSite: async (_w: string, _p: string, req: { deploymentId: string; expectedActiveDeploymentId?: string | null }, key?: string) => {
    rec("rollback", [req, key]); await held("rollback"); maybeThrow("rollback");
    const t = rel.history.find((h) => h.id === req.deploymentId)!; rel.site = idle(t.id, t.versionNumber, rel.site.pointerVersion + 1); return rel.site;
  },
  unpublishSite: async (_w: string, _p: string, expected?: string | null, key?: string) => { rec("unpublish", [expected, key]); maybeThrow("unpublish"); rel.site = { ...idle(null, null, rel.site.pointerVersion + 1), slug: "demo" }; return rel.site; },
} as unknown as ReleaseCalls;

// ?draft=public|private|invalid : the document the dialog is asked to publish (PAGE_SCHEMA public data); absent = no draft (code app / older tests)
const DRAFT = new URLSearchParams(location.search).get("draft");
// ?policy=none|public-unapproved|public-approved|private : the server's stored publish policy; ?binds=1|0 : whether the immutable version binds data (default: the draft's public data)
const POLICY = new URLSearchParams(location.search).get("policy"); const BINDS = new URLSearchParams(location.search).get("binds");
const P0 = { mode: "STATIC", requiresAuth: false, cacheSeconds: null, revision: 3 };
rel.policy = POLICY === "public-unapproved" ? { ...P0, visibility: "PUBLIC", publicDataApproved: false } : POLICY === "public-approved" ? { ...P0, visibility: "PUBLIC", publicDataApproved: true } : POLICY === "private" ? { ...P0, visibility: "PRIVATE", publicDataApproved: false } : null;
rel.binds = BINDS === null ? DRAFT === "public" : BINDS === "1";
const base = { page: "p", pages: [], sections: [{ id: "hero", type: "Hero", props: {} }, { id: "grid", type: "ProductGrid", props: {} }], dataSources: [{ id: "orders", name: "Đơn hàng", type: "postgres" }] };
const drafts: Record<string, AppDefinitionV2> = {
  public: { ...base, queries: [{ id: "q-title", name: "Tiêu đề", dataSourceRef: "orders", mode: "READ", public: true }, { id: "q-private", dataSourceRef: "orders", mode: "READ" }, { id: "q-items", name: "Sản phẩm", dataSourceRef: "orders", mode: "READ", public: true }, { id: "q-w", dataSourceRef: "orders", mode: "WRITE" }],
    dataBindings: [{ id: "b1", sectionId: "hero", prop: "title", queryRef: "q-title" }, { id: "b2", sectionId: "grid", prop: "items", queryRef: "q-items" }] } as unknown as AppDefinitionV2,
  private: { ...base, queries: [{ id: "q-private", dataSourceRef: "orders", mode: "READ" }, { id: "q-w", dataSourceRef: "orders", mode: "WRITE" }] } as unknown as AppDefinitionV2,
  invalid: { ...base, queries: [{ id: "q-w", name: "Ghi", dataSourceRef: "orders", mode: "WRITE", public: true }, { id: "q-private", dataSourceRef: "orders", mode: "READ" }], dataBindings: [{ id: "b1", sectionId: "hero", prop: "title", queryRef: "q-private" }] } as unknown as AppDefinitionV2,
  plain: { ...base } as unknown as AppDefinitionV2,
};
createRoot(document.getElementById("root")!).render(<div className="studio"><PublishModal draft={DRAFT ? drafts[DRAFT] : undefined} workspaceId="w1" projectId="p1" revision={7} current="PRIVATE" versionNumber={4} canPublish={canPublish} calls={calls} timing={{ busy: 400, idle: 700 }} onClose={() => undefined} onUnauthorized={() => undefined}/></div>);
