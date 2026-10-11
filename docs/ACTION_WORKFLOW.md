# Action and Workflow Runtime

Canonical reference for the declarative Action layer, the Workflow engine and its queue, the durable run stores, and the Approval runtime of XWEB (System Web Studio).

- Audience: engineers who have never seen the project. Language: English. No chronology; the system is described as it is.
- State described: `integration/v2` as of `9d2fc8b9758077ac880d80cfbec767e2e776f9c5` (verified against the local `integration/v2` at HEAD `28376de`, which includes the C4 approval runtime (merge `fa42ad2`, migration V33, D-C0-61) and the interrupted-worker fix `140dc36`). Release candidate `75643ad8700f42df05c3151c1d0875b063c81620`; deployed frontend `bc5c47f292d0`; deployed API `1006cbf441f6`.
- Status vocabulary: DONE / PARTIAL / BLOCKED / DEFERRED. `UNVERIFIED` = not checked against code or a document (the closing section says what would verify it).
- Related canonical docs: `docs/DATA_RUNTIME.md` (data sources, gateway, idempotency store, error mapping of writes), `docs/ARCHITECTURE.md`, `docs/contracts/v2/{action-workflow,runtime-api,app-definition,tenant-permission}.md`.

Citation convention: `(src: B/<path>)` = `backend/src/main/kotlin/com/systemwebstudio/<path>`; `file.kt line N` / `lines N-M` are line numbers on `integration/v2` HEAD `28376de` and drift when the file changes; other `(src: ...)` paths are repo-relative. Where a document and the code disagree, the code wins and the disagreement is noted.

---

## 1. Model overview

An **Action** is declarative data with a type; it never carries code, SQL or a URL. A **Workflow** is a declared graph of steps that reference actions. Both are stored in the application's AppDefinition (`actions[]`, `workflows[]`) and read by an anti-corruption layer (`logic/action/canonical`, `logic/workflow/canonical`); the contract is the JSON shape, not a Kotlin type. `logic/**` imports nothing from the access, tenancy, data, app, common, audit, runtime or wiring packages (enforced by an architecture test, `ActionContractV2Tests`); everything outside is reached through ports. (src: B/logic/action/ActionModel.kt, B/logic/action/ActionPorts.kt)

```
HTTP (wiring)  ->  ActionRuntime  ->  handler  ->  ActionDataPort  ->  [C0 adapter + AppDataBindingResolver]  ->  DataGateway  ->  connector
                \->  WorkflowRuntime (start/status/cancel/decideApproval)  ->  run store (PostgreSQL)  ->  queue (RabbitMQ)  ->  worker  ->  ActionRuntime
```

| Port (interface in `logic.action`) | Supplied by | Implementation |
|---|---|---|
| `AppDefinitionSource` | wiring | `RuntimeAppDefinitionSource` over `RuntimeAppDefinitions` (TEST = draft, LIVE = pinned version of the active release) |
| `AccessPort`, `TenantGate`, `PrincipalResolver` | C1 | `C4AccessPortAdapter`, `C4TenantGateAdapter`, `C4PrincipalResolverAdapter` (`wiring/C1PortAdapters.kt`) |
| `ActionAuditPort`, `LogicAuditPort` | audit module | `ActionAuditAdapter`, `LogicAuditAdapter` (`wiring/AuditAdapters.kt`) |
| `ActionDataPort` | C3 | `ActionDataPortAdapter` (`wiring/ActionDataPortAdapter.kt`) |
| `ActionNotifyPort` | C4 | **not wired** (`ActionPorts(notify = null)`) |
| `WorkflowStarterPort` | C4 | the `WorkflowEngine` itself, through a forwarding port |
| `ActionRunStore`, `WorkflowRunStore` | C0 persistence | `JdbcActionRunStore`, `JdbcWorkflowRunStore` (default) or in-memory (dev only) |
| `WorkflowQueue` | integration | `AmqpWorkflowQueue` or `InMemoryWorkflowQueue` |
| `ApprovalStore` | C0 persistence | `JdbcApprovalStore` (V33; `B/wiring/persistence/JdbcApprovalStore.kt`) or `InMemoryApprovalStore` |

The Action layer has **one** door to data: `ActionDataPort`. No handler, workflow step or scheduler touches JDBC, a connector or HTTP. The data path is `Action -> ActionDataPort -> adapter + AppDataBindingResolver -> DataGateway.mutate`. (src: docs/contracts/v2/action-workflow.md section 4; B/wiring/ActionDataPortAdapter.kt)

---

## 2. Feature flags and defaults

All defaults are OFF/safe. A disabled `app.workflow.enabled` means none of the runtime beans, controllers, queue configuration or worker exist, so every route below answers 404. (src: backend/src/main/resources/application.yml lines 225-244; B/wiring/AppRuntimeConfiguration.kt)

| Property (env var) | Default | Effect |
|---|---|---|
| `app.workflow.enabled` (`WORKFLOW_ENABLED`) | `false` | Gates the whole runtime: `AppRuntimeConfiguration`, `RunStoreConfiguration`, `AppRuntimeActionController`, `WorkflowWorkerRunner`, `ActionRunRecovery`, `WorkflowQueueConfiguration`. |
| `app.workflow.run-store` (`WORKFLOW_RUN_STORE`) | `jdbc` (also when the property is absent) | `jdbc` = PostgreSQL, restart-safe, the only production choice. `memory` = explicit dev/test override, single node, lost on restart. Any other value defines **no** run-store bean and the application does not start (no silent fallback). |
| `app.workflow.allow-volatile-stores` (`WORKFLOW_ALLOW_VOLATILE_STORES`) | `false` | Only meaningful with `run-store=memory`: without it LIVE state-changing actions and LIVE workflow starts answer 503 `RUNTIME_STORES_VOLATILE`. |
| `app.workflow.queue` (`WORKFLOW_QUEUE`) | empty in base config; **`amqp` in profile `prod`** (`${WORKFLOW_QUEUE:amqp}`) | Empty resolves by profile: `prod`/`production` = `amqp`, every other profile (including `local`) = `memory`. `memory` under production, or any value other than `memory`/`amqp`, stops the start. No fallback from `amqp` to `memory`: an unreachable broker stops the start. `scripts/_env.sh` sets `WORKFLOW_QUEUE=amqp` for the local V1 stack (`PORTALS=1`). (src: B/integration/queue/workflow/WorkflowQueueSelection.kt, WorkflowQueueConfiguration.kt) |
| `app.workflow.amqp.queue` / `.dead-letter-queue` / `.dead-letter-exchange` | `xweb.workflow.jobs` / `xweb.workflow.jobs.dlq` / `xweb.workflow.dlx` | Topology names (section 10). |
| `app.workflow.amqp.delivery-limit` / `.confirm-timeout` | `5` / `PT5S` | Quorum `x-delivery-limit`; publisher-confirm wait. |
| `spring.rabbitmq.host\|port\|username\|password\|virtual-host` | prod: required, no defaults; local: `127.0.0.1:15674`; code fallback `guest/guest` only when nothing is configured | Broker connection (the same settings as the rest of the application). TLS/`addresses` are not read (known limit). |
| `app.workflow.stale-after` (`WORKFLOW_STALE_AFTER`) | `PT2M` (minimum `PT30S`) | Lease of a RUNNING workflow step; renewed every third of it. |
| `app.workflow.action-run-stale-after` | `PT10M` (minimum `PT1M`) | Lease of a RUNNING action run; must exceed the longest action timeout. |
| `app.workflow.action-run-sweep-delay-ms` | `30000` | Period of `ActionRunRecovery`. |
| `app.workflow.worker-delay-ms` | `500` | Period of `WorkflowWorkerRunner.tick`. |
| `app.workflow.worker-id` | generated `node-<8 hex>` per process (at most 64 chars) | Written to `workflow_runs.lease_owner`. |
| `app.workflow.approvals` | `auto` | `auto`, `jdbc`, `memory` or `off` (section 12.8). Not listed in `application.yml`: read with an inline default in `AppRuntimeConfiguration.kt` line 132. |

The `local` V1 stack sets `DATA_PLATFORM_ENABLED=true WORKFLOW_ENABLED=true PUBLISH_CONFIGS_ENABLED=true` in `scripts/_env.sh` when `PORTALS=1`; production sets none. (src: scripts/_env.sh)

**Coupling to the data flag:** `app.workflow.enabled=true` with `app.data-platform.enabled=false` leaves no `DataGateway` bean, so data actions answer 503 `DATA_RUNTIME_UNAVAILABLE` (definite, nothing sent). (src: B/wiring/ActionDataPortAdapter.kt)

---

## 3. The nine action types

`ActionType` is a closed set of exactly nine names; `CanonicalActionReader.actionType` matches by exact string, with **no alias map and no case folding**. `RUN_QUERY`, `WRITE_DATA`, `CALL_CONNECTOR_OPERATION`, `SET_VALUE` and any other name are rejected with an issue and the action is not offered. (src: B/logic/action/ActionModel.kt, B/logic/action/canonical/CanonicalActionCatalog.kt; docs/contracts/v2/action-workflow.md section 1; D-C4-01)

| Type | `mutatesState` | Config (ids only) | Behaviour in LIVE | Behaviour in TEST | Wired? |
|---|---|---|---|---|---|
| `NAVIGATE` | no (client instruction) | `pageRef` (stored as `pageId`) | returns `{action:"NAVIGATE", pageId, params}` for the client | `WouldRun` | yes |
| `REFRESH_QUERY` | no (client instruction) | `queryRef` (READ query) | returns `{action:"REFRESH_QUERY", queryRef}`; the server reads no data (the client's next call goes through R1 under the user's own permission) | `WouldRun` | yes |
| `SUBMIT_FORM` | yes | `queryRef` (WRITE query) | `ActionDataPort.write(kind SUBMIT)` | preview | yes; PostgreSQL answers `MUTATION_UNSUPPORTED` for SUBMIT |
| `CREATE_RECORD` | yes | `queryRef` (WRITE) | `write(kind CREATE)` | preview | yes |
| `UPDATE_RECORD` | yes | `queryRef` (WRITE); input `recordId` required STRING | `write(kind UPDATE)` | preview | yes |
| `DELETE_RECORD` | yes | `queryRef` (WRITE); input `recordId` required STRING | `write(kind DELETE)` | preview | yes |
| `CALL_API` | yes | `dataSourceRef` + `operationKey` | `ActionDataPort.callOperation` (an approved operation, never a URL/header/credential) | preview | yes |
| `NOTIFY` | yes | `channel` (`IN_APP\|EMAIL\|WEBHOOK\|SMS`), `templateRef`, `endpointRef` (WEBHOOK only), `recipients[]` | `ActionNotifyPort.send` | never sends; `WouldRun` | **no**: the port is `null`, so a NOTIFY action fails `NOT_IMPLEMENTED` (501), in TEST too |
| `START_WORKFLOW` | yes | `workflowRef`; idempotency must be `REQUIRED` | `WorkflowStarterPort.start` (child run, depth + 1, always LIVE) | `WouldRun` ("no workflow run is started in TEST mode") | yes |

All types are always registered; a missing port becomes `NotImplementedActionHandler` (fail closed, non-retryable `NOT_IMPLEMENTED`), never a hole. `DOWNLOAD_FILE` and `OPEN_URL` do not exist (no security model). (src: B/logic/action/handlers/ActionHandlers.kt `DefaultActionHandlers.registry`)

`NAVIGATE` and `REFRESH_QUERY` are client instructions with no server side effect; **every other type is mutating** and needs an idempotency key. (src: B/logic/action/ActionModel.kt)

---

## 4. Action definition (AppDefinition `actions[]`)

Entry shape (src: docs/contracts/v2/action-workflow.md section 2; reader in B/logic/action/canonical/CanonicalActionCatalog.kt):

```
{ id, name?, type, enabled?, trigger?{sectionId, event}, pageRef?, queryRef?, viewModelRef?, dataSourceRef?, operationKey?, workflowRef?,
  channel?, templateRef?, endpointRef?, recipients?[], permissionRef?,
  inputs[{name, type STRING|NUMBER|BOOLEAN|DATE|..., required, maxLength?}],
  inputMapping{ name: {source COMPONENT_STATE|ROUTE_PARAM|FORM_FIELD|VIEW_MODEL|PREVIOUS_RESULT|LITERAL|CONTEXT, path|name|value|key} },
  idempotency? NONE|OPTIONAL|REQUIRED, limits{timeoutMillis}?, onSuccess[]?, onError[]? }
```

Validation (`ActionDefinitionValidator`, run on every execute and when the document is parsed). A violation makes the action **not offered** (fail closed); the reasons are available to authors through `CanonicalActionCatalog.diagnose`:

- References (`queryRef`, `dataSourceRef`, `workflowRef`, `permissionRef`, chained ids) must resolve inside the same document, and every id matches `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$` (no `:` or `/`, so no URL). `queryRef` of a record action must be a WRITE query; of `REFRESH_QUERY` a READ query.
- Forbidden keys in `config`, at any depth and case-insensitively: `sql, script, code, js, javascript, eval, expression, command, shell, exec, url, uri, endpoint, host, headers, authorization, password, secret, token, apikey, credential, credentials`. Config over 16 KiB or deeper than 6 levels is refused.
- At most 64 inputs; input names `^[A-Za-z_][A-Za-z0-9_]{0,63}$`; `maxLength` only for STRING. `inputMapping` may only target declared inputs; paths are dotted plain segments (at most 8), **no expression language**.
- A mutating type with `idempotency: NONE` is refused; `START_WORKFLOW` requires `REQUIRED`. Mutating types default to `REQUIRED`.
- At most 8 chained actions in `onSuccess`/`onError`, none to itself; an action whose chain dangles is removed (repeated until stable).
- `UPDATE_RECORD`/`DELETE_RECORD` need a required STRING input `recordId`.
- **`trigger` is optional** (D-C4-10): an action run by a top-level UI event (`TriggerKind.UI_EVENT`, call depth 0) needs `trigger{sectionId, event}` whose name `"<sectionId>.<event>"` matches the event of the request; actions used only by a workflow step, a schedule or a chain need none. A malformed `trigger` makes the action unusable. `event` is one of `onLoad, onClick, onChange, onSubmit, onSuccess, onError`.
- Platform ceilings an action may only lower: timeout 30 s, input 64 KiB, input depth 8, call depth 5. (src: B/logic/action/ActionModel.kt `ActionLimits`)

---

## 5. Action runtime

### 5.1 Entry points

`ActionRuntime.execute` (one action, no chaining; used by workflow steps), `run` (execute + `onSuccess`/`onError` chain), `dispatch` (event to `ActionRef`s). Over HTTP only `run` is reachable (section 13.1). `dispatch` has no route; `VolatileActionGuard` delegates it unchanged. (src: B/logic/action/ActionRuntime.kt)

### 5.2 Pipeline (order is deliberate: cheapest and least revealing first, side effects last)

1. Client idempotency-key shape `^[A-Za-z0-9._:-]{1,128}$` -> `IDEMPOTENCY_KEY_INVALID`.
2. Tenant gate -> `TENANT_DISABLED`; an unreadable state -> `DEPENDENCY_UNAVAILABLE` (retryable, fail closed).
3. Application context (`projectId`) required; the definition is looked up by (tenant, app, action, mode). Another tenant's or app's id, an unknown id and `enabled=false` all give `UNKNOWN_ACTION`.
3b. UI trigger rule (section 4) -> `UNKNOWN_ACTION`.
4. Authorization, in order, each through the live C1 `AccessPort`, default deny, audited as `ACTION_DENIED`: `APP_USE`; `ACTION_EXECUTE`; `DATA_MUTATE` for `SUBMIT_FORM, CREATE_RECORD, UPDATE_RECORD, DELETE_RECORD, CALL_API`; the definition's declared permission; `WORKFLOW_EXECUTE` for `START_WORKFLOW`. Denial -> `FORBIDDEN`.
4b. Per-tenant rate limit `ACTION_EXECUTE` (after authorization so the unauthorized cannot spend the budget) -> `RATE_LIMITED` (retryable, `details.retryAfterMillis`). The limiter is an **in-process token bucket** (capacity 600, 100/s refill), so with N nodes the effective budget is about N times larger. (src: B/logic/limits/TenantRateLimiter.kt)
5. Handler for the type -> `UNSUPPORTED_ACTION_TYPE`.
6. Call depth and `ActionDefinitionValidator` -> `LIMIT_EXCEEDED` / `INVALID_DEFINITION`.
7. **Input binding** (`InputResolver` then `ActionInputBinder`): see 5.3. -> `INVALID_INPUT` / `LIMIT_EXCEEDED`.
8. **TEST**: `handler.preview` -> `WouldRun`; no run state, no idempotency, no side effect; audit `PREVIEWED`.
9. **LIVE**: a mutating action with policy `REQUIRED` and no key -> `IDEMPOTENCY_KEY_REQUIRED` (400). Derive the key (section 7), `ActionRunStore.begin` (atomic): start, replay, `ACTION_IN_PROGRESS`, or `IDEMPOTENCY_KEY_REUSED`.
10. Audit `STARTED` is **fail closed**: if it cannot be written the action does not run (`AUDIT_UNAVAILABLE`, retryable; the run is completed as failed so the same key can be retried).
11. Handler runs on a virtual-thread executor under a timeout (definition limit capped at the platform ceiling; a request may only shorten it).
12. `complete` the run record, audit the terminal phase (best effort: the action already happened).

Audit rows go to `audit_events` as `ACTION_<PHASE>` (`STARTED, SUCCEEDED, FAILED, DENIED, REJECTED, REPLAYED, PREVIEWED`) with ids, phases, codes and durations, never an input or output value. (src: B/wiring/AuditAdapters.kt)

### 5.3 Input binding

Sources are typed lookups, never expressions: `COMPONENT_STATE`, `ROUTE_PARAM`, `FORM_FIELD`, `VIEW_MODEL` (all from the client's event payload; untrusted, they can only feed **declared** inputs), `PREVIOUS_RESULT` (the triggering action's output in a chain), `LITERAL`, and `CONTEXT` (`USER_ID, TENANT_ID, WORKSPACE_ID, APP_ID, REQUEST_ID, NOW`: server-derived). Rules:

- A caller that supplies an input the definition maps from `CONTEXT` is rejected (`INVALID_INPUT`), never silently overridden: a client cannot act "as" someone else.
- Explicit inputs (`inputs` in the HTTP body, workflow step inputs) win over event-mapped values but never over `CONTEXT` ones.
- The binder then keeps only declared names, checks types, string lengths, total size and depth. The HTTP body allows at most 100 `inputs`.

(src: B/logic/action/InputResolver.kt, B/logic/action/ActionInput.kt, B/wiring/RuntimeRequests.kt)

### 5.4 TEST vs LIVE

| | TEST | LIVE |
|---|---|---|
| Definition source | working draft | the AppDefinition snapshot of the version pinned by the active release |
| HTTP gate | `PROJECT_EDIT` on the project | none beyond the pipeline |
| Result | `WouldRun {actionId, type, level, plan, output?, reason?}`; `plan` carries input **names** only | `Ok {output}` or `Failed` |
| Side effects | none: no mutation, no notification, no workflow start, no `action_runs`, no idempotency reservation | real |
| Data | the data adapter refuses a non-LIVE write; connectors are not asked unless the platform supports a dry run | gateway `mutate` |
| `level` | `NOT_EXECUTED` (default, honest: nothing validated downstream), `VALIDATED`, `SANDBOX` (only if `ActionDataPort.dryRunWrite/dryRunOperation` says so; the adapter does **not** override them, so data actions report `NOT_EXECUTED`) | n/a |
| Chaining | not run; the chain is listed in the plan | runs |

A TEST dry run still derives a key (`test:<runId>` when none is given) so port requests are well formed; it is not stored. (src: B/logic/action/ActionRuntime.kt, B/logic/action/handlers/ActionHandlers.kt)

### 5.5 Chaining

After a LIVE `run`, `onSuccess` ids run if the result is `Ok`, `onError` ids if it failed. Child keys are derived from the parent client key (`<parent>:s:<id>` / `<parent>:e:<id>`, digest-shortened over 128 chars). Bounded by call depth (5) and a per-run budget of 16 chained actions (`LIMIT_EXCEEDED` for the rest).

`onError` does **not** run after: `UNKNOWN_ACTION, UNSUPPORTED_ACTION_TYPE, FORBIDDEN, TENANT_DISABLED, INVALID_DEFINITION, LIMIT_EXCEEDED, IDEMPOTENCY_KEY_REQUIRED, IDEMPOTENCY_KEY_INVALID, IDEMPOTENCY_KEY_REUSED, ACTION_IN_PROGRESS, DEPENDENCY_UNAVAILABLE, AUDIT_UNAVAILABLE, RATE_LIMITED, IDEMPOTENCY_OUTCOME_UNKNOWN`. It **does** run after `MUTATION_REJECTED` (a definite "nothing applied"). A UI must not run its own `onError` handling after `IDEMPOTENCY_OUTCOME_UNKNOWN` (D-C0-13). (src: B/logic/action/ActionRuntime.kt `NO_CHAIN_CODES`)

### 5.6 Volatile-store guard

`VolatileActionGuard` and `VolatileWorkflowGuard` wrap the runtime. With `run-store=memory` and `allow-volatile-stores=false`, a LIVE run whose action (or anything its `onSuccess`/`onError` chain can start) changes state is refused with 503 `RUNTIME_STORES_VOLATILE` (non-retryable, nothing executed), and only to a caller who already passed `ACTION_EXECUTE`/`WORKFLOW_EXECUTE` (so it discloses nothing to others). TEST runs and non-mutating actions are unaffected. With the default `jdbc` store the guard allows everything. (src: B/wiring/VolatileStoreGuards.kt)

---

## 6. Data mutation actions

`CREATE_RECORD`, `UPDATE_RECORD`, `DELETE_RECORD`, `SUBMIT_FORM` and `CALL_API` reach data only through `ActionDataPortAdapter` (D-C0-18):

1. `WriteRequest(appId, mode, queryRef, kind, params, idempotencyKey)` / `OperationRequest(...)`: the key field **only accepts a derived key** (`init { require(DERIVED_KEY) }`, 43 chars of `[A-Za-z0-9_-]`); a raw client key cannot be expressed. `toString` prints parameter names only and a redacted key.
2. The adapter checks `ctx.projectId == appId` (`APP_MISMATCH`), loads the AppDefinition tenant-scoped (`APP_NOT_FOUND`), and resolves local ids with `AppDataBindingResolver`: `queryRef -> QueryDef (must be WRITE) -> data source slot -> registered source UUID + operationKey`. Resolution errors are definite and non-retryable (`UNKNOWN_REFERENCE, WRONG_MODE, DATA_SOURCE_UNBOUND, NO_OPERATION, INVALID_SOURCE_ID, INVALID_DEFINITION, ...`).
3. It refuses any non-LIVE request (`INVALID_MODE`), builds the `GatewayContext` from the action context (actor kind converted by name; a non-USER actor is refused `FORBIDDEN`), and calls `DataGateway.mutate(GatewayMutation(sourceId, operationKey, params, derivedKey))`.
4. Failures go through `DataWriteErrors.forWrite` (`docs/DATA_RUNTIME.md` section 10.4): unknown stays unknown, rejected stays rejected, everything ambiguous becomes `IDEMPOTENCY_OUTCOME_UNKNOWN`.

`recordId` for `UPDATE_RECORD` / `DELETE_RECORD` (FQ-ACT-02): the action keeps the logical input `recordId`; the data layer maps it to the single `key=` parameter of the approved mutation (`docs/DATA_RUNTIME.md` section 9.3). The definition must be written with the real key column, for example `target: "shop.orders key=id"` and a declared parameter `id`. A table column literally named `recordId` is no longer needed and still works. Remaining behaviours: updating or deleting a missing row succeeds with `affected: 0`; `INVALID_PARAMS` from such a write is HTTP 500 (known limit, section 17).

Authorization of a data action is layered: C4 checks `APP_USE`, `ACTION_EXECUTE`, `DATA_MUTATE`; the gateway checks `MUTATION_EXECUTE` (= `DATA_MUTATE`) again through the C1 `GatewayAuthorizer`. TEST never writes.

---

## 7. Idempotency

### 7.1 Derived key

The client key (`ActionRequest.idempotencyKey`, `^[A-Za-z0-9._:-]{1,128}$`) is only an input. What is stored, audited and forwarded is the derived key:

```
derivedKey = base64url( sha256( tenantId | appId | userId | actionId | clientKey ) )     // 43 chars, no padding, [A-Za-z0-9_-]
```

Stable (same inputs, same key, so a retry reuses the same downstream reservation), scoped (two users or tenants cannot collide or replay each other), opaque (cannot be reversed). A run without a client key (policy `OPTIONAL`/`NONE`) gets a one-shot key (`forFreshRun`) that de-duplicates nothing and says so. Logs and audit use `IdempotencyKeys.redact` (`k#` + 8 hex chars). (src: B/logic/action/IdempotencyKeys.kt)

### 7.2 Run records (`action_runs`)

Scope `(tenant, app, action, user, derived key)`; unique index uses `COALESCE(app_id, nil-uuid)` so "no app" is one scope. Decision table of `begin`:

| Existing record | Result |
|---|---|
| none | `Started` (attempt 1, new run id) |
| same key, different input fingerprint | `KeyReused` -> 409 `IDEMPOTENCY_KEY_REUSED` |
| `RUNNING` | `InProgress` -> 409 `ACTION_IN_PROGRESS` (retryable) |
| `SUCCEEDED` | `Replay` of the stored result (handler not run) |
| `FAILED`, `retryable=true` | restarted: attempt + 1, **new run id** |
| `FAILED`, non-retryable | `Replay` of the stored failure |

`complete` only succeeds for the run that still owns `RUNNING` (a swept run cannot be overwritten). Only the derived key and the result (`jsonb`, at most 1 MiB) are stored; no client key. TEST mode never reaches this table (`mode = 'LIVE'` check). (src: B/wiring/persistence/JdbcActionRunStore.kt, V29)

### 7.3 `IDEMPOTENCY_OUTCOME_UNKNOWN`

**A mutating abandoned or interrupted run is an unknown outcome. It is never retried, never routed to `onError`, and never compensated.**

- Code `IDEMPOTENCY_OUTCOME_UNKNOWN`, HTTP 409, **`retryable=false` always** (forced false in `RuntimeResponses` and `PortOutcome.toResult` even if a port says otherwise).
- Sources: (a) the data layer: an earlier attempt ended ambiguously (timeout, connection lost, upstream 5xx, unclassified, lease expired); (b) the executor: a **mutating** action whose handler ended `TIMEOUT` or `INTERRUPTED` is normalized to this code with `details.cause` holding the original code (`normalizeAmbiguous`, D-C4-17); (c) the sweeper: a `RUNNING` action run untouched for `action-run-stale-after` (default 10 min) is failed with this code and `details.cause = ABANDONED` when `action_runs.mutating` is true, and with a retryable `TIMEOUT` when it is false (`AbandonedRuns`, V29 column `mutating BOOLEAN NOT NULL DEFAULT TRUE`).
- The key stays reserved/`UNKNOWN`; a retry with the same key answers 409 again. The person (or an operator) checks the data source and starts a **new** operation with a **new** client key.
- UI action: no `onError` chain. Workflow step: the run **fails** with this code, the step's `onError` route is skipped, the step is not in `compensable` (so it is not compensated); earlier succeeded steps still compensate. No automatic retry whatever `retry.maxAttempts` says.
- Not unknown: `MUTATION_REJECTED` (422, certainly nothing applied; non-retryable with the same input; `onError` runs).

(src: B/logic/action/ActionResult.kt, ActionRuntime.kt, ActionRunStore.kt, B/wiring/DataWriteErrors.kt, B/wiring/RuntimeResponses.kt; docs/contracts/v2/data-runtime.md section 4b)

---

## 8. Workflow model

Definition (`WorkflowDefinition`, read from `workflows[]`; src: B/logic/workflow/WorkflowModel.kt, B/logic/workflow/canonical/CanonicalWorkflowCatalog.kt):

```
{ id, name?, enabled?, trigger MANUAL|SCHEDULE|ACTION, schedule?(cron, SCHEDULE only), timezone?, startStepId?, compensateOnCancel?, limits?,
  steps[{ id, kind ACTION|WAIT|APPROVAL|BRANCH|END, actionRef?, inputs, next?, onError?, retry?, timeoutMillis?, waitSeconds?,
          approval?, branches?, defaultNext?, compensationActionRef? }] }
```

Step kinds and semantics:

| Kind | Behaviour |
|---|---|
| `ACTION` | Runs `actionRef` through `ActionRuntime.execute` with `TriggerKind.WORKFLOW_STEP`, key `wf:<runId>:<stepId>` (a loop that re-enters the step uses `...:v<visit>`), `callDepth = run depth`, run mode, optional step timeout. `inputs` are `ValueRef`s: `{from:INPUT,path}`, `{from:STEP,stepId,path}`, `{from:LITERAL,value}`. |
| `WAIT` | `waitSeconds` timer (at most `maxWait`, default 30 days); TEST skips the wait (`wouldWaitSeconds`). The sweeper completes a due wait. |
| `APPROVAL` | Requests an approval and waits (section 12). TEST simulates (`wouldRequestApproval`). |
| `BRANCH` | Typed conditions only: `Compare(EQ,NE,GT,GTE,LT,LTE,IN,CONTAINS)`, `Exists`, `AllOf`, `AnyOf`, `Not`; **no expression language, no script, no URL**. First matching branch wins; else `defaultNext`; none = step fails `INVALID_DEFINITION`. A comparison with a missing operand is false (NE: true). |
| `END` | Finishes the run `SUCCEEDED`. |

Routing: `next` (else the following step in the list; end of list = success), `onError` (step to continue with when this step fails for good), `approval.onReject` / `onExpire`.

Validator limits: at most 50 steps; step ids `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`; at most 32 inputs per step, 10 branches, 20 condition nodes, condition depth 5; unreachable steps are an error; definitions may only **lower** the platform ceilings. (src: B/logic/workflow/WorkflowModel.kt `WorkflowDefinitionValidator`)

### 8.1 Retry, backoff, timeout

- `retry{maxAttempts (default 1 = no retry), initialBackoffMillis (1000), multiplier (2.0, range 1..10), maxBackoffMillis (300000)}`. Delay after failed attempt n = `min(initial * multiplier^(n-1), max)`, **never below 1 s** (`MIN_BACKOFF`).
- A failed step is retried only if `Failed.retryable`, the code is not `IDEMPOTENCY_OUTCOME_UNKNOWN`/`MUTATION_REJECTED`, and `attempt < maxAttempts`; the step moves to `RETRY_WAIT` with `wakeAt` and the **sweeper** publishes when due (the broker never delays).
- `RATE_LIMITED` from an action is pushback, not failure: the step waits as asked (at least 1 s, at most 300 s) and does **not** consume an attempt.
- Step `timeoutMillis` must be positive and at most the step-timeout ceiling (5 min). The action's own timeout (30 s ceiling) also applies. Workflow-level `maxDuration` (7 days) fails an overdue run `TIMEOUT`.

### 8.2 Limits (`WorkflowLimits`, ceiling defaults)

`maxSteps 50`, `maxStepExecutions 200` (loop guard: a cycle still ends), `maxRetries 10`, `maxDuration 7 days`, `maxWait 30 days`, `maxPayloadBytes 64 KiB` (measured as JSON string length, not UTF-8 bytes), `maxDepth 3` (workflow -> action -> workflow), `maxStepTimeout 5 min`.

### 8.3 Compensation and cancel

A succeeded `ACTION` step with `compensationActionRef` (LIVE, not simulated) is added to `compensable`. When the run **fails** (LIVE) the engine publishes a `~compensate` job and runs compensation actions in **reverse order**, key `wf:<runId>:<stepId>:comp`; a failing compensation leaves the run's `compensation = PARTIAL` and the others still run (`NONE, IN_PROGRESS, DONE, PARTIAL`). Cancel is idempotent: it stops further steps, cancels a pending approval, and compensates only if `compensateOnCancel` is set. **A cancelled run is not interrupted:** a step already running finishes its effect (and is then compensated if the definition asks); nothing after it starts. Compensation of a loop covers only the last visit of each step. (src: B/logic/workflow/WorkflowEngine.kt; docs/parallel/audit/FINAL-C4-runtime-design.md section 13)

### 8.4 TEST workflows

A TEST run reads the draft definition and **still creates a `workflow_runs` row** (`mode = 'TEST'`) with its definition snapshot, but writes no `action_runs`, creates no approval or notification, performs no mutation and no compensation. ACTION steps run in TEST mode (`WouldRun`), WAIT is skipped, APPROVAL simulated; steps are marked `simulated` with `dryRunLevel`. TEST and LIVE are separate idempotency scopes. TEST rows age through retention like LIVE rows.

### 8.5 Schedules, notifications, retention (status)

`SchedulerService`, `NotificationService` and `RetentionService` exist in `logic/**` (cron with DST handling, execution ledger, channel senders, two-stage retention) and are unit-tested, **but nothing in `wiring/**` constructs them on `integration/v2`**: there is no schedule route, no scheduled fire, no notification delivery and no retention job; `DeclaredSchedule`s are parsed but not registered; `WorkflowWorker.drainDeadLetters` has no caller (dead letters are not consumed). Status: DEFERRED. (src: grep of `backend/src/main` for the constructors; docs/contracts/v2/runtime-api.md section 7)

---

## 9. Workflow runtime and engine

### 9.1 Operations (`WorkflowRuntime`)

| Operation | Authorization | Result |
|---|---|---|
| `start(ctx, {workflowRef, input (object), idempotencyKey (required), mode, callDepth})` | tenant gate; definition lookup (`UNKNOWN_WORKFLOW`); **`APP_USE` + `WORKFLOW_EXECUTE`**; per-tenant `WORKFLOW_START` rate limit (in-process bucket, capacity 120, 20/s) | persists the run `PENDING`, publishes the first job, returns the run view; **idempotent** on (tenant, app, workflow, creator, mode, key): same key + same input returns the same run, different input `IDEMPOTENCY_KEY_REUSED`. Fail closed on audit (`AUDIT_UNAVAILABLE`). |
| `status(ctx, runId)` | scope first, then creator-who-still-uses-the-app, or `WORKFLOW_MANAGE` | run view, else `WORKFLOW_RUN_NOT_FOUND` |
| `cancel(ctx, runId)` | same as status | run view (idempotent; terminal runs return as they are) |
| `decideApproval(ctx, runId, approvalId, decision, comment)` | section 12 | approval decision view |

`mayView` order: (1) **resource scope**: tenant, workspace and project of the run equal those of the request (`null` is a value, never a wildcard; a run without a workspace is not visible from a request that names one); a wrong scope is `WORKFLOW_RUN_NOT_FOUND`, never 403, so existence is not leaked (F-1); (2) the creator shortcut, only if the creator still passes `APP_USE`; (3) otherwise `WORKFLOW_MANAGE`. (src: B/logic/workflow/WorkflowEngine.kt `mayView` line 846, `sameResourceScope` line 838)

### 9.2 Authority of a run

The run acts as the user who started it (`createdBy`). It is **not** an authority token: before every **ACTION or APPROVAL step** the engine re-checks `APP_USE` and `WORKFLOW_EXECUTE` for the creator through the live C1 `AccessPort` (a disabled user, removed membership or revoked permission fails the step `FORBIDDEN`, non-retryable; an authorization outage is retried within the step's budget and never treated as allowed), and the `ActionRuntime` then re-checks the tenant gate and the action permissions (`APP_USE`, `ACTION_EXECUTE`, `DATA_MUTATE`, declared permission) at that moment. A tenant disabled mid-run fails the run `TENANT_DISABLED`. WAIT, BRANCH and END steps have no effect and are not re-checked. Jobs on the queue carry no actor, permission or payload; the worker rebuilds the context from the stored run. (src: B/logic/workflow/WorkflowEngine.kt `authorityFailure` line 367, `process` line 281)

### 9.3 Worker, step claim and fencing

`process(job)` for one queue message:

1. Tenant gate; load the run (unknown run = done); a run whose `notBefore` is in the future is untouched (backing off); terminal runs only process a `~compensate` job; a message for a step that is not the current step is stale and acked.
2. **Claim**: one compare-and-set on the run row (`version`) moves the current step `PENDING`/due `RETRY_WAIT` -> `RUNNING`, `attempt + 1`, and writes `lease_owner = workerId`, `lease_until = now + stale-after`. One winner per attempt.
3. Execute **outside any lock** with a heartbeat thread (virtual) that renews the lease every `stale-after / 3` (at least 1 s); `renewLease` is a CAS that only the owner of the running attempt can win and returns false once the lease was taken over.
4. Record the outcome by compare-and-set. **Fencing:** the outcome is applied only if the step is still `RUNNING` with the same `attempt` and the run is not terminal; a late result of a worker whose lease was reclaimed, or of a cancelled/timed-out run, is dropped. There is no separate monotonic token; fencing is the triple (row `version` CAS, `attempt`, `lease_owner`/`lease_until`). Every transition that leaves `RUNNING` clears the lease.
5. After commit: publish the next step's job (or `~compensate`), audit the event.

Process-failure budget: a worker that **throws** on a run's message counts one process failure for that run (`recordProcessFailure`): the run is pushed back (`notBefore`, backoff `10 s * 2^(n-1)`, floor 1 s, cap 10 min); at `maxProcessFailures` (5) the run fails `DEAD_LETTERED` and exactly that message is rejected to the DLQ. **Interrupted worker thread:** `WorkflowWorker.runOnce` returns without polling when `Thread.currentThread().isInterrupted` (`WorkflowEngine.kt` line 920 and following): an interrupted worker (shutdown, cancellation) takes no further message, because every action it started next would be cut off at once and a mutating one would be reported as an unknown outcome for nothing; the message stays in the queue for a live worker. Covered by `WorkflowHardeningTests` (6). An infrastructure outage (store or tenant gate unavailable) is **not** a run failure: the message is acked and the sweeper recovers. Healthy runs are never dead-lettered by someone else's poison message.

### 9.4 Sweeper

`engine.sweep(limit = 100)` runs on every `WorkflowWorkerRunner.tick` (every `worker-delay-ms`, up to 50 messages then one sweep; failures are logged without detail and never stop the schedule). It handles: due WAIT timers (completes them), due retry backoffs (publishes), awaited approvals whose callback was lost (reconciles), lost first jobs and `PENDING` runs (republishes), abandoned `RUNNING` steps (lease expired: back to `RETRY_WAIT`, attempt kept, republish; the action's idempotency key prevents a second effect), overdue runs (`TIMEOUT`), stuck compensations, and expiry of due approvals (`approvals.expireDue`).

`claimForSweep` is fair and bounded: oldest-eligible first by rotation cursor `last_swept_at` (never-examined first), round-robin across tenants, a run examined at most once per 5 s (an approval-only wait at most once per 60 s). In the JDBC store the candidates come from one `SELECT` with `row_number() OVER (PARTITION BY tenant_id ...)`, then each chosen run is stamped by a **version-guarded `UPDATE`** (`last_swept_at = now`, `version + 1`); a run that changed under the sweeper is skipped for that pass, so a second node never receives the same run (there is no `SKIP LOCKED` on this path; it is used only by retention). A run the sweeper cannot move is backed off exponentially (floor 1 s) and does not hog the batch. Safe on several nodes (every action is a CAS or an idempotent publish). A step that legitimately runs longer than its lease is protected by the heartbeat; the lease is a safety net, so `stale-after` should still exceed the longest step timeout with margin.

Known imprecision (characterized by a test, not changed): if a retry budget ends **before** the action-run sweeper has turned an abandoned mutating run into UNKNOWN, the workflow run fails with `ACTION_IN_PROGRESS` rather than `IDEMPOTENCY_OUTCOME_UNKNOWN`; nothing is resent and the step is not compensated, but the code understates "may have been applied". (src: docs/parallel/audit/C4-V29-readiness-and-queue-contract.md section 12.2)

---

## 10. Queue

The queue is a **nudge**: the run store is the truth and no state is inferred from a message. A lost, duplicated or reordered message cannot corrupt a run because every transition is a compare-and-set. (src: B/logic/workflow/WorkflowQueue.kt)

### 10.1 Message

`v1|<jobId>|<tenantId>|<runId>|<stepId>` (at most 400 chars; step id matches the step-id pattern or is the pseudo step `~compensate`). It carries **no actor, no permission and no payload**. A message that does not parse is poison for no run and is dead-lettered.

### 10.2 Selection

`app.workflow.queue=memory|amqp` (section 2). `memory` = `InMemoryWorkflowQueue` (jobs lost on restart; development and tests; refused in production). `amqp` = `AmqpWorkflowQueue` on `amqp-client` with a cached connection (`CachedBrokerConnection`, automatic recovery). The topology is declared at start-up; a drifted topology (existing queue with other arguments) fails the start with `PRECONDITION_FAILED`, on purpose. (src: B/integration/queue/workflow/**)

### 10.3 RabbitMQ topology and discipline

| Item | Contract |
|---|---|
| Queue | `xweb.workflow.jobs`: durable **quorum** (`x-queue-type=quorum`), `x-delivery-limit` (default 5), `x-dead-letter-exchange`, `x-dead-letter-routing-key`, `x-dead-letter-strategy=at-least-once`, `x-overflow=reject-publish` |
| Dead letter | exchange `xweb.workflow.dlx` (direct, durable) -> queue `xweb.workflow.jobs.dlq` (quorum), routing key = DLQ name |
| Publish | persistent (`deliveryMode 2`), `text/plain`, `type=workflow.job.v1`, `messageId=jobId`, `correlationId=runId`; **publisher confirms** on a dedicated channel, `mandatory=true`, `waitForConfirmsOrDie(confirm-timeout)`; throws on nack, timeout or unroutable. On a throwing publish the run stays `PENDING` and the sweeper publishes again: a run can be late, never lost. |
| Consume | `basic.get` with **manual ack**; lease id = `generation:deliveryTag`, so a lease from a dead channel is ignored (the broker already returned the message; a reused tag can never ack another message). `consumer_count = 0` is the normal state of a healthy worker. |
| Ack matrix | handled and state saved -> `ack`; retry already persisted -> `ack`; outage durably deferred -> `ack`; malformed message -> `nack(requeue=false)`; run's failure budget exhausted -> `nack(requeue=false)` (the run is failed `DEAD_LETTERED` first); **never `nack(requeue=true)`** (a hot redelivery loop burns the delivery budget in milliseconds and, in an outage, dead-letters healthy runs). |
| Redelivery | a message whose consumer died (no ack) is redelivered by the broker and counted (`x-delivery-count`, informational: nothing in the engine branches on it); past `x-delivery-limit` the broker dead-letters it. A dead letter counts as **one** process failure of its run (`failFromDeadLetter`), not a verdict. |
| Duplicate delivery | safe: the engine re-reads the run; a message for a step that is no longer current is acked; the claim is a CAS; a finished step's action is replayed by its key. Duplicates of one job delivered to two workers do one effect. |
| Workflow retry | never uses broker redelivery: it is `RETRY_WAIT` + sweeper publish. |

Observability gaps: no `HealthIndicator` for the workflow runtime, no queue/DLQ depth metric, no worker liveness signal (the readiness group contains `db, redis, rabbit, minio`; `rabbit` is Spring's own connection, not the workflow channels). DLQ depth must be read from the RabbitMQ management API (`:15672` locally). DEFERRED. (src: docs/parallel/audit/C4-V29-readiness-and-queue-contract.md sections 11.5, 12.4)

---

## 11. Durable run stores (V29, plus V33)

`app.workflow.run-store=jdbc` (default): `JdbcActionRunStore`, `JdbcWorkflowRunStore`. V29 is immutable; any schema change is a new forward migration. V31 is a permanent void gap; V32 is the dynamic organization; **V33 = `approvals`** (section 12.7). `outOfOrder` stays off. (src: backend/src/main/resources/db/migration/V29__workflow_run_persistence.sql; docs/parallel/MIGRATION_LEDGER.md)

| Table | Key columns and rules |
|---|---|
| `action_runs` | `run_id` PK; `tenant_id`; `workspace_id`/`app_id` (both or neither; composite FKs to `workspaces`/`projects`); `action_id`; `user_id`; `idempotency_key` (derived key); `fingerprint`; `mode='LIVE'` only; `status` `RUNNING\|SUCCEEDED\|FAILED`; `attempt`; `result` JSONB (at most 1 MiB; a finished row has result and end time); `worker_id` (diagnostic); **`mutating BOOLEAN NOT NULL DEFAULT TRUE`** (decides what an abandoned run becomes); unique `(tenant, COALESCE(app), action, user, key)`; indexes for stale sweep and retention |
| `workflow_runs` | `run_id` PK; tenant, workspace, `app_id`; `workflow_id`; `mode LIVE\|TEST`; `status PENDING\|RUNNING\|WAITING\|SUCCEEDED\|FAILED\|CANCELLED`; `created_by`, `actor_kind`; `idempotency_key`, `fingerprint`; `input` and **`definition` (the snapshot taken at start)** JSONB (at most 1 MiB each); `current_step_id`; `compensable`; `step_executions`, `depth`; `error_code/message`; `compensation NONE\|IN_PROGRESS\|DONE\|PARTIAL`; denormalised `cur_step_status`, `cur_wake_at`, `cur_approval_id`; `process_failures`, `sweep_failures`, `not_before`, `last_swept_at`; **`lease_owner VARCHAR(64)`, `lease_until`** (both or neither, constraint `workflow_runs_lease_check`); `redacted_at`; `version` (CAS); unique `(tenant, app, workflow, created_by, mode, idempotency_key)` |
| `workflow_run_steps` | PK `(run_id, step_id)`; `status PENDING\|RUNNING\|WAITING\|RETRY_WAIT\|SUCCEEDED\|FAILED`; `attempt`, `visit`; `input`/`output`; error; `started_at`, `finished_at`, `wake_at`; `approval_id`; `simulated`, `dry_run_level NOT_EXECUTED\|VALIDATED\|SANDBOX`; `compensated`; FK `(run_id, tenant_id)` ON DELETE CASCADE. Written in the **same transaction** as the run's compare-and-set. |

Properties: every table has `tenant_id NOT NULL REFERENCES tenants(id)`; the adapters insert only for an application that belongs to the tenant (default deny); no client idempotency key is stored for actions; workflow runs keep the key the caller gave `start`, but step keys are derived from the run id.

**Restart and recovery (what is promised by crash point)** (src: docs/parallel/audit/C4-V29-readiness-and-queue-contract.md sections 3, 12):

| Crash point | Persisted state | Recovery | Guarantee |
|---|---|---|---|
| after `create`, before publish / publish fails | run `PENDING` | sweeper republishes | starts once; late, never lost |
| after step claim, before the action ran | step `RUNNING(attempt n)`, lease | lease expires; sweeper -> `RETRY_WAIT`, attempt n+1, same derived key | action runs once |
| action finished and saved, before ack | step `SUCCEEDED`, next step `PENDING` | broker redelivers; stale message acked | no repeat |
| mutation sent, outcome unknown, worker dies | step `RUNNING`, action run `RUNNING` | action sweep -> UNKNOWN; re-drive answers UNKNOWN | never a second effect; run `FAILED(IDEMPOTENCY_OUTCOME_UNKNOWN)`, no retry, no `onError`, ambiguous step not compensated, earlier steps compensated |
| UNKNOWN already persisted, then restart | step/run `FAILED`, action run `FAILED` non-retryable | nothing re-drives a terminal run; replay returns the failure | UNKNOWN never becomes retry |
| consumer dies holding the message | unchanged | broker redelivers (counted, limit -> DLQ) | duplicate harmless |
| two workers on one run | CAS on `version` | loser's claim fails | one owner per attempt |
| backend restart | all of the above in PostgreSQL | sweeper at start | resumes without repeating a finished step |
| broker restart | quorum queue, persistent messages | consumers reconnect | no lost job (and the sweeper would republish one) |

Retention: `RetentionService` (two-stage for workflow runs: payload redaction after 14 days, delete after 90; `action_runs` 30 days; approvals 180 days; never below 7 days because deleting a row ends the replay guarantee of its key) is **not scheduled** by any wiring (section 8.5). Run rows therefore grow until a retention job exists. `ActionRunRecovery` (the action-run sweeper) **is** wired.

---

## 12. Approval runtime

The approval runtime is integrated: a LIVE `APPROVAL` step creates a durable approval, the run waits, an approver decides through a dedicated route, and the decision resumes the run exactly once. Decision record: D-C0-61 (`docs/parallel/DECISIONS.md`). Contract: `docs/contracts/v2/runtime-api.md` (route, `approvalId` in the step view, permission mapping). Migration: `backend/src/main/resources/db/migration/V33__approvals.sql` (undo `docs/parallel/c0/undo/U33__approvals.sql`). Original design handoff: `docs/parallel/audit/C4-FQ-WF-01-approval.md`. Without the `approvals` table (or with `app.workflow.approvals=off`) the engine is built without an `ApprovalService` and a LIVE `APPROVAL` step fails `NOT_IMPLEMENTED` ("Approvals are not wired") exactly as before; see 12.8.

### 12.1 Model

`Approval` (src: B/logic/approval/ApprovalModel.kt): `id`, `tenantId`, `appId`, `title` (1-200 chars, non-sensitive), `requestedBy`, `requestedAt`, `expiresAt`, `approverSpecs`, **`approvers` (a snapshot of user ids)**, `requiredApprovals` (>= 1), `allowSelfApproval`, `status`, `decisions[]`, `comments[]`, `source {workflowRunId, stepId}`, `idempotencyKey`, `finishedAt`, `version`. Status `PENDING | APPROVED | REJECTED | EXPIRED | CANCELLED` (the last four are final). **`WAITING_APPROVAL` is not a status**: the run is `WAITING`, the step is `WAITING` with an `approvalId`, and `WAITING_APPROVAL` is the audit event name written when the step starts waiting.

Approver specs in a definition: `{"kind":"USER","userId":..}`, `GROUP`, `ROLE`, `DEPARTMENT_MANAGER`. They are resolved by the C1 `PrincipalResolver` **once, when the step starts**, and the resolved user ids are stored as the snapshot; later group or role changes never add or remove approvers of a pending request. The requester is removed from the snapshot unless `allowSelfApproval`. At most 50 approvers; fewer eligible approvers than `requiredApprovals` is refused (`APPROVAL_INVALID`, the step fails). Today C1 answers users and roles only (groups and department managers are denied by C1). `expiresIn` defaults to 1 day in a definition (`expiresInSeconds` 86400) and is bounded 1 minute to 30 days by the service. (src: B/logic/approval/ApprovalService.kt, B/wiring/C1PortAdapters.kt)

### 12.2 Flow

1. A LIVE `APPROVAL` step first passes the run-creator authority re-check (`APP_USE`, `WORKFLOW_EXECUTE`; `WorkflowEngine.kt` line 367), then `ApprovalService.request` with idempotency key `wf:<runId>:<stepId>` (a re-delivered or re-swept step finds the same approval, never a second one: unique `(tenant_id, idempotency_key)`). The step becomes `WAITING` with `approvalId`; the run `WAITING`; audit `WORKFLOW_WAITING_APPROVAL`, `APPROVAL_REQUESTED`.
2. An approver decides (12.3). When the approval becomes final the service calls the `ApprovalListener` (`engine.onFinal` -> `resumeFromApproval`, `WorkflowEngine.kt` line 584), which **resumes the run exactly once** by a compare-and-set on the run row: `APPROVED` completes the step (`{approved:true, approvals:n}`) and advances; `REJECTED`/`EXPIRED` fail the step (`APPROVAL_REJECTED` / `APPROVAL_EXPIRED`) and route to `approval.onReject`/`onExpire` if declared, else fail the run. `CANCELLED` does not resume (the run was cancelled). If the callback is lost, the sweeper reconciles the run (an approval-only wait is polled at most every 60 s).
3. The next step runs with the **run creator's** authority re-checked per effectful step, so the approver does not need `DATA_MUTATE` and cannot widen the creator's rights.
4. TEST mode simulates the step and creates no approval.
5. Cancelling the run cancels a pending approval (status `CANCELLED`); a later decision answers `APPROVAL_ALREADY_DECIDED`.

### 12.3 Decision route

`POST /api/v1/workspaces/{ws}/projects/{p}/app-runtime/workflow-runs/{runId}/approvals/{approvalId}/decision`, body `{"decision":"APPROVE"|"REJECT","comment"?: string <= 1000}`. Strict: no identity fields; an unknown key or a missing body is 400 `INVALID_REQUEST`. The approver is always the session user; project, run and approval come from the path **after** `AccessService.forProject` proved they belong together. The route is declared in `AppRuntimeActionController` (`AppRuntimeActionController.kt` line 112), whose class is `@ConditionalOnProperty(app.workflow.enabled=true)` (line 36): with the flag off it answers 404 like every `/app-runtime` workflow route. Body parser `RuntimeRequests.approvalDecision` (`RuntimeRequests.kt` line 35); engine `WorkflowEngine.decideApproval` (`WorkflowEngine.kt` lines 226-247); response `RuntimeResponses.approvalDecision` (`RuntimeResponses.kt` line 123).

**Authorization order (server side only):**

1. Tenant enabled (`TENANT_DISABLED` 403), then **scope**: tenant -> workspace -> project of the run (`sameResourceScope`, line 838); a wrong scope is `WORKFLOW_RUN_NOT_FOUND` (404), never 403.
2. **`WORKFLOW_MANAGE`** through the live C1 `AccessPort` (resource kind `APPROVAL`, the approval id), checked **before** the approval is looked up: denied = `FORBIDDEN` 403, the same answer for an unknown approval, so nothing is revealed. **There is no `APPROVAL_DECIDE` permission in this release candidate.**
3. The approval must belong to **this run and this application** (`source.workflowRunId == run.runId`, `appId == run.appId`), else `APPROVAL_NOT_FOUND` (404).
4. `ApprovalService.decide`: the user must be in the **approver snapshot** (else `FORBIDDEN` 403, audited `APPROVAL_DECISION_DENIED`); the requester cannot decide their own request unless `allowSelfApproval`. Never a role string, organization membership, manager/head relation, position or grade is consulted at decision time.
5. Decision semantics (order matters, as coded in `ApprovalService.decide`):
   - the same user deciding the **same** way again = idempotent, `200` with the current state (this holds even after the approval is final);
   - the same user deciding the **opposite** way = **409 `APPROVAL_CONFLICT`**;
   - any other approver deciding a **final** approval (approved, rejected, expired, cancelled) = **409 `APPROVAL_ALREADY_DECIDED`**;
   - a decision after `expiresAt` lazily expires the request and answers `APPROVAL_ALREADY_DECIDED` ("expired"): a late decision can never revive it;
   - **any single `REJECT` rejects the whole request**; `APPROVE` approves when approvals >= `requiredApprovals`, otherwise the approval stays `PENDING` and the run stays `WAITING`;
   - decision, comment and status are one row replaced by one compare-and-set (`version`): a decision is never visible without its status, two concurrent decisions cannot both win, and the loser re-reads (up to 5 tries) and then sees the winner, or gets 409 `APPROVAL_CONFLICT` with `retryable=true` ("Concurrent update, please retry").

Response `200`: `{"approvalId", "approvalStatus", "requiredApprovals", "approvals", "run": <run view>}`. Because the resumed step runs on a worker, `run.status` may still say `WAITING` for a moment. Failures use the common envelope (13.4): 400 (bad body; `APPROVAL_INVALID` is mapped to `INVALID_INPUT`), 403, 404 (`WORKFLOW_RUN_NOT_FOUND`, `APPROVAL_NOT_FOUND`), 409, 503 (`DEPENDENCY_UNAVAILABLE`, store down), 501 (`NOT_IMPLEMENTED` when approvals are not wired). Note: D-C0-61 item 2 writes the run error as `RUN_NOT_FOUND`; the code and `RuntimeResponses.status` use `WORKFLOW_RUN_NOT_FOUND`.

### 12.4 `WORKFLOW_MANAGE` is broad (known limit)

`WORKFLOW_MANAGE` is also the right to view and cancel **any** run of the project. Today only `WORKSPACE_ADMIN` holds it (`PermissionMatrix.managerOnly`, `Permission.kt` line 105); no project role does. Consequence: **to decide an approval a person must be both a named approver (in the snapshot) and a workspace admin.** A dedicated `APPROVAL_DECIDE` permission (an approver could decide only approvals they are named on, without being able to cancel runs) is a proposal for C1: the engine already passes `ResourceKind.APPROVAL` with the approval id, so the switch is one constant in `decideApproval`. DEFERRED. (src: D-C0-61 item 4)

### 12.5 Run view change

Run view step objects carry `approvalId`, **present only when set** (`RuntimeResponses.kt` line 110: it is omitted, not `null`, otherwise). `runtime-api.md` documents it as `"approvalId"?`; D-C0-61 calls it nullable, and the earlier contract line "no approval id" is superseded.

### 12.6 Restart behaviour

The approval row, the run row and the queue message are all durable, so a waiting run survives a node loss: the approval keeps its snapshot and decisions in PostgreSQL; after a restart a decision still resumes the run; a resume job redelivered after a consumer death before ack runs the next step once; a lost resume nudge is recovered by another node's sweeper; expiry (`onExpire`) is driven by the sweeper and works across restarts. Test classes that cover this (counts of `@Test`, not run): `ApprovalDecisionTests` (10, engine level), `JdbcApprovalStoreTests` (9, real PostgreSQL), `WorkflowApprovalG3Tests` (7, PostgreSQL + RabbitMQ, node-level restart: a new engine, store and queue consumer over the same databases), `ApprovalJourneyE2ETests` (5, over HTTP: approve with `UPDATE_RECORD` by `recordId`, reject with `onReject`, cancel while waiting). A kill-and-restart of the whole Spring/JVM application is **not** in the HTTP test, and expiry over HTTP is not re-tested (stated in the handoff, `docs/parallel/audit/C4-FQ-WF-01-approval.md`). The expiry path itself is covered at engine level (`WorkflowEngineTests`).

### 12.7 Migration V33 (`approvals`)

V31 is a permanent void gap and V32 is the dynamic organization; V33 is the approvals table (`MIGRATION_LEDGER.md`, D-C0-61 item 1). The file creates one table and five indexes and touches no existing table (src: backend/src/main/resources/db/migration/V33__approvals.sql):

`approvals(id UUID PK, tenant_id UUID NOT NULL FK tenants, app_id UUID FK projects(id) NULL, title VARCHAR(200), requested_by UUID, requested_at, expires_at, approver_specs JSONB, approvers UUID[], required_approvals INT >= 1, allow_self_approval BOOLEAN DEFAULT FALSE, status IN (PENDING,APPROVED,REJECTED,EXPIRED,CANCELLED), decisions JSONB array, comments JSONB array, source_run_id UUID, source_step_id VARCHAR(128), idempotency_key VARCHAR(160), finished_at, version BIGINT DEFAULT 0)`

with `CHECK ((status = 'PENDING') = (finished_at IS NULL))` and unique `(tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL`; indexes `approvals_due_idx` (`expires_at WHERE PENDING`), `approvals_inbox_idx` (GIN on `approvers WHERE PENDING`), `approvals_source_idx` (`tenant_id, source_run_id`), `approvals_retention_idx` (`finished_at WHERE status <> 'PENDING'`). Documented limits: **no foreign key to `workflow_runs`** (retention purges finished runs and finished approvals independently; `purgeFinal` keeps the approval of a run still active), and **`app_id` is a plain FK to `projects(id)`, not a composite `(workspace, project)` FK** (that would need a new unique constraint on `projects`); tenant consistency is enforced by the engine's scope check before the insert and by every query being keyed on `tenant_id`. `requested_by` has no FK to `users` (like `workflow_runs.created_by`). Manual undo: `U33__approvals.sql` refuses while any approval is `PENDING` (a waiting run would never resume); final approvals are dropped with the table.

### 12.8 Wiring and `app.workflow.approvals`

Read with an inline default in `AppRuntimeConfiguration` (`@Value("${app.workflow.approvals:auto}")`, line 132; the property is **not** listed in `application.yml`). Any value other than `auto|jdbc|memory|off` stops the start (`approvalService`, lines 180-195):

| Value | Behaviour |
|---|---|
| `auto` (default) | durable `JdbcApprovalStore` when `to_regclass('public.approvals')` exists, otherwise approvals are **not wired**, a warning is logged and a LIVE `APPROVAL` step stays `NOT_IMPLEMENTED`. After V33 is applied nothing needs switching on. The probe hard-codes the `public` schema. |
| `jdbc` | durable; the table must exist (a missing table fails the first approval loudly) |
| `memory` | volatile, dev/test only, **only with `run-store=memory`** (otherwise the application does not start: a durable run must not wait for a volatile approval) |
| `off` | not wired |

The service is built with the C1 principal resolver (`C4PrincipalResolverAdapter`), the tenant gate and the logic audit port; the **notify port is `null`**, so `notifyTemplateRef` notifications are not sent. A forwarder closes the loop between the service (built first) and the engine (built second). `VolatileWorkflowGuard.decideApproval` delegates unchanged (`VolatileStoreGuards.kt` line 97): a decision acts on an approval that a run already created.

### 12.9 Known limits of approvals

- `notifyTemplateRef` is accepted by the definition and the service but **no notification is sent** (the NOTIFY port is not wired). There is no inbox or list endpoint; the decision route needs the run id and the approval id (both are in the run view of a waiting run). DEFERRED. (src: D-C0-61 item 5; `runtime-api.md` section 7)
- `WORKFLOW_MANAGE` is broad and only workspace admins hold it (12.4).
- `ROLE`/`GROUP`/`DEPARTMENT_MANAGER` approvers are snapshotted at step start; if the product wants "approvers must be explicit users", restrict it at definition validation (the runtime itself never uses a role at decision time).
- Cross-tenant approvals are denied (`CrossTenantApprovalPolicy.DENY_ALL`; a foreign tenant's approval answers as not found).

---

## 13. HTTP routes (`/app-runtime`)

Base `B_RT = /api/v1/workspaces/{workspaceId}/projects/{projectId}/app-runtime`; mounted only with `app.workflow.enabled=true` (queries route: `docs/DATA_RUNTIME.md` section 12.1). Session cookie plus `X-XSRF-TOKEN` on `POST`. The workspace/project proof (`AccessService.forProject`) answers 404 `PROJECT_NOT_FOUND` for a non-member, another workspace or another tenant. The caller is always `ActorKind.USER`. Strict bodies: unknown fields are 400 `INVALID_REQUEST`; `tenantId`, `userId`, `dataSourceId`, `sql`, `url` are never accepted. A `TEST` request needs `PROJECT_EDIT`. (src: B/wiring/AppRuntimeActionController.kt, B/wiring/RuntimeRequests.kt)

### 13.1 Execute an action

`POST {B_RT}/actions/{actionId}/execute`

Request: `{"mode":"LIVE|TEST" (default LIVE), "inputs":{...}, "idempotencyKey":"^[A-Za-z0-9._:-]{1,128}$", "trigger":{"eventName":"<section>.<event>"}}` (all optional; required for mutating LIVE types: `idempotencyKey`). The trigger kind is always `UI_EVENT`; `eventId` is not accepted; `CONTEXT`-mapped inputs cannot be supplied.

Response envelope (HTTP status from the table in section 16):

```
{"status":"OK","actionId","mode","output":{...},"followUps":[{"actionId","on":"SUCCESS|ERROR","status":"OK|FAILED|WOULD_RUN",...}]}
{"status":"FAILED","actionId","mode","error":{"code","message","retryable","details":{...}},"followUps":[...]}
{"status":"WOULD_RUN","actionId","mode":"TEST","type","level":"NOT_EXECUTED|VALIDATED|SANDBOX","plan":{...},"output":{...}?,"reason"?,"followUps":[]}
```

(The code also emits `plan` and `reason`, which `runtime-api.md` omits.) `Retry-After` accompanies 429. `retryable` is copied from the runtime and is never true for `IDEMPOTENCY_OUTCOME_UNKNOWN` and `MUTATION_REJECTED`. An unexpected exception answers 500 `INTERNAL`. There is **no other write route**.

### 13.2 Start a workflow

`POST {B_RT}/workflows/{workflowId}/runs` -> **202** + run view. Body `{"mode":"LIVE|TEST" (opt), "input":{object} (opt), "idempotencyKey":"..." (required: missing = 400 `IDEMPOTENCY_KEY_REQUIRED`)}`. The same key and input return the same run (still 202).

### 13.3 Read and cancel a run

- `GET {B_RT}/workflow-runs/{runId}` -> 200 run view. Creator (who still passes `APP_USE`) or `WORKFLOW_MANAGE`; anything else, and any wrong scope, is 404 `WORKFLOW_RUN_NOT_FOUND`.
- `POST {B_RT}/workflow-runs/{runId}/cancel` -> 200 run view; idempotent.
- `POST {B_RT}/workflow-runs/{runId}/approvals/{approvalId}/decision` -> section 12.3.

Run view (src: B/wiring/RuntimeResponses.kt `workflowRun`):

```
{"runId","workflowId","mode","status","currentStepId",
 "steps":[{"stepId","status","attempt","output","errorCode","errorMessage","startedAt","finishedAt","approvalId"?,"simulated","dryRunLevel"}],
 "errorCode","errorMessage","compensation","createdAt","updatedAt","finishedAt"}
```

No `appId`, creator id or definition snapshot is exposed. `steps` lists the steps that have state, in definition order; `approvalId` appears only on a step that has one.

### 13.4 Error envelope

Non-2xx bodies that are not an action result envelope: `{"code","message","requestId","retryable","details":{}}` (`ApiError` plus `retryable`). Errors raised by `AccessService` (404 `PROJECT_NOT_FOUND`, 403 `FORBIDDEN`) use the standard `ApiError`.

---

## 14. Permissions

Canonical permission codes (src: B/access/Permission.kt `PermissionMatrix`; B/logic/action/ActionPorts.kt `LogicPermissions`):

| Code | Used for | Held today by |
|---|---|---|
| `APP_USE` | any action/workflow call; re-checked per effectful workflow step | project VIEWER, EDITOR, PUBLISHER, OWNER; WORKSPACE_ADMIN |
| `ACTION_EXECUTE` | every action execute (even NAVIGATE) | project EDITOR, OWNER; WORKSPACE_ADMIN (**not** VIEWER/PUBLISHER) |
| `DATA_MUTATE` | data-mutating action types (and the gateway's `MUTATION_EXECUTE`) | WORKSPACE_ADMIN only |
| `WORKFLOW_EXECUTE` | start a workflow; `START_WORKFLOW`; per-step re-check | WORKSPACE_ADMIN only |
| `WORKFLOW_MANAGE` | view/cancel others' runs; **decide an approval** | WORKSPACE_ADMIN only |
| declared `permissionRef` | extra permission of one action | per definition |
| `PROJECT_EDIT` (`APP_EDIT`) | any `mode: TEST` request (the controller requires it, and C1 `AccessPort.check` also refuses `mode=TEST` without it: `access/adapters/AccessPort.kt` line 36) | project EDITOR, OWNER; WORKSPACE_ADMIN |

`DATA_SOURCE_MANAGE`, `DATA_MUTATE`, `WORKFLOW_EXECUTE`, `WORKFLOW_MANAGE` are not held by any project role: they need `WORKSPACE_ADMIN` until explicit grants exist (sharing, T15). So, in the current policy, **running a data-mutating action or any workflow requires a workspace admin**; a different rule is a C1 policy request. A platform SYSTEM_ADMIN has no business permission unless it is a workspace member (or the legacy flag `app.tenancy.system-admin-business-access` is on). (src: B/access/Permission.kt lines 98-120)

---

## 15. Audit events

All rows go to the append-only `audit_events` through `AuditService` (no new table).

| Domain | Action names (`<DOMAIN>_<EVENT>`) | Notes |
|---|---|---|
| Action | `ACTION_STARTED, ACTION_SUCCEEDED, ACTION_FAILED, ACTION_DENIED, ACTION_REJECTED, ACTION_REPLAYED, ACTION_PREVIEWED` | fail closed for `STARTED`; no input/output values |
| Workflow | `WORKFLOW_START_REQUESTED` (fail closed), `WORKFLOW_SUCCEEDED, WORKFLOW_FAILED, WORKFLOW_CANCELLED, WORKFLOW_STEP_RETRY, WORKFLOW_STEP_FAILED_ROUTED, WORKFLOW_WAITING_APPROVAL, WORKFLOW_COMPENSATED, WORKFLOW_COMPENSATION_PARTIAL, WORKFLOW_DENIED` | attributes: workflow id, step id |
| Approval | `APPROVAL_REQUESTED, APPROVAL_APPROVED_BY, APPROVAL_REJECTED_BY, APPROVAL_DECISION_DENIED, APPROVAL_EXPIRED, APPROVAL_CANCELLED, APPROVAL_COMMENTED, APPROVAL_REQUEST_DENIED` | decisions record the actor; expiry is a system event |
| Data | see `docs/DATA_RUNTIME.md` section 11.6 | the gateway audits every mutation (`DATA_MUTATION_RUN`) |

---

## 16. Error code reference

HTTP status from `RuntimeResponses.status`; **any code not listed answers 500**. `retryable` is the value the runtime sets (the HTTP status never makes a code retryable). (src: B/wiring/RuntimeResponses.kt; BR for the approval codes)

| Code | HTTP | Meaning | Retryable |
|---|---|---|---|
| `INVALID_REQUEST` | 400 | strict body parse: unknown field, wrong type, bad mode | no |
| `INVALID_INPUT` | 400 | input missing, wrong type, derived by the server, or approval request invalid | no |
| `IDEMPOTENCY_KEY_INVALID` | 400 | client key outside `[A-Za-z0-9._:-]{1,128}` | no |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | mutating LIVE action or workflow start without a key | no |
| `FORBIDDEN` | 403 | permission denied (also: not an approver, not WORKFLOW_MANAGE) | no |
| `TENANT_DISABLED` | 403 | tenant disabled; a running run fails with it | no |
| `UNKNOWN_ACTION` | 404 | unknown, other tenant/app, disabled, wrong trigger | no |
| `UNKNOWN_WORKFLOW` | 404 | unknown or disabled workflow | no |
| `WORKFLOW_RUN_NOT_FOUND` | 404 | unknown run, wrong scope, or no right to see it | no |
| `APPROVAL_NOT_FOUND` | 404 | approval is not an approval of this run/app, or unknown | no |
| `IDEMPOTENCY_OUTCOME_UNKNOWN` | 409 | an earlier attempt may or may not have been applied; never run again | **never** |
| `ACTION_IN_PROGRESS` | 409 | the same request is already running | yes |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | data-layer reservation still running | yes |
| `IDEMPOTENCY_KEY_REUSED` | 409 | key used with different input | no |
| `IDEMPOTENCY_CONFLICT` | 409 | data-layer key reused with different parameters | no |
| `CONFLICT` | 409 | concurrent update of a run, retry | yes |
| `APPROVAL_CONFLICT` | 409 | the same user decided the other way; also a concurrent-update exhaustion | no (opposite decision); yes (concurrency) |
| `APPROVAL_ALREADY_DECIDED` | 409 | approval is final (approved, rejected, expired or cancelled) | no |
| `MUTATION_REJECTED` | 422 | data source refused, certainly nothing applied | **never** |
| `INVALID_DEFINITION` | 422 | action/workflow definition invalid | no |
| `LIMIT_EXCEEDED` | 422 | input, depth, chain budget, steps, step executions, payload | no |
| `UNSUPPORTED_ACTION_TYPE` | 422 | no handler for the type | no |
| `MAPPING_REF_REQUIRED` | 422 | R1: say which mapping shapes the query | no |
| `RATE_LIMITED` | 429 | per-tenant budget (`Retry-After`, `details.retryAfterMillis`) | yes |
| `NOT_IMPLEMENTED` | 501 | NOTIFY, approvals not wired, missing port | no |
| `DEPENDENCY_UNAVAILABLE` | 503 | store, authorization, tenant gate, limiter or definitions unavailable | yes |
| `AUDIT_UNAVAILABLE` | 503 | audit write failed; nothing ran | yes |
| `DATA_RUNTIME_UNAVAILABLE` | 503 | no `DataGateway` bean | no |
| `RUNTIME_STORES_VOLATILE` | 503 | LIVE change refused with a volatile run store | no |
| `TIMEOUT` | 504 | non-mutating action timed out (a mutating one becomes UNKNOWN) | yes if non-mutating or de-duplicated by a key |
| `INTERRUPTED` | 500 | executor interrupted (non-mutating) | as `TIMEOUT` |
| `HANDLER_ERROR` | 500 | unexpected handler exception (message hidden) | no |
| `INTERNAL` | 500 | controller-level unexpected error | no |

Run-level and step-level codes that appear in a run view's `errorCode` (not as an HTTP status): `DEAD_LETTERED` (run failed `maxProcessFailures` times), `APPROVAL_REJECTED`, `APPROVAL_EXPIRED`, `TIMEOUT` (workflow exceeded `maxDuration`), `TENANT_DISABLED`, `INVALID_DEFINITION` (step no longer exists, or no branch matched), `LIMIT_EXCEEDED` (step executions or payload), plus whatever the failed step's action returned. Data-layer write codes inside an action: `docs/DATA_RUNTIME.md` section 16.

---

## 17. Known gaps and limits (plain list)

1. **NOTIFY and notifications are not wired**: a `NOTIFY` action fails `NOT_IMPLEMENTED`; `notifyTemplateRef` on approvals sends nothing. DEFERRED.
2. **Schedules, retention, the dead-letter consumer and event dispatch are not wired** (8.5): no schedule route or fire, no retention job (run tables grow), dead letters are not consumed, and `dispatch` has no route. DEFERRED.
3. **`WORKFLOW_MANAGE` is broad and only workspace admins hold it**; no `APPROVAL_DECIDE` permission in this release candidate (12.4). Likewise `DATA_MUTATE` and `WORKFLOW_EXECUTE` are admin-only today.
4. **In-process rate limits**: with N nodes the per-tenant budgets are about N times larger (a cluster-wide limiter is a C0 adapter that does not exist).
5. **`INVALID_PARAMS` and every code outside `RuntimeResponses.status` answer HTTP 500** (for example `INVALID_PARAMS` from a write, `INTERRUPTED`, `HANDLER_ERROR`); the body is correct and non-retryable.
6. **UPDATE/DELETE of a missing row succeeds** with `affected: 0`.
7. **A cancelled run is not interrupted** and compensation covers only the last visit of a looped step.
8. **ACTION_IN_PROGRESS imprecision** (9.4).
9. **Workflow payload limit is measured in characters**, not UTF-8 bytes.
10. **Approvals**: no inbox endpoint, no approver notification; explicit-user approvers are the only reliably resolved kind today; a real whole-JVM restart and expiry over HTTP are not in the HTTP tests (12.6, 12.9).
11. **The scheduler-started run has `workspaceId = null`**, so it is not visible through a request that names a workspace (fail-closed by design; the schedule UI would need the enqueuer to supply the workspace). Relevant when the scheduler is wired. (src: docs/parallel/audit/C4-V29-readiness-and-queue-contract.md section 13)
12. **No observability** for the worker, queue depth or DLQ (10.3).
13. **Broker TLS and multiple broker addresses** are not read by the queue configuration.
14. **Workflow TEST runs create rows** that age through retention like LIVE runs.
15. **`app.workflow.enabled` without `app.data-platform.enabled`** leaves data actions failing `DATA_RUNTIME_UNAVAILABLE`.

---

## 18. Test evidence (counted, not run)

Counts are `@Test` / `@ParameterizedTest` annotations per class on `integration/v2` (HEAD `28376de`): a size indicator, not a pass result; nothing was executed while writing this document. Verify with `cd backend && ./gradlew test --tests '<class>'` (JDK 21, Docker for Testcontainers).

| Area | Class (`@Test` count) |
|---|---|
| Action and workflow engine | `ActionRuntimeTests` (64), `WorkflowEngineTests` (65), `WorkflowSweeperTests` (13), `WorkflowLeaseTests` (9), `WorkflowRunScopeTests` (8), `WorkflowAbandonedWriteTests` (1), `WorkflowHardeningTests` (6), `RuntimeAuthorizationTests` (27) |
| Queue | `AmqpWorkflowQueueTests` (9), `WorkflowQueueDisciplineTests` (4) |
| Durable stores | `JdbcActionRunStoreTests` (23), `JdbcWorkflowRunStoreTests` (28), `RunStoreConfigurationTests` (6) |
| Restart and recovery (PostgreSQL + RabbitMQ) | `WorkflowG3RecoveryTests` (8), `WorkflowG3SweeperRaceTests` (8), `WorkflowG3BrokerRestartTests` (2), `WorkflowG3SpringRestartTests` (3) |
| Approvals | `ApprovalDecisionTests` (10), `JdbcApprovalStoreTests` (9), `WorkflowApprovalG3Tests` (7), `ApprovalJourneyE2ETests` (5) |
| Data path used by actions | `RecordKeyActionE2ETests` (9), `RecordKeyGatewayTests` (14) |

## Sources read

Code (`B/` = `backend/src/main/kotlin/com/systemwebstudio/`): `logic/action/{ActionModel,ActionResult,ActionRuntime,ActionPorts,ActionRunStore,IdempotencyKeys,InputResolver,ActionDefinitionValidator}.kt`, `logic/action/handlers/ActionHandlers.kt`, `logic/action/canonical/CanonicalActionCatalog.kt`, `logic/workflow/{WorkflowModel,WorkflowRun,WorkflowQueue,WorkflowRuntime,WorkflowEngine}.kt`, `logic/workflow/canonical/CanonicalWorkflowCatalog.kt`, `logic/approval/{ApprovalModel,ApprovalService}.kt`, `logic/limits/TenantRateLimiter.kt`, `integration/queue/workflow/{AmqpWorkflowQueue,WorkflowQueueConfiguration,WorkflowQueueSelection}.kt`, `wiring/{AppRuntimeConfiguration,AppRuntimeActionController,RuntimeRequests,RuntimeResponses,VolatileStoreGuards,ActionDataPortAdapter,DataWriteErrors,C1PortAdapters,RuntimeContexts,AuditAdapters}.kt`, `wiring/persistence/{JdbcActionRunStore,JdbcWorkflowRunStore,JdbcApprovalStore}.kt`, `access/Permission.kt`, `access/adapters/AccessPort.kt`; `backend/src/main/resources/{application,application-local,application-prod}.yml`; `db/migration/{V29__workflow_run_persistence,V33__approvals}.sql`; `docs/parallel/c0/undo/U33__approvals.sql`; `scripts/_env.sh`.

Documents: `docs/contracts/v2/{action-workflow,runtime-api,data-runtime}.md` (runtime-api.md as updated by D-C0-61); `docs/parallel/audit/C4-FQ-WF-01-approval.md`; `docs/parallel/audit/{FINAL-C4-runtime-design,PREP-T13-action-runtime-design,C4-V29-readiness-and-queue-contract,C4-FQ-ACT-02-record-key,C4-standalone-readiness}.md`; `docs/parallel/MIGRATION_LEDGER.md`; `docs/parallel/DECISIONS.md` (D-C0-61, D5, V31/V32); `docs/parallel/BOARD.md` (migration rows).



## Open questions / UNVERIFIED

1. **Test results.** Only `@Test` annotation counts are given (section 18); no test was run. Verify with `cd backend && ./gradlew test` (JDK 21, Docker). Older handoff totals were dropped because they predate the current HEAD.
2. **Whole-JVM restart with approvals** and **approval expiry over HTTP** are not covered by the HTTP tests (stated by the handoff). The real-stack QA evidence (`docs/parallel/c6/**`, imported at `28376de`) was not read here.
3. **`JdbcWorkflowRunStore` internals**: `claimForSweep` and `create` were read; the `compareAndSet` SQL and the lease columns inside it are taken from the store's header comments and the V29 DDL, not read line by line.
4. **Unwired features.** Scheduler, notifications, retention and the DLQ consumer are established by grep over `backend/src/main` on `integration/v2` HEAD `28376de` (no construction or call outside `logic/**`). Re-run the grep on `9d2fc8b9758077ac880d80cfbec767e2e776f9c5`.
5. **Effective queue mode of a running stack.** Profile `local` without `PORTALS=1` resolves to `memory`; the V1 scripts force `amqp`; production resolves to `amqp`. Read the startup log of the deployed API (`1006cbf441f6`) to confirm what it actually runs.
6. **D-C0-61 item 2** names the missing-run error `RUN_NOT_FOUND`; the code uses `WORKFLOW_RUN_NOT_FOUND`. The code is documented here; the DECISIONS wording should be corrected by C0.
