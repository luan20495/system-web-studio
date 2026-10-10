"use client";
/**
 * Unit types: the company defines its own kinds of node (Khối, Chi nhánh, Phòng, Team…) and the placement rules between them (which type may sit under which, whether a type may be a root, an optional
 * maximum depth). Nothing is hard-coded: a rule is data on the type, and the server enforces it (409 ORG_TYPE_RULE_VIOLATION, also when a rules change would break the tree that exists now).
 * Creating, renaming, changing the icon / rules and disabling / enabling a type are real operations (ORG_STRUCTURE_MANAGE); the code never changes. A type is never deleted.
 */
import { useId, useState, type FormEvent } from "react";
import { ModalHeader, Pencil, Power, Settings2 } from "@xweb/ui";
import { Modal } from "./Modal";
import type { NewOrgUnitType, OrganizationApi, OrgUnitType } from "./organization";
import { UNIT_ICONS, emptyRulesForm, formFromRules, orgProblem, rulesFromForm, rulesSummary, safeIcon, validateTypeForm, type OrgProblem, type OrganizationPlan, type RulesForm } from "./organizationModel";
import { NotReadyPanel, Problem } from "./orgParts";
import { UnitIcon } from "./unitIcons";

export function TypesDialog({ tenantId, api, plan, types, onClose, onChanged }: { tenantId: string; api: OrganizationApi; plan: OrganizationPlan; types: OrgUnitType[]; onClose: () => void; onChanged: () => void }) {
  const uid = useId();
  const [editing, setEditing] = useState<OrgUnitType | null>(null);
  const [name, setName] = useState(""); const [code, setCode] = useState(""); const [icon, setIcon] = useState("folder"); const [rules, setRules] = useState<RulesForm>(emptyRulesForm());
  const [touched, setTouched] = useState(false); const [busy, setBusy] = useState(false); const [problem, setProblem] = useState<OrgProblem | null>(null);
  const [again, setAgain] = useState<(() => Promise<void>) | null>(null); const [flash, setFlash] = useState<string | null>(null);
  const manage = plan.typesManage.state === "ready";
  const errors = validateTypeForm({ name, code, icon, maxDepth: rules.maxDepth }, types, !!editing);

  /** every write goes through here: a refusal keeps the dialog (and the person's input) and offers the SAME request again */
  async function act(fn: () => Promise<void>) {
    setBusy(true); setProblem(null); setFlash(null); setAgain(() => fn);
    try { await fn(); } catch (err) { setProblem(orgProblem(err)); } finally { setBusy(false); }
  }
  const reset = () => { setEditing(null); setName(""); setCode(""); setIcon("folder"); setRules(emptyRulesForm()); setTouched(false); };
  function startEdit(t: OrgUnitType) { setEditing(t); setName(t.name); setCode(t.code); setIcon(safeIcon(t.icon)); setRules(formFromRules(t.rules)); setTouched(false); setProblem(null); setFlash(null); }
  function submit(e: FormEvent) {
    e.preventDefault(); setTouched(true); if (Object.keys(errors).length || !manage) return;
    const r = rulesFromForm(rules);
    if (editing) void act(async () => { await api.updateOrganizationUnitType(tenantId, editing.id, editing.version, { name, icon, rules: r }); setFlash("Đã lưu loại đơn vị."); reset(); onChanged(); });
    else { const body: NewOrgUnitType = { name, code, icon, rules: r }; void act(async () => { await api.createOrganizationUnitType(tenantId, body); setFlash("Đã thêm loại đơn vị."); reset(); onChanged(); }); }
  }
  const toggle = (t: OrgUnitType) => act(async () => { await api.setOrganizationUnitTypeActive(tenantId, t.id, t.version, !t.active); setFlash(t.active ? "Đã tắt loại đơn vị." : "Đã bật loại đơn vị."); onChanged(); });
  const others = types.filter((t) => t.id !== editing?.id);
  const tick = (list: string[], id: string, on: boolean) => (on ? [...list, id] : list.filter((x) => x !== id));

  return (
    <Modal label="Loại đơn vị" onClose={onClose}>
      <div className="modalBody" data-testid="types-dialog">
        <ModalHeader icon={<Settings2 size={22}/>} title="Loại đơn vị" subtitle="Công ty tự định nghĩa các loại (Khối, Chi nhánh, Phòng, Team…) và quy tắc đặt chỗ giữa chúng. Không có cấp bậc cố định."/>
        <section className="xp-section" aria-label="Các loại hiện có">
          <h3>Các loại hiện có</h3>
          {plan.types.state === "not-ready" ? <NotReadyPanel testid="types-not-ready" title="Chưa sẵn sàng" reason={plan.types.reason}/> : types.length === 0 ? <p className="hint" data-testid="types-empty">Chưa có loại nào.</p> : (
            <ul className="xp-typeList" data-testid="types-list">{types.map((t) => (
              <li key={t.id} data-testid={`type:${t.code}`} data-active={t.active}>
                <span className="xp-nodeIcon"><UnitIcon id={t.icon}/></span>
                <span className="xp-pickerCur"><b>{t.name}</b> <small>{t.code}{t.active ? "" : " · đang tắt"}</small><small data-testid={`type-rules:${t.code}`}>{rulesSummary(t.rules, types).join(" · ")}</small></span>
                {manage ? <span className="row">
                  <button type="button" className="btn sm xp-btnIcon" data-testid={`type-edit:${t.code}`} disabled={busy} onClick={() => startEdit(t)}><Pencil size={14} aria-hidden="true"/> Sửa</button>
                  <button type="button" className="btn sm xp-btnIcon" data-testid={`type-toggle:${t.code}`} disabled={busy} onClick={() => void toggle(t)}><Power size={14} aria-hidden="true"/> {t.active ? "Tắt" : "Bật"}</button></span> : null}
              </li>))}</ul>)}
        </section>
        {flash ? <p className="notice" role="status" data-testid="types-flash">{flash}</p> : null}
        <form className="xp-section" noValidate onSubmit={submit} aria-label={editing ? "Sửa loại đơn vị" : "Thêm loại đơn vị"} data-testid="type-form">
          <h3>{editing ? `Sửa loại “${editing.name}”` : "Thêm loại mới"}</h3>
          {!manage ? <NotReadyPanel testid="type-create-not-ready" title="Chưa thay đổi được loại" reason={(plan.typesManage as { reason: string }).reason}/> : null}
          <label className="field"><span>Tên loại</span><input data-testid="type-name" value={name} maxLength={120} autoComplete="off" placeholder="Ví dụ: Khối" disabled={!manage} aria-invalid={touched && !!errors.name} aria-describedby={touched && errors.name ? `${uid}-tname-err` : undefined} onChange={(e) => setName(e.target.value)}/></label>
          {touched && errors.name ? <p className="formError" role="alert" id={`${uid}-tname-err`}>{errors.name}</p> : null}
          <label className="field"><span>Mã</span><input data-testid="type-code" value={code} autoComplete="off" spellCheck={false} placeholder="division" disabled={!manage || !!editing} aria-invalid={touched && !!errors.code} aria-describedby={touched && errors.code ? `${uid}-tcode-err` : undefined} onChange={(e) => setCode(e.target.value.toLowerCase())}/></label>
          {editing ? <small className="hint">Mã không đổi được sau khi tạo.</small> : null}
          {touched && errors.code ? <p className="formError" role="alert" id={`${uid}-tcode-err`}>{errors.code}</p> : null}
          <div className="field" role="radiogroup" aria-label="Biểu tượng"><span>Biểu tượng</span>
            <div className="xp-iconGrid">{UNIT_ICONS.map((i) => (
              <button key={i.id} type="button" role="radio" aria-checked={icon === i.id} aria-label={i.label} title={i.label} data-testid={`icon:${i.id}`} className={`xp-iconBtn${icon === i.id ? " sel" : ""}`} disabled={!manage} onClick={() => setIcon(safeIcon(i.id))}><UnitIcon id={i.id} size={20}/></button>))}</div>
            <small className="hint">Chỉ chọn trong bộ biểu tượng có sẵn; không dùng đường dẫn ảnh.</small></div>

          <fieldset className="xp-parentTypes" aria-label="Quy tắc đặt chỗ" data-testid="type-rules"><legend className="hint">Quy tắc đặt chỗ (máy chủ kiểm tra; để mặc định = đặt ở bất kỳ đâu)</legend>
            <label className="field"><span>Được đặt dưới</span>
              <select data-testid="rule-parents" value={rules.parents} disabled={!manage} onChange={(e) => setRules({ ...rules, parents: e.target.value as RulesForm["parents"] })}><option value="any">Mọi loại</option><option value="listed">Chỉ các loại được chọn (không chọn = chỉ làm đơn vị gốc)</option></select></label>
            {rules.parents === "listed" ? <div className="xp-checkList" aria-label="Các loại cha được phép">{others.map((t) => <label key={t.id} className="xp-check"><input type="checkbox" data-testid={`rule-parent:${t.code}`} disabled={!manage} checked={rules.parentIds.includes(t.id)} onChange={(e) => setRules({ ...rules, parentIds: tick(rules.parentIds, t.id, e.target.checked) })}/> {t.name}</label>)}
              {editing ? <label className="xp-check"><input type="checkbox" disabled={!manage} checked={rules.parentIds.includes(editing.id)} onChange={(e) => setRules({ ...rules, parentIds: tick(rules.parentIds, editing.id, e.target.checked) })}/> {editing.name} (chính loại này)</label> : null}</div> : null}
            <label className="field"><span>Chứa các loại</span>
              <select data-testid="rule-children" value={rules.children} disabled={!manage} onChange={(e) => setRules({ ...rules, children: e.target.value as RulesForm["children"] })}><option value="any">Mọi loại</option><option value="listed">Chỉ các loại được chọn (không chọn = không chứa đơn vị con)</option></select></label>
            {rules.children === "listed" ? <div className="xp-checkList" aria-label="Các loại con được phép">{others.map((t) => <label key={t.id} className="xp-check"><input type="checkbox" data-testid={`rule-child:${t.code}`} disabled={!manage} checked={rules.childIds.includes(t.id)} onChange={(e) => setRules({ ...rules, childIds: tick(rules.childIds, t.id, e.target.checked) })}/> {t.name}</label>)}
              {editing ? <label className="xp-check"><input type="checkbox" disabled={!manage} checked={rules.childIds.includes(editing.id)} onChange={(e) => setRules({ ...rules, childIds: tick(rules.childIds, editing.id, e.target.checked) })}/> {editing.name} (chính loại này)</label> : null}</div> : null}
            <label className="field"><span>Làm đơn vị gốc</span>
              <select data-testid="rule-root" value={rules.allowRoot} disabled={!manage} onChange={(e) => setRules({ ...rules, allowRoot: e.target.value as RulesForm["allowRoot"] })}><option value="default">Theo quy tắc “được đặt dưới”</option><option value="yes">Được phép</option><option value="no">Không được phép</option></select></label>
            <label className="field"><span>Độ sâu tối đa (không bắt buộc)</span><input data-testid="rule-maxdepth" inputMode="numeric" value={rules.maxDepth} disabled={!manage} autoComplete="off" placeholder="Để trống = không giới hạn" aria-invalid={touched && !!errors.maxDepth} onChange={(e) => setRules({ ...rules, maxDepth: e.target.value })}/></label>
            <small className="hint">Độ sâu tối đa là số cấp tính từ gốc (gốc = cấp 1). Đây là quy tắc của công ty; máy chủ quyết định, kể cả với các đơn vị con khi chuyển một nhánh.</small>
            {touched && errors.maxDepth ? <p className="formError" role="alert">{errors.maxDepth}</p> : null}
          </fieldset>
          {problem ? <Problem p={problem} onReload={onChanged} onRetry={again ? () => void act(again) : undefined} retrying={busy} reloadLabel="Tải lại danh sách loại" testid="types-problem"/> : null}
          <div className="xp-footer"><button type="button" className="btn" onClick={onClose}>Đóng</button>
            {editing ? <button type="button" className="btn" data-testid="type-cancel-edit" onClick={reset}>Bỏ sửa</button> : null}
            <button className="btn primary" data-testid="type-submit" disabled={busy || !manage}>{busy ? "Đang lưu…" : editing ? "Lưu loại" : "Thêm loại"}</button></div>
        </form>
      </div>
    </Modal>
  );
}
