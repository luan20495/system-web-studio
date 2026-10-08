/**
 * The guided "Hiển thị dữ liệu trong trang" flow (M-005), as pure rules. One form commits, in dependency order and with operation types that already exist:
 *   [ADD slot]  ->  ADD query (READ)  ->  ADD dataBinding {queryRef}
 * No new operation, field or endpoint. v1 binds DIRECTLY to the query (the page runtime resolves a binding to a query id); renaming / formatting columns
 * (mapping + view model) stays in "Nâng cao" until C3 confirms that mappings are applied on published pages (HF-C3-Q1).
 * The slot (the app's named link to a source) is created here when needed, with a derived id, so the person never types the word.
 */
import type { AppDefinitionV2, DataBindingDef, DataSourceDef, DefinitionOperation, ParamDef, QueryDef } from "@xweb/types";
import { checkQuery, buildQuery, uniqueId } from "./dataFlow";
import { defOps, allSections } from "./definition";
import { MAX_DISTINCT_QUERIES, PUBLIC_ID_RE, bindablePropsOf, queryOfBinding, slotOps, validateBinding, validateSlot } from "./publicData";
import { slugify } from "./pages";
import { DATA_WORDS } from "./dataWording";

export type GuidedSource = { kind: "slot"; slotId: string } | { kind: "new"; name: string; type: string };
export type GuidedDraft = {
  sectionId: string; prop: string; source: GuidedSource;
  datasetName: string; operationKey: string; params: ParamDef[]; maxRows?: number;
  /** "Khách chưa đăng nhập cũng xem được" (default OFF: nothing becomes public without the person asking) */
  public: boolean;
};
export type GuidedField = "section" | "prop" | "source" | "sourceName" | "sourceType" | "datasetName" | "operationKey" | "params" | "maxRows";
export type GuidedProblem = { field: GuidedField; message: string };
export type GuidedPlan =
  | { ok: true; ops: DefinitionOperation[]; summary: string; slotId: string; queryId: string; bindingId: string; slotCreated: boolean }
  | { ok: false; problems: GuidedProblem[] };

export const emptyGuidedDraft = (sectionId = "", prop = ""): GuidedDraft => ({ sectionId, prop, source: { kind: "slot", slotId: "" }, datasetName: "", operationKey: "", params: [], public: false });

/** a slot id that satisfies the runtime's id rule and is not taken: "Kho đơn hàng" -> "kho-don-hang", then "-2", "-3"… */
export function deriveSlotId(name: string, existing: readonly string[]): string {
  const base = (slugify(name) || "nguon").slice(0, 60);
  const taken = new Set(existing);
  let id = base, n = 2;
  while (taken.has(id)) id = `${base.slice(0, 58)}-${n++}`;
  return id;
}
/** the slot "type" label from a source type such as "POSTGRES" / "Rest API": lower case, letters / digits / hyphens */
export const slotTypeOf = (t: string): string => t.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 32);

const queryProblemField = (m: string): GuidedField =>
  /tên cho truy vấn/.test(m) ? "datasetName" : /nguồn dữ liệu/.test(m) ? "source" : /Thao tác/.test(m) ? "operationKey" : /tham số/i.test(m) ? "params" : /Số dòng/.test(m) ? "maxRows" : "datasetName";

export function planGuidedBinding(d: GuidedDraft, doc: AppDefinitionV2, labelOf: (type: string) => string = (t) => t): GuidedPlan {
  const problems: GuidedProblem[] = [];
  const bad = (field: GuidedField, message: string) => { problems.push({ field, message }); };
  const section = allSections(doc).find((s) => s.id === d.sectionId);
  if (!section) bad("section", "Hãy chọn thành phần.");
  else if (!bindablePropsOf(section.type).some((p) => p.prop === d.prop)) bad("prop", d.prop ? DATA_WORDS.where.notBindable : "Hãy chọn thuộc tính.");
  else if ((doc.dataBindings ?? []).some((b) => b.sectionId === d.sectionId && b.prop === d.prop)) bad("prop", "Thuộc tính này đã được gắn dữ liệu. Hãy gỡ dữ liệu cũ trước.");

  // the app's link to the source (a slot): an existing one, or a new one created in the same commit
  const slots = doc.dataSources ?? [];
  let slot: DataSourceDef | undefined; let slotCreated = false;
  if (d.source.kind === "slot") {
    const wanted = d.source.slotId; slot = slots.find((s) => s.id === wanted);
    if (!slot) bad("source", "Hãy chọn nguồn dữ liệu.");
  } else {
    const name = d.source.name.trim(), type = slotTypeOf(d.source.type);
    if (!name) bad("sourceName", "Hãy đặt tên cho nguồn.");
    const draft = { id: deriveSlotId(name, slots.map((s) => s.id)), type, ...(name ? { name } : {}) };
    const issues = validateSlot(draft, doc);
    for (const i of issues) bad(i.path === "type" ? "sourceType" : "sourceName", i.message);
    if (!issues.length && name) { slot = draft as DataSourceDef; slotCreated = true; }
  }

  const docWithSlot: AppDefinitionV2 = slot && slotCreated ? { ...doc, dataSources: [...slots, slot] } : doc;
  const queryDraft = { name: d.datasetName, dataSourceRef: slot?.id ?? "", mode: "READ" as const, operationKey: d.operationKey.trim(), params: d.params, ...(d.maxRows !== undefined ? { maxRows: d.maxRows } : {}), public: d.public };
  if (slot) for (const m of checkQuery(queryDraft, docWithSlot)) bad(queryProblemField(m), m);
  if (problems.length || !section || !slot) return { ok: false, problems };

  const query: QueryDef = buildQuery(queryDraft, docWithSlot);
  const docWithQuery: AppDefinitionV2 = { ...docWithSlot, queries: [...(doc.queries ?? []), query] };
  const binding: DataBindingDef = { id: uniqueId("b", (doc.dataBindings ?? []).map((b) => b.id)), sectionId: d.sectionId, prop: d.prop, queryRef: query.id };
  // everything the publish step would refuse about THIS binding, found now. "chưa công khai" is the person's own choice here and is shown on the row, not refused.
  for (const i of validateBinding(binding, docWithQuery).issues) if (i.code !== "QUERY_NOT_PUBLIC") bad("prop", i.message);
  const queries = new Set([...(doc.dataBindings ?? []).map((b) => queryOfBinding(b, doc)), query.id].filter((x): x is string => !!x));
  if (queries.size > MAX_DISTINCT_QUERIES) bad("datasetName", `Một trang dùng tối đa ${MAX_DISTINCT_QUERIES} bộ dữ liệu khác nhau.`);
  if (!PUBLIC_ID_RE.test(query.id)) bad("datasetName", "Mã bộ dữ liệu không hợp lệ.");
  if (problems.length) return { ok: false, problems };

  const where = `${labelOf(section.type)} › ${d.prop}`;
  const ops: DefinitionOperation[] = [...(slotCreated ? [slotOps.add({ id: slot.id, type: slot.type, ...(slot.name ? { name: slot.name } : {}) })] : []), defOps.add("queries", query), defOps.add("dataBindings", binding)];
  return { ok: true, ops, summary: DATA_WORDS.summary(query.name || query.id, where), slotId: slot.id, queryId: query.id, bindingId: binding.id, slotCreated };
}

/** one sentence per binding for the "Đã kết nối" list; resolves both the direct (queryRef) and the view-model path */
export type ConnectedRow = { id: string; sectionId: string; component: string; prop: string; dataset: string; queryId?: string; public: boolean; slotName?: string; viaViewModel: boolean };
export function connectedRows(doc: AppDefinitionV2): ConnectedRow[] {
  return (doc.dataBindings ?? []).map((b) => {
    const section = allSections(doc).find((s) => s.id === b.sectionId);
    const qid = queryOfBinding(b, doc);
    const q = (doc.queries ?? []).find((x) => x.id === qid);
    const slot = (doc.dataSources ?? []).find((s) => s.id === q?.dataSourceRef);
    return { id: b.id, sectionId: b.sectionId, component: section?.type ?? "?", prop: b.prop, dataset: q ? (q.name || q.id) : "(không tìm thấy bộ dữ liệu)", queryId: qid, public: q?.public === true && (q.mode ?? "READ") === "READ", slotName: slot ? (slot.name || slot.id) : undefined, viaViewModel: !b.queryRef && !!b.viewModelRef };
  });
}
