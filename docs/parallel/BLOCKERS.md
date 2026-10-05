# BLOCKERS — dependency & blocker giữa các agent

Thêm dòng khi bạn cần thay đổi file của owner khác hoặc bị chặn. Owner/C0 đóng dòng khi xử lý. Không tự sửa file của owner khác.

Loại: `needs-hot-file` · `needs-shared-file` · `needs-contract-change` · `needs-migration` · `external`.

| ID | Ngày | Từ | Cần từ | Loại | Mô tả | Trạng thái |
|---|---|---|---|---|---|---|
| B-001 | 2026-10-05 | C0 | C0 | needs-migration | Flyway không bật `outOfOrder`. Các range V26–V59 gián đoạn: nếu DB dev đã áp dụng V35 rồi nhánh khác merge V30, Flyway sẽ báo lỗi validate. Merge theo thứ tự tăng dần hoặc dùng DB dev mới; không tự bật `outOfOrder`. | OPEN |
| B-002 | 2026-10-05 | C3 | C0 | needs-shared-file | Connector proxy/SSRF guard nằm trong `runtime/Gateway.kt` (xem D-003). | OPEN |
| B-003 | 2026-10-05 | C1–C5 | C0 | needs-shared-file | Backend test dùng `support/IntegrationTestBase.kt` (Testcontainers) dùng chung; mở rộng → xin C0. | OPEN |
