# C5 — AGENT REGISTRY (single source of truth for agent ownership)

Maintained by C5-L. Last reconciled: 2026-10-09 (Asia/Saigon), repo head `a9fbdb0` (branch `agent/c5-web`).
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
**0 active agents.** `ListAgents` shows no other reachable session. Wave A agents are PLANNED (below), not started.

## Registry

| AGENT_ID | NAME | ROLE | WAVE | ISSUE_IDS | FILE_SCOPE | STATUS | STARTED_AT | LAST_PROGRESS_AT | CONTEXT_SIZE | BRANCH | HEAD | OUTPUT_STATUS | DUPLICATES | STOP_REASON |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| H-01 | C5-S4 process lifecycle cleanup | Tooling: owned-process lifecycle | early (10-08) | process-safety tooling (pre-ledger) | `tests/lib/owned-process*.mjs`, `scripts/` | FINISHED | 10-08 15:21 | terminal | UNKNOWN | `agent/c5-s4-tooling`, `agent/c5-s4-tooling-2` | 09da206 | MERGED (ancestor of HEAD) | none | completed |
| H-02 | C5-R architecture and UX review | Read-only review | early | (pre-ledger) | read-only | FINISHED | 10-08 15:21 | terminal | UNKNOWN | none | n/a | MERGED (no code) | none | completed |
| H-03 | C5-S3 shared UI diff review | Read-only review | early | (pre-ledger) | read-only | FINISHED | 10-08 15:21 | terminal | UNKNOWN | none | n/a | MERGED (no code) | none | completed |
| H-04 | C5-S3 review of mobile switch | Read-only review | early | (pre-ledger) | read-only | FINISHED | 10-08 17:26 | terminal | UNKNOWN | none | n/a | MERGED (no code) | none | completed |
| H-05 | C5-S1 Studio full audit | Studio audit, then resumed as wave1-3 | audit + remediation | Studio rows of the ledger | `features/studio/**` | FINISHED (also hit session + weekly limit while resumed; stale context) | 10-08 23:09 | terminal | UNKNOWN (large) | `agent/c5-s1-audit`, `-fix`, `-wave2`, `-wave3` | b9f6017 | MERGED (all branches ancestors) | none | limit reached, then completed; ARCHIVED |
| H-06 | C5-S2 Platform/Admin full audit | Platform/Admin audit, then wave1-2 | audit + remediation | Admin/Platform rows | `features/admin/**` | FINISHED (same limit history; stale) | 10-08 23:09 | terminal | UNKNOWN (large) | `agent/c5-s2-audit`, `-fix`, `-wave1`, `-wave2` | 1d8f89e | MERGED; worktree WIP (5 files) is superseded by d319531, saved as patch | none | ARCHIVED |
| H-07 | C5-S3 design system and text audit | Shared UI / text, then wave1/1b/1c/2 | audit + remediation | shared UI / text rows | `packages/ui/**`, `packages/i18n/**` | FINISHED (same limit history; stale) | 10-08 23:10 | terminal | UNKNOWN (large) | `agent/c5-s3-audit`, `-fix`, `-wave1`, `-wave1b`, `-wave1c`, `-wave2` | 1364025 | MERGED | none | ARCHIVED |
| H-08 | C5-S4 performance and tooling audit | Perf / tooling / real-stack baseline | audit + remediation | M-053, M-072, M-099, M-107, M-114, M-116, S4 baselines | `scripts/`, `tests/browser/lib/**` | FINISHED (same limit history; stale) | 10-08 23:10 | terminal | UNKNOWN (large) | `agent/c5-s4-perf`, `-wave1..4` | 43eb0f3 | MERGED | none | ARCHIVED |
| H-09 | C5-R architecture and onboarding review | Research / architecture | audit | R rows, `FRONTEND_ONBOARDING.md` | docs only | FINISHED | 10-08 23:10 | terminal | UNKNOWN | `agent/c5-r-arch` | n/a | MERGED | none | completed |
| H-10 | C5-R2 security, i18n, theme audit | Research | audit | R2 rows | docs only | FINISHED | 10-08 23:11 | terminal | UNKNOWN | `agent/c5-r2-sec`, `agent/c5-r2-review` | 3245952 | MERGED | none | completed |
| H-11 | C5-S1-REMEDIATION Studio P2/P3 | Remediation (opus) | 10-09 remediation | Studio P2/P3 | `features/studio/**` | STOPPED by C5-L at the pause order | 10-09 15:52 | none (no commit) | small | `agent/c5-s1-rem` | a9fbdb0 (empty) | NONE produced | REPLACED BY C5-S1-WAVE-A | user pause / estimate-first |
| H-12 | C5-S2-REMEDIATION Platform/Admin + text pass | Remediation (opus) | 10-09 remediation | Admin/Platform | `features/admin/**` | STOPPED | 10-09 15:52 | none | small | `agent/c5-s2-rem` | a9fbdb0 (empty) | NONE | REPLACED BY C5-S2-WAVE-A | same |
| H-13 | C5-S3-REMEDIATION shared UI / design system | Remediation (opus) | 10-09 remediation | shared UI | `packages/ui/**` | STOPPED | 10-09 15:53 | none | small | `agent/c5-s3-rem` | a9fbdb0 (empty) | NONE | REPLACED BY C5-S3-WAVE-A | same |
| H-14 | C5-S4-VERIFY final real-stack and matrix verification | Verification (opus) | 10-09 remediation | verification | scripts | STOPPED; its two owned processes are already GONE | 10-09 15:53 | none | small | `agent/c5-s4-verify` | a9fbdb0 (empty) | NONE | REPLACED BY C5-L (runs S4 scripts itself) | same; no dedicated S4 agent by default |
| H-15 | C5-R-REVIEW read-only regression review since 9273b93 | Review (opus) | 10-09 remediation | review | read-only | STOPPED (started too early over unstable code) | 10-09 15:53 | none | small | `agent/c5-r-review` | 72f4d0c | NONE | REPLACED BY C5-R-FINAL (later) | review only after Wave A + B + gates |
| P-01 | C5-S1-WAVE-A | Studio / Builder | Wave A | M-042 M-046 M-047 M-051 M-077..M-083 M-088(Studio) M-089 M-091 M-106 M-109 M-110 M-112 M-113 | `features/studio/**`, `builder.css`, studio tests | PLANNED (not started) | - | - | - | `agent/c5-s1-wave-a` | a9fbdb0 | - | none | - |
| P-02 | C5-S2-WAVE-A | Platform / Admin | Wave A | M-025(remainder) M-093 M-097(remainder) M-098 M-065 (keep screens separate, consolidate primitives) | `features/admin/**`, `apps/{platform,admin}`, admin/org tests | PLANNED | - | - | - | `agent/c5-s2-wave-a` | a9fbdb0 | - | none | - |
| P-03 | C5-S3-WAVE-A | Shared UI / a11y / text / design system | Wave A | M-067 M-069 M-070 M-084 + shared part of M-060..M-063 (no M-068 codemod) | `packages/ui/**`, `packages/i18n/**`, `packages/company-ui/**`, ui tests | PLANNED | - | - | - | `agent/c5-s3-wave-a` | a9fbdb0 | - | none | - |
| P-04 | C5-R-FINAL | Read-only final review | Final | n/a | read-only | PLANNED (after Wave B + full gate + real stack) | - | - | - | none | - | - | none | - |

## Totals
- Entries: **15 historical + 4 planned = 19** (the 15 in the Agent Map are the historical ones H-01..H-15).
- ACTIVE: **0**. FINISHED: 10 (H-01..H-10). FAILED: 0 as final state (H-05..H-08 failed several times on session/weekly limits and were resumed to completion; treated as STALE contexts). STOPPED: 5 (H-11..H-15). STALE: H-05..H-08.
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

## Old worktree cleanup (2026-10-09)
Removed 10 agent worktrees after verifying: HEAD is an ancestor of `agent/c5-web` HEAD, no tracked changes, no non-build untracked files, no live process with its cwd there. Preserved: `agent-a99ce337dd855d29b` (branch `agent/c5-s2-wave2`): two shells have it as cwd and it holds uncommitted WIP; the WIP is superseded by d319531 and was saved to the scratchpad as a patch. Branch refs were kept (all ancestors of HEAD).
