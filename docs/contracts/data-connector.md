# Contract — Data Platform (DataConnector, SchemaDiscovery, QueryExecutor, MappingEngine, DataGateway)

Owner: **C3**. Trạng thái: DESIGN. Package mới: `com.systemwebstudio.data.{datasource,discovery,query,mapping,gateway}`.

## Hiện trạng cần bảo toàn
- **Connector Proxy** (ADR 0017, `runtime/Gateway.kt`): app container → apps-gateway → API; API kiểm tra grant (`project_connectors`) + operation đã khai báo; credential giải mã **chỉ ở server** (`SecretsCrypto`, AES-256-GCM); chỉ gọi HTTPS base URL đã duyệt; **SSRF guard** `PublicAddress.isPublic` chặn địa chỉ không công khai; rate limit; audit (`CONNECTOR_*`). Không được làm yếu bất kỳ điều nào.
- Credential không bao giờ trả về client hay đi vào AppDefinition/AI prompt.

## Luồng bắt buộc
```
UI → App Definition / ViewModel → Query hoặc Action → Auth + Permission → Data Gateway → Connector → DB / REST / External System
```
Không đường tắt: UI/AppDefinition không biết connection string; Query không tự mở kết nối; Connector chỉ được gọi từ Data Gateway.

## Shape (thiết kế)
```kotlin
interface DataConnector {                        // một loại nguồn: REST, JDBC-Postgres, ...
    val type: String                             // "rest", "postgres"
    fun test(ds: DataSourceRef, cred: ResolvedCredential): ConnectionTestResult
    fun discovery(): SchemaDiscovery
    fun executor(): QueryExecutor
}
interface SchemaDiscovery {
    fun discover(ds: DataSourceRef, cred: ResolvedCredential): DiscoveredSchema      // bảng/cột/kiểu hoặc endpoint/response shape
}
interface QueryExecutor {
    fun execute(req: QueryRequest, ds: DataSourceRef, cred: ResolvedCredential): QueryResult
}
data class QueryRequest(                         // KHÔNG chứa SQL/URL thô từ client
    val queryId: String, val params: Map<String, JsonNode>, val page: PageSpec?, val tenant: TenantContext
)
data class QueryResult(val columns: List<Column>, val rows: List<Map<String, JsonNode>>, val truncated: Boolean)
interface MappingEngine {                        // dữ liệu nguồn → ViewModel field của AppDefinition
    fun apply(result: QueryResult, mapping: List<FieldMapping>): ViewModelData
}
interface DataGateway {                          // cổng duy nhất UI/Action đi qua
    fun runQuery(ctx: AccessContext, tenant: TenantContext, req: QueryRequest): ViewModelData
}
```
`DataSourceRef` = `{id, tenantId, type, configNonSecret}`; secret ở bảng riêng mã hóa, chỉ `DataGateway` resolve được.

## Quy tắc an toàn
1. `DataGateway.runQuery`: (a) `ctx.require(QUERY_EXECUTE …)` + kiểm tenant, (b) lấy query **đã định nghĩa & duyệt** theo `queryId`, (c) bind tham số (prepared statement / path template đã khai báo), (d) giới hạn: timeout, số dòng, kích thước, rate limit, (e) audit (không log giá trị nhạy cảm/credential).
2. REST connector dùng lại SSRF guard (`PublicAddress`); không tự viết lại. JDBC connector chỉ kết tới nguồn đã đăng ký; mặc định read-only.
3. Khám phá schema không trả secret; kết quả chuẩn hóa về `DiscoveredSchema`.
4. Mọi truy cập cô lập theo `tenantId` (`tenant-context.md`).
5. Connector proxy hiện có tiếp tục hoạt động nguyên trạng (backward compatible); hợp nhất vào Data Platform là quyết định riêng (D-003).

Migration: không tự chọn version; ghi request vào `docs/parallel/BOARD.md`, C0 cấp version (xem `OWNERSHIP.md §6`). Phụ thuộc: C1 (`TenantContext`, permission), C2 (binding trong AppDefinition tham chiếu `queryId`).
