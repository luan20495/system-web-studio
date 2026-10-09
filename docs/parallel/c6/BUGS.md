# BUGS — C6

> **Master index:** [QA_MASTER.md](QA_MASTER.md) is the single source of truth (test inventory with IDs, release gates, per-SHA history, defect lifecycle, traceability). This file keeps its own detail and is not replaced.

Cập nhật 2026-10-06 (batch 3). Format bắt buộc: BUG ID · Severity · Owner · Environment · Commit SHA · Command · Expected · Actual · Reproduction · Logs/evidence · Regression YES/NO.
C6 không sửa production code: chỉ ghi, route owner, retest. Owner: **C1** IAM/tenant/permission/isolation · **C2** build/publish/deploy/rollback · **C3** data/connector/query/mutation/credential/idempotency · **C4** action/workflow/retry/timeout/compensation/RabbitMQ · **C5** Studio/frontend/browser · **C0** integration/migration/persistence/runtime wiring.

**Batch 4 (Mac thật): chưa có bug chức năng sản phẩm; có 4 bug mới (BUG-C6-005…008, harness/config).** Revalidate trên Mac: BUG-C6-001 OPEN (`README.md:38` vẫn "185 tests"), 002 OPEN (checklist dòng 44 vẫn ghi "still disabled"; `@Disabled` = 0), 003 OPEN (`V28__data_runtime.sql` có trong cây, BOARD ghi "not imported into integration/v2", BASELINE "Re-baseline (macOS) — chưa có"), **004 OPEN (`FRONTEND_UNIT_DEFAULT`: 115 tests, 1 skipped, exit 0)**. GAP-C6-05 và GAP-C6-06 **chưa đóng** (không có test HTTP; stack chưa chạy). Cũ: **Không có bug chức năng.** Backend, real E2E, browser và recovery **chưa chạy** (chờ Mac — `MAC_QA_HANDOFF.md`), nên không loại trừ bug ở đó. Bug mới (nếu có) sẽ được mở từ `evidence/mac/RESULTS.txt` theo mẫu cuối file.

| ID | Severity | Owner | Tóm tắt | Status | Regression |
|---|---|---|---|---|---|
| BUG-C6-001 | Low | C0 | `README.md` ghi "185 tests"; số liệu lệch xa | **OPEN** | NO |
| BUG-C6-002 | Low | C0 (docs) / C1 (spec) | Docs kỳ vọng `AdminTransferOwnershipSelfGrantSpec` còn `@Disabled` (skipped = 3+1) nhưng spec đã ENABLED | **OPEN** | NO |
| BUG-C6-003 | Low | C0 | `BOARD.md`/`BASELINE.md` mâu thuẫn với nội dung worktree (V28; "Re-baseline" trống) | **OPEN** | NO |
| BUG-C6-004 | Low | C5 | `npm run test:unit` mặc định exit 0 nhưng SKIP conformance → rủi ro false-green | **OPEN** | NO |
| BUG-C6-005 | Medium (harness) | C6 | Gate backend chấp nhận kết quả `:test FROM-CACHE` như PASS (không thực thi test) | **FIXED trong harness** (`lib.sh`); verified selftest 44/44 + gate2 từ chối cache | YES (lộ ở Mac run 1) |
| BUG-C6-006 | Low | C0 | `compileKotlin` không cache: "Not enough memory to run compilation" trên Mac 16 GB; `backend/gradle.properties` không đặt `kotlin.daemon.jvmargs` | **OPEN** (config): chạy được với `-Pkotlin.daemon.jvmargs=-Xmx3g` ở 8e91172 | NO |
| BUG-C6-007 | Low (harness) | C6 | `mac-full.sh` không preflight cổng/stack có sẵn: STACK_UP fail sau khi tạo container, chờ 2 phút | **OPEN** (đề xuất preflight → `BLOCKED`) | NO |
| BUG-C6-009 | Low (harness) | C6 | Static QA SQ-03 hardcode `max <= 28` nên FAIL khi C0 cấp V29 hợp lệ (8e91172) | **FIXED** (đọc `MIGRATION_LEDGER.md`); 10/10 sau fix | YES |
| BUG-C6-008 | Low | C0 | `backend/.kotlin/` (artifact của Kotlin compile thật) không nằm trong `.gitignore` → `WORKTREE_SCOPE` FAIL giả | **OPEN** | NO |

| BUG-C6-010 | Low | C0 | `BLOCKERS.md` còn ghi OPEN các mục đã giao (B-C4-05 V29, B-C4-07 wiring, B-C4-09 controllers, B-C5-04 TEST/WouldRun, B-C5-09 quyết định D-C0-17/19, B-C0-W-01 cho run store) | **OPEN** | NO |
| BUG-C6-011 | Medium (doc + integration) | C5/C7 (guide), C0 (merge) | `C5_STUDIO_USER_GUIDE.md` mô tả UI của `agent/c5-web` (33 commit chưa vào `integration/v2`): nhãn nguồn dữ liệu/khe/dữ liệu công khai/Thử lại/Thêm hành động/Thêm workflow không tồn tại trên Studio công khai; dấu [REAL] §2.2/§4/§5 không tái hiện được; §10 bước 1 và 6 không làm được; còn khẳng định đăng ký tự do (sai) | **OPEN** | NO |
| BUG-C6-012 | Medium (env) | C0 | Build công khai không chứng minh được là `integration/v2` HEAD: không có SHA nhúng trong jar/Next, không endpoint công khai trả SHA | **OPEN** | NO |
| BUG-C6-013 | Medium | C2 + C5 | RC `62ce9697cd56`: a page published from Studio with a public query never serves data: the server needs `publish_configs.public_data_approved` (only `PUT …/publish-config`), the Studio dialog's acknowledgement is local and no Studio code calls that route → data route 404, visitor sees fallback. Refines the open handoff H-C2-07; E2E-PD01's own diagnosis ("not wired") is wrong | **OPEN** (known handoff, effect understated) | NO |
| BUG-C6-014 | Low (test) | C5 | `E2E-S7` fails whenever it runs after `E2E-PD01/PD02` (shared project A keeps public-data bindings, the publish dialog needs the acknowledgement tick); passes alone and after other flows | **OPEN** | NO |
| BUG-C6-015 | Low (test) | C1 | `scripts/provisioning-e2e.mjs` check A `/auth/me` platform-view needs a foreign workspace that does not exist on an empty DB: 34/35 first run, 35/35 second | **OPEN** | NO |
| BUG-C6-016 | Low (test) | C0 | `tests/gateway/portal-route.mjs` uses the *trusted* gateway container before it is ready (check 12, `socket hang up`): 5 of 8 runs fail; header documents `GATEWAY_FORCE_HTTPS`, code reads `PORTAL_FORCE_HTTPS` | **OPEN** | NO |
| BUG-C6-017 | Low (tooling) | C5 | `docs/parallel/c5/e2e-stack.sh` starts the sites gateway without `GATEWAY_REAL_IP_FROM` / `GATEWAY_FORCE_HTTPS`; the RC template exits (`nginx [emerg] host not found in set_real_ip_from`) | **OPEN** | NO |
| BUG-C6-018 | Low (test) | C0 | `scripts/v1-smoke.mjs` and `v1-public-smoke.mjs` take `/auth/me` `workspaces[0]` as the member workspace; the list is ordered by name so any non-empty DB gives `project created \| 403` | **OPEN** | NO |
| BUG-C6-019 | Low (test) | C5 | `E2E-06/08/09/14` are stale skeletons (obsolete blockers: slot op, data host; E2E-14 expects an error state while the product accepts the run PENDING and recovers ~2 min after the broker is back) | **OPEN** | NO |
Revalidated lần cuối 2026-10-06 ~12:00 UTC (batch 2) bằng đọc trực tiếp cây hiện tại; không có fix nào trong worktree. BUG-C6-004 sẽ được tự revalidate bằng dòng `BUG_C6_004|INFO|default_npm_test_unit=…` trong `RESULTS.txt` (đóng khi `skipped=0`).

---

## BUG-C6-001 — README test count stale
- **Severity:** Low · **Owner:** C0 (README/docs gốc) · **Regression:** NO (docs)
- **Environment:** Linux VM (đọc file + static-qa), Node 22.23.2. Không chạy Gradle.
- **Commit SHA:** chưa xác nhận (git không đọc được trong VM; kỳ vọng `f894cc6`). Sẽ có ở `RESULTS.txt` (`HEAD|…`).
- **Command:** `grep -n "185 tests" README.md` ; `node docs/parallel/c6/harness/static-qa.mjs --json` (inventory)
- **Expected:** README không ghi số test sai (hoặc khớp thực tế).
- **Actual:** README dòng 38: `./gradlew test   # 185 tests`. `BASELINE.md` ghi 192 (trước Phase 0). Đếm tĩnh hiện tại: 1395 `@Test` annotation / 133 file có test (logic 390, data 380, app 118, wiring 123…).
- **Reproduction:** chạy hai lệnh trên ở repo root.
- **Logs/evidence:** `evidence/static-qa.log` (INVENTORY); `evidence/batch2-env-probe.txt`.
- **Đề xuất (không bắt buộc):** bỏ số cứng hoặc cập nhật sau Mac gate (`BACKEND_TEST|…|tests=` trong `RESULTS.txt`).

## BUG-C6-002 — Kỳ vọng "skipped" sai: AdminTransferOwnershipSelfGrantSpec đã ENABLED
- **Severity:** Low · **Owner:** C0 (docs: `MAC_INTEGRATION_CHECKLIST.md` S2/S5, `BLOCKERS.md` B-C1-13, `V2_IMPORT_MANIFEST.md`); C1 chỉ cần biết · **Regression:** NO
- **Environment:** Linux VM (đọc file).
- **Commit SHA:** chưa xác nhận (xem trên).
- **Command:** `grep -c "@Disabled" backend/src/test/kotlin/com/systemwebstudio/tenancy/AdminTransferOwnershipSelfGrantSpec.kt` ; `grep -rn "@Disabled" backend/src/test | wc -l` ; `sed -n 44p docs/parallel/MAC_INTEGRATION_CHECKLIST.md`
- **Expected:** docs khớp mã: nếu spec enabled thì kỳ vọng skip không tính nó.
- **Actual:** spec **không** có `@Disabled` (KDoc: "ENABLED by C0 … applied in 8b944cc"); toàn `backend/src/test` có **0** `@Disabled`; checklist dòng 44 vẫn ghi "skipped = 3 + 1 (… still disabled)". Skip còn lại chỉ do điều kiện môi trường (vd `OpenRouterLiveTests` với `@EnabledIfEnvironmentVariable`).
- **Reproduction:** các lệnh trên.
- **Logs/evidence:** `evidence/static-qa.log` (backendDisabledAnnotations = 0). Danh sách test thực sự skip sẽ có ở `evidence/mac/backend-skipped.txt`.
- **Đề xuất:** sau Mac gate, sửa kỳ vọng thành số skip thật.

## BUG-C6-003 — BOARD/BASELINE mâu thuẫn với worktree
- **Severity:** Low (rủi ro quy trình) · **Owner:** C0 · **Regression:** NO
- **Environment:** Linux VM (đọc file).
- **Commit SHA:** chưa xác nhận — chính điểm này cần SHA để phân xử.
- **Command:** `ls backend/src/main/resources/db/migration | sort -V | tail -2` ; `sed -n '/Re-baseline/,$p' docs/parallel/BASELINE.md` ; `grep -n "not imported into" docs/parallel/BOARD.md`
- **Expected:** BOARD/BASELINE mô tả đúng cây `integration/v2 @ f894cc6`.
- **Actual:** (a) cây có `V28__data_runtime.sql` trong khi BOARD ghi V28 "branch only; **not imported into integration/v2**" — hoặc HEAD ≠ baseline mô tả, hoặc BOARD chưa cập nhật (C6 không phân xử được vì không có git). (b) `BASELINE.md` mục "Re-baseline (macOS)" vẫn "chưa có" dù BOARD nhiều dòng ghi "Mac GREEN" — thiếu bản ghi lệnh/SHA/số liệu.
- **Reproduction:** các lệnh trên.
- **Logs/evidence:** `evidence/static-qa.log` (SQ-02/03: V1…V28 liên tục, không V29). Dòng `BASELINE|…` và `BRANCH_CHECK|…` trong `evidence/mac/RESULTS.txt` sẽ cho biết HEAD có hậu duệ của `f894cc6` không.
- **Đề xuất:** C0 chép record từ `RESULTS.txt` vào BASELINE.

## BUG-C6-004 — Conformance SKIP im lặng, exit 0
- **Severity:** Low · **Owner:** C5 (`tests/builder/conformance.test.ts`, `scripts/test-unit.mjs`) · **Regression:** NO (hành vi từ thiết kế C5, nhưng tạo rủi ro false-green)
- **Environment:** Linux VM, Node 22.23.2, `npm ci` trên bản sao cây (Linux arm64).
- **Commit SHA:** chưa xác nhận.
- **Command:** `npm run test:unit` (không set env) so với `XWEB_CONFORMANCE_DIR=<backend/src/test/resources/app-definition> npm run test:unit`
- **Expected:** gate unit mặc định chạy conformance với fixtures C2 (đã có trong repo) hoặc fail rõ khi thiếu.
- **Actual:** mặc định `115 tests · 114 pass · 0 fail · 1 skipped`, exit 0 (test "conformance: manifest is present and lists fixtures" skip: `XWEB_CONFORMANCE_DIR` not set). Có env: `130/130 pass, 0 skipped`. 15 test conformance bổ sung chỉ chạy khi nhớ set biến.
- **Reproduction:** chạy hai lần với/không biến.
- **Logs/evidence:** `evidence/unit-default.log`, `evidence/unit-with-conformance.log`.
- **Đề xuất:** `scripts/test-unit.mjs` mặc định trỏ vào `backend/src/test/resources/app-definition` nếu thư mục tồn tại.

---

## Không phải bug (ghi nhận để khỏi nhầm)
- Số unit frontend 115 (114 + 1 skip) khác "113 / 112 / 1 skipped" trong `c5/PHASE3_AUDIT.md`: chênh +2, nguyên nhân chưa xác định (không có git để so); số có conformance (130) khớp tài liệu. Sai lệch số liệu docs, không phải lỗi.
- Mọi route của 3 app Next hiển thị `ƒ Dynamic` (có `proxy.ts`): đúng thiết kế.
- **Khoảng trống test (GAP), chưa phải bug:** GAP-C6-05 (không thấy test riêng cho user có role nhưng thiếu `QUERY_EXECUTE` bị từ chối ở route query LIVE — owner C1/C3) · GAP-C6-06 (`WORKFLOW_EXECUTE` bị từ chối mới có test ở tầng logic/adapter, chưa qua HTTP — owner C1/C4) · GAP-C6-01…04 ở `E2E_MATRIX.md`. Cần chạy trên Mac để xác nhận có thật sự thiếu hay được phủ gián tiếp.

## Mẫu mở bug mới (khi có evidence Mac)
```
BUG-C6-0NN · Severity (Critical/High/Medium/Low) · Owner (C0–C5)
Environment: macOS <ver>, JDK 21.x, Docker <ver>, STACK_PROFILE=…   (từ ENV.txt)
Commit SHA: <HEAD từ RESULTS.txt>
Command: <lệnh trong STEPS.psv / log=…>
Expected: … · Actual: … (trích log)
Reproduction steps: … · Logs/evidence: docs/parallel/c6/evidence/mac/<file>
Regression: YES/NO (YES = từng xanh ở baseline/BOARD "Mac GREEN" rồi hỏng)
```


## BUG-C6-005 — Gate backend nhận Gradle build-cache replay
- **Severity:** Medium (làm sai verdict) · **Owner:** C6 · **Regression:** YES (Mac run 1)
- **Environment:** macOS 27.0.1 arm64, JDK 21.0.12.1, Gradle 9.8.0 (`org.gradle.caching=true`) · **Commit SHA:** f894cc6
- **Command:** `cd backend && ./gradlew clean test --no-daemon --console=plain` (trong `phase_backend`)
- **Expected:** test được thực thi. **Actual:** `> Task :test FROM-CACHE`, `BUILD SUCCESSFUL in 14s`; harness ghi `BACKEND_TEST|PASS|tests=1395|…|duration=15s` từ JUnit XML phát lại.
- **Fix:** `--no-build-cache --rerun-tasks` + FAIL `reason=tests_not_executed` nếu `:test` là FROM-CACHE/UP-TO-DATE/NO-SOURCE.
- **Evidence:** `evidence/mac-run1-cached-20261006T1323Z/BACKEND_TEST.log`.

## BUG-C6-006 — Kotlin compile OOM khi không có cache
- **Severity:** Medium · **Owner:** C0 (cấu hình Gradle) · **Regression:** chưa biết
- **Environment:** macOS 27.0.1, 16 GB RAM (Docker VM + Chrome + nhiều JVM đang chạy, ~16 MB RAM trống), JDK 21.0.12.1 · **Commit SHA:** f894cc6
- **Command:** `./gradlew clean test --no-daemon --no-build-cache --rerun-tasks`
- **Expected:** biên dịch xong rồi chạy test. **Actual:** `Execution failed for task ':compileKotlin' … Not enough memory to run compilation. Try to increase it via 'gradle.properties': kotlin.daemon.jvmargs=-Xmx<size>` (`BUILD FAILED in 1m 33s`).
- **Reproduction:** lệnh trên trong `backend/` ở máy có ít RAM trống. **Có thể là môi trường**; xác nhận bằng cách chạy lại với `-Pkotlin.daemon.jvmargs=-Xmx3g`.
- **Evidence:** `evidence/mac-gate2-nocache-kotlin-oom-20261006T1331Z/BACKEND_TEST.log`, `…/backend-dot-kotlin/errors/*.log`.

## BUG-C6-007 — Harness không preflight cổng
- **Severity:** Low · **Owner:** C6 · **Regression:** NO
- **Command:** `bash docs/parallel/c6/harness/mac-full.sh` khi stack `hbl` đang chạy. **Actual:** `Bind for 127.0.0.1:13000 failed: port is already allocated` (`STACK_UP.log:76`), harness chờ 2 phút readiness rồi `STACK_UP|FAIL`.
- **Đề xuất:** kiểm 8080/3100/13000/15432/16379/18088/19000/15675/18095 và project `hbl`/`xweb-c6` trước khi `run-local.sh`; ghi `STACK_UP|NOT_RUN|reason=BLOCKED_ports_in_use(owner=…)` thay vì FAIL.

## BUG-C6-008 — `backend/.kotlin/` không bị gitignore
- **Severity:** Low · **Owner:** C0 · **Regression:** NO
- **Actual:** một lần compile Kotlin THẤT BẠI (OOM) tạo `backend/.kotlin/{errors,sessions}` (không tái hiện khi compile thành công ở 8e91172); `git status` báo `?? backend/.kotlin/` → `WORKTREE_SCOPE|FAIL|new_changes_outside_c6=1`. C6 đã copy vào evidence rồi xoá thư mục untracked này (không động file tracked).
- **Đề xuất:** thêm `.kotlin/` vào `.gitignore`.

## BUG-C6-009 — SQ-03 hardcode ngưỡng migration
- **Severity:** Low (harness) · **Owner:** C6 · **Regression:** YES (lộ khi V29 vào integration/v2)
- **Environment:** macOS 27.0.1, Node 22.23.1 · **Commit SHA:** 8e91172
- **Command:** `node docs/parallel/c6/harness/static-qa.mjs` · **Expected:** PASS khi mọi migration đã được C0 cấp · **Actual:** `FAIL SQ-03 … max=V29` (check `max <= 28`).
- **Fix:** SQ-03 lấy version cao nhất đã cấp từ `docs/parallel/MIGRATION_LEDGER.md` (V29 allocated, V30 reserved C2).
- **Evidence:** `evidence/mac-8e91172-gate/STATIC_QA.log` (fail) · `STATIC_QA_rerun_after_SQ03_fix.log` (10/10).

## Revalidate ở 8e91172
BUG-C6-001…004 vẫn OPEN (15 commit không đụng README/docs liên quan/frontend; 004: default `test:unit` = 115 tests, 1 skipped). Không có bug sản phẩm mới.

## BUG-C6-010 — BLOCKERS.md lỗi thời
- **Severity:** Low (docs) · **Owner:** C0 · **Regression:** NO · **Commit SHA:** 8e91172 · **Environment:** macOS, đọc cây + code
- **Command:** `grep -n "B-C4-05\|B-C4-07\|B-C4-09\|B-C5-04\|B-C5-09" docs/parallel/BLOCKERS.md` rồi đối chiếu `ls backend/src/main/resources/db/migration | tail -1` (V29), `backend/src/main/kotlin/com/systemwebstudio/wiring/AppRuntimeConfiguration.kt`, `AppRuntime{Action,Data}Controller.kt`, `logic/action` (`WouldRun`), `DECISIONS.md` D-C0-17/19
- **Expected:** cột trạng thái phản ánh việc đã giao. **Actual:** các mục trên vẫn `OPEN` dù đã có V29, wiring, controller, TEST/WouldRun và quyết định.
- **Evidence:** `QA_MASTER.md` §6 (REQ-BL-10), test NF-DOC-04. Không ảnh hưởng chức năng.
- **Lưu ý (batch 7):** các blocker thực sự còn mở và ảnh hưởng release (đã kiểm với code): B-C0-W-02 (AiDataCatalogAdapter, DataWebhookController), B-C0-W-03 (Management API), B-C0-W-04 (connector chỉ đọc), B-C3-09 (bus nhiều node), B-C3-10 (SyncRunner chưa được lập lịch), B-C4-08 (rate limit cluster), B-C0-WEB-01/B-C5-08 (OIDC 3 origin). Chúng nằm trong `QA_MASTER.md` là REQ-BL-01…10.

## Đính chính batch 7 (không phải bug)
GAP-C6-08 (audit append-only) và các GAP cũ khác đã được đóng bằng bằng chứng sau khi đọc thân test (QA_MASTER §12). GAP-C6-05/06 đóng bằng test HTTP của C6 (4/4 PASS). Không bug sản phẩm mới.

## BUG-C6-011 — User guide mô tả UI chưa được tích hợp
- **Severity:** Medium (doc + integration) · **Owner:** C5/C7 (guide), C0 (merge UI) · **Regression:** NO · **Commit SHA:** guide `db4d595`, integration `1a9995c` · **Environment:** Studio công khai `https://studio.toolsmcp.uk`, Chrome headless, tài khoản `demo01`
- **Command:** `node harness/ug-run.mjs`, `ug-probe2…6.mjs`, `python3 harness/ug_matrix.py`
- **Expected:** làm đúng theo guide thì cho đúng kết quả guide nêu. **Actual:** 14/104 bước FAIL, 33 BLOCKED; nhãn Thêm nguồn dữ liệu / Thêm khe / Dữ liệu công khai / Thử lại / + Thêm hành động / Thêm workflow không có; Studio ghi "Chưa sẵn sàng… chưa được nối vào máy chủ" dù backend có API (`GET /data-sources` 200, `…/app-runtime/…` có route). `git rev-list --count integration/v2..db4d595` = 33.
- **Evidence:** `USER_GUIDE_QA.md`, `USER_GUIDE_QA_MATRIX.md`, `evidence/user-guide-20261007/`. **Retest:** sau khi C0 merge UI C5 hoặc guide sửa theo build, chạy lại `ug-run.mjs`.

## BUG-C6-012 — Build công khai không chứng minh được phiên bản
- **Severity:** Medium (environment) · **Owner:** C0 · **Regression:** NO · **Commit SHA:** `1a9995c` · **Environment:** compose `hblpub`
- **Actual:** jar 14:44 (khởi động 14:48), `.next` 2026-10-06 14:46; code backend đổi lần cuối 13:29, `1a9995c` (15:07) chỉ đụng compose/infra/scripts/docs ⇒ suy luận được là khớp, **không chứng minh được**. `/actuator/*` do Next trả HTML; `/api/v1/version` cần login.
- **Đề xuất:** nhúng git SHA (build-info) vào jar và Next, expose ở endpoint công khai. **Evidence:** `evidence/user-guide-20261007/version-check.txt`.

## Batch 9 — RC `62ce9697cd56` wide regression (details: `RC_WIDE_REGRESSION_62ce9697cd56.md`)
BUG-C6-013 (Medium, C2 + C5): evidence = `evidence/rc-62ce9697cd56/logs/rc-public-smoke-noack.log` (8 of 42 checks fail without the `publish-config` acknowledgement, 42/42 with it), `logs/e2e-PD01-alone.log` (all Studio/API checks pass, anonymous data route 404). Repro: `C6_NO_ACK=1 API=… LOCAL_ADMIN_PASSWORD=… node docs/parallel/c6/harness/rc-public-smoke.mjs`.
BUG-C6-014…019 are test/tooling defects (Low), no product regression. No REGRESSION against D-C0-41 / C5's baseline. Known blockers unchanged: H-C1-04 (C1, final), H-C2-07 (C2/C5).

## Batch 10 — UI/UX regression of C5 candidate `5cc230e` (details: `UI_UX_REGRESSION_5cc230e.md`, bugs: `evidence/ui-ux-regression/5cc230e491a6/bugs-final.tsv`)
UX-001 (P1, C5): Studio Data rail crashes (English Next error screen) on a server-valid mapping without `transforms[]`; same on RC. UX-002…008 (P2, C5): builder at ≤430 (known UI-21), cramped Data/Action rails, axe serious ×4 rules (29 nodes), targets < 24px. UX-009…013 (P3). READY_FOR_UI_SIGNOFF = NO. Final sign-off not run (no C5_FINAL_HEAD / INTEGRATION_SHA / PUBLIC_URLS).

## Batch 11 — targeted retest of C5 `40ee45b` (details: `UI_UX_RETEST_40ee45b.md`)
UX-001 PASS, axe critical 0 / serious 0, process safety PASS, S4 regression PASS. Open: UX-002b (P2, C5-S2: rail panel 24px / inspector unusable at ≤ 430, no notice), UX-014 (P3 toolbar scroller without cue), UX-015/016 (P3 heading-order, empty table header), UX-017 (P3, `e2e-stack.sh down` exits silently without stack.env). READY_FOR_UI_SIGNOFF = NO; READY_FOR_C0_IMPORT = YES.
