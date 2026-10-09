# S3 glossary — the words of the product (proposal, wave 1c)

| | |
|---|---|
| Owner | C5-S3 (text library, `packages/i18n`) |
| Status | **PROPOSAL.** Everything marked **DECISION** needs the product owner. Until they decide, the proposal below is what the library and the guards implement; changing a decision is a one-line edit in `packages/i18n` plus a lower or equal guard baseline. |
| Evidence | `S3-text-and-terminology.md` (audit: T-01 … T-10, section B.1 canonical list, B.2 / B.3 string lists) |
| Code | `packages/i18n/src/{brand,roles,labels,text}.ts` · guards `tests/builder/text-guard.test.ts` |
| Terms of the guided data flow | `features/studio/builder/core/dataWording.ts` (S1). This glossary is the authority for the TERMS; that file keeps the sentences of the flow. A unit test (section 9) fails if it uses a banned term. |

Rule zero: **a person never sees a code.** Enums, roles, ids, env names, flags, HTTP codes and counters are mapped to words (section 7) or kept out of the page (shown only in a "Chi tiết kỹ thuật" disclosure or a `title`).

---

## 1. Product and portal names (M-063)

| Where | Name |
|---|---|
| Product | **Xweb** |
| Portals | **Xweb Platform** · **Quản trị công ty** (Admin) · **Xweb Studio** |
| Retired, must not appear in the UI | AI Software Factory · Company Builder Studio · Admin Console · Builder Studio |

`BRAND` (`@xweb/i18n`) is the single source; `PORTAL_TEXT` and `PORTAL_LABEL` (`@xweb/permissions`) agree with it (a unit test compares them). A sidebar brand shows the portal name, never a fourth name. Browser-tab titles: `BRAND.title`.

**DECISION D-1.** Confirm "Xweb" as the product name and the three portal names. (The repository, `PORTAL_LABEL` and the app layouts already say Xweb; the sidebar / login picker said something else.) If the product owner wants a tagline (for example for the login page), it goes into `BRAND` as one more field, not into a component.

## 2. Roles (M-062)

One table, `ROLE_LABEL` (and a one-line `ROLE_HINT`), typed `satisfies Record<RoleCode, string>`:

| Code | Label |
|---|---|
| OWNER | Chủ sở hữu |
| WORKSPACE_ADMIN | Quản trị workspace |
| EDITOR | Người chỉnh sửa |
| PUBLISHER | Người xuất bản |
| VIEWER | Người xem |
| TENANT_ADMIN | Quản trị công ty |
| MEMBER | Thành viên |
| SYSTEM_ADMIN | Quản trị hệ thống |

Four screens used three different names for the same role (EDITOR: "Biên tập viên" / "Biên tập" / "Người chỉnh sửa"; VIEWER: "Người xem" / "Chỉ xem"; WORKSPACE_ADMIN: "Quản trị không gian làm việc" / "Quản trị workspace"). A unit test forbids the old competing names in the table.

**DECISION D-2.** EDITOR: "Người chỉnh sửa" (proposal; it was already in the shared package) or "Biên tập viên" (the admin tables). Pick one; the library will carry it everywhere.

## 3. Canonical terms (T-05, B.1)

| Say | Never say | Notes |
|---|---|---|
| **ứng dụng** | project, dự án | a website is one *loại ứng dụng*. "Mã ứng dụng" for ids. |
| **workspace** | không gian làm việc | **DECISION D-3.** Loan word, capital W as a UI noun, lower case in running text. If the product prefers the translation, use "không gian làm việc" everywhere (long). Never both. |
| **công ty** | tenant | "Mã công ty" = slug. |
| **cơ cấu tổ chức**, **đơn vị**, **phòng ban** | | *Cơ cấu tổ chức* = the screen; *đơn vị* = a node; *phòng ban* only for the cost-report grouping. |
| **người dùng** / **nhân viên** / **tài khoản** | | *Người dùng* in platform-wide lists; *Nhân viên* in a company directory; *Tài khoản* = the login identity and its state. |
| **thành viên**, **quản trị viên**, **chủ sở hữu** | | person = quản trị viên; scope = role table above. |
| **quyền** | permission | permission codes are never shown. |
| **thành phần**, **thư viện thành phần** | component, registry | "Component Registry" becomes "Thư viện thành phần". |
| **mẫu**, **khối** | template, block (English) | mẫu = page template; khối = reusable section. |
| **mô hình AI**, **nhà cung cấp**, **khóa kết nối**, **hạn mức**, **ngân sách** | model, API key, key | "Khóa chống ghi trùng" becomes "Mã chống ghi trùng" (a key is a credential; a lock is a lock). `token` stays. |
| **xuất bản** | phát hành | for the action and for "bản xuất bản". |
| **tệp**, **gói thư viện** | file, package | |
| **máy chủ** / **hệ thống** / **nền tảng** | backend, server | máy chủ = "the server refused / is not ready"; hệ thống = global scope; nền tảng = the product. |
| **kho mã**, **thay đổi**, **hợp nhất**, **bản chính** | repository, commit, merge, main | plain words (T-04). |

English that may stay: **AI, API, SSO, OIDC, SAML, SCIM, MFA, URL, DNS, HTTPS, CSV, SEO, ID, Workspace, Workflow, Connector, Token, Build, Git**. Everything else is translated.

## 4. Verbs: one verb per meaning (T-09)

| Verb | Means | Not for |
|---|---|---|
| **Xóa** | delete a record (permanent, or a soft delete that looks gone) | removing a link between two things |
| **Gỡ** | remove a relationship: a member from a workspace, a grant, a link, an unpublished page ("Gỡ trang xuống") | deleting data |
| **Lưu trữ** | archive (reversible) | |
| **Khôi phục** + object | restore an archived / deleted object or a version. The object is always named: "Khôi phục ứng dụng", "Khôi phục phiên bản" | rolling a release back |
| **Quay về bản cũ** | roll the published site back to an earlier release | **DECISION D-7**: replaces "Hoàn tác" and "Phục vụ lại bản này" for releases. "Hoàn tác" is reserved for undoing an action just taken. |
| **Hủy** | abandon an action or a dialog that may have changes | |
| **Đóng** | dismiss information | |
| **Xong** | finish a flow | |
| **Thử lại** | repeat a FAILED action | refreshing |
| **Tải lại** | refresh data | |
| **Kiểm tra lại** | re-check a status | |
| **Khóa / Mở khóa** | an account | a company ("Tạm khóa") |

Destructive buttons carry the object: "Xóa công ty", "Xóa nhà cung cấp", never a bare "Xóa" at page level. A confirm dialog states what will be lost and whether it can be undone; for secrets: "Giá trị không thể xem lại; bạn sẽ phải nhập lại." Use `confirm({ danger: true })` from `@xweb/ui` (native `confirm()` is ratcheted down, section 8).

**DECISION D-6.** Account vocabulary: accounts "Hoạt động / Bị khóa / Chờ kích hoạt"; companies "Hoạt động / Tạm khóa / Đã xóa"; switchable things "Đang bật / Đang tắt". "Vô hiệu hóa" and "Tắt tài khoản" are retired.

## 5. Modes and simulation words

| Say | For |
|---|---|
| **Chế độ dùng thử** | the builder's TEST mode (no real side effects) |
| **Chế độ mô phỏng** | no real AI / no real deployment (replaces "Chế độ thử nghiệm", "Demo", "Mock") |
| **Giả lập (cục bộ)** | the local storage provider (replaces "Mock") |

**DECISION D-5.** Two words for two features is the proposal. If the product treats them as one concept, keep only one.

## 6. Tone and typography (T-01)

* **Modern tone-mark placement**: xóa, hủy, tùy, khóa, hòa (not xoá, huỷ, tuỳ, khoá, hoà). A guard counts the old spellings and may not grow (baseline 74 occurrences on the audited tree; S1/S2 lower it as they touch strings).
* Quotes “ ” (inner ‘ ’). Ellipsis `…` (never `...`). No arrow glyphs inside button text ("Gửi ↑" becomes "Gửi" + icon).
* Numbers, dates, currency: `vi-VN` via `Intl` (the `usd()` helper is the one exception: USD is shown as reported).
* Sentence case; no trailing period on button labels; a period ends full sentences.
* No developer vocabulary: no team names, ticket ids, branch or commit ids, no "revision N", no env / flag names.

## 7. Showing a value that is a code

Every enum has a label map in `@xweb/i18n/labels.ts`, typed `satisfies Record<Union, string>` so that a member added to a union without a label fails the typecheck. Use the lookup (`severityLabel(v)`, `promptOutcomeLabel(v)`, `roleName(v)`, …) or `labelOf(map, v)`:

* a known code gives its Vietnamese label,
* an unknown / empty value gives **"Khác"** (never the raw code) and is reported once through `onUnmappedLabel` so it can be logged,
* the raw code may appear only in a `title`, a "Chi tiết kỹ thuật" disclosure or a copy button.

Open sets are the exception: audit action codes (`actionLabel`) are an open set the backend keeps growing; an unmapped audit action still prints its code (the audit page is for operators). **DECISION D-11** if the product wants "Khác" there too.

Maps provided: roles, tenant status, account state, app kind, visibility, version kind, deployment status, release operation, repository state, AI provider kind, prompt outcome, limit scope, severity, health, restore-drill result, backup state, environment, library status (components / templates / blocks), package status, code-change status, server-app status, parameter type, data operation, approver kind.

**DECISION D-9 / D-10.** The wording of the app-kind names ("Bảng điều khiển", "Công cụ nội bộ", "Ứng dụng có máy chủ") and of the environments ("Sản xuất", "Thử nghiệm", "Phát triển", "Máy cục bộ") is the proposal.

## 8. Guards (they only ratchet)

`tests/builder/text-guard.test.ts` parses the TypeScript sources (compiler API) and counts, over the user-visible strings of `features/`, `apps/` and `packages/`, each rule below. A count may not go **above** its baseline; when you fix strings, lower the baseline in the same commit (the test prints the new value). A new offence fails with the file:line of every current offender.

| Rule | What it counts | Baseline (7bff844 + 1c) |
|---|---|---|
| `internal-constant` | an env / enum / flag constant inside Vietnamese text | 15 |
| `internal-term` | backend, CSRF, sandbox, registry, MinIO, Forgejo, DataGateway, Page Schema, ViewModel, pointerVersion, apiBase, workflow_run, lockfile, runner … | 72 |
| `legacy-name` | the retired product names | 11 |
| `english-nav-word` | Components, Templates, Packages, Registry, AI Control, Mock, Demo, Self-host, Project ID … | 24 |
| `term-project` · `term-workspace-long` · `term-tenant` · `term-model` · `term-component` · `term-template` | words the glossary replaces | 15 · 14 · 2 · 29 · 34 · 2 |
| `tone-old-style` | xoá huỷ tuỳ khoá hoà | 74 |
| `revision-counter` | "revision N" | 1 |
| native dialogs | `confirm()` / `prompt()` / `alert()` that are not the shared `@xweb/ui` ones (33 call sites at the audit) | 33 |

The guards do not edit anything. S1 and S2 apply the library in their own files and lower the baselines (the 33 native dialogs, for example, disappear as call sites move to `confirm()` / `prompt()`).

## 9. Folded in: the terms of the guided data flow (S1, M-005)

Source of the sentences: `features/studio/builder/core/dataWording.ts` (`DATA_WORDS`). Terms it defines, as part of THIS glossary:

| Say (in the guided flow) | Means | Never in the main flow |
|---|---|---|
| **Dữ liệu** (panel), **Hiển thị dữ liệu trong trang** | showing real company data in the page, read-only | khe, ViewModel, ánh xạ, binding, slot |
| **Nguồn dữ liệu** | a connection an administrator set up for the workspace | postgres / connector type names except as an example |
| **Bộ dữ liệu** | the named result a page asks for | query, SQL |
| **Dữ liệu cần lấy** | the operation, named by an administrator (e.g. `products.list`); no SQL, no address | |
| **Cột** | which fields are shown | |
| **Khách xem được / Chưa công khai** | public vs private data | |
| **Nâng cao** | the seven-step wizard (source, connection, dataset, columns, public data …) keeps its own labels | |

A unit test imports `DATA_WORDS` and fails if any of its sentences uses a term from the right-hand column. The word **khe** (slot) still exists in the advanced wizard and the admin screens (18 + 39 occurrences). **DECISION D-4**: keep "khe dữ liệu" there with a one-sentence definition at first use, or choose a plainer noun (candidates: "điểm nối dữ liệu", "ô dữ liệu"). Until decided the advanced screens keep it.

## 10. Decisions needed from the product owner

| # | Question | Default in the library |
|---|---|---|
| D-1 | Product name "Xweb" and the three portal names; any tagline | Xweb · Xweb Platform · Quản trị công ty · Xweb Studio |
| D-2 | EDITOR label | Người chỉnh sửa |
| D-3 | "workspace" or "không gian làm việc" | workspace |
| D-4 | the noun for "khe dữ liệu" in the advanced wizard / admin | khe dữ liệu (kept, defined at first use) |
| D-5 | "Chế độ dùng thử" and "Chế độ mô phỏng" are two features or one | two words |
| D-6 | account / company / switch vocabularies | as in section 4 |
| D-7 | release rollback verb | Quay về bản cũ |
| D-8 | password rule shown at sign-up (6) vs activation (8) | not a wording decision: ask C1 for the server policy; the UI must show the server's rule, not its own |
| D-9 | app-kind names | section 7 |
| D-10 | environment names | section 7 |
| D-11 | audit action codes not in the map: raw code or "Khác" | raw code (operator page) |

## 11. How S1 / S2 apply it

1. Replace local role / status / outcome tables with the library maps (`roleName`, `severityLabel`, `promptOutcomeLabel`, …). Delete the local table in the same commit.
2. Replace the strings of the families in section 3 using `S3-text-and-terminology.md` B.2 (file:line lists).
3. Lower the baselines in `text-guard.test.ts` for what you removed.
4. A word you need that is not in the glossary: add it to this file in your change (one table row), then use it.
