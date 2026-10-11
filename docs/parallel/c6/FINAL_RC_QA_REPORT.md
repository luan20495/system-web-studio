# C6 — FINAL RC QA REPORT (independent) · 2026-10-10

Written for: C7 / C0 (release decision makers). Evidence: `evidence/final-rc-bc5c47f292d0/` (text, committed) + three external archives `c6-evidence-final-rc-*` (screenshots, see `EVIDENCE_INDEX.md`). Every row is in `QA_MASTER_NORMALIZED.tsv` (batch 12). No production code was changed.

## 1. What was tested (exact SHAs)
| | |
|---|---|
| INTEGRATION (C7 source of truth) | `edf32dfe187a25ee159339c22be2ff4c1c093df4` (integration/v2 later `fad4a7b4356f`: docs/tests only) |
| DEPLOYED PRODUCT SHA | `bc5c47f292d00846c106669b09679a6fc36daef6` |
| STACK `c0rc` (primary) | backend worktree `953d17e53d05` (= bc5c47f + docs/tests/tooling; product-file diff 0), portals built from checkout `fad4a7b4356f` (product-file diff 0). Served BUILD_IDs, identical at start and end of the run: Platform `2GuhCrW5Nzpw91J-27pgg`, Admin `-Eg7ur7xVaYfyHAOTtq5I`, Studio `cfrpHRfq45k1DWtHqrauH` (each portal's HTML carries its id). Real PostgreSQL 32 migrations / Redis / RabbitMQ / MinIO / sites gateway; org persistence, publish-configs, public-data runtime ON; no mock |
| OWN STACK `c6fin` | same product (backend worktree = checkout `fad4a7b4356f`), JVM with the data-target dev-CA trust store, render worker. Used for the data-target legs of Journeys 05/06 and for Journey 08 restarts, because c0rc has no trust store and is C0's |
| Public API `1006cbf441f6` | **not used** for any backend verification. Public portals used for visual/deployment proof only |
| Caveat | by the end of the run C0's checkout `xweb-c0-rc` had moved to `fabea81` (C2 publish-authorization and C4 runtime-authorization imports, WorkflowEngine changed). The **running** portals/API did not change (BUILD_IDs, SERVING.json). Everything below is for `bc5c47f292d0` as served; `fabea81` is NOT tested |
| Environment events | the c0rc API was restarted by someone other than C6 at least 6 times (13:13-14:05); workstreams retried, affected UI cases were re-run on fresh sessions |

## 2. Release decision
```
RC_SHA_TESTED:           bc5c47f292d00846c106669b09679a6fc36daef6 (c0rc, stack 953d17e53d05) + own stack c6fin (fad4a7b4356f, same product)
FULL_BUSINESS_QA:        FAIL
BUSINESS_JOURNEYS:       7/8   (06 FAIL; 05 and 08 PASS on own stack c6fin only, see below)
FULL_TECHNICAL_QA:       FAIL
E2E_ORG01:               31/31 (C5 flow run by C6 on c0rc; C6 independent API re-execution 128/129, Z19 blocked)
PD02:                    PASS (C5 flow 21 checks; C6 independent 41/43, 2 real-row checks BLOCKED_ENV)
P0: 0   P1: 2 (FQ-ACT-02, FQ-WF-01)   P2: 4 (FQ-WF-02, FQ-UI-01, FQ-A11Y-02, FQ-ENV-01 environment)   P3: 12
READY_FOR_RC_FREEZE:     NO
READY_FOR_RELEASE:       NO   (open P1 FQ-ACT-02 must be closed by the C4 fix, integrated and independently retested on the final RC SHA)
```

## 3. Business journeys
| # | Journey | Result | Evidence (class REAL_BACKEND_E2E unless noted) |
|---|---|---|---|
| 01 | Company onboarding | **PASS** | API 61/62 (one P3 doc deviation FQ-IAM-01); UI half = C5 flows PL01 28 / SUPER01 18 / ADMIN01 22 checks PASS |
| 02 | Dynamic organization | **PASS** | API 97/97 + 31/32 (Z19 blocked: no route creates a principal with a strict subset of the six org codes); C5 ORG01 31/31 (UI, real flow) |
| 03 | IAM / permission lifecycle | **PASS** | 208/209 (P3 FQ-IAM-02); M-052 exact sets; every change applied on the very next request (4-34 ms) |
| 04 | Build app from zero | **PASS** | 44/45 (device preview: no server route, client side); every write confirmed by a fresh-session read; concurrent edit 1 winner + 409 |
| 05 | Data-backed app | **PASS on c6fin** (105/107; the 2 fails are FQ-ACT-02); **BLOCKED on c0rc** (TLS_FAILED, no trust store) | credential write-only, SSRF 16/16 refused, real rows read, real INSERT confirmed in the target, replay/concurrent double submit = 1 row |
| 06 | Action / workflow business process | **FAIL** | c6fin 43 pass / 3 fail / 4 blocked. Approval step not wired (FQ-WF-01), no decision route (FQ-WF-02), UPDATE_RECORD unusable (FQ-ACT-02). Proven: duplicate start = 1 run/1 row, TIMEOUT, CANCEL, BRANCH validation, ambiguous mutation => 409 `IDEMPOTENCY_OUTCOME_UNKNOWN` retryable=false, same key stays unknown, no compensation of the ambiguous step |
| 07 | Publish / public site / rollback | **PASS** | 90/92 (2 P3); H-C2-07 409/422 + approval via PUT only; v1->v2->v3, rollback, unpublish reflected by the gateway without stale content; visitor needs no session |
| 08 | Operations / recovery | **PASS on c6fin** (24/26, 2 not executed) | restart 62 s: site stayed 200 and byte/ETag-identical, Redis session survived, org data/audit/release history intact, WAIT run survived, 60 s timer completed after restart, idempotency key survived; render pause -> clean FAILED while live site kept v1; MinIO pause -> BUILDING then recovered. Not executed: portal redeploy (C0-owned), hard crash with a step in flight |

## 4. Technical gates
| Gate | Result | Note |
|---|---|---|
| ENVIRONMENT | PASS | 25/26 at start (ENV-13, no mock/localStorage fallback, closed by a browser probe: localStorage empty on Platform/Admin, Studio keeps only per-viewer preferences, every screen calls the real API); end-of-run recheck: served builds unchanged (ENV-08/14a at the end only show a slow docker exec and C0's checkout moving to fabea81) |
| PLATFORM / ADMIN / STUDIO | PASS | C5 flows 11/11 on c0rc (ORG01 after rerun); axe 0/0 |
| IAM | **PASS** | 208/209, adversarial 51/55, rc-sec regression 74/74 |
| TENANT_ISOLATION | **PASS** | 0 cross-tenant data/field/write in >1,200 swept requests; one P3 code-difference oracle (FQ-ISO-01) |
| WORKSPACE / PROJECT ISOLATION | **PASS** | |
| DYNAMIC_ORG | **PASS** | |
| DATA_SOURCE / QUERY | PASS (c6fin) | blocked on c0rc (environment) |
| MUTATION | PASS for CREATE_RECORD; **FAIL for UPDATE/DELETE** | FQ-ACT-02 |
| ACTION | **FAIL** | FQ-ACT-02 (P1), FQ-ACT-01 (P3) |
| WORKFLOW | **FAIL** | FQ-WF-01 (P1, deferred scope), FQ-WF-02 |
| QUEUE | PASS | RabbitMQ answers; 60 s timer/sweeper recovery after restart |
| PUBLISH / ROLLBACK / PUBLIC RUNTIME / PUBLISHED_SITE | **PASS** | P3 only |
| H_C2_07 | PASS | one P3 (FQ-PUBLISH-01) |
| RECOVERY | PASS (c6fin) | see Journey 08 |
| RESPONSIVE (9 widths) | **FAIL** | FQ-UI-01 P2 (Admin Nhân viên clipped at 360/390/768/1440); the other 75 routes clean at 4 widths on a stricter detector |
| ACCESSIBILITY | **FAIL** | axe critical 0 / serious 0 (moderate 13); FQ-A11Y-02 P2 (Studio builder dialogs lose focus); not a WCAG certification |
| BRAND | PASS | local Chromium/WebKit 66/72, public 48/48; P3 FQ-BRAND-01 |
| CHROMIUM | PASS (engine suite completed: 1089 cases) | product findings listed above |
| WEBKIT | PASS (3 widths, 392 cases; Playwright WebKit, NOT Safari) | two P3 engine findings |
| FIREFOX | **BLOCKED_TOOLING** | `Could not find profile folder`; not PASS, not a product failure |
| PERFORMANCE | PASS | 26/30 (2 fail = one P3 duplicate request, 2 observations); numbers on a loaded 16 GB machine |

## 5. Defects (route: C1 IAM · C2 publish · C3 data/DB · C4 action/workflow · C5 frontend · C0 integration/env)
| ID | Sev | Owner | Flow | Expected / Actual | Evidence | Repro |
|---|---|---|---|---|---|---|
| FQ-ACT-02 | **P1** | C4 (C3/C0 wiring) | 05/06 status update | UPDATE_RECORD must update a row of a real table. `MutationActionHandler` requires an input `recordId`, the PostgreSQL connector maps parameter names to column names: 422 INVALID_DEFINITION without it, 500 INVALID_PARAMS with it, MUTATION_REJECTED if the key is named recordId. Workaround: a table whose key column is literally `recordId`. Kept **P1 until the C4 fix is integrated and independently retested** (C7) | c6fin/journey05-data J05-M18/M19; source ActionHandlers.kt:162-180 | YES (3 variants) |
| FQ-WF-01 | **P1** (scope decision) | C4 | 06 approval | LIVE run must park WAITING on APPROVAL; it FAILS with `NOT_IMPLEMENTED "Approvals are not wired"` while the validator accepts the workflow. Documented deferred (DECISIONS D-C0-21 D5: "LIVE workflows with an APPROVAL step stay refused") but it is a step of business journey 06: P2 if C7/C0 scope approvals out of this RC | c6fin/journey06 J06-F03/F08 | YES |
| FQ-WF-02 | P2 | C4/C0 | 06 | no HTTP route decides an approval (B-C4-09 deferred) | J06-F05 | YES |
| FQ-UI-01 | P2 | C5 | 02 employees | `/admin/employees`: 390: filter bar 654 px in a 390 viewport (search input right edge 659), 768: "Thêm nhân viên" 657..816, 1440: unit select 846..1502; content scrolls inside `main`, hiding it from the document-width check | ui/clip2/clip.json; ui/chromium/admin/tenantAdmin/admin_employees/{390,1440}.png | YES |
| FQ-A11Y-02 | P2 | C5 | 04 | Studio builder dialogs (Chia sẻ, Phiên bản, Tệp, Cài đặt project, Xuất bản) do not return focus to the opener: after Escape focus is `<body>` (6/6) | ui/chromium cases.json warnings + manual probe | YES |
| FQ-ENV-01 | P2 (env) | C0 | 05/06/PD02 | c0rc JVM lacks the data-target dev-CA trust store: TLS_FAILED; real-row legs cannot run on c0rc | journey05-data (c0rc) | YES |
| FQ-IAM-01 | P3 | C1 | 01 | platform SYSTEM_ADMIN non-member: 200 `[]` on GET /workspaces/{W}/projects (contract 3.2: 403), no data leaks | final-onboarding ON65 | YES |
| FQ-IAM-02 | P3 | C3 | 03 | data-source POST `{}` as VIEWER: 400 before 403 | final-iam IAM-M10b | YES |
| FQ-ISO-01 | P3 | C1 | 12 | GET /projects/{id}: foreign real id 404 WORKSPACE_NOT_FOUND vs random 404 PROJECT_NOT_FOUND (code differs) | final-adv ADV-ORACLE | YES |
| FQ-ACT-01 | P3 | C0 | 06 | definite not-executed connector failure returns HTTP 500 with a correct body (WRITABLE_POSTGRES 5.1) | J06-F01d | YES |
| FQ-PUBLISH-01 | P3 | C2 | 07 | PUT publish-config without `acknowledgePublicData` -> 400 (contract default false) | H07-01 | YES |
| FQ-PUBLISH-02 | P3 | C5 | 07 | rollback dialog says the newer version can be served again; server refuses (400 DEPLOYMENT_NOT_RESTORABLE) | J07-33b (source read + API) | YES |
| FQ-SITE-01 | P3 | C0 | 07 | If-None-Match through the sites gateway always 200 (nginx strips it; direct API 304; H-C2-05) | S-06b | YES |
| FQ-PERF-01 | P3 | C5 | perf | Platform and Studio call GET /auth/me twice within 400 ms during navigation (Admin: none) | PF-dup-platform/studio | 1 run |
| FQ-BRAND-01 | P3 | C5 | brand | Admin in-content links use browser-default `rgb(0,0,238)` not `--color-link #1d5bd8` | part15 shots, computed style 2/2 | YES |
| FQ-A11Y-01 | P3 | C5 | a11y | Studio project views lack a visible h1; builder has no `<main>` (axe moderate) | ui/chromium cases.json | YES |
| FQ-WK-01 | P3 | C5 | a11y | WebKit only: focus trap in "Tạo công ty" leaks to the skip link (buttons are not Tab stops there); Escape still closes | probe + ui/webkit | YES |
| FQ-WK-02 | P3 | C5 | a11y | WebKit only: native selects 21-23 px high at 768/390 (< 24 px) | ui/webkit | YES |

## 6. Blocked / not proven (never counted as PASS)
* **Firefox**: BLOCKED_TOOLING (launch failure, tried TMPDIR and persistent context).
* **Real rows through the PD02 public query and Journey 05/06 target legs on c0rc**: BLOCKED_ENV (FQ-ENV-01); proven on c6fin only.
* Approval decisions (wrong approver, status update after approval): not testable (deferred scope); covered only by C4 unit tests.
* Hard crash with a workflow step in flight (INTERRUPTED recovery), portal redeploy, ORG_STRUCTURE_BUSY 503 shape, idle-timeout expiry (20 min), retry of a retryable failure (J06-D02), VIEW-vs-MANAGE independence at runtime (Z19), immutable-asset cache after unpublish (no image asset), Studio rollback/unpublish buttons (API only), M-052 DEFAULT-primary variant.
* Journey 04 UI half beyond C5 USER01 and the route audit; device-preview payloads (client side).
* One axe "serious" node counted by the WebKit summary inside a dialog check was not retained and did not reproduce.
* c0rc was never restarted by C6; recovery was exercised on c6fin (same product).

## 7. Method notes
* Four independent workstreams (IAM, Dynamic Org, build/data/workflow, publish) plus the coordinator ran on shared infrastructure. The per-IP limits (30 activations / 10 min, failed logins 5 per user / 50 per IP) were shared; 429s were waited out, no X-Forwarded-For was spoofed, ≈45 users were activated in total, all `c6f-*` accounts created for tests were disabled where the product allows (tenants/orgs stay: no delete route).
* Evidence classes: REAL_BACKEND_E2E (real HTTP/browser -> API -> PostgreSQL), REAL_STACK (real portals observed), MANUAL (screenshots, judged by eye), HARNESS (static source check Z18 only).
* Several harness false positives were found and fixed (rate limiter, API restarts, session artifacts, a detector that exempted `main` scrollers) — listed in `coordinator-notes.md`.

## 8. To reach READY_FOR_RELEASE
1. C4: fix FQ-ACT-02 (and decide approvals: implement FQ-WF-01/02 or scope them out in writing); integrate; **C6 retests on the final RC SHA** (data legs need the trust store on the exact RC stack: C0 FQ-ENV-01).
2. C5: FQ-UI-01 and FQ-A11Y-02 (P2) before freeze; P3s at C5's discretion.
3. C0: serve the final RC SHA (note `fabea81` is not tested) and give the C6 stack the data-target trust store.
