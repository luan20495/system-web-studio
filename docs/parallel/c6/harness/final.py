#!/usr/bin/env python3
"""final.py <RESULTS.txt> <full|gate> — decide the FINAL line. Rules (C6 QA rule):
  any FAIL on a phase (except PREREQ)            -> FAIL
  else PREREQ FAIL, or a critical key missing / NOT_RUN -> INCOMPLETE
  else                                           -> PASS     (GAP lines are listed, never hidden; PASS != production ready)
Result lines are  KEY|STATUS|field|field…  — header lines (HEAD, BRANCH, START…) have no status and are ignored."""
import re, sys

GATE = ["HEAD", "PREREQ", "WORKTREE_SCOPE", "BACKEND_COMPILE", "BACKEND_TEST", "SECURITY_SUITES", "FRONTEND_TYPECHECK", "FRONTEND_UNIT", "FRONTEND_BUILD", "STATIC_QA"]
FULL = GATE + ["SMOKE", "STACK_UP", "E2E_01", "E2E_02", "E2E_03", "E2E_04", "E2E_05", "E2E_06", "BROWSER", "RECOVERY"]
OPTIONAL = ["E2E_07", "E2E_08", "SECRET_SCAN"]   # need extra infrastructure (Keycloak / Cloudflare) or an optional tool
STATUSES = {"PASS", "FAIL", "NOT_RUN", "WARN", "INFO", "GAP"}

def load(path):
    rows = {}; order = []
    for line in open(path, encoding="utf-8", errors="replace"):
        p = line.rstrip("\n").split("|")
        if len(p) < 2: continue
        if p[0] == "HEAD": rows["HEAD"] = ("PASS" if re.match(r"^[0-9a-f]{7,40}$", p[1]) else "NOT_RUN", p[1:]); continue
        if p[1] not in STATUSES: continue
        rows.setdefault(p[0], (p[1], p[2:])); order.append(p[0])
        if p[1] == "FAIL": rows[p[0]] = ("FAIL", p[2:])      # a FAIL anywhere under a key wins
    return rows, order

def decide(path, scope):
    rows, order = load(path)
    crit = FULL if scope == "full" else GATE
    fails = sorted(k for k, (s, _) in rows.items() if s == "FAIL" and k != "PREREQ")
    missing = sorted(k for k in crit if k not in rows or rows[k][0] == "NOT_RUN")
    prereq_fail = rows.get("PREREQ", ("", []))[0] == "FAIL"
    gaps = sorted(k for k, (s, _) in rows.items() if s == "GAP")
    opt = sorted(k for k in OPTIONAL if k not in rows or rows[k][0] == "NOT_RUN")
    if fails: verdict = "FAIL"
    elif prereq_fail or missing: verdict = "INCOMPLETE"
    else: verdict = "PASS"
    return "FINAL|%s|scope=%s|failed=%s|critical_not_run=%s|optional_not_run=%s|gaps=%d" % (verdict, scope, ",".join(fails) or "-", ",".join(missing) or "-", ",".join(opt) or "-", len(gaps))

if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[2] not in ("full", "gate"):
        sys.stderr.write(__doc__); sys.exit(2)
    print(decide(sys.argv[1], sys.argv[2]))
