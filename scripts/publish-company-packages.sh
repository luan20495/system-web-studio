#!/usr/bin/env bash
# Builds and publishes @company/ui and @company/app-sdk to the private package mirror (Verdaccio, 127.0.0.1:${VERDACCIO_PORT:-14873}).
# Creates the operator account in the mirror's htpasswd once (password in .run/verdaccio-publish.env, mode 600). Versions are immutable:
# bump "version" in packages/*/package.json to publish a change.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
PORT="${VERDACCIO_PORT:-14873}"; REG="http://127.0.0.1:$PORT/"; ENVF=.run/verdaccio-publish.env; CONTAINER="${MIRROR_CONTAINER:-hbl-verdaccio-1}"
mkdir -p .run
[ -f "$ENVF" ] || ( umask 077; echo "VERDACCIO_USER=factory-publisher"; echo "VERDACCIO_PASSWORD=$(openssl rand -hex 16)" ) > "$ENVF"
set -a; . "$ENVF"; set +a
if ! docker exec "$CONTAINER" sh -c "grep -q '^$VERDACCIO_USER:' /verdaccio/storage/htpasswd 2>/dev/null"; then
  HASH="$(htpasswd -nbB "$VERDACCIO_USER" "$VERDACCIO_PASSWORD" | tr -d '\n')"
  docker exec "$CONTAINER" sh -c "echo '$HASH' >> /verdaccio/storage/htpasswd"
fi
AUTH=$(printf '%s:%s' "$VERDACCIO_USER" "$VERDACCIO_PASSWORD" | base64)
NPMRC="$(mktemp)"; trap 'rm -f "$NPMRC"' EXIT
printf '//127.0.0.1:%s/:_auth=%s\n' "$PORT" "$AUTH" > "$NPMRC"
for p in company-ui app-sdk; do
  ( cd "packages/$p"; rm -rf dist; "$ROOT/node_modules/.bin/tsc" -p .; [ -f src/styles.css ] && cp src/styles.css dist/ || true
    NAME=$(node -p "require('./package.json').name"); VER=$(node -p "require('./package.json').version")
    if npm view "$NAME@$VER" version --registry "$REG" --userconfig "$NPMRC" >/dev/null 2>&1; then echo "$NAME@$VER already published"
    else npm publish --registry "$REG" --userconfig "$NPMRC" --ignore-scripts >/dev/null && echo "published $NAME@$VER"; fi )
done
