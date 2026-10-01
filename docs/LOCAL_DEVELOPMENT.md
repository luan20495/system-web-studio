# Local development

## Prerequisites
Docker (Compose v2), Node 22+, JDK 21 (Homebrew `openjdk@21` is auto-detected by the scripts). Ports used: Postgres 15432, Redis 16379, MinIO 19000/19001, RabbitMQ 15674/15675, API 8080, UI 3100. All bind to loopback only.

## One-time setup
```bash
cp .env.example .env
# set LOCAL_ADMIN_PASSWORD to a unique value of 14+ characters
npm ci
```

## Run the full stack (real PostgreSQL, Redis, MinIO, RabbitMQ, Kotlin API, Next.js in http mode)
```bash
./scripts/run-local.sh      # starts containers, API (bootRun, profile local) and a production build of the UI
./scripts/smoke-test.sh     # ~10 s API smoke test
./scripts/stop-local.sh     # add --infra to also stop containers (volumes are kept)
```
Open http://localhost:3100. Accounts created by the `local` profile (all use `LOCAL_ADMIN_PASSWORD`, the seed re-syncs them on every start):

| user | workspace role | role on the demo project | can |
| --- | --- | --- | --- |
| local.admin | WORKSPACE_ADMIN (system admin) | OWNER | everything, audit log |
| local.editor | EDITOR | EDITOR | edit, settings, assets, restore |
| local.publisher | VIEWER | PUBLISHER | read, publish |
| local.viewer | VIEWER | VIEWER | read only |

Swagger UI (local profile only): http://127.0.0.1:8080/swagger-ui.html. MinIO console: http://127.0.0.1:19001. RabbitMQ management: http://127.0.0.1:15675.

## Mock (GitHub Pages) mode
`npm run dev` with the default `NEXT_PUBLIC_API_MODE=mock` needs no backend. `npm run build` produces the static export in `out/`; `STUDIO_BASE_PATH=/system-web-studio` is used for GitHub Pages. Mock mode never calls an API.

`.env` is read by Next.js too, so do **not** put `NEXT_PUBLIC_API_MODE=http` in it unless you want `npm run dev` to need the backend; `run-local.sh` sets it for its own build.

## Verification
```bash
./scripts/check.sh                 # typecheck, mock + http builds, all backend tests (Testcontainers), npm audit
node e2e/full-flow.mjs             # real-browser E2E (Chrome, playwright-core) against the running stack
./scripts/backup-restore-drill.sh  # dump -> restore into a scratch DB -> compare row counts
```
Backend tests need Docker (they start their own Postgres, Redis, MinIO and RabbitMQ containers and never touch the dev stack).

## Troubleshooting
- `Another next dev server is already running`: Next 16 allows one dev server per build directory. The http-mode UI uses `.next-http`, so it can run next to `npm run dev`.
- Login works in Chrome but not elsewhere over plain http: set `COOKIE_SECURE=false` (the scripts do). Never in production.
- API 503 `DEPENDENCY_UNAVAILABLE`: Redis (sessions) is unreachable; `docker compose ps`.
