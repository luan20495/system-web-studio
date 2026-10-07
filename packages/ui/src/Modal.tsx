"use client";
import { useEffect, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";

/** The overlay class is `adminModal`, NOT `modal`: globals.css already defines `.modal` as the Studio dialog BOX (width 520, dark panel, light text), which squeezed this overlay to 520px and made the text unreadable. Dialog rendered at the document root (so no card/overflow can clip it); Escape closes it and focus moves inside. */
export function Modal({ label, onClose, children }: { label: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null;
    ref.current?.querySelector<HTMLElement>("input:not([readonly]), select, textarea, button")?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    return () => { document.removeEventListener("keydown", onKey); prev?.focus?.(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  if (typeof document === "undefined") return null;
  return createPortal(<div className="adminModal" role="dialog" aria-modal="true" aria-label={label} ref={ref}>{children}</div>, document.body);
}
