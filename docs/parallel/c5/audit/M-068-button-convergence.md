# M-068 — one button vocabulary (2026-10-09)

Evidence class: HARNESS, NOT REAL BACKEND (Chromium). Result: `.button`, `.smallButton` and `.bx-btn` are gone from every `className` (198 uses, 29 files, all in Studio) and from every stylesheet. Only `.sendButton` (the AI composer's round send button) remains a component class.

## Mapping (exact, by whole className string; 13 distinct strings, no other string was touched)
| was | now |
|---|---|
| button / button primary / button ghost / button icon / button wsMoreBtn | btn / btn primary / btn ghost / btn icon / btn wsMoreBtn |
| smallButton / smallButton danger | btn sm / btn sm danger |
| bx-btn / bx-btn primary / bx-btn danger | btn dense / btn dense primary / btn dense danger |
| bx-btn sm / bx-btn sm danger / bx-btn primary sm | btn dense sm / btn dense sm danger / btn dense primary sm |

`dense` is the builder's compact size (28 px, 13 px text, 9 px radius), documented in factory.css "BUTTON VOCABULARY". Only the class strings changed: `type`, `disabled`, handlers, `aria-*`, loading and destructive wiring are untouched (the diff is 198 className edits plus CSS and tests).

## Where the dark skin applies
`:is(.studio, .modal, .drawer) .btn…` (was `.studio .btn…`): the Studio's overlays (`.modal`, `.drawer`) are dark by their own CSS and the release harness renders the modal without a `.studio` ancestor. Without this the publish dialog's buttons rendered light-on-dark (found by css-snapshot + release spec, fixed). Nothing in Platform/Admin uses `.modal`/`.drawer`.

## Method: computed-style probe (scratch tool, not committed)
For 19 ancestor contexts (every selector that CSS used to key on: workspace, top bars, builder rail, bx-dialog, bx-ds, bx-form, previewToolbar, aiProgress, the light Admin shell and its bx-ds / bx-dialog), 12 legacy→new pairs, 3 widths (1280 / 800 / 390) and 4 states (normal, disabled, focus, hover), 35 computed properties were compared between the legacy class and its replacement in the SAME CSS build before the legacy CSS was removed. 2736 comparisons. Parity was reached by adding the legacy rules' parts that `.btn` lacked (icon min-width / flex, sm min-width and gap, ghost hover, dense, `.btn.sm`/`.icon` touch minimum on phones, the dark focus ring, `.topActions` visibility rules, `.aiProgress` placement, `.bx-form>.btn`).

## Intentional differences (everything else computes identically)
- Disabled `.smallButton`: now opacity .5 + not-allowed (it looked enabled before: a defect).
- Hover: `.smallButton` / `.bx-btn` gain the same border highlight `.button` already had; `.button.primary` no longer turns its border grey on hover.
- Focus ring in the dark Studio keeps the bright ring (`--ui-ring`); the light shells keep the indigo ring.
- Platform/Admin data-source panel (`admin-ds` snapshot): outlined buttons use the control border (M-027, 3:1) and the danger button a red border, instead of the old pale `--f-line` border.

## css-snapshot (36 screenshots, before 62637db vs after)
| screen | result | class |
|---|---|---|
| release-dialog desktop / phone | changed in the first run (buttons invisible) → fixed → identical | REGRESSION, fixed |
| admin-ds desktop / phone | border contrast + danger border | INTENTIONAL |
| datasources desktop / phone | the harness now mounts the panel inside `.studio` like the app (a hint line is lighter, buttons identical) | INTENTIONAL (harness fidelity) |
| org desktop | hash differs, screenshot identical by eye | NON-DETERMINISTIC hash |

## Tests
unit (480 pass, 0 fail, 1 skip) incl. the new guard "legacy button classes are gone" (design-tokens.test.ts; any `smallButton` / `bx-btn` token in a string, any `.button` / `.smallButton` / `.bx-btn` in CSS fails); classify OK; typecheck root / apps / packages; ui-tokens 43/43 (the dark builder skin now asserted against documented values, 8 variants); ui-widgets 54/54; shared-ui 80/80; ui-route 16/16; builder 111/111; studio-wave3 162/162; studio-p1 16/16; studio-wave2 31/31; release 61/61; datasources 54/54; data-binding 34/34; admin 176/176; org 89/89; provisioning 39/39; aiproviders 27/27 (the last six ran on the pre-fix CSS; the `:is()` scope change touches only `.studio/.modal/.drawer` descendants, none exist in those pages).
`hooks.spec` is timing-sensitive: under this machine's load (load average ~40 from other teams' processes) it fails 1-3 of 20 checks at random on BOTH the old tree (62637db: 20/20, 19/20, 18/20) and the new tree (18/20, 16/20, 19/20, 17/20, 18/20); it passed 20/20 on a quiet machine at 72ad63a. Not a regression; to be re-run on a quiet machine at the final gate.
