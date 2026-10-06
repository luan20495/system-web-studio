# FINAL-C4 — Action / Workflow runtime: thiết kế, hợp đồng, DDL đề xuất

> **C0 note (2026-10-06, integration/v2):** the error-code semantics for `TIMEOUT`/`INTERRUPTED` of a *mutating* action in this document are superseded by **D-C4-17** (they become `IDEMPOTENCY_OUTCOME_UNKNOWN`, `retryable=false`), and UI `onError[]` no longer runs after `IDEMPOTENCY_OUTCOME_UNKNOWN` (**D-C0-13**). Non-mutating actions are unchanged. See `docs/parallel/DECISIONS.md`.

Owner: **C4** · Branch `agent/c4-workflow` · Base `d3c7065d3b6963dc625be5d0725922f5004fb818`
Thay thế các phần đã lỗi thời của `PREP-T13-action-runtime-design.md` (xem §12). ID tài liệu: `B-C4-xx` (BLOCKERS), `D-C4-xx` (DECISIONS).

**Cập nhật 2026-10-06 — V2 contract alignment + hardening (Phase 3).** Căn theo `integration/v2 @ c59604b`, `docs/contracts/v2/**`. Các thay đổi nằm ở: §2 (9 type chuẩn, không alias, idempotency key dẫn xuất, đường dữ liệu, quyết định `trigger`), §4 (sweeper công bằng, cách ly poison/DLQ), §6 (ledger chống bắn trùng), §8 (sửa mô tả TEST workflow), §9/§10 (rate limit theo tenant, retention, DDL gộp), §11–13. **Không có Gradle thật nào được chạy; không có tuyên bố integration PASS.**

Phạm vi đã làm: T13 (ActionRuntime), T14 (Workflow engine), Approval, Scheduler, Notification ports, chế độ TEST. Tất cả nằm trong `com.systemwebstudio.logic.*`, **không có annotation Spring/JPA/Rabbit**, không import code của C1/C2/C3; chỉ nói chuyện với phần còn lại qua port (interface do C4 định nghĩa). Adapter (JDBC, RabbitMQ, Spring, audit) chưa viết — thuộc bước tích hợp của C0 (xem §10).

## 1. Bản đồ package và chiều phụ thuộc

```
logic.action  ◄── logic.notification
     ▲        ◄── logic.approval
     │        ◄── logic.scheduler
     └───────────── logic.workflow  (import approval + scheduler; action KHÔNG import workflow)
```

- `WorkflowStarterPort` (action) do `WorkflowEngine` implement; `ScheduledRunEnqueuer` (scheduler) do `WorkflowEngine` implement; `ApprovalListener` (approval) do `WorkflowEngine` implement. Không có vòng phụ thuộc.
- `*.canonical` (`logic.action.canonical`, `logic.workflow.canonical`) là lớp chống ăn mòn: đọc **JSON AppDefinition canonical của C2** (`AppDefinitionSource.load(tenantId, appId, mode)`), không import class của C2.

## 2. T13 — luồng thực thi một action

`Event → ActionRef → ActionDefinition → tenant/authz → input binding → validate → handler → audit → result`

`DefaultActionRuntime.execute` (thứ tự cố ý: rẻ/ít lộ thông tin trước, side effect sau cùng):

1. Dạng idempotency key **thô của client** (`[A-Za-z0-9._:-]{1,128}`).
2. Tenant gate (`TENANT_DISABLED`; không đọc được trạng thái → `DEPENDENCY_UNAVAILABLE`, fail closed).
3. Cần `ctx.projectId` (app). Definition được tìm **theo (tenant, app)**; tenant/app khác, không tồn tại, `enabled=false` đều → `UNKNOWN_ACTION`.
4. Quy tắc `trigger` (xem "Quyết định `trigger`" bên dưới): run UI cấp cao nhất (`TriggerKind.UI_EVENT`, `callDepth==0`) chỉ được chạy action **có** `trigger` và tên event khớp; workflow step / schedule / chuỗi con thì không cần.
5. Quyền (mặc định từ chối, audit `DENIED`): `APP_USE`, `ACTION_EXECUTE`, `DATA_MUTATE` cho các type ghi dữ liệu (`SUBMIT_FORM`, `CREATE/UPDATE/DELETE_RECORD`, `CALL_API`), `requiredPermission` khai báo trong action, và `WORKFLOW_EXECUTE` cho `START_WORKFLOW`. Lỗi hệ thống quyền → fail closed. UI không được tin: quyền luôn lấy từ `ActionContext` do server dựng.
6. **Rate limit theo tenant** (`RateScope.ACTION_EXECUTE`) — *sau* khi qua quyền (người không có quyền không tiêu hao ngân sách của tenant), trước khi chạy handler. Vượt → `RATE_LIMITED` (`retryable`, `details.retryAfterMillis`), không kích hoạt `onError`.
7. Handler theo type (`UNSUPPORTED_ACTION_TYPE`).
8. Call depth + `ActionDefinitionValidator` + `handler.validate`.
9. `InputResolver` (nguồn: component state, route params, form, viewModel, previous result, literal, context do server cấp) rồi `ActionInputBinder` (chỉ tên đã khai báo, đúng kiểu, trong giới hạn).
10. **TEST**: `handler.preview` → `WouldRun`; không ghi `action_runs`, không cần key (nếu không có key thì dùng key dry-run `test:<runId>` đã dẫn xuất), audit `PREVIEWED`.
11. **LIVE**: key dẫn xuất (`IdempotencyKeys.derive`) → `ActionRunStore.begin` (CAS) → chạy / replay / `ACTION_IN_PROGRESS` / `IDEMPOTENCY_KEY_REUSED`.
12. Audit `STARTED` — fail closed (`AUDIT_UNAVAILABLE`, retryable).
13. Handler chạy trên executor với timeout.
14. `complete` + audit kết thúc (best effort — action đã xảy ra).

`run()` thực hiện thêm chuỗi `onSuccess`/`onError` (key dẫn xuất `parentKey:s:id` / `:e:id`, giới hạn `callDepth` và `maxChainActions`; lỗi ở cổng — quyền/tenant/… — không kích hoạt `onError`). `dispatch(ctx, event)` lấy `ActionRef` khớp `(sectionId, eventType)`, chạy từng ref với key `evt:<eventId đã làm sạch>:<refId>`; payload của client chỉ vào được input **đã khai báo** và **đã có `inputMapping`**.

### Loại action (đóng kín — D-C4-01, sửa 2026-10-06)

Đúng **9 tên chuẩn** của contract v2: `NAVIGATE, REFRESH_QUERY, SUBMIT_FORM, CREATE_RECORD, UPDATE_RECORD, DELETE_RECORD, CALL_API, NOTIFY, START_WORKFLOW`. **Không còn bản đồ alias**: `CanonicalActionReader.actionType(wire)` so khớp chính xác; `RUN_QUERY`, `WRITE_DATA`, `CALL_CONNECTOR_OPERATION`, `SET_VALUE` và mọi tên khác bị báo "is not one of …" và action đó không được cung cấp (fail closed). Giữ `REFRESH_QUERY` (không đọc dữ liệu ở server; trả chỉ thị cho client).

| Type | `config` (chỉ id tham chiếu) | Gọi | Ghi chú |
|---|---|---|---|
| `NAVIGATE` | `pageId` | — | trả chỉ thị cho client |
| `REFRESH_QUERY` | `queryRef` (READ) | — | chỉ thị client chạy lại query; việc đọc vẫn đi qua DataGateway của C3 |
| `SUBMIT_FORM` / `CREATE_RECORD` / `UPDATE_RECORD` / `DELETE_RECORD` | `queryRef` (WRITE) | `ActionDataPort.write` | UPDATE/DELETE bắt buộc input `recordId`; idempotency mặc định `REQUIRED`, `NONE` bị validator từ chối |
| `CALL_API` | `dataSourceRef` + `operationKey` | `ActionDataPort.callOperation` | **không** URL/header/credential |
| `NOTIFY` | `channel`, `templateRef`, `endpointRef?`, `recipients` | `ActionNotifyPort` | người nhận nằm trong **definition**, không nhận từ input |
| `START_WORKFLOW` | `workflowRef` | `WorkflowStarterPort` | idempotency bắt buộc `REQUIRED` |

`DOWNLOAD_FILE`, `OPEN_URL` **không** làm: chưa có security model (allowlist domain, chữ ký URL, kiểm quyền file) nên theo yêu cầu "chỉ nếu security model rõ" — ghi vào backlog.

`config` bị validator từ chối khi chứa key kiểu `sql`, `script`, `code`, `url`, `headers`, `token`, `password`… ở mọi độ sâu; mọi tham chiếu phải khớp `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$` (không `:` `/`, nên không thể là URL).

### Idempotency và `action_runs`

Key **thô** của client (`ActionRequest.idempotencyKey`) **không bao giờ** đi xuống C3/notification/workflow và không được lưu hay ghi log. Runtime dẫn xuất:

`derivedKey = base64url(sha256("<tenantId>|<appId>|<userId>|<actionId>|<clientKey>"))` — 43 ký tự, không padding (`IdempotencyKeys.derive`).

- *Ổn định*: cùng đầu vào → cùng key, nên **retry dùng lại đúng key đã dẫn xuất** (và `RunKey` của `ActionRunStore` chỉ chứa key dẫn xuất).
- *Phạm vi*: `(tenant, app, user, action, clientKey)` — user B không phát lại/chặn được key của user A; hai app/tenant không đụng nhau dù trùng key thô.
- *Không có key thô* (action `OPTIONAL`/dispatch): `forFreshRun` tạo key một-lần-theo-run, không dùng để khử trùng. Dry-run TEST: `derive(…, key ?: "test:<runId>")`.
- `WriteRequest.idempotencyKey`/`OperationRequest.idempotencyKey` có `init { require(DERIVED_KEY) }` (và `toString` che key, chỉ in tên param) — một key thô lọt vào port sẽ ném lỗi ngay.
- `IdempotencyKeys.redact(key)` cho log/audit (chỉ vài ký tự đầu).

Phạm vi lưu: `(tenant, app, action, user, derivedKey)`. Cùng key + cùng fingerprint input: SUCCEEDED/FAILED không retry → replay kết quả; FAILED `retryable` → chạy lại (attempt + 1); cùng key khác input → `IDEMPOTENCY_KEY_REUSED`. Nhờ vậy retry không bao giờ tạo bản ghi nghiệp vụ thứ hai (với điều kiện adapter C3 chuyển tiếp key dẫn xuất xuống connector — B-C4-04). Xoá một dòng `action_runs` (retention) chấm dứt bảo đảm replay của key đó, nên horizon tối thiểu 7 ngày (§10).

### Đường dữ liệu (một cửa duy nhất)

`ActionRuntime → ActionDataPort → adapter của C0 (wiring) + AppDataBindingResolver → C3 DataGateway`. `logic.*` không import repository/connector/JDBC/HTTP client (có test kiến trúc quét import của `logic/**`), và chỉ package `action.handlers` được nói chuyện với `ActionDataPort`. Port (chữ ký chính xác C0/C3 cần implement):

```kotlin
interface ActionDataPort {
  fun write(ctx: ActionContext, request: WriteRequest): PortOutcome
  fun callOperation(ctx: ActionContext, request: OperationRequest): PortOutcome
  fun dryRunWrite(ctx: ActionContext, request: WriteRequest): DryRunOutcome        // mặc định NotSupported
  fun dryRunOperation(ctx: ActionContext, request: OperationRequest): DryRunOutcome // mặc định NotSupported
}
data class WriteRequest(appId: UUID, mode: ExecutionMode, queryRef: String, kind: WriteKind, params: Map<String, JsonNode>, idempotencyKey: String /* DERIVED, 43 ký tự */)
data class OperationRequest(appId: UUID, mode: ExecutionMode, dataSourceRef: String, operationKey: String, params: Map<String, JsonNode>, idempotencyKey: String)
```

`ctx` mang `tenantId` + actor (server dựng). Adapter chịu trách nhiệm: tra `queryRef`/`dataSourceRef` trong AppDefinition của `(tenantId, appId, mode)`, kiểm tenant/quyền/data scope/SSRF/limits trong DataGateway, chuyển `idempotencyKey` xuống connector. Mutation handler lấy `appId`/`mode`/key từ `ActionRun` (`run.appId`, `run.mode`, `run.idempotencyKey`) và trả `IDEMPOTENCY_KEY_REQUIRED` nếu thiếu.

### Quyết định `trigger` (D-C4-10)

`ActionDefinition.trigger: ActionTrigger?(sectionId, EventType)` là **tùy chọn trên định nghĩa**; **bắt buộc chỉ với action gắn UI**: run `TriggerKind.UI_EVENT` ở `callDepth == 0` chỉ chạy được action có `trigger`, và tên event của request phải khớp event đã khai báo (sai → `UNKNOWN_ACTION`, không lộ gì thêm). Workflow step (`WORKFLOW_STEP`), schedule (`SCHEDULE`) và action con trong chuỗi `onSuccess`/`onError` (`callDepth ≥ 1`) **không cần** `trigger`. `trigger` có mặt nhưng sai dạng (section/event không hợp lệ) → action không dùng được (fail closed); các ref event chỉ được đăng ký khi `trigger` có mặt. **Kết luận: runtime C4 đồng ý và đã cài đặt như vậy; C4 không sửa C2 hay `docs/contracts/**`.** Lưu ý cho C0: `docs/contracts/v2/action-workflow.md` §2 viết `trigger{sectionId, event}` không có `?`; để khớp với `ActionDef.trigger` tùy chọn của C2 và với action chỉ-dùng-trong-workflow, C0 cần ghi chú `trigger?` (tùy chọn; bắt buộc chỉ khi action được gắn vào một section UI) trong contract đó qua DECISIONS — C4 chưa chạm vào file contract.

## 3. Mô hình lỗi

`ActionResult.Failed(code, retryable, message?, details)`; `message` an toàn để hiển thị. Mã chính: `UNKNOWN_ACTION`, `FORBIDDEN`, `TENANT_DISABLED`, `INVALID_INPUT`, `INVALID_DEFINITION`, `LIMIT_EXCEEDED`, `IDEMPOTENCY_KEY_REQUIRED/INVALID/REUSED`, `ACTION_IN_PROGRESS` (retryable), `NOT_IMPLEMENTED`, `TIMEOUT`/`INTERRUPTED` (retryable chỉ khi an toàn: không đổi state hoặc có key), `DEPENDENCY_UNAVAILABLE`/`AUDIT_UNAVAILABLE` (retryable), `RATE_LIMITED` (retryable, kèm `retryAfterMillis`), `HANDLER_ERROR` (không lộ message). Mã của downstream đi qua nguyên vẹn. Workflow thêm: `UNKNOWN_WORKFLOW`, `WORKFLOW_RUN_NOT_FOUND`, `DEAD_LETTERED`, `APPROVAL_REJECTED`, `APPROVAL_EXPIRED`, `CONFLICT`.

## 4. T14 — Workflow engine

### Định nghĩa (khai báo, không script)

`WorkflowDefinition(id, trigger MANUAL|SCHEDULE|ACTION, steps, startStepId?, limits, compensateOnCancel)`. Step: `ACTION` (actionRef + `inputs` kiểu `ValueRef`: Input / Step output / Literal), `WAIT`, `APPROVAL`, `BRANCH` (điều kiện có kiểu: Compare/Exists/AllOf/AnyOf/Not — **không có ngôn ngữ biểu thức**), `END`. Mỗi step có `next`, `onError`, `retry` (maxAttempts, backoff mũ, trần), `timeout`, `compensationActionRef` tùy chọn. `WorkflowDefinitionValidator` kiểm: id, tham chiếu, step không với tới được, giới hạn so với trần nền tảng, độ sâu/số node điều kiện.

### Trạng thái chạy

`WorkflowRun` (bảng `workflow_runs`): định nghĩa **workflow** được **chụp snapshot** lúc start (sửa/republish không ảnh hưởng run đang chạy; định nghĩa *action* được tra lại ở từng step, không snapshot), `status PENDING|RUNNING|WAITING|SUCCEEDED|FAILED|CANCELLED`, `steps` (trạng thái từng step: attempt, input/output, lỗi, thời điểm, `wakeAt`, `approvalId`, `simulated`, `visit`), `compensable`, `createdBy`, `version`, và các cột vận hành `processFailures`, `sweepFailures`, `notBefore`, `lastSweptAt`, `redactedAt`. **Mọi chuyển trạng thái là CAS trên dòng run** (`mutate`/`Transition`), nên message trùng/mất/đảo thứ tự không làm hỏng run.

### Hàng đợi và worker

- Message `v1|jobId|tenantId|runId|stepId` — **không mang actor, quyền hay payload**; worker nạp lại run (và actor, định nghĩa đã snapshot) từ store. Message giả/phát lại không thể mở rộng quyền của run.
- Worker: `claim` step (CAS PENDING/RETRY_WAIT→RUNNING) → thực thi **ngoài lock** → ghi kết quả bằng CAS (nếu run đã bị huỷ/timeout/sweeper reset thì kết quả muộn bị bỏ) → publish step kế tiếp.
- `start` chỉ lưu run và publish job đầu rồi trả về — **không chặn HTTP**.
- Retry/backoff: lỗi `retryable` còn lượt → `RETRY_WAIT` với `wakeAt`; **sweeper** publish khi tới hạn. Không cần delayed-delivery của broker.
- Crash: step kẹt `RUNNING` quá `staleAfter` → sweeper đưa về `RETRY_WAIT` và publish lại; vì action dùng key `wf:<runId>:<stepId>` nên việc làm lại một step đã xong là *replay*, không phải tác dụng thứ hai. Vòng lặp quay lại cùng step dùng key `…:v<visit>` nên lượt thứ hai chạy thật.
- **Ack-and-sweeper, không bao giờ requeue ngay** (D-C4-13): message đã xử lý, hoặc không xử lý được do *sự cố hạ tầng* (store/tenant gate down, tranh chấp) → `ack`; trạng thái đã lưu, sweeper publish lại sau backoff. Message sai định dạng → `nack(requeue=false)` thẳng vào DLQ (không gắn với run nào). Message mà việc xử lý **ném exception** được tính là **một lần lỗi của chính run đó** (`WorkflowEngine.recordProcessFailure`): đếm `processFailures`, đặt `notBefore` (backoff mũ, sàn 1s, trần 10 phút), sweeper publish lại sau đó; sau `maxProcessFailures` (mặc định 5, tường minh) run `FAILED/DEAD_LETTERED` và **chỉ message đó** bị `nack(requeue=false)` vào DLQ. Poison của một run (hay một sự cố hạ tầng) không bao giờ đẩy run khỏe mạnh khác vào DLQ. Dead letter do broker gửi tới (`failFromDeadLetter`) chỉ tính **một** lần lỗi cho run của nó, không giết run ngay. Run tiến triển thì các bộ đếm/`notBefore` được xoá.
- **Sweeper công bằng và có chặn** (`WorkflowRunStore.claimForSweep`): một lần claim hợp nhất (timer đến hạn / chờ approval / stale) theo thứ tự **cũ nhất đủ điều kiện trước** (con trỏ xoay `lastSweptAt`, chưa từng xét đứng đầu), **round-robin theo tenant** (`FairSelection`), batch có giới hạn, mỗi run chỉ được xét tối đa một lần mỗi `sweepMinInterval` (5s; run chỉ chờ approval: 60s), và dấu `lastSweptAt` được ghi **nguyên tử** cùng lúc claim nên node sweeper thứ hai không nhận lại cùng run. Mọi run đủ điều kiện được xét trong `ceil(N/limit)` lượt dù các run khác ra sao. Run mà sweeper không di chuyển được (publish lỗi, tranh chấp, exception) bị **backoff** (mũ, sàn 1s) và không quay lại đầu hàng — nó không làm chậm run khỏe mạnh phía sau. `ApprovalService.expireDue` cũng công bằng theo tenant và cô lập lỗi từng mục.
- Resume: toàn bộ trạng thái nằm trong store; khởi động lại node không mất gì. Sweeper cũng xử lý: job mất trước khi publish, run quá `maxDuration` (`TIMEOUT`), compensation kẹt, phê duyệt đã xong mà callback mất.

### Quyền khi chạy

Run chạy **như người tạo** (`createdBy`); mỗi ACTION step đi qua `ActionRuntime` nên tenant gate và quyền của user đó được kiểm lại **ở thời điểm chạy step**, không nhớ từ lúc start. Tenant bị disable giữa chừng → run `FAILED/TENANT_DISABLED`.

### Compensation, cancel, giới hạn

Step ACTION thành công có `compensationActionRef` được ghi vào `compensable`; khi run FAILED (hoặc CANCELLED với `compensateOnCancel`) job `~compensate` chạy bù theo **thứ tự ngược**; một bước bù lỗi → `PARTIAL` (các bước còn lại vẫn chạy). Cancel idempotent, huỷ cả approval đang chờ. Giới hạn: `maxSteps`, `maxStepExecutions` (chặn vòng lặp), `maxRetries`, `maxDuration`, `maxWait`, `maxPayloadBytes`, `maxDepth` (workflow→action→workflow), `maxStepTimeout`; định nghĩa chỉ được **hạ** trần nền tảng.

## 5. Approval

`ApprovalService`: `request / decide / comment / cancel / get / inbox / expireDue`. Approver là `PrincipalSpec` USER | GROUP | DEPARTMENT_MANAGER | ROLE, được **resolve và chụp snapshot** lúc tạo (đổi thành viên nhóm sau đó không ảnh hưởng yêu cầu đang chờ); người yêu cầu bị loại khỏi approver trừ khi `allowSelfApproval`. Quorum `requiredApprovals`; **một từ chối là từ chối cả yêu cầu**; quyết định lặp lại cùng ý là idempotent, đổi ý là `CONFLICT`; hết hạn lazily khi có quyết định muộn và bằng `expireDue`. Cross-tenant: bị từ chối (trả "không tìm thấy") trừ khi `CrossTenantApprovalPolicy` — do C1 cấp — cho phép, và vẫn phải là approver. Audit mọi bước; thông báo in-app (best effort); listener báo workflow, engine còn reconcile bằng sweeper.

## 6. Scheduler

`SchedulerService`: `create / setEnabled / delete / list / syncDeclared / executions / tick`. Cron 5 trường tự cài (không thư viện), timezone, DST: giờ không tồn tại (spring forward) kích hoạt đúng một lần sau khoảng trống, giờ lặp (fall back) kích hoạt một lần ở lần đầu (không bao giờ bắn hai lần cùng một thời điểm hay cùng key; có test). **Scheduler chỉ enqueue**: không gọi action/connector/notification.

**Chống bắn trùng (D-C4-15)** — ba lớp độc lập, để một thời điểm bắn tới workflow runtime tối đa một lần dù khởi động lại, nhiều worker, lệch/giật lùi đồng hồ hay hàng schedule bị khôi phục về trạng thái cũ:

1. CAS trên dòng schedule: chỉ một node claim được fire đến hạn.
2. **Sổ thực thi `ScheduleExecution`** với định danh duy nhất `(tenantId, scheduleId, fireAt)` (SQL: unique key / `INSERT … ON CONFLICT DO NOTHING`), tạo nguyên tử **trước** khi enqueue. Fire đã có dòng ở trạng thái cuối (`ENQUEUED`/`SKIPPED`/`FAILED`) thì không giao lần nữa (đếm `deduplicated`); dòng `CLAIMED` (crash giữa chừng) thì hoàn tất bằng enqueue idempotent. Sổ không dùng được → không giao (retry sau, có backoff).
3. Key `sched:<scheduleId>:<fireEpochMilli>` trên chính lời gọi enqueue (workflow runtime trả về cùng một run).

Thêm **guard đơn điệu**: fire time không sau `lastRunAt` bị bỏ (`SKIPPED_DUPLICATE`), và `nextRunAt` không bao giờ tính từ mốc sớm hơn lần bắn cuối (đồng hồ giật lùi, bật lại schedule). Misfire: `SKIP` hoặc `FIRE_ONCE` (tối đa một lần bù). Tenant disabled → bỏ qua (cũng ghi sổ). Rate limit `SCHEDULER_ENQUEUE` theo tenant (vượt → schedule giữ nguyên đến hạn, không mất gì); chọn schedule công bằng theo tenant.

**Pending outbox có backoff**: `pendingFireAt` + `pendingAttempts` + `pendingNotBefore`; lỗi enqueue tạm thời → backoff mũ (30s, 60s, … trần 15 phút), sau `maxEnqueueAttempts` (8) thì bỏ fire đó (ledger `FAILED`, `ENQUEUE_EXHAUSTED`) còn schedule vẫn chạy fire kế tiếp. Lỗi vĩnh viễn (vd. owner mất quyền) bỏ pending ngay. Schedule target là workflow hoặc một action (chạy như run 1 bước).

**Đăng ký schedule khai báo idempotent** (`syncDeclared`): định danh `(tenant, app, "workflow:<id>")` (`declaredKey`, unique); publish lại cùng khai báo không tạo schedule thứ hai và không đụng `nextRunAt`; đổi cron/timezone cập nhật chính dòng đó; khai báo bị gỡ → schedule bị *disable* (giữ lịch sử, không xoá). `DeclaredScheduleSpec` thuộc scheduler (không phụ thuộc `logic.workflow`); wiring ánh xạ `workflow.canonical.DeclaredSchedule(workflowId, cron, timezone)` 1–1.

## 7. Notification ports

`NotificationService : ActionNotifyPort` + `ChannelSender` theo kênh (`IN_APP`, `EMAIL`, `WEBHOOK`, `SMS`) — **không hard-code vendor**; vendor là một `ChannelSender` do bên tích hợp viết. Nội dung chỉ là **template đã duyệt** (`NotificationCatalog.template`) + tham số có kiểu; webhook chỉ tới `endpointRef` đã đăng ký (URL/credential chỉ sender biết). Người nhận resolve qua `PrincipalResolver` (tối đa 100); `DeliveryStore` khử trùng theo (key, người nhận): retry chỉ gửi lại người thất bại. Claim bị bỏ rơi do crash được lấy lại sau lease (5 phút) — nên giao hàng là *at-least-once* trừ khi provider khử trùng theo `deliveryKey`. Kênh chưa có sender → `NOT_IMPLEMENTED` (không giả vờ).

## 8. TEST mode (`ExecutionMode.TEST`)

- Action: `handler.preview` → `ActionResult.WouldRun(actionId, type, level, plan, output?, reason)`. **Không** commit mutation, **không** gửi thông báo thật, **không** start workflow, không tạo `action_runs`/idempotency. `plan` chỉ có *tên* input, không có giá trị.
- `level`: `NOT_EXECUTED` (mặc định trung thực: downstream không có dry-run), `VALIDATED` (downstream kiểm tra không side effect), `SANDBOX` (downstream chạy trong sandbox riêng). Chỉ khi `ActionDataPort.dryRunWrite/dryRunOperation` trả `Validated/Sandboxed` mới lên cấp cao hơn — "không giả vờ dry-run nếu connector không hỗ trợ". Port chưa nối → `NOT_IMPLEMENTED` kể cả ở TEST.
- Workflow TEST: **vẫn tạo một dòng `workflow_runs` (`mode=TEST`)** để theo dõi tiến trình và có snapshot định nghĩa nháp, nhưng không ghi `action_runs`, không tạo approval/notification thật, không mutation, không publish, không compensation. Chạy các step ở TEST mode, bỏ qua WAIT, mô phỏng APPROVAL; step đánh dấu `simulated` + `dryRunLevel`. Connector không có dry-run → `NOT_EXECUTED` (báo thẳng "không hỗ trợ", **không bao giờ giả vờ thành công**). TEST và LIVE là hai phạm vi idempotency riêng. Định nghĩa TEST đọc **bản nháp**, LIVE đọc bản đã publish (`AppDefinitionSource.load(…, mode)`). Quyền cho TEST (xem bản nháp) do C1 quyết qua `AccessRequest.mode` (B-C4-01).

## 9. Bảo mật — đối chiếu yêu cầu

| Yêu cầu | Cách thực hiện |
|---|---|
| Không script/URL/SQL tùy ý | validator từ chối key cấm ở mọi độ sâu; tham chiếu chỉ là id thuần; `CALL_API` = `dataSourceRef`+`operationKey`; không có ngôn ngữ biểu thức |
| Không bypass DataGateway | handler chỉ có `ActionDataPort` (một cửa duy nhất, có test kiến trúc quét import); adapter C3 đi qua DataGateway (B-C4-04) |
| Idempotency key thô không rò rỉ | chỉ key dẫn xuất sha256 được lưu, ghi log/audit, gửi cho C3; `WriteRequest/OperationRequest` từ chối key sai dạng |
| Fail closed khi thiếu quyền / ngữ cảnh | thiếu `appId`, quyền, tenant, key → từ chối trước handler; test riêng cho từng cổng |
| Không credential | không nơi nào trong model có credential; webhook = `endpointRef` |
| Không chạy khi thiếu quyền | `AccessPort` trước handler, mỗi execute và mỗi workflow step; mặc định từ chối, fail closed |
| Tenant isolation | mọi lookup mang `tenantId`; job không mang quyền; run/approval/schedule truy cập chéo tenant trả "không tìm thấy" |
| Giới hạn | `ActionLimits`, `WorkflowLimits`, số người nhận, payload, depth, retries, timeout; **rate limit theo tenant** cho action execute / workflow start / scheduler enqueue (`TenantRateLimiter`, token bucket trong tiến trình; limiter dùng chung toàn cụm là adapter của C0 — B-C4-08) |
| Audit | `ActionAuditPort` (fail closed trước khi chạy), `LogicAuditPort` cho workflow/approval/schedule/notification; không ghi giá trị input/output |

## 10. Việc tích hợp còn lại (không thuộc ownership của C4)

Xem BLOCKERS B-C4-01…09. Tóm tắt: C1 cấp permission + `TenantContext` + resolver nhóm/role/manager; C2 chốt trường bổ sung của `actions[]`/`workflows[]`; C3 cấp cổng ghi/`callOperation`/dry-run; C0 cấp migration, adapter JDBC, RabbitMQ (queue + DLQ), wiring Spring, timer cho `sweep()`/`tick()`/`expireDue()`, adapter audit.

### RabbitMQ — ánh xạ `WorkflowQueue`

**Thay đổi ngữ nghĩa so với bản trước (D-C4-13):** worker không bao giờ `nack(requeue=true)`; ack khi đã xử lý hoặc gặp sự cố hạ tầng (sweeper publish lại sau backoff), `nack(requeue=false)` chỉ cho message sai định dạng và message cuối của run đã hết ngân sách lỗi. `delivery-limit` của broker chỉ còn là lưới an toàn (consumer chết hàng loạt), và dead letter do broker tạo ra chỉ tính một lần lỗi cho run (`drainDeadLetters`).

Queue `xweb.workflow.jobs` kiểu **quorum** (có `x-delivery-count` và `delivery-limit` = `maxDeliveries`, quá giới hạn tự dead-letter), DLX → `xweb.workflow.jobs.dlq`. `publish` = persistent + publisher confirm (lỗi → run vẫn `PENDING`, sweeper publish lại); `poll` = consumer với ack thủ công, prefetch nhỏ; `nack(requeue=true)` = giao lại; `nack(requeue=false)` hoặc quá `delivery-limit` = DLQ. Consumer của DLQ gọi `WorkflowWorker.drainDeadLetters`.

### Retention (D-C4-16)

`RetentionService.runOnce()` (C0 gọi từ timer, ví dụ mỗi giờ) xoá **chỉ hàng đã kết thúc**, theo lô có giới hạn (`batchSize` ≤ 5000, `maxBatchesPerTarget` lô mỗi lần chạy), cũ nhất trước; backlog được xử lý dần qua nhiều lần chạy. Quy tắc an toàn nằm ở **hợp đồng của từng store** chứ không ở số liệu cấu hình:

| Bảng | Giai đoạn 1 (làm trống payload) | Giai đoạn 2 (xoá hàng) | Không bao giờ đụng tới |
|---|---|---|---|
| `action_runs` | — (replay cần kết quả) | `finished_at < now − actionRuns` (mặc định 30 ngày) | `RUNNING` (sweeper đổi hàng bỏ rơi thành `FAILED` trước, rồi nó mới bắt đầu già đi) |
| `workflow_runs` (+ `workflow_run_steps`) | `input`, `input/output` của step → placeholder, `redacted_at` (mặc định sau 14 ngày) | sau 90 ngày | `PENDING/RUNNING/WAITING`, `compensation = IN_PROGRESS`, run chưa kết thúc dù cũ tới đâu |
| `approvals` | — | trạng thái cuối, `finished_at < now − 180 ngày` | `PENDING`; approval cuối của workflow run còn active |
| `schedule_executions` | — | trạng thái cuối, `updated_at < now − 30 ngày` | `CLAIMED` (fire chưa xác nhận) |

`RetentionPolicy` từ chối horizon < 7 ngày (xoá hàng `action_runs`/`workflow_runs` chấm dứt khử trùng/replay của key, nên horizon phải dài hơn mọi cửa sổ retry của client). Retention là việc dọn dẹp tenant-agnostic theo tuổi; nếu cần horizon theo tenant thì thêm `tenant_id` vào hợp đồng store sau (chưa yêu cầu). Test đặt các hàng active/in-flight *cổ* cạnh hàng đã kết thúc cổ và kiểm chứng chỉ hàng đã kết thúc bị xoá.

### Yêu cầu migration gộp (chưa tạo file; **không cấp số Flyway** — chỉ C0 cấp, xem BOARD.md)

DDL đề xuất dưới đây thay thế bản trước (thêm: key dẫn xuất, `workflow_run_steps`, cột vận hành sweeper/DLQ/retention, `schedule_executions`, FK `tenants(id)`, chỉ mục retention). Mọi bảng có `tenant_id uuid NOT NULL REFERENCES tenants(id)`; mọi truy vấn của adapter phải lọc theo `tenant_id`. `app_id` không có FK vì bảng app thuộc C2 (C0 quyết định nếu muốn).

```sql
-- T13: run state + idempotency. idempotency_key là key DẪN XUẤT (base64url sha256, 43 ký tự); key thô của client không bao giờ được lưu.
CREATE TABLE action_runs (
  run_id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenants(id), app_id uuid NOT NULL, action_id text NOT NULL, user_id uuid NOT NULL,
  idempotency_key varchar(43) NOT NULL, fingerprint text NOT NULL, status text NOT NULL, attempt int NOT NULL,
  result jsonb, started_at timestamptz NOT NULL, finished_at timestamptz, updated_at timestamptz NOT NULL,
  UNIQUE (tenant_id, app_id, action_id, user_id, idempotency_key));
CREATE INDEX action_runs_stale ON action_runs (updated_at) WHERE status = 'RUNNING';
CREATE INDEX action_runs_retention ON action_runs (finished_at) WHERE status <> 'RUNNING';

-- T14: workflow run. Dòng run giữ định nghĩa snapshot + trạng thái; CAS trên cột version.
CREATE TABLE workflow_runs (
  run_id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenants(id), app_id uuid NOT NULL, workflow_id text NOT NULL, mode text NOT NULL,
  status text NOT NULL, created_by uuid NOT NULL, actor_kind text NOT NULL, workspace_id uuid,
  idempotency_key text NOT NULL, fingerprint text NOT NULL, input jsonb NOT NULL, definition jsonb NOT NULL,
  current_step_id text, compensable jsonb NOT NULL DEFAULT '[]', step_executions int NOT NULL, depth int NOT NULL,
  error_code text, error_message text, compensation text NOT NULL DEFAULT 'NONE',
  cur_step_status text, cur_wake_at timestamptz, cur_approval_id uuid,           -- phi chuẩn hóa từ bước hiện tại, cho sweeper
  process_failures int NOT NULL DEFAULT 0, sweep_failures int NOT NULL DEFAULT 0,  -- cách ly poison / backoff sweeper
  not_before timestamptz, last_swept_at timestamptz,                               -- backoff + con trỏ xoay công bằng
  redacted_at timestamptz,                                                         -- retention giai đoạn 1
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, finished_at timestamptz, version bigint NOT NULL,
  UNIQUE (tenant_id, app_id, workflow_id, created_by, mode, idempotency_key));
CREATE INDEX workflow_runs_timers   ON workflow_runs (cur_wake_at) WHERE cur_step_status IN ('WAITING','RETRY_WAIT') AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED');
CREATE INDEX workflow_runs_approval ON workflow_runs (cur_approval_id) WHERE cur_approval_id IS NOT NULL;
-- claim công bằng của sweeper: ORDER BY last_swept_at NULLS FIRST, updated_at … FOR UPDATE SKIP LOCKED, row_number() OVER (PARTITION BY tenant_id) <= :perTenant
CREATE INDEX workflow_runs_sweep    ON workflow_runs (last_swept_at NULLS FIRST, updated_at) WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED') OR compensation = 'IN_PROGRESS';
CREATE INDEX workflow_runs_retention ON workflow_runs (finished_at) WHERE status IN ('SUCCEEDED','FAILED','CANCELLED') AND compensation <> 'IN_PROGRESS';
CREATE INDEX workflow_runs_redact    ON workflow_runs (finished_at) WHERE redacted_at IS NULL AND status IN ('SUCCEEDED','FAILED','CANCELLED') AND compensation <> 'IN_PROGRESS';

-- Trạng thái từng step (chuẩn hóa; ghi trong CÙNG transaction với CAS của dòng run: adapter so sánh steps cũ/mới và UPSERT phần thay đổi).
CREATE TABLE workflow_run_steps (
  run_id uuid NOT NULL REFERENCES workflow_runs(run_id) ON DELETE CASCADE, tenant_id uuid NOT NULL REFERENCES tenants(id),
  step_id text NOT NULL, status text NOT NULL, attempt int NOT NULL, visit int NOT NULL DEFAULT 1,
  input jsonb, output jsonb, error_code text, error_message text,
  started_at timestamptz, finished_at timestamptz, wake_at timestamptz, approval_id uuid,
  simulated boolean NOT NULL DEFAULT false, dry_run_level text, compensated boolean NOT NULL DEFAULT false,
  PRIMARY KEY (run_id, step_id));
CREATE INDEX workflow_run_steps_tenant ON workflow_run_steps (tenant_id, run_id);

-- Approval. decisions/comments là jsonb trong dòng approval để quyết định + trạng thái đổi nguyên tử (CAS version).
CREATE TABLE approvals (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenants(id), app_id uuid, title text NOT NULL, requested_by uuid NOT NULL,
  requested_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, approver_specs jsonb NOT NULL, approvers uuid[] NOT NULL,
  required_approvals int NOT NULL, allow_self_approval boolean NOT NULL, status text NOT NULL,
  decisions jsonb NOT NULL DEFAULT '[]', comments jsonb NOT NULL DEFAULT '[]', source_run_id uuid, source_step_id text,
  idempotency_key text, finished_at timestamptz, version bigint NOT NULL);
CREATE UNIQUE INDEX approvals_idem ON approvals (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX approvals_inbox     ON approvals USING gin (approvers) WHERE status = 'PENDING';
CREATE INDEX approvals_expiry    ON approvals (expires_at) WHERE status = 'PENDING';
CREATE INDEX approvals_retention ON approvals (finished_at) WHERE status <> 'PENDING';

-- Schedule. declared_key: định danh ổn định của schedule do AppDefinition khai báo ("workflow:<id>"); unique theo (tenant, app).
CREATE TABLE schedules (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenants(id), app_id uuid NOT NULL, name text NOT NULL, cron text NOT NULL, timezone text NOT NULL,
  enabled boolean NOT NULL, target_kind text NOT NULL, target_ref text NOT NULL, input jsonb, created_by uuid NOT NULL,
  misfire_policy text NOT NULL, next_run_at timestamptz, last_run_at timestamptz, last_run_status text,
  pending_fire_at timestamptz, pending_attempts int NOT NULL DEFAULT 0, pending_not_before timestamptz, declared_key text,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version bigint NOT NULL);
CREATE INDEX schedules_due     ON schedules (next_run_at) WHERE enabled AND pending_fire_at IS NULL;
CREATE INDEX schedules_pending ON schedules (pending_not_before) WHERE pending_fire_at IS NOT NULL;
CREATE UNIQUE INDEX schedules_declared ON schedules (tenant_id, app_id, declared_key) WHERE declared_key IS NOT NULL;

-- Sổ thực thi: định danh duy nhất của MỘT lần bắn. claimExecution = INSERT … ON CONFLICT DO NOTHING rồi đọc dòng thắng; không FK tới schedules (giữ lịch sử khi schedule bị xoá).
CREATE TABLE schedule_executions (
  tenant_id uuid NOT NULL REFERENCES tenants(id), schedule_id uuid NOT NULL, app_id uuid NOT NULL, fire_at timestamptz NOT NULL,
  dedupe_key text NOT NULL, status text NOT NULL, attempts int NOT NULL DEFAULT 0, run_ref text, error_code text,
  claimed_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, schedule_id, fire_at), UNIQUE (tenant_id, dedupe_key));
CREATE INDEX schedule_executions_retention ON schedule_executions (updated_at) WHERE status <> 'CLAIMED';

CREATE TABLE notification_deliveries (
  tenant_id uuid NOT NULL REFERENCES tenants(id), delivery_key text NOT NULL, status text NOT NULL, updated_at timestamptz NOT NULL, PRIMARY KEY (tenant_id, delivery_key));
CREATE TABLE in_app_notifications (   -- adapter của kênh IN_APP
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenants(id), user_id uuid NOT NULL, template_ref text NOT NULL, params jsonb NOT NULL, created_at timestamptz NOT NULL, read_at timestamptz);
CREATE INDEX in_app_notifications_user ON in_app_notifications (tenant_id, user_id, created_at DESC);
```

`compareAndSet(expected, next)` của `workflow_runs` = một transaction: `UPDATE workflow_runs SET …, version = version + 1 WHERE tenant_id = ? AND run_id = ? AND version = ?` rồi UPSERT các `workflow_run_steps` thay đổi; `0 hàng` = CAS thua. `claimForSweep` = một câu `UPDATE … WHERE run_id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING *` đặt `last_swept_at = now` ngay lúc claim. Bảng không còn `steps jsonb` (đã chuyển sang `workflow_run_steps`).


## 11. Kiểm chứng và giới hạn của kiểm chứng

- **371 test đơn vị** (JUnit 5, không Spring/DB/broker): ActionRuntime 55, ActionContractV2 25, ActionHandler 16, validator 9, binder 7, resolver 7, run store 7, workflow engine 62, workflow model 22, workflow sweeper/DLQ 13, workflow shape 5, approval 22, scheduler 18 + dedupe 20 + cron 14, notification 15, canonical reader 26, tenant rate limit 17, retention 11.
- Môi trường làm việc **không chạy được `./gradlew test`** (không có Gradle/Maven Central). Test được biên dịch và chạy bằng `kotlinc 2.2.21` + JUnit shim + shim Jackson-3 tối thiểu (`MiniRunner`). Shim dễ tính hơn JUnit/Jackson thật nên **việc biên dịch với Jackson 3 và JUnit thật chưa được kiểm chứng** — C0 phải chạy `cd backend && ./gradlew test` trên JDK 21 trước khi merge. **Gradle actually run: NO. Không có tuyên bố integration PASS.**
- **Chưa có** test tích hợp RabbitMQ/Postgres (không có broker/DB; `InMemoryWorkflowQueue` mô phỏng at-least-once, redelivery, DLQ). Test tích hợp là việc đi kèm adapter của C0.
- Kiểm tra đột biến thủ công (phá cố ý rồi xem test có bắt không): key theo lượt, DLQ, guard kết quả muộn, tenant check, notification dedupe, misfire, và ở Phase 3: công bằng của claim sweeper, bỏ qua `notBefore`, ledger dedupe, guard đơn điệu, tính mốc kế tiếp từ đồng hồ giật lùi, điều kiện đủ điều kiện của retention (bỏ `terminal`, bỏ `compensation`, xoá `RUNNING`). Các đột biến đều bị test bắt, trừ một thay đổi tương đương (kiểm tra self-approval thứ hai thừa) và các guard kép (ví dụ kiểm tra `terminal` lặp lại trong `purgeFinal` của approval — cố ý thừa).

## 12. Thay đổi so với PREP-T13

| PREP | Final | Lý do |
|---|---|---|
| `ActionAuthorizer.authorize(ctx, definition)` | `AccessPort.check(ctx, AccessRequest(permission, resourceKind, resourceId, appId, mode))` + `TenantGate` + `PrincipalResolver` | C1 cần cấp nhiều quyền (APP_USE, ACTION_EXECUTE, WORKFLOW_EXECUTE, WORKFLOW_MANAGE…) cho action/workflow/schedule/approval |
| `mutationId`, `operationId`, `templateId`, `workflowId` | `queryRef` (WRITE query của AppDefinition), `dataSourceRef`+`operationKey`, `templateRef`, `workflowRef` | khớp tên canonical của C2; định nghĩa đọc từ AppDefinition, không bảng riêng (D-C4-03) |
| `ActionDataPort.mutate/callApi` | `write(WriteRequest)`, `callOperation(OperationRequest)`, `dryRunWrite/dryRunOperation` | thêm dry-run trung thực cho TEST |
| 8 loại, không `REFRESH_QUERY` | 8 loại + `REFRESH_QUERY` | C2 có `RUN_QUERY`; map thành chỉ thị client, không đọc dữ liệu ở server |
| `ActionRunStore` scope `(tenant, action, key)` | `(tenant, app, action, user, key)` | chống phát lại chéo user |
| Lỗi retry/backoff/DLQ "việc của worker" | `WorkflowEngine` + `WorkflowWorker` | T14 |
| (Phase 2) alias `RUN_QUERY→REFRESH_QUERY`, `WRITE_DATA→SUBMIT_FORM`, `CALL_CONNECTOR_OPERATION→CALL_API` | **bỏ alias**, so khớp chính xác 9 tên chuẩn | contract v2 chốt tên; alias che lỗi của producer |
| key idempotency thô đi xuống port | key dẫn xuất sha256, port từ chối key sai dạng | không rò key thô xuống C3/log |
| `WriteRequest(queryRef, kind, params, key?)` | `WriteRequest(appId, mode, queryRef, kind, params, derivedKey)` | adapter C0/C3 cần app + mode để tra binding |
| `nack(requeue=true)` khi lỗi | ack + sweeper, ngân sách lỗi theo run | một poison / sự cố hạ tầng không đẩy run khỏe vào DLQ |
| sweeper "N đầu theo updatedAt" ×3 truy vấn | claim hợp nhất công bằng, có chặn | run chờ lâu không chiếm hết slot |
| scheduler chỉ dựa CAS + key | + sổ thực thi, guard đơn điệu, backoff/give-up, sync khai báo idempotent | không bắn trùng khi restart/nhiều worker/đồng hồ lệch |

## 13. Giới hạn đã biết

1. Compensation + vòng lặp: chỉ bù **lượt cuối** của mỗi step (mỗi step một `StepState`).
2. Giao thông báo at-least-once sau crash (xem §7).
3. Giới hạn payload đo bằng độ dài chuỗi JSON (ký tự), không phải byte UTF-8.
4. `Event.id` do client gửi chỉ dùng để khử trùng; được làm sạch/băm trước khi thành key.
5. Rate limit theo tenant là token bucket **trong tiến trình** (`InMemoryTenantRateLimiter`, có chặn bộ nhớ): với N node, tổng hạn mức hiệu dụng gần N lần. Limiter dùng chung toàn cụm là adapter của C0 (B-C4-08). `DOWNLOAD_FILE`/`OPEN_URL` chưa làm (§2).
6. Mọi store chỉ có bản in-memory; chạy thật cần adapter JDBC (B-C4-05/07). Hợp đồng store (claim sweeper công bằng, ledger, purge) đã ghi kèm SQL tương đương.
7. Retention tenant-agnostic theo tuổi; xoá hàng `action_runs`/`workflow_runs` chấm dứt khử trùng của key (horizon tối thiểu 7 ngày).
8. Workflow TEST vẫn tạo một dòng `workflow_runs` (`mode=TEST`); hàng này cũng già đi theo retention như run LIVE.
9. DST fall-back: một lịch theo giờ địa phương chỉ được bắn ở lần xuất hiện đầu của giờ lặp (không bao giờ bắn đôi); lần lặp thứ hai bị bỏ qua có chủ ý.
