/**
 * User provisioning — the frontend contract, written BEFORE C1's provisioning contract is final.
 *
 * One interface (`ProvisioningApi`) is what the screens call. Its production implementation maps ONLY routes that exist on integration/v2 today
 * (verified in the backend source: `AccountController`, `TenantController`, `MemberController`); every capability whose route C1 has not delivered is
 * `NOT_READY` and the adapter throws `ProvisioningNotReady` — nothing is invented, nothing pretends to succeed. When C1 delivers a contract, only the
 * `READY_ROUTES` table and the matching adapter method change; the screens, the validation and the tests stay.
 *
 * Authority stays on the server: `needs` is the canonical capability the SERVER lists for the caller (`platformScope`, TENANT_MEMBERS, MEMBER_MANAGE);
 * the UI uses it only to decide what to offer. No role name is read here.
 */
import type { ActivationLink, Member, TenantMemberCandidate, TenantMemberView } from "@xweb/types";

export type CapabilityId =
  | "createPlatformUser" | "createTenantUser" | "inviteTenantUser" | "assignTenantRole" | "assignWorkspaceRole" | "addWorkspaceMember"
  | "listMemberCandidates" | "resetCredential" | "enableUser" | "disableUser";
export type Need = "platformScope" | "TENANT_MEMBERS" | "MEMBER_MANAGE";
export type CapabilityState = { status: "READY"; needs: Need[]; route: string } | { status: "NOT_READY"; needs: Need[]; reason: string; owner: "C1" | "C0" };

/** What exists on integration/v2 @ 1a9995c (+ C1 7ecea1a for the candidate directory). NOT_READY = no such route yet (contract owner C1). */
export const CAPABILITIES: Readonly<Record<CapabilityId, CapabilityState>> = {
  createPlatformUser: { status: "READY", needs: ["platformScope"], route: "POST /api/v1/admin/users" },
  createTenantUser: { status: "NOT_READY", needs: ["TENANT_MEMBERS"], owner: "C1", reason: "Máy chủ chưa có API tạo tài khoản theo công ty: POST /admin/users chỉ dành cho quản trị hệ thống." },
  inviteTenantUser: { status: "NOT_READY", needs: ["TENANT_MEMBERS"], owner: "C1", reason: "Máy chủ chưa có API mời người dùng vào công ty." },
  assignTenantRole: { status: "READY", needs: ["TENANT_MEMBERS"], route: "PUT /api/v1/admin/tenants/{tenantId}/members/{userId}" },
  assignWorkspaceRole: { status: "READY", needs: ["MEMBER_MANAGE"], route: "PATCH /api/v1/workspaces/{workspaceId}/members/{userId}" },
  addWorkspaceMember: { status: "READY", needs: ["MEMBER_MANAGE"], route: "POST /api/v1/workspaces/{workspaceId}/members" },
  listMemberCandidates: { status: "READY", needs: ["TENANT_MEMBERS"], route: "GET /api/v1/admin/tenants/{tenantId}/member-candidates" },
  resetCredential: { status: "READY", needs: ["platformScope"], route: "POST /api/v1/admin/users/{userId}/activation-link" },
  enableUser: { status: "READY", needs: ["platformScope"], route: "PATCH /api/v1/admin/users/{userId}/status" },
  disableUser: { status: "READY", needs: ["platformScope"], route: "PATCH /api/v1/admin/users/{userId}/status" },
};

/** thrown by the adapter for a capability the backend does not have yet; the UI shows `reason` and disables the submit — it never shows success */
export class ProvisioningNotReady extends Error {
  readonly code = "PROVISIONING_NOT_READY";
  constructor(readonly capability: CapabilityId, readonly reason: string, readonly owner: "C1" | "C0") { super(reason); }
}

export type WorkspaceRoleId = "WORKSPACE_ADMIN" | "EDITOR" | "PUBLISHER" | "VIEWER";
export type NewAccount = { username: string; displayName: string; email?: string; workspaceId: string; workspaceRole: WorkspaceRoleId };
/** what was done, and what still has to be done by someone else (never claimed as done) */
export type ProvisionResult = { user: { id: string; username: string; displayName: string }; workspaceId: string; workspaceRole: WorkspaceRoleId; activation: ActivationLink; pending: PendingStep[] };
export type PendingStep = { id: "activate" | "assignTenantRole"; label: string };

export interface ProvisioningApi {
  state(id: CapabilityId): CapabilityState;
  createPlatformUser(a: NewAccount, opts?: { tenantAdminOf?: { id: string; name: string } }): Promise<ProvisionResult>;
  createTenantUser(a: NewAccount): Promise<ProvisionResult>;
  inviteTenantUser(a: { email: string; workspaceId: string; workspaceRole: WorkspaceRoleId }): Promise<void>;
  assignTenantRole(tenantId: string, userId: string, role: "TENANT_ADMIN" | "MEMBER"): Promise<TenantMemberView>;
  assignWorkspaceRole(workspaceId: string, userId: string, role: WorkspaceRoleId): Promise<Member>;
  addWorkspaceMember(workspaceId: string, who: { username?: string; email?: string }, role: WorkspaceRoleId): Promise<Member>;
  listMemberCandidates(tenantId: string, q?: string): Promise<TenantMemberCandidate[]>;
  resetCredential(userId: string): Promise<ActivationLink>;
  enableUser(userId: string): Promise<void>;
  disableUser(userId: string): Promise<void>;
}

/** the calls the production adapter needs (a slice of `api`, so tests can inject a recording fake without a network) */
export type ProvisioningTransport = {
  createUser(b: { username: string; displayName: string; email?: string; workspaceId: string; role: string }): Promise<ActivationLink>;
  setTenantMember(tenantId: string, userId: string, role: string): Promise<TenantMemberView>;
  changeWorkspaceMember(workspaceId: string, userId: string, role: string): Promise<Member>;
  addWorkspaceMember(workspaceId: string, who: { username?: string; email?: string }, role: string): Promise<Member>;
  tenantMemberCandidates(tenantId: string, q?: string): Promise<TenantMemberCandidate[]>;
  activationLink(userId: string): Promise<ActivationLink>;
  setUserStatus(userId: string, enabled: boolean): Promise<unknown>;
};

export function createProvisioningApi(t: ProvisioningTransport, caps: Readonly<Record<CapabilityId, CapabilityState>> = CAPABILITIES): ProvisioningApi {
  const need = (id: CapabilityId): void => { const c = caps[id]; if (c.status === "NOT_READY") throw new ProvisioningNotReady(id, c.reason, c.owner); };
  const createdVia = async (id: CapabilityId, a: NewAccount, tenantAdminOf?: { id: string; name: string }): Promise<ProvisionResult> => {
    need(id);
    const link = await t.createUser({ username: a.username.trim().toLowerCase(), displayName: a.displayName.trim(), ...(a.email?.trim() ? { email: a.email.trim() } : {}), workspaceId: a.workspaceId, role: a.workspaceRole });
    // a tenant role needs an ACTIVATED account (the server refuses to add a never-activated one): it is reported as pending, never as done
    const pending: PendingStep[] = [{ id: "activate", label: "Người dùng mở liên kết kích hoạt và đặt mật khẩu." }];
    if (tenantAdminOf) pending.push({ id: "assignTenantRole", label: `Sau khi kích hoạt: gán “Quản trị công ty” cho ${link.username} ở công ty ${tenantAdminOf.name} (Công ty → Thêm thành viên).` });
    return { user: { id: link.userId, username: link.username, displayName: link.displayName }, workspaceId: a.workspaceId, workspaceRole: a.workspaceRole, activation: link, pending };
  };
  return {
    state: (id) => caps[id],
    createPlatformUser: (a, opts) => createdVia("createPlatformUser", a, opts?.tenantAdminOf),
    createTenantUser: (a) => createdVia("createTenantUser", a),
    inviteTenantUser: async () => { need("inviteTenantUser"); throw new ProvisioningNotReady("inviteTenantUser", "no route", "C1"); },
    assignTenantRole: (tenantId, userId, role) => { need("assignTenantRole"); return t.setTenantMember(tenantId, userId, role); },
    assignWorkspaceRole: (w, u, role) => { need("assignWorkspaceRole"); return t.changeWorkspaceMember(w, u, role); },
    addWorkspaceMember: (w, who, role) => { need("addWorkspaceMember"); return t.addWorkspaceMember(w, who, role); },
    listMemberCandidates: (tenantId, q) => { need("listMemberCandidates"); return t.tenantMemberCandidates(tenantId, q); },
    resetCredential: (u) => { need("resetCredential"); return t.activationLink(u); },
    enableUser: async (u) => { need("enableUser"); await t.setUserStatus(u, true); },
    disableUser: async (u) => { need("disableUser"); await t.setUserStatus(u, false); },
  };
}
