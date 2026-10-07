#!/usr/bin/env bash
# Optional: start the public stack at login and after a reboot (macOS LaunchAgent). NOT installed by anything; run it yourself.
#   ./scripts/public-launchd.sh install|uninstall|status|print
# The agent only runs scripts/public-up.sh (idempotent: starts what is not running, never a second cloudflared, never touches the gemma tunnel). KeepAlive is OFF on purpose:
# once up, the watchdog (scripts/watchdog.sh public) and Docker's restart policy keep the processes alive; the agent covers "the Mac was restarted / I logged in again".
# Day-to-day: start = ./scripts/public-up.sh, status = ./scripts/public-status.sh, logs = .run/public/{tunnel,api,portal-*}.log, stop = ./scripts/public-down.sh.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LABEL="uk.toolsmcp.hbl.public"; PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"; DOMAIN="gui/$(id -u)"
plist() { cat <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>/bin/bash</string><string>-lc</string><string>cd "$ROOT" &amp;&amp; exec ./scripts/public-up.sh</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><false/>
  <key>StandardOutPath</key><string>$ROOT/.run/public/launchd.log</string>
  <key>StandardErrorPath</key><string>$ROOT/.run/public/launchd.log</string>
</dict></plist>
PL
}
case "${1:-status}" in
  print) plist ;;
  install) mkdir -p "$HOME/Library/LaunchAgents" "$ROOT/.run/public"; plist > "$PLIST"; plutil -lint "$PLIST" >/dev/null; launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true; launchctl bootstrap "$DOMAIN" "$PLIST"; echo "installed $PLIST" ;;
  uninstall) launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true; rm -f "$PLIST"; echo "removed $LABEL" ;;
  status) [ -f "$PLIST" ] && echo "plist present: $PLIST" || echo "not installed"; launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1 && echo "loaded" || echo "not loaded" ;;
  *) echo "usage: $0 install|uninstall|status|print" >&2; exit 2 ;;
esac
