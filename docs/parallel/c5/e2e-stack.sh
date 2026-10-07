#!/usr/bin/env bash
# C5 · local real-backend E2E stack for macOS (docs/parallel/c5/MAC_RUN_2026-10-06.md). Everything is parameterised so it never touches another stack:
# its own containers (prefix $E2E_STACK_NAME), its own ports, its own state directory. It starts nothing it does not own and kills only the process LISTENING on its own port.
#
#   e2e-stack.sh prepare          detached git worktree = $E2E_BASE_REF (+ $E2E_MERGE_REFS, empty by default) (conflict = stop, nothing is resolved for you) and a random-secret env file (mode 600)
#   e2e-stack.sh up               prepare + infra + backend + render worker + Studio (build with the proxy target) — idempotent
#   e2e-stack.sh infra-up | backend-up | backend-launch (no wait) | backend-down | backend-restart | backend-pause | backend-resume | store-pause | store-resume | render-pause | render-resume | render-up | studio-up
#   e2e-stack.sh e2e [flows]      run the real-backend suite (E2E_SHUFFLE_SEED=<n> shuffles the order). Hooks for E2E-12/S6/S7/14 are wired to this script.
#   e2e-stack.sh status | down [--infra] [--worktree]
#
# No secret is printed or committed: the env file lives in $E2E_STACK_DIR (outside the repo).
set -euo pipefail

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
REPO="${E2E_REPO:-$(cd "$(dirname "$SELF")/../../.." && pwd)}"             # the C5 checkout: Studio, render worker and the suite are run from here
NAME="${E2E_STACK_NAME:-c5e2e}"
DIR="${E2E_STACK_DIR:-$HOME/.xweb-e2e-stack/$NAME}"
BASE_REF="${E2E_BASE_REF:-integration/v2}"
MERGE_REFS="${E2E_MERGE_REFS-}"                                             # EMPTY by default: integration/v2 >= 3333aa7 already contains V29 and the C3 Management API. Before that: E2E_MERGE_REFS="wire/v29-run-persistence agent/c3-data-prod"
BACKEND_REPO="${E2E_BACKEND_REPO:-$REPO}"                                    # any checkout of the same repository that has those refs
API_PORT="${E2E_API_PORT:-38080}"; PG_PORT="${E2E_PG_PORT:-35432}"; REDIS_PORT="${E2E_REDIS_PORT:-36379}"; MINIO_PORT="${E2E_MINIO_PORT:-39000}"
RABBIT_PORT="${E2E_RABBIT_PORT:-35672}"; SITES_PORT="${E2E_SITES_PORT:-38088}"; RENDER_PORT="${E2E_RENDER_PORT:-38095}"; STUDIO_PORT="${E2E_STUDIO_PORT:-3003}"
JAVA21="${E2E_JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
CHROME="${E2E_CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
ENVF="$DIR/stack.env"; WT="$DIR/backend-worktree"; LOGS="$DIR/logs"

say() { printf '[e2e-stack] %s\n' "$*"; }
die() { printf '[e2e-stack] ERROR: %s\n' "$*" >&2; exit 1; }
listener() { lsof -ti "tcp:$1" -sTCP:LISTEN 2>/dev/null || true; }                 # LISTEN only: never kill client connections of another process
kill_port() { local p; p="$(listener "$1")"; [ -z "$p" ] || { kill $p; for _ in $(seq 1 30); do [ -z "$(listener "$1")" ] && return 0; sleep 1; done; return 1; }; }
wait_http() { local url="$1" secs="$2"; for _ in $(seq 1 "$secs"); do curl -fsS "$url" >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }
need_env() { [ -f "$ENVF" ] || die "no $ENVF: run '$0 prepare' first"; set -a; . "$ENVF"; set +a; }

prepare() {
  command -v docker >/dev/null || die "docker not found"; [ -x "$JAVA21/bin/java" ] || die "JDK 21 not found at $JAVA21 (set E2E_JAVA_HOME): the backend needs JDK 21, the default java may be older/x86"
  mkdir -p "$DIR" "$LOGS"; chmod 700 "$DIR"
  if [ ! -d "$WT" ]; then
    say "creating the backend worktree from $BASE_REF + $MERGE_REFS (detached, never pushed)"
    git -C "$BACKEND_REPO" worktree add --detach "$WT" "$BASE_REF" >/dev/null
    for r in $MERGE_REFS; do git -C "$WT" merge --no-edit "$r" >/dev/null || die "merge of $r conflicts: resolve it deliberately (C0's decision), this script does not"; done
    ln -sfn "$REPO/node_modules" "$WT/node_modules"                               # for the render worker's tsc only
  fi
  if [ ! -f "$ENVF" ]; then
    say "writing $ENVF (random secrets, mode 600)"
    ( umask 077; cat > "$ENVF" <<EOF
DATABASE_URL=jdbc:postgresql://127.0.0.1:$PG_PORT/system_web_studio
DATABASE_USER=studio
DATABASE_PASSWORD=$(openssl rand -hex 12)
REDIS_HOST=127.0.0.1
REDIS_PORT=$REDIS_PORT
MINIO_ENDPOINT=http://127.0.0.1:$MINIO_PORT
MINIO_ROOT_USER=studio-minio
MINIO_ROOT_PASSWORD=$(openssl rand -hex 12)
RABBITMQ_HOST=127.0.0.1
RABBITMQ_PORT=$RABBIT_PORT
RABBITMQ_USER=studio
RABBITMQ_PASSWORD=$(openssl rand -hex 12)
LOCAL_ADMIN_PASSWORD=Aa1-$(openssl rand -hex 10)
SECRETS_MASTER_KEY=$(openssl rand -base64 32)
RENDER_TOKEN=$(openssl rand -hex 24)
COOKIE_SECURE=false
SPRING_PROFILES_ACTIVE=local
SERVER_PORT=$API_PORT
CORS_ALLOWED_ORIGINS=http://127.0.0.1:$STUDIO_PORT,http://127.0.0.1:3001,http://127.0.0.1:3002
WEB_ORIGIN_STUDIO=http://127.0.0.1:$STUDIO_PORT
WEB_ORIGIN_ADMIN=http://127.0.0.1:3002
WEB_ORIGIN_PLATFORM=http://127.0.0.1:3001
MAX_PROJECTS_PER_WORKSPACE=100000
DATA_PLATFORM_ENABLED=true
WORKFLOW_ENABLED=true
RATE_LIMIT_PUBLISH_MAX=500
DEPLOY_PROVIDER=static
SITES_ORIGIN=http://127.0.0.1:$SITES_PORT
SITES_DATA_API_BASE=${E2E_SITES_DATA_API_BASE:-}
STUDIO_ORIGIN=http://127.0.0.1:$STUDIO_PORT
RENDER_URL=http://127.0.0.1:$RENDER_PORT
RENDER_PORT=$RENDER_PORT
JAVA_HOME=$JAVA21
EOF
    )
  fi
}

run_container() { # name, args...
  local n="$NAME-$1"; shift
  if docker ps -a --format '{{.Names}}' | grep -qx "$n"; then docker start "$n" >/dev/null; else docker run -d --name "$n" "$@" >/dev/null; fi
}
infra_up() {
  need_env
  run_container pg -p "127.0.0.1:$PG_PORT:5432" -e POSTGRES_USER=studio -e "POSTGRES_PASSWORD=$DATABASE_PASSWORD" -e POSTGRES_DB=system_web_studio postgres:17.6
  run_container redis -p "127.0.0.1:$REDIS_PORT:6379" redis:8.2.1-alpine
  run_container minio -p "127.0.0.1:$MINIO_PORT:9000" -e "MINIO_ROOT_USER=$MINIO_ROOT_USER" -e "MINIO_ROOT_PASSWORD=$MINIO_ROOT_PASSWORD" -e MINIO_DEFAULT_BUCKETS=studio-assets bitnamilegacy/minio:2025.7.23-debian-12-r5
  run_container rabbit -p "127.0.0.1:$RABBIT_PORT:5672" -e RABBITMQ_DEFAULT_USER=studio -e "RABBITMQ_DEFAULT_PASS=$RABBITMQ_PASSWORD" rabbitmq:4-management-alpine
  run_container sites -p "127.0.0.1:$SITES_PORT:8080" -e "API_UPSTREAM=host.docker.internal:$API_PORT" -e SITES_HOST=sites.localhost --add-host host.docker.internal:host-gateway \
    -v "$WT/infra/sites-gateway/default.conf.template:/etc/nginx/templates/default.conf.template:ro" --tmpfs /tmp --tmpfs /etc/nginx/conf.d:uid=101,gid=101,mode=0755 nginxinc/nginx-unprivileged:1.29-alpine
  for _ in $(seq 1 40); do docker exec "$NAME-pg" pg_isready -U studio -d system_web_studio >/dev/null 2>&1 && return 0; sleep 1; done; die "postgres did not become ready"
}

backend_launch() {   # starts the process and returns at once (the E2E start hook has a 60 s budget; the flows wait for the API themselves)
  need_env; [ -z "$(listener "$API_PORT")" ] || { say "API port $API_PORT already has a listener"; return 0; }
  # kotlinc needs more heap than the default (the first compile died with OutOfMemoryError: GC overhead limit exceeded)
  ( cd "$WT/backend" && PATH="$JAVA_HOME/bin:$PATH" nohup ./gradlew bootRun --console=plain -Dorg.gradle.jvmargs=-Xmx3g -Pkotlin.daemon.jvmargs=-Xmx3g >> "$LOGS/backend.log" 2>&1 & )
  say "backend starting (first start compiles, ~1.5 min); log: $LOGS/backend.log"
}
backend_up() {
  backend_launch; need_env
  wait_http "http://127.0.0.1:$API_PORT/actuator/health/liveness" 300 || die "backend did not come up; see $LOGS/backend.log"
}
backend_down() { need_env; kill_port "$API_PORT" || die "API on $API_PORT did not stop"; }
backend_restart() { backend_down; backend_up; }
backend_pause() { need_env; local p; p="$(listener "$API_PORT")"; [ -n "$p" ] || die "no API listening on $API_PORT"; kill -STOP $p; }    # a HANG, not an outage (E2E-S9)
# fault injection for the release flows (E2E-P*): the artifact store or the render worker is PAUSED (not stopped), so a publish stays in a known step and the scope lease stays alive
store_pause() { need_env; docker pause "$NAME-minio" >/dev/null; }
store_resume() { need_env; docker unpause "$NAME-minio" >/dev/null 2>&1 || true; }
render_pause() { need_env; local p; p="$(listener "$RENDER_PORT")"; [ -n "$p" ] || die "no render worker on $RENDER_PORT"; kill -STOP $p; }
render_resume() { need_env; local p; p="$(listener "$RENDER_PORT")"; [ -z "$p" ] || kill -CONT $p; }
backend_resume() { need_env; local p; p="$(listener "$API_PORT")"; [ -z "$p" ] || kill -CONT $p; }
render_up() {
  need_env; [ -z "$(listener "$RENDER_PORT")" ] || return 0
  ( cd "$WT" && npx tsc -p workers/render/tsconfig.json )
  ( cd "$WT" && RENDER_PORT="$RENDER_PORT" RENDER_TOKEN="$RENDER_TOKEN" nohup node workers/render/dist/workers/render/server.js >> "$LOGS/render.log" 2>&1 & )
  wait_http "http://127.0.0.1:$RENDER_PORT/health" 30 || die "render worker did not start"
}
studio_up() {
  need_env; kill_port "$STUDIO_PORT" || true
  # the proxy target is BAKED IN AT BUILD TIME (next build); setting it only on `next start` has no effect
  # NEXT_DIST_DIR keeps this stack's build apart from the checkout's normal `.next` (git-ignored: .next-check*/), so a gate build never breaks a running Studio
  export NEXT_DIST_DIR=".next-check-$NAME"
  # `next build` rewrites the tracked apps/studio/tsconfig.json (adds the dist dir to "include"): keep a copy and put it back so the checkout stays clean
  cp "$REPO/apps/studio/tsconfig.json" "$DIR/tsconfig.studio.orig"
  ( cd "$REPO" && API_PROXY_TARGET="http://127.0.0.1:$API_PORT" npm run build:studio > "$LOGS/studio-build.log" 2>&1 ) || { cp "$DIR/tsconfig.studio.orig" "$REPO/apps/studio/tsconfig.json"; die "Studio build failed; see $LOGS/studio-build.log"; }
  cp "$DIR/tsconfig.studio.orig" "$REPO/apps/studio/tsconfig.json"
  ( cd "$REPO/apps/studio" && nohup npx next start -H 127.0.0.1 -p "$STUDIO_PORT" >> "$LOGS/studio.log" 2>&1 & )
  wait_http "http://127.0.0.1:$STUDIO_PORT/api/v1/auth/config" 60 || die "Studio does not reach the API (check the build's proxy target); see $LOGS/studio.log"
}

e2e() {
  need_env; local flows="${1:-}"
  ( cd "$REPO" && E2E_ONLY="$flows" E2E_STUDIO_URL="http://127.0.0.1:$STUDIO_PORT" E2E_ADMIN_USER=local.admin E2E_ADMIN_PASSWORD="$LOCAL_ADMIN_PASSWORD" E2E_CHROME="$CHROME" \
      E2E_DURABLE_RUN_STORES=1 E2E_RESTART_BACKEND_CMD="$SELF backend-restart" E2E_STOP_BACKEND_CMD="$SELF backend-down" E2E_PAUSE_STORE_CMD="$SELF store-pause" E2E_RESUME_STORE_CMD="$SELF store-resume" E2E_PAUSE_RENDER_CMD="$SELF render-pause" E2E_RESUME_RENDER_CMD="$SELF render-resume" E2E_PAUSE_BACKEND_CMD="$SELF backend-pause" E2E_RESUME_BACKEND_CMD="$SELF backend-resume" E2E_START_BACKEND_CMD="$SELF backend-launch" \
      E2E_BACKEND_URL="http://127.0.0.1:$API_PORT" E2E_BACKEND_HEAD="$(git -C "$WT" log --oneline -1) [$BASE_REF${MERGE_REFS:+ + $MERGE_REFS}]" E2E_OUT_DIR="${E2E_OUT_DIR:-$REPO/.run/e2e-real}" \
      npm run test:e2e:real )
}

status() {
  for p in "api $API_PORT" "render $RENDER_PORT" "studio $STUDIO_PORT" "pg $PG_PORT" "redis $REDIS_PORT" "minio $MINIO_PORT" "rabbit $RABBIT_PORT" "sites $SITES_PORT"; do
    set -- $p; printf '%-7s :%-6s %s\n' "$1" "$2" "$([ -n "$(listener "$2")" ] && echo listening || echo -)"
  done
  curl -fsS "http://127.0.0.1:$API_PORT/actuator/health/readiness" 2>/dev/null && echo || echo "api readiness: not answering"
}
down() {
  need_env 2>/dev/null || true
  kill_port "$STUDIO_PORT" || true; kill_port "$RENDER_PORT" || true; kill_port "$API_PORT" || true
  for a in "$@"; do
    case "$a" in
      --infra) for c in pg redis minio rabbit sites; do docker rm -f "$NAME-$c" >/dev/null 2>&1 || true; done ;;
      --worktree) git -C "$BACKEND_REPO" worktree remove --force "$WT" 2>/dev/null || true ;;
    esac
  done
}

case "${1:-}" in
  prepare) prepare ;;
  up) prepare; infra_up; backend_up; render_up; studio_up; status ;;
  infra-up) prepare; infra_up ;;
  backend-up) backend_up ;;
  backend-launch) backend_launch ;;
  backend-down) backend_down ;;
  backend-restart) backend_restart ;;
  backend-pause) backend_pause ;;
  store-pause) store_pause ;;
  store-resume) store_resume ;;
  render-pause) render_pause ;;
  render-resume) render_resume ;;
  backend-resume) backend_resume ;;
  render-up) render_up ;;
  studio-up) studio_up ;;
  e2e) shift; e2e "${1:-}" ;;
  status) status ;;
  down) shift; down "$@" ;;
  *) sed -n '2,13p' "$SELF"; exit 2 ;;
esac
