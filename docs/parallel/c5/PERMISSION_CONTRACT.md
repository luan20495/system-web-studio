# C5 — frontend permission contract (C1 decision: a VIEWER holds APP_VIEW)

Frontend capability checks are **UX only**. Hiding or disabling a control is never authorisation: the server independently enforces actor, tenant, workspace, project/app, resource scope and permission on every call. E2E-05 proves it with forged requests (below).

## 1. Capability source

| | |
|---|---|
| Backend source | `PermissionMatrix` + `Permission.kt` (`integration/v2`): project roles VIEWER = APP_VIEW, APP_USE · EDITOR = APP_VIEW, APP_EDIT, APP_USE, DATA_SOURCE_VIEW, QUERY_EXECUTE, ACTION_EXECUTE · PUBLISHER = APP_VIEW, APP_PUBLISH, APP_USE · OWNER; workspace role WORKSPACE_ADMIN = all incl. DATA_SOURCE_MANAGE, DATA_MUTATE, WORKFLOW_*; workspace VIEWER / EDITOR / PUBLISHER = **no workspace-level permission** |
| Frontend field (project) | `ApiProject.permissions` (`GET /workspaces/{w}/projects/{p}`): canonical codes **plus storage names** (`PROJECT_READ` = APP_VIEW, `PROJECT_EDIT`/`PROJECT_SETTINGS` = APP_EDIT, `PROJECT_PUBLISH` = APP_PUBLISH, `PROJECT_MEMBERS` = APP_SHARE; contract §5 table) |
| Frontend field (portal) | `Me.workspaces[].permissions` (workspace-level canonical codes) and `Me.permissions` (platform + primary tenant) |
| The only translation | the documented storage alias table; an unknown code is dropped, a role name is never an input |
| One layer | `packages/permissions/src/canonical.ts` (pure, unit-tested, 20+ tests); `features/studio/builder/core/permissions.ts` adapts it to the Builder |

## 2. What is implemented (all from the resolved set)

| Capability | Rule in `canonical.ts` | Where it acts |
|---|---|---|
| STUDIO_ACCESS | `APP_VIEW` (portal gate `canViewStudioIn`; a workspace list that is ABSENT does not block, an EMPTY list does) | portal login/redirect → `/auth/no-access` |
| PROJECT_VIEW | `APP_VIEW` only (APP_USE / APP_EDIT / data codes never stand in) | `ProjectWorkspace` page gate → `/auth/no-access?reason=app-view` |
| PROJECT_EDIT | `APP_EDIT`; APP_VIEW without it = **read-only, never a redirect** | read-only notice, inputs/save/palette disabled, empty inspector says "chỉ có quyền xem" |
| TEST_QUERY | `APP_USE` + `QUERY_EXECUTE` + `APP_EDIT` (all three) | Test panel row, disabled with "Bạn chưa có quyền: …" naming what is missing |
| ACTION_RUN | `APP_USE` + `ACTION_EXECUTE`; mutating type (SUBMIT_FORM, CREATE/UPDATE/DELETE_RECORD, CALL_API) `+ DATA_MUTATE`; START_WORKFLOW `+ WORKFLOW_EXECUTE` (what the server checks); declared permission (`permissionRef`) `+ it`; TEST `+ APP_EDIT` | Test panel; Action editor hint lists the conjunction |
| WORKFLOW_RUN | start `APP_USE` + `WORKFLOW_EXECUTE`; TEST `+ APP_EDIT`. Status/cancel: no UI authority from "creator" (C4 F-1 open) | Test panel |
| PUBLISH / ROLLBACK | `APP_PUBLISH` (APP_EDIT never stands in). **There is no deployment-rollback UI or route to bind yet**; `canRollback` is ready and tested | publish button; draft "restore version" stays APP_EDIT (it is not a rollback of a deployment) |
| DATASOURCE | view metadata `DATA_SOURCE_VIEW` (no request without it); manage (create/update/delete/credential metadata/connection test) `DATA_SOURCE_MANAGE`; TEST/draft binding `DATA_SOURCE_MANAGE + APP_EDIT` | Data sources panel: catalogue, credential metadata and bindings are only requested when allowed |
| Credential secret | write-only: the UI never expects plaintext or ciphertext back; the password input is cleared the moment the request is sent | unchanged, verified by harness (no secret in the DOM) |

## 3. Role hardcoding

Found (Studio): `StudioApp` project-create form and template buttons (`role === "WORKSPACE_ADMIN" || "EDITOR"`), `MembersDrawer` workspace-member section (`role === "WORKSPACE_ADMIN" | "ADMIN"`), `ProjectWorkspace`/`CodeWorkspace` raw `PROJECT_*` string checks (a second, legacy permission vocabulary), `capabilitiesFor.canView = APP_VIEW || APP_EDIT`, a Test panel that checked ONE code per kind, `permissionToRun` (a single code per action type).
Removed: all of the above. The create form and the workspace-member list are now requested and **the server decides** (403/404 → a plain message / the section is not shown), because no canonical resolved capability exists for them (handoff H-C1-05). A source-scan unit test (`tests/builder/permissions.test.ts`, "GUARD") fails if `role === "VIEWER" | "EDITOR" | …` comes back in `features/studio`, `packages/permissions`, `packages/auth`, `packages/ui`.
Not touched: `features/admin/*` (the platform/tenant console: a different contract) and display-only role labels.

## 4. CONTRACT MISMATCH (open, owner C1 / C0) — H-C1-04

```
Expected:  VIEWER resolved permissions include APP_VIEW, so Studio opens read-only
Actual:    GET /auth/me            → permissions = [], workspaces[].permissions = []
           GET …/projects/{p}      → permissions = ["APP_USE","PROJECT_READ"]   (PROJECT_READ = storage name of APP_VIEW)
Impact:    the Studio portal gate reads /auth/me and redirects the VIEWER to /auth/no-access.
           /auth/me lists WORKSPACE-level codes only, so everyone whose rights come from a PROJECT membership
           (VIEWER, EDITOR, PUBLISHER) is refused; only WORKSPACE_ADMIN gets in.
Owner:     C1 / C0
Frontend workaround: NONE (APP_VIEW is not injected from a role name; the gate is not loosened)
```
Verified live on `integration/v2 @ 96110d3` (EDITOR and PUBLISHER project members: `me.workspaces[].permissions = []` as well). What the backend must expose is C1/C0's decision (e.g. project-scoped grants in `/auth/me`, or an "accessible projects with permissions" field); the frontend will consume whichever resolved field is chosen.

## 5. Evidence

| Test | Result |
|---|---|
| Unit `tests/builder/permissions.test.ts` | the 14 required cases + role-name cases + source guard, all pass (suite 201/201 at that point) |
| Browser harness (`builder.spec.mjs` "PERM …", `datasources.spec.mjs` viewonly/noview/nobind) | viewer read-only (inputs, save, publish, Test controls, **zero** runtime calls); editor with all conjunctions sends exactly one call per control; partial sets disable exactly the control that needs the missing code and name it; role-looking strings grant nothing; DATA_SOURCE_VIEW-only requests metadata only |
| Real E2E-05, API half | same scope + missing permission → **403** for PATCH schema (valid operation), publish, TEST action, TEST workflow start, data-source create; foreign workspace → **404** for the same five; foreign and unknown identical; revision, deployments and data sources unchanged |
| Real E2E-04 cases B and C | a person with no APP_VIEW anywhere → `/auth/no-access` (no project data); adminA opens and edits (the gate does not break the editor/admin) |
| Real E2E-04 case A, E2E-05 UI half | **FAIL — CONTRACT MISMATCH** (§4): the VIEWER is refused at the portal although the project payload resolves APP_VIEW |

Run: `docs/parallel/c5/e2e-stack.sh e2e E2E-04,E2E-05`.
