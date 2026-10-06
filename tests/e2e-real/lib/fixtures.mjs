// @class: real-backend — per-run fixtures, created ONLY through the product's own HTTP API (no SQL, no direct DB, no server key material).
//   tenant            : the stack's default tenant. Tenant provisioning is a platform (C1) action, not exercised here → cross-TENANT isolation is C1's backend tests.
//   workspace A / B   : POST /admin/workspaces
//   users             : adminA (WORKSPACE_ADMIN in A), adminB (WORKSPACE_ADMIN in B), viewerA (VIEWER in A); activated with a RANDOM per-run password kept in memory
//   project / page / component / action / workflow : created as adminA through the normal routes (PATCH /schema operations)
//   datasource / credential / TEST|LIVE binding : created through C3's Management API (docs/parallel/c3/MANAGEMENT_API.md) by the flows that exercise it (tracked in created.dataSources / created.bindings and removed by cleanup())
//   query / mutation / declared slot             : NOT creatable over HTTP (no management endpoint for queries, no AppDefinition op for dataSources[]) → only used when the operator pre-seeded them (E2E_DATA_*)
//   publish config    : PATCH /schema UPDATE_PUBLISH_CONFIG is attempted by the publish flow only
// Every created thing is named `e2e-<runId>-…` and removed/disabled by cleanup(). No production data is read or written.
import { Session, randomSecret } from "./api.mjs";

const need = (r, what) => { if (r.status < 200 || r.status >= 300) throw new Error(`fixture: ${what} failed (${r.status} ${r.body?.code ?? r.error ?? ""})`); return r.body; };

export async function createFixtures(cfg, log = () => {}) {
  const runId = cfg.runId, name = (s) => `e2e-${runId}-${s}`;
  const fx = { runId, created: { users: [], projects: [], workspaces: [], dataSources: [], bindings: [] }, sessions: {}, users: {}, workspaces: {}, projects: {}, ids: {}, notes: {} };

  const admin = new Session(cfg.studio, "admin");
  fx.me = await admin.login(cfg.adminUser, cfg.adminPassword);
  fx.sessions.admin = admin;
  if (!fx.me?.systemAdmin && !fx.me?.platformScope) throw new Error("fixture: E2E_ADMIN_USER is not a system admin (cannot create workspaces/users)");

  for (const k of ["A", "B"]) {
    const w = need(await admin.post("/admin/workspaces", { name: name(`ws-${k}`) }), `create workspace ${k}`);
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

  // project A: owned by adminA; viewerA is a VIEWER of the workspace, which gives no project membership by itself → add it as project VIEWER
  const A = fx.sessions.adminA, wa = fx.workspaces.A;
  const pa = need(await A.post(`/workspaces/${wa}/projects`, { name: name("proj-A"), description: `secret-description-${runId}`, appType: "PAGE_SCHEMA" }), "create project A");
  fx.projects.A = pa; fx.created.projects.push({ workspaceId: wa, projectId: pa.id, owner: "adminA" });
  const addViewer = await A.post(`/workspaces/${wa}/projects/${pa.id}/members`, { username: fx.users.viewerA.username, role: "VIEWER" });
  fx.notes.viewerOnProject = addViewer.status < 300 ? "member" : `not-added (${addViewer.status} ${addViewer.body?.code ?? ""})`;
  const pb = need(await fx.sessions.adminB.post(`/workspaces/${fx.workspaces.B}/projects`, { name: name("proj-B"), appType: "PAGE_SCHEMA" }), "create project B");
  fx.projects.B = pb; fx.created.projects.push({ workspaceId: fx.workspaces.B, projectId: pb.id, owner: "adminB" });

  // definition: one NOTIFY action and one END-only workflow (typed V2 operations). If the server refuses them, that exact answer is kept: flows that need them are BLOCKED with it.
  const schema = need(await A.get(`/workspaces/${wa}/projects/${pa.id}/schema`), "read schema A");
  fx.notes.initialRevision = schema.revision;
  const ops = [
    { type: "ADD_ACTION", definition: { id: "e2e-notify", name: "E2E thông báo", type: "NOTIFY", channel: "IN_APP", templateRef: "e2e-template" } },
    { type: "ADD_WORKFLOW_REF", definition: { id: "e2e-wf", name: "E2E workflow", trigger: "MANUAL", steps: [{ id: "end", kind: "END" }] } },
  ];
  const patched = await A.patch(`/workspaces/${wa}/projects/${pa.id}/schema`, { expectedRevision: schema.revision, operations: ops, summary: "e2e fixture: action + workflow" });
  fx.notes.definitionOps = patched.status === 200 ? { ok: true } : { ok: false, status: patched.status, code: patched.body?.code ?? null, message: patched.body?.message ?? null };
  fx.ids = { actionId: "e2e-notify", workflowId: "e2e-wf" };
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
  if (fx.created.workspaces.length) problems.push(`left behind (no delete route): workspaces ${fx.created.workspaces.join(", ")} named e2e-${fx.runId}-*`);
  log(problems.length ? `cleanup notes: ${problems.join("; ")}` : "cleanup clean");
  return problems;
}
