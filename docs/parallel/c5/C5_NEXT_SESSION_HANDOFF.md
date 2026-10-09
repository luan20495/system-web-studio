# C5 — HANDOFF (final candidate, 2026-10-10; supersedes the 2026-10-09 Wave A handoff)

Written by C5-L from what was run in the session; every result names its evidence class and is itemised in `audit/FINAL_EVIDENCE_2026-10-10.md`. Nothing here claims REAL_BACKEND.

## 1. Git
- Branch `agent/c5-web` (lead checkout `/Users/hoangluan/code/xweb-c5`), synced with `integration/v2 @ 7586d8a` (merges `6099faa`, `15bb3b7`); `agent/c5-s3-brand` merged in `56801b2`. Code head `d603488`; this docs commit sits on top. REMOTE_HEAD must equal LOCAL_HEAD before the C6 / C0 handoff (`git rev-parse HEAD origin/agent/c5-web`).
- Untracked, never commit: `.next-gate*`, `apps/*/.next-gate/`, `.tmp-stage/`, `.test-build`, `.run/`.
- Other worktrees (kept on purpose): `/Users/hoangluan/code/c5-batch2/{s1,before}`, `/Users/hoangluan/code/c5-brand/s3`, other teams' `xweb-*`, backend worktrees under `~/.xweb-e2e-stack/`. ACTIVE_AGENTS = 0.

## 2. Ledger (canonical `audit/MASTER_ISSUE_LEDGER.md`, `LEDGER_STATUS_NORMALIZED.md`)
CANONICAL_TOTAL 132: **FIXED 94 · CLOSED 3 · PARTIAL 24 · OPEN 0 · BLOCKED 8 · RESEARCH_ONLY 3 · ACCEPTED_LIMITATION 0**. Unresolved 35 = P0 0 · P1 2 (M-007, M-009: C5 part done, C1 open) · P2 17 · P3 16.
- BLOCKED (8): M-039 M-040 (C2), M-073 M-090 M-102 M-108 (C0), M-094 (backend user id), M-101 (C2 worker).
- PARTIAL remainders that need another owner: M-051 (sites origin, C0/C2), M-089 (CSP sha256 for the canvas script, C0), M-107 (the unit runner cannot load `@xweb/*`, C0: HF-C0-M107), M-114 (Firefox BLOCKED_TOOLING, user decision), M-093(b) (HF-C1-05).
- Regenerate: copy `audit/tools/ledger-gen/*` to a scratch dir, `node check.mjs && node gen.mjs && node normdoc.mjs`, copy `MASTER_ISSUE_LEDGER.md`, `ledger.json` (as `master-ledger.json`), `LEDGER_STATUS_NORMALIZED.md` back; add a `status("M-xxx", "...")` line at the end of `tools/ledger-gen/patch.mjs` and keep `tools/ledger-patch.mjs` identical.

## 3. What this run delivered (by commit)
S1 Batch 2 (`72ad63a`): M-109 M-082 M-078 M-080 M-081 M-112 FIXED, M-079 M-051 M-089 M-083 PARTIAL. M-068 (`80f8c98`): one button vocabulary. M-107 partial (`2b0fa31`). M-053 (`0ea02e5`): lazy sections / lazy code workspace, measured. M-099 / M-116 (`26f88c8`). M-046 follow-up (`6bc9bbc`). M-114 tooling (`0a7b43f`, WebKit fixes `aac54cf`, `9096a53`). H-C1-04 (`87c3d04` portal, `b4cbf20` per-project admission), organization permissions D-C0-51 (`738f24a`), USER01 prep (`0833f29`). Brand system (`56801b2`, S3 tip `c006077`, `docs/BRAND_GUIDELINE.md`). Header fix at 521-600 px (`6883272`). C5-R-FINAL fixes (`d603488`): dark control borders 3:1, `webUrl`, scope-row guard, drawers gated, org error text, stale pointer target.

## 4. Gates (all green at the times recorded in FINAL_EVIDENCE)
typecheck, unit (508 tests, 507 pass, 1 skipped), classify, four production builds, every harness spec (CHROMIUM), css-snapshot, page-runtime, LIVE_PORTAL portals 36/36 + portals-lazy 18/18, `npm run gate:frontend` GREEN (it exists on this branch since the sync), REAL_STACK audit (1422 visits, 9 widths, 87/87 routes, axe 0, console 0, API failures 0), WEBKIT 17 of 19 specs fully pass (the rest: engine clipboard limit, Alt+Tab emulation). **NOT RUN: REAL_BACKEND_E2E.** FIREFOX: BLOCKED_TOOLING.

## 5. Exact C6 instructions
1. Check out `agent/c5-web` at the pushed head. `npm ci`; `npm run typecheck`; `npm run test:unit` (alone), then `node tests/browser/build-harness.mjs`.
2. Specs only through `node tests/browser/harness-server.mjs run -- node tests/browser/<spec>.spec.mjs` (`CHROME=...` on macOS if Chrome is not found; `BROWSER=webkit` for WEBKIT). On a loaded machine `hooks.spec` is timing-sensitive: re-run before calling it a regression.
3. Live portals: build with `NEXT_DIST_DIR=.next-gate API_PROXY_TARGET=http://127.0.0.1:9 npm run build:<app>` (restore `apps/*/tsconfig.json` afterwards), start each through `node tests/lib/owned-process-cli.mjs start --state F --cwd apps/<app> --port <3001|3002|3003> -- npx next start -H 127.0.0.1 -p <port>`, `refresh`, run `portals.spec` / `portals-lazy.spec`, `stop`. Never kill by name or port.
4. REAL_BACKEND for H-C1-04: run `E2E-USER01` / `E2E-04` / `E2E-05` against the integrated backend (>= `integration/v2 @ 7586d8a` + this import) following `docs/parallel/c5/USER01_REAL_BROWSER_PREP.md`.
5. REAL_STACK re-audit: `AUDIT_NO_SHOTS=1 node scripts/ui-audit.mjs --private-api <backend> --out DIR` (the backend is never started or stopped by the tool).

## 6. Known open items for C0 / C1 / C2 / C3
- C0: `@xweb/*` runtime resolution in `scripts/test-unit.mjs` (HF-C0-M107); CSP sha256 for the canvas script (M-089); import of this branch (the C0 gates `gate:frontend` + static guards are green on it).
- C1: per-tenant `permissions` on `TenantMembershipSummary` (the org screens use the primary tenant's codes; a TENANT_ADMIN of a second tenant gets the server's 403); M-007 / M-009 / M-058.
- C2: sites origin for client-side host pinning of the access-ticket redirect (M-051); renderer half of the preview banner (M-042); republish note for M-113.
- C3: Dynamic Organization persistence / API (the contract is frozen, routes answer 501 until C3 registers them). **ORG_BACKEND_WIRING = WAITING_FOR_C1_C3, ORG_BACKEND_READY = NO.** Wiring checklist is in `features/admin/organization.ts` (DTO renames, error-code renames, `?format=flat`, versions, 501 mapping).
- Known P3 left on purpose (C5-R-FINAL R-03): the org screens take their tenant from the role-derived tenant list while their codes describe the primary tenant (latent: the server only grants org codes to a TENANT_ADMIN together with TENANT_MEMBERS).

## 7. Do not
Force-push, reset --hard, clean -fd, merge main, add GitHub Actions; kill by name / port; edit `backend/`, `docs/contracts/**`, migrations; call HARNESS evidence real E2E; call WebKit "Safari"; mark Firefox passed or accepted; claim 10/10 without C6 and a REAL_BACKEND run.
