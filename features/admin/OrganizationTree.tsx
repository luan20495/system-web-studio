"use client";
/**
 * The organization tree: a flat WAI-ARIA tree of company-defined units (no fixed levels), windowed above WINDOW_ABOVE visible rows. Presentational and side-effect free: it only reports selection / open state.
 * Each row shows BOTH counts with their own words (unitRowSummary: "N trực tiếp" = members of exactly that unit, "M cả nhánh" = distinct employees of the branch), never one number called "employees".
 */
import { memo, useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { ChevronRight } from "@xweb/ui";
import type { OrgUnitType } from "./organization";
import { flattenTree, unitRowSummary, type TreeNode } from "./organizationModel";
import { UnitIcon } from "./unitIcons";

const INDENT_PX = 18; const MAX_INDENT_LEVELS = 10;
/** above this many VISIBLE rows only the rows in (and just around) the scrollport are in the DOM; every row keeps its aria-level / posinset / setsize, so the tree is still announced whole (M-111) */
export const WINDOW_ABOVE = 1000;
/** one row = the 48px node + the 2px gap of the grid; fixed so the window can be computed from scrollTop without measuring rows */
export const ROW_H = 50; const OVERSCAN = 10;
/** One row. `memo`: a row re-renders only when ITS props change (selected / open / tabbable / its unit), so selecting a node in a 2 000-node tree re-renders two rows, not 2 000. */
const TreeRow = memo(function TreeRow({ n, type, selected, open, tabbable, fixedH, onSelect, onToggle, onFocusRow }: { n: TreeNode; type: OrgUnitType | undefined; selected: boolean; open: boolean; tabbable: boolean; fixedH?: number; onSelect: (id: string) => void; onToggle: (id: string, v: boolean) => void; onFocusRow: (id: string) => void }) {
  const u = n.unit; const has = n.children.length > 0;
  const counts = unitRowSummary(u);
  return (
    <li role="treeitem" aria-level={n.depth + 1} aria-setsize={n.size} aria-posinset={n.pos} aria-expanded={has ? open : undefined} aria-selected={selected} style={fixedH ? { height: fixedH, overflow: "hidden" } : undefined}>
      <div className={`xp-node${selected ? " sel" : ""}${u.active ? "" : " off"}`} data-node={u.id} data-testid={`node:${u.id}`} tabIndex={tabbable ? 0 : -1} onClick={() => { onSelect(u.id); onFocusRow(u.id); }} onFocus={() => onFocusRow(u.id)} style={{ paddingLeft: 8 + Math.min(n.depth, MAX_INDENT_LEVELS) * INDENT_PX }}>
        <span className={`xp-chev${has ? "" : " leaf"}`} aria-hidden="true" onClick={(e) => { e.stopPropagation(); if (has) onToggle(u.id, !open); }}><ChevronRight size={16} style={{ transform: open ? "rotate(90deg)" : undefined }}/></span>
        <span className="xp-nodeIcon"><UnitIcon id={type?.icon}/></span>
        <span className="xp-nodeText"><b title={u.name}>{u.name}</b>{counts ? <small data-testid={`counts:${u.id}`} title="Trực tiếp = thành viên đúng đơn vị này. Cả nhánh = nhân viên khác nhau trong đơn vị này và các đơn vị con.">{counts}</small> : null}</span>
        {n.depth > MAX_INDENT_LEVELS ? <span className="xp-depthTag" title={`Cấp ${n.depth + 1}`}>C{n.depth + 1}</span> : null}
        {type ? <span className="xp-typeBadge">{type.name}</span> : null}
        {!u.active ? <span className="pill pill-muted" data-testid={`archived:${u.id}`}>Đã lưu trữ</span> : null}
        {n.orphan ? <span className="pill pill-warn" title="Không tìm thấy đơn vị cha trong dữ liệu">Mồ côi</span> : null}
      </div>
    </li>);
});

/**
 * A flat WAI-ARIA tree (role=tree, treeitems with aria-level / aria-setsize / aria-posinset): only the VISIBLE rows exist in the DOM (a collapsed node renders none of its children), the rows are memoised,
 * and indentation is capped so a very deep chain does not push the text out of the card (a "C<n>" tag shows the real level). Keyboard: ↑ ↓ → ← Home End Enter.
 * Above WINDOW_ABOVE visible rows (an expanded 2 000-unit tree) the rows are WINDOWED: fixed-height rows, two spacers, only the rows in view +/- OVERSCAN are mounted (26 073 DOM nodes -> a few hundred).
 * The keyboard still reaches every row (the list scrolls to it first); when the tab-stop row is out of the window the list itself takes the Tab stop and hands the focus to that row.
 */
export function Tree({ nodes, types, selected, open, onSelect, onOpen }: { nodes: TreeNode[]; types: OrgUnitType[]; selected: string | null; open: Set<string>; onSelect: (id: string) => void; onOpen: (id: string, v: boolean) => void }) {
  const visible = useMemo(() => flattenTree(nodes, open), [nodes, open]);
  const indexOf = useMemo(() => new Map(visible.map((n, i) => [n.unit.id, i])), [visible]);
  const typeById = useMemo(() => new Map(types.map((t) => [t.id, t])), [types]);
  const [focus, setFocus] = useState<string | null>(null); const root = useRef<HTMLUListElement>(null);
  const cur = focus && indexOf.has(focus) ? focus : selected && indexOf.has(selected) ? selected : visible[0]?.unit.id ?? null;
  const windowed = visible.length > WINDOW_ABOVE;
  const [view, setView] = useState({ top: 0, h: 720 }); const frame = useRef(0);
  useEffect(() => {
    const el = root.current; if (!windowed || !el) return;
    const measure = () => setView((v) => (el.clientHeight && el.clientHeight !== v.h ? { ...v, h: el.clientHeight } : v));
    measure(); window.addEventListener("resize", measure); return () => window.removeEventListener("resize", measure);
  }, [windowed]);
  useEffect(() => () => cancelAnimationFrame(frame.current), []);
  const onScroll = (e: React.UIEvent<HTMLUListElement>) => {
    if (!windowed) return; const top = e.currentTarget.scrollTop; cancelAnimationFrame(frame.current);
    frame.current = requestAnimationFrame(() => setView((v) => (Math.abs(v.top - top) < 1 ? v : { ...v, top })));
  };
  const first = windowed ? Math.max(0, Math.floor(view.top / ROW_H) - OVERSCAN) : 0;
  const last = windowed ? Math.min(visible.length, Math.ceil((view.top + view.h) / ROW_H) + OVERSCAN) : visible.length;
  /** scroll the list so row `i` is inside the scrollport (windowed only); returns the new top */
  const reveal = useCallback((i: number) => {
    const el = root.current; if (!el) return; const h = el.clientHeight || 720; let top = el.scrollTop;
    if (i * ROW_H < top) top = i * ROW_H; else if ((i + 1) * ROW_H > top + h) top = (i + 1) * ROW_H - h; else return;
    el.scrollTop = top; setView((v) => ({ ...v, top, h }));
  }, []);
  const focusRow = useCallback((id: string, tries = 4) => {
    const el = root.current?.querySelector<HTMLElement>(`[data-node="${CSS.escape(id)}"]`);
    if (el) el.focus(); else if (tries > 0) requestAnimationFrame(() => focusRow(id, tries - 1));
  }, []);
  const go = useCallback((id: string | undefined) => {
    if (!id) return; setFocus(id);
    if (windowed) { const i = indexOf.get(id); if (i !== undefined) reveal(i); }
    requestAnimationFrame(() => focusRow(id));
  }, [windowed, indexOf, reveal, focusRow]);
  // the selection moved by something other than a click (a new unit, a reload): bring it into the window
  useEffect(() => { if (windowed && selected) { const i = indexOf.get(selected); if (i !== undefined && (i < first || i >= last)) reveal(i); } }, [selected]); // eslint-disable-line react-hooks/exhaustive-deps
  const onFocusRow = useCallback((id: string) => setFocus(id), []);
  const curIndex = cur ? indexOf.get(cur) ?? -1 : -1;
  const curMounted = curIndex >= first && curIndex < last;
  function onKey(e: KeyboardEvent) {
    const i = curIndex; const n = visible[i]; if (!n) return;
    const has = n.children.length > 0; const isOpen = open.has(n.unit.id);
    if (e.key === "ArrowDown") { e.preventDefault(); go(visible[Math.min(visible.length - 1, i + 1)]?.unit.id); }
    else if (e.key === "ArrowUp") { e.preventDefault(); go(visible[Math.max(0, i - 1)]?.unit.id); }
    else if (e.key === "Home") { e.preventDefault(); go(visible[0]?.unit.id); } else if (e.key === "End") { e.preventDefault(); go(visible[visible.length - 1]?.unit.id); }
    else if (e.key === "ArrowRight") { e.preventDefault(); if (has && !isOpen) onOpen(n.unit.id, true); else if (has) go(n.children[0].unit.id); }
    else if (e.key === "ArrowLeft") { e.preventDefault(); if (has && isOpen) onOpen(n.unit.id, false); else { let j = i - 1; while (j >= 0 && visible[j].depth >= n.depth) j--; go(visible[j]?.unit.id); } }
    else if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onSelect(n.unit.id); }
  }
  const rows = windowed ? visible.slice(first, last) : visible;
  return (
    <ul className="xp-tree" role="tree" aria-label="Cơ cấu tổ chức" data-testid="org-tree" ref={root} onKeyDown={onKey} onScroll={onScroll}
      tabIndex={windowed && !curMounted ? 0 : undefined} onFocus={(e) => { if (e.target === e.currentTarget && cur) go(cur); }} style={windowed ? { display: "block" } : undefined}
      data-windowed={windowed ? "true" : undefined} data-rows={windowed ? `${rows.length}/${visible.length}` : undefined}>
      {windowed && first > 0 ? <li role="presentation" aria-hidden="true" style={{ height: first * ROW_H }}/> : null}
      {rows.map((n) => <TreeRow key={n.unit.id} n={n} type={n.unit.typeId ? typeById.get(n.unit.typeId) : undefined} selected={selected === n.unit.id} open={open.has(n.unit.id)} tabbable={cur === n.unit.id} fixedH={windowed ? ROW_H : undefined} onSelect={onSelect} onToggle={onOpen} onFocusRow={onFocusRow}/>)}
      {windowed && last < visible.length ? <li role="presentation" aria-hidden="true" style={{ height: (visible.length - last) * ROW_H }}/> : null}
    </ul>
  );
}
