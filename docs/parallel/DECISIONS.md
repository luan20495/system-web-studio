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

## D-C4-01 — Danh sách ActionType chốt (đóng kín) · ACCEPTED (C0, imported 2026-10-06) · 2026-10-05 · C4 · sửa 2026-10-06
Thay D-009 của nhánh C4 (id này không có trên integration/v2). `NAVIGATE, REFRESH_QUERY, SUBMIT_FORM, CREATE_RECORD, UPDATE_RECORD, DELETE_RECORD, CALL_API, NOTIFY, START_WORKFLOW` — đúng 9 tên của contract v2, so khớp **chính xác**. **Sửa 2026-10-06: bỏ hoàn toàn bản đồ alias** (`RUN_QUERY`, `WRITE_DATA`, `CALL_CONNECTOR_OPERATION`, `SET_VALUE` và mọi tên khác bị từ chối; action đó không được cung cấp). `SET_VALUE` là trạng thái client, không có action phía server. Ghi dữ liệu = tham chiếu `queryRef` (query `mode=WRITE` đã khai báo trong AppDefinition, không SQL/URL/tên bảng); `CALL_API` = `dataSourceRef` + `operationKey` đã duyệt (không URL thô). `DOWNLOAD_FILE`/`OPEN_URL` chưa làm vì chưa có security model. Thêm loại mới = đổi contract, phải qua file này. Hệ quả: B-C4-02, B-C4-04.

## D-C4-02 — ActionContext của C4 + port thay cho AccessContext/ActionAuthorizer · ACCEPTED (C0, imported 2026-10-06) · 2026-10-05 · C4
Thay D-008 của nhánh C4 (id này không có trên integration/v2). `ActionRuntime.execute(ctx: ActionContext, req)`; `ActionContext(tenantId, actor, workspaceId?, projectId?, requestId?)` do server dựng (HTTP: từ `AccessContext`+`TenantContext`; worker: từ run đã lưu). Quyền đi qua `AccessPort.check(AccessRequest(permission, resourceKind, resourceId, appId, mode))`, tenant qua `TenantGate`, người nhận/approver qua `PrincipalResolver`; cả ba do C1 cấp adapter (B-C4-01). `logic.*` không import `access/**`. Hệ quả: contract `action-workflow.md` cần cập nhật chữ ký nếu đồng ý.

## D-C4-03 — Định nghĩa action/workflow đọc từ AppDefinition canonical · ACCEPTED (C0, imported 2026-10-06) · 2026-10-05 · C4
Thay D-010 của nhánh C4 (id này không có trên integration/v2). C4 **không** có bảng `action_definitions`; `ActionDefinition`/`WorkflowDefinition` được đọc từ JSON AppDefinition của C2 qua `AppDefinitionSource` (lớp `*.canonical`), theo tenant + app + mode (LIVE=bản publish, TEST=bản nháp). Tham chiếu không giải quyết được (query/dataSource/workflow/permission/action nối chuỗi) → action/workflow **không được cung cấp** (fail closed). C4 chỉ sở hữu bảng trạng thái chạy (B-C4-05).

## D-C4-04 — Mô hình thực thi workflow · ACCEPTED (C0, imported 2026-10-06) · 2026-10-05 · C4
Trạng thái nằm trong DB (CAS trên dòng run), RabbitMQ chỉ mang tín hiệu `v1|jobId|tenantId|runId|stepId` (không actor/quyền/payload). Timer, retry/backoff, job mất, worker chết, approval bị lỡ callback do **sweeper** xử lý (không cần delayed delivery của broker). Poison/quá `maxDeliveries` → DLQ → run `FAILED/DEAD_LETTERED`. Run chạy như người tạo, quyền kiểm lại ở **từng step**. Định nghĩa được snapshot vào run. `start` không chặn HTTP.

## D-C4-05 — Phạm vi idempotency · ACCEPTED (C0, imported 2026-10-06) · 2026-10-05 · C4
Action: `(tenant, app, action, user, key)`; workflow start: `(tenant, app, workflow, creator, mode, key)`; TEST và LIVE là hai phạm vi riêng. Key của step workflow: `wf:<runId>:<stepId>` (lượt thứ n≥2 của vòng lặp: `…:v<n>`). Write action bắt buộc `REQUIRED`; `NONE` trên action đổi state bị validator từ chối.

## D-C4-06 — Ngữ nghĩa TEST mode · ACCEPTED (C0, imported 2026-10-06) · 2026-10-05 · C4
TEST không commit mutation, không gửi thông báo thật, không start workflow/approval thật, không tạo run state. Phản hồi là `WouldRun(level, plan, reason)` với `level` = `NOT_EXECUTED` (mặc định) / `VALIDATED` / `SANDBOX` — chỉ nâng cấp khi downstream có dry-run tường minh (`dryRunWrite/dryRunOperation`); không giả vờ. Plan chỉ chứa tên input, không chứa giá trị.

## D-C4-07 — Ngữ nghĩa Approval · ACCEPTED (C0, imported 2026-10-06) · 2026-10-05 · C4
Approver được resolve và snapshot lúc tạo; người yêu cầu bị loại trừ mặc định; quorum `requiredApprovals`; một từ chối là từ chối cả yêu cầu; cross-tenant bị từ chối trừ khi chính sách của C1 cho phép (`CrossTenantApprovalPolicy`, mặc định DENY_ALL).

## D-C4-08 — Scheduler chỉ enqueue · ACCEPTED (C0, imported 2026-10-06) · 2026-10-05 · C4
Scheduler không gọi action/connector/notification; tick claim bằng CAS rồi giao `ScheduledRunRequest` (key `sched:<id>:<fireEpochMilli>`) cho `ScheduledRunEnqueuer` (do `WorkflowEngine` implement). Misfire: `SKIP` hoặc `FIRE_ONCE`, không bao giờ bù từng lần bị lỡ. Run lịch chạy như owner của schedule.

## D-C4-09 — Notification là port, không vendor · ACCEPTED (C0, imported 2026-10-06) · 2026-10-05 · C4
Nội dung = template đã duyệt (`templateRef`) + tham số có kiểu; webhook chỉ tới `endpointRef` đã đăng ký; vendor email/SMS là một `ChannelSender` do bên tích hợp viết. Giao hàng at-least-once khi có crash, trừ khi provider khử trùng theo `deliveryKey`.

## D-C4-10 — `ActionDef.trigger` là tùy chọn trên định nghĩa, bắt buộc chỉ với action gắn UI · ACCEPTED (C0, imported 2026-10-06) · 2026-10-06 · C4
Runtime chấp nhận `trigger{sectionId, event}` **tùy chọn**. Run UI cấp cao nhất (`TriggerKind.UI_EVENT`, `callDepth == 0`) chỉ chạy action có `trigger` và tên event khớp; workflow step, schedule và action con trong chuỗi `onSuccess`/`onError` không cần `trigger`. `trigger` có mặt nhưng sai dạng → action không dùng được (fail closed). Khớp với `ActionDef.trigger` tùy chọn của C2. **Việc còn lại cho C0** (C4 không sửa C2 hay `docs/contracts/**`): `docs/contracts/v2/action-workflow.md` §2 và `app-definition.md` viết `trigger{sectionId, event}` không có `?` — cần ghi chú `trigger?` (tùy chọn; bắt buộc chỉ khi action được gắn vào một section UI) qua một mục DECISIONS của C0.

## D-C4-11 — Idempotency key dẫn xuất; key thô không rời ActionRuntime · ACCEPTED (C0, imported 2026-10-06) · 2026-10-06 · C4
`derivedKey = base64url(sha256("<tenant>|<app>|<user>|<action>|<clientKey>"))` (43 ký tự, không padding). Chỉ key dẫn xuất được lưu (`action_runs.idempotency_key`), ghi log/audit (che bớt) và gửi cho C3/notification/workflow; retry dùng lại đúng key. `WriteRequest`/`OperationRequest` yêu cầu `appId`, `mode` và key dẫn xuất (validate trong `init`). Hệ quả: B-C4-04 (adapter C3 chuyển tiếp key dẫn xuất xuống connector).

## D-C4-12 — Một cửa dữ liệu: ActionRuntime → ActionDataPort → adapter C0 → DataGateway · ACCEPTED (C0, imported 2026-10-06) · 2026-10-06 · C4
`logic.*` không import repository/connector/JDBC/HTTP client/`access|tenancy|data|app.*` (test kiến trúc quét import); chỉ `action.handlers` dùng `ActionDataPort`. Action ghi dữ liệu cần thêm quyền `DATA_MUTATE`. Chữ ký port ghi ở `FINAL-C4-runtime-design.md` §2. Adapter (C0 wiring + `AppDataBindingResolver`) tra `queryRef`/`dataSourceRef` theo `(tenant, app, mode)` rồi gọi DataGateway của C3.

## D-C4-13 — Cách ly poison/outage và sweeper công bằng · ACCEPTED (C0, imported 2026-10-06) · 2026-10-06 · C4
Worker không bao giờ requeue ngay. Đã xử lý hoặc sự cố hạ tầng → `ack` (sweeper publish lại sau backoff, sàn 1s); message sai định dạng → DLQ; message làm worker ném exception → một lần lỗi của chính run (backoff mũ, trần 10 phút), sau `maxProcessFailures` (5) run `FAILED/DEAD_LETTERED` và **chỉ message đó** vào DLQ. Dead letter do broker → một lần lỗi, không giết run ngay. Sweeper: claim hợp nhất, cũ nhất đủ điều kiện trước, round-robin theo tenant, có chặn, một run tối đa một lần mỗi interval, run lỗi bị backoff. Hệ quả cho C0: adapter RabbitMQ không requeue từ phía worker; `delivery-limit` chỉ là lưới an toàn (B-C4-06).

## D-C4-14 — Rate limit theo tenant · ACCEPTED (C0, imported 2026-10-06) · 2026-10-06 · C4
Port `TenantRateLimiter.tryAcquire(tenant, scope, cost)` với scope `ACTION_EXECUTE`, `WORKFLOW_START`, `SCHEDULER_ENQUEUE` (token bucket; `RateLimit(capacity, refillPerSecond)`, mặc định + override theo tenant). Kiểm sau authz, trước khi tạo trạng thái; vượt → `RATE_LIMITED` (retryable, `retryAfterMillis`); step workflow bị limit chờ rồi thử lại mà không tốn lượt retry; scheduler giữ fire đến hạn. Bản `InMemory` là theo từng node; limiter dùng chung toàn cụm là adapter của C0 (B-C4-08).

## D-C4-15 — Định danh thực thi của schedule và đăng ký idempotent · ACCEPTED (C0, imported 2026-10-06) · 2026-10-06 · C4
Mỗi lần bắn có định danh duy nhất `(tenant, schedule, fireAt)` trong sổ `schedule_executions` (insert-if-absent trước khi enqueue) + key `sched:<id>:<fireEpochMilli>` + guard đơn điệu theo `lastRunAt`. Schedule do AppDefinition khai báo có `declaredKey = workflow:<id>` unique theo `(tenant, app)`; publish lại không tạo trùng, bỏ khai báo thì disable (không xoá). Enqueue lỗi tạm thời: backoff mũ + ngân sách bỏ cuộc tường minh.

## D-C4-16 — Retention cho dữ liệu chạy · ACCEPTED (C0, imported 2026-10-06) · 2026-10-06 · C4
`RetentionService` xoá theo lô có chặn chỉ các hàng **đã kết thúc**; PENDING/RUNNING/WAITING, compensation đang chạy, approval PENDING, fire CLAIMED không bao giờ bị đụng (ràng buộc nằm trong hợp đồng store). Workflow run hai giai đoạn (làm trống payload sau 14 ngày, xoá sau 90 ngày); mặc định `action_runs` 30 ngày, `approvals` 180 ngày, ledger 30 ngày; tối thiểu 7 ngày vì xoá hàng chấm dứt khử trùng key. Cột/chỉ mục cần có trong yêu cầu migration gộp (BOARD.md); không cấp số Flyway.

## D-C4-17 — executor-level TIMEOUT / INTERRUPTED of a MUTATING action is an unknown outcome (closes F-1) · ACCEPTED (C0) · 2026-10-06 · C4
- Context: when the executor cuts a handler off (`TIMEOUT`, `INTERRUPTED`, or a cancelled future) the handler may still commit. For an action that changes
  state that is the same situation as a data layer answering "outcome unknown", but C4 reported it as `TIMEOUT`/`INTERRUPTED`, retryable when a key
  de-duplicated it and, with an `onError[]` chain, as an ordinary failure.
- Decision: a **single normalisation point**, `DefaultActionRuntime.normalizeAmbiguous`, applied once to the result of the LIVE execution:
  for `ActionType.mutatesState` types, `TIMEOUT` and `INTERRUPTED` become `IDEMPOTENCY_OUTCOME_UNKNOWN`, `retryable = false`, message fixed,
  `details["cause"]` = the original code. Every existing unknown-outcome rule then applies unchanged:
  no retry (a run stored as failed-non-retryable is replayed as is, the handler is not run again), no UI `onError[]` chain (`NO_CHAIN_CODES`),
  no workflow `onError` route (`WorkflowEngine.failStep`), the ambiguous step is not in `compensable` (not compensated), prior completed steps are still compensated
  by the existing workflow rules.
- Not changed: non-mutating actions (`NAVIGATE`, `REFRESH_QUERY`) keep `TIMEOUT` / `INTERRUPTED` and their retry flag and their `onError` chain;
  a TEST preview is never normalised (nothing was written); `MUTATION_REJECTED` stays a definite failure (not retryable, `onError` allowed);
  the workflow's own run-level `TIMEOUT` (`maxDuration`) is a different code and is unchanged. No `ActionType` value added; the frozen nine are intact.
- "Mutating" means `ActionType.mutatesState` (`SUBMIT_FORM`, `CREATE_RECORD`, `UPDATE_RECORD`, `DELETE_RECORD`, `CALL_API`, `NOTIFY`, `START_WORKFLOW`), not only the
  data-writing subset used for `DATA_MUTATE`.
- Changes C4 semantics: yes (a mutating step that times out is no longer retried with backoff; it fails the run `IDEMPOTENCY_OUTCOME_UNKNOWN`).
  C4-owned files: `ActionRuntime.kt`, `ActionResult.kt` KDoc, tests (`ActionRuntimeTests` 7 new / 1 replaced, `WorkflowShapeTests` 2 rewritten).
- Operational consequence for C0/UI: a slow mutating action no longer self-heals by retry. The user (or an operator) checks the data source and starts a
  new operation with a new client key.

## D-C0-13 (draft id D-C0-C4-UI-ONERROR) — an unknown-outcome write does not run the UI `onError` chain · ACCEPTED · 2026-10-06 · C0
- Context: a data write that ends ambiguously is reported as `IDEMPOTENCY_OUTCOME_UNKNOWN` (409, never retryable, `data-runtime.md` §4b).
  The write may have been committed. A workflow already refuses to route such a step to `onError` and does not compensate it.
  Before this change a UI action (`ActionRuntime.run`) still ran its `onError` actions, which assume "nothing happened".
- Decision: `IDEMPOTENCY_OUTCOME_UNKNOWN` is in `NO_CHAIN_CODES` (like the "did not get to run" codes): no `onError` follow-ups, also on replay.
  `MUTATION_REJECTED` (definite, nothing applied) keeps its `onError` chain. `onSuccess` is unchanged.
- Changes C4 semantics: yes (C4-owned files `ActionRuntime.kt`, `ActionResult.kt` KDoc, one new test). No contract value changes.
- The executor-level `TIMEOUT`/`INTERRUPTED` of a mutating action (follow-up F-1) is decided in D-C4-17.

## D-C0-14 (draft id D-C0-C4-ACTORKIND) — C4 keeps its own `logic.action.ActorKind`; adapters convert by name · ACCEPTED · 2026-10-06 · C0
- `logic/**` may not import `tenancy` (architecture test). `logic.action.ActorKind {USER, SYSTEM, APP_TOKEN, SERVICE}` stays; it is pinned by
  `ActionContractV2Tests` to the four names of `tenancy.ActorKind`. The C0 adapters convert with `valueOf(name)` in both directions; an unknown name is
  denied (default deny), never mapped to USER.
- Consequence for the wiring skeleton: `RequestContexts.kt.skel` must build `ActionActor(me.userId, logic.action.ActorKind.USER)`, not `tenancy.ActorKind`
  (the skeleton comment "C4 placeholder is deleted at import" is wrong for C4 and is corrected when W-01 is implemented).

## D-C0-15 (draft id D-C0-C4-DATA-ERRORS) — C3 failures map to C4 `PortOutcome.Failure` in the `ActionDataPortAdapter` (W-05) · ACCEPTED · 2026-10-06 · C0
1. `IDEMPOTENCY_OUTCOME_UNKNOWN` -> `Failure("IDEMPOTENCY_OUTCOME_UNKNOWN", retryable=false)`
2. `MUTATION_REJECTED` -> `Failure("MUTATION_REJECTED", retryable=false)`
3. `IDEMPOTENCY_IN_PROGRESS`, `RATE_LIMITED` -> same code, `retryable=true`
4. `IDEMPOTENCY_CONFLICT` and every other code in C3's `DataGateway.NOT_EXECUTED` (certainly nothing applied) -> same code, `retryable=false`
5. Any other `ConnectorFailure` code (TIMEOUT, CONNECT_FAILED, UPSTREAM_STATUS, QUERY_FAILED, RESPONSE_*, INTERNAL, unclassified) and any non-`ConnectorFailure`
   exception on a WRITE -> `Failure("IDEMPOTENCY_OUTCOME_UNKNOWN", retryable=false)`: C3 has already moved the key to UNKNOWN (`DataGateway.mutate` catch block).
Order matters: 1–3 are matched first; "everything else is ambiguous" is the last rule, never the first. No generic catch-all error code for writes.

## D-C0-16 — C4 official import: scope and evidence · ACCEPTED · 2026-10-06 · C0
`backend/src/{main,test}/kotlin/com/systemwebstudio/logic/**` (46 files) imported by path in one commit from the verified overlay `verify/c4-overlay@43a8088`
(= `agent/c4-workflow@f6bb475` + C0 review changes 1586be0 and 43a8088). Overlay evidence on a real Mac: `compileKotlin` 34 s, `compileTestKotlin` 52 s, full
`clean test` 5 m 41 s, all green. Not imported: wiring skeletons, `application*.yml`, `SecurityConfiguration`, `runtime/Gateway.kt`, migrations (none; V28 stays
unallocated), harness/shims, any C1/C2/C3 copy. Frozen contracts re-checked statically: exactly 9 `ActionType` values, no import of tenancy/data/access/app/common/
audit/runtime/integration/wiring or Spring in `logic/**`, `ActionDataPort` only under `logic/action`. **Not yet "integrated green":** that needs the full Gradle run of
`integration/v2` after this import.

## D-C5-01 — Builder tách Edit và Test; Test không ghi AppDefinition · PROPOSED · 2026-10-05 · C5
Edit chỉnh định nghĩa và chỉ ghi qua operation → validator → version bất biến (`applyOps`). Test ("Dùng thử") là mode riêng (`/studio/projects/:id/test`), chạy Query/Action qua `DataGateway`/`ActionRuntime` bằng quyền của chính người dùng, kết quả chỉ ở React state, không vào `schema`, version, prompt, storage trình duyệt; không nhớ mode `test` trong `sessionStorage`. Action có tác dụng phụ không chạy ở Test cho tới khi C4 có dry-run (B-C5-04). Hệ quả: không cần đổi contract hiện có; chỉ C4 bổ sung cờ test. Chi tiết: `audit/PREP-T12-builder-architecture.md` §6.

## D-C5-02 — Đường dẫn API Data/Action/Workflow theo project; tenant do server suy ra · PROPOSED · 2026-10-05 · C5
Endpoint mới theo mẫu hiện có `/api/v1/workspaces/{workspaceId}/projects/{projectId}/…`; client không bao giờ gửi `tenantId` (khớp `tenant-context.md` quy tắc 1). Request Query chỉ gồm `queryId`, `params`, `page` (khớp `QueryRequest`). Client frontend mới đặt ở `lib/api/data.ts`, `lib/api/actions.ts`, `lib/api/workflows.ts` trên lõi `lib/api/core.ts` (C5 tách từ `http-api.ts`, không đổi hành vi). Cần C2/C3/C4 xác nhận hoặc đề xuất đường dẫn khác (B-C5-02).

## D-C5-03 — Dữ liệu trên site xuất bản: chụp lúc publish, không gọi lúc chạy · PROPOSED · 2026-10-05 · C5
Site xuất bản là HTML tĩnh không script (ADR 0009, CSP). Đề xuất: khi BUILDING, API lấy `ViewModelData` qua `DataGateway` và truyền `data` cho render worker (cùng renderer với preview/Test); không dùng script gọi dữ liệu lúc truy cập. Cần chính sách chống rò rỉ dữ liệu riêng tư vào site `PUBLIC` (chỉ site `PRIVATE`, hoặc binding được duyệt công khai được) và loại binding/action khỏi Template/Block COMPANY (B-C5-07). Cần C0 sửa `workers/render/server.ts` và `publish/**` (B-C5-06). Không ảnh hưởng site hiện có (không `data` ⇒ đầu ra không đổi).

## D-C5-04 — Frontend thành monorepo npm workspaces: 3 app + packages dùng chung · PROPOSED · 2026-10-05 · C5
`apps/{platform,admin,studio}` (mỗi app tự build, cổng dev 3001/3002/3003, tiền tố route `/platform` `/admin` `/studio`) và `packages/@xweb/{types,api-client,permissions,i18n,ui,auth}` (nguồn TS, qua `transpilePackages`). `workspaces` liệt kê đường dẫn tường minh nên `@company/app-sdk` và `@company/ui` (publish registry) không bị đụng. Di chuyển tăng dần: file cũ (`lib/http-api.ts`, `lib/http-types.ts`, `features/{ui,session,routing,useLoad}`, `features/auth/AuthPages.tsx`, `components/useDialog.ts`, `features/admin/Modal.tsx`) thành shim re-export đường dẫn tương đối; app gốc (mock static export + http) và render worker (`workers/render`, tsc commonjs) vẫn build. Trang theo portal (`features/admin`, `features/studio`, `SectionInspector`, `library`) còn ở root, các app import qua alias `@/*`→`../../*`; renderer `lib/schema-preview.ts` ở lại `lib/` vì worker và ADR 0009 dùng. Đã sửa root `package.json`, `package-lock.json`, `tsconfig.json` (exclude `apps`), `next.config.ts`/`proxy.ts` (dùng factory `@xweb/auth/next`, `@xweb/auth/csp`) theo ủy quyền của người dùng, chỉ phục vụ monorepo.

## D-C5-05 — Quyền vào portal (tạm) bám `me.systemAdmin`; ẩn UI không thay thế kiểm quyền server · PROPOSED · 2026-10-05 · C5
`platform.operate` và `tenant.administer` hiện đều theo `systemAdmin` vì backend chỉ có `/admin/*` cho system_admin và chưa có TENANT_ADMIN (T3) / bảng tenant (T2); `studio.build` cần ≥1 workspace. Khi T2/T3 có API, chỉ sửa `capabilitiesOf()` trong `packages/permissions`. Backend chưa tách tenant nên Admin web hiện hiển thị dữ liệu toàn nền tảng cho system admin; các mục chưa có backend (Công ty, Nhóm, Chia sẻ, Nguồn dữ liệu, AI riêng/BYOK) hiện thông báo "Chưa sẵn sàng", không có dữ liệu giả.

## D-C5-06 — Link portal theo cấu hình; dev server bind loopback · PROPOSED · 2026-10-06 · C5
Không còn `/admin` `/studio` viết cứng trong UI: trong app dùng `portalPath`/`S()`/`A()`, sang portal khác dùng `portalHref` với `NEXT_PUBLIC_PORTAL_URL_{PLATFORM,ADMIN,STUDIO}` (rỗng = cùng origin). `dev`/`start` của các app Next bind `127.0.0.1`. Chi tiết và cấu hình: `docs/parallel/agents/C5_PHASE2_PORTALS.md`. D-C5-05 vẫn **TEMPORARY**: gỡ khi C1 giao TenantContext/permission canonical; chỉ sửa `capabilitiesOf()`.

## D-C0-17 — Runtime API route family: project-scoped `/app-runtime`; no competing family · ACCEPTED · 2026-10-06 · C0
- Audit (2026-10-06): no canonical browser route for data/action/workflow existed. C3's framework-free `DataRoutes` (`/api/v1/data/**`, runtime `dataSourceId`, browser-callable `/mutate`) and C4's proposal B-C4-09 (`/api/apps/{appId}/…`) were proposals, not mounted routes.
- Decision: the only browser family is `/api/v1/workspaces/{workspaceId}/projects/{projectId}/app-runtime/…` (C5 D-C5-02 accepted with that segment): R1 `queries/{queryId}/run`, R2 `actions/{actionId}/execute`, R3 `workflows/{workflowId}/runs`, `workflow-runs/{runId}`, `workflow-runs/{runId}/cancel`. Full contract: `docs/contracts/v2/runtime-api.md`.
- `/api/v1/data/**` is **not mounted** by this wiring (reserved for a later tenant-admin data-source contract). B-C4-09 routes outside R3 (event dispatch, approvals, schedules) are deferred.
- tenantId is server-derived (`AccessService.forProject`); strict request parsing rejects unknown fields; `SecurityConfiguration` unchanged (`anyRequest().authenticated()`, CSRF as everywhere).
- Flags stay OFF: `app.data-platform.enabled` (R1) and `app.workflow.enabled` (R2/R3 + C4 runtime beans). A disabled flag = no controller bean.

## D-C0-18 — No browser-callable data mutation route; writes happen only through actions · ACCEPTED · 2026-10-06 · C0
- A mutation is `SUBMIT_FORM | CREATE_RECORD | UPDATE_RECORD | DELETE_RECORD | CALL_API` run by R2. This keeps the C4 pipeline in front of every write: permission set (`APP_USE`, `ACTION_EXECUTE`, `DATA_MUTATE`, declared permission), derived idempotency key, audit, `IDEMPOTENCY_OUTCOME_UNKNOWN`/`MUTATION_REJECTED` semantics, no UI `onError` after an unknown outcome.
- Only `ActionPorts.kt`/`ActionHandlers.kt` build `WriteRequest`/`OperationRequest`; only the wiring `ActionDataPortAdapter` turns them into C3 `GatewayMutation` with the **derived** key.

## D-C0-19 — Admin portal stays platform-only (Q-1) · ACCEPTED · 2026-10-06 · C0
- No accepted contract gives tenant administrators admin routes. `/api/v1/admin/**` authorization is not touched and no runtime route is mounted under it. The Admin web keeps `systemAdmin`-only access (D-C5-05) until a tenant-admin contract exists.

## D-C0-20 — Persistence gap: LIVE data path answers 503; C4 in-memory stores need an explicit acknowledgement · ACCEPTED · 2026-10-06 · C0
- C3 has no persistent data-source/query/credential catalog and C4 has no persistent run stores (migrations unnumbered and unreviewed; V28 stays unallocated). Consequences, implemented in wiring only:
  1. no `DataGateway` bean → R1 and every data action answer `503 DATA_RUNTIME_UNAVAILABLE` (definite "not executed", never retryable-with-key-consumed);
  2. C4 uses `InMemoryActionRunStore` / `InMemoryWorkflowRunStore` / `InMemoryWorkflowQueue` (single node, lost on restart): idempotency replay and workflow state are volatile. LIVE actions of mutating types and LIVE workflow starts answer `503 RUNTIME_STORES_VOLATILE` unless `app.workflow.allow-volatile-stores=true` (default **false**, for dev/E2E only). TEST/WouldRun and non-mutating actions are always available;
  3. a data-source slot binding store does not exist: local data source ids resolve only through a `sourceRef` in the AppDefinition or a `DataSourceSlotBindings` bean (default: none → `422 DATA_SOURCE_UNBOUND`).
- Lifted by a reviewed persistence migration + a decision; not by this change.

## D-C0-21 — Persistence for real LIVE E2E: V28 data runtime, V29 run stores; D1–D7 · ACCEPTED · 2026-10-06 · C0 (owner decisions)
Design and audit: `docs/parallel/c0/PERSISTENCE_DESIGN.md`. Removes B-C0-W-01 in two steps; the volatile-store 503s (D-C0-20) are lifted only when the matching step is Mac-green.
- **D1 — two migrations.** V28 = Data Runtime persistence (C3 minimal + C0 bindings). V29 = Action/Workflow run persistence (C4 minimal). No already allocated migration is renumbered or reordered (V1…V27 untouched). Only roadmap items that were still unallocated moved: ledger items 2 (data foundation) and 4 (action/workflow persistence) became V28 and V29 in reduced form; items 1, 3, 5, 6, 7 stay unnumbered and keep their order. V29 is allocated now but its file is created only after the V28 Mac gate is green.
- **D2 — `source_schemas` is in V28** (minimal: what `SourceSchemaStore`/`DefaultDataGateway` need). Deferred: schema sync jobs, webhook/sync infrastructure, history trimming/versioning beyond the port.
- **D3 — one `data_source_bindings` table for TEST and LIVE**, with an explicit `mode` (`TEST | LIVE`) in the primary key. The runtime only ever READS bindings; a TEST execution never inserts, updates or deletes one.
- **D4 — data idempotency retention is 30 days by default and configurable** (`app.data-platform.idempotency-retention`, default `P30D`, minimum `P7D`). The JDBC store never lets a key expire earlier than that window (it raises the gateway's 24 h request to the configured retention), so it cannot expire before C4's action-run replay window (30 d).
- **D5 — approvals, schedules, notifications stay out of V28/V29.** They remain tracked and deferred, not cancelled; LIVE workflows with an APPROVAL step stay refused until their tables exist.
- **D6 — `action_runs.app_id` is NULLABLE** (the C4 port's `RunKey.appId` is nullable). Persistence is not stricter than the domain contract; uniqueness uses a null-safe key and an index suited to nullable `app_id` (V29).
- **D7 — no management HTTP API is invented here.** Data-source / query / mutation / binding management is a separate OPEN production-readiness blocker (B-C0-W-03); fixture-based E2E seeds through the repositories/writers. No route family is added by the persistence work.
- Finding recorded with this decision: the imported production connectors (postgres, rest) are read-only (`mutator() == null`); LIVE data mutation against a real source needs a writable connector (B-C0-W-04). V28/V29 persistence is exercised end to end with a test connector.

## D-C0-22 — Data sources are owned by a workspace at run time (B-C0-W-05) · ACCEPTED · 2026-10-06 · C0 (owner instruction)

Runtime resolution of a data source requires tenant **and** workspace, default deny; nothing the browser sends (tenant, workspace, data source id) is trusted.
- `DataSourceRepository.findInWorkspace(tenantId, workspaceId, id)` (SQL filters on all three; a tenant-level source with no workspace never matches). `DataSourceService` has a `DataSourceScope` (`TENANT` = historical C3 behaviour and default, so C3's own tests are unchanged; `WORKSPACE` = what `DataRuntimeConfiguration` sets). Every gateway operation reaches a source through `DataSourceService.load`, **after** the permission check, so a cross-workspace source answers exactly like a missing one (`NOT_FOUND` / 404) and the status of a foreign source is never revealed. This covers the AppDefinition `sourceRef` path and the binding path alike.
- V28 (not yet imported or released, edited in place): `data_sources` gets `UNIQUE (id, tenant_id, workspace_id)`; `data_source_bindings_source_fk` is `(data_source_id, tenant_id, workspace_id) -> data_sources(id, tenant_id, workspace_id)`. With `project_fk` (workspace, project) and `workspace_tenant_fk` the database itself guarantees project, binding and source agree on tenant and workspace; a source without a workspace cannot be bound.
- The binding reader also joins `projects` and `data_sources` on workspace + tenant (defence in depth); the binding writer refuses a project outside the stated workspace/tenant and any source not owned by that workspace.
- Not changed here: `DataSourceAdminService` (no route uses it yet) must be workspace-scoped when the management API is built (B-C0-W-03).
- Status 2026-10-06: **verified by the real Mac gate @ `3a6f084`; B-C0-W-05 CLOSED.**

## D-C0-23 — V29 durable action / workflow run state; restart-safety rules · PROPOSED (implemented on `wire/v29-run-persistence`, pending the real Mac gate) · 2026-10-06 · C0

Scope: replaces C4's volatile `ActionRunStore` / `WorkflowRunStore` with PostgreSQL (V29). C4 public contracts, execution semantics, the in-memory stores (kept for unit tests and as an explicit dev override) and the in-memory `WorkflowQueue` (RabbitMQ is the next phase) are unchanged.
1. **Store selection, no silent fallback.** `app.workflow.run-store` = `jdbc` (default) or `memory`. Any other value leaves both store beans undefined and the application does not start; `appRuntime` also `require`s one of the two. With `jdbc` the volatile guard (D-C0-20) no longer refuses LIVE mutating actions / workflow starts; with `memory` it still answers `503 RUNTIME_STORES_VOLATILE` unless `allow-volatile-stores=true`. The workflow queue is still in memory, so recovery after a restart is by the sweeper (item 4), not by redelivery.
2. **Isolation by schema AND adapter (default deny).** `action_runs` / `workflow_runs` carry `tenant_id NOT NULL`; composite FKs `(workspace_id, tenant_id) -> workspaces(id, tenant_id)` and `(workspace_id, app_id) -> projects(workspace_id, id)`; `workflow_run_steps` FKs `(run_id, tenant_id)` to its run. The adapters refuse (exception = "run state unavailable") a run whose application is not a project of the tenant; the workspace of an action run is derived from the project in the database, never taken from a caller. Deviation from the earlier design text (`app_id` without FK): the repository's pattern (V13…V28) is an FK to `projects`, projects are not hard-deleted, and an unconstrained id would be the only unverified tenant link. `action_runs.app_id` stays NULLABLE (D6); a NULL app is one key scope (`COALESCE` unique index) and then workspace is NULL too (`CHECK ((app_id IS NULL) = (workspace_id IS NULL))`).
3. **Ambiguity is data, not memory.** The stored action result keeps `code` and `retryable`, so `IDEMPOTENCY_OUTCOME_UNKNOWN` (retryable=false) and `MUTATION_REJECTED` replay exactly as before the restart; a non-retryable FAILED is never restarted. A swept (abandoned) action run becomes the retryable `TIMEOUT` of the unchanged store contract; retrying it carries the SAME derived key into V28 `data_idempotency` (RESERVED lease -> UNKNOWN), which is what prevents a second write. Nothing in V29 re-executes a mutation: the workflow engine's rules (an ambiguous step is not compensated, earlier completed steps may be, a `compensated` step is skipped) are enforced from persisted step flags.
4. **Lease = staleness, recovery = existing sweeper.** A RUNNING workflow run's `updatedAt` is its heartbeat; the engine's `staleAfter` (`app.workflow.stale-after`, default PT2M, minimum PT30S) is the lease. After a crash or restart the sweeper (`engine.sweep`, which the worker runner already schedules) claims runs atomically (`claimForSweep`: version-guarded stamp, per-tenant fairness, backoff, rotation cursor) so two nodes never take the same run, then: PENDING -> republished; stale RUNNING step -> RETRY_WAIT with the attempt count kept (a new attempt of the same idempotency key); WAIT / retry timers fire when due; a stuck compensation is republished and resumes without repeating compensated steps; terminal runs are never touched. Action runs: `ActionRunRecovery` (scheduled, only with `app.workflow.enabled`) turns a RUNNING action run untouched for `app.workflow.action-run-stale-after` (default PT10M, minimum PT1M, must exceed the longest action timeout) into the retryable `TIMEOUT`; a late `complete` by the dead worker is refused (ownership/version guard). Recovery latency after a restart = the lease, by design.
5. **Not in V29.** Approvals, schedules, notifications (D5); a durable queue (RabbitMQ phase); a scheduled `RetentionService` (the stores implement redact / purge and refuse to touch RUNNING / compensating rows, but nothing schedules them yet); the management API (B-C0-W-03); writable production connectors (B-C0-W-04).
- Status: implemented and statically checked only; **not claimed green until the real Mac gate passes.** B-C0-W-01 closes only after that.

