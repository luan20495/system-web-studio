> **SUPERSEDED_BY:** `docs/QA_FINAL.md` - historical document, kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# S4 matrix baseline: after the S2 AdminApp split, S3 tokens and the S1 fixes merged up to `419ffce` (+ S4 wave 3)

**HARNESS, NOT REAL BACKEND.** Every number below comes from the harness pages (`tests/browser/admin-harness.tsx`, `tests/browser/studio-app`) with in-page fakes and injected failures, Chrome 155.0.8059.40, 2026-10-09 / 10. They say what the screens do, not what a server answers. This file is the "before" for the final gate: re-run the same three commands on the final base and compare the counts per check.

Commands (all through the owned-process library; `node tests/browser/build-harness.mjs` first):

```bash
node scripts/ui-audit-harness.mjs --out DIR            # 9 widths x every route of the source inventory
node scripts/ui-state-matrix.mjs  --out DIR            # 62 screens x 7 states x 2 widths (1440, 390)
node scripts/ui-keyboard.mjs      --out DIR            # 3 keyboard-only flows at 1280
node scripts/ui-audit-selftest.mjs                     # the detectors themselves (15/15)
```

Route inventory: `registry` (`features/admin/console/sections.tsx`, 30 sections) + Studio route switch and project views = **87 routes in source, 0 not visited** (the run fails otherwise).

## 1. Responsive matrix (1920 1440 1280 1024 768 600 430 390 360)

1 422 visits (route x viewport x dialog / drawer / inspector state), 117 distinct routes and states, 158 visits per width, 18 states skipped (the system admin refused by a company screen, see the README triage notes).

| Check (visits that show it) | Count | Where / meaning |
|---|---:|---|
| horizontal overflow of the page | **0** | |
| unreachable controls (outside the viewport / clipped) | **0** | |
| controls covered by another element | 4 | `studio/projects/p1` and its views: builder rail panel while the canvas is open (S1) |
| targets under 24 px | 9 | `studio/projects/p1/site` at every width (S1) |
| visible label not in the accessible name | **0** | |
| no focus ring on the first 10 Tab stops | **0** | |
| focus fully covered by a sticky / fixed element (WCAG 2.4.11) | **0** | |
| axe critical + serious / moderate + minor | **0 / 0** | |
| console errors / uncaught exceptions | **0** | (was 18 = the artefact fixed in wave 3) |
| blank pages | **0** | (was 18) |
| failing API calls | 90 | all `GET /api/v1/component-metadata 404` on the 10 Studio project screens: the Studio harness fake deliberately answers 404 there ("a backend without the metadata endpoint"): an artefact, not a defect |
| arrow glyph characters in link text | 142 visits (9 routes) | intended `→` in links (templates, builds, ai-governance, ...) |
| page without an `h1` | 153 visits (1 route) | `studio/projects/p1/design`: the Builder has no page heading (S1: a visually hidden `h1`) |

Compared with the first full run (pre S2 split, `5b8d565`): console errors 18 to 0, blank 18 to 0 (harness artefact fixed), everything else equal.

## 2. State matrix (default / loading / empty / error / permission-denied / populated / long-content)

62 screens (Platform 24, Admin 21, Studio 17) x 7 states x 2 widths = **868 cells: 776 PASS, 32 FAIL, 60 NOT-REACHABLE** (first run on `b6d62be`: 632 PASS, 176 FAIL, 60 N/R).

| State | PASS | FAIL | NOT-REACHABLE |
|---|---:|---:|---:|
| default | 124 | 0 | 0 |
| populated | 124 | 0 | 0 |
| loading | 110 | 2 | 12 |
| empty | 100 | 10 | 14 |
| error | 108 | 4 | 12 |
| permission-denied | 110 | 2 | 12 |
| long-content | 100 | 14 | 10 |

The 32 FAIL cells (screens), each a product behaviour the harness provokes:
* long content: horizontal overflow +96 px (1440) / +434 px (390) on 7 Studio screens (`studio/`, `projects`, `new`, `templates`, `components`, `activity`, `site-access`): the long display / workspace name in the header (S1, ledger M-118..);
* empty data: no empty message on `platform/components`, `platform/system`, `platform/settings`, `studio/new`; `platform/costs` prints NaN / undefined;
* a failing request shows no error state: `admin/identity`, `studio/new`; `studio/new` also has no loading indicator and no permission-denied state.
Fixed since the first run (all OPEN then, 0 now): raw `java.lang.NullPointerException ...` text on a 500 (52 screens), raw "Access Denied" on a 403 (19 screens), the `platform/ai/usage` crash with an empty `daily`.

NOT-REACHABLE (never a pass): 60 cells = the screen makes no data request in the harness (coming sections, static pages: 48), an open screen with nothing to deny (10), the Studio component registry is a constant fixture (2).

## 3. Keyboard-only walkthrough (Tab / Shift+Tab / Enter / Space / Escape / arrows; 1280 px)

63 steps, **62 PASS, 1 FAIL**. The FAIL: Platform "Tạo công ty": after a SUCCESSFUL create the dialog closes and focus is lost to `<body>` (Escape restores it to the opener correctly) (S2). Admin create-user and the Studio select / edit / save / publish pre-check flows pass every step (reachable by Tab, visible focus on every stop, dialog focus-in / trap / wrap / Escape / restore, request sent).

## 4. Bundle size next to it (SYNTHETIC build output, `scripts/bundle-report.mjs`, same base)

First Load JS raw / gzip, before and after the entry-level lazy console (M-053 step 1): Platform and Admin 866.6 / 251.3 KB to **549.2 / 167.6 KB** (-317.4 KB, -36.6 %); Studio 989.0 / 294.9 KB to **562.4 / 172.0 KB** (-426.6 KB, -43.1 %). Raw output: `S4-baselines.md` method, numbers in the wave-3 report.
