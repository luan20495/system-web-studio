"use client";
/**
 * Dialog behaviour for the Studio's own overlays (drawers, the publish modal, the review dialog) on top of the SHARED overlay stack (`acquireOverlay`, `trapTab`, `tabbables`
 * from @xweb/ui). Same props contract as the old `useDialog`, but:
 *  - only the TOP overlay reacts to Escape and to Tab. The old hook listened in the capture phase and called `stopPropagation`, so a confirmation opened from a drawer or from the
 *    publish dialog (the shared `confirm()`, a Modal on the same stack) lost its Escape to the drawer and its Tab to the drawer's trap (M-017 / M-019 / M-023);
 *  - the page scroll lock is shared by the stack (restored in any closing order);
 *  - a focus trap that sees only controls a person could use (`tabbables`).
 * Pass `null` as `onClose` to make the dialog un-dismissible with Escape (a request is in flight).
 */
import { useEffect, useId, useRef } from "react";
import { acquireOverlay, tabbables, trapTab } from "@xweb/ui";

export function useOverlayDialog(title: string, onClose: (() => void) | null) {
  const ref = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  const titleId = useId();
  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const overlay = acquireOverlay();
    (tabbables(node)[0] ?? node).focus();
    const onKey = (e: KeyboardEvent) => {
      if (!overlay.isTop()) return;
      if (e.key === "Escape") {
        if (e.defaultPrevented) return;                 // a control inside (a select, a combobox) already used this Escape
        e.preventDefault(); closeRef.current?.();
        return;
      }
      trapTab(e, node);
    };
    document.addEventListener("keydown", onKey);
    return () => { document.removeEventListener("keydown", onKey); overlay.release(); if (opener?.isConnected) opener.focus?.(); };
  }, []);
  return { ref, titleId, props: { ref, role: "dialog" as const, "aria-modal": true as const, "aria-labelledby": titleId, tabIndex: -1 }, title };
}
