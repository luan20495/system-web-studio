#!/usr/bin/env bash
# One-time (idempotent) setup of the local Forgejo for generated code projects (ADR 0011):
#   server secrets -> .run/forgejo-server.env, admin + bot accounts, organisation, bot token -> .run/forgejo.env (both mode 600, git-ignored).
# Usage: scripts/forgejo-setup.sh [compose-file] [env-prefix]   (defaults: compose.yml / .run)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
COMPOSE="${1:-compose.yml}"; RUN="${2:-.run}"; PORT="${FORGEJO_PORT:-13000}"
mkdir -p "$RUN"
gen() { openssl rand -hex 32; }
SRV="$RUN/forgejo-server.env"; CLI="$RUN/forgejo.env"
if [ ! -f "$SRV" ]; then
  ( umask 077; { echo "FORGEJO__security__SECRET_KEY=$(gen)"; echo "FORGEJO__security__INTERNAL_TOKEN=$(gen)$(gen)"; echo "FORGEJO__oauth2__JWT_SECRET=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"; } > "$SRV" )
fi
dc() { docker compose -f "$COMPOSE" "$@"; }
dc up -d --wait forgejo
fx() { dc exec -T -u 1000 forgejo forgejo "$@"; }      # app.ini is /var/lib/gitea/custom/conf/app.ini (GITEA_APP_INI)
[ -f "$CLI" ] || ( umask 077; { echo "FORGEJO_URL=http://127.0.0.1:$PORT"; echo "FORGEJO_ORG=factory"; echo "FORGEJO_BOT=factory-bot"; echo "FORGEJO_ADMIN_PASSWORD=$(gen | cut -c1-24)"; echo "FORGEJO_BOT_PASSWORD=$(gen | cut -c1-24)"; } > "$CLI" )
set -a; . "$CLI"; set +a
fx admin user list 2>/dev/null | awk '{print $2}' | grep -qx factory-admin || fx admin user create --admin --username factory-admin --password "$FORGEJO_ADMIN_PASSWORD" --email factory-admin@factory.local --must-change-password=false >/dev/null
fx admin user list 2>/dev/null | awk '{print $2}' | grep -qx "$FORGEJO_BOT" || fx admin user create --username "$FORGEJO_BOT" --password "$FORGEJO_BOT_PASSWORD" --email factory-bot@factory.local --must-change-password=false >/dev/null
if ! grep -q '^FORGEJO_TOKEN=' "$CLI"; then
  TOKEN="$(fx admin user generate-access-token --username "$FORGEJO_BOT" --token-name "studio-api-$(date +%s)" --scopes "write:repository,write:organization,read:user" --raw)"
  ( umask 077; echo "FORGEJO_TOKEN=$TOKEN" >> "$CLI" ); FORGEJO_TOKEN="$TOKEN"
fi
# admin token: only for creating per-user read-only clone accounts (IDE access) — kept server-side
if ! grep -q '^FORGEJO_ADMIN_TOKEN=' "$CLI"; then
  AT="$(fx admin user generate-access-token --username factory-admin --token-name "studio-admin-$(date +%s)" --scopes "write:admin,write:repository,read:user" --raw)"
  ( umask 077; echo "FORGEJO_ADMIN_TOKEN=$AT" >> "$CLI" )
fi
set -a; . "$CLI"; set +a
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: token $FORGEJO_TOKEN" "$FORGEJO_URL/api/v1/orgs/$FORGEJO_ORG")
if [ "$code" != 200 ]; then
  curl -fsS -X POST -H "Authorization: token $FORGEJO_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$FORGEJO_ORG\",\"visibility\":\"private\",\"repo_admin_change_team_access\":false}" "$FORGEJO_URL/api/v1/orgs" >/dev/null
fi
# Bot commit signing (ADR 0011): a dedicated GPG key for factory-bot inside Forgejo's data volume (git HOME); never a personal key.
if ! grep -q '^FORGEJO__repository.signing__SIGNING_KEY=' "$SRV"; then
  dc exec -T -u 1000 forgejo sh -c 'export GNUPGHOME=/var/lib/gitea/home/.gnupg; mkdir -p $GNUPGHOME && chmod 700 $GNUPGHOME
    gpg --batch --list-secret-keys factory-bot@factory.local >/dev/null 2>&1 || gpg --batch --passphrase "" --quick-gen-key "factory-bot <factory-bot@factory.local>" ed25519 sign never' >/dev/null 2>&1
  FPR="$(dc exec -T -u 1000 forgejo sh -c 'GNUPGHOME=/var/lib/gitea/home/.gnupg gpg --batch --with-colons --list-secret-keys factory-bot@factory.local' | awk -F: '/^fpr/{print $10; exit}')"
  [ -n "$FPR" ] || { echo "could not create the signing key" >&2; exit 1; }
  ( umask 077; { echo "FORGEJO__repository.signing__SIGNING_KEY=$FPR"; echo "FORGEJO__repository.signing__SIGNING_NAME=factory-bot";
    echo "FORGEJO__repository.signing__SIGNING_EMAIL=factory-bot@factory.local"; echo "FORGEJO__repository.signing__INITIAL_COMMIT=always";
    echo "FORGEJO__repository.signing__CRUD_ACTIONS=always"; echo "FORGEJO__repository.signing__MERGES=always"; } >> "$SRV" )
  dc up -d --force-recreate --wait forgejo
fi
echo "Forgejo ready at $FORGEJO_URL (org $FORGEJO_ORG, bot $FORGEJO_BOT); client settings in $CLI"
