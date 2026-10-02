#!/usr/bin/env bash
# Start a local Keycloak with a generated realm (random client secret and test-user password) and write the
# matching backend settings to .run/sso.env, which run-local.sh picks up. Nothing generated is committed.
. "$(dirname "$0")/_env.sh"
G=infra/keycloak/.generated; mkdir -p "$G"
rnd() { openssl rand -base64 24 | tr -d '/+=' | cut -c1-24; }
CLIENT_SECRET="$(rnd)"; USER_PASSWORD="$(rnd)"; KEYCLOAK_ADMIN_PASSWORD="$(rnd)"
python3 - "$CLIENT_SECRET" "$USER_PASSWORD" <<'PY'
import sys
t=open("infra/keycloak/realm.template.json").read().replace("__CLIENT_SECRET__",sys.argv[1]).replace("__USER_PASSWORD__",sys.argv[2])
open("infra/keycloak/.generated/studio-realm.json","w").write(t)
PY
chmod 600 "$G/studio-realm.json"
PORT="${KEYCLOAK_PORT:-18080}"
umask 077
cat > .run/sso.env <<ENV
OIDC_ENABLED=true
OIDC_ISSUER_URI=http://127.0.0.1:$PORT/realms/studio
OIDC_CLIENT_ID=studio-web
OIDC_CLIENT_SECRET=$CLIENT_SECRET
OIDC_REDIRECT_URI=http://127.0.0.1:${FRONTEND_PORT}/login/oauth2/code/oidc
OIDC_SUCCESS_URL=http://127.0.0.1:${FRONTEND_PORT}/
SSO_TEST_USER=sso.user
SSO_TEST_USER_UNVERIFIED=sso.unverified
SSO_TEST_PASSWORD=$USER_PASSWORD
ENV
KEYCLOAK_ADMIN_PASSWORD="$KEYCLOAK_ADMIN_PASSWORD" docker compose --profile sso up -d --force-recreate keycloak
echo "Waiting for Keycloak…"
for _ in $(seq 1 90); do curl -fsS "http://127.0.0.1:$PORT/realms/studio/.well-known/openid-configuration" >/dev/null 2>&1 && break; sleep 2; done
curl -fsS "http://127.0.0.1:$PORT/realms/studio/.well-known/openid-configuration" >/dev/null || { echo "Keycloak did not come up" >&2; exit 1; }
echo "Keycloak ready. Restart the API so it loads .run/sso.env:  ./scripts/stop-local.sh && ./scripts/run-local.sh"
echo "Open the UI at http://127.0.0.1:${FRONTEND_PORT} (use 127.0.0.1, it must match the redirect URI)."
