# Production reverse proxy (nginx) - example

Status: **the configuration was validated and smoke-tested locally with throwaway certificates and stub upstreams
(results below). It has not been run in front of the real frontend/backend images, with a real certificate, or on a
public host.**

| File | Purpose |
| --- | --- |
| `nginx.conf` | main config: request-id map, WebSocket map, log format, includes `conf.d/*.conf` |
| `conf.d/studio.conf` | `:80` ACME + redirect, `studio.example.com` (`/` -> Next.js, `/api/` `/oauth2/` `/login/oauth2/` -> API), `storage.example.com` (MinIO presigned URLs) |
| `docker-compose.prod.example.yml` | single-host topology: only nginx publishes ports; Postgres/Redis/MinIO/RabbitMQ/API are on an `internal: true` network |
| `smoke-test.sh` | the real check described below (throwaway certs in a temp dir outside the repo, no files written into the repo) |

No certificate or key is committed. Replace `studio.example.com` / `storage.example.com` in `conf.d/studio.conf` first.

## TLS certificates

nginx reads `/etc/nginx/certs/studio/{fullchain,privkey}.pem` and `/etc/nginx/certs/storage/{fullchain,privkey}.pem`;
the compose example mounts the host directory `${TLS_CERT_DIR}` there read-only. Options:

* **Let's Encrypt with certbot (webroot)** - port 80 serves `/.well-known/acme-challenge/` from the `certbot-webroot` volume:
  `docker compose -f docker-compose.prod.example.yml --profile certbot run --rm certbot certonly --webroot -w /var/www/certbot -d studio.example.com -d storage.example.com --email ops@example.com --agree-tos`
  (the certbot service mounts `${TLS_CERT_DIR}` as `/etc/letsencrypt`; point the two `ssl_certificate*` paths at
  `/etc/nginx/certs/live/studio.example.com/{fullchain,privkey}.pem`, or use one SAN cert for both server blocks).
  Renew from cron (`certbot renew`) followed by `docker compose exec nginx nginx -s reload`. Not exercised here (needs public DNS).
* **Managed TLS** (cloud load balancer, Cloudflare, etc.): terminate there and keep this nginx on plain HTTP behind it. Then replace
  `$remote_addr` handling with the realip module (`set_real_ip_from <LB CIDR>; real_ip_header X-Forwarded-For; real_ip_recursive on;`)
  so nginx, not the client, decides who the client is, and list the nginx address in `TRUSTED_PROXY_CIDRS`.
* **acme.sh / Caddy / Traefik** work equally; only the file paths above matter.

## Behaviour (what the config does)

* `:80` -> `301 https://$host$request_uri`, except `/.well-known/acme-challenge/`.
* Headers on every response (`always`, including errors): `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`,
  `Referrer-Policy`, `Permissions-Policy`, plus `X-Request-Id`. **No `Content-Security-Policy` at nginx on purpose**: the Next app
  (`proxy.ts`) emits a per-request nonce CSP; a static nginx CSP would either break the nonce or be merged with it by the browser
  (policies intersect) and block scripts. If the browser must talk to the storage origin, add it through the app's
  `CSP_EXTRA_CONNECT_ORIGINS`, not here. Beware: an `add_header` inside a `location` hides the server-level ones.
* `client_max_body_size 12m` (keep it >= the API upload limit), 5s connect / 60s read+send timeouts on the app, 300s on storage.
* Forwarded headers are **overwritten**, never appended: `X-Forwarded-For $remote_addr`, `X-Real-IP $remote_addr`,
  `X-Forwarded-Proto $scheme`, `X-Forwarded-Host $host`. The backend (`ClientIpFilter`) only reads them when `TRUST_PROXY=true`
  **and** the TCP peer is inside `TRUSTED_PROXY_CIDRS`. In the compose example nginx reaches the backend over the `internal`
  network, so set `TRUSTED_PROXY_CIDRS` to that network's subnet:
  `docker network inspect <project>_internal --format '{{(index .IPAM.Config 0).Subnet}}'` (pin the subnet in compose with
  `ipam` if you want it stable). Never trust `0.0.0.0/0`.
* `X-Request-Id`: a client/LB-supplied id matching `[A-Za-z0-9._-]{8,64}` is passed through, anything else is replaced by nginx's `$request_id`;
  the id is forwarded upstream and returned to the client.
* WebSocket readiness: `Upgrade`/`Connection` are mapped and forwarded (HTTP/1.1 upstream, keepalive pools).
* Nothing but nginx is published in `docker-compose.prod.example.yml` (80/443). `internal: true` also gives the other services no route to the internet;
  put the backend on a second, non-internal network if it must reach an external OIDC issuer, SMTP, etc.

### Object storage (presigned URLs)

The API signs URLs for `MINIO_PUBLIC_ENDPOINT`. S3 signatures cover the `Host` header, so the browser must reach MinIO under exactly that host.
Two supported shapes: (a) the dedicated `storage.example.com` server block in `conf.d/studio.conf` (TLS, `proxy_set_header Host $http_host`,
unbuffered streaming uploads) with `MINIO_PUBLIC_ENDPOINT=https://storage.example.com`; (b) a managed S3/B2 endpoint reachable by browsers, which
makes the block unnecessary. A path prefix such as `/storage/` on the app host is **not** supported (the signed path would not match).
Bucket CORS must allow the app origin for browser PUT/GET.

## Validate it yourself

```bash
infra/nginx/smoke-test.sh          # needs docker, openssl, curl; uses host ports 18080/18443 (override SMOKE_HTTP_PORT/SMOKE_HTTPS_PORT)
```

It generates two 1-day self-signed certificates in a `mktemp` directory (deleted on exit), starts two stub upstreams and a stub for MinIO
(plain `nginx:stable` containers echoing what they received), runs `nginx -t` (`docker run --rm ... nginx:stable nginx -t` with the config,
`conf.d` and the certificates mounted), starts nginx and checks behaviour with curl. Containers/network are named `drill-nginx-*` and removed on exit.

### Measured result (run on this machine, nginx:stable, 2026-10-01)

```
PASS  nginx -t                              ("syntax is ok" / "test is successful")
PASS  redirect                              HTTP/1.1 301, Location: https://studio.example.com/projects?x=1
PASS  / -> frontend                         upstream=frontend uri=/dashboard
PASS  /api/ -> backend                      upstream=backend uri=/api/v1/me
PASS  /oauth2/ -> backend                   upstream=backend uri=/oauth2/authorization/oidc
PASS  /login/oauth2/ -> backend             upstream=backend uri=/login/oauth2/code/oidc?code=x
PASS  storage. -> minio, Host preserved     host=storage.example.com:18443
PASS  XFF overwritten with peer address     client sent X-Forwarded-For: 6.6.6.6 -> upstream saw xff=172.21.0.1 (the docker gateway = real TCP peer)
PASS  proto/host overwritten                client sent X-Forwarded-Proto: http, X-Forwarded-Host: evil.test -> upstream saw https / studio.example.com
PASS  request id generated / valid passed through / malformed replaced
PASS  headers: strict-transport-security, x-content-type-options, referrer-policy, permissions-policy, x-request-id; no CSP; no server version
PASS  HSTS also present on /api/ responses
PASS  13MB body -> 413; small body -> 200
PASS  acme challenge served over HTTP
NGINX SMOKE TEST PASSED
```

Not covered: HTTP/2 specifics beyond curl negotiating it, real Let's Encrypt issuance, real upstream applications, WebSocket traffic (only header
mapping is configured; no WebSocket endpoint exists yet), load/limits tuning, and `docker-compose.prod.example.yml` (only `docker compose config -q` was run on it with dummy variables, which passed; it was not started because it needs built frontend/backend images and secrets).
