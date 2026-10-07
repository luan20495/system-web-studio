# T2 — Tenant foundation (C1) — state after the V26 fix round (`fix/c1-v2`)

Canonical contract: `docs/contracts/v2/tenant-permission.md`. Evidence status is stated per item; nothing here claims a Gradle result.

## Evidence
| Item | Evidence |
|---|---|
| V26 + undo SQL | **Executed** on PostgreSQL 16 with `verification/run-v26-checks.sh` (V1–V25 applied, rows seeded, V26 in one transaction, 21 behaviour checks, undo guard refusals ×4, clean undo, re-apply). Not Flyway, not Testcontainers. |
| Kotlin main + tests | **Not compiled, not run** — Maven Central/Gradle are blocked by policy and there is no Docker in the C1 environment (B-C1-15). `cd backend && ./gradlew test` must be run by C0 on a capable machine. |

## 1. Schema (V26)
`backend/src/main/resources/db/migration/V26__tenant_foundation.sql`; runbook `runbook-tenant-migration.md` (transaction/lock behaviour, large tables, **removing compatibility**); undo `undo/U26__tenant_foundation.sql` (guarded: refuses once any V2 data exists).
Order: tenants → DEFAULT tenant `00000000-0000-0000-0000-000000000001` → nullable `tenant_id` → backfill → verify → NOT NULL + FK + `UNIQUE(id,tenant_id)` + composite FKs + indexes → triggers.
Tenant-scoped tables: `workspaces`, `workspace_members`, `projects`, `project_members`. Compatibility (must be removed, see runbook §4): `DEFAULT` on `workspaces.tenant_id` and the three `*_tenant_fill` triggers. The fill trigger **rejects** a `tenant_id` that differs from the workspace's tenant (never overwrites).

## 2. Code
- `tenancy/`: `Tenant.kt` (entities, repositories, `TenantIds`, `TenantRole`, `TenantStatus`), `TenantContext.kt` (`TenantContext(tenantId, tenantRole, status, platformScope)` and the single `ActorKind`), `TenantResolver`, `TenantService`, `TenantController` (`/api/v1/admin/tenants`).
- `access/`: `AccessService` (+`AccessContext.tenantId/tenantContext/systemAdminBypass`, `forTenant`, `forPlatform`), `Permission.kt` (canonical vocabulary, `PermissionCodes`, role matrix → `permission-matrix-v2.md`), `MeTenancy.kt` (`/auth/me` data).
- `access/adapters/`: `GatewayAuthorizer`, `AccessPort`, `TenantGate`, `PrincipalResolver` — policy cores; C0's `wiring.*Adapter` classes convert `GatewayContext`/`ActionContext` into their plain inputs.
- `identity/AuthController`: `MeResponse` + `tenantId`, `tenantRole`, `platformScope`, `businessAccess`, `tenants[]`, `permissions[]`, per-workspace `tenantId` + `permissions[]`.
- `member/MemberController`, `TenantService.setMember`: self-grant rejected (D-C1-15).

## 3. Security chain
authenticate (enabled user) → resolve tenant from the workspace → tenant ACTIVE and membership not deactivated → authorize (workspace/project role) → execute → audit. Adapters add: claimed tenant must equal the workspace's tenant; only `USER` actors are authorised; canonical codes only; default deny; any exception denies.

## 4. Where SYSTEM_ADMIN still bypasses (flag OFF) — and the tests
| # | Place | Behaviour | Test |
|---|---|---|---|
| 1 | `AccessService.forWorkspace` non-member | `platformScope`: `MEMBER_MANAGE`, `PROJECT_CREATE` (only the 409), `TENANT_*` | `TenantAccessTests`, `PrivilegeEscalationTests` |
| 2 | `/auth/me` | lists every workspace (role `ADMIN`) with platform-only permissions | `MeTenancyTests` |
| 3 | `/api/v1/admin/**` (`AdminGuard`, outside C1) | platform APIs unchanged; B-C1-13 (transfer-ownership self-escalation) | existing admin tests |
| 4 | flag `app.tenancy.system-admin-business-access=true` | legacy god mode | `TenantAccessLegacyFlagTests` |
| 5 | `forTenant` / `forPlatform` | administer every tenant | `TenantAccessTests` |
Self-grant is rejected for SYSTEM_ADMIN too (it can onboard others, not itself).
Behaviour change (flag OFF): sysadmin non-member → project detail/schema/versions 404, project list empty, workspace audit 403. Existing tests were reviewed statically: sysadmin sessions in the base suite only call `/api/v1/admin/**`, create a project as a member, or hit the 409 case.

## 5. Not done in this round (by instruction)
Sharing (T15), cross-tenant shares (T16), RLS (T18), departments/groups (T4), `tenant_id` for global resources (T5), dropping the compatibility objects (needs migration number from C0).
