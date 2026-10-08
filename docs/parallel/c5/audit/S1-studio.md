# S1 - Studio / Builder audit (PHASE 1: audit only, no product code changed)

Author: C5-S1 (Studio / Builder specialist). Base: `agent/c5-web` @ `9f858c2` (branch `agent/c5-s1-audit`). Date: 2026-10-08.
Scope: `features/studio/**`, `apps/studio`, `tests/builder`, `tests/browser` (builder / datasources / publicdata / release specs). Shared `packages/ui` belongs to S3: issues there are reported, not fixed.
Nothing in this phase changed product code; this file is the only addition.

## 0. How this was verified (read this before trusting any cell)

Evidence tags used everywhere in this document:

| Tag | Meaning |
|---|---|
| `SRC` | source reading only (file:line). Not exercised in a browser. |
| `H:<id>` | **HARNESS, NOT REAL BACKEND.** Real Chromium (Chrome, headless) driving the REAL `<StudioApp>` / `<ProjectWorkspace>` / `<BuilderWorkspace>` bundle. `/api/v1/**` is answered by an in-test Playwright fake (`page.route`), `next/navigation` and `next/link` are replaced by a virtual router. It proves what the UI does with the answers a fake gives; it says nothing about what the real server answers. Nothing here counts as real-backend evidence. `<id>` points to the probe table in section 10. |
| `U` | repo unit tests (`node scripts/test-unit.mjs`). |
| `SPEC` | existing repo browser specs (harness class, no backend). |

Process safety: every browser run went through `tests/browser/harness-server.mjs run -- <cmd>` (free port, owned process, stopped in `finally`). No port from the forbidden list was used, nothing was killed by name or port, the real backend was never contacted. `esbuild` was installed outside the repo.

Baseline (unchanged repo, before my probes):
- `node scripts/test-unit.mjs`: 306 tests, 305 pass, 1 skipped, 0 fail (`U`).
- `builder.spec.mjs` 87/87, `datasources.spec.mjs` 54/54, `publicdata.spec.mjs` 44/44, `release.spec.mjs` 56/56 (`SPEC`, harness class).
- My own probes (not committed; they lived in the session scratchpad, see section 12 to recreate): **59 checks, 32 FAIL / 27 PASS** (table in section 10), plus an axe-core sweep of **24 Studio screens/states with 0 violations**. axe cannot see most of what follows (focus, keyboard, wording, state loss, flow).
- The full-Studio harness is NOT in the repo. Phase 2 should add it as a `tests/browser/studio-full.*` harness (S4 tooling owner) so each fix has a regression check.

Scoring: nothing gets "10/10". The state matrix (section 4) marks PASS/FAIL only where an evidence tag backs the cell.

## 1. Counts (what was audited)

| Item | Count |
|---|---|
| Studio route entries (8 shell routes + 1 project default + 9 page-schema views + 9 code-project views; derived from `StudioApp.route()` `StudioApp.tsx:48-59`, `ProjectWorkspace.tsx:32-35`, `CodeWorkspace.tsx:48`) | 27 |
| Builder state rows B01-B25 (8 rail panels, 7 data-wizard steps, 6 Inspector tabs, TEST mode, device switch, phone 3-way switch, pre-check dialog, `<details>` list) | 25 |
| Inventory rows in section 2 (R01-R22 + B01-B25) | 47 |
| Popup rows in section 3: 34 rows covering 10 drawers, 1 modal (2 hosts), 12 builder dialogs, 12 native `confirm`, 1 native `prompt`, 2 toasts, 4 `<details>`, DnD overlay, file input, plus 2 families (66 native `<select>`, 51 native `title=` tooltips); PortalSwitcher / NavDrawer are S3 | 34 rows (54 individual popups + 2 families) |
| State-matrix rows (section 4) | 32 |
| Issues (section 9) | **59: P0 0, P1 5, P2 32, P3 22** |

## 2. A) Route / state inventory

Portal is always **Studio** (`apps/studio`: one catch-all route `app/[[...slug]]`, `PortalApp` -> `StudioApp`; `/login` and `/auth/*` belong to `packages/auth` / S3 and are out of scope). Routes are derived from source, not from folders: `StudioApp.tsx:48-59` (`route()` switch), `ProjectWorkspace.tsx:32-35` (`MODES = ai|design|code`, `PANELS = members|versions|assets|publish|settings|site`), `CodeWorkspace.tsx:48` (panels `members|publish|versions|packages|ide|runtime`).
Columns: PORTAL | ROUTE | PAGE COMPONENT | DIALOGS | DRAWERS | TABLES | FORMS | TOASTS | EMPTY | ERROR | LOADING | PERMISSION | RESPONSIVE | SHARED COMPONENTS | OWNER | RISK. "ND" = native browser dialog.

| # | PORTAL | ROUTE | PAGE COMPONENT | DIALOGS | DRAWERS | TABLES | FORMS | TOASTS | EMPTY | ERROR | LOADING | PERMISSION | RESPONSIVE | SHARED COMPONENTS | OWNER | RISK |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| R01 | Studio | `/studio` | `StudioApp` > `Home` (StudioApp.tsx:104) | - | - | - | 1 (idea textarea, Ctrl/Cmd+Enter) | - | recent apps: StateView | recent apps: ErrorState + retry; **KPIs and component chips: none (endless "…")** | StateView / "…" | create: server decides (403 message) | PASS at 390 (no h-scroll) | Card, StateView, ErrorState, Pill, NavLink, PortalSwitcher, MenuButton (S3) | S1 | M: duplicate create (S1-008), silent KPI failure (S1-026) |
| R02 | Studio | `/studio/projects` | `Projects` (StudioApp.tsx:155) | - | - | - | 1 search + 3 scope tabs | - | StateView | ErrorState + retry | StateView | list is server-scoped | PASS | Pager, Pill, StateView | S1 | L: card says "Website" for every kind (S1-025) |
| R03 | Studio | `/studio/new` | `NewApp` (StudioApp.tsx:176) | - | - | - | 1 (name + radiogroup of 6 kinds + template radios) | - | template lists silently empty | inline `formError` | none for kinds / template lists | server decides (403 shown) | PASS | Card, Pill | S1 | M |
| R04 | Studio | `/studio/templates` | `Templates` + `TemplateCard` (StudioApp.tsx:240,276) | 2 ND confirm (archive) | - | - | inline edit form per card | - | StateView | ErrorState | StateView | `t.canEdit` | PASS (axe 0) | SchemaThumb (`features/library`), Pill | S1 | L |
| R05 | Studio | `/studio/components` | `Components` + `BlocksSection` + `BlockCard` (StudioApp.tsx:330-366) | 1 ND confirm (delete block) | - | - | - | inline `role=status` | StateView | ErrorState (x3 lists) | StateView | `mine` only | PASS | BlockThumb, CheckList, ReviewTimeline | S1 | L |
| R06 | Studio | `/studio/activity` | `Activity` (StudioApp.tsx:385) | - | - | list | - | - | StateView | ErrorState | StateView | own activity only | PASS | Card | S1 | L |
| R07 | Studio | `/studio/site-access?site=&path=` | `SiteAccess` (StudioApp.tsx:372) | - | - | - | - | - | n/a | StateView forbidden | StateView | server ticket | not run | StateView | S1 | M: follows `r.redirect` unvalidated (S1-047) |
| R08 | Studio | `/studio/<anything else>` | `StateView kind=notfound` | - | - | - | - | - | n/a | n/a | n/a | n/a | PASS (H:Q1) | StateView | S1 | L |
| R09 | Studio | `/studio/projects/:id` (no view) | `ProjectWorkspace`, mode from `sessionStorage` (default `ai`) | see R10-R20 | | | | | | | | | | | S1 | M: an unknown view (`/projects/x/foo`) is silently treated as the default mode |
| R10 | Studio | `/studio/projects/:id/ai` | `ProjectWorkspace` AI mode (ProjectWorkspace.tsx:315-373) | - | - | - | composer (textarea, model `<select>`, suggestions) | `.toast` (notice) | intro + starters | `notice` toast, `loadError` | typing dots, `AiProgress` | read-only: composer disabled | PASS at 390 (no h-scroll; "Gửi ↑" wraps) | AiProgress, DeviceIcon | S1 | H: conversation never scrolls to the newest message (S1-002) |
| R11 | Studio | `/studio/projects/:id/design` | `BuilderWorkspace` frame (BuilderWorkspace.tsx:56): top bar + rail + canvas + right panel | Remove section, pre-check (2) | opens drawers R13-R19 | - | many (B01-B25) | `.toast` | "Chưa chọn mục nào" | readiness boxes, toast | readiness LOADING | `capabilitiesFor(project.permissions)` | 3 layouts: >1100, <=1100, <=760 (phone 3-way switch) | Tabs, Dialog, StateBox, Gate (`builder/ui`), dnd-kit | S1 | H (see issues) |
| R12 | Studio | `/studio/projects/:id/code` (PAGE_SCHEMA) | `ProjectWorkspace` `codeMode` card (ProjectWorkspace.tsx:305-314) | - | - | - | - | - | n/a | n/a | n/a | n/a | PASS | - | S1 | M: dead-end tab (S1-029) |
| R13 | Studio | `/studio/projects/:id/versions` | `Drawer` (ProjectWorkspace.tsx:377) | 1 ND confirm (restore) | 1 | list | - | toast after restore | **none (S1-049)** | toast | none | restore needs APP_EDIT | PASS at 390 | Drawer / useDialog (S3) | S1 | M |
| R14 | Studio | `/studio/projects/:id/assets` | `AssetsDrawer` (drawers.tsx:72) | 1 ND confirm (delete) | 1 | list | file input | toast (`onError`) | "Chưa có tệp nào." | toast | "Đang tải…" | `canEdit` | PASS at 390 | Drawer | S1 | L |
| R15 | Studio | `/studio/projects/:id/members` | `MembersDrawer` + `MemberTable` (drawers.tsx:105-173) | 2 ND confirm (remove) | 1 | 2 tables | 2 add forms + role selects | toast + inline `role=alert` | none (table just empty) | inline | "Đang tải…" | the workspace table appears only if the server answers 200 | **FAIL at 390 (overflow, S1-031)** | Drawer | S1 | M |
| R16 | Studio | `/studio/projects/:id/publish` | `PublishModal` (ReleaseModal.tsx:25) | 1 ND confirm (unpublish) | - | - | visibility choice, ack checkbox | inline notes | "Chưa xuất bản lần nào" | inline `formError` + retry | polling states | `canPublish` (APP_PUBLISH); reason only as `title` | PASS at 390 | useDialog (S3) | S1 | H: no confirm on rollback (S1-013) |
| R17 | Studio | `/studio/projects/:id/settings` | `SettingsDrawer` + `SaveTemplateSection` + archive (drawers.tsx:45, libraryPanels.tsx:11, ProjectWorkspace.tsx:387-392) | 1 ND confirm (archive) | 1 | - | 2 forms | toast "Đã lưu cài đặt." | n/a | toast | "Đang lưu…" | gear disabled without APP_EDIT (reason as `title`) | PASS at 390 | Drawer, Field | S1 | M: cannot clear 3 fields (S1-009) |
| R18 | Studio | `/studio/projects/:id/site` | `SiteDrawer` (SitePanels.tsx:144): Pages, Navigation, 404, Form submissions, Domains | 3 ND confirm | 1 (wide) | lists | 5 forms | inline | per section | inline | StateView | `canEdit` / `canPublish` | PASS at 390 | Drawer, Field | S1 | M: second, divergent implementation of pages / nav / 404 (S1-042) |
| R19 | Studio | (state) "Lưu thành khối…" from the Inspector | `SaveBlockDrawer` (libraryPanels.tsx:39) | - | 1 | - | 1 | toast on success | n/a | inline | "Đang lưu…" | `!readOnly` | not run | Drawer | S1 | L |
| R20 | Studio | `/studio/projects/:id/{ai,design,code,versions,packages,ide,runtime,members,publish}` (STATIC_APP) | `CodeWorkspace` (CodeWorkspace.tsx:44) + `CodePanels.tsx` | 3 ND confirm/prompt | 5 drawers + publish modal | tree / list | AI composer, editor, design form, secret form | `.toast` | per pane | `ErrorState`, toasts | StateView | canEdit / canPublish / canShare | not run at 390 | Drawer, PublishModal, MembersDrawer | S1 | H: approve-on-cancel (S1-004), Tab trap (S1-034) |
| R21 | Studio | (state) archived project | banner + read-only (ProjectWorkspace.tsx:303-304) | - | - | - | "Khôi phục" button | toast | - | toast | - | needs owner / admin (message after click) | PASS | - | S1 | L |
| R22 | Studio | (state) workspace load error / loading | `wsError` (ProjectWorkspace.tsx:249,251) | - | - | - | - | - | - | ErrorState + retry + back | StateView | - | light page in a dark app (S1-038) | ErrorState | S1 | L |

Builder states (route R11). Rail panels are `LeftRail.tsx:6-8`: Trang / Thành phần / Dữ liệu / Biểu mẫu / Hành động / Workflow / Giao diện / AI. Only the selected rail panel is mounted, so its local state is lost on every switch (S1-056).

| # | PORTAL | ROUTE / STATE | PAGE COMPONENT | DIALOGS | DRAWERS | TABLES | FORMS | TOASTS | EMPTY | ERROR | LOADING | PERMISSION | RESPONSIVE | SHARED COMPONENTS | OWNER | RISK |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| B01 | Studio | rail **Trang** | `PagesPanel` (panels/PagesPanel.tsx:20) | Add, Rename, Remove page (3) | - | tree (ARIA tree) | add / rename / menu / 404 | toast | "Trang trống." | route alert block | - | `canEdit` | PASS at 390 | Dialog, Field, StateBox, dnd-kit sortable | S1 | M: stale menu editor (S1-010), double submit (S1-015) |
| B02 | Studio | rail **Thành phần** | `ComponentsPanel` (panels/ComponentsPanel.tsx:11) | - | - | - | search | - | "Không có component phù hợp." | NOT_READY chips for data components | - | read-only text | PASS | useDraggable | S1 | L |
| B03 | Studio | rail **Dữ liệu** > step Nguồn dữ liệu | `DataWizard` + `DataSourcesPanel` + `SlotEditor` | remove source, edit slot, remove slot (3) | - | lists | create source, credential (password inputs), 2 bind selects, slot form | inline `role=status/alert` | per list | StateBox ERROR + retry | StateBox LOADING | DATA_SOURCE_VIEW / MANAGE / bind | PASS | Tabs, Field, Dialog | S1 | M: contradictory slot text (S1-027) |
| B04 | Studio | rail Dữ liệu > step Khám phá cấu trúc | `StateBox` NOT_READY | - | - | - | - | - | - | - | - | - | - | StateBox | S1 | L: leaks `DataGateway.discoverSchema` (S1-020) |
| B05 | Studio | rail Dữ liệu > step Truy vấn | `DataWizard` query form (DataWizard.tsx:105) | - | - | - | 1 (name, slot, kind, operation key, public, params, maxRows) | - | "Ứng dụng chưa có khe dữ liệu…" | `msg` alert at the bottom | - | Gate | PASS | Field | S1 | H: jargon + draft lost on rail switch (S1-006, S1-056) |
| B06 | Studio | rail Dữ liệu > step Ánh xạ | mapping form | - | - | - | 1 (fields + transforms) | - | "Hãy tạo một truy vấn trước." | `msg` | - | Gate | not run | Field | S1 | M |
| B07 | Studio | rail Dữ liệu > step ViewModel | view-model form | - | - | - | 1 | - | "Hãy tạo ánh xạ trước." | `msg` | - | Gate | not run | Field | S1 | M: "ViewModel" is implementation vocabulary |
| B08 | Studio | rail Dữ liệu > step Gắn vào thành phần | binding form (via ViewModel) | - | - | - | 1 | - | "Hãy tạo ViewModel trước." | `msg` + compatibility alert | - | Gate | not run | Field | S1 | M: competes with B09 (S1-006) |
| B09 | Studio | rail Dữ liệu > step Dữ liệu công khai | `PublicDataTab` (PublicDataPanels.tsx:251): readiness, public queries, public bindings | - | - | lists | 2 | inline | per list | inline alerts | - | `canEdit` | PASS | - | S1 | M |
| B10 | Studio | `<details>` "Đã khai báo (n)" + remove dialog | `DataWizard` / `DefList` (DataWizard.tsx:202-215) | remove definition (1) | - | lists | - | - | hidden when 0 | - | - | `disabled` | PASS | Dialog | S1 | L |
| B11 | Studio | rail **Biểu mẫu** | `FormsPanel` (panels/MiscPanels.tsx:13) | - | opens the Website drawer | list | - | - | "Ứng dụng chưa có biểu mẫu." | StateBox | - | `canEdit` + readiness | PASS | - | S1 | L |
| B12 | Studio | rail **Hành động** | `ActionsPanel` + `ActionEditor` | Remove action (1) | - | list | big action form (`<select multiple>` chains) | - | "Chưa có hành động nào." | `role=alert` list on a pristine form | Gate | `canEdit` | PASS | Dialog, Field, Gate | S1 | M: draft lost on rail switch (S1-056) |
| B13 | Studio | rail **Workflow** | `WorkflowsPanel` + `WorkflowEditor` | Remove workflow (1) | - | list | big step form (raw step ids, cron) | - | "Chưa có workflow nào." | `role=alert` | Gate | `canEdit` | not run | Dialog, Field, Gate | S1 | M (S1-052) |
| B14 | Studio | rail **Giao diện** | `ThemePanel` (panels/MiscPanels.tsx:34) | - | - | - | 1 (font, radius, <= 12 colours) | - | - | `formError` | Gate | `canEdit` | PASS | Field, Gate | S1 | M: result not previewable (S1-055), stale state (S1-010) |
| B15 | Studio | rail **AI** | `AiPanel` (panels/MiscPanels.tsx:73) | - | - | - | - | - | - | - | - | `canEdit` | PASS | - | S1 | L |
| B16 | Studio | Inspector tab **Nội dung** | `Inspector` + `PropsForm` (Inspector.tsx:20, PropsForm.tsx:22) | Remove section (shared) | - | - | 1 generated from the registry | toast | `emptyText` | toast | - | readOnly | PASS (phone 3-way) | Tabs | S1 | H: draft loss + focus loss (S1-005) |
| B17 | Studio | Inspector tab **Giao diện** | `PropsForm` | - | - | - | 1 | - | - | NOT_READY StateBox | - | readOnly | PASS | Tabs | S1 | L |
| B18 | Studio | Inspector tab **Dữ liệu** | `DataTab` (Inspector.tsx:64) | Remove binding (1) | - | list | - | - | "Thành phần này không có thuộc tính nhận dữ liệu." | StateBox | - | `ctx.canEdit` | PASS | Dialog | S1 | L: raw prop keys (S1-050) |
| B19 | Studio | Inspector tab **Hành động** | `ActionTab` (Inspector.tsx:105) | - | - | list | embeds `ActionEditor` | - | per event | StateBox | - | `ctx.canEdit` | PASS | - | S1 | L |
| B20 | Studio | Inspector tab **Quyền** | `PermissionTab` (Inspector.tsx:131) | - | - | list | add-permission form | - | per list | Gate | - | `capabilitiesFor` | PASS | Gate | S1 | L |
| B21 | Studio | Inspector tab **Nâng cao** | `AdvancedTab` (Inspector.tsx:175) | - | - | dl | - | - | - | - | - | - | PASS | - | S1 | L |
| B22 | Studio | mode **Dùng thử** (TEST) | `TestPanel` (TestPanel.tsx:49) + non-interactive canvas | - | - | query rows table | - | `role=status/alert` outcomes | "Chưa có … để thử" | `OutcomeView` | per-row `aria-busy` | `testGates(...)` | PASS (buttons wrap) | StateBox | S1 | M: not an interactive preview (S1-028) |
| B23 | Studio | device switch Máy tính / Máy tính bảng / Điện thoại | `BuilderTopBar` (BuilderTopBar.tsx:29-33) | - | - | - | segmented | - | - | - | - | - | icons only below 1760 px | - | S1 | L |
| B24 | Studio | phone (<= 760 px) 3-way switch Bản xem trước / Công cụ / Thuộc tính | `BuilderWorkspace.tsx:190-197` | - | - | - | tabs | - | - | - | - | - | PASS (no h-scroll) | Tabs | S1 | M: no look-and-adjust message, 227 px top bar (S1-007) |
| B25 | Studio | publish pre-check dialog | `Dialog` (BuilderWorkspace.tsx:222) | 1 | - | issue list | - | - | - | BLOCK / WARN list | - | `cap.canPublish` and `save.state != error` | PASS | Dialog | S1 | L |

## 3. B) Popup inventory

Implementation families (important for the fixes):
- **F1 `useDialog` + `Drawer` / `PublishModal`**. Hook in `packages/ui/src/useDialog.ts` (S3); hosts in `features/studio/drawers.tsx:29` and `ReleaseModal.tsx:86`. Document-level capture keydown, first focusable gets focus on mount, Tab wraps, Escape closes unless `onClose` is null, focus returns to the opener on unmount. No scroll lock (the body is `overflow:hidden` on desktop; below 900 px the body scrolls under the overlay), no `inert` background, `stopPropagation` (not `stopImmediatePropagation`), so two stacked dialogs would both close on one Escape (latent: no stacked case is reachable today).
- **F2 builder `Dialog`** (`builder/ui/primitives.tsx:28`). `onKeyDown` on the dialog node (Escape and the Tab trap only work while focus is inside it), first `[data-autofocus]` / focusable focused, opener restored, backdrop `onMouseDown` closes. The container has no `tabIndex`, so clicking dialog text drops focus to `<body>` and Escape then does nothing (H:C3).
- **F3 native `window.confirm` / `window.prompt`**.

Legend: Y = verified yes, N = verified no, - = not applicable, `?` = not verified. Columns: open/close, Escape, focus trap, focus restore, scroll lock, nested behaviour, mobile fit (390 px), long content, error, loading, disabled, double-submit, keyboard, accessible name, evidence.

| # | Popup | Family | Open / close | Esc | Trap | Restore | Scroll lock | Nested | Mobile 390 | Long content | Error | Loading | Disabled | Double-submit | Keyboard | Name | Evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| P01 | Settings (Cài đặt project) | Drawer F1 | route `/settings`; X, Esc, **backdrop mousedown**, Hủy | Y | Y | Y | N | n/a | fits | scrolls | toast | "Đang lưu…" | Save disabled while busy / empty name | Y (busy) | Y | h2 via `aria-labelledby` | H:D1,D5; backdrop discards edits (H:D4, S1-011) |
| P02 | Assets (Tệp của project) | Drawer F1 | route `/assets` | Y | Y | Y | N | n/a | fits | long file name: `.versionItem` | toast | "Đang tải…" | upload input disabled while uploading | Y | Y | h2 | H:PH-assets, SRC |
| P03 | Members (Thành viên và quyền) | Drawer F1 wide | route `/members` | Y | Y | Y | N | n/a | **FAIL: table 440 px in a 373 px drawer** | long email wraps | inline `role=alert` | "Đang tải…" | busy disables all | Y (busy) | Y | h2 + table caption | H:PH-members (S1-031) |
| P04 | Versions (Lịch sử phiên bản) | Drawer F1 | route `/versions` | Y | Y | Y | N | n/a | fits | list | toast | none | Restore disabled while busy | Y | Y | h2 | H:D5,K1,K2 |
| P05 | Website (site) | Drawer F1 wide | route `/site` | Y | Y | ? | N | n/a | fits | ? | inline | StateView | `canEdit` | partial (no guard on "Lưu trang") | Y | h2 | H:PH-site, SRC |
| P06 | Lưu thành khối | Drawer F1 | state `savingBlock` | Y | Y | Y | N | not stackable (overlay covers the UI) | ? | ? | inline | busy | `!name.trim()` | Y | Y | h2 | SRC |
| P07 | Code: Lịch sử (commit trên main) | Drawer F1 | route `/versions` (code) | Y | Y | ? | N | n/a | ? | ? | - | StateView | - | - | Y | h2 | SRC |
| P08 | Code: Thư viện (package) | Drawer F1 | route `/packages` | Y | Y | ? | N | n/a | ? | ? | inline | StateView | - | no guard on "Thêm" | Y | h2 | SRC |
| P09 | Code: Mở bằng IDE (clone token) | Drawer F1 | route `/ide` | Y | Y | ? | N | n/a | ? | token wraps (`breakAll`) | inline | busy | - | Y | Y | h2 | SRC; token in clear, no copy button (S1-047) |
| P10 | Code: Máy chủ của ứng dụng | Drawer F1 wide | route `/runtime` | Y | Y | ? | N | n/a | ? | log `pre` | inline | StateView | - | **N** (secret delete, rollback) | Y | h2 | SRC (S1-033) |
| P11 | Xuất bản website (publish / rollback / unpublish) | Modal F1 (`PublishModal`) | route `/publish`; Hủy / Đóng, **no backdrop close** | Y, except while a deployment or rollback runs (by design) | Y | Y | N | n/a | fits | many rows | inline `role=alert` + retry | polling | locked states | Y (keyed idempotency, `locked`) | Y | h2 | SPEC 56/56, H:PH-publish, H:P1; semantic gaps S1-013, S1-018, S1-019 |
| P12 | Xóa mục khỏi trang | Dialog F2 | "Xóa mục" button; Hủy / Esc / backdrop | Y (focus inside) | partial (wraps at the edges only) | Y | N | n/a | ? | - | toast | - | `disabled={busy}` | Y (one PATCH on dblclick, H:C7) | Y | `aria-labelledby` | H:C1-C7 |
| P13 | Kiểm tra trước khi xuất bản | Dialog F2 | "Xuất bản" in the top bar | Y | partial | ? | N | n/a | ? | list | - | - | - | - | Y | h2 | SRC |
| P14 | Thêm trang | Dialog F2 | "+ Trang" | Y | partial | ? | N | n/a | ? | - | **generic "Không lưu được. Hãy thử lại." only** | none | disabled only when the title is empty | **N (2 PATCH, H:PD1)** | Y (Enter submits) | h2 | S1-015, S1-016 |
| P15 | Đổi tên và đường dẫn | Dialog F2 | "Đổi tên / đường dẫn" | Y | partial | ? | N | n/a | ? | - | generic | none | - | N | Y | h2 | SRC |
| P16 | Xóa trang | Dialog F2 | "Xóa trang" | Y | partial | ? | N | n/a | ? | impact text | generic | none | - | N | Y | h2 | SRC |
| P17 | Gỡ dữ liệu khỏi thành phần | Dialog F2 | Inspector > Dữ liệu > Gỡ | Y | partial | ? | N | n/a | ? | - | toast | - | `disabled={ctx.busy}` | Y | Y | h2 | SRC |
| P18 | Xóa hành động | Dialog F2 | Hành động > Xóa | Y | partial | ? | N | n/a | ? | usage list | toast | - | `disabled={ctx.busy}` | Y | Y | h2 | SRC |
| P19 | Xóa workflow | Dialog F2 | Workflow > Xóa | Y | partial | ? | N | n/a | ? | usage list | toast | - | `disabled={ctx.busy}` | Y | Y | h2 | SRC |
| P20 | Xóa định nghĩa dữ liệu | Dialog F2 | "Đã khai báo" > Xóa | Y | partial | ? | N | n/a | ? | usage list | toast | - | `disabled={ctx.busy}` | Y | Y | h2 | SRC |
| P21 | Sửa khe | Dialog F2 | Khe > Sửa | Y | partial | ? | N | n/a | ? | - | inline `SAVE_FAILED` | - | save disabled when invalid | Y (`ctx.busy`) | Y | h2 | SRC |
| P22 | Xóa khe | Dialog F2 | Khe > Xóa | Y | partial | ? | N | n/a | ? | blockers list | inline | - | disabled when blockers exist | Y | Y | h2 | SRC |
| P23 | Xóa nguồn dữ liệu | Dialog F2 | Nguồn > Xóa | Y | partial | ? | N | n/a | ? | - | inline note | - | locked while pending | Y (`inflight` set) | Y | h2 | SRC; confirm styled primary, not danger (S1-054) |
| P24-P35 | 12 native `confirm()`: restore version (ProjectWorkspace.tsx:196), archive project (:391), delete asset (drawers.tsx:95), remove project member (:165), remove workspace member (:169), archive template (StudioApp.tsx:268), delete block (:319), unpublish (ReleaseModal.tsx:140), remove page (SitePanels.tsx:49), delete submission (SitePanels.tsx:106), remove domain (SitePanels.tsx:138), stop server (CodePanels.tsx:134) | F3 | click -> OS dialog | OS | OS | OS | OS | - | OS | long names unbounded | - | - | - | n/a (modal) | OS | OS text | H:K1, H:members, H:assets; inconsistent with F2, unstyled, no focus control (S1-032) |
| P36 | Approval comment | native `window.prompt` (CodeWorkspace.tsx:122) | "Duyệt" | Esc = cancel | - | - | - | - | - | - | - | - | - | - | OS | OS | **H:CW1: Cancel approves** (S1-004) |
| P37 | Toast | `<button class=toast>` (ProjectWorkspace.tsx:375, CodeWorkspace.tsx:240) | appears on `notice`, **only a click dismisses it** | N | - | - | - | z-index 70 < builder overlay 80 | fits | long text wraps | - | - | - | - | focusable button | its text | H:E1,E2,PD2 (S1-014) |
| P38-P41 | 4 `<details>`: AI raw output (AiProgress.tsx:17), release history (ReleaseModal.tsx:208), "Đã khai báo" (DataWizard.tsx:202), "Trạng thái của trang đã xuất bản" (PublicDataPanels.tsx:242) | native disclosure | click / Enter | - | - | - | - | - | ? | ? | - | - | - | - | Y | summary text | SRC |
| P42 | 66 native `<select>` (family) | native popup | - | OS | - | - | - | - | OS | long options clip | - | - | `disabled` honoured | - | Y | most have a label or `aria-label`; the chain `multiple` select (ActionEditor.tsx:57) is poor for keyboard and novices (S1-053) | SRC |
| P43 | 51 native `title=` tooltips (family) | not a component | hover only | - | - | - | - | - | **never visible on touch; unreachable by keyboard on disabled buttons** | - | - | - | **all disabled reasons live here** (S1-030) | - | - | - | SRC |
| P44 | DnD: `DragOverlay` chip + insertion line + Vietnamese `announcements` | `BuilderWorkspace.tsx:41-47,214` | pointer (4 px) / keyboard sensor | Esc cancels (dnd-kit) | - | - | - | - | handle gutter eats width | - | - | - | disabled while busy | - | Y (keyboard sensor + Thêm / Lên / Xuống buttons) | live announcements | SPEC (87/87) |
| P45 | PortalSwitcher / NavDrawer (`@xweb/ui`) | S3 | | | | | | | | | | | | | | | not audited here (S3) |
| P46 | Inline `role=alert/status` messages (family) | - | - | - | - | - | - | - | - | - | - | - | - | - | - | - | SRC |
| P47 | File input (`AssetsDrawer`) | native | - | OS | - | - | - | - | OS | - | toast | label text | disabled while uploading | Y | Y | label | SRC |
| P48 | Site-access redirect | page navigation | - | - | - | - | - | - | - | - | StateView | StateView | - | - | - | - | SRC (S1-047) |

## 4. C) State matrix per screen

Cell format: `PASS|FAIL|NR` then the evidence (`H` = harness, `S` = source reading only, `SPEC` = existing spec). `NR` = not reachable (reason). A cell with only `S` was NOT exercised in a browser.

| Screen | default | loading | empty | error | permission denied | populated | long content | keyboard | responsive | mobile |
|---|---|---|---|---|---|---|---|---|---|---|
| Home `/studio` | PASS H | PASS S | PASS S | **FAIL H (F2: KPI "…" forever)** | PASS S (403 text) | PASS H | PASS H (LC1) | **FAIL H (F1 Ctrl+Enter)** | PASS H | PASS H (header cramped, S1-037) |
| Projects | PASS H | PASS S | PASS S | PASS S | NR (server scoped) | PASS H | PASS H (LC1) | FAIL S (tabs: no arrows, S1-048) | PASS H | PASS S |
| New app | PASS H | FAIL S (lists silently empty) | PASS S | PASS S | PASS S | PASS H | PASS S | **FAIL H (M1 radiogroup)** | PASS S | PASS S |
| Templates | PASS H | PASS S | PASS H | PASS S | PASS S (`canEdit`) | NR (no fixture) | PASS S | FAIL S (tabs) | PASS S | PASS S |
| Components + Blocks | PASS H | PASS S | PASS H | PASS S | PASS S | NR (no fixture) | PASS S | FAIL S | PASS S | PASS S |
| Activity | PASS H | PASS S | PASS S | PASS S | NR | NR | PASS S | PASS S | PASS S | PASS S |
| Site access | NR (needs a real ticket) | PASS S | n/a | PASS S | PASS S | n/a | n/a | PASS S | PASS S | PASS S |
| Not found / unknown project | PASS H (Q1) | PASS H | n/a | PASS H (Q2,Q3) but **FAIL H**: duplicate title and message, useless Retry, light theme (S1-038) | PASS H (Q4 redirect) | n/a | n/a | PASS S | PASS H | PASS H |
| Project AI mode | PASS H | PASS S | PASS H | PASS H (toast) | PASS H (read-only composer) | PASS H | PASS H | PASS H (Enter sends; IME guard S) | PASS H | PASS H (no h-scroll; "Gửi ↑" wraps) |
| AI mode history | - | - | - | - | - | **FAIL H (N1/N2: never scrolls to newest)** | - | **FAIL H (N3: not announced)** | - | - |
| Builder: Trang (rail + tree) | PASS H | PASS S | PASS S | PASS H (route alert) | PASS H (R1,R2) | PASS H | PASS H (ellipsis) | PASS SPEC | PASS H | PASS H |
| Builder: preview canvas | PASS H | PASS S | PASS S (drop hint) | PASS S | n/a | **FAIL H (A1/A2: reload + scroll reset on select)** | PASS H | PASS SPEC (handles, buttons) | PASS H | **FAIL H (E-phone1: no phone guidance)** |
| Builder: Thành phần | PASS H | PASS S | PASS S | PASS S | PASS S | PASS H | PASS S | PASS SPEC | PASS H | PASS H |
| Builder: Dữ liệu (7 steps) | PASS H | PASS S | PASS H | PASS S | PASS H (needs DATA_SOURCE_VIEW) | PASS SPEC | PASS S | PASS SPEC | PASS H | PASS S |
| Builder: Dữ liệu drafts | - | - | - | - | - | **FAIL H (DW1: draft lost on rail switch)** | - | - | - | - |
| Builder: Hành động / Workflow | PASS H | PASS S | PASS H | PASS S | PASS S | PASS SPEC | FAIL S (raw ids) | PASS SPEC | PASS H | PASS S |
| Builder: action editor drafts | - | - | - | - | - | **FAIL H (AE1)** | - | - | - | - |
| Builder: Giao diện | PASS H | n/a | n/a | PASS S | PASS S | PASS H | PASS S | PASS S | PASS H | PASS S |
| Inspector (Nội dung) | PASS H | n/a | PASS S | PASS S | PASS S (read-only) | PASS H | PASS S | **FAIL H (B1 draft loss, B2 focus lost)** | PASS H | PASS H |
| Inspector other tabs (5) | PASS H | PASS S | PASS S | PASS S | PASS S | PASS H | PASS S | PASS SPEC | PASS H | PASS S |
| Test mode (Dùng thử) | PASS H | PASS S | PASS H | PASS S | PASS SPEC | PASS SPEC | PASS S | PASS SPEC | PASS H | PASS S |
| Settings drawer | PASS H | PASS S | n/a | PASS S | PASS H (gear disabled) | PASS H | PASS S | PASS H (Esc, focus restore) | PASS H | PASS H |
| Settings: save semantics | - | - | - | - | - | **FAIL H (D2: cleared fields not sent)** | - | **FAIL H (D4: backdrop discards)** | - | - |
| Versions drawer | PASS H | PASS S | **FAIL H (K2: no empty text)** | PASS S | PASS S | PASS H | PASS S | PASS H | PASS H | PASS H |
| Assets drawer | PASS H | PASS S | PASS S | PASS S | PASS S | PASS H | PASS H (long name) | PASS S | PASS H | PASS H |
| Members drawer | PASS H | PASS S | PASS S | PASS S | PASS S (workspace table hidden on 403 / 404) | PASS H | PASS H | PASS S | PASS H | **FAIL H (PH-members)** |
| Site drawer | PASS H | PASS S | PASS S | PASS S | PASS S | PASS S | PASS S | PASS S | PASS H | PASS H |
| Publish modal | PASS SPEC + H | PASS SPEC | PASS SPEC | PASS SPEC | PASS SPEC | PASS SPEC | PASS S | PASS SPEC | PASS H | PASS H |
| Publish: semantics | - | - | - | - | - | **FAIL H (PM1 no aria state; P1 leaks)** | - | - | - | - |
| Code workspace (STATIC_APP) | PASS H (renders) | PASS S | PASS S | PASS S | PASS S | PASS H | PASS S | **FAIL H (CW3 Tab trap)** | NR (not run at 390) | NR |
| Code: review actions | - | - | - | - | **FAIL H (CW1: Cancel approves)** | **FAIL H (CW2: discard without confirm)** | - | - | - | - |
| Code tab on a PAGE_SCHEMA project | PASS H | n/a | n/a | n/a | n/a | **FAIL S (dead-end card, S1-029)** | n/a | PASS S | PASS H | PASS H |

## 5. D) User-visible text findings (Vietnamese correctness, leaks, wording)

No mojibake found (searched for `Ã`, `â€`, replacement characters). The Vietnamese is generally correct. Findings (each also appears in section 9):

| # | Where | Text | Problem | Issue |
|---|---|---|---|---|
| T01 | ReleaseModal.tsx:172 | "pointerVersion 7 (chỉ để quan sát, không gửi lại máy chủ)" | internal debug field shown to every user (H:P1) | S1-019 |
| T02 | ReleaseModal.tsx:164,212,221,223 | "(APP_PUBLISH)" in text and tooltips | permission constant name | S1-019 |
| T03 | ReleaseModal.tsx:180 | "trang không nhận địa chỉ dữ liệu (apiBase)" | internal field name | S1-019 |
| T04 | ReleaseModal.tsx:201 | "Demo deployment" | English | S1-019 |
| T05 | builder/core/readiness.ts:39 | "(ADD_QUERY, ADD_ACTION…)" shown in the Inspector empty state (H screenshot) | operation names | S1-020 |
| T06 | readiness.ts:41 | "DataGateway.discoverSchema chưa có đường HTTP" | class / method name | S1-020 |
| T07 | readiness.ts:43,55,58 | "(T15)", flags `app.data-platform.enabled` / `app.workflow.enabled`, `DATA_RUNTIME_UNAVAILABLE` | ticket number, server flag names, error code | S1-020 |
| T08 | ProjectWorkspace.tsx:286, BuilderWorkspace.tsx:164, CodeWorkspace.tsx:135 | "revision 3" in every top bar | internal concurrency counter | S1-021 |
| T09 | Inspector.tsx:37 | `section.id` under the heading (for example `s-grid`) | internal id | S1-021 |
| T10 | drawers.tsx:53,87,64 | "Lưu vào backend", "Lưu trong MinIO qua URL ký sẵn", "Mock (cục bộ)" / "Self-host" / "Cloud" | infrastructure names | S1-021 |
| T11 | TestPanel.tsx:184,190,210 | "workflow_run", "Lượt chạy <uuid> · SUCCEEDED", "bộ nhớ đệm: MISS · chế độ TEST" | internal names / raw enums | S1-021 |
| T12 | StudioApp.tsx:217, SitePanels.tsx:127, CodeWorkspace.tsx:96,231 | "openapi.json", "CDN/tunnel", server error CODE appended in brackets, "OSV", "SBOM" | implementation vocabulary | S1-021 |
| T13 | StudioApp.tsx:271 | `{c.check}: {c.message}` (template check ids) | raw check ids | S1-021 |
| T14 | ProjectWorkspace.tsx:381 | `{v.kind}` shows "MANUAL_EDIT", "AI_GENERATED" | raw enum | S1-049 |
| T15 | ProjectWorkspace.tsx:261, StudioApp.tsx:26,62,284,334, LeftRail.tsx:7 | tabs "Design" / "Code", nav "Templates" / "Components", h1 "Company Components", "Workflow"; sidebar brand "Company Builder Studio" (StudioApp.tsx:65) vs tab title "Xweb Studio" (:28) | mixed English; two product names | S1-022 |
| T16 | PropsForm.tsx:44, MiscPanels.tsx:53-55, ActionEditor.tsx:111,121, WorkflowEditor.tsx:106, DataWizard.tsx (PARAM_TYPES) | option labels `light` / `dark`, `SYSTEM` / `SERIF` / `MONO` / `ROUNDED`, `NONE` / `SM` / `MD` / `LG`, notify channels, idempotency policies, `USER` / `GROUP` / `ROLE`, param types | raw enums | S1-022 |
| T17 | many | xoá / xóa, huỷ / hủy, tuỳ / tùy variants on the same screens; "Huỷ" = cancel (AiProgress.tsx:18) AND discard (CodeWorkspace.tsx:215) | inconsistent orthography; one verb with two meanings | S1-023 |
| T18 | ProjectWorkspace.tsx:136 vs :348 | "Có thể chọn “Mô phỏng” để tiếp tục chỉnh sửa" but the option is "Chế độ thử nghiệm (không dùng AI thật)"; test mode is "Dùng thử" / "Kiểm thử" | the recovery instruction names a control that does not exist; three names for "not real" | S1-024 |
| T19 | DataSourcesPanel.tsx:213 | "chưa có thao tác nào để thêm khe từ Studio … Tính năng này sẽ mở khi máy chủ hỗ trợ" directly above the working "Thêm khe" form | contradicts the screen | S1-027 |
| T20 | DataSourcesPanel.tsx:213, DataWizard.tsx | "dataSources[]", "khe", "Mã thao tác đã duyệt (ví dụ products.list)" | forces knowledge of the data model | S1-006 |
| T21 | drawers.tsx:165 | "Xóa luan khỏi project?" when the row is yourself (button says "Rời") | wording does not match the action | S1-032 |
| T22 | TestPanel.tsx:212 | `<caption className="sr-only">` | the class does not exist (only `.srOnly`): the caption is visible | S1-039 |
| T23 | DataWizard.tsx:89, Inspector.tsx:31 | tab "Khám phá cấu trúc" + badge "chưa" reads as "Khám phá cấu trúcchưa" | badge has no separator / `badgeLabel` | S1-040 |
| T24 | CodeWorkspace.tsx:215 | "Huỷ" next to "Hợp nhất vào main" | discards a change but reads as "cancel" | S1-023 |

## 6. E) UX flow review

Scale: OK / WEAK / FAIL. Click counts come from the harness runs or from the source.

| Flow | Next action obvious | Terminology | Error recovery | Status visibility | Destructive clarity | Cognitive load | Clicks | Forced implementation knowledge |
|---|---|---|---|---|---|---|---|---|
| **Enter project** (Home idea box, or a Projects card -> `/projects/:id/ai`) | OK: big prompt box | WEAK: every card says "Website" (S1-025) | WEAK: Ctrl/Cmd+Enter can create duplicates (S1-008); a failed create keeps the text | OK | n/a | Low | 2 (type, "Tạo bằng AI") | none |
| **Edit page** (Builder, rail Trang, drag or "Thêm") | OK; keyboard alternatives exist | OK | WEAK: a failed ADD_PAGE shows only "Không lưu được" inside the dialog (S1-016); double submit (S1-015) | WEAK: toast never leaves, not announced (S1-014) | OK: remove dialogs name the impact | Medium | 2-3 | slug rules |
| **Select component** (click preview or tree) | WEAK: the preview reloads and jumps to the top (S1-001) | OK | n/a | OK (highlight) | n/a | Low | 1 | none |
| **Edit component props** | WEAK: unsaved text lost when the selection changes or after a save (S1-005); no dirty indicator; no Enter-to-save | OK; raw keys on the Data tab (S1-050) | OK | WEAK: only the button text says "Đang lưu" | n/a | Low | 2 | none |
| **Data / query** | **FAIL**: 7 steps (Nguồn dữ liệu, Khám phá cấu trúc, Truy vấn, Ánh xạ, ViewModel, Gắn vào thành phần, Dữ liệu công khai), two competing binding UIs with the same label "Gắn dữ liệu", a third entry in the Inspector Data tab, drafts lost on a rail switch (S1-006, S1-056). Binding one list = a source + a slot + 4 forms. | FAIL: Query / Mapping / ViewModel / Binding / slot / operation key | OK per step (alerts) | WEAK: "Khám phá" is NOT_READY with a leaked class name (S1-020) | OK | **High** | >= 10 | operation keys like `products.list`, cardinality, slots |
| **Action / workflow** | WEAK: big forms, `<select multiple>` for chains (S1-053), step targets are raw ids, a 5-field cron and milliseconds (S1-052) | WEAK | OK | OK (describe lines) | OK: dialogs list the users of the item | **High** | >= 6 per action | cron, ids, idempotency |
| **Preview / test** ("Dùng thử") | WEAK: the name promises "use the app" but the preview is the same static non-interactive canvas; the real work is a list of "Chạy thử" buttons for queries, actions and workflows (S1-028) | WEAK: "Dùng thử" / "Kiểm thử" / "Chế độ thử nghiệm" (the AI mock) (S1-024) | OK: UNKNOWN outcomes lock the button | OK | OK ("không gửi", "sẽ chạy") | Medium | 2 | none |
| **Publish** (Builder button -> pre-check -> modal) | OK | WEAK: leaks `pointerVersion`, `APP_PUBLISH`, `apiBase` (S1-019); "Riêng tư" / "Công khai" buttons have no pressed state (S1-018) | OK (SPEC-proven retry and idempotency keys) | OK (deployment states, polling) | WEAK: going public has no confirmation beyond the sub-label "Mọi người có thể truy cập." | Medium | 3 | visibility concepts |
| **Rollback** ("Phục vụ lại bản này") | OK: a button per release | WEAK: "phục vụ lại" is not "khôi phục / hoàn tác"; the other rollback (Versions drawer "Khôi phục") is a different thing with the same everyday meaning and the difference is never explained | OK | OK | **FAIL**: one click switches the live site with no confirmation, whereas unpublish confirms (S1-013) | Medium | 2 | difference between "version restore" and "release rollback" |
| **Versions / restore** | OK | OK | OK | WEAK: an empty list has no text; raw kind (S1-049) | OK (the native confirm says what happens) | Low | 3 | none |
| **Phone builder** | **FAIL as "look and adjust"**: the phone gets the full desktop toolbar (AI / Design / Code, Chỉnh sửa / Dùng thử, 3 device buttons, Website, Phiên bản, Tệp, Cài đặt, Chia sẻ, Xuất bản) = 227 px of the 844 px first screen, a drag-handle gutter, destructive buttons, and no sentence saying phones are for viewing and light adjustments (H:E-phone1, S1-007). The 3-way switch itself (Bản xem trước / Công cụ / Thuộc tính) works and shows no h-scroll. | | | | | | | |

## 7. F) Performance / architecture hotspots

| # | Finding | Evidence | Issue |
|---|---|---|---|
| F01 | The preview posts a `studio:layout` message on EVERY scroll frame; `Canvas` calls `onRects(validRects(...))`, which builds a NEW array, so `setRects` always re-renders `BuilderWorkspace` and its un-memoised children (PagesPanel, Inspector, rail panel). Measured: 20 messages for a 20-frame scroll, 120 DOM mutations in the left panel (H:PERF1). | Canvas.tsx:30, BuilderWorkspace.tsx:79 | S1-041 |
| F02 | Selecting a section changes the whole `srcDoc` (the selection class is baked into the HTML), so the iframe is destroyed and rebuilt, including `renderSchemaDocument` for the full document. | BuilderWorkspace.tsx:163, lib/schema-preview.ts:114 | S1-001 |
| F03 | `ProjectWorkspace` builds `previewDocument` (`renderSchemaDocument`) in EVERY mode, including Design where it is never used (the Builder renders its own `html`), doubling the work per schema change. `renderPreview`, `applyOps`, `labelOf` are new functions on every render, so the `useMemo`s in `BuilderWorkspace` (`html`, `ctx`) only hit while the parent does not re-render. | ProjectWorkspace.tsx:219,258; BuilderWorkspace.tsx:94-102,163 | S1-044 |
| F04 | `canStep(...)` runs `planStep` (allocates ops) twice per section on every render of the page tree: O(n^2) per render, multiplied by F01. `preflight(doc)` is computed in both `BuilderWorkspace` and `PagesPanel`. DataWizard recomputes `viewModelFromMapping` inside a `.map` over the fields: O(f^2). | PagesPanel.tsx:62-64, BuilderWorkspace.tsx:92, DataWizard.tsx:177 | S1-044 |
| F05 | `PropsForm` is keyed on `JSON.stringify(section.props)` computed in render and remounts after every save (also the cause of the focus loss). | Inspector.tsx:43,53 | S1-005 |
| F06 | Business logic inside components: slug rules duplicated (`SitePanels.tsx:10` vs `core/pages.ts`), `lineDiff` (LCS) in `CodeWorkspace.tsx`, template / block review flow inside `StudioApp.tsx`, permission shaping in `ProjectWorkspace.tsx`. Oversized files: `StudioApp` 393 lines (8 screens + 2 card components), `ProjectWorkspace` 402 (loading, streaming AI, permissions, versions, 6 panels, 3 modes), `CodeWorkspace` 257. | files | S1-044 |
| F07 | `DiffView` recomputes the LCS of every file on every render of `CodeWorkspace` (any keystroke in the prompt box). Measured about 24 ms per keystroke with one 1,800-line file (H:CW4); grows with the file count. | CodeWorkspace.tsx:34-46 | S1-045 |
| F08 | Dead / duplicated code: `PageBar` (SitePanels.tsx:14, imported nowhere), `useRectsState` (Canvas.tsx:72, unused), `IconButton` (used only by a test); two implementations of the pages / navigation / 404 editors (SitePanels.tsx vs builder/panels/PagesPanel.tsx) with different validation (the old one has no reserved-slug check and uses native `confirm`); two modal implementations (F1 vs F2). | files | S1-042, S1-043 |
| F09 | Stale mocks: none found in scope (the Builder says "Chưa sẵn sàng" instead of faking data; `lib/mock-data.ts` is not imported by `features/studio`). | grep | - |
| F10 | Polling is bounded and cleaned up correctly in `ReleaseModal`, `TestPanel`, `CodeWorkspace` (cleanup, failure counters). No leak found. | SRC | - |

## 8. G) Security hygiene (in scope)

| Check | Result | Evidence |
|---|---|---|
| `dangerouslySetInnerHTML`, `innerHTML`, `document.write`, `eval`, `new Function` in `features/studio`, `lib/schema-preview.ts`, `packages/ui/src` | none | grep |
| Preview iframes | Builder canvas: `sandbox="allow-scripts"` (edit) or `sandbox=""` (test / read-only), no `allow-same-origin` / forms / popups; AI preview `sandbox=""`; code preview `sandbox="allow-scripts"` served from the sites origin | Canvas.tsx:41, ProjectWorkspace.tsx:368, CodeWorkspace.tsx:220 |
| `postMessage` receive | `Canvas` accepts only `e.source === iframe.contentWindow` and only section ids it knows; `rects` are validated (finite numbers, known ids) | Canvas.tsx:22-33 |
| `postMessage` send | the preview script posts to the parent with target `"*"` (the sandbox origin is opaque, so this is the only option); payload is a section id + rects, not sensitive | lib/schema-preview.ts:114 |
| Section HTML escaping | authored text is escaped in the preview and the published markup (`U` tests in `tests/page-runtime`) | U |
| `rel` on external links | all four `target=_blank` links carry `noopener noreferrer` (H:SEC1) | drawers.tsx:94, ReleaseModal.tsx:170,202, CodeWorkspace.tsx:221 |
| URLs from the API used unvalidated | `href={a.downloadUrl}`, `site.url`, `deployment.url`, `change.previewUrl`, `iframe src=previewUrl` and `window.location.assign(r.redirect)` in `SiteAccess` take no scheme / host check (a `javascript:` value would execute on click / assign). Values are server-built, so this is defence in depth (S1-047) | drawers.tsx:94, ReleaseModal.tsx:170,202, CodeWorkspace.tsx:221, StudioApp.tsx:378 |
| Secrets rendering / masking | data-source credentials are `type=password`, `autoComplete=new-password`, cleared right after sending and never echoed (SPEC `__secretsSeenInDom`); runtime secrets are write-only; **the IDE clone token is shown in clear in page state and in a `git clone` line with the token in the URL (shown once, no copy button, S1-047)** | DataSourcesPanel.tsx, CodePanels.tsx:98-112 |
| `localStorage` / `sessionStorage` | only `studio-ai-model`, `studio-ws` (workspace id), `ws-mode-<id>`; all in try/catch; stored values are validated against live lists before use (`valid()`, `workspaces.some`). No tokens, no PII | ProjectWorkspace.tsx:49-51,83,115, StudioApp.tsx:31-34, CodeWorkspace.tsx:65,76 |
| Role-name authorization hacks | none: permissions come from `resolvePermissions(project.permissions)` / `capabilitiesFor` (the code comments say so). One identity-by-display-name comparison for UX only: `change.createdBy !== me.displayName` decides whether "Duyệt" is shown (names are not unique; the server enforces) (S1-004) | CodeWorkspace.tsx:211 |
| Raw stack traces | none shown; server `message` + `requestId` only. `CodeWorkspace.act` appends the server error code to the toast (S1-021) | drawers.tsx:16, CodeWorkspace.tsx:96 |
| Prompt in the URL | the Home hand-over puts the whole prompt (<= 2000 chars) in `?prompt=`, removed with `router.replace` after load: history / referrer / log exposure (S1-046) | StudioApp.tsx:117 |
| CSV export of form submissions | formula injection is neutralised server-side (`publish/Forms.kt:150-151`, read only); not a finding | backend (read only) |
| CSRF | handled in `packages/api-client` (`X-XSRF-TOKEN`); not in scope | - |

## 9. H) ISSUE TABLE (all STATUS = OPEN)

Severity: P0 unusable / security-critical, P1 major functional / UX, P2 significant polish / performance, P3 minor. The TEST column is what must turn green after the fix. `H:` ids are in section 10.

| ID | PORTAL | ROUTE | STATE | SEV | CATEGORY | EXPECTED | ACTUAL | ROOT_CAUSE (file:line) | OWNER | PROPOSED FIX | TEST | STATUS |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S1-001 | Studio | `/projects/:id/design` | Edit mode, a page taller than the canvas | **P1** | UX / perf | selecting a section keeps the preview (scroll position, iframe) | every selection (tree or a click in the preview) rebuilds `srcDoc`: the iframe reloads, `scrollY` 500 -> 0, marker lost (H:A1,A2) | `BuilderWorkspace.tsx:163` (`html` depends on `selectedId`); `lib/schema-preview.ts:114` (selection class baked into the HTML) | S1 (+ owner of `lib/schema-preview.ts`, C5) | keep `srcDoc` independent of the selection; post `{type:"studio:select",sectionId}` to the iframe (its script already exists) to toggle `__sel`; or capture / restore `scrollY` | builder.spec: marker and scrollY survive a select; unit for the new message | OPEN |
| S1-002 | Studio | `/projects/:id/ai` | long history, or after sending | **P1** | UX | the newest message / progress is visible | `.conversation` stays at `scrollTop 0` while the content is 4,996 px (H:N1,N2); the answer appears below the fold | `ProjectWorkspace.tsx:319-333` (no scroll anchoring) | S1 | ref + effect: scroll to the bottom when `messages.length` / `live` changes unless the user scrolled up; "jump to latest" chip | harness N1,N2 | OPEN |
| S1-003 | Studio | `/projects/:id/ai` | assistant reply, AI progress | P2 | a11y | new replies are announced | the conversation has no `role=log` / `aria-live`; only the typing dots have `role=status` (H:N3) | `ProjectWorkspace.tsx:319-333` | S1 | `role="log" aria-live="polite"` on the list (progress is already live) | harness N3 + axe | OPEN |
| S1-004 | Studio | `/projects/:id/code` (STATIC_APP) | READY change that needs review | **P1** | functional / destructive | Cancel in the "approve" prompt aborts | `window.prompt(...) ?? undefined` turns Cancel (null) into "no comment" and the change IS approved (POST `/approve` sent, H:CW1): the four-eyes control is bypassed by a mis-click. The "who may approve" test also compares `createdBy` with `displayName` | `CodeWorkspace.tsx:122` (+ `:211`) | S1 | replace with an in-app dialog (Duyệt / Hủy + optional comment); `null` aborts; compare user ids if the DTO has one | harness CW1 | OPEN |
| S1-005 | Studio | `/design` | Inspector Nội dung / Giao diện | **P1** | UX / data loss / a11y | unsaved edits are kept or the user is warned; focus stays after "Lưu" | typing in "Tiêu đề", then selecting another section: the edit is gone silently (H:B1); after "Lưu thay đổi" focus falls to `<body>` (H:B2) | `Inspector.tsx:43,53` (`key` = section id + `JSON.stringify(props)` remounts `PropsForm`), `PropsForm.tsx:28` | S1 | keep drafts per section id in the Inspector (not keyed on props JSON); dirty badge + keep / discard prompt on selection change; restore focus to the save button | harness B1,B2; unit for the dirty logic | OPEN |
| S1-006 | Studio | `/design` rail Dữ liệu, Inspector Dữ liệu | binding data to a component | **P1** | UX flow | a non-technical user binds a list in a few guided steps | 7 steps (Nguồn dữ liệu, Khám phá cấu trúc, Truy vấn, Ánh xạ, ViewModel, Gắn vào thành phần, Dữ liệu công khai); two competing binding forms both labelled "Gắn dữ liệu" (ViewModel path vs public-query path) plus a third entry in the Inspector; asks for operation keys (`products.list`), slots, cardinality; >= 10 clicks | `builder/DataWizard.tsx:39-205`, `builder/PublicDataPanels.tsx:157-226`, `builder/Inspector.tsx:64-103` | S1 (design-level; C3 contract unchanged) | task-first flow ("Hiển thị dữ liệu trong danh sách này": pick source -> pick query -> pick fields -> confirm); ViewModel / Mapping under "Nâng cao"; one binding path; vocabulary pass | usability script + a new spec for the guided path | OPEN |
| S1-007 | Studio | `/design` at <= 760 px | phone | P2 | UX / responsive | the phone communicates "view and adjust": compact, no desktop-only controls | the top bar is 227 px of 844 (H:E-phone1), with 3 device-preview buttons that are meaningless on a phone, 6 text buttons, Chỉnh sửa / Dùng thử, a drag-handle gutter, and no sentence about phone use | `builder.css:146-160` (`.bx-top` rows), `BuilderTopBar.tsx:27-33,36-40`, `ProjectWorkspace.tsx:276-279` | S1 (+ S3 for css) | at <= 760: a one-line top bar + overflow menu; hide the device switch; banner "Trên điện thoại bạn xem và chỉnh nhẹ; dùng máy tính để sắp xếp trang"; hide drag handles | harness phone check: top bar < 80 px and the hint present | OPEN |
| S1-008 | Studio | `/studio` Home | create in flight | P2 | functional / double submit | one project per intent | `start()` has no `busy` guard; Ctrl/Cmd+Enter x3 sends 3 `POST /projects` (H:F1) | `StudioApp.tsx:112,127` | S1 | `if (busy) return` at the top of `start`; guard in `onKeyDown` | harness F1 | OPEN |
| S1-009 | Studio | `/settings` | clearing a value | P2 | functional | emptying "Tên miền xem trước", "Tên miền riêng", "Đích triển khai" clears them | empty values are omitted from the PATCH, so the old value stays (H:D2) | `drawers.tsx:49-51` (`...(d.domain ? {...} : {})`) | S1 | send `null` / `""` when the user cleared a field that had a value (confirm the PATCH semantics with C2) | harness D2 | OPEN |
| S1-010 | Studio | `/design` rail Trang / Giao diện | after a page is removed, a version restore, a conflict reload | P2 | state sync | editors show the current document | `MenuEditor`, `NotFoundEditor`, `ThemePanel` copy `doc` into `useState` once. After removing a page the menu still lists its link and "Lưu menu" is armed; saving re-adds a link to a deleted page (H:H1,H2), which the pre-check then blocks | `panels/PagesPanel.tsx:174,207`, `panels/MiscPanels.tsx:36` | S1 | `key` the editors on the relevant slice of `doc` (or derive + keep a separate dirty overlay) | harness H1,H2 | OPEN |
| S1-011 | Studio | all drawers + builder dialogs | form with typed input | P2 | UX / data loss | an accidental backdrop press does not discard a dirty form | `onMouseDown` on the overlay closes at once (H:D4) | `drawers.tsx:32`, `builder/ui/primitives.tsx:46` | S1 (F1 hook is S3) | ignore backdrop presses when dirty, or confirm; or require matching `mousedown` and `click` targets | harness D4 | OPEN |
| S1-012 | Studio | builder dialogs (12 uses) | focus on `<body>` inside the dialog | P2 | a11y / keyboard | Escape and the Tab trap always work | handlers sit on the dialog node; after clicking its text, focus is on body and Escape does nothing (H:C3); the container has no `tabIndex`; the trap only wraps at the first / last item | `builder/ui/primitives.tsx:28-52,36-44` | S1 | `tabIndex={-1}` on the container + a document-level handler (or reuse `useDialog` from `@xweb/ui`) | harness C3 | OPEN |
| S1-013 | Studio | `/publish` | release history | P2 | destructive clarity | switching the live site asks to confirm and names the version | "Phục vụ lại bản này" runs `rollback()` on one click (SPEC asserts this); unpublish confirms | `ReleaseModal.tsx:213` | S1 | a confirm step in the dialog ("Trang đang chạy phiên bản 3 sẽ được thay bằng phiên bản 2") | release.spec | OPEN |
| S1-014 | Studio | project workspace + code workspace | any `notice` | P2 | a11y / UX | the toast announces itself, leaves by itself, is never hidden | `<button class=toast>` has no `role` / `aria-live` (H:E1), stays until clicked (H:E2), `z-index:70` is below the builder overlay `80`, so a failure raised from a dialog is shown dimmed under it (H:PD2) | `ProjectWorkspace.tsx:375`, `CodeWorkspace.tsx:240`, `globals.css` `.toast`, `builder.css:77` | S1 (+ S3 css) | `role="status"` / `alert` by severity, 8 s auto-dismiss with pause on hover / focus, higher z-index | harness E1,E2 | OPEN |
| S1-015 | Studio | rail Trang dialogs | confirm pressed twice | P2 | double submit | one operation | "Thêm trang" has no in-flight guard: two `ADD_PAGE` with different ids are sent with the same revision (H:PD1); the second will 409 and the user sees "Project vừa được thay đổi ở nơi khác" | `panels/PagesPanel.tsx:128-146` (+ rename / remove dialogs) | S1 | a `pending` state disabling the buttons | harness PD1 | OPEN |
| S1-016 | Studio | rail Trang dialogs | save fails | P2 | error recovery | the dialog says why | the dialog shows only "Không lưu được. Hãy thử lại."; the real reason ("Đường dẫn trùng …") is only in a toast under the overlay (H:PD2) | `panels/PagesPanel.tsx:92,98,104` | S1 | pass the `applyOps` failure message into the dialog | harness PD2 | OPEN |
| S1-017 | Studio | rail Trang > Thêm trang | reserved or duplicate title | P3 | functional | the previewed path equals the saved path | the dialog shows `/api/` but `uniqueSlug` saves `api-2` | `panels/PagesPanel.tsx:130` vs `core/pages.ts:60` | S1 | use `uniqueSlug(doc, title)` in the hint | unit | OPEN |
| S1-018 | Studio | `/publish` | visibility choice | P2 | a11y | state exposed | "Riêng tư" / "Công khai" are plain buttons with a CSS class only (H:PM1) | `ReleaseModal.tsx:187` | S1 | `role="radiogroup"` + `role="radio" aria-checked` (or `aria-pressed`) | harness PM1 | OPEN |
| S1-019 | Studio | `/publish` | any | P2 | text leak | user wording | shows `pointerVersion 7 (…)`, `APP_PUBLISH` (text + 3 tooltips), `apiBase`, "Demo deployment" (H:P1) | `ReleaseModal.tsx:164,172,180,201,212,221,223` | S1 | remove / replace ("Bạn cần quyền xuất bản"), drop the pointer line (debug only) | H:P1 | OPEN |
| S1-020 | Studio | builder NOT_READY boxes | metadata / ops missing | P2 | text leak | explain what is missing in product terms | "(ADD_QUERY, ADD_ACTION…)", "DataGateway.discoverSchema chưa có đường HTTP", "(T15)", flags `app.data-platform.enabled` / `app.workflow.enabled`, `DATA_RUNTIME_UNAVAILABLE` | `builder/core/readiness.ts:39,41,43,55,58` | S1 | rewrite the reasons; keep codes in a collapsed "Chi tiết kỹ thuật" | unit on `STATIC_NOT_READY` strings | OPEN |
| S1-021 | Studio | several | always | P3 | text leak | no internal names | "revision N" in 3 top bars, `section.id` in the Inspector, "Lưu vào backend", "MinIO", "Mock / Self-host / Cloud", `workflow_run`, raw run status / cache / mode, template check ids, server error code appended, "OSV / SBOM", "CDN/tunnel", "openapi.json" | section 5 T08-T13 | S1 | wording pass | grep-based unit | OPEN |
| S1-022 | Studio | shell + builder | always | P3 | text | consistent Vietnamese | "Design", "Code", "Templates", "Components", "Company Components", "Workflow"; sidebar brand "Company Builder Studio" vs tab title "Xweb Studio"; raw enum option labels | section 5 T15,T16 | S1 | glossary + map enum -> label | unit | OPEN |
| S1-023 | Studio | all | always | P3 | text | one orthography; one verb per meaning | xoá / xóa, huỷ / hủy, tuỳ mixed on the same screens (for example "Xóa trang" in the Trang panel vs "Xoá trang" in the Website drawer); "Huỷ" = cancel (AI) and discard (code change) | `AiProgress.tsx:18`, `CodeWorkspace.tsx:172,215`, `SitePanels.tsx:49-114` vs `PagesPanel.tsx` | S1 | pick one convention ("xóa", "hủy"); discard = "Bỏ thay đổi" | grep unit | OPEN |
| S1-024 | Studio | AI mode; test mode | token limit hit; naming | P2 | text / terminology | instructions name real controls; one name per concept | the notice says choose "Mô phỏng" but the option is "Chế độ thử nghiệm (không dùng AI thật)"; "thử nghiệm" (AI mock), "Dùng thử" (test mode), "Kiểm thử" (panel title) all mean "not real" | `ProjectWorkspace.tsx:136,348`, `BuilderTopBar.tsx:29`, `TestPanel.tsx:137-139`, `BuilderWorkspace.tsx:193` | S1 | glossary: AI mock = "Chế độ thử nghiệm", app test = "Chạy thử" | unit | OPEN |
| S1-025 | Studio | Home, Projects | any non-website project | P2 | functional | the card shows the real kind | every card has a hard-coded "Website" pill with the PRIVATE tone (H:G1) although `ApiProject.appKind` exists | `StudioApp.tsx:97` | S1 | label from `appKind` | harness G1 | OPEN |
| S1-026 | Studio | Home | `/me/usage` or `/components` fails | P2 | error state | an error with retry | KPIs show "…" forever and the component chips area stays blank (H:F2); `usage.error` / `comps.error` are never read | `StudioApp.tsx:107-108,133-141,149` | S1 | ErrorState per card (reuse the existing component) | harness F2 | OPEN |
| S1-027 | Studio | rail Dữ liệu > Nguồn | DATA_SOURCE_VIEW, no slots | P2 | text / contradiction | one consistent message | the panel says there is "chưa có thao tác nào để thêm khe từ Studio … sẽ mở khi máy chủ hỗ trợ" directly above the working "Thêm khe" form | `builder/DataSourcesPanel.tsx:213` (vs `DataWizard.tsx:100`) | S1 | remove / reword the stale paragraph in the builder (keep for Admin if needed) | harness (text) | OPEN |
| S1-028 | Studio | `/design` mode Dùng thử | always | P2 | UX / expectation | trying the app = interacting with the preview | the preview is the same non-interactive canvas (`sandbox=""`); "try" = a list of "Chạy thử" buttons | `BuilderWorkspace.tsx:90` (`interactive = edit && !readOnly`), `Canvas.tsx:41` | S1 | rename ("Chạy thử thao tác") or provide a real interactive preview; explain in the banner | usability review | OPEN |
| S1-029 | Studio | `/projects/:id/code` (PAGE_SCHEMA) | click "Code" | P2 | UX / dead end | the tab exists only when it does something | the tab is always shown; the page says "Code Mode cần kiến trúc sinh mã nguồn … một giai đoạn sản phẩm riêng" (roadmap text) | `ProjectWorkspace.tsx:259-263,305-314` | S1 | hide the tab for page-schema projects (or disable it with a reason) | harness | OPEN |
| S1-030 | Studio | top bars, release modal, rail | disabled controls | P2 | a11y / mobile | the reason is reachable by keyboard and touch | every disabled reason (Chia sẻ, Xuất bản, Cài đặt, rollback, unpublish, run buttons) is a `title=`; disabled buttons are not focusable and touch has no hover | `BuilderTopBar.tsx:37-38`, `ProjectWorkspace.tsx:279,298-299`, `ReleaseModal.tsx:212,221,223`, `TestPanel.tsx` run buttons | S1 (+ S3: shared hint primitive) | `aria-disabled` + visible hint text next to the control | axe + keyboard check | OPEN |
| S1-031 | Studio | `/members` at 390 px | project member table | P2 | responsive | all controls reachable | the table is 440 px inside a 373 px drawer: the role select is clipped and the row's "Xóa" button is off-screen (H:PH-members) | `drawers.tsx:105-134`, `http.css` `.memberTable` | S1 | stacked card layout below 600 px | harness PH-members | OPEN |
| S1-032 | Studio | 12 flows | destructive confirmations | P2 | consistency | an in-app confirm that names the item and handles focus | 12 native `confirm()` + 1 `prompt()`; leaving yourself says "Xóa luan khỏi project?" | section 3 P24-P36; `drawers.tsx:165` | S1 | one `ConfirmDialog` built on F1 / F2; wording per action | harness per flow | OPEN |
| S1-033 | Studio | code workspace / server runtime | discard change, delete secret, rollback server | P2 | destructive clarity | confirm before irreversible actions | the discard POST is sent with no dialog (H:CW2); "Xoá" a runtime secret and "Khôi phục bản này" run immediately | `CodeWorkspace.tsx:125,215`, `CodePanels.tsx:132,139` | S1 | confirm dialogs; rename "Huỷ" to "Bỏ thay đổi" | harness CW2 | OPEN |
| S1-034 | Studio | `/code` editor | keyboard user | P2 | a11y | Tab leaves the editor (or an Escape-to-exit hint exists) | Tab is always swallowed and inserts two spaces (H:CW3): a WCAG 2.1.2 keyboard trap | `CodeWorkspace.tsx:185` | S1 | indent only with a modifier / toggle (Esc then Tab), show the hint | harness CW3 | OPEN |
| S1-035 | Studio | `/code` | dirty drafts | P2 | data loss | warn before losing typed code | there is no `beforeunload` / navigation guard for `drafts`; a reload loses them (SRC); `ProjectWorkspace` has one for page edits only | `CodeWorkspace.tsx` (`dirty`, no guard) | S1 | same pattern as `ProjectWorkspace.tsx:190-194` | harness | OPEN |
| S1-036 | Studio | `/projects/:id/*` | first load | P2 | functional | AI status for THIS workspace | the effect runs with `ws === ""`, so `GET /ai/status` is sent WITHOUT `workspaceId` (H request log) and is never refreshed; workspace-scoped policy / limits can be ignored by the model picker. Needs C2 to confirm what `workspaceId` changes | `ProjectWorkspace.tsx:112` (+ `:85`) | S1 (+ NOT C5: C2 confirms server semantics) | fetch after `project` is known (`useEffect` on `ws`) | harness: the request carries `workspaceId` | OPEN |
| S1-037 | Studio | shell at 390 px | any | P3 | responsive | usable header | the search box is clipped to "Tìm ứn…" next to workspace name, avatar and logout (screenshot) | `StudioApp.tsx:76-88` | S1 | collapse the search into an icon below 480 px | harness | OPEN |
| S1-038 | Studio | `/projects/:id` unknown or error | 404 / 500 | P3 | UX | clear title, no pointless retry, consistent theme | "Không tìm thấy" shown twice (title = message), "Thử lại" on a 404, a light page in a dark app (H:Q2 screenshot) | `ProjectWorkspace.tsx:249` (`wsError`), `packages/ui/src/ui.tsx` `ErrorState` | S1 / S3 shared UI | hide Retry for 404, add detail, dark theme class | harness Q2 | OPEN |
| S1-039 | Studio | Test mode | a query returned rows | P3 | a11y / css | the caption is visually hidden | `className="sr-only"` is not defined (only `.srOnly`), so the caption "Kết quả truy vấn {id}" is visible | `builder/TestPanel.tsx:212` | S1 | use `srOnly` | unit (class exists) | OPEN |
| S1-040 | Studio | Dữ liệu step tabs, Inspector tabs | NOT_READY | P3 | a11y | the badge has a separator and a label | "Khám phá cấu trúc" + "chưa" reads as one word | `DataWizard.tsx:89`, `Inspector.tsx:31`, `ui/primitives.tsx:78` | S1 | pass `badgeLabel` and a space | axe / SR | OPEN |
| S1-041 | Studio | `/design` | scrolling the preview | P2 | performance | scrolling does not re-render the Builder | one `studio:layout` per frame; each builds a new rect array -> `setRects` -> a full re-render (H:PERF1: 20 messages / 20 frames) | `Canvas.tsx:30`, `BuilderWorkspace.tsx:79` | S1 | shallow-compare rects before `setRects`; throttle; memoise panels | harness PERF1 | OPEN |
| S1-042 | Studio | Website drawer vs rail Trang | always | P2 | architecture | one pages / navigation / 404 editor | two independent implementations (SitePanels vs PagesPanel / MenuEditor / NotFoundEditor) with different validation (the old one: no reserved slugs, native confirm), duplicate `slugify`; dead `PageBar` | `SitePanels.tsx:10-100`, `panels/PagesPanel.tsx`, `SitePanels.tsx:14` | S1 | make the Website drawer reuse the builder components | unit | OPEN |
| S1-043 | Studio | all modals | stacked dialogs (latent) | P3 | architecture | one modal implementation; inert background; scroll locked on small screens | two implementations with different Escape / focus semantics; `useDialog` uses `stopPropagation` (two stacked dialogs would both close), no `inert`, no scroll lock below 900 px | `packages/ui/src/useDialog.ts:26` (S3), `builder/ui/primitives.tsx` (S1) | S3 shared UI + S1 | converge on one hook | unit | OPEN |
| S1-044 | Studio | code structure | always | P3 | architecture | small units, logic outside components | oversized files and logic in components; `previewDocument` built in Design mode where it is unused; unstable callbacks defeat memoisation | `ProjectWorkspace.tsx:219,258`, `StudioApp.tsx`, `PagesPanel.tsx:62-64`, `DataWizard.tsx:177` | S1 | split by screen; memoise handlers | - | OPEN |
| S1-045 | Studio | `/code` diff tab | typing in the prompt box | P3 | performance | no recompute | `lineDiff` re-runs for every file on every render, about 24 ms per keystroke for one 1,800-line file (H:CW4) | `CodeWorkspace.tsx:34-46` | S1 | `useMemo` per file | - | OPEN |
| S1-046 | Studio | Home -> AI mode | create from an idea | P3 | security hygiene | no user content in URLs | the prompt (<= 2000 chars) is put in `?prompt=` | `StudioApp.tsx:117` | S1 | hand over via `sessionStorage` / router state | unit | OPEN |
| S1-047 | Studio | several | the API gives a URL | P3 | security hygiene | only `http(s)` / the expected origin is followed | `<a href>` and `iframe src` of `downloadUrl`, `site.url`, `deployment.url`, `previewUrl` and `window.location.assign(r.redirect)` are unvalidated; the IDE clone token is shown in clear with no copy / mask | `drawers.tsx:94`, `ReleaseModal.tsx:170,202`, `CodeWorkspace.tsx:221`, `StudioApp.tsx:378`, `CodePanels.tsx:98-112` | S1 | a `safeHttpUrl()` helper (same-origin or https) | unit | OPEN |
| S1-048 | Studio | Projects / Templates / code tabs, New app | keyboard | P3 | a11y | WAI-ARIA tab / radio patterns | `role=tab` buttons have no `aria-controls` / arrow keys; the kind picker has `role=radio` cards that are each a Tab stop and ignore arrows (H:M1) | `StudioApp.tsx:164,207-211,285`, `CodeWorkspace.tsx` tabs | S1 (+ S3 `Tabs`) | reuse `builder/ui/primitives` `Tabs`; roving tabindex | harness M1 | OPEN |
| S1-049 | Studio | `/versions` | no versions; any | P3 | text / state | an empty text; a readable kind | 0 versions render the header only (H:K2); raw `MANUAL_EDIT` / `AI_GENERATED` (T14) | `ProjectWorkspace.tsx:378-381` | S1 | empty state + label map | harness K2 | OPEN |
| S1-050 | Studio | Inspector > Dữ liệu | any | P3 | text | Vietnamese labels | lists raw prop keys (`items`, `heading`) while `PropsForm` has `propLabel` | `Inspector.tsx:83` | S1 | use `propLabel` | unit | OPEN |
| S1-051 | Studio | archived project | banner | P3 | UX | the action is shown only to people who can do it | "Khôi phục" is shown to everyone, fails after the click ("cần quyền chủ sở hữu hoặc quản trị") and shares its name with version restore | `ProjectWorkspace.tsx:303-304` | S1 | gate on owner / admin permission when available; rename "Khôi phục ứng dụng" | - | OPEN |
| S1-052 | Studio | rail Workflow | editing steps | P2 | UX / jargon | steps chosen by name; a schedule helper | targets ("Khi lỗi, đi tới bước", branches, approve / reject routes) list raw step ids (`end`, generated ids); the schedule is a 5-field cron; retry in milliseconds; principal kinds raw (`USER` / `GROUP` / `ROLE` / `DEPARTMENT_MANAGER`) | `builder/WorkflowEditor.tsx:37,69,106,116-117` | S1 | label steps "Bước 2 - Phê duyệt"; cron presets; seconds | unit | OPEN |
| S1-053 | Studio | rail Hành động | chain / pristine form | P3 | UX | a simple chain picker; no alert on a pristine form | chains use `<select multiple>` (ctrl-click); a new action opens with `role=alert` "Chuyển trang cần chọn trang đích." before any input | `builder/ActionEditor.tsx:57,130` | S1 | checkbox list; show issues after the first interaction | harness AE | OPEN |
| S1-054 | Studio | Dữ liệu > Nguồn | delete a source | P3 | destructive clarity | danger styling | the confirm button of "Xóa nguồn" is `button primary` | `builder/DataSourcesPanel.tsx:218` | S1 | `danger` style | - | OPEN |
| S1-055 | Studio | rail Giao diện | save | P2 | UX | the user sees the effect | the panel itself says the preview does not apply the theme yet; values are saved invisibly | `builder/panels/MiscPanels.tsx:64` | S1 (renderer: `lib/schema-preview.ts` owner) | apply the theme tokens in `renderSchemaDocument`, or hide the rail entry until supported | H screenshot | OPEN |
| S1-056 | Studio | rail panels | switching rail tabs | P2 | state loss | a half-filled form survives a tab switch | only the selected rail panel is mounted; the query form, mapping fields, action / workflow editors lose their state (H:DW1, H:AE1) | `BuilderWorkspace.tsx:168-182` (`leftPanel` switch) | S1 | keep panels mounted (`hidden`) or lift the drafts | harness DW1,AE1 | OPEN |
| S1-057 | Studio | Templates / Components | repeated clicks | P3 | double submit | one request | `submit`, `act` (archive, withdraw, delete, send for review) have no in-flight state | `StudioApp.tsx:244,249,307,317` | S1 | disable while pending | unit | OPEN |
| S1-058 | Studio | test mode | narrow right column | P3 | cosmetic | one-line "Chạy thử" | the button wraps to "Chạy / thử"; nested "Chỉ đọc" boxes | H screenshot `test-mode.png` | S1 | `white-space:nowrap`; flatten | - | OPEN |
| S1-059 | Studio | all drawers | close a panel, press Back | P3 | navigation | closing returns to the page without extra history | every close is `router.push` (`go(mode)`), so Back re-opens the drawer you just closed; N panels = N Back presses | `ProjectWorkspace.tsx:377-399` (`onClose={() => go(mode)}`) | S1 | `router.back()` when the panel was opened from the app, or `replace` | harness nav log | OPEN |

Severity totals (authoritative): **P0 = 0, P1 = 5** (S1-001, 002, 004, 005, 006), **P2 = 32** (003, 007-016, 018-020, 024-036, 041, 042, 052, 055, 056), **P3 = 22** (017, 021-023, 037-040, 043-051, 053, 054, 057-059). Total 59.

Backend-caused items (OWNER NOT C5 + handoff): none confirmed. Two handoff questions: **S1-036** needs C2 to confirm what `GET /ai/status?workspaceId=` changes (model list / limits per workspace); if it changes nothing, close S1-036 as a no-op. **S1-009** needs C2 to confirm that omitting a field in `PATCH /projects/{id}` means "unchanged" and what clears it.

## 10. Probe log (HARNESS, NOT REAL BACKEND)

59 checks. `PASS` = the UI behaved as a user would expect, `FAIL` = it did not (the evidence for the issues above).

| Probe | Result | Detail |
|---|---|---|
| A1 select keeps the iframe | FAIL | marker lost: the iframe reloaded |
| A2 select keeps the scroll | FAIL | scrollY 500 -> 0 |
| B1 Inspector edit survives a selection change | FAIL | value reset to the saved text |
| B2 focus kept after "Lưu thay đổi" | FAIL | `activeElement` = BODY |
| C1 remove dialog role / aria-modal / name | PASS | |
| C2 initial focus on the safe button | PASS | "Hủy" |
| C3 Escape after clicking dialog text | FAIL | the dialog stays open |
| C4 Tab x6 stays inside | PASS | |
| C5 focus returns to the opener | PASS | |
| C6 backdrop click closes | PASS | (S1-011: also true for dirty forms) |
| C7 double-click on the destructive confirm | PASS | one PATCH |
| H1 menu drops the link of a removed page | FAIL | 1 row remains |
| H2 "Lưu menu" not armed | FAIL | enabled |
| F1 Ctrl+Enter x3 on Home | FAIL | 3 `POST /projects` |
| F2 Home KPIs on a 500 | FAIL | endless "…" |
| G1 DASHBOARD card label | FAIL | "Website" |
| M1 radiogroup arrows | FAIL | focus did not move |
| D1 settings drawer is a dialog | PASS | |
| D2 clearing fields is sent | FAIL | the body has no `domain` / `customDomain` / `deploymentTarget` |
| D3 drawer closes after save | PASS | |
| D4 backdrop on a dirty form | FAIL | closed, the edit was lost |
| D5 Esc + focus restore (versions) | PASS | |
| K1 restore asks for confirmation | PASS | native confirm |
| K2 empty versions text | FAIL | none |
| E1 toast announced | FAIL | no role / aria-live |
| E2 toast auto-dismiss | FAIL | still there after 9 s |
| Q1 unknown route | PASS | |
| Q2 unknown project | PASS | retry + back (but see S1-038) |
| Q3 schema 500 | PASS | |
| Q4 no APP_VIEW redirects | PASS | `/auth/no-access?portal=studio&reason=app-view` |
| R1 read-only banner | PASS | |
| R2 publish + settings disabled | PASS | |
| N1 AI history opens at the newest | FAIL | scrollTop 0 of 4,996 |
| N2 new answer in view | FAIL | |
| N3 replies announced | FAIL | |
| E-phone1 phone guidance text | FAIL | none |
| E-phone2 no h-scroll at 390 | PASS | |
| phone AI mode no h-scroll | PASS | |
| phone Home no h-scroll | PASS | |
| T1 test mode text has no internal names | FAIL | `workflow_run` x2 |
| P1 publish modal leaks | FAIL | `pointerVersion` |
| PH-settings / versions / assets / site / publish fit 390 | PASS (5 checks) | |
| PH-members fits 390 | FAIL | 440 > 373 |
| LC1 long name on a card | PASS | |
| LC2 long name in the top bar | PASS | ellipsis + `title` |
| SEC1 `rel` on external links | PASS | |
| CW1 Cancel in the approval prompt | FAIL | approve POST sent |
| CW2 discard asks for confirmation | FAIL | none |
| CW3 Tab leaves the code editor | FAIL | trapped |
| DW1 data draft survives a rail switch | FAIL | |
| AE1 action draft survives a rail switch | FAIL | |
| PD1 double-click "Thêm trang" | FAIL | 2 PATCH |
| PD2 reason visible in the dialog | FAIL | generic text only |
| PM1 visibility state exposed | FAIL | |
| PERF1 layout messages per scroll | FAIL | 20 / 20 frames |

axe-core (4.13) over 24 screens / states (Home, Projects, New, Templates, Components, Activity, AI mode, 8 rail panels, 4 Inspector tabs, Dùng thử, 5 drawers, Publish): 0 violations in every one.

## 11. Not verified (and why)

- Anything that depends on the real backend: payload shapes, 409 / 403 behaviour, the publish pipeline, AI streaming (the fake never streams, so `AiProgress` live states were read, not driven), data-source management routes, runtime routes. All `H:` evidence is a fake.
- `CodeWorkspace` / `CodePanels` beyond the approve / discard / editor / diff probes (design pane, packages, IDE, runtime drawers, merge policy, preview iframe): source reading only, not run at phone width.
- `StudioApp` TemplateCard / BlockCard action flows (submit, withdraw, archive, review checks) and the `SiteAccess` redirect: source reading only (they need server fixtures).
- Tablet width (768-1100 px) layouts were not screenshotted.
- Real screen-reader behaviour (VoiceOver / NVDA): only DOM / ARIA attributes and axe were checked.
- Touch drag-and-drop on a real phone: keyboard / pointer DnD is covered by `builder.spec.mjs` (87/87), touch was not tried.
- `PortalSwitcher`, `MenuButton`, `useNavDrawer`, `Pager`, `StateView`, `ErrorState` internals (S3).

## 12. Recreating the full-Studio harness used here (it is not in the repo)

1. `npm ci`; `npm i esbuild` outside the repo; `ESBUILD_DIR=<dir> node tests/browser/build-harness.mjs`.
2. A second esbuild bundle of `PortalApp portal="studio" render={(seg) => <StudioApp seg={seg} dedicated/>}` with aliases `next/navigation` -> a virtual router (path kept in module state, `?start=` gives the initial path) and `next/link` -> an `<a>` that calls that router; CSS imports of the five style files (`globals`, `responsive`, `http`, `factory`, `builder`).
3. A Playwright route `**/api/v1/**` answering `/auth/csrf`, `/auth/me`, `/auth/config`, `/me/usage`, `/components?details=true`, `/component-metadata` (404 or `[]`), `/ai/status`, `/projects/:id`, `/workspaces/:w/projects/**` (schema, versions, prompts, assets, members, site, code/**), `/workspaces/:w/data-sources/**`.
4. Run each probe with `node tests/browser/harness-server.mjs run -- node <probe>.mjs` (owned process, free port).
