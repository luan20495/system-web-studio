#!/usr/bin/env bash
# Everything that can be verified without a running stack: types, both UI builds, backend tests (Testcontainers; needs Docker).
. "$(dirname "$0")/_env.sh" 2>/dev/null || { ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"; }
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"; [ -d /opt/homebrew/opt/openjdk@21 ] && export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"
set -euo pipefail
echo "== typecheck";                npx tsc --noEmit
echo "== UI build (mock, static export for GitHub Pages)"
NEXT_PUBLIC_API_MODE=mock NEXT_DIST_DIR=.next-check STUDIO_BASE_PATH=/system-web-studio npx next build > /dev/null; test -f .next-check/index.html; grep -q "/system-web-studio/_next" .next-check/index.html
echo "== UI build (http mode)";     NEXT_PUBLIC_API_MODE=http NEXT_DIST_DIR=.next-check-http npx next build > /dev/null
rm -rf .next-check .next-check-http
echo "== backend tests";            (cd backend && ./gradlew test --console=plain)
echo "== npm audit (runtime deps)"; npm audit --omit=dev
echo "ALL CHECKS PASSED"
