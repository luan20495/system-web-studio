# Contract — AppDefinitionV2 & AppDefinitionValidator

> **SUPERSEDED (2026-10-05, C0)** by `docs/contracts/v2/app-definition.md`. This file is the Phase 0 draft, kept for history only; do not implement from it.

Owner: **C2**. Trạng thái: DESIGN. Package mới: `com.systemwebstudio.app.definition`. Hot: `AppDefinition*`, `SchemaOperation`, `PageSchemaValidator`.

## Hiện trạng cần bảo toàn
- `PAGE_SCHEMA`: `{page, sections:[{id,type,componentVersion,props}], ...}` (multi-page, navigation, site settings).
- `SchemaOperation` (data class, không có trường mang code) với `OperationTypes`: `ADD_SECTION REMOVE_SECTION MOVE_SECTION UPDATE_SECTION UPDATE_PROP ADD_ITEM REMOVE_ITEM` + site: `ADD_PAGE UPDATE_PAGE REMOVE_PAGE SET_NAVIGATION UPDATE_SITE`.
- `SchemaPatchEngine` áp operation lên bản sao; `PageSchemaValidator` validate theo component registry; `SchemaCommitService` commit trong 1 transaction: validate → CAS `projects.revision` → lưu schema → **immutable** `project_versions` → usage → audit. Lỗi revision → `409 REVISION_CONFLICT`.
- `STATIC_APP` và source/server app (`code/**`, `runtime/**`) không thuộc AppDefinition V2 giai đoạn này.

## Nguyên tắc
1. **AppDefinitionV2 là siêu tập (superset) của Page Schema.** Một project `PAGE_SCHEMA` hiện tại phải đọc được như `AppDefinitionV2` (adapter), và ghi ngược không mất thông tin. Không xóa/đổi nghĩa trường cũ.
2. Một model duy nhất cho người dùng và AI. **AI không có model dữ liệu riêng.**
3. Mọi thay đổi đi qua: Operation → `SchemaPatchEngine`-style apply → `AppDefinitionValidator` → commit tạo **version bất biến mới**. Không sửa version cũ.
4. Chỉ dữ liệu khai báo; không có trường chứa code/SQL/URL thô. Tham chiếu dữ liệu và hành động bằng **ID có kiểu**, không bằng chuỗi truy vấn.

## Shape (thiết kế)
```kotlin
data class AppDefinitionV2(
    val schemaVersion: Int,                    // 2
    val kind: AppKind,                         // PAGE_SCHEMA (khởi đầu)
    val pages: List<PageDef>,                  // tương thích Page Schema (sections giữ nguyên)
    val navigation: JsonNode?,                 // như hiện nay
    val site: JsonNode?,                       // như hiện nay
    val dataBindings: List<DataBinding> = emptyList(),   // NEW, rỗng với app cũ
    val actions: List<ActionRef> = emptyList(),          // NEW tham chiếu ActionRuntime (action-workflow.md)
    val extensions: Map<String, JsonNode> = emptyMap()   // vùng mở rộng có namespace
)
data class DataBinding(
    val id: String, val sectionId: String, val prop: String,
    val queryRef: String,                      // id của Query định nghĩa (data-connector.md), KHÔNG phải SQL
    val mapping: List<FieldMapping> = emptyList()
)
data class ActionRef(val id: String, val trigger: String /* vd sectionId.event */, val actionId: String)
```
`AppDefinitionOperation` mở rộng `SchemaOperation` bằng các loại mới (`ADD_BINDING`, `REMOVE_BINDING`, `ADD_ACTION_REF`…) **bên cạnh**, không thay đổi `OperationTypes` cũ.

## AppDefinitionValidator
```kotlin
interface AppDefinitionValidator {
    fun validate(def: AppDefinitionV2, ctx: ValidationContext): ValidationResult   // không ném; trả danh sách lỗi có path
    fun requireValid(def: AppDefinitionV2, ctx: ValidationContext)                 // ném ApiException 422 như PageSchemaValidator
}
```
Trách nhiệm: delegate phần page/section cho `PageSchemaValidator` (không nhân đôi luật); kiểm tra `dataBindings` trỏ tới query tồn tại **trong tenant** và `actions` trỏ tới action tồn tại; kiểm tra permission tham chiếu qua `AccessContext`; giữ các luật hiện có (link domain đã duyệt, `asset://` thuộc project).

## AI flow
`Prompt → Structured Operation → AppDefinition → Validator → Version`. `LLMProvider` chỉ trả `AppDefinitionOperation`/`SchemaOperation`; không có trường tự do. Audit/usage/governance AI giữ nguyên (`ai/**`, ADR 0014).

## Phụ thuộc
Tenant: `tenant-context.md`. Query/Action: `data-connector.md`, `action-workflow.md` (C2 chỉ giữ **tham chiếu ID**, không giữ logic).
Migration: không tự chọn version; ghi request vào `docs/parallel/BOARD.md`, C0 cấp version (xem `OWNERSHIP.md §6`).
