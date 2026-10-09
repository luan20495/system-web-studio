# Browser tests (real Chromium, pointer + keyboard)

**Class `harness`. None of the specs below talks to a backend, so none counts as real-backend evidence.** The real-backend suite is `tests/e2e-real/` (`npm run test:e2e:real`, runbook `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md`, matrix `docs/C5_REAL_BACKEND_E2E_MATRIX.md`). Run order: unit → these harness specs → real-backend; their results are reported separately.

Not part of `npm run test:unit`. No new repo dependency: Playwright comes from `playwright-core` (already installed) and a browser is passed with `CHROME=/path/to/chrome`
(macOS: `CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"`). The harness bundle needs `esbuild`, installed OUTSIDE the repo.

## Shared spec toolkit (`tests/browser/lib/spec.mjs`)
Every spec imports one toolkit instead of copying its boilerplate: `makeChecks()` (PASS / FAIL / SKIP lines, summary, exit code), `launch()` (Chrome from `$CHROME`, else the first installed default of the OS: macOS Google Chrome / Chromium / Edge, Linux `/opt/pw-browsers`, `google-chrome`, `chromium`, Windows Program Files; a missing browser stops with exit 2 and the list it looked for), `harnessUrl()` / `harnessOrigin()` / `harnessPage("org.html")` and `watchConsole(page, errors)`.
**`HARNESS_URL` is required.** A spec started without it (not through `node tests/browser/harness-server.mjs run -- node tests/browser/<spec>`) fails at once with exit 2 and says so; no spec falls back to a fixed port (4000 used to be the default and may belong to another process). The toolkit is covered by `tests/browser/lib/spec.test.mjs` (run by `npm run test:unit`), which also fails if a spec calls `chromium.launch` itself or hard-codes a port or a Linux Chrome path. `page-runtime.spec.mjs` is C2-owned and keeps its own stub origin.

## Hooks (`hooks.spec.mjs`) — the real `useAction` / `useLoad` with in-page fake calls, NOT a backend
`hooks-harness.tsx` mounts the hooks of `packages/ui/src`; the spec proves a real double click sends ONE call, a rejected call re-enables the control and keeps the idempotency key for the retry, StrictMode keeps the busy state, and `useLoad` keeps its old contract while the opt-in keyed cache de-duplicates, shows cached data on the first render, aborts superseded requests and is cleared by `clearLoadCache()`. The framework-free cores are also unit-tested (`tests/builder/ui-hooks-core.test.ts`).

    node tests/browser/build-harness.mjs
    node tests/browser/harness-server.mjs run -- node tests/browser/hooks.spec.mjs        # 20 checks

## Final-gate audit tooling (`scripts/ui-*.mjs`, `scripts/audit/`) — developer tools, NOT tests
Four runners and one self-test. They share one engine (`scripts/audit/measure.mjs` in-page measurements + `engine.mjs` visit / flag / summarize) and one **route inventory derived from the SOURCE** (`scripts/audit/inventory.mjs`: the admin section registry `features/admin/console/sections.tsx` when it exists, otherwise the pre-registry tables of `AdminApp.tsx` / `base.ts`; the Studio `route()` switch and the project `MODES` / `PANELS`). Every runner builds its visit list from that inventory and **exits 1 when a route found in the source was not visited**. Each prints a table and writes JSON. Servers and Chrome are started and stopped only through `tests/lib/owned-process.mjs`; no fixed port, nothing found or killed by name or port.

| Runner | What | Needs | Output |
|---|---|---|---|
| `scripts/ui-audit-selftest.mjs` | proves the detectors speak: a fixture WITH each defect is flagged, a clean page is not | Chrome | table, exit 1 on a miss |
| `scripts/ui-audit-harness.mjs` | the responsive matrix (1920 1440 1280 1024 768 600 430 390 360) over every route on the harnesses: overflow, unreachable / covered controls, targets under 24 px, label-in-name, focus ring and sticky-header cover on the first 10 Tab stops, axe of every impact, console errors, failing API calls (**HARNESS, NOT REAL BACKEND**) | `node tests/browser/build-harness.mjs` | `audit.md`, `audit.json`, `--shots` for screenshots |
| `scripts/ui-audit.mjs` | the same engine against a REAL stack: `--private-api http://127.0.0.1:47080` builds the three apps into private dist dirs with `API_PROXY_TARGET` at that backend, serves them on free ports (owned), seeds data through the product API, audits, stops them. The backend is never started or stopped. A pass-through shim rewrites only the Origin header because the backend refuses origins that are not on its CORS list | a running backend + its `stack.env` | `audit.md`, `audit.json` |
| `scripts/ui-state-matrix.mjs` | every screen x default / loading / empty / error / permission-denied / populated / long-content, PASS / FAIL / NOT-REACHABLE with the reason; injected failures include a leaky Java 500 message and a 403 (**HARNESS, NOT REAL BACKEND**) | build-harness | `state-matrix.md`, `.json` |
| `tests/browser/portals-lazy.spec.mjs` (spec, private portal builds) | the console is a lazy chunk: not on the login page, loaded after sign-in, role=status while loading, an error fallback when the chunk cannot load | the 3 apps served (owned-process-cli, `PORTAL_*_PORT`) | 18 checks |
| `scripts/ui-keyboard.mjs` | keyboard-only flows (Tab / Shift+Tab / Enter / Space / Escape / arrows, no click): Platform create company, Admin create user, Studio select + edit + publish pre-check; asserts reachability, visible focus, dialog focus-in / trap / wrap / Escape / focus restore (**HARNESS**) | build-harness | `keyboard.md`, `.json` |

    node tests/browser/build-harness.mjs                                   # dev bundle -> .test-build/browser (the runners read it)
    node scripts/ui-audit-selftest.mjs
    node scripts/ui-audit-harness.mjs --out /tmp/ah [--only platform,admin,studio] [--viewports 1440,390] [--shots]
    node scripts/ui-state-matrix.mjs  --out /tmp/sm [--only ...] [--viewports 1440,390] [--states default,loading,...]
    node scripts/ui-keyboard.mjs      --out /tmp/kb [--viewport 1280] [--only platform,admin,studio]
    node scripts/ui-audit.mjs --private-api http://127.0.0.1:47080 --out /tmp/real [--only ...] [--viewports ...]     # REAL stack; AUDIT_NO_SHOTS=1 skips screenshots

**Triage notes (what the detectors say that is NOT a product defect).** (1) The responsive matrix opens every dialog / drawer state of a screen for BOTH admin personas; a system admin on a company screen (`/admin/employees`) is refused ("Bạn chưa quản trị công ty nào") and has no `emp-create` button, so the state is recorded as `skipped` (counted in `skippedStates`), not as a blank / console-error visit (M-124: 18 rows = 2 states x 9 widths, all this artefact; the company-admin persona opens both dialogs). (2) `failingApi` in the harness counts the harness's own 404 for an endpoint it has no fixture for: read `window.__calls` `{unknown}` in admin-harness before calling it a defect. (3) `glyphs[→]` rows are the arrow characters in link text (intended). (4) NOT-REACHABLE in the state matrix is a harness limit, never a pass.

A clean audit proves nothing until the self-test has passed. NOT-REACHABLE is never a pass: it says the harness cannot produce that state for that screen (for example the screen makes no data request). The unit test `tests/lib/audit-tools.test.mjs` covers the inventory parsers (both table formats), the completeness check and the flag / summary rules.

## Builder (`builder.spec.mjs`) — component harness, NOT a backend E2E
`harness.tsx` mounts the real `<BuilderWorkspace>`; its host records every operation in `window.__ops` and applies the few section/page operations locally so the
canvas re-renders. It validates nothing the server validates and is never shipped. It exists to exercise drag and drop, focus, tabs, dialogs and ARIA in a real browser.

    npm i --prefix /tmp/esb esbuild
    node tests/browser/build-harness.mjs                       # -> .test-build/browser
    CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/builder.spec.mjs

## Data sources panel (`datasources.spec.mjs`) — component harness with an in-page fake, NOT a backend
`ds-harness.tsx` mounts the real `<DataSourcesPanel>` with a `calls` object that records every call in `window.__calls` and answers per `?s=<scenario>` (`ok`, `empty`, `flagoff`, `forbidden`, `listerr`, `createfail`, `conflictname`, `bound`, `slowtest`, `disabledtest`, `failtest`, `warntest`, `noslots`, `readonly`, `nocalls`). It proves what the PANEL does with the answers C3's document describes — states, duplicate-submit locks, secret handling (`window.__secretsSeenInDom()`), TEST/LIVE binding, read-only, ambiguous-outcome reload — never what the real routes answer. `build-harness.mjs` builds both entries (`index.html` and `ds.html`).

    node tests/browser/build-harness.mjs
    CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/datasources.spec.mjs         # 47 checks

Note: `npm run test:unit` recreates `.test-build`; rebuild the harness afterwards (the static server reads files per request, so no restart is needed).

**Process safety.** `tests/browser/harness-server.mjs run -- <command>` starts ONE static server on a free port (never a fixed port such as 4000), records pid + process group + start time + command in `.run/owned/`, runs the command with `HARNESS_URL` / `DS_HARNESS_URL` pointing at it, and ALWAYS stops exactly that process afterwards (`tests/lib/owned-process.mjs`). A busy `--port` is a clear failure (exit 3, names the port and the holder); nothing is ever stopped by name or by port. Details and the inventory: `docs/parallel/c5/PROCESS_SAFETY.md`.

## Public data V1 (`publicdata.spec.mjs`) — component harness + a page built by C2's own code, NOT a backend
Three parts, 43 checks. (1) `public-harness.tsx` mounts the real `<DataWizard>` with an in-page host that records every typed operation in `window.__pops` and applies the definition operations locally (`?s=` ok · empty · invalid · bound · reject · readonly · private): slot add/edit/delete/duplicate/referenced, `QueryDef.public` (READ true/false, WRITE blocked, persisted WRITE+public shown invalid), binding add/rebind/remove, readiness, no public action/mutation/workflow control. (2) `release.html?draft=public|private|invalid|plain` drives the publish dialog (PUBLIC_QUERIES list, acknowledgement, event after publishing, no extra field on the wire). (3) `build-c2-site.mjs` reads C2's `lib/schema-preview.ts` and `workers/render/page-runtime.ts` from git (`C2_REF`, default `c1e0df5`), runs C2's `resolveBindings` + `renderSitePages` and writes a published page + C2's runtime script to `.test-build/browser/c2/`; the spec answers the page's same-origin config and data routes and asserts the five runtime states (loading-config, not-ready, loading-data, ready, error) with C5's vocabulary (`describeRuntimeState`). If the ref is not in the clone, part 3 is reported SKIPPED, never passed.

    node tests/browser/build-harness.mjs
    CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/publicdata.spec.mjs          # 43 checks

## Portals (`portals.spec.mjs`) — three real Next apps, no backend
    npm run build:platform && npm run build:admin && npm run build:studio
    # each app through the owned-process CLI: exit 3 (port named) if 3001 / 3002 / 3003 is already taken; it never stops the holder
    node tests/lib/owned-process-cli.mjs start --state .run/owned/portal-platform.json --cwd apps/platform --port 3001 -- npx next start -H 127.0.0.1 -p 3001      # same for admin 3002 and studio 3003 (state portal-admin / portal-studio)
    CHROME=... node tests/browser/portals.spec.mjs
    node tests/lib/owned-process-cli.mjs stop --state .run/owned/portal-platform.json                                                                      # validated stop of exactly what was started (no `refresh` needed: `start` records the npx -> npm exec re-exec); a REFUSED stop keeps the state file; repeat for admin and studio

Covers only what needs no session (redirect to /login with `next`, labels, CSP/headers, no cookies, login error with the API down).
Login, session, portal switching, OIDC and CORS need the real backend and are NOT covered here.

## PAGE_SCHEMA client runtime (`page-runtime.spec.mjs`) — real Chrome, a stub origin, NOT an E2E
The page the real renderer produces and the real runtime script (`workers/render/page-runtime.ts`), served by a local stub that plays the sites gateway (page, runtime file, `__factory/config.json`, the data route) with the CSP a data-bound page is served with. Covers the states (LOADING_CONFIG / NOT_READY / LOADING_DATA / READY / ERROR), every error mapping, one attempt and no retry, no credentials and no other host, HTML in data shown as text, caps and sub-pages. The data route is a test double: the public data controller (C0 + C3 + C1) does not exist yet.

    npx tsc -p tests/tsconfig.json
    CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" node tests/browser/page-runtime.spec.mjs

## Sites gateway (`tests/gateway/data-route.mjs`) — a real nginx in a throwaway container
Renders `infra/sites-gateway/default.conf.template` against a stub upstream on 127.0.0.1 (needs Docker and the `nginxinc/nginx-unprivileged:1.29-alpine` image that `compose.yml` uses): the public data route (path shape, POST only, size, rate limit, credentials stripped, query string dropped) and that pages, forms, the server-app `/api` proxy and `/healthz` are unchanged.

    node tests/gateway/data-route.mjs

In the Builder harness the Test panel gets **no runtime** and the Data wizard gets **no management calls**, so both must show "Chưa sẵn sàng" (not connected) — the spec asserts that. The Test panel with a runtime is exercised by `tests/builder/{apiclient,errors,components}.test.ts(x)` and, against a real server, by E2E-10/11/S1.

## Sanity (`sanity.spec.mjs`) — component harness, no backend, NOT a performance project
Opens/closes the rail panels, inspector tabs and the Test panel 40 times in real Chromium and asserts: DOM nodes and JS listeners do not grow (round 10 → last), JS heap after GC stays within 30 % / 15 MB, no console error or warning (React warnings included), no uncaught exception, **no network request after load**. `HARNESS_NODE_ENV=production node tests/browser/build-harness.mjs` builds the production React bundle into `.test-build/browser-prod` (serve it and pass `HARNESS_URL`); both bundles pass.
Pitfall found while writing it: `page.waitForSelector` returns an `ElementHandle` that the DevTools protocol keeps alive, which looks exactly like a DOM leak (+50 detached nodes per Test-panel open, retainer "DevTools console / Global handles"). Use `locator.waitFor()` in anything that measures memory.

    CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/sanity.spec.mjs        # SANITY_ROUNDS=40 by default
    # production bundle: HARNESS_NODE_ENV=production node tests/browser/build-harness.mjs && CHROME=... node tests/browser/harness-server.mjs run --dir .test-build/browser-prod -- node tests/browser/sanity.spec.mjs

## Platform / Admin portals (`admin.spec.mjs`) — the REAL `PortalApp` + `AdminApp` with a FAKE `fetch`, NOT a backend
`admin-harness.tsx` mounts the real portal entry (login gate, session, `AdminApp` router, every screen) in real Chromium. `window.fetch` answers `/api/v1/**` from in-page fixtures and records every request in `window.__calls`; `admin-next-shim.tsx` stands in for `next/link` / `next/navigation` (a history based router, aliased only in the separate esbuild call of `build-harness.mjs`). Query: `?portal=platform|admin&me=sys|sysmember|tadmin|wsadmin|plain|sysatenant&start=/platform/tenants` plus `fail` / `failw` / `slow` / `empty` / `big` / `daily=empty` / `bad=audit` scenarios (see the header of the harness). It proves what the SCREENS do with the answers C1's contract describes (activation link, tenant provisioning, `/auth/me` scope), never what a server answers. Sections so far: the one-time activation link dialog (M-007), create company → first admin (M-008), SYSTEM_ADMIN application detail (M-009).

    node tests/browser/build-harness.mjs
    CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/admin.spec.mjs          # 38 checks

## Studio-app harness (`studio-p1.spec.mjs`, `studio-app/`) - the REAL `<StudioApp>`, a FAKE `/api/v1`, NOT a backend
`studio-app/entry.tsx` mounts `PortalApp` -> `StudioApp` with `next/navigation` and `next/link` replaced by a virtual router (`?start=/studio/projects/p1/ai` gives the first path, `window.__nav.log` records pushes); `studio-app/fake-api.mjs` answers `/api/v1/**` inside Playwright (`page.route`, state object per test, `state.log` = every request, `state.hold` / `state.fail` to hold or fail a call). It proves what the UI sends and shows for the answers a fake gives; it validates nothing the server validates. `studio-p1.spec.mjs` holds the regression checks of the confirmed Studio P1s (M-001 review dialog, M-004 AI conversation); M-002 / M-003 live in `builder.spec.mjs`.

    node tests/browser/build-harness.mjs                       # also builds studio.html
    CHROME=... node tests/browser/harness-server.mjs run -- node tests/browser/studio-p1.spec.mjs
