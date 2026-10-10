#!/usr/bin/env bash
# C6 FINAL RC QA — environment for every final-* script. SOURCE it (do not run it):   . docs/parallel/c6/harness/final-env.sh
# Reads the c0rc stack's stack.env INSIDE this shell (mode 600 file outside the repo) and exports only what the scripts need. Never prints a secret.
FINAL_STACK="${FINAL_STACK:-c0rc}"; FINAL_STACK_DIR="$HOME/.xweb-e2e-stack/$FINAL_STACK"
[ -f "$FINAL_STACK_DIR/stack.env" ] || { echo "final-env: no $FINAL_STACK_DIR/stack.env" >&2; return 1 2>/dev/null || exit 1; }
set -a; . "$FINAL_STACK_DIR/stack.env"; set +a
export FINAL_API="http://127.0.0.1:${SERVER_PORT}" FINAL_STUDIO="$WEB_ORIGIN_STUDIO" FINAL_PLATFORM="$WEB_ORIGIN_PLATFORM" FINAL_ADMIN="$WEB_ORIGIN_ADMIN" FINAL_SITES="${SITES_ORIGIN}"
export SA_USER="local.admin" SA_PASSWORD="$LOCAL_ADMIN_PASSWORD" API="$FINAL_API"
export FINAL_SHA_PRODUCT="bc5c47f292d00846c106669b09679a6fc36daef6" FINAL_SHA_INTEGRATION="edf32dfe187a25ee159339c22be2ff4c1c093df4" FINAL_SHA_CHECKOUT="fad4a7b4356fecd016e707423d426d1eb4b45c4d"
export FINAL_TESTS="/Users/hoangluan/code/xweb-c6-final" FINAL_OUT="/Users/hoangluan/code/xweb-c6/docs/parallel/c6/evidence/final-rc-bc5c47f292d0"
export UITOOLS="${UITOOLS:-/private/tmp/claude-501/-Users-hoangluan-code-HBL/0a63d710-a6f1-4dad-8918-b63006286da6/scratchpad/uitools}"
# data target of the V1 stack (scripts/data-target.sh): role password FILES (paths only; read them inside your script, never print)
export RO_PW_FILE="/Users/hoangluan/code/HBL/.run/data-target/ro.pw" RW_PW_FILE="/Users/hoangluan/code/HBL/.run/data-target/rw.pw" DATA_TARGET_PORT=15440
# never exported to child processes beyond this shell's scripts: the infrastructure secrets
unset DATABASE_PASSWORD MINIO_ROOT_PASSWORD RABBITMQ_PASSWORD SECRETS_MASTER_KEY RENDER_TOKEN LOCAL_ADMIN_PASSWORD
mkdir -p "$FINAL_OUT"
