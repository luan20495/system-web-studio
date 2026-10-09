# C5 — IAM / permission / tenant / Dynamic Organization: frontend inventory (Phase A)

Date 2026-10-09, branch `agent/c5-web`, read from the code at `87c3d04` + `2b0fa31`. Read-only inventory: nothing here invents a backend contract. Evidence labels: **REAL_BACKEND** = the screen calls an endpoint that exists in `integration/v2` AND a `tests/e2e-real` flow exercised it on a real stack; **ADAPTER_ONLY** = the screen goes through a typed port whose operations are marked NOT_READY (sends nothing); **PREBUILD** = UI built and verified in the browser harness only; **HARNESS_ONLY**; **NOT_IMPLEMENTED**. Backend remains authoritative; every gate below is UX.

## 1. Where permissions are decided (one place each)
| Layer | File | What it does |
|---|---|---|
| canonical codes | `packages/types/src/contract/v2/permissions.ts` | the 14 codes `APP_VIEW APP_USE APP_EDIT APP_PUBLISH APP_SHARE DATA_SOURCE_VIEW DATA_SOURCE_MANAGE QUERY_EXECUTE DATA_MUTATE ACTION_EXECUTE WORKFLOW_EXECUTE WORKFLOW_MANAGE TENANT_MANAGE TENANT_MEMBERS`; the only alias table (`PROJECT_READ/EDIT/SETTINGS/PUBLISH/MEMBERS`) |
| resolution + helpers | `packages/permissions/src/canonical.ts` | input is ONLY a list of codes the server returned; unknown codes are dropped; `canViewProject`, `canEditProject`, `isReadOnlyProject`, `canPublish`, `canRollback`, `canShare`, `canViewDataSources`, `canManageDataSources`, `canBindDataSources`, `canRunTestQuery`, `canRunAction`/`actionRequires`, `canStartWorkflow`, `canManageWorkflows`, `canViewStudioIn`, `missingReason` |
| portal admission | `packages/permissions/src/index.ts` `capabilitiesOf` | `platform.operate`, `tenant.administer`, `tenant.members`, `workspace.members`, `workspace.data`, `admin.console`, `studio.build`; Studio now also reads `projectScopes` (H-C1-04, commit `87c3d04`) |
| the only role-name reads | `packages/permissions/src/roles.ts` | `isTenantAdminRole`, `isWorkspaceAdminRole`; guarded by `tests/builder/guard-role-names.test.ts` and the Studio role guard in `tests/builder/permissions.test.ts` |
| Admin section visibility | `features/admin/console/sectionPolicy.ts` | pure table: `open/system/company/tenant/workspace/data` access, `needs-platform`, `needs-scope`, `coming`, `elsewhere`, `notfound` |

## 2. PERMISSIONS_CURRENTLY_CONSUMED
Consumed by a real UI decision: `APP_VIEW` (Studio admission, project open / read-only, `/auth/no-access?reason=app-view`), `APP_EDIT` (edit controls, TEST), `APP_PUBLISH` (publish / rollback / unpublish buttons), `APP_SHARE` (members panel; storage alias `PROJECT_MEMBERS`), `DATA_SOURCE_VIEW`, `DATA_SOURCE_MANAGE` (+ `APP_EDIT` for binding), `APP_USE` + `QUERY_EXECUTE` + `APP_EDIT` (Test panel query), `APP_USE` + `ACTION_EXECUTE` (+ `DATA_MUTATE` for the five mutating action types, + `WORKFLOW_EXECUTE` for START_WORKFLOW, + the action's declared code, + `APP_EDIT` in TEST), `WORKFLOW_EXECUTE`, `WORKFLOW_MANAGE` (a hint only: run status/cancel is decided by the server, C4 F-1 open), `TENANT_MEMBERS` (`tenant.members`, member list, provisioning), `TENANT_MANAGE` (provisioning metadata `needs`, not a client gate).
Consumed but NOT in the contract mirror's 14 codes: **`MEMBER_MANAGE`** (`capabilitiesOf` → `workspace.members`; `adminModel.ts:29,42`; provisioning `needs`) and the storage constant `PROJECT_DELETE` (`holdsStorageConstant`, archive/restore). `ORG_MANAGE` appears only as an *assumed* `need` in the NOT_READY organization table.
`APP_USE` has no end-user runtime UI in C5 (Studio only uses it inside TEST); the product "use" surface is the published site.

## 3. BACKEND_ENDPOINTS_CURRENTLY_CONSUMED (identity / tenant / membership / sharing; source `packages/api-client/src/api.ts`)
| METHOD | PATH | request → response | call site |
|---|---|---|---|
| POST / GET | `/auth/login`, `/auth/me`, `/auth/config` | `{username,password}` → `Me` (`permissions`, `workspaces[].permissions`, `tenants`, `platformScope`, `businessAccess`; **`projectScopes` typed 2026-10-09**) | `packages/auth`, `PortalApp` |
| POST | `/auth/activation/inspect`, `/auth/activation/complete` | `{token}` → `{username,displayName,purpose}`; `{token,password}` → `{status}` | `AuthPages` |
| GET | `/admin/tenants`, `/admin/tenants/{id}` | → `TenantView[]` / `TenantView` | `TenantScreens`, `UsersPages` |
| POST | `/admin/tenants` | `{slug,name,firstAdminUserId?}` → `TenantView` | create company |
| PATCH | `/admin/tenants/{id}/status` | `{status: ACTIVE|SUSPENDED|DELETED}` → `TenantView` | suspend / restore |
| GET / PUT / DELETE | `/admin/tenants/{id}/members[/{userId}]` | `{role}` → `TenantMemberView` | tenant members |
| GET | `/admin/tenants/{id}/member-candidates?q` | → `TenantMemberCandidate[]` | person picker |
| POST | `/admin/tenants/{id}/users` | `{username,displayName,email?,tenantRole?,workspaceId?,workspaceRole?}` → `ActivationLink` (the account IS the invitation) | `ProvisioningScreens` |
| POST | `/admin/tenants/{id}/workspaces` | `{name}` → `{id,name,slug,tenantId}` | provisioning |
| GET / PATCH | `/admin/users`, `/admin/users/{id}`, `/admin/users/{id}/status` (`{enabled}`) | → `Page<AdminUser>`, `AdminUserDetail` | `UsersPages` (disable / reactivate) |
| POST | `/admin/users/{id}/activation-link`, `/revoke-sessions`, `/system-admin` (`{grant,confirm:true}`) | → `ActivationLink` / `{revoked}` / `{systemAdmin}` | `UserDialogs` |
| GET / POST / PATCH / DELETE | `/workspaces/{w}/members[/{userId}]` | `{username?|email?, role}` / `{role}` → `Member` | `drawers.tsx` (workspace) |
| GET / POST / PATCH / DELETE | `/workspaces/{w}/projects/{p}/members[/{userId}]` | same | `drawers.tsx` (project sharing) |
| GET / POST / DELETE | `/admin/scim`, `/admin/scim/mappings[/{id}]` | `{groupId,workspaceId,role}` | `IdentityPages` |
| GET / POST / PATCH / DELETE / PUT | `/admin/departments[...]`, `/admin/departments/assign/users/{id}`, `/assign/workspaces/{id}` | `{name,kind,parentId?}` → `Department[]` | existing flat/tree departments (NOT the Dynamic Organization contract) |
| POST | `/admin/applications/{id}/transfer-ownership`, `/archive`, `/restore` | `{userId}` | `ApplicationsPages` |
No Organization / OrganizationUnit / position / grade / permission-grant endpoint exists in the client (nothing named, nothing guessed).

## 4. ROLE_NAME_SHORTCUTS (every match classified)
| Where | Classification | Note |
|---|---|---|
| `packages/permissions/src/roles.ts` `isTenantAdminRole`, `isWorkspaceAdminRole` | AUTH_LOGIC (UX only), isolated | the single allowed place; guard test. Used by `capabilitiesOf` (`tenant.members`), `adminModel.ts:26` (which tenants the Admin console offers) and `memberChangeBlock` (the "keep at least one administrator" hint). Replace with codes when C1 lists per-tenant codes (HF-C1-17-style request). The server still refuses (`LAST_TENANT_ADMIN`, `SELF_GRANT_FORBIDDEN`). |
| `adminModel.ts` `TENANT_ROLES`, `WORKSPACE_ROLES`; `provisioning.ts` types; `packages/types` `WORKSPACE_ROLES`, `PROJECT_ROLES`; `i18n/roles.ts` | DISPLAY_ONLY (option lists / labels sent as data) | the server maps role → permissions |
| `AiGovernancePage.tsx:35`, `IdentityPages.tsx:69` hard-coded role `<option>` lists | DISPLAY_ONLY, duplicated lists | candidate for the shared `WORKSPACE_ROLE_ORDER` (P3) |
| `drawers.tsx` member role `<select>`, `TenantScreens.tsx:122,385` | DISPLAY_ONLY (value sent to the server) | |
| `packages/auth` portal `=== "admin"` etc. | FALSE_POSITIVE (portal id, not a role) | |
| tests (`tests/builder/*`, harness fixtures) | TEST_ONLY | |
**AUTH_LOGIC role-name shortcuts to remove: none outstanding** (all three UX reads sit in `roles.ts`'s two functions). Stale comment: `drawers.tsx:144` says MEMBER_MANAGE "is not exposed to the client" while `capabilitiesOf` reads it from `/auth/me` (C1 H-C1-05 lists it): fix the comment, and ask C1 to confirm `MEMBER_MANAGE` as a canonical code and add it to the contract mirror.

## 5. DIRECT_URL_BEHAVIOR and PERMISSION_DENIED_UX
| Route / component | Behaviour | Evidence |
|---|---|---|
| any portal path without a session | `PortalApp` → `/login?next=…` (safe `next`) | `tests/builder/portals.test.ts` |
| portal without admission (`canAccessPortal`) | `<Redirect to="/auth/no-access?portal=…">` BEFORE the portal renders (no privileged content flash) | `portals.test.ts`, `e2e-04/05` (real) |
| disabled account | `/auth/no-access?reason=disabled` | `AuthPages` |
| Admin section the person may not use | `resolveSection` → `needs-platform` / `needs-scope` / `elsewhere` / `notfound` screens, never the section | `sectionpolicy.test.ts`, `admin.spec` route snapshot (SKP) |
| Studio project the server refuses | the project call answers 404/403 → the project error state; a project that loads WITHOUT `APP_VIEW` is replaced by `/auth/no-access?portal=studio&reason=app-view` in an effect (`ProjectWorkspace.tsx:79`; **one render may precede the redirect: P3 follow-up, harmless because the server only returns permitted projects**) | `e2e-04` |
| 401 mid-session | `onUnauthorized` + `sessionChanged()`: every cached list is dropped, redirect to login | `core.ts`, `useLoad` |
| 403 on an action | server text mapped to a user-facing message; buttons that can be predicted are disabled WITH a reason (`ReasonButton`, `missingReason`) | `permissions.test.ts`, `e2e-05` |
| stale permission state | permissions come with the project / `Me`; they are not polled while a page stays open (the server re-checks every call) | known, documented |
Real-stack evidence for these flows exists (`tests/e2e-real/flows/e2e-04,05,ad01,ad02,ad03,admin01,pl01,super01,user01`); **no new real-stack run was made in this session** (no stack started by C5): `DIRECT_URL_PERMISSION_TEST` for this session = harness/unit only, real-stack re-run at the final gate.

## 6. Tenant / company / account UI
| Capability | Screen | Backend | Class |
|---|---|---|---|
| create tenant (+ first admin) | Platform → Công ty | `POST /admin/tenants` | REAL_BACKEND (`e2e-pl01`, `verify-create-company-ui`) |
| edit tenant (rename) | none | no `PATCH /admin/tenants/{id}` in the client | NOT_IMPLEMENTED (contract absent) |
| disable / restore tenant | Công ty → status | `PATCH …/status` | REAL_BACKEND |
| assign tenant admin | tenant members, provisioning | `PUT …/members/{userId}` | REAL_BACKEND (`ad03`, `admin01`) |
| create user / invite | Người dùng → Tạo tài khoản (activation link shown once) | `POST …/users` | REAL_BACKEND (`admin01`) |
| disable / reactivate user | Người dùng | `PATCH /admin/users/{id}/status` (platform scope only) | REAL_BACKEND |
| re-issue activation link | user detail | `POST …/activation-link` (platform scope; tenant admin cannot: M-007 BLOCKED_BY_C1) | REAL_BACKEND (platform only) |
| membership (tenant / workspace / project) | members screens, drawers | members endpoints above | REAL_BACKEND |

## 7. Dynamic Organization UI
All screens exist behind `features/admin/organization.ts` (`OrganizationApi` port, `CAPABILITIES` table) and `organizationAdapter.ts` (the production adapter): **every operation is `NOT_READY`**, the adapter throws `OrganizationNotReady` and sends nothing; the only live call is the tenant member list used by the employee directory fallback. `e2e-org01` is a BLOCKED skeleton (WAITING_FOR_C1, H-C1-17). C3 has persistence proposals only (`docs/parallel/c3/ORGANIZATION_PERSISTENCE.md`, `V32_ORGANIZATION_PROPOSAL.md`, branches `agent/c3-org-*`): no controller, no route in `integration/v2`.
| Feature | UI | Class |
|---|---|---|
| unit types (list, create) | `OrganizationScreens` dialog | PREBUILD / ADAPTER_ONLY |
| tree (expand/collapse, select, search/filter, long names, windowed above 1000 visible rows M-111) | `OrganizationScreens` | PREBUILD (`org.spec` 89 + `org-hardening` 70 checks, HARNESS / SYNTHETIC) |
| unit detail, create, edit, move ("Di chuyển tới…" dialog, keyboard friendly, cycle shown from the server's `ORG_CYCLE`), delete confirmation | same | PREBUILD / ADAPTER_ONLY |
| employee directory (search, unit/status filter, pagination, detail, effective-permission note "organization is not a permission") | `EmployeesScreens` | PREBUILD; falls back to the REAL tenant member list (no unit / position, said on screen) |
| position, grade | position list in the port; grade: not in the port | position PREBUILD; **grade NOT_IMPLEMENTED** (no field in the frontend contract) |
| states LOADING / SUCCESS / EMPTY / 403 / 404 / ERROR, SUBMITTING guard (`useAction`) | all org screens | PREBUILD, harness-verified |
`ORG_BACKEND_READY = NO` (unchanged).

## 8. Application permission / sharing UI
Role-based membership only: Studio drawers add / change / remove a member by **username or e-mail + a role** (`OWNER/EDITOR/PUBLISHER/VIEWER`, project) and workspace roles (`WORKSPACE_ADMIN/EDITOR/PUBLISHER/VIEWER`); the labels and one-line hints are display (`ROLE_HINT`), the server maps a role to codes. Gated by `APP_SHARE` (project) / the server's answer (workspace). Not present: per-permission grants, VIEW vs USE as separate choices, group / organization targeting, effective-permission display for another person, runtime-use grants. **NOT_IMPLEMENTED; BLOCKED_BY_C1** (the grant / principal / scope / effective-permission contract does not exist). Organization membership is never read as an application permission (verified: no code path). Publish UX: `canPublish` = `APP_PUBLISH` only; `APP_VIEW`/`APP_EDIT` never stand in for it.

## 9. Classification summary
- REAL_BACKEND: auth, tenant lifecycle, tenant members, user provisioning, user enable/disable, workspace + project membership, SCIM mapping, Studio admission by resolved permissions (incl. `projectScopes` unit-tested; **real-stack retest of H-C1-04 pending**).
- ADAPTER_ONLY: Organization (all 12 operations), employee directory (except the member-list fallback).
- PREBUILD / HARNESS_ONLY: Organization tree, unit types, unit CRUD, move, delete, employee detail.
- NOT_IMPLEMENTED: tenant rename, grade, permission-grant / principal / effective-permission UI, group / org targeting.
- BLOCKED_BY_C1: organization contract (units, types, positions, grades, employees, error codes), grants / principals / effective permissions, per-tenant codes in `/auth/me` (retire the two role reads), `MEMBER_MANAGE` / `ORG_MANAGE` canonical status, M-007 (tenant admin re-issues activation link), M-009 (admin-scoped app delete/restore for a non-member platform admin), M-058.
- BLOCKED_BY_C0: runner support for `@xweb/*` (HF-C0-M107); CSP sha256 for the canvas script (M-089).
- BLOCKED_BY_C2: host-pinned redirect needs the sites origin (M-051); renderer half of the preview banner (M-042).
- BLOCKED_BY_C3: organization persistence has no API (proposals only).
- BLOCKED_BY_C4: workflow run status/cancel creator cross-scope (F-1).
