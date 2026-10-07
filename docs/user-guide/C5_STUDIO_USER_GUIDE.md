# HBL-XWeb — Studio user guide (C5 input for C7)

Status: **C5 DRAFT, not QA-verified by C6.** Source of every label: the code of `agent/c5-web @ 7892bda` (Studio, Admin, shared UI). Every claim carries one of three marks:

- **[REAL]** — exercised by C5's real-backend suite (real browser → real Studio → real API), report in `docs/parallel/c5/evidence/mac/`.
- **[UI]** — exercised only by C5's browser harness / unit tests (no backend). Do not present as "works end to end".
- **[NOT VERIFIED]** — nothing C5 ran proves it. Not in the main flow.

C7 rule applied: nothing without a [REAL] mark is in sections 1–6; [UI] and blocked items are in 7 (limitations) and 8 (do not claim). C6 must turn [REAL] into QA GREEN before this goes to a user.

## 0. Where to open it
| What | Address | Status |
|---|---|---|
| Studio (apps) | `https://studio.toolsmcp.uk/studio` | answered HTTP 200 on 2026-10-07; whether it already runs the V1 build is **C0's to confirm** (C0 `integration/v2 @ 1a9995c`, D-C0-38 says yes; C5 did not run the browser gate there) |
| Admin console | `…/admin` | same host, same caveat |
| Platform | `…/platform` | same |
| API | **no separate host**: the browser calls `/api/v1/…` on the Studio origin (same-origin proxy) | per C0 `GLOBAL_INGRESS_AUDIT.md` |
| Published sites | `https://sites.toolsmcp.uk/<slug>/` | per `docs/PUBLIC_DEPLOYMENT.md` |
| Local (developer) | Studio `http://127.0.0.1:3003/studio` | local stack only |

Accounts: the local profile seeds `local.admin`, `local.editor`, `local.publisher`, `local.viewer` (C0). Passwords are generated per stack and live in that stack's env file (`.env` / `.run/public/public.env`, mode 600, git-ignored). **Never paste them into a document.** On the public instance anyone can sign up (limit 5 per IP per hour); sign-up gives an own workspace.

## 1. Quick start (10 minutes)  [REAL: E2E-01, 02, 13, P01]
1. Open the Studio address, log in with **Tên đăng nhập** / **Mật khẩu** → **Đăng nhập**. A wrong password shows "Sai tên đăng nhập hoặc mật khẩu.".
2. Sidebar **Ứng dụng** → **+ Tạo ứng dụng** → choose **Website** (card shows "Sẵn sàng") → keep **Trang mặc định** under "Bắt đầu từ" → type **Tên ứng dụng** → **Tạo website**. Other kinds say "Chưa bật" with the reason: leave them.
3. The editor opens in **Design**. Click a section in the canvas, edit its text in the right panel. The top bar shows **Đang lưu…** then **✓ Đã lưu hh:mm**. Reload the page: the edit is still there.
4. Click **Xuất bản** (top right) → choose **Công khai** → **Xuất bản**. The dialog shows the deployment steps and, at RUNNING, **Website đã lên: <link>**. Open the link: your page, served from `…/<slug>/`.

## 2. Full guide — apps and editing
### 2.1 Modes and top bar
Mode tabs: **Design**, **✦ AI**, **Code** (Code only for code apps; a website shows an explanation). Design top bar: **Chỉnh sửa | Dùng thử**, screen sizes, **Chia sẻ**, **Xuất bản** (a red/amber number on it = problems found before publishing). The older header (AI/Code modes) has **Website · Phiên bản · Tệp · Chia sẻ · ⚙ · Xuất bản**.
### 2.2 Design editing  [REAL: E2E-02, S2, S5, S9; drag and drop, tabs, ARIA: UI]
Left rail **Công cụ**: **Trang · Thành phần · Dữ liệu · Biểu mẫu · Hành động · Workflow · Giao diện · AI**. Select a section → inspector tabs **Nội dung · Thiết kế · Dữ liệu · Hành động · Quyền · Nâng cao**. Every change is saved as an immutable version on the server.
- Offline / server down: **Lưu thất bại** + **Thử lại**; retry sends the same edit once (E2E-S2, S6, S9).
- Someone else saved first: the page reloads the newer version and asks you to repeat the edit (409, E2E-S5).
- Double click never runs an action twice (E2E-S4).
### 2.3 Versions  [UI]
**Phiên bản** → **Lịch sử phiên bản**: **Khôi phục** creates a NEW version; old versions are never changed. (Restoring a draft is not a rollback of a published site, see §6.)
### 2.4 AI  [UI: progress; AI itself NOT VERIFIED end to end]
Mode **✦ AI**: describe the change in **Mô tả thay đổi**, **Gửi ↑**. While it runs you see the steps (send → wait for model → receive → check and save), elapsed time, the countdown to the automatic stop (120 s by default) and **Huỷ**. After 15 s of silence it says so and suggests cancelling or choosing another model. Real AI needs an administrator to add a provider and key (Admin → AI → **+ Thêm nhà cung cấp**); otherwise the app runs in **Chế độ thử nghiệm** (simulator, no data leaves). The free OpenRouter models can be slow or busy.

## 3. Roles and permissions  [partly REAL; read the limitation]
Rights come from the server's resolved permission list, never from a role name (UI is only a convenience: the server checks every call).
| Capability | Needs | UI effect |
|---|---|---|
| Open an app | `APP_VIEW` | otherwise "no access" page |
| Edit | `APP_EDIT` | without it the app is read-only with a notice |
| Share | `APP_SHARE` | **Chia sẻ** disabled with the reason |
| Publish, roll back, unpublish | `APP_PUBLISH` (editing never implies it) | **Xuất bản** and history actions disabled with the reason |
| Test a query / action / workflow | `APP_USE` + the matching execute right (+ `APP_EDIT` for test) | **Chạy thử** disabled and names what is missing |
| Manage data sources | `DATA_SOURCE_MANAGE` (view: `DATA_SOURCE_VIEW`) | panel read-only without it |
Project roles (C1): VIEWER = view + use · EDITOR = + edit, data view, query/action execute · PUBLISHER = + publish.
**Limitation H-C1-04:** `GET /auth/me` carries no project permissions, so a VIEWER-only account is refused at the portal gate and the read-only view cannot be shown to them (E2E-04/05 FAIL, contract mismatch owned by C1/C0). The API itself refuses correctly (403/404, forged operations rejected: [REAL] E2E-05 API half).

## 4. Data  [REAL for sources; the rest PARTIAL]
Rail **Dữ liệu**. Steps: **Nguồn dữ liệu · Khám phá cấu trúc · Truy vấn · Ánh xạ · ViewModel · Gắn vào thành phần · Dữ liệu công khai**.
- **Data sources** [REAL: E2E-06, 23 API + 9 UI checks]: **Thêm nguồn dữ liệu** (type, config, credential), **Kiểm tra kết nối**, replace credential. The credential is write-only: it is never shown again, only the key names. Admin role needed.
- **Slots** (logical names inside the app, "Khe dữ liệu") [REAL: E2E-PD02 on a build with C2's runtime]: **Thêm khe** (id lowercase, type such as `postgres`). A slot never holds a connection; an administrator binds it to a real source (**Liên kết khe dữ liệu**: TEST / LIVE).
- **Queries, mappings, ViewModels, bindings** — the editors exist [UI] and PD02 [REAL] stores a READ query and a binding; **running a query against real data is NOT verified** (§7).
- **Public data** (tab **Dữ liệu công khai**) [REAL: PD02, only the document side]: a READ query can be switched **Công khai**; a WRITE query cannot. Bind a text/list prop of Navbar, Hero, ProductGrid, TechnologySection, ComparisonBlock, Testimonials, ContactForm or Footer to a public query. Publishing lists what becomes public and asks you to tick the confirmation.

## 5. Workflows and actions  [REAL in Test mode]
Mode **Dùng thử** (top bar): banner "Chế độ dùng thử — không lưu thay đổi vào dữ liệu thật". Rail **Hành động** / **Workflow** define them (**+ Thêm hành động**, **Thêm workflow**; step kinds ACTION, WAIT, APPROVAL, BRANCH, END). **Chạy thử**: a TEST action reports "would run" and never sends anything (E2E-S1); a workflow start creates a run and shows its steps (E2E-11), a failed run is shown as failed, never as success (E2E-S8), runs survive a backend restart (E2E-12, needs the durable run store). A workflow run in Test mode can still create a run record.

## 6. Publish, roll back, unpublish  [REAL: E2E-P01…P09, 13]
Button **Xuất bản** opens "Xuất bản website".
- Choose **Riêng tư** (members only, sign in with a company account) or **Công khai** (anyone). **Xuất bản** → statuses shown live until a final one; success is only **RUNNING**; **FAILED** shows the server's reason.
- The history **Các lần xuất bản (N)** lists RUNNING releases; **Phục vụ lại bản này** re-serves an older release (rollback). The release you left becomes ROLLED_BACK and cannot be served again: publish that version again if needed.
- **Gỡ trang xuống** takes the site offline (history kept; a rollback brings it back).
- Another publish/rollback in progress: banner "đang xuất bản/đang hoàn tác", buttons locked; a conflicting request answers "bận" with a countdown and retry. If the active release changed under you, rollback says it is stale: reload the status and decide again.
- A publish that was overtaken by a newer release ends FAILED "Bản xuất bản này đã cũ hơn bản đang chạy"; publish again.
- A repeated click never creates a second operation (idempotency key per request).

## 7. Known limitations (PARTIAL / blocked) — keep out of demos
1. **Query on real data, mutation with a real side effect, TEST/LIVE binding results** — E2E-06 (query half), 07, 08, 09 BLOCKED (C3/C2/C0). On a local stack no source can be created: C3 accepts only public DNS hosts with verified TLS (`INVALID_CONFIG host is not an allowed database address`).
2. **Public data end to end** — PD02 passes (document side, page makes one anonymous request, ends in `error` because no public data route answers); **PD01 (rendered real rows) BLOCKED**: needs C0 to import C2's runtime into `integration/v2` and a source C3 accepts.
3. **VIEWER read-only** (H-C1-04).
4. **RabbitMQ outage recovery** (E2E-14) and notification actions (NOTIFY not wired) — BLOCKED/PARTIAL.
5. **The public-data acknowledgement is a local confirmation**; the server does not enforce it yet (H-C2-07).
6. Studio preview shows the authored text, never live data (by design).
7. Code apps / server apps are "Chưa bật" on the public instance.
8. Sign-up limit 5 per IP per hour; AI quota per user per day; the public instance works only while its host is on.

## 8. Do not claim
- "Data from a real database shows on the published page" — not demonstrated.
- "Viewers get a read-only Studio" — blocked by H-C1-04.
- "AI always answers" / "AI is fast" — free models are queued; real AI needs an admin key; AI itself was not verified end to end by C5.
- "Anyone on the Internet can use it 24/7" — one Mac, no HA.
- "Rollback of a draft = rollback of the site" — different things (§2.3 vs §6).
- "Public data is approved by the platform" — the confirmation is only in the dialog.
- Any Cloudflare/DNS/public URL behaviour not confirmed by C0/C6.

## 9. Troubleshooting
| You see | Meaning / do |
|---|---|
| "Lưu thất bại" + **Thử lại** | network or server problem; retry once; if it keeps failing reload |
| "Project vừa được thay đổi ở nơi khác…" | someone saved first; the page reloaded; repeat your edit |
| AI shows "Chưa có phản hồi nào từ model sau N giây" | the free model is busy; **Huỷ** and retry or pick another model; admin can add a provider |
| **Xuất bản** disabled | you lack `APP_PUBLISH`, or an operation is running; hover for the reason |
| "Công khai" not offered | the administrator disabled public publishing |
| Deployment FAILED | read the reason in the dialog; build errors name the binding or the component; fix and publish again |
| Page shows authored text, not data | the data route/binding is not available (§7.2) — expected today |
| 530 / site not found | the public host is down (the Mac or the tunnel); not an app error |
| Admin dialogs unreadable | fixed in `808f98b`; rebuild the Admin app |

## 10. Demo script for the boss (12 minutes, only [REAL] steps)
1. (1 min) Log in as the editor account; show **Ứng dụng**.
2. (2 min) **+ Tạo ứng dụng → Website → Tạo website**; the editor opens.
3. (3 min) Change the hero title; point at **Đã lưu**; reload; the change stays. Change a second thing and show **Phiên bản** (history).
4. (2 min) **Xuất bản → Công khai → Xuất bản**; wait for RUNNING; open the public link in a private window (no login).
5. (2 min) Edit again, publish again, then **Xuất bản → Phục vụ lại bản này** on the first release; reload the public page: the old content is back.
6. (1 min) Switch to **Dùng thử**; run **Chạy thử** on a workflow/action prepared in advance; point at "would run", no real effect.
7. (1 min) Close with §7: what is deliberately not shown (real data, viewer role).
Prepare beforehand: a workflow and an action in the demo app (create them in the rail before the audience arrives), a second browser profile for the anonymous check, confirm the public host answers.

## 11. Evidence index
`docs/parallel/c5/evidence/mac/`, `docs/C5_REAL_BACKEND_E2E_MATRIX.md` (per-flow status), `docs/parallel/c5/RELEASE_CONTRACT.md`, `PERMISSION_CONTRACT.md`, `HANDOFFS_PAGE_SCHEMA_DATA.md`.
