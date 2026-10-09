#!/usr/bin/env python3
"""parse.py — evidence parsers for the C6 Mac runner (Python 3.8+, stdlib only).
  node-test <log>                 -> tests=N pass=N fail=N skipped=N        (node --test summary: lines 'ℹ tests N' …)
  static-qa <log>                 -> pass=N total=N                          (lines 'PASS|FAIL  SQ-xx')
  junit <results_dir> <out_dir>   -> suites=N tests=N failures=N errors=N skipped=N ; writes backend-failures.txt, backend-skipped.txt
  security <results_dir>          -> one 'SEC|<Class>|tests=…|fail=…|skipped=…|PASS|FAIL|MISSING' line per suite
Exit 0 always for a successful parse; exit 3 if the input is missing/unparsable (caller records NOT_RUN/FAIL)."""
import glob, os, re, sys
import xml.etree.ElementTree as ET

SECURITY_SUITES = [
    "PrivilegeEscalationTests", "TenantAccessTests", "IsolationApiTests", "AppRuntimeApiTests", "DataRuntimeLiveApiTests", "DataSourceScopeTests",
    "AccessAdaptersTests", "TenantAccessLegacyFlagTests", "AdminTransferOwnershipSelfGrantSpec", "WebhookSecurityTests", "PublicAddressTests",
    "AddressRangeSpecTests", "SecurityPrimitivesTests", "IdempotencyTests", "JdbcIdempotencyStoreTests", "WebhookReplayTests", "WebhookTests",
    "SqlGuardTests", "PostgresSessionSecurityTests", "AppRuntimeFlagsOffTests", "JdbcPersistenceTests", "DataRuntimeMigrationTests",
    "TenantFoundationMigrationTests", "AuthSecurityTests", "DataSourceAdminTests", "PermissionCanonicalTests",
]

def node_test(path):
    if not os.path.isfile(path): return 3
    t = open(path, encoding="utf-8", errors="replace").read()
    vals = {}
    for k in ("tests", "pass", "fail", "cancelled", "skipped"):
        m = list(re.finditer(r"^ℹ %s (\d+)\s*$" % k, t, re.M))
        if m: vals[k] = int(m[-1].group(1))
    if "tests" not in vals or "pass" not in vals: return 3
    print("tests=%d pass=%d fail=%d skipped=%d" % (vals["tests"], vals["pass"], vals.get("fail", 0) + vals.get("cancelled", 0), vals.get("skipped", 0)))
    return 0

def static_qa(path):
    if not os.path.isfile(path): return 3
    lines = [l for l in open(path, encoding="utf-8", errors="replace") if re.match(r"^(PASS|FAIL)\s+SQ-\d+", l)]
    if not lines: return 3
    print("pass=%d total=%d" % (sum(1 for l in lines if l.startswith("PASS")), len(lines)))
    return 0

def _suites(d):
    for p in sorted(glob.glob(os.path.join(d, "*.xml"))):
        try: yield p, ET.parse(p).getroot()
        except ET.ParseError: continue

def junit(d, out):
    suites = list(_suites(d))
    if not suites: return 3
    tot = dict(tests=0, failures=0, errors=0, skipped=0); fails = []; skips = []
    for p, r in suites:
        for k in tot: tot[k] += int(r.get(k, 0) or 0)
        for tc in r.iter("testcase"):
            name = "%s :: %s" % (r.get("name", "?"), tc.get("name", "?"))
            for kind in ("failure", "error"):
                for f in tc.findall(kind):
                    msg = (f.get("message") or "")[:300]; body = "\n".join((f.text or "").splitlines()[:15])
                    fails.append("## %s [%s]\n%s\n%s\n" % (name, kind, msg, body))
            if tc.find("skipped") is not None: skips.append(name)
    os.makedirs(out, exist_ok=True)
    open(os.path.join(out, "backend-failures.txt"), "w", encoding="utf-8").write("\n".join(fails) if fails else "(none)\n")
    open(os.path.join(out, "backend-skipped.txt"), "w", encoding="utf-8").write("\n".join(skips) + "\n" if skips else "(none)\n")
    print("suites=%d tests=%d failures=%d errors=%d skipped=%d" % (len(suites), tot["tests"], tot["failures"], tot["errors"], tot["skipped"]))
    return 0

def security(d):
    found = {}
    for p, r in _suites(d):
        n = (r.get("name") or "").split(".")[-1]
        found[n] = (int(r.get("tests", 0) or 0), int(r.get("failures", 0) or 0) + int(r.get("errors", 0) or 0), int(r.get("skipped", 0) or 0))
    if not found: return 3
    for n in SECURITY_SUITES:
        if n not in found: print("SEC|%s|tests=0|fail=0|skipped=0|MISSING" % n); continue
        t, f, s = found[n]
        st = "FAIL" if f > 0 else ("MISSING" if t == 0 else "PASS")
        print("SEC|%s|tests=%d|fail=%d|skipped=%d|%s" % (n, t, f, s, st))
    return 0

def main(a):
    try:
        if a[0] == "node-test": return node_test(a[1])
        if a[0] == "static-qa": return static_qa(a[1])
        if a[0] == "junit": return junit(a[1], a[2])
        if a[0] == "security": return security(a[1])
    except (IndexError, OSError):
        pass
    sys.stderr.write(__doc__); return 2

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
