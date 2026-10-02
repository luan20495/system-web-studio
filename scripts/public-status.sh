#!/usr/bin/env bash
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"; ENVF=.run/public/public.env
[ -f "$ENVF" ] || { echo "not set up: run scripts/public-up.sh"; exit 1; }
set -a; . "$ENVF"; set +a
ok() { printf '  %-34s %s\n' "$1" "$2"; }
code() { curl -s -m8 -o /dev/null -w '%{http_code}' "$1" 2>/dev/null; }
echo "Local:"; ok "API readiness (127.0.0.1:$API_PORT)" "$(code http://127.0.0.1:$API_PORT/actuator/health/readiness)"; ok "UI (127.0.0.1:$UI_PORT)" "$(code http://127.0.0.1:$UI_PORT/)"
for f in api ui render tunnel awake; do p=$(cat .run/public/$f.pid 2>/dev/null); ok "$f process" "$( [ -n "$p" ] && kill -0 "$p" 2>/dev/null && echo "running (pid $p)" || echo "NOT running")"; done
echo "Public:"; ok "https://$PUBLIC_HOST/" "$(code https://$PUBLIC_HOST/)"; ok "…/api/v1/auth/config" "$(code https://$PUBLIC_HOST/api/v1/auth/config)"
ok "https://$PUBLIC_FILES_HOST/ (expect 400/403)" "$(code https://$PUBLIC_FILES_HOST/)"
# the UI's catch-all route answers unknown paths with its own "not found" screen; what matters is that the API's actuator is not reachable
ok "API actuator via public host (expect 'not exposed')" "$(curl -s -m10 "https://$PUBLIC_HOST/actuator/health" | grep -q '"status":"UP"' && echo EXPOSED || echo 'not exposed')"
ok "API via sites host (expect 404)" "$(code "https://${SITES_HOST:-sites.toolsmcp.uk}/api/v1/auth/config")"
ok "sites gateway health" "$(code "https://${SITES_HOST:-sites.toolsmcp.uk}/healthz")"
echo "AI key configured: $( [ -n "${OPENROUTER_API_KEY:-}" ] && echo yes || echo 'no (simulator)')"
