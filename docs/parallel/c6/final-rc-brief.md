# C6 FINAL RC QA — shared brief for every workstream (read this first)

Role: **C6, independent QA**. Test the deployed product; **never modify production code** (`backend/`, `app/`, `apps/`, `packages/`, `features/`, `components/`, `lib/`, migrations, `docs/contracts/**`). Your own scripts live in `docs/parallel/c6/harness/final-<name>.mjs` (worktree `/Users/hoangluan/code/xweb-c6`, branch `agent/c6-qa`; do NOT commit, do NOT push, do NOT touch git state).

## What is under test (record it in every report)
| | |
|---|---|
| INTEGRATION (C7 source of truth) | `edf32dfe187a25ee159339c22be2ff4c1c093df4` (integration/v2 has since moved to `fad4a7b4356f…`: docs/tests only) |
| DEPLOYED PRODUCT SHA | `bc5c47f292d00846c106669b09679a6fc36daef6` |
| STACK `c0rc` | backend worktree `953d17e53d05` (= bc5c47f + docs/tests/tooling), portals built from checkout `fad4a7b4356f` (product files identical to bc5c47f — verified by `final-env-check.mjs`), real PostgreSQL, Redis, RabbitMQ, MinIO, sites gateway; `ORGANIZATION_PERSISTENCE_ENABLED=true`, `PUBLISH_CONFIGS_ENABLED=true`, `SITES_PUBLIC_DATA_ENABLED=true`; NO mock |
| URLs | API `http://127.0.0.1:47300` · Platform `:47308/platform` · Admin `:47309/admin` · Studio `:47307/studio` · Sites gateway `:47305` (same-origin `/api` proxy on each portal) |
| BUILD_IDs | Platform `2GuhCrW5Nzpw91J-27pgg` · Admin `-Eg7ur7xVaYfyHAOTtq5I` · Studio `cfrpHRfq45k1DWtHqrauH` |
| NOT to be used | the public API release `1006cbf441f6` (old). Public portals (`https://platform|admin|studio.toolsmcp.uk`) only for visual/deployment proof |

## Environment
```bash
cd /Users/hoangluan/code/xweb-c6 && . docs/parallel/c6/harness/final-env.sh     # exports API, FINAL_*, SA_USER, SA_PASSWORD, FINAL_OUT, UITOOLS — prints nothing, never echo a password
node docs/parallel/c6/harness/final-<yours>.mjs
```
* `harness/final-lib.mjs` (read it): `S` (cookie+CSRF session), `mkUser`, `superAdmin`, `recorder(name)` → `rec(id, area, desc, expected, actual, ok, {cls, owner, note, evidence})`, `save()` writes `$FINAL_OUT/<name>.json|.tsv`. `ok === null` = BLOCKED. Copy the style of `harness/rc-sec.mjs` / `rc-data.mjs` (independent matrices with explicit expected-vs-actual).
* Test source (read-only reference; product == deployed): `/Users/hoangluan/code/xweb-c6-final` (has `node_modules` symlink; C5's flows in `tests/e2e-real/flows`, UI helpers in `tests/e2e-real/lib/ui.mjs`, contracts in `docs/contracts/**`, `docs/parallel/c0/ORGANIZATION_API_CONTRACT_FOR_C5.md`, `docs/API_CONTRACT.md`, `docs/parallel/c1/*`).
* Browser: Playwright-core from `/Users/hoangluan/code/HBL/node_modules/playwright-core` (1.63). Chromium = system Chrome `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome` (155). WebKit = Playwright's (launches). **Firefox does NOT launch** (`Could not find profile folder`; already tried TMPDIR + persistent context) → `BLOCKED_TOOLING`, never PASS, never a product FAIL.
* Axe: `$UITOOLS/node_modules/axe-core` (4.14), pixelmatch, pngjs; the previous UI harness is `harness/ui-ux.mjs`, `ui-retest.mjs`, `ui_analyze.py`, `ui-seed.mjs`, `ui-sheet.mjs` (target ports there are old: parametrise them).

## Rules (from the final-QA order)
* Do not stop after the first failure; capture evidence for every failure; continue other independent flows.
* **Backend boundary is the final authority.** A hidden button is not authorization proof: every "denied" claim must also be proven at the API (and every "allowed" claim at the API).
* Do not convert BLOCKED_TOOLING into PASS. No fake success: if a step cannot be executed say BLOCKED with the exact reason.
* Fixtures only through product routes (invite → activate), names prefixed `c6f-`, random per-run passwords, **no SQL writes, no test hooks, no DB edits**. Read-only SQL via `docker exec c0rc-pg psql …` is allowed to confirm persistence only (label it). Disable accounts you created at the end when the product allows. Never print/log a password, token or secret.
* **Machine is shared. NEVER** `pkill`, `killall`, kill by port, `kill $(lsof …)`, stop/restart any container, backend, portal or process you did not start yourself. Do not touch other stacks (`c0demo`, `c5e2e-*`, `c6rc`, public `hblpub-*`). Only pids you started (detached process group + recorded start time) may be signalled by you. Recovery/restart tests belong to the coordinator, not to you.
* Run long things with a timeout; tell the user nothing about secrets.
* The only allowed writes: your scripts, `docs/parallel/c6/evidence/final-rc-bc5c47f292d0/**` (`$FINAL_OUT`), temp files under the scratchpad.

## Evidence classification (put it in every row/artifact)
`HARNESS` (fake transport / in-memory) · `REAL_STACK` (real portals/API observed, no business write) · `REAL_BACKEND_E2E` (real browser or HTTP → real API → PostgreSQL) · `MANUAL` (judged by eye from screenshots). Screenshots → `$FINAL_OUT/<name>/shots/*.png` (not committed to git).

## Defect format (one block per defect)
`ID` (FQ-<area>-NN) · `SEVERITY` (P0 data/security breach, privilege escalation, data loss · P1 core journey broken / wrong authorization / false success · P2 wrong behaviour with workaround, a11y serious, major visual · P3 minor) · `OWNER` (C1 IAM/Tenant/Auth/Permission · C2 Publish/Release/Deployment · C3 DB/Data/Dynamic-Org persistence · C4 Action/Workflow/Queue · C5 Frontend/UX/test-flow assertion · C0 Integration/Env/Proxy/Gateway/Deployment) · `BUSINESS_FLOW` · `TECH_FLOW` · `EXPECTED` · `ACTUAL` · `EVIDENCE` (file + row/screenshot) · `REPRODUCIBLE` (YES/NO, tried how many times). Before calling something a product defect, rule out your own harness error (wrong route, missing header, rate limit, state from a previous run) and re-run once on a fresh fixture.

## Final message you must return (concise, ≤ 120 lines)
1. Files written (scripts, evidence dir). 2. Result table: area → counts PASS/FAIL/BLOCKED with the evidence class. 3. Every defect in the format above. 4. Every BLOCKED item with the exact reason. 5. Anything you could not run and why. 6. Honest caveats (what the evidence does NOT prove). No raw dumps, no secrets.
