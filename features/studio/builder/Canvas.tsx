"use client";
/**
 * Centre column: the live preview (sandboxed iframe) with a DnD layer on top. While a drag is in progress a transparent shield covers the
 * iframe (an iframe swallows pointer events, so the drag would otherwise stop at its edge) and an insertion line shows where the drop lands.
 * Every section that has a rectangle gets a drag handle (Edit mode only); selection works by clicking inside the preview.
 */
import { memo, useCallback, useEffect, useMemo, useRef, useState, type MutableRefObject, type RefObject } from "react";
import { useDraggable, useDroppable } from "@dnd-kit/core";
import type { Section } from "@xweb/types";
import { indicatorY, scrollShift, type SectionRect } from "./core/dnd";

type Device = "desktop" | "tablet" | "mobile";

function validRects(raw: unknown, known: Set<string>): SectionRect[] {
  if (!Array.isArray(raw)) return [];
  return raw.filter((r): r is SectionRect => !!r && typeof r.id === "string" && known.has(r.id) && Number.isFinite(r.top) && Number.isFinite(r.height));
}

export function Canvas({ document: html, sections, selectedId, onSelect, rectsRef, interactive, selectable = interactive, dragging, slot, device, labelOf, frameRef, title }: {
  document: string; sections: Section[]; selectedId: string | null; onSelect: (id: string) => void;
  /** M-046: the CURRENT section rectangles (viewport of the frame), written on every layout message without a render; the host reads it while dragging */
  rectsRef: MutableRefObject<SectionRect[]>;
  interactive: boolean;
  /** the preview runs our own select/layout script (editing, also read-only). The selection is POSTED to it (M-003), never part of `document`, so selecting does not reload the frame. */
  selectable?: boolean; dragging: boolean; slot: number | null; device: Device; labelOf: (type: string) => string; frameRef: RefObject<HTMLIFrameElement | null>; title: string;
}) {
  const { setNodeRef } = useDroppable({ id: "canvas", disabled: !interactive });
  const postSelection = useCallback(() => { if (selectable) frameRef.current?.contentWindow?.postMessage({ type: "studio:selected", sectionId: selectedId }, "*"); }, [selectable, selectedId, frameRef]);
  useEffect(postSelection, [postSelection, html]);   // a selection change or a new document (the frame reloads): (re)apply the highlight without touching srcDoc
  // M-046: `base` is the layout the handles were rendered for; a pure scroll (every rectangle moved by one offset) only moves the handle
  // track with a transform, so scrolling the preview no longer re-renders the Builder (measured: 25 commits / 1455 ms -> see the commit).
  const [base, setBase] = useState<SectionRect[]>([]);
  const baseRef = useRef<SectionRect[]>([]);
  const trackRef = useRef<HTMLDivElement>(null);
  const gutterRef = useRef<HTMLDivElement>(null);
  const shiftRef = useRef(0);
  /** A handle scrolled out of the (clipped) gutter is invisible, so it must not be focusable either (WCAG 2.4.7 / 2.4.11): `inert` takes it out of the Tab order and the accessibility tree. Pure DOM
   *  (no React render, so M-046's "a scroll does not re-render the Builder" holds). The keyboard alternative to dragging is always there: Inspector "↑ Lên / ↓ Xuống" and the page list's arrows. */
  const syncReach = useCallback(() => {
    const gutter = gutterRef.current, track = trackRef.current; if (!gutter || !track) return;
    const h = gutter.clientHeight, shift = shiftRef.current;
    for (const el of Array.from(track.children) as HTMLElement[]) {
      const centre = (parseFloat(el.style.top) || 0) + shift + el.offsetHeight / 2;
      const reachable = centre >= 0 && centre <= h;
      if (el.inert === reachable) el.inert = !reachable;
    }
  }, []);
  useEffect(() => {
    const known = new Set(sections.map((s) => s.id));
    const onMessage = (e: MessageEvent) => {
      if (e.source !== frameRef.current?.contentWindow) return;
      const d = e.data as { type?: unknown; sectionId?: unknown; rects?: unknown };
      if (d?.type === "studio:select" && typeof d.sectionId === "string" && known.has(d.sectionId)) onSelect(d.sectionId);
      else if (d?.type === "studio:layout") {
        const next = validRects(d.rects, known);
        rectsRef.current = next;
        const shift = scrollShift(baseRef.current, next);
        if (trackRef.current) trackRef.current.style.transform = shift ? `translateY(${shift}px)` : "";
        shiftRef.current = shift ?? 0;
        if (shift === null) { baseRef.current = next; setBase(next); }
        syncReach();
      }
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [sections, frameRef, onSelect, rectsRef, syncReach]);
  useEffect(syncReach, [syncReach, base, interactive, dragging, device]);   // new handles / a new layout: judge them against the current scroll
  const byId = useMemo(() => new Map(sections.map((s) => [s.id, s])), [sections]);
  const rects = rectsRef.current;

  return (
    <div className={`canvasViewport viewport-${device}`}>
      <div className="bx-canvas">
        <div className="bx-frame">
          {/* AI/Test: no scripts. Edit: our own select/layout script only; still no same-origin, forms, popups or top navigation. */}
          <iframe ref={frameRef} className="previewFrame" title={title} sandbox={selectable ? "allow-scripts" : ""} srcDoc={html} onLoad={postSelection}/>
          <div ref={setNodeRef} className={`bx-shield${dragging ? " on" : ""}`} data-testid="canvas-drop">
            {dragging ? (slot === null
              ? null
              : <div className="bx-insert" style={{ top: indicatorY(rects, slot) }} aria-hidden="true"><span>Thả vào đây</span></div>) : null}
            {dragging && !rects.length ? <div className="bx-empty-drop">Thả component vào trang trống</div> : null}
          </div>
        </div>
        {/* Drag handles live in a gutter NEXT TO the preview, never over the iframe: a press must not be routed into the sandboxed frame. */}
        <div ref={gutterRef} className="bx-gutter" role="group" aria-label="Tay nắm kéo các phần của trang">
          <div ref={trackRef} className="bx-gutter-track">
            {interactive && !dragging ? base.map((r) => {
              const s = byId.get(r.id);
              return s ? <Handle key={r.id} id={r.id} top={r.top} label={labelOf(s.type)} active={r.id === selectedId}/> : null;
            }) : null}
          </div>
        </div>
      </div>
    </div>
  );
}

/** memoised (M-110): selecting a section re-renders only the two handles whose `active` changed */
const Handle = memo(function Handle({ id, top, label, active }: { id: string; top: number; label: string; active: boolean }) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: `sec:${id}` });
  return (
    <button ref={setNodeRef} type="button" className={`bx-handle${active ? " active" : ""}`} style={{ top: top + 6, opacity: isDragging ? 0.4 : undefined }}
      aria-label={`Kéo để di chuyển ${label}`} title={`Kéo để di chuyển ${label}`} {...attributes} {...listeners}>⋮⋮</button>
  );
});

/** the dragged item as shown under the pointer */
export function DragChip({ label }: { label: string }) { return <div className="bx-dragchip">{label}</div>; }
