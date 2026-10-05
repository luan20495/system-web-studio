# BLOCKERS — dependency & blocker giữa các agent

Thêm dòng khi bạn cần thay đổi file của owner khác hoặc bị chặn. Owner/C0 đóng dòng khi xử lý. Không tự sửa file của owner khác.

Loại: `needs-hot-file` · `needs-shared-file` · `needs-contract-change` · `needs-migration` · `external`.

| ID | Ngày | Từ | Cần từ | Loại | Mô tả | Trạng thái |
|---|---|---|---|---|---|---|
| B-001 | 2026-10-05 | C0 | C0 | needs-migration | Risk: migration version coordination is centralized through C0. Flyway không bật `outOfOrder`, nên version phải được C0 cấp tuần tự (request ở `BOARD.md`), gắn một task, và merge theo thứ tự tăng dần; không bật `outOfOrder=true`. Vấn đề phân vùng số cũ đã được thay thế (D-006 → D-007). | MITIGATED |
| B-002 | 2026-10-05 | C3 | C0 | needs-shared-file | Connector proxy/SSRF guard nằm trong `runtime/Gateway.kt` (xem D-003). | OPEN |
| B-003 | 2026-10-05 | C1–C5 | C0 | needs-shared-file | Backend test dùng `support/IntegrationTestBase.kt` (Testcontainers) dùng chung; mở rộng → xin C0. | OPEN |
