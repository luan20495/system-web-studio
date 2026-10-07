# C5 — User provisioning UI (prepared before C1's contract, 2026-10-07)

Flow: SYSTEM_ADMIN → tenant admin → (Admin portal) new user → workspace + role → user logs into Studio.
**Rule applied:** no endpoint is invented. The UI talks to one interface, `ProvisioningApi` (`features/admin/provisioning.ts`); its production adapter maps ONLY routes that exist in the backend source today; the rest is `NOT_READY` and the screen says so.

## What exists and what does not (verified in the backend source, integration/v2 @ 1a9995c + C1 7ecea1a)
| Capability | State | Route |
|---|---|---|
| createPlatformUser (SYSTEM_ADMIN) | **READY** | `POST /admin/users` {username, displayName, email?, workspaceId, role} → activation link |
| assignTenantRole | READY | `PUT /admin/tenants/{t}/members/{u}` (the account must be ACTIVATED and related to the tenant) |
| assignWorkspaceRole / addWorkspaceMember | READY | `PATCH` / `POST /workspaces/{w}/members` (MEMBER_MANAGE) |
| listMemberCandidates | READY on the contract, **500 on PostgreSQL as committed** (H-C1-11) | `GET /admin/tenants/{t}/member-candidates` |
| resetCredential / enableUser / disableUser | READY | `POST /admin/users/{u}/activation-link`, `PATCH /admin/users/{u}/status` |
| **createTenantUser** | **NOT_READY (C1)** | none: `/admin/users` is SYSTEM_ADMIN-only |
| **inviteTenantUser** | **NOT_READY (C1)** | none |
Also missing (H-C1-14): a workspace cannot be created for a tenant (`POST /admin/workspaces` takes only a name) and `/admin/workspaces` rows carry no `tenantId`, so a NEW tenant can have no workspace and the Platform dialog cannot filter workspaces by tenant.

## Files
| Role | File |
|---|---|
| contract + capability table + adapter (pure) | `features/admin/provisioning.ts` |
| rules: plan per scope, validation, error mapping by code | `features/admin/provisioningModel.ts` |
| screens: `CreateAccountDialog`, `PeopleView` (presentational, harness-tested) | `features/admin/ProvisioningScreens.tsx` |
| real wiring: Platform dialog, Admin "Người dùng" page | `features/admin/ProvisioningLive.tsx`, `features/admin/provisioningAdapter.ts` |
| routes | Platform: `/platform/users` → "+ Tạo tài khoản"; Admin: `/admin/people` ("Người dùng") |

## When C1 delivers the contract (the only edits)
1. `provisioning.ts` → `CAPABILITIES.createTenantUser` (and `inviteTenantUser`): `status: "READY"`, `route`.
2. `provisioning.ts` → the adapter method body (body shape per C1's contract; the tenant id must NOT be sent if the server derives it) and `provisioningAdapter.ts` (the `api.admin.*` call) — add the call to `packages/api-client/src/api.ts`.
3. `ProvisioningTransport` gets the new method; the unit test "wiring C1's contract later" already shows the flip.
4. Rerun: harness `provisioning.spec.mjs` (30), unit, then real `E2E-ADMIN01` (its tail becomes the real create + activation), `E2E-USER01` (needs H-C1-04), `E2E-SUPER01`.

## Verified
| What | Class | Result |
|---|---|---|
| unit: capability table, adapter (no invented routes, NOT_READY sends nothing), plan by scope (no role names), validation, errors by code | unit | 10 tests |
| PUI01–09, AUI01–07 (+ extras): gating, validation, tenant fixed, no SYSTEM_ADMIN / cross-tenant option, NOT_READY, 403, 409, 404, 503/network, success | harness (fake transport behind the REAL adapter) — **not a backend E2E** | 30/30 |
| SUPER01: Platform UI creates a tenant-admin account → activation through the real link → company role in the tenant page → the new admin opens the Admin portal | real backend | 13 checks PASS; ends BLOCKED (C1, H-C1-14) |
| ADMIN01: tenant admin's Người dùng: tenant from the session, create NOT_READY (disabled, reason, nothing sent), platform route refused (403) | real backend | 7 checks PASS; ends BLOCKED (C1 contract) |
| USER01: creator (EDITOR) — API side: project APIs, edit, persist, no publish, no MEMBER_MANAGE | real backend | 7 checks PASS; ends BLOCKED (C1, H-C1-04: the Studio gate refuses a project-only EDITOR) |
| SEC01 (tenant admin cannot create / grant SYSTEM_ADMIN), SEC02 (tenant A vs B: 404), SEC03 (workspace admin cannot escalate) | real backend (API level, real sessions) | 11 checks PASS |
