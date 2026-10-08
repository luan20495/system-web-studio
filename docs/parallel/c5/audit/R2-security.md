# R2 — Frontend security audit (C5-owned frontend only)

Author: C5-R2 · Base: `agent/c5-web @ 9f858c2` · Branch: `agent/c5-r2-sec` · Date: 2026-10-08 · Mode: static analysis + one local production build of `apps/studio` (no server started, no port used).
Scope: `app/ features/ components/ lib/ packages/{auth,api-client,permissions,ui,i18n,types,company-ui,app-sdk} apps/*`. The backend is the authority for every decision below; nothing here proposes replacing server enforcement, and no backend contract is invented (needs are listed as handoffs in section 14).

## 0. Method

| Check | How |
|---|---|
| Sinks (`dangerouslySetInnerHTML`, `innerHTML`, `document.write`, `eval`, `new Function`, `srcDoc`, `postMessage`, `window.open`, `location.assign`, `target=_blank`) | `grep -rnE` over `app features components lib packages apps` (ts/tsx), every hit read |
| Storage | `grep localStorage|sessionStorage|indexedDB|document.cookie` |
| Network | `grep fetch(|XMLHttpRequest|EventSource|WebSocket|sendBeacon` (7 hits, all read) |
| Secrets / internal URLs in bundle | source regex scan (AWS/GitHub/OpenAI/JWT/PEM/known dev passwords/ports) + `npm ci && HBL_ENV=local npm run build:studio`, then grep of `apps/studio/.next/static` (8 chunks, 1.1 MB, no `.map`) |
| Escaping of the preview renderer | AST-free script: every one of the 96 `${...}` interpolations in `lib/schema-preview.ts` classified (all reach the document through `e()`/`href()`/`img()`/numeric clamps, or are constants) |
| Dependencies | `npm audit --omit=dev` (online, 2026-10-08) |
| Contrast | `scripts/r2-contrast.mjs` (WCAG 2.x formulae, tokens parsed from the real CSS) |

Severity scale: P0 unusable / security-critical · P1 major · P2 significant · P3 minor. **No P0 or P1 was found.** Inferences are labelled *(inference)*.

## 1. Summary

| | |
|---|---|
| Findings | 16 (P2: 4, P3: 12), see the table in section 15 |
| `dangerouslySetInnerHTML` / `innerHTML` / `outerHTML` / `document.write` / `insertAdjacentHTML` / `eval` / `new Function` | **0 occurrences** in all source (hits for "srcdoc" are the 4 sandboxed preview iframes, section 9) |
| `console.*` in portal code | **0** (only `packages/app-sdk`, which runs inside generated apps) |
| Credentials / internal hosts in source or in the built Studio bundle | **none** (only `http://127.0.0.1:8080` as the dev fallback of `API_PROXY_TARGET` in `packages/auth/src/server/nextConfig.ts:19`, server side; production build refuses to start without it) |
| `npm audit --omit=dev` | **0 vulnerabilities** (42 prod / 6 dev / 39 optional packages) |
| Role names used as authorization input | 2 remnants (R2-004) + display-only maps; the Studio guard test does not cover `features/admin` |

Top findings: **R2-001** (P2) a link can make a logged-in editor's browser send an AI prompt (navigation-triggered action) · **R2-002** (P2) IDE clone token rendered in clear twice and embedded in a `git clone https://user:TOKEN@…` command · **R2-003** (P2) site-access redirect and `path` are not validated on the client · **R2-004** (P2) role-name checks still decide tenant scope in the Admin console.

## 2. Auth state handling

| Item | Where | Verdict |
|---|---|---|
| Session | server cookie (HttpOnly, SameSite=Lax, Secure by default — `docs/SECURITY.md`); the client never reads or stores it; every call uses `credentials:"include"` same-origin through the Next `/api` rewrite (`packages/auth/src/server/nextConfig.ts:44-45`) | OK |
| Identity in memory | `SessionProvider` keeps `Me` in React state only (`packages/auth/src/session.tsx:13`); no token, no JWT, no refresh logic in the browser | OK |
| CSRF token | module-level promise in `packages/api-client/src/core.ts:11-27`, fetched from `GET /api/v1/auth/csrf`, sent as `X-XSRF-TOKEN`; reset on login (`api.ts:46`), logout (`api.ts:62`) and on a `403 CSRF_INVALID` (one retry, `core.ts:68`) | OK, except R2-012 (`stream()` has no retry) |
| 401 handling | one handler (`onUnauthorized`) clears `me` and sends the user to `/auth/session-expired?next=<path+search>`; `next` is re-validated by `safeNext` on the way back | OK |
| Persisted client state | only UI preferences (section 6); nothing user-identifying besides a workspace id | R2-011 |
| Activation token | read from the URL **fragment** (`AuthPages.tsx:32`), so it is never sent to a server or written to access logs; removed from the address bar only after a successful `completeActivation` (`AuthPages.tsx:41`) | OK; retention in state R2-006 |
| Logout | `api.logout()` then `setMe(null)`; SSO users are sent to the IdP end-session URL only if it matches `^https?://` (`api.ts:63`) | OK |

## 3. Permission gating (derived from server data or from role names?)

Derived from server-provided codes: `packages/permissions/src/canonical.ts` takes only the lists the server returned (`ApiProject.permissions`, `Me.workspaces[].permissions`, `Me.permissions`), translates the documented storage aliases and **drops anything else**; the portal gate `capabilitiesOf` (`packages/permissions/src/index.ts:41-55`) uses `platformScope`, `TENANT_MEMBERS`, `MEMBER_MANAGE`, `DATA_SOURCE_MANAGE`, `APP_VIEW`. The Builder read-only mode, Test panel conjunctions, publish/rollback, data-source manage/view all use `canonical.ts`. This matches `docs/parallel/c5/PERMISSION_CONTRACT.md` for Studio.

Remaining role-name decisions (all UX only — the server still authorises):

| Where | What | Finding |
|---|---|---|
| `packages/permissions/src/index.ts:46` | `me.tenantRole === "TENANT_ADMIN" \|\| me.tenants?.some((t) => t.role === "TENANT_ADMIN")` grants the `tenant.members` capability (which opens the `admin.console` gate, line 53) | R2-004 |
| `features/admin/adminModel.ts:25` | `(me.tenants ?? []).filter((t) => t.role === "TENANT_ADMIN" \|\| …)` decides which tenants the Admin console lists and whether `company / organization / employees` open (`sectionAccess`, lines 48-56); the file header says "No role NAME is read" | R2-004 |
| `features/admin/adminModel.ts:132,142` | last-admin protection (`role === "TENANT_ADMIN"` / `"WORKSPACE_ADMIN"`) | validation hint, not gating; the server also refuses (`LAST_SYSTEM_ADMIN` etc.) — acceptable |
| `features/studio/CodeWorkspace.tsx:211` | "Approve" shown when `change.createdBy !== me.displayName` | R2-014 |
| `packages/permissions/src/canonical.ts:109`, `features/admin/adminModel.ts:41` | an **absent** permission list means "allow" (fail-open display) | R2-013 |

The guard `tests/builder/permissions.test.ts:123` scans `features/studio`, `packages/{permissions,auth,ui}` for `role === "VIEWER"|"EDITOR"|…` only; it does not include `features/admin`, `TENANT_ADMIN`, or `systemAdmin`, which is why R2-004 was not caught.

## 4. Sensitive data rendering and secret masking

| Secret | Path | Evidence | Verdict |
|---|---|---|---|
| Data-source credentials | `features/studio/builder/DataSourcesPanel.tsx` | password inputs (`autoComplete="new-password"`); `create()` copies the secret into a local `body` and calls `setForm(...credential: {})` **before** the request (line 97); `replaceCredential` does the same with `setCredInput` (line 104); the API only returns `CredentialMetadata` (key names); errors carry fixed text (`api.ts:34`) | **Good pattern**, matches PERMISSION_CONTRACT §2 |
| AI provider API key (closest thing to BYOK; the `byok` nav entry is a "not enabled" placeholder, `AdminApp.tsx:33`) | `features/admin/AiSetup.tsx:102-157` | `type="password"`, never read back (`keySet` boolean, placeholder `••••••••`), empty = keep; on a **failed** save the key stays in the field (acceptable); dialog unmount drops it | OK |
| Connector auth value | `features/admin/AdminApp.tsx:1141-1157` | `f.authValue` is sent in `saveConnector` and **never cleared on success** (`act()` at 1144 only reloads) | R2-005 |
| Server-app secrets | `features/studio/CodePanels.tsx:140-143` | write-only password input; value cleared in `.then(...)` after `act()` resolves, but `act()` swallows errors, so it is also cleared on failure and held for the whole request | R2-006 |
| IDE clone token | `features/studio/CodePanels.tsx:96-106` | `a.token` rendered in clear in a `<code>` (line 104) and again inside `git clone https://user:TOKEN@host/…` (lines 98,105); no mask, no copy button, no timeout; if `token` is `null` (the type allows it, `types/src/index.ts:226`) the command contains the text `null` | R2-002 |
| Activation / reset link | `features/admin/UserDialogs.tsx:13-32` | read-only input, "shown once", state dropped when `LinkBox` closes in `AdminApp.tsx:307`; in `ProvisioningScreens.tsx:35,121` the whole `result.activation` (token) stays in the dialog state after `LinkBox` closes, until the dialog unmounts; hosts discard it (`onCreated={() => …}`) | OK / R2-006 |
| Login / activation passwords | `AuthPages.tsx:34-87,113-116` | cleared on error (`setPassword("")`, line 87); kept in state after success until navigation; `again` is never cleared | R2-006 |
| Logs shown in runtime drawer | `CodePanels.tsx:149` | server-redacted ("đã che thông tin nhạy cảm"), rendered in `<pre>` text | OK *(inference: redaction is the server's)* |

No secret is written to `localStorage`, `sessionStorage`, the URL, `console`, or an error message by portal code.

## 5. Credentials / internal URLs in the client bundle

* Source scan: no key-like strings, no private IPs, no dev passwords. Only `NEXT_PUBLIC_*` in use: `NEXT_PUBLIC_API_MODE` (legacy root app; `app/layout.tsx:16`, `lib/api-client.ts:4`, `proxy.ts:7`) and `NEXT_PUBLIC_PORTAL_URL_{PLATFORM,ADMIN,STUDIO}` (public origins, `packages/permissions/src/index.ts:73-75`). All other env reads (`API_PROXY_TARGET`, `MINIO_*`, `SITES_ORIGIN`, `CSP_EXTRA_CONNECT_ORIGINS`, `STUDIO_*`) are in server-only modules (`nextConfig.ts`, `csp.ts`).
* Built Studio bundle: URLs present are only W3C namespaces, react.dev/nextjs.org error links and the core-js licence; `localhost` appears once (the loopback regex of `runtimeConfig.ts`). No source maps are emitted.
* The admin route inventory (`features/admin/provisioning.ts:19-25` lists `POST /api/v1/admin/tenants/{tenantId}/users` etc.) is part of the Admin app bundle, which is served to unauthenticated visitors of `/login` too (one catch-all client entry per app): R2-016 *(inference: not a secret, endpoints are server-guarded)*.

## 6. Browser storage

| Key | Where | Content | Note |
|---|---|---|---|
| `localStorage studio-ws` | `StudioApp.tsx:31,34` | last workspace id; validated against `me.workspaces` before use | not cleared on logout, not user-scoped (R2-011) |
| `localStorage studio-ai-model` | `ProjectWorkspace.tsx:83,115`, `CodeWorkspace.tsx:65,76` | chosen AI model id | same |
| `sessionStorage studio-lastmode…` | `ProjectWorkspace.tsx:49,51` | last editor mode | tab-scoped, harmless |
| `sessionStorage factory-portal` | `packages/permissions/src/index.ts:99-101` | `"admin" \| "builder"` (navigation intent only; value is whitelisted on read) | harmless |

All accesses are in try/catch. No cookies are written from JS. IndexedDB unused.

## 7. Error messages / stack traces

* No `error.stack`, no component stack, no `JSON.stringify(error)` is rendered anywhere. Next's production build does not expose stacks.
* `errText()` (`packages/ui/src/ui.tsx:21`, duplicated at `features/studio/drawers.tsx:16`) shows `ApiError.message` + `(mã <requestId>)` — requestId is intended; the server body is uniform (`server.error.include-*: never`, `docs/SECURITY.md`). For anything that is **not** an `ApiError` it shows `e.message` verbatim (R2-007): `response.json()` on a 200 with a non-JSON body (`core.ts:77`) and `JSON.parse(data)` of a malformed SSE event (`core.ts:114`) surface text such as `Unexpected token '<', "<!DOCTYPE "… is not valid JSON` (reveals a proxy/HTML error page).
* The Builder maps codes to curated text (`features/studio/builder/core/errors.ts`); its fallbacks use `x?.message` (lines 39-79, 89), i.e. the server's message.
* No `error.tsx`, `global-error.tsx` or `not-found.tsx` exists in `apps/*/app` (R2-015): a render exception shows Next's generic client-error screen (no internals), but with no recovery path.

## 8. Unsafe sinks — every occurrence reviewed

| Sink | Occurrences | Disposition |
|---|---|---|
| `dangerouslySetInnerHTML`, `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write` | 0 | — |
| `eval`, `new Function`, string `setTimeout/Interval` | 0 | — |
| `<iframe srcDoc>` | 4: `features/library.tsx:12` (`sandbox=""`), `features/studio/ProjectWorkspace.tsx:368` (`sandbox=""`), `components/StudioShell.tsx:215` (legacy, `sandbox=""`), `features/studio/builder/Canvas.tsx:41` (`sandbox={interactive ? "allow-scripts" : ""}`) | section 9 |
| `<iframe src>` | 1: `CodeWorkspace.tsx:220` `sandbox="allow-scripts"` (opaque origin; `frame-src` limited to `'self' about: $SITES_ORIGIN`) | OK; URL not validated, R2-008 |
| `window.open` | 0 | — |
| `window.location.assign` | 2: `session.tsx:39` (IdP logout URL, `^https?://` checked in `api.ts:63`), `StudioApp.tsx:378` (R2-003) | |
| `target="_blank"` | 5, all `rel="noopener noreferrer"` (`drawers.tsx:94`, `CodeWorkspace.tsx:221`, `ReleaseModal.tsx:170,202`; `schema-preview.ts:48` inside the preview) | OK |
| `postMessage` / `message` listener | 1 listener (`Canvas.tsx:23-34`), 2 sends from the injected script (`schema-preview.ts:114`) | section 9 |
| `router.push/replace` with user data | `StudioApp.tsx:78` (`q` URI-encoded, in-app path), `:117` (prompt in query, R2-001), `session.tsx:31`/`PortalApp.tsx:44` (`next`, encoded; re-validated by `safeNext` on use) | OK except R2-001 |
| `href={…}` with server-supplied URL | `AuthPages.tsx:107-108` (`oidcLoginUrl`, `samlLoginUrl`), `drawers.tsx:93-94` (`downloadUrl`), `ReleaseModal.tsx:170,202` (`site.url`, `deployment.url`), `CodeWorkspace.tsx:220-221` (`previewUrl`) | R2-008. Verified: React 19.2 turns a `javascript:` href/src into a throwing URL (`node_modules/react-dom/cjs/react-dom-client.production.js:1414`) |
| `fetch()` to a server-supplied URL | `api.ts:323` presigned upload; `connect-src` allows only `'self'` + the MinIO origin; request carries no cookie and no CSRF header (signature auth) | OK |

## 9. Studio canvas iframe

* **Sandbox:** AI / Test / thumbnails use `sandbox=""` (no script, no same-origin, no forms, no popups, no top navigation). Edit mode uses `allow-scripts` only — never `allow-same-origin` — so the document has an opaque origin and cannot read the portal's cookies, storage or DOM. Verified in `Canvas.tsx:41`.
* **srcdoc escaping:** `lib/schema-preview.ts` builds the document from the Page Schema. All 96 interpolations were classified: text and attributes go through `escapeHtml` (`& < > " '`, double-quoted attributes only); anchors accept only `^#[\w-]*$` (`href`, line 8) or `https://` without `"'<>\\` and whitespace (`navLink`, line 48); images only `https?://` signed URLs or `assets/<uuid>.<ext>` (`img`, line 41-43); the star count is clamped numeric; `data-sid`, `nonce`, `_forms/<id>` are escaped. No raw interpolation was found. `pg.slug` is used unescaped only as a **file path key** in `renderSitePages` (line 130; consumed by the render worker): slug validity is the server validator's job (handoff to C2).
* **Message validation (receiver, `Canvas.tsx:23-34`):** `e.source === frameRef.current.contentWindow` (the right check for an opaque-origin sender whose `origin` is `"null"`), `type` allow-list (`studio:select`, `studio:layout`), `sectionId` must be a string present in the current section set, `rects` filtered by `validRects` (id known, finite `top`/`height`). No `origin` comparison (not meaningful here). Array length and magnitude are unbounded but only the user's own tab is affected.
* **Sender (`schema-preview.ts:114`):** `parent.postMessage(…,"*")`. Parent embedding is blocked (`frame-ancestors 'none'`, `X-Frame-Options: DENY`), so the wildcard leaks nothing in practice.
* **CSP interplay (R2-009):** the page's per-request nonce is copied into the srcdoc `<script nonce>` (`ProjectWorkspace.tsx:43,258`) because srcdoc inherits the policy (`'strict-dynamic'`, no `unsafe-inline`). Combined with `style-src 'unsafe-inline'` (needed for the same srcdoc) the policy is as strong as the renderer's escaping. Defence-in-depth: authorise the constant script by `sha256-…` in the policy instead of handing out the nonce, and put the portal origin in the `postMessage` target.

## 10. URL building, open redirect, `next`

* `safeNext` (`packages/permissions/src/index.ts:105-110`): accepts only a path that starts with `/`, not `//`, no `\`, not an auth page, and whose first segment is one of `platform|admin|studio`. Every login path (`LoginPage`, `SigningIn`, `SessionExpired`, `resolvePortalPostLogin`) calls it. A `next` such as `//evil.com`, `/\evil.com`, `/studio/..//evil.com` or `https://…` is rejected or stays same-origin. **No open redirect through `next`.**
* `resolvePortalPostLogin` additionally pins the destination to the current portal prefix.
* Query values placed in client navigation are encoded (`qs()` and `encodeURIComponent` throughout; `seg()` = `encodeURIComponent` for ids in API paths, `api.ts:15`). A few paths interpolate ids without `seg()` (`${P(w,p)}/domains/${id}`, `/admin/users/${id}`): ids come from server lists, not from the URL bar.
* `StudioApp.tsx:372-382` (`/studio/site-access?site=&path=`): `site` is regex-checked, **`path` is not**, and `r.redirect` from the API is passed to `window.location.assign` without checking scheme or host (R2-003).
* Prompt in URL: `StudioApp.tsx:117` → `ProjectWorkspace.tsx:173-178` auto-sends it (R2-001).

## 11. CSRF

* Every mutating request goes through `call()`/`stream()` and carries `X-XSRF-TOKEN`; the only mutating requests that omit it are (a) the presigned object-storage `PUT` (`api.ts:323`, cross-origin, signature auth, no cookie) and (b) none else (`fetch` inventory above). `GET`s are side-effect free in the client (`formExportUrl` is a download link). `Idempotency-Key` is separate and unrelated to CSRF.
* SameSite=Lax cookies are sent on cross-site top-level navigations, which is exactly what R2-001 abuses: the app itself turns a GET navigation into a POST that carries the CSRF header. The backend CSRF defence is intact; the hole is in the page's own "act on load" behaviour.
* `stream()` (AI SSE) does not retry after `CSRF_INVALID` (`core.ts:96`), unlike `call()` (R2-012).

## 12. Tenant selector exposure

* Studio sends only `workspaceId` (path), validated against `me.workspaces`; no body, query or header carries a tenant id (`api.ts:256` documents it). Matches `docs/contracts/v2/tenant-permission.md §2` ("clients never send a tenant id").
* Admin/Platform address tenants by **path** (`/admin/tenants/{id}/…`), the documented `forTenant` surface; the Admin-portal caller's tenant is shown, not typed (`ProvisioningScreens.tsx:5`). The set of tenants offered to a non-platform admin is computed client-side from a role string (R2-004), the server answers 404/403 for any other id.
* *(inference)* `studio-ws` lets a user with workspaces in several tenants switch context in one session; isolation relies on the server resolving the tenant from the workspace in the path, which is the contract.

## 13. Headers, CSP, clickjacking

Source: `packages/auth/src/server/nextConfig.ts:35-43` (headers), `packages/auth/src/server/csp.ts` (per-request CSP), `apps/*/proxy.ts`.

| Control | Value | Note |
|---|---|---|
| CSP | `default-src 'self'`; `script-src 'self' 'nonce-…' 'strict-dynamic'` (+`'unsafe-eval'` only in `next dev`); `style-src 'self' 'unsafe-inline'`; `img-src 'self' data: blob: <MinIO>`; `connect-src 'self' <MinIO> <extra>`; `font-src 'self'`; `frame-src 'self' about: <SITES_ORIGIN>`; `object-src 'none'`; `base-uri 'self'`; `form-action 'self'`; `frame-ancestors 'none'` | strong; `'unsafe-inline'` styles are required by the srcdoc preview (documented, `csp.ts:5`); no `report-to` |
| `X-Frame-Options` | `DENY` | OK |
| `X-Content-Type-Options` | `nosniff` | OK |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | OK (prompt in query would not leak cross-origin) |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=()` | could add `payment, usb, clipboard-read` |
| HSTS | only when env `STUDIO_HSTS=true` (`csp.ts:41`), default **off** | R2-010 |
| `X-Powered-By` | removed (`poweredByHeader:false`) | OK |
| COOP / CORP / COEP | absent | R2-010 (low) |
| Mock/static build (`NEXT_PUBLIC_API_MODE=mock`, GitHub Pages) | no headers, no CSP (`proxy.ts` is inert) | R2-010; documented in `proxy.ts:3-5`, not a portal |
| Dev server | `next dev -H 127.0.0.1` | OK |

Live header values were **not** captured (no server was started in this phase); the above is from source.

## 14. Dependencies

`npm audit --omit=dev` → **0 vulnerabilities** (run 2026-10-08, registry reachable). Runtime stack: `next 16.3.8`, `react/react-dom 19.2.0`, `@dnd-kit/*`, `lucide-react`, `simple-icons`. Nothing upgraded. `docs/SECURITY.md` records the earlier `rollup` pin; unchanged.

## 15. Handoffs (not C5 work)

| To | Need | Why |
|---|---|---|
| C1 | resolved per-tenant capability (e.g. `TENANT_MEMBERS` / `TENANT_MANAGE` per entry of `Me.tenants[]`) so the client can stop reading `tenants[].role`; ask for project-scoped grants in `/auth/me` (H-C1-04, already open) | R2-004, R2-013 |
| C2 (sites / publish, delegated) | `POST /sites/{slug}/access-ticket`: confirm `path` is validated server-side to a same-site absolute path and `redirect` is always an `https` URL on the sites origin; confirm slug validation in `PageSchemaValidator` (`pg.slug` is a file path in `renderSitePages`) | R2-003, section 9 |
| C0 | HSTS on by default for non-local, COOP/CORP, optional CSP report endpoint, and a way to pass the sites origin / allowed external origins to the client (`next.config.ts`, `packages/auth/src/server/*` are shared files) | R2-003, R2-010 |
| C0-gated `code/**` | a server-provided `canApprove` (or `isCreator`) flag on a code change instead of comparing display names; clone-access response ideally without a credential-bearing URL (the UI composes it) | R2-002, R2-014 |
| Backend (any) | stable `code` for every refusal so the client never falls back to the server's free text | R2-007 |

## 16. Not verified

* No live response headers, cookies, or CSRF behaviour were observed (static analysis + build only; no server or browser was started).
* Admin and Platform bundles were not built; their source was read, and they share every package scanned in the Studio bundle.
* Backend behaviours assumed from `docs/SECURITY.md` / contracts, not exercised: cookie attributes, `server.error.include-*`, redaction of runtime logs, validation of `path`/slug/redirect.
* Whether `crypto.randomUUID` is unavailable on plain-HTTP origins (fallback `Math.random` id in `api.ts:39`, `release.ts:80`): only used for idempotency keys, not for secrets.

## 17. Issue table

Owner legend: S1–S4 = C5 squads (C5-L maps areas to squads; area is given), R = research, NOT C5 = another team.

| ID | PORTAL | ROUTE/COMPONENT | STATE | SEVERITY | CATEGORY | EXPECTED | ACTUAL | ROOT_CAUSE (file:line) | OWNER | PROPOSED FIX | TEST | STATUS |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| R2-001 | Studio | `/studio/projects/{id}/ai?prompt=` | default | P2 | CSRF / action-on-load | opening a URL never performs an AI call or writes a version; a confidential prompt never sits in a URL | the page auto-sends `?prompt=` as soon as the project loads (editors only), creating a version through the AI; the prompt also lands in browser history and Next/proxy access logs. A crafted link (project id needed) makes a logged-in editor's browser submit attacker-chosen text (SameSite=Lax sends the cookie on top-level navigation) | `features/studio/ProjectWorkspace.tsx:173-178`; `features/studio/StudioApp.tsx:117` | S-Studio (C5) | hand the prompt over through `history.state` / a one-shot in-memory store, not the URL; if a URL prompt must be accepted, only pre-fill the composer and require a click | unit: `?prompt=x` triggers no `POST /prompt`; browser: Home → create → prompt is sent once and the URL is clean | OPEN |
| R2-002 | Studio | Code project → "Mở bằng IDE" drawer | after "Tạo token clone" | P2 | Secret handling | one-time token masked, copyable, cleared on close/timeout, not embedded in a URL that ends up in `.git/config` and shell history | token in clear twice (`<code>` + `git clone https://user:TOKEN@…`), no masking/copy/auto-clear (lives until unmount); `null` token renders the text `null` in the URL | `features/studio/CodePanels.tsx:98,103-105`; `packages/types/src/index.ts:226` | S-Studio (C5); backend shape NOT C5 (C0 `code/**`) | mask by default with reveal + copy, clear after N s / on `visibilitychange`, show the credential-free URL plus a command using a credential helper or `-c http.extraHeader=…` instead of the URL form; guard `token == null` | unit on the command builder (no `@` credential form, null handled); browser: DOM holds no token after close | OPEN |
| R2-003 | Studio | `/studio/site-access?site=&path=` | on open | P2 | Redirect / URL validation | client only follows an `https` redirect on the sites origin and only sends a same-site absolute `path` | `path` (from the query string) is sent unvalidated; `r.redirect` goes straight to `window.location.assign` (a `javascript:` value would be stopped only by CSP in http mode, not in the static mock build) *(inference for the backend half)* | `features/studio/StudioApp.tsx:372-382`; `packages/api-client/src/api.ts:355` | S-Studio (C5); server validation NOT C5 (C2 sites) | validate `path` (`^/(?!/)[^\\\s]*$`) before the call; parse `r.redirect` with `new URL`, require `https:` and the configured sites origin (needs the origin at build time: C0) | unit with `//evil.com`, `javascript:`, `http:`, other host, valid sites URL | OPEN |
| R2-004 | Admin, Platform | portal gate, Admin sections `company/organization/employees` | default | P2 | Authorization (UX) | all gating from resolved permission codes; no role-name input anywhere (PERMISSION_CONTRACT §3, `adminModel.ts` header) | `tenant.members` and the tenant list are derived from `role === "TENANT_ADMIN"`; a renamed/added tenant role would silently hide or show screens; the guard test does not scan `features/admin` or `TENANT_ADMIN` | `packages/permissions/src/index.ts:46`; `features/admin/adminModel.ts:25`; `tests/builder/permissions.test.ts:123-129` | S-Shared (C5); resolved capability NOT C5 (C1) | until C1 exposes per-tenant codes, centralise the two reads in one documented function, extend the guard to `features/admin` + `TENANT_ADMIN`; then switch to the code | extend GUARD test; adminmodel unit with a `tenants[]` fixture | OPEN |
| R2-005 | Platform | Connectors page | after Lưu | P3 | Secret handling | credential input cleared the moment it is sent (as in DataSourcesPanel) | `f.authValue` stays in React state and in the DOM `value` after a successful save until navigation | `features/admin/AdminApp.tsx:1141,1144,1152,1157` | S-Admin (C5) | copy to local const, `setF(f => ({...f, authValue: ""}))` before the call | browser harness: input empty after save, secret not in DOM | OPEN |
| R2-006 | all | activation page, login, create-account dialog, runtime secrets | after submit | P3 | Secret handling | one-time tokens / passwords dropped from state when no longer needed; a failed save does not discard the user's input | activation token kept in state for the page lifetime and removed from the URL only on success; passwords (`password`, `again`) kept after success; `result.activation` kept after `LinkBox` closes; runtime secret cleared even when the save failed (`act()` swallows errors) and held during the request | `packages/auth/src/AuthPages.tsx:32,34,41,87`; `features/admin/ProvisioningScreens.tsx:35,121`; `features/studio/CodePanels.tsx:122,140-143` | S-Shared / S-Admin / S-Studio (C5) | clear on the first send; clear the token and strip the fragment as soon as it is read; make `act()` return a success boolean | unit/browser: DOM + state empty after submit; failed runtime save keeps the typed name | OPEN |
| R2-007 | all | `errText`, SSE stream, `explainError` fallbacks | on malformed response | P3 | Error handling | users see a curated message plus requestId, never a raw JS/parser message | `errText` returns `Error.message` for non-`ApiError` (`Unexpected token '<' … is not valid JSON`); `stream()` does `JSON.parse` without guard; Builder fallbacks print the server's free text | `packages/ui/src/ui.tsx:21`; `features/studio/drawers.tsx:16`; `packages/api-client/src/core.ts:77,114`; `features/studio/builder/core/errors.ts:39-89` | S-Shared (C5); stable codes NOT C5 (backend) | wrap `response.json()`/`JSON.parse` into `ApiError(…, "BAD_RESPONSE")`; `errText` returns the fallback for non-ApiError; one `errText` (remove the copy) | unit: HTML 200 body and truncated SSE event produce the fixed message | OPEN |
| R2-008 | Studio, Auth | external links / iframe src from the API | default | P3 | URL validation | server-supplied URLs checked for scheme/host before use | `previewUrl`, `downloadUrl`, `site.url`, `deployment.url`, `oidcLoginUrl`, `samlLoginUrl` used as-is. Mitigated by React 19.2 (`javascript:` blocked), CSP `frame-src`/`img-src` and noopener; residual is an `https` phishing target if the backend were compromised | `features/studio/CodeWorkspace.tsx:220-221`; `features/studio/drawers.tsx:93-94`; `features/studio/ReleaseModal.tsx:170,202`; `packages/auth/src/AuthPages.tsx:107-108` | S-Shared (C5) | one `safeHttpUrl(u, {origins?})` helper in `@xweb/ui`, used at these 6 sites | unit table of URL schemes | OPEN |
| R2-009 | Studio | Builder canvas (Edit mode) | default | P3 | Defence in depth *(inference)* | the srcdoc script does not depend on handing the page nonce to a document that renders user content | nonce copied into the srcdoc script; `postMessage` target `"*"`; safe today because every interpolation is escaped (96 reviewed) | `lib/schema-preview.ts:114`; `features/studio/ProjectWorkspace.tsx:43,258`; `packages/auth/src/server/csp.ts:23-24` | S-Studio (C5) + C0 (CSP file) | authorise the constant script by `sha256-` in the CSP and drop the nonce from the srcdoc; target the portal origin; add a unit test that fails if a new interpolation bypasses `e()` | unit: renderer fuzz with `"'<>&` in every prop; CSP header contains the hash | OPEN |
| R2-010 | all | response headers | prod | P3 | Headers | HSTS and cross-origin isolation headers on by default outside local; mock build documented as non-portal | HSTS only if `STUDIO_HSTS=true`; no COOP/CORP/report-to; mock/static build serves no CSP or `X-Frame-Options` | `packages/auth/src/server/csp.ts:41`; `packages/auth/src/server/nextConfig.ts:35-43`; `proxy.ts:3-5` | NOT C5 (C0: shared config files) | default HSTS on unless `HBL_ENV=local`; add COOP `same-origin` and CORP `same-origin`; document or retire the GitHub Pages export | header test against a started stack (live capture) | OPEN |
| R2-011 | Studio | workspace switcher, AI model | after logout/login | P3 | Storage | per-user UI preferences cleared on logout or keyed by user id | `studio-ws` and `studio-ai-model` survive logout and are shared by the next user of the browser; not secret, validated against `me.workspaces` | `features/studio/StudioApp.tsx:31,34`; `features/studio/ProjectWorkspace.tsx:83,115`; `features/studio/CodeWorkspace.tsx:65,76`; `packages/auth/src/session.tsx:37-40` | S-Shared (C5) | key by user id or clear these keys in `logout()` and the 401 handler | unit: keys removed after logout | OPEN |
| R2-012 | Studio | AI streaming | after session rotation | P3 | CSRF handling | same retry rule as `call()` | `stream()` resets the token on `CSRF_INVALID` but surfaces the failure instead of retrying once | `packages/api-client/src/core.ts:96` | S-Shared (C5) | share the retry path with `call()` | unit with a stubbed 403 `CSRF_INVALID` | OPEN |
| R2-013 | all | portal gate, workspace member panel, auth config | older/failed backend | P3 | Fail-open display | absent data denies, or the screen says "unknown" | absent `permissions` list → treated as allowed; a failed `/auth/config` shows the local-password form (`localLogin:true`) even if the deployment is OIDC-only. Server still refuses; UX/phishing-surface only | `packages/permissions/src/canonical.ts:109`; `features/admin/adminModel.ts:41`; `packages/auth/src/AuthPages.tsx:73` | S-Shared (C5); backend always sends the list: NOT C5 (C1) | after H-C1-04 closes make absence = deny; show an error state instead of defaulting auth config | unit | OPEN |
| R2-014 | Studio | code change approval | review-required change | P3 | Authorization (UX) | decided from a server flag, not a display name | "Duyệt" hidden when `createdBy === me.displayName`; display names are not identities (two people can share one; creator rule is only enforced if the server enforces it) | `features/studio/CodeWorkspace.tsx:211` | S-Studio (C5); flag NOT C5 (C0 `code/**`) | use a server-provided `canApprove`; confirm the server enforces four-eyes | unit | OPEN |
| R2-015 | all | `apps/*/app` | render exception | P3 | Resilience | an error boundary with a safe message, request id and a way back; custom 404 | none of `error.tsx`, `global-error.tsx`, `not-found.tsx` exist; a thrown render error ends in Next's generic screen | `apps/studio/app`, `apps/admin/app`, `apps/platform/app` (only `layout`, `entry`, `[[...slug]]`) | S-Shared (C5) | add `error.tsx` / `global-error.tsx` / `not-found.tsx` per app via the shared package (no stack, show nothing from the error) | browser: forced throw shows the safe screen | OPEN |
| R2-016 | all | login page | unauthenticated | P3 | Information exposure *(inference)* | the unauthenticated entry ships only login code | one catch-all client entry per app: the whole Admin/Platform/Studio UI code and the endpoint inventory (`provisioning.ts:19-25`) is downloadable before login. No secret; endpoints are server-guarded | `apps/*/app/entry.tsx`; `packages/auth/src/PortalApp.tsx:36-48`; `features/admin/provisioning.ts:19-25` | S-Shared (C5) | `next/dynamic` import of the portal screens after `me` resolves | bundle test: login chunk excludes admin code | OPEN |

## 18. Checked and found clean

CSRF header on every mutating call · no secret/PII in `console`, URL, storage · no HTML sinks · iframe sandbox without `allow-same-origin` · `safeNext` open-redirect proof · all `_blank` links `noopener noreferrer` · no credentials or internal hosts in source or bundle · no source maps · npm audit 0 · data-source and AI-provider credential forms write-only and cleared · tenant never sent by Studio · `rel`, `X-Frame-Options`, `frame-ancestors`, `nosniff`, `Referrer-Policy` in place · activation token carried in the URL fragment.
