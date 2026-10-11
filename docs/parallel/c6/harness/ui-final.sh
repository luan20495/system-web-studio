#!/usr/bin/env bash
# C6 UI/UX regression — FINAL sign-off driver. Refuses to run without the three inputs from C0/C5:
#   C5_FINAL_HEAD=<sha>  INTEGRATION_SHA=<sha>  PUBLIC_URLS="https://platform… https://admin… https://studio…"
# It never builds, merges or fixes anything: the stack under test must already be built from INTEGRATION_SHA (RC worktree HEAD is checked), seeded with ui-seed.mjs.
set -euo pipefail
: "${C5_FINAL_HEAD:?C5_FINAL_HEAD not provided - final sign-off NOT run}" "${INTEGRATION_SHA:?INTEGRATION_SHA not provided - final sign-off NOT run}" "${PUBLIC_URLS:?PUBLIC_URLS not provided - final sign-off NOT run}"
H=/Users/hoangluan/code/xweb-c6/docs/parallel/c6/harness; RC=${RC_WORKTREE:-/Users/hoangluan/code/xweb-c6-rc}; EV=/Users/hoangluan/code/xweb-c6/docs/parallel/c6/evidence/ui-ux-regression
. "$H/rc-env.sh"; : "${TOOLS:?TOOLS dir with axe-core + pngjs + pixelmatch}"
HEAD_NOW=$(git -C "$RC" rev-parse HEAD); case "$HEAD_NOW" in "$INTEGRATION_SHA"*) ;; *) echo "stack worktree is at $HEAD_NOW, not $INTEGRATION_SHA - refusing to test the wrong SHA" >&2; exit 3;; esac
git -C "$RC" merge-base --is-ancestor "$C5_FINAL_HEAD" "$INTEGRATION_SHA" || { echo "C5_FINAL_HEAD $C5_FINAL_HEAD is NOT contained in INTEGRATION_SHA $INTEGRATION_SHA" >&2; exit 3; }
read -r PU AU SU <<<"$PUBLIC_URLS"
echo "== local stack (SHA $HEAD_NOW)"; MODE=final FIXTURE=$E2E_STACK_DIR/ui-fixture.json node "$H/ui-ux.mjs"
echo "== public hosts (login/home/list routes, no seeded data)"; PF=$(mktemp); (umask 077; node "$H/ui-public-fixture.mjs" > "$PF")
MODE=final TAG=public PLATFORM_URL=$PU ADMIN_URL=$AU STUDIO_URL=$SU FIXTURE=$PF ROLES=super,workspaceAdmin ONLY=/ VPS=1440,1024,768,390,360 node "$H/ui-ux.mjs" || true; rm -f "$PF"
echo "== visual regression vs the pilot baseline"; node "$H/ui-compare.mjs" "$EV/_pilot-${INTEGRATION_SHA:0:12}-c5-"* "$EV/$C5_FINAL_HEAD" 0.5 || true
