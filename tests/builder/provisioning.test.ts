// @class: unit — user provisioning: capability table, adapter (no invented routes, NOT_READY never succeeds), plan per scope (no role names), validation, error mapping by code
import test from "node:test";
import assert from "node:assert/strict";
import type { Me } from "@xweb/types";
import { adminScope } from "../../features/admin/adminModel";
import { CAPABILITIES, ProvisioningNotReady, createProvisioningApi, type CapabilityId, type CapabilityState, type ProvisioningTransport } from "../../features/admin/provisioning";
import { ACCOUNT_TYPES, emptyAccountForm, provisioningPlan, provisioningProblem, validateAccountForm } from "../../features/admin/provisioningModel";

const me = (o: Partial<Me> = {}): Me => ({ id: "u1", username: "a", displayName: "A", roles: [], workspaces: [], ...o });
const link = (u = "new-user") => ({ userId: "id-1", username: u, displayName: "Tên", purpose: "ACTIVATION" as const, token: "t", expiresAt: "2026-10-08T00:00:00Z" });
function transport() {
  const calls: [string, unknown[]][] = [];
  const rec = <T,>(n: string, v: T) => (...a: unknown[]) => { calls.push([n, a]); return Promise.resolve(v); };
  const t: ProvisioningTransport = {
    createUser: rec("createUser", link()), setTenantMember: rec("setTenantMember", { tenantId: "t", userId: "u", role: "MEMBER", active: true }), changeWorkspaceMember: rec("changeWorkspaceMember", {} as never),
    addWorkspaceMember: rec("addWorkspaceMember", {} as never), tenantMemberCandidates: rec("tenantMemberCandidates", []), activationLink: rec("activationLink", link()), setUserStatus: rec("setUserStatus", {}),
  };
  return { t, calls };
}
const acc = { username: " Bao.Nguyen ", displayName: " Bảo ", email: " ", workspaceId: "w1", workspaceRole: "EDITOR" as const };

test("capability table: what exists is READY with its route; what does not is NOT_READY with an owner and a reason", () => {
  const ready: CapabilityId[] = ["createPlatformUser", "assignTenantRole", "assignWorkspaceRole", "addWorkspaceMember", "listMemberCandidates", "resetCredential", "enableUser", "disableUser"];
  for (const id of ready) { const c = CAPABILITIES[id]; assert.equal(c.status, "READY", id); assert.match((c as { route: string }).route, /^(GET|POST|PUT|PATCH) \/api\/v1\//); }
  for (const id of ["createTenantUser", "inviteTenantUser"] as const) { const c = CAPABILITIES[id] as Extract<CapabilityState, { status: "NOT_READY" }>; assert.equal(c.status, "NOT_READY"); assert.equal(c.owner, "C1"); assert.ok(c.reason.length > 20); }
  assert.deepEqual(CAPABILITIES.createPlatformUser.needs, ["platformScope"]); assert.deepEqual(CAPABILITIES.addWorkspaceMember.needs, ["MEMBER_MANAGE"]);
});
test("adapter createPlatformUser: ONE existing route, body normalised, tenant-admin role reported as PENDING (never as done)", async () => {
  const { t, calls } = transport(); const api = createProvisioningApi(t);
  const r = await api.createPlatformUser(acc, { tenantAdminOf: { id: "t1", name: "Acme" } });
  assert.deepEqual(calls, [["createUser", [{ username: "bao.nguyen", displayName: "Bảo", workspaceId: "w1", role: "EDITOR" }]]], "no email key when blank; lowercased; no tenant in the body");
  assert.deepEqual(r.pending.map((p) => p.id), ["activate", "assignTenantRole"]); assert.match(r.pending[1].label, /Acme/); assert.equal(r.user.id, "id-1");
  assert.equal((await createProvisioningApi(transport().t).createPlatformUser(acc)).pending.length, 1);
});
test("adapter: NOT_READY capabilities throw ProvisioningNotReady and send NOTHING", async () => {
  const { t, calls } = transport(); const api = createProvisioningApi(t);
  await assert.rejects(() => api.createTenantUser(acc), (e: unknown) => e instanceof ProvisioningNotReady && e.code === "PROVISIONING_NOT_READY" && e.capability === "createTenantUser" && e.owner === "C1");
  await assert.rejects(() => api.inviteTenantUser({ email: "a@b.vn", workspaceId: "w", workspaceRole: "VIEWER" }), ProvisioningNotReady);
  assert.equal(calls.length, 0);
});
test("adapter: the other capabilities are thin passes to the existing calls, with the same arguments", async () => {
  const { t, calls } = transport(); const api = createProvisioningApi(t);
  await api.assignTenantRole("t1", "u1", "TENANT_ADMIN"); await api.assignWorkspaceRole("w1", "u1", "VIEWER"); await api.addWorkspaceMember("w1", { username: "x" }, "EDITOR");
  await api.listMemberCandidates("t1", "ab"); await api.resetCredential("u1"); await api.enableUser("u1"); await api.disableUser("u1");
  assert.deepEqual(calls.map((c) => c[0]), ["setTenantMember", "changeWorkspaceMember", "addWorkspaceMember", "tenantMemberCandidates", "activationLink", "setUserStatus", "setUserStatus"]);
  assert.deepEqual(calls[5][1], ["u1", true]); assert.deepEqual(calls[6][1], ["u1", false]); assert.deepEqual(calls[0][1], ["t1", "u1", "TENANT_ADMIN"]);
});
test("wiring C1's contract later = flip ONE table entry: createTenantUser becomes a real call, the screens do not change", async () => {
  const { t, calls } = transport();
  const api = createProvisioningApi(t, { ...CAPABILITIES, createTenantUser: { status: "READY", needs: ["TENANT_MEMBERS"], route: "POST /api/v1/admin/tenants/{tenantId}/users" } });
  assert.equal(api.state("createTenantUser").status, "READY"); await api.createTenantUser(acc); assert.equal(calls.length, 1);
});

test("plan by SCOPE (no role names): SYSTEM_ADMIN → ready, may choose the tenant, three types and NEVER a SYSTEM_ADMIN type", () => {
  const p = provisioningPlan(adminScope(me({ platformScope: true })), "platform", (id) => CAPABILITIES[id]);
  assert.equal(p.create.state, "ready"); assert.equal(p.tenantChoice, true); assert.equal(p.fixedTenant, null);
  assert.deepEqual(p.accountTypes.map((t) => t.id), ["TENANT_ADMIN", "WORKSPACE_ADMIN", "USER"]); assert.ok(!JSON.stringify(p).includes("SYSTEM_ADMIN"));
});
test("plan: a tenant admin → create is NOT_READY with the reason, tenant FIXED from the session, no tenant-admin type, no tenant choice", () => {
  const scope = adminScope(me({ platformScope: false, tenantId: "t1", permissions: ["TENANT_MEMBERS"], tenants: [{ id: "t1", slug: "a", name: "Acme", status: "ACTIVE", role: "TENANT_ADMIN" }, { id: "t2", slug: "b", name: "Other", status: "ACTIVE", role: "MEMBER" }] }));
  const p = provisioningPlan(scope, "admin", (id) => CAPABILITIES[id]);
  assert.equal(p.create.state, "not-ready"); assert.match((p.create as { reason: string }).reason, /chưa có API/); assert.deepEqual(p.fixedTenant, { id: "t1", name: "Acme" }); assert.equal(p.tenantChoice, false);
  assert.deepEqual(p.accountTypes.map((t) => t.id), ["WORKSPACE_ADMIN", "USER"]); assert.ok(!JSON.stringify(p).includes("t2"), "no other tenant is ever offered");
  const ready = provisioningPlan(scope, "admin", (id) => (id === "createTenantUser" ? { status: "READY", needs: ["TENANT_MEMBERS"], route: "x" } : CAPABILITIES[id]));
  assert.equal(ready.create.state, "ready");
});
test("plan: a workspace admin (MEMBER_MANAGE only) cannot create, can add an existing member; a plain member has neither", () => {
  const wsAdmin = provisioningPlan(adminScope(me({ platformScope: false, workspaces: [{ id: "w", name: "W", role: "x", permissions: ["MEMBER_MANAGE"] }] })), "admin", (id) => CAPABILITIES[id]);
  assert.equal(wsAdmin.create.state, "forbidden"); assert.equal(wsAdmin.addExisting.available, true); assert.deepEqual(wsAdmin.accountTypes, []);
  const none = provisioningPlan(adminScope(me({ platformScope: false, workspaces: [{ id: "w", name: "W", role: "WORKSPACE_ADMIN", permissions: ["APP_VIEW"] }] })), "admin", (id) => CAPABILITIES[id]);
  assert.equal(none.create.state, "forbidden"); assert.equal(none.addExisting.available, false, "a role NAME grants nothing");
});

test("validation: username, email, tenant (tenant-admin type, SYSTEM_ADMIN), workspace, role", () => {
  const plan = provisioningPlan(adminScope(me({ platformScope: true })), "platform", (id) => CAPABILITIES[id]);
  const ok = { ...emptyAccountForm("USER"), username: "bao.nguyen", displayName: "Bảo", workspaceId: "w1" };
  assert.deepEqual(validateAccountForm(ok, plan), {});
  assert.ok(validateAccountForm({ ...ok, username: "ab" }, plan).username); assert.ok(validateAccountForm({ ...ok, username: "Bad Name" }, plan).username); assert.ok(validateAccountForm({ ...ok, username: "oidc-x1" }, plan).username);
  assert.deepEqual(validateAccountForm({ ...ok, username: " BAO.NGUYEN " }, plan), {}, "trimmed and lowercased like the server");
  assert.ok(validateAccountForm({ ...ok, displayName: " " }, plan).displayName); assert.ok(validateAccountForm({ ...ok, email: "no-at" }, plan).email); assert.deepEqual(validateAccountForm({ ...ok, email: "a@b.vn" }, plan), {});
  assert.ok(validateAccountForm({ ...ok, workspaceId: "" }, plan).workspace);
  assert.ok(validateAccountForm({ ...ok, type: "TENANT_ADMIN", role: "WORKSPACE_ADMIN", tenantId: "" }, plan).tenant); assert.deepEqual(validateAccountForm({ ...ok, type: "TENANT_ADMIN", role: "WORKSPACE_ADMIN", tenantId: "t1" }, plan), {});
  assert.ok(validateAccountForm({ ...ok, type: "USER", role: "WORKSPACE_ADMIN" }, plan).role);
  const tenantAdminPlan = provisioningPlan(adminScope(me({ platformScope: false, tenantId: "t", permissions: ["TENANT_MEMBERS"], tenants: [{ id: "t", slug: "s", name: "N", status: "ACTIVE", role: "TENANT_ADMIN" }] })), "admin", (id) => CAPABILITIES[id]);
  assert.ok(validateAccountForm({ ...ok, type: "TENANT_ADMIN", role: "WORKSPACE_ADMIN" }, tenantAdminPlan).type, "a tenant admin cannot create a tenant admin from this form");
  assert.deepEqual(Object.keys(ACCOUNT_TYPES), ["TENANT_ADMIN", "WORKSPACE_ADMIN", "USER"]);
});
test("errors are shown BY CODE: duplicates on their field, 403, mismatch, protections, disabled, unavailable, NOT_READY — never the generic text when a code exists", () => {
  const k = (code: string, status = 0) => provisioningProblem({ code, status });
  assert.deepEqual([k("USERNAME_TAKEN", 409).kind, k("USERNAME_TAKEN", 409).field], ["duplicate", "username"]); assert.deepEqual([k("EMAIL_TAKEN", 409).kind, k("EMAIL_TAKEN", 409).field], ["duplicate", "email"]);
  assert.equal(k("INVALID_USERNAME", 400).field, "username"); assert.equal(k("INVALID_EMAIL", 400).field, "email");
  assert.equal(k("ADMIN_REQUIRED", 403).kind, "forbidden"); assert.equal(k("PERMISSION_DENIED", 403).kind, "forbidden"); assert.equal(provisioningProblem({ status: 403 }).kind, "forbidden");
  assert.equal(k("WORKSPACE_NOT_FOUND", 404).kind, "mismatch"); assert.equal(k("WORKSPACE_NOT_FOUND", 404).field, "workspace"); assert.equal(k("USER_NOT_FOUND", 404).kind, "mismatch"); assert.equal(provisioningProblem({ status: 404 }).kind, "mismatch");
  for (const c of ["LAST_TENANT_ADMIN", "LAST_ADMIN", "LAST_SYSTEM_ADMIN", "SELF_GRANT_FORBIDDEN", "CANNOT_CHANGE_SELF"]) assert.equal(k(c, 409).kind, "protection", c);
  assert.equal(k("ACCOUNT_DISABLED", 409).kind, "disabled");
  assert.equal(provisioningProblem({ status: 0 }).kind, "unavailable"); assert.match(provisioningProblem({ status: 0 }).text, /Chưa rõ/); assert.equal(provisioningProblem({ status: 503 }).kind, "unavailable");
  assert.equal(provisioningProblem({ code: "PROVISIONING_NOT_READY", reason: "chưa có API" }).kind, "not-ready");
  for (const c of ["USERNAME_TAKEN", "EMAIL_TAKEN", "ADMIN_REQUIRED", "WORKSPACE_NOT_FOUND", "LAST_ADMIN", "ACCOUNT_DISABLED"]) assert.ok(!/Something went wrong|đã xảy ra lỗi/i.test(k(c, 409).text), c);
});
