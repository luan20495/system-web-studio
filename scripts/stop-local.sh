#!/usr/bin/env bash
# Stop the API and UI started by run-local.sh. Add --infra to also stop containers (data volumes are kept).
# PROCESS SAFETY (D-C0-48): this machine is shared by several checkouts, each with its own stack. Nothing here is killed BY NAME or BY PORT ALONE:
#   - a pid file is honoured only when that pid still runs from THIS checkout (its working directory is inside $ROOT), so a stale file whose pid was reused is never signalled;
#   - a port is cleaned only through scripts/owned-process.mjs stop-port --cwd-under "$ROOT": a listener of another checkout (or any foreign process) is reported and left alone;
#   - the Gradle daemon is shared by every checkout and is never stopped here.
. "$(dirname "$0")/_env.sh"
OWNED_CLI=(node "$ROOT/scripts/owned-process.mjs")
API_PORT_STOP="${STOP_API_PORT:-8080}"
# is this pid a process of THIS checkout? (cwd inside $ROOT; the Gradle-forked API runs in $ROOT/backend, the workers in $ROOT)
own_pid() {
  local cwd; cwd="$(lsof -a -d cwd -Fn -p "$1" 2>/dev/null | sed -n 's/^n//p' | head -1)"
  [ -n "$cwd" ] && { [ "$cwd" = "$ROOT" ] || [ "${cwd#"$ROOT"/}" != "$cwd" ]; }
}
stop_pid_file() {
  local f="$1" p; [ -f "$f" ] || return 0; p="$(cat "$f" 2>/dev/null || true)"
  if [ -n "$p" ] && own_pid "$p"; then pkill -TERM -P "$p" 2>/dev/null || true; kill "$p" 2>/dev/null || true
  else echo "$f: pid ${p:-?} is not a running process of this checkout (stale file): not touched" >&2; fi
  rm -f "$f"
}
stop_port() {   # LISTEN only: without it a client connection of Docker Desktop's network proxy would match (containers keep connections to :8080)
  local port="$1" out rc=0; out="$("${OWNED_CLI[@]}" stop-port --port "$port" --cwd-under "$ROOT" 2>&1)" || rc=$?
  case "$rc" in 0) ;; 3) echo "port $port: used by a process that is NOT from this checkout: left alone ($(printf '%s' "$out" | sed -n 's/.*"command":"\([^"]\{0,80\}\).*/\1/p'))" >&2;; *) echo "port $port: could not be freed: $out" >&2;; esac
}
# stop the watchdog and backup daemon first: an intentional stop must not be "repaired" by the watchdog
stop_pid_file .run/watchdog-local.pid; stop_pid_file .run/backup-daemon-local.pid
[ -d .run/portals ] && "$(dirname "$0")/portals.sh" down || true      # V1 portals (started only by PORTALS=1)
for n in frontend backend render runner; do stop_pid_file ".run/$n.pid"; done
stop_port "$API_PORT_STOP"
stop_port "$FRONTEND_PORT"
# the render worker too (a stale worker would keep serving an old renderer after an update)
stop_port "${RENDER_PORT:-18095}"
# graceful shutdown lets in-flight requests finish: wait until the ports are really free before returning
for port in "$API_PORT_STOP" "$FRONTEND_PORT"; do
  for _ in $(seq 1 60); do lsof -ti tcp:"$port" -sTCP:LISTEN >/dev/null 2>&1 || break; sleep 1; done
done
if [ "${1:-}" = "--infra" ]; then docker compose stop; "$(dirname "$0")/data-target.sh" down 2>/dev/null || true; fi
echo stopped
