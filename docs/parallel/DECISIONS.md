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
## D-C3-00a (was D-008; renamed by C0 at import: the id collides with the C2 branch) — DataConnector foundation: bổ sung contract và chính sách bảo mật · SUPERSEDED bởi D-C3-01…03 (giữ làm lịch sử) · 2026-10-05 · C3
Bổ sung so với `docs/contracts/data-connector.md` (chưa sửa file contract; C0 quyết định có đưa vào contract không):
- `DataConnector.validateConfig(configNonSecret)` — cùng validation non-secret config chạy khi tạo và khi test.
- Port không phụ thuộc DB: `DataSourceRepository`, `QueryCatalog` (+ `QueryDefinition` sealed: SQL/REST), `CredentialVault` (`SecretsCrypto`, `v1:`), `RateLimitGate` (`RateLimiter`, từ chối khi limiter lỗi), `DataAuditSink` (`AuditService`, chỉ field cố định). Cần migration (BOARD.md) cho bản persistent.
- `TenantContext(tenantId)` là placeholder cho type của C1 (B-004).
Chính sách:
- REST: chỉ GET, HTTPS, không bao giờ follow redirect (3xx → REDIRECT_BLOCKED), resolve một lần + kiểm tra mọi address + connect tới address đã ghim (không DNS rebinding), deadline tổng, cap header/body/row, chỉ JSON, credential chỉ thành header ở một chỗ.
- PostgreSQL: chỉ TLS (mặc định verify-full), session read-only nhiều lớp (`default_transaction_read_only`, `setReadOnly`, warm-up query, luôn rollback), `SqlGuard` + timeout, host public mặc định, allow-list private do server cấu hình, deny-list (DB nền tảng/apps) luôn chặn kể cả theo address đã resolve; test kết nối từ chối role superuser.
- Chưa đăng ký bean/endpoint nào; `runtime/Gateway.kt` và code connector proxy cũ không bị sửa. Stop-gap cho lỗ hổng `PublicAddress` ở B-007.
Hệ quả: C1 (permission/TenantContext), C0 (migration, wiring, B-007), C4/C5 dùng `QueryRequest`/`QueryResult`, không tự tạo SQL/URL.

## D-C3-01 — DataConnector: bổ sung contract (thay D-008) · PROPOSED · 2026-10-05 · C3
Bổ sung (chưa sửa `docs/contracts/data-connector.md`): `validateConfig`, `discovery()` (SchemaDiscovery), `ConnectorDescriptor/ConnectorSpi` cho driver sắp có (mysql, graphql, csv, google_sheets, odoo, salesforce), mã lỗi đóng `FailureCodes` (additive), port không phụ thuộc DB (`DataSourceRepository`, `QueryCatalog`, `CredentialVault`, `RateLimitGate`, `DataAuditSink`). `TenantContext` là placeholder cho type của C1 (B-C3-01).

## D-C3-02 — Credential tách riêng + DataSource.version trong cache key · PROPOSED · 2026-10-05 · C3
`DataSource` chỉ giữ `credentialRef`; material nằm ở `CredentialStore` (`SecretsCrypto` `v1:`), không nằm trong AppDefinition/response/log/audit. `DataSource.version` tăng mỗi lần đổi config/credential/status và nằm trong mọi cache key.

## D-C3-03 — Chính sách bảo mật connector · PROPOSED · 2026-10-05 · C3
Resolve-một-lần + kiểm tra mọi address + connect tới address đã ghim; REST chỉ HTTPS/GET, không redirect, TLS verify, deadline, cap body/row, rate limit; PostgreSQL chỉ TLS, read-only nhiều lớp, `SqlGuard`, `statement_timeout`, từ chối role đặc quyền, deny-list host nền tảng; `ConnectorFailure` chỉ văn bản cố định (không cause); audit chỉ id/mã/số lượng. Stop-gap `SupplementaryRanges` cho B-C3-04.

## D-C3-04 — Mapping/ViewModel contract + Transform DSL + Expression sandbox · PROPOSED · 2026-10-05 · C3
Hình dạng khớp C2 (`FieldType`, `Cardinality`, `ViewModelFieldDef`, `ViewModelDef`, `MappingDef`, `FieldMappingDef{from,to}`) + key tuỳ chọn additive (B-C3-06). Transform DSL là tập đóng; expression engine tự viết (allowlist hàm, giới hạn kích thước/độ sâu/bước, không reflection, không JavaScript); validation là tập đóng không regex; error policy FAIL/NULL_FIELD/SKIP_ROW.

## D-C3-05 — DataGateway · PROPOSED · 2026-10-05 · C3
Luồng: permission → resolve tenant-scoped → định nghĩa query **đã duyệt** → mapping/viewModel bắt buộc + validate → bind params theo định nghĩa → cache/connector → mapping → audit. Browser chỉ gửi `{dataSourceId, operation, mappingRef, viewModelRef?, params, page?}`; parser nghiêm từ chối `sql/url/headers/tenant`. Mặc định từ chối (fail-closed). C3 không viết controller; không sửa `runtime/Gateway.kt`.

## D-C3-06 — Cache · PROPOSED · 2026-10-05 · C3
Key = tenant + datasource + query + params canonical + versions (ds/query/mapping/viewmodel) + page, SHA-256; invalidate bằng generation counter (không SCAN); phong bì được kiểm tra lại khi đọc; Redis lỗi = miss; TTL theo query (0 = không cache, ≤ 86 400); invalidate lỗi ⇒ bypass phạm vi trong tiến trình ≤ 86 400s.

## D-C3-07 — Mutation scope + idempotency · PROPOSED · 2026-10-05 · C3
Mutation chỉ qua định nghĩa đã duyệt; `idempotencyKey` bắt buộc (`^[A-Za-z0-9_-]{8,128}$`); begin/complete/release theo fingerprint params (Run/Replay/InProgress/Conflict); lỗi ⇒ release; thành công ⇒ invalidate query khai báo + `RecordChanged` (chỉ khoá dạng id).
**Cập nhật Phase 3:** xem D-C3-14 (3 trạng thái RESERVED/DONE/UNKNOWN; lỗi mơ hồ KHÔNG release key).

## D-C3-08 — Sync V1 · PROPOSED · 2026-10-05 · C3
One-way pull theo lịch; lease nguyên tử + `finish` có rào chắn; checkpoint chỉ commit sau khi sink nhận mọi trang; sink idempotent; backoff có trần; tạm dừng khi lỗi vĩnh viễn/10 lần lỗi. Two-way chỉ là contract (`agents/C3_DATA_PLATFORM.md` §7).

## D-C3-09 — Webhook ingress · PROPOSED · 2026-10-05 · C3
Endpoint public không mang quyền; HMAC-SHA256 trên `"<ts>.<body>"`, tolerance ±300s, replay 24h, rate limit theo peer rồi theo endpoint, rotation grace ≤ 24h, chỉ đọc tên sự kiện + khoá bản ghi dạng id, trigger workflow chỉ bằng định danh, 503 khi hạ tầng lỗi, audit từ chối có giới hạn.
**Cập nhật Phase 3:** đường dẫn canonical là `POST /api/v1/webhooks/data/{endpointId}`; replay guard theo signature; chữ ký v2 — xem D-C3-16.

## D-C3-10 — Realtime qua SSE + vị trí package · PROPOSED · 2026-10-05 · C3
Sự kiện `DataChanged/RecordChanged/QueryInvalidated/StreamReset` chỉ là gợi ý (client refetch qua gateway); ring buffer 500/tenant, `seq` đơn điệu, `Last-Event-ID`, 100 stream/tenant, kiểm tra lại quyền. Events ở `data/cache`, webhook ingest ở `data/sync/webhook` (trong ownership `data/cache/**`, `data/sync/**`).

## D-C3-11 — Đối soát C2/C4 · PROPOSED · 2026-10-05 · C3
Không tạo data model cạnh tranh: AppDefinition (C2) chỉ tham chiếu `dataSources/queries/viewModels/mappings/bindings` bằng id/ref; C4 `ActionDataPort` gọi `DataGateway.mutate` với `idempotencyKey`. Chi tiết `agents/C3_DATA_PLATFORM.md` §10.

## D-C3-12 — Type canonical của C1 + `GatewayContext` · PROPOSED · 2026-10-06 · C3
C3 xoá placeholder `TenantContext`/`ActorKind` và dùng type C1 (`com.systemwebstudio.tenancy.*`). Mọi API runtime nhận `GatewayContext(tenant, actorUserId, actorKind, requestId, workspaceId, projectId, appVersionId)` do server (C0 adapter) dựng; không có tenant từ body/query/path. Runtime canonical: `GatewayQuery` / `GatewayMutation` (không dùng lại `QueryRequest` cho API). Sync chạy với tư cách chủ job (`ActorKind.USER`) và kiểm lại quyền `QUERY_EXECUTE` mỗi lần chạy; mất quyền ⇒ job tạm dừng, không pull gì.

## D-C3-13 — Mapping: `fields[].transforms[]` canonical · PROPOSED · 2026-10-06 · C3
Reader đọc `transforms[]`, tương thích ngược `transform` cũ, **từ chối** khi có cả hai (mơ hồ), chuẩn hoá về `transforms[]` (≤ 8). Writer chỉ ghi `transforms[]`. Hệ quả: C2 lưu/đọc đúng dạng này.

## D-C3-14 — Idempotency: trạng thái và mã lỗi mới · PROPOSED · 2026-10-06 · C3
Key do C4 derive (`base64url(sha256(...))`) được giữ nguyên xuyên suốt; không log key thô (audit chỉ có `idem` = 12 hex đầu của sha256). Trạng thái RESERVED/DONE/UNKNOWN + lease 300s (hết lease ⇒ UNKNOWN, không bao giờ chạy lại). Chỉ lỗi CHẮC CHẮN chưa ghi (allowlist `NOT_EXECUTED`) mới release key; còn lại markUnknown. Mã mới (additive): `IDEMPOTENCY_OUTCOME_UNKNOWN` (409), `MUTATION_REJECTED` (422). Ghi thành công mà lưu kết quả lỗi ⇒ vẫn trả thành công.

## D-C3-15 — Cache: ticket protocol chống ghi đè kết quả cũ · PROPOSED · 2026-10-06 · C3
`lookup` trả `CacheLookup(payload, ticket)`; ticket chốt key + generation (datasource/query) tại thời điểm đọc; `put(ticket, …)` từ chối nếu generation đã đổi (invalidate xen giữa) hoặc scope đang bypass; phong bì có fingerprint request kiểm lại khi đọc. Key luôn gồm tenant + version/fingerprint query.

## D-C3-16 — Webhook: path, chữ ký v2, replay · PROPOSED · 2026-10-06 · C3
Path canonical `POST /api/v1/webhooks/data/{endpointId}` (C0: permitAll + miễn CSRF chỉ route này). Tenant resolve phía server từ endpoint. v1 = HMAC `"<ts>.<body>"`; v2 = HMAC `"v2.<ts>.<len(delivery)>.<delivery>.<body>"` (delivery id nằm trong phần được ký, tách miền bằng tag + độ dài). Replay key luôn gồm `sig:<sha256(signature)>` (TTL 2×tolerance+60s) và, với v2, `id:<delivery>` (24h). Body ≤ 256 KiB đọc có chặn; rate limit theo peer rồi endpoint; mọi lỗi xác thực → 401 như nhau.

## D-C3-17 — PostgreSQL: TLS verify-full và preflight mỗi session · PROPOSED · 2026-10-06 · C3
Chỉ `sslmode=verify-full` + `DefaultJavaSSLFactory` (hostname/cert được kiểm); `require/verify-ca/prefer/allow/disable` bị từ chối khi validate config. MỖI session (không chỉ `test()`) chạy `PgSessionPreflight`: read-only, `standard_conforming_strings=on`, không superuser, không thể đạt superuser qua membership, không thuộc role `pg_*`, không createrole/createdb/replication/bypassrls; không có dòng ⇒ fail closed. Read-only + `statement_timeout` + `lock_timeout` + `idle_in_transaction_session_timeout` + connect/socket timeout + row limit.

## D-C3-18 — SqlGuard fail-closed · PROPOSED · 2026-10-06 · C3
Chặn `U&"…"`/`U&'…'` ở mọi vị trí, ký tự điều khiển, ký tự non-ASCII ngoài literal, comment, nhiều câu lệnh, COPY/DO/CALL/SELECT INTO/CTE ghi/RETURNING, hàm nguy hiểm (thêm `pg_notify`, `pg_wal_*`, `pg_stat_reset*`, `pg_copy_*`, `pg_show_*`, `inet_*`…) và quan hệ nhạy cảm (`pg_authid`, `pg_shadow`, `pg_settings`, `pg_stat_activity`…). Không phân tích được ⇒ từ chối.

## D-C3-19 — `AiDataCatalog` từ `AiSafeSchema` · PROPOSED · 2026-10-06 · C3
`DefaultAiDataCatalogProvider` (C3) cấp catalog cho planner của C2 (`wiring.AiDataCatalogAdapter` là của C0): chỉ metadata đã che, lọc theo quyền từng caller (`DATASOURCE_READ`, `QUERY_EXECUTE`, `MUTATION_EXECUTE`), chỉ nguồn ACTIVE, không credential/host/config/SQL, không sample thô; sample đã che chỉ khi yêu cầu rõ (≤ 3 dòng/entity). Audit chỉ có số lượng.

## D-C3-20 — Address security: bỏ `SupplementaryRanges` · PROPOSED · 2026-10-06 · C3
C3 chỉ dựa vào `PublicAddress` canonical (INTEGRATION_V2 §8) do C0 áp vào `runtime/Gateway.kt`; deny-list nền tảng luôn chặn và fail closed (mục chặn không resolve được ⇒ ADDRESS_BLOCKED). `AddressRangeSpecTests` là cổng kiểm cho patch C0 (`agents/C3_PUBLIC_ADDRESS_PATCH.md`).
