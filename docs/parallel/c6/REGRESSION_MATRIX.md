# REGRESSION_MATRIX — C6

> **Master index:** [QA_MASTER.md](QA_MASTER.md) is the single source of truth (test inventory with IDs, release gates, per-SHA history, defect lifecycle, traceability). This file keeps its own detail and is not replaced.

Cập nhật 2026-10-06 (batch 3). Cột **Run** = C6 đã chạy trong phiên này: `PASS` · `NOT RUN` (lý do ở `QA_STATUS.md` BLK-C6-0x). Số `@Test` là đếm tĩnh (annotation), không phải số chạy thật. **D** = có vẻ cần Docker/Testcontainers (heuristic), **U** = unit thuần.
Backend: tất cả `NOT RUN` ở C6 (BLK-C6-01) → **Mac execution PENDING (C0)**: `bash docs/parallel/c6/harness/mac-full.sh` (xem `MAC_QA_HANDOFF.md`). Cột Run chỉ đổi sang PASS/FAIL khi có dòng tương ứng trong `evidence/mac/RESULTS.txt`.

## 1. Gate toàn cục
| ID | Gate | Lệnh | Run | Kết quả |
|---|---|---|---|---|
| G-01 | Frontend typecheck root | `npx tsc --noEmit -p tsconfig.json` | yes | PASS |
| G-02 | Typecheck packages + apps | `npm run typecheck:packages` · `npm run typecheck:apps` | yes | PASS |
| G-03 | Unit/SSR + conformance | `XWEB_CONFORMANCE_DIR=… npm run test:unit` | yes | PASS 130/130 (mặc định 114 pass + 1 skip → BUG-C6-004) |
| G-04 | Build 3 app + root | `npm run build:apps` · `npm run build` | yes | PASS |
| G-05 | Static QA (migrations, flags, security wiring, skeleton) | `node docs/parallel/c6/harness/static-qa.mjs` | yes | PASS 10/10 |
| G-06 | Backend compile | `cd backend && ./gradlew compileKotlin compileTestKotlin` | no | NOT RUN (BLK-C6-01) |
| G-07 | Backend full regression | `cd backend && ./gradlew clean test` | no | NOT RUN (BLK-C6-01) |
| G-08 | Secret scan | `scripts/secret-scan.sh` | no | NOT RUN (BLK-C6-04) |
| G-09 | Smoke (stack) | `scripts/smoke-test.sh` | no | NOT RUN (BLK-C6-03) |

## 2. Frontend (C5)
| Khu vực | File test | Tests | Run | Kết quả |
|---|---|---|---|---|
| Contract mirror / conformance với C2 | `tests/builder/contract.test.ts`, `conformance.test.ts` | 6 + conformance (cần env) | yes | PASS |
| Data / Query binding | `tests/builder/data.test.ts` | 11 | yes | PASS |
| Action | `tests/builder/actions.test.ts` | 9 | yes | PASS |
| Workflow | `tests/builder/workflow.test.ts` | 11 | yes | PASS |
| Components / Inspector / Pages / DnD | `components`, `inspector`, `pages`, `dnd` | 20 / 10 / 9 / 9 | yes | PASS |
| Portals (platform/admin/studio) | `tests/builder/portals.test.ts` | 13 | yes | PASS |
| Error mapping | `tests/builder/errors.test.ts` | 16 | yes | PASS |
| Browser harness 64 + portals spec | `tests/browser/*.spec.mjs` | — | no | NOT RUN (cần Chromium, BLK-C6-03) |

## 3. Backend theo owner (kiểm kê tĩnh; chưa chạy)
| Owner | Khu vực / bất biến | Test chính (file [tests, D/U]) | Run |
|---|---|---|---|
| **C1** Tenant/Identity/Permission | Tenant foundation, cô lập tenant, leo thang đặc quyền, flag god-mode, adapters | `tenancy/`: TenantFoundationMigrationTests [12,D], TenantMigrationFromV25Tests [4,D], TenantAccessTests [8,D], PrivilegeEscalationTests [7,D], TenantAccessLegacyFlagTests [5,D], AccessAdaptersTests [15,D], AdminTransferOwnershipSelfGrantSpec [1,D], TenantInsertPathsGrepTest [1,U], PermissionCanonicalTests [7,U] · `isolation/IsolationApiTests` [10,D] · `identity/*` (AuthSecurityTests, ScimTests, Redis/session) | NOT RUN |
| **C2** AppDefinition/Schema/Version/Component | AppDefinition V2 validator + conformance (14 mã lỗi theo checklist), patch/commit bất biến, template, planner AI, publish-config | `app/`: AppDefinitionValidatorTests [25,U], ContractConformanceTests [19,U], AppDefinitionCompatibilityTests [13,U], AppDataBindingResolverTests [18,U], AppDefinitionCommitTests [3,D] · `schema/SchemaApiTests` [9,D] · `version/VersionApiTests` [3,D] · `component/*` · `template/*` · `ai/AppPlannerTests` [23,U], `TenantAiTests` [16,U] · `project/PublishConfigTests` [14,U] | NOT RUN |
| **C3** Data Platform | SSRF guard/`PublicAddress`, SQL guard, connector Postgres/REST, gateway, idempotency, cache, mapping, sync, webhook (HMAC/replay) | `data/**` (≈380): GatewayTests [34,U], SyncTests [23,U], WebhookTests [21,U], AddressRangeSpecTests [17,U], SqlGuardTests [14,U], PostgresConnectorIntegrationTests [12,D], PostgresSessionSecurityTests [13,D] · `runtime/PublicAddressTests` [9,U] | NOT RUN |
| **C4** Action/Workflow | ActionRuntime, WorkflowEngine (retry/DLQ/compensation), Approval, Scheduler (cron/dedupe), rate-limit, retention | `logic/**` (≈390, tất cả U): ActionRuntimeTests [64], WorkflowEngineTests [65], ApprovalServiceTests [22], SchedulerServiceTests [18], SchedulerDedupeTests [20], TenantRateLimitTests [17] | NOT RUN |
| **C0** wiring / persistence / security | Runtime API C3+C4, V28 persistence + workspace scope, webhook security, flags OFF, volatile-store guard | `wiring/**`: AppRuntimeApiTests [10,D], DataRuntimeLiveApiTests [18,D], AppRuntimeFlagsOffTests [2,D], VolatileStoreGuardsTests [7,U], JdbcPersistenceTests [17], JdbcIdempotencyStoreTests [15] · `migration/DataRuntimeMigrationTests` [10,D], `PublishConfigsMigrationTests` [3,D] · `identity/WebhookSecurityTests` [5,D] · `common/*` | NOT RUN |
| **Legacy (bất biến phải bảo toàn)** | Project/Publish/Static/Server runtime, audit append-only, AI Gateway | `project/ProjectApiTests` [9,D], `publish/{PublishApiTests,StaticSiteTests,QueueRecoveryTests}`, `runtime/ServerRuntimeTests` [5,D], `audit/*` [3,D], `ai/{AiGovernanceTests,OpenRouterPromptTests,…}` | NOT RUN |

## 4. Bất biến (CLAUDE.md) → cách kiểm
| Bất biến | Kiểm bằng | Trạng thái |
|---|---|---|
| Flyway liên tục, không V29 chưa cấp, không `outOfOrder` | static-qa SQ-02/03/04 | PASS (V1…V28) |
| Mọi flag V2 mặc định OFF | static-qa SQ-05/06; `AppRuntimeFlagsOffTests` | static PASS · backend NOT RUN |
| Webhook ingest ẩn danh chỉ đúng route | static-qa SQ-08; `WebhookSecurityTests` | static PASS · backend NOT RUN |
| Skeleton wiring không được compile | static-qa SQ-07 | PASS |
| SSRF guard / credential chỉ ở server | `PublicAddressTests`, `AddressRangeSpecTests`, `SecurityPrimitivesTests` | NOT RUN |
| Page Schema / PatchEngine / immutable versions | `SchemaApiTests`, `VersionApiTests`, `AppDefinitionCommitTests` | NOT RUN |
| Audit append-only (trigger DB) | `audit/*`, `TenantFoundationMigrationTests` | NOT RUN |
| Không sửa file owner khác / hot files | cần `git diff` | NOT RUN (BLK-C6-02) |

## 5. Security / isolation — kịch bản → suite/test cụ thể (batch 2)
**Tất cả cột "Run" = NOT RUN** (BLK-C6-01): đây là kiểm kê tĩnh bằng cách đọc tên test, **không phải** bằng chứng PASS. Khi chạy trên Mac, `mac-full.sh` ghi số tests/fail/skipped từng class vào `evidence/mac/security-suites.txt`; chỉ khi đó cột này mới được đổi thành PASS/FAIL. "Phủ" = test có tên/nội dung nhắm đúng kịch bản.

| Kịch bản | Phủ | Test (class :: tên) | Run |
|---|---|---|---|
| Forged **tenantId** | đủ | `AppRuntimeApiTests :: a body field the contract does not list is refused, tenantId first` · `AccessAdaptersTests :: gateway - cross tenant - the claimed tenant must be the tenant of the workspace` · `DataSourceScopeTests :: another tenant is not found, with or without the right workspace id` | NOT RUN |
| Forged **workspaceId / projectId** | đủ | `DataRuntimeLiveApiTests :: a forged workspace and project combination is denied, over HTTP and at the gateway` · `IsolationApiTests :: an outsider learns nothing and changes nothing through any combination of foreign workspace and project ids` · `AccessAdaptersTests :: gateway - a user of another workspace cannot use a foreign project id` / `an app version must belong to the project` | NOT RUN |
| **Cross-tenant ID** (version/asset/domain/deployment, source, binding) | đủ | `IsolationApiTests :: ids of another project's versions, assets, domains and deployments are not usable through my own project` · `DataRuntimeLiveApiTests :: a different tenant is refused by sourceRef and by binding` · `TenantAccessTests :: forPlatform needs an enabled system admin and forTenant hides foreign tenants` | NOT RUN |
| **Cross-workspace source** (cùng tenant) | đủ | `DataRuntimeLiveApiTests :: same tenant, another workspace, a direct sourceRef is refused exactly like a source that does not exist` / `… cannot write either, and nothing is reserved` / `… a binding is refused by the writer and by the database` · `DataSourceScopeTests` (6) · `DataRuntimeMigrationTests` (ràng buộc DB) | NOT RUN |
| Unauthorized **APP_EDIT** | đủ | `AppRuntimeApiTests :: TEST mode of a query needs APP_EDIT …` · `AccessAdaptersTests :: port - canonical codes only, viewer can use but not edit, test mode needs APP_EDIT` · `IsolationApiTests :: a project viewer reads the basics, cannot read form data and cannot write anything` | NOT RUN |
| Unauthorized **QUERY_EXECUTE** | **một phần** | `AccessAdaptersTests :: gateway - operation to permission mapping is total and matches the contract` · `… a workspace admin may do every operation, a plain member of nothing may do none` · `… default deny - unknown operation, missing workspace, non-user actors …`. Không có test tên riêng "user có role nhưng thiếu QUERY_EXECUTE bị từ chối ở route query LIVE" → **GAP-C6-05** (Low, xác nhận khi chạy) | NOT RUN |
| Unauthorized **DATA_MUTATE** | đủ | `AppRuntimeApiTests :: without DATA_MUTATE a data action is forbidden even in TEST mode` · `AccessAdaptersTests :: gateway - a project owner may read and query but not manage, mutate or sample` · `PermissionCanonicalTests` (managerOnly = DATA_MUTATE, WORKFLOW_EXECUTE, …) | NOT RUN |
| Unauthorized **WORKFLOW_EXECUTE** | đủ ở tầng logic/adapter, **chưa qua HTTP** | `AccessAdaptersTests :: port - canonical codes only, viewer can use but not edit, test mode needs APP_EDIT` (assert: owner không có WORKFLOW_EXECUTE/DATA_MUTATE, viewer không có ACTION_EXECUTE) · `ActionRuntimeTests :: START_WORKFLOW needs WORKFLOW_EXECUTE and a key …` · `WorkflowEngineTests :: start needs APP_USE and WORKFLOW_EXECUTE and audits the denial` · `TenantRateLimitTests` (deny WORKFLOW_EXECUTE). `AppRuntimeApiTests` có workflow start nhưng test được liệt kê là "refused while the run store is volatile"/"without a key", không phải 403 vì thiếu quyền → **GAP-C6-06** (Low) | NOT RUN |
| **Secret / credential leakage** | đủ | `DataRuntimeLiveApiTests :: a stored credential reaches the connector, and appears in no response, audit row or table other than as ciphertext` · `SecurityPrimitivesTests :: a resolved credential never prints …`, `credentials are stored only as SecretsCrypto ciphertext …`, `redactor removes every known secret value` · `DataSourceAdminTests :: a repository failure leaves no orphaned credential and no secret in logs`, `secrets pasted into the configuration are refused …` · `AppRuntimeApiTests :: … no input value reaches the audit trail` · `ServerRuntimeTests :: secrets are write-only, encrypted at rest …` · `MultiProviderTests :: … admin provider list shows configuration without secrets` · `AppPlannerTests :: the prompt carries masked metadata and no connector id or credential` · `WebhookTests :: the secret is visible once and stored only as ciphertext` | NOT RUN |
| **Replay / idempotency** | đủ | `DataRuntimeLiveApiTests :: a LIVE action writes through the gateway, stores only a derived key, and a retry with the same key writes nothing again` / `an ambiguous failure keeps the key reserved as UNKNOWN and a retry never writes again` · `IdempotencyTests` (11) · `JdbcIdempotencyStoreTests` (15) · `WebhookReplayTests` (12) · `WebhookTests :: wrong secret tampered body and tampered timestamp are all rejected without effect` · `PublishApiTests` (Idempotency-Key) | NOT RUN |
| Self-grant / leo thang đặc quyền | đủ | `PrivilegeEscalationTests` (7) · `AdminTransferOwnershipSelfGrantSpec` (1, **enabled**) | NOT RUN |
| Default-deny SYSTEM_ADMIN business data | đủ | `TenantAccessTests :: SYSTEM_ADMIN default policy …` · `TenantAccessLegacyFlagTests` (flag ON/OFF) · `AccessAdaptersTests :: gateway - system admin with the flag OFF has no data permission anywhere` | NOT RUN |

GAP-C6-05 / GAP-C6-06: kiểm kê tĩnh chưa thấy test nhắm đúng ca; có thể vẫn được phủ gián tiếp. C6 sẽ viết test harness chỉ khi chạy được stack để chứng minh (không thêm test chưa chạy được vào `backend/src/test/**` — thư mục này do C0/owner sở hữu).

## 6. Gate → key trong `evidence/mac/RESULTS.txt` (batch 3)
Mỗi gate/suite bên trên ánh xạ tới một key; C6 chỉ ghi PASS khi key đó là `PASS` trong RESULTS do Mac sinh ra. Evidence Linux (batch 1) giữ nguyên ở `evidence/*.log`.

| Gate | Key | Evidence Linux (batch 1) | Mac |
|---|---|---|---|
| G-01/G-02 typecheck | `FRONTEND_TYPECHECK` (+`_ROOT/_ALL/_APPS`) | PASS | PENDING |
| G-03 unit + conformance | `FRONTEND_UNIT`, `BUG_C6_004` (INFO) | PASS 130/130 | PENDING |
| G-04 build | `FRONTEND_BUILD` (+`_APPS/_ROOT`) | PASS | PENDING |
| G-05 static QA | `STATIC_QA` | PASS 10/10 | PENDING |
| G-06 backend compile | `BACKEND_COMPILE` | — | PENDING |
| G-07 backend regression | `BACKEND_TEST` (tests/passed/failed/skipped/duration) | — | PENDING |
| §5 security/isolation | `SEC_<Class>` ×26, `SECURITY_SUITES` | kiểm kê tĩnh | PENDING |
| G-08 secret scan | `SECRET_SCAN` (tuỳ chọn) | — | PENDING |
| G-09 smoke | `SMOKE` (cần `STACK_UP`) | — | PENDING |
| Worktree không bị sửa ngoài C6 | `WORKTREE_START`, `WORKTREE_SCOPE` | mtime-check (không thay git) | PENDING |
| HEAD / branch / baseline | `HEAD`, `BRANCH_CHECK`, `BASELINE` | không xác nhận được | PENDING |


## Mac run 2026-10-06 (batch 4) — HEAD f894cc6
| Nhóm | Kết quả | Độ tin cậy evidence |
|---|---|---|
| Frontend typecheck (root/packages/apps) | PASS | thực thi thật (2 lần chạy) |
| Frontend unit + conformance (`XWEB_CONFORMANCE_DIR`) | PASS 130/130, 0 skipped | thực thi thật |
| Frontend unit mặc định | 115 tests · 114 pass · **1 skipped** (BUG-C6-004 vẫn OPEN) | thực thi thật |
| Frontend build apps + root | PASS | thực thi thật |
| Static QA | PASS 10/10 | thực thi thật |
| Secret scan (gitleaks) | PASS | thực thi thật |
| Browser builder 64/64, portals 33/33 | PASS | thực thi thật (không backend) |
| Backend compile | PASS | có thể từ cache |
| **Backend test** | run 1: 1395 · 1392 pass · 0 fail · 3 skipped (`OpenRouterLiveTests` ×3, cần key) — **nhưng `:test FROM-CACHE`, KHÔNG thực thi**; run 2 không cache: **FAIL `compileKotlin` OOM** | **KHÔNG tính là evidence** |
| **Security/isolation 26 suite** (C1 auth/tenant/workspace/permission, C3 idempotency/SqlGuard/SSRF, webhook replay…) | run 1 toàn PASS (replay cache) | **KHÔNG tính là evidence** — cần chạy lại không cache |
| Recovery R01–R08 | NOT_RUN (stack) | — |
| GAP-C6-05 (QUERY_EXECUTE denied, route LIVE) / GAP-C6-06 (WORKFLOW_EXECUTE qua HTTP) | **vẫn GAP** (không test HTTP tương ứng; không đóng khi chưa có evidence chạy) | — |
Danh sách suite replay: `evidence/mac-run1-cached-20261006T1323Z/security-suites.txt`.


## Mac run — 8e91172 (batch 5, continuous QA; prev tested f894cc6)
Evidence thật (không cache): `evidence/mac-8e91172-gate/`. Chỉ áp cho SHA này, không áp ngược sang `f894cc6` (replay cache ở đó vẫn không tính).
| Nhóm | Kết quả |
|---|---|
| Backend full no-cache | 1502 · 1499 pass · 0 fail · 3 skipped (OpenRouterLive ×3, thiếu key) · 470 s |
| Security/isolation 26 suite | 26/26 PASS (C1 tenant/workspace/permission/escalation, C3 SqlGuard/SSRF/idempotency/webhook replay, migration tenant + data runtime) |
| V29 persistence + restart recovery (C0/C4) | PASS: WorkflowRunPersistenceMigration 13, JdbcWorkflowRunStore 28, JdbcActionRunStore 23, WorkflowRestartRecovery 10, ActionRunRecovery 5, RunStoreConfiguration 6, RunCodec 6 |
| C4 lease/abandon/engine | PASS: WorkflowLease 9, WorkflowAbandonedWrite 1, ActionRunAbandonment 6, WorkflowEngine 65, ActionRuntime 64 |
| Frontend typecheck / unit+conformance / build | PASS / 130/130 / PASS |
| Static QA | 10/10 (SQ-03 đã sửa, BUG-C6-009) |
| Stack, smoke, real E2E, recovery qua HTTP, perf | NOT_RUN (cổng `hbl`, DB dev V25; C3/C2/C5 chưa sẵn sàng) |


## Batch 7 (2026-10-07) — C6 contract checks tại 8e91172 (evidence: `evidence/mac-8e91172-contract-checks/`)
| Nhóm | Kết quả |
|---|---|
| Static contract checks (`harness/contract-checks.py`, self-test 10 vi phạm tiêm đều bị bắt) | 19 PASS · 0 FAIL · 3 INFO: AccessContext (2 chỗ dựng đều truyền tenant tường minh), audit_events chỉ INSERT, không controller nào map `/api/v1/data/**` hay *mutate*, layering logic/data/tenancy/access/app.definition, V1..V28 bất biến f894cc6→8e91172, 29 hash migration được ghim |
| DB probes (`harness/db-probes.py`, PostgreSQL 17.6 cô lập, đã xoá container) | 15 PASS · 0 FAIL · 1 INFO: V1..V29 áp dụng lên DB trống; audit_events từ chối UPDATE/DELETE/TRUNCATE cho owner và role không-owner; đối chứng âm; U27 từ chối khi có policy do người sửa, drop khi rỗng. INFO: owner có thể tắt trigger (T19 chưa làm) |
| Overlay HTTP tests (`qa-tests/kotlin`, worktree tạm) | 4/4 PASS: viewer thiếu QUERY_EXECUTE → 403 (LIVE và TEST); editor thiếu WORKFLOW_EXECUTE → 403, không tạo run; đối chứng admin không bị 403 |
| `npm audit --omit=dev` | 0 lỗ hổng (backend chưa có scan: C1-SEC-28) |
Backend 1502/1499/0/3 và 26 suite security giữ nguyên (không chạy lại). Chi tiết: [QA_MASTER.md](QA_MASTER.md).
