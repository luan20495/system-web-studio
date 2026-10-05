# AI Software Factory (System Web Studio)

An internal platform where employees build company software with AI under governance: one login, two areas.

* **Builder Studio** (`/studio`) — create **websites** (multi-page, forms, custom domains) from approved components with AI or the visual
  Design mode; **web apps and dashboards** (React source in a platform Git repository, sandbox builds, approved packages, AST Design mode,
  review before merge); **server apps, internal tools and workflows** (React + Node.js running in an isolated container with their own
  database, write-only secrets and approved connectors). Templates and contributed blocks with review workflows.
* **Admin Console** (`/admin`) — users, departments, workspaces, application inventory (archive), AI control and governance (model access,
  money budgets, alerts), security findings, hosting cost, packages, connectors, builds & storage, backups, identity (SSO/SAML/SCIM),
  editable policies with audit.

What is real and what is not: **[docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md)** (REAL / PARTIAL / MOCK / BLOCKED_EXTERNAL_INPUT /
NOT IMPLEMENTED). Security: [docs/SECURITY.md](docs/SECURITY.md). Architecture decisions: [docs/adr](docs/adr) (0001–0021).

## Run it

| Mode | What it is | Needs |
| --- | --- | --- |
| **mock** | Static demo, all state in memory — what GitHub Pages serves: https://luan20495.github.io/system-web-studio/ | Node |
| **http** | Full stack: Next.js UI → Kotlin/Spring API → PostgreSQL, Redis, MinIO, RabbitMQ (+ Forgejo, package mirror, build runner, apps DB, runtime gateway depending on the profile) | Docker, Node, JDK 21 |

```bash
cp .env.example .env               # set LOCAL_ADMIN_PASSWORD (14+ chars)
npm ci
STACK_PROFILE=full ./scripts/run-local.sh   # lean | medium | full (default) → http://localhost:3100, login local.admin
./scripts/stop-local.sh
```
Profiles (ADR 0020): **lean** = websites, templates, AI, admin (≈ 1.3 GiB idle) · **medium** = + source-code apps and dashboards
(Forgejo, mirror, sandbox runner, ≈ 1.6 GiB + builds) · **full** = + server apps / internal tools / workflows (apps DB, runtime gateway,
≈ 1.7 GiB + apps). Server apps are additionally off by policy until an admin enables `server-apps.enabled`.

AI: until a provider key is configured a labelled simulator answers ([docs/AI.md](docs/AI.md)); keys are read from the environment only.
Publishing from this machine: `./scripts/public-up.sh` (internal pilot behind a Cloudflare tunnel, [docs/PUBLIC_DEPLOYMENT.md](docs/PUBLIC_DEPLOYMENT.md)).

## Verify
```bash
cd backend && ./gradlew test                 # 185 tests (Testcontainers; JDK 21)
npx tsc --noEmit -p tsconfig.json            # UI typecheck
node e2e/factory-flow.mjs                    # browser E2E: websites, AI, design, publish, templates, blocks, admin
node e2e/code-flow.mjs                       # code apps: sandbox build, packages, design via AST, review, signed commits, IDE access
node e2e/runtime-flow.mjs                    # server apps: isolation, blue/green, dashboard, internal tool, workflow (full stack)
node e2e/a11y.mjs                            # axe on 41 screens
./scripts/secret-scan.sh                     # gitleaks, history + tree
./scripts/backup-all.sh local && ./scripts/restore-drill-all.sh local
```

## Docs
[IMPLEMENTATION_STATUS](docs/IMPLEMENTATION_STATUS.md) · [SOFTWARE_FACTORY_DESIGN](docs/SOFTWARE_FACTORY_DESIGN.md) · [SECURITY](docs/SECURITY.md) ·
[AI](docs/AI.md) · [PUBLIC_DEPLOYMENT](docs/PUBLIC_DEPLOYMENT.md) · [LOCAL_DEVELOPMENT](docs/LOCAL_DEVELOPMENT.md) · [ARCHITECTURE](docs/ARCHITECTURE.md) ·
[API_CONTRACT](docs/API_CONTRACT.md) · [BACKUP_DR](docs/BACKUP_DR.md) · [TIEN_DO (tiếng Việt)](docs/TIEN_DO.md)
