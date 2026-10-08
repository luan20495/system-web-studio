# R2 — i18n readiness (part A) and theme readiness (part B)

Author: C5-R2 · Base: `agent/c5-web @ 9f858c2` · Branch: `agent/c5-r2-sec` · Date: 2026-10-08 · Mode: static analysis only (no server, no browser).
Reproduce: `node scripts/r2-i18n-count.mjs [--json|--dump]`, `node scripts/r2-css-count.mjs`, `node scripts/r2-contrast.mjs` (all read-only, run from the repo root; they only need `typescript`, already a dev dependency).

Scores (0–10, justified in sections A.11 and B.8): **i18n readiness 2/10 · theme readiness 3/10** (tokens 4, dark mode 2, tenant theme 2).

---

# Part A — i18n readiness

## A.1 What exists

| Item | State | Evidence |
|---|---|---|
| i18n framework (react-intl, next-intl, i18next, `t()`, ICU, locale files) | **none** | `package.json` dependencies (`next`, `react`, `@dnd-kit/*`); no import of any i18n library anywhere |
| `packages/i18n` (`@xweb/i18n`) | a 23-line module of Vietnamese constants: `ACTION_LABEL` (≈45 audit-action labels), `ROLE_LABEL` (6), `PORTAL_TEXT` (3). Header: "Vietnamese display strings shared by the three web apps". Transpiled by all three apps (`apps/*/next.config.ts`), so the slot to grow into already exists | `packages/i18n/src/index.ts` |
| Locale detection / switching / `Accept-Language` / cookie | none | no `locale`/`lang` reads in any app |
| Document language | hard-coded `lang="vi"` in 4 layouts and 3 generated documents; no `dir` attribute anywhere | `app/layout.tsx:18`, `apps/{platform,admin,studio}/app/layout.tsx:14-15`, `lib/preview-document.ts:38`, `lib/schema-preview.ts:103` |
| Other label tables outside `@xweb/i18n` | `PERMISSION_LABEL_VI` (`packages/permissions/src/canonical.ts`), `PORTAL_LABEL` (`packages/permissions/src/index.ts:34`, duplicates `PORTAL_TEXT`), `ROLE_LABELS`/`roleLabel` (`features/admin/UserDialogs.tsx:8-9`), a second role map (`features/studio/drawers.tsx:102`), `STATE_TEXT` (`packages/ui/src/ui.tsx:24`), `SSO_ERRORS` (`packages/auth/src/AuthPages.tsx:11`), `explainError` text (`features/studio/builder/core/errors.ts`), `STATUS`/`STAGE`/`CHECK_LABEL`/`BK_NAME`/`SD_LABEL` maps in the screens | grep |
| Pseudo-locale / missing-string tooling | none | — |

## A.2 How the hard-coded strings were counted

`scripts/r2-i18n-count.mjs` parses every `.ts/.tsx` under `app apps components features lib packages` (141 files; tests, docs, build output, `.d.ts` excluded) with the TypeScript compiler API, so comments, imports, type literals, `case`/`===` comparisons and property-access keys are never counted. A literal is counted once, in the first category that matches:

| Category | Rule |
|---|---|
| `JSX_TEXT` | a JSX text node containing a letter |
| `ATTR` | string value of `aria-label`, `aria-description`, `title`, `placeholder`, `alt`, `aria-placeholder`, `aria-roledescription` |
| `PROP` | string value of copy-carrying props on custom components (`label`, `sub`, `subtitle`, `detail`, `hint`, `description`, `heading`, `buttonLabel`, `tooltip`, `emptyText`, `why`, `needs`, `action`, `legend`) |
| `JSX_EXPR` | a string/template literal inside a JSX `{…}` (ternaries, `&&` chains) that contains a Vietnamese letter or ≥2 Latin words |
| `TS_VI` | any other string/template literal containing a Vietnamese-only letter (label maps, toasts, validation messages, error text, CSS `content:` in templates) |
| `TS_EN` | other literals of ≥3 Latin words under a copy-carrying key or call (`message`, `title`, `detail`, `setError`, `new Error`, …) — catches English UI/developer messages |

Precision check: the full list (`--dump`, 4,300 lines) was sampled (about 60 items per category, every 25th–80th row); every sampled item was user-visible copy. Known **undercount**: single-word Latin labels inside TS maps (e.g. `"Email"`, `"Model"`) are only counted when they appear in JSX; text assembled by `+` concatenation across lines; strings in `.css` `content:` outside templates. Known **overcount**: a few Vietnamese developer-only strings in `tests` helper modules under `features/` (none found in the sample).

## A.3 Results

Totals: **4,300 user-visible string occurrences, 3,092 unique** (28 % repeats), **438 are template literals with `${}`** (need ICU-style arguments), 612 are accessibility/tooltip attributes.

| Category | Count |
|---|---|
| JSX_TEXT | 1,271 |
| ATTR (aria-label, title, placeholder, alt …) | 612 |
| PROP (component copy props) | 380 |
| JSX_EXPR | 374 |
| TS_VI (maps, toasts, validators, errors) | 1,655 |
| TS_EN | 8 (English developer errors: `STUDIO_BASE_PATH must be…`, `useSession outside SessionProvider`, `This app has no API`, app metadata) |
| **Total** | **4,300** |

Where they live, by owner area (code is shared across portals, so a portal's reachable total is its own tree plus the shared packages):

| Area | Files | Strings | Unique | Interpolated |
|---|---|---|---|---|
| `features/studio/**` (Studio only) | 48 | 2,108 | 1,694 | 297 |
| `features/admin/**` (Platform **and** Admin; one `AdminApp`, `OWNED` sets pick the sections, `features/admin/base.ts:25-30`) | 21 | 1,726 | 1,196 | 111 |
| `packages/{auth,ui,i18n,permissions,api-client,types}` (all three portals) | 35 | 284 | 264 | 18 |
| `features/library.tsx` (Studio + Admin) | 1 | 31 | 30 | 2 |
| `lib/` (preview renderers shared with published sites ≈17; legacy mock ≈31) | 7 | 48 | 45 | 9 |
| legacy root app (`app/`, `components/`, `features/{auth,session,ui,…}` helpers) — not a portal | 21 | 97 | 81 | 1 |
| other packages (`company-ui`, `app-sdk`, generated-app packages) | 3 | 6 | 6 | 0 |

**Per portal (reachable): Studio ≈ 2,440 · Admin ≈ 2,040 · Platform ≈ 2,040** (Admin and Platform ship the same screens; exclusive-to-one-portal counts were not computed). Largest files: `AdminApp.tsx` 880, `AiSetup.tsx` 225, `StudioApp.tsx` 196, `CodeWorkspace.tsx` 125, `TenantScreens.tsx` 120, `EmployeesScreens.tsx` 116, `OrganizationScreens.tsx` 110, `CodePanels.tsx` 109, `ProjectWorkspace.tsx` 106.

About **970 strings are in non-TSX modules** (`*Model.ts`, `core/*.ts`, `release.ts`, `packages/i18n`): pure, unit-tested code with no JSX, the cheapest part to move into a catalogue.

## A.4 Where localisation should live

* **Catalogues and runtime in `packages/i18n`** (already a workspace package, already in `transpilePackages`, `sideEffects:false`): `locales/vi.ts` (source of truth, typed keys, also the fallback), `locales/en.ts` (`Partial<Messages>`), `format.ts` (Intl wrappers), `provider.tsx` (client context + `useT()`), `server.ts` (negotiation helper used by `proxy.ts`/`layout.tsx`).
* **Keys namespaced by owner area** (`shell.*`, `auth.*`, `admin.<screen>.*`, `studio.builder.*`, `errors.<CODE>`, `enum.<Type>.<VALUE>`) so that squads can migrate screens independently.
* **Enums and server codes live in the catalogue, not in components**: `enum.ActionName.LOGIN_SUCCESS`, `enum.Role.EDITOR`, `errors.IDEMPOTENCY_KEY_REUSED`. This replaces `ACTION_LABEL`, the four role maps, `STATE_TEXT`, `explainError` text. The server's free-text `message` becomes the last-resort fallback only.
* **Generated sites are not UI**: the Vietnamese fragments inside `lib/schema-preview.ts` ("Nhận tư vấn", "Họ tên", "Gửi", "Website preview", `<html lang="vi">`, lines 61,70-80,103) are page content of the published site, whose language is a property of the *site* (a `site.lang` in the Page Schema — C2's contract, not invented here), not of the user's UI locale.
* **Adding a dependency** (`intl-messageformat`, `next-intl`) means a change to `package.json`, which is C0-owned. A zero-dependency start is feasible: `Intl.PluralRules` + a 40-line `{name}` / `{n, plural, one{…} other{…}}` formatter.

## A.5 Locale-switching architecture (three catch-all Next apps)

Constraints read from the code: each app is **one client-side router** behind `app/[[...slug]]/page.tsx` (`PortalApp`, `packages/auth/src/PortalApp.tsx`), the first path segment is the portal (`PORTAL_PREFIX`, `portalOfPath`, `segments`, `safeNext` all depend on it), deployments can sit under `STUDIO_BASE_PATH`, OIDC and activation links are fixed paths, every page is already dynamic (`await connection()` in the layouts) and every app already runs a per-request `proxy.ts` that stamps `x-nonce`.

**Recommended: cookie + `Accept-Language`, no path change.**

1. `apps/*/proxy.ts` (through the shared `createCspProxy`) resolves the locale per request: cookie `xweb-locale` → *(future, handoff C1)* user/tenant default from `/auth/me` → `Accept-Language` negotiation against the supported list → `vi`. It sets a request header `x-locale` exactly like `x-nonce`.
2. `layout.tsx` reads the header and renders `<html lang={locale} dir={dirOf(locale)}>`, then wraps children in `<I18nProvider locale messages>`. The `vi` bundle is inline; other locales are loaded with `import()` (code split).
3. A language switcher in the shell header sets the cookie (`Path=/; SameSite=Lax; Max-Age=1y`) and calls `router.refresh()`; no URL changes, no remounting of the client router, deep links and `next=` stay valid.
4. Because pages are already per-request dynamic and responses are not shared-cacheable, there is no cache-key problem; add `Vary: Cookie, Accept-Language` for safety.
5. Multi-origin production (`platform.`, `admin.`, `app.` hosts): a cookie is per host unless a `Domain=` is configured (a C0 env decision), so the durable place for the preference is the user profile (handoff C1: does `/auth/me` or a profile endpoint carry `locale`/`timeZone`?). Until then each portal remembers its own choice.

Rejected: **path prefix `/{locale}/…`** — it collides with the first-segment contract (`PORTAL_PREFIX`, `portalOfPath`, `safeNext` regex `^/[a-z]+…`), with `basePath`, with the OIDC/activation URLs configured on the backend, with the hundreds of `S()`/`A()` path builders and with the real E2E scripts; it buys SEO, which an internal tool does not need.

## A.6 Fallback strategy

`requested locale` → base language (`en-GB` → `en`) → `vi` (source of truth) → in development the key itself plus a visible marker; in production a missing key **never** renders empty or throws, it renders the `vi` text. Server codes: `errors.<code>` → server `message` → `errors.UNKNOWN`. A script (`scripts/r2-i18n-count.mjs`-style) fails the unit suite when `en` has keys that `vi` lacks, or `vi` keys are unused. A pseudo-locale (`qps` — accented, ~35 % longer) is used in browser tests to catch hard-coded strings and overflow.

## A.7 Formatting: plurals, dates, numbers, currency, time zone

`Intl` use today (everything is hard-coded to Vietnamese conventions):

| API | Count | Where |
|---|---|---|
| `Intl.DateTimeFormat("vi-VN", …)` | 5 | `packages/ui/src/ui.tsx:9` (`fmtDate`), `features/studio/drawers.tsx:15` and `features/studio/ReleaseModal.tsx:18` (two private copies of `fmtDate`), `features/admin/AdminApp.tsx:457`, `components/StudioShell.tsx:291` |
| `toLocale*String` | 6 | `ui.tsx:16` `num` ("vi-VN"), `ui.tsx:18` `usd` (**"en-US"**), `ui.tsx:20` `tok` ("vi-VN"), `ProjectWorkspace.tsx:289` and `BuilderTopBar.tsx:21` (`toLocaleTimeString("vi-VN")`), `adminModel.ts:82` (`toLocaleUpperCase()` with no locale) |
| `Intl.Collator("vi")` / `localeCompare(…, "vi")` | 5 | `organizationModel.ts`, `adminModel.ts` (path sorting in `CodeWorkspace.tsx` is locale-free, correct) |
| `Intl.NumberFormat`, `PluralRules`, `RelativeTimeFormat`, `ListFormat` | **0** | — |
| `.toFixed(n)` printed to users | 10 | `AdminApp.tsx:484,557,753,754,1027,1033,1034,1064,1182`, `drawers.tsx:92` — decimal point is always `.` while `num()` prints `1.234,5` |
| `ago()` | 1 helper, hard-coded Vietnamese words (`phút trước`, `giờ trước`, `ngày trước`) | `ui.tsx:11-16` |

Call-site leverage is good: `fmtDate/ago` are called in 49 places and `num/usd/tok` in 64, all through `@xweb/ui`, so making those functions locale-aware changes ~113 call sites without touching them.

* **Plurals:** Vietnamese does not inflect, so today's `"{n} tệp"`, `"{n} lượt gọi"` strings are correct; English needs plural categories. 438 templates are interpolated; ≈18 embed a count next to a noun (grep `\${…length|count|total…} noun`). Use `Intl.PluralRules` through the message formatter.
* **Currency:** AI costs are USD (`usd()` hard-codes `$` and `en-US` grouping, so a table can show `1.234` token counts next to `$1,234.5`); budgets carry a currency code and are printed as `"{number} {CODE}"` (`AdminApp.tsx:936`). Use `Intl.NumberFormat(locale, { style: "currency", currency })` and one locale for grouping.
* **Time zone:** no `timeZone` option anywhere, so the browser zone is used; day-bucketed reports build local midnight (`AdminApp.tsx:457`: `new Date(\`${iso}T00:00:00\`)`) while the buckets are produced by the server in its own zone *(inference — server zone not verified)*. Provide a `timeZone` argument in the wrappers (user/tenant preference, handoff C1) and make report days zone-explicit.

## A.8 RTL readiness (physical vs logical CSS)

From `node scripts/r2-css-count.mjs` over all CSS of the portals (`packages/ui/src/styles/*.css`, `packages/company-ui`):

| Direction-sensitive declaration | Count | | Logical equivalent | Count |
|---|---|---|---|---|
| `margin-left/right` | 12 | | `margin/padding-inline*` | 6 |
| `padding-left/right` | 9 | | `inset-inline*` | 10 |
| `border-left/right*` | 30 | | `border-inline*` | 0 |
| `left:` / `right:` offsets | 18 | | `text-align: start/end` | 0 |
| `text-align: left/right` | 20 | | | |
| `translateX(…)` | 5 | | | |
| 4-value `margin/padding` shorthands (inspect) | 4 | | | |
| `background-position` x | 1 | | | |
| **Total physical** | **89** (+10 to inspect) | | **Total logical** | **16** (all in `responsive.css`) |

Also: inline style `paddingLeft` ×3 (tree indentation, `CodePanels.tsx:40`, `OrganizationScreens.tsx:124,242`) and `marginLeft` ×1 (`AiSetup.tsx:152`); **9** directional icons (`ArrowLeft/Right`, `ChevronLeft/Right`) with no mirroring; no `dir`, `[dir=rtl]`, `:dir()` anywhere; grid shells (`grid-template-columns:252px 1fr`) flip correctly once `dir` is set. Vietnamese and English are LTR, so RTL is a **low-priority** readiness item: stop adding physical properties now (stylelint rule), codemod the 89 mechanically when an RTL locale is requested.

## A.9 Minimal-disruption migration plan (no mass rewrite)

| Phase | Work | Size | Touches UI? |
|---|---|---|---|
| 0 | Add `I18nProvider` with only `vi`, locale-aware `fmtDate/ago/num/usd/tok` (same output as today for `vi`), `lang`/`dir` from the locale, delete the two private `fmtDate` copies; add a **ratchet guard** (`tests/guards`) that records the current per-file string count from the script and fails when a file's count grows | ~1 day, 6 files | no visible change |
| 1 | Move the ~970 strings that already live in pure modules into `locales/vi.ts` (ACTION/ROLE/PERMISSION/PORTAL maps, `STATE_TEXT`, `SSO_ERRORS`, `explainError`, `STATUS`/`STAGE` maps, `*Model.ts` validators) and de-duplicate the 4 role maps and 2 portal maps | pure modules + their unit tests | no (same text) |
| 2 | Shell + auth pages (nav, header, login, no-access, session-expired) ≈ 300 strings → add `en` as a pilot; run the pseudo-locale in the browser harness | one squad, ≈ 1 week | yes (pilot) |
| 3 | **Opportunistic**: a screen is converted when it is touched for another reason; the ratchet baseline only goes down. `AdminApp.tsx` (880) is converted section by section as it is split | continuous | per screen |
| 4 | Server-originated text: map by `code`; ask the backend for stable codes (and optionally `Accept-Language`) | handoff | no |

Explicitly **not** proposed: an automated codemod of all 4,300 strings, or renaming routes.

Tests for the plan: unit — locale negotiation, fallback chain, plural/format helpers, `vi` output identical to the pre-migration helpers; browser — cookie `xweb-locale=en` yields `<html lang="en">` and no Vietnamese in migrated screens; pseudo-locale run reports overflow.

## A.10 Other i18n findings

* Raw enum values are shown in a Vietnamese UI: Theme panel options `SYSTEM/SERIF/MONO/ROUNDED` and `NONE/SM/MD/LG` (`features/studio/builder/panels/MiscPanels.tsx:53-54`), role codes in `AdminApp.tsx:907` (`WORKSPACE_ADMIN` …), backup state/drill results (`AdminApp.tsx:1191,1193`: `OK`, `PASS`) — this contradicts the rule stated in `packages/i18n/src/index.ts:1` ("Users never see raw enums").
* 34 native `confirm()` / `prompt()` calls (21 in `features/admin`, 13 in `features/studio`) for destructive or high-risk actions (e.g. `AdminApp.tsx:285` grant SYSTEM_ADMIN, `:816` risk-acceptance note, `:851` HIGH-risk policy, `AiSetup.tsx:54`): not localisable through a catalogue, not themeable, blocked silently by some browsers (the code fails closed). Replace with the existing `Modal` when each screen is migrated.
* Terminology drifts for the same concept: `EDITOR` is "Người chỉnh sửa" (`i18n/index.ts:14`), "Biên tập viên" (`UserDialogs.tsx:9`), "Biên tập" (`drawers.tsx:102`); `WORKSPACE_ADMIN` is "Quản trị workspace" / "Quản trị không gian làm việc"; the product is "Xweb Studio", "Company Builder Studio", "AI Software Factory" (`StudioApp.tsx:65`, `AdminApp.tsx:139`, `AuthPages.tsx:21`, `PORTAL_LABEL`).
* English fallbacks inside Vietnamese flows: `"AI request failed"` (`core.ts:119`), `"Lỗi {status}"`, `"UPLOAD_FAILED"` text.

## A.11 i18n readiness score: **2 / 10**

Evidence: no framework or locale concept (0); 4,300 hard-coded occurrences, 612 of them `aria-label`/`title`/`placeholder` (0); `lang="vi"` fixed, no `dir` (0). Credit: a ready `@xweb/i18n` slot already wired into all three apps (+1); date/number formatting already goes through five shared helpers using `Intl`, ≈113 call sites (+1); ≈970 strings already sit in pure modules, and per-request dynamic layout + `proxy.ts` already exist for locale negotiation (counted in the plan, not in the score).

---

# Part B — Theme readiness

## B.1 Token inventory

Style sheets loaded by every portal, in order (`apps/*/app/layout.tsx`): `globals.css` → `responsive.css` → `http.css` → `factory.css` → `builder.css`. (`packages/company-ui/src/styles.css` is the published component library for **generated apps**, not the portals.)

| Vocabulary | Defined in | Count | Used by |
|---|---|---|---|
| `--f-*` light "factory" shell tokens (`bg panel line line2 text muted accent accent-ink accent-soft ok ok-bg warn warn-bg bad bad-bg info info-bg`, `focus placeholder disabled-bg disabled-ink`, `r`, `shadow`) = 21 colour tokens | `factory.css:2-3,355` | 23 | Admin, Platform, Studio shell, auth pages |
| `--sp-1…--sp-8` spacing scale | `factory.css:355` | 7 | **0 uses** |
| dark builder tokens `--bg --panel --panel2 --line --text --muted --muted-strong --accent --blue --shadow` | `globals.css:1` | 10 | `.studio` project editor, `builder.css`, `http.css` |
| `--cu-*` | `company-ui/src/styles.css` | 11 | generated apps only |

Counts (`scripts/r2-css-count.mjs`, comments stripped):

| File | hex literals | `rgb()/rgba()` | `var(--*)` uses | `--*` definitions |
|---|---|---|---|---|
| `packages/ui/src/styles/factory.css` | 177 (156 outside token definitions) | 18 | 257 | 32 |
| `packages/ui/src/styles/builder.css` | 87 | 6 | 64 | 0 |
| `packages/ui/src/styles/globals.css` | 79 (70) | 11 | 30 | 10 |
| `packages/ui/src/styles/http.css` | 37 | 6 | 39 | 0 |
| `packages/ui/src/styles/responsive.css` | 6 | 0 | 12 | 0 |
| `packages/company-ui/src/styles.css` | 20 | 3 | 30 | 11 |
| `lib/preview-document.ts` (preview iframe palette) | 32 | 6 | 0 | 0 |
| `lib/schema-preview.ts` (preview iframe palette + selection chrome) | 10 | 0 | 0 | 0 |
| `features/studio/builder/panels/MiscPanels.tsx` (default `#000000` for a new theme colour) | 1 | 0 | 0 | 0 |
| **All** | **449** | **50** | **434** | **53** |

Portal CSS only (the five sheets above): **356 hex literals outside token definitions, 173 distinct values, 41 `rgb()/rgba()`, against 402 `var(--*)` uses** → about **50 % of colour values go through a token** (402 / (402 + 356 + 41)). `#fff` alone appears 57 times (buttons, inputs, cards). Most used tokens: `--f-muted` 56, `--f-line` 44, `--f-accent` 39, `--line` 46, `--muted` 37.

Tsx files: **no hard-coded colours in components** (21 `style={{…}}` attributes: layout only, one `color: var(--f-ok|--f-bad)` at `AiSetup.tsx:80`). Icons are Lucide with `currentColor`.

Not tokenised: **151** `border-radius` declarations (3 use `var(--f-r)`), **26** `box-shadow` (12 via var), **177** `font-size:…px`, one font stack (`Inter, ui-sans-serif, system-ui…`, `globals.css:1`; no `@font-face`/`next/font`, `font-src 'self'` — Inter is used only if installed), 11 `z-index`.

## B.2 Dark-mode readiness

* The product is **two palettes with two vocabularies**: a light shell (`--f-*`) for Admin/Platform/Studio chrome and auth pages, and a dark builder (`--bg/--panel/…`) applied by the `.studio` class (`ProjectWorkspace.tsx:266`, `CodeWorkspace.tsx:129`) plus ad-hoc dark variants inside the light sheet (`.sidebar.dark`, `.studio a{color:#9cc3ff}`, `.studio .formError{color:#ff9a92}` — `factory.css:7,120`). `http.css` states "the Studio is dark-only".
* No `color-scheme` declaration, no `prefers-color-scheme`, no `[data-theme]`, no `forced-colors`/`prefers-contrast` (only `prefers-reduced-motion`, 8 sheets). Consequence *(inference — verify visually)*: native controls (select popups, scrollbars, date inputs, autofill) stay light inside the dark `.studio` surfaces.
* To offer a dark shell, these would need dark variants: all **21** `--f-*` colour tokens (the status pairs `ok/warn/bad/info` and their `-bg` tints cannot simply be inverted: `--f-ok #067647` on a dark tint is 2.69:1, `--f-bad` 2.65:1 — see B.4), the **156** literals in `factory.css` (of which `#fff` ×39 for raised surfaces), `.sidebar.dark` (`#0f1115 #1d2129 #e5e7eb #9ca3af #1a1e26 #262a5c`), avatar (`#e0e7ff #3730a3`), `.xp-orgNotReady` (`#fffbeb #78350f #92400e`), shadows, the template/block preview PNGs (rendered for a white page, `StudioApp.tsx:236`, `library.tsx:19`).
* To let the builder follow the shell, the dark `--bg…` set should become aliases of the shell tokens' dark values and `.studio` should become a density/chrome modifier instead of a palette.

## B.3 Tenant-theme readiness — exact gaps (no contract invented)

What a tenant theme would plausibly override: brand accent and its derivatives, product name and logo mark, favicon/title, font family, corner radius, density. Gaps in the current code:

| # | Gap | Evidence |
|---|---|---|
| G1 | **No source of tenant branding in the data the portals already get**: `Me` (`packages/types/src/index.ts:16-22`) and `TenantView` (`:107`: id, slug, name, status, createdAt) carry no theme/brand/logo/locale field. What C1/C0 would expose is theirs to decide (handoff) | types |
| G2 | **No injection point**: nothing reads a theme before first paint; the layouts are server components (`await connection()`) and `style-src` allows `'unsafe-inline'`, so a per-request `<style>:root{…}</style>` would be technically possible, but there is no code path or ownership for it | `apps/*/app/layout.tsx`, `csp.ts:24` |
| G3 | `--f-accent` carries **three roles at once** — button fill (with `--f-accent-ink`), text/link colour on white, and text on `--f-accent-soft` (active nav) — and `--f-accent-soft` is a hand-picked constant. A brand colour that works as a fill often fails as text (B.4: yellow 1.82:1, green 3.37:1, orange 3.06:1) | `factory.css:2,12,13` |
| G4 | Radius, shadow, type scale, spacing are not tokens (151 / 26 / 177 declarations; `--sp-*` defined but unused) | B.1 |
| G5 | Font: a single stack in `globals.css:1`, no font loading path (`font-src 'self'`, no files) | CSP, CSS |
| G6 | 356 literal colours (173 distinct) outside token definitions, including status/tint colours inside components (`.pill-*`, `.xp-*`, `.bx-badge`) | B.1 |
| G7 | **Brand strings and marks are hard-coded** in 7 files with three spellings; the logo mark is a Lucide `Diamond` on `--f-accent`; no favicon/icon files in `apps/*/app`; `metadata` titles are constants | `AuthPages.tsx:21`, `StudioApp.tsx:65`, `AdminApp.tsx:139`, `PORTAL_LABEL`, `PORTAL_TEXT`, `apps/*/app/layout.tsx` |
| G8 | Dark builder palette is separate and class-scoped (B.2) | `globals.css`, `factory.css:120` |
| G9 | No automated contrast guard: a tenant palette cannot be checked (the app-level `ThemePanel` only validates `#RRGGBB` format) | `MiscPanels.tsx:33-45` |
| G10 | Preview documents (Builder canvas, thumbnails, published sites) have their own fixed palette (`previewStyles`, 42 hex across two files) and selection chrome (`#2c7cff`), unrelated to portal tokens | `lib/preview-document.ts`, `lib/schema-preview.ts:111` |
| G11 | `AppDefinition.theme` (colours map, font enum, radius enum — C2's contract) is **edited and stored but never rendered**: `grep` finds `doc.theme` only in `MiscPanels.tsx:35`; `lib/schema-preview.ts` and `workers/render` never read it. The meaning of the colour keys is undefined | R2-029 |

## B.4 Contrast of key token pairs (`scripts/r2-contrast.mjs`)

Today's light shell: all text pairs pass (`--f-text`/`--f-panel` 17.85, `--f-muted`/`--f-panel` 6.47, `--f-accent` on `--f-accent-soft` 5.55, status pills 5.40–7.04, placeholder 4.95). Dark builder: all text pairs pass (`--text` 16.94, `--muted` 7.78, accent 8.66, `--blue` 4.77). **Borders fail the non-text 3:1 rule: `--f-line` on white 1.24:1 and `--line` on `--panel` 1.30:1**, and form controls in the shell are identified only by that border (`factory.css:26`).

Probe of the token model (illustrative accents substituted for `--f-accent`; **not** a tenant theme contract):

| Accent | white ink on accent (fill) | accent as text on white | accent text on derived soft tint |
|---|---|---|---|
| `#4f46e5` (today) | 6.29 PASS | 6.29 PASS | 5.55 PASS |
| `#1f4fd8` blue | 6.63 PASS | 6.63 PASS | 5.87 PASS |
| `#12a150` green | 3.37 FAIL | 3.37 FAIL | 3.07 FAIL |
| `#f26a1b` orange | 3.06 FAIL | 3.06 FAIL | 2.81 FAIL |
| `#f2b705` yellow | 1.82 FAIL | 1.82 FAIL | 1.73 FAIL |

Naive dark inversion (keep today's colours, swap surfaces): `--f-accent` as text on a dark panel 2.77 FAIL, on a dark accent tint 2.47 FAIL, `--f-ok` on dark ok tint 2.69 FAIL, `--f-bad` 2.65 FAIL; a lightened accent `#a5b4fc` gives 8.73 PASS — i.e. each semantic colour needs its own dark counterpart. Reverse case: the dark scope's pink error `#ff9a92` and link `#9cc3ff` on the **light** panel are 2.04 and 1.80 FAIL, so a surface that loses its `.studio` scope silently becomes unreadable. Preview palette: `.logo #078675` on white 4.49 (just under 4.5; bold 800 weight in the preview).

## B.5 Icons, logos, images

* Icons: Lucide, imported by name, `currentColor` (themeable); `ProviderLogo` renders Simple Icons single-colour marks with `fill="currentColor"` in a neutral tile (themeable). The product logo mark is `Diamond` filled on an `--f-accent` tile in three files.
* Raster images: template/block previews (`/api/v1/templates/{id}/preview`, `/api/v1/component-packages/{id}/preview`) are server PNGs of a light page; they would stay light in a dark shell (acceptable, but they sit in tiles with `--f-*` borders).
* No favicon, app icon or web manifest in `apps/*/app`.

## B.6 Third-party / embedded surfaces

* Builder canvas and thumbnails: sandboxed `srcdoc` with the fixed light preview palette (correct — it previews the *site*, not the portal); the dark surround `.bx-center{background:#0a0e13}` and the selection overlay (`#2c7cff`, label "Đang chỉnh sửa" in a CSS `content:`) are hard-coded.
* Code-project preview: `<iframe sandbox="allow-scripts" src=…>` from the sites origin — third-party content, cannot be themed from the portal (an opaque-origin frame does not inherit `color-scheme` unless its own document declares it).
* No other third-party widgets.

## B.7 What to do first (proposal, still no contract)

1. Split `--f-accent` into fill / ink / text / soft / focus tokens and derive `-soft` from the accent; add `--f-radius-*`, `--f-shadow-*`, `--f-font`.
2. Replace the 356 literals with tokens in `factory.css` first (156), then `builder.css` (87), `http.css` (37), `globals.css` (70).
3. Declare `color-scheme` on `:root` and on `.studio`; introduce `[data-theme]` + `prefers-color-scheme` handling with the aliasing described in B.2.
4. Add a unit test that evaluates the contrast of the key pairs (the script already does) for any palette that is loaded.
5. Decide with C2 whether the preview renderer must consume `AppDefinition.theme` (G11).

## B.8 Theme readiness score: **3 / 10** (tokens 4 · dark mode 2 · tenant theme 2)

Evidence: a coherent light token set exists and is used 402 times (+), icons are `currentColor` (+); but ≈50 % token coverage with 356 literals, a second unrelated dark vocabulary, no `color-scheme`/`prefers-color-scheme`/`data-theme`, a single token with three roles, radius/shadow/type not tokenised, hard-coded brand names and logo, no tenant data or injection point, and a stored-but-unrendered app theme.

---

# Handoffs

| To | Need |
|---|---|
| C1 | does `/auth/me` (or a profile endpoint) carry `locale` and `timeZone`; is there, or will there be, tenant branding data (name/logo/accent) — C5 will consume whatever is chosen |
| C2 | `site.lang` for published sites; semantics of `AppDefinition.theme.colors` keys and whether the render worker must apply them (R2-029); slug validation (`pg.slug` is a path) |
| C0 | `package.json` change if a message-format library is chosen; cookie `Domain` for a shared locale cookie across the three hosts; `next.config.ts`/`csp.ts` changes if theme `<style>` injection is adopted |
| Backend (any team) | stable error `code` for every refusal; deployment events / code-change errors as codes (they are free text today: `ReleaseModal.tsx:192`, `CodeWorkspace.tsx:223`) |

## Not verified

No browser or server was started: dark-mode appearance of native controls, actual rendering of long English text, the effect of the browser time zone on server day-buckets, and whether the backend honours `Accept-Language` are all unverified. String counts are lower bounds for Latin-only labels inside TS maps (A.2).

---

# Issue table

Owner legend: S1–S4 = C5 squads (C5-L maps the area to a squad; the area is given); R = research; NOT C5 = another team.

| ID | PORTAL | ROUTE/COMPONENT | STATE | SEVERITY | CATEGORY | EXPECTED | ACTUAL | ROOT_CAUSE (file:line) | OWNER | PROPOSED FIX | TEST | STATUS |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| R2-017 | all | whole UI | default | P2 | i18n | UI strings in locale catalogues, a locale can be switched | no i18n framework; **4,300** hard-coded occurrences (3,092 unique; 438 interpolated; 612 aria-label/title/placeholder); Studio ≈2,440, Admin ≈2,040, Platform ≈2,040 reachable | `packages/i18n/src/index.ts` (23 lines of constants); `scripts/r2-i18n-count.mjs` | C5-L (all squads); library choice C0 (`package.json`) | phased plan A.9 (provider + ratchet guard first, pure modules second, shell/auth pilot third, opportunistic after) | ratchet guard in `tests/guards`; unit for fallback/plural; browser `xweb-locale=en` | OPEN |
| R2-018 | all | `<html>` of the 3 apps and preview documents | default | P2 | i18n | `lang`/`dir` follow the active locale (and the site language for published pages) | `lang="vi"` hard-coded; no `dir`; no negotiation | `apps/{platform,admin,studio}/app/layout.tsx:14-15`; `app/layout.tsx:18`; `lib/schema-preview.ts:103`; `lib/preview-document.ts:38` | S-Shared (C5); site language NOT C5 (C2 `site.lang`) | cookie + `Accept-Language` via the existing `proxy.ts` (`x-locale`), layout reads it (A.5) | unit negotiation; browser `<html lang>` | OPEN |
| R2-019 | all | `fmtDate/ago/num/usd/tok`, report screens | default | P3 | i18n / formatting | one locale-aware formatter set; same grouping in one table; zone-explicit dates | `vi-VN` hard-coded ×5 `Intl.DateTimeFormat` and ×6 `toLocale*`; `usd()` uses `en-US` next to `vi-VN` `num()/tok()`; 10 `.toFixed` print `.` decimals; two private copies of `fmtDate`; no `Intl.NumberFormat/PluralRules/RelativeTimeFormat`; no `timeZone`; local-midnight day parsing | `packages/ui/src/ui.tsx:9-20`; `features/studio/drawers.tsx:15`; `features/studio/ReleaseModal.tsx:18`; `features/admin/AdminApp.tsx:457,484,557,753,754,1027,1033,1034,1064,1182,936` | S-Shared (C5); timestamp/zone semantics NOT C5 (C1/backend) | locale-aware wrappers in `@xweb/ui` (same output for `vi`), delete the copies, `Intl.NumberFormat` currency, `timeZone` argument | unit: `vi` output identical to today; `en` output | OPEN |
| R2-020 | all | role / portal / product labels | default | P3 | i18n / consistency | one label per concept | `EDITOR` = "Người chỉnh sửa" / "Biên tập viên" / "Biên tập"; `WORKSPACE_ADMIN` two wordings; portal names in two tables; product named "Xweb Studio" / "Company Builder Studio" / "AI Software Factory" | `packages/i18n/src/index.ts:13-23`; `features/admin/UserDialogs.tsx:8-9`; `features/studio/drawers.tsx:102`; `packages/permissions/src/index.ts:34`; `StudioApp.tsx:65`; `AdminApp.tsx:139`; `AuthPages.tsx:21` | S-Shared (C5) | phase 1 of A.9 (single catalogue); product owner decides the names | unit: no duplicate maps (script) | OPEN |
| R2-021 | all | error display | on error | P3 | i18n / errors | message chosen by error `code` from the catalogue | server free text is shown (`ApiError.message`, deployment `ev.message`, change `error`); English fallbacks (`"AI request failed"`, `"UPLOAD_FAILED"` text, `Lỗi {status}`) | `packages/api-client/src/core.ts:70-75,119`; `features/studio/builder/core/errors.ts:39-89`; `features/studio/ReleaseModal.tsx:192`; `features/studio/CodeWorkspace.tsx:223` | S-Shared (C5); stable codes NOT C5 (backend) | `errors.<CODE>` catalogue first, server text last | unit per code | OPEN |
| R2-022 | Studio, Admin, Platform | Theme panel, workspace role select, backups table | default | P3 | i18n / copy | no raw enum in a Vietnamese UI (rule at `i18n/index.ts:1`) | raw `SYSTEM/SERIF/MONO/ROUNDED`, `NONE/SM/MD/LG`, `WORKSPACE_ADMIN…VIEWER`, backup `state`/drill `PASS` shown | `features/studio/builder/panels/MiscPanels.tsx:53-54`; `features/admin/AdminApp.tsx:907,1191,1193` | S-Studio / S-Admin (C5) | `enum.*` catalogue entries | unit: no enum-looking text in rendered options | OPEN |
| R2-023 | Admin, Platform, Studio | destructive / risky actions | on click | P3 | i18n / theme / a11y | in-app modal, localisable and themeable | 34 native `confirm()`/`prompt()` calls (e.g. grant SYSTEM_ADMIN, HIGH-risk policy, risk-acceptance note); fail closed if the browser suppresses them | `features/admin/AdminApp.tsx:285,290,295,404,405,686,816,851,1081,1082`; `features/admin/AiSetup.tsx:54,284`; `features/studio/*` (13 sites) | S-Admin / S-Studio (C5) | replace with the existing `Modal` as each screen is migrated | browser per flow | OPEN |
| R2-024 | all | CSS | n/a | P3 | RTL readiness | logical properties, `dir` supported | 89 physical declarations (margin 12, padding 9, border 30, offsets 18, text-align 20) + 4 inline styles vs 16 logical; 9 directional icons unmirrored; no `dir` | `packages/ui/src/styles/*.css`; `features/studio/CodePanels.tsx:40`; `features/admin/OrganizationScreens.tsx:124,242`; `AiSetup.tsx:152` | S-Shared (C5) | lint rule now for new CSS; mechanical codemod when an RTL locale is requested | stylelint rule; visual check under `dir=rtl` | OPEN |
| R2-025 | all | CSS tokens | default | P2 | Theme / dark mode | a single token vocabulary that can be re-pointed per theme | two vocabularies (`--f-*` 23, dark `--bg…` 10), ≈50 % of colour values tokenised, **356 hex literals outside token definitions (173 distinct)**, no `color-scheme`, no `prefers-color-scheme`, no `[data-theme]` | `packages/ui/src/styles/factory.css:2-3,355`; `globals.css:1`; `builder.css`; `http.css` | S-Shared (C5) | B.7 steps 2-3 | `scripts/r2-css-count.mjs` ratchet (literals must not grow) | OPEN |
| R2-026 | all | accent colour model | n/a | P2 | Theme / tenant | a brand colour can be applied without breaking contrast | one token (`--f-accent`) is fill, text and text-on-tint at once; a green, orange or yellow brand fails 4.5:1 in at least two of those roles (3.37 / 3.06 / 1.82) | `packages/ui/src/styles/factory.css:2,12,13`; `scripts/r2-contrast.mjs` §3 | S-Shared (C5) | split into fill / ink / text / soft / focus; derive soft; validate contrast when a palette loads | unit: contrast table for probe accents | OPEN |
| R2-027 | all | CSS | n/a | P3 | Theme / tokens | radius, shadow, spacing, type scale as tokens | 151 `border-radius` (3 var), 26 `box-shadow` (12 var), 177 `font-size:px`, `--sp-*` defined with 0 uses, single font stack, no font loading | `packages/ui/src/styles/*.css`; `factory.css:355`; `globals.css:1` | S-Shared (C5) | `--f-radius-*`, `--f-shadow-*`, `--f-font`, adopt `--sp-*` | css-count script | OPEN |
| R2-028 | all | brand, logo, favicon | default | P3 | Theme / tenant | name, mark and icons from one source | product name in 7 files / 3 spellings, `Diamond` mark ×3, no favicon/icon/manifest, constant `metadata` | `AuthPages.tsx:21`; `StudioApp.tsx:65`; `AdminApp.tsx:139`; `packages/i18n/src/index.ts:21-23`; `apps/*/app/layout.tsx` | S-Shared (C5); tenant branding data NOT C5 (C1) | one `brand` module; slot for a logo asset (needs C1 data and CSP `img-src`) | unit: no brand literal outside the module | OPEN |
| R2-029 | Studio | Builder → Giao diện (`UPDATE_THEME`) | after saving a theme | P2 | Theme / dead feature | a saved `AppDefinition.theme` changes the preview and the published site | `ThemePanel` persists `colors/fontFamily/radius`, but `renderSchemaDocument` and the render worker never read `theme` (`grep doc.theme` finds only the panel), so nothing changes visually; colour key names are free text with no defined meaning | `features/studio/builder/panels/MiscPanels.tsx:35-45`; `lib/schema-preview.ts` (no `theme`); `workers/render/*.ts` | S-Studio (C5, renderer) + C2 (semantics of colour keys) | agree key vocabulary with C2, map to CSS variables in `previewStyles`, render in preview and published output | unit: renderer output contains the theme variables; browser: change colour, canvas updates | OPEN |
| R2-030 | all | form controls, borders | default | P3 | Theme / contrast | non-text UI boundary ≥ 3:1 (WCAG 1.4.11) | `--f-line` 1.24:1 on white and `--line` 1.30:1 on `--panel`; inputs are identified only by that border; preview `.logo #078675` 4.49:1 | `packages/ui/src/styles/factory.css:26`; `globals.css:1`; `lib/preview-document.ts:14` | S-Shared (C5); a11y review NOT R2 | darker control border token (≈ `#8a94a6` on white) distinct from decorative `--f-line` | `scripts/r2-contrast.mjs` | OPEN |
| R2-031 | Studio | dark scopes inside the light sheet | default | P3 | Theme / robustness | scope-independent contrast | dark-surface colours (`#ff9a92`, `#9cc3ff`) are literals under `.studio …`; outside the scope they are 2.04 / 1.80:1 on white; native controls lack `color-scheme` *(inference)* | `packages/ui/src/styles/factory.css:7,120,195-198`; `http.css:11` | S-Shared (C5) | tokens per scope + `color-scheme` | visual + contrast script | OPEN |
| R2-032 | Studio | canvas, thumbnails, template previews | default | P3 | Theme / embedded | preview chrome follows the theme, previews of the site stay light on purpose | selection outline (`#2c7cff`), the label "Đang chỉnh sửa" (CSS `content:`) and `.bx-center #0a0e13` are literals; template/block preview PNGs are light-only | `lib/schema-preview.ts:111`; `packages/ui/src/styles/builder.css:12`; `StudioApp.tsx:236`; `features/library.tsx:19` | S-Studio (C5) | tokens for overlay chrome; document that previews are intentionally light | visual | OPEN |
