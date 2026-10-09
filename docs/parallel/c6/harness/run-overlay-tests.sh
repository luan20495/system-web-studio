#!/usr/bin/env bash
# Run the C6-owned Kotlin QA tests (docs/parallel/c6/qa-tests/kotlin/*.kt) against a SHA WITHOUT touching the production tree:
# a throw-away detached git worktree of <sha> is created, the tests are copied into it, Gradle runs only them, results are copied to the
# evidence directory, and the worktree is removed. Needs JDK 21 + Docker (Testcontainers). Never uses reset/clean/stash.
#   export JAVA_HOME=$(/usr/libexec/java_home -v 21); bash docs/parallel/c6/harness/run-overlay-tests.sh 8e91172 docs/parallel/c6/evidence/mac-8e91172-contract-checks [workdir]
set -u
SHA="${1:?sha}"; EV="${2:?evidence dir}"; WORK="${3:-${TMPDIR:-/tmp}/xweb-c6-overlay}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
case "$EV" in /*) ;; *) EV="$ROOT/$EV";; esac
mkdir -p "$EV" "$(dirname "$WORK")"
WT="$WORK-$SHA-$$"
FULL=$(git -C "$ROOT" rev-parse --verify "$SHA^{commit}") || exit 2
git -C "$ROOT" worktree add --detach "$WT" "$FULL" > "$EV/overlay-worktree.log" 2>&1 || { echo "cannot create worktree"; exit 2; }
mkdir -p "$WT/backend/src/test/kotlin/com/systemwebstudio/c6"
cp "$ROOT"/docs/parallel/c6/qa-tests/kotlin/*.kt "$WT/backend/src/test/kotlin/com/systemwebstudio/c6/"
{ echo "# overlay run: sha=$FULL start=$(date -u +%FT%TZ)"; echo "# files:"; ls "$WT/backend/src/test/kotlin/com/systemwebstudio/c6/"; } > "$EV/overlay-gradle.log"
( cd "$WT/backend" && ./gradlew test --tests 'com.systemwebstudio.c6.*' --no-daemon --no-build-cache -Pkotlin.daemon.jvmargs=-Xmx3g --console=plain ) >> "$EV/overlay-gradle.log" 2>&1
RC=$?
rm -f "$EV"/TEST-com.systemwebstudio.c6.*.xml
cp "$WT"/backend/build/test-results/test/TEST-com.systemwebstudio.c6.*.xml "$EV"/ 2>/dev/null
echo "# end=$(date -u +%FT%TZ) exit=$RC" >> "$EV/overlay-gradle.log"
git -C "$ROOT" worktree remove --force "$WT" >> "$EV/overlay-worktree.log" 2>&1
git -C "$ROOT" worktree prune
echo "overlay gradle exit=$RC; results in $EV"
exit $RC
