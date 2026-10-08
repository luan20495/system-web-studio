#!/usr/bin/env bash
# V1 local portals: Platform, Admin and Studio (apps/platform|admin|studio), each a production build served by `next start` on its own loopback port. SOURCE-AWARE (D-C0-45):
#   ./scripts/portals.sh up [--force-rebuild]   fingerprint the source + build environment; build only the portals whose fingerprint changed (into apps/<p>/.next-local-<fp>); keep a portal
#                                               that already runs exactly that build; restart a stale one that THIS script started; start the ones that are down. A failed build stops
#                                               before anything is touched. A port used by a process this script did not start is refused (exit 2) and never killed.
#   ./scripts/portals.sh down                   stop ONLY the processes this script started (pid + start time recorded in .run/portals/)
#   ./scripts/portals.sh restart | build | status [--force-rebuild]    status shows pid, port, built / running / desired fingerprint, running commit (+dirty) and CURRENT / STALE / DOWN / FOREIGN
# Ports and host are configuration (PORTAL_PLATFORM_PORT / PORTAL_ADMIN_PORT / PORTAL_STUDIO_PORT, PORTAL_HOST; defaults 3001 / 3002 / 3003 on 127.0.0.1). Each portal proxies /api, /oauth2 and
# /login/oauth2 to API_PROXY_TARGET; the three origins are baked into the build as NEXT_PUBLIC_PORTAL_URL_* (so a port change is a different fingerprint and rebuilds).
# The public stack has its own script, state directory, ports and dist directories: scripts/public-portals.sh. The two never share anything.
set -euo pipefail
PORTALS=1 . "$(dirname "$0")/_env.sh"
MODE=local; RUNDIR="$ROOT/.run/portals"; PL_PREFIX=""; NAMES=(platform admin studio); HOST="$PORTAL_HOST"
pl_port_of() { case "$1" in platform) echo "$PORTAL_PLATFORM_PORT";; admin) echo "$PORTAL_ADMIN_PORT";; studio) echo "$PORTAL_STUDIO_PORT";; esac; }
BUILD_ENV=( NEXT_PUBLIC_API_MODE=http API_PROXY_TARGET="$API_PROXY_TARGET" HBL_ENV=local
  NEXT_PUBLIC_PORTAL_URL_PLATFORM="http://${PORTAL_HOST}:${PORTAL_PLATFORM_PORT}" NEXT_PUBLIC_PORTAL_URL_ADMIN="http://${PORTAL_HOST}:${PORTAL_ADMIN_PORT}" NEXT_PUBLIC_PORTAL_URL_STUDIO="http://${PORTAL_HOST}:${PORTAL_STUDIO_PORT}" )
RUN_ENV=()
[ -d "$ROOT/node_modules" ] || npm ci --no-audit --no-fund      # as before: a fresh checkout installs its dependencies
. "$ROOT/scripts/_portals_lib.sh"
pl_main "$@"
