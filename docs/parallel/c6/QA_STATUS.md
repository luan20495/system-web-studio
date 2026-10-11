# QA_STATUS — C6 (QA/Test Engineer độc lập)

> **Master index:** [QA_MASTER.md](QA_MASTER.md) is the single source of truth (test inventory with IDs, release gates, per-SHA history, defect lifecycle, traceability). This file keeps its own detail and is not replaced.

Cập nhật: 2026-10-06 (UTC, batch 3) · Worktree `/Users/hoangluan/code/xweb-c6` · Branch dự kiến `agent/c6-qa` · Baseline dự kiến `integration/v2 @ f894cc6`.
C6 **không sửa production code**. Chỉ thêm file dưới `docs/parallel/c6/**` (docs, evidence, harness).

## Kết luận: **YELLOW** (cập nhật Batch 4 bên dưới: đã chạy Mac, backend/E2E/recovery vẫn chưa có evidence thật)
(Ghi chú batch 1–3, trước khi có Mac) Không có test nào FAIL trong những gì C6 chạy được. Nhưng **toàn bộ backend (Gradle, ≈1395 `@Test` theo đếm tĩnh), E2E và browser specs chưa được C6 chạy** (môi trường chặn, xem Blockers), nên C6 không thể xác nhận GREEN. Frontend: typecheck + unit + conformance + build đều PASS.

## Batch 9 (2026-10-08) — RC WIDE REGRESSION `integration/v2 @ 62ce9697cd56` (`RC_WIDE_REGRESSION_62ce9697cd56.md`)
**WIDE_REGRESSION = PASS · APP_CREATOR = BLOCKED_BY_H-C1-04 · PUBLIC_SITE = BLOCKED (H-C2-07) · FINAL_V1_READY = NO · READY_FOR_FINAL_RETEST_AFTER_C1 = YES.** Backend 211 classes / 2003 tests / 2000 pass / 0 fail / 3 skipped (real run, no cache); frontend tsc 0, unit 277/276/0/1, browser harness 345/345; isolated c6rc stack from the RC (V1 flags, TLS data target): provisioning 35/35, C6 security 74/74, C6 management+data+action+workflow 99/99, C6 queue/recovery 16/16, C6 portals 25 (23 PASS + 2 BLOCKED_BY_H-C1-04), C5 real-backend suite ×2 (PASS 31/32 of 42; every non-PASS classified), gateway 20/20 up + 4/4 down, public hosts 104/104 + 38/38 + 69/69 + 49/49 (browser network capture). 0 regressions, 0 product NEW_BUG; new Low test/tooling defects BUG-C6-014…019 and BUG-C6-013 (Medium, refines H-C2-07). Public build still not provable (BUG-C6-012). Nothing committed.

## Batch 8 (2026-10-07) — USER GUIDE QA (`USER_GUIDE_QA.md`)
Guide `db4d595` (`agent/c5-web`) chạy từng bước trên Studio công khai tại `integration/v2` HEAD `1a9995c`. **Public version verified = NO** (không có SHA; ENVIRONMENT BLOCKED). **104 bước: PASS 57 · FAIL 14 · BLOCKED 33**; dòng REAL 68: 33 verified / 11 failed / 24 blocked. **FINAL: GUIDE NEEDS FIX · READY TO GIVE USER: NO.** Nguyên nhân gốc: guide mô tả UI của `agent/c5-web` (33 commit chưa vào integration) và tài khoản/đăng ký không có trên public; vai trò EDITOR/PUBLISHER/VIEWER BLOCKED vì không có tài khoản. Bug mới: BUG-C6-011 (guide/UI chưa tích hợp), BUG-C6-012 (không chứng minh được build công khai). QA = YELLOW không đổi; số liệu backend/requirement các batch trước giữ nguyên. Không sửa production code, không commit.

## Batch 7 (2026-10-07) — Implementation × Verification, P0 audit, C6 contract checks (`QA_MASTER.md`)
**QA = YELLOW · Production Ready = NO.** HEAD không đổi (`8e91172`), không có backend run mới; số liệu lịch sử giữ nguyên (backend 1502/1499/0/3, mapped 1360/1502 = 90,5%; baseline cũ 180 requirement / 97 FULL / 57 PARTIAL / 26 NONE / 14 P0-NONE được đóng băng ở QA_MASTER §2.2).
- **Hai chiều mới:** Implementation (IMPLEMENTED 128 · PARTIAL 54 · NOT_IMPLEMENTED 8 · UNKNOWN 1) và Verification (VERIFIED 117 · PARTIAL 22 · UNVERIFIED 4 · BLOCKED 48) trên **191** requirement (180 + 11 từ GAP-C6-14: ledger item 6 và 10 blocker còn mở trong `BLOCKERS.md`). `docs/parallel/c3/**`, `c4/**` không tồn tại trên integration/v2.
- **14 P0 NONE đã audit với code:** 2 đã **implemented** và nay **VERIFIED**: REQ-TP-07 (static check CC-TP07, có self-test) và REQ-ML-07 (đã có `AuditApiTests`/`ProjectApiTests` — GAP cũ là sai; thêm app-layer scan + DB probe có đối chứng âm). 12 còn lại **không phải lỗi QA**: Management API (`wire/c3-management-import @b557a0d`), V30 + lease/CAS (`fix/c2-v3 @1ab26f9`), RabbitMQ durable queue (`agent/c4-workflow @b2386d8`) đều chưa tích hợp; REQ-AD-15 (publish không đọc `publish_configs`) NOT_IMPLEMENTED ở mọi nhánh. Verification = BLOCKED (implementation); không tạo mock.
- **P0:** 123 requirement P0 = 82 VERIFIED · 11 PARTIAL · 0 UNVERIFIED · 30 BLOCKED-by-implementation. **QA blocker (P0 implemented nhưng unverified) = 0.**
- **Test mới (C6, không sửa production):** `harness/contract-checks.py` (22 check, self-test tiêm 10 vi phạm), `harness/db-probes.py` (Postgres 17.6 cô lập: audit append-only cho owner/non-owner + đối chứng âm, undo U27), `qa-tests/kotlin/C6RuntimePermissionHttpTests.kt` chạy trong worktree tạm bằng `harness/run-overlay-tests.sh` (4/4 PASS: GAP-C6-05 QUERY_EXECUTE và GAP-C6-06 WORKFLOW_EXECUTE bị từ chối qua HTTP, có đối chứng dương), `npm audit --omit=dev` = 0 lỗ hổng. Không phát hiện bug sản phẩm.
- **Ưu tiên P0:** 212 → 233 test case P0: 10 hạ xuống P1, 29 nâng lên P0 (batch 6 đã hạ quá tay các ca idempotency/duplicate/replay/auth), 2 row mới; mỗi row P0 có lớp rủi ro. 4 requirement P0 → P1.
- **Đính chính GAP:** 15 row đóng/đổi loại bằng bằng chứng (QA_MASTER §12): lần trước chỉ quét *tên* test nên sai ở audit append-only, TENANT_SUSPENDED, sysadmin list, adopt-draft.
- **Gate:** G1–G4 PASS_WITH_GAPS · G5/G6/G7 BLOCKED by implementation · G8 BLOCKED (implementation + environment) · G9 BLOCKED (implementation + environment) · G10/G11 NOT_RUN.
- **Bug mới:** BUG-C6-010 (Low, C0): `BLOCKERS.md` còn ghi OPEN các mục đã giao (B-C4-05/07/09, B-C5-04/09, B-C0-W-01).

## Batch 6 (2026-10-06) — System QA Traceability Master (`QA_MASTER.md`)
**QA = YELLOW · Production Ready = NO.** Không chạy thêm test; chỉ dựng chuỗi **Requirement → Test → Result → Bug → Retest → Evidence** cho HEAD `8e91172`. 180 requirement mức điều khoản (contract v2, C2 deploy contract, ledger, board, decisions, CLAUDE.md): **FULL 97 · PARTIAL 57 · NONE 26**; P0 uncovered (NONE) **14**, P1 uncovered **8**. 473 test case (✅313 ❌8 ⚠98 ○1 🔁1 🟦52), ánh xạ 1359/1502 test backend (90,5%). Release gate: G1/G2/G3/G4 = PASS_WITH_GAPS · G5/G6/G7/G8/G9 = BLOCKED · G10/G11 = NOT_RUN. Chi tiết, GAP register (GAP-C6-01…), lịch sử retest từng bug: [QA_MASTER.md](QA_MASTER.md). Sinh lại bằng `harness/qa_master.py` (xem §10 của QA_MASTER).

## Batch 5 (2026-10-06, continuous QA) — `integration/v2` @ 8e91172
**QA = YELLOW · Production Ready = NO.** Verdict không đổi: backend và security nay có evidence thật, nhưng stack/real E2E/recovery vẫn chưa chạy được.

| Mục | Giá trị |
|---|---|
| HEAD tested | `8e91172` (worktree `agent/c6-qa` fast-forward từ `f894cc6`, không đụng file tracked) |
| Previous tested HEAD | `f894cc6` (evidence của SHA đó **không** dùng làm bằng chứng cho SHA mới) |
| Diff | 15 commit, 36 file, +3565/−45 |
| Module/owner đổi | **C0**: `V29__workflow_run_persistence.sql`, `wiring/persistence/*` (JDBC action/workflow run store, codecs), `AppRuntimeConfiguration`, `application.yml`; **C4**: `logic/action/*`, `logic/workflow/*` (lease, abandoned run); docs C0. **Không đổi:** frontend, auth/IAM, data/connector, publish/deploy |
| Phạm vi chạy | migration + core wiring ⇒ **full backend không cache** + 26 suite security + frontend gate + static QA. Không chạy stack/E2E/recovery (chặn, xem Batch 4) |
| Backend | **1502 tests · 1499 passed · 0 failed · 3 skipped** (`OpenRouterLiveTests` ×3, cần key thật), 470 s, `:test` thực thi (không FROM-CACHE). Khớp 1502 `@Test` đếm tĩnh |
| Security suites | 26/26 PASS (0 fail, 0 skipped) |
| Suite bị ảnh hưởng | WorkflowRunPersistenceMigration 13 · JdbcWorkflowRunStore 28 · JdbcActionRunStore 23 · WorkflowRestartRecovery 10 · ActionRunRecovery 5 · RunStoreConfiguration 6 · RunCodec 6 · WorkflowLease 9 · WorkflowAbandonedWrite 1 · ActionRunAbandonment 6 · WorkflowEngine 65 · ActionRuntime 64 — **0 fail** |
| Frontend | typecheck PASS · unit+conformance 130/130 · build PASS (không đổi file frontend; chạy như smoke). Mặc định `test:unit` vẫn 1 skipped (BUG-C6-004 OPEN) |
| Static QA | 10/10 sau khi sửa SQ-03 (BUG-C6-009) |
| Evidence | `evidence/mac-8e91172-gate/` |

**Ý nghĩa cho V29:** persistence + restart recovery của run action/workflow đã được chứng minh ở mức **integration test Testcontainers** (restart takeover, lease, schema). **Chưa** chứng minh trên stack thật: R09 (workflow bị ngắt) vẫn cần stack; RabbitMQ durable queue (R11) và connector ghi production (R10) vẫn GAP.

**Còn chặn GREEN:** (1) stack thật: cổng bị `hbl` giữ + DB dev ở V25 (chờ quyết định Option A/B); (2) C3 chưa GREEN; (3) C2 V30/apiBase/publish runtime chưa có; (4) real E2E chưa mở khoá; (5) recovery/performance chưa chạy; (6) GAP-C6-05/06 chưa đóng.

### Trigger tiếp theo (không chạy full QA cuối lúc này)
C3 GREEN → Data/Management API/connector/security/atomicity · C0 tích hợp C3 → integration + migration/persistence · C2 V30/apiBase → publish/deploy/rollback/runtime · C5 real E2E mở khoá → real backend E2E · RC → full QA cuối (backend no-cache, frontend, security, E2E, browser, recovery, performance).
Mỗi HEAD mới: ghi SHA → diff vs SHA đã test → owner/module → smoke + regression bị ảnh hưởng → security nếu đổi contract/auth/data/action → full chỉ khi migration/core/shared wiring. Lệnh backend: `C6_GRADLE_ARGS` mặc định `-Pkotlin.daemon.jvmargs=-Xmx3g`, `--no-build-cache --rerun-tasks`.

---

## Batch 4 (2026-10-06, Mac thật) — chạy `mac-full.sh` lần đầu (đọc phần này trước)
**QA = YELLOW · Production Ready = NO.** HEAD `f894cc6`, branch `agent/c6-qa`, macOS 27.0.1 arm64, JDK 21.0.12.1, Docker 29.8.2. Selftest `passed=44 failed=0` (cũng sau khi vá harness). `mac-full.sh` exit **1** (`FINAL|FAIL|failed=STACK_UP|critical_not_run=E2E_02..06,RECOVERY,SMOKE`). Evidence: `evidence/mac/` (run 1, bản gốc giữ ở `evidence/mac-run1-cached-20261006T1323Z/`).

**Hiệu chỉnh quan trọng — backend "GREEN" của run 1 KHÔNG phải evidence.** `BACKEND_TEST|PASS|tests=1395|passed=1392|skipped=3|duration=15s` đến từ `> Task :test FROM-CACHE` (Gradle build cache phát lại kết quả cũ; BOARD ghi ~6 phút). Test **không được thực thi** trong run này. Vì vậy `SEC_*` (26 suite) cũng chỉ là replay. Harness đã vá (BUG-C6-005): `--no-build-cache --rerun-tasks` và FAIL nếu `:test` là FROM-CACHE/UP-TO-DATE. Lần chạy lại có vá (`evidence/mac-gate2-nocache-kotlin-oom-20261006T1331Z/`) **FAIL ở `:compileKotlin`: "Not enough memory to run compilation"** (log `backend/.kotlin/errors/*.log` đã lưu) trước khi chạy test nào (BUG-C6-006). => **Backend regression: NOT VERIFIED.**

| Gate | Kết quả | Evidence |
|---|---|---|
| A env/HEAD/baseline/worktree | PASS (HEAD f894cc6, 0 commit ahead, dirty ngoài c6 = 0) | `mac/ENV.txt`, `RESULTS.txt` |
| B backend compile | PASS (có thể cache) | `BACKEND_COMPILE.log` |
| B backend test | **KHÔNG CÓ EVIDENCE THẬT**: run1 = FROM-CACHE; run2 = compile OOM | xem trên |
| D security suites (26) | **KHÔNG CÓ EVIDENCE THẬT** (replay) | `security-suites.txt` (run1) |
| E frontend typecheck (root/packages/apps) | PASS ×2 lần chạy (thực thi thật) | `FRONTEND_TYPECHECK_*.log` |
| F frontend unit + conformance | PASS 130/130, 0 skipped (có `XWEB_CONFORMANCE_DIR`) | `FRONTEND_UNIT.log` |
| G frontend build (apps + root) | PASS | `FRONTEND_BUILD_*.log` |
| H static QA | PASS 10/10 | `STATIC_QA.log` |
| I secret scan (gitleaks) | PASS | `SECRET_SCAN.log` |
| J stack up | **FAIL — env: cổng bị stack `hbl` của user giữ** (13000 forgejo, 15432, 16379, 18088, 19000, 15675…) | `STACK_UP.log` dòng 76 |
| K smoke | NOT_RUN (stack) | |
| L real E2E ×5 (02–06) | NOT_RUN (stack). E2E_01 pages-mock PASS 6/6 nhưng là mock, **không phải** backend thật | `E2E_01.log` |
| M browser | PASS: builder 64/64 (component harness, không backend) + portals 33/33 (không backend) | `BROWSER_*.log` |
| N recovery | NOT_RUN (stack) | |

### STACK_UP — nguyên nhân gốc
- **Phân loại: A (port collision với stack HBL có sẵn) + G (harness thiếu preflight cổng). KHÔNG phải lỗi sản phẩm (không phải C/D/E/F/H).**
- Lệnh: `./scripts/run-local.sh` → `docker compose --profile full up -d --wait` (project `xweb-c6`).
- Dòng lỗi: `Error response from daemon: failed to set up container networking … endpoint xweb-c6-forgejo-1 … Bind for 127.0.0.1:13000 failed: port is already allocated`.
- Chủ cổng: container `hbl-forgejo-1` (compose project `hbl`, `/Users/hoangluan/code/HBL`), cùng các cổng 15432/16379/18088/19000/15675 (docker proxy pid 36240) và render worker node pid 43467 trên :18095. `compose.yml` hardcode cổng, không có override theo project.
- Harness chờ thêm 2 phút readiness trên :8080 rồi mới ghi FAIL (không có preflight) — BUG-C6-007.

### Có tái dùng được stack `hbl` hiện có không? **KHÔNG (chưa)**
- Hạ tầng healthy (postgres/redis/minio/rabbitmq/forgejo/sites-gateway/apps-gateway :18088/:18090/:13000 = 200, cùng commit `f894cc6`), nhưng **API :8080 và UI :3100 đang chết** (`backend.pid` 43477 / `frontend.pid` 43663 không còn; log backend dừng ở 2026-10-05 19:04).
- **DB dev `system_web_studio` ở Flyway V25 (1799 project), repo ở V28.** Chạy API của C6 vào đó sẽ chạy V26–V28 trên dữ liệu dev của user: thay đổi bền vững, không được làm khi chưa có phép. Vì vậy C6 **không** start API/UI vào stack này và **không** chạm/dừng/kill gì của stack `hbl` (`docker ps` vẫn 9 container hbl như trước). Container `xweb-c6-*` ở Created do chính run 1 tạo đã `docker compose -p xweb-c6 down` (không xoá volume, không đụng hbl).
- Đề xuất: **Option B** (ưu tiên): stack C6 cách ly (project name + cổng khác + DB riêng) bằng compose override và env đặt dưới `docs/parallel/c6/` — cần C0 duyệt vì `compose.yml`/`scripts/*` hardcode 8080/3100/13000/15432…, e2e/smoke có thể hardcode cổng. **Option A** chỉ khi user đồng ý migrate DB dev lên V28 (hoặc dùng bản sao DB). Hoặc tạm dừng stack `hbl` (`docker compose stop`, giữ volume) — user đã chọn phương án này ở lượt trước rồi **đổi ý** ở chỉ thị sau; chưa thực hiện.

### Quy tắc GREEN
| Điều kiện | Có evidence? |
|---|---|
| HEAD xác nhận | **có** (`f894cc6`) |
| Backend regression | **không** (cache replay / compile OOM) |
| Frontend regression | có |
| Security regression | không (replay) |
| Real E2E | không (0/5 backend thật) |
| Browser | có (component harness + portals, không backend) |
| Restart/recovery | không |
| Critical/High production blocker | **không phát hiện** (chưa chạy được phần quan trọng) |
=> **YELLOW**; không RED vì không có Critical/High regression nào được chứng minh.

### NEXT
1. User chọn Option A/B/dừng-hbl cho stack. 2. Chạy lại backend thật sau khi tăng bộ nhớ Kotlin (không sửa repo): `cd backend && ./gradlew clean test --no-daemon --no-build-cache --rerun-tasks -Pkotlin.daemon.jvmargs=-Xmx3g -Dorg.gradle.jvmargs=-Xmx3g` (nên đóng bớt ứng dụng nặng; máy 16 GB, ít RAM trống) hoặc C0 thêm `kotlin.daemon.jvmargs` vào `backend/gradle.properties`. 3. Sau đó `mac-full.sh` cho stack/E2E/recovery.

---

## Batch 3 (2026-10-06) — gói bàn giao Mac cho C0 (đọc phần này trước)
**QA = YELLOW · Production Ready = NO.** Không đổi so với batch 2: vẫn **chưa có evidence** cho backend, real E2E, browser, recovery và chưa xác nhận được HEAD. Batch này chỉ hoàn thiện **gói thực thi** để C0 chạy trên Mac bằng 1 lệnh và gửi evidence về; C6 không chạy lại phase nào bị Linux VM chặn.

**Lệnh cho C0** (chi tiết `MAC_QA_HANDOFF.md`):
```bash
cd /Users/hoangluan/code/xweb-c6
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
bash docs/parallel/c6/harness/mac-full.sh
```
**Evidence kỳ vọng:** `docs/parallel/c6/evidence/mac/` — `RESULTS.txt` (machine-readable, kết thúc bằng `FINAL|PASS|FAIL|INCOMPLETE`), `STEPS.psv` (exit code + start/end/duration + log từng lệnh), `ENV.txt` (HEAD, branch, git status, Java, Docker, Node), `security-suites.txt`, `backend-failures.txt`, `backend-skipped.txt`, `recovery.psv`, log từng phase.

| Hạng mục | Trạng thái |
|---|---|
| Evidence Linux đã có (batch 1) | typecheck PASS · unit 130/130 (mặc định 114+1 skip) · build 3 app + root PASS · static QA 10/10 · cú pháp 8 E2E — giữ nguyên (`evidence/*.log`) |
| Runner Mac | **viết xong, self-test 44/44 trong VM (chỉ kiểm logic runner), chưa chạy trên Mac** |
| Mac execution | **PENDING** (C0) |
| 4 bug Low | **vẫn OPEN** (BUG-C6-001…004) |
| GAP | GAP-C6-05, GAP-C6-06, GAP-C6-01…04; V29 / RabbitMQ wiring / management API / connector ghi production **không implement** — ghi GAP, route owner |

Luật FINAL của runner: `FAIL` nếu có phase FAIL · `INCOMPLETE` nếu thiếu prerequisite hoặc có phase critical `NOT_RUN` · `PASS` chỉ khi mọi phase critical đã chạy và không FAIL (E2E sso/public và secret scan là tuỳ chọn; dòng `GAP_*` luôn được liệt kê). **NOT_RUN không bao giờ thành PASS.**

---

## Batch 2 (2026-10-06, ~12:00 UTC) — kết quả thật, không tô màu
**Trạng thái: YELLOW (không đổi). Production Ready: NO.** Không có evidence mới cho GREEN.

Batch này yêu cầu chạy trên Mac (Java 21, Docker, backend gate, real stack, E2E, browser, recovery). **C6 không có shell Mac**: công cụ chạy lệnh của C6 là Linux VM (aarch64) đọc/ghi thư mục repo qua mount. Kiểm tra lại đầu batch 2: `git rev-parse` vẫn lỗi (gitdir ngoài vùng mount), `/usr/libexec/java_home` không tồn tại, Java 11, không `docker`, Maven/Gradle/Adoptium/Playwright CDN không truy cập được (HTTP 000), chỉ npm registry truy cập được. Vì vậy các phase sau **không chạy**, và C6 không giả lập chúng:

| Phase | Kết quả | Ghi chú |
|---|---|---|
| 1 Verify worktree | **NOT RUN** (BLOCKED) | `git` lỗi trong VM. Không xác nhận được HEAD/branch/dirty. Không có file ngoài `docs/parallel/c6/**` bị sửa trong phiên C6 (kiểm bằng mtime: 0 file khác thay đổi sau 11:20 UTC; không thay thế `git status`). |
| 2 Mac backend gate | **NOT RUN** (BLOCKED) | cần JDK 21 + Docker + Maven. Runner sẵn sàng: `harness/mac-gate.sh`, `harness/mac-full.sh` |
| 3 Security/isolation | **Chỉ kiểm kê tĩnh** — chưa có kết quả chạy | bảng test→kịch bản ở `REGRESSION_MATRIX.md` §5; `mac-full.sh` in số tests/fail/skip từng suite từ JUnit XML (`evidence/mac/security-suites.txt`) |
| 4 Real stack | **NOT RUN** (BLOCKED) | `scripts/run-local.sh` cần Docker (Postgres 17.6, Redis 8.2.1, RabbitMQ 4, MinIO, sites-gateway, Forgejo…) + `gradlew bootRun` |
| 5 Real E2E | **NOT RUN** (0/8 executed) | chỉ `node --check`. Chuỗi login→…→runtime vẫn là GAP (không có test) |
| 6 Browser E2E | **NOT RUN** | không Chrome/Chromium trong VM, Playwright CDN bị chặn |
| 7 Failure/recovery | **NOT RUN** | cần stack. Đã viết `harness/recovery.sh` (R-01…R-11); **chưa từng chạy** |
| 9 Bugs cũ | revalidated: **4/4 vẫn OPEN** | `BUGS.md` |
| 10 Docs | cập nhật | 4 file + evidence + harness |

### Việc cần làm để đi tiếp
Chạy `mac-full.sh` trên Mac theo `MAC_QA_HANDOFF.md` (batch 3) và gửi lại `evidence/mac/`. Runner **chưa được thực thi lần nào trên Mac**: lần chạy đầu cũng validate harness; lỗi harness là lỗi của C6, vi phạm hợp đồng là bug cho owner.

### Quy tắc GREEN — đánh giá hiện tại
| Điều kiện | Có evidence? |
|---|---|
| HEAD xác nhận | không |
| Backend regression GREEN | không (NOT RUN) |
| Frontend regression GREEN | có (130/130, typecheck, build) |
| Security regression GREEN | không (chỉ kiểm kê) |
| Real E2E GREEN | không |
| Browser E2E GREEN / lý do chính đáng | không / có lý do (không Chrome trong VM) nhưng chạy được trên Mac |
| Restart/recovery GREEN | không |
| Không Critical/High blocker sản xuất | chưa biết (chưa chạy backend/E2E); bug đã biết đều Low |
=> **YELLOW**. Không có Critical/High regression nào được phát hiện nên không RED.

---

## HEAD tested
- **Không xác minh được SHA.** `.git` của worktree trỏ tới `/Users/hoangluan/code/HBL/.git/worktrees/xweb-c6` (ngoài thư mục được mount cho VM) → `git rev-parse` thất bại (`fatal: not a git repository`). Vì vậy C6 **không** xác nhận HEAD == `f894cc6` và **không** xác nhận branch == `agent/c6-qa`. Test chạy trên **bản sao cây làm việc** (không `.git`) tại thời điểm 2026-10-06 ~11:40–11:47 UTC.
- Dấu vết nội dung: có `V1…V28` migration (V28 `data_runtime`), `backend/src/wiring/**`, 3 app Next (`apps/{platform,admin,studio}`), `docs/parallel/c5/**`. Việc xác nhận SHA: chạy trên Mac `git rev-parse --short HEAD && git branch --show-current` rồi điền vào đây: `HEAD = ______`.

## Môi trường C6 đã dùng
Linux VM arm64, Node v22.23.2, JDK 11 (cần 21), **không** Docker, Maven Central / Gradle / Adoptium bị chặn (HTTP 000), npm registry truy cập được. `npm ci` chạy trên **bản sao** ngoài thư mục repo để không để `node_modules` linux vào worktree macOS.

## Tests executed
| # | Hạng mục | Lệnh | Kết quả | Evidence |
|---|---|---|---|---|
| 1 | Cài đặt dependency (bản sao VM) | `npm ci` | PASS (48 packages) | `evidence/npm-ci.log` |
| 2 | Typecheck root UI | `npx tsc --noEmit -p tsconfig.json` | PASS (0 lỗi) | `evidence/tsc-root.log` (rỗng) |
| 3 | Typecheck packages | `npm run typecheck:packages` | PASS | `evidence/typecheck-packages.log` |
| 4 | Typecheck 3 app | `npm run typecheck:apps` | PASS | `evidence/typecheck-apps.log` |
| 5 | Unit/SSR (mặc định) | `npm run test:unit` | 115 tests · 114 pass · 0 fail · **1 skipped** (conformance, thiếu `XWEB_CONFORMANCE_DIR`) | `evidence/unit-default.log` |
| 6 | Unit + conformance C2 | `XWEB_CONFORMANCE_DIR=<bản sao backend/src/test/resources/app-definition> npm run test:unit` | **130 tests · 130 pass · 0 fail · 0 skipped** | `evidence/unit-with-conformance.log` |
| 7 | Build 3 app | `npm run build:apps` | PASS (platform, admin, studio; Next 16.3.8) | `evidence/build-apps.log` |
| 8 | Build root (legacy UI) | `npm run build` | PASS | `evidence/build-root.log` |
| 9 | C6 static harness | `node docs/parallel/c6/harness/static-qa.mjs` | 10/10 PASS (SQ-01…SQ-10) | `evidence/static-qa.log` |
| 10 | Cú pháp 8 script E2E | `node --check e2e/*.mjs` | 8/8 syntax OK (**không phải** chạy E2E) | `evidence/e2e-syntax.log` |

## Tổng hợp
| Chỉ số | Giá trị |
|---|---|
| Frontend unit/conformance | 130 executed · 130 passed · 0 failed · 0 skipped (lần chạy mặc định: 115 · 114 · 0 · 1) |
| Gate lệnh (typecheck ×3, build ×2, npm ci) | 6 chạy · 6 PASS · 0 FAIL |
| Static harness | 10 chạy · 10 PASS |
| **Failed (tổng)** | **0** |
| **Skipped / NOT RUN** | Backend `./gradlew test` (≈1395 `@Test`, 148 file; 0 `@Disabled`), `compileKotlin/compileTestKotlin`, E2E ×8 script, browser specs (`builder.spec.mjs` 64 harness, `portals.spec.mjs`), `scripts/smoke-test.sh`, `scripts/secret-scan.sh` (cần gitleaks) |
| Bugs | 4 (đều Low, docs/test-hygiene, 0 chức năng) — xem `BUGS.md` |
| Blockers | 4 — bên dưới |

Số `@Test` backend là đếm tĩnh theo annotation (`@Test|@ParameterizedTest|@RepeatedTest`), **không phải** số test chạy thật (parameterized/dynamic có thể khác). Phân loại heuristic theo tham chiếu `IntegrationTestBase`/`Testcontainers`/`PostgreSQLContainer`/`DataRuntimeJdbcTestBase`: 65 file (≈381 `@Test`) cần Docker; 68 file (≈1014 `@Test`, gồm toàn bộ `logic/**` và phần lớn `data/**`) có vẻ là unit thuần (cần Gradle + JDK 21 nhưng không cần Docker). Heuristic có thể lệch (ví dụ test JDBC dùng helper gián tiếp) — số thật chỉ có sau khi chạy Gradle.

## Blockers
| ID | Mô tả | Cần gì | Owner mở khoá |
|---|---|---|---|
| BLK-C6-01 | Backend Gradle không chạy được: VM chỉ JDK 11 (cần 21), không Docker (Testcontainers), Maven/Gradle/Adoptium bị chặn | Mac: `mac-full.sh` (hoặc riêng `mac-gate.sh`) | C0 / Mac |
| BLK-C6-02 | Git metadata của worktree nằm ngoài vùng mount → không xác minh HEAD/branch/diff vs baseline, không kiểm được "không sửa file owner khác" bằng diff | Chạy `git status -sb && git rev-parse --short HEAD` trên Mac, dán vào mục HEAD | C6 trên Mac / C0 |
| BLK-C6-03 | Không có Chromium/Playwright browser và không có full stack (Docker + Postgres/Redis/RabbitMQ/MinIO) → E2E `e2e/*.mjs`, `tests/browser/*.spec.mjs` không chạy | Mac + `CHROME=...` + `scripts/run-local.sh` | C0 / C5 |
| BLK-C6-04 | `gitleaks` không có → secret scan chưa chạy (chỉ có heuristic SQ-09 trên `.env.example`) | `scripts/secret-scan.sh` trên Mac | C0 |

## Việc cần làm tiếp (NEXT)
1. **C0 (Mac):** `bash docs/parallel/c6/harness/mac-full.sh` theo `MAC_QA_HANDOFF.md`; gửi lại thư mục `docs/parallel/c6/evidence/mac/`.
2. **C6:** đọc `RESULTS.txt`, cập nhật `QA_STATUS.md` / `REGRESSION_MATRIX.md` / `E2E_MATRIX.md` (đổi NOT RUN → PASS/FAIL), mở bug theo format và route owner, retest sau fix, revalidate 4 bug Low.
3. **C0:** ghi record vào `BASELINE.md` mục Re-baseline (BUG-C6-003); sửa docs BUG-C6-001/002; **C5:** BUG-C6-004.
4. Chỉ nâng GREEN khi đủ evidence theo bảng "Quy tắc GREEN".
