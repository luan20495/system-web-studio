# C5 — targeted fix after C6 QA @ 5cc230e (2026-10-08)

Real Chromium against the real stack (integration `ae0432f`, nothing mocked) except where stated. Tools: `scripts/ui-audit.mjs` (all routes, dialogs, inspector tabs, builder rail states; axe tags `wcag2a, wcag2aa, wcag21a, wcag21aa, wcag22aa, best-practice`, no rule disabled), `scripts/ui-c6-evidence.mjs`.

## axe / detectors (74 routes/contexts × 1440, 768, 430, 390 = 308 visits "after"; 231 visits "before" at 1440, 768, 430)
| | Before (5cc230e build) | After |
|---|---|---|
| axe critical (nodes) | 0 | **0** |
| axe serious (nodes) | 5 (2 color-contrast, 2 scrollable-region-focusable, 1 target-size) | **0** |
| controls < 24 px (independent detector) | 146 nodes / 70 visits | **0** |
| accessible name does not contain the visible text (independent detector) | 9 nodes / 6 visits | **0** |
| page wider than the viewport | 0 | **0** |
| console errors / failing API calls | 0 | 0 |

Files: `axe/before-summary.json`, `axe/after-summary.json`, `axe/*-audit.md`.
Honest note: C6's exact 29 nodes were not visible to C5 (they came from a wider set of contexts and rules). The audit was widened to all tags above plus dialogs, inspector tabs, builder rail states and two independent detectors; on that wider set the "before" build had 5 serious nodes and the "after" build has 0. The name-mismatch detector was refined after the "before" run (element boundaries count as spaces, punctuation ignored, `<label for>` counted, `.srOnly` mirror ignored), so the "before" figure for that detector (9) includes some false positives; the one real mismatch found after the refinement was the Members "Rời" button (`aria-label="Xóa <user>"`), now fixed.

Real defects found by the wider run and fixed: dialog `<select>`s were 20 px high (dialogs are portaled outside `.shell`, so they missed the control rules); the Studio AI / Code workspace header overlapped the content at ≤ 900 px (axe target-size "partially obscured"); icon buttons shrank to 18 px in the builder bar; text link buttons were 18 px high; the builder brand pushed the page 2 px wider on a phone with a long project name; on a phone the publish button was cut off at the right edge of a scrolling row (now the action row wraps).

## UX-001 (`ux-001/`)
- `before-5cc230e.txt`: unmodified baseline, a mapping field without `transforms[]` → `TypeError: Cannot read properties of undefined (reading 'length')`.
- `after-fix.txt`: same regression test passes. Tests: `tests/builder/transforms-safe.test.tsx` (transforms undefined, null, `[]`, valid, legacy object, garbage; DataWizard SSR with bare / null / missing-fields mappings; reading never rewrites stored data).
- Real Studio: a query and a mapping whose fields have no `transforms` were written through the product's own `PATCH /schema` (HTTP 200); the Data rail at 1440 / 768 / 430 / 390 opens, lists "Ánh xạ không biến đổi — price, name", 0 page errors, 0 console errors (`after/evidence-run.txt`, `after/data-rail-*.png`).

## UX-002 / UX-003 / UX-006 / UX-007 / UX-008 (`after/`)
- Builder at 1440 / 768 / 430 / 390: `builder-*.png`, `builder-430-full-height.png`. Toolbar height 58 / 101 / 177 / 179 px; canvas first on phones; rail = wrapping tabs, none clipped, tabs ≥ 36 px high at ≤ 768; page overflow 0; device switch = 3 named icon buttons ≥ 36 px; all actions reachable (the action row wraps, nothing hidden behind a scroll).
- Data rail: `data-rail-*.png`; rail tabs fully readable, active state visible.
- Rail contrast uses the token `--muted-strong` (#c4cbd6) instead of the generic muted colour; axe color-contrast 0.
- Controls < 24 px: 0 on every audited route and state.

## UX-005 Costs table (`after/costs-focus-390.png`)
The stack has no cost measurements (the report is empty), so ONLY the `GET /admin/costs` response was replaced in the browser with realistic rows; the page, tables and keyboard behaviour are the real build. At 390 px the three tables scroll sideways: each card is `role="region"` + `tabindex=0` + `aria-label="Theo phòng ban (cuộn ngang được)"`, reached by Tab, 2 px solid focus outline, ArrowRight scrolls it (scrollLeft 0 → 80). At 768 / 1440 the tables fit, so the card is NOT a tab stop (by design, no unnecessary tab stops).

## Limitations (not hidden)
- Builder at ≤ 430 is a compromise, not a mobile editor: canvas first (≈ 64 % of a 800 px screen below a 177 px toolbar), the rail and panel sit below it and are reached by scrolling the builder body; section drag handles stay outside the canvas frame.
- The cost-table evidence uses a stubbed response (above).
- Dynamic Organization stays NOT_READY (no change in `features/admin/organization.ts`).
