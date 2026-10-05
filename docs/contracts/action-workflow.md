# Contract — ActionRuntime & WorkflowRuntime

Owner: **C4**. Trạng thái: DESIGN. Package mới: `com.systemwebstudio.logic.{action,workflow,scheduler}`.

## Hiện trạng
- Publish pipeline dùng RabbitMQ + worker `@RabbitListener` + CAS transitions + sweeper (`publish/**`, `integration/queue/JobQueue.kt`) — **không sửa**, chỉ tham khảo cách làm (idempotency, retry/DLQ).
- "Workflow" hiện tại chỉ là scaffold server-app (`resources/scaffolds/variants/workflow`), không phải runtime của nền tảng. Chưa có `logic/**`.

## Luồng
```
UI → AppDefinition (ActionRef) → Action → Auth + Permission → Data Gateway → Connector → hệ thống ngoài
Workflow = chuỗi/đồ thị Action có điều kiện, chạy bất đồng bộ (RabbitMQ) hoặc theo lịch
```
Action/Workflow **không** truy cập dữ liệu trực tiếp; mọi đọc/ghi qua `DataGateway` (`data-connector.md`).

## Shape (thiết kế)
```kotlin
interface ActionRuntime {
    fun execute(ctx: AccessContext, tenant: TenantContext, req: ActionRequest): ActionResult
}
data class ActionRequest(
    val actionId: String, val inputs: Map<String, JsonNode>,
    val idempotencyKey: String?, val trigger: TriggerInfo      // UI_EVENT | WORKFLOW_STEP | SCHEDULE
)
sealed interface ActionResult { data class Ok(val output: JsonNode): ActionResult; data class Failed(val code: String, val retryable: Boolean): ActionResult }

interface WorkflowRuntime {
    fun start(ctx: AccessContext, tenant: TenantContext, workflowId: String, input: JsonNode, idempotencyKey: String): WorkflowRunId
    fun status(ctx: AccessContext, runId: WorkflowRunId): WorkflowRunStatus
    fun cancel(ctx: AccessContext, runId: WorkflowRunId)
}
```
Action loại khai báo (không code tùy ý): `RUN_QUERY`, `WRITE_DATA` (qua Gateway), `CALL_CONNECTOR_OPERATION`, `NOTIFY`, `SET_VALUE`, … — danh sách do C4 chốt qua `DECISIONS.md`.

## Quy tắc
1. Mọi lần chạy kiểm `AccessContext.require(ACTION_EXECUTE|WORKFLOW_MANAGE)` và tenant; message RabbitMQ mang `tenantId` + `actorUserId`, worker dựng lại context (không tin payload quyền).
2. Idempotent: `Idempotency-Key` như publish pipeline; chuyển trạng thái bằng compare-and-set; retry với backoff → DLQ; sweeper cho run treo.
3. Giới hạn: timeout, số bước, độ sâu, rate limit theo tenant. Không thực thi code do AI/người dùng cung cấp trên JVM.
4. Audit mọi run (bắt đầu/kết thúc/lỗi) qua `AuditService` (append-only).
5. Scheduler: cron khai báo trong định nghĩa, chạy một lần mỗi lịch (khóa phân tán qua DB/Redis), tôn trọng tenant bị disable.

Migration: không tự chọn version; ghi request vào `docs/parallel/BOARD.md`, C0 cấp version (xem `OWNERSHIP.md §6`). Phụ thuộc: C1 (tenant, permission), C3 (`DataGateway`), C2 (`ActionRef` trong AppDefinition).
