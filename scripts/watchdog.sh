#!/usr/bin/env bash
# Auto restart for the host processes (stage L): every 30 s checks the API liveness, the UI and the render worker; after two failed checks
# in a row it runs the environment's (idempotent) start script, which starts only what is not running. Containers restart through Docker
# (restart: unless-stopped). One instance per environment; restarts are logged to .run/watchdog-<env>.log.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; ENVN="${1:-local}"
LOCK="$ROOT/.run/watchdog-$ENVN.pid"
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK")" 2>/dev/null; then echo "watchdog ($ENVN) already running"; exit 0; fi
echo $$ > "$LOCK"
if [ "$ENVN" = public ]; then set -a; . "$ROOT/.run/public/public.env"; set +a; API="http://127.0.0.1:${API_PORT:-18081}"; UI="http://127.0.0.1:${UI_PORT:-3101}"; RENDER="http://127.0.0.1:${RENDER_PORT_PUBLIC:-28095}"; UP="$ROOT/scripts/public-up.sh"
else API="http://127.0.0.1:8080"; UI="http://127.0.0.1:${FRONTEND_PORT:-3100}"; RENDER="http://127.0.0.1:${RENDER_PORT:-18095}"; UP="$ROOT/scripts/run-local.sh"; fi
fails=0
while true; do
  bad=""
  curl -fsS -m 5 "$API/actuator/health/liveness" >/dev/null 2>&1 || bad="$bad api"
  curl -fsS -m 5 -o /dev/null "$UI/login" 2>/dev/null || bad="$bad ui"
  curl -fsS -m 5 "$RENDER/health" >/dev/null 2>&1 || bad="$bad render"
  if [ -n "$bad" ]; then fails=$((fails + 1)); else fails=0; fi
  if [ "$fails" -ge 2 ]; then
    echo "$(date -u +%FT%TZ) down:$bad -> restarting via $(basename "$UP")" >> "$ROOT/.run/watchdog-$ENVN.log"
    "$UP" >> "$ROOT/.run/watchdog-$ENVN.log" 2>&1; fails=0
  fi
  sleep 30
done
