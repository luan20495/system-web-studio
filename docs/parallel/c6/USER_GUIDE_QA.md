# USER GUIDE QA — `docs/user-guide/C5_STUDIO_USER_GUIDE.md`

QA độc lập (C6), 2026-10-07. Không sửa production code, không sửa guide. Ma trận đầy đủ 104 bước: [USER_GUIDE_QA_MATRIX.md](USER_GUIDE_QA_MATRIX.md) (cùng nội dung dạng TSV: `USER_GUIDE_QA.tsv`). Sinh bằng `harness/ug_matrix.py`; bằng chứng thô trong `evidence/user-guide-20261007/` (không có token/cookie/mật khẩu; mật khẩu chỉ đi qua biến trong script).

## 1. Phiên bản (bước 1)
| | |
|---|---|
| GUIDE_SHA | `db4d595` (`agent/c5-web`, 2026-10-07 15:25). Code UI mà guide mô tả là `7892bda` trở xuống |
| INTEGRATION_SHA | `1a9995c` (`integration/v2`, 2026-10-07 15:07) |
| `agent/c5-web` so với `integration/v2` | **33 commit chưa được merge** (cả `7892bda` và `808f98b` mà guide nhắc đều không nằm trong `integration/v2`) |
| Public build | `studio.toolsmcp.uk` → Next app `/Users/hoangluan/code/HBL` (.next build 2026-10-06 14:46) + jar backend 14:44 (khởi động 14:48), compose `hblpub`, Flyway = 30 |
| Chứng minh được build = 1a9995c? | **KHÔNG.** Không có SHA nhúng trong jar/Next và không có endpoint công khai trả SHA (`/actuator/*` do Next trả HTML, `/api/v1/version` cần login). Chỉ có suy luận gián tiếp: artifact mới hơn lần đổi code cuối (backend 13:29, frontend 2026-10-06 14:42); `1a9995c` chỉ đụng compose/infra/scripts/docs. |
| Kết luận môi trường | **ENVIRONMENT/DEPLOYMENT BLOCKED (UG-008)**: mọi kết quả dưới đây là của "public build đang chạy", không phải bằng chứng đã chứng minh cho `1a9995c`. |

## 2. Cách chạy
Playwright (Chrome headless) đi đúng thứ tự guide trên Studio công khai bằng `demo01` (tài khoản duy nhất loại này trên public: WORKSPACE_ADMIN, 27/27 membership là OWNER). Kiểm tra API qua phiên đã đăng nhập (`ctx.request`), có tải CSRF. Dữ liệu test (`C6-UG-QA-*`) đã được gỡ xuất bản và xóa; còn 0 project (`cleanup.log`). Slug test không còn phục vụ nội dung.

**Đính chính tự kiểm (không giấu):** 5 kết quả FAIL của lượt chạy đầu là lỗi harness của C6, đã chạy lại và sửa trong ma trận (`ug-run.json` giữ nguyên bản thô): UG-040 (so sánh chữ hiển thị trong khi `Công cụ` là aria-label), UG-051 và UG-106 (Playwright tự đóng `confirm()` gốc của trình duyệt), UG-060 (nhãn là aria-label/placeholder), UG-108 (đích rollback trùng bản đang chạy nên bỏ qua kiểm stale theo thiết kế).

## 3. Kết quả
**104 bước: PASS 57 · FAIL 14 · BLOCKED 33.** Các dòng có đánh dấu REAL (kể cả "partly REAL"): 68 — **verified 33, failed 11, blocked 24**.

FAIL (14): UG-006, 007, 019, 043, 080, 081, 084, 087, 090, 091, 128, 130, 135, 140.

## 4. Finding và route

### 4.1 Tài liệu (guide cần sửa — C5/C7)
| # | Finding | Bước |
|---|---|---|
| D-01 | "Trên instance công khai ai cũng đăng ký được (5/IP/giờ)" **sai**: `auth/config` có `signup=false`. Lặp lại ở §7.8 | UG-007, 128 |
| D-02 | Tài khoản `local.admin/editor/publisher/viewer` không tồn tại trên public (0/14 tài khoản; chỉ có WORKSPACE_ADMIN). §10 bước 1 bảo "đăng nhập bằng tài khoản editor" — làm không được | UG-006, 130 |
| D-03 | Editor mở ở **✦ AI**, không phải Design | UG-019 |
| D-04 | **MISSING STEP** (người dùng phải đoán): bộ chọn cổng "Bạn muốn vào" khi đăng nhập; nút bắt buộc **Lưu thay đổi** (guide đọc như tự lưu); lịch sử "Các lần xuất bản (N)" đang **thu gọn**, phải bấm mới thấy "Phục vụ lại bản này"; hai hộp `confirm()` gốc của trình duyệt (Khôi phục, Gỡ trang xuống); cách tạo action/workflow (§5) | UG-010, 020, 103, 051, 106, 091 |
| D-05 | Nhãn sai/không có: **Thử lại** (không có nút), banner Dùng thử (guide trích dẫn "Chế độ dùng thử — không lưu thay đổi vào dữ liệu thật" không có nguyên văn), **Thêm nguồn dữ liệu / Khe dữ liệu / Thêm khe / Liên kết khe dữ liệu / Dữ liệu công khai / + Thêm hành động / Thêm workflow** không xuất hiện; rail Dữ liệu có 6 bước chứ không phải 7; "Công cụ" chỉ là aria-label | UG-043, 090, 080, 081, 084, 085, 087, 091, 040 |
| D-06 | **Guide mô tả code chưa được merge.** Các nhãn trên chỉ có trong `agent/c5-web @ db4d595` (`DataSourcesPanel.tsx`, `PublicDataPanels.tsx`, nút thử lại trong `BuilderTopBar.tsx`, panel tiến trình AI `7892bda`). Dấu **[REAL]** ở §2.2 (S2/S6/S9), §4, §5 không tái hiện được trên build tích hợp. Guide phải ghi rõ build nào nó mô tả, hoặc hạ các mục này xuống "chưa có" cho tới khi merge | UG-043, 080–091, 135 |
| D-07 | §10 bước 6 (Chạy thử một action/workflow chuẩn bị sẵn) **không thực hiện được**: DEMO BLOCKER. Không đưa script này cho người dùng | UG-135 |
| D-08 | Nhỏ: §4 ghi "[REAL] cho nguồn dữ liệu" nhưng §7.1 nói không tạo được nguồn trên local; mặc định mô hình AI là "Tự động" (OpenRouter), không phải simulator như câu "otherwise"; dẫn `808f98b` không nằm trong integration | UG-063, 148 |

### 4.2 Sản phẩm / tích hợp (bước vẫn ghi FAIL/BLOCKED)
| # | Finding | Owner |
|---|---|---|
| P-01 | Build công khai không chứng minh được là `1a9995c` (không SHA nhúng/expose). Đề xuất: nhúng git SHA vào jar (build-info) và Next, expose ở endpoint công khai | **C0** (`BUG-C6-012`) |
| P-02 | UI của `agent/c5-web` (33 commit) chưa vào `integration/v2`. Backend **đã có** API (`GET /workspaces/{ws}/data-sources` → 200 `{items:[]}`, `…/app-runtime/actions|workflows|workflow-runs`, `…/data-bindings`) nhưng Studio hiện chỉ hiện "Chưa sẵn sàng… chưa được nối vào máy chủ": nguồn dữ liệu, action, workflow, Test Mode, nút Thử lại, banner bận, panel tiến trình AI đều **không dùng được từ UI** | **C0** (merge) + **C5** (`BUG-C6-011`); C3/C4 chỉ khi runtime thật sự lỗi — chưa có bằng chứng |
| P-03 | Public không có tài khoản EDITOR/PUBLISHER/VIEWER nên không thể kiểm quyền (bước 4 của yêu cầu). 7 bước §3 BLOCKED (ENVIRONMENT, không phải verdict sản phẩm) | **C0** cấp tài khoản vai trò cho QA public |
| P-04 | Giới hạn H-C1-04 (VIEWER bị chặn ở portal gate) vẫn là giới hạn đã biết, guide nêu đúng | **C1** (đã biết) |
| P-05 | Nhỏ: toast lỗi mạng bảo người dùng cuối "Kiểm tra backend rồi thử lại"; UI Studio không gửi `expectedActiveDeploymentId` (API có kiểm: 409 `ROLLBACK_STALE`); slug của project đã xóa trả 403 chứ không 404 (quan sát) | C5 / C5 / C2 |

Không có mismatch hợp đồng nào cần chuyển C1.

## 5. Vai trò (bước 4)
`local.*` **không tồn tại** trên public → WRONG GUIDE (D-01/D-02). EDITOR/PUBLISHER/VIEWER: **BLOCKED** (UG-070…076) — không có tài khoản, không tạo được (đăng ký tắt; tạo user cần system admin, C6 không dùng tài khoản vận hành). Chỉ kiểm được đường dương với OWNER: sửa/lưu/xuất bản/rollback/gỡ trang đều chạy. Cổng portal: `demo01` chọn **Admin Console** → `/auth/no-access` (đúng).

## 6. Xác minh API (không ghi secret)
| Việc | Method path | Kết quả |
|---|---|---|
| Đăng nhập (sai mật khẩu) | `POST /api/v1/auth/login` | 401 / 200; `GET /auth/csrf` 200; `GET /auth/me` 200 |
| Tạo / mở app | `POST /workspaces/{id}/projects` · `GET /projects/{id}` | 201 · 200 |
| Lưu | `PATCH /workspaces/{id}/projects/{id}/schema` | 200; phiên thứ hai cũ → **409** |
| Tải lại / phiên bản | `GET …/schema`, `GET …/versions` | 200; mỗi lần lưu thêm 1 phiên bản |
| Khôi phục | `POST …/versions/{id}/restore` | 200; phiên bản 5 → 7, không phiên bản nào bị mất |
| Dữ liệu | `GET /workspaces/{id}/data-sources` | 200 `{items:[]}` (API có; tạo nguồn không chạy: cần host DB DNS công khai, guide §7.1) |
| Workflow/action | `…/app-runtime/…` | route tồn tại (404 `PROJECT_NOT_FOUND` so với `NOT_FOUND` cho route giả); không chạy được từ UI |
| Xuất bản | `POST …/publish` (+`Idempotency-Key`) · `GET …/deployments` | 202 → RUNNING; 2 request song song cùng key → cùng deployment |
| Rollback | `POST …/site/rollback` | 200; `expectedActiveDeploymentId` sai → **409 ROLLBACK_STALE**, bản đang chạy không đổi |
| Gỡ trang xuống | `DELETE …/site` | 200 → trang công khai 404; rollback đưa trang trở lại 200 |
| Trang công khai | `GET sites…/<slug>/` (ẩn danh) | 200 có đúng nội dung vừa sửa |

Không quan sát được 409 `SCOPE_BUSY` (UG-107) và FAILED "đã cũ hơn" (UG-109): không tạo được cửa sổ tranh chấp xác định — BLOCKED, không phải lỗi sản phẩm.

## 7. Giới hạn đã biết (bước 7)
H-C1-04 viewer, query dữ liệu thật, mutation thật, dữ liệu công khai đã xuất bản, E2E-14 RabbitMQ, action thông báo, H-C2-07 (`public_data_approved` chưa được server cưỡng chế), AI thật end-to-end: **guide §7/§8 nêu đúng cả tám, không trình bày như đã verify** (UG-120…125). Mâu thuẫn còn lại nằm ở §4/§5: đánh dấu [REAL] cho những thứ mà build tích hợp không có (D-06).

## 8. Kết quả cuối

```
USER GUIDE QA
GUIDE SHA:            db4d595
INTEGRATION SHA:      1a9995c
PUBLIC VERSION VERIFIED: NO   (không chứng minh được; ENVIRONMENT/DEPLOYMENT BLOCKED — C0)

TOTAL STEPS: 104   PASS: 57   FAIL: 14   BLOCKED: 33
REAL CLAIMS (68 dòng có REAL): verified 33 · failed 11 · blocked 24

WRONG LABELS:        Thử lại; banner Dùng thử; Thêm nguồn dữ liệu; Khe dữ liệu / Thêm khe;
                     Liên kết khe dữ liệu; Dữ liệu công khai (tab + bước thứ 7); + Thêm hành động;
                     Thêm workflow; (nhỏ) "Công cụ" chỉ là aria-label            → 10 nhãn
WRONG URLS:          0 (5 địa chỉ công khai đều trả lời; /admin và /platform chuyển về cùng trang login)
WRONG PERMISSIONS:   0 xác nhận sai; 7 dòng vai trò BLOCKED (không có tài khoản). WRONG ACCOUNTS: 2 (local.*, đăng ký tự do)
MISSING STEPS:       6 (bộ chọn cổng; Lưu thay đổi; mở lịch sử; confirm Khôi phục; confirm Gỡ trang xuống; cách tạo action/workflow)
DOCUMENTATION FINDINGS: 8 (D-01…D-08)  →  C5/C7
PRODUCT FINDINGS:       5 (P-01…P-05)  →  C0 (P-01, P-02, P-03), C5 (P-02, P-05), C1 (P-04, đã biết), C2 (quan sát)

FINAL: GUIDE NEEDS FIX
READY TO GIVE USER: NO
```

Điều kiện để chuyển sang "GUIDE VERIFIED": (1) C0 chứng minh được build công khai (SHA) và merge UI C5 hoặc C5/C7 sửa guide theo build thật; (2) sửa D-01…D-07; (3) cấp tài khoản EDITOR/PUBLISHER/VIEWER cho QA public và chạy lại UG-070…076, 101, 143; (4) chạy lại `node harness/ug-run.mjs` (cần `.run/public/demo-accounts.txt`) rồi `python3 harness/ug_matrix.py`.
