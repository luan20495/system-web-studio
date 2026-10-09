#!/bin/bash
cd /Users/hoangluan/code/xweb-c5
t0=$(date +%s)
bash /tmp/c5-cp-a.sh
echo "--- full harness"
bash /tmp/run-specs.sh
echo "harness elapsed $(( $(date +%s)-t0 ))s"
echo "--- builds"
bash /tmp/c5-gates-build.sh
echo "CP2-DONE total $(( $(date +%s)-t0 ))s"
