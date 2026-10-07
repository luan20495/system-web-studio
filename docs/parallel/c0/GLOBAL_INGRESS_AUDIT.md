# Global ingress through Cloudflare Tunnel — audit and STOP (C0, 2026-10-07)

Status: **AUDIT ONLY. No Cloudflare, DNS, tunnel, config or process change was made.** Reason: the requested hostnames conflict with a tunnel that already serves HBL's own public environment, and the requested origins are the LOCAL-profile services (see section 2). A decision is needed first (section 4).

## 1. Facts (read-only, 2026-10-07)

| Item | State |
|---|---|
| Repo | `/Users/hoangluan/code/HBL`, `integration/v2 @ c606d98`, clean, 16 local commits not pushed |
| cloudflared | 2026.3.0 (Homebrew, outdated; 2026.10.0 available); **not running**; no launchd / brew service; the existing public stack starts it by hand (`scripts/public-up.sh`, pid in `.run/public/`) |
| Tunnels | `gemma` (config `~/.cloudflared/config.yml`: xwork, agent, caco*, caco-matrix, caco-rtc → **another system, never touched**), **`hbl-studio` (`af7e0ac8-…`, config `~/.cloudflared/hbl-studio.yml`)**, llm95, llmacer, macos-mcp, win-9router |
| `hbl-studio` ingress (existing) | `studio.toolsmcp.uk → 127.0.0.1:3200` (legacy UI of the public stack) · `studio-files.toolsmcp.uk → 127.0.0.1:29000` (MinIO, presigned URLs) · `sites.toolsmcp.uk → 127.0.0.1:28088` (public sites gateway) · catch-all `http_status:404` |
| DNS | `studio.toolsmcp.uk`, `sites.toolsmcp.uk`, `studio-files.toolsmcp.uk` resolve to Cloudflare (proxied, to the `hbl-studio` tunnel). `api-studio.toolsmcp.uk` has **no record** |
| Public behaviour now | `https://studio.toolsmcp.uk` and `https://sites.toolsmcp.uk` answer **530** (tunnel down); `api-studio` does not resolve |
| Docker published ports | all bind `127.0.0.1` (hbl: 15432 15434 15674 15675 16379 19000 19001 18088 18090 13000 14873; hblpub: 25432 25674 26379 28088 29000). **One exception: `*:16432` = `xweb-v26-pg16`, a C0 verification Postgres of 2026-10-06 bound on all interfaces** (LAN-reachable, not Internet unless the router forwards it) |
| Local services | backend `:8080` is NOT running now; `:3003` is a C5 `next-server`; render worker `127.0.0.1:18095` (node); sites gateway `127.0.0.1:18088` |
| Actuator | `management.endpoints.web.exposure.include = health` only; `/actuator/health/**` is permitted without login (`show-details: never`); `/actuator/prometheus` needs `ROLE_METRICS` and is not exposed unless `MANAGEMENT_ENDPOINTS` says so |
| Cookies | session `STUDIO_SESSION`: `HttpOnly`, `Secure` (`COOKIE_SECURE`, default true; prod forces true), `SameSite=Lax`, host-only, path `/`; CSRF `XSRF-TOKEN`: `SameSite=Lax`, `Secure` on https; site session `site_session` (host-only on the sites origin, `SITES_COOKIE_SECURE`) |
| Streaming | SSE is used for AI streams (`PromptController`, `AiStreams`, `api-client/core.ts`); no WebSocket; the realtime data bus has no HTTP route. SSE works through a same-origin proxy (the public stack already did) |
| Existing GLOBAL MODE | `scripts/public-up.sh` + `compose.public.yml` + `docs/PUBLIC_DEPLOYMENT.md`: prod profile, separate infra (compose project `hblpub`), UI `3200` proxying `/api` same-origin to API `18081`, sites gateway `28088`, `TRUST_PROXY=true` with loopback CIDR, `CORS_ALLOWED_ORIGINS` = the public origin, HSTS, nonce CSP. Verified 2026-10-02 (`node e2e/public-flow.mjs`) |

## 2. Why the requested mapping is NOT applied

1. **Hostname conflict.** `studio.toolsmcp.uk` and `sites.toolsmcp.uk` already belong to the `hbl-studio` tunnel and route to the public stack's ports (3200, 28088), not to 3003 / 18088. Pointing them at the V1 local ports would overwrite a definition that serves HBL's public environment (and there is nothing to ask the shared `gemma` tunnel for). Per the instruction: STOP the domain change and report.
2. **`127.0.0.1:8080` / `:3003` / `:18088` are the LOCAL-profile stack.** The `local` profile seeds `local.admin|editor|publisher|viewer` with one password from `.env`, enables Swagger / OpenAPI, runs against the dev database and loopback-defaulted origins. `ProductionConfigValidator` refuses `local` together with `prod` on purpose. Publishing that instance to the Internet is unsafe; global mode must be a **prod-profile instance** (what `public-up.sh` already is), never the local one behind a tunnel.
3. **`api-studio.toolsmcp.uk` is not needed.** The frozen contract is same-origin: Studio proxies `/api`, `/oauth2`, `/login/oauth2` to its API (`API_PROXY_TARGET`), published pages call `{sites origin}/{slug}/_data` through the sites gateway. No browser ever calls the API origin, so there is no cross-site cookie, no CORS for the API and no CSRF exception. A separate API hostname would add all three problems and one more public surface.

## 3. What is already correct for GLOBAL MODE and what V1 adds

Already correct (public stack): prod profile (`COOKIE_SECURE`, validator, JSON logs, no Swagger), same-origin proxy, trusted proxy limited to loopback, CORS = public origin only, HTTPS only at Cloudflare, no database / cache / broker port published beyond `127.0.0.1`, catch-all 404.

Needed to carry the V1 features (not done, needs the decision below):
- the three portals instead of the legacy UI (`apps/studio` etc.) behind the same-origin proxy, with `API_PROXY_TARGET` pointing at the public API;
- flags `app.data-platform.enabled`, `app.workflow.enabled`, `app.publish-configs.enabled`, `app.sites.public-data.enabled` and `WORKFLOW_QUEUE=amqp` in `public-up.sh` (hblpub RabbitMQ);
- `SITES_DATA_API_BASE=https://<sites host>/{slug}/_data`;
- the gateway client address: `GATEWAY_REAL_IP_FROM` must name the range from which cloudflared reaches the container (Docker Desktop presents the VM gateway), otherwise every visitor shares one address in `limit_req` and in the API; with cloudflared the real visitor is `CF-Connecting-IP` / the right-most `X-Forwarded-For` entry. This must be measured on the running stack, not assumed;
- `postgres-targets.allowed-private` stays EMPTY in prod (the validator forbids loopback); a data source in global mode points at a real host name with a public CA;
- `compose.public.yml` needs a `docker compose up` to pick up the new template variable (D-C0-37).

## 4. Decision needed (C7 / the owner)

A. **Reuse the existing names for the existing public stack (recommended).** Keep `studio.toolsmcp.uk → 3200` and `sites.toolsmcp.uk → 28088` on `hbl-studio` (no Cloudflare change), update the public stack (`public-up.sh`, prod profile) to the V1 feature set above, bring it up with `public-up.sh`, then run the global smoke. `api-studio` is not created. Local mode is untouched.
B. **Replace the public stack by the V1 stack on the same hostnames.** Requires re-pointing `hbl-studio` ingress to the V1 ports AND running a prod-profile instance on them (not the local one). Needs explicit approval because it overwrites the current routes.
C. **New hostnames for V1 global (for example `v1-studio` / `v1-sites`)** on `hbl-studio` or a new named tunnel; no overwrite; DNS records to create (`cloudflared tunnel route dns`).
Not acceptable in any option: a tunnel route to the local-profile backend, a wildcard `*.toolsmcp.uk` ingress, the shared `gemma` tunnel, a quick tunnel as the main ingress.

## 5. Safe to do now, and done
Nothing on Cloudflare. Read-only audit above. Local mode is unchanged (no config or process change). No secret was read into this document (tunnel credential files exist under `~/.cloudflared/` and are not printed).

Open finding for the owner: `*:16432` (`xweb-v26-pg16`) listens on all interfaces; stop or rebind it to `127.0.0.1` (C0 will remove it on request; it is a throw-away V26 verification container).
