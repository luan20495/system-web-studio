# Security and Permission Model (canonical)

Audience: engineers taking over XWEB / System Web Studio. This document is the single description of **who may do what**, **how that is decided**, and **which test proves it**. It describes the system as it is in the final state: code at `9d2fc8b9758077ac880d80cfbec767e2e776f9c5`, release candidate `75643ad8700f42df05c3151c1d0875b063c81620`, public frontend `bc5c47f292d0`, public API `1006cbf441f6`.

Related canonical documents: `docs/ARCHITECTURE.md` (modules, planes), `docs/DYNAMIC_ORG.md` (organization model), `docs/contracts/v2/tenant-permission.md` (frozen vocabulary contract), `docs/contracts/v2/runtime-api.md` (runtime routes).

Citation convention: `(src: M/...)` = `backend/src/main/kotlin/com/systemwebstudio/...`, `(src: T/...)` = `backend/src/test/kotlin/com/systemwebstudio/...`. Other paths are repo-relative. Everything described is on `integration/v2`, including the C1 final hardening (D-C0-60) and the C4 workflow approval runtime with migration V33 (D-C0-61); the code was read from the local `integration/v2` ref.

Status vocabulary used: DONE / PARTIAL / BLOCKED / DEFERRED.

---

## 1. Hierarchy and boundaries

```
System (platform)                      users.system_admin  -> SYSTEM_ADMIN, platform scope only
 └─ Tenant (Company)                   tenants.status ACTIVE | SUSPENDED | DELETED ; tenant_members.role TENANT_ADMIN | MEMBER
     └─ Workspace                      workspaces.tenant_id NOT NULL ; workspace_members.role WORKSPACE_ADMIN | EDITOR | PUBLISHER | VIEWER
         └─ Project (App)              projects.workspace_id ; project_members.role OWNER | EDITOR | PUBLISHER | VIEWER ; lifecycle ACTIVE | ARCHIVED
```

- A workspace belongs to exactly one tenant; `workspace_members`, `projects`, `project_members` carry a `tenant_id` equal to their workspace's, enforced by composite foreign keys (src: docs/contracts/v2/tenant-permission.md §1).
- The default tenant is `00000000-0000-0000-0000-000000000001` (`TenantIds.DEFAULT`); all pre-tenancy data lives in it (src: M/tenancy/Tenant.kt:14-17). It can only be set to ACTIVE (409 `DEFAULT_TENANT_PROTECTED`) (src: docs/parallel/c1/final-iam-tenant-org-permission-contract.md §3.1).
- **The tenant is always derived from the resource, never from request input.** `TenantResolver.resolveForWorkspace` reads `workspaces JOIN tenants LEFT JOIN tenant_members` from the workspace id in the path (src: M/tenancy/TenantResolver.kt:19-36). A `tenantId` in a body is never read (src: M/organization/OrganizationControllers.kt header comment).
- Tenant-level administration lives under `/api/v1/admin/tenants/{tenantId}/…` where the tenant in the path is a *resource selector* that goes through `AccessService.forTenant`, not a trusted claim.

Five scopes decide authorization, each with its own source of truth. They are never merged:

| Scope | Source of truth | Gate function |
|---|---|---|
| Platform | `users.system_admin AND users.enabled`, read live | `AccessService.forPlatform`, `AdminGuard` (src: M/access/AccessService.kt:146-150, M/admin/AdminSupport.kt) |
| Tenant | `tenant_members` (role, active) + `tenants.status` | `AccessService.forTenant` (src: M/access/AccessService.kt:117-133) |
| Workspace | `workspace_members` (role, active) after the tenant gates | `AccessService.forWorkspace` → `AccessEvaluator.workspace` |
| Project | `project_members` (role, active) on top of an active workspace role | `AccessService.forProject` → `AccessEvaluator.project` |
| Public site | the ACTIVE public release of a published site (no user) | `PublicSiteAuthorizer` |

---

## 2. Actors

`ActorKind { USER, SYSTEM, APP_TOKEN, SERVICE, PUBLIC_SITE }` is the one definition for the backend (src: M/tenancy/TenantContext.kt:15). C4's own `logic.action.ActorKind` deliberately has no `PUBLIC_SITE`; converting one for the action/workflow runtime throws, i.e. denies (src: M/tenancy/TenantContext.kt:10-14, T/wiring/ActorKindsTests.kt).

| Actor | Who | Authorization | Where decided |
|---|---|---|---|
| `USER` | A logged-in account (session cookie) | Permissions from the matrices in section 4, recomputed from the database on every request | `AccessService`, `AccessPort`, `GatewayAuthorizer`, `PrincipalResolver` |
| `PUBLIC_SITE` | The anonymous visitor of a published site, seen through the same-origin sites gateway | Exactly one operation: `QUERY_EXECUTE` of a query the ACTIVE public release lists, LIVE mode only. No user id, role, membership, permission set | `PublicSiteAuthorizer` (src: M/access/adapters/PublicSiteAuthorization.kt:132-175) |
| `SYSTEM`, `SERVICE`, `APP_TOKEN` | Reserved actor kinds | **Denied by every C1 adapter.** This is a *temporary V2 policy*: the frozen v2 contract defines runtime permissions for end users only; lifting it needs a contract change approved by C0 (src: M/access/adapters/AccessDecision.kt:24-33, `ActorPolicy.userOrNull`) | `ActorPolicy` |

Notes:
- `ActorKind.APP_TOKEN` is **not** the `X-App-Token` that a server-app container uses against the legacy connector proxy (section 11.4). That is a separate, older mechanism with its own checks.
- Other non-user callers are authenticated by their own channel and are **not** `ActorKind`s: the build runner and runtime runner (`/internal/**`, token checked in the controller), SCIM (`/scim/v2/**`, bearer token), Prometheus (`/actuator/prometheus`, scrape token, role `METRICS`), data webhooks (`POST /api/v1/webhooks/data/{endpointId}`, HMAC signature verified in the handler) (src: M/identity/SecurityConfiguration.kt:113-139, 171-185).
- A `PublicSitePrincipal` is only created by `PublicSiteAuthorizer` after a successful decision; its audit shape carries actor kind, site slug, release id and query id, never a visitor identity (src: M/access/adapters/PublicSiteAuthorization.kt:50-64).

---

## 3. Canonical permission codes

### 3.1 Vocabularies

| Vocabulary | Size | Where | Used for |
|---|---|---|---|
| **Canonical** (`PermissionCodes.CANONICAL`) | 21 | M/access/Permission.kt:47-52 | What `/auth/me` returns and what C4 asks C1 about |
| **AppDefinition** (`app.definition.PermissionCodes.ALL`) | 14 | M/app/definition/PermissionCodes.kt | The only codes allowed in `PermissionDef.permission` of an app |
| Storage enum `Permission` | 26 constants | M/access/Permission.kt:8-30 | What the matrix grants and controllers check |

21 = the 14 AppDefinition codes + `MEMBER_MANAGE` + the six organization codes. The mirror in TypeScript is `packages/types/src/contract/v2/permissions.ts` (`PERMISSION_CODES` 14, `PORTAL_PERMISSION_CODES` 1, `ORG_PERMISSION_CODES` 6, `CANONICAL_PERMISSION_CODES` 21). Guard `tests/guards/permission-mirror.mjs` (rules `PERMISSION-MIRROR-APP|ORG|CANONICAL|OBSOLETE`) fails the frontend gate when the backend sets, the TS sets and `docs/contracts/v2/tenant-permission.md` drift.

Legacy storage names stay for ~56 call sites and are mapped: `PROJECT_READ`→`APP_VIEW`, `PROJECT_EDIT`→`APP_EDIT`, `PROJECT_PUBLISH`→`APP_PUBLISH`, `PROJECT_MEMBERS`→`APP_SHARE` (temporary: `APP_SHARE` has no constant of its own until sharing lands) (src: M/access/Permission.kt:39-44). A boundary accepts **canonical codes only**: `PermissionCodes.fromCode("PROJECT_READ")` is null (src: M/access/Permission.kt:66-67, T/tenancy/PermissionCanonicalTests.kt).

**Internal storage constants (enforced, never exposed in `/auth/me`, never valid in `PermissionDef`):** `PROJECT_SETTINGS`, `PROJECT_DELETE`, `PROJECT_CREATE`, `AUDIT_READ`, `REGISTRY_WRITE` (src: M/access/Permission.kt:57-64). `REGISTRY_WRITE` is held by no role; only the legacy bypass (4.6) holds it, because that set is `Permission.entries` (src: M/access/Permission.kt:122). Verified: `git grep REGISTRY_WRITE` on `integration/v2` finds the declaration (M/access/Permission.kt:10), two KDoc mentions in the same file and two assertions in T/tenancy/PermissionCanonicalTests.kt; **no controller or service checks it**, so it grants nothing today. It is a vestigial constant; do not rely on it and do not grant it.

**Not permissions (must never be introduced):** `ORG_MANAGE`, `T-ORG-MANAGE`, `ORG_ADMIN`, `DEPARTMENT_MANAGE`, `ORG_VIEW`, `ORG_EDIT`, `ORG_MEMBERS`, `EMPLOYEE_ADMIN`, `POSITION_MANAGE`, `GRADE_MANAGE` etc. Guard `ORG-FAIL-CLOSED-OBSOLETE` and `PERMISSION-MIRROR-OBSOLETE` fail the gate if one appears (src: docs/contracts/v2/tenant-permission.md §5b, tests/guards/permission-mirror.mjs). `DEPARTMENT_MANAGER` exists only as an *approver kind* in the workflow vocabulary; it is not a permission.

**Deferred, documented proposal:** `APPROVAL_DECIDE`. It does **not** exist in this RC (section 10.5).

### 3.2 Meaning, enforcement point, holders

Holders use the role names of section 4. "WA" = WORKSPACE_ADMIN. Project roles combine with workspace roles (4.5).

| Code | Storage constant | Exact meaning | Enforced by | Held by |
|---|---|---|---|---|
| `APP_VIEW` | `PROJECT_READ` | See the project. **Without it the project does not exist for the caller (404 `PROJECT_NOT_FOUND`).** Also the only right kept on an ARCHIVED project (with `AUDIT_READ`). Studio admission = `APP_VIEW` in some workspace or project scope | `AccessEvaluator.project` (src: M/access/AccessEvaluator.kt:75-84); private-site view (M/publish/SiteControllers.kt, SiteService.kt) | Project VIEWER/EDITOR/PUBLISHER/OWNER; WA |
| `APP_USE` | `APP_USE` | Use the app as an end user at runtime (LIVE): run actions, workflows, read bound data | Runtime LIVE query (M/wiring/AppRuntimeDataController.kt:66), `ActionRuntime` / `WorkflowEngine` ask `APP_USE` first (M/logic/action/ActionRuntime.kt:291, M/logic/workflow/WorkflowEngine.kt:148) | Every project role; WA |
| `APP_EDIT` | `PROJECT_EDIT` | Change the draft: schema versions, assets, forms, prompts, AI planner/tools, code changes, packages, templates, data-source management of the project, and **TEST mode** of the runtime | Many controllers (`SchemaVersionController`, `AssetController`, `AppRuntimeActionController:55,81`, `AppRuntimeDataController:66`, …); `AccessPort` TEST mode (M/access/adapters/AccessPort.kt:36) | Project EDITOR, OWNER; WA |
| `APP_PUBLISH` | `PROJECT_PUBLISH` | Publish, roll back, unpublish; domains; publish-config (incl. the public-data acknowledgement); server-runtime rollback/stop | `PublishController`, `SiteControllers`, `Domains`, `PublishConfigApi`, `ServerRuntime` | Project PUBLISHER, OWNER; WA |
| `APP_SHARE` | `PROJECT_MEMBERS` | Manage the members of a project | `MemberController` (4 call sites) | Project OWNER; WA |
| `DATA_SOURCE_VIEW` | same | Read data-source metadata (never credentials) | `GatewayAuthorizer` op `DATASOURCE_READ` | Project EDITOR, OWNER; WA |
| `DATA_SOURCE_MANAGE` | same | Create/update/delete data sources, rotate credentials, schema discovery, cache refresh, sync, webhooks | `GatewayAuthorizer` ops `DATASOURCE_MANAGE`, `SCHEMA_DISCOVER`, `CACHE_REFRESH`, `SYNC_MANAGE`, `WEBHOOK_MANAGE`, and (with `QUERY_EXECUTE`) `SCHEMA_SAMPLE` | **WA only** |
| `QUERY_EXECUTE` | same | Run an approved read query through the Data Gateway; also event subscriptions | `GatewayAuthorizer` ops `QUERY_EXECUTE`, `EVENTS_SUBSCRIBE`; runtime data route (M/wiring/AppRuntimeDataController.kt:67) | Project EDITOR, OWNER; WA. **Project VIEWER does not** (needs published-state input) |
| `ACTION_EXECUTE` | same | Execute an action | `ActionRuntime` (src above) | Project EDITOR, OWNER; WA |
| `DATA_MUTATE` | same | Run an approved mutation; required *in addition to* `ACTION_EXECUTE` for every data-mutating action and for a workflow step that mutates | `GatewayAuthorizer` op `MUTATION_EXECUTE`; `ActionRuntime` (M/logic/action/ActionRuntime.kt:293) | **WA only** |
| `WORKFLOW_EXECUTE` | same | Start a workflow run | `WorkflowEngine` (:148-149 start, :370-371 per step) | **WA only** |
| `WORKFLOW_MANAGE` | same | Read/cancel runs started by someone else, manage schedules, **decide approvals** (10.5) | `WorkflowEngine` (:230 approval decision, :851 run read/cancel), `SchedulerService` (:345) | **WA only** |
| `TENANT_MANAGE` | same | Rename the tenant, create a workspace in it | `TenantController` | TENANT_ADMIN of that tenant; SYSTEM_ADMIN (platform scope, any tenant) |
| `TENANT_MEMBERS` | same | Tenant membership: list/add/change/remove members, member candidates, create tenant users; with `EMPLOYEE_MANAGE`, create/enable/disable employees | `TenantController`, `EmployeeController` | TENANT_ADMIN of that tenant; SYSTEM_ADMIN (platform scope) |
| `MEMBER_MANAGE` | same | Workspace member administration (workspace roles, project membership by an admin); tenant AI settings of the workspace; code-change merge policy | `MemberController`, `TenantAiApi:118,130`, `CodeChangeController:247` | **WA only** |
| `ORG_STRUCTURE_VIEW` | same | Read unit types, units, the tree | `OrganizationUnitTypeController`, `OrganizationUnitController` | TENANT_ADMIN of that tenant |
| `ORG_STRUCTURE_MANAGE` | same | Create/update/move/archive/restore units; create/update/disable/enable unit types | same | TENANT_ADMIN |
| `EMPLOYEE_VIEW` | same | Read the employee directory, memberships, held positions | `EmployeeController` | TENANT_ADMIN |
| `EMPLOYEE_MANAGE` | same | Create (with `TENANT_MEMBERS`), enable/disable (with `TENANT_MEMBERS`), manage memberships and position assignments | same | TENANT_ADMIN |
| `POSITION_GRADE_VIEW` | same | Read the position and grade catalogs | `PositionController`, `GradeController` | TENANT_ADMIN |
| `POSITION_GRADE_MANAGE` | same | Create/update/disable/enable positions and grades | same | TENANT_ADMIN |
| `AUDIT_READ` (internal) | same | Read a workspace's audit events | `AuditController:31` | WA |
| `PROJECT_CREATE` (internal) | same | Create projects in a workspace | `ProjectController` | WA, workspace EDITOR |
| `PROJECT_SETTINGS` (internal) | same | Edit project settings, server-app settings | `ProjectController:166`, `ServerRuntime` | Project EDITOR, OWNER; WA |
| `PROJECT_DELETE` (internal) | same | Delete / archive / restore a project | `ProjectController:198`, `ProjectLifecycle:47,54` | Project OWNER; WA |

(src for holders: M/access/Permission.kt:98-137; call-site list from `git grep "Permission\."` on `backend/src/main`.) `*_MANAGE` never implies `*_VIEW` in code; the matrix simply grants both together (src: docs/contracts/v2/tenant-permission.md §5b).

**Independence rule.** No check derives one permission from another. `APP_VIEW` does not imply `APP_USE`; `APP_EDIT` does not imply `APP_PUBLISH`; `ACTION_EXECUTE` does not imply `DATA_MUTATE` (src: T/tenancy/FinalPermissionSemanticsTests.kt `4a`). Three couplings are structural, not grants: (a) `forProject` is 404 unless `APP_VIEW`; (b) every project role bundles `APP_USE`, so "view without use" occurs only on an ARCHIVED project; (c) TEST mode needs `APP_EDIT` (src: docs/parallel/c1/final-iam-tenant-org-permission-contract.md §7).

---

## 4. Role → permission matrices

### 4.1 Platform (system) scope

| Principal | Holds | Notes |
|---|---|---|
| SYSTEM_ADMIN (default policy) | `TENANT_MANAGE`, `TENANT_MEMBERS` on **any existing tenant** (`PermissionMatrix.platformScope`) | Plus the platform-only routes guarded by `users.system_admin AND enabled` (tenant create/list/status, `/api/v1/admin/users/**`, admin console), which are **not** permission codes. No workspace, project, data, audit or organization permission. A non-member SYSTEM_ADMIN gets 404 on a workspace and 403 on a business route (src: M/access/Permission.kt:124-127, M/access/AccessEvaluator.kt:47-65) |
| SYSTEM_ADMIN that is also an active member of a tenant/workspace | platform scope + exactly that member role's codes | Held to the same tenant status gates as everyone (suspended → 403, deleted → 404) (src: M/access/AccessEvaluator.kt:52-55) |
| SYSTEM_ADMIN with legacy flag `app.tenancy.system-admin-business-access=true` | every constant everywhere; the TENANT_ADMIN set on every tenant | **Default false.** Single place of the bypass: `AccessEvaluator.workspace` and `AccessService.forTenant`. Must not be enabled as a shortcut (src: M/access/AccessService.kt:71-75, application.yml `app.tenancy`) |

A SYSTEM_ADMIN can never grant itself anything (403 `SELF_GRANT_FORBIDDEN`), is not a member candidate, and cannot be added through `PUT members` unless it already is a member (src: M/tenancy/TenantService.kt `setMember`/`eligibility`).

### 4.2 Tenant scope (`tenant_members.role`)

| Role | Codes |
|---|---|
| TENANT_ADMIN (own tenant only) | `TENANT_MANAGE`, `TENANT_MEMBERS`, `ORG_STRUCTURE_VIEW`, `ORG_STRUCTURE_MANAGE`, `EMPLOYEE_VIEW`, `EMPLOYEE_MANAGE`, `POSITION_GRADE_VIEW`, `POSITION_GRADE_MANAGE` (exactly eight) |
| MEMBER | none |

TENANT_ADMIN grants **no** implicit access to workspace/project business data (D-C1-12). In `/auth/me` the eight codes appear in `tenants[].permissions` for that tenant, and in the root `permissions[]` only for the primary tenant (src: M/access/Permission.kt:129-136, M/access/MeTenancy.kt:16-59, T/tenancy/TenantAdminAuthMeExactTests.kt).

### 4.3 Workspace scope (`workspace_members.role`)

| Role | Codes |
|---|---|
| WORKSPACE_ADMIN | APP_VIEW, APP_USE, APP_EDIT, APP_PUBLISH, APP_SHARE, DATA_SOURCE_VIEW, QUERY_EXECUTE, ACTION_EXECUTE, **DATA_SOURCE_MANAGE, DATA_MUTATE, WORKFLOW_EXECUTE, WORKFLOW_MANAGE, MEMBER_MANAGE** + internal PROJECT_SETTINGS, PROJECT_DELETE, PROJECT_CREATE, AUDIT_READ. Applies to **every project of the workspace** |
| EDITOR | none canonical; internal `PROJECT_CREATE` only (project permissions come from project membership) |
| PUBLISHER | none |
| VIEWER | none |

### 4.4 Project scope (`project_members.role`)

| Role | Codes (canonical) | Internal |
|---|---|---|
| VIEWER | APP_VIEW, APP_USE | – |
| EDITOR | APP_VIEW, APP_EDIT, APP_USE, DATA_SOURCE_VIEW, QUERY_EXECUTE, ACTION_EXECUTE | PROJECT_SETTINGS |
| PUBLISHER | APP_VIEW, APP_PUBLISH, APP_USE | – |
| OWNER | EDITOR's + APP_PUBLISH + APP_SHARE | PROJECT_SETTINGS, PROJECT_DELETE |

`DATA_SOURCE_MANAGE`, `DATA_MUTATE`, `WORKFLOW_EXECUTE`, `WORKFLOW_MANAGE` are held by **no project role**: they need WORKSPACE_ADMIN until explicit grants exist (sharing, T15). VIEWER does not get `QUERY_EXECUTE` (src: M/access/Permission.kt:99-112, T/tenancy/PermissionCanonicalTests.kt "default deny").

### 4.5 How scopes combine

- **Effective project permissions = workspace role set ∪ project role set** (`AccessEvaluator.project`). A project role counts **only through an ACTIVE workspace role** (or the legacy bypass): a stale `project_members` row of a user who is not a workspace member grants nothing (src: M/access/AccessEvaluator.kt:79-81).
- An **ARCHIVED** project is cut down to `{APP_VIEW, AUDIT_READ}` (except for archive/restore itself, `ignoreArchive`) (src: M/access/AccessEvaluator.kt:82).
- **Tenant permissions** come only from `tenant_members` (+ `platformScope` for SYSTEM_ADMIN). They are never combined with workspace or project sets.
- `/auth/me` keeps the scopes apart: root `permissions[]` = platform + **primary** tenant; `tenants[].permissions` = that tenant only; `workspaces[].permissions` = workspace role set; `projectScopes[]` = one row per project, never unioned (src: docs/contracts/v2/tenant-permission.md §5c).

### 4.6 Matrix summary

| Code | SYSTEM_ADMIN (platform) | TENANT_ADMIN | tenant MEMBER | WA | ws EDITOR/PUBLISHER/VIEWER | Project OWNER | Project EDITOR | Project PUBLISHER | Project VIEWER |
|---|---|---|---|---|---|---|---|---|---|
| APP_VIEW | – | – | – | Y | – | Y | Y | Y | Y |
| APP_USE | – | – | – | Y | – | Y | Y | Y | Y |
| APP_EDIT | – | – | – | Y | – | Y | Y | – | – |
| APP_PUBLISH | – | – | – | Y | – | Y | – | Y | – |
| APP_SHARE | – | – | – | Y | – | Y | – | – | – |
| DATA_SOURCE_VIEW | – | – | – | Y | – | Y | Y | – | – |
| QUERY_EXECUTE | – | – | – | Y | – | Y | Y | – | – |
| ACTION_EXECUTE | – | – | – | Y | – | Y | Y | – | – |
| DATA_SOURCE_MANAGE | – | – | – | Y | – | – | – | – | – |
| DATA_MUTATE | – | – | – | Y | – | – | – | – | – |
| WORKFLOW_EXECUTE | – | – | – | Y | – | – | – | – | – |
| WORKFLOW_MANAGE | – | – | – | Y | – | – | – | – | – |
| MEMBER_MANAGE | – | – | – | Y | – | – | – | – | – |
| TENANT_MANAGE | Y (any tenant) | Y (own) | – | – | – | – | – | – | – |
| TENANT_MEMBERS | Y (any tenant) | Y (own) | – | – | – | – | – | – | – |
| six organization codes | – | Y (own) | – | – | – | – | – | – | – |

---

## 5. How a decision is made (USER)

Chain for a project route: **authenticate** (session, then `ActiveUserFilter` reads the user row: disabled/deleted → 401 `ACCOUNT_DISABLED`) → **resolve tenant from the workspace** → **tenant gates** → **workspace role** → **project role** → **permission check** (`ctx.require(Permission.X)` → 403 `FORBIDDEN` "Missing permission: X") → business validation.

`AccessEvaluator` is the **only** implementation of the workspace/project decision. It is pure; `AccessService` (single resource) and `ProjectScopeResolver` (bulk, for `/auth/me`) both load data and then call it, so the two paths cannot diverge (src: M/access/AccessEvaluator.kt:21-29, M/access/ProjectScopeResolver.kt:21-29, T/identity/authme/ProjectScopeEquivalenceTests.kt).

| Step | Result on failure |
|---|---|
| Unknown workspace, or not a member and not SYSTEM_ADMIN | 404 `WORKSPACE_NOT_FOUND` |
| Ordinary user removed from the tenant, or tenant DELETED | 404 `WORKSPACE_NOT_FOUND` |
| Tenant SUSPENDED | 403 `TENANT_SUSPENDED` |
| No active project / no `APP_VIEW` | 404 `PROJECT_NOT_FOUND` |
| Permission missing, caller is inside the scope | 403 `FORBIDDEN` |

Tenant-level routes: `forTenant` → SYSTEM_ADMIN gets platform scope on any existing tenant (DELETED included, but member-role codes are added only for a non-DELETED tenant); anyone else needs an **active** membership in a non-DELETED tenant, else 404 `TENANT_NOT_FOUND`. `forTenant` does not look at SUSPENDED; writes call `requireTenantWritable` afterwards (section 8) (src: M/access/AccessService.kt:117-143).

Authorization order of every organization route: session (401) → `forTenant` (404 stranger) → `require(code)` (403) → for `*_MANAGE`, `requireTenantWritable` (403 `TENANT_SUSPENDED`) → service (src: M/organization/OrganizationControllers.kt:23-24).

Nothing authorizes from a cached value: roles, memberships, tenant status and permissions are re-read each request; a revoked permission, removed membership, suspended tenant, disabled user or logout takes effect on the next request of the same session (src: docs/contracts/v2/tenant-permission.md §5c.6, T/tenancy/FinalIamLifecycleTests.kt).

`/auth/me` costs a constant **6 SQL statements** regardless of the number of memberships (T/identity/authme/AuthMeQueryComplexityTests.kt, TenantCardinalityQueryTests.kt). The response grows linearly with visible projects (no pagination; contract change needs C0).

---

## 6. Rules and the code/test that enforces each

| # | Rule | Enforced by | Proven by |
|---|---|---|---|
| R1 | **A role name is not authorization.** Roles are inputs to `PermissionMatrix`; handlers decide by `Permission`. `roles[]`, `workspaces[].role`, `tenantRole`, `tenants[].role` are informational. Clients gate on canonical codes only. Remaining role-name strings only map a persisted role to the matrix, parse input, enforce last-admin data rules, or select approvers | `PermissionMatrix`; `MeTenancy` KDoc; `AccessContext.seesAllProjects` is `systemAdminBypass || Permission.PROJECT_READ in permissions`, not a role-name comparison (src: M/access/AccessService.kt:39); the dead role helper `TenantContext.isTenantAdmin` was removed (D-C0-60 item 1) | T/tenancy/MultiTenantAuthMeTests.kt (`H`: source guard on tenant/organization controllers), `H the server decides from the membership, never from the role field`; T/tenancy/AccessAdaptersTests.kt; T/tenancy/HardeningLifecycleTests.kt `D` (project list: WORKSPACE_ADMIN sees every project, other roles only their own) |
| R2 | **Organization membership is not app authorization.** Unit membership, relation type (MEMBER/MANAGER/HEAD/…) grant nothing. `access/**` and `identity/**` never read organization data | Structure of `Permission.kt`/`PermissionMatrix` | T/tenancy/FinalPermissionSemanticsTests.kt `4c`; T/organization/OrganizationAuthorizationTests.kt; T/wiring/RuntimeAuthorizationApiTests.kt "organization membership alone"; T/logic/workflow/RuntimeAuthorizationTests.kt `21` |
| R3 | **Position / grade is not authorization.** Catalogs are business classification | same | FinalPermissionSemanticsTests `4c`; V32 comments; frontend guard `ORG-RELATION` (tests/guards/org-source-guards.mjs) |
| R4 | **The tenant boundary always wins.** Tenant derived from the resource; gateway/port compare the claimed tenant with the workspace's and deny on mismatch; every store call is tenant-first | `TenantResolver`; `GatewayAuthorizer:59`, `AccessPort:34` | T/tenancy/AccessAdaptersTests.kt `gateway - cross tenant…`; T/data/gateway/GatewayTests.kt `tenant b cannot reach tenant a data source query or cache`; T/isolation/IsolationApiTests.kt; T/tenancy/TenantInsertPathsGrepTest.kt; T/tenancy/SecurityIdMatrixTests.kt `1ab…1f` |
| R5 | **Workspace and project scopes do not leak.** A role in workspace A grants nothing in B; a project role grants nothing in another project; the publisher of app A has no authority over app B; secondary-tenant codes never authorize another tenant | `AccessEvaluator`; `/auth/me` keeps scopes separate | IsolationApiTests `a role in one workspace grants nothing in another`; T/publish/PublishAuthorizationTests.kt (other app, forged deploymentId); T/tenancy/ProjectScopedAuthMeTests.kt; MultiTenantAuthMeTests `A B C` |
| R6 | **Direct URL / API fails closed.** Missing session 401; foreign/unknown resource 404 (same code and message); in-scope but unauthorized 403 (section 7) | `ApiException`, `AccessEvaluator` | IsolationApiTests `an outsider learns nothing…`; PublishAuthorizationTests; RuntimeAuthorizationApiTests `direct API…`; SecurityIdMatrixTests, SecurityRuntimeRoutesMatrixTests |
| R7 | **Suspended/deleted tenant semantics** (section 8) | `tenantGates`, `requireTenantWritable`, `TenantService.live` | FinalTenantStatusTests; TenantAccessTests; MultiTenantAuthMeTests `F`,`G`; HardeningLifecycleTests `A1-A4` |
| R8 | **Last-admin rules** (section 8.3) | services | MemberApiTests; TenantServiceTests; AdminApiTests; HardeningRaceTests, HardeningLockTests |
| R9 | **No self-grant.** Nobody (SYSTEM_ADMIN, TENANT_ADMIN, WA, OWNER) adds itself or changes its own role: 403 `SELF_GRANT_FORBIDDEN` | `TenantService.setMember`, `MemberController` | T/tenancy/PrivilegeEscalationTests.kt; AdminTransferOwnershipSelfGrantSpec.kt; UserProvisioningHierarchyTests `J` |
| R10 | **Default deny for new constants.** A constant is granted only where `PermissionMatrix` says so; unknown code, blank, lower-case or legacy names are not canonical | `PermissionCodes.fromCode` | PermissionCanonicalTests (`default deny…`, `unknown, blank, lower-case…`) |
| R11 | **Gateway decides on permission + scope only** (section 10.1) | `GatewayAuthorizer` | AccessAdaptersTests; GatewayAuthorizerNoOracleTests |
| R12 | **PUBLIC_SITE: only `QUERY_EXECUTE` on the active public release** (section 10.4) | `PublicSiteAuthorizer` | PublicSiteAuthorizationTests (16), PublicDataEndpointTests, PublicQueryAllowListTests |
| R13 | **Secrets only server-side; credential never returned** (section 13) | `ResolvedCredential`, `CredentialVault` | SecurityPrimitivesTests, ManagementHttpTests, DataSourceAdminTests |
| R14 | **Audit is append-only** (section 12) | DB trigger | AuditApiTests |
| R15 | **Runtime re-authorization.** Permission is re-checked before every effectful workflow step; a durable run is not an authority token | `WorkflowEngine.authorityFailure` | RuntimeAuthorizationTests (27 `@Test`), RuntimeAuthorizationApiTests (14 `@Test`) |

---

## 7. Denial semantics: 401, 403, 404

| Status | When | Codes |
|---|---|---|
| 401 | No session | `AUTHENTICATION_REQUIRED` (src: M/identity/SecurityConfiguration.kt:188-190) |
| 401 | Session's account disabled/deleted | `ACCOUNT_DISABLED` (src: M/identity/ActiveUserFilter.kt) |
| 401 | Login failure (wrong password, unknown user, **disabled** user, pending account) | `INVALID_CREDENTIALS` |
| 403 | Caller is inside the scope but lacks the permission | `FORBIDDEN` ("Missing permission: X") |
| 403 | Platform route, not an enabled SYSTEM_ADMIN | `ADMIN_REQUIRED` (answered before any lookup, so it is not an existence oracle) |
| 403 | Suspended tenant: workspace routes, organization/tenant-admin writes | `TENANT_SUSPENDED` |
| 403 | Acting on oneself | `SELF_GRANT_FORBIDDEN` |
| 403 | Missing/invalid CSRF token | `CSRF_INVALID` |
| 404 | **Unknown id, or an id of another tenant/workspace/project, or a tenant/workspace the caller does not belong to: exactly the same answer** | `TENANT_NOT_FOUND`, `WORKSPACE_NOT_FOUND`, `PROJECT_NOT_FOUND`, `USER_NOT_FOUND`, `MEMBER_NOT_FOUND`, `EMPLOYEE_NOT_FOUND`, `ORG_UNIT_NOT_FOUND`, … |
| 404 | PUBLIC_SITE: every refusal, whatever the cause | `QUERY_NOT_FOUND` (there is no 401 and no 403 for a visitor: a 403 would reveal that a query exists but is not public) |

Rule of thumb: **404 when the caller must not learn the resource exists; 403 when the caller is of the scope and is told what is missing.** A plain MEMBER of a tenant gets 403 (not 404) on tenant-admin routes because it belongs to the tenant (src: docs/parallel/c1/final-iam-tenant-org-permission-contract.md §8). A foreign data source is the same 404 as an unknown one (DECISION A, 10.1).

**Known deviation (OPEN, owner C2):** `GET /api/v1/projects/{id}` answers `WORKSPACE_NOT_FOUND` for a project of another tenant / unjoined workspace and `PROJECT_NOT_FOUND` for a random UUID. Both are HTTP 404, so only the error *code* distinguishes them (existence oracle by code). Verified on `integration/v2`: the handler returns `PROJECT_NOT_FOUND` only when the id is unknown and lets the `WORKSPACE_NOT_FOUND` of `forProject` escape unchanged (src: M/project/ProjectLookupController.kt:19-23; the file is untouched since its creation commit; T/asset/AssetReferenceTests.kt:52-58 asserts only the status, 404). Suggested fix recorded by C1: map `WORKSPACE_NOT_FOUND` to `PROJECT_NOT_FOUND` in that handler and add a test comparing the codes. Status: OPEN.

---

## 8. Tenant lifecycle, account lifecycle, last-admin

### 8.1 Two independent lifecycles

| | Global account | Tenant employee / membership |
|---|---|---|
| Column | `users.enabled` | `tenant_members.active` (one row per tenant and user) |
| Who changes it | Only an enabled SYSTEM_ADMIN (`PATCH /api/v1/admin/users/{id}/status`; SCIM `active=false`) | A holder of `TENANT_MEMBERS` on that tenant (`PUT`/`DELETE …/members/{u}`, employee `enable`/`disable` which also needs `EMPLOYEE_MANAGE`) |
| Sessions | A disable **deletes every session of the username**; the next request answers 401. Re-enable does not resurrect old sessions | **No session is touched.** The next request recomputes access: tenant routes 404, its workspaces 404, `/auth/me` omits them |
| Cross-tenant effect | All tenants (it is the account) | None. `users.enabled` is never written by a tenant route |
| Re-activation | Flips the flag | `PUT members` restores the given role; employee `enable` always restores **MEMBER** (a former TENANT_ADMIN does not get the role back); a platform-disabled account answers 422 `USER_DISABLED` |

(src: docs/parallel/c1/final-iam-tenant-org-permission-contract.md §1; tests: T/tenancy/FinalIamLifecycleTests.kt `1a`,`1b`,`2a`,`2b`; T/tenancy/HardeningSessionTests.kt `C1`-`C3`.)

### 8.2 Tenant status

| Route family | ACTIVE | SUSPENDED | DELETED |
|---|---|---|---|
| Workspace/project business routes, ordinary member | by matrix | 403 `TENANT_SUSPENDED` | 404 `WORKSPACE_NOT_FOUND` |
| Same, SYSTEM_ADMIN acting through a membership (legacy flag off) | by matrix | 403 | 404 |
| Same, SYSTEM_ADMIN non-member | platform scope only | same | same |
| Organization / employee / position / grade **reads** | allowed | allowed | 404 `TENANT_NOT_FOUND` (admin), 403 (platform scope) |
| Organization **writes**, tenant rename, `POST /{t}/workspaces`, `POST /{t}/users`, `PUT`/`DELETE /{t}/members/{u}` by a Tenant Admin | allowed | **403 `TENANT_SUSPENDED`** | **404** |
| Same writes by the platform operator | allowed | allowed (repair) | **404** (gone for everybody) |
| `PATCH /{t}/status` | platform only | platform only | platform only (the only way back; DELETED is soft and not terminal) |
| `/auth/me` | listed | listed with capabilities; its workspaces carry `permissions: []` | absent |
| Public site | served | refused (`TenantGate`) | refused |

Order matters: the permission is checked **before** writability, so a plain member still sees 403 `FORBIDDEN`, and a stranger never learns the status (src: M/access/AccessService.kt:135-143). The freeze of tenant-admin member/user/workspace writes is `TenantController.writable` (applied to `POST /{t}/workspaces`, `POST /{t}/users`, `PUT` and `DELETE /{t}/members/{u}`, and in `rename`: M/tenancy/TenantController.kt:96,112,143,157,162, rename at :76-80) and the DELETED refusal is `TenantService.live` (`createWorkspace`, `setMember`, `removeMember`: M/tenancy/TenantService.kt:43-48,91,212,226); `live` also takes `FOR NO KEY UPDATE` on the tenant row so membership/workspace changes of one tenant run one at a time. The platform operator is not frozen by SUSPENDED (it repairs the company) but is refused on DELETED. Tests: T/tenancy/FinalTenantStatusTests.kt, TenantAccessTests.kt, MultiTenantAuthMeTests.kt `F`,`G`, HardeningLifecycleTests `A1-A4`, SecurityIdMatrixTests `4a`,`4b`. No test covers DELETED→ACTIVE or the same-status re-audit (src: final contract §3.1).

Tenant hard delete is restricted by design (`ON DELETE RESTRICT` on organization tables); tenant lifecycle is `status` (src: docs/parallel/c3/DYNAMIC_ORGANIZATION_PERSISTENCE.md §8).

### 8.3 Last-admin rules

| Scope | Rule | Code | Test |
|---|---|---|---|
| Tenant | The last active TENANT_ADMIN can be neither demoted nor removed | 409 `LAST_TENANT_ADMIN` (`TenantService.assertNotLastAdmin` locks the active TENANT_ADMIN rows `FOR UPDATE ORDER BY user_id` before counting: M/tenancy/TenantService.kt:235-238) | TenantServiceTests, UserProvisioningHierarchyTests `I`, HardeningRaceTests `B1-B3`, HardeningLockTests |
| System | The last enabled SYSTEM_ADMIN cannot be disabled or revoked; nobody disables/changes itself (`CANNOT_DISABLE_SELF`, `CANNOT_CHANGE_SELF`) | 409 `LAST_SYSTEM_ADMIN` (`AccountService.setSystemAdmin` locks every enabled system-admin row before counting: M/identity/Accounts.kt:209-213) | AdminApiTests; HardeningRaceTests `B4` |
| Workspace | The last WORKSPACE_ADMIN cannot step down or be removed | 409 `LAST_ADMIN` (M/member/MemberController.kt:109,122) | MemberApiTests `an admin cannot change their own role and the last admin…`, `concurrent removal of two admins leaves at least one` |
| Project | The last OWNER cannot be demoted or removed | 409 `LAST_OWNER` (:171,185) | MemberApiTests `project members…` |

`DELETE /{t}/members/{self}` has no self check: a Tenant Admin may remove itself while another TENANT_ADMIN exists (src: final contract §5).

---

## 9. Identity: sessions, CSRF, CORS, credentials

| Control | Implementation | Source |
|---|---|---|
| Session store | Spring Session in Redis, indexed by username (enables revoke-by-username); survives API restarts | application.yml `spring.session`; T/identity/RedisSessionTests.kt, SessionRestartTests.kt |
| Cookie | `STUDIO_SESSION`, `HttpOnly`, `SameSite=Lax`, `Secure` by default (`COOKIE_SECURE=false` is for local http only); production validator refuses a non-Secure cookie | M/identity/SessionConfiguration.kt:13-24; M/common/ProductionConfigValidator.kt:23 |
| Idle timeout | Default 20 m; login sets it from setting `session.timeout-minutes`. **No absolute session lifetime** (documented P2, product decision) | application.yml; final contract §2.1; hardening report §3 |
| Session fixation | No session exists before login; the id is rotated on login | T/identity/AuthSecurityTests.kt `anonymous protected API is 401 … and creates no session` |
| CSRF | `CookieCsrfTokenRepository.withHttpOnlyFalse()` (cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`) on **every** state-changing request including login. Only exemption: `POST /api/v1/webhooks/data/{endpointId}` (single path segment), which is authenticated by HMAC signature + timestamp + replay guard in the handler | M/identity/SecurityConfiguration.kt:37-38,171; T/identity/WebhookSecurityTests.kt, AuthSecurityTests `missing CSRF token is 403 CSRF_INVALID` |
| CORS | Exact origins only (`scheme://host[:port]`, no wildcard, path or `null`); credentials allowed; methods/headers fixed. Production requires https and refuses localhost | M/identity/SecurityConfiguration.kt:40-47,69-82; WebhookSecurityTests `CORS origins must be exact`; ProductionConfigValidatorTests |
| Headers | API: `default-src 'none'; frame-ancestors 'none'; base-uri 'none'`, referrer no-referrer, permissions policy, HSTS 2 y | M/identity/SecurityConfiguration.kt:172-178; T/common/HardeningTests.kt |
| Passwords | Argon2id (16 B salt, 32 B hash, 1 lane, 64 MiB, 3 iterations); policy 8–200 chars, letters and digits, ≥4 distinct, must not contain the username; no password is accepted by provisioning routes. Self-service sign-up (off by default) uses a weaker 6-character rule (product decision, docs/SECURITY.md) | M/identity/SecurityConfiguration.kt:51; final contract §4 |
| Brute force | Redis counters checked before Argon2: 5 failures / user and 50 / IP per 900 s; 429 + `Retry-After`; concurrent hashes capped (`login.max-concurrent-hashes`) | application.yml `app.rate-limit`, `app.login`; AuthSecurityTests `wrong password is 401 and repeated failures end in 429` |
| Activation / reset links | 32 random bytes base64url, **only the SHA-256 is stored** (`account_tokens`), 24 h TTL, single use, issuing a new link deletes unused ones; completing signs the account out everywhere | final contract §4 |
| Principal in session | `StudioUserDetails` holds the password hash for password login; the OIDC success handler calls `eraseCredentials()` on the loaded principal before the session is saved, so the Redis session never carries `users.password_hash` (a LOCAL account linked by e-mail via OIDC holds a real hash) | M/identity/Identity.kt:41-50; M/identity/oidc/OidcLoginHandlers.kt:67; T/tenancy/HardeningSessionTests.kt `C4`,`C5` (the OIDC handler itself needs an IdP and is covered by review, not by a test) |
| Sites chain | `/sites/**` is stateless: no session, no CSRF, no CORS, `securityContext` disabled, GET/HEAD plus exactly two anonymous POSTs (`/sites/*/_forms/*`, `/sites/*/_data/queries/*/run`); everything else `denyAll` | M/identity/SecurityConfiguration.kt:93-111 |
| Errors | Uniform body `{code, message, details?}` with request id; no stack traces (`server.error.include-*: never`); access logs never contain query strings, ids or credentials | application.yml; T/common/HardeningTests.kt |

Known identity limits (documented decisions/gaps, not hidden): after re-enabling an account, an activation/reset token issued before the disable is usable again within its 24 h TTL (OPEN, C0 + C1); the next request after an API disable answers 401 `AUTHENTICATION_REQUIRED` (session already deleted), not `ACCOUNT_DISABLED`, so clients must treat both as "signed out"; SCIM rename defeats revoke-by-username; login timing reveals disabled accounts; re-adding a user to a tenant as MEMBER restores old workspace/project rights because those rows stay active; the activation/reset link is returned to the *creator* (a Tenant Admin who creates a WORKSPACE_ADMIN account can log in as it) — recorded as a C0 policy decision, not closed (src: docs/parallel/c1/final-iam-hardening-report.md §3, final contract §1,§10.3).

---

## 10. Authorizers behind the runtime ports

All C1 adapters are **default deny**: any exception denies, `Denied.reason` is for audit only and is never returned to the caller (src: M/access/adapters/AccessDecision.kt:13-41).

### 10.1 Data Gateway authorizer (C3's port)

Policy: operation → required permissions (**all** required) (src: M/access/adapters/GatewayAuthorizer.kt:37-48):

| Operation | Permissions |
|---|---|
| `DATASOURCE_READ` | `DATA_SOURCE_VIEW` |
| `DATASOURCE_MANAGE`, `SCHEMA_DISCOVER`, `CACHE_REFRESH`, `SYNC_MANAGE`, `WEBHOOK_MANAGE` | `DATA_SOURCE_MANAGE` |
| `QUERY_EXECUTE`, `EVENTS_SUBSCRIBE` | `QUERY_EXECUTE` |
| `MUTATION_EXECUTE` | `DATA_MUTATE` |
| `SCHEMA_SAMPLE` | `DATA_SOURCE_MANAGE` **and** `QUERY_EXECUTE` (sampling reads real rows) |

Decision order: unknown operation → denied; PUBLIC_SITE → `PublicSiteAuthorizer.authorizeGateway`; actor must be USER (SYSTEM/SERVICE/APP_TOKEN denied); workspace required (tenant-level data sources have no permission holder: TENANT_ADMIN has no implicit data access, D-C1-12); an app version without a project is denied; `forProject`/`forWorkspace`; **claimed tenant must equal the workspace's tenant**; all permissions held; an app version must be a `project_versions` row of this project and workspace (src: M/access/adapters/GatewayAuthorizer.kt:51-71).

**DECISION A (final):** the authorizer does **not** check that a `dataSourceId` belongs to the caller's workspace. It decides on permission and scope only. Data-source ownership is C3's job and C3 answers the canonical 404 for a foreign **and** an unknown data source (`findInWorkspace`: both are null) so there is no existence oracle (a C1 check would have turned that 404 into a 403 for the same id). The attempted `GatewayAuthorizer.dataSourceBelongs` check was reverted and does not exist (D-C0-60 item 2; `GatewayAuthorizer.kt` is unchanged by the hardening; src: docs/parallel/c1/final-iam-hardening-report.md row 6 "REVERTED (decision A)", M/data/datasource/DataSourceService.kt:27, M/data/datasource/DataSourceAdminService.kt:218). Proven by T/data/datasource/DataSourceScopeTests.kt `another tenant is not found…`, T/data/gateway/GatewayTests.kt and T/tenancy/GatewayAuthorizerNoOracleTests.kt (2 tests: the decision is identical for own, foreign and unknown data-source ids, and a missing permission is denied the same way with or without an id); `GatewayAuthorizerDataSourceTests` no longer exists. At C1 itself there is therefore **no** ownership test: do not re-add one.

### 10.2 App access port (C4's `AccessPort`)

Chain: tenant enabled (`TenantGate`: existing ACTIVE tenant, else false) → canonical code known → mode LIVE or TEST → actor is a USER → workspace required → `forProject`/`forWorkspace` (tenant re-derived) → claimed tenant equals resolved tenant → permission held → for TEST mode also `APP_EDIT` (src: M/access/adapters/AccessPort.kt:25-38). Called before any input is looked at. `PrincipalResolver` addresses approvers/notification targets only inside the context tenant (enabled users with an **active** `tenant_members` row); `Group` and `DepartmentManager` targets are denied ("not available"); cross-tenant principals are refused until the cross-tenant share policy (T16) exists (src: M/access/adapters/PrincipalResolver.kt:32-77).

### 10.3 Runtime enforcement

`ActionRuntime` asks, in order, `APP_USE`, `ACTION_EXECUTE`, plus `DATA_MUTATE` for data-mutating action types and `WORKFLOW_EXECUTE` for a `START_WORKFLOW`. The workflow engine re-asks before **every effectful step**: a permission revoked while a run waits stops the next step, and a run whose creator lost the right does nothing when the worker takes it (src: M/logic/action/ActionRuntime.kt:288-296, M/logic/workflow/WorkflowEngine.kt `authorityFailure` :367-378 and its call at :382, T/logic/workflow/RuntimeAuthorizationTests.kt `11a-11d`, `20`).

### 10.4 PUBLIC_SITE

Public route: `POST /sites/{slug}/_data/queries/{queryId}/run` — anonymous, same origin via the sites gateway, no cookie/session/CSRF. Mounted only when `app.data-platform.enabled` **and** `app.sites.public-data.enabled` are true (both default false) (src: M/wiring/PublicDataController.kt:60-76, application.yml `app.sites.public-data`).

`PublicSiteAuthorizer.authorize` order: operation exactly `QUERY_EXECUTE` → mode exactly `LIVE` → query id `^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$` → **release re-verified in the database on every call** (site slug → project (active, not archived) → workspace → tenant ACTIVE and equal to the claimed one → `sites.current_deployment_id` = given release, `RUNNING`, `PUBLIC`) → optional version equality → release allow-list non-empty and containing the query. Every failure, including an exception, is `NotFound` (404 `QUERY_NOT_FOUND`) (src: M/access/adapters/PublicSiteAuthorization.kt:104-160).

The allow-list provider is `ReleaseSnapshotPublicQueryAllowList` (C2/C0): the queries the author marked `public: true`, mode READ, in the **immutable snapshot** of the version the release pins, and only while `publish_configs.public_data_approved` is true; no row, no approval, any error → empty set → everything refused (src: M/wiring/PublicDataController.kt:76-96). Without a provider bean the default is `DenyAllPublicQueryAllowList`. (The KDoc of `PublicSiteAuthorization.kt` still says no provider exists; the code and `PublicDataController` show it does — documentation lag.)

The approval can only become true through `PUT …/publish-config` / `adopt-draft` with `acknowledgePublicData=true` by a holder of `APP_PUBLISH`; `POST /publish` enforces it (422 `PUBLIC_DATA_NOT_APPROVED`, 409 `PUBLISH_POLICY_MISMATCH`) (src: docs/parallel/c2/H_C2_07_PUBLIC_DATA_APPROVAL.md). Known limit: the approval is a Boolean on the policy, not tied to a version, so data added after approval is covered by it.

Second line of defence: at the Data Gateway a PUBLIC_SITE needs the full server-derived scope (tenant, workspace, project, release version) and may do `QUERY_EXECUTE` only (M/access/adapters/PublicSiteAuthorization.kt:167-174). Public caps: body 16 KiB, 100 rows, 256 KiB response, Redis rate limits per IP burst (30/10 s), per site+IP (120/min), per site (1200/min) (application.yml `app.sites.public-data`). Tests: T/tenancy/PublicSiteAuthorizationTests.kt (16), T/wiring/PublicDataEndpointTests.kt, T/app/definition/PublicQueryAllowListTests.kt, PublicQueryAllowListCommitTests.kt, T/publish/PublicDataApprovalTests.kt.

Limit stated by the data pipeline: fetching REAL rows through a public site needs a data source the SSRF policy accepts (public DNS host, verified TLS); a local stack cannot provide one, so E2E-PD01 with a real source is not claimed (src: docs/parallel/DECISIONS.md D-C0-57 item 3).

### 10.5 Approvals

Workflow `APPROVAL` steps are decided through the existing **`WORKFLOW_MANAGE`**. There is **no** `APPROVAL_DECIDE` permission in this RC; it is a documented deferred proposal to C1 (a dedicated code would let a project grant "may decide approvals it is named on" without "may cancel any run"; the engine already passes `ResourceKind.APPROVAL` with the approval id, so the switch would be one constant in `decideApproval`: M/logic/workflow/WorkflowEngine.kt:230) (src: docs/parallel/DECISIONS.md D-C0-61 item 4; docs/parallel/audit/C4-FQ-WF-01-approval.md). Status: DONE (imported, durable store, migration V33 `approvals`). **Consequence, stated plainly:** because `WORKFLOW_MANAGE` is held only by WORKSPACE_ADMIN (4.3), a person who decides an approval must be a *named approver* **and** a workspace admin.

Route: `POST /api/v1/workspaces/{ws}/projects/{p}/app-runtime/workflow-runs/{runId}/approvals/{approvalId}/decision` (M/wiring/AppRuntimeActionController.kt:112; the controller is mounted only with `app.workflow.enabled=true`), body `{"decision":"APPROVE"|"REJECT","comment"?}` (strict: no identity fields). Authorization, in order:
1. tenant enabled; scope tenant → workspace → project of the run; a wrong scope is 404 `RUN_NOT_FOUND`, never 403;
2. `WORKFLOW_MANAGE` through the live C1 `AccessPort` (resource kind `APPROVAL`); denied → 403 before the approval is looked up (an unknown approval answers the same, so nothing is revealed);
3. the approval must belong to this run and app, else `APPROVAL_NOT_FOUND`;
4. `ApprovalService.decide`: the caller must be in the **approver snapshot** taken when the step started (explicit user ids resolved then; requester excluded unless `allowSelfApproval`). A role string, organization membership, MANAGER/HEAD relation, position or grade is **never** consulted at decision time;
5. the same decision by the same approver is idempotent; the opposite decision → 409 `APPROVAL_CONFLICT`; a final approval → 409 `APPROVAL_ALREADY_DECIDED`.

The approval store is the durable `approvals` table (migration V33, undo `docs/parallel/c0/undo/U33__approvals.sql`; `app.workflow.approvals=auto|jdbc|memory|off`, default `auto` = durable when the table exists, otherwise approvals stay unwired and a LIVE APPROVAL step stays `NOT_IMPLEMENTED`: M/wiring/AppRuntimeConfiguration.kt:177-195). Documented limit: `approvals.app_id` is a plain FK to `projects(id)` with no composite tenant FK (it would need a new unique constraint on `projects`); tenant consistency is enforced by the engine's scope check before the insert and by every query being keyed on `tenant_id` (D-C0-61 item 1). The run step view gains a nullable `approvalId` (M/wiring/RuntimeResponses.kt:110).

After approval the next step runs with the **run creator's** authority re-checked per effectful step, so an approver needs no `DATA_MUTATE` and cannot widen the creator's rights. Limits: approver kinds `Role`/`Group`/`DepartmentManager` are resolved once when the step starts (C1 answers users/roles only); `notifyTemplateRef` notifications are **not wired** (notify port is null); inbox/list endpoints are deferred. Tests (`@Test` counts): `ApprovalDecisionTests` (10), `JdbcApprovalStoreTests` (9), `WorkflowApprovalG3Tests` (7), `ApprovalJourneyE2ETests` (5), `ApprovalsV33FlywayTests` (3).

---

## 11. Outbound network safety: SSRF guard, connectors, trust store

### 11.1 The one address policy

`PublicAddress` (M/runtime/Gateway.kt:47-94) is the platform's **single** network-address policy. A host is public only if **every** address it resolves to is a global unicast address. Refused (fail closed): loopback, site-local/private, link-local (incl. 169.254.169.254), multicast, any-local, 0/8, CGNAT 100.64/10, 192.0.0/24, TEST-NETs, 198.18/15, 240/4, unique-local fc00::/7, IPv4-mapped/compatible, 6to4 2002::/16, Teredo 2001::/32, documentation ranges, `64:ff9b:1::/48`; NAT64 `64:ff9b::/96` is judged by the embedded IPv4. Spec test: `AddressRangeSpecTests`. C3 has **no second list of ranges** (src: M/data/datasource/AddressPolicy.kt:22-35).

### 11.2 Data Platform connectors (`data/datasource/**`)

Contract every connector honours (src: M/data/datasource/ConnectorSpi.kt:10-20):
1. All outbound access goes through `PinnedResolution` + `PublicAddressPolicy`: resolve **once**, check **every** returned address (one private address among public ones refuses the whole host), connect to the checked addresses only (closes the DNS-rebinding window); **no redirects**.
2. The credential is used inside the call and appears nowhere else (messages, logs, audit, results).
3. Only `ConnectorFailure` with fixed text escapes (no cause, stack, host, IP, SQL or URL).
4. Results are bounded in time, rows and bytes.
5. A data source of another tenant is never reachable (`ds.tenantId` compared with the request's).
6. Reads cannot write: a connector that cannot guarantee it does not offer `QUERY`.

`HostNamePolicy` rejects `localhost`, `.internal`, `.local`, `.localdomain`, `.lan`, IP literals (unless allowed), single-label names (compose/k8s service names) before any DNS (src: M/data/datasource/AddressPolicy.kt:52-67). REST connector: HTTPS GET of declared path templates on a registered base URL through `PinnedHttpsTransport` (verified TLS, no redirect, credential only as the configured header). Tests: T/data/datasource/AddressRangeSpecTests.kt, SecurityPrimitivesTests.kt (`one private address among public ones refuses the whole host`), rest/PinnedHttpsTransportTests.kt, rest/RestConnectorTests.kt, ConnectorSpiTests.kt.

### 11.3 PostgreSQL data sources and the trust store for data targets

- Default: **public internet addresses only**, through the same policy, so the platform DB and apps DB are unreachable because they are not public.
- `app.data-platform.postgres-targets.allowed-private`: the **explicit, server-side allow-list** of private `host:port` endpoints an operator exposes: exact entries, **port mandatory**, no wildcard/CIDR/`0.0.0.0`/link-local/multicast, at most `MAX_ALLOWED` (20) entries; a bad entry fails start-up. Empty in `application.yml` and in prod; only the `local` profile names the V1 local data target `127.0.0.1:15440`. Settable only in configuration, never through a data source's own config. The production validator refuses a loopback entry (src: M/data/datasource/postgres/PostgresTargetPolicy.kt:18-60, application.yml, application-local.yml, M/common/ProductionConfigValidator.kt:37-39).
- `app.data-platform.postgres-targets.denied`: extra targets refused even if allow-listed; **deny always wins**. The platform and apps databases are derived from the platform's own JDBC URLs and denied by address as well as by name (an alias or literal for a denied target is refused; a denied entry whose address cannot be resolved right now fails closed) (src: M/wiring/PostgresTargetPolicies.kt, PostgresTargetPolicy.kt:47-67).
- Connection: TLS **only**, `sslmode=verify-full` (chain and host name against the data source's `host`, not the pinned address) with `sslfactory=DefaultJavaSSLFactory`, i.e. **the JVM trust store**; a tenant cannot supply a CA file. `require` is never produced. Session read-only (`default_transaction_read_only=on`; off only for an approved mutation session of a data source configured `writable=true`), `statement_timeout`, `lock_timeout`, `idle_in_transaction_session_timeout`, bounded connect/socket timeouts; credentials are driver properties, never in the URL; the socket is pinned to the checked addresses (src: M/data/datasource/postgres/PostgresConnector.kt `PgConnectionProperties.build`). SQL is single-statement by construction (`SqlGuard`; T/data/datasource/postgres/SqlGuardTests.kt).
- **Trust store for the local/dev data target:** `scripts/data-target.sh` creates a dev CA and a JDK `cacerts` copy merged with it at `.run/data-target/truststore.jks` (ignored by git); `scripts/_env.sh` / the stack tooling pass it as `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=…` (src: scripts/data-target.sh:22-38, scripts/_env.sh:50-51). Proof recorded at the candidate: precheck `openssl s_client … -verify_return_error` = 0, `jcmd VM.system_properties` shows the trust store, and a data source `127.0.0.1:15440/shop` with the `shop_ro` credential answered `POST …/test` `{ok:true}` (src: docs/parallel/DECISIONS.md D-C0-59 item 4). Production uses the JVM's own CAs; the dev CA must never be installed there.

Tests: T/data/datasource/postgres/PostgresSessionSecurityTests.kt, PostgresConnectorTests.kt, T/wiring/PostgresTargetPoliciesTests.kt, PostgresTargetWiringTests.kt, PostgresTargetFlagOffTests.kt, T/common/ProductionConfigValidatorTests.kt.

### 11.4 Legacy connector proxy (ADR 0017) and other outbound calls

`ConnectorProxyController` (`/internal/connectors/{key}/**`): an app container calls the apps gateway with its `X-App-Token`; the gateway adds `X-Gateway-Token` (constant-time compared; wrong → 404); the API checks `app_runtimes.app_token_hash` (SHA-256), a grant in `project_connectors`, `status = APPROVED`, a declared operation (method + path template), no `..`/control characters, a per-project/key rate limit, `https` and same host as the base URL, `PublicAddress.isPublic(host)`; the credential is decrypted only there and the app never sees it; response ≤ 5 MB. Admin connector save validates `https`, no userinfo/query, a public host name (src: M/runtime/Gateway.kt:227-300). **Residual (L2, accepted):** this path resolves the host for the check and the JDK client resolves it again to connect, so a DNS-rebinding window remains; mitigated by admin-approved hosts only (src: docs/SECURITY.md "Independent code review" L2). Tests: T/runtime/ServerRuntimeTests.kt `connector proxy…`, T/runtime/PublicAddressTests.kt.

`PublicAddress` is also used by the TLS probe of custom domains (M/publish/Domains.kt:53). Tenant AI outbound calls are behind `app.tenant-ai.enabled` (default false) with the condition that the address is re-checked at call time (src: application.yml).

Build/runtime planes: builds run in hardened containers (non-root, read-only rootfs, caps dropped, no network at build step, mirror-only install) and server apps get one internal network each plus an HMAC-signed identity header (src: docs/SECURITY.md "Final verification before the pull request"; ADR 0010, 0017, 0019). Residual: on macOS the sandbox shares the Docker Desktop VM kernel (ADR 0010 note).

---

## 12. Audit

- `audit_events` is **append-only**: a DB trigger raises `audit_events is append-only` on UPDATE, DELETE and TRUNCATE (src: backend/src/main/resources/db/migration/V3__rbac_audit_project_settings.sql:46-57; T/audit/AuditApiTests.kt `audit rows cannot be updated or deleted, even by the application user`).
- `AuditService.record` joins the caller's transaction, so a rolled-back business change leaves no audit row, and an audit failure rolls the mutation back; it stores actor, workspace/project, action, resource, old/new JSON, IP, user agent (≤400), request id (src: M/audit/AuditService.kt:13-44).
- Only workspace admins read a workspace's audit (`AUDIT_READ`), never from another workspace (AuditApiTests).
- **Never audited:** passwords, activation tokens, hashes, credentials (organization: T/organization/OrganizationAuthorizationTests.kt `16`; provisioning: UserProvisioningHierarchyTests `O`; gateway: GatewayTests `audit records carry ids and counts but never values`).
- The credential-author lookup reads the actor id from the audit trail; the payload never held a secret (src: M/wiring/persistence/JdbcCredentialStore.kt).
- Known gap: `audit_events` has no `tenant_id` column (adding one needs a migration number from C0); the Admin Console shows audit diffs across tenants with the legacy flag OFF — listed as an open P1 owned by C0 (src: hardening report §3).

---

## 13. Secret handling policy

### 13.1 What is stored where

| Secret | Storage | Rule |
|---|---|---|
| User password | `users.password_hash`, Argon2id | Never returned, never in a session after hardening import (9) |
| Activation / reset token | `account_tokens`, SHA-256 only | One-time plaintext in the creation response; never audited |
| Data-source credentials | `data_credentials.ciphertext` (V28): `SecretsCrypto` AES-256-GCM (`v1:…`), key only from `SECRETS_MASTER_KEY` (base64, 32 bytes), tenant-scoped on every statement, separate table, never a column of the data-source row. Entries: ≤8, key `^[A-Za-z][A-Za-z0-9_]{0,31}$`, value ≤4000 | Write-only. Without the master key nothing can be sealed or opened (`SECRETS_UNAVAILABLE`). A credential reference of another tenant resolves to nothing (src: M/data/datasource/Credential.kt:27-107) |
| `ResolvedCredential` | In memory for the duration of one call | **Not a data class**: no generated `toString/equals/copy/componentN`; `toString()` = `ResolvedCredential(***)`. `PgTarget.toString()` = `PgTarget(***)`; `MutationExecRequest.toString()` hides parameter values |
| Webhook secrets | ciphertext; visible **once** at creation; rotation keeps the old one for a grace period only | T/data/sync/WebhookTests.kt |
| Legacy connector auth value | `connectors.auth_value_enc` (SecretsCrypto) | decrypted only in `ConnectorProxyController` |
| AI provider keys, server-app secrets | encrypted (AES-GCM, master key), write-only: no API returns them | V25 migration comment; T/runtime/ServerRuntimeTests.kt `secrets are write-only, encrypted at rest…`; never sent to AI |
| Service tokens / keys (environment only): `SECRETS_MASTER_KEY`, `APPS_GATEWAY_TOKEN` (≥32), `BUILD_RUNNER_TOKEN` (≥24 if set), `SCIM_TOKEN` (≥32 when enabled), `OIDC_CLIENT_SECRET` (≥12), metrics token, `FORMS_IP_SALT` (required), DB/Redis/RabbitMQ/MinIO passwords (≥12, no `local-only`/`changeme`), bootstrap admin password (≥14, mixed case) | Environment through `SecretProvider` / `@Value`; `ProductionConfigValidator` (profile `prod`) refuses unsafe values at start | M/integration/secrets/SecretProvider.kt; M/common/ProductionConfigValidator.kt:13-65 |

### 13.2 Redaction and "credential never returned"

- A `Redactor` removes every known secret value from text before it can reach a log/message (T/data/datasource/SecurityPrimitivesTests.kt `redactor removes every known secret value`).
- Management responses carry a **safe projection**; credential metadata exposes configured type names and times, never a value or a reference (T/data/gateway/ManagementHttpTests.kt `responses carry only the safe projection`, `the credential body is write only and strict`; T/data/datasource/DataSourceManagementTests.kt `credential metadata has configured type names and times but never a value or a reference`, `no management path writes the secret to a log or an audit event or an exception`; DataSourceAdminTests `a created data source … shows no credential`, `secrets pasted into the configuration are refused and nothing is stored`).
- Gateway: `no credential reaches a response an audit record or a log even when the connector blows up with it` (GatewayTests). Driver messages are never forwarded (SQLSTATE → fixed code/message).
- AppDefinition and AI operations cannot carry a URL, SQL, credential or code (T/app/definition/*Tests.kt, T/ai/planner/AppPlannerTests.kt).

### 13.3 What must never be committed

`.env`, `.env.local`, `.env.*.local`, `.run/` (generated env files, tokens, `.run/data-target/{truststore.jks,truststore.pass,ro.pw,rw.pw}`), `backups/`, `infra/keycloak/.generated/`, any key/token/cookie/password in docs, logs or evidence (src: .gitignore). `.env.example` contains only documented, loopback-only placeholders (`studio-local-only`, `studio-minio`), which `.gitleaks.toml` allow-lists together with test fixtures under `backend/src/test/` (src: .env.example, .gitleaks.toml). Per-stack credentials of E2E stacks live **outside the repository** (`~/.xweb-e2e-stack/<stack>/stack.env`, mode 600) (src: DECISIONS.md D-C0-59 item 2). Never print a password/token into a tool log (one slip is recorded in D-C0-59 item 4; the exposed value protected only a copy of public CAs).

### 13.4 gitleaks gate

`scripts/secret-scan.sh` runs `gitleaks git` over **all history** and `gitleaks dir` over the working tree (tracked and untracked, not ignored), `--redact`, config `.gitleaks.toml`, non-zero exit on any finding. It is a **manual/local** script: it is not part of `npm run gate:frontend` (that gate runs guards, builds and a bundle scan that blocks localhost/loopback/container names in browser output), and there is no GitHub Actions workflow (project rule: none). The build runner also runs `gitleaks dir` on source and output of every code-project build (src: scripts/secret-scan.sh, scripts/gate-frontend.mjs, workers/runner/runner.mjs:63-70). Last recorded clean run: all history + tree on 2026-10-05 (docs/SECURITY.md). `UNVERIFIED`: a gitleaks run at `75643ad8700f42df05c3151c1d0875b063c81620`; `docs/parallel/BASELINE.md` records an earlier "not run (gitleaks missing)". The owner should run `./scripts/secret-scan.sh` at the RC SHA and record the result.

---

## 14. How to add a permission safely

1. **Decide whether it should be a permission at all.** A role, a unit, a position or a grade is never a reason. A permission names an *action on a scope*. If a platform-only operation (tenant create, account admin) needs gating, it uses `forPlatform`/`AdminGuard`, not a code.
2. **Contract first.** Changing the vocabulary is a contract change: record a `D-*` entry in `docs/parallel/DECISIONS.md`, update `docs/contracts/v2/tenant-permission.md` (§5 for AppDefinition codes, §5b for organization codes), and get C0 approval.
3. Add the constant to `enum Permission` (`M/access/Permission.kt`) with **exactly the canonical name** (storage names that differ are legacy only). Add it to `PermissionCodes.CANONICAL`. If it must be valid in an app's `PermissionDef`, also add it to `app.definition.PermissionCodes.ALL`. If it is internal, leave it out of `CANONICAL` (it is then enforced but not exposed).
4. **Deny by default:** grant it in `PermissionMatrix` only to the roles that must hold it. `PermissionMatrix.systemAdmin = Permission.entries` auto-grows, but that set is reachable only with the legacy flag — do not rely on it. Decide scope: tenant (`tenantRoles`), workspace (`workspaceRoles`), project (`projectRoles`). Never add it to `platformScope` without a decision (it is exactly `TENANT_MANAGE` + `TENANT_MEMBERS`).
5. Enforce it **server-side at the controller/port**, after scope resolution (`forTenant`/`forWorkspace`/`forProject`), as `ctx.require(Permission.X)`; never from a role string and never from `/auth/me`. For runtime codes go through `AccessPort` with the canonical string. For tenant writes also call `requireTenantWritable`.
6. Update the mirrors: `packages/types/src/contract/v2/permissions.ts` (and the TS permission helpers), then run `node tests/guards/permission-mirror.mjs` (or `npm run gate:frontend`).
7. Tests (each must fail if the guard is removed): the matrix (`PermissionCanonicalTests` default-deny list), the denied cases (403 for a holder of a neighbouring permission, 404 for a foreign scope), the `/auth/me` exposure (`TenantAdminAuthMeExactTests` / `ProjectScopedAuthMeTests` style), and a revoke-while-session-alive test (`FinalIamLifecycleTests` style).
8. Do not introduce an alias; do not add a migration without a number from C0 (the matrix is code, not data).

---

## 15. Security test map (rule → test classes)

Test classes live under `backend/src/test/kotlin/com/systemwebstudio/` (`T/`). Frontend guards are under `tests/guards/` (run by `npm run gate:frontend`).

| Rule / area | Test classes |
|---|---|
| Account must be enabled; 401 | `access/AccessEvaluatorTests`, `identity/AuthSecurityTests`, `tenancy/FinalIamLifecycleTests` |
| Workspace/project decision (single path) | `access/AccessEvaluatorTests`, `tenancy/TenantAccessTests`, `tenancy/TenantAccessLegacyFlagTests` |
| Evaluator = bulk `/auth/me` | `identity/authme/ProjectScopeEquivalenceTests`, `ProjectScopeLegacyFlagEquivalenceTests`, `AuthMeQueryComplexityTests`, `TenantCardinalityQueryTests`, `AuthMeBenchmarkTests` |
| Canonical vocabulary, default deny | `tenancy/PermissionCanonicalTests`, `tenancy/FinalPermissionSemanticsTests`, `tenancy/TenantAdminAuthMeExactTests`, `tenancy/MeTenancyTests`, `tenancy/TenantAdminViaRoleTests`, guard `permission-mirror.mjs` |
| Role name ≠ authorization | `tenancy/MultiTenantAuthMeTests` (`H`), `tenancy/AccessAdaptersTests`, `tenancy/HardeningLifecycleTests` |
| Org membership / position / grade ≠ authorization | `tenancy/FinalPermissionSemanticsTests` (`4c`), `organization/OrganizationAuthorizationTests`, `wiring/RuntimeAuthorizationApiTests`, `logic/workflow/RuntimeAuthorizationTests`, guard `org-source-guards.mjs` (`ORG-RELATION`, `ORG-FAIL-CLOSED-*`) |
| Tenant boundary / cross-tenant | `isolation/IsolationApiTests`, `data/gateway/GatewayTests`, `data/datasource/DataSourceScopeTests`, `tenancy/UserProvisioningHierarchyTests` (`F`), `tenancy/TenantFoundationMigrationTests`, `TenantMigrationFromV25Tests`, `TenantInsertPathsGrepTest`, `tenancy/SecurityIdMatrixTests`, `SecurityRuntimeRoutesMatrixTests` |
| Scope non-leakage (workspace/project/app) | `tenancy/ProjectScopedAuthMeTests`, `publish/PublishAuthorizationTests`, `isolation/IsolationApiTests`, `tenancy/MultiTenantAuthMeTests` |
| Privilege escalation / self-grant | `tenancy/PrivilegeEscalationTests`, `AdminTransferOwnershipSelfGrantSpec`, `member/MemberApiTests` |
| Tenant status (suspended/deleted) | `tenancy/FinalTenantStatusTests`, `tenancy/FinalIamLifecycleTests`, `tenancy/HardeningLifecycleTests` |
| Last admin | `member/MemberApiTests`, `tenancy/TenantServiceTests`, `tenancy/TenantMemberDirectoryTests`, `admin/AdminApiTests`, `tenancy/HardeningRaceTests`, `HardeningLockTests` |
| Sessions, revocation | `identity/AuthSecurityTests`, `identity/RedisSessionTests`, `identity/SessionRestartTests`, `admin/AdminApiTests`, `tenancy/HardeningSessionTests` |
| CSRF / CORS / webhook route / headers | `identity/AuthSecurityTests`, `identity/WebhookSecurityTests`, `common/HardeningTests`, `common/TrustedProxyTests`, `wiring/WebOriginsTests` |
| Production config refuses unsafe values | `common/ProductionConfigValidatorTests`, `common/HardeningTests` |
| Gateway authorizer (permission + scope) | `tenancy/AccessAdaptersTests`, `data/gateway/GatewayTests`, `data/gateway/ManagementHttpTests`, `tenancy/GatewayAuthorizerNoOracleTests` |
| Runtime authorization (action/workflow/data) | `logic/workflow/RuntimeAuthorizationTests`, `wiring/RuntimeAuthorizationApiTests` |
| Approvals via `WORKFLOW_MANAGE` | `logic/workflow/ApprovalDecisionTests`, `wiring/persistence/JdbcApprovalStoreTests`, `WorkflowApprovalG3Tests`, `wiring/ApprovalJourneyE2ETests` |
| Actor kinds; SYSTEM/SERVICE/APP_TOKEN denied | `tenancy/AccessAdaptersTests`, `tenancy/PublicSiteAuthorizationTests`, `wiring/ActorKindsTests` |
| PUBLIC_SITE | `tenancy/PublicSiteAuthorizationTests`, `wiring/PublicDataEndpointTests`, `wiring/PublicDataFlagOffTests`, `app/definition/PublicQueryAllowListTests`, `PublicQueryAllowListCommitTests`, `publish/PublicDataApprovalTests` |
| SSRF guard / address policy | `data/datasource/AddressRangeSpecTests`, `data/datasource/SecurityPrimitivesTests`, `data/datasource/rest/PinnedHttpsTransportTests`, `runtime/PublicAddressTests` |
| Postgres target policy, TLS verify-full, read-only session | `wiring/PostgresTargetPoliciesTests`, `PostgresTargetWiringTests`, `PostgresTargetFlagOffTests`, `data/datasource/postgres/PostgresSessionSecurityTests`, `PostgresConnectorTests`, `SqlGuardTests` |
| Connector proxy / server-app secrets | `runtime/ServerRuntimeTests` |
| Credential never returned / redaction | `data/datasource/SecurityPrimitivesTests`, `DataSourceAdminTests`, `DataSourceManagementTests`, `DataSourceServiceTests`, `data/gateway/ManagementHttpTests`, `data/gateway/GatewayTests`, `migration/DataRuntimeMigrationTests` (`credentials are stored as ciphertext only`), `data/sync/WebhookTests` |
| Audit append-only, no secrets in audit | `audit/AuditApiTests`, `organization/OrganizationAuthorizationTests` (`16`), `tenancy/UserProvisioningHierarchyTests` (`O`) |
| Publish authorization (APP_PUBLISH, public-data approval) | `publish/PublishAuthorizationTests`, `publish/PublicDataApprovalTests` |

---

## 16. Known limits and open findings (stated plainly)

| Status | Item | Owner |
|---|---|---|
| OPEN | `GET /api/v1/projects/{id}` existence oracle by error code (section 7) | C2 |
| OPEN | A project EDITOR (`PROJECT_SETTINGS`) can set the project merge policy to AUTO_MERGE_ALLOWED and merge its own change, bypassing a workspace "review required" policy (`code/CodeChangeController.kt:219-226`) | C0-gated |
| OPEN | Admin Console shows prompt text / versions / audit diffs of every tenant's projects with the legacy flag OFF; global disable revokes sessions by username **before commit**; pending activation/reset tokens survive a disable | C0 / C1 |
| OPEN | Activation/reset link is returned to the creator (Tenant Admin can create a WORKSPACE_ADMIN and log in as it) | C0 policy |
| OPEN | Public sign-up can be turned on at runtime (`PUT /admin/settings/policies/signup.enabled`) although the production validator checks only the start-up property | C0 |
| OPEN (P2) | Private sites/apps check `APP_VIEW` where `APP_USE` is meant; templates/COMPANY blocks are visible across tenants; build runner and runtime runner share one token and `/internal/runtime/desired` returns every tenant's secrets; `GET /ai/status?workspaceId=` leaks a foreign workspace's deny rules; some reads call `forWorkspace` without `require`; Git clone access outlives a removed member; AI runs commit up to 900 s later with the old context | C2 / C0 / C4 |
| DOCUMENTED | `ProjectController.list` filters non-admins with `EXISTS project_members` after `forWorkspace`: a SYSTEM_ADMIN non-member with a stale `project_members` row can see that project's metadata (needs a DB inconsistency) | project/** owner |
| DOCUMENTED | `/auth/me` response size is linear in visible projects; with the legacy flag a SYSTEM_ADMIN member of a SUSPENDED-tenant workspace sees `permissions: []` while the API grants the bypass (under-reports, fail-safe) | C1 |
| DOCUMENTED | A workspace member with **no** `tenant_members` row is treated as active (pre-V26 data) | C1 |
| DOCUMENTED | `firstAdminUserId` of tenant create does not check that the user is enabled/activated | C1 |
| DEFERRED | `APPROVAL_DECIDE`; approval notifications; approvals inbox; cross-tenant share policy (T16); SYSTEM/SERVICE/APP_TOKEN actor policy; per-version public-data approval | C1 / C4 / C0 |
| DEFERRED | Native SAML SP (brokered through OIDC), malware scan of uploads, gVisor/Kata isolation, off-host backups | see `docs/SECURITY.md` |
| P3 (fixed) | `LockdownSettingsTests` failed in full runs with `503 SIGNUP_FULL`: the shared test database passed `app.signup.max-users` (500). Fixed in the test (D-C0-62); the product behaviour (sign-up cap, off by default) is unchanged and tested by `RegistrationAndLimitsTests` | C1 test hygiene |
| DOCUMENTED | `approvals.app_id` is a plain FK to `projects(id)` (no composite tenant FK); tenant consistency is enforced by the engine's scope check and tenant-keyed queries (D-C0-61 item 1) | C4 / C0 |

---

## Sources read

- Code (local ref `integration/v2` at `9d2fc8b9758077ac880d80cfbec767e2e776f9c5`): `M/access/{Permission,AccessService,AccessEvaluator,ProjectScopeResolver,MeTenancy}.kt`, `M/access/adapters/{GatewayAuthorizer,AccessPort,AccessDecision,PrincipalResolver,PublicSiteAuthorization,TenantGate}.kt`, `M/tenancy/{Tenant,TenantContext,TenantResolver}.kt`, `M/identity/{SecurityConfiguration,SessionConfiguration,ActiveUserFilter,Identity}.kt`, `M/admin/AdminSupport.kt`, `M/audit/AuditService.kt`, `M/app/definition/PermissionCodes.kt`, `M/data/datasource/{AddressPolicy,Credential,ConnectorSpi}.kt`, `M/data/datasource/postgres/{PostgresTargetPolicy,PostgresConnector}.kt`, `M/wiring/{PublicDataController,AppRuntimeDataController,AppRuntimeActionController}.kt`, `M/runtime/Gateway.kt`, `M/common/ProductionConfigValidator.kt`, `M/logic/action/{ActionPorts,ActionRuntime}.kt`, `M/logic/workflow/WorkflowEngine.kt`, `M/project/ProjectLookupController.kt`, `M/member/MemberController.kt`, `M/wiring/persistence/JdbcCredentialStore.kt`, `M/integration/secrets/SecretProvider.kt`, `application.yml`, `application-local.yml`, `V3`, `V28`, `V32` migrations, `scripts/{secret-scan.sh,data-target.sh,_env.sh,gate-frontend.mjs}`, `.gitignore`, `.gitleaks.toml`, `.env.example`, `packages/types/src/contract/v2/permissions.ts`, `tests/guards/permission-mirror.mjs`.
- Tests (names read from `backend/src/test`): the classes listed in section 15.
- Docs: `docs/SECURITY.md`, `docs/contracts/v2/tenant-permission.md`, `docs/parallel/c1/{final-iam-tenant-org-permission-contract,public-site-contract,organization-employee-contract}.md`, `docs/parallel/c2/H_C2_07_PUBLIC_DATA_APPROVAL.md`, `docs/parallel/c3/DYNAMIC_ORGANIZATION_PERSISTENCE.md`, `docs/parallel/WEB_SECURITY_CONFIG.md` (dated; code preferred where they differ), `docs/parallel/DECISIONS.md` D-C0-57..59, `CLAUDE.md`.
- Imports read from the local `integration/v2` ref at `9d2fc8b9758077ac880d80cfbec767e2e776f9c5`: `docs/parallel/c1/final-iam-hardening-report.md`, `docs/parallel/audit/C4-FQ-WF-01-approval.md`, `docs/parallel/DECISIONS.md` D-C0-60 and D-C0-61, `V33__approvals.sql`, and the changed files `AccessService.kt`, `TenantController.kt`, `TenantService.kt`, `Accounts.kt`, `OidcLoginHandlers.kt`, `WorkflowEngine.kt`, `AppRuntimeActionController.kt`, `AppRuntimeConfiguration.kt`, `RuntimeRequests.kt`, `RuntimeResponses.kt`.

## Open questions / UNVERIFIED

Settled by reading `integration/v2`: the project-lookup oracle is **OPEN** (section 7); `REGISTRY_WRITE` has no enforcing call site (3.1); all hardening and approval behaviour in this document was verified in the merged code.

1. **gitleaks at the RC SHA.** Run on 2026-10-11: 974 commits scanned, 5 findings, all reviewed as fake fixture values or harness literals (`KL-QA-06`); the scan is NOT CLEAN and the configuration was not weakened. Decision on the allowlist is open.
2. **SYSTEM_ADMIN platform routes.** The list of `forPlatform`/`AdminGuard` routes is taken from the contract document, not enumerated route by route.
3. **Production TLS trust for private data targets.** The repository documents only the dev CA; no production allow-listed private target exists (`allowed-private` is empty in prod). The operator procedure is not documented.
4. **OIDC `eraseCredentials()` is not covered by a test** of the handler itself (needs an IdP); `HardeningSessionTests` `C4`/`C5` cover the principal and the password-login session payload.
5. **Public deployment.** The public API runs an older pinned release (`1006cbf441f6`) and lacks M-052, `projectScopes`, the organization routes and H-C2-07 (D-C0-57 item 4, Option B DEFER); the hardening and approval runtime are not on the public deployment until that release is moved. The public frontend is `bc5c47f292d0`.
