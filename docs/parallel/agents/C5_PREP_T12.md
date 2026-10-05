# C5 · PREP-T12 — Builder / Data Binding Architecture Audit

> Prompt tự đủ context — copy toàn bộ vào Claude Desktop mở tại worktree **`../xweb-c5`** (branch **`agent/c5-web`**). Bạn là **C5 (Web Builder / Admin UI / E2E)**.
> Base commit bắt buộc: `d3c7065d3b6963dc625be5d0725922f5004fb818`. Trước khi làm: `pwd`, `git branch --show-current` (phải là `agent/c5-web`), `git rev-parse HEAD` (phải có base commit trong lịch sử), `git status --short` (sạch).

## 1. Project context
Dự án **XWEB** (repo `system-web-studio`): nền tảng nội bộ "AI Software Factory". Bạn là một trong 5 Claude (C1–C5) làm **song song** trên 5 git worktree riêng; **C0 (Architect/Integrator)** giữ contract, cấp migration, merge/cherry-pick và xử lý file dùng chung. Bạn không cần đọc cuộc chat nào khác — file này là đủ, nhưng **bắt buộc đọc thêm** trước khi code: `CLAUDE.md`, `docs/parallel/OWNERSHIP.md`, các contract trong `docs/contracts/` liên quan task, `docs/parallel/BOARD.md`, `docs/parallel/BLOCKERS.md`, `docs/parallel/DECISIONS.md`.

Worktree/branch của bạn được tạo từ BASE COMMIT `d3c7065d3b6963dc625be5d0725922f5004fb818` (nhánh nền `feat/production-hardening`). Không merge main, không rebase lên nhánh khác, không push, không GitHub Actions.

## 2. Current architecture (đã đối chiếu code thật)
- **Modular Monolith** Kotlin 2.2 / Spring Boot 4.1 / JDK 21 / Jackson 3 (`tools.jackson`), package gốc `com.systemwebstudio` ở `backend/src/main/kotlin/...`. **Không phải microservices** — không được đề xuất/chuyển sang microservices.
- Backend khoảng 105 file Kotlin, ~20 module: `access admin ai asset audit code common component identity integration maintenance member project prompt publish runtime schema settings template version`.
- **Frontend Next.js ở ROOT repo** (không có thư mục `frontend/`): `app/ features/{admin,auth,studio} components/ lib/ e2e/`.
- PostgreSQL + Flyway (hiện tới **V25**), Redis (session), MinIO, RabbitMQ.
- Ngoài JVM có các plane riêng: render worker, build runner, Forgejo, nginx/site gateway, generated app containers.
- Hiện **chưa có**: multi-tenant thật (không có bảng `tenants`/`tenant_id`), Tenant Admin, group/department permission thật, ABAC, RLS, App Model hợp nhất, metadata DataSource/Query/ViewModel/Mapping/Transform, Schema Discovery, metadata Event/Action/Workflow. Đơn vị tổ chức thực tế là **Workspace**; Super Admin = `users.system_admin`; Tenant Admin gần nhất = `WORKSPACE_ADMIN`; quyền = workspace role + project role (`PermissionMatrix`); Department chỉ để báo cáo.
- `projects` chứa hai kiểu app: **PAGE_SCHEMA** (metadata JSON; AI sinh typed operations → `SchemaPatchEngine` → `PageSchemaValidator` → version bất biến; AI không sinh code tùy ý) và **STATIC_APP** (source trong Git/Forgejo, build sandbox, container có DB riêng). V2 **không** rewrite; V2 mở rộng PAGE_SCHEMA thành App Definition V2 theo hướng backward compatible.

Luồng dữ liệu chuẩn (đích): `UI → App Definition/ViewModel → Query|Action → Auth → Permission → Data Gateway → Connector → DB/REST/External → Mapping/Transform → UI`.
Luồng AI chuẩn: `Prompt → Structured Operation → App Definition → Validator → Version`. **AI không có model dữ liệu riêng.**

## 3. Preserved systems — KHÔNG được phá, làm yếu hay đổi hành vi
Page Schema; `SchemaOperation`; `SchemaPatchEngine`; `PageSchemaValidator`; component registry + immutable component version; immutable project versions; `SchemaCommitService` (validate → CAS revision → version bất biến → audit trong 1 transaction); AI Gateway + AI governance; Connector Proxy; `PublicAddress` SSRF guard; credential chỉ ở server; `SecretsCrypto`; audit append-only (trigger DB); publish pipeline; render/build/runtime planes; `PAGE_SCHEMA` và `STATIC_APP`; cô lập runtime/server-app hiện có. Giữ **backward compatibility** (API hiện tại, bảng `projects`, schema cũ). Không xóa feature cũ để làm V2.

## 4. Ownership (tóm tắt — nguồn chân lý là `docs/parallel/OWNERSHIP.md`)
- **C1** Tenant/Identity/Permission/Sharing: `identity/** access/** member/** tenancy/**(NEW) organization/**(NEW) sharing/**(NEW)`. Hot: `AccessService`, `PermissionMatrix`.
- **C2** App Definition/Schema/Version/Component: `project/** schema/** version/** component/** template/** ai/** prompt/** asset/** app/definition/**(NEW)`. Hot: `AppDefinition*`, `SchemaOperation`, `SchemaPatchEngine`, `PageSchemaValidator`.
- **C3** Data Platform: `data/datasource/** data/discovery/** data/query/** data/mapping/** data/gateway/**` (NEW). Hot: `DataConnector`, `DataGateway`, `MappingEngine`, `QueryExecutor`.
- **C4** Action/Workflow: `logic/action/** logic/workflow/** logic/scheduler/**` (NEW). Hot: `ActionRuntime`, `WorkflowRuntime`.
- **C5** Web Builder/Admin UI/E2E (frontend ở root): `app/** features/** components/** lib/schema-preview* lib/app-definition/** e2e/**`. Hot: `ProjectWorkspace`, `schema-preview.ts`, `lib/http-api.ts`, `lib/http-types.ts`.
- **C0** Integrator: contract, cấp migration, merge, integration test, shared/hot files.

## 5. Shared / C0-gated — KHÔNG tự sửa nếu không có approval của C0
`backend/build.gradle.kts`, `backend/gradle.lockfile`, `application*.yml`, `common/**`, shared test support (`backend/src/test/**/support/**`: `IntegrationTestBase`, `TestFixtures`, `ApiSession`), `identity/SecurityConfiguration.kt` (trừ khi bạn là C1 và task nói rõ), `runtime/Gateway.kt` (chứa AppGatewayController + Connector Proxy + AdminConnector + `PublicAddress`), `publish/**`, `runtime/**`, `code/**`, `audit/**`, `admin/**`, `settings/**`, `maintenance/**`, `integration/**` (trừ phần task cho phép), `package.json`, `package-lock.json`, `tsconfig.json`, `next.config.ts`, `compose*.yml`, `.env*`, `docs/contracts/**`, `docs/parallel/**` (trừ dòng task/BLOCKERS của bạn), `CLAUDE.md`.
Cần sửa một trong số đó, hoặc file của owner khác → **không tự sửa**: ghi dòng vào `docs/parallel/BLOCKERS.md` (loại `needs-hot-file` / `needs-shared-file` / `needs-contract-change`) và tiếp tục phần việc không bị chặn. Đổi contract chung → phải có mục trong `docs/parallel/DECISIONS.md` (đề xuất PROPOSED; C0 duyệt).

## 6. Migration rule (DUY NHẤT)
- Flyway hiện ở **V25**. Agent **KHÔNG tự chọn** migration version, **không tạo** file migration khi chưa được cấp số.
- Cần migration → ghi request vào mục *Migration requests* của `docs/parallel/BOARD.md` (task, requester, mô tả thay đổi schema). **C0 cấp next available version** (V26, V27, …). Một version gắn với đúng một task. Migration được merge theo thứ tự tăng dần. **Không bật `outOfOrder=true`.** Không sửa migration đã tồn tại (V1–V25). Không đổi tên bảng `projects`.
- Chưa được cấp số → dừng phần cần DB đó, ghi request, làm phần còn lại.

## 7. Quy tắc làm việc chung
- Làm việc **chỉ** trong worktree của bạn; kiểm `git branch --show-current` đúng nhánh trước khi sửa. Không sửa worktree khác.
- Không `push`, không `merge main`, không `reset --hard`, không `clean -fd`, không `push --force`, không xóa nhánh/worktree.
- Chỉ stage file bạn đã có chủ đích sửa (`git add <path>`), không `git add -A` bừa.
- Test: backend `cd backend && ./gradlew test` (Testcontainers, cần Docker + JDK 21); frontend `npx tsc --noEmit -p tsconfig.json`, `npm run build`; E2E `node e2e/<flow>.mjs` (cần stack). Nếu môi trường của bạn không chạy được một loại test, **ghi rõ "không chạy được" và lý do** — không báo pass khi chưa chạy, không sửa test cũ chỉ để xanh. Baseline đã biết (KHÔNG phải kết quả chạy mới): typecheck UI PASS; backend lần chạy gần nhất có sẵn trên đĩa 192 tests, 0 failures, 3 skipped (xem `docs/parallel/BASELINE.md`).
- Mỗi task: **test và commit riêng**. Cập nhật dòng task của bạn trong `docs/parallel/BOARD.md` (Status/Commit) trong chính commit của task đó hoặc commit docs đi kèm.

## 8. Task objective
**Chuẩn bị** T12 (Builder data binding) bằng một **audit kiến trúc frontend**: lập bản đồ Builder hiện tại và xác định chính xác điểm gắn cho Data / Action / Workflow và hai chế độ Edit/Test. Chưa implement T12 đầy đủ vì T10/T11 và backend contract chưa tồn tại. **Không fake backend, không tạo kiến trúc mock cạnh tranh với AppDefinition.**

## 9. Exact scope
1. Đọc kỹ (frontend ở **root repo**): `features/studio/ProjectWorkspace.tsx`, `StudioApp.tsx`, `drawers.tsx`, `libraryPanels.tsx`, `SitePanels.tsx`, `CodeWorkspace.tsx`/`CodePanels.tsx`, `features/ui.tsx`, `features/library.tsx`, `features/routing.ts`, `features/session.tsx`, `lib/schema-preview.ts`, `lib/preview-document.ts`, `lib/http-api.ts`, `lib/http-types.ts`, `lib/api-client.ts`, `lib/types.ts`, `lib/mock-data.ts`, `app/**`, `components/**`, `e2e/*.mjs`, `proxy.ts`; và backend contract: `docs/contracts/app-definition-v2.md`, `data-connector.md`, `action-workflow.md`; backend thật `schema/SchemaOperation.kt` + `PageSchemaValidator.kt` để biết cấu trúc schema mà Builder đang chỉnh.
2. Viết `docs/parallel/audit/PREP-T12-builder-architecture.md` (thư mục mới thuộc task) gồm:
   - **Current Builder architecture map**: component tree, nguồn state, luồng sửa schema (Dnd, Inspector, Preview, Publish, Versions, AI prompt), cách gọi API (`http-api.ts`), cách preview render (iframe sandbox, `schema-preview`), chế độ mock vs http.
   - **Insertion points**: chính xác file/component/hàm nơi sau này gắn **ViewModel, Query, Mapping, DataSource, Action** (và Workflow).
   - **UI state design**: đề xuất state/panel/routes cho Data, Action, Workflow; cách giữ **một nguồn sự thật = AppDefinition** (UI chỉ đọc/ghi qua operation → validator → version, giống Page Schema hiện tại).
   - **Edit vs Test separation**: Edit = chỉnh định nghĩa (không gọi nguồn thật ngoài việc test có kiểm soát); Test = chạy preview với dữ liệu thật qua backend Query/Action, tách trạng thái, có chỉ báo rõ, không ghi bẩn schema.
   - **Required backend contracts**: danh sách endpoint/type phía backend cần có (từ C2/C3/C4), kèm nơi hiện đã rõ và nơi còn thiếu.
   - **Blocking dependencies**: T6 (shape `AppDefinitionV2`), T8 (Query/DataSource API), quyền (C1), T10/T11…; thứ tự đề xuất.
   - Rủi ro conflict: `lib/http-api.ts` & `lib/http-types.ts` (~33 KB/26 KB) và `ProjectWorkspace.tsx` (~35 KB) là hot file của bạn — đề xuất tách module (ví dụ `lib/api/<domain>.ts`) nhưng **chưa refactor lớn**.
3. Được phép (nhỏ, tùy chọn): thêm **type/interface placeholder** phía frontend (ví dụ trong `lib/app-definition/`) **chỉ khi** nó phản ánh đúng contract `app-definition-v2.md` hiện có; ghi chú rõ "mirror of backend contract, not authoritative". Không tự sáng tạo schema khác backend; nếu contract thiếu → ghi `needs-contract-change` và dừng ở tài liệu.
4. Không thêm màn hình chạy được cho tính năng chưa có backend.

## 10. What NOT to do
- Không nối fake data runtime, không mock dataSource/query cạnh tranh AppDefinition, không local store song song với schema.
- Không refactor lớn `ProjectWorkspace.tsx`/`http-api.ts`; không đổi hành vi UI hiện tại; không xóa/đổi tên component đang dùng.
- Không sửa backend, không migration (C5 không bao giờ có migration), không sửa `package.json`/lockfile/`next.config.ts`/`tsconfig.json` (C0). Cần thư viện mới → blocker.
- Không sửa E2E hiện có để làm xanh; chỉ đề xuất kịch bản mới trong tài liệu.
- Không đổi `docs/contracts/**` (đề xuất qua `DECISIONS.md`).

## 11. Acceptance criteria
- Tài liệu audit có đủ 5 mục: architecture map, insertion points, UI state design, Edit vs Test separation, required backend contracts + blocking dependencies.
- Mỗi insertion point trỏ đến file/hàm thật (đã kiểm tra trong code).
- Nếu có placeholder type: khớp contract, được đánh dấu không-authoritative, typecheck PASS.
- UI hiện tại không đổi hành vi; `npx tsc --noEmit -p tsconfig.json` PASS (bằng hoặc tốt hơn baseline).

## 12. Tests
- `npx tsc --noEmit -p tsconfig.json` (baseline PASS). `npm run build` nếu môi trường cho phép (trên máy đã dựng sẵn `node_modules` đúng nền tảng); E2E chỉ chạy nếu có stack, và ghi rõ nếu không chạy được. Không báo pass khi chưa chạy.

## 13. Commit rule
Commit nhỏ trên `agent/c5-web`, ví dụ `docs(web): PREP-T12 builder architecture audit`, `chore(web): app-definition type placeholders` (nếu có). Cập nhật dòng PREP-T12 trong `docs/parallel/BOARD.md`.

## Final report format (bắt buộc, đúng cấu trúc)
```
TASK REPORT — C5 · PREP-T12 — Builder / Data Binding Architecture Audit

Files changed:
- ...

Tests:
- đã chạy: <lệnh> → <kết quả>
- không chạy được: <lệnh> → <lý do>

Blockers / dependencies:
- ... (hoặc "none"; kèm ID dòng đã ghi trong BLOCKERS.md/BOARD.md)

Migration requests:
- ... (hoặc "none")

Preserved systems untouched: YES / NO (nếu NO, giải thích)

Commit SHA:
- ...
```
Sau khi báo cáo: **DỪNG**. Không tự bắt đầu task khác, không mở rộng scope.
