> **SUPERSEDED_BY:** `docs/PUBLISH_RUNTIME.md` - historical document (moved from `docs/parallel/audit/C2-integration-handoff.md`), kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# C2 — hướng dẫn tích hợp cho C0 (chạy test thật)

Trạng thái: nhánh **`fix/c2-v2`** (cắt từ `integration/v2` `c59604b`, port theo path từ `agent/c2-app-model` `a4d5f8b`), **chưa có test thật nào chạy** (B-004: egress tới `services.gradle.org`, `plugins.gradle.org`, `repo.maven.apache.org` bị chính sách tổ chức từ chối, cả ở cloud lẫn VM trên máy; không có Docker daemon). Kiểm chứng duy nhất của C2 là harness kotlinc (Kotlin 2.0.21, stub cho Spring/Jackson 3/AI gateway/AssertJ): **185 passed, 0 failed**. Harness KHÔNG thay thế Gradle. `SchemaServiceInitializationTests` (Mockito) và các test Testcontainers **chưa được biên dịch ở đâu cả**.

## 1. Lệnh chạy (từ `backend/`, cần Docker cho Testcontainers)
```
./gradlew test --tests 'com.systemwebstudio.app.definition.*' --tests 'com.systemwebstudio.component.*' \
  --tests 'com.systemwebstudio.template.*' --tests 'com.systemwebstudio.ai.planner.*' --tests 'com.systemwebstudio.ai.tenant.*' \
  --tests 'com.systemwebstudio.project.publishconfig.*'      # nhóm C2
./gradlew test                                               # toàn bộ backend (gồm Testcontainers)
```
Lớp unit thuần (không cần Docker): `AppDefinitionValidatorTests`, `AppDefinitionActionWorkflowRulesTests`, `AppDefinitionCompatibilityTests`, `ContractConformanceTests`, `AppDataBindingResolverTests`, `DefinitionOperationsTests`, `PublishConfigTests`, `BusinessTemplateTests`, `TemplateSanitizerTests`, `PlanParsingTests`, `AppPlannerTests`, `AiSourceResolverTests`, `TenantAiPolicyTests`, `TenantAiServiceTests`, `ComponentMetadataTests`, và `SchemaServiceInitializationTests` (Mockito, không cần Docker nhưng chưa biên dịch).
Lớp Testcontainers (`IntegrationTestBase`): `AppDefinitionCommitTests`, `DefinitionOperationsCommitTests`, `TemplateV2IntegrationTests` + toàn bộ test có sẵn (đặc biệt `PromptApiTests`, `VersionApiTests`, `LibraryCatalogTests`, `PublishApiTests`, `StaticSiteTests`).

## 2. Chỗ dễ lỗi khi biên dịch/chạy thật (harness không bắt được)
1. Jackson 3: harness bọc Jackson 2.16 dưới tên `tools.jackson`. Cần xem: `JsonNode.propertyNames()`, `asString()`, `isString`, `ArrayNode`, `JsonMapper.treeToValue/readValue`, `deepCopy()` (cần cast), không dùng `set<JsonNode>`. Điểm mới: codec ghi số nguyên bằng `put(String, Int)`; so sánh `JsonNode` trong `ContractConformanceTests` dựa vào quy tắc kiểu của Jackson (Int ≠ Long).
2. Kotlin 2.2 (project) so với 2.0.21 (harness): cảnh báo/lỗi suy luận kiểu mới, `-Werror` nếu bật.
3. Spring glue chỉ được compile với stub của C2: `PlannerApi.kt`, `TenantAiApi.kt`, `PublishConfigApi.kt`, `ComponentMetadataApi.kt`, `@Configuration` + `@ConditionalOnProperty`, `@ConditionalOnMissingBean` (`AiDataCatalog`).
4. **`SchemaService` đổi constructor** (thêm `appDefinitions: AppDefinitionValidator`) và `ensureInitialized` gọi `requireValidExtensions`. Chưa biên dịch. Mọi chỗ tạo `SchemaService` bằng tay (nếu có) phải thêm tham số; bean Spring thì tự nối. `SchemaServiceInitializationTests` mock `SchemaRepository` bằng Mockito (cần Mockito inline cho lớp Kotlin final/mở bởi `kotlin-spring`; Spring Boot đã mang theo).
5. AssertJ thật: một số assertion trong test đã viết theo tập con mà stub hỗ trợ (`doesNotContain`, `isGreaterThanOrEqualTo`, `isNotBlank` đã thêm vào stub).
6. Sửa file không thuộc C2 thuần: `PromptController` (thêm tham số constructor `appDefinitions`, đổi 1 dòng validate), `SchemaCommitService` (gọi `appDefinitions.requireValidExtensions`), `SchemaPatchEngine`/`SchemaOperation` (thêm operation V2, 12 operation cũ giữ nguyên), `ExternalLLMProvider.complete` (thêm `candidatesOverride` có default), `ChatProviders` (`buildDetached`), `SchemaService` (mục 4). Test có sẵn dựng các lớp này qua Spring nên nên ổn, nhưng cần chạy thật.
7. `TemplateService.list/visible` giờ trả template dựng sẵn trước template lưu; `sort=popular` đặt template dựng sẵn cuối để `LibraryCatalogTests` không đổi.
8. `ContractConformanceTests` đọc `../docs/contracts/v2/tenant-permission.md` (Gradle chạy ở `backend/`) để so tập mã quyền; nếu không thấy file thì bỏ qua phần so sánh đó (harness của C2 thì có file).
9. Fixture conformance nằm ở `backend/src/test/resources/app-definition/conformance/` và được nạp qua classpath (`/app-definition/conformance/...`); C0 có thể sao chép vào `docs/contracts/v2/fixtures/` (thư mục của C0).

## 3. Cờ (phải giữ TẮT)
Không có `application*.yml` nào của C2 bật cờ; mặc định (không khai báo) = TẮT vì `@ConditionalOnProperty(havingValue = "true")`:
- `app.ai-planner.enabled` — TẮT (PlanGuard vẫn bắt buộc trước khi commit khi bật).
- `app.tenant-ai.enabled` — TẮT. Chỉ được bật sau khi lời gọi outbound dùng `PublicAddress` canonical tại thời điểm gọi (SSRF/DNS-rebinding) VÀ `tenant_ai_providers` đã migrate.
- `app.publish-configs.enabled` — TẮT cho tới khi V27 `publish_configs` được merge.

## 4. Migration
- **V27 chỉ cho `publish_configs`** — C2 CHƯA tạo file và KHÔNG chạy/tích hợp V27 trước khi C0 xác nhận V26 (C1 tenant foundation) đã integrate. Mã (model/policy/service/API) đã sẵn sàng và bị cờ TẮT chặn; DDL nháp ở `audit/C2-T7-publish-configs.md` §4. Sau xác nhận C2 tạo đúng một file `V27__publish_configs.sql` theo DDL đó (không sửa bảng hiện có) và chạy lại test.
- `templates.tenant_id` và `tenant_ai_providers`: KHÔNG tạo cho tới khi C1 Tenant foundation được tích hợp.
- Seed component mới (DataTable…): không chặn gì, chưa xin số.

## 5. Phụ thuộc hợp đồng còn lại
- C3: **đổi reader `MappingJson` sang `transforms[]`** (canonical, C2 ghi khóa này; C2 vẫn đọc `transform` legacy). Khi `data.*` được nhập vào `integration/v2`, thay luật kiểm tra mapping/query tại chỗ bằng ủy quyền cho parser C3.
- C4: khi `logic.*` được nhập, thay luật action/workflow tại chỗ bằng `ActionDefinitionValidator`; mẫu thông báo thật cho `templateRef`; adapter `ActionDataPort` dùng `AppDataBindingResolver` (idempotency key do adapter/C0 sinh).
- C1: mã quyền canonical đã được C2 áp dụng; quyền Tenant Admin thay `MEMBER_MANAGE`.
- C5: props frontend cho `bindableProps`.
