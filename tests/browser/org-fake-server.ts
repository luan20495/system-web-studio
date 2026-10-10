// @class: harness — an in-memory FAKE of the typed organization transport (`api.org`); NOT a backend and NOT a backend E2E
/**
 * TEST-ONLY. The browser harness runs the REAL service (`createOrganizationApi`) and the REAL screens over this object, which stands where the network would: it keeps a small organization in memory and
 * answers the way the CONTRACT (docs/parallel/c0/ORGANIZATION_API_CONTRACT_FOR_C5.md) says the server answers: versions (409 VERSION_CONFLICT), cycles, archive blocks, type rules, restore conflicts, the
 * directory limits (400 VALIDATION_FAILED / OFFSET_TOO_LARGE / QUERY_TOO_SHORT), 503 ORG_STRUCTURE_BUSY with Retry-After, 501 ORG_PERSISTENCE_NOT_AVAILABLE, 403 TENANT_SUSPENDED.
 * It proves what the SCREENS do with those answers, never what the real server answers (that is E2E-ORG01 against a flag-ON stack). Its numeric limits are written out here on purpose, NOT imported from the
 * client: a client that asks for more than the server accepts must be caught by THIS code.
 * Scenarios (`?s=`): ok, off (501), down (503 without code), busy / busy2 / busy-always (503 ORG_STRUCTURE_BUSY on structural writes), version, suspended, blocked, cycle, rule, restore-conflict, notfound,
 * empty, big, deep10, deep60, emp-empty, emp-down, emp-pageempty, emp-10k, emp-10k-bigorg, emp-huge (5 000 000 rows, the server refuses what the client must never ask).
 */
import { ApiError } from "../../packages/api-client/src/core";
import type { OrgApi } from "../../packages/api-client/src/org";
import type {
  OrgEmployeeDto, OrgEmployeePositionDto, OrgGradeDto, OrgMembershipDto, OrgPositionDto, OrgUnitDto, OrgUnitNodeDto, OrgUnitTypeDto, OrgUnitTypeRules,
} from "@xweb/types";

const ISO = "2026-10-10T00:00:00Z";
const NO_RULES: OrgUnitTypeRules = { allowedParentTypeIds: null, allowedChildTypeIds: null, allowRoot: null, maxDepth: null };
export const err = (status: number, code: string, details?: unknown, retryAfter?: number) => new ApiError(status, code, "server text that the UI must never show", "req-fake", details, retryAfter === undefined ? undefined : true, retryAfter);
const fold = (s: string) => s.normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/đ/gi, "d").toLowerCase();

type Rec = { name: string; args: unknown[] };
export type FakeOrg = {
  api: OrgApi;
  /** the live state, for assertions on "what the server holds" */
  state: () => { units: OrgUnitDto[]; types: OrgUnitTypeDto[]; positions: OrgPositionDto[]; grades: OrgGradeDto[]; employees: OrgEmployeeDto[]; requests: Rec[]; refusedLimits: string[] };
  /** a concurrent change by someone else: bumps the version of a unit / membership so the next write with the old version is a 409 */
  touch: (kind: "unit" | "membership" | "type", id: string) => void;
  /** the next `n` structural writes answer 503 ORG_STRUCTURE_BUSY (nothing executed) */
  busy: (n: number) => void;
  /** switch the server to "feature off" (501) / back on */
  off: (on: boolean) => void;
  /** change what a scenario switch does, mid-test */
  set: (k: "version" | "suspended" | "blocked" | "cycle" | "rule" | "restoreConflict", on: boolean) => void;
};

export function createFakeOrg(S: string, record: (name: string, args: unknown[]) => void): FakeOrg {
  let seq = 100; const nid = (p: string) => `${p}${++seq}`;
  const flags = { off: S === "off", down: S === "down", version: S === "version", suspended: S === "suspended", blocked: S === "blocked", cycle: S === "cycle", rule: S === "rule", restoreConflict: S === "restore-conflict", notfound: S === "notfound" };
  let busyLeft = S === "busy" ? 1 : S === "busy2" ? 2 : 0; const busyAlways = S === "busy-always";
  const empty = S === "empty"; const BIGORG = S === "emp-10k-bigorg" || S === "big"; const HUGE = S === "emp-huge";
  const requests: Rec[] = []; const refusedLimits: string[] = [];

  // ---- types
  const mkType = (id: string, code: string, name: string, icon: string, rules: Partial<OrgUnitTypeRules> = {}): OrgUnitTypeDto => ({ id, tenantId: "t1", name, code, icon, active: true, rules: { ...NO_RULES, ...rules }, version: 0, createdAt: ISO, updatedAt: ISO });
  let types: OrgUnitTypeDto[] = empty ? [] : [mkType("t-div", "division", "Khối", "building"), mkType("t-dept", "dept", "Phòng", "briefcase", { allowedParentTypeIds: ["t-div"] }), mkType("t-team", "team", "Team", "users", { allowedParentTypeIds: ["t-dept", "t-team"] })];
  // ---- units
  const mkUnit = (id: string, parentId: string | null, typeId: string, name: string, o: Partial<OrgUnitDto> = {}): OrgUnitDto => ({ id, tenantId: "t1", typeId, parentId, name, code: id.toUpperCase().replace(/[^A-Z0-9]/g, "-"), sortOrder: 0, metadata: {}, active: true, version: 0, createdAt: ISO, updatedAt: ISO, archivedAt: null, ...o });
  const gen = (n: number, parentOf: (i: number) => number | null, name: (i: number) => string): OrgUnitDto[] => Array.from({ length: n }, (_, i) => mkUnit(`g${i}`, parentOf(i) === null ? null : `g${parentOf(i)}`, i === 0 ? "t-div" : "t-dept", name(i), i % 97 === 0 && i > 0 ? { active: false, archivedAt: ISO } : {}));
  const GENERATED: Record<string, () => OrgUnitDto[]> = {
    big: () => gen(2000, (i) => (i === 0 ? null : Math.floor((i - 1) / 4)), (i) => `Đơn vị ${String(i).padStart(4, "0")} — phòng ban số ${i}`),
    deep10: () => gen(10, (i) => (i === 0 ? null : i - 1), (i) => `Cấp ${i + 1}: Bộ phận phụ trách chăm sóc khách hàng khu vực miền Trung và Tây Nguyên (nhóm ${i + 1})`),
    deep60: () => gen(60, (i) => (i === 0 ? null : i - 1), (i) => `Cấp ${i + 1} — Đơn vị lồng nhau`),
  };
  let units: OrgUnitDto[] = GENERATED[S] ? GENERATED[S]() : BIGORG ? GENERATED.big() : empty ? [] : [
    mkUnit("tech", null, "t-div", "Khối Công nghệ"), mkUnit("mobile", "tech", "t-dept", "Mobile"), mkUnit("flutter", "mobile", "t-team", "Flutter Team"), mkUnit("web", "tech", "t-dept", "Web"), mkUnit("hr", null, "t-div", "Nhân sự"),
    mkUnit("old", "hr", "t-dept", "Phòng cũ", { active: false, archivedAt: ISO }),
  ];
  // ---- catalogs
  const mkPos = (id: string, code: string, name: string): OrgPositionDto => ({ id, tenantId: "t1", name, code, description: null, active: true, version: 0, createdAt: ISO, updatedAt: ISO });
  const mkGrade = (id: string, code: string, name: string, rank: number | null): OrgGradeDto => ({ id, tenantId: "t1", name, code, rank, description: null, active: true, version: 0, createdAt: ISO, updatedAt: ISO });
  let positions: OrgPositionDto[] = [mkPos("p-eng", "ENG", "Kỹ sư"), mkPos("p-lead", "LEAD", "Trưởng nhóm")];
  let grades: OrgGradeDto[] = [mkGrade("g-jr", "JR", "Junior", 1), mkGrade("g-sr", "SR", "Senior", 3)];
  // ---- employees, memberships, held positions
  type Base = { userId: string; username: string; displayName: string | null; email: string | null; tenantRole: string; active: boolean; accountActivated: boolean };
  const N_EMP = HUGE ? 0 : S === "emp-empty" ? 0 : S.startsWith("emp-10k") ? 10000 : 45;
  let base: Base[] = Array.from({ length: N_EMP }, (_, i): Base => { const n = i + 1; return { userId: `u${String(n).padStart(2, "0")}`, username: `user${n}`, displayName: n === 5 ? "Nguyễn Đức Anh" : `Nhân viên ${n}`, email: `u${n}@acme.vn`, tenantRole: n === 1 ? "TENANT_ADMIN" : "MEMBER", active: n !== 7 && n !== 8, accountActivated: n !== 9 }; });
  let memberships: OrgMembershipDto[] = []; let held: OrgEmployeePositionDto[] = [];
  const mkMember = (userId: string, unit: string, o: Partial<OrgMembershipDto> = {}): OrgMembershipDto => ({ id: nid("m"), tenantId: "t1", userId, organizationUnitId: unit, relationType: "MEMBER", primary: true, active: true, version: 0, createdAt: ISO, updatedAt: ISO, ...o });
  const mkHeld = (m: OrgMembershipDto, positionId: string, o: Partial<OrgEmployeePositionDto> = {}): OrgEmployeePositionDto => ({ id: nid("ep"), tenantId: "t1", userId: m.userId, membershipId: m.id, organizationUnitId: m.organizationUnitId, positionId, gradeId: null, primary: true, active: true, version: 0, createdAt: ISO, updatedAt: ISO, ...o });
  if (!empty) base.forEach((b, i) => {
    const unit = BIGORG ? `g${(i * 7) % 2000}` : ["flutter", "web", "hr"][i % 3]; if (!units.some((u) => u.id === unit && u.active)) return;
    const m = mkMember(b.userId, unit); memberships.push(m); held.push(mkHeld(m, i % 2 ? "p-eng" : "p-lead", { gradeId: i % 3 === 0 ? "g-sr" : null }));
    if (i === 2) memberships.push(mkMember(b.userId, "mobile", { primary: false, relationType: "MANAGER" }));          // user3: a second unit
  });

  // ---- helpers
  const by = <T extends { id: string }>(l: T[], id: string) => l.find((x) => x.id === id);
  const log = (name: string, args: unknown[]) => { requests.push({ name, args }); record(name, args); };
  const gate = (write: boolean, structural = false) => {
    if (flags.off) throw err(501, "ORG_PERSISTENCE_NOT_AVAILABLE");
    if (write && flags.suspended) throw err(403, "TENANT_SUSPENDED");
    if (structural && (busyAlways || busyLeft > 0)) { if (busyLeft > 0) busyLeft--; throw err(503, "ORG_STRUCTURE_BUSY", { retryable: true, retryAfterSeconds: 1 }, 1); }
  };
  const stale = <T extends { id: string; version: number }>(cur: T | undefined, v: number | undefined, notFound: string): T => {
    if (!cur) throw err(404, notFound);
    if (v === undefined || v === null) throw err(400, "VALIDATION_FAILED");
    if (flags.version || cur.version !== v) throw err(409, "VERSION_CONFLICT", { currentVersion: cur.version });
    return cur;
  };
  const set = <T extends { id: string; version: number; updatedAt: string }>(list: T[], id: string, patch: Partial<T>): T[] => list.map((x) => (x.id === id ? { ...x, ...patch, version: x.version + 1, updatedAt: ISO } : x));
  const childrenOf = (id: string | null) => units.filter((u) => u.parentId === id);
  const below = (id: string): Set<string> => { const out = new Set<string>(); const st = [id]; while (st.length) { const c = st.pop()!; for (const u of units) if (u.parentId === c && !out.has(u.id)) { out.add(u.id); st.push(u.id); } } return out; };
  const levelOf = (id: string | null): number => { let n = 0; for (let cur = id ? by(units, id) : undefined; cur && n < 1_000; cur = cur.parentId ? by(units, cur.parentId) : undefined) n++; return n; };
  const typeOf = (id: string) => by(types, id);
  /** the placement rules, the way the contract describes them; the reason names the rule */
  const placement = (type: OrgUnitTypeDto, parent: OrgUnitDto | null): string | null => {
    const r = type.rules; const parents = r.allowedParentTypeIds;
    if (!parent) { const rootOk = r.allowRoot ?? (parents === null || parents.length === 0); return rootOk ? null : "ROOT_NOT_ALLOWED"; }
    if (parents !== null && !parents.includes(parent.typeId)) return "PARENT_TYPE_NOT_ALLOWED";
    const pt = typeOf(parent.typeId); if (pt?.rules.allowedChildTypeIds != null && !pt.rules.allowedChildTypeIds.includes(type.id)) return "CHILD_TYPE_NOT_ALLOWED";
    if (r.maxDepth !== null && levelOf(parent.id) + 1 > r.maxDepth) return "MAX_DEPTH";
    return null;
  };
  const ruleError = (reason: string, unitId?: string) => err(409, "ORG_TYPE_RULE_VIOLATION", { reason, ...(unitId ? { unitId } : {}) });
  const upper = (c: string | undefined) => { const t = (c ?? "").trim(); if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,59}$/.test(t)) throw err(400, "INVALID_CODE"); return t.toUpperCase(); };
  const codeFree = (parentId: string | null, code: string, except?: string) => !units.some((u) => u.active && u.parentId === parentId && u.code === code && u.id !== except);

  // ---- counts (the contract: direct = ACTIVE memberships of ACTIVE employees on exactly the unit; subtree = DISTINCT active employees over the unit and its non-archived descendants; archived = 0 / 0)
  const counts = () => {
    const active = new Set(base.filter((b) => b.active).map((b) => b.userId)); const direct = new Map<string, number>(); const own = new Map<string, Set<string>>();
    for (const m of memberships) if (m.active && active.has(m.userId)) { direct.set(m.organizationUnitId, (direct.get(m.organizationUnitId) ?? 0) + 1); (own.get(m.organizationUnitId) ?? own.set(m.organizationUnitId, new Set()).get(m.organizationUnitId)!).add(m.userId); }
    const live = units.filter((u) => u.active); const order: OrgUnitDto[] = []; const st = live.filter((u) => !u.parentId || !live.some((p) => p.id === u.parentId)); const seen = new Set<string>();
    while (st.length) { const u = st.pop()!; if (seen.has(u.id)) continue; seen.add(u.id); order.push(u); for (const c of live) if (c.parentId === u.id) st.push(c); }
    const acc = new Map<string, Set<string>>(); for (const u of order) acc.set(u.id, new Set(own.get(u.id) ?? []));
    for (let i = order.length - 1; i >= 0; i--) { const u = order[i]; if (u.parentId && acc.has(u.parentId)) { const p = acc.get(u.parentId)!; for (const x of acc.get(u.id)!) p.add(x); } }
    return (id: string): { direct: number; subtree: number } => (acc.has(id) ? { direct: direct.get(id) ?? 0, subtree: acc.get(id)!.size } : { direct: 0, subtree: 0 });
  };
  const sorted = (l: OrgUnitDto[]) => [...l].sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name, "vi", { sensitivity: "base" }) || a.id.localeCompare(b.id));

  // ---- employee assembly
  const employee = (b: Base): OrgEmployeeDto => {
    const ms = memberships.filter((m) => m.userId === b.userId && m.active); const primary = ms.find((m) => m.primary);
    return { userId: b.userId, tenantId: "t1", username: b.username, displayName: b.displayName, email: b.email, active: b.active, accountEnabled: true, accountActivated: b.accountActivated, tenantRole: b.tenantRole, primaryOrganizationUnitId: primary?.organizationUnitId ?? null,
      positions: held.filter((h) => h.userId === b.userId && h.active), organizationMemberships: ms };
  };
  const baseOf = (userId: string) => { const b = base.find((x) => x.userId === userId); if (!b) throw err(404, "EMPLOYEE_NOT_FOUND"); return b; };
  const activeEmployee = (userId: string) => { const b = baseOf(userId); if (!b.active) throw err(409, "EMPLOYEE_INACTIVE"); return b; };
  const activeUnit = (id: string) => { const u = by(units, id); if (!u) throw err(404, "ORG_UNIT_NOT_FOUND"); if (!u.active) throw err(409, "ORG_UNIT_ARCHIVED"); return u; };
  const relation = (raw?: string) => { const r = (raw?.trim() || "MEMBER").toUpperCase(); if (!/^[A-Z][A-Z0-9_]{0,31}$/.test(r)) throw err(400, "INVALID_CODE"); return r; };
  const promote = (userId: string, except?: string) => { memberships = memberships.map((m) => (m.userId === userId && m.active && m.primary && m.id !== except ? { ...m, primary: false, version: m.version + 1 } : m)); };

  const api: OrgApi = {
    // ---------------------------------------------------------------------------------------------------------------------------- types
    listUnitTypes: async (_t, includeInactive = true) => { log("listUnitTypes", [includeInactive]); gate(false); return types.filter((t) => includeInactive || t.active); },
    getUnitType: async (_t, id) => { log("getUnitType", [id]); gate(false); const t = by(types, id); if (!t) throw err(404, "ORG_UNIT_TYPE_NOT_FOUND"); return t; },
    createUnitType: async (_t, b) => {
      log("createUnitType", [b]); gate(true, true);
      const code = (b.code ?? "").trim(); if (!/^[a-z0-9][a-z0-9_-]{0,39}$/.test(code)) throw err(400, "INVALID_CODE"); if (!b.name?.trim() || b.name.length > 120) throw err(400, "VALIDATION_FAILED");
      if (types.some((t) => t.code === code)) throw err(409, "ORG_UNIT_TYPE_CODE_TAKEN");
      const n: OrgUnitTypeDto = { ...mkType(nid("ty"), code, b.name.trim(), b.icon ?? "", b.rules), rules: { ...NO_RULES, ...(b.rules ?? {}) } }; types = [...types, n]; return n;
    },
    updateUnitType: async (_t, id, b) => {
      log("updateUnitType", [id, b]); gate(true, !!b.rules); const cur = stale(by(types, id), b.expectedVersion, "ORG_UNIT_TYPE_NOT_FOUND");
      if (b.rules) { const next = { ...cur, rules: { ...NO_RULES, ...b.rules } }; for (const u of units.filter((x) => x.active && x.typeId === id)) { const bad = placement(next, u.parentId ? by(units, u.parentId) ?? null : null); if (bad) throw ruleError(bad, u.id); } }
      types = set(types, id, { ...(b.name === undefined ? {} : { name: b.name }), ...(b.icon === undefined ? {} : { icon: b.icon }), ...(b.rules ? { rules: { ...NO_RULES, ...b.rules } } : {}) }); return by(types, id)!;
    },
    disableUnitType: async (_t, id, v) => { log("disableUnitType", [id, v]); gate(true); stale(by(types, id), v, "ORG_UNIT_TYPE_NOT_FOUND"); types = set(types, id, { active: false }); return by(types, id)!; },
    enableUnitType: async (_t, id, v) => { log("enableUnitType", [id, v]); gate(true); stale(by(types, id), v, "ORG_UNIT_TYPE_NOT_FOUND"); types = set(types, id, { active: true }); return by(types, id)!; },

    // ---------------------------------------------------------------------------------------------------------------------------- units
    unitTree: async (_t, includeArchived = false) => {
      log("unitTree", [includeArchived]); if (flags.down) throw err(503, "UNAVAILABLE"); gate(false);
      const c = counts(); const list = units.filter((u) => includeArchived || u.active); const kids = new Map<string | null, OrgUnitDto[]>(); const ids = new Set(list.map((u) => u.id));
      for (const u of sorted(list)) { const key = u.parentId && ids.has(u.parentId) ? u.parentId : null; (kids.get(key) ?? kids.set(key, []).get(key)!).push(u); }
      const make = (u: OrgUnitDto): OrgUnitNodeDto => { const k = c(u.id); return { unit: u, children: [], directMemberCount: k.direct, subtreeEmployeeCount: k.subtree }; };
      const roots = (kids.get(null) ?? []).map(make); const stack = [...roots]; const byId = new Map(roots.map((n) => [n.unit.id, n]));
      while (stack.length) { const n = stack.pop()!; n.children = (kids.get(n.unit.id) ?? []).map((u) => { const m = make(u); byId.set(u.id, m); stack.push(m); return m; }); }
      return roots;
    },
    unitList: async (_t, includeArchived = false) => { log("unitList", [includeArchived]); gate(false); return sorted(units.filter((u) => includeArchived || u.active)); },
    getUnit: async (_t, id) => {
      log("getUnit", [id]); gate(false); if (flags.notfound) throw err(404, "ORG_UNIT_NOT_FOUND");
      const u = by(units, id); if (!u) throw err(404, "ORG_UNIT_NOT_FOUND"); const k = counts()(id); const path: OrgUnitDto[] = []; for (let cur: OrgUnitDto | undefined = u; cur && path.length < 1_000; cur = cur.parentId ? by(units, cur.parentId) : undefined) path.unshift(cur);
      return { unit: u, path, activeChildCount: childrenOf(id).filter((c) => c.active).length, activeMemberCount: k.direct, directMemberCount: k.direct, subtreeEmployeeCount: k.subtree };
    },
    createUnit: async (_t, b) => {
      log("createUnit", [b]); gate(true, true); const type = typeOf(b.typeId); if (!type) throw err(404, "ORG_UNIT_TYPE_NOT_FOUND"); if (!type.active) throw err(409, "ORG_UNIT_TYPE_DISABLED");
      if (!b.name?.trim() || b.name.length > 160) throw err(400, "VALIDATION_FAILED"); const code = upper(b.code); const parent = b.parentId ? by(units, b.parentId) ?? null : null; if (b.parentId && !parent) throw err(404, "ORG_UNIT_NOT_FOUND"); if (parent && !parent.active) throw err(409, "ORG_UNIT_ARCHIVED");
      if (flags.rule) throw ruleError("MAX_DEPTH"); const bad = placement(type, parent); if (bad) throw ruleError(bad); if (!codeFree(b.parentId, code)) throw err(409, "ORG_UNIT_CODE_TAKEN");
      const n = mkUnit(nid("n"), b.parentId, b.typeId, b.name.trim(), { code, sortOrder: b.sortOrder ?? 0 }); units = [...units, n]; return n;
    },
    updateUnit: async (_t, id, b) => {
      log("updateUnit", [id, b]); gate(true); const cur = stale(by(units, id), b.expectedVersion, "ORG_UNIT_NOT_FOUND"); const code = b.code === undefined ? cur.code : upper(b.code);
      if (code !== cur.code && !codeFree(cur.parentId, code, id)) throw err(409, "ORG_UNIT_CODE_TAKEN");
      units = set(units, id, { ...(b.name === undefined ? {} : { name: b.name.trim() }), code, ...(b.sortOrder === undefined ? {} : { sortOrder: b.sortOrder }) }); return by(units, id)!;
    },
    moveUnit: async (_t, id, b) => {
      log("moveUnit", [id, b]); gate(true, true); if (!("newParentId" in b)) throw err(400, "VALIDATION_FAILED"); const cur = stale(by(units, id), b.expectedVersion, "ORG_UNIT_NOT_FOUND"); if (!cur.active) throw err(409, "ORG_UNIT_ARCHIVED");
      const parent = b.newParentId ? by(units, b.newParentId) ?? null : null; if (b.newParentId && !parent) throw err(404, "ORG_UNIT_NOT_FOUND"); if (parent && !parent.active) throw err(409, "ORG_UNIT_ARCHIVED");
      if (flags.cycle || b.newParentId === id || (b.newParentId && below(id).has(b.newParentId))) throw err(409, "ORG_CYCLE");
      if (flags.rule) throw ruleError("MAX_DEPTH"); const type = typeOf(cur.typeId)!; const bad = placement(type, parent); if (bad) throw ruleError(bad, id);
      if (!codeFree(b.newParentId, cur.code, id)) throw err(409, "ORG_UNIT_CODE_TAKEN");
      units = set(units, id, { parentId: b.newParentId }); return by(units, id)!;
    },
    archiveUnit: async (_t, id, v) => {
      log("archiveUnit", [id, v]); gate(true); const cur = stale(by(units, id), v, "ORG_UNIT_NOT_FOUND");
      if (flags.blocked) throw err(409, "ORG_UNIT_HAS_MEMBERS"); if (childrenOf(id).some((c) => c.active)) throw err(409, "ORG_UNIT_HAS_CHILDREN"); if (memberships.some((m) => m.active && m.organizationUnitId === id)) throw err(409, "ORG_UNIT_HAS_MEMBERS");
      units = set(units, id, { active: false, archivedAt: ISO }); return by(units, cur.id)!;
    },
    restoreUnit: async (_t, id, v) => {
      log("restoreUnit", [id, v]); gate(true, true); const cur = stale(by(units, id), v, "ORG_UNIT_NOT_FOUND"); const rc = (reason: string) => err(409, "RESTORE_CONFLICT", { reason });
      if (flags.restoreConflict) throw rc("PARENT_ARCHIVED"); if (cur.active) throw rc("NOT_ARCHIVED"); const type = typeOf(cur.typeId); if (!type?.active) throw rc("TYPE_DISABLED");
      const parent = cur.parentId ? by(units, cur.parentId) : undefined; if (cur.parentId && (!parent || !parent.active)) throw rc("PARENT_ARCHIVED"); if (placement(type, parent ?? null)) throw rc("TYPE_RULE"); if (!codeFree(cur.parentId, cur.code, id)) throw rc("CODE_TAKEN");
      units = set(units, id, { active: true, archivedAt: null }); return by(units, id)!;
    },

    // ---------------------------------------------------------------------------------------------------------------------------- positions / grades
    listPositions: async (_t, inc = true) => { log("listPositions", [inc]); gate(false); return positions.filter((p) => inc || p.active); },
    createPosition: async (_t, b) => { log("createPosition", [b]); gate(true); const code = (b.code ?? "").trim(); if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$/.test(code)) throw err(400, "INVALID_CODE"); if (positions.some((p) => p.code.toLowerCase() === code.toLowerCase())) throw err(409, "POSITION_CODE_TAKEN"); const n = { ...mkPos(nid("p"), code, b.name.trim()), description: b.description ?? null }; positions = [...positions, n]; return n; },
    updatePosition: async (_t, id, b) => { log("updatePosition", [id, b]); gate(true); stale(by(positions, id), b.expectedVersion, "POSITION_NOT_FOUND"); positions = set(positions, id, { ...(b.name === undefined ? {} : { name: b.name }), ...(b.description === undefined ? {} : { description: b.description || null }) }); return by(positions, id)!; },
    disablePosition: async (_t, id, v) => { log("disablePosition", [id, v]); gate(true); stale(by(positions, id), v, "POSITION_NOT_FOUND"); positions = set(positions, id, { active: false }); return by(positions, id)!; },
    enablePosition: async (_t, id, v) => { log("enablePosition", [id, v]); gate(true); stale(by(positions, id), v, "POSITION_NOT_FOUND"); positions = set(positions, id, { active: true }); return by(positions, id)!; },
    listGrades: async (_t, inc = true) => { log("listGrades", [inc]); gate(false); return grades.filter((g) => inc || g.active); },
    createGrade: async (_t, b) => { log("createGrade", [b]); gate(true); const code = (b.code ?? "").trim(); if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$/.test(code)) throw err(400, "INVALID_CODE"); if (grades.some((g) => g.code.toLowerCase() === code.toLowerCase())) throw err(409, "GRADE_CODE_TAKEN"); const n = { ...mkGrade(nid("g"), code, b.name.trim(), b.rank ?? null), description: b.description ?? null }; grades = [...grades, n]; return n; },
    updateGrade: async (_t, id, b) => { log("updateGrade", [id, b]); gate(true); stale(by(grades, id), b.expectedVersion, "GRADE_NOT_FOUND"); grades = set(grades, id, { ...(b.name === undefined ? {} : { name: b.name }), ...(b.clearRank ? { rank: null } : b.rank === undefined ? {} : { rank: b.rank }), ...(b.description === undefined ? {} : { description: b.description || null }) }); return by(grades, id)!; },
    disableGrade: async (_t, id, v) => { log("disableGrade", [id, v]); gate(true); stale(by(grades, id), v, "GRADE_NOT_FOUND"); grades = set(grades, id, { active: false }); return by(grades, id)!; },
    enableGrade: async (_t, id, v) => { log("enableGrade", [id, v]); gate(true); stale(by(grades, id), v, "GRADE_NOT_FOUND"); grades = set(grades, id, { active: true }); return by(grades, id)!; },

    // ---------------------------------------------------------------------------------------------------------------------------- employees
    listEmployees: async (_t, q = {}) => {
      log("listEmployees", [q]); gate(false); if (S === "emp-down") throw err(500, "INTERNAL");
      const page = q.page ?? 0; const size = q.size ?? 25;
      if (page < 0 || size < 1 || size > 100) { refusedLimits.push(`size/page ${size}/${page}`); throw err(400, "VALIDATION_FAILED"); }
      if (page * size > 10_000) { refusedLimits.push(`offset ${page * size}`); throw err(400, "OFFSET_TOO_LARGE"); }
      const text = q.q?.trim(); if (text !== undefined && text.length < 2 && text.length > 0) { refusedLimits.push(`q ${text.length}`); throw err(400, "QUERY_TOO_SHORT"); }
      if (HUGE) return { items: Array.from({ length: Math.min(size, 5_000_000 - page * size) }, (_, i) => employee({ userId: `h${page * size + i}`, username: `huge${page * size + i}`, displayName: `Người ${page * size + i}`, email: null, tenantRole: "MEMBER", active: true, accountActivated: true })), total: 5_000_000, page, size };
      if (S === "emp-pageempty" && page > 0) return { items: [], total: base.length, page, size };
      if (q.organizationUnitId && !by(units, q.organizationUnitId)) throw err(404, "ORG_UNIT_NOT_FOUND");
      const scope = q.organizationUnitId ? new Set([q.organizationUnitId, ...(q.includeDescendants === false ? [] : below(q.organizationUnitId))]) : null; const needle = fold(text ?? "");
      const inUnit = scope ? new Set(memberships.filter((m) => m.active && scope.has(m.organizationUnitId)).map((m) => m.userId)) : null;
      const withPos = q.positionId ? new Set(held.filter((h) => h.active && h.positionId === q.positionId).map((h) => h.userId)) : null; const withGrade = q.gradeId ? new Set(held.filter((h) => h.active && h.gradeId === q.gradeId).map((h) => h.userId)) : null;
      let hit = base.filter((b) => (!needle || fold(`${b.displayName} ${b.username} ${b.email}`).includes(needle)) && (!inUnit || inUnit.has(b.userId)) && (!withPos || withPos.has(b.userId)) && (!withGrade || withGrade.has(b.userId)) && (q.active === undefined || q.active === b.active));
      hit = [...hit].sort((a, b) => (q.sort === "username" ? a.username.localeCompare(b.username) : (a.displayName ?? a.username).localeCompare(b.displayName ?? b.username, "vi")) * (q.dir === "desc" ? -1 : 1) || a.userId.localeCompare(b.userId));
      return { items: hit.slice(page * size, page * size + size).map(employee), total: hit.length, page, size };
    },
    getEmployee: async (_t, userId) => { log("getEmployee", [userId]); gate(false); return employee(baseOf(userId)); },
    createEmployee: async (_t, b) => {
      log("createEmployee", [b]); gate(true); const username = (b.username ?? "").trim().toLowerCase(); if (!/^[a-z0-9._-]{3,40}$/.test(username)) throw err(400, "VALIDATION_FAILED"); if (base.some((x) => x.username === username)) throw err(409, "USERNAME_TAKEN");
      // validate EVERYTHING first: either the employee exists with its memberships and positions, or nothing was created (one transaction)
      const ms = b.organizationMemberships ?? []; for (const m of ms) { activeUnit(m.organizationUnitId); relation(m.relationType); for (const p of m.positions ?? []) { const pos = by(positions, p.positionId); if (!pos) throw err(404, "POSITION_NOT_FOUND"); if (!pos.active) throw err(409, "POSITION_DISABLED"); if (p.gradeId) { const g = by(grades, p.gradeId); if (!g) throw err(404, "GRADE_NOT_FOUND"); if (!g.active) throw err(409, "GRADE_DISABLED"); } } }
      const userId = nid("id-"); const nb: Base = { userId, username, displayName: b.displayName?.trim() || null, email: b.email ?? null, tenantRole: b.tenantRole ?? "MEMBER", active: true, accountActivated: false }; base = [...base, nb];
      const primaryIdx = Math.max(0, ms.findIndex((m) => m.primary)); ms.forEach((m, i) => { const mm = mkMember(userId, m.organizationUnitId, { relationType: relation(m.relationType), primary: i === primaryIdx }); memberships.push(mm); (m.positions ?? []).forEach((p, k) => held.push(mkHeld(mm, p.positionId, { gradeId: p.gradeId ?? null, primary: k === 0 && !!p.primary || (k === 0 && (m.positions ?? []).every((x) => !x.primary)) }))); });
      return { employee: employee(nb), activation: { userId, username, displayName: nb.displayName ?? username, purpose: "ACTIVATION", token: "x".repeat(43), expiresAt: "2026-10-17T00:00:00Z" } };
    },
    disableEmployee: async (_t, userId) => { log("disableEmployee", [userId]); gate(true); const b = baseOf(userId); base = base.map((x) => (x.userId === userId ? { ...x, active: false } : x)); return employee({ ...b, active: false }); },
    enableEmployee: async (_t, userId) => { log("enableEmployee", [userId]); gate(true); const b = baseOf(userId); base = base.map((x) => (x.userId === userId ? { ...x, active: true } : x)); return employee({ ...b, active: true }); },

    // ---------------------------------------------------------------------------------------------------------------------------- memberships
    listMemberships: async (_t, userId, inc = false) => { log("listMemberships", [userId, inc]); gate(false); baseOf(userId); return memberships.filter((m) => m.userId === userId && (inc || m.active)); },
    addMembership: async (_t, userId, b) => {
      log("addMembership", [userId, b]); gate(true); activeEmployee(userId); const u = activeUnit(b.organizationUnitId); const rel = relation(b.relationType);
      if (memberships.some((m) => m.active && m.userId === userId && m.organizationUnitId === u.id)) throw err(409, "ORG_MEMBERSHIP_EXISTS"); if (memberships.filter((m) => m.active && m.userId === userId).length >= 20) throw err(400, "VALIDATION_FAILED");
      const first = !memberships.some((m) => m.active && m.userId === userId); if (b.primary === true && !first) promote(userId); const m = mkMember(userId, u.id, { relationType: rel, primary: first || b.primary === true }); memberships = [...memberships, m]; return m;
    },
    updateMembership: async (_t, userId, id, b) => {
      log("updateMembership", [userId, id, b]); gate(true); const cur = stale(memberships.find((m) => m.id === id && m.userId === userId), b.expectedVersion, "ORG_MEMBERSHIP_NOT_FOUND"); if (!cur.active) throw err(409, "ORG_MEMBERSHIP_INACTIVE");
      if (b.primary === false) throw err(400, "VALIDATION_FAILED"); if (b.primary === true && !cur.primary) promote(userId, id);
      memberships = memberships.map((m) => (m.id === id ? { ...m, ...(b.relationType ? { relationType: relation(b.relationType) } : {}), ...(b.primary === true ? { primary: true } : {}), version: m.version + 1 } : m)); return by(memberships, id)!;
    },
    removeMembership: async (_t, userId, id, v) => {
      log("removeMembership", [userId, id, v]); gate(true); const cur = stale(memberships.find((m) => m.id === id && m.userId === userId), v, "ORG_MEMBERSHIP_NOT_FOUND");
      if (held.some((h) => h.active && h.membershipId === id)) throw err(409, "EMPLOYEE_ORG_HAS_POSITIONS");
      memberships = memberships.map((m) => (m.id === id ? { ...m, active: false, primary: false, version: m.version + 1 } : m)); const next = memberships.find((m) => m.userId === userId && m.active); if (cur.primary && next) memberships = memberships.map((m) => (m.id === next.id ? { ...m, primary: true, version: m.version + 1 } : m)); return by(memberships, id)!;
    },

    // ---------------------------------------------------------------------------------------------------------------------------- positions held within a membership
    listEmployeePositions: async (_t, userId, inc = false) => { log("listEmployeePositions", [userId, inc]); gate(false); baseOf(userId); return held.filter((h) => h.userId === userId && (inc || h.active)); },
    addEmployeePosition: async (_t, userId, b) => {
      log("addEmployeePosition", [userId, b]); gate(true); activeEmployee(userId); const m = memberships.find((x) => x.id === b.membershipId && x.userId === userId); if (!m) throw err(404, "ORG_MEMBERSHIP_NOT_FOUND"); if (!m.active) throw err(409, "ORG_MEMBERSHIP_INACTIVE");
      const pos = by(positions, b.positionId); if (!pos) throw err(404, "POSITION_NOT_FOUND"); if (!pos.active) throw err(409, "POSITION_DISABLED"); if (b.gradeId) { const g = by(grades, b.gradeId); if (!g) throw err(404, "GRADE_NOT_FOUND"); if (!g.active) throw err(409, "GRADE_DISABLED"); }
      if (held.some((h) => h.active && h.membershipId === m.id && h.positionId === b.positionId)) throw err(409, "POSITION_ASSIGNMENT_EXISTS");
      const mine = held.filter((h) => h.active && h.userId === userId); const first = mine.length === 0; if (b.primary === true && !first) held = held.map((h) => (h.userId === userId && h.active && h.primary ? { ...h, primary: false, version: h.version + 1 } : h));
      const n = mkHeld(m, b.positionId, { gradeId: b.gradeId ?? null, primary: first || b.primary === true }); held = [...held, n]; return n;
    },
    updateEmployeePosition: async (_t, userId, id, b) => {
      log("updateEmployeePosition", [userId, id, b]); gate(true); const cur = stale(held.find((h) => h.id === id && h.userId === userId), b.expectedVersion, "POSITION_ASSIGNMENT_NOT_FOUND");
      if (b.gradeId) { const g = by(grades, b.gradeId); if (!g) throw err(404, "GRADE_NOT_FOUND"); if (!g.active) throw err(409, "GRADE_DISABLED"); }
      if (b.primary === true && !cur.primary) held = held.map((h) => (h.userId === userId && h.active && h.primary ? { ...h, primary: false, version: h.version + 1 } : h));
      held = held.map((h) => (h.id === id ? { ...h, ...(b.clearGrade ? { gradeId: null } : b.gradeId ? { gradeId: b.gradeId } : {}), ...(b.primary === true ? { primary: true } : {}), version: h.version + 1 } : h)); return by(held, id)!;
    },
    removeEmployeePosition: async (_t, userId, id, v) => { log("removeEmployeePosition", [userId, id, v]); gate(true); stale(held.find((h) => h.id === id && h.userId === userId), v, "POSITION_ASSIGNMENT_NOT_FOUND"); held = held.map((h) => (h.id === id ? { ...h, active: false, primary: false, version: h.version + 1 } : h)); return by(held, id)!; },
  };

  return {
    api,
    state: () => ({ units, types, positions, grades, employees: base.slice(0, 200).map(employee), requests, refusedLimits }),
    touch: (kind, id) => { if (kind === "unit") units = set(units, id, {}); else if (kind === "type") types = set(types, id, {}); else memberships = memberships.map((m) => (m.id === id ? { ...m, version: m.version + 1 } : m)); },
    busy: (n) => { busyLeft = n; },
    off: (on) => { flags.off = on; },
    set: (k, on) => { flags[k] = on; },
  };
}
