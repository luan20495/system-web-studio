# Tiến độ AI Software Factory — đã làm và chưa làm (cập nhật 2026-10-04)

Chi tiết từng mục (REAL / PARTIAL / MOCK / BLOCKED_EXTERNAL_INPUT / NOT IMPLEMENTED): [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).
Toàn bộ nằm trên nhánh `feat/production-hardening` (chưa hợp nhất vào `main`).

## Đã làm (lộ trình A–N)
| Giai đoạn | Nội dung | Trạng thái |
|---|---|---|
| A | Khoá môi trường: tắt tự đăng ký, cài đặt chỉnh được có audit (mục rủi ro cao phải xác nhận) | Xong, đang áp dụng trên pilot |
| B | Hạn mức build/lưu trữ, dọn artifact, vòng đời kho mã | Xong |
| C | Rà soát bảo mật máy (sự cố rollup 4.64.0) | Xong, ghi trong SECURITY.md |
| D | Ứng dụng mã nguồn: danh mục package duyệt, `@company/ui`, `@company/app-sdk`, Design qua AST, review trước merge, IDE chỉ đọc, commit có chữ ký | Xong (local) |
| E | Quản trị AI: quyền dùng model, ngân sách tiền, cảnh báo, streaming có huỷ, tool calling có audit, ưu tiên tái sử dụng | Xong (thử với stub) |
| F | Template/khối: quy trình duyệt, danh mục, thẻ, ảnh xem trước an toàn, số lần dùng | Xong |
| G | Website nhiều trang, điều hướng, SEO, trang 404, form thật, tên miền riêng, cache CDN | Xong |
| H | Admin: phòng ban, lưu trữ ứng dụng, chi phí hosting từ số đo thật, phát hiện bảo mật | Xong |
| I | Định danh: đăng xuất ở IdP, SAML qua Keycloak, SCIM 2.0, MFA do IdP | Xong (SAML gốc chưa làm) |
| J | Ứng dụng có máy chủ: container cô lập, mạng riêng mỗi app, CSDL riêng mỗi app, bí mật chỉ ghi, connector, blue/green + rollback | Xong (local, Docker Desktop) |
| K | Dashboard / Công cụ nội bộ / Workflow | Xong (local) |
| L | Sao lưu hằng ngày, diễn tập khôi phục, giám sát sao lưu, tự khởi động lại, profile LEAN/MEDIUM/FULL | Xong |
| M | Kiểm tra bảo mật: quét secret, OSV (đã vá Tomcat/Jackson/RabbitMQ/BouncyCastle), soát mã độc lập (1 HIGH, 1 MEDIUM, 8 LOW — đã sửa hết) | Xong |
| N | Hồi quy: backend 185 test; E2E factory 34/34, code 14/14, runtime 8/8, a11y 41/41, providers 6/6, pages 6/6, SSO 11/11, public 13/13 | Xong |

## Chưa làm / cần bạn quyết định
* **Khoá API nhà cung cấp AI** (OpenRouter/OpenAI/Anthropic/Gemini): chưa có → AI thật chưa chạy (BLOCKED_EXTERNAL_INPUT).
* **Máy chủ Linux cho runtime** (gVisor): cần có thì mới bật ứng dụng có máy chủ cho pilot.
* **Bản sao lưu ngoài máy** (S3/MinIO khác hoặc thư mục đồng bộ): hiện sao lưu nằm trên chính máy Mac này.
* **SAML gốc**: đã quyết định KHÔNG thêm (ADR 0021) — giữ OIDC + SAML qua broker; chỉ làm lại nếu có yêu cầu mà broker không đáp ứng được.
* **Tên miền riêng trên pilot**: cần DNS của bạn và route tunnel/CDN cho từng tên miền.
* **Bật ứng dụng mã nguồn trên pilot**: cần Forgejo + runner trên môi trường public (build chạy trên máy này).
* **Hợp nhất vào `main`**: chờ bạn duyệt.
