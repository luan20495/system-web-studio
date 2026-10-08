"use client";
/** Generic form for a group of component props (Content tab and Design tab). Generated from the registry's props schema; saved as the same page operations the AI uses. */
import { useId, useMemo, useRef, useState } from "react";
import type { AssetDto, PropDef, Section } from "@xweb/types";
import { propOperations } from "./core/inspector";
import type { SchemaOperation } from "@xweb/types";

const LABELS: Record<string, string> = {
  title: "Tiêu đề", eyebrow: "Dòng nhỏ phía trên", description: "Mô tả", ctaLabel: "Chữ trên nút", heading: "Tiêu đề mục", name: "Tên",
  body: "Nội dung", brand: "Thương hiệu", visible: "Hiển thị mục này", quote: "Trích dẫn", author: "Tác giả", location: "Địa điểm",
  rating: "Số sao (0–5)", image: "Ảnh", label: "Nhãn", values: "Giá trị (mỗi dòng một giá trị)", columns: "Tên các cột (mỗi dòng một cột)",
  submitLabel: "Chữ trên nút gửi", text: "Nội dung", theme: "Giao diện", items: "Danh sách", rows: "Các dòng", links: "Liên kết",
  align: "Căn lề", alignment: "Căn lề", size: "Kích thước", spacing: "Khoảng cách", variant: "Kiểu", background: "Nền", layout: "Bố cục", columns_: "Số cột",
  radius: "Bo góc", font: "Phông chữ", fontFamily: "Phông chữ",
};
export const propLabel = (k: string) => LABELS[k] ?? k;
const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);
const lines = (v: unknown) => (Array.isArray(v) ? v.map(String).join("\n") : "");
const toLines = (t: string) => t.split("\n").map((x) => x.trim()).filter(Boolean);

/**
 * An unsaved edit (M-002). It is kept by the HOST (Builder) per section, so it survives selecting another section; `base` is the saved props it was made on:
 * when the saved props change under it (another edit, a restored version) the draft is stale and is ignored instead of overwriting newer content.
 */
export type PropsDraft = { base: string; values: Record<string, unknown> };

export function PropsForm({ section, entries, allDefs, assets = [], readOnly, busy, onApply, summary, emptyText, draft: hostDraft, onDraft }: {
  section: Section; entries: [string, PropDef][]; allDefs: Record<string, PropDef>; assets?: AssetDto[]; readOnly: boolean; busy: boolean;
  onApply: (ops: SchemaOperation[], summary: string) => Promise<boolean>; summary: string; emptyText: string;
  /** host-held draft of THIS form (omit both props to keep the draft local to the form, as in the SSR tests) */
  draft?: PropsDraft | null; onDraft?: (d: PropsDraft | null) => void;
}) {
  const uid = useId();
  const keys = useMemo(() => new Set(entries.map(([k]) => k)), [entries]);
  const formRef = useRef<HTMLDivElement>(null); const lastField = useRef<string | null>(null);
  const [localDraft, setLocalDraft] = useState<PropsDraft | null>(null);
  const stored = onDraft ? hostDraft ?? null : localDraft; const store = onDraft ?? setLocalDraft;
  const base = JSON.stringify(section.props);
  const draft: Record<string, unknown> = stored && stored.base === base ? stored.values : section.props;
  const dirty = entries.some(([k]) => !same(draft[k], section.props[k]));
  const set = (k: string, v: unknown) => {
    const next = { ...draft, [k]: v };
    store(entries.some(([kk]) => !same(next[kk], section.props[kk])) ? { base, values: next } : null);
  };
  const disabled = readOnly || busy;
  async function save() {
    const ops = propOperations(section, allDefs, draft, keys);
    if (!ops.length) return;
    const ok = await onApply(ops, summary);
    if (!ok) return;                                  // a failed save keeps the draft
    store(null);
    // the Save button is disabled again once the form is clean: put focus back on the control the person was editing (never <body>)
    requestAnimationFrame(() => { const el = (lastField.current ? document.getElementById(lastField.current) : null) ?? formRef.current?.querySelector<HTMLElement>("input,select,textarea"); el?.focus(); });
  }

  function field(key: string, def: PropDef, value: unknown, onChange: (v: unknown) => void, name: string) {
    const id = `${uid}-${name}`;
    const common = { id, disabled };
    if (def.format === "asset") {
      const images = assets.filter((a) => a.status === "READY" && a.contentType.startsWith("image/"));
      return <div className="insField" key={name}><label htmlFor={id}>{propLabel(key)}</label>
        <select {...common} value={typeof value === "string" ? value : ""} onChange={(e) => onChange(e.target.value)}>
          <option value="">Không có ảnh</option>{images.map((a) => <option key={a.id} value={`asset://${a.id}`}>{a.name}</option>)}
        </select>{images.length === 0 ? <small className="hint">Tải ảnh lên ở mục “Tệp” để dùng tại đây.</small> : null}</div>;
    }
    if (def.type === "boolean") return <label className="checkRow" key={name}><input type="checkbox" {...common} checked={value !== false} onChange={(e) => onChange(e.target.checked)}/><span>{propLabel(key)}</span></label>;
    if (def.enum) return <div className="insField" key={name}><label htmlFor={id}>{propLabel(key)}</label><select {...common} value={String(value ?? def.enum[0])} onChange={(e) => onChange(e.target.value)}>{def.enum.map((o) => <option key={o} value={o}>{o}</option>)}</select></div>;
    if (def.type === "number") return <div className="insField" key={name}><label htmlFor={id}>{propLabel(key)}</label><input {...common} type="number" value={typeof value === "number" ? value : ""} onChange={(e) => onChange(e.target.value === "" ? undefined : Number(e.target.value))}/></div>;
    if (def.type === "array") return <div className="insField" key={name}><label htmlFor={id}>{propLabel(key)}</label><textarea {...common} rows={Math.min(6, Math.max(2, Array.isArray(value) ? value.length : 2))} value={lines(value)} onChange={(e) => onChange(toLines(e.target.value))}/></div>;
    const long = (def.maxLength ?? 0) > 150;
    return <div className="insField" key={name}><label htmlFor={id}>{propLabel(key)}</label>{long
      ? <textarea {...common} rows={3} maxLength={def.maxLength} value={String(value ?? "")} onChange={(e) => onChange(e.target.value)}/>
      : <input {...common} maxLength={def.maxLength} value={String(value ?? "")} onChange={(e) => onChange(e.target.value)}/>}</div>;
  }

  if (!entries.length) return <p className="hint">{emptyText}</p>;
  return (
    <div className="bx-propsform" ref={formRef} onFocusCapture={(e) => { const t = e.target as HTMLElement; if (t.id && /^(INPUT|SELECT|TEXTAREA)$/.test(t.tagName)) lastField.current = t.id; }}>
      {entries.map(([key, def]) => {
        if (def.type === "array" && def.itemProperties) {
          const items = (draft[key] as Record<string, unknown>[] | undefined) ?? [];
          const required = Object.fromEntries((def.itemRequired ?? []).filter((r) => r !== "id").map((r) => [r, "Mới"]));
          return (
            <fieldset className="itemGroup" key={key}><legend>{propLabel(key)} ({items.length})</legend>
              {items.map((item, i) => (
                <div className="itemCard" key={String(item.id)}>
                  {Object.entries(def.itemProperties!).filter(([f]) => f !== "id").map(([f, fd]) =>
                    field(f, fd, item[f], (v) => set(key, items.map((x, j) => (j === i ? { ...x, [f]: v } : x))), `${key}-${i}-${f}`))}
                  {!readOnly ? <button type="button" className="smallButton danger" onClick={() => set(key, items.filter((_, j) => j !== i))}>Xóa mục {i + 1}</button> : null}
                </div>))}
              {!readOnly && items.length < (def.maxItems ?? 24) ? <button type="button" className="smallButton" onClick={() => set(key, [...items, { id: `i${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`, ...required }])}>+ Thêm mục</button> : null}
            </fieldset>
          );
        }
        if (def.type === "array" && key === "links") return <p className="hint" key={key}>Liên kết điều hướng đổi ở mục “Trang” bên trái.</p>;
        return field(key, def, draft[key], (v) => set(key, v), key);
      })}
      {!readOnly ? (
        <div className="saveRow">
          {dirty ? <small className="hint" role="status">Có thay đổi chưa lưu.</small> : null}
          <button type="button" className="button ghost" disabled={!dirty || busy} onClick={() => store(null)}>Hoàn tác</button>
          <button type="button" className="button primary" disabled={!dirty || busy} onClick={() => void save()}>{busy ? "Đang lưu…" : "Lưu thay đổi"}</button>
        </div>
      ) : <p className="hint">Bạn chỉ có quyền xem.</p>}
    </div>
  );
}
