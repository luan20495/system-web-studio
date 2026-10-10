"use client";
/**
 * Organization structure screen: a dynamic tree of company-defined units (no fixed levels), a detail panel with the actions of the selected unit, and dialogs to add / edit / move / archive / restore a unit,
 * to manage unit types and to manage positions and grades.
 * Presentational: it talks only to an `OrganizationApi` (organization.ts, the one typed service layer: no URL is built here) and a `plan` (organizationModel.ts), so the same code runs on the real adapter and in
 * the browser harness (whose in-memory server sits BEHIND the same typed transport).
 * A refusal is shown as the server decided it (organizationModel.orgProblem): a busy structure (503) offers the same request again with the person's input kept, an unavailable store (501) says so and shows no data.
 * Move is a "Di chuyển tới…" dialog (reliable, keyboard friendly), not drag and drop. Archive and restore are confirmed in an in-app dialog (never a native one). There is no delete.
 */
import { useCallback, useEffect, useId, useMemo, useState, type FormEvent } from "react";
import { Archive, ArchiveRestore, ArrowRightLeft, Building2, ChevronsDownUp, ChevronsUpDown, FolderTree, Pencil, Plus, RefreshCw, Settings2, Briefcase, ModalHeader, ReasonButton, Picker, type PickerOption } from "@xweb/ui";
import { Modal } from "./Modal";
import { Card, StateView } from "../ui";
import { useLoad } from "../useLoad";
import type { OrganizationApi, OrgUnit, OrgUnitType } from "./organization";
import {
  archiveHint, buildTree, compactPath, depthAdvisory, directCountView, moveTargets, orgProblem, restoreHint, subtreeCountView, unitPath, validateUnitForm, type OrgProblem, type OrganizationPlan, type TreeNode,
} from "./organizationModel";
import { Problem, NotReadyPanel } from "./orgParts";
import { Tree, WINDOW_ABOVE, ROW_H } from "./OrganizationTree";
import { TypesDialog } from "./OrganizationTypes";
import { CatalogDialog } from "./OrganizationCatalog";
import { UnitIcon } from "./unitIcons";

export { NotReadyPanel, WINDOW_ABOVE, ROW_H };
export type Tenant = { id: string; name: string };
/** above this many units a tree starts collapsed to its roots */
export const LARGE_TREE = 300;

// ------------------------------------------------------------------------------------------------------------------------------- the screen
export function OrganizationView({ api, plan, tenant }: { api: OrganizationApi; plan: OrganizationPlan; tenant: Tenant }) {
  const access = plan.access.granted; const ready = plan.units.state === "ready";
  const [showArchived, setShowArchived] = useState(false);
  const data = useLoad(async () => (access && ready ? await Promise.all([api.listOrganizationUnits(tenant.id, { includeArchived: showArchived }), plan.types.state === "ready" ? api.listOrganizationUnitTypes(tenant.id) : Promise.resolve([] as OrgUnitType[])]).then(([units, types]) => ({ units, types })) : null), [tenant.id, access, ready, showArchived]);
  const units: OrgUnit[] = data.data?.units ?? []; const types: OrgUnitType[] = data.data?.types ?? [];
  const tree = useMemo(() => buildTree(units), [units]);
  const [selected, setSelected] = useState<string | null>(null); const [open, setOpen] = useState<Set<string>>(new Set()); const [seen, setSeen] = useState<Set<string>>(new Set());
  const [dialog, setDialog] = useState<null | { kind: "create"; parentId: string | null } | { kind: "edit" | "move" | "archive" | "restore"; id: string } | { kind: "types" } | { kind: "catalog" }>(null);
  const [flash, setFlash] = useState<string | null>(null);
  const sel = units.find((u) => u.id === selected) ?? null;
  const canEdit = plan.edit.state === "ready";

  // new units start expanded; the selection survives a reload while the unit exists
  // small trees start fully open; a large one (> LARGE_TREE units) starts with only the roots open, so the first paint is a few rows, not thousands
  useEffect(() => { const fresh = units.filter((u) => !seen.has(u.id)); if (!fresh.length) return; const roots = new Set(tree.map((n) => n.unit.id)); const openNow = units.length > LARGE_TREE ? fresh.filter((u) => roots.has(u.id)) : fresh; setSeen(new Set([...seen, ...fresh.map((u) => u.id)])); setOpen(new Set([...open, ...openNow.map((u) => u.id)])); }, [units]); // eslint-disable-line react-hooks/exhaustive-deps
  const onOpen = useCallback((id: string, v: boolean) => setOpen((cur) => { const n = new Set(cur); if (v) n.add(id); else n.delete(id); return n; }), []);
  const reload = useCallback(() => { data.reload(); }, [data]);
  // a selected unit that is gone from the list (archived while archived units are hidden, or removed by someone else) is no longer selected
  useEffect(() => { if (selected && data.data && !data.loading && !units.some((u) => u.id === selected)) setSelected(null); }, [units, selected, data.data, data.loading]);

  if (!access) return <StateView kind="forbidden" title="Bạn chưa có quyền xem cơ cấu tổ chức" detail={<p data-testid="org-forbidden">{(plan.access as { reason: string }).reason}</p>}/>;
  const catalogOpen = plan.positions.state !== "no-permission";
  return (
    <div className="xp-org" data-testid="org">
      <div className="xp-orgBar">
        <p className="hint" data-testid="org-tenant">Công ty: <b>{tenant.name}</b></p>
        <div className="xp-orgBarActions">
          {ready && units.length > 0 ? <><button className="btn xp-btnIcon" data-testid="org-expand-all" onClick={() => { const parents = new Set(units.map((u) => u.parentId)); setOpen(new Set(units.filter((u) => parents.has(u.id)).map((u) => u.id))); }}><ChevronsUpDown size={16} aria-hidden="true"/> Mở rộng tất cả</button>
          <button className="btn xp-btnIcon" data-testid="org-collapse-all" onClick={() => setOpen(new Set())}><ChevronsDownUp size={16} aria-hidden="true"/> Thu gọn</button></> : null}
          <button className="btn xp-btnIcon" data-testid="org-types" disabled={!ready} onClick={() => setDialog({ kind: "types" })}><Settings2 size={16} aria-hidden="true"/> Loại đơn vị</button>
          {catalogOpen ? <button className="btn xp-btnIcon" data-testid="org-catalog" onClick={() => setDialog({ kind: "catalog" })}><Briefcase size={16} aria-hidden="true"/> Vị trí &amp; cấp bậc</button> : null}
          <button className="btn xp-btnIcon" data-testid="org-reload" disabled={!ready} onClick={reload} aria-label="Tải lại cơ cấu"><RefreshCw size={16} aria-hidden="true"/></button>
          <ReasonButton className="btn primary xp-btnIcon" data-testid="org-add-root" unavailable={!ready || !canEdit} reason={ready && !canEdit ? "Cơ cấu đang ở chế độ chỉ xem." : undefined} onClick={() => setDialog({ kind: "create", parentId: null })}><Plus size={16} aria-hidden="true"/> Thêm đơn vị gốc</ReasonButton>
        </div>
      </div>
      <label className="xp-check xp-orgFilter"><input type="checkbox" data-testid="org-show-archived" checked={showArchived} disabled={!ready} onChange={(e) => setShowArchived(e.target.checked)}/> Hiện cả đơn vị đã lưu trữ</label>
      {flash ? <p className="notice" role="status" data-testid="org-flash">{flash}</p> : null}
      {ready && !canEdit ? <NotReadyPanel level={2} testid="org-edit-not-ready" title="Cơ cấu đang ở chế độ chỉ xem" reason={(plan.edit as { reason: string }).reason}><p className="hint">Bạn xem được cây, nhưng thêm, sửa, di chuyển, lưu trữ và khôi phục đơn vị chưa dùng được.</p></NotReadyPanel> : null}

      {!ready ? <NotReadyPanel level={2} testid="org-not-ready" title="Cơ cấu tổ chức chưa sẵn sàng" reason={(plan.units as { reason: string }).reason}/>
        : data.error ? <ErrorBlock error={data.error} retry={reload}/>
        : data.loading && !data.data ? <StateView kind="loading"/>
        : units.length === 0 ? (
          <div className="xp-orgEmpty" data-testid="org-empty"><span className="xp-orgEmptyIcon" aria-hidden="true"><FolderTree size={28}/></span>
            <h3>{showArchived ? "Chưa có đơn vị nào" : "Chưa có cơ cấu tổ chức"}</h3><p>Bắt đầu bằng đơn vị gốc (ví dụ: một Khối hoặc Chi nhánh), rồi thêm đơn vị con. Bạn tự đặt tên và loại cho từng cấp.</p>
            <button className="btn primary xp-btnIcon" data-testid="org-empty-add" disabled={!canEdit} onClick={() => setDialog({ kind: "create", parentId: null })}><Plus size={16} aria-hidden="true"/> Thêm đơn vị gốc</button>
            {!canEdit ? <p className="hint">{(plan.edit as { reason: string }).reason}</p> : null}</div>)
        : (
          <div className="xp-orgGrid">
            <Card title={`Cơ cấu (${units.length})`} className="xp-orgTreeCard">
              <Tree nodes={tree} types={types} selected={selected} open={open} onSelect={setSelected} onOpen={onOpen}/>
            </Card>
            <Card title="Chi tiết" className="xp-orgDetailCard">
              {sel ? <Detail api={api} tenantId={tenant.id} unit={sel} units={units} type={types.find((t) => t.id === sel.typeId)} canEdit={canEdit} onAction={(k) => k === "child" ? setDialog({ kind: "create", parentId: sel.id }) : setDialog({ kind: k, id: sel.id })}/>
                : <p className="hint" data-testid="org-pick-hint">Chọn một đơn vị trong cây để xem và thao tác.</p>}
            </Card>
          </div>)}

      {dialog?.kind === "create" ? <UnitDialog mode="create" tenantId={tenant.id} api={api} units={units} types={types} parent={units.find((u) => u.id === dialog.parentId) ?? null} onClose={() => setDialog(null)}
        onDone={(u) => { setFlash("Đã thêm đơn vị."); if (u?.parentId) setOpen((s) => new Set([...s, u.parentId!])); if (u) setSelected(u.id); setDialog(null); reload(); }} onReload={() => { setDialog(null); reload(); }}/> : null}
      {dialog?.kind === "edit" && sel ? <UnitDialog mode="edit" tenantId={tenant.id} api={api} units={units} types={types} unit={sel} parent={units.find((u) => u.id === sel.parentId) ?? null} onClose={() => setDialog(null)}
        onDone={() => { setFlash("Đã lưu đơn vị."); setDialog(null); reload(); }} onReload={() => { setDialog(null); reload(); }}/> : null}
      {dialog?.kind === "move" && sel ? <MoveDialog tenantId={tenant.id} api={api} units={units} tree={tree} types={types} unit={sel} onClose={() => setDialog(null)} onDone={() => { setFlash("Đã di chuyển đơn vị."); setDialog(null); reload(); }} onReload={() => { setDialog(null); reload(); }}/> : null}
      {dialog?.kind === "archive" && sel ? <UnitActionDialog kind="archive" tenantId={tenant.id} api={api} units={units} unit={sel} onClose={() => setDialog(null)} onDone={() => { setFlash("Đã lưu trữ đơn vị."); setDialog(null); reload(); }} onReload={() => { setDialog(null); reload(); }}/> : null}
      {dialog?.kind === "restore" && sel ? <UnitActionDialog kind="restore" tenantId={tenant.id} api={api} units={units} unit={sel} onClose={() => setDialog(null)} onDone={() => { setFlash("Đã khôi phục đơn vị."); setDialog(null); reload(); }} onReload={() => { setDialog(null); reload(); }}/> : null}
      {dialog?.kind === "types" ? <TypesDialog tenantId={tenant.id} api={api} plan={plan} types={types} onClose={() => setDialog(null)} onChanged={reload}/> : null}
      {dialog?.kind === "catalog" ? <CatalogDialog tenantId={tenant.id} api={api} plan={plan} onClose={() => setDialog(null)}/> : null}
    </div>
  );
}

/** the whole list failed to load: said once, from the server's own code. 501 = the feature is not switched on (no data, no fallback); the rest follow orgProblem. */
function ErrorBlock({ error, retry }: { error: unknown; retry: () => void }) {
  const p = orgProblem(error);
  const title = p.kind === "forbidden" ? "Không có quyền" : p.kind === "unavailable-feature" ? "Cơ cấu tổ chức chưa khả dụng" : p.kind === "busy" ? "Hệ thống đang bận" : "Không tải được cơ cấu";
  return <StateView kind={p.kind === "forbidden" ? "forbidden" : p.kind === "unavailable-feature" ? "empty" : "error"} title={title} detail={<p data-testid="org-error" data-kind={p.kind} data-code={p.code}>{p.text}</p>} action={<button className="btn" data-testid="org-retry" onClick={retry}>{p.kind === "unavailable-feature" ? "Kiểm tra lại" : "Thử lại"}</button>}/>;
}

// ------------------------------------------------------------------------------------------------------------------------------- detail
function Detail({ api, tenantId, unit, units, type, canEdit, onAction }: { api: OrganizationApi; tenantId: string; unit: OrgUnit; units: OrgUnit[]; type: OrgUnitType | undefined; canEdit: boolean; onAction: (k: "child" | "edit" | "move" | "archive" | "restore") => void }) {
  // the unit's own detail (the server's breadcrumb and fresh counts); the tree row already carries the counts, so a failed detail only costs the breadcrumb
  const detail = useLoad(async () => api.getOrganizationUnit(tenantId, unit.id), [tenantId, unit.id, unit.version]);
  const path = detail.data?.path.length ? detail.data.path.map((p) => p.name).join(" › ") : unitPath(units, unit.id);
  const shown = detail.data?.unit ?? unit; const direct = directCountView(shown); const subtree = subtreeCountView(shown);
  const archived = !unit.active; const aHint = archiveHint(unit); const rHint = restoreHint(unit, units); const problem: OrgProblem | null = detail.error ? orgProblem(detail.error) : null;
  return (
    <div className="stack" data-testid="org-detail">
      <div className="xp-detailHead"><span className="xp-headIcon" aria-hidden="true"><UnitIcon id={type?.icon} size={22}/></span>
        <div style={{ minWidth: 0 }}><h3 data-testid="detail-name">{unit.name}</h3><small className="hint" data-testid="detail-path" title={path} aria-label={path}>{compactPath(path)}</small></div></div>
      <dl className="kv">
        <div><dt>Loại</dt><dd data-testid="detail-type">{type ? type.name : "Loại không còn tồn tại"}</dd></div>
        <div><dt>Mã</dt><dd data-testid="detail-code">{unit.code}</dd></div>
        <div><dt>Trạng thái</dt><dd>{archived ? <span className="pill pill-muted" data-testid="detail-status">Đã lưu trữ</span> : <span className="pill pill-ok" data-testid="detail-status">Hoạt động</span>}</dd></div>
        <div><dt title={direct.title}>{direct.label}</dt><dd data-testid="detail-direct-count" data-known={direct.known}>{direct.value}<small className="hint"> — số người thuộc đúng đơn vị này</small></dd></div>
        <div><dt title={subtree.title}>{subtree.label}</dt><dd data-testid="detail-subtree-count" data-known={subtree.known}>{subtree.value}<small className="hint"> — không trùng người, gồm đơn vị con</small></dd></div>
        <div><dt>Đơn vị con</dt><dd data-testid="detail-children">{unit.childCount}</dd></div>
      </dl>
      <div className="xp-orgActions">
        {archived ? <button className="btn xp-btnIcon" data-testid="org-restore" disabled={!canEdit} onClick={() => onAction("restore")}><ArchiveRestore size={16} aria-hidden="true"/> Khôi phục</button> : <>
          <button className="btn xp-btnIcon" data-testid="org-add-child" disabled={!canEdit} onClick={() => onAction("child")}><Plus size={16} aria-hidden="true"/> Thêm đơn vị con</button>
          <button className="btn xp-btnIcon" data-testid="org-edit" disabled={!canEdit} onClick={() => onAction("edit")}><Pencil size={16} aria-hidden="true"/> Sửa</button>
          <button className="btn xp-btnIcon" data-testid="org-move" disabled={!canEdit} onClick={() => onAction("move")}><ArrowRightLeft size={16} aria-hidden="true"/> Di chuyển tới…</button>
          <button className="btn danger xp-btnIcon" data-testid="org-archive" disabled={!canEdit} aria-describedby={aHint ? "org-archive-hint" : undefined} onClick={() => onAction("archive")}><Archive size={16} aria-hidden="true"/> Lưu trữ</button></>}
      </div>
      {aHint ? <p className="hint" id="org-archive-hint" data-testid="org-archive-hint">{aHint}</p> : null}
      {archived && rHint ? <p className="hint" data-testid="org-restore-hint">{rHint}</p> : null}
      {archived ? <p className="hint">Đơn vị đã lưu trữ không được tính vào số liệu và không nhận thành viên mới. Không có thao tác xóa vĩnh viễn.</p> : null}
      {problem ? <Problem p={problem} onReload={detail.reload} reloadLabel="Tải lại chi tiết"/> : null}
    </div>
  );
}

// ------------------------------------------------------------------------------------------------------------------------------- dialogs
function typeOptions(types: OrgUnitType[]): PickerOption<string>[] {
  return [{ value: "", label: "Chọn loại đơn vị", hint: "Bắt buộc", icon: <span className="xp-nodeIcon"><UnitIcon id={null}/></span> }, ...types.filter((t) => t.active).map((t) => ({ value: t.id, label: t.name, hint: t.code, icon: <span className="xp-nodeIcon"><UnitIcon id={t.icon}/></span> }))];
}
function UnitDialog({ mode, tenantId, api, units, types, unit, parent, onClose, onDone, onReload }: { mode: "create" | "edit"; tenantId: string; api: OrganizationApi; units: OrgUnit[]; types: OrgUnitType[]; unit?: OrgUnit; parent: OrgUnit | null; onClose: () => void; onDone: (u?: OrgUnit) => void; onReload: () => void }) {
  const uid = useId(); const [name, setName] = useState(unit?.name ?? ""); const [code, setCode] = useState(unit?.code ?? ""); const [typeId, setTypeId] = useState(unit?.typeId ?? "");
  const [touched, setTouched] = useState(false); const [busy, setBusy] = useState(false); const [problem, setProblem] = useState<OrgProblem | null>(null);
  const errors = validateUnitForm({ name, code, typeId: typeId || null }, { types, parent, editing: mode === "edit" });
  const advisory = mode === "create" ? depthAdvisory(types.find((t) => t.id === typeId), parent, units) : null;
  const title = mode === "create" ? (parent ? `Thêm đơn vị con của ${parent.name}` : "Thêm đơn vị gốc") : "Sửa đơn vị";
  const typeName = types.find((t) => t.id === typeId)?.name;
  /** the same request again: the dialog stays mounted, so name / code / type are exactly what the person typed (and the unit's expectedVersion is the one they looked at) */
  async function run() {
    setBusy(true); setProblem(null);
    try {
      if (mode === "create") onDone(await api.createOrganizationUnit(tenantId, { parentId: parent?.id ?? null, typeId, name, code }));
      else { await api.updateOrganizationUnit(tenantId, unit!.id, unit!.version, { name, code }); onDone(); }
    } catch (err) { setProblem(orgProblem(err)); } finally { setBusy(false); }
  }
  function submit(e: FormEvent) { e.preventDefault(); setTouched(true); if (Object.keys(errors).length) return; void run(); }
  return (
    <Modal label={title} onClose={onClose}>
      <form className="modalBody" noValidate onSubmit={submit} data-testid="unit-dialog">
        <ModalHeader icon={<Building2 size={22}/>} title={title} subtitle={parent ? `Nằm trong: ${unitPath(units, parent.id)}` : mode === "edit" && unit?.parentId ? `Nằm trong: ${unitPath(units, unit.parentId)}` : "Đơn vị gốc không thuộc đơn vị nào."}/>
        <section className="xp-section" aria-label="Thông tin đơn vị">
          <label className="field"><span>Tên đơn vị</span><input data-testid="unit-name" value={name} maxLength={160} autoComplete="off" placeholder="Ví dụ: Khối Công nghệ" aria-invalid={touched && !!errors.name} aria-describedby={touched && errors.name ? `${uid}-name-err` : undefined} onChange={(e) => setName(e.target.value)}/></label>
          {touched && errors.name ? <p className="formError" role="alert" id={`${uid}-name-err`}>{errors.name}</p> : null}
          <label className="field"><span>Mã đơn vị</span><input data-testid="unit-code" value={code} autoComplete="off" spellCheck={false} placeholder="TECH" aria-invalid={touched && !!errors.code} aria-describedby={`${uid}-code-hint${touched && errors.code ? ` ${uid}-code-err` : ""}`} onChange={(e) => setCode(e.target.value)}/></label>
          <small className="hint" id={`${uid}-code-hint`}>Duy nhất trong cùng cấp; máy chủ lưu mã ở dạng chữ hoa.</small>
          {touched && errors.code ? <p className="formError" role="alert" id={`${uid}-code-err`}>{errors.code}</p> : null}
          {mode === "create"
            ? <Picker label="Loại đơn vị" value={typeId} options={typeOptions(types)} onChange={setTypeId} describedBy={touched && errors.type ? `${uid}-type-err` : undefined}/>
            : <div className="field"><span>Loại đơn vị</span><p data-testid="unit-type-fixed">{typeName ?? "—"} <small className="hint">(không đổi được sau khi tạo)</small></p></div>}
          {mode === "create" && types.filter((t) => t.active).length === 0 ? <p className="hint">Chưa có loại đơn vị nào đang bật. Tạo loại (Khối, Chi nhánh, Phòng…) ở nút “Loại đơn vị” trước.</p> : null}
          {touched && errors.type ? <p className="formError" role="alert" id={`${uid}-type-err`} data-testid="unit-type-error">{errors.type}</p> : null}
          {advisory ? <p className="hint" role="note" data-testid="unit-depth-advisory">{advisory}</p> : null}
        </section>
        {problem ? <Problem p={problem} onReload={onReload} onRetry={() => void run()} retrying={busy}/> : null}
        <div className="xp-footer"><button type="button" className="btn" onClick={onClose}>Hủy</button><button className="btn primary" data-testid="unit-submit" disabled={busy}>{busy ? "Đang lưu…" : mode === "create" ? "Thêm đơn vị" : "Lưu"}</button></div>
      </form>
    </Modal>
  );
}

function MoveDialog({ tenantId, api, units, tree, types, unit, onClose, onDone, onReload }: { tenantId: string; api: OrganizationApi; units: OrgUnit[]; tree: TreeNode[]; types: OrgUnitType[]; unit: OrgUnit; onClose: () => void; onDone: () => void; onReload: () => void }) {
  const targets = useMemo(() => moveTargets(units, types, unit.id, tree), [units, types, unit.id, tree]);
  const [to, setTo] = useState<string | null | undefined>(undefined); const [busy, setBusy] = useState(false); const [problem, setProblem] = useState<OrgProblem | null>(null);
  const picked = to === undefined ? undefined : targets.find((t) => t.id === to);
  async function run() {
    if (to === undefined) return;
    setBusy(true); setProblem(null);
    try { await api.moveOrganizationUnit(tenantId, unit.id, unit.version, to); onDone(); } catch (err) { setProblem(orgProblem(err)); } finally { setBusy(false); }
  }
  return (
    <Modal label={`Di chuyển ${unit.name}`} onClose={onClose}>
      <form className="modalBody" onSubmit={(e) => { e.preventDefault(); void run(); }} data-testid="move-dialog">
        <ModalHeader icon={<ArrowRightLeft size={22}/>} title={`Di chuyển “${unit.name}”`} subtitle="Chọn đơn vị cha mới. Toàn bộ đơn vị con đi theo."/>
        <fieldset className="xp-moveList" aria-label="Đơn vị cha mới">
          {targets.map((t) => (
            <label key={t.id ?? "root"} className={`xp-moveRow${t.disabled ? " disabled" : ""}${to === t.id ? " sel" : ""}`} style={{ paddingLeft: 10 + t.depth * 18 }} data-testid={`move-to:${t.id ?? "root"}`}>
              <input type="radio" name="move-to" disabled={t.disabled} checked={to === t.id} onChange={() => setTo(t.id)}/>
              <span className="xp-pickerCur"><b>{t.label}</b>{t.reason ? <small>{t.reason}</small> : null}</span>
            </label>))}
        </fieldset>
        {picked?.note ? <p className="hint" role="note" data-testid="move-advisory">{picked.note}</p> : null}
        {problem ? <Problem p={problem} onReload={onReload} onRetry={() => void run()} retrying={busy}/> : null}
        <div className="xp-footer"><button type="button" className="btn" onClick={onClose}>Hủy</button><button className="btn primary" data-testid="move-submit" disabled={busy || to === undefined}>{busy ? "Đang chuyển…" : "Di chuyển"}</button></div>
      </form>
    </Modal>
  );
}

/**
 * Archive / restore confirmation (an in-app dialog; there is no hard delete). The hint is advisory: the server decides (ORG_UNIT_HAS_CHILDREN / ORG_UNIT_HAS_MEMBERS / RESTORE_CONFLICT and its reasons).
 * The confirmation stays open on a refusal (so the reason and "Thử lại" are right there) and closes only on a real success.
 */
function UnitActionDialog({ kind, tenantId, api, units, unit, onClose, onDone, onReload }: { kind: "archive" | "restore"; tenantId: string; api: OrganizationApi; units: OrgUnit[]; unit: OrgUnit; onClose: () => void; onDone: () => void; onReload: () => void }) {
  const [busy, setBusy] = useState(false); const [problem, setProblem] = useState<OrgProblem | null>(null);
  const archive = kind === "archive"; const hint = archive ? archiveHint(unit) : restoreHint(unit, units);
  async function run() {
    setBusy(true); setProblem(null);
    try { await (archive ? api.archiveOrganizationUnit(tenantId, unit.id, unit.version) : api.restoreOrganizationUnit(tenantId, unit.id, unit.version)); onDone(); } catch (err) { setProblem(orgProblem(err)); } finally { setBusy(false); }
  }
  const title = archive ? `Lưu trữ “${unit.name}”?` : `Khôi phục “${unit.name}”?`;
  return (
    <Modal label={archive ? `Lưu trữ ${unit.name}` : `Khôi phục ${unit.name}`} onClose={onClose}>
      <div className="modalBody" data-testid={`${kind}-dialog`}>
        <ModalHeader icon={archive ? <Archive size={22}/> : <ArchiveRestore size={22}/>} title={title}
          subtitle={archive ? "Đơn vị ra khỏi cơ cấu đang dùng nhưng không bị xóa: bạn khôi phục lại được. Chỉ lưu trữ được đơn vị không còn đơn vị con và thành viên." : "Đơn vị quay lại cơ cấu đang dùng, ở vị trí cũ, nếu đơn vị cha và loại của nó còn dùng được."}/>
        {hint ? <p className="hint" data-testid={`${kind}-hint`}>{hint}</p> : null}
        {problem ? <Problem p={problem} onReload={onReload} onRetry={() => void run()} retrying={busy} testid={`${kind}-problem`}/> : null}
        <div className="xp-footer"><button type="button" className="btn" data-autofocus="" onClick={onClose}>Hủy</button>
          <button type="button" className={`btn ${archive ? "danger" : "primary"}`} data-testid={`${kind}-confirm`} disabled={busy} onClick={() => void run()}>{busy ? (archive ? "Đang lưu trữ…" : "Đang khôi phục…") : archive ? "Lưu trữ đơn vị" : "Khôi phục đơn vị"}</button></div>
      </div>
    </Modal>
  );
}
