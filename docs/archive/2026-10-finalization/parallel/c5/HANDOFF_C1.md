> **SUPERSEDED_BY:** `docs/QA_FINAL.md` - historical document (moved from `docs/parallel/c5/HANDOFF_C1.md`), kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# C5 → C1 handoff — C1 — auth / tenant / permission / security

From C5 (Studio/Frontend), baseline `integration/v2 @ f894cc6`. C5 changed nothing owned by C1. Nothing here was run against a live backend: evidence is the code, the BLOCKERS rows and the suite's blocker records.

## H-C1-01 (B-C0-WEB-01)

- **ID:** H-C1-01 (B-C0-WEB-01)
- **Owner:** C1 (C0 config)
- **Severity:** P2
- **Flow:** OIDC login on three portals
- **Frontend expectation:** Each portal (:3001/:3002/:3003) can complete OIDC.
- **Actual backend behavior:** Only one redirect URI and one post-logout URI are registered, so only one portal can do OIDC. Local login works on all three. Cookies are per origin.
- **Endpoint/event:** `/oauth2/authorization/*`, `/login/oauth2/code/*`
- **Request:** OIDC start from a second portal
- **Response/status:** IdP redirect-URI mismatch
- **Reproduction:** Start OIDC from a portal other than the registered one.
- **Evidence:** BLOCKERS B-C0-WEB-01; `C5_OVERLAY_MANIFEST.md` §4.
- **Impact:** OIDC E2E out of scope; real-backend suite uses local login only.
- **Suggested contract/fix:** Register one redirect + post-logout URI per portal and three web origins.
- **C5 workaround:** Suite requires `localLogin:true`, otherwise exits NOT RUN.
- **Blocked test IDs:** none of the 14 (OIDC is not a matrix flow)

## H-C1-02 (B-C5-09 / Q-1)

- **ID:** H-C1-02 (B-C5-09 / Q-1)
- **Owner:** C1
- **Severity:** P3
- **Flow:** Admin portal for tenant admins
- **Frontend expectation:** Admin stays platform-only until C1 answers Q-1.
- **Actual backend behavior:** `app.tenancy.system-admin-business-access=false`; no tenant-admin Admin surface is defined.
- **Endpoint/event:** `/api/v1/auth/me` (`platformScope`, `businessAccess`)
- **Request:** -
- **Response/status:** -
- **Reproduction:** -
- **Evidence:** DECISIONS / BLOCKERS Q-1
- **Impact:** Admin gate remains platform-only.
- **Suggested contract/fix:** Answer Q-1.
- **C5 workaround:** Platform-only gate.
- **Blocked test IDs:** none


## H-C1-03 — a workspace VIEWER cannot open Studio at all (decision needed; seen on a live backend, 2026-10-06)

- **ID:** H-C1-03
- **Owner:** C1
- **Severity:** P2 (a decision, not a defect report)
- **Flow:** E2E-04 / E2E-05 viewer part
- **Frontend expectation:** a person who is a project member with role VIEWER opens the project read-only in Studio ("Bạn chỉ có quyền xem", save and run controls disabled with a reason).
- **Actual backend behavior:** `GET /auth/me` for a workspace VIEWER returns `permissions: []` and `workspaces[].permissions: []`. The C5 portal gate (`capabilitiesOf`, "some workspace gives a non-tenant-level permission") therefore refuses Studio and the browser lands on `/auth/no-access?portal=studio`. The API itself is consistent with the project membership: `GET …/schema` → 200, `PATCH …/schema` → 403 `FORBIDDEN "Missing permission: PROJECT_EDIT"`, `GET …/data-sources` → 403.
- **Endpoint/event:** `GET /api/v1/auth/me`
- **Request:** log in as a user created with `POST /admin/users {workspaceId, role:"VIEWER"}` and added with `POST …/projects/{p}/members {role:"VIEWER"}`
- **Response/status:** 200, `permissions: []`
- **Reproduction:** `E2E_ONLY=E2E-04 npm run test:e2e:real` (evidence: `docs/parallel/c5/evidence/mac/E2E_ALL_final.log`, fact `viewerGate` and `viewerSchemaViaApi=200` in the report).
- **Impact:** the read-only viewer UX cannot be reached or tested; project members with a VIEWER role get a "no access" page.
- **Suggested contract/fix:** C1 decides: (a) a VIEWER role carries `APP_VIEW` (then the gate opens Studio read-only with no C5 change), or (b) viewers are intentionally not Studio users (then project VIEWER membership is meaningless for Studio and C5 keeps the gate; the E2E-04/05 viewer part becomes "refused", which C5 will then assert as PASS).
- **C5 workaround:** none; the gate is not loosened without a contract.
- **Blocked test IDs:** E2E-04, E2E-05 (viewer part only)

## H-C1-03 — RESOLVED 2026-10-07

C1 decided: a VIEWER holds APP_VIEW and Studio opens read-only; `/auth/no-access` is right only without APP_VIEW. C5 implemented the contract (`PERMISSION_CONTRACT.md`) and removed the `E2E_VIEWER_POLICY` switch. The decision is not delivered by the backend yet: see H-C1-04.

## H-C1-04 — CONTRACT MISMATCH: `/auth/me` does not carry project-membership permissions

- **Owner:** C1 / C0 · **Severity:** P1 (everyone except WORKSPACE_ADMIN is locked out of Studio)
- **Expected:** a VIEWER's resolved permissions include APP_VIEW, so the Studio portal opens read-only.
- **Actual (live, `integration/v2 @ 96110d3`):** `GET /auth/me` → `permissions: []` and `workspaces[].permissions: []` for a workspace VIEWER who is a project VIEWER; the same for project EDITOR and PUBLISHER members. `GET /workspaces/{w}/projects/{p}` → `permissions: ["APP_USE","PROJECT_READ"]` (VIEWER), `[…"PROJECT_EDIT"…,"PROJECT_READ",…]` (EDITOR), `["APP_USE","PROJECT_PUBLISH","PROJECT_READ"]` (PUBLISHER). `PermissionMatrix.workspaceRoles` gives workspace VIEWER / EDITOR / PUBLISHER an empty set; project grants live only in `projectRoles`.
- **Impact:** the portal gate (reads `/auth/me`) redirects the VIEWER to `/auth/no-access`; E2E-04 case A and the UI half of E2E-05 cannot pass; the project payload also still returns storage names (`PROJECT_READ`) next to canonical ones although `/auth/me` promises canonical codes only.
- **Frontend workaround:** NONE. APP_VIEW is not injected from a role name and the gate is not loosened.
- **Suggested contract/fix (C1/C0 decide):** expose a resolved field that includes project-scoped grants (e.g. per-project canonical permissions in the project list / `/auth/me`), and return canonical codes on the project payload. C5 consumes whichever field is named.
- **Evidence:** `E2E-04`/`E2E-05` evidence blocks ("CONTRACT MISMATCH"), `fixtureNotes.viewerResolved`, `docs/parallel/c5/PERMISSION_CONTRACT.md` §4.
- **Rerun:** `docs/parallel/c5/e2e-stack.sh e2e E2E-04,E2E-05` — expected PASS once the backend delivers it (no test change needed).

## H-C1-05 — no resolved capability for "create project" and "list workspace members"

`PROJECT_CREATE` and `MEMBER_MANAGE` are server-internal storage constants that `/auth/me` does not expose (`canonicalCodesOf` filters them), so the UI had hidden the create form and the workspace-member table by ROLE NAME. That is forbidden, so the role checks are removed: the form is offered and the server answers 403 (plain message); the workspace-member list is requested and a 403/404 hides the section. Request: an exposed canonical capability (or a documented rule) for these two, so the UI can disable instead of letting the person try.
