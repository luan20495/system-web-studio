# ADR 0016: Identity — RP-initiated logout, SAML via brokering, SCIM 2.0, MFA at the IdP
Status: accepted and implemented (2026-10-03). Stage I.

## RP-initiated logout
On OIDC login the ID token is kept in the server-side (Redis) session. `POST /api/v1/auth/logout` ends the Studio session and, for SSO
users, returns the IdP `end_session_endpoint` URL (from discovery) with `id_token_hint`, `post_logout_redirect_uri`
(`OIDC_POST_LOGOUT_REDIRECT_URI`, default `<studio>/login`) and `client_id`; the UI navigates there. Verified end to end against Keycloak
(`e2e/sso-flow.mjs`): after logout the next SSO click asks for the password again.

## SAML
Native SAML SP support in Spring Security 7 needs OpenSAML 5, which is published only in the Shibboleth repository, not Maven Central.
Adding a new artifact repository is a supply-chain decision left to the owner (see readiness report). Implemented instead: SAML IdPs are
federated by the OIDC provider (Keycloak identity brokering); with `SAML_ENABLED=true` and `SAML_IDP_HINT=<alias>` the login page offers a
SAML button that adds `kc_idp_hint` to the OIDC authorization request. The app remains an OIDC client; identity ≠ authorization still holds.
Verified with a local "corp" realm acting as SAML IdP. Default: off.

## SCIM 2.0
`/scim/v2` (Users, Groups, ServiceProviderConfig, ResourceTypes, Schemas) in its own stateless chain; off unless `SCIM_ENABLED=true` and a
`SCIM_TOKEN` ≥ 32 chars (compared as SHA-256 in constant time; per-IP rate limit). SCIM manages only accounts it created (`auth_source=SCIM`):
local accounts and the bootstrap admin are invisible to it. No attribute (roles, entitlements, groups) maps to system admin. Group membership
becomes workspace membership only through an explicit admin mapping (group → workspace + workspace role); SCIM-granted memberships are
marked `source=SCIM` and removed by SCIM, manual memberships are never touched. Deprovisioning disables the account and revokes sessions.
An OIDC login whose subject equals a SCIM user's `externalId` signs in to that account. Supported filters: `attr eq "value"`.

## MFA
MFA is enforced by the identity provider; the Studio shows "MFA managed by Identity Provider". Local password accounts are for emergency
administration and demos.
