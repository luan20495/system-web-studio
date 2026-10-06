"use client";
/**
 * Centre column: the live preview (sandboxed iframe) with a DnD layer on top. While a drag is in progress a transparent shield covers the
 * iframe (an iframe swallows pointer events, so the drag would otherwise stop at its edge) and an insertion line shows where the drop lands.
 * Every section that has a rectangle gets a drag handle (Edit mode only); selection works by clicking inside the preview.
 */
import { useEffect, useState, type RefObject } from "react";
import { useDraggable, useDroppable } from "@dnd-kit/core";
import type { Section } from "@xweb/types";
import { indicatorY, type SectionRect } from "./core/dnd";

type Device = "desktop" | "tablet" | "mobile";

function validRects(raw: unknown, known: Set<string>): SectionRect[] {
  if (!Array.isArray(raw)) return [];
  return raw.filter((r): r is SectionRect => !!r && typeof r.id === "string" && known.has(r.id) && Number.isFinite(r.top) && Number.isFinite(r.height));
}

export function Canvas({ document: html, sections, selectedId, onSelect, onRects, rects, interactive, dragging, slot, device, labelOf, frameRef, title }: {
  document: string; sections: Section[]; selectedId: string | null; onSelect: (id: string) => void; onRects: (r: SectionRect[]) => void; rects: SectionRect[];
  interactive: boolean; dragging: boolean; slot: number | null; device: Device; labelOf: (type: string) => string; frameRef: RefObject<HTMLIFrameElement | null>; title: string;
}) {
  const { setNodeRef } = useDroppable({ id: "canvas", disabled: !interactive });
  useEffect(() => {
    const known = new Set(sections.map((s) => s.id));
    const onMessage = (e: MessageEvent) => {
      if (e.source !== frameRef.current?.contentWindow) return;
      const d = e.data as { type?: unknown; sectionId?: unknown; rects?: unknown };
      if (d?.type === "studio:select" && typeof d.sectionId === "string" && known.has(d.sectionId)) onSelect(d.sectionId);
      else if (d?.type === "studio:layout") onRects(validRects(d.rects, known));
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [sections, frameRef, onSelect, onRects]);

  return (
    <div className={`canvasViewport viewport-${device}`}>
      <div className="bx-canvas">
        {/* AI/Test: no scripts. Edit: our own select/layout script only; still no same-origin, forms, popups or top navigation. */}
        <iframe ref={frameRef} className="previewFrame" title={title} sandbox={interactive ? "allow-scripts" : ""} srcDoc={html}/>
        <div ref={setNodeRef} className={`bx-shield${dragging ? " on" : ""}`} data-testid="canvas-drop">
          {interactive && !dragging ? rects.map((r) => {
            const s = sections.find((x) => x.id === r.id);
            return s ? <Handle key={r.id} id={r.id} top={r.top} label={labelOf(s.type)} active={r.id === selectedId}/> : null;
          }) : null}
          {dragging ? (slot === null
            ? null
            : <div className="bx-insert" style={{ top: indicatorY(rects, slot) }} aria-hidden="true"><span>Thả vào đây</span></div>) : null}
          {dragging && !rects.length ? <div className="bx-empty-drop">Thả component vào trang trống</div> : null}
        </div>
      </div>
    </div>
  );
}

function Handle({ id, top, label, active }: { id: string; top: number; label: string; active: boolean }) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: `sec:${id}` });
  return (
    <button ref={setNodeRef} type="button" className={`bx-handle${active ? " active" : ""}`} style={{ top: Math.max(0, top) + 6, opacity: isDragging ? 0.4 : undefined }}
      aria-label={`Kéo để di chuyển ${label}`} title={`Kéo để di chuyển ${label}`} {...attributes} {...listeners}>⋮⋮</button>
  );
}

/** the dragged item as shown under the pointer */
export function DragChip({ label }: { label: string }) { return <div className="bx-dragchip">{label}</div>; }

export function useRectsState() {
  const [rects, setRects] = useState<SectionRect[]>([]);
  return [rects, setRects] as const;
}
