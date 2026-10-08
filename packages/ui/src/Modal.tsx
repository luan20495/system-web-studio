"use client";
import { useEffect, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]):not([type=hidden]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

/**
 * A modal dialog: role=dialog + aria-modal, Escape closes, Tab / Shift+Tab stay INSIDE the dialog (focus trap), the page behind does not scroll (scroll lock), and focus returns to the element that opened it.
 * The overlay class is `adminModal`, NOT `modal`: globals.css already defines `.modal` as the Studio dialog BOX (width 520, dark panel, light text), which squeezed this overlay to 520px and made the dialog look broken.
 */
export function Modal({ label, onClose, children }: { label: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null;
    const root = ref.current;
    root?.querySelector<HTMLElement>("input:not([readonly]), select, textarea, button")?.focus();
    const overflow = document.body.style.overflow; document.body.style.overflow = "hidden";
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") { onClose(); return; }
      if (e.key !== "Tab" || !root) return;
      const items = [...root.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((el) => el.offsetParent !== null || el === document.activeElement);
      if (!items.length) { e.preventDefault(); return; }
      const first = items[0], last = items[items.length - 1], at = document.activeElement as HTMLElement | null;
      if (e.shiftKey && (at === first || !root.contains(at))) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && (at === last || !root.contains(at))) { e.preventDefault(); first.focus(); }
    };
    document.addEventListener("keydown", onKey);
    return () => { document.removeEventListener("keydown", onKey); document.body.style.overflow = overflow; prev?.focus?.(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  if (typeof document === "undefined") return null;
  return createPortal(<div className="adminModal" role="dialog" aria-modal="true" aria-label={label} ref={ref}>{children}</div>, document.body);
}
