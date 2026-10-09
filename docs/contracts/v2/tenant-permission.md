# Canonical contract v2 — Tenant & Permission

Owner: **C1**. Frozen by C0 on 2026-10-05 from the code on `agent/c1-tenancy` (`e3a48eb`). Supersedes `docs/contracts/tenant-context.md` and `docs/contracts/permission-model.md`. Changing anything here needs a `DECISIONS.md` entry approved by C0.
Status of the code behind it: **written, not compiled, not run** (no Gradle available when frozen).

## 1. Tenant model
- Tables (V26): `tenants(id, slug, name, status, created_at, updated_at)`, `tenant_members(tenant_id, user_id, role, active, created_at, created_by)`.
- `TenantStatus { ACTIVE, SUSPENDED, DELETED }`. `TenantRole { TENANT_ADMIN, MEMBER }`.
- `TenantIds.DEFAULT = 00000000-0000-0000-0000-000000000001` (the one tenant that exists after V26; all existing data lands in it).
- A **workspace belongs to exactly one tenant** (`workspaces.tenant_id NOT NULL`). `workspace_members`, `projects`, `project_members` carry `tenant_id` equal to their workspace's (composite FKs enforce it).
- `projects` keeps its name. No table is renamed.
- **`tenant_members` is the single source of truth for TENANT_ADMIN.** A user with no `tenant_members` row for a tenant that owns one of their workspaces is treated as `MEMBER` (fail-open by D-C1-12; accepted risk, see INTEGRATION_V2 R-07).

## 2. TenantContext (canonical shape = the code)
```kotlin
data class TenantContext(val tenantId: UUID, val tenantRole: TenantRole?, val status: TenantStatus = ACTIVE, val platformScope: Boolean = false)
enum class ActorKind { USER, SYSTEM, APP_TOKEN, SERVICE }      // ONE definition: com.systemwebstudio.tenancy.ActorKind (C1)
```
- The draft fields `organizationId`, `workspaceId`, `actorUserId`, `requestId` are **not** part of `TenantContext`. Who/where is carried by `AccessContext` (user, workspace, project), `GatewayContext` (C3) and `ActionContext` (C4); those are built from `AccessContext` by adapters (§6), never from client input.
- `TenantContext` is resolved server-side by `TenantResolver` from the workspace in the path. Clients never send a tenant id. Unknown tenant/workspace or no membership → **404**; suspended tenant → 403 `TENANT_SUSPENDED`.
- **C3 and C4 must delete their own `TenantContext` / `ActorKind` placeholders** and use these types (adapters convert at the boundary).

## 3. AccessContext / AccessService (backward compatible)
`AccessContext(user, workspaceId, workspaceRole, projectRole, permissions, project, tenantId = DEFAULT, tenantContext = DEFAULT, systemAdminBypass = false)`. `forWorkspace` / `forProject` keep their signatures. New: `forTenant(userId, tenantId): TenantAccess`, `forPlatform(userId)`.
Default values of the trailing parameters must never be relied on by new code (they fail open to the DEFAULT tenant). New code always passes a resolved `tenantContext`.

## 4. SYSTEM_ADMIN vs TENANT_ADMIN
| Principal | Scope | Gets |
|---|---|---|
| `users.system_admin` (**SYSTEM_ADMIN**) | **Platform** | `platformScope` permissions (`TENANT_MANAGE`, `TENANT_MEMBERS`, `MEMBER_MANAGE`, `PROJECT_CREATE`) on every tenant; **no** business-data access unless the flag `app.tenancy.system-admin-business-access=true` (legacy god mode, default **false**, must be declared in `application.yml` by C0) |
| `TENANT_ADMIN` (`tenant_members`) | One tenant | EXACTLY eight codes: `EMPLOYEE_MANAGE`, `EMPLOYEE_VIEW`, `ORG_STRUCTURE_MANAGE`, `ORG_STRUCTURE_VIEW`, `POSITION_GRADE_MANAGE`, `POSITION_GRADE_VIEW`, `TENANT_MANAGE`, `TENANT_MEMBERS` (the two tenant codes + the six organization permissions of section 5b) on that tenant; a plain MEMBER holds none of them; a non-member SYSTEM_ADMIN holds the two platform codes only; **no** implicit workspace/app/data access (D-C1-12) |
| `WORKSPACE_ADMIN` / project roles | Workspace / project | as today (`PermissionMatrix` unchanged) |

Consequence recorded as a behaviour change: with the flag off, a system admin who is not a member of a workspace gets 404 on its projects, and `ProjectController.list` returns an empty list for them. Base tests relying on god mode must be rewritten with an explicit reason (INTEGRATION_V2 R-03). **Open hole:** a system admin can add themselves as WORKSPACE_ADMIN via `MEMBER_MANAGE`; C1 must close it or accept it in `DECISIONS.md` (R-08).

## 5. Canonical permission vocabulary
<!-- machine-read by C2's ContractConformanceTests: it takes the first column of the table rows between this heading and the line starting "Rules:". Keep the heading text, the row format `| `CODE` |` and the "Rules:" line. -->
**Exactly 14 canonical APPLICATION codes** (the AppDefinition vocabulary: frozen, re-confirmed 2026-10-06; `app.definition.PermissionCodes.ALL` and the set C2 validates `PermissionDef.permission` against must be this list, no more, no fewer; C1's `access.PermissionCodes.CANONICAL` that `/auth/me` speaks is this list **plus** `MEMBER_MANAGE` and the six organization codes of section 5b = 21 codes):
`APP_VIEW, APP_USE, APP_EDIT, APP_PUBLISH, APP_SHARE, DATA_SOURCE_VIEW, DATA_SOURCE_MANAGE, QUERY_EXECUTE, DATA_MUTATE, ACTION_EXECUTE, WORKFLOW_EXECUTE, WORKFLOW_MANAGE, TENANT_MANAGE, TENANT_MEMBERS`.
`TENANT_MANAGE` and `TENANT_MEMBERS` are **part of the vocabulary** (they are not "internal"): they stay in `PermissionCodes.CANONICAL`, in the role matrix and in `/auth/me`; a request to remove them was considered and rejected. Legacy storage constants without a canonical code (`MEMBER_MANAGE`, `PROJECT_CREATE`, `AUDIT_READ`, `PROJECT_SETTINGS`, `PROJECT_DELETE`, `REGISTRY_WRITE`) stay enforced server-side and are never listed to clients or accepted in `PermissionDef.permission`.
One vocabulary shared by C1–C5. The enum `Permission` (C1, hot file) is the storage; legacy `PROJECT_*` constants **stay** (≈56 call sites) and are the storage for the `APP_*` codes. New codes are added with **exactly the canonical name**.

| Canonical code | `Permission` constant | Status | Used by |
|---|---|---|---|
| `APP_VIEW` | `PROJECT_READ` | exists | everyone |
| `APP_USE` | `APP_USE` | **add** | C4 (`LogicPermissions.APP_USE`), runtime/end-user use of a published app |
| `APP_EDIT` | `PROJECT_EDIT` (+ `PROJECT_SETTINGS` for settings) | exists | C2, C5 |
| `APP_PUBLISH` | `PROJECT_PUBLISH` | exists | C2 publish config, publish |
| `APP_SHARE` | `PROJECT_MEMBERS` today → `APP_SHARE` when sharing lands (T15) | **add** later | C1 sharing |
| `DATA_SOURCE_VIEW` | `DATA_SOURCE_VIEW` | **add** | C3 (`DATASOURCE_READ`) |
| `DATA_SOURCE_MANAGE` | `DATA_SOURCE_MANAGE` | **add** | C3 (`DATASOURCE_MANAGE`, schema discovery, sync, webhook, cache refresh) |
| `QUERY_EXECUTE` | `QUERY_EXECUTE` | **add** | C3 |
| `DATA_MUTATE` | `DATA_MUTATE` | **add** | C3 (`MUTATION_EXECUTE`), C4 write actions |
| `ACTION_EXECUTE` | `ACTION_EXECUTE` | **add** | C4 |
| `WORKFLOW_EXECUTE` | `WORKFLOW_EXECUTE` | **add** | C4 start run |
| `WORKFLOW_MANAGE` | `WORKFLOW_MANAGE` | **add** (C0 addition to the list) | C4 cancel others' runs, schedules |
| `TENANT_MANAGE`, `TENANT_MEMBERS` | same | exists | C1 |

Rules: (1) a code that is not in this table is not allowed in `PermissionDef.permission` (C2 validates against this set, not a regex); (2) **deny by default** — a new constant is granted to no role until the role matrix says so. The role matrix is C1's `docs/parallel/c1/permission-matrix-v2.md` (D-C1-14, enforced in `Permission.kt`; arrives with the C1 import): `APP_USE`, `DATA_SOURCE_VIEW`, `QUERY_EXECUTE`, `ACTION_EXECUTE` for project EDITOR/OWNER and WORKSPACE_ADMIN (VIEWER/PUBLISHER hold `APP_USE` only; `QUERY_EXECUTE` for VIEWER needs published-app state, T15); `DATA_SOURCE_MANAGE`, `DATA_MUTATE`, `WORKFLOW_EXECUTE`, `WORKFLOW_MANAGE` for WORKSPACE_ADMIN only until explicit grants exist (T15); `TENANT_MANAGE`/`TENANT_MEMBERS` for TENANT_ADMIN of its own tenant and for SYSTEM_ADMIN as platform duty; (3) `PermissionMatrix.systemAdmin = entries.toSet()` auto-grows with new constants — acceptable only because it is behind the flag; (4) hiding a button in the UI is not authorisation.

## 5b. Organization permissions (tenant level; C1 final `feat/c1-final-iam-org-permissions @ db7d6b0`, frozen by D-C0-51)
Six canonical codes, **no aliases**. They are part of `access.PermissionCodes.CANONICAL` (what `/auth/me` and the role matrix speak) and are **not** AppDefinition codes: `PermissionDef.permission` is validated against the 14 codes of section 5 only.

| Code | Meaning (tenant of the path only) | Held by |
|---|---|---|
| `ORG_STRUCTURE_VIEW` | read organization unit types, units, the tree | TENANT_ADMIN of that tenant |
| `ORG_STRUCTURE_MANAGE` | create / update / move / archive / restore units; create / update / disable / enable unit types | TENANT_ADMIN of that tenant |
| `EMPLOYEE_VIEW` | read employees, their memberships and held positions | TENANT_ADMIN of that tenant |
| `EMPLOYEE_MANAGE` | create employees (with `TENANT_MEMBERS`); disable / enable; add / change / end memberships; add / change / end position assignments | TENANT_ADMIN of that tenant |
| `POSITION_GRADE_VIEW` | read positions and grades | TENANT_ADMIN of that tenant |
| `POSITION_GRADE_MANAGE` | create / update / disable / enable positions and grades | TENANT_ADMIN of that tenant |

`*_MANAGE` does not imply `*_VIEW` in code (the role matrix grants both together). SYSTEM_ADMIN on the platform scope, tenant members, workspace roles and project roles hold none of them. `MEMBER_MANAGE` (workspace member screens) stays a portal-facing canonical capability of `workspaces[].permissions`, unrelated to the organization.
**Obsolete / not canonical (must not appear in production code):** `ORG_MANAGE` — it was a type-level placeholder of the C5 adapter (`features/admin/organization.ts`, tracking row T-ORG-MANAGE), never a backend permission, removed by D-C0-51 — and every other invented name (`ORG_VIEW`, `ORG_EDIT`, `ORG_ADMIN`, `ORG_MEMBERS`, `EMPLOYEE_ADMIN`, `POSITION_MANAGE`, `GRADE_MANAGE`, ...). The guard `ORG-FAIL-CLOSED-OBSOLETE` fails the gate when one is found. The TypeScript mirror is `packages/types/src/contract/v2/permissions.ts` (`PERMISSION_CODES` = section 5, `ORG_PERMISSION_CODES` = this section, `CANONICAL_PERMISSION_CODES` = the 21); `tests/guards/permission-mirror.mjs` fails when the TypeScript sets, the backend sets or this document drift.

## 5c. Scope separation and authorization invariants (frozen, D-C0-51)
1. **Scopes are separate and never flattened.** `/auth/me` `permissions[]` = platform + current-tenant permissions only; `workspaces[].permissions` = workspace-scoped; `projectScopes[].permissions` = that one project (resolved by the same `AccessService.forProject()` as the project APIs). A project scope is never merged into a workspace or global set; two projects are two rows and are never unioned. `role` is display / context only, never an authority: clients gate on canonical codes.
2. **Studio admission** = `APP_VIEW` in some workspace scope **or** in some project scope. No role-name special case; no implicit `APP_PUBLISH` (publish is enforced server-side: lacking it is `403`).
3. **An organization relation is not authorization.** Being a member / manager / head of a unit (any tenant vocabulary) grants no permission of C1. 
4. **A position is not authorization; a grade is not authorization.** Assigning either grants no permission.
5. **The tenant boundary always wins.** No organization relation, position, grade or membership can cross it: a foreign tenant id / record is the same safe `404` as a missing one; the tenant is always the path tenant, never a body field.
6. **No stale authority.** Every request re-resolves from the database: a disabled user, a removed tenant membership, a removed project membership, a deleted / inactive project, a suspended tenant, a logout, a revoked permission take effect on the next request; there is no permission cache that outlives them.

## 6. Adapters C1 owns (package `access/adapters`, C1 files; wiring beans by C0)
- `GatewayAuthorizer` (C3): `GatewayOperation` → code: `DATASOURCE_READ→DATA_SOURCE_VIEW`, `DATASOURCE_MANAGE→DATA_SOURCE_MANAGE`, `QUERY_EXECUTE→QUERY_EXECUTE`, `MUTATION_EXECUTE→DATA_MUTATE`, `SCHEMA_DISCOVER→DATA_SOURCE_MANAGE`, `SCHEMA_SAMPLE→DATA_SOURCE_MANAGE` (+ extra sensitive check), `CACHE_REFRESH→DATA_SOURCE_MANAGE`, `EVENTS_SUBSCRIBE→QUERY_EXECUTE`, `SYNC_MANAGE`/`WEBHOOK_MANAGE→DATA_SOURCE_MANAGE`. Must also authorise `projectId`/`appVersionId` when given.
- `AccessPort`, `TenantGate`, `PrincipalResolver`, cross-tenant approval policy (C4): `AccessRequest.permission` strings are the canonical codes above; `mode=TEST` may require `APP_EDIT`.
- `TenantContext`↔`GatewayContext`/`ActionContext` conversion; `ctx.tenantId` must be verified against the app's real tenant (C4's `def.tenantId == ctx.tenantId` check is tautological on the canonical path).
- `MeResponse` must expose tenant memberships/roles so the portals can gate (C5 B-C5-05).

## 7. V26 transitional mechanisms (must have a removal plan)
`workspaces.tenant_id DEFAULT <DEFAULT tenant>` and the `BEFORE` fill triggers exist **only** so un-migrated code keeps working. Required before V26 is accepted: trigger raises on a non-NULL mismatching `tenant_id` (fills only NULL); a runbook section "removing compatibility" with owner, condition (every INSERT into the four tables passes tenant_id) and the follow-up migration (drop DEFAULT, drop fill triggers) recorded as a BOARD migration request. See INTEGRATION_V2 §7.

## 7. `/auth/me` cost and tenant rename (D-C0-53, C1 `78237a1`)
* `/auth/me` is recomputed live and is **bounded: 6 SQL statements per request, independent of the number of workspaces / projects / tenants** (not O(N) in project memberships; measured by the C1 statement-counter test). No latency SLA is derived from a local benchmark. `projectScopes[]` are DB-derived, one row per active project membership whose workspace role is active; a project permission is never flattened into a workspace or global permission, and the `role` string is informational only.
* Tenant rename: `PATCH /api/v1/admin/tenants/{tenantId}` `{name}`, permission `TENANT_MANAGE`, the slug is immutable, audit `TENANT_UPDATED`, no version / ETag, idempotent; a DELETED tenant is `404 TENANT_NOT_FOUND`; a Tenant Admin of a SUSPENDED tenant gets `403 TENANT_SUSPENDED` (the platform operator may rename an ACTIVE or SUSPENDED one); organization writes of a SUSPENDED tenant are `403 TENANT_SUSPENDED`.
