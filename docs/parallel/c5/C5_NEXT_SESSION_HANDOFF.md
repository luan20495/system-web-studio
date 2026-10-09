# C5 — NEXT SESSION HANDOFF (complete; a fresh C5-L session can start from this file alone)

Written 2026-10-09 (Asia/Saigon) at the end of Wave A, by the old lead session, from what was actually run and read in that session. Every result below names its evidence class. Browser results are **HARNESS, NOT REAL BACKEND** unless a line says REAL STACK. Nothing here was inferred without saying so.

## 1. Git state
| Field | Value |
|---|---|
| CURRENT_BRANCH | `agent/c5-web` (lead checkout `/Users/hoangluan/code/xweb-c5`) |
| Gate head (all gates below ran on it) | `c5e4272ad7eb2d0d5a04ddecb383152b9f28e3ac` |
| CURRENT_HEAD | the commit that adds this file (`git log -1`), a docs-only child of `c5e4272`; the final report of the old session gives its SHA |
| REMOTE_HEAD before this checkpoint | `bf8b509` (origin `https://github.com/luan20495/system-web-studio.git`) |
| LOCAL_AHEAD_COUNT before the docs commit | 20 commits (`git rev-list --count bf8b509..c5e4272`) |
| Integration branch | `integration/v2` (`/Users/hoangluan/code/HBL`) at `13855e5`, which contains the earlier C5 import `058e0de` (agent/c5-web @ 9f858c2). Wave A is **not** in `integration/v2` (C0 imports; C5 never merges main/integration). |
| Untracked, never commit | `.next-gate-root/`, `apps/*/.next-gate/`, `.tmp-stage/`, `.test-build` |

WORKTREE_STATE: lead checkout clean (tracked). The three Wave A worktrees `/Users/hoangluan/code/c5-wave-a/{s1,s2,s3}` were removed after merge (branches kept, all ancestors of HEAD: `agent/c5-s{1,2,3}-wave-a`). Old agent worktrees under `HBL/.claude/worktrees` were all removed earlier. Preserved on purpose (not C5 agent worktrees): other teams' checkouts `xweb-c0..c7`, `xweb-wire`, `xweb-v29`, `xweb-base`, `xweb-c5-overlay`, and the backend worktrees `~/.xweb-e2e-stack/*` (`c5e2e-ae` backs a running gradle process).

## 2. Canonical ledger
- CANONICAL_LEDGER_PATH: `docs/parallel/c5/audit/MASTER_ISSUE_LEDGER.md` (+ `master-ledger.json`). Normalized view: `docs/parallel/c5/audit/LEDGER_STATUS_NORMALIZED.md` (rules + every ID per status).
- Regenerate: `mkdir /tmp/ledger && cp docs/parallel/c5/audit/tools/ledger-gen/* /tmp/ledger/ && cd /tmp/ledger && node check.mjs && node gen.mjs && node normdoc.mjs`, then copy `MASTER_ISSUE_LEDGER.md`, `ledger.json` (as `master-ledger.json`), `LEDGER_STATUS_NORMALIZED.md` back. To change a status add `status("M-xxx", "...")` at the end of `tools/ledger-gen/patch.mjs` (keep `tools/ledger-patch.mjs` identical). Verified reproducible byte for byte at this commit.

CANONICAL_COUNTS (132 rows; P0 0 · P1 15 · P2 74 · P3 43):

| Status | Count |
|---|---|
| FIXED | 85 |
| CLOSED (not a defect / detector case: M-124, M-128, M-129) | 3 |
| PARTIAL | 20 |
| OPEN | 13 |
| BLOCKED (other owner) | 8 |
| ACCEPTED_LIMITATION | 0 |
| RESEARCH_ONLY | 3 |
| NEEDS_REVIEW | 0 |

Unresolved (not FIXED/CLOSED) = 44: P0 0 · P1 2 (M-007, M-009: C5 part fixed, C1 handoff open) · P2 19 · P3 23.

- OPEN (13): M-051 M-078 M-080 M-081 M-082 M-083 M-089 M-109 M-112 (S1 deferred for budget), M-099 M-107 M-114 M-116 (Wave B / tooling review).
- BLOCKED (8): M-039 M-040 (C2 answer), M-073 M-090 M-102 M-108 (C0), M-094 (backend user id), M-101 (C2 worker).
- RESEARCH_ONLY (3): M-071 M-100 M-103.
- PARTIAL (20): M-007 M-009 M-042 M-053 M-058 M-059 M-060 M-061 M-062 M-063 M-067 M-068 M-069 M-070 M-072 M-079 M-084 M-093 M-097 M-110 (see the normalized file for each remainder; M-097/M-079 wording is in the ledger row).
  Note: the classification of a free-text status is by its leading words (rules at the top of `LEDGER_STATUS_NORMALIZED.md`). 22 rows that had a blank status before were given an explicit OPEN/BLOCKED/RESEARCH_ONLY status in this pass.

## 3. Wave A summary (base `45f4ca4`, merge order S3, S2, S1; all three agents Opus, isolated worktrees, none pushed anything; all FINISHED, ARCHIVED, ACTIVE_AGENTS = 0)

### S3 — C5-S3-WAVE-A (shared UI) · 18 min, ~138k tokens · branch tip `7168d4d` · merge `6842f65`
Assigned: M-067 M-069 M-070 M-084 + shared part of M-060 M-061 M-062 M-063.
Commits: `8dd71bb` M-069 radius/type-scale tokens (206 uses, value-identical) · `c67f5a1` M-070 `.sr-only` defined, dead 16px checkbox size removed, conflicts 19→17 · `22670eb` M-084 portal splash/fallbacks/404 render h1 (touches `apps/*/app/entry.tsx`, `components/app/AppEntry.tsx`, `packages/auth/src/PortalApp.tsx`: one attribute each) · `28d5d3f` M-060/062/063 shared copy (APP_PUBLISH no longer shown, BRAND/PORTAL_LABEL, ceilings lowered: internal-constant 15→13, legacy-name 11→7, term-project 14→10, term-model 10→9, tone-old-style 57→30, native dialogs 12→0) · `7168d4d` M-067 `<Field>` in `@xweb/ui`, adopted on auth pages (6 fields).
Tests reported by S3 (HARNESS): unit 458 tests/457 pass/0 fail, shared-ui 80/80, ui-widgets 54/54, ui-route 16/16, ui-tokens 42/42, css-snapshot 34 of 36 identical (the 2 changed are `studio-home@desktop/@phone`, intended: auth card brand now "Xweb"), tsc clean.
Partial: M-069 (builder.css hex = S1 file, globals.css, shadow tokens, dark palette), M-070 (17 conflicting duplicates, mostly intentional), M-084 (shell level only), M-067 (~45 call sites in features/admin and features/studio not migrated, no FilterBar), M-060/062/063 (S1/S2 call sites, `runtimeConfig.ts` DATA_API_BASE_URL wording needs a product decision, `features/library.tsx` owner unknown). M-061: NOT_NEEDED for the shared part (25 label maps exist).
Deferred/follow-ups: legacy `e2e/factory-flow.mjs:40` still expects "Admin Console"/"Builder Studio" (old names; out of scope).

### S2 — C5-S2-WAVE-A (Platform/Admin) · 21 min, ~163k tokens · branch tip `16fb8ad` · merge `9b67da9` (one import-line conflict in `packages/auth/src/AuthPages.tsx` resolved by keeping both sides)
Assigned: M-025 remainder, M-093, M-097 remainder, M-098, M-065.
Commits: `418fd25` M-093(a) (`/auth/config` failure → ErrorState + retry; edits `packages/auth/src/AuthPages.tsx`, outside features/admin, root cause required it) · `5f9e3ae` M-098 (`activationUrl()` in `adminModel.ts` uses `portalOrigin("studio")`) · `26b31e2` M-097 (debounce 300 ms: REQ04 4 keystrokes→1 request, REQ03 7→1; member lists loaded once; AI providers/limits on keyed `useLoad`, REQ01 2→1; helper `features/admin/shared/useDebounced.ts`) · `16fb8ad` M-065 (user decision applied: the four people screens stay separate; shared blocks `peopleSections.ts`, `PeopleLinks.tsx`, `TenantSwitch.tsx`, `ownWorkspaces.ts`; note `docs/parallel/c5/audit/S2-people-screens.md`; route snapshot unchanged).
Tests reported by S2 (HARNESS): unit 453 pass/0 fail, admin 176/176 (SNAP01 unchanged), org 89/89, org-hardening 70/70, provisioning 39/39, aiproviders 27/27.
M-025: NOT_NEEDED (Platform and Admin share `AdminApp`; SKP01–05 already run on portal=platform). M-093(b) BLOCKED on HF-C1-05 + real-stack check (`packages/permissions/src/canonical.ts canViewStudioIn` unchanged). M-097 remainder: `api.*` methods do not accept an AbortSignal (`packages/api-client`).
Follow-ups: promote `TenantSwitch` and `useDebounced` to `@xweb/ui` (S3-type work, later).

### S1 — C5-S1-WAVE-A (Studio) · 44 min, ~197k tokens · branch tip `7d46be1` · merge `c5e4272` (no conflicts)
Assigned: M-042 M-046 M-047 M-051 M-077–M-083 M-088 (Studio part) M-089 M-091 M-106 M-109 M-110 M-112 M-113.
Commits: `c495040` M-106 (steps named "Bước N · kind: name", Vietnamese approver kinds, backoff in seconds; cron presets not done) · `9877afa` M-046 (+ part of M-110) pure scroll no longer re-renders the Builder; measured (HARNESS profiling bundle, median): at 1004 sections host commits 25→0, worst frame gap 106.9→17.8 ms, dropped frames 25→0; 404 sections 2→0; 50 sections 0→0 · `74e1d90` M-047 + M-077 (Website drawer uses builder `core/pages`; shows the slug really saved) · `5d4c585` M-042 client half (view-only banners, theme panel note; renderer half = HF-C2-04, C2) · `d2a126b` M-088 Studio part (RuntimeDrawer secret cleared only after a successful save; one save at a time) · `52cf9c5` M-091 (`studioStorage.ts` clears studio-ws/studio-ai-model/ws-mode-* on logout and on a different user) · `d30a98f` + `7d46be1` M-113 (smooth scroll only under `prefers-reduced-motion: no-preference`; **published output changed → golden sha256 updated; C2 republish note**).
Tests reported by S1 (HARNESS): unit 459 pass/0 fail/1 skip, studio-wave3 149/149 (11 new), builder 110/110, studio-p1 16/16, studio-wave2 31/31, page-runtime 37/37, tsc clean, text-guard + native-dialog guards pass.
Partial: M-110 (handles memoised; page-tree row memo/windowing not done). Deferred (not started, budget): M-109 (needs a micro-benchmark before/after), M-112, M-089, M-051, M-078, M-079 (component merged earlier; needs a check), M-080, M-081, M-082 (needs a measurement), M-083.
Follow-up found by S1: after M-046 a scrolled-out drag handle is clipped (`overflow:clip`) but still tabbable, so a keyboard user can focus an invisible handle (WCAG 2.4.7/2.4.11 risk); a fix needs a new message in the preview script (log as a new P3 or fold into M-110/M-046 follow-up).

MERGE_COMMITS: S3 `6842f65`, S2 `9b67da9`, S1 `c5e4272`.

## 4. CHECKPOINT_2_GATE_RESULTS (head `c5e4272`, run by the lead with the scripts in `docs/parallel/c5/audit/tools/gate/`)
| Gate | Result |
|---|---|
| TYPECHECK root / apps / packages | PASS / PASS / PASS |
| UNIT | 468 tests, 467 pass, 0 fail, 1 skipped (pre-existing skip) |
| CLASSIFY (`npm run test:classify`) | PASS (5 external files owned by C0/C2 not classified here) |
| BUILDS platform, admin, studio, root | all PASS (`/tmp`-style gate build with `NEXT_DIST_DIR=.next-gate`, API_PROXY_TARGET unreachable; Next cache makes re-builds fast) |
| HARNESS (HARNESS, NOT REAL BACKEND, run via `harness-server.mjs run`, ~1052 s) | admin 176/176 · aiproviders 27/27 · org 89/89 · org-hardening 70/70 · provisioning 39/39 · builder 110/110 · datasources 54/54 · release 61/61 · publicdata 47/47 · shared-ui 80/80 · ui-widgets 54/54 · ui-route 16/16 · ui-tokens 42/42 · studio-p1 16/16 · studio-wave2 31/31 · studio-wave3 149/149 · data-binding 34/34 · hooks 20/20 · sanity (production bundle) 8/8 |
| extra on the same head | css-snapshot: 36 screenshots, 0 could not be taken, exit 0 · page-runtime 37/37 |
| NOT RUN | `portals.spec.mjs` and `portals-lazy.spec.mjs`: they need the three portal apps listening on 127.0.0.1:3001/3002/3003 and exit 1 with ERR_CONNECTION_REFUSED without them (this is not a code failure; they are outside the harness list in `run-specs.sh`); `npm run gate:frontend` (this script and `tests/guards/process-safety.mjs` exist only on `integration/v2` / C0, not on `agent/c5-web`, so they cannot run here; they scan scripts/ tests/ e2e/ workers/ infra/ tooling/ and not docs); real-stack 9-width audit; axe (AXE_IF_RUN: NOT RUN in this checkpoint, the harness specs contain their own checks) |
| FAILURES | none in what ran |

## 5. BACKEND_BLOCKED / handoffs
M-007, M-009 (C1: re-issue activation link, admin-scoped app delete/restore), M-058 (C1), M-039, M-040 (C2 answer), M-101 (C2 worker), M-059 (backend provider status), M-094 (backend user id), M-073, M-090, M-102, M-108 (C0), M-093(b) (HF-C1-05), M-042 renderer half (HF-C2-04), M-113 republish note (C2). All routed in `docs/parallel/c5/audit/HANDOFFS_FRONTEND_AUDIT.md`. Dynamic Organization stays NOT_READY.

## 6. ACCEPTED_LIMITATIONS
None formally recorded. Candidates: M-114 if cross-browser runs stay impossible (see §8); M-053 deeper splitting if measurement shows no gain; the builder on phones is "look and adjust", not a mobile editor (documented in `evidence/c6-fix-2026-10-08/README.md`).

## 7. REAL_STACK_EVIDENCE_ALREADY_AVAILABLE (class REAL_STACK, private builds against the e2e backend; backend was never started/stopped by C5)
`docs/parallel/c5/audit/S4-realstack-baseline.md`, `S4-matrix-baseline.md`, `S4-baselines.md`, `S4-performance-tooling.md`: 3 portals × 9 widths, 1,422 visits, 117 routes, 0 routes not visited; 0 overflow, 0 axe findings, 0 console errors, 0 failing API calls. Re-run at an earlier head (Studio 600/768/1024, 102 visits) closed M-126, M-127, M-129. Earlier C6-fix evidence: `docs/parallel/c5/evidence/c6-fix-2026-10-08/`. **No real-stack run exists for Wave A code.** Not reachable on the real stack: /admin/organization (NOT_READY), AI generation, publish, real 403/404/500, keyboard walkthrough, cost data. Tool: `AUDIT_NO_SHOTS=1 node scripts/ui-audit.mjs --private-api http://127.0.0.1:47080 --only <portal> --viewports … --out DIR` (the backend on 47080 is the e2e stack's; never start/stop it from C5 work).

## 8. CROSS_BROWSER_STATE (M-114, user-approved install)
- FIREFOX_INSTALLED: YES (`~/Library/Caches/ms-playwright/firefox-1543`, via `node node_modules/playwright-core/cli.js install firefox webkit`, playwright-core 1.63.0).
- WEBKIT_INSTALLED: YES (`webkit-2359`, WebKit 26.6). Playwright WebKit is **not** Safari; never write "Safari tested".
- Smoke launch (no repo spec yet): WEBKIT launch OK (version 26.6, `setContent` OK). FIREFOX launch FAILED on this machine: `Could not find profile folder` (exit 1, also with TMPDIR inside the scratchpad); cause NOT diagnosed. The 30-minute stop rule applies: if a fresh session cannot fix it quickly, record FIREFOX as BLOCKED with this message.
- TESTED: NO. No spec has run on Firefox or WebKit. `tests/browser/lib/spec.mjs` `launch()` is hard-wired to a Chrome executable (`chromium.launch({ executablePath })`); a cross-browser run needs a small launcher switch (e.g. `BROWSER=firefox|webkit`) and the evidence labels CHROMIUM / FIREFOX / WEBKIT. Critical flows to run later: login, Studio builder (sandboxed srcdoc iframe, dnd-kit pointer events, `:has()`), Admin org/people, at widths 360 390 430 600 768 1024 1280 1440 1920.

## 9. AGENT_POLICY (user-approved; full text in `docs/parallel/c5/AGENT_REGISTRY.md`)
BALANCED mode: max 3 coding agents + 1 review agent; C5-L not counted; one active owner per issue ID; no duplicate roles/names (`C5-S1-WAVE-A`-style names, one generation); no dedicated S4 agent (C5-L runs the S4 scripts); no agent for tiny tasks; `C5-R-FINAL` only after Wave B + full gate + real stack; any extra agent needs a proposal (PROPOSED_AGENT / WHY_NEEDED / ISSUES / FILES / EXPECTED_TIME_SAVED / EXPECTED_TOKENS / CONFLICT_RISK) and the user's approval. ACTIVE_AGENTS = 0 now. Agent briefs: templates in `docs/parallel/c5/audit/tools/wave-briefs/` (COMMON + per-lane task; rows for a lane = the ledger rows of its IDs). How Wave A was run: create the worktree yourself (`git worktree add -b agent/c5-sN-… /Users/hoangluan/code/c5-wave-a/sN <sha>`), `npm ci --prefer-offline` in each (7 s), copy the brief into `<worktree>/.c5-brief/` (add it to the worktree's `info/exclude`), spawn with model opus, no `isolation` flag. A wave of 3 agents took 18–44 min wall clock, 138k–197k tokens each. Agents have no channel to the lead before their final report.

## 10. PROCESS_SAFETY (mandatory)
Never `pkill`, `killall`, `kill $(pgrep …)`, `kill $(lsof -ti tcp:P)`, kill by name or port. Only processes you started, via `node tests/lib/owned-process-cli.mjs {start|status|stop} --state …` or `node tests/browser/harness-server.mjs run -- node tests/browser/<spec>.spec.mjs` (picks a free port, cleans up). Foreign processes must survive: next-server on 3201–3203 and 3301–3303 (C0), gradle bootRun (pids differ; the `c5e2e-ae` e2e backend and the HBL backend), Docker 19000/19001. `npm run test:unit` DELETES `.test-build`: never run it while a spec is running, and run `node tests/browser/build-harness.mjs` after it (two false mass failures came from this). Gate scripts: `tools/gate/c5-cp-a.sh` (typecheck + unit + classify), `run-specs.sh` (harness build + all specs, set `CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"`), `c5-gates-build.sh` (3 portal builds + root, restores tsconfigs), `c5-cp2.sh` (all three in order). They hard-code `/Users/hoangluan/code/xweb-c5` and write logs to `/tmp`; run them with `bash <script>` (they are not executable) in the background and poll the log; no `timeout` command exists on this macOS.

## 11. EXACT_WAVE_B_PLAN (SERIAL, C5-L only, not started; each step: IMPLEMENT → TARGETED TEST → REVIEW DIFF → LIGHT GATE → COMMIT → NEXT; if a step is obsolete after Wave A close it with evidence instead of forcing code)
Light gate = typecheck (root + apps + packages) + `npm run test:unit` (then rebuild harness) + the targeted specs; full gate only after step 3.

1. **M-068 — one button system.** OWNER C5-L (S3 vocabulary exists: `<Button>` + dark skin, 9ea2e39). SCOPE: migrate call sites of `.button`, `.smallButton`, `.bx-btn` (ratcheted at 73/72/56 in the guards; `.btn` is the target) in small steps, lowering the ratchets after each step. FILES: `features/admin/**`, `features/studio/**`, `packages/{ui,company-ui,auth}/**`, `packages/ui/src/styles/*.css`. DEPENDENCY: Wave A merged (done). TARGETED_TEST: `css-snapshot` (pixel hashes must stay unchanged unless an intended change is justified), `shared-ui`, `ui-widgets`, `ui-tokens`, admin, builder, studio-wave3; a11y target-size/contrast detectors. STOP_CONDITION: a snapshot diff that is not explained by a documented unification, or 2 failed attempts on the same root cause, or > 2× normal spec time.
2. **M-107 — single import spelling.** OWNER C5-L (serial after M-068). SCOPE: 9 shim files and 22–23 relative `../packages/...` imports (3 spellings of one import) in S1/S2 files; the unit runner compiles to CommonJS/node10 so `@xweb/*` cannot be loaded at runtime in tests: keep relative runtime imports where the runner needs them. FILES: `tests/tsconfig.json`, `scripts/test-unit.mjs` (C0-owned; do not edit, ask via BOARD), the shim files listed in `docs/parallel/c5/audit/R-*.md`. DEPENDENCY: M-068 done (avoids churn in the same files). TARGETED_TEST: unit + all four builds + typecheck. STOP_CONDITION: the unit runner cannot load a module after a change (revert that file), or any build breaks.
3. **M-053 — deeper bundle splitting** (PARTIAL: console already lazy behind login, Platform/Admin first load 866.6→549.2 KB raw, Studio 989.0→562.4 KB). OWNER C5-L with S4 measurement. SCOPE: dynamic import for rarely used dialogs/panels/Admin sections; split `AdminApp` by portal (shared with M-066, already done). Only if the measurement shows a gain. FILES: `apps/*/app/entry.tsx`, `features/admin/AdminApp.tsx`, `features/studio/**`. DEPENDENCY: M-068 and M-107 merged. TARGETED_TEST: build output size table before/after (First Load JS), `portals-lazy` shape (needs the apps running), admin/builder specs. STOP_CONDITION: first-load gain < ~5 % or any route behaviour change → keep as ACCEPTED_LIMITATION with the numbers.

Then **review only (no speculative work)**:
- **M-072** (PARTIAL): untested-file reach and reproducibility (shims overlap M-107). OWNER C5-L/S4. FILES `tests/browser/lib/*`, `tests/browser/build-harness.mjs`. TARGETED_TEST: spec counts + a flake re-run. STOP: only fix what is measured.
- **M-099** (OPEN): verified-dead exports, 4 unused imports, `as never` casts, test ids. Only provable items (scripts `R-dead-exports.mjs`, `R-import-graph.mjs` in `audit/tools`). TARGETED_TEST: tsc + unit. STOP: anything not provably dead.
- **M-114** (OPEN): add a `BROWSER` switch to `tests/browser/lib/spec.mjs`, fix or document the Firefox launch failure (§8), run portals/builder/org specs under FIREFOX and WEBKIT, label evidence; if Firefox stays broken record M-114 as ACCEPTED_LIMITATION for Firefox with the error. STOP: > 30 min without useful progress (user rule).
- **M-116** (OPEN): make the conformance test print a loud "PENDING (XWEB_CONFORMANCE_DIR not set)" line instead of skipping silently (`tests/builder/conformance.test.ts:27,31,36`, a C0-owned runner file: ask C0 via BOARD if it must change; otherwise report only).

After Wave B: full gate (§10 scripts) → real-stack regression with the existing S4 tooling (no new audit framework) → cross-browser matrix → C5-R-FINAL read-only review → final report (format in the old prompt: BASE_HEAD a9fbdb0 … FINAL_UI_UX_10_OF_10) → C6 independent regression → C0 import. C5 does **not** self-certify GREEN.

## 12. NEXT_EXACT_ACTION
1. `cd /Users/hoangluan/code/xweb-c5 && git status && git log --oneline -5` and confirm HEAD descends from `c5e4272`.
2. Read this file, `AGENT_REGISTRY.md`, `LEDGER_STATUS_NORMALIZED.md`.
3. Ask the user which to do first: (a) start Wave B serially as in §11; (b) a second Wave-A-style batch for the 10 deferred S1 P3 items (M-109 M-112 M-089 M-051 M-078 M-079 M-080 M-081 M-082 M-083) with the existing 3-agent limit; (c) fix Firefox launch for M-114. Do not start any of them without the user's go-ahead. Recommended order: Wave B step 1 (M-068) because it is the biggest and the only serial blocker, then the rest.

## 13. DO_NOT_DO
- Do not push `--force`, `reset --hard`, `clean -fd`; do not merge `main`; do not add GitHub Actions; do not touch `backend/`, `docs/contracts/**`, Flyway migrations (only C0), the gemma tunnel, factory or chatwoot.
- Do not start any agent beyond the approved policy; do not recreate old agent sessions; do not edit the ledger from an agent (only C5-L).
- Do not run the three Wave B items in parallel; do not run `test:unit` during specs; do not call HARNESS evidence real E2E; do not claim Safari from WebKit; do not claim 10/10 without the gates, real stack, cross-browser and C6.
- Do not commit `.next-gate*`, `.tmp-stage`, `.test-build`, `.run`.
- Do not push without checking gates green (normal push only) and confirming REMOTE_HEAD == LOCAL_HEAD afterwards.

## 14. Standing rules (CLAUDE.md + memory)
Vietnamese replies; one task = one test + one commit; final report = files changed / tests / blockers / commit SHA; no faking results; C5 owns frontend files only (see `docs/parallel/OWNERSHIP.md`), contract or backend needs go to `HANDOFFS_*.md` / `BLOCKERS.md`.
