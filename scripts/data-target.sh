#!/usr/bin/env bash
# V1 local data target (D-C0-31 / L-6): a dedicated PostgreSQL with TLS for the data-source flow of Studio. NOT the platform DB (15432) and NOT the apps DB (15434).
#   ./scripts/data-target.sh up            create (once) the dev CA, the certificate, the trust store, generated passwords; start the container; seed the demo database
#   ./scripts/data-target.sh status        container, port, TLS handshake
#   ./scripts/data-target.sh password ro|rw   print the generated password of the SELECT-only / writer role (to type into Studio; the only place it is ever shown)
#   ./scripts/data-target.sh down [--purge]   stop and remove the container (data volume kept); --purge also deletes the volume and every generated file
# Everything generated is under .run/data-target/ (git-ignored, mode 600). Nothing secret is committed or printed except by `password`.
# The backend reaches it only because `PORTALS=1` (scripts/_env.sh) puts exactly 127.0.0.1:$DATA_TARGET_PORT on app.data-platform.postgres-targets.allowed-private and the
# dev CA in the JVM trust store; the connector still verifies the chain and the host name (verify-full) and still refuses the platform and apps databases.
# V2: the data source points at a real host name with a public CA, by configuration only.
set -euo pipefail
. "$(dirname "$0")/_env.sh"
D="$ROOT/.run/data-target"; NAME="${DATA_TARGET_CONTAINER:-hbl-v1-data-target}"; VOL="${NAME}-data"; PORT="$DATA_TARGET_PORT"; IMAGE="${DATA_TARGET_IMAGE:-postgres:17.6}"
DB="${DATA_TARGET_DB:-shop}"
need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: $1 is required" >&2; exit 2; }; }
running() { [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null || echo false)" = true ]; }
exists() { docker inspect "$NAME" >/dev/null 2>&1; }
pw() { [ -s "$D/$1.pw" ] || (umask 077; openssl rand -hex 16 | tr -d '\n' > "$D/$1.pw"); cat "$D/$1.pw"; }

gen_tls() {
  # regenerate when missing or when the server certificate expires within 30 days
  if [ -s "$D/server.crt" ] && openssl x509 -in "$D/server.crt" -noout -checkend $((30*86400)) >/dev/null 2>&1 && [ -s "$D/truststore.jks" ]; then return; fi
  echo "generating the dev CA and server certificate (IP SAN 127.0.0.1) ..."
  local JH="$JAVA_HOME"; [ -f "$JH/lib/security/cacerts" ] || JH="$JAVA_HOME/libexec/openjdk.jdk/Contents/Home"
  [ -f "$JH/lib/security/cacerts" ] || { echo "ERROR: no JDK trust store under JAVA_HOME ($JAVA_HOME)" >&2; exit 2; }
  ( umask 077
    openssl genrsa -out "$D/ca.key" 2048 2>/dev/null
    openssl req -x509 -new -nodes -key "$D/ca.key" -sha256 -days 365 -subj "/CN=hbl-v1-dev-ca" -out "$D/ca.crt" 2>/dev/null
    openssl genrsa -out "$D/server.key" 2048 2>/dev/null
    openssl req -new -key "$D/server.key" -subj "/CN=127.0.0.1" -out "$D/server.csr" 2>/dev/null
    printf "subjectAltName=IP:127.0.0.1\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n" > "$D/ext.cnf"
    openssl x509 -req -in "$D/server.csr" -CA "$D/ca.crt" -CAkey "$D/ca.key" -CAcreateserial -out "$D/server.crt" -days 365 -sha256 -extfile "$D/ext.cnf" 2>/dev/null
    rm -f "$D/server.csr" "$D/ext.cnf" "$D/ca.srl" "$D/truststore.jks"
    openssl rand -hex 12 | tr -d '\n' > "$D/truststore.pass"
    # the JVM's own CAs plus the dev CA: replacing the trust store must not break any other TLS the backend does
    cp "$JH/lib/security/cacerts" "$D/truststore.jks"; chmod 600 "$D/truststore.jks"
    "$JH/bin/keytool" -storepasswd -keystore "$D/truststore.jks" -storepass changeit -new "$(cat "$D/truststore.pass")" >/dev/null 2>&1
    "$JH/bin/keytool" -importcert -alias hbl-v1-dev-ca -file "$D/ca.crt" -keystore "$D/truststore.jks" -storepass "$(cat "$D/truststore.pass")" -noprompt >/dev/null 2>&1 )
}

seed() {
  local ro rw; ro=$(pw ro); rw=$(pw rw)
  docker exec -i "$NAME" psql -U postgres -v ON_ERROR_STOP=1 -q -d postgres <<SQL
SELECT 'CREATE DATABASE $DB' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '$DB')\gexec
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shop_ro') THEN CREATE ROLE shop_ro LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shop_rw') THEN CREATE ROLE shop_rw LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; END IF;
END \$\$;
ALTER ROLE shop_ro PASSWORD '$ro';
ALTER ROLE shop_rw PASSWORD '$rw';
SQL
  docker exec -i "$NAME" psql -U postgres -v ON_ERROR_STOP=1 -q -d "$DB" <<SQL
CREATE SCHEMA IF NOT EXISTS shop;
CREATE TABLE IF NOT EXISTS shop.customers (id serial PRIMARY KEY, name text NOT NULL, plan text NOT NULL);
CREATE TABLE IF NOT EXISTS shop.orders (id serial PRIMARY KEY, order_no text NOT NULL UNIQUE, customer_id int NOT NULL REFERENCES shop.customers(id), status text NOT NULL DEFAULT 'open', amount numeric(12,2) NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now());
INSERT INTO shop.customers (name, plan) SELECT * FROM (VALUES ('ACME Co','pro'),('Globex','free'),('Initech','pro')) v WHERE NOT EXISTS (SELECT 1 FROM shop.customers);
INSERT INTO shop.orders (order_no, customer_id, status, amount) SELECT * FROM (VALUES ('SO-1001',1,'open',120.50),('SO-1002',2,'open',75.00),('SO-1003',3,'closed',310.25)) v WHERE NOT EXISTS (SELECT 1 FROM shop.orders);
GRANT CONNECT ON DATABASE $DB TO shop_ro, shop_rw;
GRANT USAGE ON SCHEMA shop TO shop_ro, shop_rw;
GRANT SELECT ON ALL TABLES IN SCHEMA shop TO shop_ro, shop_rw;
GRANT INSERT, UPDATE ON shop.orders TO shop_rw;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA shop TO shop_rw;
SQL
}

cmd_up() {
  need docker; need openssl; docker info >/dev/null 2>&1 || { echo "ERROR: Docker is not running" >&2; exit 2; }
  mkdir -p "$D"; chmod 700 "$D"
  if ! exists; then
    # the port must be free: a foreign listener is named and left alone
    local who; who=$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1 || true)
    [ -z "$who" ] || { echo "ERROR: port $PORT is already used by pid $who ($(ps -p "$who" -o comm= 2>/dev/null | tail -c 60)). Free it or set DATA_TARGET_PORT." >&2; exit 2; }
  fi
  gen_tls; pw admin >/dev/null; pw ro >/dev/null; pw rw >/dev/null
  if ! running; then
    exists && docker rm -f "$NAME" >/dev/null
    docker run -d --name "$NAME" -p "127.0.0.1:${PORT}:5432" -v "$VOL":/var/lib/postgresql/data -v "$D":/certs:ro -e POSTGRES_PASSWORD_FILE=/certs/admin.pw \
      "$IMAGE" sh -c 'cp /certs/server.crt /certs/server.key /var/lib/postgresql/ && chown postgres:postgres /var/lib/postgresql/server.* && chmod 600 /var/lib/postgresql/server.key && exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/var/lib/postgresql/server.crt -c ssl_key_file=/var/lib/postgresql/server.key' >/dev/null
  fi
  for _ in $(seq 1 60); do docker exec "$NAME" pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1 && break; sleep 1; done
  docker exec "$NAME" pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1 || { echo "ERROR: the data target did not become ready; docker logs $NAME" >&2; exit 1; }
  # a fresh volume restarts once after initdb: wait for the final server, then seed
  sleep 2; for _ in $(seq 1 30); do docker exec "$NAME" psql -U postgres -Atc 'select 1' >/dev/null 2>&1 && break; sleep 1; done
  seed
  echo "data target ready: 127.0.0.1:${PORT}  database ${DB}  roles shop_ro (SELECT) / shop_rw (SELECT, INSERT, UPDATE on shop.orders)  TLS verify-full (dev CA in .run/data-target/truststore.jks)"
  echo "Studio data source: host 127.0.0.1, port ${PORT}, database ${DB}, schemas shop; credential username shop_ro, password: ./scripts/data-target.sh password ro"
}

cmd_status() {
  exists || { echo "data target: not created"; return 1; }
  running || { echo "data target: container $NAME exists but is stopped"; return 1; }
  local hs; hs=$(echo | openssl s_client -connect "127.0.0.1:${PORT}" -starttls postgres -CAfile "$D/ca.crt" -verify_return_error 2>&1 | grep -E "Verification: " | head -1)
  echo "data target: $NAME running on 127.0.0.1:${PORT}  TLS ${hs:-unknown}"
  docker exec "$NAME" psql -U postgres -d "$DB" -Atc "select 'database $DB: ' || count(*) || ' customers' from shop.customers" 2>/dev/null || true
}

cmd_down() {
  exists && { docker rm -f "$NAME" >/dev/null && echo "data target container removed (volume $VOL kept)"; } || echo "data target: nothing to stop"
  if [ "${1:-}" = "--purge" ]; then docker volume rm "$VOL" >/dev/null 2>&1 || true; rm -rf "$D"; echo "volume and generated files deleted"; fi
}

case "${1:-}" in
  up) cmd_up;; status) cmd_status;; down) shift; cmd_down "${1:-}";;
  password) case "${2:-}" in ro|rw) cat "$D/${2}.pw"; echo;; *) echo "usage: $0 password ro|rw" >&2; exit 64;; esac;;
  *) echo "usage: $0 up|status|password ro|rw|down [--purge]" >&2; exit 64;;
esac
