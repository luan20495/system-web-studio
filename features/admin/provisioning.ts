/**
 * User provisioning — the frontend contract, wired to C1's tenant-scoped provisioning API (`docs/parallel/c1/tenant-provisioning-contract.md` @ 2356d64).
 *
 * One interface (`ProvisioningApi`) is what the screens call. `CAPABILITIES` says, per operation, which route backs it and which canonical capability the SERVER must list for the caller
 * (`TENANT_MEMBERS`, `TENANT_MANAGE`, `MEMBER_MANAGE`). A capability the backend does not have is `NOT_READY`: the adapter throws `ProvisioningNotReady` and sends nothing (the mechanism stays for the next
 * missing route). No role name is read here; the server stays the authority on every call.
 *
 * There is no separate "invite" operation: in C1's contract the account IS the invitation (a one-time activation link, no password is accepted or returned).
 */
import type { ActivationLink, Member, TenantMemberCandidate, TenantMemberView } from "@xweb/types";

export type CapabilityId =
  | "createTenantUser" | "createTenantWorkspace" | "assignTenantRole" | "assignWorkspaceRole" | "addWorkspaceMember"
  | "listMemberCandidates" | "resetCredential" | "enableUser" | "disableUser";
export type Need = "platformScope" | "TENANT_MEMBERS" | "TENANT_MANAGE" | "MEMBER_MANAGE";
export type CapabilityState = { status: "READY"; needs: Need[]; route: string } | { status: "NOT_READY"; needs: Need[]; reason: string; owner: "C1" | "C0" };

export const CAPABILITIES: Readonly<Record<CapabilityId, CapabilityState>> = {
  createTenantUser: { status: "READY", needs: ["TENANT_MEMBERS"], route: "POST /api/v1/admin/tenants/{tenantId}/users" },
  createTenantWorkspace: { status: "READY", needs: ["TENANT_MANAGE"], route: "POST /api/v1/admin/tenants/{tenantId}/workspaces" },
  assignTenantRole: { status: "READY", needs: ["TENANT_MEMBERS"], route: "PUT /api/v1/admin/tenants/{tenantId}/members/{userId}" },
  assignWorkspaceRole: { status: "READY", needs: ["MEMBER_MANAGE"], route: "PATCH /api/v1/workspaces/{workspaceId}/members/{userId}" },
  addWorkspaceMember: { status: "READY", needs: ["MEMBER_MANAGE"], route: "POST /api/v1/workspaces/{workspaceId}/members" },
  listMemberCandidates: { status: "READY", needs: ["TENANT_MEMBERS"], route: "GET /api/v1/admin/tenants/{tenantId}/member-candidates" },
  resetCredential: { status: "READY", needs: ["platformScope"], route: "POST /api/v1/admin/users/{userId}/activation-link" },
  enableUser: { status: "READY", needs: ["platformScope"], route: "PATCH /api/v1/admin/users/{userId}/status" },
  disableUser: { status: "READY", needs: ["platformScope"], route: "PATCH /api/v1/admin/users/{userId}/status" },
};

/** thrown by the adapter for a capability the backend does not have; the UI shows `reason` and disables the submit — it never shows success */
export class ProvisioningNotReady extends Error {
  readonly code = "PROVISIONING_NOT_READY";
  constructor(readonly capability: CapabilityId, readonly reason: string, readonly owner: "C1" | "C0") { super(reason); }
}

export type WorkspaceRoleId = "WORKSPACE_ADMIN" | "EDITOR" | "PUBLISHER" | "VIEWER";
export type TenantRoleId = "MEMBER" | "TENANT_ADMIN";
/** `workspace` is both-or-nothing, like the server's rule (VALIDATION_FAILED otherwise) */
export type NewAccount = { username: string; displayName: string; email?: string; tenantRole: TenantRoleId; workspace?: { id: string; role: WorkspaceRoleId } };
/**
 * What was done. The activation link is returned ONCE and lives only in the state of the dialog that created it: it is never written to storage, logged or put in a URL by this module.
 * Nothing is left "pending" but the person's own activation.
 */
export type ProvisionResult = { tenantId: string; user: { id: string; username: string; displayName: string }; tenantRole: TenantRoleId; workspace: { id: string; role: WorkspaceRoleId } | null; activation: ActivationLink | null; pending: PendingStep[] };
export type PendingStep = { id: "activate" | "organization"; label: string };

export interface ProvisioningApi {
  state(id: CapabilityId): CapabilityState;
  createTenantUser(tenantId: string, a: NewAccount): Promise<ProvisionResult>;
  createTenantWorkspace(tenantId: string, name: string): Promise<{ id: string; name: string; tenantId: string }>;
  assignTenantRole(tenantId: string, userId: string, role: TenantRoleId): Promise<TenantMemberView>;
  assignWorkspaceRole(workspaceId: string, userId: string, role: WorkspaceRoleId): Promise<Member>;
  addWorkspaceMember(workspaceId: string, who: { username?: string; email?: string }, role: WorkspaceRoleId): Promise<Member>;
  listMemberCandidates(tenantId: string, q?: string): Promise<TenantMemberCandidate[]>;
  resetCredential(userId: string): Promise<ActivationLink>;
  enableUser(userId: string): Promise<void>;
  disableUser(userId: string): Promise<void>;
}

/** the calls the production adapter needs (a slice of `api`, so tests can inject a recording fake without a network) */
export type ProvisioningTransport = {
  createTenantUser(tenantId: string, b: { username: string; displayName: string; email?: string; tenantRole: TenantRoleId; workspaceId?: string; workspaceRole?: string }): Promise<ActivationLink>;
  createTenantWorkspace(tenantId: string, name: string): Promise<{ id: string; name: string; tenantId: string }>;
  setTenantMember(tenantId: string, userId: string, role: string): Promise<TenantMemberView>;
  changeWorkspaceMember(workspaceId: string, userId: string, role: string): Promise<Member>;
  addWorkspaceMember(workspaceId: string, who: { username?: string; email?: string }, role: string): Promise<Member>;
  tenantMemberCandidates(tenantId: string, q?: string): Promise<TenantMemberCandidate[]>;
  activationLink(userId: string): Promise<ActivationLink>;
  setUserStatus(userId: string, enabled: boolean): Promise<unknown>;
};

export function createProvisioningApi(t: ProvisioningTransport, caps: Readonly<Record<CapabilityId, CapabilityState>> = CAPABILITIES): ProvisioningApi {
  const need = (id: CapabilityId): void => { const c = caps[id]; if (c.status === "NOT_READY") throw new ProvisioningNotReady(id, c.reason, c.owner); };
  return {
    state: (id) => caps[id],
    async createTenantUser(tenantId, a) {
      need("createTenantUser");
      // the tenant is the PATH; nothing in the body names it. `workspaceId` and `workspaceRole` go together or not at all. No `systemAdmin` key exists: the server would ignore it anyway.
      const link = await t.createTenantUser(tenantId, { username: a.username.trim().toLowerCase(), displayName: a.displayName.trim(), ...(a.email?.trim() ? { email: a.email.trim() } : {}), tenantRole: a.tenantRole, ...(a.workspace ? { workspaceId: a.workspace.id, workspaceRole: a.workspace.role } : {}) });
      return { tenantId, user: { id: link.userId, username: link.username, displayName: link.displayName }, tenantRole: a.tenantRole, workspace: a.workspace ?? null, activation: link, pending: [{ id: "activate", label: "Người dùng mở liên kết kích hoạt và đặt mật khẩu." }] };
    },
    createTenantWorkspace: (tenantId, name) => { need("createTenantWorkspace"); return t.createTenantWorkspace(tenantId, name.trim()); },
    assignTenantRole: (tenantId, userId, role) => { need("assignTenantRole"); return t.setTenantMember(tenantId, userId, role); },
    assignWorkspaceRole: (w, u, role) => { need("assignWorkspaceRole"); return t.changeWorkspaceMember(w, u, role); },
    addWorkspaceMember: (w, who, role) => { need("addWorkspaceMember"); return t.addWorkspaceMember(w, who, role); },
    listMemberCandidates: (tenantId, q) => { need("listMemberCandidates"); return t.tenantMemberCandidates(tenantId, q); },
    resetCredential: (u) => { need("resetCredential"); return t.activationLink(u); },
    enableUser: async (u) => { need("enableUser"); await t.setUserStatus(u, true); },
    disableUser: async (u) => { need("disableUser"); await t.setUserStatus(u, false); },
  };
}
