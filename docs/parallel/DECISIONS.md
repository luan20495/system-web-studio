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
