#!/usr/bin/env bash
# C6 SINGLE ENTRY POINT for the final QA gate — a thin composition of the runners that already exist. It adds NO test logic and, in this
# version, never executes an expensive step: plan / dry-run / status only. Never edits production code.
#   bash docs/parallel/c6/harness/final-gate.sh plan      ordered phases, the exact command of each, where its evidence goes (prints only)
#   bash docs/parallel/c6/harness/final-gate.sh dry-run   plan + checks that are cheap and local: every runner exists and parses, no unsafe process
#                                                         kill in any C6 script, owned-process lifecycle test, QA master TSV validates
#   bash docs/parallel/c6/harness/final-gate.sh status    what the QA master says now: counts per status/batch, stale rows, HEAD vs tested SHAs
#   bash docs/parallel/c6/harness/final-gate.sh run       NOT IMPLEMENTED on purpose: prints the plan and exits 64 (the expensive gate is started
#                                                         by hand from the plan, one phase at a time, after C0 approves the target SHA)
# Exit: 0 ok · 1 a dry-run check failed · 64 usage / run refused.
H="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; C6="$(cd "$H/.." && pwd)"; ROOT="$(cd "$C6/../../.." && pwd)"; cd "$ROOT" || exit 1
SHA="${C6_TARGET_SHA:-$(git rev-parse --short=12 HEAD)}"
# phase | command (as run by hand) | evidence | cost
PLAN='1 SELFTEST|bash docs/parallel/c6/harness/selftest.sh|(stdout)|seconds
2 PROCESS_SAFETY|bash docs/parallel/c6/harness/owned-process-test.sh|(stdout)|seconds
3 GATE_BACKEND_FRONTEND_STATIC|bash docs/parallel/c6/harness/mac-gate.sh   (JDK 21 + Docker)|docs/parallel/c6/evidence/mac/RESULTS.txt|minutes
4 FULL_STACK_E2E_BROWSER_RECOVERY|bash docs/parallel/c6/harness/mac-full.sh   (JDK 21 + Docker; --stop-stack at the end)|docs/parallel/c6/evidence/mac/RESULTS.txt|long
5 CONTRACT_CHECKS_DB_PROBES|python3 docs/parallel/c6/harness/contract-checks.py ; python3 docs/parallel/c6/harness/db-probes.py ; bash docs/parallel/c6/harness/run-overlay-tests.sh <sha> <evidence-dir>|docs/parallel/c6/evidence/mac-<sha>-contract-checks|minutes
6 RC_WIDE_REGRESSION|source docs/parallel/c6/harness/rc-env.sh ; node rc-sec.mjs · rc-data.mjs · rc-rabbit.mjs · rc-gateway.mjs · rc-portals.mjs · rc-public-net.mjs ; bash rc-e2e-full2.sh (isolated c6rc stack)|docs/parallel/c6/evidence/rc-<sha>|long
7 UI_UX_REGRESSION|node docs/parallel/c6/harness/ui-ux.mjs ; node ui-retest.mjs ; python3 ui_analyze.py ; bash ui-final.sh (needs C5_FINAL_HEAD INTEGRATION_SHA PUBLIC_URLS)|docs/parallel/c6/evidence/ui-ux-regression/<c5sha>|long
8 USER_GUIDE_QA|node docs/parallel/c6/harness/ug-run.mjs ; python3 ug_matrix.py|docs/parallel/c6/evidence/user-guide-<date>|long
9 QA_MASTER|python3 -B docs/parallel/c6/harness/qa_master.py --sha <sha> --prev <prev> --tsv <backend-testcases.tsv> --nostack <dir> --write-state ; python3 -B docs/parallel/c6/harness/qa_master_normalize.py|docs/parallel/c6/QA_MASTER.md · QA_MASTER_NORMALIZED.tsv|seconds'
plan() { echo "C6 final gate plan for target ${SHA} (nothing is executed)"; echo "$PLAN" | while IFS='|' read -r n c e k; do printf '  %-34s cost=%-8s\n      cmd: %s\n      evidence: %s\n' "$n" "$k" "$c" "$e"; done; }
FAILS=0; chk() { if [ "$2" = ok ]; then echo "ok   $1"; else echo "FAIL $1"; FAILS=$((FAILS+1)); fi; }
case "${1:-}" in
  plan) plan ;;
  dry-run)
    plan; echo; echo "--- cheap local checks"
    for f in selftest.sh mac-gate.sh mac-full.sh lib.sh owned-process-test.sh recovery.sh run-overlay-tests.sh ui-final.sh rc-e2e-full2.sh final-gate.sh; do [ -f "$H/$f" ] && bash -n "$H/$f" 2>/dev/null && chk "exists+bash -n $f" ok || chk "exists+bash -n $f" no; done
    for f in contract-checks.py db-probes.py qa_master.py qa_master_reqs.py qa_master_normalize.py final.py parse.py; do python3 -B -c 'import ast,sys; ast.parse(open(sys.argv[1],encoding="utf-8").read())' "$H/$f" 2>/dev/null && chk "python syntax $f" ok || chk "python syntax $f" no; done
    for f in static-qa.mjs rc-sec.mjs rc-data.mjs ui-ux.mjs ui-retest.mjs ug-run.mjs; do node --check "$H/$f" 2>/dev/null && chk "node --check $f" ok || chk "node --check $f" no; done
    HITS=$(grep -rnE "pkill|killall|xargs +kill|kill +\$\(|lsof[^|]*\| *(xargs )?kill|fuser +-k|kill_port" "$H" "$C6/qa-tests" --include=*.sh --include=*.mjs --include=*.py --include=*.kt 2>/dev/null | grep -vE "/(ps-safety|final-gate)\.sh:" | wc -l | tr -d ' ')
    [ "$HITS" = 0 ] && chk "UNSAFE_PROCESS_KILL_REMAINING=0" ok || chk "UNSAFE_PROCESS_KILL_REMAINING=$HITS" no
    bash "$H/owned-process-test.sh" >/dev/null 2>&1 && chk "owned-process lifecycle test" ok || chk "owned-process lifecycle test" no
    python3 -B "$H/qa_master_normalize.py" --check >/dev/null 2>&1 && chk "QA_MASTER_NORMALIZED.tsv valid (statuses, 482 ids)" ok || chk "QA_MASTER_NORMALIZED.tsv valid" no
    echo "dry-run: failures=$FAILS"; [ $FAILS -eq 0 ]
    ;;
  status)
    echo "HEAD=$(git rev-parse --short=12 HEAD) branch=$(git branch --show-current)  integration/v2=$(git rev-parse --short=12 integration/v2 2>/dev/null || echo n/a)"
    python3 -B - "$C6/QA_MASTER_NORMALIZED.tsv" <<'PY'
import csv, sys, collections
r = list(csv.DictReader(open(sys.argv[1], encoding="utf-8"), delimiter="\t"))
print(f"QA_MASTER_TOTAL={len(r)}  " + "  ".join(f"{k}={v}" for k, v in sorted(collections.Counter(x['STATUS'] for x in r).items())))
print(f"RETEST_REQUIRED(stale)={sum(x['RETEST_REQUIRED']=='YES' for x in r)}  PROVENANCE_UNKNOWN={sum('PROVENANCE_UNKNOWN' in x.values() for x in r)}")
for b, n in sorted(collections.Counter(x['BATCH'] for x in r).items()): print(f"  batch {b:<4} rows={n:<4} last_tested={sorted({x['LAST_TESTED_SHA'][:12] for x in r if x['BATCH']==b})}")
PY
    ;;
  run) echo "final-gate.sh run is not implemented: the expensive gate is started by hand from this plan (C6-0 scope)." >&2; plan; exit 64 ;;
  *) sed -n 2,11p "${BASH_SOURCE[0]}" | cut -c1-200; exit 64 ;;
esac
