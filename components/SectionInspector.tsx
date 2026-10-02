"use client";

import { useId, useMemo, useState } from "react";
import type { PropDef, RegistryComponent, SchemaOperation, Section } from "@/lib/http-types";

const LABELS: Record<string, string> = {
  title: "Tiêu đề", eyebrow: "Dòng nhỏ phía trên", description: "Mô tả", ctaLabel: "Chữ trên nút", heading: "Tiêu đề mục", name: "Tên",
  body: "Nội dung", brand: "Thương hiệu", visible: "Hiển thị mục này", quote: "Trích dẫn", author: "Tác giả", location: "Địa điểm",
  rating: "Số sao (0–5)", label: "Nhãn", values: "Giá trị (mỗi dòng một giá trị)", columns: "Tên các cột (mỗi dòng một cột)",
  submitLabel: "Chữ trên nút gửi", text: "Nội dung", theme: "Giao diện", items: "Danh sách", rows: "Các dòng", links: "Liên kết"
};
const label = (k: string) => LABELS[k] ?? k;
const TYPE_LABELS: Record<string, string> = {
  Navbar: "Thanh điều hướng", Hero: "Đầu trang (Hero)", ProductGrid: "Danh sách sản phẩm", ProductCard: "Thẻ sản phẩm", TechnologySection: "Công nghệ",
  ComparisonBlock: "Bảng so sánh", Testimonials: "Đánh giá khách hàng", ContactForm: "Form liên hệ", Footer: "Chân trang", LandingTemplate: "Mẫu trang"
};
export const sectionLabel = (type: string, fallback?: string) => TYPE_LABELS[type] ?? fallback ?? type;
type Json = unknown;
const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v));
const same = (a: Json, b: Json) => JSON.stringify(a) === JSON.stringify(b);
const lines = (v: Json) => (Array.isArray(v) ? v.map(String).join("\n") : "");
const toLines = (t: string) => t.split("\n").map((x) => x.trim()).filter(Boolean);

/** Heuristic one-line summary of a section for the outline. */
export function sectionSummary(s: Section): string {
  const p = s.props;
  for (const k of ["title", "heading", "brand", "text", "name"]) if (typeof p[k] === "string" && p[k]) return String(p[k]);
  return "";
}

/** Form generated from the component registry (props schema), compiled to the same schema operations the AI uses. */
export function SectionInspector({ section, component, index, count, readOnly, busy, onApply, onClose }: {
  section: Section; component?: RegistryComponent; index: number; count: number; readOnly: boolean; busy: boolean;
  onApply: (ops: SchemaOperation[], summary: string) => Promise<boolean>; onClose: () => void;
}) {
  const uid = useId();
  const defs: Record<string, PropDef> = useMemo(() => component?.versions.find((v) => v.version === component.latestVersion)?.propsSchema.properties ?? {}, [component]);
  const [draft, setDraft] = useState<Record<string, Json>>(() => clone(section.props));
  const dirty = !same(draft, section.props);
  const set = (k: string, v: Json) => setDraft((d) => ({ ...d, [k]: v }));

  function operations(): SchemaOperation[] {
    const ops: SchemaOperation[] = [];
    const id = section.id;
    for (const [key, def] of Object.entries(defs)) {
      const before = section.props[key], after = draft[key];
      if (same(before, after)) continue;
      if (def.type === "array" && def.itemProperties) {
        const oldItems = (before as Record<string, Json>[] | undefined) ?? [], newItems = (after as Record<string, Json>[] | undefined) ?? [];
        for (const o of oldItems) if (!newItems.some((n) => n.id === o.id)) ops.push({ type: "REMOVE_ITEM", sectionId: id, arrayPath: key, itemId: String(o.id) });
        for (const n of newItems) {
          const o = oldItems.find((x) => x.id === n.id);
          if (!o) { ops.push({ type: "ADD_ITEM", sectionId: id, arrayPath: key, item: n }); continue; }
          for (const f of Object.keys(def.itemProperties)) if (f !== "id" && !same(o[f], n[f])) ops.push({ type: "UPDATE_PROP", sectionId: id, arrayPath: key, itemId: String(n.id), path: f, value: n[f] ?? "" });
        }
      } else ops.push({ type: "UPDATE_PROP", sectionId: id, path: key, value: after ?? (def.type === "boolean" ? false : "") });
    }
    return ops;
  }

  const apply = async () => { const ops = operations(); if (ops.length) await onApply(ops, `Chỉnh sửa ${sectionLabel(section.type, component?.name)}`); };

  function field(key: string, def: PropDef, value: Json, onChange: (v: Json) => void, name: string) {
    const id = `${uid}-${name}`;
    const common = { id, disabled: readOnly || busy };
    if (def.type === "boolean") return <label className="checkRow" key={name}><input type="checkbox" {...common} checked={value !== false} onChange={(e) => onChange(e.target.checked)}/><span>{label(key)}</span></label>;
    if (def.enum) return <div className="insField" key={name}><label htmlFor={id}>{label(key)}</label><select {...common} value={String(value ?? def.enum[0])} onChange={(e) => onChange(e.target.value)}>{def.enum.map((o) => <option key={o} value={o}>{o}</option>)}</select></div>;
    if (def.type === "number") return <div className="insField" key={name}><label htmlFor={id}>{label(key)}</label><input {...common} type="number" min={0} max={5} value={typeof value === "number" ? value : ""} onChange={(e) => onChange(e.target.value === "" ? undefined : Number(e.target.value))}/></div>;
    if (def.type === "array") return <div className="insField" key={name}><label htmlFor={id}>{label(key)}</label><textarea {...common} rows={Math.min(6, Math.max(2, Array.isArray(value) ? value.length : 2))} value={lines(value)} onChange={(e) => onChange(toLines(e.target.value))}/></div>;
    const long = (def.maxLength ?? 0) > 150;
    return <div className="insField" key={name}><label htmlFor={id}>{label(key)}</label>{long
      ? <textarea {...common} rows={3} maxLength={def.maxLength} value={String(value ?? "")} onChange={(e) => onChange(e.target.value)}/>
      : <input {...common} maxLength={def.maxLength} value={String(value ?? "")} onChange={(e) => onChange(e.target.value)}/>}</div>;
  }

  return (
    <section className="inspector" aria-label={`Chỉnh sửa ${sectionLabel(section.type, component?.name)}`}>
      <div className="inspectorHead">
        <div><h2>{sectionLabel(section.type, component?.name)}</h2></div>
        <button className="button icon" aria-label="Đóng bảng chỉnh sửa" onClick={onClose}>✕</button>
      </div>
      {Object.entries(defs).map(([key, def]) => {
        if (def.type === "array" && def.itemProperties) {
          const items = (draft[key] as Record<string, Json>[] | undefined) ?? [];
          const required = Object.fromEntries((def.itemRequired ?? []).filter((r) => r !== "id").map((r) => [r, "Mới"]));
          return (
            <fieldset className="itemGroup" key={key}><legend>{label(key)} ({items.length})</legend>
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
        if (def.type === "array" && key === "links") return <p className="hint" key={key}>Liên kết điều hướng chỉnh bằng AI (ví dụ “thêm liên kết Bảng giá”).</p>;
        return field(key, def, draft[key], (v) => set(key, v), key);
      })}
      <div className="inspectorActions">
        <div className="moveButtons">
          <button type="button" className="smallButton" disabled={readOnly || busy || index === 0} onClick={() => void onApply([{ type: "MOVE_SECTION", sectionId: section.id, index: index - 1 }], "Di chuyển mục lên")}>↑ Lên</button>
          <button type="button" className="smallButton" disabled={readOnly || busy || index === count - 1} onClick={() => void onApply([{ type: "MOVE_SECTION", sectionId: section.id, index: index + 1 }], "Di chuyển mục xuống")}>↓ Xuống</button>
          <button type="button" className="smallButton danger" disabled={readOnly || busy} onClick={() => { if (window.confirm(`Xóa mục "${sectionLabel(section.type, component?.name)}" khỏi trang?`)) void onApply([{ type: "REMOVE_SECTION", sectionId: section.id }], "Xóa mục"); }}>Xóa mục</button>
        </div>
        {!readOnly ? <div className="saveRow"><button type="button" className="button ghost" disabled={!dirty || busy} onClick={() => setDraft(clone(section.props))}>Hoàn tác</button>
          <button type="button" className="button primary" disabled={!dirty || busy} onClick={() => void apply()}>{busy ? "Đang lưu…" : "Lưu thay đổi"}</button></div> : null}
      </div>
    </section>
  );
}
