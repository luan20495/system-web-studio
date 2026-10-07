# Web security configuration — flags, webhook route, CORS, OIDC (C0)

Status 2026-10-06 on `integration/v2`: **written, not compiled, not run** (no Gradle in the authoring environment). Everything below that is code is covered by tests that the Mac gate runs (`MAC_INTEGRATION_CHECKLIST.md`). Nothing here turns a V2 feature on.

## 1. Feature switches — all OFF (`backend/src/main/resources/application.yml`, `app:` block)
| Property (environment override) | Default | Owner | Read by | May be turned on only when |
|---|---|---|---|---|
| `app.tenancy.system-admin-business-access` (`TENANCY_SYSTEM_ADMIN_BUSINESS_ACCESS`) | **false** | C1 | `AccessService`, `MeTenancyService` (C1, **after import**) | never as a shortcut: `true` restores the legacy "SYSTEM_ADMIN sees every tenant's apps/data" (R-03). Declared now so the C1 import changes behaviour exactly as D-C1-11 says |
| `app.ai-planner.enabled` (`AI_PLANNER_ENABLED`) | **false** | C2 | `ai/planner/PlannerApi` (`@ConditionalOnProperty`) | C1 canonical permissions + C3 `AiDataCatalog` (via `AiDataCatalogAdapter`) + canonical AppDefinition are imported **and** wired, and the PlanGuard tests pass on the Mac |
| `app.tenant-ai.enabled` (`TENANT_AI_ENABLED`) | **false** | C2 | `ai/tenant/TenantAiApi` | the outbound call re-resolves and checks the address with canonical `PublicAddress` **at call time** (DNS rebinding), and the `tenant_ai_providers` migration is numbered and applied (`MIGRATION_LEDGER.md` item 1) |
| `app.publish-configs.enabled` (`PUBLISH_CONFIGS_ENABLED`) | **false** | C2 | publish-config API | V27 `publish_configs` is applied and verified (`MAC_INTEGRATION_CHECKLIST` step 9) |
| `app.data-platform.enabled` (`DATA_PLATFORM_ENABLED`) | **false** | C3 (name reserved by C0) | `wiring/**` only | data foundation migration applied, adapters wired, security-isolation tests green |
| `app.data-platform.webhooks-enabled` (`DATA_PLATFORM_WEBHOOKS_ENABLED`) | **false** | C3 (reserved) | `wiring/DataWebhookController` | as above + signature/replay tests green. The route is always declared in security (§2); this switch decides whether any handler exists |
| `app.workflow.enabled` (`WORKFLOW_ENABLED`) | **false** | C4 (reserved) | `wiring/**` only | action/workflow persistence applied, RabbitMQ adapter + scheduler wired |
Turning one on is a recorded decision (`DECISIONS.md`), not a deploy-time tweak. Today only the C1/C2 flags exist in code (on their branches); the C0-reserved names have no reader yet. Until C1 is imported, the base behaves as before whatever `system-admin-business-access` says.

## 2. Data webhook ingest — exact security
Route: **`POST /api/v1/webhooks/data/{endpointId}`** (`WebhookRoutes.PATH` in C3; `data-runtime.md` §6). One path segment; no wildcard.
- `SecurityConfiguration.DATA_WEBHOOK_INGEST` = `PathPatternRequestMatcher.withDefaults().matcher(POST, "/api/v1/webhooks/data/{endpointId}")`, used in **two** places of the main chain only:
  `.requestMatchers(DATA_WEBHOOK_INGEST).permitAll()` and `.csrf { …ignoringRequestMatchers(DATA_WEBHOOK_INGEST) }`.
- Everything else stays as it was: `/api/v1/webhooks/**` is **not** opened; other methods on the same path, deeper paths (`…/data/a/b`), and `/api/v1/webhooks/other` stay authenticated + CSRF-protected (`WebhookSecurityTests`).
- No new filter chain, no CORS change (a server-to-server POST has no `Origin`; a browser POST from an unlisted origin is refused by the existing CORS rule), session still `IF_REQUIRED` but the route never needs one.
- **Authentication of the caller is NOT done in security config.** It is C3's `WebhookIngress`: HMAC signature over the raw bytes (v1 `"<ts>.<body>"`, v2 includes the delivery id), timestamp tolerance ±300 s, replay guard keyed on the signature, 1 MiB body cap read without buffering past the limit, per-peer then per-endpoint rate limit, uniform answers (`{"status":"<code>"}`), tenant resolved from the endpoint id only. The controller (`wiring/DataWebhookController`, skeleton) adds nothing and reads nothing else from the request.
- Until C3 is imported there is **no controller**: the open route answers 404 (it reaches the dispatcher). That is the intended safe state, and what `WebhookSecurityTests` expects.
- Operations: put a request-size and rate limit in front (nginx `limit_req` for this location); do not log bodies or the signature header.

## 3. CORS — exactly the three frontend origins, never a wildcard with credentials
- The three deployments (ADR 0022) reach the API through **their own same-origin `/api` proxy** (Next rewrites), so CORS is a defence in depth, not the main path. It is still configured exactly, because credentials are allowed.
- `app.web.origins.{platform,admin,studio}` (`WEB_ORIGIN_PLATFORM|ADMIN|STUDIO`) declare the three origins (dev defaults `http://localhost:3001|3002|3003`, the ports C5's apps use). `wiring/WebOrigins` validates them (exact, distinct) and produces the CORS list and the OIDC URI sets (§4).
- **Live CORS list today** = `app.cors.allowed-origins` (`CORS_ALLOWED_ORIGINS`), default still the legacy single UI (`:3000`) because `AuthSecurityTests` and the legacy UI depend on it. **At the C5 import** (checklist step 17) the default becomes `${app.web.origins.platform},${app.web.origins.admin},${app.web.origins.studio}`, `AuthSecurityTests` is updated in the same commit, and `scripts/_env.sh` / `.env.example` get the three ports.
- Enforced now (`SecurityConfiguration.requireExactOrigins`, test `WebhookSecurityTests`): every allowed origin must be `scheme://host[:port]` — no `*`, no `null`, no path, no trailing slash. IPv6 literal origins are refused (fail closed). Production additionally requires https and refuses localhost (`ProductionConfigValidator`, unchanged).
- Production example (three hosts): `CORS_ALLOWED_ORIGINS=https://platform.example.com,https://admin.example.com,https://studio.example.com`. Allowed methods/headers are unchanged (`Content-Type, X-XSRF-TOKEN, X-Request-Id, Idempotency-Key`).

## 4. OIDC — redirect / callback matrix
Spring's callback is `/login/oauth2/code/oidc` (registration id `oidc`). Each portal serves `/oauth2/*` and `/login/oauth2/*` by rewrite to the API (the root `next.config.ts` does this; **C5's three `apps/*/next.config.ts` must do the same** — verify at the C5 import).

| Portal | Dev origin | Prod example | Redirect URI to register at the IdP | Post-logout URI to register | Who normally signs in |
|---|---|---|---|---|---|
| platform | `http://localhost:3001` | `https://platform.example.com` | `<origin>/login/oauth2/code/oidc` | `<origin>/login` | SYSTEM_ADMIN |
| admin | `http://localhost:3002` | `https://admin.example.com` | `<origin>/login/oauth2/code/oidc` | `<origin>/login` | TENANT_ADMIN, scoped managers |
| studio | `http://localhost:3003` | `https://studio.example.com` | `<origin>/login/oauth2/code/oidc` | `<origin>/login` | tenant members |
`OIDC_SUCCESS_URL` stays `/` (relative: the user lands on the portal they came from). IdP client: **web origins = the same three exact origins; no `*`.** `WebOrigins.oidcRedirectUris()` / `oidcPostLogoutUris()` produce these lists.

**Known limitation (open, B-C0-WEB-01).** The backend builds one client registration with one `redirect-uri` (`OIDC_REDIRECT_URI`, or `{baseUrl}/…` when empty, and `{baseUrl}` is the host the API *sees* — `127.0.0.1:8080` behind the rewrite; `ClientIpFilter` trusts forwarded scheme only, not host). Consequences until fixed:
1. Behind the portals the redirect URI **must be set explicitly**, so only the portal whose origin is in `OIDC_REDIRECT_URI` can complete an OIDC login.
2. Session and CSRF cookies are per origin; signing in on one portal does not sign in the others.
3. **Interim rule:** local login works on all three portals; OIDC is enabled for **one** portal (default: studio) and the others are told so; an SSO-only SYSTEM_ADMIN/TENANT_ADMIN cannot use the platform/admin portal until fixed. Do not widen `OIDC_REDIRECT_URI`, do not add a wildcard, do not trust `X-Forwarded-Host` blindly.
4. **Fix (C0, wiring, before SSO is declared supported for all portals):** an allow-listed redirect-URI resolver in `identity/oidc` that picks the redirect URI from `WebOrigins` by the portal origin of the login request (exact match, else the default), with the same list registered at the IdP; tests: unknown origin → default, never reflected. Not implemented now (identity/oidc is not touched in this step).
5. The SAML hint (`?idp=saml`) and `kc_idp_hint` behaviour are unchanged.

## 5. Mac checks for this document
`./gradlew test --tests '*WebhookSecurityTests' --tests '*PublicAddressTests' --tests '*WebOriginsTests' --tests '*AuthSecurityTests'` (checklist step 1) · a manual `curl -i -X POST localhost:8080/api/v1/webhooks/data/x` (expect 404, not 401/403) · same with `GET` (expect 401) · OPTIONS from an unlisted origin (expect 403) · IdP console lists exactly three redirect URIs and three web origins.
