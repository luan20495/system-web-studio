#!/usr/bin/env bash
# Reproducible load test: k6 scenarios at several concurrency levels while sampling the API JVM's CPU and RSS.
# Needs the stack running with higher rate limits and instant mock deploys (the script prints the exact restart command):
#   RATE_LIMIT_PROMPT_MAX=1000000 RATE_LIMIT_PUBLISH_MAX=1000000 APP_DEPLOY_STEP_DELAY_MS=0 ./scripts/run-local.sh
# Results go to .run/load/ (k6 JSON summaries + samples). Usage: scripts/load-test.sh [duration] [vus...]
. "$(dirname "$0")/_env.sh"
command -v k6 >/dev/null || { echo "install k6 (brew install k6)" >&2; exit 1; }
DUR="${1:-30s}"; shift || true; LEVELS="${*:-10 50 100}"
OUT=.run/load; mkdir -p "$OUT"
PID=$(lsof -ti tcp:8080 -sTCP:LISTEN | head -1); [ -n "$PID" ] || { echo "API is not running" >&2; exit 1; }
echo "machine: $(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) cores, $(( $(sysctl -n hw.memsize) / 1073741824 )) GB; API pid $PID; duration $DUR"
for scenario in reads prompt publish; do
  for vus in $LEVELS; do
    tag="${scenario}-${vus}"
    ( while kill -0 $$ 2>/dev/null; do ps -o %cpu=,rss= -p "$PID" >> "$OUT/$tag.samples"; sleep 1; done ) & SAMPLER=$!
    rm -f "$OUT/$tag.samples"
    k6 run --quiet -e SCENARIO=$scenario -e VUS=$vus -e DURATION=$DUR -e LOAD_PASSWORD="$LOCAL_ADMIN_PASSWORD" \
       --summary-export "$OUT/$tag.json" infra/load/studio.k6.js > "$OUT/$tag.log" 2>&1 && rc=0 || rc=$?
    kill $SAMPLER 2>/dev/null || true; wait $SAMPLER 2>/dev/null || true
    python3 - "$OUT/$tag.json" "$OUT/$tag.samples" "$tag" "$rc" <<'PY'
import json,sys,os
f,sf,tag,rc=sys.argv[1:5]
d=json.load(open(f)); m=d["metrics"]; h=m.get("http_req_duration{kind:api}") or m["http_req_duration"]
cpu=[];rss=[]
if os.path.exists(sf):
    for l in open(sf):
        p=l.split()
        if len(p)==2: cpu.append(float(p[0])); rss.append(int(p[1])/1024)
print(f'{tag:12s} rps={m["http_reqs"]["rate"]:8.1f} p50={h["med"]:7.1f}ms p95={h["p(95)"]:7.1f}ms p99={h["p(99)"]:7.1f}ms max={h["max"]:7.1f}ms '
      f'err={(m.get("http_req_failed{kind:api}") or m["http_req_failed"])["value"]*100:5.2f}% cpu_avg={sum(cpu)/max(len(cpu),1):6.1f}% cpu_max={max(cpu or [0]):6.1f}% rss_max={max(rss or [0]):6.0f}MB thresholds_ok={rc==0}')
PY
  done
done
echo "--- login (Argon2id) at a deliberately low arrival rate"
k6 run --quiet -e SCENARIO=login -e LOGIN_RATE=5 -e DURATION=$DUR -e LOAD_PASSWORD="$LOCAL_ADMIN_PASSWORD" --summary-export "$OUT/login.json" infra/load/studio.k6.js > "$OUT/login.log" 2>&1 || true
python3 -c "import json;m=json.load(open('$OUT/login.json'))['metrics'];h=m['http_req_duration'];print(f'login 5/s    p50={h[\"med\"]:.0f}ms p95={h[\"p(95)\"]:.0f}ms p99={h[\"p(99)\"]:.0f}ms err={m[\"http_req_failed\"][\"value\"]*100:.2f}%')"
