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


# ---------------------------------------------------------------- batch 12: FINAL RC QA (2026-10-10), product bc5c47f292d0 on stack c0rc (+ own stack c6fin for the data-target / recovery legs)
PROD = "bc5c47f292d00846c106669b09679a6fc36daef6"; STACK_C0RC = "953d17e53d05ba939f79d8fa03763fef9cd580da"; STACK_C6FIN = "fad4a7b4356fecd016e707423d426d1eb4b45c4d"; INTEG = "edf32dfe187a25ee159339c22be2ff4c1c093df4"
FIN = "evidence/final-rc-bc5c47f292d0"
# (file, prefix, domain, stack, automation, retest_required, backend_sha, environment)
FINAL_SUITES = [
    ("part1-environment-start.json", "FIN-ENV", "ENVIRONMENT", "C0RC_REAL_STACK", "NODE_SCRIPT(final-env-check.mjs)", "NO", STACK_C0RC, "stack c0rc, start of the run"),
    ("part1-environment-end.json", "FIN-ENVEND", "ENVIRONMENT", "C0RC_REAL_STACK", "NODE_SCRIPT(final-env-check.mjs)", "NO", STACK_C0RC, "stack c0rc, end of the run (C0's checkout had moved to fabea81; served builds unchanged)"),
    ("final-onboarding.json", "FIN-J01", "JOURNEY_01_COMPANY_ONBOARDING", "C0RC_REAL_STACK", "NODE_SCRIPT(final-onboarding.mjs)", "NO", STACK_C0RC, "stack c0rc"),
    ("final-org.json", "FIN-J02", "JOURNEY_02_DYNAMIC_ORG", "C0RC_REAL_STACK", "NODE_SCRIPT(final-org.mjs)", "NO", STACK_C0RC, "stack c0rc"),
    ("final-org-authz.json", "FIN-J02Z", "JOURNEY_02_DYNAMIC_ORG_AUTHZ", "C0RC_REAL_STACK", "NODE_SCRIPT(final-org-authz.mjs)", "NO", STACK_C0RC, "stack c0rc"),
    ("final-iam.json", "FIN-J03", "JOURNEY_03_IAM_LIFECYCLE", "C0RC_REAL_STACK", "NODE_SCRIPT(final-iam.mjs)", "NO", STACK_C0RC, "stack c0rc"),
    ("final-adv.json", "FIN-ADV", "SECURITY_ADVERSARIAL", "C0RC_REAL_STACK", "NODE_SCRIPT(final-adv.mjs)", "NO", STACK_C0RC, "stack c0rc"),
    ("final-adv-s04.json", "FIN-ADVS04", "SECURITY_ADVERSARIAL", "C0RC_REAL_STACK", "NODE_SCRIPT(final-adv-s04.mjs)", "NO", STACK_C0RC, "stack c0rc"),
    ("final-rcsec.json", "FIN-RCSEC", "SECURITY_REGRESSION", "C0RC_REAL_STACK", "NODE_SCRIPT(final-rcsec.mjs)", "NO", STACK_C0RC, "stack c0rc (rc-sec regression)"),
    ("journey04-app.json", "FIN-J04", "JOURNEY_04_BUILD_APP", "C0RC_REAL_STACK", "NODE_SCRIPT(final-app.mjs)", "NO", STACK_C0RC, "stack c0rc"),
    ("journey05-data.json", "FIN-J05C0", "JOURNEY_05_DATA_APP", "C0RC_REAL_STACK_NO_TRUSTSTORE", "NODE_SCRIPT(final-data.mjs)", "YES", STACK_C0RC, "stack c0rc: JVM has no data-target trust store (TLS_FAILED) -> target-dependent rows BLOCKED (owner C0)"),
    ("journey06-workflow.json", "FIN-J06C0", "JOURNEY_06_ACTION_WORKFLOW", "C0RC_REAL_STACK_NO_TRUSTSTORE", "NODE_SCRIPT(final-workflow.mjs)", "YES", STACK_C0RC, "stack c0rc: no data-target trust store"),
    ("c6fin/journey05-data.json", "FIN-J05", "JOURNEY_05_DATA_APP", "C6FIN_OWN_STACK_TRUSTSTORE", "NODE_SCRIPT(final-data.mjs)", "YES", STACK_C6FIN, "own stack c6fin (same product SHA, data-target trust store, real target)"),
    ("c6fin/journey06-workflow.json", "FIN-J06", "JOURNEY_06_ACTION_WORKFLOW", "C6FIN_OWN_STACK_TRUSTSTORE", "NODE_SCRIPT(final-workflow.mjs)", "YES", STACK_C6FIN, "own stack c6fin (same product SHA, data-target trust store)"),
    ("final-publish.json", "FIN-J07", "JOURNEY_07_PUBLISH", "C0RC_REAL_STACK", "NODE_SCRIPT(final-publish.mjs)", "YES", STACK_C0RC, "stack c0rc"),
    ("final-pd02.json", "FIN-PD02", "PD02_PUBLIC_DATA", "C0RC_REAL_STACK", "NODE_SCRIPT(final-pd02.mjs)+PLAYWRIGHT", "YES", STACK_C0RC, "stack c0rc (real rows blocked: no trust store)"),
    ("final-site.json", "FIN-SITE", "PUBLISHED_SITE_RUNTIME", "C0RC_REAL_STACK", "NODE_SCRIPT(final-site.mjs)+PLAYWRIGHT", "YES", STACK_C0RC, "stack c0rc"),
    ("c6fin/part18-recovery.json", "FIN-J08", "JOURNEY_08_OPERATIONS_RECOVERY", "C6FIN_OWN_STACK", "NODE_SCRIPT(final-recovery.mjs)", "NO", STACK_C6FIN, "own stack c6fin: restarts through the owned-process tooling (c0rc is C0's and was not restarted by C6)"),
    ("part15-brand-local-chromium.json", "FIN-BRL-C", "BRAND_VISUAL", "C0RC_REAL_STACK", "PLAYWRIGHT_CHROMIUM(final-brand.mjs)", "NO", STACK_C0RC, "portals of c0rc, Chrome 155"),
    ("part15-brand-local-webkit.json", "FIN-BRL-W", "BRAND_VISUAL", "C0RC_REAL_STACK", "PLAYWRIGHT_WEBKIT(final-brand.mjs)", "NO", STACK_C0RC, "portals of c0rc, Playwright WebKit (not Safari)"),
    ("part15-brand-public-chromium.json", "FIN-BRP-C", "BRAND_VISUAL_PUBLIC", "PUBLIC_PORTALS", "PLAYWRIGHT_CHROMIUM(final-brand.mjs)", "NO", "NA(public portals only)", "public portals platform|admin|studio.toolsmcp.uk (visual/deployment proof only)"),
    ("part17-performance.json", "FIN-PERF", "PERFORMANCE_STABILITY", "C0RC_REAL_STACK", "PLAYWRIGHT_CHROMIUM(final-perf.mjs)", "NO", STACK_C0RC, "stack c0rc, loaded 16 GB machine (measurements)"),
]
# defects found (final RC QA). status FAIL = open. severity per C6; owner = routing owner.
FINAL_DEFECTS = [
    ("FQ-ACT-02", "P1", "C4", "UPDATE_RECORD / DELETE_RECORD unusable on real PostgreSQL tables: the action handler requires an input `recordId` while the connector maps parameter names to column names (no input shape updates a row). Journey 05/06 'status update'. P1 until the C4 fix is integrated and independently retested (C7 instruction)", "c6fin/journey05-data.json#J05-M18"),
    ("FQ-WF-01", "P1", "C4", "APPROVAL step not wired: LIVE run FAILS with NOT_IMPLEMENTED while the validator accepts the workflow. Documented as deferred (DECISIONS D-C0-21 D5) but part of the C7 business journey 06; P1 unless C7/C0 scope approvals out of this RC", "c6fin/journey06-workflow.json#J06-F03"),
    ("FQ-WF-02", "P2", "C4", "no HTTP route to decide an approval (same deferred scope, B-C4-09)", "c6fin/journey06-workflow.json#J06-F05"),
    ("FQ-UI-01", "P2", "C5", "Admin Nhân viên (/admin/employees) at 360/390/768/1440: controls extend beyond the viewport (390: filter bar 654 px wide, search input right edge 659 px; 768: 'Thêm nhân viên' 657..816; 1440: unit select 846..1502); page content scrolls inside main so the document width hides it. Only route of 76 x 4 widths with this symptom", "ui/chromium/admin/tenantAdmin/admin_employees/390.png"),
    ("FQ-A11Y-02", "P2", "C5", "Studio builder dialogs (Chia sẻ, Phiên bản, Tệp, Cài đặt project, Xuất bản) do not return focus to the opener: after Escape the focus is on <body> (6/6)", "ui/chromium/cases.json#focus-not-returned"),
    ("FQ-IAM-01", "P3", "C1", "platform SYSTEM_ADMIN non-member gets 200 [] on GET /workspaces/{W}/projects (contract 3.2: 403); no data leaks", "final-onboarding.json#ON65"),
    ("FQ-IAM-02", "P3", "C3", "data-source POST {} as VIEWER answers 400 before the permission check (403)", "final-iam.json#IAM-M10b"),
    ("FQ-ISO-01", "P3", "C1", "GET /projects/{id}: foreign real id = 404 WORKSPACE_NOT_FOUND vs random id 404 PROJECT_NOT_FOUND (code differs: low-exploitability existence oracle)", "final-adv.json#ADV-ORACLE"),
    ("FQ-ACT-01", "P3", "C0", "definite not-executed connector failure on a LIVE write returns HTTP 500 with a correct body (WRITABLE_POSTGRES.md 5.1)", "c6fin/journey06-workflow.json#J06-F01d"),
    ("FQ-PUBLISH-01", "P3", "C2", "PUT publish-config without acknowledgePublicData -> 400 MALFORMED_REQUEST though the contract defaults it to false", "final-publish.json#H07-01"),
    ("FQ-PUBLISH-02", "P3", "C5", "rollback confirm dialog promises the newer version can be served again; the server refuses (400 DEPLOYMENT_NOT_RESTORABLE)", "final-publish.json#J07-33b"),
    ("FQ-SITE-01", "P3", "C0", "conditional GET through the sites gateway always 200 (nginx proxy_cache strips If-None-Match); direct to API 304 (H-C2-05)", "final-site.json#S-06b"),
    ("FQ-PERF-01", "P3", "C5", "Platform and Studio call GET /auth/me twice within 400 ms during navigation (Admin: none)", "part17-performance.json#PF-dup-platform"),
    ("FQ-BRAND-01", "P3", "C5", "Admin in-content links use the browser default colour rgb(0,0,238) instead of the brand link token #1d5bd8", "part15-brand-local-chromium/shots/admin-home-light-1440.png"),
    ("FQ-A11Y-01", "P3", "C5", "Studio project views (ai/assets/members/publish/settings/site/versions/design) have no visible h1; the builder has no <main> landmark (axe moderate: page-has-heading-one, landmark-one-main)", "ui/chromium/cases.json#axe"),
    ("FQ-WK-01", "P3", "C5", "WebKit only: in the 'Tạo công ty' dialog the focus trap leaks to the page skip link because buttons are not Tab stops in WebKit (Escape still closes)", "ui/webkit/cases.json#Tạo công ty"),
    ("FQ-WK-02", "P3", "C5", "WebKit only: native selects render 21-23 px high at 768/390 (WCAG 2.5.8 24 px) on Studio members/settings/builder rails; Chromium has none", "ui/webkit/cases.json#touch"),
    ("FQ-ENV-01", "P2", "C0", "environment: c0rc backend JVM has no trust store for the data target's dev CA (TLS_FAILED): real-row legs of Journey 05/06/PD02 cannot run on c0rc; proven on c6fin", "journey05-data.json#J05-M05"),
]


def _dedupe(rows):
    seen = collections.Counter(); out = []
    for r in rows:
        seen[r["CASE_ID"]] += 1
        if seen[r["CASE_ID"]] > 1: r["CASE_ID"] = f"{r['CASE_ID']}~{seen[r['CASE_ID']]}"
        out.append(r)
    return out


def final_rows():
    out = []; base = os.path.join(C6)
    for f, pre, dom, stack, auto, retest, bsha, env in FINAL_SUITES:
        d = json.load(open(os.path.join(base, FIN, f), encoding="utf-8"))
        for x in d["rows"]:
            res = x.get("result", ""); st = "BLOCKED" if res.startswith("BLOCKED") else stat(res)
            cls = x.get("cls") or "REAL_BACKEND_E2E"; own = x.get("owner") or "-"
            out.append(dict(CASE_ID=f"{pre}-{x['id']}", DOMAIN=dom, SEVERITY="UNRATED", STATUS=st, EVIDENCE_CLASS=cls, LAST_TESTED_SHA=PROD, LAST_TESTED_DATE="2026-10-10", AUTOMATION=auto, OWNER=own,
                            BLOCKER=("FQ-ENV-01" if st == "BLOCKED" and "C0RC_REAL_STACK_NO_TRUSTSTORE" == stack else "-"), STACK_CLASS=stack, RETEST_REQUIRED=retest if st == "PASS" else "YES", EVIDENCE_PATH=f"{FIN}/{f}#{x['id']}",
                            BACKEND_SHA=bsha, FRONTEND_SHA=PROD if "PORTAL" in stack or "BRAND" in dom or "PERF" in dom else "NA", INTEGRATION_SHA=INTEG, ENVIRONMENT=env, PROVENANCE=f"{f} (final RC QA, product {PROD[:12]})", BATCH="12"))
    # C5 real flows run by C6 (11-flow batch on c0rc + ORG01 rerun)
    import glob, re
    rep = json.load(open(glob.glob(os.path.join(base, FIN, "c5-flows-main", "report-*.json"))[0], encoding="utf-8"))
    for r in rep["results"]:
        if r["id"] == "E2E-ORG01": 
            out.append(dict(CASE_ID="FIN-C5-E2E-ORG01-run1", DOMAIN="C5_REAL_FLOW", SEVERITY="UNRATED", STATUS="BLOCKED", EVIDENCE_CLASS="REAL_BACKEND_E2E", LAST_TESTED_SHA=PROD, LAST_TESTED_DATE="2026-10-10", AUTOMATION="C5_FLOW(tests/e2e-real)", OWNER="C0", BLOCKER="activation 429 (shared per-IP limiter used by parallel workstreams)", STACK_CLASS="C0RC_REAL_STACK", RETEST_REQUIRED="NO", EVIDENCE_PATH=f"{FIN}/c5-flows-main/run.log", BACKEND_SHA=STACK_C0RC, FRONTEND_SHA=PROD, INTEGRATION_SHA=INTEG, ENVIRONMENT="stack c0rc", PROVENANCE="c5-flows-main (fixture creation hit 429; superseded by run2)", BATCH="12")); continue
        out.append(dict(CASE_ID=f"FIN-C5-{r['id']}", DOMAIN="C5_REAL_FLOW", SEVERITY="UNRATED", STATUS=stat(r["status"]), EVIDENCE_CLASS="REAL_BACKEND_E2E", LAST_TESTED_SHA=PROD, LAST_TESTED_DATE="2026-10-10", AUTOMATION="C5_FLOW(tests/e2e-real)", OWNER="C5", BLOCKER="-", STACK_CLASS="C0RC_REAL_STACK", RETEST_REQUIRED="NO", EVIDENCE_PATH=f"{FIN}/c5-flows-main/", BACKEND_SHA=STACK_C0RC, FRONTEND_SHA=PROD, INTEGRATION_SHA=INTEG, ENVIRONMENT=f"stack c0rc; {len(r.get('checks', []))} checks", PROVENANCE="c5-flows-main/report-*.json", BATCH="12"))
    log = open(os.path.join(base, FIN, "c5-flows-org01-run2", "run.log"), encoding="utf-8").read(); ok = log.count("\u2713"); ko = log.count("\u2717")
    out.append(dict(CASE_ID="FIN-C5-E2E-ORG01", DOMAIN="C5_REAL_FLOW", SEVERITY="UNRATED", STATUS="PASS" if ko == 0 and ok == 31 else "FAIL", EVIDENCE_CLASS="REAL_BACKEND_E2E", LAST_TESTED_SHA=PROD, LAST_TESTED_DATE="2026-10-10", AUTOMATION="C5_FLOW(tests/e2e-real)", OWNER="C5", BLOCKER="-", STACK_CLASS="C0RC_REAL_STACK", RETEST_REQUIRED="NO", EVIDENCE_PATH=f"{FIN}/c5-flows-org01-run2/run.log", BACKEND_SHA=STACK_C0RC, FRONTEND_SHA=PROD, INTEGRATION_SHA=INTEG, ENVIRONMENT=f"stack c0rc; {ok} checks passed / {ko} failed (31/31 expected)", PROVENANCE="c5-flows-org01-run2 (run alone)", BATCH="12"))
    # defects
    for did, sev, owner, text, ev in FINAL_DEFECTS:
        out.append(dict(CASE_ID=did, DOMAIN="DEFECT", SEVERITY=sev, STATUS="FAIL", EVIDENCE_CLASS="REAL_BACKEND_E2E" if not ev.startswith(("ui/", "part15")) else "REAL_STACK", LAST_TESTED_SHA=PROD, LAST_TESTED_DATE="2026-10-10", AUTOMATION="DEFECT_RECORD", OWNER=owner,
                        BLOCKER=("C7: keep P1 until the C4 fix is integrated and independently retested on the final RC SHA" if did == "FQ-ACT-02" else "-"), STACK_CLASS="C0RC/C6FIN", RETEST_REQUIRED="YES", EVIDENCE_PATH=f"{FIN}/{ev}", BACKEND_SHA=STACK_C0RC, FRONTEND_SHA=PROD, INTEGRATION_SHA=INTEG, ENVIRONMENT=text[:230], PROVENANCE="coordinator-notes.md + evidence", BATCH="12"))
    # UI gates (aggregated; the 1089 + 392 individual route/width cases stay in cases.json of each run)
    gp = os.path.join(base, FIN, "ui-gates.json")
    if os.path.exists(gp):
        for g in json.load(open(gp, encoding="utf-8")):
            out.append(dict(CASE_ID=g["id"], DOMAIN="UI_FINAL_" + g["area"], SEVERITY=g.get("severity", "UNRATED"), STATUS=stat(g["status"]), EVIDENCE_CLASS=g.get("cls", "REAL_STACK"), LAST_TESTED_SHA=PROD, LAST_TESTED_DATE="2026-10-10", AUTOMATION=g.get("auto", "PLAYWRIGHT"), OWNER=g.get("owner", "C5"),
                            BLOCKER=g.get("blocker", "-"), STACK_CLASS="C0RC_REAL_STACK", RETEST_REQUIRED="YES" if g["status"] != "PASS" else "NO", EVIDENCE_PATH=f"{FIN}/{g['evidence']}", BACKEND_SHA=STACK_C0RC, FRONTEND_SHA=PROD, INTEGRATION_SHA=INTEG, ENVIRONMENT=g["detail"][:230], PROVENANCE="ui audit + coordinator-notes.md", BATCH="12"))
    return _dedupe(out)


def build():
    state = json.load(open(os.path.join(C6, "qa_master_state.json"), encoding="utf-8")); mr = load_master_rows()
    old = old_rows(mr, state)
    ids = [r["CASE_ID"] for r in old]
    if len(ids) != 482 or set(ids) != set(state["rows"]): raise SystemExit(f"original ids: {len(ids)} rows, state has {len(state['rows'])}")
    rows = old + ug_rows() + rc_rows() + ui_rows() + final_rows()
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
          f"* Total rows **{len(rows)}** = 482 original (batches 1–7) + {len(rows) - 482} ingested from batches 8–12 (user-guide QA 104, RC wide regression {len([r for r in rows if r['BATCH'] == '9'])}, UI/UX regression and retest {len([r for r in rows if r['BATCH'] in ('10', '11')])}, FINAL RC QA of product bc5c47f292d0 {len([r for r in rows if r['BATCH'] == '12'])}).",
          "* Statuses are limited to PASS FAIL BLOCKED RETEST NOT_RUN GAP. A field whose source cannot be shown is `PROVENANCE_UNKNOWN`.", "",
          "## Counts", "", "| Batch | Rows | PASS | FAIL | BLOCKED | RETEST | NOT_RUN | GAP | RETEST_REQUIRED=YES | PROVENANCE_UNKNOWN |", "|---|---|---|---|---|---|---|---|---|---|"]
    for b, label in [("1-7", "1–7 original"), ("8", "8 user-guide"), ("9", "9 RC wide"), ("10", "10 UI/UX 5cc230e"), ("11", "11 UI/UX retest 40ee45b"), ("12", "12 FINAL RC QA bc5c47f")]:
        rs = [r for r in rows if r["BATCH"] == b]; cs = c("STATUS", rs)
        md.append(f"| {label} | {len(rs)} | " + " | ".join(str(cs.get(s, 0)) for s in STATUSES) + f" | {sum(1 for r in rs if r['RETEST_REQUIRED'] == 'YES')} | {sum(1 for r in rs if UNK in r.values())} |")
    cs = c("STATUS"); md.append(f"| **all** | **{len(rows)}** | " + " | ".join(f"**{cs.get(s, 0)}**" for s in STATUSES) + f" | **{sum(1 for r in rows if r['RETEST_REQUIRED'] == 'YES')}** | **{sum(1 for r in rows if UNK in r.values())}** |")
    md += ["", "## Staleness rule (RETEST_REQUIRED)",
           f"Current build per plane when this table was written: backend of `integration/v2` = RC `{CUR_BACKEND[:12]}` (`git diff 62ce9697cd56 integration/v2 -- backend` is empty); frontend = C5 final import `{CUR_FRONTEND}` (after the `5cc230e`/`40ee45b` candidates).",
           "* Batches 1–7 were evaluated on `8e91172`; `integration/v2` has since changed 163 backend files (V30, recovery/persistence tests, …), so **every** one of those rows is `RETEST_REQUIRED=YES` even where its status is PASS (C6 rule: PASS needs evidence on the exact SHA — the old PASS is kept as history, not as current proof).",
           "* Batch 8 (user guide) ran on a public build that could not be proven to equal any SHA → `LAST_TESTED_SHA`, `BACKEND_SHA`, `FRONTEND_SHA`, `PROVENANCE` are `PROVENANCE_UNKNOWN` and every row is `RETEST_REQUIRED=YES`.",
           "* Batch 9 (RC `62ce9697cd56`): backend/API rows are current (`RETEST_REQUIRED=NO`); portal-routing and public-host rows touch the frontend, which has changed since → `YES`.",
           "* Batches 10–11 (UI): the frontend moved to the C5 final import after both candidates → `YES`.",
           "* Batch 12 (FINAL RC QA, product `bc5c47f292d0` as served on c0rc; own stack c6fin for the data-target and recovery legs): PASS rows are current for that build (`NO`). FAIL/BLOCKED rows and the publish / PD02 / site / Journey 05–06 suites are `YES`: C0's checkout already moved to `fabea81` (C2 publish-authorization and C4 runtime-authorization imports touching WorkflowEngine) and FQ-ACT-02 (P1) needs the C4 fix integrated and independently retested on the final RC SHA before READY_FOR_RELEASE.", "",
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
