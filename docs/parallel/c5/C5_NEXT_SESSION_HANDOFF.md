# C5 — HANDOFF (PL01 + Dynamic Organization + AD01 / M-052 + C2 H-C2-07 candidate, 2026-10-10; supersedes the final-candidate handoff of the same day)

Written by C5-L from what was run in the session; every result names its evidence class. Itemised evidence: `audit/PL01_ORG_AD01_C2_2026-10-10.md` (this task) and `audit/FINAL_EVIDENCE_2026-10-10.md` (the earlier gate). REAL_BACKEND claims are limited to what the table in §4 says.

## 1. Git
- Branch `agent/c5-web` (lead checkout `/Users/hoangluan/code/xweb-c5`), synced by merge with `integration/v2 @ a2a8fab` (D-C0-54: C1 M-052 per-tenant `/auth/me`; earlier `1992f9b` for C2 H-C2-07). Work commits on top: `c9d982e` flows, `7b147d7` org wiring, `e722811` org specs, `a058fcb` C2 handlers, `0f1d6c2` AD01 / M-052, `c011b6a` E2E-ORG01 / AD01 flows, `9858a88` merge, then docs / flow fixes. REMOTE_HEAD must equal LOCAL_HEAD before the C0 import (`git rev-parse HEAD origin/agent/c5-web`).
- Untracked, never commit: `.next-gate*`, `apps/*/.next-gate/`, `.tmp-stage/`, `.test-build`, `.run/`.
- Other worktrees kept on purpose: `/Users/hoangluan/code/c5-batch2/{s1,before}`, `/Users/hoangluan/code/c5-brand/s3`, other teams' `xweb-*`, older stack worktrees under `~/.xweb-e2e-stack/`. The stack of this session (`c5e2e-pl`) was stopped (owned-process library) and removed (`down --infra --worktree`); ports 3001-3003 and 38180… are free. ACTIVE_AGENTS = 0 (no agent was spawned).

## 2. Delivered in this task
- **PL01** real, in-app confirmation: A trigger, B dialog, C cancel (0 PATCH), D unchanged, E reopen, F confirm (1 PATCH, 200), G server SUSPENDED, H UI refreshed — `E2E-PL01` PASS on a real stack. AD02 / P09 flows converted to the same dialogs.
- **Dynamic Organization wired** (`docs/parallel/c5/ORGANIZATION_UI.md`): `api.org` (one URL builder), `OrganizationApi` service, 31 capabilities READY (`tests/guards/org-contract.json`), units / types / tree / counts / move / archive / restore, directory + filters + paging guard, memberships, held positions with grades, positions and grades catalog, enable / disable, create employee in ONE request, 503 busy retry state, 501 fail closed, two separate counts. Flag stays OFF by default; nothing enables it.
- **AD01 / M-052**: `tenantScope.ts` resolver (`tenants[].permissions`), `TenantMembershipSummary.permissions` required, `adminScope` / `organizationPlan` / provisioning / company page per selected company, tenant rename (+ 403 `TENANT_SUSPENDED` shown), no role-based decision. `E2E-AD01` PASS real.
- **C2 H-C2-07**: 422 `PUBLIC_DATA_NOT_APPROVED` (approval persisted via `PUT publish-config`, never via `POST /publish`), 409 `PUBLISH_POLICY_MISMATCH` (policy reload, no cached retry). HARNESS + MOCK only (real publish route not exercised: stack without publish-configs).

## 3. Ledger (`audit/MASTER_ISSUE_LEDGER.md`)
M-052 FIXED (frontend; backend by C1 `1b76746`), the AD01 blocker closed on the C5 side. Other rows unchanged from the previous handoff (CANONICAL_TOTAL 132; unresolved remainders: M-007 / M-009 C1, M-039 / M-040 / M-101 C2, M-073 / M-090 / M-102 / M-108 / M-089 / M-107 C0, M-094 backend, M-114 Firefox user decision). Regenerate with `audit/tools/ledger-gen` (see the previous handoff recipe: copy to a scratch dir, `node check.mjs && node gen.mjs && node normdoc.mjs`, copy the three outputs back).

## 4. Gates and evidence (head = the pushed commit; clean worktree for the unit / gate)
| Class | Result |
|---|---|
| `npm ci` + typecheck root / apps / packages (clean worktree) | PASS / PASS / PASS |
| unit | 540 tests, 539 pass, 0 fail, 1 skipped (conformance fixtures) |
| `npm run gate:frontend` (clean worktree) | **GATE GREEN** (static guards 9/9, guard self-tests, typecheck, unit, builds platform / admin / studio, bundle scan) |
| HARNESS CHROMIUM (exit 0 each) | admin 186, aiproviders 27, builder 111, datasources 54, hooks 20, org 122, org-employees 76, org-hardening 67, provisioning 39, publicdata 47, publish-policy 27, release 61, sanity 8, shared-ui 80; css-snapshot, data-binding, page-runtime, studio-p1 / wave2 / wave3, ui-brand, ui-route, ui-tokens, ui-widgets exit 0 |
| LIVE_PORTAL CHROMIUM | portals 36/36, portals-lazy 18/18 (production builds, API unreachable by design) |
| REAL_BACKEND (stack `c5e2e-pl`, `integration/v2 @ a2a8fab`, flag OFF) | PL01 PASS, AD01 PASS, AD02 PASS, ADMIN01 PASS, USER01 PASS, E2E-04 PASS, E2E-05 PASS, E2E-ORG01 BLOCKED(C0) with fail-closed evidence PASS, E2E-P09 FAIL on ONE environmental check (no sites gateway on this stack) |
| NOT RUN | 25 flag-ON organization cases; real 409 / 422 publish; WebKit / Firefox for the new specs (Firefox stays BLOCKED_TOOLING, not accepted) |

## 5. Exact C0 / C6 instructions
1. Check out `agent/c5-web` at the pushed head. `npm ci`; `npm run typecheck`; `npm run test:unit`; `npm run gate:frontend`.
2. Specs only through `node tests/browser/harness-server.mjs run -- node tests/browser/<spec>.spec.mjs` (`CHROME=...` on macOS; `BROWSER=webkit` for WEBKIT). `org`, `org-employees`, `org-hardening`, `publish-policy` are the new / rewritten ones. `hooks.spec` is timing-sensitive under load.
3. Live portals: `NEXT_DIST_DIR=.next-gate API_PROXY_TARGET=http://127.0.0.1:9 npm run build:<app>` (restore `apps/*/tsconfig.json`), start through `tests/lib/owned-process-cli.mjs start|refresh|stop`, run `portals.spec` / `portals-lazy.spec`. Never kill by name or port.
4. REAL_BACKEND: `docs/parallel/c5/e2e-stack.sh up` (own name and ports via `E2E_STACK_NAME`, `E2E_*_PORT`), build the two portals with `API_PROXY_TARGET=<api>` and start them through the owned-process CLI, then `e2e-stack.sh e2e "E2E-PL01,E2E-AD01,E2E-AD02,E2E-ADMIN01,E2E-USER01,E2E-04,E2E-05"`.
5. **E2E-ORG01 (25 cases)**: provide an isolated, C0-approved stack with `ORGANIZATION_PERSISTENCE_ENABLED=true` and run `e2e-stack.sh e2e "E2E-ORG01"`. On a flag-OFF stack it records the fail-closed evidence and ends BLOCKED(C0). Each case writes REQUEST · EXPECTED · ACTUAL · HTTP · UI · SERVER into its check detail.
6. C2 handlers against a real backend: needs `app.publish-configs.enabled=true` and a PAGE_SCHEMA project with a public READ query (`E2E-PD02` style fixture).

## 6. Open items
- C0: flag-ON stack for E2E-ORG01; `@xweb/*` runtime resolution in the unit runner (M-107); CSP sha256 for the canvas script (M-089); sites gateway in the stack for E2E-P09's public-URL check.
- C1: M-007 / M-009 / M-058 (unchanged). Per-tenant permissions are consumed; nothing more is asked for AD01.
- C2: M-051 sites origin; M-042 renderer half of the preview banner; real run of the two new publish refusals.
- C3: none for the frontend (persistence is in; flag decision is C0's).
- Known P3 left on purpose: none new. `adminScope` role-label listing is gone; `isTenantAdminRole` remains only in `roles.ts` for display hints (`memberChangeBlock`, promotion confirmations).

## 7. Do not
Force-push, reset --hard, clean -fd, merge main, add GitHub Actions; kill by name / port; edit `backend/`, `docs/contracts/**`, migrations; call HARNESS evidence real E2E; enable `ORGANIZATION_PERSISTENCE_ENABLED` yourself; call WebKit "Safari"; mark Firefox passed or accepted; claim 10/10 without C6.
