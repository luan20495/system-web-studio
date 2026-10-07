# C1 — H-C1-04 project-scoped Studio admission contract

Baseline: `integration/v2 @ ae0432f`.

## Change

`GET /api/v1/auth/me` adds:

```json
{
  "projectScopes": [
    {
      "projectId": "<uuid>",
      "workspaceId": "<uuid>",
      "role": "VIEWER|EDITOR|PUBLISHER|OWNER",
      "permissions": ["APP_VIEW", "APP_USE"]
    }
  ]
}
```

`role` is informational only. Portal admission and UI gating MUST use canonical `permissions`, never role strings.

Each row is scoped to exactly one active project membership. Project permissions are never unioned into top-level `permissions` or `workspaces[].permissions`.

For an archived project, the scope is reduced to the permissions the backend itself keeps for archived projects (currently `APP_VIEW` when the role grants read access).

## Studio admission

C5 may admit the user to Studio when either:

- a workspace scope already grants `APP_VIEW`, or
- at least one `projectScopes[].permissions` contains `APP_VIEW`.

This is display/routing information only. Every project API continues to call `AccessService.forProject` and remains the authorization authority.

## Invariants

- VIEWER: `APP_VIEW + APP_USE`; no edit.
- EDITOR: canonical project-role permissions including `APP_VIEW + APP_EDIT`; no `APP_PUBLISH`.
- PUBLISHER: `APP_VIEW + APP_USE + APP_PUBLISH`; no edit/mutation.
- no project membership: no project scope.
- TENANT_ADMIN alone: no project scope.
- SYSTEM_ADMIN alone: no project scope/business permission.
- foreign project: never appears.
- multiple project memberships: one independent row per project; permissions are not combined across rows.
