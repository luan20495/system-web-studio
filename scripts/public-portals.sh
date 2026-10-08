#!/usr/bin/env bash
# PUBLIC portals (Platform, Admin, Studio) - EXPLICIT DEPLOYMENT PINNING (D-C0-49). PROCESS RECOVERY IS NOT DEPLOYMENT.
#   ./scripts/public-portals.sh status                 portal, pid, port, health, running / approved / integration source, build + config fingerprint, state
#   ./scripts/public-portals.sh up | restart | down    start / restart the APPROVED release (never a build, never the working tree) / stop
#   ./scripts/public-portals.sh deploy <sha|ref>       the only way to change what is public: candidate build -> proof on temporary ports -> approve -> replace -> automatic rollback on failure
#   ./scripts/public-portals.sh rollback [release-id]  explicit return to the previous known-good release (artifacts are retained)
#   ./scripts/public-portals.sh init --from-running    pin the release that runs right now (evidence based; restarts nothing)
#   ./scripts/public-portals.sh releases | prune | verify
# What runs is recorded in .run/public/approved.json -> .run/public/releases/<sha12>-<cfg8>/ (a self-contained snapshot: source of that SHA, built portals, own node_modules, pinned non-secret env).
# The working-tree HEAD is NOT deployment authority: a crash recovered by the watchdog restarts the approved release, even when integration/v2 is newer. Local portals (scripts/portals.sh) stay source-aware.
# Replacement is stop-then-start per portal: a short outage, NOT zero-downtime. See docs/parallel/c0/PUBLIC_DEPLOYMENT_PINNING.md.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec node "$ROOT/scripts/public-release.mjs" "${@:-status}"
