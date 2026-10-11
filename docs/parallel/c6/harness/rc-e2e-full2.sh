#!/usr/bin/env bash
# C6 RC wide regression — second full run of C5's real-backend suite under the V1 configuration (amqp queue, TLS data target, public data runtime).
# Secrets are read from files outside the repo into the environment of THIS process only (never echoed).
set -euo pipefail
. /Users/hoangluan/code/xweb-c6/docs/parallel/c6/harness/rc-env.sh
EVD=/Users/hoangluan/code/xweb-c6/docs/parallel/c6/evidence/rc-62ce9697cd56; T=/Users/hoangluan/code/HBL/.run/data-target
FLOWS="${1:-}"; SUFFIX="${2:-run2}"
export E2E_OUT_DIR=$EVD/e2e-real-$SUFFIX; mkdir -p "$E2E_OUT_DIR"
export E2E_DS_TYPE=postgres E2E_DS_CONFIG_JSON='{"host":"127.0.0.1","port":"15440","database":"shop","schemas":"shop"}'
export E2E_DS_CREDENTIAL_JSON="{\"username\":\"shop_ro\",\"password\":\"$(cat $T/ro.pw)\"}"
export E2E_PD_SQL='SELECT name FROM shop.customers ORDER BY id LIMIT 1' E2E_PD_EXPECT_TEXT='ACME Co'
export E2E_RABBITMQ_WIRED=1 E2E_STOP_RABBIT_CMD='docker stop c6rc-rabbit' E2E_START_RABBIT_CMD='docker start c6rc-rabbit'
set -a; . "$E2E_STACK_DIR/data-facts.env"; set +a
docker exec c6rc-redis redis-cli --scan --pattern 'rl:*' | xargs -r docker exec -i c6rc-redis redis-cli del >/dev/null
cd /Users/hoangluan/code/xweb-c6-rc && bash docs/parallel/c5/e2e-stack.sh e2e "$FLOWS"
