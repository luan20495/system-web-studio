#!/usr/bin/env bash
# C5 · local real-backend E2E stack for macOS (docs/parallel/c5/MAC_RUN_2026-10-06.md). Everything is parameterised so it never touches another stack:
# its own containers (prefix $E2E_STACK_NAME), its own ports, its own state directory. PROCESS SAFETY (docs/parallel/c5/PROCESS_SAFETY.md): every process this script starts is spawned through
# tests/lib/owned-process-cli.mjs (own process group, pid + start time + command recorded in $DIR/run/*.json) and is signalled ONLY after that identity is re-validated. There is no process-name
# sweep, no stop-by-port and no signal to a bare pid-file value; a port held by somebody else is a clear FAILURE (it names the port and the holder), never a reason to stop that holder.
#
#   e2e-stack.sh prepare          detached git worktree = $E2E_BASE_REF (+ $E2E_MERGE_REFS, empty by default) (conflict = stop, nothing is resolved for you) and a random-secret env file (mode 600)
#   e2e-stack.sh up               prepare + infra + backend + render worker + Studio (build with the proxy target) — idempotent
#   e2e-stack.sh infra-up | backend-up | backend-launch (no wait) | backend-down | backend-restart | backend-pause | backend-resume | store-pause | store-resume | render-pause | render-resume | render-up | studio-up
#   e2e-stack.sh e2e [flows]      run the real-backend suite (E2E_SHUFFLE_SEED=<n> shuffles the order). Hooks for E2E-12/S6/S7/14 are wired to this script.
#   e2e-stack.sh portals-up | portals-down   Platform + Admin portals (Studio is `studio-up`); `up` with E2E_PORTALS=1 starts them too (C0, D-C0-55)
#   e2e-stack.sh status | down [--infra] [--worktree]
# C0 (D-C0-55) additions, all opt-in so the defaults are unchanged: E2E_PLATFORM_PORT / E2E_ADMIN_PORT (web origins + CORS follow them), E2E_ORG_PERSISTENCE=true (ORGANIZATION_PERSISTENCE_ENABLED, THIS stack only),
# E2E_DATA_TARGET=1 (the V1 TLS data target `scripts/data-target.sh` at 127.0.0.1:15440 + its trust store in the API JVM, D-C0-59: Journey 05, E2E-PD01 real rows), E2E_PUBLISH_CONFIGS=true (PUBLISH_CONFIGS_ENABLED), E2E_SITES_PUBLIC_DATA=true (SITES_PUBLIC_DATA_ENABLED = the Public Runtime of D-C0-36 AND, unless E2E_SITES_DATA_API_BASE is given, SITES_DATA_API_BASE=http://127.0.0.1:<sites port>/{slug}/_data so a published page gets a non-null runtime apiBase: E2E-PD01 / PD02); the sites gateway gets GATEWAY_REAL_IP_FROM / GATEWAY_FORCE_HTTPS (without them nginx refuses to start); `status` no longer breaks on macOS bash 3.2.
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
PLATFORM_PORT="${E2E_PLATFORM_PORT:-3001}"; ADMIN_PORT="${E2E_ADMIN_PORT:-3002}"
DT_DIR="${E2E_DATA_TARGET_DIR:-$(dirname "$(cd "$REPO" && git rev-parse --path-format=absolute --git-common-dir)")/.run/data-target}"   # the shared dev data target of scripts/data-target.sh (main checkout .run/data-target); used as a CLIENT only
DT_PORT="${DATA_TARGET_PORT:-15440}"
JAVA21="${E2E_JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
CHROME="${E2E_CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
ENVF="$DIR/stack.env"; WT="$DIR/backend-worktree"; LOGS="$DIR/logs"

say() { printf '[e2e-stack] %s\n' "$*"; }
die() { printf '[e2e-stack] ERROR: %s\n' "$*" >&2; exit 1; }
OWNED_CLI="$REPO/tests/lib/owned-process-cli.mjs"; RUNST="$DIR/run"
owned() { node "$OWNED_CLI" "$@"; }                                                    # start | refresh | adopt | stop | signal | status | port-free (all validated; see the CLI header)
st() { printf '%s/%s.json' "$RUNST" "$1"; }                                            # state file (pid, pgid, start time, command) of one owned process
is_owned_up() { owned status --state "$(st "$1")" >/dev/null 2>&1; }                  # true only when the recorded pid is alive AND start time + command still match
port_free() { owned port-free "$1" >/dev/null 2>&1; }                                  # read-only check
port_wait_free() { for _ in $(seq 1 "${2:-30}"); do port_free "$1" && return 0; sleep 1; done; return 1; }
stop_owned() {   # stop ONLY what this script started under that name: validated SIGTERM -> SIGKILL of its own group, verified gone; a stale state (pid reused by someone else) is ignored, NOT signalled
  local rc=0; owned stop --state "$(st "$1")" || rc=$?
  case "$rc" in 0) ;; 4) say "$1: the recorded pid belongs to someone else now: nothing was signalled, state file kept" ;; *) return "$rc" ;; esac
}
port_busy_die() { owned port-free "$1" || die "$2 port $1 is held by a process this script did not start (holder above). It was NOT touched. Free it yourself or choose another port ($3)."; }
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
CORS_ALLOWED_ORIGINS=http://127.0.0.1:$STUDIO_PORT,http://127.0.0.1:$PLATFORM_PORT,http://127.0.0.1:$ADMIN_PORT
WEB_ORIGIN_STUDIO=http://127.0.0.1:$STUDIO_PORT
WEB_ORIGIN_ADMIN=http://127.0.0.1:$ADMIN_PORT
WEB_ORIGIN_PLATFORM=http://127.0.0.1:$PLATFORM_PORT
MAX_PROJECTS_PER_WORKSPACE=100000
DATA_PLATFORM_ENABLED=true
WORKFLOW_ENABLED=true
RATE_LIMIT_PUBLISH_MAX=500
DEPLOY_PROVIDER=static
SITES_ORIGIN=http://127.0.0.1:$SITES_PORT
SITES_DATA_API_BASE=${E2E_SITES_DATA_API_BASE:-$( [ "${E2E_SITES_PUBLIC_DATA:-false}" = true ] && echo "http://127.0.0.1:$SITES_PORT/{slug}/_data" )}
SITES_PUBLIC_DATA_ENABLED=${E2E_SITES_PUBLIC_DATA:-false}
STUDIO_ORIGIN=http://127.0.0.1:$STUDIO_PORT
RENDER_URL=http://127.0.0.1:$RENDER_PORT
RENDER_PORT=$RENDER_PORT
$( [ "${E2E_DATA_TARGET:-0}" = 1 ] && printf 'DATA_PLATFORM_POSTGRES_ALLOWED_PRIVATE=127.0.0.1:%s\nJAVA_TOOL_OPTIONS=\"-Djavax.net.ssl.trustStore=%s/truststore.jks -Djavax.net.ssl.trustStorePassword=%s\"' "$DT_PORT" "$DT_DIR" "$(cat "$DT_DIR/truststore.pass")" )
ORGANIZATION_PERSISTENCE_ENABLED=${E2E_ORG_PERSISTENCE:-false}
PUBLISH_CONFIGS_ENABLED=${E2E_PUBLISH_CONFIGS:-false}
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
  run_container sites -p "127.0.0.1:$SITES_PORT:8080" -e "API_UPSTREAM=host.docker.internal:$API_PORT" -e SITES_HOST=sites.localhost -e GATEWAY_FORCE_HTTPS=0 -e "GATEWAY_REAL_IP_FROM=${E2E_GATEWAY_REAL_IP_FROM:-127.0.0.1}" --add-host host.docker.internal:host-gateway \
    -v "$WT/infra/sites-gateway/default.conf.template:/etc/nginx/templates/default.conf.template:ro" --tmpfs /tmp --tmpfs /etc/nginx/conf.d:uid=101,gid=101,mode=0755 nginxinc/nginx-unprivileged:1.29-alpine
  for _ in $(seq 1 40); do docker exec "$NAME-pg" pg_isready -U studio -d system_web_studio >/dev/null 2>&1 && return 0; sleep 1; done; die "postgres did not become ready"
}

backend_launch() {   # starts the process and returns at once (the E2E start hook has a 60 s budget; the flows wait for the API themselves)
  need_env; mkdir -p "$RUNST"
  if is_owned_up backend; then say "backend already running (owned, state $(st backend))"; return 0; fi
  # kotlinc needs more heap than the default (the first compile died with OutOfMemoryError: GC overhead limit exceeded). A busy API port FAILS here (exit 3, holder named): nobody is stopped to make room.
  PATH="$JAVA_HOME/bin:$PATH" owned start --state "$(st backend)" --log "$LOGS/backend.log" --cwd "$WT/backend" --name backend --port "$API_PORT" -- ./gradlew bootRun --console=plain -Dorg.gradle.jvmargs=-Xmx3g -Pkotlin.daemon.jvmargs=-Xmx3g || die "backend not started (API port $API_PORT busy or the launch failed; nothing was stopped)"
  say "backend starting (first start compiles, ~1.5 min); log: $LOGS/backend.log"
}
# gradle runs the app JVM under its daemon, outside our process group: the listener is recorded as owned ONLY when its command line contains this stack's private worktree path and it started after our launch
backend_adopt() { is_owned_up backend-app && return 0; owned adopt --state "$(st backend-app)" --port "$API_PORT" --parent "$(st backend)" --contains "$WT" --name backend-app; }
backend_up() {
  backend_launch; need_env
  wait_http "http://127.0.0.1:$API_PORT/actuator/health/liveness" 300 || die "backend did not come up; see $LOGS/backend.log"
  owned refresh --state "$(st backend)" || true; backend_adopt || die "the listener on $API_PORT is not provably this stack's API: not adopted, not touched"
}
backend_down() {
  need_env
  if is_owned_up backend; then backend_adopt 2>/dev/null || true; fi                                    # late adoption (backend-launch does not wait)
  stop_owned backend-app || die "the API process of this stack did not stop"; stop_owned backend || die "the gradle launcher of this stack did not stop"
  port_wait_free "$API_PORT" "${E2E_STOP_WAIT_SECS:-30}" || { owned port-free "$API_PORT" || true; die "API port $API_PORT is still busy after stopping what this script started: the holder (above) is not ours and was NOT touched"; }
}
backend_restart() { backend_down; backend_up; }
backend_pause() { need_env; if is_owned_up backend; then backend_adopt || die "the listener on $API_PORT is not provably this stack's API"; fi; is_owned_up backend-app || die "no API started by this script (nothing paused)"; owned signal --state "$(st backend-app)" --sig STOP; }    # a HANG, not an outage (E2E-S9)
# fault injection for the release flows (E2E-P*): the artifact store or the render worker is PAUSED (not stopped), so a publish stays in a known step and the scope lease stays alive
store_pause() { need_env; docker pause "$NAME-minio" >/dev/null; }
store_resume() { need_env; docker unpause "$NAME-minio" >/dev/null 2>&1 || true; }
render_pause() { need_env; is_owned_up render || die "no render worker started by this script (nothing paused)"; owned signal --state "$(st render)" --sig STOP; }
render_resume() { need_env; is_owned_up render || return 0; owned signal --state "$(st render)" --sig CONT; }
backend_resume() { need_env; is_owned_up backend-app || return 0; owned signal --state "$(st backend-app)" --sig CONT; }
render_up() {
  need_env; mkdir -p "$RUNST"; if is_owned_up render; then return 0; fi
  ( cd "$WT" && npx tsc -p workers/render/tsconfig.json )
  RENDER_PORT="$RENDER_PORT" RENDER_TOKEN="$RENDER_TOKEN" owned start --state "$(st render)" --log "$LOGS/render.log" --cwd "$WT" --name render --port "$RENDER_PORT" -- node workers/render/dist/workers/render/server.js || die "render worker not started (port $RENDER_PORT busy or the launch failed; nothing was stopped)"
  wait_http "http://127.0.0.1:$RENDER_PORT/health" 30 || { stop_owned render || true; die "render worker did not start"; }
  owned refresh --state "$(st render)" || true
}
studio_up() {
  need_env; mkdir -p "$RUNST"
  stop_owned studio || die "the previous Studio of this stack did not stop"                              # restart: only the Studio THIS script started; a foreign listener is never stopped
  port_busy_die "$STUDIO_PORT" "Studio" "set E2E_STUDIO_PORT"
  # the proxy target is BAKED IN AT BUILD TIME (next build); setting it only on `next start` has no effect
  # NEXT_DIST_DIR keeps this stack's build apart from the checkout's normal `.next` (git-ignored: .next-check*/), so a gate build never breaks a running Studio
  export NEXT_DIST_DIR=".next-check-$NAME"
  # `next build` rewrites the tracked apps/studio/tsconfig.json (adds the dist dir to "include"): keep a copy and put it back so the checkout stays clean
  cp "$REPO/apps/studio/tsconfig.json" "$DIR/tsconfig.studio.orig"
  ( cd "$REPO" && API_PROXY_TARGET="http://127.0.0.1:$API_PORT" npm run build:studio > "$LOGS/studio-build.log" 2>&1 ) || { cp "$DIR/tsconfig.studio.orig" "$REPO/apps/studio/tsconfig.json"; die "Studio build failed; see $LOGS/studio-build.log"; }
  cp "$DIR/tsconfig.studio.orig" "$REPO/apps/studio/tsconfig.json"
  # integration/v2 nextConfig requires the proxy target at START too
  API_PROXY_TARGET="http://127.0.0.1:$API_PORT" owned start --state "$(st studio)" --log "$LOGS/studio.log" --cwd "$REPO/apps/studio" --name studio --port "$STUDIO_PORT" -- npx next start -H 127.0.0.1 -p "$STUDIO_PORT" || die "Studio not started (port $STUDIO_PORT busy or the launch failed; nothing was stopped)"
  wait_http "http://127.0.0.1:$STUDIO_PORT/api/v1/auth/config" 60 || { stop_owned studio || true; die "Studio does not reach the API (check the build's proxy target); see $LOGS/studio.log"; }
  owned refresh --state "$(st studio)" || true
}

portal_up() {   # <name> <port>: build with THIS stack's API as proxy target (baked in at build time) and start it owned; the checkout's tracked tsconfig is put back
  local app="$1" port="$2"; need_env; mkdir -p "$RUNST"
  stop_owned "$app" || die "the previous $app of this stack did not stop"
  port_busy_die "$port" "$app" "set E2E_$(echo "$app" | tr a-z A-Z)_PORT"
  export NEXT_DIST_DIR=".next-check-$NAME"
  cp "$REPO/apps/$app/tsconfig.json" "$DIR/tsconfig.$app.orig"
  ( cd "$REPO" && API_PROXY_TARGET="http://127.0.0.1:$API_PORT" npm run "build:$app" > "$LOGS/$app-build.log" 2>&1 ) || { cp "$DIR/tsconfig.$app.orig" "$REPO/apps/$app/tsconfig.json"; die "$app build failed; see $LOGS/$app-build.log"; }
  cp "$DIR/tsconfig.$app.orig" "$REPO/apps/$app/tsconfig.json"
  API_PROXY_TARGET="http://127.0.0.1:$API_PORT" owned start --state "$(st $app)" --log "$LOGS/$app.log" --cwd "$REPO/apps/$app" --name "$app" --port "$port" -- npx next start -H 127.0.0.1 -p "$port" || die "$app did not start"
  wait_http "http://127.0.0.1:$port/api/v1/auth/config" 60 || { stop_owned "$app" || true; die "$app does not reach the API; see $LOGS/$app.log"; }
  owned refresh --state "$(st $app)" || true
}
portals_up() { portal_up platform "$PLATFORM_PORT"; portal_up admin "$ADMIN_PORT"; }
portals_down() { need_env; stop_owned platform || true; stop_owned admin || true; }

e2e() {
  need_env; local flows="${1:-}"
  ( cd "$REPO" && E2E_ONLY="$flows" E2E_STUDIO_URL="http://127.0.0.1:$STUDIO_PORT" E2E_PLATFORM_URL="http://127.0.0.1:$PLATFORM_PORT" E2E_ADMIN_URL="http://127.0.0.1:$ADMIN_PORT" E2E_ADMIN_USER=local.admin E2E_ADMIN_PASSWORD="$LOCAL_ADMIN_PASSWORD" E2E_CHROME="$CHROME" \
      E2E_DURABLE_RUN_STORES=1 E2E_RESTART_BACKEND_CMD="$SELF backend-restart" E2E_STOP_BACKEND_CMD="$SELF backend-down" E2E_PAUSE_STORE_CMD="$SELF store-pause" E2E_RESUME_STORE_CMD="$SELF store-resume" E2E_PAUSE_RENDER_CMD="$SELF render-pause" E2E_RESUME_RENDER_CMD="$SELF render-resume" E2E_PAUSE_BACKEND_CMD="$SELF backend-pause" E2E_RESUME_BACKEND_CMD="$SELF backend-resume" E2E_START_BACKEND_CMD="$SELF backend-launch" \
      E2E_BACKEND_URL="http://127.0.0.1:$API_PORT" E2E_BACKEND_HEAD="$(git -C "$WT" log --oneline -1) [$BASE_REF${MERGE_REFS:+ + $MERGE_REFS}]" E2E_OUT_DIR="${E2E_OUT_DIR:-$REPO/.run/e2e-real}" \
      npm run test:e2e:real )
}

data_target_check() {   # E2E_DATA_TARGET=1: the target must be up and its dev CA must verify, otherwise the stack would silently lack the data path (Journey 05 / PD01 real rows)
  [ "${E2E_DATA_TARGET:-0}" = 1 ] || return 0
  [ -s "$DT_DIR/truststore.jks" ] && [ -s "$DT_DIR/truststore.pass" ] && [ -s "$DT_DIR/ca.crt" ] || die "E2E_DATA_TARGET=1 but $DT_DIR has no trust store: run scripts/data-target.sh up (in the main checkout) first"
  port_free "$DT_PORT" && die "E2E_DATA_TARGET=1 but nothing listens on 127.0.0.1:$DT_PORT: run scripts/data-target.sh up"
  echo | openssl s_client -connect "127.0.0.1:$DT_PORT" -starttls postgres -CAfile "$DT_DIR/ca.crt" -verify_return_error 2>&1 | grep -q "Verify return code: 0" || die "the data target at 127.0.0.1:$DT_PORT does not verify against $DT_DIR/ca.crt"
  say "data target 127.0.0.1:$DT_PORT: TLS verified against the dev CA; trust store $DT_DIR/truststore.jks goes into the API JVM"
}
pin_check() {   # exact SHA pinning (D-C0-55): the backend worktree is BASE_REF, the portals are built from THIS checkout: they must be the same commit, otherwise the stack would serve a mix
  local want have; want="$(git -C "$BACKEND_REPO" rev-parse "$BASE_REF^{commit}")" || die "E2E_BASE_REF $BASE_REF does not resolve"; have="$(git -C "$REPO" rev-parse HEAD)"
  [ "$want" = "$have" ] || [ "${E2E_ALLOW_SKEW:-0}" = 1 ] || die "backend = $BASE_REF ($want) but the portals would be built from $REPO @ $have: check out the same commit, or set E2E_ALLOW_SKEW=1 knowingly"
  PINNED_SHA="$want"
}
serving_record() {   # what this stack serves = the BUILD STAMP (written at the moment the stack is built; never a secret). scripts/rc-verify.mjs re-checks it against the live processes
  need_env; local f="$DIR/SERVING.json" app bid="" tpl=""
  for app in studio platform admin; do bid="$bid\"$app\": \"$(cat "$REPO/apps/$app/.next-check-$NAME/BUILD_ID" 2>/dev/null || echo none)\", "; done
  tpl="$(shasum -a 256 "$WT/infra/sites-gateway/default.conf.template" 2>/dev/null | cut -d' ' -f1)"; local ts="false" cafp=""; if [ "${E2E_DATA_TARGET:-0}" = 1 ]; then ts="true"; cafp="$(openssl x509 -in "$DT_DIR/ca.crt" -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2)"; fi
  printf '{\n  "stack": "%s",\n  "sha": "%s",\n  "recordedAt": "%s",\n  "repoHead": "%s",\n  "backendWorktreeHead": "%s",\n  "buildIds": {%s"_": ""},\n  "sitesGatewayTemplateSha256": "%s",\n  "dataTarget": {"enabled": %s, "endpoint": "127.0.0.1:%s", "trustStore": "%s/truststore.jks", "caSha256": "%s"},\n  "ports": {"api": %s, "studio": %s, "platform": %s, "admin": %s, "sites": %s, "render": %s, "postgres": %s},\n  "organizationPersistence": "%s",\n  "publishConfigs": "%s",\n  "sitesPublicData": "%s"\n}\n' \
    "$NAME" "${PINNED_SHA:-unknown}" "$(date -u +%FT%TZ)" "$(git -C "$REPO" rev-parse HEAD)" "$(git -C "$WT" rev-parse HEAD)" "$bid" "$tpl" "$ts" "$DT_PORT" "$DT_DIR" "$cafp" "$API_PORT" "$STUDIO_PORT" "$PLATFORM_PORT" "$ADMIN_PORT" "$SITES_PORT" "$RENDER_PORT" "$PG_PORT" "${ORGANIZATION_PERSISTENCE_ENABLED:-false}" "${PUBLISH_CONFIGS_ENABLED:-false}" "${SITES_PUBLIC_DATA_ENABLED:-false}" > "$f"
  say "build stamp: $f"
}
owned_name() { case "$1" in api) echo backend-app ;; render) echo render ;; studio) echo studio ;; platform) echo platform ;; admin) echo admin ;; *) echo none ;; esac; }   # a function: a `case` inside $( ) is a syntax error on macOS bash 3.2
status() {
  local p name port state
  for p in "api $API_PORT" "render $RENDER_PORT" "studio $STUDIO_PORT" "platform $PLATFORM_PORT" "admin $ADMIN_PORT" "pg $PG_PORT" "redis $REDIS_PORT" "minio $MINIO_PORT" "rabbit $RABBIT_PORT" "sites $SITES_PORT"; do
    name="${p%% *}"; port="${p##* }"
    if port_free "$port"; then state="-"; elif is_owned_up "$(owned_name "$name")"; then state="listening (owned)"; else state="listening"; fi
    printf '%-9s :%-6s %s\n' "$name" "$port" "$state"
  done
  curl -fsS "http://127.0.0.1:$API_PORT/actuator/health/readiness" 2>/dev/null && echo || echo "api readiness: not answering"
}
down() {
  need_env 2>/dev/null || true
  # only what this script started (state files), each validated; a foreign process on one of these ports stays untouched
  for n in platform admin studio render backend-app backend; do stop_owned "$n" || say "WARNING: $n did not stop (see above)"; done
  for a in "$@"; do
    case "$a" in
      --infra) for c in pg redis minio rabbit sites; do docker rm -f "$NAME-$c" >/dev/null 2>&1 || true; done ;;
      --worktree) git -C "$BACKEND_REPO" worktree remove --force "$WT" 2>/dev/null || true ;;
    esac
  done
}

case "${1:-}" in
  prepare) prepare ;;
  up) pin_check; data_target_check; prepare; infra_up; backend_up; render_up; studio_up; if [ "${E2E_PORTALS:-0}" = 1 ]; then portals_up; fi; serving_record; status ;;
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
  portals-up) portals_up ;;
  portals-down) portals_down ;;
  e2e) shift; e2e "${1:-}" ;;
  status) status ;;
  down) shift; down "$@" ;;
  *) sed -n '2,13p' "$SELF"; exit 2 ;;
esac
