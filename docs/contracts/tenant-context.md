# Contract — TenantContext

Owner: **C1** (đổi contract → `DECISIONS.md`). Trạng thái: DESIGN (chưa có code). Package: `com.systemwebstudio.tenancy`.

## Mục đích
Một giá trị bất biến, được dựng **phía server** cho mỗi request, nói "request này thuộc tenant nào". Mọi truy vấn dữ liệu nghiệp vụ mới (App Definition, DataSource, Workflow…) phải lọc theo nó. Client không bao giờ tự khai tenant.

## Hiện trạng (code thật)
Không có khái niệm tenant. Đơn vị cô lập gần nhất là `workspace` (`AccessService.forWorkspace(userId, workspaceId)` → `AccessContext`), tồn tại bảng `workspaces`, `workspace_members`, `projects(workspace_id)`; có `org`/department ở admin (V20). Không rename bảng `projects`.

## Shape (thiết kế)
```kotlin
data class TenantContext(
    val tenantId: UUID,            // đơn vị cô lập tối thượng (ban đầu = organization; map 1-1 sang workspace hiện có khi chưa có bảng)
    val organizationId: UUID?,     // nếu mô hình có tầng organization phía trên workspace
    val workspaceId: UUID?,        // workspace hiện có (tương thích ngược)
    val actorUserId: UUID,         // user thực hiện
    val actorKind: ActorKind,      // USER | SYSTEM | APP_TOKEN | SERVICE
    val requestId: String?         // RequestIdFilter hiện có
)
enum class ActorKind { USER, SYSTEM, APP_TOKEN, SERVICE }
```

## Quy tắc
1. Dựng ở một nơi (`TenantContextResolver`, C1) từ session/auth + path (`workspaceId`) — **không** đọc từ body/query/header do client điều khiển.
2. Tenant lạ/không phải thành viên → `404` (không lộ sự tồn tại), giống `WORKSPACE_NOT_FOUND` hiện nay.
3. Truyền tường minh qua tham số (hoặc `AccessContext` mở rộng tương thích ngược), không dùng `ThreadLocal` ngầm cho code chạy bất đồng bộ (RabbitMQ worker phải nhận `tenantId` trong message).
4. Repository/query mới **bắt buộc** nhận `tenantId`; không có API "find by id" trần cho dữ liệu tenant-scoped.
5. `AccessContext` hiện tại giữ nguyên chữ ký; thêm trường chỉ bằng giá trị mặc định (backward compatible).
6. Audit: mọi bản ghi mới ghi `tenantId` vào payload; bảng `audit_events` vẫn append-only.
7. Cô lập dữ liệu giữa tenant là yêu cầu bảo mật: cần test "cross-tenant → 404/403".

## Không làm trong Phase 0
Bảng `tenants`, backfill, đổi API, đổi tên bảng — thuộc T1/T2 (C1), migration V26–V34.
