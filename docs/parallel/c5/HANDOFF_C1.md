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
