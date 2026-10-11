# XWEB (System Web Studio)

XWEB is an internal platform on which the people of one company build and run company software under governance. One login, three portals, one backend. Repository: `luan20495/system-web-studio`.

**Read this file first, then `docs/AI_HANDOFF.md`.** Everything a new engineer or AI team needs is in this repository; no chat history is required.

## What it is

| Portal | Who uses it | What it is for | Code |
|---|---|---|---|
| **Platform** | the platform operator (`SYSTEM_ADMIN`) | create and suspend companies (tenants), users, AI providers and budgets, components, builds, system health, backups, policies | `apps/platform` |
| **Admin** | the company's Tenant Admin (and people with admin permissions) | the company profile, organization structure, employees, accounts, workspaces, data sources | `apps/admin` |
| **Studio** | builders and publishers | create apps and pages, connect data, define actions and workflows, publish, roll back | `apps/studio` |
| **Published Site** | visitors | the released result of an app, with a constrained public data route; served through the sites gateway, not by a Next.js app | backend `publish/*`, `infra/sites-gateway` |

The product is a **modular monolith**: one Kotlin / Spring Boot backend (`backend/`, package `com.systemwebstudio`) and a Next.js monorepo at the repository root, with PostgreSQL (Flyway), Redis, RabbitMQ and MinIO. Details: `docs/ARCHITECTURE.md`.

## The hierarchy

```
System (the platform)                 SYSTEM_ADMIN: platform scope only
 └─ Tenant (Company)                  TENANT_ADMIN / MEMBER
     └─ Workspace                     WORKSPACE_ADMIN / EDITOR / PUBLISHER / VIEWER
         └─ Project (App)             OWNER / EDITOR / PUBLISHER / VIEWER
```

A **Project** and an **App** are the same record (the table is `projects`; the Studio calls the list "applications"). The tenant is always derived by the server from the workspace in the URL, never from request input. A role name is never authorization; permissions are computed from the database on every request (`docs/SECURITY_AND_PERMISSION.md`).

## Current state

| Name | Value |
|---|---|
| `CURRENT_INTEGRATION_SHA` | `{{INTEGRATION_SHA}}` on branch `integration/v2` (the source of truth; `main` is older and is **not** the implementation) |
| `CURRENT_RC_SHA` | `{{FINAL_RC_SHA}}` (the release candidate that the golden company acceptance ran on; see `docs/QA_FINAL.md`) |
| `CURRENT_PUBLIC_FRONTEND_SHA` | `{{PUBLIC_FRONTEND_SHA}}` (public portals, release `bc5c47f292d0-3f59e8d1`, as of 2026-10-11) |
| `CURRENT_PUBLIC_API_SHA` | `{{PUBLIC_API_SHA}}` (public API, release `1006cbf441f6-8985413d`, as of 2026-10-11) |

The public deployment is a pilot behind a Cloudflare tunnel on a developer machine and runs an older pinned release than integration; moving it is an explicit act (`docs/OPERATIONS.md`, decisions `D-C0-49`, `D-C0-50`, `D-C0-57`).

**Production ready: NO.** See `docs/PROJECT_STATUS.md` (per-area status) and `docs/KNOWN_LIMITATIONS.md`. The main reasons: there is no Linux runtime host with kernel-level isolation for server apps, no off-host backup, no real model-provider key in the default setup, open findings listed with owners, and several features are deliberately deferred (below).

**Deferred (not in this release):** a dedicated `APPROVAL_DECIDE` permission and approval notifications / inbox, candidate activation (migration number V31 is a permanent gap), the cross-tenant share policy, and a browser UI for starting LIVE workflows and deciding approvals (both are API-only today). Every item has an id and an owner note in `docs/KNOWN_LIMITATIONS.md`.

## Read this next, in order

1. `docs/AI_HANDOFF.md` — how to work in this repository (commands, policies, limits).
2. `docs/PROJECT_STATUS.md` — what is DONE / PARTIAL / BLOCKED / DEFERRED, per area.
3. `docs/ARCHITECTURE.md` — modules, planes, data flow, invariants that must not be broken.
4. `docs/SECURITY_AND_PERMISSION.md` — who may do what, and which test proves it.
5. `docs/GOLDEN_COMPANY_FLOW.md` — the business acceptance test: one real company end to end (27 steps).
6. `docs/OPERATIONS.md` — ports, stacks, environment, recovery, resource limits.
7. `docs/QA_FINAL.md` — test strategy, gates, evidence, final acceptance record.
8. `docs/KNOWN_LIMITATIONS.md` — what is open, deferred or flaky.

Topic references: `docs/DYNAMIC_ORG.md`, `docs/DATA_RUNTIME.md`, `docs/ACTION_WORKFLOW.md`, `docs/PUBLISH_RUNTIME.md`, `docs/BRAND_GUIDELINE.md`, `docs/DECISIONS.md` (map of the decision record), frozen contracts in `docs/contracts/v2/`.

## Create a development environment

Requirements: macOS or Linux, **JDK 21** (on the maintainer's Mac the default `java` is 11: set `JAVA_HOME=/opt/homebrew/opt/openjdk@21`), Node 22, Docker (Compose v2), about 8 GB of memory for Docker.

```bash
cp .env.example .env                 # set LOCAL_ADMIN_PASSWORD (14+ characters); .env is git-ignored
npm ci
PORTALS=1 ./scripts/run-local.sh     # backend :8080, Platform :3001, Admin :3002, Studio :3003, sites gateway :18088
./scripts/stop-local.sh              # stops only what run-local.sh started (use --infra to stop the containers)
```

Log in as `local.admin` (the password is `LOCAL_ADMIN_PASSWORD` from `.env`). `PORTALS=1` turns on the V1 configuration: data platform, workflow runtime with the RabbitMQ queue, publish configs, public data route and the data-target trust store (`scripts/_env.sh`). Without it the application starts with those features off (every one defaults to false). A TLS PostgreSQL "data target" with the demo `shop` database for the data features: `./scripts/data-target.sh up`.

An isolated stack from an exact commit (own containers, ports and state directory; used for release candidates and acceptance): `docs/OPERATIONS.md`, tool `docs/parallel/c5/e2e-stack.sh`.

## Verify

```bash
cd backend && ./gradlew test         # JDK 21, Docker (Testcontainers). Use --no-build-cache for evidence runs. About 15-20 minutes
npm run gate:frontend                # static guards, typecheck, unit tests, production builds of the three portals, bundle scan (about 90 s)
./scripts/secret-scan.sh             # gitleaks over all history and the working tree
node scripts/golden-company-flow.mjs # the 27-step acceptance chain on a running stack (see docs/GOLDEN_COMPANY_FLOW.md)
```

Run heavy jobs one at a time (a full backend run, the browser harness and a full stack together starve the machine): `docs/OPERATIONS.md` section on resource limits.

## Where secrets live (and do not)

Secrets are environment values, never source. Local: `.env` (git-ignored) and `.run/` (generated tokens and the data-target keys, git-ignored). Isolated stacks: `~/.xweb-e2e-stack/<stack>/stack.env` (mode 600, outside the repository). Stored application secrets (data-source credentials, provider keys) are AES-GCM encrypted with `SECRETS_MASTER_KEY`, write-only, and never returned by any API. `.env.example` contains only loopback placeholders. Never print a password or token into a log or document; `./scripts/secret-scan.sh` enforces it for the repository. Details: `docs/SECURITY_AND_PERMISSION.md` section 13.

## Rules that bind every change

Do not merge into `main`; no force push, no history rewrite; only one role allocates migration numbers; a change to a frozen contract needs an entry in the decision ledger first; never kill a process by name or by port; one commit and one test run per task. The full list: `docs/AI_HANDOFF.md` and `CLAUDE.md`.

## Older material

Earlier status documents (`docs/IMPLEMENTATION_STATUS.md`, `docs/TIEN_DO.md`, the temporary handoffs under `docs/parallel/`) are kept for audit and carry a `SUPERSEDED_BY` note; the index is `docs/archive/2026-10-finalization/README.md`. The static mock demo (GitHub Pages) is the legacy root app and is not part of the product described here.
