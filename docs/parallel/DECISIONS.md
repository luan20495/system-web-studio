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

## D-008 — ActionRuntime nhận `ActionContext` của C4, không nhận `AccessContext`+`TenantContext` · PROPOSED · 2026-10-05 · C4
Contract `action-workflow.md` ghi `execute(ctx: AccessContext, tenant: TenantContext, req)`. Đề xuất: `ActionRuntime.execute(ctx: ActionContext, req: ActionRequest)` với `ActionContext(tenantId, actor, workspaceId?, projectId?, requestId?)` do C4 sở hữu, dựng ở adapter từ `AccessContext`+`TenantContext` (HTTP) hoặc từ message queue (`tenantId`+`actorUserId`). Lý do: `logic.action` không import `access/**` (C1) nên biên dịch/test độc lập; context tường minh phù hợp thực thi bất đồng bộ (không ThreadLocal). Quyền vẫn do C1 quyết qua `ActionAuthorizer`. Hệ quả: C0 cập nhật contract nếu đồng ý; không đổi hành vi hiện có.

## D-009 — Danh sách ActionType chốt cho T13 · PROPOSED · 2026-10-05 · C4
`NAVIGATE, SUBMIT_FORM, CREATE_RECORD, UPDATE_RECORD, DELETE_RECORD, CALL_API, NOTIFY, START_WORKFLOW` (thay danh sách minh hoạ `RUN_QUERY/WRITE_DATA/CALL_CONNECTOR_OPERATION/SET_VALUE` trong contract). Đọc dữ liệu **không** là Action: UI đi qua `DataBinding → queryId → DataGateway.runQuery`. Ghi dữ liệu là tham chiếu `mutationId` đã khai báo (không SQL/URL/tên bảng). Thêm loại mới = đổi contract, phải qua file này. Hệ quả: C3 cần định nghĩa mutation đã khai báo (B-006).

## D-010 — Nơi lưu ActionDefinition và run state · PROPOSED · 2026-10-05 · C4
Câu hỏi cho C0/C2: `ActionDefinition` (có `tenantId`, inputs, config tham chiếu id, limits) được (a) lưu trong bảng riêng của C4 `action_definitions` (cần migration) hay (b) nằm trong `AppDefinitionV2.extensions` do C2 sở hữu và C4 chỉ đọc qua `ActionDefinitionProvider`. Khuyến nghị của C4: (a) cho action dùng chung nhiều app/workflow, (b) nếu action luôn gắn một app. Run state/idempotency (`action_runs`) luôn là bảng của C4. Chưa có migration; PREP chỉ dùng in-memory/fake.
