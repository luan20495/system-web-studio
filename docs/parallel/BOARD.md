# BOARD — Parallel development

Chỉ owner cập nhật dòng task của mình. C0 cập nhật cột Commit khi cherry-pick/merge.
Status: `NOT_STARTED` · `IN_PROGRESS` · `BLOCKED` · `REVIEW` · `DONE`.

| Task | Owner | Status | Branch | Depends | Commit |
|---|---|---|---|---|---|
| T1 — Isolation/API audit | C1 | NOT_STARTED | agent/c1-tenancy | — | — |
| T2 — Tenant foundation | C1 | NOT_STARTED | agent/c1-tenancy | T1 | — |
| T6 — App Definition V2 | C2 | NOT_STARTED | agent/c2-app-model | contract `app-definition-v2.md` | — |
| T8 — DataConnector foundation | C3 | NOT_STARTED | agent/c3-data | contract `data-connector.md`; T2 (TenantContext) cho scoping | — |
| T13 — ActionRuntime foundation | C4 | NOT_STARTED | agent/c4-workflow | contract `action-workflow.md`; T2, T8 (interface) | — |
| T12 — Builder data binding | C5 | NOT_STARTED | agent/c5-web | T6 (AppDefinition ViewModel), T8 (Query API) | — |

Ghi chú: T1/T2/T6/T8/T13/T12 là nhãn task theo kế hoạch tổng; Phase 0 **không** bắt đầu task nào.
Mỗi task: test + commit riêng; report cuối task theo `CLAUDE.md`.

## Migration requests (Flyway — chỉ C0 cấp số)

Agent ghi request ở đây; **C0 điền cột Version** theo thứ tự V26, V27, V28, … Một version chỉ thuộc một task. Agent không tự chọn số và chỉ tạo file migration sau khi có số. Integration branch merge migration theo thứ tự tăng dần. Không bật `outOfOrder=true`. Hiện tại DB tới V25; next available = **V26**.

| Version | Task | Requester | Mô tả thay đổi schema | Status |
|---|---|---|---|---|
| V26 | — | — | (chưa cấp) | AVAILABLE |
