# C6 — Targeted retest of the C5 final candidate `agent/c5-web @ 40ee45bc16fe4dda525c90e0d491d11284a23d8b`

2026-10-08. Supersedes `5cc230e`. Nothing from the previous PASS list was reused: every item below was re-run on this SHA. No production code changed, nothing committed.
Evidence: `docs/parallel/c6/evidence/ui-ux-regression/40ee45bc16fe/` (874 case screenshots, `cases.json`, `retest.json` + `retest/`, `process-safety/`, `s4-regression/`, `bugs-final.tsv`). Harness: `ui-ux.mjs`, `ui-retest.mjs`, `ps-safety.sh`, `ui_analyze.py`, `ui-seed.mjs`.

**Setup.** Worktree `/Users/hoangluan/code/xweb-c6-c5` at exactly `40ee45b`, three portals production-built from it, on the C6 stack (backend RC `62ce9697cd56`, unchanged by C5). Note on a trap I fell into: `scripts/portals.sh` (C0) reuses an existing `.next` when only the *source* changed (its stamp hashes ports/env, not code), so my first run served the old build and still showed UX-001; I deleted `.next` and rebuilt before any result below was recorded.

## Summary
| Item | Result |
|---|---|
| UX-001 Data crash | **PASS** |
| UX-002 builder ≤ 430 | **FAIL** (canvas/toolbar/skip link fixed; the rail panel and inspector are unusable on a phone — P2, C5 records it as PARTIAL) |
| UX-003 Data/Action rail | **PASS at 1024 / 768, FAIL at 430 / 390** (same root as UX-002) |
| UX-005 costs table keyboard | **PASS** |
| UX-006 / UX-008 target size | **PASS** |
| UX-007 rail contrast | **PASS** |
| AXE | **critical 0 · serious 0** over 874 cases (moderate 32, minor 47 nodes) |
| PROCESS SAFETY | **PASS** (foreign Next survives, stale PID safe, no machine-wide kill) |
| S4 final-merge regression | **PASS** |

## 1. UX-001 — PASS
`ui-retest.mjs` R1, real Studio, 1440 / 768 / 430 / 390, `pageerror` and `console.error` captured:
| Shape | Result |
|---|---|
| `transforms` missing (the exact original trigger, also the seeded main project) | **no crash, 0 page errors, 0 console errors** × 4 viewports |
| `transforms: []` | PASS × 4 |
| valid transforms (`trim`, `upper`, `lower`) | PASS × 4 |
| ViewModel with no `fields` key / `fields: []` (not tied to a mapping) | PASS × 4 each |
| `transforms: null` | **N/A**: the server refuses it (422 "must not be null"), so it cannot be stored; C5's unit test `transforms-safe.test.tsx` covers the reader (part of the 305 passing unit tests) |
| mapping without `fields` / `fields: []` | **N/A**: the server refuses it (422 "a mapping needs at least one field") |
Both the Data rail and the Inspector (section with a binding) were opened in every case.

## 2. Accessibility — PASS (own axe-core 4.14, tags wcag2a/aa, 21a/aa, 22aa, best-practice)
Full run, 874 cases = 87 role×route combinations × 7 viewports plus dialogs, builder rails/modes, states, keyboard, navigation: **AXE_CRITICAL 0, AXE_SERIOUS 0**. The four rules named in the task:
| Rule | Before (5cc230e) | Now |
|---|---|---|
| label-content-name-mismatch | 9 nodes (Studio members, all viewports) | **0** |
| scrollable-region-focusable | 9 (costs ≤ 430) | **0** |
| target-size | 7 (Studio project views @768) | **0** |
| color-contrast | 4 (builder rail "Thành phần") | **0** |
Also 0 horizontal page overflow and 0 controls under 24px on any route at any viewport; 0 broken text, glyph or icon. (The harness reported 28 "raw/undefined text" cases: they are my own test project names containing the word "null" — false positives.)

## 3. UX-002 builder ≤ 430 — FAIL (P2)
Measured at 430 / 390 / 360 on the real builder:
* **Canvas: much better.** 560 / 540 / 512 px high = 60–64% of the viewport (was 166px, 18–20%); the page tree no longer overlaps it; a section can be selected by tap/click.
* **Skip-to-preview works:** it is the 16th Tab stop (after the 15 toolbar controls), becomes visible on focus (top 8px), Enter moves focus to `#bx-canvas`, which is on screen.
* **Toolbar:** all 15 controls exist and are keyboard-reachable, but the mode/device row is a horizontal scroller (scrollWidth 526 inside 340–410px): "Dùng thử" and the three device buttons are off-screen at 390 and fully hidden at 360, with no cue (UX-014, P3).
* **Rail/panel and inspector: not usable.** With a rail open the rail region is 50px (390) / 85px (430) high, the panel window inside it **24px** (content ≈ 1,900px), the inspector sheet 75–110px; the canvas bottom edge overlaps the rail tab row (`retest/r4pre-Dữ liệu-390.png`, `r4pre-Hành động-430.png`; measurements in `retest.json` R3 and the probe in this report). Data, Action, Workflow and Page panels cannot be read or used on a phone, and nothing in the product says so. C5 itself rates UI-21 "PARTIAL (a phone is not a full editor)". This is not an ACCEPTABLE_LIMITATION as shipped (silent), so: **FAIL, P2, UX-002b → C5-S2** — either give the panel real space (e.g. canvas/panel toggle) or show an explicit "mở trên màn hình rộng hơn để chỉnh sửa" notice.

## 4. UX-003 Data / Action / Workflow rail — PASS ≥ 768, FAIL ≤ 430
| Viewport | Result |
|---|---|
| 1024 | PASS: panel 329px, tabs not clipped, active tab distinct, no word-per-line text, no overlap, inspector usable (screenshots `retest/r4-*`) |
| 768 | PASS (two-column: rail+tree left, canvas right, inspector docked at the bottom — readable, Data/Action cards no longer crushed) |
| 430 / 390 | **FAIL**: same cause as §3 (panel window 24px, tab row partly under the canvas). My automated rail check passed these cases and was wrong; the measurement and the screenshots above decide |

## 5. UX-005 costs table — PASS
* 390: the three overflowing table regions are `tabindex=0`; Tab reaches them; a visible focus ring; **ArrowRight ×2 increases `scrollLeft`**; axe scrollable-region-focusable 0.
* 768 / 1440: no overflow → no `tabindex` (no unnecessary tab stop).
* Same behaviour on `/platform/audit`, `/users`, `/components` at 390 and 768 (tab stop exactly while overflowing).

## 6. UX-006 / UX-008 target size — PASS
No interactive control below 24×24 CSS px at 768 / 430 / 390 (and 360, 1024, 1280, 1440) on any of the 87 routes, dialogs, builder rails and Studio project views; axe `target-size` 0. (Before: selects 20–22px, switches 22px, links 18–21px.)

## 7. UX-007 rail contrast — PASS
Builder rail, 8 tabs: normal / hover / active (aria-selected) / keyboard focus — computed foreground/background ratio **11.29–11.66:1** (minimum 11.29:1); no disabled tab exists to test; axe `color-contrast` = 0 on the page with Trang, Dữ liệu and Thành phần open.

## 8. Process safety — PASS (`process-safety/`, `ps-safety.sh`)
Independent scenario on this HEAD with real Next servers:
* **Foreign Next A** (`npx next start -p 3499`, started by hand; launcher pid + `next-server` pid) and **C5-owned B** (`owned-process-cli.mjs start … npx next start -p 3498`, same command shape). C5 cleanup (`stop --state`) → **B stopped (port free, pid gone), A alive and answering 200** — `FOREIGN_NEXT_SURVIVES: PASS`. The other Next servers on the machine (public portals 3201–3203, C0's 3301–3303, my own 3411–3413) were also untouched (process lists before / during / after in `process-safety/ps-safety.log`).
* **Stale PID reuse:** state files pointing at A's pid with (a) B's start time, (b) A's start time but B's command, (c) a signal STOP on that pid → all **REFUSED (exit 4), nothing signalled, state file kept, A still answering**; a state whose process is gone → ALREADY_GONE. `STALE_PID_SAFE: PASS`.
* **Harness lifecycle:** `harness-server.mjs run` (success and a failing command, exit code 7 propagated) leaves no server behind; `e2e-stack.sh down` with four stale state files naming A's pid refuses each ("pid belongs to someone else"), A untouched. `HARNESS_PID_SCOPED: PASS`.
* **Static scan** of C5-owned executable tooling (`tests/`, `e2e/`, `docs/parallel/c5/`, C5 `scripts/ui-*`): no `pkill`, `killall`, kill-by-port, `kill $(cat pid)`, `xargs kill` → `UNSAFE_EXTERNAL_CLEANUP_REMOVED: YES`.
* C5's own 21 process-helper tests also pass (21/21).
Not covered (also C5's stated limit): the real gradle-backend `adopt` path of `e2e-stack.sh up`. Finding UX-017 (P3): `e2e-stack.sh down` exits 1 *silently* before stopping anything when `stack.env` is missing (`die` inside `need_env` with stderr hidden).

## 9. S4 final-merge regression — PASS (on `40ee45b`)
`tsc` ×2 (`tsc`, `typecheck:all`) exit 0 · `npm run test:unit` **306 tests, 305 pass, 0 fail, 1 skipped** (includes the owned-process wire test) · owned-process 21/21 · browser specs through `harness-server.mjs run` (new lifecycle): sanity 8/8, builder 80/80, datasources 54/54, publicdata 44/44, aiproviders 27/27, provisioning 39/39, page-runtime 37/37, release 56/56, org 89/89, org-hardening 69/69 · **portals spec 33/33** with the three apps started and stopped through `owned-process-cli.mjs` (ports 3001–3003, freed afterwards) · 0 harness servers left behind.

## 10. Moderate / minor a11y (not serious) — classification
| Finding | Route | Impact | Should it be fixed before final UI sign-off? |
|---|---|---|---|
| heading-order (h1 → h3, no h2) | `/admin/organization`, `/admin/data-sources` | moderate, advisory (UX-015) | recommended, trivial; not blocking |
| empty table header (actions column) | `/admin/company`, `/admin/my-workspaces`, `/platform/tenants/{id}` | minor (UX-016) | recommended, trivial (visually hidden "Thao tác"); not blocking |
Owner **C5-S2**. Other moderate/minor nodes (32 / 47) are the same families.

## 11. Return
```
CANDIDATE_SHA: 40ee45bc16fe4dda525c90e0d491d11284a23d8b
UX-001: PASS
UX-002: FAIL
UX-003: FAIL        (PASS at 1024/768; FAIL at 430/390)
UX-005: PASS
UX-006: PASS
UX-007: PASS
UX-008: PASS
AXE_CRITICAL: 0
AXE_SERIOUS: 0
PROCESS_SAFETY: PASS
UNSAFE_EXTERNAL_CLEANUP_REMOVED: YES
HARNESS_PID_SCOPED: YES
FOREIGN_NEXT_SURVIVES: PASS
STALE_PID_SAFE: PASS
S4_FINAL_MERGE_REGRESSION: PASS
NEW_P0: 0
NEW_P1: 0
NEW_P2: 1        (UX-002b: rail panel / inspector unusable at ≤ 430, remainder of UX-002/003)
OPEN_MINOR_A11Y: UX-015 heading-order (organization, data-sources); UX-016 empty table header (company, my-workspaces, tenant detail); P3 UX-014 toolbar scroller without cue; P3 "Tạo ứng dụng" aria-current; P3 UX-017 e2e-stack down silent exit
READY_FOR_UI_SIGNOFF: NO
READY_FOR_C0_IMPORT: YES   (no regression, the P1 and the axe gate are fixed, process tooling is safe; the phone-builder P2 stays open for C5-S2 and for the final sign-off)
```
