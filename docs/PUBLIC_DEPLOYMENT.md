# Publishing the app from this machine (Cloudflare tunnel)

> **Since D-C0-39 (2026-10-07) the Studio hostname is the Studio portal and there are three portals**: Platform `platform.toolsmcp.uk`, Admin `admin.toolsmcp.uk`, Studio `studio.toolsmcp.uk`.
> The legacy single root UI on 3200 below is no longer published (`PUBLIC_PORTALS=false` brings it back). The diagram right below is the current topology.

```
Internet ─HTTPS─► Cloudflare ─tunnel hbl-studio─► 127.0.0.1:3210  portal gateway (nginx, routes by Host, http→https 308, client address)
                                                    ├─ platform.toolsmcp.uk → 127.0.0.1:3201  Platform portal (next start)  ─┐
                                                    ├─ admin.toolsmcp.uk    → 127.0.0.1:3202  Admin portal                   ├─ same-origin /api, /oauth2, /login/oauth2 ─► 127.0.0.1:18081 API (prod)
                                                    └─ studio.toolsmcp.uk   → 127.0.0.1:3203  Studio portal                 ─┘
                                              ├─► 127.0.0.1:28088  sites gateway   (sites.toolsmcp.uk/{slug}/, /{slug}/_data/… → API /sites/**)
                                              └─► 127.0.0.1:29000  MinIO           (studio-files.toolsmcp.uk, presigned URLs only)
```
One portal = one hostname (each serves `/_next/**` from the root of its origin). The browser never talks to the API host: every portal proxies `/api` on its own origin. Sessions are per origin
(host-only cookies): signing in on one portal does not sign in on another (B-C0-WEB-01, C1 contract). Details, measured proxy chain and tests: `docs/parallel/DECISIONS.md` D-C0-39.
`./scripts/public-portals.sh up|down|restart|build|status [--force-rebuild]` fingerprints the source and builds / restarts ONLY what changed (`apps/*/.next-public-<fingerprint>`, never the local portals' directories), keeps a failed build from touching a healthy portal; see `docs/parallel/c0/PORTAL_LIFECYCLE.md`; `./scripts/public-launchd.sh install` (optional, not done for you) starts the stack at login.
Tests through the Internet hostnames: `node scripts/global-portals-smoke.mjs`, `node scripts/global-portals-browser.mjs`, `node scripts/global-smoke.mjs`, `node tests/gateway/portal-route.mjs`.

## Legacy single-UI topology (kept for rollback)

```
Internet ──HTTPS──► Cloudflare ──tunnel hbl-studio──► 127.0.0.1:3200  Next.js UI  ──/api,/oauth2──► 127.0.0.1:18081  API (prod profile, java -jar)
                                                  └─► 127.0.0.1:29000  MinIO (presigned URLs only, host studio-files.*)
                                      Docker (compose project hblpub, all on 127.0.0.1): Postgres 25432 · Redis 26379 · RabbitMQ 25674 · MinIO 29000
```
Three public hostnames (defaults `studio.toolsmcp.uk`, `studio-files.toolsmcp.uk` and, since Phase 7.1, `sites.toolsmcp.uk` for published sites → sites gateway 127.0.0.1:28088 → API `/sites/**` only; a render worker runs on 127.0.0.1:28095; change `PUBLIC_HOST`/`PUBLIC_FILES_HOST` in `.run/public/public.env` before the first run). The API, the database, Redis and RabbitMQ are never published. A **dedicated** tunnel `hbl-studio` is used; the existing `gemma` tunnel and `~/.cloudflared/config.yml` are not touched.

## Commands
```bash
./scripts/public-up.sh            # first run generates .run/public/public.env (random secrets), starts everything, creates the tunnel + DNS
./scripts/public-status.sh        # local and public health, process list
./scripts/set-openrouter-key.sh   # enter the OpenRouter key silently; restarts the API
./scripts/public-down.sh [--infra]
node e2e/public-flow.mjs          # real-browser test of the published site
```
`public.env` (mode 600, git-ignored) holds the generated database/Redis/RabbitMQ/MinIO passwords, the operator login (`BOOTSTRAP_ADMIN_USERNAME` / `BOOTSTRAP_ADMIN_PASSWORD`) and the sign-up and quota settings. After a reboot or a Docker restart run `public-up.sh` again (it starts only what is missing).

## What users can do
Anyone can **sign up** (own workspace, no email stored), create up to `MAX_PROJECTS_PER_WORKSPACE` (20) projects, edit with AI/simulator, upload up to 30 images/PDFs per project, and press Publish. Sign-up is limited to 5 per IP per hour and 500 accounts total; set `SIGNUP_INVITE_CODE` to require a code or `SIGNUP_ENABLED=false` to close it.

## What is verified (2026-10-02)
`node e2e/public-flow.mjs` through Cloudflare: HTTPS + HSTS + nonce CSP, sign-up and login, Secure/HttpOnly cookies, project, prompt, image upload straight to the public storage host (presigned URL + CORS) and download, publish to RUNNING, reload keeps the session, audit rows store the real client IP (not 127.0.0.1), a second user cannot see the first user's workspace (404), actuator/OpenAPI/Swagger return 404, the bucket is not listable. (Two checks needed `curl --resolve` because this Mac cached a negative DNS answer; they passed that way.)

## Verified after Phase 7.1 (2026-10-02)
`node e2e/public-flow.mjs` (rewritten for the new UI) through Cloudflare: 12/13 — sign-up, Secure/HttpOnly cookies, website creation, prompt,
Design-mode edit, upload via the storage host, **real site at `https://sites.toolsmcp.uk/<slug>/`** (content, CSP, no cookie), **private site**
(anonymous → Studio sign-in redirect, member enters with a Secure host-only `site_session`, another user refused), foreign workspace 404,
real client IPs in audit, no operational endpoint reachable. The 13th check failed because Cloudflare injected its analytics script into
the site page (blocked by the CSP); fixed with `Cache-Control: no-transform` and re-verified on a live page. A full re-run was not repeated
in the same hour because of the public sign-up limit (5 per IP per hour). Database backup taken before the V8–V13 migrations:
`backups/public/studio-20261002T105043Z.dump`.

## Code projects (Phase 7.2–7.4) are not enabled here
The public instance has no Git server or build runner configured, so "Ứng dụng web (mã nguồn)" shows "Chưa bật". Enabling it would let any
signed-up internet user run sandboxed builds on this Mac; decide sign-up policy first.

## Limits you must know about
* **It only works while this Mac is on, awake, online and Docker/the processes run.** `public-up.sh` starts `caffeinate -i -s` (no sleep on AC power); closing the lid on battery still sleeps it. Nothing restarts automatically after a reboot (no LaunchAgent is installed).
* **Publish is real (Phase 7.1):** a published page is served at `https://sites.toolsmcp.uk/<slug>/` from an immutable artifact; private sites require signing in on the Studio as a member. It is served from this Mac like everything else.
* All traffic and all users' data live on one laptop disk: schedule `scripts/backup-postgres.sh` and `scripts/backup-minio.sh` (not scheduled by default).
* Public sign-up on a free AI quota can be abused: watch `audit_events`, lower `AI_DAILY_LIMIT_PER_USER`, add an invite code, or put Cloudflare Turnstile/Access in front.
* Single node, no HA; the first request after a long idle may be slow while the JVM warms up.
