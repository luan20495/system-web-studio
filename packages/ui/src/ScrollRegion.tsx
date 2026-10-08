"use client";
/**
 * A card (or any block) whose content may be wider than the screen: it scrolls sideways INSIDE itself and — only while it really overflows — becomes a keyboard-focusable stop (tabindex=0), so a keyboard
 * user can focus it and scroll with the arrow keys (axe: scrollable-region-focusable). When nothing overflows it adds no tab stop. If it has a `.cardHead h2` it is also a named `group` (not a landmark) labelled by that heading
 * (aria-labelledby, so the name is never duplicated text and follows the heading); a card without a title is only focusable (no landmark with a generic, repeated name).
 */
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { useOverflow } from "./useOverflow";

export function ScrollRegion({ className, children }: { className?: string; children: ReactNode }) {
  const ref = useRef<HTMLElement>(null); const uid = useId(); const scrolls = useOverflow(ref, "x"); const [labelledBy, setLabelledBy] = useState<string | undefined>();
  useEffect(() => {
    const h = scrolls ? ref.current?.querySelector<HTMLElement>(":scope > .cardHead h2") : null;
    if (h) { if (!h.id) h.id = `${uid}-h`; setLabelledBy(h.id); } else setLabelledBy(undefined);
  }, [scrolls, uid, children]);
  return <section ref={ref} className={className} {...(scrolls ? { tabIndex: 0, ...(labelledBy ? { role: "group", "aria-labelledby": labelledBy } : {}) } : {})}>{children}</section>;
}
