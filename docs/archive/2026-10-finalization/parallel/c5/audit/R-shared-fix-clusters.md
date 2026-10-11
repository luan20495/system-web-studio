> **SUPERSEDED_BY:** `docs/QA_FINAL.md` - historical document (moved from `docs/parallel/c5/audit/R-shared-fix-clusters.md`), kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# C5-R — PHASE 2: ledger verification and shared-fix clusters (2026-10-09)

Author: C5-R. Branch `agent/c5-r-review`, base `agent/c5-web @ a73ae3d`. **Documents only**: no product code, script, `package.json`, test or ledger row was changed. Squad mapping used here (from C5-L): **S1 = Studio, S2 = Platform / Admin, S3 = design system / shared UI / text, S4 = performance / tooling, R / R2 = research.** Input: `MASTER_ISSUE_LEDGER.md` + `master-ledger.json` (103 canonical issues from 230 raw) and the seven audit documents beside it.

Evidence labels: **[SRC]** read at `file:line` at base `a73ae3d` (product code is byte-identical to `9f858c2`: `git diff 9f858c2 a73ae3d -- . ':!docs'` adds only `scripts/r2-*.mjs`, so every line number of the audits still holds); **[SCRIPT]** computed by a static script that reads sources only (no browser, no port, no process); **(inference)** my reasoning, not observed. Nothing here was run in a browser or against a backend.

## 0. Summary

1. **Ledger integrity is good, the merging is mostly right.** All 230 raw ids appear exactly once, none is missing or unknown [SCRIPT]. For 99 of 103 rows the canonical severity equals the highest raw severity [SCRIPT]. I found **11 mis-merges / splits, 11 cross-row duplicates, 10 ownership problems, 3 class problems and 5 severity points** (section 1).
2. **15 shared solutions** (section 2, SC-01 … SC-15) touch **72 of the 103 canonical issues** (P1 11, P2 46, P3 15): the 59 P2 rows are 46 clustered + 13 feature-specific. The five that matter most for effort saved: **SC-01 overlay core** (4 dialog implementations → 1; M-010, M-021, M-022, M-023), **SC-02 confirm/prompt** (34 native calls; M-017, M-018, M-019, M-001), **SC-03 `useAction`** (13 copies of `act()`; M-020, M-067), **SC-04/05 error text + label maps** (10 mappers, 4 role maps; M-075, M-061, M-062), **SC-06 `LoadGate`** (28 ladders, 21 of 72 `useLoad` results never read `.error`; M-041, M-056, M-084).
3. **Fan-out (section 4)**: Wave 0 = S4 report (toolkit, baselines); Wave 1 = four lanes with disjoint files (S3 libraries + CSS, S4 hooks, S1 and S2 structure + independent rows); Wave 2 = S1 and S2 migrate their own call sites in parallel; Wave 3 = guards, bundle split, shim removal. **S3 is the critical path** (it authors 11 of the 15 shared pieces, SC-07 jointly with S4): section 4.5 proposes moving four of them to S4/S2.

## 1. Ledger verification

### 1.1 Integrity checks [SCRIPT]

| Check | Result |
|---|---|
| raw ids defined in the 7 audit documents (`S1` 59, `S2` 45, `S3` 50, `R2` 32, `R` 44) vs ids in the ledger provenance | 230 = 230; 0 missing, 0 unknown, **0 raw id in two canonical rows** (by id; semantic duplicates are in 1.3) |
| canonical severity vs the maximum raw severity | equal for 99/103. Differences: **M-067** P2 (raw R-014 P3; justified only if R-011 P2 is attached, see C-06), **M-074** P3 (raw R-002 P2, R-003 P2, no Sev note), **M-103** P3 (raw R2-026 P2, no Sev note), **M-087** P3 (raw S3-021 P2, has a note) |
| owner / class columns | checked row by row against the file owner (section 3.1) and `OWNERSHIP.md`; findings 1.4 and 1.5 |

### 1.2 Mis-merges and rows that should be split

| # | Row | Finding (evidence) | Recommended correction |
|---|---|---|---|
| C-01 | **M-010** (P1) | Three different roots under one row: **S2-004** Modal closes while the POST is in flight (`Modal.tsx:19` unconditional `onClose()`); **S1-011** backdrop `mousedown` closes drawers and builder dialogs (`drawers.tsx:32`, `builder/ui/primitives.tsx:46`); **S3-035** nested lock / stale `onClose` (`Modal.tsx:13-30`, `useEffect(…, [])` at :30). S1-011 repeats lines already counted in M-021; S3-035 is the nesting half of M-023. | Keep S2-004 in M-010 (P1). Move S1-011 to **M-021**, S3-035 to **M-023**. One shared fix (SC-01) still closes all three. |
| C-02 | **M-011** (P1) | S3-004 (project action buttons hidden ≤ 767 px, `responsive.css:182-186`) and S3-022 (≤ 900 px preview 25 % of the screen) have different fixes (an overflow menu vs a pane switch) and S3-022 is P2. | Split: M-011a P1 (hidden actions), M-011b P2 (preview space). Both S1 (`ProjectWorkspace.tsx` topbar) with CSS by S3. |
| C-03 | **M-012** (P1) | **S3-007** is not about Data sources: it lists the create-account dialog, employee dialog, tenant forms, org unit detail (`ProvisioningScreens.tsx:72,81,98,107`, `EmployeesScreens.tsx:120`, `TenantScreens.tsx:94,307`, `OrganizationScreens.tsx:168`) with `fieldset.stack` / `legend.bx-h4` (`.stack` is defined nowhere, `bx-h4` only in `builder.css`). Same family of cause (builder classes used outside the builder) but 5 other screens and P2. | Split S3-007 into **M-012b** (P2, S2 files + S3 CSS). M-012 keeps S3-001, S2-020, R-037. |
| C-04 | **M-025** | **S3-038** also contains "`aside` inside `main` in the builder (`BuilderWorkspace.tsx:195,201`)" and "whole-page `forbidden/notfound/conflict` states without h1" (that second part is **M-084**). | Keep skip link + focus on route change; move the heading part to M-084 and the builder landmark to a new P3 (S1). |
| C-05 | **M-061** (P2) | **S1-052** (workflow editor: raw step ids in targets, 5-field cron, retry in ms, principal kinds) is an editor-usability issue, not a missing label map. **S2-031** is half stale copy ("Chưa triển khai: Lưu trữ" beside a working button, `AdminApp.tsx:424`) = **M-064**. | New canonical "Workflow editor jargon" P2 (S1) for S1-052; move S2-031's stale-copy half to M-064; M-061 keeps the raw-key halves. |
| C-06 | **M-020 / M-067** | Raw **R-011** is a multi-issue row (13 `act()`, 28 load ladders, 11 filter forms, persistence copies). The ledger put all of it in M-020; **M-067** (ladders, `Field`, filters) has only R-014 (P3), so its P2 has no raw support. | Split R-011: `act()` part → M-020; ladders / filters / persistence → M-067 (then P2 is justified). |
| C-07 | **M-072** (TOOLING) | **R-023** (9 shim files, 22 importers inside `features/*`, `lib/*`, `components/*`) is C5 product code in S1/S2 files, not tooling; its fix edits other people's files. | Move to its own row owned by S4 but **executed last** (Wave 3, one repo-wide mechanical pass when no one else is editing). |
| C-08 | **M-080** (P3) | Bag: **S1-051** (restore button shown to everyone) is permission-aware control; **S1-053 + S3-043 + S2-045** are "native `<select multiple>` / `<select size=N>` list boxes". | Split into two P3 rows. |
| C-09 | **M-099** (P3) | Bag of six unrelated items (dead exports, `as never`, test ids, api monolith, `me!.workspaces[0]` crash `StudioApp.tsx:32,83`, builder rail registry). | Split by owner: dead code (S4), casts (S1 builder editors + S2 `TenantScreens.tsx:350`), crash guard (S1), api monolith (S3/S4), rail registry (S1). |
| C-10 | **M-086** | Title lists "53 % spacing off-scale, 16 radii, 11 shadows" but that is **S3-041**, which the ledger put in M-069. | Fix the title. |
| C-11 | **M-042** | Class C5 with owner "S1 (label) + C2 (renderer)". The renderer function is `lib/schema-preview.ts` = a C5 hot file (`OWNERSHIP.md` §4); the worker only imports it (`workers/render/server.ts:5`). | Owner **S1 for both halves**; C2 only reviews that the published output changes (class stays C5, add "C2 review"). |

### 1.3 Duplicates across canonical rows (same defect, different M-id)

| # | Rows | Evidence | Resolution |
|---|---|---|---|
| D-01 | M-010 (S1-011) ↔ M-021 (S1-012, S3-012) | both cite `builder/ui/primitives.tsx:46` backdrop `onMouseDown` | see C-01 |
| D-02 | M-018 (S3-050) ↔ M-019 (S1-033) | "delete runtime secret" `CodePanels.tsx:139` in both | move CodePanels / DataSourcesPanel items of M-018 to M-019 (S1 files) |
| D-03 | M-028 (S1-048) ↔ M-081 (S3-048) | radio cards that are each a Tab stop, `StudioApp.tsx:207-211` | merge S3-048 into M-028 |
| D-04 | M-028 (S2-032) ↔ M-085 (S3-039) | PersonPicker constant `aria-expanded` (`PersonPicker.tsx:34`) | merge S3-039's picker half into M-028; keep the tree half in M-085 |
| D-05 | M-061 (S2-031) ↔ M-064 (S3-030) | stale "Lưu trữ" copy `AdminApp.tsx:424` | see C-05 |
| D-06 | M-061 (S2-026, S3-025) ↔ M-062 (S3-024, R-020) | raw `WORKSPACE_ADMIN` options (`AdminApp.tsx:907,1128`) and the four role tables are the same fix (one role label source) | keep both rows, one fix (SC-05) |
| D-07 | M-041 (S1-026) ↔ M-056 (S2-014) | both: a `useLoad` result whose `.error` is never read → spinner / "…" forever. [SCRIPT] 21 of 72 `useLoad` results never read `error` (e.g. `StudioApp.tsx:107,108`, `AdminApp.tsx:206,207,766,767,1108,1109`) | one fix (SC-06); consider one row |
| D-08 | M-075 ↔ M-079 ↔ M-084 | all three change `ErrorState` / `StateView` (`packages/ui/src/ui.tsx:31-47`): server text, retry on 404, heading level | one owner (S3), one change set (SC-04 + SC-06) |
| D-09 | M-066 ↔ M-053 | both plan to split `AdminApp.tsx` (M-053 fix: "split AdminApp by portal") | M-053 depends on M-066; do not split twice |
| D-10 | M-047 ↔ M-099 / R-018 | `slugify` ×3 (`SitePanels.tsx:10`, `builder/core/pages.ts:24`, `adminModel.ts:71`); the third is an S2 file but M-047 is S1 | S1 removes its two; S2 replaces its own (or all three move to `packages/i18n`, SC-05) |
| D-11 | M-001 ↔ M-017 | M-001 (P1, cancel approves) is fixed only by the prompt dialog of M-017 (`CodeWorkspace.tsx:122`, `?? undefined`) | keep two rows, declare the dependency (SC-02) |

### 1.4 Owners that do not match the files

| # | Row | Problem | Correction |
|---|---|---|---|
| O-01 | M-018 (S2) | its S3-050 items are in Studio files (`CodePanels.tsx:139`, `DataSourcesPanel.tsx:165`) | those items → S1 (D-02) |
| O-02 | M-012 (S3 + S2) | `DataSourcesPanel.tsx` and `core/dataManagement.ts` live in `features/studio/builder` | add **S1** (API change `doc` optional / `headingLevel`), then S2 removes `as never` (`TenantScreens.tsx:350`), S3 CSS |
| O-03 | M-047 (S1) | `adminModel.ts:71` is an S2 file | D-10 |
| O-04 | M-060 / M-061 / M-062 / M-063 (S3) | hundreds of strings sit in S1/S2 files; one author editing all of them collides with the owners | **S3 authors the library + the guard; each owner applies it in own files** (SC-05) |
| O-05 | M-025 (S2/S1/S3) | three owners for one idea | S3: `SkipLink` + `useRouteFocus`; S2: `AdminApp.tsx:61-68`; S1: `StudioApp.tsx:42` |
| O-06 | M-074, M-075, M-092, M-097 | all touch `packages/api-client/src/core.ts` (`:16,18,63,73,92,96`) | **one editor: S3** for `core.ts`; S4 only `useLoad.ts` (`call()` already accepts `init.signal`, `core.ts:52`) |
| O-07 | M-033 (S2/S3), M-035 (S1) | CSS is in `factory.css:257-259`, `http.css .memberTable` (S3's files) | S3 edits CSS; S1/S2 supply markup hooks in their files |
| O-08 | M-032 (S3) | tone map lives in `ui.tsx` but misuse is in `AdminApp.tsx:863,997`, `AiSetup.tsx:218` (S2) | S3 defines the map, S2 fixes call sites |
| O-09 | M-053 (S4 + S2/S1) | S4 should only measure; the edit is in entries and registries owned by S2/S1 | S4 = measure + the final `dynamic()` pass in Wave 3 after SC-13 |
| O-10 | M-052 (S2) | edits `packages/permissions/src/index.ts:46` and `adminModel.ts:25`; shared package | acceptable if S2 is the only editor of `packages/permissions` in this phase (stated in 3.1) |

### 1.5 Class and severity

| # | Row | Finding | Correction |
|---|---|---|---|
| K-01 | M-042 | see C-11 | class C5 (+ C2 review) |
| K-02 | M-072 | mixed (tooling + C5 code) | see C-07 |
| K-03 | M-051 | mostly a C5 client guard with a C2 question; class HANDOFF understates the C5 work | keep severity, class "C5 + question to C2" |
| V-01 | **M-074** P3 | raw R-002 and R-003 are both P2 and there is no Sev note | **P2** (or add the note). Request-id loss on network / timeout / 5xx is the first thing a bug report needs (`core.ts:73`; backend sets `X-Request-Id` on every response, `RequestIdFilter.kt:21`, accepts a client id `:18`) |
| V-02 | **M-014 P1 vs M-026 / M-027 P2** | inconsistent under the ledger's own rule ("WCAG A/AA failure on a core flow = P1"): M-026/M-027 are AA failures (1.4.11) on every control of every light shell (input border 1.24:1, focus ring 2.13:1), M-014 only on one grid ≤ 394 px (`factory.css:41`) | decision for C5-L: either M-014 → P2 or M-026 / M-027 → P1; I recommend **M-014 P2** |
| V-03 | **M-076** P2 | the `DailyBars` half is latent: the backend zero-fills days with `generate_series(...)` (`AdminAiUsageController.kt:64`), so `daily` is empty only if that query changes (inference); the `AuditTable` `JSON.parse` half (`AdminApp.tsx:174`) depends on non-JSON audit values | keep P2 for the parse path, P3 for `DailyBars` |
| V-04 | M-103 | P3 hides raw R2-026 P2 | add Sev note ("plan only") |
| V-05 | **M-009** P1 (HANDOFF) | S2-003 says "needs a run on the real backend". I verified the source half: a non-member SYSTEM_ADMIN holds `platformScope = {TENANT_MANAGE, TENANT_MEMBERS}` only (`Permission.kt:109-111`), `forProject` then throws 404 `PROJECT_NOT_FOUND` when `PROJECT_READ` is absent (`AccessService.kt:85-111`) | P1 stands for that persona **by source**; keep "real backend required: Y" |

## 2. Shared-fix clusters

### 2.0 Overview

Where each piece lives: `packages/ui/src` (React, `"use client"`), `packages/i18n/src` (pure data and functions), `packages/api-client/src` (client), `tests/builder` (unit), `tests/browser` (harness). **Constraint for every new pure module:** the unit runner compiles to CommonJS and cannot load `@xweb/*` at runtime (`scripts/test-unit.mjs:2`, `tests/tsconfig.json`), so a module that a unit test imports uses relative imports and `import type` from `@xweb/*` only; the builder (`features/studio/builder/**`) imports packages by relative path for the same reason.

| ID | Shared piece | Closes (M-ids; P1 bold) | Library owner | Call-site owners | Risk |
|---|---|---|---|---|---|
| SC-01 | overlay core (`useOverlay`) under Modal / useDialog / builder Dialog | **M-010**, M-021, M-022, M-023, **M-007**(focus), **M-013**(inert) | S3 | S1 (builder Dialog, Drawer), S2 (none: `Modal` API compatible) | high: every dialog |
| SC-02 | `confirm()` / `prompt()` dialogs | **M-001**, M-017, M-018, M-019, **M-007**(discard) | S3 | S2 21 sites, S1 13 sites | medium |
| SC-03 | `useAction` (single-flight, busy, error, notice) | M-020, M-067(act), M-044, M-088(part), M-098 | S4 | S2 8 copies, S1 5 copies + 3 unguarded | medium |
| SC-04 | `errorText()` + code catalog + request id | M-075, M-074, M-079, M-092 | S3 | S1, S2 (delete 9 mappers' base text) | medium |
| SC-05 | label maps, glossary, brand constants + guards | M-061, M-062, M-063, M-060, M-064, M-032, feeds M-071 / M-103 | S3 | S1, S2 apply in own files | low code / high churn |
| SC-06 | `LoadGate` + `StateView` levels | M-041, M-056, M-084, M-079, M-067(ladders), M-099(`!`) | S3 | S2 25 ladders, S1 4 | low |
| SC-07 | `Field`, `FormError`, `useListQuery` | M-034, M-044, M-097, M-058, M-067(field), M-085(scroll) | S3 (UI) + S4 (`useListQuery`) | S2 6 files, S1 3 files | low |
| SC-08 | Toast + live announcer | **M-016**, **M-004**, M-098 | S3 | S1 (22 `setNotice`), S1 (conversation) | low |
| SC-09 | ErrorBoundary + diagnostics | **M-006**, M-074(part), M-076(mitigation) | S3 | S1 (Builder + app), S2 (apps) | low |
| SC-10 | a11y widgets: `Tabs`, `RadioGroup`, `DisclosureRow`, combobox ARIA | M-028, M-029, M-030, M-081, M-024, M-085 | S3 | S1, S2 | medium |
| SC-11 | disabled-with-reason pattern | M-031, M-057, M-009(UI) | S3 | S1, S2 | low |
| SC-12 | tokens, CSS hygiene, breakpoint set | **M-012**(css load), **M-013**(css), **M-014**, M-026, M-027, M-033, M-035(css), M-068, M-069, M-070, M-086, M-087, M-083 | S3 (all CSS except `builder.css` = S1) | S1/S2 migrate `.button`→`.btn` | high: visual |
| SC-13 | section registry + shells | M-066, M-025, M-055, M-053, M-048(Studio side) | S2 (Admin), S1 (Studio) | same | medium |
| SC-14 | `useDraft` (dirty state kept, reset on revision) | M-002, M-037, M-038, M-045 | S4 | S1 | medium |
| SC-15 | safe values: `safeHttpUrl`, secret fields, one-shot hand-over | M-050, M-051, M-088, M-091, M-049 | S1 (Studio), S2 (Admin) | – | low |

Not covered by a shared piece (feature-specific, 31 rows): M-003, M-005, M-008, M-011, M-015, M-036, M-039, M-040, M-042, M-043, M-046, M-047, M-052, M-054, M-059, M-065, M-072, M-073, M-077, M-078, M-080, M-082, M-089, M-090, M-093, M-094, M-095, M-096, M-100, M-101, M-102.

### SC-01 Overlay core (dialog, drawer, builder dialog)

**Problem [SRC].** Four implementations with different rules: `Modal` (`packages/ui/src/Modal.tsx`: scroll lock `:17`, Escape closes unconditionally `:19`, `onClose` captured once by `useEffect(…, [])` `:29-30`, first focus = first `input:not([readonly]), select, textarea, button` `:16` which lands on the invisible `srOnly` select of `Picker`, M-022); `useDialog` (`useDialog.ts`: capture-phase Escape with `stopPropagation` `:24`, latest-`onClose` ref, no scroll lock, no `inert`); builder `Dialog` (`builder/ui/primitives.tsx:28-52`: key handler on the dialog node `:47` so Escape does nothing when focus is on `<body>`, overlay `onMouseDown` closes at once `:46`, no scroll lock); `Drawer` (`drawers.tsx:32` same mousedown close). `@company/ui` is out of scope.

**Design.**
```ts
// packages/ui/src/overlay.ts  (pure React, no @xweb imports: unit/harness safe)
export type OverlayOptions = {
  onClose: () => void;                 // read through a ref: always the latest
  dismissible?: boolean;               // default true; false while busy -> Esc / backdrop / X do nothing
  initialFocus?: "auto" | "dialog" | RefObject<HTMLElement>;   // `[data-autofocus]` wins; "auto" skips tabindex=-1, [hidden], .srOnly, [inert], disabled
  lockScroll?: boolean;                // default true, reference-counted (nesting-safe)
  inertBackground?: boolean;           // default true: `inert` on siblings of the overlay root, restored to the previous value
};
export function useOverlay(o: OverlayOptions): { ref: RefObject<HTMLElement>; titleId: string; dialogProps: HTMLAttributes<HTMLElement>; backdropProps: HTMLAttributes<HTMLElement> };
```
- module-level overlay stack: only the **topmost** overlay handles Escape and the Tab trap (fixes "Esc closes all", S3-035);
- document-level `keydown` (not on the node) so Escape works with focus on `<body>`;
- `backdropProps` closes only when **pointer down and click both land on the backdrop** (fixes drag-select-then-release closes, S1-011);
- on close: restore focus to the opener, release scroll lock, restore `inert`.

Shells keep their markup and CSS and only swap the behaviour: `Modal({label,onClose,dismissible?,initialFocus?,children})` (API-compatible, 14 uses), `useDialog(title,onClose,{dismissible?})` (returns the same object shape, 10 drawers + `PublishModal`), builder `Dialog({title,onClose,dismissible?,children,footer})` (12 uses + the Admin data-sources panel).

**Where:** `packages/ui/src/overlay.ts` (new), edits to `Modal.tsx`, `useDialog.ts` (S3); `builder/ui/primitives.tsx:28-52` and `drawers.tsx:29-40` (S1, relative import `../../../../packages/ui/src/overlay`).

**Call sites to migrate:** only the two S1 shells; every other dialog stays unchanged. Features that must pass `dismissible={!busy}`: create company `TenantScreens.tsx:183`, create account `ProvisioningScreens.tsx:66`, `LinkBox` (`UserDialogs.tsx:18`, `initialFocus` on the link / Copy: M-007), `ReleaseModal.tsx:86` (already passes `null` while busy).

**Order:** (1) `overlay.ts` + unit tests with a DOM-less fake (focusable selector, stack, scroll-lock counter, `dismissible`); (2) `Modal` on it; (3) `useDialog`; (4) S1 swaps builder `Dialog` and `Drawer`; (5) delete the duplicated `FOCUSABLE` strings (`Modal.tsx:5`, `useDialog.ts:5`, `primitives.tsx:25`).

**Risk (high):** touches every dialog. Mitigation: public APIs unchanged; keep the old Modal behaviour for any option left unset except the three bug fixes; run the existing harness specs that open dialogs (`org`, `org-hardening`, `provisioning`, `release`, `builder`, `datasources`, `aiproviders`) unchanged before touching their expectations.

**Tests:** unit (`tests/builder/overlay.test.ts`): stack order, counter, selector excludes `.srOnly select`; harness `ui.spec.mjs`: Escape with focus on body, stacked Escape closes only the top one, backdrop drag-release does not close, `dismissible=false` ignores Escape and backdrop, focus restore, inert background (`document.querySelector('main').inert`), Tab loop; axe on an open dialog.
**Owners:** library S3; `primitives.tsx` + `drawers.tsx` S1 (Wave 2); nobody else edits these files.

### SC-02 `confirm()` / `prompt()` (replaces 34 native calls)

**Problem [SRC].** 34 native calls (30 `confirm`, 4 `prompt`; grep at base): `AdminApp.tsx:285,290,295,404,405,429,650,686(prompt),690,770,816(prompt),851,934,1081(prompt),1082`; `AiSetup.tsx:54,284,347`; `TenantScreens.tsx:73,124,285`; `CodePanels.tsx:134`, `CodeWorkspace.tsx:122(prompt)`, `ProjectWorkspace.tsx:196,391`, `ReleaseModal.tsx:140`, `SitePanels.tsx:49,106,138`, `StudioApp.tsx:268,319`, `drawers.tsx:95,165,169`. **Cancel on a prompt returns `null`, and `CodeWorkspace.tsx:122` turns it into "no comment" and approves** (M-001). Besides these there are destructive actions with **no** confirmation (M-018, M-019: `TenantScreens.tsx:70-76` role select, `AdminApp.tsx:785-786` cleanup, `AiSetup.tsx:57-59`, `DataSourcesPanel.tsx:165`, `CodePanels.tsx:139`, `ReleaseModal.tsx:213`, `CodeWorkspace.tsx:125,215`).

**Design (imperative, no provider)**: a module-level API that mounts one portal root on demand, so unit/harness hosts (`org-harness.tsx`, `prov-harness.tsx`) need no provider and call sites stay one line. Trade-off: the dialog is a separate React root (no React context inside it); acceptable because styling is global CSS tokens.
```ts
// packages/ui/src/confirm.tsx
export function confirm(o: {
  title: string; body?: ReactNode; consequence?: string;        // what is lost, in words
  confirmLabel: string; cancelLabel?: string;                    // verb on the button ("Gỡ khỏi công ty"), default cancel "Hủy"
  tone?: "danger" | "neutral";                                   // danger: initial focus on Cancel, danger button style
  requireText?: string;                                          // type this (name) to enable confirm: only for the worst cases (delete app, hard delete repo)
  run?: () => Promise<unknown>;                                  // keep the dialog open + busy while it runs; error shown inside (role=alert); resolves true only if run succeeded
}): Promise<boolean>;
export function prompt(o: { title: string; label: string; initial?: string; required?: boolean; multiline?: boolean; maxLength?: number; confirmLabel: string }): Promise<string | null>;  // Cancel / Esc = null; never ''
```
Built on `useOverlay` (SC-01): `role="alertdialog"` for danger, busy lock (`dismissible=false` while `run`), Enter confirms only when focus is on the confirm button (never on a free text field).
**Migration mechanic (mechanical):** `if (!window.confirm(m)) return;` → `if (!(await confirm({ title, confirmLabel, tone: "danger" }))) return;` (handler becomes `async`); `onClick={() => { if (confirm(m)) void act(f) }}` → `onClick={() => void confirm({ title, confirmLabel, run: f })}`; the four prompts → `prompt()` with an explicit null check (M-001: `if (comment === null) return;`).
**Also fixes:** `drawers.tsx:165` text "Xóa luan khỏi project?" when removing yourself (`title` is built from `m.id === me.id ? "Rời khỏi project?" : …`).

**Where:** `packages/ui/src/confirm.tsx` + CSS in `factory.css` (S3).
**Call sites / order:** Wave 2: S2 migrates 21 sites (AdminApp 15, AiSetup 3, TenantScreens 3) and adds the missing confirmations of M-018 (role select commit needs an explicit "Lưu"); S1 migrates 13 sites and adds M-019 ones (rollback `ReleaseModal.tsx:213`, discard `CodeWorkspace.tsx:125`, runtime secret `CodePanels.tsx:139`). The primary-button style of "Xóa nguồn" (`DataSourcesPanel.tsx:218`, S1-054) becomes `tone: "danger"`.
**Risk (medium):** release spec asserts that rollback is one click (`release.spec.mjs`, S1-013): update that expectation deliberately. Handlers that were synchronous become async: watch unmounted-component state updates (use SC-03).
**Tests:** unit for the pure part (title builders); harness `ui.spec.mjs` (confirm, cancel returns false, Esc, `run` busy then error, `requireText`, prompt null on cancel); `tests/builder/guard-native-dialogs.test.ts` — fs scan of `features/** packages/** components/**` for `\b(window\.)?(confirm|prompt|alert)\(` excluding comments; ratchet to 0 at the end of Wave 2 (the legacy `components/StudioShell.tsx` is excluded until C0 retires it).
**Owners:** S3 library + guard; S2 and S1 own their call sites, no shared file.

### SC-03 `useAction` (single-flight actions)

**Problem [SRC].** 13 hand-written `act()` copies — `AdminApp.tsx:398,636,664,1076,1111,1144`, `TenantScreens.tsx:66,278`, `StudioApp.tsx:244,307`, `drawers.tsx:154`, `CodePanels.tsx:122`, `SitePanels.tsx:125` — each with its own busy / error / notice state, plus unguarded submits: `StudioApp.tsx:112-127` `start()` (3× Ctrl+Enter = 3 `POST /projects`), `PagesPanel.tsx:128-146` `onSubmit` (2 `ADD_PAGE` with one revision), `AdminApp.tsx:510,920,926,1041,1144` (S2-009). A `busy` **state** lags one render, so a double click in the same tick still passes: the guard must be a **ref**.

**Design.**
```ts
// packages/ui/src/useAction.ts (S4; relative imports only)
export type Outcome<T> = { ok: true; value: T } | { ok: false; error: unknown };
export function useAction(o?: { fallback?: string; errorText?: (e: unknown) => string; scopes?: readonly string[] }): {
  run<T>(key: string, fn: (ctx: { key: string; idempotencyKey: string }) => Promise<T>, opt?: { ok?: string; onDone?: (v: T) => void; fallback?: string }): Promise<Outcome<T>>;  // never throws
  busy: boolean; isBusy(key: string): boolean;
  error: string | null; errorOf(scope: string): string | null;     // formatted with requestId through SC-04
  notice: string | null; clear(): void;
};
```
- single-flight per `key` (a ref map): a second `run` with the same key returns the in-flight promise (no second request); different keys run concurrently (TenantScreens' per-row keys `rm:<id>`, `role:<id>`);
- `idempotencyKey` stays the same until the run succeeds (intent-scoped, like `newIdempotencyKey`), so a retry after a network failure is safe;
- state is dropped silently after unmount (the in-flight request still completes; no setState on an unmounted component, S2-004's `setResult`);
- error formatting is `errorText` (SC-04), never `Error.message`.
**Migration:** replace the copy with `const a = useAction(); … void a.run("rename", () => api.x(), { ok: "Đã đổi tên.", onDone: reload })`. Per-scope error maps in `drawers.tsx:154` (`errors[scope]`) use `scopes`.
**Order:** hook + unit tests (Wave 1, S4) → S2 and S1 migrate in Wave 2, simplest first (AdminApp.tsx `act()` ×6 are identical in shape), then the unguarded submits.
**Risk (medium):** semantics of "busy" differ per screen (`busy: string|null` vs boolean vs per-key): keep `isBusy(key)`; never make a button disabled by `a.busy` globally where it was per row.
**Tests:** unit with a manual promise: second call same key returns same promise, different key runs, unmount safety, error mapping; harness: double click and Ctrl+Enter x3 produce one request (`window.__calls`) on `StudioApp` Home, `AddDialog`, cost price form.
**Owners:** S4 hook + tests; S2 / S1 call sites. Closes M-020 (P2) and, with SC-06/07, M-067.

### SC-04 `errorText()` — one place for API error → Vietnamese

**Problem [SRC].** 10 independent mappers: `errText` (`packages/ui/src/ui.tsx:21`) and a **verbatim copy** `drawers.tsx:16`; `adminErrorText` (`adminModel.ts:155`); `orgProblem` (`organizationModel.ts:204`); `provisioningProblem` (`provisioningModel.ts:79`); `explainError` (`builder/core/errors.ts:30`); `explainManagementError` (`core/dataManagement.ts:51`); `explainReleaseError` (`api-client/src/release.ts:109`); `describeRuntimeConfigError` (`runtimeConfig.ts:120`); `outcomeFromError` (`core/testMode.ts:81`). Raw text leaks: `errText` prints `Error.message` of non-`ApiError` (`ui.tsx:21`; "Unexpected token '<'…"), `core.ts:16,18,63,92` use words like "Kiểm tra backend" and "CSRF token"; `adminErrorText` appends `(${e.message})` (`adminModel.ts:155-160`). The request id is read from the JSON body only (`core.ts:73`).

**Design.**
```ts
// packages/i18n/src/errors.ts  (pure data)
export const ERROR_TEXT: Record<string, string>;      // code -> Vietnamese sentence (merged from the 6 tables above)
export const STATUS_TEXT: Record<0|401|403|404|409|422|429|500|502|503|504, string>;
// packages/ui/src/errorText.ts
export type ErrorParts = { text: string; code?: string; requestId?: string; retryable?: boolean; kind: "network"|"auth"|"forbidden"|"notfound"|"conflict"|"validation"|"rate"|"server"|"unknown" };
export function errorParts(e: unknown, o?: { fallback?: string; codes?: Record<string,string> }): ErrorParts;
export const errorText = (e: unknown, o?: …) => string;       // text + " (mã req_…)" when an id exists
```
Resolution order: feature `codes` override → `ERROR_TEXT[code]` → `STATUS_TEXT[status]` → `fallback`. **Never** `Error.message` of a non-`ApiError`; **never** server free text unless the code is in a small allow-list of "message is user-facing" codes (validation field messages) (inference from `provisioningModel.ts:114-122`). Feature mappers keep their contextual structure (field-level problems in provisioning/org) but call `errorParts` for the base text.
`core.ts` (S3, single editor): `requestId = body.requestId ?? response.headers.get("X-Request-Id") ?? sentId`; send `X-Request-Id: req_<uuid>` (accepted by `RequestIdFilter.kt:18`, CORS `SecurityConfiguration.kt:76`); `stream()` retries once on `CSRF_INVALID` like `call()` (M-092, `core.ts:96` vs `:68`); Vietnamese neutral wording at `:16,18,63,92`.
**Call sites:** delete `drawers.tsx:16`; switch `errText` users (`ui.tsx:21` is the export; 66 `errText(` call sites in `features` and `packages`) by re-pointing the export, no change at call sites; then shrink each feature table to its contextual overrides.
**Order:** `errors.ts` catalog first (merge tables, one commit) → `errorParts` + `errText` re-export → `core.ts` request id → feature mappers.
**Risk (medium):** changed strings break harness assertions that match the old text (`org.spec.mjs`, `provisioning.spec.mjs` assert error words): update those expectations in the same commit as the table merge.
**Tests:** unit table test: every code of every old table maps to the same or a better sentence; no output contains `Unexpected token`, `backend`, `CSRF`, `Error:`; `apiclient.test.ts`: header sent, id kept on timeout / 502 without JSON, stream retries once.
**Owners:** S3 (catalog, `errorText`, `core.ts`); S1 and S2 delete their copies in Wave 2. Closes M-075, M-074, M-092; feeds M-079, M-006.

### SC-05 Label maps, glossary, brand constants + guards

**Problem [SRC].** Role names in four tables with different wording: `drawers.tsx:101`, `UserDialogs.tsx:7-10` (dead), `adminModel.ts:87-88,138` (`TENANT_ROLES`, `WORKSPACE_ROLES` "Quản trị không gian làm việc"), `packages/i18n/src/index.ts:14` (`ROLE_LABEL`, only used by the dead `roleName`). Raw enums reach the screen: `AdminApp.tsx:316,348,907,997,1015,1128,1132,1191`, `ProjectWorkspace.tsx:378-381` (`MANUAL_EDIT`), `Inspector.tsx:83` (raw prop keys while `PropsForm` has `propLabel`), `WorkflowEditor.tsx`, theme options (`SYSTEM/SERIF…`). Product names in 7 files / 3 spellings (`AuthPages.tsx:21`, `StudioApp.tsx:65`, `AdminApp.tsx:139`, `i18n/src/index.ts:21-23`).

**Design.** One module `packages/i18n/src/labels.ts`:
```ts
export const LABELS = {
  workspaceRole: { WORKSPACE_ADMIN: "…", EDITOR: "…", PUBLISHER: "…", VIEWER: "…" } satisfies Record<WorkspaceRole, string>,   // exhaustive over the type union (compile-time guard)
  tenantRole, projectRole, versionKind, deploymentStatus, severity, backupState, packageStatus, themeFont, themeRadius, workflowStepKind, principalKind, …
} as const;
export function label(domain: keyof typeof LABELS, value: string | null | undefined): string;   // unknown value: humanised fallback (underscores → spaces, lower-case) + once-per-value dev warning, NEVER the raw uppercase code
export const BRAND = { product: "…", portals: { platform: "…", admin: "…", studio: "…" } } as const;  // one product name per portal
export const TERMS = { workspace: "workspace", app: "ứng dụng", … } // used by copy that is composed in code
export function slugify(s: string): string;      // the three copies of R-018 collapse here
```
The **content** of the glossary is the S3 proposal (`S3-text-and-terminology.md` §B.1, starred decisions need the product owner): this cluster only provides the single source and the guards, so a decision later is one edit.
**Guards (unit, fs-based, ratchets):** (a) `labels.test.ts`: every key of every domain exists for the runtime constants (`PERMISSION_CODES`, `ACTION_TYPES` from `packages/types/src/contract/v2`) and for the fixture values; (b) `guard-terms.test.ts`: denylist of variants (`xoá`, `huỷ`, `tuỳ`, `khoá`, "Admin Console", "AI Control", "Backend provisioning", raw config names `OPENROUTER_API_KEY`, `OIDC_ENABLED`, `SCIM_TOKEN`, `BACKUP_STATUS_DIRS`, `app.data-platform.enabled`) in string literals under `features/**` with a **ratchet** (current count recorded, may only decrease); (c) no `{x.status}` rendered raw is not machine-checkable: rely on (a) + review.
**Call sites:** S2 applies in `AdminApp.tsx` (8 sites above), `AiSetup.tsx`, `adminModel.ts`, `provisioningModel.ts`; S1 in `ProjectWorkspace.tsx`, `Inspector.tsx`, `WorkflowEditor.tsx`, `drawers.tsx`, `ReleaseModal.tsx`, `readiness.ts`; S3 in `packages/auth/src/AuthPages.tsx` and `packages/ui`. The 47 tone-mark rows (S3-text B.3) are applied by each owner in own files.
**Order:** (1) `labels.ts` + `slugify` + tests, exports of the old names kept as re-exports; (2) owners swap call sites file by file; (3) delete the three duplicate role maps and the dead exports; (4) guards ratchet to the final count at Wave 3.
**Risk:** low in code, **high churn** (100+ strings in files owned by S1/S2): never one author for all files (O-04). A text change breaks harness assertions that match strings: update with the owner's commit.
**Owners:** library + guards + glossary doc S3; application S1 / S2 per file. Closes M-061, M-062, M-063, M-060, M-064(part), M-032 (tone map), and gives M-071 / M-103 a phase-1 table to extend.

### SC-06 `LoadGate` + `StateView`

**Problem [SRC].** 28 identical ladders `error ? <ErrorState> : loading && !data ? <StateView loading> : empty ? … : table` (`AdminApp.tsx:183,255,276,330,342,384,395,477,526,536,592,615,631,676,729,738,831,873` (18), `AiSetup.tsx:64,213,244,342`, `StudioApp.tsx:167,292,335,389`, `OrganizationScreens.tsx:83`, `EmployeesScreens.tsx:78`) and 90 non-null `data!` after them; **21 of 72 `useLoad` results never read `error`** [SCRIPT] (list in section 1.3 D-07 and `loads.mjs` output), which is M-041 / M-056 (spinner or "…" forever). `StateView` defaults to `h2` (`ui.tsx:36`) → pages with only a state have no `h1` (M-084); `ErrorState` offers "Thử lại" on a 404 and shows "Không tìm thấy" twice (M-079).

**Design.**
```tsx
// packages/ui/src/LoadGate.tsx
export function LoadGate<T>(p: {
  state: { data: T | null; error: unknown; loading: boolean; reload: () => void };
  isEmpty?: (d: T) => boolean; empty?: ReactNode;                // e.g. <StateView kind="empty" title="Chưa có …"/>
  variant?: "block" | "inline";                                  // inline: one line "Không tải được · Thử lại" for KPIs, secondary cards (M-041, M-056)
  level?: 1 | 2;                                                 // default 2; a page renders its <PageHead> (h1) first, the gate never creates a second h1
  children: (data: T) => ReactNode;                              // data is non-null here: no `!`
}): JSX.Element;
export function useLoadAll(...s: LoadState[]): LoadState;        // first error wins; loading while any loads
```
`ErrorState` changes (same owner): no retry button when `stateOf(e)` is `notfound` / `forbidden` (nothing to retry), one title, `errorText` (SC-04) for the detail.
**Call sites:** S2 25 (AdminApp 18, AiSetup 4, Org/Emp 2, plus the 9 secondary loads of D-07 in `AdminApp.tsx:206,207,392,506,707,766,767,1108,1109` which become `variant="inline"`); S1 4 + `StudioApp.tsx:107,108,183,280`, `libraryPanels.tsx:12,42`.
**Order:** `LoadGate` + `ErrorState` fix (S3) → S2/S1 migrate screen by screen; start with the 6 secondary loads that show spinners forever.
**Risk (low):** markup-neutral for the block variant. **Tests:** SSR unit (loading / error / empty / data / inline); harness: failing secondary load shows the inline error + retry (Builds, Identity, Home KPIs); axe `page-has-heading-one` on `/platform/tenants/zzz`.
**Owners:** S3 component; S2 / S1 call sites. Closes M-041, M-056, M-084, M-079; with SC-03/07 closes M-067; removes the `data!` that follows each ladder (the repo has 90 non-null `!`, 39 in `AdminApp.tsx`; how many are `data!` I did not count).

### SC-07 `Field`, `FormError`, `useListQuery`

**Problem [SRC].** Two `Field` components with different props (`drawers.tsx:41` ReactNode children; `builder/ui/primitives.tsx:86` function children) plus 48 hand-written `<label className="field">` in 6 Admin files (AiSetup 11, EmployeesScreens 8, OrganizationLive 1, OrganizationScreens 5, ProvisioningScreens 13, TenantScreens 10). Server errors render below the sticky footer (`TenantScreens.tsx:208`, `ProvisioningScreens.tsx:112`: M-034) or only as a toast (`PagesPanel.tsx:92,98,104`: M-044). Paged lists repeat `useState(page)`+`setPage(0)` (21 occurrences, 8 `<Pager>` consumers: `AdminApp.tsx:266,333,384,485,624,700,729`, `StudioApp.tsx:169`); typing triggers one request per key (`TenantScreens.tsx:35-48`, M-097).

**Design.**
```tsx
// packages/ui/src/Field.tsx — superset of both existing Fields
<Field label hint? error? required?>{(a11y: { id; "aria-describedby"?; "aria-invalid"? }) => <input {...a11y}/>}</Field>   // children may also be a plain node (drawers.tsx callers)
// packages/ui/src/FormError.tsx — role="alert", id for aria-describedby, scrollIntoView({block:"nearest"}) on mount, scroll-margin-bottom above the sticky footer
<FormError error={e} />   // or message=
// packages/ui/src/useListQuery.ts (S4)
const q = useListQuery({ size: 25, filters: { status: "all" }, debounceMs: 250 });
//   q.page, q.setPage, q.text/setText (immediate), q.query (debounced), q.filters, q.setFilter(k,v) (resets page), q.submit()
```
Do **not** abstract table markup or the inline "add / save" forms that also carry `className="filters wrap"` (`AdminApp.tsx:942,952,964,1050,1088,1097,1125,1151,1172` are submit-an-action forms, verified at those lines, not list filters) (R-architecture §1.4: only extract state, not markup).
**Call sites:** `Field`: S2 migrates opportunistically when a dialog is touched for SC-02/03 (no forced sweep); S1 replaces its two imports. `FormError`: S2 `TenantScreens.tsx:208`, `ProvisioningScreens.tsx:112`; S1 `PagesPanel.tsx` (3 dialogs). `useListQuery`: S2 7 lists, S1 `StudioApp.tsx:165`.
**Risk (low).** **Tests:** SSR (label `for`, `aria-describedby`); harness: error visible without scrolling at 390 × 640 with the sticky footer; 9 keystrokes → 1 request.
**Owners:** S3 `Field`/`FormError`; S4 `useListQuery`; S1/S2 own migration. Closes M-034, M-044, M-097, M-058(client half), part of M-067 and M-085 (`scroll-padding-bottom` in CSS, S3).

### SC-08 Toast + live announcer

**Problem [SRC].** `<button className="toast">` inserted already containing its text: not announced, no timeout, no dismiss, replaced silently, `z-index:70` under the builder overlay (80) (`ProjectWorkspace.tsx:375`, `CodeWorkspace.tsx:240`; M-016). The AI conversation has no `role=log` and its progress `role=status` text changes per chunk (`AiProgress.tsx:13`, `aiProgressModel.ts:53-54`; M-004).
**Design (imperative, like SC-02).**
```ts
// packages/ui/src/toast.tsx
export const toast: { success(msg: string, o?: Opt): void; info(msg: string, o?: Opt): void; error(msg: string | unknown, o?: Opt & { requestId?: string }): void };
type Opt = { id?: string /* replaces the same id instead of stacking */; persist?: boolean };
export function announce(msg: string, politeness?: "polite" | "assertive"): void;   // writes into one always-mounted visually-hidden live region
```
Region: a body-level container always present (`role="status" aria-live="polite"` for success/info; errors use `role="alert"`), max 3 stacked, success/info auto-dismiss 6 s (pause on hover/focus), **errors persist** with a "Đóng" button, `z-index` from the token scale above overlays, `bottom` offset that does not cover `.xp-footer` (safe-area aware). `announce()` is for the conversation: one polite message per assistant reply, not per chunk (M-004), plus a separate silent typing indicator.
**Call sites:** S1 only: `ProjectWorkspace.tsx` (11 `setNotice`, `notice` state :78, render :375), `CodeWorkspace.tsx` (11, :66, :240), and the `onError={(e) => setNotice(errText(…))}` props of drawers (`ProjectWorkspace.tsx:395-396`). S2 adopts it for M-098 (copy failure) in `UserDialogs.tsx:15-16`.
**Risk (low)**; **tests:** harness live-region assertion (message text appears in the region after trigger; error persists; dismiss; overlay-over-toast order); axe.
**Owners:** S3 component; S1 migrates the two files in one commit. Closes M-016, M-004, M-098.

### SC-09 ErrorBoundary + diagnostics

**Problem [SRC].** No `error.tsx`, `global-error.tsx`, `not-found.tsx` or `ErrorBoundary` in `apps/*/app`, `app/`, `packages/auth/src/PortalApp.tsx:15-17` (M-006); data-dependent crashes exist today (`AdminApp.tsx:174` `JSON.parse`, `:600`), so a white portal is reachable; the Builder loses a pending edit.
**Design.**
```tsx
// packages/ui/src/ErrorBoundary.tsx  (class component)
<ErrorBoundary scope="portal" | "builder" | "section" resetKeys={[pathname]} onError={(error, info) => reportClientError(error, { scope })}>…</ErrorBoundary>
export function ErrorFallback(p: { error: unknown; reset: () => void; scope: string }): JSX.Element;   // h1/h2, "Thử lại", "Về trang chính", last request id, "Sao chép chẩn đoán"
// packages/api-client/src/diagnostics.ts : recentFailures(): { when; method; path; status; code; requestId }[]  (ring buffer of 20, no bodies, no tokens)
```
`PortalApp` wraps `render(seg)` in a `portal` boundary with `resetKeys=[pathname]`; `apps/<app>/app/error.tsx` and `global-error.tsx` (6 lines each, `global-error` must render `<html><body>`) use `ErrorFallback`; `ProjectWorkspace.tsx` wraps `<BuilderWorkspace>` in a `builder` boundary — its pending edit is `failedEdit` state held **outside** `BuilderWorkspace` (`ProjectWorkspace.tsx:183-194`), so reset keeps it.
**Owners:** S3 component + `PortalApp.tsx` + diagnostics; S2 adds the two app files in `apps/platform`, `apps/admin`; S1 adds `apps/studio` files and the Builder boundary. **Risk (low).** **Tests:** SSR fallback; harness page that throws on click (fallback, focus on heading, "Thử lại" re-mounts, id shown); `reportClientError` ring buffer unit test. Closes M-006; supplies the id for M-074; mitigates M-076.

### SC-10 a11y widgets: `Tabs`, `RadioGroup`, `DisclosureRow`, combobox ARIA

**Problem [SRC].** Seven ad-hoc tablists (`AdminApp.tsx:233,407,581-584`, `AiSetup.tsx:38`, `StudioApp.tsx:164,285`, `CodeWorkspace.tsx:208`) with `role=tab` on `<a>`/`<button>` and no tabpanel/arrow keys, while a correct `Tabs` exists only inside the builder (`builder/ui/primitives.tsx:59`); radio-like cards that are each a Tab stop (`StudioApp.tsx:207-211`) and class-only choice buttons (`ReleaseModal.tsx:186-188`); audit rows that expand on mouse click only (`AdminApp.tsx:168-174`); `Picker.tsx:38` vs `:42` activedescendant; constant `aria-expanded` (`PersonPicker.tsx:34`).
**Design.** Add to `packages/ui`: `Tabs`/`TabPanel` (same API as the builder's, plus `TabLinks` = plain `<nav aria-label>` links with `aria-current` when the tabs are routes: `UsersPage` tabs are navigation, so they should **not** be `role=tab`); `RadioGroup({label, value, onChange, options})` (`role=radiogroup`, roving tabindex, arrows); `DisclosureRow({summary, children})` (first-cell `<button aria-expanded aria-controls>`); `Picker` fix. The builder keeps its own `Tabs` until S1 chooses to import the shared one (no forced change in `builder/ui/primitives.tsx`).
**Call sites:** S2 (AdminApp 4 tablists, AiSetup 1, audit row), S1 (StudioApp 3, CodeWorkspace 1, ReleaseModal 1). **Risk (medium)**: CSS classes `.tabs`/`.active` currently style the ad-hoc ones — keep the class names on the new markup. **Tests:** axe + harness keyboard (arrows, Home/End, roving tabindex); a unit check that `TabLinks` has no `role="tab"`.
**Owners:** S3 library + `Picker`; S2 / S1 call sites. Closes M-028 (with D-03/D-04), M-029, M-030, M-081, M-024, part of M-085.

### SC-11 Disabled-with-reason

**Problem [SRC].** 33 disabled controls explained only by `title=` (`BuilderTopBar.tsx:37-38`, `ProjectWorkspace.tsx:279,298-299`, `ReleaseModal.tsx:212-223`, `EmployeesScreens.tsx:164`, `DataSourcesPanel.tsx:151-153,187,203-204`, `TestPanel.tsx:153-175`, `AdminApp.tsx:304`): invisible on touch, not focusable, not announced (M-031); `EmployeesScreens.tsx:164` is enabled and has no handler (M-057).
**Design.** `ReasonButton({ reason, …buttonProps })`: when `reason` is set the button is `aria-disabled="true"` (stays focusable, click is a no-op) and renders the reason as visible helper text linked by `aria-describedby` (or a `Hint` below in toolbars; a toolbar variant shows it in a popover on focus/tap). `useReason()` helper maps `whyNot(...)` (`builder/core/permissions.ts`) to props.
**Owners:** S3 component; S1 (Builder top bar, release, test panel), S2 (employees, admin) apply. **Risk (low).** **Tests:** harness: Tab reaches the control, reason read via `aria-describedby`, click does nothing.

### SC-12 Tokens, CSS hygiene, breakpoint set

**Problem [SRC/SCRIPT from S3, R2].** 386 hex literals (191 unique), 249 with no token; two vocabularies (`--f-*`, dark `--bg…`); `--sp-*` defined with 0 uses; `--danger`/`--warn` used but undefined; 93 selectors defined twice (40 conflicting); four button systems (`.btn` 188, `.smallButton` 65, `.button` 61, `.bx-btn` 51); 14 `@media` thresholds; `factory.css` single-line blocks. Focus ring `#67b8ff` 2.13:1, control borders 1.24-1.48:1 (M-026/M-027); `.grid2 minmax(380px,1fr)` (`factory.css:41`, M-014); `builder.css` is not loaded by `apps/admin|platform/app/layout.tsx` while `DataSourcesPanel` uses `bx-*` (M-012).
**Design (order matters, each step visually neutral until stated):**
1. `packages/ui/src/styles/tokens.css` — the only place with raw colours: surface / text / muted / accent / status (+ `-bg`), **`--f-control-border` ≥ 3:1**, **`--f-focus` ≥ 3:1 per surface (light, dark)**, `--sp-*`, `--r-sm/md/lg`, `--shadow-1/2`, font-size scale, **z-index scale** (`--z-nav`, `--z-overlay`, `--z-popover`, `--z-toast` above overlays), `color-scheme`. Imported first by the three `layout.tsx` files (S2 for platform/admin, S1 for studio: one-line import each).
2. `packages/ui/src/breakpoints.ts` — documented set `{ nav: 900, phone: 760, sheet: 600 }` + comment block in `responsive.css`; **a CSS guard test** (`tests/builder/guard-css.test.ts`, fs-based): allowed `@media` thresholds, no raw hex outside `tokens.css` (ratchet), no `var(--x)` without a definition, no selector defined twice (ratchet 93 → 0).
3. Replace repeated hex by tokens (ratchet), define the missing tokens, fix `minmax(min(380px,100%),1fr)`, `visibility:hidden` for the closed drawer (M-013), the `.stack`/`bx-h4` dialog classes (M-012b), provider row wrap (M-033), card layout of `.memberTable` ≤ 600 (M-035).
4. One `.btn` vocabulary: map `.button` → `.btn` and `.smallButton` → `.btn.sm`; S1 / S2 migrate their markup, then delete the old rules.
5. Load builder styles for Admin data sources by moving the `bx-*` rules the panel needs into a scoped file loaded by every portal (`panel.css`), not by importing `builder.css` (dark idiom) into the light shells.
**File ownership:** every CSS file S3 **except `builder.css` = S1** (M-036, M-037 etc. need it); S1/S2 never edit a CSS file they do not own — they send a request in the cluster channel.
**Risk (high):** visual regressions. Mitigation: step 1-2 land with **zero** visual diff (computed-style script `scripts/r2-css-count.mjs` / `r2-contrast.mjs` from R2 already exist as the measuring tools), `ui-audit.mjs` screenshots at 1440/390 before and after each step, axe 0 critical / 0 serious.
**Tests:** CSS guard test; contrast script in CI-less form (run by S3, numbers in the PR); `ui-dialog-check.mjs` at 5 viewports. Closes M-012(css), M-013(css), M-014, M-026, M-027, M-033, M-035(css), M-068, M-069, M-070, M-086, M-087, M-083 (nowrap).

### SC-13 Section registry + shells

**Problem [SRC].** Adding an Admin / Platform page edits five registries that must agree: `NAV` (`AdminApp.tsx:24`), `COMING` (:30), `OWNED` (`base.ts:23`), `SYSTEM_ONLY` (`adminModel.ts:45`), `route()` (:74-112); console identity is module state set during render (`base.ts:12-17`, `AdminApp.tsx:54`); the two portals ship the same 942 KB (M-053). Studio has `TITLES` (:26), nav (:62), `route` (:48) and 8 screens in one file (`StudioApp.tsx:104-394`); `ProjectWorkspace.tsx:45-402` is one 357-line function (M-048).
**Design.** `features/admin/console/sections.ts`: `SECTIONS: { key; label; icon; portals: ("platform"|"admin")[]; access: "all"|"system"|"tenant"|"workspace"; title; load: () => Promise<{default: ComponentType}> }[]` plus `AdminPortalContext`; `navFor`, `owns`, `sectionAccess`, `route` are derived from it; a unit test: every nav key routes, every route has a title, `owns` equals the table. A shared `<Shell>` is **not** proposed (two shells differ: do not over-abstract); the shared parts are `SkipLink` (`packages/ui`, S3), `useRouteFocus()` (focus `h1`/`main` and reset scroll on pathname change), `main` `tabIndex` only while `useOverflow` (M-025). Studio: `screens/*.tsx` split + `useProjectData`, `useGuardedRun` (uses SC-03), `useAiStream`, pure `noticeFor(e)`, `blockToOps` as in `R-architecture.md` §1.3 (line ranges there remain valid).
**Order:** S2: (1) pure split of `AdminApp.tsx` by the section table in `R-architecture.md` §1.3 (14 files, one commit each) → (2) registry + context → (3) `dynamic()` per section (M-053, Wave 3) → (4) M-055 real 404 state. S1: (1) `StudioApp` screens split → (2) `ProjectWorkspace` hooks.
**Owners:** S2 `features/admin/**`, `apps/platform`, `apps/admin`; S1 `features/studio/**`, `apps/studio`; S3 `SkipLink`, `useRouteFocus` (new files). **Risk (medium):** pure moves; verify with `tsc`, `ui-audit.mjs --only platform,admin` route and axe counts equal before/after.

### SC-14 `useDraft`

**Problem [SRC].** Four faces of "local edit state is not owned correctly": Inspector form remounted by a key made of the section id **and** `JSON.stringify(props)` (`Inspector.tsx:43,53`) so typing is lost on selection change (M-002); only the selected rail panel is mounted (`BuilderWorkspace.tsx:168-182`) so half-filled data / action / workflow editors vanish (M-037); `MenuEditor` / `NotFoundEditor` / `ThemePanel` copy the document into `useState` once so "Lưu menu" re-adds a deleted page (`PagesPanel.tsx:174,207`, `MiscPanels.tsx:36`; M-038); code drafts have no navigation guard (`CodeWorkspace.tsx`; M-045); the only unload guard is `ProjectWorkspace.tsx:189-194`.
**Design.** `useDraft<T>(source: T, revisionKey: unknown, opts?: { equal? })` → `{ value, set, dirty, reset }`: starts from `source`, **keeps** the edit while `dirty`, resets only when `revisionKey` changes **and** the draft is not dirty (otherwise offers "Tải lại bản mới" via the caller); registers into a small `DirtyRegistry` so one `beforeunload` / in-app navigation guard (`useUnsavedGuard`) replaces the ad-hoc one. Panels stay mounted but `hidden` (S1 choice) or lift their draft into the registry; the Inspector keys on `section.id` only.
**Owners:** S4 hook + tests (pure, no UI); S1 applies in `Inspector.tsx`, `BuilderWorkspace.tsx`, `panels/*`, `CodeWorkspace.tsx`, `ProjectWorkspace.tsx` (all S1 files). **Risk (medium):** state ownership changes inside the Builder: guard with the existing `builder.spec.mjs` plus new cases (type, select another section, come back; switch rail tab and back; delete a page then save the menu). **Tests:** unit for `useDraft` (dirty kept across `source` change, reset when clean); harness cases above. Closes M-002, M-037, M-038, M-045.

### SC-15 Safe values: URLs, secrets, hand-over

**Problem [SRC].** URL values from the API used as-is: `StudioApp.tsx:372-382` (`path` unvalidated, `r.redirect` → `window.location.assign`), `drawers.tsx:94`, `ReleaseModal.tsx:170,202`, `CodeWorkspace.tsx:221` (M-051); the IDE clone token shown in clear twice and as `null` (`CodePanels.tsx:98,103-105`, M-050); credential / token left in React state after success (`AdminApp.tsx:1141-1157`, `AuthPages.tsx:32-87`, M-088); `studio-ws` / `studio-ai-model` survive logout (`StudioApp.tsx:31,34`, M-091); `?prompt=` auto-sends an AI call on load (`ProjectWorkspace.tsx:173-178`, `StudioApp.tsx:117`, M-049).
**Design.** `safeHttpUrl(url, { origin?: string; protocols?: ("https:"|"http:")[] }): string | null` and `samePathOnly(p)` (pure, in `packages/ui/src/safe.ts`, S3 writes it; trivially small); `<SecretField>`-style pattern (value cleared on success, mask + Copy + auto-hide timeout) kept as a **local** component per feature (S1 `CodePanels`, S2 `AdminApp` connectors) because the two have different lifecycles; one-shot hand-over of the AI prompt through `history.state` / a module variable consumed once and **never acted on without a click**; clear `studio-*` keys in `logout` (`packages/auth/src/session.tsx:36-40`, S3).
**Owners:** S3 `safe.ts` + logout; S1 `CodePanels`, `StudioApp`, `ProjectWorkspace`, `drawers`, `ReleaseModal`, `CodeWorkspace`; S2 `AdminApp` connectors, `AuthPages` (S3). **Risk (low).** **Tests:** unit for `safeHttpUrl` (javascript:, protocol-relative, other origin); harness: reload with `?prompt=` sends nothing; the token field is masked and cleared.

## 3. File ownership (no file is edited by two people)

### 3.1 Matrix

| Scope | Owner | Notes |
|---|---|---|
| `features/studio/**`, `apps/studio/**`, `lib/schema-preview.ts`, `components/SectionInspector.tsx`, `components/useDialog.ts`, `features/studio/builder/**` incl. `builder.css` | **S1** | `builder.css` is the only CSS file outside S3 |
| `features/admin/**`, `apps/platform/**`, `apps/admin/**`, `packages/permissions/**` (M-052) | **S2** | `features/admin/UserDialogs.tsx` (S2) holds `LinkBox`; `features/library.tsx` (shared thumbnails) is untouched in this phase |
| `packages/ui/src/**` **except** the hook files below, `packages/ui/src/styles/*.css` **except** `builder.css`, `packages/i18n/**`, `packages/auth/**`, `packages/api-client/**` | **S3** | `packages/ui/src/index.ts` is S3 only: S3's scaffold commit (Wave 1, first) adds the export lines for **all** planned new files (`overlay`, `confirm`, `toast`, `ErrorBoundary`, `LoadGate`, `Field`, `FormError`, `Tabs`, `RadioGroup`, `DisclosureRow`, `ReasonButton`, `SkipLink`, `safe`, `./hooks`) with stub bodies so nobody else needs to touch it |
| `packages/ui/src/{useAction,useLoad,useListQuery,useDraft,usePersistentState,hooks}.ts`, `tests/browser/lib/**`, `tests/browser/build-harness.mjs`, perf scripts under `tests/` | **S4** | `hooks.ts` re-exports S4's hooks; the one `export * from "./hooks"` line is in S3's scaffold |
| `tests/builder/*` | owner of the module under test | new files per cluster (`overlay.test.ts`, `confirm.test.tsx`, `errors.test.ts`, `labels.test.ts`, `guard-*.test.ts`, `useaction.test.ts`, `usedraft.test.ts`): one author per file |
| `tests/browser/{ui,admin,studio}-harness.tsx` + specs | **S3** (ui), **S2** (admin), **S1** (studio) | S4 registers the three entries in `build-harness.mjs` in Wave 0 (stubs), nobody else edits it |
| `scripts/**`, `package.json`, `package-lock.json`, `tsconfig.json`, `next.config.ts`, `scripts/test-classify.mjs` HELPERS list | **C0** | one batched request from S4 in Wave 0 (esbuild devDependency, HELPERS entries for the three harness pages); nothing else needed by the clusters |
| `docs/parallel/c5/audit/**` | each author for own file; ledger `MASTER_ISSUE_LEDGER.md` = C5-L | corrections of section 1 are C5-L's to apply |

### 3.2 Files with the most M-ids (each has ONE editor)

`features/admin/AdminApp.tsx` (S2): M-017 ×15 sites, M-018, M-020, M-025, M-028, M-029, M-031, M-032, M-034, M-054..57, M-060..64, M-066, M-076 — **all S2, sequential in one branch; the split (M-066) goes first.** `features/studio/ProjectWorkspace.tsx` (S1): M-004, M-011, M-016, M-017 ×2, M-031, M-036, M-040, M-043, M-048, M-049, M-078 — S1 only. `packages/api-client/src/core.ts` (S3): M-074, M-075, M-092, SC-04 — S3 only. `packages/ui/src/ui.tsx` (S3): `errText`, `StateView`, `Pill` tone map, `fmtDate` (M-097) — S3 only.

## 4. Wave plan (P2 queue; P1 rows that share the same pieces are marked)

Wave numbers are dependency layers, not calendar weeks. Within a wave every owner works on disjoint files (section 3).

### 4.1 Wave 0 — gate: S4 reports (prerequisite for all)

| Item | Owner | Files | Output |
|---|---|---|---|
| M-072 (toolkit part): `tests/browser/lib/spec.mjs` (check / results / console + pageerror capture / axe / CHROME resolution / refuses to run without `HARNESS_URL`), three harness entries registered (`ui`, `admin`, `studio`) | S4 | `tests/browser/lib/**`, `tests/browser/build-harness.mjs` | every later test plan has a place to land |
| baselines for M-046 (rect re-renders), M-053 (bundle sums per portal; the script used in `R-architecture.md` §0), M-097 (request counts) | S4 | measurement scripts under `tests/` | before-numbers |
| batched C0 request (esbuild devDependency, HELPERS entries, `test:unit` outDir) | S4 → C0 | – | unblocks R-028 / R-030 |

### 4.2 Wave 1 — libraries and structure (four lanes, disjoint)

| Lane | M-ids | Owner | Files (exclusive) | Depends on |
|---|---|---|---|---|
| L1-S3a overlay + feedback | M-023, M-022, M-024; P1 on the same pieces: **M-010, M-016, M-006, M-007 (focus), M-013** | S3 | `packages/ui/src/{overlay,Modal,useDialog,confirm,toast,ErrorBoundary,NavDrawer,Picker}.ts(x)`, `packages/auth/src/PortalApp.tsx`, `packages/ui/src/index.ts` | W0 harness entry `ui` |
| L1-S3b text / state / form / CSS | M-075 (lib), M-084 + M-079 (`ErrorState`/`StateView`/`LoadGate`), M-061·M-062·M-063·M-060 (lib + guard + glossary doc), M-032 (tone map), M-028 / M-029 / M-030 / M-081 (widgets), M-031 (`ReasonButton`), M-025 (`SkipLink`), M-026, M-027, M-033, M-068, M-069, M-070, M-086, M-087; P1: **M-012 (css), M-014** | S3 | `packages/ui/src/{ui,LoadGate,Field,FormError,Tabs,RadioGroup,DisclosureRow,ReasonButton,SkipLink,safe}.tsx`, `packages/ui/src/styles/*.css` (not `builder.css`), `packages/i18n/**`, `packages/api-client/**`, `packages/auth/src/AuthPages.tsx` | L1-S3a for `index.ts` scaffold (first commit); serial after S3a for the same person |
| L1-S4 hooks | M-020 (lib), M-097 (`useLoad` abort, `useListQuery`), M-002/M-037/M-038/M-045 (lib `useDraft`) | S4 | `packages/ui/src/{useAction,useLoad,useListQuery,useDraft,usePersistentState,hooks}.ts` + tests | S3 scaffold line for `./hooks` |
| L1-S1 Studio structure + independent rows | M-048 (split `StudioApp` screens, `ProjectWorkspace` hooks), M-047 (SitePanels onto `core/pages.ts`), M-043, M-049, M-050, M-051 (client guard), M-036 (`builder.css`) | S1 | `features/studio/**` not in the Wave-2 list, `builder.css` | none |
| L1-S2 Admin structure + independent rows | M-066 (split + registry + context), M-052, M-054, M-059 (label only), M-064 (copy), M-076 (safe parse) | S2 | `features/admin/**` (structure), `packages/permissions/**` | none |
| tracks without code | M-071 (R plan), M-073 (C0), M-065 (decision by C5-L), M-039 / M-040 / M-051 server half (questions to C2) | R, C0, L | docs | – |

### 4.3 Wave 2 — call-site migration (S1 and S2 in parallel; each only in own files)

| Owner | M-ids | Files | Needs from Wave 1 |
|---|---|---|---|
| **S2** | M-017 (21 sites), M-018, M-020 (8 `act()` + 5 unguarded submits), M-025 (shell), M-028 (5 tablists), M-029, M-031, M-032, M-034, M-055 (after the registry), M-056, M-057, M-058 (client part), M-060 / M-061 / M-062 / M-063 (Admin strings), M-067 (Admin ladders / `Field`), M-075 (delete 5 mappers), `apps/platform|admin` `error.tsx` + `global-error.tsx` + `tokens.css` import, M-012b (admin dialog classes), `TenantScreens.tsx:350` `as never` after S1's panel change | `features/admin/**`, `apps/platform/**`, `apps/admin/**` | SC-01, 02, 03, 04, 05, 06, 07, 10, 11, 12 |
| **S1** | M-017 (13 sites), M-019, M-001 (P1, prompt), M-020 (5 copies + 3 unguarded), M-021 (builder Dialog + Drawer on the overlay core), M-004 + M-016 (announce, toast), M-028 / M-030 / M-081 (StudioApp, CodeWorkspace, ReleaseModal), M-031, M-035 (markup), M-037, M-038, M-045, M-002 (P1), M-041, M-044, M-060 / M-061 / M-062 / M-063 (Studio strings), M-067 (Studio ladders), M-075 (delete 4 mappers), `DataSourcesPanel` `doc` optional (first, for S2), `apps/studio` files | `features/studio/**`, `apps/studio/**`, `builder.css` | SC-01, 02, 03, 04, 05, 06, 08, 09, 10, 11, 14, 15 |

Order inside Wave 2 per owner: (1) cheapest, mechanical, most files (SC-02 confirm sites, SC-06 ladders), (2) behaviour (SC-03 `act()`, SC-14 drafts), (3) strings (SC-05) last, because each string change can break a harness assertion; update the assertion in the same commit.

### 4.4 Wave 3 — tighten and measure

| Item | Owner | Files |
|---|---|---|
| guards to zero / final ratchets: `guard-native-dialogs`, `guard-terms`, `guard-css`, `labels` | S3 | `tests/builder/guard-*.test.ts`, `packages/i18n` |
| M-053 `dynamic()` per section and per heavy panel; bundle before/after | S4 (measure) with S2 (`features/admin/console/**`) and S1 (`features/studio/**`) editing only their own entries, after SC-13 | – |
| M-072 (shims): delete the 9 shim files after rewriting the 22 importers to `@xweb/*`, one repo-wide mechanical pass when S1 / S2 / S3 have merged | S4 | `features/ui.tsx`, `session.tsx`, `routing.ts`, `useLoad.ts`, `auth/AuthPages.tsx`, `admin/Modal.tsx`, `components/useDialog.ts`, `lib/http-api.ts`, `lib/http-types.ts` and their importers |
| M-046, M-097 re-measure; M-068 delete the old `.button` / `.smallButton` rules after both owners migrated | S4 / S3 | – |

### 4.5 Where the plan is thin, and how to rebalance

S3 authors 11 of the 15 pieces (SC-01, 02, 04, 05, 06, 07-UI, 08, 09, 10, 11, 12) and is the critical path. If S3 cannot finish Wave 1 in time, move **SC-06 `LoadGate`** and **SC-07 `Field`/`FormError`** to S2 (S2 uses them most: 25 of 29 ladders, 6 of 6 Admin form files) as new files under `packages/ui/src` (no file is shared with S3), and **SC-15 `safe.ts`** to S1. The P1 items on the same pieces (M-006, M-010, M-016, M-007, M-013, M-012, M-014) should be done first inside S3's lane because they are the reason the pieces exist.

### 4.6 P2 queue: every P2 row exactly once

| M-id | Wave | Owner | M-id | Wave | Owner |
|---|---|---|---|---|---|
| M-017 | 1 lib → 2 | S3 → S2/S1 | M-046 | track (W0 base, W3 re-measure) | S1 (S4 measures) |
| M-018 | 2 | S2 (+S1 items) | M-047 | 1 | S1 |
| M-019 | 2 | S1 | M-048 | 1 | S1 |
| M-020 | 1 lib → 2 | S4 → S2/S1 | M-049 | 1 | S1 |
| M-021 | 2 | S1 | M-050 | 1 | S1 |
| M-022 | 1 | S3 | M-051 | 1 (+C2 question) | S1 |
| M-023 | 1 | S3 | M-052 | 1 | S2 |
| M-024 | 1 | S3 | M-053 | 3 | S4 + S2/S1 |
| M-025 | 1 lib → 2 | S3 → S2/S1 | M-054 | 1 | S2 |
| M-026 | 1 | S3 | M-055 | 2 (after M-066) | S2 |
| M-027 | 1 | S3 | M-056 | 2 | S2 |
| M-028 | 1 lib → 2 | S3 → S1/S2 | M-057 | 2 | S2 |
| M-029 | 2 | S2 | M-058 | 2 (+C1) | S2 |
| M-030 | 2 | S1 | M-059 | 1 (+backend) | S2 |
| M-031 | 1 lib → 2 | S3 → S1/S2 | M-060 | 1 guard → 2 | S3 → S1/S2 |
| M-032 | 1 lib → 2 | S3 → S2 | M-061 | 1 lib → 2 | S3 → S1/S2 |
| M-033 | 1 | S3 | M-062 | 1 lib → 2, guard 3 | S3 → S1/S2 |
| M-034 | 2 | S2 | M-063 | 1 lib → 2 | S3 → S1/S2 |
| M-035 | 1 css + 2 markup | S3 + S1 | M-064 | 1 | S2 |
| M-036 | 1 | S1 | M-065 | decision (L), then 2 | L → S2 |
| M-037 | 2 | S1 | M-066 | 1 | S2 |
| M-038 | 2 | S1 | M-067 | 1 lib → 2 | S3/S4 → S1/S2 |
| M-039 | track: waits for C2 | S1 | M-068 | 1 css → 2 → 3 delete | S3 |
| M-040 | track: waits for C2 | S1 | M-069 | 1 | S3 |
| M-041 | 2 | S1 | M-070 | 1 | S3 |
| M-042 | track | S1 (+C2 review) | M-071 | track (docs) | R |
| M-043 | 1 | S1 | M-072 | 0 toolkit, 3 shims | S4 |
| M-044 | 2 | S1 | M-073 | track | C0 |
| M-045 | 2 | S1 | M-075 | 1 lib → 2 | S3 → S1/S2 |
| | | | M-076 | 1 | S2 |

## 5. Decisions needed and risks

| # | Decision / risk | Who |
|---|---|---|
| 1 | Glossary and product name (starred decisions in `S3-text-and-terminology.md` §B.1) — SC-05 provides the mechanism, the content needs the product owner; until then the guard ratchets, it does not enforce a variant | C5-L / product |
| 2 | Imperative `confirm()` / `toast()` (no provider) vs a provider: I recommend imperative for migration cost and harness simplicity; the cost is a separate React root (no context inside the dialog) | C5-L |
| 3 | M-014 vs M-026 / M-027 severity (V-02) | C5-L |
| 4 | Sequencing rule: **S2 splits `AdminApp.tsx` before migrating it**; S1 splits `StudioApp.tsx` / `ProjectWorkspace.tsx` before migrating them — otherwise every Wave-2 commit rebases over a 1 200-line file | S1, S2 |
| 5 | Visual risk of SC-12: three steps must land with a measured zero diff before any colour changes | S3 |
| 6 | Harness assertions that match Vietnamese strings (`org`, `provisioning`, `release`, `builder`) change with SC-04 / SC-05 / SC-02: each owner updates them in the same commit; the release spec's "rollback in one click" is intentionally replaced | S1, S2 |
| 7 | Backend-dependent rows stay open and are not blocked by any cluster: M-039, M-040, M-051 (server), M-058, M-059, M-009, M-094 (C1 / C2 / C0 answers) | C5-L |

## 6. Not verified

- No browser or backend run: every behavioural statement is from source at `a73ae3d`, from the audits' own labelled evidence (harness, replica), or from the scripts named above.
- The audit measurements I quote from S3 / R2 (386 hex, 93 duplicate selectors, contrast ratios, 14 thresholds) were not re-measured (their scripts `scripts/r2-contrast.mjs`, `r2-css-count.mjs` exist at base; I did not run them).
- Call-site counts are exact for the greps named (confirm/prompt 34 = 30 + 4, `act()` 13, ladders 28, `useLoad` results without `.error` 21 of 72, `className="field"` 48, `setNotice` 22) and may shift by a few after Wave-1 splits.
- Wave durations and capacity of S3 / S4 are unknown to me; section 4.5 is a risk note, not an estimate.
- The imperative API of SC-02 / SC-08 has not been prototyped; the "separate React root" trade-off is my reasoning (inference).
