> **SUPERSEDED_BY:** `docs/QA_FINAL.md` - historical document (moved from `docs/parallel/c5/audit/LEDGER_STATUS_NORMALIZED.md`), kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# C5 — LEDGER STATUS, NORMALIZED (generated from master-ledger.json after Wave A)

Source of truth for the rows is `MASTER_ISSUE_LEDGER.md` / `master-ledger.json` (the free-text Status column keeps the evidence and commit SHAs). This file maps every row to exactly one canonical status. All browser evidence is HARNESS, NOT REAL BACKEND unless a row says REAL STACK.

## Rules
- FIXED: status starts with `FIXED`.
- CLOSED: status starts with `CLOSED` (triaged as not a defect / detector case / harness artefact).
- PARTIAL: part fixed, remainder open (status starts with PARTIAL, MOSTLY FIXED, CLIENT HALF FIXED, SHELL ADOPTED, COMPONENT/LIBRARY MERGED, SHARED COPY FIXED, ADMIN PART / C5 PART FIXED, GUARD/BRAND/VOCABULARY/PHASE/RTL ... MERGED). Rows whose remaining half is a handoff are PARTIAL, the handoff is named in the text.
- OPEN: no fix yet and a C5 action is possible.
- BLOCKED: nothing C5 can do until another owner (C0/C1/C2/backend) answers or changes something.
- ACCEPTED_LIMITATION: none recorded so far (M-114 may become one if cross-browser runs are declined).
- RESEARCH_ONLY: ledger class RESEARCH (report / plan, no code).
- NEEDS_REVIEW: status could not be classified from evidence (none at generation time).

## Counts
| Status | Count |
|---|---|
| FIXED | 94 |
| CLOSED | 3 |
| PARTIAL | 24 |
| OPEN | 0 |
| BLOCKED | 8 |
| ACCEPTED_LIMITATION | 0 |
| RESEARCH_ONLY | 3 |
| NEEDS_REVIEW | 0 |
| **CANONICAL_TOTAL** | **132** |

Unresolved (not FIXED/CLOSED): 35 = P0 0 · P1 2 · P2 17 · P3 16.
Severity of all 132: P0 0 · P1 15 · P2 74 · P3 43.

## FIXED (94)
- M-001 P1 C5 · Cancel in the code-change review prompt APPROVES the change
- M-002 P1 C5 · Unsaved Inspector edits are silently lost when another section is selected; focu
- M-003 P1 C5 · Selecting a section reloads the preview iframe and resets its scroll
- M-004 P1 C5 · AI conversation never scrolls to the newest message and is not announced to scre
- M-005 P1 C5 · Data binding needs 7 steps, internal jargon (ViewModel, slot, operation key) and
- M-006 P1 C5 · No error boundary anywhere: a render exception unmounts the whole portal; the Bu
- M-008 P1 C5 · 'Tạo công ty' is a dead end for the first admin (≈12 clicks across two modules)
- M-010 P1 C5 · Esc/backdrop closes the dialog while the POST is in flight: the account is creat
- M-011 P1 C5 · Studio AI/Code workspace on a phone hides Website/Phiên bản/Tệp/Chia sẻ/Cài đặt 
- M-012 P1 C5 · Admin/Platform 'Nguồn dữ liệu' renders unstyled (UA fieldset borders, delete dia
- M-013 P1 C5 · Closed mobile nav drawer stays in the Tab order (16 links; first Tab lands off-s
- M-014 P2 C5 · '.grid2 minmax(380px,1fr)' overflows main sideways at <=394px (WCAG 1.4.10 reflo
- M-015 P1 C5 · Code editor swallows Tab/Shift+Tab: keyboard trap (WCAG 2.1.2 A)
- M-016 P1 C5 · Toast has no live region, no dismiss, no timeout, sits under the builder overlay
- M-017 P2 C5 · 30-34 native window.confirm()/prompt() sites: unstyled, suppressible, reject rea
- M-018 P2 C5 · Irreversible/high-impact actions with no confirmation: role select commits on ch
- M-019 P2 C5 · Destructive Studio actions with no/weak confirmation: 'Phục vụ lại bản này' roll
- M-020 P2 C5 · No shared in-flight guard: double click/Ctrl+Enter sends duplicate requests (2 c
- M-021 P2 C5 · Builder dialog: Esc does nothing when focus is on <body> (handlers on the dialog
- M-022 P2 C5 · Modal opens with focus on the invisible srOnly <select> and keeps it in the Tab 
- M-023 P2 C5 · Four dialog/focus-trap implementations with different rules (scroll lock only in
- M-024 P2 C5 · Picker listbox: aria-activedescendant on the listbox while focus stays on the bu
- M-025 P2 C5 · No skip link (19 Tabs to <main>); main is a tab stop even when it does not scrol
- M-026 P2 C5 · Focus ring #67b8ff is 2.13:1 on white (7 control types); checkbox/radio ring #c7
- M-027 P2 C5 · Control borders 1.24-1.48:1 (inputs, switch off track, outlined buttons) fail 1.
- M-028 P2 C5 · role=tab on <a>/<button> with no tabpanel/aria-controls/arrow keys; radio cards 
- M-029 P2 C5 · Audit rows expand on mouse click only (no role/aria-expanded/keyboard)
- M-030 P2 C5 · Publish audience choice ('Riêng tư'/'Công khai') are class-only buttons (no aria
- M-031 P2 C5 · Disabled-button reasons exist only as title= (invisible on touch, not focusable)
- M-032 P2 C5 · Warnings/risk shown grey (status keys reused as a colour picker): Rủi ro cao, Tr
- M-033 P2 C5 · Provider row collapses to width 0 next to a 244px action group at 390
- M-034 P2 C5 · Server error rendered below the fold, under the sticky footer
- M-035 P2 C5 · Members drawer table overflows at 390 (role select clipped, 'Xóa' off-screen)
- M-036 P2 C5 · Phone builder does not tell the user that phones are look-and-adjust; toolbar is
- M-037 P2 C5 · Half-filled data/action/workflow editors lose their state on a rail-tab switch (
- M-038 P2 C5 · MenuEditor/NotFoundEditor/ThemePanel copy the doc into useState once: after remo
- M-041 P2 C5 · Project cards: hard-coded 'Website' pill with PRIVATE tone (appKind exists); KPI
- M-043 P2 C5 · 'Code' tab always shown; the page says Code Mode is 'a separate product phase' (
- M-044 P2 C5 · Page dialog shows only 'Không lưu được' (real reason only in a toast)
- M-045 P2 C5 · No beforeunload/navigation guard for code drafts (reload loses them)
- M-046 P2 C5 · studio:layout fires every frame and each builds a new rect array -> full re-rend
- M-047 P2 C5 · Two implementations of page add/nav/404 editing with different validation (SiteP
- M-048 P2 C5 · ProjectWorkspace is one 357-line function (24 useState, 12 useEffect: save machi
- M-049 P2 C5 · ?prompt= auto-sends an AI prompt on load (creates a version): link-triggered act
- M-050 P2 C5 · IDE clone token shown in clear twice (also in git clone URL); null renders 'null
- M-052 P3 C5 · Tenant scope/admin gate derived from role === 'TENANT_ADMIN' (header claims no r
- M-053 P2 C5 · Platform and Admin ship identical bundles (942 KB/272 KB gzip) and Studio 1048 K
- M-054 P2 C5 · Platform overview shows the company onboarding checklist to a SYSTEM_ADMIN
- M-055 P2 C5 · /platform/nope and /admin/nope answer 'Mục này nằm ở trang khác' with a button t
- M-056 P2 C5 · Failed secondary loads leave spinners forever (Builds 2 spinners, Identity KPI '
- M-057 P2 C5 · 'Tắt tài khoản/Bật tài khoản' enabled for scope.platform but has no onClick
- M-064 P3 C5 · Stale/contradictory copy: 'Lưu trữ' listed as not implemented beside a working L
- M-065 P2 C5 · People are managed in 4 screens (Công ty của tôi, Nhân viên, Người dùng, Nhóm) w
- M-066 P2 C5 · AdminApp.tsx is 1,197 lines/49 components/32 tables rendering BOTH portals; modu
- M-068 P2 C5 · Four live button systems (.btn 188, .smallButton 65, .button 61, .bx-btn 51) + ~
- M-074 P2 C5 · No log/window.onerror/report seam; request id read from JSON body only (header X
- M-075 P2 C5 · 10 independent error mappers; raw exception/server text shown ('Unexpected token
- M-076 P2 C5 · DailyBars throws RangeError on empty daily; AuditTable JSON.parse unguarded: who
- M-077 P3 C5 · Slug shown as /api/ but saved as api-2 (uniqueSlug)
- M-078 P3 C5 · Every drawer close is router.push: Back re-opens the closed drawer
- M-080 P3 C5 · 'Khôi phục' shown to everyone (fails after click); versions with 0 rows render h
- M-081 P3 C5 · 'Khám phá cấu trúc' + 'chưa' reads as one word; each enabled card is its own tab
- M-082 P3 C5 · lineDiff re-runs for every file on every render (~24 ms/keystroke on 1,800 lines
- M-085 P3 C5 · Sticky footer covers 32px under focused input (2.4.11 AAA); combobox/tree ARIA d
- M-086 P3 C5 · No forced-colors support; Inter declared but never loaded; font weights unsuppor
- M-087 P3 C5 · 100vh toolbar overlap (unverified on device); no max-width at >=1280 (1610px tab
- M-088 P3 C5 · Connector authValue and activation token/passwords stay in React state/DOM after
- M-091 P3 C5 · studio-ws and studio-ai-model survive logout (shared by the next user of the bro
- M-092 P3 C5 · stream() resets CSRF token on CSRF_INVALID but surfaces the failure instead of r
- M-095 P3 C5 · Nhóm/Chia sẻ/AI riêng appear in every admin's nav unmarked; employee detail repe
- M-096 P3 C5 · 'Cấu hình AI cho cả công ty' on a multi-company Platform; providers are global
- M-098 P3 C5 · Copy failure silent; link origin is the current portal
- M-104 P2 C5 · Studio AI mode on a phone: 3-row top bar 141 px + prompt 492 px leave the previe
- M-105 P2 C5 · Admin dialogs: fieldset.stack = UA 2px groove border, legend 16px/400, dialog ti
- M-106 P2 C5 · Workflow editor targets/branches list raw step ids (end, generated ids); jargon
- M-109 P3 C5 · PagesPanel canStep is O(n²) per render (20-32 ms at 1000 sections; beyond the 50
- M-111 P3 C5 · Expand-all has no windowing (26,073 DOM nodes for 2,000 units; 200 ms paint); mo
- M-112 P3 C5 · The phone page-scroll mode needs :has() (no fallback)
- M-113 P3 C5 · Unconditional scroll-behavior:smooth ignores prefers-reduced-motion (renderer ou
- M-115 P2 TOOLING · portals.spec hard-coded ports 3001/3002 (would read the live stack by accident)
- M-116 P3 TOOLING · Contract conformance test is silently skipped unless XWEB_CONFORMANCE_DIR is set
- M-117 P3 C5 · Links inside running text are distinguished only by colour (axe link-in-text-blo
- M-118 P2 C5 · Long project/app names overflow horizontally on 14 Studio screens (+96 px and +4
- M-119 P2 C5 · No empty-state message on 4 screens (components, system, settings, studio/new)
- M-120 P2 C5 · platform/ai/usage crashes with an empty daily series ('Invalid time value')
- M-121 P2 C5 · platform/costs shows NaN when empty
- M-122 P2 C5 · studio/new has no loading indicator and shows nothing when the request fails; ad
- M-123 P2 C5 · After a SUCCESSFUL 'Tạo công ty' focus falls to <body> (the Escape path restores
- M-125 P3 C5 · 9 visits with targets < 24 px on the Studio site screen; 4 'covered' controls on
- M-126 P2 C5 · Studio header at 600px: the search field 'Tìm ứng dụng' is squeezed to 22x36px a
- M-127 P2 C5 · Studio AI view at 768px: the starter chip 'Rút gọn tiêu đề hero' is covered by d
- M-130 P3 C5 · 'mở ứng dụng' link on /studio/activity is 86x21px at 1920 (<24px target)
- M-131 P3 C5 · 10 text leaves under 11px on Studio home (not identified)
- M-132 P3 C5 · /platform/costs shows 'Tổng đã biết $0 — đủ đơn giá' while all 3 price lines say

## CLOSED (3)
- M-124 P3 TOOLING · 18 blank and 18 console-error visits in the 9-width harness matrix (two employee
- M-128 P2 C5 · Platform 'Thêm nhà cung cấp' dialog at 360px: 'Nâng cao' is covered by the stick
- M-129 P2 C5 · Builder at 1024px with the rail 'Thành phần': the drag/add buttons of components

## PARTIAL (24)
- M-007 P1 C5 · One-time activation link lost on Enter/Esc/'Xong' (initial focus on 'Xong'); ten — C5 PART FIXED (HARNESS, merged e27318c) · C1 re-issue handoff open
- M-009 P1 HANDOFF · SYSTEM_ADMIN app detail offers 'Xóa' / 'Khôi phục vN' on workspace-scoped routes — C5 PART FIXED (HARNESS, merged e27318c) · C1 handoff + real-backend 404 not confirmed
- M-042 P2 C5 · Preview is non-interactive (sandbox) so 'try' is a list of buttons; Theme panel  — CLIENT HALF FIXED (HARNESS, 5d4c585): Test mode/preview banners say the preview is view-only and where to try; Theme panel says the theme is saved but
- M-051 P3 C5 · Access-ticket query path sent unvalidated; r.redirect goes straight to window.lo — PARTIAL (4b630de, f17f903): client half done (path sent under the server's rule; ticket redirect only https, or http only while the page itself is on 
- M-058 P2 HANDOFF · Tenants list has no search/pager (63 rows); users list has no company column/fil — C5 PART FIXED (HARNESS, merged eb38a2d): company list search + pager; C1 handoff open
- M-059 P2 HANDOFF · 'Nhà cung cấp: OpenRouter/Mô phỏng · Chưa có OPENROUTER_API_KEY' comes from lega — C5 PART FIXED (HARNESS, merged c9799bb): provider tile reads /admin/ai/providers; backend handoff open
- M-060 P2 C5 · Config/internal names shown to users (OPENROUTER_API_KEY, OIDC_ENABLED, SCIM_TOK — SHARED COPY FIXED (28d5d3f): APP_PUBLISH no longer shown, ceilings lowered (internal-constant 15->13, native dialogs 12->0); runtimeConfig DATA_API_BA
- M-061 P2 C5 · Raw enum/role/state codes shown (UPDATED, REJECTED, PASS/SKIPPED, CRITICAL, WORK — LIBRARY MERGED (9ea2e39), shared part verified (S3 Wave A): 25 maps + 'Khác' fallback + guard exist; adoption in S1/S2 call sites pending
- M-062 P2 C5 · Terminology inconsistent: workspace 105 vs không gian làm việc 14; ứng dụng/proj — PARTIAL (28d5d3f): term-project 14->10, term-model 10->9, tone-old-style 57->30
- M-063 P2 C5 · English left in UI (Components, Templates, Packages, Registry, AI Control, Desig — PARTIAL (c006077): the sidebars show the XWEB lockup (no more AI Software Factory / Company Builder Studio); legacy-name ceiling 7->4; remaining: Admi
- M-067 P2 C5 · Copy-pasted act(), load ladders (28), filter+table+pager (8-11), Field (48 hand- — COMPONENT MERGED (HARNESS, 7168d4d): <Field> in @xweb/ui, adopted on the auth pages (6 fields); ~45 call sites in features/admin and features/studio p
- M-069 P2 C5 · 386 hex (191 unique), 249 with no token, ~50% of colours tokenised, two token vo — PARTIAL (HARNESS, 8dd71bb): radius + font-size scale tokenised (206 uses, 0 of 34 snapshot screens changed); builder.css hex, globals.css, shadows and
- M-070 P2 C5 · 93 selectors defined twice, 40 with conflicts; dead hooks (sr-only, .stack, bx-h — PARTIAL (HARNESS, c67f5a1): .sr-only defined (TestPanel caption no longer shows), dead 16px checkbox sizing removed, conflicting duplicates 19->17
- M-072 P2 TOOLING · Test suite reach and reproducibility: 14 main files (~3,900 lines) reachable fro — PARTIAL (reviewed 2026-10-09): port-4000 fallback gone (HARNESS_URL required, guard test), per-OS Chrome path, shared toolkit in tests/browser/lib, BR
- M-079 P3 C5 · 'Không tìm thấy' shown twice, 'Thử lại' on a 404, light page in a dark app — PARTIAL (db5321e test only): verified 404 shows 'Không tìm thấy' once, no retry, link back, axe 0 critical/serious; the light-page-in-a-dark-app part 
- M-083 P3 C5 · Small copy: search box clipped 'Tìm ứn…'; 'Chạy / thử' wraps; nested 'Chỉ đọc' b — PARTIAL (a15246c): Studio header search gets its own full-width row at <=600 px (85/55 px -> 364/334 px at 390/360), 'Chạy thử' no longer wraps; neste
- M-084 P3 C5 · ErrorState/loading render only an h2 (no h1); CodeWorkspace has no h1; AI-mode h — SHELL ADOPTED (HARNESS, 22670eb): portal splashes/fallbacks and the not-found page render h1; CodeWorkspace/AI-mode h1 and table captions pending (S1/
- M-089 P3 C5 · Page nonce copied into the srcdoc script and postMessage target '*' (defence in  — PARTIAL (c215475): canvas postMessage pinned to the editor origin (parentOrigin, strict pattern, '*' fallback) + escaping fuzz test (300 editor docs, 
- M-093 P3 C5 · Absent permissions list is treated as allowed; failed /auth/config shows the loc — PARTIAL: (a) FIXED (HARNESS, 418fd25) a failed /auth/config shows an error with retry instead of a guessed password form (CFG01/02, platform + admin);
- M-097 P3 C5 · 9 requests for 9 keystrokes; duplicate GETs (providers 2x, limits/models 3x); us — MOSTLY FIXED (HARNESS, measured, 26b31e2): person/override searches debounced 300 ms (REQ03 7 keystrokes -> 1 request, REQ04 4 -> 1), workspace member
- M-099 P3 C5 · Dead exports (15, plus 38 needless), 4 unused imports, 9 'as never' / 2 'as unkn — PARTIAL (26f88c8): the 9 provably dead exports removed, 32 unused imports / locals removed (tsc --noUnusedLocals clean outside tests). Not proven, not
- M-107 P2 C5 · 9 shim files and 22-23 relative ../packages/... imports in S1/S2 files (3 spelli — PARTIAL (2b0fa31): everything a unit test does not load imports packages by name (3 files converted, guard computes the loaded set); the 15 relative i
- M-110 P3 C5 · Page-tree rows and canvas drag handles are not memoised (85% of selection cost a — PARTIAL (HARNESS, 9877afa): canvas drag handles memoised and found by Map; page-tree row memo/windowing and keeping the tree mounted not done
- M-114 P2 TOOLING · Only Chrome 155 (headless) was ever executed: no Firefox/WebKit/Safari run for t — PARTIAL (0a7b43f, aac54cf, 9096a53): BROWSER=chromium|firefox|webkit selector; CHROMIUM + WEBKIT runs done (WEBKIT 17 of 19 specs fully pass, evidence

## BLOCKED (8)
- M-039 P2 HANDOFF · Settings cannot clear domain/customDomain/deploymentTarget (empty values omitted — BLOCKED (handoff C2 answer): domain/customDomain/deploymentTarget cannot be cleared because the server hostname regex rejects an empty value; client p
- M-040 P2 HANDOFF · GET /ai/status is sent without workspaceId (effect runs with ws === '') and neve — BLOCKED (handoff C2 answer): GET /ai/status?workspaceId= narrowing by access needs the C2 answer before the client sends workspaceId
- M-073 P2 TOOLING · portals.sh down kills the pid in <name>.launcher without identity validation; no — BLOCKED (handoff C0): scripts/portals.sh down kills the pid in <name>.launcher without identity validation; C0 file
- M-090 P3 HANDOFF · HSTS opt-in only; no COOP/CORP/report-to; mock/static build serves no CSP/XFO — BLOCKED (handoff C0): HSTS opt-in / COOP / CORP / report-to headers are infra
- M-094 P3 HANDOFF · 'Duyệt' hidden when createdBy === me.displayName (display names are not identiti — BLOCKED (backend): 'Duyệt' visibility compares createdBy with the display name; needs a user id from the API (handoff)
- M-101 P3 HANDOFF · renderSchemaDocument mutates module-level ctx/up/site (shared with workers/rende — BLOCKED (handoff C2 worker): renderSchemaDocument mutates module-level ctx/up/site; C5 file half can follow only with the C2 worker change
- M-102 P3 TOOLING · test:unit deletes .test-build (breaks a running harness server) — BLOCKED (handoff C0): npm run test:unit deletes .test-build (outDir); C0 script
- M-108 P3 HANDOFF · HTML is no-store because of the per-request CSP nonce (fails Lighthouse back/for — BLOCKED (decision C0): HTML is no-store because of the per-request CSP nonce

## RESEARCH_ONLY (3)
- M-071 P2 RESEARCH · No i18n framework: ~4,300 hard-coded strings (Vietnamese product rule), lang=vi  — PHASE 0 MERGED (c51c705): vi-only provider, cached locale-aware formatters with identical output (0 Intl objects per 1000 cells), lang/dir from the lo
- M-100 P3 RESEARCH · Legacy root app (StudioShell, AppEntry, mock-data), live helpers beside it, comp — RESEARCH_ONLY (report only): legacy root app / live helpers inventory delivered in R docs; no code change planned
- M-103 P3 RESEARCH · vi-VN hard-coded, usd() en-US; 89 physical CSS declarations vs 16 logical; one t — RTL RATCHET MERGED (c51c705): 97 physical CSS declarations capped; formatting track folded into M-071; tenant theme not started (no contract)

