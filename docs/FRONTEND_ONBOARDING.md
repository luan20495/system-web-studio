# Frontend onboarding (XWeb / System Web Studio)

One entry point for a NEW person or AI working on the web UI. Read it top to bottom once (15 minutes), then use the "How to" sections. Written 2026-10-08 against `agent/c5-web @ 9f858c2`; every command and path was checked against `package.json`, the scripts and the source. Marks used below:

- **[RAN]** the command was executed while writing this document and worked;
- **[READ]** checked by reading the file, not executed (needs a port, Docker, a backend or a browser session that was not available);
- **[NOT VERIFIED]** could not be checked.

Companion documents: `docs/parallel/c5/audit/R-architecture.md` (review, issue list R-001…), `docs/parallel/OWNERSHIP.md` (who owns which file: read it before editing), `docs/adr/0022-frontend-monorepo-three-deployments.md` (why three apps), `docs/parallel/c5/PROCESS_SAFETY.md` (process rules), `tests/browser/README.md` (browser harness), `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md` (real-backend E2E), `docs/parallel/c5/UI_UX_AUDIT.md` (design and accessibility rules in force).

## 1. The three portals

The frontend is three Next.js apps in one npm-workspaces monorepo, one backend (the Kotlin modular monolith, never split into services). Each portal has its own origin, session cookie, CSP and `/api` proxy. An app imports packages, never another app.

| Portal | Package / folder | Dev & start port | Base path | What it is for | Who can enter (UI gate: `packages/permissions/src/index.ts` `capabilitiesOf`) |
|---|---|---|---|---|---|
| **Platform** | `@xweb/app-platform`, `apps/platform` | 3001 (`-H 127.0.0.1`) | `/platform` | operate the platform: tenants (companies), all users, AI providers and usage, components, templates, builds, packages, system health, backups, costs, alerts, security, settings, audit, connectors | `platform.operate`: the server says `platformScope` (SYSTEM_ADMIN); an older backend falls back to `systemAdmin` |
| **Admin** | `@xweb/app-admin`, `apps/admin` | 3002 | `/admin` | run one company: company profile, organization structure, employees, people (create accounts), workspaces, data sources, plus the system sections a SYSTEM_ADMIN also sees | `admin.console`: a platform admin, a tenant admin (`TENANT_MEMBERS` or `tenants[].role = TENANT_ADMIN`), someone with `MEMBER_MANAGE` or `DATA_SOURCE_MANAGE` in a workspace |
| **Studio** | `@xweb/app-studio`, `apps/studio` | 3003 | `/studio` | build, run and publish apps: projects, AI chat, the Builder (design mode), code mode, templates, components, versions, assets, site settings, publish | `studio.build`: the server lists `APP_VIEW` for some workspace of the person (a platform-only SYSTEM_ADMIN is not offered Studio) |

Facts that surprise people:
- **Platform and Admin are rendered by the same screen module**: `apps/platform/app/entry.tsx` and `apps/admin/app/entry.tsx` both mount `features/admin/AdminApp.tsx`, with `portal="platform"` / `portal="admin"`. Which sections each portal shows is decided by `OWNED` in `features/admin/base.ts` and by `navFor` in `AdminApp.tsx`.
- **Three origins = three logins** (cookies are per origin). `PortalSwitcher` (`packages/ui`) only shows links to portals the person may open (`accessiblePortals`).
- Routing is one client-side catch-all per app: `apps/<app>/app/[[...slug]]/page.tsx` → `entry.tsx` → `PortalApp` (`packages/auth/src/PortalApp.tsx`). Pages: `/login` and `/<portal>/login` (login), `/auth/activate`, `/auth/signing-in`, `/auth/no-access`, `/auth/no-workspace`, `/auth/session-expired`, then everything under the portal prefix. `/` redirects through `resolvePortalPostLogin`.
- `apps/*/proxy.ts` (Next 16 "proxy", ex-middleware) sets a per-request CSP with a nonce (`packages/auth/src/server/csp.ts`); `layout.tsx` calls `await connection()` so the nonce can be stamped.
- **Frontend gating is UX only. The backend is the authority** (tenant, workspace, project, permission checked on every call). A hidden button is not security; a missing UI check is never a reason to skip a server check.
- There is also a **legacy root app** (`app/`, `components/StudioShell.tsx`, `lib/api-client.ts`, `lib/mock-data.ts`, port 3100, mock static export for GitHub Pages). It is not part of V2, still compiles, and shares `features/*` with the portals. Do not build new things on it (review item R-022).

## 2. Local run

Prerequisites: Node 22+ (`docs/LOCAL_DEVELOPMENT.md`), `npm ci` at the repo root [RAN]. JDK 21 and Docker only for the backend (not needed for typecheck, unit tests, builds or the browser harness).

### 2.1 Frontend only (no backend needed)

| Task | Command | Status |
|---|---|---|
| install | `npm ci` | [RAN] |
| typecheck, root (legacy app + features + components + tests) | `npx tsc --noEmit` (same as `npm run typecheck`) | [RAN] clean |
| typecheck the 3 apps + 6 packages | `npm run typecheck:packages` (alias `npm run typecheck:all`); apps only: `npm run typecheck:apps` | [RAN] clean |
| unit tests | `npm run test:unit` | [RAN] 306 tests, 305 pass, 1 skipped, about 24 s |
| test file classification | `npm run test:classify` | [RAN] "OK: every test file is classified" |
| production build of one portal | `HBL_ENV=local npm run build:platform` (also `build:admin`, `build:studio`; all three: `HBL_ENV=local npm run build:apps`) | [RAN] platform, admin, studio |
| legacy root app (mock static export to `out/`) | `npm run build` | [RAN] |
| browser harness bundle | `npm run test:browser:build` (= `node tests/browser/build-harness.mjs`; needs esbuild in `/tmp/esb`, see 6.3) | [RAN] |
| runner self-test (class `mock`, no backend, random ports) | `npm run test:e2e:real:selftest` | [RAN] 25/25 |

**A production build needs `API_PROXY_TARGET` or `HBL_ENV=local`**, otherwise it stops with "API_PROXY_TARGET is required for a production build / start" [RAN]. The proxy target is baked into the build (the `/api`, `/oauth2`, `/login/oauth2` rewrites are evaluated by `next build`): setting it only at `next start` has no effect. `NEXT_PUBLIC_PORTAL_URL_PLATFORM|ADMIN|STUDIO` (links between portals) are baked at build too; unset = same origin.

### 2.2 With a backend

| Task | Command | Status |
|---|---|---|
| one portal with hot reload, API at `http://127.0.0.1:8080` (the dev default when `API_PROXY_TARGET` is unset) | `npm run dev:platform` / `dev:admin` / `dev:studio` (ports 3001 / 3002 / 3003) | [READ] |
| point a portal at another API | `API_PROXY_TARGET=http://127.0.0.1:<api port> npm run dev:studio` | [READ] |
| whole local stack + the three portals | `PORTALS=1 ./scripts/run-local.sh` (starts Docker infra, render worker, API with the V1 feature flags, then `scripts/portals.sh up`); stop: `./scripts/stop-local.sh` | [READ] needs `.env` (`cp .env.example .env`, set `LOCAL_ADMIN_PASSWORD`, 14+ characters) |
| portals only (API already running) | `./scripts/portals.sh up` / `status` / `down` (production builds, fails on a busy port, builds only when sources or ports changed) | [READ] |
| legacy stack (root app on 3100) | `./scripts/run-local.sh`, `./scripts/smoke-test.sh`, `./scripts/stop-local.sh` | [READ] |
| isolated real-backend stack for E2E (own containers, own ports) | `docs/parallel/c5/e2e-stack.sh up` · `status` · `down`; the Studio port defaults to **3003**: set `E2E_STUDIO_PORT` when something else uses it | [READ] |

Local accounts (profile `local`): `local.admin` (SYSTEM_ADMIN and workspace admin), `local.editor`, `local.publisher`, `local.viewer`; the password is `LOCAL_ADMIN_PASSWORD` from your `.env` (`docs/LOCAL_DEVELOPMENT.md`). Never commit or print it.

**Never run two repos' servers on one port**, and never start a server on a port that a stack already uses: `3001/3002/3003` are normally taken by a live stack on a developer machine. Use a free port (the harness and the owned-process helper pick one for you) and read section 12.

## 3. Folder structure

```
apps/{platform,admin,studio}/        thin Next apps: app/{layout,entry,[[...slug]]/page}.tsx, proxy.ts (CSP), next.config.ts (createNextConfig)
packages/
  types/        MIRROR of the backend contract (src/index.ts DTOs; src/contract/v2/* AppDefinition, permissions, runtime, management). A change = a contract change (C0 reviews)
  api-client/   src/core.ts (call/stream, CSRF, idempotency, ApiError, 401 hook) · api.ts (the `api` object) · release.ts · runtimeConfig.ts
  auth/         PortalApp (router + gates), session.tsx (SessionProvider/useSession), AuthPages.tsx, server/{csp,nextConfig}.ts
  permissions/  canonical.ts (server permission codes -> UX decisions) · index.ts (portal gates, portalHref/portalPath, safeNext, resolvePortalPostLogin)
  ui/           shared components, hooks, icons, styles/*.css (the design system)
  i18n/         Vietnamese label maps (action names, roles, portal text)
  company-ui/, app-sdk/   published to the internal registry for GENERATED apps; NOT workspaces, NOT used by the portals
features/
  admin/        AdminApp.tsx (Platform + Admin consoles) · *Model.ts pure rules · *Screens.tsx presentational · *Live.tsx / *Adapter.ts wiring · organization.ts / provisioning.ts capability tables
  studio/       StudioApp.tsx (shell + project list, templates…) · ProjectWorkspace.tsx (one project: AI / Design / Code) · ReleaseModal.tsx · drawers.tsx · builder/ (the Builder)
  studio/builder/   BuilderWorkspace.tsx · LeftRail.tsx · Inspector.tsx · panels/ · ui/primitives.tsx · core/ (PURE logic, relative imports only) · ctx.ts (DefCtx)
  auth/, ui.tsx, session.tsx, routing.ts, useLoad.ts, library.tsx   2-line re-exports ("moved to packages/…") kept for old imports, except library.tsx (real: template/block thumbnails)
components/  legacy shell (StudioShell, AppEntry) + SectionInspector.tsx / useDialog.ts (live helpers of Studio)
lib/         schema-preview.ts + preview-document.ts (page renderer, shared with workers/render), http-api.ts / http-types.ts (re-exports), api-client.ts + mock-data.ts (legacy mock)
app/         legacy root app entry
workers/render/   C2's render worker (preview + page runtime), not UI
tests/
  builder/      unit + SSR tests (class `unit`): `*.test.ts(x)`, fixtures.ts, a11y.ts
  browser/      Chromium specs + in-page harnesses (class `harness`) · harness-server.mjs · build-harness.mjs
  e2e-real/     real-backend suite (class `real-backend`): run.mjs, flows/e2e-*.mjs, lib/
  lib/          owned-process.mjs (+ CLI, + tests): the ONLY way tests start or stop a process
scripts/        shell and node tools (C0-owned): run-local, portals, test-unit, test-classify, ui-audit, ui-dialog-check…
docs/parallel/c5/   C5 notes, handoffs, evidence/, e2e-stack.sh · docs/parallel/c5/audit/ (reviews and tools)
```

## 4. Architecture

```
 Browser ── apps/<portal> (Next, one client router) ── same-origin /api /oauth2 /login/oauth2 ──(rewrite at build)──► Spring Boot API ──► Postgres · Redis · MinIO · RabbitMQ

   screen (features/*Screens.tsx)  ── props: calls / adapter / plan ──►  *Model.ts / builder/core/*.ts   pure rules, validation, error → text, plans of operations
        │                                                                          │
        ▼                                                                          ▼
   *Live.tsx / *Adapter.ts   ── capability table (READY | NOT_READY) ──►  packages/api-client  api.<domain>.<call>()  ──►  core.call(): CSRF, Idempotency-Key, 15 s timeout, ApiError, 401 → /auth/session-expired
        ▲
   packages/ui (StateView, Modal, Pager, Pill, icons …) · packages/permissions (what to SHOW) · packages/types (shapes)
```

Data flow of a write (Studio/Builder): control → `ctx.commit(ops, summary)` (the one write funnel, `builder/ctx.ts`) → `applyOps` in `ProjectWorkspace.tsx` → `api.patchSchema(ws, project, expectedRevision, ops, summary)` → backend validates (`PageSchemaValidator`, `SchemaPatchEngine`) and appends an immutable version. `REVISION_CONFLICT` (409) reloads the latest revision. The UI never edits a version and never sends code or SQL: only typed declarative operations.

Rules:
1. UI → adapter/model → `api-client`. A screen does not build URLs or read `fetch`; it calls `api.*` (or the injected `calls`).
2. Pure logic (validation, plans, labels) lives in `*Model.ts` / `builder/core/` and has a unit test; React files only render and wire.
3. Anything the backend does not offer yet is **NOT_READY** (section 7). Never invent a route, a field or demo data.
4. Display only what the server said: permission codes from `/auth/me` and `ApiProject.permissions`, never a role name (`role === "VIEWER"` is forbidden; `packages/permissions/src/canonical.ts` is the one place that maps codes to decisions).
5. Backward compatibility: Page Schema, `STATIC_APP`, existing API shapes and old features stay (see the root `CLAUDE.md`).

## 5. `packages/ui` (what to reuse before writing your own)

| Export (from `@xweb/ui`) | Use |
|---|---|
| `StateView({kind, title?, detail?, action?, level?})`, `ErrorState({error, retry})`, `stateOf(e)` | loading / empty / forbidden / notfound / error / network / conflict / expired / ai-unavailable / publish-failed. `level={1}` when the state is the whole page (a page has exactly one `h1`) |
| `Card({title, actions})`, `Kpi`, `Pill({value,label})`, `Pager`, `NavLink`, `ComingSoon` | page furniture. `Card` scrolls sideways inside itself and becomes a keyboard stop only while it overflows (`ScrollRegion`) |
| `Modal({label,onClose})` + `ModalHeader({icon,title,subtitle})` | the dialog: focus trap, Escape, scroll lock, focus restore, bottom sheet on phones |
| `useDialog(title, onClose)` | the same behaviour for drawers (Studio) |
| `Picker`, `Switch` | accessible listbox with logos; `role="switch"` |
| `useLoad(loader, deps)` | fetch on mount → `{data, error, loading, reload, setData}`; stale responses ignored |
| `useNavDrawer`, `MenuButton` | the ≤ 900 px navigation drawer |
| `useOverflow`, `ScrollRegion` | "focusable only while it scrolls" |
| `PortalSwitcher` | links to the other portals the person may open |
| icons (`import { Plus } from "@xweb/ui"`) | **Lucide only**, imported by name from `packages/ui/src/icons.ts`; add an icon there. Brand logos: `ProviderLogo` (Simple Icons, `docs/parallel/c5/ICON_LICENSES.md`). Never a text glyph (□ risk) and never an icon from the network |
| `errText(e, fallback)`, `fmtDate`, `ago`, `num`, `usd`, `tok`, `actionLabel` | formatting; `errText` appends "(mã req_…)" when the error has a request id |

Builder-only building blocks: `features/studio/builder/ui/primitives.tsx` (`IconButton` requires `label`, `StateBox`/`Gate` for NOT_READY, `Dialog`, `Tabs` + `tabPanelProps`, `Field`) with `bx-*` classes in `builder.css`.

## 6. Auth, permissions, session

- **Session**: `SessionProvider` (`packages/auth/src/session.tsx`) loads `GET /auth/me` once. `useSession()` → `{ me, loading, disabled, refresh, logout, setMe }`. Any 401 outside `/auth/*` calls `onUnauthorized` → `/auth/session-expired?next=…`; `ACCOUNT_DISABLED` → `/auth/no-access?reason=disabled`.
- **CSRF**: `core.call()` fetches `/auth/csrf` once and sends `X-XSRF-TOKEN` on every unsafe method; a `403 CSRF_INVALID` is retried once. **Idempotency**: pass `idempotencyKey` (publish and runtime calls use `newIdempotencyKey()`).
- **Portal gate**: `PortalApp` (`PortalRouter`) redirects to login with `?next=`, checks `canAccessPortal(me, portal)`, then renders the portal. `safeNext` accepts only same-app paths (no open redirect).
- **Section gate (Admin)**: `adminScope(me)` and `sectionAccess` in `features/admin/adminModel.ts` decide which sections are offered; the page renders `NeedsPlatform` / `NeedsScope` otherwise.
- **Permission codes (Studio)**: `resolvePermissions(project.permissions)` → `canEditProject`, `canPublish`, `canShare`, `canViewDataSources`, … and `whyNot(...)` for the text on a disabled control. A project that opens with `APP_VIEW` but not `APP_EDIT` is read-only, not a redirect. The contract is `docs/parallel/c5/PERMISSION_CONTRACT.md`.
- **Disabled with a reason, not hidden**, when the person could reasonably wonder why; the reason names the missing permission (`missingReason`).
- The server decides every call (403 = same scope, no permission; 404 = out of scope). Show the server's answer; do not pre-empt it with a guess.

## 7. API adapter pattern and NOT_READY

**Where a call goes.** Add the call to `packages/api-client/src/api.ts` (`api.<group>.<name>`) with its DTO in `packages/types/src/index.ts` (or a new file in `packages/api-client/src` exported from `index.ts`). Paths are relative to `/api/v1`; use `qs()`, `json()`, `encodeURIComponent` for ids. Validate obvious input before sending and throw an `ApiError` with the server's shape (as `badKey()` does).

**Adapter in a feature.** A feature talks to an interface, not to `api`:

```ts
// features/admin/organization.ts (contract + capability table + adapter factory, pure, unit-tested)
export const CAPABILITIES: Readonly<Record<OrgCapabilityId, OrgCapabilityState>> = { listOrganizationUnits: NR(NO_CONTRACT, ["TENANT_MEMBERS"]), /* … */ };
export function createOrganizationApi(transport: OrganizationTransport, …): OrganizationApi   // throws OrganizationNotReady and sends NOTHING for a NOT_READY capability
// features/admin/organizationAdapter.ts (wiring)
export const liveOrganization = createOrganizationApi({ tenantMembers: (t) => api.admin.tenantMembers(t) }, employeesFromMembers);
```

**NOT_READY** (the rule of the whole UI): when the backend has no route, the screen says "Chưa sẵn sàng" with a plain reason, the action is disabled, no request is sent and no data is faked. When the backend ships it, the only edits are: set the capability to `READY` with `route` and `needs` (the permission the SERVER lists), add the transport call, rerun the harness spec and the real-backend flow. Worked examples: `features/admin/organization.ts` (all NOT_READY, one live fallback), `features/admin/provisioning.ts` (all READY; the mechanism stays), `docs/parallel/c5/ORGANIZATION_UI.md`, `docs/parallel/c5/PROVISIONING_UI.md`. In the Builder the same idea is `Readiness` (`AVAILABLE | LOADING | ERROR | NOT_READY`, `features/studio/builder/core/readiness.ts`) rendered by `StateBox` / `Gate`, with one probe (`GET /api/v1/component-metadata`, `core/backend.ts`). A 404/501 on a promised endpoint is NOT_READY; a 404 **with** a domain code (`QUERY_NOT_FOUND`) is a missing id.

Errors: show the server's `code` in words, never the English message; keep the request id visible. `errText(e, fallback)` is the default; richer mappers per feature: `adminErrorText`, `orgProblem`, `provisioningProblem`, `explainError` (builder), `explainReleaseError`.

## 8. Responsive rules and design tokens

- **Desktop first, usable down to 360 px.** No page may scroll sideways at 390 px (checked by `scripts/ui-audit.mjs`).
- Canonical breakpoints in the CSS (`packages/ui/src/styles/*.css`): **≤ 900 px** sidebar becomes a drawer (`useNavDrawer`, `.shell` rules in `factory.css`); **≤ 760 px** the Builder shows ONE workspace at a time through a sticky three-way switch **Bản xem trước / Công cụ / Thuộc tính** (`data-mview` on `.bx-body`, state `mview` in `BuilderWorkspace.tsx`, all panes stay mounted so nothing reloads; `builder.css`); **≤ 720 px** the employee table becomes cards; **≤ 600 px** dialogs become bottom sheets; **≤ 1100 px** the Builder uses two columns. Other widths in the CSS (520, 700, 767, 768, 1023, 1279, 1760, 1920) are older and local: do not add new ones.
- A phone is a "look and adjust" editor, not a full Builder.
- Wide tables: put them in a `Card` (it scrolls inside itself); ids that must break go in `code`; names wrap (`break-word`).
- **Tokens** (`factory.css` `:root`): colours `--f-bg --f-panel --f-line --f-line2 --f-text --f-muted --f-accent --f-accent-ink --f-accent-soft --f-ok/-bg --f-warn/-bg --f-bad/-bg --f-info/-bg`, radius `--f-r` (10 px), `--f-shadow`, focus ring `--f-focus`, `--f-placeholder`, `--f-disabled-bg/-ink`, spacing `--sp-1…--sp-8` (4 8 12 16 20 24 32 px), `--muted-strong` for text on dark rails. The dark editor still uses the older `--bg --panel --text --muted` family in `globals.css`.
- Type: `Inter, ui-sans-serif, system-ui, …` (no webfont), body 14 px / 1.5, helper 13 px, labels 13 px / 600. Icons 14 / 16 / 18 / 20 / 22 px.
- Buttons: use `.btn` (`primary`, `ghost`, `sm`, `danger`) in portals; `.bx-btn` inside the Builder. `.button` and `.smallButton` are older (new code does not use them).
- CSS is minified on single lines in places (`globals.css`, `http.css`): edit with care and keep each change small.

## 9. Accessibility rules

Target: **axe (wcag2a, wcag2aa, wcag21a/aa, wcag22aa, best-practice): 0 critical and 0 serious**, with **no rule suppression** (no `disableRules` exists in the repo; do not add one). Measured by `scripts/ui-audit.mjs` (every route, real stack) and by axe inside `tests/browser/org*.spec.mjs`.

- Every icon-only control has an accessible name (`IconButton` requires `label`; `aria-label` otherwise). The accessible name must contain the visible text (axe `label-content-name-mismatch`).
- Every input has a label; errors are tied with `aria-describedby` / `aria-invalid` and rendered with `role="alert"`.
- One `h1` per page (`StateView level={1}`; page titles via `PageHead`). `document.title` is `"<Mục> · <Cổng>"` per route.
- Dialogs: `Modal` / `useDialog` / builder `Dialog` trap Tab, close on Escape and restore focus to the opener. A dialog taller than the screen scrolls its body and keeps the footer reachable.
- Targets ≥ 24 px; colour contrast from the tokens only (`--f-muted` on `--f-panel` passes; do not invent greys); never colour alone (a `Pill` has text).
- Keyboard: trees use roving tabindex (`organization` tree), tabs use arrow keys (`builder/ui/primitives.tsx` `Tabs`), drag and drop has a keyboard path and button alternatives ("Lên" / "Xuống", "Thêm").
- Scrollable regions are focusable only while they scroll (`ScrollRegion`); do not add `tabIndex` by hand.
- Respect `prefers-reduced-motion` (the existing rules disable transitions).
- All user-visible text is Vietnamese with full diacritics; users never see a raw enum, a team name or a flag name (map with `actionLabel`, a role-label map, `Pill label`).

## 10. Tests: what exists, what each one proves

Every test file starts with `// @class: unit | mock | harness | real-backend`; `npm run test:classify` fails on an untagged file or a file that claims more than it proves. **Results of different classes are reported separately and a harness or mock result is never called an E2E.**

| Class | Command | Needs | Proves |
|---|---|---|---|
| unit | `npm run test:unit` [RAN] | nothing | pure logic and server-rendered markup (`tests/builder/*.test.ts(x)`), the API client against a fake `fetch`, the owned-process helper |
| harness | `node tests/browser/build-harness.mjs`, then `CHROME=… node tests/browser/harness-server.mjs run -- node tests/browser/<spec>.spec.mjs` | Chromium/Chrome, esbuild | what the REAL component does in a real browser with an in-page FAKE behind it (drag and drop, focus, ARIA, states). **Labelled HARNESS in reports. Not backend evidence.** |
| real-backend | `E2E_STUDIO_URL=… E2E_ADMIN_USER=… E2E_ADMIN_PASSWORD=… npm run test:e2e:real` | a running stack | real browser → real app → real backend, no interception. **Labelled REAL.** Exit 0 passed, 1 failed, 2 NOT RUN (never a pass). Env template `tests/e2e-real/.env.example`; runbook `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md`; matrix `docs/C5_REAL_BACKEND_E2E_MATRIX.md` |
| developer audits (not tests) | `node scripts/ui-audit.mjs --out <dir>` · `scripts/ui-dialog-check.mjs` · `scripts/ui-studio-shots.mjs` | a real stack + Chrome | overflow, tofu glyphs, axe, console errors per route and viewport (1440 / 1024 / 768 / 390). Defaults point at ports 3001 / 3002 / 3086: set `AUDIT_STUDIO`, `AUDIT_PLATFORM`, `AUDIT_ADMIN` |

### 10.1 Run one browser spec safely [RAN with `sanity.spec.mjs`: 8/8]

```bash
node tests/browser/build-harness.mjs          # -> .test-build/browser (needs esbuild at ESBUILD_DIR, default /tmp/esb)
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  node tests/browser/harness-server.mjs run -- node tests/browser/builder.spec.mjs
```

`harness-server.mjs run -- <cmd>` starts ONE static server on a free port, records its identity under `.run/owned/`, runs your command with `HARNESS_URL` / `DS_HARNESS_URL` set, and always stops exactly that process afterwards. A busy `--port` exits 3 and names the holder; nothing is stopped by name or by port. Specs: `builder`, `datasources`, `org`, `org-hardening`, `provisioning`, `release`, `publicdata`, `sanity`, `aiproviders` (all through the harness server), `portals` (three built apps, see `tests/browser/README.md`), `page-runtime` (C2's).
Evidence files in `docs/parallel/c5/evidence/` must say HARNESS or REAL, the git SHA and the viewport.

### 10.2 Things that bite

- `npm run test:unit` **deletes `.test-build`** (`scripts/test-unit.mjs`): rebuild the harness afterwards (`node tests/browser/build-harness.mjs`; the static server reads files per request, no restart).
- **A unit-tested module must import at runtime by relative path** (`../../../packages/ui/src/icons`, not `@xweb/ui`): tests are compiled by `tsc` to CommonJS and run by plain `node --test`, and `@xweb/*` resolve to `.ts` sources that node cannot load. Type-only imports may use `@xweb/*`. This is why `builder/core/*` and `*Model.ts` use relative imports.
- A new harness page needs: an entry in `tests/browser/build-harness.mjs` (entry list and its `<name>.html` writer), the file listed as a helper in `scripts/test-classify.mjs` (C0-owned), and a `// @class: harness` header.
- Specs fall back to `http://127.0.0.1:4000/…` when `HARNESS_URL` is unset: always go through `harness-server.mjs run`.
- esbuild is not a repo dependency: `npm i --prefix /tmp/esb esbuild` (or set `ESBUILD_DIR`). `/tmp/esb` was present on this machine [RAN]; on a clean machine it must be installed first.
- Specs default `CHROME` to a Linux path; on macOS pass `CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"`.

## 11. How to …

### 11.1 Add a page

*Admin / Platform page* (both consoles share `features/admin/AdminApp.tsx`):
1. Write the screen (new file under `features/admin/`; keep it presentational if it will be harness-tested). Skeleton:
   ```tsx
   function XPage() {
     const { data, error, loading, reload } = useLoad(() => api.admin.x(), []);
     return (<>
       <PageHead title="Tên trang" sub="Một câu nói trang này làm gì."/>
       <Card>{error ? <ErrorState error={error} retry={reload}/> : loading && !data ? <StateView kind="loading"/> : data!.length === 0 ? <StateView kind="empty" title="Chưa có …"/> : /* table */}</Card>
     </>);
   }
   ```
2. Register it in **four places** (they must agree): `NAV` (`AdminApp.tsx:24`, `[key, label, icon]`; or `SCOPED_NAV` :37 for tenant/workspace scopes), `OWNED` (`features/admin/base.ts:23`, which console owns the key), `SYSTEM_ONLY` (`adminModel.ts:45`) or `sectionAccess` (`adminModel.ts:50`) for its gate, and a `case` in `route()` (`AdminApp.tsx:74`).
3. Add the call to `api.admin` (`packages/api-client/src/api.ts`) and the DTO to `packages/types/src/index.ts`; if the backend route is missing, use the NOT_READY pattern (section 7) and a `ComingSection` entry (`COMING`, :30).
4. Add `data-testid`s for what a test must reach, and the route to `scripts/ui-audit.mjs` (the `PLATFORM`, `ADMIN_TENANT`, `ADMIN_SYSTEM` and `STUDIO` route lists, lines 125–128; C0-owned script: ask C0 or note it in the handoff).
5. Tests: rules in `tests/builder/adminmodel.test.ts` (or a new `*.test.ts`); a harness page if the screen has states worth proving; a real-backend flow only when the backend exists.

*Studio page*: `StudioApp.tsx` — add to `TITLES` (:26), the `nav` array in `StudioSidebar` (:62) and the `switch` in `route()` (:48); a project sub-view goes through `ProjectWorkspace.tsx` (`MODES` / `PANELS`).

### 11.2 Add a dialog

Copy the structure of `features/admin/TenantScreens.tsx` (the "Tạo công ty" dialog, lines ~183–210):
```tsx
<Modal label="Tạo công ty" onClose={onClose}>
  <form className="modalBody" noValidate onSubmit={(e) => void submit(e)} data-testid="x-create">
    <ModalHeader icon={<Building2 size={22}/>} title="Tạo công ty" subtitle="Một câu: dialog này làm gì."/>
    <section className="xp-section" aria-label="…"><label className="field"><span>Tên</span><input data-testid="x-name" aria-invalid={…} aria-describedby={…}/></label>{/* error: <p className="formError" role="alert" id=…> */}</section>
    {error ? <p className="formError" role="alert">{error}</p> : null}
    <div className="xp-footer"><button type="button" className="btn" onClick={onClose}>Hủy</button><button className="btn primary" disabled={busy}>{busy ? "Đang tạo…" : "Tạo"}</button></div>
  </form>
</Modal>
```
Rules: lock the submit while `busy` (no double submit); validate with a pure function from a `*Model.ts` (unit-tested); show the server's refusal by code; for destructive steps prefer a `Modal` confirmation over the native `confirm()` that older screens still use; a drawer in Studio uses `useDialog`; inside the Builder use `Dialog` from `builder/ui/primitives.tsx`.

### 11.3 Add a shared component

1. Put it in `packages/ui/src/<Name>.tsx` (`"use client"` if it uses hooks), export it from `packages/ui/src/index.ts`, styles in `packages/ui/src/styles/factory.css` (`xp-` prefix) using the tokens, never a hard-coded colour.
2. Accessible by construction (name, role, keyboard, focus); required props for what must not be forgotten (`IconButton`'s `label`).
3. Test: SSR markup in `tests/builder/uikit.test.tsx` (import the component by relative path), behaviour in a harness page.
4. Do not put feature rules in it; do not import from `features/*`.

### 11.4 Add a frontend API adapter

1. DTOs → `packages/types/src/index.ts` (a contract mirror change goes through C0: `docs/parallel/DECISIONS.md`).
2. Call → `api.<group>.<name>` in `packages/api-client/src/api.ts` (or a new file there). Idempotent writes take an `idempotencyKey`.
3. Interface + capability table + `createXApi(transport)` in `features/<area>/<x>.ts`, wiring in `<x>Adapter.ts` (pattern: `organization.ts` + `organizationAdapter.ts`).
4. Unit test against a fake `fetch` (`tests/builder/apiclient.test.ts` is the model): the exact URL, method and body; a NOT_READY capability sends nothing.
5. A new backend migration is never yours: C5 does not create migrations.

### 11.5 Add a Builder panel

1. `features/studio/builder/LeftRail.tsx`: add `{ id, label }` to `RAIL` (a closed tuple, the compiler then points at the `switch`).
2. `features/studio/builder/BuilderWorkspace.tsx`: add the `case` in `leftPanel` (~line 168) rendering `panels/XPanel.tsx`.
3. `panels/XPanel.tsx` receives `ctx: DefCtx` (`builder/ctx.ts`: `doc`, `commit`, `readiness`, `canEdit`, `busy`, permissions with reasons, `dataManagement?`). Write only through `ctx.commit(ops, summary)`; wrap backend-dependent parts in `<Gate state={ctx.readiness}>`.
4. Put the rules (what operations to send, validation, labels) in `builder/core/x.ts` with relative imports; operations come from the contract mirror (`builder/core/contract.ts`, `DefinitionOperation`).
5. Permissions: add a flag in `builder/core/permissions.ts` (`capabilitiesFor`) and its `whyNot` text only if the code is canonical (`packages/permissions/src/canonical.ts`).
6. Tests: `tests/builder/x.test.ts` for `core/x.ts`; a case in `tests/browser/builder.spec.mjs` (tab count, keyboard); `RAIL` length checks if any.

## 12. Ownership, branches, handoffs

- **Always read `docs/parallel/OWNERSHIP.md` before editing.** C5 owns `features/**`, `components/**`, `app/**`, `apps/**`, `lib/schema-preview*`, `lib/preview-document.ts`, `e2e/**`, `tests/**` and the C5 packages. **C0 owns** `package.json`, `package-lock.json`, `tsconfig.json`, `next.config.ts`, `compose*.yml`, `scripts/**`, CI, `docs/parallel/**` (except the C5 folder), `docs/contracts/**`, `docs/adr/**`, `CLAUDE.md`. Other owners' files (backend modules, `workers/render`) are read-only for C5; ask through `docs/parallel/BLOCKERS.md` or the Depends column of `BOARD.md`.
- Only C0 issues Flyway migration numbers. C5 never creates migrations. Do not change `docs/contracts/**` without a `DECISIONS.md` entry. Never merge main, never push without permission, no force-push, no GitHub Actions.
- **C5 squad** (roles inferred from branch names `agent/c5-*`; confirm with C5-L): **C5-L** lead and integrator (`agent/c5-web`), **S1 / S2 / S3** audit and fix per portal (Platform / Admin / Studio), **S4** shared packages, tooling and performance (`agent/c5-s4-perf`, `agent/c5-s4-tooling*`), **R** research and architecture review (`agent/c5-r-arch`, documents only), **R2** security review (`agent/c5-r2-sec`). One task, one branch `agent/c5-<role>-<topic>`, one commit set; the lead merges.
- **Handoff to C5-L** (final message of a task; fields as C5-L requires, plus the repo rule "files changed / tests / blockers / commit SHA" from `CLAUDE.md`): `OWNER` · `TASK` · `BRANCH` · `BASE SHA` · `HEAD SHA` · `FILES` (changed, with owner of each) · `TESTS` (command, class, count, result; HARNESS vs REAL stated) · `EVIDENCE` (path under `docs/parallel/c5/evidence/` or `audit/`) · `ISSUES` (id, severity P0–P3, status) · `BLOCKERS` / `NOT VERIFIED`. Any further field the lead names in a brief: **[NOT VERIFIED]** here, the lead's brief wins. Commit messages end with the attribution line given in the task.
- **Handoff to another owner** (something C5 cannot change): a row `H-<owner>-<nn>` in `docs/parallel/c5/HANDOFF_INDEX.md` (ID · Owner · Severity · Blocks), details in `HANDOFF_<owner>.md` or `HANDOFFS_*.md`. State expected vs actual, evidence, and what is blocked.
- Severity scale: P0 unusable / security-critical, P1 major, P2 significant, P3 minor.

## 13. Common mistakes

| Mistake | What happens | Do instead |
|---|---|---|
| importing `@xweb/ui` (or any `@xweb/*` value) in a unit-tested module | `test:unit` fails to load `.ts` from `node_modules` | relative import (`../../packages/ui/src/icons`); `import type` from `@xweb/*` is fine |
| running `test:unit` then a browser spec | `.test-build` is gone, the spec cannot load | `node tests/browser/build-harness.mjs` again |
| `next build` without `API_PROXY_TARGET` (or `HBL_ENV=local`) | "API_PROXY_TARGET is required for a production build / start" | set one of them **at build time** |
| setting `API_PROXY_TARGET` only at `next start` | every `/api` call answers 500 `ECONNREFUSED :8080` | rebuild with it |
| two repos / worktrees' servers on the same port | one serves the other's build, tests pass against the wrong code | one port per stack; free port via `harness-server.mjs`, own ports via `E2E_*_PORT` |
| starting `portals.sh up` while another stack holds 3001–3003 | the script refuses and names the holder | choose `PORTAL_*_PORT`, never kill the holder |
| `role === "VIEWER"` or any role name in a gate | wrong after a contract change | permission codes through `packages/permissions` |
| a text glyph (✓ ✕ ▦) as an icon | tofu on some fonts, axe/visual audit fails | Lucide icon from `@xweb/ui` |
| a native `confirm()` for a new destructive step | untestable, unstyled, English buttons on some browsers | `Modal` confirmation |
| adding a page to `NAV` but not `OWNED` / `route()` | page shows "Mục này nằm ở trang khác" or not found | all four places (11.1) |
| faking data for a missing backend | looks finished, is false | NOT_READY with a reason |
| suppressing an axe rule to get green | hides a real defect | fix the markup; target 0 critical / 0 serious |
| editing `features/studio/ProjectWorkspace.tsx`, `lib/schema-preview.ts`, `lib/http-api*.ts` while another owner needs it | hot-file conflict | they are C5 hot files: coordinate with C5-L |
| treating a harness pass as backend evidence | wrong release decision | say HARNESS; only `tests/e2e-real` is REAL |

## 14. Process safety (mandatory)

Other people's processes run on the same machine (their `next start`, `http.server`, gradle, a live stack). Cleaning up by name, by port or by a bare pid file kills them.
- **NEVER** `pkill`, `killall`, `kill $(lsof -ti :PORT)`, `xargs kill`, `kill $(cat x.pid)`, `kill_port`. A guard in `tests/lib/owned-process.test.mjs` fails the unit run when C5-owned tooling contains one.
- Start long-lived things only through `tests/lib/owned-process.mjs` (library) or its CLI `node tests/lib/owned-process-cli.mjs start|stop|status|signal|port-free …`, or through `node tests/browser/harness-server.mjs run -- <command>`. The helper starts the command detached in its own process group, records pid + start time + command in `.run/owned/*.json` (or `$E2E_STACK_DIR/run/`), re-validates all three before every signal, and refuses (exit 4) when the pid was reused. A busy port **fails** (exit 3) naming the holder; it never stops it.
- Portal example (from `tests/browser/README.md`): `node tests/lib/owned-process-cli.mjs start --state .run/owned/portal-platform.json --cwd apps/platform --port 3001 -- npx next start -H 127.0.0.1 -p 3001` … `node tests/lib/owned-process-cli.mjs stop --state .run/owned/portal-platform.json`. Use another free port when 3001–3003 are taken.
- Static analysis (tsc, node scripts reading sources, esbuild bundling, the `R-*.mjs` tools in `docs/parallel/c5/audit/tools/`) needs no port: prefer it.
- Never touch the other stacks on the machine (their containers, tunnels and databases). Stop only what **you** started, by the recorded identity.
- Known gaps outside C5: `scripts/portals.sh down` sends `kill -TERM` to a pid file without identity validation (listed in `PROCESS_SAFETY.md`, owner C0).

## 15. Where to look when something breaks

| Symptom | Look at |
|---|---|
| a whole portal is a blank error page | no error boundary exists yet (R-001): open the browser console; the failing component is in the stack |
| an API error | `ApiError.code` + `requestId` (shown as "(mã req_…)" by `errText`; the audit page filters by Request ID); the backend sets `X-Request-Id` on every response |
| 500 `ECONNREFUSED` on every `/api` call | portal built with the wrong `API_PROXY_TARGET` (section 2.1) |
| login works but the portal says no access | `capabilitiesOf(me)` in `packages/permissions/src/index.ts`; the person's `/auth/me` (permissions, tenants, workspaces) |
| a Builder control is disabled | `whyNot(...)` text, `ctx.readiness`, `core/permissions.ts` |
| a screen says "Chưa sẵn sàng" | its capability table (`organization.ts`, `provisioning.ts`) or `Readiness` (`builder/core/readiness.ts`, probe `core/backend.ts`) |
| CORS 403 on login | the API's `CORS_ALLOWED_ORIGINS` must list the three portal origins (`docs/parallel/c5/HANDOFFS_PORTALS.md` H-C0-11) |
| the preview or a published page differs | `lib/schema-preview.ts` (shared with `workers/render`, C2) |
| known structural problems and their fixes | `docs/parallel/c5/audit/R-architecture.md` |
