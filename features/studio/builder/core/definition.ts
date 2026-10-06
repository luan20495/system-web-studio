/**
 * Client-side reference check of an AppDefinitionV2 document: a MIRROR of the cross-reference rules of C2's AppDefinitionValidator
 * (fix/c2-v2), used for instant feedback and for the preflight before publishing. It is advisory: the server validator is the authority and a
 * commit can still be refused (422 SCHEMA_INVALID). Messages are Vietnamese for the user; `path` uses the validator's paths so a server
 * violation and a local one point at the same field.
 */
import type { AppDefinitionV2, DefinitionOperation, DefinitionCollection, JsonValue, Section } from "@xweb/types";
import { ACTION_TYPES, CLIENT_ONLY_ACTION_TYPES, DEFINITION_COLLECTIONS, PERMISSION_CODES } from "./contract";
import { pageExists } from "./pages";

export type RefIssue = { path: string; message: string };

export function allSections(doc: AppDefinitionV2): (Section & { pageId: string })[] {
  return [
    ...doc.sections.map((s) => ({ ...s, pageId: "home" })),
    ...(doc.pages ?? []).flatMap((p) => p.sections.map((s) => ({ ...s, pageId: p.id }))),
  ];
}

const list = <T,>(v: T[] | undefined): T[] => v ?? [];

function dupes(ids: string[]): string[] { const seen = new Set<string>(); const d: string[] = []; for (const i of ids) { if (seen.has(i)) d.push(i); seen.add(i); } return d; }

/** finds a cycle in a directed graph; returns the node names of the first cycle found per start node (empty when acyclic) */
export function findCycles(graph: Map<string, string[]>): string[][] {
  const out: string[][] = []; const state = new Map<string, 1 | 2>(); const path: string[] = [];
  const visit = (n: string) => {
    state.set(n, 1); path.push(n);
    for (const next of graph.get(n) ?? []) {
      if (!graph.has(next)) continue;
      const s = state.get(next);
      if (s === 1) out.push([...path.slice(path.indexOf(next)), next]);
      else if (s === undefined) visit(next);
    }
    path.pop(); state.set(n, 2);
  };
  for (const n of graph.keys()) if (!state.has(n)) visit(n);
  return out;
}

export function validateDefinition(doc: AppDefinitionV2): RefIssue[] {
  const out: RefIssue[] = [];
  const add = (path: string, message: string) => out.push({ path, message });
  const known = (ref: string | undefined, set: Set<string>, path: string, what: string): boolean => {
    if (ref === undefined) return false;
    if (!set.has(ref)) { add(path, `Tham chiếu tới ${what} “${ref}” không tồn tại.`); return false; }
    return true;
  };
  const dataSources = new Set(list(doc.dataSources).map((d) => d.id));
  const queries = new Set(list(doc.queries).map((d) => d.id));
  const mappings = new Set(list(doc.mappings).map((d) => d.id));
  const viewModels = new Set(list(doc.viewModels).map((d) => d.id));
  const actions = new Set(list(doc.actions).map((d) => d.id));
  const workflows = new Set(list(doc.workflows).map((d) => d.id));
  const permissions = new Set(list(doc.permissions).map((d) => d.id));
  for (const [coll, ids] of [["dataSources", list(doc.dataSources).map((d) => d.id)], ["queries", list(doc.queries).map((d) => d.id)],
    ["mappings", list(doc.mappings).map((d) => d.id)], ["viewModels", list(doc.viewModels).map((d) => d.id)], ["actions", list(doc.actions).map((d) => d.id)],
    ["workflows", list(doc.workflows).map((d) => d.id)], ["permissions", list(doc.permissions).map((d) => d.id)], ["dataBindings", list(doc.dataBindings).map((d) => d.id)]] as [string, string[]][])
    for (const d of dupes(ids)) add(`${coll}`, `Mã “${d}” bị trùng.`);
  const sections = new Map(allSections(doc).map((s) => [s.id, s]));
  const queryById = new Map(list(doc.queries).map((q) => [q.id, q]));
  const mappingById = new Map(list(doc.mappings).map((m) => [m.id, m]));

  list(doc.queries).forEach((q, i) => known(q.dataSourceRef, dataSources, `queries[${i}].dataSourceRef`, "nguồn dữ liệu"));
  list(doc.mappings).forEach((m, i) => {
    known(m.queryRef, queries, `mappings[${i}].queryRef`, "truy vấn");
    for (const d of dupes(m.fields.map((f) => f.to))) add(`mappings[${i}].fields`, `Trường đích “${d}” bị trùng.`);
    // app-definition.md §3 (frozen 2026-10-06): `fields[].transforms[]` is canonical; the legacy `transform` is read-only, and both on one field is rejected.
    m.fields.forEach((f, j) => {
      const legacy = (f as unknown as { transform?: unknown }).transform;
      if (legacy !== undefined && (f as { transforms?: unknown }).transforms !== undefined) add(`mappings[${i}].fields[${j}].transform`, "Trường ánh xạ không được có đồng thời “transform” (cũ) và “transforms”.");
    });
  });
  list(doc.viewModels).forEach((vm, i) => {
    const at = `viewModels[${i}]`;
    for (const d of dupes(vm.fields.map((f) => f.name))) add(`${at}.fields`, `Trường “${d}” bị trùng.`);
    const qk = vm.queryRef !== undefined && known(vm.queryRef, queries, `${at}.queryRef`, "truy vấn");
    if (qk && queryById.get(vm.queryRef!)!.mode === "WRITE") add(`${at}.queryRef`, `ViewModel chỉ đọc dữ liệu; “${vm.queryRef}” là truy vấn ghi.`);
    const mk = vm.mappingRef !== undefined && known(vm.mappingRef, mappings, `${at}.mappingRef`, "ánh xạ");
    if (qk && mk && mappingById.get(vm.mappingRef!)!.queryRef !== vm.queryRef) add(`${at}.mappingRef`, `Ánh xạ “${vm.mappingRef}” đọc truy vấn khác với truy vấn của ViewModel.`);
    if (mk) for (const f of mappingById.get(vm.mappingRef!)!.fields) if (!vm.fields.some((x) => x.name === f.to)) add(`${at}.mappingRef`, `Ánh xạ trả về trường “${f.to}” nhưng ViewModel không có trường này.`);
  });

  list(doc.actions).forEach((a, i) => {
    const at = `actions[${i}]`;
    // action-workflow.md §2: only the 9 canonical types; RUN_QUERY / WRITE_DATA / CALL_CONNECTOR_OPERATION / SET_VALUE are aliases the server rejects.
    if (!(ACTION_TYPES as readonly string[]).includes(a.type)) add(`${at}.type`, `Loại hành động “${a.type}” không thuộc danh sách chuẩn.`);
    const qk = a.queryRef !== undefined && known(a.queryRef, queries, `${at}.queryRef`, "truy vấn");
    if (a.viewModelRef !== undefined) known(a.viewModelRef, viewModels, `${at}.viewModelRef`, "ViewModel");
    if (a.workflowRef !== undefined) known(a.workflowRef, workflows, `${at}.workflowRef`, "workflow");
    if (a.dataSourceRef !== undefined) known(a.dataSourceRef, dataSources, `${at}.dataSourceRef`, "nguồn dữ liệu");
    if (a.permissionRef !== undefined) known(a.permissionRef, permissions, `${at}.permissionRef`, "quyền");
    if (a.trigger && !sections.has(a.trigger.sectionId)) add(`${at}.trigger.sectionId`, `Thành phần “${a.trigger.sectionId}” không còn trên trang.`);
    switch (a.type) {
      case "REFRESH_QUERY":
        if (a.queryRef === undefined) add(`${at}.queryRef`, "Làm mới dữ liệu cần chọn một truy vấn đọc.");
        else if (qk && queryById.get(a.queryRef)!.mode === "WRITE") add(`${at}.queryRef`, "Làm mới dữ liệu cần truy vấn đọc, không phải truy vấn ghi.");
        break;
      case "SUBMIT_FORM": case "CREATE_RECORD": case "UPDATE_RECORD": case "DELETE_RECORD":
        if (a.queryRef === undefined) add(`${at}.queryRef`, "Hành động ghi dữ liệu cần chọn một truy vấn ghi.");
        else if (qk && (queryById.get(a.queryRef)!.mode ?? "READ") !== "WRITE") add(`${at}.queryRef`, "Hành động ghi dữ liệu cần truy vấn ghi; truy vấn này chỉ đọc.");
        break;
      case "CALL_API":
        if (a.dataSourceRef === undefined) add(`${at}.dataSourceRef`, "Gọi API cần chọn nguồn dữ liệu.");
        if (a.operationKey === undefined) add(`${at}.operationKey`, "Gọi API cần chọn một thao tác đã được duyệt.");
        break;
      case "START_WORKFLOW":
        if (a.workflowRef === undefined) add(`${at}.workflowRef`, "Cần chọn workflow để chạy.");
        break;
      case "NAVIGATE":
        if (a.pageRef === undefined) add(`${at}.pageRef`, "Chuyển trang cần chọn trang đích.");
        else if (!pageExists(doc, a.pageRef)) add(`${at}.pageRef`, `Trang đích “${a.pageRef}” không tồn tại.`);
        break;
      case "NOTIFY":
        if (a.channel === undefined) add(`${at}.channel`, "Thông báo cần chọn kênh gửi.");
        if (a.templateRef === undefined) add(`${at}.templateRef`, "Thông báo cần chọn mẫu đã được duyệt.");
        if (a.channel === "WEBHOOK" && a.endpointRef === undefined) add(`${at}.endpointRef`, "Webhook cần chọn điểm nhận đã đăng ký (không nhập URL).");
        if (a.channel !== undefined && a.channel !== "WEBHOOK" && a.endpointRef !== undefined) add(`${at}.endpointRef`, "Điểm nhận chỉ dùng cho kênh webhook.");
        break;
    }
    if (a.type !== "NOTIFY") for (const k of ["channel", "templateRef", "endpointRef"] as const) if (a[k] !== undefined) add(`${at}.${k}`, "Chỉ dùng cho hành động Thông báo.");
    if (a.type !== "NOTIFY" && list(a.recipients).length) add(`${at}.recipients`, "Người nhận chỉ dùng cho hành động Thông báo.");
    const mutating = !(CLIENT_ONLY_ACTION_TYPES as readonly string[]).includes(a.type);
    if (mutating && a.idempotency === "NONE") add(`${at}.idempotency`, "Hành động thay đổi dữ liệu không được tắt chống ghi trùng.");
    if (a.type === "START_WORKFLOW" && a.idempotency !== undefined && a.idempotency !== "REQUIRED") add(`${at}.idempotency`, "Chạy workflow bắt buộc chống ghi trùng.");
    const declared = new Set(list(a.inputs).map((x) => x.name));
    for (const k of Object.keys(a.inputMapping ?? {})) if (!declared.has(k)) add(`${at}.inputMapping.${k}`, `Giá trị đầu vào “${k}” chưa được khai báo.`);
    for (const [field, chain] of [["onSuccess", list(a.onSuccess)], ["onError", list(a.onError)]] as const) chain.forEach((ref, j) => {
      if (ref === a.id) add(`${at}.${field}[${j}]`, "Hành động không thể nối tới chính nó.");
      else known(ref, actions, `${at}.${field}[${j}]`, "hành động");
    });
  });

  list(doc.workflows).forEach((w, i) => {
    const at = `workflows[${i}]`;
    const steps = new Set(w.steps.map((s) => s.id));
    for (const d of dupes(w.steps.map((s) => s.id))) add(`${at}.steps`, `Bước “${d}” bị trùng mã.`);
    if (w.startStepId !== undefined) known(w.startStepId, steps, `${at}.startStepId`, "bước");
    const flow = new Map<string, string[]>();
    w.steps.forEach((s, j) => {
      const sp = `${at}.steps[${j}]`;
      const kind = s.kind ?? (s.actionRef !== undefined ? "ACTION" : "END");
      if (kind === "ACTION") { if (s.actionRef !== undefined) known(s.actionRef, actions, `${sp}.actionRef`, "hành động"); else add(`${sp}.actionRef`, "Bước hành động cần chọn hành động."); }
      else if (s.actionRef !== undefined) add(`${sp}.actionRef`, "Chỉ bước Hành động mới có hành động.");
      if (s.compensationActionRef !== undefined) known(s.compensationActionRef, actions, `${sp}.compensationActionRef`, "hành động");
      for (const k of ["next", "onError", "defaultNext"] as const) if (s[k] !== undefined) known(s[k], steps, `${sp}.${k}`, "bước");
      list(s.branches).forEach((b, k) => known(b.next, steps, `${sp}.branches[${k}].next`, "bước"));
      if (s.approval?.onReject !== undefined) known(s.approval.onReject, steps, `${sp}.approval.onReject`, "bước");
      if (s.approval?.onExpire !== undefined) known(s.approval.onExpire, steps, `${sp}.approval.onExpire`, "bước");
      flow.set(s.id, [s.next, s.onError, s.defaultNext, ...list(s.branches).map((b) => b.next), s.approval?.onReject, s.approval?.onExpire].filter((x): x is string => !!x));
    });
    for (const c of findCycles(flow)) add(`${at}.steps`, `Các bước tạo vòng lặp: ${c.join(" → ")}.`);
  });

  list(doc.permissions).forEach((p, i) => {
    const at = `permissions[${i}]`;
    if (!(PERMISSION_CODES as readonly string[]).includes(p.permission)) add(`${at}.permission`, `“${p.permission}” không nằm trong danh sách quyền chuẩn.`);
    const sets = { QUERY: queries, ACTION: actions, WORKFLOW: workflows, VIEW_MODEL: viewModels, DATA_SOURCE: dataSources } as const;
    known(p.resourceRef, sets[p.resourceType], `${at}.resourceRef`, "đối tượng");
  });

  const bound = new Set<string>();
  list(doc.dataBindings).forEach((b, i) => {
    const at = `dataBindings[${i}]`;
    const key = `${b.sectionId}#${b.prop}`;
    if (bound.has(key)) add(at, `Thuộc tính “${b.prop}” của thành phần này đã được gắn dữ liệu.`); bound.add(key);
    if (!sections.has(b.sectionId)) add(`${at}.sectionId`, `Thành phần “${b.sectionId}” không còn trên trang.`);
    if ((b.viewModelRef === undefined) === (b.queryRef === undefined)) add(at, "Gắn dữ liệu cần đúng một nguồn: ViewModel hoặc truy vấn.");
    if (b.viewModelRef !== undefined) known(b.viewModelRef, viewModels, `${at}.viewModelRef`, "ViewModel");
    const qk = b.queryRef !== undefined && known(b.queryRef, queries, `${at}.queryRef`, "truy vấn");
    if (qk && queryById.get(b.queryRef!)!.mode === "WRITE") add(`${at}.queryRef`, "Gắn dữ liệu chỉ đọc; đây là truy vấn ghi.");
    if (b.mappingRef !== undefined) {
      if (b.queryRef === undefined) add(`${at}.mappingRef`, "Ánh xạ chỉ dùng cùng truy vấn.");
      else if (known(b.mappingRef, mappings, `${at}.mappingRef`, "ánh xạ") && qk && mappingById.get(b.mappingRef)!.queryRef !== b.queryRef) add(`${at}.mappingRef`, "Ánh xạ đọc truy vấn khác.");
    }
  });

  const graph = new Map<string, string[]>();
  for (const a of list(doc.actions)) graph.set(`action:${a.id}`, a.workflowRef ? [`workflow:${a.workflowRef}`] : []);
  for (const w of list(doc.workflows)) graph.set(`workflow:${w.id}`, w.steps.flatMap((s) => (s.actionRef ? [`action:${s.actionRef}`] : [])));
  for (const c of findCycles(graph)) add("actions", `Vòng tham chiếu hành động ↔ workflow: ${c.join(" → ")}.`);
  return out;
}

// -------------------------------------------------------------------------------------------------------------- operations
const FAMILY = DEFINITION_COLLECTIONS;
const asJson = (v: unknown) => v as Record<string, JsonValue>;

/** typed V2 operation builders: the ONLY way the Builder writes data/action/workflow definitions (one funnel: applyOps → PATCH /schema) */
export const defOps = {
  add: (collection: DefinitionCollection, definition: { id: string }): DefinitionOperation => ({ type: `ADD_${FAMILY[collection]}` as DefinitionOperation["type"], definitionId: definition.id, definition: asJson(definition) }),
  update: (collection: DefinitionCollection, id: string, patch: Record<string, unknown>): DefinitionOperation => ({ type: `UPDATE_${FAMILY[collection]}` as DefinitionOperation["type"], definitionId: id, definition: asJson(patch) as Record<string, JsonValue | null> }),
  remove: (collection: DefinitionCollection, id: string): DefinitionOperation => ({ type: `REMOVE_${FAMILY[collection]}` as DefinitionOperation["type"], definitionId: id }),
  updateTheme: (patch: Record<string, unknown>): DefinitionOperation => ({ type: "UPDATE_THEME", definition: asJson(patch) as Record<string, JsonValue | null> }),
  updatePublishConfig: (patch: Record<string, unknown>): DefinitionOperation => ({ type: "UPDATE_PUBLISH_CONFIG", definition: asJson(patch) as Record<string, JsonValue | null> }),
};

/** what still points at a definition (REMOVE does not cascade; the server reports dangling references at commit, so the UI says it first) */
export function usersOf(doc: AppDefinitionV2, collection: DefinitionCollection, id: string): string[] {
  const out: string[] = [];
  const name = (x: { id: string; name?: string }) => x.name || x.id;
  switch (collection) {
    case "queries":
      list(doc.mappings).filter((m) => m.queryRef === id).forEach((m) => out.push(`ánh xạ ${name(m)}`));
      list(doc.viewModels).filter((v) => v.queryRef === id).forEach((v) => out.push(`ViewModel ${name(v)}`));
      list(doc.actions).filter((a) => a.queryRef === id).forEach((a) => out.push(`hành động ${name(a)}`));
      list(doc.dataBindings).filter((b) => b.queryRef === id).forEach((b) => out.push(`gắn dữ liệu ${b.sectionId}.${b.prop}`));
      break;
    case "mappings":
      list(doc.viewModels).filter((v) => v.mappingRef === id).forEach((v) => out.push(`ViewModel ${name(v)}`));
      list(doc.dataBindings).filter((b) => b.mappingRef === id).forEach((b) => out.push(`gắn dữ liệu ${b.sectionId}.${b.prop}`));
      break;
    case "viewModels":
      list(doc.dataBindings).filter((b) => b.viewModelRef === id).forEach((b) => out.push(`gắn dữ liệu ${b.sectionId}.${b.prop}`));
      list(doc.actions).filter((a) => a.viewModelRef === id).forEach((a) => out.push(`hành động ${name(a)}`));
      break;
    case "actions":
      list(doc.workflows).filter((w) => w.steps.some((s) => s.actionRef === id || s.compensationActionRef === id)).forEach((w) => out.push(`workflow ${name(w)}`));
      list(doc.actions).filter((a) => list(a.onSuccess).includes(id) || list(a.onError).includes(id)).forEach((a) => out.push(`hành động ${name(a)}`));
      break;
    case "workflows":
      list(doc.actions).filter((a) => a.workflowRef === id).forEach((a) => out.push(`hành động ${name(a)}`));
      break;
    default: break;
  }
  return out;
}
export { ACTION_TYPES };
