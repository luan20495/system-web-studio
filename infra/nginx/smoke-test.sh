#!/usr/bin/env bash
# Real end-to-end check of the nginx example with throwaway certs (generated into a temp dir OUTSIDE the repo)
# and two stub upstreams (plain nginx containers that echo what they received).
# Usage: infra/nginx/smoke-test.sh      Needs docker + openssl + curl.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
HTTP_PORT="${SMOKE_HTTP_PORT:-18080}"; HTTPS_PORT="${SMOKE_HTTPS_PORT:-18443}"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/drill-nginx.XXXXXX")"
NET="drill-nginx-net"; PFX="drill-nginx"
fail=0
cleanup() { docker rm -f "$PFX-proxy" "$PFX-frontend" "$PFX-backend" "$PFX-minio" >/dev/null 2>&1 || true
            docker network rm "$NET" >/dev/null 2>&1 || true; rm -rf "$TMP"; }
trap cleanup EXIT
check() { if [ "$2" = "ok" ]; then echo "PASS  $1"; else echo "FAIL  $1"; fail=1; fi; }

mkdir -p "$TMP/certs/studio" "$TMP/certs/storage" "$TMP/stub" "$TMP/certbot"
for n in studio storage; do
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=$n.example.com" \
    -keyout "$TMP/certs/$n/privkey.pem" -out "$TMP/certs/$n/fullchain.pem" >/dev/null 2>&1
done
for n in frontend backend minio; do
cat > "$TMP/stub/$n.conf" <<STUB
server { listen 80; listen 3000; listen 8080; listen 9000;
  location / { default_type text/plain;
    return 200 "upstream=$n uri=\$request_uri xff=\$http_x_forwarded_for xri=\$http_x_real_ip proto=\$http_x_forwarded_proto xfh=\$http_x_forwarded_host rid=\$http_x_request_id host=\$http_host\n"; } }
STUB
done
docker network create "$NET" >/dev/null
for n in frontend backend minio; do
  docker run -d --name "$PFX-$n" --network "$NET" --network-alias "$n" \
    -v "$TMP/stub/$n.conf:/etc/nginx/conf.d/default.conf:ro" nginx:stable >/dev/null
done

RUN="docker run --rm --network $NET -v $HERE/nginx.conf:/etc/nginx/nginx.conf:ro -v $HERE/conf.d:/etc/nginx/conf.d:ro -v $TMP/certs:/etc/nginx/certs:ro -v $TMP/certbot:/var/www/certbot:ro"
echo "== nginx -t"; $RUN nginx:stable nginx -t && check "nginx -t" ok || check "nginx -t" bad

docker run -d --name "$PFX-proxy" --network "$NET" -p "127.0.0.1:$HTTP_PORT:80" -p "127.0.0.1:$HTTPS_PORT:443" \
  -v "$HERE/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$HERE/conf.d:/etc/nginx/conf.d:ro" \
  -v "$TMP/certs:/etc/nginx/certs:ro" -v "$TMP/certbot:/var/www/certbot:ro" nginx:stable >/dev/null
sleep 2
S=(--resolve "studio.example.com:$HTTPS_PORT:127.0.0.1" --resolve "storage.example.com:$HTTPS_PORT:127.0.0.1" -k -sS)

echo "== HTTP -> HTTPS redirect"
h=$(curl -sS -i -H 'Host: studio.example.com' "http://127.0.0.1:$HTTP_PORT/projects?x=1" | tr -d '\r')
echo "$h" | sed -n '1p;/^[Ll]ocation/p'
echo "$h" | grep -q '^HTTP/1.1 301' && echo "$h" | grep -qi '^location: https://studio.example.com/projects?x=1' && check redirect ok || check redirect bad

echo "== routing"
r1=$(curl "${S[@]}" "https://studio.example.com:$HTTPS_PORT/dashboard"); echo "$r1"
echo "$r1" | grep -q 'upstream=frontend uri=/dashboard' && check "/ -> frontend" ok || check "/ -> frontend" bad
r2=$(curl "${S[@]}" "https://studio.example.com:$HTTPS_PORT/api/v1/me"); echo "$r2"
echo "$r2" | grep -q 'upstream=backend uri=/api/v1/me' && check "/api/ -> backend" ok || check "/api/ -> backend" bad
r3=$(curl "${S[@]}" "https://studio.example.com:$HTTPS_PORT/oauth2/authorization/oidc"); echo "$r3"
echo "$r3" | grep -q 'upstream=backend uri=/oauth2/authorization/oidc' && check "/oauth2/ -> backend" ok || check "/oauth2/ -> backend" bad
r4=$(curl "${S[@]}" "https://studio.example.com:$HTTPS_PORT/login/oauth2/code/oidc?code=x"); echo "$r4"
echo "$r4" | grep -q 'upstream=backend uri=/login/oauth2/code/oidc' && check "/login/oauth2/ -> backend" ok || check "/login/oauth2/ -> backend" bad
r5=$(curl "${S[@]}" "https://storage.example.com:$HTTPS_PORT/studio-assets/a.png?X-Amz-Signature=abc"); echo "$r5"
echo "$r5" | grep -q 'upstream=minio' && echo "$r5" | grep -q "host=storage.example.com:$HTTPS_PORT" && check "storage. -> minio, Host preserved" ok || check "storage. -> minio, Host preserved" bad

echo "== X-Forwarded-For overwrite (spoofed 6.6.6.6) and forwarded headers"
x=$(curl "${S[@]}" -H 'X-Forwarded-For: 6.6.6.6' -H 'X-Forwarded-Proto: http' -H 'X-Forwarded-Host: evil.test' "https://studio.example.com:$HTTPS_PORT/api/x"); echo "$x"
echo "$x" | grep -q 'xff=6.6.6.6' && check "XFF not spoofable" bad || { echo "$x" | grep -Eq 'xff=[0-9.]+ ' && check "XFF overwritten with peer address" ok || check "XFF overwritten with peer address" bad; }
echo "$x" | grep -q 'proto=https' && echo "$x" | grep -q 'xfh=studio.example.com' && check "proto/host overwritten" ok || check "proto/host overwritten" bad

echo "== X-Request-Id"
g=$(curl "${S[@]}" "https://studio.example.com:$HTTPS_PORT/api/x"); echo "$g"
echo "$g" | grep -Eq 'rid=[0-9a-f]{32} ' && check "request id generated" ok || check "request id generated" bad
p=$(curl "${S[@]}" -H 'X-Request-Id: client-req-12345' "https://studio.example.com:$HTTPS_PORT/api/x")
echo "$p" | grep -q 'rid=client-req-12345 ' && check "valid request id passed through" ok || check "valid request id passed through" bad
b=$(curl "${S[@]}" -H 'X-Request-Id: bad value;x' "https://studio.example.com:$HTTPS_PORT/api/x")
echo "$b" | grep -q 'rid=bad' && check "malformed request id replaced" bad || check "malformed request id replaced" ok

echo "== response headers"
hd=$(curl "${S[@]}" -D - -o /dev/null "https://studio.example.com:$HTTPS_PORT/" | tr -d '\r'); echo "$hd"
for hname in strict-transport-security x-content-type-options referrer-policy permissions-policy x-request-id; do
  echo "$hd" | grep -qi "^$hname:" && check "header $hname" ok || check "header $hname" bad
done
echo "$hd" | grep -qi '^content-security-policy:' && check "no CSP at nginx" bad || check "no CSP at nginx (Next owns it)" ok
echo "$hd" | grep -qi '^server: nginx/' && check "server version hidden" bad || check "server version hidden" ok
hdb=$(curl "${S[@]}" -D - -o /dev/null "https://studio.example.com:$HTTPS_PORT/api/missing" | tr -d '\r')
echo "$hdb" | grep -qi '^strict-transport-security:' && check "HSTS on /api/ too" ok || check "HSTS on /api/ too" bad

echo "== body size limit (client_max_body_size 12m)"
head -c 13000000 /dev/zero > "$TMP/big.bin"
code=$(curl "${S[@]}" -o /dev/null -w '%{http_code}' --data-binary @"$TMP/big.bin" "https://studio.example.com:$HTTPS_PORT/api/upload")
[ "$code" = 413 ] && check "13MB body rejected with 413" ok || { echo "got $code"; check "13MB body rejected with 413" bad; }
code=$(curl "${S[@]}" -o /dev/null -w '%{http_code}' --data-binary @"$TMP/stub/frontend.conf" "https://studio.example.com:$HTTPS_PORT/api/upload")
[ "$code" = 200 ] && check "small body accepted" ok || check "small body accepted" bad

echo "== ACME challenge path served over HTTP"
mkdir -p "$TMP/certbot/.well-known/acme-challenge"; echo token123 > "$TMP/certbot/.well-known/acme-challenge/tok"
[ "$(curl -sS -H 'Host: studio.example.com' http://127.0.0.1:$HTTP_PORT/.well-known/acme-challenge/tok)" = token123 ] && check "acme challenge" ok || check "acme challenge" bad

[ "$fail" = 0 ] && echo "NGINX SMOKE TEST PASSED" || { echo "NGINX SMOKE TEST FAILED" >&2; exit 1; }
