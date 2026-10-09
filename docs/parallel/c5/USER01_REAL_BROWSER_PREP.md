# USER01 — real-browser verification of H-C1-04 (C5 side prepared, NOT run)

Status: **READY for C0 to run; no real-backend result exists yet.** Nothing here is faked: the data comes from the product's own API, the flow refuses to start without a stack. Evidence classes: the unit / harness results in `docs/parallel/c5/audit/H-C1-04-frontend.md` are UNIT / HARNESS; only `E2E-USER01` / `E2E-04` / `E2E-05` against the integrated backend are REAL_BACKEND.

## What C5 implemented (the rule the flow must observe)
Studio admission, from `/auth/me` alone (`packages/permissions/src/canonical.ts`, `index.ts`):
- **Portal** (`capabilitiesOf` → `studio.build`): some `workspaces[].permissions` lists `APP_VIEW`, OR some `projectScopes[].permissions` lists `APP_VIEW`. Each scope is judged on its own; absent `projectScopes` = older backend (cannot say no).
- **One project** (`canAdmitProject`, used by `ProjectWorkspace` before anything of the project is rendered): the project's workspace row lists `APP_VIEW`, OR the `projectScopes[]` row of EXACTLY this `projectId` + `workspaceId` lists `APP_VIEW`; AND the project payload's own resolved list holds `APP_VIEW`. Otherwise `router.replace("/auth/no-access?portal=studio&reason=app-view")` and a neutral "Đang chuyển…" state (no builder, no project name).
- Never: a role label, `APP_PUBLISH` / `APP_EDIT` / `APP_USE` / `APP_SHARE` / tenant codes standing in for `APP_VIEW`; a scope merged into a global set; project A admitting project B.

## Test data C0 must provide (all through the product API, `tests/e2e-real/lib/portals.mjs` helpers)
| Name | What | Needed `/auth/me` shape |
|---|---|---|
| tenant T, workspace W | made by SYSTEM_ADMIN → TENANT_ADMIN → WORKSPACE_ADMIN `wsa` | – |
| user `u01cr` ("USER01") | workspace role **EDITOR** in W, **no** workspace-level Studio code | `workspaces[W].permissions` has no `APP_VIEW`; `tenantRole` `MEMBER` |
| project **A** (PAGE_SCHEMA) in W | created by `wsa`; `u01cr` added as project **EDITOR** | `projectScopes` has ONE row: `{projectId:A, workspaceId:W, permissions ⊇ [APP_VIEW, APP_EDIT, APP_USE]}`, **no** `APP_PUBLISH`, **no** `APP_SHARE` |
| project **B** (PAGE_SCHEMA) in W | created by `wsa`; `u01cr` is **not** a member | no row for B |
| optional: project **C** in a second workspace of T | `u01cr` not a member of that workspace | no row, no workspace entry |
`E2E-USER01` (`tests/e2e-real/flows/e2e-user01.mjs`) creates A and B itself (and the whole chain if ADMIN01 did not run); the exact ids are recorded in `fx.created.projects` and printed in the report.

## Steps, selectors and expected states (Studio portal `:3003` or the integrated origin)
| # | Step | Selector / call | Expected |
|---|---|---|---|
| 1 | log in as `u01cr` | `/login` → `getByLabel("Tên đăng nhập")`, `getByLabel("Mật khẩu")`, `getByRole("button",{name:"Đăng nhập"})` (`tests/e2e-real/lib/ui.mjs loginUi`) | lands on `/studio`, **not** `/auth/no-access`, not `/login` |
| 2 | load identity | `GET /auth/me` (report the JSON, secrets redacted) | `projectScopes` = exactly the A row; `permissions` has no `TENANT_*` / `ORG_*` code |
| 3 | project list | `/studio/projects` | contains project A's name, NOT project B's |
| 4 | open A | `/studio/projects/{A}/design` | `.bx-frame iframe` present (canvas), project name in the top bar, no `/auth/no-access` |
| 5 | no publish implied | the `Xuất bản` button of the top bar (`GuardedButton`) | `aria-disabled="true"` with a visible reason naming the missing permission; `POST …/publish` → **403** (already asserted) |
| 6 | no sharing implied | `Chia sẻ` | unavailable (no `APP_SHARE`) |
| 7 | **unauthorized project, direct URL** | `/studio/projects/{B}/design` typed into the address bar | **no** `.bx-frame iframe`, project B's name appears nowhere on the page, either a not-found / error state (server `404 PROJECT_NOT_FOUND`) or `/auth/no-access?portal=studio&reason=app-view`; `GET /workspaces/W/projects/B` → 404 / 403 |
| 8 | no cross-project leak | repeat 7 for C (if provided) and for a made-up uuid | same denial, never A's content |
| 9 | no role bypass | the page text / DOM never shows or tests a role string; renaming nothing | `grep` of the body for `OWNER|EDITOR|TENANT_ADMIN` in an authorisation-looking context is empty (the role may only appear as display text in the member drawer) |
Expected 403/404 handling: a 401 anywhere → `/login` (session reset); 403 on a write → the shared refusal text, no success toast; 404 on a project → the not-found state, no retry button.

## Also run (already in the suite)
`E2E-04` (Studio access follows the resolved permission set), `E2E-05` (UI permission state is not enforcement), `E2E-AD02`, `E2E-ADMIN01`. Expected change against the previous backend: `E2E-USER01` no longer ends `BLOCKED(C1)`; if it still does, the backend (`/auth/me.projectScopes`) is not the final H-C1-04 build.

## What would make this FAIL (report as such, do not work around)
- step 1 refuses `u01cr` although `projectScopes` lists A with `APP_VIEW` → frontend admission bug (C5);
- step 7 renders any project B content, or A's scope opens B → frontend bug (C5) or a backend disclosure (C1): attach the `/auth/me` JSON;
- step 5 enabled, or the publish call not 403 → backend authorization (C1), not a frontend decision.
