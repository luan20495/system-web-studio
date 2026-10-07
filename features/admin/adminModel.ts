/**
 * What the Admin / Platform consoles show to whom, and the rules of the tenant screens. Pure (no React, no fetch): unit-tested.
 * Display only: the server authorises every call (`AdminGuard`, `AccessService.forTenant/forWorkspace`); a wrong answer here can at worst show a screen whose calls answer 403.
 * No role NAME is read: a person's scope comes from the canonical codes `/auth/me` lists (TENANT_MEMBERS, MEMBER_MANAGE, DATA_SOURCE_MANAGE) and from `platformScope`.
 */
import type { Me, TenantMemberView, TenantView, WorkspaceSummary, Member, AdminUser } from "@xweb/types";

export type AdminScope = {
  /** SYSTEM_ADMIN: every `/api/v1/admin/**` screen (users, workspaces, audit…) */
  platform: boolean;
  /** tenants the person administers: the server lists TENANT_MEMBERS among their permissions */
  tenants: { id: string; slug: string; name: string; status: string }[];
  /** workspaces where the server lists MEMBER_MANAGE */
  workspaces: WorkspaceSummary[];
  /** workspaces where the server lists DATA_SOURCE_MANAGE or DATA_SOURCE_VIEW */
  dataWorkspaces: WorkspaceSummary[];
};

export function adminScope(me: Me | null | undefined): AdminScope {
  if (!me) return { platform: false, tenants: [], workspaces: [], dataWorkspaces: [] };
  const platform = me.platformScope ?? me.systemAdmin === true;
  // `/auth/me` carries the permissions of the PRIMARY tenant only, but lists every membership with its role: a person is shown the tenants where the server says they are TENANT_ADMIN
  // (or the primary one when the server listed TENANT_MEMBERS). Display only: `/admin/tenants/{id}/**` authorises per tenant.
  const holdsTenant = (me.permissions ?? []).includes("TENANT_MEMBERS");
  const tenants = (me.tenants ?? []).filter((t) => t.role === "TENANT_ADMIN" || (holdsTenant && t.id === me.tenantId)).map((t) => ({ id: t.id, slug: t.slug, name: t.name, status: t.status }));
  return {
    platform, tenants,
    workspaces: me.workspaces.filter((w) => w.permissions?.includes("MEMBER_MANAGE")),
    dataWorkspaces: me.workspaces.filter((w) => w.permissions?.some((c) => c === "DATA_SOURCE_MANAGE" || c === "DATA_SOURCE_VIEW")),
  };
}

/** sections only a SYSTEM_ADMIN can open: their APIs are guarded by AdminGuard (T1 audit) */
export const SYSTEM_ONLY: ReadonlySet<string> = new Set(["users", "workspaces", "applications", "ai", "ai-governance", "alerts", "security", "costs", "departments", "identity", "connectors", "backups", "components", "templates", "builds", "packages", "audit", "system", "settings", "tenants"]);
/** sections for the people who administer a tenant / a workspace but are not SYSTEM_ADMIN */
export const SCOPED_SECTIONS = { company: "company", myWorkspaces: "my-workspaces", dataSources: "data-sources" } as const;

export type SectionAccess = "ok" | "needs-platform" | "needs-scope";
export function sectionAccess(key: string, scope: AdminScope): SectionAccess {
  if (key === "") return "ok";
  if (key === SCOPED_SECTIONS.company) return scope.platform || scope.tenants.length ? "ok" : "needs-scope";
  if (key === SCOPED_SECTIONS.myWorkspaces) return scope.workspaces.length ? "ok" : "needs-scope";
  if (key === SCOPED_SECTIONS.dataSources) return scope.dataWorkspaces.length ? "ok" : "needs-scope";
  if (SYSTEM_ONLY.has(key)) return scope.platform ? "ok" : "needs-platform";
  return "ok";
}

// ------------------------------------------------------------------------------------------------------------------------------ tenants
export const TENANT_SLUG_RE = /^[a-z0-9][a-z0-9-]{0,118}[a-z0-9]$/;
/** TenantIds.DEFAULT: the server refuses to suspend or delete it (409 DEFAULT_TENANT_PROTECTED) */
export const DEFAULT_TENANT_ID = "00000000-0000-0000-0000-000000000001";
export function checkTenantForm(f: { slug: string; name: string }): { slug?: string; name?: string } {
  const out: { slug?: string; name?: string } = {};
  if (!TENANT_SLUG_RE.test(f.slug.trim().toLowerCase())) out.slug = "Mã công ty gồm 2–120 ký tự a–z, 0–9 và dấu “-”, bắt đầu và kết thúc bằng chữ hoặc số.";
  if (!f.name.trim() || f.name.trim().length > 160) out.name = "Hãy nhập tên công ty (tối đa 160 ký tự).";
  return out;
}
export const TENANT_ROLES = [{ id: "TENANT_ADMIN", label: "Quản trị công ty" }, { id: "MEMBER", label: "Thành viên" }] as const;
export const tenantRoleLabel = (r: string) => TENANT_ROLES.find((x) => x.id === r)?.label ?? r;
export const TENANT_STATUS_LABEL: Record<string, string> = { ACTIVE: "Hoạt động", SUSPENDED: "Tạm khóa", DELETED: "Đã xóa" };

export type TenantAction = { to: "ACTIVE" | "SUSPENDED" | "DELETED"; label: string; danger: boolean; confirm: string };
/** the status changes offered for a tenant; the DEFAULT tenant offers none (the server would refuse them) */
export function tenantActions(t: Pick<TenantView, "id" | "status" | "name">): TenantAction[] {
  if (t.id === DEFAULT_TENANT_ID) return [];
  if (t.status === "ACTIVE") return [
    { to: "SUSPENDED", label: "Tạm khóa", danger: false, confirm: `Tạm khóa công ty “${t.name}”? Người dùng của công ty này sẽ không vào được cho tới khi mở khóa lại.` },
    { to: "DELETED", label: "Xóa", danger: true, confirm: `Xóa công ty “${t.name}”? Công ty bị đánh dấu đã xóa và không còn xuất hiện cho người dùng.` }];
  if (t.status === "SUSPENDED") return [
    { to: "ACTIVE", label: "Mở khóa", danger: false, confirm: `Mở khóa công ty “${t.name}”?` },
    { to: "DELETED", label: "Xóa", danger: true, confirm: `Xóa công ty “${t.name}”? Công ty bị đánh dấu đã xóa và không còn xuất hiện cho người dùng.` }];
  return [{ to: "ACTIVE", label: "Khôi phục", danger: false, confirm: `Khôi phục công ty “${t.name}” về trạng thái hoạt động?` }];
}

/** a person the console can name: from workspace members (`/workspaces/{w}/members`) and, for a SYSTEM_ADMIN, from `/admin/users` */
export type Person = { id: string; username: string; displayName: string | null };
export const personOf = (x: Member | AdminUser, id = "userId" in x ? x.userId : (x as AdminUser).id): Person => ({ id, username: x.username, displayName: x.displayName });
export const shortId = (id: string) => id.slice(0, 8);
export function personLabel(p: Person | undefined, id: string): string { return p ? (p.displayName && p.displayName !== p.username ? `${p.displayName} (${p.username})` : p.username) : `Người dùng ${shortId(id)}…`; }

export type MemberRow = { userId: string; label: string; known: boolean; role: string; roleLabel: string };
/** tenant members with names where the console can resolve them; an unresolved id is shown as such, never guessed */
export function tenantMemberRows(members: TenantMemberView[], people: Map<string, Person>): MemberRow[] {
  return members.filter((m) => m.active).map((m) => ({ userId: m.userId, label: personLabel(people.get(m.userId), m.userId), known: people.has(m.userId), role: m.role, roleLabel: tenantRoleLabel(m.role) }))
    .sort((a, b) => a.label.localeCompare(b.label, "vi"));
}
/** people that can be added: known to the console, not yet members */
export const addableTenantPeople = (people: Map<string, Person>, members: TenantMemberView[]): Person[] => {
  const have = new Set(members.filter((m) => m.active).map((m) => m.userId));
  return [...people.values()].filter((p) => !have.has(p.id)).sort((a, b) => personLabel(a, a.id).localeCompare(personLabel(b, b.id), "vi"));
};
/** the server forbids changing your own tenant role / adding yourself (SELF_GRANT_FORBIDDEN) and removing the last TENANT_ADMIN (LAST_TENANT_ADMIN): the UI says it before the click */
export function memberChangeBlock(m: { userId: string; role: string }, me: { id: string }, all: TenantMemberView[], next: "TENANT_ADMIN" | "MEMBER" | "REMOVE"): string | null {
  if (m.userId === me.id) return "Bạn không thể tự đổi vai trò hoặc tự gỡ mình khỏi công ty.";
  const admins = all.filter((x) => x.active && x.role === "TENANT_ADMIN").length;
  if (m.role === "TENANT_ADMIN" && next !== "TENANT_ADMIN" && admins <= 1) return "Công ty phải còn ít nhất một quản trị viên.";
  return null;
}

/** workspace roles of the member API (`MemberController.WORKSPACE_ROLES`) */
export const WORKSPACE_ROLES = [{ id: "WORKSPACE_ADMIN", label: "Quản trị không gian làm việc" }, { id: "EDITOR", label: "Biên tập viên" }, { id: "PUBLISHER", label: "Người xuất bản" }, { id: "VIEWER", label: "Người xem" }] as const;
export const workspaceRoleLabel = (r: string) => WORKSPACE_ROLES.find((x) => x.id === r)?.label ?? r;
export function workspaceMemberBlock(m: Member, me: { id: string }, all: Member[], next: string | "REMOVE"): string | null {
  if (m.userId === me.id) return "Bạn không thể tự đổi vai trò hoặc tự gỡ mình khỏi workspace.";
  if (m.role === "WORKSPACE_ADMIN" && next !== "WORKSPACE_ADMIN" && all.filter((x) => x.role === "WORKSPACE_ADMIN").length <= 1) return "Workspace phải còn ít nhất một quản trị viên.";
  return null;
}

const ERROR_TEXT: Record<string, string> = {
  LAST_TENANT_ADMIN: "Công ty phải còn ít nhất một quản trị viên.", LAST_ADMIN: "Workspace phải còn ít nhất một quản trị viên.",
  SELF_GRANT_FORBIDDEN: "Bạn không thể tự cấp quyền hoặc tự đổi vai trò của chính mình.", DEFAULT_TENANT_PROTECTED: "Công ty mặc định không thể bị tạm khóa hoặc xóa.",
  TENANT_SLUG_TAKEN: "Mã công ty này đã được dùng.", TENANT_SLUG_INVALID: "Mã công ty không hợp lệ.", TENANT_NAME_INVALID: "Tên công ty không hợp lệ.",
  TENANT_NOT_FOUND: "Không tìm thấy công ty.", TENANT_MEMBER_NOT_FOUND: "Người này không còn là thành viên của công ty.", USER_NOT_FOUND: "Không tìm thấy người dùng này.",
  ALREADY_MEMBER: "Người này đã là thành viên.", INVALID_ROLE: "Vai trò không hợp lệ.", MEMBER_NOT_FOUND: "Không tìm thấy thành viên.",
  ADMIN_REQUIRED: "Màn hình này chỉ dành cho quản trị hệ thống.", PERMISSION_DENIED: "Bạn không có quyền thực hiện thao tác này.",
};
/** the server's refusals in words a company administrator understands; the code is the contract, the English message is not shown */
export function adminErrorText(e: { code?: string; status?: number; message?: string } | null | undefined, fallback: string): string {
  if (e?.code && ERROR_TEXT[e.code]) return ERROR_TEXT[e.code];
  if (e?.status === 403) return "Bạn không có quyền thực hiện thao tác này.";
  if (e?.status === 404) return "Không tìm thấy (hoặc bạn không có quyền xem).";
  return e?.message ? `${fallback} (${e.message})` : fallback;
}
