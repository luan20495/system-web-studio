# C6 RC regression stack (isolated: own containers c6rc-*, own ports, own state dir). Source this file, then run docs/parallel/c5/e2e-stack.sh from the RC worktree.
export E2E_REPO=/Users/hoangluan/code/xweb-c6-rc E2E_BACKEND_REPO=/Users/hoangluan/code/xweb-c6-rc
export E2E_STACK_NAME=c6rc E2E_STACK_DIR=$HOME/.xweb-e2e-stack/c6rc E2E_BASE_REF=62ce9697cd56e8dc86b3260923f5addbcf555743 E2E_MERGE_REFS=
export E2E_JAVA_HOME=/opt/homebrew/opt/openjdk@21
export E2E_API_PORT=51080 E2E_PG_PORT=51432 E2E_REDIS_PORT=51379 E2E_MINIO_PORT=51900 E2E_RABBIT_PORT=51672 E2E_SITES_PORT=51088 E2E_RENDER_PORT=51095
export E2E_STUDIO_PORT=3403 E2E_PLATFORM_URL=http://127.0.0.1:3401 E2E_ADMIN_URL=http://127.0.0.1:3402
export PORTAL_PLATFORM_PORT=3401 PORTAL_ADMIN_PORT=3402 PORTAL_STUDIO_PORT=3403 API_PROXY_TARGET=http://127.0.0.1:51080
export E2E_OUT_DIR=/Users/hoangluan/code/xweb-c6/docs/parallel/c6/evidence/rc-62ce9697cd56/e2e-real
