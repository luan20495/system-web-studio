> **SUPERSEDED_BY:** `docs/QA_FINAL.md` - historical document, kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# S4 audit: performance, browser, tooling, process lifecycle

Owner of this document: C5-S4 (Browser / Performance / Tooling / Process-lifecycle). Phase 1 = MEASURE + AUDIT. No product UI code was changed.
Branch `agent/c5-s4-perf`, base `9f858c2` (agent/c5-web HEAD). Audit date 2026-10-08/09.

> **Reading rules.** Every number below was produced by a command listed in section 9 on one developer machine. Three classes of evidence are kept apart and labelled each time:
> **SYNTHETIC** (Lighthouse simulated throttling, Playwright PerformanceObserver on localhost: lab data, not production Core Web Vitals, no field data, no network latency);
> **HARNESS, NOT REAL BACKEND** (the real screens mounted by `tests/browser/*-harness.tsx` over generated fixtures and in-page fakes);
> **STATIC** (code reading, grep, esbuild proxy; nothing executed in a browser).
> The machine was shared with other agents during the audit (load average about 10 on 10 cores when sampled, see 1.2): absolute milliseconds are approximate, comparisons inside one table (size n versus size 4n) are the reliable part.

## 0. Headline

| Area | Result |
|---|---|
| First Load JS (Next 16 / Turbopack build, derived from manifests) | Platform **809.4 KB raw / 230.0 KB gzip / 190.9 KB brotli**; Admin identical; Studio **912.6 / 267.6 / 221.6**; legacy root app **1198.0 / 340.8 / 273.4**. About 55 % of Platform's first load (442.7 KB) is React + Next runtime. |
| Code splitting | **None.** 0 uses of `next/dynamic`, `React.lazy`, `import()` in product code. The login page of every portal downloads the whole console (esbuild proxy: login-only code 53.7 KB min versus 399.5 KB Platform / 517.1 KB Studio). |
| Lighthouse 13.5, login pages, SYNTHETIC | Mobile preset performance 97 to 99, desktop 100, accessibility 100, best-practices 96 (the 4 points are `errors-in-console`: the dead API proxy answers 500 because no backend runs). Mobile LCP 2.03 to 2.59 s *simulated* (Slow-4G + 4x CPU); the same page measured unthrottled: FCP and LCP 49 to 106 ms (observed), CLS 0, TBT 0. |
| Harness: 2 000-unit org tree | expand-all 200 ms to paint (26 073 DOM nodes, 83 ms main thread), select one row 1.0 ms React work (Profiler actual) versus 31.9 ms if everything re-rendered (memoisation works). |
| Harness: 10 000 employees | one page (20 rows) in the DOM; 12 keystrokes send 1 request; page / filter changes 10 to 14 ms; typing never above one frame. |
| Harness: builder at the **contract maximum** (50 sections, 20 pages) | selection to inspector shown within 2 frames, preview iframe reloaded in 45 ms, save-one-field to reloaded preview 83 ms; scroll 0 dropped frames. Larger pages are a stress test beyond the contract (`PageSchemaValidator.kt:21` MAX_SECTIONS = 50, `pages.ts:12` MAX_PAGES = 20): at 1 004 sections selection costs 208 ms and a canvas scroll drops 25 of 96 frames. |
| Memory over 25 to 30 repeated rounds | **No growth**: DOM nodes, JS listeners, documents constant; heap +0.2 to +0.8 MB (noise level) in all three scenarios. |
| Cross-browser | **Only Chrome 155 was executed.** No Firefox, no WebKit, no Playwright browser cache on this machine. Safari 27.0.1 is installed but cannot be driven by Playwright and was not driven. Section 6 is a STATIC report. |
| Tooling safety | **PASS** (section 7): guard test 21/21, no `pkill`/`killall`/`lsof` piped into `kill` in C5-owned tracked tooling, `e2e-stack.sh` has no direct `kill`. Two residual hazards found and one fixed (S4-030). |
| Test suite | `npm run test:unit` 21 s (306 tests, 305 pass, 1 skipped); 11 browser specs 3x each = 33 runs, **0 failures, 0 flaky**, 334 s per pass, 543 checks per pass. |
| Issues | 0 P0, 0 P1, 4 P2, 21 P3 = 25 (24 OPEN, 1 FIXED in this branch); table in section 10. |

## 1. Methodology and environment

### 1.1 Method per task

1. **Bundles.** `NEXT_DIST_DIR=.next-check-s4 API_PROXY_TARGET=http://127.0.0.1:9 npx next build` in `apps/platform`, `apps/admin`, `apps/studio`; the root app with `NEXT_PUBLIC_API_MODE=http` (the legacy mock mode is a static export). Next 16.3.8 with Turbopack no longer prints "First Load JS", so `scripts/bundle-report.mjs` derives it: `rootMainFiles` of `build-manifest.json` + every chunk listed for `/[[...slug]]/page` in `page_client-reference-manifest.js`, minus the `nomodule` polyfill. Sizes are exact bytes of the emitted files, gzip level 6 and brotli default quality. Module composition is a **STATIC esbuild PROXY** (`--modules`): same sources, minified, tree-shaken, `next/*` external; it is not the Next bundle but is accurate for "who contributes how many bytes".
2. **Vitals / Lighthouse.** Production builds started with `tests/lib/owned-process-cli.mjs` on free ports 19101 to 19104; Chrome started the same way (`--headless=new --remote-debugging-port=19200`, private profile). `scripts/perf-lighthouse.mjs` runs Lighthouse 13.5.0 (installed outside the repo in `/tmp/lh`) through `--port`, 3 runs per page and preset, median reported, raw runs in 4.3. `scripts/perf-vitals.mjs` adds PerformanceObserver (FCP, LCP, CLS, long tasks) at CPU x1 and x4.
3. **Harness performance.** `scripts/perf-harness.mjs` (new, re-runnable, labelled HARNESS, NOT REAL BACKEND). Static server and Chrome are started by `scripts/perf-env.mjs` through `tests/lib/owned-process.mjs` (free ports, identity recorded, stopped by identity in `finally`). Bundle: `HARNESS_NODE_ENV=production HARNESS_PROFILING=1 node tests/browser/build-harness.mjs` -> `.test-build/browser-prof`, i.e. minified production React on `react-dom/profiling`, so `<Profiler>` reports real durations (a normal production bundle never calls `onRender`). Each action is timed in the page from just before the DOM event to the **second** `requestAnimationFrame` after it (= the frame that shows the result), plus the main-thread time of the action itself (React flushes a discrete event synchronously), plus Event Timing duration for real pointer / key input (Chrome rounds Event Timing to 8 ms). **Frame floor: headless Chrome ran rAF at 60 Hz, so any "painted" value below about 33 ms is quantised by two frames; read the "main-thread" and Profiler rows for sub-frame work.**
4. **Hotspots.** Code reading plus the Profiler rows above plus `scripts/perf-micro.mjs` (Node micro-benchmarks of the pure functions, to show growth with n). No instrumentation was added to product code; the only harness edits are in `tests/browser/` (Profiler wrapper and `?sections=&pages=` generated fixture in `harness.tsx`, `emp-10k-bigorg` scenario in `org-harness.tsx`, `HARNESS_PROFILING` in `build-harness.mjs`).
5. **Cross-browser.** Installed browsers inspected on disk; static feature scan with `scripts/compat-scan.mjs`.
6. **Tooling.** `node --test tests/lib/owned-process.test.mjs`, `npm run test:unit` (contains the guard), grep of tracked C5-owned files, review of `e2e-stack.sh` and spec headers, each browser spec run 3 times through `tests/browser/harness-server.mjs run`.

### 1.2 Environment

| Item | Value |
|---|---|
| Machine | Apple M1 Pro, 10 cores, 16 GiB RAM, macOS 27.0.1 (build 26A434), Darwin 27.0.0, arm64 |
| Load during the audit | `uptime` sampled at the end: load averages 10.22 / 9.95 / 8.98 (other agents were running; not controlled) |
| Node / npm | v22.23.1 / 11.20.0 |
| Next / React | 16.3.8 (Turbopack) / 19.2.0 |
| Chrome | Google Chrome 155.0.8059.40, headless (`--headless=new`), driven over CDP (`playwright-core` 1.63.0 `connectOverCDP`) |
| Lighthouse | 13.5.0, categories performance, accessibility, best-practices; mobile preset (default: Slow-4G 150 ms RTT / 1.6 Mbps, 4x CPU slowdown, simulated) and desktop preset |
| Production builds | `next build` 8 to 11 s each (Platform 10 s, Admin 8 s, Studio 9 s, root 11 s), `tsc --noEmit` of the root project 6 s |
| Browsers installed for Playwright | none (`~/Library/Caches/ms-playwright` does not exist); only system Google Chrome |
| Other browsers on the machine | Safari 27.0.1 (`/Applications/Safari.app`, `/usr/bin/safaridriver` exists, **not used**); no Firefox, no Edge, no Brave |

## 2. Bundles

All three portals and the legacy root app are **one catch-all route** (`/[[...slug]]`, dynamic `ƒ`, plus `/_not-found`) whose client router lives inside `PortalApp`; therefore "per route" First Load JS is one number per app. HTML is rendered per request (CSP nonce, `layout.tsx` awaits `connection()`).

### 2.1 First Load JS per app (measured, `scripts/bundle-report.mjs`)

| App | First Load JS raw | gzip | brotli | App-specific chunk (raw) | Framework chunks (raw) | CSS raw / gzip | All JS on disk | Dynamically loaded chunks |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Platform | 809.4 KB | 230.0 KB | 190.9 KB | 366.7 KB | 442.7 KB | 63.0 / 13.6 KB | 919.4 KB (7 files) | **0** |
| Admin | 809.4 KB | 230.0 KB | 190.9 KB | 366.7 KB | 442.7 KB | 63.0 / 13.6 KB | 919.4 KB | **0** |
| Studio | 912.6 KB | 267.6 KB | 221.6 KB | 469.9 KB | 442.7 KB | 81.1 / 17.0 KB | 1 022.6 KB | **0** |
| Legacy root (http mode) | 1 198.0 KB | 340.8 KB | 273.4 KB | 755.3 KB | 442.7 KB | 81.1 / 17.0 KB | 1 307.9 KB | **0** |

Plus a 110.0 KB (38.7 KB gzip) `nomodule` polyfill file in every build that modern browsers do not load. Measured on the wire from the browser (`perf-vitals`, script bytes encodedBodySize): Platform 222.9 KB, Studio 260.4 KB, root 333.7 KB (Next serves gzip; no brotli).
Static chunks are served `Cache-Control: public, max-age=31536000, immutable` with `Content-Encoding: gzip`; the HTML is `private, no-cache, no-store` (see S4-005).

Per-portal framework chunks (identical in all apps): `1rj7…` 223.8 KB, `1wym…` 172.4 KB, `1u5z…` 23.0 KB, `3fnt…` 14.0 KB, turbopack runtime 9.5 KB.

Full raw output: appendix A.

### 2.2 What is inside the app chunk (STATIC esbuild PROXY, minified, tree-shaken)

| Entry | Total min | gzip | Largest contributors (KB min) |
|---|---:|---:|---|
| Platform / Admin (`AdminApp`) | 399.5 KB | 104.1 KB | `features/admin` 270.9 (AdminApp.tsx 118.4, AiSetup 33.2, OrganizationScreens 24.3, TenantScreens 23.2, EmployeesScreens 17.1, ProvisioningScreens 14.1), `features/studio` **24.0** (pulled in by `TenantScreens.tsx:20`), lucide-react 21.8, api-client 21.4, auth 14.9, ui 13.3 |
| Studio (`StudioApp`) | 517.1 KB | 145.4 KB | `features/studio` 367.9 (StudioApp 31.9, ProjectWorkspace 23.4, CodeWorkspace 20.2, PublicDataPanels 16.5, DataWizard 16.1, ReleaseModal 15.3, CodePanels 15.1, DataSourcesPanel 13.7 ...), `@dnd-kit/*` **48.8** (core 38.7 + sortable 6.4 + utilities 3.1 + accessibility 0.6), api-client 30.8, auth 14.9, lucide-react 10.0 |
| Login-only (PortalApp with an empty `render`) | **53.7 KB** | 17.2 KB | api-client 21.3, auth 14.8, react 7.9, lucide 4.4, permissions 2.8, ui 2.1 |

Checks that came out **fine** (no finding):
- **Icons.** `packages/ui/src/icons.ts` re-exports about 100 icons by name from `lucide-react` 1.52.0 (4 260 icon files); no `import * as`. The Next chunk contains one `lucide-` string (the class prefix) and the proxy shows lucide at 21.8 KB (Admin) / 10.0 KB (Studio): tree-shaking works.
- **Duplicated code inside a portal.** One app chunk, Lighthouse `duplicated-javascript-insight` did not fire.
- **`@dnd-kit` import style.** Named imports; 48.8 KB min is the real cost of the library, but it is only needed by the builder (S4-002).
- **Fonts and images.** No web font and no image is requested (`font-family: Inter, ui-sans-serif, system-ui`); CLS is 0.

Duplication **across** portals: Platform and Admin ship the same 366.7 KB (99.5 KB gzip) app chunk (both entries import the whole `AdminApp`, which takes a `portal` prop), so each portal carries the other's screens (S4-003).

### 2.3 Lazy-loading candidates (evidence in 2.1/2.2)

| Candidate | Where | Size (min, proxy) | Why it can wait |
|---|---|---:|---|
| The whole console behind the auth gate | `apps/*/app/entry.tsx:4,7`, `packages/auth/src/PortalApp.tsx:47` (`render(seg.slice(1))`) | 345 KB (Platform) / 463 KB (Studio) not needed on `/login`, `/auth/*` | A login page needs 53.7 KB |
| Builder: `BuilderWorkspace`, `Canvas`, panels, `@dnd-kit` | `features/studio/ProjectWorkspace.tsx:12`, `StudioApp.tsx:15` | about 368 KB + 48.8 KB | Only the project route opens the builder |
| Release / publish modal | `ProjectWorkspace.tsx:23` -> `ReleaseModal.tsx` | 15.3 KB | Only opened on "Xuất bản" |
| Code workspace, site drawer, assets / members / settings drawers | `ProjectWorkspace.tsx:22,27,28` | 20.2 + 15.1 + 12.7 + 10.7 KB | Opened on demand |
| Data wizard, data sources, public data, workflow / action editors | `BuilderWorkspace.tsx` rail panels | 16.1 + 13.7 + 16.5 + 13.4 + 11.0 KB | Only when their rail panel opens |
| Admin sections (AI setup, organization, tenants, employees, provisioning) | `AdminApp.tsx` (1 196 lines, 118.4 KB) and siblings | 33.2 + 24.3 + 23.2 + 17.1 + 14.1 KB | One section per route |

### 2.4 Client components that could be server components

57 files carry `"use client"`. Because the whole app is one client-side router under a `"use client"` entry, nothing below it can be a server component without first splitting the route tree; the server-renderable shell today is only `layout.tsx`. The SSR HTML of `/login` is already complete (LCP element is server-rendered text, section 4). **Not a finding at this stage**: server-component conversion is blocked on the routing model (one catch-all), not on individual components. Pure presentational files without hooks (`packages/ui/src/ModalHeader.tsx`, `ProviderLogo.tsx`, `features/admin/PageHead.tsx`) are tiny and already client-agnostic.

## 3. Lighthouse and Web Vitals (SYNTHETIC, local, not production Core Web Vitals)

Pages reachable without a backend: each portal's `/login` (the root path redirects to it). Servers: production builds, owned processes, no backend (API proxy target `127.0.0.1:9` answers 500 for `/api/v1/auth/config` and `/api/v1/auth/me`).

### 3.1 Lighthouse 13.5.0 (median of 3 runs; mobile = simulated Slow-4G + 4x CPU, desktop preset)

SYNTHETIC lab results, local machine, median of N runs; not production Core Web Vitals.
| URL | preset | N | perf | a11y | best-pr | FCP ms | LCP ms | SI ms | TBT ms | CLS | TTI ms | transfer KB | requests |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| http://127.0.0.1:19101/login | mobile | 3 | 99 | 100 | 96 | 760 | 2265 | 760 | 39 | 0.000 | 2265 | 254 | 11 |
| http://127.0.0.1:19101/login | desktop | 3 | 100 | 100 | 96 | 208 | 492 | 208 | 0 | 0.000 | 492 | 254 | 11 |
| http://127.0.0.1:19102/login | mobile | 3 | 99 | 100 | 96 | 760 | 2266 | 760 | 25 | 0.000 | 2266 | 254 | 11 |
| http://127.0.0.1:19102/login | desktop | 3 | 100 | 100 | 96 | 208 | 492 | 208 | 0 | 0.000 | 492 | 254 | 11 |
| http://127.0.0.1:19103/login | mobile | 3 | 97 | 100 | 96 | 909 | 2563 | 909 | 41 | 0.000 | 2563 | 295 | 11 |
| http://127.0.0.1:19103/login | desktop | 3 | 100 | 100 | 96 | 250 | 535 | 250 | 0 | 0.000 | 535 | 295 | 11 |
| http://127.0.0.1:19104/login | mobile | 3 | 99 | 100 | 96 | 911 | 2041 | 911 | 28 | 0.000 | 2528 | 368 | 11 |
| http://127.0.0.1:19104/login | desktop | 3 | 100 | 100 | 96 | 247 | 427 | 247 | 0 | 0.000 | 427 | 368 | 11 |

Raw runs (all 24 values, simulated metrics and the unthrottled observed FCP/LCP of the same run):

| run (port_path-preset-index) | perf | a11y | best-pr | FCP | LCP | SI | TBT | CLS | observed FCP/LCP (unthrottled) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| http_127_0_0_1_19101_login-desktop-0 | 100 | 100 | 96 | 212 | 498 | 212 | 0 | 0.000 | 82/82 |
| http_127_0_0_1_19101_login-desktop-1 | 100 | 100 | 96 | 208 | 492 | 208 | 0 | 0.000 | 60/60 |
| http_127_0_0_1_19101_login-desktop-2 | 100 | 100 | 96 | 206 | 488 | 206 | 0 | 0.000 | 50/50 |
| http_127_0_0_1_19101_login-mobile-0 | 98 | 100 | 96 | 778 | 2293 | 778 | 62 | 0.000 | 82/82 |
| http_127_0_0_1_19101_login-mobile-1 | 99 | 100 | 96 | 760 | 2265 | 760 | 39 | 0.000 | 72/72 |
| http_127_0_0_1_19101_login-mobile-2 | 99 | 100 | 96 | 757 | 2107 | 757 | 12 | 0.000 | 66/66 |
| http_127_0_0_1_19102_login-desktop-0 | 100 | 100 | 96 | 209 | 493 | 209 | 0 | 0.000 | 93/93 |
| http_127_0_0_1_19102_login-desktop-1 | 100 | 100 | 96 | 207 | 491 | 207 | 0 | 0.000 | 71/71 |
| http_127_0_0_1_19102_login-desktop-2 | 100 | 100 | 96 | 208 | 492 | 208 | 0 | 0.000 | 63/63 |
| http_127_0_0_1_19102_login-mobile-0 | 98 | 100 | 96 | 774 | 2286 | 774 | 36 | 0.000 | 95/95 |
| http_127_0_0_1_19102_login-mobile-1 | 99 | 100 | 96 | 760 | 2266 | 760 | 25 | 0.000 | 59/59 |
| http_127_0_0_1_19102_login-mobile-2 | 99 | 100 | 96 | 757 | 2261 | 757 | 24 | 0.000 | 49/49 |
| http_127_0_0_1_19103_login-desktop-0 | 100 | 100 | 96 | 250 | 535 | 250 | 0 | 0.000 | 70/70 |
| http_127_0_0_1_19103_login-desktop-1 | 100 | 100 | 96 | 252 | 538 | 252 | 0 | 0.000 | 101/101 |
| http_127_0_0_1_19103_login-desktop-2 | 100 | 100 | 96 | 245 | 528 | 245 | 0 | 0.000 | 53/53 |
| http_127_0_0_1_19103_login-mobile-0 | 97 | 100 | 96 | 923 | 2585 | 923 | 41 | 0.000 | 81/81 |
| http_127_0_0_1_19103_login-mobile-1 | 97 | 100 | 96 | 909 | 2563 | 909 | 42 | 0.000 | 103/103 |
| http_127_0_0_1_19103_login-mobile-2 | 97 | 100 | 96 | 907 | 2561 | 907 | 30 | 0.000 | 106/106 |
| http_127_0_0_1_19104_login-desktop-0 | 100 | 100 | 96 | 254 | 434 | 254 | 0 | 0.000 | 79/79 |
| http_127_0_0_1_19104_login-desktop-1 | 100 | 100 | 96 | 246 | 426 | 246 | 0 | 0.000 | 83/83 |
| http_127_0_0_1_19104_login-desktop-2 | 100 | 100 | 96 | 247 | 427 | 247 | 0 | 0.000 | 65/65 |
| http_127_0_0_1_19104_login-mobile-0 | 99 | 100 | 96 | 923 | 2057 | 923 | 46 | 0.000 | 98/98 |
| http_127_0_0_1_19104_login-mobile-1 | 99 | 100 | 96 | 907 | 2030 | 907 | 28 | 0.000 | 79/79 |
| http_127_0_0_1_19104_login-mobile-2 | 99 | 100 | 96 | 911 | 2041 | 911 | 22 | 0.000 | 68/68 |
LH version 13.5.0 UA Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/155.0.0.0 Safari/537.36 network Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36

Reading:
- **best-practices 96 is an environment artefact**: the only failing audit is `errors-in-console`, i.e. the two 500 responses of the dead API proxy (`/api/v1/auth/config`, `/api/v1/auth/me`).
- **LCP** element is the server-rendered `p.authLead` (`p.hint` on the root app); the unthrottled LCP breakdown is TTFB 20 ms + element render delay 61 ms. The 2.0 to 2.6 s mobile LCP is Lighthouse's *simulation* (the whole 450 to 800 KB of script must arrive before the page counts as interactive in the model); it is not a measurement of a real phone.
- Failing / partial Lighthouse audits on Platform `/login` (mobile, run 0): `unused-javascript` est. 133 KiB (the app chunk is 83.9 % unused on this page: 82 164 of 97 958 transferred bytes), `unused-css-rules` est. 13 KiB, `render-blocking-insight` 164 ms (the single 63 KB CSS), `legacy-javascript-insight` 14 KiB (Next's own `Array.prototype.at/flat/flatMap`, `Object.fromEntries/hasOwn`, `trimStart/End` polyfills in the framework chunk), `bf-cache` (2 reasons, both `Cache-Control: no-store`), `max-potential-fid` 110 ms.
- Request waterfall (Studio `/login`, mobile run 0): document 0 ms, CSS + 6 scripts at +23 to +33 ms, `/favicon.ico` +114 ms, then `/api/v1/auth/config` and `/api/v1/auth/me` at +117 ms: **three sequential round trips** (HTML, script, API) before the form knows the session state. On a real 150 ms RTT network that is at least 450 ms of pure latency.

### 3.2 PerformanceObserver (Playwright, owned Chrome, 5 runs, median; SYNTHETIC)

SYNTHETIC lab run (PerformanceObserver) in headless Chrome 155.0.8059.40; CPU throttle x1; runs=5; NOT production Core Web Vitals.
| URL | FCP ms | LCP ms (max) | LCP element | CLS | TBT-like ms | longest task ms | DCL ms | load ms | script bytes on wire KB | requests |
|---|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|
| http://127.0.0.1:19101/login | 76 | 76 (128) | P.authLead | 0.000 | 0 | 0 | 35 | 71 | 222.9 | 11 |
| http://127.0.0.1:19102/login | 56 | 56 (64) | P.authLead | 0.000 | 0 | 0 | 37 | 70 | 222.9 | 11 |
| http://127.0.0.1:19103/login | 72 | 72 (72) | P.authLead | 0.000 | 0 | 0 | 39 | 74 | 260.4 | 11 |
| http://127.0.0.1:19104/login | 60 | 60 (76) | P.hint | 0.000 | 0 | 0 | 36 | 76 | 333.7 | 11 |
| http://127.0.0.1:19103/ | 52 | 116 (120) | P.authLead | 0.000 | 0 | 0 | 35 | 70 | 260.4 | 14 |
SYNTHETIC lab run (PerformanceObserver) in headless Chrome 155.0.8059.40; CPU throttle x4; runs=5; NOT production Core Web Vitals.
| URL | FCP ms | LCP ms (max) | LCP element | CLS | TBT-like ms | longest task ms | DCL ms | load ms | script bytes on wire KB | requests |
|---|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|
| http://127.0.0.1:19101/login | 120 | 120 (132) | P.authLead | 0.000 | 0 | 81 | 40 | 206 | 222.9 | 11 |
| http://127.0.0.1:19102/login | 116 | 116 (124) | P.authLead | 0.000 | 0 | 77 | 43 | 193 | 222.9 | 11 |
| http://127.0.0.1:19103/login | 116 | 116 (140) | P.authLead | 0.000 | 0 | 76 | 45 | 194 | 260.4 | 11 |
| http://127.0.0.1:19104/login | 124 | 124 (168) | P.hint | 0.000 | 18 | 79 | 36 | 198 | 333.7 | 11 |
| http://127.0.0.1:19103/ | 88 | 340 (340) | P.authLead | 0.000 | 0 | 80 | 38 | 171 | 260.4 | 14 |

(The second table is the same pages at CPU x4. "TBT-like" = sum of long-task time above 50 ms after FCP. `http://127.0.0.1:19103/` is the studio root path, which redirects to `/login`; its LCP is the redirected page.)

## 4. Harness performance (HARNESS, NOT REAL BACKEND)

`node scripts/perf-harness.mjs --runs 5 --rounds 30` (CPU x1) and `--runs 3 --rounds 15 --cpu 4`. Full tables: appendices B and C. Summary:

### 4.1 Organization tree, 2 000 generated units (`org.html?v=org&s=big`)

| Action | CPU x1 median (p95) | CPU x4 median (p95) | Note |
|---|---:|---:|---|
| load event (bundle execution included) | 98.7 (114.9) ms | 145.7 (405.5) ms | |
| first React commit (Profiler actual) | 13.1 ms | 50.3 ms | the tree starts collapsed to its roots (`LARGE_TREE = 300`) |
| expand all -> 2 000 rows painted | 200.3 (737.8) ms | 738.2 ms | main thread 83.1 ms / 306.3 ms; **26 073 DOM nodes** (13 per row); one of 5 runs took 737.8 ms |
| select one row (any of 10) -> painted | 27.6 (37.0) ms | 28.9 (70.9) ms | at the 2-frame floor; main-thread 2.7 ms / 9.5 ms |
| Profiler actual for that update | **1.0 ms** | 3.5 ms | versus **base 31.9 ms** = cost if all rows re-rendered: `React.memo` rows work |
| toggle one node in the expanded tree | 31.2 (61.2) ms | 101.4 (226.3) ms | whole visible list is re-flattened |
| REAL pointer click on a row, Event Timing | 24 (40) ms | 80 (112) ms | |
| REAL ArrowDown, Event Timing | 16 (32) ms | 48 (88) ms | |
| collapse all | 28.4 (32.9) ms | 108.7 ms | |

### 4.2 Employee directory, 10 000 generated employees (`org.html?v=emp`)

| Action | median (p95), CPU x1 | Note |
|---|---:|---|
| first React commit | 7.1 ms | **one page = 20 rows in the DOM**, never 10 000 |
| keystroke -> frame, 12 keys (server-paged fake) | 33.3 (34.9) ms | = one 60 Hz frame; search is debounced 250 ms: **1 request for 12 keystrokes** |
| next page / status filter | 11.2 / 13.9 ms | |
| 10 000 employees + 2 000-unit org (unit `<select>` with 2 001 options): first commit | 17.7 ms | typing: Profiler actual per commit 0.9 ms (p95 1.5): the 2 001 options are **not** a typing cost |
| members fallback (client-side filter of 10 000): keystroke -> frame | 33.3 (34.5) ms | list fetched **once** (cache by list identity, `organizationModel.ts:142`), page 9.9 ms, filter 9.2 ms |
| REAL click on a row, Event Timing | 32 ms | |

### 4.3 Builder (`index.html`, real `BuilderWorkspace`, generated `?sections=&pages=`), CPU x1, median (p95)

`4` = default harness page; `50 + 20 pages` = **contract maximum** (`MAX_SECTIONS = 50`, `MAX_PAGES = 20`); the larger columns are a STRESS test beyond the contract.

| Measure | 4 sections | **50 sections, 20 pages** | 104 | 404 | 1 004 |
|---|---:|---:|---:|---:|---:|
| load -> all section handles present (wall clock) ms | 286 (333) | 279 (281) | 309 (463) | 407 (443) | 654 (693) |
| main-document DOM nodes (outside the preview iframe) | 206 | **1 320** | 2 513 | 9 263 | **22 713** |
| preview document (iframe `srcdoc`) KB | 6.8 | 17.5 | 30.0 | 100.1 | 240.3 |
| long-task time during load ms | 0 | 0 | 0 | 52 | 228 |
| rail switch (8 panels) -> painted ms | 31.9 | 31.8 | 31.8 | 31.9 (p95 70.8) | 31.7 (p95 208.5) |
| rail switch, main-thread ms | 1.0 (3.3) | 1.3 (5.9) | 1.5 (9.1) | 3.6 (36.5) | 9.7 (109.0) |
| inspector tab switch -> painted ms | 30.9 | 31.9 | 31.7 | 31.6 | 30.9 |
| select a section: inspector shown ms | 24.1 | 11.2 | 12.6 | 33.5 | 95.3 |
| select a section: **preview iframe fully reloaded** ms | 57.5 | **45.3** | 46.0 | 104.2 | 208.4 |
| select: Profiler actual ms | 1.7 | 2.7 | 4.6 | 21.4 | 67.1 |
| save one field -> preview reloaded ms | 67.6 | **83.2** | 85.4 | 110.0 | 284.4 |
| typing one character in a field: Profiler actual | 0.1 ms, **0 ops sent** (local draft, committed by "Lưu thay đổi") | same | same | same | same |

CPU x4 (3 runs): at 404 sections select = 136.8 ms inspector / 259.1 ms preview reload, rail switch p95 296.9 ms; at 1 004 sections 394.7 / 775.7 ms, rail switch p95 828.9 ms, load 2 112 ms with 1 121 ms of long tasks.

Attribution of one selection (Profiler actualDuration, rail "Thành phần" open = page tree not mounted, versus rail "Trang" open):

| Sections | tree not mounted | tree mounted | share of the selection cost paid by the page-tree rows |
|---:|---:|---:|---:|
| 50 | 0.9 ms | 2.5 ms | 1.6 ms (64 %) |
| 404 | 2.8 ms | 19.7 ms | 16.9 ms (86 %) |
| 1 004 | 9.5 ms | 62.9 ms | 53.4 ms (85 %) |

Canvas scroll (25 wheel steps over the preview, about 1.3 s): the preview posts the rectangle of **every** section to the host on each scroll frame (`lib/schema-preview.ts:114`, `studio:layout`) and the host re-renders each time:

| Sections | layout messages | React commits | Profiler actual summed | worst frame gap | frames > 34 ms |
|---:|---:|---:|---:|---:|---:|
| 4 | 0 | 0 | 0 ms | 18.6 ms | 0 / 95 |
| 50 | 25 | 25 | 60.7 ms (2.4 ms per commit) | 18.6 ms | 0 / 93 |
| 104 | 25 | 25 | 101.8 ms | 18.7 ms | 0 / 98 |
| 404 | 25 | 25 | 435.8 ms | 37.5 ms | 1 / 94 |
| 1 004 | 25 | 25 | 1 439.6 ms | 102.3 ms | **25 / 96** |

### 4.4 Memory and listeners over repeated actions (counters after forced GC, round 5 versus last round)

| Scenario | Rounds | DOM nodes | JS listeners | Heap MB | Documents |
|---|---:|---|---|---|---|
| Org 2 000: expand all + 3 selections + collapse all | 30 | 150 -> 150 | 168 -> 168 | 4.7 -> 5.0 | 1 -> 1 |
| Employees 10 000: type + next page + clear | 30 | 552 -> 552 | 182 -> 182 | 5.3 -> 5.5 | 2 -> 2 |
| Builder 100 sections: 8 rails + select + inspector tabs + Test panel | 25 | 439 -> 439 | 204 -> 204 | 6.3 -> 7.1 | 1 -> 1 |

No leak signal; consistent with `tests/browser/sanity.spec.mjs` (8/8, 3 runs). 0 page errors or console warnings in all three scenarios. (A `Blocked script execution in about:srcdoc` console message that appears when the Test mode makes the preview iframe script-less is produced by the script's own `addInitScript`; it does not occur without it and is filtered in the script.)

### 4.5 Node micro-benchmarks of the pure functions (`scripts/perf-micro.mjs`, ms, median of 5 inside one run; the table is run 1 of 4, the text quotes the range of the 4 runs because the machine was loaded and single runs moved by up to 50 %)

Node micro-benchmarks (median of 5 after 1 warm-up; ms). Pure functions only; HARNESS-class evidence, not a backend measure.
| what | n | ms | note |
|---|---:|---:|---|
| PagesPanel: canStep x2 for every section (PagesPanel.tsx:69-70 -> dnd.ts:56-63) | 50 | 0.16 |  |
| renderSchemaDocument (preview html rebuilt on every selection / edit; BuilderWorkspace.tsx:163) | 50 | 0.03 |  |
| preflight(doc) (BuilderWorkspace.tsx:91 and PagesPanel.tsx:28, i.e. twice per doc change) | 50 | 0.03 |  |
| PagesPanel: canStep x2 for every section (PagesPanel.tsx:69-70 -> dnd.ts:56-63) | 100 | 0.52 |  |
| renderSchemaDocument (preview html rebuilt on every selection / edit; BuilderWorkspace.tsx:163) | 100 | 0.07 |  |
| preflight(doc) (BuilderWorkspace.tsx:91 and PagesPanel.tsx:28, i.e. twice per doc change) | 100 | 0.04 |  |
| PagesPanel: canStep x2 for every section (PagesPanel.tsx:69-70 -> dnd.ts:56-63) | 400 | 2.93 |  |
| renderSchemaDocument (preview html rebuilt on every selection / edit; BuilderWorkspace.tsx:163) | 400 | 0.28 |  |
| preflight(doc) (BuilderWorkspace.tsx:91 and PagesPanel.tsx:28, i.e. twice per doc change) | 400 | 0.05 |  |
| PagesPanel: canStep x2 for every section (PagesPanel.tsx:69-70 -> dnd.ts:56-63) | 1000 | 32.20 |  |
| renderSchemaDocument (preview html rebuilt on every selection / edit; BuilderWorkspace.tsx:163) | 1000 | 0.60 |  |
| preflight(doc) (BuilderWorkspace.tsx:91 and PagesPanel.tsx:28, i.e. twice per doc change) | 1000 | 0.10 |  |
| PagesPanel: canStep x2 for every section (PagesPanel.tsx:69-70 -> dnd.ts:56-63) | 2000 | 72.71 |  |
| renderSchemaDocument (preview html rebuilt on every selection / edit; BuilderWorkspace.tsx:163) | 2000 | 1.07 |  |
| preflight(doc) (BuilderWorkspace.tsx:91 and PagesPanel.tsx:28, i.e. twice per doc change) | 2000 | 0.20 |  |
| buildTree (sort with localeCompare('vi') + wire) | 500 | 1.61 |  |
| flattenTree all open | 500 | 0.07 |  |
| moveTargets (opens the move dialog: buildTree + flatten + per-row rules) | 500 | 1.77 |  |
| unitPath (builds a Map of ALL units per call; EmployeesScreens.tsx:88 calls it per row when the server gives no unit name) | 500 | 0.43 | 20 rows |
| employeesFromMembers: first call (sort + fold keys of the whole member list) | 500 | 0.60 |  |
| employeesFromMembers: later call on the same list (prepared cache) | 500 | 0.04 |  |
| buildTree (sort with localeCompare('vi') + wire) | 2000 | 6.47 |  |
| flattenTree all open | 2000 | 0.06 |  |
| moveTargets (opens the move dialog: buildTree + flatten + per-row rules) | 2000 | 6.43 |  |
| unitPath (builds a Map of ALL units per call; EmployeesScreens.tsx:88 calls it per row when the server gives no unit name) | 2000 | 1.77 | 20 rows |
| employeesFromMembers: first call (sort + fold keys of the whole member list) | 2000 | 1.78 |  |
| employeesFromMembers: later call on the same list (prepared cache) | 2000 | 0.14 |  |
| buildTree (sort with localeCompare('vi') + wire) | 8000 | 22.23 |  |
| flattenTree all open | 8000 | 0.24 |  |
| moveTargets (opens the move dialog: buildTree + flatten + per-row rules) | 8000 | 24.19 |  |
| unitPath (builds a Map of ALL units per call; EmployeesScreens.tsx:88 calls it per row when the server gives no unit name) | 8000 | 11.00 | 20 rows |
| employeesFromMembers: first call (sort + fold keys of the whole member list) | 8000 | 7.45 |  |
| employeesFromMembers: later call on the same list (prepared cache) | 8000 | 0.24 |  |
| buildTree (sort with localeCompare('vi') + wire) | 20000 | 63.86 |  |
| flattenTree all open | 20000 | 0.71 |  |
| moveTargets (opens the move dialog: buildTree + flatten + per-row rules) | 20000 | 88.72 |  |
| unitPath (builds a Map of ALL units per call; EmployeesScreens.tsx:88 calls it per row when the server gives no unit name) | 20000 | 34.87 | 20 rows |
| employeesFromMembers: first call (sort + fold keys of the whole member list) | 20000 | 22.22 |  |
| employeesFromMembers: later call on the same list (prepared cache) | 20000 | 0.86 |  |

## 5. Re-render and complexity hotspots

Method: reading + the Profiler/micro numbers above. "Good" entries are as important as findings: they show what not to touch.

**Findings (also in the issue table):**

| # | File:line | Finding | Evidence |
|---|---|---|---|
| H1 | `features/studio/builder/panels/PagesPanel.tsx:69-70` -> `core/dnd.ts:56-63` | `canStep(sections, id, -1)` and `(..., 1)` per row; each call is `findIndex` + `filter` copy (`planMove`): **O(n²) per render** of the page tree | micro (4 runs, ranges): 0.16-0.17 / 0.50-0.54 / 2.85-2.94 / 20-32 / 68-81 ms at n = 50 / 100 / 400 / 1 000 / 2 000 (about x7 for x4 n). Negligible at the contract maximum, a third to a half of a 63 to 67 ms selection at 1 000. |
| H2 | `BuilderWorkspace.tsx:163` + `Canvas.tsx:41` | Selection puts `selectedId` into the HTML, so **every selection rebuilds the whole preview document and reloads the iframe** (`srcDoc` changes) | preview reload 45 / 104 / 208 ms at 50 / 404 / 1 004 sections; document 17.5 / 100 / 240 KB. `renderSchemaDocument` itself is cheap (0.03 / 0.25 / 0.54 ms): the cost is the browser re-parsing and re-laying out the iframe. |
| H3 | `PagesPanel.tsx:68-70`, `Canvas.tsx:51,62` | One `useSortable` row and one `useDraggable` handle per section, **neither memoised**, handlers recreated each render: a selection re-renders everything (Profiler actual 67.1 ms ≈ base) | attribution 4.3: 85 % of a selection at 404+ sections is the page-tree rows |
| H4 | `lib/schema-preview.ts:114` (preview script) + `Canvas.tsx:25-31`, `BuilderWorkspace.tsx` `setRects` | Each scroll frame posts all rectangles; the host commits each time | 25 / 25 commits; 2.4 ms per commit at 50 sections, 57 ms at 1 004 (25 of 96 frames dropped) |
| H5 | `BuilderWorkspace.tsx` `leftPanel` switch | The page tree unmounts when another rail opens, so returning to "Trang" re-mounts every row | rail "Trang" re-open: main thread p95 36.5 ms (404), 109.0 ms (1 004) |
| H6 | `features/admin/OrganizationScreens.tsx:70` expand-all, `Tree` (:140) | No windowing: expand-all renders every row (13 DOM nodes per row) | 2 000 rows: 83 ms main thread, 200 ms to paint, 26 073 nodes; CPU x4: 306 ms / 738 ms. Not measured beyond 2 000 units (growth would be linear in rows). |
| H7 | `OrganizationScreens.tsx:230` `moveTargets(...)` | Opening the move dialog rebuilds and re-sorts the whole tree (`buildTree` + `flattenTree`) instead of reusing the screen's `tree` memo | micro (4 runs): 5.7 to 6.7 ms at 2 000 units, 53 to 89 ms at 20 000 |
| H8 | `EmployeesScreens.tsx:88` `unitPath(unitList, e.orgUnitId)` per row when the server sends no `orgUnitName` | Builds a Map of **all** units per call: rows x units | micro (20 rows, 4 runs): 1.8 to 2.0 ms at 2 000 units, 31 to 35 ms at 20 000 units |
| H9 | `packages/ui/src/useLoad.ts` | No cache, no de-duplication, no abort: the same list is fetched again by each screen that mounts it (`api.components()` at `StudioApp.tsx:108` and `:331`; `templates("mine")` at `StudioApp.tsx:179` and `libraryPanels.tsx:12`; `blocks("mine")` at `StudioApp.tsx:353` and `libraryPanels.tsx:42`); a request that was superseded is ignored but still runs; `AdminApp.tsx:392` chains two requests (application, then its workspace) | static; no number measured (needs a backend) |
| H10 | `packages/auth` `SessionProvider` | `/auth/config` and `/auth/me` start only after hydration (+117 ms), 3 sequential round trips on the login page | Lighthouse waterfall in 3.1 |

**Checked and fine (do not change):**
- `OrganizationScreens.tsx:119` `TreeRow` is `memo`; `buildTree` / `flattenTree` / `descendantIds` are iterative and O(n log n) / O(n) (micro: flatten 20 000 rows about 0.6 ms; no stack overflow on deep chains).
- `EmployeesScreens.tsx:36` debounce works (1 request per 12 keys); `organizationModel.ts:142` `PREPARED` WeakMap makes later searches 0.04 to 0.66 ms (first call 0.53 to 19.2 ms for 500 to 20 000 members).
- The 2 001-option unit `<select>` (`EmployeesScreens.tsx:47,68`) costs 0.9 ms per typing commit: React bails out of the unchanged options.
- Inspector (`Inspector.tsx`) edits a local draft (0.1 ms per character, 0 operations sent while typing) and commits once.
- `useLoad` ignores stale responses (sequence counter), and conditional polling (`CodeWorkspace.tsx:78`, `CodePanels.tsx:67,121`, `AdminApp.tsx:806`, `AiProgress.tsx:9` 1 s tick) runs only while a job is in flight; `ProjectWorkspace.tsx:114` refreshes assets every 8 minutes.
- Blocking work on the main thread at load: 0 ms of long tasks for every screen at normal sizes (builder at 404+ sections: 52 / 228 ms).

## 6. Cross-browser readiness

### 6.1 What was executed

| Browser | Executed? | Evidence |
|---|---|---|
| Google Chrome 155.0.8059.40 (system, headless=new) | **YES**: Lighthouse, PerformanceObserver, perf-harness, all 11 specs x3, owned-process tests | sections 3 to 8 |
| Chromium bundled with Playwright | NO | `~/Library/Caches/ms-playwright` does not exist |
| Firefox | **NO, NOT EXECUTED** | not installed (no `/Applications/Firefox.app`, no Playwright cache) |
| WebKit (Playwright) | **NO, NOT EXECUTED** | not installed |
| Safari 27.0.1 (installed system app) | **NO, NOT EXECUTED** | no automation attempted: `safaridriver` requires a one-time administrator enable that changes system settings |
| Edge, mobile Safari, Chrome Android | NO | not available. The "mobile" Lighthouse preset and the 390 / 430 px viewports in the specs are Chrome emulation of viewport and CPU, **not** a mobile browser. |

**No Safari or Firefox support claim can be derived from any run in this document.**

### 6.2 STATIC compatibility report (`scripts/compat-scan.mjs`; versions are reference data, re-verify before promising support)

The build target is Next's default (`node_modules/next/dist/shared/lib/modern-browserslist-target.js`): **Chrome 111, Edge 111, Firefox 111, Safari 16.4**; no `.browserslistrc` in the repo. Every feature below is compared with that floor.

STATIC scan (nothing executed in any browser). hits = matching lines; versions = first release with the feature (reference data, re-verify before promising support).
| feature | kind | hit lines | files | first location | Chrome | Safari | Firefox |
|---|---|---:|---:|---|---|---|---|
| :has() | css | 3 | 2 | packages/ui/src/styles/builder.css:194 | 105 | 15.4 | 121 |
| dvh / svh / lvh units | css | 10 | 3 | packages/ui/src/styles/builder.css:155 | 108 | 15.4 | 101 |
| :is() / :where() | css | 4 | 2 | packages/ui/src/styles/builder.css:200 | 88 | 14 | 78 |
| :focus-visible | css | 27 | 5 | packages/ui/src/styles/builder.css:18 | 86 | 15.4 | 85 |
| :focus-within | css | 6 | 3 | packages/ui/src/styles/factory.css:94 | 60 | 10.1 | 52 |
| scroll-padding / scroll-margin | css | 2 | 1 | packages/ui/src/styles/builder.css:203 | 69 | 14.1 | 68 |
| aspect-ratio | css | 1 | 1 | packages/ui/src/styles/factory.css:216 | 88 | 15 | 89 |
| inset shorthand | css | 10 | 4 | packages/ui/src/styles/builder.css:52 | 87 | 14.1 | 66 |
| flex/grid gap | css | 190 | 9 | packages/ui/src/styles/builder.css:3 | 84 (flex) | 14.1 (flex) | 63 (flex) |
| backdrop-filter | css | 1 | 1 | packages/ui/src/styles/globals.css:1 | 76 | 9 (-webkit- until 18) | 103 |
| min() / max() / clamp() | css | 19 | 6 | packages/ui/src/styles/builder.css:78 | 79 | 11.1 | 75 |
| overscroll-behavior | css | 3 | 2 | packages/ui/src/styles/builder.css:203 | 63 | 16 | 59 |
| accent-color | css | 2 | 2 | packages/ui/src/styles/builder.css:172 | 93 | 15.4 | 92 |
| position: sticky | css | 3 | 3 | packages/ui/src/styles/builder.css:197 | 56 | 13 | 32 |
| -webkit-line-clamp | css | 1 | 1 | packages/ui/src/styles/builder.css:123 | 6 | 5 | 68 |
| (pointer: coarse) / (hover: hover) | css | 1 | 1 | packages/ui/src/styles/builder.css:132 | 41 | 9 | 64 |
| logical properties (padding-inline...) | css | 6 | 2 | packages/ui/src/styles/builder.css:180 | 87 | 14.1 | 66 |
| prefers-reduced-motion | css | 8 | 4 | packages/ui/src/styles/builder.css:92 | 74 | 10.1 | 63 |
| grid auto-fit / minmax() | css | 38 | 4 | packages/ui/src/styles/builder.css:2 | 57 | 10.1 | 52 |
| ResizeObserver | js | 2 | 2 | packages/ui/src/useOverflow.ts:12 | 64 | 13.1 | 69 |
| MutationObserver | js | 1 | 1 | packages/ui/src/useOverflow.ts:13 | 26 | 7 | 14 |
| Intl.Collator / localeCompare(locale) | js | 5 | 3 | features/admin/adminModel.ts:116 | 24 (ICU data: full-icu in Node, browsers ship it) | 10 | 29 |
| Intl.RelativeTimeFormat / ListFormat / DateTimeFormat / NumberFormat | js | 5 | 5 | packages/ui/src/ui.tsx:9 | 71 / 72 | 14 / 14.1 | 65 / 78 |
| String.normalize('NFD') + \u0300 range (accent folding) | js | 5 | 4 | features/admin/adminModel.ts:72 | 34 | 10 | 31 |
| Object.fromEntries | js | 6 | 6 | features/studio/CodePanels.tsx:26 | 73 | 12.1 | 63 |
| Array.flat / flatMap | js | 13 | 8 | features/admin/AdminApp.tsx:509 | 69 | 12 | 62 |
| AbortSignal.timeout / any | js | 6 | 4 | packages/api-client/src/api.ts:200 | 103 / 116 | 16 / 17.4 | 100 / 124 |
| crypto.randomUUID (secure context only) | js | 3 | 3 | packages/auth/src/server/csp.ts:17 | 92 | 15.4 | 95 |
| navigator.clipboard (secure context only) | js | 1 | 1 | features/admin/UserDialogs.tsx:16 | 66 | 13.1 | 63 |
| CSS.escape | js | 2 | 2 | features/admin/OrganizationScreens.tsx:145 | 46 | 10 | 31 |
| optional chaining ?. / nullish ?? | js | 842 | 87 | packages/ui/src/Modal.tsx:16 | 80 | 13.1 | 74 |
| Array.prototype.includes / Object.entries | js | 21 | 11 | packages/api-client/src/core.ts:134 | 54 | 10.1 | 47 |
| fetch + AbortController + ReadableStream body | js | 2 | 1 | packages/api-client/src/core.ts:89 | 43 / 52 | 10.1 / 11 | 65 / 6 |
| IndexedDB / localStorage / sessionStorage | js | 11 | 4 | packages/permissions/src/index.ts:99 | 4 | 4 | 3.5 |

Checked and NOT found in the scanned sources: @container queries; color-mix(); subgrid; env(safe-area-inset-*); @layer; CSS nesting (a line starting with &); text-wrap: balance|pretty; color-scheme; prefers-color-scheme; forced-colors; scrollbar-gutter / scrollbar-width; content-visibility; translate / rotate / scale properties; light-dark(); @starting-style / anchor positioning; structuredClone; IntersectionObserver; Intl.Segmenter; Array.prototype.at(); Object.hasOwn; Array findLast / findLastIndex; toSorted / toReversed / toSpliced / with(); Object.groupBy / Map.groupBy; String.replaceAll; requestIdleCallback; <dialog> / showModal(); inert attribute / property; Promise.withResolvers; URL.canParse; Set methods (union, intersection...); Array.fromAsync; window.visualViewport; matchMedia; IntersectionObserver v2 / ElementInternals / popover API; RegExp lookbehind (?<= (?<!; Event Timing / PerformanceObserver (only if used in product code); BroadcastChannel / SharedWorker / Web Locks

scanned 138 files in packages/ui/src, packages/auth/src, packages/api-client/src, packages/company-ui/src, packages/app-sdk/src, packages/i18n/src, packages/permissions/src, features, components, lib, workers/render, apps/platform/app, apps/admin/app, apps/studio/app, app

Assessment of the features that matter (hit locations from the scan):

| Feature | Where | Needs (Chrome / Safari / Firefox) | Above the Next floor? | Fallback |
|---|---|---|---|---|
| `:has()` | `builder.css:194,203` (`html:has(.bx-root)`), `factory.css:144` (`label:has(input:checked)`) | 105 / 15.4 / **121** | **Firefox 111 to 120 only** | **None.** Without it the phone builder's `html,body{height:auto;overflow:visible}` and `scroll-padding-top` do not apply, and the checked-card highlight is missing (cosmetic). Unverified in a browser. |
| `dvh` units | 10 lines, 3 files (`builder.css:155,179,195,205,206`, `responsive.css:2,74,78,275`, `factory.css:380`) | 108 / 15.4 / 101 | No (all within the floor) | `calc(100vh - 60px)` fallback exists only at `builder.css:205-206`; elsewhere `100dvh` alone. |
| `:is()` / `:where()` | `builder.css:200-202`, `http.css:21` | 88 / 14 / 78 | No | none needed |
| `:focus-visible` | 27 lines, 5 files | 86 / 15.4 / 85 | No | none needed |
| `scroll-padding`, `overscroll-behavior` | `builder.css:203`, `factory.css:380` | 69 / 16 / 59 | No (Safari 16.4 floor) | graceful (no containment) |
| `accent-color` | `factory.css:363`, `builder.css:172` | 93 / 15.4 / 92 | No | graceful |
| `backdrop-filter` (unprefixed) | `globals.css:1` (top bar, background alpha 0.95) | 76 / **18 unprefixed** (9 with `-webkit-`) / 103 | **Safari 16.4 to 17.x** lacks the unprefixed form | graceful: no blur, still readable |
| `gap`, `inset`, `min()/max()/clamp()`, logical properties, `aspect-ratio`, grid `minmax/auto-fit`, `position: sticky`, `-webkit-line-clamp`, `(pointer: coarse)`, `prefers-reduced-motion` | many | all at or below Chrome 90 / Safari 15 / Firefox 89 | No | none needed |
| `ResizeObserver` | `packages/ui/src/useOverflow.ts:12` | 64 / 13.1 / 69 | No | guarded (`typeof ResizeObserver !== "undefined"`) |
| `AbortSignal.timeout` | `packages/api-client/src/core.ts:52` (every API call), `api.ts:200,282,311`, `runtimeConfig.ts:79` | 103 / **16** / 100 | No (Safari 16.4) | **none** (unguarded); `AbortSignal.any` not used |
| `crypto.randomUUID` | `api.ts:39`, `release.ts:80` | 92 / 15.4 / 95, **secure context only** | No | guarded with a `Date.now()+Math.random()` fallback; `csp.ts:17` runs on the server |
| `navigator.clipboard.writeText` | `features/admin/UserDialogs.tsx:16` | secure context only | n/a | wrapped in `try/catch`: on plain `http://` the button silently reports "not copied"; the link stays selectable in the input |
| `Intl.DateTimeFormat("vi-VN", { dateStyle, timeStyle })` | `ui.tsx:9`, `drawers.tsx:15`, `ReleaseModal.tsx:18`, `AdminApp.tsx:457` | 76 / 14.1 / 79 | No | none needed |
| `Intl.Collator("vi")` / `localeCompare("vi")` | `organizationModel.ts:27,146`, `adminModel.ts:116` | all | No | ordering follows the engine's ICU data (Vietnamese collation untested in Safari/Firefox) |
| `String.normalize("NFD")` accent folding | `organizationModel.ts:140`, `adminModel.ts:72` | all | No | none needed |
| `requestIdleCallback`, `inert`, `<dialog>`, `structuredClone`, `Array.at`, `Object.hasOwn`, `toSorted`, `groupBy`, `Intl.Segmenter`, Set methods, `@container`, `color-mix`, `subgrid`, `@layer`, CSS nesting, `text-wrap` | **not used** | | | |

Published-site CSS (`lib/preview-document.ts:13-39`, shipped to visitors): `clamp()` (6), `min()` (1), `gap` (7), `grid-template` (6), one `@media`, `scroll-behavior: smooth` unconditionally (no `prefers-reduced-motion` override, S4-021). Nothing newer than Chrome 79 / Safari 11.1 / Firefox 75: the published output is the most conservative CSS in the repo.

JS engine floor of `next build` output: Lighthouse still found 14 KiB of "legacy JavaScript" (array / string polyfills) in the framework chunk, i.e. the build is not tightened to the Next modern target (S4-007).

## 7. Tooling safety (process lifecycle)

### 7.1 Re-run

| Check | Result |
|---|---|
| `node --test tests/lib/owned-process.test.mjs` | **21 / 21 pass, 16 s** (FOREIGN_NEXT_SURVIVES, STALE_PID_SAFE, HARNESS_PID_SCOPED, `stopOwned`, guard scanner, `e2e-stack.sh` behaviour) |
| the same guard inside `npm run test:unit` (wire test `owned-process.wire.test.ts`) | pass (the whole run: 306 tests, 305 pass, 0 fail, 1 skipped, 21 s) |
| grep of 595 tracked files under `tests/ e2e/ docs/parallel/c5/ scripts/ packages/ workers/ features/ components/ lib/ apps/ app/` for `pkill`, `killall`, `lsof` piped into `kill`, `xargs kill`, `kill $(...)`, `kill_port`, `kill -9`, `kill $p` | only hits: `scripts/stop-local.sh:10,13,14,16` (**C0 file**, not C5; the C0 copy on `integration/v2` was rewritten with `own_pid` validation, `git show integration/v2:scripts/stop-local.sh` line 17), the guard test's own pattern list, and the inventory text in `PROCESS_SAFETY.md` |
| `process.kill` / `.kill(` / `SIGKILL` in C5-owned tracked `.mjs` (tests, e2e, new scripts) | only `tests/browser/harness-server.mjs:36,63-64`: closes its own server on SIGTERM and forwards a signal to **the child it spawned** |
| `docs/parallel/c5/e2e-stack.sh` | no `kill`, `pkill`, `lsof`, `nohup`, trailing `&`; all start/stop go through `owned-process-cli.mjs` |
| `tests/browser/*.spec.mjs` headers and `README.md` | every `// Run:` line uses `harness-server.mjs run -- ...`; no `python3 -m http.server`, no backgrounded `next start`; portal instructions use `owned-process-cli.mjs start/stop` |
| Processes started by **this audit** | every server and Chrome started through `owned-process-cli.mjs` or `withOwned`; all five state files stopped with `STOPPED (TERM)`, ports 19101 to 19104 and 19200 free afterwards, `.run/owned/` empty; no port from the forbidden list was used |

### 7.2 Verdict: **PASS**, with two residual hazards

1. **Spec default URL.** Nine specs fall back to `http://127.0.0.1:4000/...` when `HARNESS_URL` is not set (`builder.spec.mjs:8`, `sanity.spec.mjs:7`, `org.spec.mjs:8`, and so on). Nothing is started or stopped, but a run without `harness-server.mjs` navigates whatever listens on 4000 (a foreign process). Read-only, no kill risk. S4-031.
2. **`portals.spec.mjs` hard-coded ports 3001 and 3002** (only Studio was overridable): the spec cannot run beside another stack and would silently test it. **Fixed on this branch** (S4-030): `PORTAL_PLATFORM_PORT`, `PORTAL_ADMIN_PORT` env overrides; the spec then passed 33 / 33 three times against this audit's private builds on 19101 to 19103.

Not mine and not changed: C0's `scripts/{stop-local,public-down,public-up,set-openrouter-key,portals}.sh` unvalidated pid-file kills listed in `PROCESS_SAFETY.md`.
Playwright-launched Chrome in the specs (`chromium.launch`) is not started through the owned-process library; it is a child of the test process closed by `browser.close()` (accepted in `PROCESS_SAFETY.md`). After 33 spec runs I cannot attribute orphans (other agents were running Chrome on the same machine); I did not observe one of mine.

## 8. Debuggability, reproducibility, flakiness, suite time

### 8.1 Flakiness (each spec 3x, fresh browser per run, owned harness server)

| Spec | Checks | Seconds per run | Runs | Fail |
|---|---:|---|---:|---:|
| builder | 87 | 68, 68, 68 | 3 | 0 |
| sanity | 8 | 33, 33, 32 | 3 | 0 |
| org | 89 | 38, 38, 37 | 3 | 0 |
| org-hardening | 69 | 26, 26, 26 | 3 | 0 |
| datasources | 54 | 23, 22, 23 | 3 | 0 |
| release | 56 | 61, 63, 60 | 3 | 0 |
| provisioning | 39 | 21, 22, 22 | 3 | 0 |
| aiproviders | 27 | 11, 11, 12 | 3 | 0 |
| publicdata | 44 | 17, 16, 18 | 3 | 0 |
| page-runtime | 37 | 19, 18, 18 | 3 | 0 |
| portals (vs private builds on 19101 to 19103, no backend) | 33 | 17, 17, 17 | 3 | 0 |
| **Total** | **543 per pass** | **334 s per pass (5.6 min)** | **33** | **0** |

0 failures and a spread of at most 3 s per spec across runs, even though the machine's load average was about 10: no flaky spec observed. Not run (needs Docker / a real backend): `tests/gateway/data-route.mjs`, `tests/e2e-real/` (selftest only is backend-free; not run), `e2e/factory-flow.mjs`.

### 8.2 Suite time

| Step | Time |
|---|---:|
| `npm run test:unit` (tsc of tests + 306 tests incl. 21 owned-process tests with real processes) | 21 s (owned-process 16 s of it) |
| `node tests/browser/build-harness.mjs` (3 bundles variants) | about 3 s each |
| 11 browser specs, one pass | 334 s |
| `npx tsc --noEmit -p tsconfig.json` (root) | 6 s |
| `next build` per app | 8 to 11 s |
| **Unit + build + one pass of all browser specs** | **about 6.5 min** |

### 8.3 Reproducibility and state isolation

Good: every spec uses a fresh browser / page; the harness fakes are in-page; the static server is on a free port per run; `.test-build`, `.run/owned` are per worktree and git-ignored; no spec depends on the order of another. Weak points: `npm run test:unit` **deletes** `.test-build` (including a harness bundle built earlier; README says so) and the specs share that directory, so unit + browser runs in the same worktree cannot be parallelised (S4-036); the default Chrome path in the specs is a Linux CI path (`/opt/pw-browsers/chromium-1194/...`) so `CHROME=` is mandatory on macOS (S4-035); `tests/builder/conformance.test.ts:27-36` is **skipped** unless `XWEB_CONFORMANCE_DIR` is set, which the default `test:unit` summary shows only as "skipped 1" (S4-032).

### 8.4 Stable `data-testid` coverage (STATIC count in `features/ packages/`)

Well covered: `OrganizationScreens.tsx` 48, `EmployeesScreens.tsx` 41, `ProvisioningScreens.tsx` 37, `PublicDataPanels.tsx` 53, `DataSourcesPanel.tsx` 32, `ReleaseModal.tsx` 32, `TenantScreens.tsx` 23, `TestPanel.tsx` 13.
Not covered: `AdminApp.tsx` **1 testid for 142 handlers** (1 196 lines), `StudioApp.tsx` 0 / 35, `ProjectWorkspace.tsx` 0 / 25, `packages/auth/src/AuthPages.tsx` 0 / 17 (the login form), `PagesPanel.tsx` 0 / 27, `Inspector.tsx` 0 / 16, `WorkflowEditor.tsx` 0 / 39, `SitePanels.tsx` 0 / 28, `CodeWorkspace.tsx` 0 / 27. The existing specs reach those through roles, `aria-label` and Vietnamese visible text, which works today and stays accessible-first, but breaks on a copy change (S4-033).

Harness quality: the harnesses are deliberately small hosts with recorders (`window.__ops`, `__org`, `__prov`, `__calls`, `__prof`); `?s=` scenarios make states reproducible; the generated fixtures (2 000 units, 10 000 employees, now `?sections=&pages=`) are deterministic. `sanity.spec.mjs` already guards DOM / listener / heap growth and "no network after load".

## 9. Reproduce

```bash
# private production builds (no backend), restore tsconfig afterwards: git checkout -- tsconfig.json apps/*/tsconfig.json
for a in platform admin studio; do (cd apps/$a && NEXT_DIST_DIR=.next-check-s4 API_PROXY_TARGET=http://127.0.0.1:9 npx next build); done
NEXT_DIST_DIR=.next-check-s4 NEXT_PUBLIC_API_MODE=http API_PROXY_TARGET=http://127.0.0.1:9 npx next build
node scripts/bundle-report.mjs apps/platform/.next-check-s4 apps/admin/.next-check-s4 apps/studio/.next-check-s4 .next-check-s4
node scripts/bundle-report.mjs --modules apps/platform/app/entry.tsx apps/studio/app/entry.tsx            # needs esbuild in /tmp/esb
# serve them (owned, free high ports) and measure
NEXT_DIST_DIR=.next-check-s4 API_PROXY_TARGET=http://127.0.0.1:9 node tests/lib/owned-process-cli.mjs start --state .run/owned/s4-platform.json --cwd apps/platform --port 19101 -- npx next start -H 127.0.0.1 -p 19101
node tests/lib/owned-process-cli.mjs start --state .run/owned/s4-chrome-lh.json --port 19200 --name chrome-lh -- "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --remote-debugging-port=19200 --user-data-dir=<private dir> --no-first-run about:blank
node scripts/perf-lighthouse.mjs --port 19200 --runs 3 --out <dir> http://127.0.0.1:19101/login      # npm i --prefix /tmp/lh lighthouse
node scripts/perf-vitals.mjs --runs 5 --cpu 4 http://127.0.0.1:19101/login
node tests/lib/owned-process-cli.mjs stop --state .run/owned/s4-platform.json                          # validated stop; repeat per state file
# harness performance, hotspots, compatibility
HARNESS_NODE_ENV=production HARNESS_PROFILING=1 node tests/browser/build-harness.mjs
node scripts/perf-harness.mjs --runs 5 --rounds 30 [--cpu 4] [--only org,emp,builder,attrib,scroll,memory] [--json out.json]
node scripts/perf-micro.mjs
node scripts/compat-scan.mjs
# tooling safety
node --test tests/lib/owned-process.test.mjs ; npm run test:unit
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" node tests/browser/harness-server.mjs run -- node tests/browser/builder.spec.mjs
PORTAL_PLATFORM_PORT=19101 PORTAL_ADMIN_PORT=19102 PORTAL_STUDIO_PORT=19103 CHROME=... node tests/browser/portals.spec.mjs
```

New files: `scripts/bundle-report.mjs`, `scripts/perf-env.mjs`, `scripts/perf-harness.mjs`, `scripts/perf-lighthouse.mjs`, `scripts/perf-vitals.mjs`, `scripts/perf-micro.mjs`, `scripts/compat-scan.mjs` (tooling only). Edited tooling: `tests/browser/harness.tsx` (Profiler + `?sections=&pages=`), `tests/browser/org-harness.tsx` (`emp-10k-bigorg`), `tests/browser/build-harness.mjs` (`HARNESS_PROFILING`), `tests/browser/portals.spec.mjs` (port env overrides). All edits are backwards compatible (defaults unchanged); no product code, no HOT FILE, no migration, no contract touched.

## 10. Issue table

Severity: P0 unusable / security-critical, P1 major, P2 significant polish / performance, P3 minor. **No P0 and no P1 was found.** The builder scale items are rated against the contract: at the 50-section maximum they cost a few milliseconds; they are P3 because only a contract change (or a bug that bypasses the validator) would make them bite.

| ID | PORTAL | ROUTE / COMPONENT | SEVERITY | CATEGORY | EXPECTED | ACTUAL (measured) | ROOT_CAUSE (file:line) | OWNER | PROPOSED FIX | TEST | STATUS |
|---|---|---|---|---|---|---|---|---|---|---|---|
| S4-001 | Platform, Admin, Studio, root | `/login`, `/auth/*` (every route) | P2 | Performance / bundle | A login page loads little more than the login code | First Load JS 809.4 KB raw / 230.0 gzip (Platform, Admin), 912.6 / 267.6 (Studio), 1 198.0 / 340.8 (root); 0 dynamically loaded chunks; login-only code is 53.7 KB min versus 399.5 / 517.1; Lighthouse unused JS 133 KiB on `/login` (83.9 % of the app chunk) | `apps/platform/app/entry.tsx:4,7`, `apps/admin/app/entry.tsx:4,7`, `apps/studio/app/entry.tsx:4,7` import the console statically; `packages/auth/src/PortalApp.tsx:47` `render(...)` | S3 shared UI (PortalApp) with S2 / S1 (entries) | Lazy-load the console: `const AdminApp = lazy(() => import("@/features/admin/AdminApp"))` (or `next/dynamic`) inside `render`, so the gate and login stay small | `node scripts/bundle-report.mjs` after build: Platform First Load below about 500 KB raw (estimate from the proxy: 809.4 - (366.7 - 54)); `tests/browser/portals.spec.mjs` still 33/33 | OPEN |
| S4-002 | Studio | all routes after login (`/studio`, projects, templates) | P2 | Performance / bundle | The builder (and its libraries) load when a project opens | Studio chunk 469.9 KB raw: builder + code workspace + modals are 367.9 of 517.1 KB min (71 %) and `@dnd-kit` is 48.8 KB min, all before any project is open | `features/studio/StudioApp.tsx:15` imports `ProjectWorkspace`, which imports `BuilderWorkspace` (`ProjectWorkspace.tsx:12`), `CodeWorkspace` (:27), `ReleaseModal` (:23), drawers (:22), `SitePanels` (:28) statically | S1 Studio | Lazy `ProjectWorkspace` at the route; lazy `PublishModal`, `CodeWorkspace`, `SiteDrawer`; lazy rail panels (`DataWizard`, `PublicDataPanels`, `WorkflowEditor`, `ActionEditor`) | bundle-report: Studio initial chunk without `@dnd-kit`; `builder.spec.mjs` and `release.spec.mjs` unchanged | OPEN |
| S4-003 | Platform, Admin | every console route | P3 | Performance / bundle | Only the visited section's code loads | One 366.7 KB (99.5 KB gzip) chunk in **both** portals (each carries the other portal's screens); `AdminApp.tsx` is 118.4 KB min, AI setup 33.2, organization 24.3, tenants 23.2, employees 17.1, provisioning 14.1 | `features/admin/AdminApp.tsx` (1 196 lines, about 25 pages in one module, imports at :9-22); `entry.tsx` passes `portal` to the same component | S2 Platform-Admin | Split by section (`lazy` per page keyed by `seg`) | bundle-report: smaller initial chunk per portal | OPEN |
| S4-004 | Platform, Admin | `features/admin/TenantScreens.tsx` | P3 | Architecture / bundle | Admin does not pull builder code | 24.0 KB min of `features/studio` (DataSourcesPanel 13.7, `dataManagement.ts` 8.3) inside the Platform / Admin bundle | `features/admin/TenantScreens.tsx:20-21` imports `../studio/builder/DataSourcesPanel` | S2 (with S1) | Move the shared panel to `packages/ui` or lazy-load it | bundle-report module table | OPEN |
| S4-005 | all | HTML of every page | P3 | Performance / caching | Back/forward navigation restores the page (bf-cache) | Lighthouse `bf-cache`: `MainResourceHasCacheControlNoStore` and `JsNetworkRequestReceivedCacheControlNoStore`; HTML header `Cache-Control: private, no-cache, no-store, max-age=0, must-revalidate` | per-request CSP nonce: `packages/auth/src/server/csp.ts`, `apps/*/proxy.ts`, `layout.tsx` `await connection()` | **NOT C5** (C0 / security) | Decision for C0: keep (nonce is a security design) or move to hash-based CSP for static shells | Lighthouse bf-cache audit | OPEN |
| S4-006 | all | `/login` | P3 | Performance / CSS | Critical CSS only on the login page | One render-blocking CSS file: 63.0 KB (13.6 gzip) Platform / Admin, 81.1 KB (17.0 gzip) Studio; Lighthouse render-blocking 164 ms (simulated), unused CSS about 13 KiB | `apps/*/app/layout.tsx:5-8` loads `globals + responsive + http + factory` for every route (and `builder.css` for Studio) | S3 shared UI | Split `factory.css` / `builder.css` per lazy chunk after S4-001 | Lighthouse `unused-css-rules` | OPEN |
| S4-007 | all | framework chunk | P3 | Performance / build target | No polyfills for the declared browser floor | Lighthouse legacy-javascript 14 KiB (`Array.prototype.at/flat/flatMap`, `Object.fromEntries`, `Object.hasOwn`, `trimStart/End`) in `1rj7ns8rte9vc.js`; 110 KB `nomodule` polyfill file exists (not loaded by modern browsers) | Next default target (Chrome / Edge / Firefox 111, Safari 16.4) with no `.browserslistrc` / `browsersListForSwc` | **NOT C5** (`packages/auth/src/server/nextConfig.ts`, C0) | State the supported floor; set browserslist accordingly | Lighthouse `legacy-javascript-insight` | OPEN |
| S4-008 | root (legacy) | `components/app/AppEntry.tsx` | P3 | Performance / bundle | The legacy shell may stay but should not carry both consoles | First Load JS 1 198.0 KB raw / 340.8 gzip: one 755.3 KB chunk with `StudioApp` **and** `AdminApp` | `components/app/AppEntry.tsx:10-11` | S1 Studio (legacy shell, "do not remove old features") | Same lazy split as S4-001 / S4-002 | bundle-report root | OPEN |
| S4-010 | Studio | Builder > rail "Trang" > `PagesPanel` | P3 | Performance / complexity | Linear work per render | O(n²): `canStep` x2 per row = 0.16-0.17 / 0.50-0.54 / 2.85-2.94 / 20-32 / 68-81 ms per render (4 micro runs) at n = 50 / 100 / 400 / 1 000 / 2 000 | `features/studio/builder/panels/PagesPanel.tsx:69-70`; `core/dnd.ts:56-63` (`planStep` -> `planMove`: `findIndex` + `filter` copy per call) | S1 Studio | Compute `first` / `last` from the index (and the trailing-Footer rule) in O(1) per row; keep `canStep` for single use | `scripts/perf-micro.mjs` rows; unit test that the O(1) rule equals `canStep` for all positions | OPEN |
| S4-011 | Studio | Builder canvas selection | P3 | Performance / rendering | Selecting a section highlights it without reloading the preview | Every selection rebuilds `srcdoc` and reloads the iframe: 45 / 104 / 208 ms at 50 / 404 / 1 004 sections (x1), 776 ms at 1 004 (x4); document 17.5 / 100 / 240 KB | `features/studio/builder/BuilderWorkspace.tsx:163` (`selectedId` is an input of the HTML) + `Canvas.tsx:41` (`srcDoc={html}`) | S1 Studio | Post a `studio:highlight` message to the preview script instead of changing `srcDoc` (the iframe already posts `studio:select` / `studio:layout`) | `perf-harness` "preview iframe fully reloaded" row; `builder.spec.mjs` selection checks | OPEN |
| S4-012 | Studio | Builder re-renders on selection | P3 | Performance / memoisation | A selection re-renders the two rows that changed | Profiler actual 1.7 / 2.7 / 21.4 / 67.1 ms at 4 / 50 / 404 / 1 004 sections, about equal to base duration (nothing memoised); the page-tree rows are 85 % of it at 404+; rail "Trang" re-open 36.5 / 109.0 ms (p95) at 404 / 1 004 | `PagesPanel.tsx:68-70` (`SectionNode` not `memo`, inline closures, one `useSortable` each), `Canvas.tsx:51,62` (`Handle` not `memo`, one `useDraggable` each), `BuilderWorkspace.tsx` `leftPanel` unmounts the tree | S1 Studio | `memo` + stable handlers for `SectionNode` / `Handle`; keep the tree mounted (hidden) or window it above about 200 rows | `perf-harness` attribution table | OPEN |
| S4-013 | Studio | Builder canvas scroll | P3 | Performance / rendering | Scrolling the preview does not re-render the workspace | 25 wheel steps -> 25 layout messages -> 25 host commits: 2.4 ms each at 50 sections (0 dropped frames), 57 ms each at 1 004 (25 of 96 frames > 34 ms, worst gap 102 ms) | preview script `lib/schema-preview.ts:114` posts all rects on every scroll frame; host `setRects` in `Canvas.tsx:25-31` / `BuilderWorkspace.tsx` | S1 Studio | Post rects only on layout change (resize / content), derive handle `top` from document coordinates, compare before `setRects` | `perf-harness` "CANVAS SCROLL" rows | OPEN |
| S4-014 | Platform, Admin | Organization > tree | P3 | Performance / DOM size | Windowed rows for very large trees | "Mở rộng tất cả" on 2 000 units: 83.1 ms main thread, 200.3 ms to paint (one run 737.8 ms), 26 073 DOM nodes; CPU x4: 306 / 738 ms; toggling one node 31.2 ms (p95 61.2). Not measured above 2 000 units | `features/admin/OrganizationScreens.tsx:70` (expand all), `Tree` :140-164 renders every visible row | S2 Platform-Admin | Window the visible list above about 1 000 rows (rows are fixed height); keep memo rows | `org-hardening.spec.mjs` PERF02 plus `perf-harness` | OPEN |
| S4-015 | Platform, Admin | Organization > move dialog | P3 | Performance / complexity | Reuse the already built tree | `moveTargets` rebuilds + re-sorts the whole tree: 5.7 to 6.7 ms at 2 000 units, 53 to 89 ms at 20 000 (4 runs) | `OrganizationScreens.tsx:230` -> `organizationModel.ts:89-105` (`buildTree(units)` again) | S2 Platform-Admin | Pass the screen's `tree` memo into `moveTargets` | `perf-micro` moveTargets row | OPEN |
| S4-016 | Platform, Admin | Employees > rows without server unit name | P3 | Performance / complexity | O(rows + units), not rows x units | `unitPath` per row builds a Map of all units: 20 rows cost 1.8 to 2.0 ms at 2 000 units, 31 to 35 ms at 20 000 (4 runs) | `features/admin/EmployeesScreens.tsx:88` -> `organizationModel.ts:68` | S2 Platform-Admin | Build the id->unit Map once (`useMemo` over `unitList`) and pass it | `perf-micro` unitPath row | OPEN |
| S4-017 | Platform, Admin, Studio | data loading (`useLoad`) | P3 | Performance / network | One fetch per resource per view; requests that are superseded are cancelled | No cache, no de-duplication, no abort: `api.components()` at `StudioApp.tsx:108` and `:331`, `templates("mine")` at `:179` and `libraryPanels.tsx:12`, `blocks("mine")` at `:353` and `libraryPanels.tsx:42`; `AdminApp.tsx:392` chains two requests. On `/login`: `/auth/config` and `/auth/me` start at +117 ms, after hydration (3 sequential round trips) | `packages/ui/src/useLoad.ts:5-17` (no key, no `AbortController`) | S3 shared UI | Small keyed cache (stale-while-revalidate) and `AbortSignal` in `useLoad`; start session requests before hydration (`<link rel=preload>` / server-side) | static; verify with the e2e-real network log | OPEN |
| S4-020 | Studio (phone builder) | `builder.css:194,203` | P3 | Browser compatibility (STATIC) | A fallback when `:has()` is missing | `html:has(.bx-root)` has no fallback; `:has()` needs Firefox 121, Next's floor is Firefox 111: on 111 to 120 the page-scroll reset and `scroll-padding-top` do not apply (not executed in Firefox) | `packages/ui/src/styles/builder.css:194,203`; `factory.css:144` | S3 shared UI | Add a class on `<html>` from the builder (`useEffect`) instead of `:has()`, or raise the declared floor | Firefox run once one exists (S4-022) | OPEN |
| S4-021 | published sites, Studio preview | `lib/preview-document.ts:13` | P3 | Accessibility / motion (STATIC) | Smooth scrolling off when the visitor asks for reduced motion | `html{scroll-behavior:smooth}` unconditional; the published CSS has no `prefers-reduced-motion` rule (the console CSS has 8) | `lib/preview-document.ts:13` | S1 Studio (file is C5-owned) | `@media (prefers-reduced-motion: no-preference){html{scroll-behavior:smooth}}`; renderer output changes, so republish note for C2 | `page-runtime.spec.mjs` + a CSS assertion | OPEN |
| S4-022 | all | cross-browser | P2 | Test coverage | At least one non-Chromium engine exercised for the builder (sandboxed `srcdoc` iframe, `@dnd-kit` pointer events, `:has`) and the login pages | **Chrome 155 only.** No Firefox, no WebKit, no Playwright browser cache; Safari 27.0.1 installed but not drivable by Playwright | environment + no CI browser matrix (CI addition is forbidden by CLAUDE.md) | S4 with C0 (infra) | Install Playwright Firefox / WebKit on the integration machine (`npx playwright install firefox webkit`), run `portals`, `builder`, `org` specs with `CHROME` replaced by a browser option; or one manual Safari pass for the phone builder | the same specs under `firefox` / `webkit` | OPEN |
| S4-030 | n/a (tooling) | `tests/browser/portals.spec.mjs:8` | P2 | Tooling safety / reproducibility | Every port overridable; never test a foreign stack by accident | Platform 3001 and Admin 3002 hard-coded (only Studio overridable): on a machine with the live stack the spec reads that stack | `tests/browser/portals.spec.mjs:8` (before this branch) | S4 | `PORTAL_PLATFORM_PORT` / `PORTAL_ADMIN_PORT` env | spec 33 / 33 x3 against private builds on 19101-19103 | **FIXED in this branch (tooling)** |
| S4-031 | n/a (tooling) | 9 `tests/browser/*.spec.mjs` | P3 | Tooling safety | A spec run without its owned server fails loudly | Default `HARNESS_URL` / `DS_HARNESS_URL` = `http://127.0.0.1:4000/...`: without `harness-server.mjs run` the spec navigates a foreign process on 4000 (read-only) | `builder.spec.mjs:8`, `sanity.spec.mjs:7`, `org.spec.mjs:8`, `org-hardening.spec.mjs:10`, `provisioning.spec.mjs:7`, `publicdata.spec.mjs:8`, `release.spec.mjs:7`, `aiproviders.spec.mjs:7`, `datasources.spec.mjs:6` | S4 | Exit with "run through harness-server.mjs" when the variable is unset | run a spec without the variable | OPEN |
| S4-032 | n/a (tooling) | `tests/builder/conformance.test.ts:27-36` | P3 | Test coverage | The contract conformance check runs in the default unit run or is clearly red/pending | Skipped unless `XWEB_CONFORMANCE_DIR` is set: `npm run test:unit` shows "skipped 1" and passes | `tests/builder/conformance.test.ts:27,31,36` (`{ skip }`) | S4 / S3 | Print a prominent pending line in `scripts/test-unit.mjs`, or fail in CI when the env is absent | unit summary | OPEN |
| S4-033 | Platform, Admin, Studio | login, `AdminApp`, `StudioApp`, `ProjectWorkspace`, `PagesPanel`, `Inspector` | P3 | Testability | Stable hooks on the main controls | `data-testid` count 1 / 142 handlers (`AdminApp.tsx`), 0 / 35 (`StudioApp`), 0 / 25 (`ProjectWorkspace`), 0 / 17 (`AuthPages`), 0 / 27 (`PagesPanel`), 0 / 16 (`Inspector`); newer screens have 23 to 53 | files listed | S1 / S2 / S3 per file | Add `data-testid` to primary actions as screens are touched; do not rewrite specs | grep count in review | OPEN |
| S4-035 | n/a (tooling) | specs' `chromium.launch` | P3 | Reproducibility | `CHROME` default works on the developer's OS | Default `executablePath` is `/opt/pw-browsers/chromium-1194/chrome-linux/chrome` (a Linux CI path) in 10 of the 11 browser specs (all but `page-runtime.spec.mjs`): on macOS they fail unless `CHROME=` is set | `tests/browser/*.spec.mjs` (`process.env.CHROME ?? "/opt/pw-browsers/..."`, one line per spec) | S4 | Try the platform's Chrome path, then fail with a message | run a spec without `CHROME` | OPEN |
| S4-036 | n/a (tooling) | `scripts/test-unit.mjs` / `.test-build` | P3 | State isolation | Unit and browser runs do not delete each other's output | `scripts/test-unit.mjs` does `rmSync(.test-build)`, the same directory the harness bundles use: the two cannot run in parallel in one worktree, and a unit run removes built harnesses | `scripts/test-unit.mjs` (C0 file), `tests/browser/build-harness.mjs` | scripts/ is C0 unless C5-owned (owner unclear); S4 proposes the harness side | Build harnesses into a separate directory (`.test-build-browser`) | run both in parallel | OPEN |

IDs S4-009, S4-018, S4-019, S4-023 to S4-029, S4-034 are intentionally unused (candidates dropped after measurement or merged: Platform / Admin duplicate chunk merged into S4-003; the 2 001-option select, the debounce, the member cache and the memory growth were measured and are fine).

Counts: **P0 0, P1 0, P2 4 (S4-001, S4-002, S4-022, S4-030), P3 21, total 25**; STATUS: 24 OPEN, 1 FIXED in this branch (S4-030).

## 11. Recommendations, ordered by measured impact

1. **Lazy-load the console behind the auth gate (S4-001).** The only change that moves a user-visible number for every visitor: 53.7 KB of code is enough for the login page, 345 to 463 KB (min) of it is not needed there. Largest single saving available; the framework's 442.7 KB stays.
2. **Lazy-load the builder, `@dnd-kit` and the publish / code / site surfaces in Studio (S4-002)**, then per-section Admin pages (S4-003) and the shared `DataSourcesPanel` (S4-004). Together they remove most of the 367.9 + 48.8 KB (Studio) and the 24 + 270 KB (Admin) from the first load.
3. **Check the edge serves brotli.** Brotli is 17 % smaller than gzip for the same files (Platform 190.9 versus 230.0 KB; Studio 221.6 versus 267.6 KB); `next start` serves gzip only. If the production edge (nginx / CDN) does not compress with brotli, this is free. Not verified here (no production edge reachable).
4. **Collapse the login round trips (S4-017 / H10).** Session requests start 117 ms after navigation, after the script; on a 150 ms RTT link that is about 450 ms of latency before the form knows the session state.
5. **Builder at scale (S4-010, S4-011, S4-012, S4-013).** Only worth doing if the 50-section / 20-page limit is raised; at the limit every builder action measured is within two 60 Hz frames. The first two are cheap and local (O(1) `canStep`; highlight by message instead of `srcdoc`).
6. **Large organizations (S4-014 to S4-016).** 2 000 units are fine (memoised rows: 1 ms per selection); expand-all is the visible cost (200 ms, 26 k nodes); windowing is the fix if tenants beyond a few thousand units are expected.
7. **Test the engines users have (S4-022).** Nothing measured here says Safari or Firefox work; the only static risks found are `:has()` (S4-020) and unprefixed `backdrop-filter` (cosmetic).
8. **Tooling (S4-031, S4-032, S4-033, S4-035, S4-036).** Small, local, safe changes that make the suite fail loudly instead of silently passing or hitting a foreign port.

## 12. What was NOT measured, and why

- **Real backend behaviour, authenticated pages, real Core Web Vitals / field data / INP of real sessions**: no backend and no RUM in this phase. Lighthouse and the observers ran only on the unauthenticated login pages; the console screens (`AdminApp`, `StudioApp`, `ProjectWorkspace`) were measured only through the harness components, never as a logged-in app.
- **Firefox, WebKit, Safari, Edge, mobile browsers**: not installed / not drivable (section 6.1). Static report only.
- **Throttled network in a real browser**: Lighthouse simulates; Playwright runs on localhost. No packet-level throttling was applied.
- **Memory beyond 30 rounds, and under real data volumes**; organization trees larger than 2 000 units; employee lists beyond 10 000; builder pages beyond 1 004 sections.
- **Builder drag and drop performance** (pointer drag of a library item or a row): functionally covered by `builder.spec.mjs` (87 checks, passing) but not timed.
- **Gradle backend, `e2e-stack.sh up`, `tests/gateway` (needs Docker), `tests/e2e-real`, `e2e/factory-flow.mjs`**: not run (backend / Docker not part of this phase).
- **Brotli at the real edge, HTTP/2 / HTTP/3, CDN cache headers**: no production edge reachable.
- **Machine load**: the machine was shared (load about 10). Medians over 3 to 5 runs and the 2-frame floor are the mitigation; single outliers are shown as p95 / max (for example one 737.8 ms expand-all run out of 5).

---

## Appendix A. Bundle report (raw output of `scripts/bundle-report.mjs`)


### apps/platform/.next-check-s4
First Load JS (rootMainFiles + route entry chunks, excl. nomodule polyfill): raw 809.4 KB | gzip 230.0 KB | brotli 190.9 KB
polyfill (nomodule, not loaded by modern browsers): raw 110.0 KB | gzip 38.7 KB
all JS on disk: 7 files, raw 919.4 KB | gzip 268.7 KB | brotli 225.2 KB     all CSS: 1 files raw 63.0 KB | gzip 13.6 KB
dynamically loaded (not in first load) JS: 0 files
| chunk | raw KB | gzip KB | brotli KB | in first load |
|---|---:|---:|---:|---|
| chunks/3_r_2vz4l3q_g.js | 366.7 | 99.5 | 79.6 | yes |
| chunks/1rj7ns8rte9vc.js | 223.8 | 69.9 | 59.8 | yes |
| chunks/1wym20oe2_8ih.js | 172.4 | 46.1 | 38.8 | yes |
| chunks/0cz1d0mv5g_q7.js | 110.0 | 38.7 | 34.3 | nomodule |
| chunks/1wtqnum3h83ky.css | 63.0 | 13.6 | 11.9 | css |
| chunks/1u5zan5bs9a7v.js | 23.0 | 7.1 | 6.3 | yes |
| chunks/3fntmmi971322.js | 14.0 | 3.6 | 3.2 | yes |
| chunks/turbopack-11y464gitbq1t.js | 9.5 | 3.7 | 3.3 | yes |

### apps/admin/.next-check-s4
First Load JS (rootMainFiles + route entry chunks, excl. nomodule polyfill): raw 809.4 KB | gzip 230.0 KB | brotli 190.9 KB
polyfill (nomodule, not loaded by modern browsers): raw 110.0 KB | gzip 38.7 KB
all JS on disk: 7 files, raw 919.4 KB | gzip 268.7 KB | brotli 225.2 KB     all CSS: 1 files raw 63.0 KB | gzip 13.6 KB
dynamically loaded (not in first load) JS: 0 files
| chunk | raw KB | gzip KB | brotli KB | in first load |
|---|---:|---:|---:|---|
| chunks/3xqtrsd9zjmjb.js | 366.7 | 99.5 | 79.5 | yes |
| chunks/1rj7ns8rte9vc.js | 223.8 | 69.9 | 59.8 | yes |
| chunks/1wym20oe2_8ih.js | 172.4 | 46.1 | 38.8 | yes |
| chunks/0cz1d0mv5g_q7.js | 110.0 | 38.7 | 34.3 | nomodule |
| chunks/1wtqnum3h83ky.css | 63.0 | 13.6 | 11.9 | css |
| chunks/1u5zan5bs9a7v.js | 23.0 | 7.1 | 6.3 | yes |
| chunks/3fntmmi971322.js | 14.0 | 3.6 | 3.2 | yes |
| chunks/turbopack-11y464gitbq1t.js | 9.5 | 3.7 | 3.3 | yes |

### apps/studio/.next-check-s4
First Load JS (rootMainFiles + route entry chunks, excl. nomodule polyfill): raw 912.6 KB | gzip 267.6 KB | brotli 221.6 KB
polyfill (nomodule, not loaded by modern browsers): raw 110.0 KB | gzip 38.7 KB
all JS on disk: 7 files, raw 1022.6 KB | gzip 306.3 KB | brotli 255.9 KB     all CSS: 1 files raw 81.1 KB | gzip 17.0 KB
dynamically loaded (not in first load) JS: 0 files
| chunk | raw KB | gzip KB | brotli KB | in first load |
|---|---:|---:|---:|---|
| chunks/01re3y0any0cr.js | 469.9 | 137.1 | 110.2 | yes |
| chunks/1wepaifw1fhdo.js | 223.8 | 70.0 | 59.8 | yes |
| chunks/1wfxwzirr5qpx.js | 172.4 | 46.1 | 38.8 | yes |
| chunks/0cz1d0mv5g_q7.js | 110.0 | 38.7 | 34.3 | nomodule |
| chunks/23dl4_w7scuq6.css | 81.1 | 17.0 | 14.7 | css |
| chunks/2gvhh090iiesp.js | 23.0 | 7.1 | 6.3 | yes |
| chunks/3fntmmi971322.js | 14.0 | 3.6 | 3.2 | yes |
| chunks/turbopack-2rhgfkzk-4d1y.js | 9.5 | 3.7 | 3.3 | yes |

### .next-check-s4
First Load JS (rootMainFiles + route entry chunks, excl. nomodule polyfill): raw 1198.0 KB | gzip 340.8 KB | brotli 273.4 KB
polyfill (nomodule, not loaded by modern browsers): raw 110.0 KB | gzip 38.7 KB
all JS on disk: 7 files, raw 1307.9 KB | gzip 379.5 KB | brotli 307.7 KB     all CSS: 1 files raw 81.1 KB | gzip 17.0 KB
dynamically loaded (not in first load) JS: 0 files
| chunk | raw KB | gzip KB | brotli KB | in first load |
|---|---:|---:|---:|---|
| chunks/2onqj7-2momqt.js | 755.3 | 210.3 | 162.0 | yes |
| chunks/1wepaifw1fhdo.js | 223.8 | 70.0 | 59.8 | yes |
| chunks/1wfxwzirr5qpx.js | 172.4 | 46.1 | 38.8 | yes |
| chunks/0cz1d0mv5g_q7.js | 110.0 | 38.7 | 34.3 | nomodule |
| chunks/23dl4_w7scuq6.css | 81.1 | 17.0 | 14.7 | css |
| chunks/2gvhh090iiesp.js | 23.0 | 7.1 | 6.3 | yes |
| chunks/3fntmmi971322.js | 14.0 | 3.6 | 3.2 | yes |
| chunks/turbopack-2rhgfkzk-4d1y.js | 9.5 | 3.7 | 3.3 | yes |

## Appendix A2. Module composition (esbuild PROXY, raw)


### esbuild PROXY for apps/platform/app/entry.tsx (minified, tree-shaken, next/* external; NOT the Next bundle) total 399.5 KB min, gzip 104.1 KB
| package / dir | KB (minified, in output) | % |
|---|---:|---:|
| features/admin | 270.9 | 67.8 |
| features/studio | 24.0 | 6.0 |
| lucide-react | 21.8 | 5.4 |
| packages/api-client | 21.4 | 5.3 |
| packages/auth | 14.9 | 3.7 |
| packages/ui | 13.3 | 3.3 |
| lib/schema-preview.ts | 9.1 | 2.3 |
| react | 7.9 | 2.0 |
| lib/preview-document.ts | 3.9 | 1.0 |
| react-dom | 3.5 | 0.9 |
| packages/permissions | 2.8 | 0.7 |
| features/library.tsx | 2.3 | 0.6 |
| packages/i18n | 1.6 | 0.4 |
| simple-icons | 1.6 | 0.4 |
| packages/types | 0.4 | 0.1 |
| apps/platform | 0.1 | 0.0 |
| <define:process.env> | 0.0 | 0.0 |
| lib/http-api.ts | 0.0 | 0.0 |

| largest source files | KB |
|---|---:|
| features/admin/AdminApp.tsx | 118.4 |
| features/admin/AiSetup.tsx | 33.2 |
| features/admin/OrganizationScreens.tsx | 24.3 |
| features/admin/TenantScreens.tsx | 23.2 |
| features/admin/EmployeesScreens.tsx | 17.1 |
| packages/api-client/src/api.ts | 16.7 |
| features/admin/ProvisioningScreens.tsx | 14.1 |
| features/studio/builder/DataSourcesPanel.tsx | 13.7 |
| packages/auth/src/AuthPages.tsx | 12.2 |
| features/admin/organizationModel.ts | 10.5 |
| lib/schema-preview.ts | 9.1 |
| features/admin/provisioningModel.ts | 9.0 |
| features/studio/builder/core/dataManagement.ts | 8.3 |
| node_modules/react/cjs/react.production.js | 7.5 |
| features/admin/adminModel.ts | 7.0 |
| packages/ui/src/ui.tsx | 5.4 |
| packages/api-client/src/core.ts | 4.4 |
| lib/preview-document.ts | 3.9 |
| node_modules/react-dom/cjs/react-dom.production.js | 3.3 |
| packages/ui/src/Picker.tsx | 2.9 |
| features/admin/PersonPicker.tsx | 2.9 |
| features/admin/organization.ts | 2.7 |
| packages/permissions/src/index.ts | 2.6 |
| features/admin/provisioning.ts | 2.5 |
| features/library.tsx | 2.3 |

### esbuild PROXY for apps/studio/app/entry.tsx (minified, tree-shaken, next/* external; NOT the Next bundle) total 517.1 KB min, gzip 145.4 KB
| package / dir | KB (minified, in output) | % |
|---|---:|---:|
| features/studio | 367.9 | 71.2 |
| @dnd-kit/core | 38.7 | 7.5 |
| packages/api-client | 30.8 | 5.9 |
| packages/auth | 14.9 | 2.9 |
| lucide-react | 10.0 | 1.9 |
| lib/schema-preview.ts | 9.1 | 1.8 |
| packages/ui | 8.4 | 1.6 |
| react | 8.0 | 1.5 |
| @dnd-kit/sortable | 6.4 | 1.2 |
| packages/permissions | 4.4 | 0.8 |
| lib/preview-document.ts | 3.9 | 0.7 |
| react-dom | 3.5 | 0.7 |
| @dnd-kit/utilities | 3.1 | 0.6 |
| features/library.tsx | 2.8 | 0.5 |
| packages/types | 2.7 | 0.5 |
| packages/i18n | 1.6 | 0.3 |
| @dnd-kit/accessibility | 0.6 | 0.1 |
| components/SectionInspector.tsx | 0.2 | 0.0 |

| largest source files | KB |
|---|---:|
| node_modules/@dnd-kit/core/dist/core.esm.js | 38.7 |
| features/studio/StudioApp.tsx | 31.9 |
| features/studio/ProjectWorkspace.tsx | 23.4 |
| features/studio/CodeWorkspace.tsx | 20.2 |
| packages/api-client/src/api.ts | 17.0 |
| features/studio/builder/PublicDataPanels.tsx | 16.5 |
| features/studio/builder/DataWizard.tsx | 16.1 |
| features/studio/ReleaseModal.tsx | 15.3 |
| features/studio/CodePanels.tsx | 15.1 |
| features/studio/builder/DataSourcesPanel.tsx | 13.7 |
| features/studio/builder/WorkflowEditor.tsx | 13.4 |
| features/studio/SitePanels.tsx | 12.7 |
| packages/auth/src/AuthPages.tsx | 12.2 |
| features/studio/builder/panels/PagesPanel.tsx | 12.2 |
| features/studio/builder/core/definition.ts | 12.2 |
| features/studio/builder/TestPanel.tsx | 11.1 |
| features/studio/builder/ActionEditor.tsx | 11.0 |
| features/studio/builder/BuilderWorkspace.tsx | 11.0 |
| features/studio/builder/Inspector.tsx | 11.0 |
| features/studio/drawers.tsx | 10.7 |
| features/studio/builder/core/publicData.ts | 10.0 |
| packages/api-client/src/release.ts | 9.4 |
| lib/schema-preview.ts | 9.1 |
| features/studio/builder/core/errors.ts | 8.3 |
| features/studio/builder/core/dataManagement.ts | 8.3 |

## Appendix B. Harness performance, CPU x1 (raw output of `scripts/perf-harness.mjs --runs 5 --rounds 30`)


HARNESS, NOT REAL BACKEND. Generated fixtures + in-page fakes; headless Chrome 155.0.8059.40 on darwin arm64, Node v22.23.1; bundle .test-build/browser-prof; runs=5; CPU throttle x1; wall 267 s

### ORG 2 000 units (s=big)
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load event (navigation, bundle exec incl.) | ms | 5 | 98.7 | 114.9 | 114.9 |  |
| first React commit (Profiler actual) | ms | 5 | 13.1 | 13.3 | 13.3 |  |
| long-task time during load (sum) | ms | 5 | 0.0 | 0.0 | 0.0 |  |
| expand all -> 2 000 treeitems painted | ms | 5 | 200.3 | 737.8 | 737.8 | rows=2000, DOM nodes after=26073 |
|   expand all: main-thread time of the click (sync React commit) | ms | 5 | 83.1 | 90.1 | 90.1 |  |
| select 1 row of 2 000 -> painted | ms | 50 | 27.6 | 37.0 | 218.0 |  |
|   select: main-thread time of the click (sync React commit) | ms | 50 | 2.7 | 4.3 | 4.5 |  |
|   select: Profiler actualDuration of the update | ms | 50 | 1.0 | 2.2 | 2.4 | profiling bundle |
|   select: Profiler baseDuration (cost if everything re-rendered) | ms | 50 | 31.9 | 37.7 | 38.2 | profiling bundle |
| toggle one node (chevron) in the expanded tree -> painted | ms | 20 | 31.2 | 61.2 | 64.7 |  |
| REAL pointer click on a row: Event Timing duration (input->paint) | ms | 15 | 24.0 | 40.0 | 40.0 |  |
| REAL ArrowDown keydown: Event Timing duration | ms | 85 | 16.0 | 32.0 | 32.0 |  |
| collapse all -> painted | ms | 5 | 28.4 | 32.9 | 32.9 |  |

### EMPLOYEES 10 000 (generated)
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| directory (server-paged fake): load event | ms | 5 | 67.2 | 68.2 | 68.2 |  |
| directory: first React commit (Profiler actual) | ms | 5 | 7.1 | 7.3 | 7.3 |  |
| directory: keystroke -> frame (12 keys of a search) | ms | 60 | 33.3 | 34.9 | 35.2 |  |
| directory: search requests for 12 keystrokes | count | 5 | 1.0 | 1.0 | 1.0 |  |
| directory: next page -> painted | ms | 15 | 11.2 | 14.5 | 14.5 |  |
| directory: status filter -> painted | ms | 5 | 13.9 | 16.4 | 16.4 |  |
| directory: REAL click on a row, Event Timing duration | ms | 5 | 32.0 | 32.0 | 32.0 |  |
| 10 000 employees + 2 000-unit org (unit <select> has 2 000 options): first React commit (Profiler actual) | ms | 5 | 17.7 | 19.2 | 19.2 | options in the unit filter: 2001 |
|   same: keystroke -> frame (12 keys) | ms | 60 | 33.3 | 34.9 | 35.1 |  |
|   same: Profiler actualDuration of every commit while typing 12 keys + the debounced reload | ms | 100 | 0.9 | 1.5 | 1.7 | profiling bundle |
| members fallback (client-side filter of 10 000): keystroke -> frame | ms | 55 | 33.3 | 34.5 | 34.9 |  |
| members fallback: next page -> painted | ms | 15 | 9.9 | 36.4 | 36.4 |  |
| members fallback: status filter -> painted | ms | 5 | 9.2 | 10.5 | 10.5 |  |
| members fallback: member-list fetches (expect 1) | count | 5 | 1.0 | 1.0 | 1.0 |  |

### BUILDER home page = 4 sections, +0 pages
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load -> every section handle present (wall clock, goto..canvas laid out) | ms | 5 | 286.0 | 333.0 | 333.0 |  |
| main-document DOM nodes (outside the preview iframe) | count | 5 | 206.0 | 206.0 | 206.0 |  |
| preview document (iframe srcdoc) size | KB | 5 | 6.8 | 6.8 | 6.8 |  |
| long-task time during load (sum) | ms | 5 | 0.0 | 0.0 | 0.0 |  |
| rail switch (8 panels, 2 passes) -> painted | ms | 80 | 31.9 | 33.6 | 37.9 |  |
| inspector tab switch -> painted | ms | 30 | 30.9 | 33.6 | 33.8 |  |
| canvas selection: inspector shown (message -> 2 frames) | ms | 5 | 24.1 | 31.5 | 31.5 |  |
| canvas selection: preview iframe fully reloaded (selection re-renders the whole srcdoc) | ms | 5 | 57.5 | 64.6 | 64.6 |  |
|   selection: Profiler actualDuration | ms | 5 | 1.7 | 1.9 | 1.9 | profiling bundle |
|   selection: Profiler baseDuration | ms | 5 | 1.5 | 1.5 | 1.5 | profiling bundle |
| inspector: Profiler actual per typed character (local draft) | ms | 25 | 0.1 | 0.1 | 0.1 | profiling bundle |
|   ops sent while typing 5 characters (0 = draft, nothing sent) | count | 5 | 0.0 | 0.0 | 0.0 |  |
| save one field ('Lưu thay đổi') -> preview iframe reloaded | ms | 5 | 67.6 | 101.7 | 101.7 |  |
|   save: Profiler actualDuration of the commit | ms | 5 | 0.8 | 0.9 | 0.9 | profiling bundle |
| rail switch: main-thread time of the click (sync React commit) | ms | 80 | 1.0 | 3.3 | 5.2 |  |
|   rail "Trang" sync main-thread (2 passes) | ms | 10 | 0.7 | 2.0 | 2.0 | first open: 0.6 ms |
|   rail "Thành phần" sync main-thread (2 passes) | ms | 10 | 1.4 | 2.5 | 2.5 | first open: 2.4 ms |
|   rail "Dữ liệu" sync main-thread (2 passes) | ms | 10 | 1.1 | 5.2 | 5.2 | first open: 3.4 ms |
|   rail "Biểu mẫu" sync main-thread (2 passes) | ms | 10 | 0.8 | 1.3 | 1.3 | first open: 1.1 ms |
|   rail "Hành động" sync main-thread (2 passes) | ms | 10 | 0.8 | 1.2 | 1.2 | first open: 1.1 ms |
|   rail "Workflow" sync main-thread (2 passes) | ms | 10 | 0.8 | 1.2 | 1.2 | first open: 1.1 ms |
|   rail "Giao diện" sync main-thread (2 passes) | ms | 10 | 0.9 | 1.1 | 1.1 | first open: 1.0 ms |
|   rail "AI" sync main-thread (2 passes) | ms | 10 | 0.8 | 0.9 | 0.9 | first open: 0.8 ms |
| REAL pointer click on a drag handle: Event Timing duration | ms | 5 | 0.0 | 16.0 | 16.0 |  |

### BUILDER home page = 50 sections, +19 pages
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load -> every section handle present (wall clock, goto..canvas laid out) | ms | 5 | 279.0 | 281.0 | 281.0 |  |
| main-document DOM nodes (outside the preview iframe) | count | 5 | 1320.0 | 1320.0 | 1320.0 |  |
| preview document (iframe srcdoc) size | KB | 5 | 17.5 | 17.5 | 17.5 |  |
| long-task time during load (sum) | ms | 5 | 0.0 | 0.0 | 0.0 |  |
| rail switch (8 panels, 2 passes) -> painted | ms | 80 | 31.8 | 33.4 | 47.2 |  |
| inspector tab switch -> painted | ms | 30 | 31.9 | 33.3 | 33.7 |  |
| canvas selection: inspector shown (message -> 2 frames) | ms | 25 | 11.2 | 28.6 | 36.2 |  |
| canvas selection: preview iframe fully reloaded (selection re-renders the whole srcdoc) | ms | 25 | 45.3 | 62.6 | 65.3 |  |
|   selection: Profiler actualDuration | ms | 25 | 2.7 | 4.1 | 4.3 | profiling bundle |
|   selection: Profiler baseDuration | ms | 25 | 2.4 | 3.6 | 3.7 | profiling bundle |
| inspector: Profiler actual per typed character (local draft) | ms | 25 | 0.0 | 0.1 | 0.1 | profiling bundle |
|   ops sent while typing 5 characters (0 = draft, nothing sent) | count | 5 | 0.0 | 0.0 | 0.0 |  |
| save one field ('Lưu thay đổi') -> preview iframe reloaded | ms | 5 | 83.2 | 85.5 | 85.5 |  |
|   save: Profiler actualDuration of the commit | ms | 5 | 2.4 | 2.5 | 2.5 | profiling bundle |
| rail switch: main-thread time of the click (sync React commit) | ms | 80 | 1.3 | 5.9 | 6.6 |  |
|   rail "Trang" sync main-thread (2 passes) | ms | 10 | 0.8 | 6.6 | 6.6 | first open: 0.7 ms |
|   rail "Thành phần" sync main-thread (2 passes) | ms | 10 | 2.6 | 4.7 | 4.7 | first open: 4.0 ms |
|   rail "Dữ liệu" sync main-thread (2 passes) | ms | 10 | 1.4 | 4.4 | 4.4 | first open: 3.6 ms |
|   rail "Biểu mẫu" sync main-thread (2 passes) | ms | 10 | 1.4 | 2.1 | 2.1 | first open: 1.9 ms |
|   rail "Hành động" sync main-thread (2 passes) | ms | 10 | 1.1 | 1.6 | 1.6 | first open: 1.6 ms |
|   rail "Workflow" sync main-thread (2 passes) | ms | 10 | 1.0 | 1.4 | 1.4 | first open: 1.3 ms |
|   rail "Giao diện" sync main-thread (2 passes) | ms | 10 | 0.9 | 1.5 | 1.5 | first open: 1.4 ms |
|   rail "AI" sync main-thread (2 passes) | ms | 10 | 0.9 | 1.2 | 1.2 | first open: 1.1 ms |
| REAL pointer click on a drag handle: Event Timing duration | ms | 5 | 16.0 | 16.0 | 16.0 |  |

### BUILDER home page = 104 sections, +20 pages
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load -> every section handle present (wall clock, goto..canvas laid out) | ms | 5 | 309.0 | 463.0 | 463.0 |  |
| main-document DOM nodes (outside the preview iframe) | count | 5 | 2513.0 | 2513.0 | 2513.0 |  |
| preview document (iframe srcdoc) size | KB | 5 | 30.0 | 30.0 | 30.0 |  |
| long-task time during load (sum) | ms | 5 | 0.0 | 0.0 | 0.0 |  |
| rail switch (8 panels, 2 passes) -> painted | ms | 80 | 31.8 | 33.7 | 39.1 |  |
| inspector tab switch -> painted | ms | 30 | 31.7 | 33.5 | 33.8 |  |
| canvas selection: inspector shown (message -> 2 frames) | ms | 25 | 12.6 | 29.6 | 30.2 |  |
| canvas selection: preview iframe fully reloaded (selection re-renders the whole srcdoc) | ms | 25 | 46.0 | 62.3 | 62.9 |  |
|   selection: Profiler actualDuration | ms | 25 | 4.6 | 6.7 | 7.3 | profiling bundle |
|   selection: Profiler baseDuration | ms | 25 | 4.4 | 6.6 | 7.0 | profiling bundle |
| inspector: Profiler actual per typed character (local draft) | ms | 25 | 0.0 | 0.1 | 0.1 | profiling bundle |
|   ops sent while typing 5 characters (0 = draft, nothing sent) | count | 5 | 0.0 | 0.0 | 0.0 |  |
| save one field ('Lưu thay đổi') -> preview iframe reloaded | ms | 5 | 85.4 | 92.0 | 92.0 |  |
|   save: Profiler actualDuration of the commit | ms | 5 | 3.9 | 5.0 | 5.0 | profiling bundle |
| rail switch: main-thread time of the click (sync React commit) | ms | 80 | 1.5 | 9.1 | 10.8 |  |
|   rail "Trang" sync main-thread (2 passes) | ms | 10 | 1.0 | 10.8 | 10.8 | first open: 0.9 ms |
|   rail "Thành phần" sync main-thread (2 passes) | ms | 10 | 3.7 | 5.8 | 5.8 | first open: 5.3 ms |
|   rail "Dữ liệu" sync main-thread (2 passes) | ms | 10 | 1.6 | 4.9 | 4.9 | first open: 4.6 ms |
|   rail "Biểu mẫu" sync main-thread (2 passes) | ms | 10 | 2.0 | 3.3 | 3.3 | first open: 2.6 ms |
|   rail "Hành động" sync main-thread (2 passes) | ms | 10 | 1.4 | 2.1 | 2.1 | first open: 1.9 ms |
|   rail "Workflow" sync main-thread (2 passes) | ms | 10 | 1.1 | 1.6 | 1.6 | first open: 1.5 ms |
|   rail "Giao diện" sync main-thread (2 passes) | ms | 10 | 1.4 | 1.5 | 1.5 | first open: 1.5 ms |
|   rail "AI" sync main-thread (2 passes) | ms | 10 | 1.2 | 1.6 | 1.6 | first open: 1.5 ms |
| REAL pointer click on a drag handle: Event Timing duration | ms | 5 | 16.0 | 16.0 | 16.0 |  |

### BUILDER home page = 404 sections, +50 pages
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load -> every section handle present (wall clock, goto..canvas laid out) | ms | 5 | 407.0 | 443.0 | 443.0 |  |
| main-document DOM nodes (outside the preview iframe) | count | 5 | 9263.0 | 9263.0 | 9263.0 |  |
| preview document (iframe srcdoc) size | KB | 5 | 100.1 | 100.1 | 100.1 |  |
| long-task time during load (sum) | ms | 5 | 52.0 | 57.0 | 57.0 |  |
| rail switch (8 panels, 2 passes) -> painted | ms | 80 | 31.9 | 70.8 | 78.3 |  |
| inspector tab switch -> painted | ms | 30 | 31.6 | 33.9 | 34.0 |  |
| canvas selection: inspector shown (message -> 2 frames) | ms | 25 | 33.5 | 42.8 | 46.7 |  |
| canvas selection: preview iframe fully reloaded (selection re-renders the whole srcdoc) | ms | 25 | 104.2 | 115.0 | 122.1 |  |
|   selection: Profiler actualDuration | ms | 25 | 21.4 | 23.8 | 24.5 | profiling bundle |
|   selection: Profiler baseDuration | ms | 25 | 20.0 | 22.8 | 23.1 | profiling bundle |
| inspector: Profiler actual per typed character (local draft) | ms | 25 | 0.0 | 0.1 | 0.1 | profiling bundle |
|   ops sent while typing 5 characters (0 = draft, nothing sent) | count | 5 | 0.0 | 0.0 | 0.0 |  |
| save one field ('Lưu thay đổi') -> preview iframe reloaded | ms | 5 | 110.0 | 129.7 | 129.7 |  |
|   save: Profiler actualDuration of the commit | ms | 5 | 18.8 | 19.4 | 19.4 | profiling bundle |
| rail switch: main-thread time of the click (sync React commit) | ms | 80 | 3.6 | 36.5 | 40.2 |  |
|   rail "Trang" sync main-thread (2 passes) | ms | 10 | 1.3 | 40.2 | 40.2 | first open: 1.1 ms |
|   rail "Thành phần" sync main-thread (2 passes) | ms | 10 | 13.0 | 14.7 | 14.7 | first open: 13.4 ms |
|   rail "Dữ liệu" sync main-thread (2 passes) | ms | 10 | 3.6 | 6.5 | 6.5 | first open: 5.9 ms |
|   rail "Biểu mẫu" sync main-thread (2 passes) | ms | 10 | 5.2 | 6.2 | 6.2 | first open: 5.7 ms |
|   rail "Hành động" sync main-thread (2 passes) | ms | 10 | 4.0 | 5.3 | 5.3 | first open: 4.9 ms |
|   rail "Workflow" sync main-thread (2 passes) | ms | 10 | 3.0 | 3.8 | 3.8 | first open: 3.2 ms |
|   rail "Giao diện" sync main-thread (2 passes) | ms | 10 | 3.2 | 3.4 | 3.4 | first open: 3.4 ms |
|   rail "AI" sync main-thread (2 passes) | ms | 10 | 3.1 | 3.5 | 3.5 | first open: 3.2 ms |
| REAL pointer click on a drag handle: Event Timing duration | ms | 5 | 0.0 | 0.0 | 0.0 |  |

### BUILDER home page = 1004 sections, +100 pages
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load -> every section handle present (wall clock, goto..canvas laid out) | ms | 3 | 654.0 | 693.0 | 693.0 |  |
| main-document DOM nodes (outside the preview iframe) | count | 3 | 22713.0 | 22713.0 | 22713.0 |  |
| preview document (iframe srcdoc) size | KB | 3 | 240.3 | 240.3 | 240.3 |  |
| long-task time during load (sum) | ms | 3 | 228.0 | 237.0 | 237.0 |  |
| rail switch (8 panels, 2 passes) -> painted | ms | 48 | 31.7 | 208.5 | 221.9 |  |
| inspector tab switch -> painted | ms | 18 | 30.9 | 33.7 | 33.7 |  |
| canvas selection: inspector shown (message -> 2 frames) | ms | 15 | 95.3 | 108.8 | 108.8 |  |
| canvas selection: preview iframe fully reloaded (selection re-renders the whole srcdoc) | ms | 15 | 208.4 | 262.2 | 262.2 |  |
|   selection: Profiler actualDuration | ms | 15 | 67.1 | 77.3 | 77.3 | profiling bundle |
|   selection: Profiler baseDuration | ms | 15 | 65.6 | 74.4 | 74.4 | profiling bundle |
| inspector: Profiler actual per typed character (local draft) | ms | 15 | 0.0 | 0.1 | 0.1 | profiling bundle |
|   ops sent while typing 5 characters (0 = draft, nothing sent) | count | 3 | 0.0 | 0.0 | 0.0 |  |
| save one field ('Lưu thay đổi') -> preview iframe reloaded | ms | 3 | 284.4 | 297.9 | 297.9 |  |
|   save: Profiler actualDuration of the commit | ms | 3 | 53.8 | 59.2 | 59.2 | profiling bundle |
| rail switch: main-thread time of the click (sync React commit) | ms | 48 | 9.7 | 109.0 | 120.1 |  |
|   rail "Trang" sync main-thread (2 passes) | ms | 6 | 1.7 | 120.1 | 120.1 | first open: 1.5 ms |
|   rail "Thành phần" sync main-thread (2 passes) | ms | 6 | 27.9 | 32.7 | 32.7 | first open: 30.6 ms |
|   rail "Dữ liệu" sync main-thread (2 passes) | ms | 6 | 9.8 | 12.1 | 12.1 | first open: 11.2 ms |
|   rail "Biểu mẫu" sync main-thread (2 passes) | ms | 6 | 12.3 | 13.4 | 13.4 | first open: 13.4 ms |
|   rail "Hành động" sync main-thread (2 passes) | ms | 6 | 10.0 | 11.6 | 11.6 | first open: 11.5 ms |
|   rail "Workflow" sync main-thread (2 passes) | ms | 6 | 7.6 | 9.5 | 9.5 | first open: 8.0 ms |
|   rail "Giao diện" sync main-thread (2 passes) | ms | 6 | 8.1 | 9.4 | 9.4 | first open: 9.0 ms |
|   rail "AI" sync main-thread (2 passes) | ms | 6 | 8.0 | 9.2 | 9.2 | first open: 8.3 ms |
| REAL pointer click on a drag handle: Event Timing duration | ms | 3 | 0.0 | 0.0 | 0.0 |  |

### ATTRIBUTION (builder, 50 sections): Profiler actualDuration of ONE canvas selection, by the rail that is open
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| rail 'Thành phần' open (the page tree is NOT mounted) | ms | 24 | 0.9 | 1.8 | 1.8 | profiling bundle |
| rail 'Trang' open (page tree with every section row mounted) | ms | 24 | 2.5 | 4.2 | 4.5 | profiling bundle |
|   difference attributable to the page-tree rows (median) | ms | 1 | 1.6 | 1.6 | 1.6 | profiling bundle |

### ATTRIBUTION (builder, 404 sections): Profiler actualDuration of ONE canvas selection, by the rail that is open
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| rail 'Thành phần' open (the page tree is NOT mounted) | ms | 24 | 2.8 | 4.1 | 5.7 | profiling bundle |
| rail 'Trang' open (page tree with every section row mounted) | ms | 24 | 19.7 | 24.1 | 25.1 | profiling bundle |
|   difference attributable to the page-tree rows (median) | ms | 1 | 16.9 | 16.9 | 16.9 | profiling bundle |

### ATTRIBUTION (builder, 1004 sections): Profiler actualDuration of ONE canvas selection, by the rail that is open
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| rail 'Thành phần' open (the page tree is NOT mounted) | ms | 24 | 9.5 | 13.3 | 15.9 | profiling bundle |
| rail 'Trang' open (page tree with every section row mounted) | ms | 24 | 62.9 | 78.2 | 78.3 | profiling bundle |
|   difference attributable to the page-tree rows (median) | ms | 1 | 53.4 | 53.4 | 53.4 | profiling bundle |

### CANVAS SCROLL (builder, 4 sections): 25 wheel steps over the preview in ~1.3 s
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| layout messages the preview posted to the host | count | 3 | 0.0 | 0.0 | 0.0 |  |
| React commits of the host during the scroll | count | 3 | 0.0 | 0.0 | 0.0 |  |
| Profiler actualDuration summed over those commits | ms | 3 | 0.0 | 0.0 | 0.0 | profiling bundle |
| worst frame gap (rAF to rAF) | ms | 3 | 18.6 | 19.1 | 19.1 |  |
| frames slower than 34 ms (a dropped frame at 60 Hz) | count | 3 | 0.0 | 0.0 | 0.0 | of ~95 frames |

### CANVAS SCROLL (builder, 50 sections): 25 wheel steps over the preview in ~1.3 s
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| layout messages the preview posted to the host | count | 3 | 25.0 | 25.0 | 25.0 |  |
| React commits of the host during the scroll | count | 3 | 25.0 | 25.0 | 25.0 |  |
| Profiler actualDuration summed over those commits | ms | 3 | 60.7 | 65.1 | 65.1 | profiling bundle |
| worst frame gap (rAF to rAF) | ms | 3 | 18.6 | 18.6 | 18.6 |  |
| frames slower than 34 ms (a dropped frame at 60 Hz) | count | 3 | 0.0 | 0.0 | 0.0 | of ~93 frames |

### CANVAS SCROLL (builder, 104 sections): 25 wheel steps over the preview in ~1.3 s
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| layout messages the preview posted to the host | count | 3 | 25.0 | 25.0 | 25.0 |  |
| React commits of the host during the scroll | count | 3 | 25.0 | 25.0 | 25.0 |  |
| Profiler actualDuration summed over those commits | ms | 3 | 101.8 | 104.1 | 104.1 | profiling bundle |
| worst frame gap (rAF to rAF) | ms | 3 | 18.7 | 21.8 | 21.8 |  |
| frames slower than 34 ms (a dropped frame at 60 Hz) | count | 3 | 0.0 | 0.0 | 0.0 | of ~98 frames |

### CANVAS SCROLL (builder, 404 sections): 25 wheel steps over the preview in ~1.3 s
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| layout messages the preview posted to the host | count | 3 | 25.0 | 25.0 | 25.0 |  |
| React commits of the host during the scroll | count | 3 | 25.0 | 25.0 | 25.0 |  |
| Profiler actualDuration summed over those commits | ms | 3 | 435.8 | 446.9 | 446.9 | profiling bundle |
| worst frame gap (rAF to rAF) | ms | 3 | 37.5 | 38.5 | 38.5 |  |
| frames slower than 34 ms (a dropped frame at 60 Hz) | count | 3 | 1.0 | 1.0 | 1.0 | of ~94 frames |

### CANVAS SCROLL (builder, 1004 sections): 25 wheel steps over the preview in ~1.3 s
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| layout messages the preview posted to the host | count | 3 | 25.0 | 25.0 | 25.0 |  |
| React commits of the host during the scroll | count | 3 | 25.0 | 25.0 | 25.0 |  |
| Profiler actualDuration summed over those commits | ms | 3 | 1439.6 | 1457.6 | 1457.6 | profiling bundle |
| worst frame gap (rAF to rAF) | ms | 3 | 102.3 | 102.6 | 102.6 |  |
| frames slower than 34 ms (a dropped frame at 60 Hz) | count | 3 | 25.0 | 25.0 | 25.0 | of ~96 frames |

### MEMORY over repeated actions (counters after forced GC; round 5 vs last round)
| scenario | rounds | DOM nodes 5 -> last | listeners 5 -> last | heap MB 5 -> last | documents | node trend | page errors |
|---|---:|---|---|---|---|---|---:|
| ORG 2 000: expand all + 3 selections + collapse all | 30 | 150 -> 150 | 168 -> 168 | 4.7 -> 5.0 | 1 -> 1 | 10:150 20:150 30:150 | 0 |
| EMP 10 000 directory: type search + next page + clear | 30 | 552 -> 552 | 182 -> 182 | 5.3 -> 5.5 | 2 -> 2 | 10:552 20:552 30:552 | 0 |
| BUILDER 100 sections: rail x8 + select + inspector tabs + Test panel | 25 | 439 -> 439 | 204 -> 204 | 6.3 -> 7.1 | 1 -> 1 | 10:439 20:439 | 0 |

## Appendix C. Harness performance, CPU x4 (raw output of `--runs 3 --rounds 15 --cpu 4`; the member-fallback and 10 000 + 2 000-unit rows were added to the script after this run and are in appendix B)


HARNESS, NOT REAL BACKEND. Generated fixtures + in-page fakes; headless Chrome 155.0.8059.40 on darwin arm64, Node v22.23.1; bundle .test-build/browser-prof; runs=3; CPU throttle x4; wall 150 s

### ORG 2 000 units (s=big)
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load event (navigation, bundle exec incl.) | ms | 3 | 145.7 | 405.5 | 405.5 |  |
| first React commit (Profiler actual) | ms | 3 | 50.3 | 50.3 | 50.3 |  |
| long-task time during load (sum) | ms | 3 | 298.0 | 332.0 | 332.0 |  |
| expand all -> 2 000 treeitems painted | ms | 3 | 738.2 | 738.4 | 738.4 | rows=2000, DOM nodes after=26073 |
|   expand all: main-thread time of the click (sync React commit) | ms | 3 | 306.3 | 309.7 | 309.7 |  |
| select 1 row of 2 000 -> painted | ms | 30 | 28.9 | 70.9 | 81.8 |  |
|   select: main-thread time of the click (sync React commit) | ms | 30 | 9.5 | 14.4 | 15.1 |  |
|   select: Profiler actualDuration of the update | ms | 30 | 3.5 | 7.1 | 7.3 | profiling bundle |
|   select: Profiler baseDuration (cost if everything re-rendered) | ms | 30 | 108.2 | 110.8 | 111.0 | profiling bundle |
| toggle one node (chevron) in the expanded tree -> painted | ms | 12 | 101.4 | 226.3 | 226.3 |  |
| REAL pointer click on a row: Event Timing duration (input->paint) | ms | 9 | 80.0 | 112.0 | 112.0 |  |
| REAL ArrowDown keydown: Event Timing duration | ms | 60 | 48.0 | 88.0 | 104.0 |  |
| collapse all -> painted | ms | 3 | 108.7 | 123.2 | 123.2 |  |

### EMPLOYEES 10 000 (generated)
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| directory (server-paged fake): load event | ms | 3 | 141.3 | 147.6 | 147.6 |  |
| directory: first React commit (Profiler actual) | ms | 3 | 26.9 | 28.7 | 28.7 |  |
| directory: keystroke -> frame (12 keys of a search) | ms | 36 | 32.8 | 35.2 | 35.5 |  |
| directory: search requests for 12 keystrokes | count | 3 | 1.0 | 1.0 | 1.0 |  |
| directory: next page -> painted | ms | 9 | 32.0 | 35.3 | 35.3 |  |
| directory: status filter -> painted | ms | 3 | 33.4 | 36.5 | 36.5 |  |
| directory: REAL click on a row, Event Timing duration | ms | 3 | 48.0 | 48.0 | 48.0 |  |
| members fallback (client-side filter of 10 000): keystroke -> frame | ms | 33 | 33.2 | 35.3 | 35.6 |  |
| members fallback: next page -> painted | ms | 9 | 16.4 | 22.3 | 22.3 |  |
| members fallback: status filter -> painted | ms | 3 | 9.3 | 9.3 | 9.3 |  |
| members fallback: member-list fetches (expect 1) | count | 3 | 1.0 | 1.0 | 1.0 |  |

### BUILDER home page = 4 sections, +0 pages
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load -> every section handle present (wall clock, goto..canvas laid out) | ms | 3 | 560.0 | 597.0 | 597.0 |  |
| main-document DOM nodes (outside the preview iframe) | count | 3 | 206.0 | 206.0 | 206.0 |  |
| preview document (iframe srcdoc) size | KB | 3 | 6.8 | 6.8 | 6.8 |  |
| long-task time during load (sum) | ms | 3 | 174.0 | 176.0 | 176.0 |  |
| rail switch (8 panels, 2 passes) -> painted | ms | 48 | 31.7 | 33.8 | 48.1 |  |
| inspector tab switch -> painted | ms | 18 | 31.2 | 33.0 | 33.0 |  |
| canvas selection: inspector shown (message -> 2 frames) | ms | 3 | 22.3 | 29.9 | 29.9 |  |
| canvas selection: preview iframe fully reloaded (selection re-renders the whole srcdoc) | ms | 3 | 55.1 | 56.7 | 56.7 |  |
|   selection: Profiler actualDuration | ms | 3 | 5.5 | 6.0 | 6.0 | profiling bundle |
|   selection: Profiler baseDuration | ms | 3 | 4.7 | 5.0 | 5.0 | profiling bundle |
| inspector: Profiler actual per typed character (local draft) | ms | 15 | 0.1 | 0.9 | 0.9 | profiling bundle |
|   ops sent while typing 5 characters (0 = draft, nothing sent) | count | 3 | 0.0 | 0.0 | 0.0 |  |
| save one field ('Lưu thay đổi') -> preview iframe reloaded | ms | 3 | 84.3 | 93.0 | 93.0 |  |
|   save: Profiler actualDuration of the commit | ms | 3 | 3.3 | 3.9 | 3.9 | profiling bundle |
| rail switch: main-thread time of the click (sync React commit) | ms | 48 | 4.1 | 13.4 | 15.8 |  |
|   rail "Trang" sync main-thread (2 passes) | ms | 6 | 3.3 | 7.7 | 7.7 | first open: 3.1 ms |
|   rail "Thành phần" sync main-thread (2 passes) | ms | 6 | 6.6 | 10.3 | 10.3 | first open: 9.2 ms |
|   rail "Dữ liệu" sync main-thread (2 passes) | ms | 6 | 4.7 | 15.8 | 15.8 | first open: 15.6 ms |
|   rail "Biểu mẫu" sync main-thread (2 passes) | ms | 6 | 2.7 | 5.0 | 5.0 | first open: 4.9 ms |
|   rail "Hành động" sync main-thread (2 passes) | ms | 6 | 3.9 | 5.1 | 5.1 | first open: 4.6 ms |
|   rail "Workflow" sync main-thread (2 passes) | ms | 6 | 3.2 | 5.6 | 5.6 | first open: 4.6 ms |
|   rail "Giao diện" sync main-thread (2 passes) | ms | 6 | 2.5 | 6.1 | 6.1 | first open: 4.3 ms |
|   rail "AI" sync main-thread (2 passes) | ms | 6 | 2.6 | 3.6 | 3.6 | first open: 3.4 ms |
| REAL pointer click on a drag handle: Event Timing duration | ms | 3 | 0.0 | 0.0 | 0.0 |  |

### BUILDER home page = 104 sections, +20 pages
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load -> every section handle present (wall clock, goto..canvas laid out) | ms | 3 | 751.0 | 767.0 | 767.0 |  |
| main-document DOM nodes (outside the preview iframe) | count | 3 | 2513.0 | 2513.0 | 2513.0 |  |
| preview document (iframe srcdoc) size | KB | 3 | 30.0 | 30.0 | 30.0 |  |
| long-task time during load (sum) | ms | 3 | 248.0 | 254.0 | 254.0 |  |
| rail switch (8 panels, 2 passes) -> painted | ms | 48 | 31.2 | 82.7 | 91.5 |  |
| inspector tab switch -> painted | ms | 18 | 31.4 | 46.8 | 46.8 |  |
| canvas selection: inspector shown (message -> 2 frames) | ms | 15 | 43.8 | 56.9 | 56.9 |  |
| canvas selection: preview iframe fully reloaded (selection re-renders the whole srcdoc) | ms | 15 | 83.7 | 96.0 | 96.0 |  |
|   selection: Profiler actualDuration | ms | 15 | 17.3 | 21.5 | 21.5 | profiling bundle |
|   selection: Profiler baseDuration | ms | 15 | 16.5 | 21.1 | 21.1 | profiling bundle |
| inspector: Profiler actual per typed character (local draft) | ms | 15 | 0.0 | 0.5 | 0.5 | profiling bundle |
|   ops sent while typing 5 characters (0 = draft, nothing sent) | count | 3 | 0.0 | 0.0 | 0.0 |  |
| save one field ('Lưu thay đổi') -> preview iframe reloaded | ms | 3 | 154.1 | 175.7 | 175.7 |  |
|   save: Profiler actualDuration of the commit | ms | 3 | 16.0 | 16.6 | 16.6 | profiling bundle |
| rail switch: main-thread time of the click (sync React commit) | ms | 48 | 6.5 | 38.5 | 44.1 |  |
|   rail "Trang" sync main-thread (2 passes) | ms | 6 | 3.7 | 44.1 | 44.1 | first open: 3.2 ms |
|   rail "Thành phần" sync main-thread (2 passes) | ms | 6 | 16.4 | 24.1 | 24.1 | first open: 20.0 ms |
|   rail "Dữ liệu" sync main-thread (2 passes) | ms | 6 | 6.1 | 17.9 | 17.9 | first open: 17.6 ms |
|   rail "Biểu mẫu" sync main-thread (2 passes) | ms | 6 | 8.5 | 11.2 | 11.2 | first open: 10.1 ms |
|   rail "Hành động" sync main-thread (2 passes) | ms | 6 | 5.8 | 8.1 | 8.1 | first open: 7.9 ms |
|   rail "Workflow" sync main-thread (2 passes) | ms | 6 | 3.8 | 7.6 | 7.6 | first open: 6.7 ms |
|   rail "Giao diện" sync main-thread (2 passes) | ms | 6 | 4.3 | 8.3 | 8.3 | first open: 5.7 ms |
|   rail "AI" sync main-thread (2 passes) | ms | 6 | 3.7 | 6.3 | 6.3 | first open: 6.0 ms |
| REAL pointer click on a drag handle: Event Timing duration | ms | 3 | 16.0 | 16.0 | 16.0 |  |

### BUILDER home page = 404 sections, +50 pages
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load -> every section handle present (wall clock, goto..canvas laid out) | ms | 3 | 1125.0 | 1131.0 | 1131.0 |  |
| main-document DOM nodes (outside the preview iframe) | count | 3 | 9263.0 | 9263.0 | 9263.0 |  |
| preview document (iframe srcdoc) size | KB | 3 | 100.1 | 100.1 | 100.1 |  |
| long-task time during load (sum) | ms | 3 | 468.0 | 491.0 | 491.0 |  |
| rail switch (8 panels, 2 passes) -> painted | ms | 48 | 31.7 | 296.9 | 334.7 |  |
| inspector tab switch -> painted | ms | 18 | 30.9 | 35.2 | 35.2 |  |
| canvas selection: inspector shown (message -> 2 frames) | ms | 15 | 136.8 | 196.0 | 196.0 |  |
| canvas selection: preview iframe fully reloaded (selection re-renders the whole srcdoc) | ms | 15 | 259.1 | 318.0 | 318.0 |  |
|   selection: Profiler actualDuration | ms | 15 | 79.2 | 115.9 | 115.9 | profiling bundle |
|   selection: Profiler baseDuration | ms | 15 | 75.0 | 111.2 | 111.2 | profiling bundle |
| inspector: Profiler actual per typed character (local draft) | ms | 15 | 0.0 | 0.4 | 0.4 | profiling bundle |
|   ops sent while typing 5 characters (0 = draft, nothing sent) | count | 3 | 0.0 | 0.0 | 0.0 |  |
| save one field ('Lưu thay đổi') -> preview iframe reloaded | ms | 3 | 339.5 | 441.7 | 441.7 |  |
|   save: Profiler actualDuration of the commit | ms | 3 | 79.6 | 80.3 | 80.3 | profiling bundle |
| rail switch: main-thread time of the click (sync React commit) | ms | 48 | 13.9 | 150.5 | 176.7 |  |
|   rail "Trang" sync main-thread (2 passes) | ms | 6 | 4.1 | 176.7 | 176.7 | first open: 3.9 ms |
|   rail "Thành phần" sync main-thread (2 passes) | ms | 6 | 46.1 | 55.8 | 55.8 | first open: 53.1 ms |
|   rail "Dữ liệu" sync main-thread (2 passes) | ms | 6 | 14.5 | 28.7 | 28.7 | first open: 25.6 ms |
|   rail "Biểu mẫu" sync main-thread (2 passes) | ms | 6 | 19.9 | 23.7 | 23.7 | first open: 23.4 ms |
|   rail "Hành động" sync main-thread (2 passes) | ms | 6 | 16.2 | 33.0 | 33.0 | first open: 25.9 ms |
|   rail "Workflow" sync main-thread (2 passes) | ms | 6 | 12.1 | 16.3 | 16.3 | first open: 13.9 ms |
|   rail "Giao diện" sync main-thread (2 passes) | ms | 6 | 11.8 | 13.2 | 13.2 | first open: 12.6 ms |
|   rail "AI" sync main-thread (2 passes) | ms | 6 | 11.4 | 12.0 | 12.0 | first open: 11.9 ms |
| REAL pointer click on a drag handle: Event Timing duration | ms | 3 | 16.0 | 24.0 | 24.0 |  |

### BUILDER home page = 1004 sections, +100 pages
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load -> every section handle present (wall clock, goto..canvas laid out) | ms | 3 | 2112.0 | 2119.0 | 2119.0 |  |
| main-document DOM nodes (outside the preview iframe) | count | 3 | 22713.0 | 22713.0 | 22713.0 |  |
| preview document (iframe srcdoc) size | KB | 3 | 240.3 | 240.3 | 240.3 |  |
| long-task time during load (sum) | ms | 3 | 1121.0 | 1145.0 | 1145.0 |  |
| rail switch (8 panels, 2 passes) -> painted | ms | 48 | 63.0 | 828.9 | 916.4 |  |
| inspector tab switch -> painted | ms | 18 | 32.6 | 45.9 | 45.9 |  |
| canvas selection: inspector shown (message -> 2 frames) | ms | 15 | 394.7 | 461.4 | 461.4 |  |
| canvas selection: preview iframe fully reloaded (selection re-renders the whole srcdoc) | ms | 15 | 775.7 | 899.6 | 899.6 |  |
|   selection: Profiler actualDuration | ms | 15 | 260.5 | 317.5 | 317.5 | profiling bundle |
|   selection: Profiler baseDuration | ms | 15 | 252.5 | 310.5 | 310.5 | profiling bundle |
| inspector: Profiler actual per typed character (local draft) | ms | 15 | 0.1 | 0.8 | 0.8 | profiling bundle |
|   ops sent while typing 5 characters (0 = draft, nothing sent) | count | 3 | 0.0 | 0.0 | 0.0 |  |
| save one field ('Lưu thay đổi') -> preview iframe reloaded | ms | 3 | 910.9 | 915.2 | 915.2 |  |
|   save: Profiler actualDuration of the commit | ms | 3 | 231.5 | 238.2 | 238.2 | profiling bundle |
| rail switch: main-thread time of the click (sync React commit) | ms | 48 | 40.9 | 452.1 | 492.5 |  |
|   rail "Trang" sync main-thread (2 passes) | ms | 6 | 7.4 | 492.5 | 492.5 | first open: 6.9 ms |
|   rail "Thành phần" sync main-thread (2 passes) | ms | 6 | 120.2 | 128.1 | 128.1 | first open: 124.0 ms |
|   rail "Dữ liệu" sync main-thread (2 passes) | ms | 6 | 48.0 | 71.4 | 71.4 | first open: 48.0 ms |
|   rail "Biểu mẫu" sync main-thread (2 passes) | ms | 6 | 53.3 | 62.7 | 62.7 | first open: 57.1 ms |
|   rail "Hành động" sync main-thread (2 passes) | ms | 6 | 45.3 | 51.1 | 51.1 | first open: 48.1 ms |
|   rail "Workflow" sync main-thread (2 passes) | ms | 6 | 34.1 | 38.8 | 38.8 | first open: 35.6 ms |
|   rail "Giao diện" sync main-thread (2 passes) | ms | 6 | 34.5 | 37.1 | 37.1 | first open: 35.5 ms |
|   rail "AI" sync main-thread (2 passes) | ms | 6 | 33.1 | 38.9 | 38.9 | first open: 36.9 ms |
| REAL pointer click on a drag handle: Event Timing duration | ms | 3 | 24.0 | 32.0 | 32.0 |  |

### MEMORY over repeated actions (counters after forced GC; round 5 vs last round)
| scenario | rounds | DOM nodes 5 -> last | listeners 5 -> last | heap MB 5 -> last | documents | node trend | page errors |
|---|---:|---|---|---|---|---|---:|
| ORG 2 000: expand all + 3 selections + collapse all | 15 | 150 -> 150 | 168 -> 168 | 4.7 -> 4.9 | 1 -> 1 | 10:150 | 0 |
| EMP 10 000 directory: type search + next page + clear | 15 | 552 -> 552 | 182 -> 182 | 5.3 -> 5.4 | 2 -> 2 | 10:552 | 0 |
| BUILDER 100 sections: rail x8 + select + inspector tabs + Test panel | 15 | 439 -> 445 | 204 -> 205 | 6.3 -> 6.9 | 1 -> 1 | 10:439 | 0 |

## Appendix D. Spec runs (raw summary)

builder run=1 exit=0 seconds=68 PASS=87 FAIL=0
builder run=2 exit=0 seconds=68 PASS=87 FAIL=0
builder run=3 exit=0 seconds=68 PASS=87 FAIL=0
sanity run=1 exit=0 seconds=33 PASS=8 FAIL=0
sanity run=2 exit=0 seconds=33 PASS=8 FAIL=0
sanity run=3 exit=0 seconds=32 PASS=8 FAIL=0
org run=1 exit=0 seconds=38 PASS=89 FAIL=0
org run=2 exit=0 seconds=38 PASS=89 FAIL=0
org run=3 exit=0 seconds=37 PASS=89 FAIL=0
org-hardening run=1 exit=0 seconds=26 PASS=69 FAIL=0
org-hardening run=2 exit=0 seconds=26 PASS=69 FAIL=0
org-hardening run=3 exit=0 seconds=26 PASS=69 FAIL=0
datasources run=1 exit=0 seconds=23 PASS=54 FAIL=0
datasources run=2 exit=0 seconds=22 PASS=54 FAIL=0
datasources run=3 exit=0 seconds=23 PASS=54 FAIL=0
release run=1 exit=0 seconds=61 PASS=56 FAIL=0
release run=2 exit=0 seconds=63 PASS=56 FAIL=0
release run=3 exit=0 seconds=60 PASS=56 FAIL=0
provisioning run=1 exit=0 seconds=21 PASS=39 FAIL=0
provisioning run=2 exit=0 seconds=22 PASS=39 FAIL=0
provisioning run=3 exit=0 seconds=22 PASS=39 FAIL=0
aiproviders run=1 exit=0 seconds=11 PASS=27 FAIL=0
aiproviders run=2 exit=0 seconds=11 PASS=27 FAIL=0
aiproviders run=3 exit=0 seconds=12 PASS=27 FAIL=0
publicdata run=1 exit=0 seconds=17 PASS=44 FAIL=0
publicdata run=2 exit=0 seconds=16 PASS=44 FAIL=0
publicdata run=3 exit=0 seconds=18 PASS=44 FAIL=0
page-runtime run=1 exit=0 PASS=37 FAIL=0
portals run=1 exit=0 PASS=33 FAIL=0 (against private builds, PORTAL_*_PORT=19101..19103)
page-runtime run=2 exit=0 PASS=37 FAIL=0
portals run=2 exit=0 PASS=33 FAIL=0 (against private builds, PORTAL_*_PORT=19101..19103)
page-runtime run=3 exit=0 PASS=37 FAIL=0
portals run=3 exit=0 PASS=33 FAIL=0 (against private builds, PORTAL_*_PORT=19101..19103)
