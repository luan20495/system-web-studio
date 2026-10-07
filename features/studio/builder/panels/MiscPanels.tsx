"use client";
/** Forms, Theme and AI panels of the left rail. */
import { useState } from "react";
import type { ThemeDef } from "@xweb/types";
import { THEME_FONTS, THEME_RADII } from "../core/contract";
import { allSections, defOps } from "../core/definition";
import { actionsOfSection } from "../core/actions";
import type { DefCtx } from "../ctx";
import { Field, Gate, StateBox } from "../ui/primitives";

const isForm = (type: string) => /form$/i.test(type);

export function FormsPanel({ ctx, onSelect, onNewAction, openSite }: { ctx: DefCtx; onSelect: (sectionId: string, pageId: string) => void; onNewAction: (sectionId: string) => void; openSite: () => void }) {
  const forms = allSections(ctx.doc).filter((s) => isForm(s.type));
  return (
    <div className="bx-panel-body">
      <div className="bx-panel-head"><h2>Biểu mẫu</h2></div>
      <p className="hint">Các biểu mẫu trong ứng dụng. Gửi vào nguồn dữ liệu cần một hành động “Gửi biểu mẫu”.</p>
      {forms.length === 0 ? <p className="hint">Ứng dụng chưa có biểu mẫu. Thêm “Form liên hệ” từ mục Thành phần.</p> : (
        <ul className="bx-list" aria-label="Biểu mẫu">{forms.map((s) => {
          const acts = actionsOfSection(ctx.doc, s.id).filter((a) => a.type === "SUBMIT_FORM");
          return (
            <li key={s.id}><div><b>{ctx.labelOf(s.type)}</b><small>{s.id} · {acts.length ? `${acts.length} hành động gửi` : "chưa gửi vào dữ liệu"}</small></div>
              <span className="bx-row-tools"><button type="button" className="smallButton" onClick={() => onSelect(s.id, s.pageId)}>Chọn</button>
                <button type="button" className="smallButton" disabled={!ctx.canEdit || ctx.readiness.state !== "AVAILABLE"} title={ctx.readiness.state === "AVAILABLE" ? undefined : "Chưa sẵn sàng: máy chủ chưa nhận thao tác hành động"} onClick={() => onNewAction(s.id)}>Thêm hành động gửi</button></span></li>);
        })}</ul>)}
      <StateBox state={ctx.readiness} compact/>
      <p><button type="button" className="bx-btn sm" onClick={openSite}>Tin gửi về, tên miền, SEO…</button></p>
    </div>
  );
}

const HEX = /^#[0-9a-fA-F]{6}$/;
export function ThemePanel({ ctx }: { ctx: DefCtx }) {
  const t: ThemeDef = ctx.doc.theme ?? {};
  const [colors, setColors] = useState<[string, string][]>(Object.entries(t.colors ?? {}));
  const [font, setFont] = useState(t.fontFamily ?? "");
  const [radius, setRadius] = useState(t.radius ?? "");
  const [err, setErr] = useState<string | null>(null);
  const disabled = !ctx.canEdit || ctx.busy;
  async function save() {
    const bad = colors.find(([k, v]) => !/^[A-Za-z][A-Za-z0-9_]{0,31}$/.test(k) || !HEX.test(v));
    if (bad) { setErr("Tên màu gồm chữ/số và màu phải là mã #RRGGBB."); return; }
    setErr(null);
    await ctx.commit([defOps.updateTheme({ colors: Object.fromEntries(colors), fontFamily: font || null, radius: radius || null })], "Cập nhật giao diện");
  }
  return (
    <div className="bx-panel-body">
      <div className="bx-panel-head"><h2>Giao diện</h2></div>
      <p className="hint">Màu (#RRGGBB), phông và độ bo góc chọn từ danh sách cố định: không có CSS tuỳ ý hay địa chỉ phông.</p>
      <Gate state={ctx.readiness}>
        <form className="bx-form" onSubmit={(e) => { e.preventDefault(); void save(); }}>
          <Field label="Phông chữ">{(id) => <select id={id} disabled={disabled} value={font} onChange={(e) => setFont(e.target.value)}><option value="">Mặc định</option>{THEME_FONTS.map((f) => <option key={f} value={f}>{f}</option>)}</select>}</Field>
          <Field label="Bo góc">{(id) => <select id={id} disabled={disabled} value={radius} onChange={(e) => setRadius(e.target.value)}><option value="">Mặc định</option>{THEME_RADII.map((f) => <option key={f} value={f}>{f}</option>)}</select>}</Field>
          <fieldset className="bx-group"><legend>Màu</legend>
            {colors.map(([k, v], i) => (
              <div className="bx-row" key={i}>
                <input aria-label={`Tên màu ${i + 1}`} disabled={disabled} value={k} onChange={(e) => setColors(colors.map((c, j) => (j === i ? [e.target.value, c[1]] : c)))}/>
                <input aria-label={`Mã màu ${i + 1}`} disabled={disabled} value={v} placeholder="#RRGGBB" aria-invalid={!HEX.test(v)} onChange={(e) => setColors(colors.map((c, j) => (j === i ? [c[0], e.target.value] : c)))}/>
                {!disabled ? <button type="button" className="smallButton danger" aria-label={`Xóa màu ${k || i + 1}`} onClick={() => setColors(colors.filter((_, j) => j !== i))}>Xóa</button> : null}
              </div>))}
            {!disabled && colors.length < 12 ? <button type="button" className="smallButton" onClick={() => setColors([...colors, ["", "#000000"]])}>+ Thêm màu</button> : null}
          </fieldset>
          <p className="hint" role="note">Bản xem trước hiện chưa áp dụng giao diện này (bộ dựng xem trước chưa hỗ trợ). Giá trị vẫn được lưu trong ứng dụng.</p>
          {err ? <p className="formError" role="alert">{err}</p> : null}
          <div className="bx-actions"><button type="submit" className="bx-btn primary" disabled={disabled}>Lưu giao diện</button></div>
        </form>
      </Gate>
    </div>
  );
}

export function AiPanel({ openAi, canEdit }: { openAi: () => void; canEdit: boolean }) {
  return (
    <div className="bx-panel-body">
      <div className="bx-panel-head"><h2>AI</h2></div>
      <p className="hint">Mô tả thay đổi bằng lời. Kết quả đi qua cùng một cổng kiểm tra như chỉnh tay và được lưu thành phiên bản có thể khôi phục.</p>
      <p><button type="button" className="bx-btn primary" disabled={!canEdit} onClick={openAi}>Mở chế độ AI</button></p>
      {!canEdit ? <p className="hint">Bạn không có quyền chỉnh sửa ứng dụng này.</p> : null}
    </div>
  );
}
