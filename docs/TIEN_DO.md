# Tiến độ AI Software Factory — đã làm và chưa làm

Cập nhật: 03/10/2026 · Nhánh `feat/production-hardening` (commit cuối `0ea5904`, 32 commit so với `main`) · **Chưa push lên GitHub.**
Chi tiết kỹ thuật: [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md), thiết kế Phase 7: [SOFTWARE_FACTORY_DESIGN.md](SOFTWARE_FACTORY_DESIGN.md), quyết định: [adr/](adr/).

## Tổng quan theo phase

| Phase | Nội dung | Trạng thái |
| --- | --- | --- |
| 1 | Đăng nhập + khung sản phẩm (chọn cổng Admin/Builder, các màn auth, deep link) | ✅ Xong |
| 2 | Admin Console MVP (tổng quan, người dùng, workspace, ứng dụng, audit, sức khỏe, cài đặt) | ✅ Xong |
| 3 | Thiết kế lại Builder Studio (Home, Projects, AI, Design, Code honest, tệp, thành viên, phiên bản, xuất bản) | ✅ Xong |
| 4 | Đo token/chi phí AI, hạn mức token, báo cáo AI cho admin | ✅ Xong |
| 5 | Thư viện mẫu (template) + khối đóng góp có quy trình duyệt | ✅ Xong |
| 6 | Nhiều nhà cung cấp AI (OpenAI, Anthropic, Gemini, model nội bộ) + bảng giá | ✅ Xong (chỉ thử với server giả) |
| 7.1 | Website tĩnh thật tại `sites.toolsmcp.uk/<slug>/` | ✅ Xong, đang chạy public |
| 7.2 | Kho Git (Forgejo) cho ứng dụng mã nguồn | ✅ Xong ở local, chưa bật public |
| 7.3 | Build trong sandbox (Docker) + quét bảo mật | ✅ Xong ở local, chưa bật public |
| 7.4 | AI sinh mã + chế độ Code | ✅ Xong ở local, chưa bật public |
| 7.5 | Ứng dụng có máy chủ (API, database, connector) | ❌ Chưa làm — cần thiết kế và duyệt riêng |

## Đã làm

### Đăng nhập và phân quyền
- Màn đăng nhập chọn cổng **Admin Console** / **Builder Studio**; lựa chọn chỉ là điều hướng, quyền do backend quyết định.
- Mật khẩu (Argon2id, chặn dò mật khẩu), SSO/OIDC (đã thử với Keycloak), session Redis có thể thu hồi, CSRF.
- Màn: đang đăng nhập, không có quyền, chưa có workspace, hết phiên (quay lại đúng trang cũ).
- Đăng ký công khai: có, mặc định tắt (`PUBLIC_SIGNUP_ENABLED`).

### Admin Console
- Tổng quan với số liệu thật; thẻ "AI tháng này".
- Người dùng: tìm, xem chi tiết, khóa/mở khóa, thu hồi phiên (không tự khóa mình, không khóa system admin cuối cùng).
- Workspace; danh mục ứng dụng toàn công ty (thành viên, phiên bản, hoạt động AI, lịch sử xuất bản, audit); chuyển chủ sở hữu, xóa, khôi phục phiên bản.
- AI Control: nhà cung cấp, bật/tắt từng model, bảng giá, kiểm tra kết nối, báo cáo token/chi phí theo model/người/workspace/ngày, nhật ký từng lượt gọi.
- Components: registry đã duyệt + hàng chờ duyệt khối đóng góp. Templates: chia sẻ mẫu toàn công ty, lưu trữ.
- Audit toàn hệ thống có bộ lọc; sức khỏe hệ thống (kiểm tra thật PostgreSQL, Redis, RabbitMQ, MinIO, OpenRouter, OIDC); cài đặt (chỉ xem).

### Builder Studio — Website (dạng trang)
- Home có ô mô tả lớn, danh sách ứng dụng phân trang (của tôi / được chia sẻ), hoạt động, quota AI và token.
- Chế độ **AI** (chat, hiện model, phiên bản, component thay đổi, token/chi phí) và **Design** (cấu trúc trang, kéo thả, thư viện component và khối, bấm chọn trong preview, inspector, ảnh `asset://`).
- Tệp, thành viên & vai trò (Owner/Editor/Publisher/Viewer), lịch sử phiên bản & khôi phục, cài đặt.
- Mẫu: lưu trang thành mẫu, tạo website từ mẫu. Khối: lưu một mục thành khối, gửi duyệt, chèn khối đã duyệt.
- **Xuất bản thật** (7.1): artifact bất biến, trang công khai hoặc riêng tư (đăng nhập công ty + là thành viên), phục vụ lại bản cũ, gỡ trang.

### Builder Studio — Ứng dụng web (mã nguồn) — chỉ ở local
- Tạo ứng dụng React + Vite; kho Git riêng thuộc nền tảng, nhánh `main` được khóa.
- Chế độ **AI**: AI (hoặc bộ mô phỏng khi chưa có key) sửa mã; chế độ **Code**: cây file + trình sửa (cấu hình/thư viện chỉ đọc).
- Mỗi thay đổi = 1 commit trên nhánh riêng → build trong sandbox (không root, không mạng khi build, chỉ cài gói từ mirror đã duyệt) → quét secret + lỗ hổng (OSV) + SBOM → preview cách ly → hợp nhất → xuất bản công khai.

### AI
- OpenRouter (model miễn phí, tự động thử model khác khi lỗi) + bộ mô phỏng.
- OpenAI, Anthropic, Gemini, model nội bộ: model mặc định **tắt** đến khi admin bật; "tự động" không bao giờ dùng model trả phí.
- Ghi từng lượt gọi model với token/chi phí do nhà cung cấp báo (không ước lượng); chi phí theo bảng giá admin nhập; hạn mức token theo người/ngày và workspace/tháng (mặc định tắt).

### Vận hành
- Chạy local: `./scripts/run-local.sh`; public qua tunnel riêng `hbl-studio`: `./scripts/public-up.sh` (`studio.`, `studio-files.`, `sites.toolsmcp.uk`).
- Sao lưu PostgreSQL/MinIO, script khôi phục; đã sao lưu DB public trước mỗi lần migrate.
- Test: backend 136 test (0 lỗi), E2E factory 34/34, code 9/9, SSO 9/9, providers 6/6, a11y 30/30 màn, pages-mock 6/6, public 12/13 (lỗi thứ 13 đã sửa).

## Chưa làm

### Cần bạn quyết định
- [ ] Bật ứng dụng mã nguồn trên bản public? (đăng ký đang mở → ai cũng chạy được build trên máy Mac này)
- [ ] Tắt `SIGNUP_ENABLED` trên bản public?
- [ ] Push các commit lên GitHub?
- [ ] Làm Phase 7.5 (ứng dụng có máy chủ)?
- [ ] Nhập key OpenRouter / OpenAI / Anthropic / Gemini để thử AI thật (hiện chưa gọi thật nhà cung cấp nào).

### Tính năng còn thiếu
- **Đăng nhập:** SAML, SCIM; đăng xuất phía IdP; xác thực 2 lớp trong app (hiện dựa vào IdP); màn tự đổi mật khẩu.
- **Admin:** mô hình phòng ban/HR; lưu trữ (archive) ứng dụng; chi phí hosting, điểm rủi ro bảo mật; sửa cài đặt từ giao diện.
- **AI:** phân quyền model theo workspace/vai trò; ngân sách bằng tiền và cảnh báo ngưỡng; streaming, tool calling; AI chưa dùng khối đóng góp.
- **Website:** nhiều trang; form liên hệ gửi được; tên miền riêng; cache ở CDN; dọn artifact cũ.
- **Mẫu & khối:** danh mục/ảnh chụp mẫu, quy trình duyệt mẫu; đếm số lần dùng khối; thêm loại component gốc mới (phải viết renderer trong code — có chủ đích).
- **Ứng dụng mã nguồn:** ứng dụng riêng tư; cho phép thêm thư viện; gói UI công ty cho code; Design mode cho code; truy cập từ IDE ngoài; lưu trữ repo khi xóa project; hạn mức số lần build; cách ly mạnh hơn (gVisor/microVM — cần máy Linux); ký commit của bot; duyệt trước khi hợp nhất.
- **Loại ứng dụng:** Dashboard, Internal Tool, Workflow (cần 7.5).
- **Hạ tầng:** chỉ một máy (không HA), không tự khởi động lại sau khi máy reboot, sao lưu chưa chạy theo lịch.

## Lưu ý bảo mật đã ghi nhận
Xem [SECURITY.md](SECURITY.md) mục "Findings during Phase 7": rò rỉ qua cache khi đổi riêng tư (đã sửa), Cloudflare chèn script (đã sửa),
`rollup@4.64.0` đáng ngờ (đã ghim bản khác; từng chạy 1 lần ngoài sandbox khi chẩn đoán), script dừng local làm tắt Docker Desktop (đã sửa).
