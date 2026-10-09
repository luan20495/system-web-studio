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
 *    If the heading is not rendered yet (a loading state, a lazily fetched section: M-053), it is looked for again when the content changes (MutationObserver, for up to 5 s) and at 300 ms; the focus
 *    is upgraded from <main> to the <h1> the first time it exists, but only if the user has not moved it meanwhile.
 */
import { useEffect, useRef, type RefObject } from "react";
import { useOverflow } from "./useOverflow";

/** the element that should receive focus after a route change: the first <h1> inside `main`, else `main` itself. Pure over the DOM, unit-testable. */
export function focusTargetFor(main: HTMLElement): HTMLElement {
  return visibleHeading(main) ?? main;
}
/** the first <h1> that is on screen: while a lazily fetched section loads React keeps the PREVIOUS page in the DOM with `display:none`, and its <h1> cannot take focus */
export function visibleHeading(main: HTMLElement): HTMLElement | null {
  for (const h of main.querySelectorAll<HTMLElement>("h1")) if (typeof h.checkVisibility !== "function" || h.checkVisibility()) return h;
  return null;
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
    let t = 0, stop = 0, mo: MutationObserver | null = null;
    const done = () => { window.clearTimeout(t); window.clearTimeout(stop); mo?.disconnect(); };
    /** true when there is nothing left to do (the heading got the focus, or the user moved on) */
    const upgrade = (): boolean => {
      const active = document.activeElement;
      if (active && active !== main && active !== document.body) return true;       // the user moved on: leave them alone
      const h = visibleHeading(main); if (!h) return false;
      focusProgrammatically(h); return true;
    };
    if (target === main) {
      t = window.setTimeout(() => { if (upgrade()) done(); }, 300);
      if (typeof MutationObserver !== "undefined") { mo = new MutationObserver(() => { if (upgrade()) done(); }); mo.observe(main, { childList: true, subtree: true }); stop = window.setTimeout(done, 5000); }
    }
    return done;
  }, [routeKey]);
  return { ref, id: "main", tabIndex: scrolls ? 0 : -1 };
}
