/**
 * Pure rules of the organization screens (no React, no network): the dynamic tree, move targets and cycle protection, form validation, delete rules the UI explains, the employee directory fallback,
 * the gate (`organizationPlan`) and the mapping of every refusal to words. The server stays the authority on all of it; this only avoids obvious refusals and explains the rest.
 * Nothing here reads a role name. Organization metadata is never a permission.
 */
import type { TenantMemberView } from "@xweb/types";
import type { AdminScope } from "./adminModel";
import {
  OrganizationNotReady, type Employee, type EmployeePage, type EmployeeQuery, type OrgCapabilityId, type OrgCapabilityState, type OrgUnit, type OrgUnitType,
} from "./organization";

// ------------------------------------------------------------------------------------------------------------------------------- icons
/** The closed set of icons a unit type may use. An icon is an id from this list (rendered by unitIcons.tsx from the design system's Lucide set); never a URL, never user-supplied markup. */
export const UNIT_ICONS: readonly { id: string; label: string }[] = [
  { id: "building", label: "Công ty / Khối" }, { id: "landmark", label: "Trụ sở" }, { id: "map-pin", label: "Chi nhánh / Địa điểm" }, { id: "briefcase", label: "Phòng / Ban" },
  { id: "layers", label: "Bộ phận" }, { id: "users", label: "Team / Nhóm" }, { id: "git-branch", label: "Nhánh" }, { id: "folder", label: "Thư mục / Khác" },
  { id: "cpu", label: "Công nghệ" }, { id: "code", label: "Phát triển" }, { id: "smartphone", label: "Mobile" }, { id: "package", label: "Sản phẩm / Kho" },
  { id: "megaphone", label: "Marketing" }, { id: "wallet", label: "Tài chính" }, { id: "headset", label: "Hỗ trợ" }, { id: "scale", label: "Pháp chế" },
];
export const DEFAULT_ICON = "folder";
export const isUnitIcon = (id: string): boolean => UNIT_ICONS.some((i) => i.id === id);
export const safeIcon = (id: string | null | undefined): string => (id && isUnitIcon(id) ? id : DEFAULT_ICON);

// ------------------------------------------------------------------------------------------------------------------------------- tree
/** `pos` / `size`: 1-based position among the siblings and their count (aria-posinset / aria-setsize) */
export type TreeNode = { unit: OrgUnit; children: TreeNode[]; depth: number; pos: number; size: number; /** the parent was not in the list: shown at the root, never dropped */ orphan: boolean };
const byName = (a: OrgUnit, b: OrgUnit) => a.name.localeCompare(b.name, "vi", { sensitivity: "base" }) || a.id.localeCompare(b.id);

/**
 * flat server rows → forest. Order: name. A row whose parent is missing (or that would close a loop) is shown at the root, flagged `orphan`; nothing is dropped.
 * Iterative (no recursion): a chain of any depth cannot overflow the stack; cost O(n log n) for the sort, O(n) for the rest.
 */
export function buildTree(units: readonly OrgUnit[]): TreeNode[] {
  const sorted = [...units].sort(byName); const nodes = new Map<string, TreeNode>();
  for (const u of sorted) nodes.set(u.id, { unit: u, children: [], depth: 0, pos: 1, size: 1, orphan: false });
  const roots: TreeNode[] = [];
  for (const u of sorted) { const n = nodes.get(u.id)!; const p = u.parentId && u.parentId !== u.id ? nodes.get(u.parentId) : undefined; if (p) p.children.push(n); else { n.orphan = !!u.parentId; roots.push(n); } }
  // walk from the roots (explicit stack), assigning depth / position and dropping any edge back to a node already placed
  const placed = new Set<string>();
  const place = (root: TreeNode) => {
    const stack: TreeNode[] = [root]; placed.add(root.unit.id);
    while (stack.length) {
      const n = stack.pop()!; n.children = n.children.filter((c) => !placed.has(c.unit.id));
      n.children.forEach((c, i) => { c.depth = n.depth + 1; c.pos = i + 1; c.size = n.children.length; placed.add(c.unit.id); stack.push(c); });
    }
  };
  roots.forEach((r, i) => { r.pos = i + 1; r.size = roots.length; place(r); });
  // a pure cycle (A→B→A, no root reaches it) would be invisible: surface what is left at the root too
  for (const u of sorted) { if (placed.has(u.id)) continue; const n = nodes.get(u.id)!; n.orphan = true; n.depth = 0; roots.push(n); place(n); }
  roots.forEach((r, i) => { r.pos = i + 1; r.size = roots.length; });
  return roots;
}
/** depth-first, parents before children; only an OPEN node shows its children. Iterative. */
export function flattenTree(nodes: readonly TreeNode[], open?: ReadonlySet<string>): TreeNode[] {
  const out: TreeNode[] = []; const stack: TreeNode[] = [];
  for (let i = nodes.length - 1; i >= 0; i--) stack.push(nodes[i]);
  while (stack.length) { const n = stack.pop()!; out.push(n); if (!open || open.has(n.unit.id)) for (let i = n.children.length - 1; i >= 0; i--) stack.push(n.children[i]); }
  return out;
}
export function descendantIds(units: readonly OrgUnit[], id: string): Set<string> {
  const kids = new Map<string, string[]>(); for (const u of units) if (u.parentId) (kids.get(u.parentId) ?? kids.set(u.parentId, []).get(u.parentId)!).push(u.id);
  const out = new Set<string>(); const stack = [id];
  while (stack.length) for (const k of kids.get(stack.pop()!) ?? []) if (!out.has(k) && k !== id) { out.add(k); stack.push(k); }
  return out;
}
export const childCountOf = (units: readonly OrgUnit[], id: string): number => units.filter((u) => u.parentId === id).length;
/** "Khối Công nghệ › Mobile › Flutter Team" */
export function unitPath(units: readonly OrgUnit[], id: string | null | undefined): string {
  const by = new Map(units.map((u) => [u.id, u])); const out: string[] = []; const guard = new Set<string>();
  for (let cur = id ? by.get(id) : undefined; cur && !guard.has(cur.id); cur = cur.parentId ? by.get(cur.parentId) : undefined) { guard.add(cur.id); out.unshift(cur.name); }
  return out.join(" › ");
}
/** unitPath for MANY rows (an employee table, a list of units): the id -> unit map is built ONCE and every path once. The plain unitPath rebuilds the map per call: rows x units (M-111). */
export function pathResolver(units: readonly OrgUnit[]): (id: string | null | undefined) => string {
  const by = new Map(units.map((u) => [u.id, u])); const memo = new Map<string, string>();
  return (id) => {
    if (!id) return "";
    const hit = memo.get(id); if (hit !== undefined) return hit;
    const out: string[] = []; const guard = new Set<string>();
    for (let cur = by.get(id); cur && !guard.has(cur.id); cur = cur.parentId ? by.get(cur.parentId) : undefined) { guard.add(cur.id); out.unshift(cur.name); }
    const path = out.join(" › "); memo.set(id, path); return path;
  };
}
/** a long breadcrumb ("A › B › … › Z") keeps the first two and the last three levels; the full path stays available as the title / aria-label of the element that shows it */
export function compactPath(path: string, keepStart = 2, keepEnd = 3): string {
  const parts = path.split(" › "); if (parts.length <= keepStart + keepEnd + 1) return path;
  return [...parts.slice(0, keepStart), "…", ...parts.slice(-keepEnd)].join(" › ");
}
const typeOf = (types: readonly OrgUnitType[], id: string | null | undefined) => (id ? types.find((t) => t.id === id) : undefined);
/** a type with no `allowedParentTypeIds` sits anywhere; otherwise the parent's TYPE must be listed (and there must be a parent) */
export function parentRule(type: OrgUnitType | undefined, parent: OrgUnit | null, types: readonly OrgUnitType[]): string | null {
  const allowed = type?.allowedParentTypeIds ?? []; if (!type || allowed.length === 0) return null;
  const names = allowed.map((a) => typeOf(types, a)?.name ?? a).join(", ");
  if (!parent) return `Loại “${type.name}” phải nằm trong: ${names}.`;
  return parent.typeId && allowed.includes(parent.typeId) ? null : `Loại “${type.name}” chỉ đặt được trong: ${names}.`;
}

export type MoveTarget = { id: string | null; label: string; depth: number; disabled: boolean; reason?: string; current: boolean };
/** every place a unit could go, with the reason when it cannot: itself / its subtree (a cycle), a type rule, "already here". The server re-checks (ORG_CYCLE). */
export function moveTargets(units: readonly OrgUnit[], types: readonly OrgUnitType[], id: string, tree?: readonly TreeNode[]): MoveTarget[] {
  const me = units.find((u) => u.id === id); if (!me) return [];
  const below = descendantIds(units, id); const type = typeOf(types, me.typeId);
  const rows: MoveTarget[] = [];
  const rootReason = parentRule(type, null, types);
  rows.push({ id: null, label: "Gốc (không thuộc đơn vị nào)", depth: 0, current: !me.parentId, disabled: !!rootReason || !me.parentId, reason: !me.parentId ? "Đang ở gốc." : rootReason ?? undefined });
  for (const n of flattenTree(tree ?? buildTree(units))) {
    const u = n.unit; let reason: string | undefined;
    if (u.id === id) reason = "Không thể chuyển vào chính nó.";
    else if (below.has(u.id)) reason = "Không thể chuyển vào đơn vị con của nó (tạo vòng).";
    else if (u.id === me.parentId) reason = "Đang ở đây.";
    else if (!u.enabled) reason = "Đơn vị đang tắt.";
    else reason = parentRule(type, u, types) ?? undefined;
    rows.push({ id: u.id, label: u.name, depth: n.depth + 1, current: u.id === me.parentId, disabled: !!reason, reason });
  }
  return rows;
}
export const wouldCycle = (units: readonly OrgUnit[], id: string, newParentId: string | null): boolean => !!newParentId && (newParentId === id || descendantIds(units, id).has(newParentId));

// ------------------------------------------------------------------------------------------------------------------------------- validation
export const UNIT_CODE_RE = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,39}$/;
export const TYPE_CODE_RE = /^[A-Z][A-Z0-9_]{1,31}$/;
export type UnitFormErrors = { name?: string; code?: string; type?: string };
export function validateUnitForm(f: { name: string; code: string; typeId: string | null }, ctx: { types: readonly OrgUnitType[]; parent: OrgUnit | null }): UnitFormErrors {
  const out: UnitFormErrors = {}; const name = f.name.trim();
  if (!name) out.name = "Hãy nhập tên đơn vị."; else if (name.length > 120) out.name = "Tên đơn vị tối đa 120 ký tự.";
  if (f.code.trim() && !UNIT_CODE_RE.test(f.code.trim())) out.code = "Mã gồm chữ, số, “.”, “_”, “-” (tối đa 40 ký tự), bắt đầu bằng chữ hoặc số.";
  const t = typeOf(ctx.types, f.typeId);
  if (f.typeId && !t) out.type = "Loại đơn vị không còn tồn tại.";
  else { const r = parentRule(t, ctx.parent, ctx.types); if (r) out.type = r; }
  return out;
}
export type TypeFormErrors = { name?: string; code?: string; icon?: string };
export function validateTypeForm(f: { name: string; code: string; icon: string }, existing: readonly OrgUnitType[]): TypeFormErrors {
  const out: TypeFormErrors = {}; const name = f.name.trim(); const code = f.code.trim().toUpperCase();
  if (!name) out.name = "Hãy nhập tên loại (ví dụ: Khối)."; else if (name.length > 80) out.name = "Tên loại tối đa 80 ký tự.";
  if (!TYPE_CODE_RE.test(code)) out.code = "Mã gồm 2–32 ký tự A–Z, 0–9, “_”, bắt đầu bằng chữ (ví dụ: DIVISION).";
  else if (existing.some((t) => t.code.toUpperCase() === code)) out.code = "Mã này đã được dùng cho loại khác.";
  if (!isUnitIcon(f.icon)) out.icon = "Hãy chọn một biểu tượng trong danh sách.";
  return out;
}
/** what the UI says BEFORE the click when the counts it knows make a delete pointless; unknown counts → null (the server decides) */
export function deleteBlock(u: OrgUnit, units: readonly OrgUnit[]): string | null {
  const kids = u.childCount ?? childCountOf(units, u.id);
  if (kids > 0) return `Còn ${kids} đơn vị con. Chuyển hoặc xóa chúng trước.`;
  if ((u.employeeCount ?? 0) > 0) return `Còn ${u.employeeCount} nhân viên. Chuyển họ sang đơn vị khác trước.`;
  return null;
}

// ------------------------------------------------------------------------------------------------------------------------------- employees
export const EMPLOYEE_PAGE_SIZE = 20;
const fold = (s: string) => s.normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/đ/gi, "d").toLowerCase();
/** per member-list work done ONCE: the rows, the sorted order and the folded search keys. A search or a page switch on the same list is then a single cheap pass (no re-sort, no re-fold). */
const PREPARED = new WeakMap<readonly TenantMemberView[], { rows: Employee[]; keys: string[] }>();
function prepare(members: readonly TenantMemberView[]) {
  const hit = PREPARED.get(members); if (hit) return hit;
  const all: Employee[] = members.map((m) => ({ userId: m.userId, username: m.username ?? m.userId.slice(0, 8), displayName: m.displayName ?? null, email: m.email ?? null, tenantRole: m.role, active: m.active }));
  const collator = new Intl.Collator("vi", { sensitivity: "base" });
  const rows = all.sort((a, b) => collator.compare(a.displayName ?? a.username, b.displayName ?? b.username) || a.userId.localeCompare(b.userId));
  const made = { rows, keys: rows.map((e) => fold(`${e.displayName ?? ""} ${e.username} ${e.email ?? ""}`)) };
  PREPARED.set(members, made); return made;
}
/** the directory built from the tenant member list: search by name / username / email (accent-insensitive), status, then a page. It has no unit or position, so those filters do not apply. */
export function employeesFromMembers(members: readonly TenantMemberView[], q: EmployeeQuery): EmployeePage {
  const { rows, keys } = prepare(members); const needle = fold((q.q ?? "").trim()); const st = q.status ?? "ALL";
  const size = Math.max(1, q.size || EMPLOYEE_PAGE_SIZE); const hit: Employee[] = [];
  for (let i = 0; i < rows.length; i++) { const e = rows[i]; if ((st === "ALL" || (st === "ACTIVE") === e.active) && (!needle || keys[i].includes(needle))) hit.push(e); }
  const last = Math.max(0, Math.ceil(hit.length / size) - 1); const page = Math.min(Math.max(0, q.page), last);
  return { items: hit.slice(page * size, page * size + size), total: hit.length, page, size, source: "members" };
}
export const pageCount = (p: { total: number; size: number }) => Math.max(1, Math.ceil(p.total / Math.max(1, p.size)));
export const employeeName = (e: { displayName: string | null; username: string }) => e.displayName?.trim() || e.username;

// ------------------------------------------------------------------------------------------------------------------------------- gate
/** `no-permission`: the backend side may be ready but the server does not list the code this operation needs for the caller (UX hint: the server re-checks and answers 403) */
export type CapView = { state: "ready" } | { state: "not-ready"; reason: string } | { state: "no-permission"; reason: string };
const capView = (s: OrgCapabilityState, held: boolean = true, missing = "Bạn chưa có quyền thực hiện thao tác này."): CapView => (!held ? { state: "no-permission", reason: missing } : s.status === "READY" ? { state: "ready" } : { state: "not-ready", reason: s.reason });
export type OrgAccess = { granted: true } | { granted: false; reason: string };
export type OrganizationPlan = {
  /** the structure screens: the server lists ORG_STRUCTURE_VIEW (adminScope.org). NOT TENANT_MEMBERS, NOT platformScope, NOT a role (a SYSTEM_ADMIN gets 403 on every organization route) */
  access: OrgAccess;
  /** the employee directory: the server lists EMPLOYEE_VIEW */
  employeeAccess: OrgAccess;
  /** exactly one tenant → fixed from the session (read-only); several → only the caller's own tenants, never a free id */
  fixedTenant: { id: string; name: string } | null; tenantChoice: { id: string; name: string }[];
  units: CapView; edit: CapView; types: CapView; positions: CapView; assignOrg: CapView; assignPosition: CapView;
  /** "directory" when listEmployees is ready; "members" while the organization-aware list is not */
  directory: "directory" | "members";
};
export function organizationPlan(scope: AdminScope, state: (id: OrgCapabilityId) => OrgCapabilityState): OrganizationPlan {
  const o = scope.org;
  const own = scope.tenants.map((t) => ({ id: t.id, name: t.name }));
  const edit = [state("createOrganizationUnit"), state("updateOrganizationUnit"), state("moveOrganizationUnit"), state("deleteOrganizationUnit")].find((s) => s.status === "NOT_READY");
  const noEdit = "Bạn xem được cơ cấu nhưng chưa có quyền thay đổi nó.", noEmp = "Bạn chưa có quyền quản lý nhân viên.";
  return {
    access: o.structureView ? { granted: true } : { granted: false, reason: "Máy chủ không liệt kê quyền xem cơ cấu tổ chức cho tài khoản này." },
    employeeAccess: o.employeeView ? { granted: true } : { granted: false, reason: "Máy chủ không liệt kê quyền xem danh bạ nhân viên cho tài khoản này." },
    fixedTenant: own.length === 1 ? own[0] : null, tenantChoice: own.length > 1 ? own : [],
    units: capView(state("listOrganizationUnits")), edit: o.structureManage ? (edit ? capView(edit) : { state: "ready" }) : { state: "no-permission", reason: noEdit },
    types: capView(state("listOrganizationUnitTypes")), positions: capView(state("listPositions"), o.positionGradeView, "Bạn chưa có quyền xem vị trí và cấp bậc."),
    assignOrg: capView(state("updateEmployeeOrganization"), o.employeeManage, noEmp), assignPosition: capView(state("updateEmployeePosition"), o.employeeManage, noEmp),
    directory: state("listEmployees").status === "READY" ? "directory" : "members",
  };
}

// ------------------------------------------------------------------------------------------------------------------------------- errors
export type OrgProblemKind = "validation" | "duplicate" | "cycle" | "blocked" | "version" | "conflict" | "forbidden" | "notfound" | "unavailable" | "not-ready" | "unknown";
export type OrgProblem = { kind: OrgProblemKind; text: string };
type Err = { code?: string; status?: number; message?: string };
/**
 * Codes marked (assumed) are the NAMES C1 is expected to use; they are not a contract yet. Any other code falls back to the HTTP status, and an unknown one shows the server's own message.
 */
const BY_CODE: Record<string, [OrgProblemKind, string]> = {
  ORG_CYCLE: ["cycle", "Không thể chuyển đơn vị vào chính nó hoặc đơn vị con của nó (tạo vòng)."], CYCLE_DETECTED: ["cycle", "Không thể chuyển đơn vị vào chính nó hoặc đơn vị con của nó (tạo vòng)."],
  ORG_HAS_CHILDREN: ["blocked", "Không xóa được: đơn vị còn đơn vị con. Chuyển hoặc xóa chúng trước."], ORG_HAS_EMPLOYEES: ["blocked", "Không xóa được: đơn vị còn nhân viên. Chuyển họ sang đơn vị khác trước."],
  UNIT_NOT_EMPTY: ["blocked", "Không xóa được: đơn vị còn đơn vị con hoặc nhân viên."],
  VERSION_CONFLICT: ["version", "Đơn vị vừa được người khác thay đổi. Tải lại cơ cấu rồi thử lại."], STALE_VERSION: ["version", "Đơn vị vừa được người khác thay đổi. Tải lại cơ cấu rồi thử lại."],
  ORG_CODE_TAKEN: ["duplicate", "Mã này đã được dùng."], DUPLICATE_NAME: ["duplicate", "Đã có đơn vị cùng tên ở vị trí này."], TYPE_CODE_TAKEN: ["duplicate", "Mã loại đơn vị này đã được dùng."],
  ORG_PARENT_TYPE_INVALID: ["validation", "Loại đơn vị này không đặt được ở vị trí đã chọn."], VALIDATION_FAILED: ["validation", "Dữ liệu chưa hợp lệ. Kiểm tra lại các trường."],
  FORBIDDEN: ["forbidden", "Bạn không có quyền thực hiện thao tác này."], UNIT_NOT_FOUND: ["notfound", "Không tìm thấy đơn vị (có thể đã bị xóa). Tải lại cơ cấu."],
  TENANT_NOT_FOUND: ["notfound", "Không tìm thấy công ty."], USER_NOT_FOUND: ["notfound", "Không tìm thấy nhân viên trong công ty này."], USER_DISABLED: ["conflict", "Tài khoản này đang bị khóa."],
};
export function orgProblem(e: unknown): OrgProblem {
  if (e instanceof OrganizationNotReady) return { kind: "not-ready", text: `Chưa sẵn sàng: ${e.reason}` };
  const x = (e ?? {}) as Err; const hit = x.code ? BY_CODE[x.code] : undefined;
  if (hit) return { kind: hit[0], text: hit[1] };
  const s = x.status ?? 0;
  if (s === 400 || s === 422) return { kind: "validation", text: "Dữ liệu chưa hợp lệ. Kiểm tra lại các trường." };
  if (s === 401) return { kind: "forbidden", text: "Phiên đăng nhập đã hết. Hãy đăng nhập lại." };
  if (s === 403) return { kind: "forbidden", text: "Bạn không có quyền thực hiện thao tác này." };
  if (s === 404) return { kind: "notfound", text: "Không tìm thấy mục này (có thể đã bị xóa). Tải lại." };
  if (s === 409) return { kind: "conflict", text: "Thao tác xung đột với dữ liệu hiện tại. Tải lại rồi thử lại." };
  if (s === 503) return { kind: "unavailable", text: "Máy chủ chưa sẵn sàng. Thử lại sau." };
  if (s >= 500 || s === 0) return { kind: "unavailable", text: "Không kết nối được máy chủ. Chưa rõ thao tác đã được ghi hay chưa: tải lại để kiểm tra." };
  return { kind: "unknown", text: x.code ? `Chưa thực hiện được (mã ${x.code}).` : "Chưa thực hiện được." };   // the server's own (English) message is never shown, only its code as a reference
}

/**
 * Creating an employee provisions an ACCOUNT: the contract needs EMPLOYEE_MANAGE **and** TENANT_MEMBERS (final contract §9). The provisioning plan alone only knows the tenant side, so the employee
 * screens ask it through this: without both codes the create state is `forbidden` with the reason (the button is unavailable, nothing is sent).
 */
export function employeeProvisioningPlan<P extends { create: { state: string } }>(scope: AdminScope, plan: P): P {
  return scope.org.employeeProvision ? plan : { ...plan, create: { state: "forbidden", reason: "Bạn cần quyền quản lý nhân viên và quản lý thành viên công ty để thêm nhân viên." } };
}
