"use client";
import { useEffect, useState, type RefObject } from "react";

/** True only while the element really scrolls along `axis` (so it can become a keyboard stop exactly then). Measured after layout, throttled to one read per frame; follows resize and content changes. */
export function useOverflow(ref: RefObject<HTMLElement | null>, axis: "x" | "y" = "x"): boolean {
  const [over, setOver] = useState(false);
  useEffect(() => {
    const el = ref.current; if (!el) return; let raf = 0;
    const measure = () => { raf = 0; setOver(axis === "x" ? el.scrollWidth > el.clientWidth + 1 : el.scrollHeight > el.clientHeight + 1); };
    const schedule = () => { if (!raf) raf = requestAnimationFrame(measure); };
    measure();
    const ro = typeof ResizeObserver !== "undefined" ? new ResizeObserver(schedule) : null; ro?.observe(el);
    const mo = typeof MutationObserver !== "undefined" ? new MutationObserver(schedule) : null; mo?.observe(el, { childList: true, subtree: true });
    window.addEventListener("resize", schedule);
    return () => { if (raf) cancelAnimationFrame(raf); ro?.disconnect(); mo?.disconnect(); window.removeEventListener("resize", schedule); };
  }, [ref, axis]);
  return over;
}
