// @class: unit — user provisioning on C1's tenant contract (2356d64): capability table, adapter (tenant in the PATH, both-or-nothing workspace, NOT_READY sends nothing), plan by scope (no role names), validation, every C1 error code
import test from "node:test";
import assert from "node:assert/strict";
import type { Me } from "@xweb/types";
import { adminScope } from "../../features/admin/adminModel";
import { CAPABILITIES, ProvisioningNotReady, createProvisioningApi, type CapabilityId, type ProvisioningTransport } from "../../features/admin/provisioning";
import { ACCOUNT_TYPES, emptyAccountForm, provisioningPlan, provisioningProblem, validateAccountForm } from "../../features/admin/provisioningModel";

const me = (o: Partial<Me> = {}): Me => ({ id: "u1", username: "a", displayName: "A", roles: [], workspaces: [], ...o });
const link = (u = "new-user") => ({ userId: "id-1", username: u, displayName: "Tên", purpose: "ACTIVATION" as const, token: "TOKEN", expiresAt: "2026-10-08T00:00:00Z" });
function transport() {
  const calls: [string, unknown[]][] = [];
  const rec = <T,>(n: string, v: T) => (...a: unknown[]) => { calls.push([n, a]); return Promise.resolve(v); };
  const t: ProvisioningTransport = {
    createTenantUser: rec("createTenantUser", link()), createTenantWorkspace: rec("createTenantWorkspace", { id: "w9", name: "WS", tenantId: "t1" }), setTenantMember: rec("setTenantMember", { tenantId: "t", userId: "u", role: "MEMBER", active: true }),
    changeWorkspaceMember: rec("changeWorkspaceMember", {} as never), addWorkspaceMember: rec("addWorkspaceMember", {} as never), tenantMemberCandidates: rec("tenantMemberCandidates", []), activationLink: rec("activationLink", link()), setUserStatus: rec("setUserStatus", {}),
  };
  return { t, calls };
}
const acc = { username: " Bao.Nguyen ", displayName: " Bảo ", email: " ", tenantRole: "MEMBER" as const };
const scopeOf = (o: Partial<Me>) => adminScope(me(o));
const tenant = (id: string, name: string, role = "TENANT_ADMIN") => ({ id, slug: id, name, status: "ACTIVE", role });

test("capability table: every route is a real route of C1's contract; all READY; no capability needs a role name", () => {
  const ids = Object.keys(CAPABILITIES) as CapabilityId[];
  assert.deepEqual(ids.sort(), ["addWorkspaceMember", "assignTenantRole", "assignWorkspaceRole", "createTenantUser", "createTenantWorkspace", "disableUser", "enableUser", "listMemberCandidates", "resetCredential"]);
  for (const id of ids) { const c = CAPABILITIES[id]; assert.equal(c.status, "READY", id); assert.match((c as { route: string }).route, /^(GET|POST|PUT|PATCH) \/api\/v1\//); assert.ok(c.needs.every((n) => ["platformScope", "TENANT_MEMBERS", "TENANT_MANAGE", "MEMBER_MANAGE"].includes(n))); }
  assert.equal((CAPABILITIES.createTenantUser as { route: string }).route, "POST /api/v1/admin/tenants/{tenantId}/users"); assert.deepEqual(CAPABILITIES.createTenantUser.needs, ["TENANT_MEMBERS"]);
  assert.equal((CAPABILITIES.createTenantWorkspace as { route: string }).route, "POST /api/v1/admin/tenants/{tenantId}/workspaces"); assert.ok(!JSON.stringify(CAPABILITIES).includes("/admin/workspaces\""), "the legacy POST /admin/workspaces is not used");
});
test("adapter createTenantUser: the tenant is the PATH argument only; body normalised; workspace and role go together; token only in the returned link", async () => {
  const { t, calls } = transport(); const api = createProvisioningApi(t);
  const r = await api.createTenantUser("t1", { ...acc, tenantRole: "TENANT_ADMIN", workspace: { id: "w1", role: "WORKSPACE_ADMIN" } });
  assert.deepEqual(calls, [["createTenantUser", ["t1", { username: "bao.nguyen", displayName: "Bảo", tenantRole: "TENANT_ADMIN", workspaceId: "w1", workspaceRole: "WORKSPACE_ADMIN" }]]], "no email key when blank, no tenant key in the body");
  assert.equal(r.tenantId, "t1"); assert.equal(r.user.id, "id-1"); assert.deepEqual(r.pending.map((p) => p.id), ["activate"]); assert.equal(r.activation?.token, "TOKEN");
  assert.ok(!JSON.stringify({ ...r, activation: undefined }).includes("TOKEN"), "the token is not copied anywhere else in the result");
  const n = await createProvisioningApi(transport().t).createTenantUser("t1", { ...acc, email: "A@B.vn" }); assert.equal(n.workspace, null);
  const b = transport(); await createProvisioningApi(b.t).createTenantUser("t1", { ...acc, email: "a@b.vn" });
  assert.deepEqual(Object.keys((b.calls[0][1] as [string, object])[1]).sort(), ["displayName", "email", "tenantRole", "username"], "no workspace key at all when none is chosen");
});
test("adapter: a NOT_READY capability (the mechanism stays) throws ProvisioningNotReady and sends NOTHING", async () => {
  const { t, calls } = transport();
  const api = createProvisioningApi(t, { ...CAPABILITIES, createTenantUser: { status: "NOT_READY", needs: ["TENANT_MEMBERS"], owner: "C1", reason: "no route" } });
  await assert.rejects(() => api.createTenantUser("t1", acc), (e: unknown) => e instanceof ProvisioningNotReady && e.code === "PROVISIONING_NOT_READY" && e.capability === "createTenantUser");
  assert.equal(calls.length, 0);
});
test("adapter: the other capabilities are thin passes to the existing calls with the same arguments (workspace creation is the TENANT route)", async () => {
  const { t, calls } = transport(); const api = createProvisioningApi(t);
  assert.deepEqual(await api.createTenantWorkspace("t1", " Kinh doanh "), { id: "w9", name: "WS", tenantId: "t1" });
  await api.assignTenantRole("t1", "u1", "TENANT_ADMIN"); await api.assignWorkspaceRole("w1", "u1", "VIEWER"); await api.addWorkspaceMember("w1", { username: "x" }, "EDITOR");
  await api.listMemberCandidates("t1", "ab"); await api.resetCredential("u1"); await api.enableUser("u1"); await api.disableUser("u1");
  assert.deepEqual(calls.map((c) => c[0]), ["createTenantWorkspace", "setTenantMember", "changeWorkspaceMember", "addWorkspaceMember", "tenantMemberCandidates", "activationLink", "setUserStatus", "setUserStatus"]);
  assert.deepEqual(calls[0][1], ["t1", "Kinh doanh"]); assert.deepEqual(calls[6][1], ["u1", true]); assert.deepEqual(calls[7][1], ["u1", false]);
});

test("plan by SCOPE (no role names): SYSTEM_ADMIN → ready, any tenant, three types, can create a workspace, NEVER a SYSTEM_ADMIN type", () => {
  const p = provisioningPlan(scopeOf({ platformScope: true }), "platform", (id) => CAPABILITIES[id]);
  assert.equal(p.create.state, "ready"); assert.equal(p.tenantChoice, true); assert.equal(p.fixedTenant, null); assert.equal(p.canCreateWorkspace, true);
  assert.deepEqual(p.accountTypes.map((t) => t.id), ["TENANT_ADMIN", "WORKSPACE_ADMIN", "USER"]); assert.ok(!JSON.stringify(p).includes("SYSTEM_ADMIN"));
});
test("plan: a tenant admin → ready; ONE tenant is FIXED from the session; several → a choice among THEIR OWN only; never another tenant", () => {
  const one = provisioningPlan(scopeOf({ platformScope: false, tenantId: "t1", permissions: ["TENANT_MEMBERS"], tenants: [tenant("t1", "Acme"), tenant("t2", "Other", "MEMBER")] }), "admin", (id) => CAPABILITIES[id]);
  assert.equal(one.create.state, "ready"); assert.deepEqual(one.fixedTenant, { id: "t1", name: "Acme" }); assert.equal(one.tenantChoice, false); assert.ok(!JSON.stringify(one).includes("t2"));
  assert.deepEqual(one.accountTypes.map((t) => t.id), ["TENANT_ADMIN", "WORKSPACE_ADMIN", "USER"], "a tenant admin may create tenant admins (C1: TENANT_MEMBERS), still never SYSTEM_ADMIN");
  const two = provisioningPlan(scopeOf({ platformScope: false, tenants: [tenant("t1", "A"), tenant("t3", "C")] }), "admin", (id) => CAPABILITIES[id]);
  assert.equal(two.fixedTenant, null); assert.equal(two.tenantChoice, true);
});
test("plan: a workspace admin (MEMBER_MANAGE only) cannot create accounts, can add an existing member; a role NAME grants nothing", () => {
  const w = provisioningPlan(scopeOf({ platformScope: false, workspaces: [{ id: "w", name: "W", role: "x", permissions: ["MEMBER_MANAGE"] }] }), "admin", (id) => CAPABILITIES[id]);
  assert.equal(w.create.state, "forbidden"); assert.equal(w.addExisting.available, true); assert.deepEqual(w.accountTypes, []); assert.equal(w.canCreateWorkspace, false);
  const none = provisioningPlan(scopeOf({ platformScope: false, workspaces: [{ id: "w", name: "W", role: "WORKSPACE_ADMIN", permissions: ["APP_VIEW"] }] }), "admin", (id) => CAPABILITIES[id]);
  assert.equal(none.create.state, "forbidden"); assert.equal(none.addExisting.available, false);
});

test("validation: username, email, tenant, workspace (required only for the workspace-admin type), role, a new workspace needs a name", () => {
  const plan = provisioningPlan(scopeOf({ platformScope: true }), "platform", (id) => CAPABILITIES[id]);
  const ok = { ...emptyAccountForm("USER"), username: "bao.nguyen", displayName: "Bảo", tenantId: "t1" };
  assert.deepEqual(validateAccountForm(ok, plan), {}, "a user with NO workspace is valid");
  assert.ok(validateAccountForm({ ...ok, username: "ab" }, plan).username); assert.ok(validateAccountForm({ ...ok, username: "Bad Name" }, plan).username); assert.ok(validateAccountForm({ ...ok, username: "oidc-x1" }, plan).username);
  assert.deepEqual(validateAccountForm({ ...ok, username: " BAO.NGUYEN " }, plan), {});
  assert.ok(validateAccountForm({ ...ok, displayName: " " }, plan).displayName); assert.ok(validateAccountForm({ ...ok, email: "no-at" }, plan).email); assert.deepEqual(validateAccountForm({ ...ok, email: "a@b.vn" }, plan), {});
  assert.ok(validateAccountForm({ ...ok, tenantId: "" }, plan).tenant, "a SYSTEM_ADMIN must choose the tenant for every type");
  assert.ok(validateAccountForm({ ...emptyAccountForm("WORKSPACE_ADMIN"), username: "abc", displayName: "x", tenantId: "t1" }, plan).workspace, "workspace admin needs a workspace");
  assert.deepEqual(validateAccountForm({ ...emptyAccountForm("WORKSPACE_ADMIN"), username: "abc", displayName: "x", tenantId: "t1", workspaceId: "w1" }, plan), {});
  assert.deepEqual(validateAccountForm({ ...emptyAccountForm("TENANT_ADMIN"), username: "abc", displayName: "x", tenantId: "t1" }, plan), {}, "tenant admin: workspace optional");
  assert.ok(validateAccountForm({ ...ok, type: "USER", workspaceId: "w1", role: "WORKSPACE_ADMIN" }, plan).role, "a user type cannot take the admin workspace role");
  assert.ok(validateAccountForm({ ...ok, workspaceId: "__new" }, plan).workspace); assert.deepEqual(validateAccountForm({ ...ok, workspaceId: "__new" }, plan, { newWorkspaceName: "Kinh doanh" }), {});
  const fixed = provisioningPlan(scopeOf({ platformScope: false, tenantId: "t1", permissions: ["TENANT_MEMBERS"], tenants: [tenant("t1", "Acme")] }), "admin", (id) => CAPABILITIES[id]);
  assert.deepEqual(validateAccountForm({ ...ok, tenantId: "" }, fixed), {}, "a fixed tenant needs no choice");
  assert.deepEqual(Object.keys(ACCOUNT_TYPES), ["TENANT_ADMIN", "WORKSPACE_ADMIN", "USER"]); assert.deepEqual([ACCOUNT_TYPES.TENANT_ADMIN.tenantRole, ACCOUNT_TYPES.USER.tenantRole], ["TENANT_ADMIN", "MEMBER"]);
});

test("EVERY code of C1's error contract is shown by its own text and kind (never the generic message)", () => {
  const kinds: Record<string, [number, string, string?]> = {
    INVALID_USERNAME: [400, "validation", "username"], INVALID_EMAIL: [400, "validation", "email"], VALIDATION_FAILED: [400, "validation"], TENANT_ROLE_INVALID: [400, "validation"], INVALID_ROLE: [400, "validation"], QUERY_TOO_SHORT: [400, "validation"],
    AUTHENTICATION_REQUIRED: [401, "forbidden"], FORBIDDEN: [403, "forbidden"], SELF_GRANT_FORBIDDEN: [403, "protection"], ADMIN_REQUIRED: [403, "forbidden"],
    TENANT_NOT_FOUND: [404, "mismatch"], WORKSPACE_NOT_FOUND: [404, "mismatch", "workspace"], USER_NOT_FOUND: [404, "mismatch"], MEMBER_NOT_FOUND: [404, "mismatch"], TENANT_MEMBER_NOT_FOUND: [404, "mismatch"],
    USERNAME_TAKEN: [409, "duplicate", "username"], EMAIL_TAKEN: [409, "duplicate", "email"], LAST_TENANT_ADMIN: [409, "protection"], LAST_ADMIN: [409, "protection"], LAST_OWNER: [409, "protection"], ALREADY_MEMBER: [409, "duplicate"], CANNOT_CHANGE_SELF: [409, "protection"],
    LINK_INVALID: [410, "validation"], USER_DISABLED: [422, "disabled"], NOT_WORKSPACE_MEMBER: [422, "mismatch"],
  };
  const texts = new Set<string>();
  for (const [code, [status, kind, field]] of Object.entries(kinds)) {
    const p = provisioningProblem({ code, status, message: "english server text" });
    assert.equal(p.kind, kind, code); assert.equal(p.field, field, code); assert.ok(!/english server text|Something went wrong|đã xảy ra lỗi/i.test(p.text), `${code} shows its own Vietnamese text`); texts.add(p.text);
  }
  assert.ok(texts.size >= 22, "distinct texts, not one generic message");
  assert.equal(provisioningProblem({ status: 429 }).kind, "unavailable"); assert.equal(provisioningProblem({ status: 0 }).kind, "unavailable"); assert.match(provisioningProblem({ status: 0 }).text, /Chưa rõ/); assert.equal(provisioningProblem({ status: 503 }).kind, "unavailable");
  assert.equal(provisioningProblem({ code: "PROVISIONING_NOT_READY", reason: "chưa có API" }).kind, "not-ready");
});
