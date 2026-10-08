"use client";
import { useRef } from "react";

/**
 * The overlay stack of the page. Every modal surface registers here so that
 *  - only the TOP overlay reacts to Escape (a nested dialog closes alone, never the whole stack);
 *  - the page scroll lock is taken by the first overlay and given back by the last one, restoring the value that was there BEFORE the first one
 *    (closing a lower overlay first, or in any order, can no longer leave `body{overflow:hidden}` behind or release it too early).
 */
let seq = 0;
const stack: number[] = [];
let saved = "";

export type OverlayHandle = { id: number; isTop: () => boolean; release: () => void };

export function acquireOverlay(): OverlayHandle {
  const id = ++seq;
  if (stack.length === 0 && typeof document !== "undefined") { saved = document.body.style.overflow; document.body.style.overflow = "hidden"; }
  stack.push(id);
  return {
    id,
    isTop: () => stack[stack.length - 1] === id,
    release: () => {
      const i = stack.indexOf(id); if (i < 0) return;
      stack.splice(i, 1);
      if (stack.length === 0 && typeof document !== "undefined") document.body.style.overflow = saved;
    },
  };
}

/** how many overlays are open (tests, diagnostics) */
export const overlayDepth = (): number => stack.length;

/**
 * "Click outside closes" done right: the dialog closes only when the press STARTED and ENDED on the backdrop itself.
 * Selecting text inside a field and releasing the mouse over the backdrop therefore never dismisses it (and never loses what was typed).
 * Spread the result on the backdrop element; `enabled=false` makes both handlers inert.
 */
export function useBackdropClose(onClose: () => void, enabled: boolean) {
  const down = useRef(false);
  return {
    onMouseDown: (e: React.MouseEvent) => { down.current = enabled && e.target === e.currentTarget; },
    onMouseUp: (e: React.MouseEvent) => { const hit = down.current && enabled && e.target === e.currentTarget; down.current = false; if (hit) onClose(); },
  };
}
