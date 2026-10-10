/**
 * Organization structure + employee directory: the ONE typed service layer (`OrganizationApi`) every organization screen talks to, and its capability table.
 *
 * Contract: docs/parallel/c0/ORGANIZATION_API_CONTRACT_FOR_C5.md (D-C0-52) over C1's organization-employee-contract.md. Every capability below is wired to a route the backend serves (REAL_BACKEND: the
 * transport is `api.org` of @xweb/api-client, the only place that builds a URL). The server flag `ORGANIZATION_PERSISTENCE_ENABLED` is OFF by default: a server without the store answers
 * `501 ORG_PERSISTENCE_NOT_AVAILABLE` and the screens say so (organizationModel.orgProblem, kind `unavailable-feature`). There is NO fallback to a mock, a local copy or another list.
 *
 * What this layer does and does not do:
 *  - it shapes requests (trims names, never sends a tenant in a body, clamps the directory paging into the server's limits) and flattens the unit tree into rows that carry BOTH counts;
 *  - it never retries, caches, swallows or rewrites an error: the ApiError of the transport (status, code, details, retryAfterSeconds) reaches the screen untouched (409 stays a conflict, 503 stays a retry);
 *  - writes take the `expectedVersion` the person looked at; a stale write is refused by the server (409 VERSION_CONFLICT), never merged here;
 *  - it grants nothing. Permissions are the canonical codes in `needs` (ORG_STRUCTURE_*, EMPLOYEE_*, POSITION_GRADE_*) read from /auth/me by organizationModel.organizationPlan; an organization
 *    relation (MEMBER / MANAGER / HEAD), a position or a grade is business data and authorizes nothing. No role name is read.
 */
import type { OrgEmployeeDto, OrgEmployeePositionDto, OrgGradeDto, OrgMembershipDto, OrgPositionDto, OrgUnitDetailDto, OrgUnitDto, OrgUnitNodeDto, OrgUnitTypeDto, OrgUnitTypeRules } from "@xweb/types";
import type { OrgPermissionCode } from "../../packages/types/src/contract/v2/permissions";
import { clampOrgPaging, ORG_SEARCH_MIN, type OrgApi } from "../../packages/api-client/src/org";

/** the canonical codes (final contract §9, D-C0-51) the SERVER must list in `/auth/me.permissions` for the caller; the screens read these codes and nothing else (no platformScope / role as a stand-in) */
export type Need = "TENANT_MEMBERS" | OrgPermissionCode;
export type Owner = "C1" | "C3";

export type OrgCapabilityId =
  | "listOrganizationUnits" | "getOrganizationUnit" | "createOrganizationUnit" | "updateOrganizationUnit" | "moveOrganizationUnit" | "archiveOrganizationUnit" | "restoreOrganizationUnit"
  | "listOrganizationUnitTypes" | "createOrganizationUnitType" | "updateOrganizationUnitType" | "setOrganizationUnitTypeActive"
  | "listPositions" | "createPosition" | "updatePosition" | "setPositionActive" | "listGrades" | "createGrade" | "updateGrade" | "setGradeActive"
  | "listEmployees" | "getEmployee" | "createEmployee" | "setEmployeeActive"
  | "listMemberships" | "addMembership" | "updateMembership" | "removeMembership"
  | "listEmployeePositions" | "addEmployeePosition" | "updateEmployeePosition" | "removeEmployeePosition";

/**
 * READY = wired to a route the backend serves (`client` names the typed transport method; the URL is built there and nowhere else). NOT_READY is kept for an operation the backend does not have; none is
 * NOT_READY today. Not offered at all (NOT_SUPPORTED, so no capability): a hard delete of a unit (archive / restore only), employee profile fields (employee code, phone, joined date), a tenant-global
 * position (a position is held within a membership), and editing an employee's identity (username / e-mail).
 */
export type OrgCapabilityState = { status: "READY"; needs: Need[]; client: keyof OrgApi } | { status: "NOT_READY"; needs: Need[]; reason: string; owner: Owner };

const R = (client: keyof OrgApi, ...needs: Need[]): OrgCapabilityState => ({ status: "READY", needs, client });

export const CAPABILITIES: Readonly<Record<OrgCapabilityId, OrgCapabilityState>> = {
  // permission per operation = the route table of the contract; `*_MANAGE` does not imply `*_VIEW`
  listOrganizationUnits: R("unitTree", "ORG_STRUCTURE_VIEW"),
  getOrganizationUnit: R("getUnit", "ORG_STRUCTURE_VIEW"),
  createOrganizationUnit: R("createUnit", "ORG_STRUCTURE_MANAGE"),
  updateOrganizationUnit: R("updateUnit", "ORG_STRUCTURE_MANAGE"),
  moveOrganizationUnit: R("moveUnit", "ORG_STRUCTURE_MANAGE"),
  archiveOrganizationUnit: R("archiveUnit", "ORG_STRUCTURE_MANAGE"),
  restoreOrganizationUnit: R("restoreUnit", "ORG_STRUCTURE_MANAGE"),
  listOrganizationUnitTypes: R("listUnitTypes", "ORG_STRUCTURE_VIEW"),
  createOrganizationUnitType: R("createUnitType", "ORG_STRUCTURE_MANAGE"),
  updateOrganizationUnitType: R("updateUnitType", "ORG_STRUCTURE_MANAGE"),
  setOrganizationUnitTypeActive: R("disableUnitType", "ORG_STRUCTURE_MANAGE"),
  listPositions: R("listPositions", "POSITION_GRADE_VIEW"),
  createPosition: R("createPosition", "POSITION_GRADE_MANAGE"),
  updatePosition: R("updatePosition", "POSITION_GRADE_MANAGE"),
  setPositionActive: R("disablePosition", "POSITION_GRADE_MANAGE"),
  listGrades: R("listGrades", "POSITION_GRADE_VIEW"),
  createGrade: R("createGrade", "POSITION_GRADE_MANAGE"),
  updateGrade: R("updateGrade", "POSITION_GRADE_MANAGE"),
  setGradeActive: R("disableGrade", "POSITION_GRADE_MANAGE"),
  listEmployees: R("listEmployees", "EMPLOYEE_VIEW"),
  getEmployee: R("getEmployee", "EMPLOYEE_VIEW"),
  createEmployee: R("createEmployee", "EMPLOYEE_MANAGE", "TENANT_MEMBERS"),             // POST /employees also provisions the account
  setEmployeeActive: R("disableEmployee", "EMPLOYEE_MANAGE", "TENANT_MEMBERS"),         // enable / disable change tenant_members
  listMemberships: R("listMemberships", "EMPLOYEE_VIEW"),
  addMembership: R("addMembership", "EMPLOYEE_MANAGE"),
  updateMembership: R("updateMembership", "EMPLOYEE_MANAGE"),
  removeMembership: R("removeMembership", "EMPLOYEE_MANAGE"),
  listEmployeePositions: R("listEmployeePositions", "EMPLOYEE_VIEW"),
  addEmployeePosition: R("addEmployeePosition", "EMPLOYEE_MANAGE"),
  updateEmployeePosition: R("updateEmployeePosition", "EMPLOYEE_MANAGE"),
  removeEmployeePosition: R("removeEmployeePosition", "EMPLOYEE_MANAGE"),
};

/** thrown for an operation that is not wired (a NOT_READY capability, or a transport that lacks the method); the UI shows `reason` and disables the action: it never shows success */
export class OrganizationNotReady extends Error {
  readonly code = "ORGANIZATION_NOT_READY";
  constructor(readonly capability: OrgCapabilityId, readonly reason: string, readonly owner: Owner) { super(reason); }
}

// ------------------------------------------------------------------------------------------------------------------------------- data
/** Icons are chosen from a closed allow-list (organizationModel.UNIT_ICONS): an icon id, never a URL. */
export type UnitIconId = string;
export type OrgRules = OrgUnitTypeRules;
/** a company-defined kind of node (Khối, Chi nhánh, Phòng, Team, …): nothing about the hierarchy is hard-coded. `rules` are the tenant's placement rules (every field null = no constraint). */
export type OrgUnitType = { id: string; code: string; name: string; icon: UnitIconId; active: boolean; rules: OrgRules; version: number };
export type NewOrgUnitType = { name: string; code: string; icon: UnitIconId; rules?: Partial<OrgRules> };
export type OrgUnitTypePatch = { name?: string; icon?: UnitIconId; rules?: Partial<OrgRules> };
/**
 * One row of the tree. `active=false` = ARCHIVED (soft; `archivedAt` set). `version` is the optimistic-lock value the server returned; it is sent back on update / move / archive / restore so a stale edit is
 * refused (409) instead of overwriting.
 * Two DIFFERENT counts, never merged and never both called "employees":
 *  - `directMemberCount`: ACTIVE memberships of ACTIVE employees on exactly this unit;
 *  - `subtreeEmployeeCount`: DISTINCT active employees over this unit and its non-archived descendants (an employee in two units of the subtree counts once, so it is not the sum of the children).
 * null = the server gave no count (shown as unknown, never as 0). `childCount` = the non-archived children in the response.
 */
export type OrgUnit = {
  id: string; parentId: string | null; typeId: string; name: string; code: string; sortOrder: number; active: boolean; archivedAt: string | null; version: number;
  directMemberCount: number | null; subtreeEmployeeCount: number | null; childCount: number;
};
export type NewOrgUnit = { parentId: string | null; typeId: string; name: string; code: string; sortOrder?: number };
export type OrgUnitPatch = { name?: string; code?: string; sortOrder?: number };
/** the unit detail: the breadcrumb is the SERVER's `path`; `activeChildCount` / `activeMemberCount` are C1's counts (NOT the two counts above) */
export type OrgUnitDetail = { unit: OrgUnit; path: { id: string; name: string }[]; activeChildCount: number; activeMemberCount: number };

export type Position = { id: string; code: string; name: string; description: string | null; active: boolean; version: number };
export type NewPosition = { name: string; code: string; description?: string | null };
export type PositionPatch = { name?: string; description?: string | null };
export type Grade = { id: string; code: string; name: string; rank: number | null; description: string | null; active: boolean; version: number };
export type NewGrade = { name: string; code: string; rank?: number | null; description?: string | null };
export type GradePatch = { name?: string; rank?: number | null; clearRank?: boolean; description?: string | null };

/** a relation (MEMBER / MANAGER / HEAD …) is a label of the membership; it grants nothing */
export type Membership = OrgMembershipDto;
export type EmployeePosition = OrgEmployeePositionDto;
export type Employee = OrgEmployeeDto;
export type EmployeeStatus = "ACTIVE" | "INACTIVE";
export type EmployeeQuery = { q?: string; orgUnitId?: string | null; includeSubtree?: boolean; status?: EmployeeStatus | "ALL"; positionId?: string | null; gradeId?: string | null; page: number; size: number; sort?: "name" | "username"; dir?: "asc" | "desc" };
/** `page` / `size` are the ones the server answered with (the request was clamped into size 1..100 and page * size <= 10000 before it was sent) */
export type EmployeePage = { items: Employee[]; total: number; page: number; size: number };
export type NewEmployee = {
  username: string; displayName: string; email?: string; tenantRole: "MEMBER" | "TENANT_ADMIN"; workspace?: { id: string; role: string };
  /** each membership may carry the positions held within it; at most one primary */
  memberships?: { unitId: string; relationType?: string; primary?: boolean; positions?: { positionId: string; gradeId?: string | null; primary?: boolean }[] }[];
};
/** the activation link is returned ONCE (never stored by this layer) */
export type CreatedEmployee = { employee: Employee; activation: { userId: string; username: string; displayName: string; purpose: "ACTIVATION" | "RESET"; token: string; expiresAt: string } | null };
export type NewMembership = { unitId: string; relationType?: string; primary?: boolean };
export type MembershipPatch = { relationType?: string; primary?: boolean };
export type NewEmployeePosition = { membershipId: string; positionId: string; gradeId?: string | null; primary?: boolean };
export type EmployeePositionPatch = { gradeId?: string | null; clearGrade?: boolean; primary?: boolean };

export interface OrganizationApi {
  state(id: OrgCapabilityId): OrgCapabilityState;
  // units
  listOrganizationUnits(tenantId: string, opts?: { includeArchived?: boolean }): Promise<OrgUnit[]>;
  getOrganizationUnit(tenantId: string, id: string): Promise<OrgUnitDetail>;
  createOrganizationUnit(tenantId: string, u: NewOrgUnit): Promise<OrgUnit>;
  updateOrganizationUnit(tenantId: string, id: string, expectedVersion: number, patch: OrgUnitPatch): Promise<OrgUnit>;
  moveOrganizationUnit(tenantId: string, id: string, expectedVersion: number, newParentId: string | null): Promise<OrgUnit>;
  archiveOrganizationUnit(tenantId: string, id: string, expectedVersion: number): Promise<OrgUnit>;
  restoreOrganizationUnit(tenantId: string, id: string, expectedVersion: number): Promise<OrgUnit>;
  // unit types
  listOrganizationUnitTypes(tenantId: string): Promise<OrgUnitType[]>;
  createOrganizationUnitType(tenantId: string, t: NewOrgUnitType): Promise<OrgUnitType>;
  updateOrganizationUnitType(tenantId: string, id: string, expectedVersion: number, patch: OrgUnitTypePatch): Promise<OrgUnitType>;
  setOrganizationUnitTypeActive(tenantId: string, id: string, expectedVersion: number, active: boolean): Promise<OrgUnitType>;
  // positions / grades
  listPositions(tenantId: string): Promise<Position[]>;
  createPosition(tenantId: string, p: NewPosition): Promise<Position>;
  updatePosition(tenantId: string, id: string, expectedVersion: number, patch: PositionPatch): Promise<Position>;
  setPositionActive(tenantId: string, id: string, expectedVersion: number, active: boolean): Promise<Position>;
  listGrades(tenantId: string): Promise<Grade[]>;
  createGrade(tenantId: string, g: NewGrade): Promise<Grade>;
  updateGrade(tenantId: string, id: string, expectedVersion: number, patch: GradePatch): Promise<Grade>;
  setGradeActive(tenantId: string, id: string, expectedVersion: number, active: boolean): Promise<Grade>;
  // employees
  listEmployees(tenantId: string, q: EmployeeQuery): Promise<EmployeePage>;
  getEmployee(tenantId: string, userId: string): Promise<Employee>;
  createEmployee(tenantId: string, e: NewEmployee): Promise<CreatedEmployee>;
  setEmployeeActive(tenantId: string, userId: string, active: boolean): Promise<Employee>;
  // memberships and the positions held within them
  listMemberships(tenantId: string, userId: string, includeInactive?: boolean): Promise<Membership[]>;
  addMembership(tenantId: string, userId: string, m: NewMembership): Promise<Membership>;
  updateMembership(tenantId: string, userId: string, id: string, expectedVersion: number, patch: MembershipPatch): Promise<Membership>;
  removeMembership(tenantId: string, userId: string, id: string, expectedVersion: number): Promise<Membership>;
  listEmployeePositions(tenantId: string, userId: string, includeInactive?: boolean): Promise<EmployeePosition[]>;
  addEmployeePosition(tenantId: string, userId: string, p: NewEmployeePosition): Promise<EmployeePosition>;
  updateEmployeePosition(tenantId: string, userId: string, id: string, expectedVersion: number, patch: EmployeePositionPatch): Promise<EmployeePosition>;
  removeEmployeePosition(tenantId: string, userId: string, id: string, expectedVersion: number): Promise<EmployeePosition>;
}

/** the typed transport (a slice of `api.org`); a unit test injects a recording fake, the browser harness an in-memory server behind the same shape. A method may be missing only in a fake. */
export type OrganizationTransport = Partial<OrgApi>;

// ------------------------------------------------------------------------------------------------------------------------------- mapping (DTO -> rows)
const noRules: OrgRules = { allowedParentTypeIds: null, allowedChildTypeIds: null, allowRoot: null, maxDepth: null };
export const typeFromDto = (d: OrgUnitTypeDto): OrgUnitType => ({ id: d.id, code: d.code, name: d.name, icon: d.icon ?? "", active: d.active, version: d.version, rules: { ...noRules, ...(d.rules ?? {}) } });
const countOf = (n: number | null | undefined): number | null => (typeof n === "number" && Number.isFinite(n) && n >= 0 ? n : null);
export const unitFromDto = (d: OrgUnitDto, counts?: { direct?: number | null; subtree?: number | null }, childCount = 0): OrgUnit => ({
  id: d.id, parentId: d.parentId ?? null, typeId: d.typeId, name: d.name, code: d.code, sortOrder: d.sortOrder ?? 0, active: d.active, archivedAt: d.archivedAt ?? null, version: d.version,
  directMemberCount: countOf(counts?.direct), subtreeEmployeeCount: countOf(counts?.subtree), childCount,
});
/** nested tree nodes -> one flat list (parents first). Iterative: a tree of any depth cannot overflow the stack. Counts are copied exactly as the server sent them. */
export function flattenUnitNodes(nodes: readonly OrgUnitNodeDto[]): OrgUnit[] {
  const out: OrgUnit[] = []; const stack: OrgUnitNodeDto[] = [];
  for (let i = nodes.length - 1; i >= 0; i--) stack.push(nodes[i]);
  while (stack.length) {
    const n = stack.pop()!; const kids = n.children ?? [];
    out.push(unitFromDto(n.unit, { direct: n.directMemberCount, subtree: n.subtreeEmployeeCount }, kids.filter((c) => c.unit.active).length));
    for (let i = kids.length - 1; i >= 0; i--) stack.push(kids[i]);
  }
  return out;
}
const detailFromDto = (d: OrgUnitDetailDto): OrgUnitDetail => ({
  unit: unitFromDto(d.unit, { direct: d.directMemberCount, subtree: d.subtreeEmployeeCount }, d.activeChildCount), path: (d.path ?? []).map((p) => ({ id: p.id, name: p.name })),
  activeChildCount: d.activeChildCount, activeMemberCount: d.activeMemberCount,
});
const positionFromDto = (d: OrgPositionDto): Position => ({ id: d.id, code: d.code, name: d.name, description: d.description ?? null, active: d.active, version: d.version });
const gradeFromDto = (d: OrgGradeDto): Grade => ({ id: d.id, code: d.code, name: d.name, rank: d.rank ?? null, description: d.description ?? null, active: d.active, version: d.version });
const rulesBody = (r: Partial<OrgRules>): OrgRules => ({ ...noRules, ...r });

/** The production service: shapes the requests and maps the answers. `caps` is injectable so a test can prove that a NOT_READY operation sends nothing. */
export function createOrganizationApi(t: OrganizationTransport, caps: Readonly<Record<OrgCapabilityId, OrgCapabilityState>> = CAPABILITIES): OrganizationApi {
  const via = <K extends keyof OrgApi>(id: OrgCapabilityId, k: K): OrgApi[K] => {
    const c = caps[id]; if (c.status === "NOT_READY") throw new OrganizationNotReady(id, c.reason, c.owner);
    const f = t[k]; if (!f) throw new OrganizationNotReady(id, "Chưa nối máy chủ cho thao tác này.", "C1");
    return f as OrgApi[K];
  };
  return {
    state: (id) => caps[id],
    async listOrganizationUnits(tenantId, opts) { return flattenUnitNodes(await via("listOrganizationUnits", "unitTree")(tenantId, opts?.includeArchived ?? false)); },
    async getOrganizationUnit(tenantId, id) { return detailFromDto(await via("getOrganizationUnit", "getUnit")(tenantId, id)); },
    async createOrganizationUnit(tenantId, u) {
      return unitFromDto(await via("createOrganizationUnit", "createUnit")(tenantId, { typeId: u.typeId, parentId: u.parentId, name: u.name.trim(), code: u.code.trim(), ...(u.sortOrder === undefined ? {} : { sortOrder: u.sortOrder }) }));
    },
    async updateOrganizationUnit(tenantId, id, v, p) {
      return unitFromDto(await via("updateOrganizationUnit", "updateUnit")(tenantId, id, { ...(p.name === undefined ? {} : { name: p.name.trim() }), ...(p.code === undefined ? {} : { code: p.code.trim() }), ...(p.sortOrder === undefined ? {} : { sortOrder: p.sortOrder }), expectedVersion: v }));
    },
    async moveOrganizationUnit(tenantId, id, v, parent) { return unitFromDto(await via("moveOrganizationUnit", "moveUnit")(tenantId, id, { newParentId: parent, expectedVersion: v })); },
    async archiveOrganizationUnit(tenantId, id, v) { return unitFromDto(await via("archiveOrganizationUnit", "archiveUnit")(tenantId, id, v)); },
    async restoreOrganizationUnit(tenantId, id, v) { return unitFromDto(await via("restoreOrganizationUnit", "restoreUnit")(tenantId, id, v)); },

    async listOrganizationUnitTypes(tenantId) { return (await via("listOrganizationUnitTypes", "listUnitTypes")(tenantId, true)).map(typeFromDto); },
    async createOrganizationUnitType(tenantId, ty) {
      // the server stores the type code lower-case; the name is trimmed
      return typeFromDto(await via("createOrganizationUnitType", "createUnitType")(tenantId, { name: ty.name.trim(), code: ty.code.trim().toLowerCase(), icon: ty.icon, ...(ty.rules ? { rules: rulesBody(ty.rules) } : {}) }));
    },
    async updateOrganizationUnitType(tenantId, id, v, p) {
      // `rules` present REPLACES the whole object on the server, so a rules patch always carries all four fields
      return typeFromDto(await via("updateOrganizationUnitType", "updateUnitType")(tenantId, id, { ...(p.name === undefined ? {} : { name: p.name.trim() }), ...(p.icon === undefined ? {} : { icon: p.icon }), ...(p.rules ? { rules: rulesBody(p.rules) } : {}), expectedVersion: v }));
    },
    async setOrganizationUnitTypeActive(tenantId, id, v, active) {
      return typeFromDto(await (active ? via("setOrganizationUnitTypeActive", "enableUnitType") : via("setOrganizationUnitTypeActive", "disableUnitType"))(tenantId, id, v));
    },

    async listPositions(tenantId) { return (await via("listPositions", "listPositions")(tenantId, true)).map(positionFromDto); },
    async createPosition(tenantId, p) { return positionFromDto(await via("createPosition", "createPosition")(tenantId, { name: p.name.trim(), code: p.code.trim(), ...(p.description === undefined ? {} : { description: p.description }) })); },
    async updatePosition(tenantId, id, v, p) { return positionFromDto(await via("updatePosition", "updatePosition")(tenantId, id, { ...(p.name === undefined ? {} : { name: p.name.trim() }), ...(p.description === undefined ? {} : { description: p.description }), expectedVersion: v })); },
    async setPositionActive(tenantId, id, v, active) { return positionFromDto(await (active ? via("setPositionActive", "enablePosition") : via("setPositionActive", "disablePosition"))(tenantId, id, v)); },
    async listGrades(tenantId) { return (await via("listGrades", "listGrades")(tenantId, true)).map(gradeFromDto); },
    async createGrade(tenantId, g) { return gradeFromDto(await via("createGrade", "createGrade")(tenantId, { name: g.name.trim(), code: g.code.trim(), ...(g.rank === undefined ? {} : { rank: g.rank }), ...(g.description === undefined ? {} : { description: g.description }) })); },
    async updateGrade(tenantId, id, v, p) {
      return gradeFromDto(await via("updateGrade", "updateGrade")(tenantId, id, { ...(p.name === undefined ? {} : { name: p.name.trim() }), ...(p.rank === undefined ? {} : { rank: p.rank }), ...(p.clearRank ? { clearRank: true } : {}), ...(p.description === undefined ? {} : { description: p.description }), expectedVersion: v }));
    },
    async setGradeActive(tenantId, id, v, active) { return gradeFromDto(await (active ? via("setGradeActive", "enableGrade") : via("setGradeActive", "disableGrade"))(tenantId, id, v)); },

    async listEmployees(tenantId, q) {
      // pulled back inside the server's limits BEFORE it is sent (size 1..100, page * size <= 10000, a search of at least 2 characters); the server stays the authority and may still refuse
      const { page, size } = clampOrgPaging(q.page, q.size);
      const text = q.q?.trim();
      const r = await via("listEmployees", "listEmployees")(tenantId, {
        ...(text && text.length >= ORG_SEARCH_MIN ? { q: text } : {}), ...(q.orgUnitId ? { organizationUnitId: q.orgUnitId, includeDescendants: q.includeSubtree ?? true } : {}),
        ...(q.positionId ? { positionId: q.positionId } : {}), ...(q.gradeId ? { gradeId: q.gradeId } : {}), ...(q.status && q.status !== "ALL" ? { active: q.status === "ACTIVE" } : {}),
        page, size, ...(q.sort ? { sort: q.sort } : {}), ...(q.dir ? { dir: q.dir } : {}),
      });
      return { items: r.items ?? [], total: r.total ?? 0, page: r.page ?? page, size: r.size ?? size };
    },
    async getEmployee(tenantId, userId) { return via("getEmployee", "getEmployee")(tenantId, userId); },
    async createEmployee(tenantId, e) {
      // the tenant is the PATH; `workspaceId` / `workspaceRole` go together or not at all; the memberships (and the positions held within each) are created in the SAME transaction by the server
      const r = await via("createEmployee", "createEmployee")(tenantId, {
        username: e.username.trim().toLowerCase(), displayName: e.displayName.trim(), ...(e.email?.trim() ? { email: e.email.trim() } : {}), tenantRole: e.tenantRole,
        ...(e.workspace ? { workspaceId: e.workspace.id, workspaceRole: e.workspace.role } : {}),
        ...(e.memberships?.length ? { organizationMemberships: e.memberships.map((m) => ({
          organizationUnitId: m.unitId, ...(m.relationType ? { relationType: m.relationType } : {}), ...(m.primary === undefined ? {} : { primary: m.primary }),
          ...(m.positions?.length ? { positions: m.positions.map((p) => ({ positionId: p.positionId, ...(p.gradeId ? { gradeId: p.gradeId } : {}), ...(p.primary === undefined ? {} : { primary: p.primary }) })) } : {}),
        })) } : {}),
      });
      return { employee: r.employee, activation: r.activation ?? null };
    },
    async setEmployeeActive(tenantId, userId, active) { return (active ? via("setEmployeeActive", "enableEmployee") : via("setEmployeeActive", "disableEmployee"))(tenantId, userId); },

    async listMemberships(tenantId, userId, includeInactive = false) { return via("listMemberships", "listMemberships")(tenantId, userId, includeInactive); },
    async addMembership(tenantId, userId, m) { return via("addMembership", "addMembership")(tenantId, userId, { organizationUnitId: m.unitId, ...(m.relationType ? { relationType: m.relationType } : {}), ...(m.primary === undefined ? {} : { primary: m.primary }) }); },
    async updateMembership(tenantId, userId, id, v, p) { return via("updateMembership", "updateMembership")(tenantId, userId, id, { ...(p.relationType ? { relationType: p.relationType } : {}), ...(p.primary === undefined ? {} : { primary: p.primary }), expectedVersion: v }); },
    async removeMembership(tenantId, userId, id, v) { return via("removeMembership", "removeMembership")(tenantId, userId, id, v); },
    async listEmployeePositions(tenantId, userId, includeInactive = false) { return via("listEmployeePositions", "listEmployeePositions")(tenantId, userId, includeInactive); },
    async addEmployeePosition(tenantId, userId, p) { return via("addEmployeePosition", "addEmployeePosition")(tenantId, userId, { membershipId: p.membershipId, positionId: p.positionId, ...(p.gradeId ? { gradeId: p.gradeId } : {}), ...(p.primary === undefined ? {} : { primary: p.primary }) }); },
    async updateEmployeePosition(tenantId, userId, id, v, p) { return via("updateEmployeePosition", "updateEmployeePosition")(tenantId, userId, id, { ...(p.gradeId ? { gradeId: p.gradeId } : {}), ...(p.clearGrade ? { clearGrade: true } : {}), ...(p.primary === undefined ? {} : { primary: p.primary }), expectedVersion: v }); },
    async removeEmployeePosition(tenantId, userId, id, v) { return via("removeEmployeePosition", "removeEmployeePosition")(tenantId, userId, id, v); },
  };
}
