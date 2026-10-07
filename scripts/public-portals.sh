#!/usr/bin/env bash
# PUBLIC 3-portal ingress (D-C0-39): Platform, Admin and Studio (apps/platform|admin|studio) as production builds of THIS checkout, each on its own loopback port
# behind its own hostname (1 portal = 1 hostname: every portal serves /_next/** from the root of its origin).
#   internet -> Cloudflare -> tunnel hbl-studio -> 127.0.0.1:$PORTAL_*_PORT_PUBLIC (next start) -> /api, /oauth2, /login/oauth2 -> API 127.0.0.1:$API_PORT (same-origin proxy)
#   ./scripts/public-portals.sh up|down|status
# Reads .run/public/public.env (written by public-up.sh). Never touches the local portals (scripts/portals.sh, .next) nor a process it did not start:
# the build goes to NEXT_DIST_DIR=.next-public, pid files are .run/public/portal-<name>.pid.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
RUN=.run/public; ENVF="$RUN/public.env"
[ -f "$ENVF" ] || { echo "not set up: run scripts/public-up.sh" >&2; exit 1; }
set -a; . "$ENVF"; set +a
NAMES=(platform admin studio)
host_of() { case "$1" in platform) echo "${PUBLIC_PLATFORM_HOST:-platform.toolsmcp.uk}";; admin) echo "${PUBLIC_ADMIN_HOST:-admin.toolsmcp.uk}";; studio) echo "${PUBLIC_HOST:-studio.toolsmcp.uk}";; esac; }
port_of() { case "$1" in platform) echo "${PORTAL_PLATFORM_PORT_PUBLIC:-3201}";; admin) echo "${PORTAL_ADMIN_PORT_PUBLIC:-3202}";; studio) echo "${PORTAL_STUDIO_PORT_PUBLIC:-3203}";; esac; }
listener_of() { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1 || true; }
pidf() { echo "$RUN/portal-$1.pid"; }
alive() { local pf; pf="$(pidf "$1")"; [ -f "$pf" ] && [ "$(listener_of "$(port_of "$1")")" = "$(cat "$pf")" ]; }
code() { curl -s -o /dev/null -m 5 -w '%{http_code}' "http://127.0.0.1:$1/" 2>/dev/null || true; }
# what the browser is told (baked into the build) and what the server needs at run time
BUILD_ENV=( NEXT_PUBLIC_API_MODE=http NEXT_DIST_DIR=.next-public API_PROXY_TARGET="http://127.0.0.1:${API_PORT:-18081}"
  NEXT_PUBLIC_PORTAL_URL_PLATFORM="https://$(host_of platform)" NEXT_PUBLIC_PORTAL_URL_ADMIN="https://$(host_of admin)" NEXT_PUBLIC_PORTAL_URL_STUDIO="https://$(host_of studio)" )
RUN_ENV=( NODE_ENV=production STUDIO_HSTS=true MINIO_PUBLIC_ENDPOINT="https://${PUBLIC_FILES_HOST:-studio-files.toolsmcp.uk}" SITES_ORIGIN="https://${SITES_HOST:-sites.toolsmcp.uk}" )

cmd_up() {
  local n p who stamp tsc
  for n in "${NAMES[@]}"; do
    p=$(port_of "$n"); who=$(listener_of "$p")
    if [ -n "$who" ] && ! alive "$n"; then echo "ERROR: port $p (portal $n) is used by pid $who; this script never kills a process it did not start" >&2; exit 2; fi
  done
  stamp="$(echo "${BUILD_ENV[*]}" | shasum | cut -c1-16)"
  for n in "${NAMES[@]}"; do
    alive "$n" && continue
    local app="apps/$n"
    if [ ! -f "$app/.next-public/BUILD_ID" ] || [ "$(cat "$RUN/portal-$n.stamp" 2>/dev/null)" != "$stamp" ] \
       || [ -n "$(find "$app/app" "$app/proxy.ts" "$app/next.config.ts" packages -newer "$app/.next-public/BUILD_ID" -type f -not -path '*/node_modules/*' 2>/dev/null | head -1)" ]; then
      echo "building portal $n (origin https://$(host_of "$n")) ..."
      tsc="$RUN/portal-$n.tsconfig.orig"; cp "$app/tsconfig.json" "$tsc"      # next build rewrites tsconfig.json for a custom distDir; restored below
      ( cd "$app" && env "${BUILD_ENV[@]}" NODE_ENV=production npx next build > "$ROOT/$RUN/portal-$n.build.log" 2>&1 < /dev/null ) \
        || { cp "$tsc" "$app/tsconfig.json"; echo "ERROR: build of $n failed; see $RUN/portal-$n.build.log" >&2; tail -15 "$RUN/portal-$n.build.log" >&2; exit 1; }
      cp "$tsc" "$app/tsconfig.json"; rm -f "$tsc"; echo "$stamp" > "$RUN/portal-$n.stamp"
    fi
  done
  for n in "${NAMES[@]}"; do
    alive "$n" && { echo "portal $n already running (pid $(cat "$(pidf "$n")"))"; continue; }
    ( cd "apps/$n"; nohup env "${BUILD_ENV[@]}" "${RUN_ENV[@]}" npx next start -H 127.0.0.1 -p "$(port_of "$n")" > "$ROOT/$RUN/portal-$n.log" 2>&1 < /dev/null & )
  done
  local ok=1 c
  for n in "${NAMES[@]}"; do
    p=$(port_of "$n"); c=000
    for _ in $(seq 1 60); do c=$(code "$p"); c=${c:-000}; [ "$c" != 000 ] && [ "$c" -lt 500 ] && break; sleep 1; done
    if [ "$c" != 000 ] && [ "$c" -lt 500 ]; then listener_of "$p" > "$(pidf "$n")"; echo "portal $n  127.0.0.1:$p -> HTTP $c  (pid $(cat "$(pidf "$n")"))  https://$(host_of "$n")/"
    else echo "ERROR: portal $n did not answer on $p (HTTP $c); see $RUN/portal-$n.log" >&2; tail -8 "$RUN/portal-$n.log" >&2; ok=0; fi
  done
  [ "$ok" = 1 ] || exit 1
}
cmd_down() {
  for n in "${NAMES[@]}"; do
    if alive "$n"; then kill "$(cat "$(pidf "$n")")" 2>/dev/null || true; echo "portal $n stopped"; else echo "portal $n not running (or not ours)"; fi
    rm -f "$(pidf "$n")"
  done
}
cmd_status() { for n in "${NAMES[@]}"; do printf '  %-9s 127.0.0.1:%s  %s  %s\n' "$n" "$(port_of "$n")" "$(alive "$n" && echo "ours (pid $(cat "$(pidf "$n")"))" || echo "not ours / not running")" "HTTP $(code "$(port_of "$n")")"; done; }
case "${1:-status}" in up) cmd_up;; down) cmd_down;; status) cmd_status;; *) echo "usage: $0 up|down|status" >&2; exit 2;; esac
