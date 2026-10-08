# Browser tests (real Chromium, pointer + keyboard)

**Class `harness`. None of the specs below talks to a backend, so none counts as real-backend evidence.** The real-backend suite is `tests/e2e-real/` (`npm run test:e2e:real`, runbook `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md`, matrix `docs/C5_REAL_BACKEND_E2E_MATRIX.md`). Run order: unit → these harness specs → real-backend; their results are reported separately.

Not part of `npm run test:unit`. No new repo dependency: Playwright comes from `playwright-core` (already installed) and a browser is passed with `CHROME=/path/to/chrome`
(macOS: `CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"`). The harness bundle needs `esbuild`, installed OUTSIDE the repo.

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
    node tests/lib/owned-process-cli.mjs stop --state .run/owned/portal-platform.json                                                                      # validated stop of exactly what was started; repeat for admin and studio

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
