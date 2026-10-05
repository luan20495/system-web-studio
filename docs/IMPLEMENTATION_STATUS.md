# Implementation status — AI Software Factory (2026-10-04)

**Development Ready: YES · Internal Pilot Ready: YES · Production Ready: NO** — production needs a Linux runtime host,
off-host backups, real provider keys and an owner decision on the items in "Required decisions" (see the readiness report).

Legend: **REAL** = implemented and verified end to end on this machine · **PARTIAL** = real but with a stated gap · **MOCK** = simulator /
stand-in, labelled as such · **BLOCKED_EXTERNAL_INPUT** = needs something only the owner can provide · **NOT IMPLEMENTED**.

## Evidence matrix (what is REAL and why) — 2026-10-05
REAL means a working backend flow **and** an automated test. Where a feature has code and UI but a weaker test, it is marked PARTIAL here even if older text below says REAL.

| Feature | Status | Evidence | Production blocker |
|---|---|---|---|
| Auth (password, sessions, CSRF, throttling) | REAL | `AuthIntegrationTests`, `HardeningTests`, factory-flow | — |
| OIDC SSO + RP logout | REAL | `sso-flow` 11/11 (Keycloak) | real company IdP not tested (BLOCKED_EXTERNAL_INPUT) |
| SAML | PARTIAL | `sso-flow` SAML brokering via Keycloak (ADR 0021) | real tenant; native SAML intentionally not added |
| SCIM 2.0 | REAL (protocol) | `ScimTests`, `ScimDisabledTests` | real Entra/Okta tenant (BLOCKED_EXTERNAL_INPUT) |
| Admin Console (users, apps, AI, costs, security, backups, health, settings) | REAL | factory-flow, a11y 41 screens, `AdminOrgTests`, `HealthProbesTests` | — |
| Platform health | REAL | `HealthProbesTests`; five states, UNKNOWN when nothing to base a claim on | — |
| Builder: website (multi-page, nav, SEO, 404) | REAL | `StaticSiteTests`, factory-flow, public-flow | — |
| Forms | REAL | `StaticSiteTests` (validation, spam, limits, export) | — |
| Custom domains | PARTIAL | `StaticSiteTests` (TXT, TLS probe stubbed, re-check, squatting) | real DNS + tunnel route (BLOCKED_EXTERNAL_INPUT) |
| Templates / blocks / previews | REAL | `TemplateAndBlockTests`, `LibraryCatalogTests` | — |
| Components registry | REAL | `ComponentApiTests` | new base component types need renderer code (by design) |
| Source apps (Git, sandbox, packages, review, signing) | REAL (local stack) | `CodeProjectTests` (15), code-flow 14/14, live sandbox flag capture | pilot has no Git/runner (policy: off) |
| AI code generation | PARTIAL | simulator + stub-model tests; **no real provider** | provider key (BLOCKED_EXTERNAL_INPUT) |
| AI governance (access, budgets, alerts, streaming, tools) | REAL (stub-verified) | `AiGovernanceTests`, `AiConfigWebTests`, `admin-setup-flow` 12/12 | real provider calls not exercised |
| Quotas (AI, build, storage) | REAL | `QuotaEnforcementTests`, `LockdownSettingsTests`, `AiGovernanceTests`; rejections visible in admin | — |
| Server runtime | REAL (Docker Desktop) | `ServerRuntimeTests`, runtime-flow 8/8 (hardening, isolation, blue/green, archive) | Linux host + gVisor (not production isolation on a Mac) |
| App DB isolation, secrets, signed identity | REAL | runtime-flow (role cannot open other DBs; forged header ignored), `ServerRuntimeTests` | — |
| Connectors | PARTIAL | `ServerRuntimeTests` refusals, workflow NOTIFY path | no approved external API yet; real call untested |
| Dashboard | PARTIAL | runtime-flow builds and publishes it; sample data / JSON URL only, no data-source UI | pilot use case needed |
| Internal tool / Workflow | REAL (scaffold level) | runtime-flow (private, signed identity, approvers, history) | AI-generated server code untested with a real model |
| Backups + restore drill | LOCAL_BACKUP_REAL | `backup-all.sh`, `restore-drill-all.sh`, `BackupMonitorTests`; local+public drills PASS | **off-host copy** (BLOCKED_EXTERNAL_INPUT) |
| Auto restart | REAL | Docker restart policies, watchdog (pilot) | systemd on Linux |
| Public pilot posture | REAL | public-flow 13/13: sign-up closed, code/server apps off, V23, HSTS/CSP | — |

## Platform, identity, admin
| Area | Status | Notes |
|---|---|---|
| Login gateway (Admin / Builder portal), password login, Redis sessions, CSRF, throttling | REAL | |
| Public sign-up | REAL, **off** (HIGH-risk setting, audited) | pilot: `SIGNUP_DISABLED` verified through Cloudflare |
| OIDC SSO | REAL (Keycloak E2E 11/11) | RP-initiated logout ends the IdP session (verified) |
| SAML | PARTIAL | through the OIDC provider's identity brokering (`kc_idp_hint`), behind `SAML_ENABLED=false`; verified with a local SAML realm. Native SAML SP: NOT IMPLEMENTED (needs OpenSAML 5 from the Shibboleth repository — owner decision) |
| SCIM 2.0 (Users, Groups, discovery) | REAL, **off** (`SCIM_ENABLED`) | never maps to system admin; workspace roles only via explicit group mappings; tested with RFC-style payloads (no real IdP connected: BLOCKED_EXTERNAL_INPUT for an Entra/Okta tenant) |
| MFA | REAL by delegation | shown as "MFA managed by Identity Provider"; local accounts have no MFA (emergency/demo) |
| Admin console | REAL | users, workspaces, departments/teams, applications (archive/restore), AI control, AI governance, alerts, security findings, costs, components, templates, packages, builds & storage, connectors, backups, identity, audit, health, editable settings (HIGH-risk confirmation + audit) |
| Departments / teams | REAL | reporting only, grants no permission |
| Application archive | REAL | read-only + site offline + server app stopped; restore |
| Hosting cost | PARTIAL | measured storage, build CPU (cgroup), build time, known AI cost × explicit admin prices; **egress not measured** (shown as such); unknown without a price |
| Security findings | REAL | from build scans (OSV, gitleaks), accepted package risks, risky settings; no score by design |
| Audit log | REAL | append-only |

## AI
| Area | Status | Notes |
|---|---|---|
| AI page editing, code generation, simulator | REAL / MOCK | simulator labelled "mô phỏng" when no provider is configured |
| Real provider calls (OpenRouter, OpenAI, Anthropic, Gemini) | **BLOCKED_EXTERNAL_INPUT** | no keys on this server; integrations verified against local stubs only; `OpenRouterLiveTests` skipped |
| Model access (org → workspace → role → user, most restrictive wins) | REAL | Admin → Quản trị AI, effective-permission check |
| Money budgets (org/workspace/user/project, daily/monthly, soft/hard, currency with explicit rate) | REAL | unknown-cost calls counted, never estimated; paid model without a price refused under a hard budget |
| Alerts | REAL | budgets, provider key/credit failures, backups |
| SSE streaming, cancel, timeout, partial output, final usage | REAL (stub-verified) | native formats for OpenAI-compatible/OpenRouter/Anthropic; JSON fallback for servers that ignore `stream`; ≤ 2 streams per user |
| Controlled tool calling | REAL (stub-verified) | 6 tools, authorized with the user's permissions, audited in `ai_tool_calls` |
| Retrieval priority + reuse tracking | REAL | registry → approved blocks → templates → generated; verified (not trusted) in `prompt_runs.reuse_sources` |

## Websites, templates, blocks
| Area | Status | Notes |
|---|---|---|
| Multi-page sites (pages, slugs, home, 404, SEO, navigation) | REAL | one atomic artifact per publish; external links only to admin-approved https hosts |
| Functional forms | REAL | script-free POST, validation, honeypot, rate limits, origin check, hashed IPs, editor-only view/CSV/delete, retention |
| Custom domains | PARTIAL | DNS TXT verification, real TLS probe, host-based serving, daily re-check — verified locally; serving on the pilot needs the owner's DNS + a tunnel/CDN route per domain (BLOCKED_EXTERNAL_INPUT) |
| CDN caching | REAL | immutable assets, revalidated pages |
| Templates | REAL | review workflow PRIVATE→SUBMITTED→REVIEW→APPROVED/ARCHIVED, categories, tags, usage counts, JS-free screenshot previews (UNAVAILABLE without a browser on the worker) |
| Contributed blocks | REAL | + category, tags, preview, usage counted on real inserts |

## Source-code apps and server apps (local stack; off on the pilot)
| Area | Status | Notes |
|---|---|---|
| Git (Forgejo), sandbox builds (non-root, read-only, no caps, mirror-only install, no-network build, OSV, gitleaks, SBOM) | REAL | |
| Approved package catalog, `@company/ui`, `@company/app-sdk`, Design mode via AST, review before merge, IDE read-only access, signed bot commits, repository lifecycle, private code apps, build/storage quotas | REAL | |
| App kinds (Website, Web app, Dashboard, Internal tool, Workflow, Server app) | REAL | scaffolds per kind; all built through the real sandbox |
| Server runtime (Phase 7.5) | REAL on this Mac (Docker Desktop Linux VM) | isolated container per app, one internal network per app, blue/green with health checks (0 failed requests during a switch), rollback without rebuild |
| Per-app database isolation | REAL | separate Postgres server, database + role per app; cross-database connect denied (verified) |
| Project secrets | REAL | AES-GCM, write-only, never in repos or AI context |
| Connector catalog + proxy | PARTIAL | grants, operation allowlist, credential injection, public-address check — tested with refusals; **no real outbound connector call exercised** (no approved external API) |
| Linux production runtime host, gVisor | NOT IMPLEMENTED | runner supports `RUNTIME_OCI=runsc`; needs a Linux host (owner decision) |
| Source/server apps on the public pilot | NOT ENABLED | pilot has no Git server / runtime; server apps stay off until a Linux runner exists |

## Infrastructure
| Area | Status | Notes |
|---|---|---|
| Backups (platform DB, app DBs, MinIO, Forgejo), retention | REAL | daily daemon on the pilot |
| Restore drill (from the latest backup files) | REAL | local + public PASS (2026-10-04) |
| Backup monitoring + alerts | REAL | Admin → Sao lưu |
| Off-host backup copy | **BLOCKED_EXTERNAL_INPUT** | needs an S3/MinIO target or synced storage from the owner |
| Auto restart | REAL | Docker `unless-stopped` + host watchdog; systemd recommended on Linux |
| LEAN / MEDIUM / FULL profiles | REAL | measured idle memory 1.3 / 1.6 / 1.7 GiB |
| Kubernetes, GitHub Actions | NOT IMPLEMENTED (by instruction) | |

## Verification (2026-10-04, this machine)
Backend `./gradlew test`: **185 tests, 0 failures, 3 skipped** (OpenRouter live tests need a key). Browser E2E: `factory-flow` 34/34, `code-flow` 14/14, `runtime-flow` 8/8,
`a11y` 41/41 screens (no serious/critical axe violations), `admin-setup-flow` 12/12 (AI configured entirely on the web), `pages-mock` 6/6 (GitHub Pages static build), `sso-flow` 11/11
(Keycloak, incl. RP logout + SAML brokering), `public-flow` 13/13 (live pilot through Cloudflare, after the V23 redeploy). Live smoke tests:
server app create → sandbox build → isolated runtime → API → own DB; blue/green switch; Dashboard / Internal tool / Workflow; app-to-app and
platform-DB isolation; multi-page site, 404, forms through the real gateway; backup + restore drill. Secret scan (history + tree): clean.
OSV: runtime classpath 208 artifacts and scaffold lockfiles — no known advisories after the patch commit.
