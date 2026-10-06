"use client";
/**
 * Pages: the page tree (ARIA tree: pages, with the sections of the open page as children), create / rename / delete with dialogs, route and 404,
 * the navigation menu, and the list of routes that would break a publish. Set-home and page reorder have NO operation in the contract:
 * they are shown as "Chưa sẵn sàng" with the reason (the menu order, which does have an operation, is editable).
 */
import { useMemo, useRef, useState, type KeyboardEvent } from "react";
import { SortableContext, useSortable, verticalListSortingStrategy } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import type { AppDefinitionV2, ActionDef, NavLink, Section } from "@xweb/types";
import { MAX_NAV, HOME_ID, addPageToMenu, checkSlug, listPages, moveNavLink, opsAddPage, opsRemovePage, opsRenamePage, opsSetNavigation, pageExists, removeImpact, reorderPagesReadiness, resolveRoute, sectionsOf, setHomeReadiness, slugify } from "../core/pages";
import { preflight } from "../core/preflight";
import { Dialog, Field, StateBox } from "../ui/primitives";
import type { SchemaOperation } from "@xweb/types";

type Apply = (ops: SchemaOperation[], summary: string) => Promise<boolean>;

export function PagesPanel({ doc, pageId, onPage, selectedId, onSelect, labelOf, summaryOf, canEdit, busy, apply, genId, onMoveSection }: {
  doc: AppDefinitionV2; pageId: string; onPage: (id: string) => void; selectedId: string | null; onSelect: (id: string | null) => void;
  labelOf: (type: string) => string; summaryOf: (s: Section) => string; canEdit: boolean; busy: boolean; apply: Apply; genId: (prefix: string) => string;
  onMoveSection: (sectionId: string, delta: -1 | 1) => void;
}) {
  const pages = useMemo(() => listPages(doc), [doc]);
  const current = pages.find((p) => p.id === pageId) ?? pages[0];
  const sections = sectionsOf(doc, current.id);
  const routeIssues = useMemo(() => preflight(doc).filter((i) => i.code.startsWith("ROUTE_") || i.code === "NAV_TOO_MANY"), [doc]);
  const [dialog, setDialog] = useState<null | "add" | "rename" | "remove">(null);
  const disabled = !canEdit || busy;
  const treeRef = useRef<HTMLUListElement>(null);

  function onTreeKey(e: KeyboardEvent<HTMLUListElement>) {
    const items = Array.from(treeRef.current?.querySelectorAll<HTMLElement>('[role="treeitem"]') ?? []);
    const i = items.findIndex((x) => x === document.activeElement);
    let to = -1;
    if (e.key === "ArrowDown") to = Math.min(items.length - 1, i + 1); else if (e.key === "ArrowUp") to = Math.max(0, i - 1);
    else if (e.key === "Home") to = 0; else if (e.key === "End") to = items.length - 1;
    if (to < 0) return;
    e.preventDefault(); items[to]?.focus();
  }

  return (
    <div className="bx-panel-body">
      <div className="bx-panel-head"><h2>Trang</h2>
        {canEdit ? <button type="button" className="bx-btn sm" disabled={busy} onClick={() => setDialog("add")}>＋ Trang</button> : null}</div>

      {routeIssues.length ? (
        <div className="bx-alert" role="alert"><b>{routeIssues.length} đường dẫn sẽ chặn xuất bản</b>
          <ul>{routeIssues.slice(0, 4).map((i) => <li key={i.code + (i.path ?? "")}>{i.message}</li>)}</ul></div>
      ) : <p className="hint" role="status">Mọi đường dẫn hợp lệ.</p>}

      <ul ref={treeRef} role="tree" aria-label="Cấu trúc ứng dụng" className="bx-tree" onKeyDown={onTreeKey}>
        {pages.map((p) => {
          const open = p.id === current.id;
          return (
            <li role="none" key={p.id}>
              <div role="treeitem" aria-level={1} aria-selected={open} aria-expanded={open} tabIndex={open && !selectedId ? 0 : -1} className={`bx-node page${open ? " active" : ""}`}
                onClick={() => { onPage(p.id); onSelect(null); }}
                onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onPage(p.id); onSelect(null); } }}>
                <b>{p.title}</b>{p.home ? <span className="chip">Trang chủ</span> : null}<code>{p.route}</code>
                {p.navIndex !== null ? <small>menu #{p.navIndex + 1}</small> : null}
              </div>
              {open ? (
                <SortableContext items={sections.map((s) => `row:${s.id}`)} strategy={verticalListSortingStrategy}>
                  <ul role="group" aria-label={`Các phần của trang ${p.title}`} className="bx-children">
                    {sections.length === 0 ? <li role="none" className="hint">Trang trống. Kéo một component vào, hoặc nhấn vào component trong mục “Thành phần”.</li> : null}
                    {sections.map((s, i) => (
                      <SectionNode key={s.id} section={s} title={labelOf(s.type)} summary={summaryOf(s)} active={s.id === selectedId} canEdit={canEdit} busy={busy}
                        first={i === 0} last={i === sections.length - 1} onSelect={() => onSelect(s.id === selectedId ? null : s.id)} onMove={(d) => onMoveSection(s.id, d)}/>))}
                  </ul>
                </SortableContext>) : null}
            </li>
          );
        })}
      </ul>

      <div className="bx-page-actions" role="group" aria-label={`Thao tác với trang ${current.title}`}>
        <button type="button" className="bx-btn sm" disabled={disabled} onClick={() => setDialog("rename")}>Đổi tên / đường dẫn</button>
        <button type="button" className="bx-btn sm danger" disabled={disabled || current.home} title={current.home ? "Không thể xoá trang chủ" : undefined} onClick={() => setDialog("remove")}>Xóa trang</button>
      </div>
      {!current.home ? (<div className="bx-notready-row"><button type="button" className="bx-btn sm" disabled>Đặt làm trang chủ</button><StateBox state={setHomeReadiness()} compact/></div>) : null}
      <div className="bx-notready-row"><StateBox state={reorderPagesReadiness()} compact/></div>

      <MenuEditor doc={doc} pages={pages} canEdit={canEdit} busy={busy} apply={apply} genId={genId}/>
      <NotFoundEditor doc={doc} canEdit={canEdit} busy={busy} apply={apply}/>

      {dialog === "add" ? <AddDialog doc={doc} onClose={() => setDialog(null)} onSubmit={async (title) => {
        const id = genId("p"); const r = opsAddPage(doc, title, id);
        if ("error" in r) return r.error;
        if (await apply(r.ops, r.summary)) { onPage(id); setDialog(null); return null; }
        return "Không lưu được. Hãy thử lại.";
      }}/> : null}
      {dialog === "rename" ? <RenameDialog doc={doc} pageId={current.id} title={current.title} slug={current.slug} onClose={() => setDialog(null)} onSubmit={async (t, s) => {
        const r = opsRenamePage(doc, current.id, t, current.home ? undefined : s);
        if ("error" in r) return r.error;
        if (await apply(r.ops, r.summary)) { setDialog(null); return null; }
        return "Không lưu được. Hãy thử lại.";
      }}/> : null}
      {dialog === "remove" ? <RemoveDialog doc={doc} pageId={current.id} actions={doc.actions ?? []} onClose={() => setDialog(null)} onConfirm={async () => {
        const r = opsRemovePage(doc, current.id);
        if ("error" in r) return r.error;
        if (await apply(r.ops, r.summary)) { onPage(HOME_ID); setDialog(null); return null; }
        return "Không lưu được. Hãy thử lại.";
      }}/> : null}
    </div>
  );
}

function SectionNode({ section, title, summary, active, canEdit, busy, first, last, onSelect, onMove }: {
  section: Section; title: string; summary: string; active: boolean; canEdit: boolean; busy: boolean; first: boolean; last: boolean; onSelect: () => void; onMove: (d: -1 | 1) => void;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: `row:${section.id}`, disabled: !canEdit || busy });
  return (
    <li role="none" ref={setNodeRef} style={{ transform: CSS.Transform.toString(transform), transition, opacity: isDragging ? 0.6 : 1 }} className="bx-sortrow">
      {canEdit ? <button type="button" className="bx-drag" aria-label={`Kéo để di chuyển ${title}`} title={`Kéo để di chuyển ${title}`} {...attributes} {...listeners}>⋮⋮</button> : null}
      <div role="treeitem" aria-level={2} aria-selected={active} tabIndex={active ? 0 : -1} className={`bx-node section${active ? " active" : ""}`} onClick={onSelect}
        onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onSelect(); } }}>
        <b>{title}</b><span>{summary || section.id}</span></div>
      {canEdit ? <span className="bx-row-tools">
        <button type="button" className="bx-mini" aria-label={`Đưa ${title} lên`} disabled={busy || first} onClick={() => onMove(-1)}>↑</button>
        <button type="button" className="bx-mini" aria-label={`Đưa ${title} xuống`} disabled={busy || last} onClick={() => onMove(1)}>↓</button></span> : null}
    </li>
  );
}

function AddDialog({ doc, onClose, onSubmit }: { doc: AppDefinitionV2; onClose: () => void; onSubmit: (title: string) => Promise<string | null> }) {
  const [title, setTitle] = useState(""); const [err, setErr] = useState<string | null>(null);
  const slug = slugify(title);
  return (
    <Dialog title="Thêm trang" onClose={onClose} footer={<><button type="button" className="bx-btn" onClick={onClose}>Hủy</button>
      <button type="submit" form="add-page-form" className="bx-btn primary" disabled={!title.trim()}>Thêm trang</button></>}>
      <form id="add-page-form" onSubmit={(e) => { e.preventDefault(); void onSubmit(title).then(setErr); }}>
        <Field label="Tên trang">{(id) => <input id={id} data-autofocus value={title} maxLength={80} onChange={(e) => setTitle(e.target.value)}/>}</Field>
        <p className="hint">Đường dẫn dự kiến: <code>/{slug || "…"}/</code>{(doc.pages ?? []).length >= 20 ? " · đã đủ 20 trang" : ""}</p>
        {err ? <p className="formError" role="alert">{err}</p> : null}
      </form>
    </Dialog>
  );
}

function RenameDialog({ doc, pageId, title: t0, slug: s0, onClose, onSubmit }: { doc: AppDefinitionV2; pageId: string; title: string; slug: string; onClose: () => void; onSubmit: (title: string, slug: string) => Promise<string | null> }) {
  const [title, setTitle] = useState(t0); const [slug, setSlug] = useState(s0); const [err, setErr] = useState<string | null>(null);
  const home = pageId === HOME_ID;
  const check = home ? { ok: true as const } : checkSlug(doc, slug, pageId);
  return (
    <Dialog title="Đổi tên và đường dẫn" onClose={onClose} footer={<><button type="button" className="bx-btn" onClick={onClose}>Hủy</button>
      <button type="submit" form="rename-page-form" className="bx-btn primary" disabled={!title.trim() || !check.ok}>Lưu</button></>}>
      <form id="rename-page-form" onSubmit={(e) => { e.preventDefault(); void onSubmit(title, slug).then(setErr); }}>
        <Field label="Tên trang">{(id) => <input id={id} data-autofocus value={title} maxLength={80} onChange={(e) => setTitle(e.target.value)}/>}</Field>
        {home ? <p className="hint">Đường dẫn của trang chủ luôn là <code>/</code>.</p> : (
          <Field label="Đường dẫn" hint={check.ok ? `Địa chỉ: /${slug}/` : check.reason}>{(id) => <input id={id} value={slug} maxLength={60} aria-invalid={!check.ok} onChange={(e) => setSlug(slugify(e.target.value))}/>}</Field>)}
        {err ? <p className="formError" role="alert">{err}</p> : null}
      </form>
    </Dialog>
  );
}

function RemoveDialog({ doc, pageId, actions, onClose, onConfirm }: { doc: AppDefinitionV2; pageId: string; actions: ActionDef[]; onClose: () => void; onConfirm: () => Promise<string | null> }) {
  const [err, setErr] = useState<string | null>(null);
  const impact = removeImpact(doc, pageId, actions);
  return (
    <Dialog title={`Xóa trang “${impact?.pageTitle ?? ""}”?`} onClose={onClose} footer={<><button type="button" className="bx-btn" onClick={onClose}>Hủy</button>
      <button type="button" className="bx-btn danger" onClick={() => void onConfirm().then(setErr)}>Xóa trang</button></>}>
      <p>{impact?.message}</p>
      <p className="hint">Có thể khôi phục từ lịch sử phiên bản.</p>
      {err ? <p className="formError" role="alert">{err}</p> : null}
    </Dialog>
  );
}

function MenuEditor({ doc, pages, canEdit, busy, apply, genId }: { doc: AppDefinitionV2; pages: ReturnType<typeof listPages>; canEdit: boolean; busy: boolean; apply: Apply; genId: (p: string) => string }) {
  const [links, setLinks] = useState<NavLink[]>(doc.site?.navigation ?? []);
  const [err, setErr] = useState<string | null>(null);
  const dirty = JSON.stringify(links) !== JSON.stringify(doc.site?.navigation ?? []);
  const disabled = !canEdit || busy;
  const notInMenu = pages.filter((p) => !links.some((l) => l.pageId === p.id));
  return (
    <section className="bx-section" aria-labelledby="menu-h">
      <h3 id="menu-h">Menu điều hướng</h3>
      <p className="hint">Thứ tự menu đổi được ngay; thứ tự trang trong tài liệu thì chưa.</p>
      <ol className="bx-menu">
        {links.map((l, i) => {
          const broken = l.pageId !== undefined && !pageExists(doc, l.pageId);
          return (
            <li key={l.id} className={broken ? "broken" : ""}>
              <input aria-label={`Nhãn liên kết ${i + 1}`} value={l.label} maxLength={40} disabled={disabled} onChange={(e) => setLinks(links.map((x, j) => (j === i ? { ...x, label: e.target.value } : x)))}/>
              <small>{l.pageId !== undefined ? (broken ? "trang đã xoá" : `trang: ${pages.find((p) => p.id === l.pageId)?.title ?? l.pageId}`) : l.url ? "liên kết ngoài" : `phần ${l.anchor}`}</small>
              {!disabled ? <span className="bx-row-tools">
                <button type="button" className="bx-mini" aria-label={`Đưa liên kết ${l.label} lên`} disabled={i === 0} onClick={() => setLinks(moveNavLink(links, i, i - 1))}>↑</button>
                <button type="button" className="bx-mini" aria-label={`Đưa liên kết ${l.label} xuống`} disabled={i === links.length - 1} onClick={() => setLinks(moveNavLink(links, i, i + 1))}>↓</button>
                <button type="button" className="bx-mini danger" aria-label={`Xóa liên kết ${l.label}`} onClick={() => setLinks(links.filter((_, j) => j !== i))}>✕</button></span> : null}
            </li>);
        })}
      </ol>
      {!disabled && notInMenu.length && links.length < MAX_NAV ? (
        <select aria-label="Thêm trang vào menu" value="" onChange={(e) => { const p = pages.find((x) => x.id === e.target.value); if (p) setLinks(addPageToMenu(links, p, genId("n"))); }}>
          <option value="">+ Thêm trang vào menu…</option>{notInMenu.map((p) => <option key={p.id} value={p.id}>{p.title}</option>)}</select>) : null}
      {err ? <p className="formError" role="alert">{err}</p> : null}
      {canEdit ? <div className="bx-actions"><button type="button" className="bx-btn primary sm" disabled={disabled || !dirty} onClick={() => { const r = opsSetNavigation(links); if ("error" in r) setErr(r.error); else { setErr(null); void apply(r.ops, r.summary); } }}>Lưu menu</button></div> : null}
    </section>
  );
}

function NotFoundEditor({ doc, canEdit, busy, apply }: { doc: AppDefinitionV2; canEdit: boolean; busy: boolean; apply: Apply }) {
  const nf = doc.site?.notFound;
  const [title, setTitle] = useState(nf?.title ?? ""); const [message, setMessage] = useState(nf?.message ?? "");
  const shown = resolveRoute({ ...doc, site: { ...doc.site, notFound: { title: title.trim() || undefined, message: message.trim() || undefined } } } as AppDefinitionV2, "/khong-ton-tai/");
  const disabled = !canEdit || busy;
  return (
    <section className="bx-section" aria-labelledby="nf-h">
      <h3 id="nf-h">Trang 404</h3>
      <Field label="Tiêu đề">{(id) => <input id={id} value={title} maxLength={80} placeholder="Không tìm thấy trang" disabled={disabled} onChange={(e) => setTitle(e.target.value)}/>}</Field>
      <Field label="Lời nhắn">{(id) => <input id={id} value={message} maxLength={300} placeholder="Trang bạn tìm không tồn tại…" disabled={disabled} onChange={(e) => setMessage(e.target.value)}/>}</Field>
      {shown.kind === "notfound" ? <div className="bx-nf-preview" aria-label="Xem trước trang 404"><b>{shown.title}</b><p>{shown.message}</p></div> : null}
      {canEdit ? <div className="bx-actions"><button type="button" className="bx-btn sm" disabled={disabled}
        onClick={() => void apply([{ type: "UPDATE_SITE", props: { notFound: { ...(title.trim() ? { title: title.trim() } : {}), ...(message.trim() ? { message: message.trim() } : {}) } } }], "Cập nhật trang 404")}>Lưu trang 404</button></div> : null}
    </section>
  );
}
