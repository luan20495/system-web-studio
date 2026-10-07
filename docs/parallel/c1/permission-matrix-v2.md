# Permission matrix v2 (C1) — enforced by `access/Permission.kt`

Storage enum `Permission`; canonical codes (`PermissionCodes.CANONICAL`) are the only strings that cross a module boundary. Default deny.

| Canonical code | Storage constant | WORKSPACE_ADMIN | project OWNER | project EDITOR | project PUBLISHER | project VIEWER | TENANT_ADMIN | SYSTEM_ADMIN (flag OFF, non-member) |
|---|---|---|---|---|---|---|---|---|
| APP_VIEW | PROJECT_READ | ✔ | ✔ | ✔ | ✔ | ✔ | – | – |
| APP_USE | APP_USE | ✔ | ✔ | ✔ | ✔ | ✔ | – | – |
| APP_EDIT | PROJECT_EDIT (+PROJECT_SETTINGS) | ✔ | ✔ | ✔ | – | – | – | – |
| APP_PUBLISH | PROJECT_PUBLISH | ✔ | ✔ | – | ✔ | – | – | – |
| APP_SHARE (temporary) | PROJECT_MEMBERS | ✔ | ✔ | – | – | – | – | – |
| DATA_SOURCE_VIEW | DATA_SOURCE_VIEW | ✔ | ✔ | ✔ | – | – | – | – |
| QUERY_EXECUTE | QUERY_EXECUTE | ✔ | ✔ | ✔ | – | – | – | – |
| ACTION_EXECUTE | ACTION_EXECUTE | ✔ | ✔ | ✔ | – | – | – | – |
| DATA_SOURCE_MANAGE | DATA_SOURCE_MANAGE | ✔ | – | – | – | – | – | – |
| DATA_MUTATE | DATA_MUTATE | ✔ | – | – | – | – | – | – |
| WORKFLOW_EXECUTE | WORKFLOW_EXECUTE | ✔ | – | – | – | – | – | – |
| WORKFLOW_MANAGE | WORKFLOW_MANAGE | ✔ | – | – | – | – | – | – |
| TENANT_MANAGE / TENANT_MEMBERS | same | – | – | – | – | – | ✔ (own tenant) | ✔ (every tenant) |
| MEMBER_MANAGE, PROJECT_CREATE | same (no canonical code) | ✔ | – | – | – | – | – | ✔ (platform duty; PROJECT_CREATE only yields the 409 ADMIN_NOT_MEMBER) |
| AUDIT_READ, PROJECT_DELETE, REGISTRY_WRITE | same | AUDIT_READ ✔, PROJECT_DELETE ✔ (via projectAll) | PROJECT_DELETE ✔ | – | – | – | – | – |

Notes: a workspace EDITOR/PUBLISHER/VIEWER holds none of the codes above through the workspace role (project role only; EDITOR may `PROJECT_CREATE`). An ARCHIVED app keeps `APP_VIEW` and `AUDIT_READ` only (so `APP_USE` is lost). VIEWER does not hold `QUERY_EXECUTE` (the "published apps only" rule needs published state — T15). With `app.tenancy.system-admin-business-access=true` SYSTEM_ADMIN holds every constant. `mode=TEST` in `AccessPort` additionally needs `APP_EDIT`.
Operation → permission for the Data Platform (`GatewayAuthorizer.REQUIRED`): DATASOURCE_READ→DATA_SOURCE_VIEW; DATASOURCE_MANAGE, SCHEMA_DISCOVER, CACHE_REFRESH, SYNC_MANAGE, WEBHOOK_MANAGE→DATA_SOURCE_MANAGE; QUERY_EXECUTE, EVENTS_SUBSCRIBE→QUERY_EXECUTE; MUTATION_EXECUTE→DATA_MUTATE; SCHEMA_SAMPLE→DATA_SOURCE_MANAGE + QUERY_EXECUTE.

## Contract alignment round (final, before C0 Gradle)
- **Vocabulary** = exactly `docs/contracts/v2/tenant-permission.md` §5, including `TENANT_MANAGE` / `TENANT_MEMBERS` (§4 and §5 last row: "exists | C1"). They stay in `PermissionCodes.CANONICAL`, in the role matrix and in `/auth/me`. (The request to remove them was checked against the frozen file at `c59604b`; C0/LuanNTH chose to keep the contract as written.)
- **Tenant admin authorisation** = `tenantRole == TENANT_ADMIN` from an **active** `tenant_members` row of that tenant (the permission pair is derived from the role in `PermissionMatrix.tenantRoles`, there is no other source) + resource scope (its own tenant; no implicit workspace/app/data access). Tests: `TenantAdminViaRoleTests`, `TenantAccessTests`.
- **`/auth/me` exposes canonical codes only** (`PermissionCodes.canonicalCodesOf`): legacy storage constants that have no canonical code (`MEMBER_MANAGE`, `PROJECT_CREATE`, `AUDIT_READ`, `PROJECT_SETTINGS`, `PROJECT_DELETE`, `REGISTRY_WRITE`) are enforced server-side but never listed. Portals gate on `role`, `tenantRole`, `platformScope`, `businessAccess` and the canonical list.
- **Actor policy — TEMPORARY V2 POLICY** (`access/adapters/ActorPolicy`): only `ActorKind.USER` with a user id is authorised by `GatewayAuthorizer`, `AccessPort` and `PrincipalResolver`; `SYSTEM`, `SERVICE`, `APP_TOKEN` fail closed everywhere (the frozen contract defines no runtime permission for them). It is not a permanent platform rule; lifting it needs a contract change approved by C0. `TenantGate` takes a tenant id, not an actor.
- **SYSTEM_ADMIN flag** is read in exactly two places, both through `app.tenancy.system-admin-business-access` (default false): `AccessService` (every adapter goes through it) and `MeTenancyService` (display only). No adapter reads `users.system_admin` itself.
