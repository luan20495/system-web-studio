#!/usr/bin/env bash
# Store your OpenRouter API key (typed silently, never echoed or printed) and restart the API so it takes effect.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"; ENVF=.run/public/public.env
[ -f "$ENVF" ] || { echo "run scripts/public-up.sh once first" >&2; exit 1; }
if [ -n "${1:-}" ]; then KEY="$1"; else read -r -s -p "OpenRouter API key (sk-or-…): " KEY; echo; fi
[ ${#KEY} -ge 20 ] || { echo "that does not look like an API key" >&2; exit 1; }
python3 - "$ENVF" "$KEY" <<'PY'
import sys,re
p,k=sys.argv[1:3]; s=open(p).read()
s=re.sub(r'^OPENROUTER_API_KEY=.*$', lambda m: 'OPENROUTER_API_KEY='+k, s, flags=re.M)
open(p,'w').write(s)
PY
chmod 600 "$ENVF"
p=$(cat .run/public/api.pid 2>/dev/null || true); [ -n "$p" ] && kill "$p" 2>/dev/null || true
for _ in $(seq 1 30); do lsof -ti tcp:18081 -sTCP:LISTEN >/dev/null 2>&1 || break; sleep 1; done
"$ROOT/scripts/public-up.sh" >/dev/null
echo "Key saved and API restarted. Open the app: the composer now shows the model picker (free OpenRouter models)."
