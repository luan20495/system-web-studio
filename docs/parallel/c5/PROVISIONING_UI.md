# C5 — User provisioning UI on C1's tenant-scoped contract (2026-10-07)

Flow: SYSTEM_ADMIN → tenant admin (Platform) → tenant admin → new user (Admin) → workspace + role → the person activates by one-time link → signs in.
Contract consumed: C1 `fix/c1-portal-authz-blockers @ 2356d64` (`docs/parallel/c1/tenant-provisioning-contract.md`, D-C1-13 / D-C1-13A), stack `integration/v2`-based, no backend patch.
**Rules kept:** no route is invented; the UI talks to one interface, `ProvisioningApi` (`features/admin/provisioning.ts`); a capability the backend lacks is `NOT_READY` (the mechanism stays, none is currently in use); no password is ever handled; the activation token lives only in the dialog's state.

## Capabilities (all READY at 2356d64)
| Capability | Route | Needs |
|---|---|---|
| createTenantUser | `POST /admin/tenants/{tenantId}/users` body `{username, displayName, email?, tenantRole MEMBER\|TENANT_ADMIN, workspaceId?+workspaceRole?}` → ActivationLink | TENANT_MEMBERS |
| createTenantWorkspace | `POST /admin/tenants/{tenantId}/workspaces` `{name}` → `{id,name,slug,tenantId}` (the legacy `POST /admin/workspaces` is NOT used) | TENANT_MANAGE |
| assignTenantRole | `PUT /admin/tenants/{t}/members/{u}` | TENANT_MEMBERS |
| listMemberCandidates | `GET /admin/tenants/{t}/member-candidates?q=` (q ≥ 2, ≤ 50) | TENANT_MEMBERS |
| addWorkspaceMember / assignWorkspaceRole | `POST` / `PATCH /workspaces/{w}/members` | MEMBER_MANAGE (WORKSPACE_ADMIN) |
| resetCredential / enableUser / disableUser | `POST /admin/users/{u}/activation-link`, `PATCH /admin/users/{u}/status` | SYSTEM_ADMIN |

Adapter rules (unit-tested): the tenant id is the PATH only, never in the body; `workspaceId` and `workspaceRole` travel together or not at all; `username` lower-cased/trimmed; `email` only when non-blank; unknown capabilities send nothing.

## What the screens do
| Surface | Behaviour |
|---|---|
| Platform → Users → **Tạo tài khoản** (SYSTEM_ADMIN) | choose tenant (any non-deleted) → type (tenant admin / workspace admin / user, never SYSTEM_ADMIN) → optional workspace of that tenant + role (or "+ Tạo workspace mới của công ty này" via the tenant route, kept if the account step then fails, never created twice) → one call → the activation link is shown once, then a summary (account, Chờ kích hoạt, company + role, workspace + role) |
| Admin → **Người dùng** (TENANT_ADMIN) | tenant fixed from the session (read-only; a tenant admin of several tenants chooses only among their own); types incl. tenant admin; workspace list = the caller's own workspaces of THAT tenant, none, or a new workspace; no SYSTEM_ADMIN option |
| Admin → Người dùng (WORKSPACE_ADMIN, MEMBER_MANAGE only) | no create action (the reason is shown); add an existing tenant person to the workspace (`ae-*`), errors by code |
| Platform → workspace page (SYSTEM_ADMIN) | **member panel replaced by an explanation**: D-C1-13 gives a SYSTEM_ADMIN no `MEMBER_MANAGE` (`canManageWorkspaceMembers`, read from `/auth/me`); members are chosen at account creation or by the workspace's own admin |

Errors are shown by code, never generically: 400 INVALID_USERNAME / INVALID_EMAIL / VALIDATION_FAILED / TENANT_ROLE_INVALID / INVALID_ROLE / QUERY_TOO_SHORT · 403 FORBIDDEN / SELF_GRANT_FORBIDDEN / ADMIN_REQUIRED · 404 TENANT_NOT_FOUND / WORKSPACE_NOT_FOUND / USER_NOT_FOUND · 409 USERNAME_TAKEN / EMAIL_TAKEN / LAST_TENANT_ADMIN / LAST_ADMIN / ALREADY_MEMBER · 422 USER_DISABLED / NOT_WORKSPACE_MEMBER (`provisioningProblem`, field-level for username / email / workspace).

## Files
| Role | File |
|---|---|
| contract + capability table + adapter (pure) | `features/admin/provisioning.ts` |
| rules: plan per scope, validation, error mapping by code | `features/admin/provisioningModel.ts` |
| screens (presentational, harness-tested) | `features/admin/ProvisioningScreens.tsx` |
| real wiring | `features/admin/ProvisioningLive.tsx`, `features/admin/provisioningAdapter.ts`, `packages/api-client/src/api.ts` (`createTenantUser`, `createTenantWorkspace`) |
| routes | Platform `/platform/users` → "+ Tạo tài khoản"; Admin `/admin/people` |

## Verified (stack `c5e2e-c1b`, backend 2356d64 unpatched, the stack's own PostgreSQL container)
| What | Class | Result |
|---|---|---|
| unit: capability table, adapter body/path rules, NOT_READY sends nothing, plan by scope, validation, every C1 error code, `canManageWorkspaceMembers` | unit | 270 tests, 269 pass, 1 pre-existing skip |
| PUI01–09, AUI01–07 + workspace assignment, create-workspace (tenant route), retry safety, 12 error scenarios | **harness** (fake transport behind the REAL adapter) — **not a backend E2E** | 39/39 |
| **SUPER01** Platform UI: new tenant → create tenant admin (+ workspace, WORKSPACE_ADMIN) → link read from the DOM → token gone after "Xong" → real activation page → login Admin → Công ty của tôi + Người dùng | real backend | PASS |
| **ADMIN01** tenant admin (Admin UI): workspace via the tenant route + workspace admin; app creator by invitation; activation pages; creator signs in, no admin capability | real backend | PASS |
| **USER01** creator: project created by the workspace admin, project EDITOR, API: edit persisted, publish 403, no MEMBER_MANAGE | real backend | **BLOCKED (C1, H-C1-04)**: the Studio gate refuses the creator at `/auth/no-access` because `/auth/me` lists `permissions: []` for a workspace EDITOR (APP_VIEW comes from the project membership only) |
| **SEC01** tenant admin: `tenantRole SYSTEM_ADMIN` → 400 TENANT_ROLE_INVALID, unknown fields ignored, legacy `/admin/users` 403 ADMIN_REQUIRED, no grant · **SEC02** cross-tenant 404 on 8 routes both ways, foreign workspace 404 WORKSPACE_NOT_FOUND, nothing created · **SEC03** workspace admin 403 on every tenant/platform route, SELF_GRANT_FORBIDDEN, foreign person 404 USER_NOT_FOUND | real backend (real sessions) | PASS (18 checks) |
| **AD03 / H-C1-11** candidate endpoint on real PostgreSQL | real backend | PASS (23 checks) — 500s of 7ecea1a are gone |
| PL01, AD01, AD02 regression | real backend | PASS |

## Limits observed (handoffs in `HANDOFFS_PORTALS.md`)
- H-C1-16: no route lists the workspaces of a tenant and `/admin/workspaces` rows carry no `tenantId`; a tenant admin who is not a member of a workspace it just created cannot pick it in the next dialog (it can add people to it only through that workspace's own admin). The Platform dialog lists the first page of `/admin/workspaces` and offers a workspace only when it belongs to the chosen tenant (checked by the server: 404 WORKSPACE_NOT_FOUND).
- The activation endpoints are limited to 30 calls / 10 min / IP: the real suite is split across runs and never bypasses it.
