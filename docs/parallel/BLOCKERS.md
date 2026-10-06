# BLOCKERS — dependency & blocker giữa các agent

Thêm dòng khi bạn cần thay đổi file của owner khác hoặc bị chặn. Owner/C0 đóng dòng khi xử lý. Không tự sửa file của owner khác.

Loại: `needs-hot-file` · `needs-shared-file` · `needs-contract-change` · `needs-migration` · `external`.

| ID | Ngày | Từ | Cần từ | Loại | Mô tả | Trạng thái |
|---|---|---|---|---|---|---|
| B-001 | 2026-10-05 | C0 | C0 | needs-migration | Risk: migration version coordination is centralized through C0. Flyway không bật `outOfOrder`, nên version phải được C0 cấp tuần tự (request ở `BOARD.md`), gắn một task, và merge theo thứ tự tăng dần; không bật `outOfOrder=true`. Vấn đề phân vùng số cũ đã được thay thế (D-006 → D-007). | MITIGATED |
| B-002 | 2026-10-05 | C3 | C0 | needs-shared-file | Connector proxy/SSRF guard nằm trong `runtime/Gateway.kt` (xem D-003). | OPEN |
| B-003 | 2026-10-05 | C1–C5 | C0 | needs-shared-file | Backend test dùng `support/IntegrationTestBase.kt` (Testcontainers) dùng chung; mở rộng → xin C0. | OPEN |
| B-C0-WEB-01 | 2026-10-06 | C0 | C0/C5 | needs-contract-change | OIDC login uses a single registered redirect/callback URI, so only one of the three frontend origins can complete SSO until per-origin redirect handling (or one shared callback host) is designed. Documented in `WEB_SECURITY_CONFIG.md` §4; not implemented. | OPEN |
| B-C0-WEB-02 | 2026-10-06 | C0 | Mac | external | Applied-but-unverified C0 patches (PublicAddress, SecurityConfiguration webhook/CORS, AdminController self-grant, WebOrigins, 17 new tests) need the first real Gradle compile/test (`MAC_INTEGRATION_CHECKLIST.md` S1, S16). Closes B-C1-13/14, B-C3-04/06/07, B-C4-10 as "applied, pending Mac verification" until then. | OPEN |
