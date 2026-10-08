# C5 — Organization structure + employee directory (Admin portal), built before C1's contract (2026-10-08)

Routes: `/admin/organization` ("Cơ cấu tổ chức") and `/admin/employees` ("Nhân viên"), in the nav of a person for whom the server lists a tenant they administer (same derivation as "Công ty của tôi").
**Rule applied:** no endpoint is invented. Both screens talk to `OrganizationApi` (`features/admin/organization.ts`); every operation is `NOT_READY` (owner C1) until C1 publishes a contract, the adapter throws `OrganizationNotReady` and sends **nothing**.
The only live call is the tenant member list (`GET /admin/tenants/{t}/members`, C1 2356d64), which backs the employee directory while `listEmployees` is NOT_READY (the screen says unit / position are not available).

## What was built
| Area | Behaviour |
|---|---|
| Tree | dynamic, no fixed levels. WAI-ARIA tree (`role=tree/treeitem`, `aria-level/expanded/selected`, roving tabindex; ↑ ↓ → ← Home End Enter). Node: icon tile of its TYPE, name, type badge, employee count, child count, "Đã tắt" / "Mồ côi" marks. Orphans and data cycles are surfaced at the root, never dropped |
| Actions | add root / child, edit, **move ("Di chuyển tới…" dialog, not drag and drop)**, enable / disable, delete. Each write carries the unit's `version` (optimistic lock) |
| Move | every place listed with the reason when it is not allowed: itself, own subtree (cycle), "đang ở đây", disabled unit, type rule. The server stays the authority (a cycle code is shown as such) |
| Delete | blocked BEFORE the click when the known counts say so (children / employees); otherwise the server decides and its refusal is shown |
| Unit types | company-defined: name, code (`DIVISION`), icon from a **closed allow-list of 16 Lucide icons** (an id, never a URL), optional "may sit inside" types. Parent-type rule enforced in the forms |
| States | loading · empty (explanation + CTA) · error with retry · permission denied (no backend call) · NOT_READY panel · validation · duplicate · cycle · blocked delete · version conflict ("Tải lại cơ cấu") · backend unavailable ("chưa rõ đã ghi") |
| Directory | search (accent-insensitive), filter by unit (subtree) and status, 20 per page, rows → detail. Responsive: table → cards ≤ 720 px |
| Create employee | the existing tenant provisioning dialog (`createTenantUser`: tenant is the PATH, never in the body) titled "Thêm nhân viên", plus an optional "Cơ cấu tổ chức" section (unit, position). If the account is created but the follow-up assignment fails, the account is NOT rolled back: the failure is a pending step in the result |
| Detail | sections Thông tin · Cơ cấu tổ chức · Vị trí/Cấp bậc · Workspace · Quyền hiệu lực (read-only: company role label + "organization grants no permission") · Trạng thái |
| Tenant | the session's (read-only) or one of the caller's OWN tenants; never a free id. No SYSTEM_ADMIN anywhere |
| Gate | `organizationPlan(adminScope(me))`: offered only when the server lists a tenant the caller administers. No role name is read. The capability the server will list for org editing is **not decided by C1**: the gate uses the closest existing tenant capability and `needsAssumed: true` marks it |

## Files
`features/admin/organization.ts` (contract, CAPABILITIES, adapter) · `organizationModel.ts` (tree, move/cycle, validation, gate, errors, member-list directory) · `OrganizationScreens.tsx` · `EmployeesScreens.tsx` · `unitIcons.tsx` · `OrganizationLive.tsx` + `organizationAdapter.ts` (wiring) · nav in `AdminApp.tsx` · styles `packages/ui/src/styles/factory.css` · `tests/builder/organization.test.ts` · `tests/browser/org-harness.tsx` + `org.spec.mjs` · `tests/e2e-real/flows/e2e-org01.mjs` (WAITING_FOR_C1).

## When C1 publishes the contract (the only edits)
1. `organization.ts` → `CAPABILITIES.<op>`: `status: "READY"`, `route`, `needs` (the capability the SERVER lists). Add the calls to `packages/api-client/src/api.ts` and to `organizationAdapter.ts`.
2. `organizationModel.ts` → `organizationPlan` gate (replace the assumed capability) and `BY_CODE` (replace the ASSUMED error names with C1's).
3. `provisioning` ↔ employee: if C1 takes organization fields in `POST /admin/tenants/{t}/users`, send them there and drop the follow-up calls in `CreateEmployeeDialog.afterCreate`.
4. Rerun `org.spec.mjs`, unit, then **E2E-ORG01** (replace its Blocked with the steps written in the file).

## Verified
| What | Class | Result |
|---|---|---|
| contract layer (all NOT_READY, no URL, adapter sends nothing, member-list fallback, READY flip), tree, move / cycle, validation, icon allow-list, delete rules, directory paging / search, gate, error mapping | unit | 11 tests in `organization.test.ts` |
| ORG_UI01–10, TYPE01–05, NR01–02, EMP_UI01–10, EMP_NR01–06 (+ axe wcag2a/aa critical+serious: 0) | **harness** (fake in-memory transport behind the REAL adapters) — **not a backend E2E** | 89/89 |
| E2E-ORG01 | real backend | **WAITING_FOR_C1** (no contract) |
