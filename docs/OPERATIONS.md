# OPERATIONS

Canonical operations guide for XWEB / System Web Studio (repository `luan20495/system-web-studio`, backend package `com.systemwebstudio`).
Audience: engineers who take over the product with no chat history. Language: English. Every factual claim carries `(src: path[:line])`; anything not verified is marked `UNVERIFIED` and listed in the last section.

| Field | Value |
|---|---|
| Source of truth | branch `integration/v2` (read at local HEAD `28376ded2307`, 2026-10-11; final state `{{INTEGRATION_SHA}}`) |
| Final release candidate | `{{FINAL_RC_SHA}}` |
| Public portals (approved release) | `{{PUBLIC_FRONTEND_SHA}}` |
| Public API (approved release) | `{{PUBLIC_API_SHA}}` |
| Document state | describes `integration/v2` after the final imports: C4 approval runtime (migration V33, D-C0-61), C4 interrupted-worker fix, C1 final IAM hardening (D-C0-60), C5 UI fixes FQ-UI-01 / FQ-A11Y-02, the C6 QA documents and harness (`docs/parallel/c6/**`). Dated records are labelled with their date. |

Related canonical docs: [ARCHITECTURE](ARCHITECTURE.md), [IMPLEMENTATION_STATUS](IMPLEMENTATION_STATUS.md), [BACKUP_DR](BACKUP_DR.md), [BACKUP_OPERATIONS](BACKUP_OPERATIONS.md), [DISASTER_RECOVERY](DISASTER_RECOVERY.md), [OBSERVABILITY](OBSERVABILITY.md), [SECURITY](SECURITY.md), [ADRs](adr/). Companion canonical docs: [KNOWN_LIMITATIONS](KNOWN_LIMITATIONS.md) (open items), [QA_FINAL](QA_FINAL.md) (test strategy, gates, acceptance record).

Status vocabulary used in this file: DONE / PARTIAL / BLOCKED / DEFERRED.

---

## 1. System at a glance

Modular monolith: Kotlin / Spring Boot 4 API (JDK 21) + Next.js frontends + PostgreSQL 17 (Flyway), Redis, MinIO (S3), RabbitMQ (src: `docs/parallel/OWNERSHIP.md:5`, `CLAUDE.md`). The frontend is three independently deployed Next.js portals, each with its own origin and a same-origin `/api` proxy to the API (src: `docs/adr/0022-frontend-monorepo-three-deployments.md`, `backend/src/main/resources/application.yml:191-197`):

| Portal | Source dir | Audience (src: `application.yml:194-197`) |
|---|---|---|
| Platform | `apps/platform` | SYSTEM_ADMIN (platform scope) |
| Admin | `apps/admin` | TENANT_ADMIN and scoped managers |
| Studio | `apps/studio` | tenant members: build, run, publish |

Other processes: a render worker (`workers/render`, preview + static-site rendering), a build runner (`workers/runner/runner.mjs`, sandboxed builds of code projects in Docker), a sites gateway (nginx, serves published sites from the API), optional Forgejo / Verdaccio / apps DB / apps gateway / Keycloak (src: `compose.yml`, `scripts/run-local.sh`).

There is also a legacy single-root Next.js app (`app/`, port 3100) kept for backward compatibility; `PORTALS=1` replaces it with the three portals (src: `scripts/run-local.sh:3-5`).

---

## 2. Prerequisites

| Need | Detail |
|---|---|
| Docker with Compose v2 | required for the dev stack and for every backend integration test (Testcontainers) (src: `docs/LOCAL_DEVELOPMENT.md:4`, `docs/LOCAL_DEVELOPMENT.md:42`) |
| JDK 21 | `backend/build.gradle.kts:24-29` pins the toolchain to 21. `scripts/_env.sh:25` sets `JAVA_HOME=/opt/homebrew/opt/openjdk@21` when that directory exists, otherwise keeps the caller's `JAVA_HOME`. `scripts/_env.sh:24` says "JDK 17+ (21 recommended)" but the Gradle toolchain is 21: treat 21 as required. |
| Node 22+ | `docs/LOCAL_DEVELOPMENT.md:4`; `@types/node` is `^22.14.0` (src: `package.json`). No `.nvmrc` exists on `integration/v2` (checked with `git show`). |
| Chrome / Chromium | browser harness and real-backend E2E; macOS default `/Applications/Google Chrome.app/...`, override `CHROME` / `E2E_CHROME` (src: `docs/parallel/c5/e2e-stack.sh:32`, `tests/browser/README.md:5-6`) |
| OS | the tooling is macOS-oriented (Homebrew JDK path, `lsof`, `ps -o lstart`, `caffeinate`, bash 3.2 compatibility); the scripts were never run on Linux (src: `docs/BACKUP_DR.md:77`, `docs/parallel/c0/PROCESS_SAFETY.md:33`). UNVERIFIED on Linux. |
| `gitleaks` | for `scripts/secret-scan.sh` (src: `scripts/secret-scan.sh:5`) |

---

## 3. Local development

### 3.1 One-time setup

```bash
cp .env.example .env          # .env is git-ignored
# edit .env: set LOCAL_ADMIN_PASSWORD (unique, 14+ characters). Never commit it, never paste it into a ticket or a log.
npm ci
```
(src: `docs/LOCAL_DEVELOPMENT.md:6-11`, `.gitignore`, `.env.example` "LOCAL_ADMIN_PASSWORD")

`scripts/_env.sh` is sourced by every operational script. It aborts when `.env` is missing, and aborts with `Set LOCAL_ADMIN_PASSWORD in .env` when the variable is empty (src: `scripts/_env.sh:6-11`). It then fills the local-only defaults of `.env.example` for the database, Redis, MinIO and RabbitMQ and exports `COOKIE_SECURE=false`, `SPRING_PROFILES_ACTIVE=local` (src: `scripts/_env.sh:12-23`).

**Secret handling for the local admin password.** The value lives only in `.env` (git-ignored, keep mode 600). The `local` Spring profile creates four accounts (`local.admin`, `local.editor`, `local.publisher`, `local.viewer`) that all use `LOCAL_ADMIN_PASSWORD` and are re-synced on every start (src: `docs/LOCAL_DEVELOPMENT.md:19-27`). The scripts print `Login: local.admin / (LOCAL_ADMIN_PASSWORD from .env)` and never the value (src: `scripts/run-local.sh:58`). Isolated stacks generate a random one into a mode-600 file outside the repo (section 6). Variable names and purposes are in section 12; `.env.example` is the template and contains no real secret.

### 3.2 Starting and stopping

```bash
./scripts/run-local.sh                # legacy mode: containers + API (bootRun, profile local) + the root app on :3100
PORTALS=1 ./scripts/run-local.sh      # V1 local mode: the three portals instead of :3100, plus the V1 configuration (below)
./scripts/smoke-test.sh               # ~10 s API smoke test
./scripts/stop-local.sh [--infra]     # --infra also stops containers (volumes are kept)
```
(src: `scripts/run-local.sh:3-5`, `docs/LOCAL_DEVELOPMENT.md:15-17`, `scripts/stop-local.sh:37`)

What `run-local.sh` does, in order (src: `scripts/run-local.sh`):
1. `PORTALS=1` only: `scripts/data-target.sh up` first (the trust store must exist before `_env.sh` builds the JVM options) (line 7).
2. Sources `_env.sh`, then optional `.run/sso.env` written by `scripts/sso-up.sh` (lines 8-10).
3. Validates `STACK_PROFILE` = `lean | medium | full` (default `full`) and runs `docker compose --profile "$STACK_PROFILE" up -d --wait` (lines 13, 18). Profiles: lean = websites only; medium = + Git server, package mirror, build runner; full = + apps DB and apps gateway (src: line 11-12, `docs/DEPLOYMENT.md:6-12`). Measured idle memory 1.3 / 1.6 / 1.7 GiB (src: `docs/IMPLEMENTATION_STATUS.md:97`).
4. Forgejo one-time setup (`scripts/forgejo-setup.sh`) unless lean (line 20).
5. Compiles and starts the render worker if `:18095/health` does not answer (lines 22-28).
6. Starts the API with `./gradlew bootRun` if `/actuator/health/liveness` does not answer; first start compiles (about 1 minute); log `.run/backend.log` (lines 29-34).
7. `PORTALS=1`: `scripts/portals.sh up`; otherwise `next build` + `next start -p $FRONTEND_PORT` with `NEXT_PUBLIC_API_MODE=http` (lines 35-44).
8. Starts the build runner unless lean (lines 46-48); optionally `BACKUP_DAEMON=true` (scheduled backups) and `WATCHDOG=true` (restarts stopped processes) (lines 50-51).

`PORTALS=1` additionally sets (src: `scripts/_env.sh:28-53`): `DATA_PLATFORM_ENABLED=true`, `WORKFLOW_ENABLED=true`, `PUBLISH_CONFIGS_ENABLED=true`, `WORKFLOW_QUEUE=amqp`, `SITES_PUBLIC_DATA_ENABLED=true` with `SITES_DATA_API_BASE=http://127.0.0.1:18088/{slug}/_data`, `TRUST_PROXY=true`, the three portal origins in `CORS_ALLOWED_ORIGINS`, `DATA_PLATFORM_POSTGRES_ALLOWED_PRIVATE=127.0.0.1:15440` and (when present) the data-target trust store through `JAVA_TOOL_OPTIONS`. It does NOT set `ORGANIZATION_PERSISTENCE_ENABLED` (default OFF, see section 12).

The local data target is a separate PostgreSQL with TLS for the data-source flow (`scripts/data-target.sh up|status|password ro|rw|down [--purge]`), never the platform DB (15432) nor the apps DB (15434); everything it generates is under `.run/data-target/` (git-ignored, mode 600) and `password ro|rw` is the only place a generated password is printed (src: `scripts/data-target.sh:1-9`).

Generated, git-ignored state lives in `.run/` (render token, runner token, `appdb.env` with the apps DB admin password, gateway token and `SECRETS_MASTER_KEY`, pid files, logs) (src: `scripts/_env.sh:55-72`, `.gitignore`). Stopping uses pid files that are honoured only when the pid still runs from this checkout, and ports are freed only through `scripts/owned-process.mjs stop-port --cwd-under "$ROOT"` (src: `scripts/stop-local.sh:3-6`).

Mock mode (GitHub Pages demo) needs no backend: `npm run dev` with default `NEXT_PUBLIC_API_MODE=mock`; do not put `NEXT_PUBLIC_API_MODE=http` in `.env` (src: `docs/LOCAL_DEVELOPMENT.md:30-33`).

### 3.3 Local troubleshooting (verified in the corpus)
- Another `next dev` is already running: Next 16 allows one dev server per build directory; the http-mode UI uses `.next-http` (src: `docs/LOCAL_DEVELOPMENT.md:44`).
- Plain-http login fails outside Chrome: `COOKIE_SECURE=false` (the scripts set it); never in production (src: `docs/LOCAL_DEVELOPMENT.md:45`).
- API 503 `DEPENDENCY_UNAVAILABLE`: Redis (sessions) unreachable; `docker compose ps` (src: `docs/LOCAL_DEVELOPMENT.md:46`).
- The proxy target `API_PROXY_TARGET` is baked into a Next build at build time; setting it only at `next start` has no effect (every `/api` call answers 500 ECONNREFUSED :8080) (src: `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md:61-65`). Exception: the current `nextConfig` also requires it at start (src: `docs/parallel/c5/e2e-stack.sh:166`).
- A port held by a process of another checkout is reported and left alone (exit code 3 of the owned-process helper); free it yourself or use other ports (section 8).

---

## 4. Compose stacks

### 4.1 `compose.yml` (development; every port bound to `127.0.0.1`)
Default project name = directory name (containers are named `hbl-*-1` for the main checkout, src: `docs/BACKUP_OPERATIONS.md:9`). All values below are from `compose.yml`; `mem_limit` is a hard cap, not a measurement.

| Service | Image | Host port (default) | mem_limit | Profile | Notes |
|---|---|---|---|---|---|
| postgres | `postgres:17.6` | 15432 (`POSTGRES_HOST_PORT`) | 1g | always | db `system_web_studio`, user `studio` (l.4-20) |
| redis | `redis:8.2.1-alpine` | 16379 (`REDIS_HOST_PORT`) | 384m | always | AOF on, `maxmemory 256mb`, `noeviction` (sessions and rate limits: never evict silently) (l.22-36) |
| minio | `bitnamilegacy/minio:2025.7.23-debian-12-r5` | 19000 API / 19001 console | 512m | always | the official image is no longer pullable; legacy pinned build for development (l.38-57) |
| rabbitmq | `rabbitmq:4-management-alpine` | 15674 AMQP / 15675 management | 768m | always | (l.59-76) |
| sites-gateway | `nginxinc/nginx-unprivileged:1.29-alpine` | 18088 (`SITES_GATEWAY_PORT`) | 128m | always | read-only rootfs; serves `/<slug>/` from the API `/sites` endpoint (ADR 0009); `API_UPSTREAM` default `host.docker.internal:8080` (l.78-102) |
| forgejo | `codeberg.org/forgejo/forgejo:11-rootless` | 13000 (`FORGEJO_PORT`) | 512m | medium, full | Git for code projects; no SSH, no sign-up, no Actions (l.104-136) |
| verdaccio | `verdaccio/verdaccio:6` | 14873 | 384m | medium, full | allow-list npm mirror on the internal `build` network (l.138-150) |
| appdb | `postgres:17.6` | 15434 | 512m | full | database server only for generated apps (l.152-172) |
| apps-gateway | nginx-unprivileged | 18090 | 128m | full | (l.174-197) |
| keycloak | `quay.io/keycloak/keycloak:26.4` | 18080 | 1g | sso | optional OIDC/SSO test IdP; `scripts/sso-up.sh` (l.199-214) |

Networks: `default`, `build` (internal, no internet), `apps` (internal) (l.216-221). Volumes `studio-*` persist data across `down`.

Sum of the caps: always-on services 2816 MiB (2.75 GiB); medium 3712 MiB; full 4352 MiB (computed from the table; keycloak excluded). These are upper bounds per container, not measured usage.

### 4.2 `compose.public.yml` (public pilot, project `hblpub`)
Separate compose project with its own volumes and ports; every password comes from the generated, git-ignored `.run/public/public.env` (src: `compose.public.yml:1-4`). Started by `scripts/public-up.sh`.

| Service | Host port (default) | mem_limit | Notes |
|---|---|---|---|
| postgres | 25432 (`PG_PORT`) | 1g | db `studio` (l.7-17) |
| redis | 26379 (`REDIS_PORT_PUBLIC`) | 384m | `requirepass`, AOF, `noeviction` (l.19-27) |
| minio | 29000 (`MINIO_PORT_PUBLIC`) | 512m | console 9001 NOT published; CORS only from `PUBLIC_ORIGIN` (l.29-40) |
| rabbitmq | 25674 (`RABBITMQ_PORT_PUBLIC`) | 768m | management UI not published (l.42-51) |
| sites-gateway | 28088 (`SITES_GATEWAY_PORT`) | 128m | GET/HEAD only toward `API_PORT` (default 18081) (l.53-68) |
| portal-gateway | 3210 (`PORTAL_GATEWAY_PORT`) | 128m | one origin for the three portal hostnames, routed by Host; http to https redirect; 404 for any other host (D-C0-39) (l.70-91) |

Sum of caps: 2944 MiB (computed).

### 4.3 Stack profiles and what is unavailable
Features whose services are not started are reported unavailable by the API (src: `scripts/run-local.sh:11-12`). Server apps additionally need the admin policy `server-apps.enabled` (HIGH risk, default off) and, in production, `SECRETS_MASTER_KEY`, `APPS_GATEWAY_TOKEN`, `APPDB_ADMIN_PASSWORD` (src: `docs/DEPLOYMENT.md:12`, `docs/DEPLOYMENT.md:19-20`).

---

## 5. Port map

Everything binds to loopback. "Default" = value in the script or compose file; every one is overridable by environment.

| Component | Port | Source |
|---|---|---|
| API (`bootRun` / local) | 8080 (`SERVER_PORT`) | `application.yml:60`, `.env.example` |
| Legacy root UI | 3100 (`FRONTEND_PORT`) | `scripts/_env.sh:27` |
| Platform / Admin / Studio portals (local, `PORTALS=1`) | 3001 / 3002 / 3003 (`PORTAL_PLATFORM_PORT` ...) | `scripts/_env.sh:31`, `scripts/portals.sh:8` |
| Local data target (TLS PostgreSQL) | 15440 (`DATA_TARGET_PORT`) | `scripts/_env.sh:32` |
| Render worker (local) | 18095 (`RENDER_PORT`) | `scripts/_env.sh:60` |
| Sites gateway (local) | 18088 | `compose.yml:88` |
| PostgreSQL / Redis / MinIO / RabbitMQ (local) | 15432 / 16379 / 19000+19001 / 15674+15675 | `compose.yml` |
| Forgejo / Verdaccio / apps DB / apps gateway / Keycloak | 13000 / 14873 / 15434 / 18090 / 18080 | `compose.yml` |
| Public API (prod profile, `java -jar`) | 18081 (`API_PORT`) | `scripts/public-up.sh:22` |
| Public portals (platform / admin / studio, `next start`) | 3201 / 3202 / 3203 | `compose.public.yml:76-78`, `scripts/watchdog.sh:22` |
| Public portal gateway | 3210 | `compose.public.yml:84` |
| Public legacy single UI (not published since D-C0-39) | 3200 | `scripts/public-up.sh:23`, `docs/PUBLIC_DEPLOYMENT.md:3-4` |
| Public sites gateway / MinIO / PostgreSQL / Redis / RabbitMQ | 28088 / 29000 / 25432 / 26379 / 25674 | `compose.public.yml` |
| Public render worker | 28095 | `scripts/watchdog.sh:12` |
| Isolated E2E stack defaults (`e2e-stack.sh`) | API 38080, PG 35432, Redis 36379, MinIO 39000, RabbitMQ 35672, sites 38088, render 38095, Studio 3003, Platform 3001, Admin 3002 | `docs/parallel/c5/e2e-stack.sh:26-28` |
| Isolated RC stack `c0rc` (a C0 allocation, not a default) | API 47300, PG 47301, Redis 47302, MinIO 47303, RabbitMQ 47304, sites 47305, render 47306, Studio 47307, Platform 47308, Admin 47309 | `docs/parallel/DECISIONS.md` D-C0-56, D-C0-59 |

Disagreements between documents (the code default wins):
- `docs/parallel/c0/PORTAL_LIFECYCLE.md` and `docs/parallel/c0/PROCESS_SAFETY.md:30` speak of local portals on 3301-3303; the code default is 3001-3003 (`scripts/portals.sh:8`). `docs/parallel/V1_LOCAL_TARGET.md:120` says the portals were "proven on 13001-13003" because 3003 was held by another session. Ports are configuration (`PORTAL_*_PORT`); 3001-3003, 3201-3203, 3301-3303 and 4000 are treated as "someone else's" by process-safety (section 8).
- `docs/parallel/c0/DEMO_STACK_RUNBOOK.md` and the `e2e-stack.sh` defaults (Studio 3003, Platform 3001, Admin 3002) collide with the local portal defaults: always set the `E2E_*_PORT` variables for an isolated stack (the runbook says "the defaults are shared with other agents").

---

## 6. Isolated E2E / release-candidate (RC) stack

Purpose: a real, isolated stack (its own containers, ports, state dir) for demos and real-backend E2E; "never the public or the default local stack" (src: `docs/parallel/c0/DEMO_STACK_RUNBOOK.md:3`). Tool: `docs/parallel/c5/e2e-stack.sh` (owner C5, reconciled by C0 in D-C0-55). Every process is started through the owned-process CLI `tests/lib/owned-process-cli.mjs` (own process group; pid + start time + command recorded in `$DIR/run/*.json`) and signalled only after that identity is re-validated (src: `e2e-stack.sh:3-5`).

### 6.1 Bring up from an exact SHA
```bash
git -C <checkout> checkout --detach <SHA>              # the portals are built from THIS checkout
export E2E_STACK_NAME=c0rc E2E_BASE_REF=<SHA>          # backend worktree is created from the same SHA
export E2E_API_PORT=.. E2E_PG_PORT=.. E2E_REDIS_PORT=.. E2E_MINIO_PORT=.. E2E_RABBIT_PORT=.. \
       E2E_SITES_PORT=.. E2E_RENDER_PORT=.. E2E_STUDIO_PORT=.. E2E_PLATFORM_PORT=.. E2E_ADMIN_PORT=..   # free ports
export E2E_ORG_PERSISTENCE=true E2E_PUBLISH_CONFIGS=true E2E_SITES_PUBLIC_DATA=true E2E_PORTALS=1
export E2E_DATA_TARGET=1                                # only for data-backed journeys (needs scripts/data-target.sh up first)
docs/parallel/c5/e2e-stack.sh up
```
(src: `DEMO_STACK_RUNBOOK.md:5-17`, `e2e-stack.sh:7-14`)

`up` = `pin_check` (refuses a stack whose backend SHA and portal checkout differ; `E2E_ALLOW_SKEW=1` overrides knowingly) -> `data_target_check` -> `prepare` (detached backend worktree at `$DIR/backend-worktree`, random-secret `stack.env` mode 600) -> `infra_up` -> `backend_up` (Gradle `bootRun` with `-Xmx3g` and `-Pkotlin.daemon.jvmargs=-Xmx3g`, first compile about 1.5 min) -> `render_up` -> `studio_up` -> `portals_up` (if `E2E_PORTALS=1`) -> `serving_record` -> `status` (src: `e2e-stack.sh:239`, `:124`). Default state directory `$HOME/.xweb-e2e-stack/<name>` (override `E2E_STACK_DIR`) (src: `e2e-stack.sh:22`).

Other commands: `prepare`, `infra-up`, `backend-up|launch|down|restart|pause|resume`, `store-pause|resume` (pauses MinIO), `render-up|pause|resume`, `studio-up`, `portals-up|down`, `e2e [flows]`, `status`, `down [--infra] [--worktree]` (src: `e2e-stack.sh:7-12`).

Flags in an isolated stack (this stack only; application and public-API defaults stay OFF): `E2E_ORG_PERSISTENCE` -> `ORGANIZATION_PERSISTENCE_ENABLED`; `E2E_PUBLISH_CONFIGS` -> `PUBLISH_CONFIGS_ENABLED`; `E2E_SITES_PUBLIC_DATA` -> `SITES_PUBLIC_DATA_ENABLED` + `SITES_DATA_API_BASE=http://127.0.0.1:<sites port>/{slug}/_data` unless `E2E_SITES_DATA_API_BASE` is given; `E2E_DATA_TARGET=1` -> trust store in the API JVM via `JAVA_TOOL_OPTIONS` and `DATA_PLATFORM_POSTGRES_ALLOWED_PRIVATE=127.0.0.1:15440` (src: `e2e-stack.sh:92-98`, `DEMO_STACK_RUNBOOK.md:15-19`, D-C0-57 item 2, D-C0-58 item 3). The sites gateway container gets `GATEWAY_FORCE_HTTPS=0` and `GATEWAY_REAL_IP_FROM` (nginx template refuses to start without them) (src: `e2e-stack.sh:115`).

Infrastructure containers of the isolated stack are started with plain `docker run` named `<stack>-pg|redis|minio|rabbit|sites` and carry NO memory limit (src: `e2e-stack.sh:109-118`), unlike `compose.yml`. This matters for host capacity (section 9).

Credentials: the `local.admin` password is in `$DIR/stack.env` (mode 600); never print it. Data-target role passwords are in `.run/data-target/{ro,rw}.pw` of the main checkout (src: D-C0-59 item 2).

### 6.2 Build stamp and SHA proof
- `serving_record` writes `$DIR/SERVING.json`: stack name, pinned SHA, `repoHead`, `backendWorktreeHead`, the build id of each portal, sha256 of the sites-gateway template, data-target info, ports and the three flags (src: `e2e-stack.sh:207-214`).
- `node scripts/rc-verify.mjs --stack <name> --sha <40-hex> [--public] [--out file]` re-checks every component against LIVE evidence: the stamp, backend worktree HEAD, API process cwd, readiness, the migration level (expects max version 33 with V31 a gap, `scripts/rc-verify.mjs:40`), render worker, each portal process and its served build id, the sites-gateway container mount, and the flags. Exit 0 only when all match; the public API is reported separately and is not part of the RC while D-C0-57 says DEFER (src: `scripts/rc-verify.mjs:1-12`, `:71-77`).
- Procedure for a final RC: (1) pick `{{FINAL_RC_SHA}}`; (2) tear down every older stack of yours; (3) `e2e-stack.sh up` as above; (4) `node scripts/rc-verify.mjs --stack <name> --sha {{FINAL_RC_SHA}} --out $HOME/.xweb-e2e-stack/<name>/RC_STAMP.json`; (5) hand the stack to QA (`QA_FINAL.md`); (6) `down --infra --worktree` when finished.
- **Migration expectation.** The script pins the expected migration level: `add("migrations applied (max version / count)", "33 / (V31 is a gap)", mig, /^33\//.test(mig))` (src: `scripts/rc-verify.mjs:40`; updated together with V33 in commit `b3fcb53`, D-C0-61 item 7). The next migration (V34 or later) requires updating this line in the same commit. The earlier candidate-1 proof (17 PASS) was made with the version-32 expectation.
- A one-time run of the SHA proof on candidate 1 (migration expectation 32 at that time) produced 17 PASS and `SHA_ALL_MATCH YES` (src: `docs/parallel/DECISIONS.md` D-C0-59 item 3); that record is dated 2026-10-10 and is for the SHA named there, not for `{{FINAL_RC_SHA}}`.

### 6.3 Heavy-gate window
After the host-starvation incident (section 9) the heavy backend gates for candidate 1 were run only in a quiet window (22:30-06:00), one after another, by a script called `rc-window.sh` with a resource precheck and a public-latency guard (src: D-C0-58 item 4). `rc-window.sh` is not in the repository (checked: absent from `scripts/` on `integration/v2`): where it lives is UNVERIFIED. Its checks are restated as rules in section 9.2.

---

## 7. Public pinned deployment

Topology (src: `docs/PUBLIC_DEPLOYMENT.md:3-17`):
```
Internet -> Cloudflare -> tunnel hbl-studio -> 127.0.0.1:3210 portal gateway (nginx, routes by Host, http->https 308)
   platform.<domain> -> 127.0.0.1:3201 Platform portal (next start)  \
   admin.<domain>    -> 127.0.0.1:3202 Admin portal                    > same-origin /api, /oauth2, /login/oauth2 -> 127.0.0.1:18081 API (prod profile)
   studio.<domain>   -> 127.0.0.1:3203 Studio portal                  /
 sites.<domain>      -> 127.0.0.1:28088 sites gateway -> API /sites/**
 studio-files.<domain> -> 127.0.0.1:29000 MinIO (presigned URLs only)
```
Default hostnames baked into `compose.public.yml` and `public-up.sh` belong to the pilot owner's domain (`*.toolsmcp.uk`); change `PUBLIC_*_HOST` in `.run/public/public.env` before the first run. A dedicated tunnel `hbl-studio` is used; the pre-existing `gemma` tunnel and `~/.cloudflared/config.yml` are not touched (src: `docs/PUBLIC_DEPLOYMENT.md:26`). One portal = one hostname; sessions are per origin (host-only cookies): signing in on one portal does not sign in on another (B-C0-WEB-01).

Principle: **process recovery is not deployment.** What the public portals and the public API serve is an explicit, approved, immutable release; a crash, restart, `up`, the watchdog or the working tree cannot change it (src: `docs/parallel/c0/PUBLIC_DEPLOYMENT_PINNING.md:3`, `docs/parallel/c0/PUBLIC_API_PINNING.md:3`).

### 7.1 Three SHAs, never confused (portals)
`INTEGRATION_HEAD` (informational), `APPROVED_PUBLIC_SOURCE` (`.run/public/approved.json` -> `releases/<id>/release.json`), `RUNNING_PUBLIC_SOURCE` (the process's own recorded identity). Running behind integration is a normal state (`CURRENT_APPROVED (BEHIND_INTEGRATION +N)`) and never a reason to deploy (src: `PUBLIC_DEPLOYMENT_PINNING.md:9-16`).

### 7.2 Public portals: `scripts/public-portals.sh`
| Command | Effect | Never |
|---|---|---|
| `status [--json]` | state per portal; exit 0 only if all `CURRENT_APPROVED` | changes anything |
| `up` | starts the APPROVED release where not running | builds; uses HEAD; starts with no approved release (`NO_APPROVED_RELEASE`, exit 5) |
| `restart` / `down` | same approved release / stops only what this tool started | rebuilds; touches a foreign listener |
| `deploy <sha\|ref>` | snapshot (git archive) -> deps -> build candidate -> prove it on temporary ports (`/login` 200, served asset, CSP, API proxy) -> move the approved pointer (atomic) -> replace running portals -> automatic rollback if the real-port start fails | moves the pointer before the candidate is proven |
| `rollback [id]` | explicit return to the previous known-good | deletes the artifact |
| `init --from-running [--dry-run]` | pins what runs now, from evidence, without restarting | accepts what it cannot prove |
| `releases`, `prune`, `verify` | list / drop unreferenced / deep-verify | delete approved or previous known-good |

(src: `PUBLIC_DEPLOYMENT_PINNING.md:36-47`). Exit codes: 0 ok, 1 failed/not current, 3 `FOREIGN_PROCESS`, 5 `NO_APPROVED_RELEASE`, 6 `APPROVED_BUILD_MISSING` / `RELEASE_TAMPERED`, 7 candidate/activation/deps/snapshot failure, 8 `LOCKED`, 64 usage. Release id = `<sha12>-<cfg8>` (same SHA under another config is a different release); metadata holds only an allow-listed set of non-secret keys of `public.env`; retention = approved + previous known-good (src: lines 30-34). Fail closed: no approved release, missing/tampered artifact, or a foreign process on a public port means nothing starts and nothing is killed (lines 51-55).

Limits (stated in the source): activation is stop-then-start per portal (seconds of refused connections, NOT zero-downtime); a failed candidate never moves the pointer; the runtime env of a process started before pinning cannot be read back from the OS, so adoption uses the current non-secret allow-list (src: lines 68-75).

### 7.3 Public API: `scripts/public-api.sh`
Layout `.run/public/api/{approved.json, releases/<sha12>-<cfg8>/{app.jar, release.json}, release.lock}` + `.run/public/api.owned.json`. The API process gets a clean environment (small OS base + pinned non-secret config + secrets of `public.env` read at start and never recorded). Commands: `status`, `up`, `restart`, `down`, `prepare-api <sha>` (snapshot, full backend tests, two independent clean builds that must be byte-identical, `release.json`), `reproduce-api`, `validate-api`, `verify-running-api`, `deploy-api <sha> [--skip-tests] [--expand-only] [--reproduce] [--replace-legacy-unverified]`, `rollback-api [id]`, `init-api --from-running`, `releases-api`, `prune-api`, `verify-api` (src: `PUBLIC_API_PINNING.md:10-40`).

`deploy-api` order: tests -> candidate on a temporary port against a scratch database (default: an EMPTY database, Flyway applies every migration from scratch; no production data copied) -> schema verdict against the real database -> stop current API -> start artifact on 18081 -> prove the process IS the release -> only then write `approved.json`; on failure the old process is restored and the pointer has not moved (src: lines 35, 68-77). The window is about 10 s (not zero-downtime). Candidate smoke limitations: `WORKFLOW_ENABLED=false` and RabbitMQ listener auto-startup off, so the workflow consumer path and data-dependent migrations are not exercised before activation (lines 74, 168).

Rollback policy: an artifact rollback is allowed only while the database is not ahead of the target artifact, or every migration it is ahead by was declared expand-only by the release that introduced it (`deploy-api --expand-only`). Default is NOT expand-only: after a release that adds a migration `rollback-api` fails closed with `ROLLBACK_SCHEMA_INCOMPATIBLE`. Down migrations are not implemented (lines 50-66). Verdicts: `COMPATIBLE`, `ROLLBACK_SCHEMA_INCOMPATIBLE`, `FLYWAY_CHECKSUM_MISMATCH`, `SCHEMA_DIRTY`, `SCHEMA_CHECK_UNAVAILABLE`.

### 7.4 Current public state (dated records, not facts about `{{INTEGRATION_SHA}}`)
- Public portals: approved release `{{PUBLIC_FRONTEND_SHA}}`. On 2026-10-10 the portals were deployed from `bc5c47f292d0` as release `bc5c47f292d0-3f59e8d1` with previous known-good `09f27e5269d2-3f59e8d1` (src: D-C0-56 item 2).
- Public API: approved release `{{PUBLIC_API_SHA}}`. On 2026-10-10 it was `1006cbf441f6-8985413d` and was explicitly NOT upgraded for the RC (D-C0-57, option B, DEFER). Consequence recorded there: the public deployment lacks M-052 (admin of a secondary tenant), `projectScopes` (project-only members), Dynamic Organization routes and H-C2-07 (409/422 publish answers); "public production readiness for those = NO". Final backend QA runs on the isolated RC stack, not on the public API. Upgrading is an explicit later act: `prepare-api` from the frozen SHA, `validate-api` on a scratch database, `deploy-api` (about 10 s downtime; Flyway V32+ on the public database, expand-only so `rollback-api` stays possible) (src: D-C0-57 item 4).
- Adoption of the then-running public API was refused (source not provable) and the first approved API release was created by an explicit hardened `deploy-api 1006cbf441f6 --replace-legacy-unverified`; the old jar is kept only as incident evidence (src: `PUBLIC_API_PINNING.md:82-86`).

### 7.5 Pilot operation and limits
`./scripts/public-up.sh` (first run generates `.run/public/public.env` with random secrets and creates the tunnel and DNS), `public-status.sh`, `public-down.sh [--infra]`, `set-openrouter-key.sh` (silent key entry), optional `public-launchd.sh install` (not done for you) (src: `docs/PUBLIC_DEPLOYMENT.md:28-36`). The watchdog (`scripts/watchdog.sh <local|public>`): every 30 s checks API liveness, the UI and the render worker; recovery = `public-portals.sh up` + `public-api.sh up`; at most `WATCHDOG_MAX_RESTARTS` (5) recoveries per `WATCHDOG_WINDOW` (900 s) with back-off 30 -> 600 s, then a `crash-loop.json` marker (`CRASH_LOOP`) and no further restarts; it has no code path that builds or deploys (src: `scripts/watchdog.sh:1-14`, `PUBLIC_DEPLOYMENT_PINNING.md:59-62`).
Limits: works only while the host is on, awake, online and Docker/processes run (`public-up.sh` starts `caffeinate -i -s`; closing the lid on battery still sleeps it); single node, no HA; all data on one laptop disk (schedule backups); public sign-up is off in the pilot posture (src: `docs/PUBLIC_DEPLOYMENT.md:57-62`, `docs/IMPLEMENTATION_STATUS.md:42`).

### 7.6 Local portal lifecycle (source-aware, not pinned)
`scripts/portals.sh up|down|restart|status|build [--force-rebuild]`: fingerprints the build inputs (content, not commits), builds into `apps/<p>/.next-local-<fp12>`, keeps a process that already runs that exact build, restarts only stale processes THIS script started, rolls back a failed start; a port used by a foreign process is refused with exit 2 and never killed (src: `docs/parallel/c0/PORTAL_LIFECYCLE.md`). Tests: `npm run test:infra:portals`.

---

## 8. Process-safety rules (shared machine)

Several checkouts and agents run stacks, builds and test servers on ONE machine. Incident 2026-10-08: `pkill -f "gradlew"` ended another stack's backend and `pkill -f "next start"` (six times) ended the public and local Next portals (src: `docs/parallel/c0/PROCESS_SAFETY.md:3-4`).

**Rule: never kill by name or by port alone.** Forbidden: `pkill -f ...`, `pkill <name>`, `killall <name>`, `kill $(pgrep ...)`, `ps | grep | xargs kill`, `kill $(lsof -ti tcp:P)`, `lsof -ti ... | xargs kill`, `fuser -k`, `kill 0`, `kill -1`. Allowed: terminate only what you can PROVE you started - a pid captured at launch, a process group you created, children of a known parent (`pkill -TERM -P "$pid"`), or the owned-process helper; `docker stop|kill <exact container name>` (src: `PROCESS_SAFETY.md:7-8`, `:29`; `CLAUDE.md`).

Helper: `scripts/lib/owned-process.mjs` (library), `scripts/owned-process.mjs` (CLI), `scripts/lib/owned-process.sh` (shell). C5 keeps an equivalent copy of the same model in `tests/lib/owned-process.mjs` / `tests/lib/owned-process-cli.mjs` (used by `docs/parallel/c5/e2e-stack.sh` and `tests/browser/harness-server.mjs`; it also re-validates pid + start time + command before every signal) (src: `docs/parallel/c5/PROCESS_SAFETY.md`). Identity of an owned process = pid AND start time (`ps -o lstart=`) AND full command AND cwd; `kill -0` is never trusted; a recorded identity that no longer matches is `REUSED` and is not signalled. Processes start detached (own process group); a port is stopped only if its listener is the recorded process or (with `--cwd-under`) a process whose cwd is inside that directory, otherwise `FOREIGN_PROCESS` (exit 3). Exit codes: 0 ok, 1 failed, 2 reused/stale, 3 FOREIGN_PROCESS, 4 PORT_BUSY, 64 usage (src: `PROCESS_SAFETY.md:32-38`). Examples:
```bash
. scripts/lib/owned-process.sh
op_start me web .run/me-web.json --port 4100 --ready-port 4100 -- python3 -m http.server 4100 --bind 127.0.0.1
op_status .run/me-web.json ; op_stop .run/me-web.json
op_stop_port 8080 --cwd-under "$ROOT"          # what scripts/stop-local.sh uses
```
Use your OWN ports (not 3001-3003, 3201-3203, 3301-3303, 4000 unless you started them), check a port is free before starting, never "clean up" before a test, only after your own start (src: line 30). The Gradle daemon is shared by every checkout and is never stopped by the scripts (src: `scripts/stop-local.sh:6`).

Enforcement: `tests/guards/process-safety.mjs` (part of `npm run guard:static` and `npm run gate:frontend`) fails on forbidden patterns in committed code under `scripts/ tests/ e2e/ workers/ infra/ tooling/`; one justified exception syntax `# guard-allow: PROCESS-SAFETY — <reason>`. It does NOT cover hand-typed commands, `/tmp`, transcripts, other worktrees or docs; the policy covers those (src: `PROCESS_SAFETY.md:40-42`). `tests/infra/owned-process.test.mjs` (`npm run test:infra:processes`) proves with real processes that what the helper does not own survives. The C5 note `docs/parallel/c5/PROCESS_SAFETY.md` documents the same model for the C5 copy of the helper and the inventory of unsafe patterns it replaced.

Other safety constraints in force: do not touch the existing `gemma` Cloudflare tunnel; do not push, merge `main`, `push --force`, `reset --hard` or `clean -fd` (section 15).

---

## 9. Resource limits and the host-starvation incident

### 9.1 INC-2026-10-10 (record)
Symptom: a new account "could not log in to the public Studio" with `Máy chủ không phản hồi kịp...`. Findings: the message is the CLIENT's own 15 s `AbortSignal.timeout` (`packages/api-client/src/core.ts`, code `TIMEOUT`), not a 401/403; old accounts hit it too (login 11.9-18.7 s). Cause: the SHARED Mac / Docker VM was starved - 16 GB RAM, swap 17.2 of 18.4 GB used, load 30-50, **106 containers in an 8 GB Docker VM** (7 stale `c5e2e-*` stacks, `c5fx`, C0's `c0rc` and `c0demo` stacks, and the Testcontainers of full backend runs). The public PostgreSQL went through crash recovery (`FATAL: the database system is in recovery mode`), Redis commands timed out after 2 s, HikariPool starved (`total=3 active=3`), requests took 10-28 s. The windows matched the heavy workloads (stack up/flows, three full backend runs). C0 stopped its runs and tore down `c0rc` and `c0demo`: containers 106 -> 84, swap 16.0 -> 12.5 GB. Status: MITIGATED (C0 footprint removed); open items: login-timeout wording for non-writes (C5), host capacity (other owners' stacks), public Postgres / Redis isolation (src: `docs/parallel/BLOCKERS.md:88`; commit `ef5989e`).

Separate finding in the same incident: the newest accounts had no workspace and no project membership, so Studio admission was refused by design (account creation alone grants no project access).

### 9.2 Operating rules derived from the incident
The corpus defines no numeric cap and no automatic enforcement; the following rules are derived from the incident and from what C0 did in practice. Machine facts at the time of the final gates (reported by the coordinator, 2026-10-11): Docker VM memory about 8 GB; host swap about 90% used and load about 5-10 while gates ran; a full backend run on the merged C1 tree at load about 10 produced one load-induced failure (`LockdownSettingsTests`, 503 on register, 5/5 pass alone) (src: D-C0-60 item 3).
1. **Run heavy gates sequentially. Never overlap the full backend run with the browser harness** (or with a second Gradle run, a Next production build or a stack bring-up): a full backend run (Gradle + Testcontainers: postgres, redis, minio, rabbitmq, sometimes forgejo) plus a browser matrix is what starved the host. Never two Gradle test runs or two browser jobs concurrently (src: D-C0-58 item 4 "strictly one after another"; `docs/parallel/WORKTREE_SETUP.md:49` "only one stack at a time").
2. **At most one isolated RC stack of yours at a time** (D-C0-59 item 2: "ONE stack `c0rc` ... `c0demo` was not started; no other stack of C0 exists"). Tear it down (`down --infra --worktree`) when the work is done; stale stacks are the first cause of starvation.
3. **Precheck RAM / swap / load / container count before every heavy job** and record the numbers with the run (`free`-style RAM, `sysctl vm.swapusage`, `uptime`, `docker ps | wc -l`, public API latency, PostgreSQL not in recovery). Reference values: the passing precheck of 2026-10-10 22:34 was swap 6.8 GB, load 1.8, 30 containers, public API 0.26 s (src: D-C0-58 item 4); the incident values were swap 17 of 18 GB, load 30-50, 106 containers. A run started with swap near full or load far above the core count is noisy: mass `NoClassDefFoundError` from `IntegrationTestBase` container start-up (336 false failures in one C3 run, finding F-5) and load-induced timing failures were observed there, so do not read them as product failures and re-run on a quieter host.
4. **Know your memory budget** (Docker VM about 8 GB; swap was about 90% used when the final gates ran): compose caps are 2.75 / 3.6 / 4.25 GiB for lean / medium / full (section 4.1) and 2.9 GiB for the public compose; the isolated-stack containers have no cap; Gradle daemon heap is 2 GB and the Kotlin daemon 3 GB (`backend/gradle.properties`); the test JVM is capped at 2 GB; the API under `bootRun` is a further JVM; the Docker VM in the incident had 8 GB.
5. Public stack Postgres/Redis share the Docker VM with every other container: a starved VM can crash-recover the public database. Isolation of the public data services is an open item (BLOCKERS INC row).
6. Heavy work during demos or public use: run it in a quiet window (C0 used 22:30-06:00 local) and stop it if public latency rises (src: D-C0-58 item 4).

---

## 10. Backup and disaster recovery (summary; details in [BACKUP_DR](BACKUP_DR.md), [BACKUP_OPERATIONS](BACKUP_OPERATIONS.md), [DISASTER_RECOVERY](DISASTER_RECOVERY.md))

| Store | Protection | Script |
|---|---|---|
| PostgreSQL (authoritative) | logical dump `pg_dump -Fc` + checksum + atomic publish + count-protected retention; optional WAL archiving / PITR | `scripts/backup-postgres.sh`, `scripts/restore-postgres.sh --target-db NAME [DUMP]`, `infra/postgres-pitr/`, `scripts/pitr-drill.sh` |
| MinIO / S3 (asset and artifact bytes) | versioning + mirror (dir or another S3) + `MANIFEST.sha256`; single-object restore from a previous version | `scripts/backup-minio.sh`, `scripts/restore-minio.sh` |
| Redis, RabbitMQ | not backed up by design (sessions/counters disposable; durable queues + recovery sweeper re-publish from PostgreSQL) | - |
| Whole platform | platform DB + every app DB (+ roles) + MinIO + Forgejo; verifies each; prunes (`BACKUP_RETENTION_DAYS` 14, `BACKUP_KEEP_MIN` 3); writes `backups/<env>/status.json` | `scripts/backup-all.sh <local\|public>` |
| Restore drill | restores the latest backup files into a throwaway no-network `postgres:17.6`, checks schema version, counts, manifests, writes `drill.json` | `scripts/restore-drill-all.sh`, `scripts/test-backup-restore.sh`, `scripts/test-minio-backup.sh` |
| Scheduler | optional detached daemon: daily at `BACKUP_HOUR_UTC` (default 19 = 02:00 Vietnam), drill weekly (Sundays) or `BACKUP_DRILL=always` | `scripts/backup-daemon.sh`, enabled by `BACKUP_DAEMON=true` in `run-local.sh` |

Restore order: PostgreSQL -> MinIO -> start the API (Flyway validates) -> Redis/RabbitMQ start empty -> the sweeper re-publishes unfinished deployments. Restore PostgreSQL and MinIO to compatible points in time (src: `docs/BACKUP_DR.md:64-66`). Before any migration on a live database take a verified backup (`./scripts/backup-all.sh public`); recovery for an undone release is to restore the pre-migration dump into a NEW database and point `DATABASE_URL` at it; a failed migration leaves Flyway marked failed: restore, do not repair history (src: `docs/DEPLOYMENT.md:34-39`).

Targets (configured goals, not guarantees; measured only on tiny databases): PostgreSQL RPO <= 5 min with PITR / <= 24 h with daily dumps, RTO <= 1 h; MinIO RPO <= 24 h, RTO <= 2 h (src: `docs/DISASTER_RECOVERY.md:5-12`). Admin -> Sao luu and Health show last success per component and alert when stale (> 26 h), failed, or the drill is failing / older than 8 days (`BACKUP_STATUS_DIRS`, `BACKUP_MAX_AGE_HOURS`) (src: `docs/BACKUP_DR.md:86`, `application.yml:173-175`).

Honest gaps (src: `docs/BACKUP_DR.md:68-78`, `:87-90`): no scheduler installed by default; **off-host copy is BLOCKED_EXTERNAL_INPUT** (`OFFSITE_S3_URL`, `OFFSITE_S3_ACCESS_KEY`, `OFFSITE_S3_SECRET_KEY`, `OFFSITE_S3_BUCKET`; production requires it); backups are not encrypted; PITR is not enabled on the dev/pilot Postgres; no MinIO lifecycle rule or object lock; no timed full-stack rehearsal; no RPO/RTO at realistic size; only macOS/Docker was exercised.

Documentation disagreement: `docs/DISASTER_RECOVERY.md:18` shows `restore-postgres.sh <dump> --target <db>`; the script's real usage is `--target-db NAME [DUMP_FILE]` (src: `scripts/restore-postgres.sh:4`). The script wins. `docs/DISASTER_RECOVERY.md:40` also states "No rollback of a RUNNING deployment is implemented", which predates the V30 rollback / unpublish work (src: `docs/parallel/c2/BATCH2_HANDOFF.md:27-40`).

---

## 11. Observability (details in [OBSERVABILITY](OBSERVABILITY.md))

- Health: `/actuator/health/liveness` (JVM up; use for restart decisions) and `/actuator/health/readiness` (includes `db, redis, rabbit, minio`; 503 while any is down); no details exposed (src: `application.yml:104-115`, `docs/OBSERVABILITY.md:3-9`). Graceful shutdown drains in-flight requests (`SHUTDOWN_TIMEOUT`, default 30 s).
- Metrics: off by default. Enable with `MANAGEMENT_ENDPOINTS=health,prometheus` and `METRICS_TOKEN` (16+ random chars); `/actuator/prometheus` accepts only `Authorization: Bearer <METRICS_TOKEN>`; block `/actuator/` at any public proxy. The Prometheus/Grafana snippet is untested configuration, not a verified deployment (src: `docs/OBSERVABILITY.md:11-22`).
- Traces: OpenTelemetry bridge and OTLP exporter on the classpath; sampling `TRACING_SAMPLING` default 0.0 (nothing exported); export to a real collector was not verified (src: lines 24-25).
- Logs: `prod` profile = structured JSON (logstash format, MDC `requestId`, `traceId`/`spanId`); one access line per request with the route pattern, never raw URL/query/headers/cookies/bodies; never logged: passwords, session/CSRF cookies, OIDC tokens, MinIO/DB credentials, prompts. Never set `org.springframework.web` to DEBUG in production (it logs JSON request bodies, including credential `PUT`s) (src: `docs/OBSERVABILITY.md:27-32`, `docs/parallel/c3/MANAGEMENT_API.md:118`).
- Audit: `audit_events` (PostgreSQL, append-only DB trigger; the app never deletes rows) is the "who did what" record and carries `request_id` (src: `application.yml:185`, `docs/OBSERVABILITY.md:31`).
- Admin console health shows five honest states (Healthy, Degraded, Unavailable, Unknown, Not configured) for API, PostgreSQL, Redis, RabbitMQ, MinIO, AI provider, OIDC, render worker, Git server, build runner, server runtime, backups (src: `docs/DEPLOYMENT.md:29-32`).

---

## 12. Environment variables

Names, purpose and default only. **No secret value appears here.** "Local-only default" means a development placeholder documented in `compose.yml` / `.env.example` that must never be reused (the secret scanner allow-lists exactly those, src: `.gitleaks.toml`). "REQUIRED (prod)" = the `prod` profile fails to start without it (src: `application-prod.yml`, `docs/DEPLOYMENT.md:14-21`). Defaults are from `backend/src/main/resources/application.yml` unless another source is named.

### 12.1 Core runtime
| Variable | Purpose | Default |
|---|---|---|
| `APP_PROFILE` / `SPRING_PROFILES_ACTIVE` | Spring profile (`local`, `prod`) | `default` (`_env.sh` sets `local`) |
| `SERVER_ADDRESS`, `SERVER_PORT` | bind address / port | prod: REQUIRED address; port 8080 |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | PostgreSQL | URL REQUIRED (local profile defaults to `127.0.0.1:15432`); user `studio`; password local-only default |
| `DB_POOL_MAX`, `DB_POOL_MIN`, `DB_LEAK_DETECTION_MS` | Hikari pool | 10 / 2 / 0 |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | sessions, rate limits | REQUIRED; password REQUIRED in prod |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | queues | REQUIRED; user `studio` |
| `PUBLISH_WORKER_CONCURRENCY` | publish listeners | 2 |
| `MINIO_ENDPOINT`, `MINIO_PUBLIC_ENDPOINT`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `MINIO_BUCKET`, `MINIO_ARTIFACTS_BUCKET` | object storage; public endpoint (https in prod) is the browser-facing one | endpoint REQUIRED; buckets `studio-assets`, `studio-artifacts` |
| `SHUTDOWN_TIMEOUT` | graceful drain | 30s |
| `TOMCAT_MAX_THREADS`, `TOMCAT_ACCEPT_COUNT`, `TOMCAT_MAX_CONNECTIONS` | HTTP limits | 64 / 100 / 2048 |
| `MAX_REQUEST_BYTES`, `MAX_PROJECTS_PER_WORKSPACE`, `MAX_ASSETS_PER_PROJECT` | limits | 1048576 / 1000 / 200 (`_env.sh` raises projects to 100000 locally) |
| `TRUST_PROXY`, `TRUSTED_PROXY_CIDRS` | honour `X-Forwarded-*` only if true AND the TCP peer is in the CIDRs | false / empty; prod: `TRUST_PROXY` REQUIRED |
| `COOKIE_SECURE`, `SESSION_COOKIE_NAME`, `SESSION_TIMEOUT_MINUTES` | session cookie | true (forced true in prod) / `STUDIO_SESSION` / 480 |
| `CORS_ALLOWED_ORIGINS` | exact origins, no `*`, no path; prod https and no localhost | prod REQUIRED |
| `WEB_ORIGIN_PLATFORM`, `WEB_ORIGIN_ADMIN`, `WEB_ORIGIN_STUDIO` | the three portal origins (declared in `app.web.origins`) | empty (local profile: 127.0.0.1:3001/3002/3003) |
| `MANAGEMENT_ENDPOINTS`, `METRICS_TOKEN`, `TRACING_SAMPLING`, `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | observability | `health` / empty / 0.0 / `http://127.0.0.1:4318/v1/traces` |
| `LOGIN_MAX_CONCURRENT_HASHES`, `LOGIN_QUEUE_WAIT_MS` | Argon2 concurrency | 4 / 5000 |
| `RATE_LIMIT_PROMPT_MAX`, `RATE_LIMIT_PUBLISH_MAX`, `RATE_LIMIT_UPLOAD_MAX` | per-user limits (login limits are fixed: 5 per user, 50 per IP, 900 s) | 30 / 10 / 60 |
| `FORMS_IP_SALT`, `FORMS_MAX_PER_VISITOR`, `FORMS_MAX_PER_SITE_PER_HOUR` | website forms | prod: salt REQUIRED; 5 / 200 |
| `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_PASSWORD` | first operator in prod (weak value refused) | empty |
| `LOCAL_ADMIN_PASSWORD` | local profile accounts; `_env.sh` aborts if empty | none (set in `.env`, 14+ chars) |
| `LOCAL_LOGIN_ENABLED` | password login | true |

### 12.2 Identity
`OIDC_ENABLED` (false), `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, `OIDC_SCOPES` (`openid,profile,email`), `OIDC_REDIRECT_URI` (empty = `{baseUrl}/login/oauth2/code/oidc`), `OIDC_SUCCESS_URL` (`/`), `OIDC_AUTO_PROVISION` (true; new identities get NO access), `OIDC_LINK_BY_VERIFIED_EMAIL` (false), `OIDC_POST_LOGOUT_REDIRECT_URI`; `SAML_ENABLED` (false; via the OIDC broker only), `SAML_IDP_HINT`, `SAML_LABEL`; `SCIM_ENABLED` (false), `SCIM_TOKEN` (prod: 32 chars if enabled); `PUBLIC_SIGNUP_ENABLED` / `SIGNUP_ENABLED` (false), `SIGNUP_ALLOW_IN_PROD` (false), `SIGNUP_INVITE_CODE`, `SIGNUP_MAX_USERS` (500), `SIGNUP_IP_MAX_PER_HOUR` (5), `TENANCY_SYSTEM_ADMIN_BUSINESS_ACCESS` (false: SYSTEM_ADMIN is platform scope only). Known limit: only one portal origin can complete OIDC (B-C0-WEB-01, `docs/parallel/WEB_SECURITY_CONFIG.md:44-49`; see `KNOWN_LIMITATIONS.md`).

### 12.3 Feature switches (ALL default OFF; turning one on is a recorded decision, not a tweak)
| Variable | Property | Gates |
|---|---|---|
| `DATA_PLATFORM_ENABLED` | `app.data-platform.enabled` | every data source / query / mutation / management endpoint |
| `DATA_PLATFORM_WEBHOOKS_ENABLED` | `...webhooks-enabled` | webhook ingest handler (handler is a skeleton; route answers 404) |
| `WORKFLOW_ENABLED` | `app.workflow.enabled` | action / workflow / approval endpoints and the scheduler |
| `PUBLISH_CONFIGS_ENABLED` | `app.publish-configs.enabled` | publish-config API; real 409 `PUBLISH_POLICY_MISMATCH` / 422 `PUBLIC_DATA_NOT_APPROVED` |
| `SITES_PUBLIC_DATA_ENABLED` | `app.sites.public-data.enabled` | anonymous Public Runtime `POST /sites/{slug}/_data/queries/{id}/run` (mounted only together with `DATA_PLATFORM_ENABLED`) |
| `ORGANIZATION_PERSISTENCE_ENABLED` | `app.organization.persistence-enabled` | Dynamic Organization routes; OFF = `501 ORG_PERSISTENCE_NOT_AVAILABLE`; ON only in explicitly named isolated stacks |
| `AI_PLANNER_ENABLED`, `TENANT_AI_ENABLED` | `app.ai-planner.enabled`, `app.tenant-ai.enabled` | not wired (see `KNOWN_LIMITATIONS.md`) |
| `SERVER_APPS_ENABLED` | `app.runtime.enabled` | server apps runtime |
(src: `application.yml:198-235`, `docs/parallel/WEB_SECURITY_CONFIG.md:5-15`)

### 12.4 Data platform, workflow, deploy, sites
| Variable | Purpose | Default |
|---|---|---|
| `DATA_PLATFORM_POSTGRES_ALLOWED_PRIVATE` | exact `host:port` private PostgreSQL targets a data source may reach (no wildcard/CIDR); platform and apps DBs always denied | empty in base and prod; local profile `127.0.0.1:15440` |
| `DATA_PLATFORM_POSTGRES_DENIED` | extra denied hosts (deny wins) | empty |
| `DATA_PLATFORM_IDEMPOTENCY_RETENTION`, `DATA_PLATFORM_IDEMPOTENCY_PURGE_DELAY_MS` | mutation idempotency retention (never below P7D) | P30D / 3600000 |
| `DATA_MANAGEMENT_MAX_BODY_BYTES` | management API body cap | 65536 |
| `WORKFLOW_RUN_STORE` | `jdbc` (only production choice) or `memory` (dev) | jdbc |
| `WORKFLOW_QUEUE` | `amqp` (only production choice) or `memory` (dev, refused in prod) | unset: amqp in prod, memory otherwise |
| `WORKFLOW_ALLOW_VOLATILE_STORES` | dev only with `memory` store | false |
| `WORKFLOW_STALE_AFTER`, `WORKFLOW_ACTION_RUN_STALE_AFTER`, `WORKFLOW_ACTION_RUN_SWEEP_DELAY_MS`, `WORKFLOW_WORKER_ID`, `WORKFLOW_WORKER_DELAY_MS` | leases and sweeper | PT2M / PT10M / 30000 / generated / 500 |
| `ORGANIZATION_STRUCTURAL_LOCK_TIMEOUT_MS` | bound of structural lock waits; exceeded = 503 `ORG_STRUCTURE_BUSY` | 5000 |
| `DEPLOY_PROVIDER` | `mock` (nothing served; refused in prod) or `static` (real sites) | mock (`_env.sh` sets static locally) |
| `DEPLOY_SCOPE_LEASE_SECONDS`, `..._LEASE_MAX_SECONDS`, `..._WAIT_SECONDS`, `..._RETRY_MS`, `..._DUPLICATE_WAIT_SECONDS`, `..._LIFECYCLE_WAIT_SECONDS` | release-scope lease (V30) | 90 / 900 / 300 / 2000 / 60 / 10 |
| `SITES_ORIGIN`, `STUDIO_ORIGIN` | sites gateway / Studio browser-facing origins | prod REQUIRED |
| `SITES_DATA_API_BASE` | browser-facing Data API base for published apps (`{slug}` placeholder); prod https, not loopback/private | empty |
| `SITES_PUBLIC_DATA_MAX_BODY_BYTES`, `..._MAX_ROWS`, `..._MAX_RESPONSE_BYTES`, `..._IP_BURST`, `..._SITE_IP_PER_MINUTE`, `..._SITE_PER_MINUTE` | public runtime caps and rate limits | 16384 / 100 / 262144 / 30 / 120 / 1200 |
| `SITES_COOKIE_SECURE`, `SITE_EXTERNAL_LINK_DOMAINS` | private-site cookie, approved external nav hosts | false / empty |
| `RENDER_URL`, `RENDER_TOKEN`, `RENDER_PORT` | render worker | prod `RENDER_URL` REQUIRED; port 18095 locally |
| `BUILD_RUNNER_TOKEN`, `BUILD_API_BASE`, `BUILD_IMAGE` | build runner (empty token disables runner endpoints) | empty / local 127.0.0.1:8080 / pinned node image digest |
| `FORGEJO_URL`, `FORGEJO_TOKEN`, `FORGEJO_ADMIN_TOKEN`, `FORGEJO_PUBLIC_URL`, `FORGEJO_ORG`, `FORGEJO_BOT` | Git server | empty (code projects unavailable) / `factory` / `factory-bot` |
| `SECRETS_MASTER_KEY` | base64 of 32 bytes; encrypts all stored secrets (data-source credentials, project secrets) | empty (generated locally into `.run/appdb.env`) |
| `APPDB_URL`, `APPDB_ADMIN_USER`, `APPDB_ADMIN_PASSWORD`, `APPDB_HOST_FOR_APPS`, `APPS_GATEWAY_URL`, `APPS_GATEWAY_TOKEN` | server-app runtime | empty |
| `SOURCE_APPS_ENABLED`, `SOURCE_APP_BUILD_ENABLED`, `SOURCE_APP_PUBLIC_PUBLISH_ENABLED`, `PUBLIC_PUBLISH_ENABLED` | policy defaults (admin-editable at runtime) | true |
| `CLEANUP_ENABLED`, `CLEANUP_DRY_RUN`, `CLEANUP_INTERVAL_MS`, `CLEANUP_*_DAYS/HOURS` | retention jobs (audit rows are never deleted) | true / false / 3600000 / see yml |
| `BACKUP_STATUS_DIRS`, `BACKUP_MAX_AGE_HOURS` | backup monitor input | empty / 26 |

### 12.5 AI providers
`LLM_PROVIDER` (`auto`: OpenRouter when its key is set, else the built-in simulator; `mock`), `OPENROUTER_API_KEY`, `OPENROUTER_BASE_URL`, `OPENROUTER_REFERER`, `OPENROUTER_TIMEOUT_SECONDS` (45), `OPENROUTER_MAX_ATTEMPTS` (4), `AI_DAILY_LIMIT_PER_USER` (50), `OPENROUTER_MODEL_ALLOWLIST`; per provider `OPENAI_*`, `ANTHROPIC_*`, `GEMINI_*`, `LOCAL_LLM_*` (`..._API_KEY`, `..._BASE_URL`, `..._MODELS`; each model starts disabled until a system admin enables it); budgets `AI_DAILY_TOKEN_LIMIT_PER_USER`, `AI_MONTHLY_TOKEN_LIMIT_PER_WORKSPACE` (0 = off). Real provider calls were never exercised on the server (no keys): `OpenRouterLiveTests` are the 3 skipped tests (src: `docs/IMPLEMENTATION_STATUS.md:58`).

### 12.6 Scripts and tooling variables
`STACK_PROFILE` (lean|medium|full), `PORTALS` (1 = V1 local), `PORTAL_HOST`, `PORTAL_PLATFORM_PORT`, `PORTAL_ADMIN_PORT`, `PORTAL_STUDIO_PORT`, `DATA_TARGET_PORT` / `DATA_TARGET_CONTAINER` / `DATA_TARGET_IMAGE` / `DATA_TARGET_DB`, `NEXT_PUBLIC_API_MODE` (`mock` | `http`), `API_PROXY_TARGET`, `FRONTEND_PORT`, `BACKUP_DAEMON`, `WATCHDOG`, `WATCHDOG_INTERVAL|MAX_RESTARTS|WINDOW|BACKOFF_BASE|BACKOFF_MAX`, `BACKUP_DIR`, `BACKUP_RETENTION_DAYS`, `BACKUP_KEEP_MIN`, `BACKUP_HOUR_UTC`, `BACKUP_DRILL`, `POSTGRES_CONTAINER`, `PG_CLIENT_IMAGE`, `MINIO_BACKUP_TARGET_URL|ACCESS_KEY|SECRET_KEY|BUCKET`, `MINIO_MIRROR_REMOVE`, `MINIO_RESTORE_REMOVE`, `OFFSITE_S3_*`, `PREVIEW_CHROME_PATH`, public-stack names from `.run/public/public.env` (`PUBLIC_HOST`, `PUBLIC_FILES_HOST`, `PUBLIC_PLATFORM_HOST`, `PUBLIC_ADMIN_HOST`, `SITES_HOST`, `PORTAL_*_PORT_PUBLIC`, `PORTAL_GATEWAY_PORT`, `PORTAL_FORCE_HTTPS`, `PORTAL_REAL_IP_FROM`, `GATEWAY_REAL_IP_FROM`, `GATEWAY_FORCE_HTTPS`, `PUBLIC_PORTALS`), isolated-stack `E2E_*` variables (section 6; full list in `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md:67-91`), `HARNESS_URL`, `CHROME`. (src: respective scripts, read at the lines cited in earlier sections; not every tooling variable was enumerated: UNVERIFIED completeness.)

---

## 13. Secrets handling policy

1. **No secrets in git.** `.env`, `.run/`, `backups/`, generated Keycloak realms and similar are git-ignored (src: `.gitignore`; `docs/SECURITY.md:21`). `scripts/secret-scan.sh` runs `gitleaks` over the whole history and the working tree with `.gitleaks.toml`, which allow-lists only the documented loopback placeholders (`studio-local-only`, `studio-minio`) (src: `scripts/secret-scan.sh:6-10`, `.gitleaks.toml`). A leaked secret: rotate first, then clean history (src: `docs/DISASTER_RECOVERY.md:48`).
2. **Local-only defaults** in `compose.yml` / `.env.example` are placeholders for a developer laptop on loopback; never reuse them (src: `compose.yml:2`, `docs/SECURITY.md:28`).
3. **Production values come from the environment.** The `prod` profile has no defaults for secrets and fails to start on unsafe values: passwords shorter than 12 or dev-like, non-Secure cookie, Swagger on, public sign-up on (unless `SIGNUP_ALLOW_IN_PROD`), `DEPLOY_PROVIDER=mock`, a weak `BOOTSTRAP_ADMIN_PASSWORD`, `SCIM_ENABLED` without a 32-char token, `SAML_ENABLED` without OIDC, server apps without master key / gateway token / apps DB password, a short `BUILD_RUNNER_TOKEN` (src: `docs/DEPLOYMENT.md:14-21`, `backend/.../common/ProductionConfigValidator.kt`). Known gap, still OPEN (checked in code at `28376ded2307`): public sign-up can be turned on at runtime through the HIGH-risk admin policy `signup.enabled` (`backend/.../settings/Settings.kt:33`; read by `identity/RegistrationController.kt:54`) although `ProductionConfigValidator.kt:35` only checks the start-up property `app.signup.enabled`; keep the policy off and watch `audit_events` for `SETTING_CHANGED` / `SETTING_RESET` (`settings/Settings.kt:149`, `:161`) (src: C1 `docs/parallel/c1/final-iam-hardening-report.md` section 3).
4. **Generated secrets are mode 600** and stay outside the repo or in git-ignored dirs: `.run/public/public.env`, `.run/appdb.env`, `.run/render.token`, `.run/runner.token`, `.run/data-target/*`, `~/.xweb-e2e-stack/<name>/stack.env`. Metadata of public releases holds only allow-listed non-secret keys (tested with sentinel values); the API process gets secrets at start and they are never recorded (src: `PUBLIC_DEPLOYMENT_PINNING.md:32`, `PUBLIC_API_PINNING.md:21-22`).
5. **Credentials of data sources and project secrets** are encrypted with `SECRETS_MASTER_KEY` (AES-GCM, write-only, never returned, never sent to AI, never logged); the Connector Proxy and SSRF guard keep credentials server-side (src: `docs/SECURITY.md`, `docs/parallel/OWNERSHIP.md:84`, `backend/.../data/datasource/Credential.kt`).
6. **Never print a secret** in a log, ticket, chat or evidence file: tools read credentials from files outside the repo and do not echo them (src: `docs/parallel/c6/EVIDENCE_INDEX.md` "Excluded on purpose"). One recorded slip: a development trust-store password was printed once to a tool log in D-C0-59 (it protects only a copy of public CAs plus the dev CA); rotate by regenerating `.run/data-target/` (`data-target.sh down --purge`, `up`).
7. **Rotation**: user/session (disable user; clear Redis sessions), OIDC client secret (rotate at the IdP, update `OIDC_CLIENT_SECRET`, restart), DB / Redis / RabbitMQ / MinIO credentials (rotate in the service, update the secret store, restart the API), metrics token, `SECRETS_MASTER_KEY` (no rotation procedure is documented: UNVERIFIED) (src: `docs/DISASTER_RECOVERY.md:42-50`).
8. Backups are not encrypted (mode 600 files in a git-ignored dir): encrypt before they leave the host (src: `docs/BACKUP_DR.md:71`).

---

## 14. Migration policy (Flyway)

- Flyway community, `spring.flyway.enabled=true`, `ddl-auto: validate` (Hibernate drift fails fast). Forward-only versioned migrations in `backend/src/main/resources/db/migration/` (src: `application.yml:22-27`, `PUBLIC_API_PINNING.md:52`).
- **Only C0 allocates version numbers.** An owner writes a request in `docs/parallel/BOARD.md` ("Migration requests"); C0 allocates the next free number in `docs/parallel/MIGRATION_LEDGER.md`, commits, then tells the owner; the owner creates the file only after that. One version = one task. Never choose a number yourself (src: `docs/parallel/OWNERSHIP.md:87-101`, `MIGRATION_LEDGER.md:3-5`, `:36-39`).
- **Immutable once on `integration/v2`**: an applied/merged migration is never edited; a fix is a new forward migration; Flyway history is never edited by hand (src: `OWNERSHIP.md:99`, `docs/DEPLOYMENT.md:35-37`, `MIGRATION_LEDGER.md` V29 "IMMUTABLE FROM NOW ON").
- **`outOfOrder` stays off** (`spring.flyway.out-of-order` false; never enable it). Integration merges migrations only in ascending order. Consequence: **V31 is a permanent void gap** (allocated to C2 for candidate activation, never created; V32 was allocated first, and a V31 created after V32 would fail validation on every migrated database); Flyway does not need contiguous numbers (src: `MIGRATION_LEDGER.md:17-19`, D-C0-52; guard `MIGRATION-ORDER-HAZARD`).
- Applied state at `28376ded2307`: V1...V30, V32 (`V32__dynamic_organization.sql`) and V33 (`V33__approvals.sql`, owner C4, allocated by C0 on 2026-10-11 in D-C0-61, DDL moved verbatim from the C4 proposal, additive: one table and five indexes, undo `docs/parallel/c0/undo/U33__approvals.sql`) (src: `git ls-tree` of `backend/src/main/resources/db/migration`; `docs/parallel/MIGRATION_LEDGER.md` V33 row). The next free number is V34; the candidate-activation work (LIM-1, Batch 3) would receive it only when C0 authorises it, and no number is reserved now. `approvals.app_id` is a plain foreign key to `projects(id)` (no composite tenant FK, because that needs a new unique constraint on the existing `projects` table); tenant consistency is enforced by the engine scope check and by every query being keyed on `tenant_id` (D-C0-61 item 1; also `KNOWN_LIMITATIONS.md`).
- Undo scripts are manual, human-run, outside Flyway: `docs/parallel/<owner>/undo/U<version>__*.sql` (`c1/undo/U26`, `c2/undo/U27`, `c0/undo/U28`, `U29`, `c2/undo/U30`, `c3/undo/U32`). They refuse to run while dependent rows/leases exist (src: `ls-tree`, `MIGRATION_LEDGER.md:16,18`). The public-API tooling never uses them (src: `PUBLIC_API_PINNING.md:52`).
- Guard `tests/guards/migration-ledger.mjs` (inside `npm run gate:frontend`) fails on a duplicate version, a number above every ledger row, V31 not named `*candidate*` / V32 not named `*organization*`, removal/rewrite of the V31 or V32 ledger rows, or `out-of-order: true` (src: `docs/parallel/c0/FRONTEND_GATE.md:26`, `tests/guards/migration-ledger.mjs:6-9`). V33 passes it because the ledger has a V33 row; any new migration needs its ledger row first. `OrgV32FlywayTests` and `scripts/rc-verify.mjs` were updated with V33 (D-C0-61 item 7).
- Every table of every new migration: `tenant_id NOT NULL REFERENCES tenants(id)`, composite FKs for workspace-scoped tables, `(tenant_id, created_at)` index where rows grow, a retention statement (src: `MIGRATION_LEDGER.md:22`). The still-unnumbered backlog (tenant resources, sharing/departments/groups, RLS, audit hardening, `tenant_compat_removal`) is in `KNOWN_LIMITATIONS.md`.
- Before a migration on a live database: verified backup (section 10). **Public API note:** V33 will be applied to the public database only when the public API is deployed from a SHA that carries it (`deploy-api`; it is a new additive table, so `deploy-api --expand-only` is the declaration that keeps `rollback-api` possible); the public API is currently NOT upgraded (D-C0-57). A failed migration: restore the dump, do not repair history (src: `docs/DEPLOYMENT.md:38-39`). Public API: `deploy-api` applies pending migrations at start (one-way) and rollback is gated by the schema verdict (section 7.3).
- Every integration test class migrates V1 -> latest in a throwaway PostgreSQL, which is the standing proof that a fresh database builds (src: `docs/DEPLOYMENT.md:35`).

---

## 15. Branch and release policy

- **`integration/v2` is the source of truth** and the only branch that contains the implementation. `main` is older and is NOT the implementation: `origin/main` last commit `da348ff` (2026-09-30) is 673 commits behind `origin/integration/v2` (computed with `git rev-list --count origin/main..origin/integration/v2`). Never merge `main` (src: `CLAUDE.md` "Không merge main").
- Owner branches follow `agent/cN-*` (and `fix/*`, `feat/*`, `verify/*`, `wire/*`, `import/*`). The roles C0...C7 are **LOGICAL roles**, not people or tools (src: `docs/parallel/OWNERSHIP.md`): C0 architect/integrator (owns `docs/parallel/**`, `docs/contracts/**`, `compose*.yml`, `application*.yml`, `.env.example`, `build.gradle.kts`, `common/**`, `package*.json`, `CLAUDE.md`, `docs/adr/**`); C1 tenant/identity/permission/sharing/organization; C2 app definition, schema, version, component, template, publish pipeline (delegated by D-C0-26); C3 data platform; C4 action/workflow/approval; C5 web builder/admin UI/E2E (never creates migrations). `OWNERSHIP.md` itself defines C0-C5; C6 (independent QA) and C7 (release decision maker) appear in `docs/parallel/c6/*` and DECISIONS but are not defined in `OWNERSHIP.md` (UNVERIFIED formal definition). Do not edit another owner's files, especially HOT FILES (`OWNERSHIP.md` section 4); record a dependency in `docs/parallel/BLOCKERS.md` or the BOARD `Depends` column instead.
- **Import convention (C0 only)**: an owner branch reaches `integration/v2` as a non-fast-forward merge with the message `import(cN): <branch> @ <sha12> - <summary>`, after an audit of the exact delta (files, owner scope, no migration/contract surprises) and the gates; examples `import(c4): fix/c4-fq-act-02 @ 49b2ae98c303`, `import(c5): agent/c5-web @ 8e10fed01ee2` (src: `git log origin/integration/v2`, D-C0-55 item 2: "merge --no-ff ... not a cherry-pick, not a rebase"). Build output committed by mistake is dropped from the merge tree (D-C0-58 item 1). Earlier imports sometimes used path-scoped imports or `cherry-pick -x` (BOARD rows): history is not rewritten.
- **No history rewrite**: no `push --force`, `reset --hard`, `clean -fd`, `worktree remove --force`; do not add GitHub Actions (there is no CI by decision); no merging `main` (src: `CLAUDE.md`, `docs/parallel/WORKTREE_SETUP.md:4`). Pushing a branch needs the project owner's permission (a working rule stated by the owner outside the repository: UNVERIFIED in-repo).
- **Contracts and decisions**: `docs/contracts/**` changes only after a numbered entry in `docs/parallel/DECISIONS.md` (`D-C0-NN`, `D-C1-NN`, ...). Turning a feature flag on is a recorded decision (section 12.3).
- **Backward compatibility is mandatory**: current API, the `projects` table, Page Schema, `STATIC_APP`; do not delete old features for V2. Invariants to preserve: Page Schema + `SchemaPatchEngine` + `PageSchemaValidator`; component registry/versions; immutable project versions; AI Gateway; Connector Proxy + SSRF guard (`PublicAddress`) + credentials server-side only; append-only audit; publish pipeline; render/build/runtime planes (src: `CLAUDE.md`, `OWNERSHIP.md:83-85`).
- **Release policy**: the release unit is a SHA on `integration/v2`. A candidate becomes `{{FINAL_RC_SHA}}` only after: every import the release needs is on `integration/v2`; backend full uncached gate, frontend gate, browser harness green; an isolated stack is built from exactly that SHA and passes the SHA proof (`rc-verify.mjs`); independent QA (`QA_FINAL.md`) retests the open P1/P2 items on that SHA. Public deployment is a separate, explicit act (`deploy <sha>` / `deploy-api <sha>`), never a side effect of merging (section 7). Release readiness vocabulary used by C0/C6: `READY_FOR_RC_FREEZE`, `READY_FOR_RELEASE` (YES/NO) (src: `docs/parallel/c6/FINAL_RC_QA_REPORT.md`). The owner fills `{{FINAL_RC_SHA}}` and the acceptance record (`QA_FINAL.md` section 8).
- Each task: tested and committed separately; the final report lists files changed / tests / blockers / commit SHA (src: `CLAUDE.md`). Worktrees: one per owner next to the main checkout (`docs/parallel/WORKTREE_SETUP.md`); copy `.env` locally, never commit it.

---

## 16. Build and test commands

### 16.1 Backend
```bash
cd backend
export JAVA_HOME=/opt/homebrew/opt/openjdk@21        # or $(/usr/libexec/java_home -v 21)
./gradlew test                                       # needs Docker (Testcontainers)
# gate-grade run (what QA and C0 use): uncached, real execution, bigger Kotlin heap
./gradlew clean test --no-build-cache --rerun-tasks --no-daemon -Pkotlin.daemon.jvmargs=-Xmx3g
```
- JDK 21 is mandatory (toolchain 21, `backend/build.gradle.kts:24-29`). Release builds additionally verify the JDK first (`JDK_MISMATCH` unless Java 21 and `JAVA_HOME` agree) (src: `PUBLIC_API_PINNING.md:69`).
- `backend/gradle.properties`: `org.gradle.caching=true`, `org.gradle.parallel=true`, `org.gradle.jvmargs=-Xmx2g -XX:+UseParallelGC`, `kotlin.daemon.jvmargs=-Xmx3g` (the default heap ran out in `compileTestKotlin`) (src: `backend/gradle.properties`).
- **Test JVM heap is 2 GB** (`maxHeapSize = "2g"`): the suite caches one Spring context per distinct configuration and the default 512 MB ran out after the C4/C2 imports (src: `backend/build.gradle.kts:70-75`, D-C0-32).
- **Why `--no-build-cache --rerun-tasks`**: a run whose `:test` is `FROM-CACHE` / `UP-TO-DATE` executes no test and is not evidence (QA found a false "GREEN" this way) (src: `docs/parallel/c6/QA_STATUS.md` batch 4). The release build of the public API uses `./gradlew clean bootJar -x test --no-build-cache --rerun-tasks --no-daemon -Pkotlin.incremental=false` with an isolated `GRADLE_USER_HOME` and never `./gradlew --stop` (src: `PUBLIC_API_PINNING.md:69`).
- **Never run two heavy Gradle or browser jobs concurrently on the shared host** (section 9). `scripts/stop-local.sh` never stops the shared Gradle daemon.
- Last recorded full backend results (dated): (a) 2026-10-10 on the candidate `ef5989e`: 249 classes, 2260 tests, 1 failure (`LockdownSettingsTests`, test isolation / host load, 5/5 pass alone), 0 errors, 3 skipped (`OpenRouterLiveTests`, need a real provider key) (D-C0-58 item 5); (b) 2026-10-11 on the merged C1 hardening tree (JDK 21, `--no-build-cache`, host load about 10): 256 suites, 2298 tests, 2297 pass, the single failure again `LockdownSettingsTests` (503 on register, host starvation class) and 5/5 pass when run alone (D-C0-60 item 3). The count for `{{FINAL_RC_SHA}}` itself is recorded in `QA_FINAL.md` section 8, not here. Reported by the coordinator for `205707e` (not recorded in a repository document I read): `gate:frontend` GREEN with 539 unit tests, static guards, three production builds and the bundle scan; secret scan over 454 commits with no leaks. The RTL ratchet unit test counts source CSS only and skips the generated `packages/*/dist` (commit `205707e`).

### 16.2 Testcontainers requirements
Backend integration tests start their own throwaway PostgreSQL, Redis, MinIO and RabbitMQ (and, for some classes, Forgejo) through Testcontainers 2.0.5 (`org.testcontainers:testcontainers-bom:2.0.5`, `-postgresql`, `-rabbitmq`, `-minio`) and never touch the dev stack (src: `backend/build.gradle.kts:62-66`, `docs/LOCAL_DEVELOPMENT.md:42`). Requirements: a running Docker daemon (Docker Desktop on macOS), image pulls (`postgres:17.6`, `redis:8.2.1-alpine`, `bitnamilegacy/minio:2025.7.23-debian-12-r5`, `rabbitmq:4-management-alpine`, ryuk), enough VM memory for several containers per context, and no assumption of fixed ports. Docker is required for every `./gradlew test`; there is no no-Docker mode.

### 16.3 Frontend
```bash
npm ci
npm run typecheck            # root tsc --noEmit
npm run typecheck:packages   # workspaces
npm run typecheck:apps       # the three portals
npm run test:classify        # every test file has a valid // @class tag
npm run test:unit            # node scripts/test-unit.mjs (set XWEB_CONFORMANCE_DIR=backend/src/test/resources/app-definition for the conformance fixtures)
npm run build:apps           # production builds of platform, admin, studio
npm run build                # root app (mock static export or http mode)
npm run guard:static         # static guards (9)
npm run guard:test           # guards fail on deliberately broken fixtures
npm run scan:bundles         # no localhost / loopback / container names in browser bundles
npm run gate:frontend        # all of the above in one command (about 90 s; -- --no-build ~30 s)
```
(src: `package.json` scripts, `docs/parallel/c0/FRONTEND_GATE.md:1-14`). `gate:frontend` steps: 1 static guards, 2 guard self-tests (93 tests), 3 typecheck, 4 unit tests, 5 production builds into `apps/<app>/.next-gate`, 6 bundle scan. The browser harness, the real-backend flows and the public smokes are NOT in the gate. Infra tests: `npm run test:infra:portals|processes|public|api`.
- Browser harness (class `harness`, no backend): `node tests/browser/build-harness.mjs` then `CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/<spec>.spec.mjs`; `HARNESS_URL` is required (no fixed port). `esbuild` is installed OUTSIDE the repo (`npm i --prefix /tmp/esb esbuild`) (src: `tests/browser/README.md`). `npm run test:unit` recreates `.test-build`: rebuild the harness afterwards.
- Real-backend flows (class `real-backend`): `docs/parallel/c5/e2e-stack.sh e2e "E2E-PL01,E2E-AD01,..."` or `npm run test:e2e:real` against a stack you started; exit 0 all runnable flows passed, 1 a flow failed, 2 NOT RUN (never a pass) (src: `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md:99-101`).
- Whole-repo local verification: `./scripts/check.sh` (typecheck, mock + http builds, all backend tests, `npm audit --omit=dev`) (src: `scripts/check.sh`). Secret scan: `./scripts/secret-scan.sh`. Smoke: `./scripts/smoke-test.sh`, `scripts/v1-smoke.sh`, `scripts/v1-public-smoke.sh`.
- Test classes and what counts as evidence are defined in `QA_FINAL.md`.

---

## 17. Quick incident checklist (from the corpus)
1. API 503 `DEPENDENCY_UNAVAILABLE`: Redis or another readiness dependency is down (`docker compose ps`, `/actuator/health/readiness`).
2. Many 10-28 s requests or client `TIMEOUT` ("Máy chủ không phản hồi kịp"): suspect host starvation first (swap, load, container count, `docker ps | wc -l`), then PostgreSQL crash recovery in its log (section 9).
3. A public hostname serves the wrong build: `scripts/public-portals.sh status` and `scripts/public-api.sh status`; compare running vs approved vs integration; deploy only through `deploy <sha>` / `deploy-api <sha>`.
4. A crash loop: `status` shows `CRASH_LOOP`; read `.run/public/crash-loop.json`; do not "fix" by building HEAD.
5. A port is busy: identify the holder (the tools name it); do not kill it unless it is provably yours.
6. Data loss or corruption: stop writes, follow `docs/DISASTER_RECOVERY.md` scenarios (with the `--target-db` correction above), then take a fresh backup.

---

## Sources read
- Repo (read from local `integration/v2` HEAD `28376ded2307` and, for the first pass, `origin/integration/v2` `7d46ec5c5f98`): `CLAUDE.md` (via session context), `compose.yml`, `compose.public.yml`, `.env.example`, `.gitignore`, `.gitleaks.toml`, `package.json`, `backend/build.gradle.kts`, `backend/gradle.properties`, `backend/src/main/resources/application*.yml`, `backend/src/main/resources/db/migration` (listing), `scripts/_env.sh`, `run-local.sh`, `stop-local.sh`, `portals.sh`, `rc-verify.mjs` (re-read at 28376de for the V33 expectation), `check.sh`, `secret-scan.sh`, `watchdog.sh`, `backup-daemon.sh`, headers of `restore-postgres.sh`, `data-target.sh`, `public-up.sh` (grep), the docs listed in the first pass (`docs/LOCAL_DEVELOPMENT.md`, `DEPLOYMENT.md`, `PUBLIC_DEPLOYMENT.md`, `BACKUP_*`, `DISASTER_RECOVERY.md`, `OBSERVABILITY.md`, `SECURITY.md`, `IMPLEMENTATION_STATUS.md`, `C5_REAL_BACKEND_E2E_RUNBOOK.md`), `docs/parallel/OWNERSHIP.md`, `BOARD.md`, `BLOCKERS.md`, `MIGRATION_LEDGER.md`, `DECISIONS.md` (D-C0-54..61 in full), `WEB_SECURITY_CONFIG.md`, `WORKTREE_SETUP.md`, `c0/PROCESS_SAFETY.md`, `c0/DEMO_STACK_RUNBOOK.md`, `c0/PORTAL_LIFECYCLE.md`, `c0/PUBLIC_DEPLOYMENT_PINNING.md`, `c0/PUBLIC_API_PINNING.md`, `c0/FRONTEND_GATE.md`, `c5/PROCESS_SAFETY.md`, `docs/parallel/c5/e2e-stack.sh`, `tests/browser/README.md`; code checks at 28376de: `settings/Settings.kt`, `common/ProductionConfigValidator.kt`; `git log`, `git ls-tree` listings.
- QA documents now on `integration/v2` (imported from `agent/c6-qa @ 04803d7644fc`): `docs/parallel/c6/QA_STATUS.md`, `MAC_QA_HANDOFF.md`, `EVIDENCE_INDEX.md`.
- C4 approval handoff and C1 hardening report (now imported): `docs/parallel/audit/C4-FQ-WF-01-approval.md`, `docs/parallel/c1/final-iam-hardening-report.md`.
- Not read: `scripts/public-up.sh`, `public-portals.sh`, `_portals_lib.sh`, `lib/*.mjs` bodies (behaviour taken from the C0 docs and script headers), `infra/**`.

## Open questions / UNVERIFIED

1. Current public frontend / API releases (`{{PUBLIC_FRONTEND_SHA}}`, `{{PUBLIC_API_SHA}}`): only dated 2026-10-10 values are recorded. Verify with `scripts/public-portals.sh status` and `scripts/public-api.sh status`.
2. Location of `rc-window.sh` (heavy-gate window runner with resource precheck): not in the repository.
3. The simultaneous-stack and heavy-job rules in section 9.2 are derived guidance; there is no numeric cap or enforcement in the corpus. Confirm the Docker VM size (about 8 GB) and the intended policy with C0.
4. `SECRETS_MASTER_KEY` rotation procedure is not documented anywhere read.
5. Linux behaviour of every script is untested (macOS/Docker only).
6. The coordinator-reported results for `205707e` (frontend gate green with 539 unit tests, secret scan 454 commits with no leaks) and the machine facts (Docker VM about 8 GB, swap about 90%, load 5-10) are not recorded in a repository document I read.
7. The runtime-setting gap for public sign-up (section 13, item 3) was settled from code (still open); the rest of the C1 report items are settled in `KNOWN_LIMITATIONS.md`.
