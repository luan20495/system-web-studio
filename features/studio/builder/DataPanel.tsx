"use client";
/**
 * The "Dữ liệu" rail panel (M-005): what is connected, ONE guided form to connect more, and the complete 7-step wizard under "Nâng cao".
 * Everything the old panel could do is still reachable (the wizard is mounted unchanged); the guided form only covers the common case:
 * show a source's data in one property of the page, read-only.
 */
import { useRef, useState } from "react";
import { allSections, defOps } from "./core/definition";
import { DATA_WORDS as W } from "./core/dataWording";
import { connectedRows } from "./core/guidedBinding";
import { setPublicOp } from "./core/publicData";
import { DataWizard } from "./DataWizard";
import { GuidedBinding } from "./GuidedBinding";
import { propLabel } from "./PropsForm";
import { Dialog, StateBox } from "./ui/primitives";
import type { DefCtx } from "./ctx";

export type DataFocus = { sectionId?: string; prop?: string };

export function DataPanel({ ctx, focus }: { ctx: DefCtx; focus?: DataFocus }) {
  const { doc } = ctx;
  const rows = connectedRows(doc);
  const [adding, setAdding] = useState(Boolean(focus?.prop));
  const [message, setMessage] = useState<string | null>(null);
  const [removing, setRemoving] = useState<{ id: string; where: string } | null>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const disabled = !ctx.canEdit || ctx.busy;
  const label = (sectionId: string, type: string, prop: string) => { void sectionId; return `${ctx.labelOf(type)} › ${propLabel(prop)}`; };

  async function togglePublic(queryId: string, value: boolean) {
    const q = (doc.queries ?? []).find((x) => x.id === queryId); if (!q) return;
    const op = setPublicOp(q, value); if ("error" in op) { setMessage(op.error); return; }
    setMessage(null); await ctx.commit([op], `${value ? "Công khai" : "Ngừng công khai"} dữ liệu ${q.name || q.id}`);
  }

  return (
    <div className="bx-panel-body" data-testid="data-panel">
      <div className="bx-panel-head"><h2>{W.panelTitle}</h2></div>
      <p className="hint">{W.panelHint}</p>
      {ctx.readiness.state !== "AVAILABLE" ? <StateBox state={ctx.readiness}/> : null}

      <section aria-labelledby="data-connected-h">
        <h3 className="bx-h3" id="data-connected-h">{W.connectedTitle}</h3>
        {rows.length === 0 ? <p className="hint" data-testid="data-connected-empty">{W.connectedEmpty}</p> : (
          <ul className="bx-list" ref={listRef} tabIndex={-1} aria-label={W.connectedTitle} data-testid="data-connected">{rows.map((r) => {
            const where = label(r.sectionId, r.component, r.prop);
            return (
              <li key={r.id} data-testid={`connected:${r.id}`}>
                <div><b>{where}</b><small>← {r.dataset}{r.slotName ? ` · ${r.slotName}` : ""}</small>
                  <small className={r.public ? "" : "hint"} data-testid={`connected-public:${r.id}`}>{r.public ? W.publicBadge : W.privateBadge}</small></div>
                {!disabled ? <span className="bx-row-tools">
                  {r.queryId ? <button type="button" className="btn sm" onClick={() => void togglePublic(r.queryId!, !r.public)}>{r.public ? "Ngừng công khai" : "Cho khách xem"}</button> : null}
                  <button type="button" className="btn sm danger" aria-label={`${W.unlink} dữ liệu khỏi ${where}`} onClick={() => setRemoving({ id: r.id, where })}>{W.unlink}</button></span> : null}
              </li>);
          })}</ul>)}
        {message ? <p className="hint" role="status" data-testid="data-message">{message}</p> : null}
      </section>

      {!ctx.canEdit ? <p className="hint" data-testid="data-readonly">{W.readOnly}</p> : adding ? (
        <GuidedBinding ctx={ctx} focus={focus} onCancel={() => setAdding(false)}
          onDone={(m) => { setAdding(false); setMessage(m); requestAnimationFrame(() => listRef.current?.focus()); }}/>
      ) : ctx.readiness.state === "AVAILABLE" && allSections(doc).length > 0
        ? <p><button type="button" className="btn dense primary" data-testid="data-add" onClick={() => { setMessage(null); setAdding(true); }}>{W.addButton}</button></p> : null}

      <details className="bx-existing" data-testid="data-advanced"><summary>{W.advancedTitle}</summary>
        <p className="hint">{W.advancedHint}</p>
        <DataWizard ctx={ctx} focus={focus}/>
      </details>

      {removing ? (
        <Dialog title={W.unlinkTitle} onClose={() => setRemoving(null)} footer={<>
          <button type="button" className="btn dense" onClick={() => setRemoving(null)}>{W.cancel}</button>
          <button type="button" className="btn dense danger" disabled={ctx.busy} data-testid="data-unlink-confirm" onClick={() => void ctx.commit([defOps.remove("dataBindings", removing.id)], "Gỡ liên kết dữ liệu").then((ok) => { if (ok) setRemoving(null); })}>{W.unlink}</button></>}>
          <p><b>{removing.where}</b>: thành phần sẽ trở lại nội dung viết tay. Bộ dữ liệu vẫn còn và có thể dùng lại.</p>
        </Dialog>) : null}
    </div>
  );
}
