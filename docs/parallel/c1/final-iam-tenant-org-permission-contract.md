# C1 final contract: IAM, tenant, organization and permission

Branch `fix/c1-final-iam-org-contract-repair-2` (repair of `feat/c1-final-iam-org-permissions`), based on `integration/v2 @ 19fd4c2b500c`. That baseline already contains the C1 final import (D-C0-51), C3's PostgreSQL persistence of the organization seams with `V32__dynamic_organization.sql` (D-C0-52) and C0's D-C0-52 wiring (persistence flag, lock timeout, counts, directory offset bound, `ORG_STRUCTURE_BUSY`, structural lock for every structural operation). This file is the single C1 contract for identity, account lifecycle, sessions, tenants, tenant membership, the organization route summary and the permission matrix. **The code on this branch is the source of truth.** Every statement below was checked against the current source. Where an older C1 document or an inventory says otherwise, this file and the code win.

Path abbreviations used for evidence:
- `M/` = `backend/src/main/kotlin/com/systemwebstudio/`
- `T/` = `backend/src/test/kotlin/com/systemwebstudio/`

Evidence is cited by **symbol** (`Class.method`, `Object.member`) wherever possible, because line numbers drift. Where a `file:line` is given it was re-checked against this branch.

Status words used in this file:
- **DONE**: in the source of this branch and covered by a test.
- **DONE (no test)**: in the source of this branch, but no test was found under `T/` when this file was written.
- **GAP**: known behaviour that is not the target. The owner is named.

DTOs, request bodies and detailed error lists for organization, employee, position and grade routes are **not repeated here**. They live in [organization-employee-contract.md](organization-employee-contract.md) (the "org contract"). Section 6 holds the one authoritative route summary. The `/auth/me` project-scope background is in [h-c1-04-project-scoped-auth-me.md](h-c1-04-project-scoped-auth-me.md).

---

## 0. Scope and ownership

| Area | Owner | Files |
|---|---|---|
| Identity, login, sessions, `/auth/me`, accounts, activation | **C1** | `M/identity/**`, except `identity/SecurityConfiguration.kt`, which is C0-only (`docs/parallel/OWNERSHIP.md:28`) |
| Access decisions, permission vocabulary, matrix | **C1** (HOT) | `M/access/AccessEvaluator.kt`, `M/access/AccessService.kt`, `M/access/ProjectScopeResolver.kt`, `M/access/Permission.kt`, `M/access/MeTenancy.kt`, `M/access/adapters/**` |
| Tenants, tenant membership, company bootstrap | **C1** | `M/tenancy/**` |
| Workspace and project membership | **C1** | `M/member/**` |
| Organization API, authorization, services, seams | **C1** | `M/organization/**` |
| Organization **persistence** (SQL, migration, seam implementations) | **C3** (wiring / flag: C0) | **Implemented and imported (D-C0-52):** `M/data/org/**` (`OrganizationPersistenceConfiguration`, `OrgDb`, `PostgresTenantStructuralLock`, `PostgresOrganizationCounts` and the `Postgres*Repository` classes), `backend/src/main/resources/db/migration/V32__dynamic_organization.sql`, `docs/parallel/c3/**`. It implements the seams of `M/organization/OrganizationRepositories.kt` (plus the C0 counts seam `OrganizationEmployeeCounts`). Active only behind the flag of section 0 "Migrations". |
| Admin console (`/api/v1/admin/users/**`, `AdminGuard`) | **C0-gated** | `M/admin/**`, including `AdminUserController.kt` and `AdminSupport.kt` |
| Settings, common errors, audit | **C0-gated** | `M/settings/**`, `M/common/**`; `M/audit/**` (callers use only `AuditService.record`) |
| Frontend (portals, `/auth/me` type, permission helpers, org screens) | **C5** | `packages/**`, `features/**` |

Migrations and persistence status:
- **MIGRATION_REQUIRED = NO (for C1).** C1 creates no migration and this branch adds none. The organization tables already exist: `V32__dynamic_organization.sql` was **created by C3 and imported** into `integration/v2` (D-C0-52; `docs/parallel/MIGRATION_LEDGER.md`, row **V32**: ALLOCATED · CREATED / IMPORTED, immutable). Nothing else in this contract needs a schema change: tenant rename writes the existing `tenants.name` and `tenants.updated_at`.
- **V31 is VOID** (a permanent gap, D-C0-52, ledger row **V31**); V32 no longer depends on it. V33 is not allocated. `outOfOrder` stays off.
- **Persistence: IMPLEMENTED by C3, behind the D-C0-52 feature flag** `app.organization.persistence-enabled` (`backend/src/main/resources/application.yml`: `persistence-enabled: ${ORGANIZATION_PERSISTENCE_ENABLED:false}`, i.e. env `ORGANIZATION_PERSISTENCE_ENABLED`, **default `false`**). `OrganizationPersistenceConfiguration` is `@ConditionalOnProperty(prefix = "app.organization", name = ["persistence-enabled"], havingValue = "true")`, so with the default no organization repository bean exists.
- **`501 ORG_PERSISTENCE_NOT_AVAILABLE` applies only when no store is wired** (flag off, or a seam bean missing): `OrganizationRepositories` throws it when a needed `ObjectProvider` has no bean, always after authentication and authorization. With the flag on, the PostgreSQL store answers.
- The one lock-wait bound is `app.organization.structural-lock-timeout-ms` (`${ORGANIZATION_STRUCTURAL_LOCK_TIMEOUT_MS:5000}`); exceeding it answers **503 `ORG_STRUCTURE_BUSY`** (section 6.2 item 6).

---

## 1. Two lifecycles (frozen)

There are two independent switches. Neither one ever writes the other.

| | GLOBAL ACCOUNT | TENANT EMPLOYEE / MEMBERSHIP |
|---|---|---|
| Column | `users.enabled` | `tenant_members.active` (one row per tenant and user) |
| Who may change it | **Only the platform authority SYSTEM_ADMIN** (enabled, `system_admin` read live by `AdminGuard.isAdmin`, `M/admin/AdminSupport.kt`) | **Only a holder of `TENANT_MEMBERS` on that tenant**: its Tenant Admin, and also a platform SYSTEM_ADMIN through platform scope. For the employee routes, also `EMPLOYEE_MANAGE`, which in practice means only the Tenant Admin. |
| Routes | `PATCH /api/v1/admin/users/{id}/status {enabled}` (`AdminUserController.status`); SCIM `active=false` (`M/identity/Scim.kt`, private `setActive`) | `DELETE` and `PUT /api/v1/admin/tenants/{t}/members/{u}` (`TenantController.removeMember` / `setMember`); `POST /api/v1/admin/tenants/{t}/employees/{u}/disable` and `/enable` (`EmployeeController.disable` / `enable`, `M/organization/OrganizationControllers.kt`) |
| Effect on sessions | A disable **deletes every session of the username** (`AdminUserController.status` → private `revoke`). The next request with the old cookie answers **401**. Re-enabling does **not** resurrect sessions: they are gone from Redis, and the user must log in again (`T/admin/AdminApiTests.kt:62-69`). | **No session is touched.** The next request recomputes access: tenant routes answer 404 `TENANT_NOT_FOUND`, the tenant's workspaces answer 404 `WORKSPACE_NOT_FOUND`, and `/auth/me` omits them. |
| Cross-tenant side effect | Affects every tenant. This is intended: it is the account. | **None.** Only that tenant's row changes. `users.enabled` is never written by any tenant route. Evidence: `TenantService.removeMember` / `TenantService.setMember` and `EmployeeDirectoryService.setActive`. |
| Re-activation | `PATCH … {enabled:true}` only flips the flag (`AdminUserController.status`). | `PUT members/{u} {role}` restores the given role. Employee `enable` always restores **MEMBER**, so a former TENANT_ADMIN does not get the role back (`EmployeeDirectoryService.setActive`). A platform-disabled account gives 422 `USER_DISABLED` (`TenantController.setMember`, `EmployeeDirectoryService.setActive`). |

Pending activation and reset tokens, as coded:
- Disabling an account does **not** delete its unused `account_tokens`.
- While the account is disabled, the token cannot be used: lookup requires `u.enabled` (`AccountService.find`, `M/identity/Accounts.kt`), so it answers 410 `LINK_INVALID`.
- `POST …/activation-link` refuses a disabled account with 409 `ACCOUNT_DISABLED` (`AccountService.link`).
- **GAP (C0-gated `admin/**`, C1 `identity/**`):** after re-enabling, a token issued before the disable becomes usable again if it is still within its 24 h TTL (`AccountService.TTL`, `AccountService.find`). No test covers this. Target: the disable deletes unused tokens (`DELETE FROM account_tokens WHERE user_id=? AND used_at IS NULL`). This needs C0's approval in `AdminUserController` and the same change in `Scim.kt` (`setActive`).

---

## 2. AUTH

All routes are under `/api/v1/auth`. The session cookie is `STUDIO_SESSION` (`M/identity/SessionConfiguration.kt`). CSRF uses a cookie token (`XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header). CSRF is checked on every state-changing request, **including login** (`SecurityConfiguration.kt:171`; only the data webhook is exempt).

### 2.1 Routes

| METHOD PATH | REQUEST | RESPONSE | PERMISSION | TENANT SCOPE | ERRORS | VERSION | AUDIT |
|---|---|---|---|---|---|---|---|
| `GET /csrf` | – | `{token}` | public | – | – | – | – |
| `GET /config` | – | sign-in options map (`AuthController.config`) | public | – | – | – | – |
| `POST /login` | `{username (≤120, trimmed), password (≤256)}` | `MeResponse` (2.2). Session id rotated; idle timeout = setting `session.timeout-minutes` × 60 s (`AuthController.login`). | public plus CSRF | – | 404 `LOCAL_LOGIN_DISABLED`; 429 `RATE_LIMITED` (+`Retry-After`, `details.retryAfterSeconds`; per-user and per-IP limits); 401 `INVALID_CREDENTIALS` (wrong password, unknown user, **disabled** user, or pending account with an unusable hash); 403 `CSRF_INVALID` | – | `LOGIN_SUCCESS`; `LOGIN_FAILURE {reason: rate_limited \| bad_credentials}` |
| `POST /logout` | – | `{status:"logged_out", redirect?}`. `redirect` is the OIDC end-session URL when the session came from OIDC. | authenticated | – | 401 `AUTHENTICATION_REQUIRED`; 403 `CSRF_INVALID` | – | `LOGOUT` |
| `GET /me` | – | `MeResponse` | authenticated | derived server-side | 401 `AUTHENTICATION_REQUIRED` (no session); 401 `ACCOUNT_DISABLED` (session exists but account disabled or gone, `ActiveUserFilter.doFilterInternal`) | – | – |
| `POST /activation/inspect` | `{token}` | `{username, displayName, purpose: ACTIVATION\|RESET}` | public (30 / 600 s per IP) | – | 410 `LINK_INVALID`; 429 | – | – |
| `POST /activation/complete` | `{token, password}` | `{status:"ok"}` | public (30 / 600 s per IP) | – | 410 `LINK_INVALID`; 400 `WEAK_PASSWORD`; 429 | – | `ACCOUNT_ACTIVATED` or `PASSWORD_RESET_COMPLETED` |

### 2.2 `MeResponse`: exact DTO (`MeResponse`, `WorkspaceSummary`, `ProjectScopeSummary` in `M/identity/AuthController.kt`)

The response shape is **unchanged** by the bounded-statement work of 2.3a: same fields, same order, same values.

| Field | Type | Source and rule |
|---|---|---|
| `id` | UUID | session principal |
| `username` | string | session principal (snapshot taken at login) |
| `displayName` | string | session principal snapshot; falls back to `username` |
| `roles` | string[] | **live**: `["USER","ADMIN"]` if `users.system_admin` is true now, else `["USER"]` (`AuthController.me`). **Informational only.** |
| `workspaces` | `WorkspaceSummary[]` | see 2.3 |
| `systemAdmin` | boolean | **live** `users.system_admin` |
| `tenantId` | UUID? | primary tenant (2.4); null = no tenant membership |
| `tenantRole` | `"TENANT_ADMIN"` \| `"MEMBER"` \| null | the role in the primary tenant. Informational only. |
| `platformScope` | boolean | = live `systemAdmin` (`MeTenancyService.forUser`) |
| `businessAccess` | boolean | `systemAdmin && app.tenancy.system-admin-business-access` (default false) |
| `tenants` | `TenantMembershipSummary[]` | `{id, slug, name, status, role, permissions}` for each **active** membership whose tenant is not DELETED (`permissions` = the canonical codes of THAT tenant, see 2.4a). SUSPENDED tenants **are listed**, with `status:"SUSPENDED"`. Ordered DEFAULT first, then by membership `created_at`, then slug (`MeTenancyService.forUser`). |
| `permissions` | string[] | canonical codes: `platformScope` codes (if systemAdmin) **plus** the primary tenant's role codes, sorted (`MeTenancyService.forUser` → `PermissionCodes.canonicalCodesOf`) |
| `projectScopes` | `ProjectScopeSummary[]` | see 2.3 |

`WorkspaceSummary` is `{ id: UUID, name: string, role: string, tenantId: UUID?, permissions: string[] }`.

`ProjectScopeSummary` is `{ projectId: UUID, workspaceId: UUID, role: string?, permissions: string[] }`.

### 2.3 Live-recompute rules (every call; nothing is cached)

**Roles.** `roles` and `systemAdmin` come from the live `users.system_admin` (`AuthController.me`, first statement). They are not taken from the session snapshot. DONE: `T/tenancy/FinalIamLifecycleTests.kt` test 7 (revoke inside a live session → `systemAdmin=false`, `roles=["USER"]`).

**`workspaces[]` for a non-SYSTEM_ADMIN** (`AuthController.me`, `else` branch of the workspaces query). The rows are the active `workspace_members` rows, with these rules:
- **Omitted** when the workspace's tenant is `DELETED`.
- **Omitted** when the user's `tenant_members` row for that tenant is inactive. A missing tenant row is treated as active, which matches `AccessEvaluator.workspace` (a `TenantResolution` with `membershipActive == null` passes the tenant gates).
- A workspace of a **SUSPENDED** tenant is **listed with `permissions: []`**, because the API answers 403 `TENANT_SUSPENDED` there.
- Otherwise `permissions` = the canonical codes of `PermissionMatrix.workspaceRoles[role]` (`MeTenancyService.workspacePermissions`).
- DONE: `T/tenancy/FinalTenantStatusTests.kt` 6a (SUSPENDED → empty permissions), `T/tenancy/FinalIamLifecycleTests.kt` 2a/2b (removed / deactivated tenant membership).

**`workspaces[]` for a SYSTEM_ADMIN** (`AuthController.me`, `if (systemAdmin)` branch). The rows are **every** workspace:
- `role` is the real membership role, or `"ADMIN"` when it is not a member.
- `permissions` = the member role's codes, or for `"ADMIN"` the `platformScope` codes (default) / the full legacy set `PermissionMatrix.systemAdmin` when the legacy flag is on (`MeTenancyService.workspacePermissions`).
- **SYSTEM_ADMIN rows follow the same gates as `forWorkspace`:** a workspace the operator belongs to by MEMBERSHIP grants nothing when its tenant is DELETED or the operator's tenant membership was removed (the row shows role `ADMIN` = platform scope, although the server answers with platform scope only there), and carries NO permission when the tenant is SUSPENDED; a workspace without membership is listed with role `ADMIN` (platform scope only). DONE: `T/tenancy/FinalTenantStatusTests.kt` 6b / 6c.
- These rows are computed in SQL + Kotlin inside `AuthController.me`, **not** through `AccessEvaluator` (see the limit in 2.3a).

**`projectScopes[]`** (`AuthController.me` → `ProjectScopeResolver.scopesFor`):
- Candidates: active `project_members` rows of the caller in active projects of the same workspace (one joined statement, 2.3a).
- Each candidate is decided by the **same pure functions** the project APIs use: `AccessEvaluator.workspace` then `AccessEvaluator.project`. `AccessService.forWorkspace` / `AccessService.forProject` call exactly these two functions after loading ONE workspace / project; `ProjectScopeResolver` calls them after loading ALL candidates at once. There is no second implementation of a rule.
- If the evaluator throws an `ApiException` (a denial), the row is **omitted**: removed workspace membership, removed tenant membership, SUSPENDED or DELETED tenant, a project role without an effective workspace role, and so on. SQL / infrastructure errors are **not** swallowed (5xx).
- A disabled / unknown account has no project scope (`AccessEvaluator.requireEnabledAccount`; in practice `ActiveUserFilter` already answered 401).
- `permissions` = `PermissionCodes.canonicalCodesOf(decision.permissions)`, i.e. workspace role set ∪ project role set, cut down to {APP_VIEW} (+ the non-canonical AUDIT_READ) for an ARCHIVED project.
- **A project role counts only through an ACTIVE workspace role or the legacy bypass** (`AccessEvaluator.project`: `effectiveRole = if (ws.workspaceRole != null || ws.systemAdminBypass) projectRole else null`). Consequence: a **stale `project_members` row of a platform operator (SYSTEM_ADMIN) that is not a workspace member grants nothing** (platform scope has no `PROJECT_READ`, so the project is 404 in the API and absent from `/auth/me`), exactly as a non-member ordinary user is refused at the workspace gate.
- **Each project is its own row. Rows are never unioned, and never merged into `permissions` or `workspaces[].permissions`.** Ordered by `workspace_id, project_id`.
- DONE: `T/tenancy/ProjectScopedAuthMeTests.kt` (A-K plus the hierarchy flow), and the differential proofs `T/identity/authme/ProjectScopeEquivalenceTests.kt` + `T/identity/authme/ProjectScopeLegacyFlagEquivalenceTests.kt` (2.3a).

**Role strings are informational only.** This covers `roles`, `workspaces[].role`, `tenantRole`, `tenants[].role` and `projectScopes[].role`. Clients gate only on canonical permission codes.

### 2.3a `/auth/me` cost: BOUNDED statement count (guarantee)

**Guarantee.** One `GET /api/v1/auth/me` executes a **constant number of SQL statements, independent of the number of project, workspace and tenant memberships** of the caller. Measured on the request thread by the test counter (`T/identity/authme/SqlStatementCounting.kt`): **6 statements per request**, for N = 25, 100, 500 and 2000 project memberships.

| # | Statement | Where |
|---|---|---|
| 1 | `users` row of the principal (enabled / exists) | `ActiveUserFilter.doFilterInternal` (`UserRepository.findById`) |
| 2 | `SELECT system_admin FROM users WHERE id = ?` (live flag) | `AuthController.me` |
| 3 | the `workspaces[]` query (all workspaces + membership + tenant + tenant membership for a SYSTEM_ADMIN; active memberships with the tenant filters otherwise) | `AuthController.me` |
| 4 | `SELECT enabled, system_admin FROM users WHERE id = ?` | `ProjectScopeResolver.scopesFor` |
| 5 | ONE joined statement `project_members JOIN projects JOIN workspaces LEFT JOIN workspace_members (active) LEFT JOIN tenants LEFT JOIN tenant_members`, `WHERE pm.user_id = ? AND pm.active AND p.active ORDER BY pm.workspace_id, pm.project_id` | `ProjectScopeResolver.scopesFor` |
| 6 | the `tenants[]` statement (`tenant_members JOIN tenants`, active, not DELETED) | `MeTenancyService.forUser` |

Properties:
- **Nothing is cached.** Every request reads the database again; a revoke / downgrade / tenant status change is visible on the very next `/auth/me` (`T/tenancy/FinalIamLifecycleTests.kt` 2a-2d, 7).
- **Same decision logic as `AccessService.forProject`**, because both call the pure `AccessEvaluator` (`AccessEvaluator.requireEnabledAccount`, `AccessEvaluator.workspace`, `AccessEvaluator.project`). The joined statement only LOADS what `forWorkspace` / `forProject` would load one by one (`TenantResolver.resolveForWorkspace` data, the active `workspace_members` row, the project lifecycle); primary keys `(workspace_id, user_id)` and `(tenant_id, user_id)` guarantee at most one row per candidate.
- **The old gap "O(N), ~5 queries per project" is CLOSED.** Before: `5 + 5·N` statements (one `AccessService.forProject` per membership). After: `6`, constant.
- **Regression test:** `T/identity/authme/AuthMeQueryComplexityTests.kt` asserts `statements(N=2000) <= statements(N=25) + 3`, plus the positive control `statements(N=25) >= 4` (the counter really counts), and checks every returned scope against the expected role set.
- **Differential proof** against the single-resource path: `ProjectScopeEquivalenceTests` (project roles, archived, inactive membership / project, workspace admin with / without project row, inactive workspace membership, tenant member removed / SUSPENDED / DELETED, project in another tenant's workspace, SYSTEM_ADMIN platform-only with an explicit project row, SYSTEM_ADMIN with workspace membership under every tenant state) and `ProjectScopeLegacyFlagEquivalenceTests` (legacy bypass on) compare `/auth/me` with `AccessService.forProject` for every candidate (`ProjectScopeDifferentialBase.oracle`).

**Limits (stated honestly):**
1. **Response size still grows linearly with the number of visible projects.** There is no pagination: the contract (`projectScopes[]` is complete) is unchanged. Breaking it (pagination, a cap, a lazy endpoint) needs C0 approval and a contract entry.
2. **`workspaces[]` re-implements the tenant gates in SQL / Kotlin** (`AuthController.me`), not through `AccessEvaluator`. With the default flag they agree with the API. With the **LEGACY** flag `app.tenancy.system-admin-business-access=true`, a SYSTEM_ADMIN that is a member of a workspace of a SUSPENDED tenant gets `permissions: []` in `/auth/me` while the API grants the legacy bypass there: `/auth/me` **under-reports** (fail-safe; it never over-reports).
3. **`ProjectController.list` (`M/project/**`, not C1)** filters non-admin callers with `EXISTS (SELECT 1 FROM project_members … AND m.active)` after `forWorkspace`. A platform operator (non-member SYSTEM_ADMIN, platform scope) with a **stale** active `project_members` row therefore still sees that project's **metadata** in the list, although `forProject` / `/auth/me` refuse it. This needs a DB inconsistency (a project row without a workspace membership); the fix belongs to the `project/**` owner.
4. The per-membership / per-employee organization limits (20, org contract) are check-then-insert in the service: best-effort under concurrency (see org contract §11).

### 2.3b Benchmark (raw numbers)

`T/identity/authme/AuthMeBenchmarkTests.kt` drives `GET /auth/me` over HTTP (MockMvc, real PostgreSQL via Testcontainers) for N = 25 / 100 / 500 / 2000 project memberships: 2 warm-up calls (discarded), then 3 measured runs. It asserts **no latency** (no SLA is defined) and writes one raw line per N to `<BENCH_DIR>/bench-<BENCH_LABEL>.txt` (defaults `build/reports/bench`, label `run`; both read from a system property or the environment). It is HTTP-level only, so it ran unchanged on the N+1 resolver (BEFORE) and on the bulk resolver (AFTER).

**Machine load warning.** The machine is shared: load average was ~15-20 (set 2) and ~40 (set 1) from other sessions. Absolute milliseconds are **indicative only**. The acceptance property is the **bounded statement count**, not a time.

**Set 2 (quieter machine, load ~15-20)**

| Resolver | N | queries | run1 ms | run2 ms | run3 ms | median ms | response bytes |
|---|---|---|---|---|---|---|---|
| BEFORE (N+1) | 25 | 130 | 177.8 | 225.9 | 253.8 | 225.9 | 6922 |
| BEFORE (N+1) | 100 | 505 | 606.2 | 338.7 | 374.8 | 374.8 | 24197 |
| BEFORE (N+1) | 500 | 2505 | 1723.3 | 3050.2 | 1500.1 | 1723.3 | 116087 |
| BEFORE (N+1) | 2000 | 10005 | 4907.3 | 5012.4 | 5865.5 | 5012.4 | 460734 |
| AFTER (bulk) | 25 | 6 | 34.3 | 48.0 | 64.9 | 48.0 | 6922 |
| AFTER (bulk) | 100 | 6 | 34.6 | 25.1 | 29.7 | 29.7 | 24197 |
| AFTER (bulk) | 500 | 6 | 50.7 | 44.2 | 47.4 | 47.4 | 116087 |
| AFTER (bulk) | 2000 | 6 | 239.9 | 302.5 | 236.8 | 239.9 | 460734 |

**Set 1 (heavily loaded machine, load ~40)**

| Resolver | N | queries | run1 ms | run2 ms | run3 ms | median ms |
|---|---|---|---|---|---|---|
| BEFORE (N+1) | 25 | 130 | 692.3 | 555.1 | 495.0 | 555.1 |
| BEFORE (N+1) | 100 | 505 | 1172.4 | 731.5 | 659.0 | 731.5 |
| BEFORE (N+1) | 500 | 2505 | 3171.8 | 6889.7 | 3851.7 | 3851.7 |
| BEFORE (N+1) | 2000 | 10005 | 13436.1 | 21205.0 | 12597.7 | 13436.1 |
| AFTER (bulk) | 25 / 100 / 500 / 2000 | 6 for every N | – | – | – | 15.7 / 24.5 / 49.6 / 200.3 |

Reading: BEFORE = `5 + 5·N` statements; AFTER = `6`, constant. The response bytes are identical before and after (the payload is unchanged) and grow linearly with N (limit 1 of 2.3a). No SLA is claimed.

### 2.4 Primary tenant

The primary tenant is the first row of `tenants[]`: DEFAULT if the user is an active member of it, otherwise the oldest active, non-DELETED membership.

Top-level `permissions` carries the platform scope plus the role codes of the **primary tenant only** (unchanged). The capabilities of every OTHER tenant are in `tenants[].permissions` (2.4a); they are never flattened into the root list. (M-052 / AD01: closed.)

### 2.4a Tenant scope: `tenants[].permissions` (M-052, canonical)

```kotlin
// backend/src/main/kotlin/com/systemwebstudio/access/MeTenancy.kt
data class TenantMembershipSummary(val id: UUID, val slug: String, val name: String, val status: String, val role: String, val permissions: List<String> = emptyList())
```

- `permissions` is the canonical authorization signal **for that tenant only**: `PermissionMatrix.tenantRoles[role]` as canonical codes, alphabetically sorted like every other list. TENANT_ADMIN = exactly `EMPLOYEE_MANAGE, EMPLOYEE_VIEW, ORG_STRUCTURE_MANAGE, ORG_STRUCTURE_VIEW, POSITION_GRADE_MANAGE, POSITION_GRADE_VIEW, TENANT_MANAGE, TENANT_MEMBERS`. MEMBER = `[]`. (No `ORG_MANAGE` / `T-ORG-MANAGE`.)
- `role` stays **informational only** (display, routing hints). The server never authorizes from it: every decision goes through `AccessService.forTenant` / `forWorkspace` / `forProject`, `PermissionMatrix` and the live tenant membership / status. A client that reads `role === "TENANT_ADMIN"` is wrong by contract.
- **Additive and backward compatible:** the five existing fields are unchanged; `permissions` is a new field (absent in an older backend = unknown, never "granted").
- **Scope is explicit.** Root `permissions[]` / `tenantId` / `tenantRole` keep describing the platform scope + the PRIMARY tenant. A secondary tenant's capabilities appear ONLY in its own `tenants[]` entry and never authorize another tenant. A tenant with no active membership (or a DELETED one) is **absent** (no disclosure).
- **Live:** recomputed from the database on every `/auth/me` call, in memory from the single membership statement (no SQL per tenant, so the statement count stays 6 whatever the number of tenants; proven by `TenantCardinalityQueryTests`). A role downgrade, a membership removal or deactivation, a tenant deletion show up on the next call of the same session.
- **SUSPENDED tenant (exact current behaviour, no third policy):** the entry stays listed with `status:"SUSPENDED"` and the role's capabilities, exactly as the root `permissions[]` already did for the primary tenant. The API is the source of truth: organization / employee / position-grade WRITES and the tenant RENAME (by a Tenant Admin) answer 403 `TENANT_SUSPENDED`; reads and the other tenant-admin routes (members, users, workspaces) stay allowed; workspace routes of that tenant answer 403 `TENANT_SUSPENDED` (its workspaces[] rows carry no permission). C5 shows the status and lets the server answer.
- **SYSTEM_ADMIN:** the platform scope (`TENANT_MANAGE` + `TENANT_MEMBERS` on any tenant) stays in the root list; a SYSTEM_ADMIN's `tenants[]` entries carry only the codes of its own member role (with the legacy business bypass `app.tenancy.system-admin-business-access=true` (off by default): the TENANT_ADMIN set, as `forTenant` grants; the root list still shows only the primary member role's codes in that case, by design). Pinned by `T/tenancy/SystemAdminTenantScopeAuthMeTests.kt`.

Example (ids shortened; a user who is MEMBER of DEFAULT and TENANT_ADMIN of company A and MEMBER of company B):

```json
{
  "tenantId": "<DEFAULT>", "tenantRole": "MEMBER", "permissions": [],
  "tenants": [
    {"id": "<DEFAULT>", "slug": "default", "name": "Default", "status": "ACTIVE", "role": "MEMBER", "permissions": []},
    {"id": "<A>", "slug": "company-a", "name": "Company A", "status": "ACTIVE", "role": "TENANT_ADMIN",
     "permissions": ["EMPLOYEE_MANAGE","EMPLOYEE_VIEW","ORG_STRUCTURE_MANAGE","ORG_STRUCTURE_VIEW","POSITION_GRADE_MANAGE","POSITION_GRADE_VIEW","TENANT_MANAGE","TENANT_MEMBERS"]},
    {"id": "<B>", "slug": "company-b", "name": "Company B", "status": "ACTIVE", "role": "MEMBER", "permissions": []}
  ]
}
```

**C5 rule (AD01):**

```ts
const tenantScope = me.tenants.find(t => t.id === selectedTenantId)
const can = (code: string) => tenantScope?.permissions.includes(code) === true
  || (me.platformScope && me.permissions.includes(code))   // platform operators: the platform scope (TENANT_MANAGE / TENANT_MEMBERS on any tenant) is in the ROOT list
// NEVER: tenantScope.role === "TENANT_ADMIN"
```

Gates: organization structure `ORG_STRUCTURE_VIEW` / `ORG_STRUCTURE_MANAGE`; employees `EMPLOYEE_VIEW` / `EMPLOYEE_MANAGE`; positions and grades `POSITION_GRADE_VIEW` / `POSITION_GRADE_MANAGE`; tenant membership and users `TENANT_MEMBERS`; tenant rename `TENANT_MANAGE`; the tenant status screen iff `platformScope` (the status route is platform-only).

Tests: `T/tenancy/MultiTenantAuthMeTests.kt` (secondary admin, third-tenant member, foreign tenant, revocation, downgrade, suspension, deleted, role-is-not-authority, the AD01 flow), `T/identity/authme/TenantCardinalityQueryTests.kt` (statements(100 tenants) <= statements(1 tenant) + 2).

### 2.5 Frontend admission contract

**Studio** (H-C1-04):
- Admit **iff** some `workspaces[].permissions` contains `APP_VIEW` **or** some `projectScopes[].permissions` contains `APP_VIEW`.
- Never branch on a role name.
- Never merge a project scope into the workspace or global set.
- When a project is opened, use **that project's own** permissions: its `projectScopes[]` row, or the project payload. A person with `APP_VIEW` only opens the project read-only.
- An absent `projectScopes` field means an older backend: treat it as "cannot say no", the same rule as an absent `permissions`.

**Admin portal**:
- Platform screens (tenants list, create, status; accounts) are shown iff `platformScope == true`.
- Tenant screens are shown iff `permissions` contains the screen's code (section 9). For ANY tenant, read the `permissions` of the matching `tenants[]` entry (2.4a), never its `role`.
- Workspace member administration is shown iff that workspace's `permissions` contains `MEMBER_MANAGE`.
- Data-source administration is shown iff it contains `DATA_SOURCE_MANAGE`.

**Organization screens** gate on `ORG_STRUCTURE_*`, `EMPLOYEE_*` and `POSITION_GRADE_*`. They **never** gate on `TENANT_MEMBERS` or `platformScope`. A SYSTEM_ADMIN gets 403 on every org route (section 7).

**Every portal** treats the UI decision as a hint. The server re-checks every call.

---

## 3. TENANT routes

Prefix: `/api/v1/admin/tenants` (`M/tenancy/TenantController.kt:39`).

Authorization helpers (`M/access/AccessService.kt`):
- `AccessService.forPlatform`: an enabled SYSTEM_ADMIN, else 403 `ADMIN_REQUIRED`.
- `AccessService.forTenant`:
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
| `GET /{tenantId}/member-candidates?q=` | `q` optional; if given, ≥ 2 characters after trim | `TenantMemberCandidate[]` = `{userId, username, displayName, email}`, max 50, by username | `TENANT_MEMBERS` | Only users related to **this** tenant: an active workspace membership in one of its workspaces, or an inactive tenant row. The user must also be enabled, activated, not SYSTEM_ADMIN, and not already an active member (`TenantService.memberCandidates`). | 400 `QUERY_TOO_SHORT`; 404; 403 | – | – |
| `PUT /{tenantId}/members/{userId}` | `{role: "TENANT_ADMIN"\|"MEMBER"}` | `TenantMemberView` = `{tenantId, userId, role, active}`. **`username`, `displayName` and `email` are null in this response** (`TenantService`, private `TenantMemberEntity.view()`). | `TENANT_MEMBERS` | target must be eligible in this tenant (`TenantService.eligibility`) | 404 `TENANT_NOT_FOUND`; 403 `FORBIDDEN`; 400 `TENANT_ROLE_INVALID`; 404 `USER_NOT_FOUND` (unrelated, foreign, pending, SYSTEM_ADMIN not already a member, or unknown); 422 `USER_DISABLED` (related but `users.enabled=false`); **403 `SELF_GRANT_FORBIDDEN`** (actor == target, SYSTEM_ADMIN included); **409 `LAST_TENANT_ADMIN`** (demoting the last active TENANT_ADMIN) | – | `TENANT_MEMBER_SET {old:{role, active}, new:{userId, role}}` |
| `DELETE /{tenantId}/members/{userId}` (204) | – | – | `TENANT_MEMBERS` | path tenant | 404 `TENANT_MEMBER_NOT_FOUND` (not an active member, or foreign); **409 `LAST_TENANT_ADMIN`**; 404; 403 | – | `TENANT_MEMBER_REMOVED {userId, role}` |
| `POST /{tenantId}/users` (201) | `{username, displayName, email?, tenantRole = "MEMBER", workspaceId?, workspaceRole?}` | `ActivationLink` = `{userId, username, displayName, purpose: "ACTIVATION", token, expiresAt}` | `TENANT_MEMBERS` | path tenant. The workspace must belong to the tenant. | see note 4 below | – | `USER_CREATED`, `TENANT_MEMBER_SET`, `ADD_MEMBER` (if a workspace is given), `ACTIVATION_LINK_CREATED` |

Notes on the table:
1. **`POST` create, `firstAdmin` path.** One transaction (`CompanyBootstrapService.create`, `@Transactional`) creates the tenant, a pending LOCAL account (no password), its TENANT_ADMIN membership and a 24 h activation link. Any failure rolls back everything. Errors: 400 `VALIDATION_FAILED` (both `firstAdmin` and `firstAdminUserId`; blank `firstAdmin.username`; blank `displayName`), 400 `TENANT_SLUG_INVALID`, 400 `TENANT_NAME_INVALID`, 409 `TENANT_SLUG_TAKEN`, and the account errors of note 4. DONE: `T/organization/CompanyBootstrapTests.kt`.
2. **`POST` create, `firstAdminUserId` path.** The id must exist (400 `USER_NOT_FOUND`), and it becomes TENANT_ADMIN. **GAP (C1):** the code checks no enabled or activated state for that user (`TenantController.create`, `TenantService.create`). Without either field, the tenant is created with no admin, which is backward compatible. Slug normalization: trim, lower-case, regex `^[a-z0-9][a-z0-9-]{0,118}[a-z0-9]$`.
3. **Rename.** DONE and tested (`T/tenancy/FinalTenantStatusTests.kt`: ACTIVE paths, SUSPENDED 403 for a Tenant Admin / 200 for the platform operator, DELETED 404 for everybody).
4. **`POST /users` errors** (from `AccountService.createTenantUser`, `M/identity/Accounts.kt`): 400 `INVALID_USERNAME` (`^[a-z0-9][a-z0-9._-]{2,39}$` after trim and lower-case; no `oidc-` prefix), 400 `VALIDATION_FAILED` (displayName 1-160; `workspaceId` and `workspaceRole` must be sent together), 400 `INVALID_EMAIL`, 409 `EMAIL_TAKEN` (case-insensitive), 404 `TENANT_NOT_FOUND` (tenant DELETED, **SYSTEM_ADMIN included**), 400 `TENANT_ROLE_INVALID`, 400 `INVALID_ROLE`, 404 `WORKSPACE_NOT_FOUND`, 409 `USERNAME_TAKEN`. **No password is accepted. `system_admin` is always FALSE.**

### 3.1 Status transition semantics, as coded (`TenantService.setStatus`)

- Any status → any status is allowed: ACTIVE ↔ SUSPENDED, → DELETED, **DELETED → ACTIVE** and DELETED → SUSPENDED. DELETED is soft: the row stays, and it is **not terminal**.
- Setting the same status again writes `updated_at` and **audits again**. It is not a no-op.
- The DEFAULT tenant (`00000000-0000-0000-0000-000000000001`) can only be set to ACTIVE. Anything else gives 409 `DEFAULT_TENANT_PROTECTED`.
- **No session, membership or token side effect.** Enforcement happens only at request time (3.2).
- Tests: DONE for SYSTEM_ADMIN-only status changes, DEFAULT protection and suspend/reactivate (`T/tenancy/TenantAccessTests.kt:111-133`, `T/tenancy/TenantServiceTests.kt:30`). No test covers DELETED→ACTIVE or the same-status re-audit.

### 3.2 Tenant status semantics

| Route family | ACTIVE | SUSPENDED | DELETED |
|---|---|---|---|
| Workspace and project business routes (`forWorkspace` / `forProject`), ordinary member | allowed by matrix | **403 `TENANT_SUSPENDED`** (`AccessEvaluator.workspace` → private `tenantGates`) | **404 `WORKSPACE_NOT_FOUND`** (same) |
| Same routes, SYSTEM_ADMIN **acting through a membership** (legacy flag off) | allowed by matrix | **403 `TENANT_SUSPENDED`** (`AccessEvaluator.workspace`, membership branch) | **404** (same) |
| Same routes, SYSTEM_ADMIN that is **not a member** | `platformScope` only, so business routes give 403 | same, no gate | same, no gate |
| Organization / employee / position / grade **reads** (`*_VIEW`) | allowed | **allowed** | non-SYSTEM_ADMIN 404 `TENANT_NOT_FOUND`; SYSTEM_ADMIN 403 `FORBIDDEN` (platform scope only) |
| Organization **writes** (every `*_MANAGE` route, including employee create, enable and disable) | allowed | **403 `TENANT_SUSPENDED`** (extension `AccessService.org`, `M/organization/OrganizationControllers.kt:23-24` → `AccessService.requireTenantWritable`). Checked **after** the permission, so a plain member still sees 403 `FORBIDDEN`. | 404 `TENANT_NOT_FOUND` for every write (`requireTenantWritable`, also with the legacy flag). Unit restore also requires an ACTIVE tenant: `RESTORE_CONFLICT {reason: TENANT_INACTIVE}`. |
| Tenant admin routes (`GET /{t}`, rename, workspaces, members, candidates, users) | allowed | **allowed, unchanged** (`forTenant` does not check SUSPENDED), except **rename**, which a Tenant Admin cannot do in a SUSPENDED company (403 `TENANT_SUSPENDED`; the platform operator can). | non-SYSTEM_ADMIN 404. SYSTEM_ADMIN allowed, except `POST /users`, which gives 404 `TENANT_NOT_FOUND`. |
| `/auth/me` | normal | tenant listed in `tenants[]`. Its workspaces are listed with `permissions: []` (non-SYSTEM_ADMIN). Top-level `permissions` still carries the tenant role codes if it is the primary tenant (2.4). | tenant and its workspaces omitted (non-SYSTEM_ADMIN) |
| Public site (`PUBLIC_SITE`) | served | refused (TenantGate) | refused |

Status of the SUSPENDED rules:
- **DONE** for workspace routes (`T/tenancy/TenantAccessTests.kt:38-45`).
- **DONE and tested** for organization writes in a SUSPENDED tenant (`T/tenancy/FinalTenantStatusTests.kt` test 6a: 12 write routes answer 403 `TENANT_SUSPENDED`, reads 200).
- **GAP (C1, policy unchanged):** in a SUSPENDED tenant, the tenant-admin member and provisioning routes stay open.

SYSTEM_ADMIN member rules:
- A SYSTEM_ADMIN can never grant itself anything (403 `SELF_GRANT_FORBIDDEN`, `TenantService.setMember`).
- It is not a member candidate and cannot be added through `PUT members` unless it already is an active member (`TenantService.eligibility`).
- When it *is* a member, it holds exactly its member role's codes on top of `platformScope`, but never in a DELETED tenant (`AccessService.forTenant`). DONE: `T/organization/OrganizationAuthorizationTests.kt:148`.

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
- Account service and the legacy create, link and system-admin routes: `AccountService` / `AccountController` in `M/identity/Accounts.kt`.
- User admin routes: `M/admin/AdminUserController.kt` (C0-gated).
- A Tenant Admin can **never** grant SYSTEM_ADMIN and **never** reach `/api/v1/admin/users/**`: `AdminGuard` reads `system_admin AND enabled` live.

There is no dedicated "invite" e-mail. Invitation means the admin receives the one-time `ActivationLink` and delivers it.

**Token policy** (`AccountService.newToken`, `AccountService.find`, `AccountService.complete`):
- Each token is 32 random bytes, base64url-encoded. **Only its SHA-256 is stored** (`account_tokens`).
- TTL is 24 h. A token is single use: the `used_at` UPDATE is atomic.
- Issuing a new link deletes every unused link of that user.
- Tokens are validated against `^[A-Za-z0-9_-]{30,100}$`.
- Lookup requires `users.enabled AND auth_source='LOCAL'`.
- Password policy: 8-200 characters, letters **and** digits, at least 4 distinct characters, and must not contain the username.
- Completing the flow sets the hash and `activated_at`, and **deletes every session of the username** (`AccountService.complete`).
- The pending-token gap is described in section 1.

### 4.1 Session behaviour

Sessions are stored in Spring Session Redis (indexed by principal name = username). The default session timeout is 20 m (`backend/src/main/resources/application.yml`, `spring.session.timeout: 20m`), and login overrides it with `session.timeout-minutes`.

| Event | Existing sessions | Next request with the old cookie | Evidence |
|---|---|---|---|
| SYSTEM_ADMIN disables via `PATCH …/status` | deleted by username, inside the transaction **before commit** | **401 `AUTHENTICATION_REQUIRED`**: the session is gone, so the caller is anonymous (`SecurityConfiguration.kt:188-189`). It is **not** `ACCOUNT_DISABLED`. Login gives 401 `INVALID_CREDENTIALS`. | `AdminUserController.kt:89-93,106-110`; `T/admin/AdminApiTests.kt:62-65` (asserts 401 only) |
| Account disabled in the DB only (no session revocation) | kept | **401 `ACCOUNT_DISABLED`**; the session is invalidated | `ActiveUserFilter.kt:21-24`; `T/identity/AuthSecurityTests.kt:63-70` |
| SCIM deprovision (`active=false`) | deleted by username | 401 `AUTHENTICATION_REQUIRED` | `Scim.kt` (private `setActive` / `revoke`) |
| SYSTEM_ADMIN re-enables | nothing is resurrected | old cookie: 401. New login: 200. | `AdminUserController.kt:89-93`; `AdminApiTests.kt:68-69`. `T/tenancy/UserProvisioningHierarchyTests.kt:277` accepts 200 or 401 for the old session, so the result is not pinned. |
| `POST …/revoke-sessions` | deleted | 401 `AUTHENTICATION_REQUIRED` | `AdminUserController.kt:97-104`; `AdminApiTests.kt:73` |
| Activation or password reset completed | deleted | 401 `AUTHENTICATION_REQUIRED` | `AccountService.complete`. The test name `AccountActivationTests.kt:32` says "reset signs out", but nothing asserts it. |
| SYSTEM_ADMIN revoked | kept | admin routes: 403 `ADMIN_REQUIRED`; `forPlatform` routes: 403; `/auth/me` `systemAdmin=false`, `roles=["USER"]` | `AdminGuard.isAdmin` / `require` (`AdminSupport.kt:17-22`); `AuthController.me`; `AdminApiTests.kt:44` |
| Tenant membership deactivated (`DELETE members` / employee `disable`) | kept | tenant routes: 404 `TENANT_NOT_FOUND`; the tenant's workspaces: 404 `WORKSPACE_NOT_FOUND`; `/auth/me` omits them | `AccessEvaluator.workspace` (`tenantGates`), `AccessService.forTenant`; `T/tenancy/TenantAccessTests.kt:38` |
| Tenant role demoted (TENANT_ADMIN → MEMBER) | kept | 403 `FORBIDDEN` "Missing permission: …" | `TenantAccess.require` (`M/access/AccessService.kt`); `T/tenancy/TenantAdminViaRoleTests.kt` (service level only) |
| Tenant SUSPENDED | kept | section 3.2 | `TenantAccessTests.kt:45` |
| Tenant DELETED | kept | 404 for non-SYSTEM_ADMIN | `AccessEvaluator.workspace` (`tenantGates`), `AccessService.forTenant` |
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
| Last active TENANT_ADMIN can be neither demoted nor removed: 409 `LAST_TENANT_ADMIN` | `TenantService.setMember` / `TenantService.removeMember` | DONE (`TenantServiceTests.kt:39`, `UserProvisioningHierarchyTests.kt:225`). **GAP (C1):** the count is not row-locked, so two concurrent demotions can both pass. Untested. |
| No self-grant or self role change for anyone, SYSTEM_ADMIN included: 403 `SELF_GRANT_FORBIDDEN` | `TenantService.setMember` | DONE (`T/tenancy/PrivilegeEscalationTests.kt`, `UserProvisioningHierarchyTests.kt:246`) |
| Self-disable as an employee: 403 `SELF_GRANT_FORBIDDEN` | `EmployeeDirectoryService.setActive` | DONE |
| `DELETE members/{self}` | **no self check**. A Tenant Admin may remove itself while another TENANT_ADMIN exists. | as coded |
| Re-adding a platform-disabled account: 422 `USER_DISABLED` | `TenantController.setMember`, `EmployeeDirectoryService.setActive` | DONE (`UserProvisioningHierarchyTests.kt:265`) |
| Only users related to this tenant can be added; others get 404 `USER_NOT_FOUND` | `TenantService.eligibility` | DONE (`T/tenancy/TenantMemberDirectoryTests.kt`, `UserProvisioningHierarchyTests.kt:148`) |

**Workspace and project membership** (`M/member/MemberController.kt`, unchanged on this branch):
- Workspace members are managed with `MEMBER_MANAGE`, held only by WORKSPACE_ADMIN. 409 `LAST_ADMIN` protects the last WORKSPACE_ADMIN.
- Project members are managed with `APP_SHARE` (storage name `PROJECT_MEMBERS`). 409 `LAST_OWNER` protects the last OWNER.
- 403 `SELF_GRANT_FORBIDDEN`, 404 `USER_NOT_FOUND` (not in this tenant), 422 `USER_DISABLED`, 409 `ALREADY_MEMBER`, 404 `MEMBER_NOT_FOUND`.

---

## 6. ORGANIZATION / POSITION / GRADE

Prefix: `/api/v1/admin/tenants/{tenantId}`.

**Authorization order on every route** (extension `AccessService.org`, `M/organization/OrganizationControllers.kt`):
1. 401 if there is no session.
2. `forTenant`: a stranger or unknown tenant gets a safe 404 `TENANT_NOT_FOUND`.
3. `require(capability)`: 403 `FORBIDDEN` if missing.
4. For a `*_MANAGE` capability, `requireTenantWritable`: 403 `TENANT_SUSPENDED`.
5. The service runs. When no store is wired (flag `app.organization.persistence-enabled` off, the default), the route answers 501 `ORG_PERSISTENCE_NOT_AVAILABLE`, always after steps 1-4. With the flag on, a write whose lock wait exceeds `app.organization.structural-lock-timeout-ms` answers 503 `ORG_STRUCTURE_BUSY` (6.2 item 6).

Details for every route (DTOs, bodies, full error lists, order of checks): see the [org contract](organization-employee-contract.md), sections §2-§5.

### 6.1 Route summary (authoritative)

| METHOD PATH | PERMISSION | Key errors (beyond 401 / 404 `TENANT_NOT_FOUND` / 403 `FORBIDDEN` / 403 `TENANT_SUSPENDED` on writes / 501 when no store is wired / 503 `ORG_STRUCTURE_BUSY` on writes when a lock wait times out) | ETag |
|---|---|---|---|
| `GET /organization-unit-types?includeInactive=true` | ORG_STRUCTURE_VIEW | – | – |
| `GET /organization-unit-types/{typeId}` | ORG_STRUCTURE_VIEW | 404 `ORG_UNIT_TYPE_NOT_FOUND` | yes |
| `POST /organization-unit-types` (201) | ORG_STRUCTURE_MANAGE | 400 `VALIDATION_FAILED` / `INVALID_CODE`; 404 `ORG_UNIT_TYPE_NOT_FOUND` (a rule id); 409 `ORG_UNIT_TYPE_CODE_TAKEN` | yes |
| `PATCH /organization-unit-types/{typeId}` | ORG_STRUCTURE_MANAGE | 404; 409 `VERSION_CONFLICT`; a `rules` change is validated against the existing tree (D-C0-52): 409 `ORG_TYPE_RULE_VIOLATION{reason, unitId, existingStructure:true}` | yes |
| `POST /organization-unit-types/{typeId}/disable` and `/enable` | ORG_STRUCTURE_MANAGE | 404; 409 `VERSION_CONFLICT` | yes |
| `GET /organization-units?format=tree\|flat&includeArchived=false` | ORG_STRUCTURE_VIEW | 400 `VALIDATION_FAILED` (bad `format`). Tree nodes carry `directMemberCount` / `subtreeEmployeeCount` (D-C0-52; `null` when the store provides no counts); `flat` carries no counts. | – |
| `GET /organization-units/{unitId}` | ORG_STRUCTURE_VIEW | 404 `ORG_UNIT_NOT_FOUND`. Detail = `{unit, path, activeChildCount, activeMemberCount, directMemberCount, subtreeEmployeeCount}`. | yes (`unit.version`) |
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
| `GET /employees?q&organizationUnitId&includeDescendants&positionId&gradeId&active&userId&page&size&sort&dir` | EMPLOYEE_VIEW | 400 `VALIDATION_FAILED` / `QUERY_TOO_SHORT` / `OFFSET_TOO_LARGE` (`page * size > 10000`, `EmployeeDirectoryService.MAX_OFFSET`, D-C0-52); 404 `ORG_UNIT_NOT_FOUND` / `POSITION_NOT_FOUND` / `GRADE_NOT_FOUND` | – |
| `GET /employees/{userId}` | EMPLOYEE_VIEW | 404 `EMPLOYEE_NOT_FOUND` | **none** |
| `POST /employees` (201) | EMPLOYEE_MANAGE **+ TENANT_MEMBERS** | org contract §4, plus the provisioning errors of section 3 | none |
| `POST /employees/{userId}/disable` | EMPLOYEE_MANAGE **+ TENANT_MEMBERS** | 404 `EMPLOYEE_NOT_FOUND`; 403 `SELF_GRANT_FORBIDDEN`; 409 `LAST_TENANT_ADMIN` | none |
| `POST /employees/{userId}/enable` | EMPLOYEE_MANAGE **+ TENANT_MEMBERS** | 404; 422 `USER_DISABLED` | none |
| `GET /employees/{userId}/organization-memberships?includeInactive=false` | EMPLOYEE_VIEW | 404 `EMPLOYEE_NOT_FOUND` | – |
| `POST /employees/{userId}/organization-memberships` (201) | EMPLOYEE_MANAGE | 400 `VALIDATION_FAILED` (more than 20 active memberships per employee); 409 `EMPLOYEE_INACTIVE` / `ORG_UNIT_ARCHIVED` / `ORG_MEMBERSHIP_EXISTS`; 404 `ORG_UNIT_NOT_FOUND` | yes |
| `PATCH /employees/{userId}/organization-memberships/{membershipId}` | EMPLOYEE_MANAGE | 404 `ORG_MEMBERSHIP_NOT_FOUND`; 400 (`primary=false`); 409 `EMPLOYEE_INACTIVE` / `VERSION_CONFLICT` | yes |
| `DELETE /employees/{userId}/organization-memberships/{membershipId}?expectedVersion=n` | EMPLOYEE_MANAGE | 404; **409 `EMPLOYEE_ORG_HAS_POSITIONS{activePositionCount}`**; 409 `VERSION_CONFLICT` | yes |
| `GET /employees/{userId}/positions?includeInactive=false` | EMPLOYEE_VIEW | 404 `EMPLOYEE_NOT_FOUND` | – |
| `POST /employees/{userId}/positions` (201) | EMPLOYEE_MANAGE | 400 (`membershipId` / `positionId` missing); 400 `VALIDATION_FAILED` (the membership already holds 20 active positions: the limit is **per membership**); 404 `ORG_MEMBERSHIP_NOT_FOUND` / `POSITION_NOT_FOUND` / `GRADE_NOT_FOUND`; 409 `ORG_MEMBERSHIP_INACTIVE` / `POSITION_DISABLED` / `GRADE_DISABLED` / `POSITION_ASSIGNMENT_EXISTS` / `EMPLOYEE_INACTIVE` | yes |
| `PATCH /employees/{userId}/positions/{employeePositionId}` | EMPLOYEE_MANAGE | 404 `POSITION_ASSIGNMENT_NOT_FOUND`; 400; 409 `VERSION_CONFLICT` / `EMPLOYEE_INACTIVE` | yes |
| `DELETE /employees/{userId}/positions/{employeePositionId}?expectedVersion=n` | EMPLOYEE_MANAGE | 404; 409 `VERSION_CONFLICT` | yes |

### 6.2 Frozen semantics

Source: D-C0-43, as reconciled (CF-1..CF-4), CF-4 amended by D-C0-52. Each item was verified in code.

1. **No EmployeeProfile.** An employee is `tenant_members JOIN users`, and `EmployeeDto.active` = `tenant_members.active`. There is no `PATCH /employees/{id}`, and `POST /employees` creates a **new** account only (KDoc of `EmployeeDto` in `OrganizationContract.kt`, KDoc of `EmployeeDirectoryService`).
2. **Unit code is canonical.** It is required, trimmed, validated against `^[A-Za-z0-9][A-Za-z0-9._-]{0,59}$` (otherwise 400 `INVALID_CODE`), then `uppercase(Locale.ROOT)` (`OrgRules.UNIT_CODE`, `OrgRules.unitCode`). It is unique **among non-archived siblings**: same tenant and parent, with all roots treated as siblings. Archiving frees the code (`OrganizationRepositories.kt` KDoc, rule 3). Type codes are lower-case (`^[a-z0-9][a-z0-9_-]{0,39}$`). Position and grade codes are unique per tenant, case-insensitively.
3. **An EmployeePosition is held within an active membership.** `membershipId` is required, and `organizationUnitId` is derived from it (`EmployeeDirectoryService.addPosition` → `activeMembership`, private `insertAssignment`). Active uniqueness is **(membershipId, positionId)**; the grade is an attribute, not part of the identity (`OrganizationRepositories.kt` rule 3, key `assignment`; `EmployeePositionRepository.insert` / `update`).
4. **409 `EMPLOYEE_ORG_HAS_POSITIONS`.** A membership that still holds active positions cannot end. Nothing cascades (`EmployeeDirectoryService.removeMembership`, `MembershipHasPositions`).
5. **Soft archive and restore.** A unit has `active` and `archivedAt`. There is no hard delete. Archive refuses while active children or members exist. Restore re-validates tenant, type, parent, rules and code (`OrganizationUnitService.restore`). Types, positions and grades use enable / disable.
6. **The structural lock is used only by the STRUCTURAL operations** (CF-4 as amended by D-C0-52). The `repos.lock.acquire(tenantId)` calls in `M/organization/**` are exactly four, all in `OrganizationServices.kt`:
   - `OrganizationUnitService.create` (first step of the transaction);
   - `OrganizationUnitService.move` (first step);
   - `OrganizationUnitService.restore` (first step);
   - `OrganizationUnitTypeService.update`, **only when `rules` is present** (a rule change is then also validated against the existing tree, private `checkExistingStructure`).

   **Never taken** by: unit-type create, rename / icon change (a PATCH without `rules`), enable / disable; unit update and archive; positions and grades; employee create / enable / disable; memberships (add / update / primary / end); position assignments (add / update / primary / end); the employee directory and every read (`EmployeeDirectoryService` contains no `lock` call). These use the ordinary transaction, constraints, row locks and optimistic versions. Pinned by `T/organization/OrganizationApiContractTests.kt` test "CF-4 the tenant structural lock is taken by the STRUCTURAL operations only …" (acquisition count per operation) and, on PostgreSQL, by C3's `OrganizationIntegrationWiringTests` / `OrgStructuralLockTests`.

   **`503 ORG_STRUCTURE_BUSY`** (D-C0-52, implemented by C3 in `OrgDb`): every organization write of the PostgreSQL store sets a transaction-local `lock_timeout` = `app.organization.structural-lock-timeout-ms` (default 5000). A wait for the structural lock **or a row lock** that exceeds it (SQLSTATE 55P03, the only failure translated) answers HTTP 503, code `ORG_STRUCTURE_BUSY`, `details = {retryable: true, retryAfterSeconds: N}`, header `Retry-After: N`, `N = max(1, ceil(timeoutMs / 1000))` (`OrgDb.retryAfterSeconds`). Nothing was applied; the client retries the same request. It exists only with the flag on (never with the 501 path).
7. **Atomic move seam.** `OrganizationUnitRepository.move` is one versioned CAS that changes only the moved row's parent. The store re-checks for cycles and sibling codes (KDoc of `OrganizationUnitRepository.move`). The service validates `maxDepth` for every descendant (`OrganizationUnitService.move`).
8. **One active primary membership per (tenant, user)**, and one active primary position. `setPrimary` is atomic. A lost race answers 409 `VERSION_CONFLICT` (`EmployeeDirectoryService` private `lostRace`, `makePrimaryMembership`, `makePrimaryPosition`).
9. **`description` is nullable** for positions and grades (`PositionDto.description`, `GradeDto.description`).
10. **Limits (C1 contract decision, not a C0 decision).** `MAX_ACTIVE_POSITIONS_PER_MEMBERSHIP = 20` (code constant `EmployeeDirectoryService.MAX_POSITIONS`, counted on `membershipId`; another membership of the same employee has its own independent 20) and at most 20 ACTIVE memberships per employee (`EmployeeDirectoryService.MAX_MEMBERSHIPS`). Both answer 400 `VALIDATION_FAILED` (the 21st), in `POST /employees` (each nested `positions` list ≤ 20) and in `POST /employees/{userId}/positions` / `…/organization-memberships` (`EmployeeDirectoryService.create`, `addPosition`, `addMembership`). Check-then-insert: best-effort under concurrency (org contract §11). DONE for the position limit: `T/organization/OrganizationLimitsTests.kt` (route + nested create form); the membership limit has no dedicated test.
11. **Organization data never grants a permission.** No code in `access/**` or `identity/**` reads organization data. Relation type, position and grade are business data (KDoc of `OrganizationMembershipDto`, `PermissionMatrix` KDoc in `Permission.kt`).

Consistency with the org contract: the former conflicts (suspended-company writes, number of kit tests) are **resolved** in [organization-employee-contract.md](organization-employee-contract.md): org writes in a SUSPENDED company answer 403 `TENANT_SUSPENDED` (§11), and the kit has **13** `@Test` methods (`T/organization/OrganizationRepositoryContractKit.kt`, §10).

---

## 7. CANONICAL PERMISSION MATRIX

Source: `M/access/Permission.kt`.

**Canonical vocabulary.** `PermissionCodes.CANONICAL` has 21 codes (`M/access/Permission.kt:47-52`):
- `APP_VIEW APP_USE APP_EDIT APP_PUBLISH APP_SHARE`
- `DATA_SOURCE_VIEW DATA_SOURCE_MANAGE QUERY_EXECUTE DATA_MUTATE ACTION_EXECUTE WORKFLOW_EXECUTE WORKFLOW_MANAGE`
- `TENANT_MANAGE TENANT_MEMBERS MEMBER_MANAGE`
- `ORG_STRUCTURE_VIEW ORG_STRUCTURE_MANAGE EMPLOYEE_VIEW EMPLOYEE_MANAGE POSITION_GRADE_VIEW POSITION_GRADE_MANAGE`

**Storage aliases.** `PROJECT_READ`=`APP_VIEW`, `PROJECT_EDIT`=`APP_EDIT`, `PROJECT_PUBLISH`=`APP_PUBLISH`, `PROJECT_MEMBERS`=`APP_SHARE` (temporary).

**Internal codes, never exposed:** `PROJECT_SETTINGS`, `PROJECT_DELETE`, `PROJECT_CREATE`, `AUDIT_READ`, `REGISTRY_WRITE`.

**Exact canonical sets (frozen; as `/auth/me` returns them, i.e. sorted by `PermissionCodes.canonicalCodesOf`):**
- **TENANT_ADMIN** (`PermissionMatrix.tenantRoles["TENANT_ADMIN"]`), exactly eight codes: `EMPLOYEE_MANAGE, EMPLOYEE_VIEW, ORG_STRUCTURE_MANAGE, ORG_STRUCTURE_VIEW, POSITION_GRADE_MANAGE, POSITION_GRADE_VIEW, TENANT_MANAGE, TENANT_MEMBERS`. Pinned by `T/tenancy/TenantAdminAuthMeExactTests.kt` (matrix constant + HTTP `/auth/me`) and `T/tenancy/UserProvisioningHierarchyTests.kt` test N.
- **Tenant MEMBER**: none.
- **platformScope** (non-member SYSTEM_ADMIN, default flag; `PermissionMatrix.platformScope`), exactly two codes: `TENANT_MANAGE, TENANT_MEMBERS`. No organization code, no `MEMBER_MANAGE`, no business code.

**Not canonical (T_ORG_MANAGE_CANONICAL = NO).** `ORG_MANAGE`, `T-ORG-MANAGE`, `ORG_ADMIN` and `DEPARTMENT_MANAGE` are **not** permissions: none is in `PermissionCodes.CANONICAL` or in the `Permission` enum, none is granted, and none may be promoted or invented. The organization authority is exactly the six codes `ORG_STRUCTURE_VIEW/MANAGE`, `EMPLOYEE_VIEW/MANAGE`, `POSITION_GRADE_VIEW/MANAGE`. (`DEPARTMENT_MANAGER` is an unrelated approver kind of the app-definition / workflow vocabulary, e.g. `features/studio/builder/WorkflowEditor.tsx`; it is not a permission.)

**T_ORG_MANAGE closure (D-C0-51).** C0 retired `ORG_MANAGE` on the baseline `19fd4c2`. Each former placeholder was re-checked there (by symbol, not line); C1 edits none of these files.

Already FIXED by C0 / C5 on this baseline:

| Where | Status on `19fd4c2` |
|---|---|
| `features/admin/organization.ts`, `export type Need` | FIXED: the union is `"TENANT_MEMBERS" \| "TENANT_MANAGE"` + the six canonical organization codes; no `ORG_MANAGE` |
| `tests/guards/org-guards.allow.json` | FIXED: `"allow": []` (empty since D-C0-51) |
| `tests/guards/guards.test.mjs`, test "REAL FILES (copy) MUTATED: flipping the org adapter's permission need …" | FIXED: the mutation now rewrites `"ORG_STRUCTURE_VIEW"` into `"ORG_MANAGE"` and expects `ORG-FAIL-CLOSED-OBSOLETE`. The remaining `ORG_MANAGE` strings in that file are synthetic negative fixtures (expected findings), not placeholders |
| guards `tests/guards/org-source-guards.mjs` (`OBSOLETE`) and `tests/guards/permission-mirror.mjs` (`OBSOLETE`, 9th static guard) | NEW: `ORG_MANAGE` and its aliases are a finding in all production code; the TS mirror, the backend sets and the contract must agree |
| `docs/parallel/BLOCKERS.md`, row `T-ORG-MANAGE` | CLOSED (D-C0-51) |
| `docs/parallel/c0/FRONTEND_GATE.md`, step "Remove the `ORG_MANAGE` entry …" | DONE (D-C0-51) |
| `docs/parallel/DECISIONS.md`, D-C0-45 / D-C0-46 | history kept; D-C0-51 item 5 is the closing note |
| `docs/contracts/v2/tenant-permission.md` section **5b** (organization permissions) and 5c | FIXED: the six codes, "`ORG_MANAGE` obsolete", scope separation |
| `packages/types/src/contract/v2/permissions.ts` | FIXED: `ORG_PERMISSION_CODES` (6), `PORTAL_PERMISSION_CODES` (`MEMBER_MANAGE`), `CANONICAL_PERMISSION_CODES` (21); `PERMISSION_CODES` deliberately stays the 14 AppDefinition codes |

Genuinely REMAINING on this baseline:

| Where | What | Owner / action |
|---|---|---|
| `docs/contracts/v2/tenant-permission.md` section 4, row `users.system_admin` (SYSTEM_ADMIN) | says `platformScope` = `TENANT_MANAGE, TENANT_MEMBERS, MEMBER_MANAGE, PROJECT_CREATE`; the code (`PermissionMatrix.platformScope`) is exactly `TENANT_MANAGE, TENANT_MEMBERS` | **C0** (contract) |
| `docs/contracts/v2/tenant-permission.md` section 5, paragraph "`TENANT_MANAGE` and `TENANT_MEMBERS` are part of the vocabulary …" | still lists `MEMBER_MANAGE` among constants "never listed to clients", contradicting the same file's section 5 lead-in and 5b (and `PermissionCodes.CANONICAL`, which contains `MEMBER_MANAGE`) | **C0** (contract) |
| `scripts/provisioning-e2e.mjs`, check "B /auth/me: not a system admin; the tenant permissions are exactly TENANT_MANAGE + TENANT_MEMBERS …" | asserts the old 2-code TENANT_ADMIN list; `/auth/me.permissions` of a Tenant Admin is the eight codes below (and `tenants[].permissions` of that tenant carries the eight, 2.4a) | **C0**: assert the eight codes |
| `packages/permissions/src/canonical.ts`, module constant `CANONICAL` (= `new Set(PERMISSION_CODES)`, used by `resolvePermissions`) | drops the six organization codes and `MEMBER_MANAGE` | **C5** (D-C0-51 item 5 handoff): resolve against `CANONICAL_PERMISSION_CODES` when the organization is wired |

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
- **Effective project permissions** = workspace role set ∪ project role set (`AccessEvaluator.project`), where the project role counts only through an ACTIVE workspace role or the legacy bypass (2.3). For an ARCHIVED project, this is cut down to {APP_VIEW (+AUDIT_READ)} (same function).
- **Tenant permissions** come only from `tenant_members` (plus `platformScope` for a SYSTEM_ADMIN). They are never combined with workspace or project sets.

**Independence rules** (frozen):
1. `APP_VIEW` does not imply `APP_USE`, `APP_EDIT` or `APP_PUBLISH`. `APP_EDIT` does not imply `APP_PUBLISH`. Every check requires its exact constant; no code path derives one from another.
2. Organization membership, relation type (MEMBER / MANAGER / HEAD / …), position and grade grant **nothing**. `access/**` never reads organization data. Tested by `T/organization/OrganizationAuthorizationTests.kt:71` (WORKSPACE_ADMIN, SYSTEM_ADMIN) and `T/tenancy/FinalPermissionSemanticsTests.kt` 4c (a plain employee with MANAGER / HEAD relations, a position and a grade gets no `APP_*`, no project visibility, no organization authority).
3. **Only `PermissionMatrix` grants.** Role strings are inputs to the matrix lookup, never decisions.
4. **The tenant boundary always wins.** The tenant is derived from the resource (`TenantResolver`), never from request input, and a foreign id is a safe 404 (section 8).
5. WORKSPACE_ADMIN holds no `TENANT_*` or org code. SYSTEM_ADMIN holds no org code and no business code by default. A Tenant Admin holds no workspace or project code by being Tenant Admin (`T/tenancy/TenantAdminViaRoleTests.kt`, `OrganizationAuthorizationTests.kt:71`, `UserProvisioningHierarchyTests.kt:353`).

**Where the structure couples permissions.** These are stated honestly. They are not grants.
- `forProject` answers **404 `PROJECT_NOT_FOUND` unless `APP_VIEW`** (`PROJECT_READ`) is held (`AccessEvaluator.project`). APP_USE without APP_VIEW therefore cannot be expressed.
- **Every project role bundles `APP_USE`** (`PermissionMatrix.projectRoles`, `Permission.kt:107-112`). APP_VIEW without APP_USE occurs only for ARCHIVED projects.
- TEST-mode runtime calls require `APP_EDIT` in place of, or in addition to, `APP_USE`: `wiring/AppRuntimeDataController.kt` (C3) and `access/adapters/AccessPort.kt`.

**Role-name checks that remain inside `AccessService`.** These are internal visibility, not controller authorization.
- `AccessContext.seesAllProjects = systemAdminBypass || workspaceRole == "WORKSPACE_ADMIN"` (`M/access/AccessService.kt`). It is two disjuncts: the legacy-bypass flag and the WORKSPACE_ADMIN role name. It is used only to widen the project **list** (`ProjectController.list`, `M/project/ProjectController.kt:94`). Every project call still goes through `forProject`. See 2.3a limit 3 for the `EXISTS` clause used when it is false.
- The legacy bypass in `forTenant` selects the TENANT_ADMIN set by name (`AccessService.forTenant`).
- `MeTenancy.workspacePermissions("ADMIN")` uses `"ADMIN"` as a display placeholder for a non-member SYSTEM_ADMIN (`MeTenancyService.workspacePermissions`).

---

## 8. DENIAL SEMANTICS

| Status | When | Codes |
|---|---|---|
| **401** | no session | `AUTHENTICATION_REQUIRED` (`SecurityConfiguration.kt:188-189`) |
| 401 | the session's account is disabled or deleted | `ACCOUNT_DISABLED` (`ActiveUserFilter.doFilterInternal`, `AccessEvaluator.requireEnabledAccount`) |
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
7. Store availability (501 when no store is wired); with the store wired, a write whose lock wait times out answers 503 `ORG_STRUCTURE_BUSY` (nothing applied).

A stranger therefore never learns whether a tenant exists, nor its status.

**Exceptions.** `PATCH /tenants/{id}/status`, `GET /tenants` and `POST /tenants` answer 403 `ADMIN_REQUIRED` before any lookup, for any id. A plain MEMBER of a tenant receives 403, not 404, because it is of the tenant.

**Error code catalogue for these areas:**
- **400:** `VALIDATION_FAILED`, `TENANT_SLUG_INVALID`, `TENANT_NAME_INVALID`, `TENANT_STATUS_INVALID`, `TENANT_ROLE_INVALID`, `INVALID_ROLE`, `INVALID_USERNAME`, `INVALID_EMAIL`, `WEAK_PASSWORD`, `QUERY_TOO_SHORT`, `INVALID_CODE`, `OFFSET_TOO_LARGE` (employee directory, D-C0-52), `USER_NOT_FOUND` (tenant create only)
- **401:** `AUTHENTICATION_REQUIRED`, `ACCOUNT_DISABLED`, `INVALID_CREDENTIALS`
- **403:** `FORBIDDEN`, `ADMIN_REQUIRED`, `TENANT_SUSPENDED`, `SELF_GRANT_FORBIDDEN`, `CSRF_INVALID`
- **404:** the list above
- **409:** `TENANT_SLUG_TAKEN`, `DEFAULT_TENANT_PROTECTED`, `LAST_TENANT_ADMIN`, `LAST_ADMIN`, `LAST_OWNER`, `ALREADY_MEMBER`, `USERNAME_TAKEN`, `EMAIL_TAKEN`, `CANNOT_DISABLE_SELF`, `CANNOT_CHANGE_SELF`, `LAST_SYSTEM_ADMIN`, `NOT_ACTIVE`, `NOT_LOCAL_ACCOUNT`, `ACCOUNT_DISABLED` (activation-link)
- **409, organization** (org contract §9): `VERSION_CONFLICT{currentVersion}`, `ORG_CYCLE`, `ORG_TYPE_RULE_VIOLATION{reason}`, `ORG_UNIT_HAS_CHILDREN`, `ORG_UNIT_HAS_MEMBERS`, `ORG_UNIT_ARCHIVED`, `RESTORE_CONFLICT{reason}`, `ORG_UNIT_CODE_TAKEN`, `ORG_UNIT_TYPE_CODE_TAKEN`, `ORG_UNIT_TYPE_DISABLED`, `POSITION_CODE_TAKEN`, `GRADE_CODE_TAKEN`, `POSITION_DISABLED`, `GRADE_DISABLED`, `ORG_MEMBERSHIP_EXISTS`, `ORG_MEMBERSHIP_INACTIVE`, `EMPLOYEE_ORG_HAS_POSITIONS`, `POSITION_ASSIGNMENT_EXISTS`, `EMPLOYEE_INACTIVE`
- **410:** `LINK_INVALID`
- **422:** `USER_DISABLED`
- **428:** `CONFIRMATION_REQUIRED`
- **429:** `RATE_LIMITED`
- **501:** `ORG_PERSISTENCE_NOT_AVAILABLE` (no store wired: flag `app.organization.persistence-enabled` off, the default)
- **503:** `ORG_STRUCTURE_BUSY` (organization write, lock wait > `app.organization.structural-lock-timeout-ms`; `details.retryable=true`, `details.retryAfterSeconds`, header `Retry-After`; D-C0-52)

The error body is `{code, message, details?}`.

---

## 9. C5 INPUT (frontend-ready)

General rules:
- Gate every screen on the **canonical code** named here, as listed by `/auth/me`. Never use a role string.
- The server re-checks every call. Treat 403 as "hide or disable", and 404 as "gone or not yours".
- Show `NOT_READY` on 501 `ORG_PERSISTENCE_NOT_AVAILABLE` (the store is not wired: flag off, the default).
- On 503 `ORG_STRUCTURE_BUSY`, retry the same request after `Retry-After` seconds (same `expectedVersion`; nothing was applied). On 400 `OFFSET_TOO_LARGE`, narrow the directory search instead of paging deeper.
- C0's frozen wiring sheet for these routes is `docs/parallel/c0/ORGANIZATION_API_CONTRACT_FOR_C5.md` (D-C0-52); it agrees with this section.

| Screen | Routes | Gate | Pagination / version |
|---|---|---|---|
| Session and portal routing | `GET /api/v1/auth/me`, `POST /auth/login`, `POST /auth/logout`, `GET /auth/csrf` | section 2.5 | – |
| Studio admission | `/auth/me` `workspaces[].permissions` / `projectScopes[].permissions` | `APP_VIEW` in any row | – |
| Tenant list / create / status | `GET` / `POST /api/v1/admin/tenants`, `PATCH /{t}/status` | `platformScope == true` | not paginated; no version |
| Tenant rename | `PATCH /api/v1/admin/tenants/{t} {name}` | `TENANT_MANAGE` | no version, no ETag; idempotent |
| Tenant membership | `GET /{t}/members`, `GET /{t}/member-candidates?q`, `PUT /{t}/members/{u} {role}`, `DELETE /{t}/members/{u}` | `TENANT_MEMBERS` | not paginated (candidates max 50); no version |
| Tenant user provisioning | `POST /{t}/users` | `TENANT_MEMBERS` | – |
| Account lifecycle (global) | `GET /api/v1/admin/users`, `GET /{id}`, `PATCH /{id}/status {enabled}`, `POST /{id}/revoke-sessions`, `POST /{id}/activation-link`, `POST /{id}/system-admin {grant, confirm:true}` | `platformScope == true` | `PageDto {items,total,page,size}`; `size` is clamped to 1-100 (default 25); no version |
| Employee directory | `GET /{t}/employees?…`, `GET /{t}/employees/{u}` | `EMPLOYEE_VIEW` | `{items,total,page,size}`; `page` 0-based; `size` 1-100 (default 25); **400 if out of range** (no clamping): `VALIDATION_FAILED`, or `OFFSET_TOO_LARGE` when `page * size > 10000`. Employees have **no version and no ETag**. |
| Employee create / enable / disable | `POST /{t}/employees`, `POST /{t}/employees/{u}/disable` and `/enable` (no body) | `EMPLOYEE_MANAGE` **and** `TENANT_MEMBERS` | – |
| Memberships / held positions | `…/employees/{u}/organization-memberships`, `…/employees/{u}/positions` | read: `EMPLOYEE_VIEW`; write: `EMPLOYEE_MANAGE` | `expectedVersion` in the body; for `DELETE`, the query `?expectedVersion=n`; ETag = version |
| Org tree | `GET /{t}/organization-units?format=tree\|flat&includeArchived` | `ORG_STRUCTURE_VIEW` | not paginated; no ETag on lists; tree nodes carry `directMemberCount` / `subtreeEmployeeCount` |
| Unit detail | `GET /{t}/organization-units/{id}` → `{unit, path[], activeChildCount, activeMemberCount, directMemberCount, subtreeEmployeeCount}` | `ORG_STRUCTURE_VIEW` | ETag = `unit.version` |
| Unit types | `/{t}/organization-unit-types[...]` | read: `ORG_STRUCTURE_VIEW`; write: `ORG_STRUCTURE_MANAGE` | ETag; `expectedVersion` |
| Unit create / edit / move / archive / restore | `POST`, `PATCH /{id}`, `POST /{id}/move \| archive \| restore` | `ORG_STRUCTURE_MANAGE` | `expectedVersion` required; 409 `VERSION_CONFLICT` → reload using `details.currentVersion` |
| Positions / grades | `/{t}/positions[...]`, `/{t}/grades[...]` | read: `POSITION_GRADE_VIEW`; write: `POSITION_GRADE_MANAGE` | ETag; `expectedVersion` |

Version rule: the ETag of a record and its `version` field are both the `expectedVersion` of the next write. Versions start at 0 and increase by 1 on each applied write.

**Mismatches C5 must adapt.** These come from `inv-C` §6 and were re-checked against the code.
1. **There is no `ORG_MANAGE`.** Gate on `ORG_STRUCTURE_*`, `EMPLOYEE_*` and `POSITION_GRADE_*`. DONE on the baseline (D-C0-51): `features/admin/organization.ts` `type Need` carries the six codes, the allow-list is empty, T-ORG-MANAGE is closed (section 7, "T_ORG_MANAGE closure").
2. **A SYSTEM_ADMIN sees 403 on every org route.** Its `/auth/me` `permissions` is exactly `TENANT_MANAGE, TENANT_MEMBERS`. Do not offer org screens on `TENANT_MEMBERS` or `platformScope`. Employee enable / disable needs `EMPLOYEE_MANAGE` + `TENANT_MEMBERS`. `canToggleStatus = scope.platform` is wrong.
3. **Flat vs tree.** The list defaults to `format=tree` (`[{unit, children}]`). Send `?format=flat` for `OrgUnit[]`. Archived units are hidden unless `includeArchived=true`.
4. **`active` + `archivedAt` replace `enabled`** on units. Unit `typeId` and `code` are **required**. `code` comes back upper-case.
5. **Counts.** `format=flat` carries none. Tree nodes carry `directMemberCount` / `subtreeEmployeeCount` (D-C0-52; `null` only when the store provides no counts). The detail carries `activeChildCount` / `activeMemberCount` (C1, archive blockers) plus the same two counts.
6. **There is no delete.** Use `POST …/{id}/archive {expectedVersion}` and `/restore`. Unit PATCH accepts `{name?, code?, sortOrder?, metadata?, expectedVersion}`. The type is immutable, and the parent changes only through `move {newParentId (key required; null = root), expectedVersion, sortOrder?}`.
7. **Unit types.**
   - Parent / child rules are nested in `rules{allowedParentTypeIds?, allowedChildTypeIds?, allowRoot?, maxDepth?}`.
   - `[]` means **root only** for parents and **leaf** for children. Only `null` means "anywhere".
   - Type codes are **lower-case**: stop upper-casing them (`features/admin/organization.ts`, adapter method `createOrganizationUnitType`, still `code.trim().toUpperCase()` on the baseline).
   - A `PATCH` that changes `rules` can answer 409 `ORG_TYPE_RULE_VIOLATION{reason, unitId, existingStructure:true}` when the existing tree already violates the new rule.
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
13. **The `/auth/me` type** (`packages/types/src/index.ts`) lacks `projectScopes` (no occurrence under `packages/` on the baseline). Add it, and admit to Studio per section 2.5 (`packages/permissions/src/index.ts` `studio.build`, `packages/permissions/src/canonical.ts` `canViewStudioIn`).
14. **`resolvePermissions` drops the organization codes.** It filters against the module constant `CANONICAL` = `PERMISSION_CODES` (`packages/permissions/src/canonical.ts`), the 14 AppDefinition codes. The contract and the TS mirror are already extended (D-C0-51: `ORG_PERMISSION_CODES`, `CANONICAL_PERMISSION_CODES` in `packages/types/src/contract/v2/permissions.ts`); C5 must resolve org gates against `CANONICAL_PERMISSION_CODES` (D-C0-51 item 5).
15. **`tenant.members` is granted by role name** (`packages/permissions/src/index.ts`, the `tenant.members` capability: `me.tenantRole === "TENANT_ADMIN" || me.tenants?.some(t => t.role === "TENANT_ADMIN") || …`). Gate it on `TENANT_MEMBERS` only.
16. **Tenant rename and status.** Rename is `PATCH /{t} {name}` gated on `TENANT_MANAGE`. Status is platform-only. Show `tenants[].status` (SUSPENDED); org writes there answer 403 `TENANT_SUSPENDED`.

---

## 10. C3 INPUT and C0 INPUT

### 10.1 C3: seams (DELIVERED, D-C0-52)

**C3_INPUT: nothing outstanding from C1.** C3 implemented every seam of `M/organization/OrganizationRepositories.kt` on PostgreSQL (`M/data/org/**`) and V32; C0 imported it (D-C0-52) and wired it behind `app.organization.persistence-enabled` (default `false`). The binding rules stay those of the header KDoc of `OrganizationRepositories.kt` (rules 1-6, 4b, 4c) and of the org contract §8; they are pinned on PostgreSQL by the unchanged C1 kit.

What exists on the baseline (verified by file / symbol):
- **Beans** (`OrganizationPersistenceConfiguration`, only with the flag on): the seven repositories, `TenantStructuralLock` (`PostgresTenantStructuralLock`: `pg_advisory_xact_lock(namespace, hashtext(tenant))`, transaction-scoped, re-entrant, `IllegalStateException` without a transaction, bounded wait) and the C0 counts seam `OrganizationEmployeeCounts`. Every bean is optional at boot; a missing one answers 501.
- **Conformance kit:** `PostgresOrganizationConformanceTest` subclasses `T/organization/OrganizationRepositoryContractKit.kt` unchanged (13 / 13 per D-C0-52 item 8).
- **C3 PostgreSQL tests** (the list of org contract §10): `OrgStructuralLockTests` (lock semantics, tenant independence at repository level, racing moves never build a cycle), `OrgRaceTests` (archive vs insert, end membership vs position insert, setPrimary races, concurrent sibling-code / membership / assignment inserts, transaction participation, bounded row-lock wait → 503, maxDepth re-check inside create / restore), `OrganizationIntegrationWiringTests` (structural operations wait for the lock, non-structural never do, the two MAX_DEPTH races, `ORG_STRUCTURE_BUSY`, directory bound, counts, rule change against the existing tree), `OrganizationOnPostgresApiTests` (the API on PostgreSQL; 501 without the flag), `OrgSchemaTests`, `OrgV32FlywayTests`, `OrgBenchmarkTests`.
- **V32:** `backend/src/main/resources/db/migration/V32__dynamic_organization.sql` (six tables, `tenant_id NOT NULL` + composite FKs, `version BIGINT NOT NULL DEFAULT 0` with `CHECK (version >= 0)`, sibling-code partial unique index `NULLS NOT DISTINCT WHERE deleted_at IS NULL`). The C1 V32 content requirements of earlier revisions of this file are superseded by the shipped, immutable file (MIGRATION_LEDGER row V32).

**Locking, as shipped:** the advisory lock is taken by the four structural operations of 6.2 item 6 (service side) and by nothing else; inserts take the referenced row `FOR SHARE`, archive and end take `FOR UPDATE`; every lock wait is bounded (503 `ORG_STRUCTURE_BUSY`).

### 10.2 C0: what to record

These are coordination files. C1 does not edit them. Status re-checked on the baseline `19fd4c2`:

1. **DECISIONS entry: DONE** (D-C0-51: six organization codes replace `ORG_MANAGE`, TENANT_ADMIN only, `/auth/me.projectScopes`, org writes 403 `TENANT_SUSPENDED`, CF-1..CF-4; D-C0-52: CF-4 amended to every structural operation). **Still open:** tenant rename `PATCH /api/v1/admin/tenants/{t}` (`TenantController.rename`, audit `TENANT_UPDATED`) is in the code but recorded in neither DECISIONS nor `docs/contracts/**`.
2. **`docs/contracts/v2/tenant-permission.md`: six codes DONE** (section 5b, 5c; TS mirror `ORG_PERMISSION_CODES` / `CANONICAL_PERMISSION_CODES`). **Still open:** the section 4 SYSTEM_ADMIN row (`platformScope` is only `TENANT_MANAGE, TENANT_MEMBERS`) and the section 5 sentence that puts `MEMBER_MANAGE` among the constants "never listed to clients" (section 7, "Genuinely REMAINING"). `app/definition/PermissionCodes.ALL` (C2) stays the 14-code `PermissionDef` vocabulary.
3. **BLOCKERS: DONE** for H-C1-17 (contract and persistence integrated, flag OFF, frontend OPEN C5), H-C1-04 (backend DONE, C5 OPEN) and T-ORG-MANAGE (CLOSED). **To record when this branch is imported:** the `/auth/me` cost finding of D-C0-51 item 9(a) ("one `forProject()` per project membership") is closed by this branch (2.3a: constant 6 statements; 10.3 gap 6).
4. **Ledger row V32: DONE** (ALLOCATED · CREATED / IMPORTED, C3 source `fe35eb075bf1`; V31 VOID; V33 not allocated).
5. **BOARD "Migration requests": DONE** (V32 row ALLOCATED, D-C0-52).
6. **`scripts/provisioning-e2e.mjs`:** assert the eight TENANT_ADMIN codes (section 7).

### 10.3 Known gaps owned by others

Unless marked CLOSED, none of these is fixed on this branch.

| # | Gap | Evidence | Owner |
|---|---|---|---|
| 1 | `AdminUserController` revokes sessions **by username, before commit**. An in-flight request can re-save its session. Renamed principals (SCIM) are missed; `ActiveUserFilter` still blocks them by id while they are disabled. | `M/admin/AdminUserController.kt:89-93,106-110`; `M/identity/Scim.kt:162-167` | C0 (`admin/**`) with C1 (`identity/**`) |
| 2 | **Pending activation / reset tokens survive a disable** and work again after re-enable (within 24 h). | `AccountService.TTL`, `AccountService.find`; no token cleanup in `AdminUserController.status` | C0 + C1 |
| 3 | The next request after an **API disable** answers **401 `AUTHENTICATION_REQUIRED`**, not `ACCOUNT_DISABLED`, because the session is deleted first. Clients must treat both 401 codes as "signed out". | `AdminUserController.kt:91`; `SecurityConfiguration.kt:188-189`; `AdminApiTests.kt:64` | C0 (contract choice) |
| 4 | ~~**maxDepth race.** Create and restore validate `maxDepth` without the structural lock.~~ **CLOSED (D-C0-52 item 7):** create, restore and a type-rule change now take the structural lock first (6.2 item 6), and C3 re-checks the depth inside create / restore. Proofs: C3 `OrganizationIntegrationWiringTests` ("MAX_DEPTH race 1" / "MAX_DEPTH race 2"), `OrgRaceTests` ("maxDepth is re-checked inside create and restore …"). Residual: the header KDoc of `OrganizationRepositories.kt` (rule 4c, paragraph "Known, documented limit") still describes the old race; documentation only. | `OrganizationUnitService.create` / `restore`, `OrganizationUnitTypeService.update` | C3 (closed); KDoc cleanup C1 |
| 5 | **The suspended-tenant policy for tenant-admin member and provisioning routes is unchanged.** `forTenant` does not check SUSPENDED, so a Tenant Admin keeps member, user and workspace routes (organization writes and rename are read-only in a SUSPENDED company). | `AccessService.forTenant`; `AccountService.createTenantUser` (`Accounts.kt:123`, DELETED check only) | C1 policy, needs a C0 decision |
| 6 | ~~`/auth/me` is O(N) in the number of project memberships (~5 queries per project).~~ **CLOSED on this branch:** a constant 6 statements per request (section 2.3a; regression test `AuthMeQueryComplexityTests`, benchmark 2.3b). Remaining: the response size is linear in the number of visible projects (no pagination, contract unchanged), and the 2.3a limits 2-3. | `ProjectScopeResolver.scopesFor`, `AuthController.me` | C1 (closed); pagination needs C0 |
| 7 | **CLOSED (M-052).** Top-level `permissions` stays the platform scope + the primary tenant (by design); the capabilities of every tenant are in `tenants[].permissions` (2.4a). | `MeTenancyService.forUser` | C1 (done) |
| 8 | Last-tenant-admin count is not locked (concurrent demotions). | `TenantService.setMember` / `TenantService.removeMember` (last-admin count) | C1 |
| 9 | A workspace member with **no** `tenant_members` row is treated as active. | `AccessEvaluator.tenantGates` (`membershipActive == null` passes); `AuthController.me` (`tm.user_id IS NULL OR tm.active`) | C1 (legacy, pre-V26 data) |
| 10 | `firstAdminUserId` does not check that the user is enabled or activated. | `TenantController.create` (`TenantController.kt:62`) | C1 |
| 11 | Tests that are still missing: the exact error code of the next request after an API disable (it is 401 `AUTHENTICATION_REQUIRED` because the session is deleted first; `ACCOUNT_DISABLED` appears only when a live session meets a disabled row), and the concurrency of the last-admin check. Covered on this branch: tenant rename, organization writes in a SUSPENDED company, `/auth/me` live rules, a plain employee with relation / position / grade gets no `APP_*`, TENANT_ADMIN 403 on `/admin/users/{id}/status`, old session stays dead after re-enable (`T/tenancy/FinalIam*Tests`, `FinalTenantStatusTests`, `FinalPermissionSemanticsTests`). | `T/tenancy/Final*` | C1 (tests) / C0 (`admin` tests) |
