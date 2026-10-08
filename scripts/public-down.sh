#!/usr/bin/env bash
# Stop the public app: tunnel, UI, API, keep-awake. Add --infra to also stop the data containers (data volumes are kept).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
# stop the watchdog and backup daemon first: an intentional stop must not be "repaired" by the watchdog
for f in .run/watchdog-public.pid .run/backup-daemon-public.pid; do [ -f "$f" ] && { kill "$(cat "$f")" 2>/dev/null || true; rm -f "$f"; }; done
"$ROOT/scripts/public-portals.sh" down 2>/dev/null || true
"$ROOT/scripts/public-api.sh" down 2>/dev/null || true   # D-C0-50: the API is stopped through its ownership record (a foreign listener is left alone)
for f in tunnel ui render awake; do p=$(cat .run/public/$f.pid 2>/dev/null) && kill "$p" 2>/dev/null; rm -f .run/public/$f.pid; done
if [ "${1:-}" = "--infra" ]; then docker compose -f compose.public.yml --env-file .run/public/public.env stop; fi
echo "public app stopped"
