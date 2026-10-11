# Final IAM / tenant / authorization hardening (C1)

Base `origin/integration/v2 @ fad4a7b4356f`. Branch `fix/c1-final-iam-hardening`. Three read-only audits (identity / session / account lifecycle; route-level authorization of the product packages; route-level authorization of the platform + IAM packages, about 300 handlers in total) found **no P0** (no cross-tenant read or write, no privilege escalation by an ordinary user). The C1-owned P1 / P2 findings were fixed and tested; everything owned by someone else is listed below with a suggested fix.

## 1. Fixed by C1 (code + tests)

| # | Finding | Fix | Tests |
|---|---|---|---|
| 1 | A SUSPENDED company was NOT frozen for its Tenant Admin: workspace creation, account creation (incl. WORKSPACE_ADMIN rows + activation links) and member changes still worked (organization routes already answered 403) | `TenantController.writable` (403 `TENANT_SUSPENDED` for a non-platform caller) on `POST /{t}/workspaces`, `POST /{t}/users`, `PUT` / `DELETE /{t}/members/{u}`; the platform operator keeps its reach to repair the company | `HardeningLifecycleTests` A1-A2, `SecurityIdMatrixTests` section 4, `MultiTenantAuthMeTests` F |
| 2 | A DELETED company accepted workspace creation and member changes from the platform operator | `TenantService.live()` refuses DELETED in `createWorkspace`, `setMember`, `removeMember` (the way back is `PATCH /{t}/status`) | `HardeningLifecycleTests` A3-A4 |
| 3 | Last TENANT_ADMIN rule raced: two admins demoting / removing each other concurrently could both pass | `TenantService.assertNotLastAdmin` locks the active TENANT_ADMIN rows (`FOR UPDATE ORDER BY user_id`) before counting | `HardeningRaceTests` (demote, remove, cycle: 5 rounds each, exactly one 200 + one 409 `LAST_TENANT_ADMIN`) |
| 4 | Last SYSTEM_ADMIN rule raced in `AccountService.setSystemAdmin` | every enabled system-admin row is locked (id order) before the count | `HardeningRaceTests` (two operators revoking each other, 5 rounds) |
| 5 | OIDC login put the user's password hash (a real Argon2 hash for a LOCAL account linked by e-mail) into the Redis session | `OidcLoginHandlers` calls `eraseCredentials()` on the loaded principal before the context is saved | `HardeningSessionTests` (unit + the Redis payload of a password login carries no hash; the OIDC handler itself needs an IdP: covered by review) |
| 6 | ~~The data-gateway authorizer accepted a `dataSourceId` without checking it belongs to the caller's workspace~~ **REVERTED (decision A, C7 2026-10-11).** The `GatewayAuthorizer.dataSourceBelongs` check (cfb064b) was removed: it turned C3's canonical 404 for a foreign / unknown data source into a 403 `data source not in workspace` for the same id, i.e. an existence oracle (403 vs 404 distinguishes an id that exists elsewhere from one that does not) and a silent change of the C3 contract. Workspace isolation of data sources stays C3's job (`findInWorkspace`: foreign and unknown ids are both 404) | none in C1; C1 only decides on permission and scope | `GatewayAuthorizerNoOracleTests` (the decision does not depend on the data source id) |
| 7 | Role-name decision: `AccessContext.seesAllProjects` compared `workspaceRole == "WORKSPACE_ADMIN"` | decided by `Permission.PROJECT_READ in permissions` (same result for every workspace role, see the matrix) | `HardeningLifecycleTests` project-list test |
| 8 | Dead role helper `TenantContext.isTenantAdmin` | removed | compile |

**ROLE_SHORTCUTS: 0.** No handler or service allows or denies an operation by comparing a role NAME any more; the remaining `TENANT_ADMIN` / `WORKSPACE_ADMIN` strings only map a persisted role to `PermissionMatrix`, parse input, assign a role, enforce the last-admin data rule, or select approvers (`PrincipalResolver`). Guard test: `MultiTenantAuthMeTests` H (source guard on the tenant / organization controllers).

## 2. Tenant lifecycle (exact behaviour, enforced by tests)

| Operation | ACTIVE | SUSPENDED | DELETED |
|---|---|---|---|
| Workspace / project routes (member) | by matrix | 403 `TENANT_SUSPENDED` | 404 |
| Organization / employee / position / grade reads | allowed | allowed | 404 (admin) / 403 (platform scope) |
| Organization writes, tenant rename, `POST /{t}/workspaces`, `POST /{t}/users`, `PUT` / `DELETE /{t}/members/{u}` - Tenant Admin | allowed | **403 `TENANT_SUSPENDED`** | **404** |
| Same writes - platform operator (SYSTEM_ADMIN) | allowed | allowed (repair) | **404** (gone for everybody) |
| `PATCH /{t}/status` | platform only | platform only | platform only (the only way back) |
| `/auth/me` | listed | listed, capabilities of the role; its workspaces carry no permission | absent |

Sessions and revocation (all proven in `HardeningSessionTests`, `SecurityIdMatrixTests`, `FinalIamLifecycleTests`): every authorization decision is recomputed from the database on each request; a removed / downgraded membership takes effect on the next request of the SAME session; a globally disabled user gets 401 on the next request and the old cookie stays dead after re-enable; completing a reset / activation link signs the account out everywhere; the tenant employee lifecycle (`tenant_members.active`) never touches `users.enabled`.

## 3. Open items owned by others (P1 / P2, with the suggested fix)

| Sev | Owner | Finding | Suggested fix |
|---|---|---|---|
| P1 | C2 (`project/ProjectLookupController.kt`) | `GET /api/v1/projects/{id}` is an existence oracle: a project of another tenant / unjoined workspace answers `WORKSPACE_NOT_FOUND`, a random UUID answers `PROJECT_NOT_FOUND` | map the `WORKSPACE_NOT_FOUND` of `forProject` to `PROJECT_NOT_FOUND` in that handler |
| P1 | C0-gated (`code/CodeChangeController.kt:219-226`) | a project EDITOR (PROJECT_SETTINGS) can set the project merge policy to AUTO_MERGE_ALLOWED and merge their own change, bypassing a workspace "review required" policy | weakening the policy needs MEMBER_MANAGE, or the effective policy is the stricter of project / workspace |
| P1 | C0-gated (`admin/AdminController.kt`, `AdminUserController.kt`) | the Admin Console shows prompt text / versions / audit diffs of every tenant's projects with the legacy flag OFF; `audit_events` has no tenant id; the global disable revokes sessions by username BEFORE commit and does not lock the last-SYSTEM_ADMIN count; pending activation / reset tokens survive a disable and work again after re-enable (24 h) | redact business payloads unless the flag is on; add `tenant_id` to audit (needs a migration number from C0); lock enabled system admins; delete or invalidate pending tokens on disable |
| P1 | C0 (policy decision, DECISIONS) | the activation / reset link of a created or reset account is returned to the CREATOR (a Tenant Admin can create a WORKSPACE_ADMIN account and log in as it; an operator can reset another admin's password and log in) | deliver the link to the target only, or require a second approver, or record it as an accepted risk |
| P1 | C0 | public sign-up can be turned on at runtime (`PUT /admin/settings/policies/signup.enabled`) although the production validator only checks the start-up property | extend the production guard to the runtime setting |
| P2 | C2 / C0 / C4 | private sites / apps check APP_VIEW where APP_USE is meant; templates / COMPANY blocks are visible across tenants; AI runs commit up to 900 s later with the old context; workflow creator can view / cancel without APP_USE; async dependency job does not re-check PROJECT_EDIT; GET /schema can create version 1 for a VIEWER; Git clone access outlives a removed member; build runner and runtime runner share one token and `/internal/runtime/desired` returns every tenant's secrets; `GET /ai/status?workspaceId=` leaks a foreign workspace's deny rules; some reads call `forWorkspace` without `require` | see the audit reports |
| P2 | C1 (documented, no change) | re-adding a user to a tenant as MEMBER restores old workspace / project rights (rows stay active); SCIM rename defeats revoke-by-username; no absolute session lifetime; 5 bad passwords lock a user out; login timing shows disabled accounts; username / e-mail existence is visible to a Tenant Admin (`USERNAME_TAKEN` / `EMAIL_TAKEN`); `ROLE_ADMIN` authority from the login snapshot is unused (do not build `hasRole("ADMIN")` on it) | product decisions |

## 4. `/auth/me` complexity (unchanged guarantee)

6 SQL statements per request, independent of the number of projects, workspaces and tenants (`AuthMeQueryComplexityTests`, `TenantCardinalityQueryTests`); see the final contract 2.3a / 2.3b.
