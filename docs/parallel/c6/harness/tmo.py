#!/usr/bin/env python3
"""tmo.py <seconds> <cmd...>  — run a command with a wall-clock timeout (macOS has no `timeout`). Kills the whole process group. Exit 124 on timeout."""
import os, signal, subprocess, sys

def main():
    if len(sys.argv) < 3:
        sys.stderr.write("usage: tmo.py <seconds> <cmd...>\n"); return 2
    secs = float(sys.argv[1]); cmd = sys.argv[2:]
    try:
        p = subprocess.Popen(cmd, start_new_session=True)
    except OSError as e:
        sys.stderr.write("tmo.py: cannot start %r: %s\n" % (cmd[0], e)); return 127
    try:
        return p.wait(timeout=secs)
    except subprocess.TimeoutExpired:
        sys.stderr.write("tmo.py: TIMEOUT after %ss: %s\n" % (sys.argv[1], " ".join(cmd)))
        for sig in (signal.SIGTERM, signal.SIGKILL):
            try: os.killpg(p.pid, sig)
            except ProcessLookupError: break
            try: p.wait(timeout=10); break
            except subprocess.TimeoutExpired: continue
        return 124
    except KeyboardInterrupt:
        try: os.killpg(p.pid, signal.SIGTERM)
        except ProcessLookupError: pass
        return 130

if __name__ == "__main__":
    sys.exit(main())
