# H-C1-04 — project-scoped Studio access

Base: `integration/v2 @ 62ce9697cd56`.

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
