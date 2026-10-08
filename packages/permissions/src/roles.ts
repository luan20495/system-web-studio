/**
 * The ONE place the frontend compares an admin ROLE NAME (guarded by tests/builder/guard-role-names.test.ts).
 *
 * A role name is display data the server happens to send; what a person may DO is the list of permission codes it resolves (`canonical.ts`). Two UX decisions still have no code to read, so they read the name here:
 *  - which of a person's tenants the Admin console offers ("TENANT_ADMIN" of a tenant that is not the primary one): `/auth/me` lists the permissions of the PRIMARY tenant only (C1 handoff: a per-tenant TENANT_MEMBERS code, M-052);
 *  - the "keep at least one administrator" hint shown before the click (the server still refuses with LAST_TENANT_ADMIN / LAST_ADMIN).
 * Display only: a wrong answer shows a screen whose calls answer 403. Replace the bodies with permission-code reads when C1 lists the codes per tenant / workspace; no caller changes.
 */
export const isTenantAdminRole = (role: string | null | undefined): boolean => role === "TENANT_ADMIN";
export const isWorkspaceAdminRole = (role: string | null | undefined): boolean => role === "WORKSPACE_ADMIN";
