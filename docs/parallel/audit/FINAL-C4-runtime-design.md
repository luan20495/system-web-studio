# FINAL-C4 — Action / Workflow runtime: thiết kế, hợp đồng, DDL đề xuất

Owner: **C4** · Branch `agent/c4-workflow` · Base `d3c7065d3b6963dc625be5d0725922f5004fb818`
Thay thế các phần đã lỗi thời của `PREP-T13-action-runtime-design.md` (xem §12). ID tài liệu: `B-C4-xx` (BLOCKERS), `D-C4-xx` (DECISIONS).

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

1. Dạng idempotency key (`[A-Za-z0-9._:-]{1,128}`).
2. Tenant gate (`TENANT_DISABLED`; không đọc được trạng thái → `DEPENDENCY_UNAVAILABLE`, fail closed).
3. Cần `ctx.projectId` (app). Definition được tìm **theo (tenant, app)**; tenant/app khác, không tồn tại, `enabled=false` đều → `UNKNOWN_ACTION`.
4. Quyền (mặc định từ chối, audit `DENIED`): `APP_USE`, `ACTION_EXECUTE`, `requiredPermission` khai báo trong action, và `WORKFLOW_EXECUTE` cho `START_WORKFLOW`. Lỗi hệ thống quyền → fail closed. UI không được tin: quyền luôn lấy từ `ActionContext` do server dựng.
5. Handler theo type (`UNSUPPORTED_ACTION_TYPE`).
6. Call depth + `ActionDefinitionValidator` + `handler.validate`.
7. `InputResolver` (nguồn: component state, route params, form, viewModel, previous result, literal, context do server cấp) rồi `ActionInputBinder` (chỉ tên đã khai báo, đúng kiểu, trong giới hạn).
8. **TEST**: `handler.preview` → `WouldRun`; không ghi run state, không cần key, audit `PREVIEWED`.
9. **LIVE**: `ActionRunStore.begin` (CAS) → chạy / replay / `ACTION_IN_PROGRESS` / `IDEMPOTENCY_KEY_REUSED`.
10. Audit `STARTED` — fail closed (`AUDIT_UNAVAILABLE`, retryable).
11. Handler chạy trên executor với timeout.
12. `complete` + audit kết thúc (best effort — action đã xảy ra).

`run()` thực hiện thêm chuỗi `onSuccess`/`onError` (key dẫn xuất `parentKey:s:id` / `:e:id`, giới hạn `callDepth` và `maxChainActions`; lỗi ở cổng — quyền/tenant/… — không kích hoạt `onError`). `dispatch(ctx, event)` lấy `ActionRef` khớp `(sectionId, eventType)`, chạy từng ref với key `evt:<eventId đã làm sạch>:<refId>`; payload của client chỉ vào được input **đã khai báo** và **đã có `inputMapping`**.

### Loại action (đóng kín — D-C4-01)

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

Phạm vi key: `(tenant, app, action, user, key)`. Có `user` trong phạm vi để user B không phát lại kết quả của user A và không chặn key của A. Cùng key + cùng fingerprint input: SUCCEEDED/FAILED không retry → replay kết quả; FAILED `retryable` → chạy lại (attempt + 1); cùng key khác input → `IDEMPOTENCY_KEY_REUSED`. Nhờ vậy retry không bao giờ tạo bản ghi nghiệp vụ thứ hai (với điều kiện adapter C3 chuyển tiếp `idempotencyKey` xuống connector — B-C4-04).

## 3. Mô hình lỗi

`ActionResult.Failed(code, retryable, message?, details)`; `message` an toàn để hiển thị. Mã chính: `UNKNOWN_ACTION`, `FORBIDDEN`, `TENANT_DISABLED`, `INVALID_INPUT`, `INVALID_DEFINITION`, `LIMIT_EXCEEDED`, `IDEMPOTENCY_KEY_REQUIRED/INVALID/REUSED`, `ACTION_IN_PROGRESS` (retryable), `NOT_IMPLEMENTED`, `TIMEOUT`/`INTERRUPTED` (retryable chỉ khi an toàn: không đổi state hoặc có key), `DEPENDENCY_UNAVAILABLE`/`AUDIT_UNAVAILABLE` (retryable), `HANDLER_ERROR` (không lộ message). Mã của downstream đi qua nguyên vẹn. Workflow thêm: `UNKNOWN_WORKFLOW`, `WORKFLOW_RUN_NOT_FOUND`, `DEAD_LETTERED`, `APPROVAL_REJECTED`, `APPROVAL_EXPIRED`, `CONFLICT`.

## 4. T14 — Workflow engine

### Định nghĩa (khai báo, không script)

`WorkflowDefinition(id, trigger MANUAL|SCHEDULE|ACTION, steps, startStepId?, limits, compensateOnCancel)`. Step: `ACTION` (actionRef + `inputs` kiểu `ValueRef`: Input / Step output / Literal), `WAIT`, `APPROVAL`, `BRANCH` (điều kiện có kiểu: Compare/Exists/AllOf/AnyOf/Not — **không có ngôn ngữ biểu thức**), `END`. Mỗi step có `next`, `onError`, `retry` (maxAttempts, backoff mũ, trần), `timeout`, `compensationActionRef` tùy chọn. `WorkflowDefinitionValidator` kiểm: id, tham chiếu, step không với tới được, giới hạn so với trần nền tảng, độ sâu/số node điều kiện.

### Trạng thái chạy

`WorkflowRun` (bảng `workflow_runs`): định nghĩa được **chụp snapshot** lúc start (sửa/republish không ảnh hưởng run đang chạy), `status PENDING|RUNNING|WAITING|SUCCEEDED|FAILED|CANCELLED`, `steps` (trạng thái từng step: attempt, input/output, lỗi, thời điểm, `wakeAt`, `approvalId`, `simulated`, `visit`), `compensable`, `createdBy`, `version`. **Mọi chuyển trạng thái là CAS trên dòng run** (`mutate`/`Transition`), nên message trùng/mất/đảo thứ tự không làm hỏng run.

### Hàng đợi và worker

- Message `v1|jobId|tenantId|runId|stepId` — **không mang actor, quyền hay payload**; worker nạp lại run (và actor, định nghĩa đã snapshot) từ store. Message giả/phát lại không thể mở rộng quyền của run.
- Worker: `claim` step (CAS PENDING/RETRY_WAIT→RUNNING) → thực thi **ngoài lock** → ghi kết quả bằng CAS (nếu run đã bị huỷ/timeout/sweeper reset thì kết quả muộn bị bỏ) → publish step kế tiếp.
- `start` chỉ lưu run và publish job đầu rồi trả về — **không chặn HTTP**.
- Retry/backoff: lỗi `retryable` còn lượt → `RETRY_WAIT` với `wakeAt`; **sweeper** publish khi tới hạn. Không cần delayed-delivery của broker.
- Crash: step kẹt `RUNNING` quá `staleAfter` → sweeper đưa về `RETRY_WAIT` và publish lại; vì action dùng key `wf:<runId>:<stepId>` nên việc làm lại một step đã xong là *replay*, không phải tác dụng thứ hai. Vòng lặp quay lại cùng step dùng key `…:v<visit>` nên lượt thứ hai chạy thật.
- DLQ: message lỗi hạ tầng liên tục tới `maxDeliveries` → dead-letter; `drainDeadLetters` → `failFromDeadLetter` đánh dấu run `FAILED/DEAD_LETTERED` (không treo im lặng). Message sai định dạng bị `nack(requeue=false)` thẳng vào DLQ.
- Resume: toàn bộ trạng thái nằm trong store; khởi động lại node không mất gì. Sweeper cũng xử lý: job mất trước khi publish, run quá `maxDuration` (`TIMEOUT`), compensation kẹt, phê duyệt đã xong mà callback mất.

### Quyền khi chạy

Run chạy **như người tạo** (`createdBy`); mỗi ACTION step đi qua `ActionRuntime` nên tenant gate và quyền của user đó được kiểm lại **ở thời điểm chạy step**, không nhớ từ lúc start. Tenant bị disable giữa chừng → run `FAILED/TENANT_DISABLED`.

### Compensation, cancel, giới hạn

Step ACTION thành công có `compensationActionRef` được ghi vào `compensable`; khi run FAILED (hoặc CANCELLED với `compensateOnCancel`) job `~compensate` chạy bù theo **thứ tự ngược**; một bước bù lỗi → `PARTIAL` (các bước còn lại vẫn chạy). Cancel idempotent, huỷ cả approval đang chờ. Giới hạn: `maxSteps`, `maxStepExecutions` (chặn vòng lặp), `maxRetries`, `maxDuration`, `maxWait`, `maxPayloadBytes`, `maxDepth` (workflow→action→workflow), `maxStepTimeout`; định nghĩa chỉ được **hạ** trần nền tảng.

## 5. Approval

`ApprovalService`: `request / decide / comment / cancel / get / inbox / expireDue`. Approver là `PrincipalSpec` USER | GROUP | DEPARTMENT_MANAGER | ROLE, được **resolve và chụp snapshot** lúc tạo (đổi thành viên nhóm sau đó không ảnh hưởng yêu cầu đang chờ); người yêu cầu bị loại khỏi approver trừ khi `allowSelfApproval`. Quorum `requiredApprovals`; **một từ chối là từ chối cả yêu cầu**; quyết định lặp lại cùng ý là idempotent, đổi ý là `CONFLICT`; hết hạn lazily khi có quyết định muộn và bằng `expireDue`. Cross-tenant: bị từ chối (trả "không tìm thấy") trừ khi `CrossTenantApprovalPolicy` — do C1 cấp — cho phép, và vẫn phải là approver. Audit mọi bước; thông báo in-app (best effort); listener báo workflow, engine còn reconcile bằng sweeper.

## 6. Scheduler

`SchedulerService`: `create / setEnabled / delete / list / tick`. Cron 5 trường tự cài (không thư viện), timezone, DST: giờ không tồn tại (spring forward) kích hoạt đúng một lần sau khoảng trống, giờ lặp (fall back) kích hoạt một lần ở lần đầu. **Scheduler chỉ enqueue**: `tick` claim các schedule đến hạn bằng CAS (nhiều node không bắn đôi), rồi giao `ScheduledRunRequest` (key tất định `sched:<id>:<fireEpochMilli>`) cho `ScheduledRunEnqueuer`; không gọi action/connector/notification. Outbox `pendingFireAt` bảo đảm không mất fire khi crash giữa claim và enqueue; lỗi vĩnh viễn (vd. owner mất quyền) xóa pending thay vì retry mãi. Misfire: `SKIP` hoặc `FIRE_ONCE` (tối đa một lần bù). Tenant disabled → bỏ qua. Schedule target là workflow hoặc một action (chạy như run 1 bước, có retry/audit/quyền như workflow).

## 7. Notification ports

`NotificationService : ActionNotifyPort` + `ChannelSender` theo kênh (`IN_APP`, `EMAIL`, `WEBHOOK`, `SMS`) — **không hard-code vendor**; vendor là một `ChannelSender` do bên tích hợp viết. Nội dung chỉ là **template đã duyệt** (`NotificationCatalog.template`) + tham số có kiểu; webhook chỉ tới `endpointRef` đã đăng ký (URL/credential chỉ sender biết). Người nhận resolve qua `PrincipalResolver` (tối đa 100); `DeliveryStore` khử trùng theo (key, người nhận): retry chỉ gửi lại người thất bại. Claim bị bỏ rơi do crash được lấy lại sau lease (5 phút) — nên giao hàng là *at-least-once* trừ khi provider khử trùng theo `deliveryKey`. Kênh chưa có sender → `NOT_IMPLEMENTED` (không giả vờ).

## 8. TEST mode (`ExecutionMode.TEST`)

- Action: `handler.preview` → `ActionResult.WouldRun(actionId, type, level, plan, output?, reason)`. **Không** commit mutation, **không** gửi thông báo thật, **không** start workflow, không tạo run state/idempotency. `plan` chỉ có *tên* input, không có giá trị.
- `level`: `NOT_EXECUTED` (mặc định trung thực: downstream không có dry-run), `VALIDATED` (downstream kiểm tra không side effect), `SANDBOX` (downstream chạy trong sandbox riêng). Chỉ khi `ActionDataPort.dryRunWrite/dryRunOperation` trả `Validated/Sandboxed` mới lên cấp cao hơn — "không giả vờ dry-run nếu connector không hỗ trợ". Port chưa nối → `NOT_IMPLEMENTED` kể cả ở TEST.
- Workflow TEST: chạy các step ở TEST mode, bỏ qua WAIT, mô phỏng APPROVAL (không tạo approval), không compensation; step đánh dấu `simulated` + `dryRunLevel`. TEST và LIVE là hai phạm vi idempotency riêng. Định nghĩa TEST đọc **bản nháp**, LIVE đọc bản đã publish (`AppDefinitionSource.load(…, mode)`). Quyền cho TEST (xem bản nháp) do C1 quyết qua `AccessRequest.mode` (B-C4-01).

## 9. Bảo mật — đối chiếu yêu cầu

| Yêu cầu | Cách thực hiện |
|---|---|
| Không script/URL/SQL tùy ý | validator từ chối key cấm ở mọi độ sâu; tham chiếu chỉ là id thuần; `CALL_API` = `dataSourceRef`+`operationKey`; không có ngôn ngữ biểu thức |
| Không bypass DataGateway | handler chỉ có `ActionDataPort`; adapter C3 đi qua DataGateway (B-C4-04) |
| Không credential | không nơi nào trong model có credential; webhook = `endpointRef` |
| Không chạy khi thiếu quyền | `AccessPort` trước handler, mỗi execute và mỗi workflow step; mặc định từ chối, fail closed |
| Tenant isolation | mọi lookup mang `tenantId`; job không mang quyền; run/approval/schedule truy cập chéo tenant trả "không tìm thấy" |
| Giới hạn | `ActionLimits`, `WorkflowLimits`, số người nhận, payload, depth, retries, timeout; rate-limit theo tenant **chưa làm** (B-C4-08) |
| Audit | `ActionAuditPort` (fail closed trước khi chạy), `LogicAuditPort` cho workflow/approval/schedule/notification; không ghi giá trị input/output |

## 10. Việc tích hợp còn lại (không thuộc ownership của C4)

Xem BLOCKERS B-C4-01…09. Tóm tắt: C1 cấp permission + `TenantContext` + resolver nhóm/role/manager; C2 chốt trường bổ sung của `actions[]`/`workflows[]`; C3 cấp cổng ghi/`callOperation`/dry-run; C0 cấp migration, adapter JDBC, RabbitMQ (queue + DLQ), wiring Spring, timer cho `sweep()`/`tick()`/`expireDue()`, adapter audit.

### RabbitMQ — ánh xạ `WorkflowQueue`

Queue `xweb.workflow.jobs` kiểu **quorum** (có `x-delivery-count` và `delivery-limit` = `maxDeliveries`, quá giới hạn tự dead-letter), DLX → `xweb.workflow.jobs.dlq`. `publish` = persistent + publisher confirm (lỗi → run vẫn `PENDING`, sweeper publish lại); `poll` = consumer với ack thủ công, prefetch nhỏ; `nack(requeue=true)` = giao lại; `nack(requeue=false)` hoặc quá `delivery-limit` = DLQ. Consumer của DLQ gọi `WorkflowWorker.drainDeadLetters`.

### DDL đề xuất (chưa tạo file; số version do C0 cấp — BOARD.md)

```sql
-- T13: run state + idempotency
CREATE TABLE action_runs (
  run_id uuid PRIMARY KEY, tenant_id uuid NOT NULL, app_id uuid NOT NULL, action_id text NOT NULL, user_id uuid NOT NULL,
  idempotency_key text NOT NULL, fingerprint text NOT NULL, status text NOT NULL, attempt int NOT NULL,
  result jsonb, started_at timestamptz NOT NULL, finished_at timestamptz, updated_at timestamptz NOT NULL,
  UNIQUE (tenant_id, app_id, action_id, user_id, idempotency_key));
CREATE INDEX action_runs_stale ON action_runs (updated_at) WHERE status = 'RUNNING';

-- T14: workflow run (steps nằm trong dòng run để CAS nguyên tử; có thể thêm bảng chiếu cho báo cáo)
CREATE TABLE workflow_runs (
  run_id uuid PRIMARY KEY, tenant_id uuid NOT NULL, app_id uuid NOT NULL, workflow_id text NOT NULL, mode text NOT NULL,
  status text NOT NULL, created_by uuid NOT NULL, actor_kind text NOT NULL, workspace_id uuid,
  idempotency_key text NOT NULL, fingerprint text NOT NULL, input jsonb NOT NULL, definition jsonb NOT NULL,
  current_step_id text, steps jsonb NOT NULL, compensable jsonb NOT NULL DEFAULT '[]', step_executions int NOT NULL, depth int NOT NULL,
  error_code text, error_message text, compensation text NOT NULL DEFAULT 'NONE',
  cur_step_status text, cur_wake_at timestamptz, cur_approval_id uuid,   -- phi chuẩn hóa từ steps cho sweeper
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, finished_at timestamptz, version bigint NOT NULL,
  UNIQUE (tenant_id, app_id, workflow_id, created_by, mode, idempotency_key));
CREATE INDEX workflow_runs_timers ON workflow_runs (cur_wake_at) WHERE cur_step_status IN ('WAITING','RETRY_WAIT') AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED');
CREATE INDEX workflow_runs_stale ON workflow_runs (updated_at) WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED') OR compensation = 'IN_PROGRESS';
CREATE INDEX workflow_runs_approval ON workflow_runs (cur_approval_id) WHERE cur_approval_id IS NOT NULL;

CREATE TABLE approvals (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, app_id uuid, title text NOT NULL, requested_by uuid NOT NULL,
  requested_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, approver_specs jsonb NOT NULL, approvers uuid[] NOT NULL,
  required_approvals int NOT NULL, allow_self_approval boolean NOT NULL, status text NOT NULL,
  decisions jsonb NOT NULL DEFAULT '[]', comments jsonb NOT NULL DEFAULT '[]', source_run_id uuid, source_step_id text,
  idempotency_key text, finished_at timestamptz, version bigint NOT NULL);
CREATE UNIQUE INDEX approvals_idem ON approvals (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX approvals_inbox ON approvals USING gin (approvers) WHERE status = 'PENDING';
CREATE INDEX approvals_expiry ON approvals (expires_at) WHERE status = 'PENDING';

CREATE TABLE schedules (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, app_id uuid NOT NULL, name text NOT NULL, cron text NOT NULL, timezone text NOT NULL,
  enabled boolean NOT NULL, target_kind text NOT NULL, target_ref text NOT NULL, input jsonb, created_by uuid NOT NULL,
  misfire_policy text NOT NULL, next_run_at timestamptz, last_run_at timestamptz, last_run_status text, pending_fire_at timestamptz,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version bigint NOT NULL);
CREATE INDEX schedules_due ON schedules (next_run_at) WHERE enabled AND pending_fire_at IS NULL;
CREATE INDEX schedules_pending ON schedules (updated_at) WHERE pending_fire_at IS NOT NULL;

CREATE TABLE notification_deliveries (
  tenant_id uuid NOT NULL, delivery_key text NOT NULL, status text NOT NULL, updated_at timestamptz NOT NULL, PRIMARY KEY (tenant_id, delivery_key));
CREATE TABLE in_app_notifications (   -- adapter của kênh IN_APP
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, user_id uuid NOT NULL, template_ref text NOT NULL, params jsonb NOT NULL, created_at timestamptz NOT NULL, read_at timestamptz);
CREATE INDEX in_app_notifications_user ON in_app_notifications (tenant_id, user_id, created_at DESC);
```

Mọi bảng đều chứa `tenant_id` và mọi truy vấn của adapter phải lọc theo nó. `compareAndSet(expected, next)` = `UPDATE … SET …, version = version + 1 WHERE tenant_id = ? AND <pk> = ? AND version = ?`.

## 11. Kiểm chứng và giới hạn của kiểm chứng

- **277 test đơn vị** (JUnit 5, không Spring/DB/broker): action 101, workflow engine 62 + model 21, approval 22, scheduler 18 + cron 14, notification 15, canonical reader 24 (con số theo lớp ở báo cáo cuối).
- Môi trường làm việc **không chạy được `./gradlew test`** (không có Gradle/Maven Central). Test được biên dịch và chạy bằng `kotlinc 2.2.21` + JUnit shim + shim Jackson-3 tối thiểu (`MiniRunner`). Shim dễ tính hơn JUnit/Jackson thật nên **việc biên dịch với Jackson 3 và JUnit thật chưa được kiểm chứng** — C0 phải chạy `cd backend && ./gradlew test` trên JDK 21 trước khi merge.
- **Chưa có** test tích hợp RabbitMQ/Postgres (không có broker/DB; `InMemoryWorkflowQueue` mô phỏng at-least-once, redelivery, DLQ). Test tích hợp là việc đi kèm adapter của C0.
- Đã chạy kiểm tra đột biến thủ công (phá cố ý claim step, key theo lượt, DLQ, guard kết quả muộn, tenant check, notification dedupe, misfire…): các lỗi chèn vào đều bị test bắt, trừ một thay đổi tương đương (kiểm tra self-approval thứ hai thừa vì requester đã bị loại khỏi snapshot) và các guard dự phòng chỉ có ý nghĩa khi tranh chấp đồng thời.

## 12. Thay đổi so với PREP-T13

| PREP | Final | Lý do |
|---|---|---|
| `ActionAuthorizer.authorize(ctx, definition)` | `AccessPort.check(ctx, AccessRequest(permission, resourceKind, resourceId, appId, mode))` + `TenantGate` + `PrincipalResolver` | C1 cần cấp nhiều quyền (APP_USE, ACTION_EXECUTE, WORKFLOW_EXECUTE, WORKFLOW_MANAGE…) cho action/workflow/schedule/approval |
| `mutationId`, `operationId`, `templateId`, `workflowId` | `queryRef` (WRITE query của AppDefinition), `dataSourceRef`+`operationKey`, `templateRef`, `workflowRef` | khớp tên canonical của C2; định nghĩa đọc từ AppDefinition, không bảng riêng (D-C4-03) |
| `ActionDataPort.mutate/callApi` | `write(WriteRequest)`, `callOperation(OperationRequest)`, `dryRunWrite/dryRunOperation` | thêm dry-run trung thực cho TEST |
| 8 loại, không `REFRESH_QUERY` | 8 loại + `REFRESH_QUERY` | C2 có `RUN_QUERY`; map thành chỉ thị client, không đọc dữ liệu ở server |
| `ActionRunStore` scope `(tenant, action, key)` | `(tenant, app, action, user, key)` | chống phát lại chéo user |
| Lỗi retry/backoff/DLQ "việc của worker" | `WorkflowEngine` + `WorkflowWorker` | T14 |

## 13. Giới hạn đã biết

1. Compensation + vòng lặp: chỉ bù **lượt cuối** của mỗi step (mỗi step một `StepState`).
2. Giao thông báo at-least-once sau crash (xem §7).
3. Giới hạn payload đo bằng độ dài chuỗi JSON (ký tự), không phải byte UTF-8.
4. `Event.id` do client gửi chỉ dùng để khử trùng; được làm sạch/băm trước khi thành key.
5. Rate limit theo tenant chưa có (B-C4-08); `DOWNLOAD_FILE`/`OPEN_URL` chưa làm (§2).
6. Mọi store chỉ có bản in-memory; chạy thật cần adapter JDBC (B-C4-05/07).
