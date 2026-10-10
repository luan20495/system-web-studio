/**
 * Pure rules of the organization screens (no React, no network): the dynamic tree, move targets and cycle protection, form validation, the two employee counts and their wording, the advisory hints
 * the UI shows BEFORE a click, the gate (`organizationPlan`) and the mapping of every refusal to words.
 * The server stays the authority on all of it (cycles, type rules, depth, blocks, versions, limits): a local pre-check only avoids an obvious refusal and explains it, and the maximum depth is NEVER enforced
 * here (it is the tenant's own rule on the type, shown as an advisory note; the server's answer decides). Nothing here reads a role name; an organization relation, a position or a grade is never a permission.
 */
import { capsOf, tenantsWith, type AdminScope } from "./adminModel";
import { orgMaxPage, ORG_OFFSET_MAX, ORG_PAGE_SIZE_MAX, ORG_SEARCH_MIN } from "../../packages/api-client/src/org";
import { OrganizationNotReady, type Employee, type Grade, type Membership, type OrgCapabilityId, type OrgCapabilityState, type OrgUnit, type OrgUnitType, type Position } from "./organization";

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
/** the server's own order (sortOrder, name, id), so what the person sees is what `GET` returns */
const byOrder = (a: OrgUnit, b: OrgUnit) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.name.localeCompare(b.name, "vi", { sensitivity: "base" }) || a.id.localeCompare(b.id);

/**
 * flat server rows → forest. A row whose parent is missing (or that would close a loop) is shown at the root, flagged `orphan`; nothing is dropped.
 * Iterative (no recursion): a chain of any depth cannot overflow the stack; cost O(n log n) for the sort, O(n) for the rest.
 */
export function buildTree(units: readonly OrgUnit[]): TreeNode[] {
  const sorted = [...units].sort(byOrder); const nodes = new Map<string, TreeNode>();
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
export const childCountOf = (units: readonly OrgUnit[], id: string): number => units.filter((u) => u.parentId === id && u.active).length;
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
const typeNames = (types: readonly OrgUnitType[], ids: readonly string[]) => ids.map((a) => typeOf(types, a)?.name ?? "loại đã xóa").join(", ");

/**
 * The placement rules of the TENANT (data on the type, contract OrgUnitTypeRules), applied to a prospective parent. Advisory: the server re-checks every rule and answers 409 ORG_TYPE_RULE_VIOLATION.
 *  - the root: `allowRoot` when set, else yes when `allowedParentTypeIds` is absent or empty, no when it lists types;
 *  - the parent: its TYPE must be in `allowedParentTypeIds` (absent = any), and the type must be in the parent type's `allowedChildTypeIds` (absent = any; [] = a leaf type).
 * The maximum depth is not decided here (see depthAdvisory).
 */
export function parentRule(type: OrgUnitType | undefined, parent: OrgUnit | null, types: readonly OrgUnitType[]): string | null {
  if (!type) return null;
  const parents = type.rules.allowedParentTypeIds;
  if (!parent) {
    const rootOk = type.rules.allowRoot ?? (parents === null || parents.length === 0);
    return rootOk ? null : parents && parents.length ? `Loại “${type.name}” phải nằm trong: ${typeNames(types, parents)}.` : `Loại “${type.name}” không được làm đơn vị gốc.`;
  }
  if (parents !== null && !parents.includes(parent.typeId)) return parents.length ? `Loại “${type.name}” chỉ đặt được trong: ${typeNames(types, parents)}.` : `Loại “${type.name}” chỉ được làm đơn vị gốc.`;
  const parentType = typeOf(types, parent.typeId); const children = parentType?.rules.allowedChildTypeIds ?? null;
  if (parentType && children !== null && !children.includes(type.id)) return children.length ? `Loại “${parentType.name}” chỉ chứa: ${typeNames(types, children)}.` : `Loại “${parentType.name}” không chứa đơn vị con.`;
  return null;
}
/** how many levels from the root a unit sits (1 = a root); a broken or looping parent chain stops the count */
export function levelOf(units: readonly OrgUnit[], id: string | null | undefined): number {
  const by = new Map(units.map((u) => [u.id, u])); const seen = new Set<string>(); let n = 0;
  for (let cur = id ? by.get(id) : undefined; cur && !seen.has(cur.id); cur = cur.parentId ? by.get(cur.parentId) : undefined) { seen.add(cur.id); n++; }
  return n;
}
/**
 * ADVISORY only: the type's own `rules.maxDepth` (set by the company, read from the server) against the level the new unit would get. It never blocks a request; the server checks the unit and every
 * descendant of a moved subtree and answers ORG_TYPE_RULE_VIOLATION (reason MAX_DEPTH), which is shown as such.
 */
export function depthAdvisory(type: OrgUnitType | undefined, parent: OrgUnit | null, units: readonly OrgUnit[]): string | null {
  const limit = type?.rules.maxDepth ?? null; if (!type || limit === null) return null;
  const newLevel = parent ? levelOf(units, parent.id) + 1 : 1;
  return newLevel > limit ? `Loại “${type.name}” được công ty giới hạn tối đa ${limit} cấp, vị trí này sẽ ở cấp ${newLevel}. Máy chủ sẽ kiểm tra và có thể từ chối.` : null;
}

export type MoveTarget = { id: string | null; label: string; depth: number; disabled: boolean; reason?: string; current: boolean; /** advisory (never disables) */ note?: string };
/** every place a unit could go, with the reason when it cannot: itself / its subtree (a cycle), an archived unit, a type rule, "already here". The server re-checks (ORG_CYCLE, ORG_TYPE_RULE_VIOLATION). */
export function moveTargets(units: readonly OrgUnit[], types: readonly OrgUnitType[], id: string, tree?: readonly TreeNode[]): MoveTarget[] {
  const me = units.find((u) => u.id === id); if (!me) return [];
  const below = descendantIds(units, id); const type = typeOf(types, me.typeId);
  const rows: MoveTarget[] = [];
  const rootReason = parentRule(type, null, types); const rootNote = depthAdvisory(type, null, units);
  rows.push({ id: null, label: "Gốc (không thuộc đơn vị nào)", depth: 0, current: !me.parentId, disabled: !!rootReason || !me.parentId, reason: !me.parentId ? "Đang ở gốc." : rootReason ?? undefined, ...(rootNote ? { note: rootNote } : {}) });
  for (const n of flattenTree(tree ?? buildTree(units))) {
    const u = n.unit; let reason: string | undefined;
    if (u.id === id) reason = "Không thể chuyển vào chính nó.";
    else if (below.has(u.id)) reason = "Không thể chuyển vào đơn vị con của nó (tạo vòng).";
    else if (u.id === me.parentId) reason = "Đang ở đây.";
    else if (!u.active) reason = "Đơn vị đã lưu trữ.";
    else reason = parentRule(type, u, types) ?? undefined;
    const note = reason ? undefined : depthAdvisory(type, u, units) ?? undefined;
    rows.push({ id: u.id, label: u.name, depth: n.depth + 1, current: u.id === me.parentId, disabled: !!reason, reason, ...(note ? { note } : {}) });
  }
  return rows;
}
export const wouldCycle = (units: readonly OrgUnit[], id: string, newParentId: string | null): boolean => !!newParentId && (newParentId === id || descendantIds(units, id).has(newParentId));

// ------------------------------------------------------------------------------------------------------------------------------- the two counts
/**
 * `directMemberCount` and `subtreeEmployeeCount` are two different numbers and are shown as two different things (never both "nhân viên"):
 *  - direct: active members of exactly this unit;
 *  - subtree: distinct active employees over this unit and its non-archived descendants. A person in two units of the branch counts once, so it is NOT the sum of the children.
 * An unknown count (null: the server gave none) is "chưa có số liệu", never 0.
 */
export type CountView = { label: string; value: string; title: string; known: boolean };
const fmt = (n: number) => n.toLocaleString("vi-VN");
export function directCountView(u: Pick<OrgUnit, "directMemberCount">): CountView {
  const n = u.directMemberCount;
  return { label: "Thành viên trực tiếp", value: n === null ? "chưa có số liệu" : fmt(n), known: n !== null, title: "Số nhân viên đang hoạt động có tư cách thành viên đúng ở đơn vị này (không tính đơn vị con, thành viên đã kết thúc, nhân viên bị khóa)." };
}
export function subtreeCountView(u: Pick<OrgUnit, "subtreeEmployeeCount">): CountView {
  const n = u.subtreeEmployeeCount;
  return { label: "Nhân viên cả nhánh", value: n === null ? "chưa có số liệu" : fmt(n), known: n !== null, title: "Số nhân viên khác nhau (đang hoạt động) trong đơn vị này và các đơn vị con chưa lưu trữ. Một người thuộc nhiều đơn vị trong nhánh chỉ tính một lần, nên con số này không bằng tổng các đơn vị con." };
}
/** the one-line summary of a tree row: both counts, each with its own word, plus the number of child units */
export function unitRowSummary(u: OrgUnit): string {
  const d = directCountView(u); const s = subtreeCountView(u);
  return [d.known ? `${d.value} trực tiếp` : null, s.known ? `${s.value} cả nhánh` : null, u.childCount > 0 ? `${u.childCount} đơn vị con` : null].filter(Boolean).join(" · ");
}

// ------------------------------------------------------------------------------------------------------------------------------- unit type rules (form <-> contract)
/**
 * The placement rules of a unit type as the form edits them. Each list has a policy: "any" = no constraint (null on the wire), "listed" = only the ticked types ([] = none: a leaf / root-only type).
 * `allowRoot` "default" = unset (null). `maxDepth` is the company's own number; nothing here decides what a good value is.
 */
export type RulesForm = { parents: "any" | "listed"; parentIds: string[]; children: "any" | "listed"; childIds: string[]; allowRoot: "default" | "yes" | "no"; maxDepth: string };
export const emptyRulesForm = (): RulesForm => ({ parents: "any", parentIds: [], children: "any", childIds: [], allowRoot: "default", maxDepth: "" });
export const formFromRules = (r: OrgUnitType["rules"]): RulesForm => ({
  parents: r.allowedParentTypeIds === null ? "any" : "listed", parentIds: [...(r.allowedParentTypeIds ?? [])], children: r.allowedChildTypeIds === null ? "any" : "listed", childIds: [...(r.allowedChildTypeIds ?? [])],
  allowRoot: r.allowRoot === null ? "default" : r.allowRoot ? "yes" : "no", maxDepth: r.maxDepth === null ? "" : String(r.maxDepth),
});
export const rulesFromForm = (f: RulesForm): OrgUnitType["rules"] => ({
  allowedParentTypeIds: f.parents === "listed" ? f.parentIds : null, allowedChildTypeIds: f.children === "listed" ? f.childIds : null,
  allowRoot: f.allowRoot === "default" ? null : f.allowRoot === "yes", maxDepth: f.maxDepth.trim() ? Number(f.maxDepth.trim()) : null,
});
/** the rules of a type in words, one line each (the list of types shows them; nothing is hidden when a type has none) */
export function rulesSummary(r: OrgUnitType["rules"], types: readonly OrgUnitType[]): string[] {
  const out: string[] = [];
  if (r.allowedParentTypeIds !== null) out.push(r.allowedParentTypeIds.length ? `đặt dưới: ${typeNames(types, r.allowedParentTypeIds)}` : "chỉ làm đơn vị gốc");
  if (r.allowedChildTypeIds !== null) out.push(r.allowedChildTypeIds.length ? `chỉ chứa: ${typeNames(types, r.allowedChildTypeIds)}` : "không chứa đơn vị con");
  if (r.allowRoot !== null) out.push(r.allowRoot ? "được làm đơn vị gốc" : "không làm đơn vị gốc");
  if (r.maxDepth !== null) out.push(`tối đa ${r.maxDepth} cấp`);
  return out.length ? out : ["không giới hạn nơi đặt"];
}
/** the relation of a membership is free business vocabulary (MEMBER by default): it is upper-cased like the server does and grants nothing */
export const normalizeRelation = (raw: string): string => raw.trim().toUpperCase();
export const relationError = (raw: string): string | null => (raw.trim() === "" || RELATION_RE.test(normalizeRelation(raw)) ? null : "Quan hệ gồm 1–32 ký tự A–Z, 0–9, “_”, bắt đầu bằng chữ (ví dụ: MEMBER).");

// ------------------------------------------------------------------------------------------------------------------------------- validation
/** the server's patterns (OrgRules): unit code 1–60 of A-Z a-z 0-9 . _ - (stored upper-case); type code lower-case; position / grade code 1–40 */
export const UNIT_CODE_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,59}$/;
export const TYPE_CODE_RE = /^[a-z0-9][a-z0-9_-]{0,39}$/;
export const CATALOG_CODE_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$/;
export const RELATION_RE = /^[A-Z][A-Z0-9_]{0,31}$/;
export type UnitFormErrors = { name?: string; code?: string; type?: string };
export function validateUnitForm(f: { name: string; code: string; typeId: string | null }, ctx: { types: readonly OrgUnitType[]; parent: OrgUnit | null; /** edit: the type and the place are not changed here */ editing?: boolean }): UnitFormErrors {
  const out: UnitFormErrors = {}; const name = f.name.trim(); const code = f.code.trim();
  if (!name) out.name = "Hãy nhập tên đơn vị."; else if (name.length > 160) out.name = "Tên đơn vị tối đa 160 ký tự.";
  if (!code) out.code = "Hãy nhập mã đơn vị (duy nhất trong cùng cấp)."; else if (!UNIT_CODE_RE.test(code)) out.code = "Mã gồm chữ, số, “.”, “_”, “-” (tối đa 60 ký tự), bắt đầu bằng chữ hoặc số.";
  if (!ctx.editing) {
    const t = typeOf(ctx.types, f.typeId);
    if (!f.typeId) out.type = "Hãy chọn loại đơn vị.";
    else if (!t) out.type = "Loại đơn vị không còn tồn tại.";
    else if (!t.active) out.type = "Loại đơn vị này đang bị tắt.";
    else { const r = parentRule(t, ctx.parent, ctx.types); if (r) out.type = r; }
  }
  return out;
}
export type TypeFormErrors = { name?: string; code?: string; icon?: string; maxDepth?: string };
export function validateTypeForm(f: { name: string; code: string; icon: string; maxDepth?: string }, existing: readonly OrgUnitType[], editing = false): TypeFormErrors {
  const out: TypeFormErrors = {}; const name = f.name.trim(); const code = f.code.trim().toLowerCase();
  if (!name) out.name = "Hãy nhập tên loại (ví dụ: Khối)."; else if (name.length > 120) out.name = "Tên loại tối đa 120 ký tự.";
  if (!editing) {
    if (!TYPE_CODE_RE.test(code)) out.code = "Mã gồm 1–40 ký tự a–z, 0–9, “_”, “-”, bắt đầu bằng chữ hoặc số (ví dụ: division).";
    else if (existing.some((t) => t.code.toLowerCase() === code)) out.code = "Mã này đã được dùng cho loại khác.";
  }
  if (!isUnitIcon(f.icon)) out.icon = "Hãy chọn một biểu tượng trong danh sách.";
  const md = (f.maxDepth ?? "").trim();
  if (md && !(/^\d+$/.test(md) && Number(md) >= 1 && Number(md) <= 100)) out.maxDepth = "Độ sâu tối đa là số nguyên từ 1 đến 100 (để trống = không giới hạn).";
  return out;
}
export type CatalogFormErrors = { name?: string; code?: string; rank?: string; description?: string };
export function validateCatalogForm(f: { name: string; code: string; rank?: string; description?: string }, existing: readonly { code: string }[], opts: { editing?: boolean; withRank?: boolean } = {}): CatalogFormErrors {
  const out: CatalogFormErrors = {}; const name = f.name.trim(); const code = f.code.trim();
  if (!name) out.name = "Hãy nhập tên."; else if (name.length > 120) out.name = "Tên tối đa 120 ký tự.";
  if (!opts.editing) {
    if (!CATALOG_CODE_RE.test(code)) out.code = "Mã gồm chữ, số, “.”, “_”, “-” (tối đa 40 ký tự), bắt đầu bằng chữ hoặc số.";
    else if (existing.some((x) => x.code.toLowerCase() === code.toLowerCase())) out.code = "Mã này đã được dùng (kể cả bản ghi đã tắt).";
  }
  if ((f.description ?? "").length > 500) out.description = "Mô tả tối đa 500 ký tự.";
  const rk = (f.rank ?? "").trim();
  if (opts.withRank && rk && !(/^\d+$/.test(rk) && Number(rk) <= 10_000)) out.rank = "Bậc là số nguyên từ 0 đến 10000 (để trống = không có bậc).";
  return out;
}
/**
 * What the UI says BEFORE the click about archiving a unit. ADVISORY: the counts it knows may be stale or partial (the server also refuses while a disabled employee is still a member), so the
 * button stays available and the server's ORG_UNIT_HAS_CHILDREN / ORG_UNIT_HAS_MEMBERS answer is authoritative. Unknown counts → no hint.
 */
export function archiveHint(u: Pick<OrgUnit, "childCount" | "directMemberCount" | "active">): string | null {
  if (!u.active) return null;
  const parts: string[] = [];
  if (u.childCount > 0) parts.push(`còn ${u.childCount} đơn vị con (lưu trữ hoặc chuyển chúng trước)`);
  if ((u.directMemberCount ?? 0) > 0) parts.push(`còn ${u.directMemberCount} thành viên trực tiếp (chuyển họ sang đơn vị khác trước)`);
  return parts.length ? `Có thể bị máy chủ từ chối: ${parts.join(", ")}.` : null;
}
/** restore needs the unit's own parent to be active; the server also checks the type, its rules, the code and the company status (RESTORE_CONFLICT) */
export function restoreHint(u: Pick<OrgUnit, "parentId" | "active">, units: readonly OrgUnit[]): string | null {
  if (u.active || !u.parentId) return null;
  const p = units.find((x) => x.id === u.parentId);
  return p && !p.active ? "Đơn vị cha đang được lưu trữ: khôi phục đơn vị cha trước." : null;
}

// ------------------------------------------------------------------------------------------------------------------------------- employees
export const EMPLOYEE_PAGE_SIZE = 20;
export const pageCount = (p: { total: number; size: number }) => Math.max(1, Math.ceil(p.total / Math.max(1, p.size)));
export const employeeName = (e: { displayName: string | null; username: string }) => e.displayName?.trim() || e.username;
/** the last page index the server accepts for this size (page * size <= offset limit): the pager never offers a page beyond it, whatever the total says */
export const lastReachablePage = (p: { total: number; size: number }): number => Math.min(pageCount(p) - 1, orgMaxPage(p.size));
/** true when rows exist beyond the last reachable page: the screen tells the person to narrow the search (never to page deeper) */
export const pagingCapped = (p: { total: number; size: number }): boolean => pageCount(p) - 1 > orgMaxPage(p.size);
export const PAGING_LIMITS = { maxSize: ORG_PAGE_SIZE_MAX, maxOffset: ORG_OFFSET_MAX, minSearch: ORG_SEARCH_MIN } as const;
/** the search box: fewer characters than the server needs are not sent; the screen says so instead of silently ignoring the text */
export function searchState(raw: string): { send: string | undefined; hint: string | null } {
  const t = raw.trim();
  if (!t) return { send: undefined, hint: null };
  return t.length < ORG_SEARCH_MIN ? { send: undefined, hint: `Nhập ít nhất ${ORG_SEARCH_MIN} ký tự để tìm.` } : { send: t, hint: null };
}
/** an employee's active memberships with the unit they sit in and the positions held within each (the unit name is "" while the unit list is not loaded) */
export type MembershipRow = { membership: Membership; unitName: string; unitPath: string; unitArchived: boolean; positions: { id: string; positionId: string; positionName: string; gradeId: string | null; gradeName: string; primary: boolean; version: number }[] };
export function membershipRows(e: Pick<Employee, "organizationMemberships" | "positions">, units: readonly OrgUnit[], positions: readonly Position[], grades: readonly Grade[], pathOf: (id: string) => string = (id) => unitPath(units, id)): MembershipRow[] {
  const posName = new Map(positions.map((p) => [p.id, p.name])); const gradeName = new Map(grades.map((g) => [g.id, g.name])); const unit = new Map(units.map((u) => [u.id, u]));
  return [...(e.organizationMemberships ?? [])].filter((m) => m.active).sort((a, b) => Number(b.primary) - Number(a.primary) || a.createdAt.localeCompare(b.createdAt)).map((m) => ({
    membership: m, unitName: unit.get(m.organizationUnitId)?.name ?? "", unitPath: pathOf(m.organizationUnitId), unitArchived: unit.get(m.organizationUnitId)?.active === false,
    positions: (e.positions ?? []).filter((p) => p.active && p.membershipId === m.id).sort((a, b) => Number(b.primary) - Number(a.primary)).map((p) => ({
      id: p.id, positionId: p.positionId, positionName: posName.get(p.positionId) ?? "Vị trí đã xóa", gradeId: p.gradeId, gradeName: p.gradeId ? gradeName.get(p.gradeId) ?? "Cấp bậc đã xóa" : "", primary: p.primary, version: p.version,
    })),
  }));
}

// ------------------------------------------------------------------------------------------------------------------------------- gate
/** `no-permission`: the server does not list the code this operation needs for the caller (UX hint: the server re-checks and answers 403) */
export type CapView = { state: "ready" } | { state: "not-ready"; reason: string } | { state: "no-permission"; reason: string };
const capView = (states: OrgCapabilityState[], held: boolean = true, missing = "Bạn chưa có quyền thực hiện thao tác này."): CapView => {
  if (!held) return { state: "no-permission", reason: missing };
  const nr = states.find((s) => s.status === "NOT_READY");
  return nr && nr.status === "NOT_READY" ? { state: "not-ready", reason: nr.reason } : { state: "ready" };
};
export type OrgAccess = { granted: true } | { granted: false; reason: string };
export type OrganizationPlan = {
  /** the structure screens: the server lists ORG_STRUCTURE_VIEW (adminScope.org). NOT TENANT_MEMBERS, NOT platformScope, NOT a role (a SYSTEM_ADMIN gets 403 on every organization route) */
  access: OrgAccess;
  /** the employee directory: the server lists EMPLOYEE_VIEW */
  employeeAccess: OrgAccess;
  /** exactly one tenant → fixed from the session (read-only); several → only the caller's own tenants, never a free id */
  fixedTenant: { id: string; name: string } | null; tenantChoice: { id: string; name: string }[];
  units: CapView; edit: CapView; types: CapView; typesManage: CapView; positions: CapView; catalogManage: CapView; assignOrg: CapView; assignPosition: CapView; employeeCreate: CapView; employeeStatus: CapView;
};
export function organizationPlan(scope: AdminScope, state: (id: OrgCapabilityId) => OrgCapabilityState, tenantId?: string | null): OrganizationPlan {
  // the companies the person can use ANY organization screen in (by the codes of EACH company); the plan then judges the SELECTED one with that company's own codes only
  const own = tenantsWith(scope, (c) => c.org.structureView || c.org.employeeView || c.org.positionGradeView).map((t) => ({ id: t.id, name: t.name }));
  const selected = tenantId ?? own[0]?.id ?? null; const o = capsOf(scope, selected).org; const of = (...ids: OrgCapabilityId[]) => ids.map(state);
  const noEdit = "Bạn xem được cơ cấu nhưng chưa có quyền thay đổi nó.", noEmp = "Bạn chưa có quyền quản lý nhân viên.", noCatalog = "Bạn chưa có quyền quản lý vị trí và cấp bậc.";
  const employeeAccounts = "Bạn cần quyền quản lý nhân viên và quản lý thành viên công ty để thêm, khóa hoặc mở khóa tài khoản nhân viên.";
  return {
    access: o.structureView ? { granted: true } : { granted: false, reason: "Máy chủ không liệt kê quyền xem cơ cấu tổ chức của công ty này cho tài khoản của bạn." },
    employeeAccess: o.employeeView ? { granted: true } : { granted: false, reason: "Máy chủ không liệt kê quyền xem danh bạ nhân viên của công ty này cho tài khoản của bạn." },
    fixedTenant: own.length === 1 ? own[0] : null, tenantChoice: own.length > 1 ? own : [],
    units: capView(of("listOrganizationUnits", "getOrganizationUnit")),
    edit: capView(of("createOrganizationUnit", "updateOrganizationUnit", "moveOrganizationUnit", "archiveOrganizationUnit", "restoreOrganizationUnit"), o.structureManage, noEdit),
    types: capView(of("listOrganizationUnitTypes")),
    typesManage: capView(of("createOrganizationUnitType", "updateOrganizationUnitType", "setOrganizationUnitTypeActive"), o.structureManage, noEdit),
    positions: capView(of("listPositions", "listGrades"), o.positionGradeView, "Bạn chưa có quyền xem vị trí và cấp bậc."),
    catalogManage: capView(of("createPosition", "updatePosition", "setPositionActive", "createGrade", "updateGrade", "setGradeActive"), o.positionGradeManage, noCatalog),
    assignOrg: capView(of("addMembership", "updateMembership", "removeMembership"), o.employeeManage, noEmp),
    assignPosition: capView(of("addEmployeePosition", "updateEmployeePosition", "removeEmployeePosition"), o.employeeManage, noEmp),
    employeeCreate: capView(of("createEmployee"), o.employeeProvision, employeeAccounts),
    employeeStatus: capView(of("setEmployeeActive"), o.employeeProvision, employeeAccounts),
  };
}

// ------------------------------------------------------------------------------------------------------------------------------- errors
export type OrgProblemKind =
  | "validation" | "duplicate" | "cycle" | "blocked" | "rule" | "version" | "conflict" | "forbidden" | "notfound"
  /** 503 ORG_STRUCTURE_BUSY: nothing was executed, the SAME request may be retried (after `retryAfterSeconds`) */
  | "busy"
  /** 501 ORG_PERSISTENCE_NOT_AVAILABLE: the company's organization data is not switched on on this server; no data, no fallback */
  | "unavailable-feature"
  | "unavailable" | "not-ready" | "unknown";
export type OrgProblem = { kind: OrgProblemKind; text: string; /** the same request may be sent again unchanged */ retryable?: boolean; retryAfterSeconds?: number; code?: string };
type Err = { code?: string; status?: number; message?: string; details?: unknown; retryable?: boolean; retryAfterSeconds?: number };
const detail = (e: Err, key: string): unknown => (e.details && typeof e.details === "object" ? (e.details as Record<string, unknown>)[key] : undefined);

const RULE_TEXT: Record<string, string> = {
  MAX_DEPTH: "Vượt độ sâu tối đa mà loại đơn vị cho phép (công ty đặt giới hạn này; máy chủ kiểm tra cả các đơn vị con).",
  ROOT_NOT_ALLOWED: "Loại đơn vị này không được làm đơn vị gốc.",
  PARENT_TYPE_NOT_ALLOWED: "Loại đơn vị này không đặt được dưới đơn vị cha đã chọn.",
  CHILD_TYPE_NOT_ALLOWED: "Đơn vị cha đã chọn không chứa loại đơn vị này.",
};
const RESTORE_TEXT: Record<string, string> = {
  NOT_ARCHIVED: "Đơn vị này không ở trạng thái lưu trữ. Tải lại cơ cấu.",
  TENANT_INACTIVE: "Công ty không ở trạng thái hoạt động nên chưa khôi phục được.",
  TYPE_DISABLED: "Loại của đơn vị đang bị tắt hoặc không còn. Bật lại loại đơn vị trước.",
  PARENT_ARCHIVED: "Đơn vị cha đang được lưu trữ. Khôi phục đơn vị cha trước.",
  TYPE_RULE: "Đơn vị không còn thỏa quy tắc đặt chỗ của loại hiện tại. Chuyển đơn vị tới vị trí hợp lệ rồi khôi phục.",
  CODE_TAKEN: "Một đơn vị khác đang dùng mã này ở cùng cấp. Đổi mã của một trong hai rồi khôi phục.",
};
const SAFE_NOT_FOUND = "Không tìm thấy mục này hoặc bạn không có quyền xem nó (có thể đã bị lưu trữ hoặc thuộc công ty khác). Tải lại.";
const BY_CODE: Record<string, [OrgProblemKind, string]> = {
  // busy: nothing was executed, the same request is retried (the text is built from Retry-After in orgProblem); rule: the text depends on details.reason
  ORG_STRUCTURE_BUSY: ["busy", ""], ORG_TYPE_RULE_VIOLATION: ["rule", "Thao tác không phù hợp với quy tắc đặt chỗ của loại đơn vị."],
  ORG_CYCLE: ["cycle", "Không thể chuyển đơn vị vào chính nó hoặc đơn vị con của nó (tạo vòng)."],
  VERSION_CONFLICT: ["version", "Bản ghi vừa được người khác thay đổi. Tải lại rồi thử lại; thay đổi của bạn chưa được lưu."],
  ORG_UNIT_CODE_TAKEN: ["duplicate", "Mã đơn vị này đã được dùng ở cùng cấp."], ORG_UNIT_TYPE_CODE_TAKEN: ["duplicate", "Mã loại đơn vị này đã được dùng."],
  POSITION_CODE_TAKEN: ["duplicate", "Mã vị trí này đã được dùng (kể cả vị trí đã tắt)."], GRADE_CODE_TAKEN: ["duplicate", "Mã cấp bậc này đã được dùng (kể cả cấp bậc đã tắt)."],
  ORG_MEMBERSHIP_EXISTS: ["duplicate", "Nhân viên đã thuộc đơn vị này."], POSITION_ASSIGNMENT_EXISTS: ["duplicate", "Nhân viên đã giữ vị trí này trong đơn vị đó."],
  ORG_UNIT_ARCHIVED: ["conflict", "Đơn vị đã được lưu trữ. Khôi phục nó hoặc chọn đơn vị khác."], ORG_UNIT_TYPE_DISABLED: ["conflict", "Loại đơn vị đang bị tắt."],
  POSITION_DISABLED: ["conflict", "Vị trí đang bị tắt."], GRADE_DISABLED: ["conflict", "Cấp bậc đang bị tắt."], ORG_MEMBERSHIP_INACTIVE: ["conflict", "Tư cách thành viên đơn vị này đã kết thúc."],
  EMPLOYEE_INACTIVE: ["conflict", "Nhân viên đang bị khóa. Mở khóa trước khi thay đổi cơ cấu của họ."],
  ORG_UNIT_HAS_CHILDREN: ["blocked", "Không lưu trữ được: đơn vị còn đơn vị con đang hoạt động. Lưu trữ hoặc chuyển chúng trước."],
  ORG_UNIT_HAS_MEMBERS: ["blocked", "Không lưu trữ được: đơn vị còn nhân viên là thành viên. Chuyển họ sang đơn vị khác trước."],
  EMPLOYEE_ORG_HAS_POSITIONS: ["blocked", "Không gỡ được khỏi đơn vị: nhân viên còn giữ vị trí trong đơn vị này. Gỡ các vị trí đó trước."],
  VALIDATION_FAILED: ["validation", "Dữ liệu chưa hợp lệ. Kiểm tra lại các trường."], INVALID_CODE: ["validation", "Mã chưa đúng định dạng. Kiểm tra lại."],
  QUERY_TOO_SHORT: ["validation", `Từ khóa tìm kiếm cần ít nhất ${ORG_SEARCH_MIN} ký tự.`],
  OFFSET_TOO_LARGE: ["validation", `Không xem sâu hơn ${ORG_OFFSET_MAX.toLocaleString("vi-VN")} kết quả đầu được. Hãy thu hẹp tìm kiếm (từ khóa, đơn vị, vị trí) thay vì chuyển trang.`],
  TENANT_SUSPENDED: ["forbidden", "Công ty đang bị tạm khóa: bạn chỉ xem được, chưa thay đổi được cơ cấu, nhân viên hay vị trí."],
  FORBIDDEN: ["forbidden", "Bạn không có quyền thực hiện thao tác này."],
  ORG_PERSISTENCE_NOT_AVAILABLE: ["unavailable-feature", "Cơ cấu tổ chức chưa được bật trên máy chủ này, nên chưa có dữ liệu để xem hoặc lưu. Không có bản sao tạm nào được dùng thay thế. Liên hệ quản trị hệ thống."],
  TENANT_NOT_FOUND: ["notfound", SAFE_NOT_FOUND], ORG_UNIT_NOT_FOUND: ["notfound", SAFE_NOT_FOUND], ORG_UNIT_TYPE_NOT_FOUND: ["notfound", SAFE_NOT_FOUND], EMPLOYEE_NOT_FOUND: ["notfound", SAFE_NOT_FOUND],
  POSITION_NOT_FOUND: ["notfound", SAFE_NOT_FOUND], GRADE_NOT_FOUND: ["notfound", SAFE_NOT_FOUND], ORG_MEMBERSHIP_NOT_FOUND: ["notfound", SAFE_NOT_FOUND], POSITION_ASSIGNMENT_NOT_FOUND: ["notfound", SAFE_NOT_FOUND],
};
const secs = (e: Err): number | undefined => {
  const d = detail(e, "retryAfterSeconds"); const v = typeof e.retryAfterSeconds === "number" ? e.retryAfterSeconds : typeof d === "number" ? d : undefined;
  return v !== undefined && Number.isFinite(v) && v > 0 ? Math.ceil(v) : undefined;
};
/**
 * Every refusal of the organization routes in words. The server's code decides (never the English message, which is never shown); an unknown code falls back to the HTTP status, and an unknown one shows only its code.
 * 503 ORG_STRUCTURE_BUSY is a RETRY state (nothing was executed, resend the same request); 501 ORG_PERSISTENCE_NOT_AVAILABLE is a fail-closed "not available" state. Neither is ever a success.
 */
export function orgProblem(e: unknown): OrgProblem {
  if (e instanceof OrganizationNotReady) return { kind: "not-ready", text: `Chưa sẵn sàng: ${e.reason}` };
  const x = (e ?? {}) as Err; const code = x.code;
  if (code === "RESTORE_CONFLICT") { const r = String(detail(x, "reason") ?? ""); return { kind: "conflict", code, text: RESTORE_TEXT[r] ?? "Chưa khôi phục được đơn vị vì xung đột với dữ liệu hiện tại." }; }
  const hit = code ? BY_CODE[code] : undefined;
  if (hit && hit[0] === "busy") {
    const n = secs(x);
    return { kind: "busy", retryable: true, retryAfterSeconds: n, code, text: `Hệ thống đang xử lý một thay đổi cơ cấu khác nên chưa thực hiện được thao tác của bạn (chưa có gì được lưu). ${n ? `Hãy thử lại sau khoảng ${n} giây.` : "Hãy thử lại sau ít giây."}` };
  }
  if (hit && hit[0] === "rule") { const r = String(detail(x, "reason") ?? ""); return { kind: "rule", code, text: RULE_TEXT[r] ?? hit[1] }; }
  if (hit) return { kind: hit[0], text: hit[1], code };
  const s = x.status ?? 0;
  if (s === 501) return { kind: "unavailable-feature", code, text: BY_CODE.ORG_PERSISTENCE_NOT_AVAILABLE[1] };
  if (s === 400 || s === 422) return { kind: "validation", text: "Dữ liệu chưa hợp lệ. Kiểm tra lại các trường." };
  if (s === 401) return { kind: "forbidden", text: "Phiên đăng nhập đã hết. Hãy đăng nhập lại." };
  if (s === 403) return { kind: "forbidden", text: "Bạn không có quyền thực hiện thao tác này." };
  if (s === 404) return { kind: "notfound", text: SAFE_NOT_FOUND };
  if (s === 409) return { kind: "conflict", text: "Thao tác xung đột với dữ liệu hiện tại. Tải lại rồi thử lại." };
  if (s === 503) return { kind: "unavailable", retryable: true, retryAfterSeconds: secs(x), text: "Máy chủ chưa sẵn sàng. Thử lại sau." };
  if (s >= 500 || s === 0) return { kind: "unavailable", text: "Không kết nối được máy chủ. Chưa rõ thao tác đã được ghi hay chưa: tải lại để kiểm tra." };
  return { kind: "unknown", text: code ? `Chưa thực hiện được (mã ${code}).` : "Chưa thực hiện được." };   // the server's own (English) message is never shown, only its code as a reference
}
/** problems that mean "your copy is stale": the screen offers a reload */
export const needsReload = (p: OrgProblem): boolean => p.kind === "version" || p.kind === "notfound" || p.kind === "conflict";

/**
 * Creating an employee provisions an ACCOUNT: the contract needs EMPLOYEE_MANAGE **and** TENANT_MEMBERS (final contract §9). The provisioning plan alone only knows the tenant side, so the employee
 * screens ask it through this: without both codes the create state is `forbidden` with the reason (the button is unavailable, nothing is sent).
 */
export function employeeProvisioningPlan<P extends { create: { state: string } }>(scope: AdminScope, plan: P, tenantId: string | null | undefined): P {
  return capsOf(scope, tenantId).org.employeeProvision ? plan : { ...plan, create: { state: "forbidden", reason: "Bạn cần quyền quản lý nhân viên và quản lý thành viên công ty này để thêm nhân viên." } };
}
