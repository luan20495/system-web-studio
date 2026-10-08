# C5 — UI / UX audit of Platform, Admin and Studio (2026-10-08)

Tool: `scripts/ui-audit.mjs` (real Chromium, real stack = integration `ae0432f` unpatched, nothing mocked). It opens every real route, logs in as a SYSTEM_ADMIN (Platform + Admin system sections), a tenant admin (Admin company sections) and a workspace admin (Studio), and records per route and viewport: glyph characters that can render as tofu, U+FFFD / mojibake / HTML entities, page overflow, text spilling out of the viewport, clipped text, broken images, controls without a name, inputs without a label, console errors, failing API calls, blank pages, h1 count and axe (wcag2a/aa) serious + critical. Data is created through the product API (a new tenant, a tenant admin, a workspace admin, a project, 25 employees with long Vietnamese names, "Công ty Cổ phần Ánh Dương").
Companion tools: `scripts/ui-dialog-check.mjs` (Create Company + Admin organization / employees at 1440 / 1024 / 768 / 430 / 390, keyboard, axe), `scripts/ui-studio-shots.mjs` (builder rail panels, test mode, publish).

## Inventory
**53 routes × 4 viewports (1440, 1024, 768, 390) = 224 visits "before"; "after" re-run at 1440 + 390 (112 visits).**

| Portal | Routes opened (persona) |
|---|---|
| Platform (local.admin) | `/` `tenants` `tenants/{id}` `users` `ai` `components` `templates` `builds` `packages` `system` `backups` `costs` `alerts` `security` `settings` `audit` `connectors` + unknown route |
| Admin (tenant admin) | `/` `company` `organization` `employees` `people` `my-workspaces` `data-sources` `groups` `sharing` `byok` + unknown route |
| Admin (system admin) | `/` `users` `applications` `departments` `identity` `ai-governance` `templates` `audit` `sharing` `data-sources` |
| Studio (workspace admin) | `/` `projects` `new` `templates` `components` `activity` `site-access` `projects/{id}/ai|design|code|members|versions|assets|publish|settings|site` + unknown route; builder rail panels (Trang, Dữ liệu, Hành động, Workflow, Giao diện) and test mode by `ui-studio-shots.mjs` |
| Dialogs | Tạo công ty (Platform), move / unit / types / employee detail (harness) |

## Result (same routes, 1440 + 390)
| Check | Before | After |
|---|---|---|
| glyph characters used as icons (□ risk) | 94 visits | 14 (only "→" inside sentences / notation, e.g. "Duyệt → kiểm tra") |
| U+FFFD / mojibake / HTML entities | 0 | 0 |
| axe **critical** | 2 (builder tree `aria-required-children`) | **0** |
| axe serious | 0 | **0** (a dark-editor chip on a light page appeared mid-way and was fixed: `.shell .chip`) |
| page without an `<h1>` | 14 | 2 (the builder: its title is a `role=heading` element) |
| page wider than the phone (390) | 1 | **0** (every route of the final run) |
| controls without a name / inputs without a label | 0 | 0 |
| console errors / failing API calls | 0 / 0 | 0 / 0 |
| text spill / clipped without an ellipsis | 8 / 4 | 10 / **0** (the 10 are table cells inside a card that now scrolls sideways, by design) |

## Issues
| ID | Portal | Route | Issue | Sev | Before | Fix | After | Status |
|---|---|---|---|---|---|---|---|---|
| UI-01 | all | every screen with a sidebar / state | functional icons were text glyphs (▦ ◎ ▤ ✦ ⚖ ⛨ ⇄ ⛁ ⌘ ♥ ⚙ ⛔ ○ ✓ ✕ ◆ …): fonts without the glyph show □, and they were a different style from Lucide | P1 | 94 visits | Lucide everywhere (nav, brand mark, states, status, close, plus, grip, arrows in buttons, pager) | 14 (prose arrows only) | FIXED |
| UI-02 | all | `< 900 px` | the sidebar was `display:none` with no replacement: no navigation at all on a phone | P1 | no way to move | drawer navigation (menu button in the header, backdrop, Esc, closes on navigation, focus returns) | `ui-dialog-check` ADMIN-nav 4/4 | FIXED |
| UI-03 | Studio | `projects/{id}/design` | page tree: drag handle and ↑ ↓ buttons were children of the tree but not tree items (axe critical) | P0 | 2 critical | the row `<li>` is the treeitem and owns its buttons | 0 critical | FIXED |
| UI-04 | Platform / Admin / Studio | tables (audit, users, components, applications, members…) | wide tables spilled out of the card / the page at ≤ 1024 px | P1 | 8 spill, 4 clipped, overflow 110 px | the card scrolls sideways, words wrap (`break-word`), ids break only inside `code`, `position:relative` so hidden labels cannot escape | page overflow 0 | FIXED |
| UI-05 | all | whole-page states (404, "needs platform", "needs scope", site-access) | no `<h1>` | P2 | 14 | `StateView level={1}` | 2 left (builder, role=heading) | FIXED / noted |
| UI-06 | all | every route | the same browser title ("Xweb Platform") for every page | P2 | 18 identical titles per portal | `"<Mục> · <Cổng>"` per route, the project name for a project | distinct | FIXED |
| UI-07 | all | dialogs | no focus trap, no scroll lock, tall dialogs ran off a short screen, footer not reachable | P1 | — | `Modal` trap + lock + restore; `.modalBody` ≤ viewport, sticky `xp-footer`, bottom sheet on phones | `ui-dialog-check` CC-* 5 viewports | FIXED |
| UI-08 | all | forms | native select arrows differed, textarea / checkbox / radio unstyled (a 36 px-high checkbox), placeholders faint, no `:disabled` / busy look | P2 | — | controls v2 block (one chevron, textarea, 16 px checkbox, placeholder contrast, focus ring, disabled, busy, spacing tokens) | — | FIXED |
| UI-09 | Studio | shell | light sidebar while Platform and Admin are dark | P2 | inconsistent | same `sidebar dark` shell | consistent | FIXED |
| UI-10 | Admin | `employees` | "B(" avatars for "Bùi Văn Đức (12)"; "—" cells that look like errors | P2 | — | unicode-safe `initials` (brackets, counters, emoji, any letter); unit / position columns left out + note while the member list has none | — | FIXED |
| UI-11 | all | sidebar footer | a long display name took 3 lines | P3 | — | one line + ellipsis | — | FIXED |
| UI-12 | Platform | `tenants` → Tạo công ty | bare multi-line select + loose search box | P2 | screenshot | header, slug from the name, searchable picker with avatars (2026-10-07) | `ui-dialog-check` 42/42 | FIXED |
| UI-13 | Admin | `organization` | tree: recursion, whole-tree re-render on a click, indentation unbounded | P1 | — | iterative builders, memoised flat rows, capped indent + level tag, collapsed start for > 300 units, scroll inside the card | 2 000 units: select 30 ms; depth 60 PASS | FIXED |
| UI-14 | Admin | `employees` (member-list fallback) | every search / page switch refetched and re-sorted all members | P1 | — | one fetch per tenant (cache + bust token), list prepared once | 10 000: page switch 0.1 ms (unit) | FIXED |
| UI-15 | Admin | `employees` | the debounce reset the chosen page on mount | P2 | page jumped back | apply only a CHANGED text | spec ST04 | FIXED |
| UI-16 | Admin | `organization`, `employees` | NOT_READY could be only a silently disabled button | P2 | — | explicit notices (read-only tree, create not ready, empty page) | org-hardening ST01–ST07 | FIXED |
| UI-17 | Studio | builder top bar / page tree | project name wrapped to 3 lines, device buttons clipped under the action buttons, pills tall, page rows 3 lines. ROOT CAUSE: `.workspace3 .topbar` (3-column grid, specificity 0,2,0) beat `.bx-top` (flex, 0,1,0), so the actions sat in a 1fr cell and overflowed to the LEFT | P2 | screenshot | the builder bar is a wrapping flex row again (`.topbar.bx-top`), one-line project name with an ellipsis (title = full name), nowrap buttons, device switch = 3 named icon buttons, section titles clamp to 2 lines | screenshots | FIXED |
| UI-18 | Studio | `templates` | chips used the dark-editor colours on a light page (axe serious contrast) | P2 | 1 serious | `.shell .chip` | 0 serious | FIXED |
| UI-19 | all | coming-soon texts | internal team names / flags in user-facing copy ("Chờ C1 (T3)", `app.tenant-ai.enabled`) | P2 | — | plain wording | — | FIXED |
| UI-20 | test | sanity spec | the JS-listener counter jumps 200 / 226 / 252 on an idle page: the unchanged baseline failed the ≤ 10 % check 2 runs of 5 | P3 | flaky | median of 5 GC'd readings (a real leak still shows) | 6/6 and 2/2 | FIXED |
| UI-21 | Studio | builder at 390 px | the canvas is squeezed to a small block under a tall stack of toolbars | P2 | screenshot | NOT changed (a layout redesign of the editor, not polish) | — | OPEN |
| UI-22 | Platform / Admin | the long tail of pages (AI, security, costs, alerts, backups…) | visual review beyond what the detector measures (spacing, hierarchy) was done on the screenshots of the main screens only | P3 | — | the shared controls / tables / dialogs / states now apply to them | — | PARTIAL |

## Typography, icons, tokens (what is now the rule)
- Font stack `Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif` (no webfont, nothing hot-linked); body 14 px / 1.5, helper 13 px, labels 13 px / 600, headings 650–700, font smoothing on; Vietnamese strings (every diacritic of the checklist) render intact — `ui-dialog-check` CC-unicode / CC-strings.
- Icons: Lucide at 14 / 16 / 18 / 20 / 22 px by context; brand logos only through `ProviderLogo`; icon-only buttons carry an `aria-label`.
- Tokens: spacing 4 / 8 / 12 / 16 / 20 / 24 / 32 (`--sp-*`), one border, radius and shadow from the existing `--f-*` set, focus ring `--f-focus`.

## Dead / stale UI (REPORT ONLY — nothing removed without the owner)
| Item | Evidence | Note |
|---|---|---|
| `components/StudioShell.tsx`, `components/app/AppEntry.tsx`, `app/[[...slug]]` | the legacy root app; `StudioShell` is used only by `AppEntry` | the three dedicated portals do not use it; owner C0 decides (the root build still compiles it) |
| `lib/mock-data.ts` | imported only by `lib/api-client.ts` (demo mode) | demo path of the legacy root app |
| `CreateUserDialog`, client `createWorkspace` / `createUser` | removed on 2026-10-08 (legacy routes) | done |
| `.xp-note` / `.noteRow` style duplicates, `README` classes | not audited for unused CSS | PARTIAL |

## Not claimed
- Breakpoints 1024 / 768 / 430 / 360 were exercised on the dialogs and the Admin screens (`ui-dialog-check`, 5 viewports) and on the "before" audit (1440 / 1024 / 768 / 390); the "after" full-route audit ran at 1440 and 390.
- A visual-regression baseline for C6 is the screenshots under `evidence/screenshots/ui-audit-2026-10-08/`.
