# C5 — phone builder: ONE workspace at a time (C6 UX-002b / UX-003), 2026-10-08

Real Chromium against the real stack (integration `ae0432f`), nothing mocked. Tool: `scripts/ui-c6-mobile.mjs` (57 checks, `mobile-run.txt`); regression guard without a stack: `tests/browser/builder.spec.mjs` (PHONE checks, 87/87).

## What C6 saw (390 / 430)
Canvas, rail, panel and Inspector were stacked in one scrolling column: the rail ~50–85 px, the panel only ~24 px visible below the canvas, the Inspector ~75–110 px. Not operable.

## Layout: CANVAS_PANEL_SWITCH (≤ 760 px only)
A sticky 3-way switch (`Bản xem trước | Công cụ | Thuộc tính`, ARIA tabs, 44 px targets) shows ONE workspace at full width:
- **Bản xem trước**: the canvas, ≈ 90 % of the screen high.
- **Công cụ**: the rail (8 wrapping tabs, ≥ 36 px) + the panel (Trang / Thành phần / **Dữ liệu** / Biểu mẫu / **Hành động** / **Workflow** / Giao diện / AI) at full width, natural height; the page scrolls (the toolbar scrolls away).
- **Thuộc tính**: the Inspector (or the Test panel in test mode) at full width; a dot on the tab shows that a section is selected.
All three stay mounted (CSS hides two), so nothing reloads or resets: typed text, the active rail, the selection and the canvas iframe survive Canvas → Công cụ → Thuộc tính → Canvas (checked: the same panel node, the same iframe + a marker inside the frame). Opening the data wizard / a new action from the Inspector switches to Công cụ and moves focus to that tab. ≥ 761 px: the switch is `display:none`, the 2 / 3 column layouts are unchanged (measured at 768 / 1024 / 1440: all panes shown, same grid columns).

## Screenshots (this folder)
390: `390-canvas.png`, `390-tools-data.png`, `390-tools-action.png`, `390-tools-workflow.png`, `390-tools-pages.png`, `390-properties.png`
430: `430-canvas.png`, `430-tools-data.png`, `430-tools-action.png`, `430-tools-workflow.png`, `430-tools-pages.png`, `430-properties.png`
No regression: `768-builder.png`, `1024-builder.png`, `1440-builder.png`.

## Measured (390 / 430)
Tools pane 390 / 430 px wide, panel content ≥ 366 / 406 px (was ~24 px visible), Data panel 1405 px tall, no control outside the screen or < 24 px, no page overflow in any view, Inspector 390 / 430 px wide and ≥ 784 px tall, every Inspector tab opens without overflow. Keyboard: ArrowRight / Home / End on the switch move selection AND focus, 2 px focus ring, skip link reachable; `scroll-padding-top` keeps a focused control from sitting under the sticky bar.

## axe (tags wcag2a, wcag2aa, wcag21a, wcag21aa, wcag22aa, best-practice; EVERY impact reported)
- `axe/full-audit-308-visits.md`: 74 routes/contexts × 1440, 768, 430, 390 = 308 visits, the phone rail / inspector / test-mode states now visited in the right workspace: critical 0, serious 0, controls < 24 px 0, name mismatches 0, overflow 0; the only finding was one moderate heading-order (Test mode panel on a phone) — fixed afterwards.
- `axe/studio-final-audit-132-visits.md`: Studio only, final build: 0 violations of any impact.
- Every phone view in `mobile-run.txt` (canvas, Dữ liệu, Hành động, Workflow, properties): axe 0 violations.
- Minor a11y fixed: heading order on `/admin/organization`, `/admin/data-sources` (panels take a heading level); empty table headers on `/admin/company`, `/platform/tenants/{id}` and the application versions table (`<th>` now has a hidden "Thao tác").

## Limitations (not hidden)
- A phone is a "look and adjust" editor, not a full one: dragging a library item onto the canvas across two views is not possible (use the Thêm button / keyboard move up-down; both exist).
- The toolbar (up to ≈ 230 px with the wrapped actions) sits above the switch and scrolls away.
- Not run on real iOS Safari / Android Chrome (dvh, pull-to-refresh, sticky behaviour are set defensively but only Chromium was exercised). `:has()` is required for the page-scroll mode (Chrome 105+, Safari 15.4+, Firefox 121+).
- Live regions inside a hidden pane do not announce results that arrive after the user switched away.
