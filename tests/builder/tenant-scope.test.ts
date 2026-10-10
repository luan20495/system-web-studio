// @class: unit — M-052 / AD01: tenant-scoped permissions resolved from tenants[].permissions (the ONE resolver); no role label, no flattening, fail closed on a backend without the field
import test from "node:test";
import assert from "node:assert/strict";
import type { Me } from "@xweb/types";
import { TENANT_ADMIN_CODES, canAccessPortal, capabilitiesOf, holdsInTenant, primaryTenantId, tenantPermissionsOf, tenantsAdministered } from "../../packages/permissions/src/index";

const EIGHT = ["TENANT_MEMBERS", "TENANT_MANAGE", "ORG_STRUCTURE_VIEW", "ORG_STRUCTURE_MANAGE", "EMPLOYEE_VIEW", "EMPLOYEE_MANAGE", "POSITION_GRADE_VIEW", "POSITION_GRADE_MANAGE"];
const DEFAULT = "00000000-0000-0000-0000-000000000001";
/** `permissions` omitted = a payload WITHOUT the per-tenant field (only a stale cache / an old backend could produce it; the type now requires it, the resolver stays defensive) */
const row = (id: string, role: string, permissions?: string[], status = "ACTIVE") => ({ id, slug: id, name: id, status, role, ...(permissions ? { permissions } : {}) }) as unknown as NonNullable<Me["tenants"]>[number];
const me = (o: Partial<Me> = {}): Me => ({ id: "u", username: "u", displayName: "U", roles: [], workspaces: [], ...o });
/** the AD01 fixture: primary DEFAULT, role MEMBER, root permissions []; secondary company, role TENANT_ADMIN, the exact canonical eight */
const AD01 = me({ tenantId: DEFAULT, permissions: [], tenants: [row(DEFAULT, "MEMBER", []), row("company", "TENANT_ADMIN", EIGHT)] });

test("the eight tenant-admin codes are the canonical ones (six organization codes + TENANT_MEMBERS + TENANT_MANAGE), nothing invented", () => {
  assert.deepEqual([...TENANT_ADMIN_CODES].sort(), [...EIGHT].sort());
});

test("AD01 SECONDARY_TENANT_ADMIN: the second company's own codes are resolved for THAT company; the primary tenant (DEFAULT, MEMBER, root []) holds nothing", () => {
  assert.deepEqual([...tenantPermissionsOf(AD01, "company")].sort(), [...EIGHT].sort());
  assert.deepEqual([...tenantPermissionsOf(AD01, DEFAULT)], []);
  assert.deepEqual(tenantsAdministered(AD01).map((t) => t.id), ["company"]);
  assert.equal(holdsInTenant(AD01, "company", "TENANT_MANAGE"), true); assert.equal(holdsInTenant(AD01, DEFAULT, "TENANT_MANAGE"), false);
  assert.equal(primaryTenantId(AD01), DEFAULT);
});

test("PRIMARY_MEMBER_NO_LEAK + CROSS_TENANT_ISOLATION: company A's codes authorize neither the primary tenant nor company B nor an unknown / empty tenant", () => {
  const m = me({ tenantId: DEFAULT, permissions: [], tenants: [row(DEFAULT, "MEMBER", []), row("a", "TENANT_ADMIN", EIGHT), row("b", "MEMBER", ["ORG_STRUCTURE_VIEW"])] });
  for (const code of EIGHT) { assert.equal(holdsInTenant(m, "a", code as never), true, code); assert.equal(holdsInTenant(m, DEFAULT, code as never), false, `${code} leaked into the primary tenant`); }
  assert.equal(holdsInTenant(m, "b", "ORG_STRUCTURE_VIEW"), true); assert.equal(holdsInTenant(m, "b", "ORG_STRUCTURE_MANAGE"), false, "a's manage code does not unlock b");
  for (const id of ["nope", "", null, undefined]) assert.equal(tenantPermissionsOf(m, id as string).size, 0, String(id));
  assert.equal(tenantPermissionsOf(null, "a").size, 0); assert.equal(tenantPermissionsOf(me({ tenants: undefined }), "a").size, 0);
  // the result is a copy: mutating it cannot change what the next call says
  const first = tenantPermissionsOf(m, "a"); first.clear(); assert.equal(tenantPermissionsOf(m, "a").size, 8);
});

test("ROLE_ONLY_NEGATIVE: a role label alone enables nothing, in either place a role appears (the membership row, the top-level tenantRole)", () => {
  const m = me({ tenantId: DEFAULT, tenantRole: "TENANT_ADMIN", permissions: [], tenants: [row(DEFAULT, "TENANT_ADMIN", []), row("company", "TENANT_ADMIN", [])] });
  assert.deepEqual(tenantsAdministered(m), []); assert.equal(tenantPermissionsOf(m, "company").size, 0);
  assert.equal(capabilitiesOf(m).has("admin.console"), false); assert.equal(canAccessPortal(m, "admin"), false); assert.equal(capabilitiesOf(m).has("tenant.members"), false);
  // a backend before M-052 (no per-tenant field): a SECONDARY tenant says nothing even with the role TENANT_ADMIN; only the primary resolves, from the top-level list
  const old = me({ tenantId: DEFAULT, permissions: [], tenants: [row(DEFAULT, "MEMBER"), row("company", "TENANT_ADMIN")] });
  assert.deepEqual(tenantsAdministered(old), []); assert.equal(canAccessPortal(old, "admin"), false);
  const oldPrimary = me({ tenantId: DEFAULT, permissions: EIGHT, tenants: [row(DEFAULT, "TENANT_ADMIN"), row("company", "MEMBER")] });
  assert.deepEqual(tenantsAdministered(oldPrimary).map((t) => t.id), [DEFAULT]); assert.equal(holdsInTenant(oldPrimary, "company", "TENANT_MEMBERS"), false, "the top-level list speaks for the primary tenant only");
  // an unknown / non-canonical code in a tenant's list is dropped (ORG_MANAGE does not exist)
  assert.equal(tenantPermissionsOf(me({ tenants: [row("t", "MEMBER", ["ORG_MANAGE", "SYSTEM_ADMIN", "TENANT_ADMIN"])] , tenantId: "t" }), "t").size, 0);
});

test("the Admin portal admits the secondary company's administrator by the CODES of that company; the primary MEMBER with no codes is not admitted", () => {
  const caps = capabilitiesOf(AD01);
  assert.equal(caps.has("admin.console"), true); assert.equal(caps.has("tenant.members"), true); assert.equal(canAccessPortal(AD01, "admin"), true);
  assert.equal(caps.has("platform.operate"), false, "company administration is not platform scope"); assert.equal(caps.has("tenant.administer"), false);
  const member = me({ tenantId: DEFAULT, permissions: [], tenants: [row(DEFAULT, "MEMBER", [])] });
  assert.equal(canAccessPortal(member, "admin"), false); assert.equal(capabilitiesOf(member).has("tenant.members"), false);
  // revocation / refresh: the next /auth/me lists no codes for the company → admission is gone
  const revoked = me({ tenantId: DEFAULT, permissions: [], tenants: [row(DEFAULT, "MEMBER", []), row("company", "TENANT_ADMIN", [])] });
  assert.equal(canAccessPortal(revoked, "admin"), false);
  // only ORG codes (no TENANT_MEMBERS) still open the console for that company (an employee viewer), without tenant.members
  const viewer = me({ tenantId: DEFAULT, permissions: [], tenants: [row(DEFAULT, "MEMBER", []), row("company", "MEMBER", ["EMPLOYEE_VIEW"])] });
  assert.equal(canAccessPortal(viewer, "admin"), true); assert.equal(capabilitiesOf(viewer).has("tenant.members"), false);
});

test("SUSPENDED tenant: still listed WITH its codes (the server decides the write: 403 TENANT_SUSPENDED); the resolver never turns a status into a denial or into a promise", () => {
  const m = me({ tenantId: DEFAULT, permissions: [], tenants: [row(DEFAULT, "MEMBER", []), row("company", "TENANT_ADMIN", EIGHT, "SUSPENDED")] });
  assert.deepEqual(tenantsAdministered(m).map((t) => [t.id, t.status]), [["company", "SUSPENDED"]]); assert.equal(holdsInTenant(m, "company", "TENANT_MANAGE"), true);
});

test("a person with no membership rows (a mock / an older backend) keeps the top-level behaviour: TENANT_MEMBERS in permissions still means tenant.members", () => {
  assert.equal(capabilitiesOf(me({ permissions: ["TENANT_MEMBERS"] })).has("tenant.members"), true); assert.equal(capabilitiesOf(me({ permissions: [] })).has("tenant.members"), false);
});
