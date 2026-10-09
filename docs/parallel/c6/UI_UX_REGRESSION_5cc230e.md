# C6 — Visual / UX regression of the C5 candidate `agent/c5-web @ 5cc230e491a6d8b0135cbd7eb132793368a45073`

2026-10-08. Independent run; C5's own evidence was used only as a comparison. No production code was changed, nothing committed.
Evidence: `docs/parallel/c6/evidence/ui-ux-regression/5cc230e491a6/` (866 case screenshots, `cases.json/.tsv`, `summary.json`, `bugs-final.tsv`, `verify-fixes.json`, `verify/`, `sheets/` for manual review, `unicode-scan.json`, `visual-diff.tsv`). Harness: `docs/parallel/c6/harness/ui-ux.mjs · ui-verify.mjs · ui-sheet.mjs · ui-compare.mjs · ui_analyze.py · ui-seed.mjs · ui-unicode-scan.mjs`.

## What was tested
* **Frontend under test:** worktree `/Users/hoangluan/code/xweb-c6-c5` at exactly `5cc230e` (3 portals production-built, ports 3411–3413). `integration/v2` has since moved to `26c3c87` (C0 imported this candidate); the frontend there is identical (`git diff 5cc230e 26c3c87` touches only `package.json` scripts), so the findings apply to it. The final sign-off inputs (`C5_FINAL_HEAD`, `INTEGRATION_SHA`, `PUBLIC_URLS`) were not given: this is the candidate run, not the final sign-off (`ui-final.sh` refuses to run without them).
* **Backend:** RC `62ce9697cd56` (C5's diff has no backend change), real stack, realistic Vietnamese fixture created through the API (3 tenants incl. one suspended and one with a 74-character name, 25 members with diacritics / a 90-character name, 9 apps with `"…" <test> & 'quote'` names, a published app with two releases, a data source on the TLS target, query, slot, action, workflow).
* **Browser:** Google Chrome 154.0.8037.98 only (no Firefox/WebKit available here — stated limitation).
* **Coverage:** 87 distinct role×route combinations (Platform, Admin as tenant admin / workspace admin / system admin, Studio incl. 9 project views, builder rails/modes, auth and unknown-route pages) × the 7 viewports 1440 · 1280 · 1024 · 768 · 430 · 390 · 360 = **866 cases** (route renders, dialogs, builder variants, simulated loading/empty/error states, keyboard, navigation). Every case: full-page screenshot, in-page audit (overflow, clipped text, controls outside the viewport, clipped by container, overlap, touch target, small text, navigation reachability), Unicode/glyph test (canvas-based missing-glyph detection per character and font), icon test, **axe-core 4.14** (wcag2a/aa, 21a/aa, 22aa, best-practice).
* **App Creator** (Studio gate refuses it: H-C1-04) — 56 cases BLOCKED, not counted as failures.

## Result
| | |
|---|---|
| Cases | 866 → **PASS 721 · FAIL 89 · BLOCKED 56** (after removing 3 documented harness false positives: display:none icon, internally scrolling drawer, anonymous visit; unfiltered 445/365/56 in `summary.json`) |
| By viewport (fails) | 1440: 5 · 1280: 2 · 1024: 3 · **768: 27 · 430: 15 · 390: 19 · 360: 18** |
| KNOWN_NOT_READY | 13 cases (Organization, Nhóm, Chia sẻ, AI riêng, create buttons) — all show an explicit "Chưa sẵn sàng" notice, no invented data, no internal team names |
| Axe | **critical 0 · serious 29 nodes** (4 rules: label-content-name-mismatch 9, scrollable-region-focusable 9, target-size 7, color-contrast 4) → **gate not met** |
| Unicode | 0 broken text in 866 renders (no U+FFFD, mojibake, lone surrogate, non-NFC, missing glyph); literal scan of rendered text/aria/placeholder: the 3 hits `Ã`/`Â` are legitimate Vietnamese capitals produced by CSS uppercase (ĐÃ, NHÂN); 63 of 67 Vietnamese letters appear on the pages (missing: ẳ ỳ ỷ ỵ — no page uses them) |
| Icons | all nav links carry Lucide SVG (Platform 16/16, Admin 10/10, Studio 6/6), 0 text glyphs used as icons, 0 broken images |

## 1. C5's major fixes — independently verified (`verify-fixes.json`)
| Claim (UI_UX_AUDIT) | C6 result |
|---|---|
| UI-10 initials | **VERIFIED**: sidebar/home avatars `LO`, `NG`, `TR`; directory 17 avatars all 1–2 letters, derived from the name (Đ→D, "Nguyễn Văn Ưu Tiên Đặc Biệt…" ok, "Nhân viên đã nghỉ việc" ok), no brackets/digits/'?' |
| UI-01 Lucide icons, no □ | **VERIFIED** (see above) |
| UI-02 mobile menu | **VERIFIED with a note**: no sidebar < 900px, menu button present, Enter opens, Escape closes and returns focus, backdrop closes, picking a link closes (all three portals). Note UX-009 (P3): focus is not moved into the drawer and the page behind is not inert |
| UI-04 wide tables | **PARTLY**: no page overflow at any of the 7 viewports on any route and tables scroll inside their card, **but** the scroll regions are not keyboard-focusable (UX-005, axe serious on /platform/costs) |
| UI-17 builder toolbar | **VERIFIED at ≥ 768** (1440: one row 58px; 1024/768: two rows); **not at ≤ 430** → UX-002 |
| UI-14/15 employee debounce | **VERIFIED**: typing 10 characters sends 0 further requests (list is filtered client-side from one fetch), typed text intact, slow typing returns the matching rows |
| UI-07 modal behaviour | **VERIFIED on Create Company** (7/7: Enter and Space open, focus moves in, Tab ×12 and Shift+Tab ×6 stay inside, scroll lock, Escape closes, focus returns to the trigger); dialogs/drawers of Platform, Admin, Studio at 4 viewports each: role, name, trap, Escape, fit all pass except the target-size findings |
| UI-16 / UI-19 NOT_READY | **VERIFIED** for Organization (KNOWN_NOT_READY H-C1-17: warning box, disabled "Thêm đơn vị gốc"), Nhóm, Chia sẻ, AI riêng, employee create. UX-011: other pages still print internal names (Backups) |
| "axe serious 0 / page wider than 390: 0" | overflow **confirmed 0**; axe serious **NOT 0** (29 nodes) — C5's claim is not reproduced |
| UI-03 builder tree critical | **VERIFIED**: 0 critical anywhere |
| (crash) | **UX-001 P1: the Data rail still crashes on a mapping without `transforms[]`** (same TypeError as the RC) |

## 2. Manual inspection (screenshots / contact sheets at 1440·1024·768·430·390)
* **Long tail (C5 said incomplete) — Platform AI, Security, Cost, Alerts, Backup/Settings: inspected by eye at all five viewports.** Layout, hierarchy, Vietnamese text, empty states and Lucide icons are clean; findings: UX-005 (costs table region), UX-010 (header wraps at ≤430), UX-011 (English KPI labels; internal script/env names in Backups copy). Only the first tab of AI (Nhà cung cấp) was looked at per viewport.
* **Create Company** (Platform): dialog at 1440/1024/390/360 — slug auto-fill, searchable first-admin picker with initials, long names truncate with ellipsis, footer reachable, bottom sheet on phones. PASS.
* **Admin** dashboard / Công ty của tôi / Cơ cấu tổ chức / Nhân viên (table → cards at ≤ 768) / Nguồn dữ liệu: clean; UX-012 (a button cut at 390).
* **Studio** projects, builder, inspector, publish dialog (all viewports readable), history: builder rails Dữ liệu/Hành động too cramped at desktop widths (UX-003); builder at 390/430 → UX-002.

## 3. Known open items — classified, not hidden
| Item | Classification |
|---|---|
| Dynamic Organization backend (H-C1-17) | **KNOWN_NOT_READY** — UI behaves correctly (explicit notice, nothing fabricated) |
| Builder canvas at 390 px | **P2 UX_LIMITATION confirmed (UX-002)**: canvas 166px = 18–20% of the viewport under 189px of toolbars, tree overlaps it; selection still works, so usable but materially hard |
| `components/StudioShell.tsx`, `lib/mock-data.ts` | **not examined for removal**; the production builds of all three portals succeeded and no route or console error referenced them (no runtime/build effect observed) |

## 4. Bugs (route to C5; `bugs-final.tsv` has the required fields for each)
**NEW_P0: 0 · NEW_P1: 1 · NEW_P2: 7 · NEW_P3: 5**
| ID | Sev | Portal · route | Viewport | Summary |
|---|---|---|---|---|
| UX-001 | P1 | Studio · design → Dữ liệu | all | white-screen English "This page couldn't load" on a server-valid mapping without `transforms[]` (DataWizard.tsx:203, Inspector.tsx:85) |
| UX-002 | P2 | Studio · design | 430/390/360 | canvas 166px high under 4 toolbar rows; overlaps the page tree |
| UX-003 | P2 | Studio · rails Dữ liệu / Hành động | 1440/1024/768 | 230px rail: text wraps one word per line, table columns clipped |
| UX-004 | P2 | Studio · /projects/{id}/members | all | axe serious label-content-name-mismatch |
| UX-005 | P2 | Platform · costs (+ audit/users/components) | ≤ 430 | axe serious scrollable-region-focusable |
| UX-006 | P2 | Studio · ai/assets/members/publish/settings/site | 768 | axe serious target-size |
| UX-007 | P2 | Studio · rail Thành phần | 1440/1280 | axe serious color-contrast |
| UX-008 | P2 | all | ≤ 768 | targets < 24px: selects 20–22px, switches 22px, links 18–21px (WCAG 2.5.8) |
| UX-009 | P3 | all · mobile drawer | ≤ 768 | focus not moved in, background not inert |
| UX-010 | P3 | Platform | ≤ 430 | header buttons wrap to extra rows |
| UX-011 | P3 | Platform · security, backups | all | English KPI labels; internal script/env names in user copy |
| UX-012 | P3 | Admin · company | 390/360 | "Gỡ" button cut at the edge |
| UX-013 | P3 | Studio · sidebar | 1440 | CTA link not aria-current |

## 5. Harness notes (what is not a product finding)
The first full runs were invalidated and discarded when the C6 stack's API process was killed by the OS mid-run (every page then showed the Next error screen); the harness now waits for the stack and restarts it. A first fixture without `transforms[]` revealed UX-001 and is kept as evidence (`_pilot-pass1-fixture-mapping-without-transforms/`, RC frontend). The RC-frontend run (`_baseline-rc-62ce9697cd56-partial/`, `_pilot-pass1-…`) was used as the visual baseline: `visual-diff.tsv` shows 567 of 912 shots changed > 2% (expected: icons, drawer, controls), 135 new — a review list, not a defect list. False positives removed in `ui_analyze.py` are listed in its header and were each checked in the browser. Studio drawer scripted cases timed out on a late step; each behaviour was verified by hand (`verify/studio-drawer-manual.txt`).

## 6. Final
```
CANDIDATE_SHA: 5cc230e491a6d8b0135cbd7eb132793368a45073
ROUTES_TESTED: 87 (866 cases at 7 viewports)
MANUAL_LONG_TAIL_AUDIT: FAIL   (done for AI, security, cost, alerts, backup: P2 UX-005, P3 UX-010/011)
UNICODE: PASS
ICONS: PASS
RESPONSIVE: FAIL
A11Y: FAIL
AXE_CRITICAL: 0
AXE_SERIOUS: 29
CREATE_COMPANY: PASS
ORGANIZATION_UI: KNOWN_NOT_READY
EMPLOYEE_DIRECTORY: PASS
STUDIO: FAIL
BUILDER_390: UX_LIMITATION
NEW_P0: 0
NEW_P1: 1
NEW_P2: 7
NEW_P3: 5
BUGS: UX-001 (P1) … UX-013 (see §4)
READY_FOR_UI_SIGNOFF: NO
```
