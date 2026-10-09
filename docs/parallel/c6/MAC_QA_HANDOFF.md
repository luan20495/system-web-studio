# MAC_QA_HANDOFF — C6 → C0

Cập nhật 2026-10-06. Mục đích: C0 chạy **toàn bộ gate QA của C6 trên Mac bằng 1 lệnh**, rồi gửi thư mục evidence về cho C6 đánh giá.
C6 không có shell Mac (chỉ có Linux VM, không Docker/JDK 21/Chrome/Maven), nên **chưa có kết quả backend, real E2E, browser, recovery**. QA hiện tại = **YELLOW**, Production Ready = **NO**.

> **Trạng thái runner:** đã viết và **self-test 44/44** trong Linux VM (parser, luật FINAL, timeout, ghi exit code/timestamp, fail-fast). **Chưa từng chạy trên Mac.** Lần chạy Mac đầu tiên cũng là validate harness: nếu script hỏng vì chính harness thì đó là lỗi C6 (gửi log về), không phải lỗi sản phẩm.

## 1. Lệnh chạy (copy/paste)
```bash
cd /Users/hoangluan/code/xweb-c6
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
bash docs/parallel/c6/harness/selftest.sh          # ~20 giây, không đụng Docker/Gradle; phải in "SELFTEST passed=44 failed=0"
bash docs/parallel/c6/harness/mac-full.sh          # chạy mọi thứ; in RESULTS cuối cùng
```
Biến thể:
```bash
bash docs/parallel/c6/harness/mac-gate.sh          # chỉ gate: HEAD + backend + frontend + static (không stack/E2E/browser)
RUN_SSO=1 RUN_PUBLIC=1 bash docs/parallel/c6/harness/mac-full.sh   # thêm sso-flow (Keycloak, restart API) và public-flow
bash docs/parallel/c6/harness/mac-full.sh --stop-stack             # dừng API/UI khi xong
bash docs/parallel/c6/harness/mac-full.sh --allow-partial          # prerequisite thiếu thì vẫn chạy phần còn lại (FINAL chỉ có thể INCOMPLETE)
```
Exit code: `0` = FINAL PASS · `1` = FINAL FAIL · `2` = FINAL INCOMPLETE. Không dùng `git reset/clean`, không sửa file tracked ngoài `docs/parallel/c6/**` (script tự kiểm: `WORKTREE_SCOPE`).

## 2. Prerequisites (script kiểm đầu tiên và **fail-fast**: thiếu thì ghi `PREREQ|FAIL|missing=…`, `FINAL|INCOMPLETE`, exit 2)
| Cần | Ghi chú |
|---|---|
| macOS, repo đúng `/Users/hoangluan/code/xweb-c6` | `git rev-parse` phải đọc được (worktree hợp lệ) |
| **JDK 21** | `brew install openjdk@21`; script tự tìm qua `/usr/libexec/java_home -v 21` hoặc `/opt/homebrew/opt/openjdk@21` |
| **Docker Desktop đang chạy** | Testcontainers (backend test) + stack thật. Lần đầu có thể phải pull image |
| Node ≥ 22, npm, python3, curl, lsof | `npm ci` tự chạy nếu chưa có `node_modules` (bản macOS) |
| Google Chrome tại `/Applications/Google Chrome.app` (hoặc `CHROME=/path`) | E2E có trình duyệt + browser specs; thiếu → các phase đó `NOT_RUN` |
| Mạng tới npm registry | `npm i --prefix /tmp/esb esbuild` cho browser harness (ngoài repo) |
| `gitleaks` (tuỳ chọn) | `brew install gitleaks`; thiếu → `SECRET_SCAN|NOT_RUN` (không chặn FINAL) |
| Cổng trống | 8080, 3100, 18095, 18088, 15432, 16379, 19000/19001, 15675 (stack); 3001–3003 và 4000 (browser specs). Cổng bận → phase đó `NOT_RUN`, script **không kill** tiến trình không phải của nó |

## 3. `.env`
Stack cần `.env` (gitignored) ở root worktree, có `LOCAL_ADMIN_PASSWORD` (≥ 14 ký tự). Script: nếu chưa có `.env` mà tồn tại `/Users/hoangluan/code/HBL/.env` thì **copy** (đúng `WORKTREE_SETUP.md` §2); nếu không có cả hai → `STACK_UP|NOT_RUN reason=no_.env`. Hoặc làm tay: `cp .env.example .env` rồi đặt `LOCAL_ADMIN_PASSWORD`. Script không in và không ghi giá trị `.env` vào evidence. Mọi biến khác lấy default từ `scripts/_env.sh`.

## 4. Docker services
`STACK_PROFILE=full` (mặc định; `code-flow` và `runtime-flow` cần `full`): `postgres` (17.6), `redis`, `minio`, `rabbitmq`, `sites-gateway`, + `forgejo`, `verdaccio`, `appdb`, `apps-gateway`. Keycloak chỉ khi `RUN_SSO=1`. Script gọi `scripts/run-local.sh` (docker compose `up -d --wait`, `gradlew bootRun` ~1 phút lần đầu, Next UI :3100, render worker, build runner) rồi chờ `actuator/health/readiness` = 200 và UI = 200. Stack **không được mock**.

## 5. Phase → key trong `RESULTS.txt`
| Phase | Key | Ghi chú |
|---|---|---|
| A env/HEAD/prereq | `HEAD` `BRANCH` `BRANCH_CHECK` `BASELINE` `JAVA` `DOCKER` `PREREQ` `WORKTREE_START` | branch ≠ `agent/c6-qa` hoặc HEAD không phải hậu duệ `f894cc6` → **WARN**, test HEAD thực tế, không reset |
| B backend | `BACKEND_COMPILE` `BACKEND_TEST` | `gradlew compileKotlin compileTestKotlin`, `gradlew clean test --no-daemon`; đếm từ JUnit XML: tests/passed/failed/skipped/duration |
| 3 security | `SEC_<Class>` ×26, `SECURITY_SUITES` | từng suite có tests/fail/skipped riêng; suite không chạy → NOT_RUN (không PASS) |
| C/D/E frontend | `FRONTEND_TYPECHECK` `FRONTEND_UNIT` (conformance bật) `FRONTEND_BUILD` `BUG_C6_004` (INFO) | |
| F static | `STATIC_QA` | 10/10 |
| H secret | `SECRET_SCAN` | tuỳ chọn |
| I stack | `STACK_UP` | |
| G smoke | `SMOKE` | chạy ngay sau I (cần stack) |
| J E2E | `E2E_01` pages-mock · `02` factory · `03` admin-setup · `04` code · `05` runtime · `06` a11y · `07` sso (RUN_SSO) · `08` public (RUN_PUBLIC) | thực thi thật từng script |
| K browser | `BROWSER_BUILDER` `BROWSER_PORTALS` `BROWSER` | builder spec = component harness, **không** phải backend E2E |
| L recovery | `RECOVERY_R01…R08` (+ sub-key), `RECOVERY` | R09–R11 = `GAP` |
| cuối | `WORKTREE_SCOPE` `FINAL` | |

`RESULTS.txt`: mỗi dòng `KEY|STATUS|field|…|at=<UTC>`; STATUS ∈ `PASS FAIL NOT_RUN WARN INFO GAP`. Dòng đầu (`HEAD|<sha>`, `BRANCH|…`, `START|…`) không có STATUS.
`FINAL|PASS` chỉ khi: không FAIL **và** mọi phase critical đã chạy (E2E_07/08 và SECRET_SCAN là tuỳ chọn). Có phase critical NOT_RUN → `INCOMPLETE`; có FAIL → `FAIL`. **`FINAL|PASS` ≠ production ready**: các dòng `GAP_*` vẫn nằm trong file.
`STEPS.psv`: từng lệnh một dòng — `exit=`, `start=`, `end=`, `duration=`, `log=`.

## 6. Tác dụng phụ (để C0 biết trước)
- Tạo project `C6 recovery` và vài project smoke trong workspace dev local; E2E tạo thêm dữ liệu dev.
- **Recovery dừng rồi bật lại** container `postgres`, `redis`, `rabbitmq`, và dừng/khởi động lại API + UI (`scripts/stop-local.sh` + `run-local.sh`). Volume dữ liệu giữ nguyên.
- `RUN_SSO=1`: chạy `scripts/sso-up.sh` rồi restart API với `.run/sso.env`.
- Ghi `node_modules/` (nếu thiếu), `.env` (copy), `backend/build/`, `.next*`, `.test-build/`, `.run/` — tất cả đã gitignore. `esbuild` cài vào `/tmp/esb` (ngoài repo).
- Stack **để chạy** khi xong (dùng `--stop-stack` để dừng). Không ghi gì vào file tracked ngoài `docs/parallel/c6/**`.
- Ước lượng thời gian (không đo): backend `clean test` ≈ 6 phút (BOARD ghi 5m48–6m07 trên Mac), toàn bộ ≈ 30–60 phút tuỳ E2E.

## 7. Gửi gì về cho C6
**Cả thư mục** `docs/parallel/c6/evidence/mac/` (nén: `zip -r c6-mac-evidence.zip docs/parallel/c6/evidence/mac`). Tối thiểu:
`RESULTS.txt` · `STEPS.psv` · `ENV.txt` · `scope-start.txt` / `scope-end.txt` · `diff-vs-baseline.txt` · log của **mọi** key có `FAIL` hoặc `NOT_RUN` bất ngờ (đường dẫn nằm trong trường `log=`) · `backend-failures.txt` · `backend-skipped.txt` · `backend-test-report.tgz` · `security-suites.txt` · `stack-state.txt` · `recovery.psv`.
Nếu script chết giữa chừng: thêm toàn bộ terminal output và `RESULTS.txt` (có dòng `FINAL|INCOMPLETE|…aborted…`). Nếu `PREREQ|FAIL`: sửa theo `missing=` và chạy lại — không cần gửi gì.
Soát nhanh log trước khi gửi (log stack có thể chứa thông tin môi trường dev local); `.env` **không** được script đưa vào evidence.

## 8. Việc C6 làm sau khi nhận evidence
1. Đọc `RESULTS.txt`; chuyển các bảng `NOT RUN` trong `QA_STATUS.md`, `REGRESSION_MATRIX.md`, `E2E_MATRIX.md` thành PASS/FAIL theo evidence.
2. Mỗi `FAIL` → bug theo format (BUG ID, Severity, Owner, Environment, Commit SHA, Command, Expected, Actual, Reproduction, Logs, Regression) và route owner: C1 IAM/tenant/permission · C2 build/publish/rollback · C3 data/connector/query/mutation/credential/idempotency · C4 action/workflow/retry/timeout/compensation/RabbitMQ · C5 Studio/frontend/browser · C0 integration/migration/persistence/runtime wiring.
3. Revalidate 4 bug Low (BUG_C6_004 có sẵn trong `RESULTS.txt`); đóng bug nào đã sửa kèm evidence.
4. Nâng QA lên GREEN **chỉ khi** có evidence cho đủ điều kiện ở `QA_STATUS.md` (HEAD, backend, frontend, security, real E2E, browser, recovery, không Critical/High).
5. C0 ghi dòng record vào `BASELINE.md` mục "Re-baseline (macOS)" (xử lý BUG-C6-003) từ `RESULTS.txt`.

## 9. Ngoài phạm vi / GAP (không implement trong batch này)
V29 run stores · wiring RabbitMQ (`WorkflowQueue`) · management API cho data source/credential/query · connector production có ghi (hiện read-only). Hậu quả cho QA: `RECOVERY_R09` (workflow bị ngắt), `R10` (mutation timeout mơ hồ), `R11` (queue replay/DLQ) = `GAP`; E-06…E-09 trong `c5/PHASE3_E2E_PLAN.md` chưa thể viết; chuỗi login→…→runtime chưa có E2E thật. Owner: C0/C3/C4/C5 (xem `E2E_MATRIX.md`). GAP-C6-05/06 (QUERY_EXECUTE LIVE denial, WORKFLOW_EXECUTE qua HTTP) cần chạy để xác nhận.

## 10. Danh sách file runner
`harness/mac-full.sh` (1 lệnh, A–L) · `harness/mac-gate.sh` (A–F) · `harness/recovery.sh` (R01–R11) · `harness/lib.sh` (dùng chung, bash 3.2) · `harness/parse.py` `final.py` `tmo.py` (parser, luật FINAL, timeout — macOS không có `timeout`) · `harness/static-qa.mjs` · `harness/selftest.sh` (44 check logic). `_to_delete/` chứa `__pycache__` sinh nhầm lúc self-test, an toàn để xoá.
