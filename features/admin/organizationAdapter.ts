/**
 * The production adapter of the organization screens. Every operation is NOT_READY until C1 publishes its contract (organization.ts CAPABILITIES), so no URL is named here;
 * the only live call is the tenant member list (C1 2356d64) that backs the employee directory while `listEmployees` is NOT_READY.
 */
import { api } from "@/lib/http-api";
import { createOrganizationApi } from "./organization";
import { employeesFromMembers } from "./organizationModel";

export const liveOrganization = createOrganizationApi({ tenantMembers: (tenantId) => api.admin.tenantMembers(tenantId) }, employeesFromMembers);
