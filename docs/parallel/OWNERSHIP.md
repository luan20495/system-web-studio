# OWNERSHIP — Parallel development (Phase 0)

> Đọc file này **trước khi sửa bất kỳ code nào**. Không sửa file của owner khác. Cần đổi file của owner khác → ghi dependency vào `BOARD.md` / `BLOCKERS.md`, không tự sửa.

Kiến trúc: **Modular Monolith** Kotlin/Spring (Boot 4, JDK 21, package gốc `com.systemwebstudio`) + Next.js (App Router, **ở root repo**). Không chuyển microservices.

## 1. Cấu trúc thật của repo (đối chiếu code, 2026-10-05)

| Thành phần | Vị trí thật |
|---|---|
| Backend | `backend/src/main/kotlin/com/systemwebstudio/<module>/` |
| Backend test | `backend/src/test/kotlin/com/systemwebstudio/<module>/` (+ `support/` dùng chung) |
| Migration | `backend/src/main/resources/db/migration/` — hiện có V1…V25 |
| Frontend | **Root repo**: `app/`, `features/{admin,auth,studio}`, `components/`, `lib/`, `proxy.ts` (không có thư mục `frontend/`) |
| E2E | `e2e/*.mjs` (Playwright-core scripts) |
| Khác | `packages/`, `workers/`, `infra/`, `scripts/`, `docs/adr/` |

Module backend hiện có: `access admin ai asset audit code common component identity integration maintenance member project prompt publish runtime schema settings template version`.
**Chưa tồn tại** (là NEW): `tenancy organization sharing data logic app/definition`.

Quy ước đường dẫn trong file này: `X/**` nghĩa là `backend/src/{main,test}/kotlin/com/systemwebstudio/X/**` nếu là backend, trừ khi ghi rõ `frontend`.

## 2. Ownership theo agent

### C0 — Architect / Integrator
Không sở hữu feature lớn. Review contract, merge/cherry-pick, integration test, quản lý `docs/parallel/**` và `docs/contracts/**` (C1–C5 chỉ đề xuất đổi qua `DECISIONS.md`).
Sở hữu **shared/integration files** (chỉ C0 sửa):
`backend/build.gradle.kts`, `backend/gradle.lockfile`, `backend/src/main/resources/application*.yml`, `common/**`, `identity/SecurityConfiguration.kt`, `backend/src/test/**/support/**`, `package.json`, `package-lock.json`, `tsconfig.json`, `next.config.ts`, `compose*.yml`, `.env.example`, `CLAUDE.md`, `docs/parallel/**`, `docs/contracts/**`, `docs/adr/**`.
Nếu C1–C5 cần thêm config key/dependency → ghi `BLOCKERS.md` (loại *needs-shared-file*), C0 xử lý.

### C1 — Tenant / Identity / Permission / Sharing
`identity/**`, `access/**`, `member/**`*, `tenancy/**` NEW, `organization/**` NEW, `sharing/**` NEW.
Migration **V26–V34**.

### C2 — App Definition / Schema / Version / Component
`project/**`, `schema/**`, `version/**`, `component/**`, `template/**`, `app/definition/**` NEW (`com.systemwebstudio.app.definition`), `prompt/**`*, `ai/**`*, `asset/**`*.
Migration **V35–V39**.

### C3 — Data Platform
`data/datasource/**`, `data/discovery/**`, `data/query/**`, `data/mapping/**`, `data/gateway/**` (tất cả NEW, `com.systemwebstudio.data.*`) và code connector liên quan dữ liệu.
Lưu ý: connector hiện tại (`ConnectorProxyController`, `AdminConnectorController`, `PublicAddress` SSRF guard) nằm trong `runtime/Gateway.kt` — xem §5 (không được sửa tại chỗ).
Migration **V40–V49**.

### C4 — Action / Workflow
`logic/action/**`, `logic/workflow/**`, `logic/scheduler/**` (NEW, `com.systemwebstudio.logic.*`), tích hợp RabbitMQ cho workflow (class mới; không sửa `integration/queue/**` và `publish/**`).
Migration **V50–V59**.

### C5 — Web Builder / Admin UI / E2E
Frontend (root repo): `features/**`, `components/**`, `app/**`, `lib/schema-preview*`, `lib/preview-document.ts`, `lib/app-definition/**` NEW, `e2e/**`.
**Không tạo migration.**

(*) = gán mặc định của C0 cho module không được nêu trong yêu cầu gốc; xem `DECISIONS.md` D-002. Chờ chủ dự án xác nhận.

## 3. Module legacy chưa có owner trong yêu cầu gốc
Mặc định: **C0-gated** — mọi thay đổi phải có task trong BOARD và C0 duyệt.

| Module | Ghi chú | Gán mặc định |
|---|---|---|
| `member/**` | thành viên workspace/project | C1 |
| `prompt/**`, `ai/**`, `integration/llm/**` | AI Gateway | C2 (ranh giới: không đổi AI Gateway governance, chỉ nối Structured Operation → AppDefinition) |
| `asset/**` | tài sản dự án | C2 |
| `publish/**`, `runtime/**` (trừ connector), `code/**`, `integration/{deploy,git,storage,queue,secrets}/**` | publish pipeline, render/build/runtime planes, `STATIC_APP` | **C0-gated** |
| `audit/**` | append-only | **C0-gated** (chỉ gọi `AuditService.record`, không đổi bảng/trigger) |
| `admin/**`, `settings/**`, `maintenance/**` | admin console backend | **C0-gated** |

## 4. HOT FILES — chỉ một owner được sửa

| File / khái niệm | Owner | Ghi chú |
|---|---|---|
| `access/AccessService.kt`, `access/Permission.kt` (`PermissionMatrix`, `AccessContext`) | **C1** | |
| `app/definition/AppDefinition*`, `schema/SchemaOperation.kt`, `schema/PageSchemaValidator.kt`, `schema/SchemaPatchEngine.kt`, `version/SchemaCommitService.kt` | **C2** | |
| `DataGateway`, `DataConnector` (và interface trong `data/gateway`, `data/datasource`) | **C3** | |
| `ActionRuntime`, `WorkflowRuntime` (`logic/**`) | **C4** | |
| `features/studio/ProjectWorkspace.tsx`, `lib/schema-preview.ts` | **C5** | |
| `lib/http-api.ts`, `lib/http-types.ts` (≈33 KB / 26 KB, mọi domain dùng chung) | **C5** | Agent khác **không sửa**; thêm API client mới trong file mới `lib/api/<domain>.ts` hoặc ghi dependency cho C5 |
| `features/studio/StudioApp.tsx` | **C5** | |
| `runtime/Gateway.kt` (chứa AppGatewayController + ConnectorProxy + AdminConnector + `PublicAddress`) | **C0-gated** | Không sửa tại chỗ; xem §5 |
| `schema/PageSchemaValidator.kt` | **C2** | Là một phần của bất biến phải bảo toàn |

Agent khác cần đổi hot file → **không tự sửa**; ghi dependency (cột *Depends* trong `BOARD.md` hoặc `BLOCKERS.md`) và chờ owner.

## 5. Điều cần bảo toàn (không được phá)
Page Schema + `SchemaPatchEngine` + `PageSchemaValidator`; component registry/version; immutable project versions; AI Gateway (`ai/**`, `integration/llm/**`); Connector Proxy + SSRF guard (`PublicAddress`) + credential chỉ ở server (`SecretsCrypto`); Audit append-only (trigger DB); publish pipeline; render/build/runtime planes; `PAGE_SCHEMA` và `STATIC_APP`.
Code mới tái dùng thông qua interface; **không** copy-paste/fork logic SSRF. C3 muốn dùng SSRF guard: dùng `PublicAddress` hiện có (read-only) hoặc đề xuất C0 trích xuất sang `common/` (D-003).

## 6. Migration ownership (Flyway — hiện tới V25)

| Owner | Range | Dùng cho |
|---|---|---|
| C1 | **V26–V34** | Tenant / Permission / Sharing |
| C2 | **V35–V39** | App Definition |
| C3 | **V40–V49** | Data Platform |
| C4 | **V50–V59** | Action / Workflow |
| C5 | — | Không tạo migration |
| C0 | V60+ | Dự phòng / hợp nhất / sửa chữa |

Quy tắc: không dùng số ngoài range; **không sửa migration đã tồn tại (V1–V25)** và không sửa migration đã merge; mỗi file `V<n>__<mô_tả>.sql`; không bắt buộc dùng hết range, số không liên tục là bình thường (xem rủi ro out-of-order trong `BASELINE.md`/`BLOCKERS.md`). Đổi tên bảng `projects` **bị cấm** trong giai đoạn này.

## 7. Test ownership
Test nằm cùng module với code. Test tích hợp dùng chung `support/IntegrationTestBase.kt`, `TestFixtures.kt`, `ApiSession.kt` (C0-owned; cần mở rộng → BLOCKERS). E2E (`e2e/*.mjs`) do C5 sở hữu; agent khác đề xuất kịch bản trong BOARD.
