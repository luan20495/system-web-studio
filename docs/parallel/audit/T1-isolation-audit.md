# T1 — Isolation / IDOR / BOLA Audit (C1)

- Agent: C1 (Tenant / Identity / Permission / Sharing) · Branch `agent/c1-tenancy` · Base `d3c7065d3b6963dc625be5d0725922f5004fb818`
- Ngày: 2026-10-05 · Phạm vi: toàn bộ `@RestController` trong `backend/src/main/kotlin/com/systemwebstudio/**`
- Kết luận ngắn: **không tìm thấy IDOR/BOLA đọc/ghi chéo workspace hoặc chéo project** trong 227 route. Mọi route mang resource context đi qua `AccessService.forWorkspace/forProject` trước khi chạm dữ liệu, và mọi id con (version, asset, deployment, domain, change, submission) đều được scope theo `project_id` trong SQL. Có **6 gap** (1 Medium cho phần merge policy, 1 Medium cho git access sau khi bị gỡ quyền, 4 Low/Info) — **tất cả nằm ngoài ownership C1**, đã ghi vào `BLOCKERS.md` (B-C1-01…B-C1-06), không sửa tại chỗ. Không có lỗi trong `identity/** access/** member/**`, nên **không có commit fix**.
- **Test mới đã viết nhưng CHƯA chạy được** trong môi trường này (không có Docker/JDK 21 cho Testcontainers). Xem §6 và §10.

## 1. Phương pháp

1. Quét tĩnh toàn bộ controller bằng script (regex trên `@Get/Post/Put/Patch/Delete/RequestMapping`, gắn dấu hiệu `access.forProject`, `access.forWorkspace`, `.require(Permission.*)`, `AdminGuard.require`, header token runner/gateway, bearer SCIM, `me.userId`) → **227 route**.
2. Đọc tay mọi route mà script không thấy `require` (helper `ctx()` che mất) hoặc không thấy `forProject/forWorkspace` (23 dòng `NONE`, 15 route "self", 9 route token).
3. Đọc `AccessService`, `Permission`, `PermissionMatrix`, `ActiveUserFilter` (`identity/ActiveUserFilter.kt`, đăng ký tại `SecurityConfiguration.kt:171`), `MemberController`, `Accounts`, `Scim`, `RegistrationController`.
4. Với mỗi route có id con, kiểm tra truy vấn SQL có ràng buộc `project_id`/`workspace_id` (không tin id từ path/body).
5. Viết ma trận test isolation (`isolation/IsolationApiTests.kt`) từ chính bảng inventory.

## 2. Mô hình truy cập hiện tại (as-is, đối chiếu code)

- `AccessService.forWorkspace(userId, workspaceId)` (`access/AccessService.kt:36-42`): user disabled/không tồn tại → **401 `ACCOUNT_DISABLED`**; không phải thành viên và không phải `system_admin` → **404 `WORKSPACE_NOT_FOUND`**.
- `AccessService.forProject(userId, workspaceId, projectId, ignoreArchive=false)` (`:50-56`): project tìm theo **cặp `(id, workspace_id)` và `active`** → sai workspace trong path = **404 `PROJECT_NOT_FOUND`** (không phân biệt "không tồn tại" với "của người khác"); quyền = quyền workspace + quyền vai trò project; thiếu `PROJECT_READ` → **404**; thiếu quyền khác → **403 `Missing permission: X`**; project `ARCHIVED` → chỉ còn `PROJECT_READ`, `AUDIT_READ` (trừ khi `ignoreArchive`, dùng cho archive/restore).
- Workspace `EDITOR` **không** có `PROJECT_READ` nếu không có vai trò project (kiểm chứng bởi `ProjectApiTests` "same workspace but no project membership" → 404). `WORKSPACE_ADMIN` có toàn bộ quyền project + `PROJECT_CREATE`, `MEMBER_MANAGE`, `AUDIT_READ` (`access/Permission.kt:36`).
- `/api/v1/admin/**` dùng `AdminGuard.require(userId)` (system admin) ở **mọi** route (96/96).
- Public/capability: `/sites/**`, `/sites/_preview/{token}`, `/sites/_app/{token}`, form submit, auth; `/internal/**` dùng token máy (runner/gateway); `/scim/v2/**` dùng bearer token băm SHA-256, so sánh constant-time.

## 3. Tổng quan inventory (227 route)

| Nhóm | Route | Phân loại | Ghi chú |
|---|---:|---|---|
| Workspace/project-scoped (`forWorkspace`/`forProject`) | 69 | **Protected** | 9 workspace + 60 project; 15 route chỉ đọc không `require` tường minh (đọc = `PROJECT_READ` ngầm), 10 route dùng helper `ctx()` có `require` bên trong |
| Admin (`/api/v1/admin/**`) | 96 | **Protected (system admin)** | 96/96 gọi `guard.require` |
| SCIM (`/scim/v2/**`) | 15 | **By-design (bearer token)** | tắt mặc định; token ≥ 32 ký tự; rate limit |
| Token máy (`/internal/**`, `_app/{token}/api`) | 9 | **By-design (machine token)** | ngoài mô hình user |
| Self/own-resource (`/me/**`, templates/packages của chính mình, AI status) | 15 | **Protected (owner/visibility)** + 1 Suspect (S4, S6) | |
| Public / capability / delegating | 23 | **By-design** (21) + **Protected qua service** (2) | bảng §3.1 |
| **Tổng** | **227** | | |

Inventory đầy đủ từng route (file:dòng, guard, phân loại) ở **Phụ lục A**.

### 3.1 Route không có `forProject/forWorkspace` tại controller (23)

| # | Route | Evidence | Phân loại | Lý do |
|---|---|---|---|---|
| 1 | `POST /api/v1/auth/activation/inspect` | `identity/Accounts.kt:171` | By-design | Chỉ nhận token một lần (SHA-256, regex `^[A-Za-z0-9_-]{30,100}$`, TTL 24h, `used_at IS NULL`); không lộ gì ngoài username/displayName của chủ token |
| 2 | `POST /api/v1/auth/activation/complete` | `identity/Accounts.kt:176` | By-design | Claim nguyên tử (`UPDATE … WHERE used_at IS NULL`), đăng xuất mọi session của tài khoản |
| 3 | `GET /api/v1/auth/csrf` | `identity/AuthController.kt:63` | By-design | Public |
| 4 | `GET /api/v1/auth/config` | `identity/AuthController.kt:67` | By-design | Cờ cấu hình đăng nhập |
| 5 | `POST /api/v1/auth/login` | `identity/AuthController.kt:75` | By-design | Public |
| 6 | `GET /api/v1/auth/me` | `identity/AuthController.kt:108` | By-design | Chỉ principal của session; `ActiveUserFilter` → 401 khi disabled |
| 7 | `POST /api/v1/auth/logout` | `identity/AuthController.kt:129` | By-design | |
| 8 | `POST /api/v1/auth/register` | `identity/RegistrationController.kt:51` | By-design | Chỉ tạo user **mới** + workspace **riêng** (`WORKSPACE_ADMIN` của workspace vừa tạo, `:77`); không thể tự gia nhập workspace có sẵn; `signup.enabled`, invite code, rate limit IP, `max-users` |
| 9 | `ANY /sites/{slug}/api/**` | `runtime/Gateway.kt:85` | By-design | Gateway cho traffic khách của site đã publish |
| 10 | `GET /api/v1/library/categories` | `template/Library.kt:187` | By-design | Danh mục tĩnh |
| 11 | `POST …/code/clone-access` | `code/GitAccess.kt:113` | **Protected qua service** | `GitAccessService.issue` gọi `access.forProject` (`code/GitAccess.kt:67`); xem S3/S5 |
| 12 | `GET /api/v1/components`, `/components/{id}` (2 route) | `component/ComponentRegistry.kt:58,62` | By-design | Registry component dùng chung (không dữ liệu tenant) — **global**, ghi cho T2 |
| 13 | `POST /sites/{slug}/_forms/{formId}`, `POST /sites/_host/_forms/{formId}` (2 route) | `publish/Forms.kt:123,127` | By-design | Chỉ ghi vào project của site đang LIVE; allowlist origin, băm IP, rate limit |
| 14 | `GET /sites/_access` | `publish/SiteControllers.kt:59` | By-design | Đổi ticket một lần → session site |
| 15 | `GET /sites/_preview/{token}`, `/**` (2 route) | `publish/SiteControllers.kt:70,77` | By-design | Capability URL (token khó đoán, hết hạn) |
| 16 | `GET /sites/_app/{token}/**` | `publish/SiteControllers.kt:86` | By-design | Capability URL cho app container |
| 17 | `GET /sites/_host/**` | `publish/SiteControllers.kt:97` | By-design | Chỉ phục vụ domain `VERIFIED` của site công khai (`Domains.liveFor`) |
| 18 | `GET /sites/{slug}`, `/**` (2 route) | `publish/SiteControllers.kt:105,110` | By-design | Site PRIVATE cần ticket do thành viên có `PROJECT_READ` cấp (`POST /api/v1/sites/{slug}/access-ticket`, `:255-259`) |
| 19 | `POST …/prompts` | `prompt/PromptController.kt:166` | **Protected qua service** | `forProject` + `require(PROJECT_EDIT)` tại `:104-105` |

(Đếm: 21 route by-design + `clone-access` + `prompts` = 23; `clone-access` được phân loại Suspect ở Phụ lục A vì S3/S5.)

## 4. Protected — bằng chứng và thứ tự kiểm tra

Quy tắc xuyên suốt, được xác nhận bằng đọc code ở từng controller:

1. `forWorkspace`/`forProject` chạy **trước** mọi truy cập dữ liệu và trước khi đọc id con.
2. `require(Permission.X)` chạy **trước** khi tra id con (nên viewer nhận 403 thay vì dò id), trừ các route đọc thuần.
3. Id con luôn nằm trong mệnh đề `WHERE … AND project_id = ?` (hoặc `workspace_id`):
   - versions: `version/SchemaRepository.kt:72-75` (`v.project_id = ? AND v.id = ?`)
   - assets: `asset/AssetController.kt:69` (`id AND project_id AND workspace_id AND status <> 'DELETED'`), `complete`/`delete` dùng cùng `find()`
   - deployments: `publish/DeploymentRepository.kt:23` (`findInProject`) + `PublishController.kt:113`
   - site rollback: `publish/SiteControllers.kt:232-234` (`d.project_id = ?`) → id lạ nhận **400 `DEPLOYMENT_NOT_RESTORABLE`** (không lộ tồn tại)
   - domains: `publish/Domains.kt:80` (`one(projectId, id)` → 404 `DOMAIN_NOT_FOUND`)
   - form submissions: `publish/Forms.kt:167` (`DELETE … WHERE id = ? AND project_id = ?`) và danh sách/CSV cần `PROJECT_EDIT` (dữ liệu cá nhân của khách → viewer **không** đọc được)
   - code changes: `code/CodeChangeController.kt:82-84` (`c.id = ? AND c.project_id = ?`)
   - runtime secrets: `runtime/ServerRuntime.kt:269-275` (`project_id = ? AND name = ?`; giá trị không bao giờ trả về)
4. Member API (`member/MemberController.kt`): `MEMBER_MANAGE` ở mức workspace, `PROJECT_MEMBERS` ở mức project; không tự đổi vai trò của mình; không hạ/gỡ admin/owner cuối cùng (có test đồng thời); người được thêm vào project phải đã là thành viên workspace.
5. Audit (`audit/AuditController.kt`): bắt buộc `AUDIT_READ` ở workspace và câu SQL luôn có `workspace_id = ?`; `projectId` chỉ là bộ lọc thêm → truyền project lạ trả về **rỗng**, không rò rỉ.
6. Admin: 96/96 route `guard.require`; `admin/AdminSupport.kt:18` (`AdminGuard`) kiểm `system_admin AND enabled`.

## 5. Gap phát hiện

Không gap nào cho phép đọc/ghi chéo tenant. Mức độ tính theo tác động thực tế trong mô hình hiện tại (single company, nhiều workspace).

| ID | Mức | Owner thực tế | Evidence | Mô tả / khai thác | Đề xuất | Ghi |
|---|---|---|---|---|---|---|
| **S1** | Medium | C0-gated (`code/**`) | `code/CodeChangeController.kt:105-106`, `:219-226` | Policy hiệu lực = `coalesce(project.merge_policy, workspace.merge_policy)`; người có `PROJECT_SETTINGS` (project OWNER) đặt `AUTO_MERGE_ALLOWED` ở cấp project để **vô hiệu hoá `REVIEW_REQUIRED` do workspace admin đặt**, rồi tự merge. | Project chỉ được **siết** (REVIEW_REQUIRED thắng nếu một trong hai là REVIEW_REQUIRED), hoặc nới policy cần `MEMBER_MANAGE`. | B-C1-01 |
| **S2** | Low | C0-gated (`code/**`) | `code/CodeChangeController.kt:113` | `approve` với change id không tồn tại/thuộc project khác: `jdbc.queryForMap` ném `EmptyResultDataAccessException` → handler chung → **500** thay vì 404 `CHANGE_NOT_FOUND`. Không lộ dữ liệu, không đổi trạng thái. | Dùng `queryForList(...).firstOrNull() ?: throw notFound` hoặc gọi `get(project.id, id)` trước. | B-C1-02 |
| **S3** | Medium | C0-gated (`code/**`, `maintenance/**`) + hook từ `member/` (C1) | `code/GitAccess.kt:63-78`, `:94-110`, `maintenance/ArtifactRetention.kt:65` | Quyền đọc repo (collaborator Forgejo) và token `studio-clone` chỉ bị gỡ khi job retention chạy `syncCollaborators()`. Sau khi bị gỡ khỏi workspace/project hoặc bị disable, user **vẫn clone được source** cho tới lần chạy kế tiếp. Token cũng không bị thu hồi khi disable. | Phát sự kiện `MemberAccessRevoked`/`UserDisabled` từ `member/`/`identity/` (C1, T2) và để `GitAccessService` lắng nghe (revoke token + gỡ collaborator ngay). | B-C1-03 |
| **S4** | Low | C2 (`ai/**`) | `ai/AiController.kt:24-28`, `ai/AiGovernance.kt:127` | `GET /api/v1/ai/status?workspaceId=<uuid>` không kiểm tra thành viên: người ngoài suy ra được **quy tắc deny model của workspace khác** (cần biết UUID). | Khi có `workspaceId` → gọi `access.forWorkspace(me.userId, workspaceId)` (404 nếu không phải thành viên). | B-C1-04 |
| **S5** | Info (cần quyết định) | C0 (permission-model) | `code/CodeChangeController.kt:94` (`previewUrl`), `code/GitAccess.kt:113`, `:67` | `PROJECT_READ` (kể cả VIEWER) nhận được **preview URL (capability) của change chưa merge** và có thể **xin token clone toàn bộ repo**. Có thể là chủ ý; nhưng viewer đọc được source/preview chưa duyệt. | Quyết định trong `permission-model.md` (đề xuất: clone-access và preview URL cần `PROJECT_EDIT`). | B-C1-05 |
| **S6** | Low/Info | C2 (`template/**`) | `template/Templates.kt:30`, `:55`, `:128-136` | `TemplateDto.sourceProjectId` lộ UUID project nguồn của template `COMPANY` cho mọi user đăng nhập (kèm `schema`). Không dẫn tới truy cập (mọi route project trả 404), nhưng lộ id nội bộ chéo workspace. | Ẩn `sourceProjectId` với người không phải tác giả/admin. | B-C1-06 |
| **S7** | Info | C2 (`project/**`) | `project/ProjectController.kt:41-42` | `domain`/`customDomain` nhận qua `PATCH` nhưng việc phục vụ domain thật dùng bảng `site_domains` đã xác minh TXT; hai field cũ là **inert**, không có rủi ro takeover. | Giữ; xoá khi dọn dẹp. | (không blocker) |
| **S8** | Info → T2 | C1 (T2) | xem bên dưới | Tài nguyên **toàn cục, không có scope tenant/workspace** (đúng với mô hình single-company hiện tại, nhưng là đầu vào bắt buộc cho T2). | Xem §8. | (báo cáo) |

Các tài nguyên toàn cục của S8: `templates`/`component_packages` (visibility `COMPANY`), registry component, connector + grants (`runtime/Gateway.kt`), `system_settings`, `git_access` (theo user), `scim_group_mappings`/SCIM bearer token duy nhất, `/internal/**` token máy dùng chung, `departments`, `users.username` duy nhất toàn hệ thống.

**Quan sát thiết kế (không phải lỗi):**
- O-1 `WORKSPACE_ADMIN` thấy/ghi mọi project trong workspace (`projectAll`).
- O-2 `system_admin` bỏ qua kiểm tra thành viên ở `forWorkspace` (`AccessService.kt:42`).
- O-3 `Domains.verify` xoá các claim PENDING của host khác khi một site chứng minh quyền sở hữu (`publish/Domains.kt:111`) — chủ ý (ghi chú trong code).
- O-4 `ARCHIVED` vẫn giữ `AUDIT_READ`.
- O-5 `POST /auth/register` luôn tạo workspace riêng; không có đường tự gia nhập.

## 6. Test

### 6.1 Đã có sẵn (đối chiếu, không sửa)
`ProjectApiTests` (foreign workspace/project), `MemberApiTests` (cross-workspace, leo thang, last admin, member bị gỡ mất project), `SchemaApiTests`, `VersionApiTests` (foreign version 404), `AssetApiTests` (project khác không thấy/xoá asset), `PublishApiTests` (deployment project khác 404), `TemplateAndBlockTests`, `AuditApiTests`, `AuthSecurityTests` (disabled → 401 `ACCOUNT_DISABLED`), `AdminOrgTests` (ARCHIVED read-only), `CodeProjectTests` (clone-access người ngoài 404, runner token).

### 6.2 Mới: `backend/src/test/kotlin/com/systemwebstudio/isolation/IsolationApiTests.kt`
Một bảng 60+ route project-scoped (kể cả code/runtime/domains/members) phát lại với mọi tổ hợp sai id/danh tính; body hợp lệ để validation không che mất lỗi access; kỳ vọng kiểm cả **mã lỗi** (`WORKSPACE_NOT_FOUND`/`PROJECT_NOT_FOUND`) để một route gõ sai đường dẫn không "pass nhầm" bằng 404 của router.

| Test | Chứng minh |
|---|---|
| `an outsider learns nothing and changes nothing …` | User B (workspace B) gọi mọi route với: workspace+project của A; workspace của B + project của A; workspace của A + project của B → luôn 404 đúng mã; DB của A không đổi (member/domain/asset/deployment/audit) |
| `a workspace member without a role on the project …` | Thành viên workspace (EDITOR, VIEWER) không có vai trò project → 404 toàn bộ |
| `without a session every project route is 401` | Anonymous → 401 mọi route + workspace-level |
| `workspace-level routes of a foreign workspace are hidden and create nothing` | projects, members, audit, merge-policy của workspace lạ: 404; không tạo project/member |
| `audit events are scoped …` | Lọc `projectId` của workspace khác ra rỗng; workspace lạ 404 |
| `ids of another project's versions, assets, domains and deployments …` | Dùng id thật của A qua project của B: 404 (rollback 400/404) và dữ liệu A nguyên vẹn |
| `a project viewer reads the basics, cannot read form data …` | Viewer đọc được cơ bản, **không** đọc form submissions/members, 403 mọi thao tác ghi |
| `an archived project is read-only for its owner too` | Owner của project ARCHIVED: ghi → 403, restore → 200 |
| `a disabled account loses every route …` | Disabled → 401 `ACCOUNT_DISABLED` ở lần gọi kế tiếp (đọc và ghi), không đăng nhập lại được |
| `a role in one workspace grants nothing in another` | Admin ở workspace B là viewer ở workspace A → 403 ở A; project của A dưới workspace id của B → 404 |

### 6.3 Chưa phủ bằng test (cần hạ tầng/ngoài scope)
- Id change/diff thật của project khác (cần Forgejo): chỉ phủ bằng ma trận 404 ở cấp project (đủ vì `forProject` chạy trước) + đọc code `:82-84`.
- `runtime/rollback` với deployment thật (cần server app): `ServerRuntimeTests` hiện có; ma trận phủ phần access.
- SSE (`prompts/stream`, `code/ai/stream`) — loại khỏi ma trận do kiểu phản hồi; cùng `forProject` + `require(PROJECT_EDIT)` với bản không stream.
- SCIM, `/internal/**` (token máy), route public `/sites/**` — có test riêng (`StaticSiteTests`, `CodeProjectTests`, `identity/`).

## 7. Kết quả chạy test
**Chưa chạy được.** Môi trường làm việc: JDK 11 và không có Docker/cache Gradle (máy dev), JDK 21 + Gradle nhưng không có Docker daemon và Maven Central bị chặn (cloud). `IntegrationTestBase` cần Testcontainers (PostgreSQL, Redis, MinIO, RabbitMQ). Test được viết theo sát mẫu hiện có (`MemberApiTests`, `AdminOrgTests`, `PublishApiTests`) nhưng **chưa được biên dịch/chạy**; C0/người review cần chạy:

```
cd backend && ./gradlew test --tests 'com.systemwebstudio.isolation.IsolationApiTests'
cd backend && ./gradlew test        # đối chiếu baseline 192 tests / 0 fail / 3 skipped
```
Rủi ro đã biết khi chạy lần đầu: body của một số route trong bảng có thể cần chỉnh nếu validation thay đổi; mọi lỗi sẽ in danh sách route sai lệch (`expectAll` gom hết).

## 8. Đề xuất cho T2 (Tenant foundation)

1. **Giữ `AccessService` là điểm chặn duy nhất**: thêm resolve `TenantContext` trong `forWorkspace`; `forProject` tiếp tục tìm theo `(id, workspace_id)` và workspace phải thuộc tenant của caller. Giữ quy ước "ngoài phạm vi → 404".
2. **Quyết định rõ `system_admin` (O-2)**: tách *Super Admin* (xuyên tenant) và *Tenant Admin* (`WORKSPACE_ADMIN` mở rộng); hiện `forWorkspace` cho `system_admin` đi qua mọi workspace.
3. **Gắn tenant cho tài nguyên toàn cục (S8)**: templates, component_packages, connector + grants, system_settings, departments, scim mappings, git_access; username hiện duy nhất toàn hệ thống.
4. **Thu hồi thông tin xác thực ngoài khi mất quyền (S3)**: sự kiện miền (`MemberRemoved`, `UserDisabled`, `RoleChanged`) để Git token, SSE stream, session site `_app` bị cắt ngay, không chờ job.
5. **Không để helper nào bỏ qua `forProject`**: giữ ma trận `IsolationApiTests` làm bài hồi quy bắt buộc và thêm chiều *tenant* (user ở tenant khác × workspace × project).
6. **Chuẩn hoá 404 ở tầng repository** (S2): dùng `firstOrNull() ?: notFound` thay `queryForMap/queryForObject` để tránh 500 khi id lạ.
7. Cân nhắc RLS hoặc `tenant_id` trong mọi truy vấn id con (hiện dựa vào kỷ luật `AND project_id = ?` — đúng ở mọi chỗ đã kiểm tra, nhưng không có lưới an toàn ở DB).

## 9. Giới hạn của audit
Audit tĩnh + test viết sẵn (chưa chạy). Không fuzz runtime, không kiểm cấu hình nginx/gateway ngoài JVM, không kiểm frontend, không đánh giá brute-force/rate limit của đăng nhập. Phân loại 227 route dựa trên script + đọc tay; route thêm sau commit base không được tính.

## Phụ lục A — Inventory đầy đủ

| # | Method | Path | Evidence | Guard (script) | Class | Note |
|---:|---|---|---|---|---|---|
| 1 | GET | `/api/v1/admin/settings/policies` | `settings/Settings.kt:134` | admin | self | Protected | AdminGuard (system admin) |
| 2 | PUT | `/api/v1/admin/settings/policies/{key}` | `settings/Settings.kt:138` | admin | self | Protected | AdminGuard (system admin) |
| 3 | DELETE | `/api/v1/admin/settings/policies/{key}` | `settings/Settings.kt:154` | admin | self | Protected | AdminGuard (system admin) |
| 4 | POST | `/api/v1/admin/users` | `identity/Accounts.kt:144` | admin | self | Protected | AdminGuard (system admin) |
| 5 | POST | `/api/v1/admin/users/{id}/activation-link` | `identity/Accounts.kt:148` | admin | self | Protected | AdminGuard (system admin) |
| 6 | POST | `/api/v1/admin/users/{id}/system-admin` | `identity/Accounts.kt:152` | admin | self | Protected | AdminGuard (system admin) |
| 7 | POST | `/api/v1/admin/workspaces` | `identity/Accounts.kt:159` | admin | self | Protected | AdminGuard (system admin) |
| 8 | POST | `/api/v1/auth/activation/inspect` | `identity/Accounts.kt:171` | NONE | By-design | public / capability (audit §3.1) |
| 9 | POST | `/api/v1/auth/activation/complete` | `identity/Accounts.kt:176` | NONE | By-design | public / capability (audit §3.1) |
| 10 | GET | `/api/v1/auth/csrf` | `identity/AuthController.kt:63` | NONE | By-design | public / capability (audit §3.1) |
| 11 | GET | `/api/v1/auth/config` | `identity/AuthController.kt:67` | NONE | By-design | public / capability (audit §3.1) |
| 12 | POST | `/api/v1/auth/login` | `identity/AuthController.kt:75` | NONE | By-design | public / capability (audit §3.1) |
| 13 | GET | `/api/v1/auth/me` | `identity/AuthController.kt:108` | NONE | By-design | public / capability (audit §3.1) |
| 14 | POST | `/api/v1/auth/logout` | `identity/AuthController.kt:129` | NONE | By-design | public / capability (audit §3.1) |
| 15 | GET | `/api/v1/me/usage` | `identity/MeController.kt:32` | self | Protected | owner / visibility check (self) |
| 16 | GET | `/api/v1/me/activity` | `identity/MeController.kt:43` | self | Protected | owner / visibility check (self) |
| 17 | POST | `/api/v1/auth/register` | `identity/RegistrationController.kt:51` | NONE | By-design | public / capability (audit §3.1) |
| 18 | GET | `/scim/v2/ServiceProviderConfig` | `identity/Scim.kt:319` | scim | By-design | SCIM bearer token |
| 19 | GET | `/scim/v2/ResourceTypes` | `identity/Scim.kt:325` | scim | By-design | SCIM bearer token |
| 20 | GET | `/scim/v2/Schemas` | `identity/Scim.kt:330` | scim | By-design | SCIM bearer token |
| 21 | GET | `/scim/v2/Users` | `identity/Scim.kt:335` | scim | By-design | SCIM bearer token |
| 22 | GET | `/scim/v2/Users/{id}` | `identity/Scim.kt:339` | scim | By-design | SCIM bearer token |
| 23 | POST | `/scim/v2/Users` | `identity/Scim.kt:340` | scim | By-design | SCIM bearer token |
| 24 | PUT | `/scim/v2/Users/{id}` | `identity/Scim.kt:341` | scim | By-design | SCIM bearer token |
| 25 | PATCH | `/scim/v2/Users/{id}` | `identity/Scim.kt:342` | scim | By-design | SCIM bearer token |
| 26 | DELETE | `/scim/v2/Users/{id}` | `identity/Scim.kt:343` | scim | By-design | SCIM bearer token |
| 27 | GET | `/scim/v2/Groups` | `identity/Scim.kt:345` | scim | By-design | SCIM bearer token |
| 28 | GET | `/scim/v2/Groups/{id}` | `identity/Scim.kt:349` | scim | By-design | SCIM bearer token |
| 29 | POST | `/scim/v2/Groups` | `identity/Scim.kt:350` | scim | By-design | SCIM bearer token |
| 30 | PUT | `/scim/v2/Groups/{id}` | `identity/Scim.kt:351` | scim | By-design | SCIM bearer token |
| 31 | PATCH | `/scim/v2/Groups/{id}` | `identity/Scim.kt:352` | scim | By-design | SCIM bearer token |
| 32 | DELETE | `/scim/v2/Groups/{id}` | `identity/Scim.kt:353` | scim | By-design | SCIM bearer token |
| 33 | GET | `/api/v1/admin/scim` | `identity/Scim.kt:363` | admin | self | Protected | AdminGuard (system admin) |
| 34 | POST | `/api/v1/admin/scim/mappings` | `identity/Scim.kt:375` | admin | self | Protected | AdminGuard (system admin) |
| 35 | DELETE | `/api/v1/admin/scim/mappings/{id}` | `identity/Scim.kt:388` | admin | self | Protected | AdminGuard (system admin) |
| 36 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/assets/upload-url` | `asset/AssetController.kt:72` | forProject | perm:PROJECT_EDIT | self | Protected | forProject | perm:PROJECT_EDIT | self |
| 37 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/assets/complete` | `asset/AssetController.kt:103` | forProject | perm:PROJECT_EDIT | self | Protected | forProject | perm:PROJECT_EDIT | self |
| 38 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/assets` | `asset/AssetController.kt:128` | forProject | self | Protected | forProject | self |
| 39 | DELETE | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/assets/{assetId}` | `asset/AssetController.kt:136` | forProject | perm:PROJECT_EDIT | self | Protected | forProject | perm:PROJECT_EDIT | self |
| 40 | GET | `/api/v1/workspaces/{w}/members` | `member/MemberController.kt:54` | forWorkspace | perm:MEMBER_MANAGE | self | Protected | forWorkspace | perm:MEMBER_MANAGE | self |
| 41 | POST | `/api/v1/workspaces/{w}/members` | `member/MemberController.kt:61` | forWorkspace | perm:MEMBER_MANAGE | self | Protected | forWorkspace | perm:MEMBER_MANAGE | self |
| 42 | PATCH | `/api/v1/workspaces/{w}/members/{userId}` | `member/MemberController.kt:83` | forWorkspace | perm:MEMBER_MANAGE | self | Protected | forWorkspace | perm:MEMBER_MANAGE | self |
| 43 | DELETE | `/api/v1/workspaces/{w}/members/{userId}` | `member/MemberController.kt:99` | forWorkspace | perm:MEMBER_MANAGE | self | Protected | forWorkspace | perm:MEMBER_MANAGE | self |
| 44 | GET | `/api/v1/workspaces/{w}/projects/{p}/members` | `member/MemberController.kt:113` | forProject | perm:PROJECT_MEMBERS | self | Protected | forProject | perm:PROJECT_MEMBERS | self |
| 45 | POST | `/api/v1/workspaces/{w}/projects/{p}/members` | `member/MemberController.kt:120` | forProject | perm:PROJECT_MEMBERS | self | Protected | forProject | perm:PROJECT_MEMBERS | self |
| 46 | PATCH | `/api/v1/workspaces/{w}/projects/{p}/members/{userId}` | `member/MemberController.kt:144` | forProject | perm:PROJECT_MEMBERS | self | Protected | forProject | perm:PROJECT_MEMBERS | self |
| 47 | DELETE | `/api/v1/workspaces/{w}/projects/{p}/members/{userId}` | `member/MemberController.kt:161` | forProject | perm:PROJECT_MEMBERS | self | Protected | forProject | perm:PROJECT_MEMBERS | self |
| 48 | ANY | `/sites/{slug}/api/**` | `runtime/Gateway.kt:85` | NONE | By-design | public / capability (audit §3.1) |
| 49 | ANY | `/sites/_app/{token}/api/**` | `runtime/Gateway.kt:93` | token | By-design | machine / capability token |
| 50 | ANY | `/internal/connectors/{key}/**` | `runtime/Gateway.kt:181` | token | By-design | machine / capability token |
| 51 | GET | `/api/v1/admin/connectors` | `runtime/Gateway.kt:225` | admin | self | Protected | AdminGuard (system admin) |
| 52 | PUT | `/api/v1/admin/connectors` | `runtime/Gateway.kt:228` | admin | self | Protected | AdminGuard (system admin) |
| 53 | POST | `/api/v1/admin/connectors/{key}/status` | `runtime/Gateway.kt:251` | admin | self | Protected | AdminGuard (system admin) |
| 54 | POST | `/api/v1/admin/connectors/{key}/grants` | `runtime/Gateway.kt:260` | admin | self | Protected | AdminGuard (system admin) |
| 55 | DELETE | `/api/v1/admin/connectors/{key}/grants/{projectId}` | `runtime/Gateway.kt:271` | admin | self | Protected | AdminGuard (system admin) |
| 56 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/runtime` | `runtime/ServerRuntime.kt:246` | forProject | perm:PROJECT_PUBLISH,PROJECT_SETTINGS | Protected | forProject | perm:PROJECT_PUBLISH,PROJECT_SETTINGS |
| 57 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/runtime/rollback` | `runtime/ServerRuntime.kt:253` | forProject | perm:PROJECT_PUBLISH | self | Protected | forProject | perm:PROJECT_PUBLISH | self |
| 58 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/runtime/stop` | `runtime/ServerRuntime.kt:258` | forProject | perm:PROJECT_PUBLISH | self | Protected | forProject | perm:PROJECT_PUBLISH | self |
| 59 | PUT | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/runtime/secrets` | `runtime/ServerRuntime.kt:264` | forProject | perm:PROJECT_SETTINGS | self | Protected | forProject | perm:PROJECT_SETTINGS | self |
| 60 | DELETE | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/runtime/secrets/{name}` | `runtime/ServerRuntime.kt:269` | forProject | perm:PROJECT_SETTINGS | Protected | forProject | perm:PROJECT_SETTINGS |
| 61 | GET | `/internal/runtime/desired` | `runtime/ServerRuntime.kt:288` | token | By-design | machine / capability token |
| 62 | GET | `/internal/runtime/artifacts/{artifactId}` | `runtime/ServerRuntime.kt:291` | token | By-design | machine / capability token |
| 63 | POST | `/internal/runtime/{deploymentId}/report` | `runtime/ServerRuntime.kt:294` | token | By-design | machine / capability token |
| 64 | GET | `/api/v1/library/categories` | `template/Library.kt:187` | NONE | By-design | public / capability (audit §3.1) |
| 65 | PATCH | `/api/v1/templates/{id}/catalog` | `template/Library.kt:190` | self | Protected | owner / visibility check (self) |
| 66 | POST | `/api/v1/templates/{id}/submit` | `template/Library.kt:203` | self | Protected | owner / visibility check (self) |
| 67 | POST | `/api/v1/templates/{id}/withdraw` | `template/Library.kt:207` | self | Protected | owner / visibility check (self) |
| 68 | GET | `/api/v1/templates/{id}/reviews` | `template/Library.kt:211` | admin | self | Protected | owner / visibility check (self) |
| 69 | GET | `/api/v1/templates/{id}/preview` | `template/Library.kt:219` | self | Protected | owner / visibility check (self) |
| 70 | POST | `/api/v1/admin/templates/{id}/review` | `template/Library.kt:224` | admin | self | Protected | AdminGuard (system admin) |
| 71 | POST | `/api/v1/admin/templates/{id}/preview` | `template/Library.kt:230` | admin | self | Protected | AdminGuard (system admin) |
| 72 | PATCH | `/api/v1/component-packages/{id}/catalog` | `template/Library.kt:237` | admin | self | Protected | owner / visibility check (self) |
| 73 | GET | `/api/v1/component-packages/{id}/preview` | `template/Library.kt:253` | self | Protected | owner / visibility check (self) |
| 74 | POST | `/api/v1/admin/component-packages/{id}/preview` | `template/Library.kt:258` | admin | self | Protected | AdminGuard (system admin) |
| 75 | GET | `/api/v1/templates` | `template/Templates.kt:123` | admin | self | Suspect | Suspect S6 / B-C1-06 |
| 76 | GET | `/api/v1/templates/{id}` | `template/Templates.kt:138` | self | Suspect | Suspect S6 / B-C1-06 |
| 77 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/templates` | `template/Templates.kt:143` | forProject | perm:PROJECT_EDIT | self | Protected | forProject | perm:PROJECT_EDIT | self |
| 78 | PATCH | `/api/v1/templates/{id}` | `template/Templates.kt:171` | self | Protected | owner / visibility check (self) |
| 79 | DELETE | `/api/v1/templates/{id}` | `template/Templates.kt:182` | self | Protected | owner / visibility check (self) |
| 80 | GET | `/api/v1/admin/ai/limits` | `admin/AdminAiLimits.kt:70` | admin | self | Protected | AdminGuard (system admin) |
| 81 | PUT | `/api/v1/admin/ai/limits/defaults` | `admin/AdminAiLimits.kt:90` | admin | self | Protected | AdminGuard (system admin) |
| 82 | PUT | `/api/v1/admin/ai/limits/overrides` | `admin/AdminAiLimits.kt:105` | admin | self | Protected | AdminGuard (system admin) |
| 83 | DELETE | `/api/v1/admin/ai/limits/overrides/{id}` | `admin/AdminAiLimits.kt:130` | admin | self | Protected | AdminGuard (system admin) |
| 84 | GET | `/api/v1/admin/ai/limits/users/{userId}` | `admin/AdminAiLimits.kt:141` | admin | self | Protected | AdminGuard (system admin) |
| 85 | GET | `/api/v1/admin/ai/providers` | `admin/AdminAiProvidersController.kt:81` | admin | self | Protected | AdminGuard (system admin) |
| 86 | POST | `/api/v1/admin/ai/providers` | `admin/AdminAiProvidersController.kt:143` | admin | self | Protected | AdminGuard (system admin) |
| 87 | PUT | `/api/v1/admin/ai/providers/{id}` | `admin/AdminAiProvidersController.kt:163` | admin | self | Protected | AdminGuard (system admin) |
| 88 | DELETE | `/api/v1/admin/ai/providers/{id}` | `admin/AdminAiProvidersController.kt:187` | admin | self | Protected | AdminGuard (system admin) |
| 89 | POST | `/api/v1/admin/ai/providers/{id}/probe` | `admin/AdminAiProvidersController.kt:213` | admin | self | Protected | AdminGuard (system admin) |
| 90 | POST | `/api/v1/admin/ai/providers/{id}/discover` | `admin/AdminAiProvidersController.kt:233` | admin | self | Protected | AdminGuard (system admin) |
| 91 | PUT | `/api/v1/admin/ai/models/policy` | `admin/AdminAiProvidersController.kt:250` | admin | self | Protected | AdminGuard (system admin) |
| 92 | GET | `/api/v1/admin/ai/pricing` | `admin/AdminAiProvidersController.kt:263` | admin | self | Protected | AdminGuard (system admin) |
| 93 | POST | `/api/v1/admin/ai/pricing` | `admin/AdminAiProvidersController.kt:271` | admin | self | Protected | AdminGuard (system admin) |
| 94 | GET | `/api/v1/admin/ai/usage` | `admin/AdminAiUsageController.kt:47` | admin | self | Protected | AdminGuard (system admin) |
| 95 | GET | `/api/v1/admin/ai/calls` | `admin/AdminAiUsageController.kt:72` | admin | self | Protected | AdminGuard (system admin) |
| 96 | GET | `/api/v1/admin/overview` | `admin/AdminController.kt:72` | admin | self | Protected | AdminGuard (system admin) |
| 97 | GET | `/api/v1/admin/workspaces` | `admin/AdminController.kt:99` | admin | self | Protected | AdminGuard (system admin) |
| 98 | GET | `/api/v1/admin/workspaces/{id}` | `admin/AdminController.kt:112` | admin | self | Protected | AdminGuard (system admin) |
| 99 | GET | `/api/v1/admin/applications` | `admin/AdminController.kt:141` | admin | self | Protected | AdminGuard (system admin) |
| 100 | GET | `/api/v1/admin/applications/{id}` | `admin/AdminController.kt:156` | admin | self | Protected | AdminGuard (system admin) |
| 101 | POST | `/api/v1/admin/applications/{id}/transfer-ownership` | `admin/AdminController.kt:178` | admin | self | Protected | AdminGuard (system admin) |
| 102 | GET | `/api/v1/admin/audit` | `admin/AdminController.kt:198` | admin | self | Protected | AdminGuard (system admin) |
| 103 | GET | `/api/v1/admin/audit/actions` | `admin/AdminController.kt:220` | admin | self | Protected | AdminGuard (system admin) |
| 104 | GET | `/api/v1/admin/ai` | `admin/AdminController.kt:227` | admin | self | Protected | AdminGuard (system admin) |
| 105 | GET | `/api/v1/admin/components` | `admin/AdminController.kt:249` | admin | self | Protected | AdminGuard (system admin) |
| 106 | GET | `/api/v1/admin/system/health` | `admin/AdminController.kt:263` | admin | self | Protected | AdminGuard (system admin) |
| 107 | GET | `/api/v1/admin/settings` | `admin/AdminController.kt:295` | admin | self | Protected | AdminGuard (system admin) |
| 108 | GET | `/api/v1/admin/component-packages` | `admin/AdminGovernanceController.kt:34` | admin | self | Protected | AdminGuard (system admin) |
| 109 | GET | `/api/v1/admin/component-packages/{id}` | `admin/AdminGovernanceController.kt:49` | admin | self | Protected | AdminGuard (system admin) |
| 110 | POST | `/api/v1/admin/component-packages/{id}/review` | `admin/AdminGovernanceController.kt:56` | admin | self | Protected | AdminGuard (system admin) |
| 111 | POST | `/api/v1/admin/component-packages/{id}/deprecate` | `admin/AdminGovernanceController.kt:85` | admin | self | Protected | AdminGuard (system admin) |
| 112 | POST | `/api/v1/admin/component-packages/{id}/restore` | `admin/AdminGovernanceController.kt:97` | admin | self | Protected | AdminGuard (system admin) |
| 113 | GET | `/api/v1/admin/templates` | `admin/AdminGovernanceController.kt:111` | admin | self | Protected | AdminGuard (system admin) |
| 114 | POST | `/api/v1/admin/templates/{id}/visibility` | `admin/AdminGovernanceController.kt:128` | admin | self | Protected | AdminGuard (system admin) |
| 115 | POST | `/api/v1/admin/templates/{id}/status` | `admin/AdminGovernanceController.kt:142` | admin | self | Protected | AdminGuard (system admin) |
| 116 | GET | `/api/v1/admin/departments` | `admin/AdminOrg.kt:36` | admin | self | Protected | AdminGuard (system admin) |
| 117 | POST | `/api/v1/admin/departments` | `admin/AdminOrg.kt:48` | admin | self | Protected | AdminGuard (system admin) |
| 118 | PATCH | `/api/v1/admin/departments/{id}` | `admin/AdminOrg.kt:61` | admin | self | Protected | AdminGuard (system admin) |
| 119 | DELETE | `/api/v1/admin/departments/{id}` | `admin/AdminOrg.kt:75` | admin | self | Protected | AdminGuard (system admin) |
| 120 | PUT | `/api/v1/admin/departments/assign/users/{userId}` | `admin/AdminOrg.kt:89` | admin | self | Protected | AdminGuard (system admin) |
| 121 | PUT | `/api/v1/admin/departments/assign/workspaces/{workspaceId}` | `admin/AdminOrg.kt:98` | admin | self | Protected | AdminGuard (system admin) |
| 122 | GET | `/api/v1/admin/costs` | `admin/AdminOrg.kt:138` | admin | self | Protected | AdminGuard (system admin) |
| 123 | GET | `/api/v1/admin/costs/prices` | `admin/AdminOrg.kt:175` | admin | self | Protected | AdminGuard (system admin) |
| 124 | POST | `/api/v1/admin/costs/prices` | `admin/AdminOrg.kt:180` | admin | self | Protected | AdminGuard (system admin) |
| 125 | GET | `/api/v1/admin/security/findings` | `admin/AdminOrg.kt:210` | admin | self | Protected | AdminGuard (system admin) |
| 126 | GET | `/api/v1/admin/retention/preview` | `admin/AdminRetentionController.kt:20` | admin | self | Protected | AdminGuard (system admin) |
| 127 | POST | `/api/v1/admin/retention/run` | `admin/AdminRetentionController.kt:23` | admin | self | Protected | AdminGuard (system admin) |
| 128 | GET | `/api/v1/admin/retention/repositories` | `admin/AdminRetentionController.kt:26` | admin | self | Protected | AdminGuard (system admin) |
| 129 | POST | `/api/v1/admin/retention/repositories/{projectId}/delete` | `admin/AdminRetentionController.kt:36` | admin | self | Protected | AdminGuard (system admin) |
| 130 | GET | `/api/v1/admin/users` | `admin/AdminUserController.kt:47` | admin | self | Protected | AdminGuard (system admin) |
| 131 | GET | `/api/v1/admin/users/{id}` | `admin/AdminUserController.kt:64` | admin | self | Protected | AdminGuard (system admin) |
| 132 | PATCH | `/api/v1/admin/users/{id}/status` | `admin/AdminUserController.kt:78` | admin | self | Protected | AdminGuard (system admin) |
| 133 | POST | `/api/v1/admin/users/{id}/revoke-sessions` | `admin/AdminUserController.kt:97` | admin | self | Protected | AdminGuard (system admin) |
| 134 | POST | `/internal/build-jobs/claim` | `code/BuildJobs.kt:221` | token | By-design | machine / capability token |
| 135 | GET | `/internal/build-jobs/{id}/source` | `code/BuildJobs.kt:228` | token | By-design | machine / capability token |
| 136 | PUT | `/internal/build-jobs/{id}/artifact` | `code/BuildJobs.kt:231` | token | By-design | machine / capability token |
| 137 | POST | `/internal/build-jobs/{id}/finish` | `code/BuildJobs.kt:238` | token | By-design | machine / capability token |
| 138 | GET | `/api/v1/admin/builds` | `code/BuildPolicy.kt:85` | admin | self | Protected | AdminGuard (system admin) |
| 139 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/ai` | `code/CodeAi.kt:198` | forProject | perm:PROJECT_EDIT | self | Protected | forProject | perm:PROJECT_EDIT | self |
| 140 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/ai/stream` | `code/CodeAi.kt:204` | forProject | perm:PROJECT_EDIT | self | Protected | forProject | perm:PROJECT_EDIT | self |
| 141 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/ai` | `code/CodeAi.kt:213` | forProject | self | Protected | forProject | self |
| 142 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/tree` | `code/CodeChangeController.kt:160` | forProject | Protected | forProject |
| 143 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/file` | `code/CodeChangeController.kt:167` | forProject | Protected | forProject |
| 144 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/commits` | `code/CodeChangeController.kt:178` | forProject | Protected | forProject |
| 145 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/changes` | `code/CodeChangeController.kt:184` | forProject | Suspect | Suspect S5 / B-C1-05 |
| 146 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/changes` | `code/CodeChangeController.kt:189` | forProject | perm:PROJECT_EDIT | self | Protected | forProject | perm:PROJECT_EDIT | self |
| 147 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/changes/{id}` | `code/CodeChangeController.kt:197` | forProject | Suspect | Suspect S5 / B-C1-05 |
| 148 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/changes/{id}/diff` | `code/CodeChangeController.kt:202` | forProject | Protected | forProject |
| 149 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/changes/{id}/merge` | `code/CodeChangeController.kt:207` | forProject | perm:PROJECT_EDIT | self | Protected | forProject | perm:PROJECT_EDIT | self |
| 150 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/changes/{id}/approve` | `code/CodeChangeController.kt:212` | forProject | self | Suspect | Suspect S2 / B-C1-02 |
| 151 | PUT | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/merge-policy` | `code/CodeChangeController.kt:219` | forProject | perm:PROJECT_SETTINGS | Suspect | Suspect S1 / B-C1-01 |
| 152 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/changes/{id}/discard` | `code/CodeChangeController.kt:229` | forProject | perm:PROJECT_EDIT | Protected | forProject | perm:PROJECT_EDIT |
| 153 | GET | `/api/v1/workspaces/{workspaceId}/merge-policy` | `code/CodeChangeController.kt:239` | forWorkspace | self | Protected | forWorkspace | self |
| 154 | PUT | `/api/v1/workspaces/{workspaceId}/merge-policy` | `code/CodeChangeController.kt:245` | forWorkspace | perm:MEMBER_MANAGE | self | Protected | forWorkspace | perm:MEMBER_MANAGE | self |
| 155 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/design` | `code/CodeChangeController.kt:265` | forProject | self | Protected | forProject | self |
| 156 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/design/edit` | `code/CodeChangeController.kt:274` | forProject | perm:PROJECT_EDIT | self | Protected | forProject | perm:PROJECT_EDIT | self |
| 157 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/clone-access` | `code/GitAccess.kt:113` | NONE | Suspect | Suspect S3,S5 / B-C1-03,B-C1-05 |
| 158 | DELETE | `/api/v1/me/clone-access` | `code/GitAccess.kt:116` | self | Protected | owner / visibility check (self) |
| 159 | GET | `/api/v1/admin/packages` | `code/Packages.kt:102` | admin | self | Protected | AdminGuard (system admin) |
| 160 | POST | `/api/v1/admin/packages` | `code/Packages.kt:106` | admin | self | Protected | AdminGuard (system admin) |
| 161 | PUT | `/api/v1/admin/packages/{name}/decision` | `code/Packages.kt:122` | admin | self | Protected | AdminGuard (system admin) |
| 162 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/dependencies` | `code/Packages.kt:212` | forProject | self | Protected | forProject | self |
| 163 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/dependencies` | `code/Packages.kt:218` | forProject | perm:PROJECT_EDIT | self | Protected | forProject | perm:PROJECT_EDIT | self |
| 164 | GET | `/api/v1/component-packages` | `component/ComponentPackages.kt:164` | admin | self | Protected | owner / visibility check (self) |
| 165 | GET | `/api/v1/component-packages/{id}` | `component/ComponentPackages.kt:172` | admin | self | Protected | owner / visibility check (self) |
| 166 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/component-packages` | `component/ComponentPackages.kt:178` | forProject | perm:PROJECT_EDIT | admin | self | Protected | forProject | perm:PROJECT_EDIT | admin | self |
| 167 | PATCH | `/api/v1/component-packages/{id}` | `component/ComponentPackages.kt:225` | admin | self | Protected | owner / visibility check (self) |
| 168 | POST | `/api/v1/component-packages/{id}/submit` | `component/ComponentPackages.kt:237` | admin | self | Protected | owner / visibility check (self) |
| 169 | POST | `/api/v1/component-packages/{id}/withdraw` | `component/ComponentPackages.kt:260` | admin | self | Protected | owner / visibility check (self) |
| 170 | DELETE | `/api/v1/component-packages/{id}` | `component/ComponentPackages.kt:274` | self | Protected | owner / visibility check (self) |
| 171 | GET | `/api/v1/components` | `component/ComponentRegistry.kt:58` | NONE | By-design | public / capability (audit §3.1) |
| 172 | GET | `/api/v1/components/{id}` | `component/ComponentRegistry.kt:62` | NONE | By-design | public / capability (audit §3.1) |
| 173 | GET | `/api/v1/workspaces/{workspaceId}/projects` | `project/ProjectController.kt:83` | forWorkspace | self | Protected | forWorkspace | self |
| 174 | POST | `/api/v1/workspaces/{workspaceId}/projects` | `project/ProjectController.kt:105` | forWorkspace | perm:PROJECT_CREATE | self | Protected | forWorkspace | perm:PROJECT_CREATE | self |
| 175 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}` | `project/ProjectController.kt:152` | forProject | self | Protected | forProject | self |
| 176 | PATCH | `/api/v1/workspaces/{workspaceId}/projects/{projectId}` | `project/ProjectController.kt:159` | forProject | perm:PROJECT_SETTINGS | self | Protected | forProject | perm:PROJECT_SETTINGS | self |
| 177 | DELETE | `/api/v1/workspaces/{workspaceId}/projects/{projectId}` | `project/ProjectController.kt:190` | forProject | perm:PROJECT_DELETE | self | Protected | forProject | perm:PROJECT_DELETE | self |
| 178 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/archive` | `project/ProjectLifecycle.kt:44` | forProject | perm:PROJECT_DELETE | self | Protected | forProject | perm:PROJECT_DELETE | self |
| 179 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/restore` | `project/ProjectLifecycle.kt:51` | forProject | perm:PROJECT_DELETE | self | Protected | forProject | perm:PROJECT_DELETE | self |
| 180 | POST | `/api/v1/admin/applications/{id}/archive` | `project/ProjectLifecycle.kt:61` | admin | self | Protected | AdminGuard (system admin) |
| 181 | POST | `/api/v1/admin/applications/{id}/restore` | `project/ProjectLifecycle.kt:67` | admin | self | Protected | AdminGuard (system admin) |
| 182 | GET | `/api/v1/projects/{projectId}` | `project/ProjectLookupController.kt:17` | forProject | self | Protected | forProject | self |
| 183 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/domains` | `publish/Domains.kt:165` | forProject | Protected | forProject |
| 184 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/domains` | `publish/Domains.kt:170` | forProject | self | Protected | forProject | self |
| 185 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/domains/{id}/verify` | `publish/Domains.kt:177` | forProject | Protected | forProject |
| 186 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/domains/{id}/check-tls` | `publish/Domains.kt:182` | forProject | Protected | forProject |
| 187 | DELETE | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/domains/{id}` | `publish/Domains.kt:187` | forProject | Protected | forProject |
| 188 | POST | `/sites/{slug}/_forms/{formId}` | `publish/Forms.kt:123` | NONE | By-design | public / capability (audit §3.1) |
| 189 | POST | `/sites/_host/_forms/{formId}` | `publish/Forms.kt:127` | NONE | By-design | public / capability (audit §3.1) |
| 190 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/form-submissions` | `publish/Forms.kt:138` | forProject | perm:PROJECT_EDIT | self | Protected | forProject | perm:PROJECT_EDIT | self |
| 191 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/form-submissions/export` | `publish/Forms.kt:146` | forProject | perm:PROJECT_EDIT | self | Protected | forProject | perm:PROJECT_EDIT | self |
| 192 | DELETE | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/form-submissions/{id}` | `publish/Forms.kt:162` | forProject | perm:PROJECT_EDIT | self | Protected | forProject | perm:PROJECT_EDIT | self |
| 193 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/publish` | `publish/PublishController.kt:54` | forProject | perm:PROJECT_PUBLISH | self | Protected | forProject | perm:PROJECT_PUBLISH | self |
| 194 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/deployments` | `publish/PublishController.kt:102` | forProject | self | Protected | forProject | self |
| 195 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/deployments/{deploymentId}` | `publish/PublishController.kt:109` | forProject | self | Protected | forProject | self |
| 196 | GET | `/sites/_access` | `publish/SiteControllers.kt:59` | NONE | By-design | public / capability (audit §3.1) |
| 197 | GET | `/sites/_preview/{token}` | `publish/SiteControllers.kt:70` | NONE | By-design | public / capability (audit §3.1) |
| 198 | GET | `/sites/_preview/{token}/**` | `publish/SiteControllers.kt:77` | NONE | By-design | public / capability (audit §3.1) |
| 199 | GET | `/sites/_app/{token}/**` | `publish/SiteControllers.kt:86` | NONE | By-design | public / capability (audit §3.1) |
| 200 | GET | `/sites/_host/**` | `publish/SiteControllers.kt:97` | NONE | By-design | public / capability (audit §3.1) |
| 201 | GET | `/sites/{slug}` | `publish/SiteControllers.kt:105` | NONE | By-design | public / capability (audit §3.1) |
| 202 | GET | `/sites/{slug}/**` | `publish/SiteControllers.kt:110` | NONE | By-design | public / capability (audit §3.1) |
| 203 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/site` | `publish/SiteControllers.kt:219` | forProject | self | Protected | forProject | self |
| 204 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/site/rollback` | `publish/SiteControllers.kt:227` | forProject | perm:PROJECT_PUBLISH | self | Protected | forProject | perm:PROJECT_PUBLISH | self |
| 205 | DELETE | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/site` | `publish/SiteControllers.kt:244` | forProject | perm:PROJECT_PUBLISH | self | Protected | forProject | perm:PROJECT_PUBLISH | self |
| 206 | POST | `/api/v1/sites/{slug}/access-ticket` | `publish/SiteControllers.kt:255` | forProject | perm:PROJECT_READ | self | Protected | forProject | perm:PROJECT_READ | self |
| 207 | GET | `/api/v1/workspaces/{workspaceId}/audit-events` | `audit/AuditController.kt:22` | forWorkspace | perm:AUDIT_READ | self | Protected | forWorkspace | perm:AUDIT_READ | self |
| 208 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/prompts` | `prompt/PromptController.kt:166` | NONE | Protected | forProject in service |
| 209 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/prompts/stream` | `prompt/PromptController.kt:173` | self | Protected | owner / visibility check (self) |
| 210 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/prompts` | `prompt/PromptController.kt:228` | forProject | self | Protected | forProject | self |
| 211 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/schema` | `version/SchemaVersionController.kt:60` | forProject | self | Protected | forProject | self |
| 212 | PATCH | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/schema` | `version/SchemaVersionController.kt:68` | forProject | perm:PROJECT_EDIT | self | Protected | forProject | perm:PROJECT_EDIT | self |
| 213 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/versions` | `version/SchemaVersionController.kt:91` | forProject | self | Protected | forProject | self |
| 214 | GET | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/versions/{versionId}` | `version/SchemaVersionController.kt:101` | forProject | self | Protected | forProject | self |
| 215 | POST | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/versions/{versionId}/restore` | `version/SchemaVersionController.kt:110` | forProject | perm:PROJECT_EDIT | self | Protected | forProject | perm:PROJECT_EDIT | self |
| 216 | GET | `/api/v1/ai/status` | `ai/AiController.kt:24` | self | Suspect | Suspect S4 / B-C1-04 |
| 217 | GET | `/api/v1/admin/alerts` | `ai/AiGovernance.kt:52` | admin | self | Protected | AdminGuard (system admin) |
| 218 | POST | `/api/v1/admin/alerts/{id}/acknowledge` | `ai/AiGovernance.kt:58` | admin | self | Protected | AdminGuard (system admin) |
| 219 | GET | `/api/v1/admin/ai/access` | `ai/AiGovernance.kt:134` | admin | self | Protected | AdminGuard (system admin) |
| 220 | POST | `/api/v1/admin/ai/access` | `ai/AiGovernance.kt:147` | admin | self | Protected | AdminGuard (system admin) |
| 221 | DELETE | `/api/v1/admin/ai/access/{id}` | `ai/AiGovernance.kt:172` | admin | self | Protected | AdminGuard (system admin) |
| 222 | GET | `/api/v1/admin/ai/access/effective` | `ai/AiGovernance.kt:182` | admin | self | Protected | AdminGuard (system admin) |
| 223 | GET | `/api/v1/admin/ai/budgets` | `ai/AiGovernance.kt:287` | admin | self | Protected | AdminGuard (system admin) |
| 224 | PUT | `/api/v1/admin/ai/budgets` | `ai/AiGovernance.kt:290` | admin | self | Protected | AdminGuard (system admin) |
| 225 | DELETE | `/api/v1/admin/ai/budgets/{id}` | `ai/AiGovernance.kt:311` | admin | self | Protected | AdminGuard (system admin) |
| 226 | POST | `/api/v1/ai/streams/{id}/cancel` | `ai/AiStreams.kt:98` | self | Protected | owner / visibility check (self) |
| 227 | GET | `/api/v1/admin/backups` | `maintenance/Backups.kt:67` | admin | self | Protected | AdminGuard (system admin) |

Tổng theo phân loại: By-design 45, Protected 174, Suspect 8.

Ghi chú: cột *Guard (script)* là dấu hiệu tự động (`self` = dùng `me.userId`); các dòng `forProject` không có `perm:` đã được đọc tay (quyền nằm trong helper `ctx()` hoặc là route chỉ đọc `PROJECT_READ`).

