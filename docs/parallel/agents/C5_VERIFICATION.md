# C5 — Verification record (Phase 1 + 2), 2026-10-06

Môi trường: **Linux container (x86_64, Node 22, Chromium 1194)**, KHÔNG phải macOS. Phiên làm việc không có shell trên Mac; `device_bash` là Linux VM và trình duyệt trên Mac không với tới server trong VM.
Backend Kotlin không build được ở đó (Maven Central / Gradle plugin portal bị egress chặn, không có Docker) nên **không có backend thật, không có E2E đăng nhập**. Không dùng backend giả.

| Hạng mục | Kết quả |
|---|---|
| `npm ci`, `typecheck:all`, `tsc` root, `typecheck:apps` | PASS |
| `npm run test:unit` | 101/101 PASS |
| build platform / admin / studio / root mock / root http, `tsc` render worker | PASS |
| 3 portal `next start -H 127.0.0.1` (3001/3002/3003), Chromium thật (`tests/browser/portals.spec.mjs`) | 33/33 PASS: redirect `/login?next=`, nhãn form, CSP (nonce + strict-dynamic, frame-ancestors none), header, không cookie, lỗi đăng nhập khi API chết |
| Builder trong Chromium thật (`tests/browser/builder.spec.mjs`, harness test-only, KHÔNG phải backend E2E) | 64/64 PASS: pointer DnD, indicator, reorder, chọn, inspector, bàn phím, dialog/tab/Esc/focus, tên truy cập, trang/menu/404/preflight, Action/Mapping/Query (V2 editors) |

Lỗi tìm thấy bằng browser thật và đã sửa (`59347e8`): tay nắm kéo section đè lên iframe sandbox khiến 15–45% lần nhấn không bắt đầu kéo (pointerdown vào iframe); tay nắm trong suốt; nút Lên/Xuống của Footer bật nhưng vô tác dụng.

KHÔNG kiểm chứng được (cần backend C0): đăng nhập/phiên/cookie sau đăng nhập, chuyển portal, OIDC redirect/callback, CORS và chia sẻ phiên giữa 3 origin, 409/422 từ server thật, Data Source/Query preview/Action/Workflow runtime, Test Mode, Share, Publish, Rollback. Cần chạy lại trên Mac: `npm ci`, các build, 2 spec browser với `CHROME=...` (xem `tests/browser/README.md`).
