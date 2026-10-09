"use client";
/**
 * The Builder (Design mode): top bar, left tools, centre canvas with real drag and drop, right inspector. One write funnel: `applyOps`.
 * Nothing here talks to a backend that does not exist: backend-dependent tools render NOT_READY with a reason.
 */
import { Activity, useCallback, useMemo, useRef, useState, type ReactNode } from "react";
import {
  DndContext, DragOverlay, KeyboardSensor, PointerSensor, closestCenter, rectIntersection, useSensor, useSensors,
  type Announcements, type CollisionDetection, type DragEndEvent, type DragMoveEvent, type DragStartEvent,
} from "@dnd-kit/core";
import { sortableKeyboardCoordinates } from "@dnd-kit/sortable";
import type { ApiProject, AppDefinitionV2, AssetDto, DefinitionOperation, RegistryComponent, SchemaOperation, Section } from "@xweb/types";
import { useOverflow } from "../../../packages/ui/src/useOverflow";
import { ErrorBoundary } from "../../../packages/ui/src/ErrorBoundary";
import { Canvas, DragChip } from "./Canvas";
import { Inspector } from "./Inspector";
import type { PropsDraft } from "./PropsForm";
import { LeftRail, type RailId } from "./LeftRail";
import { BuilderTopBar } from "./BuilderTopBar";
import { TestPanel, type RuntimeCalls } from "./TestPanel";
import type { DataManagementCalls } from "./core/dataManagement";
import { DataPanel } from "./DataPanel";
import { ComponentsPanel, type BlockOption } from "./panels/ComponentsPanel";
import { PagesPanel } from "./panels/PagesPanel";
import { ActionsPanel } from "./panels/ActionsPanel";
import { WorkflowsPanel } from "./panels/WorkflowsPanel";
import { AiPanel, FormsPanel, ThemePanel } from "./panels/MiscPanels";
import { Dialog, StateBox, Tabs } from "./ui/primitives";
import type { DefCtx } from "./ctx";
import type { Backend } from "./core/backend";
import { canStep, clampSlot, planAdd, planMove, planStep, sectionIndexForSlot, slotForClickAdd, slotFromPoint, type SectionRect } from "./core/dnd";
import { defaultProps, typeLabel } from "./core/library";
import { sectionsOf } from "./core/pages";
import { allSections } from "./core/definition";
import { capabilitiesFor, whyNot } from "./core/permissions";
import { blockers, preflight, type PreflightIssue } from "./core/preflight";
import type { Readiness } from "./core/readiness";

type Device = "desktop" | "tablet" | "mobile";
type Ops = (SchemaOperation | DefinitionOperation)[];

const newId = (prefix: string) => `${prefix}-${Math.random().toString(36).slice(2, 7)}`;

const announcements: Announcements = {
  onDragStart: ({ active }) => `Đã nhấc ${String(active.id).replace(/^(lib|sec|row):/, "")}. Dùng phím mũi tên để di chuyển, Space để thả, Esc để hủy.`,
  onDragOver: ({ over }) => (over ? "Đang ở trên vùng có thể thả." : "Đang ở ngoài vùng thả."),
  onDragEnd: ({ over }) => (over ? "Đã thả." : "Đã thả ngoài vùng hợp lệ, không có thay đổi."),
  onDragCancel: () => "Đã hủy kéo thả.",
};
const screenReaderInstructions = { draggable: "Nhấn Space hoặc Enter để nhấc, mũi tên để di chuyển, Space để thả, Esc để hủy. Hoặc dùng nút “Thêm” / “Lên” / “Xuống”." };

function pointerY(e: DragMoveEvent | DragEndEvent): number | null {
  const a = e.activatorEvent as { clientY?: number } | null;
  if (a && typeof a.clientY === "number") return a.clientY + e.delta.y;
  const r = e.active.rect.current.translated;
  return r ? r.top + r.height / 2 : null;
}

export function BuilderWorkspace(props: {
  project: ApiProject; doc: AppDefinitionV2; revision: number; registry: RegistryComponent[]; assets: AssetDto[]; blocks: BlockOption[]; backend: Backend;
  pageId: string; onPage: (id: string) => void; selectedId: string | null; onSelect: (id: string | null) => void; device: Device; onDevice: (d: Device) => void;
  busy: boolean; save: { state: "saved" | "saving" | "error"; at: Date | null }; readOnly: boolean; latest?: number;
  applyOps: (ops: Ops, summary: string, blockId?: string) => Promise<boolean>; addBlock: (id: string) => void;
  /** the reason the last failed save was explained with (M-044): dialogs show it themselves instead of a generic line */
  lastFailure?: () => string | null;
  renderPreview: (o: { selectedId: string | null; interactive: boolean; pageId: string }) => string;
  labelOf: (type: string) => string; summaryOf: (s: Section) => string;
  leading?: ReactNode; modeTabs?: ReactNode; trailing?: ReactNode;
  runtime?: RuntimeCalls; dataManagement?: DataManagementCalls; onRetrySave?: () => void; goAi: () => void; openSite: () => void; openMembers: () => void; openPublish: () => void; saveBlock: () => void;
}) {
  const { doc, registry, backend, pageId, selectedId, readOnly, busy } = props;
  const cap = capabilitiesFor(props.project.permissions);
  const [appMode, setAppMode] = useState<"EDIT" | "TEST">("EDIT");
  const [rail, setRail] = useState<RailId>("pages");
  // phones (<= 760 px): ONE workspace at a time (canvas / tools / properties). All three stay mounted, so switching never reloads the canvas or loses a form; CSS shows one. Wider screens ignore this state.
  const [mview, setMview] = useState<"canvas" | "tools" | "props">("canvas");
  const openRail = (id: RailId) => {
    setRail(id); setMview("tools");
    // if the pane that held focus (e.g. the Inspector) is hidden by this switch on a phone, move focus to the tab we land on instead of letting it fall to <body>
    requestAnimationFrame(() => { const a = document.activeElement; if (!a || a === document.body || a.getClientRects().length === 0) { window.scrollTo(0, 0); document.getElementById("mview-tab-tools")?.focus(); } });
  };
  const [dataFocus, setDataFocus] = useState<{ sectionId?: string; prop?: string; n?: number }>({});
  const [actionPreset, setActionPreset] = useState<{ sectionId?: string } | undefined>(undefined);
  const [rects, setRects] = useState<SectionRect[]>([]);
  const [drag, setDrag] = useState<{ kind: "lib" | "sec" | "row"; id: string; label: string } | null>(null);
  const [slot, setSlot] = useState<number | null>(null);
  const [removing, setRemoving] = useState(false);
  const [check, setCheck] = useState<PreflightIssue[] | null>(null);
  const frameRef = useRef<HTMLIFrameElement>(null);
  const rightRef = useRef<HTMLElement>(null); const rightScrolls = useOverflow(rightRef, "y"); // the properties panel is a keyboard stop only while it scrolls

  const sections = useMemo(() => sectionsOf(doc, pageId), [doc, pageId]);
  const selected = sections.find((s) => s.id === selectedId) ?? null;
  const edit = appMode === "EDIT";
  const interactive = edit && !readOnly;
  const issues = useMemo(() => preflight(doc), [doc]);
  // unsaved Inspector edits (M-002), held here so they survive selecting another section; a draft made on props that have since changed is stale and ignored
  const [drafts, setDrafts] = useState<Record<string, PropsDraft>>({});
  const setDraft = useCallback((key: string, d: PropsDraft | null) => setDrafts((prev) => {
    if (d) return { ...prev, [key]: d };
    if (!(key in prev)) return prev;
    const { [key]: _gone, ...rest } = prev; void _gone; return rest;
  }), []);
  const unsaved: PreflightIssue[] = useMemo(() => Object.entries(drafts).flatMap(([key, d]) => {
    const s = allSections(doc).find((x) => x.id === key.slice(2));
    return s && d.base === JSON.stringify(s.props) ? [{ severity: "WARN" as const, code: "UNSAVED_DRAFT" as const, message: `Có thay đổi chưa lưu ở “${props.labelOf(s.type)}”. Bản xuất bản dùng nội dung đã lưu: hãy lưu hoặc hoàn tác trước.`, pageId: s.pageId }] : [];
  }), [drafts, doc, props.labelOf]); // eslint-disable-line react-hooks/exhaustive-deps
  const counts = { block: issues.filter((i) => i.severity === "BLOCK").length, warn: issues.filter((i) => i.severity === "WARN").length + unsaved.length };

  const ctx: DefCtx = useMemo(() => ({
    doc, readiness: backend.definitionOps as Readiness, canEdit: cap.canEdit && interactive, busy, siteVisibility: props.project.siteVisibility, metadata: backend.metadata, registry, labelOf: props.labelOf,
    dataManagement: props.dataManagement, canManageData: cap.canManageDataSources, manageDataReason: whyNot("canManageDataSources"),
    canViewData: cap.canViewDataSources, viewDataReason: whyNot("canViewDataSources"), canBindData: cap.canBindDataSources && interactive, bindDataReason: cap.canBindDataSources ? "Chế độ dùng thử hoặc chỉ-xem: không thể đổi liên kết." : whyNot("canBindDataSources"),
    commit: (ops, summary) => props.applyOps(ops, summary),
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }), [doc, backend, cap.canEdit, cap.canManageDataSources, cap.canViewDataSources, cap.canBindDataSources, interactive, busy, props.project.siteVisibility, registry, props.labelOf, props.applyOps, props.dataManagement]);

  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 4 } }), useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }));
  const collision: CollisionDetection = useCallback((args) => {
    const rowDrag = String(args.active.id).startsWith("row:");
    const only = args.droppableContainers.filter((c) => (rowDrag ? String(c.id).startsWith("row:") : c.id === "canvas"));
    return rowDrag ? closestCenter({ ...args, droppableContainers: only }) : rectIntersection({ ...args, droppableContainers: only });
  }, []);

  const select = props.onSelect;
  async function addComponent(componentId: string, at?: number) {
    const c = registry.find((x) => x.id === componentId);
    if (!c) return;
    const index = at ?? slotForClickAdd(sections, selectedId, c.id);
    const id = newId(c.id.toLowerCase());
    const plan = planAdd(sections, c.id, typeLabel(c.id, c.name), index, { id, props: defaultProps(c), pageId });
    if (await props.applyOps(plan.ops, plan.summary)) select(id);
  }
  async function moveSection(sectionId: string, slotIdx: number) {
    const plan = planMove(sections, sectionId, slotIdx);
    if (plan.kind === "move") await props.applyOps(plan.ops, `Di chuyển ${props.labelOf(sections.find((s) => s.id === sectionId)?.type ?? "")}`);
  }
  async function step(sectionId: string, delta: -1 | 1) {
    const plan = planStep(sections, sectionId, delta);
    if (plan.kind === "move") await props.applyOps(plan.ops, delta < 0 ? "Di chuyển mục lên" : "Di chuyển mục xuống");
  }

  function onDragStart(e: DragStartEvent) {
    const id = String(e.active.id);
    const kind = id.startsWith("lib:") ? "lib" : id.startsWith("sec:") ? "sec" : "row";
    const raw = id.replace(/^(lib|sec|row):/, "");
    const label = kind === "lib" ? typeLabel(raw, registry.find((c) => c.id === raw)?.name) : props.labelOf(sections.find((s) => s.id === raw)?.type ?? raw);
    setDrag({ kind, id: raw, label });
  }
  function onDragMove(e: DragMoveEvent) {
    if (!drag || drag.kind === "row") return;
    if (e.over?.id !== "canvas") { setSlot(null); return; }
    const y = pointerY(e);
    setSlot(y === null ? null : slotFromPoint(rects, y - e.over.rect.top));
  }
  async function onDragEnd(e: DragEndEvent) {
    const d = drag; const s = slot;
    setDrag(null); setSlot(null);
    if (!d || readOnly) return;
    if (d.kind === "row") {
      const over = e.over ? String(e.over.id).replace(/^row:/, "") : null;
      if (!over || over === d.id) return;
      const from = sections.findIndex((x) => x.id === d.id), to = sections.findIndex((x) => x.id === over);
      if (from >= 0 && to >= 0) await moveSection(d.id, to > from ? to + 1 : to);
      return;
    }
    if (e.over?.id !== "canvas" || s === null) return;
    const index = clampSlot(sections, sectionIndexForSlot(sections, rects, s), d.kind === "lib" ? d.id : sections.find((x) => x.id === d.id)?.type ?? "");
    if (d.kind === "lib") await addComponent(d.id, index);
    else await moveSection(d.id, index);
  }

  function publish() {
    if (counts.block) { setCheck(issues.filter((i) => i.severity === "BLOCK")); return; }
    if (counts.warn) { setCheck([...issues, ...unsaved]); return; }
    props.openPublish();
  }

  // M-003: the selection is NOT part of the document (it is posted to the frame), so selecting never rebuilds srcDoc; `edit` (not `interactive`) decides whether the frame runs our script
  const html = useMemo(() => props.renderPreview({ selectedId: null, interactive: edit, pageId }), [props.renderPreview, edit, pageId]); // eslint-disable-line react-hooks/exhaustive-deps
  const meta = `${props.latest ? `Phiên bản ${props.latest}` : "Chưa có phiên bản"} · revision ${props.revision} · ${props.project.siteVisibility === "PUBLIC" ? "Công khai" : "Riêng tư"}${readOnly ? " · chỉ xem" : ""}`;
  const shareReason = whyNot("canShare");
  const publishReason = whyNot("canPublish");

  const panelOf = (id: RailId): ReactNode => {
    switch (id) {
      case "pages": return <PagesPanel doc={doc} pageId={pageId} onPage={props.onPage} selectedId={selectedId} onSelect={select} labelOf={props.labelOf} summaryOf={props.summaryOf}
        canEdit={interactive} busy={busy} apply={(ops, summary) => props.applyOps(ops, summary)} lastFailure={props.lastFailure} genId={newId} onMoveSection={(id, d) => void step(id, d)}/>;
      case "components": return <ComponentsPanel registry={registry} blocks={props.blocks} canEdit={interactive} busy={busy} onAdd={(id) => void addComponent(id)} onAddBlock={props.addBlock}/>;
      case "data": return <DataPanel key={dataFocus.n ?? 0} ctx={ctx} focus={dataFocus}/>;
      case "forms": return <FormsPanel ctx={ctx} onSelect={(id, pg) => { props.onPage(pg); select(id); }} onNewAction={(id) => { setActionPreset({ sectionId: id }); openRail("actions"); }} openSite={props.openSite}/>;
      case "actions": return <ActionsPanel key={actionPreset?.sectionId ?? "list"} ctx={ctx} preset={actionPreset ? { type: "SUBMIT_FORM", sectionId: actionPreset.sectionId } : undefined}/>;
      case "workflows": return <WorkflowsPanel ctx={ctx}/>;
      case "theme": return <ThemePanel key={JSON.stringify((doc as { theme?: unknown }).theme ?? null)} ctx={ctx}/>;
      case "ai": return <AiPanel openAi={props.goAi} canEdit={cap.canEdit}/>;
    }
  };
  // M-037: a rail panel that has been opened STAYS mounted (React <Activity>: state kept, effects paused, rendered at low priority while hidden), so a half-filled
  // query / action / workflow / menu editor is not thrown away by looking at another tab. Panels that were never opened are not mounted at all (no extra requests).
  const [opened, setOpened] = useState<RailId[]>(["pages"]);
  const seen = opened.includes(rail) ? opened : [...opened, rail];
  if (seen !== opened && seen.length !== opened.length) setOpened(seen);
  const leftPanel = seen.map((id) => <Activity key={id} mode={id === rail ? "visible" : "hidden"}><div className="bx-rail-pane">{panelOf(id)}</div></Activity>);

  return (
    <DndContext sensors={sensors} collisionDetection={collision} accessibility={{ announcements, screenReaderInstructions }} onDragStart={onDragStart} onDragMove={onDragMove} onDragEnd={(e) => void onDragEnd(e)} onDragCancel={() => { setDrag(null); setSlot(null); }}>
      <BuilderTopBar name={props.project.name} meta={meta} save={props.save} appMode={appMode} onAppMode={setAppMode} device={props.device} onDevice={props.onDevice}
        leading={props.leading} modeTabs={props.modeTabs} trailing={props.trailing}
        canShare={cap.canShare} shareReason={shareReason} onShare={props.openMembers} canPublish={cap.canPublish && props.save.state !== "error"} publishReason={props.save.state === "error" ? "Có thay đổi chưa lưu được. Thử lưu lại trước khi xuất bản." : publishReason} publishBusy={busy} issues={counts} onPublish={publish} onRetrySave={props.onRetrySave}/>
      <main className="bx-body" data-mview={mview}>
        <a className="bx-skip" href="#mview-panel-canvas" onClick={(e) => { e.preventDefault(); setMview("canvas"); requestAnimationFrame(() => document.getElementById("mview-panel-canvas")?.focus()); }}>Bỏ qua tới bản xem trước</a>
        <div className="bx-mview">
          <Tabs label="Khu vực làm việc" idPrefix="mview" value={mview} onChange={(id) => setMview(id as "canvas" | "tools" | "props")}
            items={[{ id: "canvas", label: "Bản xem trước" }, { id: "tools", label: "Công cụ" }, { id: "props", label: edit ? "Thuộc tính" : "Kiểm thử", badge: edit && selected ? "●" : undefined, badgeLabel: "có mục đang chọn" }]}/>
        </div>
        <LeftRail id="mview-panel-tools" value={rail} onChange={setRail}><ErrorBoundary variant="inline" title="Công cụ này gặp sự cố" resetKeys={[rail]}>{leftPanel}</ErrorBoundary></LeftRail>
        <section className="bx-center" id="mview-panel-canvas" tabIndex={-1} aria-label="Bản xem trước ứng dụng">
          {!edit ? <p className="bx-banner" role="note">Đang ở chế độ dùng thử: bản xem trước không chỉnh sửa được.</p> : readOnly ? <p className="bx-banner" role="note">Bạn chỉ có quyền xem.</p> : null}
          <ErrorBoundary variant="inline" title="Bản xem trước gặp sự cố" resetKeys={[pageId]}>
            <Canvas document={html} sections={sections} selectedId={selectedId} onSelect={select} onRects={setRects} rects={rects} interactive={interactive} selectable={edit} dragging={!!drag && drag.kind !== "row"} slot={slot}
              device={props.device} labelOf={props.labelOf} frameRef={frameRef} title="Bản xem trước ứng dụng"/>
          
          </ErrorBoundary>
        </section>
        <aside ref={rightRef} id="mview-panel-props" className="bx-right" aria-label="Thuộc tính" {...(rightScrolls ? { tabIndex: 0 } : {})}>
          <ErrorBoundary variant="inline" title="Bảng thuộc tính gặp sự cố" resetKeys={[selectedId, edit]}>
          {!edit ? <TestPanel doc={doc} rawPermissions={props.project.permissions} runtime={props.runtime} dirty={props.save.state !== "saved" || busy}/>
            : selected ? (
              <Inspector ctx={ctx} drafts={drafts} onDraft={setDraft} section={selected} component={registry.find((c) => c.id === selected.type)} meta={backend.metadata.get(selected.type)} index={sections.indexOf(selected)} canUp={canStep(sections, selected.id, -1)} canDown={canStep(sections, selected.id, 1)} count={sections.length}
                readOnly={!interactive} busy={busy} assets={props.assets} rawPermissions={props.project.permissions} onApply={(ops, summary) => props.applyOps(ops, summary)} onClose={() => select(null)}
                onMove={(d) => void step(selected.id, d)} onRemove={() => setRemoving(true)} onSaveBlock={props.saveBlock} pageId={pageId}
                openDataWizard={(id, prop) => { setDataFocus({ sectionId: id, prop, n: Date.now() }); openRail("data"); }}/>
            ) : (
              <div className="bx-empty"><h2>Chưa chọn mục nào</h2>{readOnly ? <p>Bạn chỉ có quyền xem ứng dụng này. Chọn một mục trong “Trang” để xem thuộc tính; không chỉnh sửa được.</p> : <p>Chọn một mục trong “Trang” hoặc nhấp vào bản xem trước để chỉnh.</p>}
                {backend.metadataReadiness.state !== "AVAILABLE" ? <StateBox state={backend.metadataReadiness} compact/> : null}</div>)}
          </ErrorBoundary>
        </aside>
      </main>
      <DragOverlay>{drag ? <DragChip label={drag.label}/> : null}</DragOverlay>

      {removing && selected ? (
        <Dialog title={`Xóa “${props.labelOf(selected.type)}” khỏi trang?`} onClose={() => setRemoving(false)} footer={<>
          <button type="button" className="bx-btn" onClick={() => setRemoving(false)}>Hủy</button>
          <button type="button" className="bx-btn danger" disabled={busy} onClick={() => void props.applyOps([{ type: "REMOVE_SECTION", sectionId: selected.id }], "Xóa mục").then((ok) => { if (ok) { setRemoving(false); select(null); } })}>Xóa mục</button></>}>
          <p>Có thể khôi phục từ lịch sử phiên bản. Dữ liệu và hành động gắn với mục này sẽ bị hỏng cho tới khi bạn gỡ chúng.</p>
        </Dialog>) : null}
      {check ? (
        <Dialog title={check.some((i) => i.severity === "BLOCK") ? "Chưa thể xuất bản" : "Kiểm tra trước khi xuất bản"} onClose={() => setCheck(null)} footer={<>
          <button type="button" className="bx-btn" onClick={() => setCheck(null)}>Đóng</button>
          {blockers(check).length === 0 ? <button type="button" className="bx-btn primary" onClick={() => { setCheck(null); props.openPublish(); }}>Vẫn xuất bản</button> : null}</>}>
          <ul className="bx-issues" aria-label="Kết quả kiểm tra">{check.map((i) => <li key={i.code + (i.path ?? "") + i.message} className={i.severity === "BLOCK" ? "block" : "warn"}><b>{i.severity === "BLOCK" ? "Lỗi" : "Cảnh báo"}</b> {i.message}</li>)}</ul>
        </Dialog>) : null}
    </DndContext>
  );
}

