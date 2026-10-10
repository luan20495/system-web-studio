# C4 handoff — FQ-WF-01 / FQ-WF-02: workflow APPROVAL is executable (Journey 06)

Owner: C4 · branch `feat/c4-approval-runtime` (based on `fix/c4-fq-act-02` @ 49b2ae9 = integration/v2 fad4a7b + FQ-ACT-02) · **nothing is pushed**.

## What was wrong
- FQ-WF-01: the LIVE validator accepted an `APPROVAL` step but the wiring never gave the engine an `ApprovalService` / durable store → every LIVE approval step failed `NOT_IMPLEMENTED` ("Approvals are not wired").
- FQ-WF-02: no route and no canonical operation to decide an approval; no durable `approvals` table (`InMemoryApprovalStore` was the only store); `ApprovalService.decide` knows tenant + approver snapshot but not project/run scope.

## What is delivered (one workflow model, no second one)
| Piece | Where | Owner |
|---|---|---|
| `WorkflowRuntime.decideApproval(ctx, runId, approvalId, APPROVE\|REJECT, comment)` + `ApprovalDecisionView`, codes `APPROVAL_NOT_FOUND` / `APPROVAL_ALREADY_DECIDED` / `APPROVAL_CONFLICT` | `logic/workflow/WorkflowRuntime.kt`, `WorkflowEngine.kt` | C4 |
| `JdbcApprovalStore` (CAS on `version`, idempotent create, tenant-scoped, retention) | `wiring/persistence/JdbcApprovalStore.kt` | C4 (file is new) |
| **DDL proposal** `approvals` | `backend/src/test/resources/db/proposed/approvals.sql` | C4 proposes, **C0 numbers** |
| Wiring: `app.workflow.approvals=auto\|jdbc\|memory\|off`, forwarder listener → `engine.onFinal` | `wiring/AppRuntimeConfiguration.kt` | **C0 file — proposal** |
| Route `POST /api/v1/workspaces/{ws}/projects/{p}/app-runtime/workflow-runs/{runId}/approvals/{approvalId}/decision` body `{"decision":"APPROVE"\|"REJECT","comment"?}` (strict body: no identity fields) | `wiring/AppRuntimeActionController.kt`, `RuntimeRequests.kt`, `RuntimeResponses.kt` | **C0 files — proposal** |
| `approvalId` in the step JSON of the run view (additive) | `RuntimeResponses.kt` | **C0 file + contract delta (see below)** |
| `VolatileWorkflowGuard` delegates `decideApproval` (compile fix of the new interface method) | `wiring/VolatileStoreGuards.kt` | **C0 file — one delegating method** |

## Authorization of a decision (server side only, in this order)
1. tenant enabled, then **scope**: tenant → workspace → project of the run (`sameResourceScope`); a wrong scope is `RUN_NOT_FOUND` (404), never FORBIDDEN;
2. canonical permission **`WORKFLOW_MANAGE`** through the live C1 `AccessPort` (resource kind `APPROVAL`); denied → `FORBIDDEN` before the approval is even looked up (same answer for an unknown approval: nothing is revealed);
3. the approval must belong to **this run and this app** (`source.workflowRunId`, `appId`) else `APPROVAL_NOT_FOUND`;
4. `ApprovalService.decide`: the user must be in the **approver snapshot** taken when the step started (explicit user ids resolved at that moment; the requester is excluded unless `allowSelfApproval`). Never a role string, org membership, MANAGER/HEAD relation, position or grade is consulted at decision time.
5. idempotent for the same decision by the same approver; the opposite decision → `APPROVAL_CONFLICT` (409); a final approval (approved/rejected/expired/cancelled) → `APPROVAL_ALREADY_DECIDED` (409).
Effect: `ApprovalListener.onFinal` → `engine.resumeFromApproval` (CAS on the run row) publishes the resume job once; the next step is executed with the **run creator's** authority re-checked per effectful step (`authorityFailure`, unchanged), so an approver does not need DATA_MUTATE and cannot widen the creator's rights.

## Migration request (C0)
`approvals` — additive, one table, indexes for idempotency key, expiry sweep, inbox (GIN on `approvers`), source run, retention. Next free number per MIGRATION_LEDGER is **V33**. C0: `git mv backend/src/test/resources/db/proposed/approvals.sql backend/src/main/resources/db/migration/V33__approvals.sql` (+ an `U33` undo, + ledger row). The tests apply the proposal verbatim (`ApprovalTestSchema`), and become no-ops once the table exists through Flyway. No existing migration is touched; no `outOfOrder`.
Until the table exists: `app.workflow.approvals=auto` (default) detects the missing table at start-up, logs a warning and leaves approvals unwired → a LIVE APPROVAL step stays `NOT_IMPLEMENTED` exactly as before (no behaviour change for an environment that has not imported the migration). After the migration is applied nothing needs to be switched on.

## Contract delta to record in DECISIONS.md before import (docs/contracts/v2/runtime-api.md)
1. New route `…/workflow-runs/{runId}/approvals/{approvalId}/decision` (200 body: `approvalId`, `approvalStatus`, `requiredApprovals`, `approvals`, `run` = the run view; errors 400/403/404/409/503).
2. Run view step objects gain `approvalId` (nullable) — additive; the contract line "no approval id" is superseded.
3. Approvals inbox / list endpoints are **not** part of this change (still deferred).

## Proposal for C1 (not required for this task)
`WORKFLOW_MANAGE` is a broad right (manage runs). A dedicated `APPROVAL_DECIDE` permission would let a project grant "may decide approvals it is named on" without "may cancel any run". The engine already passes `ResourceKind.APPROVAL` with the approval id, so the switch is one constant in `decideApproval`.

## Tests (all green on this branch)
- `ApprovalDecisionTests` (10, engine level, mutation-checked: removing the WORKFLOW_MANAGE guard and the run-binding guard each made the intended tests fail).
- `JdbcApprovalStoreTests` (9) — real PostgreSQL: round-trip, restart, idempotent create, CAS loss, tenant isolation, inbox, expiry order, retention, check constraints, real 2-thread race.
- `WorkflowApprovalG3Tests` (7) — real PostgreSQL + RabbitMQ: durable WAITING across a node loss, approve / reject after restart, concurrent opposite decisions from two nodes, resume job redelivered after a consumer death before ack (next step once), lost resume nudge recovered by another node's sweeper, cancel while waiting then decide, wrong tenant / requester / outsider after restart.
- `ApprovalJourneyE2ETests` (3) — whole chain over HTTP: **Journey 06** approve (requester ≠ approver, 403 for requester / un-named admin / approver without WORKFLOW_MANAGE, 404 for other tenant / other project / approval of another run, 400 for bad body and for an identity field in the body, then APPROVE → `UPDATE_RECORD` by `recordId` (FQ-ACT-02) → `approved` in the customer table, exactly one extra action run, duplicate decision harmless, opposite 409, audit rows); reject with `onReject` branch and strict reject (`FAILED` / `APPROVAL_REJECTED`, data unchanged); cancel while waiting.

## Known limits (honest list)
- A real Spring/JVM kill-and-restart of the whole HTTP application is not in the HTTP test; restart is proven at node level (new engine + store + queue consumer over the same PostgreSQL/RabbitMQ) in `WorkflowApprovalG3Tests`.
- `PrincipalSpec.Role` / `Group` / `DepartmentManager` approvers are resolved by C1's `PrincipalResolver` **once, when the step starts** (C1 today answers users/roles only). If the policy "approvers must be explicit users" is wanted, restrict at definition validation; the runtime itself never uses a role at decision time.
- Notifications to approvers (`notifyTemplateRef`) are not wired (the notify port is `null` in the runtime, unchanged).
- Approval expiry (`onExpire`) is driven by the existing sweeper; with the durable store it now works across restarts, covered at engine level (`WorkflowEngineTests`), not re-tested over HTTP.
