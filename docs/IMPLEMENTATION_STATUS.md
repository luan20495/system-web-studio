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
| Admin overview | REAL | DB counts (AI tokens/cost are on the AI Control page) |
| Users: list/search/detail/enable/disable/revoke sessions | REAL | no departments/HR model |
| Workspaces: list/detail | REAL | |
| Application inventory + detail (members, versions, AI activity, deployments, audit) | REAL | AI/hosting cost, risk score: NOT IMPLEMENTED |
| Transfer ownership, delete, restore version (admin) | REAL | archive: NOT IMPLEMENTED |
| AI Control | REAL | providers, per-model enable/disable, pricing catalog, live connection check, usage report |
| Multiple AI providers (Phase 6) | REAL, stub-verified | OpenAI, Anthropic, Gemini (OpenAI-compatible), internal OpenAI-compatible; paid models off until enabled; never used by "auto"; **no real call made to these providers yet**; per-workspace/role model permissions, streaming, tool calling: NOT IMPLEMENTED |
| AI cost | REAL | provider-reported (OpenRouter) or explicit pricing catalog × reported tokens; unknown otherwise; budgets in money (not tokens), alerts: NOT IMPLEMENTED |
| AI usage accounting (Phase 4) | REAL | every upstream call in `ai_calls` with provider-reported tokens/cost (null = not reported, never estimated); admin report by model/user/workspace/day + call log; my usage on Studio Home; tokens/cost per AI answer |
| AI token budgets | REAL, off by default | per user per 24 h and per workspace per month, checked before the model call (`429 AI_TOKEN_LIMIT`); alerts, per-model budgets, own price table, chargeback: NOT IMPLEMENTED |
| Component registry (admin + studio) | REAL (fixed approved set) | new base component types need a renderer in code (by design, ADR 0006) |
| Contributed blocks (Phase 5) | REAL | presets of approved components; draft → automated checks → admin review (no self-approval) → library → deprecate/restore; versioned; usage of blocks NOT MEASURED; AI does not use blocks yet |
| Audit log (org-wide, filters, request id) | REAL | append-only in the database |
| Platform health | REAL | live probes, no invented percentages |
| Settings | REAL (read-only) | changes via environment |
| Studio shell: Home, Projects (paged, mine/shared), Activity | REAL | |
| Create application | PARTIAL | Website REAL; Dashboard / Internal Tool / Workflow: COMING SOON |
| Templates (Phase 5) | REAL | Company / My templates as page schema (no code, images not carried over); save from a project, versions, start a project from a template; only admins share company-wide; no template review queue, no categories/screenshots |
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
Backend `./gradlew test` on 2026-10-02 (after Phase 6): 124 tests, 0 failures, 3 skipped (OpenRouterLiveTests — need a real OPENROUTER_API_KEY), browser E2E `e2e/factory-flow.mjs` 33/33, `e2e/sso-flow.mjs` 9/9 (Keycloak), `e2e/providers-flow.mjs` 6/6 (local OpenAI-compatible stub),
`e2e/a11y.mjs` 28/28 screens without serious/critical axe violations, `e2e/pages-mock.mjs` 6/6, typecheck and both builds (http + static mock).
