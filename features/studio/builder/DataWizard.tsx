"use client";
/**
 * Data builder: Source -> Discovery -> Query -> Mapping -> ViewModel -> Binding (C3 contract). Each step saves its own definition through the
 * single write funnel, so a commit is always a valid document (query before mapping before ViewModel before binding).
 * Nothing is simulated: with no server for a step the step says "Chưa sẵn sàng" and why; no sample rows are ever produced here.
 */
import { useMemo, useState } from "react";
import { ArrowDown, ArrowUp, X } from "../../../packages/ui/src/icons";
import type { FieldMappingDef, MappingDef, ParamDef, QueryDef, ViewModelDef, DataBindingDef } from "@xweb/types";
import { CARDINALITIES, FIELD_TYPES, MAPPING_ERROR_POLICIES, PARAM_TYPES, QUERY_MODES, paramRequired } from "./core/contract";
import {
  DATA_STEPS, FROM_RE, MAX_TRANSFORMS, SIMPLE_TRANSFORMS, addTransform, bindingCompatibility, buildBinding, buildMapping, buildQuery, checkMapping, checkQuery, describeTransform, fieldsOf, mappingNote, transformsOf, vmFieldsOf,
  isSimpleTransform, moveTransform, newParam, removeTransform, setParamRequired, stepReadiness, viewModelFromMapping, viewStateOf, VIEW_STATE_TEXT, type DataStepId,
} from "./core/dataFlow";
import { allSections, defOps, usersOf } from "./core/definition";
import { bindableProps } from "./core/inspector";
import { staticReadiness } from "./core/readiness";
import { DataSourcesPanel } from "./DataSourcesPanel";
import { PublicDataTab, SlotEditor } from "./PublicDataPanels";
import { Dialog, Field, Gate, StateBox, Tabs, tabPanelProps } from "./ui/primitives";
import type { DefCtx } from "./ctx";

const emptyField = (): FieldMappingDef => ({ from: "", to: "", transforms: [] });

export function DataWizard({ ctx, focus }: { ctx: DefCtx; focus?: { sectionId?: string } }) {
  const { doc } = ctx;
  const [step, setStep] = useState<DataStepId>("source");
  const [msg, setMsg] = useState<string | null>(null);
  const [qd, setQd] = useState<{ name: string; dataSourceRef: string; mode: "READ" | "WRITE"; operationKey: string; params: ParamDef[]; maxRows?: number; public?: boolean }>({ name: "", dataSourceRef: doc.dataSources?.[0]?.id ?? "", mode: "READ", operationKey: "", params: [] });
  const [queryId, setQueryId] = useState<string>(doc.queries?.[0]?.id ?? "");
  const [fields, setFields] = useState<FieldMappingDef[]>([emptyField()]);
  const [mapName, setMapName] = useState("");
  const [policy, setPolicy] = useState<MappingDef["errorPolicy"]>("NULL_FIELD");
  const [mappingId, setMappingId] = useState<string>(doc.mappings?.[0]?.id ?? "");
  const [vmName, setVmName] = useState("");
  const [card, setCard] = useState<"SINGLE" | "LIST">("LIST");
  const [vmId, setVmId] = useState<string>(doc.viewModels?.[0]?.id ?? "");
  const [bSection, setBSection] = useState(focus?.sectionId ?? "");
  const [bProp, setBProp] = useState("");
  const [removing, setRemoving] = useState<{ collection: "queries" | "mappings" | "viewModels" | "dataBindings"; id: string; name: string } | null>(null);
  const disabled = !ctx.canEdit || ctx.busy;
  const ready = (s: DataStepId) => stepReadiness(s, ctx.readiness, !!ctx.dataManagement);
  const prefix = "data-wizard";

  // the slot the query form shows: the chosen one, or the first slot when none was chosen / the chosen one is gone (a slot added in this same session must be usable without touching the select)
  const slotRef = (doc.dataSources ?? []).some((d) => d.id === qd.dataSourceRef) ? qd.dataSourceRef : doc.dataSources?.[0]?.id ?? "";
  const queries = doc.queries ?? [], mappings = doc.mappings ?? [], vms = doc.viewModels ?? [], bindings = doc.dataBindings ?? [];
  const sections = allSections(doc);
  const targetSection = sections.find((s) => s.id === bSection);
  const component = ctx.registry.find((c) => c.id === targetSection?.type);
  const props = useMemo(() => bindableProps(component, targetSection ? ctx.metadata.get(targetSection.type) : undefined), [component, targetSection, ctx.metadata]);
  const vm = vms.find((v) => v.id === vmId);
  const targetProp = props.find((p) => p.prop === bProp);
  const compat = vm && targetProp ? bindingCompatibility({ prop: targetProp.prop, cardinality: targetProp.cardinality, itemFields: targetProp.itemFields }, vm) : null;

  const done = async (ops: Parameters<DefCtx["commit"]>[0], summary: string) => { setMsg(null); return ctx.commit(ops, summary); };

  async function saveQuery() {
    const draft = { ...qd, dataSourceRef: slotRef };
    const e = checkQuery(draft, doc); if (e.length) { setMsg(e[0]); return; }
    const q: QueryDef = buildQuery(draft, doc);
    if (await done([defOps.add("queries", q)], `Thêm truy vấn ${q.name}`)) { setQueryId(q.id); setStep("mapping"); }
  }
  async function saveMapping() {
    if (!queryId) { setMsg("Hãy chọn truy vấn cho ánh xạ."); return; }
    const e = checkMapping(fields); if (e.length) { setMsg(e[0]); return; }
    const m = buildMapping(mapName, queryId, fields, doc, policy);
    if (await done([defOps.add("mappings", m)], `Thêm ánh xạ ${m.name ?? m.id}`)) { setMappingId(m.id); setStep("viewModel"); }
  }
  async function saveViewModel() {
    const m = mappings.find((x) => x.id === mappingId); if (!m) { setMsg("Hãy chọn ánh xạ."); return; }
    const v: ViewModelDef = viewModelFromMapping(m, doc, vmName, card);
    if (await done([defOps.add("viewModels", v)], `Thêm ViewModel ${v.name ?? v.id}`)) { setVmId(v.id); setStep("binding"); }
  }
  async function saveBinding() {
    if (!bSection || !bProp || !vmId) { setMsg("Hãy chọn thành phần, thuộc tính và ViewModel."); return; }
    if (compat && !compat.ok) { setMsg(compat.message); return; }
    const b = buildBinding(bSection, bProp, vmId, doc);
    if ("error" in b) { setMsg(b.error); return; }
    if (await done([defOps.add("dataBindings", b as DataBindingDef)], "Gắn dữ liệu vào thành phần")) setMsg("Đã gắn dữ liệu.");
  }
  async function doRemove() {
    if (!removing) return;
    const ok = await done([defOps.remove(removing.collection, removing.id)], `Xóa ${removing.name}`);
    if (ok) setRemoving(null);
  }

  const setField = (i: number, f: FieldMappingDef) => setFields((x) => x.map((y, j) => (j === i ? f : y)));
  const items = DATA_STEPS.map((s) => ({ id: s.id, label: s.label, badge: ready(s.id).state === "NOT_READY" ? "chưa" : undefined }));
  const cur = DATA_STEPS.find((s) => s.id === step)!;

  return (
    <div className="bx-data">
      <Tabs label="Các bước dữ liệu" idPrefix={prefix} items={items} value={step} onChange={(s) => { setMsg(null); setStep(s as DataStepId); }}/>
      <div {...tabPanelProps(prefix, step)} className="bx-tabpanel">
        <p className="hint">{cur.help}</p>

        {step === "source" ? (<>
          <DataSourcesPanel doc={doc} calls={ctx.dataManagement} canView={ctx.canViewData ?? false} viewReason={ctx.viewDataReason ?? "Bạn chưa được cấp quyền xem nguồn dữ liệu."} canManage={ctx.canManageData ?? false} manageReason={ctx.manageDataReason ?? "Bạn chưa được cấp quyền quản lý nguồn dữ liệu."} canBind={ctx.canBindData ?? false} bindReason={ctx.bindDataReason ?? "Liên kết nguồn dữ liệu cần quyền quản lý nguồn dữ liệu và quyền chỉnh sửa ứng dụng."}/>
          <SlotEditor ctx={ctx}/>
        </>) : null}

        {step === "discovery" ? <StateBox state={ready("discovery")}/> : null}

        {step === "query" ? (
          <Gate state={ready("query")}>
            {!(doc.dataSources ?? []).length ? <p className="hint" data-testid="no-slots">Ứng dụng chưa có khe dữ liệu nên chưa tạo được truy vấn. Hãy thêm khe ở bước “Nguồn dữ liệu”.</p> : (
              <form className="bx-form" onSubmit={(e) => { e.preventDefault(); void saveQuery(); }}>
                <Field label="Tên truy vấn">{(id) => <input id={id} disabled={disabled} value={qd.name} onChange={(e) => setQd({ ...qd, name: e.target.value })}/>}</Field>
                <Field label="Nguồn dữ liệu">{(id) => <select id={id} disabled={disabled} value={slotRef} onChange={(e) => setQd({ ...qd, dataSourceRef: e.target.value })}>
                  {(doc.dataSources ?? []).map((d) => <option key={d.id} value={d.id}>{d.name || d.id}</option>)}</select>}</Field>
                <Field label="Kiểu">{(id) => <select id={id} disabled={disabled} value={qd.mode} onChange={(e) => setQd({ ...qd, mode: e.target.value as "READ" | "WRITE", ...(e.target.value === "WRITE" ? { public: false } : {}) })}>{QUERY_MODES.map((m) => <option key={m} value={m}>{m === "READ" ? "Đọc dữ liệu" : "Ghi dữ liệu"}</option>)}</select>}</Field>
                <Field label="Mã thao tác đã duyệt" hint="Do quản trị viên đặt tên (ví dụ products.list). Không nhập câu SQL hay địa chỉ.">{(id) => <input id={id} disabled={disabled} value={qd.operationKey} onChange={(e) => setQd({ ...qd, operationKey: e.target.value })}/>}</Field>
                <label className="checkRow" data-testid="query-public-row"><input type="checkbox" data-testid="query-public" disabled={disabled || qd.mode !== "READ"} checked={qd.mode === "READ" && qd.public === true} onChange={(e) => setQd({ ...qd, public: e.target.checked })}/><span>Công khai (khách không cần đăng nhập cũng chạy được)</span></label>
                {qd.mode !== "READ" ? <p className="hint" data-testid="query-public-write-note">Truy vấn ghi không thể công khai.</p> : null}
                <fieldset className="bx-group"><legend>Tham số</legend>
                  {qd.params.map((p, i) => (
                    <div className="bx-row" key={i}>
                      <input aria-label={`Tên tham số ${i + 1}`} disabled={disabled} value={p.name} onChange={(e) => setQd({ ...qd, params: qd.params.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)) })}/>
                      <select aria-label={`Kiểu tham số ${i + 1}`} disabled={disabled} value={p.type} onChange={(e) => setQd({ ...qd, params: qd.params.map((x, j) => (j === i ? { ...x, type: e.target.value as ParamDef["type"] } : x)) })}>{PARAM_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}</select>
                      <label className="checkRow"><input type="checkbox" disabled={disabled} checked={paramRequired(p)} onChange={(e) => setQd({ ...qd, params: qd.params.map((x, j) => (j === i ? setParamRequired(x, e.target.checked) : x)) })}/><span>Bắt buộc</span></label>
                      {!disabled ? <button type="button" className="smallButton danger" aria-label={`Xóa tham số ${i + 1}`} onClick={() => setQd({ ...qd, params: qd.params.filter((_, j) => j !== i) })}>Xóa</button> : null}
                    </div>))}
                  {!disabled ? <button type="button" className="smallButton" onClick={() => setQd({ ...qd, params: [...qd.params, newParam()] })}>+ Thêm tham số</button> : null}
                </fieldset>
                <Field label="Số dòng tối đa (tuỳ chọn)">{(id) => <input id={id} type="number" min={1} disabled={disabled} value={qd.maxRows ?? ""} onChange={(e) => setQd({ ...qd, maxRows: e.target.value ? Number(e.target.value) : undefined })}/>}</Field>
                <StateBox state={staticReadiness("QUERY_PREVIEW")} compact/>
                <div className="bx-actions"><button type="submit" className="bx-btn primary" disabled={disabled}>Lưu truy vấn</button></div>
              </form>)}
          </Gate>
        ) : null}

        {step === "mapping" ? (
          <Gate state={ready("mapping")}>
            {!queries.length ? <p className="hint">Hãy tạo một truy vấn trước.</p> : (
              <form className="bx-form" onSubmit={(e) => { e.preventDefault(); void saveMapping(); }}>
                <Field label="Truy vấn">{(id) => <select id={id} disabled={disabled} value={queryId} onChange={(e) => setQueryId(e.target.value)}>{queries.map((q) => <option key={q.id} value={q.id}>{q.name || q.id}</option>)}</select>}</Field>
                <Field label="Tên ánh xạ">{(id) => <input id={id} disabled={disabled} value={mapName} onChange={(e) => setMapName(e.target.value)}/>}</Field>
                <Field label="Khi một giá trị lỗi">{(id) => <select id={id} disabled={disabled} value={policy} onChange={(e) => setPolicy(e.target.value as MappingDef["errorPolicy"])}>{MAPPING_ERROR_POLICIES.map((p) => <option key={p} value={p}>{p === "FAIL" ? "Báo lỗi" : p === "NULL_FIELD" ? "Để trống ô đó" : "Bỏ qua dòng"}</option>)}</select>}</Field>
                <StateBox state={ready("discovery")} compact/>
                <fieldset className="bx-group"><legend>Các trường</legend>
                  {fields.map((f, i) => (
                    <div className="bx-field-card" key={i}>
                      <div className="bx-row">
                        <input aria-label={`Cột nguồn của trường ${i + 1}`} placeholder="cột nguồn" disabled={disabled} value={f.from ?? ""} aria-invalid={!!f.from && !FROM_RE.test(f.from)} onChange={(e) => setField(i, { ...f, from: e.target.value })}/>
                        <span aria-hidden="true">→</span>
                        <input aria-label={`Tên trường ${i + 1}`} placeholder="tên trường" disabled={disabled} value={f.to} onChange={(e) => setField(i, { ...f, to: e.target.value })}/>
                        {!disabled && fields.length > 1 ? <button type="button" className="smallButton danger" aria-label={`Xóa trường ${i + 1}`} onClick={() => setFields(fields.filter((_, j) => j !== i))}>Xóa</button> : null}
                      </div>
                      <ol className="bx-chips" aria-label={`Biến đổi của trường ${f.to || i + 1}`}>
                        {transformsOf(f).map((t, k) => (
                          <li key={k} className="chip">{describeTransform(t)}{!isSimpleTransform(t) ? " (giữ nguyên)" : ""}
                            {!disabled ? <>
                              <button type="button" className="chipBtn" aria-label={`Đưa ${describeTransform(t)} lên`} disabled={k === 0} onClick={() => setField(i, moveTransform(f, k, k - 1))}><ArrowUp size={12} aria-hidden="true"/></button>
                              <button type="button" className="chipBtn" aria-label={`Đưa ${describeTransform(t)} xuống`} disabled={k === transformsOf(f).length - 1} onClick={() => setField(i, moveTransform(f, k, k + 1))}><ArrowDown size={12} aria-hidden="true"/></button>
                              <button type="button" className="chipBtn" aria-label={`Bỏ ${describeTransform(t)}`} onClick={() => setField(i, removeTransform(f, k))}><X size={12} aria-hidden="true"/></button></> : null}
                          </li>))}
                      </ol>
                      {!disabled && transformsOf(f).length < MAX_TRANSFORMS ? (
                        <select aria-label={`Thêm biến đổi cho trường ${f.to || i + 1}`} value="" onChange={(e) => { if (!e.target.value) return; const r = addTransform(f, e.target.value); if ("error" in r) setMsg(r.error); else setField(i, r); }}>
                          <option value="">+ Thêm biến đổi…</option>{SIMPLE_TRANSFORMS.map((t) => <option key={t.type} value={t.type}>{t.label}</option>)}</select>) : null}
                    </div>))}
                  {!disabled ? <button type="button" className="smallButton" onClick={() => setFields([...fields, emptyField()])}>+ Thêm trường</button> : null}
                </fieldset>
                <div className="bx-actions"><button type="submit" className="bx-btn primary" disabled={disabled}>Lưu ánh xạ</button></div>
              </form>)}
          </Gate>
        ) : null}

        {step === "viewModel" ? (
          <Gate state={ready("viewModel")}>
            {!mappings.length ? <p className="hint">Hãy tạo ánh xạ trước.</p> : (
              <form className="bx-form" onSubmit={(e) => { e.preventDefault(); void saveViewModel(); }}>
                <Field label="Ánh xạ">{(id) => <select id={id} disabled={disabled} value={mappingId} onChange={(e) => setMappingId(e.target.value)}>{mappings.map((m) => <option key={m.id} value={m.id}>{m.name || m.id}</option>)}</select>}</Field>
                <Field label="Tên ViewModel">{(id) => <input id={id} disabled={disabled} value={vmName} onChange={(e) => setVmName(e.target.value)}/>}</Field>
                <Field label="Dạng dữ liệu">{(id) => <select id={id} disabled={disabled} value={card} onChange={(e) => setCard(e.target.value as "SINGLE" | "LIST")}>{CARDINALITIES.map((c) => <option key={c} value={c}>{c === "LIST" ? "Danh sách" : "Một bản ghi"}</option>)}</select>}</Field>
                <p className="hint">Các trường lấy từ ánh xạ: {fieldsOf(mappings.find((m) => m.id === mappingId)).map((f) => `${f.to} (${viewModelFromMapping(mappings.find((m) => m.id === mappingId)!, doc, "x").fields.find((x) => x.name === f.to)?.type ?? FIELD_TYPES[0]})`).join(", ") || "—"}.</p>
                <div className="bx-actions"><button type="submit" className="bx-btn primary" disabled={disabled}>Lưu ViewModel</button></div>
              </form>)}
          </Gate>
        ) : null}

        {step === "binding" ? (
          <Gate state={ready("binding")}>
            {!vms.length ? <p className="hint">Hãy tạo ViewModel trước.</p> : (
              <form className="bx-form" onSubmit={(e) => { e.preventDefault(); void saveBinding(); }}>
                <Field label="Thành phần">{(id) => <select id={id} disabled={disabled} value={bSection} onChange={(e) => { setBSection(e.target.value); setBProp(""); }}><option value="">— chọn —</option>{sections.map((s) => <option key={s.id} value={s.id}>{ctx.labelOf(s.type)} · {s.id}</option>)}</select>}</Field>
                <Field label="Thuộc tính nhận dữ liệu">{(id) => <select id={id} disabled={disabled || !bSection} value={bProp} onChange={(e) => setBProp(e.target.value)}><option value="">— chọn —</option>{props.map((p) => <option key={p.prop} value={p.prop}>{p.prop} ({p.cardinality === "LIST" ? "danh sách" : "một giá trị"})</option>)}</select>}</Field>
                <Field label="ViewModel">{(id) => <select id={id} disabled={disabled} value={vmId} onChange={(e) => setVmId(e.target.value)}>{vms.map((v) => <option key={v.id} value={v.id}>{v.name || v.id}</option>)}</select>}</Field>
                {compat ? <p className={compat.ok ? "hint" : "formError"} role={compat.ok ? "status" : "alert"}>{compat.message}</p> : null}
                <ViewStates/>
                <div className="bx-actions"><button type="submit" className="bx-btn primary" disabled={disabled || (!!compat && !compat.ok)}>Gắn dữ liệu</button></div>
              </form>)}
          </Gate>
        ) : null}

        {step === "public" ? <PublicDataTab ctx={ctx} focus={focus}/> : null}

        {msg ? <p className="formError" role="alert">{msg}</p> : null}
      </div>

      <details className="bx-existing"><summary>Đã khai báo ({queries.length + mappings.length + vms.length + bindings.length})</summary>
        <DefList title="Truy vấn" items={queries.map((q) => ({ id: q.id, name: q.name || q.id, note: `${q.mode ?? "READ"} · ${q.operationKey ?? ""}${q.public === true ? " · công khai" : ""}` }))} onRemove={(i) => setRemoving({ collection: "queries", id: i.id, name: i.name })} disabled={disabled}/>
        <DefList title="Ánh xạ" items={mappings.map((m) => ({ id: m.id, name: m.name || m.id, note: mappingNote(m) }))} onRemove={(i) => setRemoving({ collection: "mappings", id: i.id, name: i.name })} disabled={disabled}/>
        <DefList title="ViewModel" items={vms.map((v) => ({ id: v.id, name: v.name || v.id, note: `${v.cardinality ?? "LIST"} · ${vmFieldsOf(v).map((f) => f.name).join(", ")}` }))} onRemove={(i) => setRemoving({ collection: "viewModels", id: i.id, name: i.name })} disabled={disabled}/>
        <DefList title="Gắn dữ liệu" items={bindings.map((b) => ({ id: b.id, name: `${b.sectionId}.${b.prop}`, note: b.viewModelRef ? `← ${vms.find((v) => v.id === b.viewModelRef)?.name ?? b.viewModelRef}` : "" }))} onRemove={(i) => setRemoving({ collection: "dataBindings", id: i.id, name: i.name })} disabled={disabled}/>
      </details>

      {removing ? (
        <Dialog title={`Xóa “${removing.name}”?`} onClose={() => setRemoving(null)} footer={<>
          <button type="button" className="bx-btn" onClick={() => setRemoving(null)}>Hủy</button>
          <button type="button" className="bx-btn danger" disabled={ctx.busy} onClick={() => void doRemove()}>Xóa</button></>}>
          {(() => { const users = usersOf(doc, removing.collection, removing.id); return users.length
            ? <><p>Mục này đang được dùng bởi: {users.join(", ")}.</p><p>Xóa sẽ không tự xóa các mục đó; máy chủ sẽ từ chối lưu cho tới khi bạn sửa các tham chiếu bị hỏng.</p></>
            : <p>Mục này không được dùng ở đâu khác.</p>; })()}
        </Dialog>) : null}
    </div>
  );
}

function DefList({ title, items, onRemove, disabled }: { title: string; items: { id: string; name: string; note: string }[]; onRemove: (i: { id: string; name: string }) => void; disabled: boolean }) {
  if (!items.length) return null;
  return (<><h4 className="bx-h4">{title}</h4><ul className="bx-list">{items.map((i) => (
    <li key={i.id}><div><b>{i.name}</b><small>{i.note}</small></div>
      {!disabled ? <button type="button" className="smallButton danger" aria-label={`Xóa ${title.toLowerCase()} ${i.name}`} onClick={() => onRemove(i)}>Xóa</button> : null}</li>))}</ul></>);
}

/** the four states a bound component shows. With no server to answer, only NOT_READY is honest. */
function ViewStates() {
  const s = viewStateOf(staticReadiness("QUERY_PREVIEW"), null);
  return (
    <div className="bx-viewstates" aria-label="Trạng thái hiển thị của thành phần">
      <b>Trạng thái hiển thị</b>
      <ul>{(["loading", "empty", "error", "success"] as const).map((k) => <li key={k}><span className={`chip chip-${k}`}>{VIEW_STATE_TEXT[k]}</span></li>)}</ul>
      <p className="hint">{s.kind === "not-ready" ? `Chưa sẵn sàng: ${s.reason}` : ""}</p>
    </div>
  );
}
