"use client";
/** Components: the approved registry (draggable onto the canvas; click-to-add is the fallback), the data components the UI wants (NOT_READY unless the registry really has them) and ready-made blocks. */
import { useMemo, useState } from "react";
import { useDraggable } from "@dnd-kit/core";
import type { RegistryComponent } from "@xweb/types";
import { dataComponents, libraryEntries, type LibraryEntry } from "../core/library";

export type BlockOption = { id: string; name: string; who: string; baseLabel: string };

export function ComponentsPanel({ registry, blocks, canEdit, busy, onAdd, onAddBlock }: {
  registry: RegistryComponent[]; blocks: BlockOption[]; canEdit: boolean; busy: boolean; onAdd: (componentId: string) => void; onAddBlock: (id: string) => void;
}) {
  const [q, setQ] = useState("");
  const entries = useMemo(() => libraryEntries(registry), [registry]);
  const data = useMemo(() => dataComponents(registry), [registry]);
  const shown = entries.filter((e) => !q.trim() || `${e.label} ${e.category}`.toLowerCase().includes(q.trim().toLowerCase()));
  if (!canEdit) return <div className="bx-panel-body"><div className="bx-panel-head"><h2>Thành phần</h2></div><p className="hint">Bạn chỉ có quyền xem ứng dụng này.</p></div>;
  return (
    <div className="bx-panel-body">
      <div className="bx-panel-head"><h2>Thành phần</h2></div>
      <p className="hint">Component đã được công ty duyệt. Kéo vào bản xem trước, hoặc nhấn “Thêm” (có thể dùng bàn phím). Không có component nào ngoài registry.</p>
      <label className="srOnly" htmlFor="lib-search">Tìm component</label>
      <input id="lib-search" type="search" placeholder="Tìm component…" value={q} onChange={(e) => setQ(e.target.value)}/>
      <ul className="bx-lib" aria-label="Thư viện component">
        {shown.map((e) => <LibItem key={e.id} entry={e} busy={busy} onAdd={() => onAdd(e.id)}/>)}
        {shown.length === 0 ? <li className="hint">Không có component phù hợp.</li> : null}
      </ul>

      <h3 className="bx-h3">Thành phần dữ liệu</h3>
      <ul className="bx-lib" aria-label="Thành phần dữ liệu">
        {data.map((d) => d.available
          ? <LibItem key={d.id} entry={{ id: d.id, label: d.label, category: d.purpose, disabled: false }} busy={busy} onAdd={() => onAdd(d.id)}/>
          : <li key={d.id} className="bx-lib-item notready" data-state="NOT_READY"><div><b>{d.label}</b><small>{d.purpose}</small></div><span className="chip chip-notready" title={d.reason}>Chưa sẵn sàng</span><p className="hint">{d.reason}</p></li>)}
      </ul>

      <h3 className="bx-h3">Khối dựng sẵn</h3>
      {blocks.length === 0 ? <p className="hint">Chưa có khối nào. Chọn một mục rồi “Lưu thành khối”.</p> : (
        <ul className="bx-lib" aria-label="Khối dựng sẵn">{blocks.map((b) => (
          <li key={`${b.who}-${b.id}`} className="bx-lib-item"><div><b>{b.name}</b><small>{b.who} · {b.baseLabel}</small></div>
            <button type="button" className="bx-btn sm" disabled={busy} aria-label={`Thêm khối ${b.name} vào trang`} onClick={() => onAddBlock(b.id)}>Thêm</button></li>))}</ul>)}
    </div>
  );
}

function LibItem({ entry, busy, onAdd }: { entry: LibraryEntry; busy: boolean; onAdd: () => void }) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: `lib:${entry.id}`, disabled: entry.disabled || busy });
  return (
    <li className={`bx-lib-item${entry.disabled ? " disabled" : ""}`} style={{ opacity: isDragging ? 0.5 : undefined }}>
      <button ref={setNodeRef} type="button" className="bx-drag" aria-label={`Kéo ${entry.label} vào trang`} title={entry.disabled ? entry.reason : `Kéo ${entry.label} vào trang`} disabled={entry.disabled || busy} {...attributes} {...listeners}>⋮⋮</button>
      <div><b>{entry.label}</b><small>{entry.category}{entry.disabled ? ` · ${entry.reason}` : ""}</small></div>
      <button type="button" className="bx-btn sm" disabled={entry.disabled || busy} aria-label={`Thêm ${entry.label} vào trang`} onClick={onAdd}>Thêm</button>
    </li>
  );
}
