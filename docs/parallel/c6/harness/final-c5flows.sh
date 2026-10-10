#!/usr/bin/env bash
# C6 FINAL RC QA — run C5's REAL-backend flows (tests/e2e-real, from the exact checkout fad4a7b: product == bc5c47f) against the c0rc stack.
# Evidence class REAL_BACKEND_E2E (real Chrome -> real portals -> real API -> PostgreSQL). Secrets are read inside this shell, never printed.
#   bash final-c5flows.sh "E2E-PL01,E2E-AD01"   (default = the Part 11 + ORG01 + PD02 selection)
cd "$(dirname "${BASH_SOURCE[0]}")" && . ./final-env.sh || exit 1
FLOWS="${1:-E2E-PL01,E2E-AD01,E2E-AD02,E2E-ADMIN01,E2E-SUPER01,E2E-USER01,E2E-04,E2E-05,E2E-P09,E2E-ORG01,E2E-PD02}"; OUTD="$FINAL_OUT/c5-flows-${2:-main}"; mkdir -p "$OUTD"
cd "$FINAL_TESTS" && E2E_ONLY="$FLOWS" E2E_STUDIO_URL="$FINAL_STUDIO" E2E_PLATFORM_URL="$FINAL_PLATFORM" E2E_ADMIN_URL="$FINAL_ADMIN" E2E_ADMIN_USER=local.admin E2E_ADMIN_PASSWORD="$SA_PASSWORD" \
  E2E_CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" E2E_BACKEND_URL="$FINAL_API" E2E_OUT_DIR="$OUTD" E2E_PUBLIC_BASE="$FINAL_SITES" \
  node tests/e2e-real/run.mjs > "$OUTD/run.log" 2>&1; rc=$?; echo "exit=$rc" >> "$OUTD/run.log"; tail -40 "$OUTD/run.log" | cut -c1-220; exit $rc
