# DECISIONS — log quyết định chung

Thay đổi contract chung (`docs/contracts/**`), ranh giới ownership hoặc quy tắc cấp migration **phải** ghi ở đây trước khi sửa. Định dạng: ID · ngày · người đề xuất · trạng thái (PROPOSED / ACCEPTED / REJECTED) · nội dung · hệ quả.

## D-001 — Giữ Modular Monolith · ACCEPTED · 2026-10-05 · C0
Không chuyển microservices. Module mới là package mới trong `com.systemwebstudio`. Code cũ không bị di chuyển/đổi tên trong Phase 0.

## D-002 — Gán mặc định cho module legacy không có owner · PROPOSED · 2026-10-05 · C0
`member→C1`; `prompt, ai, integration/llm, asset→C2`; `publish, runtime, code, integration/{deploy,git,storage,queue,secrets}, audit, admin, settings, maintenance, common→C0-gated`. Cần chủ dự án xác nhận.

## D-003 — SSRF guard & connector hiện có nằm trong `runtime/Gateway.kt` · PROPOSED · 2026-10-05 · C0
`PublicAddress` (SSRF guard) và `ConnectorProxyController`/`AdminConnectorController` dùng chung file với `AppGatewayController`. Đề xuất: C3 **không sửa** file này; DataConnector mới gọi `PublicAddress.isPublic` (read-only). Nếu cần dùng chung sâu, C0 sẽ làm một task riêng trích `PublicAddress` sang `common/` (không đổi hành vi). Ngoài scope Phase 0.

## D-004 — Frontend ở root repo, không có `frontend/` · ACCEPTED · 2026-10-05 · C0
Ownership C5 ánh xạ: `frontend/features/**→features/**`, `frontend/lib/...→lib/...`. Không tạo thư mục `frontend/` và không di chuyển file.

## D-005 — API client frontend dùng chung · PROPOSED · 2026-10-05 · C0
`lib/http-api.ts` và `lib/http-types.ts` là hot file của C5. Agent khác thêm client mới ở `lib/api/<domain>.ts` (tạo mới, do agent đó sở hữu) hoặc yêu cầu C5.

## D-006 — Migration không liên tục · SUPERSEDED bởi D-007 · 2026-10-05 · C0
Quyết định ban đầu (phân vùng số migration cố định theo agent) đã bị thay thế; giữ lại làm lịch sử. Quy tắc hiện hành: D-007 và `OWNERSHIP.md §6`.

## D-007 — C0 cấp số Flyway migration · ACCEPTED · 2026-10-05 · C0
Agent không tự chọn Flyway migration version. Khi cần migration, agent ghi request vào `docs/parallel/BOARD.md` (mục *Migration requests*); C0 cấp next available version (V26, V27, …). Một version gắn với đúng một task. Migration được merge theo thứ tự tăng dần. Không bật `outOfOrder=true`. Chi tiết: `OWNERSHIP.md §6`.

## D-C4-01 — Danh sách ActionType chốt (đóng kín) · PROPOSED · 2026-10-05 · C4 · sửa 2026-10-06
Thay D-009. `NAVIGATE, REFRESH_QUERY, SUBMIT_FORM, CREATE_RECORD, UPDATE_RECORD, DELETE_RECORD, CALL_API, NOTIFY, START_WORKFLOW` — đúng 9 tên của contract v2, so khớp **chính xác**. **Sửa 2026-10-06: bỏ hoàn toàn bản đồ alias** (`RUN_QUERY`, `WRITE_DATA`, `CALL_CONNECTOR_OPERATION`, `SET_VALUE` và mọi tên khác bị từ chối; action đó không được cung cấp). `SET_VALUE` là trạng thái client, không có action phía server. Ghi dữ liệu = tham chiếu `queryRef` (query `mode=WRITE` đã khai báo trong AppDefinition, không SQL/URL/tên bảng); `CALL_API` = `dataSourceRef` + `operationKey` đã duyệt (không URL thô). `DOWNLOAD_FILE`/`OPEN_URL` chưa làm vì chưa có security model. Thêm loại mới = đổi contract, phải qua file này. Hệ quả: B-C4-02, B-C4-04.

## D-C4-02 — ActionContext của C4 + port thay cho AccessContext/ActionAuthorizer · PROPOSED · 2026-10-05 · C4
Thay D-008. `ActionRuntime.execute(ctx: ActionContext, req)`; `ActionContext(tenantId, actor, workspaceId?, projectId?, requestId?)` do server dựng (HTTP: từ `AccessContext`+`TenantContext`; worker: từ run đã lưu). Quyền đi qua `AccessPort.check(AccessRequest(permission, resourceKind, resourceId, appId, mode))`, tenant qua `TenantGate`, người nhận/approver qua `PrincipalResolver`; cả ba do C1 cấp adapter (B-C4-01). `logic.*` không import `access/**`. Hệ quả: contract `action-workflow.md` cần cập nhật chữ ký nếu đồng ý.

## D-C4-03 — Định nghĩa action/workflow đọc từ AppDefinition canonical · PROPOSED · 2026-10-05 · C4
Thay D-010. C4 **không** có bảng `action_definitions`; `ActionDefinition`/`WorkflowDefinition` được đọc từ JSON AppDefinition của C2 qua `AppDefinitionSource` (lớp `*.canonical`), theo tenant + app + mode (LIVE=bản publish, TEST=bản nháp). Tham chiếu không giải quyết được (query/dataSource/workflow/permission/action nối chuỗi) → action/workflow **không được cung cấp** (fail closed). C4 chỉ sở hữu bảng trạng thái chạy (B-C4-05).

## D-C4-04 — Mô hình thực thi workflow · PROPOSED · 2026-10-05 · C4
Trạng thái nằm trong DB (CAS trên dòng run), RabbitMQ chỉ mang tín hiệu `v1|jobId|tenantId|runId|stepId` (không actor/quyền/payload). Timer, retry/backoff, job mất, worker chết, approval bị lỡ callback do **sweeper** xử lý (không cần delayed delivery của broker). Poison/quá `maxDeliveries` → DLQ → run `FAILED/DEAD_LETTERED`. Run chạy như người tạo, quyền kiểm lại ở **từng step**. Định nghĩa được snapshot vào run. `start` không chặn HTTP.

## D-C4-05 — Phạm vi idempotency · PROPOSED · 2026-10-05 · C4
Action: `(tenant, app, action, user, key)`; workflow start: `(tenant, app, workflow, creator, mode, key)`; TEST và LIVE là hai phạm vi riêng. Key của step workflow: `wf:<runId>:<stepId>` (lượt thứ n≥2 của vòng lặp: `…:v<n>`). Write action bắt buộc `REQUIRED`; `NONE` trên action đổi state bị validator từ chối.

## D-C4-06 — Ngữ nghĩa TEST mode · PROPOSED · 2026-10-05 · C4
TEST không commit mutation, không gửi thông báo thật, không start workflow/approval thật, không tạo run state. Phản hồi là `WouldRun(level, plan, reason)` với `level` = `NOT_EXECUTED` (mặc định) / `VALIDATED` / `SANDBOX` — chỉ nâng cấp khi downstream có dry-run tường minh (`dryRunWrite/dryRunOperation`); không giả vờ. Plan chỉ chứa tên input, không chứa giá trị.

## D-C4-07 — Ngữ nghĩa Approval · PROPOSED · 2026-10-05 · C4
Approver được resolve và snapshot lúc tạo; người yêu cầu bị loại trừ mặc định; quorum `requiredApprovals`; một từ chối là từ chối cả yêu cầu; cross-tenant bị từ chối trừ khi chính sách của C1 cho phép (`CrossTenantApprovalPolicy`, mặc định DENY_ALL).

## D-C4-08 — Scheduler chỉ enqueue · PROPOSED · 2026-10-05 · C4
Scheduler không gọi action/connector/notification; tick claim bằng CAS rồi giao `ScheduledRunRequest` (key `sched:<id>:<fireEpochMilli>`) cho `ScheduledRunEnqueuer` (do `WorkflowEngine` implement). Misfire: `SKIP` hoặc `FIRE_ONCE`, không bao giờ bù từng lần bị lỡ. Run lịch chạy như owner của schedule.

## D-C4-09 — Notification là port, không vendor · PROPOSED · 2026-10-05 · C4
Nội dung = template đã duyệt (`templateRef`) + tham số có kiểu; webhook chỉ tới `endpointRef` đã đăng ký; vendor email/SMS là một `ChannelSender` do bên tích hợp viết. Giao hàng at-least-once khi có crash, trừ khi provider khử trùng theo `deliveryKey`.

## D-C4-10 — `ActionDef.trigger` là tùy chọn trên định nghĩa, bắt buộc chỉ với action gắn UI · PROPOSED · 2026-10-06 · C4
Runtime chấp nhận `trigger{sectionId, event}` **tùy chọn**. Run UI cấp cao nhất (`TriggerKind.UI_EVENT`, `callDepth == 0`) chỉ chạy action có `trigger` và tên event khớp; workflow step, schedule và action con trong chuỗi `onSuccess`/`onError` không cần `trigger`. `trigger` có mặt nhưng sai dạng → action không dùng được (fail closed). Khớp với `ActionDef.trigger` tùy chọn của C2. **Việc còn lại cho C0** (C4 không sửa C2 hay `docs/contracts/**`): `docs/contracts/v2/action-workflow.md` §2 và `app-definition.md` viết `trigger{sectionId, event}` không có `?` — cần ghi chú `trigger?` (tùy chọn; bắt buộc chỉ khi action được gắn vào một section UI) qua một mục DECISIONS của C0.

## D-C4-11 — Idempotency key dẫn xuất; key thô không rời ActionRuntime · PROPOSED · 2026-10-06 · C4
`derivedKey = base64url(sha256("<tenant>|<app>|<user>|<action>|<clientKey>"))` (43 ký tự, không padding). Chỉ key dẫn xuất được lưu (`action_runs.idempotency_key`), ghi log/audit (che bớt) và gửi cho C3/notification/workflow; retry dùng lại đúng key. `WriteRequest`/`OperationRequest` yêu cầu `appId`, `mode` và key dẫn xuất (validate trong `init`). Hệ quả: B-C4-04 (adapter C3 chuyển tiếp key dẫn xuất xuống connector).

## D-C4-12 — Một cửa dữ liệu: ActionRuntime → ActionDataPort → adapter C0 → DataGateway · PROPOSED · 2026-10-06 · C4
`logic.*` không import repository/connector/JDBC/HTTP client/`access|tenancy|data|app.*` (test kiến trúc quét import); chỉ `action.handlers` dùng `ActionDataPort`. Action ghi dữ liệu cần thêm quyền `DATA_MUTATE`. Chữ ký port ghi ở `FINAL-C4-runtime-design.md` §2. Adapter (C0 wiring + `AppDataBindingResolver`) tra `queryRef`/`dataSourceRef` theo `(tenant, app, mode)` rồi gọi DataGateway của C3.

## D-C4-13 — Cách ly poison/outage và sweeper công bằng · PROPOSED · 2026-10-06 · C4
Worker không bao giờ requeue ngay. Đã xử lý hoặc sự cố hạ tầng → `ack` (sweeper publish lại sau backoff, sàn 1s); message sai định dạng → DLQ; message làm worker ném exception → một lần lỗi của chính run (backoff mũ, trần 10 phút), sau `maxProcessFailures` (5) run `FAILED/DEAD_LETTERED` và **chỉ message đó** vào DLQ. Dead letter do broker → một lần lỗi, không giết run ngay. Sweeper: claim hợp nhất, cũ nhất đủ điều kiện trước, round-robin theo tenant, có chặn, một run tối đa một lần mỗi interval, run lỗi bị backoff. Hệ quả cho C0: adapter RabbitMQ không requeue từ phía worker; `delivery-limit` chỉ là lưới an toàn (B-C4-06).

## D-C4-14 — Rate limit theo tenant · PROPOSED · 2026-10-06 · C4
Port `TenantRateLimiter.tryAcquire(tenant, scope, cost)` với scope `ACTION_EXECUTE`, `WORKFLOW_START`, `SCHEDULER_ENQUEUE` (token bucket; `RateLimit(capacity, refillPerSecond)`, mặc định + override theo tenant). Kiểm sau authz, trước khi tạo trạng thái; vượt → `RATE_LIMITED` (retryable, `retryAfterMillis`); step workflow bị limit chờ rồi thử lại mà không tốn lượt retry; scheduler giữ fire đến hạn. Bản `InMemory` là theo từng node; limiter dùng chung toàn cụm là adapter của C0 (B-C4-08).

## D-C4-15 — Định danh thực thi của schedule và đăng ký idempotent · PROPOSED · 2026-10-06 · C4
Mỗi lần bắn có định danh duy nhất `(tenant, schedule, fireAt)` trong sổ `schedule_executions` (insert-if-absent trước khi enqueue) + key `sched:<id>:<fireEpochMilli>` + guard đơn điệu theo `lastRunAt`. Schedule do AppDefinition khai báo có `declaredKey = workflow:<id>` unique theo `(tenant, app)`; publish lại không tạo trùng, bỏ khai báo thì disable (không xoá). Enqueue lỗi tạm thời: backoff mũ + ngân sách bỏ cuộc tường minh.

## D-C4-16 — Retention cho dữ liệu chạy · PROPOSED · 2026-10-06 · C4
`RetentionService` xoá theo lô có chặn chỉ các hàng **đã kết thúc**; PENDING/RUNNING/WAITING, compensation đang chạy, approval PENDING, fire CLAIMED không bao giờ bị đụng (ràng buộc nằm trong hợp đồng store). Workflow run hai giai đoạn (làm trống payload sau 14 ngày, xoá sau 90 ngày); mặc định `action_runs` 30 ngày, `approvals` 180 ngày, ledger 30 ngày; tối thiểu 7 ngày vì xoá hàng chấm dứt khử trùng key. Cột/chỉ mục cần có trong yêu cầu migration gộp (BOARD.md); không cấp số Flyway.
