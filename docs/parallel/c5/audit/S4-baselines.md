# S4 baselines ("before" table for the code-splitting wave, M-053)

Measured on `agent/c5-web @ 6838ba2` (merged phase 1 + master ledger) plus the S4 wave-1 branch `agent/c5-s4-wave1` (hooks `useAction` / `useLoad`, spec toolkit; no product screen changed).
Date 2026-10-09. Same method, scripts and machine as `S4-performance-tooling.md` (read its section 1 for the method and the labels). **No number below is a backend or production measurement.**

| Class | Meaning here |
|---|---|
| **SYNTHETIC** | Production `next build` served locally by an owned `next start`, measured with Playwright PerformanceObserver in headless Chrome 155.0.8059.40; localhost, no network latency |
| **HARNESS, NOT REAL BACKEND** | The real screens mounted by `tests/browser/*-harness.tsx` over generated fixtures and in-page fakes (`scripts/perf-harness.mjs`, profiling bundle) |
| **STATIC** | esbuild proxy and source counts; nothing executed in a browser |

Machine: Apple M1 Pro, 10 cores, 16 GiB, macOS 27.0.1, Node v22.23.1; load average during the harness run 3.5 / 5.7 / 6.5 (less loaded than phase 1, where it was about 10). Two re-runs of the same harness on this machine agree within noise (the only row that differs by more than 35 % from the phase-1 run is an 8 ms-quantised Event Timing value).

## 1. Bundles (SYNTHETIC build output, `scripts/bundle-report.mjs`)

First Load JS = `rootMainFiles` + the route's client chunks, excluding the 110.0 KB `nomodule` polyfill. Each app is one catch-all route, so there is one number per app. 0 dynamically loaded chunks in every app.

| App | **Base 6838ba2** raw / gzip / brotli | with wave-1 hooks (`useLoad` cache, `useAction`) raw / gzip / brotli | delta of the hooks | CSS raw / gzip |
|---|---|---|---|---|
| Platform | **813.0 / 231.0 / 191.6 KB** | 814.8 / 231.8 / 192.4 KB | +1.8 / +0.8 / +0.8 KB | 63.0 / 13.6 KB |
| Admin | **813.0 / 231.0 / 191.6 KB** | 814.8 / 231.8 / 192.2 KB | +1.8 / +0.8 / +0.6 KB | 63.0 / 13.6 KB |
| Studio | **912.6 / 267.6 / 221.6 KB** | 914.4 / 268.3 / 222.2 KB | +1.8 / +0.7 / +0.6 KB | 81.1 / 17.0 KB |
| Legacy root (http mode) | (see note) | 1 203.4 / 342.7 / 274.7 KB | n/a | 81.1 / 17.0 KB |

Note on the root app: the temporary build of the base for the root app could not be completed (its type check also covers `tests/browser/hooks-harness.tsx`, which needs the new `useLoad` options), so only the with-hooks figure is a clean build; the hooks add about 1.8 KB raw as in the other apps, so the base is about 1 201.6 KB.
Phase 1 (`a73ae3d`-era base `9f858c2`) had Platform 809.4, Studio 912.6, root 1 198.0 KB: the merged wave-0/1 UI work added about 3.6 KB raw to Platform / Admin (S2's admin work), nothing to Studio.

Chunk composition of the Platform / Admin app (STATIC esbuild PROXY, with-hooks, minified; full table in appendix A2): `features/admin` 275.1 KB, `features/studio` 24.0 KB (pulled in by `TenantScreens.tsx`), lucide-react 21.8, api-client 21.4, packages/ui 15.2, auth 14.9. Studio: `features/studio` about 368 KB, `@dnd-kit/*` 48.8 KB.
Reference for the M-053 target (STATIC proxy, phase 1): a login-only entry is 53.7 KB minified versus 399.5 KB (Platform) and 517.1 KB (Studio).

Code-splitting facts at this base (STATIC): `next/dynamic` / `lazy()` / `import()` occurrences in product code = **0**; `useLoad(` call sites = **73**.

Raw output: appendix A.

## 2. Page load and request count of the pages reachable without a backend (SYNTHETIC, median of 5, CPU x1)

Each portal's `/login` (the root path redirects to it). `/api/` requests are the two session calls (`/api/v1/auth/config`, `/api/v1/auth/me`) answered 500 by the dead API proxy. Servers: owned `next start` of the base builds (Platform / Admin / Studio on the base `useLoad`; root with the hooks).

| URL | FCP ms | LCP ms (max) | LCP element | CLS | TBT-like ms | longest task ms | DCL ms | load ms | script bytes on wire KB | requests | of which /api/ |
|---|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Platform `/login` | 76 | 76 (96) | P.authLead | 0.000 | 0 | 0 | 31 | 72 | 223.9 | 11 | 2 |
| Admin `/login` | 64 | 64 (72) | P.authLead | 0.000 | 0 | 0 | 38 | 84 | 223.9 | 11 | 2 |
| Studio `/login` | 72 | 72 (80) | P.authLead | 0.000 | 0 | 0 | 28 | 73 | 260.4 | 11 | 2 |
| Legacy root `/login` | 72 | 72 (72) | P.hint | 0.000 | 0 | 0 | 19 | 74 | 335.6 | 11 | 2 |

11 requests = 1 document, 1 CSS, 6 scripts (5 for the framework + 1 app chunk), `favicon.ico`, 2 API calls. The API calls start about 117 ms after navigation, after hydration (phase 1 waterfall). Lighthouse numbers of phase 1 are not repeated (bundle sizes moved by under 1 %).
**Request counts of authenticated screens cannot be measured here** (no backend). What is known STATICALLY and is what M-097 targets: no cache and no de-duplication in `useLoad`, so the same list is requested again by each screen that mounts it (`api.components()` at `StudioApp.tsx:108` and `:331`, `templates("mine")` at `:179` and `libraryPanels.tsx:12`, `blocks("mine")` at `:353` and `libraryPanels.tsx:42`). The hooks harness (`tests/browser/hooks.spec.mjs`) proves the new opt-in `key` makes two screens with one key send ONE request.

## 3. Harness performance (HARNESS, NOT REAL BACKEND; `node scripts/perf-harness.mjs --runs 5 --rounds 30`, CPU x1)

The profiling bundle was built on this branch, so the harnesses that use `useLoad` (organization / employees) already contain the new hook; its behaviour without a `key` is unchanged and the numbers equal the phase-1 run within noise.
Frame floor: any "painted" value below about 33 ms is two 60 Hz frames; read the main-thread and Profiler rows for sub-frame work. Contract maximum of the builder: 50 sections per page, 20 pages (`PageSchemaValidator.kt:21`, `pages.ts:12`); the larger sizes are stress.

Headline rows (full table in appendix B):

| Measure | Value (median) |
|---|---:|
| Org tree 2 000 units: expand all to paint / main thread / DOM nodes | 185.7 ms (p95 188.6) / 73.0 ms / 26 073 |
| Org tree: select one row, Profiler actual (base duration if all re-rendered) | 1.0 ms (25.8 ms) |
| Employees 10 000: keystroke to frame, requests per 12 keystrokes | 33.3 ms (one frame), 1 |
| Builder 50 sections + 20 pages: select, preview reload / save one field, preview reload | 45.2 ms (p95 62.5) / 70.1 ms (p95 87.5) |
| Builder 1 004 sections (stress): select, preview reload | 219.6 ms (p95 255.4) (save: 283.9 ms) |
| Memory over 25 to 30 rounds (nodes, listeners, documents) | constant in all three scenarios |

### Full table (raw)


HARNESS, NOT REAL BACKEND. Generated fixtures + in-page fakes; headless Chrome 155.0.8059.40 on darwin arm64, Node v22.23.1; bundle .test-build/browser-prof; runs=5; CPU throttle x1; wall 260 s

### ORG 2 000 units (s=big)
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load event (navigation, bundle exec incl.) | ms | 5 | 93.7 | 98.5 | 98.5 |  |
| first React commit (Profiler actual) | ms | 5 | 12.2 | 13.5 | 13.5 |  |
| long-task time during load (sum) | ms | 5 | 0.0 | 0.0 | 0.0 |  |
| expand all -> 2 000 treeitems painted | ms | 5 | 185.7 | 188.6 | 188.6 | rows=2000, DOM nodes after=26073 |
|   expand all: main-thread time of the click (sync React commit) | ms | 5 | 73.0 | 80.6 | 80.6 |  |
| select 1 row of 2 000 -> painted | ms | 50 | 29.2 | 31.6 | 31.9 |  |
|   select: main-thread time of the click (sync React commit) | ms | 50 | 2.6 | 3.8 | 3.9 |  |
|   select: Profiler actualDuration of the update | ms | 50 | 1.0 | 2.1 | 2.4 | profiling bundle |
|   select: Profiler baseDuration (cost if everything re-rendered) | ms | 50 | 25.8 | 30.5 | 31.4 | profiling bundle |
| toggle one node (chevron) in the expanded tree -> painted | ms | 20 | 32.7 | 44.2 | 45.8 |  |
| REAL pointer click on a row: Event Timing duration (input->paint) | ms | 15 | 24.0 | 40.0 | 40.0 |  |
| REAL ArrowDown keydown: Event Timing duration | ms | 87 | 16.0 | 32.0 | 32.0 |  |
| collapse all -> painted | ms | 5 | 26.6 | 30.3 | 30.3 |  |

### EMPLOYEES 10 000 (generated)
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| directory (server-paged fake): load event | ms | 5 | 59.3 | 62.5 | 62.5 |  |
| directory: first React commit (Profiler actual) | ms | 5 | 6.7 | 6.8 | 6.8 |  |
| directory: keystroke -> frame (12 keys of a search) | ms | 60 | 33.3 | 34.6 | 35.4 |  |
| directory: search requests for 12 keystrokes | count | 5 | 1.0 | 1.0 | 1.0 |  |
| directory: next page -> painted | ms | 15 | 12.2 | 15.7 | 15.7 |  |
| directory: status filter -> painted | ms | 5 | 12.3 | 19.4 | 19.4 |  |
| directory: REAL click on a row, Event Timing duration | ms | 5 | 32.0 | 32.0 | 32.0 |  |
| 10 000 employees + 2 000-unit org (unit <select> has 2 000 options): first React commit (Profiler actual) | ms | 5 | 18.0 | 18.4 | 18.4 | options in the unit filter: 2001 |
|   same: keystroke -> frame (12 keys) | ms | 60 | 33.3 | 34.8 | 35.1 |  |
|   same: Profiler actualDuration of every commit while typing 12 keys + the debounced reload | ms | 100 | 1.0 | 1.6 | 3.3 | profiling bundle |
| members fallback (client-side filter of 10 000): keystroke -> frame | ms | 55 | 33.3 | 35.1 | 35.4 |  |
| members fallback: next page -> painted | ms | 15 | 10.1 | 13.9 | 13.9 |  |
| members fallback: status filter -> painted | ms | 5 | 8.8 | 10.0 | 10.0 |  |
| members fallback: member-list fetches (expect 1) | count | 5 | 1.0 | 1.0 | 1.0 |  |

### BUILDER home page = 4 sections, +0 pages
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load -> every section handle present (wall clock, goto..canvas laid out) | ms | 5 | 251.0 | 571.0 | 571.0 |  |
| main-document DOM nodes (outside the preview iframe) | count | 5 | 206.0 | 206.0 | 206.0 |  |
| preview document (iframe srcdoc) size | KB | 5 | 6.8 | 6.8 | 6.8 |  |
| long-task time during load (sum) | ms | 5 | 0.0 | 0.0 | 0.0 |  |
| rail switch (8 panels, 2 passes) -> painted | ms | 80 | 31.9 | 33.8 | 68.3 |  |
| inspector tab switch -> painted | ms | 30 | 31.3 | 33.4 | 33.7 |  |
| canvas selection: inspector shown (message -> 2 frames) | ms | 5 | 23.1 | 24.2 | 24.2 |  |
| canvas selection: preview iframe fully reloaded (selection re-renders the whole srcdoc) | ms | 5 | 55.2 | 58.2 | 58.2 |  |
|   selection: Profiler actualDuration | ms | 5 | 1.5 | 1.6 | 1.6 | profiling bundle |
|   selection: Profiler baseDuration | ms | 5 | 1.2 | 1.5 | 1.5 | profiling bundle |
| inspector: Profiler actual per typed character (local draft) | ms | 25 | 0.1 | 0.1 | 0.2 | profiling bundle |
|   ops sent while typing 5 characters (0 = draft, nothing sent) | count | 5 | 0.0 | 0.0 | 0.0 |  |
| save one field ('Lưu thay đổi') -> preview iframe reloaded | ms | 5 | 68.7 | 74.5 | 74.5 |  |
|   save: Profiler actualDuration of the commit | ms | 5 | 0.9 | 1.1 | 1.1 | profiling bundle |
| rail switch: main-thread time of the click (sync React commit) | ms | 80 | 0.9 | 3.4 | 3.5 |  |
|   rail "Trang" sync main-thread (2 passes) | ms | 10 | 0.8 | 2.2 | 2.2 | first open: 0.7 ms |
|   rail "Thành phần" sync main-thread (2 passes) | ms | 10 | 1.4 | 2.5 | 2.5 | first open: 2.4 ms |
|   rail "Dữ liệu" sync main-thread (2 passes) | ms | 10 | 1.1 | 3.5 | 3.5 | first open: 3.4 ms |
|   rail "Biểu mẫu" sync main-thread (2 passes) | ms | 10 | 0.9 | 1.2 | 1.2 | first open: 1.1 ms |
|   rail "Hành động" sync main-thread (2 passes) | ms | 10 | 0.9 | 1.2 | 1.2 | first open: 1.1 ms |
|   rail "Workflow" sync main-thread (2 passes) | ms | 10 | 0.7 | 1.3 | 1.3 | first open: 1.2 ms |
|   rail "Giao diện" sync main-thread (2 passes) | ms | 10 | 0.9 | 1.1 | 1.1 | first open: 0.9 ms |
|   rail "AI" sync main-thread (2 passes) | ms | 10 | 0.7 | 1.1 | 1.1 | first open: 0.9 ms |
| REAL pointer click on a drag handle: Event Timing duration | ms | 5 | 0.0 | 16.0 | 16.0 |  |

### BUILDER home page = 50 sections, +19 pages
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load -> every section handle present (wall clock, goto..canvas laid out) | ms | 5 | 310.0 | 370.0 | 370.0 |  |
| main-document DOM nodes (outside the preview iframe) | count | 5 | 1320.0 | 1320.0 | 1320.0 |  |
| preview document (iframe srcdoc) size | KB | 5 | 17.5 | 17.5 | 17.5 |  |
| long-task time during load (sum) | ms | 5 | 0.0 | 0.0 | 0.0 |  |
| rail switch (8 panels, 2 passes) -> painted | ms | 80 | 31.8 | 33.6 | 42.0 |  |
| inspector tab switch -> painted | ms | 30 | 31.8 | 33.3 | 34.0 |  |
| canvas selection: inspector shown (message -> 2 frames) | ms | 25 | 12.1 | 29.8 | 30.1 |  |
| canvas selection: preview iframe fully reloaded (selection re-renders the whole srcdoc) | ms | 25 | 45.2 | 62.5 | 62.9 |  |
|   selection: Profiler actualDuration | ms | 25 | 2.5 | 4.2 | 4.4 | profiling bundle |
|   selection: Profiler baseDuration | ms | 25 | 2.3 | 4.0 | 4.0 | profiling bundle |
| inspector: Profiler actual per typed character (local draft) | ms | 25 | 0.0 | 0.1 | 0.1 | profiling bundle |
|   ops sent while typing 5 characters (0 = draft, nothing sent) | count | 5 | 0.0 | 0.0 | 0.0 |  |
| save one field ('Lưu thay đổi') -> preview iframe reloaded | ms | 5 | 70.1 | 87.5 | 87.5 |  |
|   save: Profiler actualDuration of the commit | ms | 5 | 2.8 | 3.1 | 3.1 | profiling bundle |
| rail switch: main-thread time of the click (sync React commit) | ms | 80 | 1.3 | 5.5 | 6.9 |  |
|   rail "Trang" sync main-thread (2 passes) | ms | 10 | 0.9 | 6.9 | 6.9 | first open: 0.8 ms |
|   rail "Thành phần" sync main-thread (2 passes) | ms | 10 | 2.6 | 4.4 | 4.4 | first open: 4.2 ms |
|   rail "Dữ liệu" sync main-thread (2 passes) | ms | 10 | 1.3 | 4.0 | 4.0 | first open: 3.6 ms |
|   rail "Biểu mẫu" sync main-thread (2 passes) | ms | 10 | 1.4 | 2.1 | 2.1 | first open: 2.0 ms |
|   rail "Hành động" sync main-thread (2 passes) | ms | 10 | 1.1 | 1.6 | 1.6 | first open: 1.5 ms |
|   rail "Workflow" sync main-thread (2 passes) | ms | 10 | 0.9 | 1.4 | 1.4 | first open: 1.3 ms |
|   rail "Giao diện" sync main-thread (2 passes) | ms | 10 | 0.9 | 1.5 | 1.5 | first open: 1.3 ms |
|   rail "AI" sync main-thread (2 passes) | ms | 10 | 0.8 | 1.3 | 1.3 | first open: 1.2 ms |
| REAL pointer click on a drag handle: Event Timing duration | ms | 5 | 0.0 | 0.0 | 0.0 |  |

### BUILDER home page = 104 sections, +20 pages
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load -> every section handle present (wall clock, goto..canvas laid out) | ms | 5 | 284.0 | 300.0 | 300.0 |  |
| main-document DOM nodes (outside the preview iframe) | count | 5 | 2513.0 | 2513.0 | 2513.0 |  |
| preview document (iframe srcdoc) size | KB | 5 | 30.0 | 30.0 | 30.0 |  |
| long-task time during load (sum) | ms | 5 | 0.0 | 0.0 | 0.0 |  |
| rail switch (8 panels, 2 passes) -> painted | ms | 80 | 31.9 | 33.5 | 46.4 |  |
| inspector tab switch -> painted | ms | 30 | 31.8 | 33.7 | 34.2 |  |
| canvas selection: inspector shown (message -> 2 frames) | ms | 25 | 15.3 | 30.2 | 30.4 |  |
| canvas selection: preview iframe fully reloaded (selection re-renders the whole srcdoc) | ms | 25 | 46.8 | 62.2 | 62.7 |  |
|   selection: Profiler actualDuration | ms | 25 | 5.3 | 7.1 | 7.2 | profiling bundle |
|   selection: Profiler baseDuration | ms | 25 | 5.0 | 6.8 | 6.8 | profiling bundle |
| inspector: Profiler actual per typed character (local draft) | ms | 25 | 0.0 | 0.1 | 0.2 | profiling bundle |
|   ops sent while typing 5 characters (0 = draft, nothing sent) | count | 5 | 0.0 | 0.0 | 0.0 |  |
| save one field ('Lưu thay đổi') -> preview iframe reloaded | ms | 5 | 86.0 | 91.4 | 91.4 |  |
|   save: Profiler actualDuration of the commit | ms | 5 | 4.4 | 4.6 | 4.6 | profiling bundle |
| rail switch: main-thread time of the click (sync React commit) | ms | 80 | 1.6 | 8.9 | 12.3 |  |
|   rail "Trang" sync main-thread (2 passes) | ms | 10 | 0.9 | 12.3 | 12.3 | first open: 0.8 ms |
|   rail "Thành phần" sync main-thread (2 passes) | ms | 10 | 3.8 | 5.8 | 5.8 | first open: 5.3 ms |
|   rail "Dữ liệu" sync main-thread (2 passes) | ms | 10 | 1.6 | 4.3 | 4.3 | first open: 3.8 ms |
|   rail "Biểu mẫu" sync main-thread (2 passes) | ms | 10 | 1.9 | 2.5 | 2.5 | first open: 2.5 ms |
|   rail "Hành động" sync main-thread (2 passes) | ms | 10 | 1.5 | 2.2 | 2.2 | first open: 2.0 ms |
|   rail "Workflow" sync main-thread (2 passes) | ms | 10 | 1.2 | 1.7 | 1.7 | first open: 1.5 ms |
|   rail "Giao diện" sync main-thread (2 passes) | ms | 10 | 1.4 | 1.6 | 1.6 | first open: 1.5 ms |
|   rail "AI" sync main-thread (2 passes) | ms | 10 | 1.1 | 1.6 | 1.6 | first open: 1.4 ms |
| REAL pointer click on a drag handle: Event Timing duration | ms | 5 | 16.0 | 16.0 | 16.0 |  |

### BUILDER home page = 404 sections, +50 pages
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load -> every section handle present (wall clock, goto..canvas laid out) | ms | 5 | 366.0 | 376.0 | 376.0 |  |
| main-document DOM nodes (outside the preview iframe) | count | 5 | 9263.0 | 9263.0 | 9263.0 |  |
| preview document (iframe srcdoc) size | KB | 5 | 100.1 | 100.1 | 100.1 |  |
| long-task time during load (sum) | ms | 5 | 50.0 | 52.0 | 52.0 |  |
| rail switch (8 panels, 2 passes) -> painted | ms | 80 | 32.0 | 67.9 | 76.7 |  |
| inspector tab switch -> painted | ms | 30 | 31.9 | 33.0 | 33.6 |  |
| canvas selection: inspector shown (message -> 2 frames) | ms | 25 | 32.6 | 36.6 | 36.8 |  |
| canvas selection: preview iframe fully reloaded (selection re-renders the whole srcdoc) | ms | 25 | 86.6 | 112.9 | 115.0 |  |
|   selection: Profiler actualDuration | ms | 25 | 20.3 | 22.4 | 23.5 | profiling bundle |
|   selection: Profiler baseDuration | ms | 25 | 19.4 | 21.2 | 23.2 | profiling bundle |
| inspector: Profiler actual per typed character (local draft) | ms | 25 | 0.0 | 0.1 | 0.1 | profiling bundle |
|   ops sent while typing 5 characters (0 = draft, nothing sent) | count | 5 | 0.0 | 0.0 | 0.0 |  |
| save one field ('Lưu thay đổi') -> preview iframe reloaded | ms | 5 | 117.3 | 128.1 | 128.1 |  |
|   save: Profiler actualDuration of the commit | ms | 5 | 18.9 | 20.2 | 20.2 | profiling bundle |
| rail switch: main-thread time of the click (sync React commit) | ms | 80 | 3.5 | 35.8 | 38.8 |  |
|   rail "Trang" sync main-thread (2 passes) | ms | 10 | 1.1 | 38.8 | 38.8 | first open: 1.0 ms |
|   rail "Thành phần" sync main-thread (2 passes) | ms | 10 | 13.1 | 14.9 | 14.9 | first open: 13.3 ms |
|   rail "Dữ liệu" sync main-thread (2 passes) | ms | 10 | 3.6 | 7.0 | 7.0 | first open: 5.9 ms |
|   rail "Biểu mẫu" sync main-thread (2 passes) | ms | 10 | 5.1 | 6.3 | 6.3 | first open: 5.8 ms |
|   rail "Hành động" sync main-thread (2 passes) | ms | 10 | 3.8 | 4.8 | 4.8 | first open: 4.4 ms |
|   rail "Workflow" sync main-thread (2 passes) | ms | 10 | 3.1 | 3.6 | 3.6 | first open: 3.4 ms |
|   rail "Giao diện" sync main-thread (2 passes) | ms | 10 | 3.1 | 3.5 | 3.5 | first open: 3.2 ms |
|   rail "AI" sync main-thread (2 passes) | ms | 10 | 2.9 | 3.5 | 3.5 | first open: 3.1 ms |
| REAL pointer click on a drag handle: Event Timing duration | ms | 5 | 16.0 | 16.0 | 16.0 |  |

### BUILDER home page = 1004 sections, +100 pages
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| load -> every section handle present (wall clock, goto..canvas laid out) | ms | 3 | 631.0 | 638.0 | 638.0 |  |
| main-document DOM nodes (outside the preview iframe) | count | 3 | 22713.0 | 22713.0 | 22713.0 |  |
| preview document (iframe srcdoc) size | KB | 3 | 240.3 | 240.3 | 240.3 |  |
| long-task time during load (sum) | ms | 3 | 225.0 | 231.0 | 231.0 |  |
| rail switch (8 panels, 2 passes) -> painted | ms | 48 | 32.0 | 211.2 | 246.1 |  |
| inspector tab switch -> painted | ms | 18 | 31.6 | 34.0 | 34.0 |  |
| canvas selection: inspector shown (message -> 2 frames) | ms | 15 | 96.8 | 109.3 | 109.3 |  |
| canvas selection: preview iframe fully reloaded (selection re-renders the whole srcdoc) | ms | 15 | 219.6 | 255.4 | 255.4 |  |
|   selection: Profiler actualDuration | ms | 15 | 68.5 | 78.0 | 78.0 | profiling bundle |
|   selection: Profiler baseDuration | ms | 15 | 66.7 | 76.2 | 76.2 | profiling bundle |
| inspector: Profiler actual per typed character (local draft) | ms | 15 | 0.0 | 0.1 | 0.1 | profiling bundle |
|   ops sent while typing 5 characters (0 = draft, nothing sent) | count | 3 | 0.0 | 0.0 | 0.0 |  |
| save one field ('Lưu thay đổi') -> preview iframe reloaded | ms | 3 | 283.9 | 287.3 | 287.3 |  |
|   save: Profiler actualDuration of the commit | ms | 3 | 54.1 | 57.6 | 57.6 | profiling bundle |
| rail switch: main-thread time of the click (sync React commit) | ms | 48 | 9.7 | 110.9 | 131.4 |  |
|   rail "Trang" sync main-thread (2 passes) | ms | 6 | 2.0 | 131.4 | 131.4 | first open: 1.4 ms |
|   rail "Thành phần" sync main-thread (2 passes) | ms | 6 | 28.5 | 34.9 | 34.9 | first open: 33.2 ms |
|   rail "Dữ liệu" sync main-thread (2 passes) | ms | 6 | 9.2 | 12.9 | 12.9 | first open: 11.7 ms |
|   rail "Biểu mẫu" sync main-thread (2 passes) | ms | 6 | 12.6 | 13.8 | 13.8 | first open: 13.5 ms |
|   rail "Hành động" sync main-thread (2 passes) | ms | 6 | 11.1 | 12.3 | 12.3 | first open: 12.3 ms |
|   rail "Workflow" sync main-thread (2 passes) | ms | 6 | 8.3 | 9.3 | 9.3 | first open: 8.8 ms |
|   rail "Giao diện" sync main-thread (2 passes) | ms | 6 | 8.2 | 9.6 | 9.6 | first open: 9.2 ms |
|   rail "AI" sync main-thread (2 passes) | ms | 6 | 8.1 | 9.8 | 9.8 | first open: 8.6 ms |
| REAL pointer click on a drag handle: Event Timing duration | ms | 3 | 0.0 | 0.0 | 0.0 |  |

### ATTRIBUTION (builder, 50 sections): Profiler actualDuration of ONE canvas selection, by the rail that is open
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| rail 'Thành phần' open (the page tree is NOT mounted) | ms | 24 | 1.0 | 2.3 | 2.8 | profiling bundle |
| rail 'Trang' open (page tree with every section row mounted) | ms | 24 | 2.5 | 3.9 | 6.3 | profiling bundle |
|   difference attributable to the page-tree rows (median) | ms | 1 | 1.5 | 1.5 | 1.5 | profiling bundle |

### ATTRIBUTION (builder, 404 sections): Profiler actualDuration of ONE canvas selection, by the rail that is open
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| rail 'Thành phần' open (the page tree is NOT mounted) | ms | 24 | 3.4 | 6.7 | 6.8 | profiling bundle |
| rail 'Trang' open (page tree with every section row mounted) | ms | 24 | 21.8 | 27.8 | 29.0 | profiling bundle |
|   difference attributable to the page-tree rows (median) | ms | 1 | 18.4 | 18.4 | 18.4 | profiling bundle |

### ATTRIBUTION (builder, 1004 sections): Profiler actualDuration of ONE canvas selection, by the rail that is open
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| rail 'Thành phần' open (the page tree is NOT mounted) | ms | 24 | 10.7 | 12.8 | 12.8 | profiling bundle |
| rail 'Trang' open (page tree with every section row mounted) | ms | 24 | 65.5 | 77.9 | 81.9 | profiling bundle |
|   difference attributable to the page-tree rows (median) | ms | 1 | 54.8 | 54.8 | 54.8 | profiling bundle |

### CANVAS SCROLL (builder, 4 sections): 25 wheel steps over the preview in ~1.3 s
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| layout messages the preview posted to the host | count | 3 | 0.0 | 0.0 | 0.0 |  |
| React commits of the host during the scroll | count | 3 | 0.0 | 0.0 | 0.0 |  |
| Profiler actualDuration summed over those commits | ms | 3 | 0.0 | 0.0 | 0.0 | profiling bundle |
| worst frame gap (rAF to rAF) | ms | 3 | 18.6 | 18.6 | 18.6 |  |
| frames slower than 34 ms (a dropped frame at 60 Hz) | count | 3 | 0.0 | 0.0 | 0.0 | of ~92 frames |

### CANVAS SCROLL (builder, 50 sections): 25 wheel steps over the preview in ~1.3 s
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| layout messages the preview posted to the host | count | 3 | 25.0 | 25.0 | 25.0 |  |
| React commits of the host during the scroll | count | 3 | 25.0 | 25.0 | 25.0 |  |
| Profiler actualDuration summed over those commits | ms | 3 | 59.8 | 60.3 | 60.3 | profiling bundle |
| worst frame gap (rAF to rAF) | ms | 3 | 18.6 | 18.7 | 18.7 |  |
| frames slower than 34 ms (a dropped frame at 60 Hz) | count | 3 | 0.0 | 0.0 | 0.0 | of ~93 frames |

### CANVAS SCROLL (builder, 104 sections): 25 wheel steps over the preview in ~1.3 s
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| layout messages the preview posted to the host | count | 3 | 25.0 | 25.0 | 25.0 |  |
| React commits of the host during the scroll | count | 3 | 25.0 | 25.0 | 25.0 |  |
| Profiler actualDuration summed over those commits | ms | 3 | 105.5 | 107.0 | 107.0 | profiling bundle |
| worst frame gap (rAF to rAF) | ms | 3 | 18.6 | 18.7 | 18.7 |  |
| frames slower than 34 ms (a dropped frame at 60 Hz) | count | 3 | 0.0 | 0.0 | 0.0 | of ~93 frames |

### CANVAS SCROLL (builder, 404 sections): 25 wheel steps over the preview in ~1.3 s
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| layout messages the preview posted to the host | count | 3 | 25.0 | 25.0 | 25.0 |  |
| React commits of the host during the scroll | count | 3 | 25.0 | 25.0 | 25.0 |  |
| Profiler actualDuration summed over those commits | ms | 3 | 445.1 | 446.2 | 446.2 | profiling bundle |
| worst frame gap (rAF to rAF) | ms | 3 | 36.0 | 37.5 | 37.5 |  |
| frames slower than 34 ms (a dropped frame at 60 Hz) | count | 3 | 1.0 | 2.0 | 2.0 | of ~94 frames |

### CANVAS SCROLL (builder, 1004 sections): 25 wheel steps over the preview in ~1.3 s
| measure | unit | n | median | p95 | max | note |
|---|---|---:|---:|---:|---:|---|
| layout messages the preview posted to the host | count | 3 | 25.0 | 25.0 | 25.0 |  |
| React commits of the host during the scroll | count | 3 | 25.0 | 25.0 | 25.0 |  |
| Profiler actualDuration summed over those commits | ms | 3 | 1397.3 | 1399.0 | 1399.0 | profiling bundle |
| worst frame gap (rAF to rAF) | ms | 3 | 102.2 | 108.3 | 108.3 |  |
| frames slower than 34 ms (a dropped frame at 60 Hz) | count | 3 | 25.0 | 25.0 | 25.0 | of ~98 frames |

### MEMORY over repeated actions (counters after forced GC; round 5 vs last round)
| scenario | rounds | DOM nodes 5 -> last | listeners 5 -> last | heap MB 5 -> last | documents | node trend | page errors |
|---|---:|---|---|---|---|---|---:|
| ORG 2 000: expand all + 3 selections + collapse all | 30 | 150 -> 150 | 168 -> 168 | 4.8 -> 5.0 | 1 -> 1 | 10:150 20:150 30:150 | 0 |
| EMP 10 000 directory: type search + next page + clear | 30 | 552 -> 552 | 182 -> 182 | 5.3 -> 5.5 | 2 -> 2 | 10:552 20:552 30:552 | 0 |
| BUILDER 100 sections: rail x8 + select + inspector tabs + Test panel | 25 | 439 -> 439 | 204 -> 204 | 6.3 -> 7.1 | 1 -> 1 | 10:439 20:439 | 0 |

## 4. How to use this for the M-053 wave

1. After the splitting change, rebuild with the same commands (below) and compare section 1: the First Load JS row of each app; the number of dynamically loaded chunks (now 0); `features/studio` inside Platform / Admin (now 24.0 KB min); `@dnd-kit` inside the Studio first load (now 48.8 KB min).
2. Re-run `scripts/perf-vitals.mjs` on `/login`: requests may rise (more chunks) while bytes must fall; LCP / FCP must not regress by more than noise (about 20 ms at CPU x1).
3. Re-run `scripts/perf-harness.mjs`: the screens are unchanged by splitting, so every row of section 3 should stay within noise; a regression in "load -> handles present" would mean a lazy boundary was put on the critical path of the builder.
4. Honest limits: these are lab numbers on localhost; the gain of a smaller first load shows in the Lighthouse mobile simulation (phase 1: LCP 2.03 to 2.59 s simulated, unthrottled 49 to 106 ms), not in the localhost numbers.

Commands (all through the owned-process CLI, free high ports, nothing found or stopped by name or port):

```bash
for a in platform admin studio; do (cd apps/$a && NEXT_DIST_DIR=.next-check-s4 API_PROXY_TARGET=http://127.0.0.1:9 npx next build); done      # then: git checkout -- tsconfig.json apps/*/tsconfig.json
NEXT_DIST_DIR=.next-check-s4 NEXT_PUBLIC_API_MODE=http API_PROXY_TARGET=http://127.0.0.1:9 npx next build
node scripts/bundle-report.mjs apps/platform/.next-check-s4 apps/admin/.next-check-s4 apps/studio/.next-check-s4 .next-check-s4
node scripts/bundle-report.mjs --modules apps/platform/app/entry.tsx apps/studio/app/entry.tsx
NEXT_DIST_DIR=.next-check-s4 API_PROXY_TARGET=http://127.0.0.1:9 node tests/lib/owned-process-cli.mjs start --state .run/owned/s4-platform.json --cwd apps/platform --port 19101 -- npx next start -H 127.0.0.1 -p 19101   # likewise admin 19102, studio 19103, root 19104
node scripts/perf-vitals.mjs --runs 5 --cpu 1 http://127.0.0.1:19101/login http://127.0.0.1:19102/login http://127.0.0.1:19103/login http://127.0.0.1:19104/login
node tests/lib/owned-process-cli.mjs stop --state .run/owned/s4-platform.json                                                               # likewise the other three
HARNESS_NODE_ENV=production HARNESS_PROFILING=1 node tests/browser/build-harness.mjs && node scripts/perf-harness.mjs --runs 5 --rounds 30
```

---

## Appendix A. Bundle report, base 6838ba2 (raw, `scripts/bundle-report.mjs`)

Platform, Admin and Studio are clean builds of the base. **The last block (root app) is not reliable**: that temporary build failed its type check (see the note in section 1) and the manifest is partial; use the root row of section 1.


### apps/platform/.next-check-s4
First Load JS (rootMainFiles + route entry chunks, excl. nomodule polyfill): raw 813.0 KB | gzip 231.0 KB | brotli 191.6 KB
polyfill (nomodule, not loaded by modern browsers): raw 110.0 KB | gzip 38.7 KB
all JS on disk: 7 files, raw 923.0 KB | gzip 269.7 KB | brotli 226.0 KB     all CSS: 1 files raw 63.0 KB | gzip 13.6 KB
dynamically loaded (not in first load) JS: 0 files
| chunk | raw KB | gzip KB | brotli KB | in first load |
|---|---:|---:|---:|---|
| chunks/3iod0qi_dzh_7.js | 370.3 | 100.5 | 80.3 | yes |
| chunks/1rj7ns8rte9vc.js | 223.8 | 69.9 | 59.8 | yes |
| chunks/1wym20oe2_8ih.js | 172.4 | 46.1 | 38.8 | yes |
| chunks/0cz1d0mv5g_q7.js | 110.0 | 38.7 | 34.3 | nomodule |
| chunks/1wtqnum3h83ky.css | 63.0 | 13.6 | 11.9 | css |
| chunks/1u5zan5bs9a7v.js | 23.0 | 7.1 | 6.3 | yes |
| chunks/3fntmmi971322.js | 14.0 | 3.6 | 3.2 | yes |
| chunks/turbopack-11y464gitbq1t.js | 9.5 | 3.7 | 3.3 | yes |

### apps/admin/.next-check-s4
First Load JS (rootMainFiles + route entry chunks, excl. nomodule polyfill): raw 813.0 KB | gzip 231.0 KB | brotli 191.6 KB
polyfill (nomodule, not loaded by modern browsers): raw 110.0 KB | gzip 38.7 KB
all JS on disk: 7 files, raw 923.0 KB | gzip 269.7 KB | brotli 226.0 KB     all CSS: 1 files raw 63.0 KB | gzip 13.6 KB
dynamically loaded (not in first load) JS: 0 files
| chunk | raw KB | gzip KB | brotli KB | in first load |
|---|---:|---:|---:|---|
| chunks/13976ni3w578g.js | 370.3 | 100.5 | 80.3 | yes |
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
First Load JS (rootMainFiles + route entry chunks, excl. nomodule polyfill): raw 1201.6 KB | gzip 341.9 KB | brotli 274.2 KB
polyfill (nomodule, not loaded by modern browsers): raw 110.0 KB | gzip 38.7 KB
all JS on disk: 7 files, raw 1311.5 KB | gzip 380.6 KB | brotli 308.5 KB     all CSS: 1 files raw 81.1 KB | gzip 17.0 KB
dynamically loaded (not in first load) JS: 0 files
| chunk | raw KB | gzip KB | brotli KB | in first load |
|---|---:|---:|---:|---|
| chunks/3r-ltz9ycut05.js | 758.8 | 211.4 | 162.7 | yes |
| chunks/1wepaifw1fhdo.js | 223.8 | 70.0 | 59.8 | yes |
| chunks/1wfxwzirr5qpx.js | 172.4 | 46.1 | 38.8 | yes |
| chunks/0cz1d0mv5g_q7.js | 110.0 | 38.7 | 34.3 | nomodule |
| chunks/23dl4_w7scuq6.css | 81.1 | 17.0 | 14.7 | css |
| chunks/2gvhh090iiesp.js | 23.0 | 7.1 | 6.3 | yes |
| chunks/3fntmmi971322.js | 14.0 | 3.6 | 3.2 | yes |
| chunks/turbopack-2rhgfkzk-4d1y.js | 9.5 | 3.7 | 3.3 | yes |

## Appendix A2. Module composition (STATIC esbuild PROXY, with the wave-1 hooks)


### esbuild PROXY for apps/platform/app/entry.tsx (minified, tree-shaken, next/* external; NOT the Next bundle) total 405.5 KB min, gzip 106.1 KB
| package / dir | KB (minified, in output) | % |
|---|---:|---:|
| features/admin | 275.1 | 67.8 |
| features/studio | 24.0 | 5.9 |
| lucide-react | 21.8 | 5.4 |
| packages/api-client | 21.4 | 5.3 |
| packages/ui | 15.2 | 3.7 |
| packages/auth | 14.9 | 3.7 |
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
| features/admin/AdminApp.tsx | 119.3 |
| features/admin/AiSetup.tsx | 33.2 |
| features/admin/TenantScreens.tsx | 24.3 |
| features/admin/OrganizationScreens.tsx | 24.3 |
| features/admin/EmployeesScreens.tsx | 17.1 |
| packages/api-client/src/api.ts | 16.7 |
| features/admin/ProvisioningScreens.tsx | 14.3 |
| features/studio/builder/DataSourcesPanel.tsx | 13.6 |
| packages/auth/src/AuthPages.tsx | 12.2 |
| features/admin/organizationModel.ts | 10.5 |
| lib/schema-preview.ts | 9.1 |
| features/admin/provisioningModel.ts | 9.0 |
| features/studio/builder/core/dataManagement.ts | 8.3 |
| node_modules/react/cjs/react.production.js | 7.5 |
| features/admin/adminModel.ts | 7.1 |
| packages/ui/src/ui.tsx | 5.4 |
| packages/api-client/src/core.ts | 4.4 |
| lib/preview-document.ts | 3.9 |
| node_modules/react-dom/cjs/react-dom.production.js | 3.3 |
| features/admin/UserDialogs.tsx | 3.1 |
| packages/ui/src/Picker.tsx | 2.9 |
| features/admin/PersonPicker.tsx | 2.9 |
| features/admin/organization.ts | 2.7 |
| packages/permissions/src/index.ts | 2.6 |
| features/admin/provisioning.ts | 2.5 |

### esbuild PROXY for apps/studio/app/entry.tsx (minified, tree-shaken, next/* external; NOT the Next bundle) total 518.9 KB min, gzip 146.2 KB
| package / dir | KB (minified, in output) | % |
|---|---:|---:|
| features/studio | 367.9 | 70.9 |
| @dnd-kit/core | 38.7 | 7.5 |
| packages/api-client | 30.8 | 5.9 |
| packages/auth | 14.9 | 2.9 |
| packages/ui | 10.3 | 2.0 |
| lucide-react | 10.0 | 1.9 |
| lib/schema-preview.ts | 9.1 | 1.8 |
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
