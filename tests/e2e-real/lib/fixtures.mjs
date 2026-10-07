// @class: real-backend — per-run fixtures, created ONLY through the product's own HTTP API (no SQL, no direct DB, no server key material).
//   tenant            : the stack's default tenant. Tenant provisioning is a platform (C1) action, not exercised here → cross-TENANT isolation is C1's backend tests.
//   workspace A / B   : POST /admin/tenants/{DEFAULT_TENANT}/workspaces (the tenant route, tenant explicit in the path; the legacy collection POST is not used anywhere in the suite)
//   users             : adminA (WORKSPACE_ADMIN in A), adminB (WORKSPACE_ADMIN in B), viewerA (VIEWER in A); activated with a RANDOM per-run password kept in memory
//   project / page / component / action / workflow : created as adminA through the normal routes (PATCH /schema operations)
//   datasource / credential / TEST|LIVE binding : created through C3's Management API (docs/parallel/c3/MANAGEMENT_API.md) by the flows that exercise it (tracked in created.dataSources / created.bindings and removed by cleanup())
//   query / mutation / declared slot             : NOT creatable over HTTP (no management endpoint for queries, no AppDefinition op for dataSources[]) → only used when the operator pre-seeded them (E2E_DATA_*)
//   publish config    : PATCH /schema UPDATE_PUBLISH_CONFIG is attempted by the publish flow only
// Every created thing is named `e2e-<runId>-…` and removed/disabled by cleanup(). No production data is read or written.
import { Session, randomSecret } from "./api.mjs";

const need = (r, what) => { if (r.status < 200 || r.status >= 300) throw new Error(`fixture: ${what} failed (${r.status} ${r.body?.code ?? r.error ?? ""})`); return r.body; };

/** the stack's default tenant: the legacy fixtures (flows 01–14) live there, now addressed explicitly */
export const DEFAULT_TENANT = "00000000-0000-0000-0000-000000000001";
export const E2E_ACTION_ID = "e2e-start-wf";
/** the fixture action: starts the fixture workflow from a click on the first section (a mutating type, so TEST must answer WOULD_RUN and never write) */
export const e2eActionDefinition = (sectionId) => ({ id: E2E_ACTION_ID, name: "E2E khởi chạy workflow", type: "START_WORKFLOW", workflowRef: "e2e-wf", idempotency: "REQUIRED", ...(sectionId ? { trigger: { sectionId, event: "onClick" } } : {}) });

/** A schema write that is VALID for the server (an empty `operations` list is rejected by bean validation with 400 before any permission check, which would test nothing).
 *  Used only against callers who must be refused: if the server ever accepted it, the revision check that follows each probe would fail. */
export const deniedWriteProbe = (summary) => ({ operations: [{ type: "ADD_ACTION", definition: { id: "e2e-denied-probe", name: "denied probe", type: "NOTIFY", channel: "IN_APP", templateRef: "e2e-template" } }], summary });

export async function createFixtures(cfg, log = () => {}) {
  const runId = cfg.runId, name = (s) => `e2e-${runId}-${s}`;
  const fx = { runId, created: { users: [], projects: [], workspaces: [], dataSources: [], bindings: [] }, sessions: {}, users: {}, workspaces: {}, projects: {}, ids: {}, notes: {} };

  const admin = new Session(cfg.studio, "admin");
  fx.me = await admin.login(cfg.adminUser, cfg.adminPassword);
  fx.sessions.admin = admin;
  if (!fx.me?.systemAdmin && !fx.me?.platformScope) throw new Error("fixture: E2E_ADMIN_USER is not a system admin (cannot create workspaces/users)");

  for (const k of ["A", "B"]) {
    const w = need(await admin.post(`/admin/tenants/${DEFAULT_TENANT}/workspaces`, { name: name(`ws-${k}`) }), `create workspace ${k}`);
    fx.workspaces[k] = w.id; fx.created.workspaces.push(w.id); log(`workspace ${k} created`);
  }
  async function user(key, workspace, role) {
    const username = name(key.toLowerCase()).toLowerCase().replace(/[^a-z0-9._-]/g, "-").slice(0, 40);
    const link = need(await admin.post("/admin/users", { username, displayName: `E2E ${key} ${runId}`, workspaceId: fx.workspaces[workspace], role }), `create user ${key}`);
    const password = randomSecret();
    need(await new Session(cfg.studio, "activation").post("/auth/activation/complete", { token: link.token, password }), `activate user ${key}`);
    const s = new Session(cfg.studio, key); await s.login(username, password);
    fx.users[key] = { id: link.userId, username, password, workspace: fx.workspaces[workspace], role }; fx.sessions[key] = s; fx.created.users.push(link.userId);
    log(`user ${key} (${role}) ready`);
  }
  await user("adminA", "A", "WORKSPACE_ADMIN");
  await user("adminB", "B", "WORKSPACE_ADMIN");
  await user("viewerA", "A", "VIEWER");
  await user("lonelyA", "A", "VIEWER");           // a member of workspace A with NO membership of project A: no APP_VIEW anywhere (E2E-04 case B)

  // project A: owned by adminA; viewerA is a VIEWER of the workspace, which gives no project membership by itself → add it as project VIEWER
  const A = fx.sessions.adminA, wa = fx.workspaces.A;
  const pa = need(await A.post(`/workspaces/${wa}/projects`, { name: name("proj-A"), description: `secret-description-${runId}`, appType: "PAGE_SCHEMA" }), "create project A");
  fx.projects.A = pa; fx.created.projects.push({ workspaceId: wa, projectId: pa.id, owner: "adminA" });
  const addViewer = await A.post(`/workspaces/${wa}/projects/${pa.id}/members`, { username: fx.users.viewerA.username, role: "VIEWER" });
  fx.notes.viewerOnProject = addViewer.status < 300 ? "member" : `not-added (${addViewer.status} ${addViewer.body?.code ?? ""})`;
  const pb = need(await fx.sessions.adminB.post(`/workspaces/${fx.workspaces.B}/projects`, { name: name("proj-B"), appType: "PAGE_SCHEMA" }), "create project B");
  fx.projects.B = pb; fx.created.projects.push({ workspaceId: fx.workspaces.B, projectId: pb.id, owner: "adminB" });

  const schema = need(await A.get(`/workspaces/${wa}/projects/${pa.id}/schema`), "read schema A");
  fx.notes.initialRevision = schema.revision;
  // definition: one START_WORKFLOW action and one END-only workflow (typed V2 operations). NOTIFY is not used: ActionNotifyPort is not wired (501 NOT_IMPLEMENTED even in TEST).
  // If the server refuses the operations, that exact answer is kept: flows that need them are BLOCKED with it.
  // A UI-bound run needs a declared trigger (D-C4-10): the browser route always runs an action as a UI event, otherwise UNKNOWN_ACTION.
  const sectionId = schema.schema?.sections?.[0]?.id ?? schema.schema?.pages?.[0]?.sections?.[0]?.id;
  fx.ids.sectionId = sectionId;
  const ops = [
    { type: "ADD_WORKFLOW_REF", definition: { id: "e2e-wf", name: "E2E workflow", trigger: "MANUAL", steps: [{ id: "end", kind: "END" }] } },
    { type: "ADD_ACTION", definition: e2eActionDefinition(sectionId) },
  ];
  const patched = await A.patch(`/workspaces/${wa}/projects/${pa.id}/schema`, { expectedRevision: schema.revision, operations: ops, summary: "e2e fixture: action + workflow" });
  fx.notes.definitionOps = patched.status === 200 ? { ok: true } : { ok: false, status: patched.status, code: patched.body?.code ?? null, message: patched.body?.message ?? null };
  fx.ids = { ...fx.ids, actionId: E2E_ACTION_ID, workflowId: "e2e-wf" };
  return fx;
}

/** Best effort and idempotent: removes what this run created. Failures are reported, never thrown (a failed cleanup must not hide a test result). */
export async function cleanup(fx, log = () => {}) {
  const problems = [];
  if (!fx) return problems;
  // bindings first (a bound source cannot be deleted: 409), then sources, then the projects they belong to
  for (const b of [...(fx.created.bindings ?? [])].reverse()) {
    const s = fx.sessions[b.owner]; if (!s) continue;
    const r = await s.del(`/workspaces/${b.workspaceId}/projects/${b.projectId}/data-bindings/${b.mode}/${encodeURIComponent(b.slotId)}`);
    if (r.status >= 300 && r.status !== 404) problems.push(`unbind ${b.mode}/${b.slotId}: ${r.status} ${r.body?.code ?? ""}`);
  }
  for (const d of [...(fx.created.dataSources ?? [])].reverse()) {
    const s = fx.sessions[d.owner]; if (!s) continue;
    const r = await s.del(`/workspaces/${d.workspaceId}/data-sources/${encodeURIComponent(d.id)}`);
    if (r.status >= 300 && r.status !== 404) problems.push(`delete data source ${d.id}: ${r.status} ${r.body?.code ?? ""}`);
  }
  for (const p of [...fx.created.projects].reverse()) {
    const s = fx.sessions[p.owner]; if (!s) continue;
    const cur = await s.get(`/workspaces/${p.workspaceId}/projects/${p.projectId}`);
    if (cur.status === 200) {
      const r = await s.del(`/workspaces/${p.workspaceId}/projects/${p.projectId}?expectedRevision=${cur.body.revision}`);
      if (r.status >= 300) problems.push(`delete project ${p.projectId}: ${r.status} ${r.body?.code ?? ""}`);
    }
  }
  const admin = fx.sessions.admin;
  for (const id of fx.created.users) {
    const r = await admin?.patch(`/admin/users/${id}/status`, { enabled: false });
    if (r && r.status >= 300) problems.push(`disable user ${id}: ${r.status}`);
    await admin?.post(`/admin/users/${id}/revoke-sessions`, {});
  }
  for (const id of [...(fx.created.tenants ?? [])].reverse()) {
    const r = await admin?.patch(`/admin/tenants/${id}/status`, { status: "DELETED" });
    if (r && r.status >= 300 && r.status !== 404) problems.push(`delete tenant ${id}: ${r.status} ${r.body?.code ?? ""}`);
  }
  if (fx.created.workspaces.length) problems.push(`left behind (no delete route): workspaces ${fx.created.workspaces.join(", ")} named e2e-${fx.runId}-*`);
  log(problems.length ? `cleanup notes: ${problems.join("; ")}` : "cleanup clean");
  return problems;
}
