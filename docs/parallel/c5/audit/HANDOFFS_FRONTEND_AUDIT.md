# C5 frontend audit — handoffs to other teams (ready to send)

From: C5 (R2 packaging, Phase 2) · Base: `agent/c5-web @ a73ae3d` · Date: 2026-10-09 · Ledger: `docs/parallel/c5/audit/MASTER_ISSUE_LEDGER.md`
Status of every item: **OPEN, nothing sent**. C5-L sends the sections as they are.

## How to read this

* **Evidence labels.** `HARNESS` = observed in a C5 browser harness with a faked API (not a real backend). `STATIC` = read in the frontend source. `STATIC-BE` = read in the backend source in this repository (read-only; **not run**, not compiled). `REAL` = observed on a real running backend. **No item below has REAL evidence unless it says so**; `H-C1-04` (existing) is the only REAL one referenced.
* **C5 will NOT meanwhile**: never invent a route, a field, a status code, a permission code or a semantic. Where the backend source already answers a question, the answer is quoted so the owner only has to confirm or correct it.
* **Blocking** = C5 cannot finish its own fix / a test cannot pass without the answer. **Non-blocking** = C5 proceeds with its own part and only the backend-dependent capability waits.
* IDs are `HF-<team>-NN` (new, to avoid clashing with the existing `H-C*` ids in `HANDOFF_INDEX.md`); existing ids are cross-referenced.

---

## HANDOFF_C1 — identity / tenancy / organization / sharing / member

| ID | Ledger | Type |
|---|---|---|
| HF-C1-01 tenant-admin activation-link re-issue | M-007 | request |
| HF-C1-02 admin-scoped application delete / restore | M-009 | request |
| HF-C1-03 tenant fields and filter on `/admin/users` | M-058 | request |
| HF-C1-04 workspace list route per tenant | M-058 | request |
| HF-C1-05 per-tenant capability codes, always-present permission lists, project-scoped grants | M-052, M-093 (+ existing H-C1-04, H-C1-05) | request |
| HF-C1-06 locale / time zone / tenant branding data | M-071, M-103 | question |
| HF-C1-07 password policy 6 vs 8 | M-064 | question |

**HF-C1-01 — Tenant admin cannot re-issue a lost activation link** · M-007 (S2-001, S3-033) · non-blocking for C5's own part
* Observed: the only re-issue route is `POST /api/v1/admin/users/{id}/activation-link`, guarded by `AdminGuard` (system admin): `identity/Accounts.kt:223-224` `[STATIC-BE]`. A tenant admin's account creation `POST /api/v1/admin/tenants/{tenantId}/users` returns the link once: `tenancy/TenantController.kt:88` `[STATIC-BE]`; the Admin portal only offers reset on the Platform user page (`features/admin/AdminApp.tsx:278-282`, `newLink()` on the user page) `[STATIC]`; the link modal discards it on Enter/Esc/"Xong" (`Modal.tsx:16`, `UserDialogs.tsx:13-27`) `[HARNESS]`.
* Request: is there, or will there be, a tenant-scoped way for a TENANT_ADMIN to re-issue the activation link of a **pending** user of their own tenant (and under which permission code)? If not intended, say so and C5 will state it in the UI.
* C5 will NOT: call `/admin/users/{id}/activation-link` from the Admin portal; guess a tenant route; hold the one-time link longer than the user needs it. C5 does its own part now (focus the copy control, confirm before discarding until copied).

**HF-C1-02 — SYSTEM_ADMIN is offered "Xóa" / "Khôi phục vN" on routes that refuse them** · M-009 (S2-003) · non-blocking
* Observed: `AdminApp.tsx:405,429` call `api.deleteProject` / `api.restoreVersion` on workspace-scoped routes `[STATIC]`; `docs/parallel/c5/audit/S2-platform-admin.md` S2-003 reads `AccessService.kt:93-122` and expects a 404 `PROJECT_NOT_FOUND` for a non-member platform admin with `app.tenancy.system-admin-business-access=false` (D-C1-13A). **Not confirmed on a real backend.**
* Request: confirm the 404, and say whether an `/admin/**` delete and restore-version route exist or are planned for the Platform console (archive / restore / transfer already use `/admin/**`).
* C5 will NOT: invent an `/admin/applications/{id}` route. Meanwhile C5 keeps the controls but, for a user whose documented `Me.businessAccess` is `false`, explains instead of offering (the field is in the contract mirror, `packages/types/src/index.ts:21`), and maps the 404 to a plain message. It does not hide by guessing.

**HF-C1-03 — `/admin/users` has no tenant** · M-058 (S2-022) · non-blocking
* Observed: `AdminUserRow` has id, username, displayName, email, enabled, systemAdmin, authSource, createdAt, workspaces, projects, lastLoginAt, pending, departmentId — no tenant fields, and the list has no tenant filter: `admin/AdminUserController.kt:14-18,36-44` `[STATIC-BE]`. The UI list shows no company (`AdminApp.tsx:256-265`) `[STATIC]`, with 63 company rows seen without search/pager `[HARNESS]`.
* Request: can `/admin/users` expose the user's tenant memberships (id, name, role) and accept a `tenantId` filter? Or is the tenant members route (`GET /admin/tenants/{t}/members`, `TenantController.kt:104`) the intended path for per-company views?
* C5 will NOT: derive a company column from other calls per row (N+1) or invent a filter parameter. C5 adds client search/paging for the tenants list and the loaded page only, labelled as such.

**HF-C1-04 — No route lists the workspaces of one tenant** · M-058 (S2-023, existing H-C1-16) · non-blocking
* Observed: `TenantController` exposes `POST /{tenantId}/workspaces` but no `GET` for workspaces (`tenancy/TenantController.kt:45-137`) `[STATIC-BE]`; `ProvisioningLive.tsx:21-26` offers the first page (25) of **all** tenants' workspaces, so a workspace beyond page 1 cannot be chosen and a wrong-tenant pick is refused later `[STATIC]`.
* Request: `GET /admin/tenants/{tenantId}/workspaces` (paged, searchable by name), or tell C5 the intended source.
* C5 will NOT: filter client-side on `tenantId` of an unpaged global list, nor guess the route.

**HF-C1-05 — Capability codes the portals can read** · M-052, M-093 (R2-004, R2-013, S2-036); relates to open `H-C1-04` (REAL, E2E-04 case A / USER01 BLOCKED) and `H-C1-05` · blocking for M-093 only
* Observed: the Admin gate derives the tenant list and `tenant.members` from `role === "TENANT_ADMIN"` (`packages/permissions/src/index.ts:46`, `features/admin/adminModel.ts:25`) `[STATIC]`; `/auth/me` carries the canonical permissions of the **primary** tenant only (`adminModel.ts:22`); an absent `workspaces[].permissions` is treated as allowed (`canonical.ts:109`, `adminModel.ts:41`) because older backends omit it `[STATIC]`; project-only members get `permissions: []` (H-C1-04, `[REAL]`, still open at 2356d64).
* Request: (a) per entry of `Me.tenants[]`, the canonical codes the person holds in that tenant (`TENANT_MEMBERS`, `TENANT_MANAGE`), so C5 can stop reading the role name; (b) a guarantee that `Me.workspaces[].permissions` is always present (so C5 can treat absence as "deny"); (c) the decision on project-scoped grants in `/auth/me` (H-C1-04).
* C5 will NOT: inject `APP_VIEW`, loosen a gate, or flip "absent = allow" to "absent = deny" before (b) is confirmed on a real stack (that would lock out users of an older backend). C5 centralises the single role-name read in one documented helper and extends the guard test.

**HF-C1-06 — Locale, time zone and branding data** · M-071, M-103 · non-blocking (question)
* Observed: `Me` (`packages/types/src/index.ts:16-22`) and `TenantView` (`:107`: id, slug, name, status, createdAt) carry no locale, time zone, logo, accent or product name `[STATIC]`.
* Question: will `/auth/me` or a profile endpoint carry `locale` and `timeZone`, and will tenants carry branding data (name / logo / accent)? C5 will consume whatever C1 decides; until then the locale lives in a browser cookie and the brand is a constant.
* C5 will NOT: invent a theme or profile contract, or store a preference the server cannot return.

**HF-C1-07 — Password policy differs between sign-up and activation** · M-064 (S3-030) · non-blocking (question)
* Observed: sign-up `RegisterRequest.password` min 6 (`identity/RegistrationController.kt:24`, comment at `:33` "at least 6 characters with both letters and digits … product decision") vs activation/reset `PasswordPolicy.check` min 8, no username inside, ≥4 distinct characters (`identity/Accounts.kt:28-35`, comment "minimum 8 characters for accounts activated by link") `[STATIC-BE]`. The UI copy follows each flow (`packages/auth/src/AuthPages.tsx:52` "8", `:115` "6") `[STATIC]`; the "no username" and "≥4 distinct characters" rules are not shown.
* Question: confirm the two minimums are intended, and whether the UI should also state the username and repetition rules.
* C5 will NOT: unify the numbers or relax a rule in the UI.

---

## HANDOFF_C2 — project / schema / version / component / template / ai / prompt / asset (+ renderer, publish/sites)

These are **questions**, not instructions. Where the backend source already answers, the answer is quoted for confirmation.

| ID | Ledger | Question |
|---|---|---|
| HF-C2-01 | M-039 (S1-009) | PATCH semantics for omitted / null / empty fields |
| HF-C2-02 | M-040 (S1-036) | does `GET /ai/status?workspaceId=` change the answer |
| HF-C2-03 | M-051 (R2-003) | access-ticket `path` / `redirect` validation |
| HF-C2-04 | M-042 (R2-029) | applying `AppDefinition.theme` in the renderer |
| HF-C2-05 | M-071 (R2-018) | site language |
| HF-C2-06 | R2 (renderSitePages) | slug as a file-path key |

**HF-C2-01 — What does `PATCH /projects/{p}` do with an omitted, null or empty value?** · M-039 · non-blocking
* Observed: Settings omits empty fields from the body (`features/studio/drawers.tsx:49-51`) so a cleared "Tên miền xem trước", "Tên miền riêng" or "Đích triển khai" keeps its old value `[HARNESS]`.
* Backend source: `UpdateProjectRequest` (`project/ProjectController.kt:34-44`) applies every field with `?.let` (null = unchanged); `description` and `deploymentTarget` are `trim().ifEmpty { null }` (an empty string clears them); `domain` and `customDomain` are validated by `HOSTNAME` (`:20`, one or more characters), so `""` is a 400 and **neither can be cleared** `[STATIC-BE]`.
* Question: is "domains cannot be cleared through PATCH" intended? If clearing is wanted, which form does the API accept (explicit `null`, a dedicated field, another route)?
* C5 will NOT: send `null` or `""` for `domain` / `customDomain`, or guess. It may send `""` for `description` / `deploymentTarget` only after C2 confirms the above reading.

**HF-C2-02 — Is a workspace-less `GET /ai/status` complete?** · M-040 · non-blocking
* Observed: the effect runs with `ws === ""` so the request carries no `workspaceId` and is never refreshed (`ProjectWorkspace.tsx:112,85`) `[HARNESS]`.
* Backend source: `AiController.status` takes an optional `workspaceId`; the comment says "with a workspace, the list is narrowed by the model access rules that apply to the caller there" (`ai/AiController.kt:23-27`) `[STATIC-BE]`.
* Question: confirm that without `workspaceId` the picker may list models the workspace's rules forbid, and whether any other field of `AiStatus` (limits, budget, `configured`) is workspace-dependent.
* C5 will NOT: change the UI until confirmed. Once confirmed, it sends the existing optional parameter after the project (and so the workspace) is known.

**HF-C2-03 — Confirm the access-ticket hardening is complete** · M-051 · non-blocking, information
* Observed: `/studio/site-access?site=&path=` forwards `path` and passes `r.redirect` to `window.location.assign` without a client check (`features/studio/StudioApp.tsx:372-382`) `[STATIC]`.
* Backend source: `POST /sites/{slug}/access-ticket` builds the redirect from the configured `sites.sitesOrigin` (`publish/SiteControllers.kt:292-299`); `SiteService.safePath` returns `/` unless the path starts with a single `/`, has no `\`, no control characters, no `..`, ≤512 chars (`publish/SiteService.kt:173-181`), and re-applies it on redeem (`:186-191`) `[STATIC-BE]`. This closes the server half of R2-003.
* Question: (1) is `sitesOrigin` required to be `https` outside local profiles? (2) does `GET /sites/_access` (`SiteControllers.kt:64`) apply the same path rule on the sites host?
* C5 will NOT: depend on the answer. It adds a client-side guard (https, no credentials, valid `path` before the call) as defence in depth.

**HF-C2-04 — Should the renderer apply `AppDefinition.theme`?** · M-042 · non-blocking
* Observed: the Builder's Giao diện panel saves `theme.colors / fontFamily / radius` through `UPDATE_THEME` (`features/studio/builder/panels/MiscPanels.tsx:35-45`), but neither `lib/schema-preview.ts` nor `workers/render/*` reads `theme` (no `doc.theme` anywhere else) `[STATIC]`, so nothing changes in the preview or the published site. The contract (`docs/contracts/v2/app-definition.md`, `packages/types/src/contract/v2/appDefinition.ts:139-144`) fixes the shape (colours `#RRGGBB`, font and radius enums) but not the **meaning of the colour keys**.
* Question: what is the colour-key vocabulary, and should preview **and** published output apply the theme? `lib/schema-preview.ts` is a C5 hot file (OWNERSHIP §4) and `workers/render` is the C0-gated render plane; who implements it?
* C5 will NOT: map colour keys to CSS before the vocabulary exists; the panel stays as is and the UI says what the preview does not show.

**HF-C2-05 — Will the Page Schema carry a site language?** · M-071 · non-blocking
* Observed: the preview and published documents hard-code `<html lang="vi">` and Vietnamese fragments ("Nhận tư vấn", "Họ tên", "Gửi", "Website preview") `[STATIC]` (`lib/schema-preview.ts:61,70-80,103`, `lib/preview-document.ts:38`).
* Question: is a `site.lang` (or similar) planned? C5 will not add a field; the site language is a content property and must not follow the portal's UI locale.

**HF-C2-06 — Slug as a file-path key (answered by source, confirm)** · R2 section 9 · information
* Observed: `renderSitePages` writes `files[`${pg.slug}/index.html`]` with the slug unescaped (`lib/schema-preview.ts:130`) `[STATIC]`.
* Backend source: `PageSchemaValidator` accepts only `^[a-z0-9]+(-[a-z0-9]+)*$`, ≤60 characters, not in the reserved set (`assets api sites _app _preview _access _forms __factory 404 index home static`), unique (`schema/PageSchemaValidator.kt:16-17,64-67`) `[STATIC-BE]`. A slug cannot contain `/`, `..` or start with `_`, so the concern raised in R2 is **closed by source**. Question: confirm the render worker only renders schemas that passed this validator (no import path that skips it).

---

## HANDOFF_C3 — data platform (datasource / discovery / query / mapping / gateway)

**None found by this audit.** How this was checked: (1) the master ledger (103 rows) was searched for C3 / data-gateway dependencies: only M-005 mentions C3 and states "C3 data contract unchanged (UI flow only)"; (2) the S1, S2, S3, R and R2 audit documents were searched for `C3`/`NOT C5` rows touching data sources (S1: 5 mentions, S2: 2, S3: 2, R: 0), none is a new request; (3) R2 reviewed the credential handling of `DataSourcesPanel` (write-only inputs, cleared before the request, `DataSourcesPanel.tsx:94-107`) and found it conforming. The previously filed items remain open and unchanged: `H-C3-01` (connectors read-only, E2E-09), `H-C3-02` (Management API doc vs controller), `H-C3-03` (no approved-query / mutation management endpoint). Nothing from this audit changes them.

## HANDOFF_C4 — action / workflow / scheduler

**None found by this audit.** How this was checked: the same ledger and audit-document search for `C4`, `workflow`, `action runtime`; the Builder's Test panel conjunctions and workflow editor were reviewed by R2 against `PERMISSION_CONTRACT.md` (no role names, one call per control) and no new request arose. Open from earlier phases and unchanged: `H-C4-01` (no RabbitMQ / in-memory run stores) and the C4 `F-1` creator-scope question already recorded in `PERMISSION_CONTRACT.md §2` (the UI offers nothing based on "I created it").

---

## HANDOFF_C0 — integrator / shared files / scripts / package.json / CI

| ID | Ledger | Request | Blocking |
|---|---|---|---|
| HF-C0-01 | M-090 | HSTS, COOP, CORP defaults | non-blocking |
| HF-C0-02 | M-089 | CSP hash for the canvas script | non-blocking |
| HF-C0-03 | M-073 | `scripts/portals.sh` kills a pid without identity validation | non-blocking |
| HF-C0-04 | M-102 | `test:unit` deletes `.test-build` | non-blocking |
| HF-C0-05 | M-072/M-073 | ESLint / Prettier | non-blocking |
| HF-C0-06 | M-072 | esbuild as a devDependency | non-blocking |
| HF-C0-07 | M-073 | stale README / ARCHITECTURE / LOCAL_DEVELOPMENT | non-blocking |
| HF-C0-08 | M-094 | `canApprove` on a code change | non-blocking |

**HF-C0-01 — Security header defaults** · M-090 (R2-010) · non-blocking
* Observed: HSTS only when `STUDIO_HSTS=true` (`packages/auth/src/server/csp.ts:41`); headers in `packages/auth/src/server/nextConfig.ts:35-43` are `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Permissions-Policy` (3 features); no COOP / CORP / CSP report endpoint; the mock static export serves no CSP or `X-Frame-Options` (`proxy.ts:3-5`) `[STATIC]`. No live response was captured (no server started).
* Request: (a) where does TLS terminate in each deployment (tunnel vs Next)? If at the tunnel, who sets HSTS? (b) default HSTS on unless `HBL_ENV=local`; (c) COOP `same-origin` and CORP `same-origin` for the portals — please verify on the real stack that the OIDC full-page redirect, presigned MinIO image loads and the sites-origin preview iframe are unaffected; (d) optional CSP `report-to` endpoint.
* C5 will NOT: edit `next.config.ts` / `packages/auth/src/server/*` headers (shared files).

**HF-C0-02 — Authorise the canvas script by hash** · M-089 (R2-009) · non-blocking
* Observed: the page nonce is copied into the srcdoc `<script nonce>` (`lib/schema-preview.ts:114`, `ProjectWorkspace.tsx:43,258`); every interpolation is escaped today (96 reviewed) `[STATIC]`.
* Request: would C0 accept an optional `'sha256-…'` entry for that constant script in `csp.ts`? C5 would export the hash from a unit test so the policy cannot drift. If not wanted, C5 keeps the nonce and adds an escaping fuzz test.
* C5 will NOT: change `csp.ts`.

**HF-C0-03 — `scripts/portals.sh down` is an unvalidated kill** · M-073 (R-041) · non-blocking
* Observed: `scripts/portals.sh:66-67` sends `kill -TERM` to the pid in `<name>.launcher` with no identity check; already listed in `PROCESS_SAFETY.md` "Reported, NOT mine" `[STATIC]`.
* Request: use the owned-process pattern (`tests/lib/owned-process-cli.mjs` or C0's `scripts/lib/owned-process.mjs`, which exists on `integration/v2` only, D-C0-48).

**HF-C0-04 — `npm run test:unit` removes a running harness bundle** · M-102 (R-030) · non-blocking
* Observed: `scripts/test-unit.mjs:9` `rmSync(.test-build)` also deletes `.test-build/browser` `[STATIC]`. Request: unit output to `.test-build/unit` (`tests/tsconfig.json` `outDir`).

**HF-C0-05 — No lint / format tooling** · M-072 (R-026) · non-blocking
* Observed: no ESLint or Prettier configuration or dependency; 12 `eslint-disable-line react-hooks/exhaustive-deps` comments refer to a rule that never runs (`useLoad.ts:10`, `Modal.tsx:29`) `[STATIC]`. Request: `eslint`, `eslint-config-next`, `react-hooks` as devDependencies run from `typecheck:all`, warnings first (`package.json` / lockfile are C0-owned).

**HF-C0-06 — `esbuild` is not a dependency** · M-072 (R-028) · non-blocking
* Observed: `tests/browser/build-harness.mjs:4` needs esbuild in `/tmp/esb`, outside the repository (`tests/browser/README.md:9-11`), so the harness is not reproducible on a clean checkout `[STATIC]`. Request: root devDependency, default `ESBUILD_DIR` to `node_modules`.

**HF-C0-07 — Stale entry documents** · M-073 (R-031) · non-blocking
* Observed: root `README.md` is a one-line Pages note; `docs/ARCHITECTURE.md` describes the V1 era (no portals, "Flyway V1–V5"); `docs/LOCAL_DEVELOPMENT.md` documents only the `:3100` app and mock mode; the runbook says "serve .test-build/browser on :4000" which contradicts `PROCESS_SAFETY.md` `[STATIC]`. Request: link or update them; C5 provides `docs/FRONTEND_ONBOARDING.md` and rewords its own runbook.

**HF-C0-08 — `canApprove` for a code change** · M-094 (R2-014) · non-blocking
* Observed: the "Duyệt" button is hidden when `change.createdBy === me.displayName` (`features/studio/CodeWorkspace.tsx:211`) and `createdBy` is a display name (`CodeChange` DTO, `code/CodeChangeController.kt:31`), not an identity `[STATIC]`. The server already refuses self-review: `403 SELF_REVIEW` (`code/CodeChangeController.kt:115`) `[STATIC-BE]`.
* Request (`code/**` is C0-gated): a server flag on the change (`canApprove`, or `createdByMe`) so the UI can decide without comparing names.
* C5 will NOT: invent the flag. Interim C5 part: stop using the display-name heuristic and map `SELF_REVIEW` to a Vietnamese message.

---

## TOOLING (S4 / C5 `tests/**`, with C0 asks kept separate)

| ID | Ledger | Item | Owner | Blocking |
|---|---|---|---|---|
| HF-T-01 | M-072 (R-029) | 9 specs fall back to `http://127.0.0.1:4000/…` when `HARNESS_URL` is unset (`builder.spec.mjs:8`, `aiproviders.spec.mjs:7`, `org-hardening.spec.mjs:10`, …) `[STATIC]`. Port 4000 is on the forbidden list for agents; the README says "never a fixed port". Fix (C5, `tests/**`): refuse to run without `HARNESS_URL`, one shared `tests/browser/lib/spec.mjs` | S4 | non-blocking, do early |
| HF-T-02 | M-072 (R-029) | new harness pages need edits in `tests/browser/build-harness.mjs:16-22` (C5) **and** the `HELPERS` list in `scripts/test-classify.mjs` (C0-owned) `[STATIC]` | S4 + C0 | non-blocking: ask C0 to read the list from a manifest under `tests/` |
| HF-T-03 | M-074 (R-002) | request id is read from the JSON body only (`packages/api-client/src/core.ts:73`); the backend sets and exposes `X-Request-Id` (`common/RequestIdFilter.kt:31`, `identity/SecurityConfiguration.kt:76-77`) `[STATIC-BE]`. C5 may read the header (no invention); no reporting endpoint is requested | S3 | non-blocking |
| HF-T-04 | M-072 (R-027) | 14 main files (~3,900 lines) are reachable from no test (list in `R-architecture.md`); C5 injects `calls` while splitting | S1–S4 | n/a |

---

## Review corrections to R2's own Phase 1 text

* **R2-003 downgraded P2 → P3.** The server half was an inference; the backend source shows the redirect is built from the configured sites origin and `path` is sanitised by `safePath` (HF-C2-03). The client guard remains as defence in depth.
* **Slug concern closed** (HF-C2-06): the validator constrains the slug.
* R2-security.md §15 listed the access-ticket and slug items as C2 needs; they become information-only.

## HANDOFF_C3 — QUESTION added by C5-L (2026-10-09), from the M-005 data-binding proposal
**HF-C3-Q1 (ledger M-005, non-blocking):** when a published page binds a query whose Page Schema also lists a mapping (`mappingRef`, data-runtime.md §GatewayQuery), does the runtime APPLY the mapping (rename / transform columns) to the rows the page receives, or does it return the raw query rows? Evidence: C5 read `workers/render/page-runtime.ts:43-63` (a binding resolves to a query id directly, or via viewModelRef -> vm.queryRef, or via the VM's mapping's queryRef) and found nothing that applies a mapping; this is STATIC (not run on a real backend). C5 will NOT meanwhile promise in the UI that renaming a column works on a published page: the guided form ships v1 with 'use the source columns as returned' only. Also (nice-to-have): can C3 expose the approved operations of a source to the editor (today `operationKey` is a free-text field because discovery is NOT_READY)?
