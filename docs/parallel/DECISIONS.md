# DECISIONS — log quyết định chung

Thay đổi contract chung (`docs/contracts/**`), ranh giới ownership hoặc quy tắc cấp migration **phải** ghi ở đây trước khi sửa. Định dạng: ID · ngày · người đề xuất · trạng thái (PROPOSED / ACCEPTED / REJECTED) · nội dung · hệ quả.

## D-001 — Giữ Modular Monolith · ACCEPTED · 2026-10-05 · C0
Không chuyển microservices. Module mới là package mới trong `com.systemwebstudio`. Code cũ không bị di chuyển/đổi tên trong Phase 0.

## D-002 — Gán mặc định cho module legacy không có owner · PROPOSED · 2026-10-05 · C0
`member→C1`; `prompt, ai, integration/llm, asset→C2`; `publish, runtime, code, integration/{deploy,git,storage,queue,secrets}, audit, admin, settings, maintenance, common→C0-gated`. Cần chủ dự án xác nhận.

## D-003 — SSRF guard & connector hiện có nằm trong `runtime/Gateway.kt` · PROPOSED · 2026-10-05 · C0
`PublicAddress` (SSRF guard) và `ConnectorProxyController`/`AdminConnectorController` dùng chung file với `AppGatewayController`. Đề xuất: C3 **không sửa** file này; DataConnector mới gọi `PublicAddress.isPublic` (read-only). Nếu cần dùng chung sâu, C0 sẽ làm một task riêng trích `PublicAddress` sang `common/` (không đổi hành vi). Ngoài scope Phase 0.

## D-004 — Frontend ở root repo, không có `frontend/` · ACCEPTED · 2026-10-05 · C0
Ownership C5 ánh xạ: `frontend/features/**→features/**`, `frontend/lib/...→lib/...`. Không tạo thư mục `frontend/` và không di chuyển file.

## D-005 — API client frontend dùng chung · PROPOSED · 2026-10-05 · C0
`lib/http-api.ts` và `lib/http-types.ts` là hot file của C5. Agent khác thêm client mới ở `lib/api/<domain>.ts` (tạo mới, do agent đó sở hữu) hoặc yêu cầu C5.

## D-006 — Migration không liên tục · SUPERSEDED bởi D-007 · 2026-10-05 · C0
Quyết định ban đầu (phân vùng số migration cố định theo agent) đã bị thay thế; giữ lại làm lịch sử. Quy tắc hiện hành: D-007 và `OWNERSHIP.md §6`.

## D-007 — C0 cấp số Flyway migration · ACCEPTED · 2026-10-05 · C0
Agent không tự chọn Flyway migration version. Khi cần migration, agent ghi request vào `docs/parallel/BOARD.md` (mục *Migration requests*); C0 cấp next available version (V26, V27, …). Một version gắn với đúng một task. Migration được merge theo thứ tự tăng dần. Không bật `outOfOrder=true`. Chi tiết: `OWNERSHIP.md §6`.

## D-C0-10 — Web-only V2 preparation applied on integration/v2 (uncompiled) · ACCEPTED · 2026-10-06 · C0
C0 applied its own shared patches (PublicAddress canonical policy, single-route webhook security, exact-origin CORS guard, explicit OFF flags, `transfer-ownership` self-grant 403) and froze contract updates A–D (`ActionDef.trigger?`, mapping `transforms[]` canonical, 14 permission codes kept, data-mutation 409 `IDEMPOTENCY_OUTCOME_UNKNOWN` / 422 `MUTATION_REJECTED`). No C1–C4 code imported; no migration added. Nothing compiled or run: verification = `MAC_INTEGRATION_CHECKLIST.md`.

## D-C0-11 — Wiring skeleton is non-compiled until the agent code is imported · ACCEPTED · 2026-10-06 · C0
`backend/src/wiring-skeleton/*.kt.skel` is not a source set and holds typed TODO adapters only (no fake implementations). It moves to `backend/src/main/kotlin/com/systemwebstudio/wiring/` as separate commits after the import steps S4–S16 are green. Only `wiring/WebOrigins.kt` is live.

## D-C0-12 — Frontend monorepo, three deployments (ADR 0022) · ACCEPTED · 2026-10-06 · C0
Records C5's replacement of ADR 0001/0002: one monorepo, three frontend deployments (platform/admin/studio), shared packages, one modular-monolith backend, no microservices. C5 code import follows the backend gate (checklist S17b).
## D-C1-11 — SYSTEM_ADMIN là platform scope; business-data bypass chỉ sau cờ · ACCEPTED (C0) · 2026-10-05 · C1
Mặc định (`app.tenancy.system-admin-business-access=false`): SYSTEM_ADMIN không có quyền business-data của workspace mà nó không là thành viên. Ở workspace không là member nó giữ `PermissionMatrix.platformScope` = `MEMBER_MANAGE`, `PROJECT_CREATE` (chỉ để giữ 409 `ADMIN_NOT_MEMBER`), `TENANT_MANAGE`, `TENANT_MEMBERS`. Là member thì chỉ có quyền của role member. Cờ `true` khôi phục hành vi cũ (mọi quyền). Nơi còn bypass: `docs/parallel/c1/T2-tenant-foundation.md` §4. Cờ phải khai báo `false` trong `application.yml` (C0).

## D-C1-12 — `tenant_members` là source of truth cho tenant role · ACCEPTED (C0) · 2026-10-05 · C1
TENANT_ADMIN/MEMBER ở `tenant_members`, không ở `workspace_members`; một user có thể thuộc nhiều tenant. TENANT_ADMIN chỉ có `TENANT_MANAGE`/`TENANT_MEMBERS` trên tenant của mình, không có quyền business implicit. Trigger giữ bất biến "workspace member active ⇒ tenant member (MEMBER)" (insert, kích hoạt lại, đổi workspace, đổi tenant của workspace); hàng tenant_members đã bị tắt không bao giờ bị bật lại tự động. Không có hàng ⇒ coi như MEMBER (fail-open có chủ đích cho rollout).

## D-C1-13 — Compatibility V26 có kế hoạch gỡ · ACCEPTED · 2026-10-06 · C1
`DEFAULT` của `workspaces.tenant_id` và 3 trigger `*_tenant_fill` chỉ là scaffolding. Trigger **từ chối** `tenant_id` khác tenant của workspace (không ghi đè im lặng), chỉ điền khi NULL. Gỡ bằng migration `tenant_compat_removal` (BOARD, chưa có số) khi `TenantInsertPathsGrepTest` hết PENDING. Runbook §4.

## D-C1-14 — Ma trận quyền canonical (default deny) · PROPOSED (C0 gộp vào contract) · 2026-10-06 · C1
Role matrix thực thi trong `Permission.kt` (`docs/parallel/c1/permission-matrix-v2.md`): `APP_USE`/`DATA_SOURCE_VIEW`/`QUERY_EXECUTE`/`ACTION_EXECUTE` cho project EDITOR/OWNER (VIEWER/PUBLISHER chỉ `APP_USE`); `DATA_SOURCE_MANAGE`/`DATA_MUTATE`/`WORKFLOW_EXECUTE`/`WORKFLOW_MANAGE` chỉ WORKSPACE_ADMIN (chưa có cơ chế grant tường minh trước T15). VIEWER không có `QUERY_EXECUTE` (cần trạng thái published). `APP_SHARE` tạm = `PROJECT_MEMBERS`. Mã ngoài `PermissionCodes.CANONICAL` bị từ chối ở biên.

## D-C1-15 — Không ai tự cấp quyền cho mình (đóng R-08) · ACCEPTED · 2026-10-06 · C1
Thêm chính mình vào workspace/project/tenant, hoặc đổi role của chính mình, bị từ chối với 403 `SELF_GRANT_FORBIDDEN` cho MỌI caller (kể cả SYSTEM_ADMIN, TENANT_ADMIN, WORKSPACE_ADMIN); tự rời vẫn được. Người khác (admin khác) vẫn cấp được. Việc ngoài ownership C1: `admin/AdminController.transfer-ownership` (B-C1-13).

## D-C1-16 — Adapter C1 là lõi policy thuần, C0 nối bằng `wiring.*Adapter` · ACCEPTED · 2026-10-06 · C1
`access/adapters/{GatewayAuthorizer,AccessPort,TenantGate,PrincipalResolver}` nhận kiểu thuần (`Principal`, id, tên operation dạng string), không import `data.*`/`logic.*` (layering). Default deny: chỉ actor USER được phép; SYSTEM/SERVICE/APP_TOKEN bị từ chối cho tới khi C0/C4 quyết định (B-C1-17). Tenant luôn được suy ra từ workspace và so với tenant caller khai.
