# C4 · PREP-T13 — ActionRuntime Architecture + Scaffold

> Prompt tự đủ context — copy toàn bộ vào Claude Desktop mở tại worktree **`../xweb-c4`** (branch **`agent/c4-workflow`**). Bạn là **C4 (Action / Workflow)**.
> Base commit bắt buộc: `d3c7065d3b6963dc625be5d0725922f5004fb818`. Trước khi làm: `pwd`, `git branch --show-current` (phải là `agent/c4-workflow`), `git rev-parse HEAD` (phải có base commit trong lịch sử), `git status --short` (sạch).

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
**Chuẩn bị** T13 (ActionRuntime) — chưa triển khai đầy đủ vì `DataGateway` (C3) và `AppDefinition` (C2) chưa ổn định. Làm **kiến trúc + scaffold framework** độc lập implementation của C3: domain model, port/interface, runtime khung và handler đăng ký. Không hard-code `DataGateway`.

## 9. Exact scope
1. Đọc: `docs/contracts/action-workflow.md`, `data-connector.md`, `permission-model.md`, `tenant-context.md`, `app-definition-v2.md`; `docs/adr/0009`, `0017`; cơ chế publish làm mẫu **chỉ đọc**: `publish/DeploymentProcessor.kt`, `PublishWorker.kt`, `integration/queue/JobQueue.kt` (idempotency, compare-and-set, retry/DLQ, sweeper).
2. Tạo package `com.systemwebstudio.logic.action` (và khung `logic.workflow`, `logic.scheduler` chỉ để chứa interface rỗng/placeholder nếu contract cần; **không** triển khai sâu):
   - Model tối thiểu: `Event`, `ActionDefinition`, `ActionContext`, `ActionInput`, `ActionResult` (Ok / Failed có mã + `retryable`), `ActionHandler`, `ActionRuntime`.
   - `ActionType` dự kiến: `NAVIGATE`, `SUBMIT_FORM`, `CREATE_RECORD`, `UPDATE_RECORD`, `DELETE_RECORD`, `CALL_API`, `NOTIFY`, `START_WORKFLOW`.
   - `ActionRuntime.execute(...)` trả về kết quả có kiểu, chọn handler theo `ActionType` từ registry; kiểm tra input theo định nghĩa; áp giới hạn (timeout, kích thước input, độ sâu) ở mức khung.
   - Các nhu cầu bên ngoài đi qua **port** (interface do C4 định nghĩa, ví dụ `ActionDataPort`, `ActionNotifyPort`, `ActionAuthorizer`, `WorkflowStarterPort`, `ActionAuditPort`). Đây là chỗ sau này C3 `DataGateway`, C1 `AccessService`, `AuditService` được nối; **không import** trực tiếp class của C3.
   - Handler: chỉ scaffold/handler tối giản hoặc stub trả "NOT_IMPLEMENTED" có kiểu; handler `NAVIGATE`, `NOTIFY` có thể hoàn chỉnh nếu không cần phụ thuộc ngoài. Các handler dữ liệu (`CREATE/UPDATE/DELETE_RECORD`, `SUBMIT_FORM`, `CALL_API`) chỉ định nghĩa hợp đồng qua port.
   - Idempotency key, trạng thái chạy, audit: định nghĩa interface; cài đặt in-memory cho test nếu cần.
3. Viết **tài liệu thiết kế** `docs/parallel/audit/PREP-T13-action-runtime-design.md` (thư mục mới thuộc task): sơ đồ phụ thuộc, danh sách port và ai cung cấp implementation, action type và dữ liệu vào/ra, mô hình lỗi/retry, quyền, tenant, audit, những gì còn phụ thuộc (C1/C2/C3), thứ tự tích hợp đề xuất.
4. Ghi dependency vào `BLOCKERS.md`/`BOARD.md` cho thứ bạn cần từ C1 (permission `ACTION_EXECUTE`…), C2 (`ActionRef`/`actions[]` shape), C3 (`DataGateway` signature).
5. Đề xuất đổi contract → `DECISIONS.md` (PROPOSED); không tự sửa `docs/contracts/**`.

## 10. What NOT to do
- Không hard-code hoặc import `DataGateway`/`DataConnector`; không đoán signature của C3.
- Không implement workflow engine/scheduler sâu, không dùng RabbitMQ thật trong task này, không sửa `integration/queue/**` hay `publish/**`.
- Không **migration** (chưa được cấp; nếu cần lưu run state → ghi request BOARD.md và dừng phần đó, dùng port + in-memory).
- Không thực thi code do người dùng/AI cung cấp trên JVM; Action là khai báo, có kiểu.
- Không sửa `access/**` (C1), `app/definition/**`/`schema/**` (C2), `data/**` (C3), frontend (C5), shared files.
- Không đổi API hiện tại, không endpoint HTTP công khai mới nếu chưa có quyền.

## 11. Acceptance criteria
- Package `logic.action` có đủ: `Event`, `ActionDefinition`, `ActionContext`, `ActionInput`, `ActionResult`, `ActionHandler`, `ActionRuntime` + registry + các port, biên dịch được, **không phụ thuộc** class của C3.
- `ActionRuntime` chạy được end-to-end trong test với port giả: chọn đúng handler, trả `ActionResult` đúng, từ chối action lạ/ input sai/ thiếu quyền (qua `ActionAuthorizer` giả).
- Tài liệu thiết kế đầy đủ, dependency và blocker đã ghi.
- Không migration; không file cấm bị sửa; test hiện có xanh.

## 12. Tests
- Unit test trong `backend/src/test/kotlin/com/systemwebstudio/logic/action/`: registry, từng handler đã có, mô hình lỗi, idempotency (nếu có), quyền, giới hạn input.
- Chạy `cd backend && ./gradlew test`; baseline 192/0 fail/3 skipped. Ghi rõ nếu không chạy được.

## 13. Commit rule
Commit nhỏ trên `agent/c4-workflow`, ví dụ `feat(action): action domain model and ports`, `feat(action): ActionRuntime scaffold with handler registry`, `docs(action): PREP-T13 design`. Cập nhật dòng PREP-T13 trong `docs/parallel/BOARD.md`.

## Final report format (bắt buộc, đúng cấu trúc)
```
TASK REPORT — C4 · PREP-T13 — ActionRuntime Architecture + Scaffold

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
