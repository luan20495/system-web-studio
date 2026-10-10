# C5 — AGENT REGISTRY (single source of truth for agent ownership)

Maintained by C5-L. Last reconciled: 2026-10-10 (Asia/Saigon), repo head `d603488`+docs (branch `agent/c5-web`).
Times are Asia/Saigon (UTC+7), taken from the session transcript. CONTEXT_SIZE of historical sub-agents was not recorded (UNKNOWN).

## Admission rules (user-approved, BALANCED mode)
- Active slots: **max 3 coding agents + 1 review agent** (4 excluding C5-L). Historical / finished / failed / stopped agents do **not** count as active slots.
- One active owner per canonical issue ID. No two active agents on the same role, issue IDs, files or root cause.
- One current-generation name per lane: `C5-S1-WAVE-A`, `C5-S2-WAVE-A`, `C5-S3-WAVE-A`, `C5-R-FINAL`. Replace = mark the old one STOPPED/REPLACED first, then spawn.
- No dedicated S4 agent by default: C5-L runs the existing S4 scripts (`scripts/ui-audit.mjs`, `scripts/ui-state-matrix.mjs`, `scripts/ui-keyboard.mjs`, `scripts/ui-repro.mjs`).
- No agent for tiny tasks (one copy fix, one CSS tweak, one test rerun, one doc line, one verification).
- C5-R-FINAL starts only after Wave A + Wave B + full gate + real stack.
- Any additional agent needs a proposal to the user (PROPOSED_AGENT / WHY_NEEDED / ISSUES / FILES / EXPECTED_TIME_SAVED / EXPECTED_TOKENS / CONFLICT_RISK) and approval.

## Active execution slots right now
**ACTIVE_AGENTS = 0 (2026-10-10).** P-05 C5-S1-BATCH2 FINISHED, merged in 72ad63a; P-06 C5-S3-BRAND FINISHED, merged in 56801b2; P-04 C5-R-FINAL FINISHED (report received, P2/P3 findings fixed in d603488). All Wave A agents (P-01..P-03) FINISHED and their branches are merged into `agent/c5-web` (see below); the 15 historical agents H-01..H-15 are FINISHED/STOPPED and ARCHIVED. No agent occupies a slot. C5-R-FINAL is PLANNED only (after Wave B + full gate + real stack).

## Registry

| AGENT_ID | NAME | ROLE | WAVE | ISSUE_IDS | FILE_SCOPE | STATUS | STARTED_AT | LAST_PROGRESS_AT | CONTEXT_SIZE | BRANCH | HEAD | OUTPUT_STATUS | DUPLICATES | STOP_REASON |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| H-01 | C5-S4 process lifecycle cleanup | Tooling: owned-process lifecycle | early (10-08) | process-safety tooling (pre-ledger) | `tests/lib/owned-process*.mjs`, `scripts/` | FINISHED, ARCHIVED | 10-08 15:21 | terminal | UNKNOWN | `agent/c5-s4-tooling`, `agent/c5-s4-tooling-2` | 09da206 | MERGED (ancestor of HEAD) | none | completed |
| H-02 | C5-R architecture and UX review | Read-only review | early | (pre-ledger) | read-only | FINISHED, ARCHIVED | 10-08 15:21 | terminal | UNKNOWN | none | n/a | MERGED (no code) | none | completed |
| H-03 | C5-S3 shared UI diff review | Read-only review | early | (pre-ledger) | read-only | FINISHED, ARCHIVED | 10-08 15:21 | terminal | UNKNOWN | none | n/a | MERGED (no code) | none | completed |
| H-04 | C5-S3 review of mobile switch | Read-only review | early | (pre-ledger) | read-only | FINISHED, ARCHIVED | 10-08 17:26 | terminal | UNKNOWN | none | n/a | MERGED (no code) | none | completed |
| H-05 | C5-S1 Studio full audit | Studio audit, then resumed as wave1-3 | audit + remediation | Studio rows of the ledger | `features/studio/**` | FINISHED, STALE, ARCHIVED (also hit session + weekly limit while resumed; stale context) | 10-08 23:09 | terminal | UNKNOWN (large) | `agent/c5-s1-audit`, `-fix`, `-wave2`, `-wave3` | b9f6017 | MERGED (all branches ancestors) | none | limit reached, then completed; ARCHIVED |
| H-06 | C5-S2 Platform/Admin full audit | Platform/Admin audit, then wave1-2 | audit + remediation | Admin/Platform rows | `features/admin/**` | FINISHED, STALE, ARCHIVED (same limit history; stale) | 10-08 23:09 | terminal | UNKNOWN (large) | `agent/c5-s2-audit`, `-fix`, `-wave1`, `-wave2` | 1d8f89e | MERGED; worktree WIP (5 files) is superseded by d319531, saved as patch | none | ARCHIVED |
| H-07 | C5-S3 design system and text audit | Shared UI / text, then wave1/1b/1c/2 | audit + remediation | shared UI / text rows | `packages/ui/**`, `packages/i18n/**` | FINISHED, STALE, ARCHIVED (same limit history; stale) | 10-08 23:10 | terminal | UNKNOWN (large) | `agent/c5-s3-audit`, `-fix`, `-wave1`, `-wave1b`, `-wave1c`, `-wave2` | 1364025 | MERGED | none | ARCHIVED |
| H-08 | C5-S4 performance and tooling audit | Perf / tooling / real-stack baseline | audit + remediation | M-053, M-072, M-099, M-107, M-114, M-116, S4 baselines | `scripts/`, `tests/browser/lib/**` | FINISHED, STALE, ARCHIVED (same limit history; stale) | 10-08 23:10 | terminal | UNKNOWN (large) | `agent/c5-s4-perf`, `-wave1..4` | 43eb0f3 | MERGED | none | ARCHIVED |
| H-09 | C5-R architecture and onboarding review | Research / architecture | audit | R rows, `FRONTEND_ONBOARDING.md` | docs only | FINISHED, ARCHIVED | 10-08 23:10 | terminal | UNKNOWN | `agent/c5-r-arch` | n/a | MERGED | none | completed |
| H-10 | C5-R2 security, i18n, theme audit | Research | audit | R2 rows | docs only | FINISHED, ARCHIVED | 10-08 23:11 | terminal | UNKNOWN | `agent/c5-r2-sec`, `agent/c5-r2-review` | 3245952 | MERGED | none | completed |
| H-11 | C5-S1-REMEDIATION Studio P2/P3 | Remediation (opus) | 10-09 remediation | Studio P2/P3 | `features/studio/**` | STOPPED, ARCHIVED by C5-L at the pause order | 10-09 15:52 | none (no commit) | small | `agent/c5-s1-rem` | a9fbdb0 (empty) | NONE produced | REPLACED BY C5-S1-WAVE-A | user pause / estimate-first |
| H-12 | C5-S2-REMEDIATION Platform/Admin + text pass | Remediation (opus) | 10-09 remediation | Admin/Platform | `features/admin/**` | STOPPED, ARCHIVED | 10-09 15:52 | none | small | `agent/c5-s2-rem` | a9fbdb0 (empty) | NONE | REPLACED BY C5-S2-WAVE-A | same |
| H-13 | C5-S3-REMEDIATION shared UI / design system | Remediation (opus) | 10-09 remediation | shared UI | `packages/ui/**` | STOPPED, ARCHIVED | 10-09 15:53 | none | small | `agent/c5-s3-rem` | a9fbdb0 (empty) | NONE | REPLACED BY C5-S3-WAVE-A | same |
| H-14 | C5-S4-VERIFY final real-stack and matrix verification | Verification (opus) | 10-09 remediation | verification | scripts | STOPPED, ARCHIVED; its two owned processes are already GONE | 10-09 15:53 | none | small | `agent/c5-s4-verify` | a9fbdb0 (empty) | NONE | REPLACED BY C5-L (runs S4 scripts itself) | same; no dedicated S4 agent by default |
| H-15 | C5-R-REVIEW read-only regression review since 9273b93 | Review (opus) | 10-09 remediation | review | read-only | STOPPED, ARCHIVED (started too early over unstable code) | 10-09 15:53 | none | small | `agent/c5-r-review` | 72f4d0c | NONE | REPLACED BY C5-R-FINAL (later) | review only after Wave A + B + gates |
| P-01 | C5-S1-WAVE-A | Studio / Builder | Wave A | M-042 M-046 M-047 M-051 M-077..M-083 M-088(Studio) M-089 M-091 M-106 M-109 M-110 M-112 M-113 | `features/studio/**`, `builder.css`, studio tests | FINISHED, ARCHIVED | 10-09 16:37 | see merge commit | small | `agent/c5-s1-wave-a` | 45f4ca4 | MERGED into agent/c5-web (c5e4272); branch tip 7d46be1; 44 min, ~197k tokens | none | completed |
| P-02 | C5-S2-WAVE-A | Platform / Admin | Wave A | M-025(remainder) M-093 M-097(remainder) M-098 M-065 (keep screens separate, consolidate primitives) | `features/admin/**`, `apps/{platform,admin}`, admin/org tests | FINISHED, ARCHIVED | 10-09 16:37 | see merge commit | small | `agent/c5-s2-wave-a` | 45f4ca4 | MERGED into agent/c5-web (9b67da9); branch tip 16fb8ad; 21 min, ~163k tokens | none | completed |
| P-03 | C5-S3-WAVE-A | Shared UI / a11y / text / design system | Wave A | M-067 M-069 M-070 M-084 + shared part of M-060..M-063 (no M-068 codemod) | `packages/ui/**`, `packages/i18n/**`, `packages/company-ui/**`, ui tests | FINISHED, ARCHIVED | 10-09 16:37 | see merge commit | small | `agent/c5-s3-wave-a` | 45f4ca4 | MERGED into agent/c5-web (6842f65); branch tip 7168d4d; 18 min, ~138k tokens | none | completed |
| P-05 | C5-S1-BATCH2 | Studio / Builder second batch | Batch 2 (approved 10-09 after handoff 77e48d6) | M-051 M-078 M-079 M-080 M-081 M-082 M-083 M-089 M-109 M-112 | `features/studio/**`, `packages/ui/src/styles/builder.css`, Studio tests | FINISHED, ARCHIVED | 10-09 (after 77e48d6) | final report received | ~218k tokens, 45 min | `agent/c5-s1-batch2` (worktree `/Users/hoangluan/code/c5-batch2/s1`) | 77e48d6 | MERGED into agent/c5-web (72ad63a); tip a15246c | none | completed |
| P-06 | C5-S3-BRAND | Design system / XWEB brand (user brief 2026-10-09) | Brand | brand system: logo family, semantic tokens light/dark, backgrounds, banners, portal identity, BRAND_GUIDELINE.md | `packages/ui/**`, `packages/i18n/src/brand.ts`, assets, brand parts of shells | FINISHED, ARCHIVED | 10-09 22:3x (after 15bb3b7) | final report received | ~281k tokens, 35 min | `agent/c5-s3-brand` (worktree `/Users/hoangluan/code/c5-brand/s3`) | 15bb3b7 | MERGED into agent/c5-web (56801b2); tip c006077 | none | completed |
| P-04 | C5-R-FINAL | Read-only final review | Final | n/a | read-only | FINISHED, ARCHIVED (ran 10-09 after Wave B + full gate + real stack + cross-browser; ~288k tokens, 9 min; 2 P2 + 6 P3, no P0/P1; fixed in d603488) | - | - | - | none | - | - | none | - |

## Totals
- Entries: **21 = 15 historical (H-01..H-15) + 6 current-generation (P-01..P-06)**.
- ACTIVE: **0**. FINISHED: 10 historical (H-01..H-10) + P-01..P-06. FAILED: 0 as final state (H-05..H-08 failed several times on session/weekly limits and were resumed to completion; treated as STALE contexts). STOPPED: 5 (H-11..H-15). STALE: H-05..H-08.
- Duplicates among H-11..H-15 and the earlier agents: H-11 duplicates the Studio scope of H-05, H-12 of H-06, H-13 of H-07, H-14 of H-08, H-15 of H-02/H-09/H-10 (same roles, new generation). Their predecessors are FINISHED and merged, and H-11..H-15 produced nothing, so no work is lost or double-owned.

## Issue ownership (one active owner each; Wave A)
- S1 lane: M-042 M-046 M-047 M-051 M-077 M-078 M-079 M-080 M-081 M-082 M-083 M-088(Studio) M-089 M-091 M-106 M-109 M-110 M-112 M-113
- S2 lane: M-025(remainder) M-093 M-097(remainder) M-098 M-065
- S3 lane: M-067 M-069 M-070 M-084, shared part of M-060 M-061 M-062 M-063
- C5-L (serial, after Wave A): M-068, M-107, M-053; tooling review M-072 M-099 M-114 M-116; ledger; gates; real-stack; cross-browser
- Not C5 code (handoff / research): M-007 M-009 M-058 (C1), M-039 M-040 M-101 (C2), M-059 M-094 (backend), M-073 M-090 M-102 M-108 (C0), M-071 M-100 M-103 (research)

## File-scope conflict check (Wave A)
- S1 vs S2: no shared directory (`features/studio` vs `features/admin`). Shared hot files: `tests/builder/text-guard.test.ts` (ceilings may only go down; merge by taking the lower number), `README.md` (nobody edits in Wave A).
- S1 vs S3: `packages/ui/src/styles/builder.css` is S1's; `ui.css`, `factory.css`, `overlay.ts`, `focus.ts` are S3's. S1 must not edit them.
- S2 vs S3: S2 builds people-screen primitives locally in `features/admin/shared/`; S3 does not edit `features/admin/**`.
- Merge order proposed: S3 first (shared), then S2, then S1; C5-L resolves.

## Worktree cleanup (2026-10-09, before Wave A)
Rule applied to every old C5 agent worktree: branch HEAD is an ancestor of `agent/c5-web`, no valuable uncommitted change, no process or shell with its cwd/command inside it.
- Removed 11 agent worktrees (the ten checked earlier, plus `agent-a99ce337dd855d29b` once its two shells had exited). Its WIP (5 files) was superseded by d319531 (3 files identical to HEAD, 2 superseded by later edits) and was saved as a patch in the lead scratchpad before removal.
- `git worktree prune`: nothing stale left.
- Wave A worktrees `/Users/hoangluan/code/c5-wave-a/{s1,s2,s3}` were removed after their branches were merged (clean, ancestors of HEAD, no process inside); branches `agent/c5-s{1,2,3}-wave-a` kept.
- Branch refs `agent/c5-*` (33, including the three Wave A branches) are kept: every one is an ancestor of HEAD (no unique commits), so git history is intact.
- Not C5 agent worktrees, therefore preserved: the checkouts of other teams (`xweb-c0..c7`, `xweb-wire`, `xweb-v29`, `xweb-base`), `xweb-c5` (lead checkout), `xweb-c5-overlay`, the real-stack backend worktrees under `~/.xweb-e2e-stack/` (`c5e2e-ae` backs a running gradle process; the others are idle, belong to the e2e-stack tooling and are kept until C5-L decides) and the lead scratchpad `stack` worktree.
- Claude Agent Map UI history: no supported deletion mechanism known (AGENT_MAP_HISTORY_REMOVABLE: NO); not attempted.

## 2026-10-10 PL01 / Dynamic Organization / AD01 / C2 task
No coding agent was spawned (ACTIVE_AGENTS = 0). The isolated real stack `c5e2e-pl` of this task (containers, backend, portals, Studio) was started and stopped only through `docs/parallel/c5/e2e-stack.sh` and `tests/lib/owned-process-cli.mjs` (validated pid / start time / command; no kill by name or port) and removed with `down --infra --worktree`; ports 3001-3003 and 38180-38195 were free afterwards. The clean gate worktree was removed.
