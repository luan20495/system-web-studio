#!/usr/bin/env bash
# Stop the API and UI started by run-local.sh. Add --infra to also stop containers (data volumes are kept).
. "$(dirname "$0")/_env.sh"
for n in frontend backend render runner; do
  if [ -f ".run/$n.pid" ]; then kill "$(cat ".run/$n.pid")" 2>/dev/null || true; rm -f ".run/$n.pid"; fi
done
pkill -f "system-web-studio.*bootRun" 2>/dev/null || true
# LISTEN only: without -sTCP:LISTEN this also matched Docker Desktop's network proxy (containers such as the sites gateway keep
# connections to :8080 through it) and killed com.docker.backend, taking Docker Desktop down.
lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill 2>/dev/null || true
lsof -ti tcp:"$FRONTEND_PORT" -sTCP:LISTEN | xargs kill 2>/dev/null || true
# graceful shutdown lets in-flight requests finish: wait until the ports are really free before returning
for port in 8080 "$FRONTEND_PORT"; do
  for _ in $(seq 1 60); do lsof -ti tcp:"$port" -sTCP:LISTEN >/dev/null 2>&1 || break; sleep 1; done
done
[ "${1:-}" = "--infra" ] && docker compose stop
echo stopped
