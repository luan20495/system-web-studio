# M-053 — measured bundle splitting (2026-10-09)

Evidence: `next build` (Next 16 production, `NEXT_DIST_DIR=.next-gate`, API proxy unreachable) + `node scripts/bundle-report.mjs`; raw / gzip KB. BEFORE = `agent/c5-web` at 2b0fa31 (after M-068/M-107), AFTER = this change. Chromium harness checks are HARNESS, NOT REAL BACKEND.

## BEFORE
| app | First Load JS | authenticated shell chunk (dynamic) |
|---|---|---|
| Platform / Admin (same `AdminApp`) | 552.4 raw / 169.1 gz | 345.9 raw / 93.3 gz (every console page in ONE chunk) |
| Studio | 565.2 raw / 173.4 gz | 461.0 raw / 134.4 gz (page builder + code workspace + all panels) |
First load is already at its floor: ~373 KB is the framework (react-dom 199.8 + Next client 173.0); the login code is ~140 KB. No further first-load win exists without dropping the framework.

## Change (what the measurement supported)
1. **Admin console sections** (`features/admin/console/sections.tsx`): the 18 system / governance pages a person opens rarely (AI setup+usage+pricing as one `AiSection`, AI governance, applications, audit, alerts / costs / security, components / templates, builds / health / packages / settings, departments / identity / connectors / backups) are `lazy()` chunks fetched on first visit. The landing page, company / user / people / organization screens stay static. `routes.tsx` wraps the page in `<Suspense>` with a compact loading state (an `h2`, no `h1`).
2. **Focus after a lazy route** (found by the admin spec, SKP04): `useMain` moved focus to the first `<h1>`, but while a lazy section loads React keeps the PREVIOUS page in the DOM with `display:none`; its `<h1>` cannot take focus, so focus stayed on the sidebar link. `focusTargetFor` now picks the first VISIBLE `<h1>` (`checkVisibility`), and a heading that appears later than the old 300 ms look is picked up by a `MutationObserver` (5 s cap; a user who moved focus is never overridden). New browser checks (ui-route, a heading 900 ms late) and unit checks.
3. **Studio code workspace** (`ProjectWorkspace.tsx`): `CodeWorkspace` (+ CodePanels, diff, site panels used only there) is a `lazy()` chunk, loaded when a code project (STATIC_APP) opens; a page project never fetches it.

## AFTER
| app | First Load JS | authenticated shell chunk | new lazy chunks |
|---|---|---|---|
| Admin (Platform identical, same code) | 552.6 raw / 169.0 gz (+0.2) | **178.8 raw / 48.5 gz (-48.3% / -48.0%)** | 11 chunks, largest 60.1 raw (the AI section) |
| Studio | 566.0 raw / 173.3 gz (+0.8) | **351.4 raw / 102.0 gz (-23.8% / -24.1%)** | code workspace 81.1 raw / 27.1 gz |
Total JS on disk grows 2.3 % (Admin 1038.1 → 1062.4 KB) because of per-chunk overhead; what a person downloads on the way to the first screen shrinks. A section costs one extra request the first time it is opened (the loading state shows meanwhile).

## Verdict (ledger)
First load: unchanged (at its floor, measured). Post-login payload: Admin / Platform -167 KB raw (-45 KB gzip), Studio -110 KB raw (-32 KB gzip). No lazy-load regression found: admin 176/176, ui-route 19/19, studio-wave3 162/162 (+ studio-p1, studio-wave2, builder, release, data-binding: see the gate log). Left as is (measured, not split): the page builder inside the Studio chunk (it is what the Studio opens first), the Admin landing + company / user screens. `portals-lazy.spec` (needs the three portals running) is part of the final gate. Not a Core Web Vitals claim.
