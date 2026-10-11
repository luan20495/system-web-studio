# E2E_MATRIX — C6

> **Master index:** [QA_MASTER.md](QA_MASTER.md) is the single source of truth (test inventory with IDs, release gates, per-SHA history, defect lifecycle, traceability). This file keeps its own detail and is not replaced.

Cập nhật 2026-10-06 (batch 3). **Mac execution PENDING (C0)** — `MAC_QA_HANDOFF.md`. Trạng thái: `NOT RUN` (C6 không có stack/Chromium: BLK-C6-03) · `NO TEST` (chưa có test nào cho luồng này) · `COVERED-BY-INTEGRATION` (có test API/integration backend, chưa chạy ở C6) · `PASS` (C6 đã chạy).
**Quan trọng:** theo `c5/PHASE3_E2E_PLAN.md`, chưa có E2E nào chạy trên cả 3 portal + backend V2 thật (`tests/e2e-real/` chưa tạo). Các script `e2e/*.mjs` là E2E của **app gốc legacy (single-origin :3100)**, không chứng minh V2.

## 1. Script E2E hiện có (legacy, cần full stack `scripts/run-local.sh`)
| Script | Luồng | Cần | C6 run | Ghi chú |
|---|---|---|---|---|
| `e2e/factory-flow.mjs` | AI Software Factory: login, tạo/sửa project, version, AI | stack + Chromium | NOT RUN | cú pháp OK (`evidence/e2e-syntax.log`) |
| `e2e/admin-setup-flow.mjs` | Admin cấu hình provider/limit → nhân viên dùng quota | stack + stub AI :18099 | NOT RUN | |
| `e2e/code-flow.mjs` | Code project: sandbox build, diff, merge, publish | stack + Forgejo + Docker runner | NOT RUN | |
| `e2e/runtime-flow.mjs` | Server runtime: build sandbox, container, blue/green | full stack + Docker | NOT RUN | bật/tắt policy `server-apps.enabled` trong lúc chạy |
| `e2e/sso-flow.mjs` | OIDC/SSO với Keycloak | `scripts/sso-up.sh` | NOT RUN | |
| `e2e/public-flow.mjs` | Deployment public qua Cloudflare | `scripts/public-up.sh` | NOT RUN | cần hạ tầng ngoài |
| `e2e/a11y.mjs` | axe-core trên login/Admin/Studio/editor | stack + Chromium | NOT RUN | |
| `e2e/pages-mock.mjs` | Static export GitHub Pages chạy không backend | Chromium (+ build export) | NOT RUN | nên chạy được trên Mac không cần stack |

## 2. Luồng critical C1–C5 (V2) — ma trận phủ
| ID | Luồng | Owner chính | Phủ hiện có | Trạng thái | Khoảng trống |
|---|---|---|---|---|---|
| F-01 | Login local + session cookie HttpOnly, logout, redirect `next` | C0/C1/C5 | backend: `AuthSecurityTests`, `RedisSessionTests`; browser: `portals.spec.mjs` (chỉ phần không session) | COVERED-BY-INTEGRATION (backend) · browser NOT RUN | không E2E 3 origin thật (E-01) |
| F-02 | Portal gate (viewer/system-admin platform-only/tenant admin) | C1/C5 | `tenancy/MeTenancyTests`, `AccessAdaptersTests`; FE `portals.test.ts` (13) | FE PASS · BE NOT RUN | Q-1 (TENANT_ADMIN vào Admin) chưa quyết (B-C5-09) → GAP-C6-02 |
| F-03 | Cô lập tenant: user tenant B không thấy/ghi dữ liệu tenant A | C1 | `isolation/IsolationApiTests`, `TenantAccessTests`, `PrivilegeEscalationTests`, `TenantMigrationFromV25Tests` | COVERED-BY-INTEGRATION (NOT RUN) | không có check UI (E-12) |
| F-04 | Tạo project → mở Builder → sửa section → save/version → restore | C2/C5 | BE: `SchemaApiTests`, `VersionApiTests`, `AppDefinitionCommitTests`; FE: `pages/dnd/components/inspector.test` | FE PASS · BE NOT RUN | không E2E trình duyệt + backend thật (E-05) |
| F-05 | AppDefinition V2 validate/conformance FE↔BE | C2/C5 | BE `ContractConformanceTests`; FE `conformance.test.ts` | FE PASS (130/130) · BE NOT RUN | — (cặp fixture dùng chung đã chạy phía FE) |
| F-06 | Data query: DataSource → Query → Mapping → ViewModel → render | C3/C5 | BE: `GatewayTests`, `DataRuntimeLiveApiTests` [18], `PostgresConnectorIntegrationTests`; FE `data.test.ts` | FE PASS · BE NOT RUN | **E-06 BLOCKED** (Q-2/B-C3-11; không có API quản trị data source, B-C0-W-03) → GAP-C6-01 |
| F-07 | Data mutation + idempotency (409 `IDEMPOTENCY_OUTCOME_UNKNOWN`, 422 `MUTATION_REJECTED`) | C3/C4 | `IdempotencyTests`, `ActionDataPortAdapterTests`, `DataWriteErrorsTests` | COVERED-BY-INTEGRATION (NOT RUN) | connector production read-only (B-C0-W-04); E-07 BLOCKED |
| F-08 | Action execute (TEST = WouldRun, LIVE; quyền `ACTION_EXECUTE`) | C4/C5 | `ActionRuntimeTests` [64], `AppRuntimeApiTests` [10], FE `actions.test.ts` | FE PASS · BE NOT RUN | E-08 BLOCKED (B-C4-09); LIVE mutating trả 503 `RUNTIME_STORES_VOLATILE` khi chưa có V29 |
| F-09 | Workflow: start/status/cancel, approval, retry/DLQ, scheduler | C4 | `WorkflowEngineTests` [65], `ApprovalServiceTests`, `SchedulerServiceTests`, `WorkflowSweeperTests`; FE `workflow.test.ts` | FE PASS · BE NOT RUN | persistence V29 chưa có (B-C0-W-01); queue RabbitMQ adapter chưa; E-09 BLOCKED |
| F-10 | Publish policy → publish → site served → rollback | C2/C0 | `PublishApiTests`, `StaticSiteTests`, `PublishConfigTests`, `PublishConfigsMigrationTests` | COVERED-BY-INTEGRATION (NOT RUN) | flag `PUBLISH_CONFIGS_ENABLED` OFF; E-10 cần bật flag |
| F-11 | Webhook ingest ẩn danh (HMAC, replay) | C3/C0 | `WebhookTests`, `WebhookReplayTests`, `WebhookSecurityTests`; static SQ-08 | static PASS · BE NOT RUN | handler W-07 chưa có (route trả 404) — kỳ vọng hiện tại |
| F-12 | Flag OFF mặc định (không route V2 lộ ra) | C0 | `AppRuntimeFlagsOffTests`; static SQ-05/06 | static PASS · BE NOT RUN | — |
| F-13 | CORS / cookie / CSP 3 origin | C0/C5 | `WebOriginsTests`, `AuthSecurityTests`; `portals.spec.mjs` (header) | NOT RUN | OIDC 1 redirect URI cho 3 origin (B-C0-WEB-01, B-C5-08) → GAP-C6-03 |
| F-14 | Error/empty states khi backend lỗi | C5 | FE `errors.test.ts` (16) | FE PASS | không E2E backend dừng giữa chừng (E-11) |
| F-15 | A11y shell + Builder | C5 | `e2e/a11y.mjs` (legacy), harness spec 64 | NOT RUN | — |

## 3. Khoảng trống (GAP) và kế hoạch C6
| ID | Khoảng trống | Vì sao | Việc C6 làm trong phạm vi |
|---|---|---|---|
| GAP-C6-01 | Chưa có E2E real-backend nào cho chuỗi C1→C5 (E-01…E-14 trong `c5/PHASE3_E2E_PLAN.md`); E-06…E-09 bị chặn bởi quyết định Q-2 và wiring chưa có | Thiếu route/API quản trị; không phải do QA | Không viết test giả. C6 sẽ viết specs `R` (E-01…E-05, E-10…E-14) **chỉ sau khi** có stack chạy được để chứng minh; đang chặn bởi BLK-C6-03. Harness hiện thêm: `harness/static-qa.mjs`, `harness/mac-gate.sh` |
| GAP-C6-02 | Admin cho TENANT_ADMIN (Q-1) chưa quyết nên không viết được kỳ vọng E-03 cho vai trò này | Quyết định C0 | Chờ C0 |
| GAP-C6-03 | OIDC/CORS đa origin chưa kiểm chứng | Cấu hình hạ tầng | Chờ C0; kiểm bằng E-13 |
| GAP-C6-04 | Không kiểm tự động "không sửa file owner khác"/hot files | Cần git | Chạy `git diff --name-only <baseline>..HEAD` đối chiếu `OWNERSHIP.md` trên Mac |

## 4. Chạy khi có Mac + Docker
Một lệnh: `bash docs/parallel/c6/harness/mac-full.sh` (xem `MAC_QA_HANDOFF.md`). Nó chạy backend → frontend → static → stack thật → smoke → 8 script E2E → browser specs → recovery, ghi từng phase `PASS/FAIL/NOT_RUN` vào `evidence/mac/RESULTS.txt`. Sau đó C6 đổi các `NOT RUN` ở đây thành kết quả thật.

## 5. Batch 2 — trạng thái thực thi (2026-10-06)
| Hạng mục | Kết quả |
|---|---|
| Script E2E `e2e/*.mjs` thực thi | **0 / 8** (không có Docker/Chromium/Mac shell; chỉ `node --check` 8/8) |
| Browser specs (`builder.spec.mjs`, `portals.spec.mjs`) | **NOT RUN** (không Chrome trong VM) |
| Recovery R01…R08 (`harness/recovery.sh`) | **NOT RUN** — script đã viết, chưa từng chạy trên Mac |
| R09 workflow interrupted · R10 mutation timeout mơ hồ · R11 queue replay/DLQ | **Không tự động hoá được qua API công khai hiện nay** (V29/run store còn volatile, connector production read-only, `WorkflowQueue` chưa wire). Ngữ nghĩa "không retry mutation mơ hồ" chỉ có test mức integration: `DataRuntimeLiveApiTests :: an ambiguous failure keeps the key reserved as UNKNOWN and a retry never writes again`; chưa có bằng chứng chạy |
| Chuỗi login→tenant/ws/project→Studio→data source→query→mutation→action→workflow→publish→runtime | **GAP** (không có test thực; E-06…E-09 blocked bởi Q-2/B-C3-11/B-C4-09/B-C0-W-03) |

### Mapping script → key (`mac-full.sh`, thực thi thật, không chỉ `node --check`)
`E2E_01` pages-mock · `E2E_02` factory-flow · `E2E_03` admin-setup-flow · `E2E_04` code-flow · `E2E_05` runtime-flow · `E2E_06` a11y · `E2E_07` sso-flow (cần `RUN_SSO=1`) · `E2E_08` public-flow (cần `RUN_PUBLIC=1`). Browser: `BROWSER_BUILDER` (component harness, **không** phải backend E2E), `BROWSER_PORTALS`, tổng `BROWSER`. Chuỗi login→…→runtime: `GAP_E2E_CHAIN`.

### Recovery cases do `recovery.sh` kiểm khi có stack (key `RECOVERY_*` trong RESULTS)
`R01` 5 ghi đồng thời cùng `expectedRevision` → đúng 1×200, còn lại 409 · `R02` replay revision cũ → 409 · `R03` publish trùng `Idempotency-Key` → cùng deployment · `R04` 5 publish song song cùng key → 1 deployment · `R05/R06/R07` dừng Postgres/Redis/RabbitMQ → readiness ≠ 200 (`_NOTREADY`), liveness (INFO), bật lại → readiness 200 (`_RECOVERED`), login + đọc project (`_FUNCTIONAL`) · `R08` `stop-local.sh` + `run-local.sh` → project còn, login lại được · `R09` workflow bị ngắt · `R10` mutation timeout mơ hồ · `R11` queue replay/DLQ = **`GAP`** (không phải PASS): cần V29 run store / connector ghi production / `WorkflowQueue` — ngoài phạm vi, route C0/C3/C4.
Tổng `RECOVERY` = PASS chỉ khi R01–R04, R05–R07 `_RECOVERED`, R08 đều PASS. Chạy: `mac-full.sh` (gọi `recovery.sh` sau khi stack lên) hoặc riêng `bash docs/parallel/c6/harness/recovery.sh`.
Lưu ý nội dung: ngữ nghĩa "không retry mutation mơ hồ" hiện chỉ có test integration (`DataRuntimeLiveApiTests`), không có kiểm thử live qua API (R10 = GAP).


## Mac run 2026-10-06 (batch 4) — HEAD f894cc6, nguồn `evidence/mac/RESULTS.txt` (run 1)
| ID | Script / spec | Kết quả | Ghi chú |
|---|---|---|---|
| E2E_01 | `e2e/pages-mock.mjs` | **PASS 6/6** | mock/static, **không** chạm backend thật — không tính là real E2E |
| E2E_02 | `factory-flow.mjs` | NOT_RUN | `stack_not_ready` (STACK_UP FAIL: cổng bị stack `hbl` giữ) |
| E2E_03 | `admin-setup-flow.mjs` | NOT_RUN | như trên |
| E2E_04 | `code-flow.mjs` | NOT_RUN | như trên |
| E2E_05 | `runtime-flow.mjs` | NOT_RUN | như trên |
| E2E_06 | `a11y.mjs` | NOT_RUN | như trên |
| E2E_07 | `sso-flow.mjs` | NOT_RUN | cần `RUN_SSO=1` + stack |
| E2E_08 | `public-flow.mjs` | NOT_RUN | cần `RUN_PUBLIC=1` + hạ tầng public |
| BROWSER_BUILDER | `tests/browser/builder.spec.mjs` | **PASS 64/64** | Chrome thật, component harness, **không** backend |
| BROWSER_PORTALS | `tests/browser/portals.spec.mjs` | **PASS 33/33** | Chrome thật, 3 portal đã build, **không** backend |
| SMOKE | `scripts/smoke-test.sh` | NOT_RUN | cần stack |
| GAP_E2E_CHAIN | login→…→runtime | **GAP** | không có test (không đổi) |
Real backend E2E: **0/5 đã chạy**. Chặn bởi môi trường (cổng), không phải lỗi sản phẩm; chi tiết `QA_STATUS.md` Batch 4.


## 8e91172 (batch 5)
Không chạy E2E mới: không có thay đổi frontend/publish; stack vẫn bị chặn. E2E_01…08, smoke, recovery giữ nguyên như batch 4: real backend E2E **0/5**. V29 chỉ có bằng chứng ở tầng integration test (Testcontainers), chưa qua stack thật (R09/R10/R11 vẫn GAP). Mở khoá khi: có stack cách ly hoặc user duyệt migrate DB dev; C3 GREEN; C2 V30/apiBase; C5 real E2E.


## Batch 7 (2026-10-07)
Không chạy E2E mới (stack vẫn bị chặn bởi cổng/DB dev). Phân loại lại: các hàng E2E chuỗi CH-03/04/08/09 bị chặn bởi **implementation** (Management API, apiBase/publish runtime, V30 chưa tích hợp), CH-07 bởi RabbitMQ chưa nối, các hàng còn lại bởi **environment**. Xem QA_MASTER §3 (cột "Blocked by") và §7.
