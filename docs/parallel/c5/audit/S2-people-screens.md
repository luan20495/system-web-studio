# S2 — the people screens of the Admin console (M-065)

Decision (user-approved, binding): do **not** merge the people screens just to reduce routes. Keep distinct jobs separate; consolidate the shared building blocks. Dynamic Organization stays NOT_READY (C1), so no route is added or removed. Route snapshot `tests/browser/admin-routes.snapshot.json` is unchanged.

## Jobs to be done (who / what / which API)

| Route | Shown to | Job | Data source |
|---|---|---|---|
| `/admin/company` — Công ty của tôi | tenant admin (not SYSTEM_ADMIN) | See my company (status, code) and decide **who administers it**: promote / demote / remove tenant members | tenant API (`/admin/tenants/{id}`, members) |
| `/admin/organization` — Cơ cấu tổ chức | tenant admin | Build the **structure**: unit types and the unit tree (no people) | organization API (NOT_READY parts say so) |
| `/admin/employees` — Nhân viên | tenant admin | The **employee directory**: search / filter by unit and status, add an employee into a unit with a position, open an employee | organization employee API, falls back to the member list |
| `/admin/people` — Người dùng | tenant admin **or** workspace admin | **Accounts and workspace access**: create an account (one-time activation link), add an existing person to a workspace with a role | provisioning API |
| `/admin/groups` — Nhóm | — | placeholder ("Sắp có"), no backend | — |

Evidence that they are not duplicates: each screen has a different primary object (company admins / units / employees / accounts + workspace membership), a different audience (`people` is the only one a workspace admin sees) and a different API. Merging Nhân viên and Người dùng would hide the provisioning screen from workspace admins or show them an employee directory they may not read. Organization has no people at all.

The "two create-account entry points" (Người dùng › Tạo tài khoản, Nhân viên › Thêm nhân viên) already run the **same** dialog (`CreateAccountDialog`, `ProvisioningScreens.tsx`); Nhân viên only adds the unit / position section (`extraSection`). That is one implementation with two contexts, kept.

## What was consolidated (this change)

- `features/admin/shared/peopleSections.ts` — ONE table of the people screens: label, one-line job, and the visibility rule. The sidebar (`console/sections.tsx` `navWhen`) and the cross-links read the same rule (before: four inline copies of the predicate).
- `features/admin/shared/PeopleLinks.tsx` — one line under each people screen's heading naming the sibling screens the person can open and what each is for (the four routes read as one area). Hidden when there is no sibling (workspace admin, SYSTEM_ADMIN).
- `features/admin/shared/TenantSwitch.tsx` — the "Công ty" select (was copied in Công ty của tôi, Cơ cấu tổ chức, Nhân viên). Test ids kept (`org-tenant-switch`, `emp-tenant-switch`).
- `features/admin/shared/ownWorkspaces.ts` — `useOwnWorkspacesOf()`, the caller's workspaces of a company for the create-account dialog (was copied in Người dùng and Nhân viên).
- Already shared before this change and kept: `PersonPicker` (member picker), `CreateAccountDialog`, `LoadGate` / `ErrorState` / `StateView` from `@xweb/ui`, `useLoad` keyed cache, `adminModel.ts` role labels and member rules.

## What stays separate (and why)

- The four routes and their pages: different jobs / audiences / APIs (above).
- Role editors: tenant role (TENANT_ADMIN / MEMBER) and workspace role (4 roles) are different models with different last-admin rules (`memberChangeBlock` vs `workspaceMemberBlock`); one generic editor would hide that.
- Employees' table and filters: unit / position / status filters exist only there.

## Candidates to promote to `@xweb/ui` (S3 owns packages/ui)

`TenantSwitch` (generic "scope switch" select), `useDebounced` (`features/admin/shared/useDebounced.ts`, added for M-097).

## Tests

Unit `tests/builder/adminmodel.test.ts` (M-065 related links per persona); harness `tests/browser/admin.spec.mjs` PPL01–PPL03 (HARNESS, NOT REAL BACKEND); `admin`, `org`, `org-hardening`, `provisioning`, `aiproviders` specs and the route snapshot unchanged and green.
