#!/usr/bin/env bash
# Public API lifecycle (D-C0-50): API RECOVERY != API DEPLOYMENT. A thin wrapper of scripts/public-api.mjs, see docs/parallel/c0/PUBLIC_API_PINNING.md.
#   public-api.sh status | up | restart | down                 start / restart the APPROVED jar only: never Gradle, never the working tree
#   public-api.sh deploy-api <sha> | rollback-api [id]          the explicit way to change what is public
#   public-api.sh init-api --from-running [--source <sha>]      pin the API that runs now (evidence based)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec node "$ROOT/scripts/public-api.mjs" "${@:-status}"
