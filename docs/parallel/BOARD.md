# BOARD — Parallel development

Chỉ owner cập nhật dòng task của mình. C0 cập nhật cột Commit khi cherry-pick/merge.
Status: `NOT_STARTED` · `READY` (brief sẵn sàng, chưa bắt đầu) · `IN_PROGRESS` · `BLOCKED` · `REVIEW` · `DONE`.

| Task | Owner | Status | Branch | Depends | Commit |
|---|---|---|---|---|---|
| T1 — Isolation/API audit | C1 | READY | agent/c1-tenancy | — | — |
| T2 — Tenant foundation (V26 fix round) | C1 | REVIEW | fix/c1-v2 | T1; V26 | Gradle NOT RUN (Maven blocked in C1 env) · V26 verified with psql on PostgreSQL 16 · see `docs/parallel/c1/T2-tenant-foundation.md` |
| T6 — App Definition V2 | C2 | READY | agent/c2-app-model | contract `app-definition-v2.md` | — |
| T8 — DataConnector foundation | C3 | READY | agent/c3-data | contract `data-connector.md`; T2 (TenantContext) cho scoping | — |
| T13 — ActionRuntime foundation | C4 | NOT_STARTED | agent/c4-workflow | contract `action-workflow.md`; T2, T8 (interface) | — |
| T12 — Builder data binding | C5 | NOT_STARTED | agent/c5-web | T6 (AppDefinition ViewModel), T8 (Query API), PREP-T12 | — |
| PREP-T13 — ActionRuntime architecture + scaffold | C4 | READY | agent/c4-workflow | contract `action-workflow.md` | — |
| PREP-T12 — Builder/data binding architecture audit | C5 | READY | agent/c5-web | contracts `app-definition-v2.md`, `data-connector.md`, `action-workflow.md` | — |

Ghi chú: T1/T2/T6/T8/T13/T12 là nhãn task theo kế hoạch tổng. Brief từng agent: `docs/parallel/agents/` (`C1_T1`, `C2_T6`, `C3_T8`, `C4_PREP_T13`, `C5_PREP_T12`). T13 và T12 đầy đủ chưa bắt đầu; C4 và C5 làm bước PREP trước.
Mỗi task: test + commit riêng; report cuối task theo `CLAUDE.md`.

## Migration requests (Flyway — chỉ C0 cấp số)

Agent ghi request ở đây; **C0 điền cột Version** theo thứ tự V26, V27, V28, … Một version chỉ thuộc một task. Agent không tự chọn số và chỉ tạo file migration sau khi có số. Integration branch merge migration theo thứ tự tăng dần. Không bật `outOfOrder=true`. Hiện tại DB tới V25. **V26 và V27 đã cấp** (bảng dưới); **không cấp thêm số nào** cho tới khi C0 duyệt từng request theo thứ tự trong [`MIGRATION_LEDGER.md`](MIGRATION_LEDGER.md) — số kế tiếp sẽ là V28 nhưng CHƯA được cấp, không ai được tự chọn.

| Version | Task | Requester | Mô tả thay đổi schema | Status |
|---|---|---|---|---|
| V26 | T2 | C1 | `V26__tenant_foundation.sql` — tenants, tenant_members, tenant_id + backfill + FK, guarded fill trigger. Bản đã sửa ở `fix/c1-v2`. | ASSIGNED (2026-10-06) — IMPORTED vào `integration/v2` (path-scoped, từ `fix/c1-v2@32c5922`; Mac Gradle của C1 xanh); verify PG16 trên Mac: xem `BASELINE.md`; chỉ áp dụng sau Gradle xanh |
| (chờ C0) | T2 follow-up `tenant_compat_removal` | C1 | Drop `DEFAULT` on `workspaces.tenant_id` and triggers `workspace_members_tenant_fill`, `projects_tenant_fill`, `project_members_tenant_fill` + `tenancy_fill_tenant_from_workspace()`. **Needs V26 integrated.** Condition: `TenantInsertPathsGrepTest` PENDING list is empty (every INSERT into workspaces/workspace_members/projects/project_members passes `tenant_id`; C2 entities, `AdminController`, 2 test files and C1's identity/member inserts still pending). Runbook §4. | REQUESTED — no number requested yet |
| V27 | T7 | C2 | `V27__publish_configs.sql` — bảng `publish_configs` (policy bền vững của publish). | ASSIGNED, **có điều kiện**: C2 chỉ tạo file sau khi V26 đã tích hợp **và** verify trên Mac (checklist bước 8); cờ `app.publish-configs.enabled` vẫn OFF |
| (không cấp) | các request khác | C1 / C2 / C3 / C4 | Thứ tự phụ thuộc: tenant resources → data foundation (C3, MỘT request gộp) → sharing/departments/groups (C1) → action/workflow persistence (C4) → RLS → audit hardening → compat removal. Chi tiết, điều kiện và blocker: `MIGRATION_LEDGER.md` §2. | UNNUMBERED — không ai tự chọn số |
