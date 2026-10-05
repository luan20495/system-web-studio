# Canonical contract v2 — Action & Workflow

Owner: **C4** (runtime model and the shape of `actions[]` / `workflows[]` entries). Frozen by C0 on 2026-10-05 from `agent/c4-workflow` (`a38ff60`). Supersedes `docs/contracts/action-workflow.md`. C2 stores/validates the declarations (`app-definition.md`). Code: framework-free domain layer, in-memory stores, **no Spring/RabbitMQ/DB wiring, not compiled, not run** (277 tests ran on an ad-hoc kotlinc harness).

## 1. ActionType — one list
`NAVIGATE, REFRESH_QUERY, SUBMIT_FORM, CREATE_RECORD, UPDATE_RECORD, DELETE_RECORD, CALL_API, NOTIFY, START_WORKFLOW` (C4's `ActionType`).
- `REFRESH_QUERY` is canonical for "re-run a READ query" (it replaces the draft name `RUN_QUERY`). C2 adds it (needs a READ `queryRef`).
- `WRITE_DATA`, `CALL_CONNECTOR_OPERATION`, `RUN_QUERY`, `SET_VALUE` never existed in stored data: **the alias map in C4's reader is removed**; they are rejected.
- `NAVIGATE` and `REFRESH_QUERY` are client instructions (no server side effect). All other types are mutating and need an idempotency key.

## 2. `actions[]` entry (stored in the AppDefinition)
`{id, name?, type, enabled?, trigger{sectionId, event}, pageRef?, queryRef?, viewModelRef?, dataSourceRef?, operationKey?, workflowRef?, channel?, templateRef?, endpointRef?, recipients?[], permissionRef?, inputs[{name,type,required,maxLength?}], inputMapping{name:{source COMPONENT_STATE|ROUTE_PARAM|FORM_FIELD|VIEW_MODEL|PREVIOUS_RESULT|LITERAL|CONTEXT, path|name|value|key}}, idempotency?, limits{timeoutMillis}?, onSuccess[]?, onError[]?}`.
- C2's `ActionDef` is a strict subset today (no `channel/templateRef/endpointRef/recipients/inputMapping/idempotency/limits/onSuccess/onError/enabled`); a `NOTIFY` action passes C2 and fails at runtime. C2 extends `ActionDef` (typed, lossless round-trip) and delegates deep validation to `ActionDefinitionValidator` (C4). Forbidden anywhere in `config`: `sql, script, url, headers, token, password…`; every reference matches `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`.
- `event` ∈ `EventType` wire names (`onLoad, onClick, onChange, onSubmit, onSuccess, onError`); `Event.name = "$sectionId.$wire"`.
- `ActionRef` (runtime, C4) is **derived** from `actions[].trigger`; there is no stored `ActionRef` list. `TriggerKind { UI_EVENT, WORKFLOW_STEP, SCHEDULE }` is the *runtime* trigger of a run (not the same thing as `WorkflowTriggerType`).

## 3. `workflows[]` entry
`{id, name?, enabled?, trigger MANUAL|SCHEDULE|ACTION, schedule?(cron), timezone?, startStepId?, compensateOnCancel?, limits?, steps[{id, kind ACTION|WAIT|APPROVAL|BRANCH|END, actionRef?, inputs, next?, onError?, retry?, timeoutMillis?, waitSeconds?, approval?, branches?, defaultNext?, compensationActionRef?}]}` — C4's `CanonicalWorkflowCatalog` is the reference reader. C2's `WorkflowStepDef{id, actionRef, next, onError}` is too thin and is extended the same way as actions. Runtime: CAS on `WorkflowRun.version`, queue message `v1|jobId|tenantId|runId|stepId` (no payload/actor), retry/backoff, DLQ, approval (snapshotted approvers, any reject rejects, cross-tenant = DENY_ALL), scheduler that only enqueues, TEST mode = `WouldRun`.
`WorkflowRuntime { start, status, cancel }` as in the code. Run state is `PENDING|RUNNING|WAITING|SUCCEEDED|FAILED|CANCELLED`.

## 4. Data path — the only one
```
Action → ActionDataPort → [C0 adapter + AppDataBindingResolver] → DataGateway → DataSource → Connector
```
`CREATE_RECORD/UPDATE_RECORD/DELETE_RECORD/SUBMIT_FORM` → `GatewayMutation`; `CALL_API` → mutation/operation through the gateway; `REFRESH_QUERY` → client instruction + `GatewayQuery`. No handler touches JDBC/HTTP; **no adapter exists yet** (B-C4-04) — it is wiring work for step 7. Adapter rules: derive the idempotency key (`data-runtime.md` §4); resolve `queryRef` through the AppDefinition; reject a mutating action whose key is null (`IdempotencyPolicy.OPTIONAL` must not reach the port as null); `WriteRequest`/`OperationRequest` gain `mode` and `appId` so TEST dry-run can tell draft from published.

## 5. Permissions and tenancy
Port permission strings = the canonical codes (`tenant-permission.md` §5): `APP_USE`, `ACTION_EXECUTE`, `DATA_MUTATE` (for write actions), `WORKFLOW_EXECUTE`, `WORKFLOW_MANAGE`. C4 imports nothing from `access`/`tenancy`; `ActionContext.tenantId` is stamped by the C1 adapter and **verified against the app's tenant** by `AppDefinitionSource`. Workers rebuild the context from the stored run creator; a disabled tenant fails the run `TENANT_DISABLED`. `ActorKind` is `tenancy.ActorKind`. Audit entries carry the actor explicitly (virtual threads lose `SecurityContext`).

## 6. Fixes C4 owes before wiring
Sweeper starvation (`stale()`/`waitingApprovals()` ordered by `updatedAt` fill the first 100 slots with long-waiting runs); no immediate `nack(requeue=true)` loop and no `DEAD_LETTERED` failure for a transient infrastructure outage; backoff floor (no 0 ms retries); tenant rate limit; retention for run input/output; schedule-registration dedupe key on republish; correct the doc claims (definition snapshot covers workflows only; TEST workflows still create a run row).

## 7. Persistence
Six tables proposed (`action_runs, workflow_runs, approvals, schedules, notification_deliveries, in_app_notifications`) in `FINAL-C4-runtime-design.md` §10. Not numbered. Must reference `tenants(id)`, add `(tenant_id, created_at)` indexes and a retention policy before a number is assigned.
