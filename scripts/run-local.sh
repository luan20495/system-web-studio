#!/usr/bin/env bash
# Start infrastructure (docker compose), the Kotlin API and the Next.js UI in http mode.
. "$(dirname "$0")/_env.sh"
# Optional SSO settings written by scripts/sso-up.sh
if [ -f .run/sso.env ]; then set -a; . .run/sso.env; set +a; fi
docker compose up -d --wait
if ! curl -fsS http://127.0.0.1:8080/actuator/health/liveness >/dev/null 2>&1; then
  (cd backend && nohup ./gradlew bootRun --console=plain > "$ROOT/.run/backend.log" 2>&1 & echo $! > "$ROOT/.run/backend.pid")
fi
echo "Waiting for the API (first start compiles, ~1 min)…"
for _ in $(seq 1 120); do curl -fsS http://127.0.0.1:8080/actuator/health/liveness >/dev/null 2>&1 && break; sleep 2; done
curl -fsS http://127.0.0.1:8080/actuator/health/liveness >/dev/null || { echo "API did not start; see .run/backend.log" >&2; exit 1; }
if ! curl -fsS "http://127.0.0.1:${FRONTEND_PORT}/" >/dev/null 2>&1; then
  [ -d node_modules ] || npm ci
  export NEXT_DIST_DIR=.next-http NEXT_PUBLIC_API_MODE=http API_PROXY_TARGET=http://127.0.0.1:8080
  # Production build: NEXT_PUBLIC_* is baked in at build time, and there is no HMR socket to flake in tests.
  npx next build > .run/frontend-build.log 2>&1 < /dev/null || { echo "UI build failed; see .run/frontend-build.log" >&2; exit 1; }
  nohup npx next start -p "$FRONTEND_PORT" > .run/frontend.log 2>&1 < /dev/null & echo $! > .run/frontend.pid
  for _ in $(seq 1 60); do curl -fsS "http://127.0.0.1:${FRONTEND_PORT}/" >/dev/null 2>&1 && break; sleep 1; done
fi
echo "UI:      http://localhost:${FRONTEND_PORT}"
echo "API:     http://127.0.0.1:8080  (Swagger UI: /swagger-ui.html, profile local)"
echo "MinIO:   http://127.0.0.1:19001   RabbitMQ: http://127.0.0.1:15675"
echo "Login:   local.admin / (LOCAL_ADMIN_PASSWORD from .env)"
