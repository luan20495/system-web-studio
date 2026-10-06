# T7 — PUBLISH_CONFIGS (C2)

Trạng thái: cài đặt xong phần C2 sở hữu; **API tắt** cho tới khi C0 cấp migration và bật `app.publish-configs.enabled=true`. Mã: `backend/src/main/kotlin/com/systemwebstudio/project/publishconfig/`. Quyết định: D-C2-06, D-C2-08.

## 1. Ba thứ khác nhau
| | Là gì | Ai ghi | Có hiệu lực lên bản đang chạy? |
|---|---|---|---|
| `AppDefinition.publishConfig` | **bản nháp ý định** trong tài liệu (mode STATIC/DYNAMIC, visibility, requiresAuth, cacheSeconds) | operation `UPDATE_PUBLISH_CONFIG`, UI, AI | **không** (lưu, version hoá, restore không đổi gì) |
| `publish_configs` | **chính sách xuất bản được duyệt** của project (1 hàng / project, CAS `revision`) | người có `PROJECT_PUBLISH` qua `PUT /publish-config` hoặc `POST /publish-config/adopt-draft` | không; dùng khi tạo **deployment mới** |
| `deployments` (+ `sites.current_deployment_id`) | trạng thái **đang phục vụ**; `visibility` của deployment bất biến | publish pipeline (C0) | có |

Nguồn sự thật = `publish_configs` (chính sách) + `deployments` (thực tế). SHARE (ai mở/sửa trong studio: `projects.project_access_policy`, thành viên, mô-đun sharing của C1) **không** đọc/ghi các bảng này, và ngược lại. Quyền khác nhau: chia sẻ = `PROJECT_MEMBERS`/`MEMBER_MANAGE`, xuất bản = `PROJECT_PUBLISH`.

## 2. Quy tắc (`PublishConfigPolicy`, thuần)
- `mode` theo loại project (`projects.app_kind`): `SERVER_APP` ⇔ mode `SERVER_APP`; `SOURCE_WEB_APP` ⇒ `STATIC`; còn lại `STATIC` hoặc `DYNAMIC`, không bao giờ `SERVER_APP`.
- `visibility`: `PRIVATE` (thành viên project), `TENANT` (mọi thành viên tenant — hiện = workspace; C1 chốt khi có tenant, **PROVISIONAL**), `PUBLIC`, `PRIVATE_LINK` (ai có link; token 256-bit, chỉ lưu SHA-256, hiện đúng một lần, `POST /link/rotate` thu hồi ngay).
- `PUBLIC` cần công tắc admin `publish.public-enabled` (và `source-apps.public-publish-enabled` cho app mã nguồn), không được `requiresAuth`.
- **Dữ liệu lên site PUBLIC** (D-C5-03): nếu tài liệu có `dataBindings`, người xuất bản phải gửi `acknowledgePublicData=true`; ghi vào `public_data_approved` và audit. Áp cho cả STATIC (ảnh chụp dữ liệu lúc build cũng là dữ liệu công khai).
- `cacheSeconds` 0..86400. Mọi thay đổi → audit `UPDATE_PUBLISH_CONFIG` / `ROTATE_PUBLISH_LINK` (không chứa token).

## 3. Tương thích — không phá site đã xuất bản, rollback vẫn chạy
- Không có hàng `publish_configs` ⇒ `PublishConfigService.decisionFor(projectId, requestedVisibility)` trả đúng hành vi cũ (STATIC, PUBLIC nếu request nói PUBLIC, còn lại PRIVATE): API client và UI hiện tại không đổi.
- Migration **backfill** chỉ cho project đã từng deploy (`visibility` = `projects.site_visibility`, mode `STATIC`/`SERVER_APP` theo `app_kind`) để trạng thái hiển thị khớp thực tế; không deployment nào bị chạm.
- Đổi chính sách không đổi deployment đang chạy; rollback chỉ chuyển con trỏ site sang deployment cũ (visibility của deployment đó giữ nguyên) và **không** đọc hay ghi `publish_configs`.

## 4. Migration (DRAFT — C0 cấp số; chưa có file)
```sql
CREATE TABLE publish_configs (
    project_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    mode VARCHAR(16) NOT NULL DEFAULT 'STATIC',
    visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
    requires_auth BOOLEAN NOT NULL DEFAULT FALSE,
    cache_seconds INTEGER,
    public_data_approved BOOLEAN NOT NULL DEFAULT FALSE,
    link_token_hash VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT 1,
    updated_by UUID REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT publish_configs_project_fk FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id),
    CONSTRAINT publish_configs_mode_check CHECK (mode IN ('STATIC', 'DYNAMIC', 'SERVER_APP')),
    CONSTRAINT publish_configs_visibility_check CHECK (visibility IN ('PRIVATE', 'TENANT', 'PUBLIC', 'PRIVATE_LINK')),
    CONSTRAINT publish_configs_cache_check CHECK (cache_seconds IS NULL OR cache_seconds BETWEEN 0 AND 86400),
    CONSTRAINT publish_configs_link_check CHECK (visibility <> 'PRIVATE_LINK' OR link_token_hash IS NOT NULL),
    CONSTRAINT publish_configs_public_auth_check CHECK (visibility <> 'PUBLIC' OR requires_auth = FALSE)
);
INSERT INTO publish_configs (project_id, workspace_id, mode, visibility, revision)
SELECT p.id, p.workspace_id, CASE WHEN p.app_kind = 'SERVER_APP' THEN 'SERVER_APP' ELSE 'STATIC' END,
       CASE WHEN p.site_visibility = 'PUBLIC' THEN 'PUBLIC' ELSE 'PRIVATE' END, 1
FROM projects p
WHERE EXISTS (SELECT 1 FROM deployments d WHERE d.project_id = p.id) OR EXISTS (SELECT 1 FROM server_deployments s WHERE s.project_id = p.id);
```
Một version một task; không sửa bảng hiện có trong migration này.

## 5. Việc của C0 khi tích hợp (publish/** là C0-gated — C2 không sửa)
1. Cấp số, tạo file migration, bật `app.publish-configs.enabled=true` trong `application*.yml`.
2. `PublishController.publish`: gọi `PublishConfigService.decisionFor(projectId, request.visibility)`; nếu có chính sách thì dùng mode/visibility của nó (từ chối request khác visibility bằng 409) và vẫn áp `publish.public-enabled`.
3. Mở rộng `deployments_visibility_check` (và `projects_site_visibility_check` nếu còn dùng) thêm `TENANT`, `PRIVATE_LINK`; thêm cột `mode` cho deployment nếu cần phục vụ DYNAMIC.
4. Serving plane (`SiteControllers`): `TENANT` → người xem đã đăng nhập và thuộc tenant; `PRIVATE_LINK` → token trong URL/cookie kiểm bằng `PublishConfigService.linkMatches` (hằng thời gian).
5. DYNAMIC / dữ liệu lúc publish: theo D-C5-03 (B-C5-06).
