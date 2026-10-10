# C5 — Dynamic Organization: structure + employees + positions / grades (Admin portal), WIRED to the real backend (2026-10-10)

Contract: `docs/parallel/c0/ORGANIZATION_API_CONTRACT_FOR_C5.md` (D-C0-52) over C1's `organization-employee-contract.md` and the DTOs in `backend/.../organization/OrganizationContract.kt`. Evidence classes: UNIT, HARNESS (`org*.spec.mjs` over a typed in-memory fake), REAL_BACKEND (only `E2E-ORG01`, see §7). **The server flag `ORGANIZATION_PERSISTENCE_ENABLED` stays `false` by default; nothing in the frontend enables it.**

Routes: `/admin/organization` ("Cơ cấu tổ chức") and `/admin/employees` ("Nhân viên"), offered by the codes of a company (M-052, `tenants[].permissions`), not by a role.

## 1. Boundary (one typed layer)
```
screens (OrganizationScreens / OrganizationTree / OrganizationTypes / OrganizationCatalog / EmployeesScreens)
   └─ OrganizationApi (features/admin/organization.ts: shapes requests, maps DTOs, clamps paging, passes every error through untouched)
        └─ api.org (packages/api-client/src/org.ts: the ONLY place that builds a URL, query or body)
             └─ call() (ApiError: status, code, details, retryAfterSeconds)
```
Pure rules (tree, counts, placement rules, forms, hints, gate, error mapping): `features/admin/organizationModel.ts`. DTO mirror: `packages/types/src/index.ts` (`Org*`). No UI component names a route (guard `ORG-FAIL-CLOSED-ROUTE`; `tests/guards/org-contract.json` `wired` lists the 31 capabilities, changed together with `CAPABILITIES`).

## 2. Capability classification (REAL_BACKEND = a route the backend serves; the proof of each is UNIT/HARNESS until E2E-ORG01 runs on a flag-ON stack)
| Capability | Class | Permission the SERVER must list (for THAT company) |
|---|---|---|
| unit types: list / create / update (name, icon, rules) / disable / enable | REAL_BACKEND | ORG_STRUCTURE_VIEW / ORG_STRUCTURE_MANAGE |
| units: tree (`format=tree`, with both counts), detail, create, update, move / reparent (`newParentId` always sent, null = root), archive, restore | REAL_BACKEND | ORG_STRUCTURE_VIEW / ORG_STRUCTURE_MANAGE |
| employee directory (search ≥ 2 chars, unit ± descendants, position, grade, status, paging) + detail | REAL_BACKEND | EMPLOYEE_VIEW |
| create employee (account + memberships + positions in ONE request), enable / disable | REAL_BACKEND | EMPLOYEE_MANAGE **and** TENANT_MEMBERS |
| memberships (several, one primary; relation label), positions held within a membership (+ grade) | REAL_BACKEND | EMPLOYEE_VIEW / EMPLOYEE_MANAGE |
| positions and grades: list, create, update, disable / enable | REAL_BACKEND | POSITION_GRADE_VIEW / POSITION_GRADE_MANAGE |
| counts `directMemberCount` / `subtreeEmployeeCount` | REAL_BACKEND (from the tree and the detail) | ORG_STRUCTURE_VIEW |
| hard delete of a unit, employee profile fields (code, phone, joined date), tenant-global position, editing an employee's identity | **NOT_SUPPORTED** (not in the contract; not offered, nothing invented) | – |

## 3. Behaviour that matters
* **Two counts, never merged**: "N trực tiếp" (ACTIVE memberships of ACTIVE employees on exactly that unit) and "M cả nhánh" (DISTINCT employees over the unit and its non-archived descendants; one person in two units of the branch counts once, so M is not the sum). Visible text + tooltip; unknown (null) is "chưa có số liệu", never 0.
* **Errors by the server's code** (`orgProblem`): `ORG_STRUCTURE_BUSY` 503 → a RETRY state (kind `busy`, Retry-After from the header or `details`, "chưa có gì được lưu", a "Thử lại" button that resends the SAME request with the person's input kept; never success); `ORG_PERSISTENCE_NOT_AVAILABLE` 501 → fail closed (own state, no data, no mock, no local copy, no fallback to the member list); `VERSION_CONFLICT` (reload), `ORG_CYCLE`, `ORG_TYPE_RULE_VIOLATION` (reason MAX_DEPTH / ROOT_NOT_ALLOWED / PARENT_TYPE_NOT_ALLOWED / CHILD_TYPE_NOT_ALLOWED), `ORG_UNIT_HAS_CHILDREN` / `ORG_UNIT_HAS_MEMBERS` (archive blocks), `RESTORE_CONFLICT` (reason NOT_ARCHIVED / TENANT_INACTIVE / TYPE_DISABLED / PARENT_ARCHIVED / TYPE_RULE / CODE_TAKEN), `OFFSET_TOO_LARGE`, `QUERY_TOO_SHORT`, `TENANT_SUSPENDED`, disclosure-safe `*_NOT_FOUND`. The server's English message is never shown.
* **Paging guard**: size 1..100, page × size ≤ 10 000, search ≥ 2 characters are enforced BEFORE the request (`clampOrgPaging`); the pager stops at the last reachable page and says "thu hẹp tìm kiếm"; the fake server is stricter than the client and never has to refuse (LIM01-03).
* **Max depth is the backend's decision**: only the company's own `rules.maxDepth` (read from the server) is shown as an advisory note; no depth number is in the code (DEPTH01-04).
* **Archive / restore, no delete**: in-app confirmation (Cancel focused, focus restored), advisory hints from the counts, the server decides; an archived unit offers only "Khôi phục"; "Hiện cả đơn vị đã lưu trữ" reloads with `includeArchived`.
* **Relation / position / grade authorize nothing** (UNIT test + guard `ORG-RELATION`): a person labelled HEAD / MANAGER with a senior grade still gets exactly the codes the server listed.
* **Permissions per company** (M-052): `capsOf(scope, tenantId)` from `tenants[].permissions`; the screens judge the SELECTED company only.

## 4. Files
`features/admin/{organization.ts, organizationModel.ts, organizationAdapter.ts, OrganizationScreens.tsx, OrganizationTree.tsx, OrganizationTypes.tsx, OrganizationCatalog.tsx, EmployeesScreens.tsx, orgParts.tsx, OrganizationLive.tsx}` · `packages/api-client/src/org.ts` · `packages/types/src/index.ts` · tests: `tests/builder/organization*.test.ts`, `organization-client.test.ts`, `tenant-scope.test.ts`; `tests/browser/{org,org-employees,org-hardening}.spec.mjs` + `org-harness.tsx` + `org-fake-server.ts`; `tests/e2e-real/flows/e2e-org01.mjs`.

## 5. Harness fake
`tests/browser/org-fake-server.ts` is an in-memory implementation of the typed transport that answers the way the contract says (versions, cycles, type rules incl. maxDepth, archive blocks, restore conflicts, directory limits, 503 busy with Retry-After, 501, 403 TENANT_SUSPENDED). Its numeric limits are written out independently of the client. It proves what the SCREENS do with those answers, never what a server answers.

## 6. Gate
`npm run gate:frontend`, unit, typecheck, `org` 122 / `org-employees` 76 / `org-hardening` 67 checks (CHROMIUM, HARNESS).

## 7. E2E-ORG01 (real backend)
`tests/e2e-real/flows/e2e-org01.mjs`: 25 flag-ON cases, each recording REQUEST · EXPECTED · ACTUAL · HTTP · UI · SERVER. It runs ONLY against a C0-approved isolated stack with `ORGANIZATION_PERSISTENCE_ENABLED=true`. On a default (flag OFF) stack it records REAL_BACKEND evidence of the fail-closed behaviour (cases 0a-0g: 501 on every route family after authorization, 403 for a member, the real portal's "chưa khả dụng" state, no browser persistence) and ends **BLOCKED(C0)**: it is never reported as PASS and never enables the flag.
