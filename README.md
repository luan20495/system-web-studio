# System Web Studio

An AI-assisted web studio: describe a change in chat, the backend turns it into validated, versioned page-schema operations, the UI previews the result, and a publish pipeline deploys it (to a **mock** provider locally).

Two ways to run it:

| Mode | What it is | Needs |
| --- | --- | --- |
| **mock** (default) | Static demo. All state is in memory; nothing is saved, published or sent. This is what GitHub Pages serves: https://luan20495.github.io/system-web-studio/ | Node only |
| **http** | Real full stack: Next.js UI → Kotlin/Spring API → PostgreSQL, Redis (sessions), MinIO (assets), RabbitMQ (publish jobs) | Docker, Node, JDK 21 |

## Quick start (full stack)
```bash
cp .env.example .env         # set LOCAL_ADMIN_PASSWORD (14+ chars)
npm ci
./scripts/run-local.sh       # containers + API + UI → http://localhost:3100  (login: local.admin)
./scripts/smoke-test.sh
./scripts/stop-local.sh
```
Mock demo only: `npm ci && npm run dev` → http://localhost:3000.

## What works in http mode
Login/logout with Redis-backed sessions · workspaces and projects with role-based access (admin, editor, publisher, viewer) · prompts → mock-LLM operations validated against the component registry · every change is an immutable version (history, restore as a new version, direct editing through the same validated endpoint) · project settings · asset upload to MinIO via presigned URLs · publish through RabbitMQ with idempotency and a visible state machine · append-only audit log · optimistic-concurrency conflicts (409) surfaced in the UI.

## What is mocked or missing
The LLM is a deterministic keyword planner, the deploy provider returns a labelled mock URL, and there is no Git export. There is no CSP, MFA/SSO, member-management UI or backup automation; see [docs/SECURITY.md](docs/SECURITY.md) and [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md). This is a development/internal-demo build, **not production-ready**.

## Verify
```bash
./scripts/check.sh                 # typecheck, both UI builds, backend tests (Testcontainers), npm audit
node e2e/full-flow.mjs             # browser E2E against the running stack
./scripts/backup-restore-drill.sh  # backup restore proof
```

## Docs
[LOCAL_DEVELOPMENT](docs/LOCAL_DEVELOPMENT.md) · [ARCHITECTURE](docs/ARCHITECTURE.md) · [API_CONTRACT](docs/API_CONTRACT.md) · [SECURITY](docs/SECURITY.md) · [DEPLOYMENT](docs/DEPLOYMENT.md) · [BACKUP_DR](docs/BACKUP_DR.md) · [IMPLEMENTATION_STATUS](docs/IMPLEMENTATION_STATUS.md)
