# BLOCKERS — dependency & blocker giữa các agent

Thêm dòng khi bạn cần thay đổi file của owner khác hoặc bị chặn. Owner/C0 đóng dòng khi xử lý. Không tự sửa file của owner khác.

Loại: `needs-hot-file` · `needs-shared-file` · `needs-contract-change` · `needs-migration` · `external`.

| ID | Ngày | Từ | Cần từ | Loại | Mô tả | Trạng thái |
|---|---|---|---|---|---|---|
| B-001 | 2026-10-05 | C0 | C0 | needs-migration | Risk: migration version coordination is centralized through C0. Flyway không bật `outOfOrder`, nên version phải được C0 cấp tuần tự (request ở `BOARD.md`), gắn một task, và merge theo thứ tự tăng dần; không bật `outOfOrder=true`. Vấn đề phân vùng số cũ đã được thay thế (D-006 → D-007). | MITIGATED |
| B-002 | 2026-10-05 | C3 | C0 | needs-shared-file | Connector proxy/SSRF guard nằm trong `runtime/Gateway.kt` (xem D-003). | OPEN |
| B-003 | 2026-10-05 | C1–C5 | C0 | needs-shared-file | Backend test dùng `support/IntegrationTestBase.kt` (Testcontainers) dùng chung; mở rộng → xin C0. | OPEN |
| B-004 | 2026-10-05 | C4 | C1 | needs-hot-file | Cần permission `ACTION_EXECUTE` (+ `WORKFLOW_MANAGE`), `ResourceType.ACTION`, `AccessService.forResource`, và `TenantContext`/`ActorKind` đã công bố để viết `ActionAuthorizer` + factory dựng `ActionContext` từ `AccessContext`+`TenantContext`. C4 không sửa `access/**`. Port: `ActionAuthorizer`. | OPEN |
| B-005 | 2026-10-05 | C4 | C2 | needs-contract-change | Chốt shape `ActionRef` (C4 đang phản chiếu `ActionRef(id, trigger, actionId)`), nguồn `ActionBindingPort` (đọc `actions[]` của version đang chạy) và nơi lưu `ActionDefinition` (C2 `extensions` hay bảng của C4 — xem D-010). `AppDefinitionValidator` nên gọi `ActionDefinitionValidator.validate` qua adapter. | OPEN |
| B-006 | 2026-10-05 | C4 | C3 | needs-contract-change | `data-connector.md` chưa có phía ghi. C4 cần C3 xác nhận khái niệm **mutation đã khai báo** (`mutationId`, đối xứng `queryId`) và cổng `DataGateway` cho ghi, cùng `callApi(operationId)`; adapter `ActionDataPort` tự kiểm quyền/tenant/SSRF/limits và forward `idempotencyKey`. C4 không import/đoán signature của C3. | OPEN |
| B-007 | 2026-10-05 | C4 | C0 | needs-migration | Request số migration cho `action_runs` (+ `action_definitions` nếu D-010 chọn lưu riêng). Đã ghi ở `BOARD.md` (Migration requests). Chưa tạo file migration. | OPEN |
| B-008 | 2026-10-05 | C4 | C0 | needs-shared-file | Adapter `ActionAuditPort` → `AuditService.record` (audit/** C0-gated; worker không có request nên phải truyền `actorId` tường minh), `@Configuration` wiring Spring cho `DefaultActionRuntime`, và key cấu hình giới hạn (`application*.yml`) khi tích hợp T13. Chưa cần ở PREP. | OPEN |
