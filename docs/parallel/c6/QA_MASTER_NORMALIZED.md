# QA_MASTER_NORMALIZED — one flat table over every C6 result (generated, do not edit by hand)

Generator: `harness/qa_master_normalize.py` · table: `QA_MASTER_NORMALIZED.tsv` (19 columns). It **adds** a normalised view next to `QA_MASTER.md` / `qa_master_state.json`; it rewrites no history: the 482 original CASE_IDs keep their last recorded status (a PASS stays a PASS), `NOTRUN` is spelled `NOT_RUN`.

* Total rows **912** = 482 original (batches 1–7) + 430 ingested from batches 8–11 (user-guide QA 104, RC wide regression 287, UI/UX regression and retest 39).
* Statuses are limited to PASS FAIL BLOCKED RETEST NOT_RUN GAP. A field whose source cannot be shown is `PROVENANCE_UNKNOWN`.

## Counts

| Batch | Rows | PASS | FAIL | BLOCKED | RETEST | NOT_RUN | GAP | RETEST_REQUIRED=YES | PROVENANCE_UNKNOWN |
|---|---|---|---|---|---|---|---|---|---|
| 1–7 original | 482 | 327 | 9 | 107 | 1 | 1 | 37 | 482 | 0 |
| 8 user-guide | 104 | 57 | 14 | 33 | 0 | 0 | 0 | 104 | 104 |
| 9 RC wide | 287 | 285 | 0 | 2 | 0 | 0 | 0 | 74 | 0 |
| 10 UI/UX 5cc230e | 24 | 4 | 18 | 2 | 0 | 0 | 0 | 24 | 0 |
| 11 UI/UX retest 40ee45b | 15 | 8 | 7 | 0 | 0 | 0 | 0 | 15 | 0 |
| **all** | **912** | **681** | **48** | **144** | **1** | **1** | **37** | **699** | **104** |

## Staleness rule (RETEST_REQUIRED)
Current build per plane when this table was written: backend of `integration/v2` = RC `62ce9697cd56` (`git diff 62ce9697cd56 integration/v2 -- backend` is empty); frontend = C5 final import `9f858c2ee5c1` (after the `5cc230e`/`40ee45b` candidates).
* Batches 1–7 were evaluated on `8e91172`; `integration/v2` has since changed 163 backend files (V30, recovery/persistence tests, …), so **every** one of those rows is `RETEST_REQUIRED=YES` even where its status is PASS (C6 rule: PASS needs evidence on the exact SHA — the old PASS is kept as history, not as current proof).
* Batch 8 (user guide) ran on a public build that could not be proven to equal any SHA → `LAST_TESTED_SHA`, `BACKEND_SHA`, `FRONTEND_SHA`, `PROVENANCE` are `PROVENANCE_UNKNOWN` and every row is `RETEST_REQUIRED=YES`.
* Batch 9 (RC `62ce9697cd56`): backend/API rows are current (`RETEST_REQUIRED=NO`); portal-routing and public-host rows touch the frontend, which has changed since → `YES`.
* Batches 10–11 (UI): the frontend moved to the C5 final import after both candidates → `YES`.

## How the UI/UX batches are indexed
The 866 (5cc230e) / 874 (40ee45b) individual route×viewport cases stay in `evidence/ui-ux-regression/<sha>/cases.json` (raw, unfiltered: it still contains the documented harness false positives, so its FAIL count is higher than the reports' filtered numbers). The master carries what the reports signed: one `UXB-*` row per bug (`bugs-final.tsv`) and one `UXG-*` row per gate item, each pointing at its evidence file.

## Columns
`CASE_ID` · `DOMAIN` · `SEVERITY` · `STATUS` · `EVIDENCE_CLASS` · `LAST_TESTED_SHA` · `LAST_TESTED_DATE` · `AUTOMATION` · `OWNER` · `BLOCKER` · `STACK_CLASS` · `RETEST_REQUIRED` · `EVIDENCE_PATH` · `BACKEND_SHA` · `FRONTEND_SHA` · `INTEGRATION_SHA` · `ENVIRONMENT` · `PROVENANCE` · `BATCH`

Original CASE_ID prefixes: C0 87, C1 102, C2 66, C3 65, C4 76, C5 24, E2E 20, NF 16, PERF 10, REC 16
