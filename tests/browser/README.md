# Browser tests (real Chromium, pointer + keyboard)

**Class `harness`. None of the specs below talks to a backend, so none counts as real-backend evidence.** The real-backend suite is `tests/e2e-real/` (`npm run test:e2e:real`, runbook `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md`, matrix `docs/C5_REAL_BACKEND_E2E_MATRIX.md`). Run order: unit → these harness specs → real-backend; their results are reported separately.

Not part of `npm run test:unit`. No new repo dependency: Playwright comes from `playwright-core` (already installed) and a browser is passed with `CHROME=/path/to/chrome`
(macOS: `CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"`). The harness bundle needs `esbuild`, installed OUTSIDE the repo.

## Builder (`builder.spec.mjs`) — component harness, NOT a backend E2E
`harness.tsx` mounts the real `<BuilderWorkspace>`; its host records every operation in `window.__ops` and applies the few section/page operations locally so the
canvas re-renders. It validates nothing the server validates and is never shipped. It exists to exercise drag and drop, focus, tabs, dialogs and ARIA in a real browser.

    npm i --prefix /tmp/esb esbuild
    node tests/browser/build-harness.mjs                       # -> .test-build/browser
    (cd .test-build/browser && python3 -m http.server 4000 --bind 127.0.0.1 &)
    CHROME=... node tests/browser/builder.spec.mjs

## Data sources panel (`datasources.spec.mjs`) — component harness with an in-page fake, NOT a backend
`ds-harness.tsx` mounts the real `<DataSourcesPanel>` with a `calls` object that records every call in `window.__calls` and answers per `?s=<scenario>` (`ok`, `empty`, `flagoff`, `forbidden`, `listerr`, `createfail`, `conflictname`, `bound`, `slowtest`, `disabledtest`, `failtest`, `warntest`, `noslots`, `readonly`, `nocalls`). It proves what the PANEL does with the answers C3's document describes — states, duplicate-submit locks, secret handling (`window.__secretsSeenInDom()`), TEST/LIVE binding, read-only, ambiguous-outcome reload — never what the real routes answer. `build-harness.mjs` builds both entries (`index.html` and `ds.html`).

    node tests/browser/build-harness.mjs
    (cd .test-build/browser && python3 -m http.server 4000 --bind 127.0.0.1 &)
    CHROME=... node tests/browser/datasources.spec.mjs         # 47 checks

Note: `npm run test:unit` recreates `.test-build`; rebuild the harness (and restart the static server, whose working directory disappears) afterwards.

## Portals (`portals.spec.mjs`) — three real Next apps, no backend
    npm run build:platform && npm run build:admin && npm run build:studio
    (cd apps/platform && npx next start -H 127.0.0.1 -p 3001 &) ; same for admin 3002, studio 3003
    CHROME=... node tests/browser/portals.spec.mjs

Covers only what needs no session (redirect to /login with `next`, labels, CSP/headers, no cookies, login error with the API down).
Login, session, portal switching, OIDC and CORS need the real backend and are NOT covered here.

In the Builder harness the Test panel gets **no runtime** and the Data wizard gets **no management calls**, so both must show "Chưa sẵn sàng" (not connected) — the spec asserts that. The Test panel with a runtime is exercised by `tests/builder/{apiclient,errors,components}.test.ts(x)` and, against a real server, by E2E-10/11/S1.
