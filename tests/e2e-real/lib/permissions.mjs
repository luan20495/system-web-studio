// @class: real-backend — the TEST side of the C1 permission contract. It reads what the SERVER resolved (`/auth/me`, the project payload) and turns it into the expectation of a UI state / a forged call.
// It is a deliberate, tiny mirror of docs/contracts/v2/tenant-permission.md §5 (the 14 codes + the storage alias table) so the expectation comes from the CONTRACT, never from a role name.
export const ALIAS = { PROJECT_READ: "APP_VIEW", PROJECT_EDIT: "APP_EDIT", PROJECT_SETTINGS: "APP_EDIT", PROJECT_PUBLISH: "APP_PUBLISH", PROJECT_MEMBERS: "APP_SHARE" };
export const CODES = ["APP_VIEW", "APP_USE", "APP_EDIT", "APP_PUBLISH", "APP_SHARE", "DATA_SOURCE_VIEW", "DATA_SOURCE_MANAGE", "QUERY_EXECUTE", "DATA_MUTATE", "ACTION_EXECUTE", "WORKFLOW_EXECUTE", "WORKFLOW_MANAGE", "TENANT_MANAGE", "TENANT_MEMBERS"];
export const resolve = (raw) => new Set((raw ?? []).map((p) => (CODES.includes(p) ? p : ALIAS[p])).filter(Boolean));
export const holdsAll = (set, codes) => codes.every((c) => set.has(c));

/** What the contract says a person with this resolved set may do in the Test panel / lifecycle (the task's table). */
export const expectations = (set) => ({
  viewStudio: set.has("APP_VIEW"), readOnly: set.has("APP_VIEW") && !set.has("APP_EDIT"),
  edit: set.has("APP_EDIT"), publish: set.has("APP_PUBLISH"),
  testQuery: holdsAll(set, ["APP_USE", "QUERY_EXECUTE", "APP_EDIT"]),
  testNavigateAction: holdsAll(set, ["APP_USE", "ACTION_EXECUTE", "APP_EDIT"]),
  testWorkflow: holdsAll(set, ["APP_USE", "WORKFLOW_EXECUTE", "APP_EDIT"]),
  viewDataSources: set.has("DATA_SOURCE_VIEW"), manageDataSources: set.has("DATA_SOURCE_MANAGE"),
});

/** the payloads the server resolved for one person on one project: the evidence every permission flow records */
export async function resolvedFor(session, workspaceId, projectId) {
  const me = (await session.get("/auth/me")).body;
  const ws = (me?.workspaces ?? []).find((w) => w.id === workspaceId);
  const project = await session.get(`/workspaces/${workspaceId}/projects/${projectId}`);
  return {
    me: { permissions: me?.permissions ?? [], workspace: ws?.permissions ?? null, workspaceListed: !!ws },
    project: { status: project.status, permissions: project.body?.permissions ?? null },
    resolvedMe: resolve([...(me?.permissions ?? []), ...(ws?.permissions ?? [])]),
    resolvedProject: resolve(project.body?.permissions),
  };
}
