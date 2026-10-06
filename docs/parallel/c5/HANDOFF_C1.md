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

