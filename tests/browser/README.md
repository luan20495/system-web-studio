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
