# Security notes

## Controls in place (each is covered by an automated test unless marked)
| Area | Control |
| --- | --- |
| Sessions | Spring Session in Redis (`spring-boot-starter-session-data-redis`), survives API restarts and Redis restarts (AOF); cookie `HttpOnly`, `SameSite=Lax`, `Secure` by default (`COOKIE_SECURE=false` is for local http only); no session exists before login (no fixation) |
| CSRF | token required on all unsafe methods, 403 `CSRF_INVALID` otherwise |
| CORS | explicit allowlist (`app.cors.allowed-origins`), credentials only for listed origins |
| Brute force | Redis counters per username and IP, checked before Argon2 runs; 429 + `Retry-After`; every failure audited without the password |
| Passwords | Argon2id |
| AuthZ | `PermissionMatrix` on every endpoint; cross-tenant ids return 404; disabled users are rejected on the next request (`ACCOUNT_DISABLED`) |
| Audit | `audit_events` is append-only (DB trigger rejects UPDATE/DELETE/TRUNCATE); records login success/failure, logout, project create/update/delete, versions, restore, uploads, deletes, publish and every deployment transition with actor, IP, user agent and request id |
| Input | Bean Validation on all bodies; schema operations validated against the registry; no SQL is built from user input (all JDBC uses bind parameters) |
| AI boundary | LLM output is a list of declarative operations, never code; nothing generated runs in the JVM |
| Files | MIME allowlist (SVG/HTML rejected), size limits, server-generated object keys (user file names are sanitised and never used as paths), size/type re-verified in MinIO on completion, short-lived presigned URLs, bucket is private |
| Preview | escaped data rendered into `<iframe sandbox="">` (no scripts, no same-origin); only `#anchor` links are rendered |
| Publish | idempotency keys, CAS state machine, content scan before deploy |
| Errors | uniform body with request id; no stack traces or messages leaked (`server.error.include-*: never`) |
| Dependency failure | Redis outage → fast `503 DEPENDENCY_UNAVAILABLE` (2 s timeout), recovers without restart |
| Actuator | only `health` exposed by default, no details; `metrics` is opt-in; Swagger/OpenAPI disabled outside the `local` profile |
| Secrets | no secrets in git; `.env` is ignored; production values come from the environment through `SecretProvider` |

## Known gaps (not production-ready)
- No Content-Security-Policy on the UI (Next.js inline scripts need a nonce setup); basic headers only.
- No MFA, SSO/OIDC, password reset, or member management API (members are seeded/SQL in local).
- The published-site gateway, TLS termination, WAF and real deploy provider are out of scope here.
- `idempotency_keys`, `deployment_events` and `audit_events` have no retention job.
- Dev compose uses well-known local-only passwords (`studio-local-only`); ports are loopback-only. Never reuse them.
- Login throttling is keyed on `remoteAddr`; behind a proxy `server.forward-headers-strategy` and a trusted-proxy list must be configured.
- No malware scanning of uploaded files; no per-workspace quotas.

## Public deployment and AI (added 2026-10-02)
* The published instance runs the `prod` profile (validator refuses dev defaults), behind a Cloudflare tunnel; only the UI server and the storage host are public (see `PUBLIC_DEPLOYMENT.md`). The API is bound to 127.0.0.1 and trusts `X-Forwarded-For` only from `127.0.0.1` (verified: audit rows hold real client IPs).
* Self-service sign-up (password: at least 6 characters, letters and digits — a product decision; online guessing is bounded by the login throttle, 5 failures per 15 min per username) is rate limited per IP, optionally invite-gated, capped in total, never stores an email (an unverified email could otherwise be used with "add member by email" to claim an address) and gives each user an isolated workspace. Per-workspace caps on projects and assets bound storage abuse.
* AI output is untrusted data: validated against the component registry before anything is stored; only free OpenRouter models can be selected; each user has a daily AI allowance; the API key never leaves the server. Prompts and page content are sent to a third party when a key is set (see `AI.md`).
* Argon2 verification concurrency is capped (`LOGIN_MAX_CONCURRENT_HASHES`), after a 100-user login burst froze the API during load testing.

## Findings during Phase 7 (2026-10-02 / 2026-10-03)
* **Cache leak on visibility change (fixed):** time-based caching let a site's public copy keep being served after it was switched to private;
  site responses are now `public, no-cache` (ETag revalidation) or `private, no-store`.
* **CDN script injection (fixed):** Cloudflare injected its analytics `<script>` into script-free sites (blocked by CSP); responses carry `no-transform`.
* **500 for system admins outside their workspaces (fixed):** now `409 ADMIN_NOT_MEMBER`, nothing half-created, no silent membership.
* **Suspicious npm package version (mitigated):** `rollup@4.64.0`/`4.63.0` carry an unexpected optional dependency (`@napi-rs/lzma-linux-x64-gnu`) and
  burned ~170 s of CPU for a trivial build. Pinned to `rollup@4.62.0`, purged from the mirror. Note: it was executed once on this Mac outside
  the sandbox while diagnosing (a plain `vite build`), before the anomaly was understood.
* **Operational script killed Docker Desktop (fixed):** `stop-local.sh` killed every process with a TCP connection to :8080, which included
  Docker Desktop's network proxy (the sites gateway keeps connections to the API); it now kills listeners only.
* **Isolation of generated code (by design):** code previews/apps run under CSP `sandbox allow-scripts` (opaque origin, verified: no cookies or
  storage); builds run in hardened containers with no network (install: mirror only). On macOS the containers share the Docker Desktop VM kernel
  with the platform's own containers — acceptable only while code projects are internal (ADR 0010 note).

## Host security review (2026-10-03, best effort, read-only)
Triggered by the one-time execution of `rollup@4.64.0` on this Mac outside the sandbox (2026-10-03 ≈ 09:11 local). No credentials were rotated or deleted.

| Area | Result | Notes |
| --- | --- | --- |
| Running processes | CHECKED · CLEAN | processes started after the incident are the user's apps (Chrome, VS Code, Cursor, Docker) and this project's services |
| LaunchAgents / LaunchDaemons | CHECKED · CLEAN | none created or modified in the last 3 days; existing user agents (openclaw, fb-xin-jd, githuball, ollama, Google, Zoom, Microsoft, Docker) pre-date the incident by months |
| Shell startup files, crontab | CHECKED · CLEAN | `.zshrc` (Sep 5), `.zprofile` (Sep 26) unchanged; one pre-existing cron job (android-keep-screen-on) |
| npm global packages | CHECKED · CLEAN | only npm + corepack (Sep 26 / Jun 23) |
| npm cache | CHECKED · CLEAN | `rollup-4.64.0.tgz` and the registry metadata of `@napi-rs/lzma-linux-x64-gnu` are cached; the lzma **tarball was never downloaded** (Linux-only optional package) |
| Package content | CHECKED · NO EVIDENCE OF MALICE | 4.64.0 vs 4.62.0: no new child_process/network/eval/env/home-dir usage; native.js only adds a coverage-flush export; `package.json` documents the lzma entry as a napi cross-build fix (#6461); darwin binary same signing type, no instrumentation. The ~100× slowdown cause stays **UNKNOWN** (likely a regression), so the pin to 4.62.0 stays |
| Network listeners | CHECKED · 1 FINDING (fixed) | the dev UI (`next start`, :3100) listened on all interfaces → now `-H 127.0.0.1`. `*:5432/*:6379` belong to the unrelated `factory-*` containers (not touched); macOS services (ARD, ControlCenter, rapportd) are system |
| Files/executables created in the incident window | CHECKED · CLEAN | only a CPU profile written by the diagnosis itself (deleted) |
| Project files | CHECKED · CLEAN | `git status` clean apart from intended changes |
| SSH keys / config (metadata only) | CHECKED · CLEAN | keys and config dated Jan 2026; no `authorized_keys` |
| Git credential config | CHECKED · CLEAN | no credential helper or URL rewrites in global config |
| Environment files | CHECKED · 1 FINDING (fixed) | `.env` was mode 644 → 600; `.run/*.env`/tokens already 600; none tracked by git |
| Docker configuration | CHECKED · CLEAN | credsStore `desktop`, one ECR registry login (pre-existing) |
| Full home scan of the incident window | UNKNOWN | a whole-home `find` exceeded the time limit; narrowed scans (autostart/bin/tmp locations) found nothing |
