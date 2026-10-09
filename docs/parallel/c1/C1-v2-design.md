# C1 V2 — Tenancy / Identity / Permission / Sharing / Security: thiết kế & kế hoạch

Owner: C1 · Nhánh `agent/c1-tenancy` · Nền: T1 (`docs/parallel/audit/T1-isolation-audit.md`) · Trạng thái: **DESIGN — chưa có code V2**.
Quyết định kèm theo: `DECISIONS.md` (D-C1-01 … D-C1-09). Blocker: `BLOCKERS.md` (B-C1-xx). Yêu cầu migration: `BOARD.md` mục *Migration requests*.

## 0. Nguyên tắc
1. Backward compatible: `AccessService.forWorkspace/forProject`, `AccessContext`, `PermissionMatrix`, API và status code hiện tại **không đổi**. Mọi thứ mới là bổ sung có default.
2. Luồng bắt buộc: `authenticate → resolve tenant → resolve resource → authorize → execute → audit`. Tenant luôn suy ra **server-side** (từ workspace/project trong path, hoặc session) — không bao giờ từ query/body/header do client điều khiển.
3. Mặc định **deny**; ngoài phạm vi → **404**, thiếu quyền → **403** (quy ước T1).
4. Ứng dụng (AccessService) là lớp bảo vệ chính; RLS là lớp thứ hai (defense in depth).
5. Mỗi subtask commit riêng; migration chỉ tạo **sau khi C0 cấp version**.

## 1. Mô hình tenant (T2)

```
Platform (SYSTEM_ADMIN)
 └─ Tenant ──┬─ TenantMember (TENANT_ADMIN | MEMBER)
             ├─ Department (cây) ── DepartmentMember / DepartmentManager
             ├─ Group ── GroupMember
             └─ Workspace ── WorkspaceMember ── Project ── ProjectMember
```
- `Workspace ≠ Tenant`. Workspace luôn thuộc đúng một tenant (`workspaces.tenant_id NOT NULL`).
- Dữ liệu cũ: một tenant `DEFAULT` (id cố định, slug `default`) được tạo trong migration; mọi workspace hiện có gán vào đó (backfill trước, `NOT NULL` sau).
- `users` là danh tính toàn cục (username duy nhất toàn hệ thống — giữ nguyên). Quan hệ user–tenant = `tenant_members` (một user có thể ở nhiều tenant). Một user cũng được coi là thuộc tenant nếu có `workspace_members` active trong workspace của tenant đó (suy ra, không cần backfill `tenant_members` cho thành viên thường; chỉ `TENANT_ADMIN` cần hàng tường minh).
- Code (package `com.systemwebstudio.tenancy`): `Tenant`, `TenantStatus {ACTIVE, SUSPENDED, DELETED}`, `TenantContext` (theo `docs/contracts/tenant-context.md`), `TenantResolver`, `TenantRepository`, `TenantService`.
- `TenantResolver`: `fromWorkspace(workspaceId)`, `fromProject(projectId)`; **không** có hàm nhận tenantId từ client. Tenant `SUSPENDED` → 404 cho mọi user trừ `SYSTEM_ADMIN`.
- Quy tắc truy vấn: repository mới **bắt buộc** nhận `tenantId`; không có "find by id" trần cho dữ liệu tenant-scoped (kiểm tra bằng test kiến trúc ArchUnit-style/grep trong CI cục bộ).

### 1.1 Bảng cần `tenant_id` (phân tầng)
| Tầng | Bảng | Cách backfill |
|---|---|---|
| Gốc | `workspaces` | gán DEFAULT |
| Phái sinh (denormalize để RLS rẻ) — tầng 1 | `workspace_members`, `projects`, `project_members` | `UPDATE … FROM workspaces` |
| Phái sinh — tầng 2 | `assets`, `deployments`, `sites`, `site_domains`, `form_submissions`, `repositories`, `code_changes`, `page_schemas`, `project_versions`, `prompts`, `prompt_runs`, `idempotency_keys`(không), `app_runtimes`, `project_secrets` | join qua `project_id` |
| Chỉ-thêm (append-only, không backfill được vì trigger) | `audit_events` | cột nullable; ghi từ thời điểm triển khai; đọc cũ suy ra qua `workspace_id` (xem §8) |
| Toàn cục hiện nay → tenant hoá ở T5 | `templates`, `component_packages(+versions, reviews)`, `connectors`, `project_connectors`, `ai_providers`, `ai_limit_overrides`, `ai_budgets`, `ai_model_access`, `ai_model_policies`, `system_settings`, `departments`, `scim_*`, `git_access*` | xem §5 |
| Giữ toàn cục (platform-owned) | `users`, `external_identities`, `components`, `component_versions`, `cost_prices`, `approved_packages`, `admin_alerts` | không thêm tenant_id |

### 1.2 Migration an toàn (mô hình expand → backfill → contract) + rollback
Một migration/nhóm nhưng theo 4 bước, trong một transaction cho bảng nhỏ; bảng lớn (`audit_events`, `ai_calls`, `form_submissions`, `deployment_events`) chỉ thêm cột nullable (không bao giờ khoá bảng để `NOT NULL`).
1. `CREATE TABLE tenants`; `INSERT` tenant DEFAULT (id cố định) — idempotent (`ON CONFLICT DO NOTHING`).
2. `ALTER TABLE … ADD COLUMN tenant_id UUID` (nullable, chưa FK ép buộc).
3. Backfill: `UPDATE … SET tenant_id = <DEFAULT>` / join; kiểm `SELECT count(*) WHERE tenant_id IS NULL = 0` bằng `DO $$ … RAISE EXCEPTION` (migration fail nếu còn NULL).
4. `SET NOT NULL`, thêm FK `REFERENCES tenants(id)`, index `(tenant_id, …)`.
Rollback/runbook (`docs/parallel/c1/runbook-tenant-migration.md`, viết cùng migration): trước khi chạy → `pg_dump` + kiểm tra số dòng; Flyway không hỗ trợ down → file `U<version>__undo.sql` (kèm, không tự chạy) gỡ FK/NOT NULL/cột/bảng theo thứ tự ngược; app tương thích **cả hai chiều** trong một release (code đọc `tenant_id` nhưng không phụ thuộc vào nó cho kết quả cũ), nên rollback code không cần rollback DB.

## 2. Vai trò & AccessContext (T3)

Hiện tại: `users.system_admin` (platform), workspace role `WORKSPACE_ADMIN|EDITOR|PUBLISHER|VIEWER`, project role `OWNER|EDITOR|PUBLISHER|VIEWER`.
Thêm (D-C1-02):
- `SYSTEM_ADMIN` = `users.system_admin` (giữ nguyên nguồn). Phạm vi platform; **không** mặc định đọc dữ liệu nghiệp vụ tenant ở API tenant-scoped ngoài hành vi cũ (giữ bypass `forWorkspace` hiện tại để không phá API; ghi audit có `tenant_id`; có cờ cấu hình để siết lại ở bản sau).
- `TENANT_ADMIN` = hàng `tenant_members(role='TENANT_ADMIN')`. Quyền: tất cả quyền `WORKSPACE_ADMIN` trên **mọi workspace của tenant mình** + quản trị department/group/share/cross-tenant của tenant. **Không** có quyền gì ở tenant khác (kiểm tra bằng `workspace.tenant_id == ctx.tenantId`).
- `WORKSPACE_ADMIN`, `EDITOR`, `PUBLISHER`, `VIEWER` giữ nguyên.
- `AccessContext` thêm (đều có default, chữ ký/`copy` cũ chạy được): `tenantId: UUID? = null`, `roles: Set<String> = emptySet()`, `shareGrants: Set<…>` nội bộ. `userId`, `workspaceId`, `projectId`, `permissions` đã có (qua `user`, `workspaceId`, `project`).
- `AccessService.forTenant(userId, tenantId)` mới (resolve từ resource, không từ client); `forWorkspace/forProject` bổ sung: resolve tenant từ workspace rồi tính quyền hiệu lực = quyền cũ ∪ quyền `TENANT_ADMIN` (nếu có) ∪ quyền từ share/group (§5/§6). Thứ tự ưu tiên ở §6.2.
- Permission enum bổ sung (D-C1-03): `TENANT_MANAGE`, `TENANT_MEMBERS`, ~~`DEPARTMENT_MANAGE`~~ (never implemented; superseded by `ORG_STRUCTURE_VIEW/MANAGE`, `EMPLOYEE_VIEW/MANAGE`, `POSITION_GRADE_VIEW/MANAGE`, see final-iam-tenant-org-permission-contract.md), `GROUP_MANAGE`, `SHARE_MANAGE`, `SHARE_CROSS_TENANT`, `DATASOURCE_READ`, `DATASOURCE_MANAGE`, `QUERY_EXECUTE`, `ACTION_EXECUTE`, `WORKFLOW_MANAGE` (hai nhóm sau chỉ khai báo cho C3/C4, chưa có hành vi). Không đổi nghĩa permission cũ.

## 3. Department / Group (T4)
- `departments` đã có (V20: cây `parent_id`, `kind DEPARTMENT|TEAM`, *không* cấp quyền). Thêm `tenant_id`; tên duy nhất theo `(tenant_id, parent_id, lower(name))`; chống vòng lặp parent bằng kiểm tra ở service + CHECK không tự tham chiếu (có sẵn) + test.
- Mới: `department_members(department_id, user_id)`, `department_managers(department_id, user_id)`.
- **Department Manager** chỉ quản lý (thêm/bớt thành viên, xem báo cáo, chia sẻ resource *của* department) trong cây con của department được giao; không phải `TENANT_ADMIN`; không tự cấp quyền ngoài phạm vi. Membership department **không** đồng nghĩa permission (đúng V20).
- `groups(id, tenant_id, name, source MANUAL|SCIM)`, `group_members(group_id, user_id)`; một user nhiều group. **Quyền qua group = qua `resource_shares` với `grantee_type=GROUP`** (không có role ngầm theo group).
- Tương thích SCIM (V21): `scim_groups`/`scim_group_mappings` giữ nguyên; `SCIM group → workspace role` (cơ chế cũ) vẫn chạy. Thêm cầu nối tuỳ chọn: nhóm SCIM đồng bộ thành `groups(source='SCIM')` để dùng cho share; không thay đổi `syncWorkspace`.

## 4. Tenant hoá tài nguyên toàn cục (T5)
Nguyên tắc `scope = PLATFORM | TENANT` (D-C1-04): bảng có cột `scope` và `tenant_id NULL` khi `PLATFORM`; `CHECK ((scope='PLATFORM') = (tenant_id IS NULL))`.
| Tài nguyên | Quy tắc |
|---|---|
| `ai_providers` (+ khoá API) | `PLATFORM`: SYSTEM_ADMIN cấu hình mặc định. `TENANT`: TENANT_ADMIN BYOK. Khoá mã hoá (SecretsCrypto), **không bao giờ** trả về cho client (đã có hành vi write-only — giữ). Tenant dùng provider TENANT nếu có, ngược lại PLATFORM nếu được phép. |
| `ai_limit_overrides`, `ai_budgets`, `ai_model_access` | thêm `tenant_id`; ngân sách theo tenant; PLATFORM-limit là trần. |
| `templates`/`component_packages` `COMPANY` | `visibility='COMPANY'` → `TENANT` (chỉ trong tenant); thêm `PLATFORM` (do Super Admin publish). Ẩn `source_project_id` (B-C1-06). |
| `connectors`/`project_connectors` | connector `scope`; tenant-owned connector chỉ grant được cho project cùng tenant. (C3 sở hữu data/**; `runtime/Gateway.kt` C0-gated → B-C1-xx.) |
| `system_settings` | key theo `scope`; `tenant_settings(tenant_id, key, value)` mới cho policy tenant; key platform-only được liệt kê tường minh. |
| `departments` | thuộc §3. |
| audit | `tenant_id` (§8). |
Phần sửa trong `ai/**` (C2), `template/**` (C2), `runtime/**`, `settings/**`, `admin/**` (C0-gated) **không do C1 sửa**: C1 chuẩn bị bảng (migration), interface `TenantScope` và request patch → blocker B-C1-xx.

## 5. Resource sharing (T15)
```
resource_shares(
  id, tenant_id,            -- tenant SỞ HỮU resource
  resource_type, resource_id,
  grantee_type  USER|GROUP|DEPARTMENT|TENANT,
  grantee_id,               -- user/group/department id hoặc tenant id (khi TENANT)
  permission    VIEW|USE|EDIT|PUBLISH|SHARE|ADMIN,
  effect        ALLOW|DENY,   -- DENY: hỗ trợ, có precedence (§6.2)
  status        ACTIVE|REVOKED|EXPIRED,
  expires_at, created_by, created_at, revoked_at, revoked_by,
  source_share_id NULL      -- liên kết tới cross_tenant_shares (§7)
)
```
- Mặc định **PRIVATE / DEFAULT DENY**: không có share + không có membership → không có quyền.
- `project_members` cũ **vẫn là nguồn hợp lệ** (compatibility adapter): khi tính quyền, vai trò project cũ được xem là grant ngầm `USER` ở mức tương ứng (VIEWER→VIEW, EDITOR→EDIT, PUBLISHER→PUBLISH, OWNER→ADMIN). Không migrate dữ liệu ở bản đầu; có công cụ `backfill` tuỳ chọn tạo share từ `project_members` sau (idempotent) → "migration path".
- Thu hồi **tức thì**: không cache quyền dài hạn; cache (nếu có) key theo `share.updated_at` hoặc TTL ≤ vài giây + xoá khi revoke. `expires_at` kiểm tại thời điểm truy cập (không dựa job dọn).
- Ánh xạ `share permission → Permission`: VIEW→`PROJECT_READ`; USE→`PROJECT_READ`+`QUERY_EXECUTE/ACTION_EXECUTE`; EDIT→`PROJECT_EDIT`; PUBLISH→`PROJECT_PUBLISH`; SHARE→`SHARE_MANAGE`; ADMIN→tất cả quyền project trừ `PROJECT_DELETE` (cần OWNER). Kết quả cuối cùng vẫn là tập `Permission` trong `AccessContext` (một nguồn sự thật).

### 5.1 Thứ tự ưu tiên (D-C1-05)
1. Tenant không khớp / user không thuộc tenant → **404**, dừng (không có gì thắng được bước này, kể cả share DENY/ALLOW).
2. User disabled → 401.
3. Project ARCHIVED → chỉ `PROJECT_READ`, `AUDIT_READ` (áp **sau** khi gộp, như hiện nay).
4. **Explicit DENY thắng ALLOW** ở mọi cấp (một DENY khớp user/group/department của user cho đúng permission → loại permission đó, kể cả khi vai trò cho phép — trừ `SYSTEM_ADMIN`, và trừ việc không thể DENY toàn bộ quyền của `TENANT_ADMIN`/OWNER cuối cùng để tránh khoá tài nguyên).
5. Gộp ALLOW: quyền = quyền vai trò workspace ∪ quyền vai trò project (cũ) ∪ quyền `TENANT_ADMIN` (trong tenant) ∪ share ALLOW còn hiệu lực (user ∪ group ∪ department ancestors ∪ tenant-wide) — hợp (union), không có "mức cao nhất thắng" mơ hồ.
6. Không có gì → deny (404 nếu không có `PROJECT_READ`, 403 nếu có READ nhưng thiếu quyền yêu cầu).

## 6. Chia sẻ chéo tenant (T16)
`cross_tenant_shares(id, source_tenant_id, target_tenant_id, resource_type, resource_id, permission, status, requested_by, source_approved_by, target_accepted_by, requested_at, source_approved_at, target_accepted_at, expires_at, revoked_at, revoked_by, reject_reason)`.

Máy trạng thái (mọi chuyển trạng thái ghi audit, có `approval_id`):
```
REQUESTED ──(Admin A approve)──▶ SOURCE_APPROVED ──(Admin B accept)──▶ TARGET_ACCEPTED ─▶ ACTIVE
    │                                  │                                     
    └──────────(reject)───────▶ REJECTED ◀──────(reject)───────┘
ACTIVE ──(revoke bởi A hoặc B)──▶ REVOKED          ACTIVE ──(expires_at)──▶ EXPIRED
```
- Chỉ `TENANT_ADMIN` của đúng tenant đó được approve (A) / accept (B); người yêu cầu **không** tự approve (tách nhiệm vụ).
- `ACTIVE` ⇒ tạo `resource_shares(grantee_type=TENANT, grantee_id=target, source_share_id=…)` trong tenant nguồn; **không copy dữ liệu**, owner vẫn ở tenant nguồn. Truy cập của tenant đích đi qua `forResource` và luôn kiểm tra: share ACTIVE chưa hết hạn **và** cross-tenant record ACTIVE (hai lớp). Tenant C: không có bản ghi → 404.
- Thu hồi/hết hạn có hiệu lực ngay khi đọc (kiểm `status`, `expires_at` tại thời điểm truy cập); job nền chỉ để chuyển nhãn `EXPIRED` + audit.
- Resource chia sẻ chéo tenant chỉ mở quyền tối đa `USE`/`EDIT` đã duyệt; `SHARE` lại cho tenant thứ ba bị cấm (không bắc cầu).

## 7. RLS defense in depth (T18)
- Mẫu: trong mỗi transaction nghiệp vụ `SET LOCAL app.tenant_id = '<uuid>'` (và `app.actor_id`); policy `USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)` + `WITH CHECK` cùng điều kiện. Thiếu GUC → `NULL` → **không trả hàng nào** (fail-safe).
- `ALTER TABLE … ENABLE ROW LEVEL SECURITY; … FORCE ROW LEVEL SECURITY;` (chủ bảng cũng bị áp, trừ khi có policy bypass).
- Job hệ thống / migration / SCIM / retention: policy `… OR current_setting('app.bypass_rls', true) = 'on'`, chỉ bật bằng `SET LOCAL app.bypass_rls = 'on'` trong code của một lớp duy nhất (`SystemTenantScope`), được audit; migration Flyway chạy bằng role chủ bảng có `BYPASSRLS` (không phải role app).
- Không áp lên bảng platform-global (`users`, `components`, `cost_prices`, …) ngoài `users` thì không; bảng `PLATFORM|TENANT` dùng policy `scope='PLATFORM' OR tenant_id = …`.
- **Điều kiện tiên quyết kỹ thuật** (B-C1-08): cần hook đặt GUC ở mức transaction (`TransactionManager`/`DataSource` wrapper). Đây là cấu hình dùng chung (nằm ngoài ownership) → cần C0 duyệt; query JDBC ngoài transaction sẽ không có GUC. Triển khai theo 3 pha: (a) bảng **mới** (shares, groups, cross_tenant_shares) bật RLS ngay; (b) bảng cũ tầng 1 ở chế độ `shadow` (policy cho phép khi GUC chưa đặt + log) đo phạm vi code chưa chạy trong tenant-tx; (c) `enforce` sau khi test hồi quy toàn bộ xanh (cờ `app.tenancy.rls.enforce`).
- Test bắt buộc: tenant A không thấy hàng tenant B (kể cả `SELECT … WHERE id = <id của B>`); không GUC → 0 hàng; INSERT sai tenant bị `WITH CHECK` chặn; job hệ thống có bypass rõ ràng và được audit.

## 8. Audit V2 (T19)
- Thêm vào `audit_events`: `tenant_id UUID NULL`, `outcome VARCHAR(16)` (`SUCCESS|DENIED|FAILED`, mặc định `SUCCESS` để dòng cũ hợp lệ), `approval_id UUID NULL`; `resource_type/resource_id` đã có. Giữ trigger append-only (kể cả `TRUNCATE`).
- Không backfill dòng cũ (trigger cấm UPDATE): dòng cũ có `tenant_id NULL`; truy vấn tenant dùng `COALESCE(tenant_id, (SELECT tenant_id FROM workspaces WHERE id = workspace_id))` hoặc view `audit_events_v2`. Dòng mới luôn có `tenant_id` do `AuditService` (C0-gated: `audit/**` thuộc C0; C1 đề xuất patch / interface `AuditContext`).
- Retention/partition: `audit_events` → partition theo tháng (`PARTITION BY RANGE (created_at)`) cần tạo lại bảng → yêu cầu migration riêng + runbook (copy `INSERT … SELECT` được phép vì là bảng mới; trigger gắn lại; bảng cũ giữ read-only đến hết retention). Không DROP dữ liệu audit; chỉ DETACH + archive partition theo chính sách (`audit_retention_months`, mặc định không xoá).
- Mọi approval/revoke/expire cross-tenant và share ghi audit với `tenant_id` của **cả hai** phía (hai dòng, cùng `approval_id`).

## 9. Secret / key rotation — phần security (T20)
`SecretsCrypto` hiện nằm ở `runtime/RuntimeSupport.kt` (C0-gated) → C1 **không sửa**; đây là thiết kế + test + patch đề xuất (B-C1-07).
- Định dạng: `v1:` (hiện tại, khoá `SECRETS_MASTER_KEY`) → `v2:<keyId>:base64(iv||ct||tag)`; AAD = `purpose|tenantId|rowId` để tách ngữ cảnh (ciphertext của tenant A không giải mã được khi bị copy sang hàng của tenant B).
- Key ring: `app.secrets.keys[keyId]=…`, `app.secrets.active-key-id`; **decrypt** thử theo `keyId` (v2) hoặc khoá v1 cũ; **encrypt** luôn dùng khoá active.
- Re-encrypt nền: job quét theo lô các cột bí mật (`project_secrets`, `connectors`, `ai_providers`, …), giải mã bằng khoá cũ, mã hoá bằng khoá mới, `UPDATE … WHERE ciphertext = <cũ>` (CAS, không mất dữ liệu nếu ghi đồng thời); báo cáo tiến độ; khoá cũ chỉ được gỡ khi `count(v1 hoặc keyId cũ) = 0`.
- Interface `KeyProvider { fun key(id): ByteArray; fun activeId(): String }` với `EnvKeyProvider` (mặc định) và chỗ cắm `KmsKeyProvider` (tuỳ chọn, chưa triển khai).
- Test bảo mật: round-trip v1→v2; giải mã v1 còn chạy sau khi xoay; AAD sai → từ chối; bản ghi tenant A đưa sang tenant B không giải mã được; xoay giữa lúc ghi đồng thời không mất credential.

## 10. Lộ trình & phụ thuộc
| Task | Phụ thuộc | Cần |
|---|---|---|
| T2 Tenant foundation | — | migration (V cấp bởi C0) |
| T3 Roles/AccessContext | T2 | — |
| T4 Department/Group | T2 | migration |
| T15 Resource sharing | T2, T3, T4 | migration |
| T16 Cross-tenant | T15 | migration |
| T5 Tenant hoá global | T2, T3 | migration + patch ngoài ownership (C0/C2) |
| T19 Audit V2 | T2 | migration + patch `audit/**` (C0) |
| T18 RLS | T2, T5, T15 | migration + hook transaction (C0) |
| T20 Secret rotation | — | patch `SecretsCrypto` (C0) |

Môi trường kiểm thử: mọi task trên cần `cd backend && ./gradlew test` thật (Testcontainers: Docker + JDK 21 + Maven Central). Môi trường hiện tại của C1 **không có** (B-C1-09) → không thể tuyên bố PASS.
