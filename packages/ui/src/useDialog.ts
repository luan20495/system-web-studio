"use client";

import { useEffect, useId, useRef } from "react";
import { dialogOpener } from "./focus";

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

/**
 * Modal behaviour for drawers/dialogs: focus moves in on open, Tab/Shift+Tab stay inside, Escape closes (when allowed),
 * and focus returns to the opener on close. Returns props to spread on the dialog element.
 */
export function useDialog(title: string, onClose: (() => void) | null) {
  const ref = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  const titleId = useId();

  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    const opener = dialogOpener();
    const items = () => Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((el) => el.getClientRects().length > 0);
    (items()[0] ?? node).focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && closeRef.current) { event.preventDefault(); event.stopPropagation(); closeRef.current(); return; }
      if (event.key !== "Tab") return;
      const f = items();
      if (!f.length) { event.preventDefault(); node.focus(); return; }
      const first = f[0], last = f[f.length - 1], active = document.activeElement;
      if (event.shiftKey && (active === first || !node.contains(active))) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && (active === last || !node.contains(active))) { event.preventDefault(); first.focus(); }
    };
    document.addEventListener("keydown", onKey, true);
    return () => { document.removeEventListener("keydown", onKey, true); opener?.focus?.(); };
  }, []);

  return { ref, titleId, props: { ref, role: "dialog" as const, "aria-modal": true as const, "aria-labelledby": titleId, tabIndex: -1 }, title };
}
