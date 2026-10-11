> **SUPERSEDED_BY:** `docs/QA_FINAL.md` - historical document (moved from `docs/parallel/c5/audit/tools/wave-briefs/S1-task.md`), kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# C5-S1 — Studio / Builder (Wave A)
Worktree: __WT__ (branch agent/c5-s1-a, base a9fbdb0). Install already done (`npm ci`).
Scope (verify each in code first): M-042 M-046 M-047 M-051 M-077 M-078 M-079 M-080 M-081 M-082 M-083 M-088 (Studio part: RuntimeDrawer secret kept in React state; Admin part is done) M-089 M-091 M-106 M-109 M-110 M-112 M-113.
Files/area: `features/studio/**`, `packages/ui/src/styles/builder.css`, `lib/schema-preview.ts` (hot file: coordinate nothing, keep diff minimal), `tests/browser/studio-wave3.spec.mjs` (append your checks at the end), `tests/browser/studio-app/*`, `tests/builder/*`.
Do NOT edit: `features/admin/**`, `packages/ui/src/{overlay,focus}.ts`, `packages/ui/src/styles/ui.css` (S3/S2 own those), `tests/builder/text-guard.test.ts` ceilings except lowering them.
Priority order: P2 first (M-042, M-046, M-047, M-106), then P3 (M-109, M-110, M-088, M-089, M-091, M-112, M-113, M-051, M-082, M-077..M-081, M-083).
Notes: M-046 / M-109 / M-110 need a MEASUREMENT before and after (see `docs/parallel/c5/audit/S4-performance-tooling.md` for the tool and numbers; M-046: canvas scroll drops frames at 1004 sections). If the measurement shows no real problem, report NOT_NEEDED with numbers. M-042: preview is sandboxed non-interactive; only do the client-side half (copy/affordance), the renderer half is C2 -> handoff. M-094 stays a handoff (not yours). Do not reopen M-126/M-127/M-129.
Targeted tests: `node tests/browser/build-harness.mjs` then `node tests/browser/harness-server.mjs run -- node tests/browser/builder.spec.mjs` (also studio-wave3, studio-p1, studio-wave2, data-binding, datasources, hooks as touched), unit: `npm run test:unit` (alone, then rebuild harness).
