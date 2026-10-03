#!/usr/bin/env bash
# Start infrastructure (docker compose), the Kotlin API and the Next.js UI in http mode.
. "$(dirname "$0")/_env.sh"
# Optional SSO settings written by scripts/sso-up.sh
if [ -f .run/sso.env ]; then set -a; . .run/sso.env; set +a; fi
docker compose up -d --wait
# Git server for code projects: one-time setup creates the bot/org/token (idempotent)
if [ ! -f .run/forgejo.env ] || ! grep -q '^FORGEJO_TOKEN=' .run/forgejo.env; then ./scripts/forgejo-setup.sh; set -a; . .run/forgejo.env; set +a; fi
# render worker: the Studio preview renderer as a local process (127.0.0.1 only, token-protected); built with the repo's TypeScript
if ! curl -fsS "http://127.0.0.1:${RENDER_PORT}/health" >/dev/null 2>&1; then
  npx tsc -p workers/render/tsconfig.json
  # safe-render previews (stage F) use the local Chrome if present: JavaScript disabled, all network requests blocked
  [ -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ] && export PREVIEW_CHROME_PATH="${PREVIEW_CHROME_PATH:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
  nohup node workers/render/dist/workers/render/server.js > .run/render.log 2>&1 < /dev/null & echo $! > .run/render.pid
  for _ in $(seq 1 20); do curl -fsS "http://127.0.0.1:${RENDER_PORT}/health" >/dev/null 2>&1 && break; sleep 0.5; done
fi
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
  nohup npx next start -H 127.0.0.1 -p "$FRONTEND_PORT" > .run/frontend.log 2>&1 < /dev/null & echo $! > .run/frontend.pid
  for _ in $(seq 1 60); do curl -fsS "http://127.0.0.1:${FRONTEND_PORT}/" >/dev/null 2>&1 && break; sleep 1; done
fi
# build runner (sandboxed builds of code projects in Docker; never inside the API)
if ! { [ -f .run/runner.pid ] && kill -0 "$(cat .run/runner.pid)" 2>/dev/null; }; then
  nohup env RUNNER_API="$BUILD_API_BASE" BUILD_RUNNER_TOKEN="$BUILD_RUNNER_TOKEN" BUILD_NETWORK=hbl_build node workers/runner/runner.mjs > .run/runner.log 2>&1 < /dev/null & echo $! > .run/runner.pid
fi
echo "UI:      http://localhost:${FRONTEND_PORT}"
echo "API:     http://127.0.0.1:8080  (Swagger UI: /swagger-ui.html, profile local)"
echo "Sites:   ${SITES_ORIGIN}/<slug>/  (published sites, gateway)   Render worker: 127.0.0.1:${RENDER_PORT}"
echo "Git:     ${FORGEJO_URL:-not configured} (org ${FORGEJO_ORG:-factory})   Build runner: .run/runner.log"
echo "MinIO:   http://127.0.0.1:19001   RabbitMQ: http://127.0.0.1:15675"
echo "Login:   local.admin / (LOCAL_ADMIN_PASSWORD from .env)"
