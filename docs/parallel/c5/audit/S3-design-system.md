> **SUPERSEDED_BY:** `docs/QA_FINAL.md` - historical document, kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# S3 — Design system / UX / Accessibility audit (PHASE 1: audit only, no product code changed)

| | |
|---|---|
| Auditor | C5-S3 (design system / UX / accessibility specialist), C5 frontend squad |
| Base | `agent/c5-web` @ `9f858c2` (branch `agent/c5-s3-audit`), 2026-10-08 |
| Scope | `packages/ui/**` (ui.tsx, Modal, ModalHeader, NavDrawer, PortalSwitcher, Picker, Switch, ScrollRegion, useDialog, useLoad, useOverflow, icons, `styles/{globals,responsive,http,factory,builder}.css`), every portal's USE of them (`features/admin/**`, `features/studio/**`, `apps/*`), `packages/auth` (login / auth pages), `packages/i18n`. Text: see companion file `S3-text-and-terminology.md`. |
| Not touched | backend, `docs/contracts/**`, any product code. Only the two audit documents are added. |
| Owner guess (column OWNER) | S3 = `packages/ui` + CSS + a11y primitives; S1 = `features/admin/**` (Platform / Admin screens); S2 = `features/studio/**` (Studio / Builder); NOT C5 = backend text / contract. **Assumption — adjust if the squad split differs.** |

**Evidence legend.** `[C]` read in code (file:line). `[M]` computed from the CSS/TS by script (numbers are reproducible, see Appendix A). `[H]` verified in a real browser (the system Google Chrome driven by the repository's `playwright-core`, headless) against a **HARNESS, NOT REAL BACKEND**: either the repository's own harness pages (`tests/browser/*`, built into the ignored `.test-build/`) or a harness page written for this audit that mounts a real component with a fake `calls` object. `[R]` = replica: static markup copied from a component plus the **real** CSS files in the real load order (used where the component cannot be mounted without Next's router). Nothing here touches a real backend; nothing counts as backend evidence. Every `[H]`/`[R]` result below is labelled "HARNESS, NOT REAL BACKEND".

**Reading note.** `globals.css` is a **single 9.7 KB line** (minified in the repo), so it has no line numbers: it is cited by selector. All other CSS files are cited `file:line`.

---

## 0. Summary

(counts and the ten most important issues are in section E.0 and in the final report)

CSS actually loaded per app (order matters, later wins at equal specificity):

| App | `globals` (dark v1 Studio shell + dead marketing mock) | `responsive` | `http` (dark v1 forms) | `factory` (light shell + dark editor additions) | `builder` (`bx-*`) |
|---|---|---|---|---|---|
| Platform `apps/platform/app/layout.tsx:3-6` | yes | yes | yes | yes | **no** |
| Admin `apps/admin/app/layout.tsx:3-6` | yes | yes | yes | yes | **no** |
| Studio `apps/studio/app/layout.tsx:3-7` | yes | yes | yes | yes | yes |
| legacy root `app/layout.tsx:3-7` | yes | yes | yes | yes | yes |

`features/admin/**` imports `bx-*` markup (`DataSourcesPanel`, `bx-h3`, `bx-h4`) from the Studio, but Platform and Admin never load `builder.css` → issue S3-001 / S3-007.

---

## A. DESIGN-SYSTEM AUDIT

### A1. Three visual idioms, one token vocabulary each — are they coherent?

| Idiom | Where | Tokens | Look |
|---|---|---|---|
| **L — light "factory" shell** | Platform, Admin, Studio home / projects / templates / components / activity, auth pages. `.shell` + `.sidebar.dark` + `.topHeader` + `.page` (`factory.css:6-27`) | `--f-*` (23 vars, `factory.css:2-3`, `355`) + `--sp-*` (7, unused) | white cards on `#f5f6f8`, indigo `#4f46e5` accent, **dark** sidebar `#0f1115` |
| **D1 — dark project workspace** | Studio project in AI mode and in Code mode (`.studio`, `.topbar`, `.promptPane`, `.previewPane`, `.codeStudio`, drawers `.drawer`, dialogs `.modal`) | `--bg --panel --panel2 --line --text --muted --accent --blue --shadow` (`globals.css` `:root`), `--muted-strong` | near-black `#0b0d10`, teal `#20c997` + blue `#2c7cff` gradient send button, **white** primary button |
| **D2 — dark builder** | Studio Design mode (`.bx-*`, `builder.css`) | none of its own (64 `var()` uses of D1 tokens, **0 definitions**) | same dark surface but indigo `#6366f1` / `#262a5c` selection, **white** primary `.bx-btn.primary`, 28 px controls |

Findings:

1. **Coherent only at the shell boundary.** The three idioms share the 14 px / Inter-or-system type and the Lucide icon set, but they have **three brand colours** for "primary / selected": teal `#20c997` (D1 accent, `.uploadBox`, focus ring in `http.css:21`), blue `#2c7cff` (D1 gradient, `.paneTabs`), indigo `#4f46e5` / `#6366f1` (L accent, D2 selection, `.modeTabs.active #262a5c`, `.bx-node.active #6366f1`). The primary button is **indigo-filled in L, white-filled in D1 and D2**. [M] `#6366f1` (literal ×8 in `builder.css`/`factory.css`) and `#4f46e5` (`--f-accent`, 39 `var()` uses) are both used as "the accent" — Tailwind indigo-500 vs 600. `#eef2ff` (×8) vs the token `--f-accent-soft: #eef0ff` are two different "indigo-50" values.
2. **The idioms leak into each other** because the dark rules are global selectors and the light rules are scoped by `.shell`:
   * `body` is dark (`globals.css`: `background:var(--bg);color:var(--text)`), every light page re-skins itself via `.shell,.authPage,.splash,.wsError{background:var(--f-bg);color:var(--f-text)}` (`factory.css:6`). A page that forgets `.shell` shows light text on the dark body.
   * `.button`, `.smallButton`, `.hint`, `.formError`, `.chip`, `.toast`, `.drawer` are **dark-only** classes; whenever a screen reuses them in a light shell they need a patch (`factory.css:120-121`, `197-199`, `390`). `DataSourcesPanel` inside Admin is the failure case: white `.button.primary` on a white card (S3-001).
   * Studio dashboards (light) and the Studio project (dark) are the same app; going into a project flips the whole UI from light to dark with a different top bar, button set and form controls.
3. **Verdict:** two distinct themes (light shell, dark editor) is a legitimate product choice; the incoherence is the **third, duplicated dark implementation** (D1 vs D2: `.button` 36 px vs `.bx-btn` 28 px, `.insField` vs `.bx-field` vs `.settingField`, `.chip` defined twice, `.smallButton` defined three times) and the absence of tokens in D2.

### A2. Colour tokens vs hard-coded colours [M]

Hard-coded colours per CSS file (token definitions in `:root` excluded from the "classification" columns; `var()` = token uses):

| File | hex occurrences (incl. token defs) | unique hex | `rgb()/rgba()` | `var(--…)` uses | tokens defined |
|---|---:|---:|---:|---:|---:|
| `globals.css` | 79 | 68 | 11 | 30 | 10 |
| `responsive.css` | 6 | 6 | 0 | 12 | 0 |
| `http.css` | 37 | 28 | 6 | 39 | 0 |
| `factory.css` | 177 | 90 | 18 | 257 | 31 (23 `--f-*`, 7 `--sp-*`, `--mpad`) |
| `builder.css` | 87 | 40 | 6 | 64 | 0 |
| **total** | **386** | **191** across files | **41** | **402** | **41** |

Hard-coded colours in TSX/TS (`features/**`, `packages/ui/src/*.tsx`, `apps/**`, `packages/auth`, `packages/i18n`): **1** (`features/studio/builder/panels/MiscPanels.tsx:62`, `"#000000"` as the default value of a new theme colour = data, legit). The components are clean; **all** the colour debt is in the five CSS files. Inline `style={{}}` in components: 15 (10 legit dynamic values — indent, drag transform, canvas position, logo size, opacity while dragging; 5 one-off hacks: `OrganizationScreens.tsx:170 minWidth:0`, `AiSetup.tsx:152 marginLeft:auto`, `AdminApp.tsx:968,970,1053 width:70/64`).

Classification of the 356 non-definition hex occurrences (script: a rule is "dead" when every selector contains a class with no user in `features/ packages/ components/ app/ apps/`; "duplicate" when the value is exactly a defined token's value):

| Class | Count | Meaning | Examples |
|---|---:|---|---|
| **legit / dead** | 39 | colours of the marketing-site mock and v1 pages that no component renders (`.hero`, `.siteNav`, `.productGrid`, `.authCard`, `.listPage`, `.projectList`…) | `globals.css .hero{background:…#0b6b5f,#0e4559 68%,#133b63}`, `.machine`, `.fakeInput` |
| **should-be-existing-token** | 68 | literally equal to a defined token | `#fff` ×51 (= `--f-panel`; in the dark idiom it is really "text on dark"), `#b42318` ×4 (= `--f-bad`), `#175cd3` ×3 (= `--f-info`), `#eef0ff` ×2, `#067647`, `#e4e7ec` |
| **should-be-NEW-token (palette gap)** | 164 | 50 distinct values used ≥ 2× with no token | `#c7d2fe` ×13 (focus ring / soft border), `#6366f1` ×8, `#eef2ff` ×8, `#131a22` ×6, `#111820` ×6, `#fecdca` ×6, `#93c5fd` ×6, `#b54708` ×5, `#cbd7e3` ×4, `#3a4655` ×4 (input border), `#262a5c` ×4 (selected tab) |
| **one-off** | 85 | 85 values used exactly once (some are status borders / gradient stops / diff colours) | `#9aefd5` `.savedPill`, `#0b1220` `.codeEditor`, `rgba(46,160,67,.25)` `.dl.add` |

Token hygiene: `--sp-1…--sp-8` (7 spacing tokens, `factory.css:355`) have **0 uses**; `--danger` and `--warn` are **used but never defined** (`builder.css:98`, `100`; they work only through their hex fallbacks); there are **no** tokens for: surface-2/3 of the light theme, status borders, focus ring, overlay scrims (`rgba(15,23,42,.45)`, `rgba(0,0,0,.44)`, `.5` — three different scrims), selection indigo, code surface.

Token contrast pairs: all computed in section C.3.

### A3. Spacing scale [M]

Declared scale `--sp-*` = 4 / 8 / 12 / 16 / 20 / 24 / 32 (unused). Actual `padding / margin / gap` pixel values (674 declarations): **47 % (318) are on the declared scale**, **53 % are not**. The de-facto scale is 2-based: `2×38 4×49 6×91 8×148 10×107 12×72 14×34 16×30 18×22 …`; `6`, `10`, `14`, `18` together (254) outnumber `4` and `16` (79). Odd values (`5×8 7×9 9×10 11 13×2 26 52 58 70`) are one-offs. Not a user-visible defect, but the scale cannot be enforced or reused.

### A4. Typography [M][C]

* Family `Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif` (`globals.css`). **Inter is never loaded** (no `@font-face`, no `next/font`; `grep` over `app apps packages` finds none): the UI renders in whatever the OS provides, so metrics differ per OS. Monospace: `ui-monospace, SFMono-Regular, Menlo, monospace` (6 places, fine).
* **17 distinct font sizes**: `10 11 12 13 14 15 16 17 18 20 21 22 24 30 34 38 52` px. Frequency: `12px ×62`, `13px ×48`, `14px ×26`, `11px ×18`, others ≤ 7. **80 declarations are 11–12 px** (10 % of text rules are ≤ 11 px: `.chip`, `.bx-badge`, `.composerFooter`, `.state`, `.usageMeta`…).
* Weights `400, 600, 650, 700, 800, 900`; **650** (`factory.css:357`, `h2,h3`) does not exist in a non-variable system font → renders as 600 or 700 depending on the OS; 800/900 (`.projectName`, `.brandMark`, `.siteLogo`) the same.
* Line-height tokens: 1.2–1.65 mixed, one pixel value (`18px`, `.bx-badge`); letter-spacing 10 values (`-.035em … .14em`).
* Headings: `.pageHead h1` 24 px (`factory.css:38`), `.authPanel h1` 24, `.homeHero h1` 30, `.intro h1` 24, `.codeCard h1` 22, `.cardHead h2` 15, `.stateView h2` 16, `.modalBody h2` browser default 24 in `CreateAccountDialog` vs `.xp-modalHead h2` 18 in dialogs with `ModalHeader` (see S3-007). There is no type scale; each component sets its own.

### A5. Radius, borders, shadows [M]

* **16 distinct radii**: `3 5 6 7 8 9 10 11 12 14 15 16 18 22 26 px`, `50%`, `999px`, plus `var(--f-r)` (=10 px, only 3 uses). Frequent: `10px ×33`, `8px ×31`, `12px ×16`, `999px ×16`, `6px ×10`, `9px ×10`. Buttons: `.btn` 8, `.button` 10, `.bx-btn` 9, `.smallButton` 10, `.sendButton` 999, `.cu-btn` 999.
* Borders: `1px solid var(--f-line|--line)` everywhere (consistent). Dashed: `.comingSoon` 1px, `.uploadBox` 2px, `.libList button`, `.bx-lib-item`, `.xp-orgEmpty`, `.bx-state` 1px.
* **11 distinct shadows**: tokens `--shadow ×6`, `--f-shadow ×4`; ad-hoc: `0 22px 60px`, `0 12px 32px` (two alphas), `0 8px 24px`, `0 20px 60px`, `0 0 40px`, `-20px 0 60px`, `0 30px 60px`, `0 14px 26px`, `0 1px 2px`, `0 0 0 3px` focus-glows (two different).

### A6. Buttons — duplicates [M][C]

Usage in components (`features/**`, `packages/ui`, `packages/auth`): `.btn` **188**, `.smallButton` **65**, `.button` **61**, `.bx-btn` **51**, `.sendButton` 2 — **four live button systems**:

| System | File | Sizes | Primary | Danger | Icon button |
|---|---|---|---|---|---|
| `.btn` (+`primary ghost danger sm block`) | factory | 36 / 30 | indigo fill | red text, pink border; `btn danger primary` red fill | `.xp-btnIcon` + SVG |
| `.button` (+`primary ghost icon`) | globals | 36 | **white fill** | none (uses `.smallButton.danger`) | `.button.icon` 36 (32 min) |
| `.smallButton` | globals ×2, builder | 30 → 28 min | — | `.smallButton.danger` pink text | — |
| `.bx-btn` (+`sm primary danger`), `.bx-icon`, `.bx-mini`, `.bx-drag` | builder | **34 → 28** (conflict, see below), 28, 24→28, 24→28 | **white fill** | `#fda29b` text on dark | `.bx-icon` 32 |
| other button-like: `.chipBtn` `.linkButton` `.linkChip` `.xp-clear` `.xp-iconBtn` `.xp-chev` `.xp-advBtn` `.xp-pickerBtn` `.publishChoice` `.starter` `.suggestion` `.kpiButton` `.segmented button` `.modeTabs button` `.paneTabs button` `.bx-tabs button` `.tabs>*` `.fileTree button` `.designTree button` `.changeItem` `.outline>li>button` `.sortRow>button` `.libList button` `.projectList button` | — | 20 more | — | — | — |

* Conflict: `.bx-btn{min-height:34px}` (`builder.css:21`) is overridden by `.bx-tabs button,.bx-btn,.bx-icon{min-height:28px}` (`builder.css:130`, the "accessibility pass"): the target-size pass **shrank** every builder button from 34 to 28 px (still ≥ 24 px for WCAG 2.5.8, 36 px only under `(pointer:coarse)` and only for `.bx-btn.sm`, `builder.css:132-134`). `.smallButton` is declared 36 → 30 → 28 in three places.
* Variants differ in radius, weight (`700/800/600`), hover (`.btn:hover` border; `.button:hover` border `#3a4452`; `.bx-btn` none) and disabled look (`.btn.primary:disabled` pastel; `.button:disabled opacity .5`).
* Verdict: needs one `Button` component (variants: primary / secondary / ghost / danger; sizes sm / md; icon-only) with two themes; the tests and the Builder CSS are the main users of the duplicates.

### A7. Form controls [M][C]

Controls: `<input` 175, `<select` 115, `<textarea` 10. **Custom** `Picker` is used in 2 places; `Switch` in 2 places versus **6 `label.switch + <input type=checkbox>`** (`AdminApp.tsx:866,971,993`, `AiSetup.tsx:224`, `SitePanels.tsx:63`, `CodePanels.tsx:52`) — both are "an on/off setting" with different look, size and semantics (`role=switch` vs checkbox).

* Five checkbox patterns: `.checkRow`, `.xp-check`, `.check`, `.switch`, `.pickList label`.
* Six field-label patterns: `.field>span` (admin), `.insField label`, `.settingField span`, `.bx-field label`, `.xp-pickerLabel`, `.inlineLabel`; visible heights 36 (`.shell input`), 40 (`.field input`, `.authCard input`), 42 (`.xp-empFilters .xp-search`), 44, 48 (`.xp-pickerBtn`), 32/34 (`.bx-*`).
* Nine input skins: `.shell input` (`factory.css:26`), `.authCard input` + `.inlineForm input` (`http.css:9`), `.insField input` (`http.css:47`), `.settingField input` (globals), `.bx-field input` (`builder.css:60`), `.composerBox textarea`, `.xp-keyInput`, `.xp-slugInput`, `.xp-search`.
* Validation wiring: `aria-invalid` + `aria-describedby` + `role=alert` exist **only** in the newer dialogs (`TenantScreens`, `OrganizationScreens`, `ProvisioningScreens`: 11 `aria-invalid` in total). All older forms (`AdminApp`, `AiSetup`, `StudioApp`, `drawers`, `SitePanels`, `CodePanels`) show a free `<p class="formError" role="alert">` that is not associated with the field (82 `role="alert"`, 11 `aria-invalid`).
* Native `<select>` for 115 uses, including `<select multiple>` for action chaining (`ActionEditor.tsx:57`, no hint that Ctrl/⌘ is needed) and a `size=6` listbox (`TenantScreens.tsx:99`); the `Picker` listbox pattern is not used where options have hints/logos (Studio model chooser, role chooser, unit type picker is the exception).
* Disabled: `--f-disabled-bg/-ink` (3.47:1, exempt) vs `opacity .5/.6` in dark idiom; both acceptable, not unified.
* Placeholder `--f-placeholder #677086` 4.95:1 (PASS); dark placeholders are the UA default.

### A8. Tables [M][C]

40 data tables in the portals; classes `.table` (39), `.memberTable` (1), `.settingsTable`, `.bx-table` (**undefined**), `.xp-empTable` (card layout ≤ 720 px). `th` = 12 px uppercase muted `.03em` (`factory.css:45`); `td small` secondary line; `.clickRow` hover. Only **2 of 40** tables have a `<caption>` (and one of them is hidden with the undefined class `sr-only`, S3-047); `scope` appears on 3 tables; the other 37 rely on implicit header association. No sticky headers (so nothing can cover the focused cell). On ≤ 720 px `.xp-empTable` becomes `display:block` rows (`factory.css:317-322`), which drops table semantics in Safari/VoiceOver. Wide tables are contained by the card (`.card{overflow-x:auto}`, `factory.css:352`) which `ScrollRegion` makes keyboard-focusable only while it scrolls (good pattern).

### A9. Cards / KPI [M]

`.card` (1 definition, 3 re-declarations: `factory.css:39`, `352`, `393`, `398`), `.kpi`, `.healthCard`, `.projectCard`, `.compCard`, `.libCard`, `.typeCard`, `.portalCard`, `.providerItem`, `.xp-orgEmpty`, `.xp-personChosen`: 11 card-like boxes with radii 10 / 12 / 14 / 16 and 3 different box-shadows. `Card` (`ui.tsx:73`) always renders `<section>` + `ScrollRegion` (adds a ResizeObserver, a MutationObserver on the whole subtree and a window `resize` listener per card). KPI tiles are `div`s without any grouping/`dl` semantics (`Kpi`, `ui.tsx:70`).

### A10. Badges / pills / status colours [C]

`Pill` maps status strings → 4 tones through a 45-key table (`ui.tsx:62-69`). The table is **reused as a colour picker**: 49 `<Pill value=… label=…>` call sites pass a fake status to get a tone; **13 occurrences of the `"UNKNOWN"` tone key (= grey)** are used for things that are warnings or severities: `Rủi ro cao` (high-risk setting, `AdminApp.tsx:863`), `Trả phí` (`AiSetup.tsx:218`), `Chờ duyệt` (`AdminApp.tsx:680`, `StudioApp.tsx:229`), alert `WARNING` and security `MEDIUM` (`AdminApp.tsx:997`, `1005`), `Quản trị hệ thống` is shown as `PUBLIC` (info, `AdminApp.tsx:261`). A "high risk" label is therefore the **least** salient colour on the screen (S3-044). Other chips: `.chip` (dark, 11 px, defined in globals **and** builder with different padding), `.tag`, `.xp-typeBadge`, `.xp-opt-tag`, `.xp-depthTag`, `.bx-badge`, `.saveState`, `.savedPill`, `.environmentBadge`, `.you`, `.state.s-*`, `.chip-*` (7 builder variants). Status is never colour-only for pills (text label present) **but** `.healthCard` left border and `.alertRow` border are colour-only accents next to a text pill (acceptable).

### A11. Empty / error / loading states [C]

`StateView` (`ui.tsx:36`) is the shared state with 10 kinds, icon + `h1|h2` + detail; `ErrorState` maps `ApiError` status → kind. Good: one component, roles `status` (loading) / `alert` (everything else except `empty`). Issues: (a) `forbidden`/`notfound`/`conflict` whole-page states are `role="alert"` → announced assertively on navigation (S3-038); (b) the builder uses a **different** state component `StateBox` (`ui/primitives.tsx:12`, `bx-state`) with its own wording ("Có lỗi", "Chưa sẵn sàng", "Đang tải…") and the admin `ComingSoon` (dashed box + pill "Chưa triển khai"); (c) `Splash` (`PortalApp.tsx:19`) renders the loading state as an `h2` with no `h1` and no `<main>`; (d) inline loading spinners have no accessible text except the `role=status` wrapper title.

### A12. Focus state consistency [M][H]

Focus ring definitions that can apply to the same page:

| Where | Rule | Colour | Contrast on white |
|---|---|---|---:|
| `.btn`, `.navLink`, `.shell a`, `.shell summary`, switch | `factory.css:369`, `.xp-switch:focus-visible` | `#4f46e5` 2 px, offset 2 | 6.29 |
| inputs / selects / textarea | `factory.css:367-368` (the earlier `:27` rule is overridden) | `border #4f46e5` + `outline 2px #c7d2fe` offset 0 | border 6.29 / ring **1.49** |
| every other `button/a/summary/input` | `responsive.css:62-70` | `#67b8ff` 2 px offset 2 | **2.13** |
| `:where(...)` | `http.css:21` (specificity 0, always loses) | `--accent` `#20c997` | 2.13 |
| builder | `builder.css:18,19,126,127,170` | `#93c5fd` | 1.80 on white / 10.2 on dark |
| `.xp-pickerBtn`, `.xp-keyInput`, `.xp-search`… | `factory.css:253,261,267,272` | `border #4f46e5` + `outline 2px #c7d2fe` | border OK |

`[H]` Keyboard-focus ring measured in the Admin CSS set (HARNESS, NOT REAL BACKEND): `.tabs button`, `.kpiButton`, `.xp-chev`, `.linkButton`, `.xp-advBtn`, `.xp-clear`, `.smallButton` → `solid 2px rgb(103,184,255)` = **2.13:1 on white** (fails the 3:1 of WCAG 1.4.11); `.btn`, `.xp-iconBtn`, `.navLink`, links, switch → `rgb(79,70,229)` (OK); inputs → `rgb(199,210,254)` ring **1.49:1** with a 1 px `#4f46e5` border (acceptable for text fields, **not** for `input[type=checkbox|radio]`, whose only indicator is that 1.49:1 ring: `p-cb → solid 2px rgb(199,210,254) off=0px`). Four focus colours for one product → S3-008.

### A13. Icons [C]

Lucide only (`packages/ui/src/icons.ts`, 91 icons, tree-shaken, ISC) + 3 brand marks from Simple Icons via `ProviderLogo` + one inline SVG set (`DeviceIcon`, `drawers.tsx:22-27`). The glyph-as-icon problem (UI-01) is fixed except these remaining glyphs in the UI text: `⋮⋮` drag handles (`Canvas.tsx:65`, `ComponentsPanel.tsx:49`), `↑`/`↓` in button labels (`Inspector.tsx:46-47`, `Gửi ↑` `ProjectWorkspace.tsx:351`, `CodeWorkspace.tsx:173`), `→` as a separator in 24 sentences, `⋯` in the diff (`CodeWorkspace.tsx:40`), `•` dirty marker (`CodeWorkspace.tsx:179`), `<code>` arrows. Icon sizes 12/13/14/16/18/20/22/28/44 px. Icon-only buttons all carry `aria-label` (checked in `PagesPanel`, `Inspector`, `Canvas`, `TenantScreens`).

### A14. Density [M]

Light shell: 14 px body, row height ≈ 41 px (`td` 10 px padding), controls 36 px, `.btn.sm` 30 px — a comfortable "operations console". Dark editor: 12–13 px text, 28–34 px controls, 11 px chips — dense and ≥ 24 px targets only because of the 28 px minima. The two densities meet inside the Admin `DataSourcesPanel` (13 px dark small buttons on a 14 px light card, S3-001) and in the Studio shell → project jump. No density token/mode exists.

### A15. Dead and duplicated CSS [M]

* **Dead (no user in any component):** ≈ **96 rules, 7.4 KB (8 % of 92 KB)**: the marketing-site mock in `globals.css` (`.hero .siteNav .siteLogo .siteMenu .productGrid .productCard .compareCard .quoteCard .testimonialGrid .contactLayout .formGrid .fakeInput .siteFooter .machine .ctaRow …`, 46 rules / 3.4 KB, 35 % of the file), v1 pages in `http.css` (`.authCard .listPage .projectList .itemEditor .plainArea .ssoButton .emptyState .outline .inspectorHead .inspectorActions .moveButtons .paneTabs`, 29 rules / 2.3 KB, 31 % of the file), and `factory.css` leftovers (`.designLeft .inspectorPane .paneSection .sortRow .dragHandle .libList .usageMeta .inspectorTools`, 21 rules / 1.7 KB). Legacy `.pane-conversation/.pane-preview/.mobilePaneTabs/.mobileMore*` are used only by `components/StudioShell.tsx` (legacy root app).
* **Used but undefined (no CSS anywhere):** `.stack` (10 uses: `ProvisioningScreens.tsx:72,81,98,107,148,178`, `EmployeesScreens.tsx:120`, `OrganizationScreens.tsx:168`, `TenantScreens.tsx:94,307`), `bx-h3`/`bx-h4` outside the builder (8 uses), `center` (`AuthPages.tsx:109`), `sr-only` (`TestPanel.tsx:212`), `bx-table`, `xp-empCard`, `xp-orgTreeCard`, `xp-orgDetailCard`, `codeMsg`, `buildInfo`, `studio-shell`, `bx-ds`, `bx-ds-item`, `bx-data`, `bx-existing`, `bx-test-wf` (the last ten are hooks with no styling: harmless but undocumented).
* **Selectors defined more than once: 93; with conflicting declarations: 40** (script `dups.mjs`). The load-order-dependent ones that change what the user sees:
  * `.pickList` — `factory.css:142` (`display:grid; max-height:280px; overflow:auto`) vs `237` (`display:flex; flex-wrap:wrap`): the Studio "Bắt đầu từ" template chooser (`StudioApp.tsx:214`, `fieldset.pickList`) and the AI override finder (`AiSetup.tsx:327`) get the **later flex** layout, so rows meant to stack become wrapping chips.
  * `.studio` — `globals.css` `100vh` / `58px 1fr` vs `responsive.css:2-3` `100dvh` / `58px minmax(0,1fr)`; `@media(max-width:900px){.studio{height:auto}}` in globals is **overridden** by the later `responsive.css` base rule, so the "page scrolls on tablets" intent is dead.
  * `.bx-btn` 34 → 28 px (`builder.css:21` vs `130`), `.bx-mini` 24 → 28, `.bx-tabs button` colour/active background (4 declarations), `.bx-body` columns (3 declarations, `builder.css:7`, `145`), `.shell input[type=checkbox]` 16 → 24 px (`factory.css:363` vs `396`), `.modalBody` padding/`max-height` (`230` vs `380`), `.hint` colour (`http.css:11` `--muted` vs `factory.css:58` `--f-muted`; correct only because factory loads last), `.formError` colour (`!important` in both).
  * `.checklist` (`factory.css:233`) vs `.checkList` (`factory.css:138`, `236`): two classes differing only in case, used by different components (`AdminApp.tsx:221` vs `library.tsx:37`).

### A16. Z-index and stacking [M]

Declared: `.modalBody .xp-footer 2` · `.topbar 30` (sticky ≤ 900) · `.bx-mview 45` (sticky phone switch) · `.adminModal 50` · `.overlay 50` · `.bx-skip 60` · `.sideBackdrop 65` · `.shell>.sidebar 70` · `.xp-pickerList 70` · `.toast 70` · `.bx-overlay 80`. No scale, but the collisions that matter: `.toast` (70) sits **below** the builder `Dialog` (80), so a notice raised by a save inside a Dialog flow is hidden until it closes; `.toast` (70) sits **above** drawers/modals (50) at `right:20px;bottom:20px`, i.e. exactly over the primary action row of `.drawerActions` / `.modalActions` (S3-006); the drawer sidebar (70) equals the picker list (70) — harmless today because a modal cannot be open while the drawer is. `.adminModal` (50) and `.overlay` (50) are two different overlay components at the same level.

### A17. Motion and reduced motion [M][C]

Inventory (all short, none essential): `spin .8s` spinner (`globals`), `xp-rot 1s` (`.xp-spin`), `dot 1s` typing dots (`http.css:38`), `.canvas{transition:.2s}`, sidebar drawer `.18s`, switch `.15s`, knob `.15s`, chevron `.12s`, `.btn` `.12s`, `.bx-handle` `.12s`. **Reduced motion: PASS** — a global block (`responsive.css:279-287`: `animation-duration:.01ms !important; animation-iteration-count:1 !important; transition-duration:.01ms !important; scroll-behavior:auto`) is loaded by all three apps; in addition 7 component-level `prefers-reduced-motion` blocks (`factory.css:250,293,328,350,375`, `http.css:40`, `builder.css:92`) which are now redundant. Not handled: `prefers-contrast`, `forced-colors`, `prefers-color-scheme`, `color-scheme` (0 occurrences in all five files): in forced-colors the switch track/knob and the `.bars`/`.budgetBar` fills (background-only) disappear; the dark editor does not declare `color-scheme:dark`, so native scrollbars / date pickers inside it follow the OS light theme (S3-042).


---

## B. POPUP BEHAVIOUR AUDIT (every shared overlay primitive)

Inventory of overlay primitives (there is **no** popover, tooltip component or menu: tooltips are the native `title=` attribute — 99 uses; `<details>` 5 uses; `mobileMore` menu exists only in the legacy `components/StudioShell.tsx`):

| # | Primitive | File | Used by | Rendering |
|---|---|---|---|---|
| P1 | `Modal` (+`ModalHeader`) | `packages/ui/src/Modal.tsx` | 14 admin/platform dialogs (`CreateTenantDialog`, org/unit/move/delete/types dialogs, employee detail, provider dialog, model picker, override dialog, `LinkBox`, create-account) | portal to `document.body`, `.adminModal` (light, z 50) |
| P2 | builder `Dialog` | `features/studio/builder/ui/primitives.tsx:28` | 12 builder confirmations/forms (+ the Admin data-sources panel) | in place (not portaled), `.bx-overlay` (dark, z 80) |
| P3 | `useDialog` + `Drawer` | `packages/ui/src/useDialog.ts`, `features/studio/drawers.tsx:29` | 10 Studio drawers (settings, assets, members, versions, site, packages, IDE, runtime, save-as-block…) and `PublishModal` | in place, `.overlay` (z 50) + `.drawer`/`.modal` |
| P4 | `Picker` listbox | `packages/ui/src/Picker.tsx` | 2 (provider kind, unit type) | absolute list inside the form (`.xp-pickerList`, z 70) |
| P5 | `PersonPicker` combobox | `features/admin/PersonPicker.tsx` | tenant first-admin | inline list |
| P6 | `NavDrawer` (`useNavDrawer`, `MenuButton`) | `packages/ui/src/NavDrawer.tsx` + `factory.css:338-350` | the 3 shells ≤ 900 px | off-canvas sidebar + backdrop |
| P7 | toast | `ProjectWorkspace.tsx:375`, `CodeWorkspace.tsx:240` + `.toast` (globals) | Studio project | `<button class="toast">` |
| P8 | native `window.confirm / prompt` | 34 call sites | Admin, Studio | browser |
| P9 | `Switch` | `packages/ui/src/Switch.tsx` | 2 | inline (control, listed for completeness) |
| P10 | `ScrollRegion` | `packages/ui/src/ScrollRegion.tsx` | every `Card` | not an overlay; listed because it owns a focus stop |

Legend for results: **PASS**, **FAIL**, **PARTIAL**, **n/a**. `[H]` = HARNESS, NOT REAL BACKEND (Chromium).

### B1. `Modal` (admin / platform dialogs)

| Check | Result | Evidence |
|---|---|---|
| Open / close | PASS | portal, `Modal.tsx:32`; opener restored on close `[H]` (`afterEsc2.openerIsActive: true`) |
| Escape | PASS | document `keydown` `Modal.tsx:19,27`; `[H]` with a `Picker` open, **first** Esc closes only the picker (`afterEsc1: dialogOpen 1, pickerOpen 0`), the second closes the dialog (`Picker.tsx:27` stops propagation) |
| Focus moves in on open | **FAIL** when the first control is a `Picker` | `Modal.tsx:16` focuses the first match of `input:not([readonly]), select, textarea, button`; `Picker.tsx:37` renders a visually hidden **native `<select class="srOnly" tabindex=-1>` before** the combobox button, so `[H]` the provider dialog opens with focus on that invisible select (`modal_initialFocus: SELECT.srOnly tabindex=-1`): sighted keyboard users see no focus (S3-010) |
| Focus moves in on open (other dialogs) | PASS / PARTIAL | first input (forms), "Hủy" (delete dialogs). **PARTIAL for `LinkBox`**: first button is **"Xong"** (`UserDialogs.tsx:24`), and Escape/Xong discard the one-time activation link for good with no confirmation (S3-033) |
| Focus trap | PASS with a defect | Tab/Shift+Tab wrap, also if focus is outside (`Modal.tsx:21-25`). **Defect `[H]`:** the hidden native `<select>` is counted as focusable (`FOCUSABLE` has `select:not([disabled])` with no `tabindex=-1` exclusion), so wrapping lands on it: tab sequence `…BUTTON.btn → SELECT.srOnly[tabindex=-1] → BUTTON.xp-pickerBtn` — one invisible Tab stop per cycle |
| Focus restore | PASS `[H]` | `Modal.tsx:28`; fails only if the opener was unmounted meanwhile |
| Scroll lock + restore of the previous value | PASS (single dialog) / FAIL (nested) | `Modal.tsx:17,28` saves and restores `body.style.overflow` (`[H]` `"" → "hidden" → ""`). The lock is **moot** — `body{overflow:hidden}` is already set by CSS at every width (`globals.css`, `responsive.css:99`; `[H]` `bodyOverflowBefore.computed: hidden`) and the page scrolls inside `.page`, which the portaled overlay cannot chain to. Nested modals would restore in the wrong order (`overflow` saved as `"hidden"` by the second modal, restored last) — no nested modal exists today (latent, S3-035) |
| Nested modals | n/a (latent FAIL) | Escape is a document listener per instance → two open modals would **both** close; no z-order/`aria-modal` stack |
| Mobile fit | PASS `[H]` | 360×640: dialog 344×608, footer visible, no horizontal scroll; `max-height:calc(100dvh - 32px)`, sticky `.xp-footer`, bottom-sheet ≤ 600 px (`factory.css:380-382`) |
| Long content | PASS | body scrolls inside, footer sticky, `overscroll-behavior:contain`. **P3 observation `[H]`:** at 1280×420 the focused field can sit 32 px under the sticky footer (`INPUT overlapPx 32`, not fully hidden → WCAG 2.4.11 AA passes, 2.4.12 AAA fails; no `scroll-padding-bottom` on `.modalBody`) |
| Error / loading / disabled | PASS | submit buttons `disabled={busy}`, `aria-busy`; errors `role=alert` |
| Double submit | PASS (code) | `disabled={busy}`; an implicit Enter-submit is ignored when the default button is disabled; guard also in handlers where present |
| Keyboard nav | PASS | Tab/Shift+Tab/Esc; Enter submits forms |
| Accessible name | PASS | `aria-label={label}` (`Modal.tsx:32`) — not `aria-labelledby`, so the visible `<h2>` and the name can drift (e.g. `CreateAccountDialog` uses `title` for both, others pass a different label) |
| `aria-modal` / inert background | PARTIAL | `aria-modal="true"` present; **no `inert`/`aria-hidden` on the rest of the page** `[H]` (`rootInert:false, anyInert:false`), so a screen-reader virtual cursor that ignores `aria-modal` can read the page behind |
| Backdrop click | n/a | does not close `[H]` (`backdropClickClosesAdminModal:false`) — intentional for forms |
| Stale `onClose` | latent FAIL | the key listener is created once (`useEffect(…, [])`, `Modal.tsx:13-30`) and captures the first `onClose`; a parent that passes a closure over changing state would close with stale data (all current callers pass stable setters) |

### B2. Builder `Dialog` (`ui/primitives.tsx:28-54`)

| Check | Result | Evidence |
|---|---|---|
| Open / close, focus restore | PASS `[H]` | `[data-autofocus]` or first focusable; destructive confirmations focus **"Hủy"** first (`dialog_initialFocus: Hủy`); opener refocused (`dialog_focusRestored: Xóa`) |
| Escape | **FAIL** when focus is not inside | `onKeyDown` is on the dialog `<div>` (`:36-38`): `[H]` after a click on the dialog title (focus falls to `<body>`: `afterClick: BODY`), Escape does **nothing** (`escFromBodyClosesBuilderDialog:false`). Mouse users who click inside the text then press Esc cannot dismiss |
| Focus trap | PASS `[H]` | wraps on first/last (`:39-43`); Tab from the body after a click lands back inside (browser sequential-focus start point) |
| Scroll lock | FAIL (none) | no `overflow` handling; because it is **not portaled** it sits inside scrollable panels (`.bx-left-panel`, `.bx-right`), so wheel events over the backdrop can scroll the panel behind |
| Backdrop | PASS/PARTIAL `[H]` | `onMouseDown` on the backdrop closes (`:46`, `dialog_backdropClickCloses:true`) — fine for confirmations, **data loss** for the form dialogs (`AddDialog`, `RenameDialog`, slot edit) |
| Stacking | FAIL (minor) | z 80 > toast 70 |
| Mobile fit | PASS `[H]` | 360 px: 328×238, `width:min(520px,100%)`, `max-height:90vh` |
| Standalone styling | **FAIL** | depends on `builder.css`; in Platform / Admin it renders **inline in the page flow with no overlay, no box, white-on-white buttons** (`[H]` `dlgPos: static`, `overlayPos: static`, `overlayH 140`, footer `button primary` = white on white) — S3-001 |
| aria-modal / labelled / inert | PARTIAL | `aria-modal` + `aria-labelledby` (h2) present; no inert |

### B3. `useDialog` / `Drawer` / `PublishModal` (`.overlay`)

| Check | Result | Evidence |
|---|---|---|
| Escape | PASS | document **capture** listener (`useDialog.ts:32`), works from anywhere; `PublishModal` passes `null` while a deployment/rollback runs so Esc cannot interrupt (`ReleaseModal.tsx:86`) |
| Focus in / trap / restore | PASS | `useDialog.ts:17-34` (`getClientRects` filter, wrap, opener refocus). First focus is the drawer's close "Đóng" button; in `PublishModal` it is the first control (`[H]` `active: INPUT` = the public-data acknowledgement) |
| Scroll lock | FAIL (none) | no lock in `useDialog`; background is not scrollable only because the portal-less overlay is `position:fixed` over a body that is `overflow:hidden` |
| Inert background | FAIL | none |
| Backdrop | PASS | `Drawer` closes on mousedown (`drawers.tsx:32`); `PublishModal` has no backdrop close (correct for an operation dialog) |
| Mobile fit | PASS | `.drawer{width:min(480px,100%);max-height:100dvh}`, `.modal{max-height:calc(100dvh - 32px)}` (`responsive.css:72-80`) |
| Selection semantics | **FAIL** | `PublishModal` visibility choice = two `<button class="publishChoice selected">` with **no `aria-pressed` / radio role** `[H]` (`ariaPressed:null, role:null`): the chosen option is conveyed by colour only (`ReleaseModal.tsx:186-188`, S3-015) |
| Double submit / busy | PASS | `locked`, `submitting`, `pending`, idempotency-key book |

### B4. `Picker` (`packages/ui/src/Picker.tsx`) and `PersonPicker`

| Check | Result | Evidence |
|---|---|---|
| Open/close, Esc, outside click, Tab | PASS `[H]` | `Picker.tsx:18-20,27,31`; Esc returns focus to the button |
| Keyboard (↑ ↓ Home End Enter Space, type-ahead) | PASS | `Picker.tsx:24-33` (type-ahead by first letter only; skips disabled) |
| ARIA | **FAIL** | the combobox (the focused `button`, `role=combobox`, `:38`) carries `aria-expanded/-controls`, but **`aria-activedescendant` is on the `listbox`** (`:42`), not on the focused combobox `[H]` (`picker_ariaActive: "_r_1_-o0"` on the list while focus stays on the button): assistive tech never announces the active option (S3-011) |
| Hidden native `<select>` | **FAIL** | gets initial focus in a `Modal` and one Tab stop per cycle (B1) |
| List inside a scrolling dialog | PASS `[H]` | `listOverflowsModal:false`; the list grows the dialog's scroll area |
| `PersonPicker` | PARTIAL | `role=combobox` with constant `aria-expanded="true"` (`:34`), options `aria-selected={i===at}` marks the *active* row as *selected* (`:41`); Esc clears search (`:29`) |

### B5. `NavDrawer` (mobile navigation, `NavDrawer.tsx`, `factory.css:338-350`)

| Check | Result | Evidence |
|---|---|---|
| Open / close, Esc, backdrop, close on navigation | PASS (code) | `NavDrawer.tsx:12-18`; Esc refocuses the menu button |
| Focus moves into the drawer on open | **FAIL** | nothing moves focus; no `aria-modal`/`role=dialog`/focus trap; page behind stays interactive |
| Closed drawer removed from tab order / a11y tree | **FAIL** `[H]` | at ≤ 900 px the closed sidebar is `position:fixed; transform:translateX(-102%)` and still `visibility:visible`, `display:flex`, not `inert`/`aria-hidden`: the **first Tab press focuses the off-screen "Mục 1" link at x = −294…−19** at 360, 600, 800 and 900 px (`firstTabFocus: A:Mục 1 @x=-294..-19`) — S3-002 |
| Scroll lock | n/a | body already `overflow:hidden` |
| `aria-controls` / `aria-expanded` on `MenuButton` | PASS | `NavDrawer.tsx:23` |

### B6. Toast (`.toast`)

| Check | Result | Evidence |
|---|---|---|
| Announced | **FAIL** | `<button className="toast">` (`ProjectWorkspace.tsx:375`, `CodeWorkspace.tsx:240`) has **no `role="status"/"alert"`/`aria-live`**; it is inserted into the DOM already containing its text, which screen readers do not announce (WCAG 4.1.3). It carries the **most important** feedback of the workspace: revision conflict, AI token limit, "Đã lưu cài đặt", restore results (`ProjectWorkspace.tsx:129-139,199,203`) |
| Dismiss | FAIL | only a click on the toast; no timeout, no Esc, no close affordance; a later notice silently replaces the earlier one (single `notice` state) |
| Position | FAIL (minor) | fixed `right:20px; bottom:20px; z-index:70` (globals): over `.drawerActions` / `.modalActions` / composer send button |
| Name | PARTIAL | the button's name is the whole message; no "đóng" hint |

### B7. Native dialogs, switch, scroll region, tooltips

| Item | Result | Evidence |
|---|---|---|
| `window.confirm/prompt` (34 sites) | PARTIAL | native → accessible and robust, but unstyled, untranslatable, un-themed, blocked in some embedded contexts, and 4 sites use `window.prompt` to collect **required** input (`AdminApp.tsx:686` rejection reason, `:816` risk-acceptance note, `:1081` rename; `CodeWorkspace.tsx:122`) with no validation message and no a11y affordances (S3-017) |
| `Switch` | PASS | `role=switch`, `aria-checked`, label by `htmlFor`, 42×24 target, reduced-motion |
| `ScrollRegion` | PASS | focusable only while it scrolls, named by the card heading (`ScrollRegion.tsx:12-16`) |
| `title=` tooltips (99) | **FAIL** as the only carrier | 33 of them are the **only** explanation of why a control is disabled (e.g. `BuilderTopBar.tsx:37-38`, `ProjectWorkspace.tsx:299`, `ReleaseModal.tsx:212-223`, `EmployeesScreens.tsx:164`, `DataSourcesPanel.tsx:151-153`): disabled buttons are not focusable, titles do not show on touch, and screen readers often skip them (S3-016) |

### B8. Scoreboard

Checks marked in B1–B7: **53** — **PASS 24, PARTIAL 10, FAIL 16, n/a 3.** The FAIL rows map to S3-001, -002, -006, -010, -011, -012, -015, -016, -017 and -034 (scroll lock / inert background of `useDialog` and builder `Dialog`).

---

## C. ACCESSIBILITY AUDIT BY CODE

### C1. Heading hierarchy

Rules in use: `PageHead` / `.pageHead` render the single `<h1>` (17 `h1`, 31 `h2`, 58 `h3`, 7 `h4` in `features/ packages/`); `Card` titles are `h2`; `StateView level={1|2}` and `NotReadyPanel level` / `DataSourcesPanel headingLevel` exist precisely to avoid skipped levels (good). Builder pages use `role="heading" aria-level="1"` on the project name (`BuilderTopBar.tsx:19`). Gaps (all `[C]`):

* `CodeWorkspace` has **no h1** at all (`.projectName` is a `div`; only `h2 "Thay đổi"`), AI mode in `ProjectWorkspace` has an `h1` **only while the conversation is empty** (`:321`) → no h1 after the first message (S3-036).
* `Splash` (`PortalApp.tsx:19`) and every `StateView kind="loading"` default to `h2` with no `h1` (S3-036).
* Section-in-section: `BlockCard` renders `<h2>` (`StudioApp.tsx:312`) inside a `Card` whose title is already the `<h2>` (`:355`, `:360`), `BlockReviewPanel` uses `<h2 class="subHead">` inside a table row (`AdminApp.tsx:641-642`), `AdminApp.tsx:951` `h3` after `h2` OK.
* `Kpi` values are not headings (fine).

### C2. Landmarks

Platform / Admin / Studio shell: `aside.sidebar[aria-label]` (+ unlabeled `nav` inside), `header.topHeader`, `main#main.page[tabindex=0]`, `div.sideBackdrop[aria-hidden]`. Builder: `header.topbar[aria-label]`, `main.bx-body` containing **`aside` landmarks** (`BuilderWorkspace.tsx:195,201` → axe "landmark-complementary-is-top-level"), `section[aria-label]` canvas. Auth pages: `<main class="authPage">`. Gaps: (a) **no skip link** in the three shells (only the builder has `bx-skip`, `BuilderWorkspace.tsx:190`); landmarks mitigate (ARIA11) but a keyboard user crosses 19 sidebar links on every page; (b) `main` is a tab stop (`tabIndex={0}`) even when it does not scroll (`AdminApp.tsx:68`, `StudioApp.tsx:42`) — conflicts with `ScrollRegion`'s "only while it overflows" rule; (c) `<nav>` has no accessible name distinct from the `aside` (single nav, acceptable); (d) `Splash`/`wsError` have no landmark (S3-038).

### C3. Contrast of every token pair (WCAG 2.x) [M]

Method: the values in `factory.css`, `globals.css`, `http.css`, `builder.css`, `responsive.css` are converted to sRGB; `rgba` backgrounds are composited over the real parent; relative luminance and `(L1+0.05)/(L2+0.05)`; thresholds 4.5 : 1 (text < 18.66 px bold / 24 px), 3 : 1 (non-text UI, focus). **115 pairs computed; the full list is Appendix B.** Summary:

| Idiom | Text pairs | Text FAIL | Non-text pairs | Non-text FAIL |
|---|---:|---:|---:|---:|
| L (light, 52 pairs) | 36 | **0** real (2 flagged: disabled ink 3.47 and disabled primary 2.22 — disabled controls are exempt) | 16 | **13** |
| D (dark, 63 pairs) | 52 | **0** real (1 flagged: disabled send button 3.58, exempt) | 11 | **5** |

Text contrast is **good**: smallest passing values `--f-placeholder` 4.95, `composerFooter #758193` 4.68 (11 px), `.sendButton` text on the blue end of its gradient 4.95, `.bx-badge.warn` 5.43, `.pill-ok` 5.40. **Non-text contrast (WCAG 1.4.11) is where the system fails:**

| Pair | Ratio | Where |
|---|---:|---|
| input / select / textarea border `#e4e7ec` on white | **1.24** | every light control (`factory.css:26,362`); dark `#3a4655` on `#161a21` 1.82, `#2d3742` on `#121a22` 1.45 |
| `.btn` border `#e4e7ec` on white, `.btn.danger` border `#fecdca` | 1.24 / 1.42 | ghost-like outlined buttons (text carries the identity, so only the boundary fails) |
| `.xp-switch` track `#cbd5e1` on white; knob white on that track | **1.48 / 1.48** | the *off* state of every switch (`factory.css:247-249`) |
| card border `#e4e7ec` on `#f5f6f8` | 1.15 | `.card`, `.kpi` (decorative boundary) |
| `.budgetBar` fill `#20c997` / `#f5a524` on its track | 1.46 / 1.40 | budget bars (value also printed as text) |
| `.healthCard` left border `#98a2b3` / `#12b76a` / `#f79009` | 2.58 / 2.62 / 2.35 | status accent beside a text pill |
| `#c7d2fe` focus ring on white (inputs, checkboxes) | **1.49** | `factory.css:324,367` (border change rescues text fields; checkboxes/radios have no border change) |
| `#67b8ff` focus ring on white (default for any control without its own rule) | **2.13** | `responsive.css:62-70`, `[H]` confirmed on 7 control types |
| `.bx-outcome.tone-bad` border `#b42318` on `#2a1411`, `.bx-menu li.broken` | 2.65 / 2.80 | builder status boxes |

→ S3-008 (focus ring) and S3-009 (control boundaries).

### C4. Table semantics

See A8: 40 tables, 2 captions, 3 `scope`; header cells are `th` in `thead` (implicit col scope); row-header tables use `th scope=row` correctly only in `UserAiCard` (`AiSetup.tsx:354-359`); action columns carry a visually hidden `<th><span class="srOnly">Thao tác</span></th>` (good). `.xp-empTable` mobile card layout removes table semantics in some browsers. Clickable rows: `tr.clickRow` + `onClick` (`AdminApp.tsx:168,259,332,366`, `TenantScreens.tsx:152`) with **no key handling** except `EmployeesScreens.tsx:86` (`tabIndex=0` + Enter); in `AuditTable` the click **expands the detail row** and has no other path → the audit detail (old/new JSON) is unreachable by keyboard (S3-014).

### C5. Labels, descriptions, errors

* Inputs: 175 `<input` — all visible-label or `aria-label`ed in the portals (the earlier axe runs report 0 unlabeled). `placeholder`-only labels do not occur.
* Descriptions: `hint` text is rarely wired (`aria-describedby` only in the dialogs listed in A7; `Field` primitive in the builder never wires `hint` to the control — `primitives.tsx:86-89` renders `<small>` after the control with no id).
* Errors: see A7 — only the dialogs listed there associate the message with its field (`aria-invalid` + `aria-describedby`); every other form (`AdminApp`, `AiSetup`, `StudioApp`, `drawers`, `SitePanels`, `CodePanels`, `DataSourcesPanel`, `DataWizard`, `ActionEditor`) shows a free `role=alert` paragraph.
* Label-in-name: `Picker` button `aria-label` = "Nhà cung cấp: OpenRouter. <hint>" (starts with the visible label — OK); `CreateAccountDialog` `fieldset aria-label="Thông tin"` vs visible legend "1 · Thông tin" (name overrides legend; not an interactive control).
* Required: `required` attribute used inconsistently; optional fields are marked "(không bắt buộc)" instead.

### C6. Live regions

82 `role="alert"`, 41 `role="status"`, 8 `aria-live`. Problems: (a) **toast has none** (B6); (b) `AiProgress` headline is a `role=status aria-live=polite` region whose text changes **on every streamed chunk** ("Đang nhận câu trả lời… 123 ký tự", `AiProgress.tsx:13`, `aiProgressModel.ts:54`) → screen-reader chatter for the entire duration (S3-023); (c) whole-page `forbidden/notfound/conflict` states are `role=alert` (`ui.tsx:39`) → announced assertively on every navigation to such a page; (d) `BuilderTopBar` save state `role=status aria-live=polite` is correct; (e) the `copied` feedback of `LinkBox` (`UserDialogs.tsx:25`) is a button-text change, not announced.

### C7. Target size (WCAG 2.5.8, 24 × 24)

PASS overall: `.btn.sm` 30, `.bx-btn` 28, `.bx-mini` 28, `.chipBtn` 24, `.xp-clear` 24, `.xp-iconBtn` 40, checkboxes/radios 24 in `.shell`, `.adminModal`, `.bx-right`, `.bx-left-panel`, `.drawer` (`factory.css:396`, `builder.css:172`), switch 42×24; coarse pointers get 32–44 (`builder.css:132-136`). Exceptions: `.xp-chev` 22 × 22 (decorative, mouse-only twin of the keyboard arrows, `aria-hidden`), native checkboxes in `.modal`/`.codeLeft` are 13 × 13 `[H]` (publish acknowledgement) — **exempt** as an unmodified user-agent control but inconsistent with the 24 px elsewhere.

### C8. Focus visibility and sticky headers (WCAG 2.4.7 / 2.4.11)

* Ring colours: see A12 — plain buttons/tabs/chevrons/KPI buttons in the light shell are **2.13 : 1**; checkbox/radio ring **1.49 : 1** `[H]` (S3-008).
* Sticky elements that can cover the focus: `.bx-mview` (phone switch, handled by `scroll-padding-top:64px`, `builder.css:203`), `.xp-footer` in dialogs (partially covers by 32 px at very short viewports `[H]`, no `scroll-padding-bottom`), `.topbar` sticky ≤ 900 (legacy shell only). No sticky table headers. 2.4.11 AA: PASS (never fully hidden in the cases tested); 2.4.12 AAA: not met.
* `main:focus{outline:none}`, `.page:focus-visible{outline:2px solid --f-accent; outline-offset:-2px}` (`factory.css:121`): fine.
* **Keyboard trap (WCAG 2.1.2 A): `CodeWorkspace.tsx:185`** — the code editor `<textarea>` handles `Tab` with `preventDefault()` and inserts two spaces **for both Tab and Shift+Tab**; there is no documented/implemented escape key (Esc), so a keyboard-only user who enters the editor cannot leave it with the keyboard (S3-005).

### C9. Reduced motion, forced colors, contrast preferences

Reduced motion: PASS (A17). `forced-colors` / `prefers-contrast`: **none** (A17). `<html lang="vi">` set in all four layouts (PASS). Page titles per route in Admin/Studio (`AdminApp.tsx:61`, `StudioApp.tsx:28`), project name for projects; auth pages keep the layout default.

### C10. Zoom and reflow at 320 px / 400 % (WCAG 1.4.4 / 1.4.10)

* 400 % zoom of a 1280 px window = 320 CSS px. `[H]` **FAIL** on the light shell: `.grid2{grid-template-columns:repeat(auto-fit,minmax(380px,1fr))}` (`factory.css:41`) forces cards **380 px wide** in a 292–362 px container: `main.page` scrolls sideways (`pageScrollW 394 > clientW 320/360/390`), cards overflow by 34–88 px. The document itself does not overflow (`docScrollW = vw`), which is why the previous C5 "page wider than the phone = 0" check did not catch it. Pages affected: Overview, user/workspace/application detail, AI limits form, template review, any `grid2` (S3-003).
* Tables scroll inside their card (allowed for data tables); auth pages `min(440px,100%)` PASS; dialogs 344 px PASS `[H]`.
* Text is in px but zoom/`text-spacing` work (no fixed heights on text containers except `.xp-nodeText` ellipsis and `.bx-node.section b` 2-line clamp).
* Builder at 320: phone switch ≤ 760 px (see D).
* Not verified: browser text-only zoom 200 %, iOS Safari `100vh` behaviour (`factory.css:7-8` uses `100vh` for `.shell`/`.shellMain`; `responsive.css` uses `dvh` for the Studio) — S3-021.

---

## D. RESPONSIVE AUDIT (CSS reading + `[H]`/`[R]` measurements)

### D1. Inventory of every `@media` (34 blocks, 14 distinct thresholds)

| # | File:line | Query | What it does |
|---|---|---|---|
| 1 | `globals.css` (single line) | `max-width:900px` | v1 Studio: `body{overflow:auto}`, `.studio{height:auto}`, `.workspace` 1 column, `.promptPane min-height:56vh`, `.previewPane min-height:85vh`, `.topbar` sticky z 30, `.projectMeta` hidden. **Mostly neutralised**: `responsive.css` loads later and re-sets `.studio{height:100dvh}`, `body{overflow:hidden}` (≤ 1023) |
| 2 | `responsive.css:82` | `min-width:1920px` | legacy `.workspace` columns `minmax(320px,480px) 1fr` |
| 3 | `responsive.css:88` | `max-width:1279px and min-width:1024px` | legacy `.workspace` 320–360 px column; hides `.environmentBadge` |
| 4 | `responsive.css:98` | `max-width:1023px` | `body{overflow:hidden}`, `.studio` rows `56px 48px 1fr`, `.mobilePaneTabs` (legacy shell only), single column, hides one pane, canvas padding 8 |
| 5 | `responsive.css:157` | `max-width:767px` | compact top bar; hides `.projectMeta`, **`.topActions>.button.ghost` and `.button.icon`**, toolbar label/badge/small buttons; `.mobileMore` menu (**not rendered by the real Studio**); composer textarea `max-height:25dvh` |
| 6 | `responsive.css:279` | `prefers-reduced-motion` | global kill-switch (PASS) |
| 7 | `http.css:22` | `max-width:700px` | `.itemEditor` 1 col (dead class) |
| 8 | `http.css:40` | `prefers-reduced-motion` | `.dots i` |
| 9 | `factory.css:117` | `max-width:1100px` | `.wsBody.mode-design` 2 columns (legacy design layout, superseded by `bx-*`) |
| 10 | `factory.css:118` | `max-width:900px` | `.shell` 1 col, `.sidebar{display:none}` (overridden by #16), `.wsBody` 1 col, `.portalPick` 1 col, `.workspace3 .topbar` 1 col |
| 11 | `factory.css:193` | `max-width:900px` | code studio single column, file tree 160 px |
| 12 | `factory.css:223` | `max-width:700px` | `.navRow` 2 columns (Site drawer navigation editor) |
| 13 | `factory.css:250,293,328,350,375` | `prefers-reduced-motion` ×5 | component-level (redundant with #6) |
| 14 | `factory.css:316` | `max-width:900px` | `.xp-orgGrid` 1 col, `.xp-empFilters` 2 col |
| 15 | `factory.css:317` | `max-width:720px` | employee table → cards, filters 1 col |
| 16 | `factory.css:340` | `max-width:900px` | **drawer navigation** (fixed sidebar, backdrop), header/page/pageHead paddings |
| 17 | `factory.css:382` | `max-width:600px` | dialogs: bottom sheet, `--mpad:16px`, footer wraps |
| 18 | `factory.css:385` | `max-width:520px` | header may wrap to 2 rows, breadcrumb hidden |
| 19 | `factory.css:388` | `max-width:900px` | `.shell` 1 col `minmax(0,1fr)` |
| 20 | `builder.css:90` | `max-width:1100px` | `.bx-body` 2 columns (superseded by #26) |
| 21 | `builder.css:91` | `max-width:760px` | `.bx-body` 1 col, rail as a row |
| 22 | `builder.css:92` | `prefers-reduced-motion` | `.bx-handle` |
| 23 | `builder.css:113` | `max-width:700px` | project name `max-width:42vw` |
| 24 | `builder.css:116` | `min-width:1760px` | device buttons show their text label |
| 25 | `builder.css:132` | `max-width:768px, (pointer:coarse)` | 32–36 px targets |
| 26 | `builder.css:147` | `max-width:1100px` | `.bx-body` 2 columns, rail → tab row, inspector under the canvas |
| 27 | `builder.css:153` | `max-width:760px` | phone toolbar rows |
| 28 | `builder.css:178` | `max-width:900px` | AI/Code header `height:auto`, actions wrap |
| 29 | `builder.css:183` | `max-width:760px` | project name width |
| 30 | `builder.css:193` | `max-width:760px` | **phone single workspace** (sticky 3-way switch Bản xem trước / Công cụ / Thuộc tính) |

(34 `@media` occurrences in total: 8 are `prefers-reduced-motion`.)

### D2. Are the breakpoints consistent?

**No — 14 distinct width thresholds** for what is conceptually three states:

| Concept | Values used |
|---|---|
| "phone" | **520, 600, 700, 720, 760, 767, 768** (7 values; `760` builder, `767` studio top bar, `768` target size, `720` employee table, `700` nav-row / project name, `600` dialogs, `520` header) |
| "tablet / drawer nav" | **900** (shell, org grid, code studio), **1023** (legacy studio), **1100** (builder, legacy design), **1279 / 1024** (legacy workspace) |
| "wide" | **1760** (device labels), **1920** (legacy workspace) |

Consequences `[H]/[R]`: between 761 and 767 px the builder is a 2-column tablet layout but the Studio **AI/Code top bar already hides its action buttons**; between 768 and 900 the shell is drawer-navigated while the project workspace is still a 2-pane grid until 900; between 901 and 1023 the legacy `body{overflow:hidden}` rule applies. **Gaps:** nothing is defined for **≤ 360** (small phones: only the shell/`modal` rules apply), nothing between **361 and 519** except the shell header at 520, **no max-width container** at ≥ 1280 (the Admin `.page` spans the whole content area: at 1920 cards and tables are ≈ 1610 px wide, `.pageHead p` is the only measure limit at 80 ch), and no `max-width:430` rule at all (the builder phone layout starts at 760).

### D3. Behaviour per viewport

Measured results are marked; the rest is CSS reading.

| Width | Platform / Admin shell | Studio dashboard | Studio project: AI / Code mode | Builder (Design) | Dialogs |
|---|---|---|---|---|---|
| **1920** | sidebar 252 + unbounded content; `.grid2` may lay 4 columns; KPI grid up to 8 tiles; no max-width | same | `.wsBody` conversation = 34 % = 652 px; `.workspace` legacy rules unused | 3 columns 340–380 / 1fr / 300–340, canvas ≤ 1476, device labels shown (≥ 1760) | centred, ≤ 520 |
| **1440** | baseline | baseline | baseline | baseline | baseline |
| **1280** | content 1028 | — | `.wsBody` conversation 435 | 3 columns | — |
| **1024** | content 772; `.grid2` → 1 column (2 × 380 + 16 > 716); tables scroll in the card | — | 2 columns | **≤ 1100: 2 columns**, tools + canvas, inspector under them (screenshot `evidence/c6-mobile-2026-10-08/1024-builder.png`) | — |
| **768** | **drawer** nav (≤ 900); content 740; `.grid2` 1 col `[H]` 740 | — | `[R]` two stacked panes, top bar 143 px tall (3 rows), prompt 493 px, preview **264 px** | 2 columns (761–1100), toolbar wraps to 2 rows (`768-builder.png`) | — |
| **600** | drawer; page padding 16/14 | — | 3-row top bar; **secondary actions hidden (≤ 767)** | phone single-workspace switch (≤ 760) | bottom sheet (≤ 600) |
| **430** | as 600; `.grid2` ok (402 px column) `[H]` | — | `[R]` top bar 141, preview 239 px | phone switch (screenshots `430-*.png`) | bottom sheet |
| **390** | drawer; **`.grid2` cards 380 px wide in a 362 px container → `main` scrolls sideways** `[H]` (`pageScrollW 394 > clientW 390`) | — | `[R]` preview pane **211 px** (25 % of the height), no Website / Phiên bản / Tệp / Chia sẻ / Cài đặt buttons (S3-004) | phone switch, 44 px tabs | bottom sheet |
| **360** | same; overflow 34 px; header wraps (≤ 520) | — | `[R]` preview 208 px | phone switch | `[H]` 344 × 608, footer visible |
| **320** | `[H]` `main` overflow 74 px; WCAG 1.4.10 not met on `grid2` pages | — | not measured | not measured | bottom sheet; auth `min(440px,100%)` fits |

Tables: scroll inside their card at every width (`.card{overflow-x:auto}`, `ScrollRegion` makes the region focusable only while it overflows); `.xp-empTable` becomes cards ≤ 720. Sidebar: static 252 px ≥ 901; off-canvas drawer ≤ 900 (but see S3-002: still focusable). Dialogs: centred ≥ 601; bottom sheet ≤ 600; `max-height: calc(100dvh − 32px)` with sticky footer; the builder `Dialog` is `width:min(520px,100%)`, `max-height:90vh`. `body` is `overflow:hidden` at **every** width; every scroll container is an inner element (`.page`, `.card`, panes) — hence 100 vh/100 dvh matters: `.shell`/`.shellMain` use `100vh` (`factory.css:7-8`) while the Studio uses `100dvh`; on iOS Safari the `100vh` shell is taller than the visible viewport, so the last ≈ 80 px of `.page` can sit under the browser toolbar (not verified on a device, S3-021).

`[R]` AI-mode replica table (HARNESS, NOT REAL BACKEND, static markup of `ProjectWorkspace.tsx` AI mode + the real five CSS files):

| Viewport | top bar | prompt pane | preview pane | notes |
|---|---:|---:|---:|---|
| 900×800 | 143 | — | 214 | stacked, `.studio` stays `100dvh` (the `height:auto` rule of `globals` is overridden) |
| 768×900 | 143 | 493 | 264 | stacked |
| 430×900 | 141 | 520 | 239 | stacked |
| 390×844 | 141 | 492 | **211** | only "Xuất bản" visible among the actions |
| 360×740 | 141 | 391 | **208** | same |

(1440 and 1024 behave as the desktop layout: single-row top bar, two columns.)

---

## E. ISSUE TABLE (evidenced issues only; STATUS = OPEN for all)

Column key: **STATE** = condition that triggers it · **SEVERITY** P0 unusable / security-critical, P1 major, P2 significant polish / a11y / perf, P3 minor · **TEST** = proposed regression test (all browser tests are the repository's *harness* class: no backend, never counted as backend evidence) · text issues are detailed in `S3-text-and-terminology.md` (T-nnn).

**P0: none found.** (No data-loss / security-critical defect in the shared UI layer. The one-time activation link discarded by a stray key is rated P2, S3-033.)

### E.0 Counts and the ten most important issues

| Severity | Count |
|---|---:|
| P0 | 0 |
| P1 | 6 |
| P2 | 27 |
| P3 | 17 |
| **Total** | **50** |

Top 10 (ordered by user impact): **S3-001** Admin/Platform *Nguồn dữ liệu* screen renders unstyled and its delete dialog is inline with white-on-white buttons · **S3-002** closed mobile nav drawer is the first Tab stop (off-screen) at ≤ 900 px · **S3-003** every `grid2` page overflows sideways at ≤ 394 px / 400 % zoom · **S3-004** Studio AI/Code mode hides Website / Phiên bản / Tệp / Chia sẻ / Cài đặt on phones with no replacement · **S3-005** Tab/Shift+Tab are trapped in the Code editor · **S3-006** toast is a plain button: nothing is announced, never dismisses, covers the action row · **S3-008** focus ring 2.13 : 1 on tabs/KPI buttons/chevrons and 1.49 : 1 on checkboxes · **S3-009** control boundaries 1.24–1.48 : 1 (inputs, switch off, outlined buttons) · **S3-010** Modal with a `Picker` opens with focus on an invisible `<select>` and keeps it in the Tab loop · **S3-017/S3-027** 34 native `confirm/prompt` dialogs + inconsistent terminology (workspace / project / ứng dụng, xóa / xoá / gỡ).

### E.1 Table

| ID | PORTAL | ROUTE / COMPONENT | STATE | SEV | CATEGORY | EXPECTED | ACTUAL | ROOT CAUSE | OWNER | PROPOSED FIX | TEST | STATUS |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S3-001 | Platform + Admin | `/data-sources` (`TenantScreens.tsx:350` → `DataSourcesPanel` + `Dialog`) | any: list, add form, delete dialog | P1 | CSS load / design system | Looks like the console; delete is a modal over a scrim | `[H]` unstyled: UA `fieldset` borders, bullet lists, labels glued to inputs; **delete dialog renders in the page flow under the form** (`position:static`, no scrim, no box); *Tạo nguồn* / *Xóa nguồn* are white-on-white (`.button.primary`); `bx-h3` headings fall to UA `h2` 21 px | `apps/admin/app/layout.tsx:3-6` and `apps/platform/app/layout.tsx:3-6` do not load `builder.css`; the panel uses `bx-*` + dark-only `.button/.smallButton` (`DataSourcesPanel.tsx:140,176,187,190,218`, `ui/primitives.tsx:46-47`) | S3 (+S1) | move the shared `bx-field/-list/-group/-state/-overlay/-dialog/-btn` primitives into a **themeable** `@xweb/ui` stylesheet (light + dark tokens) loaded by all apps — do **not** just import `builder.css` (its colours are dark and would give a dark dialog on a light page) | harness spec: mount the panel in a `.shell` page with only the four Admin CSS files; assert dialog `position:fixed`, scrim, button contrast ≥ 3 : 1. Real: tenant admin with `DATA_SOURCE_MANAGE` | OPEN |
| S3-002 | all three | NavDrawer / `.shell>.sidebar` (`NavDrawer.tsx`, `factory.css:340-350`) | ≤ 900 px, menu closed or open | P1 | a11y: focus / keyboard | Closed drawer out of tab order and a11y tree; open drawer takes focus, traps it, restores it | `[H]` first Tab lands on the **off-screen** link (x −294…−19) at 360 / 600 / 800 / 900 px; sidebar stays `visibility:visible`; opening does not move focus, no trap, no `aria-modal`, page behind stays operable | `transform:translateX(-102%)` only; `useNavDrawer` handles Esc and nothing else | S3 | closed: `visibility:hidden` after the transition (or `inert`); open: `role="dialog" aria-modal`, focus first link, trap, `inert` on `.shellMain`, restore to button | harness: first Tab at 360/800/900 never lands at x < 0; open → focus inside; Esc → menu button focused; axe | OPEN |
| S3-003 | Platform + Admin | every `.grid2` page: Overview, user / workspace / application detail, AI limits, template review, settings, scoped home | viewport ≤ 394 px or 400 % zoom (320 px) | P1 | responsive / WCAG 1.4.10 | cards shrink to the container, no sideways scroll | `[H]` cards 380 px wide in a 292–362 px container; `main.page` scrolls sideways (`scrollWidth 394` vs `clientWidth 320 / 360 / 390`); document does not overflow so the earlier "page wider than phone = 0" check missed it | `factory.css:41` `repeat(auto-fit,minmax(380px,1fr))` | S3 | `minmax(min(380px,100%),1fr)` | harness reflow spec at 320 / 360 / 390 asserting `.page.scrollWidth ≤ clientWidth` on a `grid2` fixture; teach `scripts/ui-audit.mjs` to measure inner scroll containers | OPEN |
| S3-004 | Studio | project AI mode (`ProjectWorkspace.tsx:293-300`) and Code mode (`CodeWorkspace.tsx:141-148`) | ≤ 767 px | P1 | responsive / function | every project panel reachable on a phone | `[R]` *Website, Phiên bản, Tệp, Chia sẻ, Cài đặt* (AI) and *Lịch sử, Thư viện, IDE, Máy chủ, Chia sẻ* (Code) are `display:none`; the `.mobileMore` menu meant to replace them exists only in legacy `components/StudioShell.tsx:133` | `responsive.css:182-186` hides the buttons; the real Studio never renders `.mobileMore` | S3 + S2 | shared overflow `Menu` (details/popover) or wrap the buttons like `.bx-top`; one-row compact bar | real 390 px run: every drawer route opens by tap; replica spec kept | OPEN |
| S3-005 | Studio | Code mode editor `CodeWorkspace.tsx:185` | keyboard, editable file | P1 | a11y: WCAG 2.1.2 (A) | Tab leaves the editor (or Esc then Tab) | `Tab` **and** `Shift+Tab` are `preventDefault`ed and insert two spaces; no escape key, no hint | handler on every `Tab` | S2 | do not intercept Tab by default; or "Esc then Tab leaves" with a visible hint (`aria-describedby`) and no Shift+Tab capture | Playwright: focus editor, Tab moves focus out | OPEN |
| S3-006 | Studio | project toast `ProjectWorkspace.tsx:375`, `CodeWorkspace.tsx:240`, `.toast` (globals) | any notice | P1 | a11y: WCAG 4.1.3 / UX | polite or assertive announcement, auto-dismiss + close control, queue, never over primary actions | `<button class="toast">` inserted with its text → **not announced**; no timeout, no Esc, replaced silently by the next notice; fixed `right:20 bottom:20 z:70` over `.drawerActions` / composer; below builder dialogs (80) | single `notice` state rendered as a button | S3 + S2 | shared `Toast` region present from page load (`role=status`, `role=alert` for errors), 6–8 s, close button, stacking, offset above composer/drawer actions | harness + axe: live region exists before the message; message text lands in it | OPEN |
| S3-007 | Platform + Admin | create-account dialog (`ProvisioningScreens.tsx:72,81,98,107`), employee dialog (`EmployeesScreens.tsx:120`), tenant forms (`TenantScreens.tsx:94,307`), unit detail (`OrganizationScreens.tsx:168`), `AddExisting` | open | P2 | design system | grouped sections with the same look as the other dialogs | `[H]` `fieldset.stack` = UA `2px groove` border, `legend.bx-h4` 16 px/400, dialog title a bare 24 px `h2` (no `ModalHeader`) | `.stack` defined nowhere; `bx-h3/h4` only in `builder.css` | S3 + S1 | define `.stack` + a section-title class in `factory.css`; convert to `ModalHeader` / `.xp-section` | harness `prov.html`: fieldset border none, legend font-size | OPEN |
| S3-008 | all light shells | `.tabs` buttons, `.kpiButton`, `.xp-chev`, `.linkButton`, `.xp-advBtn`, `.xp-clear`, `.smallButton`; every checkbox / radio | keyboard focus | P2 | a11y: focus visible / 1.4.11 | one ring ≥ 3 : 1 on its surface | `[H]` ring `#67b8ff` = **2.13 : 1** on white for 7 control types; checkbox/radio only a `#c7d2fe` ring = **1.49 : 1**; four ring colours in the product | `responsive.css:62-70` (dark-theme ring applies everywhere), `factory.css:324,367-368` | S3 | `--focus-ring` token per surface (light `#4f46e5`, dark `#93c5fd`) + one `:focus-visible` rule; checkbox/radio get the same 2 px ring | harness computed `outline-color` for every control class, contrast assertion | OPEN |
| S3-009 | all | inputs / selects / textareas, outlined buttons, `.xp-switch` off, cards | any | P2 | a11y: WCAG 1.4.11 | boundaries ≥ 3 : 1 | input border `#e4e7ec` **1.24 : 1** (dark `#3a4655` 1.82, `#2d3742` 1.45); switch off track & knob **1.48**; `.btn` / `.btn.danger` border 1.24 / 1.42 | `--f-line` reused for control borders (`factory.css:26,362`), `.xp-switch #cbd5e1` | S3 | `--f-control-border` `#858d9d` (3.34 : 1 on white, 3.09 on `--f-bg`); dark `#5b6b7d` (3.2 : 1); switch off track `#858d9d`; keep `--f-line` for decorative dividers | token-pair contrast unit test (script in Appendix A) | OPEN |
| S3-010 | Platform + Admin | `Modal` whose first control is a `Picker` (provider dialog "Thêm nhà cung cấp", unit dialog) | open dialog; Tab cycle | P2 | a11y: focus | initial focus on a visible control; no hidden stops | `[H]` initial focus on the invisible `<select class="srOnly" tabindex=-1>`; wrap lands on it once per cycle | `Modal.tsx:16` selector + `:5` `FOCUSABLE` have no `tabindex=-1`/hidden exclusion; `Picker.tsx:37` hidden native select precedes the button | S3 | exclude `[tabindex="-1"]`, `.srOnly`, `aria-hidden` from initial focus and the trap; support `[data-autofocus]` | harness `ai.html`: `document.activeElement` is visible; Tab loop has no 1 px element | OPEN |
| S3-011 | Platform + Admin | `Picker` | list open, screen reader | P2 | a11y: ARIA | `aria-activedescendant` on the focused combobox | on the `listbox` while focus stays on the button → active option never announced | `Picker.tsx:38` vs `:42` | S3 | move the attribute to the button; add `aria-labelledby`; keep `aria-selected` for the chosen value | harness + axe | OPEN |
| S3-012 | Studio (+ Admin via S3-001) | builder `Dialog` (12 uses) `primitives.tsx:28-54` | focus not inside (after clicking dialog text); form dialogs | P2 | popup behaviour | Esc always closes; background inert; no data loss on stray click | `[H]` Esc does nothing when focus is on `<body>`; backdrop **mousedown** closes `AddDialog` / `RenameDialog` / slot edit and loses typed text; no scroll lock; not portaled (wheel over the scrim scrolls the panel behind) | `onKeyDown` on the dialog div (`:36-38`), `onMouseDown` close (`:46`) | S2 + S3 | document-level key handler, portal to `body`, `inert` background, scroll lock, no backdrop-close when dirty | harness `ds.html` | OPEN |
| S3-013 | Platform / Admin / Studio | 7 tablists: `AdminApp.tsx:233,407,581-584`, `AiSetup.tsx:38`, `StudioApp.tsx:164,285`, `CodeWorkspace.tsx:208` | AT / keyboard | P2 | a11y: ARIA | proper tabs (roving tabindex, arrows, tabpanel) or `nav` + `aria-current` | `role=tab` on `<Link>`s and on buttons with no tabpanel and no arrow keys; the correct `Tabs` primitive exists only in the builder | ad-hoc `.tabs` markup | S3 + S1/S2 | one `Tabs` in `@xweb/ui` (button variant + link variant) | axe `aria-required-parent/children`; Playwright arrow keys | OPEN |
| S3-014 | Platform + Admin | `AuditTable` `AdminApp.tsx:168-174`; other `tr.clickRow` `:259,332,366`, `TenantScreens.tsx:152` | keyboard | P2 | a11y | expandable detail reachable by keyboard | the audit row **expands only on mouse click** (old/new JSON); other clickable rows have a link so only the audit detail is lost | `tr onClick` with no key handling | S1 | `<button aria-expanded>` in the action cell | Playwright keyboard | OPEN |
| S3-015 | Studio | `PublishModal` visibility choice `ReleaseModal.tsx:186-188` | any | P2 | a11y: ARIA | selected option exposed | `[H]` two buttons `.publishChoice(.selected)` with `aria-pressed=null`, no radio role | class-only selection | S2 | `role=radiogroup` + `role=radio aria-checked` (arrow keys) | harness `release.html` | OPEN |
| S3-016 | all | 33 disabled controls whose only explanation is `title` (e.g. `BuilderTopBar.tsx:37-38`, `ProjectWorkspace.tsx:299`, `ReleaseModal.tsx:212-223`, `EmployeesScreens.tsx:164`, `DataSourcesPanel.tsx:151-153,187,203-204`, `TestPanel.tsx:153-175`) | disabled | P2 | a11y / UX | reason reachable by keyboard / touch / SR | disabled `<button>` is not focusable, `title` never shows on touch | pattern | S3 | `aria-disabled` + visible inline hint (`aria-describedby`) via a `DisabledReason` helper | axe + manual | OPEN |
| S3-017 | Admin + Studio | 34 `window.confirm / prompt` sites (e.g. `AdminApp.tsx:285,290,295,404,405,429,650,686,690,770,816,851,934,1081,1082`, `drawers.tsx:95,165,169`, `SitePanels.tsx:49,106,138`, `StudioApp.tsx:268,319`, `CodeWorkspace.tsx:122`, `ReleaseModal.tsx:140`) | destructive actions / required input | P2 | UX consistency / a11y | one styled confirmation (focus on Cancel) and inline fields for required input | native dialogs; 4 `window.prompt` collect **required** text (template rejection reason `:686`, risk-acceptance note `:816`, rename `:1081`, review comment `CodeWorkspace.tsx:122`) with no validation message | convenience | S3 + S1/S2 | `ConfirmDialog` + `useConfirm()`; replace prompts with a textarea in a `Modal` | Playwright | OPEN |
| S3-018 | all | buttons | — | P2 | design system | one Button | 4 live systems (`.btn` 188, `.smallButton` 65, `.button` 61, `.bx-btn` 51) + ≈ 20 button-likes; `.bx-btn` shrinks 34 → 28 (`builder.css:21` vs `130`); 5 radii, 4 hover styles | layered history | S3 | `Button` component (variants / sizes / icon) + two themes; migrate per portal | screenshot diffs; class-usage script | OPEN |
| S3-019 | all | CSS tokens | — | P2 | design system | every repeated colour is a token; none unused / undefined | 386 hex (191 unique): 249 with no token (50 distinct values repeated: `#c7d2fe` ×13, `#6366f1` ×8, `#eef2ff` ×8…), 68 equal to a token; `--sp-*` unused; `--danger` / `--warn` undefined (`builder.css:98,100`); 3 accents; 3 scrim values | — | S3 | token layer (surface-1/2/3, border, control-border, focus, scrim, selection, status-border); codemod; stylelint `color-no-hex` allow-list | stylelint run locally / script (no GitHub Actions) | OPEN |
| S3-020 | all | CSS files | — | P2 | CSS hygiene | no conflicting duplicates, no dead rules, diffable source | 93 selectors defined twice, 40 with conflicts (`.pickList` flex vs grid changes the template chooser, `.studio` height, `.bx-btn`, `.checklist` vs `.checkList`…); ≈ 96 dead rules (7.4 KB, 8 %); `globals.css` is one 9.7 KB line | five layered files | S3 | delete dead rules (owner sign-off), merge duplicates, unminify `globals.css`, order by component | `dups.mjs` (Appendix A) as a local check | OPEN |
| S3-021 | all three | `.shell` / `.shellMain` `height:100vh` (`factory.css:7-8`) | mobile browsers with dynamic toolbars (iOS Safari) | P2 | responsive | `100dvh` with `100vh` fallback | last ≈ 80 px of `.page` may sit under the toolbar (**NOT VERIFIED on a device**) | `vh` unit; Studio already uses `dvh` | S3 | `height:100vh;height:100dvh` | device test | OPEN |
| S3-022 | Studio | project AI mode | ≤ 900 px | P2 | responsive | preview usable, 2-way pane switch | `[R]` 3-row top bar 141 px + prompt 492 px → **preview 211 px** (25 % of 844); no pane tabs in the real UI | `responsive.css:98-155` (pane-tab rules target legacy markup only) | S3 + S2 | prompt / preview switch like `.bx-mview`, compact bar | `[R]` spec + real 390 px screenshot | OPEN |
| S3-023 | Studio | `AiProgress.tsx:13`, `aiProgressModel.ts:53-54` | streaming answer | P2 | a11y: live region | announce phase changes only | `role=status aria-live=polite` text changes on **every chunk** ("Đang nhận câu trả lời… 123 ký tự") → continuous chatter | counters inside the live region | S2 | counters in an `aria-hidden` span; live text only on phase change | unit on `progressView` + manual SR | OPEN |
| S3-024 | Admin + Studio | role labels: `adminModel.ts` `WORKSPACE_ROLES`, `UserDialogs.tsx:7-10`, `drawers.tsx:101-103`, `packages/i18n` `ROLE_LABEL` (unused) | any role display | P2 | text (T-03) | one name per role | 3 Vietnamese names per role (*Quản trị không gian làm việc / Quản trị workspace*, *Biên tập viên / Biên tập / Người chỉnh sửa*, *Chỉ xem / Người xem*); raw `WORKSPACE_ADMIN`, `EDITOR`… shown at `AdminApp.tsx:316,348,907,1128`, `EmployeesScreens.tsx:157` | 4 copies | S3 | single `roleName()` in `@xweb/i18n`, remove copies | unit: no raw role in rendered text | OPEN |
| S3-025 | Admin + Studio | raw enum / status / code / id shown to users (T-02: ≈ 40 sites: `AdminApp.tsx:170,171,428,431,784,1186,1191,1193`, `ProjectWorkspace.tsx:97,166`, `CodeWorkspace.tsx:96,228`, `TestPanel.tsx:190,210`, `ActionEditor.tsx:112,121`, `PropsForm.tsx:44`, `WorkflowEditor.tsx:106`…) | any | P2 | text | Vietnamese label for every value | `UPDATED`, `REJECTED`, `PASS/SKIPPED`, `CRITICAL/WARNING`, `GET`, `workflow_run`, step ids, `NOTIFY_CHANNELS`… | missing label maps | S1 + S2 | label maps with a fallback that logs unknown values | unit: every enum value used in UI has a label | OPEN |
| S3-026 | Platform / Admin / Studio | config / env / flag names and internal vocabulary shown to users (T-04: `OPENROUTER_API_KEY`, `OIDC_ENABLED`, `SCIM_TOKEN`, `BACKUP_STATUS_DIRS`, `app.data-platform.enabled`, `APP_PUBLISH`, `pointerVersion`, `apiBase`, `data-xw-state`, `dataSources[]`, "Backend provisioning", "Page Schema", "Registry", "MinIO", "Forgejo"…) | any | P2 | text | plain Vietnamese, no internals | ≈ 45 strings | developer-written copy | S1 + S2 | rewrite per T-04 table | grep guard test for forbidden tokens in UI strings | OPEN |
| S3-027 | all | terminology (T-05) | any | P2 | text | one term per concept | *workspace / không gian làm việc* (105 / 14), *project / ứng dụng / dự án* (24 / 171 / 4), *component / thành phần* (35 / 46), *model / mô hình*, *template / mẫu*, *xóa / xoá / gỡ*, *hủy / huỷ / đóng*, *tắt / khóa / vô hiệu hóa*, *hoàn tác / khôi phục / phục vụ lại*, five names for test mode | no glossary | S3 (+S1/S2 apply) | adopt the canonical list in the text file | guard test of forbidden variants | OPEN |
| S3-028 | all | tone-mark style | any | P3 | text | one style | *xoá* ×27 vs *xóa* ×106, *huỷ* ×11 vs *hủy* ×35, *tuỳ* ×7 vs *tùy* ×2, *khoá* ×2 vs *khóa* ×69 | — | S1 + S2 | convert to the modern style (47 rows in the text file) | grep guard | OPEN |
| S3-029 | Admin + Studio | English labels in navigation / titles (T-06) | any | P2 | text | Vietnamese UI with a short controlled English glossary | *Components, Templates, Packages, Connector, Registry, Model Access, AI Control, Design / Code, Mock / Self-host / Cloud, Admin Console, Builder Studio, Base URL, API key, "MFA managed by Identity Provider"* | — | S1 + S2 | per T-06 table | — | OPEN |
| S3-030 | Admin + Studio + auth | stale / contradictory copy (T-07) | any | P2 | text | true, consistent statements | *Chưa triển khai: Lưu trữ (archive)* next to a working *Lưu trữ* button (`AdminApp.tsx:404,424`); Home says "Website (một trang)" while *Tạo ứng dụng* offers multi-page + 5 other kinds (`StudioApp.tsx:128` vs `:186`); password policy **6** characters (`AuthPages.tsx:116`, `minLength={6}`) vs **8** (`:54`, `minLength={8}`); "(demo)" / "Demo deployment" | drift | S1 + S2 | fix per T-07 | — | OPEN |
| S3-031 | Admin | `DailyBars` `AdminApp.tsx:455-465`, `.budgetBar` `:979` | AT / keyboard / touch | P3 | a11y | per-value data available as text | bars have `title` tooltips only, container `role=img` with a summary; no table alternative | tooltip-only | S1 | visually hidden data table or `<details>` | axe + manual | OPEN |
| S3-032 | all | brand / portal names (T-08) | any | P2 | text / brand | one product name per portal | *AI Software Factory* (`AdminApp.tsx:139`, `AuthPages.tsx:21`, root layout), *Company Builder Studio* (`StudioApp.tsx:65`), *Xweb Platform / Xweb Studio / Quản trị công ty* (`PORTAL_LABEL`), *Xweb Admin* (`apps/admin/app/layout.tsx:8`), *Admin Console*, *Builder Studio*; root layout description in English | — | S3 | single `BRAND` / `PORTAL_TEXT` source (the unused `i18n.PORTAL_TEXT` is the place) | — | OPEN |
| S3-033 | Platform + Admin | `LinkBox` (one-time activation / reset link) `UserDialogs.tsx:13-30` | open | P2 | UX / a11y | focus on the link or a safe control; confirm before discarding; copy result announced | first focusable = **"Xong"**; Esc or Xong destroys the only copy of the link without confirmation; "Đã sao chép" is a button-text change (not announced) | `Modal` focuses the first button | S1 | focus the readonly input (select on focus), `role=status` for copy, confirm on Esc when not copied | harness | OPEN |
| S3-034 | Studio | `useDialog` / `Drawer` / `PublishModal`, `Modal` | open | P3 | popup | background inert + scroll lock | no `inert` / `aria-hidden` behind (`[H]` `anyInert:false`), no scroll lock in `useDialog` | by design (relies on `aria-modal`) | S3 | shared `useOverlay()` (inert + lock + focus + Esc) | harness | OPEN |
| S3-035 | Platform + Admin | `Modal.tsx` | nested / re-rendered dialogs (latent: none today) | P3 | popup | stack-safe | nested lock restores in the wrong order; Esc closes **all** open modals; `onClose` captured once (`useEffect(…, [])`) | `Modal.tsx:13-30` | S3 | overlay stack, ref to latest `onClose` | unit | OPEN |
| S3-036 | Studio + shared | heading structure (C1) | any | P3 | a11y | one `h1` per page, no skipped / nested levels | `CodeWorkspace` has no `h1`; AI mode `h1` disappears after the first message; `Splash` / loading `StateView` = `h2` without `h1`; `BlockCard` `h2` inside `Card` `h2` | `StateView` default level 2 | S2 + S3 | `h1` on the workspace title; `StateView level` for splash | axe `page-has-heading-one` | OPEN |
| S3-037 | all | 40 data tables | AT | P3 | a11y | named tables | 2 of 40 have `<caption>` (one with the undefined class `sr-only`); `.xp-empTable` card layout (`factory.css:317-322`) drops table semantics in Safari | — | S1 + S3 | `Table` wrapper with `caption`; `role=table` on the mobile layout | axe | OPEN |
| S3-038 | all | landmarks / alerts (C2, C6) | any | P3 | a11y | quiet pages, correct landmarks | `main` is a tab stop even when it does not scroll (`AdminApp.tsx:68`, `StudioApp.tsx:42`); `aside` inside `main` in the builder (`BuilderWorkspace.tsx:195,201`); whole-page `forbidden / notfound / conflict` states are `role=alert` (`ui.tsx:39`); no skip link in the three shells | — | S3 | `tabIndex=-1` on `main` (focus after route change), drop `role=alert` for page states, add a skip link | axe | OPEN |
| S3-039 | Admin | `PersonPicker.tsx:34,41`, org tree `OrganizationScreens.tsx:123-124` | AT | P3 | a11y: ARIA | valid combobox / tree | constant `aria-expanded="true"`, active option marked `aria-selected`; tree focus is on a child `div` of the `treeitem` `li` | — | S1 | fix per WAI-ARIA APG | axe + SR | OPEN |
| S3-040 | all | typography (A4) | any | P3 | design system | loaded font, a scale | Inter declared but never loaded; weights 650 / 800 / 900 unsupported by system fonts; 17 sizes, 80 text rules at 11–12 px | — | S3 | `next/font` (self-hosted, Vietnamese subset) + type scale tokens | visual diff | OPEN |
| S3-041 | all | spacing / radius / shadow (A3, A5) | any | P3 | design system | scales | 53 % of spacing off-scale; 16 radii; 11 shadows; `--sp-*` unused | — | S3 | adopt scales | lint | OPEN |
| S3-042 | all | `forced-colors`, `color-scheme`, `prefers-contrast` (A17) | OS settings | P3 | a11y | supported | none declared: switch / bars invisible in forced colors; dark editor shows light native scrollbars / date pickers | — | S3 | `color-scheme` per idiom; forced-colors overrides | Playwright `forcedColors:'active'` screenshots | OPEN |
| S3-043 | Studio + Admin | native `<select>` 115 vs `Picker` 2; `select multiple` `ActionEditor.tsx:57` | any | P3 | UX | consistent choosers; instructions for multi-select | listboxes with logos/hints are native selects; multi-select without hint (Ctrl/⌘) | — | S2 | use `Picker`/checkbox group | — | OPEN |
| S3-044 | Platform + Admin + Studio | `Pill` tone mapping `ui.tsx:62-69`, 49 call sites with `label=` (13 occurrences of the `"UNKNOWN"` tone key) | any | P2 | design system / UX | tones express severity | warnings / risk shown **grey**: *Rủi ro cao* (`AdminApp.tsx:863`), *Trả phí* (`AiSetup.tsx:218`), *Chờ duyệt* (`AdminApp.tsx:680`, `StudioApp.tsx:229`), alert `WARNING` (`:997`), `MEDIUM` (`:1005`); *Quản trị hệ thống* shown as `PUBLIC` | status keys reused as a colour picker | S3 | `<Pill tone="warn \| bad \| ok \| info \| muted">` API | unit + visual | OPEN |
| S3-045 | Platform + Admin | `JSON.parse` of server strings `AdminApp.tsx:174` (audit old/new value), `:600` (props schema) | malformed data | P3 | robustness | a bad row shows an error cell | unguarded `JSON.parse` throws and unmounts the page | — | S1 | `safeJson()` | unit | OPEN |
| S3-046 | Platform + Admin | `.modalBody .xp-footer` sticky (`factory.css:380-381`) | short viewport (≤ 420 px high), landscape phone, 400 % zoom | P3 | a11y: 2.4.11 / 2.4.12 | focused field not covered | `[H]` focused input 32 px under the sticky footer (not fully hidden: AA passes, AAA fails) | no `scroll-padding-bottom` | S3 | `scroll-padding-bottom` = footer height on `.modalBody` | harness | OPEN |
| S3-047 | Studio + auth | undefined classes: `sr-only` (`TestPanel.tsx:212` → caption **visible**), `bx-table`, `center` (`AuthPages.tsx:109`), `xp-empCard`, `xp-orgTreeCard`, `xp-orgDetailCard`, `codeMsg`, `buildInfo`, `studio-shell` | any | P3 | CSS | classes resolve | no CSS defines them | typos / dead hooks | S2 + S3 | `srOnly`; define or remove | lint: used-but-undefined | OPEN |
| S3-048 | Studio | `NewApp` type chooser `StudioApp.tsx:207-211` | keyboard | P3 | a11y | radiogroup with arrow keys / roving tabindex | each enabled card is its own tab stop; arrows do nothing | — | S2 | roving tabindex + arrows | Playwright | OPEN |
| S3-049 | Platform + Admin | `.page` content width at ≥ 1280 (`factory.css:9`) | wide screens | P3 | responsive | readable measure | no max-width: tables / cards ≈ 1610 px at 1920; `.grid2` may lay 4 columns | — | S3 | `max-width` container (e.g. 1440) | visual | OPEN |
| S3-050 | Platform + Admin + Studio | irreversible actions with no confirmation: *Chạy dọn dẹp ngay* (`AdminApp.tsx:786`, handler `:769`), *Xóa khóa kết nối* (`DataSourcesPanel.tsx:165`), delete secret (`CodePanels.tsx:139`) | click | P2 | UX safety / text (T-09) | a confirmation that states what is lost (counts, "không xem lại được") | one click executes: cleanup deletes artifacts / expired previews permanently; credential and secret values are write-only, so deleting them cannot be undone by the user | handlers call the API directly (other deletes in the same screens do confirm) | S1 + S2 | `ConfirmDialog` (S3-017) with the consequence text | Playwright: click → dialog, no request before confirm | OPEN |

---

## Appendix A. Reproduction (HARNESS, NOT REAL BACKEND)

Nothing below needs a backend. `esbuild` is installed **outside** the repo (the repo rule), the builds go to the git-ignored `.test-build/`, and every server/process is started through `tests/browser/harness-server.mjs run -- <cmd>` (free port, owned-process identity, always stopped); no fixed port, no kill by name or port.

```
npm i --prefix <scratch>/esb esbuild
ESBUILD_DIR=<scratch>/esb node tests/browser/build-harness.mjs            # repo harness pages: index / ds / ai / org / prov / release / public
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  node tests/browser/harness-server.mjs run -- node <spec>.mjs             # <spec> reads HARNESS_URL
```

Audit-only harness pages / specs (kept in the session scratchpad, **not committed**; the logic is small enough to re-create):

| Spec | What it does | Findings |
|---|---|---|
| `adminds.tsx` (esbuild entry, imports the four Admin CSS files, **not** `builder.css`) + `spec-adminds.mjs` | mounts the real `<DataSourcesPanel>` with a fake `calls` object inside a `.shell` page; opens the delete dialog; reads computed styles | S3-001 |
| `spec-popups.mjs` on `ai.html` / `ds.html` | provider `Modal` + `Picker`: initial focus, Tab sequence, Esc order, scroll-lock value, 360 × 640 fit; builder `Dialog`: Esc, backdrop, restore | S3-010, 011, 012, 034, 035 |
| `spec-popups2.mjs`, `spec-nav.mjs` | Esc from `<body>`; off-canvas sidebar at 360 / 600 / 800 / 900 / 1280 (computed transform, `visibility`, first Tab target) | S3-002, 012 |
| `spec-focus.mjs` | injects 15 control types into the admin shell, focuses each by keyboard, reads `outline` | S3-008 |
| `spec-reflow.mjs` | `.grid2` fixture at 320 / 360 / 390 / 400 / 430 / 768 / 1024: `main.page` scroll width | S3-003 |
| `spec-prov.mjs` (`prov.html`) | create-account dialog fieldset / legend computed style | S3-007 |
| `spec-release.mjs` (`release.html?draft=public`) | publish dialog choice semantics, checkbox size | S3-015 |
| `spec-sticky.mjs` (`ai.html`) | focus rects vs the sticky footer at 1280 × 420 / 360 × 500 | S3-046 |
| `ai-replica/index.html` + `spec-ai.mjs` | **replica** (static markup copied from `ProjectWorkspace.tsx` AI mode + the real CSS in load order) at 7 viewports | S3-004, S3-022 |

CSV-style numeric analyses (reproducible Node scripts, no dependencies): `classes.mjs` (used-vs-defined classes), `scales.mjs` (colours / spacing / font / radius / shadow tallies), `dups.mjs` (duplicate selectors & conflicts), `deadbytes.mjs`, `hexclass.mjs` (token duplicates), `contrast.mjs` (115 pairs).

## Appendix B. Contrast — all 115 computed pairs

`kind text` needs 4.5 : 1, `ui` needs 3 : 1. Rows flagged "(exempt)" in the text are disabled controls.

```
PASS 16.51 (need 4.5) L01 text on page bg :: --f-text on --f-bg
PASS  6.47 (need 4.5) L02 muted on panel (hint, th, kpiLabel) :: --f-muted on --f-panel
PASS  5.98 (need 4.5) L03 muted on page bg (.chip shell, .crumb) :: --f-muted on --f-bg
PASS  5.66 (need 4.5) L04 muted on f-line2 hover row :: --f-muted on --f-line2 (.navLink:hover small)
PASS  6.29 (need 4.5) L05 accent link on white (.table a) :: --f-accent on white
PASS  5.55 (need 4.5) L06 accent on accent-soft (.navLink.active) :: --f-accent on --f-accent-soft
PASS  6.29 (need 4.5) L07 btn.primary text :: --f-accent-ink on --f-accent
PASS   7.9 (need 4.5) L08 btn.primary:hover :: hover
PASS   5.4 (need 4.5) L09 pill-ok :: pill-ok 12px
PASS  7.04 (need 4.5) L10 pill-warn :: pill-warn 12px
PASS  6.05 (need 4.5) L11 pill-bad :: pill-bad 12px
PASS  5.57 (need 4.5) L12 pill-info :: pill-info 12px
PASS  9.49 (need 4.5) L13 pill-muted :: pill-muted 12px
PASS  6.57 (need 4.5) L14 btn.danger text :: .btn.danger
FAIL  1.42 (need 3) L15 btn.danger border :: .btn.danger border (1.4.11)
FAIL  1.24 (need 3) L16 btn border :: .btn border (boundary)
FAIL  1.24 (need 3) L17 input border :: input/select/textarea border (--f-line)
PASS  4.95 (need 4.5) L18 placeholder :: --f-placeholder
FAIL  3.47 (need 4.5) L19 disabled ink (exempt) :: --f-disabled-ink
FAIL  1.49 (need 3) L20 focus ring outline #c7d2fe on white :: input:focus outline
PASS  6.29 (need 3) L21 focus border/outline --f-focus on white :: .btn:focus-visible outline
PASS  7.62 (need 4.5) L22 notice text :: .notice
PASS   8.7 (need 4.5) L23 xp-note :: .xp-note
PASS  8.75 (need 4.5) L24 xp-orgNotReady text :: .xp-orgNotReady
PASS  6.84 (need 4.5) L25 xp-orgNotReady hint :: .xp-orgNotReady .hint
PASS   8.4 (need 4.5) L26 archivedBanner :: .archivedBanner
PASS   3.3 (need 3) L27 xp-ok check :: .xp-ok icon
FAIL  1.48 (need 3) L28 switch off track vs white :: .xp-switch track off
PASS  6.29 (need 3) L29 switch on track vs white :: .xp-switch.on
FAIL  1.48 (need 3) L30 switch knob on off-track :: .xp-knob on track
PASS  8.06 (need 4.5) L31 avatar :: .avatar
PASS  6.98 (need 4.5) L32 stateIcon :: .stateIcon
PASS 15.26 (need 4.5) L33 sidebar.dark text :: .sidebar.dark
PASS  7.44 (need 4.5) L34 sidebar.dark small :: .sidebar.dark small
PASS 13.39 (need 4.5) L35 sidebar.dark active :: .sidebar.dark .navLink.active
PASS 13.49 (need 4.5) L36 sidebar.dark nav hover :: hover
FAIL  1.17 (need 3) L37 sidebar border #1d2129 vs #0f1115 :: decorative
FAIL  2.58 (need 3) L38 healthCard border-left grey vs white :: .healthCard border-left (status by colour only)
FAIL  2.62 (need 3) L39 healthCard HEALTHY #12b76a :: .h-HEALTHY (label also given)
FAIL  2.35 (need 3) L40 healthCard DEGRADED #f79009 :: .h-DEGRADED
FAIL  1.46 (need 3) L41 budgetBar fill #20c997 on rgba(127,127,127,.2) over white :: .budgetBar>i
FAIL   1.4 (need 3) L42 budgetBar warn #f5a524 :: .budgetBar.warn
PASS  6.47 (need 4.5) L43 th muted 12px uppercase on white :: .table th
PASS  6.47 (need 4.5) L44 tabs inactive on white :: .tabs>* color muted
PASS  5.98 (need 4.5) L45 xp-opt-tag :: .xp-opt-tag 11px
PASS  6.29 (need 4.5) L46 portalKicker accent on white :: .portalKicker 12px
PASS  6.08 (need 4.5) L47 kpiButton etc: link --f-accent on #fafbff hover :: .clickRow:hover link
PASS  6.57 (need 4.5) L48 formError --f-bad on white :: .formError
PASS  6.29 (need 4.5) L49 xp-person .xp-pick on accent :: .xp-pick
FAIL  2.22 (need 4.5) L50 .btn.primary:disabled (exempt) :: disabled primary
FAIL  1.15 (need 3) L51 card border vs bg #e4e7ec on #f5f6f8 :: .card border vs page
PASS 17.85 (need 4.5) L52 kv/dd etc fine :: text on panel
PASS 17.87 (need 4.5) D01 text on bg :: --text on --bg
PASS  7.78 (need 4.5) D02 muted on panel :: --muted on --panel
PASS  7.36 (need 4.5) D03 muted on panel2 :: --muted on --panel2
PASS 11.29 (need 4.5) D04 muted-strong on panel :: --muted-strong
PASS  5.72 (need 4.5) D05 toolbarLabel #8190a1 on #0e1319 12px :: .toolbarLabel
PASS  4.68 (need 4.5) D06 composerFooter #758193 on #0f141b 11px :: .composerFooter
PASS  5.79 (need 4.5) D07 segmented btn #8794a4 on #111820 :: .segmented button
PASS 14.67 (need 4.5) D08 segmented active #fff on #202936 :: .segmented button.active
PASS 12.56 (need 4.5) D09 settingField #cbd7e3 on #0f151c :: .settingField
PASS 11.99 (need 4.5) D10 suggestion #cbd7e3 on #131a22 :: .suggestion
PASS 11.94 (need 4.5) D11 chip #cbd7e3 on panel :: .chip in assistant bubble
PASS 12.84 (need 4.5) D12 savedPill #9aefd5 on blended rgba(32,201,151,.08) :: .savedPill
PASS 11.31 (need 4.5) D13 environmentBadge #bcd4ff on #131d2b :: .environmentBadge
PASS 10.35 (need 4.5) D14 bx-badge white on #334155 :: .bx-badge 11px
PASS  6.57 (need 4.5) D15 bx-badge.bad :: .bx-badge.bad
PASS  5.43 (need 4.5) D16 bx-badge.warn :: .bx-badge.warn
PASS  9.03 (need 4.5) D17 bx-btn.danger #fda29b on #131a22 :: .bx-btn.danger
PASS 12.27 (need 4.5) D18 bx-alert #fecdca on #2a1411 :: .bx-alert
PASS 13.37 (need 4.5) D19 bx-issues warn #fedf89 on #2a1411 :: .bx-issues li.warn
PASS 12.04 (need 4.5) D20 bx-test-banner #b2ddff on #0b1b33 :: .bx-test-banner
PASS 12.16 (need 4.5) D21 bx-caps yes #6ce9a6 on panel :: .bx-caps li.yes
PASS  9.04 (need 4.5) D22 studio formError #ff9a92 on panel :: .studio .formError
PASS 10.24 (need 4.5) D23 studio link #9cc3ff on panel :: .studio a
PASS 10.64 (need 4.5) D24 aiWarn #f5c26b on bubble :: .aiWarn
PASS  8.08 (need 4.5) D25 state.s-failed #ff8a80 on panel :: .state.s-failed
PASS 13.12 (need 4.5) D26 fileTree dirty #ffd479 on panel :: .fileTree button.dirty
PASS  7.91 (need 4.5) D27 modeTabs inactive muted on #0f1218 :: .modeTabs button
PASS 13.39 (need 4.5) D28 modeTabs active #fff on #262a5c :: .modeTabs button.active
PASS 11.66 (need 4.5) D29 bx-tabs active #fff on #2d3270 :: .bx-tabs button.active
PASS 11.29 (need 4.5) D30 bx-tabs inactive muted-strong on panel :: .bx-tabs button
PASS 18.34 (need 4.5) D31 button.primary #10151b on #fff :: .button.primary
PASS  4.95 (need 4.5) D32 sendButton #04120e on gradient start #2c7cff :: .composer .sendButton (left end)
PASS  8.98 (need 4.5) D33 sendButton #04120e on gradient end #20c997 :: .composer .sendButton (right end)
FAIL  3.58 (need 4.5) D34 sendButton disabled #7b8794 on #2a313b (exempt) :: disabled
PASS 12.27 (need 4.5) D35 saveState.saving #cfd8e3 :: .saveState.saving
PASS 10.34 (need 4.5) D36 saveState.error #ffb4ab on blended :: .saveState.error
PASS 17.87 (need 4.5) D37 toast #fff on #111820 :: .toast
PASS  8.45 (need 4.5) D38 codeEditor readonly #9fb0c3 on #0b1220 :: .codeEditor[readonly]
PASS  5.62 (need 4.5) D39 aiSteps pending opacity .55 of #f5f5f7 on bubble :: .aiSteps li[data-state=pending]
PASS  9.39 (need 4.5) D40 usageMeta/streamTail opacity .75 :: opacity .75 text
FAIL   1.3 (need 3) D41 line #262b34 vs panel (border) :: --line vs --panel
FAIL  1.45 (need 3) D42 input border #2d3742 vs #121a22 (bx-field) :: .bx-field input border
FAIL  1.82 (need 3) D43 input border #3a4655 vs #161a21 (http.css) :: .insField input / .authCard input
PASS 10.23 (need 3) D44 focus ring #93c5fd vs panel :: .bx-* focus
PASS  8.67 (need 3) D45 focus ring #67b8ff vs panel :: responsive.css focus
PASS  8.66 (need 3) D46 focus ring --accent #20c997 vs panel (http.css :where) :: http.css focus
PASS  3.45 (need 3) D47 bx-node active border #6366f1 vs #1d2148 :: .bx-node.active
PASS  4.33 (need 3) D48 bx-insert #6366f1 line vs canvas bg :: drop indicator
PASS  7.14 (need 4.5) D49 bx-state muted on tint :: .bx-state
PASS  6.72 (need 4.5) D50 bx-handle #e5e7eb on rgba :: .bx-handle opacity .65
PASS  3.24 (need 3) D51 bx-outcome tone borders :: .bx-outcome.tone-ok border
FAIL  2.65 (need 3) D52 bx-outcome tone-bad border :: .bx-outcome.tone-bad border
FAIL   2.8 (need 3) D53 bx-menu broken border :: .bx-menu li.broken
PASS 14.16 (need 4.5) D54 chip-notready #fedf89 on panel :: .chip-notready
PASS 12.89 (need 4.5) D55 chip-retry #b2ddff :: .chip-retry
PASS 13.71 (need 4.5) D56 chip-approval #e9d7fe :: .chip-approval
PASS 18.34 (need 4.5) D57 bx-skip #10151b on #fff :: .bx-skip
PASS  7.94 (need 4.5) D58 you pill #9ec1ff on #1c2a3f :: .you
PASS  7.23 (need 4.5) D59 starter span #6ea8ff on panel2 :: .starter span
PASS  8.66 (need 4.5) D60 uploadBox accent text on panel :: .uploadBox
PASS   9.3 (need 4.5) D61 siteLogo etc ignore :: .siteLogo (preview-only)
PASS  11.4 (need 4.5) D62 insField label #c4cfdb on #0f1722 :: .insField label
PASS 10.05 (need 4.5) D63 versionItem code #9bc3f4 on #0f151c :: .versionItem code
TOTAL 115 FAIL 21
```

## Appendix C. Source of the two scripts the findings lean on most (so they can be re-run without this session)

**C.1 `dups.mjs`** — selectors defined more than once and their conflicting declarations (run with Node ≥ 18; edit `root`).

```js
import fs from "node:fs";
const root = "packages/ui/src/styles/";
const files = ["globals", "responsive", "http", "factory", "builder"];
const seen = new Map();
for (const f of files) {
  const t = fs.readFileSync(root + f + ".css", "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
  const re = /(@media[^{]*)\{|([^{}@]+)\{([^{}]*)\}|\}/g; let m; const stack = [];
  while ((m = re.exec(t))) {
    if (m[1]) { stack.push(m[1].trim()); continue; }
    if (m[0] === "}") { stack.pop(); continue; }
    const sel = m[2].trim().replace(/\s+/g, " "), body = m[3].trim(), ctx = stack.join(" ");
    for (const s of sel.split(",")) { const k = (ctx ? ctx + " " : "") + s.trim(); if (!seen.has(k)) seen.set(k, []); seen.get(k).push({ f, body }); }
  }
}
const dups = [...seen.entries()].filter(([k, v]) => v.length > 1 && !k.startsWith("@keyframes"));
console.log("selectors defined more than once:", dups.length);
for (const [k, v] of dups) {
  const props = v.map((x) => new Map(x.body.split(";").filter(Boolean).map((d) => { const i = d.indexOf(":"); return [d.slice(0, i).trim(), d.slice(i + 1).trim()]; })));
  const conflicts = [];
  for (const p of new Set(props.flatMap((m) => [...m.keys()]))) { const vals = props.map((m) => m.get(p)).filter((x) => x !== undefined); if (vals.length > 1 && new Set(vals).size > 1) conflicts.push(`${p}: ${vals.join(" -> ")}`); }
  if (conflicts.length) console.log(`${k}  [${v.map((x) => x.f).join(", ")}]  CONFLICT ${conflicts.join(" ; ").slice(0, 200)}`);
}
```

**C.2 `adminds.tsx`** — S3-001 reproduction: the real `DataSourcesPanel` inside the Admin portal's CSS set (esbuild entry; alias `@xweb/*` to `packages/*/src` as `tests/browser/build-harness.mjs` does).

```tsx
import { createRoot } from "react-dom/client";
import { DataSourcesPanel } from "<repo>/features/studio/builder/DataSourcesPanel";
import "<repo>/packages/ui/src/styles/globals.css";      // exactly apps/admin/app/layout.tsx:3-6 — NO builder.css
import "<repo>/packages/ui/src/styles/responsive.css";
import "<repo>/packages/ui/src/styles/http.css";
import "<repo>/packages/ui/src/styles/factory.css";
const calls: any = {
  connectors: async () => [{ type: "postgres", displayName: "PostgreSQL", status: "AVAILABLE", capabilities: ["QUERY"], configKeys: [{ name: "host", required: true, description: "Máy chủ" }], credentialKeys: ["password"], notes: "" }],
  list: async () => [{ id: "ds-1", workspaceId: "w1", name: "billing-db", type: "postgres", config: { host: "x" }, hasCredential: true, status: "ACTIVE", version: 1, createdBy: null, createdAt: "", updatedAt: "" }],
  credential: async () => ({ configured: true, keys: ["password"], updatedAt: "" }),
  create: async () => ({}), update: async () => ({}), remove: async () => ({}), setCredential: async () => ({}), removeCredential: async () => ({}), test: async () => ({}),
  listBindings: async () => [], bind: async () => ({}), unbind: async () => ({}),
};
createRoot(document.getElementById("root")!).render(
  <div className="shell" data-nav="closed"><aside className="sidebar dark"><nav><a className="navLink" href="#x">Mục 1</a></nav></aside>
    <div className="shellMain"><header className="topHeader"><div className="crumb">Quản trị công ty</div></header>
      <main className="page" id="main"><h1>Nguồn dữ liệu</h1><section className="card">
        <DataSourcesPanel headingLevel={2} doc={{ page: "", sections: [] } as never} calls={calls} canView viewReason="" canManage manageReason="" canBind={false} bindReason="x"/>
      </section></main></div></div>);
// spec: click [data-testid="ds-delete:ds-1"]; read getComputedStyle of [role=dialog] and its parent (.bx-overlay): position static / z-index auto / height 140
//       and of [data-testid="ds-create"]: background rgb(255,255,255) on a white .card.
```

## Appendix D. What could not be verified

* **Real backend / real portals with a session:** none of the harness evidence is backend evidence; real data-driven states (long tables with real rows, real error bodies) were not seen.
* **Screen readers (NVDA / JAWS / VoiceOver / TalkBack):** every ARIA finding is from the DOM and the ARIA specs, not from announcement testing.
* **iOS Safari / Android Chrome devices:** `100vh` behaviour (S3-021), pull-to-refresh, virtual keyboard behaviour in bottom-sheet dialogs, touch-target feel.
* **Studio project pages in a running app:** the AI-mode phone layout (S3-004, S3-022) is a **replica** (markup copied, real CSS), because `ProjectWorkspace` needs Next's router and a backend; the CSS rules that hide the buttons are real, but the real page should be re-measured in the integrated stack.
* **Forced-colors / high-contrast / print / 200 % text-only zoom** were not run (S3-042 is code-derived).
* **Performance of `ScrollRegion` / `useOverflow`** on pages with many cards: not measured.
* **`packages/company-ui`** (the SDK for *generated* sites, its own `--cu-*` tokens, buttons, dialogs, toasts) was only read, not audited: it is a fourth design system outside the three portals.
* **`lib/schema-preview.ts` (C2-owned preview HTML/CSS, the iframe content)** and the legacy root app (`components/StudioShell.tsx`, `app/`) were not audited beyond their strings.
