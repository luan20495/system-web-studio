# ADR 0017: Server runtime for generated apps (Phase 7.5)
Status: accepted and implemented locally (2026-10-04). Off by policy by default (`server-apps.enabled`, HIGH risk); not enabled on the
public pilot (the pilot host is a developer Mac — see "Production target").

## App kinds
`projects.app_kind`: WEBSITE_STATIC (page schema), SOURCE_WEB_APP and DASHBOARD (browser-only React), INTERNAL_TOOL, WORKFLOW and
SERVER_APP (React UI + Node server). `app_type` stays the build model (PAGE_SCHEMA / STATIC_APP repository).

## Stack: Node.js/TypeScript (chosen) vs Kotlin/Ktor
| | Node.js + TypeScript | Kotlin + Ktor |
|---|---|---|
| Same language as the UI and the existing scaffold, one lockfile, one approved-package mirror | yes | no (second toolchain, Maven mirror needed) |
| AI code generation quality / volume of examples | high | lower |
| Build in the existing sandbox (`npm ci` from the mirror, no network build) | yes | needs a JDK image + Gradle offline repo |
| Cold start / memory per app | ~40 MB, < 1 s | ~150–250 MB, seconds |
| Type safety | strict TS | stronger |
Decision: Node 22 + TypeScript, server bundled with esbuild into one `server.cjs` (dependencies such as `pg` included), no runtime
`npm install`. Kotlin stays the platform language.

## Build and deploy
The server app repository = React UI (`src/`) + server (`server/`) + `openapi.json` (declared routes). The same sandbox build as every code
app produces `dist/` (UI) and `dist/server/` (bundle + routes). Publishing serves the UI like a code app and creates a **server deployment**:
the API stores the declared routes and sets the runtime's desired deployment; the runner builds an image from the bundle with no network
(`FROM node@sha256:… ; COPY app/`) and starts a container:
non-root (10001), read-only rootfs (+ 32 MB noexec tmpfs), `--cap-drop ALL`, `no-new-privileges`, 256 MB / 0.5 CPU / 128 pids, no host mounts,
no Docker socket, env from a 0600 file (never on a command line), health check `GET /health`, restart on failure (5×), network `apps`.

**Blue/green:** the new container runs next to the current one; only after its health check passes does the API switch the gateway to it
and mark the old one SUPERSEDED (the runner removes it). A failed start leaves the current version serving. **Rollback** = deploy an
earlier build's artifact again (no rebuild). Verified live: 17 requests during a switch, 0 failed.

## Network and gateway
`apps` is an internal Docker network: no internet, no host, no platform services. It contains the apps database server and the apps gateway.
Inbound: browser → sites gateway → API (`/<slug>/api/**` public, `/_app/<token>/api/**` private member capability) → apps gateway
(`/app-<project>-<deployment>/…`, requires the API's gateway token; apps cannot use it to reach each other) → container. The API passes only
methods/paths declared in the deployed `openapi.json`, strips cookies and Authorization, sends the member in `X-Factory-User` for private
apps, limits body (1 MB), time (20 s) and rate (300/min per visitor), and never returns HTML as a document. Outbound: only connectors.

## Secrets and connectors
Project secrets, app DB passwords, app tokens and connector credentials are AES-256-GCM encrypted with `SECRETS_MASTER_KEY`. Secrets are
write-only in the API, never in the repository (secret scan + change policy), never sent to the AI (the AI sees repository files only), and
only delivered to the runner when it starts a container. Connectors are admin-approved HTTPS APIs (public host names only) with an
allowlist of operations; an app calls `CONNECTOR_URL/<key>/<path>` with its `APP_TOKEN`; the API checks grant + operation and adds the
credential. Connector metadata (no secrets) is what the AI tool `search_connector_metadata` returns.

## Production target
The runner drives Docker; on this Mac containers run in Docker Desktop's Linux VM. Production: a Linux host (or a dedicated VM pool) running
the same runner, optionally with gVisor (`RUNTIME_OCI=runsc`, ADR 0019). Until such a host exists the public pilot keeps server apps off.
