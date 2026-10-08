"use client";
/**
 * "Thêm" menu of the AI / Code workspace top bar on a phone (M-011). Below 768 px the text buttons (Website, Phiên bản, Tệp, Chia sẻ, Cài đặt / Lịch sử, Thư viện, IDE, Máy chủ)
 * are hidden by the shared responsive rules and used to have NO replacement: the person could not open versions, files or members on a phone.
 * Menu button pattern (APG): `aria-haspopup="menu"`, Arrow keys / Home / End move between items, Escape closes and returns focus to the button, Tab or a click outside closes.
 * An item that is not available stays focusable (`aria-disabled`) and shows WHY as visible text (never title-only, M-031). Hidden from 768 px up (CSS, builder.css) where the buttons are shown.
 */
import { useEffect, useId, useRef, useState } from "react";

export type MenuItem = { key: string; label: string; onSelect: () => void; unavailable?: boolean; reason?: string };

export function OverflowMenu({ label = "Thêm thao tác", items }: { label?: string; items: MenuItem[] }) {
  const [open, setOpen] = useState(false);
  const id = useId(); const root = useRef<HTMLDivElement>(null); const button = useRef<HTMLButtonElement>(null);
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  useEffect(() => {
    if (!open) return;
    refs.current.find((x) => x && x.getAttribute("aria-disabled") !== "true")?.focus();
    const outside = (e: PointerEvent) => { if (!root.current?.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("pointerdown", outside, true);
    return () => document.removeEventListener("pointerdown", outside, true);
  }, [open]);
  const close = (refocus: boolean) => { setOpen(false); if (refocus) requestAnimationFrame(() => button.current?.focus()); };
  function onKey(e: React.KeyboardEvent) {
    const list = refs.current.filter((x): x is HTMLButtonElement => !!x);
    const i = list.findIndex((x) => x === document.activeElement);
    if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); close(true); }
    else if (e.key === "ArrowDown") { e.preventDefault(); list[(i + 1) % list.length]?.focus(); }
    else if (e.key === "ArrowUp") { e.preventDefault(); list[(i - 1 + list.length) % list.length]?.focus(); }
    else if (e.key === "Home") { e.preventDefault(); list[0]?.focus(); }
    else if (e.key === "End") { e.preventDefault(); list[list.length - 1]?.focus(); }
    else if (e.key === "Tab") setOpen(false);
  }
  return (
    <div className="wsMore" ref={root} onKeyDown={onKey}>
      <button ref={button} type="button" className="button wsMoreBtn" aria-haspopup="menu" aria-expanded={open} aria-controls={open ? id : undefined} aria-label={label} title={label} onClick={() => setOpen((o) => !o)}>
        <span aria-hidden="true">⋯</span>
      </button>
      {open ? (
        <div id={id} role="menu" aria-label={label} className="wsMoreMenu">
          {items.map((it, n) => (
            <button key={it.key} ref={(el) => { refs.current[n] = el; }} type="button" role="menuitem" className="wsMoreItem" aria-disabled={it.unavailable ? true : undefined}
              onClick={() => { if (it.unavailable) return; close(false); it.onSelect(); }}>
              <span>{it.label}</span>{it.unavailable && it.reason ? <small>{it.reason}</small> : null}
            </button>))}
        </div>) : null}
    </div>
  );
}
