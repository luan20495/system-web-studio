#!/usr/bin/env bash
# V1 local stack smoke (scripts/v1-smoke.mjs). Needs the stack from `PORTALS=1 ./scripts/run-local.sh` (or the same environment) and the data target.
#   V1_SMOKE_DB=<scratch platform database name>  (default: the database of DATABASE_URL)  ./scripts/v1-smoke.sh
. "$(dirname "$0")/_env.sh"
DBNAME="${V1_SMOKE_DB:-$(echo "$DATABASE_URL" | sed -E 's#.*/([^/?]+).*#\1#')}"
export V1_SMOKE_SEED="${V1_SMOKE_SEED:-docker exec -i ${POSTGRES_CONTAINER:-hbl-postgres-1} psql -U ${DATABASE_USER} -d ${DBNAME} -q -v ON_ERROR_STOP=1}"
exec node "$ROOT/scripts/v1-smoke.mjs"
