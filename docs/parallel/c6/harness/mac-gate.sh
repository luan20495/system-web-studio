#!/usr/bin/env bash
# C6 Mac GATE (subset): A environment/HEAD · B backend compile+test (+ security suites) · C/D/E frontend typecheck/unit+conformance/build · F static QA.
# No stack, no E2E, no browser — for the full run use mac-full.sh. Same evidence dir and RESULTS.txt format. Never edits production code.
# Usage (repo root, on the Mac):  export JAVA_HOME=$(/usr/libexec/java_home -v 21); bash docs/parallel/c6/harness/mac-gate.sh [--allow-partial]
# Exit: 0 FINAL PASS · 1 FINAL FAIL · 2 FINAL INCOMPLETE (a critical phase NOT_RUN or prerequisite missing)
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ALLOW_PARTIAL=0; for a in "$@"; do [ "$a" = --allow-partial ] && ALLOW_PARTIAL=1; done
export C6_NEED_BACKEND=1 C6_NEED_DOCKER=1
phase_env; PRE=$?
res SCOPE INFO "gate-only(no stack/E2E/browser/recovery)"
if [ $PRE -ne 0 ] && [ $ALLOW_PARTIAL -ne 1 ]; then
  for k in BACKEND_COMPILE BACKEND_TEST SECURITY_SUITES FRONTEND_TYPECHECK FRONTEND_UNIT FRONTEND_BUILD STATIC_QA; do res $k NOT_RUN "reason=prerequisite_missing(fail-fast)"; done
  phase_finish gate; rc=$?; echo "RESULTS: $C6_RESULTS"; exit $rc
fi
if [ -n "$C6_JAVA_HOME" ] && [ $C6_DOCKER_OK -eq 1 ]; then phase_backend; else res BACKEND_COMPILE NOT_RUN "reason=jdk21_or_docker_missing"; res BACKEND_TEST NOT_RUN "reason=jdk21_or_docker_missing"; res SECURITY_SUITES NOT_RUN "reason=jdk21_or_docker_missing"; fi
phase_frontend
phase_static
phase_finish gate; rc=$?
echo "RESULTS: $C6_RESULTS"; exit $rc
