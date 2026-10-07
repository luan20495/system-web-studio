// @class: harness — in-page host of the create-account screens with a FAKE transport; NOT a backend and NOT a backend E2E
/**
 * TEST-ONLY harness for <CreateAccountDialog> / <PeopleView>. The adapter is the REAL `createProvisioningApi`; only its transport (the HTTP calls) is an in-page fake that records every call in
 * window.__prov and answers per ?s=<scenario>. It proves what the SCREENS do with the answers the backend's codes describe (states, gating, no invented calls), never what the server answers.
 * The server's real answers are the job of tests/e2e-real (SUPER01…).
 */
import { createRoot } from "react-dom/client";
import type { Me } from "@xweb/types";
import { adminScope } from "../../features/admin/adminModel";
import { CAPABILITIES, createProvisioningApi, type CapabilityId, type CapabilityState, type ProvisioningTransport } from "../../features/admin/provisioning";
import { provisioningPlan } from "../../features/admin/provisioningModel";
import { CreateAccountDialog, PeopleView, type Option } from "../../features/admin/ProvisioningScreens";
import "../../packages/ui/src/styles/globals.css";
import "../../packages/ui/src/styles/responsive.css";
import "../../packages/ui/src/styles/http.css";
import "../../packages/ui/src/styles/factory.css";

declare global { interface Window { __prov: { name: string; args: unknown[] }[] } }
window.__prov = [];
const S = new URLSearchParams(location.search).get("s") ?? "platform";
const err = (status: number, code: string, message = "fixed text") => Object.assign(new Error(message), { status, code });
const me = (o: Partial<Me>): Me => ({ id: "me", username: "me", displayName: "Me", roles: [], workspaces: [], ...o });
const T = [{ id: "t1", slug: "acme", name: "Acme", status: "ACTIVE", role: "TENANT_ADMIN" }];
const SCOPES: Record<string, Me> = {
  platform: me({ platformScope: true, systemAdmin: true }),
  "admin-tenant": me({ platformScope: false, tenantId: "t1", permissions: ["TENANT_MEMBERS"], tenants: T, workspaces: [{ id: "w1", name: "Kinh doanh", role: "x", tenantId: "t1", permissions: ["MEMBER_MANAGE"] }, { id: "w2", name: "Kỹ thuật", role: "x", tenantId: "t1", permissions: ["APP_VIEW"] }] }),
  "admin-wsadmin": me({ platformScope: false, workspaces: [{ id: "w1", name: "Kinh doanh", role: "WORKSPACE_ADMIN", tenantId: "t1", permissions: ["MEMBER_MANAGE", "DATA_SOURCE_MANAGE"] }] }),
  "admin-claims-role-only": me({ platformScope: false, workspaces: [{ id: "w1", name: "Kinh doanh", role: "WORKSPACE_ADMIN", tenantId: "t1", permissions: ["APP_VIEW"] }] }),
};
const scopeKey = S.startsWith("platform") ? "platform" : S.startsWith("admin-tenant") ? "admin-tenant" : S.startsWith("admin-ws") ? "admin-wsadmin" : "admin-claims-role-only";
const caps: Record<CapabilityId, CapabilityState> = { ...CAPABILITIES };
if (S === "platform-notready") caps.createPlatformUser = { status: "NOT_READY", needs: ["platformScope"], owner: "C1", reason: "Máy chủ chưa có API provisioning." };
if (S === "admin-tenant-ready") caps.createTenantUser = { status: "READY", needs: ["TENANT_MEMBERS"], route: "POST /api/v1/admin/tenants/{tenantId}/users (simulated contract)" };
const FAIL: Record<string, [number, string]> = { "platform-403": [403, "ADMIN_REQUIRED"], "platform-dup": [409, "USERNAME_TAKEN"], "platform-dupmail": [409, "EMAIL_TAKEN"], "platform-ws404": [404, "WORKSPACE_NOT_FOUND"], "platform-down": [0, "NETWORK"], "platform-503": [503, "UNAVAILABLE"], "platform-invalid": [400, "INVALID_USERNAME"] };
const rec = <T,>(name: string, f: (...a: unknown[]) => T) => (...args: unknown[]): Promise<Awaited<T>> => { window.__prov.push({ name, args }); return Promise.resolve().then(() => f(...args)) as Promise<Awaited<T>>; };
const link = (u: string) => ({ userId: "id-" + u, username: u, displayName: u, purpose: "ACTIVATION" as const, token: "x".repeat(43), expiresAt: "2026-10-08T00:00:00Z" });
const transport: ProvisioningTransport = {
  createUser: rec("createUser", (b) => { const f = FAIL[S]; if (f) throw err(f[0], f[1]); return link((b as { username: string }).username); }),
  setTenantMember: rec("setTenantMember", () => ({ tenantId: "t1", userId: "u", role: "MEMBER", active: true })),
  changeWorkspaceMember: rec("changeWorkspaceMember", () => ({}) as never),
  addWorkspaceMember: rec("addWorkspaceMember", (w, who) => { if ((who as { username?: string }).username === "taken") throw err(409, "ALREADY_MEMBER"); if ((who as { username?: string }).username === "ghost") throw err(404, "USER_NOT_FOUND"); if (S.endsWith("-403")) throw err(403, "PERMISSION_DENIED"); return {} as never; }),
  tenantMemberCandidates: rec("tenantMemberCandidates", () => []), activationLink: rec("activationLink", () => link("x")), setUserStatus: rec("setUserStatus", () => ({})),
};
const api = createProvisioningApi(transport, caps);
const scope = adminScope(SCOPES[scopeKey]);
const plan = provisioningPlan(scope, scopeKey === "platform" ? "platform" : "admin", api.state);
const tenants: Option[] = [{ id: "t1", name: "Acme" }, { id: "t2", name: "Beta" }];
const workspaces: Option[] = [{ id: "w1", name: "Kinh doanh" }, { id: "w2", name: "Kỹ thuật" }];
const root = document.getElementById("root")!;
if (scopeKey === "platform") createRoot(root).render(<CreateAccountDialog api={api} plan={plan} tenants={tenants} workspaces={workspaces} onClose={() => undefined} createWorkspace={async (name) => ({ id: "w-new", name })}/>);
else createRoot(root).render(<div style={{ maxWidth: 640, padding: 12 }}><PeopleView api={api} plan={plan} tenants={scope.tenants.map((t) => ({ id: t.id, name: t.name }))} workspaces={workspaces} memberWorkspaces={scope.workspaces.map((w) => ({ id: w.id, name: w.name }))}/></div>);
