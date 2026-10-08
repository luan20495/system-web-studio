"use client";
/**
 * The <main> landmark of a portal shell (M-025). One hook gives it everything the old `<main id="main" tabIndex={0}>` lacked:
 *
 *   const main = useMain(seg.join("/"));           // the route key: any value that changes when the page changes
 *   <SkipLink/> ... <main className="page" {...main}>{route}</main>
 *
 *  - `id="main"`: the target of the skip link.
 *  - `tabIndex`: 0 ONLY while the region really scrolls (a keyboard user needs a stop to scroll it with the arrow keys); -1 otherwise, so it is not a pointless
 *    Tab stop (it used to be one on every page, even when nothing scrolled) but can still receive focus from the skip link / the route change.
 *  - on a ROUTE CHANGE (not on first load, where the browser's own start-of-document focus is right): scroll to the top and move focus to the page's <h1>
 *    (made programmatically focusable), else to <main>. Without this, after following a link the focus stays on the now-gone link / <body> and a screen reader says nothing.
 *    If the heading is not rendered yet (a loading state), a second look 300 ms later upgrades the focus from <main> to the <h1>, but only if the user has not moved it meanwhile.
 */
import { useEffect, useRef, type RefObject } from "react";
import { useOverflow } from "./useOverflow";

/** the element that should receive focus after a route change: the first <h1> inside `main`, else `main` itself. Pure over the DOM, unit-testable. */
export function focusTargetFor(main: HTMLElement): HTMLElement {
  return main.querySelector<HTMLElement>("h1") ?? main;
}

/** focus without scrolling the page twice, giving non-focusable elements a tabindex of -1 (programmatic focus only; it never joins the Tab order) */
export function focusProgrammatically(el: HTMLElement): void {
  if (!el.hasAttribute("tabindex") && el.tabIndex < 0) el.setAttribute("tabindex", "-1");
  el.focus({ preventScroll: true });
}

export function useMain(routeKey: string): { ref: RefObject<HTMLElement | null>; id: "main"; tabIndex: 0 | -1 } {
  const ref = useRef<HTMLElement>(null);
  const scrolls = useOverflow(ref, "y");
  const first = useRef(true);
  useEffect(() => {
    if (first.current) { first.current = false; return; }
    const main = ref.current; if (!main) return;
    main.scrollTo?.({ top: 0 });
    const target = focusTargetFor(main); focusProgrammatically(target);
    let t = 0;
    if (target === main) t = window.setTimeout(() => {
      const active = document.activeElement;
      if (active && active !== main && active !== document.body) return;       // the user moved on: leave them alone
      const h = main.querySelector<HTMLElement>("h1"); if (h) focusProgrammatically(h);
    }, 300);
    return () => window.clearTimeout(t);
  }, [routeKey]);
  return { ref, id: "main", tabIndex: scrolls ? 0 : -1 };
}
