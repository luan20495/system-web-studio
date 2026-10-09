# S3 — User-facing text and terminology audit (PHASE 1: audit only, nothing changed)

| | |
|---|---|
| Auditor | C5-S3 · base `agent/c5-web` @ `9f858c2` · branch `agent/c5-s3-audit` · 2026-10-08 |
| Scope | all user-visible strings of the three portals: `features/admin/**`, `features/studio/**`, `features/library.tsx`, `packages/ui/**`, `packages/i18n`, `packages/auth/**` (login / auth pages), `packages/permissions` + `packages/api-client` (strings that reach the screen), `apps/*`; the legacy root app (`components/StudioShell.tsx`, `app/`) is counted separately and not audited line by line |
| Method | (1) every string literal / template literal / JSX text node in those files was extracted by script (comments stripped) and filtered to strings with Vietnamese diacritics or in JSX / `aria-label` / `title` / `placeholder` / `alt` — **3 678 occurrences, 2 689 unique** (counts per area in section C); (2) pattern scans (mojibake, NFC, ellipsis, quotes, dev names, env names, UPPER_SNAKE, internal vocabulary, ≈ 110 terminology probes with Unicode-aware word boundaries); (3) manual reading of the 60 files that own the copy. Evidence is code (`file:line`), not a running app. |
| Companion | `S3-design-system.md` (issue IDs `S3-nnn`; text issues S3-024 … S3-030, S3-032 point here as `T-nn`) |

## Summary of findings

| Check | Result |
|---|---|
| Unicode / mojibake | **clean**: 0 mojibake sequences, 0 non-NFC strings, 0 U+FFFD (the one scan hit, `ĐÃ LƯU`, is a false positive) |
| Ellipsis | **consistent**: 115 `…` (U+2026), 0 `...` |
| Quotes | curly `“ ”` everywhere except **2 straight-quote strings** (`AdminApp.tsx:404,405`) and **1 nested curly quote** (`CodeWorkspace.tsx:155`) |
| Vietnamese spelling / vocabulary | no spelling errors found among the 79 single-use words and ≈ 110 probed terms; **tone-mark placement is mixed** (old style `xoá/huỷ/tuỳ/khoá` ×47 vs new style `xóa/hủy/tùy/khóa` ×212) |
| Raw codes / enum values shown | ≈ 40 sites (T-02) |
| Config / env / flag / internal names shown | ≈ 45 strings (T-04) |
| Hard-coded English mixed in | ≈ 35 labels (T-06) |
| Terminology inconsistent | 16 concept families (T-05) |
| Stale / contradictory copy | 6 cases (T-07) |
| Developer team names (C0–C7, H-C…, D-C…, ADR, commit / branch ids) | **none** left in user-visible strings (previous UI-19 fix held) |

IDs used below: **T-01** punctuation / glyphs / quotes / tone marks · **T-02** raw codes and enums · **T-03** role labels · **T-04** config / env / internal vocabulary · **T-05** terminology families · **T-06** English in Vietnamese UI · **T-07** stale / contradictory copy · **T-08** brand and portal names · **T-09** CTAs and destructive wording · **T-10** error-message leakage.

---

## A. FINDINGS

### T-01. Punctuation, glyphs, quotes, tone marks

| Finding | Evidence |
|---|---|
| **Tone-mark style is mixed** (both are valid Vietnamese; the product must pick one). The majority is the modern style (`xóa` 106, `hủy` 35, `khóa` 69, `tùy` 2) → standardise on it. 47 strings to change, full list in section B.3. | `xoá` ×27, `huỷ` ×11, `tuỳ` ×7, `khoá` ×2 |
| Straight double quotes in confirm texts, curly quotes elsewhere | `AdminApp.tsx:404` `Lưu trữ "${a.name}"? …`, `:405` `Xóa ứng dụng "${a.name}"? (xóa mềm, có ghi nhật ký)` → `“…”` |
| Nested identical quotes | `CodeWorkspace.tsx:155` `…như “đổi tiêu đề thành “…”” hoặc “đổi màu nền vàng”.` → `‘…’` for the inner pair |
| Arrow / glyph characters inside labels (read aloud as "mũi tên lên", clipped by some fonts) | `Gửi ↑` (`ProjectWorkspace.tsx:351`, `CodeWorkspace.tsx:173`, `components/StudioShell.tsx:188`), `↑ Lên` / `↓ Xuống` (`Inspector.tsx:46-47`), drag handle `⋮⋮` (`Canvas.tsx:65`, `ComponentsPanel.tsx:49`), `•` dirty marker (`CodeWorkspace.tsx:179`), `⋯` (`CodeWorkspace.tsx:40`) → Lucide icon + text, `aria-hidden` |
| `→` used as a path separator in 24 sentences ("Cài đặt → Build / Lưu trữ / Lưu giữ", "Studio → Templates") | acceptable in prose; prefer `>` or a breadcrumb for navigation paths and keep arrows for flows |
| Placeholder / ellipsis | consistent (`Tìm…`, `Đang tải…`); no `...` |
| Number / date / currency | `vi-VN` via `Intl` (`ui.tsx:9`, `:16`, `:20`); **`usd()` hard-codes `en-US`** (`ui.tsx:18`) so `$1,234.5` appears beside `1.234,5` in the same table; the relative time `ago()` is hard-coded Vietnamese (`ui.tsx:10-15`) |

### T-02. Raw codes, enum values and ids shown to users

Current → proposed (labels live in a map; unknown values should fall back to a neutral "Khác" and be logged, never printed raw).

| file:line | shows now | proposed |
|---|---|---|
| `AdminApp.tsx:170` | audit action label **plus** the code `LOGIN_SUCCESS` in a `small.code` | label only; code in `title` (or a copy button) |
| `AdminApp.tsx:171` | `{r.resourceType}` (e.g. `PROJECT`) as link text | map: Ứng dụng / Người dùng / Workspace / Mẫu / Khối / … |
| `AdminApp.tsx:721` | audit filter `Đăng nhập (LOGIN_SUCCESS)` (code in the option text) | label only |
| `AdminApp.tsx:316,348,427` | member / project role `EDITOR`, `WORKSPACE_ADMIN` | `roleName()` (T-03) |
| `AdminApp.tsx:428` | version `kind` raw | label map (Chỉnh sửa / AI / Khôi phục / Mẫu…) |
| `AdminApp.tsx:431` | deployment `visibility` raw (`PRIVATE`/`PUBLIC`) | Riêng tư / Công khai |
| `AdminApp.tsx:430,572` | `<Pill value={p.outcome}/>` without label → `UPDATED` / `REJECTED` / `BAD_OUTPUT` | `OUTCOME_LABEL` (already defined at `:438`) |
| `AdminApp.tsx:598` | component `status` raw when not `ACTIVE` | Đã duyệt / Ngừng dùng / Chờ duyệt |
| `AdminApp.tsx:784` | build rejection `reason` code | label map |
| `AdminApp.tsx:861` | settings group card title = raw key (`authentication`, `limits`…) | `SETTING_GROUP[g]` (exists at `:752` but only used for the read-only block) |
| `AdminApp.tsx:863` | `<small class="code">{s.key}</small>` under every policy | hide behind "Chi tiết kỹ thuật" |
| `AdminApp.tsx:875` | read-only config rendered as `<dt>{k}</dt>` (raw property names) and `String(v)` | label map; hide values the operator cannot act on |
| `AdminApp.tsx:907,1128` | role `<option>` shows `WORKSPACE_ADMIN`, `EDITOR`, `PUBLISHER`, `VIEWER` | `roleName()` |
| `AdminApp.tsx:958` | model-access `m.reason` (server text) | label map |
| `AdminApp.tsx:997,1005,1015,1018` | `CRITICAL`, `WARNING`, `HIGH`, `MEDIUM`, `LOW`, `INFO` (English) | Nghiêm trọng / Cảnh báo / Cao / Trung bình / Thấp / Thông tin |
| `AdminApp.tsx:1186` | `Môi trường: ${e.environment}` raw | label map (Sản xuất / Thử nghiệm …) |
| `AdminApp.tsx:1191` | backup component `state` raw (`SKIPPED`, `OK`) | label map |
| `AdminApp.tsx:1193` | restore-drill `d.result` (`PASS`, `SKIPPED`, `FAIL`) as the pill text | Đạt / Bỏ qua / Không đạt |
| `StudioApp.tsx:340` | component `status` raw when not `ACTIVE` | as above |
| `StudioApp.tsx:341` | `<small class="code">{c.id} · v{…} · {c.category}</small>` | category label; id only in `title` |
| `StudioApp.tsx:271` | template check failures `{c.check}: {c.message}` (check code) | `CHECK_LABEL` (exists in `library.tsx:33`) |
| `library.tsx:38` | `CHECK_LABEL[c.check] ?? c.check` fallback prints the raw code | neutral fallback |
| `ProjectWorkspace.tsx:97,166` | chat chip `UPDATED` / `REJECTED` / `NO_CHANGE` | `OUTCOME_LABEL` |
| `ProjectWorkspace.tsx:381` | version `kind` raw | label map |
| `CodeWorkspace.tsx:96` | error toast `${e.message} (${e.code})` | message only; code in `title` |
| `CodeWorkspace.tsx:228` | `change.build.status` raw | label map (`STATUS` exists at `:18` but not used here) |
| `TestPanel.tsx:190` | `Lượt chạy ${run.runId} · ${run.status} · ${stepId}:${status}…` | status labels; hide ids |
| `TestPanel.tsx:210` | `bộ nhớ đệm: ${res.cache} · chế độ ${res.mode}` (`HIT`, `TEST`) | Có / Không; "Thử" |
| `PublicDataPanels.tsx:244` | runtime state codes `(LOADING_DATA)` next to every label | drop the code |
| `ActionEditor.tsx:112,121` | `NOTIFY_CHANNELS` and `IDEMPOTENCY_POLICIES` values printed as options | label maps |
| `ActionEditor.tsx:92`, `DataWizard.tsx:120` | param type `STRING`, `NUMBER`… | Chuỗi / Số / … |
| `DataWizard.tsx:203,205` | `READ · products.list`, `LIST · a, b` | Đọc / Ghi; Danh sách / Một bản ghi |
| `PropsForm.tsx:44` | enum prop options printed raw (`left`, `center`, `primary`) | per-prop label map from the registry |
| `WorkflowEditor.tsx:106` | approver kind `USER`, `GROUP`, `ROLE`, `DEPARTMENT_MANAGER` | Người dùng / Nhóm / Vai trò / Trưởng phòng ban |
| `WorkflowEditor.tsx:54,74,79,116-117,141,147` | step ids (`s-3f9a`) as option labels and card subtitles | "Bước 3 · Phê duyệt" |
| `Inspector.tsx:37`, `MiscPanels.tsx:23`, `DataWizard.tsx:187`, `PublicDataPanels.tsx:215`, `ActionEditor.tsx:72`, `PagesPanel.tsx:120` | raw section ids (`hero-ab12c`) as subtitles / option suffixes | position + title ("Đầu trang #1") |
| `EmployeesScreens.tsx:157` | workspace member `{w.role}` raw | `roleName()` |
| `DataSourcesPanel.tsx:147` | `desc?.displayName ?? d.type` (raw `postgres`); `:156` `Mã: {t.code}`; `:182-183` field labels are config key names (`host`, `database`, `password`) | connector-provided display names; label map with the key as hint |

### T-03. Role labels — one role, up to three names

| Role code | `adminModel.ts` (`WORKSPACE_ROLES`, `:138`) | `UserDialogs.tsx:7-10` | `provisioningModel.ts:15-16` | `drawers.tsx:101-103` | `packages/i18n` `ROLE_LABEL` (**unused**) |
|---|---|---|---|---|---|
| `WORKSPACE_ADMIN` | Quản trị không gian làm việc | Quản trị không gian làm việc | Quản trị không gian làm việc | Quản trị workspace | Quản trị workspace |
| `EDITOR` | Biên tập viên | Biên tập viên | — | **Biên tập** | **Người chỉnh sửa** |
| `PUBLISHER` | Người xuất bản | Người xuất bản | — | **Xuất bản** | Người xuất bản |
| `VIEWER` | Người xem | Người xem | — | **Chỉ xem** | Người xem |
| `OWNER` | — | — | — | Chủ sở hữu | Chủ sở hữu |

Also `packages/i18n` exports `roleName`, `PORTAL_TEXT` and the audit `ACTION_LABEL`; `roleName` and `PORTAL_TEXT` have **no importer**, and `ACTION_LABEL` clashes by name with the builder's `core/actions.ts ACTION_LABEL` (action *types*). Proposal: one `ROLE_LABEL` in `@xweb/i18n` (Quản trị workspace · Người chỉnh sửa · Người xuất bản · Người xem · Chủ sở hữu · Quản trị công ty · Thành viên · Quản trị hệ thống), delete the four copies.

### T-04. Config / env / flag names and internal vocabulary

| file:line | current | proposed |
|---|---|---|
| `AdminApp.tsx:543` | `Chưa có OPENROUTER_API_KEY` | `Chưa có khóa kết nối OpenRouter` (and `Đã cấu hình key` → `Đã có khóa`) |
| `AdminApp.tsx:1117` | `OIDC_ENABLED · đăng xuất cũng kết thúc phiên ở IdP` | `Đăng xuất cũng kết thúc phiên ở nhà cung cấp danh tính.` |
| `AdminApp.tsx:1118` | `SAML_ENABLED + SAML_IDP_HINT` | drop the variable names |
| `AdminApp.tsx:1119` | `… · SCIM_ENABLED + SCIM_TOKEN` | `… tài khoản do SCIM cấp` |
| `AdminApp.tsx:1184` | `Lịch: hằng ngày (scripts/backup-daemon.sh), …` | `Sao lưu chạy hằng ngày; diễn tập khôi phục hằng tuần…` |
| `AdminApp.tsx:1185` | `Chưa cấu hình thư mục sao lưu (BACKUP_STATUS_DIRS)` | `Chưa cấu hình thư mục trạng thái sao lưu.` |
| `AdminApp.tsx:580` | `Component Registry` / `Registry` | `Thư viện thành phần` / `Thư viện` (T-06) |
| `AdminApp.tsx:666,StudioApp.tsx:284` | `…cấu trúc trang (Page Schema)…` | `…cấu trúc trang…` |
| `AdminApp.tsx:773` | `Số liệu đo thật từ runner (CPU của container, …)` | `…từ máy build (CPU, thời gian, kích thước kết quả).` |
| `AdminApp.tsx:809,820` | `giải phụ thuộc trong sandbox + quét OSV`, `mirror và lockfile` | `kiểm tra phụ thuộc và lỗ hổng bảo mật` |
| `AdminApp.tsx:1178` | `CSDL nền tảng (PostgreSQL)`, `Tệp & artifact (MinIO)`, `Kho mã (Forgejo)` | operators only; keep product names in parentheses, translate the nouns (`Tệp & kết quả build`) |
| `ProvisioningScreens.tsx:69,112,155`, `provisioningModel.ts:82` | `Backend provisioning chưa sẵn sàng: …` | `Máy chủ chưa hỗ trợ tạo tài khoản.` |
| `EmployeesScreens.tsx:165` | `…(API hiện có).` | delete |
| `OrganizationScreens.tsx:81` | `Giao diện đã sẵn sàng: …; không có dữ liệu nào được tạo giả.` | `Tính năng này sẽ mở khi máy chủ hỗ trợ cơ cấu tổ chức.` |
| `AdminApp.tsx:125` | `Màn hình này sẽ hiển thị dữ liệu thật ngay khi phần máy chủ có mặt; hiện tại không có dữ liệu giả.` | `Mục này sẽ mở khi máy chủ hỗ trợ.` |
| `drawers.tsx:53` | `Lưu vào backend; xung đột phiên bản sẽ được báo.` | `Thay đổi được lưu ngay; nếu người khác vừa sửa, bạn sẽ được báo.` |
| `drawers.tsx:87` | `PNG, JPEG, WebP, GIF hoặc PDF. Lưu trong MinIO qua URL ký sẵn.` | `PNG, JPEG, WebP, GIF hoặc PDF.` |
| `ProjectWorkspace.tsx:309-311` | `Code Mode cần kiến trúc sinh mã nguồn … Page Schema (JSON) … Company Component Registry … repository mã nguồn … Git` | `Chế độ mã nguồn chưa có. Ứng dụng này được tạo từ các thành phần đã duyệt của công ty nên chưa có tệp mã để hiển thị.` |
| `ProjectWorkspace.tsx:286`, `BuilderWorkspace.tsx:164`, `CodeWorkspace.tsx:135`, `AdminApp.tsx:368,410` | `revision {n}` / `r{n}` | remove (internal concurrency counter) or `lần lưu {n}` |
| `ProjectWorkspace.tsx:321`, `aiProgressModel.ts:25,46` | `…kiểm tra theo registry…` | `…kiểm tra theo thư viện thành phần…` |
| `ReleaseModal.tsx:164,212,221,223`, `release.ts:126` | `(APP_PUBLISH)` | `Cần quyền xuất bản.` |
| `ReleaseModal.tsx:172` | `pointerVersion {n} (chỉ để quan sát, không gửi lại máy chủ)` | delete (debug output) |
| `ReleaseModal.tsx:180`, `publicData.ts:279,280,311` | `…địa chỉ dữ liệu (apiBase)…` | `…địa chỉ dữ liệu…` |
| `ReleaseModal.tsx:201`, `AdminApp.tsx:431` | `Demo deployment` | `Triển khai mô phỏng` |
| `PublicDataPanels.tsx:243` | `…(thuộc tính data-xw-state)…` | `Trang đã xuất bản tự báo trạng thái dữ liệu theo các bước sau:` |
| `DataSourcesPanel.tsx:213` | `…chưa khai báo khe dữ liệu (dataSources[])…` | `Ứng dụng chưa có khe dữ liệu nào…` |
| `dataManagement.ts:40` | `…(app.data-platform.enabled đang tắt hoặc chưa được nối).` | `Máy chủ chưa bật quản lý nguồn dữ liệu.` |
| `readiness.ts:39` | `…(ADD_QUERY, ADD_ACTION…): máy chủ chưa hỗ trợ định nghĩa ứng dụng phiên bản 2.` | `Máy chủ chưa hỗ trợ dữ liệu và hành động trong trình dựng.` |
| `readiness.ts:58` | `…chưa có DataGateway… (DATA_RUNTIME_UNAVAILABLE).` | `Máy chủ chưa có dịch vụ dữ liệu cho môi trường này.` |
| `actions.ts:74` | `…“Làm mới dữ liệu” (REFRESH_QUERY)…` | drop the code |
| `TestPanel.tsx:184`, `testMode.ts:42,62` | `(workflow_run)` | `bản ghi lượt chạy` |
| `Inspector.tsx:180`, `backend.ts:22`, `inspector.ts:56` | `component-metadata` | `thông tin thành phần` |
| `CodePanels.tsx:70,99,106,123`, `CodeWorkspace.tsx:100,152,200,222,244` | `Lockfile`, `sandbox`, `runner`, `mirror package`, `commit`, `main`, `Git`, `container`, `chuyển lưu lượng` | plain words: `bản build thử`, `máy build`, `kho thư viện của công ty`, `thay đổi`, `bản chính` |
| `StudioApp.tsx:185,188,217,218` | `(cần kho Git và máy build)`, `build trong sandbox`, `container cô lập (không root, không Internet) … PostgreSQL … openapi.json` | one-sentence benefits; details in docs |
| `packages/api-client/src/core.ts:63,92` | `Không kết nối được tới máy chủ. Kiểm tra backend rồi thử lại.` | `…Kiểm tra kết nối mạng rồi thử lại.` |
| `core.ts:16,18` | `Không lấy được CSRF token (${r.status}).`, `API không trả CSRF token.` | `Phiên làm việc chưa hợp lệ. Hãy tải lại trang.` |
| `runtimeConfig.ts:111-116` | `Cấu hình chạy thiếu DATA_API_BASE_URL … localhost trong môi trường production …` | shown to **site visitors**: `Trang này chưa được cấu hình đúng. Liên hệ người vận hành.`; keep the variable name in the console only |
| `components/StudioShell.tsx:80,155,290` (legacy) | `…chưa lưu vào backend`, `Demo`… | legacy app only |

No team / ticket names (C0–C7, H-C0-xx, D-C…, UX-nnn), branch or commit ids remain in user-visible strings.

### T-05. Terminology families (canonical list in section B.1)

| Family | Variants and counts (occurrences in UI strings) | Worst places |
|---|---|---|
| workspace | `Workspace` 105 · `không gian làm việc` 14 · `Quản trị không gian làm việc` ×5 | same page mixes both: `AiSetup.tsx:281,359` vs `AdminApp.tsx:567` ; `DataSourcesPanel.tsx:140,173,219` vs the rest of Admin |
| ứng dụng / project | `ứng dụng` 171 · `project` 24 · `dự án` 4 | Studio drawers: `Cài đặt project`, `Tên project`, `Thành viên project`, `Tệp của project`, `Xóa … khỏi project?` (`drawers.tsx:53,55,87,163,165,169`, `ProjectWorkspace.tsx:133,279,298`); `Project ID` (`AdminApp.tsx:722`); `Dự án yêu cầu duyệt` (`CodeWorkspace.tsx:218`); `tệp của dự án` (`library.tsx:34`); `Chủ cũ ở lại dự án` (`AdminApp.tsx:422`) |
| công ty / tenant | `công ty` 159 · `Công ty (tenant)` ×2 (`AdminApp.tsx:38`, `TenantScreens.tsx:144`) | drop "(tenant)" |
| thành phần / component | `thành phần` 46 (builder) · `component` 35 (admin, Studio, builder hints) | nav `Components`, `Company Components` |
| mẫu / template | `mẫu` 59 · `template` (nav `Templates` ×3, `ProjectWorkspace.tsx:168`, `libraryPanels.tsx:20,50`) | |
| khối / block | `khối` 49 (consistent) | |
| mô hình / model | `mô hình` 39 (`AiSetup`) · `model` 44 (AdminApp AI pages, Studio composer, `aiProgressModel.ts`) | `AiSetup` tab `Mô hình` vs `AdminApp.tsx:475,519,527,564,572` columns `Model`, `Mọi model`, `Theo model` on the sibling tab |
| xóa / gỡ / bỏ | `Xóa` 106 · `Xoá` 27 · `Gỡ` 42 · `Bỏ …` · `Thu hồi` | member removal: `Gỡ … khỏi workspace` (`TenantScreens.tsx:285`) vs `Xóa … khỏi workspace` (`drawers.tsx:169`) vs `Rời/Xóa` (`drawers.tsx:124`) |
| hủy / đóng / xong | `Hủy` 35 · `Huỷ` 11 · `Đóng` 23 · `Xong` 11 | `Huỷ` for cancelling an AI request (`AiProgress.tsx:18`) |
| khóa (lock / key) | `khóa` = lock (`Khóa tài khoản`, `Bị khóa`) **and** key (`Khóa kết nối`, `Khóa chống ghi trùng`) | `Khóa chống ghi trùng` (`errors.ts:60`, `release.ts`) → `Mã chống ghi trùng` |
| disable / lock account | `Khóa tài khoản` (admin) · `Tạm khóa` (company) · `Bị khóa` · `Vô hiệu hóa` (`AuthPages.tsx:13,143`) · `Tắt tài khoản` (`EmployeesScreens.tsx:164`) · `Đã tắt` | one verb per object (T-09) |
| test / simulated | `Dùng thử` (builder test mode) · `Chạy thử` · `Chế độ thử nghiệm` (AI simulator, 12×) · `Mô phỏng` · `Demo` · `Mock` | `Chế độ dùng thử` (builder) and `Chế độ thử nghiệm` (AI) read as the same thing but are different features |
| rollback / restore | `Hoàn tác` (release rollback, draft revert, workflow compensation) · `Khôi phục` (version, archived app, block, template) · `Phục vụ lại bản này` (`ReleaseModal.tsx:213`) | three words for one release action |
| publish / release | `Xuất bản` 96 · `phát hành` 9 (`thao tác phát hành`, `bản phát hành`) | |
| khe / slot | `khe dữ liệu` 18 · `khe` 39 · `slot` 3 | unusual word with no definition on first use; product decision needed |
| key | `Khóa kết nối` 22 · `API key` 3 · `khóa` · `key` 5 | `AiSetup.tsx:132` heading `API key` over the field `Khóa kết nối` |

### T-06. Hard-coded English in the Vietnamese UI

Proposed glossary of English terms that may stay: **AI, API, SSO, OIDC, SAML, SCIM, MFA, URL, DNS, HTTPS, CSV, SEO, ID, Workspace, Workflow, Connector, Token, Build, Git**. Everything else below should be translated.

| file:line | current | proposed |
|---|---|---|
| `AdminApp.tsx:25` | nav `Components`, `Connector`(keep), `Build & lưu trữ`(keep) | `Thành phần` |
| `AdminApp.tsx:26,666` | nav / title `Templates` | `Mẫu trang` |
| `AdminApp.tsx:26,820` | nav / title `Packages` | `Gói thư viện` |
| `AdminApp.tsx:580,582` | `Component Registry`, tab `Registry` | `Thư viện thành phần`, `Thư viện` |
| `AdminApp.tsx:494,941` | `AI Control` | `Quản lý AI` |
| `AdminApp.tsx:940` | `Quyền dùng model (Model Access)` | `Quyền dùng mô hình` |
| `AdminApp.tsx:722-724` | `Project ID`, `Workspace ID`, `Request ID` (label + placeholder) | `Mã ứng dụng`, `Mã workspace`, `Mã yêu cầu` |
| `AdminApp.tsx:1155` | `Base URL` | `Địa chỉ gốc (Base URL)` |
| `AdminApp.tsx:312,1120` | hint `MFA managed by Identity Provider` (a whole English sentence) | `Xác thực nhiều lớp do nhà cung cấp danh tính quản lý.` |
| `AdminApp.tsx:431`, `ReleaseModal.tsx:201` | `Demo deployment` | `Triển khai mô phỏng` |
| `AdminApp.tsx:153,215`, `AuthPages.tsx:98,147` | `Mở Builder Studio`, `Builder Studio`, `Vào Builder Studio` | `Mở ${PORTAL_LABEL.studio}` |
| `StudioApp.tsx:84`, `AuthPages.tsx:97,144,164` | `Admin Console` | `${PORTAL_LABEL.admin}` |
| `StudioApp.tsx:26,62` | nav `Templates`, `Components` | `Mẫu`, `Thành phần` |
| `StudioApp.tsx:65` | brand `Company Builder Studio` | `Xweb Studio` (T-08) |
| `StudioApp.tsx:284` | `Templates` (h1) | `Mẫu` |
| `StudioApp.tsx:334` | `Company Components` | `Thành phần của công ty` |
| `libraryPanels.tsx:20,50` | `Xem ở Studio → Templates`, `…→ Components` | `…Studio → Mẫu`, `…→ Thành phần` |
| `ProjectWorkspace.tsx:261`, `CodeWorkspace.tsx:139` | mode tabs `Design`, `Code` | `Thiết kế`, `Mã nguồn` (`AI` stays) |
| `drawers.tsx:64` | `Mock (cục bộ)`, `Self-host`, `Cloud` | `Giả lập (cục bộ)`, `Tự lưu trữ`, `Đám mây` |
| `AiSetup.tsx:14` | `AI nội bộ (Local)` | `AI nội bộ` |
| `AiSetup.tsx:132` | section `API key` | `Khóa kết nối` |
| `ProjectWorkspace.tsx:168` | `…${n} template` | `…${n} mẫu` |
| `AdminApp.tsx:1006` | `Phụ thuộc (OSV)` | `Phụ thuộc` (OSV in the tooltip) |

### T-07. Stale or contradictory copy

| file:line | problem | proposed |
|---|---|---|
| `AdminApp.tsx:424` vs `:403-404` | card **"Chưa triển khai — Lưu trữ (archive), chặn xuất bản công khai, chi phí, điểm bảo mật"** sits beside a working **Lưu trữ / Khôi phục** button | remove "Lưu trữ (archive)" from the list |
| `StudioApp.tsx:128` vs `:186-192` | Home: "Hiện hỗ trợ loại ứng dụng: Website (một trang)"; the New-app page offers multi-page websites and 5 other kinds | "Bắt đầu bằng mô tả; bạn chọn loại ứng dụng ở bước sau." |
| `AuthPages.tsx:116` (`minLength={6}`) vs `:54` (`minLength={8}`) | **password policy 6 vs 8 characters** (sign-up vs activation) | one rule, from the server's policy |
| `AdminApp.tsx:193,359` | `đã xuất bản (demo)` / small `demo` next to every publish pill | explain ("Triển khai mô phỏng, chưa có website thật") or drop |
| `AdminApp.tsx:312` vs `:1120` | same MFA sentence once in Vietnamese, once in English (T-06) | single Vietnamese string |
| `ProjectWorkspace.tsx:303` + `CodeWorkspace`, `StudioApp` | "website đang ngoại tuyến" for an archived app, but `AdminApp.tsx:401` says "chỉ xem, ngoại tuyến" (different wording for one state) | "Đã lưu trữ: chỉ xem, website đã gỡ khỏi mạng." everywhere |

### T-08. Brand and portal names

| Where | Name shown |
|---|---|
| Admin / Platform sidebar brand (`AdminApp.tsx:139`), auth frame (`AuthPages.tsx:21`), root layout title (`app/layout.tsx`) | **AI Software Factory** (root description is English: "AI-first web studio prototype") |
| Studio sidebar (`StudioApp.tsx:65`) | **Company Builder Studio** over `AI Software Factory` |
| `PORTAL_LABEL` (`packages/permissions/src/index.ts:23`) | `Xweb Platform` · **`Quản trị công ty`** · `Xweb Studio` |
| `apps/*/layout.tsx` metadata | `Xweb Platform`/`Xweb Admin`/`Xweb Studio` (`Xweb Admin` ≠ `Quản trị công ty`) |
| `CONSOLE_NAME` (`AdminApp.tsx:51`) | `Admin Console` (legacy), `Xweb Platform`, `Quản trị công ty` |
| i18n `PORTAL_TEXT` (unused) | `Xweb Platform / Quản trị công ty / Xweb Studio` with subtitles |
| Login picker (`AuthPages.tsx:97-98`) | `Admin Console`, `Builder Studio` |

Proposal: product **Xweb**; portals **Xweb Platform**, **Xweb Admin — Quản trị công ty** (short: *Quản trị công ty*), **Xweb Studio**; the sidebar brand shows the portal name, not a fourth name; `PORTAL_TEXT` becomes the single source.

### T-09. CTAs and destructive wording

| file:line | problem | proposed |
|---|---|---|
| `AdminApp.tsx:786,769` | **"Chạy dọn dẹp ngay"** deletes artifacts and expired previews permanently with **no confirmation** (the preview text is on the card, but one click executes) | confirm dialog stating counts and size (S3-050) |
| `DataSourcesPanel.tsx:165`, `CodePanels.tsx:139` | **"Xóa khóa kết nối"** / **"Xoá" (secret)** are irreversible (write-only values) with **no confirmation** | confirm dialog: "Giá trị không thể xem lại; bạn sẽ phải nhập lại." |
| `TenantScreens.tsx:130` / `adminModel.ts:97,100,101` | company status button reads just **"Xóa"** / "Khôi phục" at page level | `Xóa công ty` / `Khôi phục công ty` |
| `AdminApp.tsx:405` | `Xóa ứng dụng "x"? (xóa mềm, có ghi nhật ký)` — "xóa mềm" is developer jargon | `Ứng dụng sẽ bị ẩn khỏi mọi người dùng; quản trị hệ thống vẫn khôi phục được.` |
| `AdminApp.tsx:403,429,651,691`, `ProjectWorkspace.tsx:304`, `drawers` | bare `Khôi phục` for four different objects | `Khôi phục ứng dụng` / `Khôi phục phiên bản` / … |
| `CodeWorkspace.tsx:190`, `CodePanels.tsx:53` | `Tạo thay đổi & build` | `Gửi thay đổi để kiểm tra` |
| `CodeWorkspace.tsx:214` | `Hợp nhất vào main` | `Hợp nhất vào bản chính` |
| `ReleaseModal.tsx:213` | `Phục vụ lại bản này` | `Quay về bản này` (and `Đã phục vụ lại phiên bản n` → `Đã quay về phiên bản n`) |
| `AdminApp.tsx:621` vs `:684` | `Xem xét` (block) vs `Xem` (template) for the same "open the review row" | `Xem xét` for both |
| `PropsForm.tsx:77` | `Hoàn tác` = discard unsaved edits | `Bỏ thay đổi` (reserve `Hoàn tác` for undo of a saved operation) |
| retry family | `Thử lại` 59 · `Tải lại` 25 · `Kiểm tra lại` · `Tải lại trạng thái` · `Tải lại cơ cấu` | define: *Thử lại* = repeat a failed action; *Tải lại* = refresh data; *Kiểm tra lại* = re-check a status |
| `Gửi ↑` | arrow glyph in a text button | `Gửi` + icon |
| `OrganizationScreens.tsx:131` | pill `Mồ côi` (orphan unit) | `Không có đơn vị cha` |

### T-10. Error-message leakage (server text reaches users)

| file:line | behaviour | proposed |
|---|---|---|
| `adminModel.ts:159` | `${fallback} (${e.message})` appends the server's **English** message | map by `code` / `status`; log `message` |
| `ui.tsx:21` `errText` | `${e.message} (mã ${requestId})` — `message` is whatever the backend sent | keep the request id, translate by `code` |
| `CodeWorkspace.tsx:96`, `CodePanels.tsx:32,71,96`, `drawers.tsx:157` | `e.message` shown verbatim (+ `(${e.code})` in `CodeWorkspace`) | same |
| `organizationModel.ts:216`, `provisioningModel.ts:118,121,122,123` | `Chưa thực hiện được (${x.message}).` / `e?.message ?? "…"` (server text first) | same |
| `errors.ts:39,40,42,53,76,79,91,99,104` | `x.message \|\| "…"` prefers the server message over the Vietnamese default | prefer the default; show `message` only in a details disclosure |

---

## B. CANONICAL TERMINOLOGY AND THE STRING CHANGE LIST

### B.1 Canonical terminology (proposal — product owner to confirm the starred decisions)

| Vietnamese (use) | English / code term | Where used now | Preferred form and rule |
|---|---|---|---|
| **ứng dụng** | project / app (`ApiProject`, `AppKind`) | all portals (171×); *project* 24×, *dự án* 4× | always **ứng dụng**; *website* is one **loại ứng dụng** (type). Never *project* / *dự án* in UI. "Mã ứng dụng" for ids |
| **workspace** \* | workspace | 105× (admin) vs *không gian làm việc* 14× | **Workspace** (loan word, capital W as a UI noun, lower-case in running text). Decision \*: if the product prefers a translation, use *không gian làm việc* everywhere (long) — but not both |
| **công ty** | tenant | 159× | **công ty**; never *tenant* in UI (`Công ty (tenant)` ×2). "Mã công ty" = slug |
| **cơ cấu tổ chức**, **đơn vị**, **loại đơn vị**, **phòng ban** | organization, org unit, unit type, department | org screens, `AdminApp` departments | *Cơ cấu tổ chức* = the screen; *đơn vị* = a node; *phòng ban* only for the cost-report grouping (legacy "Phòng ban & nhóm") |
| **người dùng** / **nhân viên** / **tài khoản** | user / employee / account | admin lists / employee directory / login state | *Người dùng* = person in platform-wide lists; *Nhân viên* = person in a company directory; *Tài khoản* = the login identity and its state (khóa / kích hoạt) |
| **thành viên** | member | 51× | members of a company / workspace / app |
| **vai trò** + role names | role | T-03 | one table in `@xweb/i18n`: Chủ sở hữu · Quản trị workspace · Người chỉnh sửa · Người xuất bản · Người xem · Quản trị công ty · Thành viên · Quản trị hệ thống |
| **quyền** | permission | 134× | *quyền* (never *permission*); permission codes never shown |
| **quản trị viên** | admin (person) | 46× | person = *quản trị viên*; scope words as in the role table |
| **chủ sở hữu** | owner | 12× (+ raw *owner* ×1) | |
| **thành phần**, **thư viện thành phần** | component, registry | *thành phần* 46× (builder), *component* 35× | **thành phần**; *Component Registry* → *Thư viện thành phần*; the word *registry* never appears |
| **mẫu**, **khối** | template, block | *mẫu* 59, *template* 7, *khối* 49 | *mẫu* (page template), *khối* (reusable section, "khối dựng sẵn") |
| **phiên bản**, **bản nháp**, **xuất bản**, **bản xuất bản** | version, draft, publish, release | *phát hành* 9× next to *xuất bản* 96× | **xuất bản** for the action and for "bản xuất bản"; drop *phát hành* |
| **gỡ trang xuống**, **quay về bản này**, **khôi phục**, **lưu trữ**, **xóa**, **gỡ**, **thu hồi** | unpublish, rollback, restore, archive, delete, detach, revoke | mixed (T-05) | *Xóa* = delete a record permanently (or soft-delete shown as gone); *Gỡ* = remove a relationship (member from workspace, link, grant); *Lưu trữ* = archive (reversible); *Khôi phục* = restore (always name the object); *Thu hồi* = revoke (token, sessions); *Quay về bản này* = release rollback; *Bỏ* = discard a draft / selection / block rule |
| **hủy / đóng / xong** | cancel / close / done | | *Hủy* = abandon an action or dialog with possible changes; *Đóng* = dismiss information; *Xong* = finish a flow |
| **mô hình**, **nhà cung cấp**, **khóa kết nối**, **hạn mức**, **ngân sách**, **lượt gọi**, **token** | AI model, provider, credential, limit, budget, call, token | *model* 44× vs *mô hình* 39× | **mô hình AI**; *khóa kết nối* for credentials (never *API key* or *khóa* alone); *Khóa chống ghi trùng* → *Mã chống ghi trùng*; *token* stays |
| **nguồn dữ liệu**, **khe dữ liệu** \*, **truy vấn**, **ánh xạ**, **dữ liệu hiển thị** | data source, slot, query, mapping, view model | *khe* 39× (unexplained), *ViewModel* 15× | *Khe dữ liệu* is the logical name an admin later binds to a real source; define it in one sentence at first use; \*product to confirm the noun (candidates: *điểm nối dữ liệu*, *nguồn logic*). *ViewModel* → **dữ liệu hiển thị** |
| **hành động**, **workflow**, **biểu mẫu** | action, workflow, form | `quy trình` ×1 | *Workflow* (kept); *Biểu mẫu* (not *Form* except the component name "Form liên hệ" → *Biểu mẫu liên hệ*) |
| **chế độ dùng thử**, **chế độ mô phỏng**, **chỉnh sửa** | builder TEST mode; simulator; EDIT mode | *thử nghiệm* 15, *mô phỏng* 8, *Demo* / *Mock* | **Chế độ dùng thử** = builder TEST (no real side effects); **Chế độ mô phỏng** = no real AI / no real deployment (replaces *Chế độ thử nghiệm*, *Demo*, *Mock*). Never both words for one thing |
| **Hoạt động / Bị khóa / Chờ kích hoạt** · **Hoạt động / Tạm khóa / Đã xóa** · **Đang bật / Đã tắt** | status vocabularies | | accounts · companies · switchable things (provider, unit, data source); use *Khóa* for accounts, *Tạm khóa* for companies, *Tắt* for the rest; *Vô hiệu hóa* and *Tắt tài khoản* retire |
| **thử lại / tải lại / kiểm tra lại** | retry / reload / re-check | 59 / 25 / ≈ 10 | *Thử lại* = repeat a failed action; *Tải lại* = refresh data; *Kiểm tra lại* = re-check a status |
| **máy chủ**, **hệ thống**, **nền tảng** | server, system, platform | 134 / 49 / 11 | *máy chủ* for "the server refused / is not ready"; *hệ thống* for global scope ("quản trị hệ thống"); *nền tảng* for the product ("nền tảng nội bộ") |
| **tệp**, **thư viện**, **gói** | file, library, package | *tệp* 25, *file* 2, *package* 11 | *tệp*; *gói thư viện* for npm packages |
| **kho mã**, **thay đổi**, **hợp nhất**, **bản chính** | repository, change, merge, main | `commit`, `main`, `Git` exposed | plain words (T-04) |
| English kept | AI, API, SSO, OIDC, SAML, SCIM, MFA, URL, DNS, HTTPS, CSV, SEO, ID, Workspace, Workflow, Connector, Token, Build, Git | | everything else translated (T-06) |

### B.2 Strings to change (file:line | current | proposed)

The tables below are generated from the string extraction (so they are exhaustive for literals that contain the term) with a **mechanical** replacement; wording must still be reviewed by the owner of each screen. Other changes are in section A (T-02 … T-10).

#### B.2.a *project / dự án* → *ứng dụng* (legacy `components/` excluded)

| file:line | current | proposed (mechanical, review wording) |
|---|---|---|
| features/admin/AdminApp.tsx:722 | Project ID | Mã ứng dụng |
| features/admin/AdminApp.tsx:422 | Chủ cũ ở lại dự án với vai trò Editor. Mọi thay đổi được ghi nhật ký. | Chủ cũ ở lại ứng dụng với vai trò Editor. Mọi thay đổi được ghi nhật ký. |
| features/admin/AdminApp.tsx:500 | Ngân sách theo tổ chức/dự án và ngưỡng cảnh báo: chưa triển khai (đang có giới hạn token theo người … | Ngân sách theo tổ chức/ứng dụng và ngưỡng cảnh báo: chưa triển khai (đang có giới hạn token theo ngư… |
| features/studio/CodeWorkspace.tsx:218 | Dự án yêu cầu duyệt: một thành viên có quyền xuất bản (không phải người tạo) cần duyệt trước khi hợp… | Ứng dụng yêu cầu duyệt: một thành viên có quyền xuất bản (không phải người tạo) cần duyệt trước khi … |
| features/studio/ProjectWorkspace.tsx:133 | Project vừa được thay đổi ở nơi khác. Đã tải lại bản mới nhất, hãy thử lại. | Ứng dụng vừa được thay đổi ở nơi khác. Đã tải lại bản mới nhất, hãy thử lại. |
| features/studio/ProjectWorkspace.tsx:279 | Cài đặt project | Cài đặt ứng dụng |
| features/studio/ProjectWorkspace.tsx:298 | Cài đặt project | Cài đặt ứng dụng |
| features/studio/ProjectWorkspace.tsx:391 | Lưu trữ “${project.name}”? | Lưu trữ “${ứng dụng.name}”? |
| features/studio/builder/BuilderWorkspace.tsx:164 | …evision ${props.revision} · ${props.project.siteVisibility === "PUBLIC" ? "Công khai" : "Riêng tư"}$… | …evision ${props.revision} · ${props.ứng dụng.siteVisibility === "PUBLIC" ? "Công khai" : "Riêng tư"}… |
| features/studio/drawers.tsx:53 | Cài đặt project | Cài đặt ứng dụng |
| features/studio/drawers.tsx:55 | Tên project | Tên ứng dụng |
| features/studio/drawers.tsx:87 | Tệp của project | Tệp của ứng dụng |
| features/studio/drawers.tsx:163 | Thành viên project | Thành viên ứng dụng |
| features/studio/drawers.tsx:165 | Xóa ${m.username} khỏi project? | Xóa ${m.username} khỏi ứng dụng? |
| features/studio/drawers.tsx:169 | … workspace? Họ cũng mất quyền ở mọi project. | … workspace? Họ cũng mất quyền ở mọi ứng dụng. |
| features/library.tsx:34 | Không gắn tệp của dự án | Không gắn tệp của ứng dụng |

#### B.2.b *không gian làm việc* → *workspace* (14 strings; also the 5 `Quản trị không gian làm việc` role labels, T-03)

| file:line | current | proposed (mechanical, review wording) |
|---|---|---|
| features/admin/AiSetup.tsx:26 | Theo không gian làm việc | Theo workspace |
| features/admin/AiSetup.tsx:266 | Lượng AI (token) / không gian làm việc / tháng | Lượng AI (token) / workspace / tháng |
| features/admin/AiSetup.tsx:268 | Ngân sách AI trả phí / không gian làm việc / tháng (USD) | Ngân sách AI trả phí / workspace / tháng (USD) |
| features/admin/AiSetup.tsx:281 | Không gian làm việc | Workspace |
| features/admin/AiSetup.tsx:359 | Hạn mức theo không gian làm việc | Hạn mức theo workspace |
| features/admin/UserDialogs.tsx:8 | Quản trị không gian làm việc | Quản trị workspace |
| features/admin/adminModel.ts:138 | Quản trị không gian làm việc | Quản trị workspace |
| features/admin/provisioningModel.ts:15 | Quản trị không gian làm việc | Quản trị workspace |
| features/admin/provisioningModel.ts:16 | Quản trị không gian làm việc | Quản trị workspace |
| features/studio/builder/DataSourcesPanel.tsx:140 | Nguồn dữ liệu của không gian làm việc | Nguồn dữ liệu của workspace |
| features/studio/builder/DataSourcesPanel.tsx:173 | Chưa có nguồn dữ liệu nào trong không gian làm việc này. | Chưa có nguồn dữ liệu nào trong workspace này. |
| features/studio/builder/DataSourcesPanel.tsx:219 | … khóa kết nối của nó sẽ bị xóa khỏi không gian làm việc. Nếu nguồn đang được liên kết với một ứng dụ… | … khóa kết nối của nó sẽ bị xóa khỏi workspace. Nếu nguồn đang được liên kết với một ứng dụng, máy ch… |
| features/studio/builder/core/dataManagement.ts:63 | Cần quyền quản lý nguồn dữ liệu của không gian làm việc (quản trị viên). Quyền được kiểm tra ở máy c… | Cần quyền quản lý nguồn dữ liệu của workspace (quản trị viên). Quyền được kiểm tra ở máy chủ. |
| features/studio/builder/core/dataManagement.ts:67 | … này không tồn tại hoặc không thuộc không gian làm việc của bạn. Tải lại danh sách. | … này không tồn tại hoặc không thuộc workspace của bạn. Tải lại danh sách. |

#### B.2.c *model* → *mô hình* (43 strings)

| file:line | current | proposed (mechanical, review wording) |
|---|---|---|
| features/admin/AdminApp.tsx:442 | Chưa có lượt gọi model nào | Chưa có lượt gọi mô hình nào |
| features/admin/AdminApp.tsx:447 | Chưa có lượt gọi model thật nào trong khoảng thời gian này. | Chưa có lượt gọi mô hình thật nào trong khoảng thời gian này. |
| features/admin/AdminApp.tsx:472 | Nhật ký lượt gọi model | Nhật ký lượt gọi mô hình |
| features/admin/AdminApp.tsx:475 | Model | Mô hình |
| features/admin/AdminApp.tsx:477 | Bộ mô phỏng không gọi model nên không có dòng nào ở đây. | Bộ mô phỏng không gọi mô hình nên không có dòng nào ở đây. |
| features/admin/AdminApp.tsx:496 | Lượt gọi model | Lượt gọi mô hình |
| features/admin/AdminApp.tsx:516 | Bảng giá model | Bảng giá mô hình |
| features/admin/AdminApp.tsx:519 | Model | Mô hình |
| features/admin/AdminApp.tsx:526 | Chi phí của model trả phí sẽ hiển thị “không rõ” cho tới khi có giá. | Chi phí của mô hình trả phí sẽ hiển thị “không rõ” cho tới khi có giá. |
| features/admin/AdminApp.tsx:550 | Mức sử dụng model | Mức sử dụng mô hình |
| features/admin/AdminApp.tsx:554 | Lượt gọi model | Lượt gọi mô hình |
| features/admin/AdminApp.tsx:556 | Model miễn phí báo $0 | Mô hình miễn phí báo $0 |
| features/admin/AdminApp.tsx:564 | Theo model | Theo mô hình |
| features/admin/AdminApp.tsx:938 | Quyền dùng model theo tổ chức → workspace → vai trò → người dùng (chặn ở bất kỳ mức nào là chặn), và… | Quyền dùng mô hình theo tổ chức → workspace → vai trò → người dùng (chặn ở bất kỳ mức nào là chặn), … |
| features/admin/AdminApp.tsx:940 | Quyền dùng model (Model Access) | Quyền dùng mô hình (Mô hình Access) |
| features/admin/AdminApp.tsx:944 | Model bị chặn | Mô hình bị chặn |
| features/admin/AdminApp.tsx:956 | Chưa có model nào được cấu hình | Chưa có mô hình nào được cấu hình |
| features/admin/AdminApp.tsx:430 | Model | Mô hình |
| features/admin/AdminApp.tsx:475 | Mọi model | Mọi mô hình |
| features/admin/AdminApp.tsx:478 | Model | Mô hình |
| features/admin/AdminApp.tsx:517 | …ông báo (OpenAI, Anthropic, Gemini, model nội bộ). Hệ thống không có sẵn giá nào. Giá không sửa được… | …ông báo (OpenAI, Anthropic, Gemini, mô hình nội bộ). Hệ thống không có sẵn giá nào. Giá không sửa đư… |
| features/admin/AdminApp.tsx:519 | Chọn model | Chọn mô hình |
| features/admin/AdminApp.tsx:527 | Model | Mô hình |
| features/admin/AdminApp.tsx:572 | Model | Mô hình |
| features/admin/AdminApp.tsx:941 | Model phải được bật ở AI Control trước. Quy tắc ở đây chỉ CHẶN thêm. Mã model: chính xác (ví dụ | Mô hình phải được bật ở AI Control trước. Quy tắc ở đây chỉ CHẶN thêm. Mã mô hình: chính xác (ví dụ |
| features/admin/AdminApp.tsx:941 | (mọi model trả phí) hoặc | (mọi mô hình trả phí) hoặc |
| features/admin/AdminApp.tsx:941 | (mọi model thật; bộ mô phỏng luôn dùng được). | (mọi mô hình thật; bộ mô phỏng luôn dùng được). |
| features/admin/AdminApp.tsx:948 | Model bị chặn | Mô hình bị chặn |
| features/admin/AdminApp.tsx:957 | Model | Mô hình |
| features/studio/CodeWorkspace.tsx:167 | Model AI | Mô hình AI |
| features/studio/CodeWorkspace.tsx:171 | Đã gửi, đang chờ model trả lời… | Đã gửi, đang chờ mô hình trả lời… |
| features/studio/ProjectWorkspace.tsx:39 | ${calls} lượt gọi model | ${calls} lượt gọi mô hình |
| features/studio/ProjectWorkspace.tsx:344 | Model AI | Mô hình AI |
| features/studio/ProjectWorkspace.tsx:343 | Model AI | Mô hình AI |
| features/studio/StudioApp.tsx:138 | Chưa gọi model thật nào; Chế độ thử nghiệm không tính token | Chưa gọi mô hình thật nào; Chế độ thử nghiệm không tính token |
| features/studio/aiProgressModel.ts:23 | Đang hỏi model ${status.slice(6)}… | Đang hỏi mô hình ${status.slice(6)}… |
| features/studio/aiProgressModel.ts:24 | Model trước chưa trả lời được, đang thử ${status.slice(9)}… | Mô hình trước chưa trả lời được, đang thử ${status.slice(9)}… |
| features/studio/aiProgressModel.ts:30 | Tự động: thử lần lượt các model miễn phí | Tự động: thử lần lượt các mô hình miễn phí |
| features/studio/aiProgressModel.ts:44 | Chờ model trả lời (${modelLabel(model)}) | Chờ mô hình trả lời (${mô hìnhLabel(mô hình)}) |
| features/studio/aiProgressModel.ts:55 | Đã gửi, đang chờ model bắt đầu trả lời… | Đã gửi, đang chờ mô hình bắt đầu trả lời… |
| features/studio/aiProgressModel.ts:59 | Chưa có phản hồi nào từ model sau ${silent} giây. Model miễn phí thường bận; với “Tự động”, máy chủ … | Chưa có phản hồi nào từ mô hình sau ${silent} giây. Mô hình miễn phí thường bận; với “Tự động”, máy … |
| features/studio/aiProgressModel.ts:60 | Model ngừng gửi dữ liệu ${silent} giây. Có thể huỷ và thử lại. | Mô hình ngừng gửi dữ liệu ${silent} giây. Có thể huỷ và thử lại. |
| packages/ui/src/ui.tsx:28 | Thử lại sau hoặc chọn model khác. | Thử lại sau hoặc chọn mô hình khác. |

#### B.2.d *component* → *thành phần* (38 strings; the nav / title cases are also in T-06)

| file:line | current | proposed (mechanical, review wording) |
|---|---|---|
| features/admin/AdminApp.tsx:580 | Component Registry | Thư viện thành phần |
| features/admin/AdminApp.tsx:580 | Component đã duyệt (AI và trình chỉnh sửa chỉ dùng những component này) và khối do nhân viên đóng gó… | Thành phần đã duyệt (AI và trình chỉnh sửa chỉ dùng những thành phần này) và khối do nhân viên đóng … |
| features/admin/AdminApp.tsx:603 | Thêm component gốc mới | Thêm thành phần gốc mới |
| features/admin/AdminApp.tsx:603 | Component có renderer mới | Thành phần có renderer mới |
| features/admin/AdminApp.tsx:666 | …c giả gửi duyệt → kiểm tra tự động (component, nội dung, render an toàn) → quản trị viên khác tác gi… | …c giả gửi duyệt → kiểm tra tự động (thành phần, nội dung, render an toàn) → quản trị viên khác tác g… |
| features/admin/AdminApp.tsx:596 | Component | Thành phần |
| features/admin/AdminApp.tsx:603 | Thêm một loại component gốc mới cần viết renderer trong mã nguồn và được review như mọi thay đổi mã.… | Thêm một loại thành phần gốc mới cần viết renderer trong mã nguồn và được review như mọi thay đổi mã… |
| features/admin/AdminApp.tsx:617 | Component gốc | Thành phần gốc |
| features/studio/ProjectWorkspace.tsx:169 | …ged.map(label).join(", ") \|\| "—"} · Component đang dùng: ${used.map(label).join(", ")} | …ged.map(label).join(", ") \|\| "—"} · Thành phần đang dùng: ${used.map(label).join(", ")} |
| features/studio/ProjectWorkspace.tsx:310 | và chỉ dùng component trong Company Component Registry. Hệ thống | và chỉ dùng thành phần trong thư viện thành phần của công ty. Hệ thống |
| features/studio/StudioApp.tsx:148 | Component dùng chung | Thành phần dùng chung |
| features/studio/StudioApp.tsx:187 | Website một hoặc nhiều trang từ component đã duyệt, có form liên hệ và tên miền riêng. | Website một hoặc nhiều trang từ thành phần đã duyệt, có form liên hệ và tên miền riêng. |
| features/studio/StudioApp.tsx:62 | ], ["components", "Components", | ], ["thành phần", "Thành phần", |
| features/studio/StudioApp.tsx:124 | …ô tả ý tưởng; Studio tạo website từ component đã duyệt của công ty, rồi bạn chỉnh bằng AI hoặc trực … | …ô tả ý tưởng; Studio tạo website từ thành phần đã duyệt của công ty, rồi bạn chỉnh bằng AI hoặc trực… |
| features/studio/StudioApp.tsx:141 | Component của công ty | Thành phần của công ty |
| features/studio/StudioApp.tsx:284 | … là cấu trúc trang (Page Schema) từ component đã duyệt, không phải mã nguồn; ảnh không đi kèm mẫu. | … là cấu trúc trang (Page Schema) từ thành phần đã duyệt, không phải mã nguồn; ảnh không đi kèm mẫu. |
| features/studio/StudioApp.tsx:334 | Company Components | Thành phần của công ty |
| features/studio/StudioApp.tsx:334 | Component đã được duyệt. AI và trình chỉnh sửa chỉ dùng những component này; số liệu là số ứng dụng … | Thành phần đã được duyệt. AI và trình chỉnh sửa chỉ dùng những thành phần này; số liệu là số ứng dụn… |
| features/studio/StudioApp.tsx:356 | …hình sẵn (nội dung, bố cục) của một component đã duyệt, được nhân viên đóng góp và quản trị viên phê… | …hình sẵn (nội dung, bố cục) của một thành phần đã duyệt, được nhân viên đóng góp và quản trị viên ph… |
| features/studio/builder/Canvas.tsx:46 | Thả component vào trang trống | Thả thành phần vào trang trống |
| features/studio/builder/Inspector.tsx:179 | Phiên bản component | Phiên bản thành phần |
| features/studio/builder/Inspector.tsx:180 | Chưa có (máy chủ chưa trả component-metadata) | Chưa có (máy chủ chưa trả thông tin thành phần) |
| features/studio/builder/core/backend.ts:22 | Máy chủ chưa có component-metadata. | Máy chủ chưa có thông tin thành phần. |
| features/studio/builder/core/inspector.ts:54 | Component này chưa khai báo thuộc tính thiết kế (khoảng cách, kích thước, căn lề, chữ) trong registr… | Thành phần này chưa khai báo thuộc tính thiết kế (khoảng cách, kích thước, căn lề, chữ) trong regist… |
| features/studio/builder/core/inspector.ts:56 | Chưa có thông tin sự kiện của component (component-metadata). | Chưa có thông tin sự kiện của thành phần (thông tin thành phần). |
| features/studio/builder/core/library.ts:39 | Chưa có bản xem trước cho component này | Chưa có bản xem trước cho thành phần này |
| features/studio/builder/core/library.ts:62 | Component này chưa có trong Company Component Registry. | Thành phần này chưa có trong thư viện thành phần của công ty. |
| features/studio/builder/core/library.ts:63 | Component có trong registry nhưng chưa có bản xem trước. | Thành phần có trong registry nhưng chưa có bản xem trước. |
| features/studio/builder/panels/ComponentsPanel.tsx:23 | Tìm component… | Tìm thành phần… |
| features/studio/builder/panels/ComponentsPanel.tsx:24 | Thư viện component | Thư viện thành phần |
| features/studio/builder/panels/ComponentsPanel.tsx:21 | Component đã được công ty duyệt. Kéo vào bản xem trước, hoặc nhấn “Thêm” (có thể dùng bàn phím). Khô… | Thành phần đã được công ty duyệt. Kéo vào bản xem trước, hoặc nhấn “Thêm” (có thể dùng bàn phím). Kh… |
| features/studio/builder/panels/ComponentsPanel.tsx:22 | Tìm component | Tìm thành phần |
| features/studio/builder/panels/ComponentsPanel.tsx:26 | Không có component phù hợp. | Không có thành phần phù hợp. |
| features/studio/builder/panels/PagesPanel.tsx:67 | Trang trống. Kéo một component vào, hoặc nhấn vào component trong mục “Thành phần”. | Trang trống. Kéo một thành phần vào, hoặc nhấn vào thành phần trong mục “Thành phần”. |
| features/studio/libraryPanels.tsx:50 | : ""} Gửi duyệt ở Studio → Components. | : ""} Gửi duyệt ở Studio → Thành phần. |
| features/studio/libraryPanels.tsx:56 | Dựa trên component ${section.type}. Dùng nội dung ĐÃ LƯU của mục này. | Dựa trên thành phần ${section.type}. Dùng nội dung ĐÃ LƯU của mục này. |
| features/studio/libraryPanels.tsx:58 | Khối là cấu hình sẵn của một component đã duyệt (không chứa mã). Khối mới chỉ bạn dùng được; gửi duy… | Khối là cấu hình sẵn của một thành phần đã duyệt (không chứa mã). Khối mới chỉ bạn dùng được; gửi du… |
| features/library.tsx:34 | Component gốc đã duyệt | Thành phần gốc đã duyệt |

#### B.2.e *template* → *mẫu* (7 strings; nav labels are in T-06)

| file:line | current | proposed (mechanical, review wording) |
|---|---|---|
| features/admin/AdminApp.tsx:666 | Templates | Mẫu |
| features/admin/AdminApp.tsx:26 | ], ["templates", "Templates", | ], ["mẫu", "Mẫu", |
| features/studio/ProjectWorkspace.tsx:168 | …{reuse.blocks.length} khối, ${reuse.templates.length} template | …{reuse.blocks.length} khối, ${reuse.mẫu.length} mẫu |
| features/studio/StudioApp.tsx:62 | ], ["templates", "Templates", | ], ["mẫu", "Mẫu", |
| features/studio/StudioApp.tsx:284 | Templates | Mẫu |
| features/studio/libraryPanels.tsx:20 | Đã lưu mẫu “${r.template.name}” (v${r.template.version}, riêng tư).${r.removedImages ? | Đã lưu mẫu “${r.mẫu.name}” (v${r.mẫu.version}, riêng tư).${r.removedImages ? |
| features/studio/libraryPanels.tsx:20 | : ""} Xem ở Studio → Templates. | : ""} Xem ở Studio → Mẫu. |

### B.3 Tone-mark style: `xoá huỷ tuỳ khoá` → `xóa hủy tùy khóa` (47 strings)

| file:line | current | proposed (modern tone-mark style) |
|---|---|---|
| features/admin/AdminApp.tsx:770 | Xoá vĩnh viễn kho mã “${r.name}”? Không thể hoàn tác. | Xóa vĩnh viễn kho mã “${r.name}”? Không thể hoàn tác. |
| features/admin/AdminApp.tsx:770 | Không xoá được. | Không xóa được. |
| features/admin/AdminApp.tsx:795 | Chờ xoá | Chờ xóa |
| features/admin/AdminApp.tsx:795 | Đã xoá | Đã xóa |
| features/admin/AdminApp.tsx:925 | Không xoá được. | Không xóa được. |
| features/admin/AdminApp.tsx:934 | Xoá ngân sách này? | Xóa ngân sách này? |
| features/admin/AdminApp.tsx:934 | Không xoá được. | Không xóa được. |
| features/admin/AdminApp.tsx:993 | …hạm ngưỡng hoặc nhà cung cấp AI từ chối khoá / hết tín dụng. | …hạm ngưỡng hoặc nhà cung cấp AI từ chối khóa / hết tín dụng. |
| features/admin/AdminApp.tsx:1082 | Xoá “${d.name}”? | Xóa “${d.name}”? |
| features/admin/AdminApp.tsx:787 | …đang chạy. Job tự chạy mỗi giờ; mỗi lần xoá đều ghi audit. | …đang chạy. Job tự chạy mỗi giờ; mỗi lần xóa đều ghi audit. |
| features/admin/AdminApp.tsx:797 | Xoá vĩnh viễn | Xóa vĩnh viễn |
| features/admin/AdminApp.tsx:980 | Xoá | Xóa |
| features/admin/AdminApp.tsx:1082 | Xoá | Xóa |
| features/studio/AiProgress.tsx:18 | Huỷ | Hủy |
| features/studio/CodePanels.tsx:73 | …ng ty đã duyệt. Không chạy lệnh cài đặt tuỳ ý; phiên bản do danh mục quyết định. | …ng ty đã duyệt. Không chạy lệnh cài đặt tùy ý; phiên bản do danh mục quyết định. |
| features/studio/CodePanels.tsx:139 | Xoá | Xóa |
| features/studio/CodeWorkspace.tsx:18 | Đã huỷ | Đã hủy |
| features/studio/CodeWorkspace.tsx:122 | Nhận xét khi duyệt (tuỳ chọn): | Nhận xét khi duyệt (tùy chọn): |
| features/studio/CodeWorkspace.tsx:125 | Không huỷ được. | Không hủy được. |
| features/studio/CodeWorkspace.tsx:188 | Mô tả ngắn (tuỳ chọn) | Mô tả ngắn (tùy chọn) |
| features/studio/CodeWorkspace.tsx:39 | (xoá) | (xóa) |
| features/studio/CodeWorkspace.tsx:172 | Huỷ | Hủy |
| features/studio/CodeWorkspace.tsx:215 | Huỷ | Hủy |
| features/studio/ProjectWorkspace.tsx:129 | Đã huỷ yêu cầu AI. | Đã hủy yêu cầu AI. |
| features/studio/SitePanels.tsx:49 | Xoá trang “${current.title}” cùng các phần của nó? Liên kết điều hướng tới trang này cũng bị xoá. | Xóa trang “${current.title}” cùng các phần của nó? Liên kết điều hướng tới trang này cũng bị xóa. |
| features/studio/SitePanels.tsx:50 | Xoá trang ${current.title} | Xóa trang ${current.title} |
| features/studio/SitePanels.tsx:84 | Xoá liên kết ${l.label} | Xóa liên kết ${l.label} |
| features/studio/SitePanels.tsx:106 | Xoá tin gửi này? | Xóa tin gửi này? |
| features/studio/SitePanels.tsx:106 | Không xoá được. | Không xóa được. |
| features/studio/SitePanels.tsx:64 | Xoá trang | Xóa trang |
| features/studio/SitePanels.tsx:108 | … nhân: chỉ người chỉnh sửa xem được, tự xoá sau thời hạn lưu giữ của công ty. | … nhân: chỉ người chỉnh sửa xem được, tự xóa sau thời hạn lưu giữ của công ty. |
| features/studio/SitePanels.tsx:114 | Xoá | Xóa |
| features/studio/aiProgressModel.ts:58 | …n đã nhận yêu cầu. Kiểm tra kết nối rồi huỷ và gửi lại nếu quá lâu. | …n đã nhận yêu cầu. Kiểm tra kết nối rồi hủy và gửi lại nếu quá lâu. |
| features/studio/aiProgressModel.ts:59 | …odel khác khi một model lỗi. Bạn có thể huỷ rồi thử lại hoặc chọn model khác. | …odel khác khi một model lỗi. Bạn có thể hủy rồi thử lại hoặc chọn model khác. |
| features/studio/aiProgressModel.ts:60 | …gừng gửi dữ liệu ${silent} giây. Có thể huỷ và thử lại. | …gừng gửi dữ liệu ${silent} giây. Có thể hủy và thử lại. |
| features/studio/builder/DataWizard.tsx:126 | Số dòng tối đa (tuỳ chọn) | Số dòng tối đa (tùy chọn) |
| features/studio/builder/Inspector.tsx:148 | Ẩn hay khoá một nút trên giao diện không phải là phân quyền. Máy chủ kiểm tra quyền ở mọi lệnh gọi. | Ẩn hay khóa một nút trên giao diện không phải là phân quyền. Máy chủ kiểm tra quyền ở mọi lệnh gọi. |
| features/studio/builder/PublicDataPanels.tsx:84 | Tên hiển thị (tuỳ chọn) | Tên hiển thị (tùy chọn) |
| features/studio/builder/PublicDataPanels.tsx:87 | Mô tả (tuỳ chọn) | Mô tả (tùy chọn) |
| features/studio/builder/core/pages.ts:86 | Xoá trang “${p.title}” sẽ xoá ${parts.join(" và ")}.${warn} | Xóa trang “${p.title}” sẽ xóa ${parts.join(" và ")}.${warn} |
| features/studio/builder/core/pages.ts:90 | Không thể xoá trang chủ. | Không thể xóa trang chủ. |
| features/studio/builder/core/pages.ts:93 | Xoá trang ${p.title} | Xóa trang ${p.title} |
| features/studio/builder/panels/MiscPanels.tsx:50 | …chọn từ danh sách cố định: không có CSS tuỳ ý hay địa chỉ phông. | …chọn từ danh sách cố định: không có CSS tùy ý hay địa chỉ phông. |
| features/studio/builder/panels/PagesPanel.tsx:80 | Không thể xoá trang chủ | Không thể xóa trang chủ |
| features/studio/builder/panels/PagesPanel.tsx:189 | trang đã xoá | trang đã xóa |
| packages/api-client/src/core.ts:91 | Đã huỷ yêu cầu AI. | Đã hủy yêu cầu AI. |
| packages/api-client/src/core.ts:104 | Đã huỷ yêu cầu AI. | Đã hủy yêu cầu AI. |

---

## C. HOW MANY USER-VISIBLE STRINGS, AND WHERE THEY LIVE (i18n readiness)

Counts are **string occurrences** with Vietnamese diacritics (JS literals, template literals, JSX text); they include messages that are thrown as `Error` but shown on screen; they exclude test ids, class names and comments. Upper bound within ≈ 3 %.

### C.1 By area

| Area | Portal(s) | VI string occurrences | unique | with `${…}` placeholders | in pure `.ts` model files | JSX text nodes | `aria-label` / `title` / `placeholder` / `alt` |
|---|---|---:|---:|---:|---:|---:|---:|
| `features/admin/**` | **Platform + Admin** (one bundle; sections are split at run time by `OWNED` in `base.ts`) | 1 454 | 1 014 | 97 | 167 | 455 | 235 |
| `features/studio/builder/**` | **Studio** (Design mode, shared `DataSourcesPanel` also in Admin) | 1 168 | 1 004 | 204 | 531 | 229 | 98 |
| `features/studio/*` (other) | **Studio** (shell, project, drawers, release, code) | 681 | 562 | 70 | 24 | 207 | 91 |
| `packages/api-client` | all (errors, release labels) | 108 | 102 | 9 | 108 | 0 | 0 |
| `packages/auth` | all three (login, activation, no-access…) | 67 | 61 | 2 | 0 | 31 | 2 |
| `packages/i18n` | all (action / role / portal maps) | 47 | 47 | 0 | 47 | 0 | 0 |
| `features/library.tsx` + `features/*.ts` | Admin + Studio (templates / blocks) | 30 | 29 | 2 | 0 | 1 | 3 |
| `packages/ui` | all (state texts, pager, nav button, portal switcher) | 28 | 28 | 3 | 0 | 2 | 1 |
| `packages/permissions` | all (permission labels) | 16 | 15 | 1 | 16 | 0 | 0 |
| `apps/*` | metadata (`title`, `description`) | 3 | 3 | 0 | 0 | 0 | 0 |
| **three portals total** | | **3 602** | **≈ 2 600** | **388** | **893** | **925** | **430** |
| legacy root app (`components/`, `app/`) — not one of the three portals | demo / static export | 76 | 60 | 1 | 0 | 26 | 9 |

**By portal (approximate, shared code counted once):** Platform + Admin ≈ **1 450** (+ 30 in the shared `library.tsx`) · Studio ≈ **1 850** (+ the same 30) · shared by all three (`auth`, `ui`, `i18n`, `permissions`, `api-client`) ≈ **266**. Platform-only vs Admin-only copy cannot be separated by file (one `AdminApp.tsx`: 706 strings; `TenantScreens.tsx` 107; `AiSetup.tsx` 193; `OrganizationScreens.tsx` 99; `EmployeesScreens.tsx` 96; `ProvisioningScreens.tsx` 56).

### C.2 Top files (VI string occurrences / unique)

| file | VI string occurrences | unique |
|---|---|---|
| features/admin/AdminApp.tsx | 706 | 517 |
| features/admin/AiSetup.tsx | 193 | 149 |
| features/studio/StudioApp.tsx | 168 | 146 |
| features/admin/TenantScreens.tsx | 107 | 85 |
| features/admin/OrganizationScreens.tsx | 99 | 85 |
| features/studio/CodeWorkspace.tsx | 98 | 93 |
| features/admin/EmployeesScreens.tsx | 96 | 66 |
| features/studio/ProjectWorkspace.tsx | 93 | 81 |
| features/studio/CodePanels.tsx | 85 | 81 |
| features/studio/builder/DataWizard.tsx | 76 | 72 |
| packages/api-client/src/release.ts | 73 | 73 |
| components/StudioShell.tsx | 73 | 57 |
| features/studio/builder/PublicDataPanels.tsx | 71 | 63 |
| features/studio/builder/core/definition.ts | 71 | 54 |
| features/studio/builder/WorkflowEditor.tsx | 66 | 64 |
| features/admin/provisioningModel.ts | 64 | 57 |
| features/studio/ReleaseModal.tsx | 64 | 57 |
| packages/auth/src/AuthPages.tsx | 64 | 58 |
| features/studio/SitePanels.tsx | 62 | 58 |
| features/studio/builder/ActionEditor.tsx | 62 | 61 |
| features/studio/builder/Inspector.tsx | 59 | 57 |
| features/studio/builder/core/dataManagement.ts | 59 | 58 |
| features/studio/drawers.tsx | 58 | 54 |
| features/admin/organizationModel.ts | 57 | 53 |
| features/studio/builder/core/publicData.ts | 57 | 57 |
| features/admin/ProvisioningScreens.tsx | 56 | 48 |
| features/studio/builder/core/dataFlow.ts | 52 | 52 |
| features/studio/builder/core/errors.ts | 52 | 48 |
| features/studio/builder/panels/PagesPanel.tsx | 50 | 43 |
| packages/i18n/src/index.ts | 47 | 47 |
| features/admin/adminModel.ts | 42 | 37 |
| features/studio/builder/BuilderWorkspace.tsx | 42 | 39 |
| features/studio/builder/DataSourcesPanel.tsx | 42 | 42 |
| features/studio/builder/core/testMode.ts | 42 | 41 |
| features/studio/builder/core/workflow.ts | 42 | 42 |
| features/studio/builder/PropsForm.tsx | 39 | 36 |
| features/studio/builder/core/actions.ts | 37 | 37 |
| features/studio/builder/TestPanel.tsx | 36 | 30 |
| features/studio/builder/core/library.ts | 35 | 34 |
| features/library.tsx | 30 | 29 |
| packages/ui/src/ui.tsx | 26 | 26 |
| features/studio/builder/core/pages.ts | 25 | 24 |
| features/studio/builder/panels/MiscPanels.tsx | 25 | 23 |
| features/studio/libraryPanels.tsx | 25 | 23 |
| features/studio/aiProgressModel.ts | 24 | 24 |
| features/studio/builder/core/inspector.ts | 23 | 23 |
| features/studio/builder/BuilderTopBar.tsx | 16 | 16 |
| features/studio/builder/panels/ComponentsPanel.tsx | 16 | 12 |
| packages/permissions/src/canonical.ts | 15 | 15 |
| packages/api-client/src/runtimeConfig.ts | 13 | 12 |
| features/studio/builder/core/permissions.ts | 12 | 12 |
| features/studio/builder/panels/ActionsPanel.tsx | 12 | 11 |
| packages/api-client/src/core.ts | 12 | 8 |
| features/admin/UserDialogs.tsx | 11 | 10 |
| features/studio/builder/core/readiness.ts | 10 | 10 |
| packages/api-client/src/api.ts | 10 | 9 |
| features/admin/PersonPicker.tsx | 9 | 8 |
| features/studio/builder/core/preflight.ts | 9 | 9 |
| features/studio/builder/panels/WorkflowsPanel.tsx | 9 | 9 |
| features/studio/builder/LeftRail.tsx | 7 | 7 |
| features/admin/OrganizationLive.tsx | 5 | 5 |
| features/admin/ProvisioningLive.tsx | 5 | 4 |
| features/studio/builder/Canvas.tsx | 5 | 4 |
| features/studio/AiProgress.tsx | 4 | 4 |
| features/studio/builder/core/dnd.ts | 4 | 4 |
| features/studio/builder/ui/primitives.tsx | 4 | 4 |
| features/admin/organization.ts | 3 | 3 |
| packages/auth/src/PortalApp.tsx | 3 | 3 |
| components/app/AppEntry.tsx | 3 | 3 |
| packages/ui/src/NavDrawer.tsx | 2 | 2 |
| features/admin/provisioning.ts | 1 | 1 |
| features/studio/builder/core/backend.ts | 1 | 1 |
| packages/permissions/src/index.ts | 1 | 1 |
| apps/admin/app/layout.tsx | 1 | 1 |
| apps/platform/app/layout.tsx | 1 | 1 |
| apps/studio/app/layout.tsx | 1 | 1 |

### C.3 Readiness verdict for an i18n project

* **≈ 99 % of the copy is inline in components.** `@xweb/i18n` holds 47 strings (1.3 %) and three exports (`roleName`, `PORTAL_TEXT`, and in effect `ROLE_LABEL`) have no importer; `ACTION_LABEL` clashes by name with the builder's `core/actions.ts`.
* **Easiest first wave (≈ 893 strings, 24 %)** already live in **pure `.ts` model files** (`features/studio/builder/core/*.ts`, `features/admin/*Model.ts`, `provisioning*.ts`, `organization*.ts`, `packages/api-client/src/{release,core,runtimeConfig}.ts`, `packages/permissions`, `packages/i18n`): they have no JSX, are unit-tested, and can move to message catalogues with mechanical edits.
* **388 strings (10.6 %) are built with `${…}` interpolation / concatenation** (`Đã dọn: ${a} artifact (${b}), ${c} bản xem trước hết hạn.`): these need named placeholders or ICU messages; word order is currently fixed in code. Vietnamese has no plural forms, but English will (`1 phiên` / `n phiên`) — the counts are spliced in code (e.g. `Đã thu hồi ${r.revoked} phiên.`).
* **430 strings (12 %) are attributes** (`aria-label` 206, `title` 186, `placeholder` 44, `alt` 3): the usual blind spot of extraction tools; of the 99 `title=` attributes, 33 are the *only* explanation of a disabled control (S3-016).
* **Formatting is hard-coded:** `Intl` with `"vi-VN"` in `ui.tsx:9,16,20`, `ProjectWorkspace.tsx:289`, `drawers.tsx:15`, `ReleaseModal.tsx:18`, `AdminApp.tsx:457`; `usd()` hard-codes `"en-US"` (`ui.tsx:18`); `ago()` returns literal Vietnamese (`ui.tsx:10-15`); `<html lang="vi">` is fixed in four layouts.
* **Strings that must not be translated mechanically:** role / status / action code maps (`i18n`), permission labels (`PERMISSION_LABEL_VI` — name already says VI), `OUTCOME_LABEL`, `DEPLOYMENT_STATUS_LABEL`, `BLOCK_STATUS`, `CHECK_LABEL`, `DECISION_LABEL`, `HEALTH_LABEL` … these are *lookup tables* and should become one catalogue namespace each.
* **Prerequisites before extraction:** (1) fix the terminology in sections A and B so the catalogue starts clean; (2) remove raw-code fallbacks (T-02) so every value has a key; (3) decide the Vietnamese-only default (no runtime language switch exists, no `lang` state, no locale in the session).
* **Out of scope here:** text inside preview iframes rendered by `lib/schema-preview.ts` (C2-owned), server-originated messages (`ApiError.message`, audit rows), and the legacy root app.
