# C1 final contract: IAM, tenant, organization and permission

Branch `feat/c1-final-iam-org-permissions`. This file is the single C1 contract for identity, account lifecycle, sessions, tenants, tenant membership, the organization route summary and the permission matrix. **The code on this branch is the source of truth.** Every statement below was checked against the current source. Where an older C1 document or an inventory says otherwise, this file and the code win.

Path abbreviations used for evidence:
- `M/` = `backend/src/main/kotlin/com/systemwebstudio/`
- `T/` = `backend/src/test/kotlin/com/systemwebstudio/`

Status words used in this file:
- **DONE**: in the source of this branch and covered by a test.
- **DONE (no test)**: in the source of this branch, but no test was found under `T/` when this file was written.
- **GAP**: known behaviour that is not the target. The owner is named.

DTOs, request bodies and detailed error lists for organization, employee, position and grade routes are **not repeated here**. They live in [organization-employee-contract.md](organization-employee-contract.md) (the "org contract"). Section 6 holds the one authoritative route summary. The `/auth/me` project-scope background is in [h-c1-04-project-scoped-auth-me.md](h-c1-04-project-scoped-auth-me.md).

---

## 0. Scope and ownership

| Area | Owner | Files |
|---|---|---|
| Identity, login, sessions, `/auth/me`, accounts, activation | **C1** | `M/identity/**`, except `identity/SecurityConfiguration.kt`, which is C0-only (OWNERSHIP.md L27-28) |
| Access decisions, permission vocabulary, matrix | **C1** (HOT) | `M/access/AccessService.kt`, `M/access/Permission.kt`, `M/access/MeTenancy.kt`, `M/access/adapters/**` |
| Tenants, tenant membership, company bootstrap | **C1** | `M/tenancy/**` |
| Workspace and project membership | **C1** | `M/member/**` |
| Organization API, authorization, services, seams | **C1** | `M/organization/**` |
| Organization **persistence** (SQL, migration, seam implementations) | **C3** | Nothing exists yet. It implements `M/organization/OrganizationRepositories.kt`. |
| Admin console (`/api/v1/admin/users/**`, `AdminGuard`) | **C0-gated** | `M/admin/**`, including `AdminUserController.kt` and `AdminSupport.kt` |
| Settings, common errors, audit | **C0-gated** | `M/settings/**`, `M/common/**`; `M/audit/**` (callers use only `AuditService.record`) |
| Frontend (portals, `/auth/me` type, permission helpers, org screens) | **C5** | `packages/**`, `features/**` |

Migrations:
- **MIGRATION_REQUIRED = YES, for the organization tables only.** Nothing else in this contract needs a schema change. Tenant rename writes the existing `tenants.name` and `tenants.updated_at`.
- **No migration number is chosen here.** `V32__dynamic_organization.sql` is **RESERVED by C0, not allocated** (`docs/parallel/MIGRATION_LEDGER.md:18`).
- V32 depends on **C2's V31** (`MIGRATION_LEDGER.md:17`). V31 is allocated, but its file has not been created. The V32 file may not exist before V31 is applied and C0 allocates V32. `outOfOrder` stays off.

---

## 1. Two lifecycles (frozen)

There are two independent switches. Neither one ever writes the other.

| | GLOBAL ACCOUNT | TENANT EMPLOYEE / MEMBERSHIP |
|---|---|---|
| Column | `users.enabled` | `tenant_members.active` (one row per tenant and user) |
| Who may change it | **Only the platform authority SYSTEM_ADMIN** (enabled, `system_admin` read live by `AdminGuard`, `M/admin/AdminSupport.kt:16-23`) | **Only a holder of `TENANT_MEMBERS` on that tenant**: its Tenant Admin, and also a platform SYSTEM_ADMIN through platform scope. For the employee routes, also `EMPLOYEE_MANAGE`, which in practice means only the Tenant Admin. |
| Routes | `PATCH /api/v1/admin/users/{id}/status {enabled}` (`M/admin/AdminUserController.kt:78-95`); SCIM `active=false` (`M/identity/Scim.kt:162-163`) | `DELETE` and `PUT /api/v1/admin/tenants/{t}/members/{u}` (`M/tenancy/TenantController.kt:139-157`); `POST /api/v1/admin/tenants/{t}/employees/{u}/disable` and `/enable` (`M/organization/OrganizationControllers.kt:188-195`) |
| Effect on sessions | A disable **deletes every session of the username** (`AdminUserController.kt:91,106-110`). The next request with the old cookie answers **401**. Re-enabling does **not** resurrect sessions: they are gone from Redis, and the user must log in again (`T/admin/AdminApiTests.kt:62-69`). | **No session is touched.** The next request recomputes access: tenant routes answer 404 `TENANT_NOT_FOUND`, the tenant's workspaces answer 404 `WORKSPACE_NOT_FOUND`, and `/auth/me` omits them. |
| Cross-tenant side effect | Affects every tenant. This is intended: it is the account. | **None.** Only that tenant's row changes. `users.enabled` is never written by any tenant route. Evidence: `TenantService.removeMember` / `setMember` (`M/tenancy/TenantService.kt:199-223`) and `EmployeeDirectoryService.setActive` (`M/organization/EmployeeDirectoryService.kt:127-142`). |
| Re-activation | `PATCH … {enabled:true}` only flips the flag (`AdminUserController.kt:89-93`). | `PUT members/{u} {role}` restores the given role. Employee `enable` always restores **MEMBER**, so a former TENANT_ADMIN does not get the role back (`EmployeeDirectoryService.kt:137`). A platform-disabled account gives 422 `USER_DISABLED` (`TenantController.kt:146`, `EmployeeDirectoryService.kt:136`). |

Pending activation and reset tokens, as coded:
- Disabling an account does **not** delete its unused `account_tokens`.
- While the account is disabled, the token cannot be used: lookup requires `u.enabled` (`M/identity/Accounts.kt:186-188`), so it answers 410 `LINK_INVALID`.
- `POST …/activation-link` refuses a disabled account with 409 `ACCOUNT_DISABLED` (`Accounts.kt:175`).
- **GAP (C0-gated `admin/**`, C1 `identity/**`):** after re-enabling, a token issued before the disable becomes usable again if it is still within its 24 h TTL (`Accounts.kt:64,187`). No test covers this. Target: the disable deletes unused tokens (`DELETE FROM account_tokens WHERE user_id=? AND used_at IS NULL`). This needs C0's approval in `AdminUserController` and the same change in `Scim.kt:162-167`.

---

## 2. AUTH

All routes are under `/api/v1/auth`. The session cookie is `STUDIO_SESSION` (`M/identity/SessionConfiguration.kt`). CSRF uses a cookie token (`XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header). CSRF is checked on every state-changing request, **including login** (`SecurityConfiguration.kt:171`; only the data webhook is exempt).

### 2.1 Routes

| METHOD PATH | REQUEST | RESPONSE | PERMISSION | TENANT SCOPE | ERRORS | VERSION | AUDIT |
|---|---|---|---|---|---|---|---|
| `GET /csrf` | – | `{token}` | public | – | – | – | – |
| `GET /config` | – | sign-in options map (`AuthController.kt:99-105`) | public | – | – | – | – |
| `POST /login` | `{username (≤120, trimmed), password (≤256)}` | `MeResponse` (2.2). Session id rotated; idle timeout = setting `session.timeout-minutes` × 60 s (`AuthController.kt:129-130`). | public plus CSRF | – | 404 `LOCAL_LOGIN_DISABLED`; 429 `RATE_LIMITED` (+`Retry-After`, `details.retryAfterSeconds`; per-user and per-IP limits); 401 `INVALID_CREDENTIALS` (wrong password, unknown user, **disabled** user, or pending account with an unusable hash); 403 `CSRF_INVALID` | – | `LOGIN_SUCCESS`; `LOGIN_FAILURE {reason: rate_limited \| bad_credentials}` |
| `POST /logout` | – | `{status:"logged_out", redirect?}`. `redirect` is the OIDC end-session URL when the session came from OIDC. | authenticated | – | 401 `AUTHENTICATION_REQUIRED`; 403 `CSRF_INVALID` | – | `LOGOUT` |
| `GET /me` | – | `MeResponse` | authenticated | derived server-side | 401 `AUTHENTICATION_REQUIRED` (no session); 401 `ACCOUNT_DISABLED` (session exists but account disabled or gone, `M/identity/ActiveUserFilter.kt:21-24`) | – | – |
| `POST /activation/inspect` | `{token}` | `{username, displayName, purpose: ACTIVATION\|RESET}` | public (30 / 600 s per IP) | – | 410 `LINK_INVALID`; 429 | – | – |
| `POST /activation/complete` | `{token, password}` | `{status:"ok"}` | public (30 / 600 s per IP) | – | 410 `LINK_INVALID`; 400 `WEAK_PASSWORD`; 429 | – | `ACCOUNT_ACTIVATED` or `PASSWORD_RESET_COMPLETED` |

### 2.2 `MeResponse`: exact DTO (`M/identity/AuthController.kt:35-66`)

| Field | Type | Source and rule |
|---|---|---|
| `id` | UUID | session principal |
| `username` | string | session principal (snapshot taken at login) |
| `displayName` | string | session principal snapshot; falls back to `username` |
| `roles` | string[] | **live**: `["USER","ADMIN"]` if `users.system_admin` is true now, else `["USER"]` (`AuthController.kt:147-148`). **Informational only.** |
| `workspaces` | `WorkspaceSummary[]` | see 2.3 |
| `systemAdmin` | boolean | **live** `users.system_admin` |
| `tenantId` | UUID? | primary tenant (2.4); null = no tenant membership |
| `tenantRole` | `"TENANT_ADMIN"` \| `"MEMBER"` \| null | the role in the primary tenant. Informational only. |
| `platformScope` | boolean | = live `systemAdmin` (`M/access/MeTenancy.kt:41`) |
| `businessAccess` | boolean | `systemAdmin && app.tenancy.system-admin-business-access` (default false) |
| `tenants` | `TenantMembershipSummary[]` | `{id, slug, name, status, role}` for each **active** membership whose tenant is not DELETED. SUSPENDED tenants **are listed**, with `status:"SUSPENDED"`. Ordered DEFAULT first, then by membership `created_at`, then slug (`MeTenancy.kt:31-36`). |
| `permissions` | string[] | canonical codes: `platformScope` codes (if systemAdmin) **plus** the primary tenant's role codes, sorted (`MeTenancy.kt:38-41`) |
| `projectScopes` | `ProjectScopeSummary[]` | see 2.3 |

`WorkspaceSummary` is `{ id: UUID, name: string, role: string, tenantId: UUID?, permissions: string[] }`.

`ProjectScopeSummary` is `{ projectId: UUID, workspaceId: UUID, role: string?, permissions: string[] }`.

### 2.3 Live-recompute rules (every call; nothing is cached)

**Roles.** `roles` and `systemAdmin` come from the live `users.system_admin` (`AuthController.kt:147`). They are not taken from the session snapshot. DONE (no test pins the live `roles` after a revoke inside an existing session).

**`workspaces[]` for a non-SYSTEM_ADMIN** (`AuthController.kt:157-166`). The rows are the active `workspace_members` rows, with these rules:
- **Omitted** when the workspace's tenant is `DELETED`.
- **Omitted** when the user's `tenant_members` row for that tenant is inactive. A missing tenant row is treated as active, which matches `AccessService.forWorkspace:92`.
- A workspace of a **SUSPENDED** tenant is **listed with `permissions: []`**, because the API answers 403 `TENANT_SUSPENDED` there.
- Otherwise `permissions` = the canonical codes of `PermissionMatrix.workspaceRoles[role]`.
- Status: DONE (no test for the omission or the empty-permissions rule).

**`workspaces[]` for a SYSTEM_ADMIN** (`AuthController.kt:149-155`). The rows are **every** workspace:
- `role` is the real membership role, or `"ADMIN"` when it is not a member.
- `permissions` = the member role's codes, or the `platformScope` codes for `"ADMIN"` (`MeTenancy.kt:45-49`).
- **Not navigable rows:** a SYSTEM_ADMIN whose membership no longer counts (tenant DELETED / tenant membership removed) is listed with role `ADMIN` and the platform-scope codes only, although the server answers 404 on that workspace's routes; with the legacy flag on, rows keep the member-role codes (flag-gated, off by default).
- **SYSTEM_ADMIN rows follow the same gates as `forWorkspace`:** a workspace the operator belongs to by MEMBERSHIP grants nothing when its tenant is DELETED or the operator's tenant membership was removed (the row shows role `ADMIN` = platform scope), and carries NO permission when the tenant is SUSPENDED; a workspace without membership is listed with role `ADMIN` (platform scope only).

**`projectScopes[]`** (`AuthController.kt:168-184`):
- The SQL pre-filter selects active `project_members` rows of active projects.
- For each row, the server calls `AccessService.forProject(user, workspace, project)`, the same resolver every project API uses.
- If that call throws an `ApiException`, the row is **omitted**: removed workspace membership, removed tenant membership, SUSPENDED or DELETED tenant, inactive project, and so on.
- `permissions` = `canonicalCodesOf(ctx.permissions)`, i.e. workspace role ∪ project role, cut down to {APP_VIEW} for an ARCHIVED project.
- **Each project is its own row. Rows are never unioned, and never merged into `permissions` or `workspaces[].permissions`.**
- DONE: `T/tenancy/ProjectScopedAuthMeTests.kt` (A-K plus the hierarchy flow).

**Role strings are informational only.** This covers `roles`, `workspaces[].role`, `tenantRole`, `tenants[].role` and `projectScopes[].role`. Clients gate only on canonical permission codes.

### 2.4 Primary tenant

The primary tenant is the first row of `tenants[]`: DEFAULT if the user is an active member of it, otherwise the oldest active, non-DELETED membership.

**GAP (C1):** top-level `permissions` carries the role codes of the **primary tenant only**, even when that tenant is SUSPENDED. A Tenant Admin of a *second* tenant gets no `TENANT_*` or `ORG_*` code in `/auth/me`. C5 must not compensate by reading `tenants[].role`. The fix is a per-tenant `permissions` field on `TenantMembershipSummary`, and it needs a C1 change plus a contract entry. Until then, the admin portal can gate the tenant screens **of the primary tenant only** on codes, and must use the server's 403 for any other tenant.

### 2.5 Frontend admission contract

**Studio** (H-C1-04):
- Admit **iff** some `workspaces[].permissions` contains `APP_VIEW` **or** some `projectScopes[].permissions` contains `APP_VIEW`.
- Never branch on a role name.
- Never merge a project scope into the workspace or global set.
- When a project is opened, use **that project's own** permissions: its `projectScopes[]` row, or the project payload. A person with `APP_VIEW` only opens the project read-only.
- An absent `projectScopes` field means an older backend: treat it as "cannot say no", the same rule as an absent `permissions`.

**Admin portal**:
- Platform screens (tenants list, create, status; accounts) are shown iff `platformScope == true`.
- Tenant screens are shown iff `permissions` contains the screen's code (section 9). This applies to the primary tenant only (2.4).
- Workspace member administration is shown iff that workspace's `permissions` contains `MEMBER_MANAGE`.
- Data-source administration is shown iff it contains `DATA_SOURCE_MANAGE`.

**Organization screens** gate on `ORG_STRUCTURE_*`, `EMPLOYEE_*` and `POSITION_GRADE_*`. They **never** gate on `TENANT_MEMBERS` or `platformScope`. A SYSTEM_ADMIN gets 403 on every org route (section 7).

**Every portal** treats the UI decision as a hint. The server re-checks every call.

---

## 3. TENANT routes

Prefix: `/api/v1/admin/tenants` (`M/tenancy/TenantController.kt:39`).

Authorization helpers (`M/access/AccessService.kt`):
- `forPlatform` (`:155-159`): an enabled SYSTEM_ADMIN, else 403 `ADMIN_REQUIRED`.
- `forTenant` (`:127-143`):
  - A **SYSTEM_ADMIN** passes on any existing tenant, DELETED included. It gets `platformScope` (= TENANT_MANAGE + TENANT_MEMBERS). If it is also an ACTIVE member and the tenant is not DELETED, its member role's codes are added. If the legacy flag is on, it gets the TENANT_ADMIN set instead.
  - **Anyone else** needs an active membership and a tenant that is not DELETED. Otherwise the answer is 404 `TENANT_NOT_FOUND`.
  - **`forTenant` does not check SUSPENDED.**

| METHOD PATH | REQUEST | RESPONSE | PERMISSION | TENANT SCOPE | ERRORS | VERSION | AUDIT |
|---|---|---|---|---|---|---|---|
| `GET ` | – | `TenantResponse[]` = `{id, slug, name, status, createdAt}`, all statuses, by `createdAt` | `forPlatform` | platform | 403 `ADMIN_REQUIRED` | – | – |
| `POST ` (201) | `{slug, name, firstAdmin?: {username, displayName, email?}, firstAdminUserId?}` | `TenantCreatedResponse` = `{id, slug, name, status, createdAt, firstAdmin?: ActivationLink}` | `forPlatform` | platform | see notes 1 and 2 below | – | `TENANT_CREATED {slug, name, firstAdmin}`. With `firstAdmin`, also `USER_CREATED`, `TENANT_MEMBER_SET` and `ACTIVATION_LINK_CREATED`. |
| `GET /{tenantId}` | – | `TenantResponse` | `forTenant` (any active member, or SYSTEM_ADMIN) | path tenant | 404 `TENANT_NOT_FOUND` | – | – |
| `PATCH /{tenantId}` (**rename, new**) | `{name}` | `TenantResponse` | `TENANT_MANAGE` via `forTenant` | path tenant. **Slug is immutable.** | 404 `TENANT_NOT_FOUND` (stranger, unknown id, DELETED for everybody - the service refuses a DELETED tenant; the platform operator may rename an ACTIVE or SUSPENDED one); 403 `TENANT_SUSPENDED` (a Tenant Admin of a SUSPENDED company: the company is read-only for its own administrators); 403 `FORBIDDEN` (plain member); 400 `TENANT_NAME_INVALID` (blank after trim, or > 160) | – (no version; last write wins) | `TENANT_UPDATED {old:{name}, new:{name}}`. **Idempotent:** the same name returns the tenant with no write and no audit. |
| `PATCH /{tenantId}/status` | `{status: "ACTIVE"\|"SUSPENDED"\|"DELETED"}` (case-sensitive) | `TenantResponse` | `forPlatform` (**SYSTEM_ADMIN only**) | platform | 403 `ADMIN_REQUIRED`; 400 `TENANT_STATUS_INVALID` (checked before the id is looked up); 404 `TENANT_NOT_FOUND`; 409 `DEFAULT_TENANT_PROTECTED` | – | `TENANT_STATUS_CHANGED {old:{status}, new:{status}}` |
| `POST /{tenantId}/workspaces` (201) | `{name}` | `{id, name, slug: "w-<16 hex>", tenantId}` | `TENANT_MANAGE` | path tenant | 404 `TENANT_NOT_FOUND`; 403 `FORBIDDEN`; 400 `VALIDATION_FAILED` (name 1-160) | – | `WORKSPACE_CREATED` |
| `GET /{tenantId}/members` | – | `TenantMemberView[]` = `{tenantId, userId, role, active, username, displayName, email}`, **active rows only**, by `lower(displayName ?: username)` then username | `TENANT_MEMBERS` | path tenant | 404; 403 | – | – |
| `GET /{tenantId}/member-candidates?q=` | `q` optional; if given, ≥ 2 characters after trim | `TenantMemberCandidate[]` = `{userId, username, displayName, email}`, max 50, by username | `TENANT_MEMBERS` | Only users related to **this** tenant: an active workspace membership in one of its workspaces, or an inactive tenant row. The user must also be enabled, activated, not SYSTEM_ADMIN, and not already an active member (`TenantService.kt:120-163`). | 400 `QUERY_TOO_SHORT`; 404; 403 | – | – |
| `PUT /{tenantId}/members/{userId}` | `{role: "TENANT_ADMIN"\|"MEMBER"}` | `TenantMemberView` = `{tenantId, userId, role, active}`. **`username`, `displayName` and `email` are null in this response** (`TenantService.kt:231`). | `TENANT_MEMBERS` | target must be eligible in this tenant (`TenantService.kt:172-191`) | 404 `TENANT_NOT_FOUND`; 403 `FORBIDDEN`; 400 `TENANT_ROLE_INVALID`; 404 `USER_NOT_FOUND` (unrelated, foreign, pending, SYSTEM_ADMIN not already a member, or unknown); 422 `USER_DISABLED` (related but `users.enabled=false`); **403 `SELF_GRANT_FORBIDDEN`** (actor == target, SYSTEM_ADMIN included); **409 `LAST_TENANT_ADMIN`** (demoting the last active TENANT_ADMIN) | – | `TENANT_MEMBER_SET {old:{role, active}, new:{userId, role}}` |
| `DELETE /{tenantId}/members/{userId}` (204) | – | – | `TENANT_MEMBERS` | path tenant | 404 `TENANT_MEMBER_NOT_FOUND` (not an active member, or foreign); **409 `LAST_TENANT_ADMIN`**; 404; 403 | – | `TENANT_MEMBER_REMOVED {userId, role}` |
| `POST /{tenantId}/users` (201) | `{username, displayName, email?, tenantRole = "MEMBER", workspaceId?, workspaceRole?}` | `ActivationLink` = `{userId, username, displayName, purpose: "ACTIVATION", token, expiresAt}` | `TENANT_MEMBERS` | path tenant. The workspace must belong to the tenant. | see note 4 below | – | `USER_CREATED`, `TENANT_MEMBER_SET`, `ADD_MEMBER` (if a workspace is given), `ACTIVATION_LINK_CREATED` |

Notes on the table:
1. **`POST` create, `firstAdmin` path.** One transaction (`M/tenancy/CompanyBootstrapService.kt:24-31`) creates the tenant, a pending LOCAL account (no password), its TENANT_ADMIN membership and a 24 h activation link. Any failure rolls back everything. Errors: 400 `VALIDATION_FAILED` (both `firstAdmin` and `firstAdminUserId`; blank `firstAdmin.username`; blank `displayName`), 400 `TENANT_SLUG_INVALID`, 400 `TENANT_NAME_INVALID`, 409 `TENANT_SLUG_TAKEN`, and the account errors of note 4. DONE: `T/organization/CompanyBootstrapTests.kt`.
2. **`POST` create, `firstAdminUserId` path.** The id must exist (400 `USER_NOT_FOUND`), and it becomes TENANT_ADMIN. **GAP (C1):** the code checks no enabled or activated state for that user (`TenantController.kt:62`, `TenantService.kt:52`). Without either field, the tenant is created with no admin, which is backward compatible. Slug normalization: trim, lower-case, regex `^[a-z0-9][a-z0-9-]{0,118}[a-z0-9]$`.
3. **Rename.** DONE and tested (`T/tenancy/FinalTenantStatusTests.kt`: ACTIVE paths, SUSPENDED 403 for a Tenant Admin / 200 for the platform operator, DELETED 404 for everybody).
4. **`POST /users` errors** (from `AccountService.createTenantUser`, `M/identity/Accounts.kt:101-168`): 400 `INVALID_USERNAME` (`^[a-z0-9][a-z0-9._-]{2,39}$` after trim and lower-case; no `oidc-` prefix), 400 `VALIDATION_FAILED` (displayName 1-160; `workspaceId` and `workspaceRole` must be sent together), 400 `INVALID_EMAIL`, 409 `EMAIL_TAKEN` (case-insensitive), 404 `TENANT_NOT_FOUND` (tenant DELETED, **SYSTEM_ADMIN included**), 400 `TENANT_ROLE_INVALID`, 400 `INVALID_ROLE`, 404 `WORKSPACE_NOT_FOUND`, 409 `USERNAME_TAKEN`. **No password is accepted. `system_admin` is always FALSE.**

### 3.1 Status transition semantics, as coded (`M/tenancy/TenantService.kt:70-79`)

- Any status → any status is allowed: ACTIVE ↔ SUSPENDED, → DELETED, **DELETED → ACTIVE** and DELETED → SUSPENDED. DELETED is soft: the row stays, and it is **not terminal**.
- Setting the same status again writes `updated_at` and **audits again**. It is not a no-op.
- The DEFAULT tenant (`00000000-0000-0000-0000-000000000001`) can only be set to ACTIVE. Anything else gives 409 `DEFAULT_TENANT_PROTECTED`.
- **No session, membership or token side effect.** Enforcement happens only at request time (3.2).
- Tests: DONE for SYSTEM_ADMIN-only status changes, DEFAULT protection and suspend/reactivate (`T/tenancy/TenantAccessTests.kt:111-133`, `T/tenancy/TenantServiceTests.kt:30`). No test covers DELETED→ACTIVE or the same-status re-audit.

### 3.2 Tenant status semantics

| Route family | ACTIVE | SUSPENDED | DELETED |
|---|---|---|---|
| Workspace and project business routes (`forWorkspace` / `forProject`), ordinary member | allowed by matrix | **403 `TENANT_SUSPENDED`** (`AccessService.kt:93`) | **404 `WORKSPACE_NOT_FOUND`** (`:92`) |
| Same routes, SYSTEM_ADMIN **acting through a membership** | allowed by matrix | **403 `TENANT_SUSPENDED`** (`:94-98`) | **404** (`:96`) |
| Same routes, SYSTEM_ADMIN that is **not a member** | `platformScope` only, so business routes give 403 | same, no gate | same, no gate |
| Organization / employee / position / grade **reads** (`*_VIEW`) | allowed | **allowed** | non-SYSTEM_ADMIN 404 `TENANT_NOT_FOUND`; SYSTEM_ADMIN 403 `FORBIDDEN` (platform scope only) |
| Organization **writes** (every `*_MANAGE` route, including employee create, enable and disable) | allowed | **403 `TENANT_SUSPENDED`** (`OrganizationControllers.kt:24`, `AccessService.kt:149-152`). Checked **after** the permission, so a plain member still sees 403 `FORBIDDEN`. | 404 `TENANT_NOT_FOUND` for every write (`requireTenantWritable`, also with the legacy flag). Unit restore also requires an ACTIVE tenant: `RESTORE_CONFLICT {reason: TENANT_INACTIVE}`. |
| Tenant admin routes (`GET /{t}`, rename, workspaces, members, candidates, users) | allowed | **allowed, unchanged** (`forTenant` does not check SUSPENDED), except **rename**, which a Tenant Admin cannot do in a SUSPENDED company (403 `TENANT_SUSPENDED`; the platform operator can). | non-SYSTEM_ADMIN 404. SYSTEM_ADMIN allowed, except `POST /users`, which gives 404 `TENANT_NOT_FOUND`. |
| `/auth/me` | normal | tenant listed in `tenants[]`. Its workspaces are listed with `permissions: []` (non-SYSTEM_ADMIN). Top-level `permissions` still carries the tenant role codes if it is the primary tenant (2.4). | tenant and its workspaces omitted (non-SYSTEM_ADMIN) |
| Public site (`PUBLIC_SITE`) | served | refused (TenantGate) | refused |

Status of the SUSPENDED rules:
- **DONE** for workspace routes (`T/tenancy/TenantAccessTests.kt:38-45`).
- **DONE and tested** for organization writes in a SUSPENDED tenant (`T/tenancy/FinalTenantStatusTests.kt` test 6a: 12 write routes answer 403 `TENANT_SUSPENDED`, reads 200).
- **GAP (C1, policy unchanged):** in a SUSPENDED tenant, the tenant-admin member and provisioning routes stay open.

SYSTEM_ADMIN member rules:
- A SYSTEM_ADMIN can never grant itself anything (403 `SELF_GRANT_FORBIDDEN`, `TenantService.kt:203`).
- It is not a member candidate and cannot be added through `PUT members` unless it already is an active member (`TenantService.kt:177`).
- When it *is* a member, it holds exactly its member role's codes on top of `platformScope`, but never in a DELETED tenant (`AccessService.kt:136`). DONE: `T/organization/OrganizationAuthorizationTests.kt:148`.

---

## 4. ACCOUNT

| METHOD PATH | REQUEST | RESPONSE | PERMISSION | ERRORS | AUDIT |
|---|---|---|---|---|---|
| `POST /api/v1/admin/tenants/{t}/users` | section 3 | `ActivationLink` | `TENANT_MEMBERS` on `{t}` | section 3 | section 3 |
| `POST /api/v1/admin/tenants/{t}/employees` | org contract §4 | `{employee, activation}` | `EMPLOYEE_MANAGE` + `TENANT_MEMBERS` | org contract §4 | `EMPLOYEE_CREATED` + the provisioning events |
| `POST /api/v1/admin/users` (legacy, platform) | `{username, displayName, email?, workspaceId, role: WORKSPACE_ADMIN\|EDITOR\|PUBLISHER\|VIEWER}` | 201 `ActivationLink` | SYSTEM_ADMIN (`AdminGuard`) | 403 `ADMIN_REQUIRED`; 400 bean validation / `INVALID_USERNAME` / `INVALID_EMAIL`; 409 `EMAIL_TAKEN` / `USERNAME_TAKEN`; 404 `WORKSPACE_NOT_FOUND` | `USER_CREATED`, `ACTIVATION_LINK_CREATED` |
| `POST /api/v1/admin/users/{id}/activation-link` | – | `ActivationLink`. `purpose` is `ACTIVATION` if the account was never activated, else `RESET`. | SYSTEM_ADMIN | 404 `USER_NOT_FOUND`; 409 `NOT_LOCAL_ACCOUNT`; 409 `ACCOUNT_DISABLED` | `ACTIVATION_LINK_CREATED` / `PASSWORD_RESET_LINK_CREATED` |
| `PATCH /api/v1/admin/users/{id}/status` | `{enabled: boolean}` | `AdminUserRow` | **SYSTEM_ADMIN only** | 403 `ADMIN_REQUIRED`; 400 `VALIDATION_FAILED`; 409 `CANNOT_DISABLE_SELF`; 404 `USER_NOT_FOUND`; 409 `LAST_SYSTEM_ADMIN` | `USER_DISABLED` / `USER_ENABLED {sessionsRevoked}`, written only when the value changes |
| `POST /api/v1/admin/users/{id}/revoke-sessions` | – | `{revoked: n}` | SYSTEM_ADMIN | 403; 404 `USER_NOT_FOUND` | `REVOKE_SESSIONS {sessionsRevoked}` |
| `POST /api/v1/admin/users/{id}/system-admin` | `{grant: boolean, confirm: true}` | `{systemAdmin}` | SYSTEM_ADMIN | 428 `CONFIRMATION_REQUIRED`; 409 `CANNOT_CHANGE_SELF`; 404 `USER_NOT_FOUND`; 409 `NOT_ACTIVE` (grant needs an enabled, activated account); 409 `LAST_SYSTEM_ADMIN` | `SYSTEM_ADMIN_GRANTED` / `SYSTEM_ADMIN_REVOKED` |
| `GET /api/v1/admin/users?page&size&q&status=all\|active\|disabled\|admin` | – | `PageDto<AdminUserRow>` = `{items, total, page, size}` (size 1-100) | SYSTEM_ADMIN | 403 | – |
| `GET /api/v1/admin/users/{id}` | – | `AdminUserDetail` | SYSTEM_ADMIN | 403; 404 | – |

Where these are defined:
- Account service and the legacy create, link and system-admin routes: `M/identity/Accounts.kt:217-243`.
- User admin routes: `M/admin/AdminUserController.kt` (C0-gated).
- A Tenant Admin can **never** grant SYSTEM_ADMIN and **never** reach `/api/v1/admin/users/**`: `AdminGuard` reads `system_admin AND enabled` live.

There is no dedicated "invite" e-mail. Invitation means the admin receives the one-time `ActivationLink` and delivers it.

**Token policy** (`Accounts.kt:62-73,182-203`):
- Each token is 32 random bytes, base64url-encoded. **Only its SHA-256 is stored** (`account_tokens`).
- TTL is 24 h. A token is single use: the `used_at` UPDATE is atomic.
- Issuing a new link deletes every unused link of that user.
- Tokens are validated against `^[A-Za-z0-9_-]{30,100}$`.
- Lookup requires `users.enabled AND auth_source='LOCAL'`.
- Password policy: 8-200 characters, letters **and** digits, at least 4 distinct characters, and must not contain the username.
- Completing the flow sets the hash and `activated_at`, and **deletes every session of the username** (`Accounts.kt:201`).
- The pending-token gap is described in section 1.

### 4.1 Session behaviour

Sessions are stored in Spring Session Redis (indexed by principal name = username). The default `server.servlet.session.timeout` is 20 m (`application.yml:28-34`), and login overrides it with `session.timeout-minutes`.

| Event | Existing sessions | Next request with the old cookie | Evidence |
|---|---|---|---|
| SYSTEM_ADMIN disables via `PATCH …/status` | deleted by username, inside the transaction **before commit** | **401 `AUTHENTICATION_REQUIRED`**: the session is gone, so the caller is anonymous (`SecurityConfiguration.kt:188-189`). It is **not** `ACCOUNT_DISABLED`. Login gives 401 `INVALID_CREDENTIALS`. | `AdminUserController.kt:89-93,106-110`; `T/admin/AdminApiTests.kt:62-65` (asserts 401 only) |
| Account disabled in the DB only (no session revocation) | kept | **401 `ACCOUNT_DISABLED`**; the session is invalidated | `ActiveUserFilter.kt:21-24`; `T/identity/AuthSecurityTests.kt:63-70` |
| SCIM deprovision (`active=false`) | deleted by username | 401 `AUTHENTICATION_REQUIRED` | `Scim.kt:162-167` |
| SYSTEM_ADMIN re-enables | nothing is resurrected | old cookie: 401. New login: 200. | `AdminUserController.kt:89-93`; `AdminApiTests.kt:68-69`. `T/tenancy/UserProvisioningHierarchyTests.kt:278` accepts 200 or 401 for the old session, so the result is not pinned. |
| `POST …/revoke-sessions` | deleted | 401 `AUTHENTICATION_REQUIRED` | `AdminUserController.kt:97-104`; `AdminApiTests.kt:73` |
| Activation or password reset completed | deleted | 401 `AUTHENTICATION_REQUIRED` | `Accounts.kt:201`. The test name `AccountActivationTests.kt:32` says "reset signs out", but nothing asserts it. |
| SYSTEM_ADMIN revoked | kept | admin routes: 403 `ADMIN_REQUIRED`; `forPlatform` routes: 403; `/auth/me` `systemAdmin=false`, `roles=["USER"]` | `AdminSupport.kt:17-21`; `AuthController.kt:147`; `AdminApiTests.kt:44` |
| Tenant membership deactivated (`DELETE members` / employee `disable`) | kept | tenant routes: 404 `TENANT_NOT_FOUND`; the tenant's workspaces: 404 `WORKSPACE_NOT_FOUND`; `/auth/me` omits them | `AccessService.kt:92,141`; `T/tenancy/TenantAccessTests.kt:38` |
| Tenant role demoted (TENANT_ADMIN → MEMBER) | kept | 403 `FORBIDDEN` "Missing permission: …" | `TenantAccess.require` (`AccessService.kt:58`); `T/tenancy/TenantAdminViaRoleTests.kt` (service level only) |
| Tenant SUSPENDED | kept | section 3.2 | `TenantAccessTests.kt:45` |
| Tenant DELETED | kept | 404 for non-SYSTEM_ADMIN | `AccessService.kt:92,141` |
| Project membership removed | kept | project routes: 404 `PROJECT_NOT_FOUND`; the `projectScopes` row is omitted | `T/member/MemberApiTests.kt:108`; `ProjectScopedAuthMeTests.kt:198` |
| Logout | invalidated | 401 `AUTHENTICATION_REQUIRED` | `AuthSecurityTests.kt:56` |

---

## 5. MEMBERSHIP

**Tenant roles** (`M/tenancy/Tenant.kt:22`): `TENANT_ADMIN` and `MEMBER`. `tenant_members` is the only source of a tenant role.

**Lifecycle of `tenant_members.active`.**
- A row is created active by:
  - tenant create with `firstAdminUserId`, or the `firstAdmin` bootstrap;
  - `POST /{t}/users` and `POST /{t}/employees`;
  - `PUT members`;
  - the V26 trigger. When a workspace membership is inserted and no tenant row exists, the trigger adds a `MEMBER` row. It never touches an existing row, so an inactive row stays inactive (`db/migration/V26__tenant_foundation.sql:137-142`).
- `active=false` is set **only** by `TenantService.removeMember` (the `DELETE members` route or employee `disable`).
- `active=true` is set again **only** by `TenantService.setMember` (the `PUT members` route, or employee `enable` with role MEMBER).
- **Rows are never deleted.**
- Deactivating a tenant membership leaves `workspace_members` untouched. `forWorkspace` refuses access because of the tenant row.

**Protections.**

| Rule | Where | Status |
|---|---|---|
| Last active TENANT_ADMIN can be neither demoted nor removed: 409 `LAST_TENANT_ADMIN` | `TenantService.kt:207,219,225-228` | DONE (`TenantServiceTests.kt:39`, `UserProvisioningHierarchyTests.kt:225`). **GAP (C1):** the count is not row-locked, so two concurrent demotions can both pass. Untested. |
| No self-grant or self role change for anyone, SYSTEM_ADMIN included: 403 `SELF_GRANT_FORBIDDEN` | `TenantService.kt:203` | DONE (`T/tenancy/PrivilegeEscalationTests.kt`, `UserProvisioningHierarchyTests.kt:246`) |
| Self-disable as an employee: 403 `SELF_GRANT_FORBIDDEN` | `EmployeeDirectoryService.kt:133` | DONE |
| `DELETE members/{self}` | **no self check**. A Tenant Admin may remove itself while another TENANT_ADMIN exists. | as coded |
| Re-adding a platform-disabled account: 422 `USER_DISABLED` | `TenantController.kt:146`, `EmployeeDirectoryService.kt:136` | DONE (`UserProvisioningHierarchyTests.kt:265`) |
| Only users related to this tenant can be added; others get 404 `USER_NOT_FOUND` | `TenantService.kt:172-191` | DONE (`T/tenancy/TenantMemberDirectoryTests.kt`, `UserProvisioningHierarchyTests.kt:148`) |

**Workspace and project membership** (`M/member/MemberController.kt`, unchanged on this branch):
- Workspace members are managed with `MEMBER_MANAGE`, held only by WORKSPACE_ADMIN. 409 `LAST_ADMIN` protects the last WORKSPACE_ADMIN.
- Project members are managed with `APP_SHARE` (storage name `PROJECT_MEMBERS`). 409 `LAST_OWNER` protects the last OWNER.
- 403 `SELF_GRANT_FORBIDDEN`, 404 `USER_NOT_FOUND` (not in this tenant), 422 `USER_DISABLED`, 409 `ALREADY_MEMBER`, 404 `MEMBER_NOT_FOUND`.

---

## 6. ORGANIZATION / POSITION / GRADE

Prefix: `/api/v1/admin/tenants/{tenantId}`.

**Authorization order on every route** (`OrganizationControllers.kt:23-24`):
1. 401 if there is no session.
2. `forTenant`: a stranger or unknown tenant gets a safe 404 `TENANT_NOT_FOUND`.
3. `require(capability)`: 403 `FORBIDDEN` if missing.
4. For a `*_MANAGE` capability, `requireTenantWritable`: 403 `TENANT_SUSPENDED`.
5. The service runs. Until C3 persistence exists, the route answers 501 `ORG_PERSISTENCE_NOT_AVAILABLE`, always after steps 1-4.

Details for every route (DTOs, bodies, full error lists, order of checks): see the [org contract](organization-employee-contract.md), sections §2-§5.

### 6.1 Route summary (authoritative)

| METHOD PATH | PERMISSION | Key errors (beyond 401 / 404 `TENANT_NOT_FOUND` / 403 `FORBIDDEN` / 403 `TENANT_SUSPENDED` on writes / 501) | ETag |
|---|---|---|---|
| `GET /organization-unit-types?includeInactive=true` | ORG_STRUCTURE_VIEW | – | – |
| `GET /organization-unit-types/{typeId}` | ORG_STRUCTURE_VIEW | 404 `ORG_UNIT_TYPE_NOT_FOUND` | yes |
| `POST /organization-unit-types` (201) | ORG_STRUCTURE_MANAGE | 400 `VALIDATION_FAILED` / `INVALID_CODE`; 404 `ORG_UNIT_TYPE_NOT_FOUND` (a rule id); 409 `ORG_UNIT_TYPE_CODE_TAKEN` | yes |
| `PATCH /organization-unit-types/{typeId}` | ORG_STRUCTURE_MANAGE | 404; 409 `VERSION_CONFLICT` | yes |
| `POST /organization-unit-types/{typeId}/disable` and `/enable` | ORG_STRUCTURE_MANAGE | 404; 409 `VERSION_CONFLICT` | yes |
| `GET /organization-units?format=tree\|flat&includeArchived=false` | ORG_STRUCTURE_VIEW | 400 `VALIDATION_FAILED` (bad `format`) | – |
| `GET /organization-units/{unitId}` | ORG_STRUCTURE_VIEW | 404 `ORG_UNIT_NOT_FOUND` | yes (`unit.version`) |
| `POST /organization-units` (201) | ORG_STRUCTURE_MANAGE | 400 `VALIDATION_FAILED` / `INVALID_CODE`; 404 `ORG_UNIT_TYPE_NOT_FOUND` / `ORG_UNIT_NOT_FOUND`; 409 `ORG_UNIT_TYPE_DISABLED` / `ORG_UNIT_ARCHIVED` / `ORG_TYPE_RULE_VIOLATION{reason}` / `ORG_UNIT_CODE_TAKEN` | yes |
| `PATCH /organization-units/{unitId}` | ORG_STRUCTURE_MANAGE | 404; 409 `ORG_UNIT_ARCHIVED` / `ORG_UNIT_CODE_TAKEN` / `VERSION_CONFLICT`; 400 `INVALID_CODE` | yes |
| `POST /organization-units/{unitId}/move` | ORG_STRUCTURE_MANAGE | 400 (`newParentId` key absent or not a UUID/null; `expectedVersion` missing); 404; 409 `ORG_UNIT_ARCHIVED` / `ORG_CYCLE` / `ORG_TYPE_RULE_VIOLATION` / `ORG_UNIT_CODE_TAKEN` / `VERSION_CONFLICT` | yes |
| `POST /organization-units/{unitId}/archive` | ORG_STRUCTURE_MANAGE | 404; 409 `ORG_UNIT_ARCHIVED` / `ORG_UNIT_HAS_CHILDREN{activeChildCount}` / `ORG_UNIT_HAS_MEMBERS{activeMemberCount}` / `VERSION_CONFLICT` | yes |
| `POST /organization-units/{unitId}/restore` | ORG_STRUCTURE_MANAGE | 404; 409 `RESTORE_CONFLICT{reason}` / `VERSION_CONFLICT` | yes |
| `GET /positions?includeInactive=true`, `GET /grades?includeInactive=true` | POSITION_GRADE_VIEW | – | – |
| `GET /positions/{id}`, `GET /grades/{id}` | POSITION_GRADE_VIEW | 404 `POSITION_NOT_FOUND` / `GRADE_NOT_FOUND` | yes |
| `POST /positions`, `POST /grades` (201) | POSITION_GRADE_MANAGE | 400 `VALIDATION_FAILED` / `INVALID_CODE`; 409 `POSITION_CODE_TAKEN` / `GRADE_CODE_TAKEN` | yes |
| `PATCH /positions/{id}`, `PATCH /grades/{id}` | POSITION_GRADE_MANAGE | 404; 409 `VERSION_CONFLICT` | yes |
| `POST /positions/{id}/disable` and `/enable`, `POST /grades/{id}/disable` and `/enable` | POSITION_GRADE_MANAGE | 404; 409 `VERSION_CONFLICT` | yes |
| `GET /employees?q&organizationUnitId&includeDescendants&positionId&gradeId&active&userId&page&size&sort&dir` | EMPLOYEE_VIEW | 400 `VALIDATION_FAILED` / `QUERY_TOO_SHORT`; 404 `ORG_UNIT_NOT_FOUND` / `POSITION_NOT_FOUND` / `GRADE_NOT_FOUND` | – |
| `GET /employees/{userId}` | EMPLOYEE_VIEW | 404 `EMPLOYEE_NOT_FOUND` | **none** |
| `POST /employees` (201) | EMPLOYEE_MANAGE **+ TENANT_MEMBERS** | org contract §4, plus the provisioning errors of section 3 | none |
| `POST /employees/{userId}/disable` | EMPLOYEE_MANAGE **+ TENANT_MEMBERS** | 404 `EMPLOYEE_NOT_FOUND`; 403 `SELF_GRANT_FORBIDDEN`; 409 `LAST_TENANT_ADMIN` | none |
| `POST /employees/{userId}/enable` | EMPLOYEE_MANAGE **+ TENANT_MEMBERS** | 404; 422 `USER_DISABLED` | none |
| `GET /employees/{userId}/organization-memberships?includeInactive=false` | EMPLOYEE_VIEW | 404 `EMPLOYEE_NOT_FOUND` | – |
| `POST /employees/{userId}/organization-memberships` (201) | EMPLOYEE_MANAGE | 409 `EMPLOYEE_INACTIVE` / `ORG_UNIT_ARCHIVED` / `ORG_MEMBERSHIP_EXISTS`; 404 `ORG_UNIT_NOT_FOUND` | yes |
| `PATCH /employees/{userId}/organization-memberships/{membershipId}` | EMPLOYEE_MANAGE | 404 `ORG_MEMBERSHIP_NOT_FOUND`; 400 (`primary=false`); 409 `EMPLOYEE_INACTIVE` / `VERSION_CONFLICT` | yes |
| `DELETE /employees/{userId}/organization-memberships/{membershipId}?expectedVersion=n` | EMPLOYEE_MANAGE | 404; **409 `EMPLOYEE_ORG_HAS_POSITIONS{activePositionCount}`**; 409 `VERSION_CONFLICT` | yes |
| `GET /employees/{userId}/positions?includeInactive=false` | EMPLOYEE_VIEW | 404 `EMPLOYEE_NOT_FOUND` | – |
| `POST /employees/{userId}/positions` (201) | EMPLOYEE_MANAGE | 400 (`membershipId` / `positionId` missing); 404 `ORG_MEMBERSHIP_NOT_FOUND` / `POSITION_NOT_FOUND` / `GRADE_NOT_FOUND`; 409 `ORG_MEMBERSHIP_INACTIVE` / `POSITION_DISABLED` / `GRADE_DISABLED` / `POSITION_ASSIGNMENT_EXISTS` / `EMPLOYEE_INACTIVE` | yes |
| `PATCH /employees/{userId}/positions/{employeePositionId}` | EMPLOYEE_MANAGE | 404 `POSITION_ASSIGNMENT_NOT_FOUND`; 400; 409 `VERSION_CONFLICT` / `EMPLOYEE_INACTIVE` | yes |
| `DELETE /employees/{userId}/positions/{employeePositionId}?expectedVersion=n` | EMPLOYEE_MANAGE | 404; 409 `VERSION_CONFLICT` | yes |

### 6.2 Frozen semantics

Source: D-C0-43, as reconciled (CF-1..CF-4). Each item was verified in code.

1. **No EmployeeProfile.** An employee is `tenant_members JOIN users`, and `EmployeeDto.active` = `tenant_members.active`. There is no `PATCH /employees/{id}`, and `POST /employees` creates a **new** account only (`OrganizationContract.kt:60-72`, `EmployeeDirectoryService.kt:13-16`).
2. **Unit code is canonical.** It is required, trimmed, validated against `^[A-Za-z0-9][A-Za-z0-9._-]{0,59}$` (otherwise 400 `INVALID_CODE`), then `uppercase(Locale.ROOT)` (`OrganizationServices.kt:16,36-37`). It is unique **among non-archived siblings**: same tenant and parent, with all roots treated as siblings. Archiving frees the code (`OrganizationRepositories.kt:19-22`). Type codes are lower-case (`^[a-z0-9][a-z0-9_-]{0,39}$`). Position and grade codes are unique per tenant, case-insensitively.
3. **An EmployeePosition is held within an active membership.** `membershipId` is required, and `organizationUnitId` is derived from it (`EmployeeDirectoryService.kt:216-218,241`). Active uniqueness is **(membershipId, positionId)**; the grade is an attribute, not part of the identity (`OrganizationRepositories.kt:22,156-159`).
4. **409 `EMPLOYEE_ORG_HAS_POSITIONS`.** A membership that still holds active positions cannot end. Nothing cascades (`EmployeeDirectoryService.kt:196-199`).
5. **Soft archive and restore.** A unit has `active` and `archivedAt`. There is no hard delete. Archive refuses while active children or members exist. Restore re-validates tenant, type, parent, rules and code (`OrganizationServices.kt:225-269`). Types, positions and grades use enable / disable.
6. **The structural lock is used only for move.** The only `repos.lock.acquire` call is the first step of `move` (`OrganizationServices.kt:203`).
7. **Atomic move seam.** `OrganizationUnitRepository.move` is one versioned CAS that changes only the moved row's parent. The store re-checks for cycles and sibling codes (`OrganizationRepositories.kt:84-89`). The service validates `maxDepth` for every descendant (`OrganizationServices.kt:213`).
8. **One active primary membership per (tenant, user)**, and one active primary position. `setPrimary` is atomic. A lost race answers 409 `VERSION_CONFLICT` (`EmployeeDirectoryService.kt:224-229`).
9. **`description` is nullable** for positions and grades (`OrganizationContract.kt:95-96`).
10. **Organization data never grants a permission.** No code in `access/**` or `identity/**` reads organization data. Relation type, position and grade are business data (`OrganizationContract.kt:86`, `Permission.kt:88-89`).

Conflicts with the org contract:
- The org contract §11 bullet "Suspended company: … a Tenant Admin of a suspended company keeps the organization / employee writes" is **superseded**. The code answers 403 `TENANT_SUSPENDED` on every org write (section 3.2).
- The org contract §10 says "the 10 tests". The kit has **13** `@Test` methods (`T/organization/OrganizationRepositoryContractKit.kt`), matching its table.
- C1 must correct both in that file in a follow-up.

---

## 7. CANONICAL PERMISSION MATRIX

Source: `M/access/Permission.kt`.

**Canonical vocabulary.** `PermissionCodes.CANONICAL` has 21 codes (`Permission.kt:47-52`):
- `APP_VIEW APP_USE APP_EDIT APP_PUBLISH APP_SHARE`
- `DATA_SOURCE_VIEW DATA_SOURCE_MANAGE QUERY_EXECUTE DATA_MUTATE ACTION_EXECUTE WORKFLOW_EXECUTE WORKFLOW_MANAGE`
- `TENANT_MANAGE TENANT_MEMBERS MEMBER_MANAGE`
- `ORG_STRUCTURE_VIEW ORG_STRUCTURE_MANAGE EMPLOYEE_VIEW EMPLOYEE_MANAGE POSITION_GRADE_VIEW POSITION_GRADE_MANAGE`

**Storage aliases.** `PROJECT_READ`=`APP_VIEW`, `PROJECT_EDIT`=`APP_EDIT`, `PROJECT_PUBLISH`=`APP_PUBLISH`, `PROJECT_MEMBERS`=`APP_SHARE` (temporary).

**Internal codes, never exposed:** `PROJECT_SETTINGS`, `PROJECT_DELETE`, `PROJECT_CREATE`, `AUDIT_READ`, `REGISTRY_WRITE`.

**There is no `ORG_MANAGE`.**

| Scope / role | Canonical codes (as `/auth/me` shows them) | Internal extras |
|---|---|---|
| **Project** VIEWER | APP_VIEW, APP_USE | – |
| **Project** EDITOR | APP_VIEW, APP_EDIT, APP_USE, DATA_SOURCE_VIEW, QUERY_EXECUTE, ACTION_EXECUTE | PROJECT_SETTINGS |
| **Project** PUBLISHER | APP_VIEW, APP_PUBLISH, APP_USE | – |
| **Project** OWNER | APP_VIEW, APP_EDIT, APP_PUBLISH, APP_SHARE, APP_USE, DATA_SOURCE_VIEW, QUERY_EXECUTE, ACTION_EXECUTE | PROJECT_SETTINGS, PROJECT_DELETE |
| **Workspace** WORKSPACE_ADMIN | everything OWNER has + DATA_SOURCE_MANAGE, DATA_MUTATE, WORKFLOW_EXECUTE, WORKFLOW_MANAGE, MEMBER_MANAGE | PROJECT_SETTINGS, PROJECT_DELETE, PROJECT_CREATE, AUDIT_READ |
| **Workspace** EDITOR | – | PROJECT_CREATE |
| **Workspace** PUBLISHER, VIEWER | – | – |
| **Tenant** TENANT_ADMIN | TENANT_MANAGE, TENANT_MEMBERS, ORG_STRUCTURE_VIEW, ORG_STRUCTURE_MANAGE, EMPLOYEE_VIEW, EMPLOYEE_MANAGE, POSITION_GRADE_VIEW, POSITION_GRADE_MANAGE | – |
| **Tenant** MEMBER | – | – |
| **Platform** SYSTEM_ADMIN (default) | TENANT_MANAGE, TENANT_MEMBERS (`platformScope`) | – |
| SYSTEM_ADMIN with the legacy flag `app.tenancy.system-admin-business-access=true` (off by default) | every constant in workspaces; the TENANT_ADMIN set on every tenant | all |

How the scopes combine:
- **Effective project permissions** = workspace role set ∪ project role set (`AccessService.kt:117`). For an ARCHIVED project, this is cut down to {APP_VIEW (+AUDIT_READ)} (`:119`).
- **Tenant permissions** come only from `tenant_members` (plus `platformScope` for a SYSTEM_ADMIN). They are never combined with workspace or project sets.

**Independence rules** (frozen):
1. `APP_VIEW` does not imply `APP_USE`, `APP_EDIT` or `APP_PUBLISH`. `APP_EDIT` does not imply `APP_PUBLISH`. Every check requires its exact constant; no code path derives one from another.
2. Organization membership, relation type (MEMBER / MANAGER / HEAD / …), position and grade grant **nothing**. `access/**` never reads organization data. Tested by `OrganizationAuthorizationTests.kt:71`, but only with a WORKSPACE_ADMIN. A plain-employee test is missing (C1).
3. **Only `PermissionMatrix` grants.** Role strings are inputs to the matrix lookup, never decisions.
4. **The tenant boundary always wins.** The tenant is derived from the resource (`TenantResolver`), never from request input, and a foreign id is a safe 404 (section 8).
5. WORKSPACE_ADMIN holds no `TENANT_*` or org code. SYSTEM_ADMIN holds no org code and no business code by default. A Tenant Admin holds no workspace or project code by being Tenant Admin (`T/tenancy/TenantAdminViaRoleTests.kt`, `OrganizationAuthorizationTests.kt:71`, `UserProvisioningHierarchyTests.kt:353`).

**Where the structure couples permissions.** These are stated honestly. They are not grants.
- `forProject` answers **404 `PROJECT_NOT_FOUND` unless `APP_VIEW`** (`PROJECT_READ`) is held (`AccessService.kt:118`). APP_USE without APP_VIEW therefore cannot be expressed.
- **Every project role bundles `APP_USE`** (`Permission.kt:108-111`). APP_VIEW without APP_USE occurs only for ARCHIVED projects.
- TEST-mode runtime calls require `APP_EDIT` in place of, or in addition to, `APP_USE`: `wiring/AppRuntimeDataController.kt` (C3) and `access/adapters/AccessPort.kt`.

**Role-name checks that remain inside `AccessService`.** These are internal visibility, not controller authorization.
- `AccessContext.seesAllProjects = systemAdminBypass || workspaceRole == "WORKSPACE_ADMIN"` (`AccessService.kt:39`). It is two disjuncts: the legacy-bypass flag and the WORKSPACE_ADMIN role name. It is used only to widen the project **list** (`M/project/ProjectController.kt:94`). Every project call still goes through `forProject`.
- The legacy bypass in `forTenant` selects the TENANT_ADMIN set by name (`AccessService.kt:135`).
- `MeTenancy.workspacePermissions("ADMIN")` uses `"ADMIN"` as a display placeholder for a non-member SYSTEM_ADMIN (`MeTenancy.kt:46`).

---

## 8. DENIAL SEMANTICS

| Status | When | Codes |
|---|---|---|
| **401** | no session | `AUTHENTICATION_REQUIRED` (`SecurityConfiguration.kt:188-189`) |
| 401 | the session's account is disabled or deleted | `ACCOUNT_DISABLED` (`ActiveUserFilter.kt:24`, `AccessService.kt:78`) |
| 401 | login failure | `INVALID_CREDENTIALS` |
| **403** | same scope, permission missing | `FORBIDDEN` ("Missing permission: X") |
| 403 | platform route, not an enabled SYSTEM_ADMIN | `ADMIN_REQUIRED` |
| 403 | SUSPENDED tenant: workspace business routes, org writes | `TENANT_SUSPENDED` |
| 403 | acting on oneself | `SELF_GRANT_FORBIDDEN` |
| 403 | CSRF | `CSRF_INVALID` |
| **404** | an unknown id, **or an id of another tenant**, or a workspace or tenant the caller is not in. These give exactly the same answer: **safe 404**. | `TENANT_NOT_FOUND`, `WORKSPACE_NOT_FOUND`, `PROJECT_NOT_FOUND`, `USER_NOT_FOUND`, `TENANT_MEMBER_NOT_FOUND`, `MEMBER_NOT_FOUND`, `EMPLOYEE_NOT_FOUND`, `ORG_UNIT_NOT_FOUND`, `ORG_UNIT_TYPE_NOT_FOUND`, `ORG_MEMBERSHIP_NOT_FOUND`, `POSITION_NOT_FOUND`, `GRADE_NOT_FOUND`, `POSITION_ASSIGNMENT_NOT_FOUND`, `LOCAL_LOGIN_DISABLED` |

Order of checks:
1. Authentication, then account active.
2. Scope resolution (404 for a stranger).
3. Permission (403).
4. Tenant writability (403 `TENANT_SUSPENDED`).
5. Validation (400).
6. Domain (404 for a child id / 409 / 422).
7. Store availability (501).

A stranger therefore never learns whether a tenant exists, nor its status.

**Exceptions.** `PATCH /tenants/{id}/status`, `GET /tenants` and `POST /tenants` answer 403 `ADMIN_REQUIRED` before any lookup, for any id. A plain MEMBER of a tenant receives 403, not 404, because it is of the tenant.

**Error code catalogue for these areas:**
- **400:** `VALIDATION_FAILED`, `TENANT_SLUG_INVALID`, `TENANT_NAME_INVALID`, `TENANT_STATUS_INVALID`, `TENANT_ROLE_INVALID`, `INVALID_ROLE`, `INVALID_USERNAME`, `INVALID_EMAIL`, `WEAK_PASSWORD`, `QUERY_TOO_SHORT`, `INVALID_CODE`, `USER_NOT_FOUND` (tenant create only)
- **401:** `AUTHENTICATION_REQUIRED`, `ACCOUNT_DISABLED`, `INVALID_CREDENTIALS`
- **403:** `FORBIDDEN`, `ADMIN_REQUIRED`, `TENANT_SUSPENDED`, `SELF_GRANT_FORBIDDEN`, `CSRF_INVALID`
- **404:** the list above
- **409:** `TENANT_SLUG_TAKEN`, `DEFAULT_TENANT_PROTECTED`, `LAST_TENANT_ADMIN`, `LAST_ADMIN`, `LAST_OWNER`, `ALREADY_MEMBER`, `USERNAME_TAKEN`, `EMAIL_TAKEN`, `CANNOT_DISABLE_SELF`, `CANNOT_CHANGE_SELF`, `LAST_SYSTEM_ADMIN`, `NOT_ACTIVE`, `NOT_LOCAL_ACCOUNT`, `ACCOUNT_DISABLED` (activation-link)
- **409, organization** (org contract §9): `VERSION_CONFLICT{currentVersion}`, `ORG_CYCLE`, `ORG_TYPE_RULE_VIOLATION{reason}`, `ORG_UNIT_HAS_CHILDREN`, `ORG_UNIT_HAS_MEMBERS`, `ORG_UNIT_ARCHIVED`, `RESTORE_CONFLICT{reason}`, `ORG_UNIT_CODE_TAKEN`, `ORG_UNIT_TYPE_CODE_TAKEN`, `ORG_UNIT_TYPE_DISABLED`, `POSITION_CODE_TAKEN`, `GRADE_CODE_TAKEN`, `POSITION_DISABLED`, `GRADE_DISABLED`, `ORG_MEMBERSHIP_EXISTS`, `ORG_MEMBERSHIP_INACTIVE`, `EMPLOYEE_ORG_HAS_POSITIONS`, `POSITION_ASSIGNMENT_EXISTS`, `EMPLOYEE_INACTIVE`
- **410:** `LINK_INVALID`
- **422:** `USER_DISABLED`
- **428:** `CONFIRMATION_REQUIRED`
- **429:** `RATE_LIMITED`
- **501:** `ORG_PERSISTENCE_NOT_AVAILABLE`

The error body is `{code, message, details?}`.

---

## 9. C5 INPUT (frontend-ready)

General rules:
- Gate every screen on the **canonical code** named here, as listed by `/auth/me`. Never use a role string.
- The server re-checks every call. Treat 403 as "hide or disable", and 404 as "gone or not yours".
- Show `NOT_READY` on 501 `ORG_PERSISTENCE_NOT_AVAILABLE`.

| Screen | Routes | Gate | Pagination / version |
|---|---|---|---|
| Session and portal routing | `GET /api/v1/auth/me`, `POST /auth/login`, `POST /auth/logout`, `GET /auth/csrf` | section 2.5 | – |
| Studio admission | `/auth/me` `workspaces[].permissions` / `projectScopes[].permissions` | `APP_VIEW` in any row | – |
| Tenant list / create / status | `GET` / `POST /api/v1/admin/tenants`, `PATCH /{t}/status` | `platformScope == true` | not paginated; no version |
| Tenant rename | `PATCH /api/v1/admin/tenants/{t} {name}` | `TENANT_MANAGE` | no version, no ETag; idempotent |
| Tenant membership | `GET /{t}/members`, `GET /{t}/member-candidates?q`, `PUT /{t}/members/{u} {role}`, `DELETE /{t}/members/{u}` | `TENANT_MEMBERS` | not paginated (candidates max 50); no version |
| Tenant user provisioning | `POST /{t}/users` | `TENANT_MEMBERS` | – |
| Account lifecycle (global) | `GET /api/v1/admin/users`, `GET /{id}`, `PATCH /{id}/status {enabled}`, `POST /{id}/revoke-sessions`, `POST /{id}/activation-link`, `POST /{id}/system-admin {grant, confirm:true}` | `platformScope == true` | `PageDto {items,total,page,size}`; `size` is clamped to 1-100 (default 25); no version |
| Employee directory | `GET /{t}/employees?…`, `GET /{t}/employees/{u}` | `EMPLOYEE_VIEW` | `{items,total,page,size}`; `page` 0-based; `size` 1-100 (default 25); **400 if out of range** (no clamping). Employees have **no version and no ETag**. |
| Employee create / enable / disable | `POST /{t}/employees`, `POST /{t}/employees/{u}/disable` and `/enable` (no body) | `EMPLOYEE_MANAGE` **and** `TENANT_MEMBERS` | – |
| Memberships / held positions | `…/employees/{u}/organization-memberships`, `…/employees/{u}/positions` | read: `EMPLOYEE_VIEW`; write: `EMPLOYEE_MANAGE` | `expectedVersion` in the body; for `DELETE`, the query `?expectedVersion=n`; ETag = version |
| Org tree | `GET /{t}/organization-units?format=tree\|flat&includeArchived` | `ORG_STRUCTURE_VIEW` | not paginated; no ETag on lists |
| Unit detail | `GET /{t}/organization-units/{id}` → `{unit, path[], activeChildCount, activeMemberCount}` | `ORG_STRUCTURE_VIEW` | ETag = `unit.version` |
| Unit types | `/{t}/organization-unit-types[...]` | read: `ORG_STRUCTURE_VIEW`; write: `ORG_STRUCTURE_MANAGE` | ETag; `expectedVersion` |
| Unit create / edit / move / archive / restore | `POST`, `PATCH /{id}`, `POST /{id}/move \| archive \| restore` | `ORG_STRUCTURE_MANAGE` | `expectedVersion` required; 409 `VERSION_CONFLICT` → reload using `details.currentVersion` |
| Positions / grades | `/{t}/positions[...]`, `/{t}/grades[...]` | read: `POSITION_GRADE_VIEW`; write: `POSITION_GRADE_MANAGE` | ETag; `expectedVersion` |

Version rule: the ETag of a record and its `version` field are both the `expectedVersion` of the next write. Versions start at 0 and increase by 1 on each applied write.

**Mismatches C5 must adapt.** These come from `inv-C` §6 and were re-checked against the code.
1. **There is no `ORG_MANAGE`.** Gate on `ORG_STRUCTURE_*`, `EMPLOYEE_*` and `POSITION_GRADE_*`. Remove `"ORG_MANAGE"` from `features/admin/organization.ts:17` `type Need` and its allow-list entry (T-ORG-MANAGE).
2. **A SYSTEM_ADMIN sees 403 on every org route.** Its `/auth/me` `permissions` is exactly `TENANT_MANAGE, TENANT_MEMBERS`. Do not offer org screens on `TENANT_MEMBERS` or `platformScope`. Employee enable / disable needs `EMPLOYEE_MANAGE` + `TENANT_MEMBERS`. `canToggleStatus = scope.platform` is wrong.
3. **Flat vs tree.** The list defaults to `format=tree` (`[{unit, children}]`). Send `?format=flat` for `OrgUnit[]`. Archived units are hidden unless `includeArchived=true`.
4. **`active` + `archivedAt` replace `enabled`** on units. Unit `typeId` and `code` are **required**. `code` comes back upper-case.
5. **Counts are not in the list.** Only the detail carries `activeChildCount` / `activeMemberCount`.
6. **There is no delete.** Use `POST …/{id}/archive {expectedVersion}` and `/restore`. Unit PATCH accepts `{name?, code?, sortOrder?, metadata?, expectedVersion}`. The type is immutable, and the parent changes only through `move {newParentId (key required; null = root), expectedVersion, sortOrder?}`.
7. **Unit types.**
   - Parent / child rules are nested in `rules{allowedParentTypeIds?, allowedChildTypeIds?, allowRoot?, maxDepth?}`.
   - `[]` means **root only** for parents and **leaf** for children. Only `null` means "anywhere".
   - Type codes are **lower-case**: stop upper-casing them (`organization.ts:115`).
   - Types support `PATCH` and `disable` / `enable`.
8. **Positions and grades are two catalogs**: `PositionDto {id, tenantId, name, code, description?, active, version, …}` and `GradeDto {…, rank?, description?}`. Both have CRUD and enable / disable.
9. **Employee shape.** The response is `EmployeeDto {userId, tenantId, username, displayName, email, active, accountEnabled, accountActivated, tenantRole, primaryOrganizationUnitId, positions[], organizationMemberships[]}`. It carries no names of units or positions (resolve them client-side) and no workspaces.
10. **Employee query.** Parameters are `q` (≥2 characters), `organizationUnitId`, `includeDescendants` (default true), `positionId`, `gradeId`, `active` (boolean, not a status enum), `userId`, `page`, `size` (1-100, default 25), `sort=name|username`, `dir=asc|desc`. The page is `{items,total,page,size}`; `source` is client-only.
11. **`POST /employees` creates a NEW account.** Positions are nested inside `organizationMemberships[]`. To attach an existing member, use `POST /employees/{u}/organization-memberships`. Setting a unit or position is composed from the membership and position sub-resources: there is no single "set unit / position" call.
12. **Codes renamed.**
    - `ORG_HAS_CHILDREN` → `ORG_UNIT_HAS_CHILDREN`
    - `ORG_HAS_EMPLOYEES` → `ORG_UNIT_HAS_MEMBERS`
    - `ORG_CODE_TAKEN` → `ORG_UNIT_CODE_TAKEN`
    - `TYPE_CODE_TAKEN` → `ORG_UNIT_TYPE_CODE_TAKEN`
    - `ORG_PARENT_TYPE_INVALID` → `ORG_TYPE_RULE_VIOLATION{reason}`
    - `UNIT_NOT_FOUND` → `ORG_UNIT_NOT_FOUND`
    - `USER_NOT_FOUND` → `EMPLOYEE_NOT_FOUND` (org routes)
    - `STALE_VERSION`, `CYCLE_DETECTED`, `UNIT_NOT_EMPTY` and `DUPLICATE_NAME` do not exist.
    - Add mappings for `RESTORE_CONFLICT`, `ORG_UNIT_ARCHIVED`, `ORG_MEMBERSHIP_*`, `POSITION_*`, `GRADE_*`, `EMPLOYEE_INACTIVE`, `EMPLOYEE_ORG_HAS_POSITIONS`, `QUERY_TOO_SHORT`, `INVALID_CODE` and `TENANT_SUSPENDED`.
13. **The `/auth/me` type** (`packages/types/src/index.ts`) lacks `projectScopes`. Add it, and admit to Studio per section 2.5 (`packages/permissions/src/index.ts` `studio.build`).
14. **`resolvePermissions` drops non-canonical codes.** It filters against `PERMISSION_CODES` (`packages/permissions/src/canonical.ts:17`, `packages/types/src/contract/v2/permissions.ts:4-9`), which has 14 codes and none of the six org codes. Org gates must not go through it until C0 extends the contract and the TS mirror (section 10).
15. **`tenant.members` is granted by role name** (`packages/permissions/src/index.ts:46`, `tenantRole === "TENANT_ADMIN"`). Gate it on `TENANT_MEMBERS` only.
16. **Tenant rename and status.** Rename is `PATCH /{t} {name}` gated on `TENANT_MANAGE`. Status is platform-only. Show `tenants[].status` (SUSPENDED); org writes there answer 403 `TENANT_SUSPENDED`.

---

## 10. C3 INPUT and C0 INPUT

### 10.1 C3: seams to implement (`M/organization/OrganizationRepositories.kt`)

**Beans to register.** These are optional at boot; a missing bean answers 501:
- `OrganizationUnitTypeRepository`
- `OrganizationUnitRepository`
- `EmployeeDirectoryRepository`
- `EmployeeOrganizationMembershipRepository`
- `PositionRepository`
- `GradeRepository`
- `EmployeePositionRepository`
- `TenantStructuralLock` (`pg_advisory_xact_lock(tenant)`: transaction-scoped, re-entrant, and `IllegalStateException` without a transaction)

**Rules.**
- Every rule in `OrganizationRepositories.kt:12-40` is binding: tenant first, versioned CAS writes that answer null, store-enforced uniqueness, ambient transaction only, typed refusals under row locks, deterministic order, no cascade.
- Typed refusals: `DuplicateOrganizationKey(key)` (keys `code`, `membership`, `assignment`, `primary`), `OrganizationUnitInUse`, `MembershipHasPositions`, `ReferencedRowInactive(kind)`, `OrganizationCycle`.
- `TenantIdentityDirectory` stays C1-owned (`M/organization/JdbcTenantIdentityDirectory.kt`). C3 reads `users` / `tenant_members` and never writes them.

**Conformance kit.**
- Subclass `T/organization/OrganizationRepositoryContractKit.kt` on PostgreSQL. Implement its abstract members: the seven repositories, plus `identities`, `newTenant()`, `newMember(tenantId, username, displayName)` and `setMemberActive(...)`.
- All **13** tests must pass unchanged. The reference subclass is `InMemoryOrganizationConformanceTest`.
- The C3-only PostgreSQL tests are listed in the org contract §10: lock concurrency, move races, setPrimary races, archive vs insert, end membership vs addPosition, and the create-depth race.

**V32 content requirements** (file name and number are allocated by C0 only):
- **Every table:** `tenant_id NOT NULL REFERENCES tenants(id)`, `version BIGINT NOT NULL DEFAULT 0` (C1 inserts version 0; do **not** add `CHECK version >= 1`), `created_at` / `updated_at`, an index on `(tenant_id, created_at)`, and a retention statement (ledger rules).
- **Unit types:** `code` unique per tenant, lower-case; `name`; `icon NULL`; `active`; rules: allowed parent ids, allowed child ids, `allow_root NULL`, `max_depth NULL` (1-100).
- **Units:**
  - `parent_id NULL` with a composite FK `(tenant_id, parent_id)` → units, and a type FK in the same tenant.
  - `code VARCHAR(60)` with `CHECK (code = upper(code))`; `sort_order`; `metadata jsonb` (≤ 8192 bytes, enforced by C1); `active`; `archived_at` (consistent with `active`).
  - Partial unique `(tenant_id, parent_id, code) WHERE archived_at IS NULL`, `NULLS NOT DISTINCT`, so that roots are siblings.
  - A recursive query (CTE) for `subtree`, `depthOf` and the store-side cycle check in `move`.
- **Memberships:**
  - `id`; composite FK `(tenant_id, user_id)` → `tenant_members`; `organization_unit_id` (same-tenant FK); `relation_type`; `is_primary`; `active`; `version`.
  - Partial unique `(tenant_id, user_id, organization_unit_id) WHERE active`.
  - Partial unique `(tenant_id, user_id) WHERE is_primary AND active`.
- **Positions / grades:** unique `(tenant_id, lower(code))` (it also covers inactive rows); `description NULL`; grade `rank INT NULL` (0-10000).
- **Employee positions:**
  - `id`; `membership_id` with a composite FK to the membership (same tenant and user, **no CASCADE**); `position_id`; `grade_id NULL`; `is_primary`; `active`.
  - Partial unique `(membership_id, position_id) WHERE active`.
  - Partial unique `(tenant_id, user_id) WHERE is_primary AND active`.
- **Locking:** the advisory lock is used only by move. Inserts take the referenced row `FOR SHARE`; archive and end take `FOR UPDATE`. The proposal SQL on `agent/c3-org-v32-proposal` is stale on all these points and must be rewritten (inv-C §4).

### 10.2 C0: what to record

These are coordination files. C1 does not edit them.

1. **A DECISIONS.md entry** (required before any `docs/contracts/**` change):
   - The six organization capabilities `ORG_STRUCTURE_VIEW/MANAGE`, `EMPLOYEE_VIEW/MANAGE` and `POSITION_GRADE_VIEW/MANAGE` **replace `ORG_MANAGE`**, which is never promoted.
   - TENANT_ADMIN holds all six. SYSTEM_ADMIN (default) and WORKSPACE_ADMIN hold none.
   - CF-1..CF-4 are frozen. This supersedes the `EmployeeProfile` / "delete" wording of D-C0-43.
   - `/auth/me.projectScopes` is added, and the live-recompute rules of section 2.3 apply.
   - Tenant rename `PATCH /admin/tenants/{t}` is added.
   - Org writes answer 403 `TENANT_SUSPENDED` in a SUSPENDED tenant.
2. **`docs/contracts/v2/tenant-permission.md` §5:** add the six codes to the vocabulary table. Correct the stale §4 line 30 (`platformScope` is only `TENANT_MANAGE, TENANT_MEMBERS`) and §5 line 40 (`MEMBER_MANAGE` *is* listed to clients as a portal-facing capability, `Permission.kt:62`). Update the TS mirror `packages/types/src/contract/v2/permissions.ts` to match. `app/definition/PermissionCodes.ALL` (C2) is the `PermissionDef` vocabulary and needs no org codes.
3. **BLOCKERS:**
   - **H-C1-17** → DELIVERED (contract): this file plus the org contract; backend routes live behind seams; persistence WAITING_FOR_C3 (V32).
   - **H-C1-04** → backend DELIVERED on this branch (`projectScopes`, `ProjectScopedAuthMeTests`); remaining work is C5 Studio admission (section 2.5).
   - **T-ORG-MANAGE** → answered by item 1. C5 removes `ORG_MANAGE` and its allow-list entry.
4. **Ledger row V32, replacement text:** "`V32__dynamic_organization.sql` (RESERVED, not allocated) · Dynamic Organization persistence: unit types, units, employee organization memberships, positions, grades, employee position assignments (**no employee profile**) · **C3** implements the C1 seams of H-C1-17 (`docs/parallel/c1/final-iam-tenant-org-permission-contract.md` §10.1, D-C0-43 as reconciled CF-1..CF-4) · allocated only after V31 (C2) is applied and verified."
5. **BOARD "Migration requests":** a V32 request (owner C3, depends on V31) with the §10.1 content.

### 10.3 Known gaps owned by others

None of these is fixed on this branch.

| # | Gap | Evidence | Owner |
|---|---|---|---|
| 1 | `AdminUserController` revokes sessions **by username, before commit**. An in-flight request can re-save its session. Renamed principals (SCIM) are missed; `ActiveUserFilter` still blocks them by id while they are disabled. | `M/admin/AdminUserController.kt:89-93,106-110`; `M/identity/Scim.kt:162-167` | C0 (`admin/**`) with C1 (`identity/**`) |
| 2 | **Pending activation / reset tokens survive a disable** and work again after re-enable (within 24 h). | `Accounts.kt:64,187`; no token cleanup in `AdminUserController.kt:78-95` | C0 + C1 |
| 3 | The next request after an **API disable** answers **401 `AUTHENTICATION_REQUIRED`**, not `ACCOUNT_DISABLED`, because the session is deleted first. Clients must treat both 401 codes as "signed out". | `AdminUserController.kt:91`; `SecurityConfiguration.kt:188-189`; `AdminApiTests.kt:64` | C0 (contract choice) |
| 4 | **maxDepth race.** Create and restore validate `maxDepth` without the structural lock. C3 should re-check the depth inside `insert` / `setActive(true)` under the parent row lock. | `OrganizationRepositories.kt:37-38`; org contract §11 | C3 |
| 5 | **The suspended-tenant policy for tenant-admin member and provisioning routes is unchanged.** `forTenant` does not check SUSPENDED, so a Tenant Admin keeps member, user and workspace routes (organization writes and rename are read-only in a SUSPENDED company). | `AccessService.kt:127-143`; `Accounts.kt:123` | C1 policy, needs a C0 decision |
| 6 | `/auth/me` is O(N) in the number of project memberships (each one runs `forProject`, ~5 queries). Acceptable for V1; a batched query or a cap is a later optimisation. | `AuthController.kt` projectScopes | C1 |
| 7 | Top-level `permissions` covers the primary tenant only. | `MeTenancy.kt:37-41` | C1 + contract (C0) |
| 8 | Last-tenant-admin count is not locked (concurrent demotions). | `TenantService.kt:225-228` | C1 |
| 9 | A workspace member with **no** `tenant_members` row is treated as active. | `AccessService.kt:92`; `AuthController.kt:161` | C1 (legacy, pre-V26 data) |
| 10 | `firstAdminUserId` does not check that the user is enabled or activated. | `TenantController.kt:62` | C1 |
| 11 | Tests that are still missing: the exact error code of the next request after an API disable (it is 401 `AUTHENTICATION_REQUIRED` because the session is deleted first; `ACCOUNT_DISABLED` appears only when a live session meets a disabled row), and the concurrency of the last-admin check. Covered on this branch: tenant rename, organization writes in a SUSPENDED company, `/auth/me` live rules, a plain employee with relation / position / grade gets no `APP_*`, TENANT_ADMIN 403 on `/admin/users/{id}/status`, old session stays dead after re-enable (`T/tenancy/FinalIam*Tests`, `FinalTenantStatusTests`, `FinalPermissionSemanticsTests`). | `T/tenancy/Final*` | C1 (tests) / C0 (`admin` tests) |
