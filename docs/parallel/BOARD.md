# BOARD — Parallel development

Chỉ owner cập nhật dòng task của mình. C0 cập nhật cột Commit khi cherry-pick/merge.
Status: `NOT_STARTED` · `READY` (brief sẵn sàng, chưa bắt đầu) · `IN_PROGRESS` · `BLOCKED` · `REVIEW` · `DONE`.

| Task | Owner | Status | Branch | Depends | Commit |
|---|---|---|---|---|---|
| T1 — Isolation/API audit | C1 | READY | agent/c1-tenancy | — | — |
| T2 — Tenant foundation | C1 | NOT_STARTED | agent/c1-tenancy | T1 | — |
| T6 — App Definition V2 | C2 | READY | agent/c2-app-model | contract `app-definition-v2.md` | — |
| T8 — DataConnector foundation | C3 | READY | agent/c3-data | contract `data-connector.md`; T2 (TenantContext) cho scoping | — |
| T13 — ActionRuntime | C4 | REVIEW | agent/c4-workflow | contract `action-workflow.md`; port tới T2/T6/T8 (B-C4-01/02/04) | `67e472e` (chưa merge) |
| T14 — Workflow engine (queue worker, retry/DLQ, compensation, TEST) | C4 | REVIEW | agent/c4-workflow | T13; RabbitMQ/JDBC adapter (B-C4-05/06/07) | `3340897` (chưa merge) |
| Approval | C4 | REVIEW | agent/c4-workflow | C1 principal resolver (B-C4-01) | `17c2fe4` (chưa merge) |
| Scheduler | C4 | REVIEW | agent/c4-workflow | T14 | `20b5175` (chưa merge) |
| Notification ports | C4 | REVIEW | agent/c4-workflow | — | `b633f97` (chưa merge) |
| T12 — Builder data binding | C5 | NOT_STARTED | agent/c5-web | T6 (AppDefinition ViewModel), T8 (Query API), PREP-T12 | — |
| PREP-T13 — ActionRuntime architecture + scaffold | C4 | DONE | agent/c4-workflow | contract `action-workflow.md` | `d3b661f` (code, chưa merge; C0 cập nhật khi cherry-pick) |
| PREP-T12 — Builder/data binding architecture audit | C5 | READY | agent/c5-web | contracts `app-definition-v2.md`, `data-connector.md`, `action-workflow.md` | — |

Ghi chú: T1/T2/T6/T8/T13/T14/T12 là nhãn task theo kế hoạch tổng. Brief từng agent: `docs/parallel/agents/` (`C1_T1`, `C2_T6`, `C3_T8`, `C4_PREP_T13`, `C5_PREP_T12`). T13 và T12 đầy đủ chưa bắt đầu; C4 và C5 làm bước PREP trước.
Mỗi task: test + commit riêng; report cuối task theo `CLAUDE.md`.

## Migration requests (Flyway — chỉ C0 cấp số)

Agent ghi request ở đây; **C0 điền cột Version** theo thứ tự V26, V27, V28, … Một version chỉ thuộc một task. Agent không tự chọn số và chỉ tạo file migration sau khi có số. Integration branch merge migration theo thứ tự tăng dần. Không bật `outOfOrder=true`. Hiện tại DB tới V25; next available = **V26**.

| Version | Task | Requester | Mô tả thay đổi schema | Status |
|---|---|---|---|---|
| V26 | — | — | (chưa cấp) | AVAILABLE |
| (chưa cấp) | T13/T14 | C4 | **Request, chưa tạo file.** Sáu bảng: `action_runs`, `workflow_runs`, `approvals`, `schedules`, `notification_deliveries`, `in_app_notifications` (C4 không tạo `action_definitions` — D-C4-03). Cột/chỉ mục/unique đề xuất: `docs/parallel/audit/FINAL-C4-runtime-design.md` §10. C0 có thể cấp một version cho cả bộ hoặc tách theo bảng. Mọi bảng có `tenant_id`. | REQUESTED |
