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
export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-http://localhost:${FRONTEND_PORT},http://127.0.0.1:${FRONTEND_PORT}}"
mkdir -p "$ROOT/.run"
# Real static sites (ADR 0009): render worker + sites gateway (compose service sites-gateway on 127.0.0.1:18088)
export DEPLOY_PROVIDER="${DEPLOY_PROVIDER:-static}"
export SITES_ORIGIN="${SITES_ORIGIN:-http://127.0.0.1:18088}"
export STUDIO_ORIGIN="${STUDIO_ORIGIN:-http://127.0.0.1:${FRONTEND_PORT}}"
export RENDER_PORT="${RENDER_PORT:-18095}" RENDER_URL="${RENDER_URL:-http://127.0.0.1:18095}"
[ -f "$ROOT/.run/render.token" ] || (umask 077; openssl rand -hex 24 > "$ROOT/.run/render.token")
export RENDER_TOKEN="${RENDER_TOKEN:-$(cat "$ROOT/.run/render.token")}"
