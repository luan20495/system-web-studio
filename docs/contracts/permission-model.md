# Contract — Permission model (AccessContext / AccessService)

Owner: **C1**. Trạng thái: DESIGN. Hot file: `access/AccessService.kt`, `access/Permission.kt`.

## Hiện trạng (code thật)
- `enum Permission { PROJECT_READ, PROJECT_EDIT, PROJECT_SETTINGS, PROJECT_DELETE, PROJECT_PUBLISH, PROJECT_CREATE, PROJECT_MEMBERS, MEMBER_MANAGE, AUDIT_READ, REGISTRY_WRITE }`
- `PermissionMatrix`: role project (VIEWER/EDITOR/PUBLISHER/OWNER) + role workspace (WORKSPACE_ADMIN/EDITOR/PUBLISHER/VIEWER) + `users.system_admin`.
- `AccessContext(user, workspaceId, workspaceRole, projectRole, permissions, project)` với `require(permission)`.
- `AccessService.forWorkspace(userId, workspaceId)` và `forProject(userId, workspaceId, projectId, ignoreArchive)`. Project ARCHIVED chỉ còn `PROJECT_READ`, `AUDIT_READ`.
- Server là nguồn quyền duy nhất; UI chỉ ẩn nút.

## Nguyên tắc bất biến
1. Mọi đường UI → Query/Action đều qua `AccessService` + `AccessContext.require(...)` **trước** Data Gateway.
2. Mặc định **deny**; không có permission ngầm.
3. Hành vi hiện có giữ nguyên (backward compatible). Permission mới là **bổ sung** enum/matrix, không đổi nghĩa permission cũ.
4. Không tồn tại → 404; tồn tại nhưng thiếu quyền → 403 (giữ quy ước hiện tại).

## Shape mở rộng (thiết kế)
```kotlin
// AccessContext giữ nguyên các trường hiện tại; thêm (có default):
val tenant: TenantContext?          // xem tenant-context.md
// AccessService giữ forWorkspace / forProject; thêm:
fun forResource(userId: UUID, tenant: TenantContext, resource: ResourceRef): AccessContext
data class ResourceRef(val type: ResourceType, val id: UUID)
enum class ResourceType { PROJECT, APP_DEFINITION, DATA_SOURCE, ACTION, WORKFLOW }
```
Permission dự kiến thêm (tên do C1 chốt, ghi vào `DECISIONS.md` khi thêm): `DATASOURCE_READ/MANAGE`, `QUERY_EXECUTE`, `ACTION_EXECUTE`, `WORKFLOW_MANAGE`, `SHARE_MANAGE`.

## Cách agent khác dùng
C2/C3/C4/C5 **chỉ gọi** `AccessService`/`AccessContext.require`. Cần permission mới → ghi `BLOCKERS.md` (needs-hot-file) để C1 thêm; không tự sửa `Permission.kt`/`PermissionMatrix`.

## Sharing (C1, sau)
`sharing/**`: cấp quyền theo resource cho user/nhóm; kết quả cuối cùng vẫn là tập `Permission` trong `AccessContext` (một nguồn sự thật).
