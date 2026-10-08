"use client";
/** Right column: Content, Design, Data, Action, Permission, Advanced. No raw JSON for normal users; Advanced is read-only identifiers and never shows a secret. */
import { useMemo, useState } from "react";
import { CircleCheck, CircleX, X } from "../../../packages/ui/src/icons";
import type { AssetDto, ComponentMetadataV2, EventType, PermissionCode, RegistryComponent, SchemaOperation, Section } from "@xweb/types";
import { PERMISSION_CODES, PERMISSION_RESOURCE_TYPES } from "./core/contract";
import { actionsOfSection, describeAction, eventsFor, EVENT_LABEL, roleOf } from "./core/actions";
import { bindingCompatibility, viewStateOf, VIEW_STATE_TEXT, mappingNote } from "./core/dataFlow";
import { defOps } from "./core/definition";
import { bindableProps, groupProps, permissionLabel, permissionRefsFor, propsSchemaOfRegistry, tabStates, type InspectorTabId } from "./core/inspector";
import { capabilitiesFor, whyNot } from "./core/permissions";
import { staticReadiness } from "./core/readiness";
import { ActionEditor } from "./ActionEditor";
import { PropsForm } from "./PropsForm";
import { Dialog, Gate, StateBox, Tabs, tabPanelProps } from "./ui/primitives";
import type { DefCtx } from "./ctx";

const uid = () => Math.random().toString(36).slice(2, 7);

export function Inspector({ ctx, section, component, meta, index, count, canUp, canDown, readOnly, busy, assets, rawPermissions, onApply, onClose, onMove, onRemove, onSaveBlock, openDataWizard, pageId }: {
  ctx: DefCtx; section: Section; component?: RegistryComponent; meta?: ComponentMetadataV2; index: number; count: number; canUp?: boolean; canDown?: boolean; readOnly: boolean; busy: boolean; assets: AssetDto[];
  rawPermissions: readonly string[]; onApply: (ops: SchemaOperation[], summary: string) => Promise<boolean>; onClose: () => void; onMove: (d: -1 | 1) => void;
  onRemove: () => void; onSaveBlock?: () => void; openDataWizard: (sectionId: string) => void; pageId: string;
}) {
  const [tab, setTab] = useState<InspectorTabId>("content");
  const groups = useMemo(() => groupProps(component), [component]);
  const defs = useMemo(() => propsSchemaOfRegistry(component), [component]);
  const states = tabStates({ section, component, meta, canEdit: !readOnly, definitionOps: ctx.readiness });
  const label = ctx.labelOf(section.type);
  const prefix = "inspector";
  const items = states.map((s) => ({ id: s.id, label: s.label, badge: s.readiness.state === "NOT_READY" ? "chưa" : undefined }));
  const current = states.find((s) => s.id === tab)!;

  return (
    <section className="bx-inspector" aria-label={`Thuộc tính của ${label}`}>
      <div className="bx-insp-head">
        <div><h2>{label}</h2><small>{section.id}</small></div>
        <button type="button" className="bx-icon" aria-label="Đóng bảng thuộc tính" title="Đóng bảng thuộc tính" onClick={onClose}><X size={16} aria-hidden="true"/></button>
      </div>
      <Tabs label="Nhóm thuộc tính" idPrefix={prefix} items={items} value={tab} onChange={(t) => setTab(t as InspectorTabId)}/>
      <div {...tabPanelProps(prefix, tab)} className="bx-tabpanel">
        {tab === "content" ? (<>
          <PropsForm key={`c:${section.id}:${JSON.stringify(section.props)}`} section={section} entries={[...groups.content, ...(groups.visibility ? [groups.visibility] : [])]} allDefs={defs} assets={assets}
            readOnly={readOnly} busy={busy} summary={`Chỉnh sửa ${label}`} onApply={onApply} emptyText="Thành phần này không có nội dung chỉnh được."/>
          <div className="bx-insp-tools">
            <button type="button" className="bx-btn sm" disabled={readOnly || busy || !(canUp ?? index > 0)} onClick={() => onMove(-1)}>↑ Lên</button>
            <button type="button" className="bx-btn sm" disabled={readOnly || busy || !(canDown ?? index < count - 1)} onClick={() => onMove(1)}>↓ Xuống</button>
            <button type="button" className="bx-btn sm danger" disabled={readOnly || busy} onClick={onRemove}>Xóa mục</button>
            {onSaveBlock && !readOnly ? <button type="button" className="bx-btn sm" onClick={onSaveBlock}>Lưu thành khối…</button> : null}
          </div>
        </>) : null}
        {tab === "design" ? (current.readiness.state === "AVAILABLE"
          ? <PropsForm key={`d:${section.id}:${JSON.stringify(section.props)}`} section={section} entries={groups.design} allDefs={defs} assets={assets} readOnly={readOnly} busy={busy} summary={`Chỉnh giao diện ${label}`} onApply={onApply} emptyText=""/>
          : <StateBox state={current.readiness}/>) : null}
        {tab === "data" ? <DataTab ctx={ctx} section={section} component={component} meta={meta} state={current.readiness} openDataWizard={openDataWizard}/> : null}
        {tab === "action" ? <ActionTab ctx={ctx} section={section} meta={meta} state={current.readiness}/> : null}
        {tab === "permission" ? <PermissionTab ctx={ctx} section={section} rawPermissions={rawPermissions}/> : null}
        {tab === "advanced" ? <AdvancedTab ctx={ctx} section={section} meta={meta} component={component} pageId={pageId}/> : null}
      </div>
    </section>
  );
}

function DataTab({ ctx, section, component, meta, state, openDataWizard }: { ctx: DefCtx; section: Section; component?: RegistryComponent; meta?: ComponentMetadataV2; state: ReturnType<typeof staticReadiness>; openDataWizard: (id: string) => void }) {
  const { doc } = ctx;
  const props = bindableProps(component, meta);
  const mine = (doc.dataBindings ?? []).filter((b) => b.sectionId === section.id);
  const [removing, setRemoving] = useState<string | null>(null);
  if (state.state !== "AVAILABLE") return <StateBox state={state}/>;
  const vmView = viewStateOf(staticReadiness("QUERY_PREVIEW"), null);
  return (
    <div className="bx-form">
      {props.length === 0 ? <p className="hint">Thành phần này không có thuộc tính nhận dữ liệu.</p> : (
        <ul className="bx-list" aria-label="Thuộc tính nhận dữ liệu">{props.map((p) => {
          const b = mine.find((x) => x.prop === p.prop);
          const vm = b?.viewModelRef ? (doc.viewModels ?? []).find((v) => v.id === b.viewModelRef) : undefined;
          const q = vm?.queryRef ? (doc.queries ?? []).find((x) => x.id === vm.queryRef) : undefined;
          const m = vm?.mappingRef ? (doc.mappings ?? []).find((x) => x.id === vm.mappingRef) : undefined;
          const compat = vm ? bindingCompatibility({ prop: p.prop, cardinality: p.cardinality, itemFields: p.itemFields }, vm) : null;
          return (
            <li key={p.prop}>
              <div>
                <b>{p.prop}</b><small>{p.cardinality === "LIST" ? "danh sách" : "một giá trị"}{p.derived ? " · suy ra từ schema" : ""}</small>
                {b ? (<>
                  <small>ViewModel: {vm?.name ?? b.viewModelRef ?? "—"}{q ? ` · truy vấn ${q.name ?? q.id}` : ""}</small>
                  {m ? <small>Ánh xạ: {mappingNote(m)}</small> : null}
                  {compat && !compat.ok ? <small className="formError" role="alert">{compat.message}</small> : null}
                  <small>Trạng thái: {VIEW_STATE_TEXT[vmView.kind]}{vmView.kind === "not-ready" ? ` — ${vmView.reason}` : ""}</small>
                </>) : <small>Chưa gắn dữ liệu</small>}
              </div>
              {b && ctx.canEdit ? <button type="button" className="smallButton danger" aria-label={`Gỡ dữ liệu khỏi ${p.prop}`} onClick={() => setRemoving(b.id)}>Gỡ</button> : null}
            </li>);
        })}</ul>)}
      {ctx.canEdit ? <button type="button" className="bx-btn sm" onClick={() => openDataWizard(section.id)}>Thiết lập luồng dữ liệu…</button> : null}
      {removing ? (
        <Dialog title="Gỡ dữ liệu khỏi thành phần?" onClose={() => setRemoving(null)} footer={<>
          <button type="button" className="bx-btn" onClick={() => setRemoving(null)}>Hủy</button>
          <button type="button" className="bx-btn danger" disabled={ctx.busy} onClick={() => void ctx.commit([defOps.remove("dataBindings", removing)], "Gỡ liên kết dữ liệu").then((ok) => { if (ok) setRemoving(null); })}>Gỡ</button></>}>
          <p>Thành phần sẽ trở lại nội dung viết tay. Truy vấn, ánh xạ và ViewModel vẫn còn.</p>
        </Dialog>) : null}
    </div>
  );
}

function ActionTab({ ctx, section, meta, state }: { ctx: DefCtx; section: Section; meta?: ComponentMetadataV2; state: ReturnType<typeof staticReadiness> }) {
  const events = eventsFor(meta);
  const [adding, setAdding] = useState<EventType | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  if (state.state !== "AVAILABLE") return <StateBox state={state}/>;
  const mine = actionsOfSection(ctx.doc, section.id);
  if (adding || editingId) {
    const initial = editingId ? mine.find((a) => a.id === editingId) : undefined;
    return <ActionEditor ctx={ctx} initial={initial} preset={adding ? { sectionId: section.id, event: adding } : undefined} onDone={() => { setAdding(null); setEditingId(null); }} onCancel={() => { setAdding(null); setEditingId(null); }}/>;
  }
  return (
    <div className="bx-form">
      {events.length === 0 ? <p className="hint">Thành phần này không phát sự kiện nào.</p> : events.map((e) => {
        const list = mine.filter((a) => a.trigger?.event === e.name);
        return (
          <fieldset className="bx-group" key={e.name}><legend>{EVENT_LABEL[e.name]}</legend>
            {list.length ? <ul className="bx-list">{list.map((a) => (
              <li key={a.id}><div><b>{a.name || a.id}</b><small>{describeAction(a, ctx.doc)}</small><small>{roleOf(a, ctx.doc) === "UI_BOUND" ? "Gắn vào thành phần" : ""}</small></div>
                {ctx.canEdit ? <button type="button" className="smallButton" onClick={() => setEditingId(a.id)}>Sửa</button> : null}</li>))}</ul> : <p className="hint">Chưa có hành động.</p>}
            {ctx.canEdit && e.supportedActions.length ? <button type="button" className="smallButton" onClick={() => setAdding(e.name)}>+ Thêm hành động</button> : null}
          </fieldset>);
      })}
    </div>
  );
}

function PermissionTab({ ctx, section, rawPermissions }: { ctx: DefCtx; section: Section; rawPermissions: readonly string[] }) {
  const cap = capabilitiesFor(rawPermissions);
  const { doc } = ctx;
  const refs = [
    ...(doc.dataBindings ?? []).filter((b) => b.sectionId === section.id).flatMap((b) => b.viewModelRef ? [{ type: "VIEW_MODEL" as const, ref: b.viewModelRef, label: `ViewModel ${(doc.viewModels ?? []).find((v) => v.id === b.viewModelRef)?.name ?? b.viewModelRef}` }] : []),
    ...actionsOfSection(doc, section.id).map((a) => ({ type: "ACTION" as const, ref: a.id, label: `Hành động ${a.name ?? a.id}` })),
  ];
  const [perm, setPerm] = useState<PermissionCode>("APP_VIEW");
  const [target, setTarget] = useState(refs[0] ? `${refs[0].type}:${refs[0].ref}` : "");
  const disabled = !ctx.canEdit || ctx.busy;
  async function add() {
    const [rt, rr] = target.split(":");
    if (!PERMISSION_RESOURCE_TYPES.includes(rt as never) || !rr) return;
    await ctx.commit([defOps.add("permissions", { id: `perm-${uid()}`, name: permissionLabel[perm], permission: perm, resourceType: rt, resourceRef: rr } as never)], "Thêm quyền");
  }
  return (
    <div className="bx-form">
      <p className="hint" role="note">Ẩn hay khoá một nút trên giao diện không phải là phân quyền. Máy chủ kiểm tra quyền ở mọi lệnh gọi.</p>
      <h3 className="bx-h3">Quyền của bạn trên ứng dụng này</h3>
      <ul className="bx-caps" aria-label="Quyền của bạn">
        {([["canView", "Xem"], ["canEdit", "Chỉnh sửa"], ["canPublish", "Xuất bản"], ["canShare", "Chia sẻ"]] as const).map(([k, l]) => (
          <li key={k} className={cap[k] ? "yes" : "no"}><span aria-hidden="true" className="xp-checkIcon">{cap[k] ? <CircleCheck size={14}/> : <CircleX size={14}/>}</span> {l}{cap[k] ? "" : <small> · {whyNot(k)}</small>}</li>))}
      </ul>
      <h3 className="bx-h3">Quyền cần có để dùng thành phần này</h3>
      {refs.length === 0 ? <p className="hint">Thành phần chưa gắn dữ liệu hay hành động, nên chưa có quyền riêng nào để khai báo.</p> : (
        <>
          <ul className="bx-list" aria-label="Quyền đã khai báo">{refs.flatMap((r) => permissionRefsFor(doc, r.type, r.ref).map((p) => (
            <li key={p.id}><div><b>{permissionLabel[p.permission]}</b><small>{r.label}</small></div>
              {!disabled && ctx.readiness.state === "AVAILABLE" ? <button type="button" className="smallButton danger" aria-label={`Bỏ quyền ${permissionLabel[p.permission]} của ${r.label}`} onClick={() => void ctx.commit([defOps.remove("permissions", p.id)], "Bỏ quyền")}>Bỏ</button> : null}</li>)))}</ul>
          <Gate state={ctx.readiness} compact>
            {ctx.canEdit ? (
              <form className="bx-row" onSubmit={(e) => { e.preventDefault(); void add(); }}>
                <label className="srOnly" htmlFor="perm-target">Áp dụng cho</label>
                <select id="perm-target" value={target} onChange={(e) => setTarget(e.target.value)}>{refs.map((r) => <option key={`${r.type}:${r.ref}`} value={`${r.type}:${r.ref}`}>{r.label}</option>)}</select>
                <label className="srOnly" htmlFor="perm-code">Quyền</label>
                <select id="perm-code" value={perm} onChange={(e) => setPerm(e.target.value as PermissionCode)}>{PERMISSION_CODES.map((c) => <option key={c} value={c}>{permissionLabel[c]}</option>)}</select>
                <button type="submit" className="smallButton" disabled={disabled}>+ Thêm</button>
              </form>) : null}
          </Gate>
        </>)}
    </div>
  );
}

function AdvancedTab({ ctx, section, meta, component, pageId }: { ctx: DefCtx; section: Section; meta?: ComponentMetadataV2; component?: RegistryComponent; pageId: string }) {
  const bindings = (ctx.doc.dataBindings ?? []).filter((b) => b.sectionId === section.id);
  const acts = actionsOfSection(ctx.doc, section.id);
  const rows: [string, string][] = [
    ["Mã thành phần", section.id], ["Loại", section.type], ["Phiên bản component", component?.latestVersion ?? "—"], ["Trang", pageId],
    ["Nguồn metadata", meta ? (meta.source === "OVERLAY" ? "Khai báo bởi registry" : "Suy ra từ schema") : "Chưa có (máy chủ chưa trả component-metadata)"],
    ["Số thuộc tính", String(Object.keys(section.props).length)],
    ["Mã gắn dữ liệu", bindings.map((b) => b.id).join(", ") || "—"], ["Mã hành động", acts.map((a) => a.id).join(", ") || "—"],
  ];
  return (
    <div className="bx-form">
      <p className="hint">Thông tin chỉ đọc để hỗ trợ kỹ thuật. Không có khóa, mật khẩu hay mã định danh cơ sở dữ liệu ở đây.</p>
      <dl className="bx-dl">{rows.map(([k, v]) => <div key={k}><dt>{k}</dt><dd><code>{v}</code></dd></div>)}</dl>
    </div>
  );
}
