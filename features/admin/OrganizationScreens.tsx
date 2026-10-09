"use client";
/**
 * Organization structure screen: a dynamic tree of company-defined units (no fixed levels), a detail panel with the actions of the selected unit, and dialogs to add / edit / move / delete a unit and to manage unit types.
 * Presentational: it talks only to an `OrganizationApi` (organization.ts) and a `plan` (organizationModel.ts), so the same code runs on the real adapter (NOT_READY until C1's contract) and in the browser harness.
 * Move is a "Di chuyển tới…" dialog (reliable, keyboard friendly), not drag and drop. The UI avoids obvious cycles; the server is the authority (ORG_CYCLE).
 */
import { memo, useCallback, useEffect, useId, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { ArrowRightLeft, Building2, ChevronRight, ChevronsDownUp, ChevronsUpDown, FolderTree, Info, Pencil, Plus, Power, RefreshCw, Settings2, Trash2, ModalHeader, Picker, type PickerOption } from "@xweb/ui";
import { Modal } from "./Modal";
import { Card, StateView } from "../ui";
import { useLoad } from "../useLoad";
import type { NewOrgUnitType, OrganizationApi, OrgUnit, OrgUnitType } from "./organization";
import {
  UNIT_ICONS, buildTree, compactPath, deleteBlock, flattenTree, moveTargets, orgProblem, safeIcon, unitPath, validateTypeForm, validateUnitForm, type OrgProblem, type OrganizationPlan, type TreeNode,
} from "./organizationModel";
import { UnitIcon } from "./unitIcons";

export type Tenant = { id: string; name: string };
/** above this many units a tree starts collapsed to its roots */
export const LARGE_TREE = 300;

// ------------------------------------------------------------------------------------------------------------------------------- shared bits
export function NotReadyPanel({ title, reason, testid, children, level = 3 }: { title: string; reason: string; testid: string; children?: React.ReactNode; level?: 2 | 3 }) {
  const H = level === 2 ? "h2" : "h3"; // 2 when the panel sits directly under the page's h1 (no skipped heading level)
  return (
    <div className="xp-orgNotReady" role="note" data-testid={testid}>
      <span className="xp-orgNotReadyIcon" aria-hidden="true"><Info size={20}/></span>
      <div><H>{title}</H><p>{reason}</p>{children}</div>
    </div>
  );
}
function Problem({ p, onReload }: { p: OrgProblem; onReload?: () => void }) {
  return (
    <div className={p.kind === "not-ready" ? "notice" : "formError"} role="alert" data-testid="org-problem" data-kind={p.kind}>
      {p.text}{onReload && (p.kind === "version" || p.kind === "notfound" || p.kind === "conflict") ? <> <button type="button" className="btn sm" onClick={onReload}>Tải lại cơ cấu</button></> : null}
    </div>
  );
}
const count = (n: number | undefined, one: string) => (n === undefined ? null : `${n} ${one}`);

// ------------------------------------------------------------------------------------------------------------------------------- the screen
export function OrganizationView({ api, plan, tenant }: { api: OrganizationApi; plan: OrganizationPlan; tenant: Tenant }) {
  const access = plan.access.granted; const ready = plan.units.state === "ready";
  const data = useLoad(async () => (access && ready ? { units: await api.listOrganizationUnits(tenant.id), types: plan.types.state === "ready" ? await api.listOrganizationUnitTypes(tenant.id) : [] } : null), [tenant.id, access, ready]);
  const units: OrgUnit[] = data.data?.units ?? []; const types: OrgUnitType[] = data.data?.types ?? [];
  const tree = useMemo(() => buildTree(units), [units]);
  const [selected, setSelected] = useState<string | null>(null); const [open, setOpen] = useState<Set<string>>(new Set()); const [seen, setSeen] = useState<Set<string>>(new Set());
  const [dialog, setDialog] = useState<null | { kind: "create"; parentId: string | null } | { kind: "edit" | "move" | "delete"; id: string } | { kind: "types" }>(null);
  const [flash, setFlash] = useState<string | null>(null); const [busyToggle, setBusyToggle] = useState(false); const [toggleProblem, setToggleProblem] = useState<OrgProblem | null>(null);
  const sel = units.find((u) => u.id === selected) ?? null;
  const canEdit = plan.edit.state === "ready";

  // new units start expanded; the selection survives a reload while the unit exists
  // small trees start fully open; a large one (> LARGE_TREE units) starts with only the roots open, so the first paint is a few rows, not thousands
  useEffect(() => { const fresh = units.filter((u) => !seen.has(u.id)); if (!fresh.length) return; const roots = new Set(tree.map((n) => n.unit.id)); const openNow = units.length > LARGE_TREE ? fresh.filter((u) => roots.has(u.id)) : fresh; setSeen(new Set([...seen, ...fresh.map((u) => u.id)])); setOpen(new Set([...open, ...openNow.map((u) => u.id)])); }, [units]); // eslint-disable-line react-hooks/exhaustive-deps
  const typeOf = (id: string | null | undefined) => types.find((t) => t.id === id);
  const onOpen = useCallback((id: string, v: boolean) => setOpen((cur) => { const n = new Set(cur); if (v) n.add(id); else n.delete(id); return n; }), []);
  const reload = useCallback(() => { data.reload(); }, [data]);
  async function toggle(u: OrgUnit) {
    setBusyToggle(true); setToggleProblem(null);
    try { await api.updateOrganizationUnit(tenant.id, u.id, u.version, { enabled: !u.enabled }); setFlash(u.enabled ? "Đã tắt đơn vị." : "Đã bật đơn vị."); reload(); } catch (e) { setToggleProblem(orgProblem(e)); } finally { setBusyToggle(false); }
  }

  if (!access) return <StateView kind="forbidden" title="Bạn chưa quản trị công ty nào" detail={<p data-testid="org-forbidden">{(plan.access as { reason: string }).reason}</p>}/>;
  return (
    <div className="xp-org" data-testid="org">
      <div className="xp-orgBar">
        <p className="hint" data-testid="org-tenant">Công ty: <b>{tenant.name}</b></p>
        <div className="xp-orgBarActions">
          {ready && units.length > 0 ? <><button className="btn xp-btnIcon" data-testid="org-expand-all" onClick={() => { const parents = new Set(units.map((u) => u.parentId)); setOpen(new Set(units.filter((u) => parents.has(u.id)).map((u) => u.id))); }}><ChevronsUpDown size={16} aria-hidden="true"/> Mở rộng tất cả</button>
          <button className="btn xp-btnIcon" data-testid="org-collapse-all" onClick={() => setOpen(new Set())}><ChevronsDownUp size={16} aria-hidden="true"/> Thu gọn</button></> : null}
          <button className="btn xp-btnIcon" data-testid="org-types" disabled={!ready} onClick={() => setDialog({ kind: "types" })}><Settings2 size={16} aria-hidden="true"/> Loại đơn vị</button>
          <button className="btn xp-btnIcon" data-testid="org-reload" disabled={!ready} onClick={reload} aria-label="Tải lại cơ cấu"><RefreshCw size={16} aria-hidden="true"/></button>
          <button className="btn primary xp-btnIcon" data-testid="org-add-root" disabled={!ready || !canEdit} title={!canEdit ? "Chưa sẵn sàng" : undefined} onClick={() => setDialog({ kind: "create", parentId: null })}><Plus size={16} aria-hidden="true"/> Thêm đơn vị gốc</button>
        </div>
      </div>
      {flash ? <p className="notice" role="status" data-testid="org-flash">{flash}</p> : null}
      {ready && !canEdit ? <NotReadyPanel level={2} testid="org-edit-not-ready" title="Cơ cấu đang ở chế độ chỉ xem" reason={(plan.edit as { reason: string }).reason}><p className="hint">Bạn xem được cây, nhưng thêm, sửa, di chuyển, bật/tắt và xóa đơn vị chưa dùng được.</p></NotReadyPanel> : null}

      {!ready ? <NotReadyPanel level={2} testid="org-not-ready" title="Cơ cấu tổ chức chưa sẵn sàng" reason={(plan.units as { reason: string }).reason}>
        <p className="hint">Giao diện đã sẵn sàng: cây đơn vị tùy biến (không cố định Phòng/Team), loại đơn vị, thêm / sửa / di chuyển / bật tắt / xóa. Màn hình sẽ hoạt động khi máy chủ công bố API; không có dữ liệu nào được tạo giả.</p></NotReadyPanel>
        : data.error ? <ErrorBlock error={data.error} retry={reload}/>
        : data.loading && !data.data ? <StateView kind="loading"/>
        : units.length === 0 ? (
          <div className="xp-orgEmpty" data-testid="org-empty"><span className="xp-orgEmptyIcon" aria-hidden="true"><FolderTree size={28}/></span>
            <h3>Chưa có cơ cấu tổ chức</h3><p>Bắt đầu bằng đơn vị gốc (ví dụ: một Khối hoặc Chi nhánh), rồi thêm đơn vị con. Bạn tự đặt tên và loại cho từng cấp.</p>
            <button className="btn primary xp-btnIcon" data-testid="org-empty-add" disabled={!canEdit} onClick={() => setDialog({ kind: "create", parentId: null })}><Plus size={16} aria-hidden="true"/> Thêm đơn vị gốc</button>
            {!canEdit ? <p className="hint">{(plan.edit as { reason: string }).reason}</p> : null}</div>)
        : (
          <div className="xp-orgGrid">
            <Card title={`Cơ cấu (${units.length})`} className="xp-orgTreeCard">
              <Tree nodes={tree} types={types} selected={selected} open={open} onSelect={setSelected} onOpen={onOpen}/>
            </Card>
            <Card title="Chi tiết" className="xp-orgDetailCard">
              {sel ? <Detail unit={sel} units={units} type={typeOf(sel.typeId)} canEdit={canEdit} busy={busyToggle} problem={toggleProblem} onAction={(k) => k === "child" ? setDialog({ kind: "create", parentId: sel.id }) : k === "toggle" ? void toggle(sel) : setDialog({ kind: k, id: sel.id })}/>
                : <p className="hint" data-testid="org-pick-hint">Chọn một đơn vị trong cây để xem và thao tác.</p>}
            </Card>
          </div>)}

      {dialog?.kind === "create" ? <UnitDialog mode="create" tenantId={tenant.id} api={api} units={units} types={types} parent={units.find((u) => u.id === dialog.parentId) ?? null} onClose={() => setDialog(null)}
        onDone={(u) => { setFlash("Đã thêm đơn vị."); if (u?.parentId) setOpen((s) => new Set([...s, u.parentId!])); if (u) setSelected(u.id); setDialog(null); reload(); }} onReload={reload}/> : null}
      {dialog?.kind === "edit" && sel ? <UnitDialog mode="edit" tenantId={tenant.id} api={api} units={units} types={types} unit={sel} parent={units.find((u) => u.id === sel.parentId) ?? null} onClose={() => setDialog(null)}
        onDone={() => { setFlash("Đã lưu đơn vị."); setDialog(null); reload(); }} onReload={() => { setDialog(null); reload(); }}/> : null}
      {dialog?.kind === "move" && sel ? <MoveDialog tenantId={tenant.id} api={api} units={units} types={types} unit={sel} onClose={() => setDialog(null)} onDone={() => { setFlash("Đã di chuyển đơn vị."); setDialog(null); reload(); }} onReload={() => { setDialog(null); reload(); }}/> : null}
      {dialog?.kind === "delete" && sel ? <DeleteDialog tenantId={tenant.id} api={api} units={units} unit={sel} onClose={() => setDialog(null)} onDone={() => { setFlash("Đã xóa đơn vị."); setSelected(null); setDialog(null); reload(); }} onReload={() => { setDialog(null); reload(); }}/> : null}
      {dialog?.kind === "types" ? <TypesDialog tenantId={tenant.id} api={api} plan={plan} types={types} onClose={() => setDialog(null)} onChanged={reload}/> : null}
    </div>
  );
}

function ErrorBlock({ error, retry }: { error: unknown; retry: () => void }) {
  const p = orgProblem(error);
  return <StateView kind={p.kind === "forbidden" ? "forbidden" : "error"} title={p.kind === "forbidden" ? "Không có quyền" : "Không tải được cơ cấu"} detail={<p data-testid="org-error" data-kind={p.kind}>{p.text}</p>} action={<button className="btn" data-testid="org-retry" onClick={retry}>Thử lại</button>}/>;
}

// ------------------------------------------------------------------------------------------------------------------------------- tree
const INDENT_PX = 18; const MAX_INDENT_LEVELS = 10;
/** One row. `memo`: a row re-renders only when ITS props change (selected / open / tabbable / its unit), so selecting a node in a 2 000-node tree re-renders two rows, not 2 000. */
const TreeRow = memo(function TreeRow({ n, type, selected, open, tabbable, onSelect, onToggle, onFocusRow }: { n: TreeNode; type: OrgUnitType | undefined; selected: boolean; open: boolean; tabbable: boolean; onSelect: (id: string) => void; onToggle: (id: string, v: boolean) => void; onFocusRow: (id: string) => void }) {
  const u = n.unit; const has = n.children.length > 0;
  const counts = [count(u.employeeCount, "nhân viên"), has ? `${n.children.length} đơn vị con` : null].filter(Boolean).join(" · ");
  return (
    <li role="treeitem" aria-level={n.depth + 1} aria-setsize={n.size} aria-posinset={n.pos} aria-expanded={has ? open : undefined} aria-selected={selected}>
      <div className={`xp-node${selected ? " sel" : ""}${u.enabled ? "" : " off"}`} data-node={u.id} data-testid={`node:${u.id}`} tabIndex={tabbable ? 0 : -1} onClick={() => { onSelect(u.id); onFocusRow(u.id); }} onFocus={() => onFocusRow(u.id)} style={{ paddingLeft: 8 + Math.min(n.depth, MAX_INDENT_LEVELS) * INDENT_PX }}>
        <span className={`xp-chev${has ? "" : " leaf"}`} aria-hidden="true" onClick={(e) => { e.stopPropagation(); if (has) onToggle(u.id, !open); }}><ChevronRight size={16} style={{ transform: open ? "rotate(90deg)" : undefined }}/></span>
        <span className="xp-nodeIcon"><UnitIcon id={type?.icon}/></span>
        <span className="xp-nodeText"><b title={u.name}>{u.name}</b>{counts ? <small>{counts}</small> : null}</span>
        {n.depth > MAX_INDENT_LEVELS ? <span className="xp-depthTag" title={`Cấp ${n.depth + 1}`}>C{n.depth + 1}</span> : null}
        {type ? <span className="xp-typeBadge">{type.name}</span> : null}
        {!u.enabled ? <span className="pill pill-muted">Đã tắt</span> : null}
        {n.orphan ? <span className="pill pill-warn" title="Không tìm thấy đơn vị cha trong dữ liệu">Mồ côi</span> : null}
      </div>
    </li>);
});

/**
 * A flat WAI-ARIA tree (role=tree, treeitems with aria-level / aria-setsize / aria-posinset): only the VISIBLE rows exist in the DOM (a collapsed node renders none of its children), the rows are memoised,
 * and indentation is capped so a very deep chain does not push the text out of the card (a "C<n>" tag shows the real level). Keyboard: ↑ ↓ → ← Home End Enter.
 */
function Tree({ nodes, types, selected, open, onSelect, onOpen }: { nodes: TreeNode[]; types: OrgUnitType[]; selected: string | null; open: Set<string>; onSelect: (id: string) => void; onOpen: (id: string, v: boolean) => void }) {
  const visible = useMemo(() => flattenTree(nodes, open), [nodes, open]);
  const typeById = useMemo(() => new Map(types.map((t) => [t.id, t])), [types]);
  const [focus, setFocus] = useState<string | null>(null); const root = useRef<HTMLUListElement>(null);
  const cur = focus && visible.some((n) => n.unit.id === focus) ? focus : selected && visible.some((n) => n.unit.id === selected) ? selected : visible[0]?.unit.id ?? null;
  const go = useCallback((id: string | undefined) => { if (!id) return; setFocus(id); requestAnimationFrame(() => root.current?.querySelector<HTMLElement>(`[data-node="${CSS.escape(id)}"]`)?.focus()); }, []);
  const onFocusRow = useCallback((id: string) => setFocus(id), []);
  function onKey(e: KeyboardEvent) {
    const i = visible.findIndex((n) => n.unit.id === cur); const n = visible[i]; if (!n) return;
    const has = n.children.length > 0; const isOpen = open.has(n.unit.id);
    if (e.key === "ArrowDown") { e.preventDefault(); go(visible[Math.min(visible.length - 1, i + 1)]?.unit.id); }
    else if (e.key === "ArrowUp") { e.preventDefault(); go(visible[Math.max(0, i - 1)]?.unit.id); }
    else if (e.key === "Home") { e.preventDefault(); go(visible[0]?.unit.id); } else if (e.key === "End") { e.preventDefault(); go(visible[visible.length - 1]?.unit.id); }
    else if (e.key === "ArrowRight") { e.preventDefault(); if (has && !isOpen) onOpen(n.unit.id, true); else if (has) go(n.children[0].unit.id); }
    else if (e.key === "ArrowLeft") { e.preventDefault(); if (has && isOpen) onOpen(n.unit.id, false); else { let j = i - 1; while (j >= 0 && visible[j].depth >= n.depth) j--; go(visible[j]?.unit.id); } }
    else if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onSelect(n.unit.id); }
  }
  return (
    <ul className="xp-tree" role="tree" aria-label="Cơ cấu tổ chức" data-testid="org-tree" ref={root} onKeyDown={onKey}>
      {visible.map((n) => <TreeRow key={n.unit.id} n={n} type={n.unit.typeId ? typeById.get(n.unit.typeId) : undefined} selected={selected === n.unit.id} open={open.has(n.unit.id)} tabbable={cur === n.unit.id} onSelect={onSelect} onToggle={onOpen} onFocusRow={onFocusRow}/>)}
    </ul>
  );
}

// ------------------------------------------------------------------------------------------------------------------------------- detail
function Detail({ unit, units, type, canEdit, busy, problem, onAction }: { unit: OrgUnit; units: OrgUnit[]; type: OrgUnitType | undefined; canEdit: boolean; busy: boolean; problem: OrgProblem | null; onAction: (k: "child" | "edit" | "move" | "toggle" | "delete") => void }) {
  const block = deleteBlock(unit, units); const path = unitPath(units, unit.id);
  return (
    <div className="stack" data-testid="org-detail">
      <div className="xp-detailHead"><span className="xp-headIcon" aria-hidden="true"><UnitIcon id={type?.icon} size={22}/></span>
        <div style={{ minWidth: 0 }}><h3 data-testid="detail-name">{unit.name}</h3><small className="hint" data-testid="detail-path" title={path} aria-label={path}>{compactPath(path)}</small></div></div>
      <dl className="kv">
        <div><dt>Loại</dt><dd data-testid="detail-type">{type ? type.name : "Chưa chọn loại"}</dd></div>
        {unit.code ? <div><dt>Mã</dt><dd>{unit.code}</dd></div> : null}
        <div><dt>Trạng thái</dt><dd>{unit.enabled ? <span className="pill pill-ok">Hoạt động</span> : <span className="pill pill-muted">Đã tắt</span>}</dd></div>
        {unit.employeeCount !== undefined ? <div><dt>Nhân viên</dt><dd data-testid="detail-employees">{unit.employeeCount}</dd></div> : null}
        <div><dt>Đơn vị con</dt><dd data-testid="detail-children">{unit.childCount ?? units.filter((u) => u.parentId === unit.id).length}</dd></div>
      </dl>
      <div className="xp-orgActions">
        <button className="btn xp-btnIcon" data-testid="org-add-child" disabled={!canEdit} onClick={() => onAction("child")}><Plus size={16} aria-hidden="true"/> Thêm đơn vị con</button>
        <button className="btn xp-btnIcon" data-testid="org-edit" disabled={!canEdit} onClick={() => onAction("edit")}><Pencil size={16} aria-hidden="true"/> Sửa</button>
        <button className="btn xp-btnIcon" data-testid="org-move" disabled={!canEdit} onClick={() => onAction("move")}><ArrowRightLeft size={16} aria-hidden="true"/> Di chuyển tới…</button>
        <button className="btn xp-btnIcon" data-testid="org-toggle" disabled={!canEdit || busy} onClick={() => onAction("toggle")}><Power size={16} aria-hidden="true"/> {unit.enabled ? "Tắt" : "Bật"}</button>
        <button className="btn danger xp-btnIcon" data-testid="org-delete" disabled={!canEdit || !!block} title={block ?? undefined} onClick={() => onAction("delete")}><Trash2 size={16} aria-hidden="true"/> Xóa</button>
      </div>
      {block ? <p className="hint" data-testid="org-delete-blocked">Chưa xóa được: {block}</p> : null}
      {problem ? <Problem p={problem}/> : null}
    </div>
  );
}

// ------------------------------------------------------------------------------------------------------------------------------- dialogs
function typeOptions(types: OrgUnitType[]): PickerOption<string>[] {
  return [{ value: "", label: "Không chọn loại", hint: "Đơn vị tự do", icon: <span className="xp-nodeIcon"><UnitIcon id={null}/></span> }, ...types.map((t) => ({ value: t.id, label: t.name, hint: t.code, icon: <span className="xp-nodeIcon"><UnitIcon id={t.icon}/></span> }))];
}
function UnitDialog({ mode, tenantId, api, units, types, unit, parent, onClose, onDone, onReload }: { mode: "create" | "edit"; tenantId: string; api: OrganizationApi; units: OrgUnit[]; types: OrgUnitType[]; unit?: OrgUnit; parent: OrgUnit | null; onClose: () => void; onDone: (u?: OrgUnit) => void; onReload: () => void }) {
  const uid = useId(); const [name, setName] = useState(unit?.name ?? ""); const [code, setCode] = useState(unit?.code ?? ""); const [typeId, setTypeId] = useState(unit?.typeId ?? "");
  const [touched, setTouched] = useState(false); const [busy, setBusy] = useState(false); const [problem, setProblem] = useState<OrgProblem | null>(null);
  const errors = validateUnitForm({ name, code, typeId: typeId || null }, { types, parent });
  const title = mode === "create" ? (parent ? `Thêm đơn vị con của ${parent.name}` : "Thêm đơn vị gốc") : "Sửa đơn vị";
  async function submit(e: FormEvent) {
    e.preventDefault(); setTouched(true); setProblem(null);
    if (Object.keys(errors).length) return;
    setBusy(true);
    try {
      if (mode === "create") onDone(await api.createOrganizationUnit(tenantId, { parentId: parent?.id ?? null, typeId: typeId || null, name, ...(code.trim() ? { code: code.trim() } : {}) }));
      else { await api.updateOrganizationUnit(tenantId, unit!.id, unit!.version, { name, code: code.trim() || null, typeId: typeId || null }); onDone(); }
    } catch (err) { setProblem(orgProblem(err)); } finally { setBusy(false); }
  }
  return (
    <Modal label={title} onClose={onClose}>
      <form className="modalBody" noValidate onSubmit={(e) => void submit(e)} data-testid="unit-dialog">
        <ModalHeader icon={<Building2 size={22}/>} title={title} subtitle={parent ? `Nằm trong: ${unitPath(units, parent.id)}` : "Đơn vị gốc không thuộc đơn vị nào."}/>
        <section className="xp-section" aria-label="Thông tin đơn vị">
          <label className="field"><span>Tên đơn vị</span><input data-testid="unit-name" value={name} maxLength={120} autoComplete="off" placeholder="Ví dụ: Khối Công nghệ" aria-invalid={touched && !!errors.name} aria-describedby={touched && errors.name ? `${uid}-name-err` : undefined} onChange={(e) => setName(e.target.value)}/></label>
          {touched && errors.name ? <p className="formError" role="alert" id={`${uid}-name-err`}>{errors.name}</p> : null}
          <label className="field"><span>Mã (không bắt buộc)</span><input data-testid="unit-code" value={code} autoComplete="off" spellCheck={false} aria-invalid={touched && !!errors.code} aria-describedby={touched && errors.code ? `${uid}-code-err` : undefined} onChange={(e) => setCode(e.target.value)}/></label>
          {touched && errors.code ? <p className="formError" role="alert" id={`${uid}-code-err`}>{errors.code}</p> : null}
          <Picker label="Loại đơn vị" value={typeId} options={typeOptions(types)} onChange={setTypeId} describedBy={touched && errors.type ? `${uid}-type-err` : undefined}/>
          {types.length === 0 ? <p className="hint">Chưa có loại đơn vị nào. Tạo loại (Khối, Chi nhánh, Phòng…) ở nút “Loại đơn vị”, hoặc để đơn vị tự do.</p> : null}
          {touched && errors.type ? <p className="formError" role="alert" id={`${uid}-type-err`} data-testid="unit-type-error">{errors.type}</p> : null}
        </section>
        {problem ? <Problem p={problem} onReload={onReload}/> : null}
        <div className="xp-footer"><button type="button" className="btn" onClick={onClose}>Hủy</button><button className="btn primary" data-testid="unit-submit" disabled={busy}>{busy ? "Đang lưu…" : mode === "create" ? "Thêm đơn vị" : "Lưu"}</button></div>
      </form>
    </Modal>
  );
}

function MoveDialog({ tenantId, api, units, types, unit, onClose, onDone, onReload }: { tenantId: string; api: OrganizationApi; units: OrgUnit[]; types: OrgUnitType[]; unit: OrgUnit; onClose: () => void; onDone: () => void; onReload: () => void }) {
  const targets = useMemo(() => moveTargets(units, types, unit.id), [units, types, unit.id]);
  const [to, setTo] = useState<string | null | undefined>(undefined); const [busy, setBusy] = useState(false); const [problem, setProblem] = useState<OrgProblem | null>(null);
  async function submit(e: FormEvent) {
    e.preventDefault(); setProblem(null); if (to === undefined) return;
    setBusy(true); try { await api.moveOrganizationUnit(tenantId, unit.id, unit.version, to); onDone(); } catch (err) { setProblem(orgProblem(err)); } finally { setBusy(false); }
  }
  return (
    <Modal label={`Di chuyển ${unit.name}`} onClose={onClose}>
      <form className="modalBody" onSubmit={(e) => void submit(e)} data-testid="move-dialog">
        <ModalHeader icon={<ArrowRightLeft size={22}/>} title={`Di chuyển “${unit.name}”`} subtitle="Chọn đơn vị cha mới. Toàn bộ đơn vị con đi theo."/>
        <fieldset className="xp-moveList" aria-label="Đơn vị cha mới">
          {targets.map((t) => (
            <label key={t.id ?? "root"} className={`xp-moveRow${t.disabled ? " disabled" : ""}${to === t.id ? " sel" : ""}`} style={{ paddingLeft: 10 + t.depth * 18 }} data-testid={`move-to:${t.id ?? "root"}`}>
              <input type="radio" name="move-to" disabled={t.disabled} checked={to === t.id} onChange={() => setTo(t.id)}/>
              <span className="xp-pickerCur"><b>{t.label}</b>{t.reason ? <small>{t.reason}</small> : null}</span>
            </label>))}
        </fieldset>
        {problem ? <Problem p={problem} onReload={onReload}/> : null}
        <div className="xp-footer"><button type="button" className="btn" onClick={onClose}>Hủy</button><button className="btn primary" data-testid="move-submit" disabled={busy || to === undefined}>{busy ? "Đang chuyển…" : "Di chuyển"}</button></div>
      </form>
    </Modal>
  );
}

function DeleteDialog({ tenantId, api, units, unit, onClose, onDone, onReload }: { tenantId: string; api: OrganizationApi; units: OrgUnit[]; unit: OrgUnit; onClose: () => void; onDone: () => void; onReload: () => void }) {
  const [busy, setBusy] = useState(false); const [problem, setProblem] = useState<OrgProblem | null>(null); const block = deleteBlock(unit, units);
  async function go() { setBusy(true); setProblem(null); try { await api.deleteOrganizationUnit(tenantId, unit.id, unit.version); onDone(); } catch (err) { setProblem(orgProblem(err)); } finally { setBusy(false); } }
  return (
    <Modal label={`Xóa ${unit.name}`} onClose={onClose}>
      <div className="modalBody" data-testid="delete-dialog">
        <ModalHeader icon={<Trash2 size={22}/>} title={`Xóa “${unit.name}”?`} subtitle="Chỉ xóa được đơn vị không còn đơn vị con và nhân viên. Việc này không hòan tác được."/>
        {block ? <p className="hint">{block}</p> : null}
        {problem ? <Problem p={problem} onReload={onReload}/> : null}
        <div className="xp-footer"><button className="btn" onClick={onClose}>Hủy</button><button className="btn danger" data-testid="delete-confirm" disabled={busy || !!block} onClick={() => void go()}>{busy ? "Đang xóa…" : "Xóa đơn vị"}</button></div>
      </div>
    </Modal>
  );
}

function TypesDialog({ tenantId, api, plan, types, onClose, onChanged }: { tenantId: string; api: OrganizationApi; plan: OrganizationPlan; types: OrgUnitType[]; onClose: () => void; onChanged: () => void }) {
  const uid = useId(); const [name, setName] = useState(""); const [code, setCode] = useState(""); const [icon, setIcon] = useState("folder"); const [allowed, setAllowed] = useState<string[]>([]);
  const [touched, setTouched] = useState(false); const [busy, setBusy] = useState(false); const [problem, setProblem] = useState<OrgProblem | null>(null); const [added, setAdded] = useState<OrgUnitType[]>([]);
  const all = [...types, ...added.filter((a) => !types.some((t) => t.id === a.id))];
  const errors = validateTypeForm({ name, code, icon }, all); const createState = api.state("createOrganizationUnitType");
  async function submit(e: FormEvent) {
    e.preventDefault(); setTouched(true); setProblem(null); if (Object.keys(errors).length || createState.status === "NOT_READY") return;
    setBusy(true);
    try {
      const body: NewOrgUnitType = { name, code: code.trim().toUpperCase(), icon, ...(allowed.length ? { allowedParentTypeIds: allowed } : {}) };
      const t = await api.createOrganizationUnitType(tenantId, body); setAdded((a) => [...a, t]); setName(""); setCode(""); setIcon("folder"); setAllowed([]); setTouched(false); onChanged();
    } catch (err) { setProblem(orgProblem(err)); } finally { setBusy(false); }
  }
  return (
    <Modal label="Loại đơn vị" onClose={onClose}>
      <div className="modalBody" data-testid="types-dialog">
        <ModalHeader icon={<Settings2 size={22}/>} title="Loại đơn vị" subtitle="Công ty tự định nghĩa các loại (Khối, Chi nhánh, Phòng, Team…). Không có cấp bậc cố định."/>
        <section className="xp-section" aria-label="Các loại hiện có">
          <h3>Các loại hiện có</h3>
          {plan.types.state === "not-ready" ? <NotReadyPanel testid="types-not-ready" title="Chưa sẵn sàng" reason={plan.types.reason}/> : all.length === 0 ? <p className="hint" data-testid="types-empty">Chưa có loại nào.</p> : (
            <ul className="xp-typeList" data-testid="types-list">{all.map((t) => (
              <li key={t.id}><span className="xp-nodeIcon"><UnitIcon id={t.icon}/></span><span className="xp-pickerCur"><b>{t.name}</b><small>{t.code}{t.allowedParentTypeIds?.length ? ` · đặt trong: ${t.allowedParentTypeIds.map((a) => all.find((x) => x.id === a)?.name ?? a).join(", ")}` : " · đặt ở bất kỳ đâu"}</small></span></li>))}</ul>)}
        </section>
        <form className="xp-section" noValidate onSubmit={(e) => void submit(e)} aria-label="Thêm loại đơn vị" data-testid="type-form">
          <h3>Thêm loại mới</h3>
          {createState.status === "NOT_READY" ? <NotReadyPanel testid="type-create-not-ready" title="Chưa thêm được loại" reason={createState.reason}/> : null}
          <label className="field"><span>Tên loại</span><input data-testid="type-name" value={name} maxLength={80} autoComplete="off" placeholder="Ví dụ: Khối" aria-invalid={touched && !!errors.name} aria-describedby={touched && errors.name ? `${uid}-tname-err` : undefined} onChange={(e) => setName(e.target.value)}/></label>
          {touched && errors.name ? <p className="formError" role="alert" id={`${uid}-tname-err`}>{errors.name}</p> : null}
          <label className="field"><span>Mã</span><input data-testid="type-code" value={code} autoComplete="off" spellCheck={false} placeholder="DIVISION" aria-invalid={touched && !!errors.code} aria-describedby={touched && errors.code ? `${uid}-tcode-err` : undefined} onChange={(e) => setCode(e.target.value.toUpperCase())}/></label>
          {touched && errors.code ? <p className="formError" role="alert" id={`${uid}-tcode-err`}>{errors.code}</p> : null}
          <div className="field" role="radiogroup" aria-label="Biểu tượng"><span>Biểu tượng</span>
            <div className="xp-iconGrid">{UNIT_ICONS.map((i) => (
              <button key={i.id} type="button" role="radio" aria-checked={icon === i.id} aria-label={i.label} title={i.label} data-testid={`icon:${i.id}`} className={`xp-iconBtn${icon === i.id ? " sel" : ""}`} onClick={() => setIcon(safeIcon(i.id))}><UnitIcon id={i.id} size={20}/></button>))}</div>
            <small className="hint">Chỉ chọn trong bộ biểu tượng có sẵn; không dùng đường dẫn ảnh.</small></div>
          {all.length ? <fieldset className="xp-parentTypes" aria-label="Được đặt trong loại"><legend className="hint">Được đặt trong loại (để trống = đặt ở bất kỳ đâu)</legend>
            {all.map((t) => <label key={t.id} className="xp-check"><input type="checkbox" checked={allowed.includes(t.id)} onChange={(e) => setAllowed(e.target.checked ? [...allowed, t.id] : allowed.filter((x) => x !== t.id))}/> {t.name}</label>)}</fieldset> : null}
          {problem ? <Problem p={problem}/> : null}
          <div className="xp-footer"><button type="button" className="btn" onClick={onClose}>Đóng</button><button className="btn primary" data-testid="type-submit" disabled={busy || createState.status === "NOT_READY"}>{busy ? "Đang lưu…" : "Thêm loại"}</button></div>
        </form>
      </div>
    </Modal>
  );
}
