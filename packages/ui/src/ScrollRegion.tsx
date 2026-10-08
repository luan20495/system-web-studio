"use client";
/**
 * A card (or any block) whose content may be wider than the screen: it scrolls sideways INSIDE itself, and — only while it really overflows — it becomes a keyboard-focusable named region
 * (tabindex=0, role=region, aria-label), so a keyboard user can focus it and scroll with the arrow keys (axe: scrollable-region-focusable). When nothing overflows it adds no tab stop.
 */
import { useEffect, useRef, useState, type ReactNode } from "react";

export function ScrollRegion({ className, label, children }: { className?: string; label: string; children: ReactNode }) {
  const ref = useRef<HTMLElement>(null); const [scrolls, setScrolls] = useState(false);
  useEffect(() => {
    const el = ref.current; if (!el) return;
    const check = () => setScrolls(el.scrollWidth > el.clientWidth + 1);
    check(); const ro = typeof ResizeObserver !== "undefined" ? new ResizeObserver(check) : null; ro?.observe(el);
    const mo = typeof MutationObserver !== "undefined" ? new MutationObserver(check) : null; mo?.observe(el, { childList: true, subtree: true });
    window.addEventListener("resize", check);
    return () => { ro?.disconnect(); mo?.disconnect(); window.removeEventListener("resize", check); };
  }, []);
  return <section ref={ref} className={className} {...(scrolls ? { tabIndex: 0, role: "region", "aria-label": `${label} (cuộn ngang được)` } : {})}>{children}</section>;
}
