#!/usr/bin/env bash
# PUBLIC 3-portal ingress (D-C0-39), source-aware since D-C0-45: Platform, Admin and Studio (apps/platform|admin|studio) as production builds of THIS checkout, each on its own loopback port behind
# its own hostname (1 portal = 1 hostname: every portal serves /_next/** from the root of its origin).
#   internet -> Cloudflare -> tunnel hbl-studio -> portal gateway 127.0.0.1:3210 -> 127.0.0.1:$PORTAL_*_PORT_PUBLIC (next start) -> /api, /oauth2, /login/oauth2 -> API 127.0.0.1:$API_PORT (same-origin proxy)
#   ./scripts/public-portals.sh up [--force-rebuild] | down | restart | build | status
# `up`: fingerprint source + build environment; build only what changed into apps/<p>/.next-public-<fp> WHILE the old process keeps serving its own directory; only after every needed build succeeded,
# restart each stale process (graceful TERM, KILL after 15 s, only processes this script started); a failed build changes nothing; a new process that does not start is rolled back to the previous build.
# LIMITATION: this is a stop-then-start per portal (a second or two of refused connections on that hostname), not zero-downtime: there is no reverse proxy in front of the portals that could switch
# upstreams, and the portal gateway is shared infrastructure that this script does not reconfigure.
# Reads .run/public/public.env (written by public-up.sh). State: .run/public/portal-<name>.*. Never touches the local portals (scripts/portals.sh, apps/*/.next-local-*, .run/portals).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
RUN="${PL_PUBLIC_RUN:-.run/public}"; ENVF="$RUN/public.env"
[ -f "$ENVF" ] || { echo "not set up: run scripts/public-up.sh" >&2; exit 1; }
set -a; . "$ENVF"; set +a
MODE=public; RUNDIR="$ROOT/$RUN"; PL_PREFIX="portal-"; NAMES=(platform admin studio); HOST=127.0.0.1
host_of() { case "$1" in platform) echo "${PUBLIC_PLATFORM_HOST:-platform.toolsmcp.uk}";; admin) echo "${PUBLIC_ADMIN_HOST:-admin.toolsmcp.uk}";; studio) echo "${PUBLIC_HOST:-studio.toolsmcp.uk}";; esac; }
pl_port_of() { case "$1" in platform) echo "${PORTAL_PLATFORM_PORT_PUBLIC:-3201}";; admin) echo "${PORTAL_ADMIN_PORT_PUBLIC:-3202}";; studio) echo "${PORTAL_STUDIO_PORT_PUBLIC:-3203}";; esac; }
# baked into the browser bundle (and into the proxy rewrites): a change rebuilds
BUILD_ENV=( NEXT_PUBLIC_API_MODE=http API_PROXY_TARGET="http://127.0.0.1:${API_PORT:-18081}"
  NEXT_PUBLIC_PORTAL_URL_PLATFORM="https://$(host_of platform)" NEXT_PUBLIC_PORTAL_URL_ADMIN="https://$(host_of admin)" NEXT_PUBLIC_PORTAL_URL_STUDIO="https://$(host_of studio)" )
# read by the running server only (CSP, HSTS): a change restarts, it does not rebuild
RUN_ENV=( STUDIO_HSTS=true MINIO_PUBLIC_ENDPOINT="https://${PUBLIC_FILES_HOST:-studio-files.toolsmcp.uk}" SITES_ORIGIN="https://${SITES_HOST:-sites.toolsmcp.uk}" )
[ -d "$ROOT/node_modules" ] || { echo "node_modules is missing: run npm ci first" >&2; exit 1; }
. "$ROOT/scripts/_portals_lib.sh"
pl_main "${1:-status}" "${@:2}"
