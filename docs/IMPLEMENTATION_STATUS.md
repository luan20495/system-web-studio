# Implementation status — AI Software Factory (2026-10-02)

Development Ready: **YES** · Internal Demo Ready: **YES** · Production Ready: **NO** (see "Not implemented").

## REAL / PARTIAL / MOCK / NOT IMPLEMENTED
| Area | Status | Notes |
| --- | --- | --- |
| Login gateway with portal choice (Admin / Builder) | REAL | portal is navigation intent only (ADR 0002) |
| Password login, Redis sessions, CSRF, throttling | REAL | sessions now in the *indexed* repository (revocable) |
| SSO / OIDC | REAL (Keycloak-verified) | SAML, SCIM: NOT IMPLEMENTED |
| Public sign-up | REAL, off by default | `PUBLIC_SIGNUP_ENABLED` (falls back to `SIGNUP_ENABLED`) |
| Auth screens (signing-in, no-access, no-workspace, session-expired) + return to original route | REAL | E2E-tested |
| Admin Console shell | REAL | 8 sections |
| Admin overview | REAL | DB counts; AI cost/token: NOT IMPLEMENTED (shown as such) |
| Users: list/search/detail/enable/disable/revoke sessions | REAL | no departments/HR model |
| Workspaces: list/detail | REAL | |
| Application inventory + detail (members, versions, AI activity, deployments, audit) | REAL | AI/hosting cost, risk score: NOT IMPLEMENTED |
| Transfer ownership, delete, restore version (admin) | REAL | archive: NOT IMPLEMENTED |
| AI Control | PARTIAL | OpenRouter free models + simulator, limits, counts; multi-provider, per-model permissions, tokens, cost, budgets: NOT IMPLEMENTED |
| Component registry (admin + studio) | REAL (fixed approved set) | contribution/review workflow: NOT IMPLEMENTED |
| Audit log (org-wide, filters, request id) | REAL | append-only in the database |
| Platform health | REAL | live probes, no invented percentages |
| Settings | REAL (read-only) | changes via environment |
| Studio shell: Home, Projects (paged, mine/shared), Activity | REAL | |
| Create application | PARTIAL | Website REAL; Dashboard / Internal Tool / Workflow: COMING SOON |
| Templates | PARTIAL | default page only; template library: NOT IMPLEMENTED |
| AI workspace (chat, model, changed components, version) | REAL | LLM = OpenRouter free models or simulator |
| Design mode: structure, drag/drop reorder, component library, click-to-select in preview, inspector | REAL | schema-driven; multi-page: NOT IMPLEMENTED |
| Images in components (`asset://`) | REAL | Hero and product images |
| Code mode | NOT IMPLEMENTED | honest screen; ADR 0005 |
| Deep links `/studio/projects/{id}/{ai|design|code|members|versions|assets|publish|settings}` | REAL | refresh stays in place |
| Pagination | REAL for projects, users, workspaces, inventory, audit; versions/prompts limited (100) | |
| Members & roles (Owner/Editor/Publisher/Viewer) | REAL | no Reviewer role (does not exist) |
| Versions & restore | REAL | restore creates a new version |
| Publish pipeline | REAL queue + state machine | deployment provider MOCK, labelled "Demo deployment" |
| Git, code generation, sandbox build, runtime hosting, connectors, secrets per project | NOT IMPLEMENTED | separate phase, needs approval |

## Verification (this machine)
Backend `./gradlew test` on 2026-10-02: 105 tests, 0 failures, 3 skipped (OpenRouterLiveTests — need a real OPENROUTER_API_KEY), browser E2E `e2e/factory-flow.mjs` 30/30, `e2e/sso-flow.mjs` 9/9 (Keycloak),
`e2e/a11y.mjs` 27/27 screens without serious/critical axe violations, `e2e/pages-mock.mjs` 6/6, typecheck and both builds (http + static mock).
