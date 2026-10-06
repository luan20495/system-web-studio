"use client";
/**
 * Editor of ONE canonical action. Only what the contract allows: type (9), typed references, typed input sources, chain, idempotency, permission.
 * There is no input that can hold code, SQL, a URL, a header or a secret. A UI-bound action picks an event; a child action does not.
 */
import { useState } from "react";
import type { ActionDef, ActionType, EventType, InputSourceDef } from "@xweb/types";
import { ACTION_TYPES, CONTEXT_KEYS, EVENT_TYPES, IDEMPOTENCY_POLICIES, INPUT_SOURCE_KINDS, NOTIFY_CHANNELS } from "./core/contract";
import { ACTION_HELP, ACTION_LABEL, EVENT_LABEL, actionOps, actionTypesFor, checkAction, isClientOnly, newAction, pageOptions, permissionToRun } from "./core/actions";
import { allSections } from "./core/definition";
import { OPERATION_KEY_RE } from "./core/dataFlow";
import { permissionLabel } from "./core/inspector";
import { Field } from "./ui/primitives";
import type { DefCtx } from "./ctx";

const SOURCE_LABEL: Record<string, string> = { COMPONENT_STATE: "Trạng thái thành phần", ROUTE_PARAM: "Tham số đường dẫn", FORM_FIELD: "Trường biểu mẫu", VIEW_MODEL: "Trường ViewModel", PREVIOUS_RESULT: "Kết quả hành động trước", LITERAL: "Giá trị cố định", CONTEXT: "Ngữ cảnh hệ thống" };
const CONTEXT_LABEL: Record<string, string> = { USER_ID: "Người dùng", TENANT_ID: "Công ty", WORKSPACE_ID: "Workspace", APP_ID: "Ứng dụng", REQUEST_ID: "Mã yêu cầu", NOW: "Thời điểm hiện tại" };

export function ActionEditor({ ctx, initial, preset, onDone, onCancel }: {
  ctx: DefCtx; initial?: ActionDef; preset?: { type?: ActionType; sectionId?: string; event?: EventType }; onDone: (id: string) => void; onCancel: () => void;
}) {
  const { doc } = ctx;
  const isNew = !initial;
  const [a, setA] = useState<ActionDef>(() => initial ?? { ...newAction(preset?.type ?? "NAVIGATE", doc, preset?.sectionId && preset.event ? { sectionId: preset.sectionId, event: preset.event } : undefined) });
  const [error, setError] = useState<string | null>(null);
  const patch = (p: Partial<ActionDef>) => setA((x) => ({ ...x, ...p }));
  const sections = allSections(doc);
  const section = a.trigger ? sections.find((s) => s.id === a.trigger!.sectionId) : undefined;
  const allowedTypes = section && a.trigger ? (actionTypesFor(ctx.metadata.get(section.type), a.trigger.event) as readonly ActionType[]) : ACTION_TYPES;
  const types = allowedTypes.length ? allowedTypes : ACTION_TYPES;
  const queries = doc.queries ?? [];
  const query = queries.find((q) => q.id === a.queryRef);
  const issues = checkAction(a, doc);
  const disabled = !ctx.canEdit || ctx.busy;

  function setType(t: ActionType) {
    // keep only what the new type uses
    const { id, name, enabled, trigger, onSuccess, onError } = a;
    setA({ id, name: !name || Object.values(ACTION_LABEL).includes(name) ? ACTION_LABEL[t] : name, type: t, ...(enabled === false ? { enabled } : {}), ...(trigger ? { trigger } : {}), ...(onSuccess ? { onSuccess } : {}), ...(onError ? { onError } : {}) });
  }
  function setMapping(param: string, m: InputSourceDef | null) {
    const next = { ...(a.inputMapping ?? {}) };
    if (m) next[param] = m; else delete next[param];
    patch({ inputMapping: Object.keys(next).length ? next : undefined });
  }
  async function save() {
    setError(null);
    if (a.type === "CALL_API" && a.operationKey && !OPERATION_KEY_RE.test(a.operationKey)) { setError("Thao tác phải là mã đã được duyệt, không phải SQL hay địa chỉ."); return; }
    if (issues.length) { setError(issues[0].message); return; }
    const ok = await ctx.commit([isNew ? actionOps.add(a) : actionOps.update(a.id, a as never)], isNew ? `Thêm hành động ${a.name ?? ""}` : `Sửa hành động ${a.name ?? ""}`);
    if (ok) onDone(a.id);
  }
  const chain = (key: "onSuccess" | "onError") => {
    const others = (doc.actions ?? []).filter((x) => x.id !== a.id);
    return <Field label={key === "onSuccess" ? "Khi thành công, chạy tiếp" : "Khi lỗi, chạy tiếp"} hint={others.length ? "Hành động được chọn sẽ là hành động con (không gắn sự kiện)." : "Chưa có hành động khác."}>{(id) => (
      <select id={id} multiple disabled={disabled || !others.length} value={a[key] ?? []} onChange={(e) => { const v = Array.from(e.target.selectedOptions).map((o) => o.value); patch({ [key]: v.length ? v : undefined } as Partial<ActionDef>); }}>
        {others.map((x) => <option key={x.id} value={x.id}>{x.name || x.id}</option>)}</select>)}</Field>;
  };

  return (
    <form className="bx-form" onSubmit={(e) => { e.preventDefault(); void save(); }} aria-label={isNew ? "Thêm hành động" : `Sửa hành động ${a.name ?? a.id}`}>
      <Field label="Tên hành động">{(id) => <input id={id} value={a.name ?? ""} maxLength={80} disabled={disabled} onChange={(e) => patch({ name: e.target.value })}/>}</Field>
      <Field label="Loại hành động" hint={ACTION_HELP[a.type]}>{(id) => (
        <select id={id} value={a.type} disabled={disabled} onChange={(e) => setType(e.target.value as ActionType)}>
          {types.map((t) => <option key={t} value={t}>{ACTION_LABEL[t]}</option>)}</select>)}</Field>

      <fieldset className="bx-group"><legend>Khi nào chạy</legend>
        <label className="checkRow"><input type="checkbox" disabled={disabled} checked={!!a.trigger} onChange={(e) => patch({ trigger: e.target.checked ? { sectionId: sections[0]?.id ?? "", event: "onClick" } : undefined })}/><span>Gắn vào một thành phần trên trang</span></label>
        {a.trigger ? (<>
          <Field label="Thành phần">{(id) => <select id={id} disabled={disabled} value={a.trigger!.sectionId} onChange={(e) => patch({ trigger: { ...a.trigger!, sectionId: e.target.value } })}>
            {sections.map((s) => <option key={s.id} value={s.id}>{ctx.labelOf(s.type)} · {s.id}</option>)}</select>}</Field>
          <Field label="Sự kiện">{(id) => <select id={id} disabled={disabled} value={a.trigger!.event} onChange={(e) => patch({ trigger: { ...a.trigger!, event: e.target.value as EventType } })}>
            {(section ? (ctx.metadata.get(section.type)?.events.map((x) => x.name) ?? [...EVENT_TYPES]) : [...EVENT_TYPES]).map((ev) => <option key={ev} value={ev}>{EVENT_LABEL[ev as EventType]}</option>)}</select>}</Field>
        </>) : <p className="hint">Hành động con: chạy từ workflow hoặc từ chuỗi “khi thành công/khi lỗi” của hành động khác.</p>}
      </fieldset>

      {a.type === "NAVIGATE" ? <Field label="Trang đích">{(id) => <select id={id} disabled={disabled} value={a.pageRef ?? ""} onChange={(e) => patch({ pageRef: e.target.value || undefined })}>
        <option value="">— chọn trang —</option>{pageOptions(doc).map((p) => <option key={p.id} value={p.id}>{p.title}</option>)}</select>}</Field> : null}

      {a.type === "REFRESH_QUERY" ? <Field label="Truy vấn đọc cần làm mới" hint="Chỉ truy vấn đọc (READ) mới làm mới được.">{(id) => <select id={id} disabled={disabled} value={a.queryRef ?? ""} onChange={(e) => patch({ queryRef: e.target.value || undefined })}>
        <option value="">— chọn truy vấn —</option>{queries.filter((q) => (q.mode ?? "READ") === "READ").map((q) => <option key={q.id} value={q.id}>{q.name || q.id}</option>)}</select>}</Field> : null}

      {a.type === "SUBMIT_FORM" || a.type === "CREATE_RECORD" || a.type === "UPDATE_RECORD" || a.type === "DELETE_RECORD" ? (<>
        <Field label="Truy vấn ghi" hint="Chỉ truy vấn ghi (WRITE) đã khai báo trong Dữ liệu.">{(id) => <select id={id} disabled={disabled} value={a.queryRef ?? ""} onChange={(e) => patch({ queryRef: e.target.value || undefined, inputMapping: undefined })}>
          <option value="">— chọn truy vấn —</option>{queries.filter((q) => q.mode === "WRITE").map((q) => <option key={q.id} value={q.id}>{q.name || q.id}</option>)}</select>}</Field>
        {query?.params?.length ? (
          <fieldset className="bx-group"><legend>Giá trị đầu vào của truy vấn</legend>
            {query.params.map((p) => {
              const m = a.inputMapping?.[p.name];
              return (<div className="bx-row" key={p.name}>
                <span className="bx-param">{p.name}<small>{p.type}</small></span>
                <label className="srOnly" htmlFor={`m-${p.name}`}>Nguồn của {p.name}</label>
                <select id={`m-${p.name}`} disabled={disabled} value={m?.source ?? ""} onChange={(e) => setMapping(p.name, e.target.value ? ({ source: e.target.value } as InputSourceDef) : null)}>
                  <option value="">— chưa chọn —</option>{INPUT_SOURCE_KINDS.map((k) => <option key={k} value={k}>{SOURCE_LABEL[k]}</option>)}</select>
                {m?.source === "CONTEXT" ? <select aria-label={`Ngữ cảnh của ${p.name}`} disabled={disabled} value={m.key ?? ""} onChange={(e) => setMapping(p.name, { ...m, key: e.target.value as never })}><option value="">—</option>{CONTEXT_KEYS.map((k) => <option key={k} value={k}>{CONTEXT_LABEL[k]}</option>)}</select>
                  : m?.source === "LITERAL" ? <input aria-label={`Giá trị của ${p.name}`} disabled={disabled} value={m.value === undefined ? "" : String(m.value)} onChange={(e) => setMapping(p.name, { ...m, value: e.target.value })}/>
                  : m ? <input aria-label={`Đường dẫn của ${p.name}`} disabled={disabled} placeholder={m.source === "FORM_FIELD" ? "tên trường" : "đường dẫn"} value={m.path ?? m.name ?? ""} onChange={(e) => setMapping(p.name, m.source === "FORM_FIELD" || m.source === "COMPONENT_STATE" ? { ...m, name: e.target.value } : { ...m, path: e.target.value })}/> : null}
              </div>);
            })}
          </fieldset>) : null}
      </>) : null}

      {a.type === "CALL_API" ? (<>
        <Field label="Nguồn dữ liệu">{(id) => <select id={id} disabled={disabled} value={a.dataSourceRef ?? ""} onChange={(e) => patch({ dataSourceRef: e.target.value || undefined })}>
          <option value="">— chọn nguồn —</option>{(doc.dataSources ?? []).map((d) => <option key={d.id} value={d.id}>{d.name || d.id}</option>)}</select>}</Field>
        <Field label="Mã thao tác đã duyệt" hint="Do quản trị viên đặt tên (ví dụ orders.create). Không nhập địa chỉ hay câu lệnh.">{(id) => <input id={id} disabled={disabled} value={a.operationKey ?? ""} onChange={(e) => patch({ operationKey: e.target.value || undefined })}/>}</Field>
      </>) : null}

      {a.type === "NOTIFY" ? (<>
        <Field label="Kênh">{(id) => <select id={id} disabled={disabled} value={a.channel ?? ""} onChange={(e) => patch({ channel: (e.target.value || undefined) as never })}>
          <option value="">— chọn kênh —</option>{NOTIFY_CHANNELS.map((c) => <option key={c} value={c}>{c}</option>)}</select>}</Field>
        <Field label="Mã mẫu thông báo đã duyệt" hint="Nội dung thư lấy từ mẫu đã duyệt, không soạn tại đây.">{(id) => <input id={id} disabled={disabled} value={a.templateRef ?? ""} onChange={(e) => patch({ templateRef: e.target.value || undefined })}/>}</Field>
      </>) : null}

      {a.type === "START_WORKFLOW" ? <Field label="Workflow">{(id) => <select id={id} disabled={disabled} value={a.workflowRef ?? ""} onChange={(e) => patch({ workflowRef: e.target.value || undefined })}>
        <option value="">— chọn workflow —</option>{(doc.workflows ?? []).map((w) => <option key={w.id} value={w.id}>{w.name || w.id}</option>)}</select>}</Field> : null}

      {!isClientOnly(a.type) ? <Field label="Chống chạy trùng (idempotency)" hint="Hành động ghi cần khóa chống trùng; để trống thì máy chủ dùng mặc định.">{(id) => (
        <select id={id} disabled={disabled} value={a.idempotency ?? ""} onChange={(e) => patch({ idempotency: (e.target.value || undefined) as never })}>
          <option value="">Mặc định</option>{IDEMPOTENCY_POLICIES.map((p) => <option key={p} value={p}>{p}</option>)}</select>)}</Field> : null}

      <Field label="Quyền chạy" hint={`Máy chủ kiểm tra quyền “${permissionLabel[permissionToRun(a.type)]}” khi chạy. Có thể chọn một quyền đã khai báo cho hành động này.`}>{(id) => (
        <select id={id} disabled={disabled} value={a.permissionRef ?? ""} onChange={(e) => patch({ permissionRef: e.target.value || undefined })}>
          <option value="">Mặc định theo loại hành động</option>{(doc.permissions ?? []).map((p) => <option key={p.id} value={p.id}>{p.name || permissionLabel[p.permission]}</option>)}</select>)}</Field>

      {chain("onSuccess")}{chain("onError")}
      <label className="checkRow"><input type="checkbox" disabled={disabled} checked={a.enabled !== false} onChange={(e) => patch({ enabled: e.target.checked ? undefined : false })}/><span>Đang bật</span></label>

      {issues.length ? <ul className="bx-issues" role="alert">{issues.slice(0, 4).map((i) => <li key={i.path + i.message}>{i.message}</li>)}</ul> : null}
      {error ? <p className="formError" role="alert">{error}</p> : null}
      <div className="bx-actions">
        <button type="button" className="bx-btn" onClick={onCancel}>Hủy</button>
        <button type="submit" className="bx-btn primary" disabled={disabled || issues.length > 0}>{ctx.busy ? "Đang lưu…" : "Lưu hành động"}</button>
      </div>
    </form>
  );
}
