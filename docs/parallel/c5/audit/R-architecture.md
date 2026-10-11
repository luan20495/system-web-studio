> **SUPERSEDED_BY:** `docs/QA_FINAL.md` - historical document, kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# C5-R — Frontend architecture, reuse, extensibility and debuggability review (2026-10-08)

Author: C5-R (research / architecture reviewer). Branch `agent/c5-r-arch`, base `agent/c5-web @ 9f858c2`. Documents only: no product code, script, `package.json` or test was changed; the only non-markdown files are three read-only analysis tools in `docs/parallel/c5/audit/tools/` (they read sources and write one JSON file to a path you give, nothing else).
Companion document: `docs/FRONTEND_ONBOARDING.md` (the entry point for a new person / AI).
Squad roles used in the OWNER column (assumption from the branch names `agent/c5-s1-audit`, `s2-audit`, `s3-audit`, `s4-perf`, `s4-tooling`, `r2-sec`; confirm with C5-L): **S1 = Platform portal, S2 = Admin portal, S3 = Studio portal, S4 = shared packages / tooling / performance, R = research (docs only)**, **NOT C5** = a file owned by C0 or another C-owner.

## 0. How this was measured (reproducible, no port, no process)

| What | How | Result |
|---|---|---|
| import graph, cycles, dead exports, `any` / `as never` counts | `node docs/parallel/c5/audit/tools/R-import-graph.mjs <repo> <out.json>` (TypeScript compiler API over `app apps components features lib packages workers`; tests, scripts and `e2e` counted as consumers) then `R-dead-exports.mjs <repo> <out.json>` (word-boundary grep per candidate) | 145 source files · 0 runtime cycles · 1 type-only cycle · 15 dead exports (+1 dead by chain) |
| which source files a test can reach | `node docs/parallel/c5/audit/tools/R-test-reach.mjs <repo>` (import graph from `tests/builder|page-runtime|lib/*.test.*` = unit, from `tests/browser/*harness*.tsx` = harness) | 121 files in `features packages lib components`: 89 reached, 32 not |
| typecheck | `npx tsc --noEmit` (root) and `npm run typecheck:packages` (3 apps + 6 packages) | both clean |
| unit | `npm run test:unit` | 306 tests: 305 pass, 0 fail, 1 skipped (23.9 s) |
| test classification | `npm run test:classify` | OK: 113 files · unit 28 · mock 6 · harness 17 · real-backend 62 |
| build + bundle | `HBL_ENV=local npm run build:platform|admin|studio`; summed `.next/static/**/*.js` | platform 942 KB (272 KB gzip) · admin 942 KB (272 KB gzip) · studio 1 048 KB (311 KB gzip) |
| `nextConfig` guard | `npm run build:platform` without `API_PROXY_TARGET` / `HBL_ENV` | fails with "API_PROXY_TARGET is required for a production build / start" (`packages/auth/src/server/nextConfig.ts:21`) |

Not run: any browser spec, `npm run test:e2e:real`, `scripts/portals.sh`, `scripts/run-local.sh`, `docs/parallel/c5/e2e-stack.sh` (need Chromium, Docker, a backend or a port; this task forbids long-lived processes and the ports 3001 3002 3003 3086 4000 4001 47xxx).

## 1. Architecture review

### 1.1 Shape (what exists)

```
 apps/platform (3001, /platform)   apps/admin (3002, /admin)   apps/studio (3003, /studio)       legacy root app: app/ components/ lib/ (3100, mock static export)
   layout.tsx + entry.tsx + [[...slug]]/page.tsx + proxy.ts (CSP) + next.config.ts                      app/[[...slug]] -> components/app/AppEntry -> StudioShell | features/*
        │ PortalApp(portal, render)                       │                                                    │
        ▼                                                  ▼                                                    ▼
 packages/auth  (PortalApp router + session + AuthPages + server/{csp,nextConfig})                       (own copy of the router in AppEntry.tsx)
        │                                                                                                      
        ▼                                          features/admin  (AdminApp = Platform AND Admin consoles)     features/studio (StudioApp, ProjectWorkspace, builder/)
 packages/ui  ·  packages/permissions  ·  packages/i18n            features/admin/{*Model.ts pure}, {*Screens.tsx presentational}, {*Live.tsx wiring}
        │                                                                          │
        ▼                                                                          ▼
 packages/api-client  (core.call/stream: CSRF, idempotency, ApiError;  api.ts: one `api` object;  release.ts, runtimeConfig.ts)   ──►  same-origin /api  ──►  Spring API
        │
        ▼
 packages/types  (MIRROR of the backend contract; contract/v2/*)            packages/company-ui, packages/app-sdk: published for generated apps, not used by the portals
```

Good structure that exists and should be preserved:
- **Package direction is acyclic at runtime** (0 runtime cycles over 145 files): `types ← api-client ← ui/permissions ← auth ← apps`.
- **Builder `core/` is pure** (`features/studio/builder/core/*.ts`, about 1 970 lines, no React, relative imports only) and is the best-tested code (`tests/builder/*.test.ts`, 29 files). UI → `core` → contract mirror is a clean layering.
- **Adapter + capability table** for backends that do not exist yet (`features/admin/organization.ts:32` `CAPABILITIES`, `provisioning.ts:18`, `builder/core/readiness.ts`): a missing backend is `NOT_READY`, nothing is invented.
- **Model / Screens / Live split** in the newer Admin features (`organizationModel.ts` + `OrganizationScreens.tsx` + `OrganizationLive.tsx`/`organizationAdapter.ts`): the presentational part is mounted by the browser harness with a fake transport. This is the pattern the oversized files should converge to.

### 1.2 Feature boundaries and layering violations

| # | Finding | Evidence | Effect |
|---|---|---|---|
| L1 | Platform and Admin are **one feature, one file**: both portals render `features/admin/AdminApp.tsx` with `portal="platform"` / `"admin"` (`apps/platform/app/entry.tsx`, `apps/admin/app/entry.tsx`). | Platform chunk set = Admin chunk set (942 349 vs 942 343 bytes); the Platform bundle contains "Cơ cấu tổ chức", the Admin bundle contains the SYSTEM_ADMIN pages. | A change to a Platform page can regress Admin; each portal ships the other's code. |
| L2 | **admin → studio** dependency: `features/admin/TenantScreens.tsx:20-21` imports `DataSourcesPanel` and `DataManagementCalls` from `../studio/builder/…`, and passes `{ page: "", sections: [] } as never` (`TenantScreens.tsx:350`) as a fake document. | grep | the Studio builder panel is bundled into Platform and Admin; the panel's props leak Builder concepts (`doc`). |
| L3 | **components ↔ features**: `components/` mixes two live helpers used by Studio (`SectionInspector.tsx`, `useDialog.ts`) with the legacy shell (`StudioShell.tsx`, `app/AppEntry.tsx`). Directory-level SCC: `features/admin → features/studio → components → features/admin` (via `components/app/AppEntry.tsx:10`). | graph | not a file cycle; it blocks deleting the legacy shell and makes `components/` meaningless. |
| L4 | **Three spellings of the same import** and a shim layer. `features/ui.tsx`, `session.tsx`, `routing.ts`, `useLoad.ts`, `auth/AuthPages.tsx`, `admin/Modal.tsx`, `components/useDialog.ts`, `lib/http-api.ts`, `lib/http-types.ts` are 2-line re-exports ("Moved to packages/…"). 23 imports reach into `../packages/<pkg>/src/…` by relative path (e.g. `builder/BuilderWorkspace.tsx:13`, `builder/panels/PagesPanel.tsx:8`, `builder/core/permissions.ts:10`). `@/lib/http-api` (14 files), `@xweb/types` (37), `@xweb/ui` (12). | grep | the same symbol is reachable three ways; moving a package file breaks relative imports; the shims hide the real dependency (`features/*` → `packages/*`); 22 files import through a shim. **Cause (a real constraint, not carelessness):** `scripts/test-unit.mjs:2` compiles tests with `tsc` to CommonJS (`tests/tsconfig.json`: `module commonjs`, `moduleResolution node10`) and runs plain `node --test`; `@xweb/*` resolve to `.ts` sources (`"main": "src/index.ts"`), which node cannot load, so every unit-tested module must import at runtime by relative path. |
| L5 | `packages/permissions/src/index.ts` contains **legacy single-app helpers** (`Portal`, `rememberPortal`, `resolvePostLogin`, `isAdmin`, `hasWorkspace`, lines 94–141) next to the three-portal code, used only by `components/app/AppEntry.tsx`. | read | the shared package carries the legacy router's logic. |
| L6 | `lib/types.ts` holds `DeviceMode`, which **live** Studio code imports (`ProjectWorkspace.tsx:9`, `drawers.tsx:12`), next to the mock-demo types (`Product`, `Testimonial`). | grep | live code depends on a file that belongs to the legacy demo. |
| L7 | `lib/schema-preview.ts:24,31,32` keeps the render context in module-level `let ctx / up / site`; `renderSchemaDocument` (:108) mutates them. It is also imported by `workers/render` (C2). | read | not re-entrant; correct today only because rendering is synchronous. Hot file: change needs the owner. |
| L8 | `features/admin/base.ts:12-17` keeps the console identity (`portal`, `base`) in module variables set during render by `setAdminPortal(portal)` (`AdminApp.tsx:54`). | read | impure render; `A()`, `owns()`, `adminPortal()` return different values depending on call order; two consoles cannot be mounted in one test page; the legacy `"all"` mode is still a live branch of every helper. |

### 1.3 Oversized components and concrete split points

Source sizes (lines): `AdminApp.tsx` 1 197 (121 KB; 80 lines are longer than 240 characters; 49 function components) · `ProjectWorkspace.tsx` 403 · `StudioApp.tsx` 394 · `AiSetup.tsx` 365 · `api.ts` 357 · `TenantScreens.tsx` 355 · `StudioShell.tsx` (legacy) 321.

**`features/admin/AdminApp.tsx` (49 function components + about 10 label maps in one module).** Split along the existing seams; each target file is a pure move (no behaviour change) and keeps `AdminApp` as the only export used by `apps/*`:

| Target file | Lines (current) | Contents |
|---|---|---|
| `admin/console/navigation.ts` | 24–51 | `NAV`, `COMING`, `NAV_COMING`, `SCOPED_NAV`, `navFor`, `CONSOLE_NAME` (the section registry; see R-009) |
| `admin/console/AdminApp.tsx` | 53–72 | the shell only |
| `admin/console/routes.tsx` | 74–133 | `route()`, `NeedsPlatform`, `NeedsScope`, `ComingSection`, `ElsewhereNote` |
| `admin/console/AdminChrome.tsx` | 135–160 | `AdminSidebar`, `AdminHeader` |
| `admin/pages/Overview.tsx` | 161–228 | `AuditTable`, `Overview`, `SetupChecklist` |
| `admin/pages/UsersPages.tsx` | 229–356 | `UsersPage`, `UserList`, `UserDetail`, `WorkspaceList`, `WorkspaceDetail` |
| `admin/pages/ApplicationsPages.tsx` | 357–437 | `AppTable`, `AppsPage`, `AppDetail` |
| `admin/pages/AiUsagePages.tsx` | 438–576 | `UsageTable`, `DailyBars`, `AiCallsLog`, `AiMonthCard`, `PricingCard`, `AiPage` (next to `AiSetup.tsx`) |
| `admin/pages/RegistryPages.tsx` | 577–705 | `ComponentsPage`, `RegistryTable`, `BlocksAdmin`, `BlockReviewPanel`, `TemplatesAdmin` |
| `admin/pages/AuditPage.tsx` | 706–734 | `AuditPage` |
| `admin/pages/SystemPages.tsx` | 735–881 | `HealthPage`, `BuildsPage`, `PackagesPage`, `SettingsPage` |
| `admin/pages/AiGovernancePage.tsx` | 882–984 | `ScopePicker`, `AiGovernancePage` |
| `admin/pages/OperationsPages.tsx` | 985–1071 | `AlertsPage`, `SecurityPage`, `CostTable`, `CostsPage` |
| `admin/pages/IdentityPages.tsx` | 1072–1196 | `DepartmentsPage`, `IdentityPage`, `ConnectorsPage`, `BackupsPage` |

Order of work with the least regression risk: (1) move `navigation.ts` + `routes.tsx` (nothing else imports them), (2) one `pages/*.tsx` per commit, each followed by `tsc` + `scripts/ui-audit.mjs --only platform,admin` against a stack (the only check that opens every route). A second phase makes the Platform / Admin split real by giving each portal its own registry (so the Platform bundle stops containing tenant screens).

**`features/studio/ProjectWorkspace.tsx` (`ProjectWorkspace`, lines 45–402: one function, 24 `useState`, 12 `useEffect`).** Business logic that is not rendering and has no test (the file is reachable from no test):

| Extract | Lines | Why |
|---|---|---|
| `useProjectData(projectId)` | 77–106, 103–119 (`reload`, registry, blocks, assets, ai, probe) | loading + refresh timers (8-minute asset refresh, :114) |
| pure `noticeFor(error)` + `useGuardedRun()` | 120–142 (`run`: save state machine, ABORTED, retryable vs final, REVISION_CONFLICT, AI_TOKEN_LIMIT, 409/422/429/TENANT_SUSPENDED mapping) | the most bug-prone logic in the portal; pure part is unit-testable (`builder/core/errors.ts` already holds `explainError`) |
| `useAiChat()` / `core/chat.ts` | 143–181 (`toMessages`, `submitPrompt`, stream callbacks, hand-over of `?prompt=`) | `usageChip` (:37) and `toMessages` (:129) are pure and untested |
| `useSaveQueue()` | 183–194 (`applyOps`, `failedEdit`, `beforeunload`) | one write funnel |
| `core/blocks.ts` `blockToOps(b, pageSections)` | 210–222 (`addBlock`) | pure plan, same shape as `planAdd` in `core/dnd.ts` |
| `core/clients.ts` `makeRuntimeCalls(api, ws, pid)`, `makeDataManagementCalls(api, ws, pid)` | 224–247 | pure mapping `api` → `RuntimeCalls` / `DataManagementCalls`; testable with a fake `api` |
| `AiChatPane`, `PreviewPane`, `CodeModeNotice`, `LegacyTopBar`, `WorkspaceDrawers` | render 266–402 (`topbar` 281–301 duplicates `builder/BuilderTopBar.tsx`; AI pane 318–358; preview 359–376; drawers 377–402) | the render is one 136-line expression |

**`features/studio/StudioApp.tsx`**: eight screens in one file — `Home` 104, `Projects` 155, `NewApp` 176, `TemplateCard`/`Templates` 240/276, `BlockCard`/`Components`/`BlocksSection` 304/330/352, `SiteAccess` 372, `Activity` 385. Split into `studio/screens/*.tsx`; keep `StudioApp` (shell + route table, lines 24–91).

**Not oversized, fine as is:** `builder/BuilderWorkspace.tsx` (231 lines, but see R-024: 33-prop bag and a closed rail registry), `builder/core/*`, `ReleaseModal.tsx`.

### 1.4 Duplicated logic (evidence table)

| Pattern | Copies (file:line) | Proposed extraction (clear benefit, low risk) |
|---|---|---|
| `async function act(fn) { setBusy/setErr; try { await fn(); reload() } catch { setErr(errText…) } }` | 13: `AdminApp.tsx:398,636,664,1076,1111,1144` · `TenantScreens.tsx:66,278` · `StudioApp.tsx:244,307` · `drawers.tsx:154` · `CodePanels.tsx:122` · `SitePanels.tsx:125` | `useAction({ onDone })` in `packages/ui` returning `{ run, busy, error, notice }`; behaviour kept per call site by options |
| list-page ladder `error ? <ErrorState/> : loading && !data ? <StateView loading/> : empty ? <StateView empty/> : <table/>` | 28: `AdminApp.tsx` ×18, `AiSetup.tsx` ×4, `StudioApp.tsx` ×4, `EmployeesScreens.tsx`, `OrganizationScreens.tsx` | `<LoadGate state empty>` wrapper (renders the right `StateView`); no markup change |
| search + filter form with `setPage(0)` | 11 `className="filters"` forms, 21 `setPage(0)` | `useListQuery({ page, q, filters })` hook; keep each form's markup |
| table scaffold (`<table className="table">` + clickable rows + `<Pager>`) | 41 `<table>` in features (32 in `AdminApp.tsx`; 37 `className="table"`), 6 `clickRow`, 8 `<Pager>` | **do not** abstract the markup (columns differ); extract only the `clickRow` keyboard behaviour |
| native confirm | 30 `confirm(`/`window.confirm(` (AdminApp ×12, AiSetup ×3, drawers ×3, SitePanels ×3, TenantScreens ×3, StudioApp ×2, ProjectWorkspace ×2, ReleaseModal, CodePanels) | `useConfirm()` + one `ConfirmDialog` on `Modal` (R-013) |
| dialog scaffolds | `Modal` (`packages/ui/src/Modal.tsx`, 14 uses), `useDialog` (`packages/ui/src/useDialog.ts`, used by `drawers.tsx:30`, `ReleaseModal.tsx:86`, legacy `StudioShell.tsx:244,286,304`), builder `Dialog` (`builder/ui/primitives.tsx:28`), `@company/ui` `Dialog`/`Modal` — four focus-trap implementations with different rules (scroll lock only in `Modal`; overlay click close only in builder `Dialog`; `Modal` Escape does not `stopPropagation`, `useDialog` does) | one `useFocusTrap` hook shared by all three; keep the three visual shells |
| form field | `Field` in `studio/drawers.tsx:41` and `builder/ui/primitives.tsx:86` (different props), 48 hand-written `className="field"` labels in Admin | one `Field` in `packages/ui`; migrate opportunistically |
| error → Vietnamese text | `errText` (`packages/ui/src/ui.tsx:21`) **and an identical copy** `studio/drawers.tsx:16`; `adminErrorText` (`adminModel.ts:155`); `orgProblem` (`organizationModel.ts:204`); `provisioningProblem` (`provisioningModel.ts:79`); `explainError` (`builder/core/errors.ts:30`); `explainManagementError` (`core/dataManagement.ts:51`); `explainReleaseError` (`api-client/src/release.ts:109`); `describeRuntimeConfigError` (`api-client/src/runtimeConfig.ts:120`); `outcomeFromError` (`core/testMode.ts:81`); `readinessFromError` (`core/readiness.ts:24`) | a single `packages/i18n` code → text table used by all of them as the base layer (each keeps its contextual wording on top); the same code (`FORBIDDEN`, `VERSION_CONFLICT`, `NETWORK`) must not read differently on two screens |
| permission → scope | `adminScope` (`features/admin/adminModel.ts:19`) and `capabilitiesOf` (`packages/permissions/src/index.ts:~35`) both derive "platform / tenant admin / MEMBER_MANAGE" from `Me` | `adminScope` built on `capabilitiesOf` |
| section registry of the Admin console | `NAV` (`AdminApp.tsx:24`) · `OWNED` (`base.ts:23`) · `SYSTEM_ONLY` (`adminModel.ts:45`) · `route()` switch (`AdminApp.tsx:93–111`) · `COMING` (:30) | one `SECTIONS` table: `{ key, label, icon, portals, access, render }` (R-009) |
| role labels | `packages/i18n/src/index.ts:14` (`ROLE_LABEL`, only used by the dead `roleName`), `features/admin/UserDialogs.tsx:7` (`ROLE_LABELS`, dead), `features/studio/drawers.tsx:101` (`roleLabel`, live), `adminModel.ts` `TENANT_ROLES`: **three different Vietnamese wordings** for the same role ("Quản trị workspace" vs "Quản trị không gian làm việc"; "Biên tập" vs "Biên tập viên" vs "Người chỉnh sửa") | keep one map in `packages/i18n` |
| `slugify` | `studio/SitePanels.tsx:10`, `builder/core/pages.ts:24`, `admin/adminModel.ts:71` | one in `packages/i18n` (pure) |
| page management UI | `SitePanels.tsx` `PagesSection` (:27) / `NavigationSection` (:69) / `NotFoundSection` (:93) vs `builder/panels/PagesPanel.tsx` (221 lines) + `core/pages.ts` | `SitePanels` should call the `core/pages.ts` plans (tested) instead of its own `apply(ops)` code |
| `fmt` date | `drawers.tsx:15`, `ReleaseModal.tsx:18` vs `ui.tsx:7` `fmtDate` | use `fmtDate` |
| persisted UI choice | `localStorage "studio-ai-model"` read/written in `CodeWorkspace.tsx:65,76` **and** `ProjectWorkspace.tsx:83,115`; `"studio-ws"` `StudioApp.tsx:31,34`; `sessionStorage ws-mode-*` `ProjectWorkspace.tsx:49–51` | `usePersistentState(key, default)` (try/catch once) |
| Portal router | `packages/auth/src/PortalApp.tsx:26-47` and `components/app/AppEntry.tsx:27-48` | goes away with the legacy app |

### 1.5 Circular dependencies (computed)

| Level | Result |
|---|---|
| runtime (value imports), file level, 145 files | **0 cycles** |
| including `import type` | **1**: `packages/types/src/index.ts` → `./contract/v2` (`export *`) → `contract/v2/index.ts` → `appDefinition.ts` → `import type { PageSchema, Seo } from "../../index"` (`appDefinition.ts:10`). Erased at compile time; harmless today. Fix when touched: move `PageSchema`/`Seo` to `contract/v2/meta.ts` (contract file: needs C0). |
| directory level (`features/admin`, `features/studio`, `components`, `packages/*`, `apps/*`, `lib`) | **1 SCC of 3**: `features/admin → features/studio` (`TenantScreens.tsx:20-21`) → `components` (`drawers.tsx:13`, `StudioApp.tsx:8`, `ReleaseModal.tsx:14`, `ProjectWorkspace.tsx:10`) → `features/admin` (`components/app/AppEntry.tsx:10`). Disappears when the legacy shell is removed and `SectionInspector.tsx` / `useDialog.ts` move out of `components/`. Package graph: `types ← api-client ← permissions/ui ← auth ← apps`; `ui → api-client` (`ui.tsx:5` for `ApiError`) and `auth → ui` are the only upward-looking edges and are acyclic. |

### 1.6 Dead code (computed, then verified by `grep -w` over `features packages components lib apps app tests workers e2e scripts`)

223 exports have no importer. 50 are in `packages/company-ui` and `packages/app-sdk` (published for generated apps, not consumed here: out of scope). Of the other 173: **113 are used only by tests** (deliberate test seams, e.g. `builder/core/publicData.ts` ×27), **38 are used only inside their own file** (the `export` keyword is unnecessary), and **15 are referenced nowhere** (+1 dead by chain):

| Dead export | File:line | Evidence |
|---|---|---|
| `PageBar` | `features/studio/SitePanels.tsx:14` | one occurrence in the repo |
| `useRectsState` | `features/studio/builder/Canvas.tsx:72` | same |
| `slotNeighbours` | `builder/core/dnd.ts:72` | same |
| `eventsOf` | `builder/core/inspector.ts:73` | same (`eventsFor` in `actions.ts` is the live one) |
| `BoundElementState`, `LIST_ROW_FIELDS` | `builder/core/publicData.ts:273,38` | same |
| `isReady` | `builder/core/readiness.ts:15` | same |
| `AppMode`, `testModeReadiness` | `builder/core/testMode.ts:17,50` | same (`BuilderWorkspace.tsx` spells `"EDIT" \| "TEST"` inline) |
| `isCompareOp`, `isStepKind` | `builder/core/workflow.ts:102,18` | same |
| `roleName`, `PORTAL_TEXT` | `packages/i18n/src/index.ts:18,21` | same; `ROLE_LABEL` (:14) is then used only by `roleName` |
| `ROLE_LABELS`, `roleLabel` | `features/admin/UserDialogs.tsx:7,10` | the file's only live export is `LinkBox` (:14), imported by `AdminApp.tsx:8` and `ProvisioningScreens.tsx:11` (the name `UserDialogs` is misleading since `CreateUserDialog` was removed) |

Orphan **files** in `apps/ packages/(ui,auth,api-client,permissions,i18n,types) features components lib`: none unreachable from an entry. Reachable only from the legacy root app: `components/StudioShell.tsx`, `components/app/AppEntry.tsx`, `lib/api-client.ts`, `lib/mock-data.ts`, and 2 of the 3 types in `lib/types.ts`. `workers/render/*` is C2's and reachable from tests only.

### 1.7 Stale mocks and the legacy root app (REPORTED ONLY; owner C0 decides)

`app/[[...slug]]/page.tsx` → `components/app/AppEntry.tsx` → `isDemoMode` (`NEXT_PUBLIC_API_MODE` unset = `mock`) → `components/StudioShell.tsx` (321 lines) → `lib/api-client.ts` (91) → `lib/mock-data.ts` (42). `npm run build` still compiles it as a static export (GitHub Pages); `docs/LOCAL_DEVELOPMENT.md` still describes it as "the" UI on `:3100`. In http mode the same root app is a fourth copy of the portal router that imports `features/admin` and `features/studio` (`AppEntry.tsx:10-11`). The mock path answers with canned data (`mock-data.ts`) and a keyword "AI" (`api-client.ts` `applyMockPrompt`): it is a demo of the first product, not of V2, and the two files are the only consumers of the `Product`/`Testimonial` types.

### 1.8 State management

- Server state: `useLoad` (`packages/ui/src/useLoad.ts`, 17 lines; about 70 call sites): fetch on mount, ignore stale responses, `reload`, `setData`. No cache, no request cancellation (stale results are discarded, the request is not aborted), no de-duplication. Several screens re-request the same reference data (`api.components()`, `api.blocks()`, `api.aiStatus()` in `ProjectWorkspace.tsx:101,112`, again in `StudioApp.tsx`).
- Session: one React context (`packages/auth/src/session.tsx`), `onUnauthorized` handler singleton in `api-client/src/core.ts:29`.
- Everything else is component-local `useState` (`useState(false)` ×46, `setBusy/setErr/setError` ×116). The only other contexts: `StudioApp.tsx:20` `Ctx` (workspace id) and the Builder `DefCtx` (`builder/ctx.ts`, a single write funnel `commit`).
- Module-level mutable state: `features/admin/base.ts:12-13`, `lib/schema-preview.ts:24,31,32`, `api-client/src/core.ts:11,29` (CSRF promise and unauthorized handler: acceptable singletons).
- Verdict: consistent for a 3-portal admin tool (one pattern, no Redux/SWR mix) but at the point where `useLoad` needs `AbortSignal` + keyed cache, the cheapest upgrade is inside `useLoad` itself (R-036).

### 1.9 Error handling

| Aspect | State |
|---|---|
| Error boundaries | **none**: no `error.tsx`, `global-error.tsx`, `not-found.tsx`, `ErrorBoundary`, `componentDidCatch` in `apps/*/app`, `app/`, `features`, `packages` (grep). A thrown render error unmounts the portal (Next's default error page); in the Builder the unsaved in-flight edit is lost (`beforeunload` guard in `ProjectWorkspace.tsx:189-194` does not cover a crash). |
| API errors | one `ApiError {status, code, message, requestId, details, retryable, retryAfterSeconds}` (`core.ts:4`); two wire shapes normalised (`core.ts:38`); 401 → `onUnauthorized` → `/auth/session-expired` (`session.tsx:27-34`); CSRF retry once (`core.ts:68`); `NETWORK` / `TIMEOUT` (15 s, `core.ts:52,62`) are status 0. |
| Mapping to Vietnamese text | 10 mappers (table 1.4); `StateView`/`ErrorState` (`ui.tsx:31-47`) give a generic title per `stateOf(error)`; 49 `<ErrorState>` usages. |
| `requestId` | shown only through `errText` (`ui.tsx:21`: "… (mã req_…)") and as a column of the audit table (`AdminApp.tsx:172`); **read only from the JSON body** (`core.ts:73`), never from the `X-Request-Id` response header that the backend sets on every response and exposes to CORS (`backend/src/main/kotlin/com/systemwebstudio/common/RequestIdFilter.kt:21`, `identity/SecurityConfiguration.kt:77`); screens that print `e.message` directly (e.g. `CodePanels.tsx:122`) drop it; a `NETWORK` / `TIMEOUT` failure has none. The backend **accepts a client-supplied `X-Request-Id`** (`RequestIdFilter.kt:18`, pattern `^[A-Za-z0-9._-]{8,64}$`, allowed by CORS `SecurityConfiguration.kt:76`), so the client can mint the id itself. |
| Destructive confirmation | 30 native `confirm()` calls (browser-language buttons, unstyled, not reachable by the harness without `page.on("dialog")`). |

### 1.10 Type safety

Strict mode on; **0** `any` keyword, **0** `@ts-ignore` / `@ts-expect-error`. Escape hatches: `as never` ×9 (`builder/ActionEditor.tsx:51,111,120` and 1 more, `WorkflowEditor.tsx:27,106`, `Inspector.tsx:143,144`, `TenantScreens.tsx:350`), `as unknown as` ×2 (`builder/core/definition.ts:75`, `workers/render/ast.ts:19`), non-null `!` ×90 (`AdminApp.tsx` ×39 after `loading && !data` guards, `StudioApp.tsx` ×19 on `me!`). 12 `// eslint-disable-line react-hooks/exhaustive-deps` comments exist but **there is no ESLint (or Prettier) configuration or dependency in the repo**, so the rule they silence is never run (R-026). `packages/api-client/src/api.ts:21-33` copies contract patterns "kept local: this module must load under plain node" (same constraint as L4).

### 1.11 Test seams

| Seam | Quality |
|---|---|
| pure `core/` and `*Model.ts` modules | excellent: unit-tested, relative imports (L4) |
| SSR component tests (`tests/builder/*.test.tsx`, `react-dom/server`) | good for markup and ARIA, none for interaction |
| browser harness (`tests/browser/*-harness.tsx`) | one tiny `index.html` + entry per feature, fake transport behind the REAL adapter (`org-harness.tsx`, `prov-harness.tsx`); 17 files classified `harness` |
| injectable calls | `ReleaseCalls` (`ReleaseModal.tsx:21`), `RuntimeCalls`, `DataManagementCalls`, `OrganizationApi`, `ProvisioningApi`: good pattern |
| not testable without a stack | `AdminApp.tsx`, `TenantScreens.tsx`, `ProjectWorkspace.tsx`, `StudioApp.tsx`, `CodeWorkspace.tsx`, `CodePanels.tsx`, `SitePanels.tsx`, `drawers.tsx`, `libraryPanels.tsx`, `library.tsx`, `packages/auth/src/{PortalApp,AuthPages,session}.tsx`, `packages/auth/src/server/csp.ts` — **14 files, about 3 900 lines, reachable from no unit or harness test** (`R-test-reach.mjs`). They call the `api` singleton directly (`import { api } from "@/lib/http-api"`: 14 files), so there is no seam to fake. |

## 2. Reusability score (evidence)

Score 0–10 per area; "shared" = one implementation used by all three portals.

| Area | Score | Shared | Copy-pasted (file:line) | Extract? (benefit / risk) |
|---|---|---|---|---|
| Shared UI (`packages/ui`: `StateView`, `Card`, `Pill`, `Pager`, `Modal`, `Picker`, `Switch`, `NavDrawer`, `ScrollRegion`, `PortalSwitcher`, icons) | 7 | one package, 3 portals, a11y fixes land once (UI-02/07/23) | Builder has its own `bx-*` set (`builder/ui/primitives.tsx`: `Dialog`, `Tabs`, `Field`, `IconButton`, `StateBox`); 4 button families (`.btn` 169 uses, `.button` 67, `.bx-btn` 51, `.smallButton` 66) | unify `Field`/focus trap (high / low); do **not** merge `bx-*` visuals |
| Shared auth (`packages/auth`) | 8 | `PortalApp` + session + `AuthPages` for 3 portals, CSP and `nextConfig` factories | router copy in legacy `AppEntry.tsx:27-48` | delete with the legacy app |
| Shared API client | 8 | one `call()` (CSRF, idempotency, timeout, error normalisation, 401 hook), one `ApiError`, one `api` object | `api.ts` is a 313-line single object (`:43-356`); contract regexes duplicated (`:21-33`) | split per domain only when a domain grows (R-035) |
| Table / list patterns | 3 | `Pager`, `Pill`, `Card`, `StateView` | 41 `<table>`, 28 load ladders, 11 filter forms, 21 `setPage(0)` | extract the ladder and the filter state, not the markup (medium / low) |
| Dialogs | 4 | `Modal` + `ModalHeader` for 14 Admin dialogs | 4 focus-trap implementations; 30 native confirms | shared focus-trap hook + `ConfirmDialog` (high / low) |
| Empty / error / loading | 6 | `StateView` / `ErrorState` (49 uses) incl. `level` for the single `h1` | `StateBox` in the Builder, hand-written `<p className="hint">` in several panels; `Loading…` strings spelled differently | `LoadGate` (medium / low) |
| Forms | 3 | `Picker`, `Switch`, `ModalHeader`, `PersonPicker` | 48 hand-written `className="field"`; 2 `Field` components; validation re-implemented in `provisioningModel.ts`, `organizationModel.ts`, `adminModel.ts` (`slugify`, regexes) | one `Field`; leave validation per feature (they differ by contract) |
| Search / filter | 3 | `PersonPicker` (debounced, tested) | `UserList` (`AdminApp.tsx:240`), `WorkspaceList`, `AppsPage`, `AuditPage:706`, `EmployeesScreens.tsx` debounce | `useDebouncedValue` + `useListQuery` |
| Responsive helpers | 6 | `useNavDrawer`, `useOverflow`, `ScrollRegion`, phone switch in `BuilderWorkspace` (CSS-driven, all panes mounted) | 14 different `@media` thresholds in 5 CSS files (900, 760, 720, 700, 600, 520, 768, 767, 1023, 1100, 1279, 1760, 1920) with no token | document 3 canonical widths (≤ 900 drawer, ≤ 760 phone builder, ≤ 600 sheets); keep others |
| Permissions / gating | 8 | `packages/permissions` is the one place that turns server codes into UX decisions (`canonical.ts`, 120 lines, tested: `tests/builder/permissions.test.ts`) | `adminScope` vs `capabilitiesOf` | align (low) |
| i18n | 4 | `ACTION_LABEL`, `PERMISSION_LABEL_VI` | UI strings are literals in components by design (Vietnamese only); only 25 lines in `packages/i18n`; role wording differs (1.4) | central code→text table (R-010) |

**Overall reuse score: 5.5 / 10.** Strong at the package boundary (auth, client, permissions, icons, states), weak at the screen level (lists, dialogs, forms are rewritten per screen).

## 3. Extensibility: what adding something costs today

"Tests" = what should be added; "Touches unrelated" = files that are not part of the new feature.

| Task | Current steps | Files typically touched | Tests needed | Pain points / unrelated files |
|---|---|---|---|---|
| **New Platform page** (Platform = `AdminApp` with `portal="platform"`) | 1 write the component; 2 add `[key,label,icon]` to `NAV` (`AdminApp.tsx:24`); 3 add the key to `OWNED.platform` (`base.ts:23`); 4 add to `SYSTEM_ONLY` if system-only (`adminModel.ts:45`); 5 add `case` to `route()` (`AdminApp.tsx:93`); 6 add `api.admin.<x>` (`api-client/src/api.ts:81+`) and DTO (`types/src/index.ts`) | `AdminApp.tsx`, `base.ts`, `adminModel.ts`, `api.ts`, `types/index.ts` | pure rules in `tests/builder/adminmodel.test.ts`; a route entry in `scripts/ui-audit.mjs:125` (PLATFORM list); a real-backend flow in `tests/e2e-real` (PL01) | 3 registries to keep in sync with no check (R-009); the page lives in the **Admin** file, so it ships in the Admin portal too (R-007) and conflicts with every other Admin change (R-004) |
| **New Admin resource** (tenant admin) | same as above plus `SCOPED_NAV` (`AdminApp.tsx:37`), `sectionAccess` (`adminModel.ts:50`), `adminScope` fields; a NOT_READY adapter if the backend is missing (`organization.ts` pattern: capability table + `createXApi` + `XLive.tsx` + `XScreens.tsx` + `xModel.ts`) | 6–9 files (see `ORGANIZATION_UI.md` "Files") | `xModel` unit; harness `*-harness.tsx` + `*.spec.mjs` with a fake transport; `build-harness.mjs` entry list **and** its HTML writer (`build-harness.mjs:16-22`); `scripts/test-classify.mjs` HELPERS list | adding a harness page edits two C0-owned scripts (R-029); the harness needs esbuild from `/tmp/esb` (R-028) |
| **New Studio builder panel** | 1 add `{id,label}` to `RAIL` (`builder/LeftRail.tsx:5`); 2 add `case` in `leftPanel` (`BuilderWorkspace.tsx:168-182`); 3 write `panels/XPanel.tsx` taking `DefCtx` (`builder/ctx.ts`); 4 pure logic in `builder/core/x.ts` (relative imports only); 5 if it writes: `ctx.commit(ops, summary)` with `DefinitionOperation` from the contract mirror | `LeftRail.tsx`, `BuilderWorkspace.tsx`, new `panels/`, new `core/` | `tests/builder/x.test.ts` (unit), SSR in `components.test.tsx`, a case in `tests/browser/builder.spec.mjs` (rail tab count, keyboard) | `BuilderWorkspace` is a 33-prop component (`:56-67`) re-created per host (`tests/browser/harness.tsx`); a new capability needs `core/permissions.ts` + `whyNot` text; `RAIL` is a closed tuple so the compiler finds the switch (good) |
| **New datasource type** | **none in the frontend**: the form is generated from the server's `ConnectorDescriptor` (`configKeys`, `credentialKeys`, `notes`; `DataSourcesPanel.tsx:76,180-183`). Only if the type needs a logo or special wording | – | `tests/builder/management-client.test.ts` / `management.test.tsx` fixtures | the descriptor contract (`types/src/contract/v2/management.ts:57`) is the extension point: keep it that way |
| **New action / workflow screen** | new action **type**: contract mirror `types/src/contract/v2/appDefinition.ts:64` `ACTION_TYPES` (needs C0 DECISION), then `ACTION_LABEL` / hints (`builder/core/actions.ts:14-30`, `Record<ActionType,…>` makes the compiler list what is missing), per-type fields in `builder/ActionEditor.tsx`, mutating/permission rules `DATA_MUTATING_ACTION_TYPES` (`packages/permissions/src/canonical.ts:52`), validation in `core/definition.ts:92`. New step kind: `core/workflow.ts` + `WorkflowEditor.tsx` | 5–6 files across 3 packages | `tests/builder/actions.test.ts`, `workflow.test.ts`, `conformance.test.ts` (fixtures from the backend), `permissions.test.ts` | the contract mirror is hand-maintained (`MIRROR … manual`); `as never` casts in the editors (R-025) hide missing fields |
| **New form / dialog** (Admin) | `<Modal label onClose>` + `ModalHeader` + `.modalBody` + footer buttons; local `busy`/`err`; `data-testid` by hand; native `confirm` for destructive steps | the screen file only | harness spec with `axe` (copy `org.spec.mjs:28`), unit for validation | no `Field`, no `useAction`, no `ConfirmDialog`: every dialog re-implements busy / error / duplicate-submit lock (R-011, R-012, R-013) |
| **New shared component** | add to `packages/ui/src`, export in `index.ts`, add CSS to `factory.css`/`responsive.css` (minified single lines) | `packages/ui/src/*`, `styles/*.css` | `tests/builder/uikit.test.tsx` (SSR), a11y check in a harness | **a unit-tested module must not import `@xweb/ui`** (L4): import by relative path or inject; CSS is minified in `globals.css`/`http.css` (R-016); picking among 4 button classes (R-015) |
| **New frontend API adapter** | add the call to `api` (`api.ts`) or a new file in `packages/api-client/src` exported from `index.ts`; for a backend that does not exist: capability table + adapter that throws `…NotReady` and sends nothing (`provisioning.ts`, `organization.ts`); inject through a `*Transport` | `api.ts`, `types/index.ts`, `features/<area>/<x>.ts`, `<x>Adapter.ts` | `tests/builder/<x>.test.ts` against a fake `fetch` (`apiclient.test.ts` pattern), NOT_READY sends nothing | `api.ts` is one object (R-035); the OWNERSHIP note "new clients in `lib/api/<domain>.ts`" is stale (the code is in `packages/api-client`) |

**Where a feature touches unrelated modules today:** (1) any Admin or Platform page touches `AdminApp.tsx` + `base.ts` + `adminModel.ts`; (2) any new harness touches `build-harness.mjs` + `scripts/test-classify.mjs`; (3) any contract change touches `packages/types` (C0 review), the unit conformance fixtures and the builder `core/contract.ts` re-export; (4) any shared component imported by a unit-tested module needs a relative import.

## 4. Debuggability and bug-fix speed

| Area | Today | Recommendation |
|---|---|---|
| Error boundary | none (1.9) | `apps/*/app/error.tsx` + `global-error.tsx` + a boundary in `PortalApp` around `render(seg)` and one around `BuilderWorkspace`; show title, "Thử lại", the request id of the last failed call and a one-click "copy diagnostics" (route, portal, build id, last 5 API results). [SAFE] |
| Logging / telemetry | **no** `console`, no `window.onerror`, no reporting seam (grep: 0 hits in `features packages apps components lib`) | `reportClientError(error, context)` in `packages/ui` that today only keeps the last 20 entries in a module ring buffer (shown by "copy diagnostics") and has a single `sink` hook for later. No network call. [SAFE] |
| requestId | body only (1.9); absent on network / timeout / 5xx without JSON | `call()`: mint `req_<uuid>` per request and send `X-Request-Id` (backend accepts it); on failure set `ApiError.requestId = body.requestId ?? response.headers.get("X-Request-Id") ?? sent id`; make `ErrorState`/`errText` the only formatter. [SAFE: `packages/api-client`, existing CORS allows the header] |
| `data-testid` | 293 ids in 17 files, concentrated in the newest screens (`PublicDataPanels` 53, `OrganizationScreens` 46, `EmployeesScreens` 38, `ProvisioningScreens` 37, `ReleaseModal` 32, `DataSourcesPanel` 31); **0** in `StudioApp`, `ProjectWorkspace`, `drawers`, `CodeWorkspace`, `SitePanels`, `CodePanels`; **1** in `AdminApp.tsx` (1 197 lines). Mixed naming: `ws-pick`, `unit-name`, `release:${id}`, `public-query:${id}`, `n…`. No written convention | convention `area-element[-state]` kebab-case, `:` + id for rows (`row:<id>`), written in `FRONTEND_ONBOARDING.md`; add ids to the file-level actions of the six uncovered files when they are split. [SAFE] |
| Component testability | 14 main-screen files unreachable by any test (1.11) | after the splits (1.3), move calls behind injected `calls` props as `ReleaseModal` does; add one harness page per screen family. [SAFE] |
| Harness quality | good intent labelling (`@class`, HARNESS vs REAL, `test:classify`), owned-process server (`harness-server.mjs`); but: esbuild installed outside the repo (`/tmp/esb`, `build-harness.mjs:4`), 11 specs each re-implement `check()` / `results` / console capture / `chromium.launch` (default `CHROME` is a Linux path `/opt/pw-browsers/chromium-1194/chrome-linux/chrome`, `release.spec.mjs:11`), each falls back to port **4000** when `HARNESS_URL` is unset (9 specs, e.g. `builder.spec.mjs:8`), `test:unit` deletes `.test-build` (`scripts/test-unit.mjs:9`) so the harness bundle disappears | `tests/browser/lib/spec.mjs` (check, results, console/pageerror capture, axe, evidence, `CHROME` resolution, refuse to run without `HARNESS_URL`); `esbuild` as a root devDependency or `scripts/` install step; unit build into `.test-build/unit`. The first is [SAFE]; the others need C0 (package.json, scripts). |
| Browser evidence reproducibility | evidence folders under `docs/parallel/c5/evidence/*` with a README of numbers; `scripts/ui-audit.mjs` reads the stack env from `~/.xweb-e2e-stack/c5e2e-ae/stack.env` and defaults the Studio URL to **3086** (`ui-audit.mjs:18`); screenshots are committed binaries | print the git SHA, viewport list, stack name and `HARNESS` vs `REAL` label at the top of every evidence `README`; add a `--url-*` flag check that refuses a URL on a forbidden port. [SAFE for tests/, NEEDS C0 for scripts/] |
| Fixtures / state isolation | e2e-real creates per-run fixtures through the product API with random passwords and cleans up (good); harness fakes are in-page and reset by reload (good); `?s=<scenario>` query switches (good) | keep; add a `?seed=` for the generated large fixtures so a failing timing run is reproducible. [SAFE] |
| Quick reproduce loop | a UI bug on a main screen needs a backend stack (`e2e-stack.sh up`, Docker + JDK 21 + minutes) | the splits in 1.3 + injected calls turn most of them into a harness page that starts in seconds. |
| Build / runtime config traps | `API_PROXY_TARGET` is baked at build (`next.config` rewrites), `NEXT_PUBLIC_PORTAL_URL_*` too; a wrong value shows as 500 `ECONNREFUSED` on every call | show the proxy target and the three portal origins in the "copy diagnostics" payload (read from `NEXT_PUBLIC_*`; the proxy target cannot be read at runtime: print the build id instead). [SAFE] |

## 5. Issue table

Severity: P0 unusable / security-critical · P1 major · P2 significant · P3 minor. STATUS is OPEN for all. `[SAFE]` = inside C5-owned files, behaviour-preserving, coverable by existing tests + `tsc` + the browser harness; `[NEEDS C0]` = `package.json`, `package-lock.json`, `tsconfig.json`, `next.config.ts`, `scripts/**`, CI, `docs/parallel/**`, `docs/contracts/**`, `docs/ARCHITECTURE.md`; `[NEEDS OWNER]` = a file of another owner or a shared contract mirror.

| ID | PORTAL | AREA | SEVERITY | CATEGORY | EXPECTED | ACTUAL | ROOT_CAUSE (file:line) | OWNER | PROPOSED FIX | TEST | STATUS |
|---|---|---|---|---|---|---|---|---|---|---|---|
| R-001 | ALL | error handling | P1 | RESILIENCE | a thrown render error shows a recoverable page with a request id; the Builder keeps its in-flight edit | no boundary anywhere: the portal unmounts to Next's default error page | no `error.tsx` / `global-error.tsx` / `ErrorBoundary` in `apps/*/app`, `app/`, `packages/auth/src/PortalApp.tsx:15-17` | S4 | `error.tsx` + `global-error.tsx` per app, boundary around `render(seg)` in `PortalApp` and around `BuilderWorkspace`; [SAFE] | unit: boundary renders fallback (SSR) + harness page that throws on click, asserts fallback, focus and "Thử lại" | OPEN |
| R-002 | ALL | requestId | P2 | DEBUGGABILITY | every failed call shows a copyable request id | id read from JSON body only; absent for NETWORK / TIMEOUT / 5xx without body; many screens print `e.message` only | `packages/api-client/src/core.ts:73` ignores header `X-Request-Id` (`RequestIdFilter.kt:21`); `CodePanels.tsx:122` | S4 | mint `req_<uuid>`, send `X-Request-Id` (accepted by `RequestIdFilter.kt:18`, CORS `SecurityConfiguration.kt:76`), fall back to the response header, one formatter; [SAFE] | `tests/builder/apiclient.test.ts`: header sent, id kept on timeout, on 502 without JSON | OPEN |
| R-003 | ALL | telemetry | P2 | DEBUGGABILITY | field failures can be reconstructed | no log, no `window.onerror`, no reporting seam | 0 hits for `console`/`onerror`/`reportError` in source | S4 | `reportClientError` ring buffer + "copy diagnostics" in the error UI; no network; [SAFE] | unit for the ring buffer; harness: error page shows the last API failure | OPEN |
| R-004 | PLATFORM, ADMIN | structure | P2 | MAINTAINABILITY | one screen family per file; a Platform change cannot regress Admin | `AdminApp.tsx` 1 197 lines / 121 KB, 49 function components, 32 `<table>`, no test reaches it | `features/admin/AdminApp.tsx:24-1196` | S1+S2 | split per section 1.3 (pure moves, one commit per file); [SAFE] | `tsc`, `ui-audit.mjs --only platform,admin` before/after (route count and axe equal) | OPEN |
| R-005 | STUDIO | structure | P2 | MAINTAINABILITY | state machines and plans are pure and tested | `ProjectWorkspace` is one 357-line function: save state machine, error mapping, AI streaming, block plan, adapters inline; no test reaches it | `features/studio/ProjectWorkspace.tsx:45-402` (run 120-142, submitPrompt 146-181, addBlock 210-222, adapters 224-247) | S3 | extract per table in 1.3; hot file of C5 [SAFE] | new unit tests for `noticeFor`, `toMessages`, `usageChip`, `blockToOps` | OPEN |
| R-006 | STUDIO | structure | P3 | MAINTAINABILITY | one screen per file | 8 screens in `StudioApp.tsx` (394 lines) | `features/studio/StudioApp.tsx:104-394` | S3 | `studio/screens/*` | `tsc`, `ui-audit.mjs --only studio` | OPEN |
| R-007 | PLATFORM, ADMIN | performance | P2 | PERFORMANCE | each portal ships its own screens; heavy panels load on demand | Platform and Admin bundles are identical (942 KB / 272 KB gzip, 10 chunks); Studio 1 048 KB / 311 KB; 0 `React.lazy` / `next/dynamic`; every screen of a portal loads on first paint | one catch-all route + static imports: `apps/*/app/entry.tsx`, `AdminApp.tsx:74 route()`; L1, L2 | S4 | per-portal section registry + `dynamic()` per section; move `DataSourcesPanel` out of `studio/builder` (R-037); measure with the same `bundle` sum; [SAFE after R-004] | bundle-size note in evidence; `ui-audit` route sweep | OPEN |
| R-008 | PLATFORM, ADMIN | state | P2 | TESTABILITY | console identity is an explicit prop/context | module variables `portal`/`base` assigned during render; helpers return different values by call order; the legacy `"all"` branch stays everywhere | `features/admin/base.ts:12-17`, `AdminApp.tsx:54` | S2 | `AdminPortalContext` + `useAdminPortal()`; `A()` becomes a hook-free function of the context value passed in; drop `"all"` after R-022; [SAFE] | unit: two consoles in one SSR render | OPEN |
| R-009 | PLATFORM, ADMIN, STUDIO | extensibility | P2 | EXTENSIBILITY | adding a section is one table row | 4–5 registries must agree: `NAV` `AdminApp.tsx:24`, `OWNED` `base.ts:23`, `SYSTEM_ONLY` `adminModel.ts:45`, `route()` `AdminApp.tsx:93`, `COMING` :30; Studio: `TITLES` `StudioApp.tsx:26`, nav :62, `route` :48 | scattered registries | S2/S3 | one `SECTIONS: { key,label,icon,portals,access,render,title }` per app; a unit test that every nav key routes and every route has a title; [SAFE] | unit invariants on the table | OPEN |
| R-010 | ALL | errors | P2 | CONSISTENCY | one Vietnamese text per backend error code | 10 independent mappers; `errText` duplicated verbatim in `drawers.tsx:16` | `ui.tsx:21`, `drawers.tsx:16`, `adminModel.ts:155`, `organizationModel.ts:204`, `provisioningModel.ts:79`, `builder/core/errors.ts:30`, `core/dataManagement.ts:51`, `api-client/src/release.ts:109`, `runtimeConfig.ts:120`, `core/testMode.ts:81` | S4 | `packages/i18n` `ERROR_TEXT[code]` as base layer; delete the `drawers.tsx` copy first (trivial); [SAFE] | unit: same code → same base text from every mapper | OPEN |
| R-011 | ALL | duplication | P2 | MAINTAINABILITY | busy/error/notice handled once | 13 hand-written `act()`; 28 load ladders; 11 filter forms; 4 hand-copied `studio-ai-model` / `studio-ws` / `ws-mode` persistence blocks | see 1.4 | S4 | `useAction`, `LoadGate`, `useListQuery`, `usePersistentState` in `packages/ui`; migrate screen by screen; [SAFE] | unit (hook logic via SSR + act-free state machine), harness | OPEN |
| R-012 | ALL | dialogs | P2 | ACCESSIBILITY | one focus-trap, scroll-lock and Escape behaviour | 4 implementations with different rules (scroll lock only in `Modal`; builder `Dialog` has none; Escape propagation differs) | `packages/ui/src/Modal.tsx:11`, `useDialog.ts:11`, `builder/ui/primitives.tsx:28`, `packages/company-ui/src/index.tsx:115` | S4 | `useFocusTrap` hook shared by the three C5 implementations | harness: Escape, Tab loop, focus restore on each | OPEN |
| R-013 | ALL | destructive actions | P2 | UX / TESTABILITY | styled, labelled, keyboard-testable confirmation | 30 native `confirm()` calls (12 in `AdminApp.tsx`) | `AdminApp.tsx:285,290,295,404,405,429,650,690,770,851,934,1082`; `AiSetup.tsx:54,284,347`; `drawers.tsx:95,165,169`; `SitePanels.tsx:49,106,138`; `TenantScreens.tsx:73,124,285`; `StudioApp.tsx:268,319`; `ProjectWorkspace.tsx:196,391`; `ReleaseModal.tsx:140`; `CodePanels.tsx:134` | S1/S2/S3 | `useConfirm()` + `ConfirmDialog` on `Modal` | harness: confirm via keyboard, cancel keeps data | OPEN |
| R-014 | ALL | forms | P3 | CONSISTENCY | one `Field` | `Field` in `drawers.tsx:41` and `builder/ui/primitives.tsx:86` with different props; 48 hand-written `className="field"` | see 1.4 | S4 | single `Field` in `packages/ui`, migrate opportunistically | SSR uikit test (label `for`, `aria-describedby`) | OPEN |
| R-015 | ALL | styling | P3 | CONSISTENCY | one button vocabulary | `.btn` 169, `.button` 67, `.bx-btn` 51, `.smallButton` 66 | `globals.css` (legacy `.button/.smallButton`), `factory.css` (`.btn`), `builder.css` (`.bx-btn`) | S4 | write the mapping in the onboarding doc; migrate `.button`/`.smallButton` to `.btn` as screens are touched | `ui-audit` (no controls < 24 px, axe) | OPEN |
| R-016 | ALL | styling | P3 | MAINTAINABILITY | readable, diffable CSS; tokens in one place | `globals.css` is 1 line (9.7 KB), `factory.css` 47 KB in 398 lines, `http.css` minified; legacy demo-site CSS (`.hero`, `.productGrid`, `.siteNav`) loads in all portals; two token families (`--bg/--panel/--text` dark legacy, `--f-*` light, `--sp-*`) | `packages/ui/src/styles/*.css` | S4 | reformat (no content change) in its own commit; move the demo-site rules to the legacy app; document tokens | `ui-audit` screenshot parity | OPEN |
| R-017 | ALL | responsive | P3 | CONSISTENCY | a short documented set of breakpoints | 13 distinct max/min-width values over 5 CSS files | `factory.css:117,118,193,223,316,317,340,382,385,388`, `builder.css:90,91,113,132,147,153,178,193`, `responsive.css:82,88,98,157`, `http.css:22` | S4 | document 900 / 760 / 600 as canonical, fold near-duplicates (700/720, 767/768) when touched | `ui-dialog-check` (5 viewports) | OPEN |
| R-018 | ADMIN, STUDIO | duplication | P3 | MAINTAINABILITY | one `slugify` | three copies | `SitePanels.tsx:10`, `builder/core/pages.ts:24`, `adminModel.ts:71` | S4 | one in `packages/i18n` | `pages.test.ts`, `adminmodel.test.ts` already cover two | OPEN |
| R-019 | STUDIO | duplication | P3 | MAINTAINABILITY | page management UI uses the tested `core/pages.ts` plans | the Site drawer re-implements page add / nav / 404 editing | `features/studio/SitePanels.tsx:27-100` vs `builder/panels/PagesPanel.tsx`, `core/pages.ts` | S3 | rebuild `PagesSection`/`NavigationSection` on `core/pages.ts`; delete dead `PageBar` (:14) | `pages.test.ts` + harness | OPEN |
| R-020 | ALL | i18n | P3 | CONSISTENCY | one label per role | three maps with different wording ("Quản trị workspace" / "Quản trị không gian làm việc"; "Biên tập" / "Biên tập viên" / "Người chỉnh sửa") | `drawers.tsx:101`, `UserDialogs.tsx:7`, `packages/i18n/src/index.ts:14` | S4 | keep one map in `packages/i18n`, delete the other two | unit snapshot of the map | OPEN |
| R-021 | ALL | dead code | P3 | MAINTAINABILITY | no unreferenced exports | 15 dead exports (+ `ROLE_LABEL` by chain), 38 needless `export`s | table 1.6 | S4 | delete (each verified by `grep -w`); [SAFE] | `tsc`, `test:unit` | OPEN |
| R-022 | LEGACY | stale mock | P3 | MAINTAINABILITY | one product UI | the root app (mock static export + a copy of the router) is still built and imported by tests of nothing | `components/StudioShell.tsx`, `components/app/AppEntry.tsx`, `lib/api-client.ts`, `lib/mock-data.ts` | NOT C5 (C0 decides, `docs/parallel/OWNERSHIP.md` §4/UI audit "Dead / stale UI") | report only; if retired: delete these 4 files, `app/`, root `next.config.ts` legacy branch, `permissions` legacy helpers (L5), `lib/types.ts` mock types | `npm run build` | OPEN |
| R-023 | ALL | layering | P2 | MAINTAINABILITY | one import spelling per dependency; no shims | 9 shim files; 23 relative `../packages/...` imports; 3 spellings of the same module | see L4; cause `scripts/test-unit.mjs:2` + `tests/tsconfig.json` (commonjs, node10) | S4 | (a) delete shims after rewriting their 22 importers to `@xweb/*`; (b) keep relative imports only in `builder/core` + `*Model.ts` and say so in the onboarding doc; (c) longer term, a unit runner that resolves workspaces (esbuild/tsx) [NEEDS C0] | `tsc`, `test:unit`, harness build | OPEN |
| R-024 | STUDIO | extensibility | P3 | EXTENSIBILITY | a panel is registered, not wired by hand | rail id in `LeftRail.tsx:5`, `switch` in `BuilderWorkspace.tsx:168`, a 33-prop bag (`:56-67`) | those lines | S3 | `RAIL` entries carry their `render(ctx)`; props grouped in `host`, `data`, `calls` objects | `builder.spec.mjs`, `components.test.tsx` | OPEN |
| R-025 | STUDIO, ADMIN | type safety | P3 | TYPE SAFETY | no `as never` on contract data | 9 `as never`, 2 `as unknown as`, 90 `!` | `ActionEditor.tsx:51,111,120,…`, `WorkflowEditor.tsx:27,106`, `Inspector.tsx:143,144`, `TenantScreens.tsx:350`, `core/definition.ts:75` | S3 | give `workflowOps.update`/`actionOps.update` a `Partial<ActionDef>` parameter; type the select handlers; replace `data!` with a narrowed `LoadGate` render prop | `tsc` | OPEN |
| R-026 | ALL | tooling | P2 | MAINTAINABILITY | lint and format are enforced | no ESLint/Prettier config or dependency; 12 `eslint-disable` comments refer to a rule that never runs (e.g. `useLoad.ts:10`, `Modal.tsx:29`) | repo root, `package.json` | NOT C5 (C0: package.json) | add `eslint` + `eslint-config-next` + `react-hooks` as devDependencies, run in `typecheck:all`; start with warnings | CI script | OPEN |
| R-027 | ALL | test seams | P2 | TESTABILITY | every main screen can be exercised without a stack | 14 files / ~3 900 lines reachable from no unit or harness test (list in 1.11); `api` singleton imported directly in 14 screens | `AdminApp.tsx`, `TenantScreens.tsx`, `ProjectWorkspace.tsx`, `StudioApp.tsx`, `CodeWorkspace.tsx`, `CodePanels.tsx`, `SitePanels.tsx`, `drawers.tsx`, `libraryPanels.tsx`, `library.tsx`, `PortalApp.tsx`, `AuthPages.tsx`, `session.tsx`, `csp.ts` | S1–S4 | inject `calls` (as `ReleaseModal.tsx:21`) while splitting; `PortalApp` gets a pure `decideRoute(me, path, portal)`; `csp.ts` gets a unit test of the produced policy | unit + harness | OPEN |
| R-028 | TEST | harness | P2 | REPRODUCIBILITY | `build-harness` runs on a clean checkout | needs esbuild installed in `/tmp/esb` (outside the repo, cleaned by the OS) | `tests/browser/build-harness.mjs:4`, `tests/browser/README.md:9-11` | NOT C5 (C0: package.json) | add `esbuild` as a root devDependency, default `ESBUILD_DIR` to `node_modules` | `npm run test:browser:build` | OPEN |
| R-029 | TEST | harness | P2 | MAINTAINABILITY | one spec toolkit | 11 specs re-implement `check`/`results`/console capture/`chromium.launch`; Linux default `CHROME`; 9 specs fall back to `127.0.0.1:4000` when `HARNESS_URL` is unset; a new harness page needs edits in `build-harness.mjs:16-22` and `scripts/test-classify.mjs` HELPERS | e.g. `release.spec.mjs:7-11`, `builder.spec.mjs:8`, `datasources.spec.mjs:6` | S4 | `tests/browser/lib/spec.mjs`; refuse to run without `HARNESS_URL`; entries from a manifest; [SAFE for `tests/`] ; HELPERS list [NEEDS C0] | selftest of the lib; run all 11 specs unchanged | OPEN |
| R-030 | TEST | tooling | P3 | REPRODUCIBILITY | `test:unit` must not remove the harness bundle | `rmSync(.test-build)` deletes `.test-build/browser` | `scripts/test-unit.mjs:9` | NOT C5 (C0: scripts) | unit outDir `.test-build/unit` (`tests/tsconfig.json` `outDir`) | run unit then a spec | OPEN |
| R-031 | DOCS | docs | P2 | DOCUMENTATION | one entry point for the frontend | root `README.md` is a one-line Pages note; `docs/ARCHITECTURE.md` is the V1-era description (no portals, "Flyway V1–V5"); `docs/LOCAL_DEVELOPMENT.md` documents only the `:3100` app and mock mode; runbook §2 still says "serve .test-build/browser on :4000" (contradicts `PROCESS_SAFETY.md`) | `README.md:1`, `docs/ARCHITECTURE.md`, `docs/LOCAL_DEVELOPMENT.md`, `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md:§2` | R | `docs/FRONTEND_ONBOARDING.md` (this delivery); links from the others [NEEDS C0 for README / ARCHITECTURE / LOCAL_DEVELOPMENT]; runbook line reworded [SAFE: C5 doc] | reading check by a new person | OPEN |
| R-032 | ALL | docs | P3 | DOCUMENTATION | JSDoc matches the code | `capabilitiesOf` doc says tenant.administer = platform.operate "FOR NOW" and a TENANT_ADMIN "would get 403 on every screen"; the code opens `admin.console` for tenant admins | `packages/permissions/src/index.ts:28-39` vs `:41-56` | S4 | rewrite the comment | – | OPEN |
| R-033 | ADMIN | permissions | P3 | MAINTAINABILITY | one derivation of "who administers what" | `adminScope` and `capabilitiesOf` both derive platform / tenant-admin / MEMBER_MANAGE from `Me` | `adminModel.ts:19-31` vs `packages/permissions/src/index.ts:41-56` | S2 | build `adminScope` on `capabilitiesOf` | `permissions.test.ts`, `adminmodel.test.ts` | OPEN |
| R-034 | STUDIO | renderer | P3 | ROBUSTNESS | pure render function | `renderSchemaDocument` mutates module-level `ctx`/`up`/`site` | `lib/schema-preview.ts:24,31,32,108-110` (shared with `workers/render`, C2) | S3 [NEEDS OWNER: C2 consumes it] | pass a render context object | the 43-check `publicdata.spec.mjs` part 3 + `page-runtime.test.ts` | OPEN |
| R-035 | ALL | api-client | P3 | MAINTAINABILITY | per-domain modules | one `api` object (`api.ts:43-356`) mixing auth, admin, code, runtime, management; OWNERSHIP text says "new clients in `lib/api/<domain>.ts`" (stale) | `packages/api-client/src/api.ts` | S4 | new domains in new files, composed into `api`; update OWNERSHIP wording [NEEDS C0] | `apiclient.test.ts` | OPEN |
| R-036 | ALL | state | P3 | PERFORMANCE | cancel superseded requests | `useLoad` ignores stale results but does not abort; no cache; fixed 15 s timeout for every call | `packages/ui/src/useLoad.ts:6-14`, `core.ts:52` | S4 | pass an `AbortSignal` to the loader; optional `key` cache | unit (SSR + manual loader) | OPEN |
| R-037 | ADMIN, PLATFORM | boundaries | P2 | ARCHITECTURE | Admin does not import Studio's builder | `TenantScreens.tsx` imports `DataSourcesPanel` and passes a fake `doc` `as never` | `features/admin/TenantScreens.tsx:20-21,350` | S2 + S3 | move `DataSourcesPanel` + `core/dataManagement.ts` to `features/data/`; make `doc` optional | `datasources.spec.mjs` (47 checks) | OPEN |
| R-038 | ALL | boundaries | P3 | ARCHITECTURE | `components/` is either live or legacy | live helpers (`SectionInspector.tsx`, `useDialog.ts`) sit beside the legacy shell | `components/*` | S3 | move the two helpers to `features/studio/` / `packages/ui`; the SCC disappears | `tsc` | OPEN |
| R-039 | TYPES | contract mirror | P3 | ARCHITECTURE | no import cycle in `packages/types` | `types/index.ts` ⇄ `contract/v2/appDefinition.ts` (type-only) | `packages/types/src/contract/v2/appDefinition.ts:10` | NOT C5 (C0: contract mirror) | move `PageSchema`/`Seo` below `contract/v2/meta.ts` | `tsc` | OPEN |
| R-040 | PACKAGES | scope | P3 | MAINTAINABILITY | clear purpose of each package | `packages/company-ui` (168 lines) and `packages/app-sdk` (67 lines) have no consumer in the repo and duplicate `Dialog`/`Modal`/`Tabs`/`Table` | `packages/company-ui/src/index.tsx`, `scripts/publish-company-packages.sh` | NOT C5 (C0 decides) | keep, document as "published for generated apps"; do not copy from them | – | OPEN |
| R-041 | SCRIPTS | process safety | P2 | SAFETY | stop only what was started, validated | `portals.sh down` sends `kill -TERM` to the pid in `<name>.launcher` without identity validation (already listed in `PROCESS_SAFETY.md` "Reported, NOT mine") | `scripts/portals.sh:66-67` | NOT C5 (C0) | use `tests/lib/owned-process-cli.mjs`, or C0's `scripts/lib/owned-process.mjs` (exists on `integration/v2` only, D-C0-48) | pattern of `tests/lib/owned-process.test.mjs` | OPEN |
| R-042 | ALL | test ids | P3 | DEBUGGABILITY | stable, documented, uniform ids | 293 ids, 0 in six main files, 1 in `AdminApp.tsx`; mixed naming | see section 4 | S1–S4 | convention in the onboarding doc; add ids when splitting | `test:classify` unaffected | OPEN |
| R-043 | STUDIO | robustness | P3 | RESILIENCE | no crash when the list is empty | `me!.workspaces[0].id` in the Studio shell; only the router gate prevents it | `features/studio/StudioApp.tsx:32`, `:83` | S3 | guard + `StateView` | unit (SSR with `workspaces: []`) | OPEN |
| R-044 | ALL | i18n | P3 | EXTENSIBILITY | (information) translation is possible | all UI text is a literal in components (Vietnamese by product rule); `packages/i18n` is 25 lines | `packages/i18n/src/index.ts` | NOT C5 (product decision) | none now; R-010 creates the table that a future translation would extend | – | OPEN |

Counts: **P0 0 · P1 1 · P2 19 · P3 24 (44 issues)**. By first-listed owner: S4 19 · S3 8 · S1 4 · S2 4 · R 1 · NOT C5 8 (R-022, R-026, R-028, R-030, R-039, R-040, R-041, R-044).

## 6. What is safe to implement now vs what needs approval

| Proposal | Class |
|---|---|
| R-001 error boundaries, R-002 request id, R-003 diagnostics buffer | SAFE (C5 files `apps/*/app`, `packages/auth|api-client|ui`); unit + harness tests included in the task |
| R-004, R-005, R-006 file splits, R-009 registry, R-021 dead code, R-018/R-020 de-duplication, R-011 hooks, R-013 `ConfirmDialog`, R-012 `useFocusTrap`, R-014 `Field`, R-019, R-025, R-033, R-037, R-038, R-043 | SAFE (C5-owned `features/**`, `components/**`, `packages/ui|permissions|i18n`); one commit per move, `tsc` + `test:unit` + harness + `ui-audit` sweep after each |
| R-007 code splitting, R-008 context for the console identity | SAFE but after R-004 (order matters); verify with the bundle sum used here |
| R-029 `tests/browser/lib/spec.mjs`, R-027 new unit tests | SAFE (`tests/**`) |
| R-031 runbook wording, onboarding doc | SAFE (C5 docs); README / ARCHITECTURE / LOCAL_DEVELOPMENT links need C0 |
| R-023(c) unit runner that resolves workspaces, R-026 ESLint/Prettier, R-028 esbuild devDependency, R-030 unit outDir, R-029 HELPERS list in `scripts/test-classify.mjs` | NEEDS C0 (`package.json`, `package-lock.json`, `scripts/**`, `tsconfig.json`) |
| R-034 renderer context | NEEDS OWNER (shared with `workers/render`, C2) |
| R-035 OWNERSHIP wording, R-039, R-022, R-040, R-041, R-044 | NEEDS C0 / not C5 |

## 7. Scores (justified)

| Dimension | Score | Evidence |
|---|---|---|
| Architecture | 6.5 | 0 runtime cycles over 145 files; strict acyclic package direction; pure `builder/core`; NOT_READY adapter pattern; but L1–L4 (Platform = Admin file, admin→studio import, shims + relative imports, legacy root app) and no error boundary |
| Reuse | 5.5 | section 2: strong at package level (auth 8, client 8, permissions 8), weak at screen level (lists 3, forms 3, dialogs 4) |
| Extensibility | 5 | datasource type = 0 frontend files (descriptor driven) and capability-table adapters are good; Admin / Platform page = 5 files in 3 registries, builder panel = 4 files, new harness = 2 C0 scripts |
| Maintainability | 5 | 1 197-line `AdminApp.tsx`, 357-line `ProjectWorkspace`, 13 copies of `act()`, no lint, 14 main-screen files untested; offset by 306 unit tests, `test:classify`, strict TS with 0 `any` |
| Debuggability | 4 | no boundary, no telemetry, requestId from body only, 30 native confirms, esbuild outside the repo, but strong evidence discipline (HARNESS vs REAL, owned-process, `@class` guard) |
| Documentation | 5 existing / 7 with `FRONTEND_ONBOARDING.md` | excellent per-feature notes (`ORGANIZATION_UI.md`, `PROCESS_SAFETY.md`, `UI_UX_AUDIT.md`) but no entry point; README and `ARCHITECTURE.md` stale |

## 8. Not verified

- Any behaviour in a browser (no Chromium run, no real backend, no stack): every runtime claim here is from source, `tsc`, unit tests and production builds only.
- The squad role mapping S1–S4 (assumed from branch names).
- That the section splits in 1.3 are free of circular imports: they follow the existing function boundaries and shared helpers (`PageHead`, `LinkBox`, `errText`), but only a build after the move proves it.
- Bundle numbers are sums of `.next/static/**/*.js` of a Turbopack production build with `HBL_ENV=local`; Next prints no per-route sizes for this version, so first-load JS per route was not measured.
- `docs/parallel/c5/e2e-stack.sh up` on a real backend, `scripts/portals.sh up`, `PORTALS=1 ./scripts/run-local.sh` (not run).
