# C5 → C0 import delta (HOLD / RETEST mode, C5 HEAD `2f189dd`, 2026-10-07)

Last C5 import: `518be0f` = `agent/c5-web @ 824bdb6`. Delta: **23 commits, 232 paths (171 added, 61 modified)**. Nothing here changes the C1 permission contract; nothing needs a backend change; no new dependency (only 4 npm scripts).
`C5_OVERLAY_MANIFEST.md` still describes the FIRST import (base `a82660f`, integration `4884be3`); use this file for the second one.

## Conflict set
`git diff --name-only 518be0f integration/v2` ∩ the delta = **3 docs** that C0 edited after the first import: `docs/parallel/c5/C5_OVERLAY_MANIFEST.md`, `docs/parallel/c5/PHASE3_E2E_PLAN.md`, `tests/browser/README.md`. Take C5's version of the two docs under `docs/parallel/c5/` unless C0's edit must be kept (then re-apply it), and merge `tests/browser/README.md` by hand (C5 added sections only). No code file conflicts.
Import by path, on a clean `integration/v2`: `git checkout agent/c5-web -- <paths below>`.

## Paths (all C5-owned)
| Area | Paths | What |
|---|---|---|
| Studio | `features/studio/**` | release dialog on the C2 contract, public-data editors (slots, `QueryDef.public`, bindings, publish approval), AI progress, canonical permission gate |
| Portals | `features/admin/**` (`AdminApp`, `TenantScreens`, `adminModel`, `PageHead`, `base`), `packages/auth/src/PortalApp.tsx`, `packages/permissions/src/{index,canonical}.ts` | Platform / Admin on the tenant + member APIs; scoped console; login-landing fix |
| Shared | `packages/types/**`, `packages/api-client/**`, `packages/ui/**` | tenant types and calls, release client, stream abort/deadline, `.adminModal`, tones |
| Tests | `tests/builder/**`, `tests/browser/**`, `tests/e2e-real/**`, `scripts/{test-classify,gen-publicdata-fixtures,e2e-stability-summary}.mjs` | 256 unit tests, harness specs, real-backend flows (see matrix) |
| Tooling | `package.json` (scripts only: `test:classify`, `test:e2e:real`, `test:e2e:real:selftest`, `test:browser:build`), `e2e/*.mjs` (+2 lines each) | |
| Docs | `docs/parallel/c5/**`, `docs/user-guide/**`, `docs/C5_REAL_BACKEND_E2E_{MATRIX,RUNBOOK}.md` | |
Not C5's, not touched: backend, `docs/contracts/**`, `docs/parallel/c0..c4`, `application.yml`, migrations.

## After the import (C5 retest list; nothing else is required of C0)
1. `npm ci && npm run typecheck:all && npm run typecheck:apps && npm run test:unit` (expect 255 pass, 1 skip: conformance needs `XWEB_CONFORMANCE_DIR`), `npm run build:apps && npm run build && NEXT_PUBLIC_API_MODE=http npm run build`, `npx tsc -p workers/render/tsconfig.json --noEmit`, `npm run test:classify`, `node tests/e2e-real/selftest.mjs` (25/25).
2. Real flows: PL01, AD01, AD02 (+ P01–P09 release regression, PD02 when C2's runtime is in integration).
3. Public: only after C0 gives the three hostnames and `CORS_ALLOWED_ORIGINS` (H-C0-11).
