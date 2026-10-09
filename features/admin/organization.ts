/**
 * Organization structure + employee directory: the frontend port (`OrganizationApi`) and its capability table.
 *
 * The contract is FROZEN (C1 docs/parallel/c1/organization-employee-contract.md, final-iam-tenant-org-permission-contract.md §9; permissions D-C0-51) but this frontend does NOT call it yet:
 * ORG_BACKEND_WIRING = WAITING_FOR_C1_C3 (the routes answer 501 ORG_PERSISTENCE_NOT_AVAILABLE until C3 registers its repositories). So every operation stays `NOT_READY`, the adapter throws
 * `OrganizationNotReady` and sends NOTHING, and this file names no URL. `needs` is already the contract's code(s) per operation. When the backend is wired the edits are
 *   1. CAPABILITIES.<op>: `status: "READY"`, `route`;
 *   2. the transport (a slice of `api`) and the `api-client` call for that op, with the renamed DTO fields / error codes of contract §9 (see organizationModel.ts `BY_CODE`, the unit `enabled` -> `active` + `archivedAt`, etc.).
 *
 * One exception that is NOT invented: the employee directory falls back to the tenant member list (`GET /admin/tenants/{t}/members`, TENANT_MEMBERS) while `listEmployees` is NOT_READY.
 * That list carries username / displayName / email / tenant role / active; it has no organization unit or position, and the UI says so.
 *
 * Organization metadata is NOT a permission: nothing in this file grants anything, and no role name is read. The screens are gated on ORG_STRUCTURE_* / EMPLOYEE_* / POSITION_GRADE_* ONLY.
 */
import type { TenantMemberView } from "@xweb/types";
import type { OrgPermissionCode } from "../../packages/types/src/contract/v2/permissions";

/** user-facing wording (the owner stays in `owner` and in the handoff docs; end users do not need team names) */
const NO_CONTRACT = "Máy chủ chưa hỗ trợ cơ cấu tổ chức (đơn vị, loại đơn vị, vị trí/cấp bậc, nhân viên).";

/** the canonical codes (C1 final contract §1 / §9, D-C0-51) the SERVER must list in `/auth/me.permissions` for the caller; the screens read these codes and nothing else (no TENANT_MEMBERS / platformScope / role as a stand-in) */
export type Need = "TENANT_MEMBERS" | OrgPermissionCode;
export type Owner = "C1" | "C3";

export type OrgCapabilityId =
  | "listOrganizationUnits" | "createOrganizationUnit" | "updateOrganizationUnit" | "moveOrganizationUnit" | "deleteOrganizationUnit"
  | "listOrganizationUnitTypes" | "createOrganizationUnitType" | "listPositions"
  | "listEmployees" | "createEmployee" | "updateEmployeeOrganization" | "updateEmployeePosition";

/** NOT_READY = the frontend adapter does not call the backend yet (WAITING_FOR_C1_C3: the routes are frozen but persistence answers 501 ORG_PERSISTENCE_NOT_AVAILABLE until C3 registers it). `needs` is the contract's code(s) for the operation. */
export type OrgCapabilityState = { status: "READY"; needs: Need[]; route: string } | { status: "NOT_READY"; needs: Need[]; reason: string; owner: Owner };

const NR = (needs: Need[], reason: string = NO_CONTRACT): OrgCapabilityState => ({ status: "NOT_READY", needs, owner: "C3", reason });

export const CAPABILITIES: Readonly<Record<OrgCapabilityId, OrgCapabilityState>> = {
  // permission per operation = C1 docs/parallel/c1/organization-employee-contract.md §1 / §9 (route table); `*_MANAGE` does not imply `*_VIEW`
  listOrganizationUnits: NR(["ORG_STRUCTURE_VIEW"]),
  createOrganizationUnit: NR(["ORG_STRUCTURE_MANAGE"]),
  updateOrganizationUnit: NR(["ORG_STRUCTURE_MANAGE"]),
  moveOrganizationUnit: NR(["ORG_STRUCTURE_MANAGE"]),
  deleteOrganizationUnit: NR(["ORG_STRUCTURE_MANAGE"]),                       // the contract has no delete: archive / restore (same code)
  listOrganizationUnitTypes: NR(["ORG_STRUCTURE_VIEW"]),
  createOrganizationUnitType: NR(["ORG_STRUCTURE_MANAGE"]),
  listPositions: NR(["POSITION_GRADE_VIEW"]),
  listEmployees: NR(["EMPLOYEE_VIEW"]),
  createEmployee: NR(["EMPLOYEE_MANAGE", "TENANT_MEMBERS"]),                  // POST /employees also provisions the account
  updateEmployeeOrganization: NR(["EMPLOYEE_MANAGE"]),                        // memberships sub-resource
  updateEmployeePosition: NR(["EMPLOYEE_MANAGE"]),                            // held positions sub-resource
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
