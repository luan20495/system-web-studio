# H-C1-04 — project-scoped Studio access

Base: `integration/v2 @ e310b6a16156` (rebased from `fix/c1-h-c1-04-rc` which was validated on 62ce9697cd56; cherry-picked cleanly, no conflict).

## Root cause

`GET /api/v1/auth/me` previously resolved only:
- platform / primary-tenant permissions in `permissions`;
- workspace-role permissions in `workspaces[].permissions`.

Project APIs use `AccessService.forProject()`, which combines the caller's actual workspace + project membership and applies tenant status, archive restrictions and default deny. That resolver was never represented in `/auth/me`, so a user with only project-level `APP_VIEW`/ `APP_EDIT` could call the project API but the Studio portal saw no business permission.

## Additive response

`GET /api/v1/auth/me` now also returns:

```json
{
  "projectScopes": [
    {
      "projectId": "00000000-0000-0000-0000-000000000000",
      "workspaceId": "00000000-0000-0000-0000-000000000000",
      "role": "EDITOR",
      "permissions": [
        "ACTION_EXECUTE",
        "APP_EDIT",
        "APP_USE",
        "APP_VIEW",
        "DATA_SOURCE_VIEW",
        "QUERY_EXECUTE"
      ]
    }
  ]
}
```

`role` is informational only. C5 must gate on canonical `permissions`, never role names.

Each project scope is derived by the same `AccessService.forProject()` used by backend project endpoints. Therefore:
- no new permission is granted;
- archive restrictions remain identical;
- tenant/workspace/project isolation remains identical;
- stale/inactive/out-of-scope memberships are omitted;
- project permissions are NOT flattened into top-level `permissions` or `workspaces[].permissions`.

Studio admission may use: at least one usable scope containing `APP_VIEW` (workspace or project scope).

## Negative invariants

- no project membership => no `projectScopes`;
- TENANT_ADMIN alone => no Studio project scope;
- SYSTEM_ADMIN alone => no Studio project scope/business permission;
- foreign project => never disclosed;
- lack of `APP_PUBLISH` => publish stays 403;
- multiple projects remain independent rows and are never unioned.

## Validation (macOS, JDK 21, Docker, PostgreSQL via Testcontainers)

Branch `fix/c1-h-c1-04-v2` on `integration/v2 @ e310b6a16156`. `ProjectScopedAuthMeTests` has 12 tests, all through the real HTTP API and the real database:

| Case | Proves |
|---|---|
| A VIEWER | scope = exactly `APP_VIEW`, `APP_USE`; `GET project` 200; edit 403 |
| B EDITOR | `APP_VIEW`, `APP_USE`, `APP_EDIT`, `DATA_SOURCE_VIEW`, `QUERY_EXECUTE`, `ACTION_EXECUTE`; no `APP_PUBLISH`, `DATA_MUTATE`, `WORKFLOW_MANAGE`; save 200 |
| C PUBLISHER | exactly `APP_VIEW`, `APP_USE`, `APP_PUBLISH`; no edit / mutation / query; edit 403 |
| D no project membership | `projectScopes = []`; the project is 404 |
| E foreign project | absent from the scopes; direct API 404 |
| F TENANT_ADMIN alone | permissions exactly `TENANT_MANAGE` + `TENANT_MEMBERS`; no scope |
| G SYSTEM_ADMIN alone | no scope, no `APP_VIEW` / `MEMBER_MANAGE` in `workspaces[].permissions` |
| H two projects (A VIEWER, B EDITOR) | two separate rows; permissions never unioned |
| I stale / removed / deactivated membership, deleted project | omitted; the direct API agrees (404); the `/auth/me` body contains no `password`, `hash`, `token`, `secret` |
| J nobody / second user | empty response; one user's scope never appears in another's |
| K project membership whose workspace membership is inactive | omitted AND `/auth/me` still answers 200 (the `catch` branch; removing it fails this test) |
| real hierarchy flow | SYSTEM_ADMIN -> tenant -> workspace -> Tenant Admin (activate, login) -> `app.creator` (workspace VIEWER, activate, login) -> project created -> project EDITOR assigned -> `/auth/me` has `APP_VIEW`+`APP_EDIT`, no `APP_PUBLISH`; `workspaces[].permissions` of that user has NO `APP_VIEW/APP_EDIT/APP_PUBLISH/APP_USE`; GET 200; save 200; publish 403 |

Why project scopes exist: `PermissionMatrix.workspaceRoles` gives a workspace `VIEWER`, `PUBLISHER` and (without a project role) `EDITOR` no Studio permission at all, so before this change such a person was refused by the portal although every project API answered them.

Mutation checks (each rule broken on purpose): grant `APP_PUBLISH` in every scope -> 5 tests fail; remove the `catch` of stale memberships -> test K fails. Dropping the SQL `active` / workspace-join predicates changes nothing observable, because `AccessService.forProject()` refuses those memberships itself: the SQL is only a pre-filter, the authority is `forProject()`.

## Limits

- One `forProject()` per active project membership on every `/auth/me`: fine for a person with a handful of projects; a user with hundreds should get a paginated or lazy endpoint (not needed for V1).
- Only `ApiException` (the authorization refusals) is turned into "omit this scope"; a database or other infrastructure failure still fails the request with 5xx.
- `role` is for display only. The canonical codes are the contract; the project payload (`GET /projects/{id}`) still carries storage names (`PROJECT_READ` for `APP_VIEW`), `projectScopes[].permissions` is already canonical.

## C5 handoff (`agent/c5-web @ a3f9a4e`)

Studio admission is now: ALLOW if some `workspaces[].permissions` holds `APP_VIEW` **or** some `projectScopes[].permissions` holds `APP_VIEW`. Where it is decided today: `packages/permissions/src/index.ts` (the `studio.build` rule, which currently reads workspace permissions only and explicitly refuses a project-only person) and `packages/permissions/src/canonical.ts` (`canViewStudioIn`). Add an optional `projectScopes` to the `/auth/me` type (absent = older backend: it cannot say no, same rule as `permissions`). Never branch on `role` (`EDITOR` / `VIEWER` / `PUBLISHER`); never merge a scope into the workspace or global permission set; when a project opens, keep using that project's own resolved permissions (an `APP_VIEW`-only person opens read-only).
