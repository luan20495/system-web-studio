#!/usr/bin/env bash
# Source-aware lifecycle of the three portals (D-C0-45). Sourced by scripts/portals.sh (local) and scripts/public-portals.sh (public); never run directly.
# bash 3.2 compatible (macOS): no associative arrays, empty arrays are expanded with ${a[@]+"${a[@]}"}.
#
# A portal is a production build of apps/<name> served by `next start` on its own loopback port. This library makes "what is running" and "what the source says" comparable:
#   build fingerprint   = scripts/portal-fingerprint.mjs: content hash of everything the build reads (+ the build environment + the Node major version)
#   run fingerprint     = build fingerprint + the RUN-time environment (a change that needs a restart but not a rebuild)
#   dist directory      = apps/<name>/.next-<mode>-<build fingerprint:12>   (never `.next`, never shared by the two modes, never overwritten while a process serves it)
# State (RUNDIR, one set per portal; $PL_PREFIX is "" for local and "portal-" for public so the pid files of earlier versions of the scripts stay valid):
#   <p>.fp  <p>.dist  <p>.buildmeta        the last SUCCESSFUL build (written last, atomically: a failed build never changes them)
#   <p>.pid <p>.pidstart <p>.launcher      the process this library started (a pid alone is never trusted: it must still be THE listener of the port with the same start time)
#   <p>.runfp <p>.runbfp <p>.rundist <p>.runmeta   what that process was started from
#
# The caller sets (before `pl_main`): ROOT MODE(local|public) RUNDIR PL_PREFIX NAMES HOST  pl_port_of() pl_label_of()  BUILD_ENV(array) RUN_ENV(array)
set -euo pipefail

PL_NODE="${PL_NODE:-node}"
pl_fpfile() { echo "$RUNDIR/$PL_PREFIX$1.$2"; }
pl_get() { cat "$(pl_fpfile "$1" "$2")" 2>/dev/null || true; }
pl_put() { local f; f="$(pl_fpfile "$1" "$2")"; printf '%s\n' "$3" > "$f.tmp.$$" && mv "$f.tmp.$$" "$f"; }       # atomic
pl_del() { rm -f "$(pl_fpfile "$1" "$2")"; }
pl_listener() { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1 || true; }
pl_pstart() { ps -p "$1" -o lstart= 2>/dev/null | sed 's/^ *//;s/ *$//' || true; }
pl_pargs() { ps -p "$1" -o args= 2>/dev/null | cut -c1-110 || true; }
pl_code() { curl -s -o /dev/null -m 5 -w '%{http_code}' "http://$HOST:$1/" 2>/dev/null || true; }
pl_healthy() { local c; c="$(pl_code "$1")"; [ -n "$c" ] && [ "$c" != 000 ] && [ "$c" -lt 500 ]; }
pl_sha() { shasum -a 256 | cut -c1-16; }

# --- fingerprints ----------------------------------------------------------------------------------------------------------------------------------------
pl_bfp() { "$PL_NODE" "$ROOT/scripts/portal-fingerprint.mjs" --root "$ROOT" --app "$1" -- ${BUILD_ENV[@]+"${BUILD_ENV[@]}"}; }
pl_meta() { "$PL_NODE" "$ROOT/scripts/portal-fingerprint.mjs" --root "$ROOT" --app "$1" --meta 2>/dev/null || echo "nogit 0"; }
pl_runfp() { { echo "$1"; printf '%s\n' ${RUN_ENV[@]+"${RUN_ENV[@]}"} | sort; } | pl_sha; }            # $1 = build fingerprint
pl_fmtmeta() { local m="$1"; if [ "${m#* }" = 1 ]; then echo "${m% *}+dirty"; else echo "${m% *}"; fi; }
pl_distname() { echo ".next-$MODE-${1:0:12}"; }                                                         # $1 = build fingerprint

# --- ownership -------------------------------------------------------------------------------------------------------------------------------------------
# ours = the recorded pid is STILL the listener of the portal's port and (when recorded) has the same start time: a reused pid number or a foreign listener never matches
pl_ours() {
  local n="$1" p pid ps0 now; p="$(pl_port_of "$n")"; pid="$(pl_get "$n" pid)"
  [ -n "$pid" ] && [ "$(pl_listener "$p")" = "$pid" ] || return 1
  ps0="$(pl_get "$n" pidstart)"; if [ -n "$ps0" ]; then now="$(pl_pstart "$pid")"; [ "$now" = "$ps0" ] || return 1; fi
  return 0
}

# --- build -----------------------------------------------------------------------------------------------------------------------------------------------
# Builds into a NEW directory named after the fingerprint; the running process (if any) keeps serving its own directory. Success writes the state LAST; failure writes nothing and leaves nothing.
pl_build() {
  local n="$1" fp dist app log keep f snap ok=0
  fp="$(pl_bfp "$n")"; dist="$(pl_distname "$fp")"; app="$ROOT/apps/$n"; log="$RUNDIR/$PL_PREFIX$n.build.log"
  rm -rf "$app/$dist"
  # `next build` rewrites tsconfig.json (and next-env.d.ts) for a custom distDir: snapshot, build, put the tracked files back (a dirty tree would change the next fingerprint)
  snap="$(mktemp -d "${TMPDIR:-/tmp}/portal-snap.XXXXXX")"
  for f in tsconfig.json next-env.d.ts; do [ -f "$app/$f" ] && cp "$app/$f" "$snap/$f"; done
  echo "building portal $n [$MODE] fingerprint $fp -> apps/$n/$dist"
  ( cd "$app" && env ${BUILD_ENV[@]+"${BUILD_ENV[@]}"} NODE_ENV=production NEXT_DIST_DIR="$dist" npx next build > "$log" 2>&1 < /dev/null ) && ok=1
  for f in tsconfig.json next-env.d.ts; do [ -f "$snap/$f" ] && ! cmp -s "$snap/$f" "$app/$f" && cp "$snap/$f" "$app/$f"; done
  rm -rf "$snap"
  if [ "$ok" = 1 ] && [ -f "$app/$dist/BUILD_ID" ]; then
    pl_put "$n" dist "$dist"; pl_put "$n" buildmeta "$(pl_meta "$n") $(date -u +%FT%TZ)"; pl_put "$n" fp "$fp"       # fp LAST: it is the "this build is complete" marker
    return 0
  fi
  rm -rf "$app/$dist"
  echo "ERROR: build of portal $n failed (the previous build and the running process are untouched); last lines of $log:" >&2; tail -12 "$log" >&2
  return 1
}
# is the build for the CURRENT source already there?
pl_build_current() { local n="$1" fp; fp="$(pl_bfp "$n")"; [ "$(pl_get "$n" fp)" = "$fp" ] && [ -f "$ROOT/apps/$n/$(pl_distname "$fp")/BUILD_ID" ] && [ "$(pl_get "$n" dist)" = "$(pl_distname "$fp")" ]; }

# --- process ---------------------------------------------------------------------------------------------------------------------------------------------
pl_stop() {                       # only a process this library started; TERM, then KILL after 15 s (it is ours); the npx launcher goes with it
  local n="$1" p pid l; p="$(pl_port_of "$n")"; pid="$(pl_get "$n" pid)"
  if pl_ours "$n"; then
    kill -TERM "$pid" 2>/dev/null || true
    l="$(pl_get "$n" launcher)"; if [ -n "$l" ] && [ "$l" != "$pid" ] && pl_pargs "$l" | grep -q "next"; then kill -TERM "$l" 2>/dev/null || true; fi
    for _ in $(seq 1 30); do [ "$(pl_listener "$p")" = "$pid" ] || break; sleep 0.5; done
    if [ "$(pl_listener "$p")" = "$pid" ]; then echo "portal $n: pid $pid ignored TERM for 15 s, killing it (it is ours)" >&2; kill -KILL "$pid" 2>/dev/null || true; sleep 0.5; fi
  fi
  for e in pid pidstart launcher runfp runbfp rundist runmeta; do pl_del "$n" "$e"; done
}
# start the portal from dist directory $2; healthy => state written; returns 1 when it did not come up
pl_start() {
  local n="$1" dist="$2" p log pid i; p="$(pl_port_of "$n")"; log="$RUNDIR/$PL_PREFIX$n.log"
  [ -z "$(pl_listener "$p")" ] || { echo "ERROR: port $p is not free to start portal $n" >&2; return 1; }
  ( cd "$ROOT/apps/$n"; nohup env ${BUILD_ENV[@]+"${BUILD_ENV[@]}"} ${RUN_ENV[@]+"${RUN_ENV[@]}"} NODE_ENV=production NEXT_DIST_DIR="$dist" npx next start -H "$HOST" -p "$p" > "$log" 2>&1 < /dev/null & echo $! > "$RUNDIR/$PL_PREFIX$n.launcher.tmp" )
  mv "$RUNDIR/$PL_PREFIX$n.launcher.tmp" "$(pl_fpfile "$n" launcher)"
  for i in $(seq 1 60); do pl_healthy "$p" && break; sleep 1; done
  if pl_healthy "$p"; then
    pid="$(pl_listener "$p")"; pl_put "$n" pid "$pid"; pl_put "$n" pidstart "$(pl_pstart "$pid")"; return 0
  fi
  echo "ERROR: portal $n did not answer on port $p; last lines of $log:" >&2; tail -8 "$log" >&2 || true
  pid="$(pl_listener "$p")"; [ -n "$pid" ] && kill -TERM "$pid" 2>/dev/null || true       # the port was free a moment ago, so whatever listens now is what we just started
  return 1
}
pl_record_run() { local n="$1" bfp="$2" dist="$3"; pl_put "$n" runfp "$(pl_runfp "$bfp")"; pl_put "$n" runbfp "$bfp"; pl_put "$n" rundist "$dist"; pl_put "$n" runmeta "$(pl_meta "$n")"; }

# --- commands --------------------------------------------------------------------------------------------------------------------------------------------
pl_refuse_foreign() {             # every port must be free or ours BEFORE anything is built, stopped or started; a foreign listener is named and left alone
  local n p who bad=0
  for n in "${NAMES[@]}"; do
    p="$(pl_port_of "$n")"; who="$(pl_listener "$p")"
    if [ -n "$who" ] && ! pl_ours "$n"; then echo "ERROR: port $p (portal $n, $MODE) is used by pid $who ($(pl_pargs "$who")), which this script did not start. It is left alone: free the port or choose another (the *_PORT variables)." >&2; bad=1; fi
  done
  [ "$bad" = 0 ] || exit 2
}

pl_cmd_build() {
  local force="$1" n failed=0
  for n in "${NAMES[@]}"; do
    if [ "$force" = 0 ] && pl_build_current "$n"; then echo "portal $n: build is current ($(pl_get "$n" fp))"; else pl_build "$n" || failed=1; fi
  done
  return $failed
}

pl_cmd_up() {
  local force="$1" n failed=0 p bfp dist want_run have_run prev prevdist good=0
  mkdir -p "$RUNDIR"; pl_refuse_foreign
  # phase 1 - build whatever is not current. A failure here stops BEFORE any process is touched: the portals that run keep running, from the build they run.
  pl_cmd_build "$force" || { echo "up aborted: nothing was stopped or started (a failed build never replaces a healthy portal)." >&2; exit 1; }
  # phase 2 - swap: keep a process that already runs this exact build with this exact run environment, restart every other one of OURS, start the ones that are down
  for n in "${NAMES[@]}"; do
    p="$(pl_port_of "$n")"; bfp="$(pl_get "$n" fp)"; dist="$(pl_get "$n" dist)"; want_run="$(pl_runfp "$bfp")"
    if pl_ours "$n"; then
      have_run="$(pl_get "$n" runfp)"
      if [ "$have_run" = "$want_run" ] && pl_healthy "$p"; then echo "portal $n [$MODE] 127.0.0.1:$p: already current (pid $(pl_get "$n" pid), build $bfp)"; continue; fi
      prevdist="$(pl_get "$n" rundist)"; prev="$(pl_get "$n" runbfp)"
      echo "portal $n: running build ${prev:-unknown} is stale for $bfp (source or environment changed) - restarting"
      pl_stop "$n"
    else prevdist=""; fi
    if pl_start "$n" "$dist"; then pl_record_run "$n" "$bfp" "$dist"; echo "portal $n [$MODE] http://$HOST:$p/  build $bfp  pid $(pl_get "$n" pid)  commit $(pl_fmtmeta "$(pl_get "$n" runmeta)")"
    else
      failed=1
      if [ -n "$prevdist" ] && [ -f "$ROOT/apps/$n/$prevdist/BUILD_ID" ] && pl_start "$n" "$prevdist"; then pl_record_run "$n" "${prev:-unknown}" "$prevdist"; echo "portal $n: the NEW build did not start; the PREVIOUS build ($prevdist) is serving again (rolled back)" >&2
      else echo "portal $n: the new build did not start and there is no previous build to return to: the portal is DOWN" >&2; fi
    fi
  done
  # phase 3 - drop dist directories nothing refers to any more (the build we serve, the build we last made, never another mode's)
  for n in "${NAMES[@]}"; do
    for d in "$ROOT/apps/$n"/.next-"$MODE"-*; do
      [ -d "$d" ] || continue; d="$(basename "$d")"
      [ "$d" = "$(pl_get "$n" dist)" ] || [ "$d" = "$(pl_get "$n" rundist)" ] || rm -rf "$ROOT/apps/$n/$d"
    done
  done
  # the directory that earlier versions of public-portals.sh built into (`apps/<p>/.next-public`) is orphaned once the portal serves a fingerprint directory; the LOCAL legacy `.next` is not touched
  # (it may belong to `next dev` or to someone else's worktree): delete it by hand once.
  if [ "$MODE" = public ]; then for n in "${NAMES[@]}"; do [ "$(pl_get "$n" rundist)" = ".next-public" ] || [ -z "$(pl_get "$n" rundist)" ] || rm -rf "$ROOT/apps/$n/.next-public"; done; fi
  [ "$failed" = 0 ] || exit 1
}

pl_cmd_down() {
  local n p; for n in "${NAMES[@]}"; do
    p="$(pl_port_of "$n")"
    if pl_ours "$n"; then pl_stop "$n"; [ -z "$(pl_listener "$p")" ] && echo "portal $n stopped" || echo "WARNING: port $p still has a listener after stopping portal $n" >&2
    else
      for e in pid pidstart launcher runfp runbfp rundist runmeta; do pl_del "$n" "$e"; done
      if [ -n "$(pl_listener "$p")" ]; then echo "portal $n: port $p is used by pid $(pl_listener "$p") ($(pl_pargs "$(pl_listener "$p")")), not started by this script: left alone"; else echo "portal $n not running"; fi
    fi
  done
}

pl_cmd_status() {
  local n p pid st bfp rbfp rfp want code rc=0 meta short
  printf '%-9s %-6s %-7s %-8s %-17s %-17s %-17s %-19s %s\n' PORTAL PORT PID OWNER BUILT RUNNING DESIRED COMMIT STATE
  for n in "${NAMES[@]}"; do
    p="$(pl_port_of "$n")"; bfp="$(pl_bfp "$n")"; pid="$(pl_listener "$p")"; code="$(pl_code "$p")"; code="${code:-000}"; rbfp="$(pl_get "$n" runbfp)"; rfp="$(pl_get "$n" runfp)"; want="$(pl_runfp "$bfp")"
    meta="$(pl_get "$n" runmeta)"; short="-"; if [ -n "$meta" ]; then short="$(pl_fmtmeta "$meta")"; fi
    if [ -n "$pid" ] && ! pl_ours "$n"; then st="FOREIGN (pid $pid is not ours: $(pl_pargs "$pid" | cut -c1-40))"; rc=1
    elif [ -z "$pid" ]; then if pl_build_current "$n"; then st="DOWN (build current)"; else st="DOWN (build $([ -n "$(pl_get "$n" fp)" ] && echo stale || echo missing))"; fi; rc=1
    elif [ "$code" = 000 ] || [ "$code" -ge 500 ]; then st="UNHEALTHY (HTTP $code)"; rc=1
    elif [ "$rbfp" != "$bfp" ]; then st="STALE: source/build environment changed (running ${rbfp:-unknown}, source $bfp)"; rc=1
    elif [ "$rfp" != "$want" ]; then st="STALE: run environment changed (restart needed, no rebuild)"; rc=1
    elif ! pl_build_current "$n"; then st="STALE: build missing"; rc=1
    else st="CURRENT (HTTP $code)"; fi
    printf '%-9s %-6s %-7s %-8s %-17s %-17s %-17s %-19s %s\n' "$n" "$p" "${pid:--}" "$(if [ -z "$pid" ]; then echo -; elif pl_ours "$n"; then echo ours; else echo foreign; fi)" "$(pl_get "$n" fp | grep . || echo -)" "${rbfp:--}" "$bfp" "$short" "$st"
  done
  return $rc
}

pl_usage() { echo "usage: $0 up|down|restart|status|build [--force-rebuild]" >&2; exit 64; }
pl_main() {
  local cmd="${1:-}" force=0; shift || true
  for a in "$@"; do case "$a" in --force-rebuild) force=1;; *) pl_usage;; esac; done
  mkdir -p "$RUNDIR"
  case "$cmd" in
    up) pl_cmd_up "$force";;
    down) pl_cmd_down;;
    restart) pl_cmd_down; pl_cmd_up "$force";;
    status) pl_cmd_status;;
    build) pl_cmd_build "$force";;
    *) pl_usage;;
  esac
}
