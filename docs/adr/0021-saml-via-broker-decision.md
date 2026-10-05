# ADR 0021: SAML — keep the IdP broker, do not add native SAML (decision)
Status: accepted (2026-10-05). Supersedes the "native SAML" open item of ADR 0016.

## Question
Should the platform add a native SAML 2.0 service provider (Spring Security SAML2 + OpenSAML 5)?

## Facts
* The application is an OIDC client; identity ≠ authorization (accounts get no access until an admin or SCIM mapping grants it).
* A company IdP (Entra ID, Okta, Keycloak, ADFS) can broker SAML identities into OIDC; with `SAML_ENABLED=true` and `SAML_IDP_HINT=<alias>` the
  login page offers a SAML button that adds `kc_idp_hint`. Verified end to end against a local SAML realm (`e2e/sso-flow.mjs`, 11/11).
* Native SAML needs OpenSAML 5, published on the Shibboleth repository (not Maven Central). That adds a second artifact repository to the supply
  chain, a second login/session path (SAML assertions, signature/clock-skew/replay handling, metadata rotation) and a larger attack surface
  (XML signature wrapping history) for a protocol the IdP already terminates.
* No requirement has been stated that a broker cannot meet. The only unproven part is a real company tenant (BLOCKED_EXTERNAL_INPUT).

## Decision
Keep **OIDC as the application integration** and **SAML through the IdP broker**. Do not add native SAML or the Shibboleth repository.
Revisit only if (a) a customer IdP can speak SAML only and cannot be brokered by an OIDC-capable IdP the company runs, or (b) a compliance
requirement mandates SP-side SAML. Status in the matrix stays **PARTIAL** (real and tested, but not against a company tenant) — it is not
upgraded by adding a dependency.

## Consequences
The company's IdP team must either run a broker (Keycloak, Entra External ID, Okta OIDC app) or give the platform an OIDC application. Group →
workspace role mapping is SCIM (ADR 0016), independent of how the user signed in.
