# M-005 (S1-006 + S1-027) - Data-binding simplification: PROPOSAL (not implemented)

Author: C5-S1. Base: `agent/c5-web` @ `a73ae3d`. Status: **for C5-L review; nothing in `features/` has been changed for this item.**
Scope: the Builder's "Dữ liệu" rail, the Inspector "Dữ liệu" tab, and the stale copy in `DataSourcesPanel`. Out of scope: Admin data-source screens (`features/admin`), the C3 management API, the C2 runtime.

## 1. What the person sees today (facts, with file:line)

The "Dữ liệu" rail is a 7-tab wizard (`builder/core/dataFlow.ts:19-28`, `builder/DataWizard.tsx`):

| # | Tab | What it asks | Vocabulary that is internal |
|---|---|---|---|
| 1 | Nguồn dữ liệu | create / test / enable a workspace source, store a credential, link a source to a "khe" per TEST/LIVE, then (below, `SlotEditor`) add "khe" | khe (slot), TEST / LIVE, `dataSources[]` |
| 2 | Khám phá cấu trúc | nothing: always "Chưa sẵn sàng" (`DataGateway.discoverSchema` leaks, S1-020) | - |
| 3 | Truy vấn | name, source slot, READ / WRITE, "Mã thao tác đã duyệt" (free text, e.g. `products.list`), params with types, max rows, "Công khai" | operation key, param types, READ/WRITE |
| 4 | Ánh xạ | query, "Khi một giá trị lỗi", fields `cột nguồn -> tên trường` + transforms | mapping, transforms |
| 5 | ViewModel | mapping, name, "Dạng dữ liệu" (Danh sách / Một bản ghi) | ViewModel, cardinality |
| 6 | Gắn vào thành phần | component, prop, ViewModel -> `DataBinding{viewModelRef}` | binding |
| 7 | Dữ liệu công khai | readiness, public toggle per query, and a SECOND binding form "Gắn dữ liệu vào trang" -> `DataBinding{queryRef}` (`builder/PublicDataPanels.tsx:157-226`) | public query, binding |

Plus a third entry: Inspector > Dữ liệu > "Thiết lập luồng dữ liệu…" (`Inspector.tsx:94`) which just opens tab 1.

Measured/derived cost of "show this list from my data" = pick a source + create a slot + 4 forms (query, mapping, view model, binding) + a public toggle = **>= 10 clicks and 5 saves**, each save being a PATCH of the document. Two buttons are both called "Gắn dữ liệu" (tab 6 and tab 7) and do different things (ViewModel path vs query path). A half-filled form is lost when the rail tab changes (S1-056, queued separately).

S1-027: `DataSourcesPanel.tsx:213` says "chưa có thao tác nào để thêm khe từ Studio … sẽ mở khi máy chủ hỗ trợ" while `SlotEditor` (same tab, `DataWizard.tsx:100`) adds slots. The paragraph is only true for the Admin host of the panel.

## 2. Constraints that decide what is possible (read from the contracts, not assumed)

- Published pages bind **only to queries**: `workers/render/page-runtime.ts:43-63` resolves a binding to a query id directly (`queryRef`), or through `viewModelRef -> vm.queryRef`, or through the VM's mapping's `queryRef`. So the ViewModel / Mapping are NOT needed for a page to show data; they are an optional "shape" layer (and the Studio uses them for `bindingCompatibility` checks). Whether the server applies a mapping to the rows is a C3 matter (`data-runtime.md` §GatewayQuery has `mappingRef`) and **must be confirmed with C3 before the UI promises that renaming a column works on a published page**.
- Public V1 = READ queries with `public:true` only, bindable props are an allow-list (`publicData.ts:34-39` `BINDABLE_SCALAR/LIST`), max 8 distinct queries per page, and the publish acknowledgement stays (`PublishModal`).
- Ids/limits (`PUBLIC_ID_RE`, slot type regex) are validated client-side by `core/publicData.ts` and again by the server.
- All writes go through the single funnel `ctx.commit` -> `defOps.*` -> `PATCH /schema` with the existing operation types. **No new operation, endpoint or field is needed by this proposal.**
- Operations of a connector ("operationKey") are not listable from the contract today (discovery is NOT_READY), so the first version keeps a text field; a picker is a request to C3, not something to fake.

## 3. Proposed model in plain Vietnamese

Four ideas instead of seven tabs. The user never types "khe", "ViewModel", "ánh xạ", "truy vấn", "binding".

| Old term | New wording | Meaning |
|---|---|---|
| Nguồn dữ liệu (workspace) | **Nguồn dữ liệu** (unchanged) | where the company's data lives; managed by admins |
| Khe dữ liệu | **Kết nối của ứng dụng** (hidden; created automatically) | the app's named link to a source |
| Truy vấn (+ mã thao tác) | **Bộ dữ liệu** | "lấy gì từ nguồn nào" |
| Ánh xạ + ViewModel | **Cột hiển thị** (optional) | which columns, with which names |
| Gắn vào thành phần / Gắn dữ liệu | **Hiển thị ở đâu** | which component + property shows it |
| Công khai (query.public) | **Ai xem được** | "Khách chưa đăng nhập xem được" |

## 4. Proposed flow

The rail tab "Dữ liệu" opens ONE panel with two parts: **Đã kết nối** (what exists, as sentences) and **Thêm dữ liệu** (a 4-step guided form). Everything today stays under **Nâng cao**.

### 4.1 Panel: "Dữ liệu của ứng dụng" (text wireframe)

```
Dữ liệu
Hiển thị dữ liệu thật của công ty trong trang. Dữ liệu chỉ được đọc, không sửa.

ĐÃ KẾT NỐI
 ● Danh sách sản phẩm › Danh sách (items)   ←  Bộ dữ liệu “Sản phẩm”      [Khách xem được]   [Sửa] [Gỡ]
 ○ Chưa có dữ liệu nào được hiển thị trong trang.                          (empty state)

[ + Hiển thị dữ liệu trong trang ]

▸ Nâng cao   (nguồn, kết nối, bộ dữ liệu, cột, dữ liệu công khai - như hiện nay)
```
"Sửa" reopens the guided form prefilled; "Gỡ" = the existing remove-binding dialog (the dataset stays, same text as today).

### 4.2 Guided form "Hiển thị dữ liệu trong trang" (one screen, 4 numbered sections, one **Lưu** at the end = ONE commit)

1. **Hiển thị ở đâu?** - Thành phần (select, preselected when opened from the Inspector or when a section is selected) and Thuộc tính (select, label from `propLabel`, "danh sách" / "một giá trị"). Only props the published runtime can bind (`bindablePropsOf`) are offered, with the reason shown for the others ("Thuộc tính này chưa hiển thị được dữ liệu thật").
2. **Lấy dữ liệu từ đâu?** - Nguồn dữ liệu (select of the workspace sources the user may view; if the app has no connection to it yet, the form creates the hidden "kết nối" = slot with a derived id, in the same commit), "Dữ liệu cần lấy" (the approved operation, text field with the existing hint "Do quản trị viên đặt tên, ví dụ products.list. Không nhập SQL hay địa chỉ."), "Số dòng tối đa" (optional). Tham số (params) are collapsed under "Tham số của dữ liệu (nếu có)".
3. **Hiển thị cột nào?** - default "Dùng nguyên các cột nguồn trả về" (emits a direct `queryRef` binding: no mapping, no view model). Option "Chọn / đổi tên cột" reveals a small table `Cột nguồn -> Tên hiển thị`, pre-filled with the field names the property reads (`LIST_ROW_FIELDS`, e.g. name, description) and then emits mapping + view model + `viewModelRef` binding (today's tabs 4-6, auto-named, `viewModelFromMapping`). Because discovery is NOT_READY the column names are typed; the section says so once, plainly ("Chưa tự gợi ý được cột: nhập đúng tên cột của nguồn").
4. **Ai xem được?** - checkbox "Khách chưa đăng nhập cũng xem được dữ liệu này" (only enabled for a READ dataset, default OFF, the existing warning text; the publish dialog acknowledgement is unchanged). When ON the pre-check/readiness messages of tab 7 apply.

Validation is the existing pure code (`checkQuery`, `checkMapping`, `bindingCompatibility`, `buildQueryBinding`, `validateBinding`), run before the commit; errors are shown next to the section they belong to, not at the bottom of the panel (today `msg` is at the bottom).

What the single **Lưu** commits (one `ctx.commit(ops, summary)`, operation types that exist today): `ADD_DATA_SOURCE_SLOT` (only if needed) + `ADD queries` + (`ADD mappings` + `ADD viewModels` only when step 3 chose columns) + `ADD dataBindings` + (`UPDATE queries.public` when step 4). Order is already the dependency order of `planDataFlow` (`dataFlow.ts:160`).

### 4.3 Where the two "Gắn dữ liệu" paths go

One path in the UI: "Hiển thị ở đâu". The emitted binding is `queryRef` when no columns were customised and `viewModelRef` when they were. Both are accepted by the server and the runtime (`page-runtime.ts:43-63`). The old tab 6 (ViewModel path) and tab 7's binding form stay only inside **Nâng cao** for people who want to wire existing objects by hand; their button labels become "Gắn bộ dữ liệu có sẵn" and "Gắn truy vấn công khai" so no two buttons share a name.

### 4.4 Inspector

Tab "Dữ liệu" keeps listing properties, with the property **label** (not the raw key, S1-050) and a one-line state ("Đang hiển thị: Sản phẩm" / "Chưa gắn dữ liệu"). The button "Thiết lập luồng dữ liệu…" becomes **"Hiển thị dữ liệu…"** for a property (opens the guided form with section + property preselected) instead of dropping the user on tab 1 of the wizard.

### 4.5 What stays under "Nâng cao" (no behaviour change, only moved)

The current 7-tab wizard component as-is, collapsed: Nguồn dữ liệu (management + TEST/LIVE links + slots), Khám phá cấu trúc (NOT_READY), Truy vấn, Ánh xạ, ViewModel, Gắn vào thành phần, Dữ liệu công khai, and the "Đã khai báo (n)" list with its remove dialogs. Admin-ish work (creating a source, credentials, TEST/LIVE link) is reached from the new form with a link "Quản lý nguồn dữ liệu" that opens Nâng cao > Nguồn dữ liệu, because creating a source is a DATA_SOURCE_MANAGE action that most editors do not have (their form just offers the sources they can see, with the existing "bạn chưa có quyền" reason when none).

### 4.6 S1-027 (stale copy)

The slot paragraph in `DataSourcesPanel` is only shown when the host cannot add slots (new boolean prop `canAddSlots`, false in Admin, true in the Builder). In the Builder it is removed; the empty state becomes "Ứng dụng chưa có kết nối dữ liệu nào" with the button "Thêm kết nối" (the existing `SlotEditor` form, in Nâng cao).

## 5. Permissions and states

- `ctx.canEdit` false -> the panel shows only "Đã kết nối" (read-only) with the existing "Bạn chỉ xem được" line; no form.
- `ctx.canViewData` false -> the source select is replaced by the existing reason text; the form can still create a dataset on a slot that already has a source link, otherwise the section 2 is disabled with the reason (no request is made without DATA_SOURCE_VIEW, as today).
- Readiness: if `ctx.readiness` (definition operations) is NOT_READY the whole form is replaced by the existing StateBox (reworded without operation names, S1-020).
- Loading / error / empty / long names / double submit: one `pending` flag disables **Lưu**; failures keep the form and show the server reason inside the form (the lesson of S1-016).

## 6. What must NOT change

- C3 contracts (management API, data-runtime, connector) and C2 contracts (Page Schema, `SchemaPatchEngine`, `PageSchemaValidator`, published runtime, `workers/render/**`): no field, route, flag or operation is added or changed.
- Document shapes: `queries`, `mappings`, `viewModels`, `dataBindings`, `dataSources` remain exactly as `core/definition.ts` writes them today.
- The publish acknowledgement of public queries and its list (`PublishModal`) stay as they are.
- Nothing fakes data: no sample rows, no invented column discovery, no invented operation list.
- Security UX: credentials stay write-only (`DataSourcesPanel` untouched in behaviour).

## 7. Tests: what changes

| Existing check | Effect |
|---|---|
| `tests/builder/data.test.ts`, `publicdata.test.ts` (pure rules, 23 generated C2 cases) | unchanged; the new form reuses those functions. A new pure helper `planGuidedBinding(draft, doc)` (ops for the 3 shapes: direct, with columns, + slot) gets its own unit tests incl. dependency order and the duplicate / limit refusals. |
| `tests/browser/publicdata.spec.mjs` (43 checks, `public-harness.tsx` mounts `<DataWizard>`) | unchanged as long as `<DataWizard>` is still what "Nâng cao" mounts; only the harness entry may need to open the "Nâng cao" disclosure. |
| `tests/browser/datasources.spec.mjs` (54 checks, mounts `<DataSourcesPanel>`) | unchanged (panel behaviour does not change; `canAddSlots` defaults to the old behaviour). |
| `tests/browser/builder.spec.mjs` data-rail checks (2) | the selectors for tabs "Truy vấn" / "Ánh xạ" move under "Nâng cao"; updated. |
| `tests/e2e-real/lib/publicdata.mjs:65`, `flows/e2e-07.mjs` (real backend, drive the old forms by label: "Lưu truy vấn", `query-public`, ...) | they keep working through "Nâng cao"; a second real-backend flow for the guided form is proposed but is the E2E owner's call. |
| NEW `tests/browser/data-binding.spec.mjs` (HARNESS, NOT REAL BACKEND, on the Studio-app harness) | guided path emits ONE PATCH with the expected ops for (a) direct, (b) with columns, (c) public; Escape / backdrop do not lose a dirty form; double Lưu sends one request; read-only shows no form; the contradictory slot sentence never appears in the Builder (S1-027). |
| axe sweep | the new panel added to the screen list. |

## 8. Risks

1. **Mapping semantics (C3).** If the server does not apply mappings on a published page, "Chọn / đổi tên cột" would promise something the page does not do. Mitigation: ship step 3 as "Dùng nguyên các cột nguồn trả về" only, keep the column table behind the C3 confirmation; this does not block the rest.
2. **Auto-created slot** hides a concept the C2 validator still requires. Mitigation: it is created with the derived id and the source's type in the same commit and appears in Nâng cao; if the validator refuses, the error is shown in the form.
3. **Operation key stays free text** (no catalogue in the contract): the form remains the hardest field. A C3 request ("list approved operations of a source for the editor") is the real fix and is not in this scope.
4. **Two paths now emit different binding shapes** (`queryRef` vs `viewModelRef`); both are valid today, but the Inspector's "Đang hiển thị" sentence must resolve both (already needed by `PublicBindingsPanel` / `DataTab`).
5. **Scope creep**: Admin-side data-source UI, discovery, preview-with-data are NOT part of this.

## 9. Delivery plan (after approval)

1. Pure helper + unit tests (`planGuidedBinding`, sentence builder for "Đã kết nối").
2. New panel + guided form behind the existing rail tab; old wizard wrapped as "Nâng cao".
3. Inspector tab wording + entry point; S1-027 copy fix (`canAddSlots`).
4. New harness spec; update the 2 builder.spec selectors; run unit + builder / datasources / publicdata / release specs.
Estimated: 4 commits, no backend change, no shared-UI change (uses `Dialog`, `Field`, `Tabs`, `StateBox` of `builder/ui`).

## 10. Decisions needed from C5-L

1. Approve the 4-section single form and the "Nâng cao" disclosure.
2. Approve auto-creating the hidden slot (vs. keeping "Thêm khe" visible).
3. Decide whether step 3 (custom columns) ships in v1 or waits for the C3 mapping confirmation.
4. Wording glossary (section 3), to be shared with S4 / the wording pass (S1-021 / 022 / 024).
