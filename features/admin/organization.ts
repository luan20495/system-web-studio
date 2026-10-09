/**
 * Organization structure + employee directory — the frontend contract (2026-10-08).
 *
 * C1 has NOT published a contract for this yet (no route, no table, no permission code in integration/v2 or fix/c1-*). So nothing here names a URL:
 * `CAPABILITIES` marks every operation `NOT_READY` with the owner, and the adapter throws `OrganizationNotReady` and sends NOTHING. When C1 publishes its contract the only edits are
 *   1. CAPABILITIES.<op>: `status: "READY"`, `route`, `needs` (the canonical capability the SERVER must list);
 *   2. the transport (a slice of `api`) and `api-client` call for that op;
 *   3. `organizationPlan` in organizationModel.ts: the capability that gates the screens (today: the same tenant capability that gates tenant provisioning, flagged `assumed`).
 *
 * One exception that is NOT invented: the employee directory falls back to the tenant member list (`GET /admin/tenants/{t}/members`, C1 2356d64, TENANT_MEMBERS) while `listEmployees` is NOT_READY.
 * That list carries username / displayName / email / tenant role / active; it has no organization unit or position, and the UI says so.
 *
 * Organization metadata is NOT a permission: nothing in this file grants anything, and no role name is read.
 */
import type { TenantMemberView } from "@xweb/types";

export type Need = "TENANT_MEMBERS" | "TENANT_MANAGE" | "ORG_STRUCTURE_VIEW" | "ORG_STRUCTURE_MANAGE" | "EMPLOYEE_VIEW" | "EMPLOYEE_MANAGE" | "POSITION_GRADE_VIEW" | "POSITION_GRADE_MANAGE";
export type Owner = "C1" | "C0";

export type OrgCapabilityId =
  | "listOrganizationUnits" | "createOrganizationUnit" | "updateOrganizationUnit" | "moveOrganizationUnit" | "deleteOrganizationUnit"
  | "listOrganizationUnitTypes" | "createOrganizationUnitType" | "listPositions"
  | "listEmployees" | "createEmployee" | "updateEmployeeOrganization" | "updateEmployeePosition";

/** `needsAssumed`: the capability the SERVER will list is not decided by C1 yet; the screen gate uses the closest existing tenant capability until it is. */
export type OrgCapabilityState = { status: "READY"; needs: Need[]; route: string } | { status: "NOT_READY"; needs: Need[]; needsAssumed: true; reason: string; owner: Owner };

const NR = (reason: string, needs: Need[] = ["TENANT_MANAGE"]): OrgCapabilityState => ({ status: "NOT_READY", needs, needsAssumed: true, owner: "C1", reason });
/** user-facing wording (the owner stays in `owner` and in the handoff docs; end users do not need team names) */
const NO_CONTRACT = "Máy chủ chưa hỗ trợ cơ cấu tổ chức (đơn vị, loại đơn vị, vị trí/cấp bậc, nhân viên).";

export const CAPABILITIES: Readonly<Record<OrgCapabilityId, OrgCapabilityState>> = {
  listOrganizationUnits: NR(NO_CONTRACT, ["TENANT_MEMBERS"]),
  createOrganizationUnit: NR(NO_CONTRACT),
  updateOrganizationUnit: NR(NO_CONTRACT),
  moveOrganizationUnit: NR(NO_CONTRACT),
  deleteOrganizationUnit: NR(NO_CONTRACT),
  listOrganizationUnitTypes: NR(NO_CONTRACT, ["TENANT_MEMBERS"]),
  createOrganizationUnitType: NR(NO_CONTRACT),
  listPositions: NR(NO_CONTRACT, ["TENANT_MEMBERS"]),
  listEmployees: NR(NO_CONTRACT, ["TENANT_MEMBERS"]),
  createEmployee: NR(NO_CONTRACT, ["TENANT_MEMBERS"]),
  updateEmployeeOrganization: NR(NO_CONTRACT),
  updateEmployeePosition: NR(NO_CONTRACT),
};

/** thrown by the adapter for an operation the backend does not have; the UI shows `reason` and disables the action — it never shows success */
export class OrganizationNotReady extends Error {
  readonly code = "ORGANIZATION_NOT_READY";
  constructor(readonly capability: OrgCapabilityId, readonly reason: string, readonly owner: Owner) { super(reason); }
}

// ------------------------------------------------------------------------------------------------------------------------------- data
/** Icons are chosen from a closed allow-list (organizationModel.UNIT_ICONS): an icon id, never a URL. */
export type UnitIconId = string;
/** a company-defined kind of node (Khối, Chi nhánh, Phòng, Team, …): nothing about the hierarchy is hard-coded */
export type OrgUnitType = { id: string; code: string; name: string; icon: UnitIconId; /** empty / absent = may sit anywhere */ allowedParentTypeIds?: string[] };
export type NewOrgUnitType = Omit<OrgUnitType, "id">;
/** `version` is the optimistic-lock value the server returns; it is sent back on update / move / delete so a stale edit is refused (409) instead of overwriting */
export type OrgUnit = { id: string; parentId: string | null; typeId: string | null; name: string; code?: string | null; enabled: boolean; version: number; childCount?: number; employeeCount?: number };
export type NewOrgUnit = { parentId: string | null; typeId: string | null; name: string; code?: string };
export type OrgUnitPatch = { name?: string; code?: string | null; typeId?: string | null; enabled?: boolean };
export type Position = { id: string; name: string; level?: number | null };

export type EmployeeStatus = "ACTIVE" | "INACTIVE";
export type Employee = {
  userId: string; username: string; displayName: string | null; email: string | null; tenantRole: string; active: boolean;
  orgUnitId?: string | null; orgUnitName?: string | null; positionId?: string | null; positionName?: string | null;
  /** only when the directory carries it; otherwise the screen says it is unavailable */
  workspaces?: { id: string; name: string; role: string }[];
};
/** `fresh`: a cache-bust token (the screen bumps it after a write); the member-list fallback reuses one fetch per tenant for a few seconds otherwise */
export type EmployeeQuery = { q?: string; orgUnitId?: string | null; includeSubtree?: boolean; status?: EmployeeStatus | "ALL"; page: number; size: number; fresh?: number };
/** `source`: "directory" = the organization-aware list; "members" = the tenant member list (no unit / position), used while listEmployees is NOT_READY */
export type EmployeePage = { items: Employee[]; total: number; page: number; size: number; source: "directory" | "members" };

export interface OrganizationApi {
  state(id: OrgCapabilityId): OrgCapabilityState;
  listOrganizationUnits(tenantId: string): Promise<OrgUnit[]>;
  createOrganizationUnit(tenantId: string, u: NewOrgUnit): Promise<OrgUnit>;
  updateOrganizationUnit(tenantId: string, id: string, expectedVersion: number, patch: OrgUnitPatch): Promise<OrgUnit>;
  moveOrganizationUnit(tenantId: string, id: string, expectedVersion: number, newParentId: string | null): Promise<OrgUnit>;
  deleteOrganizationUnit(tenantId: string, id: string, expectedVersion: number): Promise<void>;
  listOrganizationUnitTypes(tenantId: string): Promise<OrgUnitType[]>;
  createOrganizationUnitType(tenantId: string, t: NewOrgUnitType): Promise<OrgUnitType>;
  listPositions(tenantId: string): Promise<Position[]>;
  listEmployees(tenantId: string, q: EmployeeQuery): Promise<EmployeePage>;
  createEmployee(tenantId: string, e: { userId: string; orgUnitId?: string | null; positionId?: string | null }): Promise<Employee>;
  updateEmployeeOrganization(tenantId: string, userId: string, orgUnitId: string | null): Promise<Employee>;
  updateEmployeePosition(tenantId: string, userId: string, positionId: string | null): Promise<Employee>;
}

/** the calls the production adapter needs; every method but `tenantMembers` is optional until C1's contract exists */
export type OrganizationTransport = Partial<Omit<OrganizationApi, "state">> & { tenantMembers?: (tenantId: string) => Promise<TenantMemberView[]> };

export type MembersToEmployees = (members: TenantMemberView[], q: EmployeeQuery) => EmployeePage;

/**
 * `fromMembers` turns the tenant member list into a directory page (client-side search / status / paging); it lives in organizationModel.ts and is injected so this file stays free of UI logic.
 */
export function createOrganizationApi(t: OrganizationTransport, fromMembers: MembersToEmployees, caps: Readonly<Record<OrgCapabilityId, OrgCapabilityState>> = CAPABILITIES): OrganizationApi {
  let cache: { tenantId: string; at: number; fresh: number | undefined; data: TenantMemberView[] } | null = null; const TTL_MS = 10_000;
  const need = (id: OrgCapabilityId): void => { const c = caps[id]; if (c.status === "NOT_READY") throw new OrganizationNotReady(id, c.reason, c.owner); };
  const via = <K extends keyof OrganizationTransport>(id: OrgCapabilityId, k: K): NonNullable<OrganizationTransport[K]> => {
    need(id); const f = t[k]; if (!f) throw new OrganizationNotReady(id, "Chưa nối máy chủ cho thao tác này.", "C1"); return f as NonNullable<OrganizationTransport[K]>;
  };
  return {
    state: (id) => caps[id],
    async listOrganizationUnits(tenantId) { return via("listOrganizationUnits", "listOrganizationUnits")(tenantId); },
    async createOrganizationUnit(tenantId, u) { return via("createOrganizationUnit", "createOrganizationUnit")(tenantId, { ...u, name: u.name.trim() }); },
    async updateOrganizationUnit(tenantId, id, v, p) { return via("updateOrganizationUnit", "updateOrganizationUnit")(tenantId, id, v, p.name === undefined ? p : { ...p, name: p.name.trim() }); },
    async moveOrganizationUnit(tenantId, id, v, parent) { return via("moveOrganizationUnit", "moveOrganizationUnit")(tenantId, id, v, parent); },
    async deleteOrganizationUnit(tenantId, id, v) { return via("deleteOrganizationUnit", "deleteOrganizationUnit")(tenantId, id, v); },
    async listOrganizationUnitTypes(tenantId) { return via("listOrganizationUnitTypes", "listOrganizationUnitTypes")(tenantId); },
    async createOrganizationUnitType(tenantId, ty) { return via("createOrganizationUnitType", "createOrganizationUnitType")(tenantId, { ...ty, name: ty.name.trim(), code: ty.code.trim().toUpperCase() }); },
    async listPositions(tenantId) { return via("listPositions", "listPositions")(tenantId); },
    async listEmployees(tenantId, q) {
      if (caps.listEmployees.status === "READY") return via("listEmployees", "listEmployees")(tenantId, q);
      // not an invented route: the tenant member list of C1's provisioning contract (TENANT_MEMBERS), filtered and paged here
      if (!t.tenantMembers) throw new OrganizationNotReady("listEmployees", "Chưa nối danh sách thành viên công ty.", "C1");
      // searching / paging the SAME list must not refetch every member each time: one fetch per tenant, reused for a few seconds unless the screen bumped `fresh`
      if (!cache || cache.tenantId !== tenantId || cache.fresh !== q.fresh || Date.now() - cache.at > TTL_MS) cache = { tenantId, at: Date.now(), fresh: q.fresh, data: await t.tenantMembers(tenantId) };
      return fromMembers(cache.data, q);
    },
    async createEmployee(tenantId, e) { return via("createEmployee", "createEmployee")(tenantId, e); },
    async updateEmployeeOrganization(tenantId, userId, unit) { return via("updateEmployeeOrganization", "updateEmployeeOrganization")(tenantId, userId, unit); },
    async updateEmployeePosition(tenantId, userId, pos) { return via("updateEmployeePosition", "updateEmployeePosition")(tenantId, userId, pos); },
  };
}
