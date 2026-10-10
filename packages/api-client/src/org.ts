/**
 * Dynamic Organization client: the ONLY place that builds an organization / employee / position URL (docs/parallel/c0/ORGANIZATION_API_CONTRACT_FOR_C5.md, D-C0-52; DTOs in @xweb/types `Org*`).
 * Every route is under `/admin/tenants/{tenantId}`; the tenant is the path, never a body field. Writes carry `expectedVersion` (the `version` of the record the person looked at): a stale write is
 * 409 VERSION_CONFLICT, a structural lock timeout is 503 ORG_STRUCTURE_BUSY (nothing executed, retry the same request), a server without the store answers 501 ORG_PERSISTENCE_NOT_AVAILABLE.
 * Nothing here retries, caches, falls back or invents a value: an error is the ApiError of `call` (status, code, details, retryAfterSeconds) untouched.
 * Plain relative imports only: this module is loaded by the unit tests under node.
 */
import type {
  OrgEmployeeCreateBody, OrgEmployeeCreatedDto, OrgEmployeeDto, OrgEmployeePageDto, OrgEmployeePositionCreateBody, OrgEmployeePositionDto, OrgEmployeePositionUpdateBody, OrgEmployeeQuery,
  OrgGradeCreateBody, OrgGradeDto, OrgGradeUpdateBody, OrgMembershipCreateBody, OrgMembershipDto, OrgMembershipUpdateBody, OrgPositionCreateBody, OrgPositionDto, OrgPositionUpdateBody,
  OrgUnitCreateBody, OrgUnitDetailDto, OrgUnitDto, OrgUnitMoveBody, OrgUnitNodeDto, OrgUnitTypeCreateBody, OrgUnitTypeDto, OrgUnitTypeUpdateBody, OrgUnitUpdateBody,
} from "@xweb/types";
import { call, json, qs } from "./core";

const seg = encodeURIComponent;
const T = (tenantId: string) => `/admin/tenants/${seg(tenantId)}`;
const OWN = (v: number) => json({ expectedVersion: v });

/**
 * The server's own limits of the employee directory (400 VALIDATION_FAILED / OFFSET_TOO_LARGE otherwise): size 1..100, page >= 0, page * size <= 10000. The server stays the authority; the client
 * never SENDS a request outside them (a UI that pages deeper is told to narrow the search instead).
 */
export const ORG_PAGE_SIZE_MAX = 100;
export const ORG_OFFSET_MAX = 10_000;
export const ORG_SEARCH_MIN = 2;
export type OrgPaging = { page: number; size: number; /** true when the request had to be pulled back inside the limits */ clamped: boolean };
export function clampOrgPaging(page: number | undefined, size: number | undefined, defaultSize = 25): OrgPaging {
  const asInt = (v: number | undefined, d: number) => (typeof v === "number" && Number.isFinite(v) ? Math.trunc(v) : d);
  const s = Math.min(ORG_PAGE_SIZE_MAX, Math.max(1, asInt(size, defaultSize)));
  const wantPage = Math.max(0, asInt(page, 0));
  const maxPage = Math.max(0, Math.floor(ORG_OFFSET_MAX / s));          // page * size <= ORG_OFFSET_MAX
  const p = Math.min(wantPage, maxPage);
  return { page: p, size: s, clamped: p !== wantPage || s !== asInt(size, defaultSize) };
}
/** the highest page index the server accepts for this size (the UI disables "next" beyond it) */
export const orgMaxPage = (size: number): number => Math.max(0, Math.floor(ORG_OFFSET_MAX / Math.min(ORG_PAGE_SIZE_MAX, Math.max(1, Math.trunc(size) || 1))));

export const orgApi = {
  // ---- unit types (ORG_STRUCTURE_VIEW / ORG_STRUCTURE_MANAGE)
  listUnitTypes: (tenantId: string, includeInactive = true) => call<OrgUnitTypeDto[]>(`${T(tenantId)}/organization-unit-types${qs({ includeInactive: String(includeInactive) })}`),
  getUnitType: (tenantId: string, typeId: string) => call<OrgUnitTypeDto>(`${T(tenantId)}/organization-unit-types/${seg(typeId)}`),
  createUnitType: (tenantId: string, b: OrgUnitTypeCreateBody) => call<OrgUnitTypeDto>(`${T(tenantId)}/organization-unit-types`, { method: "POST", body: json(b) }),
  updateUnitType: (tenantId: string, typeId: string, b: OrgUnitTypeUpdateBody) => call<OrgUnitTypeDto>(`${T(tenantId)}/organization-unit-types/${seg(typeId)}`, { method: "PATCH", body: json(b) }),
  disableUnitType: (tenantId: string, typeId: string, expectedVersion: number) => call<OrgUnitTypeDto>(`${T(tenantId)}/organization-unit-types/${seg(typeId)}/disable`, { method: "POST", body: OWN(expectedVersion) }),
  enableUnitType: (tenantId: string, typeId: string, expectedVersion: number) => call<OrgUnitTypeDto>(`${T(tenantId)}/organization-unit-types/${seg(typeId)}/enable`, { method: "POST", body: OWN(expectedVersion) }),

  // ---- units
  /** `format=tree`: nested nodes with `directMemberCount` / `subtreeEmployeeCount` (counts of the whole tenant in one statement) */
  unitTree: (tenantId: string, includeArchived = false) => call<OrgUnitNodeDto[]>(`${T(tenantId)}/organization-units${qs({ format: "tree", includeArchived: String(includeArchived) })}`),
  /** `format=flat`: the same units with `parentId`, no counts */
  unitList: (tenantId: string, includeArchived = false) => call<OrgUnitDto[]>(`${T(tenantId)}/organization-units${qs({ format: "flat", includeArchived: String(includeArchived) })}`),
  getUnit: (tenantId: string, unitId: string) => call<OrgUnitDetailDto>(`${T(tenantId)}/organization-units/${seg(unitId)}`),
  createUnit: (tenantId: string, b: OrgUnitCreateBody) => call<OrgUnitDto>(`${T(tenantId)}/organization-units`, { method: "POST", body: json(b) }),
  updateUnit: (tenantId: string, unitId: string, b: OrgUnitUpdateBody) => call<OrgUnitDto>(`${T(tenantId)}/organization-units/${seg(unitId)}`, { method: "PATCH", body: json(b) }),
  /** `newParentId` is ALWAYS in the body (a UUID = under that unit, `null` = to the root): an absent key is a 400, never a silent move to the root */
  moveUnit: (tenantId: string, unitId: string, b: OrgUnitMoveBody) =>
    call<OrgUnitDto>(`${T(tenantId)}/organization-units/${seg(unitId)}/move`, { method: "POST", body: json({ newParentId: b.newParentId ?? null, expectedVersion: b.expectedVersion, ...(b.sortOrder === undefined ? {} : { sortOrder: b.sortOrder }) }) }),
  archiveUnit: (tenantId: string, unitId: string, expectedVersion: number) => call<OrgUnitDto>(`${T(tenantId)}/organization-units/${seg(unitId)}/archive`, { method: "POST", body: OWN(expectedVersion) }),
  restoreUnit: (tenantId: string, unitId: string, expectedVersion: number) => call<OrgUnitDto>(`${T(tenantId)}/organization-units/${seg(unitId)}/restore`, { method: "POST", body: OWN(expectedVersion) }),

  // ---- positions and grades (POSITION_GRADE_VIEW / POSITION_GRADE_MANAGE): separate taxonomies, they authorize nothing
  listPositions: (tenantId: string, includeInactive = true) => call<OrgPositionDto[]>(`${T(tenantId)}/positions${qs({ includeInactive: String(includeInactive) })}`),
  createPosition: (tenantId: string, b: OrgPositionCreateBody) => call<OrgPositionDto>(`${T(tenantId)}/positions`, { method: "POST", body: json(b) }),
  updatePosition: (tenantId: string, id: string, b: OrgPositionUpdateBody) => call<OrgPositionDto>(`${T(tenantId)}/positions/${seg(id)}`, { method: "PATCH", body: json(b) }),
  disablePosition: (tenantId: string, id: string, expectedVersion: number) => call<OrgPositionDto>(`${T(tenantId)}/positions/${seg(id)}/disable`, { method: "POST", body: OWN(expectedVersion) }),
  enablePosition: (tenantId: string, id: string, expectedVersion: number) => call<OrgPositionDto>(`${T(tenantId)}/positions/${seg(id)}/enable`, { method: "POST", body: OWN(expectedVersion) }),
  listGrades: (tenantId: string, includeInactive = true) => call<OrgGradeDto[]>(`${T(tenantId)}/grades${qs({ includeInactive: String(includeInactive) })}`),
  createGrade: (tenantId: string, b: OrgGradeCreateBody) => call<OrgGradeDto>(`${T(tenantId)}/grades`, { method: "POST", body: json(b) }),
  updateGrade: (tenantId: string, id: string, b: OrgGradeUpdateBody) => call<OrgGradeDto>(`${T(tenantId)}/grades/${seg(id)}`, { method: "PATCH", body: json(b) }),
  disableGrade: (tenantId: string, id: string, expectedVersion: number) => call<OrgGradeDto>(`${T(tenantId)}/grades/${seg(id)}/disable`, { method: "POST", body: OWN(expectedVersion) }),
  enableGrade: (tenantId: string, id: string, expectedVersion: number) => call<OrgGradeDto>(`${T(tenantId)}/grades/${seg(id)}/enable`, { method: "POST", body: OWN(expectedVersion) }),

  // ---- employees (EMPLOYEE_VIEW / EMPLOYEE_MANAGE; create, enable and disable also need TENANT_MEMBERS on the server)
  /** the directory: `size` 1..100, `page >= 0`, `page * size <= 10000` (clamped HERE so an out-of-range request is never sent), search text at least 2 characters (shorter is dropped, never sent) */
  listEmployees: (tenantId: string, q: OrgEmployeeQuery = {}) => {
    const { page, size } = clampOrgPaging(q.page, q.size);
    const text = q.q?.trim();
    return call<OrgEmployeePageDto>(`${T(tenantId)}/employees${qs({
      q: text && text.length >= ORG_SEARCH_MIN ? text : undefined, organizationUnitId: q.organizationUnitId, includeDescendants: q.organizationUnitId ? String(q.includeDescendants ?? true) : undefined,
      positionId: q.positionId, gradeId: q.gradeId, active: q.active === undefined ? undefined : String(q.active), page, size, sort: q.sort, dir: q.dir })}`);
  },
  getEmployee: (tenantId: string, userId: string) => call<OrgEmployeeDto>(`${T(tenantId)}/employees/${seg(userId)}`),
  createEmployee: (tenantId: string, b: OrgEmployeeCreateBody) => call<OrgEmployeeCreatedDto>(`${T(tenantId)}/employees`, { method: "POST", body: json(b) }),
  disableEmployee: (tenantId: string, userId: string) => call<OrgEmployeeDto>(`${T(tenantId)}/employees/${seg(userId)}/disable`, { method: "POST" }),
  enableEmployee: (tenantId: string, userId: string) => call<OrgEmployeeDto>(`${T(tenantId)}/employees/${seg(userId)}/enable`, { method: "POST" }),

  // ---- memberships (an employee may belong to several units; exactly one is primary)
  listMemberships: (tenantId: string, userId: string, includeInactive = false) => call<OrgMembershipDto[]>(`${T(tenantId)}/employees/${seg(userId)}/organization-memberships${qs({ includeInactive: String(includeInactive) })}`),
  addMembership: (tenantId: string, userId: string, b: OrgMembershipCreateBody) => call<OrgMembershipDto>(`${T(tenantId)}/employees/${seg(userId)}/organization-memberships`, { method: "POST", body: json(b) }),
  updateMembership: (tenantId: string, userId: string, membershipId: string, b: OrgMembershipUpdateBody) =>
    call<OrgMembershipDto>(`${T(tenantId)}/employees/${seg(userId)}/organization-memberships/${seg(membershipId)}`, { method: "PATCH", body: json(b) }),
  removeMembership: (tenantId: string, userId: string, membershipId: string, expectedVersion: number) =>
    call<OrgMembershipDto>(`${T(tenantId)}/employees/${seg(userId)}/organization-memberships/${seg(membershipId)}${qs({ expectedVersion })}`, { method: "DELETE" }),

  // ---- positions held by an employee, WITHIN one of their memberships
  listEmployeePositions: (tenantId: string, userId: string, includeInactive = false) => call<OrgEmployeePositionDto[]>(`${T(tenantId)}/employees/${seg(userId)}/positions${qs({ includeInactive: String(includeInactive) })}`),
  addEmployeePosition: (tenantId: string, userId: string, b: OrgEmployeePositionCreateBody) => call<OrgEmployeePositionDto>(`${T(tenantId)}/employees/${seg(userId)}/positions`, { method: "POST", body: json(b) }),
  updateEmployeePosition: (tenantId: string, userId: string, id: string, b: OrgEmployeePositionUpdateBody) =>
    call<OrgEmployeePositionDto>(`${T(tenantId)}/employees/${seg(userId)}/positions/${seg(id)}`, { method: "PATCH", body: json(b) }),
  removeEmployeePosition: (tenantId: string, userId: string, id: string, expectedVersion: number) =>
    call<OrgEmployeePositionDto>(`${T(tenantId)}/employees/${seg(userId)}/positions/${seg(id)}${qs({ expectedVersion })}`, { method: "DELETE" }),
};
export type OrgApi = typeof orgApi;
