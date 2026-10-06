# C5 overlay import manifest (for C0, checklist step 17 / 17b, ADR 0022)

Source branch `agent/c5-web`. Import base: merge-base with `integration/v2` is `a82660f`; `integration/v2` is at `4884be3` (C1–C4 imported).
**Import commit to use: the head of `agent/c5-web` at the time C0 imports (see the C5 report); `8e04d79` named in `V2_IMPORT_MANIFEST.md` §6 is outdated — Phase 2 (`edff679`, `767c31d`, `533b320`), the verification fixes (`59347e8`, `56a2524`, `21a88c7`) and the Phase 3 preparation commit come after it.**
Nothing here is applied by C5: no push, no merge, no change to `integration/v2` or to C0–C4 code.

How to list the exact files and to import by path (run on a clean `integration/v2`):

```
git diff --name-status a82660f agent/c5-web                    # everything C5 changed (143 paths at 21a88c7 + the Phase 3 prep commit)
git diff --name-only  a82660f integration/v2 | sort > /tmp/v2.paths
git diff --name-only  a82660f agent/c5-web   | sort > /tmp/c5.paths
comm -12 /tmp/v2.paths /tmp/c5.paths                           # the conflict set; expected result is in §3 (3 docs files, nothing else)
```

## 1. Files C5 owns (clean overlay: `integration/v2` has not changed any of them since `a82660f`)

| Path / glob | What | Notes |
|---|---|---|
| `apps/platform/**`, `apps/admin/**`, `apps/studio/**` | the three Next apps (each: `app/`, `next.config.ts`, `proxy.ts`, `package.json`, `tsconfig.json`) | new directories; `next-env.d.ts` and `tsconfig.tsbuildinfo` are git-ignored |
| `packages/types/**` | types + the **only** frontend mirror of the v2 contracts (`src/contract/v2/*`, header `MIRROR of docs/contracts/v2/… @ 8b944cc — manual`; `CONTRACT_SOURCE` pins `8b944cc`, first mirrored `c59604b`, verified against `4884be3`) | a change here is a contract change (ADR 0022 §3) |
| `packages/api-client/**`, `packages/auth/**`, `packages/permissions/**`, `packages/i18n/**` | fetch/CSRF/401 handling, session + login pages + Next config factory, portal gate, strings | `packages/auth/src/server/nextConfig.ts` is what writes the CSP and the `/api`, `/oauth2`, `/login/oauth2` rewrites into each app |
| `packages/ui/**` | shared UI + CSS (`app/{factory,globals,http,responsive}.css` were **renamed** into `packages/ui/src/styles/`, 100 % similarity) | the legacy `app/layout.tsx` import paths are updated (§2) |
| `features/studio/**` | Studio screens and the whole Builder (`features/studio/builder/**`, `core/**`, panels, editors, DnD) | |
| `features/admin/**` | Admin screens (3 files modified, 1 added) | |
| `tests/builder/**`, `tests/browser/**`, `tests/tsconfig.json` | unit/SSR/conformance tests; browser harness + portals specs (labelled NOT backend) | `tests/e2e-real/**` is **not** created yet (`PHASE3_E2E_PLAN.md` §5) |
| `scripts/test-unit.mjs` | `npm run test:unit` driver (tsc → commonjs `.test-build` → `node --test`) | |
| `docs/parallel/c5/**`, `docs/parallel/agents/C5_*.md`, `docs/parallel/audit/PREP-T12-builder-architecture.md` | C5 docs | |

## 2. Legacy front-end files C5 edited (shims / small edits; `integration/v2` unchanged → clean overwrite)

`app/layout.tsx` · `components/SectionInspector.tsx` · `components/useDialog.ts` · `features/{library.tsx,routing.ts,session.tsx,ui.tsx,useLoad.ts}` · `features/auth/AuthPages.tsx` · `lib/{http-api.ts,http-types.ts,schema-preview.ts}`.
`routing.ts`, `ui.tsx`, `useLoad.ts`, `session.tsx`, `AuthPages.tsx`, `http-api.ts`, `http-types.ts` are **re-export shims** of the packages (the legacy root app and `workers/render` keep building). `lib/app-definition/contract-mirror.ts` never existed on `integration/v2`; there is nothing to delete (the C0 text "delete contract-mirror.ts" is already satisfied).

## 3. Shared / C0-gated files (do **not** overwrite blindly)

| File | C5 change | `integration/v2` also changed? | What C0 does |
|---|---|---|---|
| root `package.json` | npm workspaces list (explicit: `apps/*` + 6 packages; `packages/app-sdk` and `packages/company-ui` stay outside), scripts `typecheck:all`, `typecheck:apps`, `typecheck:packages`, `build:apps`, `build:platform|admin|studio`, `dev:platform|admin|studio`, `test:unit`; root `dev`/`start` bind `-H 127.0.0.1` | no | merge by hand (step 17b commit 2) |
| `package-lock.json` | regenerated for the workspaces | no | take C5's, then `npm ci` must succeed; regenerate on the Mac if the registry resolution differs |
| `tsconfig.json` | one line: `apps` added to `exclude` (the legacy project does not typecheck the apps; each app has its own tsconfig) | no | merge by hand |
| `next.config.ts`, `proxy.ts` (root) | the legacy config / CSP proxy now come from `packages/auth/src/server/{nextConfig,csp}.ts` (same behaviour, one implementation shared with the three apps) | no | merge by hand; check the legacy mock export (`STUDIO_BASE_PATH=/system-web-studio`) and http mode still build |
| `.gitignore` | one line: `.test-build/` | no | union |
| `docs/parallel/BOARD.md`, `BLOCKERS.md`, `DECISIONS.md` | C5 rows only (BOARD: PREP-T12, PHASE 2, T12; BLOCKERS: B-C5-01…B-C5-08; DECISIONS: D-C5-01…D-C5-06) | **yes — the only expected conflict** | union by hand; keep every C0–C4 row from `integration/v2`; B-C5-05 (MeResponse tenant fields) can be marked **resolved by the C1 import** (client types now mirror it) except the Admin-gate part (Q-1) |
| `.env.example` | none by C5 | yes (C0 added `WEB_ORIGIN_*`) | nothing to merge; document the new `NEXT_PUBLIC_PORTAL_URL_*` (§4) here |
| `scripts/run-local.sh`, `scripts/_env.sh`, `backend/**/application.yml`, `identity/SecurityConfiguration.kt`, `AuthSecurityTests` | **none by C5** — requirements only (§4) | — | C0 |

Expected conflict set = the three docs files. If `comm -12` prints anything else, C0 changed a C5 path after `a82660f`: stop and read the diff.

## 4. Env / config the import needs (C5 asks, C0 applies)

| Where | Setting | Why |
|---|---|---|
| backend `application.yml` + `AuthSecurityTests` (step 17b) | CORS default becomes exactly `app.web.origins.platform/admin/studio` (dev `http://127.0.0.1:3001|3002|3003` when the browser uses 127.0.0.1) — no wildcard; one change with the test | the Next rewrite forwards the browser's `Origin`, so Spring's CORS filter judges every state-changing call even though the browser only talks to its own origin |
| each portal build | `NEXT_PUBLIC_PORTAL_URL_PLATFORM`, `_ADMIN`, `_STUDIO` (absolute origins, **build-time**; empty = same origin = legacy/single host) | cross-portal links (`portalHref`) |
| each portal run | `API_PROXY_TARGET` (default `http://127.0.0.1:8080`), `-H 127.0.0.1 -p 3001|3002|3003` | same-origin `/api`, `/oauth2`, `/login/oauth2` rewrites (the three rewrites are in `packages/auth/src/server/nextConfig.ts`, used by all three `next.config.ts`) |
| OIDC IdP | one redirect URI + one post-logout URI **per portal**, three web origins | only **one** portal can do OIDC today (single registered redirect URI; B-C0-WEB-01). Local login works on all three. Cookies are per origin: a session on :3003 is not a session on :3001 |
| `scripts/run-local.sh` | opt-in `PORTALS=1` start path (see `PHASE3_E2E_PLAN.md` §3) | the local stack currently starts only the legacy app on :3100 |
| flags for real E2E | `app.publish-configs.enabled`, `app.data-platform.enabled`, `app.workflow.enabled` ON only in the test stack, recorded per run; `app.tenancy.system-admin-business-access` stays false | `PHASE3_E2E_PLAN.md` §2 |

## 5. Backend API assumptions the web code makes (what C0/C1–C4 must keep true)

| Assumption | Source | Verified |
|---|---|---|
| `GET /api/v1/auth/csrf` → `{token}`; mutating calls send `X-XSRF-TOKEN`; 403 `CSRF_INVALID` is retried once with a fresh token | `core.ts` | against controller list only; not run |
| `GET /api/v1/auth/me` → `MeResponse` incl. `tenantId?, tenantRole?, platformScope, businessAccess, tenants[], permissions[]`, `workspaces[].tenantId/permissions[]`; permissions are the 14 canonical codes | `AuthController.kt`, `MeTenancy.kt` | read from code, not run |
| Error body `{code, message, requestId, details}`; codes and statuses of `data-runtime.md` §4b, C4 `ActionErrorCodes`, `REVISION_CONFLICT` 409, `SCHEMA_INVALID` 422 + `details.violations[{path,message}]`, `TENANT_SUSPENDED` 403, 401 outside `/auth/*` = session expired | contracts, `ActionResult.kt` | mapped in `errors.ts`, covered by `tests/builder/errors.test.ts` |
| `PATCH …/projects/{id}/schema` takes `{expectedRevision, operations[]}` with the 23 typed V2 ops (+ legacy ops), answers the new `{schema, revision, version}` | `integration-contract`, C2 | op shapes mirrored; not run |
| `GET /api/v1/component-metadata` exists (404/501 ⇒ the Builder marks definition ops NOT_READY) | C2 | probe path exists in controllers |
| All 102 distinct client calls in `packages/api-client` exist as METHOD + path in the `integration/v2` controllers (checked with a script over the Kotlin mappings) | controllers | yes (static) |
| The client **never** sends a tenant id; tenant derives from the workspace in the URL | `tenant-permission.md` §2 | grep clean |
| Not assumed to exist (UI shows NOT_READY): data query/mutation/discovery/preview/cache, action execute, workflow runs/approvals, test mode, sharing; publish-config routes only with `app.publish-configs.enabled` | `PHASE3_AUDIT.md` §1 | confirmed absent on `integration/v2` |

## 6. Import order and checks (adds to checklist 17b; C0 runs them on the Mac)

1. Commit 1 — packages + legacy shims + `tests/**` + `scripts/test-unit.mjs` (everything in §1 and §2 except `apps/**`).
2. Commit 2 — `apps/**` + root `package.json`, `package-lock.json`, `tsconfig.json`, `next.config.ts`, `proxy.ts`, `.gitignore` merged by hand.
3. `npm ci && npm run typecheck:all && npm run typecheck:apps && npm run test:unit && npm run build:apps && npm run build && NEXT_PUBLIC_API_MODE=http npm run build` and the render worker `tsc`.
4. `XWEB_CONFORMANCE_DIR=backend/src/test/resources/app-definition npm run test:unit` — must show 0 skipped conformance tests once C2 is imported.
5. Then, and only then, the browser specs (`tests/browser/README.md`), labelled `BROWSER-HARNESS` / `PORTALS-NO-BACKEND`, and last the `PHASE3-REAL-BACKEND` plan once the stack in `PHASE3_E2E_PLAN.md` §2 exists.
6. C5 reports a Phase 3 result only from step 5's last item; steps 3–5 above are not that.

## 7. What C5 does not claim

Not built on the Mac (macOS), not run against any backend, no real-backend E2E has passed, the Builder's Data / Action / Workflow / Test panels are `NOT_READY` by design until the routes exist (Q-2), and the Admin portal remains platform-only until Q-1 is answered.
