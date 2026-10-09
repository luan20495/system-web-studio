/**
 * What the Admin / Platform consoles show to whom, and the rules of the tenant screens. Pure (no React, no fetch): unit-tested.
 * Display only: the server authorises every call (`AdminGuard`, `AccessService.forTenant/forWorkspace`); a wrong answer here can at worst show a screen whose calls answer 403.
 * A role NAME is read only through packages/permissions/src/roles.ts (guarded): a person's scope comes from the canonical codes `/auth/me` lists (TENANT_MEMBERS, MEMBER_MANAGE, DATA_SOURCE_MANAGE) and from `platformScope`.
 */
import type { Me, TenantMemberView, TenantView, WorkspaceSummary, Member, AdminUser } from "@xweb/types";
import { isTenantAdminRole, isWorkspaceAdminRole } from "../../packages/permissions/src/roles";

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
  const tenants = (me.tenants ?? []).filter((t) => isTenantAdminRole(t.role) || (holdsTenant && t.id === me.tenantId)).map((t) => ({ id: t.id, slug: t.slug, name: t.name, status: t.status }));
  return {
    platform, tenants,
    workspaces: me.workspaces.filter((w) => w.permissions?.includes("MEMBER_MANAGE")),
    dataWorkspaces: me.workspaces.filter((w) => w.permissions?.some((c) => c === "DATA_SOURCE_MANAGE" || c === "DATA_SOURCE_VIEW")),
  };
}

/**
 * Whether the server lets this person manage the members of ONE workspace: `/auth/me` lists MEMBER_MANAGE in that workspace's permissions.
 * A SYSTEM_ADMIN sees every workspace but holds only tenant-level codes there (D-C1-13), so the member panel is not offered to them.
 * A row whose `permissions` field is absent (older backend) does not block: the server still decides. No role name is read.
 */
export function canManageWorkspaceMembers(me: Me | null | undefined, workspaceId: string): boolean {
  const row = me?.workspaces.find((w) => w.id === workspaceId);
  if (!row) return false;
  return row.permissions === undefined ? true : row.permissions.includes("MEMBER_MANAGE");
}

/**
 * Whether the workspace-scoped routes (delete an application, restore one of its versions: `/workspaces/{w}/projects/**`) can work for this person.
 * D-C1-13A: a SYSTEM_ADMIN holds NO business permission in a workspace it is not a member of (unless the server runs the legacy flag, which `/auth/me` reports as `businessAccess`).
 * Those routes then answer 404, so the console does not offer them; the `/admin/applications/**` ones (archive, restore, transfer) are unaffected. Display only: the server still decides every call.
 */
export function canActInWorkspace(me: Me | null | undefined, workspaceId: string): boolean {
  if (!me) return false;
  return me.businessAccess === true || me.workspaces.some((w) => w.id === workspaceId);
}

// Which sections exist, who may open them and how they are routed: console/sectionPolicy.ts over the ONE table in console/sections.tsx.

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
/** a company code proposed from its name: Vietnamese letters folded to ASCII, lower-case, runs of anything else become one "-", trimmed, at most 120 characters */
export function slugify(name: string): string {
  return name.normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/đ/gi, "d").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 120).replace(/-+$/g, "");
}
/**
 * Up to two letters for an avatar: the first letter of the first and of the last WORD of the name that has a letter.
 * Bracketed parts ("(Demo)", "[QA]", "{x}") and tokens without a letter ("#3", "—", emoji) are ignored; Vietnamese marks are folded ("Đức" → "D") and any Unicode letter works (Cyrillic, CJK…).
 * No letter in the display name → the same rule on the username → "?". Never throws.
 */
export function initials(p: { username: string; displayName: string | null }): string {
  const clean = (t: string) => t.replace(/[(\[{][^)\]}]*[)\]}]?/gu, " ").normalize("NFD").replace(/\p{M}/gu, "").replace(/đ/giu, "d");
  const wordsOf = (t: string | null | undefined) => clean(t ?? "").split(/[\s._\-/]+/u).filter((w) => /\p{L}/u.test(w));
  const first = (w: string) => (Array.from(w).find((c) => /\p{L}/u.test(c)) ?? "").toLocaleUpperCase();
  const own = wordsOf(p.displayName); const words = own.length ? own : wordsOf(p.username);
  if (!words.length) return "?";
  return words.length > 1 ? first(words[0]) + first(words[words.length - 1]) : first(words[0]);
}
export const TENANT_ROLES = [{ id: "TENANT_ADMIN", label: "Quản trị công ty" }, { id: "MEMBER", label: "Thành viên" }] as const;
export const tenantRoleLabel = (r: string) => TENANT_ROLES.find((x) => x.id === r)?.label ?? r;
export const TENANT_STATUS_LABEL: Record<string, string> = { ACTIVE: "Hoạt động", SUSPENDED: "Tạm khóa", DELETED: "Đã xóa" };

export type TenantAction = { to: "ACTIVE" | "SUSPENDED" | "DELETED"; label: string; danger: boolean; confirm: string; /** what happens, for the confirmation dialog (the title is "{label} công ty …?") */ message: string };
/** the status changes offered for a tenant; the DEFAULT tenant offers none (the server would refuse them) */
export function tenantActions(t: Pick<TenantView, "id" | "status" | "name">): TenantAction[] {
  if (t.id === DEFAULT_TENANT_ID) return [];
  if (t.status === "ACTIVE") return [
    { to: "SUSPENDED", label: "Tạm khóa", danger: false, confirm: `Tạm khóa công ty “${t.name}”? Người dùng của công ty này sẽ không vào được cho tới khi mở khóa lại.`, message: "Người dùng của công ty này sẽ không vào được cho tới khi mở khóa lại." },
    { to: "DELETED", label: "Xóa", danger: true, confirm: `Xóa công ty “${t.name}”? Công ty bị đánh dấu đã xóa và không còn xuất hiện cho người dùng.`, message: "Công ty bị đánh dấu đã xóa và không còn xuất hiện cho người dùng. Có thể khôi phục sau." }];
  if (t.status === "SUSPENDED") return [
    { to: "ACTIVE", label: "Mở khóa", danger: false, confirm: `Mở khóa công ty “${t.name}”?`, message: "Người dùng của công ty vào lại được." },
    { to: "DELETED", label: "Xóa", danger: true, confirm: `Xóa công ty “${t.name}”? Công ty bị đánh dấu đã xóa và không còn xuất hiện cho người dùng.`, message: "Công ty bị đánh dấu đã xóa và không còn xuất hiện cho người dùng. Có thể khôi phục sau." }];
  return [{ to: "ACTIVE", label: "Khôi phục", danger: false, confirm: `Khôi phục công ty “${t.name}” về trạng thái hoạt động?`, message: "Công ty trở về trạng thái hoạt động." }];
}

/** a person the console can name: from workspace members (`/workspaces/{w}/members`) and, for a SYSTEM_ADMIN, from `/admin/users` */
export type Person = { id: string; username: string; displayName: string | null };
export const personOf = (x: Member | AdminUser, id = "userId" in x ? x.userId : (x as AdminUser).id): Person => ({ id, username: x.username, displayName: x.displayName });
export const shortId = (id: string) => id.slice(0, 8);
export function personLabel(p: Person | undefined, id: string): string { return p ? (p.displayName && p.displayName !== p.username ? `${p.displayName} (${p.username})` : p.username) : `Người dùng ${shortId(id)}…`; }

export type MemberRow = { userId: string; label: string; email: string | null; known: boolean; role: string; roleLabel: string };
/** tenant members, named from the member row itself (C1's directory metadata) or, for an older backend, from `people`; an unresolved id is shown as such, never guessed */
export function tenantMemberRows(members: TenantMemberView[], people: Map<string, Person> = new Map()): MemberRow[] {
  return members.filter((m) => m.active).map((m) => {
    const p: Person | undefined = m.username ? { id: m.userId, username: m.username, displayName: m.displayName ?? null } : people.get(m.userId);
    return { userId: m.userId, label: personLabel(p, m.userId), email: m.email ?? null, known: !!p, role: m.role, roleLabel: tenantRoleLabel(m.role) };
  }).sort((a, b) => a.label.localeCompare(b.label, "vi"));
}
/** the candidate directory needs ≥ 2 characters when a search is typed (the server answers 400 QUERY_TOO_SHORT otherwise); empty = the first 50 */
export const CANDIDATE_MIN_QUERY = 2, CANDIDATE_MAX_RESULTS = 50;
export function candidateQuery(raw: string): { ask: boolean; q?: string; hint?: string } {
  const q = raw.trim();
  if (!q) return { ask: true };
  if (q.length < CANDIDATE_MIN_QUERY) return { ask: false, hint: `Gõ ít nhất ${CANDIDATE_MIN_QUERY} ký tự để tìm.` };
  return { ask: true, q };
}
export const candidateLabel = (c: { username: string; displayName: string | null; email: string | null }): string =>
  `${personLabel({ id: "", username: c.username, displayName: c.displayName }, "")}${c.email ? ` · ${c.email}` : ""}`;

/** the server forbids changing your own tenant role / adding yourself (SELF_GRANT_FORBIDDEN) and removing the last TENANT_ADMIN (LAST_TENANT_ADMIN): the UI says it before the click */
export function memberChangeBlock(m: { userId: string; role: string }, me: { id: string }, all: TenantMemberView[], next: "TENANT_ADMIN" | "MEMBER" | "REMOVE"): string | null {
  if (m.userId === me.id) return "Bạn không thể tự đổi vai trò hoặc tự gỡ mình khỏi công ty.";
  const admins = all.filter((x) => x.active && isTenantAdminRole(x.role)).length;
  if (isTenantAdminRole(m.role) && !isTenantAdminRole(next) && admins <= 1) return "Công ty phải còn ít nhất một quản trị viên.";
  return null;
}

/** workspace roles of the member API (`MemberController.WORKSPACE_ROLES`) */
export const WORKSPACE_ROLES = [{ id: "WORKSPACE_ADMIN", label: "Quản trị không gian làm việc" }, { id: "EDITOR", label: "Biên tập viên" }, { id: "PUBLISHER", label: "Người xuất bản" }, { id: "VIEWER", label: "Người xem" }] as const;
export const workspaceRoleLabel = (r: string) => WORKSPACE_ROLES.find((x) => x.id === r)?.label ?? r;
export function workspaceMemberBlock(m: Member, me: { id: string }, all: Member[], next: string | "REMOVE"): string | null {
  if (m.userId === me.id) return "Bạn không thể tự đổi vai trò hoặc tự gỡ mình khỏi workspace.";
  if (isWorkspaceAdminRole(m.role) && !isWorkspaceAdminRole(next) && all.filter((x) => isWorkspaceAdminRole(x.role)).length <= 1) return "Workspace phải còn ít nhất một quản trị viên.";
  return null;
}

/**
 * M-098: the one-time activation / reset link. The person who opens it is an employee, so it points at the Studio web app when its origin is configured
 * (NEXT_PUBLIC_PORTAL_URL_STUDIO via `portalOrigin("studio")`), not at whatever host the admin happens to use (that may be an internal address the employee cannot reach).
 * No origin configured (single-host development, the legacy root app) = the current origin. The token stays in the fragment (never sent to a server log).
 */
export function activationUrl(token: string, configuredOrigin: string, currentOrigin: string): string {
  const origin = (configuredOrigin || currentOrigin).replace(/\/+$/, "");
  return `${origin}/auth/activate#${token}`;
}
