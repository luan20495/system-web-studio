#!/usr/bin/env bash
# Shared by the other scripts: load .env (gitignored) and fill the local-only defaults from .env.example.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if [ ! -f .env ]; then
  echo "No .env found. Create it with:  cp .env.example .env  and set LOCAL_ADMIN_PASSWORD (14+ chars)." >&2
  exit 1
fi
set -a; . ./.env; set +a
: "${LOCAL_ADMIN_PASSWORD:?Set LOCAL_ADMIN_PASSWORD in .env}"
export DATABASE_URL="${DATABASE_URL:-jdbc:postgresql://127.0.0.1:15432/system_web_studio}"
export DATABASE_USER="${DATABASE_USER:-studio}"
export DATABASE_PASSWORD="${DATABASE_PASSWORD:-studio-local-only}"
export REDIS_HOST="${REDIS_HOST:-127.0.0.1}" REDIS_PORT="${REDIS_PORT:-16379}"
export MINIO_ROOT_USER="${MINIO_ROOT_USER:-studio-minio}" MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-studio-local-only}"
export RABBITMQ_PASSWORD="${RABBITMQ_PASSWORD:-studio-local-only}"
# Local runs are plain http; production must keep the default (Secure cookies).
export COOKIE_SECURE="${COOKIE_SECURE:-false}"
export MINIO_ENDPOINT="${MINIO_ENDPOINT:-http://127.0.0.1:19000}"   # also read by the UI server to allow presigned URLs in its CSP
# dev workspace holds thousands of load-test projects; the production default (1000) would block creating new ones locally
export MAX_PROJECTS_PER_WORKSPACE="${MAX_PROJECTS_PER_WORKSPACE:-100000}"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
# The build needs JDK 17+ (21 recommended). Prefer Homebrew's 21 when present, else keep the caller's JAVA_HOME.
[ -d /opt/homebrew/opt/openjdk@21 ] && export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
FRONTEND_PORT="${FRONTEND_PORT:-3100}"
# --- V1 LOCAL (docs/parallel/V1_LOCAL_TARGET.md, D-C0-29/34): PORTALS=1 runs the three portals instead of the legacy :3100 app and the whole V1 configuration.
# Ports are configuration; defaults are the documented ones. Nothing here is read by production code that does not also read it from the environment.
export PORTAL_HOST="${PORTAL_HOST:-127.0.0.1}"
export PORTAL_PLATFORM_PORT="${PORTAL_PLATFORM_PORT:-3001}" PORTAL_ADMIN_PORT="${PORTAL_ADMIN_PORT:-3002}" PORTAL_STUDIO_PORT="${PORTAL_STUDIO_PORT:-3003}"
export DATA_TARGET_PORT="${DATA_TARGET_PORT:-15440}"
PORTAL_ORIGINS="http://${PORTAL_HOST}:${PORTAL_PLATFORM_PORT},http://${PORTAL_HOST}:${PORTAL_ADMIN_PORT},http://${PORTAL_HOST}:${PORTAL_STUDIO_PORT}"
if [ "${PORTALS:-0}" = 1 ]; then
  # the features of the V1 flow (all default OFF in the application; a decision, recorded here and in V1_LOCAL_TARGET.md)
  export DATA_PLATFORM_ENABLED=true WORKFLOW_ENABLED=true PUBLISH_CONFIGS_ENABLED=true
  export WORKFLOW_QUEUE="${WORKFLOW_QUEUE:-amqp}"                 # RabbitMQ, as in production (H-5); memory is refused there
  export HBL_ENV=local API_PROXY_TARGET="${API_PROXY_TARGET:-http://127.0.0.1:8080}"
  export WEB_ORIGIN_PLATFORM="http://${PORTAL_HOST}:${PORTAL_PLATFORM_PORT}" WEB_ORIGIN_ADMIN="http://${PORTAL_HOST}:${PORTAL_ADMIN_PORT}" WEB_ORIGIN_STUDIO="http://${PORTAL_HOST}:${PORTAL_STUDIO_PORT}"
  export STUDIO_ORIGIN="${STUDIO_ORIGIN:-$WEB_ORIGIN_STUDIO}"
  # the Public Runtime (D-C0-36 / 37): same-origin through the sites gateway; apiBase is ONE value for every site ({slug} is replaced per site)
  export SITES_PUBLIC_DATA_ENABLED=true
  export SITES_DATA_API_BASE="${SITES_DATA_API_BASE:-${SITES_ORIGIN:-http://127.0.0.1:18088}/{slug}/_data}"
  # the API sees the gateway container's address as its TCP peer (Docker Desktop: a private range); the gateway passes ONE validated client address (nginx real_ip)
  export TRUST_PROXY="${TRUST_PROXY:-true}" TRUSTED_PROXY_CIDRS="${TRUSTED_PROXY_CIDRS:-127.0.0.1/32,172.16.0.0/12,192.168.0.0/16,10.0.0.0/8}"
  export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-${PORTAL_ORIGINS},http://localhost:${FRONTEND_PORT},http://127.0.0.1:${FRONTEND_PORT}}"
  # the local data target (scripts/data-target.sh): ONE exact endpoint the postgres connector may reach, TLS verified against its dev CA
  export DATA_PLATFORM_POSTGRES_ALLOWED_PRIVATE="${DATA_PLATFORM_POSTGRES_ALLOWED_PRIVATE:-127.0.0.1:${DATA_TARGET_PORT}}"
  if [ -f "$ROOT/.run/data-target/truststore.jks" ] && [ -f "$ROOT/.run/data-target/truststore.pass" ]; then
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=$ROOT/.run/data-target/truststore.jks -Djavax.net.ssl.trustStorePassword=$(cat "$ROOT/.run/data-target/truststore.pass")"
  fi
fi
export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-http://localhost:${FRONTEND_PORT},http://127.0.0.1:${FRONTEND_PORT}}"
mkdir -p "$ROOT/.run"
# Real static sites (ADR 0009): render worker + sites gateway (compose service sites-gateway on 127.0.0.1:18088)
export DEPLOY_PROVIDER="${DEPLOY_PROVIDER:-static}"
export SITES_ORIGIN="${SITES_ORIGIN:-http://127.0.0.1:18088}"
export STUDIO_ORIGIN="${STUDIO_ORIGIN:-http://127.0.0.1:${FRONTEND_PORT}}"
export RENDER_PORT="${RENDER_PORT:-18095}" RENDER_URL="${RENDER_URL:-http://127.0.0.1:18095}"
[ -f "$ROOT/.run/render.token" ] || (umask 077; openssl rand -hex 24 > "$ROOT/.run/render.token")
export RENDER_TOKEN="${RENDER_TOKEN:-$(cat "$ROOT/.run/render.token")}"
# Code projects (ADR 0010/0011): local Forgejo (scripts/forgejo-setup.sh writes .run/forgejo.env) and the build runner token
if [ -f "$ROOT/.run/forgejo.env" ]; then set -a; . "$ROOT/.run/forgejo.env"; set +a; fi
[ -f "$ROOT/.run/runner.token" ] || (umask 077; openssl rand -hex 24 > "$ROOT/.run/runner.token")
export BUILD_RUNNER_TOKEN="${BUILD_RUNNER_TOKEN:-$(cat "$ROOT/.run/runner.token")}"
export BUILD_API_BASE="${BUILD_API_BASE:-http://127.0.0.1:8080}"
# Server apps (stage J): apps DB admin, gateway token, secrets master key (all generated once, git-ignored, mode 600)
if [ ! -f "$ROOT/.run/appdb.env" ]; then (umask 077; { echo "APPDB_ADMIN_PASSWORD=$(openssl rand -hex 24)"; echo "APPS_GATEWAY_TOKEN=$(openssl rand -hex 24)";
  echo "SECRETS_MASTER_KEY=$(openssl rand -base64 32)"; } > "$ROOT/.run/appdb.env"); fi
set -a; . "$ROOT/.run/appdb.env"; set +a
export APPDB_URL="${APPDB_URL:-jdbc:postgresql://127.0.0.1:15434/appdb}" APPS_GATEWAY_URL="${APPS_GATEWAY_URL:-http://127.0.0.1:18090}"
