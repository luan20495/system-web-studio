#!/bin/bash
cd /Users/hoangluan/code/xweb-c5
export NEXT_DIST_DIR=.next-gate API_PROXY_TARGET=http://127.0.0.1:9
for n in platform admin studio; do
  cp apps/$n/tsconfig.json /tmp/tsconfig.gate.$n.orig
  if npm run build:$n > /tmp/c5-gate-build-$n.log 2>&1; then echo "BUILD-OK $n"; else echo "BUILD-FAIL $n"; fi
  cp /tmp/tsconfig.gate.$n.orig apps/$n/tsconfig.json
done
cp tsconfig.json /tmp/tsconfig.gate.root.orig
if NEXT_DIST_DIR=.next-gate-root npm run build > /tmp/c5-gate-build-root.log 2>&1; then echo "BUILD-OK root"; else echo "BUILD-FAIL root"; fi
cp /tmp/tsconfig.gate.root.orig tsconfig.json
echo GATES-DONE
