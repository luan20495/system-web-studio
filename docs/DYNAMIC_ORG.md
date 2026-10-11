# Dynamic Organization (canonical)

Audience: engineers taking over XWEB / System Web Studio. This document describes the **Dynamic Organization** feature: a per-company (tenant) organization tree with employees, memberships, positions and grades. It states what it is, what it is **not** (it is not authorization), how it is stored (migration V32), its API, limits, lifecycle, UI and tests.

State described: code at `{{INTEGRATION_SHA}}`, release candidate `{{FINAL_RC_SHA}}`, public frontend `{{PUBLIC_FRONTEND_SHA}}`, public API `{{PUBLIC_API_SHA}}`. Companion document: `docs/SECURITY_AND_PERMISSION.md` (permission model). Architecture overview: `docs/ARCHITECTURE.md`.

Citation convention: `(src: M/...)` = `backend/src/main/kotlin/com/systemwebstudio/...`, `(src: T/...)` = `backend/src/test/kotlin/com/systemwebstudio/...`. Status words: DONE / PARTIAL / BLOCKED / DEFERRED.

---

## 1. What it is, and what it is not

**It is** a business model of a company's structure, scoped to one tenant:

| Concept | Meaning |
|---|---|
| **Unit type** | A company-defined kind of unit (for example "division", "team"; the platform hard-codes **no** level names such as Company/Department/Team) with placement rules |
| **Unit** | A node of an arbitrary-depth tree (parent/child), with a type and a code |
| **Employee** | A person who is a member of the tenant: `tenant_members JOIN users`. There is **no employee profile entity** (CF-1) |
| **Organization membership** | An employee's link to a unit, with a free business label (`relationType`, e.g. MEMBER, MANAGER, HEAD). An employee may have several, exactly one primary |
| **Position** | A job title from a per-company catalog |
| **Grade** | A level from a separate per-company catalog (optional `rank`) |
| **Held position** (`EmployeePosition`) | An employee holding a position **within one active membership**, with an optional grade |

(src: docs/parallel/c1/organization-employee-contract.md "Frozen decisions" CF-1..CF-4, architecture D-C0-43.)

**It is NOT authorization.** This is an invariant, not a default:

- Unit membership, relation type (MEMBER/MANAGER/HEAD/any vocabulary), position and grade grant **no permission**. A "HEAD" with a senior grade has exactly the permission codes the server lists for that person, nothing more.
- `access/**` and `identity/**` never read organization data (src: docs/parallel/c1/final-iam-tenant-org-permission-contract.md §6.2 item 11; M/access/Permission.kt KDoc of `PermissionMatrix`; V32 comment on `relation_type`: "NEVER an authorization input").
- Approver selection for workflow approvals does not use organization data either: `PrincipalResolver` denies `Group` and `DepartmentManager` targets ("not available"), and the decision-time check is the approver snapshot plus `WORKFLOW_MANAGE`, never a unit/relation/position/grade (src: M/access/adapters/PrincipalResolver.kt:54-55; C4 handoff `docs/parallel/audit/C4-FQ-WF-01-approval.md`, "Authorization of a decision" item 4).
- Tests: T/tenancy/FinalPermissionSemanticsTests.kt `4c` (MANAGER and HEAD relations, a position and a grade give no APP permission, no project visibility and no organization authority), T/organization/OrganizationAuthorizationTests.kt, T/wiring/RuntimeAuthorizationApiTests.kt "organization membership alone", T/logic/workflow/RuntimeAuthorizationTests.kt `21`.

**It is also not** the legacy `departments` feature (table `departments`, V20; `AdminDepartmentController` at `/api/v1/admin/departments`), which is a reporting grouping of users and workspaces ("It grants NO permission") and is **unchanged and unused** by the Dynamic Organization (src: M/admin/AdminOrg.kt:25-27, V32 header comment). The two coexist.

The organization authority is exactly six tenant-level codes and no others: `ORG_STRUCTURE_VIEW`, `ORG_STRUCTURE_MANAGE`, `EMPLOYEE_VIEW`, `EMPLOYEE_MANAGE`, `POSITION_GRADE_VIEW`, `POSITION_GRADE_MANAGE`. There is no `ORG_MANAGE`, `ORG_ADMIN`, `DEPARTMENT_MANAGE`; they are obsolete and a frontend guard fails the gate if they appear (src: docs/contracts/v2/tenant-permission.md §5b).

---

## 2. Domain model and invariants

### 2.1 Unit types

`OrganizationUnitTypeDto { id, tenantId, name, code, icon?, active, rules, version, createdAt, updatedAt }` with `rules { allowedParentTypeIds?, allowedChildTypeIds?, allowRoot?, maxDepth? }` (src: docs/parallel/c1/organization-employee-contract.md §2).

- `code`: lower-case, `^[a-z0-9][a-z0-9_-]{0,39}$` (input is trimmed and lower-cased first), unique **per tenant**, also when the type is disabled; **immutable** (src: M/organization/OrganizationServices.kt:14-15, V32 `organization_unit_types_code_unique`). `name` 1–120 characters; `icon` is an id of the UI's closed icon list, never a URL.
- Rule semantics (all fields null = unrestricted). Placing a unit = *child*; the type of its parent unit = *parent*:
  - `allowRoot = false` → the type cannot be a root (`ROOT_NOT_ALLOWED`); `null` = allowed when `allowedParentTypeIds` is null or `[]`.
  - `allowedParentTypeIds` non-null → the parent's type must be listed (`PARENT_TYPE_NOT_ALLOWED`); **`[]` means root only**.
  - The parent type's `allowedChildTypeIds` non-null must list the child type (`CHILD_TYPE_NOT_ALLOWED`); **`[]` means leaf**.
  - `maxDepth` (1–100; a global cap of 100 applies to the value) → the unit's depth (root = 1) may not exceed it (`MAX_DEPTH`).
  - A violation is `409 ORG_TYPE_RULE_VIOLATION {reason}` (src: M/organization/OrganizationServices.kt:27-39).
- A rule list holds at most 50 ids; a type may name itself in its rules; a rule id must be a type of **this** tenant (a foreign id is the same 404 as an unknown one).
- A `PATCH` that changes `rules` **replaces** the whole object and is validated against the existing tree under the structural lock (`409 ORG_TYPE_RULE_VIOLATION {reason, unitId, existingStructure:true}`).
- Disabling a type blocks **new units** and **restores** of that type; existing units keep it and stay editable. Types are never deleted.

### 2.2 Units and the tree

`OrganizationUnitDto { id, tenantId, typeId, parentId?, name, code, sortOrder, metadata{}, active, version, createdAt, updatedAt, archivedAt? }`.

- **Tree**: adjacency list; arbitrary depth (limited only by type `maxDepth`); several roots per tenant are allowed (`parentId` null). **Cycles are impossible**: the service pre-checks and the store re-checks atomically (`OrganizationCycle`) (src: V32 `organization_units_not_self`, contract "TREE contract").
- **Code** (CF-3): required; trimmed, validated against `^[A-Za-z0-9][A-Za-z0-9._-]{0,59}$` (else `400 INVALID_CODE`), then **upper-cased with `Locale.ROOT`**; stored, compared and returned upper-case (DB `CHECK (code ~ '^[A-Z0-9][A-Z0-9._-]{0,59}$')`). Unique among **non-archived siblings** (same tenant and parent); **all roots are siblings of each other**; the same code under different parents is allowed; archiving frees the code (src: M/organization/OrganizationServices.kt:16,60-63, V32 `organization_units_sibling_code_unique ... NULLS NOT DISTINCT WHERE deleted_at IS NULL`). A collision is `409 ORG_UNIT_CODE_TAKEN` on create, rename, **move** next to a same-code sibling, and `RESTORE_CONFLICT {reason: CODE_TAKEN}` on restore.
- `name` 1–160; `metadata` must be a JSON object ≤ 8192 bytes; `sortOrder` default 0. The **type is immutable**; the parent changes only through `move`.
- Deterministic order: `sortOrder ASC, lower(name) ASC, id ASC`.
- Counts (tree nodes and unit detail): `directMemberCount` = ACTIVE memberships of ACTIVE employees on exactly that unit; `subtreeEmployeeCount` = DISTINCT active employees over the unit and its non-archived descendants (a person in two units of the branch counts once, so it is not the sum). `null` only when the store provides no counts. `format=flat` carries no counts. `activeMemberCount` (unit detail) is the archive blocker and is **not** the subtree count (src: docs/parallel/c0/ORGANIZATION_API_CONTRACT_FOR_C5.md).

### 2.3 Employees

`EmployeeDto { userId, tenantId, username, displayName?, email?, active, accountEnabled, accountActivated, tenantRole, primaryOrganizationUnitId?, positions[], organizationMemberships[] }` — **no `version`, no ETag**. `active` = `tenant_members.active`; `primaryOrganizationUnitId` is derived from the active primary membership; `positions[]` and `organizationMemberships[]` list **active rows only** (use the sub-resources with `includeInactive=true` for history) (src: M/organization/EmployeeDirectoryService.kt:50-62).

- `POST /employees` creates a **new account only** (no password accepted or stored; the response carries a one-time activation link) together with optional memberships and positions, **in one transaction** (any failure rolls everything back). To attach an existing tenant member to a unit use the memberships sub-resource.
- Employee enable/disable is the canonical **tenant-membership lifecycle** (section 6.3), not an edit of the employee.
- There is no `PATCH /employees/{id}`; identity fields are not editable here (CF-1).

### 2.4 Memberships

`OrganizationMembershipDto { id, tenantId, userId, organizationUnitId, relationType, primary, active, version, createdAt, updatedAt }`.

- `relationType`: free business vocabulary, `^[A-Z][A-Z0-9_]{0,31}$` after upper-casing, default `MEMBER`, **no authority**.
- One ACTIVE membership per (employee, unit) (`409 ORG_MEMBERSHIP_EXISTS`); an ended one does not block a new one.
- **At most one active primary membership per (tenant, employee)**; the first active membership becomes primary; `primary=true` atomically demotes the previous one; `primary=false` is refused (make another one primary); ending the primary promotes the oldest remaining active one. Backed by a partial unique index; a lost race is `409 VERSION_CONFLICT` (never a silent "no primary").
- Ending a membership that still holds ACTIVE positions is refused: `409 EMPLOYEE_ORG_HAS_POSITIONS {activePositionCount}`. **Nothing cascades.**

### 2.5 Positions, grades, held positions

- `PositionDto { id, tenantId, name, code, description?, active, version, … }`, `GradeDto { …, rank?, … }`: two **independent** per-tenant catalogs (not units, no hierarchy, no permission). `code` `^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$`, unique per tenant **case-insensitively**, also when disabled; immutable. `name` 1–120, `description` ≤ 500 and nullable (`""` on PATCH clears it), grade `rank` 0–10000, nullable, **not unique**.
- `EmployeePositionDto { id, tenantId, userId, membershipId, organizationUnitId, positionId, gradeId?, primary, active, version, … }` (CF-2): held **within an active membership**; `membershipId` is mandatory; `organizationUnitId` is **derived** from it and always equals its unit. Active uniqueness is `(membershipId, positionId)` (`409 POSITION_ASSIGNMENT_EXISTS`), **even with another grade** — the grade is an attribute, not part of the key. The same position in two different active memberships is allowed. Membership, position and unit of an assignment are **immutable**; only `gradeId` and `primary` change (end it and add another to change the rest).
- At most one active primary position per (tenant, employee); the first assignment is primary; ending the primary promotes the oldest remaining active one.

---

## 3. Permissions

Tenant-level; held by **TENANT_ADMIN of that tenant only** (they are the eight codes of a TENANT_ADMIN: these six plus `TENANT_MANAGE` and `TENANT_MEMBERS`) (src: M/access/Permission.kt:129-136).

| Principal | ORG_STRUCTURE_* | EMPLOYEE_* | POSITION_GRADE_* |
|---|---|---|---|
| TENANT_ADMIN of that tenant | VIEW + MANAGE | VIEW + MANAGE | VIEW + MANAGE |
| tenant MEMBER, workspace roles, project roles | – | – | – |
| SYSTEM_ADMIN (platform scope, default) | – (403 on every organization route) | – | – |
| SYSTEM_ADMIN with the legacy flag `app.tenancy.system-admin-business-access=true` (default off) | as TENANT_ADMIN | as TENANT_ADMIN | as TENANT_ADMIN |
| User of another tenant / stranger | 404 | 404 | 404 |

- `*_MANAGE` does **not** imply `*_VIEW` in code (the matrix grants both together).
- `POST /employees`, `POST …/disable`, `POST …/enable` need `EMPLOYEE_MANAGE` **and** `TENANT_MEMBERS` (account provisioning).
- A non-member SYSTEM_ADMIN has exactly `TENANT_MANAGE` + `TENANT_MEMBERS` and therefore gets 403 on every organization route; organization screens must never be offered on `TENANT_MEMBERS` or `platformScope`.
- In `/auth/me`, read the company's codes from `tenants[].permissions` of the **selected** company; the root `permissions[]` describes only the platform scope and the primary tenant. Never gate on a role name.

**Authorization order of every route** (src: M/organization/OrganizationControllers.kt:12-24):
1. 401 no session → 2. `AccessService.forTenant` (stranger or unknown tenant → safe 404 `TENANT_NOT_FOUND`) → 3. `require(code)` (403 `FORBIDDEN`) → 4. for `*_MANAGE`, `requireTenantWritable` (403 `TENANT_SUSPENDED`; 404 for a DELETED tenant) → 5. validation → 6. domain (404 child id / 409 / 422) → 7. store availability (501 when no store is wired).

A `tenantId` in a body is never read; every id (unit, type, employee, position, grade, membership, assignment) is resolved **inside the path tenant**: a record of another tenant is indistinguishable from a missing one (same code and message).

---

## 4. API

Base path: `/api/v1/admin/tenants/{tenantId}/…`. Session cookie + CSRF header on every mutation. JSON. Error body `{code, message, details?}`.

### 4.1 Conventions

- **Versioning**: types, units, memberships, held positions, positions and grades have `version` (long, starts at **0**, +1 on every applied write; setting a primary also bumps the demoted row). Writes carry `expectedVersion` (body; for `DELETE` the query parameter). Missing → `400 VALIDATION_FAILED`. Stale → `409 VERSION_CONFLICT {currentVersion}`. Single-record responses carry `ETag: "<version>"`. Employees have no version/ETag; lists carry no ETag.
- **Query booleans**: `includeInactive` (types, positions, grades: default **true**; memberships and held positions: default **false**), `includeArchived` (units: default **false**).
- **No hard delete anywhere.** Archive/disable/end are state changes of the same row; history stays.
- **Busy store**: with the PostgreSQL store, every organization write bounds its lock waits by `app.organization.structural-lock-timeout-ms` (default 5000). A timeout (SQLSTATE 55P03 only) answers `503 ORG_STRUCTURE_BUSY`, `details = {retryable:true, retryAfterSeconds:N}`, header `Retry-After: N`, `N = max(1, ceil(timeoutMs/1000))`; **nothing was applied**; retry the same request with the same `expectedVersion` (src: docs/parallel/DECISIONS.md D-C0-52 item 4).
- **No store wired** (flag off): every route answers `501 ORG_PERSISTENCE_NOT_AVAILABLE` after authentication and authorization.

### 4.2 Routes

| Method and path | Permission | Notes / key errors |
|---|---|---|
| `GET /organization-unit-types?includeInactive` | ORG_STRUCTURE_VIEW | ordered `lower(name), id` |
| `GET /organization-unit-types/{typeId}` | ORG_STRUCTURE_VIEW | 404 `ORG_UNIT_TYPE_NOT_FOUND`; ETag |
| `POST /organization-unit-types` (201) | ORG_STRUCTURE_MANAGE | `{name, code, icon?, rules?}`; 400 `VALIDATION_FAILED`/`INVALID_CODE`; 409 `ORG_UNIT_TYPE_CODE_TAKEN` |
| `PATCH /organization-unit-types/{typeId}` | ORG_STRUCTURE_MANAGE | `{name?, icon?, rules?, expectedVersion}`; rules change: 409 `ORG_TYPE_RULE_VIOLATION{…existingStructure}` |
| `POST /organization-unit-types/{typeId}/disable` / `/enable` | ORG_STRUCTURE_MANAGE | `{expectedVersion}` |
| `GET /organization-units?format=tree\|flat&includeArchived` | ORG_STRUCTURE_VIEW | tree (default) = `[{unit, children, directMemberCount, subtreeEmployeeCount}]`; flat = `[Unit]`; 400 on a bad `format`; the tree call computes counts of the whole tenant in one statement |
| `GET /organization-units/{unitId}` | ORG_STRUCTURE_VIEW | `{unit, path[], activeChildCount, activeMemberCount, directMemberCount, subtreeEmployeeCount}`; ETag = `unit.version` |
| `POST /organization-units` (201) | ORG_STRUCTURE_MANAGE | `{typeId, parentId?, name, code, sortOrder?, metadata?}`; 409 `ORG_UNIT_TYPE_DISABLED`, `ORG_UNIT_ARCHIVED` (parent), `ORG_TYPE_RULE_VIOLATION`, `ORG_UNIT_CODE_TAKEN` |
| `PATCH /organization-units/{unitId}` | ORG_STRUCTURE_MANAGE | `{name?, code?, sortOrder?, metadata?, expectedVersion}`; 409 `ORG_UNIT_ARCHIVED` (an archived unit cannot be changed) |
| `POST /organization-units/{unitId}/move` | ORG_STRUCTURE_MANAGE | `{newParentId (key REQUIRED: uuid or explicit null = root), expectedVersion, sortOrder?}`; a missing key is 400, never a silent move to root; 409 `ORG_CYCLE`, `ORG_UNIT_ARCHIVED`, `ORG_TYPE_RULE_VIOLATION`, `ORG_UNIT_CODE_TAKEN` |
| `POST /organization-units/{unitId}/archive` | ORG_STRUCTURE_MANAGE | 409 `ORG_UNIT_HAS_CHILDREN{activeChildCount}`, `ORG_UNIT_HAS_MEMBERS{activeMemberCount}`, `ORG_UNIT_ARCHIVED` |
| `POST /organization-units/{unitId}/restore` | ORG_STRUCTURE_MANAGE | 409 `RESTORE_CONFLICT{reason: NOT_ARCHIVED\|TENANT_INACTIVE\|TYPE_DISABLED\|PARENT_ARCHIVED\|TYPE_RULE\|CODE_TAKEN}` |
| `GET /positions`, `GET /grades` (`includeInactive`); `GET …/{id}` | POSITION_GRADE_VIEW | grades ordered `rank` (null last), name, id |
| `POST /positions`, `POST /grades` (201) | POSITION_GRADE_MANAGE | 409 `POSITION_CODE_TAKEN` / `GRADE_CODE_TAKEN` |
| `PATCH /positions/{id}`, `PATCH /grades/{id}`; `POST …/{id}/disable` / `/enable` | POSITION_GRADE_MANAGE | disabling never touches existing assignments (a disabled position/grade just cannot be assigned anew) |
| `GET /employees?q&organizationUnitId&includeDescendants&positionId&gradeId&active&userId&page&size&sort&dir` | EMPLOYEE_VIEW | `{items,total,page,size}`; see 4.3 |
| `GET /employees/{userId}` | EMPLOYEE_VIEW | 404 `EMPLOYEE_NOT_FOUND` (not a member, or another tenant's user) |
| `POST /employees` (201) | EMPLOYEE_MANAGE + TENANT_MEMBERS | `{username, displayName, email?, tenantRole?, workspaceId?+workspaceRole?, organizationMemberships?[{organizationUnitId, relationType?, primary?, positions?[{positionId, gradeId?, primary?}]}]}` → `{employee, activation?}` |
| `POST /employees/{userId}/disable` / `/enable` | EMPLOYEE_MANAGE + TENANT_MEMBERS | no body, no version, idempotent; 403 `SELF_GRANT_FORBIDDEN`, 409 `LAST_TENANT_ADMIN`, 422 `USER_DISABLED` |
| `GET /employees/{userId}/organization-memberships?includeInactive` | EMPLOYEE_VIEW | primary first |
| `POST …/organization-memberships` (201) | EMPLOYEE_MANAGE | 400 at the 21st active membership; 409 `EMPLOYEE_INACTIVE`, `ORG_UNIT_ARCHIVED`, `ORG_MEMBERSHIP_EXISTS` |
| `PATCH …/organization-memberships/{membershipId}` | EMPLOYEE_MANAGE | `{relationType?, primary?, expectedVersion}`; `primary=false` is 400 |
| `DELETE …/organization-memberships/{membershipId}?expectedVersion=n` | EMPLOYEE_MANAGE | 409 `EMPLOYEE_ORG_HAS_POSITIONS`; allowed even if the employee is disabled |
| `GET /employees/{userId}/positions?includeInactive` | EMPLOYEE_VIEW | |
| `POST …/positions` (201) | EMPLOYEE_MANAGE | `{membershipId, positionId, gradeId?, primary?}`; 400 at the 21st active position of that membership; 404 `ORG_MEMBERSHIP_NOT_FOUND`; 409 `ORG_MEMBERSHIP_INACTIVE`, `POSITION_DISABLED`, `GRADE_DISABLED`, `POSITION_ASSIGNMENT_EXISTS`, `EMPLOYEE_INACTIVE` |
| `PATCH …/positions/{employeePositionId}` | EMPLOYEE_MANAGE | `{gradeId? \| clearGrade?, primary?, expectedVersion}` |
| `DELETE …/positions/{employeePositionId}?expectedVersion=n` | EMPLOYEE_MANAGE | 404 `POSITION_ASSIGNMENT_NOT_FOUND` |
| `POST /api/v1/admin/tenants` with `firstAdmin` | platform (`forPlatform`, SYSTEM_ADMIN) | company bootstrap, section 7 |

(src: docs/parallel/c1/final-iam-tenant-org-permission-contract.md §6.1; controllers: M/organization/OrganizationControllers.kt:29, 58, 104, 133, 162.) Order of checks in `POST …/positions`: employee → membership → position → grade → limit → store uniqueness.

### 4.3 Employee directory

`q` (trimmed; < 2 characters → `400 QUERY_TOO_SHORT`; literal — `%`, `_`, `\` are text — and case-insensitive over username, display name, e-mail), `organizationUnitId` (+ `includeDescendants=true` default: the unit and its whole subtree; matches users with an **active membership** in any of those units; a foreign/unknown unit → 404, not an empty page), `positionId`, `gradeId` (active assignment; foreign/unknown → 404), `active` (= `tenant_members.active`; disabled members stay in the directory), `userId`, `page` (0-based), `size` (1–100, default 25), `sort` = `name`|`username` (default `name`: `lower(displayName ?: username)`), `dir` = `asc`|`desc`. Ties by `userId`. A person matching through several memberships is **one** row; `total` is the filtered count. **Out of range is a 400, never clamped**: `VALIDATION_FAILED` (negative page, size outside 1–100) or `OFFSET_TOO_LARGE` when `page * size > 10000` (narrow the search instead of paging deeper) (src: M/organization/EmployeeDirectoryService.kt:32-40,70-76).

---

## 5. Persistence (migration V32)

File: `backend/src/main/resources/db/migration/V32__dynamic_organization.sql` — created by C3, **immutable**; the undo script is `docs/parallel/c3/undo/U32__dynamic_organization.sql` (refuses while any organization row exists; Flyway never runs it). **V31 is a permanent void gap** (never to be created); `outOfOrder` stays off; V33 is `V33__approvals.sql` (the C4 workflow approvals table, D-C0-61, undo `docs/parallel/c0/undo/U33__approvals.sql`) and is unrelated to the organization; `OrgV32FlywayTests` pins exactly one V32 and one V33, V34 and above not consumed. Requires PostgreSQL ≥ 15 (`NULLS NOT DISTINCT`); the platform runs 17.6 (src: V32 header, docs/parallel/DECISIONS.md D-C0-52 items 1-2).

Six additive tables; no existing table is touched; no data copied; `departments` (V20) unchanged.

| Table | Key columns and constraints |
|---|---|
| `organization_unit_types` | `id`, `tenant_id`, `code` (≤40), `name`, `icon`, `rules JSONB` (object), `active`, `version ≥ 0`; unique `(tenant_id, lower(code))`; `UNIQUE (id, tenant_id)` |
| `organization_units` | `type_id`, `parent_id` (NULL = root), `code` ≤60 (CHECK upper-case form), `name`, `sort_order`, `metadata JSONB` (object), `active`, `deleted_at`, `version`; CHECKs: not self-parent, `deleted_at IS NULL OR NOT active`; unique sibling code `(tenant_id, parent_id, code) NULLS NOT DISTINCT WHERE deleted_at IS NULL`; composite FKs `(type_id, tenant_id)`, `(parent_id, tenant_id)` |
| `positions`, `grades` | `code`, `name`, `description` (nullable), grades `"rank"` (nullable, not unique), `active`, `version`; unique `(tenant_id, lower(code))` |
| `employee_organization_units` (membership) | `user_id`, `organization_unit_id`, `relation_type` (`^[A-Z][A-Z0-9_]{0,31}$`), `is_primary`, `active`, `version`; FK `(tenant_id, user_id)` → `tenant_members` (the canonical employee; a member with memberships cannot be deleted); FK to the unit; unique active `(tenant_id, user_id, organization_unit_id) WHERE active`; unique primary `(tenant_id, user_id) WHERE is_primary`; `CHECK (NOT is_primary OR active)`; `UNIQUE (id, tenant_id, user_id)` |
| `employee_positions` | `membership_id`, `position_id`, `grade_id` NULL, `is_primary`, `active`, `version`; FK `(membership_id, tenant_id, user_id)` → membership (a position can only be held in a membership of **the same employee and tenant**); FKs to position and grade; unique active `(membership_id, position_id)`; unique primary `(tenant_id, user_id) WHERE is_primary` |

**Tenant isolation in the database:** every table has `tenant_id NOT NULL REFERENCES tenants`; `UNIQUE (id, tenant_id)` plus composite FKs carry the tenant on every cross-table reference, so a cross-tenant link is impossible even through raw SQL. All FKs are `ON DELETE RESTRICT`: nothing cascades, and **a tenant that has organization rows cannot be hard-deleted** (tenant lifecycle is `tenants.status`) (src: docs/parallel/c3/DYNAMIC_ORGANIZATION_PERSISTENCE.md §2, §8).

### 5.1 Code layout

| Layer | Location | Owner |
|---|---|---|
| Contract DTOs, application services, controllers, repository **seams** | `M/organization/{OrganizationContract,OrganizationServices,EmployeeDirectoryService,OrganizationRepositories,OrganizationControllers,JdbcTenantIdentityDirectory}.kt` | C1 |
| PostgreSQL implementation of every seam (plain `JdbcTemplate`, one ambient transaction, never `REQUIRES_NEW`) | `M/data/org/{OrgPersistence,OrganizationPersistenceConfiguration,PostgresOrganizationCatalogs,PostgresOrganizationEmployees,PostgresOrganizationUnits}.kt` | C3 |
| Company bootstrap | `M/tenancy/CompanyBootstrapService.kt` | C1 |

The application services depend only on the seams (`OrganizationUnitTypeRepository`, `OrganizationUnitRepository`, `EmployeeDirectoryRepository`, `EmployeeOrganizationMembershipRepository`, `PositionRepository`, `GradeRepository`, `EmployeePositionRepository`, `TenantStructuralLock`, `TenantIdentityDirectory`, plus the optional counts seam `OrganizationEmployeeCounts`). Mandatory behaviour of every implementation: tenant first (a foreign record is null/empty/0), versioned writes by one atomic compare-and-set statement (`null` when stale **or** missing; the service re-reads to tell 404 from 409), uniqueness enforced by the store (`DuplicateOrganizationKey`), one ambient transaction (employee creation provisions the account and the organization rows atomically; an audit failure rolls the mutation back), deterministic ordering, no cascade (src: docs/parallel/c1/organization-employee-contract.md §8).

### 5.2 Feature flag and configuration

| Property (env) | Default | Meaning |
|---|---|---|
| `app.organization.persistence-enabled` (`ORGANIZATION_PERSISTENCE_ENABLED`) | **false** | `OrganizationPersistenceConfiguration` is `@ConditionalOnProperty(havingValue = "true")` with no `matchIfMissing`. Off: no repository bean exists; every route answers 501 after authorization |
| `app.organization.structural-lock-timeout-ms` (`ORGANIZATION_STRUCTURAL_LOCK_TIMEOUT_MS`) | 5000 | The one bound of every structural-lock and row-lock wait of an organization write; exceeded → 503 `ORG_STRUCTURE_BUSY` |

(src: backend/src/main/resources/application.yml:133-137; the property was first named `app.organization.lock-timeout-ms` by C3 and renamed by C0, D-C0-52 item 3.) Every repository bean is optional at boot; a missing one also answers 501. Where the flag is on: the RC candidate stack `c0rc` (D-C0-59 item 2) and the isolated E2E stacks. The public API deployment runs an older pinned release and **does not contain the organization routes** (section 10).

### 5.3 Locking and concurrency

- **Structural lock** (`TenantStructuralLock`, `pg_advisory_xact_lock(namespace, hashtext(tenant))`): transaction-scoped, re-entrant, needs an active transaction, bounded wait. Taken by **exactly four** operations, all in `OrganizationServices.kt`: unit **create**, subtree **move**, unit **restore**, and a unit-type **rule change** (only when `rules` is present). Every other write uses the ordinary transaction, constraints, row locks and optimistic versions. The four are pinned by `OrganizationApiContractTests` "CF-4 the tenant structural lock is taken by the STRUCTURAL operations only…" (acquisition count per operation) and on PostgreSQL by `OrganizationIntegrationWiringTests`/`OrgStructuralLockTests` (src: final contract §6.2 item 6, D-C0-52 item 7).
- **Row locks** (race safety without a tenant-wide lock): a child insert, move destination, membership insert or restore takes `FOR SHARE` on the referenced unit row and refuses an archived one (`ReferencedRowInactive`); archive takes `FOR UPDATE` on the unit, then counts ACTIVE children and memberships (`OrganizationUnitInUse`); ending a membership takes `FOR UPDATE` and counts active positions (`MembershipHasPositions`); `setPrimary` locks the employee's active rows in id order, with the partial unique index as backstop.
- **Move** (one READ COMMITTED transaction): lock → source exists (404) → source active (409) → destination exists and active → destination not inside the subtree (`ORG_CYCLE`) → resulting depth → type rules → `maxDepth` of **every descendant** → CAS on `expectedVersion` + UPDATE of the moved row only (descendants follow and are not rewritten). The move does **not** re-check that the types involved are still `active` (only create and restore do).
- `maxDepth` is also re-checked by the store inside insert and restore under the parent row lock (the earlier depth race is CLOSED, D-C0-52 item 7).

---

## 6. Limits and lifecycle

### 6.1 Limits

| Limit | Value | Enforced by | Notes |
|---|---|---|---|
| **Active positions per membership** | **20** (`EmployeeDirectoryService.MAX_POSITIONS`) | Service, check-then-insert; 400 `VALIDATION_FAILED` on the 21st | Counted per `membershipId`: another membership of the same employee has its own 20; an ended position frees its slot; the nested create form enforces the same limit per membership |
| Active memberships per employee | 20 (`MAX_MEMBERSHIPS`) | Service; 400 `VALIDATION_FAILED` | `OrganizationLimitsTests` (the 21st is refused and creates nothing; an ended membership frees a slot) |
| Directory page size / offset | `size` 1–100, `page * size ≤ 10000` | Service; 400 `VALIDATION_FAILED` / `OFFSET_TOO_LARGE` | Not clamped |
| Directory search term | ≥ 2 characters | Service; 400 `QUERY_TOO_SHORT` | |
| Unit-type rule ids | ≤ 50; `maxDepth` 1–100 | Service | |
| Unit metadata | JSON object ≤ 8192 bytes | Service + DB `CHECK jsonb_typeof = 'object'` | |
| Field lengths | type name ≤120, code ≤40 / unit code ≤60 / position and grade code ≤40, unit name ≤160, description ≤500, relation ≤32 | Service + DB CHECKs | |
| Lock wait | 5 s default | `structural-lock-timeout-ms` → 503 | |

The two "20" limits are **best-effort business bounds, not a security boundary**: they are checked by the service before the insert and not by a store constraint, so two concurrent writes can, in a rare race, end at 21 (src: final contract §6.2 item 10; organization contract §11). Tests: T/organization/OrganizationLimitsTests.kt (position limit: route and nested create form).

### 6.2 Deletion and archive semantics

- **Units**: archive (`active=false`, `archivedAt` set, DB column `deleted_at`) and **restore**. No `DELETE`. Archive is refused while the unit has an active child (`ORG_UNIT_HAS_CHILDREN`) or an active member (`ORG_UNIT_HAS_MEMBERS`); nothing cascades. In `tree` a unit whose parent is archived is hidden with it. An archived unit cannot be edited and answers `0 / 0` counts. **Restore** checks, in order: not archived → tenant ACTIVE (`TENANT_INACTIVE`) → type active (`TYPE_DISABLED`) → own parent active (`PARENT_ARCHIVED`) → placement rules and depth (`TYPE_RULE`) → sibling code free (`CODE_TAKEN`); it returns under the previous parent with the previous code.
- **Types, positions, grades**: enable/disable only; never deleted; disabling does not touch existing units/assignments.
- **Memberships and held positions**: ended by `active=false, primary=false`; the row stays (history). A membership row is never reused — a new one is inserted. Ending a membership with active positions is refused.
- **Employees**: never deleted; enable/disable only (6.3).

### 6.3 Employee lifecycle vs account lifecycle

Two independent switches (src: final contract §1):

| | `users.enabled` (global account) | `tenant_members.active` (employee) |
|---|---|---|
| Who | Only the platform SYSTEM_ADMIN | TENANT_ADMIN (`EMPLOYEE_MANAGE` + `TENANT_MEMBERS`), or the platform operator through platform scope for `TENANT_MEMBERS` routes |
| Effect | Kills every session of the account; affects every tenant | **No session touched.** Next request: the company's routes 404, its workspaces 404, `/auth/me` omits it. Other companies are unaffected; `users.enabled` is never written |

- Employee **disable**: idempotent, no body; self-disable → 403 `SELF_GRANT_FORBIDDEN`; the last active TENANT_ADMIN → 409 `LAST_TENANT_ADMIN`. Memberships and positions are **kept**. While disabled, adding or changing memberships/positions is `409 EMPLOYEE_INACTIVE` (ending them stays allowed). Disabled members stay in the directory (`active=false`).
- Employee **enable**: re-adds the account as tenant **MEMBER** (a former TENANT_ADMIN does not get the role back); an account disabled platform-wide answers 422 `USER_DISABLED`.
- Audited as `EMPLOYEE_DISABLED` / `EMPLOYEE_ENABLED` in addition to the tenant service's `TENANT_MEMBER_REMOVED` / `TENANT_MEMBER_SET`.

### 6.4 Tenant status

| Tenant status | Organization / employee / position / grade **reads** | **Writes** (every `*_MANAGE` route, including employee create/enable/disable) |
|---|---|---|
| ACTIVE | allowed | allowed |
| SUSPENDED | allowed | **403 `TENANT_SUSPENDED`** (checked after the permission, so a plain member still sees 403 `FORBIDDEN`) |
| DELETED | 404 `TENANT_NOT_FOUND` (admin); 403 for platform scope | 404 `TENANT_NOT_FOUND` (also with the legacy flag); unit restore also needs an ACTIVE tenant (`RESTORE_CONFLICT {TENANT_INACTIVE}`) |

(src: M/access/AccessService.kt:134-142; T/tenancy/FinalTenantStatusTests.kt `6a`: 12 write routes answer 403, reads 200; T/organization/OrganizationAuthorizationTests.kt.)

---

## 7. Company bootstrap

`POST /api/v1/admin/tenants` with `firstAdmin {username, displayName, email?}` (platform SYSTEM_ADMIN via `forPlatform`): **one transaction** (`CompanyBootstrapService.create`) creates the tenant, the first Tenant Admin account (pending, no password, no default password), its `TENANT_ADMIN` membership, and a one-time 24 h activation link. Any failure (taken slug/username, invalid e-mail, audit failure) rolls everything back: no tenant without an admin, no orphan account. `firstAdmin` together with `firstAdminUserId` → 400. There is no EmployeeProfile. Proven on real PostgreSQL by T/organization/CompanyBootstrapTests.kt. Known gap: `firstAdminUserId` does not check that the user is enabled/activated (src: final contract §3).

---

## 8. Audit

Written in the **same transaction** as the mutation (an audit failure rolls the mutation back); refused calls (403/404/409) write nothing. Entity types `ORG_UNIT_TYPE`, `ORG_UNIT`, `POSITION`, `GRADE`, `EMPLOYEE`. Events (exact):
`ORG_UNIT_TYPE_CREATED|UPDATED|ENABLED|DISABLED`, `ORG_UNIT_CREATED|UPDATED|MOVED|ARCHIVED|RESTORED`, `POSITION_CREATED|UPDATED|ENABLED|DISABLED`, `GRADE_CREATED|UPDATED|ENABLED|DISABLED`, `EMPLOYEE_CREATED|ENABLED|DISABLED`, `EMPLOYEE_ORG_ASSIGNED|UPDATED|REMOVED`, `EMPLOYEE_POSITION_ASSIGNED|UPDATED|REMOVED`, `TENANT_CREATED`. `MOVED` carries old/new `parentId`, `sortOrder`, `version` and `subtreeSize`; `EMPLOYEE_ORG_REMOVED` carries `promotedMembershipId`. **Never audited:** password, activation token, hash, credential (T/organization/OrganizationAuthorizationTests.kt `16`; `OrganizationApiContractTests` `N`). `audit_events` is append-only (see `docs/SECURITY_AND_PERMISSION.md` section 12).

---

## 9. UI surface (Admin portal)

Routes: **`/organization`** ("Cơ cấu tổ chức") and **`/employees`** ("Nhân viên") inside the Admin portal (`apps/admin`; the C5 documents call them `/admin/organization` and `/admin/employees`), sections `organization` and `employees` of the Admin console (src: features/admin/console/sections.tsx:73-74; the real-backend flow opens the admin portal at `/organization`: tests/e2e-real/flows/e2e-org01.mjs:33). Positions and grades are a dialog ("Vị trí & cấp bậc") opened from the organization screen, not a separate route (src: features/admin/OrganizationScreens.tsx:61).

- **Gating**: a section is offered only when the server lists the code for the **selected company** (`tenants[].permissions`): organization needs `ORG_STRUCTURE_VIEW`, employees need `EMPLOYEE_VIEW`, the catalog dialog needs `POSITION_GRADE_VIEW`; create/enable/disable employee needs `EMPLOYEE_MANAGE` **and** `TENANT_MEMBERS`. Never a role, `TENANT_MEMBERS` alone or `platformScope` (src: features/admin/console/sectionPolicy.ts:54-55; features/admin/organization.ts:63).
- **Layers**: screens (`OrganizationScreens`, `OrganizationTree`, `OrganizationTypes`, `OrganizationCatalog`, `EmployeesScreens`) → `OrganizationApi` (`features/admin/organization.ts`: shapes requests, clamps paging, passes every error through) → `api.org` (`packages/api-client/src/org.ts`, the only place that builds a URL, query or body) → `call()`. Pure rules (tree, counts, placement hints, forms, error mapping): `features/admin/organizationModel.ts`. DTO mirror: `packages/types/src/index.ts` (`Org*`). The guard `ORG-FAIL-CLOSED-ROUTE` forbids a component from naming a route; `tests/guards/org-contract.json` `wired` lists the 31 capabilities.
- **Behaviour that matters**: two counts, never merged ("N trực tiếp" vs "M cả nhánh"; `null` = "chưa có số liệu", never 0); errors by the server's **code** (`ORG_STRUCTURE_BUSY` → a retry state that resends the same request and says nothing was saved; `ORG_PERSISTENCE_NOT_AVAILABLE` 501 → fail closed with no data, no mock, no local copy; `VERSION_CONFLICT` → reload; archive blockers; `RESTORE_CONFLICT` reasons; `TENANT_SUSPENDED`); paging guard before the request (size 1–100, page×size ≤ 10 000, search ≥ 2); max depth shown only from the company's own `rules.maxDepth` (no depth number in code); archive/restore with in-app confirmation, no delete; relation/position/grade authorize nothing.
- **Not supported (not offered, nothing invented)**: hard delete of a unit, employee profile fields (code, phone, joined date), tenant-global position, editing an employee's identity.
- **Layout fixes (DONE, on `integration/v2`)**: **FQ-UI-01** — `/admin/employees` was clipped at 360/390/768/1440 px with long unit/position/grade names; fixed in CSS only (grid tracks `minmax(0, …)`, selects ellipsize; proof `org-hardening` FQ-UI-01 at 9 widths and real-backend `E2E-UI01` 9/9, `tests/e2e-real/flows/e2e-ui01.mjs`), and **FQ-A11Y-02** — builder dialogs lost focus after Escape because the Studio remounts on path change; fixed in the shared focus primitive `restoreOpener` (`packages/ui/src/focus.ts:75`; `E2E-A11Y01` 15/15) (src: docs/parallel/c5/C5_NEXT_SESSION_HANDOFF.md §11, packages/ui/src/styles/factory.css).
- Status: UI wired to the real backend and proven with the flag ON on an isolated stack (E2E-ORG01 31/31, D-C0-57 item 3). It stays fail-closed (501 state) where the flag is off.

---

## 10. Operating status

| Aspect | Status |
|---|---|
| Backend persistence (V32, repositories, services, routes) | DONE; behind `app.organization.persistence-enabled`, **default OFF** |
| Frontend (Admin portal) | DONE (wired, 31 capabilities); FQ-UI-01 and FQ-A11Y-02 fixed |
| Real-browser proof with the flag ON | DONE on the isolated stack `c0rc` / E2E stacks: E2E-ORG01 **31/31 PASS** (D-C0-57 item 3). The default (flag-OFF) run records the fail-closed behaviour and ends BLOCKED(C0), never PASS |
| Public deployment | **BLOCKED / not deployed.** The public API is pinned to an older release (`{{PUBLIC_API_SHA}}`) that lacks the Dynamic Organization routes, M-052 tenant-scoped permissions, `projectScopes` and H-C2-07; the public portals (`{{PUBLIC_FRONTEND_SHA}}`) are the visual frontend only. Public production readiness of Dynamic Organization is **NO** until the API is redeployed (an explicit act; Flyway V32 is expand-only so a rollback of the API remains possible) (src: docs/parallel/DECISIONS.md D-C0-57 item 4) |

---

## 11. Tests and benchmarks

### 11.1 C1 contract tests (in-memory double — **not** PostgreSQL proof)

`backend/src/test/kotlin/com/systemwebstudio/organization/`: `OrganizationApiContractTests`, `EmployeeApiContractTests`, `OrganizationAuthorizationTests` (isolation, foreign node, move/archive/restore authorization, cross-tenant assignment, relation ≠ permission, structural guard: every mutating handler requires `*_MANAGE`, every GET `*_VIEW`), `OrganizationUnavailableTests` (501), `OrganizationLimitsTests`, `CompanyBootstrapTests` (PostgreSQL), and the **repository contract kit** `OrganizationRepositoryContractKit` (abstract, **13** tests) with the reference subclass `InMemoryOrganizationConformanceTest` (double: `InMemoryOrganization`). The kit is the executable specification of the seams: tenant first, versioned writes, sibling-scoped codes, hierarchy, cycle refusal, primary, assignments within a membership, directory semantics, atomic typed refusals, sibling-code edge cases (src: organization contract §10).

### 11.2 C3 PostgreSQL tests (real PostgreSQL 17.6 via Testcontainers)

`backend/src/test/kotlin/com/systemwebstudio/data/org/`: `PostgresOrganizationConformanceTest` (the **same 13-test kit, unchanged**, on PostgreSQL), `OrgSchemaTests` (structure, raw-SQL cross-tenant, sibling, lifecycle, primary), `OrgTreeTests`, `OrgStructuralLockTests` (lock semantics, racing moves never build a cycle, negative control without the lock builds one), `OrgRaceTests` (archive vs child/membership insert, end membership vs position insert, primary races, concurrent code/membership/assignment inserts, transaction participation, bounded row-lock wait → 503, maxDepth re-check), `OrgCountsDirectoryTests` (distinct counts and 10k directory pages vs an in-memory oracle), `OrgV32FlywayTests` (real Flyway on the shipped migration: clean V1→V32 and upgrade V30→V32 with legacy data identical, guarded undo, duplicate-migration guard, checksum stability), `FlywayHarnessTests`, `OrganizationRulesOnPostgresTests`, `OrganizationIntegrationWiringTests` (C0: structural operations wait for the lock, non-structural never do, MAX_DEPTH races, `ORG_STRUCTURE_BUSY`, directory bound, counts, rule change against the existing tree), `OrganizationOnPostgresApiTests` (the API on PostgreSQL; 501 without the flag), `OrgBenchmarkTests`. Counted from the source (`@Test` annotations): the `data/org` classes carry 54 own tests (OrgSchemaTests 5, OrgTreeTests 5, OrgStructuralLockTests 6, OrgRaceTests 7, OrgCountsDirectoryTests 4, OrgV32FlywayTests 5, FlywayHarnessTests 2, OrganizationRulesOnPostgresTests 5, OrganizationIntegrationWiringTests 10, OrganizationOnPostgresApiTests 3, OrgBenchmarkTests 2) plus the 13 inherited kit tests; `ApprovalsV33FlywayTests` (3) covers V33, not the organization. C1 side: OrganizationApiContractTests 14, EmployeeApiContractTests 8, OrganizationAuthorizationTests 7, OrganizationLimitsTests 3, OrganizationUnavailableTests 1, CompanyBootstrapTests 3, kit 13. Last full backend run on the merged tree (D-C0-60 item 3): 256 suites, 2298 tests, 2297 pass; the single failure (`LockdownSettingsTests`) is host starvation and passed 5/5 alone. Earlier recorded runs: D-C0-52 item 8 (2144 tests), D-C0-58 item 5 (2260 tests). Unrelated test-isolation failure (`LockdownSettingsTests`).

Limit: restore versus a concurrent archive of its parent has **no dedicated race test** (`OrgRaceTests` covers archive vs child/membership insert, end membership vs position insert, and the maxDepth re-check in create/restore); the store's `FOR SHARE` refusal (`ReferencedRowInactive`, kit test 11) is what prevents it. The 20-per-membership and 20-per-employee limits are check-then-insert (6.1).

### 11.3 Frontend and real-flow tests

Unit `tests/builder/organization*.test.ts`, `organization-client.test.ts`, `tenant-scope.test.ts`; browser harness `tests/browser/{org,org-employees,org-hardening}.spec.mjs` over a typed in-memory fake server (122 / 76 / 67 checks, CHROMIUM) — evidence class HARNESS, which proves what the **screens** do with the contract's answers, never what a server answers; real backend `tests/e2e-real/flows/e2e-org01.mjs` (25 flag-ON cases + the flag-OFF fail-closed cases 0a–0g; 31/31 PASS on the C0 stack). Guards in `npm run gate:frontend`: `org-source-guards.mjs` (`ORG-FAIL-CLOSED-*`, `ORG-RELATION`), `permission-mirror.mjs`, `tests/guards/org-contract.json`.

### 11.4 Benchmarks (indicative, not an SLA)

Dataset: 2,000 units (5 roots, max depth 64, a depth-60 chain, a 301-children node), 10,000 employees, 12,824 memberships (27 % of employees with several), 8,885 held positions, 50 positions, 10 grades; second tenant of 500 units / 2,000 employees; PostgreSQL 17.6 in Testcontainers on a shared developer Mac (load average ~17–38, so absolute times are inflated; compare the shape). Regenerate with `cd backend && ./gradlew test --tests 'com.systemwebstudio.data.org.OrgBenchmarkTests'` (src: docs/parallel/c3/ORGANIZATION_FINAL_BENCHMARK.md, D-C0-52 item 9).

| Case (wall ms, median / p95) | Run at the C0 candidate (load ~17) | Final-schema snapshot (load ~32-38) |
|---|---|---|
| Children of the broad node (301) | 1.89 / 3.86 | 8.48 / 16.86 |
| Children of a spine node | 0.36 / 0.55 | 2.25 / 7.34 |
| Ancestors, depth 64 | 0.61 / 1.18 | 2.80 / 4.39 |
| Large subtree (1,602) | 13.79 / 31.59 | 16.24 / 23.63 |
| Full tree (2,000 units, bounded) | 31.95 / 37.07 | 56.20 / 157.17 |
| Counts of every unit (bulk; used by the tree call) | 139 / 200 | 340.62 / 811.38 |
| Directory first page | 9.28 / 11.51 | 38.32 / 88.71 |
| Directory deepest page (offset 9,900) | 18.62 / 21.70 | 95.28 / 188.31 |
| Directory name search | 21.08 / 23.75 | 55.26 / 97.21 |
| Directory org filter, large subtree | 62.66 / 90.44 | 104.67 / 141.07 |
| Move of a 151-unit subtree | 2.00 / 4.40 | 59.15 / 155.65 |

Initial targets (children p95 < 200 ms; directory/search p95 < 300 ms) are met in both runs. Concurrency (pool 10 = production default, 100 simultaneous threads): 100 mixed operations over 10 tenants ≈ 154–174 ops/s; 100 structural moves in one hot tenant ≈ 86–165 ops/s with 0 deadlocks; with `lock_timeout` 20 ms the contended moves end as clean `LOCK_TIMEOUT` (67 of 100 in one run); 50 A↔B move pairs end as exactly 50 OK + 50 CYCLE; the graph stayed acyclic in all tenants. With 100 threads on a 10-connection pool up to 99 threads wait for a connection: **the Hikari pool, not the lock, is the first queue**. No `pg_trgm`; the directory search is a bounded scan (~14 ms EXPLAIN at 12k users).

---

## 12. Known limits (stated plainly)

- **Limit races**: the 20-positions-per-membership and 20-memberships-per-employee bounds are check-then-insert; a rare race can end at 21 (best-effort, not security).
- The tree call computes counts for the whole tenant in one statement (~140 ms median on 2,000 units / 10,000 employees on the benchmark host); a lazy UI that expands one level should prefer `flat` + detail (~30 ms per level).
- No pagination of units or of positions/grades (tenant structures are bounded); the tree is returned whole.
- The directory search is a bounded scan without a trigram index.
- Move does not re-check that the involved types are still active.
- The in-memory double is not PostgreSQL proof; PostgreSQL behaviour is proven only by the C3/C0 tests of 11.2.
- The earlier "move only" wording of the structural-lock KDoc is no longer present in `M/organization/**` (a grep for "move only", "ONE operation", "Known, documented limit" finds nothing); the code and 5.3 agree.
- A tenant with organization rows cannot be purged by `DELETE` (RESTRICT); a tenant-purge job needs an explicit design.
- The employee e-mail/username existence is visible to a Tenant Admin (`USERNAME_TAKEN` / `EMAIL_TAKEN`) — documented product decision.
- Public deployment does not contain the feature (section 10).

---

## Sources read

- Contracts and handoffs: `docs/parallel/c1/organization-employee-contract.md`, `docs/parallel/c1/final-iam-tenant-org-permission-contract.md`, `docs/parallel/c3/DYNAMIC_ORGANIZATION_PERSISTENCE.md`, `docs/parallel/c3/ORGANIZATION_FINAL_BENCHMARK.md`, `docs/parallel/c0/ORGANIZATION_API_CONTRACT_FOR_C5.md`, `docs/parallel/c5/ORGANIZATION_UI.md`, `docs/contracts/v2/tenant-permission.md` (§5b, §5c), `docs/parallel/DECISIONS.md` (D-C0-52, D-C0-57, D-C0-58, D-C0-59), `docs/parallel/DECISIONS.md` D-C0-60 and D-C0-61, `docs/parallel/c5/C5_NEXT_SESSION_HANDOFF.md` (§11) and `docs/parallel/c5/audit/FINAL_HARDENING_2026-10-10.md`.
- Code: `M/organization/{OrganizationControllers,OrganizationServices,EmployeeDirectoryService}.kt`, `M/data/org/` (file list), `M/access/{Permission,AccessService}.kt`, `M/access/adapters/PrincipalResolver.kt`, `M/admin/AdminOrg.kt`, `backend/src/main/resources/db/migration/V32__dynamic_organization.sql`, `application.yml`, `features/admin/{console/sections.tsx,console/sectionPolicy.ts,OrganizationScreens.tsx,organization.ts}` (selected lines).
- Tests: names read from `backend/src/test/kotlin/com/systemwebstudio/{organization,data/org,tenancy,wiring,logic/workflow}`.

## Open questions / UNVERIFIED

Settled from `integration/v2`: the FQ-UI-01 / FQ-A11Y-02 fixes are on the branch; the membership limit has a test; test counts are `@Test` counts from the source; the superseded KDoc wording is gone.

1. **Portal URL prefix.** The portal-relative routes `/organization` and `/employees` are confirmed (E2E, section keys); how the deployed gateway exposes them (the C5 documents say `/admin/...`) is not re-derived here.
2. **Benchmarks** are copied from the C3 report and D-C0-52 and were measured on a loaded shared machine; no re-run was made.
3. **Flag state per environment.** `c0rc` has `ORGANIZATION_PERSISTENCE_ENABLED` on (D-C0-59); no document read states the flag value of any other long-lived environment other than "public API: feature absent". Confirm before claiming otherwise.
4. **Test totals** are quoted from D-C0-60 (256 suites, 2298 tests); re-count at `{{FINAL_RC_SHA}}` with `./gradlew test`.
