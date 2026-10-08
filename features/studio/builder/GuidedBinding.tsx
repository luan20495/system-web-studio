"use client";
/**
 * "Hiển thị dữ liệu trong trang": ONE form, four numbered sections, ONE commit (M-005). The rules are in core/guidedBinding.ts (pure, unit-tested);
 * this file only collects the draft, shows each problem next to its field and commits through `ctx.commit` (the single write funnel).
 * Nothing here executes a query, shows rows, holds a credential or invents a column list.
 */
import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import type { DataSourceView, ParamDef } from "@xweb/types";
import { PARAM_TYPES, paramRequired } from "./core/contract";
import { newParam, setParamRequired } from "./core/dataFlow";
import { allSections } from "./core/definition";
import { DATA_WORDS as W } from "./core/dataWording";
import { emptyGuidedDraft, planGuidedBinding, slotTypeOf, type GuidedDraft, type GuidedField } from "./core/guidedBinding";
import { bindablePropsOf } from "./core/publicData";
import { propLabel } from "./PropsForm";
import { Field } from "./ui/primitives";
import type { DefCtx } from "./ctx";

type Choice = string;   // "slot:<id>" | "src:<workspace source id>" | "new"

export function GuidedBinding({ ctx, focus, onDone, onCancel }: { ctx: DefCtx; focus?: { sectionId?: string; prop?: string }; onDone: (message: string) => void; onCancel: () => void }) {
  const { doc } = ctx;
  const sections = useMemo(() => {
    const seen = new Map<string, number>();
    return allSections(doc).filter((s) => bindablePropsOf(s.type).length > 0).map((s) => { const n = (seen.get(s.type) ?? 0) + 1; seen.set(s.type, n); return { id: s.id, type: s.type, label: `${ctx.labelOf(s.type)}${n > 1 ? ` (${n})` : ""}` }; });
  }, [doc, ctx]);
  const [draft, setDraft] = useState<GuidedDraft>(() => {
    const sid = focus?.sectionId && sections.some((s) => s.id === focus.sectionId) ? focus.sectionId : "";
    const sec = sections.find((s) => s.id === sid);
    const prop = focus?.prop && sec && bindablePropsOf(sec.type).some((p) => p.prop === focus.prop) ? focus.prop : "";
    const slot0 = (doc.dataSources ?? [])[0];
    return { ...emptyGuidedDraft(sid, prop), source: slot0 ? { kind: "slot", slotId: slot0.id } : { kind: "new", name: "", type: "postgres" } };
  });
  const [choice, setChoice] = useState<Choice>(() => ((doc.dataSources ?? [])[0] ? `slot:${doc.dataSources![0].id}` : "new"));
  const [sources, setSources] = useState<DataSourceView[]>([]);
  const [problems, setProblems] = useState<{ field: GuidedField; message: string }[]>([]);
  const [failed, setFailed] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const first = useRef<HTMLSelectElement>(null);
  useEffect(() => { first.current?.focus(); }, []);
  // workspace sources the person may VIEW: offered as ready-made names for a new connection (the real link source <-> app stays an explicit step in "Nâng cao")
  useEffect(() => {
    let alive = true;
    if (ctx.dataManagement && ctx.canViewData) void ctx.dataManagement.list().then((l) => { if (alive) setSources(l); }).catch(() => undefined);
    return () => { alive = false; };
  }, [ctx.dataManagement, ctx.canViewData]);

  const slots = doc.dataSources ?? [];
  const fieldErr = (f: GuidedField) => problems.find((p) => p.field === f)?.message;
  const section = sections.find((s) => s.id === draft.sectionId);
  const props = section ? bindablePropsOf(section.type) : [];
  const disabled = !ctx.canEdit || ctx.busy || pending;
  const set = (patch: Partial<GuidedDraft>) => { setDraft((d) => ({ ...d, ...patch })); setProblems([]); setFailed(null); };
  const pickSource = (c: Choice) => {
    setChoice(c);
    if (c.startsWith("slot:")) set({ source: { kind: "slot", slotId: c.slice(5) } });
    else if (c.startsWith("src:")) { const s = sources.find((x) => x.id === c.slice(4)); set({ source: { kind: "new", name: s?.name ?? "", type: slotTypeOf(s?.type ?? "") } }); }
    else set({ source: { kind: "new", name: "", type: "postgres" } });
  };

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (disabled) return;
    const plan = planGuidedBinding(draft, doc, ctx.labelOf);
    if (!plan.ok) { setProblems(plan.problems); return; }
    setPending(true); setFailed(null);
    try {
      if (await ctx.commit(plan.ops, plan.summary)) onDone(W.saved(plan.slotCreated));
      else setFailed("Không lưu được. Hãy xem thông báo của máy chủ rồi thử lại.");
    } finally { setPending(false); }
  }
  const err = (f: GuidedField) => { const m = fieldErr(f); return m ? <p className="formError" role="alert" data-testid={`gb-error:${f}`}>{m}</p> : null; };

  return (
    <form className="bx-form bx-guided" data-testid="guided-binding" aria-label={W.formTitle} onSubmit={(e) => void submit(e)} noValidate>
      <h3 className="bx-h3">{W.formTitle}</h3>

      <fieldset className="bx-group"><legend>{W.where.title}</legend>
        <Field label={W.where.component}>{(id) => <select id={id} ref={first} data-testid="gb-section" disabled={disabled} value={draft.sectionId} aria-invalid={!!fieldErr("section")} onChange={(e) => set({ sectionId: e.target.value, prop: "" })}>
          <option value="">— chọn —</option>{sections.map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}</select>}</Field>
        {err("section")}
        <Field label={W.where.prop}>{(id) => <select id={id} data-testid="gb-prop" disabled={disabled || !section} value={draft.prop} aria-invalid={!!fieldErr("prop")} onChange={(e) => set({ prop: e.target.value })}>
          <option value="">— chọn —</option>{props.map((p) => <option key={p.prop} value={p.prop}>{propLabel(p.prop)} ({p.kind === "list" ? "danh sách" : "một giá trị"})</option>)}</select>}</Field>
        {err("prop")}
        {sections.length === 0 ? <p className="hint">Trang chưa có thành phần nào hiển thị được dữ liệu thật.</p> : null}
      </fieldset>

      <fieldset className="bx-group"><legend>{W.from.title}</legend>
        <Field label={W.from.source}>{(id) => <select id={id} data-testid="gb-source" disabled={disabled} value={choice} aria-invalid={!!fieldErr("source")} onChange={(e) => pickSource(e.target.value)}>
          {slots.map((s) => <option key={s.id} value={`slot:${s.id}`}>{s.name || s.id}</option>)}
          {sources.filter((s) => !slots.some((x) => x.name === s.name)).map((s) => <option key={s.id} value={`src:${s.id}`}>{s.name}</option>)}
          <option value="new">{W.from.newSource}</option></select>}</Field>
        {err("source")}
        {draft.source.kind === "new" ? (<>
          <Field label={W.from.newSourceName}>{(id) => <input id={id} data-testid="gb-source-name" disabled={disabled} value={draft.source.kind === "new" ? draft.source.name : ""} aria-invalid={!!fieldErr("sourceName")} maxLength={120}
            onChange={(e) => set({ source: { kind: "new", name: e.target.value, type: draft.source.kind === "new" ? draft.source.type : "postgres" } })}/>}</Field>
          {err("sourceName")}
          <Field label={W.from.newSourceType}>{(id) => <input id={id} data-testid="gb-source-type" disabled={disabled} value={draft.source.kind === "new" ? draft.source.type : ""} aria-invalid={!!fieldErr("sourceType")}
            onChange={(e) => set({ source: { kind: "new", name: draft.source.kind === "new" ? draft.source.name : "", type: e.target.value } })}/>}</Field>
          {err("sourceType")}
        </>) : null}
        <Field label={W.from.dataset}>{(id) => <input id={id} data-testid="gb-dataset" disabled={disabled} value={draft.datasetName} maxLength={120} aria-invalid={!!fieldErr("datasetName")} onChange={(e) => set({ datasetName: e.target.value })}/>}</Field>
        {err("datasetName")}
        <Field label={W.from.operation} hint={W.from.operationHint}>{(id) => <input id={id} data-testid="gb-operation" disabled={disabled} value={draft.operationKey} aria-invalid={!!fieldErr("operationKey")} onChange={(e) => set({ operationKey: e.target.value })}/>}</Field>
        {err("operationKey")}
        <Field label={W.from.maxRows}>{(id) => <input id={id} type="number" min={1} data-testid="gb-maxrows" disabled={disabled} value={draft.maxRows ?? ""} aria-invalid={!!fieldErr("maxRows")} onChange={(e) => set({ maxRows: e.target.value === "" ? undefined : Number(e.target.value) })}/>}</Field>
        {err("maxRows")}
        <details className="bx-existing"><summary>{W.from.params}{draft.params.length ? ` (${draft.params.length})` : ""}</summary>
          {draft.params.map((p: ParamDef, i) => (
            <div className="bx-row" key={i}>
              <input aria-label={`Tên tham số ${i + 1}`} disabled={disabled} value={p.name} onChange={(e) => set({ params: draft.params.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)) })}/>
              <select aria-label={`Kiểu tham số ${i + 1}`} disabled={disabled} value={p.type} onChange={(e) => set({ params: draft.params.map((x, j) => (j === i ? { ...x, type: e.target.value as ParamDef["type"] } : x)) })}>{PARAM_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}</select>
              <label className="checkRow"><input type="checkbox" disabled={disabled} checked={paramRequired(p)} onChange={(e) => set({ params: draft.params.map((x, j) => (j === i ? setParamRequired(x, e.target.checked) : x)) })}/><span>Bắt buộc</span></label>
              <button type="button" className="smallButton danger" disabled={disabled} aria-label={`Xóa tham số ${i + 1}`} onClick={() => set({ params: draft.params.filter((_, j) => j !== i) })}>Xóa</button>
            </div>))}
          <button type="button" className="smallButton" disabled={disabled} onClick={() => set({ params: [...draft.params, newParam()] })}>+ Thêm tham số</button>
          {err("params")}
        </details>
      </fieldset>

      <fieldset className="bx-group"><legend>{W.columns.title}</legend>
        <p className="hint" data-testid="gb-columns"><b>{W.columns.asIs}</b>. {W.columns.note}</p>
      </fieldset>

      <fieldset className="bx-group"><legend>{W.who.title}</legend>
        <label className="checkRow"><input type="checkbox" data-testid="gb-public" disabled={disabled} checked={draft.public} onChange={(e) => set({ public: e.target.checked })}/><span>{W.who.public}</span></label>
        <p className="hint">{draft.public ? W.who.warning : W.who.publicOff}</p>
      </fieldset>

      {failed ? <p className="formError" role="alert" data-testid="gb-failed">{failed}</p> : null}
      <div className="bx-actions">
        <button type="button" className="bx-btn" disabled={pending} onClick={onCancel}>{W.cancel}</button>
        <button type="submit" className="bx-btn primary" data-testid="gb-save" disabled={disabled} aria-busy={pending}>{pending ? W.saving : W.save}</button>
      </div>
    </form>
  );
}
