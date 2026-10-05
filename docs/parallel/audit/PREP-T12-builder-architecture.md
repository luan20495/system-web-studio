# PREP-T12 — Builder / Data Binding Architecture Audit

Owner: **C5** · Branch `agent/c5-web` · Base `d3c7065d3b6963dc625be5d0725922f5004fb818` · Ngày 2026-10-05
Trạng thái: **AUDIT — không triển khai tính năng**. Mọi đoạn "đề xuất" là thiết kế cho T12, chưa phải code. Số dòng trích dẫn là của base commit; mục "đã kiểm tra" nghĩa là đã đọc trực tiếp file đó.

## 0. Kết luận ngắn

1. Builder hiện tại có **đúng một phễu ghi** vào định nghĩa ứng dụng: `ProjectWorkspace.applyOps()` → `api.patchSchema()` → `PATCH /schema` → `SchemaPatchEngine` → `PageSchemaValidator` → `SchemaCommitService` (version bất biến). Inspector, DnD, thư viện component/block, trang/điều hướng, restore và AI đều đi qua phễu này. Data/Action/Workflow phải dùng **cùng phễu** (operation mới của C2), không có kho state song song.
2. `lib/schema-preview.ts` là **renderer duy nhất của cả preview lẫn publish** (render worker `workers/render/server.ts` import chính file này). Đây là ràng buộc lớn nhất của T12: mọi thay đổi renderer ảnh hưởng site đã xuất bản; site đã xuất bản là HTML tĩnh, không script, CSP chặt, nên **không thể gọi Query lúc chạy** trên trang xuất bản.
3. Phía backend, **chưa có gì** để nối: không có package `app/definition`, `data/*`, `logic/*`; `SchemaOperation`/`OperationTypes` chưa có loại binding/action; frontend `PageSchema` chưa có `dataBindings`/`actions`. Vì vậy chưa thể làm UI chạy được mà không giả API.
4. Contract hiện tại **còn thiếu và có chỗ mâu thuẫn** (mục 3 và 7): không định nghĩa thực thể Query/ViewModel/DataSource để UI liệt kê, không có endpoint, `FieldMapping`/`ViewModelData`/`PageSpec` chưa có shape, `ActionRequest` không có cờ dry-run/test, `pages` của AppDefinitionV2 lệch với Page Schema thật, `AppKind` trùng tên khác nghĩa. Đã ghi `needs-contract-change` ở `BLOCKERS.md` (B-C5-01…) và đề xuất ở `DECISIONS.md` (D-C5-01…).
5. Edit và Test phải là **hai chế độ tách trạng thái**; Test chạy qua backend `DataGateway`/`ActionRuntime` bằng quyền của chính người dùng, dữ liệu chỉ nằm trong React state, **không bao giờ** vào `schema`, `applyOps`, version, lịch sử prompt hay storage trình duyệt.

Việc đã làm trong PREP: tài liệu này; một file type placeholder chỉ chứa phần **nguyên văn có trong contract** (`lib/app-definition/contract-mirror.ts`, không import ở đâu); cập nhật BOARD/BLOCKERS/DECISIONS. Không đổi hành vi UI, không backend, không migration, không đụng `package.json`/`tsconfig.json`.

## 1. Phạm vi đã đọc

Frontend: `features/studio/{ProjectWorkspace,StudioApp,drawers,libraryPanels,SitePanels}.tsx`, `features/{ui,library,session}.tsx`, `features/routing.ts`, `components/{SectionInspector,StudioShell}.tsx`, `components/app/AppEntry.tsx`, `app/{layout.tsx,[[...slug]]/page.tsx}`, `lib/{schema-preview,preview-document,http-api,http-types,api-client,types}.ts`, `proxy.ts`, `workers/render/server.ts`, `e2e/factory-flow.mjs` (selector), `features/admin/AdminApp.tsx` (`ConnectorsPage`). Chưa đọc kỹ từng dòng: `CodeWorkspace.tsx`/`CodePanels.tsx` (chỉ xác nhận ranh giới: `STATIC_APP` được giao nguyên cho `CodeWorkspace`, nằm ngoài AppDefinition V2).
Backend (chỉ đọc): `schema/{SchemaOperation,PageSchemaValidator,SchemaPatchEngine}.kt`, `version/{SchemaCommitService,SchemaVersionController}.kt`, `access/Permission.kt`, `runtime/Gateway.kt` (connector proxy), cấu trúc thư mục module.
Docs: `CLAUDE.md`, `OWNERSHIP`, `BOARD`, `DECISIONS`, `BLOCKERS`, `BASELINE`, 3 contract, `ARCHITECTURE_ASSESSMENT.md` §17 (T10–T13).

## 2. Current Builder architecture map

### 2.1 Định tuyến và chế độ mock/http
- `app/[[...slug]]/page.tsx` → `components/app/AppEntry.tsx`. `isDemoMode` (`lib/api-client.ts`: `NEXT_PUBLIC_API_MODE`, mặc định `mock`) chọn: **mock** → `StudioShell` (UI demo riêng, dữ liệu `lib/mock-data.ts`, `studioApi`, không router); **http** → `SessionProvider` → `Router` → `/studio/*` → `StudioApp`.
- `StudioApp.tsx:20-37`: `/studio/projects/:id/:view?` → `ProjectWorkspace` toàn màn hình; các trang khác nằm trong shell Studio.
- **`ProjectWorkspace` chỉ tồn tại ở http mode.** Mọi thứ T12 thêm vào chỉ được sống ở đây; mock mode (`StudioShell`) **không** được nhận tính năng data (sẽ phải fake).
- `view` là tham số URL duy nhất: `ai|design|code` là *mode* (L21), `members|versions|assets|publish|settings|site` là *panel/drawer* (L22). Mode cuối cùng nhớ trong `sessionStorage` (`ws-mode-<id>`, L50-53).
- `proxy.ts`: CSP theo request, `connect-src 'self' <storage>`, `frame-src 'self' about: <SITES_ORIGIN>`; iframe `srcDoc` kế thừa CSP này.

### 2.2 Cây component (http mode, project PAGE_SCHEMA)
```
StudioApp
└─ ProjectWorkspace (features/studio/ProjectWorkspace.tsx, ~35 KB, một component, ~20 useState)
   ├─ [STATIC_APP] CodeWorkspace            (L221, ngoài phạm vi AppDefinition V2)
   ├─ header.topbar: modeTabs(ai/design/code) · Website · Phiên bản · Tệp · Chia sẻ · ⚙ · Xuất bản
   ├─ mode=code  → thẻ "Chưa triển khai" (L257-266)  [chỉ cho PAGE_SCHEMA]
   ├─ main.wsBody
   │   ├─ mode=ai     → section.promptPane (hội thoại + composer + chọn model, L269-310)
   │   ├─ mode=design → section.designLeft (L312-341)
   │   │      PageBar (SitePanels) · DndContext/SortableContext/SortableRow (outline)
   │   │      Thư viện component (registry) · Khối dựng sẵn (blocks)
   │   ├─ section.previewPane → toolbar thiết bị + <iframe.previewFrame srcDoc> (L343-354)
   │   └─ mode=design → aside.inspectorPane → SectionInspector (components/SectionInspector.tsx)
   └─ drawers/modal theo `view`: Drawer(versions) · SiteDrawer · SettingsDrawer · SaveBlockDrawer · AssetsDrawer · MembersDrawer · PublishModal (L372-394)
```

### 2.3 Nguồn state (tất cả cục bộ trong `ProjectWorkspace`, không store toàn cục)
| State | Dòng | Nguồn thật | Ghi chú |
|---|---|---|---|
| `project` | L56 | `api.lookupProject` | có `permissions: string[]`, `appType`, `appKind` |
| `schema` | L57 | `api.getSchema` / kết quả `patchSchema`/`sendPrompt`/`restoreVersion` | **nguồn sự thật duy nhất của định nghĩa app** |
| `revision` | L58 | cùng response | gửi lại là `expectedRevision` (CAS); `409 REVISION_CONFLICT` → tải lại (L116) |
| `versions`, `messages` | L59-60 | `listVersions`, `listPrompts` | |
| `registry` | L61 | `api.components()` | `propsSchema` quyết định form Inspector và `defaultProps()` |
| `blocks`, `assets`, `ai` | L62-65 | API tương ứng | `assets` làm mới mỗi 8 phút (URL ký hết hạn 10 phút, L106) |
| `pageId`, `selectedId`, `device`, `prompt`, `live`, `busy`, `notice`, `save` | L67-78 | UI thuần | `selectedId` được đồng bộ với iframe qua `postMessage` (L196-205) |
| `SectionInspector.draft` | `SectionInspector.tsx:38` | bản nháp cục bộ, remount khi `JSON.stringify(props)` đổi (L360) | draft → operation → `onApply`; **không** ghi trực tiếp |
- `can(p)` = `project.permissions.includes(p)` (L83); `readOnly = !can("PROJECT_EDIT")`. Quyền hiện có ở backend: `PROJECT_READ/EDIT/SETTINGS/DELETE/PUBLISH/MEMBERS` (`access/Permission.kt`); **chưa có** `QUERY_EXECUTE`/`ACTION_EXECUTE`/`WORKFLOW_MANAGE` (contract nhắc tới, C1 sở hữu).

### 2.4 Luồng sửa schema (tất cả hội tụ vào một phễu)
```
Inspector.apply()      ┐
DnD onDragEnd (L213)   │
addSection/addBlock    ├─► applyOps(ops, summary, blockId?)  (L159)
SiteDrawer/PageBar     │      └─ run("edit") → api.patchSchema(ws,pid,revision,ops,summary)   [http-api.ts:307]
Inspector move/remove  ┘            └─ setSchema(r.schema); setRevision(r.revision); refreshVersions()
AI prompt (submitPrompt, L128) → api.sendPrompt|streamPrompt → server tự áp operation → trả {pageSchema, revision, schemaPatch[]}
restore(v) (L164)      → api.restoreVersion → {schema, revision}
```
Backend cho cả hai: `SchemaPatchEngine.apply` (≤50 op, sao chép rồi áp) → `SchemaCommitService.commit` (validate → CAS `projects.revision` → upsert schema → version bất biến → usage → audit, một transaction; lỗi `422 SCHEMA_INVALID` với `details.violations[{path,message}]`, `409 REVISION_CONFLICT`).
`run()` (L111-124) là nơi duy nhất xử lý `saving/saved/error`, `REVISION_CONFLICT`, `AI_TOKEN_LIMIT`, 401. **Hiện UI chỉ hiện `e.message`, chưa dùng `details.violations[].path`** — binding editor sẽ cần ánh xạ path→field.

### 2.5 Lớp API
- `lib/http-api.ts`: `call<T>` (L37: CSRF double-submit, timeout 15 s mặc định, ánh xạ lỗi `{code,message,requestId,details}` → `ApiError`, 401 → `onUnauthorized`), `stream<T>` (SSE cho AI, L72), object `api` (L114-345), tiền tố `P(w,p) = /workspaces/{w}/projects/{p}` (L112).
- **`call`, `stream`, `json`, `qs`, `P` không được export** (chỉ `ApiError`, `resetCsrf`, `onUnauthorized`, `api`). Hệ quả: file `lib/api/<domain>.ts` mới (D-005) **không thể** tái dùng CSRF/401/mapping lỗi nếu chưa tách lõi. Xem 4.2.
- `lib/http-types.ts`: `Section`, `PageSchema = {page, sections, site?, pages?}` (L8), `SchemaOperation` (L40-45, 12 loại), `SchemaResponse`, `PromptResponse` (`schemaPatch: SchemaOperation[]`), `ApiProject`, `AppKind` (L218), `Connector` (L224, của server app — khác Data Platform).
- Idempotency: `call(..., {idempotencyKey})`; `PublishModal` giữ khoá bằng `useRef(crypto.randomUUID())` (drawers.tsx:~117).

### 2.6 Preview và renderer
- `renderSchemaDocument(schema, RenderOptions)` (`lib/schema-preview.ts:99`) trả chuỗi HTML; `ProjectWorkspace` gọi trong `useMemo` (L208-210) rồi `<iframe srcDoc sandbox=…>` (L352). `sandbox="allow-scripts"` **chỉ** ở design mode khi có quyền sửa (script duy nhất: click-to-select gửi `studio:select`); mode AI và read-only: `sandbox=""`. E2E đang khẳng định hai giá trị này (`e2e/factory-flow.mjs:78-79`).
- Renderer là `switch` thủ công trên 8 type (L43-77), mọi chuỗi qua `escapeHtml`, ảnh chỉ nhận `asset://` (L24-29), link chỉ `#anchor`. `ProductCard`, `LandingTemplate` có trong registry nhưng **không có renderer** (`NOT_RENDERED`, ProjectWorkspace L32).
- Trạng thái mô-đun toàn cục: `ctx`, `up`, `site` (L20-23) được ghi mỗi lần render → hàm đồng bộ, không re-entrant; chấp nhận được miễn là dữ liệu truyền vào trước khi render, không `await` giữa chừng.
- **Dùng chung với publish**: `workers/render/server.ts:45,55` gọi `renderSchemaDocument`/`renderSitePages` (`published: true`), API gọi worker ở bước BUILDING và chỉ gửi `{schema, assets}`. Ngoài ra `features/library.tsx:10` (`SchemaThumb`, thumbnail template/block) cũng gọi renderer; `drawers.tsx:6` chỉ import nhưng không gọi.

### 2.7 "Edit" và "Test" hiện tại
- Không có chế độ Test/"Dùng thử" nào. Hai mode có preview là: **AI** (preview tĩnh, không script) và **Design** (preview có click-to-select, chỉnh được). Preview luôn là *render tĩnh của schema đã lưu*, không bao giờ gọi nguồn dữ liệu.
- "Mô phỏng/mock" trong code có 3 nghĩa khác nhau, đừng nhầm: (1) `NEXT_PUBLIC_API_MODE=mock` = `StudioShell` demo; (2) model AI `mock` ("Chế độ thử nghiệm", `effectiveModel`); (3) publish `provider: "mock"` (`PublishModal`). Không cái nào là Test mode dữ liệu.
- Publish: `PublishModal` → `api.publish(expectedRevision, idempotencyKey)` → poll `getDeployment` mỗi 800 ms; kết quả site tĩnh.

## 3. Đối chiếu contract với thực tế (điểm lệch/thiếu cần C0/C2/C3/C4)

| # | Phát hiện | Bằng chứng | Hệ quả cho T12 |
|---|---|---|---|
| G1 | `AppDefinitionV2.pages: List<PageDef>` nhưng Page Schema thật là `{page, sections (= trang chủ), pages? (các trang phụ), site?}` — **trang chủ nằm ở `sections` gốc**, không trong `pages` | `PageSchemaValidator.validate`, `SchemaPatchEngine.allSections`, `http-types.ts:5-8` | UI không biết đọc/ghi trang chủ theo shape nào; binding `sectionId` phải tra được trên cả hai nơi (id section duy nhất toàn site — đã đảm bảo bởi validator) |
| G2 | `AppKind` trong contract (`PAGE_SCHEMA` khởi đầu) trùng tên với `AppKind` frontend/DB (`WEBSITE_STATIC…WORKFLOW`) và khác `appType` (`PAGE_SCHEMA\|STATIC_APP`) | `http-types.ts:17-19,218` | Cần tên khác (vd `definitionKind`) để tránh nhầm; `AppKind.WORKFLOW` hiện là loại server app, **không** phải `WorkflowRuntime` |
| G3 | Contract không có thực thể **Query/DataSource/ViewModel definition** để UI liệt kê/chọn; `queryRef` trỏ "Query định nghĩa" nhưng `data-connector.md` chỉ có `QueryRequest` + `DataGateway.runQuery`. Roadmap (`ARCHITECTURE_ASSESSMENT` T6) nói AppDefinition có `dataSources, viewModels, queries…` còn contract V2 chỉ có `dataBindings, actions, extensions` | `app-definition-v2.md`, `data-connector.md` | Chưa rõ Query nằm trong AppDefinition hay bảng riêng của Data Platform; quyết định này đổi hẳn UI (sửa Query = operation + version, hay CRUD riêng + chỉ chọn) |
| G4 | `FieldMapping`, `ViewModelData`, `PageSpec`, `Column`, `ValidationContext`, `TriggerInfo` được dùng nhưng **không có shape** | cả 3 contract | Không thể mirror type; Mapping editor/preview chưa thiết kế được |
| G5 | `DataBinding{sectionId, prop, queryRef, mapping}` chỉ trỏ tới **prop cấp section**; component có prop mảng-đối-tượng (`ProductGrid.items`, `Testimonials.items`) với `itemProperties` và id item phải duy nhất (`checkItems`) | `PageSchemaValidator.checkItems` | Mapping phải sinh `id` cho mỗi item; chưa nói rõ ai sinh/ổn định |
| G6 | Component registry (`propsSchema`: `type/format/enum/maxLength/maxItems/itemProperties`) **không có** khái niệm "prop gắn được dữ liệu" hay "event phát ra" (`ActionRef.trigger = sectionId.event`) | `http-types.ts:94-100`, `V5__seed_component_registry.sql` | C2 phải thêm metadata bindable/event vào registry (và seed bằng migration do C0 cấp số) |
| G7 | Validator hiện **từ chối prop lạ và field lạ** của page/site; chỉ khóa gốc (`dataBindings`) không bị kiểm | `checkProps`, `validate` | Đúng ý contract (binding tách khỏi `props`). Frontend **không được** nhét binding vào `section.props` |
| G8 | `ActionRequest`/`TriggerInfo` không có `dryRun`/`TEST` | `action-workflow.md` | Test mode cho action có tác dụng phụ không an toàn nếu thiếu |
| G9 | Không có endpoint/đường dẫn nào được định nghĩa cho Query list/run, Action execute, Workflow start/status/cancel, DataSource list, test-run | cả 3 contract | UI chỉ biết tên interface Kotlin, chưa có HTTP contract |
| G10 | `restore` và template/block: `SaveTemplate`/`SaveBlock` sao chép schema/props (đã có `removedImages`); `dataBindings`/`actions` trỏ tới ID thuộc tenant sẽ **rò rỉ** vào template COMPANY | `template/Templates.kt`, `component/ComponentPackages.kt` | C2 phải loại binding/action khi lưu template/block; restore version cũ có thể trỏ query đã xoá → `422` |
| G11 | Dữ liệu trên site đã xuất bản: site tĩnh, không script (ADR 0009, CSP) — contract không nói binding được "đóng băng" lúc publish hay chạy lúc truy cập | `workers/render/server.ts`, `proxy.ts` | Quyết định kiến trúc, xem D-C5-03 |

## 4. Insertion points (đã kiểm tra trong code)

### 4.1 Bảng chính
| Thành phần | File / vị trí hiện có | Cách gắn (đề xuất) | Phụ thuộc |
|---|---|---|---|
| **Panel Data / Action / Workflow** (liệt kê, duyệt) | `ProjectWorkspace.tsx:22,24` (`PanelName`, `PANELS`) + khối render drawer `L372-394`; mẫu: `SiteDrawer` (`SitePanels.tsx:142`) và `Drawer` (`drawers.tsx:29`) | thêm `"data" \| "actions" \| "workflows"` vào hai hằng số; mỗi drawer là file mới `features/studio/data/*.tsx`, nhận `schema`, `ws`, `pid`, `canEdit`, `apply` (đúng chữ ký `Apply` ở `SitePanels.tsx:~24`) | T6, T8/T10 |
| **Nút mở panel** | `ProjectWorkspace.tsx:245-252` (`topActions`) | thêm nút "Dữ liệu"/"Hành động" ẩn khi không có quyền | C1 (quyền) |
| **DataSource** (chọn nguồn đã duyệt) | mẫu quản trị: `AdminApp.tsx:1066` `ConnectorsPage` (admin duyệt, credential chỉ ghi, project được cấp quyền); `http-api.ts:212-216` | Builder **chỉ chọn** nguồn đã được duyệt/cấp quyền; tạo nguồn + credential ở Admin Console (trang mới trong `AdminApp`), không bao giờ ở Builder | T8 (C3) |
| **Query** (chọn/định nghĩa) | trong drawer Data (mới); client ở `lib/api/data.ts` (mới) | tuỳ G3: (a) Query là phần AppDefinition → qua operation; (b) Query là tài nguyên Data Platform → chỉ liệt kê + `queryRef` | G3, T8 |
| **Mapping / ViewModel** | cùng drawer Data + tab trong Inspector | editor sinh `FieldMapping[]` rồi đóng gói vào operation `ADD_BINDING`/`UPDATE_BINDING`; xem trước kết quả mapping bằng test-run (6.3), không tính mapping ở client | G4, T10 |
| **Binding theo prop** | `SectionInspector.tsx:42-59` (`operations()`), `:89-107` (vòng lặp prop) | mỗi prop `bindable` hiện một điều khiển "Nguồn dữ liệu" bên cạnh ô nhập tĩnh; thay đổi biên dịch thành operation binding cùng `onApply` (cùng cơ chế draft→ops hiện có). Giá trị tĩnh vẫn là **fallback** hợp lệ | G5, G6 |
| **Binding trong outline/preview** | `ProjectWorkspace.tsx:399-406` `SortableRow` (huy hiệu "liên kết dữ liệu"); `schema-preview.ts:105` (bọc `div.__sec`, thêm class `__bound` khi design mode) | chỉ hiển thị, đọc từ `schema.dataBindings` | T6 |
| **Dữ liệu vào renderer** | `schema-preview.ts:9-19` (`RenderOptions`), `render()` `L43-77` (vd `ProductGrid` đọc `p.items` ở L53-54) | thêm option **thuần dữ liệu** `data?: Record<sectionId, Record<prop, BoundValue>>`; `render()` gọi `resolveProp(s, "items")` ưu tiên `data`, mặc định như cũ. Không fetch trong renderer, không script mới | T10/T11 |
| **Component mới** (DataTable/DataList/KPI…) | registry (C2, seed `V5__…`), renderer `schema-preview.ts` switch, nhãn `SectionInspector.tsx:6-17` (`LABELS`, `TYPE_LABELS`), `NOT_RENDERED` (`ProjectWorkspace.tsx:32`) | ba nơi phải đổi cùng lúc (registry + renderer + nhãn) | C2 migration do C0 cấp |
| **Action gắn sự kiện** | Inspector: tab "Sự kiện" của section; `ActionRef{trigger:"sectionId.event"}` | cần metadata event (G6). `ContactForm` hiện post HTML thuần tới `_forms/<sectionId>` (`schema-preview.ts:64-71`); chuyển thành Action là việc T13, không làm ở T12 | T13 |
| **Workflow** | drawer `workflows` mới; trạng thái chạy bám mẫu `PublishModal` (`drawers.tsx:106-188`: khoá idempotency `useRef`, poll 800 ms, xử lý 401) | chỉ liệt kê/khởi chạy/huỷ qua `WorkflowRuntime`; định nghĩa workflow do C4 | T13/T14 |
| **Mode Test** | `ProjectWorkspace.tsx:21,23` (`Mode`, `MODES`), `:242-244` tabs, `:343-354` preview, `:208-210` `previewDocument` | xem mục 6 | T10, T11 |
| **Kết quả AI có op mới** | `ProjectWorkspace.tsx:141-147` (tổng hợp `changed` giả định op có `sectionId`/`sectionType`) | chịu được op không có `sectionId` (`ADD_BINDING`…) | T6 |
| **Lỗi validator theo path** | `run()` `L111-124`, `errText` (`features/ui.tsx:19`) | đọc `details.violations[]` và ánh xạ `dataBindings[i].…` lên field | T6 |
| **Template/Block/Restore** | `libraryPanels.tsx` (`SaveBlockDrawer`, `SaveTemplateSection`), `restore()` `L164-169` | cảnh báo khi section có binding; restore có thể `422` | G10 |

### 4.2 Việc tách module (không làm ở PREP)
Rủi ro conflict là có thật (`http-api.ts` 33 KB, `http-types.ts` 26 KB, `ProjectWorkspace.tsx` 35 KB đều là hot file, mọi domain dùng chung). Đề xuất thứ tự, mỗi bước **behavior-preserving** và commit riêng khi bắt đầu T12:
1. `lib/api/core.ts`: chuyển `ApiError`, `csrf`, `call`, `stream`, `json`, `qs`, `P` ra khỏi `http-api.ts`; `http-api.ts` re-export để không đổi import hiện có. Mở khoá `lib/api/data.ts`, `lib/api/actions.ts`, `lib/api/workflows.ts`.
2. `lib/app-definition/` (đã có placeholder) để chứa type mirror + hàm đọc thuần (`bindingsOf(schema, sectionId)`), không gọi API.
3. Toàn bộ UI mới trong `features/studio/data/` (file mới). Trong `ProjectWorkspace.tsx` chỉ sửa **điểm nối tối thiểu**: hằng `PanelName/PANELS`, nút `topActions`, khối render drawer, một `Mode` mới, và truyền `data` vào `renderSchemaDocument`. Không tách `ProjectWorkspace` thành hook trước khi có nhu cầu.

## 5. UI state design (đề xuất, không chuẩn tắc)

### 5.1 Nguyên tắc: một nguồn sự thật = AppDefinition
Luồng đích: `UI → AppDefinition/ViewModel → Query|Action → Auth+Permission → DataGateway → Connector` (CLAUDE.md). Trong Builder điều đó có nghĩa:

| Loại dữ liệu | Sống ở đâu | Frontend được làm gì |
|---|---|---|
| Binding, mapping, ActionRef (khai báo trong AppDefinition) | `schema` (state L57) → backend | đọc từ `schema`; ghi **chỉ** qua `applyOps` (operation → validator → version bất biến). Không có `useState`/store/`localStorage` giữ bản sao |
| DataSource, credential, định nghĩa Query (tuỳ G3) | Data Platform (server) | chỉ đọc danh mục đã được duyệt/cấp quyền; credential **không bao giờ** xuống client; Builder không gõ SQL/URL (contract: "không trường chứa code/SQL/URL thô") |
| Bản nháp form (Inspector/Mapping editor) | state cục bộ của component (như `SectionInspector.draft`) | draft → operation → `onApply` → khi thành công, `schema` mới remount editor |
| Kết quả Test (rows, trạng thái chạy) | state tạm của mode Test | tách khỏi `schema`; mất khi rời Test hoặc đổi `revision` |
| Panel/chọn lựa | `view` URL + `selectedId` | như hiện nay |

Không thêm: `lib/mock-data` cho data, store song song, kiểu `DataSource/Query` tự đặt, cache bền vững (kể cả `sessionStorage`, vốn đã bị dùng cho `ws-mode-*`).

### 5.2 Định tuyến
- Panel mới (drawer, giữ URL-addressable như hiện nay): `/studio/projects/:id/data`, `/actions`, `/workflows`. Thêm vào `PanelName`/`PANELS` (ProjectWorkspace L22/L24).
- Mode mới: `/studio/projects/:id/test` — thêm vào `Mode`/`MODES` (L21/L23). **Ngoại lệ bắt buộc:** `sessionStorage` `ws-mode-<id>` (L50-53) **không** được ghi/đọc giá trị `test`, nếu không mở lại project sẽ tự chạy truy vấn dữ liệu thật.
- `design` + `test` dùng chung cột preview và `pageSections`; chỉ khác nguồn `previewDocument` (mục 6).

### 5.3 Điều khiển theo quyền
`can()` (L83) chỉ ẩn/vô hiệu nút; server vẫn là nguồn quyền. Cần các chuỗi quyền xuất hiện trong `ApiProject.permissions` (hiện chưa có; C1 chốt tên — contract dự kiến `QUERY_EXECUTE`, `ACTION_EXECUTE`, `WORKFLOW_MANAGE`, `DATASOURCE_READ`). Quy ước hiện tại của server: không tồn tại → 404, thiếu quyền → 403; `features/ui.tsx:29 stateOf` đã ánh xạ sang `StateView` `notfound/forbidden`. Project `ARCHIVED` chỉ còn `PROJECT_READ`: Test mode phải coi như chỉ xem (kiểm tra thực tế khi backend có).

### 5.4 Bốn trạng thái dữ liệu (roadmap T12) và bổ sung
Mỗi binding trong Test có trạng thái UI: `idle · loading · ready · empty · error · forbidden`, kèm cờ `truncated` (từ `QueryResult.truncated`, contract) và `rateLimited` (429 + `Retry-After`, `call()` đã dựng sẵn thông điệp). Dùng `StateView` hiện có (`loading/empty/forbidden/network/error`) cho khung; renderer chỉ cần vẽ khung skeleton/thông báo bằng HTML tĩnh đã escape. Không thêm trạng thái "stale/cache" cho tới khi T11 quyết định cache.

### 5.5 Xung đột & vòng đời
- `REVISION_CONFLICT` giữ như `run()` (L116). Test-run gắn `revision` đã chạy; nếu `revision` đổi, kết quả cũ bị bỏ (không hiển thị dữ liệu của định nghĩa khác).
- Gỡ/đổi tên section có binding: do operation của C2 xử lý (cascade hay từ chối) — C5 chỉ hiển thị lỗi `422` theo `path`.
- AI: không có model dữ liệu riêng. Op binding do AI sinh đi qua cùng `sendPrompt` → `schemaPatch`; UI chỉ cần hiển thị (L141-147).

## 6. Edit vs Test — tách biệt

### 6.1 Bảng đối chiếu
| | **Edit** (ai/design + panel data/actions/workflows) | **Test / Dùng thử** (mode `test`) |
|---|---|---|
| Mục đích | chỉnh định nghĩa | xem app chạy với dữ liệu thật của chính người dùng |
| Gọi nguồn dữ liệu | **Không** tự động. Ngoại lệ có kiểm soát: nút "Chạy thử" của Mapping editor gọi đúng một test-run (cùng endpoint với Test) và hiển thị trong panel, không đổ vào preview | Có, qua backend `DataGateway`/`ActionRuntime` (không bao giờ trực tiếp tới connector) |
| Preview | render **schema đã lưu**; section có binding hiện giá trị tĩnh (fallback) + huy hiệu "liên kết dữ liệu" | render schema đã lưu + `data` (kết quả test-run) truyền vào renderer |
| Sandbox iframe | như hiện nay (`allow-scripts` chỉ cho click-to-select, design) | `sandbox=""` (không script); chọn section bằng outline, không bằng click trong iframe |
| Ghi `schema`/version | có, qua `applyOps` | **không** (không `applyOps`, không `patchSchema`) |
| State | `schema`, `revision`, draft | `testRuns` tách biệt, chỉ trong bộ nhớ |
| Chỉ báo | như hiện nay | dải cố định "Đang chạy thử với DỮ LIỆU THẬT · quyền của bạn" + nhãn `truncated`; không thể nhầm với Edit |
| Quyền | `PROJECT_EDIT` | `QUERY_EXECUTE` (+ `ACTION_EXECUTE` cho action) — người chỉ xem có thể được cấp Test mà không sửa được |
| Audit | audit version như cũ | audit do DataGateway/ActionRuntime ghi phía server (contract); UI không tự ghi |

### 6.2 Bất biến (invariant) của Test mode
- **I1** Dữ liệu test không rời React state: không vào `schema`, `localStorage`, `sessionStorage`, URL, lịch sử prompt/AI, `notice`/toast, log console.
- **I2** Không có chuỗi SQL/URL/credential ở client; request chỉ chứa `queryId` + `params` + `page` (đúng `QueryRequest` contract). `tenantId` không bao giờ do client gửi (`tenant-context.md` quy tắc 1).
- **I3** Mọi giá trị dữ liệu đi qua `escapeHtml` trước khi vào HTML preview; trường ảnh trong ViewModel chỉ chấp nhận `asset://` (renderer hiện cũng vậy, L24-29) vì CSP `img-src` chặn URL ngoài.
- **I4** Hành động có tác dụng phụ **không** chạy trong Test cho tới khi backend có chế độ dry-run (G8). Trước đó nút Action ở Test bị vô hiệu kèm lý do; không giả lập kết quả.
- **I5** Mỗi lần chạy có `Idempotency-Key` riêng khi gọi Action/Workflow (mẫu `PublishModal`), và có timeout/huỷ (`AbortController`) — `call()` mặc định 15 s, query dài cần `signal` riêng như `sendPrompt` làm.
- **I6** Giới hạn: hiển thị `truncated`, không tự phân trang vô hạn; tôn trọng 429.
- **I7** Test không đổi `save` indicator ("Đã lưu") và không làm `busy` của Edit.
- **I8** Dữ liệu thật chạy dưới quyền người đang Test, **khác** quyền của người xem site xuất bản — UI ghi rõ ("Bạn thấy theo quyền của bạn").
- **I9** Thoát Test, đổi `revision`, đổi trang hoặc hết phiên (401) ⇒ xoá `testRuns`.
- **I10** Mock mode (`StudioShell`) không có Test mode data; không thêm dữ liệu giả vào http mode.

### 6.3 Luồng Test query (đề xuất)
`enter test` → từ `schema.dataBindings` (của trang đang xem) suy ra tập `queryRef` cần chạy → với mỗi binding gọi `dataApi.runQuery(ws, pid, {queryId, params, page})` (client mới ở `lib/api/data.ts`) song song có giới hạn → cập nhật `testRuns[bindingId]` → `renderSchemaDocument(schema, {…, data})` → `iframe sandbox=""`. Mapping được **backend** áp (`MappingEngine.apply` trong `DataGateway.runQuery` trả `ViewModelData`, contract); client không tự map để khỏi có hai bộ luật map.
Tham số Query (`params`): nhập ở panel Test (form sinh từ khai báo tham số của Query — hiện **chưa có** khai báo, G3/G9), mặc định rỗng.

### 6.4 Test Action / Workflow
- Action: chỉ khi backend hỗ trợ `dryRun`/`TriggerInfo.TEST` (G8). UI: xác nhận trước khi chạy, hiển thị `ActionResult.Ok|Failed(code, retryable)`.
- Workflow: `start` (idempotencyKey bắt buộc theo contract) → poll `status` (mẫu 800 ms của `PublishModal`) → `cancel`. Không thực thi workflow thật từ Test nếu chưa có dry-run; chỉ xem trạng thái các run có sẵn.

### 6.5 "Publish cùng dữ liệu" (roadmap T12) — quyết định cần có
Site xuất bản là HTML tĩnh, không script, CSP `default-src 'self'` (`workers/render/server.ts`, `proxy.ts`, ADR 0009). Ba hướng:
- **A. Chụp lúc publish (build-time snapshot):** API gọi `DataGateway` khi BUILDING, truyền `data` vào render worker (`/render-site` hiện chỉ nhận `{schema, assets}`), site là tĩnh nhưng dữ liệu đóng băng. Giữ nguyên mô hình bảo mật. **Rủi ro rò rỉ:** dữ liệu riêng tư có thể bị nướng vào site `PUBLIC` ⇒ cần chính sách rõ (vd chỉ cho site `PRIVATE`, hoặc binding phải được duyệt "xuất bản công khai được"). Cần sửa `workers/render/server.ts` và `publish/**` (C0-gated).
- **B. Gọi lúc chạy (client script / runtime gateway):** cần script và route dữ liệu cho site công khai ⇒ phá bất biến "site tĩnh không script", mở bề mặt tấn công mới; **không đề xuất** trong T12.
- **C. App có máy chủ (`SERVER_APP`/`STATIC_APP`):** đã có Connector Proxy; ngoài phạm vi AppDefinition V2.
Đề xuất chọn **A**, ghi `D-C5-03`. Renderer sẽ nhận `data` giống nhau ở Test và publish ⇒ preview Test = bản xuất bản tại thời điểm đó (một renderer).

## 7. Backend contract cần có

Trạng thái: **CÓ** = đã có shape trong contract/code; **MỘT PHẦN** = có tên nhưng thiếu shape; **THIẾU** = chưa định nghĩa.

| # | Hạng mục | Owner | Trạng thái | Ghi chú |
|---|---|---|---|---|
| 1 | Cơ chế đọc/ghi AppDefinitionV2 qua HTTP (tái dùng `GET/PATCH /schema` hay endpoint mới), `schemaVersion` | C2 | THIẾU | Frontend phải chịu được thiếu `schemaVersion` (schema cũ) |
| 2 | Shape gốc AppDefinitionV2 khớp Page Schema thật (G1) | C2 | MỘT PHẦN | `pages` lệch với `sections` gốc |
| 3 | Loại operation: `ADD_BINDING`, `REMOVE_BINDING`, `ADD_ACTION_REF`, … (contract ghi "…"); cần `UPDATE_BINDING`/`UPDATE_MAPPING`/`REMOVE_ACTION_REF` | C2 | MỘT PHẦN | thêm bên cạnh `OperationTypes` cũ |
| 4 | Lỗi validator: `422 SCHEMA_INVALID` + `violations[{path,message}]` cho đường dẫn `dataBindings[i]…`, `actions[i]…` | C2 | CÓ (cơ chế) / THIẾU (path mới) | |
| 5 | Metadata registry: prop gắn được dữ liệu, event phát ra, component DataTable/DataList/KPI (G6) | C2 (+C0 cấp migration) | THIẾU | |
| 6 | Danh mục DataSource cho project/workspace (id, tên, type, trạng thái; **không** secret) | C3 | THIẾU | `DataSourceRef` có shape, endpoint chưa có |
| 7 | Danh mục Query: id, tên, khai báo tham số, DataSource, (tuỳ G3) CRUD | C3 | THIẾU | |
| 8 | `QueryRequest{queryId, params, page, tenant}` — server tự điền `tenant` | C3 | CÓ (shape) | endpoint THIẾU |
| 9 | Kết quả `ViewModelData`, `QueryResult{columns, rows, truncated}`, `PageSpec`, `Column` | C3 | MỘT PHẦN | `ViewModelData`/`PageSpec` chưa có field |
| 10 | `FieldMapping` + Transform DSL (vd `full_name→name`), version/validate | C3/T10 | THIẾU | |
| 11 | Schema Discovery (cây bảng/cột/endpoint) và mẫu dữ liệu đã che PII để gợi ý mapping (T9) | C3 | THIẾU | không bắt buộc cho T12 |
| 12 | `ActionRequest/ActionResult` | C4 | CÓ (shape) | endpoint, danh mục Action, khai báo input THIẾU |
| 13 | Cờ `dryRun`/`TriggerInfo.TEST` (G8) | C4 | THIẾU | chặn Test Action |
| 14 | `WorkflowRuntime.start/status/cancel` + `WorkflowRunStatus`, danh sách workflow/run | C4 | MỘT PHẦN | `WorkflowRunStatus` chưa có field |
| 15 | Quyền mới trong `ApiProject.permissions` (`QUERY_EXECUTE`…) | C1 | THIẾU | |
| 16 | Mã lỗi ổn định: hết thời gian, vượt giới hạn dòng, nguồn lỗi, 429 | C3/C4 | THIẾU | UI cần phân biệt để dùng `StateView` |
| 17 | Loại bỏ binding/action khi lưu Template/Block COMPANY; hành vi `restore` khi query đã xoá (G10) | C2 | THIẾU | |
| 18 | Nhận `data` ở render worker & luồng publish (6.5-A) | C0 (workers/publish) | THIẾU | |
| 19 | Đường dẫn HTTP đề xuất: theo mẫu `/workspaces/{w}/projects/{p}/…` (tenant do server suy ra) | C2/C3/C4 | THIẾU | đề xuất ở `D-C5-02` |

## 8. Phụ thuộc chặn và thứ tự đề xuất

Theo `BOARD.md`: T12 phụ thuộc T6 (AppDefinition ViewModel), T8 (Query API) và PREP-T12; theo roadmap còn T10 (ViewModel + Mapping) và T11 (Data Gateway). Chưa task nào bắt đầu (T6/T8 `READY`).

1. **Hiện có thể làm không giả API (C5):** tách `lib/api/core.ts` (behavior-preserving); mirror type phần nguyên văn contract (đã có); thiết kế E2E. Không thêm màn hình/drawer chạy được cho tính năng chưa có backend.
2. **Sau T6 (C2):** mở rộng `PageSchema`/`SchemaOperation` type trong `http-types.ts`; `lib/app-definition/` đọc binding; badge trong outline/preview; ánh xạ lỗi `violations`; cảnh báo template/restore.
3. **Sau T8 (C3) có danh mục DataSource/Query + run:** `lib/api/data.ts`; drawer Data; Test mode query.
4. **Sau T10/T11:** Mapping editor + xem trước mapping; `data` trong renderer; component dữ liệu mới; publish snapshot (cần C0).
5. **Sau T13 (C4):** Action/Workflow panel + Test Action.
Chặn cứng, không thể làm trước: G1–G4, G8–G9 (contract), quyền C1, migration registry (C0).

## 9. Rủi ro

| Rủi ro | Mức | Giảm thiểu |
|---|---|---|
| Conflict trên hot file `http-api.ts`/`http-types.ts`/`ProjectWorkspace.tsx`/`schema-preview.ts` khi 5 agent cùng chạm | cao | chỉ C5 sửa; UI mới vào file mới (`features/studio/data/*`, `lib/api/*`, `lib/app-definition/*`); điểm nối tối thiểu trong hot file (4.1); `lib/api/core.ts` tách trước, behavior-preserving |
| Renderer dùng chung preview/publish: lỗi hiển thị data làm hỏng site xuất bản | cao | `data` là option; thiếu `data` ⇒ đầu ra byte-giống hiện tại; test snapshot HTML cũ trước khi đổi |
| Rò rỉ dữ liệu riêng tư vào site công khai (6.5-A) hoặc vào template COMPANY (G10) | cao | quyết định chính sách trước; loại binding ở template/block |
| `ProjectWorkspace` phình thêm (35 KB, ~20 state) | trung bình | không thêm state data vào component; dùng hook/provider ở file mới |
| `Mode=test` tự mở lại và chạy truy vấn thật | trung bình | loại khỏi `sessionStorage` (5.2) |
| Mâu thuẫn cách đặt tên `AppKind`/`WORKFLOW` (G2) | thấp | đổi tên ở contract trước khi mirror |
| `call()` timeout 15 s cắt truy vấn dài | thấp | `signal` riêng, thông điệp rõ |
| Hành vi `PageSchemaValidator` từ chối prop lạ khiến frontend cũ lưu schema mới | thấp | binding ở khóa gốc, không trong `props` (G7) |

## 10. Kịch bản E2E đề xuất (không viết/sửa E2E hiện có; cần stack)

Khi T6/T8/T10/T11 xong, thêm `e2e/data-binding-flow.mjs` (cùng khung `factory-flow.mjs`: `check()`, `login()`, selector role/label):
1. Admin tạo DataSource (nguồn REST/CSV thử) và gán cho project; Builder thấy trong panel Data, **không** thấy credential (kiểm tra network response không chứa secret).
2. Design: chọn `ProductGrid` → Inspector → gắn Query vào `items` → map field → lưu; xác nhận `revision` tăng, có version mới, `dataBindings` có trong schema trả về.
3. Preview ở Edit: không có request tới endpoint Query (đếm network); iframe `sandbox` vẫn là `allow-scripts` (design).
4. Test: `/test` hiện dải "DỮ LIỆU THẬT"; có đúng một request run-query/binding; iframe `sandbox=""`; ba trạng thái loading/empty/error (route-mock lỗi 500, rỗng, 403); `truncated`.
5. Bất biến: sau Test, `GET /schema` không đổi `revision`; `sessionStorage`/`localStorage` không chứa dữ liệu hàng; mở lại project không vào Test.
6. Quyền: viewer chỉ-xem không thấy nút sửa binding nhưng (nếu được cấp) chạy được Test; user không có quyền ⇒ `forbidden`.
7. Publish: (theo quyết định 6.5) site tĩnh chứa dữ liệu snapshot, không có `<script>`; site `PUBLIC` bị chặn nếu binding không được duyệt.
8. Regression: `factory-flow.mjs` không đổi và vẫn pass (nhãn "Cấu trúc trang", region "Chỉnh sửa …", `iframe.previewFrame` sandbox `allow-scripts`/`""`).

## 11. Kiểm thử trong PREP
Chạy trên bản sao scratch của worktree (sandbox Linux arm64, `npm ci` từ `package-lock.json`, kèm file mới `lib/app-definition/contract-mirror.ts`); worktree gốc không có `node_modules` và không bị sinh `.next`.

| Lệnh | Kết quả |
|---|---|
| `npx tsc --noEmit -p tsconfig.json` — trước khi thêm placeholder | PASS (0 lỗi) |
| `npx tsc --noEmit -p tsconfig.json` — sau khi thêm placeholder | PASS (0 lỗi) |
| `npm run build` (mock, static export) | PASS |
| `NEXT_PUBLIC_API_MODE=http NEXT_DIST_DIR=.next-http npm run build` (http mode, có `ProjectWorkspace`) | PASS |
| `node e2e/factory-flow.mjs` và các E2E khác | **không chạy**: không có stack/server ở `127.0.0.1:3100` (`ERR_CONNECTION_REFUSED` không phải regression code); E2E cần Docker + Chrome trên Mac |
| Backend `./gradlew test` | không chạy: PREP không đổi backend; cần JDK 21 + Docker |

Không có thay đổi hành vi UI: không sửa file nào đang chạy (`ProjectWorkspace`, `schema-preview`, `http-api`, `http-types` nguyên trạng); placeholder không được import.

## 12. Dòng đã ghi ở file điều phối
- `BLOCKERS.md`: B-C5-01 (G1+G2), B-C5-02 (G3+G4+G9), B-C5-03 (G6), B-C5-04 (G8), B-C5-05 (quyền, C1), B-C5-06 (render worker/publish, C0), B-C5-07 (G10).
- `DECISIONS.md` (PROPOSED): D-C5-01 (Test/Edit tách, Test không ghi schema), D-C5-02 (đường dẫn API theo project, tenant do server suy ra), D-C5-03 (publish snapshot + chính sách rò rỉ).
- `BOARD.md`: dòng PREP-T12.
