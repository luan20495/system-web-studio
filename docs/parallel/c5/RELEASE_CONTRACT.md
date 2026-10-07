# C5 — publish / rollback / unpublish on the C2 contract

Authoritative sources (nothing is invented beyond them): `docs/parallel/c2/PUBLISH_API_CONTRACT.md` and `PublishApiContractTests` (+ `PUBLISHED_RUNTIME_TOPOLOGY.md` for the public side), **`fix/c2-v3 @ 8d40218`**.
**Integration status:** the contract is NOT in `integration/v2` (head `ef0d890` at the time): merging `fix/c2-v3` conflicts with the older C2 slice C0 imported (`ReleaseService.kt` add/add, `SiteService.kt`, `ReleaseCasTests.kt`, `SiteRuntimeConfigTests.kt`, `BLOCKERS.md`, `BOARD.md`, `BATCH2_HANDOFF.md`). All real runs below therefore use a backend built from `8d40218` itself (`E2E_BASE_REF=8d40218 docs/parallel/c5/e2e-stack.sh up`). C0 must integrate it (handoff H-C0-09). C5 changed no backend file.

## 1. What is implemented

| Area | Implementation | Code |
|---|---|---|
| PUBLISH | `POST …/publish`, body **exactly** `{visibility, expectedRevision}`, `Idempotency-Key` required (8–120 of `A-Za-z0-9_.:-`, refused before sending otherwise) + `X-XSRF-TOKEN`; 202 + deployment, polled via `GET …/deployments/{id}` | `api.publish`, `publishBody` |
| ROLLBACK | `POST …/site/rollback`, body `{deploymentId, expectedActiveDeploymentId?}` (omitted, never `null`, when unknown), optional key; **synchronous** 200 + SiteInfo | `api.rollbackSite`, `rollbackBody` |
| UNPUBLISH | `DELETE …/site[?expectedActiveDeploymentId=]`, **no body**, optional key; 200 + SiteInfo; already offline = 200 | `api.unpublishSite`, `unpublishQuery` |
| SITEINFO | `pointerVersion: number` (displayed, labelled "chỉ để quan sát, không gửi lại"; **never sent**, never used for optimistic concurrency) and `operation: null \| {kind: PUBLISH\|ROLLBACK\|UNPUBLISH, deploymentId\|null, since, leaseUntil}` | `SiteInfo`, `SiteOperation` (`packages/types`) |
| STATUSES | the closed list of nine incl. `ROLLING_BACK`; `isTerminal` = {RUNNING, FAILED, ROLLED_BACK} (unchanged), `isBusy` = the rest (unknown ⇒ busy), `isSuccess` = RUNNING only, labels, polling stop = terminal; `ROLLBACK_FAILED` / `ROLLBACK_OFFLINE` are event names only | `release.ts` |
| ERRORS | `409 SCOPE_BUSY` (retryable, `Retry-After` read into `ApiError.retryAfterSeconds`, holder named, countdown), `409 ROLLBACK_STALE` (not retryable; reload; nothing changed), `409 IDEMPOTENCY_KEY_REUSED` (new key), `409 IDEMPOTENCY_IN_PROGRESS`, `REVISION_CONFLICT`, `DEPLOYMENT_NOT_RESTORABLE`, `ROLLBACK_FAILED`, `SITE_NOT_FOUND`, `NO_VERSION`, `PUBLIC_PUBLISH_DISABLED`, 403, 429, unknown outcome | `explainReleaseError` |
| STALE_PUBLISH | **not an HTTP error**: a deployment that ends `FAILED` with `error "[STALE_PUBLISH] …"`; shown as a failed deployment with its own explanation and "Xuất bản lại" | `explainFailedDeployment` |
| IDEMPOTENCY | one key per LOGICAL request (`ReleaseKeyBook`): same payload ⇒ same key (a retry after a lost answer is a replay), different payload ⇒ new key (no 409 by construction), `rotate()` after an outcome the user repeats (a replay would only return the old outcome) | `ReleaseKeyBook` |
| BUSY UI | `SiteInfo.operation != null` ⇒ banner (kind), Publish / rollback / unpublish locked, SiteInfo polled while shown (2.5 s) and refreshed slowly when idle (8 s) so an operation started elsewhere shows up; failed polling stops after 5 and offers a manual reconnect. UX only: the server still answers SCOPE_BUSY | `ReleaseModal.tsx` |
| ROLLBACK UX | candidates = RUNNING, not mock, not the served one (a ROLLED_BACK release is not offered); pending state; request carries `expectedActiveDeploymentId` = the release the dialog saw; **done only after the 200 AND a SiteInfo that serves the target**; a lost answer is reconciled from SiteInfo; refresh in the middle shows the busy scope and follows the server | `ReleaseModal.tsx` |
| PERMISSION | publish, rollback and unpublish = `APP_PUBLISH` (`canPublish`/`canRollback`), disabled with the reason; `APP_EDIT` never stands in. The server answers 403 regardless | `canonical.ts`, `PublishModal canPublish` |
| LAYOUT | a long release history scrolls INSIDE the dialog (found by the real run: the flex-centred dialog clipped the rollback buttons) | `factory.css` |

## 2. Mismatches with the task text (the contract wins)

1. **`SiteInfo.operation`** was described as the string `null/PUBLISH/ROLLBACK/UNPUBLISH`; the contract says an **object** `{kind, deploymentId, since, leaseUntil}` or `null` (`kind` has the three values). Implemented as the contract.
2. **`ROLLING_BACK → ROLLED_BACK`**: the contract says `ROLLING_BACK` "leaves only to `FAILED`" (a publish whose switch is being undone) and `ROLLED_BACK` is the terminal status of the release an operator rolled **away from**. Studio shows exactly that. `ROLLING_BACK → FAILED` is exercised in the browser harness (it cannot be produced on a real stack on demand); `ROLLED_BACK` is verified for real (E2E-P03).
3. **Permission name**: the contract says `PROJECT_PUBLISH` (the storage name of `APP_PUBLISH`); the UI uses `APP_PUBLISH` through the documented alias. No mismatch in meaning.
4. **`SiteInfo.operation = UNPUBLISH` on a real backend**: the scope is held for an instant; 12 tight-polling attempts per run never observed it. Covered by the browser harness only (stated in the E2E-P09 fact line).

## 3. Public data / `apiBase` — nothing implemented (by contract)

C2's topology document says a published page **cannot** call the Data Runtime today (CSP `sandbox` without `allow-same-origin`, `@company/app-sdk` uses `credentials: "omit"`, the Data Runtime authenticates with the Studio session cookie + CSRF and its CORS excludes the sites origin, the sites gateway has no route to `app-runtime`). `apiBase` exists only in `__factory/config.json` of **code apps** (null until `app.sites.data-api-base` is set) and is consumed by C2's SDK. The auth model "published browser → Data Runtime" is NOT frozen (C0 + C1 + C3). So C5 added no `apiBase` consumption, no token, no header; C5's existing `/runtime-config.json` loader is unrelated to this contract (H-C2-04). No public-data E2E is claimed.

## 4. Evidence

| Layer | Result |
|---|---|
| Unit/mock `tests/builder/release.test.ts` | 22 tests: statuses, STALE_PUBLISH, exact requests (publish/rollback/unpublish), key validation before sending, no pointerVersion/tenantId anywhere, SiteInfo shape, busy banners, every error code, key lifecycle, candidates |
| Browser harness `tests/browser/release.spec.mjs` | 56 checks: idle, operation PUBLISH/ROLLBACK/UNPUBLISH busy + polling + auto-recovery, every status incl. ROLLING_BACK (busy, not success, polls, → FAILED), ROLLED_BACK, STALE_PUBLISH, key lifecycle (double click, lost answer replay with the same key, KEY_REUSED ⇒ new key, publish again ⇒ new key), rollback pending/stale/scope-busy countdown/not-restorable/failed/lost answer, unpublish confirm and busy, APP_PUBLISH, failed polling ⇒ reconnect, no console errors |
| Real backend (`8d40218`) E2E-P01…P09 | see the matrix; PASS 9/9 in three consecutive runs (fixed order, shuffle seeds 5 and 17) |

Real flows ↔ the 15 requested items: 1 publish success = P01 · 2 concurrent publish = P02 · 3 scope busy = P04 · 4 rollback success = P03 · 5 ROLLING_BACK→ROLLED_BACK = see §2.2 (ROLLED_BACK real in P03; ROLLING_BACK in the harness) · 6 refresh during rollback = P05 · 7 rollback stale = P06 · 8 idempotency key reused = P07 · 9 stale publish terminal FAILED = P08 · 10 unpublish = P09 · 11 operation=PUBLISH = P04 · 12 operation=ROLLBACK = P05 · 13 operation=UNPUBLISH = harness only (§2.4) · 14 idle null = P01/P03 · 15 pointerVersion observable, never sent = P01/P03/P09 (every request scanned).
Fault injection (no backend change, nothing stubbed): the artifact store container is **paused** the moment a publish reaches DEPLOYING (that step holds the scope; the lease stays alive) or while a rollback restores; the render worker is **SIGSTOPped** while a publish builds. Hooks `E2E_PAUSE/RESUME_STORE_CMD`, `E2E_PAUSE/RESUME_RENDER_CMD` (wired by `e2e-stack.sh`); without them P04, P05, P08 are BLOCKED. The stack needs `RATE_LIMIT_PUBLISH_MAX` raised (default 10/min/user; the script sets 500).
