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
* Self-service sign-up is rate limited per IP, optionally invite-gated, capped in total, never stores an email (an unverified email could otherwise be used with "add member by email" to claim an address) and gives each user an isolated workspace. Per-workspace caps on projects and assets bound storage abuse.
* AI output is untrusted data: validated against the component registry before anything is stored; only free OpenRouter models can be selected; each user has a daily AI allowance; the API key never leaves the server. Prompts and page content are sent to a third party when a key is set (see `AI.md`).
* Argon2 verification concurrency is capped (`LOGIN_MAX_CONCURRENT_HASHES`), after a 100-user login burst froze the API during load testing.
