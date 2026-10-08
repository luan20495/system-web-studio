#!/usr/bin/env bash
# Auto restart for the host processes (stage L): every 30 s checks the API liveness, the UI and the render worker; after two failed checks
# in a row it runs the environment's (idempotent) start script, which starts only what is not running. Containers restart through Docker
# (restart: unless-stopped). One instance per environment; restarts are logged to .run/watchdog-<env>.log.
# D-C0-49: a restart is a RECOVERY, never a deployment. The start script it runs starts the APPROVED public release (scripts/public-portals.sh up); the watchdog itself never builds anything.
# Bounded: after WATCHDOG_MAX_RESTARTS recoveries inside WATCHDOG_WINDOW seconds it STOPS restarting (a crash loop), writes the marker .run/public/crash-loop.json (local: .run/crash-loop-local.json,
# shown by `public-portals.sh status` as CRASH_LOOP) and keeps checking; between recoveries it backs off (WATCHDOG_BACKOFF_BASE x 2^n up to WATCHDOG_BACKOFF_MAX). Healthy again => marker cleared.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; ENVN="${1:-local}"
LOCK="$ROOT/.run/watchdog-$ENVN.pid"
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK")" 2>/dev/null; then echo "watchdog ($ENVN) already running"; exit 0; fi
echo $$ > "$LOCK"
if [ "$ENVN" = public ]; then set -a; . "$ROOT/.run/public/public.env"; set +a; API="http://127.0.0.1:${API_PORT:-18081}"; UI="http://127.0.0.1:${UI_PORT:-3101}"; RENDER="http://127.0.0.1:${RENDER_PORT_PUBLIC:-28095}"; UP="$ROOT/scripts/public-up.sh"
else API="http://127.0.0.1:8080"; UI="http://127.0.0.1:${FRONTEND_PORT:-3100}"; RENDER="http://127.0.0.1:${RENDER_PORT:-18095}"; UP="$ROOT/scripts/run-local.sh"; fi
INTERVAL="${WATCHDOG_INTERVAL:-30}"; MAXR="${WATCHDOG_MAX_RESTARTS:-5}"; WINDOW="${WATCHDOG_WINDOW:-900}"; BOFF="${WATCHDOG_BACKOFF_BASE:-30}"; BOFFMAX="${WATCHDOG_BACKOFF_MAX:-600}"
if [ "$ENVN" = public ]; then MARKER="$ROOT/.run/public/crash-loop.json"; else MARKER="$ROOT/.run/crash-loop-local.json"; fi
LOGF="$ROOT/.run/watchdog-$ENVN.log"; attempts=(); consecutive=0; next_allowed=0; fails=0
while true; do
  bad=""
  curl -fsS -m 5 "$API/actuator/health/liveness" >/dev/null 2>&1 || bad="$bad api"
  if [ "$ENVN" = public ] && [ "${PUBLIC_PORTALS:-true}" = true ]; then
    # public: the three portals (each answers on its own loopback port) and the tunnel process; the legacy root UI is not part of this topology
    for pp in "${PORTAL_PLATFORM_PORT_PUBLIC:-3201}" "${PORTAL_ADMIN_PORT_PUBLIC:-3202}" "${PORTAL_STUDIO_PORT_PUBLIC:-3203}"; do curl -fsS -m 5 -o /dev/null "http://127.0.0.1:$pp/" 2>/dev/null || bad="$bad portal:$pp"; done
    { [ -f "$ROOT/.run/public/tunnel.pid" ] && kill -0 "$(cat "$ROOT/.run/public/tunnel.pid")" 2>/dev/null; } || bad="$bad tunnel"
  else
    curl -fsS -m 5 -o /dev/null "$UI/login" 2>/dev/null || bad="$bad ui"
  fi
  curl -fsS -m 5 "$RENDER/health" >/dev/null 2>&1 || bad="$bad render"
  if [ -n "$bad" ]; then fails=$((fails + 1)); else
    fails=0; consecutive=0; next_allowed=0
    if [ -f "$MARKER" ]; then rm -f "$MARKER"; echo "$(date -u +%FT%TZ) healthy again: crash-loop marker cleared" >> "$LOGF"; fi
  fi
  if [ "$fails" -ge 2 ]; then
    now="$(date +%s)"; kept=(); for t in ${attempts[@]+"${attempts[@]}"}; do [ $((now - t)) -lt "$WINDOW" ] && kept+=("$t"); done; attempts=(${kept[@]+"${kept[@]}"})
    if [ "${#attempts[@]}" -ge "$MAXR" ]; then
      if [ ! -f "$MARKER" ]; then
        mkdir -p "$(dirname "$MARKER")"; printf '{"since":"%s","restarts":%s,"windowSec":%s,"down":"%s"}\n' "$(date -u +%FT%TZ)" "${#attempts[@]}" "$WINDOW" "${bad# }" > "$MARKER"
        echo "$(date -u +%FT%TZ) CRASH LOOP: ${#attempts[@]} recoveries in ${WINDOW}s and still down:$bad -> automatic restarts paused (nothing is deployed)" >> "$LOGF"
      fi
    elif [ "$now" -lt "$next_allowed" ]; then :
    else
      attempts+=("$now"); wait=$((BOFF * (1 << consecutive))); [ "$wait" -gt "$BOFFMAX" ] && wait="$BOFFMAX"; next_allowed=$((now + wait)); consecutive=$((consecutive + 1))
      echo "$(date -u +%FT%TZ) down:$bad -> restarting via $(basename "$UP") (recovery ${#attempts[@]}/$MAXR in ${WINDOW}s, next allowed in ${wait}s)" >> "$LOGF"
      "$UP" >> "$LOGF" 2>&1 || echo "$(date -u +%FT%TZ) $(basename "$UP") exited non-zero" >> "$LOGF"; fails=0
    fi
  fi
  sleep "$INTERVAL"
done
