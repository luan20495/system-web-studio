> **SUPERSEDED_BY:** `docs/QA_FINAL.md` - historical document (moved from `docs/parallel/c5/audit/tools/wave-briefs/COMMON.md`), kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# C5 Wave A — common rules (read fully, ~2 min)

You are a fresh, isolated implementation agent for the XWeb frontend (Next.js, three portals Platform/Admin/Studio). You work ONLY inside your own git worktree (absolute path given in your task). Never `cd` out of it, never touch the shared checkouts `/Users/hoangluan/code/HBL` or `/Users/hoangluan/code/xweb-c5`.

## Hard rules
- Read `CLAUDE.md` and `docs/FRONTEND_ONBOARDING.md` first. Reply/commit text in English or Vietnamese; UI strings stay Vietnamese.
- Do NOT touch `backend/`, `docs/contracts/**`, Flyway migrations, GitHub Actions. Need a backend/contract change -> write it in `docs/parallel/c5/audit/HANDOFFS_FRONTEND_AUDIT.md` (append) and move on. Dynamic Organization stays NOT_READY.
- Stay inside your issue IDs and the files they name. If you must edit a file outside your area (shared hot file), keep the diff minimal and say so in the commit message. Never run codemods across the repo.
- Process safety: NEVER `pkill`, `killall`, kill by port, `kill $(lsof ...)`, or kill by name. Run browser tests only through `node tests/browser/harness-server.mjs run -- node tests/browser/<spec>.spec.mjs` (picks a free port, owned process). Only stop processes you started, via `node tests/lib/owned-process-cli.mjs stop --state <file>`. Foreign processes (ports 3201-3203, 3301-3303, Docker, gradle) must survive.
- `npm run test:unit` DELETES `.test-build`. Never run it while a spec is running; after it, run `node tests/browser/build-harness.mjs` before any spec. Run unit + specs strictly one after another.
- Chrome for specs: `export CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"`.
- Never commit `.tmp-stage/`, `.next*`, `.test-build`, `.run/`. Commit only files you changed: `git add <paths>` (never `git add -A`). Never push. Never force anything. Do not merge or rebase onto other branches.
- Evidence labels: harness results are "HARNESS, NOT REAL BACKEND". Never call them real E2E.
- UI must keep: native confirm/prompt/alert not used (use shared `confirm/prompt/toast`), shared overlay stack (`packages/ui/src/overlay.ts`), `useAction` double-submit guard, `LoadGate/ErrorState`, text library `packages/i18n` (ratchet guards in `tests/builder/text-guard.test.ts` may only go DOWN), ui.css guard (`.xp-*` or `.shell`-scoped selectors only, no `:where`), RTL ratchet (physical CSS decl cap 97), breakpoint ratchet. Prefer logical CSS properties.
- Accessibility bar for anything you touch: axe 0 critical/0 serious, targets >= 24px, focus visible + restored after dialogs, labels, no keyboard traps.
- No speculative optimisation: performance changes only with a measurement in the commit message.
- Regression: do not reopen fixed items (M-126/127/129 are closed; Studio P1 fixes M-001..M-005 must not regress).

## Process / budget
- Small commits, one coherent change each, with tests. Run only TARGETED tests while working (1-4 min each). The lead runs full gates.
- Typecheck before each commit: `npx tsc --noEmit -p tsconfig.json && npm run typecheck:apps`.
- STOP and report (do not keep trying) when: 20 min without meaningful progress; the same failing command twice; the same fix failed twice without new evidence; a test takes > 2x its normal time; you need a file owned by another agent; a backend contract is missing; your context nears ~300k tokens.
- Checkpoint report format (send when you have your first commit, ~30 min in, and at the end):
  AGENT / CURRENT_ISSUE / COMMIT (sha) / FILES_CHANGED / TARGETED_TEST / PASS|FAIL / BLOCKER
- Final report: per issue ID -> FIXED (sha, test name) | NOT_NEEDED (evidence) | BLOCKED (reason) | DEFERRED (reason). Also list the ledger status lines you recommend (the lead edits the ledger; you must NOT edit `master-ledger.json` / `MASTER_ISSUE_LEDGER.md`).
- Time budget: aim to finish in ~90 minutes of wall clock; if the queue is not finished, commit what is coherent and report what is left.

## Ledger
Your rows are in the attached `<ID>-rows.md` (full ledger: `docs/parallel/c5/audit/MASTER_ISSUE_LEDGER.md`, audit sources `docs/parallel/c5/audit/S1-studio.md`, `S2-platform-admin.md`, `S3-design-system.md`, `R-*.md`). Verify each row against the current code before acting: if already fixed, report NOT_NEEDED with evidence instead of changing code.
