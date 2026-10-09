#!/bin/bash
cd /Users/hoangluan/code/xweb-c5
t0=$(date +%s)
npx tsc --noEmit -p tsconfig.json > /tmp/cp-tsc-root.log 2>&1; echo "tsc-root exit=$? $(( $(date +%s)-t0 ))s"
npm run typecheck:apps > /tmp/cp-tsc-apps.log 2>&1; echo "tsc-apps exit=$? $(( $(date +%s)-t0 ))s"
npm run typecheck:packages > /tmp/cp-tsc-pkgs.log 2>&1; echo "tsc-pkgs exit=$? $(( $(date +%s)-t0 ))s"
npm run test:unit > /tmp/cp-unit.log 2>&1; echo "unit exit=$? $(( $(date +%s)-t0 ))s"; grep -E "^# (tests|pass|fail|skipped)" /tmp/cp-unit.log
npm run test:classify > /tmp/cp-classify.log 2>&1; echo "classify exit=$? $(( $(date +%s)-t0 ))s"; tail -2 /tmp/cp-classify.log
echo CP-A-DONE
