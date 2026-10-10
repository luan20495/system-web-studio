/**
 * Tenant-scoped permissions (M-052 / AD01). A person may belong to SEVERAL companies and hold different codes in each. `/auth/me` lists, per membership, the canonical codes the person holds IN THAT TENANT
 * (`tenants[].permissions`); this module is the ONE resolver of them. Rules:
 *  - the codes of tenant A never authorize anything in tenant B, nor in the primary tenant, and nothing is flattened into one global set;
 *  - a tenant's authority is its own `permissions` list and nothing else: NOT its `role` label (`tenants[].role` / `tenantRole` are display data), not platform scope, not workspace roles, not organization
 *    relations / positions / grades;
 *  - a row WITHOUT a `permissions` field (a backend before M-052) can say nothing about that tenant: the only row that may then use a list is the PRIMARY tenant, whose codes are the top-level
 *    `/auth/me.permissions` (C1 final contract §2.4). Every other such tenant resolves to NOTHING (fail closed: the server's 403 answers).
 * Display only: the server authorizes every call per tenant (`AccessService.forTenant`). A tenant listed as SUSPENDED still lists its codes: a mutation may still be refused (403 TENANT_SUSPENDED),
 * so a listed code is never proof that a write will succeed.
 * Plain relative imports only (loaded by the unit tests under node).
 */
import type { Me } from "@xweb/types";
import { resolveCanonicalPermissions, type CanonicalPermissionCode } from "./canonical";

/** the canonical codes that make a person an administrator of (part of) ONE company: the tenant codes and the six organization codes */
export const TENANT_ADMIN_CODES: readonly CanonicalPermissionCode[] = ["TENANT_MEMBERS", "TENANT_MANAGE", "ORG_STRUCTURE_VIEW", "ORG_STRUCTURE_MANAGE", "EMPLOYEE_VIEW", "EMPLOYEE_MANAGE", "POSITION_GRADE_VIEW", "POSITION_GRADE_MANAGE"];

type TenantMe = Pick<Me, "tenants" | "tenantId" | "permissions">;
/** the primary tenant: `tenantId` when the server sent it, else the first listed membership (contract §2.4) */
export const primaryTenantId = (me: TenantMe | null | undefined): string | null => me?.tenantId ?? me?.tenants?.[0]?.id ?? null;

/** the canonical codes the person holds in THIS tenant (empty when the tenant is not one of their active memberships); never merged with another tenant's */
export function tenantPermissionsOf(me: TenantMe | null | undefined, tenantId: string | null | undefined): Set<CanonicalPermissionCode> {
  if (!me || !tenantId) return new Set();
  const row = me.tenants?.find((t) => t?.id === tenantId);
  if (!row) return new Set();
  if (Array.isArray(row.permissions)) return resolveCanonicalPermissions(row.permissions);
  return tenantId === primaryTenantId(me) ? resolveCanonicalPermissions(me.permissions) : new Set();
}
export const holdsInTenant = (me: TenantMe | null | undefined, tenantId: string | null | undefined, code: CanonicalPermissionCode): boolean => tenantPermissionsOf(me, tenantId).has(code);
/** the memberships in which the person holds at least one tenant-level code: the companies the Admin console may offer them (by CODE only, never by role) */
export function tenantsAdministered(me: TenantMe | null | undefined): NonNullable<Me["tenants"]> {
  return (me?.tenants ?? []).filter((t) => { const p = tenantPermissionsOf(me, t.id); return TENANT_ADMIN_CODES.some((c) => p.has(c)); });
}
