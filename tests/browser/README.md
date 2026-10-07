# Browser tests (real Chromium, pointer + keyboard)

Not part of `npm run test:unit`. No new repo dependency: Playwright comes from `playwright-core` (already installed) and a browser is passed with `CHROME=/path/to/chrome`
(macOS: `CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"`). The harness bundle needs `esbuild`, installed OUTSIDE the repo.

## Builder (`builder.spec.mjs`) — component harness, NOT a backend E2E
`harness.tsx` mounts the real `<BuilderWorkspace>`; its host records every operation in `window.__ops` and applies the few section/page operations locally so the
canvas re-renders. It validates nothing the server validates and is never shipped. It exists to exercise drag and drop, focus, tabs, dialogs and ARIA in a real browser.

    npm i --prefix /tmp/esb esbuild
    node tests/browser/build-harness.mjs                       # -> .test-build/browser
    (cd .test-build/browser && python3 -m http.server 4000 --bind 127.0.0.1 &)
    CHROME=... node tests/browser/builder.spec.mjs

## Portals (`portals.spec.mjs`) — three real Next apps, no backend
    npm run build:platform && npm run build:admin && npm run build:studio
    (cd apps/platform && npx next start -H 127.0.0.1 -p 3001 &) ; same for admin 3002, studio 3003
    CHROME=... node tests/browser/portals.spec.mjs

Covers only what needs no session (redirect to /login with `next`, labels, CSP/headers, no cookies, login error with the API down).
Login, session, portal switching, OIDC and CORS need the real backend and are NOT covered here.

## PAGE_SCHEMA client runtime (`page-runtime.spec.mjs`) — real Chrome, a stub origin, NOT an E2E
The page the real renderer produces and the real runtime script (`workers/render/page-runtime.ts`), served by a local stub that plays the sites gateway (page, runtime file, `__factory/config.json`, the data route) with the CSP a data-bound page is served with. Covers the states (LOADING_CONFIG / NOT_READY / LOADING_DATA / READY / ERROR), every error mapping, one attempt and no retry, no credentials and no other host, HTML in data shown as text, caps and sub-pages. The data route is a test double: the public data controller (C0 + C3 + C1) does not exist yet.

    npx tsc -p tests/tsconfig.json
    CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" node tests/browser/page-runtime.spec.mjs

## Sites gateway (`tests/gateway/data-route.mjs`) — a real nginx in a throwaway container
Renders `infra/sites-gateway/default.conf.template` against a stub upstream on 127.0.0.1 (needs Docker and the `nginxinc/nginx-unprivileged:1.29-alpine` image that `compose.yml` uses): the public data route (path shape, POST only, size, rate limit, credentials stripped, query string dropped) and that pages, forms, the server-app `/api` proxy and `/healthz` are unchanged.

    node tests/gateway/data-route.mjs
