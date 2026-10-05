# BOARD — Parallel development

Chỉ owner cập nhật dòng task của mình. C0 cập nhật cột Commit khi cherry-pick/merge.
Status: `NOT_STARTED` · `READY` (brief sẵn sàng, chưa bắt đầu) · `IN_PROGRESS` · `BLOCKED` · `REVIEW` · `DONE`.

| Task | Owner | Status | Branch | Depends | Commit |
|---|---|---|---|---|---|
| T1 — Isolation/API audit | C1 | READY | agent/c1-tenancy | — | — |
| T2 — Tenant foundation | C1 | NOT_STARTED | agent/c1-tenancy | T1 | — |
| T6 — App Definition V2 | C2 | READY | agent/c2-app-model | contract `app-definition-v2.md` | — |
| T8 — DataConnector foundation | C3 | READY | agent/c3-data | contract `data-connector.md`; T2 (TenantContext) cho scoping | — |
| T13 — ActionRuntime | C4 | REVIEW | agent/c4-workflow | contract `action-workflow.md`; port tới T2/T6/T8 (B-C4-01/02/04) | `67e472e`, `9089447` V2 alignment, `3e587e3` rate limit (chưa merge) |
| T14 — Workflow engine (queue worker, retry/DLQ, compensation, TEST) | C4 | REVIEW | agent/c4-workflow | T13; RabbitMQ/JDBC adapter (B-C4-05/06/07) | `3340897`, `3227437` sweeper/DLQ, `e91cf20` retention, `8b222d0` shape/timeout tests (chưa merge) |
| Approval | C4 | REVIEW | agent/c4-workflow | C1 principal resolver (B-C4-01) | `17c2fe4` (chưa merge) |
| Scheduler | C4 | REVIEW | agent/c4-workflow | T14 | `20b5175`, `da3b1ba` dedupe ledger (chưa merge) |
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
| (chưa cấp) | T13/T14 | C4 | **Request gộp (thay yêu cầu cũ), chưa tạo file, KHÔNG cấp số Flyway.** Mười một bảng: `action_runs` (cột `idempotency_key` = key dẫn xuất 43 ký tự), `workflow_runs` (+ `process_failures`, `sweep_failures`, `not_before`, `last_swept_at`, `redacted_at`), `workflow_run_steps` (mới, trạng thái từng step, `ON DELETE CASCADE`), `approvals`, `schedules` (+ `pending_attempts`, `pending_not_before`, `declared_key` unique theo `(tenant_id, app_id)`), `schedule_executions` (mới, sổ chống bắn trùng, PK `(tenant_id, schedule_id, fire_at)`, unique `(tenant_id, dedupe_key)`), `notification_deliveries`, `in_app_notifications`. Mọi bảng `tenant_id uuid NOT NULL REFERENCES tenants(id)`; chỉ mục retention `(finished_at)` / `(updated_at)` một phần trên `action_runs`, `workflow_runs`, `approvals`, `schedule_executions`, chỉ mục claim sweeper `(last_swept_at NULLS FIRST, updated_at)`. DDL đầy đủ + chỉ mục: `docs/parallel/audit/FINAL-C4-runtime-design.md` §10 "Yêu cầu migration gộp". C0 có thể cấp một version cho cả bộ hoặc tách theo bảng. | REQUESTED |
