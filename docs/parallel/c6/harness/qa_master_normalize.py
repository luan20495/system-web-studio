#!/usr/bin/env python3
"""C6 — normalise QA_MASTER into one flat, machine-readable table (C6 only; never touches production code, never rewrites history).

  python3 -B docs/parallel/c6/harness/qa_master_normalize.py            # writes QA_MASTER_NORMALIZED.tsv + QA_MASTER_NORMALIZED.md
  python3 -B docs/parallel/c6/harness/qa_master_normalize.py --check    # validates the committed TSV (statuses, fields, 482 ids), exit 1 on a violation

Inputs (read-only): qa_master.py + qa_master_reqs.py (the 482 original CASE_IDs), qa_master_state.json (per-row history, append-only),
USER_GUIDE_QA.tsv (batch 8), evidence/rc-62ce9697cd56/rc-*.json (batch 9), evidence/ui-ux-regression/*/bugs-final.tsv + the two UI reports (batches 10, 11).
Rules: the 482 ids and their last historical status are copied as they are (a PASS stays PASS); NOTRUN is spelled NOT_RUN; statuses are limited to
PASS FAIL BLOCKED RETEST NOT_RUN GAP. A result is STALE (RETEST_REQUIRED=YES) when the build it was tested on is not the build that is current for its
plane (backend / frontend). A field whose source cannot be shown is PROVENANCE_UNKNOWN — never guessed.
"""
import csv, collections, glob, importlib.util, json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__)); C6 = os.path.normpath(os.path.join(HERE, "..")); EV = "docs/parallel/c6/evidence"
UNK = "PROVENANCE_UNKNOWN"
FIELDS = ["CASE_ID", "DOMAIN", "SEVERITY", "STATUS", "EVIDENCE_CLASS", "LAST_TESTED_SHA", "LAST_TESTED_DATE", "AUTOMATION", "OWNER", "BLOCKER", "STACK_CLASS",
          "RETEST_REQUIRED", "EVIDENCE_PATH", "BACKEND_SHA", "FRONTEND_SHA", "INTEGRATION_SHA", "ENVIRONMENT", "PROVENANCE", "BATCH"]
STATUSES = ("PASS", "FAIL", "BLOCKED", "RETEST", "NOT_RUN", "GAP")

# the builds that exist (full SHAs are what the evidence names; short ones are only how the old master spelled them)
S8E = "8e91172ffc31db2c8b2eaf2eb75ba07cb8701bec"; SF8 = "f894cc6"; RC = "62ce9697cd56e8dc86b3260923f5addbcf555743"
C5A = "5cc230e491a6d8b0135cbd7eb132793368a45073"; C5B = "40ee45bc16fe4dda525c90e0d491d11284a23d8b"
# what is current for each plane when this table was written: backend of integration/v2 == RC 62ce9697cd56 (git diff 62ce..integration/v2 -- backend is empty);
# frontend of integration/v2 is the C5 final import (9f858c2ee5c1, after 40ee45b) — no UI result below was produced on it.
CUR_BACKEND = RC; CUR_FRONTEND = "9f858c2ee5c1"


def load_master_rows():
    sys.path.insert(0, HERE); sys.argv = ["x"]
    spec = importlib.util.spec_from_file_location("qa_master", os.path.join(HERE, "qa_master.py")); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    import qa_master_reqs as RQ
    RQ.register(m.row, m.J, m.GAP, m.BLK, m.STATIC)
    return m.ROWS


def stat(s):
    s = {"NOTRUN": "NOT_RUN"}.get(s, s)
    if s not in STATUSES: raise SystemExit(f"status {s!r} is not one of {STATUSES}")
    return s


def domain_of(area):
    d = re.sub(r"\s+", "_", area.split(" ")[0] + ("_" + area.split("/")[0].split(" ", 1)[1] if " " in area.split("/")[0] else "")).upper() if area else UNK
    return d.replace("/", "_")


def old_rows(master_rows, state):
    out = []; blk = re.compile(r"(H-C\d-\d+|BLK-C6-\d+|BUG-C6-\d+|F-\d+)")
    for r in master_rows:
        h = state["rows"].get(r["id"])
        if not h: raise SystemExit("row without history: " + r["id"])
        sha, st = h[-1]; st = stat(st); src = r["src"]; kind = src[0] if isinstance(src, tuple) else "unknown"; note = src[1] if isinstance(src, tuple) and len(src) > 1 and isinstance(src[1], str) else ""
        blockers = sorted(set(blk.findall(" ".join([r["bug"] if r["bug"] != "-" else "", note, r.get("pre") or ""]))))
        if st == "BLOCKED" and not blockers: blockers = ["BLK-C6-05"] if "Stack up" in (r.get("pre") or "") else []
        automation = {"junit": "JUNIT", "static": "STATIC_GREP", "fe": "NODE_TEST", "res": "HARNESS_STEP", "selftest": "HARNESS_SELFTEST", "gap": "NONE", "blocked": "NONE", "notrun": "NONE"}.get(kind, "MANUAL_OR_DERIVED")
        evcls = {"junit": "JUNIT_XML", "static": "STATIC_LOG", "fe": "NODE_TEST_LOG", "res": "HARNESS_RESULTS", "selftest": "HARNESS_RESULTS"}.get(kind, "NONE" if st in ("GAP", "NOT_RUN", "BLOCKED") else "DERIVED")
        pre = r.get("pre") or ""
        stack = "TESTCONTAINERS" if "Testcontainers" in pre else "FULL_STACK_NOT_AVAILABLE" if "Stack up" in pre else "NONE"
        path = {"junit": f"{EV}/mac-8e91172-gate/backend-testcases.tsv", "static": f"{EV}/mac-8e91172-nostack/STATIC_QA.log", "fe": f"{EV}/mac-8e91172-nostack/RESULTS.txt", "res": f"{EV}/mac-8e91172-nostack/RESULTS.txt", "selftest": f"{EV}/mac-8e91172-nostack/RESULTS.txt"}.get(kind, "-")
        full = S8E if sha == "8e91172" else sha
        stale = "YES"   # every old row was last evaluated on 8e91172; the backend changed 163 files since (see the staleness rule in the .md)
        out.append(dict(CASE_ID=r["id"], DOMAIN=domain_of(r["area"]), SEVERITY=r["pr"], STATUS=st, EVIDENCE_CLASS=evcls, LAST_TESTED_SHA=full, LAST_TESTED_DATE="2026-10-06" if sha in ("8e91172", "f894cc6") else UNK,
                        AUTOMATION=automation, OWNER=r["owner"], BLOCKER=",".join(blockers) or "-", STACK_CLASS=stack, RETEST_REQUIRED=stale, EVIDENCE_PATH=path,
                        BACKEND_SHA=full, FRONTEND_SHA=full if kind in ("fe", "static") else "NA", INTEGRATION_SHA=full,
                        ENVIRONMENT="macOS arm64, JDK21, Docker Testcontainers (Gradle unit/integration tests; no full stack)", PROVENANCE=f"qa_master_state.json[{r['id']}]@{sha};row={kind}" if kind != "unknown" else UNK, BATCH="1-7"))
    return out


def ug_rows():
    out = []; rd = list(csv.DictReader(open(os.path.join(C6, "USER_GUIDE_QA.tsv"), encoding="utf-8"), delimiter="\t"))
    for x in rd:
        st = stat(x["C6 RESULT"].strip()); ev = x["Evidence"].strip()
        bl = "H-C1-04" if st == "BLOCKED" and "H-C1-04" in (x["Finding"] + x["Actual result"]) else "-"
        out.append(dict(CASE_ID=x["Step ID"], DOMAIN="USER_GUIDE_" + x["Section"].replace("§", "S"), SEVERITY="UNRATED", STATUS=st, EVIDENCE_CLASS="BROWSER_API_RUN", LAST_TESTED_SHA=UNK, LAST_TESTED_DATE="2026-10-07",
                        AUTOMATION="PLAYWRIGHT_SCRIPT(ug-run.mjs)", OWNER=x["Owner"].strip() or "-", BLOCKER=bl, STACK_CLASS="PUBLIC_DEPLOY(studio.toolsmcp.uk)", RETEST_REQUIRED="YES",
                        EVIDENCE_PATH=f"{EV}/user-guide-20261007/ug-run.json" + (f"#{x['Step ID']}" if ev else ""), BACKEND_SHA=UNK, FRONTEND_SHA=UNK, INTEGRATION_SHA="1a9995c (claimed; the public build could not be proven to equal it)",
                        ENVIRONMENT="public deploy, Chrome 154 (Playwright), demo accounts", PROVENANCE=UNK, BATCH="8"))
    return out


def rc_rows():
    out = []; E = "evidence/rc-62ce9697cd56/"
    spec = [("rc-sec", "RC-SEC", "AUTH_SECURITY", "ISOLATED_RC_STACK_API", "API_SCRIPT(rc-sec.mjs)", "NO"), ("rc-data", "RC-DATA", "DATA_ACTION_WORKFLOW", "ISOLATED_RC_STACK_API", "API_SCRIPT(rc-data.mjs)", "NO"),
            ("rc-portals", "RC-PORTALS", "PORTAL_ROUTING", "ISOLATED_RC_STACK_BROWSER", "PLAYWRIGHT_SCRIPT(rc-portals.mjs)", "YES"), ("rc-rabbit", "RC-RABBIT", "WORKFLOW_QUEUE", "ISOLATED_RC_STACK_API", "API_SCRIPT(rc-rabbit.mjs)", "NO"),
            ("rc-gateway-up", "RC-GW-UP", "GATEWAY", "ISOLATED_RC_STACK_API", "API_SCRIPT(rc-gateway.mjs)", "NO"), ("rc-gateway-down", "RC-GW-DOWN", "GATEWAY", "ISOLATED_RC_STACK_API", "API_SCRIPT(rc-gateway.mjs)", "NO"),
            ("rc-public-net", "RC-PUBLIC", "PUBLIC_HOSTS", "PUBLIC_DEPLOY_HTTPS", "API_SCRIPT(rc-public-net.mjs)", "YES")]
    for f, pre, dom, stack, auto, frontend_stale in spec:
        for x in json.load(open(os.path.join(C6, E + f + ".json"), encoding="utf-8"))["rows"]:
            res = x["result"]; st = "BLOCKED" if res.startswith("BLOCKED") else stat(res)
            out.append(dict(CASE_ID=f"{pre}-{x['id']}", DOMAIN=dom, SEVERITY="UNRATED", STATUS=st, EVIDENCE_CLASS="JSON_ROWS", LAST_TESTED_SHA=RC, LAST_TESTED_DATE="2026-10-08", AUTOMATION=auto, OWNER="-",
                            BLOCKER="H-C1-04" if st == "BLOCKED" else "-", STACK_CLASS=stack, RETEST_REQUIRED=frontend_stale, EVIDENCE_PATH=f"{EV}/rc-62ce9697cd56/{f}.json#{x['id']}",
                            BACKEND_SHA=RC, FRONTEND_SHA=RC if frontend_stale == "YES" else "NA", INTEGRATION_SHA=RC, ENVIRONMENT="isolated c6rc stack from an RC worktree (API 51080), Chrome 154" if "ISOLATED" in stack else "public hosts over HTTPS",
                            PROVENANCE=f"RC_WIDE_REGRESSION_62ce9697cd56.md + {f}.json", BATCH="9"))
    return out


def ui_rows():
    out = []
    def bugs(sha, full, date):
        for x in csv.DictReader(open(os.path.join(C6, f"evidence/ui-ux-regression/{sha}/bugs-final.tsv"), encoding="utf-8"), delimiter="\t"):
            out.append(dict(CASE_ID=f"UXB-{sha[:7]}-{x['ID']}", DOMAIN="UI_UX_" + x["PORTAL"].replace("/", "_"), SEVERITY=x["SEVERITY"], STATUS="FAIL", EVIDENCE_CLASS="SCREENSHOT_AXE_AUDIT", LAST_TESTED_SHA=full, LAST_TESTED_DATE=date,
                            AUTOMATION="PLAYWRIGHT_SCRIPT(ui-ux.mjs)", OWNER=x["OWNER"], BLOCKER="-", STACK_CLASS="ISOLATED_C6_STACK_BROWSER", RETEST_REQUIRED="YES", EVIDENCE_PATH=f"{EV}/ui-ux-regression/{sha}/bugs-final.tsv#{x['ID']}",
                            BACKEND_SHA=RC, FRONTEND_SHA=full, INTEGRATION_SHA=RC, ENVIRONMENT="Chrome 154 only, viewports 1440/1280/1024/768/430/390/360", PROVENANCE=f"UI_UX_{'REGRESSION_5cc230e' if sha.startswith('5cc') else 'RETEST_40ee45b'}.md", BATCH="10" if sha.startswith("5cc") else "11"))
    bugs("5cc230e491a6", C5A, "2026-10-08"); bugs("40ee45bc16fe", C5B, "2026-10-08")
    def gate(sha, full, key, st, batch, blocker="-", sev="UNRATED", date="2026-10-08"):
        d = "UI_UX_REGRESSION_5cc230e.md" if sha.startswith("5cc") else "UI_UX_RETEST_40ee45b.md"
        out.append(dict(CASE_ID=f"UXG-{sha[:7]}-{key}", DOMAIN="UI_UX_GATE", SEVERITY=sev, STATUS=stat(st), EVIDENCE_CLASS="SCREENSHOT_AXE_AUDIT", LAST_TESTED_SHA=full, LAST_TESTED_DATE=date, AUTOMATION="PLAYWRIGHT_SCRIPT(ui-ux.mjs/ui-retest.mjs)",
                        OWNER="C5", BLOCKER=blocker, STACK_CLASS="ISOLATED_C6_STACK_BROWSER", RETEST_REQUIRED="YES", EVIDENCE_PATH=f"{EV}/ui-ux-regression/{sha}/" + ("summary.json" if sha.startswith("5cc") else "retest.json"),
                        BACKEND_SHA=RC, FRONTEND_SHA=full, INTEGRATION_SHA=RC, ENVIRONMENT="Chrome 154 only, isolated c6rc stack", PROVENANCE=d, BATCH=batch))
    a = "5cc230e491a6"
    for k, s, bl in [("ROUTES_866_CASES", "FAIL", "-"), ("AXE_SERIOUS_29", "FAIL", "-"), ("UNICODE", "PASS", "-"), ("ICONS", "PASS", "-"), ("CREATE_COMPANY", "PASS", "-"), ("EMPLOYEE_DIRECTORY", "PASS", "-"),
                     ("STUDIO", "FAIL", "-"), ("MANUAL_LONG_TAIL", "FAIL", "-"), ("ORGANIZATION_UI", "BLOCKED", "H-C1-17"), ("APP_CREATOR_ROUTES_56_CASES", "BLOCKED", "H-C1-04"), ("BUILDER_390", "FAIL", "-")]:
        gate(a, C5A, k, s, "10", bl)
    b = "40ee45bc16fe"
    for k, s in [("UX-001", "PASS"), ("UX-002", "FAIL"), ("UX-003", "FAIL"), ("UX-005", "PASS"), ("UX-006", "PASS"), ("UX-007", "PASS"), ("UX-008", "PASS"), ("AXE_CRITICAL0_SERIOUS0_874", "PASS"),
                 ("PROCESS_SAFETY", "PASS"), ("S4_FINAL_MERGE_REGRESSION", "PASS")]:
        gate(b, C5B, k, s, "11")
    return out


def build():
    state = json.load(open(os.path.join(C6, "qa_master_state.json"), encoding="utf-8")); mr = load_master_rows()
    old = old_rows(mr, state)
    ids = [r["CASE_ID"] for r in old]
    if len(ids) != 482 or set(ids) != set(state["rows"]): raise SystemExit(f"original ids: {len(ids)} rows, state has {len(state['rows'])}")
    rows = old + ug_rows() + rc_rows() + ui_rows()
    dup = [k for k, v in collections.Counter(r["CASE_ID"] for r in rows).items() if v > 1]
    if dup: raise SystemExit("duplicate CASE_IDs: " + ",".join(dup[:10]))
    return rows, state


def write(rows):
    with open(os.path.join(C6, "QA_MASTER_NORMALIZED.tsv"), "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, FIELDS, delimiter="\t", lineterminator="\n"); w.writeheader()
        for r in rows: w.writerow({k: str(r[k]).replace("\t", " ").replace("\n", " ") for k in FIELDS})
    c = lambda key, rs=rows: collections.Counter(r[key] for r in rs)
    orig = [r for r in rows if r["BATCH"] == "1-7"]
    md = ["# QA_MASTER_NORMALIZED — one flat table over every C6 result (generated, do not edit by hand)", "",
          "Generator: `harness/qa_master_normalize.py` · table: `QA_MASTER_NORMALIZED.tsv` (19 columns). It **adds** a normalised view next to `QA_MASTER.md` / `qa_master_state.json`; it rewrites no history: the 482 original CASE_IDs keep their last recorded status (a PASS stays a PASS), `NOTRUN` is spelled `NOT_RUN`.", "",
          f"* Total rows **{len(rows)}** = 482 original (batches 1–7) + {len(rows) - 482} ingested from batches 8–11 (user-guide QA 104, RC wide regression {len([r for r in rows if r['BATCH'] == '9'])}, UI/UX regression and retest {len([r for r in rows if r['BATCH'] in ('10', '11')])}).",
          "* Statuses are limited to PASS FAIL BLOCKED RETEST NOT_RUN GAP. A field whose source cannot be shown is `PROVENANCE_UNKNOWN`.", "",
          "## Counts", "", "| Batch | Rows | PASS | FAIL | BLOCKED | RETEST | NOT_RUN | GAP | RETEST_REQUIRED=YES | PROVENANCE_UNKNOWN |", "|---|---|---|---|---|---|---|---|---|---|"]
    for b, label in [("1-7", "1–7 original"), ("8", "8 user-guide"), ("9", "9 RC wide"), ("10", "10 UI/UX 5cc230e"), ("11", "11 UI/UX retest 40ee45b")]:
        rs = [r for r in rows if r["BATCH"] == b]; cs = c("STATUS", rs)
        md.append(f"| {label} | {len(rs)} | " + " | ".join(str(cs.get(s, 0)) for s in STATUSES) + f" | {sum(1 for r in rs if r['RETEST_REQUIRED'] == 'YES')} | {sum(1 for r in rs if UNK in r.values())} |")
    cs = c("STATUS"); md.append(f"| **all** | **{len(rows)}** | " + " | ".join(f"**{cs.get(s, 0)}**" for s in STATUSES) + f" | **{sum(1 for r in rows if r['RETEST_REQUIRED'] == 'YES')}** | **{sum(1 for r in rows if UNK in r.values())}** |")
    md += ["", "## Staleness rule (RETEST_REQUIRED)",
           f"Current build per plane when this table was written: backend of `integration/v2` = RC `{CUR_BACKEND[:12]}` (`git diff 62ce9697cd56 integration/v2 -- backend` is empty); frontend = C5 final import `{CUR_FRONTEND}` (after the `5cc230e`/`40ee45b` candidates).",
           "* Batches 1–7 were evaluated on `8e91172`; `integration/v2` has since changed 163 backend files (V30, recovery/persistence tests, …), so **every** one of those rows is `RETEST_REQUIRED=YES` even where its status is PASS (C6 rule: PASS needs evidence on the exact SHA — the old PASS is kept as history, not as current proof).",
           "* Batch 8 (user guide) ran on a public build that could not be proven to equal any SHA → `LAST_TESTED_SHA`, `BACKEND_SHA`, `FRONTEND_SHA`, `PROVENANCE` are `PROVENANCE_UNKNOWN` and every row is `RETEST_REQUIRED=YES`.",
           "* Batch 9 (RC `62ce9697cd56`): backend/API rows are current (`RETEST_REQUIRED=NO`); portal-routing and public-host rows touch the frontend, which has changed since → `YES`.",
           "* Batches 10–11 (UI): the frontend moved to the C5 final import after both candidates → `YES`.", "",
           "## How the UI/UX batches are indexed",
           "The 866 (5cc230e) / 874 (40ee45b) individual route×viewport cases stay in `evidence/ui-ux-regression/<sha>/cases.json` (raw, unfiltered: it still contains the documented harness false positives, so its FAIL count is higher than the reports' filtered numbers). The master carries what the reports signed: one `UXB-*` row per bug (`bugs-final.tsv`) and one `UXG-*` row per gate item, each pointing at its evidence file.", "",
           "## Columns", "`" + "` · `".join(FIELDS) + "`", "",
           "Original CASE_ID prefixes: " + ", ".join(f"{k} {v}" for k, v in sorted(collections.Counter(r['CASE_ID'].split('-')[0] for r in orig).items()))]
    open(os.path.join(C6, "QA_MASTER_NORMALIZED.md"), "w", encoding="utf-8").write("\n".join(md) + "\n")


def check():
    rows = list(csv.DictReader(open(os.path.join(C6, "QA_MASTER_NORMALIZED.tsv"), encoding="utf-8"), delimiter="\t")); bad = []
    if list(rows[0].keys()) != FIELDS: bad.append("header")
    for r in rows:
        if r["STATUS"] not in STATUSES: bad.append(f"{r['CASE_ID']}: status {r['STATUS']}")
        if any(v == "" for v in r.values()): bad.append(f"{r['CASE_ID']}: empty field")
    state = json.load(open(os.path.join(C6, "qa_master_state.json"), encoding="utf-8")); ids = {r["CASE_ID"] for r in rows}
    miss = set(state["rows"]) - ids
    if miss: bad.append(f"{len(miss)} original CASE_IDs missing")
    print(f"rows={len(rows)} original_present={len(state['rows']) - len(miss)}/{len(state['rows'])} violations={len(bad)}"); [print(" ", b) for b in bad[:20]]
    return 1 if bad else 0


if __name__ == "__main__":
    if "--check" in sys.argv: sys.exit(check())
    rows, _ = build(); write(rows); print(f"wrote {len(rows)} rows"); sys.exit(check())
