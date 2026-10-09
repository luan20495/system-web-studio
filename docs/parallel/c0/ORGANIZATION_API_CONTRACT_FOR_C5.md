# Dynamic Organization - API / DTO / error contract handed to C5 (C0, D-C0-52)

Status: **FROZEN for C5 wiring; the server flag is still OFF.** `app.organization.persistence-enabled=false` => every route below answers `501 ORG_PERSISTENCE_NOT_AVAILABLE` after authentication and authorization. The frontend stays NOT_READY (`tests/guards/org-contract.json` `wired = []`) until C5 wires it and C0 proves it in a real browser (E2E-ORG01) with the flag ON. Source of the DTO shapes: `backend/src/main/kotlin/com/systemwebstudio/organization/OrganizationContract.kt` (the same data classes are the JSON bodies). Everything is tenant scoped: `/api/v1/admin/tenants/{tenantId}/...`; a record of another tenant is a `404`.

## Permissions (canonical, no aliases; from `/auth/me` `workspaces[].permissions[]` / `tenants`)
`ORG_STRUCTURE_VIEW` / `ORG_STRUCTURE_MANAGE` (types, units), `EMPLOYEE_VIEW` / `EMPLOYEE_MANAGE` (employees, memberships, employee positions), `POSITION_GRADE_VIEW` / `POSITION_GRADE_MANAGE` (positions, grades). An organization relation, a position or a grade grants NO permission. `ORG_MANAGE` does not exist.

## Routes (all under `/api/v1/admin/tenants/{tenantId}`)
| Area | Routes |
|---|---|
| Unit types | `GET/POST organization-unit-types`, `GET/PATCH .../{typeId}`, `POST .../{typeId}/disable` or `enable` |
| Units | `GET organization-units?format=tree|flat&includeArchived=`, `GET/PATCH .../{unitId}`, `POST organization-units`, `POST .../{unitId}/move` (key `newParentId` mandatory, `null` = root), `POST .../{unitId}/archive|restore` |
| Positions / grades | `GET/POST positions`, `GET/PATCH positions/{id}`, `POST positions/{id}/disable|enable` (same for `grades`) |
| Employees | `GET employees` (directory), `GET/POST employees`, `GET employees/{userId}`, `POST employees/{userId}/disable|enable` |
| Memberships | `GET/POST employees/{userId}/organization-memberships`, `PATCH/DELETE .../{membershipId}` |
| Employee positions | `GET/POST employees/{userId}/positions`, `PATCH/DELETE .../{employeePositionId}` |

Versions start at **0**. Every write carries `expectedVersion` (header `ETag`/body `version` returned); a stale write is `409 VERSION_CONFLICT` with `details.currentVersion`.

## What C0 added in D-C0-52 (new fields / behaviour)
* `GET organization-units` (tree) nodes: `{unit, children, directMemberCount, subtreeEmployeeCount}`; `GET organization-units/{id}`: `{unit, path, activeChildCount, activeMemberCount, directMemberCount, subtreeEmployeeCount}`. `directMemberCount` = ACTIVE memberships of ACTIVE employees on exactly that unit; `subtreeEmployeeCount` = DISTINCT active employees over the unit and its non-archived descendants (an employee in several units of the subtree counts once). Ended memberships, disabled employees and archived units are not counted; an archived unit answers 0 / 0. The two fields are `null` only when the store provides no counts. `activeMemberCount` (C1) is unchanged and is NOT the subtree count. The tree call computes the counts of the whole tenant in ONE statement (about 140 ms median for 2,000 units / 10,000 employees on the benchmark host); a lazy UI that expands one level should prefer `flat` + detail.
* Directory `GET employees?page=&size=`: `size` 1..100, `page >= 0`, **`page * size <= 10000`**. Anything else is `400`: `VALIDATION_FAILED` (negative page, size outside 1..100) or `OFFSET_TOO_LARGE` (offset above 10,000: narrow the search, do not page deeper). The API never returns an empty page with a total for an invalid offset.
* `503 ORG_STRUCTURE_BUSY`: a structural write could not get the tenant structural lock (or a row lock) within `app.organization.structural-lock-timeout-ms` (default 5000). Body `details = {retryable: true, retryAfterSeconds: N}`, header `Retry-After: N` (integer seconds, `N = max(1, ceil(timeoutMs / 1000))`). **Nothing was executed**: retry the same request (same `expectedVersion`). Not a validation error, not a conflict.
* `409 ORG_TYPE_RULE_VIOLATION` (`details.reason` = `MAX_DEPTH | ROOT_NOT_ALLOWED | PARENT_TYPE_NOT_ALLOWED | CHILD_TYPE_NOT_ALLOWED`) is now also returned by `PATCH organization-unit-types/{id}` with `rules` when the unit tree that exists now already violates the new rule (`details.unitId` names a unit).

## Errors the UI must handle (code -> status)
`ORG_PERSISTENCE_NOT_AVAILABLE` 501 (feature off: show "not available", never fake data) - `ORG_STRUCTURE_BUSY` 503 retry - `VERSION_CONFLICT` 409 reload - `ORG_CYCLE`, `ORG_UNIT_CODE_TAKEN`, `ORG_UNIT_ARCHIVED`, `ORG_UNIT_HAS_CHILDREN`, `ORG_UNIT_HAS_MEMBERS`, `ORG_TYPE_RULE_VIOLATION`, `ORG_UNIT_TYPE_DISABLED`, `ORG_UNIT_TYPE_CODE_TAKEN`, `RESTORE_CONFLICT` (`details.reason`), `EMPLOYEE_ORG_HAS_POSITIONS` 409 - `ORG_UNIT_NOT_FOUND`, `ORG_UNIT_TYPE_NOT_FOUND` and the other `*_NOT_FOUND` 404 - `VALIDATION_FAILED`, `OFFSET_TOO_LARGE`, `QUERY_TOO_SHORT`, `INVALID_CODE` 400 - `403 TENANT_SUSPENDED` for org writes of a suspended company - `401/403` as everywhere.

## Rules the UI must not re-implement
Unit `code` is canonical UPPER-CASE (the server uppercases and stores it) and unique among non-archived siblings (roots are siblings of each other). The tenant, never the browser, scopes every call. There is no hard delete: units are archived / restored. A position is held WITHIN an active membership (`membershipId` mandatory); the grade is a nullable attribute of that holding.
