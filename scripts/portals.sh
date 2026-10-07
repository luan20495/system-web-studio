#!/usr/bin/env bash
# V1 local portals: Platform, Admin and Studio (apps/platform|admin|studio), each a production build served by `next start` on its own loopback port.
#   ./scripts/portals.sh up        build (once per source change) and start the three portals; waits until each answers; fails clearly on an occupied port
#   ./scripts/portals.sh down      stop ONLY the processes this script started (pid files in .run/portals/), never anything else
#   ./scripts/portals.sh status    which portal answers where
# Ports and host are configuration (PORTAL_PLATFORM_PORT / PORTAL_ADMIN_PORT / PORTAL_STUDIO_PORT, PORTAL_HOST; defaults 3001 / 3002 / 3003 on 127.0.0.1).
# Each portal proxies /api, /oauth2 and /login/oauth2 to API_PROXY_TARGET (the backend); the three origins are baked into the build as NEXT_PUBLIC_PORTAL_URL_*
# (Next.js inlines NEXT_PUBLIC_* at build time: changing a port means `up` rebuilds; a V2 deployment passes its own origins the same way).
set -euo pipefail
PORTALS=1 . "$(dirname "$0")/_env.sh"
RUNDIR="$ROOT/.run/portals"; mkdir -p "$RUNDIR"
NAMES=(platform admin studio)
port_of() { case "$1" in platform) echo "$PORTAL_PLATFORM_PORT";; admin) echo "$PORTAL_ADMIN_PORT";; studio) echo "$PORTAL_STUDIO_PORT";; esac; }
listener_of() { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1 || true; }
# ours = the pid recorded when the portal answered is still the one LISTENING on its port (a reused pid number never matches a different listener)
alive() { local pf="$RUNDIR/$1.pid"; [ -f "$pf" ] && [ "$(listener_of "$(port_of "$1")")" = "$(cat "$pf")" ]; }
code() { curl -s -o /dev/null -m 5 -w '%{http_code}' "http://${PORTAL_HOST}:$1/" 2>/dev/null || true; }

cmd_up() {
  # 1. every port must be free or already ours; a foreign process is named and left alone
  local n p who
  for n in "${NAMES[@]}"; do
    p=$(port_of "$n"); who=$(listener_of "$p")
    if [ -n "$who" ] && ! alive "$n"; then
      echo "ERROR: port $p (portal $n) is already used by pid $who ($(ps -p "$who" -o comm= 2>/dev/null | tail -c 60))." >&2
      echo "       Free it, or choose another port: PORTAL_$(echo "$n" | tr a-z A-Z)_PORT=<port> ./scripts/portals.sh up   (this script never kills a process it did not start)" >&2
      exit 2
    fi
  done
  [ -d node_modules ] || npm ci --no-audit --no-fund
  # 2. build with the three origins and the API address of this machine
  local export_env=( NEXT_PUBLIC_API_MODE=http API_PROXY_TARGET="$API_PROXY_TARGET" HBL_ENV=local \
    NEXT_PUBLIC_PORTAL_URL_PLATFORM="http://${PORTAL_HOST}:${PORTAL_PLATFORM_PORT}" NEXT_PUBLIC_PORTAL_URL_ADMIN="http://${PORTAL_HOST}:${PORTAL_ADMIN_PORT}" NEXT_PUBLIC_PORTAL_URL_STUDIO="http://${PORTAL_HOST}:${PORTAL_STUDIO_PORT}" )
  local stamp; stamp="$(echo "${export_env[*]}" | shasum | cut -c1-16)"
  for n in "${NAMES[@]}"; do
    if alive "$n"; then echo "portal $n already running (pid $(cat "$RUNDIR/$n.pid"))"; continue; fi
    if [ ! -f "apps/$n/.next/BUILD_ID" ] || [ "$(cat "$RUNDIR/$n.stamp" 2>/dev/null)" != "$stamp" ]; then
      echo "building portal $n ..."
      ( cd "apps/$n" && env "${export_env[@]}" npx next build > "$RUNDIR/$n.build.log" 2>&1 < /dev/null ) || { echo "ERROR: build of $n failed; see $RUNDIR/$n.build.log" >&2; tail -15 "$RUNDIR/$n.build.log" >&2; exit 1; }
      echo "$stamp" > "$RUNDIR/$n.stamp"
    fi
  done
  # 3. start
  for n in "${NAMES[@]}"; do
    alive "$n" && continue
    p=$(port_of "$n")
    # `;` not `&&`: the background job must be the server itself, not a subshell that would keep this script's stdout open
    ( cd "apps/$n"; nohup env "${export_env[@]}" npx next start -H "$PORTAL_HOST" -p "$p" > "$RUNDIR/$n.log" 2>&1 < /dev/null & echo $! > "$RUNDIR/$n.launcher" )
  done
  # 4. wait until each one answers (any HTTP status below 500)
  local ok=1 c
  for n in "${NAMES[@]}"; do
    p=$(port_of "$n"); c=000
    for _ in $(seq 1 60); do c=$(code "$p"); c=${c:-000}; [ "$c" != 000 ] && [ "$c" -lt 500 ] && break; sleep 1; done
    if [ "$c" != 000 ] && [ "$c" -lt 500 ]; then listener_of "$p" > "$RUNDIR/$n.pid"; echo "portal $n  http://${PORTAL_HOST}:$p/  -> HTTP $c  (pid $(cat "$RUNDIR/$n.pid"))"; else echo "ERROR: portal $n did not answer on port $p (HTTP $c); see $RUNDIR/$n.log" >&2; tail -8 "$RUNDIR/$n.log" >&2; ok=0; fi
  done
  [ "$ok" = 1 ] || exit 1
}

cmd_down() {
  local n pid p
  for n in "${NAMES[@]}"; do
    [ -f "$RUNDIR/$n.pid" ] || continue
    pid=$(cat "$RUNDIR/$n.pid"); p=$(port_of "$n")
    # only the pid that was recorded as THE listener of this portal, and only while it still is; its launcher (npx) goes with it
    if [ "$(listener_of "$p")" = "$pid" ]; then kill -TERM "$pid" 2>/dev/null || true; fi
    [ -f "$RUNDIR/$n.launcher" ] && { kill -TERM "$(cat "$RUNDIR/$n.launcher")" 2>/dev/null || true; rm -f "$RUNDIR/$n.launcher"; }
    for _ in $(seq 1 20); do [ -z "$(listener_of "$p")" ] && break; sleep 0.5; done
    rm -f "$RUNDIR/$n.pid"
    [ -z "$(listener_of "$p")" ] && echo "portal $n stopped" || echo "WARNING: port $p is still used by pid $(listener_of "$p") (not started by this script; left alone)" >&2
  done
}

cmd_status() {
  local n p c rc=0
  for n in "${NAMES[@]}"; do p=$(port_of "$n"); c=$(code "$p"); c=${c:-000}; echo "portal $n  http://${PORTAL_HOST}:$p/  HTTP $c  $(alive "$n" && echo "pid $(cat "$RUNDIR/$n.pid")" || echo "not started by this script")"; { [ "$c" != 000 ] && [ "$c" -lt 500 ]; } || rc=1; done
  return $rc
}

case "${1:-}" in up) cmd_up;; down) cmd_down;; status) cmd_status;; *) echo "usage: $0 up|down|status" >&2; exit 64;; esac
