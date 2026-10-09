/**
 * PAGE_SCHEMA public data, V1 — the pure rules shared by the slot editor, the query editor, the binding UI, the publish dialog and the tests.
 * No React, no fetch, no state machine of its own.
 *
 * PROVENANCE (a MIRROR, advisory: the server is the authority and refuses at publish/commit):
 *  - slot rules ........ C2 SchemaOperation / DATA_SOURCE_SLOT, fix/c2-v3 @ c1e0df5 (docs/parallel/c2/HANDOFF_C5_PAGE_SCHEMA_DATA.md)
 *  - `public` rule ..... AppDefinitionValidator `queries[i].public` + `PublicQueries.of` (public && READ, document order)
 *  - bindable props .... workers/render/page-runtime.ts SCALAR / LIST, ID / PROP regexes, MAX_DISTINCT_QUERIES, `resolveBindings`
 *  - runtime states .... PAGE_RUNTIME_JS: `data-xw-state` loading-config | not-ready | loading-data | ready | error (+ `data-xw-detail`)
 * tests/builder/publicdata.test.ts keeps this file honest against fixtures copied from C2's own tests.
 *
 * What this module never does: invent a sourceRef / credential / URL, infer `public` from visibility, binding or datasource type, or treat a
 * WRITE query as publicly runnable. Public V1 is READ-only: there is no public action, mutation or workflow concept here on purpose.
 */
import type { AppDefinitionV2, DataBindingDef, DataSourceDef, DefinitionOperation, QueryDef } from "@xweb/types";
import { defOps, allSections } from "./definition";
import { uniqueId } from "./dataFlow";

export type PublicIssue = { path: string; code: string; message: string };
const list = <T,>(v: T[] | undefined): T[] => v ?? [];

// ------------------------------------------------------------------------------------------------------------------- limits (C2)
/** ^[a-z0-9][a-z0-9-]{0,63}$ — a slot id, a section id, a query id and a binding id as the runtime accepts them (NOT the looser ID_RE of the builder). */
export const PUBLIC_ID_RE = /^[a-z0-9][a-z0-9-]{0,63}$/;
export const SLOT_TYPE_RE = /^[a-z][a-z0-9-]{0,31}$/;
export const PROP_RE = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
export const MAX_DISTINCT_QUERIES = 8;
export const MAX_SLOT_NAME = 120;
export const MAX_SLOT_DESCRIPTION = 500;

/** Props of the registered components that a published page can bind in V1. Anything else is refused at publish (never ignored). */
export const BINDABLE_SCALAR: Readonly<Record<string, readonly string[]>> = {
  Navbar: ["brand"], Hero: ["eyebrow", "title", "description"], ProductGrid: ["heading"], TechnologySection: ["heading", "body"],
  ComparisonBlock: ["heading"], Testimonials: ["heading"], ContactForm: ["heading"], Footer: ["text"],
};
export const BINDABLE_LIST: Readonly<Record<string, readonly string[]>> = { ProductGrid: ["items"], Testimonials: ["items"] };

export type BindableProp = { prop: string; kind: "text" | "list" };
export function bindablePropsOf(componentType: string): BindableProp[] {
  return [
    ...(BINDABLE_SCALAR[componentType] ?? []).map((prop): BindableProp => ({ prop, kind: "text" })),
    ...(BINDABLE_LIST[componentType] ?? []).map((prop): BindableProp => ({ prop, kind: "list" })),
  ];
}
export const isBindable = (componentType: string, prop: string): boolean => bindablePropsOf(componentType).some((p) => p.prop === prop);

// ------------------------------------------------------------------------------------------------------------------- slots
export type SlotDraft = { id: string; name?: string; type: string; description?: string };

/** `mode` absent = READ (C3 / C2 default) */
export const modeOf = (q: Pick<QueryDef, "mode">): "READ" | "WRITE" => q.mode ?? "READ";

/**
 * Validates a slot for ADD (`editing` undefined) or UPDATE (`editing` = the stored slot). A slot is `{id, name?, type, description?}` and NOTHING else:
 * the editor cannot carry sourceRef / credential / URL, and `extraKeys` lets a caller (or a test) prove that nothing else is being sent.
 */
export function validateSlot(draft: SlotDraft & Record<string, unknown>, doc: AppDefinitionV2, editing?: DataSourceDef): PublicIssue[] {
  const out: PublicIssue[] = [];
  const add = (path: string, code: string, message: string) => out.push({ path, code, message });
  const id = typeof draft.id === "string" ? draft.id : "";
  if (!editing) {
    if (!PUBLIC_ID_RE.test(id)) add("id", "SLOT_ID_INVALID", "Mã khe chỉ gồm chữ thường, số và dấu gạch ngang (bắt đầu bằng chữ hoặc số, tối đa 64 ký tự).");
    else if (list(doc.dataSources).some((s) => s.id === id)) add("id", "SLOT_ID_DUPLICATE", `Mã khe “${id}” đã tồn tại.`);
  }
  const type = typeof draft.type === "string" ? draft.type : "";
  if (!(editing?.sourceRef)) {
    if (!SLOT_TYPE_RE.test(type)) add("type", "SLOT_TYPE_INVALID", "Loại nguồn gồm chữ thường, số, dấu gạch ngang (bắt đầu bằng chữ, tối đa 32 ký tự), ví dụ: postgres.");
  } else if (type !== editing.type) add("type", "SLOT_TYPE_LOCKED", "Khe đã được cấp nguồn thật nên không đổi được loại.");
  if (draft.name !== undefined && (typeof draft.name !== "string" || draft.name.length > MAX_SLOT_NAME)) add("name", "SLOT_NAME_INVALID", `Tên khe tối đa ${MAX_SLOT_NAME} ký tự.`);
  if (draft.description !== undefined && (typeof draft.description !== "string" || draft.description.length > MAX_SLOT_DESCRIPTION)) add("description", "SLOT_DESCRIPTION_INVALID", `Mô tả tối đa ${MAX_SLOT_DESCRIPTION} ký tự.`);
  for (const k of Object.keys(draft)) if (!["id", "name", "type", "description"].includes(k)) add(k, "SLOT_FIELD_NOT_ALLOWED", `Khe không được mang trường “${k}” (không có sourceRef, khóa, địa chỉ hay SQL).`);
  return out;
}

/** every dependant that makes REMOVE_DATA_SOURCE fail with 422: the server does not cascade, so the UI says so first */
export function slotDependants(doc: AppDefinitionV2, slotId: string): { queries: QueryDef[]; actions: string[]; permissions: string[] } {
  return {
    queries: list(doc.queries).filter((q) => q.dataSourceRef === slotId),
    actions: list(doc.actions).filter((a) => a.dataSourceRef === slotId).map((a) => a.name || a.id),
    permissions: list(doc.permissions).filter((p) => p.resourceType === "DATA_SOURCE" && p.resourceRef === slotId).map((p) => p.name || p.id),
  };
}
export function validateSlotRemoval(doc: AppDefinitionV2, slotId: string): PublicIssue[] {
  const d = slotDependants(doc, slotId);
  const out: PublicIssue[] = [];
  d.queries.forEach((q) => out.push({ path: `queries.${q.id}.dataSourceRef`, code: "SLOT_IN_USE", message: `Truy vấn “${q.name || q.id}” đang dùng khe này.` }));
  d.actions.forEach((a) => out.push({ path: `actions.${a}.dataSourceRef`, code: "SLOT_IN_USE", message: `Hành động “${a}” đang dùng khe này.` }));
  d.permissions.forEach((p) => out.push({ path: `permissions.${p}.resourceRef`, code: "SLOT_IN_USE", message: `Quyền “${p}” đang trỏ tới khe này.` }));
  return out;
}

/** the three slot operations; a draft's other keys are dropped here as well (defence in depth: sourceRef can never be written from the UI) */
export const slotOps = {
  add: (d: SlotDraft): DefinitionOperation => defOps.add("dataSources", pickSlot(d)),
  /** `null` clears name / description (C2); `type` is only sent when it is being changed */
  update: (id: string, patch: { name?: string | null; description?: string | null; type?: string }): DefinitionOperation => defOps.update("dataSources", id, patch),
  remove: (id: string): DefinitionOperation => defOps.remove("dataSources", id),
};
function pickSlot(d: SlotDraft): SlotDraft {
  const o: SlotDraft = { id: d.id, type: d.type };
  if (d.name?.trim()) o.name = d.name.trim();
  if (d.description?.trim()) o.description = d.description.trim();
  return o;
}
/** the diff of an edit as an UPDATE patch: only changed fields; an emptied name/description becomes null (clear) */
export function slotPatch(before: DataSourceDef, after: { name?: string; description?: string; type?: string }): Record<string, string | null> {
  const p: Record<string, string | null> = {};
  const norm = (v: string | undefined) => (v ?? "").trim();
  if (norm(after.name) !== norm(before.name)) p.name = norm(after.name) || null;
  if (norm(after.description) !== norm(before.description)) p.description = norm(after.description) || null;
  if (after.type !== undefined && !before.sourceRef && after.type !== before.type) p.type = after.type;
  return p;
}

// ------------------------------------------------------------------------------------------------------------------- public queries
/** true only for a READ query (a WRITE query can never be public) */
export const isPublicEligibleQuery = (q: Pick<QueryDef, "mode">): boolean => modeOf(q) === "READ";
/** the allow-list rule of a release: `public === true` AND READ (mirror of PublicQueries.of). `public` is never inferred. */
export const isPublicQuery = (q: QueryDef): boolean => q.public === true && isPublicEligibleQuery(q);

/** `public` on one query: READ true/false valid; WRITE false valid; WRITE true INVALID (shown, never silently fixed); a non-boolean is invalid */
export function validatePublicQuery(q: QueryDef, path = "queries[0]"): PublicIssue[] {
  const raw = (q as { public?: unknown }).public;
  if (raw === undefined || raw === null || raw === false) return [];
  if (typeof raw !== "boolean") return [{ path: `${path}.public`, code: "PUBLIC_NOT_BOOLEAN", message: "“Công khai” phải là đúng/sai." }];
  if (!isPublicEligibleQuery(q)) return [{ path: `${path}.public`, code: "PUBLIC_WRITE_QUERY", message: `Chỉ truy vấn ĐỌC mới được công khai; “${q.name || q.id}” là truy vấn ghi.` }];
  return [];
}
export function validatePublicQueries(doc: AppDefinitionV2): PublicIssue[] {
  return list(doc.queries).flatMap((q, i) => validatePublicQuery(q, `queries[${i}]`));
}

/** UPDATE_QUERY patch for the toggle; `false` is written explicitly (so the stored intent is visible), never inferred */
export function setPublicOp(q: QueryDef, value: boolean): DefinitionOperation | { error: string } {
  if (value && !isPublicEligibleQuery(q)) return { error: "Chỉ truy vấn đọc mới được công khai." };
  return defOps.update("queries", q.id, { public: value });
}

// ------------------------------------------------------------------------------------------------------------------- bindings
/** the local id of the query a binding calls: directly, through its view model's queryRef, or through the view model's mapping (as resolveBindings does) */
export function queryOfBinding(b: Pick<DataBindingDef, "queryRef" | "viewModelRef">, doc: AppDefinitionV2): string | undefined {
  if (b.queryRef) return b.queryRef;
  if (!b.viewModelRef) return undefined;
  const vm = list(doc.viewModels).find((v) => v.id === b.viewModelRef);
  if (!vm) return undefined;
  return vm.queryRef || list(doc.mappings).find((m) => m.id === vm.mappingRef)?.queryRef || undefined;
}

export type BindingView = { binding: DataBindingDef; component?: string; query?: QueryDef; slotId?: string; slot?: DataSourceDef; kind?: "text" | "list"; issues: PublicIssue[] };

/** Mirror of `resolveBindings` for ONE binding: every refusal of the publish step, with the same reason, found while editing. */
export function validateBinding(b: DataBindingDef, doc: AppDefinitionV2, path = "dataBindings[0]"): BindingView {
  const issues: PublicIssue[] = [];
  const add = (field: string, code: string, message: string) => issues.push({ path: field ? `${path}.${field}` : path, code, message });
  const view: BindingView = { binding: b, issues };
  const section = allSections(doc).find((s) => s.id === b.sectionId);
  if (!PUBLIC_ID_RE.test(b.id ?? "")) add("id", "BINDING_ID_INVALID", `Mã gắn dữ liệu “${b.id}” không hợp lệ.`);
  if (!PUBLIC_ID_RE.test(b.sectionId ?? "")) add("sectionId", "SECTION_ID_INVALID", `Mã thành phần “${b.sectionId}” không hợp lệ.`);
  if (!PROP_RE.test(b.prop ?? "")) add("prop", "PROP_INVALID", `Thuộc tính “${b.prop}” không hợp lệ.`);
  if (!section) add("sectionId", "SECTION_MISSING", `Thành phần “${b.sectionId}” không còn trên trang.`);
  else {
    view.component = section.type;
    const kind = bindablePropsOf(section.type).find((p) => p.prop === b.prop)?.kind;
    if (!kind) add("prop", "PROP_NOT_BINDABLE", `${section.type}.${b.prop} chưa gắn được dữ liệu công khai (V1 chỉ hỗ trợ chữ và danh sách của vài thành phần).`);
    else view.kind = kind;
  }
  if (b.viewModelRef && b.queryRef) add("", "BINDING_TWO_SOURCES", "Gắn dữ liệu cần đúng một nguồn: truy vấn hoặc ViewModel.");
  if (b.viewModelRef && !list(doc.viewModels).some((v) => v.id === b.viewModelRef)) add("viewModelRef", "VIEWMODEL_MISSING", `ViewModel “${b.viewModelRef}” không tồn tại.`);
  const qid = queryOfBinding(b, doc);
  if (!qid) add("queryRef", "QUERY_NONE", "Gắn dữ liệu chưa chọn truy vấn (trực tiếp hoặc qua ViewModel).");
  else if (!PUBLIC_ID_RE.test(qid)) add("queryRef", "QUERY_ID_INVALID", `Mã truy vấn “${qid}” không hợp lệ cho trang công khai.`);
  else {
    const q = list(doc.queries).find((x) => x.id === qid);
    if (!q) add("queryRef", "QUERY_MISSING", `Truy vấn “${qid}” không tồn tại.`);
    else {
      view.query = q; view.slotId = q.dataSourceRef; view.slot = list(doc.dataSources).find((s) => s.id === q.dataSourceRef);
      if (!view.slot) add("queryRef", "SLOT_MISSING", `Khe dữ liệu “${q.dataSourceRef}” của truy vấn không tồn tại.`);
      if (q.public !== true) add("queryRef", "QUERY_NOT_PUBLIC", `Truy vấn “${q.name || q.id}” chưa công khai: bật “Công khai” hoặc bỏ gắn dữ liệu.`);
      if (!isPublicEligibleQuery(q)) add("queryRef", "QUERY_NOT_READ", `Truy vấn “${q.name || q.id}” là truy vấn ghi, không thể công khai.`);
    }
  }
  return view;
}

export function validateBindings(doc: AppDefinitionV2): BindingView[] {
  const seen = new Set<string>();
  const views = list(doc.dataBindings).map((b, i) => validateBinding(b, doc, `dataBindings[${i}]`));
  views.forEach((v) => {
    const k = `${v.binding.sectionId}#${v.binding.prop}`;
    if (seen.has(k)) v.issues.push({ path: `dataBindings.${v.binding.id}`, code: "BINDING_DUPLICATE", message: `Thuộc tính “${v.binding.prop}” của thành phần này đã được gắn dữ liệu.` });
    seen.add(k);
  });
  const distinct = new Set(views.map((v) => v.query?.id).filter((x): x is string => !!x));
  if (distinct.size > MAX_DISTINCT_QUERIES && views[0]) views[0].issues.push({ path: "dataBindings", code: "TOO_MANY_QUERIES", message: `Một trang dùng tối đa ${MAX_DISTINCT_QUERIES} truy vấn khác nhau; hiện có ${distinct.size}.` });
  return views;
}

/** A binding written by the public-data editor: section + prop → query (a LOCAL id). The slot is derived from the query and is never stored; nothing physical can be written. */
export function buildQueryBinding(sectionId: string, prop: string, queryRef: string, doc: AppDefinitionV2): DataBindingDef | { error: string } {
  if (list(doc.dataBindings).some((b) => b.sectionId === sectionId && b.prop === prop)) return { error: "Thuộc tính này đã được gắn dữ liệu. Hãy đổi truy vấn của gắn kết hiện có hoặc gỡ nó trước." };
  const b: DataBindingDef = { id: uniqueId("b", list(doc.dataBindings).map((x) => x.id)), sectionId, prop, queryRef };
  const bad = validateBinding(b, doc).issues[0];
  return bad ? { error: bad.message } : b;
}
/** changing the query of an existing binding: only `queryRef` is sent (a viewModelRef/mappingRef is cleared with null so exactly one source remains) */
export function rebindOp(b: DataBindingDef, queryRef: string, doc: AppDefinitionV2): DefinitionOperation | { error: string } {
  const next: DataBindingDef = { id: b.id, sectionId: b.sectionId, prop: b.prop, queryRef };
  const bad = validateBinding(next, doc).issues[0];
  if (bad) return { error: bad.message };
  return defOps.update("dataBindings", b.id, { queryRef, ...(b.viewModelRef !== undefined ? { viewModelRef: null } : {}), ...(b.mappingRef !== undefined ? { mappingRef: null } : {}) });
}

/** can this prop be offered for binding to this query? (the picker uses it so it never offers what the publish step refuses) */
export function queryChoicesFor(doc: AppDefinitionV2): { query: QueryDef; slot?: DataSourceDef; public: boolean; reason?: string }[] {
  return list(doc.queries).map((q) => ({
    query: q, slot: list(doc.dataSources).find((s) => s.id === q.dataSourceRef), public: q.public === true,
    reason: !isPublicEligibleQuery(q) ? "Truy vấn ghi không thể công khai" : q.public !== true ? "Chưa công khai" : undefined,
  }));
}

// ------------------------------------------------------------------------------------------------------------------- publish confirmation
export type PublicQueryInfo = { id: string; name: string; slotId: string; slotName: string; boundBy: string[] };
/** what becomes public with a release of THIS draft: the queries of `PublicQueries.of`, in document order, with their slot and the places that use them */
export function collectPublicQueries(doc: AppDefinitionV2): PublicQueryInfo[] {
  const bindings = list(doc.dataBindings);
  return list(doc.queries).filter(isPublicQuery).map((q) => ({
    id: q.id, name: q.name || q.id, slotId: q.dataSourceRef,
    slotName: list(doc.dataSources).find((s) => s.id === q.dataSourceRef)?.name || q.dataSourceRef,
    boundBy: bindings.filter((b) => queryOfBinding(b, doc) === q.id).map((b) => `${b.sectionId}.${b.prop}`),
  }));
}
export const publicQueryIds = (doc: AppDefinitionV2): string[] => collectPublicQueries(doc).map((q) => q.id);

/**
 * The approval the publish dialog asks for. Required ONLY when the release would make a query public; a draft without public queries gets no warning
 * and no checkbox. Nothing here is sent to `POST /publish` (release contract unchanged): the acknowledgement is a local confirmation (review M1).
 */
export type PublishApproval =
  | { required: false; queries: [] }
  | { required: true; queries: PublicQueryInfo[]; slots: string[]; warning: string; unboundQueries: string[] };
export function publishApproval(doc: AppDefinitionV2): PublishApproval {
  const queries = collectPublicQueries(doc);
  if (!queries.length) return { required: false, queries: [] };
  return {
    required: true, queries,
    slots: [...new Set(queries.map((q) => q.slotName))],
    unboundQueries: queries.filter((q) => !q.boundBy.length).map((q) => q.id),
    warning: "Bất kỳ ai mở được trang này đều chạy được các truy vấn dưới đây (chỉ đọc, không cần đăng nhập). Chỉ xuất bản khi dữ liệu này được phép công khai.",
  };
}
/** blockers of the publish step that the editor can already see (a persisted WRITE+public query, a binding the render worker would refuse) */
export function publicDataBlockers(doc: AppDefinitionV2): PublicIssue[] {
  return [...validatePublicQueries(doc), ...validateBindings(doc).flatMap((v) => v.issues)];
}
/** the server's `PUBLIC_QUERIES` event text → ids ("Public queries of this release (…): a, b"); [] when the text has none */
export function parsePublicQueriesEvent(message: string | undefined | null): string[] {
  if (!message) return [];
  const i = message.lastIndexOf("):");
  if (i < 0) return [];
  return message.slice(i + 2).split(",").map((s) => s.trim()).filter(Boolean);
}
/** what C5 announced vs what the server froze: any difference is shown, never hidden */
export function diffAnnounced(announced: readonly string[], frozen: readonly string[]): { onlyAnnounced: string[]; onlyFrozen: string[] } {
  return { onlyAnnounced: announced.filter((x) => !frozen.includes(x)), onlyFrozen: frozen.filter((x) => !announced.includes(x)) };
}

// ------------------------------------------------------------------------------------------------------------------- runtime states (C2 names)
export const PAGE_RUNTIME_STATES = ["loading-config", "not-ready", "loading-data", "ready", "error"] as const;
export type PageRuntimeState = (typeof PAGE_RUNTIME_STATES)[number];
export const BOUND_ELEMENT_STATES = ["ready", "empty", "error"] as const;
export const RUNTIME_STATE_TEXT: Record<PageRuntimeState, string> = {
  "loading-config": "Đang tải cấu hình", "not-ready": "Chưa sẵn sàng", "loading-data": "Đang tải dữ liệu", ready: "Đã có dữ liệu", error: "Lỗi dữ liệu",
};
/** `data-xw-detail` codes of C2 → what a person can do about it */
export const RUNTIME_DETAIL_TEXT: Record<string, string> = {
  "api-base-missing": "Cấu hình chưa có địa chỉ dữ liệu (apiBase). Trang riêng tư không có địa chỉ dữ liệu công khai; hãy xuất bản công khai.",
  "api-base-invalid": "Địa chỉ dữ liệu (apiBase) không hợp lệ.", "api-base-cross-origin": "Địa chỉ dữ liệu không cùng nguồn với trang.",
  "config-unavailable": "Không đọc được cấu hình của trang.",
  forbidden: "Máy chủ từ chối (401/403).", "not-found": "Truy vấn không nằm trong danh sách công khai của bản phát hành này, hoặc trang đã gỡ.",
  "rate-limited": "Quá nhiều yêu cầu (429).", unavailable: "Máy chủ dữ liệu chưa sẵn sàng (5xx).", timeout: "Quá thời gian chờ (15 giây).", network: "Mất kết nối mạng.",
  "invalid-response": "Phản hồi không đúng dạng.", "field-missing": "Dữ liệu thiếu trường mà thành phần cần.", "template-missing": "Thiếu mẫu hiển thị.",
  "query-missing": "Thiếu truy vấn.", "too-many-queries": "Trang dùng quá nhiều truy vấn.",
};
export type RuntimeStateView = { state: PageRuntimeState; label: string; busy: boolean; details: { code: string; query?: string; text: string }[] };
/** reads what the published runtime wrote (`data-xw-state`, `data-xw-detail`); for `error` the detail is `<queryId>:<code>,…` */
export function describeRuntimeState(state: string | null | undefined, detail?: string | null): RuntimeStateView | null {
  if (!state || !(PAGE_RUNTIME_STATES as readonly string[]).includes(state)) return null;
  const s = state as PageRuntimeState;
  const details = (detail ?? "").split(",").map((x) => x.trim()).filter(Boolean).map((x) => {
    const [a, b] = x.includes(":") ? [x.slice(0, x.indexOf(":")), x.slice(x.indexOf(":") + 1)] : [undefined, x];
    return { code: b, query: a, text: RUNTIME_DETAIL_TEXT[b] ?? (/^http-\d+$/.test(b) ? `Máy chủ trả mã ${b.slice(5)}.` : "Lỗi không xác định.") };
  });
  return { state: s, label: RUNTIME_STATE_TEXT[s], busy: s === "loading-config" || s === "loading-data", details };
}

/**
 * What the Studio can say about a draft BEFORE a published page exists: the same five vocabulary words, derived only from the document and the site info.
 * It never shows rows and never fakes the ones a visitor would get (the preview is not data-aware: review M5).
 */
export type PublicReadiness =
  | { state: "none"; label: string }
  | { state: "not-ready"; label: string; reasons: string[] }
  | { state: "ready-to-publish"; label: string; queries: string[] };
export function publicDataReadiness(doc: AppDefinitionV2, opts: { visibility?: string | null } = {}): PublicReadiness {
  const bindings = validateBindings(doc);
  if (!bindings.length && !collectPublicQueries(doc).length) return { state: "none", label: "Trang không dùng dữ liệu công khai" };
  const reasons = [...validatePublicQueries(doc).map((i) => i.message), ...bindings.flatMap((v) => v.issues.map((i) => i.message))];
  if (bindings.length && opts.visibility && opts.visibility !== "PUBLIC") reasons.push("Trang chưa xuất bản công khai: không có địa chỉ dữ liệu (apiBase) nên khách chưa thấy dữ liệu.");
  if (reasons.length) return { state: "not-ready", label: RUNTIME_STATE_TEXT["not-ready"], reasons };
  return { state: "ready-to-publish", label: "Sẵn sàng xuất bản", queries: publicQueryIds(doc) };
}
