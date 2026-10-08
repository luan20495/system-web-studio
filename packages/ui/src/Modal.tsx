"use client";
import { useEffect, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { tabbables, trapTab } from "./focus";
import { acquireOverlay, useBackdropClose } from "./overlay";

const INITIAL = "input:not([readonly]), select, textarea, button";

/**
 * A modal dialog: role=dialog + aria-modal, Escape closes, Tab / Shift+Tab stay INSIDE the dialog (focus trap), the page behind does not scroll (scroll lock), and focus returns to the element that opened it.
 * The overlay class is `adminModal`, NOT `modal`: globals.css already defines `.modal` as the Studio dialog BOX (width 520, dark panel, light text), which squeezed this overlay to 520px and made the dialog look broken.
 *
 * Dismissal rules (M-010):
 *  - `dismissible={false}` — or any element inside the dialog carrying `aria-busy="true"` (the submit button while the request is in flight) — makes Escape and the backdrop do nothing: the dialog cannot be dismissed
 *    while its action is running (an in-flight "create account" no longer loses its result).
 *  - Escape closes only the TOP modal; the backdrop (`closeOnBackdrop`, default off: forms must not lose typed data to a stray click) closes only when the press started AND ended on the backdrop.
 *  - `onClose` / `dismissible` are read through a ref: the key handler never runs a stale closure.
 *  - the scroll lock is shared by the overlay stack (see overlay.ts): correct for nesting in any closing order.
 * Initial focus: the element marked `data-autofocus`, else the first visible text field / select / button. Never the visually hidden native select of a `Picker` (tabindex=-1 / `.srOnly`).
 */
export function Modal({ label, onClose, children, dismissible = true, closeOnBackdrop = false }: { label: string; onClose: () => void; children: ReactNode; dismissible?: boolean; closeOnBackdrop?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  const latest = useRef({ onClose, dismissible });
  latest.current = { onClose, dismissible };
  const canClose = () => latest.current.dismissible && !ref.current?.querySelector('[aria-busy="true"]');
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null;
    const root = ref.current;
    const overlay = acquireOverlay();
    if (root) {
      const items = tabbables(root);
      const target = root.querySelector<HTMLElement>("[data-autofocus]") ?? items.find((el) => el.matches(INITIAL)) ?? null;
      if (target) target.focus(); else { root.tabIndex = -1; root.focus(); }
    }
    const onKey = (e: KeyboardEvent) => {
      if (!overlay.isTop()) return;
      if (e.key === "Escape") {
        if (e.defaultPrevented) return;                      // a Picker / combobox inside already used this Escape
        if (canClose()) latest.current.onClose(); else e.preventDefault();
        return;
      }
      if (root) trapTab(e, root);
    };
    document.addEventListener("keydown", onKey);
    return () => { document.removeEventListener("keydown", onKey); overlay.release(); if (prev?.isConnected) prev.focus?.(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const backdrop = useBackdropClose(() => { if (canClose()) latest.current.onClose(); }, closeOnBackdrop);
  if (typeof document === "undefined") return null;
  return createPortal(<div className="adminModal" role="dialog" aria-modal="true" aria-label={label} ref={ref} {...backdrop}>{children}</div>, document.body);
}
