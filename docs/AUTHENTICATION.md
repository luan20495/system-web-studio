# Authentication, SSO and MFA strategy

## Modes
| Mode | When | Switch |
| --- | --- | --- |
| Local password (Argon2id, Redis session) | development, tests, internal fallback | `LOCAL_LOGIN_ENABLED=true` (default) |
| Generic OIDC / SSO | production | `OIDC_ENABLED=true` + issuer, client id, client secret |
| OIDC only | recommended production setting | `OIDC_ENABLED=true` and `LOCAL_LOGIN_ENABLED=false` |

`GET /api/v1/auth/config` tells the UI which options exist (`localLogin`, `oidc`), so the login screen follows the deployment.

## OIDC configuration (no vendor is hard-coded)
```
OIDC_ENABLED=true
OIDC_ISSUER_URI=https://idp.example.com/realms/studio      # must publish /.well-known/openid-configuration; https required in prod
OIDC_CLIENT_ID=studio-web
OIDC_CLIENT_SECRET=...                                      # from your secret store, never from git
OIDC_SCOPES=openid,profile,email
OIDC_REDIRECT_URI=https://studio.example.com/login/oauth2/code/oidc   # set explicitly behind a proxy
OIDC_SUCCESS_URL=/
OIDC_AUTO_PROVISION=true            # unknown identities get an account with NO access
OIDC_LINK_BY_VERIFIED_EMAIL=false   # keep false unless you fully trust the IdP's email verification
```
Register the redirect URI `<public origin>/login/oauth2/code/oidc` at the provider. The reverse proxy must forward `/oauth2/*` and `/login/oauth2/*` to the API (the example in `infra/nginx` does; `next.config.ts` does the same for the dev proxy). The authorization-code flow is handled by Spring Security's OAuth2 client (the client authenticates with its secret; the app does not add PKCE for this confidential client); the registration is resolved lazily, so an IdP outage at boot does not stop the API.

## Identity is not authorization
* The stable identity is `(issuer, subject)` stored in `external_identities`. Email is informational.
* First login of an unknown identity creates a user (`auth_source=OIDC`, random unusable password hash, email stored only when the IdP marks it verified and no other account owns it) **with no workspace or project membership**. It can see nothing until a workspace admin adds it (members API/UI, by username or email).
* An existing local account is never taken over because the email matches (`OIDC_LINK_BY_VERIFIED_EMAIL=false`, tested). If you enable linking, only IdP-verified emails link, and the link is audited (`LINK_IDENTITY`).
* All permissions come from the internal RBAC (`PermissionMatrix`), evaluated per request. Disabling the user in the database ends access on the next request.
* Every provisioning and login is audited (`PROVISION_USER`, `LOGIN_SUCCESS` with `method=OIDC`, `LOGIN_FAILURE`).
* Logout ends the Studio session only; it does not call the IdP's end-session endpoint (RP-initiated logout is not implemented).

## Verified locally
`./scripts/sso-up.sh` starts a Keycloak (profile `sso`, generated realm, random secrets), `node e2e/sso-flow.mjs` runs a real browser round trip: login, provisioning without access, no linking to a local account with the same verified email, HttpOnly session cookie, no password login for SSO accounts, access only after an admin grants it, runtime disable, unverified email not stored, forged callback rejected. Result: 9/9 on 2026-10-01. Other providers were **not** tested.

## MFA strategy
The application does not implement TOTP/WebAuthn itself.
* **Production:** MFA is enforced by the identity provider (Conditional Access / required actions / authentication policies). The application consumes the authenticated identity and applies its own RBAC. To make this enforceable, run OIDC-only (`LOCAL_LOGIN_ENABLED=false`) so there is no password path around the IdP.
* **Local password mode** is single-factor by design: it is for development and as a break-glass option for a small internal deployment. If you keep it enabled in production, accept the risk explicitly, use long unique passwords, keep the login throttling on, and restrict who has such accounts (they are created by an administrator; there is no self-service sign-up).
* Not implemented: step-up authentication inside the app, `acr`/`amr` claim enforcement. If your compliance regime requires proof of MFA per session, add a check of the `amr`/`acr` claim in `OidcLoginSuccessHandler` for your provider.

## Local password mode details
Argon2id (64 MiB, 3 iterations); at most `LOGIN_MAX_CONCURRENT_HASHES` (default 4) verifications run at once, others wait up to `LOGIN_QUEUE_WAIT_MS` and then get `503 LOGIN_BUSY` + `Retry-After` (found by load test: 100 simultaneous logins previously froze the API). Failed attempts are throttled per username (5 / 15 min) and per client IP (50 / 15 min) before any hashing happens.
