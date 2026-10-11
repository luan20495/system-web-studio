> **SUPERSEDED_BY:** `docs/ACTION_WORKFLOW.md` - historical document, kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# PREP-T13 — ActionRuntime: thiết kế + scaffold

> **C0 note (2026-10-06, integration/v2):** the error-code semantics for `TIMEOUT`/`INTERRUPTED` of a *mutating* action in this document are superseded by **D-C4-17** (they become `IDEMPOTENCY_OUTCOME_UNKNOWN`, `retryable=false`), and UI `onError[]` no longer runs after `IDEMPOTENCY_OUTCOME_UNKNOWN` (**D-C0-13**). Non-mutating actions are unchanged. See `docs/parallel/DECISIONS.md`.

> **Đã được thay thế một phần bởi [`FINAL-C4-runtime-design.md`](FINAL-C4-runtime-design.md)** (Phase 2). Giữ lại làm lịch sử. Khác biệt chính: `ActionAuthorizer` → `AccessPort`/`TenantGate`/`PrincipalResolver`; `mutationId/operationId/templateId/workflowId` → `queryRef`, `dataSourceRef`+`operationKey`, `templateRef`, `workflowRef`; thêm `REFRESH_QUERY`; không còn bảng `action_definitions` (D-C4-03); phạm vi idempotency có thêm `app` và `user`. ID cũ B-004…B-008 / D-008…D-010 đã đổi thành B-C4-xx / D-C4-xx.

Owner: **C4** · Branch `agent/c4-workflow` · Base `d3c7065d3b6963dc625be5d0725922f5004fb818` · Trạng thái: scaffold xong, **chưa nối** với C1/C2/C3.
Liên quan: `docs/contracts/action-workflow.md`, `data-connector.md`, `permission-model.md`, `tenant-context.md`, `app-definition-v2.md`.

## 1. Phạm vi và nguyên tắc

Làm: domain model, port, runtime khung, handler cho 8 loại action, in-memory run store, test không cần Spring/DB/queue.
Không làm: workflow engine, scheduler, RabbitMQ, migration, endpoint HTTP, bean Spring, bất kỳ import nào tới code của C1/C2/C3.

Quy tắc thiết kế:

1. **Khai báo, có kiểu, không code.** Action chỉ chứa tham chiếu bằng id (`pageId`, `mutationId`, `operationId`, `workflowId`, `templateId`). Validator từ chối key kiểu `sql`, `url`, `script`, `code`… ở mọi độ sâu của `config`, và id phải khớp `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$` (không `:` `/` nên không thể là URL hay path).
2. **Không đường tắt dữ liệu.** Handler không biết DB/connector; mọi đọc/ghi qua `ActionDataPort` (C3 cấp adapter, đi qua `DataGateway`).
3. **Framework-free.** Package `logic.action` không có annotation Spring. Lý do thực tế: `SystemWebStudioApplication` scan toàn bộ `com.systemwebstudio`; một `@Component` cần port chưa có implementation sẽ làm app không khởi động. Việc wiring Spring là bước tích hợp sau (§10).
4. **Mặc định deny, fail closed.** Authorizer lỗi → từ chối; audit `STARTED` lỗi → không chạy; port chưa nối → `NOT_IMPLEMENTED`.
5. **Context truyền tường minh**, không ThreadLocal, để worker bất đồng bộ dựng lại từ `tenantId` + `actorUserId`.

## 2. Sơ đồ phụ thuộc

```
                    ┌────────────── logic.action (C4, không import C1/C2/C3) ──────────────┐
 HTTP controller ─┐ │                                                                      │
 queue worker ────┼─► ActionRuntime ──► ActionHandlerRegistry ──► ActionHandler (8 loại)   │
 workflow engine ─┤ │      │                                         │                     │
 scheduler ───────┘ │      │ ports (interface do C4 định nghĩa)      │                     │
                    └──────┼─────────────────────────────────────────┼─────────────────────┘
                           ▼                                         ▼
   ActionDefinitionProvider  ActionBindingPort  ActionAuthorizer   ActionDataPort  ActionNotifyPort  WorkflowStarterPort
   ActionAuditPort           ActionRunStore
        │ C4/C2                  │ C2                │ C1                │ C3              │ C4/C0            │ C4 (logic.workflow)
        ▼                        ▼                   ▼                   ▼                 ▼                  ▼
   (bảng action_*, §9)     AppDefinition.actions  AccessService +    DataGateway +     in-app/email      WorkflowRuntime.start
                           (ActionRef)            ACTION_EXECUTE     connector proxy
```

Chiều phụ thuộc: `logic.workflow → logic.action`, không ngược lại (START_WORKFLOW đi qua `WorkflowStarterPort`). Adapter (code nối) nằm **ngoài** `logic.action`, ở nơi có quyền import cả hai phía.

## 3. Domain model (`logic/action/`)

| Kiểu | Vai trò |
|---|---|
| `ActionType` | 8 loại: `NAVIGATE SUBMIT_FORM CREATE_RECORD UPDATE_RECORD DELETE_RECORD CALL_API NOTIFY START_WORKFLOW`; có cờ `mutatesState` |
| `ActionDefinition` | `id, tenantId, type, inputs: List<InputSpec>, config: Map<String,JsonNode>, idempotency: IdempotencyPolicy, limits, enabled` |
| `ActionContext` | Người gọi: `tenantId, actor(userId, kind), workspaceId?, projectId?, requestId?`. Chỉ do server dựng |
| `ActionRequest` | `actionId, inputs, idempotencyKey?, trigger(UI_EVENT\|WORKFLOW_STEP\|SCHEDULE), callDepth` (đúng shape contract) |
| `ActionInput` | Input đã qua `ActionInputBinder`: chỉ tên đã khai báo, đúng kiểu JSON, trong giới hạn |
| `ActionResult` | `Ok(output)` \| `Failed(code, retryable, message?, details)` |
| `ActionHandler` | `type`, `validate(definition)`, `execute(ctx, definition, input, run)` |
| `ActionRuntime` | `execute(ctx, request)` và `dispatch(ctx, event)` |
| `Event`, `ActionRef`, `TriggerInfo` | Sự kiện UI/workflow/lịch; `ActionRef` phản chiếu `ActionRef(id, trigger, actionId)` của C2 |
| `ActionRun` | Dữ kiện mỗi lần chạy cho handler: `runId, idempotencyKey, trigger, callDepth` |

`ActionRuntime.execute` nhận `ActionContext` thay vì `(AccessContext, TenantContext)` như contract — xem **D-C4-02**.

## 4. Pipeline của `DefaultActionRuntime.execute`

Thứ tự cố ý: kiểm tra rẻ và ít tiết lộ trước, side effect sau cùng.

1. Idempotency key đúng dạng (`[A-Za-z0-9._:-]{1,128}`).
2. Tìm definition **theo tenant**; id của tenant khác, không tồn tại hay `enabled=false` đều → `UNKNOWN_ACTION` (cùng mã, không lộ sự tồn tại). Runtime kiểm lại `definition.tenantId == ctx.tenantId` dù provider đã lọc.
3. `ActionAuthorizer` → `FORBIDDEN` (audit `DENIED`). Chạy **trước** khi nhìn input nên người không có quyền không nhận được chi tiết validate.
4. Có handler cho type chưa → `UNSUPPORTED_ACTION_TYPE`.
5. `callDepth` ≤ giới hạn; `ActionDefinitionValidator` + `handler.validate` → `LIMIT_EXCEEDED` / `INVALID_DEFINITION`.
6. Bind input: kích thước, độ sâu, tên đã khai báo, kiểu → `LIMIT_EXCEEDED` / `INVALID_INPUT` (chi tiết theo từng input).
7. Idempotency: `IdempotencyPolicy.REQUIRED` thiếu key → `IDEMPOTENCY_KEY_REQUIRED`; `ActionRunStore.begin` (CAS) → chạy / replay kết quả cũ / `ACTION_IN_PROGRESS` / `IDEMPOTENCY_KEY_REUSED`.
8. Audit `STARTED` — **fail closed** (`AUDIT_UNAVAILABLE`, retryable; run state đóng lại để cùng key thử lại được).
9. Chạy handler trên `ExecutorService` với timeout; hết giờ → `future.cancel(true)`.
10. `ActionRunStore.complete` (CAS theo `runId`) và audit `SUCCEEDED|FAILED` (best effort — action đã xảy ra nên lỗi audit không được che kết quả).

`dispatch(ctx, event)`: lấy `ActionRef` từ `ActionBindingPort` khớp `event.name`, mỗi ref chạy độc lập qua `execute` với key `evt:<eventId>:<refId>` (giao lại cùng event không chạy lại), chỉ chuyển những field payload mà action **khai báo** là input.

## 5. Port và ai cung cấp implementation

| Port | Phương thức | Cung cấp | Ghi chú |
|---|---|---|---|
| `ActionDefinitionProvider` | `find(tenantId, actionId)` | C4 (bảng riêng) hoặc C2 | Không có `find(actionId)` thiếu tenant. Xem §9 |
| `ActionBindingPort` | `refsFor(ctx, eventName)` | C2 | Đọc `AppDefinition.actions[]` của version đang chạy |
| `ActionAuthorizer` | `authorize(ctx, definition)` | C1 | `AccessService.forResource(userId, tenant, ResourceRef(ACTION, id))` + `require(ACTION_EXECUTE)`; quyền lấy phía server, không từ payload |
| `ActionAuditPort` | `record(entry)` | adapter trên `AuditService.record` (C0-gated) | Entry **không** chứa giá trị input/output |
| `ActionDataPort` | `mutate`, `callApi` | C3 | Adapter tự kiểm quyền + tenant + SSRF/limits của connector (phòng thủ nhiều lớp), không dựa vào `ActionAuthorizer` |
| `ActionNotifyPort` | `send` | C4/C0 | Chưa chọn nhà cung cấp email; có thể bắt đầu bằng in-app |
| `WorkflowStarterPort` | `start` | C4 (`logic.workflow`) | Bắt buộc idempotency key |
| `ActionRunStore` | `begin/complete/find/sweepStale` | C4 + migration | Có `InMemoryActionRunStore` để test và chạy sớm |

## 6. Từng loại action

| Type | `config` (id tham chiếu) | Input | Gọi | Output (`Ok.output`) |
|---|---|---|---|---|
| `NAVIGATE` | `pageId` | tuỳ khai báo → `params` | không (hoàn chỉnh) | `{action:"NAVIGATE", pageId, params}` — chỉ là chỉ thị cho client |
| `NOTIFY` | `channel` (`IN_APP`\|`EMAIL`), `templateId`, `recipientUserIds?` | tham số template | `ActionNotifyPort.send` | do port |
| `SUBMIT_FORM` | `mutationId` | các field form | `ActionDataPort.mutate(kind=SUBMIT)` | do port |
| `CREATE_RECORD` | `mutationId` | field | `mutate(kind=CREATE)` | do port |
| `UPDATE_RECORD` | `mutationId` | bắt buộc có `recordId: STRING required` | `mutate(kind=UPDATE)` | do port |
| `DELETE_RECORD` | `mutationId` | bắt buộc có `recordId: STRING required` | `mutate(kind=DELETE)` | do port |
| `CALL_API` | `operationId` (operation connector đã khai báo) | tham số | `ActionDataPort.callApi` | do port |
| `START_WORKFLOW` | `workflowId`; `idempotency` phải là `REQUIRED` | input workflow | `WorkflowStarterPort.start` | do port |

Quyết định đáng chú ý:

- **Ghi dữ liệu = tham chiếu `mutationId`** (đối xứng với `queryId` của `data-connector.md`), không phải tên bảng/SQL/URL. Contract của C3 hiện chưa có phía ghi; đây là điều C4 cần C3 xác nhận (**B-C4-04**). `recordId` của UPDATE/DELETE đi như một param thường, connector tự bind.
- `recipientUserIds` nằm trong **definition**, không nhận từ input lúc chạy, để action NOTIFY không bị dùng làm công cụ spam người khác.
- Port chưa nối → `DefaultActionHandlers.registry` vẫn đăng ký đủ 8 type, loại thiếu port là `NotImplementedActionHandler` (`NOT_IMPLEMENTED`, không retry).

## 7. Mô hình lỗi và retry

`Failed(code, retryable, message, details)`; `message` an toàn để hiển thị (không credential/SQL/stack/body downstream); `details` map tên input → lỗi.

| Code | retryable | Khi nào | Gợi ý HTTP |
|---|---|---|---|
| `UNKNOWN_ACTION` | không | không tồn tại / tenant khác / disabled | 404 |
| `FORBIDDEN` | không | authorizer từ chối | 403 |
| `UNSUPPORTED_ACTION_TYPE`, `INVALID_DEFINITION` | không | cấu hình sai | 500/422 |
| `INVALID_INPUT`, `IDEMPOTENCY_KEY_REQUIRED/INVALID` | không | input sai | 400/422 |
| `LIMIT_EXCEEDED` | không | quá kích thước/độ sâu/call depth | 413/422 |
| `IDEMPOTENCY_KEY_REUSED` | không | cùng key khác input | 409 |
| `ACTION_IN_PROGRESS` | **có** | cùng request đang chạy | 409 |
| `NOT_IMPLEMENTED` | không | port chưa nối | 501 |
| `TIMEOUT`, `INTERRUPTED` | **chỉ khi** action không đổi state **hoặc** có idempotency key | kết quả không chắc chắn nên chỉ an toàn thử lại khi có dedupe | 504 |
| `DEPENDENCY_UNAVAILABLE`, `AUDIT_UNAVAILABLE` | có | authorizer/run store/audit/executor lỗi | 503 |
| `HANDLER_ERROR` | không | handler ném exception (không lộ message; log chỉ tên class + runId) | 500 |
| mã của downstream (vd `QUERY_TIMEOUT`) | theo port | `PortOutcome.Failure` đi qua nguyên vẹn | — |

Retry/backoff/DLQ là việc của **worker/workflow engine** (T13 đầy đủ), dựa trên cờ `retryable` + idempotency key; runtime không tự retry.

## 8. Quyền, tenant, giới hạn

- Tenant: mọi lookup mang `tenantId`; key idempotency scope theo `(tenantId, actionId, key)`; test chéo tenant có (`another tenant's action…`, `the same key in two tenants…`).
- Quyền: `ActionAuthorizer` default-deny. Cần C1 thêm `ACTION_EXECUTE` (+ `WORKFLOW_MANAGE`, `ResourceType.ACTION`) — **B-C4-01**. Worker không tin payload về quyền: dựng lại `ActionContext` từ `tenantId` + `actorUserId` rồi gọi lại authorizer.
- Giới hạn (`ActionLimits`): `timeout` 30s, `maxInputBytes` 64 KiB, `maxInputDepth` 8, `maxCallDepth` 5 — là **trần** của runtime, definition chỉ được hạ thấp. Rate limit theo tenant **chưa làm** (cần state dùng chung; thuộc bước tích hợp, có thể dùng `common/RateLimiter` — C0).
- Không thực thi code: không có `eval`, script, SQL hay URL thô ở bất kỳ đâu trong model.

## 9. Lưu trữ — cần migration (chưa xin số, chưa tạo file)

`ActionRunStore` và `ActionDefinitionProvider` hiện chỉ có in-memory/fake. Để chạy thật cần (đã ghi request trong `BOARD.md`, **không** tự chọn version):

- `action_definitions(id, tenant_id, type, name, inputs jsonb, config jsonb, idempotency, limits jsonb, enabled, …)` — hoặc C2 lưu trong `AppDefinitionV2.extensions` và C4 chỉ đọc (cần C0/C2 chốt, **D-C4-03**).
- `action_runs(tenant_id, action_id, idempotency_key, run_id, fingerprint, status, attempt, result jsonb, started_at, updated_at)` với unique `(tenant_id, action_id, idempotency_key)`; `begin` = `INSERT … ON CONFLICT` / `UPDATE … WHERE status = 'FAILED' AND retryable` (CAS); sweeper cho run `RUNNING` quá hạn — cùng kiểu với `publish/PublishWorker`.

## 10. Thứ tự tích hợp đề xuất

1. **C0** duyệt D-C4-01…D-C4-03, cấp số migration (nếu đồng ý).
2. **C1** công bố `TenantContext` + permission `ACTION_EXECUTE` → viết `ActionAuthorizer` adapter và `ActionContextFactory(AccessContext, TenantContext)`.
3. **C2** chốt shape `ActionRef` + nơi lưu definition → `ActionBindingPort`, `ActionDefinitionProvider`; gọi `ActionDefinitionValidator` từ `AppDefinitionValidator`.
4. **C3** công bố cổng ghi (`mutationId`) và `callApi` → `ActionDataPort` adapter.
5. **C0/C4** `ActionAuditPort` adapter (`AuditService.record`, truyền `actorId` tường minh vì worker không có request) + `@Configuration` wiring + controller (T13 đầy đủ).
6. T13 đầy đủ: persistent run store + sweeper, RabbitMQ worker (message mang `tenantId` + `actorUserId`), retry/backoff/DLQ, rate limit tenant; sau đó Workflow/Scheduler.

## 11. Cách đã kiểm chứng và giới hạn của kiểm chứng

- Test đơn vị ở `backend/src/test/kotlin/com/systemwebstudio/logic/action/` (JUnit 5, không Spring/Testcontainers): 56 test — registry, validator, binder, run store (kể cả đồng thời), từng handler, mô hình lỗi, quyền, tenant, idempotency, timeout, audit fail-closed, dispatch.
- Môi trường làm việc **không chạy được** `./gradlew test` (máy chỉ có JDK 11 và không tải được Gradle/Maven Central); cách thay thế và kết quả cụ thể nằm trong báo cáo cuối task. Cần chạy lại `cd backend && ./gradlew test` trên máy có JDK 21 trước khi C0 merge.
- Rủi ro biên dịch còn lại khi lần đầu build bằng Jackson 3 thật: tên API `tools.jackson.databind.JsonNode` (`isString`, `asString`, `propertyNames`) đã bám theo cách repo đang dùng; phần ít chắc chắn nhất là `json.readTree("null")` trong một test.
