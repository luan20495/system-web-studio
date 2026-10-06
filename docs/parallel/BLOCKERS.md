# BLOCKERS — dependency & blocker giữa các agent

Thêm dòng khi bạn cần thay đổi file của owner khác hoặc bị chặn. Owner/C0 đóng dòng khi xử lý. Không tự sửa file của owner khác.

Loại: `needs-hot-file` · `needs-shared-file` · `needs-contract-change` · `needs-migration` · `external`.

| ID | Ngày | Từ | Cần từ | Loại | Mô tả | Trạng thái |
|---|---|---|---|---|---|---|
| B-001 | 2026-10-05 | C0 | C0 | needs-migration | Risk: migration version coordination is centralized through C0. Flyway không bật `outOfOrder`, nên version phải được C0 cấp tuần tự (request ở `BOARD.md`), gắn một task, và merge theo thứ tự tăng dần; không bật `outOfOrder=true`. Vấn đề phân vùng số cũ đã được thay thế (D-006 → D-007). | MITIGATED |
| B-002 | 2026-10-05 | C3 | C0 | needs-shared-file | Connector proxy/SSRF guard nằm trong `runtime/Gateway.kt` (xem D-003). | OPEN |
| B-003 | 2026-10-05 | C1–C5 | C0 | needs-shared-file | Backend test dùng `support/IntegrationTestBase.kt` (Testcontainers) dùng chung; mở rộng → xin C0. | OPEN |
| B-C5-01 | 2026-10-05 | C5 | C2 / C0 | needs-contract-change | `app-definition-v2.md`: (G1) `AppDefinitionV2.pages: List<PageDef>` lệch Page Schema thật (trang chủ = `sections` gốc, `pages` = trang phụ); (G2) `AppKind` (PAGE_SCHEMA) trùng tên `AppKind` DB/frontend (WEBSITE_STATIC…WORKFLOW) và khác `appType`. Cần shape gốc + tên không trùng + endpoint đọc/ghi (reuse `/schema`?) và `schemaVersion`. Chi tiết: `audit/PREP-T12-builder-architecture.md` §3 G1-G2, §7 #1-2. | OPEN |
| B-C5-02 | 2026-10-05 | C5 | C2 / C3 / C0 | needs-contract-change | Thiếu thực thể và HTTP contract để UI liệt kê/chọn: DataSource, Query (+khai báo tham số), ViewModel; chưa rõ Query nằm trong AppDefinition hay Data Platform (G3); `FieldMapping`, `ViewModelData`, `PageSpec`, `Column` chưa có shape (G4); chưa có đường dẫn HTTP cho query list/run, action execute, workflow start/status/cancel (G9); mã lỗi ổn định (timeout, truncated, rate limit). Xem audit §3, §7 #6-12,14,16,19. | OPEN |
| B-C5-03 | 2026-10-05 | C5 | C2 (+C0 cấp migration) | needs-contract-change | Component registry chưa có metadata prop gắn được dữ liệu / event phát ra (G6) và chưa có component DataTable/DataList/KPI; seed registry là migration (V5…) nên cần C0 cấp số khi C2 yêu cầu. Renderer + nhãn Inspector phía C5 đổi cùng lúc. | OPEN |
| B-C5-04 | 2026-10-05 | C5 | C4 | needs-contract-change | `ActionRequest`/`TriggerInfo` không có `dryRun`/`TEST` (G8): Test mode không thể chạy Action có tác dụng phụ an toàn. Đến khi có, UI vô hiệu Action ở Test. | OPEN |
| B-C5-05 | 2026-10-05 | C5 | C1 | needs-hot-file | `Permission.kt`: cần các permission dự kiến trong `permission-model.md` (`QUERY_EXECUTE`, `ACTION_EXECUTE`, `WORKFLOW_MANAGE`, `DATASOURCE_READ`) xuất hiện trong `ApiProject.permissions` để UI ẩn/hiện Test và panel; C5 chỉ đọc chuỗi quyền. | OPEN |
| B-C5-06 | 2026-10-05 | C5 | C0 | needs-shared-file | "Publish cùng dữ liệu": `workers/render/server.ts` (`/render-site` chỉ nhận `{schema, assets}`) và `publish/**` (DeploymentProcessor) là C0-gated; cần quyết định D-C5-03 trước khi sửa. | OPEN |
| B-C5-07 | 2026-10-05 | C5 | C2 | needs-contract-change | `template/**`, `component/**`: lưu Template/Block COMPANY phải loại `dataBindings`/`actions` (ID thuộc tenant) và `restore` version cũ có thể trỏ query đã xoá → 422 (G10). | OPEN |
| B-C5-08 | 2026-10-06 | C5 | C0 / C1 | external | Chạy 3 origin (platform/admin/studio) cần cấu hình OIDC redirect URI, CORS/cookie, CSP `frame-ancestors`/link giữa origin ở reverse proxy; C5 chỉ cung cấp `NEXT_PUBLIC_PORTAL_URL_*` (xem `agents/C5_PHASE2_PORTALS.md`). Chưa kiểm chứng end-to-end. | OPEN |
