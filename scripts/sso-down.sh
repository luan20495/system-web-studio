#!/usr/bin/env bash
. "$(dirname "$0")/_env.sh"
KEYCLOAK_ADMIN_PASSWORD=x docker compose --profile sso rm -sf keycloak
rm -rf infra/keycloak/.generated .run/sso.env
echo "Keycloak removed; restart the API to return to password-only login."
