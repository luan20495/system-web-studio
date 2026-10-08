/**
 * Data builder model, following C3's contract: Data Source → Schema Discovery → Query → Mapping → ViewModel → Binding.
 * Everything here builds TYPED definitions of the canonical AppDefinition V2 (mirror in packages/types) and the operations that write them.
 * It never executes a query, never invents rows and never holds a credential: a data source is only referenced (`sourceRef`), and every id is local.
 * Mapping fields carry `transforms[]`; the legacy key `transform` is READ (normalised) and never written.
 */
import type { AppDefinitionV2, BindablePropMeta, DataBindingDef, DefinitionOperation, FieldMappingDef, FieldType, MappingDef, ParamDef, QueryDef, TransformDef, ViewModelDef, ViewModelFieldDef } from "@xweb/types";
import { paramRequired } from "./contract";
import { defOps } from "./definition";
import { notReady, staticReadiness, type Readiness } from "./readiness";

export const ID_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
export const TARGET_RE = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
export const FROM_RE = /^[A-Za-z0-9_][A-Za-z0-9_ -]{0,63}(\.[A-Za-z0-9_][A-Za-z0-9_ -]{0,63}){0,7}$/;
export const OPERATION_KEY_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
export const MAX_TRANSFORMS = 8;

export type DataStepId = "source" | "discovery" | "query" | "mapping" | "viewModel" | "binding" | "public";
export const DATA_STEPS: readonly { id: DataStepId; label: string; help: string }[] = [
  { id: "source", label: "Nguồn dữ liệu", help: "Nguồn do quản trị viên cấp cho ứng dụng. Khóa kết nối không bao giờ hiện ở đây." },
  { id: "discovery", label: "Khám phá cấu trúc", help: "Xem bảng/API và các cột mà nguồn cho phép." },
  { id: "query", label: "Truy vấn", help: "Chọn một thao tác đã được duyệt của nguồn. Không nhập SQL hay địa chỉ." },
  { id: "mapping", label: "Ánh xạ", help: "Đổi cột của nguồn thành các trường dễ dùng." },
  { id: "viewModel", label: "ViewModel", help: "Hình dạng dữ liệu mà giao diện nhận." },
  { id: "binding", label: "Gắn vào thành phần", help: "Chọn thành phần và thuộc tính sẽ hiển thị dữ liệu này." },
  { id: "public", label: "Dữ liệu công khai", help: "Trang công khai chỉ chạy các truy vấn ĐỌC bạn đã bật “Công khai”. Gắn thuộc tính của trang vào các truy vấn đó." },
];

/** readiness per step: the document can only describe the flow; sources/discovery/preview need the Data Platform to be wired to HTTP */
export function stepReadiness(step: DataStepId, definitionOps: Readiness, managementAvailable = false): Readiness {
  switch (step) {
    case "source": return managementAvailable ? { state: "AVAILABLE" } : staticReadiness("DATA_SOURCES");
    case "discovery": return staticReadiness("SCHEMA_DISCOVERY");
    default: return definitionOps;
  }
}

export function uniqueId(prefix: string, existing: readonly string[]): string {
  let n = 1; const taken = new Set(existing);
  while (taken.has(`${prefix}${n}`)) n += 1;
  return `${prefix}${n}`;
}

// ------------------------------------------------------------------------------------------------------------------- transforms
export const SIMPLE_TRANSFORMS: readonly { type: string; label: string }[] = [
  { type: "toString", label: "Chuyển thành chữ" }, { type: "toNumber", label: "Chuyển thành số" }, { type: "trim", label: "Bỏ khoảng trắng đầu/cuối" },
  { type: "lower", label: "Chữ thường" }, { type: "upper", label: "Chữ HOA" },
];
const LABELS: Record<string, string> = Object.fromEntries(SIMPLE_TRANSFORMS.map((t) => [t.type, t.label]));
Object.assign(LABELS, { toBoolean: "Chuyển thành đúng/sai", date: "Định dạng ngày", enumMap: "Đổi giá trị theo bảng", join: "Ghép nhiều cột", split: "Tách chuỗi", formula: "Công thức" });
export const describeTransform = (t: TransformDef): string => LABELS[t.type] ?? t.type;
/**
 * Safe READ of a mapping field's transforms: a field stored without `transforms` (undefined / null), with the LEGACY `transform` (one object or an array), or with garbage reads as a list — it never throws.
 * Nothing is written back from here: an untouched mapping keeps exactly what the server stored; an edited field is rebuilt with the canonical `transforms[]`.
 */
export function transformsOf(f: { transforms?: unknown; transform?: unknown } | null | undefined): TransformDef[] {
  const src = f?.transforms !== undefined && f?.transforms !== null ? f.transforms : f?.transform;
  if (Array.isArray(src)) return src.filter((t): t is TransformDef => !!t && typeof t === "object" && typeof (t as TransformDef).type === "string");
  return src && typeof src === "object" && typeof (src as TransformDef).type === "string" ? [src as TransformDef] : [];
}
/** the fields of a stored mapping; a mapping without `fields` reads as having none */
export const fieldsOf = (m: { fields?: unknown } | null | undefined): FieldMappingDef[] => (Array.isArray(m?.fields) ? (m!.fields as FieldMappingDef[]).filter((f) => !!f && typeof f === "object") : []);
/** "price [toNumber → …], name" — the one-line summary shown for a mapping (Data panel list, Inspector) */
export const mappingNote = (m: { fields?: unknown } | null | undefined): string => fieldsOf(m).map((f) => { const t = transformsOf(f); return `${f.to}${t.length ? ` [${t.map(describeTransform).join(" → ")}]` : ""}`; }).join(", ");
/** only the parameterless transforms can be added from the UI; the others are kept exactly as stored and shown read-only */
export const isSimpleTransform = (t: TransformDef): boolean => Object.keys(t).length === 1 && t.type in LABELS && SIMPLE_TRANSFORMS.some((s) => s.type === t.type);

/**
 * Read side of the canonical shape. A field may arrive with the LEGACY key `transform` (one object, or an array): it becomes `transforms[]`.
 * A field carrying both keys is ambiguous (the server rejects it too) and is reported, not merged.
 */
export function normalizeField(raw: Record<string, unknown>): { field: FieldMappingDef; error?: string } {
  const { transform, transforms, ...rest } = raw as { transform?: unknown; transforms?: unknown };
  let list: TransformDef[] = [];
  let error: string | undefined;
  if (transform !== undefined && transforms !== undefined) error = "Trường có cả `transform` (cũ) và `transforms` (chuẩn); hãy giữ một.";
  const src = transforms !== undefined ? transforms : transform;
  if (Array.isArray(src)) list = src as TransformDef[];
  else if (src && typeof src === "object") list = [src as TransformDef];
  const field = { ...(rest as object), transforms: list } as FieldMappingDef;
  return error ? { field, error } : { field };
}
export function normalizeMapping(raw: Record<string, unknown>): { mapping: MappingDef; errors: string[] } {
  const errors: string[] = [];
  const fields = (Array.isArray(raw.fields) ? raw.fields : []).map((f, i) => { const r = normalizeField(f as Record<string, unknown>); if (r.error) errors.push(`fields[${i}]: ${r.error}`); return r.field; });
  return { mapping: { ...(raw as object), fields } as MappingDef, errors };
}

export function addTransform(f: FieldMappingDef, type: string): FieldMappingDef | { error: string } {
  if (!SIMPLE_TRANSFORMS.some((t) => t.type === type)) return { error: "Biến đổi này chưa tạo được từ giao diện." };
  const cur = transformsOf(f);
  if (cur.length >= MAX_TRANSFORMS) return { error: `Tối đa ${MAX_TRANSFORMS} biến đổi cho một trường.` };
  return { ...f, transforms: [...cur, { type }] };
}
export const removeTransform = (f: FieldMappingDef, index: number): FieldMappingDef => ({ ...f, transforms: transformsOf(f).filter((_, i) => i !== index) });
export function moveTransform(f: FieldMappingDef, from: number, to: number): FieldMappingDef {
  const cur = transformsOf(f);
  if (from < 0 || from >= cur.length || to < 0 || to >= cur.length || from === to) return f;
  const next = cur.slice(); const [t] = next.splice(from, 1); next.splice(to, 0, t);
  return { ...f, transforms: next };
}

// ------------------------------------------------------------------------------------------------------------------- builders
export function newParam(name = ""): ParamDef { return { name, type: "STRING" }; }
/** `required` is TRUE when absent (C3 default). We never write `required: true` redundantly, only an explicit false. */
export function setParamRequired(p: ParamDef, required: boolean): ParamDef { const { required: _r, ...rest } = p; void _r; return required ? rest : { ...rest, required: false }; }

export type QueryDraft = { name: string; dataSourceRef: string; mode: "READ" | "WRITE"; operationKey: string; params: ParamDef[]; maxRows?: number; public?: boolean };
export function checkQuery(d: QueryDraft, doc: AppDefinitionV2): string[] {
  const e: string[] = [];
  if (!d.name.trim()) e.push("Hãy đặt tên cho truy vấn.");
  if (!(doc.dataSources ?? []).some((s) => s.id === d.dataSourceRef)) e.push("Hãy chọn nguồn dữ liệu.");
  if (!OPERATION_KEY_RE.test(d.operationKey)) e.push("Thao tác phải là mã đã được duyệt (chữ, số, dấu . _ -), không phải câu SQL hay địa chỉ.");
  const names = d.params.map((p) => p.name);
  if (names.some((n) => !TARGET_RE.test(n))) e.push("Tên tham số chỉ gồm chữ, số, gạch dưới và bắt đầu bằng chữ.");
  if (new Set(names).size !== names.length) e.push("Tên tham số bị trùng.");
  if (d.maxRows !== undefined && (!Number.isInteger(d.maxRows) || d.maxRows < 1)) e.push("Số dòng tối đa phải là số nguyên dương.");
  if (d.public && d.mode !== "READ") e.push("Chỉ truy vấn đọc mới được công khai.");
  return e;
}
export function buildQuery(d: QueryDraft, doc: AppDefinitionV2): QueryDef {
  const id = uniqueId("q", (doc.queries ?? []).map((q) => q.id));
  return { id, name: d.name.trim(), dataSourceRef: d.dataSourceRef, mode: d.mode, operationKey: d.operationKey, params: d.params, ...(d.maxRows ? { maxRows: d.maxRows } : {}), ...(d.public && d.mode === "READ" ? { public: true } : {}) };
}

export function checkMapping(fields: FieldMappingDef[]): string[] {
  const e: string[] = [];
  if (!fields.length) e.push("Cần ít nhất một trường.");
  for (const f of fields) {
    if (!TARGET_RE.test(f.to)) e.push(`Tên trường “${f.to}” không hợp lệ (chữ, số, gạch dưới; bắt đầu bằng chữ).`);
    if (f.from != null && !FROM_RE.test(f.from)) e.push(`Cột nguồn “${f.from}” không hợp lệ.`);
    if ((f.from == null || f.from === "") && !transformsOf(f).length && f.default === undefined) e.push(`Trường “${f.to}” cần cột nguồn, biến đổi hoặc giá trị mặc định.`);
    if (transformsOf(f).length > MAX_TRANSFORMS) e.push(`Trường “${f.to}” có quá ${MAX_TRANSFORMS} biến đổi.`);
  }
  const to = fields.map((f) => f.to);
  if (new Set(to).size !== to.length) e.push("Có hai trường trùng tên đích.");
  return e;
}
export function buildMapping(name: string, queryRef: string, fields: FieldMappingDef[], doc: AppDefinitionV2, errorPolicy: MappingDef["errorPolicy"] = "NULL_FIELD"): MappingDef {
  return { id: uniqueId("m", (doc.mappings ?? []).map((m) => m.id)), name: name.trim() || undefined, queryRef, errorPolicy, fields: fields.map((f) => ({ ...f, from: f.from || undefined })) } as MappingDef;
}

/** best-effort type of a mapped field from its last transform; never claims more than the transforms say */
export function fieldTypeOf(f: FieldMappingDef): FieldType {
  const list = transformsOf(f); const last = list[list.length - 1]?.type;
  return last === "toNumber" ? "NUMBER" : last === "toBoolean" ? "BOOLEAN" : last === "date" ? "DATE" : "STRING";
}
export function viewModelFromMapping(m: MappingDef, doc: AppDefinitionV2, name: string, cardinality: "SINGLE" | "LIST" = "LIST"): ViewModelDef {
  const fields: ViewModelFieldDef[] = fieldsOf(m).map((f) => ({ name: f.to, type: fieldTypeOf(f) }));
  return { id: uniqueId("vm", (doc.viewModels ?? []).map((v) => v.id)), name: name.trim() || undefined, queryRef: m.queryRef, mappingRef: m.id, cardinality, fields };
}

/** what a bindable prop expects vs what the ViewModel offers (a LIST prop wants item fields such as id/name/description/image) */
export function bindingCompatibility(prop: BindablePropMeta, vm: ViewModelDef): { ok: boolean; missing: string[]; message: string } {
  if (prop.cardinality === "LIST" && (vm.cardinality ?? "LIST") !== "LIST") return { ok: false, missing: [], message: "Thuộc tính này nhận danh sách, nhưng ViewModel chỉ có một bản ghi." };
  if (prop.cardinality === "SINGLE" && vm.cardinality === "LIST") return { ok: false, missing: [], message: "Thuộc tính này nhận một giá trị, nhưng ViewModel là danh sách." };
  const have = new Set(vm.fields.map((f) => f.name));
  const missing = prop.itemFields.filter((f) => !have.has(f));
  return missing.length
    ? { ok: false, missing, message: `ViewModel chưa có trường: ${missing.join(", ")}.` }
    : { ok: true, missing: [], message: "Khớp." };
}
export function buildBinding(sectionId: string, prop: string, viewModelRef: string, doc: AppDefinitionV2): DataBindingDef | { error: string } {
  if ((doc.dataBindings ?? []).some((b) => b.sectionId === sectionId && b.prop === prop)) return { error: "Thuộc tính này đã được gắn dữ liệu." };
  return { id: uniqueId("b", (doc.dataBindings ?? []).map((b) => b.id)), sectionId, prop, viewModelRef };
}

/** all operations of one pass through the wizard, in dependency order (a commit validates the final document, so order is for readability) */
export function planDataFlow(parts: { query?: QueryDef; mapping?: MappingDef; viewModel?: ViewModelDef; binding?: DataBindingDef }): { ops: DefinitionOperation[]; summary: string } {
  const ops: DefinitionOperation[] = [];
  if (parts.query) ops.push(defOps.add("queries", parts.query));
  if (parts.mapping) ops.push(defOps.add("mappings", parts.mapping));
  if (parts.viewModel) ops.push(defOps.add("viewModels", parts.viewModel));
  if (parts.binding) ops.push(defOps.add("dataBindings", parts.binding));
  return { ops, summary: "Gắn dữ liệu vào ứng dụng" };
}

// ------------------------------------------------------------------------------------------------------------------- UI states
export type ViewState =
  | { kind: "loading" } | { kind: "empty" } | { kind: "error"; message: string } | { kind: "success"; rows: number } | { kind: "not-ready"; reason: string };

/** the four states a bound component shows in the inspector: loading, empty, error, success; or NOT_READY when no server can answer */
export function viewStateOf(ready: Readiness, result: { loading?: boolean; error?: string; rows?: unknown[] } | null): ViewState {
  if (ready.state === "NOT_READY") return { kind: "not-ready", reason: ready.reason };
  if (ready.state === "ERROR") return { kind: "error", message: ready.message };
  if (ready.state === "LOADING" || result?.loading) return { kind: "loading" };
  if (result?.error) return { kind: "error", message: result.error };
  if (!result?.rows) return { kind: "not-ready", reason: "Chưa có kết quả để hiển thị." };
  return result.rows.length === 0 ? { kind: "empty" } : { kind: "success", rows: result.rows.length };
}
export const VIEW_STATE_TEXT: Record<ViewState["kind"], string> = { loading: "Đang tải dữ liệu…", empty: "Không có dữ liệu", error: "Không tải được dữ liệu", success: "Có dữ liệu", "not-ready": "Chưa sẵn sàng" };
export { paramRequired, notReady };
