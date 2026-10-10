# C5 final candidate — evidence record (2026-10-09 / 10)

Every number below was produced in the session that wrote this file; logs live in the session scratchpad and are NOT in the repository, so this record IS the artefact. Classes: UNIT, HARNESS (real browser, in-page fakes, no backend), LIVE_PORTAL (the three real production portal builds started through the owned-process library, `/api` faked or unreachable), REAL_STACK (private builds against the e2e backend `http://127.0.0.1:47080`, backend started and stopped by someone else), REAL_BACKEND_E2E (`tests/e2e-real`, **none run**). Browser labels: CHROMIUM, WEBKIT (Playwright WebKit 26.6, never Safari), FIREFOX (blocked).

## 1. Full C5 gate on `56801b2` (merge of the S3 brand system; load 11-14)
| Stage | Result |
|---|---|
| typecheck root / apps / packages | PASS / PASS / PASS (UNIT) |
| unit | 505 tests pass, 0 fail, 1 skipped (conformance fixtures: prints PENDING) |
| classify | PASS |
| builds platform / admin / studio / root | PASS x4 (`.next-gate`) |
| HARNESS CHROMIUM | admin 176, aiproviders 27, org 93, org-hardening 70, provisioning 39, builder 111, datasources 54, release 61, publicdata 47, shared-ui 80, ui-widgets 54, ui-route 19, ui-tokens 43, studio-p1 16, studio-wave2 31, studio-wave3 166, data-binding 34, hooks 20, sanity (production bundle) 8, ui-brand 166 — all pass |
| css-snapshot | 40 screenshots, 0 not taken (28 brand changes reviewed INTENTIONAL by C5-S3) |
| page-runtime | 37/37 |
| LIVE_PORTAL CHROMIUM | portals 36/36, portals-lazy 18/18; ports 3001-3003 free afterwards, no foreign process touched |
| `npm run gate:frontend` (C0) | GREEN (static guards 9/9, guard self-tests, typecheck, unit, production builds, bundle scan) |
Timing-sensitive `hooks.spec` fails 1-3 checks at random when the machine load exceeds ~30 (also on the pre-change tree); 20/20 at load < 15.

## 2. Fixes after C5-R-FINAL (`d603488`, targeted gate)
unit 508 tests (507 pass, 1 skipped); CHROMIUM studio-p1 16, studio-wave2 31, studio-wave3 174, release 61, shared-ui 80, ui-tokens 43, ui-widgets 54, org 93, builder 111, datasources 54, aiproviders 27, provisioning 39, ui-brand 166, admin 176, hooks 20, data-binding 34. css-snapshot re-taken and the dark dialog buttons reviewed (INTENTIONAL: borders now visible).

## 3. REAL_STACK audit (`scripts/ui-audit.mjs --private-api http://127.0.0.1:47080`, HEAD `56801b2`)
1422 visits (route x viewport x state) at 1920 1440 1280 1024 768 600 430 390 360, 117 routes / states, **87 of 87 source routes visited**; overflow 0, unreachable controls 0, small targets 0, label mismatch 0, no focus ring 0, focus obscured 0, **axe critical + serious 0, moderate + minor 0**, console errors 0, failing API calls 0, blank pages 0, **covered controls 8** (all Studio at 600 px: the header search under `<main>`, M-083 side effect). Informational only: `glyphs` (arrows / dots used as text) and `noH1` on the Studio editor (the project name is `role=heading aria-level=1`, the tool counts literal `<h1>`). Fix `6883272` (grid row grows with the two-row header) + checks at 360-600 px in studio-wave3; re-audit of Studio at 600 / 560 / 430: 102 visits, covered 0, everything else 0. Not reachable on this stack: `/admin/organization` data (NOT_READY by design), AI generation, publish, real 403 / 404 / 500, cost data. The backend is the older `c5e2e-ae` build (no H-C1-04 / final org contract): this is NOT evidence for those.

## 4. Cross-browser (HARNESS specs through `BROWSER=`)
- **CHROMIUM**: everything in 1 and 2.
- **WEBKIT** (final run at `6883272`): fully pass: ui-route 19, shared-ui 80, ui-widgets 54, ui-tokens 43, ui-brand 166, org 93, org-hardening 70, provisioning 39, aiproviders 27, builder 111, studio-p1 16, studio-wave3 166, data-binding 34, release 61, datasources 54, publicdata 47, hooks 20 (17 of 19 specs). **studio-wave2 30/31**: M-015 (Esc then Tab inside the code editor) fails ONLY because the spec shim sends Tab as Alt+Tab; with `WEBKIT_PLAIN_TAB=1` it is 31/31 (text-area Tab is native). **admin 173/174**: one check reads the clipboard (`navigator.clipboard.readText`), which WebKit refuses without a permission name it does not support (engine limit, not a product defect). macOS WebKit only tabs through text fields (measured: Tab = input, select; Option+Tab = every control), hence the Alt+Tab emulation in `tests/browser/lib/spec.mjs`; a Safari user with the default preference cannot Tab to a button: that is an OS setting.
- The WEBKIT run found and fixed two real defects: a dialog did not return focus to the control that opened it (WebKit does not focus a clicked button), and an opened Picker did not take focus (Escape / arrows missed it).
- **FIREFOX**: BLOCKED_TOOLING (`audit/S4-cross-browser.md`). Not passed, not accepted: the user decides.

## 5. Not run / not claimed
REAL_BACKEND_E2E (USER01, E2E-04/05/AD02/ADMIN01 against the integrated final backend): prepared (`docs/parallel/c5/USER01_REAL_BROWSER_PREP.md`), NOT run. Dynamic Organization against a backend: not wired (WAITING_FOR_C1_C3). Safari: never tested. Core Web Vitals: not claimed.

## 6. Superseded in part by the PL01 / Dynamic Organization / AD01 / C2 record
Section 5 above ("REAL_BACKEND_E2E ... NOT run") is superseded: PL01, AD01, AD02, ADMIN01, USER01, E2E-04 / 05 were run against a real stack on 2026-10-10 (flag OFF) and E2E-ORG01 recorded the real fail-closed evidence; see `audit/PL01_ORG_AD01_C2_2026-10-10.md`. The organization screens are no longer NOT_READY: they are wired (`ORGANIZATION_UI.md`).
