# S4 real-stack baseline (REAL STACK, private builds, HEAD bd23f89)

**REAL STACK (private builds).** The three apps were built from `agent/c5-web @ bd23f89` into private dist dirs (`.next-check-audit`) with `API_PROXY_TARGET` at the e2e stack backend `http://127.0.0.1:47080` (through a pass-through shim that rewrites only the Origin header, see `tests/browser/README.md`), served on free ports through the owned-process library and stopped afterwards. The backend was neither started nor stopped. Data was created through the product API by `scripts/ui-audit.mjs` (one NEW tenant "Công ty Cổ phần Ánh Dương", a company admin, a workspace admin, a project, 25 employees with long Vietnamese names, one name of 100+ characters). Chrome 155.0.8059.40, headless, 2026-10-10. Nothing is intercepted or faked in this run.

Command: `AUDIT_NO_SHOTS=1 node scripts/ui-audit.mjs --private-api http://127.0.0.1:47080 --out DIR` (a second 1440 px run for Platform + Admin kept screenshots).

## 1. Coverage

* Route inventory (read from the SOURCE, `features/admin/console/sections.tsx` registry + the Studio route switch and project views): **87 routes, 0 not visited** (the run exits 1 otherwise).
* **1 422 visits** (route x width x dialog / drawer / inspector state), 117 distinct routes and states, **158 visits at each of the 9 widths** 1920, 1440, 1280, 1024, 768, 600, 430, 390, 360. Per portal: Platform 378, Admin 738 (company admin and system admin), Studio 306.

## 2. Counts per check (visits that show the finding)

| Check | Count | Verdict |
|---|---:|---|
| horizontal overflow | **0** | clean on real data (long names, 25 employees) |
| unreachable controls (outside the viewport / clipped) | **0** | |
| visible label not in the accessible name | **0** | |
| no focus ring on the first 10 Tab stops | **0** | |
| focus fully covered by a sticky / fixed element | **0** | |
| axe critical + serious / moderate + minor (all WCAG 2.0 to 2.2 A / AA + best practice) | **0 / 0** | |
| console errors / uncaught exceptions | **0** | |
| failing API calls (4xx / 5xx) | **0** | |
| blank pages | **0** | |
| controls covered by another element | **12** (5 distinct defects, section 3) | real |
| targets under 24 px | **16** (2 distinct, section 3) | real |
| page without an `h1` | 153 (1 route: `studio/projects/:id/design`, 17 of its states) | real, known (S1) |
| text leaves under 11 px | 9 visits, 1 route: Studio home, 10 leaves | real; the leaves were not identified (S3 tokens: a 11 px floor?) |
| arrow / key glyph characters in text (`→ ↑ ↓ ⌘ ●`) | 151 visits, 24 routes | intended icons-in-text, not defects |

By width, the covered / small findings are concentrated at **600 px** (8 + 8 of 12 + 16), then 768, 1024, 360 (1 to 2 each); 0 at 1920, 1440, 1280, 430 and 390.

## 3. Triage: real defect vs artefact (what real data adds to the harness results)

| # | Finding on the real stack | Width | Verdict | Owner | Reproduced in the harness? (`node scripts/ui-repro.mjs`) |
|---|---|---:|---|---|---|
| R1 | **Studio header at 600 px**: the search field "Tìm ứng dụng" is squeezed to 22 x 36 px and covered by the workspace picker (`div.row`) on every Studio screen (`/studio`, `/projects`, `/new`, `/templates`, `/components`, `/activity`, `/site-access`, `/nope`: 8 visits) | 600 | **real defect**, triggered by a long workspace name ("Kinh doanh và Chăm sóc khách hàng") on a workspace-admin account | **S1** (StudioApp header) with S3 (header css) | **YES**: studio harness, persona with `MEMBER_MANAGE` + workspace name "Kinh doanh và Chăm sóc khách hàng" at 600 px, `MEASURE` reports the same `covered by div.row` and `22x36` |
| R2 | **Studio AI view at 768 px**: starter chip "Rút gọn tiêu đề hero" is covered by `div.composer` (the "AI hiện chưa được quản trị" state) on `/projects/:id` and `/projects/:id/ai` | 768 | **real defect** | **S1** | **YES**: `/studio/projects/p1/ai` at 768 in the studio harness |
| R3 | **Platform AI-provider dialog at 360 px**: the "Nâng cao" button is covered by the sticky dialog footer ("Hủy / Lưu") | 360 | **real defect** (a control under the footer cannot be reached without scrolling the dialog; check it is scrollable) | **S2** (dialog) / **S3** (Modal footer) | **YES**: `/platform/ai`, "Thêm nhà cung cấp" at 360 in the admin harness |
| R4 | **Builder at 1024 px, rail "Thành phần"**: "Kéo Danh sách sản phẩm …" and "Thêm Danh sách sản phẩm …" are covered by `aside.bx-right` (the properties panel row) | 1024 | **real, data-dependent**: not reproduced with the 6 + 18 component fixture; at 1024 the properties panel is a full-width bottom row (y 744 to 900) and the component list of the real registry reaches it | **S1** (builder layout) with S3 (builder.css) | NO (the harness registry is too short; reproduce with the real registry list) |
| R5 | `/studio/activity` link "mở ứng dụng" 86 x 21 px | 1920 | minor: a block link 21 px high (target < 24 px) | S1 | not tried |
| R6 | `studio/projects/:id/design` has no `h1` (17 states x 9 widths) | all | **real, known** (already in the ledger as the Builder heading) | S1 | YES (harness matrix, same row) |
| R7 | 10 text leaves under 11 px on Studio home | all | real, small; leaves not identified | S3 / S1 | not tried |

No artefact class of the harness (`component-metadata 404`, skipped personas) appears here: on the real stack the Studio screens make no failing call. Both admin personas (company admin and system admin) visit every Admin route; the refusals of the other persona are by design and raise no finding.

What real data did NOT break (compare the harness state matrix): no overflow with a 100+ character employee name and 25 employees, no raw Java / SQL text, no console error, no axe finding at any width. The long-content failures of the harness matrix (Studio header overflow +96 / +434 px) are not reproduced here because the real long name was in the workspace, not the display name.

## 4. What could not be reached on the real backend, and why

* **Organization routes (`/admin/organization`) are NOT_READY**: the real backend has no organization API; the screen says so ("Cơ cấu tổ chức chưa sẵn sàng: Máy chủ chưa hỗ trợ cơ cấu tổ chức"). The tree, move / delete dialogs, unit types and the unit filter of the employee list were therefore audited only on the harness (state matrix, `org.spec`), never on real data.
* **Employees** (`/admin/employees`) ran on the member-list fallback: the unit and position columns are left out and the unit filter is disabled ("Chưa sẵn sàng").
* **Costs** (`/platform/costs`) shows no data: no price is set and no usage exists (0 GiB, 0 h, $0, "đủ đơn giá" next to three "chưa có đơn giá" lines, an observation for S2: a total of $0 flagged "complete" while no price exists). The by-department and by-workspace cards are empty states.
* Groups, sharing and BYOK are "coming" sections (no backend), reached as placeholders.
* Anything that needs a SECOND user in a flow (activation by the invited user, sharing between users), AI generation (no provider key on the stack), publish to the sites gateway and code projects were not exercised by the audit: it opens screens and dialogs, it does not run flows. The keyboard walkthrough (`scripts/ui-keyboard.mjs`) is harness-only (it asserts the request the fake recorded); it was not pointed at the real stack.
* Real 403 / 404 / 500 error shapes were not provoked: the real stack answered 2xx everywhere the personas went (0 failing API calls). The raw-error-text and permission-denied states are covered by the harness state matrix only.

## 5. Comparison with the harness baselines

| | Harness (`S4-matrix-baseline.md`) | Real stack (this file) |
|---|---|---|
| visits / routes | 1 422 / 117 | 1 422 / 117 |
| overflow, unreachable, label-in-name, focus ring, focus obscured, axe | 0 | 0 |
| console errors, blank | 0 | 0 |
| failing API | 90 (harness fixture 404) | 0 |
| covered controls | 4 | 12 (new on real data: R1, R2, R3, R4) |
| targets < 24 px | 9 | 16 (R1, R5) |
| no h1 | 153 | 153 |

Reproduce: `node tests/browser/build-harness.mjs && node scripts/ui-repro.mjs` (HARNESS) for R1 to R4; the real run needs the backend at `http://127.0.0.1:47080`.
