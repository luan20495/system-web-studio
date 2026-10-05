#!/usr/bin/env bash
# Fast API smoke test against the running stack (scripts/run-local.sh). Uses only curl + python3.
. "$(dirname "$0")/_env.sh"
B="${API_BASE:-http://127.0.0.1:8080/api/v1}"; J="$(mktemp)"; trap 'rm -f "$J"' EXIT
W=00000000-0000-0000-0000-000000000001
fail() { echo "FAIL: $*" >&2; exit 1; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval('d'+sys.argv[1]))" "$1"; }

[ "$(status http://127.0.0.1:8080/actuator/health/readiness)" = 200 ] || fail "readiness (db, redis, rabbit, minio) not UP"
[ "$(status "$B/workspaces/$W/projects")" = 401 ] || fail "unauthenticated request must be 401"
T=$(curl -s -c "$J" -b "$J" "$B/auth/csrf" | jget '["token"]')
[ "$(status -c "$J" -b "$J" -X POST "$B/auth/login" -H 'Content-Type: application/json' -d '{"username":"local.admin","password":"x"}')" = 403 ] || fail "login without CSRF must be 403"
LOGIN=$(python3 -c 'import json,sys;print(json.dumps({"username":"local.admin","password":sys.argv[1]}))' "$LOCAL_ADMIN_PASSWORD")
CODE=$(curl -s -o /dev/null -w '%{http_code}' -c "$J" -b "$J" -X POST "$B/auth/login" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d "$LOGIN")
[ "$CODE" = 200 ] || fail "login returned $CODE"
T=$(curl -s -c "$J" -b "$J" "$B/auth/csrf" | jget '["token"]')
H=(-H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json')
P=$(curl -s -b "$J" -c "$J" "${H[@]}" -X POST "$B/workspaces/$W/projects" -d '{"name":"Smoke test"}' | jget '["id"]'); [ -n "$P" ] || fail "create project"
R=$(curl -s -b "$J" "$B/workspaces/$W/projects/$P" | jget '["revision"]')
OUT=$(curl -s -b "$J" "${H[@]}" -X POST "$B/workspaces/$W/projects/$P/prompts" -d "{\"prompt\":\"thêm bảng so sánh\",\"expectedRevision\":$R}")
[ "$(echo "$OUT" | jget '["outcome"]')" = UPDATED ] || fail "prompt did not update the page: $OUT"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$J" "${H[@]}" -X POST "$B/workspaces/$W/projects/$P/prompts" -d "{\"prompt\":\"bỏ đánh giá\",\"expectedRevision\":$R}")
[ "$CODE" = 409 ] || fail "stale revision must be 409 (got $CODE)"
N=$(curl -s -b "$J" "$B/workspaces/$W/projects/$P/versions" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))'); [ "$N" = 2 ] || fail "expected 2 versions, got $N"
R=$(curl -s -b "$J" "$B/workspaces/$W/projects/$P" | jget '["revision"]')
D=$(curl -s -b "$J" "${H[@]}" -H "Idempotency-Key: smoke-$(date +%s)" -X POST "$B/workspaces/$W/projects/$P/publish" -d "{\"visibility\":\"PRIVATE\",\"expectedRevision\":$R}" | jget '["id"]')
for _ in $(seq 1 30); do S=$(curl -s -b "$J" "$B/workspaces/$W/projects/$P/deployments/$D" | jget '["status"]'); [ "$S" = RUNNING ] || [ "$S" = FAILED ] && break; sleep 1; done
[ "$S" = RUNNING ] || fail "deployment ended as $S"
echo "SMOKE TEST PASSED (project $P, deployment $D RUNNING via mock provider)"
