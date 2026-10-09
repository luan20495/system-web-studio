#!/usr/bin/env python3
"""C6 static contract checks — verify requirements that are IMPLEMENTED in the product but had no test. Read-only; never edits production code.

  python3 docs/parallel/c6/harness/contract-checks.py --sha 8e91172 --prev f894cc6 --out docs/parallel/c6/evidence/mac-8e91172-contract-checks

Checks (id -> QA rows / requirements):
  CC-TP07-*  AccessContext/TenantContext defaults never used by production or test code   (C1-TEN-23, REQ-TP-07)
  CC-ML07-*  audit_events is INSERT-only in application code; the V3 triggers are never dropped/disabled   (C1-AUD-01, REQ-ML-07, REQ-CL-06)
  CC-WIR18-* no browser-callable data route (/api/v1/data/**, *mutate*) is mapped by any controller   (C0-WIR-18, REQ-RA-01, REQ-RA-17, REQ-MA-08)
  CC-ARCH-*  package layering of integration-contract.md section 1   (C0-ARCH-07, REQ-IC-01, REQ-AW-06)
  CC-MIG-*   applied migrations are immutable: V1..Vn-1 identical between prev and sha, plus a pinned hash manifest   (C0-MIG-25, REQ-CL-10)
Output: <out>/contract-checks.tsv (id<TAB>status<TAB>detail) — status PASS | FAIL | INFO. Exit 0 = no FAIL.
"""
import argparse, hashlib, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".."))
MAIN = "backend/src/main/kotlin/com/systemwebstudio"
TEST = "backend/src/test/kotlin/com/systemwebstudio"
RES = "backend/src/main/resources"
OUT = []


def rec(i, st, detail):
    OUT.append((i, st, detail))
    print(f"{st:5} {i}  {detail}")


def files(base, ext=(".kt",)):
    for d, _, fs in os.walk(os.path.join(ROOT, base)):
        for f in fs:
            if f.endswith(ext):
                yield os.path.join(d, f)


def rel(p):
    return os.path.relpath(p, ROOT)


def strip_comments(s):
    s = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), s, flags=re.S)
    return re.sub(r"//[^\n]*", "", s)


def call_args(src, start):
    """return the text between the matching parentheses of the call whose '(' is at src[start]"""
    depth, i, in_str = 0, start, None
    while i < len(src):
        c = src[i]
        if in_str:
            if c == "\\":
                i += 1
            elif c == in_str:
                in_str = None
        elif c in "\"'":
            in_str = c
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return src[start + 1:i]
        i += 1
    return src[start + 1:]


def top_level_args(text):
    args, depth, cur, in_str = [], 0, "", None
    for c in text:
        if in_str:
            cur += c
            if c == in_str:
                in_str = None
            continue
        if c in "\"'":
            in_str = c
            cur += c
        elif c in "([{<" and c != "<":
            depth += 1
            cur += c
        elif c in ")]}":
            depth -= 1
            cur += c
        elif c == "," and depth == 0:
            args.append(cur.strip())
            cur = ""
        else:
            cur += c
    if cur.strip():
        args.append(cur.strip())
    return args


# ------------------------------------------------------------------ TP-07: AccessContext defaults
def check_tp07():
    sites, bad = [], []
    for base in (MAIN, TEST):
        for p in files(base):
            src = strip_comments(open(p, encoding="utf-8").read())
            for m in re.finditer(r"(?<![A-Za-z0-9_.])AccessContext\(", src):
                before = src[max(0, m.start() - 20):m.start()]
                if re.search(r"class\s+$", before):      # the declaration itself
                    continue
                args = top_level_args(call_args(src, m.end() - 1))
                named = {a.split("=")[0].strip() for a in args if re.match(r"^[A-Za-z_]\w*\s*=", a)}
                explicit = len(args) >= 8 or {"tenantId", "tenantContext"} <= named
                sites.append((rel(p), len(args), explicit))
                if not explicit:
                    bad.append(f"{rel(p)} passes only {len(args)} args (tenantId/tenantContext default to the DEFAULT tenant)")
    rec("CC-TP07-1", "FAIL" if bad else "PASS", f"{len(sites)} AccessContext construction site(s), all pass tenantId+tenantContext explicitly" if not bad else "; ".join(bad))
    allowed = {"backend/src/main/kotlin/com/systemwebstudio/access/AccessService.kt", "backend/src/main/kotlin/com/systemwebstudio/access/MeTenancy.kt",
               "backend/src/main/kotlin/com/systemwebstudio/tenancy/TenantContext.kt", "backend/src/main/kotlin/com/systemwebstudio/tenancy/TenantService.kt"}
    extra = []
    for p in files(MAIN):
        src = strip_comments(open(p, encoding="utf-8").read())
        if re.search(r"TenantContext\.DEFAULT|TenantIds\.DEFAULT", src) and rel(p) not in allowed:
            extra.append(rel(p))
    rec("CC-TP07-2", "FAIL" if extra else "PASS", "DEFAULT tenant referenced outside the 4 audited files: " + ", ".join(extra) if extra else "TenantContext.DEFAULT / TenantIds.DEFAULT appear only in AccessService (parameter defaults), MeTenancy (ORDER BY), TenantContext, TenantService (guard)")
    # the runtime AccessContext of the two production sites derives the tenant from the resolver, not from a literal
    svc = strip_comments(open(os.path.join(ROOT, MAIN, "access/AccessService.kt"), encoding="utf-8").read())
    ok = "tenantResolver" in svc and "TenantContext(tenant.tenantId" in svc and "ws.tenantId, ws.tenantContext" in svc
    rec("CC-TP07-3", "PASS" if ok else "FAIL", "forWorkspace builds the context from tenantResolver; forProject copies it from the workspace context" if ok else "AccessService no longer derives the tenant from the resolver")
    rec("CC-TP07-4", "INFO", "AccessContext still DECLARES defaulted tenantId/tenantContext (accepted fail-open for legacy callers, contract section 3); this check is the guard that no caller uses them")


# ------------------------------------------------------------------ ML-07: audit append-only (application layer + trigger lifecycle)
def check_ml07():
    bad = []
    pat = re.compile(r"(UPDATE\s+audit_events|DELETE\s+FROM\s+audit_events|TRUNCATE\s+(TABLE\s+)?audit_events|DROP\s+TABLE\s+audit_events|ALTER\s+TABLE\s+audit_events)", re.I)
    for base, ext in ((MAIN, (".kt",)), (RES, (".yml", ".yaml", ".sql", ".properties"))):
        for p in files(base, ext):
            r = rel(p)
            src = open(p, encoding="utf-8").read()
            src = strip_comments(src) if p.endswith(".kt") else re.sub(r"--[^\n]*", "", src)
            for m in pat.finditer(src):
                bad.append(f"{r}: {m.group(0)}")
    rec("CC-ML07-1", "FAIL" if bad else "PASS", "; ".join(bad) if bad else "no application code, config or migration issues UPDATE/DELETE/TRUNCATE/DROP/ALTER on audit_events")
    ins = []
    for p in files(MAIN):
        src = strip_comments(open(p, encoding="utf-8").read())
        if re.search(r"INSERT\s+INTO\s+audit_events", src, re.I):
            ins.append(rel(p))
    rec("CC-ML07-2", "PASS" if ins == [os.path.join(MAIN, "audit/AuditService.kt")] else "FAIL", "the only writer of audit_events is " + ", ".join(ins))
    v3 = open(os.path.join(ROOT, RES, "db/migration/V3__rbac_audit_project_settings.sql"), encoding="utf-8").read()
    has = all(x in v3 for x in ("BEFORE UPDATE OR DELETE ON audit_events", "FOR EACH ROW", "BEFORE TRUNCATE ON audit_events", "audit_events is append-only"))
    rec("CC-ML07-3", "PASS" if has else "FAIL", "V3 defines the row trigger (UPDATE/DELETE) and the statement trigger (TRUNCATE) that raise 'audit_events is append-only'" if has else "V3 trigger definition changed")
    later = []
    for p in files(os.path.join(RES, "db/migration"), (".sql",)):
        if os.path.basename(p).startswith("V3__"):
            continue
        s = re.sub(r"--[^\n]*", "", open(p, encoding="utf-8").read())
        if re.search(r"(DROP|DISABLE)\s+TRIGGER[^;]*audit_events|audit_events_(no_update_delete|no_truncate|immutable)", s, re.I):
            later.append(rel(p))
    rec("CC-ML07-4", "FAIL" if later else "PASS", ("later migration touches the audit triggers: " + ", ".join(later)) if later else "no later migration drops, disables or redefines the audit triggers")
    rec("CC-ML07-5", "INFO", "DB-layer proof is in db-probes.py (UPDATE/DELETE/TRUNCATE refused for owner and non-owner roles). Residual: the table owner can DISABLE the trigger (privilege hardening = ledger item 6 / T19, not implemented)")


# ------------------------------------------------------------------ WIR-18: no browser-callable data route
def check_wir18():
    mapping = re.compile(r"@(Request|Get|Post|Put|Delete|Patch)Mapping\s*\(([^)]*)\)", re.S)
    hits, mutate = [], []
    for p in files(MAIN):
        src = strip_comments(open(p, encoding="utf-8").read())
        for m in mapping.finditer(src):
            arg = m.group(2)
            if "/api/v1/data" in arg or "DataApi.BASE" in arg or "DataRoutes" in arg:
                hits.append(f"{rel(p)}: {m.group(0)[:70]}")
            if re.search(r"mutat", arg, re.I):
                mutate.append(f"{rel(p)}: {m.group(0)[:70]}")
        # class-level base path constants used by a controller
        if re.search(r"@(RestController|Controller)", src) and re.search(r"DataApi\.BASE|DataRoutes", src) and "/data/api/" not in rel(p).replace("\\", "/"):
            hits.append(f"{rel(p)}: controller references DataApi.BASE/DataRoutes")
    rec("CC-WIR18-1", "FAIL" if hits else "PASS", "; ".join(hits) if hits else "no controller maps /api/v1/data/** (the C3 route table DataApi/DataRoutes is not mounted)")
    rec("CC-WIR18-2", "FAIL" if mutate else "PASS", "; ".join(mutate) if mutate else "no request mapping in main code contains 'mutate' (the only write path is app-runtime/actions/{id}/execute)")
    app_rt = [rel(p) for p in files(MAIN) if "RequestMapping(\"/api/v1/workspaces/{workspaceId}/projects/{projectId}/app-runtime\")" in open(p, encoding="utf-8").read()]
    rec("CC-WIR18-3", "PASS" if len(app_rt) == 2 else "FAIL", f"the app-runtime family is mounted by exactly {len(app_rt)} controller(s): " + ", ".join(os.path.basename(x) for x in app_rt))


# ------------------------------------------------------------------ ARCH: layering (integration-contract.md section 1)
def check_arch():
    rules = [("logic", ("access", "tenancy", "data", "app", "wiring"), "logic.* imports base only (ports); never access/tenancy/data/app"),
             ("data", ("logic", "app", "wiring"), "data.* imports base + tenancy types; never logic.* / app.*"),
             ("tenancy", ("logic", "data", "app", "wiring"), "tenancy imports no V2 agent package"),
             ("access", ("logic", "data", "app", "wiring"), "access imports no V2 agent package"),
             ("app/definition", ("wiring", "access"), "app.definition imports no wiring/access")]
    # app.definition may import data.{query,mapping,...} shape/parser classes but NEVER connectors, gateway, datasource services
    app_forbidden = ("data.datasource", "data.gateway", "data.cache", "data.sync", "data.api")
    total = 0
    for (pkg, forbidden, text) in rules:
        viol = []
        for p in files(os.path.join(MAIN, pkg)):
            for ln in open(p, encoding="utf-8"):
                m = re.match(r"^import\s+com\.systemwebstudio\.([a-z]+)(\.[A-Za-z0-9_.]+)?", ln)
                if not m:
                    continue
                top, full = m.group(1), "com.systemwebstudio." + m.group(1) + (m.group(2) or "")
                total += 1
                if top in forbidden and not (top == "data" and pkg == "tenancy"):
                    viol.append(f"{rel(p)} imports {full}")
                if pkg == "app/definition" and any(full.startswith("com.systemwebstudio." + f) for f in app_forbidden):
                    viol.append(f"{rel(p)} imports {full}")
        rec(f"CC-ARCH-{pkg.replace('/', '_')}", "FAIL" if viol else "PASS", ("; ".join(viol[:6]) + (f" … (+{len(viol) - 6})" if len(viol) > 6 else "")) if viol else text)
    rec("CC-ARCH-scope", "INFO", f"{total} project imports scanned; legacy modules (identity, project, member, publish, …) are not restricted by the contract and are not checked")


# ------------------------------------------------------------------ MIG: immutability
def git(*a):
    return subprocess.run(["git", "-C", ROOT, *a], capture_output=True, text=True)


def check_mig(prev, sha):
    mdir = "backend/src/main/resources/db/migration"
    tree = {f: hashlib.sha256(open(os.path.join(ROOT, mdir, f), "rb").read()).hexdigest() for f in os.listdir(os.path.join(ROOT, mdir)) if f.endswith(".sql")}
    vkey = lambda f: int(re.match(r"V(\d+)__", f).group(1))
    prevlist = git("ls-tree", "--name-only", f"{prev}:{mdir}/")
    changed, added = [], []
    if prevlist.returncode == 0:
        pf = {f for f in prevlist.stdout.split() if f.endswith(".sql")}
        for f in sorted(pf, key=vkey):
            b = git("show", f"{prev}:{mdir}/{f}")
            h = hashlib.sha256(b.stdout.encode("utf-8")).hexdigest() if b.returncode == 0 else None
            if f not in tree or tree[f] != h:
                changed.append(f)
        added = sorted(set(tree) - pf, key=vkey)
        rec("CC-MIG-1", "FAIL" if changed else "PASS", (f"already-applied migration edited or removed between {prev} and {sha}: " + ", ".join(changed)) if changed else f"V{vkey(sorted(pf, key=vkey)[0])}..V{vkey(sorted(pf, key=vkey)[-1])} ({len(pf)} files) are byte-identical between {prev} and {sha}; added: {', '.join(added) or 'none'}")
    else:
        rec("CC-MIG-1", "INFO", f"cannot read migrations of {prev}")
    manifest = os.path.join(HERE, "..", "migration-hashes.sha256")
    lines = sorted(f"{h}  {f}" for f, h in tree.items())
    if not os.path.exists(manifest):
        open(manifest, "w").write(f"# C6 pinned sha256 of backend/src/main/resources/db/migration/* at {sha} (applied migrations are immutable; a new SHA may only ADD files)\n" + "\n".join(lines) + "\n")
        rec("CC-MIG-2", "INFO", f"manifest created with {len(lines)} files at {sha}")
    else:
        pinned = dict((ln.split("  ", 1)[1], ln.split("  ", 1)[0]) for ln in open(manifest).read().splitlines() if ln and not ln.startswith("#"))
        diff = [f for f, h in pinned.items() if tree.get(f) != h]
        rec("CC-MIG-2", "FAIL" if diff else "PASS", ("pinned migration changed or missing: " + ", ".join(diff)) if diff else f"all {len(pinned)} pinned migrations still match (new files since pin: {', '.join(sorted(set(tree) - set(pinned), key=vkey)) or 'none'})")
    nums = sorted(vkey(f) for f in tree)
    rec("CC-MIG-3", "PASS" if nums == list(range(1, nums[-1] + 1)) else "FAIL", f"versions contiguous V1..V{nums[-1]}")


def selftest():
    """prove the checks have teeth: inject one violation per check into a throw-away tree and require FAIL"""
    import tempfile, shutil
    global ROOT, OUT
    tmp = tempfile.mkdtemp(prefix="c6-cc-selftest-")
    real_root, ROOT = ROOT, tmp
    try:
        def w(path, text):
            fp = os.path.join(tmp, path); os.makedirs(os.path.dirname(fp), exist_ok=True); open(fp, "w").write(text)
        w(MAIN + "/access/AccessService.kt", "class AccessContext(val a: Int)\nfun f() = AccessContext(user, ws, null, null, perms, null)\n")      # 6 args, no tenant, no resolver
        w(MAIN + "/foo/Leak.kt", "val t = TenantContext.DEFAULT\nfun g(jdbc: J) { jdbc.update(\"DELETE FROM audit_events WHERE 1=1\") }\n")
        w(MAIN + "/audit/AuditService.kt", "fun r(j: J) { j.update(\"INSERT INTO audit_events (id) VALUES (1)\") }\n")
        w(MAIN + "/foo/Ctl.kt", "@RestController\nclass C { @PostMapping(\"/api/v1/data/mutate\") fun m() {} }\n")
        w(MAIN + "/logic/Bad.kt", "package x\nimport com.systemwebstudio.data.gateway.DataGateway\n")
        w(MAIN + "/app/definition/Bad.kt", "package x\nimport com.systemwebstudio.data.datasource.Connector\n")
        w(RES + "/db/migration/V3__rbac_audit_project_settings.sql", "-- no triggers here\n")
        w(RES + "/db/migration/V9__x.sql", "ALTER TABLE audit_events DISABLE TRIGGER audit_events_no_update_delete;\n")
        OUT = []
        check_tp07(); check_ml07(); check_wir18(); check_arch()
        res = {i: st for i, st, _ in OUT}
        expect_fail = ["CC-TP07-1", "CC-TP07-2", "CC-TP07-3", "CC-ML07-1", "CC-ML07-3", "CC-ML07-4", "CC-WIR18-1", "CC-WIR18-2", "CC-ARCH-logic", "CC-ARCH-app_definition"]
        missed = [i for i in expect_fail if res.get(i) != "FAIL"]
        print("SELFTEST", "FAILED: checks that did not catch the injected violation: " + ", ".join(missed) if missed else f"passed ({len(expect_fail)} injected violations all detected)")
        return not missed
    finally:
        ROOT = real_root; shutil.rmtree(tmp, ignore_errors=True); OUT = []


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--selftest":
        sys.exit(0 if selftest() else 1)
    ap = argparse.ArgumentParser()
    ap.add_argument("--sha", required=True)
    ap.add_argument("--prev", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    head = git("rev-parse", "--short", "HEAD").stdout.strip()
    rec("CC-HEAD", "PASS" if head == a.sha[:len(head)] or a.sha.startswith(head) else "FAIL", f"worktree HEAD {head}, expected {a.sha}")
    check_tp07(); check_ml07(); check_wir18(); check_arch(); check_mig(a.prev, a.sha)
    out = os.path.join(ROOT, a.out) if not os.path.isabs(a.out) else a.out
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "contract-checks.tsv"), "w", encoding="utf-8") as fh:
        fh.write("id\tstatus\tdetail\n")
        for i, st, d in OUT:
            fh.write(f"{i}\t{st}\t{d}\n")
    n = sum(1 for _, s, _ in OUT if s == "FAIL")
    print(f"\ncontract-checks: {sum(1 for _, s, _ in OUT if s == 'PASS')} PASS · {n} FAIL · {sum(1 for _, s, _ in OUT if s == 'INFO')} INFO -> {os.path.join(a.out, 'contract-checks.tsv')}")
    sys.exit(1 if n else 0)


if __name__ == "__main__":
    main()
