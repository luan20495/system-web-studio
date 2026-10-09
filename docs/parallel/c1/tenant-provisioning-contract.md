# C1 — tenant-scoped provisioning: API contract for C5 (Admin Portal / Studio)

Owner C1 · branch `fix/c1-portal-authz-blockers` · baseline `integration/v2 @ 1a9995c` · 2026-10-07. All routes are session + CSRF (the Studio chain). **No route takes or returns a password**: accounts are provisioned by invitation (one-time activation link), as the existing flow already works.

## 1. The business flow (all steps are covered by `UserProvisioningHierarchyTests`, PostgreSQL)

```text
SYSTEM_ADMIN  POST /api/v1/admin/tenants                         -> tenant
              POST /api/v1/admin/tenants/{t}/workspaces          -> workspace OF THAT TENANT (tenant_id explicit)
              POST /api/v1/admin/tenants/{t}/users               -> first Tenant Admin (+ optional workspace + WORKSPACE_ADMIN) + activation link
Tenant Admin  opens the link  POST /api/v1/auth/activation/complete {token, password}   -> signs in on the Admin Portal
              POST /api/v1/admin/tenants/{t}/users               -> BRAND-NEW user in its own tenant (+ optional workspace + role) + activation link
New user      opens the link, chooses a password, signs in on Studio; /auth/me lists the workspace
Workspace Admin  /api/v1/workspaces/{w}/members                  -> list / add an eligible tenant person / change role / remove
```

## 2. Endpoints

```text
CREATE USER (brand-new account, invitation)
METHOD: POST
PATH:   /api/v1/admin/tenants/{tenantId}/users            -> 201
REQUEST:
  {
    "username": "user01",              // required, 3-40 chars: a-z 0-9 . _ - (lower-cased by the server)
    "displayName": "User 01",          // required, max 160
    "email": "user01@example.com",     // optional
    "tenantRole": "MEMBER",            // optional, default MEMBER; only MEMBER | TENANT_ADMIN
    "workspaceId": "<uuid>",           // optional, must be a workspace OF {tenantId}; together with workspaceRole or both absent
    "workspaceRole": "VIEWER"          // optional: WORKSPACE_ADMIN | EDITOR | PUBLISHER | VIEWER
  }
RESPONSE (the existing ActivationLink): { "userId", "username", "displayName", "purpose": "ACTIVATION", "token", "expiresAt" }
REQUIRED PERMISSION: TENANT_MEMBERS on {tenantId}  (TENANT_ADMIN of that tenant, or SYSTEM_ADMIN). WORKSPACE_ADMIN alone -> 403.
RULES: enabled=true, system_admin=false, activated_at=null (cannot sign in until activated); no password is accepted or returned;
       unknown fields (e.g. "systemAdmin") never take effect; the token is returned ONLY here, never audited or logged.

INVITE / ACTIVATION
 - the link is the response above: the portal builds the activation page URL from `token` (C5 owns the page; it only calls the three `/api/v1/auth/activation/*` and `/admin/users/{id}/activation-link` routes below)
 - POST /api/v1/auth/activation/inspect  {token}               -> {username, displayName, purpose}     (anonymous, rate limited per IP)
 - POST /api/v1/auth/activation/complete {token, password}     -> {status:"ok"}  410 LINK_INVALID once used / expired
 - new link for an existing account (reset / re-invite): POST /api/v1/admin/users/{id}/activation-link   (SYSTEM_ADMIN only, unchanged)
 - disable / enable: PATCH /api/v1/admin/users/{id}/status {"enabled": false|true}                       (SYSTEM_ADMIN only, unchanged)

CREATE WORKSPACE (of a tenant)
METHOD: POST
PATH:   /api/v1/admin/tenants/{tenantId}/workspaces       -> 201
REQUEST: { "name": "Workspace A" }                         (required, max 160)
RESPONSE: { "id", "name", "slug", "tenantId" }
REQUIRED PERMISSION: TENANT_MANAGE on {tenantId} (TENANT_ADMIN of it, or SYSTEM_ADMIN). Audit WORKSPACE_CREATED.
NOTE: the old POST /api/v1/admin/workspaces (no tenant) is unchanged and still puts the workspace in the DEFAULT tenant; the portals should use this one.

ADD TENANT MEMBER / ASSIGN TENANT ROLE (an existing person related to the tenant)
METHOD: PUT
PATH:   /api/v1/admin/tenants/{tenantId}/members/{userId}      REQUEST: { "role": "MEMBER" | "TENANT_ADMIN" }   -> 200 {tenantId,userId,role,active,username,displayName,email}
PERMISSION: TENANT_MEMBERS. The target must be eligible (see candidates), else 404 USER_NOT_FOUND (a guessed UUID is indistinguishable from nobody).
REMOVE:  DELETE /api/v1/admin/tenants/{tenantId}/members/{userId} -> 204        LIST: GET /api/v1/admin/tenants/{tenantId}/members -> [{tenantId,userId,role,active,username,displayName,email}]

ADD WORKSPACE MEMBER
METHOD: POST
PATH:   /api/v1/workspaces/{workspaceId}/members            REQUEST: { "username"| "email": "...", "role": "WORKSPACE_ADMIN|EDITOR|PUBLISHER|VIEWER" }  -> 201 MemberDto
PERMISSION: MEMBER_MANAGE on the workspace (WORKSPACE_ADMIN). The person must be an ACTIVE member of the workspace's tenant, else 404 USER_NOT_FOUND.
OTHER:  GET .../members (list), PATCH .../members/{userId} {role} (change), DELETE .../members/{userId} (remove)  -- same permission.

ASSIGN WORKSPACE ROLE
METHOD: PATCH
PATH:   /api/v1/workspaces/{workspaceId}/members/{userId}   REQUEST: { "role": "..." }   -> 200 MemberDto     (not your own role)

LIST / CANDIDATE USER
METHOD: GET
PATH:   /api/v1/admin/tenants/{tenantId}/member-candidates
QUERY PARAMS: q (optional; at least 2 characters, else 400 QUERY_TOO_SHORT; matched, case-insensitive and literally, against username, display name, e-mail)
RESPONSE: [{ "userId", "username", "displayName", "email" }]   ordered by username, at most 50
PERMISSION: TENANT_MEMBERS
SCOPE RULE: never a global directory. A person appears only if the account is enabled, activated and not a platform admin, is NOT already an active member of this tenant,
            and has a relation to THIS tenant: an active workspace membership in one of its workspaces, or an inactive tenant membership of it.
            People known only to another tenant never appear; adding one by a guessed UUID is 404.
```

## 3. Who may do what (frozen D-C1-13)

| Actor | Holds | Can | Cannot |
|---|---|---|---|
| SYSTEM_ADMIN | `TENANT_MANAGE`, `TENANT_MEMBERS` (platform scope) | create tenants, workspaces of a tenant, accounts (first Tenant Admin / Workspace Admin), tenant roles; disable / enable; activation links | read business data; hold `MEMBER_MANAGE`, `PROJECT_CREATE` or any `APP_*` in a workspace it is not a member of; grant itself anything |
| TENANT_ADMIN | `TENANT_MANAGE`, `TENANT_MEMBERS` of its tenant (and not `MEMBER_MANAGE`) | create accounts and workspaces in its tenant, assign tenant roles, list candidates | touch another tenant; grant SYSTEM_ADMIN; manage workspace members unless it is itself a WORKSPACE_ADMIN there |
| WORKSPACE_ADMIN | `MEMBER_MANAGE` (+ the workspace permissions) | list / add (eligible person) / change role / remove members of its workspace | create accounts; call the tenant APIs; promote itself; touch another workspace or tenant |

`/api/v1/auth/me`: `workspaces[].permissions` of a WORKSPACE_ADMIN contains `MEMBER_MANAGE`; `permissions` (tenant level) of a TENANT_ADMIN is exactly the eight codes `TENANT_MANAGE`, `TENANT_MEMBERS`, `ORG_STRUCTURE_VIEW`, `ORG_STRUCTURE_MANAGE`, `EMPLOYEE_VIEW`, `EMPLOYEE_MANAGE`, `POSITION_GRADE_VIEW`, `POSITION_GRADE_MANAGE` (a tenant MEMBER: none); a SYSTEM_ADMIN sees every workspace in the list but its permissions there are exactly `TENANT_MANAGE` + `TENANT_MEMBERS` (no business, no member, no `PROJECT_CREATE`). The legacy god mode exists only with `app.tenancy.system-admin-business-access=true`.

## 4. Error contract (exact codes of the implementation)

```text
400  INVALID_USERNAME · INVALID_EMAIL · VALIDATION_FAILED (displayName missing; workspaceId without workspaceRole or the reverse; body shape)
     TENANT_ROLE_INVALID (anything but MEMBER | TENANT_ADMIN, incl. SYSTEM_ADMIN) · INVALID_ROLE (workspace / project role) · QUERY_TOO_SHORT
401  AUTHENTICATION_REQUIRED
403  FORBIDDEN (missing same-scope permission, e.g. WORKSPACE_ADMIN calling a tenant route) · SELF_GRANT_FORBIDDEN (own membership / role) · ADMIN_REQUIRED (platform-only route)
404  TENANT_NOT_FOUND (unknown OR foreign tenant) · WORKSPACE_NOT_FOUND (unknown OR other tenant's workspace) · USER_NOT_FOUND (unknown OR not related to this tenant)
     MEMBER_NOT_FOUND · TENANT_MEMBER_NOT_FOUND
409  USERNAME_TAKEN · EMAIL_TAKEN · LAST_TENANT_ADMIN · LAST_ADMIN (last WORKSPACE_ADMIN) · LAST_OWNER (project) · ALREADY_MEMBER · CANNOT_CHANGE_SELF (only when a system admin changes its own SYSTEM_ADMIN flag)
410  LINK_INVALID (activation link used / expired)
422  USER_DISABLED · NOT_WORKSPACE_MEMBER (adding to a project someone who is not in its workspace)
429  rate limited (activation per IP, login failures)
```

## 5. Audit (append-only)

`USER_CREATED`, `ACTIVATION_LINK_CREATED`, `TENANT_MEMBER_SET`, `ADD_MEMBER`, `WORKSPACE_CREATED`; role change `CHANGE_PERMISSION`, removal `REMOVE_MEMBER` / `TENANT_MEMBER_REMOVED` (existing events). Payloads carry ids, roles, the tenant and the link's expiry only; never a token, password or hash (checked by test O).

## 6. Limits C5 must know

1. **Adding an EXISTING person to a workspace is a WORKSPACE_ADMIN action** (`POST /workspaces/{w}/members`). A TENANT_ADMIN or SYSTEM_ADMIN assigns a workspace at account creation (`workspaceId` + `workspaceRole`) and can create a WORKSPACE_ADMIN to delegate; there is deliberately no tenant-level "add existing user to workspace" route (it would give the tenant role implicit workspace authority). If the product needs it, that is a new contract decision.
2. The portal must show the activation link to the administrator once (or deliver it): the token is not stored in clear and cannot be shown again; a new link is `POST /admin/users/{id}/activation-link` (SYSTEM_ADMIN) or a re-provision.
3. Usernames and e-mails are global: a duplicate is 409 even if the other account belongs to another tenant.
4. Provisioning does not check the tenant's lifecycle status beyond `DELETED` (unknown); status gating of provisioning is not part of this change.
