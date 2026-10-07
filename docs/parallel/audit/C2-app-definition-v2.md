# AppDefinition V2 — cài đặt của C2 theo hợp đồng đã đóng băng

Trạng thái: **bám hợp đồng canonical `docs/contracts/v2/app-definition.md`** (C0 đóng băng tại `c59604b786b375fa3817982c611c0fa2b4de6f13`, nhánh `integration/v2`). Tài liệu này mô tả phần C2 đã cài đặt và các điểm cần C0/C3/C4 biết; nếu lệch, hợp đồng thắng. Mã nguồn: `backend/src/main/kotlin/com/systemwebstudio/app/definition/`. Nhánh làm việc: `fix/c2-v2` (cắt từ `integration/v2`, port theo path từ `agent/c2-app-model` — không rebase nhánh cũ, không nhập BOARD/BLOCKERS/DECISIONS cũ, không có contract mirror).

## 1. Nguyên tắc
1. AppDefinitionV2 là **siêu tập** của Page Schema, lưu trong **cùng JSON** (`page_schemas.schema`, `project_versions.schema_snapshot`). Không migration, không đổi bảng.
2. Mọi khóa mới đều **tùy chọn**. Schema cũ không có khóa V2 → mọi luật V2 là no-op; round-trip y nguyên (`codec.toJson(codec.fromJson(x)) == x`).
3. **Đọc rồi ghi không làm mất trường không thuộc C2**: mọi khóa gốc không phải khóa V2 (kể cả khóa lạ) nằm nguyên văn trong `pageSchema`; `extensions` giữ nguyên nội dung; mọi trường canonical của C3 (mapping/viewModel/query) và C4 (action/workflow) đều có kiểu trong model nên không bị bỏ. Tài liệu tự khai báo `schemaVersion` thì không được có khóa lạ ở gốc (hợp đồng).
4. Chỉ tham chiếu bằng **id cục bộ có kiểu**. Không SQL, URL, credential, code, JS. Một reader duy nhất (`AppDefinitionReader`) phục vụ validator và codec. Id cục bộ không được trông như UUID (tránh lưu nhầm id runtime).
5. Mọi thay đổi: Operation → `SchemaPatchEngine` → `PageSchemaValidator` + `AppDefinitionValidator` → CAS `projects.revision` → version bất biến → audit (`SchemaCommitService`). `SchemaService.ensureInitialized` đi qua **đúng cùng hai validator** (không có đường vòng, xem §8).

## 2. Khóa lưu (persisted keys)
`schemaVersion kind theme dataSources viewModels queries mappings dataBindings actions workflows permissions publishConfig extensions` + khóa cũ `page sections pages[] site seo` (và khóa lạ của schema cũ). `pages`, `components`, `navigation` (và `assets`, `actionRefs`) là **view tính ra** từ phần cũ (`AppDefinitionViews`), **không phải nguồn sự thật riêng** và không bao giờ được ghi thành khóa. Test: `ContractConformanceTests` (`the persisted keys of a V2 document are exactly the contract keys`).

## 3. Shape
```
AppDefinitionV2 {
  schemaVersion: 2, kind: PAGE_SCHEMA
  -- kế thừa Page Schema (nguyên văn): page, sections, pages, site, seo, <khóa lạ>
  theme { colors{name:#RRGGBB}≤12, fontFamily SYSTEM|SERIF|MONO|ROUNDED, radius NONE|SM|MD|LG }   // PROVISIONAL
  dataSources[{id,type,sourceRef?(UUID đã đăng ký),name?,description?}]                            // id cục bộ; sourceRef là slot đã gắn
  queries[{id,dataSourceRef,mode READ|WRITE,operationKey?,params[{name,type,required=true,default?}],maxRows≤10000,name?}]
       ParamType = STRING|INTEGER|NUMBER|BOOLEAN|TIMESTAMP|DATE; required mặc định TRUE (C3)
  mappings[{id,name?,description?,queryRef,errorPolicy FAIL|NULL_FIELD|SKIP_ROW,version,
            fields[{from?,to,transforms[obj ≤8] (canonical; legacy key `transform` read + normalised),default?,nullable=true,
                    validation{minLength,maxLength,min,max,oneOf[≤200],format EMAIL|URL|UUID|DATE|DATETIME|INTEGER},description?}]}]
  viewModels[{id,name?,description?,queryRef?,mappingRef?,cardinality SINGLE|LIST,fields[{name,type,label?,description?}]}]
  dataBindings[{id,sectionId,prop,viewModelRef | queryRef(+mappingRef)}]        // binding duy nhất của C2
  actions[{id,name?,type,enabled=true,trigger{sectionId,event}?,pageRef?,queryRef?,viewModelRef?,dataSourceRef?,operationKey?,workflowRef?,
           channel?,templateRef?,endpointRef?,recipients[]?,permissionRef?,inputs[{name,type,required=false,maxLength?}],
           inputMapping{input:{source,path|name|value|key}},idempotency?,limits{timeoutMillis}?,onSuccess[]?,onError[]?}]
       ActionType = NAVIGATE|REFRESH_QUERY|SUBMIT_FORM|CREATE_RECORD|UPDATE_RECORD|DELETE_RECORD|CALL_API|NOTIFY|START_WORKFLOW
       (bí danh RUN_QUERY, WRITE_DATA, CALL_CONNECTOR_OPERATION, SET_VALUE bị từ chối)
  workflows[{id,name?,enabled=true,trigger MANUAL|SCHEDULE|ACTION,schedule?,timezone?,startStepId?,compensateOnCancel=false,limits{…}?,
             steps[{id,kind? ACTION|WAIT|APPROVAL|BRANCH|END,actionRef?,inputs{name:{from INPUT|STEP|LITERAL,path|stepId|value}},next?,onError?,
                    retry{maxAttempts,initialBackoffMillis,multiplier,maxBackoffMillis}?,timeoutMillis?,waitSeconds?,approval{…}?,
                    branches[{condition,next}]?,defaultNext?,compensationActionRef?}]}]
  permissions[{id,name?,permission,resourceType,resourceRef}]     // permission CHỈ nhận mã canonical (§5)
  publishConfig { mode STATIC|DYNAMIC, visibility PRIVATE|TENANT|PUBLIC|PRIVATE_LINK, requiresAuth, cacheSeconds }   // chỉ là NHÁP
  extensions { "vendor.feature": {…} }                            // vùng có namespace, vẫn bị quét URL/credential/code
}
```
Giá trị mặc định **không** được ghi (mode READ, cardinality LIST, field type STRING, trigger MANUAL, enabled true, `required` của param true, `required` của action input false, `nullable` true, errorPolicy NULL_FIELD, version 1, publish STATIC/PRIVATE, requiresAuth false) và tập rỗng không được ghi → dạng ghi chuẩn ổn định. Số nguyên vừa kiểu Int được ghi dưới dạng Int (Jackson so sánh `JsonNode` theo kiểu: Long ≠ Int; lỗi này harness đã bắt được ở `MappingDef.version`).

## 4. Sự kiện (trigger)
`trigger.event` phải là tên dây của `EventType`: `onLoad, onClick, onChange, onSubmit, onSuccess, onError`. Component có overlay chỉ chấp nhận sự kiện nó khai báo và loại action mà sự kiện đó hỗ trợ (`ComponentOverlays`; ProductGrid: click một mục → `onClick`).

## 5. Luật kiểm tra
- Cấu trúc/kiểu/pattern/giới hạn trong từng định nghĩa; trường lạ trong định nghĩa bị từ chối kèm path (`queries[0].rawSql`) — C2 là cổng chặn, nhưng mọi trường canonical của C3/C4 đều có kiểu nên không bị từ chối.
- Id duy nhất trong từng collection; tham chiếu chéo phải tồn tại: query→dataSource, mapping→query, viewModel→query/mapping (mapping phải cùng query, `to` phải là field của viewModel), action→query/viewModel/workflow/dataSource/permission/section(trigger)/page(NAVIGATE)/action(onSuccess, onError; không tự nối), step→action/step (next, onError, defaultNext, branches, approval.onReject/onExpire, compensationActionRef, inputs.stepId), binding→section+prop+viewModel|query+mapping, permission→tài nguyên.
- Đọc ≠ ghi: viewModel/dataBinding/`REFRESH_QUERY` chỉ dùng query READ; `SUBMIT_FORM`/`CREATE_RECORD`/`UPDATE_RECORD`/`DELETE_RECORD` chỉ dùng query WRITE. `CALL_API` cần `dataSourceRef` + `operationKey` (không bao giờ URL).
- `NOTIFY` cần `channel` (IN_APP|EMAIL|WEBHOOK|SMS) và `templateRef`; `WEBHOOK` cần `endpointRef` (id đã đăng ký, không phải URL), kênh khác cấm `endpointRef`; `channel/templateRef/endpointRef/recipients` chỉ hợp lệ cho NOTIFY.
- Idempotency: `NONE` bị từ chối với action đổi trạng thái; `START_WORKFLOW` phải `REQUIRED`. `inputMapping` chỉ điền input đã khai báo; mỗi nguồn (`COMPONENT_STATE/VIEW_MODEL/PREVIOUS_RESULT` → path, `ROUTE_PARAM/FORM_FIELD` → name, `LITERAL` → value, `CONTEXT` → key) chỉ mang đúng thành viên của nó.
- Workflow: `SCHEDULE` cần cron 5 trường, lịch/timezone chỉ cho `SCHEDULE`; từng loại step cần thành viên của nó (WAIT→`waitSeconds`, APPROVAL→`approval` có title + approvers, BRANCH→`branches`); điều kiện nhánh là cây C4 giới hạn (độ sâu ≤5, ≤20 nút); đồ thị step không có vòng trên mọi cạnh; không vòng action→workflow→action.
- Quyền: `permission` **chỉ** nhận 14 mã canonical của `tenant-permission.md` (`APP_VIEW, APP_USE, APP_EDIT, APP_PUBLISH, APP_SHARE, DATA_SOURCE_VIEW, DATA_SOURCE_MANAGE, QUERY_EXECUTE, DATA_MUTATE, ACTION_EXECUTE, WORKFLOW_EXECUTE, WORKFLOW_MANAGE, TENANT_MANAGE, TENANT_MEMBERS`) — đúng bảng §5 của `docs/contracts/v2/tenant-permission.md` (cột "Canonical code", gồm cả `TENANT_MANAGE`, `TENANT_MEMBERS` vì bảng đóng băng liệt kê chúng); không lấy từ cài đặt C1, và `ContractConformanceTests` đọc chính bảng đó để so. Mã lạ bị từ chối. Ánh xạ sang `Permission` lưu trữ (legacy `PROJECT_*`) là việc của C1/runtime. Mã cũ (`PROJECT_EDIT`…) và mã tự đặt bị từ chối (`PermissionCodes`).
- `publishConfig.mode = SERVER_APP` bị từ chối (chỉ có ở `publish_configs`); `PUBLIC` + `requiresAuth` mâu thuẫn.
- Cấm tuyệt đối: chuỗi có `://`, `javascript:`, `vbscript:`, `file:`, `data:`; key kiểu password/secret/token/apiKey/credential/connectionString/sql/url/endpoint/script/eval trong vùng tự do.
- **Chưa làm (cố ý):** ủy quyền kiểm tra sâu cho validator của C4 (`logic.*`) và C3 (`data.*`) — hai gói này chưa nằm trong `integration/v2`; tầng `app.definition` chỉ được import shape C3 + validator C4 (INTEGRATION_V2 layering). Hiện C2 kiểm tra các luật ở trên tại chỗ; khi hai gói được nhập, thay các luật trùng bằng ủy quyền.

## 6. AppDataBindingResolver (`AppDataBindingResolver.kt`)
Một nơi duy nhất đổi **id cục bộ → định danh runtime**, dùng cho cả đường UI (binding, view model) và `ActionDataPort` của C4: binding/action/viewModel → `queryRef` → `QueryDef` → `dataSourceRef` → `DataSourceDef.sourceRef` (UUID nguồn đã đăng ký) + `operationKey`. Thuần (không I/O, không Spring, không import lớp data-platform); adapter ở `wiring.*` (C0) đổi kết quả thành `GatewayQuery/GatewayMutation`, thêm tenant, user, idempotency key. `slotBindings` cho slot chưa có `sourceRef` (project tạo từ template gắn nguồn ngoài tài liệu); `sourceRef` trong tài liệu thắng. `mapping.queryRef` được dịch sang `operationKey` cho C3. **AppDefinition không bao giờ lưu id runtime** (test `resolving never changes the definition and no runtime id appears in it`). Lỗi trả mã ổn định (`ResolutionCodes`): `UNKNOWN_REFERENCE, DATA_SOURCE_UNBOUND, NO_OPERATION, WRONG_MODE, NOT_A_DATA_ACTION, INVALID_SOURCE_ID, NO_QUERY`.

## 7. Điểm lệch / cần C0-C3-C4 xác nhận
1. **Mapping `transforms[]` (đã căn theo hợp đồng).** Canonical V2 là `fields[].transforms[]` (mảng ≤8 object, theo thứ tự). C2 GHI đúng `transforms[]` (không ghi khi rỗng) và ĐỌC cả khóa legacy `transform` của reader C3 cũ (một object hoặc một mảng) rồi chuẩn hóa về `transforms` — không bỏ gì; cùng lúc có cả hai khóa → bị từ chối. **C3 phải đổi reader sang `transforms[]` trong vòng C3 tới** (`MappingJson`: đọc `transforms`, tùy chọn giữ `transform` làm legacy). Cho tới lúc đó adapter `wiring.*` không được đưa JSON do C2 ghi vào reader C3 cũ mà không chuyển khóa. Fixture: `valid-mapping-transforms`, `valid-mapping-legacy-transform`, `invalid-mapping-transform-both`.
2. `ParamDef.required` mặc định **true** (C3) còn `actions[].inputs[].required` mặc định **false** (reader C4). C2 giữ đúng hai mặc định.
3. Template dựng sẵn `notify-team` dùng `templateRef = tpl-review-request` (id trung lập; mẫu thông báo thật do C4/C0 đăng ký).
4. Khóa `futureXxx` bên trong `props`/`pages[]`/`site` của schema cũ vẫn bị `PageSchemaValidator` hiện có từ chối (hành vi cũ, không đổi); khóa lạ ở gốc, trong `seo`, trong `sections[]` và trong `extensions` được giữ nguyên.

## 8. `SchemaService.ensureInitialized`
Phiên bản đầu của một project (project mới, từ template, tài liệu khôi phục) trước đây chỉ qua `PageSchemaValidator`. Nay gọi thêm `appDefinitions.requireValidExtensions(schema)` ngay sau `validator.requireValid(schema)` — **y như `SchemaCommitService.commit`** (INTEGRATION_V2 R-12). Không có đường ghi `page_schemas`/`project_versions` nào khác ngoài `SchemaRepository` (đã rà toàn repo). Test: `SchemaServiceInitializationTests` (Mockito; không ghi gì khi tài liệu sai), `AppDefinitionCommitTests` cho đường commit.

## 9. Conformance fixtures
`backend/src/test/resources/app-definition/conformance/` + `manifest.json` (C0 có thể sao chép vào `docs/contracts/v2/fixtures/`; manifest có `normalizesTo` cho cách viết legacy): `legacy-single-page`, `valid-v2-full`, `valid-action-refresh-query`, `valid-param-date`, `valid-required-omitted`, `valid-unknown-fields-roundtrip`, `valid-action-def-full`, `valid-workflow-def-full`, `valid-mapping-def-full`, `invalid-permission-code`, `invalid-reference`, `invalid-action-run-query-alias`, `invalid-unknown-definition-field`, và (vòng căn hợp đồng) `valid-mapping-transforms`, `valid-mapping-legacy-transform`, `invalid-mapping-transform-both` — 16 file + manifest. `ContractConformanceTests` đọc manifest (hợp lệ → không vi phạm + round-trip chính xác; không hợp lệ → đúng path) và so tên/thứ tự các enum C2 phản chiếu với hợp đồng.

## 10. Component Registry V2 (D-C2-12)
Metadata khai báo cho từng component: bindable props, events (tên `EventType`) → loại action hỗ trợ, điều kiện hiển thị, preview. 8 component seed có overlay bằng code; component mới nhận mô tả suy ra từ props schema. Validator chỉ kiểm trigger của component có overlay. API chỉ đọc `GET /api/v1/component-metadata`. Test: `ComponentMetadataTests`.

## 11. Template V2 (D-C2-09)
Scope SYSTEM / TENANT / PRIVATE; 13 template nghiệp vụ dựng bằng code (không seed, id ổn định); `TemplateSanitizer` bỏ `publishConfig`, `sourceRef`, và (SYSTEM) `operationKey` + extension lạ; sample data giới hạn. TENANT cần `templates.tenant_id` (migration chờ C0/C1). Test: `BusinessTemplateTests`, `TemplateSanitizerTests`, `TemplateV2IntegrationTests` (Testcontainers, chưa chạy).

## 12. AI App Planner (D-C2-10) và Platform/Tenant AI (D-C2-11)
Planner chỉ trả về operation có kiểu; `PlanGuard` (parse → checkOperations → checkResult) + `AppDefinitionValidator` **bắt buộc** trước khi PROPOSED; commit chỉ qua `SchemaCommitService`. Prompt planner nói đúng loại action (gồm `REFRESH_QUERY`), sự kiện, kênh NOTIFY, `DATE`, `required` mặc định true và mã quyền canonical. AI Gateway giữ nguyên, chỉ áp cho nguồn PLATFORM. Tenant AI: `AiSourceResolver`, `TenantAiPolicy`, khóa mã hóa ở server. **Cả hai API TẮT** (`app.ai-planner.enabled`, `app.tenant-ai.enabled`; mặc định không khai báo = tắt). Tenant AI chỉ được bật khi lời gọi outbound dùng `PublicAddress` canonical tại thời điểm gọi và `tenant_ai_providers` đã migrate.
