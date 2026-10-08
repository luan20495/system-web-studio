#!/usr/bin/env bash
# Shell wrappers of scripts/owned-process.mjs (D-C0-48). Source it:   . "$ROOT/scripts/lib/owned-process.sh"
#   op_start  OWNER NAME STATEFILE [--port P] [--cwd DIR] [--ready-port P|--ready-url U] [--timeout S] -- cmd args...   starts a process this script then OWNS (own process group)
#   op_stop   STATEFILE                      stops exactly that process (and its group); a pid that was reused by someone else is NOT touched (exit 2)
#   op_status STATEFILE                      0 running, 1 gone, 2 reused by a foreign process
#   op_stop_port PORT [--state FILE] [--cwd-under DIR]   stops the listener ONLY when it is proven ours (exit 3 = FOREIGN_PROCESS, nothing killed)
# Never use pkill -f / killall / kill-by-port instead of these on the shared machine: tests/guards/process-safety.mjs fails the gate on them.
_op_cli() { node "${OP_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}/scripts/owned-process.mjs" "$@"; }
op_start() { local owner="$1" name="$2" state="$3"; shift 3; _op_cli start --owner "$owner" --name "$name" --state "$state" "$@"; }
op_stop() { _op_cli stop --state "$1" "${@:2}"; }
op_status() { _op_cli status --state "$1"; }
op_stop_port() { local port="$1"; shift; _op_cli stop-port --port "$port" "$@"; }
