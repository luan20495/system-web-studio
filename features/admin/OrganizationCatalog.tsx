"use client";
/**
 * Positions and grades: two separate catalogs of the company (a position is "what the person does", a grade is "how senior"; neither is an organization unit and NEITHER GRANTS ANY PERMISSION).
 * Viewing needs POSITION_GRADE_VIEW, creating / renaming / disabling needs POSITION_GRADE_MANAGE (organizationModel.organizationPlan); the codes are unique per company (also against disabled records) and never change.
 * A disabled position or grade stays on the people who already hold it but cannot be given to anyone new.
 */
import { useId, useState, type FormEvent } from "react";
import { Briefcase, ModalHeader, Pencil, Power } from "@xweb/ui";
import { Modal } from "./Modal";
import { StateView } from "../ui";
import { useLoad } from "../useLoad";
import type { Grade, OrganizationApi, Position } from "./organization";
import { orgProblem, validateCatalogForm, type OrgProblem, type OrganizationPlan } from "./organizationModel";
import { NotReadyPanel, Problem } from "./orgParts";

type Kind = "position" | "grade";
type Row = Position | Grade;
const WORDS: Record<Kind, { one: string; title: string; created: string; saved: string; on: string; off: string; empty: string }> = {
  position: { one: "vị trí", title: "Vị trí", created: "Đã thêm vị trí.", saved: "Đã lưu vị trí.", on: "Đã bật vị trí.", off: "Đã tắt vị trí.", empty: "Chưa có vị trí nào (ví dụ: Kỹ sư, Trưởng phòng)." },
  grade: { one: "cấp bậc", title: "Cấp bậc", created: "Đã thêm cấp bậc.", saved: "Đã lưu cấp bậc.", on: "Đã bật cấp bậc.", off: "Đã tắt cấp bậc.", empty: "Chưa có cấp bậc nào (ví dụ: Junior, Senior)." },
};

export function CatalogDialog({ tenantId, api, plan, onClose }: { tenantId: string; api: OrganizationApi; plan: OrganizationPlan; onClose: () => void }) {
  const positions = useLoad(async () => api.listPositions(tenantId), [tenantId]);
  const grades = useLoad(async () => api.listGrades(tenantId), [tenantId]);
  const manage = plan.catalogManage.state === "ready";
  return (
    <Modal label="Vị trí và cấp bậc" onClose={onClose}>
      <div className="modalBody" data-testid="catalog-dialog">
        <ModalHeader icon={<Briefcase size={22}/>} title="Vị trí và cấp bậc" subtitle="Hai danh mục riêng của công ty. Chúng mô tả công việc và thâm niên, không cấp quyền truy cập."/>
        {!manage ? <NotReadyPanel testid="catalog-readonly" title="Chỉ xem" reason={(plan.catalogManage as { reason: string }).reason}/> : null}
        <Section kind="position" tenantId={tenantId} api={api} manage={manage} load={positions}/>
        <Section kind="grade" tenantId={tenantId} api={api} manage={manage} load={grades}/>
        <div className="xp-footer"><button type="button" className="btn" data-testid="catalog-close" onClick={onClose}>Đóng</button></div>
      </div>
    </Modal>
  );
}

function Section({ kind, tenantId, api, manage, load }: { kind: Kind; tenantId: string; api: OrganizationApi; manage: boolean; load: { data: Row[] | null | undefined; error: unknown; loading: boolean; reload: () => void } }) {
  const uid = useId(); const w = WORDS[kind]; const rows = load.data ?? [];
  const [editing, setEditing] = useState<Row | null>(null);
  const [name, setName] = useState(""); const [code, setCode] = useState(""); const [description, setDescription] = useState(""); const [rank, setRank] = useState("");
  const [touched, setTouched] = useState(false); const [busy, setBusy] = useState(false); const [problem, setProblem] = useState<OrgProblem | null>(null);
  const [again, setAgain] = useState<(() => Promise<void>) | null>(null); const [flash, setFlash] = useState<string | null>(null);
  const errors = validateCatalogForm({ name, code, rank, description }, rows, { editing: !!editing, withRank: kind === "grade" });
  const reset = () => { setEditing(null); setName(""); setCode(""); setDescription(""); setRank(""); setTouched(false); };
  async function act(fn: () => Promise<void>) {
    setBusy(true); setProblem(null); setFlash(null); setAgain(() => fn);
    try { await fn(); } catch (err) { setProblem(orgProblem(err)); } finally { setBusy(false); }
  }
  function startEdit(r: Row) { setEditing(r); setName(r.name); setCode(r.code); setDescription(r.description ?? ""); setRank("rank" in r && r.rank !== null ? String(r.rank) : ""); setTouched(false); setProblem(null); setFlash(null); }
  function submit(e: FormEvent) {
    e.preventDefault(); setTouched(true); if (Object.keys(errors).length || !manage) return;
    const desc = description.trim(); const rk = rank.trim() ? Number(rank.trim()) : null;
    if (editing) {
      const e0 = editing;
      void act(async () => {
        if (kind === "position") await api.updatePosition(tenantId, e0.id, e0.version, { name, description: desc });
        else await api.updateGrade(tenantId, e0.id, e0.version, { name, description: desc, ...(rk === null ? { clearRank: true } : { rank: rk }) });
        setFlash(w.saved); reset(); load.reload();
      });
    } else {
      void act(async () => {
        if (kind === "position") await api.createPosition(tenantId, { name, code, ...(desc ? { description: desc } : {}) });
        else await api.createGrade(tenantId, { name, code, ...(rk === null ? {} : { rank: rk }), ...(desc ? { description: desc } : {}) });
        setFlash(w.created); reset(); load.reload();
      });
    }
  }
  const toggle = (r: Row) => act(async () => { await (kind === "position" ? api.setPositionActive(tenantId, r.id, r.version, !r.active) : api.setGradeActive(tenantId, r.id, r.version, !r.active)); setFlash(r.active ? w.off : w.on); load.reload(); });
  return (
    <section className="xp-section" aria-label={w.title} data-testid={`catalog-${kind}`}>
      <h3>{w.title}</h3>
      {load.error ? (() => { const p = orgProblem(load.error); return <StateView kind={p.kind === "forbidden" ? "forbidden" : "error"} compact title={`Không tải được ${w.one}`} detail={<p data-testid={`catalog-${kind}-error`} data-kind={p.kind}>{p.text}</p>} action={<button type="button" className="btn sm" onClick={load.reload}>Thử lại</button>}/>; })()
        : load.loading && !load.data ? <StateView kind="loading" compact/>
        : rows.length === 0 ? <p className="hint" data-testid={`catalog-${kind}-empty`}>{w.empty}</p>
        : <ul className="xp-typeList" data-testid={`catalog-${kind}-list`}>{rows.map((r) => (
          <li key={r.id} data-testid={`${kind}:${r.code}`} data-active={r.active}>
            <span className="xp-pickerCur"><b>{r.name}</b> <small>{r.code}{"rank" in r && r.rank !== null ? ` · bậc ${r.rank}` : ""}{r.active ? "" : " · đang tắt"}</small>{r.description ? <small>{r.description}</small> : null}</span>
            {manage ? <span className="row">
              <button type="button" className="btn sm xp-btnIcon" data-testid={`${kind}-edit:${r.code}`} disabled={busy} onClick={() => startEdit(r)}><Pencil size={14} aria-hidden="true"/> Sửa</button>
              <button type="button" className="btn sm xp-btnIcon" data-testid={`${kind}-toggle:${r.code}`} disabled={busy} onClick={() => void toggle(r)}><Power size={14} aria-hidden="true"/> {r.active ? "Tắt" : "Bật"}</button></span> : null}
          </li>))}</ul>}
      {flash ? <p className="notice" role="status" data-testid={`catalog-${kind}-flash`}>{flash}</p> : null}
      {manage ? (
        <form noValidate onSubmit={submit} aria-label={editing ? `Sửa ${w.one}` : `Thêm ${w.one}`} data-testid={`${kind}-form`}>
          <h4>{editing ? `Sửa ${w.one} “${editing.name}”` : `Thêm ${w.one}`}</h4>
          <label className="field"><span>Tên</span><input data-testid={`${kind}-name`} value={name} maxLength={120} autoComplete="off" aria-invalid={touched && !!errors.name} aria-describedby={touched && errors.name ? `${uid}-n` : undefined} onChange={(e) => setName(e.target.value)}/></label>
          {touched && errors.name ? <p className="formError" role="alert" id={`${uid}-n`}>{errors.name}</p> : null}
          <label className="field"><span>Mã</span><input data-testid={`${kind}-code`} value={code} autoComplete="off" spellCheck={false} disabled={!!editing} aria-invalid={touched && !!errors.code} aria-describedby={touched && errors.code ? `${uid}-c` : undefined} onChange={(e) => setCode(e.target.value)}/></label>
          {editing ? <small className="hint">Mã không đổi được sau khi tạo.</small> : null}
          {touched && errors.code ? <p className="formError" role="alert" id={`${uid}-c`}>{errors.code}</p> : null}
          {kind === "grade" ? <><label className="field"><span>Bậc (số, không bắt buộc)</span><input data-testid="grade-rank" inputMode="numeric" value={rank} autoComplete="off" aria-invalid={touched && !!errors.rank} aria-describedby={touched && errors.rank ? `${uid}-r` : undefined} onChange={(e) => setRank(e.target.value)}/></label>
            {touched && errors.rank ? <p className="formError" role="alert" id={`${uid}-r`}>{errors.rank}</p> : null}</> : null}
          <label className="field"><span>Mô tả (không bắt buộc)</span><input data-testid={`${kind}-description`} value={description} maxLength={500} autoComplete="off" onChange={(e) => setDescription(e.target.value)}/></label>
          {touched && errors.description ? <p className="formError" role="alert">{errors.description}</p> : null}
          {problem ? <Problem p={problem} onReload={load.reload} onRetry={again ? () => void act(again) : undefined} retrying={busy} reloadLabel={`Tải lại ${w.one}`} testid={`catalog-${kind}-problem`}/> : null}
          <div className="xp-footer">{editing ? <button type="button" className="btn" data-testid={`${kind}-cancel-edit`} onClick={reset}>Bỏ sửa</button> : null}
            <button className="btn primary" data-testid={`${kind}-submit`} disabled={busy}>{busy ? "Đang lưu…" : editing ? "Lưu" : `Thêm ${w.one}`}</button></div>
        </form>) : problem ? <Problem p={problem} onReload={load.reload} testid={`catalog-${kind}-problem`}/> : null}
    </section>
  );
}
