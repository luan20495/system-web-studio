> **SUPERSEDED_BY:** `docs/DATA_RUNTIME.md` - historical document, kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# C3 — Data Platform: thiết kế, wiring blueprint, runbook

Phạm vi: `backend/src/main/kotlin/com/systemwebstudio/data/**` (T8–T11, cache, sync, webhook, realtime). Tài liệu này là hồ sơ bàn giao cho C0 để tích hợp. Mọi quyết định ở `DECISIONS.md` (D-C3-01…11), blocker ở `BLOCKERS.md` (B-C3-01…10), yêu cầu migration ở `BOARD.md` (không có số version; C0 cấp).

**Trạng thái kiểm chứng (trung thực):** toàn bộ test chạy được trong harness `kotlinc` cục bộ (361 test, 0 fail ở lần chạy cuối của Phase 3). **Chưa** chạy `./gradlew test`, chưa chạy `PostgresConnectorIntegrationTests` (Testcontainers/Docker), chưa compile với Spring Boot 4.1 / Jackson 3 / Kotlin 2.2.21 thật (B-C3-05). Không có Spring bean/controller nào do C3 viết (không kiểm chứng được); thay vào đó là facade độc lập framework + blueprint bên dưới.

## 1. Kiến trúc tổng thể

```
request ─► (C0/C1: auth, TenantContext, CSRF) ─► Controller mỏng (C0 viết theo §5) ─► DataApiHandler
        ─► GatewayContext(tenant, actorUserId, actorKind, requestId, workspaceId, projectId, appVersionId)  ← C0 dựng từ phiên, không từ request
        ─► DataGateway.runQuery / mutate / testConnection / discoverSchema / refreshCache
             ├ GatewayGuard (permission, default-deny, fail-closed, audit DENIED)
             ├ DataSourceService.resolve (tenant-scoped; DISABLED → lỗi)
             ├ QueryCatalog.find (định nghĩa đã duyệt; không SQL/URL từ browser)
             ├ MappingCatalog.findMapping/findViewModel + MappingValidator
             ├ QueryParams.bind (kiểm tra theo định nghĩa trước cache và trước connector)
             ├ QueryCache (key theo tenant+datasource+query+params+versions)
             ├ DataConnectorRegistry → REST | PostgreSQL (read-only) | SPI (mysql/graphql/csv/sheets/odoo/salesforce)
             ├ MappingEngine (Transform DSL + Expression sandbox) → ViewModelData
             └ DataAuditSink (QUERY_SERVED… chỉ id/mã/số lượng)
```

Luồng ngoài request: `SyncRunner` (pull theo lịch → ánh xạ → `SyncSink`), `WebhookIngress` (signed, public), `RealtimeService` (SSE, sự kiện chỉ là gợi ý).

## 2. DataSource (T8)

- Model `DataSource`: tenant-owned, `workspaceId` optional, `name`, `connectorType`, `configNonSecret` (từ chối key/giá trị trông như bí mật), `credentialRef` (tham chiếu), `status`, `createdBy`, timestamps, `version` (tăng mỗi lần đổi config/credential/status; nằm trong cache key).
- Credential tách riêng trong `CredentialStore`/`CredentialVault` (`SecretsCrypto` `v1:`); **không** nằm trong AppDefinition, không về browser/log/audit; `ResolvedCredential` che giá trị khi `toString`.
- `ConnectorFailure(code, safeMessage)`: chỉ văn bản cố định, không cause. Bộ mã đóng ở `FailureCodes`.
- REST: GET, HTTPS, resolve-một-lần/kiểm-tra-mọi-address/connect-tới-address-đã-ghim, không redirect, TLS verify, deadline, cap header/body/row, chỉ JSON.
- PostgreSQL: TLS, session read-only nhiều lớp, `SqlGuard` (một câu lệnh, không ghi, chặn hàm nguy hiểm), `statement_timeout`, từ chối role superuser, deny-list host nền tảng.
- SPI cho mysql/graphql/csv/google_sheets/odoo/salesforce: `ConnectorDescriptor` + `ConnectorSpi` (khai báo capability, schema config, kiểm tra deterministic); chưa có driver live (không có credential thật). Driver mới chỉ cần implement `DataConnector` và đăng ký vào registry.

## 3. Discovery (T9)

`SchemaDiscovery.discover(ref, credential, options)` → `SchemaSnapshot`: entities (table/view/endpoint), fields (kiểu chuẩn hoá, nullable, primaryKey), relations (FK chỉ giữa entity nằm trong schema được cấu hình), metadata, `version`, `discoveredAt`, `truncated`, `warnings`. Mẫu dữ liệu (`sample`) mặc định tắt; khi bật, đi qua `SampleMasker` **ngay trong connector** (PII theo tên cột và theo mẫu email/phone/IP/thẻ; secret/identifier bị che hoàn toàn) trước khi ra khỏi lớp discovery, nên AI chỉ thấy dữ liệu đã che. `DiscoveryService.refresh` giới hạn tần suất (`REFRESH_TOO_SOON`) và lưu `source_schemas` (port; cần migration). Studio gọi `DataGateway.discoverSchema(refresh)`.

## 4. ViewModel + Mapping (T10)

- Hình dạng khớp C2: `FieldType`, `Cardinality`, `ViewModelFieldDef(name,type,label)`, `ViewModelDef`, `MappingDef`, `FieldMappingDef{from,to}`; C3 **thêm** (tuỳ chọn, tương thích ngược) `transforms[]` (canonical; tối đa 8), `default`, `nullable`, `validation`, `errorPolicy`, `version` (B-C3-06: reader của C2 phải giữ các key này). Reader: đọc `transforms[]`, nhận `transform` cũ, **từ chối** khi có cả hai, chuẩn hoá về `transforms[]`; writer chỉ ghi `transforms[]`.
- Transform DSL là tập đóng: `toString,toNumber,toBoolean,date,enumMap,join,split,formula,trim,lower,upper`. Validation là tập đóng (`minLength,maxLength,min,max,oneOf,format∈EMAIL/URL/UUID/DATE/DATETIME/INTEGER`), không regex tuỳ ý.
- `formula` dùng engine biểu thức riêng (lexer/parser/evaluator): giá trị null/Boolean/BigDecimal/String; hàm allowlist; không toán tử luỹ thừa; `if` lazy; chia 0 → null; định danh trần là đường dẫn cột JSON (không reflection/host access); giới hạn: source 500, token 250, depth 24, node 120, step 2000, args 10, chuỗi 10 000, precision 60. Không chạy JavaScript.
- Chính sách lỗi: `FAIL` / `NULL_FIELD` (mặc định) / `SKIP_ROW`; cảnh báo giới hạn 50; tối đa 10 000 hàng.
- ViewModel tách UI khỏi schema nguồn: UI chỉ biết tên field ViewModel; `MappingEngine.apply(QueryResult, mapping, viewModel)` trả `ViewModelData`.

## 5. Route table (canonical; nguồn máy đọc: `data/api/DataApi.kt` → `DataRoutes`)

Controller (C0 viết, mỏng) chỉ: xác thực phiên → dựng `GatewayContext` phía server → gọi `DataApiHandler` → ghi `ApiResult`. **Không** nhận tenantId từ body/query/path/header. Parser nghiêm (`GatewayRequests`) từ chối `sql/url/headers/tenantId/credential…`. Runtime canonical là `GatewayQuery`/`GatewayMutation`.

| Method + path | Gateway operation | Handler |
|---|---|---|
| `POST /api/v1/data/query` | QUERY_EXECUTE | `runQuery(ctx, body)` — body `{dataSourceId, operation, mappingRef, viewModelRef?, params?, page?}` |
| `POST /api/v1/data/mutate` | MUTATION_EXECUTE | `mutate(ctx, body)` — bắt buộc `idempotencyKey` (C4 gửi key đã derive) |
| `POST /api/v1/data/sources/{dataSourceId}/test` | DATASOURCE_MANAGE | `testConnection` |
| `POST /api/v1/data/sources/{dataSourceId}/schema/refresh` | SCHEMA_DISCOVER | `discoverSchema` (mẫu đã che, mặc định tắt) |
| `POST /api/v1/data/sources/{dataSourceId}/cache/refresh` | CACHE_REFRESH | `refreshCache` |
| `GET /api/v1/data/events` (SSE) | EVENTS_SUBSCRIBE | `RealtimeService.subscribe` + `SseFormat`; `Last-Event-ID`; `Handle.stillAllowed()` định kỳ |
| `GET /api/v1/data/ai-catalog` | DATASOURCE_READ | `aiCatalog` (qua `AiDataCatalogProvider`) |
| `POST /api/v1/webhooks/data/{endpointId}` | không (HMAC) | `WebhookHttp.readBody` → `WebhookIngress.handle`; **public**: C0 `permitAll` + miễn CSRF CHỈ route này (B-C3-07); tenant resolve phía server |

Admin CRUD (`DataSourceAdminService`, `SyncAdminService`, `WebhookAdminService`) vẫn là controller mỏng của C0; chưa có DTO/route trong `DataRoutes` (không thuộc Phase 3). Lỗi: `GatewayProblems` → status/code/message cố định (+ `Retry-After`); ngoại lệ lạ → 500 không chi tiết. Đường dẫn cũ `/api/data/…` và `/api/data/webhooks/{id}/ingest` đã bị rút lại. Đề xuất wiring đầy đủ: `C3_WIRING_PROPOSAL.md`.

## 6. Cache (G)

- Key `xw:data:v1:t:<tenant>:ds:<ds>:g<dsGen>.<qGen>:<sha256>`; digest gồm tenant, datasource, query, versions (ds, query, mapping, viewmodel), params canonical đã bind, page. Không chia sẻ cache giữa tenant.
- Phong bì (tenant, ds, query) được kiểm tra lại khi đọc; không khớp → bỏ qua + tính là miss.
- TTL theo query (`cacheTtlSeconds`; 0 = không cache; ≤ 86 400). Giá trị ≤ 512 KB. Redis lỗi = miss (không lỗi người dùng).
- Invalidate bằng generation counter (không SCAN): theo datasource (cập nhật datasource/credential/status), theo query (mutation khai báo, webhook, refresh thủ công). Nếu bump thất bại → bypass phạm vi đó trong tiến trình tối đa 86 400s (giới hạn: không xuyên node → B-C3-09).

## 7. Sync V1 (H)

One-way PULL theo lịch. `SyncJob` (nguồn query + mapping + viewModel, `keyField`, `cursorField/cursorParam`, interval 60…86 400s, `pageSize`, `maxPagesPerRun` ≤ 50), `SyncState` (checkpoint, lastSuccess, lastError, failures, nextRunAt, lease). Lease nguyên tử (`tryLease`) + `finish` có rào chắn (đúng owner, đúng `job.version`, nếu không → `SUPERSEDED`). Checkpoint chỉ commit **sau khi** sink nhận mọi trang. Sink idempotent theo (key) và (runKey,page). Backoff 30s nhân đôi tối đa 3 600s; tạm dừng sau 10 lần lỗi hoặc ngay với lỗi vĩnh viễn (permission/disabled/…). So sánh cursor theo kiểu ViewModel; nguồn trả bản ghi cũ hơn checkpoint → `SYNC_ORDER_VIOLATION`. Chạy dưới actor hệ thống (`ActorKind.SYSTEM`) nhưng vẫn qua `GatewayGuard`. Hạn chế V1: không lan truyền xoá; executor bỏ qua `page` sẽ giao lại trang (sink idempotent).

**Hợp đồng two-way (chỉ contract, chưa implement):** thêm `direction = TWO_WAY`, `SyncSink` phát `ChangeSet` ngược qua `DataGateway.mutate` (mỗi thay đổi một `idempotencyKey` = `sync:<jobId>:<recordKey>:<version>`), chính sách xung đột `SOURCE_WINS | APP_WINS | FAIL` khai báo tường minh theo job, cột `updatedAt/etag` bắt buộc ở nguồn, và vòng lặp phản hồi bị chặn bằng nhãn nguồn gốc thay đổi. Chưa làm vì ngoài scope V1.

## 8. Webhook ingress (I)

Endpoint công khai không mang quyền ambient. Thứ tự: tìm endpoint (một lookup phi-tenant `resolveForIngress`) → giới hạn theo peer (trước lookup) → trạng thái → timestamp ±300s → HMAC-SHA256: v1 trên `"<ts>.<body>"`, v2 trên `"v2.<ts>.<len(delivery)>.<delivery>.<body>"` (header `x-xweb-signature: v1=<hex>|v2=<hex>`, `x-xweb-timestamp`, `x-xweb-delivery`; delivery id chỉ được tin khi nằm trong phần ký), so sánh hằng thời gian, chấp nhận secret trước đó trong thời gian rotate (≤ 24h) → giới hạn theo endpoint → replay guard: luôn theo signature (`sig:<sha256>`, TTL 2×tolerance+60s) + delivery id đã ký với v2 (24h) → parse JSON (chỉ đọc tên sự kiện có giới hạn + khoá bản ghi dạng id) → invalidate cache → phát `DataChanged/RecordChanged/QueryInvalidated` → (tuỳ chọn) kích hoạt workflow **chỉ bằng định danh** qua `WorkflowTriggerPort` (B-C3-08). Hạ tầng lỗi → 503 và quên replay key để sender retry. Audit từ chối bị giới hạn 10/phút/endpoint. Webhook không bao giờ cấp quyền đọc/ghi dữ liệu.

## 9. Realtime (J)

SSE, không WebSocket. Sự kiện: `DataChanged`, `RecordChanged`, `QueryInvalidated`, `StreamReset`; chỉ là **gợi ý** — client luôn refetch qua `DataGateway` (nên quyền/tenant luôn được áp lại). Ring buffer 500 sự kiện/tenant, `seq` đơn điệu, `Last-Event-ID`; vượt buffer → `StreamReset`. Tối đa 100 stream/tenant. Payload một dòng JSON, không chứa tenantId. Event bus trong bộ nhớ theo node; đa node cần bus chung (B-C3-09).

## 10. Đối soát với C2 và C4

- **C2 (AppDefinition V2):** `dataSources`, `queries`, `viewModels`, `mappings`, `bindings` trong AppDefinition chỉ **tham chiếu** (id/ref); không chứa credential/URL/SQL. C3 không tạo model cạnh tranh: `MappingCatalog` đọc ViewModel/Mapping/Query đã **duyệt** theo `AppScope(tenantId, projectId, appVersionId)` từ nơi C2 lưu. Binding: widget → (`dataSourceId`, `operation`, `mappingRef`, `viewModelRef`, params) → `POST /api/data/query`.
- **C4 (ActionRuntime):** `ActionDataPort` mutation / `callApi` gọi `DataGateway.mutate(ctx, GatewayMutation(dataSourceId, operation, params, idempotencyKey))`; C4 gửi `idempotencyKey` đã derive (`base64url(sha256(tenant|app|user|action|clientKey))`, 43 ký tự); C3 giữ nguyên key này, không log key thô. Replay cùng key + cùng params trả kết quả cũ (`replayed=true`); cùng key khác params → `IDEMPOTENCY_CONFLICT`; đang chạy → `IDEMPOTENCY_IN_PROGRESS`; kết quả mơ hồ (timeout/mất kết nối/5xx) KHÔNG release key → retry nhận `IDEMPOTENCY_OUTCOME_UNKNOWN` (409), không tạo bản ghi trùng; từ chối chắc chắn → `MUTATION_REJECTED` (422, key được release). Mutation thành công invalidate các query khai báo và phát `RecordChanged`. Adapter `ActionDataPort` ↔ `DataGateway` do C4/C0 viết (B-C3-03).

## 11. Wiring blueprint cho C0

1. Migration: MỘT request gộp ở BOARD, schema ở `C3_SCHEMA_PROPOSAL.md` (`data_sources` + `credential_ref`, `data_credentials`, `data_queries`, `data_mutations`, `source_schemas`, `data_idempotency`, `sync_jobs`/`sync_state`, `webhook_endpoints`; sink do C0/C2).
2. Bean: `DataConnectorRegistry` (REST, PostgreSQL), `QueryCache(CacheBackend=Redis)`, `DefaultDataGateway`, `DataSourceService`, `DiscoveryService`, `SyncRunner`, `WebhookIngress`, `RealtimeService`, các store JDBC, `GatewayAuthorizer` (nối permission C1). `PostgresTargetPolicy` deny-host lấy từ `spring.datasource.url` và `app.runtime.appdb-url` (`application*.yml` của C0).
3. `GatewayAuthorizer` mặc định **từ chối**; không có bean nào ⇒ không route nào hoạt động (fail-closed).
4. Scheduler: một `@Scheduled` gọi `SyncRunner` cho job đến hạn (`SyncJobStore.due`), owner = id node.
5. Security: `permitAll` + bỏ CSRF **chỉ** cho `POST /api/v1/webhooks/data/{endpointId}`; SSE cần buffering tắt ở proxy. Chi tiết: `C3_WIRING_PROPOSAL.md`.

## 12. Giới hạn đã biết

- Chưa có driver live cho mysql/graphql/csv/google_sheets/odoo/salesforce (chỉ SPI + test deterministic).
- Event bus và bypass cache theo node.
- Sync V1 không xoá lan truyền; two-way chỉ là contract.
- Kiểm chứng bằng harness cục bộ; Gradle/Testcontainers do C0 chạy (B-C3-05).

## 13. Phase 3 — căn chỉnh contract V2 và làm cứng (tóm tắt)

- **Type canonical:** xoá placeholder `TenantContext`/`ActorKind`; dùng type C1 qua `GatewayContext`. Sync chạy với tư cách chủ job và kiểm lại quyền mỗi lần chạy (mất quyền ⇒ tạm dừng, không pull gì).
- **SqlGuard (fail-closed):** chặn `U&"…"`/`U&'…'`, ký tự điều khiển, non-ASCII ngoài literal, comment, nhiều câu lệnh, COPY/DO/CALL/SELECT INTO/CTE ghi/RETURNING, hàm nguy hiểm và quan hệ hệ thống nhạy cảm.
- **PostgreSQL:** chỉ `verify-full` (hostname/cert thật; `require` không phải bảo đảm hostname); preflight role **mỗi session**; read-only, `statement_timeout`/`lock_timeout`/idle timeout, connect timeout, row limit; deny-list nền tảng fail-closed (`denyingPlatformDatabases` ném lỗi khi URL thiếu/không parse được).
- **Cache:** ticket protocol — `lookup` chốt key/generation, `put(ticket, …)` bị từ chối nếu invalidate xen giữa; phong bì có fingerprint request; invalidate không bao giờ bị kết quả cũ ghi đè.
- **Webhook:** path canonical, chữ ký v2, replay theo signature, body limit có chặn, tenant từ endpoint, rate limit, audit, invalidate cache.
- **Idempotency:** 3 trạng thái + lease; key được giữ khi kết quả mơ hồ; mã mới `IDEMPOTENCY_OUTCOME_UNKNOWN`/`MUTATION_REJECTED`.
- **Địa chỉ:** `SupplementaryRanges` đã xoá; dựa vào `PublicAddress` canonical (patch C0: `C3_PUBLIC_ADDRESS_PATCH.md`); `AddressRangeSpecTests` fail trên base cho tới khi patch được áp.
- **`AiDataCatalog`:** `DefaultAiDataCatalogProvider` từ `AiSafeSchema`: chỉ metadata đã che, lọc theo quyền caller, không credential/PII thô.
- **Kiểm chứng:** harness `kotlinc` cục bộ 361/361 pass; mutation check cho U&, cache race, preflight mỗi session, phân loại idempotency. **Gradle: chưa chạy. Testcontainers: chưa chạy. Không có tuyên bố integration PASS.**
