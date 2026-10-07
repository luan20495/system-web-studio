#!/usr/bin/env bash
# V1 public smoke (scripts/v1-public-smoke.mjs); needs the PORTALS=1 stack, the data target and Google Chrome.
. "$(dirname "$0")/_env.sh"
exec node "$ROOT/scripts/v1-public-smoke.mjs"
