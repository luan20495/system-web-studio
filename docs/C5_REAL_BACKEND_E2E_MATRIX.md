# C5 — Real-backend E2E matrix

Owner: C5 (Studio / Frontend / UX / browser E2E) · Baseline: `integration/v2 @ f894cc6` · Branch: `agent/c5-web`
Suite: `tests/e2e-real/` · Runbook: `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md` · Handoffs: `docs/parallel/c5/HANDOFF_*.md`

## Honest status of this document

**No flow below has been run against a backend.** The C5 cloud sandbox has no JDK, no Docker and no PostgreSQL/Redis/RabbitMQ, so the integration stack cannot start there.
The suite itself is written, type-checked (`node --check`) and self-tested (`node tests/e2e-real/selftest.mjs`, 6/6 — it proves the runner answers **exit 2 NOT RUN** when there is no backend, the backend is unreachable or not the platform API, local login is off, or fixtures cannot be created; it never answers PASS).
Therefore the `Status` column only ever says **NOT RUN** (the flow has a real body and needs the stack) or **BLOCKED** (the flow cannot run on this baseline; owner and reference given). **PASS 0/14.** The status becomes PASS only from `.run/e2e-real/report-*.json` of a real run (the runner writes it).

Status vocabulary: `NOT RUN` = real assertions exist, stack required · `BLOCKED` = skeleton or evidence-only body, throws `Blocked(owner, reason, ref)`; never counted as PASS · `PASS` / `FAIL` = only from a run report.

## Matrix

Fixture legend: **F-core** = per-run workspaces A and B, `adminA` (WORKSPACE_ADMIN in A), `adminB` (WORKSPACE_ADMIN in B), `viewerA` (VIEWER in A, project member), project A (with a secret description) and project B, all created through the product API with random per-run passwords and deleted/disabled by `cleanup()`. **F-def** = F-core plus the typed definition ops `ADD_ACTION e2e-notify` (NOTIFY) and `ADD_WORKFLOW_REF e2e-wf` (END-only) on project A.

| ID | Flow | UI Steps | Real APIs | Fixture | Expected persistence/side effect | Browser assertion | Dependency | Owner | Status |
|---|---|---|---|---|---|---|---|---|---|
| E2E-01 | Load project/page | Log in as adminA at `/login`; open `/projects/{A}` by URL | `POST /auth/login`, `GET /auth/me`, `GET /workspaces/{w}/projects/{p}`, `GET …/schema` | F-core | none (read) | schema answered 200; canvas iframe rendered; project name and persisted first section on screen; no console/page errors | stack up, local login | C5 (C1 auth, C2 schema) | NOT RUN |
| E2E-02 | Edit → save → reload | Select first section; type a marker in the inspector; click "Lưu thay đổi"; reload | `PATCH …/schema` (`expectedRevision` + ops), `GET …/schema`, `GET …/versions` | F-core | revision +1, new version row, marker in persisted schema (read back by a second session) | PATCH 200; top bar "Đã lưu"; after reload canvas shows marker | stack up | C5 (C2 schema/versions) | NOT RUN |
| E2E-03 | Workspace isolation A/B | adminA and adminB each open their project list | `GET /workspaces/{w}/projects[/{p}]`, `PATCH …/schema` as the other tenant | F-core | A's revision unchanged by B's attempts | each list shows only own project; cross reads 404 (no existence/data leak), cross list 403/404, cross write 403/404 | stack up | C1 (tenant), C5 | NOT RUN |
| E2E-04 | Authorized user reaches a valid resource | adminA and viewerA log in, open project A by direct URL, refresh | `GET /auth/me`, `GET …/projects/{p}`, `GET …/schema`, member add (fixture) | F-core + viewer membership | none | not sent to `/auth/no-access`; canvas; viewer sees read-only notice, admin does not; refresh keeps project | stack up; project member routes | C1, C5 | NOT RUN |
| E2E-05 | Forbidden UX, no leak | adminB opens A's project URL; viewerA opens it and tries to edit/test | `GET/PATCH …/schema` as outsider/viewer | F-core (+ F-def for the Test-panel check) | A unchanged | outsider: no canvas, plain "not found/forbidden" text, A's name/description in no API body or on screen; viewer: PATCH 403, read-only notice, save disabled, "Chạy thử" disabled with a reason | stack up | C1, C5 | NOT RUN |
| E2E-06 | Configure DataSource/Query | Open Data panel; verify the UI states honestly that configuration is not available | none exist | F-core | would need a source + credential + query | evidence only: UI shows NOT_READY, never a fake configuration | **No management API** for sources / credentials / queries / bindings (`B-C0-W-03`); credentials are SecretsCrypto ciphertext, a fixture cannot honestly seed one | C0 (C3 data) | BLOCKED |
| E2E-07 | TEST-binding query | Test panel → "Chạy thử" a read query; see rows | `POST …/app-runtime/queries/{id}/run` `{mode:"TEST"}` | Operator pre-seeded project (`E2E_DATA_*`: workspace, project, query, user with APP_EDIT + QUERY_EXECUTE) | none (read) | `query-result` table with the server's rows; no fake rows | `app.data-platform.enabled=true` + a seeded TEST source | C0 (B-C0-W-03), C3 | BLOCKED unless `E2E_DATA_*` set; with it: NOT RUN |
| E2E-08 | LIVE-binding query | (published app) call a LIVE read query | `…/app-runtime/queries/{id}/run` `{mode:"LIVE"}` against a running deployment | Pre-seeded LIVE binding + a RUNNING deployment | none (read) | rows shown in the published app | LIVE binding has no HTTP route (`B-C0-W-03`); the published app has no data runtime host (`B-C5-06`, `workers/render` takes only `{schema, assets}`) | C2 (+C0, C3) | BLOCKED |
| E2E-09 | Mutation/action success with a real side effect | (published app) submit a form bound to a CREATE_RECORD action | `…/app-runtime/actions/{id}/execute` `{mode:"LIVE", idempotencyKey}` | Pre-seeded writable source | row exists in the source; replaying the same key writes once | success shown only after envelope `OK`; row read back through a READ query | production connectors (postgres, rest) are read-only → `MUTATION_UNSUPPORTED` (`B-C0-W-04`); LIVE mutating actions answer 503 `RUNTIME_STORES_VOLATILE` without V29 / `allow-volatile-stores` (`B-C0-W-01`); no source registration route (`B-C0-W-03`) | C3 (+C0, C4) | BLOCKED (TEST-mode part covered by E2E-S1) |
| E2E-10 | Failure with no fake success | Test panel; the action is deleted on the server by another session; click "Chạy thử" | `PATCH …/schema` (REMOVE_ACTION), `POST …/actions/{id}/execute` → 404 `UNKNOWN_ACTION` | F-def | action restored by the flow; nothing written | row shows ERROR "Không tìm thấy mục cần chạy"; no SUCCESS anywhere; button usable again; no unhandled errors | `app.workflow.enabled=true` (action route); typed ops accepted | C5 (C4 route, C2 ops) | NOT RUN |
| E2E-11 | Workflow start/read | Test panel → start workflow; watch status | `POST …/workflows/{id}/runs` (202 `runId`, `idempotencyKey` in body), `GET …/workflow-runs/{runId}`, `POST …/cancel` | F-def | TEST run record; terminal status | UI shows run id + server status; finished as simulated ("would run", not success); a second session reads the same run (mode TEST) | `app.workflow.enabled=true`; run stores in memory are acceptable for TEST | C4, C5 | NOT RUN |
| E2E-12 | Durable workflow after backend restart | Start a run, restart the backend, log in again, read the run | as E2E-11 + operator hook `E2E_RESTART_BACKEND_CMD` | F-def; `E2E_DURABLE_RUN_STORES=1` | run survives the restart with the same id and a terminal status | UI after restart shows the run, not "not found" | C4 run stores are **in memory** on `f894cc6`; V29 is a branch, not integrated (`B-C0-W-01`, `B-C4-05`). A restart loses every run by design today | C0 (C4) | BLOCKED (real body runs only with the hooks) |
| E2E-13 | Publish → public page | "Xuất bản" → Công khai → Xuất bản; wait; open the URL as an anonymous visitor | `POST …/publish` (Idempotency-Key), `GET …/deployments/{id}`, public URL | F-core | deployment RUNNING with `mock=false`; public page serves the saved content | dialog "Website đã lên"; visitor 200, shows E2E-02's marker, no Studio chrome | `app.publish-configs.enabled`; a SELF_HOSTED deployment provider (a MOCK provider only yields a demo URL → the flow reports BLOCKED, not PASS); `E2E_PUBLIC_BASE` if the public host differs | C2 | NOT RUN |
| E2E-14 | RabbitMQ down → recovery | Stop RabbitMQ, start a workflow, observe the failure, start RabbitMQ, retry | as E2E-11 + `E2E_STOP_RABBIT_CMD` / `E2E_START_RABBIT_CMD` | F-def; `E2E_RABBITMQ_WIRED=1` | correct failure while down, retry works after | explicit error + retry; no fake success | No RabbitMQ consumer or queue adapter on `f894cc6`: C4's queue and run stores are in memory (`B-C4-05`, `B-C4-06`), so no dependency outage can change a workflow start | C4 (C0) | BLOCKED |
| E2E-S1 | (supplementary) TEST action → WOULD_RUN | Test panel → run the NOTIFY action | `POST …/actions/{id}/execute` `{mode:"TEST", idempotencyKey}` | F-def | **no write** | body only has contract fields; 200 `WOULD_RUN`; UI says "would run", never success | `app.workflow.enabled=true` | C5 (C4) | NOT RUN |
| E2E-S2 | (supplementary) offline save → retry | Edit, go offline, "Lưu thay đổi" (fails), go online, "Thử lại" | `PATCH …/schema` | F-core | exactly one revision added | "Lưu thất bại" + retry button; Publish disabled while unsaved; after retry "Đã lưu"; revision = before + 1 | stack up | C5 | NOT RUN |

Split of the 14 required flows (E2E-13 can turn BLOCKED on a MOCK deployment provider; E2E-07 turns NOT RUN when `E2E_DATA_*` is set):

| Status (this baseline, nothing run) | IDs |
|---|---|
| NOT RUN (real body, stack required) | 01, 02, 03, 04, 05, 10, 11, 13 (+ S1, S2) |
| BLOCKED — C0/C3 | 06, 07 (unless pre-seeded), 08, 09 |
| BLOCKED — C0/C4 | 12, 14 |

So at the required granularity: **PASS 0/14 · FAIL 0/14 · NOT RUN 8/14 · BLOCKED 6/14**.

## Rules the suite enforces (see `scripts/test-classify.mjs`)

- Every test file carries a first-line `// @class: unit|mock|harness|real-backend`. Real-backend files live only in `tests/e2e-real/` (plus the legacy pre-V2 `e2e/*.mjs`, tagged `@legacy`) and must not intercept the network. Mock/harness files may not call themselves a full E2E.
- A flow passes only with ≥1 check and zero failed checks; a thrown `Blocked` is listed, never counted as PASS; any failed check wins over BLOCKED.
- Exit codes of `node tests/e2e-real/run.mjs`: `0` all runnable flows passed, `1` a flow failed, `2` NOT RUN (no/unsuitable backend, fixtures failed, or zero PASS).
- Fixtures use the product API only, random per-run passwords, no secrets in the repo; workspaces cannot be deleted by any route, so they are left behind (and reported) after cleanup.

## Supersedes

`docs/parallel/c5/PHASE3_E2E_PLAN.md` used E-xx IDs; this matrix replaces them (mapping is in that file's header).
