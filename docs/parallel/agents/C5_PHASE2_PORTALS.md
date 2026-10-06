# C5 — Phase 2: cấu hình portal (platform / admin / studio)

Phạm vi: frontend. Không đổi contract, không thêm permission code. Tài liệu này thuộc C5.

## 1. Link giữa các portal không còn viết cứng

| Cần | Dùng | Ví dụ |
|---|---|---|
| Điều hướng **trong** app hiện tại (`<Link>`, `router.push`) | `portalPath(portal, path)` (`@xweb/permissions`); Studio dùng `S()`/`projectBase()` (`features/studio/base.ts`), Admin/Platform dùng `A()` (`features/admin/base.ts`) | `S("/projects/1")` → `/studio/projects/1` |
| Link **sang** portal khác | `portalHref(portal, path)` | `portalHref("admin")` → `https://admin.example.com/admin` khi đã cấu hình origin |
| Biết path thuộc portal nào / `next` an toàn | `portalOfPath`, `safeNext` | `portalOfPath("/studio/x")` → `studio` |

Tiền tố (`/platform`, `/admin`, `/studio`) chỉ khai báo một chỗ: `PORTAL_PREFIX`. Đường `/admin/...` trong `packages/api-client/src/api.ts` là **endpoint REST của backend**, không phải route UI nên giữ nguyên.

## 2. Cấu hình origin

Đặt lúc **build** (Next chỉ inline `NEXT_PUBLIC_*` thấy được trong source):

| Biến | Ý nghĩa | Mặc định |
|---|---|---|
| `NEXT_PUBLIC_PORTAL_URL_PLATFORM` | Origin Xweb Platform, vd `https://platform.example.com` | rỗng = cùng origin |
| `NEXT_PUBLIC_PORTAL_URL_ADMIN` | Origin Admin Console | rỗng = cùng origin |
| `NEXT_PUBLIC_PORTAL_URL_STUDIO` | Origin Studio | rỗng = cùng origin |
| `API_PROXY_TARGET` | Backend mà `/api`, `/oauth2`, `/login/oauth2` của từng app proxy tới | `http://127.0.0.1:8080` |
| `STUDIO_BASE_PATH` | Base path (nếu đặt sau reverse proxy) | rỗng |

Không có dấu `/` ở cuối; không đặt secret vào biến `NEXT_PUBLIC_*`.

Cổng dev mặc định: platform `3001`, admin `3002`, studio `3003`.

Điều kiện phía backend/hạ tầng **chưa do C5 xác nhận** (ghi ở `BLOCKERS.md` B-C5-08):
- OIDC redirect URI và CORS/cookie cần cho từng origin trong production. Mỗi app proxy `/api` cùng origin nên mỗi origin có phiên đăng nhập riêng; UI **không** tuyên bố SSO giữa ba origin.
- Khi origin chưa cấu hình, mọi link chỉ là path (`/studio`, `/admin`) và chỉ đúng với app gộp cũ ở thư mục gốc hoặc một host duy nhất.

## 3. Dev server chỉ bind loopback

`dev` và `start` của `apps/*` và của app gốc dùng `next … -H 127.0.0.1`. Muốn mở ra LAN thì chạy tay `npx next dev -H 0.0.0.0 -p 3003` và tự chịu rủi ro (dev server không có kiểm soát truy cập). `scripts/run-local.sh`, `scripts/public-up.sh` đã bind `127.0.0.1` sẵn.

## 4. Quyền vào portal là TEMPORARY

`capabilitiesOf()` hiện cho `platform.operate` và `tenant.administer` theo `me.systemAdmin`, `studio.build` theo "có ≥ 1 workspace" (D-C5-05). Đây chỉ chọn màn hình/ẩn link; server kiểm quyền lại mọi request.

**Điều kiện gỡ TEMPORARY:** C1 giao `TenantContext` / permission canonical (`TENANT_MANAGE`, `TENANT_MEMBERS`, …) trong `/auth/me` hoặc API tương đương. Lúc đó chỉ sửa `capabilitiesOf()`; không thêm permission code mới ở frontend.
