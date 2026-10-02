# System Web Studio — technical walkthrough / handover

> **Update 2026-10-02 — AI Software Factory (phases 1–3).** The product is now one frontend with two areas. Sections below written
> before this update still describe the backend correctly; where they mention the old single-screen UI, this block wins.
> * **Routing:** `app/[[...slug]]/page.tsx` + `components/app/AppEntry.tsx` (client router). Routes: `/login`, `/auth/{signing-in,no-access,no-workspace,session-expired}`,
>   `/admin`, `/admin/{users,users/{id},workspaces,workspaces/{id},applications,applications/{id},ai,components,audit,system,settings}`,
>   `/studio`, `/studio/{projects,new,templates,components,activity}`, `/studio/projects/{id}/{ai|design|code|members|versions|assets|publish|settings}`.
>   Refresh keeps the route; an expired session returns to the same route after sign-in. Static export (mock / Pages) still builds.
> * **Login gateway:** portal cards (Admin Console / Builder Studio) are navigation intent only; `resolvePostLogin()` in `features/routing.ts`.
>   Admin needs live `system_admin` (`AdminGuard`, every `/api/v1/admin/**` call). Public sign-up is off unless `PUBLIC_SIGNUP_ENABLED=true`.
> * **Admin Console** (`features/admin/AdminApp.tsx`, backend `admin/`): overview, users (enable/disable/revoke sessions), workspaces,
>   application inventory (+ transfer ownership, delete, restore), AI control (counts, models, limits; tokens/cost NOT IMPLEMENTED),
>   components (registry + usage), org-wide audit with filters, live platform health, read-only settings.
> * **Builder Studio** (`features/studio/`): home with a big prompt (creates a Website and sends the prompt), paged projects (mine/shared),
>   create (Website active; other types coming soon), templates (default only), components (registry + usage), activity.
>   Project editor modes: **AI** (chat with real metadata: model, version, changed components), **Design** (structure with drag/drop
>   reorder, component library, click-to-select in the preview via postMessage, registry-driven inspector incl. images), **Code** (honest
>   "not available": schema-driven, no source repository — ADR 0005).
> * **New/changed APIs:** see API_CONTRACT.md "Added for the AI Software Factory". DB migrations V8 (admin indexes), V9 (asset image props).
>   Sessions moved to the indexed Redis repository (needed for revocation; existing sessions were invalidated once).
> * **Phase 4 — AI usage accounting:** `ai_calls` (V10) records every upstream model call with OpenRouter-reported tokens/cost
>   (`AiUsageService`, `AdminAiUsageController`). `PromptController.run` now calls the model outside the DB transaction (short tx before,
>   commit tx after). Optional token budgets `AI_DAILY_TOKEN_LIMIT_PER_USER` / `AI_MONTHLY_TOKEN_LIMIT_PER_WORKSPACE`. See docs/AI.md.
> * **Phase 5 — templates & blocks:** `template/Templates.kt`, `component/ComponentPackages.kt`, `admin/AdminGovernanceController.kt`
>   (migration V11). Studio: Templates, New app (template picker), Components (blocks), editor library + “Lưu thành khối”, Settings
>   “Lưu trang thành mẫu”. Admin: Components → “Khối đóng góp” review queue, Templates. ADR 0006.
> * **Phase 6 — multiple AI providers:** `integration/llm/ChatProviders.kt`, `ExternalLLMProvider` (was `OpenRouterLLMProvider`),
>   `AiService` policies/catalog, `AiUsageService.price`, `admin/AdminAiProvidersController.kt` (migration V12). ADR 0007.
> * **Decisions:** docs/adr/0001–0007. Status matrix: IMPLEMENTATION_STATUS.md.

Source of truth: the code on branch `feat/production-hardening` and the stack that was running on 2026-10-01 (API :8080, UI :3100, Docker services). Where README or docs disagree with the code, the code is used. Labels: **REAL** = runs against real components, **MOCK** = simulated, **PARTIAL**, **NOT IMPLEMENTED**. Nothing in this task changed code; the one write to the dev database was a sample project named "Walkthrough sample".

---
## 1. What the system is

**System Web Studio** is a studio for building a marketing/product web page by talking to it. A user opens a project, types "thêm bảng so sánh sản phẩm", the backend turns that sentence into structured edits of a JSON page, validates them, stores them as a new version, and the browser shows the result in a preview. A "publish" button runs the page through a (mock) deployment pipeline.

| Concept | What it actually is in the code |
| --- | --- |
| **User** | A person with an account (`users`). Roles are per workspace and per project, not global (except an optional `system_admin` flag). Intended users: marketers/site owners (editors), release people (publishers), reviewers (viewers), workspace admins. |
| **Workspace** | A tenant. Everything belongs to one workspace (`workspaces`, `workspace_members`). |
| **Project** | One website being built (`projects`): name, framework, visibility, domain, deployment target and a `revision` counter. Its content is the page schema. |
| **Page Schema** | A JSON document `{page, sections:[{id,type,componentVersion,props}]}` in `page_schemas.schema` (current) and `project_versions.schema_snapshot` (history). It *is* the website; there is no source code generation. |
| **Component Registry** | Tables `components` / `component_versions`: 10 allowed section types with a JSON-schema-like description of their props. The validator rejects any schema that uses a type or prop outside it. |
| **Prompt** | A sentence the user types (`prompts`). The LLM port converts it to a list of schema operations (`prompt_runs`). Only a **mock** LLM exists. |
| **Preview** | The browser renders the schema JSON to an HTML string (`lib/schema-preview.ts`) and shows it in a sandboxed `<iframe srcDoc>`. The backend never renders HTML. |
| **Version** | An immutable snapshot of the schema (`project_versions`) created by every successful prompt, direct edit or restore. This is what "History" lists. |
| **Publish vs Deploy** | *Publish* is the user action + API (`POST …/publish`): it creates a `deployments` row for the current version and enqueues a job. *Deploy* is the last pipeline step performed by a `DeployProvider` (here `MockDeployProvider`, which only returns a fake URL). |
| **Asset** | An uploaded file (png/jpeg/webp/gif/pdf) stored in MinIO with metadata in `assets`. Assets are listed/uploaded/deleted but **not referenced by any schema component yet**. |
| **Audit log** | `audit_events`: append-only rows (DB trigger blocks update/delete/truncate) for logins, project/prompt/version/asset/publish/member changes. |
| **PostgreSQL** | Source of truth for everything above. |
| **Redis** | HTTP sessions (Spring Session) and rate-limit counters (`rl:*`). Nothing business-critical. |
| **MinIO** | S3-compatible object store for asset bytes (bucket `studio-assets`). |
| **RabbitMQ** | Durable queue `studio.publish` (+ `studio.publish.dlq`) that carries deployment ids to the publish worker. |

```
 User
  │ browser
  ▼
 Web (Next.js UI, :3100)  ── same-origin /api, /oauth2, /login/oauth2 rewrites ──┐
  │ (static JS in the browser)                                                    │
  ▼                                                                               ▼
 API (Spring Boot / Kotlin, :8080)  ◄────────────────────────────────────────────┘
  │
  ├─ Backend modules: identity · access(RBAC) · project · schema · component · prompt ·
  │                   version · asset · publish(+worker) · member · audit · maintenance · common
  │
  ├──► PostgreSQL :15432  business data, versions, audit, deployments
  ├──► Redis      :16379  sessions + rate limits
  ├──► MinIO      :19000  asset bytes (browser uploads directly with presigned URLs)
  └──► RabbitMQ   :15674  publish jobs  ──► worker (same JVM) ──► MockDeployProvider
  ▼
 Mock AI (MockLLMProvider)  /  Mock Deploy (MockDeployProvider)
```
Arrows: browser→UI = HTML/JS; UI→API = JSON over cookies (the Next server proxies, the browser never talks to :8080); API→PostgreSQL = JPA + JdbcTemplate; API→Redis = session read on every request + counters; API→MinIO = signs URLs and verifies objects, the *browser* PUTs the bytes; API→RabbitMQ = publishes the deployment id after commit; RabbitMQ→worker = `@RabbitListener` in the same JVM, which writes status back to PostgreSQL; the UI polls that status.

---
## 2. Repository structure

```
/
├── app/                 Next.js App Router: layout.tsx (fonts/css, CSP nonce hook), page.tsx (picks UI by mode), *.css
├── components/          StudioShell.tsx (mock-mode Studio), HttpStudio.tsx (http-mode: login→projects→studio+drawers), useDialog.ts
├── lib/                 http-api.ts (all real API calls), http-types.ts, schema-preview.ts (schema→HTML),
│                        api-client.ts + mock-data.ts + types.ts + preview-document.ts (mock mode only)
├── proxy.ts             Next "proxy" (middleware): per-request CSP with nonce, http mode only
├── next.config.ts       mode switch: static export (mock) vs server + /api,/oauth2 rewrites (http); security headers
├── index.html, assets/  ★ the page GitHub Pages actually serves: a separate hand-written vanilla-JS demo (see §25)
├── docs/                documentation (+ docs/index.html copy of the old demo)
├── backend/             Kotlin/Spring Boot 4 API (Gradle). src/main/kotlin/com/systemwebstudio/<module>/…, db/migration V1–V6, tests
├── e2e/                 Playwright-core scripts: full-flow, sso-flow, a11y, pages-mock
├── scripts/             run-local, stop-local, smoke-test, check, load-test, backup/restore/PITR/MinIO drills, secret-scan, sso-up/down
├── infra/               nginx (prod reverse-proxy example), postgres-pitr, keycloak (realm template), load (k6)
├── compose.yml          Postgres, Redis, MinIO, RabbitMQ (+ optional Keycloak profile `sso`)
├── proxy.ts, package.json, tsconfig.json, .env.example, .gitleaks.toml
```
Backend modules (`backend/src/main/kotlin/com/systemwebstudio/`): `identity` (login, session, Spring Security, OIDC), `access` (PermissionMatrix, AccessService), `project`, `schema` (operations, patch engine, validator, default page), `component` (registry), `prompt`, `version` (commit/restore/schema API), `asset`, `publish` (controller, repository, processor, worker, recovery sweeper), `member`, `audit`, `maintenance` (cleanup), `common` (request id, errors, rate limiter, filters, prod validator), `integration/{llm,deploy,git,queue,secrets,storage}` (ports + adapters).

The *entry point* is `SystemWebStudioApplication.kt` (backend) and `app/page.tsx` (frontend). Backend runs from startup; frontend code runs in the browser. Backend depends on the 4 services; frontend depends only on the API.

---
## 3. Frontend screens

Three separate frontends exist (see §5/§25). The real product UI is `components/HttpStudio.tsx` (http mode). `StudioShell.tsx` is the Next mock-mode UI. The root `index.html` is the GitHub Pages demo.

| Screen (HTTP mode) | Source | API called | Local state | Backend state | Roles | Real/Mock |
| --- | --- | --- | --- | --- | --- | --- |
| Boot / session check | `HttpStudio()` | `GET /auth/me` | spinner | session identity | any | REAL |
| **Login** (password + "Đăng nhập bằng SSO" if enabled) | `LoginScreen` | `GET /auth/config`, `GET /auth/csrf`, `POST /auth/login`, `GET /auth/me`; SSO = browser navigation to `/oauth2/authorization/oidc` | form fields, error | session | anonymous | REAL (SSO REAL against Keycloak only) |
| "No workspace" notice | `HttpStudio()` | – | – | `me.workspaces` empty | any | REAL |
| **Project list + create** | `ProjectList` | `GET …/projects`, `POST …/projects` | new-name input | project list | any member; create needs workspace ADMIN/EDITOR role | REAL |
| **Studio workspace** (chat left, preview right, device toggle, mobile tabs) | `StudioView` | `GET project, schema, versions, prompts` (parallel), `POST prompts` | prompt text, device, pane, busy, notice | schema, revision, versions, chat history | read for view; EDIT to prompt | REAL |
| **History drawer** | `StudioView` + `Drawer` | `GET versions` (loaded), `POST versions/{id}/restore` | – | versions | restore needs EDIT | REAL |
| **Edit content drawer** (hero title/description, products add/edit/remove) | `ContentDrawer` | `PATCH …/schema` | drafts | schema | EDIT | REAL |
| **Settings drawer** (name, description, auth mode, domains, deployment mode/target) | `SettingsDrawer` | `PATCH …/projects/{p}` | draft | project | SETTINGS (EDITOR, OWNER, admins) | REAL |
| **Assets drawer** | `AssetsDrawer` | `GET assets`, `POST assets/upload-url`, **PUT to MinIO**, `POST assets/complete`, `DELETE assets/{id}` | uploading flag | assets | list = read; upload/delete = EDIT | REAL |
| **Members drawer** (project members; workspace members only for workspace admins) | `MembersDrawer` | `GET/POST/PATCH/DELETE …/members` (project and workspace) | add form | members | PROJECT_MEMBERS (OWNER, ws admin) / MEMBER_MANAGE | REAL |
| **Publish modal** (visibility choice → live status list) | `PublishModal` | `POST …/publish` (Idempotency-Key), poll `GET …/deployments/{id}` every 0.8 s | key, deployment, error | deployment | PUBLISH (PUBLISHER, OWNER, admins) | REAL pipeline, MOCK provider |
| Toast notices | `StudioView` | – | message | – | – | – |

Not present in HTTP mode: **Undo/Redo and the "Saved" pill** (NOT IMPLEMENTED; "History/Restore" is the server-side substitute), project delete UI (API exists), project rename outside Settings, deep links/routing (the selected project lives only in React state), pagination, audit viewer UI (API only).

Mock-mode Next UI (`StudioShell`): Studio, Settings drawer, History drawer (read-only), Publish modal. All data is in memory (no localStorage, no API). The GitHub Pages demo (`assets/studio.js`) additionally has a project switcher, Undo/Redo, saved indicator, product editor and localStorage.

---
## 4. The main Studio workspace (HTTP mode)

```
┌ top bar: [←] project name · r<revision> · user │ Lịch sử │ Chỉnh sửa │ Tệp │ Thành viên* │ Xuất bản │ ⚙ │ Đăng xuất ┐
├ left: conversation + composer ────────────┬ right: preview toolbar (Máy tính/Bảng/Điện thoại) + sandboxed iframe ────┤
```
(*only when `project.permissions` contains PROJECT_MEMBERS; buttons are disabled/hidden by the permissions the API returned — the server enforces anyway.)

* **On open** (`StudioView.reload`): in parallel `GET /projects/{p}` (project + `permissions[]`), `GET …/schema` (`{schema, revision, version}`), `GET …/versions`, `GET …/prompts`.
* **Project** comes from PostgreSQL `projects`; **schema** from `page_schemas.schema` (created on first read if missing, `SchemaService.ensureInitialized`).
* **Preview** = `renderSchemaDocument(schema)` (React `useMemo`) → HTML string → `<iframe sandbox="" srcDoc>`; no scripts, no same-origin, only `#anchors` as links, all text HTML-escaped.
* **Chat history** = `GET …/prompts` (table `prompts` + `prompt_runs`), shown oldest-first; new turns are appended from the `POST prompts` response.
* **Saved indicator**: none in HTTP mode (changes are saved by each request). The top bar shows `r<revision>` instead.
* **Undo/Redo**: NOT IMPLEMENTED in the Next app (only the legacy GitHub Pages demo has it, in the browser). **History** is server-side: every change is an immutable version; restoring makes a *new* version.
* **Product editing**: Edit drawer → computes `UPDATE_PROP` (per item `itemId`), `REMOVE_ITEM`, `ADD_ITEM` operations → `PATCH …/schema` with `expectedRevision`; the response replaces the schema and revision.
* **Settings save**: `PATCH /projects/{p}` with `expectedRevision`; changes the project row (JPA `@Version` bumps `revision`), no schema version is created.
* **Publish**: modal → `POST …/publish` → polls status → shows ordered events; on close the project is reloaded.
* **Conflicts**: a `409 REVISION_CONFLICT` reloads server state and tells the user to retry; `401` returns to the login screen.

```
Studio open ─► GET project ─┐
            ─► GET schema  ─┼─► React state ─► renderSchemaDocument ─► <iframe srcDoc>
            ─► GET versions ─┤                    ▲
            ─► GET prompts ──┘                    │ new schema + revision
 prompt / edit / restore ─► POST|PATCH (expectedRevision) ─► API ─► PostgreSQL (version, audit) ┘
```

---
## 5. Mock mode vs HTTP mode

`NEXT_PUBLIC_API_MODE` (default `mock`) is read **at build time** by `lib/api-client.ts`, `app/page.tsx`, `next.config.ts`, `proxy.ts`.

| Feature | Mock mode (Next) | HTTP mode (Next) | Legacy Pages demo (`index.html`) |
| --- | --- | --- | --- |
| Login | none | real password/SSO session | none |
| Project | one in-memory project | real projects, multi-user | several projects in localStorage |
| Prompt | in-page keyword function (`applyMockPrompt`) | `POST …/prompts` → MockLLM → validation → DB | its own JS keyword function |
| Preview | `renderPreviewDocument` (flat content) | `renderSchemaDocument` (page schema) | own renderer |
| Version/History | in-memory list, not restorable | real, restorable | localStorage snapshots + Undo/Redo |
| Assets | none | MinIO via presigned URLs | none |
| Publish | demo notice, no URL | real queue/state machine, **mock** URL | demo notice |
| Persistence | lost on reload | PostgreSQL | browser localStorage |
| Backend called | never | always (via same-origin proxy) | never |

HTTP mode config: `NEXT_PUBLIC_API_MODE=http` (build), `API_PROXY_TARGET` (default `http://127.0.0.1:8080`, used by `next.config.ts` rewrites), `MINIO_PUBLIC_ENDPOINT` (read at runtime by `proxy.ts` to allow presigned URLs in the CSP). The browser only ever calls its own origin. After a browser refresh: the cookie is still valid → `GET /auth/me` → the **project list** appears (the open project is not remembered; no URL routing).

---
## 6. Authentication flow

```
Browser ─GET /api/v1/auth/csrf──────────► sets XSRF-TOKEN cookie, returns {token}
Browser ─POST /api/v1/auth/login  (X-XSRF-TOKEN) ► CsrfFilter ► RateLimiter.peek (user, IP)
        ► PasswordHashGate (≤4 concurrent) ► AuthenticationManager ► Argon2 password check
        ► new session id ► SecurityContext saved in Redis (Spring Session) ► Set-Cookie STUDIO_SESSION
Browser ─subsequent calls (cookie + X-XSRF-TOKEN on unsafe methods)► ActiveUserFilter (user still enabled?) ► controller
```
* Login: `POST /api/v1/auth/login` body `{"username","password"}` → `200 {id, username, displayName, roles, workspaces:[{id,name,role}]}`; `401 INVALID_CREDENTIALS`; `429 RATE_LIMITED` (+`Retry-After`); `503 LOGIN_BUSY`; `404 LOCAL_LOGIN_DISABLED` when password login is off.
* Cookie `STUDIO_SESSION` (value = session id, no Base64): `HttpOnly`, `SameSite=lax`, `Secure` unless `COOKIE_SECURE=false` (local runs). CSRF cookie `XSRF-TOKEN` (readable by JS by design, `SameSite=Lax`).
* Redis: `spring:session:sessions:<id>` (1,030 keys at inspection, from load tests); session timeout **20 min** idle; sessions survive API restarts and Redis restarts (AOF on).
* `GET /auth/me`: current identity; `POST /auth/logout`: invalidates the session and expires the cookie.
* Disabled user: `ActiveUserFilter` loads the user on every request; if `enabled=false` → session invalidated, `401 ACCOUNT_DISABLED`. Verified in E2E.
* Brute force: **REAL** — 5 failures/15 min per username and 50/15 min per client IP (Redis `rl:login:fail:*`) checked *before* hashing; observed: 5×401 then `429 Retry-After: 897`.
* SSO/OIDC: **REAL but verified only against Keycloak** (`e2e/sso-flow.mjs`, 9/9 when it was run). Generic Spring OAuth2 client, lazy discovery, identity = (issuer, subject), unknown identity gets an account with **no** access, email never links accounts by default. No RP-initiated logout. MFA: **not in the app by design** (IdP's job; see `docs/AUTHENTICATION.md`). The dev Keycloak container is currently **stopped**.
* Not implemented: password change/reset, self sign-up, user-management API (users are created by seed/SQL or by first SSO login).

---
## 7. Authorization / RBAC

Layers: `User → workspace_members(role) → project_members(role) → Permission set` evaluated per request in `access/AccessService.kt` (`forWorkspace`, `forProject`) with `PermissionMatrix` in `access/Permission.kt`; controllers call `ctx.require(Permission.X)`. Unknown/foreign workspace or project = **404** (existence not leaked); known but not allowed = **403**. The `system_admin` flag grants everything (no API can set it; the dev `local.admin` is *not* a system admin — `/auth/me` shows roles `["USER"]` — but is WORKSPACE_ADMIN).

Roles that exist: workspace `WORKSPACE_ADMIN, EDITOR, PUBLISHER, VIEWER`; project `OWNER, EDITOR, PUBLISHER, VIEWER`; global `system_admin` ("ADMIN").

| Action (permission) | System admin | Workspace admin | Workspace EDITOR only* | Project OWNER | Project EDITOR | Project PUBLISHER | Project VIEWER |
| --- | :-: | :-: | :-: | :-: | :-: | :-: | :-: |
| See workspace/projects list | all | all | only own projects | own | own | own | own |
| View project (READ) | ✔ | ✔ all | – | ✔ | ✔ | ✔ | ✔ |
| Create project (PROJECT_CREATE) | ✔ | ✔ | ✔ (becomes OWNER) | – | – | – | – |
| Edit schema / prompt / restore / upload & delete asset (PROJECT_EDIT) | ✔ | ✔ | – | ✔ | ✔ | – | – |
| Project settings / rename (PROJECT_SETTINGS) | ✔ | ✔ | – | ✔ | ✔ | – | – |
| Delete project (PROJECT_DELETE) | ✔ | ✔ | – | ✔ | – | – | – |
| Publish (PROJECT_PUBLISH) | ✔ | ✔ | – | ✔ | – | ✔ | – |
| Manage project members (PROJECT_MEMBERS) | ✔ | ✔ | – | ✔ | – | – | – |
| Manage workspace members (MEMBER_MANAGE) | ✔ | ✔ | – | – | – | – | – |
| Read audit log (AUDIT_READ) | ✔ | ✔ | – | – | – | – | – |
*Workspace PUBLISHER/VIEWER roles give no project permissions by themselves either; project access comes from `project_members`. Rules in `MemberController`: nobody changes their own role; last WORKSPACE_ADMIN / last project OWNER cannot be removed/demoted (rows locked); removing a workspace member also removes project access; every change audited.

---
## 8. API inventory (all endpoints in code)

Base `/api/v1`. `{w}`=workspaceId, `{p}`=projectId. Auth: **S**=session, **A**=anonymous. All unsafe methods need `X-XSRF-TOKEN`. Common errors on every authenticated endpoint: `401 AUTHENTICATION_REQUIRED/ACCOUNT_DISABLED`, `403` (permission or CSRF), `404` (foreign ids), `500 INTERNAL_ERROR`; `413 REQUEST_TOO_LARGE` for JSON > 1 MiB; `503 DEPENDENCY_UNAVAILABLE` when Redis is down.

| Method | Path | Controller → collaborators → tables | Auth / permission | Specific errors | Frontend caller | Status |
| --- | --- | --- | --- | --- | --- | --- |
| GET | /auth/csrf | `AuthController` (CsrfToken) | A | – | `http-api.ts call()` before unsafe calls | REAL |
| GET | /auth/config | `AuthController` | A | – | `LoginScreen` | REAL |
| POST | /auth/login | `AuthController` → `RateLimiter`, `PasswordHashGate`, `AuthenticationManager`, `AuditService`; `users` | A | 400, 401 INVALID_CREDENTIALS, 404 LOCAL_LOGIN_DISABLED, 429, 503 LOGIN_BUSY | `api.login` | REAL |
| GET | /auth/me | `AuthController`; `users`,`workspace_members`,`workspaces` | S | – | `api.me` | REAL |
| POST | /auth/logout | `AuthController`; audit | S | – | `api.logout` | REAL |
| GET | /oauth2/authorization/oidc, /login/oauth2/code/oidc | Spring OAuth2 client → `OidcLoginSuccessHandler`; `external_identities`,`users` | A (only if `OIDC_ENABLED`) | redirect `/?sso_error=…` | "Đăng nhập bằng SSO" link | REAL (Keycloak only) |
| GET | /components, /components/{id} | `ComponentController` → `ComponentRegistry`; `components`,`component_versions` | S | 404 | – (not called by UI) | REAL |
| GET | /workspaces/{w}/projects | `ProjectController` → `ProjectRepository` (JPA); `projects`,`project_members` | S, workspace member | 404 | `api.listProjects` | REAL — **not paginated** |
| POST | /workspaces/{w}/projects | `ProjectController` → repo, `SchemaService.ensureInitialized`, audit; `projects`,`project_members`,`page_schemas`,`project_versions` | PROJECT_CREATE | 400 VALIDATION_FAILED | `api.createProject` | REAL |
| GET | /workspaces/{w}/projects/{p} | `ProjectController` | READ | 404 | `api.getProject` | REAL |
| PATCH | …/projects/{p} | `ProjectController`; `projects` | PROJECT_SETTINGS | 400, 409 REVISION_CONFLICT | `api.updateProject` | REAL |
| DELETE | …/projects/{p} | `ProjectController` (soft delete `active=false`) | PROJECT_DELETE | 404 | – (no UI) | REAL |
| GET | …/{p}/schema | `SchemaVersionController` → `SchemaService`; `page_schemas`,`project_versions` | READ | – | `api.getSchema` | REAL |
| PATCH | …/{p}/schema | `SchemaVersionController` → `SchemaPatchEngine`, `PageSchemaValidator`, `SchemaCommitService` | PROJECT_EDIT | 400 INVALID_OPERATION/SCHEMA_INVALID, 409 | `api.patchSchema` (Edit drawer) | REAL |
| POST | …/{p}/prompts | `PromptController` → `LLMProvider`(Mock), `SchemaPatchEngine`, `SchemaCommitService`, `RateLimiter`; `prompts`,`prompt_runs`,`project_versions`,`page_schemas`,`component_usage` | PROJECT_EDIT | 400, 409, 429 | `api.sendPrompt` | REAL pipeline, MOCK LLM |
| GET | …/{p}/prompts | `PromptController`; `prompts`,`prompt_runs` | READ | – | `api.listPrompts` | REAL |
| GET | …/{p}/versions, …/versions/{id} | `SchemaVersionController` → `SchemaRepository`; `project_versions` | READ | 404 | `api.listVersions` (detail unused by UI) | REAL |
| POST | …/{p}/versions/{id}/restore | `SchemaVersionController` → `SchemaCommitService` | PROJECT_EDIT | 404, 409 | `api.restoreVersion` | REAL |
| POST | …/{p}/assets/upload-url | `AssetController` → `StorageProvider`(MinIO), `RateLimiter`; `assets` | PROJECT_EDIT | 400, 413 FILE_TOO_LARGE, 415, 429 | `api.uploadAsset` | REAL |
| POST | …/{p}/assets/complete | `AssetController` (verifies object size/type) | PROJECT_EDIT | 409 UPLOAD_MISSING/ASSET_NOT_PENDING, 422 INVALID_UPLOAD | `api.uploadAsset` | REAL |
| GET | …/{p}/assets | `AssetController` | READ | – | `api.listAssets` | REAL |
| DELETE | …/{p}/assets/{id} | `AssetController` (soft delete + object delete) | PROJECT_EDIT | 404 | `api.deleteAsset` | REAL |
| POST | …/{p}/publish | `PublishController` → `DeploymentRepository`, `JobQueue`; `deployments`,`deployment_events`,`idempotency_keys` | PROJECT_PUBLISH | 400 (missing/invalid Idempotency-Key), 409 REVISION_CONFLICT / IDEMPOTENCY_KEY_REUSED, 429 | `api.publish` | REAL queue, MOCK provider |
| GET | …/{p}/deployments, …/deployments/{id} | `PublishController`; `deployments`,`deployment_events` | READ | 404 | `api.getDeployment` (list unused) | REAL |
| GET/POST/PATCH/DELETE | /workspaces/{w}/members[/{userId}] | `MemberController` (JdbcTemplate); `workspace_members`,`users`,`audit_events` | MEMBER_MANAGE | 400, 404, 409 ALREADY_MEMBER/LAST_ADMIN, 422 | `api.*WorkspaceMember` | REAL |
| GET/POST/PATCH/DELETE | …/{p}/members[/{userId}] | `MemberController`; `project_members` | PROJECT_MEMBERS | 409 LAST_OWNER, 422 NOT_WORKSPACE_MEMBER | `api.*ProjectMember` | REAL |
| GET | /workspaces/{w}/audit-events | `AuditController` (JdbcTemplate); `audit_events` | AUDIT_READ | – | – (no UI) | REAL |
| GET | /actuator/health, /health/liveness, /health/readiness | Actuator | A | readiness 503 when a dependency is down | scripts | REAL |
| GET | /actuator/prometheus | Actuator | bearer `METRICS_TOKEN` only | 401/403 | – | REAL (off by default) |
| GET | /v3/api-docs, /swagger-ui.html | springdoc | A, **local profile only** | – | – | REAL |
| – | create user / create workspace / system admin API | – | – | – | – | **NOT IMPLEMENTED** (only seed or first SSO login) |

Real examples (captured from the running API):

**POST /auth/login** → `200` `{"id":"e35ef152-…","username":"local.admin","displayName":"local.admin","roles":["USER"],"workspaces":[{"id":"00000000-0000-0000-0000-000000000001","name":"Local Workspace","role":"WORKSPACE_ADMIN"}]}` and `Set-Cookie: STUDIO_SESSION=…; Path=/; HttpOnly; SameSite=lax`.

**POST /workspaces/{w}/projects** `{"name":"Walkthrough sample","description":"created by the walkthrough"}` → `201` `{"id":"ffbb292a-…","name":"Walkthrough sample","framework":"nextjs","projectAccessPolicy":"PRIVATE_MEMBERS","siteVisibility":"PRIVATE","authMode":"LOCAL","deploymentMode":"MOCK","status":"ACTIVE","revision":0,…,"permissions":[]}` (permissions are filled on GET).

**POST …/prompts** `{"prompt":"Thêm bảng so sánh sản phẩm","expectedRevision":0}` → `200` `{"promptId":"…","outcome":"UPDATED","message":{"role":"assistant","content":"Đã thêm ComparisonBlock từ thư viện trước phần Testimonials."},"schemaPatch":[{"type":"ADD_SECTION","sectionId":"comparison-1","sectionType":"ComparisonBlock","props":{"heading":"So sánh sản phẩm","columns":["K-Series Pure","Smart RO Max","Eco Compact"],"rows":[…]},"beforeSectionId":"testimonials-1"}],"pageSchema":{…},"revision":1,"version":{…},"registryReuse":100}`.

**POST …/publish** `{"visibility":"PUBLIC","expectedRevision":3}` + `Idempotency-Key: …` → `202` `{"id":"8db40b9c-…","versionNumber":4,"visibility":"PUBLIC","status":"QUEUED","url":null,"provider":"mock","events":[{"status":"QUEUED",…}],"mock":true}`; ~0.04 s later `status":"RUNNING","url":"http://localhost:3000/mock-deployments/walkthrough-sample-8db40b9c"` with 6 events.

**Errors (real bodies):** `400 {"code":"VALIDATION_FAILED","message":"Request validation failed","requestId":"req_…","details":{"fields":{"name":"must not be blank"}}}` · `401 {"code":"AUTHENTICATION_REQUIRED",…}` · `403 {"code":"CSRF_INVALID","message":"Missing or invalid CSRF token",…}` (or `FORBIDDEN`) · `404 {"code":"PROJECT_NOT_FOUND",…}` · `409 {"code":"REVISION_CONFLICT","message":"Project changed elsewhere; reload and retry.","details":{"currentRevision":1}}` · `429 {"code":"RATE_LIMITED","details":{"retryAfterSeconds":897}}` + `Retry-After` · `400 INVALID_OPERATION "operation[1] ADD_SECTION: component 'Nope' is not in the registry"` · `415 UNSUPPORTED_MEDIA_TYPE` for SVG upload · `503 DEPENDENCY_UNAVAILABLE` (Redis down, covered by `RedisOutageTests`, not re-triggered here).

---
## 9. UI action → API → backend → store

| UI action | Component | API | Backend | DB/infra |
| --- | --- | --- | --- | --- |
| Login | `LoginScreen` | csrf, login, me | `AuthController`, Spring Security | `users`, Redis session, audit |
| SSO | `LoginScreen` link | `/oauth2/authorization/oidc` | OAuth2 client, `OidcLoginSuccessHandler` | Keycloak, `external_identities`, Redis |
| Load projects | `ProjectList` | `GET …/projects` | `ProjectController` | `projects`,`project_members` |
| Create project | `ProjectList` | `POST …/projects` | `ProjectController`, `SchemaService` | `projects`,`project_members`,`page_schemas`,`project_versions`,audit |
| Rename / settings | `SettingsDrawer` | `PATCH …/projects/{p}` | `ProjectController` | `projects`, audit |
| Send prompt | `StudioView.submitPrompt` | `POST …/prompts` | `PromptController`→Mock LLM→`SchemaPatchEngine`→validator→`SchemaCommitService` | `prompts`,`prompt_runs`,`project_versions`,`page_schemas`,`component_usage`,`projects.revision`, audit |
| Edit product / hero | `ContentDrawer` | `PATCH …/schema` | `SchemaVersionController`… | same as above (kind `EDIT`) |
| Open history | toolbar | (versions already loaded) `GET …/versions` | `SchemaVersionController` | `project_versions` |
| Restore | `StudioView.restore` | `POST …/versions/{id}/restore` | `SchemaCommitService` | new `project_versions` row (kind `RESTORE`), `page_schemas`, revision, audit |
| Upload image | `AssetsDrawer` | `upload-url` → PUT MinIO → `complete` | `AssetController`, `MinioStorageProvider` | `assets`, MinIO bucket, audit |
| Publish | `PublishModal` | `POST …/publish`, poll `GET …/deployments/{id}` | `PublishController`, `DeploymentProcessor` | `deployments`,`deployment_events`,`idempotency_keys`, RabbitMQ, audit |
| Add/change/remove member | `MembersDrawer` | members endpoints | `MemberController` | `workspace_members`/`project_members`, audit |
| Logout | top bar | `POST /auth/logout` | `AuthController` | Redis session deleted, audit |

---
## 10. Database (Flyway V1–V6, PostgreSQL 17.6)

| Table | Purpose | Important fields | FKs | Used by |
| --- | --- | --- | --- | --- |
| users | accounts | username, password_hash, enabled, system_admin, email, auth_source (LOCAL/OIDC) | – | auth, members |
| workspaces | tenants | name, slug | – | everywhere |
| workspace_members | workspace role | role (WORKSPACE_ADMIN/EDITOR/PUBLISHER/VIEWER), active | users, workspaces | access, members |
| projects | a website | owner_user_id, site_visibility, revision, active, framework, auth_mode, domain, custom_domain, deployment_mode, deployment_target | users, workspaces | project API |
| project_members | project role | role (OWNER/EDITOR/PUBLISHER/VIEWER), active | projects, users, workspace_members | access |
| components, component_versions | the registry | id, category, latest_version, **props_schema (jsonb)**, actions, permissions | component_versions→components | validator, prompt |
| page_schemas | current schema | project_id (PK), **schema (jsonb)** | projects | schema API |
| project_versions | immutable history | version_number, **schema_snapshot (jsonb)**, kind (INITIAL/PROMPT/EDIT/RESTORE), prompt_id, restored_from_version_id, source_revision | projects, prompts, users, self | history/restore |
| prompts | what users typed | text, created_by | projects, users | chat history |
| prompt_runs | what the LLM planned | intent, status, **schema_patch (jsonb)**, assistant_message, registry_reuse | prompts, project_versions | chat history |
| component_usage | components used per version | usage_count | project_versions, component_versions | stats (write-only today) |
| assets | file metadata | storage_key, status (PENDING/READY/DELETED), size_bytes, content_type | projects, users | asset API, cleanup |
| deployments | one publish | version_id, visibility, status, url, error, provider | projects, project_versions, users | publish |
| deployment_events | state history | status, message | deployments | publish UI |
| idempotency_keys | duplicate-publish guard | scope_key (PK), request_hash, resource_id | – | publish, cleanup |
| audit_events | append-only audit | action, resource_type/id, actor_id, **old_value/new_value (jsonb)**, ip_address, user_agent, request_id | – (no FK, on purpose) | audit API |
| external_identities | OIDC identity map | (issuer, subject) PK, user_id | users | SSO |
| flyway_schema_history | migrations | – | – | Flyway |
JSONB columns: `audit_events.old_value/new_value`, `component_versions.props_schema/actions/permissions`, `page_schemas.schema`, `project_versions.schema_snapshot`, `prompt_runs.schema_patch`. Triggers: `audit_events_no_update_delete`, `audit_events_no_truncate`.

```
users ─< workspace_members >─ workspaces ─< projects ─< project_members (also → workspace_members)
                                              │
                         ┌────────────────────┼───────────────┬──────────────┬─────────────┐
                    page_schemas        project_versions     prompts        assets     deployments ─< deployment_events
                    (current JSONB)     (snapshots; self FK   └< prompt_runs                │
                                        restored_from)  >── component_usage >── component_versions >── components
users ─< external_identities     idempotency_keys (loose link)     audit_events (loose link by ids)
```
Dev database at inspection: 1,680+ projects and ~77,000 versions, almost all created by load tests.

---
## 11. Project data model
* `projects` columns: `id, workspace_id, name, owner_user_id, project_access_policy (always PRIVATE_MEMBERS), site_visibility (PRIVATE|PUBLIC), revision, active, created_at, updated_at, description, framework (nextjs|react|static), auth_mode (NONE|LOCAL|OIDC), domain, custom_domain, deployment_mode (MOCK|SELF_HOSTED|CLOUD), deployment_target`. There is **no** `project_settings` table: settings are plain columns of `projects`.
* API response adds `status` (ACTIVE|DELETED) and `permissions[]` (what the caller may do).
* **Revision** = optimistic-lock counter of the *project* (JPA `@Version` + explicit CAS in `SchemaCommitService`); it increases on every schema commit **and** every settings save. **Version number** counts only schema snapshots. They are different numbers (the sample project: revision 3 after prompt+edit+restore, versions 1..4).
* Page content (sections/props) is only in the schema, never in `projects`.
* `site_visibility` is set by a successful publish **and** can also be set directly by `PATCH` (API allows it; the UI settings drawer does not expose it) — inconsistency noted in §33.

---
## 12. Page schema (real, from the sample project)
```json
{"page":"product-site","sections":[
 {"id":"navbar-1","type":"Navbar","componentVersion":"1.0.0","props":{"brand":"Walkthrough sample","links":[{"id":"l1","href":"#products","label":"Sản phẩm"},{"id":"l2","href":"#contact","label":"Liên hệ"}]}},
 {"id":"hero-1","type":"Hero","componentVersion":"1.0.0","props":{"eyebrow":"Pure living • smart water","title":"Nước sạch mỗi ngày, sống khỏe mỗi ngày.","description":"…","ctaLabel":"Khám phá sản phẩm"}},
 {"id":"products-1","type":"ProductGrid","componentVersion":"1.0.0","props":{"heading":"Chọn giải pháp phù hợp","items":[{"id":"p1","name":"K-Series Pure","description":"…"},{"id":"p2",…},{"id":"p3",…}]}},
 {"id":"technology-1","type":"TechnologySection","props":{"heading":"…","body":"…"}},
 {"id":"testimonials-1","type":"Testimonials","props":{"heading":"Khách hàng nói gì","visible":true,"items":[{"id":"t1","quote":"…","author":"Nguyễn Mai","rating":5,"location":"Hà Nội"},…]}},
 {"id":"contact-1","type":"ContactForm","props":{"heading":"Liên hệ tư vấn","submitLabel":"Gửi yêu cầu"}},
 {"id":"footer-1","type":"Footer","props":{"text":"© Pure Living"}}]}
```
`page` = fixed page kind; `sections[]` ordered; `sectionId` stable id used by operations; `type` must be a registry component; `componentVersion` is informational (always 1.0.0); `props` validated against the registry; `items[]` are arrays of objects with their own stable ids.
**Preview rendering** (`lib/schema-preview.ts`) is a hand-written `switch` over 8 types (Navbar, Hero, ProductGrid, TechnologySection, ComparisonBlock, Testimonials, ContactForm, Footer): Hero → eyebrow/h1/description/CTA + decorative box; ProductGrid → heading + card per item; Testimonials → skipped when `props.visible === false`, stars from `rating`. **Not generic**: `LandingTemplate` and `ProductCard` exist in the registry but render nothing; a new component needs backend registry rows *and* a new `case` in the frontend. Styles are shared with the mock renderer (`previewStyles`).

---
## 13. Component registry (table `components` + `component_versions`, seeded by V5)
| id | category | version | props | actions | frontend renderer |
| --- | --- | --- | --- | --- | --- |
| Navbar | UI | 1.0.0 | brand, links[] | navigate | yes |
| Hero | BLOCK | 1.0.0 | title, eyebrow, description, ctaLabel | navigate | yes |
| ProductGrid | BLOCK | 1.0.0 | heading, items[{id,name,description}] | edit-item | yes |
| ProductCard | BUSINESS | 1.0.0 | name, description | – | **no** |
| TechnologySection | BLOCK | 1.0.0 | heading, body | – | yes |
| ComparisonBlock | BLOCK | 1.0.0 | heading, columns[], rows[{id,label,values[]}] | – | yes |
| Testimonials | BLOCK | 1.0.0 | heading, visible, items[{id,quote,author,rating,location}] | – | yes |
| ContactForm | BUSINESS | 1.0.0 | heading, submitLabel | submit-lead | yes (placeholder, sends nothing) |
| Footer | UI | 1.0.0 | text | – | yes |
| LandingTemplate | TEMPLATE | 1.0.0 | theme | – | **no** |
Registry location: PostgreSQL (`ComponentRegistry` reads it); there is no UI to edit it (REGISTRY_WRITE permission exists, no endpoint).
"Prompt → registry lookup → reuse": **PARTIAL/mostly cosmetic.** The registry list *is* passed to `LLMProvider.plan`, but `MockLLMProvider` ignores it (hard-coded intents and component names). The registry *is* enforced afterwards by the validator (unknown types rejected). `registryReuse` returned to the UI = percentage of sections in the resulting schema whose type exists in the registry — nearly always 100, not a measure of real reuse.

---
## 14. Prompt / mock-AI flow ("Thêm bảng so sánh sản phẩm")
```
Composer (HttpStudio.submitPrompt)
 → lib/http-api.ts sendPrompt  POST /workspaces/{w}/projects/{p}/prompts {prompt, expectedRevision}
 → [filters] RequestId · ClientIp · RequestSize · Security(session, CSRF) · ActiveUser
 → PromptController.run: AccessService.forProject + require(PROJECT_EDIT); RateLimiter(prompt 30/min/user);
   revision check (409) → SchemaService.ensureInitialized → INSERT prompts
 → MockLLMProvider.plan(prompt, current schema, registry list)
     intent by keywords: "so sánh"/"compare" → ADD_SECTION ComparisonBlock (columns/rows copied from ProductGrid items,
     inserted before Testimonials); else UNSUPPORTED
 → SchemaPatchEngine.apply (on a deep copy)  → PageSchemaValidator against the registry (inside SchemaCommitService.commit)
 → SchemaCommitService.commit @Transactional:
     UPDATE projects SET revision=revision+1 WHERE id=? AND revision=?   (0 rows → 409)
     upsert page_schemas · insert project_versions (kind PROMPT) · insert component_usage · audit CREATE_VERSION
 → INSERT prompt_runs (intent, status, schema_patch jsonb, registry_reuse) · audit RUN_PROMPT
 → 200 {outcome, message, schemaPatch, pageSchema, revision, version, registryReuse}
 → UI: setSchema → renderSchemaDocument → iframe updates; chat shows the assistant message
```
Supported prompts (keyword match, case-insensitive, Vietnamese/English): add comparison table (`so sánh`/`compare`); hide/delete/show/add testimonials (`đánh giá` + `bỏ|ẩn` / `xóa|xoá` / `hiện|hiển thị|thêm`); add a product (`thêm` + `sản phẩm`, not with `so sánh`); shorten hero (`rút gọn` + `hero`). Outcomes: `UPDATED` (new version), `NO_CHANGE` (recognised but nothing to do, no version), `UNSUPPORTED` ("Mock LLM chưa hiểu yêu cầu này", no version, still stored as a prompt/run). There is no real LLM; `app.llm.provider` is a config key nothing reads.

---
## 15. Schema patch
Operations (`SchemaOperation`): `ADD_SECTION` (sectionType, sectionId, props, beforeSectionId/afterSectionId), `REMOVE_SECTION`, `MOVE_SECTION`, `UPDATE_SECTION` (merge props), `UPDATE_PROP` (sectionId, optional itemId/arrayPath, path, value), `ADD_ITEM`, `REMOVE_ITEM`. Max 50 per request; path segments restricted to `[A-Za-z][A-Za-z0-9]{0,39}` and `__proto__/constructor/prototype` rejected; no field can carry code.
Real payload used by the Edit drawer: `PATCH …/schema {"expectedRevision":1,"summary":"edit hero","operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"Nước sạch. Sống khỏe."}]}` → `200 {schema, revision:2, version:{versionNumber:3, kind:"EDIT"}}`.
Validation: patch applied to a copy; `PageSchemaValidator` checks section ids unique, types in registry, required props, types and max lengths, item shapes; a failure returns `400 INVALID_OPERATION` (with `operationIndex`) or `SCHEMA_INVALID` and **nothing is stored**. The revision check makes two simultaneous editors safe: the loser gets `409` and must reload.

---
## 16. Revision / version / undo / history
* **Project revision**: counter for concurrency; +1 on schema commit and settings save.
* **Page-schema version**: immutable snapshot row; numbered 1,2,3… gaplessly per project.
* **Browser Undo/Redo**: only in the legacy Pages demo (localStorage stacks). **Not in the Next UI.**
* **History**: the version list; restore is server-side.
```
v1 INITIAL ─prompt─► v2 PROMPT ─edit─► v3 EDIT ─restore v1─► v4 RESTORE (copy of v1's schema, restored_from_version_id = v1)
```
Restore creates a *new* version so history is never rewritten, audit stays consistent and the restore itself can be restored. Stored in `project_versions` (+ current copy in `page_schemas`). API: `GET …/versions`, `POST …/versions/{id}/restore {expectedRevision}`. Verified on the sample project (versions 3→4, `kind=RESTORE`).

---
## 17. Assets / MinIO
```
Browser ─POST …/assets/upload-url {fileName,contentType,size}─► AssetController: PROJECT_EDIT, rate limit 60/min, MIME allowlist,
        size limit, server-made key  → INSERT assets (PENDING) → MinioStorageProvider.presignUpload
Browser ◄─ {assetId, uploadUrl (SigV4, 600 s), method PUT, headers}
Browser ─PUT bytes directly─► MinIO (bucket studio-assets)
Browser ─POST …/assets/complete {assetId}─► AssetController: stat object; size & content-type must match → READY (else delete+422)
PostgreSQL assets row (metadata) · list returns short-lived presigned GET URLs
```
Bucket `studio-assets` (created on first use, private). Key `workspaceId/projectId/assetId/<sanitised-name>` (always server generated). Allowed: png, jpeg, webp, gif (≤ 10 MiB), pdf (≤ 20 MiB); SVG/HTML rejected (415). Signed URL lifetime 10 min. Delete = row `DELETED` + object removed; the cleanup job reaps old PENDING/DELETED. Classes: `AssetController`, `StorageProvider`/`MinioStorageProvider` (two clients: internal and public-endpoint signer). Assets are not yet usable inside page components.

---
## 18. RabbitMQ
Used **only for publish**. Queue `studio.publish` (durable, classic, dead-letter → `studio.publish.dlq`); producer `AmqpJobQueue` (called by `PublishController` after the DB commit); consumer `PublishWorker` (`@RabbitListener`, concurrency 2, prefetch 1, auto-ack after the method returns); payload = deployment id string; retry 3 attempts with 1 s × 2 back-off then rejected without requeue → DLQ. Restart behaviour: queues are durable and persisted in the `hbl_studio-rabbitmq` volume; unacked jobs are redelivered; and `DeploymentRecovery` (scheduled every 30 s) re-publishes deployments stuck in QUEUED/in-progress, so a lost enqueue or a dead worker is healed from PostgreSQL. The DLQ has no consumer and no UI (manual inspection at :15675). Observed now: `studio.publish` 0 messages, 2 consumers; DLQ 0.

---
## 19. Publish flow
```
PublishModal ─POST …/publish {visibility, expectedRevision} + Idempotency-Key
 → PublishController: forProject + PROJECT_PUBLISH · INSERT idempotency_keys ON CONFLICT (same key+body → replay; different body → 409)
   · rate limit 10/min · revision check · latest version · INSERT deployments(QUEUED)+event · audit PUBLISH
   · afterCommit → JobQueue.publish(deploymentId)           ← 202 {status:"QUEUED"}
 → RabbitMQ studio.publish → PublishWorker → DeploymentProcessor (each step is a compare-and-set UPDATE on deployments.status)
     QUEUED → POLICY_CHECK (project active, schema valid vs registry)
            → SECURITY_CHECK (rejects "<script", "javascript:", "onerror=", "onload=", "data:text/html")
            → BUILDING (SHA-256 of the snapshot; nothing is built)
            → DEPLOYING (MockDeployProvider returns "<base-url>/<slug>-<id8>"; target "fail" simulates failure)
            → RUNNING (sets projects.site_visibility, no revision bump)       or  FAILED(error)
   every step: deployment_events row + audit DEPLOY_STATUS_CHANGE (actor null, request id "job_…")
 → PublishModal polls GET …/deployments/{id} every 0.8 s and lists the events
```
Statuses in use: QUEUED, POLICY_CHECK, SECURITY_CHECK, BUILDING, DEPLOYING, RUNNING, FAILED (ROLLED_BACK is reserved, unused). The URL is **MOCK**: `app.deploy.mock.base-url` = `http://localhost:3000/mock-deployments` (nothing serves it; on this machine port 3000 belongs to another app). PRIVATE/PUBLIC only sets `projects.site_visibility` and the deployment's `visibility` field; there is no site gateway that enforces it.

---
## 20. Audit log
Table `audit_events` (append-only via trigger; app has no delete path; the cleanup job never touches it). Written in the caller's transaction (a rolled-back change leaves no row). Events: `LOGIN_SUCCESS` (method OIDC for SSO), `LOGIN_FAILURE`, `LOGOUT`, `PROVISION_USER`, `LINK_IDENTITY`, `CREATE_PROJECT`, `UPDATE_PROJECT` (old/new field diff), `DELETE_PROJECT`, `RUN_PROMPT`, `CREATE_VERSION`, `RESTORE_VERSION`, `UPLOAD_ASSET`, `DELETE_ASSET`, `PUBLISH`, `DEPLOY_STATUS_CHANGE`, `ADD_MEMBER`, `CHANGE_PERMISSION`, `REMOVE_MEMBER`, `CLEANUP`. Columns carry actor, ip (after the trusted-proxy filter), user agent and `request_id` (same value as the `X-Request-Id` response header and log MDC). Read through `GET …/audit-events` (workspace admin). Prompt text is not stored in audit rows. 215k rows exist from tests/load.

---
## 21. Request id and errors
```
request → RequestIdFilter (accept valid X-Request-Id or make req_<uuid>) → MDC "requestId" + request attribute + response header
 → ClientIpFilter → RequestSizeFilter → Security chain → controller → exception
 → ApiExceptionHandler / security handlers → {code,message,requestId,details} + same X-Request-Id
 → AccessLogFilter logs "http_request method route status duration_ms user" (route pattern only)
```
Background jobs use `job_<deploymentId>` as request id. Samples are in §8. Unhandled errors return a generic 500 body with the id; the stack trace is only in the log.

---
## 22. Security by layer
| Control | State | Detail |
| --- | --- | --- |
| TLS | **Missing in the running stack** (plain http locally); nginx config + smoke test exist in `infra/nginx` (certs not included) | PARTIAL |
| CSP | Implemented for http-mode UI (nonce, `strict-dynamic`, no unsafe-eval; `style-src 'unsafe-inline'` because the preview iframe inherits it); API sends `default-src 'none'`. **Not applicable** to GitHub Pages/static builds | PARTIAL |
| HSTS | API emits it only on requests it believes are https (trusted proxy); nginx example sets it; UI via `STUDIO_HSTS=true` | PARTIAL |
| CSRF | Implemented (cookie token + header, all unsafe methods) | REAL |
| CORS | Implemented allowlist (`CORS_ALLOWED_ORIGINS`) | REAL |
| Authentication | Password (Argon2id) + OIDC; sessions in Redis | REAL |
| RBAC / IDOR-BOLA | Permission checks per request, tenant-scoped queries, 404 for foreign ids, tested | REAL |
| Rate limits | login (user+IP), prompt, publish, upload | REAL |
| Password hashing | Argon2id (64 MiB) via BouncyCastle, ≤4 concurrent verifications | REAL |
| Secrets | none in git (gitleaks clean on history + tree); `prod` profile refuses dev defaults; no secret manager integration (env only) | PARTIAL |
| Trusted proxy | `TRUST_PROXY` + `TRUSTED_PROXY_CIDRS`, right-most-untrusted XFF, tested | REAL (not run behind a real proxy) |
| SSO/OIDC | see §6 | PARTIAL |
| MFA | delegated to IdP; none in app | NOT IMPLEMENTED in app |
| Audit | REAL |
| Dependency scan | `npm audit --omit=dev`: 0 (earlier run). **No backend/JVM dependency vulnerability scan has been run** (no OWASP/Trivy step) | PARTIAL |
Also: input validation (Bean Validation + registry), sandboxed preview, generic errors, `server.error.include-*: never`, actuator limited to health.

---
## 23. Infrastructure (docker project `hbl`)
| Service | Purpose | Image | Host→container port | Volume | Health | Memory limit / observed | Persistence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL | source of truth | postgres:17.6 | 15432→5432 | hbl_studio-postgres | healthy (pg_isready) | 1 GiB / ~244 MiB | durable volume; PITR **not** enabled here |
| Redis | sessions, rate limits | redis:8.2.1-alpine | 16379→6379 | hbl_studio-redis | healthy | 384 MiB / ~37 MiB | AOF on, `noeviction`, maxmemory 256 MB |
| MinIO | asset bytes | bitnamilegacy/minio:2025.7.23-debian-12-r5 | 19000→9000, 19001→9001 | hbl_studio-minio | healthy | 512 MiB / ~177 MiB | durable volume |
| RabbitMQ | publish jobs | rabbitmq:4-management-alpine | 15674→5672, 15675→15672 | hbl_studio-rabbitmq | healthy | 768 MiB / ~130 MiB | durable queues |
| Keycloak (profile `sso`, optional) | local IdP | quay.io/keycloak/keycloak:26.4 | 18080→8080 | none (realm imported) | none | 1 GiB | currently **Exited (137)** |
All bound to 127.0.0.1. Native processes: the API JVM (Gradle `bootRun`, which adds `-XX:TieredStopAtLevel=1`) and the Next.js server.

---
## 24. Local development
`./scripts/run-local.sh`: loads `.env` + defaults (`_env.sh`: JDK 21, `COOKIE_SECURE=false`, CORS for the UI port, MinIO endpoint), optionally `.run/sso.env`; `docker compose up -d --wait` (4 services); starts `./gradlew bootRun` (profile `local`: Swagger, seed users `local.admin/editor/publisher/viewer` and the demo project) and waits for `/actuator/health/liveness`; then, if the UI port is silent, builds the UI (`next build`, http mode, `.next-http`) and runs `next start -p 3100`. Ports: UI 3100, API 8080, Postgres 15432, Redis 16379, MinIO 19000/19001, RabbitMQ 15674/15675. `stop-local.sh` kills both and waits for the ports.
```
MacBook (M1 Pro)
├── Node  → next start :3100  (rewrites /api,/oauth2,/login/oauth2 → :8080)
├── JVM   → Spring Boot :8080
└── Docker → PostgreSQL :15432 · Redis :16379 · MinIO :19000 · RabbitMQ :15674 (· Keycloak :18080 optional)
```
Note: an unrelated `next dev` for this repo may run on :3000 (mock mode); Chatwoot occupies 127.0.0.1:3000.

---
## 25. GitHub Pages
Pages is configured as **legacy build from branch `main`, path `/`** (verified via the GitHub API: `source: main /`). It therefore serves the repository root: `index.html` + `assets/studio.js` + `assets/studio.css` — a **hand-written vanilla-JS demo, not the Next.js app and not a Next static export**. `docs/index.html` is a copy. The page never calls a backend (no `fetch` in `studio.js`), keeps state in `localStorage` (`system-web-studio-state-v3`) with its own Undo/Redo, history, project switcher and product editor. Backend or branch changes cannot affect it unless the root files on `main` change; this branch's files are identical to `main` for those three.
**Correction to earlier reports:** `e2e/pages-mock.mjs` tests the *Next* mock-mode static export under `/system-web-studio/`, which is **not** what Pages serves. It validates the Next mock build, not the live site. The live URL answered 200 when last checked.

---
## 26. Real vs mock
| Feature | Status | Real/Mock | Notes |
| --- | --- | --- | --- |
| Frontend (Next http mode) | works | REAL | no routing, no undo/redo |
| Frontend (Next mock mode / Pages demo) | works | MOCK | in-memory / localStorage |
| Backend | works | REAL | |
| PostgreSQL | works | REAL | |
| Redis | works | REAL | sessions survive restarts (AOF) |
| Authentication (password) | works | REAL | |
| SSO | works with Keycloak only | PARTIAL | other IdPs untested; Keycloak currently stopped |
| Prompt pipeline | works | REAL | |
| LLM | keyword matcher | MOCK | 4 intents |
| Schema + validation | works | REAL | |
| Registry | seeded data, enforced | REAL (static) | no editor, LLM ignores it |
| Version/restore | works | REAL | |
| Assets | works | REAL | not usable in components |
| MinIO | works | REAL | |
| RabbitMQ | works | REAL | only publish |
| Publish pipeline | works | REAL | |
| Deploy | returns a fake URL | MOCK | no site is served |
| Git provider | interface + DB adapter, **never called** | NOT IMPLEMENTED | no commits/SHAs |
| Audit | works | REAL | |
| Backup | scripts tested in throwaway containers, not scheduled | PARTIAL | |
| PITR | drill passed in throwaway containers; **not enabled on the dev DB** | PARTIAL | |
| Member management | API + UI | REAL | no user-creation API |
| Observability | health, optional Prometheus/OTLP | PARTIAL | not verified against a collector |

---
## 27. Tests (counted/run on 2026-10-01)
* **Backend:** `cd backend && ./gradlew test` → **77 tests, 0 failures** (18 classes) — AuthSecurity 8+, Project 10, Schema 9, Prompt 6, Version 3, Asset 4, Publish 6, QueueRecovery 3, Member 7, Cleanup 2, Hardening 6, TrustedProxy 5, Audit 3, RedisSession/SessionRestart/RedisOutage, PasswordHashGate 2, repository test. They start **real Testcontainers**: PostgreSQL 17.6, Redis 8.2.1, MinIO (bitnamilegacy), RabbitMQ 4; SessionRestart starts two app contexts on one Redis.
* **Browser E2E** (Chrome via playwright-core, needs the running stack): `node e2e/full-flow.mjs` (26 checks: login, project, prompts, history/restore, edit, settings, assets, publish, restart, RBAC, disabled user, conflict, widths 390/1280/1440/1920/2560); `node e2e/sso-flow.mjs` (9, needs `scripts/sso-up.sh`); `node e2e/a11y.mjs` (axe-core on login, list, studio at 4 widths + 6 dialogs, plus keyboard focus/trap/Escape/error-alert checks).
* **Next mock export:** `node e2e/pages-mock.mjs` (6; see §25 caveat).
* **Security-oriented:** hardening/proxy/prod-config tests above, `scripts/secret-scan.sh` (gitleaks), `scripts/check.sh` (types, 2 builds, backend tests, npm audit), `scripts/smoke-test.sh`.
* **Infra drills:** `scripts/test-backup-restore.sh`, `pitr-drill.sh`, `test-minio-backup.sh`, `infra/nginx/smoke-test.sh` (all run by the infra agent in throwaway containers; passes reported).
* **Load:** `scripts/load-test.sh` (k6, last run: reads 340 rps at 10 VUs / 190 rps at 100 VUs, prompt ~500/360 rps, publish ~120–350 rps, 0% errors, p95 below ~0.5 s at 100 VUs except publish 311 ms; the API ran under `bootRun` with C1-only JIT, so a packaged JAR should do better; the `/projects` list degraded the reads because of the unpaginated 1,680-project list).

---
## 28. Five flows
**A. Login**
```
LoginScreen ─GET /auth/csrf──► AuthController ─► XSRF-TOKEN cookie
            ─POST /auth/login► CsrfFilter → RateLimiter(Redis rl:*) → PasswordHashGate → DaoAuthenticationProvider(users, Argon2)
                               → session id rotated → Redis spring:session:sessions:<id> → audit LOGIN_SUCCESS
            ◄─ 200 identity + Set-Cookie STUDIO_SESSION
```
**B. Create project**
```
ProjectList ─POST …/projects─► ProjectController (forWorkspace, PROJECT_CREATE) ─► projects + project_members(OWNER)
              ─► SchemaService.ensureInitialized (DefaultPageSchema → validator) ─► page_schemas + project_versions(v1 INITIAL) ─► audit CREATE_PROJECT
              ◄─ 201 project ─► UI opens the Studio (GET schema/versions/prompts)
```
**C. Send prompt** — see §14.

**D. Restore version**
```
History ─POST …/versions/{id}/restore {expectedRevision}─► SchemaVersionController (EDIT) ─► load snapshot (404 if foreign)
        ─► SchemaCommitService.commit(kind RESTORE) ─► revision CAS · page_schemas · project_versions(new, restored_from) · usage · audit
        ◄─ {version, schema, revision} ─► UI re-renders; history list reloaded
```
**E. Publish** — see §19.

---
## 29. If something restarts
| Component | Lost | Kept |
| --- | --- | --- |
| API (JVM) | in-flight requests; a worker mid-step | sessions (Redis), data (Postgres), queue; interrupted deployments resume (redelivery or sweeper, tested) |
| Frontend (Next) | nothing server-side; browser state (open project) | everything |
| PostgreSQL | API unavailable meanwhile (readiness 503) | all data (volume); `audit_events` intact |
| Redis | rate-limit windows if volume lost | sessions survive restart (AOF verified); API gives fast 503s while it is down |
| RabbitMQ | nothing on restart; with volume loss, queued ids (recovered by sweeper from Postgres) | durable queues |
| MinIO | uploads fail meanwhile | objects (volume) |
| Mac | everything stops (Docker must be started again — it was down after the last session break) | all volumes; the 2 native processes must be restarted with `run-local.sh` |
Durable: PostgreSQL, MinIO objects, RabbitMQ durable queue, Redis AOF (sessions). Volatile: Next/JVM processes, browser state, rate-limit counters on Redis loss.

---
## 30. Scale
The API is stateless: sessions in Redis, CSRF is cookie-based, no local files, CAS transitions make workers safe, `@Scheduled` jobs are idempotent (cleanup uses a Postgres advisory lock), RabbitMQ gives competing consumers. So `Load balancer → API 1..N` works with: sticky sessions **not** needed; shared Redis, Postgres, RabbitMQ, MinIO required; `TRUST_PROXY/TRUSTED_PROXY_CIDRS` set to the LB; `DB_POOL_MAX × N < max_connections` (Postgres default 100). Single points: one Postgres, one Redis (needs Sentinel/managed), one RabbitMQ node, one MinIO. Not verified with more than one instance. Next.js UI is also stateless (one extra build per environment because `API_PROXY_TARGET`/mode are build-time).

---
## 31. Where it can run
| Target | What changes |
| --- | --- |
| Mac local | works as is (verified) |
| Linux VPS (single node) | package the JAR + `next build`; use `infra/nginx` + compose for the 4 services; `prod` profile env (all required vars); scripts are bash 3.2-compatible but untested on Linux; a Dockerfile for API/UI does **not** exist yet |
| AWS | Postgres→RDS (`DATABASE_URL`), Redis→ElastiCache (+password), MinIO client works with S3 (`MINIO_ENDPOINT`=S3 endpoint, access keys; region currently fixed `us-east-1`), RabbitMQ→Amazon MQ for RabbitMQ (AMQP 0-9-1) ; secrets→Secrets Manager injected as env; DeployProvider real adapter still to write |
| Azure | Postgres Flexible Server, Azure Cache for Redis, Blob needs an S3 gateway (no S3 API natively → `StorageProvider` adapter needed), **Service Bus is AMQP 1.0, not compatible** with Spring AMQP (use RabbitMQ on VM/AKS-less container) |
| GCP | Cloud SQL, Memorystore, GCS via S3-interoperability (HMAC keys) or new adapter, RabbitMQ self-managed |
| On-prem | closest to the current compose; add TLS, backups (scripts exist), monitoring |
Config points: `DATABASE_*`, `REDIS_*`, `RABBITMQ_*`, `MINIO_*`, `CORS_ALLOWED_ORIGINS`, `TRUST_PROXY`, OIDC vars; code points: `StorageProvider`, `DeployProvider`, `LLMProvider`, `SecretProvider` (unused), `JobQueue`.

---
## 32. Not built yet
| Area | Missing | Impact | Prod blocker | Effort |
| --- | --- | --- | --- | --- |
| Product | real AI; assets usable in components; component editor/registry admin; undo/redo; diff between versions; deep links/routing; multi-page sites; real published site | the core promise (AI builds the site, site goes live) is simulated | for the product, yes | Large |
| Backend | user/workspace creation & invitation API; project list pagination; real DeployProvider; Git provider; unpublish/rollback; ROLLED_BACK use | cannot onboard users without SQL | yes | Medium–Large |
| Security | MFA in-app (IdP-delegated), password reset, session management UI, RP-initiated logout, JVM dependency scan, CSP for static builds | – | partially | Small–Medium |
| Infrastructure | Dockerfiles/images, CI, prod compose run end-to-end behind nginx (config validated only with stubs), managed secrets, HA data stores | – | yes | Medium |
| Operations | scheduled backups, off-host copies, monitoring/alerting, runbook rehearsal on a real host | – | yes | Medium |
| UX | project delete/rename UI polish, audit viewer, pagination/search, error states for IdP failure, mobile polish | – | no | Small–Medium |
| AI | provider integration, prompt evaluation set, registry-aware planning, safety filters, cost limits | – | yes (for real AI) | Large |

---
## 33. Technical debt (observed)
| Where | Issue | Impact | Priority |
| --- | --- | --- | --- |
| `ProjectController.list` | returns every project, no paging (1,680 in dev) | slow list, dominates read load; memory | High |
| Frontends | three UIs (root demo, Next mock, Next http) with separate renderers | drift, confusion about what Pages shows | High |
| `index.html`/Pages vs Next | Pages serves the legacy demo; e2e "Pages" test targets another artifact | false assurance | High |
| `PATCH /projects` | can set `siteVisibility` without publishing | inconsistent with deployments | Medium |
| `lib/schema-preview.ts` | non-generic renderer, 2 registry components unrenderable | new components need 2 code changes | Medium |
| `registryReuse` | cosmetic metric (always ~100) | misleading UI chip | Low |
| `GitProvider`, `SecretProvider`, `app.llm.provider`, `component_usage` | scaffolding never read | dead code/config | Low |
| Controllers (`Asset`, `Member`, `Audit`, `Auth`) | SQL directly in controllers; two persistence styles (JPA + JdbcTemplate) | harder to test/evolve | Medium |
| `MockDeployProvider` base URL | points to `localhost:3000` | broken link in UI | Low |
| Seed | `local.admin` is not a system admin (roles USER) | confusing | Low |
| No routing in UI | open project lost on refresh | UX | Medium |
| Dev runtime | `bootRun` uses C1-only JIT; `run-local.sh` rebuilds the UI each start | load numbers pessimistic, slow start | Low |
| Docs | `README.md` still lists CSP/backup as missing; `DEPLOYMENT.md` says migrations V1–V5 (V6 exists); `scripts/backup-restore-drill.sh` superseded | stale | Low |
| Tests | e2e scripts hard-code macOS Chrome path and docker container names | not portable | Low |
| Logging | `ApiExceptionHandler` logs full exceptions (DB errors can echo values) | PII risk, low | Low |

---
## 34. Suggested next steps (no code now)
1. **NEXT 1 — Make what users see and what is tested the same thing.** Decide the Pages story: either rebuild Pages from the Next mock export or keep the legacy demo and say so; point e2e at the real artifact. *Why:* current confidence statements about "GitHub Pages" are about a different artifact. *Output:* one deployed demo, one test that hits it.
2. **NEXT 2 — Onboarding + list scalability.** User/workspace invitation (or SSO auto-membership policy), project list pagination/search, routing/deep links. *Why:* today a new person cannot be added without SQL; the list is the main perf hotspot. *Output:* a team can actually use it.
3. **NEXT 3 — Real AI behind `LLMProvider`.** Registry-aware planning with schema-constrained output, prompt evaluation set, cost/rate guards, keep the validator as the gate. *Why:* the product value; architecture already isolates it. *Dependency:* decision on provider + data policy. *Output:* prompts beyond 4 keywords.
4. **NEXT 4 — Real publish target.** One real `DeployProvider` (static hosting on your VPS/S3+CDN) with rollback/unpublish and private-site enforcement. *Why:* publish currently produces nothing. *Output:* a reachable site per project.
5. **NEXT 5 — Production packaging.** Dockerfiles, run the `infra/nginx` compose end-to-end with real TLS on a VPS, schedule backups/PITR, add monitoring, add a JVM dependency scan, re-run load test on the packaged JAR. *Output:* a measured, rehearsed first environment.
6. **NEXT 6 — Product depth:** assets inside components, version diff, undo/redo, audit viewer, registry admin.
7. **NEXT 7 — Scale/HA** only after real usage data (Redis/Postgres HA, multi-instance test).
Order follows: product value → production blockers → security → reliability → AI depth → scale.

---
## 35. One-page summary
* **Frontend:** Next.js 16; http mode is the real UI (login, projects, studio, history, edit, settings, assets, members, publish); mock mode and the GitHub Pages demo are separate offline demos.
* **Backend:** Kotlin/Spring Boot 4 modular monolith, 40+ endpoints, RBAC per request, optimistic revisions, 77 passing integration tests.
* **Database:** PostgreSQL, 18 tables (6 migrations), JSONB schemas and snapshots, append-only audit.
* **Cache/session:** Redis sessions (20 min) + rate limits.
* **Files:** MinIO via presigned URLs, validated on completion.
* **Queue:** RabbitMQ for publish jobs only, durable with DLQ and a recovery sweeper.
* **AI:** mock keyword planner; real validation gate.
* **Deploy:** real state machine, **mock** provider and URL.
* **Security:** CSRF, CORS, CSP (http mode), Argon2id, throttling, tenant isolation, OIDC (Keycloak-verified), trusted-proxy handling; gaps listed in §22/§32.
* **Testing:** 77 backend tests on real containers, 26 + 9 browser flows, axe a11y run (0 violations), load/backup/PITR/MinIO drills.
* **Production readiness:** development- and internal-demo-ready; not production-ready (no real AI or deploy, no onboarding API, no packaging or monitored production run, no scheduled backups).
```
User → Web → API → Backend ─┬─ PostgreSQL
                            ├─ Redis
                            ├─ MinIO
                            └─ RabbitMQ
                              ↓
                       Mock AI / Mock Deploy
```
**5 next things:** (1) fix the Pages/test mismatch, (2) onboarding + pagination + routing, (3) real LLM provider, (4) real deploy target, (5) production packaging with a rehearsed deployment.
