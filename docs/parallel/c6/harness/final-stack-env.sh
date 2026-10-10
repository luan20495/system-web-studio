#!/usr/bin/env bash
# C6 FINAL RC QA — an OWN isolated stack `c6fin` (same product SHA as c0rc: backend worktree = checkout fad4a7b, product files identical to bc5c47f), used for what c0rc cannot give:
# the data-target trust store (Journeys 05/06 legs) and controlled restarts (Journey 08). SOURCE it, then run docs/parallel/c5/e2e-stack.sh from /Users/hoangluan/code/xweb-c6-final.
export E2E_REPO=/Users/hoangluan/code/xweb-c6-final E2E_BACKEND_REPO=/Users/hoangluan/code/xweb-c6-final E2E_STACK_NAME=c6fin E2E_BASE_REF=fad4a7b4356fecd016e707423d426d1eb4b45c4d E2E_MERGE_REFS=
export E2E_JAVA_HOME=/opt/homebrew/opt/openjdk@21 E2E_API_PORT=52300 E2E_PG_PORT=52301 E2E_REDIS_PORT=52302 E2E_MINIO_PORT=52303 E2E_RABBIT_PORT=52304 E2E_SITES_PORT=52305 E2E_RENDER_PORT=52306 E2E_STUDIO_PORT=52307 E2E_PLATFORM_PORT=52308 E2E_ADMIN_PORT=52309
export E2E_ORG_PERSISTENCE=true E2E_PUBLISH_CONFIGS=true E2E_SITES_PUBLIC_DATA=true
# the data target's dev CA (same as scripts/_env.sh PORTALS=1); the password is read here and never printed
T=/Users/hoangluan/code/HBL/.run/data-target; export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$T/truststore.jks -Djavax.net.ssl.trustStorePassword=$(cat $T/truststore.pass)"
