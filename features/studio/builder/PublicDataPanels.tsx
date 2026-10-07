"use client";
/**
 * PAGE_SCHEMA public data V1 — the editors: logical data-source SLOTS, `QueryDef.public`, bindings (section.prop → public READ query) and the readiness of the draft.
 * Every write goes through the single funnel `ctx.commit` (typed operations → PATCH …/schema). The rules are in core/publicData.ts (pure, unit-tested).
 * Nothing here holds a sourceRef, credential, URL or SQL; nothing here runs a query or shows rows; nothing here offers a public action, mutation or workflow.
 */
import { useState } from "react";
import type { DataBindingDef, DataSourceDef, QueryDef } from "@xweb/types";
import { allSections, defOps, usersOf } from "./core/definition";
import {
  MAX_SLOT_DESCRIPTION, MAX_SLOT_NAME, PAGE_RUNTIME_STATES, RUNTIME_STATE_TEXT, bindablePropsOf, buildQueryBinding, isPublicEligibleQuery, modeOf, publicDataReadiness, publishApproval,
  queryChoicesFor, rebindOp, setPublicOp, slotOps, slotPatch, validateBindings, validatePublicQuery, validateSlot, validateSlotRemoval,
} from "./core/publicData";
import { Dialog, Field, Gate } from "./ui/primitives";
import type { DefCtx } from "./ctx";

const SAVE_FAILED = "Không lưu được thay đổi. Hãy xem thông báo của máy chủ rồi thử lại.";

// -------------------------------------------------------------------------------------------------------------------------------------- slots
type SlotForm = { id: string; name: string; type: string; description: string };
const emptySlot = (): SlotForm => ({ id: "", name: "", type: "postgres", description: "" });

export function SlotEditor({ ctx }: { ctx: DefCtx }) {
  const { doc } = ctx;
  const slots = doc.dataSources ?? [];
  const disabled = !ctx.canEdit || ctx.busy;
  const [form, setForm] = useState<SlotForm>(emptySlot());
  const [touched, setTouched] = useState(false);
  const [editing, setEditing] = useState<DataSourceDef | null>(null);
  const [edit, setEdit] = useState<SlotForm>(emptySlot());
  const [removing, setRemoving] = useState<DataSourceDef | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  const addIssues = validateSlot({ id: form.id.trim(), type: form.type.trim(), ...(form.name.trim() ? { name: form.name.trim() } : {}), ...(form.description.trim() ? { description: form.description.trim() } : {}) }, doc);
  const editIssues = editing ? validateSlot({ id: editing.id, type: edit.type.trim(), name: edit.name, description: edit.description }, doc, editing) : [];
  const patch = editing ? slotPatch(editing, { name: edit.name, description: edit.description, type: edit.type.trim() }) : {};
  const err = (issues: { path: string; message: string }[], path: string) => issues.find((i) => i.path === path)?.message;

  async function add() {
    setTouched(true); setMsg(null);
    if (addIssues.length) return;
    const ok = await ctx.commit([slotOps.add({ id: form.id.trim(), type: form.type.trim(), name: form.name, description: form.description })], `Thêm khe dữ liệu ${form.id.trim()}`);
    if (ok) { setForm(emptySlot()); setTouched(false); } else setMsg(SAVE_FAILED);
  }
  async function save() {
    if (!editing || editIssues.length || !Object.keys(patch).length) return;
    setMsg(null);
    const ok = await ctx.commit([slotOps.update(editing.id, patch)], `Sửa khe dữ liệu ${editing.id}`);
    if (ok) setEditing(null); else setMsg(SAVE_FAILED);
  }
  async function remove() {
    if (!removing) return;
    if (validateSlotRemoval(doc, removing.id).length) return;
    const ok = await ctx.commit([slotOps.remove(removing.id)], `Xóa khe dữ liệu ${removing.id}`);
    if (ok) setRemoving(null); else setMsg(SAVE_FAILED);
  }

  const removeIssues = removing ? validateSlotRemoval(doc, removing.id) : [];
  return (
    <section className="bx-public" data-testid="slot-editor" aria-label="Khe dữ liệu">
      <h3 className="bx-h3">Khe dữ liệu (tên logic)</h3>
      <p className="hint">Khe chỉ là một cái tên và loại nguồn trong ứng dụng. Nguồn thật (địa chỉ, khóa) do quản trị viên gắn ở phần “Nguồn dữ liệu” phía trên; không bao giờ nhập vào đây.</p>
      <Gate state={ctx.readiness}>
        {slots.length === 0
          ? <p className="hint" data-testid="slot-empty">Chưa có khe nào. Hãy thêm một khe để tạo truy vấn.</p>
          : <ul className="bx-list" data-testid="slot-list">{slots.map((s) => {
            const used = usersOf(doc, "dataSources", s.id);
            return (
              <li key={s.id} data-testid={`slot-${s.id}`}>
                <div><b>{s.name || s.id}</b><small>{s.id} · {s.type}{s.sourceRef ? " · đã gắn nguồn thật" : " · chưa gắn nguồn"}{used.length ? ` · đang dùng bởi ${used.length} mục` : ""}</small>
                  {s.description ? <small>{s.description}</small> : null}</div>
                {!disabled ? <span className="bx-row">
                  <button type="button" className="smallButton" data-testid={`slot-edit-${s.id}`} aria-label={`Sửa khe ${s.name || s.id}`} onClick={() => { setMsg(null); setEditing(s); setEdit({ id: s.id, name: s.name ?? "", type: s.type, description: s.description ?? "" }); }}>Sửa</button>
                  <button type="button" className="smallButton danger" data-testid={`slot-delete-${s.id}`} aria-label={`Xóa khe ${s.name || s.id}`} onClick={() => { setMsg(null); setRemoving(s); }}>Xóa</button>
                </span> : null}
              </li>);
          })}</ul>}

        {!ctx.canEdit ? <p className="hint" data-testid="slot-readonly">Bạn chỉ xem được: không có quyền chỉnh sửa khe dữ liệu.</p> : (
          <form className="bx-form" data-testid="slot-add-form" noValidate onSubmit={(e) => { e.preventDefault(); void add(); }}>
            <h4 className="bx-h4">Thêm khe</h4>
            <Field label="Mã khe" hint="Chữ thường, số, dấu gạch ngang. Ví dụ: orders">{(id) => <input id={id} data-testid="slot-id" disabled={disabled} value={form.id} aria-invalid={touched && !!err(addIssues, "id")} onChange={(e) => setForm({ ...form, id: e.target.value })}/>}</Field>
            {touched && err(addIssues, "id") ? <p className="formError" role="alert" data-testid="slot-id-error">{err(addIssues, "id")}</p> : null}
            <Field label="Tên hiển thị (tuỳ chọn)">{(id) => <input id={id} data-testid="slot-name" disabled={disabled} maxLength={MAX_SLOT_NAME} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })}/>}</Field>
            <Field label="Loại nguồn" hint="Ví dụ: postgres, rest. Chỉ là nhãn loại.">{(id) => <input id={id} data-testid="slot-type" disabled={disabled} value={form.type} aria-invalid={touched && !!err(addIssues, "type")} onChange={(e) => setForm({ ...form, type: e.target.value })}/>}</Field>
            {touched && err(addIssues, "type") ? <p className="formError" role="alert" data-testid="slot-type-error">{err(addIssues, "type")}</p> : null}
            <Field label="Mô tả (tuỳ chọn)">{(id) => <input id={id} data-testid="slot-desc" disabled={disabled} maxLength={MAX_SLOT_DESCRIPTION} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })}/>}</Field>
            <div className="bx-actions"><button type="submit" className="bx-btn primary" data-testid="slot-add" disabled={disabled}>Thêm khe</button></div>
          </form>)}
        {msg ? <p className="formError" role="alert" data-testid="slot-error">{msg}</p> : null}
      </Gate>

      {editing ? (
        <Dialog title={`Sửa khe “${editing.name || editing.id}”`} onClose={() => setEditing(null)} footer={<>
          <button type="button" className="bx-btn" onClick={() => setEditing(null)}>Hủy</button>
          <button type="button" className="bx-btn primary" data-testid="slot-save" disabled={ctx.busy || editIssues.length > 0 || !Object.keys(patch).length} onClick={() => void save()}>Lưu</button></>}>
          <div className="bx-form" data-testid="slot-edit-form">
            <p className="hint">Mã khe “{editing.id}” không đổi được.</p>
            <Field label="Tên hiển thị">{(id) => <input id={id} data-testid="slot-edit-name" data-autofocus maxLength={MAX_SLOT_NAME} value={edit.name} onChange={(e) => setEdit({ ...edit, name: e.target.value })}/>}</Field>
            <Field label="Loại nguồn" hint={editing.sourceRef ? "Khe đã gắn nguồn thật nên không đổi được loại." : undefined}>{(id) => <input id={id} data-testid="slot-edit-type" disabled={!!editing.sourceRef} value={edit.type} aria-invalid={!!err(editIssues, "type")} onChange={(e) => setEdit({ ...edit, type: e.target.value })}/>}</Field>
            {err(editIssues, "type") ? <p className="formError" role="alert">{err(editIssues, "type")}</p> : null}
            <Field label="Mô tả">{(id) => <input id={id} data-testid="slot-edit-desc" maxLength={MAX_SLOT_DESCRIPTION} value={edit.description} onChange={(e) => setEdit({ ...edit, description: e.target.value })}/>}</Field>
            {msg ? <p className="formError" role="alert">{msg}</p> : null}
          </div>
        </Dialog>) : null}

      {removing ? (
        <Dialog title={`Xóa khe “${removing.name || removing.id}”?`} onClose={() => setRemoving(null)} footer={<>
          <button type="button" className="bx-btn" onClick={() => setRemoving(null)}>Hủy</button>
          <button type="button" className="bx-btn danger" data-testid="slot-delete-confirm" disabled={ctx.busy || removeIssues.length > 0} onClick={() => void remove()}>Xóa</button></>}>
          {removeIssues.length
            ? <div data-testid="slot-blocked" role="alert"><p>Không xóa được: khe này đang được dùng. Máy chủ không tự xóa các mục phụ thuộc, nên hãy gỡ chúng trước:</p>
              <ul>{removeIssues.map((i) => <li key={i.path}>{i.message}</li>)}</ul></div>
            : <p>Khe này không được dùng ở đâu khác.</p>}
          {msg ? <p className="formError" role="alert">{msg}</p> : null}
        </Dialog>) : null}
    </section>
  );
}

// ----------------------------------------------------------------------------------------------------------------------------- public queries
/** One query row: the `public` switch exists ONLY for READ queries; a stored invalid WRITE+public query is shown as invalid and never fixed silently. */
export function PublicQueriesPanel({ ctx }: { ctx: DefCtx }) {
  const queries = ctx.doc.queries ?? [];
  const disabled = !ctx.canEdit || ctx.busy;
  const [msg, setMsg] = useState<string | null>(null);
  async function toggle(q: QueryDef, value: boolean) {
    setMsg(null);
    const op = setPublicOp(q, value);
    if ("error" in op) { setMsg(op.error); return; }
    if (!(await ctx.commit([op], `${value ? "Công khai" : "Ngừng công khai"} truy vấn ${q.name || q.id}`))) setMsg(SAVE_FAILED);
  }
  return (
    <section className="bx-public" data-testid="public-queries" aria-label="Truy vấn công khai">
      <h3 className="bx-h3">Truy vấn công khai</h3>
      <p className="hint">Truy vấn công khai là truy vấn ĐỌC mà bất kỳ ai mở được trang đã xuất bản đều chạy được, không cần đăng nhập. Mặc định là riêng tư. Việc này không suy ra từ chế độ hiển thị của trang.</p>
      {queries.length === 0 ? <p className="hint" data-testid="public-empty">Chưa có truy vấn nào. Hãy tạo truy vấn ở bước “Truy vấn”.</p> : (
        <ul className="bx-list">{queries.map((q, i) => {
          const issues = validatePublicQuery(q, `queries[${i}]`);
          const eligible = isPublicEligibleQuery(q);
          return (
            <li key={q.id} data-testid={`pq-${q.id}`} className={issues.length ? "bx-invalid" : ""}>
              <div><b>{q.name || q.id}</b><small>{q.id} · {modeOf(q) === "READ" ? "Đọc" : "Ghi"} · khe {q.dataSourceRef}</small>
                {issues.map((x) => <small key={x.code} className="formError" role="alert" data-testid={`pq-invalid-${q.id}`}>Không hợp lệ: {x.message}</small>)}
                {!eligible && !issues.length ? <small data-testid={`pq-write-${q.id}`}>Truy vấn ghi không thể công khai.</small> : null}</div>
              {eligible
                ? <label className="checkRow"><input type="checkbox" data-testid={`public-toggle-${q.id}`} disabled={disabled} checked={q.public === true} onChange={(e) => void toggle(q, e.target.checked)}/><span>Công khai</span></label>
                : issues.length && !disabled ? <button type="button" className="smallButton" data-testid={`public-off-${q.id}`} onClick={() => void toggle(q, false)}>Tắt công khai</button> : null}
            </li>);
        })}</ul>)}
      {msg ? <p className="formError" role="alert" data-testid="public-error">{msg}</p> : null}
    </section>
  );
}

// ---------------------------------------------------------------------------------------------------------------------------------- bindings
export function PublicBindingsPanel({ ctx, focus }: { ctx: DefCtx; focus?: { sectionId?: string } }) {
  const { doc } = ctx;
  const disabled = !ctx.canEdit || ctx.busy;
  const sections = allSections(doc).filter((s) => bindablePropsOf(s.type).length > 0);
  const [sectionId, setSectionId] = useState(focus?.sectionId && sections.some((s) => s.id === focus.sectionId) ? focus.sectionId : "");
  const [prop, setProp] = useState("");
  const [queryId, setQueryId] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const views = validateBindings(doc);
  const choices = queryChoicesFor(doc);
  const section = sections.find((s) => s.id === sectionId);
  const props = section ? bindablePropsOf(section.type) : [];
  const chosen = choices.find((c) => c.query.id === queryId);

  async function add() {
    setMsg(null);
    if (!sectionId || !prop || !queryId) { setMsg("Hãy chọn thành phần, thuộc tính và truy vấn công khai."); return; }
    const b = buildQueryBinding(sectionId, prop, queryId, doc);
    if ("error" in b) { setMsg(b.error); return; }
    if (await ctx.commit([defOps.add("dataBindings", b)], "Gắn dữ liệu công khai vào thành phần")) { setProp(""); setQueryId(""); } else setMsg(SAVE_FAILED);
  }
  async function rebind(b: DataBindingDef, q: string) {
    setMsg(null);
    const op = rebindOp(b, q, doc);
    if ("error" in op) { setMsg(op.error); return; }
    if (!(await ctx.commit([op], "Đổi truy vấn của gắn dữ liệu"))) setMsg(SAVE_FAILED);
  }
  async function unbind(b: DataBindingDef) {
    setMsg(null);
    if (!(await ctx.commit([defOps.remove("dataBindings", b.id)], `Gỡ gắn dữ liệu ${b.sectionId}.${b.prop}`))) setMsg(SAVE_FAILED);
  }

  return (
    <section className="bx-public" data-testid="public-bindings" aria-label="Gắn dữ liệu công khai">
      <h3 className="bx-h3">Gắn dữ liệu vào trang</h3>
      <p className="hint">Mỗi thuộc tính của thành phần nhận dữ liệu từ MỘT truy vấn công khai (đọc). Khe dữ liệu được suy ra từ truy vấn; không gắn thẳng vào nguồn thật.</p>
      {views.length === 0 ? <p className="hint" data-testid="binding-empty">Chưa có thuộc tính nào được gắn dữ liệu.</p> : (
        <ul className="bx-list" data-testid="binding-list">{views.map((v) => {
          const b = v.binding;
          const viaVm = b.queryRef === undefined && b.viewModelRef !== undefined;
          return (
            <li key={b.id} data-testid={`binding-${b.id}`} className={v.issues.length ? "bx-invalid" : ""}>
              <div><b>{v.component ?? "?"}.{b.prop}</b><small data-testid={`binding-map-${b.id}`}>{b.sectionId} → {v.query ? `truy vấn ${v.query.name || v.query.id}` : "chưa có truy vấn"}{viaVm ? ` (qua ViewModel ${b.viewModelRef})` : ""}{v.slotId ? ` → khe ${v.slot?.name || v.slotId}` : ""}</small>
                {v.issues.map((x) => <small key={x.code + x.path} className="formError" role="alert" data-testid={`binding-issue-${b.id}`}>{x.message}</small>)}</div>
              {!disabled ? <span className="bx-row">
                <select aria-label={`Đổi truy vấn của ${b.sectionId}.${b.prop}`} data-testid={`binding-rebind-${b.id}`} value="" onChange={(e) => { if (e.target.value) void rebind(b, e.target.value); }}>
                  <option value="">Đổi truy vấn…</option>
                  {choices.filter((c) => !c.reason && c.query.id !== v.query?.id).map((c) => <option key={c.query.id} value={c.query.id}>{c.query.name || c.query.id}</option>)}
                </select>
                <button type="button" className="smallButton danger" data-testid={`binding-remove-${b.id}`} aria-label={`Gỡ gắn dữ liệu ${b.sectionId}.${b.prop}`} onClick={() => void unbind(b)}>Gỡ</button>
              </span> : null}
            </li>);
        })}</ul>)}

      {!ctx.canEdit ? null : sections.length === 0 ? <p className="hint" data-testid="binding-no-sections">Trang chưa có thành phần nào nhận dữ liệu (Navbar, Hero, ProductGrid, Testimonials, Footer…).</p> : (
        <form className="bx-form" data-testid="binding-form" onSubmit={(e) => { e.preventDefault(); void add(); }}>
          <h4 className="bx-h4">Gắn thuộc tính mới</h4>
          <Field label="Thành phần">{(id) => <select id={id} data-testid="binding-section" disabled={disabled} value={sectionId} onChange={(e) => { setSectionId(e.target.value); setProp(""); }}>
            <option value="">— chọn —</option>{sections.map((s) => <option key={s.id} value={s.id}>{ctx.labelOf(s.type)} · {s.id}</option>)}</select>}</Field>
          <Field label="Thuộc tính">{(id) => <select id={id} data-testid="binding-prop" disabled={disabled || !sectionId} value={prop} onChange={(e) => setProp(e.target.value)}>
            <option value="">— chọn —</option>{props.map((p) => <option key={p.prop} value={p.prop}>{p.prop} ({p.kind === "list" ? "danh sách" : "một giá trị"})</option>)}</select>}</Field>
          <Field label="Truy vấn công khai" hint="Chỉ truy vấn đọc đã bật “Công khai” mới chọn được.">{(id) => <select id={id} data-testid="binding-query" disabled={disabled} value={queryId} onChange={(e) => setQueryId(e.target.value)}>
            <option value="">— chọn —</option>{choices.map((c) => <option key={c.query.id} value={c.query.id} disabled={!!c.reason}>{c.query.name || c.query.id}{c.reason ? ` — ${c.reason}` : ""}</option>)}</select>}</Field>
          {chosen ? <p className="hint" data-testid="binding-slot">Khe dữ liệu: {chosen.slot?.name || chosen.query.dataSourceRef}{chosen.slot ? "" : " (khe không tồn tại)"}</p> : null}
          <div className="bx-actions"><button type="submit" className="bx-btn primary" data-testid="binding-add" disabled={disabled}>Gắn dữ liệu</button></div>
        </form>)}
      {msg ? <p className="formError" role="alert" data-testid="binding-error">{msg}</p> : null}
    </section>
  );
}

// -------------------------------------------------------------------------------------------------------------------------------- readiness
/**
 * What the Studio can say about the draft BEFORE a published page exists, in the vocabulary of C2's published runtime. It never shows rows and never
 * fakes a state a visitor would see; the real states are observed on the published page (data-xw-state).
 */
export function PublicReadinessPanel({ ctx }: { ctx: DefCtx }) {
  const r = publicDataReadiness(ctx.doc, { visibility: ctx.siteVisibility });
  const a = publishApproval(ctx.doc);
  return (
    <section className="bx-public" data-testid="public-readiness" aria-label="Trạng thái dữ liệu công khai">
      <h3 className="bx-h3">Trạng thái</h3>
      <p role="status" data-testid="public-readiness-state" data-state={r.state}><b>{r.label}</b></p>
      {r.state === "not-ready" ? <ul className="bx-list" data-testid="public-readiness-reasons">{r.reasons.map((x, i) => <li key={i}>{x}</li>)}</ul> : null}
      {a.required ? <p className="hint" data-testid="public-readiness-queries">Khi xuất bản, sẽ công khai: {a.queries.map((q) => q.id).join(", ")}.</p> : null}
      <details className="bx-existing" data-testid="runtime-legend"><summary>Trạng thái của trang đã xuất bản</summary>
        <p className="hint">Studio không hiển thị dữ liệu thật. Trang đã xuất bản tự báo trạng thái (thuộc tính data-xw-state) theo các bước sau:</p>
        <ol>{PAGE_RUNTIME_STATES.map((s) => <li key={s} data-state={s}><b>{RUNTIME_STATE_TEXT[s]}</b> <small>({s})</small></li>)}</ol>
      </details>
    </section>
  );
}

/** the whole "public data" tab of the data builder */
export function PublicDataTab({ ctx, focus }: { ctx: DefCtx; focus?: { sectionId?: string } }) {
  return (
    <div className="bx-publicdata" data-testid="public-data-tab">
      <PublicReadinessPanel ctx={ctx}/>
      <PublicQueriesPanel ctx={ctx}/>
      <PublicBindingsPanel ctx={ctx} focus={focus}/>
    </div>
  );
}
